package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * BR-77: the start-angle measure's fits (plan §4). Halves: two rows of half-turn pairs 90° apart give the flow's peak
 * and its amplitude a from x = (A − B)/(A + B) = −(2a/π)·sin(start − peak). Quarters: 4 turns × 4 quarter dots give
 * the dead quarter's start by the first harmonic of the 4 means (the grid alone is 90° coarse). Count angles in deg.
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
        ScrewPhaseFit.Halves h = ScrewPhaseFit.halves(modelRow(297.9, 179.1, 0.47), modelRow(27.9, 179.1, 0.47));
        assertEquals(ScrewPhaseFit.HIGH, h.confidence, h.why.toString());
        assertEquals(179.1, h.peakDeg, 1e-9);
        assertEquals(0.47, h.a, 1e-9);
        assertEquals(359.1, h.balancedStartDeg, 1e-9);
        assertEquals(179.1, h.featureDeg(), 1e-9);
        assertEquals(-0.26220, h.rows.get(0).x, 1e-4);
        assertEquals(0.14415, h.rows.get(1).x, 1e-4);
        assertEquals(297.9, h.rows.get(0).startDeg, 1e-9);
        assertTrue(h.peakSeDeg < 2, "± 0.02 on areas of 1: " + h.peakSeDeg);
        // any start and peak, across 0
        for (double p : new double[] {0, 46, 135.5, 270, 359}) {
            for (double peak : new double[] {3, 120, 299}) {
                ScrewPhaseFit.Halves x = ScrewPhaseFit.halves(modelRow(p, peak, 0.6), modelRow((p + 90) % 360, peak,
                        0.6));
                assertEquals(peak, x.peakDeg, 1e-9, "P " + p);
                assertEquals(0.6, x.a, 1e-9);
            }
        }
    }

    @Test
    public void halvesWithoutRippleOrDotsGiveNoAngle() {
        // a whole-turn recipe's screw: A = B in both rows
        ScrewPhaseFit.Halves flat = ScrewPhaseFit.halves(row(298, 1, 1, 8), row(28, 1, 1, 8));
        assertEquals(ScrewPhaseFit.NONE, flat.confidence);
        assertEquals("no ripple: a whole-turn recipe needs no start angle", flat.why.get(0));
        assertNull(flat.featureDeg());
        // 2 of the 8 dots at 118 found
        List<ScrewPhaseFit.Dot> r1 = modelRow(298, 179.1, 0.47);
        for (int i = 1; i < r1.size(); i += 2) {
            if (i > 3) {
                r1.set(i, new ScrewPhaseFit.Dot(r1.get(i).startDeg, 0));
            }
        }
        ScrewPhaseFit.Halves few = ScrewPhaseFit.halves(r1, modelRow(28, 179.1, 0.47));
        assertEquals(ScrewPhaseFit.NONE, few.confidence);
        assertEquals("only 2 dots found at 118°: at least 3 per half", few.why.get(0));
        // a noisy measure (± 0.25 on areas of 1): the peak's standard error decides
        ScrewPhaseFit.Halves none = noisy(0.336);
        assertEquals(25.3, none.peakSeDeg, 0.05);
        assertEquals(ScrewPhaseFit.NONE, none.confidence);
        assertEquals("the peak is uncertain: its standard error is 25.3° (at most 20° to set it)", none.why.get(0));
        ScrewPhaseFit.Halves low = noisy(0.53);
        assertEquals(16.05, low.peakSeDeg, 0.05);
        assertEquals(ScrewPhaseFit.LOW, low.confidence);
        assertEquals(179.1, low.featureDeg(), 1e-9);
        assertEquals(85.0, noisy(0.1).peakSeDeg, 0.1);
        assertEquals("no ripple: a whole-turn recipe needs no start angle", noisy(0.1).why.get(0));
    }

    /** Rows of 8 pairs from 298 and 28 with x at the peak 179.1 and amplitude a, ± 0.25 on each dot. */
    static ScrewPhaseFit.Halves noisy(double a) {
        List<List<ScrewPhaseFit.Dot>> rows = new ArrayList<>();
        for (double s : new double[] {298, 28}) {
            double x = -(2 * a / Math.PI) * Math.sin(Math.toRadians(s - 179.1));
            List<ScrewPhaseFit.Dot> l = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                double e = i % 2 == 0 ? 0.25 : -0.25;
                l.add(new ScrewPhaseFit.Dot(s, 1 + x + e));
                l.add(new ScrewPhaseFit.Dot((s + 180) % 360, 1 - x - e));
            }
            rows.add(l);
        }
        return ScrewPhaseFit.halves(rows.get(0), rows.get(1));
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
        ScrewPhaseFit.Quarters q = ScrewPhaseFit.quarters(l);
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
        ScrewPhaseFit.Quarters q = ScrewPhaseFit.quarters(grid(0, new double[] {0.179, 0.775, 0.89, 0.343}, 4));
        assertEquals(0, q.gridDeadStartDeg, 1e-9);
        assertEquals(328.7, q.deadQuarterStartDeg, 0.1);
        assertEquals(ScrewPhaseFit.LOW, q.confidence);
        assertEquals("the dead region straddles two quarters (270° and 0°)", q.why.get(0));
        // the worst phase (offset 1120, P 316, truth 182.6): one dead, its neighbour partly dead
        ScrewPhaseFit.Quarters w = ScrewPhaseFit.quarters(grid(316, new double[] {0.826, 0.911, 0.361, 0}, 4));
        assertEquals(199.0, w.deadQuarterStartDeg, 0.1);
        assertEquals(226, w.gridDeadStartDeg, 1e-9);
        assertEquals(ScrewPhaseFit.LOW, w.confidence);
    }

    @Test
    public void quartersWithoutOneClearDeadQuarterGiveNoAngle() {
        ScrewPhaseFit.Quarters half = ScrewPhaseFit.quarters(grid(46, new double[] {1, 1, 0.05, 0.04}, 4));
        assertEquals(ScrewPhaseFit.NONE, half.confidence);
        assertEquals("a dead half: run the halves measure", half.why.get(0));
        assertNull(half.featureDeg());
        ScrewPhaseFit.Quarters flat = ScrewPhaseFit.quarters(grid(46, new double[] {1, 0.9, 0.8, 0.7}, 4));
        assertEquals(ScrewPhaseFit.NONE, flat.confidence);
        assertEquals("no dead quarter: every quarter lays more than half the largest", flat.why.get(0));
        ScrewPhaseFit.Quarters two = ScrewPhaseFit.quarters(grid(46, new double[] {1, 0.1, 1, 0.1}, 4));
        assertEquals(ScrewPhaseFit.NONE, two.confidence);
        assertEquals("two dips half a turn apart (136° and 316°)", two.why.get(0));
        ScrewPhaseFit.Quarters nothing = ScrewPhaseFit.quarters(grid(46, new double[] {0, 0, 0, 0}, 4));
        assertEquals("no dot was found: did paste come out?", nothing.why.get(0));
        // fewer than 2 dots at an angle
        List<ScrewPhaseFit.Dot> one = grid(46, new double[] {1, 1, 0, 1}, 1);
        one.addAll(grid(46, new double[] {1, 1, 0, 1}, 1).subList(0, 3));
        ScrewPhaseFit.Quarters few = ScrewPhaseFit.quarters(one);
        assertEquals(ScrewPhaseFit.NONE, few.confidence);
        assertEquals("only 1 measured dot at 316°: at least 2 per angle", few.why.get(0));
    }

    @Test
    public void aLiveQuarterThatSpreadsIsLow() {
        List<ScrewPhaseFit.Dot> l = grid(46, new double[] {0.6, 0.5, 0, 0.55}, 2);
        // 46's two dots 0.4 and 0.8: cv 0.47; the other live angles repeat
        l.set(0, new ScrewPhaseFit.Dot(46, 0.4));
        l.set(4, new ScrewPhaseFit.Dot(46, 0.8));
        ScrewPhaseFit.Quarters q = ScrewPhaseFit.quarters(l);
        assertEquals(ScrewPhaseFit.LOW, q.confidence);
        assertEquals("the live quarter at 46° spreads (cv 0.47, at most 0.15)", q.why.get(0));
        assertEquals(226, q.gridDeadStartDeg, 1e-9);
    }
}
