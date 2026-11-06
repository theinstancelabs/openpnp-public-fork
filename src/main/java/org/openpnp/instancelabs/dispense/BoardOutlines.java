package org.openpnp.instancelabs.dispense;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BR-81: the outline of every board on the bed in machine X/Y, and the paste places that lie on one. A registration's
 * board is its outline ({@link BoardRegistration#boardRectMm}; for a record made before it, the smaller box of its
 * fiducials) through its affine, a parallelogram (the fit has scale and shear; a bottom-side board is mirrored by it).
 * A tile ({@link DispenseConfig#tiles}) is its own edge; its boards only name the nearest one, since places lie in the
 * gaps between them. A point is on a board within the margin ({@code outputZones.marginMm}) of its outline; a sink
 * place (the waste, the towel, a cup) keeps its circle ({@code placeRadiusMm}) more, the room its pile and the hold's
 * drool take (Process, 11-04). An outline that is not a number refuses every point. Every stored registration counts as
 * on the bed: one that left it is forgotten (DELETE /api/board-registrations/{id}).
 */
public final class BoardOutlines {
    /** A paste place on a board: the place, its kind, where it is (a box: {@link #rect}), the board and the text. */
    public static final class Place {
        /** "waste", "waste-test-dot", "purge row", "calibration area", "coupon1". */
        public final String place;
        /** place | test-dot | purge-row | calibration-area | coupon */
        public final String kind;
        public final double x, y;
        /** The calibration area's or a coupon's box {x0, y0, x1, y1}; null for a point. */
        public final double[] rect;
        public final String board, text;

        Place(String place, String kind, double x, double y, double[] rect, String board, String text) {
            this.place = place;
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.rect = rect;
            this.board = board;
            this.text = text;
        }
    }

    private static final class Outline {
        final String label, kind, id;
        /** When it was recorded (the wall clock, for the status), and its place in the order of outlines. */
        final long atMs, order;
        final boolean fromFiducials;
        final List<double[]> edge;
        /** A tile's boards by name, to name the nearest; none for a registration. */
        final Map<String, List<double[]>> boards;

        Outline(String label, String kind, String id, long atMs, long order, boolean fromFiducials, List<double[]> edge,
                Map<String, List<double[]>> boards) {
            this.label = label;
            this.kind = kind;
            this.id = id;
            this.atMs = atMs;
            this.order = order;
            this.fromFiducials = fromFiducials;
            this.edge = edge;
            this.boards = boards;
        }

        /** "the Nano tile (board G3)": the board of the tile nearest the shape. */
        String name(List<double[]> shape) {
            String near = null;
            double best = Double.MAX_VALUE;
            for (Map.Entry<String, List<double[]>> b : boards.entrySet()) {
                double d = distance(b.getValue(), shape);
                if (d < best) {
                    best = d;
                    near = b.getKey();
                }
            }
            return near == null ? label : label + " (board " + near + ")";
        }
    }

    private final DispenseConfig c;
    private final List<Outline> outlines = new ArrayList<>();

    private BoardOutlines(DispenseConfig c) {
        this.c = c;
    }

    public static BoardOutlines of(DispenseConfig c) {
        BoardOutlines o = new BoardOutlines(c);
        for (BoardRegistration r : c.boardRegistrations.values()) {
            double[] b = r == null || !r.isAffineValid() ? null
                    : r.boardRectMm != null ? r.boardRectMm : fiducialBox(r);
            if (b == null || b.length != 4) {
                continue;
            }
            AffineTransform t = r.transform();
            List<double[]> edge = new ArrayList<>();
            for (double[] p : new double[][] {{b[0], b[1]}, {b[2], b[1]}, {b[2], b[3]}, {b[0], b[3]}}) {
                double[] m = new double[2];
                t.transform(p, 0, m, 0, 1);
                edge.add(m);
            }
            o.outlines.add(new Outline("board '" + r.boardName + "' (registration " + r.shortId() + ")",
                    "registration", r.id, r.atMs, r.outlineOrder(), r.boardRectMm == null, edge,
                    Collections.emptyMap()));
        }
        for (Map.Entry<String, DispenseConfig.Tile> e : c.tiles.entrySet()) {
            DispenseConfig.Tile t = e.getValue();
            if (t == null || t.problem() != null) {
                continue; // CONFIG refuses every run and the hold while a stored tile does not hold together
            }
            Map<String, List<double[]>> boards = new LinkedHashMap<>();
            for (DispenseConfig.TileBoard b : t.boards) {
                boards.put(b.name, b.outline);
            }
            o.outlines.add(new Outline("the " + t.label, "tile", e.getKey(), t.atMs, t.outlineOrder(), false,
                    t.outline, boards));
        }
        return o;
    }

    /** The box of a registration's fiducials in board mm, or null without any. */
    private static double[] fiducialBox(BoardRegistration r) {
        List<double[]> at = new ArrayList<>();
        for (BoardRegistration.Fid f : r.fiducials == null ? new ArrayList<BoardRegistration.Fid>() : r.fiducials) {
            if (f != null) {
                at.add(new double[] {f.bx, f.by});
            }
        }
        return bounds(at);
    }

    /** The box {x0, y0, x1, y1} around the points, or null without any. */
    static double[] bounds(List<double[]> pts) {
        double[] b = null;
        for (double[] p : pts) {
            b = b == null ? new double[] {p[0], p[1], p[0], p[1]}
                    : new double[] {Math.min(b[0], p[0]), Math.min(b[1], p[1]), Math.max(b[2], p[0]),
                            Math.max(b[3], p[1])};
        }
        return b;
    }

    /** The board (x, y) lies on or within {@code margin} of: its name ("the Nano tile (board G3)"), or null. */
    public String on(double x, double y, double margin) {
        return on(x, y, margin, null, null);
    }

    /**
     * As above, among the outlines after {@code afterMs} in the order of outlines only (null: all of them) but the
     * registration {@code exceptId} (a plan's own board; null: none).
     */
    public String on(double x, double y, double margin, Long afterMs, String exceptId) {
        return on(Collections.singletonList(new double[] {x, y}), margin, afterMs, exceptId);
    }

    /** The board the box overlaps or comes within {@code margin} of (the calibration area, a coupon), or null. */
    public String on(double x0, double y0, double x1, double y1, double margin) {
        return on(box(x0, y0, x1, y1), margin, null, null);
    }

    private String on(List<double[]> shape, double margin, Long afterMs, String exceptId) {
        for (Outline o : outlines) {
            boolean except = exceptId != null && "registration".equals(o.kind) && exceptId.equals(o.id);
            if (!except && (afterMs == null || o.order > afterMs) && !(distance(o.edge, shape) > margin)) {
                return o.name(shape);
            }
        }
        return null;
    }

    /** The outlines for the status: label, kind (registration | tile), id, atMs, fromFiducials. */
    public List<Map<String, Object>> view() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Outline o : outlines) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", o.label);
            m.put("kind", o.kind);
            m.put("id", o.id);
            m.put("atMs", o.atMs);
            m.put("fromFiducials", o.fromFiducials);
            l.add(m);
        }
        return l;
    }

    /**
     * The paste places on a board, each once, within {@code outputZones.marginMm} (a sink: and its circle): the waste,
     * the towel, a cup, the blank and any place with a probed surface of its own name (a dot ladder's, a touch-off's;
     * not the board surface's), the test dot's spot (when the waste is not on one already), the purge row by its first
     * spot on one (every spot counts, not only the unused ones), the calibration area and each coupon by their boxes.
     */
    public List<Place> placesOnBoards() {
        List<Place> l = new ArrayList<>();
        if (outlines.isEmpty()) {
            return l;
        }
        DispenseConfig.OutputZoneSettings zs = c.outputZones != null ? c.outputZones
                : new DispenseConfig.OutputZoneSettings();
        double m = zs.marginMm, sink = zs.marginMm + zs.placeRadiusMm;
        for (Map.Entry<String, DispenseConfig.NamedLocation> e : c.locations.entrySet()) {
            String n = e.getKey();
            DispenseConfig.NamedLocation p = e.getValue();
            boolean isSink = OutputZones.isSinkPlace(c, n);
            boolean paste = isSink || n.equals(c.blankLocationName) || c.surface(n) != null
                    && !n.equals(c.boardSurfaceName) && !isCoupon(n);
            String b = p == null || !paste ? null : on(p.x, p.y, isSink ? sink : m);
            if (b != null) {
                String where = !isSink || on(p.x, p.y, m) != null ? "lies on " + b : String.format(Locale.ROOT,
                        "lies within %.1f mm of %s (its %.1f mm circle and the %.1f mm margin)", sink, b,
                        zs.placeRadiusMm, m);
                l.add(new Place(n, "place", p.x, p.y, null, b, String.format(Locale.ROOT, "%s (%.1f, %.1f) %s: set it "
                        + "again off the board (Machine › Places)", n.equals(c.wasteLocationName) ? "the waste"
                                : "the place '" + n + "'", p.x, p.y, where)));
            }
        }
        DispenseConfig.NamedLocation waste = c.location(c.wasteLocationName);
        if (waste != null && on(waste.x, waste.y, sink) == null) {
            double x = waste.x + c.testDotOffsetXMm, y = waste.y + c.testDotOffsetYMm;
            String b = on(x, y, m);
            if (b != null) {
                l.add(new Place(DispenseService.WASTE_TEST_DOT, "test-dot", x, y, null, b, String.format(Locale.ROOT,
                        "the test dot's spot (%.1f, %.1f) lies on %s: set the waste again off the board (Machine › "
                                + "Places)", x, y, b)));
            }
        }
        DispenseConfig.PurgeRow row = c.purgeRow;
        for (int i = 0; row != null && row.isDefined() && i < row.spots; i++) {
            double[] s = row.spot(i);
            String b = on(s[0], s[1], m);
            if (b != null) {
                l.add(new Place("purge row", "purge-row", s[0], s[1], null, b, String.format(Locale.ROOT, "the purge "
                        + "row's spot %d of %d (%.1f, %.1f) lies on %s: set the row again off the board (Machine › "
                        + "Places)", i + 1, row.spots, s[0], s[1], b)));
                break;
            }
        }
        CalibrationArea a = c.calibrationArea == null ? null : c.calibrationArea.effective(c);
        if (a != null) {
            double[] r = {a.x0Mm, a.y0Mm, a.x0Mm + a.widthMm, a.y0Mm + a.heightMm};
            String b = on(r[0], r[1], r[2], r[3], m);
            if (b != null) {
                l.add(new Place("calibration area", "calibration-area", r[0], r[1], r, b, String.format(Locale.ROOT,
                        "the calibration area (%.1f..%.1f, %.1f..%.1f) lies on %s: move it off the board "
                                + "(Calibrate › Pad calibration)", r[0], r[2], r[1], r[3], b)));
            }
        }
        if (c.padCalibration != null && c.padCalibration.couponPlaces != null) {
            for (String place : c.padCalibration.couponPlaces) {
                double[] r = place == null ? null : c.padCalibration.couponRect(c, place);
                String b = r == null ? null : on(r[0], r[1], r[2], r[3], m);
                if (b != null) {
                    DispenseConfig.NamedLocation p = c.location(place);
                    l.add(new Place(place, "coupon", p.x, p.y, r, b, String.format(Locale.ROOT, "the coupon place '%s' "
                            + "(%.1f, %.1f) lies on %s: set the coupon place again off the board (Machine › Places)",
                            place, p.x, p.y, b)));
                }
            }
        }
        return l;
    }

    /** A weigh coupon's place: listed by its box. */
    private boolean isCoupon(String place) {
        return c.padCalibration != null && c.padCalibration.couponPlaces != null
                && c.padCalibration.couponPlaces.contains(place);
    }

    // --- plane geometry (machine mm) -------------------------------------------------------------

    static List<double[]> box(double x0, double y0, double x1, double y1) {
        List<double[]> l = new ArrayList<>();
        l.add(new double[] {x0, y0});
        l.add(new double[] {x1, y0});
        l.add(new double[] {x1, y1});
        l.add(new double[] {x0, y1});
        return l;
    }

    /** 0 where the polygon and the shape (a point or a polygon) overlap, else the gap between them. */
    static double distance(List<double[]> poly, List<double[]> shape) {
        for (double[] p : shape) {
            if (inside(poly, p[0], p[1])) {
                return 0;
            }
        }
        for (double[] p : poly) {
            if (shape.size() >= 3 && inside(shape, p[0], p[1])) {
                return 0;
            }
        }
        double d = Double.MAX_VALUE;
        for (int i = 0; i < poly.size(); i++) {
            double[] a = poly.get(i), b = poly.get((i + 1) % poly.size());
            for (int j = 0; j < shape.size(); j++) {
                double[] p = shape.get(j), q = shape.get((j + 1) % shape.size());
                if (shape.size() >= 2 && crosses(a, b, p, q)) {
                    return 0;
                }
                d = Math.min(d, Math.min(toSegment(p, a, b), shape.size() >= 2 ? toSegment(a, p, q) : d));
            }
        }
        return d;
    }

    /** Even-odd rule (a polygon that does not cross itself). */
    static boolean inside(List<double[]> poly, double x, double y) {
        boolean in = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            double[] a = poly.get(i), b = poly.get(j);
            if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) {
                in = !in;
            }
        }
        return in;
    }

    private static double toSegment(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2));
        return Math.hypot(a[0] + t * dx - p[0], a[1] + t * dy - p[1]);
    }

    /** The segments a-b and p-q meet (touching counts). */
    static boolean crosses(double[] a, double[] b, double[] p, double[] q) {
        double d1 = side(p, q, a), d2 = side(p, q, b), d3 = side(a, b, p), d4 = side(a, b, q);
        if ((d1 > 0) != (d2 > 0) && (d3 > 0) != (d4 > 0) && d1 != 0 && d2 != 0 && d3 != 0 && d4 != 0) {
            return true;
        }
        return d1 == 0 && toSegment(a, p, q) == 0 || d2 == 0 && toSegment(b, p, q) == 0 || d3 == 0
                && toSegment(p, a, b) == 0 || d4 == 0 && toSegment(q, a, b) == 0;
    }

    private static double side(double[] a, double[] b, double[] p) {
        return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
    }

    /**
     * Why {@code pts} is no polygon of 3..{@code max} points the engine can test a place against, or null: each point
     * two finite numbers within 1000 mm, no edge crossing another, at least 1 mm² inside.
     */
    static String polygonProblem(List<double[]> pts, int max) {
        if (pts == null || pts.size() < 3 || pts.size() > max) {
            return "needs 3 to " + max + " [x, y] points";
        }
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            double[] a = pts.get(i);
            if (a == null || a.length != 2 || !(Math.abs(a[0]) <= 1000 && Math.abs(a[1]) <= 1000)) {
                return "point " + (i + 1) + " must be [x, y], numbers within 1000 mm";
            }
        }
        double area = 0;
        for (int i = 0; i < n; i++) {
            double[] a = pts.get(i), b = pts.get((i + 1) % n);
            area += a[0] * b[1] - b[0] * a[1];
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                boolean adjacent = j == i + 1 || i == 0 && j == n - 1;
                if (!adjacent && crosses(pts.get(i), pts.get((i + 1) % n), pts.get(j), pts.get((j + 1) % n))) {
                    return "crosses itself (edges " + (i + 1) + " and " + (j + 1) + ")";
                }
            }
        }
        return Math.abs(area) / 2 < 1 ? "encloses less than 1 mm²" : null;
    }
}
