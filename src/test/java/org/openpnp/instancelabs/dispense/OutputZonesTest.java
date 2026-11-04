package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * BR-03: where the valve and the screw may switch on. Allowed, (b) of the process engineer's 11-04 decision: the places
 * and surfaces runs put paste on, i.e. the sink places (the waste, the towel, the cup), the purge row, the calibration
 * area, each coupon, and by I4's rule the probed board and blank surfaces ({@code boardSurfaceName},
 * {@code blankLocationName}); every other place (the park, feeder picks, the oven) and surface is not. Forbidden, whatever
 * else covers it: the bottom camera (a 15 mm circle) and any configured circle.
 */
public class OutputZonesTest {
    static final double[] CAMERA = {200, 30};

    static DispenseConfig config() {
        DispenseConfig c = new DispenseConfig();
        c.locations.put("waste", new DispenseConfig.NamedLocation(100, 50, 5));
        c.locations.put("towel", new DispenseConfig.NamedLocation(210, 35, 5)); // 11 mm from the camera
        c.purgeRow.x0Mm = 80.0;
        c.purgeRow.y0Mm = 230.0;
        c.purgeRow.pitchXMm = 2.5;
        c.purgeRow.spots = 20;
        c.surfaces.put("board", patch(300, 260, 320, 260, 310, 275));
        return c;
    }

    /** A probed surface of three points at Z 5.6. */
    static DispenseConfig.SurfaceMap patch(double x0, double y0, double x1, double y1, double x2, double y2) {
        DispenseConfig.SurfaceMap m = new DispenseConfig.SurfaceMap();
        m.points.add(new DispenseConfig.SurfacePoint(x0, y0, 5.6));
        m.points.add(new DispenseConfig.SurfacePoint(x1, y1, 5.6));
        m.points.add(new DispenseConfig.SurfacePoint(x2, y2, 5.6));
        return m;
    }

    @Test
    public void placesRowsAndSurfacesAreAllowed() {
        OutputZones z = OutputZones.of(config(), CAMERA);
        assertNull(z.refusal(100, 50), "the waste");
        assertNull(z.refusal(103, 52), "near the waste, inside its radius");
        assertNull(z.refusal(80 + 19 * 2.5, 230), "the last purge spot");
        assertNull(z.lineRefusal("dot 'P1' at line 9", 310, 268), "a program's dot inside the probed board");
        assertEquals("waste", z.zoneAt(100, 50));
        assertEquals("purge row", z.zoneAt(85, 230));
        assertEquals("surface board", z.zoneAt(310, 268));
    }

    /**
     * (b), the process engineer 11-04: only the places and surfaces runs put paste on are allowed. A stored place that
     * never takes paste (the park, the oven spot) and a probed patch no run names are no allowed zone: a program's line
     * there is refused, naming the place or the surface and where paste goes.
     */
    @Test
    public void aPlaceOrSurfaceNoRunUsesIsNoAllowedZone() {
        DispenseConfig c = config();
        c.locations.put("park", new DispenseConfig.NamedLocation(60, 120, 31.5));
        c.locations.put("oven", new DispenseConfig.NamedLocation(40, 200, 5));
        c.surfaces.put("zref", patch(400, 300, 420, 300, 410, 315));
        OutputZones z = OutputZones.of(c, CAMERA);
        assertNull(z.zoneAt(60, 120), "the park");
        assertNull(z.zoneAt(40, 200), "the oven");
        assertNull(z.zoneAt(410, 305), "a probed patch no run names");
        assertEquals("dot 'shot' at line 9 puts paste at (60.0, 120.0), over the place 'park', which takes no paste: "
                + "paste goes only on the waste, the towel, the cup, the purge row, the calibration area, a coupon, or "
                + "the probed 'board' or 'blank' surface", z.lineRefusal("dot 'shot' at line 9", 60, 120));
        String oven = z.lineRefusal("dot 'shot' at line 9", 40, 200);
        assertTrue(oven != null && oven.contains("over the place 'oven', which takes no paste"), oven);
        String patch = z.lineRefusal("the valve ON at line 4", 410, 305);
        assertTrue(patch != null && patch.contains("over the probed 'zref' surface, which takes no paste"), patch);
        assertNotNull(z.refusal(60, 120), "nor does the hold open the valve there");
        // the places and areas runs use stay allowed
        assertEquals("waste", z.zoneAt(100, 50));
        assertEquals("towel", z.zoneAt(214, 38));
        assertEquals("purge row", z.zoneAt(85, 230));
    }

    /**
     * (b): the surfaces runs dispense on are allowed by I4's rule: the board surface ({@code boardSurfaceName}, which
     * Register board probes for the plan being run) and the blank ({@code blankLocationName}: the dot ladder, the
     * touch-off). The names follow the settings: with the board surface named 'tile', 'tile' is allowed, 'board' not.
     */
    @Test
    public void theBoardAndBlankSurfacesAreAllowedByI4sRule() {
        DispenseConfig c = config();
        c.surfaces.put("blank", patch(160, 110, 180, 110, 170, 130));
        OutputZones z = OutputZones.of(c, CAMERA);
        assertEquals("surface board", z.zoneAt(310, 268));
        assertEquals("surface blank", z.zoneAt(170, 120));
        assertNull(z.lineRefusal("dot 'R16.1' at line 57", 297, 260), "3 mm beyond the board's points");
        assertEquals("dot 'R16.1' at line 57 puts paste at (296.0, 260.0), 4.0 mm beyond the probed 'board' surface's "
                + "points (margin 3.5 mm): probe around it", z.lineRefusal("dot 'R16.1' at line 57", 296, 260));
        c.boardSurfaceName = "tile";
        c.surfaces.put("tile", patch(400, 300, 420, 300, 410, 315));
        z = OutputZones.of(c, CAMERA);
        assertEquals("surface tile", z.zoneAt(410, 305));
        assertNull(z.zoneAt(310, 268), "'board' is no plan's surface now");
        assertTrue(z.lineRefusal("dot 'P1' at line 9", 310, 268).endsWith("or the probed 'tile' or 'blank' surface"),
                z.lineRefusal("dot 'P1' at line 9", 310, 268));
    }

    /** (b): the bottom camera inside an allowed surface, the board, stays forbidden. */
    @Test
    public void theCameraInsideTheBoardStillWins() {
        DispenseConfig c = config();
        c.surfaces.put("board", patch(170, 0, 230, 0, 200, 60));
        OutputZones z = OutputZones.of(c, CAMERA);
        assertEquals("surface board", z.zoneAt(200, 50));
        assertEquals("forbidden: bottom camera", z.zoneAt(200, 30));
        String why = z.lineRefusal("dot 'R1.1' at line 12", 200, 30);
        assertTrue(why != null && why.contains("over the bottom camera, where the valve and the screw stay off"), why);
        assertNotNull(z.crossing(180, 30, 220, 30), "nor may a move with the valve on cross it");
    }

    /**
     * #12 final review M1: the hold opens the valve only over a sink (the waste, the towel, the cup, the purge row,
     * the calibration area, a coupon), never over a board or another surface (a finished board would take 18 psi of
     * drool); a program keeps every allowed zone (I4 holds its dots to their surface).
     */
    @Test
    public void aHoldOpensTheValveOnlyOverASink() {
        DispenseConfig c = config();
        c.locations.put("cup", new DispenseConfig.NamedLocation(140, 50, 5));
        c.locations.put("blank", new DispenseConfig.NamedLocation(170, 120, 5.5));
        c.locations.put("park", new DispenseConfig.NamedLocation(60, 120, 31.5));
        c.surfaces.put("blank", patch(160, 110, 180, 110, 170, 130));
        c.calibrationArea.x0Mm = 20.0;
        c.calibrationArea.y0Mm = 160.0;
        c.calibrationArea.widthMm = 30.0;
        c.calibrationArea.heightMm = 20.0;
        OutputZones z = OutputZones.of(c, CAMERA);
        assertNull(z.refusal(100, 50), "the waste");
        assertNull(z.refusal(140, 50), "the cup");
        assertNull(z.refusal(85, 230), "the purge row");
        assertNull(z.refusal(30, 170), "the calibration area");
        String board = z.refusal(310, 268);
        assertNotNull(board, "a probed surface (a board) is no sink");
        assertTrue(board.contains("park first"), board);
        assertTrue(board.contains("the waste"), board);
        assertNotNull(z.refusal(170, 120), "the blank is no sink");
        assertNotNull(z.refusal(60, 120), "the park place is no sink");
        assertNotNull(z.parkRefusal("blank", 170, 120), "a park over a place that is no sink");
        assertNull(z.parkRefusal("waste", 100, 50));
        // a program still lays its dots on the board and the blank
        assertNull(z.lineRefusal("dot 'P1' at line 9", 310, 268));
        assertNull(z.lineRefusal("the valve ON at line 4", 170, 120));
    }

    /** ZFrameTest's pad R24.1 and a 0603 pad R16.1 at (x, y), planned on its probed ftp board. */
    static DispensePlan ftp(DispenseConfig c, double x, double y) {
        PadType t = DispenseDb.seedPadTypes().get(1); // chip-0603
        t.status = PadTypeStatus.PROVISIONAL;
        t.version = 1;
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(new PadGeometry("R24.1", 359.584, 131.569, 0, 0.8, 0.95, 5.5, t));
        pads.add(new PadGeometry("R16.1", x, y, 0, 0.8, 0.95, 5.5, t));
        DispensePlan p = TestTips.planner(c, new PadTypeResolver(Collections.singletonList(t), null))
                .withSurface(DispenseService.planeOf(c, c.boardSurfaceName)).plan("ftp", pads, 290, 125);
        p.boardRegistration = ZFrameTest.SEAT;
        return p;
    }

    static List<DispensePlan.PlannedDot> dotsOf(DispensePlan p, String padId) {
        List<DispensePlan.PlannedDot> l = new ArrayList<>();
        for (DispensePlan.PlannedDot d : p.dots) {
            if (d.padId.equals(padId)) {
                l.add(d);
            }
        }
        assertFalse(l.isEmpty(), "no dot on " + padId);
        return l;
    }

    /**
     * Review (BLOCKER): a probed surface allows paste wherever I4 lets a dot take its Z from it, by I4's own rule
     * (within {@code surfaceMarginMm}, 3.5 mm, of the probed points), so a board run I4 passes is never refused by I12.
     * ZFrameTest's ftp board probed at the bench spots: a pad 3.08 mm beyond the points passes both, its board program
     * too; with a 3 mm margin both refuse it; a pad beyond the margin is refused by both, by I4 first.
     */
    @Test
    public void aProbedSurfaceAllowsWhatI4Allows() throws Exception {
        DispenseConfig c = ZFrameTest.cfg();
        SurfacePlane board = DispenseService.planeOf(c, "board");
        DispensePlan near = ftp(c, 366.19, 151.3); // the points' right edge runs at x 363.1 there
        for (DispensePlan.PlannedDot d : dotsOf(near, "R16.1")) {
            double out = board.distanceOutside(d.xMm, d.yMm);
            assertTrue(out > 3.0 && out < 3.2, "R16.1's dot is " + out + " mm beyond the points");
            assertNull(OutputZones.of(c, CAMERA).lineRefusal("dot 'R16.1'", d.xMm, d.yMm),
                    "I12 allows the program's dot");
        }
        assertEquals(Collections.emptyList(), ZFrameTest.i4(ZFrameTest.check(c, near)), "I4 passes it");
        ToyMapper m = new ToyMapper(290, 125, 31.5, 0);
        GcodeProgram prog = GcodeStreamBuilder.forPlan(c, m, near, Collections.emptyList(), false, false, 0L);
        assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(prog, c, "B",
                GcodeStreamValidator.zWindow(c, m), GcodeStreamValidator.ZoneCheck.of(OutputZones.of(c, CAMERA), m)),
                "the board program passes the validator");
        // one rule, one setting: with a 3 mm margin I4 and I12 both refuse it
        c.surfaceMarginMm = 3.0;
        assertFalse(ZFrameTest.i4(ZFrameTest.check(c, near)).isEmpty());
        for (DispensePlan.PlannedDot d : dotsOf(near, "R16.1")) {
            assertNotNull(OutputZones.of(c, CAMERA).refusal(d.xMm, d.yMm));
        }
        c.surfaceMarginMm = 3.5;
        DispensePlan far = ftp(c, 367.3, 151.3);
        List<String> i4 = ZFrameTest.i4(ZFrameTest.check(c, far));
        assertTrue(i4.size() == 1 && i4.get(0).contains("outside the probed 'board' surface"), i4.toString());
        for (DispensePlan.PlannedDot d : dotsOf(far, "R16.1")) {
            assertTrue(board.distanceOutside(d.xMm, d.yMm) > 3.5);
            assertNotNull(OutputZones.of(c, CAMERA).refusal(d.xMm, d.yMm));
        }
    }

    @Test
    public void aCouponIsAllowedOverItsWholeRectangle() {
        DispenseConfig c = config();
        c.locations.put("coupon1", new DispenseConfig.NamedLocation(330, 50, 5.6)); // a 40 x 30 coupon
        OutputZones z = OutputZones.of(c, CAMERA);
        assertNull(z.refusal(345, 60), "a weigh dot near the coupon's corner");
        assertEquals("coupon coupon1", z.zoneAt(345, 60));
        assertNotNull(z.refusal(355, 70), "beyond the coupon");
    }

    @Test
    public void theBottomCameraIsForbiddenWhateverCoversIt() {
        OutputZones z = OutputZones.of(config(), CAMERA);
        String why = z.refusal(205, 32);
        assertNotNull(why);
        assertTrue(why.contains("bottom camera"), why);
        assertEquals("forbidden: bottom camera", z.zoneAt(205, 32));
        // the towel's circle reaches into the camera's: the overlap stays forbidden, its far side is allowed
        assertNotNull(z.refusal(206, 33));
        assertNull(z.refusal(214, 38));
    }

    @Test
    public void nowhereInParticularIsRefused() {
        OutputZones z = OutputZones.of(config(), CAMERA);
        String why = z.refusal(150, 150);
        assertNotNull(why);
        assertTrue(why.contains("not over a place that takes paste while the pressure is held"), why);
        assertNull(z.zoneAt(150, 150));
        assertEquals("the valve ON at line 4 puts paste at (150.0, 150.0), and no place where paste may go is set (the "
                + "waste, the towel, the cup, the purge row, the calibration area, a coupon, or the probed 'board' or "
                + "'blank' surface): set the place or probe the surface it dispenses on",
                OutputZones.of(new DispenseConfig(), null).lineRefusal("the valve ON at line 4", 150, 150));
    }

    @Test
    public void aSegmentMayNotCrossAForbiddenZone() {
        OutputZones z = OutputZones.of(config(), CAMERA);
        assertNotNull(z.crossing(150, 30, 250, 30), "straight over the camera");
        assertNull(z.crossing(150, 60, 250, 60), "30 mm beside it");
    }

    @Test
    public void configuredForbiddenCirclesAndRadii() {
        DispenseConfig c = config();
        c.outputZones.forbidRadiusMm = 25;
        DispenseConfig.ForbidZone f = new DispenseConfig.ForbidZone();
        f.name = "feeder";
        f.x = 100;
        f.y = 52;
        f.radiusMm = 1;
        c.outputZones.forbid.add(f);
        OutputZones z = OutputZones.of(c, CAMERA);
        assertNotNull(z.refusal(214, 38), "inside the wider camera circle now");
        assertTrue(z.refusal(100, 52).contains("feeder"), z.refusal(100, 52));
        assertNull(OutputZones.of(c, null).refusal(205, 32), "no bottom camera: the towel there is allowed");
    }
}
