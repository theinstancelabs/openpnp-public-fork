package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.LadderStats;
import org.openpnp.instancelabs.padprofile.Tip;

import com.google.gson.Gson;

/**
 * Tip setups (§22.2, §22.8): a setup's dot table is the newest passing optical record per dose of the setup at its
 * recipe and paste and the simulator flag, any age; failed records, other setups, another recipe or paste, a job
 * flagged wrong-tip and the other simulator flag never enter it; the mass per step is the newest confirmed weighing.
 */
public class DotTableTest {
    static final String S = "25g-straight";
    long nextId = 1;

    DoseRecord optical(DispenseConfig c, int steps, double dMean, double d95, boolean pass, long at) {
        DoseRecord r = DoseRecordTest.record(DoseRecord.OPTICAL, steps, c, at);
        r.id = nextId++;
        r.tipKey = S;
        r.diameterMeanMm = dMean;
        r.diameterP95Mm = d95;
        r.pass = pass;
        r.jobId = "job-a";
        return r;
    }

    DoseRecord coupon(DispenseConfig c, int steps, long at) {
        DoseRecord r = DoseRecordTest.record(DoseRecord.COUPON, steps, c, at);
        r.id = nextId++;
        r.tipKey = S;
        r.jobId = "job-a";
        return r;
    }

    DoseRecord mass(DoseRecord coupon, double totalMg, Long confirmedBy) {
        DoseRecord m = DoseRecordTest.mass(coupon, totalMg);
        m.id = nextId++;
        m.createdAtMs = coupon.createdAtMs + 600_000;
        m.confirmedBy = confirmedBy;
        m.jobId = coupon.jobId;
        return m;
    }

    @Test
    public void theNewestPassingOpticalRecordPerDoseAnyAge() {
        DispenseConfig c = new DispenseConfig();
        List<DoseRecord> l = new ArrayList<>();
        l.add(optical(c, 400, 0.60, 0.66, true, 1_000L)); // years old: still in the table
        l.add(optical(c, 400, 0.62, 0.69, true, 5_000L));
        l.add(optical(c, 400, 0.70, 0.95, false, 6_000L)); // failed: never
        l.add(optical(c, 800, 0.81, 0.88, true, 7_000L));
        DoseRecord other = optical(c, 1600, 1.0, 1.1, true, 8_000L);
        other.tipKey = "ad22tta-b"; // another setup's
        l.add(other);
        DoseRecord recipe = optical(c, 1600, 1.0, 1.1, true, 9_000L);
        recipe.doseFp = "another-recipe";
        l.add(recipe);
        DoseRecord paste = optical(c, 1600, 1.0, 1.1, true, 9_100L);
        paste.pasteKey = "another-paste";
        l.add(paste);
        DoseRecord sim = optical(c, 1600, 1.0, 1.1, true, 9_200L);
        sim.simulated = true;
        l.add(sim);
        DoseRecord flagged = optical(c, 1600, 1.0, 1.1, true, 9_300L);
        flagged.jobId = "job-wrong";
        l.add(flagged);
        DoseRecords.DotTable t = DoseRecords.dotTable(l, S, c.profile.doseFingerprint(), c.paste.key, false,
                Collections.singleton("job-wrong"));
        assertEquals(2, t.doses.size(), t.doses.keySet().toString());
        Tip.Measured q = t.doses.get(400);
        assertEquals(0.62, q.dMeanMm, 0);
        assertEquals(0.69, q.d95Mm, 0);
        assertEquals(2, q.recordId);
        assertEquals(5_000L, q.atMs);
        assertEquals(0.88, t.doses.get(800).d95Mm, 0);
        assertNull(t.mgPerStep, "never weighed");
        // the simulator's table has the simulator's record only
        DoseRecords.DotTable s = DoseRecords.dotTable(l, S, c.profile.doseFingerprint(), c.paste.key, true,
                Collections.<String>emptySet());
        assertEquals(Collections.singleton(1600), s.doses.keySet());
    }

    /** A half-turn block's dots as the camera saw them: round, the two start angles in turn, on their spots. */
    static String dots(int n, double areaMm2, boolean threads) {
        List<DoseRecords.Seen> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            DoseRecords.Seen s = new DoseRecords.Seen();
            s.found = true;
            s.a = areaMm2 * (1 + 0.02 * (i % 3 - 1));
            s.d = 2 * Math.sqrt(s.a / Math.PI);
            s.c = 0.9;
            s.dx = 0.0;
            s.dy = 0.0;
            s.k = i % 2;
            s.t = threads;
            l.add(s);
        }
        return new Gson().toJson(l);
    }

    @Test
    public void aLadderBlockSizedForItsJobsWeighDotsCountsWithThem() {
        // §7.4: the weighed dose's ladder block has 28 dots (27 moves) because its 136 weigh dots on the coupons add
        // the moves the thread count needs (60 at the machine's floor): alone it fails, with them it passes, as the
        // job judged it (Measure its dots of a tip whose smallest dot is the weighed half turn)
        DispenseConfig c = new DispenseConfig();
        LadderStats.Settings set = DoseRecords.settings(c.padCalibration);
        String fp = c.profile.doseFingerprint();
        DoseRecord ladder = optical(c, 800, 0.80, 0.84, false, 1_000L);
        ladder.dotsJson = dots(28, 0.5, false);
        DoseRecord k1 = coupon(c, 800, 2_000L);
        k1.dotsJson = dots(68, 0.5, false);
        DoseRecord k2 = coupon(c, 800, 3_000L);
        k2.dotsJson = dots(68, 0.5, false);
        List<DoseRecord> l = new ArrayList<>(Arrays.asList(ladder, k1, k2));
        assertTrue(DoseRecords.dotTable(l, S, fp, c.paste.key, false, Collections.<String>emptySet()).doses.isEmpty(),
                "each optical record alone: the block fails its thread count");
        Tip.Measured m = DoseRecords.dotTable(l, S, fp, c.paste.key, false, Collections.<String>emptySet(), set).doses
                .get(800);
        assertNotNull(m, "pooled with its job's weigh dots");
        assertEquals(ladder.id, m.recordId);
        assertEquals(3_000L, m.atMs, "the newest record of the pool");
        assertEquals(2 * Math.sqrt(0.5 / Math.PI), m.dMeanMm, 0.01, "the pooled size");
        // another job's weigh dots never complete it
        k1.jobId = "job-b";
        k2.jobId = "job-b";
        assertTrue(DoseRecords.dotTable(l, S, fp, c.paste.key, false, Collections.<String>emptySet(), set).doses
                .isEmpty());
        // nor do weigh dots that fail with it: a paste thread after every one
        k1.jobId = "job-a";
        k2.jobId = "job-a";
        k1.dotsJson = dots(68, 0.5, true);
        assertTrue(DoseRecords.dotTable(l, S, fp, c.paste.key, false, Collections.<String>emptySet(), set).doses
                .isEmpty());
    }

    @Test
    public void theMassPerStepIsTheNewestConfirmedWeighing() {
        DispenseConfig c = new DispenseConfig();
        List<DoseRecord> l = new ArrayList<>();
        DoseRecord k1 = coupon(c, 800, 10_000L);
        DoseRecord k2 = coupon(c, 800, 20_000L);
        DoseRecord k3 = coupon(c, 800, 30_000L);
        l.add(k1);
        l.add(k2);
        l.add(k3);
        DoseRecord m1 = mass(k1, 68 * 0.8, null);
        l.add(m1);
        DoseRecord m2 = mass(k2, 68 * 0.82, m1.id); // agrees with m1: confirmed
        l.add(m2);
        DoseRecord m3 = mass(k3, 68 * 1.2, null); // newer, but weighed once
        l.add(m3);
        DoseRecords.DotTable t = DoseRecords.dotTable(l, S, c.profile.doseFingerprint(), c.paste.key, false,
                Collections.<String>emptySet());
        assertTrue(t.mgPerStepConfirmed);
        assertEquals(0.82 / 800, t.mgPerStep, 1e-9);
        assertEquals(m2.createdAtMs, (long) t.mgPerStepAtMs);
        // without the confirmed pair: the newest single weighing
        l.remove(m2);
        DoseRecords.DotTable once = DoseRecords.dotTable(l, S, c.profile.doseFingerprint(), c.paste.key, false,
                Collections.<String>emptySet());
        assertFalse(once.mgPerStepConfirmed);
        assertEquals(1.2 / 800, once.mgPerStep, 1e-9);
        // a skipped weighing is no reading; the job flagged wrong-tip leaves the table
        Set<String> flagged = Collections.singleton("job-a");
        DoseRecords.DotTable none = DoseRecords.dotTable(l, S, c.profile.doseFingerprint(), c.paste.key, false, flagged);
        assertNull(none.mgPerStep);
        assertTrue(none.doses.isEmpty());
    }

    @Test
    public void flaggedJobsLeaveTheEvidence() {
        DispenseConfig c = DoseRecordTest.setup();
        long now = DoseRecordTest.NOW;
        DoseRecord a = DoseRecordTest.record(DoseRecord.OPTICAL, 800, c, now - 60_000);
        a.jobId = "job-ok";
        DoseRecord b = DoseRecordTest.record(DoseRecord.OPTICAL, 800, c, now - 30_000);
        b.jobId = "job-wrong";
        List<DoseRecord> l = new ArrayList<>();
        l.add(a);
        l.add(b);
        DoseRecord.Epochs ep = DoseRecordTest.MACHINE;
        assertEquals(2, DoseRecords.fresh(l, c, ep, now, Collections.<String>emptySet()).size());
        List<DoseRecord> fresh = DoseRecords.fresh(l, c, ep, now, Collections.singleton("job-wrong"));
        assertEquals(1, fresh.size());
        assertEquals("job-ok", fresh.get(0).jobId);
    }
}
