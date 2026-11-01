package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pad -> profile dot positions (one per pad, or a grid where the pad type's rule says the pad is
 * too large for one dot), and the dot-ladder interpolation kept for calibration records.
 */
public class DotSizerTest {
    static List<DotLadderRow> ladder(double[][] rotDia) {
        List<DotLadderRow> l = new ArrayList<>();
        long id = 1;
        for (double[] r : rotDia) {
            DotLadderRow row = new DotLadderRow(1, r[0], 10, r[1], 0.05, Math.PI * r[1] * r[1] / 4, 0.1);
            row.id = id++;
            l.add(row);
        }
        return l;
    }

    @Test
    public void ladderInterpolatesOnlyInsideRange() {
        DotLadder l = DotLadder.of(ladder(new double[][] {{20, 0.40}, {40, 0.55}, {60, 0.70}}));
        assertEquals(0.475, l.diameterAt(30), 1e-12);
        assertEquals(0.40, l.diameterAt(20), 1e-12);
        assertEquals(0.70, l.diameterAt(60), 1e-12);
        assertNull(l.diameterAt(19.9));
        assertNull(l.diameterAt(60.1));
        assertTrue(l.isMonotonic());
    }

    @Test
    public void newestRowPerRotationWins() {
        List<DotLadderRow> rows = ladder(new double[][] {{20, 0.40}, {40, 0.55}, {40, 0.60}});
        DotLadder l = DotLadder.of(rows);
        assertEquals(2, l.rungs().size());
        assertEquals(0.60, l.diameterAt(40), 1e-12);
    }

    @Test
    public void nonMonotonicLadderRefusesToInterpolate() {
        DotLadder l = DotLadder.of(ladder(new double[][] {{20, 0.50}, {40, 0.45}}));
        assertFalse(l.isMonotonic());
        assertNull(l.diameterAt(30));
    }

    @Test
    public void oneProfileDotPerPadWithoutARule() {
        List<double[]> o = DotSizer.layout(0.8, 0.95, 0.76, null);
        assertEquals(1, o.size());
        assertEquals(0, o.get(0)[0], 0);
        assertEquals(0, o.get(0)[1], 0);
        // a rule without a size limit, or a pad below it, keeps one dot too
        assertEquals(1, DotSizer.layout(5, 5, 25, new PadRule("big", 6, null, null)).size());
        assertEquals(1, DotSizer.layout(0.8, 0.95, 0.76, new PadRule("chip-0603", null, null, 1.0)).size());
        assertEquals(1, DotSizer.layout(0.8, 0.95, 0.76, new PadRule("chip-0603", null, null, 0.0)).size());
    }

    @Test
    public void largePadGetsGridAlongLongSide() {
        // 1206-like pad 1.125 x 1.75 = 1.97 mm2, one dot covers at most 1.0 mm2 -> 2 dots along Y
        List<double[]> o = DotSizer.layout(1.125, 1.75, 1.125 * 1.75, new PadRule("chip-1206", null, null, 1.0));
        assertEquals(2, o.size());
        assertEquals(0, o.get(0)[0], 1e-12);
        assertEquals(-1.75 / 4, o.get(0)[1], 1e-12);
        assertEquals(1.75 / 4, o.get(1)[1], 1e-12);
        // rotated the other way: along X
        o = DotSizer.layout(1.75, 1.125, 1.125 * 1.75, new PadRule("chip-1206", null, null, 1.0));
        assertEquals(-1.75 / 4, o.get(0)[0], 1e-12);
        assertEquals(1.75 / 4, o.get(1)[0], 1e-12);
    }

    @Test
    public void dotCountIsTheCeilingOfAreaOverTheLimitCappedByMaxDots() {
        // exactly twice the limit: 2 dots, not 3 (no floating-point surprise)
        assertEquals(2, DotSizer.layout(2.0, 0.5, 1.0, new PadRule("x", null, null, 0.5)).size());
        assertEquals(3, DotSizer.layout(3.0, 0.5, 1.0001, new PadRule("x", null, null, 0.5)).size());
        // 1.7 x 1.7 QFN centre, 0.5 mm2 per dot -> 6 (= the default cap) on a 3 x 2 grid
        List<double[]> o = DotSizer.layout(1.7, 1.7, 2.89, new PadRule("qfn-centre", null, null, 0.5));
        assertEquals(DotSizer.DEFAULT_MAX_DOTS, o.size());
        // the rule's maxDots caps the grid: 4 -> 2 x 2, cells centred in the pad
        o = DotSizer.layout(1.7, 1.7, 2.89, new PadRule("qfn-centre", 4, null, 0.5));
        assertEquals(4, o.size());
        for (double[] d : o) {
            assertEquals(1.7 / 4, Math.abs(d[0]), 1e-12);
            assertEquals(1.7 / 4, Math.abs(d[1]), 1e-12);
        }
        // maxDots 1: one dot whatever the area
        assertEquals(1, DotSizer.layout(1.7, 1.7, 2.89, new PadRule("qfn-centre", 1, null, 0.1)).size());
        // a huge pad never gets more than the default cap
        assertEquals(DotSizer.DEFAULT_MAX_DOTS, DotSizer.layout(10, 10, 100, new PadRule("x", null, null, 1.0)).size());
    }

    @Test
    public void gridCellsTileThePad() {
        List<double[]> o = DotSizer.layout(3.0, 1.0, 3.0, new PadRule("x", null, null, 1.0));
        assertEquals(3, o.size());
        assertEquals(-1.0, o.get(0)[0], 1e-12);
        assertEquals(0.0, o.get(1)[0], 1e-12);
        assertEquals(1.0, o.get(2)[0], 1e-12);
        for (double[] d : o) {
            assertEquals(0, d[1], 1e-12);
        }
    }

    @Test
    public void bestGrid() {
        assertEquals(2, DotSizer.bestGrid(2, 2.0, 1.0)[0]);
        assertEquals(1, DotSizer.bestGrid(2, 1.0, 2.0)[0]);
        int[] g = DotSizer.bestGrid(4, 2.0, 2.0);
        assertEquals(2, g[0]);
        assertEquals(2, g[1]);
        // prime n on a square: a row along X (ties -> more columns along the long side, X if equal)
        g = DotSizer.bestGrid(3, 1.7, 1.7);
        assertEquals(3, g[0]);
        assertEquals(1, g[1]);
        g = DotSizer.bestGrid(6, 1.7, 1.7);
        assertEquals(6, g[0] * g[1]);
        assertEquals(3, Math.max(g[0], g[1]));
        g = DotSizer.bestGrid(1, 0.8, 0.95);
        assertEquals(1, g[0]);
        assertEquals(1, g[1]);
    }
}
