package org.openpnp.instancelabs.board;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.openpnp.instancelabs.board.ArtPath.Shape;
import org.openpnp.instancelabs.kicad.BoardFrame;
import org.openpnp.instancelabs.kicad.Deadline;
import org.openpnp.instancelabs.kicad.ImportProposal;
import org.openpnp.instancelabs.kicad.ImportProposal.FootprintP;
import org.openpnp.instancelabs.kicad.ImportProposal.PackageP;
import org.openpnp.instancelabs.kicad.ImportProposal.PadP;
import org.openpnp.instancelabs.kicad.ImportProposal.PlacementP;
import org.openpnp.instancelabs.kicad.ImportWarning;
import org.openpnp.instancelabs.kicad.KGeom;
import org.openpnp.instancelabs.kicad.KText;
import org.openpnp.instancelabs.kicad.KicadPcb;
import org.openpnp.instancelabs.kicad.KicadPcb.Footprint;
import org.openpnp.instancelabs.kicad.KicadPcb.Graphic;
import org.openpnp.instancelabs.kicad.KicadPcb.Pad;
import org.openpnp.instancelabs.kicad.KicadPcb.Track;
import org.openpnp.instancelabs.kicad.KicadPcb.Via;
import org.openpnp.instancelabs.kicad.KicadPcb.Zone;
import org.openpnp.instancelabs.kicad.PasteRules;
import org.openpnp.instancelabs.kicad.SExprException;

/**
 * KiCad board → {@link BoardArt} (design §4.2), in the board frame. The layers that must line up with the camera and
 * the job overlay (outline, mask openings, exposed copper, paste, holes, fiducials) are always drawn within 0.01 mm;
 * copper, tracks, silkscreen and part outlines are simplified further (0.02, 0.04, 0.08 mm) until the drawing fits
 * 400 KB. Holes, top copper and mask openings come from every footprint, top or bottom (§2.7); vias only when their
 * layer pair includes F.Cu. Too many points (2,000,000) or bytes (2 MB) drop the tracks, then the copper pours.
 */
public final class BoardArtBuilder {
    public static final int TARGET_BYTES = 400 * 1024;
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final long MAX_POINTS = 2_000_000;
    /** The fine tolerance and the coarse steps (mm). */
    static final double FINE = 0.01;
    static final double[] COARSE = {0.01, 0.02, 0.04, 0.08};
    /** End points of lines closer than this are joined into one polyline. */
    static final double JOIN_MM = 0.0005;
    /** Line width when the file gives none (silkscreen, part outlines). */
    static final double DEFAULT_LINE_MM = 0.1;
    /** Mask openings at least this much wider than their copper show in the thumbnail. */
    static final double THUMB_MASK_MARGIN = 0.1;

    private int targetBytes = TARGET_BYTES;
    private int maxBytes = MAX_BYTES;
    private long maxPoints = MAX_POINTS;

    /** The art and its notes (ZONES_UNFILLED, ART_SIMPLIFIED, ART_REDUCED): the caller adds them to the proposal. */
    public static final class Result {
        public final BoardArt art;
        public final List<ImportWarning> warnings;

        Result(BoardArt art, List<ImportWarning> warnings) {
            this.art = art;
            this.warnings = Collections.unmodifiableList(warnings);
        }
    }

    /** Other budgets (tests): the size target, the size limit and the point limit. */
    public BoardArtBuilder budget(int targetBytes, int maxBytes, long maxPoints) {
        this.targetBytes = targetBytes;
        this.maxBytes = maxBytes;
        this.maxPoints = maxPoints;
        return this;
    }

    public Result build(KicadPcb pcb, BoardFrame frame, ImportProposal proposal, Deadline deadline)
            throws SExprException {
        return new Work(pcb, frame, proposal, deadline).run();
    }

    // ---- the work of one build -------------------------------------------------------------------------------------

    private final class Work {
        final KicadPcb pcb;
        final BoardFrame frame;
        final ImportProposal proposal;
        final Deadline deadline;
        final BoardArt art = new BoardArt();
        final List<ImportWarning> warnings = new ArrayList<>();

        // the fine layers (always 0.01 mm)
        final ArtPath cuPads = new ArtPath();
        final ArtPath maskOpen = new ArtPath();
        final ArtPath exposed = new ArtPath();
        final ArtPath paste = new ArtPath();
        final ArtPath pth = new ArtPath();
        final ArtPath npth = new ArtPath();
        final ArtPath via = new ArtPath();
        final List<Shape> thumbMask = new ArrayList<>();
        final List<Shape> thumbExposed = new ArrayList<>();
        /** The current footprint's top copper pads without a mask opening of their own (a drawn one may open them). */
        final List<Shape> covered = new ArrayList<>();
        long finePoints;

        // the coarse layers, kept unsimplified so each tolerance starts from the same points
        final List<double[]> cuGraphics = new ArrayList<>();          // filled F.Cu graphics
        final List<double[]> zoneFills = new ArrayList<>();
        final SortedMap<Long, List<Line>> tracks = new TreeMap<>();
        final List<double[]> silkFill = new ArrayList<>();
        final SortedMap<Long, List<Line>> silkLines = new TreeMap<>();
        final SortedMap<Long, List<Line>> fabLines = new TreeMap<>();
        boolean dropTracks;
        boolean dropZones;
        boolean dropSilkFab;

        Work(KicadPcb pcb, BoardFrame frame, ImportProposal proposal, Deadline deadline) {
            this.pcb = pcb;
            this.frame = frame;
            this.proposal = proposal;
            this.deadline = deadline;
        }

        Result run() throws SExprException {
            art.kind = BoardArt.KIND_KICAD;
            art.size = new double[] {frame.widthMm(), frame.heightMm()};
            art.origin = frame.origin;
            art.finish.mask = finish(pcb.setup.maskColor, true);
            art.finish.silk = finish(pcb.setup.silkColor, false);
            art.source.kicadVersion = pcb.version;
            String gen = pcb.generator == null ? null : KText.clean(pcb.generator, 60);
            if (gen != null && pcb.generatorVersion != null) {
                gen = KText.clean(gen + " " + pcb.generatorVersion, 60);
            }
            art.source.generator = gen;

            outline();
            deadline.check();
            budgetOptional();
            for (int i = 0; i < pcb.footprints.size(); i++) {
                deadline.check();
                footprint(pcb.footprints.get(i), i);
            }
            boardItems();
            deadline.check();
            pasteLayer();
            fiducials();
            texts();
            deadline.check();

            art.layers.mask.open = maskOpen.toString();
            art.layers.exposed.fill = exposed.toString();
            art.layers.paste.fill = paste.toString();
            art.layers.holes.pth = pth.toString();
            art.layers.holes.npth = npth.toString();
            art.layers.holes.via = via.toString();
            art.thumb.mask = ArtPath.thumb(thumbMask);
            art.thumb.exposed = ArtPath.thumb(thumbExposed);

            fit();
            return new Result(art, warnings);
        }

        // ---- outline -------------------------------------------------------------------------------------------

        void outline() throws SExprException {
            ArtPath d = new ArtPath();
            ArtPath t = new ArtPath(ArtPath.THUMB_DECIMALS);
            if (frame.outlineLoop != null) {
                d.polygon(KGeom.simplify(frame.outlineLoop, FINE, true, deadline));
                thumbLoop(t, frame.outlineLoop);
            }
            else if (!frame.openChains.isEmpty()) {
                for (double[] c : frame.openChains) {
                    d.polyline(KGeom.simplify(c, FINE, false, deadline));
                    t.polyline(KGeom.simplify(c, ArtPath.THUMB_MM, false, deadline));
                }
            }
            else {
                double[] box = {0, 0, art.size[0], 0, art.size[0], art.size[1], 0, art.size[1]};
                d.polygon(box);
                t.polygon(box);
            }
            ArtPath c = new ArtPath();
            for (double[] loop : frame.cutouts) {
                c.polygon(KGeom.simplify(loop, FINE, true, deadline));
                thumbLoop(t, loop);
            }
            art.outline.d = d.toString();
            art.outline.cutouts = c.toString();
            art.outline.closed = BoardFrame.OUTLINE_CLOSED.equals(frame.outline);
            art.thumb.outline = t.toString();
            finePoints += d.points() + c.points();
        }

        /** A loop in the thumbnail: a circle (a drilled cut-out) as two arcs, anything else simplified at 0.1 mm. */
        void thumbLoop(ArtPath t, double[] loop) throws SExprException {
            double[] c = circleOf(loop);
            if (c != null) {
                t.circle(c[0], c[1], c[2]);
            }
            else {
                t.polygon(KGeom.simplify(loop, ArtPath.THUMB_MM, true, deadline));
            }
        }

        // ---- the point budget ----------------------------------------------------------------------------------

        /**
         * Counts the points of the optional layers before making them: tracks, then copper pours (and, past the
         * design's two steps, silkscreen and part outlines) are left out when the drawing would exceed the budget.
         */
        void budgetOptional() throws SExprException {
            long trackPts = 0;
            for (Track tr : pcb.tracks) {
                if ("F.Cu".equals(tr.layer)) {
                    trackPts += tr.mx == null ? 2 : arcPoints(tr.x1, tr.y1, tr.mx, tr.my, tr.x2, tr.y2);
                }
            }
            long zonePts = 0;
            long silkFabPts = 0;
            long essential = 0;
            for (Zone z : allZones()) {
                List<double[]> f = z.filled.get("F.Cu");
                if (f != null) {
                    for (double[] p : f) {
                        zonePts += p.length / 2;
                    }
                }
            }
            List<Graphic> all = new ArrayList<>(pcb.graphics);
            for (Footprint fp : pcb.footprints) {
                all.addAll(fp.graphics);
                for (Pad p : fp.pads) {
                    essential += 12;
                    for (Graphic g : p.primitives) {
                        essential += estimate(g);
                    }
                }
            }
            for (Graphic g : all) {
                String layer = g.layer == null ? "" : g.layer;
                if ("F.Cu".equals(layer) && !g.filled) {
                    trackPts += estimate(g);
                }
                else if (layer.equals("F.SilkS") || layer.equals("F.Fab") || layer.equals("F.CrtYd")) {
                    silkFabPts += estimate(g);
                }
                else if (layer.equals("F.Cu") || layer.equals("F.Mask") || layer.equals("F.Paste")) {
                    essential += estimate(g);
                }
            }
            essential += 12L * pcb.vias.size() + finePoints;
            if (essential > maxPoints) {
                throw SExprException.limit(0, String.format(Locale.ROOT,
                        "its drawing has more than %,d points even without tracks, copper pours and silkscreen",
                        maxPoints));
            }
            long total = essential + trackPts + zonePts + silkFabPts;
            if (total > maxPoints) {
                dropTracks = true;
                total -= trackPts;
            }
            if (total > maxPoints) {
                dropZones = true;
                total -= zonePts;
            }
            if (total > maxPoints) {
                dropSilkFab = true;
            }
        }

        List<Zone> allZones() {
            List<Zone> l = new ArrayList<>(pcb.zones);
            for (Footprint fp : pcb.footprints) {
                l.addAll(fp.zones);
            }
            return l;
        }

        // ---- footprints ----------------------------------------------------------------------------------------

        void footprint(Footprint fp, int index) throws SExprException {
            covered.clear();
            for (Pad p : fp.pads) {
                pad(fp, p);
            }
            for (Graphic g : fp.graphics) {
                graphic(g, fp);
            }
            if (fp.top) {
                fab(fp);
            }
            part(fp, index);
            checkFine();
        }

        void pad(Footprint fp, Pad p) {
            boolean cu = p.onFrontCopper() && hasCopper(p);
            boolean mask = p.onFrontMask();
            Shape copper = cu ? padShape(fp, p, 0) : null;
            if (copper != null) {
                finePoints += copper.emit(cuPads);
                if (!mask) {
                    covered.add(copper);
                }
            }
            if (mask) {
                double m = PasteRules.maskMargin(pcb, fp, p);
                Shape open = padShape(fp, p, m);
                if (open != null) {
                    finePoints += open.emit(maskOpen);
                    if (copper == null || m >= THUMB_MASK_MARGIN) {
                        thumbMask.add(open);
                    }
                }
                if (copper != null) {
                    Shape ex = m >= 0 ? copper : open;
                    if (ex != null) {
                        finePoints += ex.emit(exposed);
                        thumbExposed.add(ex);
                    }
                }
            }
            if (p.drillW > 0 && ("thru_hole".equals(p.type) || "np_thru_hole".equals(p.type))) {
                boolean plated = "thru_hole".equals(p.type);
                double[] c = padPoint(fp, p, 0, 0);
                ArtPath into = plated ? pth : npth;
                if (Math.abs(p.drillW - p.drillH) < 1e-9) {
                    into.circle(c[0], c[1], p.drillW / 2);
                }
                else {
                    into.roundRect(c[0], c[1], p.drillW, p.drillH, Math.min(p.drillW, p.drillH) / 2, p.angle);
                }
                finePoints += 4;
                double r;
                double[] at = c;
                if (plated && copper != null) {
                    r = Math.max(p.w, p.h) / 2;
                    at = padPoint(fp, p, p.offX, p.offY);
                }
                else {
                    r = Math.max(p.drillW, p.drillH) / 2 + (plated ? 0.25 : 0);
                }
                art.keepOutHoles.add(new double[] {r2(at[0]), r2(at[1]), r2(r)});
            }
        }

        /** An unplated hole has copper only when its pad is larger than its drill. */
        boolean hasCopper(Pad p) {
            if (!"np_thru_hole".equals(p.type)) {
                return true;
            }
            return p.w > p.drillW + 0.01 || p.h > p.drillH + 0.01;
        }

        /** A pad's copper grown by m (mm) in the board frame, or null when nothing is left. */
        Shape padShape(Footprint fp, Pad p, double m) {
            double[] c = padPoint(fp, p, p.offX, p.offY);
            String shape = p.shape == null ? "" : p.shape;
            double w = p.w + 2 * m;
            double h = p.h + 2 * m;
            switch (shape) {
                case "circle": {
                    double r = p.w / 2 + m;
                    return r > 0.005 ? Shape.circle(c[0], c[1], r) : null;
                }
                case "oval":
                    return w > 0.01 && h > 0.01 ? Shape.rect(c[0], c[1], w, h, Math.min(w, h) / 2, p.angle) : null;
                case "roundrect": {
                    double r0 = p.rratio * Math.min(p.w, p.h);
                    if (p.chamferRatio != null && p.chamferRatio > 0 && !p.chamfer.isEmpty()) {
                        return padPolys(fp, p, Collections.singletonList(chamfered(p, r0)), m);
                    }
                    return w > 0.01 && h > 0.01 ? Shape.rect(c[0], c[1], w, h, Math.max(0, r0 + m), p.angle) : null;
                }
                case "trapezoid": {
                    double hw = p.w / 2;
                    double hh = p.h / 2;
                    double dx = p.deltaX / 2;
                    double dy = p.deltaY / 2;
                    double[] q = {-hw - dy, hh + dx, -hw + dy, -hh - dx, hw - dy, -hh + dx, hw + dy, hh - dx};
                    return padPolys(fp, p, Collections.singletonList(q), m);
                }
                case "custom":
                    return padPolys(fp, p, customPolys(p), m);
                default: // rect and anything unknown
                    return w > 0.01 && h > 0.01 ? Shape.rect(c[0], c[1], w, h, Math.max(0, m), p.angle) : null;
            }
        }

        /** Polygons in the pad's own frame (KiCad, around the shape centre) → board frame, grown by m. */
        Shape padPolys(Footprint fp, Pad p, List<double[]> local, double m) {
            List<double[]> out = new ArrayList<>();
            for (double[] q : local) {
                double[] b = new double[q.length];
                for (int i = 0; i + 1 < q.length; i += 2) {
                    double[] k = padPoint(fp, p, p.offX + q[i], p.offY + q[i + 1]);
                    b[i] = k[0];
                    b[i + 1] = k[1];
                }
                b = ccw(b);
                if (m != 0) {
                    b = offset(b, m);
                }
                if (b != null && Math.abs(KGeom.polygonArea(b)) >= ArtPath.MIN_AREA) {
                    out.add(b);
                }
            }
            return out.isEmpty() ? null : Shape.poly(out);
        }

        /** A point of the pad's frame (KiCad, relative to the pad position) in the board frame. */
        double[] padPoint(Footprint fp, Pad p, double a, double b) {
            double t = Math.toRadians(p.angle - fp.angle);
            double c = Math.cos(t);
            double s = Math.sin(t);
            return frame.localToBoard(fp, p.x + a * c + b * s, p.y - a * s + b * c);
        }

        /** A roundrect with chamfered corners as one polygon in the pad's frame (KiCad, Y down). */
        double[] chamfered(Pad p, double r) {
            double hw = p.w / 2;
            double hh = p.h / 2;
            double ch = p.chamferRatio * Math.min(p.w, p.h);
            KGeom.Pts out = new KGeom.Pts();
            // corners in drawing order: top_left (−,−), top_right (+,−), bottom_right (+,+), bottom_left (−,+)
            String[] names = {"top_left", "top_right", "bottom_right", "bottom_left"};
            double[][] k = {{-hw, -hh}, {hw, -hh}, {hw, hh}, {-hw, hh}};
            for (int i = 0; i < 4; i++) {
                double[] prev = k[(i + 3) % 4];
                double[] cur = k[i];
                double[] next = k[(i + 1) % 4];
                double ix = Math.signum(prev[0] - cur[0]);
                double iy = Math.signum(prev[1] - cur[1]);
                double ox = Math.signum(next[0] - cur[0]);
                double oy = Math.signum(next[1] - cur[1]);
                if (p.chamfer.contains(names[i]) && ch > 0) {
                    out.add(cur[0] + ix * ch, cur[1] + iy * ch);
                    out.add(cur[0] + ox * ch, cur[1] + oy * ch);
                }
                else if (r > 0) {
                    double cx = cur[0] + (ix + ox) * r;
                    double cy = cur[1] + (iy + oy) * r;
                    double a0 = Math.atan2(cur[1] + iy * r - cy, cur[0] + ix * r - cx);
                    double a1 = Math.atan2(cur[1] + oy * r - cy, cur[0] + ox * r - cx);
                    double sweep = KGeom.norm2pi(a1 - a0);
                    if (sweep > Math.PI) {
                        sweep -= 2 * Math.PI;
                    }
                    int n = KGeom.arcSegments(r, sweep, FINE);
                    for (int j = 0; j <= n; j++) {
                        double t = a0 + sweep * j / n;
                        out.add(cx + r * Math.cos(t), cy + r * Math.sin(t));
                    }
                }
                else {
                    out.add(cur[0], cur[1]);
                }
            }
            return out.toArray();
        }

        /** The anchor and every primitive of a custom pad as polygons in the pad's frame (KiCad, Y down). */
        List<double[]> customPolys(Pad p) {
            List<double[]> l = new ArrayList<>();
            if ("rect".equals(p.anchor)) {
                l.add(new double[] {-p.w / 2, -p.h / 2, p.w / 2, -p.h / 2, p.w / 2, p.h / 2, -p.w / 2, p.h / 2});
            }
            else {
                l.add(KGeom.circle(0, 0, p.w / 2, FINE));
            }
            for (Graphic g : p.primitives) {
                filledOrStroked(g, true, l);
            }
            return l;
        }

        /**
         * A graphic as filled polygons in its own coordinates: filled shapes as they are, lines and arcs as stadiums
         * of their width (closed shapes of a custom pad, a primitive, count as filled).
         */
        void filledOrStroked(Graphic g, boolean primitive, List<double[]> out) {
            Flat f = flat(g, FINE);
            if (f == null) {
                return;
            }
            boolean fill = g.filled || primitive && (g.kind == Graphic.Kind.POLY || g.kind == Graphic.Kind.RECT
                    || g.kind == Graphic.Kind.CIRCLE);
            if (fill && f.closed) {
                if (g.kind == Graphic.Kind.CIRCLE && g.width > 0) {
                    double r = Math.hypot(g.pts[2] - g.pts[0], g.pts[3] - g.pts[1]) + g.width / 2;
                    out.add(KGeom.circle(g.pts[0], g.pts[1], r, FINE));
                }
                else {
                    out.add(f.pts);
                }
            }
            else {
                stroke(f.pts, f.closed, Math.max(g.width, 0.01), out);
            }
        }

        // ---- graphics --------------------------------------------------------------------------------------------

        /** One graphic of a footprint (fp) or of the board (fp null), sorted onto its layer. */
        void graphic(Graphic g, Footprint fp) throws SExprException {
            String layer = g.layer == null ? "" : g.layer;
            switch (layer) {
                case "F.Cu": {
                    if (g.filled) {
                        Flat f = flat(g, FINE);
                        if (f != null && f.closed) {
                            cuGraphics.add(ccw(toBoard(f.pts, fp)));
                        }
                    }
                    else if (!dropTracks) {
                        lineGraphic(g, fp, tracks, 0.2);
                    }
                    break;
                }
                case "F.Mask": {
                    List<double[]> l = new ArrayList<>();
                    filledOrStroked(g, false, l);
                    List<double[]> b = new ArrayList<>();
                    for (double[] q : l) {
                        b.add(ccw(toBoard(q, fp)));
                    }
                    Shape s = Shape.poly(b);
                    finePoints += s.emit(maskOpen);
                    thumbMask.add(s);
                    if (fp != null) {
                        exposeDrawnOpening(b);
                    }
                    break;
                }
                case "F.Paste":
                    if (fp == null) { // board-level paste: drawn only (BOARD_PASTE_GRAPHIC)
                        List<double[]> l = new ArrayList<>();
                        filledOrStroked(g, false, l);
                        List<double[]> b = new ArrayList<>();
                        for (double[] q : l) {
                            b.add(ccw(toBoard(q, null)));
                        }
                        finePoints += Shape.poly(b).emit(paste);
                    }
                    break;
                case "F.SilkS":
                    if (dropSilkFab) {
                        break;
                    }
                    if (g.filled) {
                        Flat f = flat(g, FINE);
                        if (f != null && f.closed) {
                            silkFill.add(ccw(toBoard(f.pts, fp)));
                        }
                    }
                    else {
                        lineGraphic(g, fp, silkLines, DEFAULT_LINE_MM);
                    }
                    break;
                default:
                    break;
            }
        }

        /**
         * A footprint's drawn mask opening over one of its pads that has no opening of its own (nano IC3's centre
         * pad): the copper it uncovers is exposed. An opening inside the pad exposes the opening, a pad inside the
         * opening exposes the pad; a partial overlap is left covered.
         */
        void exposeDrawnOpening(List<double[]> openings) {
            for (double[] q : openings) {
                for (int i = 0; i < covered.size(); i++) {
                    Shape c = covered.get(i);
                    Shape ex = null;
                    if (allInside(q, c)) {
                        ex = Shape.poly(Collections.singletonList(q));
                    }
                    else if (allInside(c.around(), Shape.poly(Collections.singletonList(q)))) {
                        ex = c;
                        covered.remove(i--); // exposed once
                    }
                    if (ex != null) {
                        finePoints += ex.emit(exposed);
                        thumbExposed.add(ex);
                    }
                }
            }
        }

        /** An unfilled graphic as a line of its width (closed shapes stay closed). */
        void lineGraphic(Graphic g, Footprint fp, SortedMap<Long, List<Line>> into, double defaultWidth) {
            Flat f = flat(g, FINE);
            if (f == null) {
                return;
            }
            double w = g.width > 0 ? g.width : defaultWidth;
            into.computeIfAbsent(widthKey(w), k -> new ArrayList<>()).add(new Line(toBoard(f.pts, fp), f.closed));
        }

        /** Part outlines: the F.Fab graphics, else the courtyard, else the pads' box grown 0.2 mm. */
        void fab(Footprint fp) {
            if (dropSilkFab) {
                return;
            }
            boolean any = false;
            for (String layer : new String[] {"F.Fab", "F.CrtYd"}) {
                for (Graphic g : fp.graphics) {
                    if (layer.equals(g.layer)) {
                        Flat f = flat(g, FINE);
                        if (f != null) {
                            double w = g.width > 0 ? g.width : DEFAULT_LINE_MM;
                            fabLines.computeIfAbsent(widthKey(w), k -> new ArrayList<>())
                                    .add(new Line(toBoard(f.pts, fp), f.closed));
                            any = true;
                        }
                    }
                }
                if (any) {
                    return;
                }
            }
            double[] box = padsBox(fp, false);
            if (KGeom.isEmpty(box)) {
                return;
            }
            double g = 0.2;
            double[] q = {box[0] - g, box[1] - g, box[2] + g, box[1] - g, box[2] + g, box[3] + g, box[0] - g,
                    box[3] + g};
            double[] b = new double[8];
            for (int i = 0; i < 8; i += 2) {
                double[] k = frame.localToBoard(fp, q[i], -q[i + 1]);
                b[i] = k[0];
                b[i + 1] = k[1];
            }
            fabLines.computeIfAbsent(widthKey(DEFAULT_LINE_MM), k -> new ArrayList<>()).add(new Line(b, true));
        }

        /**
         * The box of a footprint's pads in the part frame (+Y up): {x0, y0, x1, y1}; drilledOnly keeps the pads with
         * a hole (bottom-side parts are drawn by their holes).
         */
        double[] padsBox(Footprint fp, boolean drilledOnly) {
            double[] box = KGeom.emptyBox();
            for (Pad p : fp.pads) {
                if (drilledOnly && p.drillW <= 0) {
                    continue;
                }
                double t = Math.toRadians(p.angle - fp.angle);
                double c = Math.cos(t);
                double s = Math.sin(t);
                double hw = p.w / 2;
                double hh = ("circle".equals(p.shape) ? p.w : p.h) / 2;
                for (int k = 0; k < 4; k++) {
                    double a = p.offX + (k == 0 || k == 3 ? -hw : hw);
                    double b = p.offY + (k < 2 ? -hh : hh);
                    double u = p.x + a * c + b * s;
                    double v = p.y - a * s + b * c;
                    KGeom.extend(box, u, -v);
                }
            }
            return box;
        }

        // ---- board-level items -----------------------------------------------------------------------------------

        void boardItems() throws SExprException {
            for (Graphic g : pcb.graphics) {
                graphic(g, null);
            }
            if (!dropTracks) {
                for (Track tr : pcb.tracks) {
                    if (!"F.Cu".equals(tr.layer)) {
                        continue;
                    }
                    double[] p;
                    if (tr.mx == null) {
                        p = new double[] {tr.x1, tr.y1, tr.x2, tr.y2};
                    }
                    else {
                        KGeom.Pts out = new KGeom.Pts();
                        if (!KGeom.flattenArc(tr.x1, tr.y1, tr.mx, tr.my, tr.x2, tr.y2, FINE, out, true)) {
                            continue;
                        }
                        p = out.toArray();
                    }
                    double w = tr.width > 0 ? tr.width : 0.2;
                    tracks.computeIfAbsent(widthKey(w), k -> new ArrayList<>()).add(new Line(frame.toBoard(p), false));
                }
            }
            deadline.check();
            double clearance = pcb.setup.padToMaskClearance == null ? 0 : Math.max(0, pcb.setup.padToMaskClearance);
            for (Via v : pcb.vias) {
                if (!v.layers.contains("F.Cu")) {
                    continue; // buried, or blind from the bottom
                }
                double[] c = frame.toBoard(v.x, v.y);
                Shape copper = Shape.circle(c[0], c[1], v.size / 2);
                finePoints += copper.emit(cuPads);
                via.circle(c[0], c[1], v.drill / 2);
                finePoints += 3;
                if (!v.tentedFront(pcb.setup)) {
                    Shape open = Shape.circle(c[0], c[1], v.size / 2 + clearance);
                    finePoints += open.emit(maskOpen) + copper.emit(exposed);
                    thumbExposed.add(copper);
                }
                art.keepOutHoles.add(new double[] {r2(c[0]), r2(c[1]), r2(v.size / 2)});
            }
            checkFine();
            boolean unfilled = false;
            for (Zone z : allZones()) {
                deadline.check();
                boolean front = z.layers.contains("F.Cu") || z.layers.contains("F&B.Cu") || z.layers.contains("*.Cu");
                if (z.keepout || !front) {
                    continue;
                }
                if (z.filled.isEmpty()) {
                    unfilled = true;
                }
                List<double[]> f = z.filled.get("F.Cu");
                if (f != null && !dropZones) {
                    for (double[] p : f) {
                        zoneFills.add(ccw(frame.toBoard(p)));
                    }
                }
            }
            if (unfilled) {
                warnings.add(ImportWarning.of("ZONES_UNFILLED", null,
                        "Zones are not filled in this file: copper pours are not drawn."));
            }
        }

        // ---- paste, fiducials, parts, texts ----------------------------------------------------------------------

        void pasteLayer() {
            for (PlacementP pl : proposal.placements) {
                if (!"Placement".equals(pl.type)) {
                    continue;
                }
                PackageP pk = proposal.packages.get(pl.packageIndex);
                double t = Math.toRadians(pl.rotation);
                double c = Math.cos(t);
                double s = Math.sin(t);
                for (PadP pad : pk.pads) {
                    double x = pl.x + pad.x * c - pad.y * s;
                    double y = pl.y + pad.x * s + pad.y * c;
                    double r = Math.min(pad.w, pad.h) * Math.max(0, Math.min(100, pad.roundness)) / 200;
                    finePoints += Shape.rect(x, y, pad.w, pad.h, r, pl.rotation + pad.rotation).emit(paste);
                }
            }
        }

        void fiducials() {
            for (PlacementP pl : proposal.placements) {
                if (!"Fiducial".equals(pl.type)) {
                    continue;
                }
                Footprint fp = pcb.footprints.get(pl.footprintIndex);
                Pad copper = null;
                for (Pad p : fp.pads) {
                    if (p.onFrontCopper()) {
                        copper = p;
                        break;
                    }
                }
                BoardArt.FidArt f = new BoardArt.FidArt();
                f.name = pl.id;
                f.bx = pl.x;
                f.by = pl.y;
                if (copper != null) {
                    double d = "circle".equals(copper.shape) ? copper.w : Math.min(copper.w, copper.h);
                    f.copperMm = r4(d);
                    double mask = d + 2 * PasteRules.maskMargin(pcb, fp, copper);
                    f.maskMm = r4(Math.max(mask, 2 * maskDrawnRadius(fp, copper)));
                }
                art.fiducials.add(f);
            }
        }

        /**
         * How far the footprint's F.Mask drawings reach from the pad's centre (0 without any): a fiducial whose
         * opening is drawn (nano: a ring of width 1.2 mm on F.Mask) rather than made by a mask margin.
         */
        double maskDrawnRadius(Footprint fp, Pad pad) {
            double r = 0;
            for (Graphic g : fp.graphics) {
                if (!"F.Mask".equals(g.layer)) {
                    continue;
                }
                double half = g.width / 2;
                if (g.kind == Graphic.Kind.CIRCLE) {
                    double rc = Math.hypot(g.pts[2] - g.pts[0], g.pts[3] - g.pts[1]);
                    r = Math.max(r, Math.hypot(g.pts[0] - pad.x, g.pts[1] - pad.y) + rc + half);
                    continue;
                }
                Flat fl = flat(g, FINE);
                if (fl != null) {
                    for (int i = 0; i + 1 < fl.pts.length; i += 2) {
                        r = Math.max(r, Math.hypot(fl.pts[i] - pad.x, fl.pts[i + 1] - pad.y) + half);
                    }
                }
            }
            return r;
        }

        void part(Footprint fp, int index) {
            FootprintP f = index < proposal.footprints.size() ? proposal.footprints.get(index) : null;
            if (f == null) {
                return;
            }
            boolean bottom = FootprintP.BOTTOM.equals(f.kind);
            double[] box;
            if (bottom) {
                box = padsBox(fp, true);
                if (KGeom.isEmpty(box)) {
                    return; // a bottom-side part without holes is not seen from the top
                }
            }
            else {
                box = graphicsBox(fp, "F.Fab");
                if (KGeom.isEmpty(box)) {
                    box = graphicsBox(fp, "F.CrtYd");
                }
                if (KGeom.isEmpty(box)) {
                    box = padsBox(fp, false);
                }
                if (KGeom.isEmpty(box)) {
                    box = new double[] {-0.5, -0.5, 0.5, 0.5};
                }
            }
            BoardArt.Part p = new BoardArt.Part();
            p.ref = f.ref;
            p.kind = f.kind;
            PlacementP pl = f.placement >= 0 ? proposal.placements.get(f.placement) : null;
            p.pkg = pl != null ? proposal.packages.get(pl.packageIndex).baseId : KText.cleanId(fp.name, "FP");
            p.value = KText.clean(fp.value, KText.ID_MAX);
            double[] at = frame.toBoard(fp.x, fp.y);
            p.x = KGeom.round6(at[0]);
            p.y = KGeom.round6(at[1]);
            p.a = KGeom.round6(fp.angle);
            p.box = new double[] {r2(box[0]), r2(box[1]), r2(box[2]), r2(box[3])};
            p.pads = pl != null ? pl.pads : fp.pads.size();
            if (!bottom && !fp.refHidden && "F.SilkS".equals(fp.refLayer)) {
                double[] l = frame.localToBoard(fp, fp.refX, fp.refY);
                p.label = new BoardArt.Label();
                p.label.x = r2(l[0]);
                p.label.y = r2(l[1]);
                p.label.a = r2(KGeom.normDeg(fp.refAngle));
                p.label.h = r2(fp.refSize > 0 ? fp.refSize : 1.0);
            }
            art.parts.add(p);
        }

        /** The box of a footprint's graphics on one layer in the part frame (+Y up). */
        double[] graphicsBox(Footprint fp, String layer) {
            double[] box = KGeom.emptyBox();
            for (Graphic g : fp.graphics) {
                if (layer.equals(g.layer)) {
                    double[] b = BoardFrame.graphicBox(g.kind, g.pts);
                    if (!KGeom.isEmpty(b)) {
                        KGeom.extend(box, b[0], -b[3]);
                        KGeom.extend(box, b[2], -b[1]);
                    }
                }
            }
            return box;
        }

        void texts() {
            for (KicadPcb.Text t : pcb.texts) {
                if (!"F.SilkS".equals(t.layer) || t.hidden) {
                    continue;
                }
                String[] rows = (t.text == null ? "" : t.text).split("\n", -1);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rows.length; i++) {
                    sb.append(i == 0 ? "" : "\n").append(KText.clean(rows[i], 200));
                }
                String s = capCodePoints(sb.toString(), 200);
                if (s.trim().isEmpty()) {
                    continue;
                }
                double h = t.size > 0 ? t.size : 1.0;
                double[] b = frame.toBoard(t.x, t.y);
                String j = t.justify == null ? "" : " " + t.justify + " ";
                double block = h + (rows.length - 1) * 1.6 * h; // the renderer's line pitch
                double a = Math.toRadians(t.angle);
                double down = j.contains(" top ") ? block / 2 : j.contains(" bottom ") ? -block / 2 : 0;
                BoardArt.Text x = new BoardArt.Text();
                x.t = s;
                x.x = r2(b[0] + down * Math.sin(a));
                x.y = r2(b[1] - down * Math.cos(a));
                x.a = r2(KGeom.normDeg(t.angle));
                x.h = r2(h);
                x.anchor = j.contains(" left ") ? "start" : j.contains(" right ") ? "end" : "middle";
                art.texts.add(x);
            }
        }

        // ---- the coarse layers and the size budget ---------------------------------------------------------------

        void fit() throws SExprException {
            for (SortedMap<Long, List<Line>> groups : Arrays.asList(tracks, silkLines, fabLines)) {
                chain(groups);
            }
            double used = COARSE[0];
            for (double t : COARSE) {
                deadline.check();
                used = t;
                if (render(t) <= targetBytes) {
                    break;
                }
            }
            if (art.stats.bytes > maxBytes && !dropTracks) {
                dropTracks = true;
                tracks.clear();
                render(used);
            }
            if (art.stats.bytes > maxBytes && !dropZones) {
                dropZones = true;
                zoneFills.clear();
                render(used);
            }
            if (used > COARSE[0]) {
                warnings.add(ImportWarning.of("ART_SIMPLIFIED", null, String.format(Locale.ROOT, "The drawing was "
                        + "simplified to stay small: copper, tracks and silkscreen are drawn within %.2f mm.", used)));
            }
            if (dropTracks || dropZones || dropSilkFab) {
                String what = dropSilkFab ? "tracks, copper pours, silkscreen and part outlines are"
                        : dropZones ? "tracks and copper pours are" : "tracks are";
                warnings.add(ImportWarning.of("ART_REDUCED", null,
                        "The board is too detailed to draw in full: " + what + " not drawn."));
            }
        }

        /** Simplifies the coarse layers at t, fills the art and returns its size in bytes. */
        long render(double t) throws SExprException {
            ArtPath cu = new ArtPath().append(cuPads);
            long pts = finePoints;
            for (List<double[]> l : Arrays.asList(cuGraphics, zoneFills)) {
                for (double[] q : l) {
                    pts += polygon(cu, q, t);
                }
                deadline.check();
            }
            ArtPath silk = new ArtPath();
            for (double[] q : silkFill) {
                pts += polygon(silk, q, t);
            }
            art.layers.cu.fill = cu.toString();
            art.layers.silk.fill = silk.toString();
            long[] n = new long[1];
            art.layers.cu.tracks = strokes(tracks, t, n);
            art.layers.silk.lines = strokes(silkLines, t, n);
            art.layers.fab.lines = strokes(fabLines, t, n);
            pts += n[0];
            art.stats.points = pts;
            art.stats.toleranceMm = FINE;
            art.stats.coarseToleranceMm = t;
            art.measure();
            return art.stats.bytes;
        }

        /** Simplifies a polygon at t into p (dropped below ArtPath.MIN_AREA); returns the points written. */
        long polygon(ArtPath p, double[] q, double t) throws SExprException {
            double[] s = KGeom.simplify(q, t, true, deadline);
            if (Math.abs(KGeom.polygonArea(s)) < ArtPath.MIN_AREA) {
                return 0;
            }
            long before = p.points();
            p.polygon(s);
            return p.points() - before;
        }

        /** Joins the open lines of each width end to end (fewer, longer paths), once for every tolerance. */
        void chain(SortedMap<Long, List<Line>> groups) throws SExprException {
            for (Map.Entry<Long, List<Line>> e : groups.entrySet()) {
                deadline.check();
                List<Line> joined = new ArrayList<>();
                List<double[]> open = new ArrayList<>();
                for (Line l : e.getValue()) {
                    if (l.closed) {
                        joined.add(l);
                    }
                    else {
                        open.add(l.pts);
                    }
                }
                for (KGeom.Chain c : KGeom.chain(open, null, JOIN_MM, deadline)) {
                    joined.add(new Line(c.pts, c.closed));
                }
                e.setValue(joined);
            }
        }

        /** One path per width, each line simplified at t. */
        List<BoardArt.Stroke> strokes(SortedMap<Long, List<Line>> groups, double t, long[] points)
                throws SExprException {
            SortedMap<Long, ArtPath> out = new TreeMap<>();
            for (Map.Entry<Long, List<Line>> e : groups.entrySet()) {
                deadline.check();
                ArtPath p = new ArtPath();
                for (Line l : e.getValue()) {
                    double[] s = KGeom.simplify(l.pts, t, l.closed, deadline);
                    if (l.closed) {
                        p.polygon(s);
                    }
                    else {
                        p.polyline(s);
                    }
                }
                points[0] += p.points();
                out.put(e.getKey(), p);
            }
            return BoardArt.strokes(out);
        }

        // ---- helpers ---------------------------------------------------------------------------------------------

        void checkFine() throws SExprException {
            if (finePoints > maxPoints) {
                throw SExprException.limit(0, String.format(Locale.ROOT,
                        "its drawing has more than %,d points even without tracks and copper pours", maxPoints));
            }
        }

        /** Points of a footprint graphic (fp) or a board graphic (fp null) in the board frame. */
        double[] toBoard(double[] p, Footprint fp) {
            if (fp == null) {
                return frame.toBoard(p);
            }
            double[] r = new double[p.length];
            for (int i = 0; i + 1 < p.length; i += 2) {
                double[] b = frame.localToBoard(fp, p[i], p[i + 1]);
                r[i] = b[0];
                r[i + 1] = b[1];
            }
            return r;
        }
    }

    /** A line (polyline) in the board frame. */
    static final class Line {
        final double[] pts;
        final boolean closed;

        Line(double[] pts, boolean closed) {
            this.pts = pts;
            this.closed = closed;
        }
    }

    /** A graphic flattened in its own coordinates: a polyline, or a closed polygon. */
    static final class Flat {
        final double[] pts;
        final boolean closed;

        Flat(double[] pts, boolean closed) {
            this.pts = pts;
            this.closed = closed;
        }
    }

    static Flat flat(Graphic g, double chord) {
        double[] p = g.pts;
        switch (g.kind) {
            case LINE:
                return new Flat(p.clone(), false);
            case ARC: {
                KGeom.Pts out = new KGeom.Pts();
                if (!KGeom.flattenArc(p[0], p[1], p[2], p[3], p[4], p[5], chord, out, true)) {
                    return null;
                }
                return new Flat(out.toArray(), false);
            }
            case CIRCLE: {
                double r = Math.hypot(p[2] - p[0], p[3] - p[1]);
                return r > 0 ? new Flat(KGeom.circle(p[0], p[1], r, chord), true) : null;
            }
            case RECT:
                return new Flat(BoardFrame.rectCorners(p), true);
            case POLY:
                return p.length >= 6 ? new Flat(p.clone(), true) : new Flat(p.clone(), false);
            case BEZIER: {
                KGeom.Pts out = new KGeom.Pts();
                KGeom.flattenBezier(p, out, true);
                return new Flat(out.toArray(), false);
            }
            default:
                return null;
        }
    }

    /** Points a graphic makes when flattened at 0.01 mm (the budget counts them before making them). */
    static long estimate(Graphic g) {
        double[] p = g.pts;
        switch (g.kind) {
            case ARC:
                return arcPoints(p[0], p[1], p[2], p[3], p[4], p[5]);
            case CIRCLE:
                return Math.max(8, KGeom.arcSegments(Math.hypot(p[2] - p[0], p[3] - p[1]), 2 * Math.PI, FINE));
            case POLY:
                return p.length / 2;
            case BEZIER:
                return KGeom.BEZIER_MAX_SEGMENTS + 1;
            default:
                return 4;
        }
    }

    static long arcPoints(double sx, double sy, double mx, double my, double ex, double ey) {
        double[] a = KGeom.arc3(sx, sy, mx, my, ex, ey);
        return a == null ? 2 : KGeom.arcSegments(a[2], a[4], FINE) + 1;
    }

    /**
     * Lines of a width as stadium polygons (a rectangle with round caps per segment), for mask openings and custom
     * pads, which are fills.
     */
    static void stroke(double[] p, boolean closed, double width, List<double[]> out) {
        int n = p.length / 2;
        double r = width / 2;
        int segs = closed ? n : n - 1;
        for (int i = 0; i < segs; i++) {
            int j = (i + 1) % n;
            double ax = p[2 * i];
            double ay = p[2 * i + 1];
            double bx = p[2 * j];
            double by = p[2 * j + 1];
            double len = Math.hypot(bx - ax, by - ay);
            if (len < 1e-9) {
                continue;
            }
            double a = Math.atan2(by - ay, bx - ax);
            int k = Math.max(2, KGeom.arcSegments(r, Math.PI, FINE));
            KGeom.Pts q = new KGeom.Pts();
            for (int s = 0; s <= k; s++) { // the cap around b, from the right side to the left side
                double t = a - Math.PI / 2 + Math.PI * s / k;
                q.add(bx + r * Math.cos(t), by + r * Math.sin(t));
            }
            for (int s = 0; s <= k; s++) { // the cap around a
                double t = a + Math.PI / 2 + Math.PI * s / k;
                q.add(ax + r * Math.cos(t), ay + r * Math.sin(t));
            }
            out.add(q.toArray());
        }
    }

    /** The polygon counter-clockwise in the board frame (+Y up): merged fills then add up under nonzero. */
    static double[] ccw(double[] p) {
        return KGeom.polygonArea(p) < 0 ? KGeom.reverse(p) : p;
    }

    /**
     * A counter-clockwise polygon grown by d (shrunk when d &lt; 0), mitred corners limited to 4·|d|; null when a
     * shrink leaves nothing.
     */
    static double[] offset(double[] p, double d) {
        int n = p.length / 2;
        double[] r = new double[p.length];
        for (int i = 0; i < n; i++) {
            int a = (i + n - 1) % n;
            int b = (i + 1) % n;
            double[] n1 = normal(p[2 * a], p[2 * a + 1], p[2 * i], p[2 * i + 1]);
            double[] n2 = normal(p[2 * i], p[2 * i + 1], p[2 * b], p[2 * b + 1]);
            double bx = n1[0] + n2[0];
            double by = n1[1] + n2[1];
            double bl = Math.hypot(bx, by);
            if (bl < 1e-12) {
                bx = n1[0];
                by = n1[1];
                bl = 1;
            }
            bx /= bl;
            by /= bl;
            double cos = bx * n1[0] + by * n1[1];
            double len = Math.min(4, 1 / Math.max(1e-6, cos)) * d;
            r[2 * i] = p[2 * i] + bx * len;
            r[2 * i + 1] = p[2 * i + 1] + by * len;
        }
        return KGeom.polygonArea(r) * KGeom.polygonArea(p) > 0 ? r : null;
    }

    /** The outward unit normal of a counter-clockwise edge a → b. */
    private static double[] normal(double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double l = Math.hypot(dx, dy);
        return l < 1e-12 ? new double[] {0, 0} : new double[] {dy / l, -dx / l};
    }

    /** True when every point (x0 y0 x1 y1 …) lies in the shape. */
    static boolean allInside(double[] pts, Shape s) {
        for (int i = 0; i + 1 < pts.length; i += 2) {
            if (!s.contains(pts[i], pts[i + 1])) {
                return false;
            }
        }
        return pts.length >= 2;
    }

    /** {cx, cy, r} when every point of the loop lies within 0.01 mm of one circle (8 points or more), else null. */
    static double[] circleOf(double[] loop) {
        int n = loop.length / 2;
        if (n < 8) {
            return null;
        }
        double cx = 0;
        double cy = 0;
        for (int i = 0; i < n; i++) {
            cx += loop[2 * i];
            cy += loop[2 * i + 1];
        }
        cx /= n;
        cy /= n;
        double min = Double.POSITIVE_INFINITY;
        double max = 0;
        for (int i = 0; i < n; i++) {
            double r = Math.hypot(loop[2 * i] - cx, loop[2 * i + 1] - cy);
            min = Math.min(min, r);
            max = Math.max(max, r);
        }
        return max - min <= 0.01 && min > 0 ? new double[] {cx, cy, (min + max) / 2} : null;
    }

    /** Widths are grouped to 0.01 mm. */
    static long widthKey(double w) {
        return Math.max(1, Math.round(w * 100));
    }

    static double r2(double v) {
        return ArtPath.round(v, 2);
    }

    static double r4(double v) {
        return ArtPath.round(v, 4);
    }

    /** The stackup colour → the palette keys of §5.3 (the last word of KiCad's name: "Light Green" is green). */
    static String finish(String color, boolean mask) {
        String def = mask ? "slate" : "white";
        if (color == null) {
            return def;
        }
        String c = color.trim().toLowerCase(Locale.ROOT);
        int sp = c.lastIndexOf(' ');
        String last = sp < 0 ? c : c.substring(sp + 1);
        List<String> names = mask
                ? Arrays.asList("green", "red", "blue", "purple", "black", "white", "yellow")
                : Arrays.asList("white", "black", "yellow");
        return names.contains(last) ? last : def;
    }

    static String capCodePoints(String s, int max) {
        if (s.codePointCount(0, s.length()) <= max) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, max));
    }
}
