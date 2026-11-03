package org.openpnp.instancelabs.dispense;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Where N1 touches a board or the blank: deterministic, pure. Board targets are bare spots near the
 * corners and the centre of the dots (or an operator's proven spots for the board file, like the
 * bench's (8,8), (67,8), (67,67), (8,67), (37,37) on ftp), kept off the pads (from the pad edge, plus
 * N1's measured 0.4 mm offset error), the fiducial masks, unknown pads and the board art's holes,
 * inside the board outline. Without art a mounting hole is assumed in each corner of the outline
 * (ftp's M3 rings sit there, and the first real registration's corner target and both its 2 mm
 * alternates landed on one). More points only where dots would otherwise lie outside the probed area
 * (a 9-point bench map had a 0.048 mm residual, over the 0.04 limit; its 5-point maps 0.015-0.039).
 * Blank targets lie either side of the purge row, close to where the purge dots go (the blank is
 * bowed).
 */
public final class ProbeTargets {
    private ProbeTargets() {
    }

    /** The allowed region is the board outline shrunk by this (mm). */
    public static final double EDGE_MM = 2.0;
    /** Without an outline: the box of the pads and fiducials grown by this (mm). */
    public static final double BOX_GROW_MM = 3.0;
    /** A pad without geometry is kept out as a circle of this radius plus the pad keep-out (mm). */
    public static final double UNKNOWN_PAD_RADIUS_MM = 1.5;
    public static final double GRID_MM = 0.25;
    /** A target is searched this far from its ideal point (mm). */
    public static final double SEARCH_MM = 6.0;
    /** Targets closer than this are duplicates (mm). */
    public static final double DUPLICATE_MM = 3.0;
    public static final int MAX_TARGETS = 9;
    /** A coverage point starts this far outward of the worst dot (mm). */
    public static final double COVERAGE_PUSH_MM = 1.0;
    /**
     * Without dots the ideal corners sit this far inside the outline (mm): the bench's ftp spots, clear
     * of the corner holes' rings.
     */
    public static final double CORNER_INSET_MM = 8.0;
    /**
     * Without board art: a mounting hole assumed in each corner of an outline of at least
     * {@link #ASSUMED_HOLE_MIN_BOARD_MM}, its centre this far in from both edges (mm). ftp's measured
     * 4.5-5.0.
     */
    public static final double ASSUMED_HOLE_INSET_MM = 4.5;
    /** Its copper ring's radius (mm): an M3 pad of 6.4 mm (ftp's ring is 5.6 mm on a 3.2 mm hole). */
    public static final double ASSUMED_HOLE_RADIUS_MM = 3.2;
    public static final double ASSUMED_HOLE_MIN_BOARD_MM = 20.0;
    /**
     * Alternates on these rings, every {@link #ALTERNATE_STEP_DEG}, at least {@link #ALTERNATE_MIN_MM}
     * apart. 5 mm leaves a mounting hole's ring the target's look saw (ring radius 3.2 + 1.4 keep-out)
     * in any direction up to 90 degrees from straight away from it; 2 mm (the bench's step, enough for
     * a via or a speck of paste) where 5 mm is not free, e.g. inside ftp's ring of resistors.
     */
    public static final double[] ALTERNATE_RINGS_MM = {5.0, 2.0};
    public static final double ALTERNATE_STEP_DEG = 30;
    public static final double ALTERNATE_MIN_MM = 2.0;
    /** Blank: targets this far either side of the purge row, alternates this far along it (mm). */
    public static final double ROW_OFFSET_MM = 2.0;
    /** Blank: used purge spots carry paste (a 1.5 mm dot radius plus N1's 0.4 mm offset error). */
    public static final double USED_SPOT_RADIUS_MM = 1.5 + 0.4;
    /** Blank: the waste place and the test-dot spot. */
    public static final double PLACE_RADIUS_MM = 3.0;
    /** Blank: below this row length only its two ends are probed (mm). */
    public static final double ROW_SPLIT_MM = 6.0;
    /**
     * Blank: targets stay this far from the band of the calibration area earlier pad-calibration
     * ladders laid their dots in (the calibration job's blank step keeps the same distance).
     */
    public static final double USED_BAND_MARGIN_MM = 1.0;

    /**
     * An extra keep-out circle in board mm (board art: drills with their copper rings, vias, copper).
     * Like every feature it keeps targets {@link DispenseConfig.VacuumProbe#padKeepOutMm} from its edge.
     */
    public static final class KeepOut {
        public double x, y, r;

        public KeepOut() {
        }

        public KeepOut(double x, double y, double r) {
            this.x = x;
            this.y = y;
            this.r = r;
        }
    }

    /** The plan's dots (machine X/Y) in board mm, through the inverse of the plan's own transform. */
    public static List<double[]> dotsBoard(DispensePlan plan, AffineTransform planToBoard) {
        List<double[]> l = new ArrayList<>();
        for (DispensePlan.PlannedDot d : plan.dots) {
            Point2D p = planToBoard.transform(new Point2D.Double(d.xMm, d.yMm), null);
            l.add(new double[] {p.getX(), p.getY()});
        }
        return l;
    }

    /** {@link #forBoard(BoardFeatures, List, DispenseConfig, List)} for a board without art. */
    public static List<VacuumProber.Target> forBoard(BoardFeatures f, List<double[]> dotsBoard, DispenseConfig cfg)
            throws DispenseService.RunRefusedException {
        return forBoard(f, dotsBoard, cfg, null);
    }

    /**
     * Board targets in board mm (x/y equal bx/by until {@link #map}). extraKeepOuts are the board art's
     * holes and copper (an empty list: art without any); null when the board has no art, and then a
     * mounting hole is assumed in each corner. Refused when an ideal point has no free spot within
     * {@link #SEARCH_MM}, or the dots cannot be covered with {@link #MAX_TARGETS}.
     */
    public static List<VacuumProber.Target> forBoard(BoardFeatures f, List<double[]> dotsBoard, DispenseConfig cfg,
            List<KeepOut> extraKeepOuts) throws DispenseService.RunRefusedException {
        Area g = Area.of(f, cfg.vacuumProbe.padKeepOutMm, extraKeepOuts);
        List<double[]> dots = dotsBoard == null ? Collections.emptyList() : dotsBoard;
        double[] centroid = centroid(dots, g);
        List<double[]> ideal = new ArrayList<>();
        List<double[]> spots = cfg.vacuumProbe.boardSpots.get(f.fileName);
        if (spots == null) {
            spots = cfg.vacuumProbe.boardSpots.get(f.boardName);
        }
        if (spots != null) {
            for (double[] s : spots) {
                ideal.add(new double[] {s[0], s[1]});
            }
        }
        else {
            double[] box = !dots.isEmpty() ? box(dots)
                    : f.hasOutline() ? cornerBox(f) : new double[] {g.x0, g.y0, g.x1, g.y1};
            ideal.add(new double[] {box[0], box[1]});
            ideal.add(new double[] {box[2], box[1]});
            ideal.add(new double[] {box[2], box[3]});
            ideal.add(new double[] {box[0], box[3]});
            ideal.add(new double[] {(box[0] + box[2]) / 2, (box[1] + box[3]) / 2});
        }
        List<double[]> pts = new ArrayList<>();
        for (double[] p : ideal) {
            double[] q = nearestFree(p[0], p[1], g, centroid);
            if (q == null) {
                throw refused(String.format(Locale.ROOT, "PROBE: no bare spot within %.1f mm of board X %.1f, Y %.1f "
                        + "(pads, fiducials or the board edge all round): probe by hand", SEARCH_MM, p[0], p[1]));
            }
            if (!near(pts, q, DUPLICATE_MM)) {
                pts.add(q);
            }
        }
        double limit = cfg.surfaceMarginMm - 0.5;
        while (true) {
            int worst = -1;
            double worstD = limit;
            for (int i = 0; i < dots.size(); i++) {
                double d = distanceOutside(pts, dots.get(i));
                if (d > worstD + 1e-9) {
                    worst = i;
                    worstD = d;
                }
            }
            if (worst < 0) {
                break;
            }
            double[] q = pts.size() >= MAX_TARGETS ? null : coveragePoint(dots.get(worst), centroid, g, pts);
            if (q == null) {
                throw refused("PROBE: the dots cannot be covered with " + MAX_TARGETS + " probe points: probe by hand");
            }
            pts.add(q);
        }
        List<VacuumProber.Target> out = new ArrayList<>();
        int n = 1;
        for (double[] p : pts) {
            VacuumProber.Target t = new VacuumProber.Target();
            t.n = n++;
            t.bx = p[0];
            t.by = p[1];
            t.x = p[0];
            t.y = p[1];
            for (double[] a : alternates(p, g, centroid, cfg.vacuumProbe.alternates)) {
                t.alternatesBoard.add(a);
                t.alternates.add(a.clone());
            }
            out.add(t);
        }
        return out;
    }

    /** Board targets mapped to the machine X/Y of the N1 tip through a board -> machine transform. */
    public static List<VacuumProber.Target> map(List<VacuumProber.Target> boardTargets, AffineTransform boardToMachine) {
        List<VacuumProber.Target> out = new ArrayList<>();
        for (VacuumProber.Target b : boardTargets) {
            VacuumProber.Target t = new VacuumProber.Target();
            t.n = b.n;
            t.bx = b.bx;
            t.by = b.by;
            t.storedZ = b.storedZ;
            Point2D p = boardToMachine.transform(new Point2D.Double(b.bx, b.by), null);
            t.x = p.getX();
            t.y = p.getY();
            for (double[] a : b.alternatesBoard) {
                t.alternatesBoard.add(a.clone());
                Point2D q = boardToMachine.transform(new Point2D.Double(a[0], a[1]), null);
                t.alternates.add(new double[] {q.getX(), q.getY()});
            }
            out.add(t);
        }
        return out;
    }

    /**
     * Blank targets in machine mm around the purge row: s (along the row) at its ends and middle (only
     * the ends below {@link #ROW_SPLIT_MM}), t at {@link #ROW_OFFSET_MM} either side of the spots; each
     * moved along the row first off the used spots, the waste / test-dot places and the calibration
     * area's used band ({@link #usedBand}). The cover set is every purge spot, used or not, plus the
     * blank place when this surface is the blank.
     */
    public static List<VacuumProber.Target> forRow(DispenseConfig cfg, String surfaceName)
            throws DispenseService.RunRefusedException {
        DispenseConfig.PurgeRow row = cfg.purgeRow;
        List<double[]> cover = new ArrayList<>();
        if (row.isDefined()) {
            for (int i = 0; i < row.spots; i++) {
                cover.add(row.spot(i));
            }
        }
        DispenseConfig.NamedLocation blank = cfg.location(cfg.blankLocationName);
        if (blank != null && cfg.blankLocationName.equals(surfaceName)) {
            cover.add(new double[] {blank.x, blank.y});
        }
        if (cover.isEmpty()) {
            throw refused("PROBE: nothing to probe around on the '" + surfaceName + "' surface: set the purge row (or "
                    + "the '" + cfg.blankLocationName + "' place) first");
        }
        double len = Math.hypot(row.pitchXMm, row.pitchYMm);
        double[] u = row.spots >= 2 && len > 0 ? new double[] {row.pitchXMm / len, row.pitchYMm / len}
                : new double[] {1, 0};
        double[] nv = {-u[1], u[0]};
        double s0 = Double.MAX_VALUE, s1 = -Double.MAX_VALUE, t0 = Double.MAX_VALUE, t1 = -Double.MAX_VALUE;
        for (double[] c : cover) {
            double s = dot(c, u), t = dot(c, nv);
            s0 = Math.min(s0, s);
            s1 = Math.max(s1, s);
            t0 = Math.min(t0, t);
            t1 = Math.max(t1, t);
        }
        double mid = (s0 + s1) / 2;
        double[] sv;
        if (s1 - s0 < ROW_SPLIT_MM) {
            // only the ends, at least ROW_SPLIT_MM apart so the points span an area
            sv = new double[] {Math.min(s0, mid - ROW_SPLIT_MM / 2), Math.max(s1, mid + ROW_SPLIT_MM / 2)};
        }
        else {
            sv = new double[] {s0, mid, s1};
        }
        List<double[]> circles = new ArrayList<>();
        for (int i = 0; row.isDefined() && i < Math.min(row.usedSpots, row.spots); i++) {
            double[] p = row.spot(i);
            circles.add(new double[] {p[0], p[1], USED_SPOT_RADIUS_MM});
        }
        DispenseConfig.NamedLocation waste = cfg.location(cfg.wasteLocationName);
        if (waste != null) {
            circles.add(new double[] {waste.x, waste.y, PLACE_RADIUS_MM});
            circles.add(new double[] {waste.x + cfg.testDotOffsetXMm, waste.y + cfg.testDotOffsetYMm, PLACE_RADIUS_MM});
        }
        Area g = Area.open(circles);
        double[] band = usedBand(cfg);
        if (band != null) {
            g.boxes.add(band);
        }
        List<double[]> pts = new ArrayList<>();
        List<Double> outward = new ArrayList<>();
        for (double t : new double[] {t0 - ROW_OFFSET_MM, t1 + ROW_OFFSET_MM}) {
            for (double s : sv) {
                double out = s >= mid ? 1 : -1;
                double[] q = alongRow(s, t, out, u, nv, g);
                if (q == null) {
                    double[] ideal = add(scale(u, s), scale(nv, t));
                    q = nearestFree(ideal[0], ideal[1], g, add(scale(u, mid), scale(nv, (t0 + t1) / 2)));
                }
                if (q == null) {
                    double[] ideal = add(scale(u, s), scale(nv, t));
                    throw refused(String.format(Locale.ROOT, "PROBE: no free spot within %.1f mm of X %.2f, Y %.2f "
                            + "(used purge spots, the waste or the test-dot place, or the calibration area's used band "
                            + "all round): give the targets, or probe by hand", SEARCH_MM, ideal[0], ideal[1]));
                }
                if (!near(pts, q, DUPLICATE_MM)) {
                    pts.add(q);
                    outward.add(out);
                }
            }
        }
        double limit = cfg.surfaceMarginMm - 0.5;
        int outside = 0;
        double worst = 0;
        for (double[] c : cover) {
            double d = distanceOutside(pts, c);
            if (d > limit + 1e-9) {
                outside++;
                worst = Math.max(worst, d);
            }
        }
        if (outside > 0) {
            throw refused(String.format(Locale.ROOT, "PROBE: the probe points leave %d spot(s) of the purge row up to "
                    + "%.1f mm outside the probed area (limit %.1f mm): give the targets, or probe by hand", outside,
                    worst, limit));
        }
        List<VacuumProber.Target> l = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            VacuumProber.Target t = new VacuumProber.Target();
            t.n = i + 1;
            t.x = p[0];
            t.y = p[1];
            for (double sign : new double[] {outward.get(i), -outward.get(i)}) {
                double[] a = add(p, scale(u, sign * ROW_OFFSET_MM));
                if (t.alternates.size() < cfg.vacuumProbe.alternates && g.free(a[0], a[1])) {
                    t.alternates.add(a);
                }
            }
            l.add(t);
        }
        return l;
    }

    /**
     * Blank: the band of the calibration area that pad-calibration ladders have laid dots in since the
     * blank was last wiped (from y0 up by {@link CalibrationArea#usedMm}), grown by
     * {@link #USED_BAND_MARGIN_MM}: {x0, y0, x1, y1} in machine mm, or null when none is used. The
     * default area starts at the blank place, so the target past the blank place lies in it whenever
     * the purge row lies below the blank place; the camera look refuses those spots once a ladder ran.
     */
    static double[] usedBand(DispenseConfig cfg) {
        CalibrationArea a = cfg.calibrationArea == null ? null : cfg.calibrationArea.effective(cfg);
        if (a == null || !(a.usedMm > 0)) {
            return null;
        }
        double m = USED_BAND_MARGIN_MM;
        return new double[] {a.x0Mm - m, a.y0Mm - m, a.x0Mm + a.widthMm + m,
                a.y0Mm + Math.min(a.usedMm, a.heightMm) + m};
    }

    /** Explicit N1 tip targets (machine mm); their alternates are 2 mm either side in X. */
    public static List<VacuumProber.Target> explicit(List<double[]> xy, int alternates) {
        List<VacuumProber.Target> l = new ArrayList<>();
        for (int i = 0; i < xy.size(); i++) {
            VacuumProber.Target t = new VacuumProber.Target();
            t.n = i + 1;
            t.x = xy.get(i)[0];
            t.y = xy.get(i)[1];
            for (double sign : new double[] {1, -1}) {
                if (t.alternates.size() < alternates) {
                    t.alternates.add(new double[] {t.x + sign * ROW_OFFSET_MM, t.y});
                }
            }
            l.add(t);
        }
        return l;
    }

    // ------------------------------------------------------------------------------------------

    /**
     * Allowed rectangle and keep-outs (circles {x, y, radius}, grown pad rectangles and boxes {x0, y0,
     * x1, y1}).
     */
    static final class Area {
        double x0 = -Double.MAX_VALUE, y0 = -Double.MAX_VALUE, x1 = Double.MAX_VALUE, y1 = Double.MAX_VALUE;
        final List<double[]> circles = new ArrayList<>();
        final List<BoardFeatures.Pad> rects = new ArrayList<>();
        final List<double[]> boxes = new ArrayList<>();
        double rectKeepOut;

        static Area of(BoardFeatures f, double keepOut, List<KeepOut> extra) {
            Area a = new Area();
            a.rectKeepOut = keepOut;
            if (f.hasOutline()) {
                a.x0 = EDGE_MM;
                a.y0 = EDGE_MM;
                a.x1 = f.widthMm - EDGE_MM;
                a.y1 = f.heightMm - EDGE_MM;
            }
            else {
                double bx0 = Double.MAX_VALUE, by0 = Double.MAX_VALUE, bx1 = -Double.MAX_VALUE, by1 = -Double.MAX_VALUE;
                for (BoardFeatures.Pad p : f.pads) {
                    bx0 = Math.min(bx0, p.x);
                    by0 = Math.min(by0, p.y);
                    bx1 = Math.max(bx1, p.x);
                    by1 = Math.max(by1, p.y);
                }
                for (BoardFeatures.Fiducial p : f.fiducials) {
                    bx0 = Math.min(bx0, p.x);
                    by0 = Math.min(by0, p.y);
                    bx1 = Math.max(bx1, p.x);
                    by1 = Math.max(by1, p.y);
                }
                if (bx0 <= bx1) {
                    a.x0 = bx0 - BOX_GROW_MM;
                    a.y0 = by0 - BOX_GROW_MM;
                    a.x1 = bx1 + BOX_GROW_MM;
                    a.y1 = by1 + BOX_GROW_MM;
                }
            }
            for (BoardFeatures.Pad p : f.pads) {
                if (p.known) {
                    a.rects.add(p);
                }
                else {
                    a.circles.add(new double[] {p.x, p.y, UNKNOWN_PAD_RADIUS_MM + keepOut});
                }
            }
            for (BoardFeatures.Fiducial fd : f.fiducials) {
                a.circles.add(new double[] {fd.x, fd.y, fd.maskDiameterMm / 2 + keepOut});
            }
            for (KeepOut k : extra != null ? extra : assumedHoles(f)) {
                a.circles.add(new double[] {k.x, k.y, k.r + keepOut});
            }
            return a;
        }

        static Area open(List<double[]> circles) {
            Area a = new Area();
            a.circles.addAll(circles);
            return a;
        }

        boolean free(double x, double y) {
            if (x < x0 - 1e-9 || x > x1 + 1e-9 || y < y0 - 1e-9 || y > y1 + 1e-9) {
                return false;
            }
            for (double[] c : circles) {
                if (Math.hypot(x - c[0], y - c[1]) < c[2] - 1e-9) {
                    return false;
                }
            }
            for (BoardFeatures.Pad p : rects) {
                if (padDistance(p, x, y) < rectKeepOut - 1e-9) {
                    return false;
                }
            }
            for (double[] b : boxes) {
                if (x > b[0] + 1e-9 && x < b[2] - 1e-9 && y > b[1] + 1e-9 && y < b[3] - 1e-9) {
                    return false;
                }
            }
            return true;
        }
    }

    /** Without board art: a mounting hole in each corner of an outline big enough to carry one. */
    static List<KeepOut> assumedHoles(BoardFeatures f) {
        List<KeepOut> l = new ArrayList<>();
        if (!f.hasOutline() || Math.min(f.widthMm, f.heightMm) < ASSUMED_HOLE_MIN_BOARD_MM) {
            return l;
        }
        double i = ASSUMED_HOLE_INSET_MM;
        for (double[] c : new double[][] {{i, i}, {f.widthMm - i, i}, {f.widthMm - i, f.heightMm - i},
                {i, f.heightMm - i}}) {
            l.add(new KeepOut(c[0], c[1], ASSUMED_HOLE_RADIUS_MM));
        }
        return l;
    }

    /** The ideal corners without dots: {@link #CORNER_INSET_MM} inside the outline (its middle if smaller). */
    private static double[] cornerBox(BoardFeatures f) {
        double ix = Math.min(CORNER_INSET_MM, f.widthMm / 2), iy = Math.min(CORNER_INSET_MM, f.heightMm / 2);
        return new double[] {ix, iy, f.widthMm - ix, f.heightMm - iy};
    }

    /** Distance from (x, y) to the pad's rectangle (0 inside). */
    static double padDistance(BoardFeatures.Pad p, double x, double y) {
        double r = Math.toRadians(p.rotationDeg);
        double dx = x - p.x, dy = y - p.y;
        double u = dx * Math.cos(r) + dy * Math.sin(r);
        double v = -dx * Math.sin(r) + dy * Math.cos(r);
        return Math.hypot(Math.max(0, Math.abs(u) - p.wMm / 2), Math.max(0, Math.abs(v) - p.hMm / 2));
    }

    /**
     * The free grid point (0.25 mm, centred on the ideal point) within {@link #SEARCH_MM} nearest to it;
     * ties: further from the centroid (keeps the hull wide), then lower y, then lower x. Null if none.
     */
    static double[] nearestFree(double ix, double iy, Area g, double[] centroid) {
        int n = (int) Math.round(SEARCH_MM / GRID_MM);
        double[] best = null;
        int bestR = Integer.MAX_VALUE;
        double bestC = 0;
        for (int i = -n; i <= n; i++) {
            for (int j = -n; j <= n; j++) {
                int r2 = i * i + j * j;
                if (r2 > n * n || r2 > bestR) {
                    continue;
                }
                double x = ix + i * GRID_MM, y = iy + j * GRID_MM;
                if (!g.free(x, y)) {
                    continue;
                }
                double c = Math.hypot(x - centroid[0], y - centroid[1]);
                if (best == null || r2 < bestR || c > bestC + 1e-9
                        || (Math.abs(c - bestC) <= 1e-9 && (y < best[1] - 1e-9
                                || (Math.abs(y - best[1]) <= 1e-9 && x < best[0] - 1e-9)))) {
                    best = new double[] {x, y};
                    bestR = r2;
                    bestC = c;
                }
            }
        }
        return best;
    }

    /** Blank: the nearest free point moved along the row only (outward first on ties), or null. */
    private static double[] alongRow(double s, double t, double outward, double[] u, double[] nv, Area g) {
        int n = (int) Math.round(SEARCH_MM / GRID_MM);
        for (int k = 0; k <= n; k++) {
            for (double sign : k == 0 ? new double[] {0} : new double[] {outward, -outward}) {
                double[] p = add(scale(u, s + sign * k * GRID_MM), scale(nv, t));
                if (g.free(p[0], p[1])) {
                    return p;
                }
            }
        }
        return null;
    }

    /** A free point for an uncovered dot, pushed outward from the centroid; null if none helps. */
    private static double[] coveragePoint(double[] dot, double[] centroid, Area g, List<double[]> pts) {
        double dx = dot[0] - centroid[0], dy = dot[1] - centroid[1], d = Math.hypot(dx, dy);
        double px = d > 1e-9 ? dot[0] + COVERAGE_PUSH_MM * dx / d : dot[0];
        double py = d > 1e-9 ? dot[1] + COVERAGE_PUSH_MM * dy / d : dot[1];
        double[] q = nearestFree(px, py, g, centroid);
        return q == null || near(pts, q, DUPLICATE_MM) ? null : q;
    }

    /**
     * Alternates of a target: free points on rings of 5 and 2 mm every 30 deg, at least 2 mm from the
     * target and each other. Chosen spread out: the first on the 5 mm ring (where it does not move toward
     * the centroid by more than 0.5 mm, keeping the hull, if it can), each next one on the outermost ring
     * left, as far in direction from those taken as it can be; so a hole on one side of the target cannot
     * take every alternate. Tried in that ring, hull, angle order.
     */
    static List<double[]> alternates(double[] p, Area g, double[] centroid, int count) {
        double dTarget = Math.hypot(p[0] - centroid[0], p[1] - centroid[1]);
        List<double[]> cand = new ArrayList<>(); // x, y, ring index, keeps the hull (0/1), angle
        for (int ring = 0; ring < ALTERNATE_RINGS_MM.length; ring++) {
            double r = ALTERNATE_RINGS_MM[ring];
            for (double a = 0; a < 360 - 1e-9; a += ALTERNATE_STEP_DEG) {
                double x = p[0] + r * Math.cos(Math.toRadians(a)), y = p[1] + r * Math.sin(Math.toRadians(a));
                if (!g.free(x, y) || Math.hypot(x - p[0], y - p[1]) < ALTERNATE_MIN_MM - 1e-9) {
                    continue;
                }
                boolean outward = Math.hypot(x - centroid[0], y - centroid[1]) >= dTarget - 0.5;
                cand.add(new double[] {x, y, ring, outward ? 0 : 1, a});
            }
        }
        Comparator<double[]> order = (u, v) -> {
            int c = Double.compare(u[2], v[2]);
            if (c == 0) {
                c = Double.compare(u[3], v[3]);
            }
            return c != 0 ? c : Double.compare(u[4], v[4]);
        };
        cand.sort(order);
        List<double[]> taken = new ArrayList<>();
        while (taken.size() < count) {
            double[] pick = null;
            double spread = -1;
            for (double[] c : cand) {
                if (near(taken, c, ALTERNATE_MIN_MM)) {
                    continue;
                }
                if (pick != null && c[2] != pick[2]) {
                    break; // the outermost ring left only
                }
                double d = angleFrom(taken, c[4]);
                if (d > spread + 1e-9) {
                    pick = c;
                    spread = d;
                }
            }
            if (pick == null) {
                break;
            }
            taken.add(pick);
        }
        taken.sort(order);
        List<double[]> out = new ArrayList<>();
        for (double[] c : taken) {
            out.add(new double[] {c[0], c[1]});
        }
        return out;
    }

    /** The smallest difference in direction (deg) between a and the taken candidates'; 180 when none. */
    private static double angleFrom(List<double[]> taken, double a) {
        double min = 180;
        for (double[] c : taken) {
            double d = Math.abs(a - c[4]) % 360;
            min = Math.min(min, Math.min(d, 360 - d));
        }
        return min;
    }

    /** How far p lies outside the convex hull of the points (the hull of a z = 0 plane fit). */
    static double distanceOutside(List<double[]> pts, double[] p) {
        List<DispenseConfig.SurfacePoint> sp = new ArrayList<>();
        for (double[] q : pts) {
            sp.add(new DispenseConfig.SurfacePoint(q[0], q[1], 0));
        }
        try {
            return SurfacePlane.fit(sp).distanceOutside(p[0], p[1]);
        }
        catch (IllegalArgumentException e) {
            // fewer than 3 points or all on a line: the distance to the polyline through them
            double best = Double.MAX_VALUE;
            for (int i = 0; i < pts.size(); i++) {
                for (int j = i; j < pts.size(); j++) {
                    best = Math.min(best, segmentDistance(pts.get(i), pts.get(j), p));
                }
            }
            return best;
        }
    }

    private static double segmentDistance(double[] a, double[] b, double[] p) {
        double ex = b[0] - a[0], ey = b[1] - a[1], len2 = ex * ex + ey * ey;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * ex + (p[1] - a[1]) * ey) / len2));
        return Math.hypot(p[0] - (a[0] + t * ex), p[1] - (a[1] + t * ey));
    }

    private static boolean near(List<double[]> pts, double[] q, double d) {
        for (double[] p : pts) {
            if (Math.hypot(p[0] - q[0], p[1] - q[1]) < d - 1e-9) {
                return true;
            }
        }
        return false;
    }

    private static double[] box(List<double[]> pts) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (double[] p : pts) {
            b[0] = Math.min(b[0], p[0]);
            b[1] = Math.min(b[1], p[1]);
            b[2] = Math.max(b[2], p[0]);
            b[3] = Math.max(b[3], p[1]);
        }
        return b;
    }

    private static double[] centroid(List<double[]> dots, Area g) {
        if (dots.isEmpty()) {
            boolean bounded = g.x0 > -Double.MAX_VALUE && g.x1 < Double.MAX_VALUE;
            return bounded ? new double[] {(g.x0 + g.x1) / 2, (g.y0 + g.y1) / 2} : new double[] {0, 0};
        }
        double x = 0, y = 0;
        for (double[] d : dots) {
            x += d[0];
            y += d[1];
        }
        return new double[] {x / dots.size(), y / dots.size()};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1];
    }

    private static double[] scale(double[] a, double k) {
        return new double[] {a[0] * k, a[1] * k};
    }

    private static double[] add(double[] a, double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1]};
    }

    private static DispenseService.RunRefusedException refused(String why) {
        return new DispenseService.RunRefusedException(why, null);
    }
}
