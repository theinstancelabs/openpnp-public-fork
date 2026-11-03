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
 * BR-03: where the valve and the screw may switch on. Allowed: the places (waste, towel, park, cup, coupons, ...), the
 * purge row, the calibration area and every probed surface (engine-probed patches, the blank, the boards of plans).
 * Forbidden, whatever else covers it: the bottom camera (a 15 mm circle) and any configured circle.
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
        DispenseConfig.SurfaceMap patch = new DispenseConfig.SurfaceMap();
        patch.points.add(new DispenseConfig.SurfacePoint(300, 260, 5.6));
        patch.points.add(new DispenseConfig.SurfacePoint(320, 260, 5.6));
        patch.points.add(new DispenseConfig.SurfacePoint(310, 275, 5.6));
        c.surfaces.put("patch1", patch);
        return c;
    }

    @Test
    public void placesRowsAndSurfacesAreAllowed() {
        OutputZones z = OutputZones.of(config(), CAMERA);
        assertNull(z.refusal(100, 50), "the waste");
        assertNull(z.refusal(103, 52), "near the waste, inside its radius");
        assertNull(z.refusal(80 + 19 * 2.5, 230), "the last purge spot");
        assertNull(z.lineRefusal("dot 'P1' at line 9", 310, 268), "a program's dot inside the probed patch");
        assertEquals("waste", z.zoneAt(100, 50));
        assertEquals("purge row", z.zoneAt(85, 230));
        assertEquals("surface patch1", z.zoneAt(310, 268));
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
        assertNotNull(z.refusal(170, 120), "the blank's place is no sink");
        assertNotNull(z.refusal(60, 120), "the park place is no sink");
        assertNotNull(z.parkRefusal("blank", 170, 120), "a park over a place that is no sink");
        assertNull(z.parkRefusal("waste", 100, 50));
        // a program still lays its dots on the surfaces and the places
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
            assertNull(OutputZones.of(c, CAMERA).refusal(d.xMm, d.yMm), "I12 allows it");
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
        assertTrue(why.contains("not over a place where paste may go"), why);
        assertNull(z.zoneAt(150, 150));
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
