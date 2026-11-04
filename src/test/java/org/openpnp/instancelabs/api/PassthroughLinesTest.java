package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * BR-72: what the G-code passthrough lets through: the bench tools' lines (Process's BR-72 list, and every command in
 * runs/serial.log 10-27..11-03), hard limits only (the machine's soft limits, homed for X/Y/Z moves, the pump and valve
 * full on, the screw current); never BR-03's dispensing zones.
 */
public class PassthroughLinesTest {
    /** The live machine's soft limits (raw: camera X ≤ 408 since 11-02). */
    static final Map<Character, double[]> LIMITS = new LinkedHashMap<>();

    static {
        LIMITS.put('X', new double[] {0, 408});
        LIMITS.put('Y', new double[] {0, 410});
        LIMITS.put('Z', new double[] {Double.NaN, 63});
        LIMITS.put('A', new double[] {-200, 200});
    }

    static PassthroughLines.Line ok(String line) {
        PassthroughLines.Line l = PassthroughLines.check(line, LIMITS, true);
        assertNull(l.refusal, line + ": " + l.refusal);
        return l;
    }

    static String refused(String line) {
        return refused(line, true);
    }

    static String refused(String line, boolean homed) {
        String why = PassthroughLines.check(line, LIMITS, homed).refusal;
        assertNotNull(why, line + " must be refused");
        return why;
    }

    @Test
    public void theBenchToolsLinesPass() {
        for (String l : Arrays.asList("G1 X200.0000 Y250.0000 F6000", "G1 Z31.5 F600", "G0 X10 Y10", "G1 B-30 F20000",
                "G1 B22000 F66000", "G1 X100 Y120 B360 F300", "G1 A90 F2000", "G4 P250", "G4 S30", "M400", "M114",
                "M115", "M119", "M410", "G90", "G92 B0", "G92 A0 B0", "M106 P2 S255", "M106 P3 S255", "M106 P3",
                "M107 P3", "M107 P2", "M107 P1", "M107 P0", "M107", "M106", "M106 P1 S180", "M106 P0 S128",
                "M150 P255 R255 U255 B255", "M150 R0 U0 B0", "M204 S2000", "M204 S20000", "M201 B10000",
                "M906 B580", "M906 B670", "M906", "M18 B", "M17 B", "M260 A112 B1 S1", "M260 A109",
                "M260 B48", "M260 S1", "M261 A109 B3 S1", "M485 FFFF0001", "M42 I P53 T2", "M42 P53 T2",
                "M42 P53 T0", "M42 P53 T3", "M43 P5", "M43 I P5")) {
            ok(l);
        }
    }

    @Test
    public void linesAreNormalisedAndCommentsDropped() {
        assertEquals("G1 X10 Y20 F6000", ok("  g1   x10 y20  f6000 ; to the waste").text);
        assertEquals("M400", ok("M400 ; wait").text);
        assertEquals("G1 X10 Y20", ok("G1X10Y20").text);
        PassthroughLines.Line blank = ok("   ; only a comment");
        assertTrue(blank.empty);
        assertTrue(ok("").empty);
    }

    @Test
    public void whatALineDoesIsKnown() {
        assertTrue(ok("G1 X1 F600").motion);
        assertTrue(ok("G1 B90").motion);
        assertTrue(ok("G0 Z30").motion);
        assertFalse(ok("M114").motion);
        assertTrue(ok("M400").sync);
        assertFalse(ok("G4 P100").sync, "only M400 counts as the end of queued motion");
        assertTrue(ok("G92 B0").frame);
        assertTrue(ok("G1 X1").frame);
        assertFalse(ok("M106 P2").frame);
    }

    @Test
    public void theMachinesLimitsHold() {
        assertTrue(refused("G1 X408.5").contains("408"));
        ok("G1 X408");
        assertTrue(refused("G1 Y-1").contains("Y"));
        assertTrue(refused("G1 Z63.01").contains("Z"));
        ok("G1 Z-5"); // no low Z limit set
        // X/Y/Z only once homed; B, outputs and reads before
        assertTrue(refused("G1 X10", false).contains("home"));
        assertTrue(refused("G0 Z10", false).contains("home"));
        assertNull(PassthroughLines.check("G1 B90 F600", LIMITS, false).refusal);
        assertNull(PassthroughLines.check("M114", LIMITS, false).refusal);
        // the pump and the valve only full on (plan §4.2a: never PWM through the buck; the valve needs >= 90 %)
        assertTrue(refused("M106 P2 S128").contains("255"));
        assertTrue(refused("M106 P3 S180").contains("255"));
        assertTrue(refused("M106 P4 S255").contains("P0-P3"));
        assertTrue(refused("M107 P7").contains("P0-P3"));
        // the screw motor's rated current
        assertTrue(refused("M906 B700").contains("670"));
        assertTrue(refused("M906 X800").contains("B"));
        assertTrue(refused("G4 P61000").contains("60"));
        assertTrue(refused("G4 S61").contains("60"));
        // #19 review MINOR 4: Marlin reads G4 P with strtoul (-3e9 = a 15-day dwell that blocks a STOP's valve-off)
        refused("G4 P-3000000000");
        refused("G4 S-1");
        refused("M204 S0");
        refused("M204 S-500");
        refused("G1 B" + new String(new char[400]).replace('\0', '1'));
        refused("G1 B2000000");
        refused("G1 X10 F0");
        // A (N1's rotation) has soft limits too: enforced like X/Y/Z
        assertTrue(refused("G1 A250").contains("A"));
        ok("G1 A90");
        // #19 review MINOR 9: M42 only on the switch pin
        refused("M42 I P45 T2");
        refused("M43 P12");
        ok("M43 P5");
        // 11-04: the switch is on PA5 (the HACK header's pin 3), not BR-48's PC0: PC0's numbers are refused, and each
        // command takes its own number for PA5 (M42 the Arduino pin 53, M43 the pin table's entry 5)
        refused("M42 I P44 T2");
        refused("M43 P29");
        refused("M43 P53");
        refused("M42 P5 T2");
    }

    @Test
    public void aQuickStopLeavesOpenPnpsPositionStale() {
        // #19 review MINOR 6: an M410 can stop a move short: OpenPnP must re-read where the head is
        assertTrue(ok("M410").frame);
        assertFalse(ok("M410").motion);
    }

    @Test
    public void whatBreaksOpenPnpsStateOrTheFirmwareIsRefusedWithTheRouteToUse() {
        assertTrue(refused("G28").contains("/api/machine/home"));
        assertTrue(refused("G28 X").contains("/api/machine/home"));
        assertTrue(refused("G92 X217.088 Y200.225").contains("G92"));
        assertTrue(refused("G92 Z0").contains("G92"));
        assertTrue(refused("G92").contains("G92"));
        assertTrue(refused("M18").contains("M18 B"));
        assertTrue(refused("M84").contains("M18 B"));
        assertTrue(refused("M18 X").contains("M18 B"));
        assertTrue(refused("G91").contains("absolute"));
        refused("M92 B4.44");
        refused("M500");
        refused("M502");
        refused("M503");
        refused("M112");
        refused("M999");
        refused("M997");
        refused("M990 S2000");
        refused("M43 T");
        refused("M43");
        refused("M42 P53 S255");
        refused("M42 P53 T1");
        refused("M201 X5000");
        refused("M203 X100");
        refused("G1 E5");
        refused("T1");
        refused("G1 X10 M400");
        refused("N10 G1 X10*45");
        refused("G1 X1e3");
        refused("G1 Xabc");
        assertTrue(refused("M3 S1000").contains("ask Backend"));
    }
}
