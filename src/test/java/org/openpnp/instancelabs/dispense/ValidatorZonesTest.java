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

    /**
     * Review (MINOR): a program's lines get one I12 entry, the first line with where its paste would land and what to
     * do; never "park first" (a program does not park).
     */
    @Test
    public void aScrewTurnAtTheCameraIsRefusedEvenWithTheValveOpenedElsewhere() {
        DispenseConfig c = config();
        List<String> v = check(c, prog("M106 P2 S255", "M106 P3 S255", "M107 P3", "M107 P2", "G1 " + xy(205, 32)
                + " F6000", "M106 P2 S255", "M106 P3 S255", "G1 B90.0000 F21600", "M107 P3", "M107 P2"), 100, 50);
        assertEquals(1, v.size(), "one entry for the valve and the B move, both at the camera: " + v);
        assertTrue(v.get(0).startsWith("I12: 2 valve or screw lines put paste where it may not go; the first: the "
                + "valve ON at line 7 puts paste at (205.0, 32.0), over the bottom camera"), v.toString());
        assertTrue(v.get(0).endsWith(": set that place or surface 15 mm or more away from it"), v.toString());
    }

    /** Review (MINOR): the crossing says what to do. */
    @Test
    public void aMoveWithTheValveOnMayNotCrossTheCamera() {
        DispenseConfig c = config();
        // from the waste to the towel, straight over the camera, with the valve on
        List<String> v = check(c, prog("M106 P2 S255", "M106 P3 S255", "G1 " + xy(250, 30) + " F6000", "M107 P3",
                "M107 P2"), 150, 30);
        assertTrue(v.stream().anyMatch(s -> s.startsWith("I12: line 3 moves the needle from (150.0, 30.0) to (250.0, "
                + "30.0) with the valve on, across the bottom camera (15 mm around it): set the places or boards at "
                + "its ends so that the path between them misses it")), v.toString());
    }

    /**
     * Review (MINOR): a dot beyond a probed surface names its pad, how far beyond the points it lands and the remedy:
     * ZFrameTest's ftp board with R16.1 beyond the margin (a run is refused by I4 first, with the same rule).
     */
    @Test
    public void aDotBeyondAProbedSurfaceNamesItsPad() throws Exception {
        DispenseConfig c = ZFrameTest.cfg();
        ToyMapper m = new ToyMapper(290, 125, 31.5, 0);
        GcodeProgram prog = GcodeStreamBuilder.forPlan(c, m, OutputZonesTest.ftp(c, 367.3, 151.3),
                java.util.Collections.emptyList(), false, false, 0L);
        List<String> v = GcodeStreamValidator.validate(prog, c, "B", null,
                GcodeStreamValidator.ZoneCheck.of(OutputZones.of(c, OutputZonesTest.CAMERA), m));
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).matches("I12: \\d+ valve or screw lines put paste where it may not go; the first: dot "
                + "'R16\\.1' at line \\d+ puts paste at \\(367\\.3, 151\\.3\\), 4\\.\\d mm beyond the probed 'board' "
                + "surface's points \\(margin 3\\.5 mm\\): probe around it"), v.get(0));
    }

    /** One dot whose paste lands at (x, 30), built (ToyMapper) with the tip's landing + trim (landingX, 0), checked. */
    static List<String> landingAt(DispenseConfig c, double landingX, double x) throws Exception {
        GcodeStreamBuilder.TipXy xy = new GcodeStreamBuilder.TipXy();
        xy.dxMm = landingX;
        ToyMapper m = new ToyMapper(230, 60, 31.5, 0);
        GcodeProgram p = GcodeStreamBuilder.forDots(c, m, java.util.Collections.singletonList(
                new GcodeStreamBuilder.DotSpec(x, 30, 3, 4, 180, 0, "dot")), true, false, false, 0, "landing", xy);
        return GcodeStreamValidator.validate(p, c, "B", null,
                GcodeStreamValidator.ZoneCheck.of(OutputZones.of(c, OutputZonesTest.CAMERA), m));
    }

    /**
     * Review (MAJOR): the zones are where the paste lands, not where N2 is. The builder aims N2 at every target minus
     * the tip's landing + trim, so the paste lands at N2 plus that. A towel 20 mm from the camera and a 4 mm landing:
     * paste landing 13 mm from the camera (inside its 15 mm circle) is refused though N2 is 17 mm away, over the towel;
     * the reverse (N2 inside the circle, the paste on the towel) is allowed.
     */
    @Test
    public void theZonesAreWhereThePasteLandsNotWhereN2Is() throws Exception {
        DispenseConfig c = new DispenseConfig();
        c.locations.put("towel", new DispenseConfig.NamedLocation(220, 30, 5.5));
        List<String> v = landingAt(c, -4, 213);
        assertTrue(v.stream().anyMatch(s -> s.contains("I12") && s.contains("bottom camera")), v.toString());
        v = landingAt(c, 4, 217);
        assertTrue(v.stream().noneMatch(s -> s.contains("I12")), v.toString());
    }

    @Test
    public void withoutAZoneCheckNothingChanges() {
        DispenseConfig c = config();
        assertTrue(GcodeStreamValidator.validate(prog("M106 P2 S255", "M106 P3 S255", "M107 P3", "M107 P2"), c, "B",
                null).isEmpty());
    }
}
