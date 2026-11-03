package org.openpnp.instancelabs.kicad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plane geometry for the parser and the art (design §2.1): arcs (3 points, KiCad 5 form, exact extents, flattening),
 * Béziers, chaining segments into loops through a hash grid, polygon area and containment, iterative Douglas–Peucker,
 * convex hull and the minimum-area enclosing rectangle. Point lists are x0 y0 x1 y1 … arrays.
 */
public final class KGeom {
    /** Default flattening chord error (mm). */
    public static final double CHORD_MM = 0.01;
    /** Arcs and circles are flattened in steps of at least 1° (≤ 360 segments per full circle). */
    public static final double MIN_STEP = Math.toRadians(1.0);
    /** ... and at most 45°, so small circles stay round. */
    public static final double MAX_STEP = Math.toRadians(45.0);
    public static final int BEZIER_MAX_SEGMENTS = 64;
    /**
     * Points one step may make by drawing arcs, circles and curves as lines (the parser's polygon arcs; the board
     * frame's Edge.Cuts). The 1° steps bound one arc; this bounds them all: within the item limit, a hostile file
     * of full circles would otherwise make about 50 million points (gigabytes).
     */
    public static final int MAX_FLAT_POINTS = 2_000_000;

    private KGeom() {
    }

    /** A growable list of x, y pairs. */
    public static final class Pts {
        private double[] a = new double[32];
        private int n;

        public void add(double x, double y) {
            if (n + 2 > a.length) {
                a = Arrays.copyOf(a, a.length * 2);
            }
            a[n++] = x;
            a[n++] = y;
        }

        /** Number of points. */
        public int size() {
            return n / 2;
        }

        public double x(int i) {
            return a[2 * i];
        }

        public double y(int i) {
            return a[2 * i + 1];
        }

        public void removeLast() {
            n -= 2;
        }

        public double[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }

    // ---- arcs ----------------------------------------------------------------------------------------------------

    /**
     * The circle through start, mid and end: {cx, cy, r, a0, sweep} with a0 the start angle and sweep the signed angle
     * from start to end through mid (radians, positive = increasing angle in the coordinates given). A start equal to
     * the end gives a full circle. Null when the points are collinear or coincide.
     */
    public static double[] arc3(double sx, double sy, double mx, double my, double ex, double ey) {
        if (Math.hypot(ex - sx, ey - sy) < 1e-9) {
            double cx = (sx + mx) / 2;
            double cy = (sy + my) / 2;
            double r = Math.hypot(mx - sx, my - sy) / 2;
            if (r < 1e-9) {
                return null;
            }
            return new double[] {cx, cy, r, Math.atan2(sy - cy, sx - cx), 2 * Math.PI};
        }
        double bx = mx - sx;
        double by = my - sy;
        double qx = ex - sx;
        double qy = ey - sy;
        double d = 2 * (bx * qy - by * qx);
        double scale = Math.max(Math.hypot(bx, by), Math.hypot(qx, qy));
        if (Math.abs(d) <= 1e-12 * scale * scale) {
            return null;
        }
        double b2 = bx * bx + by * by;
        double q2 = qx * qx + qy * qy;
        double ux = (qy * b2 - by * q2) / d;
        double uy = (bx * q2 - qx * b2) / d;
        double cx = sx + ux;
        double cy = sy + uy;
        double r = Math.hypot(ux, uy);
        double a0 = Math.atan2(sy - cy, sx - cx);
        double a2 = Math.atan2(ey - cy, ex - cx);
        double cross = bx * (ey - my) - by * (ex - mx);
        double sweep = cross > 0 ? norm2pi(a2 - a0) : -norm2pi(a0 - a2);
        return new double[] {cx, cy, r, a0, sweep};
    }

    /**
     * A KiCad 5 arc: centre c, arc start s, sweep angle in degrees, positive = clockwise on screen, which is the
     * positive rotation in KiCad's Y-down coordinates. Returns {sx, sy, mx, my, ex, ey}.
     */
    public static double[] arcKicad5(double cx, double cy, double sx, double sy, double angleDeg) {
        double[] m = rotateAbout(sx, sy, cx, cy, Math.toRadians(angleDeg / 2));
        double[] e = rotateAbout(sx, sy, cx, cy, Math.toRadians(angleDeg));
        return new double[] {sx, sy, m[0], m[1], e[0], e[1]};
    }

    private static double[] rotateAbout(double x, double y, double cx, double cy, double rad) {
        double c = Math.cos(rad);
        double s = Math.sin(rad);
        double dx = x - cx;
        double dy = y - cy;
        return new double[] {cx + dx * c - dy * s, cy + dx * s + dy * c};
    }

    /** Segments for an arc of radius r and the given sweep at the chord error (1° to 45° steps). */
    public static int arcSegments(double r, double sweep, double chord) {
        double step = chord < r ? 2 * Math.acos(1 - chord / r) : MAX_STEP;
        step = Math.max(MIN_STEP, Math.min(MAX_STEP, step));
        return Math.max(1, (int) Math.ceil(Math.abs(sweep) / step - 1e-9));
    }

    /**
     * Appends the arc start → mid → end as a polyline; the first and last points are exactly start and end. Returns
     * false (nothing added) when the points do not make an arc.
     */
    public static boolean flattenArc(double sx, double sy, double mx, double my, double ex, double ey, double chord,
            Pts out, boolean includeStart) {
        double[] a = arc3(sx, sy, mx, my, ex, ey);
        if (a == null) {
            return false;
        }
        int n = arcSegments(a[2], a[4], chord);
        if (includeStart) {
            out.add(sx, sy);
        }
        for (int i = 1; i < n; i++) {
            double t = a[3] + a[4] * i / n;
            out.add(a[0] + a[2] * Math.cos(t), a[1] + a[2] * Math.sin(t));
        }
        out.add(ex, ey);
        return true;
    }

    /** A circle as a closed polygon (the first point is not repeated), counter-clockwise in the coordinates given. */
    public static double[] circle(double cx, double cy, double r, double chord) {
        int n = Math.max(8, arcSegments(r, 2 * Math.PI, chord));
        double[] p = new double[2 * n];
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n;
            p[2 * i] = cx + r * Math.cos(t);
            p[2 * i + 1] = cy + r * Math.sin(t);
        }
        return p;
    }

    /** Appends a cubic Bézier (4 control points, 8 numbers) flattened into at most 64 segments. */
    public static void flattenBezier(double[] c, Pts out, boolean includeStart) {
        double len = 0;
        for (int i = 0; i < 3; i++) {
            len += Math.hypot(c[2 * i + 2] - c[2 * i], c[2 * i + 3] - c[2 * i + 1]);
        }
        int n = Math.min(BEZIER_MAX_SEGMENTS, Math.max(4, (int) Math.ceil(len / 0.25)));
        if (includeStart) {
            out.add(c[0], c[1]);
        }
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            double u = 1 - t;
            double b0 = u * u * u;
            double b1 = 3 * u * u * t;
            double b2 = 3 * u * t * t;
            double b3 = t * t * t;
            out.add(b0 * c[0] + b1 * c[2] + b2 * c[4] + b3 * c[6], b0 * c[1] + b1 * c[3] + b2 * c[5] + b3 * c[7]);
        }
    }

    /** The exact box of an arc: its end points plus the 0°/90°/180°/270° points inside the sweep. */
    public static double[] arcExtents(double sx, double sy, double mx, double my, double ex, double ey) {
        double[] box = emptyBox();
        extend(box, sx, sy);
        extend(box, ex, ey);
        double[] a = arc3(sx, sy, mx, my, ex, ey);
        if (a == null) {
            extend(box, mx, my);
            return box;
        }
        for (int k = 0; k < 4; k++) {
            double t = k * Math.PI / 2;
            boolean inside = a[4] >= 0 ? norm2pi(t - a[3]) <= a[4] : norm2pi(a[3] - t) <= -a[4];
            if (inside) {
                extend(box, a[0] + a[2] * Math.cos(t), a[1] + a[2] * Math.sin(t));
            }
        }
        return box;
    }

    // ---- boxes ---------------------------------------------------------------------------------------------------

    /** {minX, minY, maxX, maxY} that any point extends. */
    public static double[] emptyBox() {
        return new double[] {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY};
    }

    public static void extend(double[] box, double x, double y) {
        box[0] = Math.min(box[0], x);
        box[1] = Math.min(box[1], y);
        box[2] = Math.max(box[2], x);
        box[3] = Math.max(box[3], y);
    }

    public static void extend(double[] box, double[] other) {
        if (!isEmpty(other)) {
            extend(box, other[0], other[1]);
            extend(box, other[2], other[3]);
        }
    }

    public static void extendPts(double[] box, double[] pts) {
        for (int i = 0; i + 1 < pts.length; i += 2) {
            extend(box, pts[i], pts[i + 1]);
        }
    }

    public static boolean isEmpty(double[] box) {
        return !(box[0] <= box[2] && box[1] <= box[3]);
    }

    public static double boxArea(double[] box) {
        return isEmpty(box) ? 0 : (box[2] - box[0]) * (box[3] - box[1]);
    }

    // ---- chaining ------------------------------------------------------------------------------------------------

    /** A chain of pieces: its polyline (closed chains do not repeat the first point), the pieces and their box. */
    public static final class Chain {
        public final double[] pts;
        public final boolean closed;
        public final List<Integer> pieces;
        public final double[] box;

        Chain(double[] pts, boolean closed, List<Integer> pieces, double[] box) {
            this.pts = pts;
            this.closed = closed;
            this.pieces = pieces;
            this.box = box;
        }
    }

    /**
     * Chains open polylines whose end points lie within tol of each other, in file order (deterministic), finding
     * neighbours through a hash grid of 2·tol cells. A chain whose ends meet is closed.
     *
     * @param pieces polylines (at least 2 points each)
     * @param boxes the exact box of each piece (arcs bulge past their points), or null to use the points
     */
    public static List<Chain> chain(List<double[]> pieces, List<double[]> boxes, double tol) {
        try {
            return chain(pieces, boxes, tol, Deadline.none());
        }
        catch (SExprException e) {
            throw new IllegalStateException(e); // Deadline.none() never runs out
        }
    }

    /**
     * {@link #chain(List, List, double)} under the import's deadline, checked before every neighbour search: end
     * points piled into a few grid cells (a hostile file) make each search slow.
     */
    public static List<Chain> chain(List<double[]> pieces, List<double[]> boxes, double tol, Deadline deadline)
            throws SExprException {
        int n = pieces.size();
        double cell = 2 * tol;
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < n; i++) {
            double[] p = pieces.get(i);
            gridAdd(grid, cell, p[0], p[1], 2 * i);
            gridAdd(grid, cell, p[p.length - 2], p[p.length - 1], 2 * i + 1);
        }
        boolean[] used = new boolean[n];
        List<Chain> chains = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            if (used[s]) {
                continue;
            }
            used[s] = true;
            List<double[]> seq = new ArrayList<>(); // oriented polylines
            List<Integer> ids = new ArrayList<>();
            seq.add(pieces.get(s));
            ids.add(s);
            double[] first = pieces.get(s);
            double hx = first[0];
            double hy = first[1];
            double tx = first[first.length - 2];
            double ty = first[first.length - 1];
            boolean closed = false;
            while (true) {
                if (Math.hypot(tx - hx, ty - hy) <= tol && (seq.size() > 1 || first.length > 4)) {
                    closed = true;
                    break;
                }
                deadline.check();
                int code = gridFind(grid, cell, pieces, used, tx, ty, tol);
                if (code < 0) {
                    break;
                }
                int j = code / 2;
                used[j] = true;
                double[] p = code % 2 == 0 ? pieces.get(j) : reverse(pieces.get(j));
                seq.add(p);
                ids.add(j);
                tx = p[p.length - 2];
                ty = p[p.length - 1];
            }
            while (!closed) {
                deadline.check();
                int code = gridFind(grid, cell, pieces, used, hx, hy, tol);
                if (code < 0) {
                    break;
                }
                int j = code / 2;
                used[j] = true;
                double[] p = code % 2 == 1 ? pieces.get(j) : reverse(pieces.get(j));
                seq.add(0, p);
                ids.add(0, j);
                hx = p[0];
                hy = p[1];
                closed = Math.hypot(tx - hx, ty - hy) <= tol;
            }
            Pts out = new Pts();
            double[] box = emptyBox();
            for (int k = 0; k < seq.size(); k++) {
                double[] p = seq.get(k);
                for (int i = k == 0 ? 0 : 2; i < p.length; i += 2) {
                    out.add(p[i], p[i + 1]);
                }
                int id = ids.get(k);
                if (boxes != null) {
                    extend(box, boxes.get(id));
                }
                else {
                    extendPts(box, p);
                }
            }
            if (closed && out.size() > 1) {
                out.removeLast();
            }
            chains.add(new Chain(out.toArray(), closed, ids, box));
        }
        return chains;
    }

    private static long key(long ix, long iy) {
        return (ix << 32) ^ (iy & 0xffffffffL);
    }

    private static void gridAdd(Map<Long, List<Integer>> grid, double cell, double x, double y, int code) {
        long k = key((long) Math.floor(x / cell), (long) Math.floor(y / cell));
        grid.computeIfAbsent(k, kk -> new ArrayList<>(2)).add(code);
    }

    /** The smallest unused end code (piece·2 + end) within tol of (x, y), or -1. */
    private static int gridFind(Map<Long, List<Integer>> grid, double cell, List<double[]> pieces, boolean[] used,
            double x, double y, double tol) {
        long ix = (long) Math.floor(x / cell);
        long iy = (long) Math.floor(y / cell);
        int best = -1;
        for (long dx = -1; dx <= 1; dx++) {
            for (long dy = -1; dy <= 1; dy++) {
                List<Integer> l = grid.get(key(ix + dx, iy + dy));
                if (l == null) {
                    continue;
                }
                for (int code : l) {
                    if (used[code / 2] || (best >= 0 && code >= best)) {
                        continue;
                    }
                    double[] p = pieces.get(code / 2);
                    double px = code % 2 == 0 ? p[0] : p[p.length - 2];
                    double py = code % 2 == 0 ? p[1] : p[p.length - 1];
                    if (Math.hypot(px - x, py - y) <= tol) {
                        best = code;
                    }
                }
            }
        }
        return best;
    }

    public static double[] reverse(double[] p) {
        double[] r = new double[p.length];
        for (int i = 0; i < p.length; i += 2) {
            r[p.length - 2 - i] = p[i];
            r[p.length - 1 - i] = p[i + 1];
        }
        return r;
    }

    // ---- polygons ------------------------------------------------------------------------------------------------

    /** Signed area (positive = counter-clockwise in a Y-up frame). */
    public static double polygonArea(double[] p) {
        double a = 0;
        int n = p.length / 2;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            a += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1];
        }
        return a / 2;
    }

    public static boolean pointInPolygon(double x, double y, double[] p) {
        boolean in = false;
        int n = p.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = p[2 * i];
            double yi = p[2 * i + 1];
            double xj = p[2 * j];
            double yj = p[2 * j + 1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                in = !in;
            }
        }
        return in;
    }

    /**
     * Iterative Douglas–Peucker (an explicit stack, no recursion): keeps the points farther than tol from the
     * simplified line. A closed polygon keeps at least 3 points.
     */
    public static double[] simplify(double[] p, double tol, boolean closed) {
        try {
            return simplify(p, tol, closed, Deadline.none());
        }
        catch (SExprException e) {
            throw new IllegalStateException(e); // Deadline.none() never runs out
        }
    }

    /**
     * {@link #simplify(double[], double, boolean)} under the import's deadline: a zigzag whose amplitude grows makes
     * Douglas–Peucker quadratic (each split peels off one point), so the deadline is checked as the work grows.
     */
    public static double[] simplify(double[] p, double tol, boolean closed, Deadline deadline) throws SExprException {
        int n = p.length / 2;
        if (n <= (closed ? 3 : 2)) {
            return p.clone();
        }
        double[] q = p;
        int m = n;
        if (closed) {
            q = Arrays.copyOf(p, p.length + 2);
            q[p.length] = p[0];
            q[p.length + 1] = p[1];
            m = n + 1;
        }
        boolean[] keep = new boolean[m];
        keep[0] = true;
        keep[m - 1] = true;
        int[] stack = new int[64];
        int sp = 0;
        stack[sp++] = 0;
        stack[sp++] = m - 1;
        long work = 0;
        while (sp > 0) {
            int j = stack[--sp];
            int i = stack[--sp];
            work += j - i;
            if (work >= 1 << 20) {
                work = 0;
                deadline.check();
            }
            double best = -1;
            int bk = -1;
            for (int k = i + 1; k < j; k++) {
                double d = segmentDistance(q[2 * k], q[2 * k + 1], q[2 * i], q[2 * i + 1], q[2 * j], q[2 * j + 1]);
                if (d > best) {
                    best = d;
                    bk = k;
                }
            }
            if (bk >= 0 && best > tol) {
                keep[bk] = true;
                if (sp + 4 > stack.length) {
                    stack = Arrays.copyOf(stack, stack.length * 2);
                }
                stack[sp++] = i;
                stack[sp++] = bk;
                stack[sp++] = bk;
                stack[sp++] = j;
            }
        }
        if (closed) {
            keep[m - 1] = false; // the repeated first point
            int kept = 0;
            for (int k = 0; k < m; k++) {
                kept += keep[k] ? 1 : 0;
            }
            for (int k = 1; kept < 3 && k < n; k++) { // a sliver: keep enough points to stay a polygon
                if (!keep[k]) {
                    keep[k] = true;
                    kept++;
                }
            }
        }
        Pts out = new Pts();
        for (int k = 0; k < m; k++) {
            if (keep[k]) {
                out.add(q[2 * k], q[2 * k + 1]);
            }
        }
        return out.toArray();
    }

    /** Distance from (px, py) to the segment a–b. */
    public static double segmentDistance(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /** Convex hull (Andrew's monotone chain), counter-clockwise, without repeated or collinear points. */
    public static double[] convexHull(double[] p) {
        int n = p.length / 2;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> p[2 * a] != p[2 * b] ? Double.compare(p[2 * a], p[2 * b])
                : Double.compare(p[2 * a + 1], p[2 * b + 1]));
        int[] h = new int[2 * n + 1];
        int k = 0;
        for (int t = 0; t < n; t++) {
            int i = idx[t];
            while (k >= 2 && cross(p, h[k - 2], h[k - 1], i) <= 0) {
                k--;
            }
            h[k++] = i;
        }
        for (int t = n - 2, lo = k + 1; t >= 0; t--) {
            int i = idx[t];
            while (k >= lo && cross(p, h[k - 2], h[k - 1], i) <= 0) {
                k--;
            }
            h[k++] = i;
        }
        int m = Math.max(0, k - 1);
        double[] out = new double[2 * m];
        for (int i = 0; i < m; i++) {
            out[2 * i] = p[2 * h[i]];
            out[2 * i + 1] = p[2 * h[i] + 1];
        }
        return out;
    }

    private static double cross(double[] p, int o, int a, int b) {
        return (p[2 * a] - p[2 * o]) * (p[2 * b + 1] - p[2 * o + 1])
                - (p[2 * a + 1] - p[2 * o + 1]) * (p[2 * b] - p[2 * o]);
    }

    /** True when every turn of the polygon goes the same way (collinear points allowed). */
    public static boolean isConvex(double[] p) {
        int n = p.length / 2;
        int sign = 0;
        for (int i = 0; i < n; i++) {
            double c = cross(p, i, (i + 1) % n, (i + 2) % n);
            if (Math.abs(c) < 1e-12) {
                continue;
            }
            int s = c > 0 ? 1 : -1;
            if (sign != 0 && s != sign) {
                return false;
            }
            sign = s;
        }
        return true;
    }

    /**
     * The minimum-area rectangle enclosing the points (rotating calipers over the convex hull): {cx, cy, w, h, angle}
     * with angle in degrees, (−45, 45], the direction of the w side. Exact for a rectangle. O(n log n): the hull points
     * farthest along, across and back along each edge only move forward as the edges turn.
     */
    public static double[] minAreaRect(double[] pts) {
        double[] h = convexHull(pts);
        int n = h.length / 2;
        if (n < 3) {
            double[] box = emptyBox();
            extendPts(box, pts);
            return new double[] {(box[0] + box[2]) / 2, (box[1] + box[3]) / 2, box[2] - box[0], box[3] - box[1], 0};
        }
        double bestArea = Double.POSITIVE_INFINITY;
        double[] best = null;
        int right = -1; // the hull points of largest u, largest v and smallest u; -1: find them afresh
        int top = -1;
        int left = -1;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double ex = h[2 * j] - h[2 * i];
            double ey = h[2 * j + 1] - h[2 * i + 1];
            double len = Math.hypot(ex, ey);
            if (len < 1e-12) {
                right = -1;
                continue;
            }
            double ux = ex / len;
            double uy = ey / len;
            if (right < 0) {
                right = farthest(h, ux, uy);
                top = farthest(h, -uy, ux);
                left = farthest(h, -ux, -uy);
            }
            else {
                right = walk(h, right, ux, uy);
                top = walk(h, top, -uy, ux);
                left = walk(h, left, -ux, -uy);
            }
            // the hull is counter-clockwise, so every point lies on the left of the edge: its own v is the smallest
            double minU = h[2 * left] * ux + h[2 * left + 1] * uy;
            double maxU = h[2 * right] * ux + h[2 * right + 1] * uy;
            double minV = Math.min(-h[2 * i] * uy + h[2 * i + 1] * ux, -h[2 * j] * uy + h[2 * j + 1] * ux);
            double maxV = -h[2 * top] * uy + h[2 * top + 1] * ux;
            double area = (maxU - minU) * (maxV - minV);
            if (area < bestArea - 1e-12) {
                bestArea = area;
                double cu = (minU + maxU) / 2;
                double cv = (minV + maxV) / 2;
                best = new double[] {cu * ux - cv * uy, cu * uy + cv * ux, maxU - minU, maxV - minV,
                        Math.toDegrees(Math.atan2(uy, ux))};
            }
        }
        double a = normDeg(best[4]);
        double w = best[2];
        double hh = best[3];
        while (a > 45 + 1e-9) {
            a -= 90;
            double t = w;
            w = hh;
            hh = t;
        }
        while (a <= -45 + 1e-9) {
            a += 90;
            double t = w;
            w = hh;
            hh = t;
        }
        return new double[] {best[0], best[1], w, hh, a};
    }

    /** The point of h farthest in the direction (dx, dy). */
    private static int farthest(double[] h, double dx, double dy) {
        int k = 0;
        for (int i = 1; i < h.length / 2; i++) {
            if (h[2 * i] * dx + h[2 * i + 1] * dy > h[2 * k] * dx + h[2 * k + 1] * dy) {
                k = i;
            }
        }
        return k;
    }

    /**
     * From the farthest point of a counter-clockwise convex polygon for an earlier direction, the farthest point for
     * (dx, dy) turned counter-clockwise from it: walk forward while the next point is at least as far.
     */
    private static int walk(double[] h, int k, double dx, double dy) {
        int n = h.length / 2;
        for (int steps = 0; steps < n; steps++) {
            int next = (k + 1) % n;
            if (h[2 * next] * dx + h[2 * next + 1] * dy < h[2 * k] * dx + h[2 * k + 1] * dy) {
                break;
            }
            k = next;
        }
        return k;
    }

    // ---- numbers -------------------------------------------------------------------------------------------------

    /** Rounded to 1e-6; −0 becomes 0. */
    public static double round6(double v) {
        double r = Math.round(v * 1e6) / 1e6;
        return r == 0 ? 0.0 : r;
    }

    /** Degrees normalised to (−180, 180]. */
    public static double normDeg(double a) {
        double r = a % 360.0;
        if (r <= -180) {
            r += 360;
        }
        else if (r > 180) {
            r -= 360;
        }
        return r == 0 ? 0.0 : r;
    }

    /** Radians normalised to [0, 2π). */
    public static double norm2pi(double a) {
        double r = a % (2 * Math.PI);
        return r < 0 ? r + 2 * Math.PI : r;
    }
}
