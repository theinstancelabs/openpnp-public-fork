package org.openpnp.instancelabs.padprofile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Statistics of the calibration's dots (§7.4, §7.5), pure. Per dose, on every fresh record pooled
 * (failed ones included, so a repeat cannot erase a failure); the first 2 dots after a cross are
 * left out of repeat, phase and drift (they carry the switch over, PT/lessons.md:15).
 *
 * <ul>
 * <li>transfer: misses in N dots at most k95(N, 0.5 %);</li>
 * <li>threads: threads (a string counts as one) in H hops, H at least 60, at most k95(H, 3 %);</li>
 * <li>repeat: residual area CV after the phase harmonic, its upper bound cv (1 + 1.645 / sqrt(2N));</li>
 * <li>phase: half turns |m0 - m1| / (m0 + m1) + 1.645 SE; quarter turns the least-squares
 * first-harmonic amplitude + 1.645 SE; a partial dose not seen at every start angle fails (R4);</li>
 * <li>drift: OLS slope x (N - 1) / mean per record, failing above 10 % and 2 SE;</li>
 * <li>landing: |mean offset| and per-axis sd; size: mean d, CV_d, d95 = mean (1 + 1.65 CV_d).</li>
 * </ul>
 */
public final class LadderStats {
    private LadderStats() {
    }

    /** The calibration's thresholds (D/PadCalibrationSettings defaults). */
    public static final class Settings {
        public double maxResidCv = 0.13;
        public double maxHalfSplit = 0.13;
        public double maxQuarterAmplitude = 0.20;
        public double massTolLow = 0.25;
        public double massTolHigh = 0.10;
        public double confirmTolerance = 0.05;
        public double controlTolerance = 0.15;
        public int minHops = 60;
        public double threadRateOk = 0.03;
        public double missRateOk = 0.005;
    }

    /** One commanded dot as the camera saw it. */
    public static final class Dot {
        public boolean found;
        public double areaMm2;
        public double diameterMm;
        public double circularity = 1;
        /** Phase index k = floorMod(startCount - startAngleSteps, 1600) / s; null = 0. */
        public Integer phase;
        /** Landing: found - commanded. */
        public double dxMm;
        public double dyMm;
        public boolean threadToNext;
        public boolean string;

        public Dot() {
        }

        public Dot(double areaMm2, int phase) {
            this.found = true;
            this.areaMm2 = areaMm2;
            this.diameterMm = DoseModel.diameterOfArea(areaMm2);
            this.phase = phase;
        }
    }

    /** One block of one dose (a ladder block or a coupon's dots), in dispense order, after one cross. */
    public static final class Record {
        public long id;
        public int steps;
        /** optical | coupon */
        public String kind = "optical";
        public List<Dot> dots = new ArrayList<>();
        /** Moves between consecutive dots; null = dots - 1. */
        public Integer hops;
    }

    /** One check: its value, limit and plain words when it fails. */
    public static final class Check {
        public String key;
        public boolean ok;
        public Double value;
        public Double limit;
        public String text;

        Check(String key, boolean ok, Double value, Double limit, String text) {
            this.key = key;
            this.ok = ok;
            this.value = value;
            this.limit = limit;
            this.text = text;
        }
    }

    /** The pooled statistics of one dose. */
    public static final class Stats {
        public int steps;
        public int records;
        public int n;
        public int found;
        public int misses;
        public int missLimit;
        public int hops;
        public int threads;
        public int threadLimit;
        public int strings;
        /** Dots in repeat, phase and drift (found, round, not the first 2 after a cross). */
        public int used;
        public Double meanAreaMm2;
        public Double medianAreaMm2;
        public Double residCv;
        public Double residCvUpper;
        /** half | quarter | none */
        public String phaseKind;
        public Double phaseValue;
        public Double phaseSe;
        public Double phaseStat;
        public Double phaseLimit;
        /** The record with the largest |drift| (fraction) and its standard error. */
        public Double driftPct;
        public Double driftSe;
        public boolean driftFail;
        public Double landingMeanMm;
        public Double landingSdMm;
        /** The mean landing per axis, found - commanded (mm; tip setups design §22.3); its length is landingMeanMm. */
        public Double landingDxMm, landingDyMm;
        public Double dMeanMm;
        public Double dCv;
        public Double d95Mm;
        public List<Check> checks = new ArrayList<>();
        public boolean pass;
        public List<String> failures = new ArrayList<>();

        /** The optical part of {@link DoseEvidence} (the mass comes from {@link #mass}). */
        public DoseEvidence toEvidence() {
            DoseEvidence e = new DoseEvidence(steps);
            e.dMeanMm = dMeanMm;
            e.dCv = dCv;
            e.d95Mm = d95Mm;
            e.residCv = residCvUpper;
            e.phaseStat = phaseStat;
            e.phaseLimit = phaseLimit;
            e.driftPct = driftPct;
            e.misses = misses;
            e.hops = hops;
            e.threads = threads;
            e.landingMeanMm = landingMeanMm;
            e.landingSdMm = landingSdMm;
            e.n = n;
            e.opticalFresh = records > 0;
            e.opticalPass = pass;
            e.failures = new ArrayList<>(failures);
            return e;
        }
    }

    /** Phase index of a dot that starts at a Marlin count (§7.4). */
    public static int phaseOf(long startCount, int startAngleSteps, int steps) {
        return (int) (Math.floorMod(startCount - startAngleSteps, (long) DoseModel.STEPS_PER_REV) / steps);
    }

    /** Phases of a dose: 4 quarter, 2 half, 1 whole. */
    public static int phases(int steps) {
        int g = gcd(DoseModel.STEPS_PER_REV, steps);
        return DoseModel.STEPS_PER_REV / g;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /** Binomial P(X <= k), X ~ Bin(n, p). */
    public static double binomCdf(int k, int n, double p) {
        double sum = 0;
        double term = Math.pow(1 - p, n);
        for (int i = 0; i <= k && i <= n; i++) {
            sum += term;
            term = term * (n - i) / (i + 1) * p / (1 - p);
        }
        return Math.min(1, sum);
    }

    /** The smallest k with P(X <= k) >= 0.95: threads 60 -> 4, 90 -> 6, 180 -> 9 at 3 %; misses 60 -> 1, 180 -> 3 at 0.5 %. */
    public static int k95(int n, double p) {
        int k = 0;
        while (k < n && binomCdf(k, n, p) < 0.95) {
            k++;
        }
        return k;
    }

    /** Upper 95 % bound of a CV from N values: cv (1 + 1.645 / sqrt(2N)). */
    public static double cvUpper(double cv, int n) {
        return cv * (1 + Limits.Z95 / Math.sqrt(2.0 * n));
    }

    /** Half-turn split {|m0 - m1| / (m0 + m1), its delta-method SE}. */
    public static double[] halfSplit(double[] phase0, double[] phase1) {
        double m0 = mean(phase0);
        double m1 = mean(phase1);
        double se0 = sd(phase0) / Math.sqrt(phase0.length);
        double se1 = sd(phase1) / Math.sqrt(phase1.length);
        double s = m0 + m1;
        double stat = Math.abs(m0 - m1) / s;
        double se = 2 * Math.sqrt(m1 * m1 * se0 * se0 + m0 * m0 * se1 * se1) / (s * s);
        return new double[] {stat, se};
    }

    /**
     * Least-squares first harmonic of values against phase angles: y = v / mean - 1 = c cos + d sin.
     * Returns {amplitude, its SE (sd sqrt(2 / n)), residual sd (n - 3 degrees of freedom)}.
     */
    public static double[] harmonic(double[] phaseRad, double[] values) {
        int n = values.length;
        double m = mean(values);
        double cc = 0;
        double ss = 0;
        double cs = 0;
        double yc = 0;
        double ys = 0;
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            y[i] = values[i] / m - 1;
            double c = Math.cos(phaseRad[i]);
            double s = Math.sin(phaseRad[i]);
            cc += c * c;
            ss += s * s;
            cs += c * s;
            yc += y[i] * c;
            ys += y[i] * s;
        }
        double det = cc * ss - cs * cs;
        double c = (yc * ss - ys * cs) / det;
        double d = (ys * cc - yc * cs) / det;
        double rss = 0;
        for (int i = 0; i < n; i++) {
            double r = y[i] - c * Math.cos(phaseRad[i]) - d * Math.sin(phaseRad[i]);
            rss += r * r;
        }
        double sd = Math.sqrt(rss / Math.max(1, n - 3));
        return new double[] {Math.hypot(c, d), sd * Math.sqrt(2.0 / n), sd};
    }

    /** Drift over a record: {OLS slope x (N - 1) / mean, its SE} against the dots' indices. */
    public static double[] drift(double[] index, double[] values) {
        int n = values.length;
        double mx = mean(index);
        double my = mean(values);
        double sxx = 0;
        double sxy = 0;
        for (int i = 0; i < n; i++) {
            sxx += (index[i] - mx) * (index[i] - mx);
            sxy += (index[i] - mx) * (values[i] - my);
        }
        double b = sxy / sxx;
        double rss = 0;
        for (int i = 0; i < n; i++) {
            double r = values[i] - my - b * (index[i] - mx);
            rss += r * r;
        }
        double s = Math.sqrt(rss / Math.max(1, n - 2));
        double seb = s / Math.sqrt(sxx);
        double span = index[n - 1] - index[0];
        return new double[] {b * span / my, seb * span / my};
    }

    /** The pooled statistics and checks of one dose over its fresh records. */
    public static Stats of(int steps, List<Record> records, Settings set) {
        Settings s = set != null ? set : new Settings();
        Stats st = new Stats();
        st.steps = steps;
        st.records = records.size();
        String turn = DoseModel.turnName(steps);
        // strings: circularity below 0.35, or an area above 1.8 x the dose's median of round dots
        List<Double> roundAreas = new ArrayList<>();
        for (Record r : records) {
            for (Dot d : r.dots) {
                if (d.found && d.circularity >= Limits.STRING_CIRCULARITY) {
                    roundAreas.add(d.areaMm2);
                }
            }
        }
        double medianRound = median(roundAreas);
        List<Double> areas = new ArrayList<>();
        List<Integer> phase = new ArrayList<>();
        List<Double> diam = new ArrayList<>();
        List<Double> dx = new ArrayList<>();
        List<Double> dy = new ArrayList<>();
        for (Record r : records) {
            st.hops += r.hops != null ? r.hops : Math.max(0, r.dots.size() - 1);
            List<Double> recIdx = new ArrayList<>();
            List<Double> recVal = new ArrayList<>();
            for (int i = 0; i < r.dots.size(); i++) {
                Dot d = r.dots.get(i);
                st.n++;
                boolean string = d.string || d.found && (d.circularity < Limits.STRING_CIRCULARITY
                        || !Double.isNaN(medianRound) && d.areaMm2 > Limits.STRING_AREA_FACTOR * medianRound);
                if (string) {
                    st.strings++;
                }
                if (string || i < r.dots.size() - 1 && d.threadToNext) {
                    st.threads++;
                }
                if (!d.found) {
                    st.misses++;
                    continue;
                }
                st.found++;
                dx.add(d.dxMm);
                dy.add(d.dyMm);
                if (string) {
                    continue;
                }
                diam.add(d.diameterMm);
                if (i >= Limits.EXCLUDED_AFTER_CROSS) {
                    areas.add(d.areaMm2);
                    phase.add(d.phase == null ? 0 : d.phase);
                    recIdx.add((double) i);
                    recVal.add(d.areaMm2);
                }
            }
            if (recVal.size() >= 3) {
                double[] dr = drift(toArray(recIdx), toArray(recVal));
                boolean fail = Math.abs(dr[0]) > Limits.DRIFT_LIMIT && Math.abs(dr[0]) > Limits.DRIFT_SE * dr[1];
                // report the worst record: a failing one first, then the largest drift
                if (st.driftPct == null || fail && !st.driftFail
                        || fail == st.driftFail && Math.abs(dr[0]) > Math.abs(st.driftPct)) {
                    st.driftPct = dr[0];
                    st.driftSe = dr[1];
                }
                st.driftFail |= fail;
            }
        }
        st.used = areas.size();
        st.missLimit = k95(st.n, s.missRateOk);
        st.threadLimit = k95(st.hops, s.threadRateOk);
        add(st, "transfer", st.misses <= st.missLimit, (double) st.misses, (double) st.missLimit,
                st.misses + " of " + st.n + " " + turn + " dots were not found (at most " + st.missLimit + " allowed)");
        if (st.hops < s.minHops) {
            add(st, "threads", false, (double) st.hops, (double) s.minHops, "only " + st.hops + " moves between " + turn
                    + " dots were seen (" + s.minHops + " needed to count the paste threads)");
        }
        else {
            add(st, "threads", st.threads <= st.threadLimit, (double) st.threads, (double) st.threadLimit, turn
                    + " dots trailed a paste thread to the next dot " + st.threads + " times in " + st.hops
                    + " moves (at most " + st.threadLimit + " allowed)");
        }
        double[] a = toArray(areas);
        int phases = phases(steps);
        if (a.length >= 4) {
            st.meanAreaMm2 = mean(a);
            st.medianAreaMm2 = median(areas);
            double cv;
            // R4: a partial dose is calibrated at every start angle of its set (at least 2 dots each)
            double[][] split = byPhase(a, phase, phases);
            int angles = 0;
            for (double[] k : split) {
                angles += k.length >= 2 ? 1 : 0;
            }
            boolean everyAngle = angles == phases;
            if (phases == 2) {
                double rss = 0;
                double[] means = {mean(split[0]), mean(split[1])};
                for (int i = 0; i < a.length; i++) {
                    double r = a[i] - means[Math.floorMod(phase.get(i), 2)];
                    rss += r * r;
                }
                cv = Math.sqrt(rss / Math.max(1, a.length - 2)) / st.meanAreaMm2;
                if (everyAngle) {
                    double[] hs = halfSplit(split[0], split[1]);
                    st.phaseKind = "half";
                    st.phaseValue = hs[0];
                    st.phaseSe = hs[1];
                    st.phaseLimit = s.maxHalfSplit;
                }
            }
            else if (phases >= 3 && everyAngle) {
                double[] rad = new double[a.length];
                for (int i = 0; i < a.length; i++) {
                    rad[i] = 2 * Math.PI * phase.get(i) / phases;
                }
                double[] h = harmonic(rad, a);
                cv = h[2];
                st.phaseKind = "quarter";
                st.phaseValue = h[0];
                st.phaseSe = h[1];
                st.phaseLimit = s.maxQuarterAmplitude;
            }
            else {
                // a whole turn, or a partial dose not seen at every start angle (failed below): the plain CV
                cv = sd(a) / st.meanAreaMm2;
                st.phaseKind = phases == 1 ? "none" : null;
            }
            st.residCv = cv;
            st.residCvUpper = cvUpper(cv, a.length);
            add(st, "repeat", st.residCvUpper <= s.maxResidCv, st.residCvUpper, s.maxResidCv, "the " + turn
                    + " dots vary " + Texts.pct(st.residCvUpper) + " % in area beyond the screw-angle pattern (at most "
                    + Texts.pct(s.maxResidCv) + " % allowed)");
            if (st.phaseValue != null) {
                st.phaseStat = st.phaseValue + Limits.Z95 * st.phaseSe;
                boolean half = "half".equals(st.phaseKind);
                add(st, "phase", st.phaseStat <= st.phaseLimit, st.phaseStat, st.phaseLimit, half
                        ? "the two start angles of the half-turn dots differ " + Texts.pct(st.phaseStat) + " % in area (at most "
                                + Texts.pct(st.phaseLimit) + " % allowed)"
                        : "the " + turn + " dots swing " + Texts.pct(st.phaseStat) + " % in area with the screw angle (at most "
                                + Texts.pct(st.phaseLimit) + " % allowed)");
            }
            else if (phases > 1) {
                add(st, "phase", false, (double) angles, (double) phases, "the " + turn + " dots were measured at "
                        + angles + " of their " + phases + " start angles (at least 2 dots at each needed)");
            }
        }
        else {
            add(st, "repeat", false, null, s.maxResidCv, "only " + a.length + " " + turn + " dots could be measured");
        }
        if (st.driftPct != null) {
            add(st, "drift", !st.driftFail, st.driftPct, Limits.DRIFT_LIMIT, "the " + turn + " dots "
                    + (st.driftPct > 0 ? "grew " : "shrank ") + Texts.pct(st.driftPct) + " % over a run (at most "
                    + Texts.pct(Limits.DRIFT_LIMIT) + " % allowed): the screw may be starving");
        }
        if (dx.size() >= 2) {
            double mx = mean(toArray(dx));
            double my = mean(toArray(dy));
            st.landingMeanMm = Math.hypot(mx, my);
            st.landingDxMm = mx;
            st.landingDyMm = my;
            st.landingSdMm = Math.max(sd(toArray(dx)), sd(toArray(dy)));
            add(st, "landing", st.landingMeanMm <= Limits.LANDING_MEAN_MM + Limits.EPS, st.landingMeanMm,
                    Limits.LANDING_MEAN_MM, "the dots landed " + Texts.mm(st.landingMeanMm) + " mm off on average (at most "
                            + Texts.mm(Limits.LANDING_MEAN_MM) + " mm allowed): check the needle offset");
            add(st, "scatter", st.landingSdMm <= Limits.LANDING_SD_MM + Limits.EPS, st.landingSdMm, Limits.LANDING_SD_MM,
                    "the dots scattered " + Texts.mm(st.landingSdMm) + " mm about their spots (at most "
                            + Texts.mm(Limits.LANDING_SD_MM) + " mm allowed)");
        }
        if (diam.size() >= 2) {
            double[] d = toArray(diam);
            st.dMeanMm = mean(d);
            st.dCv = sd(d) / st.dMeanMm;
            st.d95Mm = st.dMeanMm * (1 + Limits.D95_Z * st.dCv);
        }
        st.pass = true;
        for (Check c : st.checks) {
            st.pass &= c.ok;
            if (!c.ok) {
                st.failures.add(c.text);
            }
        }
        return st;
    }

    private static void add(Stats st, String key, boolean ok, Double value, Double limit, String text) {
        st.checks.add(new Check(key, ok, value, limit, ok ? null : text));
    }

    private static double[][] byPhase(double[] a, List<Integer> phase, int phases) {
        List<List<Double>> l = new ArrayList<>();
        for (int k = 0; k < phases; k++) {
            l.add(new ArrayList<>());
        }
        for (int i = 0; i < a.length; i++) {
            l.get(Math.floorMod(phase.get(i), phases)).add(a[i]);
        }
        double[][] out = new double[phases][];
        for (int k = 0; k < phases; k++) {
            out[k] = toArray(l.get(k));
        }
        return out;
    }

    // ---- weighing (§7.5) ------------------------------------------------------------------------

    /** One coupon's readings: full minus wiped, over the dots the camera found on it. */
    public static final class MassReading {
        public long id;
        public int steps;
        public int dots;
        public double totalMg;

        public MassReading() {
        }

        public MassReading(long id, int steps, int dots, double totalMg) {
            this.id = id;
            this.steps = steps;
            this.dots = dots;
            this.totalMg = totalMg;
        }

        public double mgPerDot() {
            return totalMg / dots;
        }

        public double mgPerStep() {
            return totalMg / dots / steps;
        }
    }

    /** The weighed mass of a dose from every fresh reading (§7.5). */
    public static final class Mass {
        public int steps;
        public int readings;
        public Double mgPerDot;
        public Double mgPerStep;
        /** Two readings agree within the confirm tolerance in mass per step, one of them of this dose. */
        public boolean confirmed;
        public Long confirmedBy;

        /** Writes the mass into the dose's evidence. */
        public void applyTo(DoseEvidence e) {
            e.mgPerDot = mgPerDot;
            e.mgPerStep = mgPerStep;
            e.massReadings = readings;
            e.massConfirmed = confirmed;
        }
    }

    public static Mass mass(int steps, List<MassReading> readings, Settings set) {
        Settings s = set != null ? set : new Settings();
        Mass m = new Mass();
        m.steps = steps;
        double total = 0;
        int dots = 0;
        for (MassReading r : readings) {
            if (r.steps == steps && r.dots > 0) {
                m.readings++;
                total += r.totalMg;
                dots += r.dots;
            }
        }
        if (m.readings == 0) {
            return m;
        }
        m.mgPerDot = total / dots;
        m.mgPerStep = m.mgPerDot / steps;
        for (MassReading a : readings) {
            if (a.steps != steps || a.dots <= 0) {
                continue;
            }
            for (MassReading b : readings) {
                if (b == a || b.dots <= 0) {
                    continue;
                }
                if (Math.abs(a.mgPerStep() / b.mgPerStep() - 1) <= s.confirmTolerance + Limits.EPS) {
                    m.confirmed = true;
                    m.confirmedBy = b.id;
                    return m;
                }
            }
        }
        return m;
    }

    /** A reading is plausible at 0.5 to 2 times the expected mass of the found dots (§7.5). */
    public static boolean plausible(double totalMg, int foundDots, double predictedMgPerDot) {
        double expected = foundDots * predictedMgPerDot;
        return totalMg >= 0.5 * expected && totalMg <= 2 * expected;
    }

    // ---- the control (§7.4) -------------------------------------------------------------------

    /**
     * The profile dose laid first: its median blank area against the bench (half turn 1.057 mm²,
     * one turn 1.421 mm², ±controlTolerance) and against its newest fresh passing record (±10 %).
     */
    public static Check control(int steps, double medianAreaMm2, Double newestPassingMedianMm2, double psi,
            Settings set) {
        return control(steps, medianAreaMm2, newestPassingMedianMm2, psi, set, null);
    }

    /**
     * The control for the mounted tip setup (tip setups design §22.2): against the bench only with the bench model,
     * else against the setup's newest passing record of the dose ({@code newestPassingMedianMm2}, from its dot table,
     * ±10 %), or against nothing (it passes with the text "first measurement of this tip"). The failure text adds "or
     * another tip than the {S} is on" (§22.3). {@code tip} null: as {@link #control(int, double, Double, double,
     * Settings)}.
     */
    public static Check control(int steps, double medianAreaMm2, Double newestPassingMedianMm2, double psi,
            Settings set, Tip tip) {
        Settings s = set != null ? set : new Settings();
        String turn = DoseModel.turnName(steps);
        boolean bench0 = tip == null || tip.benchModel;
        Double bench = !bench0 ? null : steps == DoseModel.HALF ? Limits.BENCH_CONTROL_AREA_MM2
                : steps == DoseModel.ONE_TURN ? DoseModel.ONE_TURN_AREA_MM2 : null;
        String fix = ": check the regulator (" + Texts.num(psi, 0) + " psi) and the paste before calibrating"
                + (tip == null ? "" : ", or another tip than the " + tip.name + " is on");
        if (bench != null && Math.abs(medianAreaMm2 / bench - 1) > s.controlTolerance + Limits.EPS) {
            double e = medianAreaMm2 / bench - 1;
            return new Check("control", false, medianAreaMm2, bench, "the control " + turn + " dots are "
                    + Texts.mm(medianAreaMm2) + " mm², " + Texts.pct(e) + " % " + (e < 0 ? "below" : "above")
                    + " the bench's " + Texts.mm(bench) + " mm²" + fix);
        }
        if (newestPassingMedianMm2 != null
                && Math.abs(medianAreaMm2 / newestPassingMedianMm2 - 1) > Limits.CONTROL_RECORD_TOLERANCE + Limits.EPS) {
            double e = medianAreaMm2 / newestPassingMedianMm2 - 1;
            return new Check("control", false, medianAreaMm2, newestPassingMedianMm2, "the control " + turn
                    + " dots are " + Texts.mm(medianAreaMm2) + " mm², " + Texts.pct(e) + " % "
                    + (e < 0 ? "below" : "above") + " the " + Texts.mm(newestPassingMedianMm2)
                    + " mm² of the last calibration" + fix);
        }
        if (!bench0 && newestPassingMedianMm2 == null) {
            // §22.2: nothing to compare a setup's first dots of this dose with
            return new Check("control", true, medianAreaMm2, null, "first measurement of this tip");
        }
        return new Check("control", true, medianAreaMm2, bench, null);
    }

    // ---- small statistics -----------------------------------------------------------------------

    static double[] toArray(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return v.length == 0 ? Double.NaN : s / v.length;
    }

    /** Sample standard deviation (n - 1). */
    static double sd(double[] v) {
        if (v.length < 2) {
            return Double.NaN;
        }
        double m = mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return Math.sqrt(s / (v.length - 1));
    }

    static double median(List<Double> l) {
        if (l.isEmpty()) {
            return Double.NaN;
        }
        double[] a = toArray(l);
        Arrays.sort(a);
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2;
    }
}
