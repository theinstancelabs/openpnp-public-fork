package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.vision.Dot;

/** Pure parts of the dot-ladder and needle touch-off calibrations, and the I9 test-dot check. */
public class CalibrationAnalysisTest {
    @Test
    public void touchOffFindsSquashJump() {
        double[] g = {0.125, 0.100, 0.075, 0.050, 0.025, 0.000};
        Double[] d = {0.62, 0.63, 0.61, 0.95, 1.05, null};
        NeedleTouchOff.Analysis a = NeedleTouchOff.analyze(g, d, 0.25);
        assertTrue(a.found(), a.reason);
        assertEquals(0.0625, a.contactGapMm, 1e-12);
        assertEquals(0.075, a.bracketHighMm, 1e-12);
        assertEquals(0.050, a.bracketLowMm, 1e-12);
    }

    @Test
    public void touchOffFindsMissingDotAndOrderIndependent() {
        double[] g = {0.000, 0.050, 0.100, 0.025, 0.075};
        Double[] d = {null, 0.60, 0.61, null, 0.62};
        NeedleTouchOff.Analysis a = NeedleTouchOff.analyze(g, d, 0.25);
        assertEquals(0.0375, a.contactGapMm, 1e-12);
    }

    @Test
    public void touchOffNotFound() {
        double[] g = {0.1, 0.075, 0.05};
        assertFalse(NeedleTouchOff.analyze(g, new Double[] {0.6, 0.61, 0.6}, 0.25).found());
        assertFalse(NeedleTouchOff.analyze(g, new Double[] {null, null, null}, 0.25).found());
    }

    @Test
    public void touchOffLayoutUsesCurrentOffset() {
        DispenseConfig c = new DispenseConfig();
        c.needleZOffsetMm = -0.2;
        NeedleTouchOff.Request req = new NeedleTouchOff.Request();
        List<GcodeStreamBuilder.DotSpec> l = NeedleTouchOff.layout(req, new DispenseConfig.NamedLocation(10, 20, 1.5), c,
                null);
        assertEquals(6, l.size());
        assertEquals(1.5 - 0.2 + 0.125, l.get(0).dispenseZ, 1e-12);
        assertEquals(1.5 - 0.2 + 0.0, l.get(5).dispenseZ, 1e-12);
        assertEquals(10 + 5 * 1.5, l.get(5).x, 1e-12);
    }

    /**
     * Review finding: the touch-off ladder used one flat Z, so a slope of the blank along the ladder
     * changed the real gap steps (purge_spots.json: 0.0092 mm/mm down in +X) and biased the offset.
     * With the probed blank plane every dot keeps exactly its commanded gap above the surface.
     */
    @Test
    public void touchOffLayoutFollowsTheBlankPlane() {
        DispenseConfig c = new DispenseConfig();
        c.needleZOffsetMm = -2.59;
        double slope = -0.0092;
        List<DispenseConfig.SurfacePoint> pts = new ArrayList<>();
        for (double[] p : new double[][] {{340, 230}, {390, 230}, {340, 240}, {390, 240}}) {
            pts.add(new DispenseConfig.SurfacePoint(p[0], p[1], 5.95 + slope * (p[0] - 340)));
        }
        SurfacePlane plane = SurfacePlane.fit(pts);
        NeedleTouchOff.Request req = new NeedleTouchOff.Request();
        double[] g = NeedleTouchOff.gaps(req);
        List<GcodeStreamBuilder.DotSpec> l = NeedleTouchOff.layout(req, new DispenseConfig.NamedLocation(350, 233.5,
                5.86), c, plane);
        for (int i = 0; i < l.size(); i++) {
            double surface = 5.95 + slope * (l.get(i).x - 340);
            assertEquals(g[i], l.get(i).dispenseZ - c.needleZOffsetMm - surface, 1e-9, "gap of dot " + i);
        }
        // flat layout for comparison: the real gap step is 0.025 - 1.5 * 0.0092 = 0.0112 mm
        List<GcodeStreamBuilder.DotSpec> flat = NeedleTouchOff.layout(req, new DispenseConfig.NamedLocation(350, 233.5,
                5.86), c, null);
        double realStep = (flat.get(0).dispenseZ - (5.95 + slope * (flat.get(0).x - 340)))
                - (flat.get(1).dispenseZ - (5.95 + slope * (flat.get(1).x - 340)));
        assertEquals(0.025 + 1.5 * slope, realStep, 1e-9);
        // the dot ladder follows the plane too
        DotLadderCalibration.Request dl = new DotLadderCalibration.Request();
        for (GcodeStreamBuilder.DotSpec d : DotLadderCalibration.layout(dl, new DispenseConfig.NamedLocation(350, 233.5,
                5.86), c, plane)) {
            assertEquals(c.gapMm(), d.dispenseZ - c.needleZOffsetMm - plane.z(d.x, d.y), 1e-9);
        }
    }

    @Test
    public void ladderLayoutAndStatistics() {
        DispenseConfig c = new DispenseConfig();
        DotLadderCalibration.Request req = new DotLadderCalibration.Request();
        req.rotationsDeg = new double[] {30, 60};
        req.repeats = 3;
        List<GcodeStreamBuilder.DotSpec> specs = DotLadderCalibration.layout(req,
                new DispenseConfig.NamedLocation(0, 0, 1), c, null);
        assertEquals(6, specs.size());
        assertEquals(1 + c.needleZOffsetMm + c.gapMm(), specs.get(0).dispenseZ, 1e-12);
        assertEquals(1 + 0.10, specs.get(0).dispenseZ, 1e-12, "default gap 0.10 (profile)");
        List<Dot> found = new ArrayList<>(Arrays.asList(new Dot(0, 0, 0.50, 0.196, 0.9), new Dot(0, 0, 0.52, 0.212, 0.9),
                null, new Dot(0, 0, 0.70, 0.385, 0.9), new Dot(0, 0, 0.72, 0.407, 0.9), new Dot(0, 0, 0.71, 0.396, 0.9)));
        List<String> notes = new ArrayList<>();
        List<DotLadderRow> rows = DotLadderCalibration.analyze(5, req, specs, found, c.gapMm(), notes);
        assertEquals(2, rows.size());
        assertEquals(30, rows.get(0).rotationDeg, 0);
        assertEquals(2, rows.get(0).n);
        assertEquals(0.51, rows.get(0).diameterMeanMm, 1e-12);
        assertEquals(0.71, rows.get(1).diameterMeanMm, 1e-12);
        assertEquals(1, notes.size(), notes.toString());
        assertTrue(DotLadder.of(rows).isMonotonic());
    }

    @Test
    public void testDotCheck() {
        List<Dot> dots = Arrays.asList(new Dot(0.05, -0.02, 0.66, 0.34, 0.9), new Dot(3, 3, 1.0, 0.8, 0.9));
        assertNull(TestDotCheck.evaluate(dots, 0.7, 0.6, 0.15, 1.0));
        assertTrue(TestDotCheck.evaluate(dots, 1.5, 0.6, 0.15, 1.0).startsWith("I9"));
        assertTrue(TestDotCheck.evaluate(Arrays.asList(new Dot(3, 3, 1.0, 0.8, 0.9)), 0.7, 0.6, 0.15, 1.0)
                .startsWith("I9: no test dot"));
        assertNull(TestDotCheck.evaluate(dots, null, 0.6, 0.15, 1.0));
    }
}
