package org.openpnp.instancelabs.dispense;

import java.util.List;

import org.openpnp.instancelabs.padprofile.DotGeometry;
import org.openpnp.instancelabs.padprofile.Limits;

/**
 * Pad -> dot positions. Every dot is one dot of the dispense profile (no dose model): one dot per
 * pad, or, for a pad larger than its type's {@link PadRule#largestSingleDotPadMm2}, n = ceil(area /
 * largestSingleDotPadMm2) dots (at most {@link PadRule#maxDots}, default {@link #DEFAULT_MAX_DOTS})
 * on the grid that maximises the short side of a cell. The grid is {@link DotGeometry#layoutN} with
 * no pitch floor and no dot radius (nothing moves), the one a pad type with its own dose uses too.
 */
public final class DotSizer {
    public static final int DEFAULT_MAX_DOTS = Limits.MAX_DOTS;

    private DotSizer() {
    }

    /** Dot centres in the pad frame (mm, pad X along w). */
    public static List<double[]> layout(double wMm, double hMm, double areaMm2, PadRule rule) {
        int n = rule == null ? 1 : DotGeometry.profileDotCount(areaMm2, rule.largestSingleDotPadMm2, rule.maxDots);
        return DotGeometry.layoutN(wMm, hMm, n, 0, 0);
    }

    /** nx * ny = n maximising the short side of a cell (ties -> more columns along the long side). */
    static int[] bestGrid(int n, double w, double h) {
        return DotGeometry.bestGrid(n, w, h);
    }
}
