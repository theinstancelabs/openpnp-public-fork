package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-98: the machine collision model, {@code <config>/instancelabs/collision-model.json} (collision-model-design.md
 * §2): the bed and its plane, every object on it, every tool's layers and the see-saw. Read from the file's JSON tree,
 * which is kept as written, so keys this build does not read (notes, lumen-sim's) survive a rewrite. {@link #parse} is
 * the loader's and every write's validation: the first problem throws, in words.
 *
 * <p>Frames: X/Y in machine mm (head X/Y = raw X/Y), heights above the bed plane. The plane is the least-squares fit
 * rc(x, y) of {@code bed.plane.points} [x, y, rawN1]. N1's tip is h1 = g1 (min(r, k1) - rc), the needle's h2 = g2
 * ((K - rc) - max(r, k2)), each at its own tip's X/Y (§2 "Tip heights on a tilted bed"). Unknown inputs are the worst
 * case: an unknown raw Z or a null knee puts that tip at its lowest end stop, a null K leaves the needle's height
 * unknown (minus infinity: it meets everything under it).</p>
 */
public final class BedModel {
    public static final int SCHEMA = 1;
    public static final String BED_ID = "bed";
    public static final List<String> STATES = Arrays.asList("measured", "added", "moved", "removed");
    /** Circles are swept as their circumscribed 64-gon (design §2, both implementations). */
    static final int CIRCLE_SIDES = 64;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final JsonObject json;
    public final int version;
    public final double zMarginMm, contactDepthMm;
    private final double planeA, planeB, planeC;
    final double planeErrMm;
    final Long planeHomeAtMs;
    final double rLo, rHi, g1, g2, homeRepeatMm, seesawErrMm;
    final Double k1, k2, kRaw;
    final Long kHomeAtMs, seesawHomeAtMs;
    private final List<Obj> obstacles = new ArrayList<>();
    private final List<Tool> tools = new ArrayList<>();

    /** A convex polygon, counter-clockwise, with its bounding box. */
    public static final class Poly {
        final double[] x, y;
        final double minX, minY, maxX, maxY;

        Poly(double[] x, double[] y) {
            this.x = x;
            this.y = y;
            double a = Double.POSITIVE_INFINITY, b = Double.POSITIVE_INFINITY, c = Double.NEGATIVE_INFINITY,
                    d = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < x.length; i++) {
                a = Math.min(a, x[i]);
                b = Math.min(b, y[i]);
                c = Math.max(c, x[i]);
                d = Math.max(d, y[i]);
            }
            minX = a;
            minY = b;
            maxX = c;
            maxY = d;
        }

        static Poly box(double x0, double y0, double x1, double y1) {
            return new Poly(new double[] {x0, x1, x1, x0}, new double[] {y0, y0, y1, y1});
        }

        static Poly circle(double cx, double cy, double r) {
            double[] x = new double[CIRCLE_SIDES], y = new double[CIRCLE_SIDES];
            double out = r / Math.cos(Math.PI / CIRCLE_SIDES);
            for (int i = 0; i < CIRCLE_SIDES; i++) {
                double a = 2 * Math.PI * i / CIRCLE_SIDES;
                x[i] = cx + out * Math.cos(a);
                y[i] = cy + out * Math.sin(a);
            }
            return new Poly(x, y);
        }

        public int size() {
            return x.length;
        }

        public double x(int i) {
            return x[i];
        }

        public double y(int i) {
            return y[i];
        }

        Poly shifted(double dx, double dy) {
            double[] a = x.clone(), b = y.clone();
            for (int i = 0; i < a.length; i++) {
                a[i] += dx;
                b[i] += dy;
            }
            return new Poly(a, b);
        }

        /** The point reflection (still counter-clockwise). */
        Poly negated() {
            double[] a = x.clone(), b = y.clone();
            for (int i = 0; i < a.length; i++) {
                a[i] = -a[i];
                b[i] = -b[i];
            }
            return new Poly(a, b);
        }

        /** Inside or on an edge. */
        boolean contains(double px, double py) {
            int n = x.length;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                if ((x[j] - x[i]) * (py - y[i]) - (y[j] - y[i]) * (px - x[i]) < -1e-9) {
                    return false;
                }
            }
            return true;
        }

        /** Every vertex of {@code o} inside this one (both convex: all of it). */
        public boolean contains(Poly o) {
            for (int i = 0; i < o.x.length; i++) {
                if (!contains(o.x[i], o.y[i])) {
                    return false;
                }
            }
            return true;
        }

        /** Distance from a point, 0 inside. */
        double distance(double px, double py) {
            return distance(px, py, px, py);
        }

        /** Distance from the segment (ax, ay)-(bx, by), 0 where they meet. */
        double distance(double ax, double ay, double bx, double by) {
            if (contains(ax, ay) || contains(bx, by)) {
                return 0;
            }
            double d = Double.POSITIVE_INFINITY;
            int n = x.length;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double o1 = cross(ax, ay, bx, by, x[i], y[i]), o2 = cross(ax, ay, bx, by, x[j], y[j]);
                double o3 = cross(x[i], y[i], x[j], y[j], ax, ay), o4 = cross(x[i], y[i], x[j], y[j], bx, by);
                if (o1 * o2 < 0 && o3 * o4 < 0) {
                    return 0;
                }
                d = Math.min(d, Math.min(pointSegment(ax, ay, x[i], y[i], x[j], y[j]),
                        Math.min(pointSegment(bx, by, x[i], y[i], x[j], y[j]), pointSegment(x[i], y[i], ax, ay, bx,
                                by))));
            }
            return d;
        }

        /** The Minkowski sum of two convex counter-clockwise polygons (edges merged by angle). */
        static Poly minkowski(Poly a, Poly b) {
            int n = a.size(), m = b.size(), i0 = lowest(a), j0 = lowest(b);
            double[] rx = new double[n + m], ry = new double[n + m];
            int i = 0, j = 0, k = 0;
            while (i < n || j < m) {
                int p = (i0 + i) % n, q = (j0 + j) % m;
                rx[k] = a.x[p] + b.x[q];
                ry[k++] = a.y[p] + b.y[q];
                double cr = (a.x[(p + 1) % n] - a.x[p]) * (b.y[(q + 1) % m] - b.y[q])
                        - (a.y[(p + 1) % n] - a.y[p]) * (b.x[(q + 1) % m] - b.x[q]);
                if (j >= m || i < n && cr > 0) {
                    i++;
                }
                else if (i >= n || cr < 0) {
                    j++;
                }
                else {
                    i++;
                    j++;
                }
            }
            return new Poly(Arrays.copyOf(rx, k), Arrays.copyOf(ry, k));
        }

        private static int lowest(Poly p) {
            int best = 0;
            for (int i = 1; i < p.x.length; i++) {
                if (p.y[i] < p.y[best] || p.y[i] == p.y[best] && p.x[i] < p.x[best]) {
                    best = i;
                }
            }
            return best;
        }
    }

    static double cross(double ax, double ay, double bx, double by, double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    static double pointSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return Math.hypot(px - ax - t * dx, py - ay - t * dy);
    }

    /** An obstacle: an object of the file, or the bed (id {@value #BED_ID}, top 0). */
    public static final class Obj {
        public final String id, name, kind, state;
        public final Poly poly;
        public final Double topZ;
        public final double marginMm, topErrMm;
        public final boolean touchable;

        Obj(String id, String name, String kind, String state, Poly poly, Double topZ, double marginMm, double topErrMm,
                boolean touchable) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.state = state;
            this.poly = poly;
            this.topZ = topZ;
            this.marginMm = marginMm;
            this.topErrMm = topErrMm;
            this.touchable = touchable;
        }

        /** Not measured (a null top, or added, moved or removed): it blocks its outline at every height. */
        public boolean blocksEveryHeight() {
            return topZ == null || !"measured".equals(state);
        }
    }

    /** A tool's layer: its outline relative to the head (a tip-end outline moved by the tool's tipEnd). */
    public static final class Layer {
        public final String name;
        /** {@code at: tip-end}: the only kind of layer an exemption may cover. */
        public final boolean tip;
        public final Poly poly;
        public final Double aboveTipMm, bottomZ;
        /** One per obstacle, in {@link #obstacles()} order. */
        Pair[] pairs;

        Layer(String name, boolean tip, Poly poly, Double aboveTipMm, Double bottomZ) {
            this.name = name;
            this.tip = tip;
            this.poly = poly;
            this.aboveTipMm = aboveTipMm;
            this.bottomZ = bottomZ;
        }
    }

    public static final class Tool {
        public final String id;
        /** n1, needle (the see-saw map it rides) or fixed. */
        public final String moves;
        /** The tip's end relative to the head; NaN on a fixed tool without one. */
        public final double tipDx, tipDy;
        /** When that end was measured ({@code tipEnd.atMs}); null when the file does not say. */
        public final Long tipEndAtMs;
        public final List<Layer> layers;

        Tool(String id, String moves, double tipDx, double tipDy, Long tipEndAtMs, List<Layer> layers) {
            this.id = id;
            this.moves = moves;
            this.tipDx = tipDx;
            this.tipDy = tipDy;
            this.tipEndAtMs = tipEndAtMs;
            this.layers = layers;
        }

        public boolean rides() {
            return !"fixed".equals(moves);
        }
    }

    /**
     * A layer against an obstacle, worked out once: the head may not come within the growth of {@link #cspace} (the
     * obstacle grown by the layer turned round), and the layer lies inside the obstacle exactly where n . head <= room
     * for every edge of the obstacle.
     */
    static final class Pair {
        final Poly cspace;
        final double[] nx, ny, room;

        Pair(Poly obstacle, Poly layer) {
            cspace = Poly.minkowski(obstacle, layer.negated());
            int n = obstacle.size();
            nx = new double[n];
            ny = new double[n];
            room = new double[n];
            for (int k = 0; k < n; k++) {
                int j = (k + 1) % n;
                double ex = obstacle.x[j] - obstacle.x[k], ey = obstacle.y[j] - obstacle.y[k], l = Math.hypot(ex, ey);
                nx[k] = ey / l;
                ny[k] = -ex / l;
                double support = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < layer.size(); i++) {
                    support = Math.max(support, nx[k] * layer.x[i] + ny[k] * layer.y[i]);
                }
                room[k] = nx[k] * obstacle.x[k] + ny[k] * obstacle.y[k] - support;
            }
        }

        /** The layer, with the head anywhere within {@code errMm} of (hx, hy), lies inside the obstacle. */
        boolean holds(double hx, double hy, double errMm) {
            for (int k = 0; k < room.length; k++) {
                if (nx[k] * hx + ny[k] * hy > room[k] - errMm + 1e-9) {
                    return false;
                }
            }
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------

    public static BedModel parse(String json) {
        JsonElement e;
        try {
            e = new JsonParser().parse(json);
        }
        catch (RuntimeException x) {
            throw new IllegalArgumentException("not JSON: " + x.getMessage());
        }
        if (e == null || !e.isJsonObject()) {
            throw new IllegalArgumentException("the model is a JSON object");
        }
        return new BedModel(e.getAsJsonObject());
    }

    /** Validates a copy of the tree. */
    public static BedModel parse(JsonObject json) {
        return parse(json.toString());
    }

    /** A copy of the tree as written. */
    public JsonObject json() {
        return new JsonParser().parse(json.toString()).getAsJsonObject();
    }

    private BedModel(JsonObject j) {
        this.json = j;
        if (integer(j, "schema", "") != SCHEMA) {
            throw new IllegalArgumentException("schema must be " + SCHEMA);
        }
        version = integer(j, "version", "");
        if (version < 0) {
            throw new IllegalArgumentException("version must be 0 or more");
        }
        JsonObject frame = object(j, "frame", "");
        if (!"machine".equals(text(frame, "xy", "frame")) || !"above-bed".equals(text(frame, "z", "frame"))) {
            throw new IllegalArgumentException("frame must be {\"xy\": \"machine\", \"z\": \"above-bed\"}");
        }
        zMarginMm = atLeast0(j, "zMarginMm", "");
        Double depth = optional(j, "contactDepthMm", "");
        contactDepthMm = depth == null ? 0.25 : depth;
        if (contactDepthMm < 0 || contactDepthMm > 1.0) {
            // the cap of both loaders, the engine's and lumen-sim's (Infra and Backend 11-04)
            throw new IllegalArgumentException("contactDepthMm must be 0 to 1.0");
        }
        JsonObject bed = object(j, "bed", "");
        JsonObject plane = object(bed, "plane", "bed");
        List<DispenseConfig.SurfacePoint> pts = new ArrayList<>();
        for (JsonElement p : array(plane, "points", "bed.plane")) {
            double[] v = numbers(p, 3, "bed.plane.points");
            pts.add(new DispenseConfig.SurfacePoint(v[0], v[1], v[2]));
        }
        SurfacePlane fit;
        try {
            fit = SurfacePlane.fit(pts);
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bed.plane.points: at least 3 points [x, y, rawN1] not in a line ("
                    + e.getMessage() + ")");
        }
        planeA = fit.b;
        planeB = fit.c;
        planeC = fit.a;
        planeErrMm = atLeast0(plane, "errMm", "bed.plane");
        planeHomeAtMs = optionalLong(plane, "homeAtMs", "bed.plane");
        obstacles.add(new Obj(BED_ID, "the bed", "fixed", "measured", outline(bed.get("outline"), "bed.outline"), 0.0,
                0, 0, false));
        Set<String> ids = new HashSet<>();
        JsonArray objs = array(j, "objects", "");
        for (int i = 0; i < objs.size(); i++) {
            obstacles.add(readObject(objs.get(i), "objects[" + i + "]", ids));
        }
        JsonObject s = object(j, "seesaw", "");
        double[] stops = numbers(s.get("endStopsRaw"), 2, "seesaw.endStopsRaw");
        if (!(stops[0] < stops[1])) {
            throw new IllegalArgumentException("seesaw.endStopsRaw: [low, high] with low < high");
        }
        rLo = stops[0];
        rHi = stops[1];
        JsonObject n1 = object(s, "n1", "seesaw"), needle = object(s, "needle", "seesaw");
        k1 = optional(n1, "kneeRaw", "seesaw.n1");
        k2 = optional(needle, "kneeRaw", "seesaw.needle");
        g1 = positive(n1, "mmPerRaw", "seesaw.n1");
        g2 = positive(needle, "mmPerRaw", "seesaw.needle");
        JsonObject k = object(s, "K", "seesaw");
        kRaw = optional(k, "raw", "seesaw.K");
        kHomeAtMs = optionalLong(k, "homeAtMs", "seesaw.K");
        homeRepeatMm = atLeast0(s, "homeRepeatMm", "seesaw");
        JsonObject measured = object(s, "measured", "seesaw");
        how(measured, "seesaw.measured");
        seesawErrMm = atLeast0(measured, "errMm", "seesaw.measured");
        seesawHomeAtMs = optionalLong(measured, "homeAtMs", "seesaw.measured");
        Set<String> toolIds = new HashSet<>();
        JsonArray ts = array(j, "tools", "");
        for (int i = 0; i < ts.size(); i++) {
            tools.add(readTool(ts.get(i), "tools[" + i + "]", toolIds));
        }
        int n1s = 0, needles = 0;
        for (Tool t : tools) {
            n1s += "n1".equals(t.moves) ? 1 : 0;
            needles += "needle".equals(t.moves) ? 1 : 0;
        }
        if (n1s != 1 || needles != 1) {
            throw new IllegalArgumentException("tools: exactly one with moves n1 and one with moves needle (here " + n1s
                    + " and " + needles + "): every leg sweeps both tips");
        }
        for (Tool t : tools) {
            for (Layer l : t.layers) {
                l.pairs = new Pair[obstacles.size()];
                for (int o = 0; o < obstacles.size(); o++) {
                    l.pairs[o] = new Pair(obstacles.get(o).poly, l.poly);
                }
            }
        }
    }

    private static Obj readObject(JsonElement e, String path, Set<String> ids) {
        if (e == null || !e.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        JsonObject o = e.getAsJsonObject();
        String id = text(o, "id", path);
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException(path + ": the id '" + id + "' must be 1-64 of A-Z a-z 0-9 . _ -");
        }
        path = path + " (" + id + ")";
        if (BED_ID.equals(id)) {
            throw new IllegalArgumentException(path + ": the id '" + BED_ID + "' is reserved for the bed");
        }
        if (!ids.add(id)) {
            throw new IllegalArgumentException("objects: the id '" + id + "' is there twice");
        }
        String kind = text(o, "kind", path);
        if (!"fixed".equals(kind) && !"movable".equals(kind)) {
            throw new IllegalArgumentException(path + ": kind is fixed or movable");
        }
        String state = text(o, "state", path);
        if (!STATES.contains(state)) {
            throw new IllegalArgumentException(path + ": state is measured, added, moved or removed");
        }
        Poly poly = outline(o.get("outline"), path + ".outline");
        Double top = optional(o, "topZ", path);
        double margin = atLeast0(o, "marginMm", path);
        double topErr = 0;
        if ("measured".equals(state) && top != null) {
            JsonObject m = o.has("measured") && o.get("measured").isJsonObject() ? o.getAsJsonObject("measured") : null;
            JsonElement t = m == null ? null : m.get("top");
            if (t == null || !t.isJsonObject()) {
                throw new IllegalArgumentException(path + ": a measured object with a topZ needs measured.top {how, "
                        + "errMm, ...}");
            }
            how(t.getAsJsonObject(), path + ".measured.top");
            topErr = atLeast0(t.getAsJsonObject(), "errMm", path + ".measured.top");
        }
        String name = o.get("name") != null && o.get("name").isJsonPrimitive() ? o.get("name").getAsString() : id;
        return new Obj(id, name, kind, state, poly, top, margin, topErr, flag(o, "touchable", path));
    }

    private static Tool readTool(JsonElement e, String path, Set<String> ids) {
        if (e == null || !e.isJsonObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        JsonObject o = e.getAsJsonObject();
        String id = text(o, "id", path);
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException(path + ": the id '" + id + "' must be 1-64 of A-Z a-z 0-9 . _ -");
        }
        path = path + " (" + id + ")";
        if (!ids.add(id)) {
            throw new IllegalArgumentException("tools: the id '" + id + "' is there twice");
        }
        String moves = text(o, "moves", path);
        if (!"n1".equals(moves) && !"needle".equals(moves) && !"fixed".equals(moves)) {
            throw new IllegalArgumentException(path + ": moves is n1, needle or fixed");
        }
        boolean rides = !"fixed".equals(moves);
        double dx = Double.NaN, dy = Double.NaN;
        Long tipAt = null;
        JsonElement te = o.get("tipEnd");
        if (te != null && te.isJsonObject()) {
            dx = number(te.getAsJsonObject(), "dx", path + ".tipEnd");
            dy = number(te.getAsJsonObject(), "dy", path + ".tipEnd");
            tipAt = optionalLong(te.getAsJsonObject(), "atMs", path + ".tipEnd");
        }
        else if (rides) {
            throw new IllegalArgumentException(path + ": tipEnd {dx, dy} is required for a tool that rides the see-saw "
                    + "(its height is taken at its tip)");
        }
        List<Layer> layers = new ArrayList<>();
        Set<String> names = new HashSet<>();
        JsonArray ls = array(o, "layers", path);
        if (ls.size() == 0) {
            throw new IllegalArgumentException(path + ": at least one layer");
        }
        for (int i = 0; i < ls.size(); i++) {
            String lp = path + ".layers[" + i + "]";
            if (!ls.get(i).isJsonObject()) {
                throw new IllegalArgumentException(lp + " must be an object");
            }
            JsonObject l = ls.get(i).getAsJsonObject();
            String name = text(l, "name", lp);
            lp = lp + " (" + name + ")";
            if (!names.add(name)) {
                throw new IllegalArgumentException(path + ": the layer '" + name + "' is there twice");
            }
            String at = text(l, "at", lp);
            if (!"tip-end".equals(at) && !"head".equals(at)) {
                throw new IllegalArgumentException(lp + ": at is tip-end or head");
            }
            boolean tip = "tip-end".equals(at);
            if (tip && Double.isNaN(dx)) {
                throw new IllegalArgumentException(lp + ": a tip-end layer needs the tool's tipEnd");
            }
            Double above = optional(l, "aboveTipMm", lp), bottom = optional(l, "bottomZ", lp);
            if ((above == null) == (bottom == null)) {
                throw new IllegalArgumentException(lp + ": aboveTipMm or bottomZ, exactly one");
            }
            if (above != null && !rides) {
                throw new IllegalArgumentException(lp + ": a fixed tool's layer has bottomZ (it does not ride the "
                        + "see-saw)");
            }
            if (above != null && above < 0) {
                throw new IllegalArgumentException(lp + ": aboveTipMm must be 0 or more");
            }
            Poly poly = outline(l.get("outline"), lp + ".outline");
            layers.add(new Layer(name, tip, tip ? poly.shifted(dx, dy) : poly, above, bottom));
        }
        return new Tool(id, moves, dx, dy, tipAt, Collections.unmodifiableList(layers));
    }

    /** {box: [x0, y0, x1, y1]}, {circle: [cx, cy, r]} or {polygon: [[x, y], ...]} convex, counter-clockwise. */
    static Poly outline(JsonElement e, String path) {
        if (e == null || !e.isJsonObject()) {
            throw new IllegalArgumentException(path + ": an outline is {box}, {circle} or {polygon}");
        }
        JsonObject o = e.getAsJsonObject();
        int kinds = (o.has("box") ? 1 : 0) + (o.has("circle") ? 1 : 0) + (o.has("polygon") ? 1 : 0);
        if (kinds != 1) {
            throw new IllegalArgumentException(path + ": one of box, circle or polygon");
        }
        if (o.has("box")) {
            double[] b = numbers(o.get("box"), 4, path + ".box");
            if (!(b[0] < b[2]) || !(b[1] < b[3])) {
                throw new IllegalArgumentException(path + ": a box is [x0, y0, x1, y1] with x0 < x1 and y0 < y1");
            }
            return Poly.box(b[0], b[1], b[2], b[3]);
        }
        if (o.has("circle")) {
            double[] c = numbers(o.get("circle"), 3, path + ".circle");
            if (!(c[2] > 0)) {
                throw new IllegalArgumentException(path + ": a circle is [cx, cy, r] with a radius > 0");
            }
            return Poly.circle(c[0], c[1], c[2]);
        }
        JsonElement pe = o.get("polygon");
        if (!pe.isJsonArray() || pe.getAsJsonArray().size() < 3) {
            throw new IllegalArgumentException(path + ": a polygon is at least 3 points [x, y]");
        }
        JsonArray a = pe.getAsJsonArray();
        double[] x = new double[a.size()], y = new double[a.size()];
        for (int i = 0; i < a.size(); i++) {
            double[] p = numbers(a.get(i), 2, path + ".polygon");
            x[i] = p[0];
            y[i] = p[1];
        }
        double area = 0, turn = 0;
        boolean left = false, right = false;
        int n = x.length;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n, k = (i + 2) % n;
            area += x[i] * y[j] - x[j] * y[i];
            double ax = x[j] - x[i], ay = y[j] - y[i], bx = x[k] - x[j], by = y[k] - y[j];
            double cr = ax * by - ay * bx;
            left |= cr > 1e-9;
            right |= cr < -1e-9;
            turn += Math.atan2(cr, ax * bx + ay * by);
        }
        if (left && right || Math.abs(Math.abs(turn) - 2 * Math.PI) > 1e-6) {
            throw new IllegalArgumentException(path + ": the polygon is not convex (the writer splits a concave outline "
                    + "into convex ones)");
        }
        if (!(area > 0)) {
            throw new IllegalArgumentException(path + ": the polygon's points must run counter-clockwise");
        }
        return new Poly(x, y);
    }

    // ------------------------------------------------------------------------------------------
    // The model
    // ------------------------------------------------------------------------------------------

    /** The bed first (id {@value #BED_ID}), then the file's objects in order. */
    public List<Obj> obstacles() {
        return Collections.unmodifiableList(obstacles);
    }

    public List<Tool> tools() {
        return Collections.unmodifiableList(tools);
    }

    public Obj object(String id) {
        for (Obj o : obstacles) {
            if (o.id.equals(id)) {
                return o;
            }
        }
        return null;
    }

    public Tool tool(String id) {
        for (Tool t : tools) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }

    /** N1's contact raw with the bed plane at (x, y), in the frame of the plane's home. */
    public double rc(double x, double y) {
        return planeA * x + planeB * y + planeC;
    }

    /**
     * The tool's tip above the bed at its own tip X/Y, the head at (headX, headY) and raw Z {@code rawZ} (null:
     * unknown). NaN for a fixed tool; minus infinity for the needle without K.
     */
    public double tipZ(Tool t, Double rawZ, double headX, double headY) {
        if (!t.rides()) {
            return Double.NaN;
        }
        double rc = rc(headX + t.tipDx, headY + t.tipDy);
        if ("n1".equals(t.moves)) {
            return g1 * ((rawZ == null || k1 == null ? rLo : Math.min(rawZ, k1)) - rc);
        }
        if (kRaw == null) {
            return Double.NEGATIVE_INFINITY;
        }
        return g2 * ((kRaw - rc) - (rawZ == null || k2 == null ? rHi : Math.max(rawZ, k2)));
    }

    /** Whether the tool's height follows its raw Z: its knee known, and K for the needle (else: its worst case). */
    public boolean mapKnown(Tool t) {
        return "n1".equals(t.moves) ? k1 != null : "needle".equals(t.moves) && k2 != null && kRaw != null;
    }

    /**
     * The error of a tool's computed height: the see-saw's and the plane's measurement errors, plus the home-to-home
     * repeat for each raw value taken under another home than {@code homeAtMs} (the plane, the knees, and K twice: a
     * shifted frame moves K by twice the shift).
     */
    public double tipErrMm(Tool t, Long homeAtMs) {
        if (!t.rides()) {
            return 0;
        }
        int others = (differs(planeHomeAtMs, homeAtMs) ? 1 : 0) + (differs(seesawHomeAtMs, homeAtMs) ? 1 : 0)
                + ("needle".equals(t.moves) && differs(kHomeAtMs, homeAtMs) ? 2 : 0);
        return seesawErrMm + planeErrMm + others * homeRepeatMm;
    }

    private static boolean differs(Long a, Long b) {
        return a == null || b == null || !a.equals(b);
    }

    // ------------------------------------------------------------------------------------------
    // Field readers (the first problem throws with its path)
    // ------------------------------------------------------------------------------------------

    private static String at(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static JsonObject object(JsonObject o, String key, String path) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonObject()) {
            throw new IllegalArgumentException(at(path, key) + " is required (an object)");
        }
        return e.getAsJsonObject();
    }

    private static JsonArray array(JsonObject o, String key, String path) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) {
            throw new IllegalArgumentException(at(path, key) + " is required (a list)");
        }
        return e.getAsJsonArray();
    }

    private static String text(JsonObject o, String key, String path) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || e.getAsString().trim().isEmpty()) {
            throw new IllegalArgumentException(at(path, key) + " is required (text)");
        }
        return e.getAsString().trim();
    }

    /** A measurement record says how it was measured: any words (Process's per-part methods, never a fixed list). */
    private static void how(JsonObject o, String path) {
        JsonElement e = o.get("how");
        if (e == null || !e.isJsonPrimitive() || e.getAsString().trim().isEmpty()) {
            throw new IllegalArgumentException(path + ".how must say how it was measured");
        }
    }

    private static boolean flag(JsonObject o, String key, String path) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return false;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(at(path, key) + " is true or false");
        }
        return e.getAsBoolean();
    }

    private static Double optional(JsonObject o, String key, String path) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !Double.isFinite(e.getAsDouble())) {
            throw new IllegalArgumentException(at(path, key) + " must be a number or null");
        }
        return e.getAsDouble();
    }

    private static Long optionalLong(JsonObject o, String key, String path) {
        Double d = optional(o, key, path);
        return d == null ? null : Long.valueOf(Math.round(d));
    }

    private static double number(JsonObject o, String key, String path) {
        Double d = optional(o, key, path);
        if (d == null) {
            throw new IllegalArgumentException(at(path, key) + ": a number is required");
        }
        return d;
    }

    private static double atLeast0(JsonObject o, String key, String path) {
        double d = number(o, key, path);
        if (d < 0) {
            throw new IllegalArgumentException(at(path, key) + ": a number >= 0 is required");
        }
        return d;
    }

    private static double positive(JsonObject o, String key, String path) {
        double d = number(o, key, path);
        if (!(d > 0)) {
            throw new IllegalArgumentException(at(path, key) + ": a number > 0 is required");
        }
        return d;
    }

    private static int integer(JsonObject o, String key, String path) {
        double d = number(o, key, path);
        if (d != Math.rint(d) || Math.abs(d) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(at(path, key) + " must be a whole number");
        }
        return (int) d;
    }

    private static double[] numbers(JsonElement e, int n, String path) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() != n) {
            throw new IllegalArgumentException(path + ": " + n + " numbers");
        }
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            JsonElement x = e.getAsJsonArray().get(i);
            if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isNumber() || !Double.isFinite(x.getAsDouble())) {
                throw new IllegalArgumentException(path + ": " + n + " numbers");
            }
            v[i] = x.getAsDouble();
        }
        return v;
    }
}
