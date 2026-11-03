package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The pad calibration settings (profile calibration design §5.4, decisions 1, 9 and 12): tip,
 * paste, calibration area and limits; defaults, ranges, old files, the default area off the purge
 * row, and the floors that only the simulator may go below.
 */
public class PadCalibrationConfigTest {
    static void refused(String key, java.util.function.Consumer<DispenseConfig> change) {
        AutoregConfigTest.refused(key, change);
    }

    static void accepted(java.util.function.Consumer<DispenseConfig> change) {
        AutoregConfigTest.accepted(change);
    }

    @Test
    public void defaults() {
        DispenseConfig c = new DispenseConfig();
        assertEquals(new ArrayList<>(), c.validate());
        assertEquals("ad22tta-b", c.tip.key);
        assertEquals("22G bent taper", c.tip.name);
        assertEquals(0.41, c.tip.idMm, 0);
        assertEquals(0.72, c.tip.odMm, 0, "decision 12: the assumed OD lives in TipSpec");
        assertEquals(400, c.tip.minDotSteps);
        assertEquals("gc50-t5", c.paste.key);
        assertEquals("Loctite GC50 SAC305 T5", c.paste.name);
        assertNull(c.paste.lot);
        assertNull(c.paste.loadedAtMs);
        assertEquals("blank", c.calibrationArea.surfaceName);
        assertFalse(c.calibrationArea.isDefined());
        assertEquals(0, c.calibrationArea.usedMm, 0);
        assertNull(c.calibrationArea.resetAtMs);
        assertEquals(20, c.calibrationArea.leftMm(), 0);
        PadCalibrationSettings s = c.padCalibration;
        assertEquals(0.13, s.maxResidCv, 0);
        assertEquals(0.13, s.maxHalfSplit, 0);
        assertEquals(0.20, s.maxQuarterAmplitude, 0);
        assertEquals(0.25, s.massTolLow, 0);
        assertEquals(0.10, s.massTolHigh, 0);
        assertEquals(0.05, s.confirmTolerance, 0);
        assertEquals(0.15, s.controlTolerance, 0);
        assertEquals(70, s.weighTargetMg, 0);
        assertEquals(8, s.doseRecordMaxAgeH, 0, "decision 9");
        assertEquals(15, s.readingConfirmAfterMin, 0);
        assertEquals(Arrays.asList("coupon1", "coupon2"), s.couponPlaces);
        assertArrayEquals(new double[] {40, 30}, s.couponSizeMm, 0);
        assertEquals(3, s.couponMarginMm, 0);
        assertEquals(0.3, s.couponWindowMm, 0);
        assertEquals(4, s.flushDots);
        assertEquals(12, s.ladderDotsPerPhase);
        assertEquals(28, s.minLadderDots);
        assertEquals(60, s.minHops);
        assertEquals(0.03, s.threadRateOk, 0);
        assertEquals(0.005, s.missRateOk, 0);
        assertTrue(s.realMachineProblems().isEmpty());
    }

    @Test
    public void ranges() {
        refused("tip.key", c -> c.tip.key = "22G Tip");
        refused("tip.key", c -> c.tip.key = null);
        refused("tip.name", c -> c.tip.name = " ");
        refused("tip.idMm", c -> c.tip.odMm = 0.40);
        refused("tip.idMm", c -> c.tip.idMm = 0);
        refused("tip.idMm", c -> c.tip.odMm = 3.5);
        refused("tip.minDotSteps", c -> c.tip.minDotSteps = 200);
        refused("tip.minDotSteps", c -> c.tip.minDotSteps = 533);
        accepted(c -> c.tip.minDotSteps = 800);
        accepted(c -> {
            c.tip.key = "ad25g-straight";
            c.tip.name = "25G tip";
            c.tip.idMm = 0.25;
            c.tip.odMm = 0.51;
        });
        refused("paste.key", c -> c.paste.key = "");
        refused("paste.name", c -> c.paste.name = null);
        accepted(c -> c.paste.lot = "L2026-10");
        refused("padCalibration.maxResidCv", c -> c.padCalibration.maxResidCv = 0);
        refused("padCalibration.maxHalfSplit", c -> c.padCalibration.maxHalfSplit = 1);
        refused("padCalibration.massTolLow", c -> c.padCalibration.massTolLow = -0.1);
        refused("padCalibration.threadRateOk", c -> c.padCalibration.threadRateOk = 0);
        refused("padCalibration.confirmTolerance", c -> c.padCalibration.confirmTolerance = 0.6);
        refused("padCalibration.weighTargetMg", c -> c.padCalibration.weighTargetMg = 0);
        refused("padCalibration.doseRecordMaxAgeH", c -> c.padCalibration.doseRecordMaxAgeH = 0);
        refused("padCalibration.doseRecordMaxAgeH", c -> c.padCalibration.doseRecordMaxAgeH = 200);
        accepted(c -> c.padCalibration.doseRecordMaxAgeH = 24);
        refused("padCalibration.readingConfirmAfterMin", c -> c.padCalibration.readingConfirmAfterMin = -1);
        refused("padCalibration.couponPlaces", c -> c.padCalibration.couponPlaces = new ArrayList<>());
        refused("padCalibration.couponPlaces", c -> c.padCalibration.couponPlaces = Arrays.asList("a", "b", "c"));
        refused("padCalibration.couponPlaces", c -> c.padCalibration.couponPlaces = Arrays.asList("a", "a"));
        refused("padCalibration.couponPlaces", c -> c.padCalibration.couponPlaces = Arrays.asList("a", " "));
        // decision 1: one coupon place is enough
        accepted(c -> c.padCalibration.couponPlaces = Collections.singletonList("coupon1"));
        refused("padCalibration.couponSizeMm", c -> c.padCalibration.couponSizeMm = new double[] {11, 30});
        refused("padCalibration.couponSizeMm", c -> c.padCalibration.couponSizeMm = new double[] {40});
        refused("padCalibration.couponMarginMm", c -> c.padCalibration.couponMarginMm = -1);
        refused("padCalibration.couponWindowMm", c -> c.padCalibration.couponWindowMm = 0);
        refused("padCalibration.flushDots", c -> c.padCalibration.flushDots = 0);
        refused("padCalibration.ladderDotsPerPhase", c -> c.padCalibration.ladderDotsPerPhase = 0);
        refused("padCalibration.minLadderDots", c -> c.padCalibration.minLadderDots = 0);
        refused("padCalibration.minHops", c -> c.padCalibration.minHops = 0);
        // the shortened statistics of a SIM test are valid settings; only the real machine refuses them
        accepted(c -> {
            c.padCalibration.ladderDotsPerPhase = 3;
            c.padCalibration.minLadderDots = 8;
            c.padCalibration.minHops = 10;
            c.padCalibration.weighTargetMg = 20;
        });
        refused("tip, paste, calibrationArea and padCalibration must be objects", c -> c.tip = null);
        refused("tip, paste, calibrationArea and padCalibration must be objects", c -> c.padCalibration = null);
        refused("calibrationArea: set x0Mm", c -> c.calibrationArea.x0Mm = 300.0);
        refused("calibrationArea: x0Mm and y0Mm", c -> area(c, 300, 250, 2, 20));
        refused("calibrationArea.surfaceName", c -> c.calibrationArea.surfaceName = null);
        refused("calibrationArea.usedMm", c -> c.calibrationArea.usedMm = -1);
        accepted(c -> area(c, 300, 250, 40, 20));
    }

    /** Design §12.1: one text per floor, exact. */
    @Test
    public void realMachineFloors() {
        PadCalibrationSettings s = new PadCalibrationSettings();
        s.minHops = 20;
        assertEquals(Arrays.asList("padCalibration.minHops below 60 is allowed in the simulator only"),
                s.realMachineProblems());
        s.ladderDotsPerPhase = 11;
        s.minLadderDots = 27;
        s.weighTargetMg = 49.9;
        assertEquals(Arrays.asList("padCalibration.ladderDotsPerPhase below 12 is allowed in the simulator only",
                "padCalibration.minLadderDots below 28 is allowed in the simulator only",
                "padCalibration.minHops below 60 is allowed in the simulator only",
                "padCalibration.weighTargetMg below 50 is allowed in the simulator only"), s.realMachineProblems());
        s.ladderDotsPerPhase = 12;
        s.minLadderDots = 28;
        s.minHops = 60;
        s.weighTargetMg = 50;
        assertTrue(s.realMachineProblems().isEmpty());
    }

    @Test
    public void oldFilesAndRoundTrips() {
        DispenseConfig old = DispenseConfig.fromJson("{\"schemaVersion\":3,\"hopHeightMm\":1.5}");
        assertEquals(new DispenseConfig().toJson(), withHop(old, 1.0).toJson(), "every new object at its defaults");
        assertEquals(new ArrayList<>(), old.validate());
        DispenseConfig nulls = DispenseConfig.fromJson("{\"tip\":null,\"paste\":null,\"calibrationArea\":null,"
                + "\"padCalibration\":{\"couponPlaces\":null,\"couponSizeMm\":null,\"minHops\":90}}");
        assertEquals("ad22tta-b", nulls.tip.key);
        assertEquals("gc50-t5", nulls.paste.key);
        assertNotNull(nulls.calibrationArea);
        assertEquals(Arrays.asList("coupon1", "coupon2"), nulls.padCalibration.couponPlaces);
        assertArrayEquals(new double[] {40, 30}, nulls.padCalibration.couponSizeMm, 0);
        assertEquals(90, nulls.padCalibration.minHops);
        assertEquals(new ArrayList<>(), nulls.validate());

        DispenseConfig c = new DispenseConfig();
        c.tip.odMm = 0.75;
        c.paste.loadedAtMs = 123L;
        c.paste.lot = "L1";
        area(c, 300, 250, 40, 20);
        c.calibrationArea.usedMm = 11;
        c.calibrationArea.resetAtMs = 99L;
        c.padCalibration.couponPlaces = new ArrayList<>(Collections.singletonList("coupon1"));
        c.padCalibration.doseRecordMaxAgeH = 6;
        DispenseConfig back = DispenseConfig.fromJson(c.toJson());
        assertEquals(c.toJson(), back.toJson());
        assertEquals(0.75, back.tip.odMm, 0);
        assertEquals(123L, (long) back.paste.loadedAtMs);
        assertEquals(9, back.calibrationArea.leftMm(), 0);
        assertEquals(Arrays.asList("coupon1"), back.padCalibration.couponPlaces);
        assertEquals(6, back.copy().padCalibration.doseRecordMaxAgeH, 0);
    }

    static DispenseConfig withHop(DispenseConfig c, double hop) {
        c.hopHeightMm = hop;
        return c;
    }

    static void area(DispenseConfig c, double x0, double y0, double w, double h) {
        c.calibrationArea.x0Mm = x0;
        c.calibrationArea.y0Mm = y0;
        c.calibrationArea.widthMm = w;
        c.calibrationArea.heightMm = h;
    }

    /** The bench layout of ProbeTargetsTest: purge row 300 + i x 2.5 at Y 240, blank place (322, 236). */
    static DispenseConfig bench() {
        DispenseConfig c = new DispenseConfig();
        c.purgeRow.x0Mm = 300.0;
        c.purgeRow.y0Mm = 240.0;
        c.purgeRow.pitchXMm = 2.5;
        c.purgeRow.spots = 20;
        c.locations.put("blank", new DispenseConfig.NamedLocation(322.0, 236.0, 5.62));
        return c;
    }

    @Test
    public void defaultAreaIsAtTheBlankPlaceOffThePurgeRow() {
        DispenseConfig c = new DispenseConfig();
        assertNull(c.calibrationArea.effective(c), "no blank place, no default");
        c.locations.put("blank", new DispenseConfig.NamedLocation(322.0, 236.0, 5.62));
        CalibrationArea a = c.calibrationArea.effective(c);
        assertEquals(322, a.x0Mm, 0);
        assertEquals(236, a.y0Mm, 0);
        assertEquals(40, a.widthMm, 0);
        assertEquals(20, a.heightMm, 0);
        assertFalse(c.calibrationArea.isDefined(), "effective() is a copy");

        // the row crosses it at Y 240: moves up 5.5 (down would be 17.5, right 27, left 63.5)
        DispenseConfig b = bench();
        a = b.calibrationArea.effective(b);
        assertEquals(322, a.x0Mm, 1e-9);
        assertEquals(241.5, a.y0Mm, 1e-9);
        // a row near the top edge: down is the shortest move
        b.purgeRow.y0Mm = 255.0;
        a = b.calibrationArea.effective(b);
        assertEquals(255 - 1.5 - 20, a.y0Mm, 1e-9);
        assertEquals(322, a.x0Mm, 1e-9);
        // a short vertical row at the area's right end: left is the shortest move
        b.purgeRow.x0Mm = 360.0;
        b.purgeRow.y0Mm = 230.0;
        b.purgeRow.pitchXMm = 0;
        b.purgeRow.pitchYMm = 2.5;
        b.purgeRow.spots = 14;
        a = b.calibrationArea.effective(b);
        assertEquals(360 - 1.5 - 40, a.x0Mm, 1e-9);
        assertEquals(236, a.y0Mm, 1e-9);
        // a spot 1.5 mm away is clear: no move
        b.purgeRow.x0Mm = 322.0 + 40 + 1.5;
        a = b.calibrationArea.effective(b);
        assertEquals(322, a.x0Mm, 1e-9);
        // every default validates once it is set
        for (DispenseConfig d : Arrays.asList(bench(), b)) {
            CalibrationArea e = d.calibrationArea.effective(d);
            area(d, e.x0Mm, e.y0Mm, e.widthMm, e.heightMm);
            assertEquals(new ArrayList<>(), d.validate());
        }
        // a defined area is returned as set
        DispenseConfig set = bench();
        area(set, 280, 200, 30, 15);
        a = set.calibrationArea.effective(set);
        assertEquals(280, a.x0Mm, 0);
        assertEquals(15, a.heightMm, 0);
        assertEquals(15, set.calibrationArea.leftMm(), 0);
    }

    @Test
    public void definedAreaStaysOffThePurgeRowAndTheCoupons() {
        DispenseConfig c = bench();
        area(c, 322, 236, 40, 20);
        List<String> p = c.validate();
        assertEquals(1, p.size(), p.toString());
        assertTrue(p.get(0).startsWith("calibrationArea must stay 1.5 mm from every purge spot: the spot at X 322.5, "
                + "Y 240.0 is 0.00 mm from it"), p.get(0));
        area(c, 322, 241.5, 40, 20);
        assertEquals(new ArrayList<>(), c.validate(), "1.5 mm exactly is enough");
        area(c, 322, 241.4, 40, 20);
        assertFalse(c.validate().isEmpty());
        // a corner spot counts by its true distance: (347.5, 240) is 1.58 mm from (346.4, 241.14)
        area(c, 348.6, 241.14, 40, 20);
        assertEquals(new ArrayList<>(), c.validate());

        // the coupon is 40 x 30 mm centred on its place: here X 330-370, Y 262-292, 0.5 mm above the area
        c = bench();
        area(c, 322, 241.5, 40, 20);
        c.locations.put("coupon1", new DispenseConfig.NamedLocation(350, 277, 5.8));
        p = c.validate();
        assertEquals(Arrays.asList("calibrationArea must stay 3.0 mm from the coupon place 'coupon1' (40 x 30 mm "
                + "around it): it is 0.50 mm from it"), p);
        c.locations.put("coupon1", new DispenseConfig.NamedLocation(350, 279.5, 5.8));
        assertEquals(new ArrayList<>(), c.validate(), "3 mm exactly is enough");
        // only the configured coupon places count
        c.locations.put("coupon9", new DispenseConfig.NamedLocation(350, 250, 5.8));
        assertEquals(new ArrayList<>(), c.validate());
    }

    @Test
    public void reserveUsesTheAreaInBands() {
        CalibrationArea a = new CalibrationArea();
        assertEquals(20, a.leftMm(), 0);
        assertEquals(0, a.reserve(11), 0);
        assertEquals(11, a.usedMm, 0);
        assertEquals(9, a.leftMm(), 0);
        assertEquals(11, a.reserve(9), 0);
        assertEquals(0, a.leftMm(), 0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> a.reserve(0.1));
        assertEquals("the calibration area has 0.0 mm left; 0.1 mm asked", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new CalibrationArea().reserve(0));
        a.usedMm = 25;
        assertEquals(0, a.leftMm(), 0, "never negative");
    }

    /** Decision 1: a job may start with one coupon place; the second confirms the mass. */
    @Test
    public void couponPlacesAndTheirRectangles() {
        DispenseConfig c = new DispenseConfig();
        PadCalibrationSettings s = c.padCalibration;
        assertTrue(s.definedCouponPlaces(c).isEmpty());
        assertNull(s.couponRect(c, "coupon1"));
        c.locations.put("coupon2", new DispenseConfig.NamedLocation(350, 90, 5.8));
        assertEquals(Arrays.asList("coupon2"), s.definedCouponPlaces(c));
        c.locations.put("coupon1", new DispenseConfig.NamedLocation(350, 55, 5.8));
        assertEquals(Arrays.asList("coupon1", "coupon2"), s.definedCouponPlaces(c));
        assertArrayEquals(new double[] {330, 40, 370, 70}, s.couponRect(c, "coupon1"), 1e-12);
        s.couponSizeMm = new double[] {50, 40};
        assertArrayEquals(new double[] {325, 35, 375, 75}, s.couponRect(c, "coupon1"), 1e-12);
    }

    /** Decision 5: a pad type made with another tip is told by its key. */
    @Test
    public void otherTip() {
        TipSpec t = new TipSpec();
        assertFalse(t.isOther(null), "a type not tied to a tip");
        assertFalse(t.isOther("ad22tta-b"));
        assertTrue(t.isOther("ad25g"));
    }
}
