package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

/**
 * Pad -> dot positions. Every dot is one dot of the dispense profile (no dose model): one dot per
 * pad, or, for a pad larger than its type's {@link PadRule#largestSingleDotPadMm2}, n = ceil(area /
 * largestSingleDotPadMm2) dots (at most {@link PadRule#maxDots}, default {@link #DEFAULT_MAX_DOTS})
 * on the grid that maximises the short side of a cell.
 */
public final class DotSizer {
    public static final int DEFAULT_MAX_DOTS = 6;

    private DotSizer() {
    }

    /** Dot centres in the pad frame (mm, pad X along w). */
    public static List<double[]> layout(double wMm, double hMm, double areaMm2, PadRule rule) {
        int n = 1;
        if (rule != null && rule.largestSingleDotPadMm2 != null && rule.largestSingleDotPadMm2 > 0
                && areaMm2 > rule.largestSingleDotPadMm2) {
            int max = rule.maxDots != null ? Math.max(1, rule.maxDots) : DEFAULT_MAX_DOTS;
            n = Math.min(max, (int) Math.ceil(areaMm2 / rule.largestSingleDotPadMm2 - 1e-9));
        }
        int[] grid = bestGrid(n, wMm, hMm);
        List<double[]> offsets = new ArrayList<>();
        for (int iy = 0; iy < grid[1]; iy++) {
            for (int ix = 0; ix < grid[0]; ix++) {
                offsets.add(new double[] {-wMm / 2 + wMm / grid[0] * (ix + 0.5), -hMm / 2 + hMm / grid[1] * (iy + 0.5)});
            }
        }
        return offsets;
    }

    /** nx * ny = n maximising the short side of a cell (ties -> more columns along the long side). */
    static int[] bestGrid(int n, double w, double h) {
        int[] best = {n, 1};
        double bestCell = -1;
        for (int nx = 1; nx <= n; nx++) {
            if (n % nx != 0) {
                continue;
            }
            int ny = n / nx;
            double cell = Math.min(w / nx, h / ny);
            boolean better = cell > bestCell + 1e-12
                    || (Math.abs(cell - bestCell) <= 1e-12 && (w >= h ? nx > best[0] : ny > best[1]));
            if (better) {
                bestCell = cell;
                best = new int[] {nx, ny};
            }
        }
        return best;
    }
}
