package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * BR-03 in the stream validator: the needle's X/Y (raw head X/Y + the N2 offset) at every valve-on line and every B
 * line is inside an allowed zone and outside every forbidden one; with the valve on, no X/Y move crosses a forbidden
 * zone. Raw lines here put the needle at raw + (18.5, -71).
 */
public class ValidatorZonesTest {
    static final double DX = 18.5, DY = -71;

    static DispenseConfig config() {
        DispenseConfig c = OutputZonesTest.config();
        return c;
    }

    static GcodeStreamValidator.ZoneCheck zones(DispenseConfig c, double startToolX, double startToolY) {
        return new GcodeStreamValidator.ZoneCheck(OutputZones.of(c, OutputZonesTest.CAMERA), DX, DY, "X", "Y",
                startToolX - DX, startToolY - DY);
    }

    static GcodeProgram prog(String... lines) {
        GcodeProgram p = new GcodeProgram();
        p.bZeroedAtStart = true;
        for (String l : lines) {
            p.lines.add(new GcodeProgram.Line(l, GcodeProgram.Marker.NONE, -9, null, 0, null));
        }
        return p;
    }

    /** Raw X/Y of a needle X/Y. */
    static String xy(double x, double y) {
        return String.format(java.util.Locale.ROOT, "X%.4f Y%.4f", x - DX, y - DY);
    }

    static List<String> check(DispenseConfig c, GcodeProgram p, double startX, double startY) {
        return GcodeStreamValidator.validate(p, c, "B", null, zones(c, startX, startY));
    }

    @Test
    public void aDotOverTheWasteIsFine() {
        DispenseConfig c = config();
        List<String> v = check(c, prog("G1 " + xy(100, 50) + " F6000", "M106 P2 S255", "M106 P3 S255",
                "G1 B180.0000 F21600", "M107 P3", "M107 P2"), 150, 150);
        assertTrue(v.isEmpty(), v.toString());
    }

    @Test
    public void theValveOverTheCameraIsRefused() {
        DispenseConfig c = config();
        List<String> v = check(c, prog("M106 P2 S255", "M106 P3 S255", "M107 P3", "M107 P2"), 205, 32);
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("I12") && v.get(0).contains("bottom camera"), v.toString());
    }

    @Test
    public void aScrewTurnAtTheCameraIsRefusedEvenWithTheValveOpenedElsewhere() {
        DispenseConfig c = config();
        List<String> v = check(c, prog("M106 P2 S255", "M106 P3 S255", "M107 P3", "M107 P2", "G1 " + xy(205, 32)
                + " F6000", "M106 P2 S255", "M106 P3 S255", "G1 B90.0000 F21600", "M107 P3", "M107 P2"), 100, 50);
        assertEquals(2, v.size(), "the valve and the B move, both at the camera: " + v);
        assertTrue(v.get(1).contains("'G1 B90.0000 F21600'"), v.toString());
    }

    @Test
    public void aMoveWithTheValveOnMayNotCrossTheCamera() {
        DispenseConfig c = config();
        // from the waste to the towel, straight over the camera, with the valve on
        List<String> v = check(c, prog("M106 P2 S255", "M106 P3 S255", "G1 " + xy(250, 30) + " F6000", "M107 P3",
                "M107 P2"), 150, 30);
        assertTrue(v.stream().anyMatch(s -> s.contains("crosses the bottom camera")), v.toString());
    }

    @Test
    public void withoutAZoneCheckNothingChanges() {
        DispenseConfig c = config();
        assertTrue(GcodeStreamValidator.validate(prog("M106 P2 S255", "M106 P3 S255", "M107 P3", "M107 P2"), c, "B",
                null).isEmpty());
    }
}
