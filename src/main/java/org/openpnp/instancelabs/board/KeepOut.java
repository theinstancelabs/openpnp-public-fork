package org.openpnp.instancelabs.board;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.openpnp.instancelabs.kicad.KGeom;

/**
 * A circle the automatic probe must not touch, in board millimetres (design §4.4, the {x, y, r} format of AR §16
 * item 10). From KiCad art: every drill (holes and vias leak), the outline and every cut-out as a chain of circles,
 * and circles over the parts of the board box that are not board.
 */
public final class KeepOut {
    /** Circles along the outline and the cut-outs: this radius, at most this far apart. */
    public static final double EDGE_R = 1.0;
    public static final double EDGE_STEP = 1.0;
    /** Circles over the box outside the outline and inside cut-outs, one per 1 mm cell (r ≥ 0.71 covers it). */
    public static final double FILL_R = 0.75;
    public static final double FILL_CELL = 1.0;

    public final double x;
    public final double y;
    public final double r;

    public KeepOut(double x, double y, double r) {
        this.x = x;
        this.y = y;
        this.r = r;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "(%.3f, %.3f) r %.3f", x, y, r);
    }

    /** The keep-outs of KiCad art (fallback art has none). */
    public static List<KeepOut> of(BoardArt art) {
        List<KeepOut> out = new ArrayList<>();
        if (art == null || !BoardArt.KIND_KICAD.equals(art.kind)) {
            return out;
        }
        if (art.keepOutHoles != null) {
            for (double[] h : art.keepOutHoles) {
                if (h != null && h.length >= 3) {
                    out.add(new KeepOut(h[0], h[1], h[2]));
                }
            }
        }
        boolean estimated = "estimated".equals(art.origin);
        List<ArtPath.Sub> outline = estimated ? new ArrayList<>() : ArtPath.parse(art.outline.d);
        List<ArtPath.Sub> cutouts = ArtPath.parse(art.outline.cutouts);
        for (ArtPath.Sub s : outline) {
            chain(s.pts, s.closed, out);
        }
        for (ArtPath.Sub s : cutouts) {
            chain(s.pts, s.closed, out);
        }
        double[] loop = art.outline.closed && outline.size() == 1 && outline.get(0).closed ? outline.get(0).pts
                : null;
        if (loop != null && art.size != null) {
            int nx = (int) Math.ceil(art.size[0] / FILL_CELL - 1e-9);
            int ny = (int) Math.ceil(art.size[1] / FILL_CELL - 1e-9);
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    double cx = (i + 0.5) * FILL_CELL;
                    double cy = (j + 0.5) * FILL_CELL;
                    boolean off = !KGeom.pointInPolygon(cx, cy, loop);
                    for (int k = 0; !off && k < cutouts.size(); k++) {
                        off = cutouts.get(k).closed && KGeom.pointInPolygon(cx, cy, cutouts.get(k).pts);
                    }
                    if (off) {
                        out.add(new KeepOut(cx, cy, FILL_R));
                    }
                }
            }
        }
        return out;
    }

    /** Circles every ≤ 1 mm along a polyline (closed: around the loop), evenly spaced. */
    static void chain(double[] p, boolean closed, List<KeepOut> out) {
        int n = p.length / 2;
        if (n == 0) {
            return;
        }
        int segs = closed ? n : n - 1;
        double total = 0;
        for (int i = 0; i < segs; i++) {
            int j = (i + 1) % n;
            total += Math.hypot(p[2 * j] - p[2 * i], p[2 * j + 1] - p[2 * i + 1]);
        }
        if (total < 1e-9) {
            out.add(new KeepOut(p[0], p[1], EDGE_R));
            return;
        }
        int count = (int) Math.ceil(total / EDGE_STEP - 1e-9);
        double step = total / count;
        int last = closed ? count - 1 : count; // a closed loop's end is its start
        int seg = 0;
        double segStart = 0;
        for (int k = 0; k <= last; k++) {
            double s = Math.min(total, k * step);
            while (seg < segs - 1) {
                int j = (seg + 1) % n;
                double len = Math.hypot(p[2 * j] - p[2 * seg], p[2 * j + 1] - p[2 * seg + 1]);
                if (segStart + len >= s) {
                    break;
                }
                segStart += len;
                seg++;
            }
            int j = (seg + 1) % n;
            double len = Math.hypot(p[2 * j] - p[2 * seg], p[2 * j + 1] - p[2 * seg + 1]);
            double t = len < 1e-12 ? 0 : Math.max(0, Math.min(1, (s - segStart) / len));
            double x = p[2 * seg] + t * (p[2 * j] - p[2 * seg]);
            double y = p[2 * seg + 1] + t * (p[2 * j + 1] - p[2 * seg + 1]);
            out.add(new KeepOut(ArtPath.round(x, 3), ArtPath.round(y, 3), EDGE_R));
        }
    }
}
