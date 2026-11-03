package org.openpnp.instancelabs.dispense;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.openpnp.instancelabs.padprofile.DoseEvidence;
import org.openpnp.instancelabs.padprofile.LadderStats;
import org.openpnp.instancelabs.padprofile.Limits;

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

    /** The records fresh for the current setup (§1.7), oldest first. */
    public static List<DoseRecord> fresh(List<DoseRecord> records, DispenseConfig cfg, DoseRecord.Epochs epochs,
            long nowMs) {
        List<DoseRecord> l = new ArrayList<>();
        for (DoseRecord r : records) {
            if (r.isFresh(nowMs, cfg, epochs)) {
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
     * weighed mass of every fresh reading ({@link LadderStats#mass}).
     */
    public static Map<Integer, DoseEvidence> evidence(List<DoseRecord> records, DispenseConfig cfg,
            DoseRecord.Epochs epochs, long nowMs) {
        List<DoseRecord> fresh = fresh(records, cfg, epochs, nowMs);
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
