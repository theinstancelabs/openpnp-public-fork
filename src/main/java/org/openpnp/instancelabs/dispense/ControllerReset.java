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
 * <p>Times are epoch ms from an injectable clock. Thread safe.</p>
 */
public final class ControllerReset {
    /** Where stock Marlin believes A and B are at power-up (J_MAX_POS), until a G92. */
    public static final double BOOT_B_DEG = 720;
    /** Journal codes (the events table's {@code code}): a connect read the boot position. */
    public static final String EVENT_RESET = "CONTROLLER_RESET";
    /** A connect could not read the position (it may have been a reset). */
    public static final String EVENT_UNREAD = "POSITION_UNREAD";
    /** A home made an unknown position known again. */
    public static final String EVENT_KNOWN = "POSITION_KNOWN";
    /** The screw count may have restarted, the position is not concerned (the cutover to the journal). */
    public static final String EVENT_COUNT_EPOCH = "SCREW_COUNT_EPOCH";
    /** The first start of an engine that journals: a database without it was used by an earlier build. */
    public static final String EVENT_JOURNAL = "RESET_JOURNAL";
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
    private boolean positionKnown;
    private String unknownWhy;
    private boolean detected;
    private Long detectedAtMs;
    private Map<String, Double> reported;
    private Long countEpochMs;

    /** Without a journal: nothing outlives this engine (tests). */
    public ControllerReset(LongSupplier clock, boolean enabledAtStart, boolean homedAtStart) {
        this(clock, enabledAtStart, homedAtStart, null, null);
    }

    /**
     * @param clock epoch ms (null = the system clock)
     * @param enabledAtStart whether OpenPnP was connected when the service started (that connect was not seen)
     * @param homedAtStart whether it was homed then
     * @param history what earlier engines journaled: the newest event of each code, oldest first (null = none)
     * @param journal where this engine's changes go, to be read back after a restart (null = nowhere)
     */
    public ControllerReset(LongSupplier clock, boolean enabledAtStart, boolean homedAtStart, List<Event> history,
            Consumer<Event> journal) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.journal = journal;
        positionKnown = true;
        for (Event e : history == null ? Collections.<Event> emptyList() : history) {
            if (EVENT_RESET.equals(e.code)) {
                detectedAtMs = e.atMs; // what it reported is not journaled: reported stays null
                unknown(e.message, e.atMs);
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
            }
        }
        if (!positionKnown) {
            unknownWhy += "; no home has followed (OpenPnP restarted meanwhile)";
        }
        if (homedAtStart) {
            // OpenPnP clears its homed flag at every disable and a reset needs a reconnect: this home came after it
            homed();
        }
        else if (enabledAtStart && positionKnown) {
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
    public synchronized void connected(List<String> m114, char letterB, String readError) {
        long now = clock.getAsLong();
        Map<Character, Double> p = parsePosition(m114);
        detected = !p.isEmpty() && isBootPosition(p, letterB);
        if (p.isEmpty()) {
            unknown("the controller's position could not be read at connect ("
                    + (readError != null ? readError : "no position in its M114 reply")
                    + "), so it may have been reset", now);
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
            record(EVENT_RESET, now, unknownWhy);
        }
    }

    /** A home succeeded (the engine's, the API's or OpenPnP's own): the position is known. */
    public synchronized void homed() {
        if (!positionKnown) {
            record(EVENT_KNOWN, clock.getAsLong(), "homed: the position is known again");
        }
        positionKnown = true;
        unknownWhy = null;
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
     * The status's view, read at once: positionKnown, positionUnknownWhy and powerCycle {detected, atMs, reported}
     * (field by field, a home in between could show an unknown position without its reason).
     */
    public synchronized Map<String, Object> view() {
        Map<String, Object> pc = new LinkedHashMap<>();
        pc.put("detected", detected);
        pc.put("atMs", detectedAtMs);
        pc.put("reported", reported());
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("positionKnown", positionKnown);
        v.put("positionUnknownWhy", unknownWhy());
        v.put("powerCycle", pc);
        return v;
    }

    /**
     * When the B step count last restarted or may have: a detected reset or a connect whose position could not be
     * read; null if neither was seen since the engine started.
     */
    public synchronized Long countEpochMs() {
        return countEpochMs;
    }

    private static Long later(Long a, long b) {
        return a == null ? b : Math.max(a, b);
    }

    /** The position became unknown at {@code atMs}, and the B step count may have restarted then. */
    private void unknown(String why, long atMs) {
        positionKnown = false;
        unknownWhy = why;
        countEpochMs = later(countEpochMs, atMs);
    }

    private void record(String code, long atMs, String message) {
        if (journal != null) {
            journal.accept(new Event(code, atMs, message));
        }
    }
}
