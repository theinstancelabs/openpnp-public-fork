package org.openpnp.instancelabs.kicad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.openpnp.instancelabs.kicad.KicadPcb.Footprint;
import org.openpnp.instancelabs.kicad.KicadPcb.Graphic;
import org.openpnp.instancelabs.kicad.KicadPcb.Pad;

/**
 * The board frame (design §2.6): origin at the bottom-left corner of the Edge.Cuts box, +Y up, millimetres. The box is
 * the exact extent of the outline loop (arcs bulge past their end points); an open outline uses every Edge.Cuts item;
 * a file without Edge.Cuts uses the parts' courtyards and is marked "estimated".
 */
public final class BoardFrame {
    /** End points closer than this are joined when chaining Edge.Cuts items. */
    public static final double CHAIN_TOL = 0.01;

    public static final String OUTLINE_CLOSED = "closed";
    public static final String OUTLINE_OPEN = "open";
    public static final String OUTLINE_NONE = "none";
    public static final String ORIGIN_OUTLINE = "outline";
    public static final String ORIGIN_EDGES = "edges";
    public static final String ORIGIN_ESTIMATED = "estimated";

    /** The box in KiCad coordinates (Y down); the origin is (minX, maxY). */
    public final double minX;
    public final double minY;
    public final double maxX;
    public final double maxY;
    public final String outline;
    public final String origin;
    /** The outline loop in the board frame (closed polygon, first point not repeated), or null. */
    public final double[] outlineLoop;
    /** Every other closed Edge.Cuts loop in the board frame, file order. */
    public final List<double[]> cutouts;
    /** The chains of an open outline in the board frame (polylines), file order. */
    public final List<double[]> openChains;
    public final List<ImportWarning> warnings;

    private BoardFrame(double[] box, String outline, String origin, double[] outlineK, List<double[]> cutoutsK,
            List<double[]> openK, List<ImportWarning> warnings) {
        this.minX = box[0];
        this.minY = box[1];
        this.maxX = box[2];
        this.maxY = box[3];
        this.outline = outline;
        this.origin = origin;
        this.outlineLoop = outlineK == null ? null : toBoard(outlineK);
        List<double[]> c = new ArrayList<>();
        for (double[] p : cutoutsK) {
            c.add(toBoard(p));
        }
        this.cutouts = Collections.unmodifiableList(c);
        List<double[]> o = new ArrayList<>();
        for (double[] p : openK) {
            o.add(toBoard(p));
        }
        this.openChains = Collections.unmodifiableList(o);
        this.warnings = Collections.unmodifiableList(warnings);
    }

    public double widthMm() {
        return KGeom.round6(maxX - minX);
    }

    public double heightMm() {
        return KGeom.round6(maxY - minY);
    }

    /** A KiCad point in the board frame. */
    public double[] toBoard(double x, double y) {
        return new double[] {x - minX, maxY - y};
    }

    /** KiCad points (x0 y0 x1 y1 …) in the board frame. */
    public double[] toBoard(double[] p) {
        double[] r = new double[p.length];
        for (int i = 0; i + 1 < p.length; i += 2) {
            r[i] = p[i] - minX;
            r[i + 1] = maxY - p[i + 1];
        }
        return r;
    }

    /**
     * A footprint-local point (u, v) in the board frame: (X − minX, maxY − Y) + R(θ)·(u, −v), R the standard
     * counter-clockwise rotation (the composition BoardPadExtractor uses for a pad offset). Bottom footprints too:
     * KiCad stores their pads already mirrored.
     */
    public double[] localToBoard(Footprint fp, double u, double v) {
        double[] k = localToKicad(fp, u, v);
        return toBoard(k[0], k[1]);
    }

    /** A footprint-local point in KiCad coordinates: KiCad's angle is counter-clockwise on screen (Y down). */
    public static double[] localToKicad(Footprint fp, double u, double v) {
        double a = Math.toRadians(fp.angle);
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new double[] {fp.x + u * c + v * s, fp.y - u * s + v * c};
    }

    /** The inverse of localToKicad (footprint zones are stored in board coordinates). */
    public static double[] kicadToLocal(Footprint fp, double x, double y) {
        double a = Math.toRadians(fp.angle);
        double c = Math.cos(a);
        double s = Math.sin(a);
        double dx = x - fp.x;
        double dy = y - fp.y;
        return new double[] {dx * c - dy * s, dx * s + dy * c};
    }

    /** Footprint-local points in KiCad coordinates. */
    public static double[] localToKicad(Footprint fp, double[] p) {
        double[] r = new double[p.length];
        for (int i = 0; i + 1 < p.length; i += 2) {
            double[] k = localToKicad(fp, p[i], p[i + 1]);
            r[i] = k[0];
            r[i + 1] = k[1];
        }
        return r;
    }

    // ---- the rule ------------------------------------------------------------------------------------------------

    public static BoardFrame of(KicadPcb pcb, Deadline deadline) throws SExprException {
        List<double[]> pieces = new ArrayList<>();
        List<double[]> pieceBoxes = new ArrayList<>();
        List<double[]> loops = new ArrayList<>();
        List<double[]> loopBoxes = new ArrayList<>();
        long points = 0;
        for (Graphic g : pcb.graphics) {
            if ("Edge.Cuts".equals(g.layer)) {
                points += edge(g.kind, g.pts, pieces, pieceBoxes, loops, loopBoxes);
                checkPoints(points, g.line);
            }
        }
        for (Footprint fp : pcb.footprints) {
            for (Graphic g : fp.graphics) {
                if ("Edge.Cuts".equals(g.layer)) {
                    boolean rect = g.kind == Graphic.Kind.RECT; // a rotated footprint turns its rect
                    points += edge(rect ? Graphic.Kind.POLY : g.kind,
                            localToKicad(fp, rect ? rectCorners(g.pts) : g.pts), pieces, pieceBoxes, loops, loopBoxes);
                    checkPoints(points, g.line);
                }
            }
        }
        deadline.check();
        List<ImportWarning> warnings = new ArrayList<>();
        if (pieces.isEmpty() && loops.isEmpty()) {
            warnings.add(ImportWarning.of("NO_EDGE_CUTS", null, "The file has no board outline (Edge.Cuts). The origin "
                    + "is estimated from the parts: place the board with \"Camera is on FID1\" after saving."));
            double[] box = estimatedBox(pcb);
            return new BoardFrame(box, OUTLINE_NONE, ORIGIN_ESTIMATED, null, Collections.emptyList(),
                    Collections.emptyList(), warnings);
        }
        List<double[]> open = new ArrayList<>();
        double openArea = -1; // the largest box of an open chain
        List<double[]> allLoops = new ArrayList<>();
        List<double[]> allLoopBoxes = new ArrayList<>();
        for (KGeom.Chain c : KGeom.chain(pieces, pieceBoxes, CHAIN_TOL, deadline)) {
            if (c.closed) {
                allLoops.add(c.pts);
                allLoopBoxes.add(c.box);
            }
            else {
                open.add(c.pts);
                openArea = Math.max(openArea, KGeom.boxArea(c.box));
            }
        }
        allLoops.addAll(loops);
        allLoopBoxes.addAll(loopBoxes);
        int outlineIndex = -1;
        double bestArea = -1;
        for (int i = 0; i < allLoops.size(); i++) {
            double a = KGeom.boxArea(allLoopBoxes.get(i));
            if (a > bestArea) {
                bestArea = a;
                outlineIndex = i;
            }
        }
        if (openArea > bestArea) {
            outlineIndex = -1; // the outline is the open chain (it has a gap): every closed loop is a cut-out
        }
        double[] outlineK = outlineIndex < 0 ? null : allLoops.get(outlineIndex);
        List<double[]> cutouts = new ArrayList<>();
        for (int i = 0; i < allLoops.size(); i++) {
            if (i != outlineIndex) {
                cutouts.add(allLoops.get(i));
            }
        }
        if (open.isEmpty()) {
            return new BoardFrame(allLoopBoxes.get(outlineIndex), OUTLINE_CLOSED, ORIGIN_OUTLINE, outlineK, cutouts,
                    open, warnings);
        }
        // an open outline: its Edge.Cuts items are still the board's physical edges
        double[] box = KGeom.emptyBox();
        for (double[] b : pieceBoxes) {
            KGeom.extend(box, b);
        }
        for (double[] b : loopBoxes) {
            KGeom.extend(box, b);
        }
        double[] first = open.get(0);
        int n = first.length;
        double gap = Math.hypot(first[n - 2] - first[0], first[n - 1] - first[1]);
        warnings.add(ImportWarning.of("OUTLINE_OPEN", null, String.format(Locale.ROOT,
                "The board outline is not closed (gap %.2f mm near X %.1f, Y %.1f mm). The board size uses every "
                        + "outline line; check the drawing.",
                gap, first[n - 2] - box[0], box[3] - first[n - 1])));
        return new BoardFrame(box, OUTLINE_OPEN, ORIGIN_EDGES, outlineK, cutouts, open, warnings);
    }

    /**
     * One Edge.Cuts item (KiCad coordinates): lines, arcs and curves are chained; rects, polygons, circles are loops.
     * Returns the number of points it added.
     */
    private static int edge(Graphic.Kind kind, double[] p, List<double[]> pieces, List<double[]> pieceBoxes,
            List<double[]> loops, List<double[]> loopBoxes) {
        double[] box = KGeom.emptyBox();
        double[] q;
        switch (kind) {
            case LINE:
                KGeom.extendPts(box, p);
                q = p.clone();
                pieces.add(q);
                pieceBoxes.add(box);
                break;
            case ARC: {
                KGeom.Pts out = new KGeom.Pts();
                if (!KGeom.flattenArc(p[0], p[1], p[2], p[3], p[4], p[5], KGeom.CHORD_MM, out, true)) {
                    return 0;
                }
                q = out.toArray();
                pieces.add(q);
                pieceBoxes.add(KGeom.arcExtents(p[0], p[1], p[2], p[3], p[4], p[5]));
                break;
            }
            case BEZIER: {
                KGeom.Pts out = new KGeom.Pts();
                KGeom.flattenBezier(p, out, true);
                q = out.toArray();
                KGeom.extendPts(box, q);
                pieces.add(q);
                pieceBoxes.add(box);
                break;
            }
            case CIRCLE: {
                double r = Math.hypot(p[2] - p[0], p[3] - p[1]);
                q = KGeom.circle(p[0], p[1], r, KGeom.CHORD_MM);
                loops.add(q);
                loopBoxes.add(new double[] {p[0] - r, p[1] - r, p[0] + r, p[1] + r});
                break;
            }
            case RECT:
                q = rectCorners(p);
                KGeom.extendPts(box, q);
                loops.add(q);
                loopBoxes.add(box);
                break;
            case POLY:
                KGeom.extendPts(box, p);
                q = p.clone();
                loops.add(q);
                loopBoxes.add(box);
                break;
            default:
                return 0;
        }
        return q.length / 2;
    }

    /** The outline's arcs and circles, drawn as lines, stay within the point budget (KGeom.MAX_FLAT_POINTS). */
    private static void checkPoints(long points, int line) throws SExprException {
        if (points > KGeom.MAX_FLAT_POINTS) {
            throw SExprException.limit(line, String.format(Locale.ROOT, "its outline has more than %,d points once "
                    + "its arcs and circles are drawn (line %d)", KGeom.MAX_FLAT_POINTS, line));
        }
    }

    /** The four corners of a rect given by two opposite corners (turn them before transforming a rotated rect). */
    public static double[] rectCorners(double[] p) {
        return new double[] {p[0], p[1], p[2], p[1], p[2], p[3], p[0], p[3]};
    }

    /** No Edge.Cuts: every footprint's courtyard, else its fab outline, else its pads. */
    private static double[] estimatedBox(KicadPcb pcb) {
        double[] box = KGeom.emptyBox();
        for (Footprint fp : pcb.footprints) {
            double[] b = graphicsBox(fp, "CrtYd");
            if (KGeom.isEmpty(b)) {
                b = graphicsBox(fp, "Fab");
            }
            if (KGeom.isEmpty(b)) {
                b = padsBox(fp);
            }
            KGeom.extend(box, b);
        }
        if (KGeom.isEmpty(box)) {
            return new double[] {0, 0, 0, 0};
        }
        return box;
    }

    /** The KiCad-frame box of a footprint's graphics on F.&lt;suffix&gt; or B.&lt;suffix&gt;. */
    static double[] graphicsBox(Footprint fp, String suffix) {
        double[] box = KGeom.emptyBox();
        for (Graphic g : fp.graphics) {
            if (("F." + suffix).equals(g.layer) || ("B." + suffix).equals(g.layer)) {
                KGeom.extend(box, graphicBox(g.kind, localToKicad(fp, g.kind == Graphic.Kind.RECT
                        ? rectCorners(g.pts) : g.pts)));
            }
        }
        return box;
    }

    /**
     * The box of one graphic's points (already in the frame wanted), without its stroke width. RECT points may be the
     * two corners or the four corners.
     */
    public static double[] graphicBox(Graphic.Kind kind, double[] p) {
        double[] box = KGeom.emptyBox();
        switch (kind) {
            case ARC:
                return KGeom.arcExtents(p[0], p[1], p[2], p[3], p[4], p[5]);
            case CIRCLE: {
                double r = Math.hypot(p[2] - p[0], p[3] - p[1]);
                return new double[] {p[0] - r, p[1] - r, p[0] + r, p[1] + r};
            }
            case RECT:
                KGeom.extendPts(box, p.length == 4 ? rectCorners(p) : p);
                return box;
            default:
                KGeom.extendPts(box, p);
                return box;
        }
    }

    private static double[] padsBox(Footprint fp) {
        double[] box = KGeom.emptyBox();
        for (Pad p : fp.pads) {
            double a = Math.toRadians(p.angle - fp.angle);
            double c = Math.cos(a);
            double s = Math.sin(a);
            for (int k = 0; k < 4; k++) {
                double u = (k == 0 || k == 3 ? -1 : 1) * p.w / 2;
                double v = (k < 2 ? -1 : 1) * p.h / 2;
                double[] q = localToKicad(fp, p.x + u * c + v * s, p.y - u * s + v * c);
                KGeom.extend(box, q[0], q[1]);
            }
        }
        return box;
    }
}
