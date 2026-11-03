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
import org.openpnp.instancelabs.vision.SpotDot;

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
        assertEquals(1.6, req.pitchMm, 1e-12, "F14: the default pitch");
        assertEquals(10 + 5 * 1.6, l.get(5).x, 1e-12);
    }

    /**
     * F14: the touch-off's pitch leaves room between the dots (the expected dot diameter + 0.4 mm): half-turn dots
     * (~1.07 mm on the real machine, 1.16 mm in the dose model) merged at 1.0 mm and stood apart at 1.6 mm.
     */
    @Test
    public void touchOffPitchLeavesRoomBetweenTheDots() {
        DispenseConfig c = new DispenseConfig();
        NeedleTouchOff.Request req = new NeedleTouchOff.Request();
        assertEquals(800, c.profile.netStepsPerDot, "the profile's half-turn dot");
        assertEquals(1.16, NeedleTouchOff.expectedDotDiameterMm(req, c), 1e-9);
        assertEquals(1.56, NeedleTouchOff.minPitchMm(req, c), 1e-9);
        assertNull(NeedleTouchOff.pitchProblem(req, c), "1.6 mm");
        assertEquals(1.6, NeedleTouchOff.defaultPitchMm(req, c), 1e-9);
        req.pitchMm = 1.0;
        assertEquals("the touch-off pitch 1.00 mm is too small for these dots: a half-turn dot is about 1.16 mm across, "
                + "so dots 1.00 mm apart touch or string together and cannot be measured; use a pitch of at least "
                + "1.56 mm (the dot + 0.40 mm)", NeedleTouchOff.pitchProblem(req, c));
        req.pitchMm = 1.56;
        assertNull(NeedleTouchOff.pitchProblem(req, c), "the bound itself");
        // a one-turn dot (360 deg of B) is 1.345 mm: at least 1.745 mm, and the default grows to 1.75 mm
        req.rotationDeg = 1600 / c.bStepsPerDeg;
        req.pitchMm = 1.6;
        assertTrue(NeedleTouchOff.pitchProblem(req, c).contains("use a pitch of at least 1.75 mm"),
                NeedleTouchOff.pitchProblem(req, c));
        assertEquals(1.75, NeedleTouchOff.defaultPitchMm(req, c), 1e-9);
        // the disc that cuts the strings: 0.45 x the expected dot, 0.52 mm for a half turn (real strings 0.28-0.36 mm)
        assertEquals(0.522, NeedleTouchOff.cutMm(1.16), 1e-9);
    }

    /**
     * F9: paste at a dot's spot that could not be measured (joined to a neighbour, strings) is not "no dot": before
     * the contact it refuses the conclusion; after it, the contact stands.
     */
    @Test
    public void touchOffRefusesOnPasteItCannotMeasure() {
        double[] g = {0.125, 0.100, 0.075, 0.050, 0.025, 0.000};
        Double[] d = {0.62, 0.63, null, null, 1.05, null};
        String[] u = {null, null, "joined to the dot at gap 0.050 mm", "joined to the dot at gap 0.075 mm", null,
            null};
        NeedleTouchOff.Analysis a = NeedleTouchOff.analyze(g, d, u, 0.25);
        assertFalse(a.found());
        assertEquals("dots touch or are strung together: the paste at gap 0.075 mm is joined to the dot at gap 0.050 mm; "
                + "use a larger pitch", a.reason);
        assertTrue(a.reason.startsWith(NeedleTouchOff.STRUNG));
        assertEquals(Arrays.asList("dot", "dot", "unmeasured", "unmeasured", "dot", "none"), Arrays.asList(a.dotStates));
        assertEquals(0.625, a.referenceDiameterMm, 1e-12);
        // the same paste read as "no dot", as before F9: contact between 0.100 and 0.075
        assertEquals(0.0875, NeedleTouchOff.analyze(g, d, 0.25).contactGapMm, 1e-12);
        // in the reference, too
        String[] first = {"no dot, only strings or a smear (0.10 mm²)", null, null, null, null, null};
        NeedleTouchOff.Analysis top = NeedleTouchOff.analyze(g, new Double[] {null, 0.6, 0.6, 0.6, 0.6, 0.6}, first,
                0.25);
        assertEquals("dots touch or are strung together: the paste at gap 0.125 mm is no dot, only strings or a smear "
                + "(0.10 mm²); use a larger pitch", top.reason);
        // after the contact (squashed at 0.050) unmeasured paste does not matter: squashed dots touch more easily
        Double[] squash = {0.62, 0.63, 0.61, 0.95, null, null};
        String[] after = {null, null, null, null, "joined to the dot at gap 0.000 mm", "joined to the dot at gap "
                + "0.025 mm"};
        NeedleTouchOff.Analysis c = NeedleTouchOff.analyze(g, squash, after, 0.25);
        assertTrue(c.found(), c.reason);
        assertEquals(0.0625, c.contactGapMm, 1e-12);
    }

    /**
     * The first two dots are the reference only when both formed and they agree (the real ladder's first dot was a
     * 0.37 mm droplet: dots laid too high miss, and the next one carries their paste).
     */
    @Test
    public void touchOffReferenceDotsMustFormAndAgree() {
        double[] g = {0.45, 0.40, 0.35, 0.30, 0.25};
        NeedleTouchOff.Analysis a = NeedleTouchOff.analyze(g, new Double[] {0.373, 1.00, 1.02, 0.98, 1.01}, 0.25);
        assertFalse(a.found(), "not contact between 0.45 and 0.40: " + a.reason);
        assertEquals("the first two dots differ too much to be the reference (0.373 mm at gap 0.450, 1.000 mm at gap "
                + "0.400: more than 25 %): the dots far from the surface did not form evenly, or the needle reached "
                + "the surface at once; run the ladder again from another start gap", a.reason);
        // a droplet, then nothing, then the paste both left on the needle: no contact at 0.425 either
        NeedleTouchOff.Analysis b = NeedleTouchOff.analyze(g, new Double[] {0.373, null, 1.5, 1.0, 1.0}, 0.25);
        assertFalse(b.found(), b.reason);
        assertEquals("only the first dot formed before a missing one (0.373 mm at gap 0.450, none at gap 0.400): a "
                + "contact needs two steady dots before it (the first may be a droplet from too high); run the ladder "
                + "again from another start gap", b.reason);
        // two steady dots, then none: the contact
        assertEquals(0.375, NeedleTouchOff.analyze(g, new Double[] {1.0, 1.02, null, null, null}, 0.25).contactGapMm,
                1e-12);
    }

    /**
     * F9 review: two droplets of the same size first (a start gap too high), then steady dots. Read against the
     * droplets, the first steady dot is "squashed" and the contact would be applied 0.4 mm above the surface (the F9
     * error again, one step higher); against the dose model's steady dot (60 % of 1.16 mm for a half turn) the
     * droplets are no reference.
     */
    @Test
    public void touchOffReferenceDotsMustBeSteadyDotsNotDroplets() {
        DispenseConfig c = new DispenseConfig();
        NeedleTouchOff.Request req = new NeedleTouchOff.Request();
        double steady = NeedleTouchOff.steadyDotMm(req, c);
        assertEquals(1.16, steady, 1e-9, "a half-turn dot on the blank");
        double[] g = {0.50, 0.45, 0.40, 0.35, 0.30, 0.25};
        Double[] d = {0.40, 0.42, 1.00, 1.02, 1.05, 1.00};
        assertEquals(0.425, NeedleTouchOff.analyze(g, d, 0.25).contactGapMm, 1e-12, "the droplets as the reference");
        NeedleTouchOff.Analysis a = NeedleTouchOff.analyze(g, d, new String[g.length], 0.25, steady);
        assertFalse(a.found(), a.reason);
        assertNull(a.referenceDiameterMm);
        assertEquals("the first two dots (0.400 mm at gap 0.500, 0.420 mm at gap 0.450) are under 60 % of the 1.16 mm "
                + "a steady dot of this dose makes on the blank: droplets from a needle too high, so a larger dot "
                + "after them is no contact; run the ladder again from a lower start gap", a.reason);
        // the spots' path checks it too
        List<SpotDot> seen = new ArrayList<>();
        for (Double x : d) {
            seen.add(SpotDot.dot(new Dot(0, 0, x, Math.PI / 4 * x * x, 0.9)));
        }
        assertEquals(a.reason, NeedleTouchOff.analyze(g, seen, 0.25, steady).reason);
        // steady dots like the real ladder's (0.91-1.20 mm), then a squash: the contact
        NeedleTouchOff.Analysis ok = NeedleTouchOff.analyze(g, new Double[] {1.00, 0.97, 1.02, 1.40, 1.6, 1.7},
                new String[g.length], 0.25, steady);
        assertEquals(0.375, ok.contactGapMm, 1e-12, ok.reason);
        // the bound: 60 % of the steady dot
        assertTrue(NeedleTouchOff.analyze(g, new Double[] {0.70, 0.70, 0.70, 0.70, 0.70, 1.0}, new String[g.length],
                0.25, steady).found());
        assertFalse(NeedleTouchOff.analyze(g, new Double[] {0.69, 0.69, 0.69, 0.69, 0.69, 1.0}, new String[g.length],
                0.25, steady).found());
        // a quarter turn's steady dot is the dose model's lower edge for it
        req.rotationDeg = 400 / c.bStepsPerDeg;
        assertEquals(org.openpnp.instancelabs.padprofile.DoseModel.dLoMm(400), NeedleTouchOff.steadyDotMm(req, c),
                1e-9);
    }

    /** What the camera saw at each spot, as the analysis reads it: the joined dots named by their gaps. */
    @Test
    public void touchOffAnalysisOfTheSpots() {
        double[] g = {0.10, 0.075, 0.05, 0.025};
        List<SpotDot> seen = Arrays.asList(SpotDot.dot(new Dot(0, 0, 1.02, 0.82, 0.9)), SpotDot.dot(new Dot(0, 0, 1.04,
                0.85, 0.9)), SpotDot.unmeasured("joined to the paste at another spot", Arrays.asList(3)),
                SpotDot.none());
        NeedleTouchOff.Analysis a = NeedleTouchOff.analyze(g, seen, 0.25, 1.16);
        assertEquals(Arrays.asList(1.02, 1.04, null, null), Arrays.asList(a.diametersMm));
        assertEquals("joined to the dot at gap 0.025 mm", a.dotNotes[2]);
        assertEquals(Arrays.asList("dot", "dot", "unmeasured", "none"), Arrays.asList(a.dotStates));
        assertTrue(a.reason.startsWith("dots touch or are strung together: the paste at gap 0.050 mm is joined"),
                a.reason);
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
        // flat layout for comparison: the real gap step is 0.025 - 1.6 * 0.0092 = 0.0103 mm
        List<GcodeStreamBuilder.DotSpec> flat = NeedleTouchOff.layout(req, new DispenseConfig.NamedLocation(350, 233.5,
                5.86), c, null);
        double realStep = (flat.get(0).dispenseZ - (5.95 + slope * (flat.get(0).x - 340)))
                - (flat.get(1).dispenseZ - (5.95 + slope * (flat.get(1).x - 340)));
        assertEquals(0.025 + req.pitchMm * slope, realStep, 1e-9);
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
