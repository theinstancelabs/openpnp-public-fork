package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
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
        assertTrue(r.isDetected(), "the reset stays on record");
        assertEquals(Long.valueOf(now - 2000), r.detectedAtMs());
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
