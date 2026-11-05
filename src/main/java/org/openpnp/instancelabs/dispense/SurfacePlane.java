package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Least-squares plane z = a + b x + c y through probed surface points (plan §6.1: "if the warp you
 * care about is less than 3 x probe SD, a 3-4 point plane fit beats a full map"), with the convex
 * hull of the points for the containment check of I4 ("the target lies inside the board's Z-map").
 * Pure; no OpenPnP state.
 */
public final class SurfacePlane {
    public final double a, b, c;
    public final double[] residuals;
    public final double maxAbsResidualMm;
    public final double minZ, maxZ;
    public final int pointCount;
    /** Convex hull, counter-clockwise. */
    private final List<double[]> hull;
    private final String fingerprint;

    private SurfacePlane(double a, double b, double c, double[] residuals, double minZ, double maxZ,
            List<double[]> hull, String fingerprint) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.residuals = residuals;
        double m = 0;
        for (double r : residuals) {
            m = Math.max(m, Math.abs(r));
        }
        this.maxAbsResidualMm = m;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.pointCount = residuals.length;
        this.hull = hull;
        this.fingerprint = fingerprint;
    }

    /** A surface point's coordinates are within this (mm): any machine's reach, far below an overflow. */
    static final double MAX_MM = 10_000;

    /**
     * Fits the plane. Needs at least 3 points that span an area (not all on one line, hull area
     * at least 1 mm^2).
     */
    public static SurfacePlane fit(List<DispenseConfig.SurfacePoint> pts) {
        if (pts == null || pts.size() < 3) {
            throw new IllegalArgumentException("a surface needs at least 3 probed points, it has "
                    + (pts == null ? 0 : pts.size()));
        }
        int n = pts.size();
        double mx = 0, my = 0, mz = 0;
        for (DispenseConfig.SurfacePoint p : pts) {
            if (!Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z)) {
                throw new IllegalArgumentException("surface point with a missing coordinate");
            }
            // far outside any machine: the sums below would overflow into a NaN plane, or a flat one at the mean
            if (Math.abs(p.x) > MAX_MM || Math.abs(p.y) > MAX_MM || Math.abs(p.z) > MAX_MM) {
                throw new IllegalArgumentException(String.format(Locale.ROOT, "surface point (%g, %g, %g) is out of "
                        + "range: machine coordinates are within ±%.0f mm", p.x, p.y, p.z, MAX_MM));
            }
            mx += p.x;
            my += p.y;
            mz += p.z;
        }
        mx /= n;
        my /= n;
        mz /= n;
        double sxx = 0, sxy = 0, syy = 0, sxz = 0, syz = 0;
        for (DispenseConfig.SurfacePoint p : pts) {
            double dx = p.x - mx, dy = p.y - my, dz = p.z - mz;
            sxx += dx * dx;
            sxy += dx * dy;
            syy += dy * dy;
            sxz += dx * dz;
            syz += dy * dz;
        }
        List<double[]> hull = convexHull(pts);
        double area = Math.abs(polygonArea(hull));
        double det = sxx * syy - sxy * sxy;
        if (hull.size() < 3 || area < 1.0 || !(det > 1e-9)) { // a NaN det is refused too
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "the surface points lie (almost) on one line (area %.2f mm^2): probe at least 3 corners", area));
        }
        double b = (sxz * syy - syz * sxy) / det;
        double c = (syz * sxx - sxz * sxy) / det;
        double a = mz - b * mx - c * my;
        double[] res = new double[n];
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        StringBuilder fp = new StringBuilder();
        for (int i = 0; i < n; i++) {
            DispenseConfig.SurfacePoint p = pts.get(i);
            res[i] = p.z - (a + b * p.x + c * p.y);
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
            fp.append(String.format(Locale.ROOT, "%.4f,%.4f,%.4f;", p.x, p.y, p.z));
        }
        return new SurfacePlane(a, b, c, res, minZ, maxZ, hull, fp.toString());
    }

    /** Surface Z (OpenPnP frame) at machine x, y. */
    public double z(double x, double y) {
        return a + b * x + c * y;
    }

    /** Largest slope (mm per mm). */
    public double slope() {
        return Math.hypot(b, c);
    }

    /** Distance of (x, y) outside the convex hull of the points; 0 inside or on it. */
    public double distanceOutside(double x, double y) {
        boolean inside = true;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < hull.size(); i++) {
            double[] p = hull.get(i), q = hull.get((i + 1) % hull.size());
            double ex = q[0] - p[0], ey = q[1] - p[1];
            double cross = ex * (y - p[1]) - ey * (x - p[0]);
            if (cross < -1e-9) {
                inside = false;
            }
            double len2 = ex * ex + ey * ey;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((x - p[0]) * ex + (y - p[1]) * ey) / len2));
            best = Math.min(best, Math.hypot(x - (p[0] + t * ex), y - (p[1] + t * ey)));
        }
        return inside ? 0 : best;
    }

    /** Identity of the probed points (a plan made on another surface map is stale). */
    public String fingerprint() {
        return fingerprint;
    }

    static List<double[]> convexHull(List<DispenseConfig.SurfacePoint> pts) {
        List<double[]> p = new ArrayList<>();
        for (DispenseConfig.SurfacePoint s : pts) {
            p.add(new double[] {s.x, s.y});
        }
        p.sort(Comparator.<double[]> comparingDouble(v -> v[0]).thenComparingDouble(v -> v[1]));
        if (p.size() < 3) {
            return p;
        }
        List<double[]> h = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            int start = h.size();
            for (int k = 0; k < p.size(); k++) {
                double[] v = p.get(pass == 0 ? k : p.size() - 1 - k);
                while (h.size() >= start + 2 && cross(h.get(h.size() - 2), h.get(h.size() - 1), v) <= 0) {
                    h.remove(h.size() - 1);
                }
                h.add(v);
            }
            h.remove(h.size() - 1);
        }
        return h;
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    private static double polygonArea(List<double[]> h) {
        double s = 0;
        for (int i = 0; i < h.size(); i++) {
            double[] p = h.get(i), q = h.get((i + 1) % h.size());
            s += p[0] * q[1] - q[0] * p[1];
        }
        return s / 2;
    }
}
