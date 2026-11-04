package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * BR-77: the fits of the start-angle measure (plan §4), pure. Count angles in degrees, mod 360; a dot's angle is the one
 * its own Marlin count started at, its area 0 when the camera found nothing in its after frame.
 *
 * <ul>
 * <li><b>Halves</b> (the 22G, roles/process.md "Screw timing"): two rows of half-turn pairs, the second 90° after the
 * first. Per row x = (A − B)/(A + B) of the dots at its start and half a turn on; x = −(2a/π)·sin(start − peak) gives
 * the flow's peak and its amplitude a.</li>
 * <li><b>Quarters</b> (the 25G at a threshold): 4 quarter dots per turn; the dead quarter's start by the first harmonic
 * of the 4 means (the 90° grid alone misses ±22.5°), the grid's own minimum kept beside it.</li>
 * </ul>
 */
public final class ScrewPhaseFit {
    public static final String HIGH = "high", LOW = "low", NONE = "none";
    /** Halves: the peak's standard error (deg) for high, for low; a ripple below 3 standard errors of x is none. */
    static final double HIGH_SE_DEG = 10, LOW_SE_DEG = 20, RIPPLE_SE = 3;
    static final int MIN_FOUND_PER_HALF = 3;
    /** Quarters (Process 11-04): dead at or below 0.2 of the largest mean, a dip at or below 0.5; a live cv above 0.15. */
    static final double DEAD_SHARE = 0.2, DIP_SHARE = 0.5, LIVE_CV_MAX = 0.15;
    static final int MIN_DOTS_PER_ANGLE = 2;

    private ScrewPhaseFit() {
    }

    /** A measured dot: the count angle it started at (deg) and its area (mm², 0 when not found). */
    public static final class Dot {
        public final double startDeg, areaMm2;

        public Dot(double startDeg, double areaMm2) {
            this.startDeg = startDeg;
            this.areaMm2 = areaMm2;
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
        public Double deadQuarterStartDeg, gridDeadStartDeg;
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

    /** Two rows of half-turn pairs, row 2 starting 90° after row 1. */
    public static Halves halves(List<Dot> row1, List<Dot> row2) {
        Halves h = new Halves();
        double maxSe = 0;
        for (List<Dot> dots : java.util.Arrays.asList(row1, row2)) {
            Row r = new Row();
            r.startDeg = norm(dots.get(0).startDeg);
            List<List<Double>> half = groups(dots, r.startDeg, 180, 2);
            for (int j = 0; j < 2; j++) {
                int found = 0;
                for (double v : half.get(j)) {
                    found += v > 0 ? 1 : 0;
                }
                if (found < MIN_FOUND_PER_HALF && h.why.isEmpty()) {
                    h.why.add("only " + found + (found == 1 ? " dot" : " dots") + " found at " + deg(r.startDeg + 180
                            * j) + ": at least " + MIN_FOUND_PER_HALF + " per half");
                }
            }
            if (!h.why.isEmpty()) {
                return h;
            }
            double a = mean(half.get(0)), b = mean(half.get(1));
            r.x = (a - b) / (a + b);
            r.xSe = Math.sqrt(var(half.get(0)) / half.get(0).size() + var(half.get(1)) / half.get(1).size()) / (a + b);
            maxSe = Math.max(maxSe, r.xSe);
            h.rows.add(r);
        }
        double x1 = h.rows.get(0).x, x2 = h.rows.get(1).x, k = Math.hypot(x1, x2);
        h.peakDeg = norm(h.rows.get(0).startDeg - Math.toDegrees(Math.atan2(-x1, -x2)));
        h.a = Math.PI / 2 * k;
        h.balancedStartDeg = norm(h.peakDeg + 180);
        h.peakSeDeg = k == 0 ? null : Math.toDegrees(Math.hypot(h.rows.get(0).xSe, h.rows.get(1).xSe) / k);
        if (k == 0 || k < RIPPLE_SE * maxSe) {
            h.why.add("no ripple: a whole-turn recipe needs no start angle");
        }
        else if (h.peakSeDeg > LOW_SE_DEG) {
            h.why.add(String.format(Locale.ROOT, "the peak is uncertain: its standard error is %s° (at most %s° to set "
                    + "it)", DispenseProfile.plain(round1(h.peakSeDeg)), DispenseProfile.plain(LOW_SE_DEG)));
        }
        else {
            h.confidence = h.peakSeDeg <= HIGH_SE_DEG ? HIGH : LOW;
            if (LOW.equals(h.confidence)) {
                h.why.add(String.format(Locale.ROOT, "the peak's standard error is %s° (high at most %s°)",
                        DispenseProfile.plain(round1(h.peakSeDeg)), DispenseProfile.plain(HIGH_SE_DEG)));
            }
        }
        return h;
    }

    /** Quarter dots of whole turns, from the first dot's angle on. */
    public static Quarters quarters(List<Dot> dots) {
        Quarters q = new Quarters();
        double p = norm(dots.get(0).startDeg);
        List<List<Double>> g = groups(dots, p, 90, 4);
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
            q.why.add("no dead quarter: every quarter lays more than half the largest");
            return q;
        }
        for (int j = 0; j < 4; j++) {
            if (dead[j] && dead[(j + 1) % 4]) {
                q.why.add("a dead half: run the halves measure");
                return q;
            }
            if (j < 2 && dip[j] && dip[j + 2]) {
                q.why.add("two dips half a turn apart (" + deg(s[j]) + " and " + deg(s[j + 2]) + ")");
                return q;
            }
        }
        if (dips.size() == 2) {
            int a = dips.get(0), b = dips.get(1);
            int first = (a + 1) % 4 == b ? a : b; // in turn order
            q.why.add("the dead region straddles two quarters (" + deg(s[first]) + " and " + deg(s[(first + 1) % 4])
                    + ")");
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
        q.confidence = q.why.isEmpty() ? HIGH : LOW;
        return q;
    }

    /** The values of the dots grouped by the nearest of {@code n} angles {@code step} apart from {@code p}. */
    private static List<List<Double>> groups(List<Dot> dots, double p, double step, int n) {
        List<List<Double>> g = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            g.add(new ArrayList<>());
        }
        for (Dot d : dots) {
            g.get((int) Math.floorMod(Math.round(norm(d.startDeg - p) / step), (long) n)).add(d.areaMm2);
        }
        return g;
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
        return d < 0 ? d + 360 : d >= 360 ? 0 : d;
    }

    private static double round1(double x) {
        return Math.round(x * 10) / 10.0;
    }

    /** "46°", "297.9°". */
    private static String deg(double x) {
        return DispenseProfile.plain(round1(norm(x)) % 360) + "°";
    }
}
