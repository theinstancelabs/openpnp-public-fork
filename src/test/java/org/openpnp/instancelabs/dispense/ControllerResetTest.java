package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    public void aResetHomedBeforeAnEngineRestartStillDatesTheScrewCount() {
        // the review's case: start angle set, power cycle, attested home (its G92 A0 B0 removes the boot
        // position), then OpenPnP restarts and connects to a controller that reads a homed position
        ControllerReset first = run(false, false);
        now = 1000;
        first.connected(reply(BOOT), 'B', null);
        now = 2000;
        first.homed();
        assertEquals(Arrays.asList(ControllerReset.EVENT_RESET, ControllerReset.EVENT_KNOWN), codes());
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
        assertEquals(2, journal.size(), "a plain reconnect journals nothing");
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
                ControllerReset.EVENT_KNOWN), codes(), "every reset, and the home that ends the unknown position");
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
        assertTrue(r.lastScrewMotorOff().message.contains("the controller's idle timeout: no screw move for 60 min"),
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
        assertTrue(r.lastScrewMotorOff().message.contains("no screw move for 1 min"), r.lastScrewMotorOff().message);
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
