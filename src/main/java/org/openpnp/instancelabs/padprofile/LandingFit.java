package org.openpnp.instancelabs.padprofile;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a needle lands (tip setups design §22.3), pure: the search radius and the band it needs, the
 * locator's coarse shift, and the fit of a row of 8 dots whose gaps all differ. The residual shift is
 * tried within ±{@value #RESIDUAL_MAX_MM} mm; the one that puts at least {@value #MIN_MATCHES} of the
 * 8 targets within {@value #MATCH_MM} mm of a blob wins, and the next-best must match at most
 * {@value #MAX_NEXT_BEST} (no distance between two row dots repeats within ±1 mm).
 *
 * <p>Blobs are {x, y, areaMm2} in machine mm; targets {x, y}.</p>
 */
public final class LandingFit {
    /** The row's dots along their line, mm from the first: every gap different, the smallest 2.0 mm. */
    public static final double[] ROW_MM = {0, 2.0, 4.5, 7.5, 11.0, 15.0, 19.5, 24.5};
    /** Search radii R (mm): a setup with a landing of its own that is straight or bent the recorded way; else; wide. */
    public static final double SEARCH_KNOWN_MM = 2, SEARCH_FIRST_MM = 6, SEARCH_WIDE_MM = 9;
    /** The band of the check is 2R + this high (mm). */
    public static final double BAND_EXTRA_MM = 8;
    public static final double RESIDUAL_MAX_MM = 1.0;
    public static final double MATCH_MM = 0.3;
    public static final int MIN_MATCHES = 6;
    public static final int MAX_NEXT_BEST = 3;
    /** The ladder's landing limit: the matched pairs scatter at most this (mm). */
    public static final double SCATTER_MAX_MM = 0.15;
    /** A landing this far from the setup's own is asked about, not saved (mm). */
    public static final double MOVED_MM = 1.0;

    private LandingFit() {
    }

    /** The fit of the row: the residual shift, how many targets it matched, the next-best count, the scatter. */
    public static final class Fit {
        /** Residual shift {dx, dy} (mm) added to the coarse one. */
        public double dxMm, dyMm;
        public int matched;
        public int nextBest;
        public double scatterMm;
        /** The matched pairs {target x, y, blob x, y}. */
        public List<double[]> pairs = new ArrayList<>();
        /** At least {@link #MIN_MATCHES} matched, the next-best at most {@link #MAX_NEXT_BEST}, scatter in limit. */
        public boolean ok;
    }

    /**
     * The search radius R: {@link #SEARCH_KNOWN_MM} for a setup with a landing of its own that is straight, or bent
     * with its bend direction recorded; {@link #SEARCH_FIRST_MM} otherwise; {@link #SEARCH_WIDE_MM} with wide.
     */
    public static double searchMm(boolean ownLanding, boolean straight, boolean bendTowardKnown, boolean wide) {
        if (wide) {
            return SEARCH_WIDE_MM;
        }
        return ownLanding && (straight || bendTowardKnown) ? SEARCH_KNOWN_MM : SEARCH_FIRST_MM;
    }

    /** The band height the check reserves: 2R + 8 mm (R 2 → 12, R 6 → 20, R 9 → 26 mm). */
    public static double bandHeightMm(double searchMm) {
        return 2 * searchMm + BAND_EXTRA_MM;
    }

    /**
     * The locator's coarse shift C = blob - target: the new blob nearest the target within ±R (the largest when a
     * dot splashed), or null when there is none. Blobs are {x, y, areaMm2}; "within ±R" is a square window.
     */
    public static double[] locate(List<double[]> newBlobs, double[] target, double searchMm) {
        double[] best = null;
        for (double[] b : newBlobs) {
            if (Math.abs(b[0] - target[0]) > searchMm + 1e-9 || Math.abs(b[1] - target[1]) > searchMm + 1e-9) {
                continue;
            }
            // the largest blob in the window: a splashed dot leaves satellites, the dot itself is the big one
            if (best == null || area(b) > area(best) + 1e-12 || Math.abs(area(b) - area(best)) <= 1e-12
                    && dist(b, target) < dist(best, target)) {
                best = b;
            }
        }
        return best == null ? null : new double[] {best[0] - target[0], best[1] - target[1]};
    }

    private static double area(double[] b) {
        return b.length > 2 ? b[2] : 0;
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    /** The grid of residual shifts tried (mm): every 0.02 mm within ±{@link #RESIDUAL_MAX_MM}. */
    static final double STEP_MM = 0.02;

    /**
     * The best residual shift within ±1.0 mm that lays the targets on the blobs (see the class comment): every shift
     * of a 0.02 mm grid is counted (targets within {@link #MATCH_MM} of a blob, each blob taken once), the best count
     * wins (ties: the smaller sum of distances); it is then refined to the mean offset of its pairs. The next-best is
     * the best count of a shift more than 2 × {@link #MATCH_MM} from the winner.
     */
    public static Fit fit(List<double[]> targets, List<double[]> blobs) {
        Fit f = new Fit();
        int steps = (int) Math.round(RESIDUAL_MAX_MM / STEP_MM);
        int bestN = -1;
        double bestSum = Double.MAX_VALUE;
        double bx = 0, by = 0;
        int[][] counts = new int[2 * steps + 1][2 * steps + 1];
        for (int i = -steps; i <= steps; i++) {
            for (int j = -steps; j <= steps; j++) {
                double dx = i * STEP_MM, dy = j * STEP_MM;
                double[] sum = {0};
                int n = matches(targets, blobs, dx, dy, sum, null);
                counts[i + steps][j + steps] = n;
                if (n > bestN || n == bestN && sum[0] < bestSum - 1e-12) {
                    bestN = n;
                    bestSum = sum[0];
                    bx = dx;
                    by = dy;
                }
            }
        }
        // refine: the mean offset of the pairs of the best shift, then the pairs again at that shift
        List<double[]> pairs = new ArrayList<>();
        matches(targets, blobs, bx, by, new double[1], pairs);
        if (!pairs.isEmpty()) {
            double mx = 0, my = 0;
            for (double[] p : pairs) {
                mx += p[2] - p[0];
                my += p[3] - p[1];
            }
            mx /= pairs.size();
            my /= pairs.size();
            if (Math.abs(mx) <= RESIDUAL_MAX_MM + MATCH_MM && Math.abs(my) <= RESIDUAL_MAX_MM + MATCH_MM) {
                List<double[]> again = new ArrayList<>();
                int n = matches(targets, blobs, mx, my, new double[1], again);
                if (n >= pairs.size()) {
                    bx = mx;
                    by = my;
                    pairs = again;
                }
            }
        }
        f.dxMm = bx;
        f.dyMm = by;
        f.matched = pairs.size();
        f.pairs = pairs;
        int next = 0;
        for (int i = -steps; i <= steps; i++) {
            for (int j = -steps; j <= steps; j++) {
                if (Math.hypot(i * STEP_MM - bx, j * STEP_MM - by) > 2 * MATCH_MM + 1e-9) {
                    next = Math.max(next, counts[i + steps][j + steps]);
                }
            }
        }
        f.nextBest = next;
        double sx = 0, sy = 0, sxx = 0, syy = 0;
        for (double[] p : pairs) {
            double ex = p[2] - p[0] - bx, ey = p[3] - p[1] - by;
            sx += ex;
            sy += ey;
            sxx += ex * ex;
            syy += ey * ey;
        }
        int n = pairs.size();
        f.scatterMm = n < 2 ? 0 : Math.max(Math.sqrt(Math.max(0, (sxx - sx * sx / n) / (n - 1))),
                Math.sqrt(Math.max(0, (syy - sy * sy / n) / (n - 1))));
        f.ok = f.matched >= MIN_MATCHES && f.nextBest <= MAX_NEXT_BEST && f.scatterMm <= SCATTER_MAX_MM + 1e-9;
        return f;
    }

    /**
     * The targets moved by (dx, dy) that lie within {@link #MATCH_MM} of a blob, each blob taken once (nearest pairs
     * first); {@code sum[0]} gets the sum of their distances, {@code pairs} (when given) the {target, blob} pairs.
     */
    private static int matches(List<double[]> targets, List<double[]> blobs, double dx, double dy, double[] sum,
            List<double[]> pairs) {
        List<double[]> cand = new ArrayList<>();
        for (int t = 0; t < targets.size(); t++) {
            double[] a = targets.get(t);
            for (int b = 0; b < blobs.size(); b++) {
                double d = Math.hypot(blobs.get(b)[0] - (a[0] + dx), blobs.get(b)[1] - (a[1] + dy));
                if (d <= MATCH_MM + 1e-9) {
                    cand.add(new double[] {d, t, b});
                }
            }
        }
        cand.sort((x, y) -> Double.compare(x[0], y[0]));
        boolean[] tUsed = new boolean[targets.size()];
        boolean[] bUsed = new boolean[blobs.size()];
        int n = 0;
        for (double[] c : cand) {
            int t = (int) c[1], b = (int) c[2];
            if (tUsed[t] || bUsed[b]) {
                continue;
            }
            tUsed[t] = true;
            bUsed[b] = true;
            n++;
            sum[0] += c[0];
            if (pairs != null) {
                double[] a = targets.get(t), o = blobs.get(b);
                pairs.add(new double[] {a[0], a[1], o[0], o[1]});
            }
        }
        return n;
    }
}
