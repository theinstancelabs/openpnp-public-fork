package org.openpnp.instancelabs.board;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.instancelabs.kicad.KGeom;

/**
 * SVG path data of the board art (design §4.1): board millimetres rounded to a fixed number of decimals, absolute
 * commands only (M L H V A Z), an implicit L after a point, −0 written as 0, points that round onto the previous one
 * left out. The same input always gives the same text. Also the filled shapes both kinds of art draw ({@link Shape})
 * and the thumbnail writer; {@link #parse} reads path data back (outline loops, tests).
 */
public final class ArtPath {
    /** Decimals of the drawing (0.01 mm) and of the thumbnail (0.1 mm). */
    public static final int DECIMALS = 2;
    public static final int THUMB_DECIMALS = 1;
    /** The thumbnail's tolerance (mm). */
    public static final double THUMB_MM = 0.1;
    /** Polygons smaller than this (mm²) are not drawn. */
    public static final double MIN_AREA = 0.0004;
    /** In the thumbnail, pads in one row closer than this become one bar (0.25 mm gaps are a third of a pixel). */
    static final double THUMB_JOIN_MM = 0.3;

    private final StringBuilder sb = new StringBuilder();
    private final int decimals;
    private final double scale;
    private long startX;
    private long startY;
    private long lastX;
    private long lastY;
    private boolean open;      // a subpath has started and is not closed
    private char prev;         // the last command: M, L (also implicit), H, V, A, Z; 0 at the start
    private long points;

    public ArtPath() {
        this(DECIMALS);
    }

    public ArtPath(int decimals) {
        this.decimals = decimals;
        this.scale = Math.pow(10, decimals);
    }

    private long units(double v) {
        return Math.round(v * scale);
    }

    public ArtPath moveTo(double x, double y) {
        long ux = units(x);
        long uy = units(y);
        sb.append('M');
        num(ux);
        sb.append(' ');
        num(uy);
        startX = lastX = ux;
        startY = lastY = uy;
        open = true;
        prev = 'M';
        points++;
        return this;
    }

    /** A line to (x, y): H or V when one coordinate stays, an implicit L after a point, nothing for a repeat. */
    public ArtPath lineTo(double x, double y) {
        if (!open) {
            return moveTo(x, y);
        }
        long ux = units(x);
        long uy = units(y);
        if (ux == lastX && uy == lastY) {
            return this;
        }
        if (uy == lastY) {
            sb.append('H');
            num(ux);
            prev = 'H';
        }
        else if (ux == lastX) {
            sb.append('V');
            num(uy);
            prev = 'V';
        }
        else {
            sb.append(prev == 'M' || prev == 'L' ? ' ' : 'L');
            num(ux);
            sb.append(' ');
            num(uy);
            prev = 'L';
        }
        lastX = ux;
        lastY = uy;
        points++;
        return this;
    }

    /**
     * A circular arc of radius r to (x, y). sweep = true turns counter-clockwise in the board frame (+Y up: SVG's
     * positive-angle direction in the path's own coordinates).
     */
    public ArtPath arcTo(double r, boolean large, boolean sweep, double x, double y) {
        long ux = units(x);
        long uy = units(y);
        if (!open || ux == lastX && uy == lastY) {
            return open ? this : moveTo(x, y);
        }
        long ur = Math.max(1, units(r));
        sb.append('A');
        num(ur);
        sb.append(' ');
        num(ur);
        sb.append(" 0 ").append(large ? '1' : '0').append(' ').append(sweep ? '1' : '0').append(' ');
        num(ux);
        sb.append(' ');
        num(uy);
        lastX = ux;
        lastY = uy;
        prev = 'A';
        points++;
        return this;
    }

    public ArtPath close() {
        if (open) {
            sb.append('Z');
            open = false;
            lastX = startX;
            lastY = startY;
            prev = 'Z';
        }
        return this;
    }

    /** A closed polygon (x0 y0 x1 y1 …, first point not repeated); nothing when it rounds to fewer than 3 points. */
    public ArtPath polygon(double[] p) {
        if (p.length < 6) {
            return this;
        }
        int n = p.length / 2;
        // a last point that rounds onto the first is left out (Z closes)
        long fx = units(p[0]);
        long fy = units(p[1]);
        while (n > 1 && units(p[2 * n - 2]) == fx && units(p[2 * n - 1]) == fy) {
            n--;
        }
        int mark = sb.length();
        long before = points;
        char prevBefore = prev;
        moveTo(p[0], p[1]);
        for (int i = 1; i < n; i++) {
            lineTo(p[2 * i], p[2 * i + 1]);
        }
        if (points - before < 3) { // a sliver at this precision
            sb.setLength(mark);
            points = before;
            open = false;
            prev = prevBefore;
            return this;
        }
        return close();
    }

    /** An open polyline. */
    public ArtPath polyline(double[] p) {
        if (p.length < 4) {
            return this;
        }
        moveTo(p[0], p[1]);
        for (int i = 2; i + 1 < p.length; i += 2) {
            lineTo(p[i], p[i + 1]);
        }
        open = false; // the next shape starts with M
        prev = 0;
        return this;
    }

    /**
     * A rectangle centred on (cx, cy), turned by angleDeg (counter-clockwise), with corners of radius r drawn as
     * arcs; counter-clockwise like every filled shape of the art, so merged shapes add up (nonzero rule). r is
     * clamped to half the shorter side: r = min(w, h) / 2 draws a stadium.
     */
    public ArtPath roundRect(double cx, double cy, double w, double h, double r, double angleDeg) {
        double hw = w / 2;
        double hh = h / 2;
        double rr = Math.max(0, Math.min(r, Math.min(hw, hh)));
        boolean arcs = units(rr) > 0;
        if (!arcs) {
            rr = 0;
        }
        double a = Math.toRadians(angleDeg);
        double c = Math.cos(a);
        double s = Math.sin(a);
        double[][] q = {{hw, -hh + rr}, {hw, hh - rr}, {hw - rr, hh}, {-hw + rr, hh}, {-hw, hh - rr},
                {-hw, -hh + rr}, {-hw + rr, -hh}, {hw - rr, -hh}};
        double[] x = new double[8];
        double[] y = new double[8];
        for (int i = 0; i < 8; i++) {
            x[i] = cx + q[i][0] * c - q[i][1] * s;
            y[i] = cy + q[i][0] * s + q[i][1] * c;
        }
        moveTo(x[0], y[0]);
        for (int i = 1; i < 8; i += 2) { // a side, then the corner after it
            int next = (i + 1) % 8;
            if (arcs) {
                lineTo(x[i], y[i]);
                arcTo(rr, false, true, x[next], y[next]);
            }
            else if (next != 0) { // square corners: the last side is Z's
                lineTo(x[i], y[i]);
            }
        }
        return close();
    }

    /** A full circle as two arcs, counter-clockwise. */
    public ArtPath circle(double cx, double cy, double r) {
        if (units(r) <= 0) {
            return this;
        }
        moveTo(cx + r, cy);
        arcTo(r, true, true, cx - r, cy);
        arcTo(r, true, true, cx + r, cy);
        return close();
    }

    /** Appends another path's data (a new subpath must follow it). */
    public ArtPath append(ArtPath other) {
        sb.append(other.sb);
        points += other.points;
        open = false;
        prev = 0;
        return this;
    }

    public boolean isEmpty() {
        return sb.length() == 0;
    }

    public int length() {
        return sb.length();
    }

    /** Points written (each M, L, H, V and arc end). */
    public long points() {
        return points;
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    private void num(long u) {
        if (u == 0) {
            sb.append('0');
            return;
        }
        long a = Math.abs(u);
        if (u < 0) {
            sb.append('-');
        }
        long one = (long) scale;
        sb.append(a / one);
        long frac = a % one;
        if (frac == 0) {
            return;
        }
        sb.append('.');
        String f = Long.toString(frac);
        for (int i = f.length(); i < decimals; i++) {
            sb.append('0');
        }
        int end = f.length();
        while (end > 0 && f.charAt(end - 1) == '0') {
            end--;
        }
        sb.append(f, 0, end);
    }

    /** One number as the art writes it (0.01 mm by default): the same rounding and text as the paths. */
    public static String format(double v, int decimals) {
        ArtPath p = new ArtPath(decimals);
        p.num(p.units(v));
        return p.sb.toString();
    }

    /** v rounded as the art rounds it; −0 becomes 0. */
    public static double round(double v, int decimals) {
        double s = Math.pow(10, decimals);
        double r = Math.round(v * s) / s;
        return r == 0 ? 0.0 : r;
    }

    // ---- shapes and the thumbnail ------------------------------------------------------------------------------

    /** A filled shape in the board frame: a rounded rectangle, a circle, or polygons (counter-clockwise). */
    static final class Shape {
        static final int RECT = 0;
        static final int CIRCLE = 1;
        static final int POLY = 2;

        final int kind;
        double cx;
        double cy;
        double w;
        double h;
        double r;
        double angle;
        final List<double[]> polys;

        private Shape(int kind, List<double[]> polys) {
            this.kind = kind;
            this.polys = polys;
        }

        static Shape rect(double cx, double cy, double w, double h, double r, double angle) {
            Shape s = new Shape(RECT, null);
            s.cx = cx;
            s.cy = cy;
            s.w = w;
            s.h = h;
            s.r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
            s.angle = angle;
            return s;
        }

        static Shape circle(double cx, double cy, double r) {
            Shape s = new Shape(CIRCLE, null);
            s.cx = cx;
            s.cy = cy;
            s.r = r;
            return s;
        }

        static Shape poly(List<double[]> polys) {
            return new Shape(POLY, polys);
        }

        /** True when (x, y) lies in the shape (on its edge included, within 1e-6 mm). */
        boolean contains(double x, double y) {
            double e = 1e-6;
            switch (kind) {
                case RECT: {
                    double a = Math.toRadians(angle);
                    double dx = x - cx;
                    double dy = y - cy;
                    double u = Math.abs(dx * Math.cos(a) + dy * Math.sin(a));
                    double v = Math.abs(-dx * Math.sin(a) + dy * Math.cos(a));
                    if (u > w / 2 + e || v > h / 2 + e) {
                        return false;
                    }
                    double ou = u - (w / 2 - r);
                    double ov = v - (h / 2 - r);
                    return ou <= 0 || ov <= 0 || Math.hypot(ou, ov) <= r + e;
                }
                case CIRCLE:
                    return Math.hypot(x - cx, y - cy) <= r + e;
                default:
                    for (double[] q : polys) {
                        if (KGeom.pointInPolygon(x, y, q)) {
                            return true;
                        }
                    }
                    return false;
            }
        }

        /**
         * Points around the shape (x0 y0 x1 y1 …) that enclose it: a rectangle's corners, 16 points on a circle's
         * circumscribed polygon, a polygon's vertices.
         */
        double[] around() {
            switch (kind) {
                case RECT: {
                    double a = Math.toRadians(angle);
                    double c = Math.cos(a);
                    double s = Math.sin(a);
                    double[] p = new double[8];
                    for (int i = 0; i < 4; i++) {
                        double u = (i == 0 || i == 3 ? -1 : 1) * w / 2;
                        double v = (i < 2 ? -1 : 1) * h / 2;
                        p[2 * i] = cx + u * c - v * s;
                        p[2 * i + 1] = cy + u * s + v * c;
                    }
                    return p;
                }
                case CIRCLE: {
                    double[] p = new double[32];
                    double rr = r / Math.cos(Math.PI / 16);
                    for (int i = 0; i < 16; i++) {
                        p[2 * i] = cx + rr * Math.cos(Math.PI * i / 8);
                        p[2 * i + 1] = cy + rr * Math.sin(Math.PI * i / 8);
                    }
                    return p;
                }
                default: {
                    KGeom.Pts p = new KGeom.Pts();
                    for (double[] q : polys) {
                        for (int i = 0; i + 1 < q.length; i += 2) {
                            p.add(q[i], q[i + 1]);
                        }
                    }
                    return p.toArray();
                }
            }
        }

        /** Draws the shape into p; returns the points written. */
        long emit(ArtPath p) {
            long before = p.points();
            switch (kind) {
                case RECT:
                    p.roundRect(cx, cy, w, h, r, angle);
                    break;
                case CIRCLE:
                    p.circle(cx, cy, r);
                    break;
                default:
                    for (double[] q : polys) {
                        if (Math.abs(KGeom.polygonArea(q)) >= MIN_AREA) {
                            p.polygon(q);
                        }
                    }
                    break;
            }
            return p.points() - before;
        }

        /** At the thumbnail's 0.1 mm: rectangles without arcs, round shapes as circles, polygons simplified. */
        void emitThumb(ArtPath p) {
            switch (kind) {
                case RECT:
                    if (Math.abs(w - h) < THUMB_MM && r >= Math.min(w, h) / 2 - THUMB_MM / 2 && w >= 0.5) {
                        p.circle(cx, cy, (w + h) / 4);
                    }
                    else {
                        p.roundRect(cx, cy, w, h, 0, angle);
                    }
                    break;
                case CIRCLE:
                    if (r >= 0.25) {
                        p.circle(cx, cy, r);
                    }
                    else {
                        p.roundRect(cx, cy, 2 * r, 2 * r, 0, 0);
                    }
                    break;
                default:
                    for (double[] q : polys) {
                        double[] s = KGeom.simplify(q, THUMB_MM, true);
                        if (Math.abs(KGeom.polygonArea(s)) >= THUMB_MM * THUMB_MM) {
                            p.polygon(s);
                        }
                    }
                    break;
            }
        }
    }

    /**
     * The thumbnail of filled shapes (0.1 mm): axis-aligned rectangles snapped to the grid and joined along rows
     * and columns when less than 0.3 mm apart (fine-pitch pads become bars, as a few pixels show them), every
     * other shape as {@link Shape#emitThumb}.
     */
    static String thumb(List<Shape> shapes) {
        ArtPath p = new ArtPath(THUMB_DECIMALS);
        List<long[]> boxes = new ArrayList<>();
        for (Shape s : shapes) {
            double a = KGeom.normDeg(s.angle);
            boolean square = Math.abs(a % 90) < 1e-6;
            boolean round = Math.abs(s.w - s.h) < THUMB_MM && s.r >= Math.min(s.w, s.h) / 2 - THUMB_MM / 2;
            if (s.kind == Shape.RECT && square && !(round && s.w >= 0.5)) {
                boolean turned = Math.abs(Math.abs(a) - 90) < 1e-6;
                double w = turned ? s.h : s.w;
                double h = turned ? s.w : s.h;
                boxes.add(new long[] {Math.round((s.cx - w / 2) * 10), Math.round((s.cy - h / 2) * 10),
                        Math.round((s.cx + w / 2) * 10), Math.round((s.cy + h / 2) * 10)});
            }
            else {
                s.emitThumb(p);
            }
        }
        long join = Math.round(THUMB_JOIN_MM * 10);
        boxes = joinBoxes(boxes, join, 0);
        boxes = joinBoxes(boxes, join, 1);
        for (long[] b : boxes) {
            if (b[2] > b[0] && b[3] > b[1]) {
                p.roundRect((b[0] + b[2]) / 20.0, (b[1] + b[3]) / 20.0, (b[2] - b[0]) / 10.0,
                        (b[3] - b[1]) / 10.0, 0, 0);
            }
        }
        return p.toString();
    }

    /**
     * Joins boxes (x0, y0, x1, y1 in grid units) that share their extent across the axis (axis 0: rows of equal y0,
     * y1) and lie at most gap apart along it; deterministic order (sorted).
     */
    static List<long[]> joinBoxes(List<long[]> boxes, long gap, int axis) {
        int a0 = axis == 0 ? 0 : 1;
        int a1 = a0 + 2;
        int c0 = axis == 0 ? 1 : 0;
        int c1 = c0 + 2;
        List<long[]> sorted = new ArrayList<>(boxes);
        sorted.sort((p, q) -> p[c0] != q[c0] ? Long.compare(p[c0], q[c0])
                : p[c1] != q[c1] ? Long.compare(p[c1], q[c1])
                : p[a0] != q[a0] ? Long.compare(p[a0], q[a0]) : Long.compare(p[a1], q[a1]));
        List<long[]> out = new ArrayList<>();
        long[] cur = null;
        for (long[] b : sorted) {
            if (cur != null && cur[c0] == b[c0] && cur[c1] == b[c1] && b[a0] - cur[a1] <= gap) {
                cur[a1] = Math.max(cur[a1], b[a1]);
            }
            else {
                cur = b.clone();
                out.add(cur);
            }
        }
        return out;
    }

    // ---- reading -------------------------------------------------------------------------------------------------

    /** One subpath read back: its points (an arc counts by its end point) and whether Z closed it. */
    public static final class Sub {
        public final double[] pts;
        public final boolean closed;

        Sub(double[] pts, boolean closed) {
            this.pts = pts;
            this.closed = closed;
        }
    }

    /**
     * Reads path data written with M L H V A Z (absolute) back into subpaths.
     *
     * @throws IllegalArgumentException on anything else
     */
    public static List<Sub> parse(String d) {
        List<Sub> out = new ArrayList<>();
        if (d == null) {
            return out;
        }
        Reader r = new Reader(d);
        KGeom.Pts cur = null;
        double cx = 0;
        double cy = 0;
        double sx = 0;
        double sy = 0;
        char cmd = 0;
        while (true) {
            r.skipSpace();
            if (r.end()) {
                break;
            }
            char c = r.peek();
            if (Character.isLetter(c)) {
                cmd = c;
                r.pos++;
                if (cmd == 'Z') {
                    if (cur == null) {
                        throw new IllegalArgumentException("Z without a subpath at " + r.pos);
                    }
                    out.add(new Sub(cur.toArray(), true));
                    cur = null;
                    cx = sx;
                    cy = sy;
                    continue;
                }
            }
            else if (cmd == 'M') {
                cmd = 'L'; // implicit L after M
            }
            else if (cmd == 0 || cmd == 'Z') {
                throw new IllegalArgumentException("a number without a command at " + r.pos);
            }
            switch (cmd) {
                case 'M':
                    if (cur != null) {
                        out.add(new Sub(cur.toArray(), false));
                    }
                    cur = new KGeom.Pts();
                    cx = r.num();
                    cy = r.num();
                    sx = cx;
                    sy = cy;
                    cur.add(cx, cy);
                    break;
                case 'L':
                    cx = r.num();
                    cy = r.num();
                    add(cur, cx, cy, r);
                    break;
                case 'H':
                    cx = r.num();
                    add(cur, cx, cy, r);
                    break;
                case 'V':
                    cy = r.num();
                    add(cur, cx, cy, r);
                    break;
                case 'A':
                    for (int i = 0; i < 5; i++) {
                        r.num();
                    }
                    cx = r.num();
                    cy = r.num();
                    add(cur, cx, cy, r);
                    break;
                default:
                    throw new IllegalArgumentException("unexpected command " + cmd + " at " + r.pos);
            }
        }
        if (cur != null) {
            out.add(new Sub(cur.toArray(), false));
        }
        return out;
    }

    private static void add(KGeom.Pts cur, double x, double y, Reader r) {
        if (cur == null) {
            throw new IllegalArgumentException("a point before M at " + r.pos);
        }
        cur.add(x, y);
    }

    private static final class Reader {
        final String s;
        int pos;

        Reader(String s) {
            this.s = s;
        }

        boolean end() {
            return pos >= s.length();
        }

        char peek() {
            return s.charAt(pos);
        }

        void skipSpace() {
            while (pos < s.length() && (s.charAt(pos) == ' ' || s.charAt(pos) == ',')) {
                pos++;
            }
        }

        double num() {
            skipSpace();
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                pos++;
            }
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos || pos == start + 1 && !Character.isDigit(s.charAt(start))) {
                throw new IllegalArgumentException("a number expected at " + start);
            }
            double v = Double.parseDouble(s.substring(start, pos));
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("not a finite number at " + start);
            }
            return v;
        }
    }
}
