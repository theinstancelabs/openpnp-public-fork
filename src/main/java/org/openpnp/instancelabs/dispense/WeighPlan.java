package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.openpnp.instancelabs.padprofile.LadderStats;
import org.openpnp.instancelabs.padprofile.Limits;

/**
 * The weighing of the pad calibration (profile calibration design §7.2, §7.5), pure: which dose goes
 * on which weigh coupon, how many dots, and where on the coupon.
 *
 * <ul>
 * <li>Count: ceil(weighTargetMg / predicted mg per dot), clamped to {@value Limits#WEIGH_DOTS_MIN}..
 * {@value Limits#WEIGH_DOTS_MAX}, rounded up to whole screw revolutions (N·s a multiple of 1600 steps):
 * the first-harmonic ripple cancels in the total. Quarter 136, half 68, one turn 34 at 70 mg.</li>
 * <li>Grid: serpentine rows at the dose's hop pitch, centred on the coupon's place, every dot the
 * margin + d95/2 inside the coupon's nominal rectangle.</li>
 * <li>Allocation: the doses that need a confirmed mass, by the number of groups that chose them, then
 * the smaller first. One dose: coupon 1, and coupon 2 the profile dose when it has no confirmed mass
 * (the control's weighing), else the same dose again. Two: one each (their masses per step confirm
 * each other). More: the first two; the rest wait for another calibration.</li>
 * </ul>
 */
public final class WeighPlan {
    private WeighPlan() {
    }

    /** Weigh dots of a dose on one coupon. */
    public static int count(int steps, double mgPerDot, double targetMg) {
        int n = (int) Math.ceil(targetMg / mgPerDot - 1e-9);
        n = Math.max(Limits.WEIGH_DOTS_MIN, Math.min(Limits.WEIGH_DOTS_MAX, n));
        int phases = LadderStats.phases(steps);
        return (n + phases - 1) / phases * phases;
    }

    /** How far the grid's outer dot centres stay inside the coupon's rectangle (mm). */
    public static double insetMm(double d95Mm, double marginMm) {
        return marginMm + d95Mm / 2;
    }

    /**
     * The weigh grid: {@code n} dots in serpentine rows {@code pitch} apart both ways, centred on the
     * rectangle {x0, y0, x1, y1}, in dispense order; null when they do not fit inside the margin.
     */
    public static List<double[]> grid(double[] rect, int n, double pitch, double d95Mm, double marginMm) {
        double inset = insetMm(d95Mm, marginMm);
        double uw = rect[2] - rect[0] - 2 * inset;
        double uh = rect[3] - rect[1] - 2 * inset;
        if (n <= 0 || uw < 0 || uh < 0) {
            return null;
        }
        int cols = Math.min(n, (int) Math.floor(uw / pitch + 1e-9) + 1);
        int rows = (n + cols - 1) / cols;
        if ((rows - 1) * pitch > uh + 1e-9) {
            return null;
        }
        double cx = (rect[0] + rect[2]) / 2, cy = (rect[1] + rect[3]) / 2;
        double left = cx - (cols - 1) * pitch / 2;
        double bottom = cy - (rows - 1) * pitch / 2;
        List<double[]> l = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int row = k / cols;
            int col = k % cols;
            int c = row % 2 == 0 ? col : cols - 1 - col;
            l.add(new double[] {left + c * pitch, bottom + row * pitch});
        }
        return l;
    }

    /**
     * The camera looks before a coupon is touched (§7.2 step 2): the grid's four corners pushed out to
     * {@code outMm} beyond its outer dots, then its centre (last).
     */
    public static List<double[]> looks(List<double[]> grid, double outMm) {
        double[] b = box(grid);
        List<double[]> l = new ArrayList<>();
        l.add(new double[] {b[0] - outMm, b[1] - outMm});
        l.add(new double[] {b[2] + outMm, b[1] - outMm});
        l.add(new double[] {b[2] + outMm, b[3] + outMm});
        l.add(new double[] {b[0] - outMm, b[3] + outMm});
        l.add(new double[] {(b[0] + b[2]) / 2, (b[1] + b[3]) / 2});
        return l;
    }

    /**
     * A grid whose dots, each {@code insetMm} inside the coupon's edges, stay inside what another grid and its inset
     * cover: the coupon step checked the margin around that grid (§7.2 step 2), so it holds for this one too. False
     * for no grid (the dots do not fit the coupon).
     */
    public static boolean within(List<double[]> grid, double insetMm, List<double[]> checked, double checkedInsetMm) {
        if (grid == null || grid.isEmpty() || checked == null || checked.isEmpty()) {
            return false;
        }
        double[] g = box(grid);
        double[] c = box(checked);
        double e = 1e-9;
        return g[0] - insetMm >= c[0] - checkedInsetMm - e && g[1] - insetMm >= c[1] - checkedInsetMm - e
                && g[2] + insetMm <= c[2] + checkedInsetMm + e && g[3] + insetMm <= c[3] + checkedInsetMm + e;
    }

    /** {minX, minY, maxX, maxY} of the dots. */
    private static double[] box(List<double[]> grid) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] p : grid) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    /** The N1 touches on a coupon: 6 mm inside three of its corners (§7.2 step 2). */
    public static List<double[]> touches(double[] rect) {
        double in = 6;
        List<double[]> l = new ArrayList<>();
        l.add(new double[] {rect[0] + in, rect[1] + in});
        l.add(new double[] {rect[2] - in, rect[1] + in});
        l.add(new double[] {rect[0] + in, rect[3] - in});
        return l;
    }

    /** A dose that needs a confirmed mass, and how many groups chose it. */
    public static final class Need {
        public final int steps;
        public final int groups;

        public Need(int steps, int groups) {
            this.steps = steps;
            this.groups = groups;
        }
    }

    /** Which dose each coupon gets (in coupon order), and the doses left for another calibration. */
    public static final class Allocation {
        public final List<Integer> coupons = new ArrayList<>();
        public final List<Integer> notWeighed = new ArrayList<>();
    }

    /**
     * @param needs the doses without a confirmed mass
     * @param profileConfirmed the profile dose has a confirmed mass
     * @param couponCount coupon places defined (1 or 2)
     */
    public static Allocation allocate(List<Need> needs, int profileSteps, boolean profileConfirmed, int couponCount) {
        Allocation a = new Allocation();
        List<Need> l = new ArrayList<>(needs);
        l.sort(Comparator.<Need>comparingInt(x -> -x.groups).thenComparingInt(x -> x.steps));
        if (l.isEmpty() || couponCount <= 0) {
            for (Need x : l) {
                a.notWeighed.add(x.steps);
            }
            return a;
        }
        a.coupons.add(l.get(0).steps);
        if (couponCount >= 2) {
            if (l.size() == 1) {
                int s = l.get(0).steps;
                a.coupons.add(!profileConfirmed && s != profileSteps ? profileSteps : s);
            }
            else {
                a.coupons.add(l.get(1).steps);
            }
        }
        for (Need x : l) {
            if (!a.coupons.contains(x.steps)) {
                a.notWeighed.add(x.steps);
            }
        }
        Collections.sort(a.notWeighed);
        return a;
    }
}
