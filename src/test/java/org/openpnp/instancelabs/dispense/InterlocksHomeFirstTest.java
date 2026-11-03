package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A task that homes by itself does not refuse "not homed"; Register / probe use the basic checks only. */
public class InterlocksHomeFirstTest {
    @Test
    public void homeFirstTurnsNotHomedIntoANote() {
        Interlocks.Inputs in = InterlocksTest.ok();
        in.machineHomed = false;
        InterlockReport r = Interlocks.check(in);
        assertTrue(r.has("MACHINE"));
        assertTrue(r.toString().contains("machine is not homed"));
        in.homeFirst = true;
        r = Interlocks.check(in);
        assertTrue(r.ok(), r.toString());
        assertTrue(r.notes.contains("MACHINE: not homed: homes first"));
    }

    @Test
    public void notEnabledStaysAViolation() {
        Interlocks.Inputs in = InterlocksTest.ok();
        in.machineEnabled = false;
        in.machineHomed = false;
        in.homeFirst = true;
        InterlockReport r = Interlocks.check(in);
        assertEquals(1, r.violations.size(), r.toString());
        assertEquals("MACHINE: machine is not enabled", r.violations.get(0).toString());
        r = Interlocks.checkBasic(in);
        assertEquals(1, r.violations.size(), r.toString());
        assertEquals("MACHINE: machine is not enabled", r.violations.get(0).toString());
    }

    @Test
    public void checkBasicReportsOnlyConfigAndMachine() {
        Interlocks.Inputs in = InterlocksTest.ok();
        in.cfg.needleAnchor = null; // I4
        in.compatibleNozzleTips = java.util.Arrays.asList("NT1"); // I3
        in.estimatedPressurisedSeconds = 1e6; // I7
        in.cfg.purgeRow.spots = 0; // I9
        in.opticalCheckAvailable = false; // I10
        in.cfg.profile.startAngleSetAtMs = null; // I11
        in.cfg.hopHeightMm = -1; // CONFIG
        in.machineHomed = false; // MACHINE
        InterlockReport full = Interlocks.check(in);
        for (String code : new String[] {"I3", "I4", "I7", "I9", "I10", "CONFIG", "MACHINE"}) {
            assertTrue(full.has(code), code + " in " + full);
        }
        InterlockReport basic = Interlocks.checkBasic(in);
        for (InterlockReport.Violation v : basic.violations) {
            assertTrue(v.code.equals("CONFIG") || v.code.equals("MACHINE"), v.toString());
        }
        assertTrue(basic.has("CONFIG") && basic.has("MACHINE"));
        in.cfg.hopHeightMm = 1;
        in.homeFirst = true;
        basic = Interlocks.checkBasic(in);
        assertTrue(basic.ok(), basic.toString());
        assertFalse(basic.notes.isEmpty());
    }
}
