package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * BR-02: a controller reset (power cycle) is told from a reconnect by the M114 position read at connect (stock Marlin
 * boots at X 0, Y 0, Z 0 and B 720), and only a home makes an unknown position known again (fake clock).
 */
public class ControllerResetTest {
    long now = 1_000_000;

    static final String BOOT = "X:0.0000 Y:0.0000 Z:0.0000 A:720.0000 B:720.0000 Count X:0 Y:0 Z:0 B:3197";
    static final String PARKED = "X:150.0000 Y:200.0000 Z:31.5000 A:0.0000 B:1234.5000 Count X:12000 Y:16000 Z:2520 B:5481";

    ControllerReset reset(boolean enabledAtStart, boolean homedAtStart) {
        return new ControllerReset(() -> now, enabledAtStart, homedAtStart);
    }

    static List<String> reply(String line) {
        return Arrays.asList(line, "ok");
    }

    @Test
    public void readsTheLogicalPositionBeforeCount() {
        Map<Character, Double> p = ControllerReset.parsePosition(reply(PARKED));
        assertEquals(150.0, p.get('X'), 1e-9);
        assertEquals(200.0, p.get('Y'), 1e-9);
        assertEquals(31.5, p.get('Z'), 1e-9);
        assertEquals(1234.5, p.get('B'), 1e-9, "the logical B, not the step count after Count");
        assertTrue(ControllerReset.parsePosition(Collections.singletonList("ok")).isEmpty());
    }

    @Test
    public void onlyTheBootPositionIsAReset() {
        assertTrue(ControllerReset.isBootPosition(ControllerReset.parsePosition(reply(BOOT)), 'B'));
        assertFalse(ControllerReset.isBootPosition(ControllerReset.parsePosition(reply(PARKED)), 'B'));
        // after a home the controller reads X/Y/Z 0 too, but OpenPnP's home sets G92 A0 B0
        assertFalse(ControllerReset.isBootPosition(ControllerReset.parsePosition(
                reply("X:0.0000 Y:0.0000 Z:0.0000 A:0.0000 B:0.0000 Count X:0 Y:0 Z:0 B:0")), 'B'));
        assertFalse(ControllerReset.isBootPosition(ControllerReset.parsePosition(
                reply("X:0.0100 Y:0.0000 Z:0.0000 A:720.0000 B:720.0000 Count X:0 Y:0 Z:0 B:3197")), 'B'));
        assertFalse(ControllerReset.isBootPosition(ControllerReset.parsePosition(
                reply("X:0.0000 Y:0.0000 Z:0.0000 Count X:0 Y:0 Z:0")), 'B'), "no B reported: not the LumenPnP boot");
        // the screw letter is the configured one
        assertTrue(ControllerReset.isBootPosition(ControllerReset.parsePosition(
                reply("X:0.0000 Y:0.0000 Z:0.0000 A:720.0000 B:0.0000 Count X:0 Y:0 Z:0")), 'A'));
    }

    /**
     * #11 re-review MAJOR: a tool zeroed the screw after the power-up (G92 B0) and nobody homed: X, Y and Z still read
     * their boot values and A its 720 (CI's deploy dry run, where the home then raised Z 31.5 on the boot belief).
     */
    @Test
    public void aBootWithTheScrewZeroedIsStillAReset() {
        String zeroed = "X:0.00 Y:0.00 Z:0.00 A:720.00 B:0.00 Count X:0 Y:0 Z:0 A:3197 B:3197";
        assertTrue(ControllerReset.isBootPosition(ControllerReset.parsePosition(reply(zeroed)), 'B'));
        assertTrue(ControllerReset.isBootPosition(ControllerReset.parsePosition(
                reply("X:0.00 Y:0.00 Z:0.00 A:0.00 B:720.00 Count X:0 Y:0 Z:0")), 'B'), "N1's rotation zeroed instead");
        ControllerReset r = reset(false, false);
        now = 1000;
        r.connected(reply(zeroed), 'B', null);
        assertFalse(r.isPositionKnown());
        assertTrue(r.isDetected());
        assertEquals(Long.valueOf(1000), r.countEpochMs());
        assertTrue(r.unknownWhy().contains("A 720"), r.unknownWhy());
        assertEquals(0.0, r.reported().get("b"), 1e-9, "what the screw read");
    }

    @Test
    public void aResetMakesThePositionUnknownUntilAHome() {
        ControllerReset r = reset(false, false);
        assertTrue(r.isPositionKnown(), "not started: the first connect decides");
        assertNull(r.unknownWhy());
        assertFalse(r.isDetected());
        now += 1000;
        r.connected(reply(BOOT), 'B', null);
        assertFalse(r.isPositionKnown());
        assertTrue(r.isDetected());
        assertEquals(Long.valueOf(now), r.detectedAtMs());
        assertEquals(0.0, r.reported().get("x"), 1e-9);
        assertEquals(720.0, r.reported().get("b"), 1e-9);
        assertTrue(r.unknownWhy().contains("reset"), r.unknownWhy());
        // a reconnect does not make it known, whatever the controller now says (OpenPnP may have jogged the believed
        // position)
        now += 1000;
        r.connected(reply(PARKED), 'B', null);
        assertFalse(r.isPositionKnown(), "only a home makes the position known");
        now += 1000;
        r.homed();
        assertTrue(r.isPositionKnown());
        assertNull(r.unknownWhy());
        assertFalse(r.isDetected(), "this connection's connect read a parked head, not the boot position");
        assertEquals(Long.valueOf(now - 2000), r.detectedAtMs(), "the last reset stays on record");
    }

    @Test
    public void detectedNamesTheCurrentConnection() {
        ControllerReset r = reset(false, false);
        now = 1000;
        r.connected(reply(BOOT), 'B', null);
        now = 2000;
        r.homed();
        assertTrue(r.isDetected(), "this connection began with a reset, homed or not");
        now = 3000;
        r.connected(reply(PARKED), 'B', null);
        assertFalse(r.isDetected(), "a Stop and Start with the controller powered: not switched off");
        assertEquals(Long.valueOf(1000), r.detectedAtMs());
        assertEquals(720.0, r.reported().get("b"), 1e-9, "what the last reset reported stays on record");
    }

    /** What the engine's events table keeps: everything one run of the engine journaled. */
    List<ControllerReset.Event> journal = new ArrayList<>();

    /** A run of the engine that reads back what earlier runs journaled. */
    ControllerReset run(boolean enabledAtStart, boolean homedAtStart) {
        return new ControllerReset(() -> now, enabledAtStart, homedAtStart, newestOfEach(), journal::add);
    }

    /** What the engine reads back at its start: the newest event of each code, oldest first. */
    List<ControllerReset.Event> newestOfEach() {
        Map<String, ControllerReset.Event> newest = new LinkedHashMap<>();
        for (ControllerReset.Event e : journal) {
            newest.remove(e.code);
            newest.put(e.code, e);
        }
        return new ArrayList<>(newest.values());
    }

    List<String> codes() {
        List<String> c = new ArrayList<>();
        for (ControllerReset.Event e : journal) {
            c.add(e.code);
        }
        return c;
    }

    /**
     * BR-93: a lost link (the controller left USB, a read or a write failed) may have been a reset: the position is
     * unknown until a home, that home moves the Z frame, and the screw's motor may have gone off (the start angle wants a
     * measure). Each loss goes on record once and the next engine reads it back; a reconnect that reads a powered
     * controller's position does not make it known again.
     */
    @Test
    public void aLostLinkIsAPossibleResetOnRecordOnce() {
        ControllerReset first = run(true, true);
        now = 1000;
        first.screwMoved();
        assertNull(first.whyNoAngle(null), "the screw turned: an angle may be set");
        now = 2000;
        String why = "the link to the controller was lost at 15:07:57 (the controller left USB)";
        assertTrue(first.linkLost(1500, why));
        assertFalse(first.linkLost(1500, why), "the same loss goes on record once");
        assertFalse(first.isPositionKnown());
        assertTrue(first.unknownWhy().startsWith(why) && first.unknownWhy().contains("may have been reset"),
                first.unknownWhy());
        assertEquals(Long.valueOf(1500), first.countEpochMs(), "I11: an angle older than the loss is stale");
        String noAngle = first.whyNoAngle(null);
        assertTrue(noAngle != null && noAngle.contains("measure the start angle"), String.valueOf(noAngle));
        first.flush();
        assertEquals(Arrays.asList(ControllerReset.EVENT_UNREAD, ControllerReset.EVENT_MOTOR_OFF), codes());

        now = 5000;
        ControllerReset second = run(false, false);
        assertFalse(second.isPositionKnown(), "after a restart too");
        assertTrue(second.unknownWhy().startsWith(why), second.unknownWhy());
        now = 6000;
        second.connected(reply(PARKED), 'B', null); // the board is back, not reset: still a possible one
        assertFalse(second.isPositionKnown());
        assertNotNull(second.whyNoAngle(null));
        now = 7000;
        assertTrue(second.homed(), "that home moves the Z frame");
        assertTrue(second.isPositionKnown());
    }

    @Test
    public void aResetHomedBeforeAnEngineRestartStillDatesTheScrewCount() {
        // the review's case: start angle set, power cycle, attested home (its G92 A0 B0 removes the boot
        // position), then OpenPnP restarts and connects to a controller that reads a homed position
        ControllerReset first = run(false, false);
        now = 1000;
        first.connected(reply(BOOT), 'B', null);
        now = 2000;
        first.homed();
        assertEquals(Arrays.asList(ControllerReset.EVENT_RESET, ControllerReset.EVENT_FRAME_MOVED,
                ControllerReset.EVENT_KNOWN), codes(), "the home after the reset moved the Z frame too");
        assertTrue(journal.get(0).message.contains("reset"), journal.get(0).message);
        assertEquals(1000, journal.get(0).atMs);

        now = 5000;
        ControllerReset second = run(false, false);
        assertEquals(Long.valueOf(1000), second.countEpochMs(), "I11: a start angle older than the reset stays stale");
        assertTrue(second.isPositionKnown(), "the attested home followed the reset");
        assertFalse(second.isDetected(), "no connection yet in this run");
        assertEquals(Long.valueOf(1000), second.detectedAtMs(), "the last reset stays on record");
        assertNull(second.reported(), "what it reported is not journaled");
        now = 6000;
        second.connected(reply(PARKED), 'B', null);
        assertEquals(Long.valueOf(1000), second.countEpochMs());
        assertTrue(second.isPositionKnown());
        assertEquals(3, journal.size(), "a plain reconnect journals nothing");
        assertFalse(second.homed(), "the reset's home is on record: no second Z frame move");
    }

    @Test
    public void aResetNotHomedBeforeAnEngineRestartLeavesThePositionUnknown() {
        ControllerReset first = run(false, false);
        now = 1000;
        first.connected(reply(BOOT), 'B', null);
        // OpenPnP's own jog moves the head while unhomed: the next connect no longer reads the boot position

        now = 5000;
        ControllerReset second = run(false, false);
        assertFalse(second.isPositionKnown(), "the reset was never followed by a home");
        assertTrue(second.unknownWhy().contains("reset"), second.unknownWhy());
        assertTrue(second.unknownWhy().contains("restarted"), second.unknownWhy());
        now = 6000;
        second.connected(reply("X:5.0000 Y:0.0000 Z:0.0000 A:720.0000 B:720.0000 Count X:400 Y:0 Z:0 B:3197"),
                'B', null);
        assertFalse(second.isPositionKnown(), "only a home makes the position known");
        assertFalse(second.isDetected(), "this connect read X 5");
        assertEquals(Long.valueOf(1000), second.countEpochMs());
        now = 7000;
        second.homed();
        assertTrue(second.isPositionKnown());

        now = 9000;
        assertTrue(run(false, false).isPositionKnown(), "the home is on record too");
    }

    @Test
    public void anUnreadConnectBeforeAnEngineRestartStaysAPossibleReset() {
        ControllerReset first = run(false, false);
        now = 1500;
        first.connected(null, 'B', "timeout");
        assertEquals(Arrays.asList(ControllerReset.EVENT_UNREAD), codes());
        assertTrue(journal.get(0).message.contains("timeout"), journal.get(0).message);

        ControllerReset second = run(false, false);
        assertFalse(second.isPositionKnown());
        assertEquals(Long.valueOf(1500), second.countEpochMs());
        assertNull(second.detectedAtMs(), "not a detected reset");
    }

    @Test
    public void anEngineStartedOnAHomedMachineKnowsThePositionAfterAReset() {
        // OpenPnP clears its homed flag at every disable and a reset needs a reconnect: a home followed it
        ControllerReset first = run(false, false);
        now = 1000;
        first.connected(reply(BOOT), 'B', null);
        now = 5000;
        ControllerReset second = run(true, true);
        assertTrue(second.isPositionKnown());
        assertEquals(Long.valueOf(1000), second.countEpochMs());
        assertEquals(ControllerReset.EVENT_KNOWN, journal.get(journal.size() - 1).code, "the home goes on record");
        assertEquals(5000, journal.get(journal.size() - 1).atMs);
    }

    /**
     * The first start on a database from before the journal (the cutover from a build that asked for the start angle
     * after every connect): the engine cannot know what happened, so the screw count may have restarted. The position
     * is not affected (the deployer's health check homes without the operator).
     */
    @Test
    public void aScrewCountEpochDatesTheCountButNotThePosition() {
        journal.add(new ControllerReset.Event(ControllerReset.EVENT_COUNT_EPOCH, 3000, "first start with a journal"));
        now = 5000;
        ControllerReset r = run(false, false);
        assertEquals(Long.valueOf(3000), r.countEpochMs());
        assertTrue(r.isPositionKnown());
        assertNull(r.detectedAtMs(), "not a detected reset");
    }

    @Test
    public void onlyChangesAreJournaled() {
        ControllerReset r = run(false, false);
        r.connected(reply(PARKED), 'B', null);
        r.homed();
        assertTrue(journal.isEmpty(), "a known position homed again is nothing new: " + codes());
        r.connected(reply(BOOT), 'B', null);
        r.connected(reply(BOOT), 'B', null);
        r.homed();
        r.homed();
        assertEquals(Arrays.asList(ControllerReset.EVENT_RESET, ControllerReset.EVENT_RESET,
                ControllerReset.EVENT_FRAME_MOVED, ControllerReset.EVENT_KNOWN), codes(),
                "every reset, and the home that ends the unknown position (it moved the Z frame)");
    }

    @Test
    public void aPlainReconnectKeepsAKnownPosition() {
        ControllerReset r = reset(false, false);
        r.connected(reply(PARKED), 'B', null);
        assertTrue(r.isPositionKnown());
        assertFalse(r.isDetected());
        assertNull(r.countEpochMs(), "no reset seen: the screw count runs on");
    }

    @Test
    public void anUnreadPositionIsUnknown() {
        ControllerReset r = reset(false, false);
        now += 500;
        r.connected(null, 'B', "timeout");
        assertFalse(r.isPositionKnown());
        assertFalse(r.isDetected(), "not a detected reset, only an unknown one");
        assertTrue(r.unknownWhy().contains("could not be read"), r.unknownWhy());
        assertTrue(r.unknownWhy().contains("timeout"), r.unknownWhy());
        assertEquals(Long.valueOf(now), r.countEpochMs(), "the screw count may have restarted");
        r.connected(Collections.singletonList("ok"), 'B', null);
        assertFalse(r.isPositionKnown(), "a reply without a position is not a read");
    }

    @Test
    public void aServiceStartedOnAStartedUnhomedMachineDoesNotKnowThePosition() {
        assertFalse(reset(true, false).isPositionKnown());
        assertTrue(reset(true, false).unknownWhy().contains("never checked"), reset(true, false).unknownWhy());
        assertTrue(reset(true, true).isPositionKnown());
    }

    // ------------------------------------------------------------------------------------------
    // The screw motor switched off (a STOP's M18 B, the engine's stop sequence, the controller's idle timeout)
    // ------------------------------------------------------------------------------------------

    static final long HOUR = 3600_000L;
    /** The controller's idle timeout (Marlin's DEFAULT_STEPPER_DEACTIVE_TIME on the LumenPnP). */
    long idleMs = HOUR;

    /** A run of the engine that knows the controller's idle timeout and reads back what earlier runs journaled. */
    ControllerReset withIdle() {
        return new ControllerReset(() -> now, false, false, newestOfEach(), journal::add, () -> idleMs);
    }

    /**
     * With its motor off the screw turns freely (118 deg became 226 deg on the bench): the start angle and the camera
     * records of partial turns are dated by it as by a reset; the head's position is not concerned (M18 B).
     */
    @Test
    public void aScrewMotorOffDatesTheScrewCountButNotThePosition() {
        ControllerReset r = withIdle();
        now = 1000;
        r.connected(reply(PARKED), 'B', null);
        now = 2000;
        r.screwMotorOff("a STOP");
        assertEquals(Long.valueOf(2000), r.countEpochMs());
        ControllerReset.Event off = r.lastScrewMotorOff();
        assertEquals(2000, off.atMs);
        assertTrue(off.message.startsWith("the screw motor was switched off at "), off.message);
        assertTrue(off.message.endsWith(" (a STOP)"), off.message);
        assertTrue(r.isPositionKnown(), "the head's motors stay on");
        assertNull(r.detectedAtMs(), "not a reset");
        assertEquals(Arrays.asList(ControllerReset.EVENT_MOTOR_OFF), codes());
        assertEquals(2000, journal.get(0).atMs);
        assertEquals(off.message, journal.get(0).message);
        Map<String, Object> v = r.view();
        @SuppressWarnings("unchecked")
        Map<String, Object> mo = (Map<String, Object>) v.get("screwMotorOff");
        assertEquals(2000L, mo.get("atMs"));
        assertEquals(off.message, mo.get("why"));
    }

    @Test
    public void aScrewMotorOffStaysOnRecordAcrossAnEngineRestart() {
        ControllerReset first = withIdle();
        now = 2000;
        first.screwMotorOff("a STOP");
        now = 9000;
        ControllerReset second = withIdle();
        assertEquals(Long.valueOf(2000), second.countEpochMs());
        assertEquals(2000, second.lastScrewMotorOff().atMs);
        assertEquals(first.lastScrewMotorOff().message, second.lastScrewMotorOff().message);
        assertTrue(second.isPositionKnown());
        assertNull(withIdle().detectedAtMs());
    }

    /** Marlin switches an idle motor off after its timeout: dated then, when the state is next read, journaled once. */
    @Test
    public void anIdleScrewIsSwitchedOffByTheControllerOnceAtItsTimeout() {
        ControllerReset r = withIdle();
        now = 1000;
        r.screwMoved();
        now = 1000 + HOUR - 1;
        assertNull(r.countEpochMs(), "not idle long enough yet");
        assertNull(r.lastScrewMotorOff());
        assertTrue(journal.isEmpty());
        now = 1000 + HOUR + 5000;
        assertEquals(Long.valueOf(1000 + HOUR), r.countEpochMs(), "dated when the controller switched it off");
        assertEquals(1000 + HOUR, r.lastScrewMotorOff().atMs);
        assertTrue(r.lastScrewMotorOff().message.contains("the controller's idle timeout: no move for 60 min"),
                r.lastScrewMotorOff().message);
        now += 3 * HOUR;
        r.countEpochMs();
        r.lastScrewMotorOff();
        r.view();
        assertEquals(Arrays.asList(ControllerReset.EVENT_MOTOR_OFF), codes(), "journaled once");
        assertEquals(1000 + HOUR, journal.get(0).atMs);
        // the next move starts the idle clock again
        r.screwMoved();
        long moved = now;
        now = moved + HOUR;
        assertEquals(Long.valueOf(moved + HOUR), r.countEpochMs());
        assertEquals(2, journal.size());
    }

    /** The timeout is the setting of the moment (dispense settings, screwIdleOffS). */
    @Test
    public void theIdleTimeoutIsTheSettingNow() {
        ControllerReset r = withIdle();
        now = 1000;
        r.screwMoved();
        idleMs = 60_000;
        now = 1000 + 60_000;
        assertEquals(Long.valueOf(1000 + 60_000), r.countEpochMs());
        assertTrue(r.lastScrewMotorOff().message.contains("no move for 1 min"), r.lastScrewMotorOff().message);
        assertNull(reset(false, false).countEpochMs(), "without a timeout (tests): no idle switch-off");
    }

    /** A move long after the last one: the controller switched the motor off in between, though nothing read it then. */
    @Test
    public void aMoveAfterTheTimeoutStillPutsTheSwitchOffBeforeItOnRecord() {
        ControllerReset r = withIdle();
        now = 1000;
        r.screwMoved();
        now = 1000 + 2 * HOUR;
        r.screwMoved();
        assertEquals(1000 + HOUR, r.lastScrewMotorOff().atMs);
        now += HOUR - 1;
        assertEquals(Long.valueOf(1000 + HOUR), r.countEpochMs(), "the move started the idle clock again");
        assertEquals(1, journal.size());
    }

    /** A motor that is off has no idle timeout until a move switches it on again; a reset boots with it off. */
    @Test
    public void aMotorAlreadyOffHasNoIdleTimeoutUntilTheScrewMovesAgain() {
        ControllerReset r = withIdle();
        now = 1000;
        r.screwMoved();
        now = 2000;
        r.screwMotorOff("the engine's stop sequence: M18 B");
        now = 1000 + 5 * HOUR;
        assertEquals(Long.valueOf(2000), r.countEpochMs());
        assertEquals(1, journal.size(), "no idle timeout of a motor that is off: " + codes());

        now = 10 * HOUR;
        r.screwMoved();
        now += 1000;
        r.connected(reply(BOOT), 'B', null);
        now += 2 * HOUR;
        assertEquals(Long.valueOf(10 * HOUR + 1000), r.countEpochMs(), "the reset's epoch, no idle switch-off after it");
        assertEquals(Arrays.asList(ControllerReset.EVENT_MOTOR_OFF, ControllerReset.EVENT_RESET), codes());
    }

    /**
     * OpenPnP restarted without a Stop (a crash, or a stop that disconnects first) leaves the motor on: the controller's
     * idle clock runs on from the screw's last move on record.
     */
    @Test
    public void theScrewsLastMoveOutlivesAnEngineRestart() {
        ControllerReset first = withIdle();
        now = 1000;
        first.screwMoved();
        first.journalScrewMotion();
        assertEquals(Arrays.asList(ControllerReset.EVENT_SCREW_MOTION), codes());
        assertEquals(1000, journal.get(0).atMs);
        now = 1000 + HOUR / 2;
        ControllerReset second = withIdle();
        assertNull(second.countEpochMs(), "still on");
        now = 1000 + HOUR;
        assertEquals(Long.valueOf(1000 + HOUR), second.countEpochMs(), "the idle clock ran on across the restart");
        now += HOUR;
        assertEquals(Long.valueOf(1000 + HOUR), withIdle().countEpochMs(), "and its switch-off is on record once");
        assertEquals(Arrays.asList(ControllerReset.EVENT_SCREW_MOTION, ControllerReset.EVENT_MOTOR_OFF), codes());
    }

    /**
     * The screw's moves are journaled at most every {@link ControllerReset#MOTION_JOURNAL_MS} (a restart may date the
     * last one that much early: the idle switch-off then only comes sooner), and the first one after a switch-off at
     * once (it must not read as older than the switch-off after a restart).
     */
    @Test
    public void theScrewsMovesAreJournaledNowAndThen() {
        ControllerReset r = withIdle();
        now = 1000;
        r.journalScrewMotion();
        assertTrue(journal.isEmpty(), "no move yet");
        r.screwMoved();
        r.journalScrewMotion();
        now = 2000;
        r.screwMoved();
        r.journalScrewMotion();
        assertEquals(1, journal.size(), "a minute later: not again");
        now = 1000 + ControllerReset.MOTION_JOURNAL_MS;
        r.screwMoved();
        r.journalScrewMotion();
        assertEquals(2, journal.size());
        now += 1000;
        r.screwMotorOff("a STOP");
        now += 1000;
        r.screwMoved();
        r.journalScrewMotion();
        assertEquals(Arrays.asList(ControllerReset.EVENT_SCREW_MOTION, ControllerReset.EVENT_SCREW_MOTION,
                ControllerReset.EVENT_MOTOR_OFF, ControllerReset.EVENT_SCREW_MOTION), codes());
        long moved = now;
        now = moved + HOUR;
        assertEquals(Long.valueOf(moved + HOUR), withIdle().countEpochMs(), "after a restart, from the move after it");
    }

    /**
     * Marlin restarts its idle clock on any queued move: while the screw's motor is on, a move of another axis keeps it
     * on too; while it is off (until the screw turns again) nothing times out.
     */
    @Test
    public void anyMoveWhileTheMotorIsOnRestartsTheIdleClock() {
        ControllerReset r = withIdle();
        now = 1000;
        r.axisMoved(); // the motor's state is not known yet: no clock
        now = 1000 + 2 * HOUR;
        assertNull(r.countEpochMs());
        r.screwMoved();
        long turned = now;
        now = turned + HOUR / 2;
        r.axisMoved(); // an X move restarts it
        long moved = now;
        now = turned + HOUR + 1000;
        assertNull(r.countEpochMs(), "the X move kept it on");
        now = moved + HOUR;
        assertEquals(Long.valueOf(moved + HOUR), r.countEpochMs());
        now += 3 * HOUR;
        r.axisMoved(); // off now: a move of another axis does not switch it on
        now += 2 * HOUR;
        r.countEpochMs();
        assertEquals(Arrays.asList(ControllerReset.EVENT_MOTOR_OFF), codes());
    }

    /**
     * A move between a stream's lines journals nothing (a write there would hold the needle at a dot): the switch-off
     * it finds goes on record after the stream, before the move.
     */
    @Test
    public void aMoveFindsASwitchOffButJournalsItOnlyAfterTheStream() {
        ControllerReset r = withIdle();
        now = 1000;
        r.screwMoved();
        now = 1000 + 2 * HOUR;
        r.screwMoved();
        r.axisMoved();
        assertTrue(journal.isEmpty(), "nothing written between two lines: " + codes());
        r.journalScrewMotion(); // after the stream
        assertEquals(Arrays.asList(ControllerReset.EVENT_MOTOR_OFF, ControllerReset.EVENT_SCREW_MOTION), codes());
        assertEquals(1000 + HOUR, journal.get(0).atMs);
    }

    /**
     * A start angle is measured with the screw turning (a dot pair): after a switch-off or a reset none is set until the
     * screw has turned since, nor one whose evidence was made before it.
     */
    @Test
    public void anAngleIsSetOnlyOnceTheScrewTurnedSinceItsMotorWentOff() {
        ControllerReset r = withIdle();
        now = 1000;
        assertNull(r.whyNoAngle(null), "nothing switched it off");
        r.screwMoved();
        now = 2000;
        r.screwMotorOff("a STOP");
        now = 3000;
        r.axisMoved(); // an X move is no turn of the screw
        String why = r.whyNoAngle(null);
        assertTrue(why != null && why.startsWith("the screw has not turned since its motor was switched off at ")
                && why.endsWith(": measure the start angle (a dot pair) first"), why);
        now = 4000;
        r.screwMoved();
        assertNull(r.whyNoAngle(null), "turned since: the dot pair");
        assertNull(r.whyNoAngle(4000L));
        String old = r.whyNoAngle(1500L);
        assertTrue(old != null && old.startsWith("the start angle was measured at ")
                && old.contains(", before its motor was switched off at "), old);
        assertNotNull(r.whyNoAngle(2000L), "measured in the millisecond of the switch-off");
        r.journalScrewMotion();
        assertNull(withIdle().whyNoAngle(null), "after a restart the turn on record counts");
        // a reset boots with the motor off: the same until the screw turns
        now = 5000;
        r.connected(reply(BOOT), 'B', null);
        assertTrue(r.whyNoAngle(null).startsWith("the screw has not turned since the controller was reset at "),
                r.whyNoAngle(null));
        now = 6000;
        r.screwMoved();
        assertNull(r.whyNoAngle(null));
    }

    /**
     * The host watchdog's M990 keeps "tripped" until the host arms it again (without the keepalive nothing arms it):
     * one trip goes on record once, at its first report; an arm, or a reset (the firmware boots without it), ends it.
     */
    @Test
    public void aTripReportedAtEveryConnectIsRecordedOncePerArm() {
        ControllerReset r = withIdle();
        now = 1000;
        assertTrue(r.watchdogTripped("the host watchdog tripped", true));
        now = 2000;
        assertFalse(r.watchdogTripped("the host watchdog tripped", true), "the same trip at the next connect");
        assertFalse(withIdle().watchdogTripped("the host watchdog tripped", true), "and after a restart");
        assertEquals(Arrays.asList(ControllerReset.EVENT_MOTOR_OFF, ControllerReset.EVENT_WATCHDOG_TRIP), codes());
        assertEquals(Long.valueOf(1000), r.countEpochMs());
        now = 3000;
        r.watchdogArmed(); // a run's M990 S3000: the firmware forgets its trip
        now = 4000;
        assertTrue(r.watchdogTripped("the host watchdog tripped", true), "reported after an arm: a new one");
        assertTrue(r.watchdogTripped("Error:host watchdog", false), "heard as it happened: always new");
        now = 5000;
        r.connected(reply(BOOT), 'B', null); // a power cycle: it boots without the trip
        now = 6000;
        assertTrue(withIdle().watchdogTripped("the host watchdog tripped", true), "after the reset, read back");
        assertEquals(Long.valueOf(6000), withIdle().countEpochMs());
    }

    /**
     * #11 review (11-04): a trip heard as it happens is recorded at once, in memory (the driver's reader thread does no
     * I/O), so the arm after it on the wire ends it; {@link ControllerReset#flush} writes both, in their order.
     */
    @Test
    public void aTripHeardIsRecordedAtOnceAndWrittenByTheFlush() {
        ControllerReset r = withIdle();
        now = 1000;
        r.watchdogTripHeard("the controller's host watchdog tripped: Error:host watchdog");
        now = 2000;
        r.watchdogArmed(); // a stream's M990 S: the firmware forgot that trip
        assertTrue(journal.isEmpty(), "no I/O on the reader thread or between a stream's lines: " + codes());
        r.flush();
        assertEquals(Arrays.asList(ControllerReset.EVENT_MOTOR_OFF, ControllerReset.EVENT_WATCHDOG_TRIP,
                ControllerReset.EVENT_WATCHDOG_ARMED), codes());
        now = 3000;
        assertTrue(withIdle().watchdogTripped("the host watchdog tripped", true), "read back: one reported now is new");
    }

    /**
     * A journal write runs outside the lock: a status poll writing a switch-off it found must not make the stream's next
     * move wait for the insert.
     */
    @Test
    public void aJournalWriteNeverHoldsTheStreamsNextMove() throws Exception {
        java.util.concurrent.CountDownLatch writing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ControllerReset r = new ControllerReset(() -> now, false, false, null, e -> {
            writing.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS); // a slow insert
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, () -> idleMs);
        now = 1000;
        r.screwMoved();
        now = 1000 + 2 * HOUR;
        Thread poll = new Thread(r::view); // finds the idle switch-off and writes it
        poll.start();
        assertTrue(writing.await(5, java.util.concurrent.TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        r.screwMoved();
        r.axisMoved();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        release.countDown();
        poll.join(5000);
        assertTrue(ms < 1000, "the moves waited " + ms + " ms for the journal");
    }

    /**
     * The confirm before the home after a reset (the process engineer, 11-04): the see-saw levelled, both tips up.
     * BR-96: it says what must be true, for whoever checks (an agent's cameras, or a person), not who checks.
     */
    @Test
    public void theHomeCheckLevelsTheSeeSawFirst() {
        String c = ControllerReset.HOME_CHECK;
        assertTrue(c.contains("homing corner (X, then Y) at the height the tools are at now, then homes Z there"), c);
        assertTrue(c.contains("before Home, nothing is under either tool (N1 or the needle) or in their way to that "
                + "corner, and the see-saw is levelled, both tips up"), c);
        assertTrue(c.contains("by hand") && c.contains("Z-only moves"), "either way of levelling: " + c);
        assertFalse(c.contains("check that"), c);
    }

    /** BR-96: the home that ends an unknown position journals who attested it and their evidence, once. */
    @Test
    public void theHomeAfterAResetSaysWhoAttestedIt() {
        ControllerReset r = run(false, false);
        now = 1000;
        r.connected(reply(BOOT), 'B', null);
        r.attest("Process", "{\"run\":\"r-17\",\"pictures\":[3,4]}");
        assertNull(r.view().get("lastAttestation"), "not before the home");
        now = 2000;
        r.homed();
        ControllerReset.Event known = journal.get(journal.size() - 1);
        assertEquals(ControllerReset.EVENT_KNOWN, known.code);
        assertTrue(known.message.contains("attested by Process (evidence: {\"run\":\"r-17\",\"pictures\":[3,4]})"),
                known.message);
        Map<?, ?> last = (Map<?, ?>) r.view().get("lastAttestation");
        assertEquals("Process", last.get("by"));
        assertEquals("{\"run\":\"r-17\",\"pictures\":[3,4]}", last.get("evidence"));
        assertEquals(2000L, last.get("atMs"));

        // a later reset homed without a word of its own (OpenPnP's own Home): the old attestation is not reused
        now = 3000;
        r.connected(reply(BOOT), 'B', null);
        now = 4000;
        r.homed();
        known = journal.get(journal.size() - 1);
        assertEquals(ControllerReset.EVENT_KNOWN, known.code);
        assertFalse(known.message.contains("attested"), known.message);
        assertEquals(2000L, ((Map<?, ?>) r.view().get("lastAttestation")).get("atMs"), "the last one stays shown");

        // nor one a home took while the position was known, nor one cleared (a home that failed before its end)
        r.attest("Process", null);
        now = 5000;
        r.homed();
        r.attest("UI", "\"picture 9\"");
        r.attest(null, null);
        now = 6000;
        r.connected(reply(BOOT), 'B', null);
        now = 7000;
        r.homed();
        assertFalse(journal.get(journal.size() - 1).message.contains("attested"), journal.toString());
    }

    /** BR-96: no evidence given reads so. */
    @Test
    public void anAttestationWithoutEvidenceSaysSo() {
        ControllerReset r = run(false, false);
        r.connected(reply(BOOT), 'B', null);
        r.attest("the web UI's user", null);
        r.homed();
        String m = journal.get(journal.size() - 1).message;
        assertTrue(m.contains("attested by the web UI's user (no evidence given)"), m);
    }

    /**
     * BR-97 review (MAJOR): the see-saw's levelling is bounded around the Z where the position became unknown: Marlin's
     * boot Z 0 after a detected reset (on record, so also after a restart), else the first levelling call's Z. A reset,
     * a possible one (an unread connect), a home's start and a home take it afresh; a Stop and Start keeps it.
     */
    @Test
    public void theLevellingReferenceIsWhereThePositionBecameUnknown() {
        ControllerReset r = run(false, false);
        assertEquals(20.0, r.levelReference(20), 1e-9, "no reset: the first call's Z");
        assertEquals(20.0, r.levelReference(25), 1e-9, "kept");
        now = 1000;
        r.connected(reply(BOOT), 'B', null);
        assertEquals(0.0, r.levelReference(30), 1e-9, "a detected reset: Marlin's boot Z 0, not the call's Z");
        now = 1500;
        r.connected(reply("X:0.0000 Y:0.0000 Z:30.0000 A:720.0000 B:720.0000 Count X:0 Y:0 Z:0"), 'B', null);
        assertEquals(0.0, r.levelReference(30), 1e-9, "a Stop and Start, the controller powered: kept");
        ControllerReset restarted = run(false, false);
        assertEquals(0.0, restarted.levelReference(30), 1e-9, "an OpenPnP restart: the reset is on record");
        restarted.homeStarted();
        assertEquals(31.5, restarted.levelReference(31.5), 1e-9, "a home's G28 moved the frame, homed or not");
        assertEquals(31.5, run(false, false).levelReference(31.5), 1e-9, "a home's start is on record (re-review)");
        now = 2000;
        restarted.connected(null, 'B', "no answer");
        assertEquals(12.0, restarted.levelReference(12), 1e-9, "a possible reset: taken afresh");
        now = 3000;
        restarted.homed();
        assertEquals(7.0, restarted.levelReference(7), 1e-9, "a home: taken afresh");
        assertEquals(7.0, run(false, false).levelReference(9), 1e-9, "homed since the reset: no boot Z after a "
                + "restart, the Z taken since is on record");
    }

    /**
     * BR-97 x BR-93: a lost link keeps the levelling reference and the reconnect decides: a powered controller keeps
     * it, the boot position read sets Marlin's boot Z 0, an unread reconnect (a possible reset) takes it afresh. The
     * reference kept is on record after the loss's row (BR-97 follow-up), so a restart keeps it too, and a levelling
     * call does not write it again.
     */
    @Test
    public void aLostLinkKeepsTheLevellingReferenceUntilTheReconnectDecides() {
        ControllerReset r = run(false, false);
        assertEquals(20.0, r.levelReference(20), 1e-9);
        now = 1000;
        r.linkLost(1000, "the link ended");
        r.flush(); // the journal thread's write
        int rows = journal.size();
        assertEquals(20.0, r.levelReference(5), 1e-9, "a lost link moves no frame by itself");
        assertEquals(rows, journal.size(), "on record by the loss's own row: not written again");
        assertEquals(20.0, run(false, false).levelReference(5), 1e-9, "after a restart too");
        now = 1500;
        r.connected(reply("X:0.0000 Y:0.0000 Z:5.0000 A:720.0000 B:720.0000 Count X:0 Y:0 Z:0"), 'B', null);
        assertEquals(20.0, r.levelReference(5), 1e-9, "a powered controller: kept");
        now = 2000;
        r.linkLost(2000, "the link ended");
        r.connected(reply(BOOT), 'B', null);
        assertEquals(0.0, r.levelReference(5), 1e-9, "a reset the reconnect detects: Marlin's boot Z 0");
        assertEquals(0.0, run(false, false).levelReference(5), 1e-9, "after a restart too: the reset followed the "
                + "loss");
        now = 3000;
        r.linkLost(3000, "the link ended");
        r.connected(null, 'B', "timeout");
        assertEquals(12.0, r.levelReference(12), 1e-9, "an unread reconnect: a possible reset, taken afresh");
    }

    /**
     * BR-97 follow-up: a levelling reference on record (LEVEL_REF: taken afresh, or kept by a lost link) is read back
     * at a restart unless a row that ends one follows it in the journal: a reset (then Marlin's boot Z 0), a possible
     * reset (an unread connect, a lost link), a home's start or a home (then taken afresh). The journal's order
     * decides, also in one ms: a lost link writes its LEVEL_REF right after its POSITION_UNREAD row, in that row's ms.
     */
    @Test
    public void aLevellingReferenceOnRecordOutlivesARestartUntilARowEndsIt() {
        ControllerReset.Event ref = new ControllerReset.Event(ControllerReset.EVENT_LEVEL_REF, 2000,
                "levelling reference Z 20.0000 (a test)");
        for (String code : new String[] {ControllerReset.EVENT_RESET, ControllerReset.EVENT_UNREAD,
                ControllerReset.EVENT_HOME_STARTED, ControllerReset.EVENT_KNOWN}) {
            double ended = ControllerReset.EVENT_RESET.equals(code) ? 0 : 12; // else taken afresh: the call's Z 12
            assertEquals(20.0, replay(row(code, 1000), ref).levelReference(12), 1e-9, "after " + code);
            assertEquals(20.0, replay(row(code, 2000), ref).levelReference(12), 1e-9, "after " + code + ", one ms");
            assertEquals(ended, replay(ref, row(code, 2000)).levelReference(12), 1e-9, "before " + code + ", one ms");
            assertEquals(ended, replay(ref, row(code, 3000)).levelReference(12), 1e-9, "before " + code);
        }
        assertEquals(20.0, replay(ref).levelReference(12), 1e-9, "nothing ended it");
        // a message whose Z cannot be read counts as no reference: the next levelling call takes it afresh
        assertEquals(12.0, replay(row(ControllerReset.EVENT_UNREAD, 1000), new ControllerReset.Event(
                ControllerReset.EVENT_LEVEL_REF, 2000, "levelling reference Z twenty (a test)")).levelReference(12),
                1e-9);
    }

    /** An engine that starts on this journal (the newest row of each code, oldest first). */
    ControllerReset replay(ControllerReset.Event... history) {
        return new ControllerReset(() -> now, false, false, Arrays.asList(history), journal::add);
    }

    static ControllerReset.Event row(String code, long atMs) {
        return new ControllerReset.Event(code, atMs, "a test");
    }

    /**
     * BR-97 follow-up: a reference taken afresh goes on record first: when its row cannot be written the call fails and
     * the reference is not taken (the next call writes it). A home whose start is not on record (OpenPnP's own Home
     * button) ends one on record too.
     */
    @Test
    public void aLevellingReferenceIsTakenOnlyOnRecord() {
        boolean[] full = {true};
        ControllerReset r = new ControllerReset(() -> now, false, false, null, e -> {
            if (full[0] && ControllerReset.EVENT_LEVEL_REF.equals(e.code)) {
                throw new IllegalStateException("disk full");
            }
            journal.add(e);
        });
        now = 1000;
        assertThrows(IllegalStateException.class, () -> r.levelReference(20));
        full[0] = false;
        assertEquals(5.0, r.levelReference(5), 1e-9, "not taken: the next call takes it");
        assertEquals(5.0, run(false, false).levelReference(9), 1e-9, "on record");
        now = 2000;
        r.homed(); // OpenPnP's own Home button: no home's start on record, the position known all along
        assertEquals(9.0, run(false, false).levelReference(9), 1e-9, "that home ended it, on record too");
    }

    /**
     * BR-97 follow-up review: a lost link's LEVEL_REF that cannot be written (on the journal thread) loses no row after
     * it, and the next levelling call writes the kept reference again before it counts; while it cannot, the call is
     * refused, so a restart mid-walk never counts the 32 mm afresh. The row is matched as itself, not by the Z its text
     * keeps to 4 decimals (LEVEL_REF review NIT: here a Z of 6).
     */
    @Test
    public void aLostLinksReferenceThatMissedTheJournalIsWrittenAgainFirst() {
        boolean[] full = {false};
        ControllerReset r = new ControllerReset(() -> now, false, false, null, e -> {
            if (full[0] && ControllerReset.EVENT_LEVEL_REF.equals(e.code)) {
                throw new IllegalStateException("disk full");
            }
            journal.add(e);
        });
        now = 1000;
        assertEquals(20.123456, r.levelReference(20.123456), 1e-9);
        full[0] = true;
        now = 2000;
        r.linkLost(2000, "the link ended");
        r.flush(); // the journal thread's write: its LEVEL_REF fails, nothing escapes, the rows after it go on
        assertEquals(ControllerReset.EVENT_MOTOR_OFF, journal.get(journal.size() - 1).code, codes().toString());
        assertThrows(IllegalStateException.class, () -> r.levelReference(5), "the kept reference is not on record");
        full[0] = false;
        assertEquals(20.123456, r.levelReference(5), 1e-9, "written again, then it counts");
        int rows = journal.size();
        assertEquals(20.123456, r.levelReference(5), 1e-9);
        assertEquals(rows, journal.size(), "once on record it is not written again");
        assertEquals(20.1235, run(false, false).levelReference(5), 1e-9, "a restart keeps it, to 4 decimals");
    }

    /**
     * LEVEL_REF review NIT: a lost link's reference that missed the journal ends with the reference. After a home, a
     * home's start or a reset (Marlin's boot Z 0, on record by the reset's row), a later reference of the same Z is not
     * written again as kept at a lost link, also when the miss is heard after the change (its write drains the loss's
     * rows).
     */
    @Test
    public void aLostLinksMissedReferenceEndsWithTheReference() {
        List<Consumer<ControllerReset>> ends = Arrays.asList(ControllerReset::homed, ControllerReset::homeStarted,
                r -> r.connected(reply(BOOT), 'B', null));
        for (Consumer<ControllerReset> end : ends) {
            journal.clear();
            boolean[] full = {false};
            ControllerReset r = new ControllerReset(() -> now, false, false, null, e -> {
                if (full[0] && ControllerReset.EVENT_LEVEL_REF.equals(e.code)) {
                    throw new IllegalStateException("disk full");
                }
                journal.add(e);
            });
            now = 1000;
            r.levelReference(0);
            full[0] = true;
            now = 2000;
            r.linkLost(2000, "the link ended"); // its LEVEL_REF misses the journal
            now = 3000;
            end.accept(r);
            full[0] = false;
            int rows = journal.size();
            assertEquals(0.0, r.levelReference(0), 1e-9);
            assertEquals(0.0, r.levelReference(0), 1e-9);
            for (ControllerReset.Event e : journal.subList(rows, journal.size())) {
                assertFalse(e.message.contains("kept at a lost link"), e.message);
            }
        }
    }

    /**
     * LEVEL_REF review MINOR (safety): a levelling call's reference and its row are one step in the journal's order.
     * The call (an API thread) starts while the journal writer is busy with an earlier row; a connect that reads a
     * reset, or a home's end (the machine thread), changes the reference before the call's row is written. A restart
     * then replays the reference the engine holds, never the call's Z in a frame the reset or the home moved.
     */
    @Test
    public void aResetOrAHomeDuringALevellingCallLeavesTheJournalAsTheEngine() throws Exception {
        assertEquals(0.0, levelDuring(r -> r.connected(reply(BOOT), 'B', null)), 1e-9, "the reset's boot Z");
        journal.clear();
        assertEquals(20.0, levelDuring(ControllerReset::homed), 1e-9, "taken after the home");
    }

    /**
     * A levelling call at Z 20 (the position unknown, no reference yet) while the journal writer is busy with another
     * row; {@code change} runs on another thread once the call has read the clock, and the writer goes on once the
     * change is made. Returns the reference the engine holds, checked against a restart's.
     */
    double levelDuring(Consumer<ControllerReset> change) throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Map<Thread, CountDownLatch> clockRead = new ConcurrentHashMap<>();
        ControllerReset r = new ControllerReset(() -> {
            clockRead.getOrDefault(Thread.currentThread(), new CountDownLatch(0)).countDown();
            return now;
        }, false, false, null, e -> {
            if (ControllerReset.EVENT_MOTOR_OFF.equals(e.code)) {
                writing.countDown();
                await(go); // a slow insert
            }
            journal.add(e);
        });
        r.connected(null, 'B', "timeout");
        List<Throwable> failed = Collections.synchronizedList(new ArrayList<>());
        Thread writer = start(clockRead, failed, () -> r.screwMotorOff("a STOP"));
        await(writing);
        Thread call = start(clockRead, failed, () -> r.levelReference(20));
        await(clockRead.get(call)); // it reads the clock as it starts, before it waits for the writer
        Thread machine = start(clockRead, failed, () -> change.accept(r));
        await(clockRead.get(machine));
        synchronized (r) {
            go.countDown(); // the change is made: it read the clock inside the engine's lock
        }
        for (Thread t : Arrays.asList(writer, call, machine)) {
            t.join(5000);
            assertFalse(t.isAlive(), t + " still runs");
        }
        assertTrue(failed.isEmpty(), failed.toString());
        double replayed = run(false, false).levelReference(99);
        double held = r.levelReference(99);
        assertEquals(held, replayed, 1e-9, "a restart replays the reference the engine holds: " + codes());
        return held;
    }

    /** Starts {@code body} on a thread whose first clock read counts its latch in {@code clockRead} down. */
    static Thread start(Map<Thread, CountDownLatch> clockRead, List<Throwable> failed, Runnable body) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            }
            catch (Throwable e) {
                failed.add(e);
            }
        });
        clockRead.put(t, new CountDownLatch(1));
        t.start();
        return t;
    }

    static void await(CountDownLatch l) {
        try {
            assertTrue(l.await(5, TimeUnit.SECONDS), "timed out");
        }
        catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * BR-97 follow-up review: a reference taken while a lost link's rows still wait for the journal thread is written
     * after them, so the replay sees it last.
     */
    @Test
    public void aReferenceTakenBeforeALostLinksRowsAreWrittenComesAfterThem() {
        ControllerReset r = run(false, false);
        now = 1000;
        r.linkLost(1000, "the link ended"); // no reference yet: its rows wait for the journal thread
        assertEquals(5.0, r.levelReference(5), 1e-9);
        r.flush();
        assertEquals(Arrays.asList(ControllerReset.EVENT_UNREAD, ControllerReset.EVENT_MOTOR_OFF,
                ControllerReset.EVENT_LEVEL_REF), codes(), "the loss's rows, then the reference");
        assertEquals(5.0, run(false, false).levelReference(9), 1e-9, "on record after the loss's rows");
    }

    /**
     * LEVEL_REF re-review: a reference whose row fails is given up (the next call takes it afresh) only if nothing
     * changed it while the row was written: a reset read meanwhile keeps its boot Z (here also the call's Z), and a
     * lost link meanwhile keeps the Z, on record by its own row. The journal then agrees with the engine.
     */
    @Test
    public void aFailedReferenceIsGivenUpOnlyIfNothingChangedItMeanwhile() throws Exception {
        for (boolean reset : new boolean[] {true, false}) {
            journal.clear();
            Map<Thread, CountDownLatch> clockRead = new ConcurrentHashMap<>();
            List<Throwable> failed = Collections.synchronizedList(new ArrayList<>());
            ControllerReset[] engine = new ControllerReset[1];
            Thread[] meanwhile = new Thread[1];
            ControllerReset r = new ControllerReset(() -> {
                clockRead.getOrDefault(Thread.currentThread(), new CountDownLatch(0)).countDown();
                return now;
            }, false, false, null, e -> {
                if (ControllerReset.EVENT_LEVEL_REF.equals(e.code) && meanwhile[0] == null) {
                    ControllerReset c = engine[0];
                    meanwhile[0] = start(clockRead, failed, reset ? () -> c.connected(reply(BOOT), 'B', null)
                            : () -> c.linkLost(now, "the link ended"));
                    await(clockRead.get(meanwhile[0])); // read inside the engine's lock, where it makes the change
                    synchronized (c) {
                        throw new IllegalStateException("disk full");
                    }
                }
                journal.add(e);
            });
            engine[0] = r;
            now = 1000;
            assertThrows(IllegalStateException.class, () -> r.levelReference(0));
            meanwhile[0].join(5000);
            assertFalse(meanwhile[0].isAlive());
            assertTrue(failed.isEmpty(), failed.toString());
            r.flush(); // the journal thread's write
            double replayed = run(false, false).levelReference(99);
            assertEquals(0.0, r.levelReference(99), 1e-9, reset ? "the reset's boot Z" : "kept by the lost link");
            assertEquals(0.0, replayed, 1e-9, "a restart replays the same: " + codes());
        }
    }

    /**
     * The home after a detected reset moves the Z frame (K by twice the shift): it says so once, also when the reset was
     * homed only after an engine restart; an ordinary home does not. A connect whose position was not read may have been
     * a reset (I11 counts it so): its home moves the frame too (#11 review 11-04, the conservative side).
     */
    @Test
    public void theHomeAfterAResetMovesTheZFrame() {
        ControllerReset r = run(false, false);
        now = 1000;
        r.connected(reply(PARKED), 'B', null);
        assertFalse(r.homed(), "an ordinary home keeps the frame");
        now = 2000;
        r.connected(reply(BOOT), 'B', null);
        now = 3000;
        assertTrue(r.homed(), "the home after the reset");
        assertEquals(Long.valueOf(3000), r.frameMovedAtMs());
        now = 4000;
        assertFalse(r.homed(), "once");
        r.connected(null, 'B', "timeout");
        assertTrue(r.homed(), "a home after an unread connect: it may have been a reset");
        assertEquals(Long.valueOf(4000), run(false, false).frameMovedAtMs(), "read back");
        now = 5000;
        run(false, false).connected(reply(BOOT), 'B', null); // reset, then OpenPnP restarted before the home
        now = 6000;
        ControllerReset after = run(false, false);
        assertTrue(after.homed());
        assertEquals(Long.valueOf(6000), after.frameMovedAtMs());
        now = 7000;
        run(false, false).connected(reply(BOOT), 'B', null);
        now = 8000;
        assertEquals(Long.valueOf(8000), run(true, true).frameMovedAtMs(), "an engine started on a homed machine");
        now = 9000;
        run(false, false).connected(null, 'B', "timeout"); // unread, then OpenPnP restarted before the home
        now = 10000;
        assertTrue(run(false, false).homed(), "an unread connect read back");
        now = 11000;
        ControllerReset caughtUp = run(false, false);
        caughtUp.startedUnhomed(); // a connect made before the engine's start: its read may never come (#11 re-review)
        assertTrue(caughtUp.homed(), "a connect not read yet");
    }

    @Test
    public void theScrewCountEpochMovesOnlyOnAResetOrAnUnreadConnect() {
        ControllerReset r = reset(false, false);
        now = 1000;
        r.connected(reply(BOOT), 'B', null);
        assertEquals(Long.valueOf(1000), r.countEpochMs());
        r.homed();
        now = 2000;
        r.connected(reply(PARKED), 'B', null);
        assertEquals(Long.valueOf(1000), r.countEpochMs(), "a plain reconnect keeps the count");
        now = 3000;
        r.connected(null, 'B', "no reply");
        assertEquals(Long.valueOf(3000), r.countEpochMs());
        now = 4000;
        r.connected(reply(PARKED), 'B', null);
        assertEquals(Long.valueOf(3000), r.countEpochMs(), "an unread connect stays a possible restart");
        now = 5000;
        r.connected(reply(BOOT), 'B', null);
        assertEquals(Long.valueOf(5000), r.countEpochMs());
    }
}
