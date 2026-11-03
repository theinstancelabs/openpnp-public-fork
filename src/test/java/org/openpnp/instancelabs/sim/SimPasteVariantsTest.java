package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The negative controls of §11.7 at the SIM level: each scene variant moves the quantity the
 * calibration checks (§7.4) past its limit, and the default scene stays well inside it. The engine's
 * verdicts on them are pc-task's end-to-end test; here the paste itself is measured (areas from the
 * deposits; the camera measures them within 5 %, SimPasteInspectTest).
 */
public class SimPasteVariantsTest {
    /** The engine's start angle 298° as a count (the purge dot puts the work there): 1324 steps. */
    static final long START_COUNT = Math.round(298 / 360.0 * SimPaste.STEPS_PER_REV);
    final long[] nowNs = {0};

    SimScene scene(PadcalScenes.Variant v) {
        SimScene s = SimPasteTest.scene();
        if (v != null) {
            PadcalScenes.apply(s, v, 0.0);
        }
        return s;
    }

    SimPaste paste(SimScene s) {
        return new SimPaste(() -> s, SimPaste.Store.forScene(s), () -> nowNs[0], () -> 0.0);
    }

    /** A ladder as the engine lays it: 8 purge dots of the profile's half turn, then n dots of a dose on the blank. */
    List<SimPaste.Deposit> ladder(SimScene s, SimPaste p, long steps, int n, double pitch) {
        SimNeedle needle = new SimNeedle(p, s.paste.n2OffsetMm);
        p.setCount(START_COUNT - 8 * 800);
        needle.pressurize();
        needle.row(282, 227, 2.5, 8, SimPasteTest.BLANK_Z, 800);
        int perRow = (int) Math.floor(70 / pitch);
        for (int i = 0; i < n; i += perRow) {
            needle.travel(285, 231 + 2.5 * (i / perRow));
            needle.row(285, 231 + 2.5 * (i / perRow), pitch, Math.min(perRow, n - i), SimPasteTest.BLANK_Z, steps);
        }
        needle.vent();
        List<SimPaste.Deposit> d = SimNeedle.dots(p, "blank");
        return d.subList(Math.min(8, d.size()), d.size());
    }

    /**
     * First-harmonic amplitude of area vs screw phase, relative to the mean, from the per-phase means
     * (the first 2 dots left out).
     */
    static double harmonic(List<SimPaste.Deposit> dots, long steps) {
        double[] m = phaseMeans(dots, steps);
        double re = 0, im = 0, mean = 0;
        for (int k = 0; k < m.length; k++) {
            re += m[k] * Math.cos(2 * Math.PI * k / m.length);
            im += m[k] * Math.sin(2 * Math.PI * k / m.length);
            mean += m[k] / m.length;
        }
        return 2 * Math.hypot(re, im) / m.length / mean;
    }

    /** Mean area per phase k = ((start count − 1324) mod 1600) / steps, the first 2 dots left out. */
    static double[] phaseMeans(List<SimPaste.Deposit> dots, long steps) {
        int phases = (int) (SimPaste.STEPS_PER_REV / steps);
        double[] sum = new double[phases];
        int[] n = new int[phases];
        for (int i = 2; i < dots.size(); i++) {
            int k = (int) (Math.floorMod(dots.get(i).startCount - START_COUNT, SimPaste.STEPS_PER_REV) / steps);
            sum[k] += dots.get(i).areaMm2();
            n[k]++;
        }
        for (int k = 0; k < phases; k++) {
            sum[k] /= n[k];
        }
        return sum;
    }

    /** Half turns: |mean(φ0) − mean(φ1)| / (mean(φ0) + mean(φ1)) of the area, the first 2 dots left out. */
    static double halfSplit(List<SimPaste.Deposit> dots) {
        double[] m = phaseMeans(dots, 800);
        return Math.abs(m[0] - m[1]) / (m[0] + m[1]);
    }

    /** Area CV after the per-phase means are removed (the residual of §7.4's repeat check). */
    static double residualCv(List<SimPaste.Deposit> dots, long steps) {
        int phases = (int) (SimPaste.STEPS_PER_REV / steps);
        double[] means = phaseMeans(dots, steps);
        double ss = 0, all = 0;
        int m = 0;
        for (int i = 2; i < dots.size(); i++) {
            int k = (int) (Math.floorMod(dots.get(i).startCount - START_COUNT, SimPaste.STEPS_PER_REV) / steps);
            double r = dots.get(i).areaMm2() - means[k];
            ss += r * r;
            all += dots.get(i).areaMm2();
            m++;
        }
        return Math.sqrt(ss / (m - phases)) / (all / m);
    }

    @Test
    public void reportCountOff() {
        SimScene s = scene(PadcalScenes.Variant.REPORT_COUNT_OFF);
        SimNeedle n = new SimNeedle(paste(s), s.paste.n2OffsetMm);
        assertNull(n.send("M114"), "no Count: the engine cannot set a partial turn's start angle (I11)");
        assertNotNull(new SimNeedle(paste(scene(null)), s.paste.n2OffsetMm).send("M114"));
    }

    @Test
    public void ripple045BreaksThePhaseChecks() {
        // P4's limits: quarter-turn first-harmonic amplitude ≤ 0.20, half-turn split ≤ 0.13. The tip bead
        // (transferRatio 0.75) carries a dot into the next and smooths the ripple: ×0.73 for quarter turns,
        // ×0.6 for half turns, so 0.45 gives about 0.23 and 0.13 (§11.7's 0.31 and 0.22 leave the bead out).
        SimScene s = scene(PadcalScenes.Variant.RIPPLE_045);
        double q = harmonic(ladder(s, paste(s), 400, 96, 2.5), 400);
        assertTrue(q > 0.20 && q < 0.27, "quarter amplitude " + q);
        s = scene(PadcalScenes.Variant.RIPPLE_045);
        double h = halfSplit(ladder(s, paste(s), 800, 56, 2.5));
        assertTrue(h > 0.115 && h < 0.16, "half split at the worst phase " + h);
        s = scene(null);
        s.paste.offsetSteps = PadcalScenes.RIPPLE_WORST_OFFSET_STEPS;
        assertTrue(harmonic(ladder(s, paste(s), 400, 96, 2.5), 400) < 0.11, "the default ripple 0.15");
        s = scene(null);
        s.paste.offsetSteps = PadcalScenes.RIPPLE_WORST_OFFSET_STEPS;
        assertTrue(halfSplit(ladder(s, paste(s), 800, 56, 2.5)) < 0.06, "the default ripple 0.15, worst phase");
    }

    @Test
    public void threadZero065ThreadsTheQuarterTurns() {
        int[] threads = new int[2];
        PadcalScenes.Variant[] vs = {null, PadcalScenes.Variant.THREAD_ZERO_065};
        for (int i = 0; i < 2; i++) {
            SimScene s = scene(vs[i]);
            SimPaste p = paste(s);
            ladder(s, p, 400, 136, 2.0);
            for (SimPaste.Deposit d : p.deposits("blank")) {
                threads[i] += d.thread ? 1 : 0;
            }
        }
        assertEquals(0, threads[0], "0.575 mg quarter dots are above threadZeroMg 0.45");
        assertTrue(threads[1] > 9, "above k95(180 hops, 3 %) = 9 in 135 hops: " + threads[1]);
    }

    @Test
    public void cv020FailsTheRepeatCheckOnAlmostEveryLadder() {
        // §7.4's repeat check at the ladder's own size: residual area CV × (1 + 1.645/√(2n)) ≤ 0.13, n the dots
        // after the first 2 of 28 half turns or 48 quarter turns. §11.7's cv 0.16 gives an area CV of 0.123 with
        // the tip bead and passes on about one ladder in five; cv 0.20 (PadcalScenes.REPEAT_FAIL_CV) gives 0.154.
        SimScene s = scene(PadcalScenes.Variant.CV_020);
        double bad = residualCv(ladder(s, paste(s), 800, 200, 2.5), 800);
        s = scene(null);
        double good = residualCv(ladder(s, paste(s), 800, 200, 2.5), 800);
        assertTrue(bad > 0.14 && bad < 0.17, "cv 0.20: area CV " + bad);
        assertTrue(good < 0.075, "cv 0.08: area CV " + good);
        int badPassed = 0, goodFailed = 0;
        for (long seed = 0; seed < 40; seed++) {
            for (long steps : new long[] {800, 400}) {
                int n = steps == 800 ? 28 : 48;
                double pitch = steps == 800 ? 2.5 : 2.0;
                s = scene(PadcalScenes.Variant.CV_020);
                s.paste.seed = seed;
                badPassed += repeatPasses(ladder(s, paste(s), steps, n, pitch), steps) ? 1 : 0;
                s = scene(null);
                s.paste.seed = seed;
                goodFailed += repeatPasses(ladder(s, paste(s), steps, n, pitch), steps) ? 0 : 1;
            }
        }
        assertTrue(badPassed <= 2, "cv 0.20 passed the repeat check on " + badPassed + " of 80 ladders");
        assertEquals(0, goodFailed, "the default scene failed it on " + goodFailed + " of 80 ladders");
    }

    /** §7.4's repeat check on one ladder: the residual area CV's upper bound at most 0.13. */
    static boolean repeatPasses(List<SimPaste.Deposit> dots, long steps) {
        int n = dots.size() - 2;
        return residualCv(dots, steps) * (1 + 1.645 / Math.sqrt(2 * n)) <= 0.13;
    }

    @Test
    public void landingBiasMovesEveryDot() {
        SimScene s = scene(PadcalScenes.Variant.LANDING_BIAS_02);
        SimPaste p = paste(s);
        SimNeedle n = new SimNeedle(p, s.paste.n2OffsetMm);
        n.pressurize();
        n.row(285, 235, 2.5, 25, SimPasteTest.BLANK_Z, 800);
        double sx = 0, sy = 0;
        List<SimPaste.Deposit> d = SimNeedle.dots(p, "blank");
        for (int i = 0; i < d.size(); i++) {
            sx += d.get(i).x - (285 + 2.5 * i);
            sy += d.get(i).y - 235;
        }
        assertEquals(0.2, sx / d.size(), 0.02, "above the 0.15 mm landing limit");
        assertEquals(0, sy / d.size(), 0.02);
    }

    @Test
    public void needleGap025MissesEveryDot() {
        SimScene s = scene(PadcalScenes.Variant.NEEDLE_GAP_025);
        SimPaste p = paste(s);
        SimNeedle n = new SimNeedle(p, s.paste.n2OffsetMm);
        n.pressurize();
        n.row(285, 235, 2.5, 12, SimPasteTest.BLANK_Z, 800); // the engine's gap 0.10 over its own offset 0
        assertTrue(p.deposits(null).isEmpty(), "0.25 above the surface: nothing transfers");
        assertTrue(p.beadMg() > 10, "it all hangs on the tip: " + p.beadMg());
    }

    @Test
    public void aReconnectMovesThePhaseUnderTheSameCount() {
        SimScene s = scene(null);
        SimPaste p = paste(s);
        int before = p.offsetSteps();
        p.powerCycle();
        assertNotEquals(before, p.offsetSteps(), "the count-based start angle is another physical angle now");
        assertEquals(0, p.count());
    }

    @Test
    public void idleDead0KillsTheFirstTwoDotsOfEverySession() {
        SimScene s = scene(PadcalScenes.Variant.IDLE_DEAD_0);
        SimPaste p = paste(s);
        SimNeedle n = new SimNeedle(p, s.paste.n2OffsetMm);
        for (int session = 0; session < 3; session++) {
            n.pressurize();
            n.row(285, 230 + 5 * session, 2.5, 10, SimPasteTest.BLANK_Z, 800);
            n.vent();
            List<SimPaste.Deposit> d = SimNeedle.dots(p, "blank");
            assertEquals(8 * (session + 1), d.size(), "session " + session + ": 8 of 10 laid");
            assertEquals(285 + 5, d.get(8 * session).x, 0.15, "the first laid is the third dot");
        }
    }

    @Test
    public void dwell50OneTurnDotsWouldStarveTheScrew() {
        // The engine refuses this recipe (I6-feed) before anything moves; the SIM shows why.
        SimScene s = scene(null);
        SimPaste p = paste(s);
        SimNeedle n = new SimNeedle(p, s.paste.n2OffsetMm);
        n.dwellMs = 50;
        n.pressurize();
        n.row(282, 230, 2.4, 29, SimPasteTest.BLANK_Z, 1600);
        n.row(282, 235, 2.4, 29, SimPasteTest.BLANK_Z, 1600);
        List<SimPaste.Deposit> d = SimNeedle.dots(p, "blank");
        double sum = 0;
        for (int i = 31; i < 58; i++) {
            sum += d.get(i).mg;
        }
        double rate = sum / (d.get(57).atS - d.get(30).atS);
        assertTrue(rate <= 3.05, "laid no faster than the paste feeds (3.0 mg/s): " + rate);
        assertTrue(sum / 27 < 0.9 * 2.30, "the one-turn dots shrink: " + sum / 27);
    }
}
