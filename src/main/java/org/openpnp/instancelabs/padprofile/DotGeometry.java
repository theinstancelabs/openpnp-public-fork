package org.openpnp.instancelabs.padprofile;

import java.util.ArrayList;
import java.util.List;

/**
 * Dot positions on a pad and the plane geometry the checks need (§1.6, §4.3 step 3). Pad frame:
 * mm from the pad centre, x along the pad's w. D/DotSizer delegates its grid here with no pitch
 * floor and no dot radius, so today's positions are unchanged.
 */
public final class DotGeometry {
    private DotGeometry() {
    }

    /** Area of a rounded rectangle, roundness = corner radius as % of half the short side (D/DotMath). */
    public static double padAreaMm2(double w, double h, double roundnessPercent) {
        double r = Math.min(w, h) / 2.0 * Math.max(0, Math.min(100, roundnessPercent)) / 100.0;
        return w * h - (4.0 - Math.PI) * r * r;
    }

    /**
     * Profile dots for a pad without a plan of its own (D/DotSizer, no dose model): one, or for a pad
     * larger than {@code largestSingleDotPadMm2}, ceil(area / largest) capped at {@code maxDots}
     * (default {@link Limits#MAX_DOTS}).
     */
    public static int profileDotCount(double areaMm2, Double largestSingleDotPadMm2, Integer maxDots) {
        if (largestSingleDotPadMm2 == null || !(largestSingleDotPadMm2 > 0) || areaMm2 <= largestSingleDotPadMm2) {
            return 1;
        }
        int max = maxDots != null ? Math.max(1, maxDots) : Limits.MAX_DOTS;
        return Math.min(max, (int) Math.ceil(areaMm2 / largestSingleDotPadMm2 - 1e-9));
    }

    /** nx * ny = n maximising the short side of a cell (ties -> more columns along the long side). */
    public static int[] bestGrid(int n, double w, double h) {
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

    /**
     * n dot centres on a w x h pad (§4.3 step 3): DotSizer's grid of cell centres; along an axis
     * where the cell pitch is below {@code pMin} the dots spread to pMin (the needle face must not
     * land on the earlier dot); along an axis where the outer dot of radius {@code rDot} overhangs
     * more than {@link Limits#OVERHANG_MM} and the pitch is above pMin, the dots move inward, down to
     * pMin. With pMin = rDot = 0 nothing moves. Rows along y, x fastest (DotSizer's order).
     */
    public static List<double[]> layoutN(double w, double h, int n, double pMin, double rDot) {
        int[] grid = bestGrid(Math.max(1, n), w, h);
        double[] xs = axis(grid[0], w, pMin, rDot);
        double[] ys = axis(grid[1], h, pMin, rDot);
        List<double[]> offsets = new ArrayList<>();
        for (double y : ys) {
            for (double x : xs) {
                offsets.add(new double[] {x, y});
            }
        }
        return offsets;
    }

    static double[] axis(int nn, double len, double pMin, double rDot) {
        double[] c = new double[nn];
        double cell = len / nn;
        double p = cell;
        boolean moved = false;
        if (nn > 1 && cell < pMin) {
            p = pMin;
            moved = true;
        }
        else if (nn > 1 && (nn - 1) * cell / 2 + rDot - len / 2 > Limits.OVERHANG_MM + Limits.EPS) {
            p = Math.max(pMin, 2 * (len / 2 + Limits.OVERHANG_MM - rDot) / (nn - 1));
            moved = true;
        }
        for (int i = 0; i < nn; i++) {
            // the cell centres exactly as D/DotSizer computes them when nothing moves
            c[i] = moved ? (i - (nn - 1) / 2.0) * p : -len / 2 + len / nn * (i + 0.5);
        }
        return c;
    }

    /** A pad-frame point in the pad's frame (board, +Y up). */
    public static double[] toBoard(PadInstance p, double u, double v) {
        double a = Math.toRadians(p.rotationDeg);
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new double[] {p.x + u * c - v * s, p.y + u * s + v * c};
    }

    /** A board point in the pad's frame. */
    public static double[] toPad(PadInstance p, double x, double y) {
        double a = Math.toRadians(p.rotationDeg);
        double c = Math.cos(a);
        double s = Math.sin(a);
        double dx = x - p.x;
        double dy = y - p.y;
        return new double[] {dx * c + dy * s, -dx * s + dy * c};
    }

    /** Distance from a board point to a pad's rectangle (0 inside; corners not rounded). */
    public static double pointToPadMm(double x, double y, PadInstance q) {
        double[] uv = toPad(q, x, y);
        return Math.hypot(Math.max(Math.abs(uv[0]) - q.wMm / 2, 0), Math.max(Math.abs(uv[1]) - q.hMm / 2, 0));
    }

    /** How far a dot of radius r at a pad-frame point reaches past the pad edge (max over both axes). */
    public static double overhangMm(PadInstance p, double[] padFrameDot, double r) {
        return Math.max(Math.abs(padFrameDot[0]) + r - p.wMm / 2, Math.abs(padFrameDot[1]) + r - p.hMm / 2);
    }

    /** Half the pad's diagonal: every point of the pad lies within it of the centre. */
    public static double halfDiagonal(PadInstance p) {
        return Math.hypot(p.wMm, p.hMm) / 2;
    }

    /** The four corners (board frame). */
    public static double[][] corners(PadInstance p) {
        double hw = p.wMm / 2;
        double hh = p.hMm / 2;
        return new double[][] {toBoard(p, -hw, -hh), toBoard(p, hw, -hh), toBoard(p, hw, hh), toBoard(p, -hw, hh)};
    }

    /** Edge-to-edge distance of two pad rectangles; 0 when they touch or overlap. */
    public static double padGapMm(PadInstance a, PadInstance b) {
        double[][] ca = corners(a);
        double[][] cb = corners(b);
        if (intersects(ca, cb)) {
            return 0;
        }
        double d = Double.POSITIVE_INFINITY;
        for (double[] p : ca) {
            d = Math.min(d, pointToPadMm(p[0], p[1], b));
        }
        for (double[] p : cb) {
            d = Math.min(d, pointToPadMm(p[0], p[1], a));
        }
        return d;
    }

    /** Separating-axis test of two convex quadrilaterals (touching counts as intersecting). */
    static boolean intersects(double[][] a, double[][] b) {
        for (double[][] poly : new double[][][] {a, b}) {
            for (int i = 0; i < 4; i++) {
                double ex = poly[(i + 1) % 4][0] - poly[i][0];
                double ey = poly[(i + 1) % 4][1] - poly[i][1];
                double nx = -ey;
                double ny = ex;
                double[] pa = project(a, nx, ny);
                double[] pb = project(b, nx, ny);
                double len = Math.hypot(nx, ny);
                if (pa[1] < pb[0] - 1e-9 * len || pb[1] < pa[0] - 1e-9 * len) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double[] project(double[][] poly, double nx, double ny) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double[] p : poly) {
            double v = p[0] * nx + p[1] * ny;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return new double[] {lo, hi};
    }
}
