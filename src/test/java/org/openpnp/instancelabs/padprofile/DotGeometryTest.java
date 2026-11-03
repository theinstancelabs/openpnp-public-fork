package org.openpnp.instancelabs.padprofile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DotSizer;
import org.openpnp.instancelabs.dispense.PadRule;

/** Dot positions (§4.3 step 3) and the plane geometry of the checks. */
public class DotGeometryTest {
    /** D/DotSizer before the delegation (base 37fe1af46), the reference for today's positions. */
    static List<double[]> todaysDotSizer(double w, double h, double area, PadRule rule) {
        int n = 1;
        if (rule != null && rule.largestSingleDotPadMm2 != null && rule.largestSingleDotPadMm2 > 0
                && area > rule.largestSingleDotPadMm2) {
            int max = rule.maxDots != null ? Math.max(1, rule.maxDots) : 6;
            n = Math.min(max, (int) Math.ceil(area / rule.largestSingleDotPadMm2 - 1e-9));
        }
        int[] best = {n, 1};
        double bestCell = -1;
        for (int nx = 1; nx <= n; nx++) {
            if (n % nx != 0) {
                continue;
            }
            int ny = n / nx;
            double cell = Math.min(w / nx, h / ny);
            if (cell > bestCell + 1e-12 || (Math.abs(cell - bestCell) <= 1e-12 && (w >= h ? nx > best[0] : ny > best[1]))) {
                bestCell = cell;
                best = new int[] {nx, ny};
            }
        }
        List<double[]> l = new ArrayList<>();
        for (int iy = 0; iy < best[1]; iy++) {
            for (int ix = 0; ix < best[0]; ix++) {
                l.add(new double[] {-w / 2 + w / best[0] * (ix + 0.5), -h / 2 + h / best[1] * (iy + 0.5)});
            }
        }
        return l;
    }

    @Test
    public void withoutAFloorOrARadiusNothingMovesOnEveryDotSizerTestCase() {
        Object[][] cases = {
            {0.8, 0.95, 0.76, null},
            {5.0, 5.0, 25.0, new PadRule("big", 6, null, null)},
            {0.8, 0.95, 0.76, new PadRule("chip-0603", null, null, 1.0)},
            {0.8, 0.95, 0.76, new PadRule("chip-0603", null, null, 0.0)},
            {1.125, 1.75, 1.125 * 1.75, new PadRule("chip-1206", null, null, 1.0)},
            {1.75, 1.125, 1.125 * 1.75, new PadRule("chip-1206", null, null, 1.0)},
            {2.0, 0.5, 1.0, new PadRule("x", null, null, 0.5)},
            {3.0, 0.5, 1.0001, new PadRule("x", null, null, 0.5)},
            {1.7, 1.7, 2.89, new PadRule("qfn-centre", null, null, 0.5)},
            {1.7, 1.7, 2.89, new PadRule("qfn-centre", 4, null, 0.5)},
            {1.7, 1.7, 2.89, new PadRule("qfn-centre", 1, null, 0.1)},
            {10.0, 10.0, 100.0, new PadRule("x", null, null, 1.0)},
            {3.0, 1.0, 3.0, new PadRule("x", null, null, 1.0)},
        };
        for (Object[] c : cases) {
            double w = (Double) c[0];
            double h = (Double) c[1];
            double area = (Double) c[2];
            PadRule rule = (PadRule) c[3];
            List<double[]> today = todaysDotSizer(w, h, area, rule);
            int n = rule == null ? 1 : DotGeometry.profileDotCount(area, rule.largestSingleDotPadMm2, rule.maxDots);
            List<double[]> mine = DotGeometry.layoutN(w, h, n, 0, 0);
            List<double[]> sizer = DotSizer.layout(w, h, area, rule);
            assertEquals(today.size(), mine.size());
            assertEquals(today.size(), sizer.size());
            for (int i = 0; i < today.size(); i++) {
                assertArrayEquals(today.get(i), mine.get(i), 0, w + "x" + h + " dot " + i);
                assertArrayEquals(today.get(i), sizer.get(i), 0, w + "x" + h + " dot " + i);
            }
        }
    }

    @Test
    public void dotsSpreadToTheSamePadPitch() {
        // 0805 (1.025 x 1.40), 2 half-turn dots: the cells are 0.70 apart, the needle needs d/2 + OD/2 = 0.94
        double pMin = DoseModel.dHiMm(800) / 2 + Tip.g22().odMm / 2;
        assertEquals(0.94, pMin, 1e-9);
        List<double[]> l = DotGeometry.layoutN(1.025, 1.40, 2, pMin, DoseModel.dHiMm(800) / 2);
        assertEquals(2, l.size());
        assertArrayEquals(new double[] {0, -0.47}, l.get(0), 1e-12);
        assertArrayEquals(new double[] {0, 0.47}, l.get(1), 1e-12);
        // they reach 0.47 + 0.58 - 0.70 = 0.35 past the pad: why 0805 gets one full turn
        assertEquals(0.35, DotGeometry.overhangMm(pad(1.025, 1.40), l.get(1), 0.58), 1e-9);
        // SOT-23 (1.475 x 0.60), 2 quarter turns: spread to 0.86 -> +-0.43 (§4.6)
        double q = DoseModel.dHiMm(400);
        l = DotGeometry.layoutN(1.475, 0.60, 2, q / 2 + 0.36, q / 2);
        assertEquals(-0.43, l.get(0)[0], 0.0005);
        assertEquals(0.43, l.get(1)[0], 0.0005);
    }

    @Test
    public void outerDotsMoveInwardDownToTheFloor() {
        // 3 dots of radius 0.9 on a 3.0 x 2.0 mm pad: cells 1.0 apart overhang 0.40, so they move to 0.80 (0.20)
        List<double[]> l = DotGeometry.layoutN(3.0, 2.0, 3, 0.5, 0.9);
        assertEquals(-0.8, l.get(0)[0], 1e-12);
        assertEquals(0, l.get(1)[0], 1e-12);
        assertEquals(0.8, l.get(2)[0], 1e-12);
        assertEquals(0.2, DotGeometry.overhangMm(pad(3.0, 2.0), l.get(2), 0.9), 1e-12);
        // but never closer than the floor
        l = DotGeometry.layoutN(3.0, 2.0, 3, 0.9, 0.9);
        assertEquals(0.9, l.get(2)[0], 1e-12);
        // inside the limit nothing moves
        l = DotGeometry.layoutN(3.0, 2.0, 3, 0.5, 0.6);
        assertEquals(1.0, l.get(2)[0], 1e-12);
    }

    @Test
    public void profileDotCountIsDotSizers() {
        assertEquals(1, DotGeometry.profileDotCount(2.0, null, null));
        assertEquals(1, DotGeometry.profileDotCount(0.76, 1.0, null));
        assertEquals(2, DotGeometry.profileDotCount(1.0, 0.5, null));
        assertEquals(6, DotGeometry.profileDotCount(100, 1.0, null));
        assertEquals(4, DotGeometry.profileDotCount(2.89, 0.5, 4));
    }

    @Test
    public void planeGeometry() {
        PadInstance a = new PadInstance("R1", "R", "1", 0, 0, 90, 1.0, 0.5, 0);
        // rotated 90 deg: 1.0 along board Y
        assertEquals(0, DotGeometry.pointToPadMm(0.2, 0.45, a), 1e-12);
        assertEquals(0.1, DotGeometry.pointToPadMm(0.35, 0, a), 1e-12);
        double[] b = DotGeometry.toBoard(a, 0.5, 0);
        assertArrayEquals(new double[] {0, 0.5}, b, 1e-12);
        assertArrayEquals(new double[] {0.5, 0}, DotGeometry.toPad(a, b[0], b[1]), 1e-12);
        PadInstance c = new PadInstance("R1", "R", "2", 0.75, 0, 90, 1.0, 0.5, 0);
        assertEquals(0.25, DotGeometry.padGapMm(a, c), 1e-12);
        PadInstance d = new PadInstance("R1", "R", "3", 0.4, 0, 0, 1.0, 0.5, 0);
        assertEquals(0, DotGeometry.padGapMm(a, d), 0);
        assertTrue(Neighbours.overlapping(a, d));
        assertFalse(Neighbours.overlapping(a, c));
        // roundness: OpenPnP's corner radius as % of half the short side
        assertEquals(0.7257, DotGeometry.padAreaMm2(0.80, 0.95, 50), 0.0001);
    }

    static PadInstance pad(double w, double h) {
        return new PadInstance("X1", "X", "1", 0, 0, 0, w, h, 0);
    }
}
