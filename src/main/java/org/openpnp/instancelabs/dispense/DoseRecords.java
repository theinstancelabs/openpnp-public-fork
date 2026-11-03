package org.openpnp.instancelabs.dispense;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.openpnp.instancelabs.padprofile.DoseEvidence;
import org.openpnp.instancelabs.padprofile.LadderStats;
import org.openpnp.instancelabs.padprofile.Limits;
import org.openpnp.instancelabs.padprofile.Tip;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * The dose records of the pad calibration and the evidence they make (profile calibration design §1.7,
 * §5.3, §7.4-7.6): a camera record per laid block with every dot it saw ({@code dots_json}), and the
 * pooled evidence of every fresh record of a dose. Pooling recomputes the statistics on the dots of
 * all fresh records, failed ones included, so a later pass cannot erase an earlier failure; a record
 * without dots (typed in, or made before dots were kept) adds its own verdict, when it has one. Weighings
 * are pooled the same way: a mass is confirmed by any two fresh readings that agree (§7.5).
 */
public final class DoseRecords {
    private static final Gson GSON = new Gson();
    private static final Type SEEN_LIST = new TypeToken<List<Seen>>() { }.getType();

    private DoseRecords() {
    }

    /** One commanded dot as the camera saw it ({@code dots_json}). */
    public static final class Seen {
        /** Commanded N2 machine X/Y. */
        public double x, y;
        public boolean found;
        /** Area (mm²), equivalent diameter (mm), circularity of the blob found. */
        public Double a, d, c;
        /** Landing: found - commanded (mm). */
        public Double dx, dy;
        /** Screw phase index (null: the start count was unknown). */
        public Integer k;
        /** A thread to the next dot; a string. */
        public boolean t, s;
    }

    /**
     * A tip setup's dot table (tip setups design §22.2): per dose, the newest passing optical record of the setup at
     * the recipe ({@code dose_fp}), the loaded paste and the simulator flag, outside jobs flagged wrong-tip, any age;
     * and the mass per step of its newest confirmed weighing at that recipe and paste, else its newest single one.
     * It describes the tip the way the bench model describes the 22G; freshness still decides characterized.
     */
    public static final class DotTable {
        public String setupKey;
        public String doseFp;
        public String pasteKey;
        public boolean simulated;
        /** Per dose (steps): the dot size measured and the record it came from. */
        public final Map<Integer, Tip.Measured> doses = new TreeMap<>();
        /** Weighed mass per motor step (mg) and when; null = never weighed at this recipe and paste. */
        public Double mgPerStep;
        public Long mgPerStepAtMs;
        /** Confirmed by two readings that agree (false: weighed once). */
        public boolean mgPerStepConfirmed;
    }

    /** The calibration's thresholds from the site settings. */
    public static LadderStats.Settings settings(PadCalibrationSettings p) {
        LadderStats.Settings s = new LadderStats.Settings();
        s.maxResidCv = p.maxResidCv;
        s.maxHalfSplit = p.maxHalfSplit;
        s.maxQuarterAmplitude = p.maxQuarterAmplitude;
        s.massTolLow = p.massTolLow;
        s.massTolHigh = p.massTolHigh;
        s.confirmTolerance = p.confirmTolerance;
        s.controlTolerance = p.controlTolerance;
        s.minHops = p.minHops;
        s.threadRateOk = p.threadRateOk;
        s.missRateOk = p.missRateOk;
        return s;
    }

    /** The dots of a laid block. */
    public static List<Seen> seen(DoseBlocksResult.BlockOut b) {
        List<Seen> l = new ArrayList<>();
        for (DoseBlocksResult.DotOut o : b.dots) {
            Seen s = new Seen();
            s.x = o.x;
            s.y = o.y;
            s.found = o.found != null;
            if (o.found != null) {
                s.a = o.found.areaMm2;
                s.d = o.found.diameterMm;
                s.c = o.found.circularity;
                s.dx = o.dxMm;
                s.dy = o.dyMm;
            }
            s.k = o.phase;
            s.t = o.threadToNext;
            s.s = o.string;
            l.add(s);
        }
        return l;
    }

    /** The statistics' view of a list of seen dots (one block, after one cross). */
    public static LadderStats.Record record(long id, int steps, String kind, List<Seen> dots) {
        LadderStats.Record r = new LadderStats.Record();
        r.id = id;
        r.steps = steps;
        r.kind = kind;
        for (Seen s : dots) {
            LadderStats.Dot d = new LadderStats.Dot();
            d.found = s.found;
            if (s.found) {
                d.areaMm2 = s.a == null ? 0 : s.a;
                d.diameterMm = s.d == null ? 0 : s.d;
                d.circularity = s.c == null ? 1 : s.c;
                d.dxMm = s.dx == null ? 0 : s.dx;
                d.dyMm = s.dy == null ? 0 : s.dy;
            }
            d.phase = s.k;
            d.threadToNext = s.t;
            d.string = s.s;
            r.dots.add(d);
        }
        return r;
    }

    /** The dots a record kept, or null when it kept none. */
    public static List<Seen> dotsOf(DoseRecord r) {
        if (r.dotsJson == null || r.dotsJson.trim().isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(r.dotsJson, SEEN_LIST);
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A camera record (optical: the ladder; coupon: weigh dots) of one block with its own statistics;
     * the conditions of §1.7 (pressure, recipe, tip, paste, attestations, epochs, method) are the
     * caller's.
     */
    public static DoseRecord fromBlock(DoseBlocksResult.BlockOut b, String kind, LadderStats.Settings set) {
        List<Seen> dots = seen(b);
        LadderStats.Stats st = LadderStats.of(b.netSteps, java.util.Collections.singletonList(record(0, b.netSteps,
                kind, dots)), set);
        DoseRecord r = new DoseRecord();
        r.kind = kind;
        r.steps = b.netSteps;
        r.n = b.dots.size();
        r.found = st.found;
        r.misses = st.misses;
        r.hops = st.hops;
        r.threads = st.threads;
        r.hopMm = b.hopMm;
        r.areaMeanMm2 = st.meanAreaMm2;
        r.residCv = st.residCvUpper;
        r.diameterMeanMm = st.dMeanMm;
        r.diameterCv = st.dCv;
        r.diameterP95Mm = st.d95Mm;
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("kind", st.phaseKind);
        phase.put("value", st.phaseValue);
        phase.put("se", st.phaseSe);
        phase.put("stat", st.phaseStat);
        phase.put("limit", st.phaseLimit);
        phase.put("medianAreaMm2", st.medianAreaMm2);
        r.phaseJson = GSON.toJson(phase);
        r.drift = st.driftPct;
        r.landingMeanMm = st.landingMeanMm;
        r.landingSdMm = st.landingSdMm;
        r.landingDxMm = st.landingDxMm;
        r.landingDyMm = st.landingDyMm;
        r.dotsJson = GSON.toJson(dots);
        r.pass = st.pass;
        r.why = st.failures.isEmpty() ? null : st.failures.get(0);
        r.couponPlace = b.couponPlace;
        return r;
    }

    /** The median blank area of a record's round dots after the first 2 (the control, §7.4), or null. */
    public static Double medianArea(DoseRecord r) {
        List<Seen> dots = dotsOf(r);
        if (dots == null) {
            return r.areaMeanMm2;
        }
        List<Double> a = new ArrayList<>();
        for (int i = Limits.EXCLUDED_AFTER_CROSS; i < dots.size(); i++) {
            Seen s = dots.get(i);
            if (s.found && s.a != null && !s.s && (s.c == null || s.c >= Limits.STRING_CIRCULARITY)) {
                a.add(s.a);
            }
        }
        if (a.isEmpty()) {
            return null;
        }
        a.sort(Double::compare);
        int n = a.size();
        return n % 2 == 1 ? a.get(n / 2) : (a.get(n / 2 - 1) + a.get(n / 2)) / 2;
    }

    /**
     * The setup's dot table (§22.2, see {@link DotTable}) from its records: another setup's records never enter it,
     * nor the records of a job in {@code flaggedJobs} (wrong tip). Each optical record counts alone.
     */
    public static DotTable dotTable(List<DoseRecord> records, String setupKey, String doseFp, String pasteKey,
            boolean simulated, Set<String> flaggedJobs) {
        return dotTable(records, setupKey, doseFp, pasteKey, simulated, flaggedJobs, null);
    }

    /**
     * As above; with {@code set}, an optical record that fails alone counts when its job's camera records of the
     * same dose pass together, as the job judged them (§7.4): a ladder block is sized for the hops of the weigh dots
     * planned on the coupons ({@link PadLadder#dotsFor}), so at the machine's 60 hops the weighed dose's block fails
     * its thread count alone, and Measure its dots would otherwise leave a tip whose smallest dot is weighed
     * unmeasured. Its sizes are then the pooled ones (what the job reported).
     */
    public static DotTable dotTable(List<DoseRecord> records, String setupKey, String doseFp, String pasteKey,
            boolean simulated, Set<String> flaggedJobs, LadderStats.Settings set) {
        DotTable t = new DotTable();
        t.setupKey = setupKey;
        t.doseFp = doseFp;
        t.pasteKey = pasteKey;
        t.simulated = simulated;
        Map<Long, DoseRecord> byId = new HashMap<>();
        List<DoseRecord> mine = new ArrayList<>();
        for (DoseRecord r : records) {
            byId.put(r.id, r);
            if (setupKey.equals(r.tipKey) && Objects.equals(doseFp, r.doseFp) && Objects.equals(pasteKey, r.pasteKey)
                    && r.simulated == simulated && !(r.jobId != null && flaggedJobs != null
                            && flaggedJobs.contains(r.jobId))) {
                mine.add(r);
            }
        }
        mine.sort((a, b) -> Long.compare(a.id, b.id));
        for (DoseRecord r : mine) {
            // the newest passing optical record per dose (records come oldest first: a later one replaces it)
            if (!DoseRecord.OPTICAL.equals(r.kind)) {
                continue;
            }
            Tip.Measured m = null;
            if (Boolean.TRUE.equals(r.pass) && r.diameterMeanMm != null && r.diameterP95Mm != null) {
                m = new Tip.Measured();
                m.dMeanMm = r.diameterMeanMm;
                m.d95Mm = r.diameterP95Mm;
                m.recordId = r.id;
                m.atMs = r.createdAtMs;
            }
            else if (set != null && r.jobId != null) {
                m = pooledWithJob(r, mine, set);
            }
            if (m != null) {
                t.doses.put(r.steps, m);
            }
        }
        // the newest confirmed weighing (a reading another agrees with, §7.5), else the newest single one
        java.util.Set<Long> confirmed = new java.util.HashSet<>();
        for (DoseRecord r : mine) {
            if (r.hasReading() && r.confirmedBy != null && byId.containsKey(r.confirmedBy)
                    && mine.contains(byId.get(r.confirmedBy))) {
                confirmed.add(r.id);
                confirmed.add(r.confirmedBy);
            }
        }
        DoseRecord single = null, best = null;
        for (DoseRecord r : mine) {
            if (!r.hasReading() || mgPerStepOf(r, byId) == null) {
                continue;
            }
            single = r;
            if (confirmed.contains(r.id)) {
                best = r;
            }
        }
        DoseRecord w = best != null ? best : single;
        if (w != null) {
            t.mgPerStep = mgPerStepOf(w, byId);
            t.mgPerStepAtMs = w.createdAtMs;
            t.mgPerStepConfirmed = best != null;
        }
        return t;
    }

    /**
     * An optical record pooled with its job's other camera records of its dose (the coupons' imaged weigh dots, another
     * block): their sizes when they pass together, else null (nothing to pool, or the pool fails too).
     */
    private static Tip.Measured pooledWithJob(DoseRecord r, List<DoseRecord> mine, LadderStats.Settings set) {
        List<LadderStats.Record> pool = new ArrayList<>();
        long at = r.createdAtMs;
        for (DoseRecord x : mine) {
            if (x.steps != r.steps || !r.jobId.equals(x.jobId)
                    || !(DoseRecord.OPTICAL.equals(x.kind) || DoseRecord.COUPON.equals(x.kind))) {
                continue;
            }
            List<Seen> dots = dotsOf(x);
            if (dots != null && !dots.isEmpty()) {
                pool.add(record(x.id, x.steps, x.kind, dots));
                at = Math.max(at, x.createdAtMs);
            }
        }
        if (pool.size() < 2) {
            return null;
        }
        LadderStats.Stats st = LadderStats.of(r.steps, pool, set);
        if (!st.pass || st.dMeanMm == null || st.d95Mm == null) {
            return null;
        }
        Tip.Measured m = new Tip.Measured();
        m.dMeanMm = st.dMeanMm;
        m.d95Mm = st.d95Mm;
        m.recordId = r.id;
        m.atMs = at;
        return m;
    }

    /** A reading's mass per motor step: as stored, else its total over the dots laid on its coupon (§7.5). */
    static Double mgPerStepOf(DoseRecord r, Map<Long, DoseRecord> byId) {
        if (r.mgPerStep != null) {
            return r.mgPerStep;
        }
        DoseRecord coupon = r.couponRecordId == null ? null : byId.get(r.couponRecordId);
        int dots = coupon != null ? coupon.n : r.n;
        return r.totalMg == null || dots <= 0 || r.steps <= 0 ? null : r.totalMg / dots / r.steps;
    }

    /**
     * The records fresh for the current setup (§1.7), oldest first, without the records of the jobs flagged wrong-tip
     * (tip setups design §22.3): they leave the setup's evidence.
     */
    public static List<DoseRecord> fresh(List<DoseRecord> records, DispenseConfig cfg, DoseRecord.Epochs epochs,
            long nowMs, Set<String> flaggedJobs) {
        List<DoseRecord> l = new ArrayList<>();
        for (DoseRecord r : records) {
            if (r.isFresh(nowMs, cfg, epochs) && !(r.jobId != null && flaggedJobs != null
                    && flaggedJobs.contains(r.jobId))) {
                l.add(r);
            }
        }
        return l;
    }

    /**
     * The weighed readings among fresh records, with the dots laid on their coupons (§7.5: mass per dot is the
     * total over the dots laid, not over those the camera found).
     */
    public static List<LadderStats.MassReading> readings(List<DoseRecord> fresh, List<DoseRecord> all) {
        Map<Long, DoseRecord> byId = new HashMap<>();
        for (DoseRecord r : all) {
            byId.put(r.id, r);
        }
        List<LadderStats.MassReading> l = new ArrayList<>();
        for (DoseRecord r : fresh) {
            if (!r.hasReading()) {
                continue;
            }
            DoseRecord coupon = r.couponRecordId == null ? null : byId.get(r.couponRecordId);
            int dots = coupon != null ? coupon.n : r.n;
            if (dots > 0) {
                l.add(new LadderStats.MassReading(r.id, r.steps, dots, r.totalMg));
            }
        }
        return l;
    }

    /**
     * The pooled evidence of every dose from the fresh records (§1.7, §7.6): camera records (ladder and
     * coupon) pooled through {@link LadderStats#of}, a record without dots by its own verdict, and the
     * weighed mass of every fresh reading ({@link LadderStats#mass}); the records of the jobs flagged wrong-tip
     * left out (tip setups design §22.3).
     */
    public static Map<Integer, DoseEvidence> evidence(List<DoseRecord> records, DispenseConfig cfg,
            DoseRecord.Epochs epochs, long nowMs, Set<String> flaggedJobs) {
        return pooled(records, fresh(records, cfg, epochs, nowMs, flaggedJobs), cfg);
    }

    /** The evidence pooled from the fresh records ({@code all}: every record, for the coupons of the readings). */
    private static Map<Integer, DoseEvidence> pooled(List<DoseRecord> all, List<DoseRecord> fresh, DispenseConfig cfg) {
        List<DoseRecord> records = all;
        LadderStats.Settings set = settings(cfg.padCalibration);
        Map<Integer, List<LadderStats.Record>> pooled = new TreeMap<>();
        Map<Integer, List<DoseRecord>> verdicts = new TreeMap<>();
        for (DoseRecord r : fresh) {
            if (DoseRecord.MASS.equals(r.kind)) {
                continue;
            }
            List<Seen> dots = dotsOf(r);
            if (dots != null && !dots.isEmpty()) {
                pooled.computeIfAbsent(r.steps, k -> new ArrayList<>()).add(record(r.id, r.steps, r.kind, dots));
            }
            else if (r.pass != null) {
                verdicts.computeIfAbsent(r.steps, k -> new ArrayList<>()).add(r);
            }
            // a record with neither dots nor a verdict (a coupon weighed without the camera) says nothing optical
        }
        Map<Integer, DoseEvidence> m = new TreeMap<>();
        for (Map.Entry<Integer, List<LadderStats.Record>> e : pooled.entrySet()) {
            DoseEvidence ev = LadderStats.of(e.getKey(), e.getValue(), set).toEvidence();
            ev.opticalFresh = true;
            m.put(e.getKey(), ev);
        }
        for (Map.Entry<Integer, List<DoseRecord>> e : verdicts.entrySet()) {
            DoseEvidence ev = m.get(e.getKey());
            boolean hasDots = ev != null;
            boolean pass = !hasDots || ev.opticalPass;
            if (!hasDots) {
                ev = new DoseEvidence(e.getKey());
                m.put(e.getKey(), ev);
            }
            for (DoseRecord r : e.getValue()) {
                pass &= Boolean.TRUE.equals(r.pass);
                if (!Boolean.TRUE.equals(r.pass) && r.why != null) {
                    ev.failures.add(r.why);
                }
                ev.n += r.n;
                ev.misses += r.misses == null ? 0 : r.misses;
                ev.hops += r.hops == null ? 0 : r.hops;
                ev.threads += r.threads == null ? 0 : r.threads;
                if (!hasDots) {
                    // no pooled dots: the newest record's own size (records come oldest first)
                    ev.dMeanMm = r.diameterMeanMm;
                    ev.dCv = r.diameterCv;
                    ev.d95Mm = r.diameterP95Mm;
                    ev.residCv = r.residCv;
                    ev.driftPct = r.drift;
                    ev.landingMeanMm = r.landingMeanMm;
                    ev.landingSdMm = r.landingSdMm;
                }
            }
            ev.opticalFresh = true;
            ev.opticalPass = pass;
        }
        List<LadderStats.MassReading> readings = readings(fresh, records);
        java.util.Set<Integer> weighed = new java.util.TreeSet<>();
        for (LadderStats.MassReading r : readings) {
            weighed.add(r.steps);
        }
        for (int steps : weighed) {
            LadderStats.Mass mass = LadderStats.mass(steps, readings, set);
            mass.applyTo(m.computeIfAbsent(steps, DoseEvidence::new));
        }
        return m;
    }

    /** The ids of the fresh records of a dose (camera and mass): the evidence a decision pooled. */
    public static List<Long> ids(List<DoseRecord> fresh, int steps) {
        List<Long> l = new ArrayList<>();
        for (DoseRecord r : fresh) {
            if (r.steps == steps) {
                l.add(r.id);
            }
        }
        return l;
    }
}
