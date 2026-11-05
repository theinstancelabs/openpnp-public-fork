package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * BR-77: the fits of the start-angle measure (plan §4), pure. Count angles in degrees, mod 360; a dot's angle is the one
 * its own Marlin count started at, which must be the start the plan put it at.
 *
 * <ul>
 * <li><b>Halves</b> (the 22G, roles/process.md "Screw timing"): two rows of half-turn pairs, the second 90° after the
 * first. Per row x = (A − B)/(A + B) of the trimmed means (min and max dropped) of the found dots at its start and half
 * a turn on (a half turn always lays paste; a start-up or carried blob at 2-3× comes one at a time and is trimmed; a
 * second in one half makes the angle low when still above 1.5× the half's median, OUTLIER_SHARE); x = −(2a/π)·sin(start
 * − peak) gives the flow's peak and its amplitude a.</li>
 * <li><b>Quarters</b> (the 25G at a threshold): 4 quarter dots per turn, a dot not found laid nothing; the dead quarter's
 * start by the first harmonic of the 4 means (the 90° grid alone misses ±22.5°), the grid's own minimum kept beside
 * it.</li>
 * </ul>
 *
 * <p>Two dots a thread or one blob joins are left out (their areas are not their own), and named.</p>
 */
public final class ScrewPhaseFit {
    public static final String HIGH = "high", LOW = "low", NONE = "none";
    /** Halves: the peak's standard error (deg) for high, for low; a ripple below 3 standard errors of x is none. */
    static final double HIGH_SE_DEG = 10, LOW_SE_DEG = 20, RIPPLE_SE = 3;
    /**
     * Halves (Process 11-04 20:5x): the smallest ripple that needs a start angle. A ripple a moves a half-turn dose by
     * up to (2/π)·a, so with a ±10 % dose tolerance any a ≥ 0.15 does. A fit below 3 errors hides up to (π/2)·3·maxSe.
     */
    static final double ANGLE_RIPPLE = 0.15;
    /**
     * Halves (Process 11-04 15:0x): a dot this many times its half's median left after the trim is a second outlier in
     * that half, and the angle is low (normal scatter is 10-15 % cv, the blobs 1.7-2.9×). It misses some (Monte Carlo,
     * cv 0.10-0.15): a 1.7× second blob in 23-36 % of 8-dot halves, 36-49 % at 5; it cannot fire with 3 dots, and
     * with 4 only for a second blob above 3× the other dot; normal scatter rarely fires it (≤ 0.2 %). Kept at 1.5×
     * (Process 11-04 15:2x: 1.4× would give 2-8 % false low per measure).
     */
    static final double OUTLIER_SHARE = 1.5;
    static final int MIN_FOUND_PER_HALF = 3;
    /** Halves: the fewest pairs a measure lays; a half left with fewer dots is too few to see a ripple. */
    public static final int MIN_PAIRS = 6;
    /**
     * Halves: a half's error × this by its dots (3-16, the last above) so 1.96 errors hold a row's x 95 % of the time
     * (Monte Carlo of the formula on normal scatter, seed 20261104 + n, 2·10⁶ rows per count; the bias alone is 1.59
     * at 4). Fitted at cv 0.12, no ripple, both halves at one count; unequal halves (dots missed or joined) hold it
     * 91-95 % of the time. The program: src/test/resources/br77/ScrewPhaseFitSeFactorMc.java.txt.
     */
    static final double[] SE_FACTOR = {0, 0, 0, 0.51, 3.19, 1.66, 1.36, 1.24, 1.18, 1.14, 1.12, 1.10, 1.08, 1.07, 1.07,
            1.06, 1.05};
    /** Quarters (Process 11-04): dead at or below 0.2 of the largest mean, a dip at or below 0.5; a live cv above 0.15. */
    static final double DEAD_SHARE = 0.2, DIP_SHARE = 0.5, LIVE_CV_MAX = 0.15;
    static final int MIN_DOTS_PER_ANGLE = 2;
    /** A dot's count angle this far (deg, about 4 steps) from its planned start: the count does not match the plan. */
    static final double SLOT_DEG = 1;

    private ScrewPhaseFit() {
    }

    /** A measured dot: the count angle it started at (deg) and its area (mm², 0 when not found). */
    public static final class Dot {
        public final double startDeg, areaMm2;
        /** Found in its after frame; joined to a neighbour by a thread or one blob (its area is not its own). */
        public final boolean found, joined;

        public Dot(double startDeg, double areaMm2) {
            this(startDeg, areaMm2, areaMm2 > 0, false);
        }

        public Dot(double startDeg, double areaMm2, boolean found, boolean joined) {
            this.startDeg = startDeg;
            this.areaMm2 = areaMm2;
            this.found = found;
            this.joined = joined;
        }
    }

    /** One row of half-turn pairs: its start, x and x's standard error. */
    public static final class Row {
        public double startDeg, x, xSe;
    }

    /** What a fit found: its confidence (high | low | none) and why it is not high. */
    public abstract static class Fit {
        public String confidence = NONE;
        public final List<String> why = new ArrayList<>();

        /** The feature an apply keeps the recipe's offset from, or null when nothing could be fitted (none). */
        public abstract Double featureDeg();
    }

    public static final class Halves extends Fit {
        public final List<Row> rows = new ArrayList<>();
        public Double peakDeg, a, peakSeDeg, balancedStartDeg;

        /** The flow's peak. */
        @Override
        public Double featureDeg() {
            return NONE.equals(confidence) ? null : peakDeg;
        }
    }

    public static final class Quarters extends Fit {
        /** The start of one more grid when the dead region straddles two quarters (Process 11-04), else null. */
        public Double deadQuarterStartDeg, gridDeadStartDeg, regridStartDeg;
        /** The means of the three quarters but the grid's dead one, by start angle. */
        public final Map<Double, Double> liveQuarterAreas = new TreeMap<>();
        /** Each angle's cv (sample SD / mean); null for a dead quarter. Never pooled over the live quarters. */
        public final Map<Double, Double> cvPerAngle = new TreeMap<>();

        /** The dead quarter's start, interpolated (not the grid's). */
        @Override
        public Double featureDeg() {
            return NONE.equals(confidence) ? null : deadQuarterStartDeg;
        }
    }

    /** Two rows of half-turn pairs as planned: row 1 from s1, row 2 from s2 = s1 + 90. */
    public static Halves halves(List<Dot> row1, double s1, List<Dot> row2, double s2) {
        Halves h = new Halves();
        List<String> notes = new ArrayList<>();
        String outlier = null;
        double maxSe = 0, fewestAt = 0;
        int fewest = Integer.MAX_VALUE;
        double[] starts = {norm(s1), norm(s2)};
        List<List<Dot>> rows = java.util.Arrays.asList(row1, row2);
        for (int k = 0; k < 2; k++) {
            Row r = new Row();
            r.startDeg = starts[k];
            List<List<Double>> half = groups(h, rows.get(k), r.startDeg, 180, 2, true, notes);
            if (half == null) {
                return h;
            }
            for (int j = 0; j < 2 && h.why.isEmpty(); j++) {
                if (half.get(j).size() < MIN_FOUND_PER_HALF) {
                    h.why.add("only " + dots(half.get(j).size()) + " to use at " + deg(r.startDeg + 180 * j)
                            + ": at least " + MIN_FOUND_PER_HALF + " per half");
                }
            }
            if (!h.why.isEmpty()) {
                h.why.addAll(notes);
                return h;
            }
            for (int j = 0; j < 2; j++) {
                List<Double> t = trimmed(half.get(j));
                double m = median(half.get(j)), top = java.util.Collections.max(t);
                if (top > OUTLIER_SHARE * m) {
                    outlier = "two outliers in one half at " + deg(r.startDeg + 180 * j) + ": a dot at "
                            + DispenseProfile.plain(round1(top / m)) + "× the half's median is left after the trim";
                    notes.add(outlier);
                }
                if (half.get(j).size() < fewest) {
                    fewest = half.get(j).size();
                    fewestAt = r.startDeg + 180 * j;
                }
            }
            double a = mean(trimmed(half.get(0))), b = mean(trimmed(half.get(1)));
            r.x = (a - b) / (a + b);
            r.xSe = Math.hypot(trimmedSe(half.get(0)), trimmedSe(half.get(1))) / (a + b);
            maxSe = Math.max(maxSe, r.xSe);
            h.rows.add(r);
        }
        Row r1 = h.rows.get(0), r2 = h.rows.get(1);
        double x1 = r1.x, x2 = r2.x, k = Math.hypot(x1, x2);
        h.peakDeg = norm(r1.startDeg - Math.toDegrees(Math.atan2(-x1, -x2)));
        h.a = Math.PI / 2 * k;
        h.balancedStartDeg = norm(h.peakDeg + 180);
        // the error of atan2(−x1, −x2) by x1 and x2
        h.peakSeDeg = k == 0 ? null : Math.toDegrees(Math.hypot(x2 * r1.xSe, x1 * r2.xSe) / (k * k));
        if (k == 0 || k < RIPPLE_SE * maxSe) {
            // too few dots in a half, or a second blob, widen the error until a real ripple hides in it
            if (fewest < MIN_PAIRS) {
                h.why.add("too few dots per half to see a ripple: " + dots(fewest) + " at " + deg(fewestAt));
            }
            else if (outlier != null) {
                notes.remove(outlier);
                h.why.add("no ripple can be told from these dots: " + outlier);
            }
            else if (Math.PI / 2 * RIPPLE_SE * maxSe > ANGLE_RIPPLE) {
                h.why.add("too few dots to rule out a ripple that needs a start angle");
            }
            else {
                h.why.add(String.format(Locale.ROOT, "no ripple above ≈ %.2f: a whole-turn recipe needs no start "
                        + "angle", Math.PI / 2 * RIPPLE_SE * maxSe));
            }
        }
        else if (h.peakSeDeg > LOW_SE_DEG) {
            h.why.add(String.format(Locale.ROOT, "the peak is uncertain: its standard error is %s° (at most %s° to set "
                    + "it)", DispenseProfile.plain(round1(h.peakSeDeg)), DispenseProfile.plain(LOW_SE_DEG)));
        }
        else {
            h.confidence = h.peakSeDeg <= HIGH_SE_DEG && notes.isEmpty() ? HIGH : LOW;
            if (h.peakSeDeg > HIGH_SE_DEG) {
                h.why.add(String.format(Locale.ROOT, "the peak's standard error is %s° (high at most %s°)",
                        DispenseProfile.plain(round1(h.peakSeDeg)), DispenseProfile.plain(HIGH_SE_DEG)));
            }
        }
        h.why.addAll(notes);
        return h;
    }

    /**
     * Quarter dots of whole turns from p as planned. {@code deadQuarterStored}: the stored start angle was set from a
     * dead quarter (no dead quarter now says the pressure changed).
     */
    public static Quarters quarters(List<Dot> dots, double p, boolean deadQuarterStored) {
        Quarters q = new Quarters();
        List<String> notes = new ArrayList<>();
        p = norm(p);
        List<List<Double>> g = groups(q, dots, p, 90, 4, false, notes);
        if (g == null) {
            return q;
        }
        double[] m = new double[4], s = new double[4];
        for (int j = 0; j < 4; j++) {
            s[j] = norm(p + 90 * j);
            int n = g.get(j).size();
            if (n < MIN_DOTS_PER_ANGLE) {
                q.why.add("only " + n + " measured " + (n == 1 ? "dot" : "dots") + " at " + deg(s[j]) + ": at least "
                        + MIN_DOTS_PER_ANGLE + " per angle");
                return q;
            }
            m[j] = mean(g.get(j));
        }
        int min = 0;
        double max = 0;
        for (int j = 0; j < 4; j++) {
            min = m[j] < m[min] ? j : min;
            max = Math.max(max, m[j]);
        }
        if (max <= 0) {
            q.why.add("no dot was found: did paste come out?");
            return q;
        }
        q.gridDeadStartDeg = s[min];
        // the first harmonic of the means peaks at θmax; the dead quarter starts half a turn after it
        q.deadQuarterStartDeg = norm(p + Math.toDegrees(Math.atan2(m[1] - m[3], m[0] - m[2])) + 180);
        boolean[] dip = new boolean[4], dead = new boolean[4];
        List<Integer> dips = new ArrayList<>();
        for (int j = 0; j < 4; j++) {
            dead[j] = m[j] <= DEAD_SHARE * max;
            dip[j] = m[j] <= DIP_SHARE * max;
            if (dip[j]) {
                dips.add(j);
            }
            q.cvPerAngle.put(s[j], dead[j] ? null : Math.sqrt(var(g.get(j))) / m[j]);
            if (j != min) {
                q.liveQuarterAreas.put(s[j], m[j]);
            }
        }
        if (dips.isEmpty()) {
            q.why.add((deadQuarterStored ? "the dead quarter is gone: check the regulator (" : "no dead quarter: ")
                    + "every quarter lays more than half the largest" + (deadQuarterStored ? ")" : ""));
            return q;
        }
        for (int j = 0; j < 4; j++) {
            if (dead[j] && dead[(j + 1) % 4]) {
                q.why.add("a dead half: run the halves measure");
                return q;
            }
        }
        for (int j = 0; j < 2; j++) {
            if (dip[j] && dip[j + 2]) {
                q.why.add("two dips half a turn apart (" + deg(s[j]) + " and " + deg(s[j + 2]) + ")");
                return q;
            }
        }
        if (dips.size() == 2) {
            int a = dips.get(0), b = dips.get(1);
            int first = (a + 1) % 4 == b ? a : b; // in turn order
            q.regridStartDeg = q.deadQuarterStartDeg;
            q.why.add("the dead region straddles two quarters (" + deg(s[first]) + " and " + deg(s[(first + 1) % 4])
                    + "): measure again from startDeg " + DispenseProfile.plain(round1(q.regridStartDeg) % 360) + " to "
                    + "put a grid start on it");
        }
        else if (!dead[min]) {
            q.why.add("the dip at " + deg(s[min]) + " is only partly dead");
        }
        for (int j = 0; j < 4; j++) {
            Double cv = q.cvPerAngle.get(s[j]);
            if (!dip[j] && cv != null && cv > LIVE_CV_MAX) {
                q.why.add(String.format(Locale.ROOT, "the live quarter at %s spreads (cv %.2f, at most %.2f)", deg(s[j]),
                        cv, LIVE_CV_MAX));
            }
        }
        q.why.addAll(notes);
        q.confidence = q.why.isEmpty() ? HIGH : LOW;
        return q;
    }

    /**
     * The areas of the dots by the nearest of {@code n} planned starts {@code step} apart from {@code p} (found only when
     * {@code foundOnly}, else a dot not found counts 0); the joined and, with foundOnly, the missed dots named in
     * {@code notes}. Null, with the fit's reason, when an area is no number or a dot is off its planned start.
     */
    private static List<List<Double>> groups(Fit f, List<Dot> dots, double p, double step, int n, boolean foundOnly,
            List<String> notes) {
        List<List<Double>> g = new ArrayList<>();
        int[] missed = new int[n], joined = new int[n];
        for (int j = 0; j < n; j++) {
            g.add(new ArrayList<>());
        }
        for (Dot d : dots) {
            int j = (int) Math.floorMod(Math.round(norm(d.startDeg - p) / step), (long) n);
            if (Math.abs(norm(d.startDeg - p - j * step + 180) - 180) > SLOT_DEG) {
                List<String> planned = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    planned.add(deg(p + i * step));
                }
                String last = planned.remove(n - 1);
                f.why.add("a dot started at " + deg(d.startDeg) + ", not at " + String.join(", ", planned) + " or " + last
                        + " as planned: the screw's count does not match the plan");
                return null;
            }
            if (!Double.isFinite(d.areaMm2)) {
                f.why.add("the area of a dot at " + deg(d.startDeg) + " is not a number: the camera did not measure it");
                return null;
            }
            if (d.joined) {
                joined[j]++;
            }
            else if (!d.found && foundOnly) {
                missed[j]++;
            }
            else {
                g.get(j).add(d.found ? d.areaMm2 : 0);
            }
        }
        for (int j = 0; j < n; j++) {
            if (missed[j] > 0) {
                notes.add(dots(missed[j]) + " not found at " + deg(p + j * step) + " (a half turn always lays paste): left "
                        + "out");
            }
        }
        for (int j = 0; j < n; j++) {
            if (joined[j] > 0) {
                notes.add(dots(joined[j]) + " joined to a neighbour (a thread or one blob) at " + deg(p + j * step)
                        + ": left out");
            }
        }
        return g;
    }

    private static String dots(int n) {
        return n + (n == 1 ? " dot" : " dots");
    }

    private static double median(List<Double> l) {
        List<Double> s = new ArrayList<>(l);
        java.util.Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }

    /** The values without one min and one max (at least 3 values). */
    private static List<Double> trimmed(List<Double> l) {
        List<Double> s = new ArrayList<>(l);
        java.util.Collections.sort(s);
        return s.subList(1, s.size() - 1);
    }

    /**
     * The trimmed mean's standard error (Tukey-McLaughlin): the winsorized SD (min and max replaced by their
     * neighbours: a blob does not widen it)·√n/(n − 2); the sample SD in its place when winsorizing leaves no spread
     * (3 dots, or ties); times the small-sample factor.
     */
    private static double trimmedSe(List<Double> l) {
        int n = l.size();
        List<Double> w = new ArrayList<>(l);
        java.util.Collections.sort(w);
        w.set(0, w.get(1));
        w.set(n - 1, w.get(n - 2));
        // the ends, not the SD, say "no spread": three copies of 0.7 do not average to 0.7 exactly
        double sd = Math.sqrt(var(w.get(0) < w.get(n - 1) ? w : l));
        return sd * Math.sqrt(n) / (n - 2) * SE_FACTOR[Math.min(n, SE_FACTOR.length - 1)];
    }

    private static double mean(List<Double> l) {
        double s = 0;
        for (double v : l) {
            s += v;
        }
        return l.isEmpty() ? 0 : s / l.size();
    }

    /** The sample variance (n − 1), 0 for fewer than 2 values. */
    private static double var(List<Double> l) {
        if (l.size() < 2) {
            return 0;
        }
        double m = mean(l), s = 0;
        for (double v : l) {
            s += (v - m) * (v - m);
        }
        return s / (l.size() - 1);
    }

    /** An angle in [0, 360). */
    public static double norm(double deg) {
        double d = deg % 360;
        if (d < 0) {
            d += 360;
        }
        return d >= 360 ? 0 : d;
    }

    private static double round1(double x) {
        return Math.round(x * 10) / 10.0;
    }

    /** "46°", "297.9°". */
    private static String deg(double x) {
        return DispenseProfile.plain(round1(norm(x)) % 360) + "°";
    }
}
