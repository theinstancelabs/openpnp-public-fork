package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * BR-77: the start-angle measure's fits (plan §4). Halves: two rows of half-turn pairs 90° apart give the flow's peak
 * and its amplitude a from x = (A − B)/(A + B) = −(2a/π)·sin(start − peak). Quarters: 4 turns × 4 quarter dots give
 * the dead quarter's start by the first harmonic of the 4 means (the grid alone is 90° coarse). Count angles in deg;
 * every dot at its planned start (the rows' and the grid's), else no angle.
 */
public class ScrewPhaseFitTest {
    /** n dots at an angle with mean m: ±0.02 alternating (so the mean is exact and the SD is not 0). */
    static List<ScrewPhaseFit.Dot> at(double deg, double m, int n) {
        List<ScrewPhaseFit.Dot> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(new ScrewPhaseFit.Dot(deg, m + (n % 2 == 1 && i == n - 1 ? 0 : i % 2 == 0 ? 0.02 : -0.02)));
        }
        return l;
    }

    /** A row of half-turn pairs in dispense order: s, s + 180, s, … with the means a and b. */
    static List<ScrewPhaseFit.Dot> row(double s, double a, double b, int pairs) {
        List<ScrewPhaseFit.Dot> l = new ArrayList<>();
        List<ScrewPhaseFit.Dot> as = at(s, a, pairs), bs = at((s + 180) % 360, b, pairs);
        for (int i = 0; i < pairs; i++) {
            l.add(as.get(i));
            l.add(bs.get(i));
        }
        return l;
    }

    /** A row whose x is the model's for a peak and an amplitude (A + B = 2). */
    static List<ScrewPhaseFit.Dot> modelRow(double s, double peak, double a) {
        double x = -(2 * a / Math.PI) * Math.sin(Math.toRadians(s - peak));
        return row(s, 1 + x, 1 - x, 8);
    }

    /** Quarter dots of {@code turns} turns from p with these 4 means (per turn p, p + 90, p + 180, p + 270). */
    static List<ScrewPhaseFit.Dot> grid(double p, double[] m, int turns) {
        List<ScrewPhaseFit.Dot> l = new ArrayList<>();
        for (int t = 0; t < turns; t++) {
            for (int j = 0; j < 4; j++) {
                l.add(new ScrewPhaseFit.Dot((p + 90 * j) % 360, m[j] + (m[j] == 0 ? 0 : t % 2 == 0 ? 0.01 : -0.01)));
            }
        }
        return l;
    }

    @Test
    public void halvesGiveThePeakAndTheAmplitude() {
        // the SIM's peak 179.1 at a 0.47, measured from 297.9 (and 27.9)
        ScrewPhaseFit.Halves h = ScrewPhaseFit.halves(modelRow(297.9, 179.1, 0.47), 297.9, modelRow(27.9, 179.1, 0.47),
                27.9);
        assertEquals(ScrewPhaseFit.HIGH, h.confidence, h.why.toString());
        assertEquals(179.1, h.peakDeg, 1e-9);
        assertEquals(0.47, h.a, 1e-9);
        assertEquals(359.1, h.balancedStartDeg, 1e-9);
        assertEquals(179.1, h.featureDeg(), 1e-9);
        assertEquals(-0.26220, h.rows.get(0).x, 1e-4);
        assertEquals(0.14415, h.rows.get(1).x, 1e-4);
        assertEquals(297.9, h.rows.get(0).startDeg, 1e-9);
        assertTrue(h.peakSeDeg < 2.5, "± 0.02 on areas of 1 (about 1.6): " + h.peakSeDeg);
        // any start and peak, across 0
        for (double p : new double[] {0, 46, 135.5, 270, 359}) {
            for (double peak : new double[] {3, 120, 299}) {
                ScrewPhaseFit.Halves x = ScrewPhaseFit.halves(modelRow(p, peak, 0.6), p, modelRow((p + 90) % 360, peak,
                        0.6), (p + 90) % 360);
                assertEquals(peak, x.peakDeg, 1e-9, "P " + p);
                assertEquals(0.6, x.a, 1e-9);
            }
        }
    }

    @Test
    public void halvesWithoutRippleOrDotsGiveNoAngle() {
        // a whole-turn recipe's screw: A = B in both rows
        ScrewPhaseFit.Halves flat = ScrewPhaseFit.halves(row(298, 1, 1, 8), 298, row(28, 1, 1, 8), 28);
        assertEquals(ScrewPhaseFit.NONE, flat.confidence);
        assertEquals("no ripple: a whole-turn recipe needs no start angle", flat.why.get(0));
        assertNull(flat.featureDeg());
        // 3 dots found at 118°: too few to tell a ripple from scatter, and said so
        List<ScrewPhaseFit.Dot> three = row(298, 1, 1, 8);
        for (int i = 7; i < three.size(); i += 2) {
            three.set(i, new ScrewPhaseFit.Dot(118, 0));
        }
        ScrewPhaseFit.Halves blind = ScrewPhaseFit.halves(three, 298, row(28, 1, 1, 8), 28);
        assertEquals(ScrewPhaseFit.NONE, blind.confidence);
        assertEquals("too few dots per half to see a ripple: 3 dots at 118°", blind.why.get(0));
        assertEquals("5 dots not found at 118° (a half turn always lays paste): left out", blind.why.get(1));
        // a real 0.47 ripple (± 0.1 on each dot), high from every dot, hides in a 4-dot half's error, the widest
        // (× 3.19): below MIN_PAIRS dots in a half it is too few dots, not "no ripple" (review 11-04)
        assertEquals(ScrewPhaseFit.HIGH, noisy(0.47, 0.1).confidence);
        assertEquals("[too few dots per half to see a ripple: 4 dots at 118°, 4 dots not found at 118° (a half turn "
                + "always lays paste): left out]", noisy(0.47, 0.1, 4).why.toString());
        assertEquals(ScrewPhaseFit.NONE, noisy(0.47, 0.1, 4).confidence);
        // the boundary: 5 dots in a half are still too few (MIN_PAIRS is 6; re-review NIT 11-04)
        assertEquals("too few dots per half to see a ripple: 5 dots at 118°", noisy(0.3, 0.15, 3).why.get(0));
        // 2 of the 8 dots at 118 found
        List<ScrewPhaseFit.Dot> r1 = modelRow(298, 179.1, 0.47);
        for (int i = 1; i < r1.size(); i += 2) {
            if (i > 3) {
                r1.set(i, new ScrewPhaseFit.Dot(r1.get(i).startDeg, 0));
            }
        }
        ScrewPhaseFit.Halves few = ScrewPhaseFit.halves(r1, 298, modelRow(28, 179.1, 0.47), 28);
        assertEquals(ScrewPhaseFit.NONE, few.confidence);
        assertEquals("only 2 dots to use at 118°: at least 3 per half", few.why.get(0));
        assertEquals("6 dots not found at 118° (a half turn always lays paste): left out", few.why.get(1));
        // found but joined (dots 5-16): the same count, and the notes say why
        List<ScrewPhaseFit.Dot> j = modelRow(298, 179.1, 0.47);
        for (int i = 4; i < j.size(); i++) {
            j.set(i, new ScrewPhaseFit.Dot(j.get(i).startDeg, j.get(i).areaMm2, true, true));
        }
        ScrewPhaseFit.Halves joined = ScrewPhaseFit.halves(j, 298, modelRow(28, 179.1, 0.47), 28);
        assertEquals("[only 2 dots to use at 298°: at least 3 per half, 6 dots joined to a neighbour (a thread or one "
                + "blob) at 298°: left out, 6 dots joined to a neighbour (a thread or one blob) at 118°: left out]",
                joined.why.toString());
        // a noisy measure (± 0.25 on areas of 1): the peak's standard error, √(x₂²se₁² + x₁²se₂²)/k², decides; each
        // half's error is its trimmed mean's, the winsorized SD·√n/(n − 2) × the factor of 8 dots (0.2673·√8/6·1.18
        // here: xSe 0.1051)
        ScrewPhaseFit.Halves low = noisy(0.7, 0.25);
        assertEquals(13.52, low.peakSeDeg, 0.01);
        assertEquals(0.1051, low.rows.get(0).xSe, 1e-4);
        assertEquals(ScrewPhaseFit.LOW, low.confidence);
        assertEquals("the peak's standard error is 13.5° (high at most 10°)", low.why.get(0));
        assertEquals(179.1, low.featureDeg(), 1e-9);
        assertEquals(15.77, noisy(0.6, 0.25).peakSeDeg, 0.01);
        // ± 0.25 at 0.85 read high (9.4°) before the small-sample factor; × 1.18 at 8 dots it is low
        ScrewPhaseFit.Halves wider = noisy(0.85, 0.25);
        assertEquals(ScrewPhaseFit.LOW, wider.confidence);
        assertEquals("the peak's standard error is 11.1° (high at most 10°)", wider.why.get(0));
        assertEquals(ScrewPhaseFit.HIGH, noisy(0.85, 0.2).confidence, "SE 8.9°, every half's dots within 1.5× its "
                + "median");
        assertEquals(94.61, noisy(0.1, 0.25).peakSeDeg, 0.01);
        assertEquals("no ripple: a whole-turn recipe needs no start angle", noisy(0.1, 0.25).why.get(0));
    }

    /**
     * A half turn always lays paste: a dot the camera did not find is left out, not counted as nothing; so are two dots
     * a thread or one blob joins (their areas are not their own). Each is named, and the angle is low at best.
     */
    @Test
    public void halvesAverageTheFoundDotsAndNameTheOthers() {
        List<ScrewPhaseFit.Dot> r1 = modelRow(298, 179.1, 0.47), r2 = modelRow(28, 179.1, 0.47);
        List<ScrewPhaseFit.Dot> missed = new ArrayList<>(r1), without = new ArrayList<>(r1);
        missed.set(3, new ScrewPhaseFit.Dot(118, 0));
        without.remove(3);
        ScrewPhaseFit.Halves m = ScrewPhaseFit.halves(missed, 298, r2, 28), w = ScrewPhaseFit.halves(without, 298, r2,
                28);
        assertEquals(w.peakDeg, m.peakDeg, 1e-12, "as if the missed dot was not laid");
        assertEquals(w.a, m.a, 1e-12);
        assertEquals(ScrewPhaseFit.LOW, m.confidence);
        assertEquals("1 dot not found at 118° (a half turn always lays paste): left out", m.why.get(0));
        // dots 5 and 6 (298°, 118°) joined: both left out
        List<ScrewPhaseFit.Dot> joined = new ArrayList<>(r1), cut = new ArrayList<>(r1);
        joined.set(4, new ScrewPhaseFit.Dot(298, 2.1, true, true));
        joined.set(5, new ScrewPhaseFit.Dot(118, 2.1, true, true));
        cut.remove(5);
        cut.remove(4);
        ScrewPhaseFit.Halves j = ScrewPhaseFit.halves(joined, 298, r2, 28), c = ScrewPhaseFit.halves(cut, 298, r2, 28);
        assertEquals(c.peakDeg, j.peakDeg, 1e-12);
        assertEquals(ScrewPhaseFit.LOW, j.confidence);
        assertEquals("1 dot joined to a neighbour (a thread or one blob) at 298°: left out", j.why.get(0));
        assertEquals("1 dot joined to a neighbour (a thread or one blob) at 118°: left out", j.why.get(1));
    }

    /**
     * Each half's area is its trimmed mean, min and max dropped (Process 11-04 15:0x): a start-up or carried blob at 2-3×
     * comes one at a time, so one per half moves neither the peak nor the error (winsorized); a second one still above
     * 1.5× its half's median after the trim makes the angle low ("two outliers in one half"). The guard misses some
     * second blobs (ScrewPhaseFit.OUTLIER_SHARE), so a quiet peak is still possible.
     */
    @Test
    public void halvesTrimEachHalfSoOneBlobPerHalfDoesNotMoveThePeak() {
        List<ScrewPhaseFit.Dot> r1 = modelRow(298, 179.1, 0.47), r2 = modelRow(28, 179.1, 0.47);
        ScrewPhaseFit.Halves clean = ScrewPhaseFit.halves(r1, 298, r2, 28);
        List<ScrewPhaseFit.Dot> blobs = new ArrayList<>(r1);
        blobs.set(0, new ScrewPhaseFit.Dot(298, 3 * r1.get(0).areaMm2));
        blobs.set(1, new ScrewPhaseFit.Dot(118, 2.5 * r1.get(1).areaMm2));
        ScrewPhaseFit.Halves h = ScrewPhaseFit.halves(blobs, 298, r2, 28);
        assertEquals(clean.peakDeg, h.peakDeg, 1e-9, "a blob in each half: the peak does not move");
        assertEquals(clean.a, h.a, 1e-9);
        assertEquals(clean.peakSeDeg, h.peakSeDeg, 1e-9, "nor the error");
        assertEquals(ScrewPhaseFit.HIGH, h.confidence, h.why.toString());
        // two blobs in the half at 298° (3× and 1.7×): the second is left after the trim and moves the peak ~6° with an
        // error of ~9° that would read high: low, and said
        List<ScrewPhaseFit.Dot> two = new ArrayList<>(r1);
        two.set(0, new ScrewPhaseFit.Dot(298, 3 * r1.get(0).areaMm2));
        two.set(4, new ScrewPhaseFit.Dot(298, 1.7 * r1.get(4).areaMm2));
        ScrewPhaseFit.Halves t = ScrewPhaseFit.halves(two, 298, r2, 28);
        assertEquals(ScrewPhaseFit.LOW, t.confidence, t.why.toString());
        assertTrue(t.peakSeDeg < 10, "the error alone would say high: " + t.peakSeDeg);
        assertTrue(t.why.stream().anyMatch(w -> w.startsWith("two outliers in one half at 298°: a dot at ")
                && w.endsWith("× the half's median is left after the trim")), t.why.toString());
        // a stronger second blob (2.5×) widens the error past the ripple: none, and the blob is why, not "no ripple"
        two.set(4, new ScrewPhaseFit.Dot(298, 2.5 * r1.get(4).areaMm2));
        ScrewPhaseFit.Halves t2 = ScrewPhaseFit.halves(two, 298, r2, 28);
        assertEquals(ScrewPhaseFit.NONE, t2.confidence, t2.why.toString());
        assertEquals("[no ripple can be told from these dots: two outliers in one half at 298°: a dot at 2.6× the half's "
                + "median is left after the trim]", t2.why.toString());
        // 3 dots per half: the trim leaves the middle one and winsorizing leaves no spread, so the sample SD stands in
        List<ScrewPhaseFit.Dot> few = new ArrayList<>();
        for (double a : new double[] {1.0, 1.1, 1.3}) {
            few.add(new ScrewPhaseFit.Dot(298, a));
            few.add(new ScrewPhaseFit.Dot(118, a));
        }
        double se = ScrewPhaseFit.halves(few, 298, r2, 28).rows.get(0).xSe;
        assertEquals(0.08674, se, 1e-5, "√2·0.1528·√3/1·0.51/2.2");
        // the same spread around 0.7 (three copies of 0.7 do not average to 0.7 exactly): the same error, by A + B
        List<ScrewPhaseFit.Dot> shifted = new ArrayList<>();
        for (double a : new double[] {0.6, 0.7, 0.9}) {
            shifted.add(new ScrewPhaseFit.Dot(298, a));
            shifted.add(new ScrewPhaseFit.Dot(118, a));
        }
        assertEquals(se * 2.2 / 1.4, ScrewPhaseFit.halves(shifted, 298, r2, 28).rows.get(0).xSe, 1e-9);
    }

    /** No angle from a dot the camera could not measure, or from a dot that did not start where the plan put it. */
    @Test
    public void anAreaThatIsNoNumberOrADotOffItsPlannedStartGivesNoAngle() {
        List<ScrewPhaseFit.Dot> r1 = modelRow(298, 179.1, 0.47);
        r1.set(1, new ScrewPhaseFit.Dot(118, Double.NaN, true, false));
        ScrewPhaseFit.Halves nan = ScrewPhaseFit.halves(r1, 298, modelRow(28, 179.1, 0.47), 28);
        assertEquals(ScrewPhaseFit.NONE, nan.confidence);
        assertEquals("the area of a dot at 118° is not a number: the camera did not measure it", nan.why.get(0));
        // row 2 started a quarter turn late (its first dot at 118°): no angle
        ScrewPhaseFit.Halves late = ScrewPhaseFit.halves(modelRow(298, 179.1, 0.47), 298, modelRow(118, 179.1, 0.47),
                28);
        assertEquals(ScrewPhaseFit.NONE, late.confidence);
        assertEquals("a dot started at 118°, not at 28° or 208° as planned: the screw's count does not match the plan",
                late.why.get(0));
        List<ScrewPhaseFit.Dot> q = grid(46, new double[] {0.6, 0.5, 0, 0.55}, 4);
        q.set(5, new ScrewPhaseFit.Dot(91, 0.5));
        assertEquals("a dot started at 91°, not at 46°, 136°, 226° or 316° as planned: the screw's count does not match "
                + "the plan", ScrewPhaseFit.quarters(q, 46, false).why.get(0));
        q.set(5, new ScrewPhaseFit.Dot(136, Double.NaN, true, false));
        assertEquals(ScrewPhaseFit.NONE, ScrewPhaseFit.quarters(q, 46, false).confidence);
        // and the angles stay below 360
        assertEquals(0, ScrewPhaseFit.norm(-1e-14), 0);
        assertEquals(0, ScrewPhaseFit.norm(360), 0);
        assertEquals(270, ScrewPhaseFit.norm(-90), 0);
    }

    /** Rows of 8 pairs from 298 and 28 with x at the peak 179.1 and amplitude a, ± e on each dot. */
    static ScrewPhaseFit.Halves noisy(double a, double e) {
        return noisy(a, e, 0);
    }

    /** The same with the last {@code missed} dots at 118° not found. */
    static ScrewPhaseFit.Halves noisy(double a, double e, int missed) {
        List<List<ScrewPhaseFit.Dot>> rows = new ArrayList<>();
        for (double s : new double[] {298, 28}) {
            double x = -(2 * a / Math.PI) * Math.sin(Math.toRadians(s - 179.1));
            List<ScrewPhaseFit.Dot> l = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                l.add(new ScrewPhaseFit.Dot(s, 1 + x + (i % 2 == 0 ? e : -e)));
                l.add(new ScrewPhaseFit.Dot((s + 180) % 360, s == 298 && i >= 8 - missed ? 0
                        : 1 - x - (i % 2 == 0 ? e : -e)));
            }
            rows.add(l);
        }
        return ScrewPhaseFit.halves(rows.get(0), 298, rows.get(1), 28);
    }

    /**
     * The error covers the truth at few dots (review 11-04): over seeded runs of normal scatter (cv 0.12) at a 0.3
     * ripple, 1.96·xSe holds the model's x in 93.5 to 96.5 % of the rows at 3, 4, 5, 6 and 8 dots per half (too wide an
     * error refuses good measures).
     */
    @Test
    public void halvesErrorCoversTheTruthAtFewDots() {
        Random rnd = new Random(20261104);
        Map<Integer, Double> covered = new TreeMap<>();
        for (int n : new int[] {3, 4, 5, 6, 8}) {
            int in = 0, runs = 4000;
            for (int k = 0; k < runs; k++) {
                List<List<ScrewPhaseFit.Dot>> rows = new ArrayList<>();
                double[] x = new double[2];
                for (int j = 0; j < 2; j++) {
                    double s = j == 0 ? 298 : 28;
                    x[j] = -(2 * 0.3 / Math.PI) * Math.sin(Math.toRadians(s - 179.1));
                    List<ScrewPhaseFit.Dot> l = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        l.add(new ScrewPhaseFit.Dot(s, (1 + x[j]) * (1 + 0.12 * rnd.nextGaussian())));
                        l.add(new ScrewPhaseFit.Dot((s + 180) % 360, (1 - x[j]) * (1 + 0.12 * rnd.nextGaussian())));
                    }
                    rows.add(l);
                }
                ScrewPhaseFit.Halves h = ScrewPhaseFit.halves(rows.get(0), 298, rows.get(1), 28);
                for (int j = 0; j < 2; j++) {
                    in += Math.abs(h.rows.get(j).x - x[j]) <= 1.96 * h.rows.get(j).xSe ? 1 : 0;
                }
            }
            covered.put(n, Math.round(in / (2.0 * runs) * 1000) / 1000.0);
        }
        for (double c : covered.values()) {
            assertTrue(c >= 0.935 && c <= 0.965, "dots per half = share of rows 1.96·xSe holds: " + covered);
        }
    }

    @Test
    public void quartersOfS0bGiveTheDeadQuarterBetweenTheGridPoints() {
        // runs/p35 NOTES S0b (11-03, 13.7 psi, 25G): 46: 0.70/0.62/0.58/0.60, 136: 0.52/0.47/0.41/0.43,
        // 226: 0.03/0/0, 316: 0.53/0.53/0.52
        List<ScrewPhaseFit.Dot> l = new ArrayList<>();
        double[][] v = {{0.70, 0.62, 0.58, 0.60}, {0.52, 0.47, 0.41, 0.43}, {0.03, 0, 0}, {0.53, 0.53, 0.52}};
        for (int t = 0; t < 4; t++) {
            for (int j = 0; j < 4; j++) {
                if (t < v[j].length) {
                    l.add(new ScrewPhaseFit.Dot(46 + 90 * j, v[j][t]));
                }
            }
        }
        ScrewPhaseFit.Quarters q = ScrewPhaseFit.quarters(l, 46, false);
        assertEquals(219.6, q.deadQuarterStartDeg, 0.5);
        assertEquals(226, q.gridDeadStartDeg, 1e-9);
        assertEquals(219.6, q.featureDeg(), 0.5);
        assertEquals(ScrewPhaseFit.HIGH, q.confidence, q.why.toString());
        assertEquals(3, q.liveQuarterAreas.size());
        assertEquals(0.625, q.liveQuarterAreas.get(46.0), 1e-9);
        assertEquals(0.4575, q.liveQuarterAreas.get(136.0), 1e-9);
        assertEquals(0.011, q.cvPerAngle.get(316.0), 0.001);
        assertEquals(0.084, q.cvPerAngle.get(46.0), 0.001);
        assertEquals(0.106, q.cvPerAngle.get(136.0), 0.001);
        assertTrue(q.cvPerAngle.containsKey(226.0));
        assertNull(q.cvPerAngle.get(226.0), "no spread of a dead quarter");
    }

    @Test
    public void aDipBetweenTwoGridPointsIsInterpolatedAndLow() {
        // the SIM model at offset 533, ripple 1.4, P 0: the dead arc straddles 270 and 0 (truth 314.1; the grid says 0)
        ScrewPhaseFit.Quarters q = ScrewPhaseFit.quarters(grid(0, new double[] {0.179, 0.775, 0.89, 0.343}, 4), 0,
                false);
        assertEquals(0, q.gridDeadStartDeg, 1e-9);
        assertEquals(328.7, q.deadQuarterStartDeg, 0.1);
        assertEquals(ScrewPhaseFit.LOW, q.confidence);
        // Process 11-04: one more grid started at the estimate puts a grid start on the dead quarter
        assertEquals("the dead region straddles two quarters (270° and 0°): measure again from startDeg 328.7 to put a "
                + "grid start on it", q.why.get(0));
        assertEquals(q.deadQuarterStartDeg, q.regridStartDeg, 0);
        // the worst phase (offset 1120, P 316, truth 182.6): one dead, its neighbour partly dead
        ScrewPhaseFit.Quarters w = ScrewPhaseFit.quarters(grid(316, new double[] {0.826, 0.911, 0.361, 0}, 4), 316,
                false);
        assertEquals(199.0, w.deadQuarterStartDeg, 0.1);
        assertEquals(226, w.gridDeadStartDeg, 1e-9);
        assertEquals(ScrewPhaseFit.LOW, w.confidence);
        assertEquals(w.deadQuarterStartDeg, w.regridStartDeg, 0);
        assertNull(ScrewPhaseFit.quarters(grid(46, new double[] {0.6, 0.5, 0, 0.55}, 4), 46, false).regridStartDeg,
                "one clear dead quarter needs no second grid");
    }

    @Test
    public void quartersWithoutOneClearDeadQuarterGiveNoAngle() {
        ScrewPhaseFit.Quarters half = ScrewPhaseFit.quarters(grid(46, new double[] {1, 1, 0.05, 0.04}, 4), 46, false);
        assertEquals(ScrewPhaseFit.NONE, half.confidence);
        assertEquals("a dead half: run the halves measure", half.why.get(0));
        assertNull(half.featureDeg());
        // a dead half beside a partly dead quarter is still a dead half (not two dips half a turn apart)
        assertEquals("a dead half: run the halves measure", ScrewPhaseFit.quarters(grid(46, new double[] {0.4, 1, 0, 0},
                4), 46, false).why.get(0));
        ScrewPhaseFit.Quarters flat = ScrewPhaseFit.quarters(grid(46, new double[] {1, 0.9, 0.8, 0.7}, 4), 46, false);
        assertEquals(ScrewPhaseFit.NONE, flat.confidence);
        assertEquals("no dead quarter: every quarter lays more than half the largest", flat.why.get(0));
        // Process 11-04: the stored angle came from a dead quarter, and there is none now
        assertEquals("the dead quarter is gone: check the regulator (every quarter lays more than half the largest)",
                ScrewPhaseFit.quarters(grid(46, new double[] {1, 0.9, 0.8, 0.7}, 4), 46, true).why.get(0));
        ScrewPhaseFit.Quarters two = ScrewPhaseFit.quarters(grid(46, new double[] {1, 0.1, 1, 0.1}, 4), 46, false);
        assertEquals(ScrewPhaseFit.NONE, two.confidence);
        assertEquals("two dips half a turn apart (136° and 316°)", two.why.get(0));
        ScrewPhaseFit.Quarters nothing = ScrewPhaseFit.quarters(grid(46, new double[] {0, 0, 0, 0}, 4), 46, false);
        assertEquals("no dot was found: did paste come out?", nothing.why.get(0));
        // fewer than 2 dots at an angle
        List<ScrewPhaseFit.Dot> one = grid(46, new double[] {1, 1, 0, 1}, 1);
        one.addAll(grid(46, new double[] {1, 1, 0, 1}, 1).subList(0, 3));
        ScrewPhaseFit.Quarters few = ScrewPhaseFit.quarters(one, 46, false);
        assertEquals(ScrewPhaseFit.NONE, few.confidence);
        assertEquals("only 1 measured dot at 316°: at least 2 per angle", few.why.get(0));
    }

    @Test
    public void aLiveQuarterThatSpreadsIsLow() {
        List<ScrewPhaseFit.Dot> l = grid(46, new double[] {0.6, 0.5, 0, 0.55}, 2);
        // 46's two dots 0.4 and 0.8: cv 0.47; the other live angles repeat
        l.set(0, new ScrewPhaseFit.Dot(46, 0.4));
        l.set(4, new ScrewPhaseFit.Dot(46, 0.8));
        ScrewPhaseFit.Quarters q = ScrewPhaseFit.quarters(l, 46, false);
        assertEquals(ScrewPhaseFit.LOW, q.confidence);
        assertEquals("the live quarter at 46° spreads (cv 0.47, at most 0.15)", q.why.get(0));
        assertEquals(226, q.gridDeadStartDeg, 1e-9);
        // a live dot joined to its neighbour is left out and named
        List<ScrewPhaseFit.Dot> j = grid(46, new double[] {0.6, 0.5, 0, 0.55}, 4);
        j.set(4, new ScrewPhaseFit.Dot(46, 1.4, true, true));
        ScrewPhaseFit.Quarters joined = ScrewPhaseFit.quarters(j, 46, false);
        assertEquals(ScrewPhaseFit.LOW, joined.confidence);
        assertEquals("1 dot joined to a neighbour (a thread or one blob) at 46°: left out", joined.why.get(0));
        assertEquals(0.6, joined.liveQuarterAreas.get(46.0), 0.01);
    }
}
