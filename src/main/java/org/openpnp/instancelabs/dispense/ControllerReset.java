package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BR-02: whether the controller was reset (a power cycle), and so whether the position it reports can be trusted.
 *
 * <p>Stock Marlin boots believing X 0, Y 0, Z 0 and A/B 720 (Z_MIN_POS 0, J_MAX_POS 720; OpenPnP's home sets
 * G92 A0 B0 afterwards). Right after every connect the engine reads M114 once: X, Y and Z at 0 with A or B still at
 * 720 means the controller was reset, and the head may be anywhere (one of them at 720 is enough: a bench tool may
 * have zeroed the screw with G92 B0 without homing). A reconnect of a powered controller keeps its position and its B
 * step count.
 * Only a home makes an unknown position known again; a reconnect never does, whatever the controller says then (the
 * believed position may have been jogged meanwhile).</p>
 *
 * <p>The B step count restarts at a reset, so the screw start angle (I11) and the camera records of partial turns are
 * checked against {@link #countEpochMs()}: the last detected reset, or the last connect whose position could not be
 * read (it may have been one).</p>
 *
 * <p>Both outlive an OpenPnP restart (a deploy, a restart to recover the link): every reset, unread connect and the
 * home that ends an unknown position is journaled ({@link Event}, the engine's events table), and the next engine
 * reads the newest of each back at its start. A restart after a reset therefore keeps the screw count's epoch, and an
 * unknown position stays unknown until a home even if the controller was jogged off the boot position meanwhile.
 * A reset the engine never saw (OpenPnP stopped, the controller power-cycled and homed by another tool before
 * OpenPnP connected again) cannot be detected. The journal lives in the config folder's database: an OpenPnP started
 * on another copy of the config does not see it.</p>
 *
 * <p>The first start of a journaling engine on a database an earlier build used ({@link #EVENT_JOURNAL} absent) may
 * follow a power cycle that build saw and nobody answered: it dates the screw count once ({@link #EVENT_COUNT_EPOCH}),
 * so I11 asks for the start angle; the position is read at the connect as usual.</p>
 *
 * <p>The screw's motor switched off dates the screw count as a reset does ({@link #EVENT_MOTOR_OFF}): unpowered, the
 * screw turns freely (the bench measured 118 deg, then 226 deg after a motor-off), so the step count no longer gives
 * its phase, and I11 wants the angle measured again (no confirm). The motor goes off at a STOP whose DISABLE_COMMAND
 * sends M18 B, at the engine's own stop sequence (its M18 B), and when the controller's idle timeout passes without a
 * move (Marlin's DEFAULT_STEPPER_DEACTIVE_TIME): that one is dated at the last move the engine knows of plus the
 * timeout, found when the state is next read, and journaled once. Marlin restarts its idle clock only while a move is
 * queued, of any axis, and a screw move switches the motor on again: so does this clock (the engine's streams, the
 * API's G-code passthrough, OpenPnP's own moves and homes), counting from a move's acknowledgement. Counting a line
 * that queued nothing (a feed-only G1, a G1 to where the axis is) would date the switch-off late, and the runs between
 * the real switch-off and that date would use a lost angle: so only lines that move an axis count. A move it did not
 * hear of dates it early: the angle is asked for sooner (and a set then needs a turn, which restarts both clocks). The
 * last move is journaled now and then ({@link #EVENT_SCREW_MOTION}), so the idle clock outlives a restart that left
 * the motor on.</p>
 *
 * <p>The il/safety firmware's host watchdog switches the screw's driver off when it trips, and its M990 report keeps
 * the trip until the host arms it again (never, without the keepalive): a reported trip goes on record once per arm
 * ({@link #EVENT_WATCHDOG_TRIP}, {@link #EVENT_WATCHDOG_ARMED}); a reset ends it too (the firmware boots without it).</p>
 *
 * <p>Journal writes happen after the lock is released, one writer at a time, in the order the changes were made: a
 * status poll writing a switch-off never makes the stream's next move wait for the insert. Changes made where no I/O
 * may happen (a stream's moves and arms, a trip heard on the reader thread) are in memory at once and written by
 * {@link #flush}.</p>
 *
 * <p>After a switch-off or a reset a start angle can only be set once the screw has turned since (the dot pair that
 * measures it turns it; {@link #whyNoAngle}): the angle shown before it, one click away, would otherwise clear I11.</p>
 *
 * <p>BR-97: the see-saw's levelling before a home stays within 32 mm of where the position became unknown
 * ({@link #levelReference}): Marlin's boot Z 0 after a detected reset, also after a restart (it is on record, and so
 * is a home's start, which ends it: {@link #EVENT_HOME_STARTED}).</p>
 * <p>BR-93: a link to the controller lost unasked (the board left USB, a read or a write failed) may have been a reset
 * too (a board that leaves USB has often lost power): {@link #linkLost} makes the position unknown and dates the screw
 * count and the motor's switch-off, as an unread connect and a switch-off do, until the attested home.</p>
 *
 * <p>Times are epoch ms from an injectable clock. Thread safe.</p>
 */
public final class ControllerReset {
    /**
     * What the home after a reset does, and what must be true before it: the board's G28 homes X, then Y, at the
     * current height (no Z clearance: Z_HOMING_HEIGHT and HOME_Z_FIRST off), then Z at its Z_SAFE_HOMING point. The
     * see-saw is levelled first, both tips up (the process engineer, 11-04). BR-96: whoever checks it (an agent's
     * cameras or a person) attests it with the home ({@link #attest}).
     */
    public static final String HOME_CHECK = "the home first drives the head to its homing corner (X, then Y) at the "
            + "height the tools are at now, then homes Z there: before Home, nothing is under either tool (N1 or the "
            + "needle) or in their way to that corner, and the see-saw is levelled, both tips up (by hand, or by "
            + "Z-only moves through the G-code passthrough)";
    /** Where stock Marlin believes A and B are at power-up (J_MAX_POS), until a G92. */
    public static final double BOOT_B_DEG = 720;
    /** Journal codes (the events table's {@code code}): a connect read the boot position. */
    public static final String EVENT_RESET = "CONTROLLER_RESET";
    /** A connect could not read the position, or the link was lost (BR-93): either may have been a reset. */
    public static final String EVENT_UNREAD = "POSITION_UNREAD";
    /** A home made an unknown position known again. */
    public static final String EVENT_KNOWN = "POSITION_KNOWN";
    /** The screw count may have restarted, the position is not concerned (the cutover to the journal). */
    public static final String EVENT_COUNT_EPOCH = "SCREW_COUNT_EPOCH";
    /** The first start of an engine that journals: a database without it was used by an earlier build. */
    public static final String EVENT_JOURNAL = "RESET_JOURNAL";
    /** The screw's motor was switched off (M18 B, the controller's idle timeout): the screw may have turned. */
    public static final String EVENT_MOTOR_OFF = "SCREW_MOTOR_OFF";
    /**
     * A move with the screw's motor on (the last one, journaled now and then): the controller's idle clock runs from
     * it, and the screw turned after the last switch-off (only a turn switches the motor on).
     */
    public static final String EVENT_SCREW_MOTION = "SCREW_MOTION";
    /**
     * The screw's moves go on record at most this often: a restart may then date the last one this much early, which
     * only brings the idle switch-off sooner.
     */
    public static final long MOTION_JOURNAL_MS = 10 * 60_000L;
    /** The host watchdog tripped: on record until it is armed again (its M990 report keeps the trip until then). */
    public static final String EVENT_WATCHDOG_TRIP = "HOST_WATCHDOG_TRIP";
    /** The host watchdog was armed again after a trip on record: the firmware forgot the trip. */
    public static final String EVENT_WATCHDOG_ARMED = "HOST_WATCHDOG_ARMED";
    /**
     * The home after a detected reset moved the Z frame (K by twice the shift, the process engineer 11-04), or after a
     * connect whose position was not read (it may have been one): the needle frame and its touch-off are recorded again
     * (I4).
     */
    public static final String EVENT_FRAME_MOVED = "Z_FRAME_MOVED";
    /** BR-97: a home started: its G28 may have moved the controller's frame, even if the home ended unhomed. */
    public static final String EVENT_HOME_STARTED = "HOME_STARTED";
    /** BR-93: the way back after a lost link (the refusals while it is lost). */
    public static final String LINK_LOST_REMEDY = "once the board is back on USB: Stop, then Start (or restart "
            + "il-openpnp), then Home as after a reset";
    private static final double TOL = 1e-3;
    private static final Pattern WORD = Pattern.compile("(?:^|\\s)([A-Z]):(-?\\d+(?:\\.\\d+)?)");

    /** One journaled change: its code (EVENT_*), when, and the reason in plain words. */
    public static final class Event {
        public final String code;
        public final long atMs;
        public final String message;

        public Event(String code, long atMs, String message) {
            this.code = code;
            this.atMs = atMs;
            this.message = message;
        }
    }

    private final LongSupplier clock;
    private final Consumer<Event> journal;
    /** The controller's idle timeout now (ms), or null: no idle switch-off is counted. */
    private final LongSupplier idleOffMs;
    private boolean positionKnown;
    private String unknownWhy;
    private boolean detected;
    private Long detectedAtMs;
    private Map<String, Double> reported;
    private Long countEpochMs;
    /** The last time the screw's motor was switched off, and why ("the screw motor was switched off at ..."). */
    private Event motorOff;
    /** The last move (any axis) while the screw's motor is on: the idle clock; null while it is off. */
    private Long lastMoveMs;
    /** The screw's last turn this engine knows of (also read back), or null. */
    private Long screwTurnedAtMs;
    /** The last move put on record, or null: the next move goes on record at once. */
    private Long journaledMoveMs;
    /** A host watchdog trip on record that no arm or reset has ended since (its M990 still reports it), or null. */
    private Long watchdogTripAtMs;
    /**
     * A detected reset (or an unread connect, or one not read yet: maybe one) no home has followed yet: the next home
     * moves the Z frame.
     */
    private boolean resetUnhomed;
    /** When the last home after a detected reset moved the Z frame, or null. */
    private Long frameMovedAtMs;
    /** BR-96: who attests the next home, and their evidence (JSON text, or null); null: nobody. */
    private String attestedBy;
    private String attestedEvidence;
    /** The last home that ended an unknown position on someone's word: {by, evidence, atMs}; null: none here. */
    private Map<String, Object> lastAttestation;
    /**
     * BR-97: the levelling's reference ({@link #levelReference}): 0 after a detected reset (also read back after a
     * restart); null: the next levelling call's Z. A Stop and Start keeps it, and so does a lost link (BR-93).
     */
    private Double levelRefZ;
    /** BR-93: the last lost link put on record (the driver's time of it), or null. */
    private Long linkLostAtMs;
    /** The changes made and not written yet (under this lock), in their order. */
    private final List<Event> toWrite = new ArrayList<>();
    /** One journal writer at a time, outside this lock (a stream's move never waits for an insert). */
    private final Object writeLock = new Object();

    /** Without a journal: nothing outlives this engine (tests). */
    public ControllerReset(LongSupplier clock, boolean enabledAtStart, boolean homedAtStart) {
        this(clock, enabledAtStart, homedAtStart, null, null);
    }

    /** Without the controller's idle timeout: only the M18s switch the screw's motor off (tests). */
    public ControllerReset(LongSupplier clock, boolean enabledAtStart, boolean homedAtStart, List<Event> history,
            Consumer<Event> journal) {
        this(clock, enabledAtStart, homedAtStart, history, journal, null);
    }

    /**
     * @param clock epoch ms (null = the system clock)
     * @param enabledAtStart whether OpenPnP was connected when the service started (that connect was not seen)
     * @param homedAtStart whether it was homed then
     * @param history what earlier engines journaled: the newest event of each code, oldest first (null = none)
     * @param journal where this engine's changes go, to be read back after a restart (null = nowhere)
     * @param idleOffMs the controller's idle timeout of the moment, ms (the dispense settings); null = none
     */
    public ControllerReset(LongSupplier clock, boolean enabledAtStart, boolean homedAtStart, List<Event> history,
            Consumer<Event> journal, LongSupplier idleOffMs) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.journal = journal;
        this.idleOffMs = idleOffMs;
        positionKnown = true;
        Long tripAt = null, tripEndedAt = null, resetAt = null, knownAt = null;
        for (Event e : history == null ? Collections.<Event> emptyList() : history) {
            // the times what follows the loop is decided from
            if (EVENT_RESET.equals(e.code) || EVENT_UNREAD.equals(e.code)) {
                resetAt = later(resetAt, e.atMs); // a reset, or a connect that may have been one
            }
            if (EVENT_KNOWN.equals(e.code)) {
                knownAt = e.atMs;
            }
            else if (EVENT_FRAME_MOVED.equals(e.code)) {
                frameMovedAtMs = e.atMs;
            }
            else if (EVENT_WATCHDOG_TRIP.equals(e.code)) {
                tripAt = e.atMs;
            }
            else if (EVENT_WATCHDOG_ARMED.equals(e.code) || EVENT_UNREAD.equals(e.code)) {
                tripEndedAt = later(tripEndedAt, e.atMs); // armed, or maybe a reset: the firmware forgot it
            }
            // the state
            if (EVENT_RESET.equals(e.code)) {
                tripEndedAt = later(tripEndedAt, e.atMs); // it boots without the trip
                detectedAtMs = e.atMs; // what it reported is not journaled: reported stays null
                unknown(e.message, e.atMs);
                levelRefZ = 0.0; // BR-97: the boot Z, until a newer possible reset, home or home's start clears it
                stopIdleClock(e.atMs); // the controller boots with its motors off
            }
            else if (EVENT_MOTOR_OFF.equals(e.code)) {
                switchedOff(e);
            }
            else if (EVENT_SCREW_MOTION.equals(e.code)) {
                // a move after the last switch-off and reset: the screw turned since (only a turn switches the motor
                // on), and the idle clock runs on from it
                if ((motorOff == null || e.atMs > motorOff.atMs) && (detectedAtMs == null || e.atMs > detectedAtMs)) {
                    lastMoveMs = e.atMs;
                    journaledMoveMs = e.atMs;
                    screwTurnedAtMs = e.atMs;
                }
            }
            else if (EVENT_COUNT_EPOCH.equals(e.code)) {
                countEpochMs = later(countEpochMs, e.atMs);
            }
            else if (EVENT_UNREAD.equals(e.code)) {
                unknown(e.message, e.atMs);
            }
            else if (EVENT_KNOWN.equals(e.code)) {
                positionKnown = true;
                unknownWhy = null;
                levelRefZ = null;
            }
            else if (EVENT_HOME_STARTED.equals(e.code)) {
                levelRefZ = null;
            }
        }
        watchdogTripAt(tripAt, tripEndedAt);
        resetUnhomed = resetAt != null && (knownAt == null || resetAt > knownAt); // homed after a restart, if at all
        if (!positionKnown) {
            unknownWhy += "; no home has followed (OpenPnP restarted meanwhile)";
        }
        if (homedAtStart) {
            // OpenPnP clears its homed flag at every disable and a reset needs a reconnect: this home came after it
            homed();
        }
        else if (enabledAtStart) {
            startedUnhomed();
        }
    }

    /**
     * The machine was started, and not homed, before the engine saw the connect: the position is unknown until a home,
     * and that home moves the Z frame, as after an unread connect (a home queued first leaves the read undone). In
     * memory only: the engine reads the controller next ({@link #connected}).
     */
    public synchronized void startedUnhomed() {
        resetUnhomed = true;
        if (positionKnown) {
            positionKnown = false;
            unknownWhy = "the machine was started but not homed when the engine started: its position was never "
                    + "checked";
        }
    }

    /** The logical position of an M114 reply (the words before "Count"), axis letter → value; empty if none. */
    public static Map<Character, Double> parsePosition(List<String> m114) {
        Map<Character, Double> p = new LinkedHashMap<>();
        if (m114 == null) {
            return p;
        }
        for (String line : m114) {
            if (line == null) {
                continue;
            }
            int count = line.indexOf("Count");
            String head = count >= 0 ? line.substring(0, count) : line;
            Matcher m = WORD.matcher(head);
            while (m.find()) {
                p.put(m.group(1).charAt(0), Double.parseDouble(m.group(2)));
            }
            if (p.containsKey('X') && p.containsKey('Z')) {
                return p;
            }
            p.clear();
        }
        return p;
    }

    /**
     * X, Y and Z at 0, the screw reported, and a rotation axis (A or B, any axis but X, Y, Z) still at 720: what stock
     * Marlin reports after a power-up until a home's G92 A0 B0. Only the screw zeroed (G92 B0, a bench tool) still
     * reads A 720; OpenPnP's own GcodeServer simulator starts at A and B 0, so it never reads as a reset.
     */
    public static boolean isBootPosition(Map<Character, Double> p, char letterB) {
        Double x = p.get('X'), y = p.get('Y'), z = p.get('Z');
        return x != null && y != null && z != null && p.get(letterB) != null && Math.abs(x) < TOL
                && Math.abs(y) < TOL && Math.abs(z) < TOL && !bootRotations(p).isEmpty();
    }

    /** The rotation axes (any but X, Y, Z) that still read the boot 720. */
    private static List<Character> bootRotations(Map<Character, Double> p) {
        List<Character> at720 = new ArrayList<>();
        for (Map.Entry<Character, Double> e : p.entrySet()) {
            if ("XYZ".indexOf(e.getKey()) < 0 && Math.abs(e.getValue() - BOOT_B_DEG) < TOL) {
                at720.add(e.getKey());
            }
        }
        return at720;
    }

    /**
     * Right after a connect: the controller's M114 reply, or null with {@code readError} when it could not be read. A
     * reply without a position counts as unread.
     */
    public void connected(List<String> m114, char letterB, String readError) {
        synchronized (this) {
            connectedLocked(m114, letterB, readError);
        }
        writeOut();
    }

    private void connectedLocked(List<String> m114, char letterB, String readError) {
        long now = clock.getAsLong();
        Map<Character, Double> p = parsePosition(m114);
        detected = !p.isEmpty() && isBootPosition(p, letterB);
        if (p.isEmpty()) {
            unknown("the controller's position could not be read at connect ("
                    + (readError != null ? readError : "no position in its M114 reply")
                    + "), so it may have been reset", now);
            watchdogTripAtMs = null; // maybe a reset: a trip reported now is a new one
            resetUnhomed = true; // and its home may move the Z frame (the conservative side)
            record(EVENT_UNREAD, now, unknownWhy);
            return;
        }
        if (detected) {
            detectedAtMs = now;
            Map<String, Double> r = new LinkedHashMap<>();
            r.put("x", p.get('X'));
            r.put("y", p.get('Y'));
            r.put("z", p.get('Z'));
            r.put("b", p.get(letterB));
            reported = r;
            StringBuilder rot = new StringBuilder();
            for (char c : bootRotations(p)) {
                rot.append(rot.length() == 0 ? "" : " and ").append(c).append(' ')
                        .append(DispenseProfile.plain(BOOT_B_DEG));
            }
            unknown(String.format(Locale.ROOT, "the controller was reset (a power cycle: at connect it reported "
                    + "X 0, Y 0, Z 0 and %s), so the head may be anywhere", rot), now);
            levelRefZ = 0.0; // BR-97: Marlin boots at Z 0 where the see-saw stands
            stopIdleClock(now); // it boots with its motors off: the count's epoch covers the screw
            watchdogTripAtMs = null; // and without a host watchdog trip
            resetUnhomed = true; // its home will move the Z frame
            record(EVENT_RESET, now, unknownWhy);
        }
    }

    /** BR-93: "the link to the controller was lost at 15:07 (the controller left USB: … is gone)". */
    public static String linkLostWhy(long atMs, String cause) {
        return "the link to the controller was lost at " + ZCheck.clock(atMs) + " (" + cause + ")";
    }

    /**
     * BR-93: the open link to the controller was lost at {@code atMs} ({@code why}, {@link #linkLostWhy}): it may have
     * been a reset, as at a connect whose position could not be read. The position is unknown until a home (the
     * attested one), that home moves the Z frame, and the screw's motor may have gone off (a reset boots with it off):
     * the start angle wants a measure. On record once per loss; no I/O (the driver's reader thread calls it): {@link
     * #flush} writes it. Returns whether it was new.
     */
    public synchronized boolean linkLost(long atMs, String why) {
        if (linkLostAtMs != null && linkLostAtMs == atMs) {
            return false;
        }
        linkLostAtMs = atMs;
        idleCheck(clock.getAsLong()); // an idle switch-off before it goes on record first
        Double ref = levelRefZ;
        unknown(why + ", so the controller may have been reset", atMs);
        levelRefZ = ref; // BR-97: a lost link moves no frame by itself; the reconnect decides (a detected reset: boot Z)
        resetUnhomed = true; // and its home may move the Z frame
        watchdogTripAtMs = null; // maybe a reset: a trip reported now is a new one
        record(EVENT_UNREAD, atMs, unknownWhy);
        Event off = new Event(EVENT_MOTOR_OFF, atMs, offText(atMs, "the link to the controller was lost: a reset "
                + "switches it off"));
        switchedOff(off);
        toWrite.add(off);
        return true;
    }

    /**
     * The screw's motor was switched off now: an M18 that names it (a STOP's DISABLE_COMMAND, the engine's stop
     * sequence). Journaled; dates the screw count. {@code cause} in plain words ("a STOP: ...").
     */
    public void screwMotorOff(String cause) {
        synchronized (this) {
            long now = clock.getAsLong();
            idleCheck(now); // an idle switch-off before it goes on record first
            Event e = new Event(EVENT_MOTOR_OFF, now, offText(now, cause));
            switchedOff(e);
            toWrite.add(e);
        }
        writeOut();
    }

    /**
     * The il/safety firmware's host watchdog tripped: it switched the screw's driver off ({@code cause} in plain words).
     * {@code reported}: its M990 report at a connect, which keeps a trip until the host arms it again: one already on
     * record (no arm or reset since) is that trip, not another (false; nothing recorded). Heard as it happened (its
     * error), it is always a new one.
     */
    public boolean watchdogTripped(String cause, boolean reported) {
        boolean recorded;
        synchronized (this) {
            recorded = trippedLocked(cause, reported);
        }
        writeOut();
        return recorded;
    }

    /**
     * A trip heard as it happens (its error, on the driver's reader thread): recorded now, in the wire's order with the
     * arms and resets around it. No I/O: {@link #flush} writes it.
     */
    public synchronized void watchdogTripHeard(String cause) {
        trippedLocked(cause, false);
    }

    private boolean trippedLocked(String cause, boolean reported) {
        if (reported && watchdogTripAtMs != null) {
            return false;
        }
        long now = clock.getAsLong();
        idleCheck(now);
        Event e = new Event(EVENT_MOTOR_OFF, now, offText(now, cause));
        switchedOff(e);
        toWrite.add(e);
        watchdogTripAtMs = now;
        record(EVENT_WATCHDOG_TRIP, now, "the host watchdog tripped at " + ZCheck.clock(now) + ": its M990 reports it "
                + "until the host arms it again");
        return true;
    }

    /**
     * The engine armed the host watchdog (M990 S: a run, the pressure hold): the firmware forgets its trip, so one it
     * reports later is a new one. No I/O (a stream's line): {@link #flush} writes it.
     */
    public synchronized void watchdogArmed() {
        if (watchdogTripAtMs != null) {
            watchdogTripAtMs = null;
            long now = clock.getAsLong();
            record(EVENT_WATCHDOG_ARMED, now, "the host watchdog was armed again at " + ZCheck.clock(now) + ": the "
                    + "firmware forgot its trip");
        }
    }

    /** A trip read back is still on record when no arm or reset (or unread connect) came after it. */
    private void watchdogTripAt(Long tripAt, Long endedAt) {
        watchdogTripAtMs = tripAt != null && (endedAt == null || tripAt > endedAt) ? tripAt : null;
    }

    /**
     * The screw turned now (a G0/G1 of its axis was acknowledged): its motor is on, the controller's idle clock starts
     * again. No I/O: the engine's stream calls it per line (a switch-off it finds is journaled after the stream).
     */
    public synchronized void screwMoved() {
        long now = clock.getAsLong();
        idleCheck(now);
        lastMoveMs = now;
        screwTurnedAtMs = now;
    }

    /**
     * Another axis moved now (an X/Y/Z move, a home, OpenPnP's own moves): while the screw's motor is on, the
     * controller's idle clock starts again; an off motor stays off. No I/O, as {@link #screwMoved}.
     */
    public synchronized void axisMoved() {
        long now = clock.getAsLong();
        idleCheck(now);
        if (lastMoveMs != null) {
            lastMoveMs = now;
        }
    }

    /**
     * Why a start angle cannot be set now, in plain words, or null: after the screw's motor went off (a switch-off, or
     * a reset: it boots off) the screw must have turned since (a dot pair measures the angle), and evidence the
     * request dates ({@code measuredAtMs}, null = none) must be from after it.
     */
    public String whyNoAngle(Long measuredAtMs) {
        String why;
        synchronized (this) {
            idleCheck(clock.getAsLong());
            why = whyNoAngleLocked(measuredAtMs);
        }
        writeOut();
        return why;
    }

    private String whyNoAngleLocked(Long measuredAtMs) {
        Long offAt = motorOff == null ? detectedAtMs : later(detectedAtMs, motorOff.atMs);
        if (offAt == null) {
            return null;
        }
        String since = detectedAtMs != null && detectedAtMs.equals(offAt)
                ? "the controller was reset at " + ZCheck.clock(offAt)
                : "its motor was switched off at " + ZCheck.clock(offAt);
        if (screwTurnedAtMs == null || screwTurnedAtMs <= offAt) {
            return "the screw has not turned since " + since + ": measure the start angle (a dot pair) first";
        }
        if (measuredAtMs != null && measuredAtMs <= offAt) {
            return "the start angle was measured at " + ZCheck.clock(measuredAtMs) + ", before " + since
                    + ": measure it again";
        }
        return null;
    }

    /**
     * Puts the screw's last move on record, at most every {@link #MOTION_JOURNAL_MS} (the first after a switch-off at
     * once): an OpenPnP restart that left the motor on then keeps the controller's idle clock. Called after a stream
     * (never during one: a journal write between two lines would hold the needle at a dot).
     */
    public void journalScrewMotion() {
        synchronized (this) {
            Long last = lastMoveMs;
            if (last != null && (journaledMoveMs == null || last - journaledMoveMs >= MOTION_JOURNAL_MS)) {
                journaledMoveMs = last;
                record(EVENT_SCREW_MOTION, last, "a move at " + ZCheck.clock(last) + " with the screw's motor on"
                        + idleNote());
            }
        }
        writeOut(); // also what a stream's moves found
    }

    /** The last switch-off of the screw's motor (atMs and the reason in plain words), or null: none on record. */
    public Event lastScrewMotorOff() {
        Event e;
        synchronized (this) {
            idleCheck(clock.getAsLong());
            e = motorOff;
        }
        writeOut();
        return e;
    }

    /** ": the controller switches it off after 60 min without one", or "" without a timeout. */
    private String idleNote() {
        Long t = idleOffMs == null ? null : idleOffMs.getAsLong();
        return t == null || t <= 0 ? "" : ": the controller switches it off after " + duration(t) + " without one";
    }

    /** "the screw motor was switched off at 16:20 (a STOP)". */
    private static String offText(long atMs, String cause) {
        return "the screw motor was switched off at " + ZCheck.clock(atMs) + " (" + cause + ")";
    }

    /** "60 min", or "90 s" when it is no whole number of minutes. */
    private static String duration(long ms) {
        long s = Math.round(ms / 1000.0);
        return s > 0 && s % 60 == 0 ? s / 60 + " min" : s + " s";
    }

    /**
     * The controller's idle timeout passed since the last move: it switched the motor off then. Journaled once (the
     * idle clock stops with it), dated at the last move plus the timeout, by the next call that writes (never a
     * stream's line).
     */
    private void idleCheck(long now) {
        Long t = idleOffMs == null ? null : idleOffMs.getAsLong();
        if (lastMoveMs == null || t == null || t <= 0 || now - lastMoveMs < t) {
            return;
        }
        long at = lastMoveMs + t;
        Event e = new Event(EVENT_MOTOR_OFF, at, offText(at, "the controller's idle timeout: no move for "
                + duration(t)));
        switchedOff(e);
        toWrite.add(e);
    }

    /**
     * Writes the changes not written yet: those made without I/O (a stream's moves and arms, a trip heard on the reader
     * thread). The engine calls it off those threads, and when it stops.
     */
    public void flush() {
        writeOut();
    }

    /**
     * Writes the changes made so far, outside this lock and in their order: one writer at a time drains them all, so a
     * later writer finds the earlier changes written.
     */
    private void writeOut() {
        synchronized (writeLock) {
            List<Event> l;
            synchronized (this) {
                if (toWrite.isEmpty()) {
                    return;
                }
                l = new ArrayList<>(toWrite);
                toWrite.clear();
            }
            if (journal != null) {
                for (Event e : l) {
                    journal.accept(e);
                }
            }
        }
    }

    /** A switch-off of the screw's motor (now or read back): the screw count is dated, the idle clock stops. */
    private void switchedOff(Event e) {
        if (motorOff == null || e.atMs >= motorOff.atMs) {
            motorOff = e;
        }
        countEpochMs = later(countEpochMs, e.atMs);
        stopIdleClock(e.atMs);
    }

    /** The motor is off since {@code atMs}: no idle timeout until a move switches it on; that move goes on record. */
    private void stopIdleClock(long atMs) {
        if (lastMoveMs != null && lastMoveMs <= atMs) {
            lastMoveMs = null;
        }
        if (journaledMoveMs != null && journaledMoveMs <= atMs) {
            journaledMoveMs = null;
        }
    }

    /**
     * A home succeeded (the engine's, the API's or OpenPnP's own): the position is known. Returns whether it is the
     * first home after a detected reset or an unread connect: it moved the Z frame ({@link #frameMovedAtMs}).
     */
    public boolean homed() {
        boolean moved;
        synchronized (this) {
            long now = clock.getAsLong();
            String by = attestedBy;
            String evidence = attestedEvidence;
            attestedBy = null; // this home's only
            attestedEvidence = null;
            moved = resetUnhomed;
            if (moved) {
                resetUnhomed = false;
                frameMovedAtMs = now;
                record(EVENT_FRAME_MOVED, now, "the home after the controller's reset moved the Z frame (K by twice the "
                        + "shift): record the needle frame again");
            }
            if (!positionKnown) {
                record(EVENT_KNOWN, now, "homed: the position is known again" + (by == null ? "" : ", attested by " + by
                        + (evidence == null ? " (no evidence given)" : " (evidence: " + evidence + ")")));
                if (by != null) {
                    lastAttestation = new LinkedHashMap<>();
                    lastAttestation.put("by", by);
                    lastAttestation.put("evidence", evidence);
                    lastAttestation.put("atMs", now);
                }
            }
            positionKnown = true;
            unknownWhy = null;
            levelRefZ = null;
        }
        writeOut();
        return moved;
    }

    /**
     * BR-96: who attests that the head may home now (what {@link #HOME_CHECK} names), and their evidence as JSON text
     * (null: none). Only the next {@link #homed} uses it; null clears it (a home that ended without one).
     */
    public synchronized void attest(String who, String evidenceJson) {
        attestedBy = who;
        attestedEvidence = evidenceJson;
    }

    /**
     * BR-97: the Z (the controller's frame) the see-saw's levelling before a home stays within 32 mm of: where the
     * position became unknown. Marlin's boot Z 0 after a detected reset; else {@code zNow}, taken at the first levelling
     * call since a possible reset, a home's start or a home (a lost link keeps it: the reconnect decides).
     */
    public synchronized double levelReference(double zNow) {
        if (levelRefZ == null) {
            levelRefZ = zNow;
        }
        return levelRefZ;
    }

    /**
     * BR-97: a home starts (its G28 next): its G28 may move the controller's frame even if the home ends unhomed, so the
     * next levelling takes its reference afresh, also after a restart (journaled). Its row is written here, before the
     * G28: when the journal throws, this throws and the home must not start (unrecorded, a restart would bring back the
     * reset's boot Z), and the reference stays.
     */
    public void homeStarted() {
        long now = clock.getAsLong();
        Event e = new Event(EVENT_HOME_STARTED, now, "a home started at " + ZCheck.clock(now) + ": its G28 may move the "
                + "controller's Z frame, so the see-saw's levelling takes its reference afresh");
        writeOut(); // the rows before it first, in order
        synchronized (writeLock) {
            if (journal != null) {
                journal.accept(e);
            }
        }
        synchronized (this) {
            levelRefZ = null;
        }
    }

    /** When the last home after a detected reset moved the Z frame (also before a restart), or null. */
    public synchronized Long frameMovedAtMs() {
        return frameMovedAtMs;
    }

    public synchronized boolean isPositionKnown() {
        return positionKnown;
    }

    /** Why the position is unknown, in plain words; null when it is known. */
    public synchronized String unknownWhy() {
        return positionKnown ? null : unknownWhy;
    }

    /**
     * The current connection began with a reset: its connect read the boot position (it stays so after the home, until
     * the next connect). A Stop and Start with the controller powered reads false.
     */
    public synchronized boolean isDetected() {
        return detected;
    }

    /** When the last reset was detected, also by an engine before a restart; or null. */
    public synchronized Long detectedAtMs() {
        return detectedAtMs;
    }

    /** What the controller reported at the last reset this engine detected ({x, y, z, b}), or null. */
    public synchronized Map<String, Double> reported() {
        return reported == null ? null : new LinkedHashMap<>(reported);
    }

    /**
     * The status's view, read at once: positionKnown, positionUnknownWhy, powerCycle {detected, atMs, reported},
     * screwMotorOff {atMs, why} and lastAttestation {by, evidence, atMs} (field by field, a home in between could show
     * an unknown position without its reason).
     */
    public Map<String, Object> view() {
        Map<String, Object> v;
        synchronized (this) {
            idleCheck(clock.getAsLong());
            v = viewLocked();
        }
        writeOut();
        return v;
    }

    private Map<String, Object> viewLocked() {
        Map<String, Object> pc = new LinkedHashMap<>();
        pc.put("detected", detected);
        pc.put("atMs", detectedAtMs);
        pc.put("reported", reported());
        Map<String, Object> off = new LinkedHashMap<>();
        off.put("atMs", motorOff == null ? null : motorOff.atMs);
        off.put("why", motorOff == null ? null : motorOff.message);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("positionKnown", positionKnown);
        v.put("positionUnknownWhy", unknownWhy());
        v.put("powerCycle", pc);
        v.put("screwMotorOff", off);
        v.put("lastAttestation", lastAttestation == null ? null : new LinkedHashMap<>(lastAttestation));
        return v;
    }

    /**
     * When the B step count last restarted or may have, or stopped giving the screw's phase: a detected reset, a
     * connect whose position could not be read, or the screw's motor switched off (it may have turned); null if none
     * was seen.
     */
    public Long countEpochMs() {
        Long epoch;
        synchronized (this) {
            idleCheck(clock.getAsLong());
            epoch = countEpochMs;
        }
        writeOut();
        return epoch;
    }

    private static Long later(Long a, long b) {
        return a == null ? b : Math.max(a, b);
    }

    /** The position became unknown at {@code atMs}, and the B step count may have restarted then. */
    private void unknown(String why, long atMs) {
        positionKnown = false;
        unknownWhy = why;
        countEpochMs = later(countEpochMs, atMs);
        levelRefZ = null; // BR-97: maybe a new frame (a detected reset then sets its boot Z)
    }

    /** A change to journal: written after the lock is released, after the changes made before it. */
    private void record(String code, long atMs, String message) {
        toWrite.add(new Event(code, atMs, message));
    }
}
