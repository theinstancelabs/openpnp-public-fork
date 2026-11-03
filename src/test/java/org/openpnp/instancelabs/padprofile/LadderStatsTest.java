package org.openpnp.instancelabs.padprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** The statistics of §7.4 and the weighing rules of §7.5, with the operating points on seeded trials. */
public class LadderStatsTest {
    private static final int TRIALS = 2000;

    /** One record: n used dots after the 2 excluded ones, area (1 + alpha cos(phi - 1)) (1 + cv z) (1 + drift t). */
    static LadderStats.Record record(Random rnd, int steps, int n, double alpha, double cv, double drift) {
        LadderStats.Record r = new LadderStats.Record();
        r.steps = steps;
        int phases = LadderStats.phases(steps);
        int total = n + Limits.EXCLUDED_AFTER_CROSS;
        for (int i = 0; i < total; i++) {
            int k = i % phases;
            double phi = 2 * Math.PI * k / phases;
            double a = (1 + (phases > 1 ? alpha * Math.cos(phi - 1.0) : 0)) * (1 + cv * rnd.nextGaussian())
                    * (1 + drift * i / (total - 1));
            r.dots.add(new LadderStats.Dot(a, k));
        }
        return r;
    }

    static LadderStats.Check check(LadderStats.Stats s, String key) {
        for (LadderStats.Check c : s.checks) {
            if (c.key.equals(key)) {
                return c;
            }
        }
        return null;
    }

    static double failRate(int steps, int n, double alpha, double cv, double drift, String key, long seed) {
        Random rnd = new Random(seed);
        int fail = 0;
        for (int t = 0; t < TRIALS; t++) {
            LadderStats.Stats s = LadderStats.of(steps, Arrays.asList(record(rnd, steps, n, alpha, cv, drift)), null);
            fail += check(s, key).ok ? 0 : 1;
        }
        return fail / (double) TRIALS;
    }

    @Test
    public void k95Table() {
        assertEquals(4, LadderStats.k95(60, 0.03));
        assertEquals(6, LadderStats.k95(90, 0.03));
        assertEquals(9, LadderStats.k95(180, 0.03));
        assertEquals(1, LadderStats.k95(60, 0.005));
        assertEquals(3, LadderStats.k95(180, 0.005));
        assertEquals(1.0, LadderStats.binomCdf(60, 60, 0.3), 1e-12);
        assertEquals(0.97, LadderStats.binomCdf(4, 60, 0.03), 0.005);
        assertEquals(0.139, LadderStats.binomCdf(4, 60, 0.12), 0.002);
    }

    @Test
    public void halfSplitAndQuarterHarmonicOnKnownSets() {
        Random rnd = new Random(1);
        double[] a = new double[200];
        double[] b = new double[200];
        for (int i = 0; i < 200; i++) {
            a[i] = 1.17 * (1 + 0.01 * rnd.nextGaussian());
            b[i] = 1.03 * (1 + 0.01 * rnd.nextGaussian());
        }
        double[] hs = LadderStats.halfSplit(a, b);
        assertEquals((1.17 - 1.03) / 2.20, hs[0], 0.002);
        assertTrue(hs[1] > 0 && hs[1] < 0.002);
        // a noise-free first harmonic of amplitude 0.10 over 4 phases
        double[] ph = new double[48];
        double[] v = new double[48];
        for (int i = 0; i < 48; i++) {
            ph[i] = 2 * Math.PI * (i % 4) / 4;
            v[i] = 1 + 0.10 * Math.cos(ph[i] - 1.0);
        }
        double[] h = LadderStats.harmonic(ph, v);
        assertEquals(0.10, h[0], 1e-9);
        assertEquals(0, h[1], 1e-9);
        // with noise the fit recovers it (seeded)
        LadderStats.Stats s = LadderStats.of(400, Arrays.asList(record(new Random(2), 400, 4800, 0.10, 0.06, 0)), null);
        assertEquals("quarter", s.phaseKind);
        assertEquals(0.10, s.phaseValue, 0.005);
        assertEquals(0.06, s.residCv, 0.003);
        assertEquals(0.20, s.phaseLimit, 0);
    }

    @Test
    public void residualCvLeavesOutTheFirstTwoDotsAfterACross() {
        LadderStats.Record r = record(new Random(3), 800, 200, 0.05, 0.06, 0);
        r.dots.get(0).areaMm2 = 3.0;
        r.dots.get(1).areaMm2 = 2.5;
        LadderStats.Stats s = LadderStats.of(800, Arrays.asList(r), null);
        assertEquals(200, s.used);
        assertEquals("half", s.phaseKind);
        assertEquals(0.06, s.residCv, 0.01);
        assertTrue(check(s, "repeat").ok);
        // the same carry-over later in the record is a repeat failure
        r.dots.get(10).areaMm2 = 3.0;
        r.dots.get(11).areaMm2 = 2.5;
        r.dots.get(12).areaMm2 = 3.0;
        r.dots.get(14).areaMm2 = 2.5;
        assertTrue(LadderStats.of(800, Arrays.asList(r), null).residCv > s.residCv);
        assertEquals(LadderStats.cvUpper(0.06, 28), 0.06 * (1 + 1.645 / Math.sqrt(56)), 1e-12);
    }

    @Test
    public void aPartialDoseNotSeenAtEveryStartAngleFails() {
        // R4: 64 good dots, all from one start angle, measure no phase; the dose must not pass
        for (int steps : new int[] {800, 400}) {
            LadderStats.Record r = record(new Random(8), steps, 62, 0, 0.04, 0);
            for (LadderStats.Dot d : r.dots) {
                d.phase = 0;
            }
            LadderStats.Stats s = LadderStats.of(steps, Arrays.asList(r), null);
            assertFalse(s.pass, "" + steps);
            assertFalse(check(s, "phase").ok);
            assertNull(s.phaseStat);
            String turn = steps == 800 ? "half-turn" : "quarter-turn";
            int phases = steps == 800 ? 2 : 4;
            assertEquals("the " + turn + " dots were measured at 1 of their " + phases + " start angles (at least 2 dots "
                    + "at each needed)", check(s, "phase").text);
            assertTrue(check(s, "repeat").ok, "the plain CV of 4 % still passes");
        }
        // one turn has a single start angle: no phase check at all
        LadderStats.Stats whole = LadderStats.of(1600, Arrays.asList(record(new Random(9), 1600, 62, 0, 0.04, 0)), null);
        assertEquals("none", whole.phaseKind);
        assertNull(check(whole, "phase"));
        assertTrue(whole.pass);
    }

    @Test
    public void theDriftRule() {
        LadderStats.Stats flat = LadderStats.of(1600, Arrays.asList(record(new Random(4), 1600, 24, 0, 0.0, 0)), null);
        assertTrue(check(flat, "drift").ok);
        LadderStats.Stats trend = LadderStats.of(1600, Arrays.asList(record(new Random(4), 1600, 24, 0, 0.01, 0.20)), null);
        LadderStats.Check d = check(trend, "drift");
        assertFalse(d.ok);
        assertEquals(0.18, trend.driftPct, 0.03);
        assertTrue(d.text.startsWith("the one-turn dots grew "), d.text);
        // above 10 % but within 2 SE is noise, not drift
        LadderStats.Stats noisy = LadderStats.of(1600, Arrays.asList(record(new Random(5), 1600, 6, 0, 0.30, 0.12)), null);
        if (Math.abs(noisy.driftPct) > 0.10) {
            assertEquals(Math.abs(noisy.driftPct) > 2 * noisy.driftSe, !check(noisy, "drift").ok);
        }
    }

    @Test
    public void pooledRecordsKeepAFailure() {
        Random rnd = new Random(6);
        LadderStats.Record bad = record(rnd, 400, 59, 0.10, 0.05, 0);
        for (int i = 0; i < 10; i++) {
            bad.dots.get(5 * i).threadToNext = true;
        }
        LadderStats.Record good = record(rnd, 400, 59, 0.10, 0.05, 0);
        assertFalse(check(LadderStats.of(400, Arrays.asList(bad), null), "threads").ok);
        assertTrue(check(LadderStats.of(400, Arrays.asList(good, record(rnd, 400, 59, 0.10, 0.05, 0)), null),
                "threads").ok);
        LadderStats.Stats pooled = LadderStats.of(400, Arrays.asList(bad, good), null);
        assertEquals(10, pooled.threads);
        assertEquals(120, pooled.hops);
        assertEquals(7, pooled.threadLimit);
        assertFalse(pooled.pass);
        assertEquals("quarter-turn dots trailed a paste thread to the next dot 10 times in 120 moves (at most 7 allowed)",
                pooled.failures.get(0));
        DoseEvidence e = pooled.toEvidence();
        assertTrue(e.opticalFresh);
        assertFalse(e.opticalPass);
        assertEquals(pooled.failures, e.failures);
        // too few hops is a failure of its own
        LadderStats.Record tiny = record(rnd, 400, 20, 0.10, 0.05, 0);
        assertEquals("only 21 moves between quarter-turn dots were seen (60 needed to count the paste threads)",
                LadderStats.of(400, Arrays.asList(tiny), null).failures.get(0));
    }

    @Test
    public void missesStringsLandingAndSize() {
        LadderStats.Record r = record(new Random(7), 800, 58, 0.03, 0.04, 0);
        r.dots.get(3).found = false;
        r.dots.get(4).found = false;
        r.dots.get(20).circularity = 0.2;
        r.dots.get(30).areaMm2 = 2.5;
        for (LadderStats.Dot d : r.dots) {
            d.dxMm = 0.05;
            d.dyMm = -0.02;
        }
        LadderStats.Stats s = LadderStats.of(800, Arrays.asList(r), null);
        assertEquals(60, s.n);
        assertEquals(2, s.misses);
        assertEquals(1, s.missLimit);
        assertEquals("2 of 60 half-turn dots were not found (at most 1 allowed)", check(s, "transfer").text);
        assertEquals(2, s.strings);
        assertEquals(2, s.threads, "a string counts as a thread");
        assertEquals(Math.hypot(0.05, 0.02), s.landingMeanMm, 1e-9);
        assertEquals(0, s.landingSdMm, 1e-9);
        assertTrue(check(s, "landing").ok);
        assertEquals(s.dMeanMm * (1 + 1.65 * s.dCv), s.d95Mm, 1e-12);
        assertEquals(3, LadderStats.phaseOf(1234 + 800 + 400 + 3 * 1600, 1234, 400));
        assertEquals(0, LadderStats.phaseOf(1234 - 1600, 1234, 800));
        assertEquals(1, LadderStats.phaseOf(1234 + 800, 1234, 800));
        assertEquals(4, LadderStats.phases(400));
        assertEquals(1, LadderStats.phases(1600));
    }

    @Test
    public void operatingPointsOnSeededTrials() {
        // threads: passes 97 % at a 3 % rate, 14 % at 12 %, 0.02 % at 25 % (60 hops)
        for (double[] p : new double[][] {{0.03, 0.97}, {0.12, 0.14}, {0.25, 0.0002}}) {
            Random rnd = new Random(11);
            int pass = 0;
            for (int t = 0; t < TRIALS; t++) {
                LadderStats.Record r = record(rnd, 1600, 59, 0, 0.05, 0);
                for (int i = 0; i < r.dots.size() - 1; i++) {
                    r.dots.get(i).threadToNext = rnd.nextDouble() < p[0];
                }
                pass += check(LadderStats.of(1600, Arrays.asList(r), null), "threads").ok ? 1 : 0;
            }
            assertEquals(p[1], pass / (double) TRIALS, 0.03, "thread rate " + p[0]);
        }
        // repeat (half turns, 28 dots): bench CV 0.06 fails < 1 %, CV 0.08 < 6 %, CV 0.12 most runs
        assertTrue(failRate(800, 28, 0.0, 0.06, 0, "repeat", 12) < 0.01 + 0.03);
        assertTrue(failRate(800, 28, 0.0, 0.08, 0, "repeat", 13) < 0.06 + 0.03);
        assertTrue(failRate(800, 28, 0.0, 0.12, 0, "repeat", 14) > 0.5);
        // phase, half: the bench split (0.064) passes, twice it fails
        double benchAlpha = 0.0636 / Math.cos(1.0);
        assertTrue(failRate(800, 28, benchAlpha, 0.06, 0, "phase", 15) < 0.03);
        assertTrue(failRate(800, 28, 2 * benchAlpha, 0.06, 0, "phase", 16) > 0.5);
        // phase, quarter (48 dots): predicted 0.10 fails 0.1 % at CV 0.10; 1.5x fails 2 %; 2x fails 98 %
        assertEquals(0.0, failRate(400, 48, 0.102, 0.06, 0, "phase", 17), 0.03);
        assertEquals(0.001, failRate(400, 48, 0.102, 0.10, 0, "phase", 18), 0.03);
        assertEquals(0.02, failRate(400, 48, 0.153, 0.06, 0, "phase", 19), 0.03);
        assertEquals(0.98, failRate(400, 48, 0.204, 0.06, 0, "phase", 20), 0.03);
        // drift (24 one-turn dots, CV 0.06): a flat run fails about 1 %, a 20 % trend 95 %
        assertEquals(0.01, failRate(1600, 24, 0, 0.06, 0, "drift", 21), 0.03);
        assertEquals(0.95, failRate(1600, 24, 0, 0.06, 0.20, "drift", 22), 0.03);
    }

    @Test
    public void theControl() {
        LadderStats.Check c = LadderStats.control(800, 0.82, null, 18, null);
        assertFalse(c.ok);
        assertEquals("the control half-turn dots are 0.82 mm², 22 % below the bench's 1.06 mm²: check the regulator "
                + "(18 psi) and the paste before calibrating", c.text);
        assertTrue(LadderStats.control(800, 1.10, null, 18, null).ok);
        c = LadderStats.control(800, 1.10, 0.97, 18, null);
        assertFalse(c.ok);
        assertEquals("the control half-turn dots are 1.10 mm², 13 % above the 0.97 mm² of the last calibration: check "
                + "the regulator (18 psi) and the paste before calibrating", c.text);
        assertTrue(LadderStats.control(800, 1.10, 1.05, 18, null).ok);
    }

    @Test
    public void weighingConfirmsOnlyWithTwoReadings() {
        List<LadderStats.MassReading> l = new ArrayList<>();
        l.add(new LadderStats.MassReading(1, 400, 136, 136 * 0.575));
        LadderStats.Mass one = LadderStats.mass(400, l, null);
        assertEquals(1, one.readings);
        assertFalse(one.confirmed);
        assertEquals(0.575, one.mgPerDot, 1e-12);
        // a half-turn coupon with the same mass per step confirms both doses (positive displacement)
        l.add(new LadderStats.MassReading(2, 800, 68, 68 * 1.16));
        LadderStats.Mass q = LadderStats.mass(400, l, null);
        LadderStats.Mass h = LadderStats.mass(800, l, null);
        assertTrue(q.confirmed);
        assertEquals(Long.valueOf(2), q.confirmedBy);
        assertTrue(h.confirmed);
        DoseEvidence e = new DoseEvidence(400);
        q.applyTo(e);
        assertTrue(e.massConfirmed);
        assertEquals(0.575 / 400, e.mgPerStep, 1e-12);
        // 8 % apart: no confirmation
        l.set(1, new LadderStats.MassReading(2, 800, 68, 68 * 1.242));
        assertFalse(LadderStats.mass(400, l, null).confirmed);
        // no reading of the dose: nothing
        assertNull(LadderStats.mass(1600, l, null).mgPerDot);
        assertTrue(LadderStats.plausible(72, 136, 0.53));
        assertFalse(LadderStats.plausible(7.2, 136, 0.53));
        assertFalse(LadderStats.plausible(720, 136, 0.53));
    }
}
