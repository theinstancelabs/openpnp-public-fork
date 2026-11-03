package org.openpnp.instancelabs.kicad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.openpnp.instancelabs.kicad.KicadPcb.Footprint;
import org.openpnp.instancelabs.kicad.KicadPcb.Graphic;
import org.openpnp.instancelabs.kicad.KicadPcb.Pad;

/**
 * The paste apertures of one footprint (design §2.8), in the part frame OpenPnP stores (+Y up, mm, rotation relative
 * to the footprint): pads on F.Paste grown by KiCad's paste margin and ratio (pad > footprint > board), filled F.Paste
 * shapes and zone fills inside the footprint. Shapes OpenPnP cannot hold (trapezoid, custom, polygons that are not
 * rectangles) become their enclosing rectangle with a warning. Also names the openings uniquely.
 */
public final class PasteRules {
    /** An opening this thin or thinner is gone. */
    public static final double GONE_MM = 0.01;

    private PasteRules() {
    }

    /** One opening in the part frame. */
    public static final class Aperture {
        public String name;
        /** The source pad, or null for a drawn shape or a zone fill. */
        public final Pad pad;
        public double x;
        public double y;
        public double w;
        public double h;
        public double rotation;
        public double roundness;
        /** null, or what was approximated: "trapezoid", "custom", "polygon". */
        public String approx;
        boolean emptyName;
        String host;
        double polyAreaPct;

        Aperture(Pad pad) {
            this.pad = pad;
        }

        public double areaMm2() {
            double r = Math.min(w, h) / 2.0 * Math.max(0, Math.min(100, roundness)) / 100.0;
            return w * h - (4.0 - Math.PI) * r * r;
        }
    }

    /** A note about one footprint: the code and the text after its reference, so equal notes of a package merge. */
    public static final class Note {
        public final String code;
        public final String detail;

        Note(String code, String detail) {
            this.code = code;
            this.detail = detail;
        }
    }

    public static final class Result {
        public final List<Aperture> apertures = new ArrayList<>();
        public final List<Note> notes = new ArrayList<>();
    }

    // ---- margins -------------------------------------------------------------------------------------------------

    /**
     * KiCad's precedence pad → footprint → board → 0 with the zero rule of §2.5: before KiCad 8 (20240108) a present
     * 0 means "not set".
     */
    public static double resolve(Double pad, Double footprint, Double board, int version) {
        return resolve(pad, footprint, board, version < KicadPcb.V_KICAD8);
    }

    private static double resolve(Double pad, Double footprint, Double board, boolean zeroIsUnset) {
        if (pad != null && !(zeroIsUnset && pad == 0)) {
            return pad;
        }
        if (footprint != null && !(zeroIsUnset && footprint == 0)) {
            return footprint;
        }
        return board == null ? 0 : board;
    }

    /** The solder mask margin of a pad (pad → footprint → board pad_to_mask_clearance). */
    public static double maskMargin(KicadPcb pcb, Footprint fp, Pad pad) {
        return resolve(pad.maskMargin, fp.maskMargin, pcb.setup.padToMaskClearance, pcb.version);
    }

    /** {mx, my}: the paste margin per axis, m + ρ·size. */
    public static double[] pasteMargins(KicadPcb pcb, Footprint fp, Pad pad) {
        double m = resolve(pad.pasteMargin, fp.pasteMargin, pcb.setup.padToPasteClearance, pcb.version);
        double r = resolve(pad.pasteRatio, fp.pasteRatio, pcb.setup.padToPasteClearanceRatio, pcb.version);
        return new double[] {m + r * pad.w, m + r * pad.h};
    }

    // ---- apertures -----------------------------------------------------------------------------------------------

    /** The paste openings of a top-side footprint, named, with its notes (no time limit). */
    public static Result paste(KicadPcb pcb, Footprint fp) {
        try {
            return paste(pcb, fp, Deadline.none());
        }
        catch (SExprException e) {
            throw new IllegalStateException(e); // Deadline.none() never runs out
        }
    }

    /**
     * The paste openings of a top-side footprint, named, with its notes, under the import's deadline (one footprint
     * of a hostile file can hold most of its 1.5 million items).
     */
    public static Result paste(KicadPcb pcb, Footprint fp, Deadline deadline) throws SExprException {
        Result res = new Result();
        List<String> gone = new ArrayList<>();
        List<String> tht = new ArrayList<>();
        List<String> custom = new ArrayList<>();
        List<String> chamfered = new ArrayList<>();
        double chamferPct = 0;
        List<String> zeroNotes = new ArrayList<>();
        for (Pad p : fp.pads) {
            if (!p.onFrontPaste()) {
                continue;
            }
            if ("thru_hole".equals(p.type)) {
                tht.add(KText.clean(p.name, 40));
                continue;
            }
            if (!"smd".equals(p.type) && !"connect".equals(p.type)) {
                continue;
            }
            double[] mm = pasteMargins(pcb, fp, p);
            String zero = zeroOverride(pcb, fp, p, mm);
            if (zero != null) {
                zeroNotes.add(zero);
            }
            Aperture a = padShape(fp, p, mm[0], mm[1]);
            if (a.w <= GONE_MM || a.h <= GONE_MM) {
                gone.add(KText.clean(p.name, 40));
                continue;
            }
            if ("trapezoid".equals(a.approx)) {
                double pct = 100 * ((p.w + Math.abs(p.deltaY)) * (p.h + Math.abs(p.deltaX)) / (p.w * p.h) - 1);
                res.notes.add(new Note("PAD_SHAPE_APPROX", " pad " + KText.clean(p.name, 40) + " is a trapezoid: its "
                        + "paste opening is taken as the surrounding rectangle (" + pct(pct) + " more area)."));
            }
            else if ("custom".equals(a.approx)) {
                custom.add(KText.clean(p.name, 40));
            }
            int corners = chamferCorners(p);
            if (corners > 0) {
                double c = p.chamferRatio * Math.min(p.w, p.h);
                double r = p.rratio * Math.min(p.w, p.h);
                double area = p.w * p.h - (4 - Math.PI) * r * r;
                double removed = corners * c * c / 2;
                chamfered.add(KText.clean(p.name, 40));
                chamferPct = Math.max(chamferPct, 100 * removed / Math.max(1e-9, area - removed));
            }
            res.apertures.add(a);
        }
        for (Graphic g : fp.graphics) {
            if (!"F.Paste".equals(g.layer) || !g.filled) {
                continue;
            }
            Aperture a = graphicShape(g);
            if (a != null) {
                res.apertures.add(a);
            }
        }
        boolean zoneUnfilled = false;
        for (KicadPcb.Zone z : fp.zones) {
            if (z.keepout || !(z.layers.contains("F.Paste") || z.layers.contains("*.Paste"))) {
                continue;
            }
            List<double[]> fills = new ArrayList<>();
            for (Map.Entry<String, List<double[]>> e : z.filled.entrySet()) {
                if ("F.Paste".equals(e.getKey()) || "*.Paste".equals(e.getKey())) {
                    fills.addAll(e.getValue());
                }
            }
            if (fills.isEmpty()) {
                zoneUnfilled = true;
            }
            for (double[] poly : fills) {
                double[] local = new double[poly.length];
                for (int i = 0; i + 1 < poly.length; i += 2) {
                    double[] l = BoardFrame.kicadToLocal(fp, poly[i], poly[i + 1]);
                    local[i] = l[0];
                    local[i + 1] = l[1];
                }
                Aperture a = polygonShape(local, 0);
                if (a != null) {
                    res.apertures.add(a);
                }
            }
        }
        name(fp, res, true, deadline);
        polygonNotes(res);
        if (!custom.isEmpty()) {
            res.notes.add(new Note("PAD_SHAPE_APPROX", " " + pads(custom) + (custom.size() == 1
                    ? " has a custom shape: its paste opening is" : " have a custom shape: their paste openings are")
                    + " taken as the surrounding rectangle."));
        }
        if (!gone.isEmpty()) {
            res.notes.add(new Note("APERTURE_GONE", " " + pads(gone) + (gone.size() == 1 ? " gets" : " get")
                    + " no paste: the board's paste margin removes the whole opening. Change the margin in KiCad if "
                    + "paste is wanted there."));
        }
        if (!tht.isEmpty()) {
            res.notes.add(new Note("THT_PASTE_SKIPPED", tht.size() == 1
                    ? " " + pads(tht) + " is a through-hole pad with paste (pin in paste): it gets no paste."
                    : " " + pads(tht) + " are through-hole pads with paste (pin in paste): they get no paste."));
        }
        if (!chamfered.isEmpty()) {
            res.notes.add(new Note("CHAMFER_IGNORED", " " + pads(chamfered) + (chamfered.size() == 1 ? " has" : " have")
                    + " cut corners: the paste opening is taken without the cuts (up to " + pct(chamferPct)
                    + " more area)."));
        }
        if (zoneUnfilled) {
            res.notes.add(new Note("PASTE_ZONE_IGNORED", ": a paste area (zone) in the part is not filled in this file, "
                    + "so it gets no paste. In KiCad, fill the zones (B), save, and upload again."));
        }
        for (String z : zeroNotes) {
            res.notes.add(new Note("PASTE_ZERO_OVERRIDE", z));
        }
        return res;
    }

    /** The copper pads of a fiducial as its package pads (no paste, margin 0; no time limit). */
    public static Result copper(KicadPcb pcb, Footprint fp) {
        try {
            return copper(pcb, fp, Deadline.none());
        }
        catch (SExprException e) {
            throw new IllegalStateException(e); // Deadline.none() never runs out
        }
    }

    /** The copper pads of a fiducial as its package pads (no paste, margin 0), under the import's deadline. */
    public static Result copper(KicadPcb pcb, Footprint fp, Deadline deadline) throws SExprException {
        Result res = new Result();
        for (Pad p : fp.pads) {
            if (p.onFrontCopper()) {
                res.apertures.add(padShape(fp, p, 0, 0));
            }
        }
        name(fp, res, false, deadline);
        return res;
    }

    /** The opening of a pad grown by (mx, my) per axis, in the part frame. */
    static Aperture padShape(Footprint fp, Pad p, double mx, double my) {
        Aperture a = new Aperture(p);
        double rel = KGeom.normDeg(p.angle - fp.angle);
        double cx = p.offX;
        double cy = p.offY;
        double w = p.w + 2 * mx;
        double h = p.h + 2 * my;
        switch (p.shape) {
            case "roundrect": {
                double r = p.rratio * Math.min(p.w, p.h);
                double r2 = Math.max(0, r + Math.min(mx, my));
                a.roundness = clamp(200 * r2 / Math.min(w, h));
                break;
            }
            case "circle":
                w = p.w + 2 * mx;
                h = w;
                a.roundness = 100;
                break;
            case "oval":
                a.roundness = 100;
                break;
            case "trapezoid":
                w = p.w + Math.abs(p.deltaY) + 2 * mx;
                h = p.h + Math.abs(p.deltaX) + 2 * my;
                a.approx = "trapezoid";
                break;
            case "custom": {
                double[] box = customBox(p);
                cx += (box[0] + box[2]) / 2;
                cy += (box[1] + box[3]) / 2;
                w = box[2] - box[0] + 2 * mx;
                h = box[3] - box[1] + 2 * my;
                a.approx = "custom";
                break;
            }
            default: { // rect, and anything unknown
                double r2 = Math.max(0, Math.min(mx, my));
                a.roundness = r2 > 0 ? clamp(200 * r2 / Math.min(w, h)) : 0;
                break;
            }
        }
        // the shape centre in the footprint (KiCad frame, Y down): pad position + the offset turned with the pad
        double t = Math.toRadians(rel);
        double c = Math.cos(t);
        double s = Math.sin(t);
        double u = p.x + cx * c + cy * s;
        double v = p.y - cx * s + cy * c;
        a.x = KGeom.round6(u);
        a.y = KGeom.round6(-v);
        a.w = KGeom.round6(w);
        a.h = KGeom.round6(h);
        a.rotation = KGeom.round6(rel);
        a.roundness = KGeom.round6(a.roundness);
        return a;
    }

    /**
     * The box of a custom pad in its own frame (KiCad, Y down): the anchor shape and every primitive, each grown by
     * half its stroke width. {minX, minY, maxX, maxY}.
     */
    static double[] customBox(Pad p) {
        double[] box = KGeom.emptyBox();
        double hw = p.w / 2;
        double hh = "rect".equals(p.anchor) ? p.h / 2 : p.w / 2;
        KGeom.extend(box, -hw, -hh);
        KGeom.extend(box, hw, hh);
        for (Graphic g : p.primitives) {
            double[] b = BoardFrame.graphicBox(g.kind, g.pts);
            if (KGeom.isEmpty(b)) {
                continue;
            }
            double grow = g.width / 2;
            KGeom.extend(box, b[0] - grow, b[1] - grow);
            KGeom.extend(box, b[2] + grow, b[3] + grow);
        }
        return box;
    }

    /** A filled F.Paste shape drawn in the footprint (local KiCad coordinates). */
    private static Aperture graphicShape(Graphic g) {
        double sw = g.width;
        switch (g.kind) {
            case RECT: {
                Aperture a = new Aperture(null);
                double w = Math.abs(g.pts[2] - g.pts[0]) + sw;
                double h = Math.abs(g.pts[3] - g.pts[1]) + sw;
                a.x = KGeom.round6((g.pts[0] + g.pts[2]) / 2);
                a.y = KGeom.round6(-(g.pts[1] + g.pts[3]) / 2);
                a.w = KGeom.round6(w);
                a.h = KGeom.round6(h);
                a.roundness = sw > 0 ? KGeom.round6(clamp(100 * sw / Math.min(w, h))) : 0;
                return a.w > GONE_MM && a.h > GONE_MM ? a : null;
            }
            case CIRCLE: {
                Aperture a = new Aperture(null);
                double d = 2 * Math.hypot(g.pts[2] - g.pts[0], g.pts[3] - g.pts[1]) + sw;
                a.x = KGeom.round6(g.pts[0]);
                a.y = KGeom.round6(-g.pts[1]);
                a.w = KGeom.round6(d);
                a.h = a.w;
                a.roundness = 100;
                return a;
            }
            case POLY:
                return polygonShape(g.pts, sw);
            default:
                return null;
        }
    }

    /**
     * A polygon (local KiCad coordinates) as a rectangle: exact when it is one (any angle), else the minimum-area
     * rectangle of a convex polygon, else the box of a concave one; grown by half the stroke width.
     */
    private static Aperture polygonShape(double[] local, double strokeWidth) {
        double[] p = new double[local.length];
        for (int i = 0; i + 1 < local.length; i += 2) {
            p[i] = local[i];
            p[i + 1] = -local[i + 1];
        }
        p = corners(p);
        if (p.length < 6) {
            return null;
        }
        Aperture a = new Aperture(null);
        double area = Math.abs(KGeom.polygonArea(p));
        double[] r;
        if (isRectangle(p)) {
            r = KGeom.minAreaRect(p);
            a.approx = null;
        }
        else if (KGeom.isConvex(p)) {
            r = KGeom.minAreaRect(p);
            a.approx = "polygon";
        }
        else {
            double[] box = KGeom.emptyBox();
            KGeom.extendPts(box, p);
            r = new double[] {(box[0] + box[2]) / 2, (box[1] + box[3]) / 2, box[2] - box[0], box[3] - box[1], 0};
            a.approx = "polygon";
        }
        double w = r[2] + strokeWidth;
        double h = r[3] + strokeWidth;
        if (w <= GONE_MM || h <= GONE_MM) {
            return null;
        }
        a.x = KGeom.round6(r[0]);
        a.y = KGeom.round6(r[1]);
        a.w = KGeom.round6(w);
        a.h = KGeom.round6(h);
        a.rotation = KGeom.round6(r[4]);
        a.roundness = strokeWidth > 0 ? KGeom.round6(clamp(100 * strokeWidth / Math.min(w, h))) : 0;
        a.polyAreaPct = a.approx == null ? 0 : 100 * (r[2] * r[3] / Math.max(1e-9, area) - 1);
        return a;
    }

    /** Drops repeated and collinear points (within 1e-6 mm). */
    private static double[] corners(double[] p) {
        KGeom.Pts q = new KGeom.Pts();
        int n = p.length / 2;
        for (int i = 0; i < n; i++) {
            double px = p[2 * ((i + n - 1) % n)];
            double py = p[2 * ((i + n - 1) % n) + 1];
            double x = p[2 * i];
            double y = p[2 * i + 1];
            double nx = p[2 * ((i + 1) % n)];
            double ny = p[2 * ((i + 1) % n) + 1];
            if (Math.hypot(x - px, y - py) < 1e-6) {
                continue;
            }
            if (KGeom.segmentDistance(x, y, px, py, nx, ny) < 1e-6) {
                continue;
            }
            q.add(x, y);
        }
        return q.toArray();
    }

    /** Four corners with right angles (within 0.001 mm over the side lengths). */
    private static boolean isRectangle(double[] p) {
        if (p.length != 8) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            int a = (i + 3) % 4;
            int b = (i + 1) % 4;
            double ax = p[2 * a] - p[2 * i];
            double ay = p[2 * a + 1] - p[2 * i + 1];
            double bx = p[2 * b] - p[2 * i];
            double by = p[2 * b + 1] - p[2 * i + 1];
            double lb = Math.hypot(bx, by);
            if (lb == 0 || Math.abs(ax * bx + ay * by) / lb > 0.001) {
                return false;
            }
        }
        return true;
    }

    private static int chamferCorners(Pad p) {
        if (p.chamferRatio == null || p.chamferRatio <= 0 || p.chamfer.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String c : new String[] {"top_left", "top_right", "bottom_left", "bottom_right"}) {
            n += p.chamfer.contains(c) ? 1 : 0;
        }
        return n;
    }

    /** A note when a present 0 margin reads differently under the KiCad 7 and the KiCad 8 rules. */
    private static String zeroOverride(KicadPcb pcb, Footprint fp, Pad p, double[] used) {
        boolean zeroPresent = isZero(p.pasteMargin) || isZero(p.pasteRatio) || isZero(fp.pasteMargin)
                || isZero(fp.pasteRatio);
        if (!zeroPresent) {
            return null;
        }
        boolean old = pcb.version < KicadPcb.V_KICAD8;
        double m = resolve(p.pasteMargin, fp.pasteMargin, pcb.setup.padToPasteClearance, !old);
        double r = resolve(p.pasteRatio, fp.pasteRatio, pcb.setup.padToPasteClearanceRatio, !old);
        double ox = m + r * p.w;
        double oy = m + r * p.h;
        if (Math.abs(ox - used[0]) < 1e-9 && Math.abs(oy - used[1]) < 1e-9) {
            return null;
        }
        return String.format(Locale.ROOT, " pad %s sets a paste margin of exactly 0: read %s (%s), the paste opening "
                + "grows by %.4f × %.4f mm per side; the %s rules would give %.4f × %.4f mm.",
                KText.clean(p.name, 40), old ? "as not set" : "as an override",
                old ? "KiCad 7 and older" : "KiCad 8 and newer", used[0], used[1], old ? "KiCad 8" : "KiCad 7", ox,
                oy);
    }

    private static boolean isZero(Double d) {
        return d != null && d == 0;
    }

    // ---- names ---------------------------------------------------------------------------------------------------

    /**
     * Unique names within the footprint (§2.8): unique names kept; repeats become name~2, name~3; empty names and
     * drawn shapes become host~k, host = the copper pad containing the opening's centre (a named pad first), else P.
     */
    static void name(Footprint fp, Result res, boolean notes, Deadline deadline) throws SExprException {
        List<Aperture> ap = res.apertures;
        Set<String> used = new HashSet<>();
        for (Aperture a : ap) {
            a.name = a.pad == null ? "" : KText.clean(a.pad.name, 40);
            a.emptyName = a.name.isEmpty();
            used.add(a.name);
        }
        List<String> renamed = new ArrayList<>();
        Map<String, Integer> nextRepeat = new HashMap<>();
        Set<String> firstDone = new HashSet<>();
        for (Aperture a : ap) {
            if (a.emptyName || firstDone.add(a.name)) {
                continue;
            }
            a.name = free(a.name, nextRepeat, used, 2);
            renamed.add(a.name);
        }
        Map<String, Integer> nextHosted = new HashMap<>();
        Map<String, List<String>> byHost = new LinkedHashMap<>();
        List<Aperture> hosts = null;
        for (Aperture a : ap) {
            if (!a.emptyName) {
                continue;
            }
            deadline.check();
            if (hosts == null) {
                hosts = hostShapes(fp);
            }
            a.host = host(hosts, a);
            a.name = free(a.host, nextHosted, used, 1);
            byHost.computeIfAbsent(a.host, k -> new ArrayList<>()).add(a.name);
        }
        if (!notes) {
            return;
        }
        if (!renamed.isEmpty()) {
            res.notes.add(new Note("PAD_NAME_DUPLICATE", ": pads with a repeated name are saved as "
                    + listOrRange(renamed) + "."));
        }
        int empty = 0;
        List<String> emptyNames = new ArrayList<>();
        for (List<String> l : byHost.values()) {
            empty += l.size();
            emptyNames.addAll(l);
        }
        if (empty > 0) {
            res.notes.add(new Note("PAD_NAME_EMPTY", ": " + empty + (empty == 1 ? " paste opening" : " paste openings")
                    + " without a name " + (empty == 1 ? "is " : "are ") + listOrRange(emptyNames) + "."));
        }
        // windows inside a centre pad that has no paste of its own are dispensed as separate small pads
        for (Map.Entry<String, List<String>> e : byHost.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            deadline.check(); // each host scans every opening
            if (!isBareCentrePad(fp, e.getKey(), ap)) {
                continue;
            }
            double area = 0;
            double min = Double.POSITIVE_INFINITY;
            double max = 0;
            for (Aperture a : ap) {
                if (e.getKey().equals(a.host)) {
                    double ar = a.areaMm2();
                    area += ar;
                    min = Math.min(min, ar);
                    max = Math.max(max, ar);
                }
            }
            int n = e.getValue().size();
            String each = max - min <= 0.01 * max ? String.format(Locale.ROOT, "%.2f mm² each", area / n)
                    : String.format(Locale.ROOT, "%.2f to %.2f mm²", min, max);
            res.notes.add(new Note("QFN_WINDOWS", "'s " + n + " paste windows (" + each + ") inside its centre pad "
                    + e.getKey() + " are dispensed as small edge pads, not as one centre pad. Check the dots in the "
                    + "pad inspector."));
        }
    }

    private static String free(String base, Map<String, Integer> next, Set<String> used, int start) {
        int k = next.getOrDefault(base, start);
        String n = base + "~" + k;
        while (used.contains(n)) {
            k++;
            n = base + "~" + k;
        }
        next.put(base, k + 1);
        used.add(n);
        return n;
    }

    /** The named copper pads of a footprint as shapes in the part frame, file order, each with its cleaned name. */
    private static List<Aperture> hostShapes(Footprint fp) {
        List<Aperture> l = new ArrayList<>();
        for (Pad p : fp.pads) {
            String n = KText.clean(p.name, 40);
            if (p.onFrontCopper() && !n.isEmpty()) {
                Aperture c = padShape(fp, p, 0, 0);
                c.name = n;
                l.add(c);
            }
        }
        return l;
    }

    /** The name of the copper pad whose rectangle holds the opening's centre (a named pad first), else "P". */
    private static String host(List<Aperture> hosts, Aperture a) {
        for (Aperture c : hosts) {
            if (c.pad != a.pad && contains(c, a.x, a.y)) {
                return c.name;
            }
        }
        return "P";
    }

    private static boolean contains(Aperture c, double x, double y) {
        double t = Math.toRadians(-c.rotation);
        double dx = x - c.x;
        double dy = y - c.y;
        double u = dx * Math.cos(t) - dy * Math.sin(t);
        double v = dx * Math.sin(t) + dy * Math.cos(t);
        return Math.abs(u) <= c.w / 2 + 1e-6 && Math.abs(v) <= c.h / 2 + 1e-6;
    }

    /** The host is a pad at the footprint's origin of at least 1 mm² that has no opening of its own. */
    private static boolean isBareCentrePad(Footprint fp, String host, List<Aperture> ap) {
        for (Aperture a : ap) {
            if (!a.emptyName && host.equals(a.name)) {
                return false;
            }
        }
        for (Pad p : fp.pads) {
            if (p.onFrontCopper() && host.equals(KText.clean(p.name, 40))) {
                Aperture c = padShape(fp, p, 0, 0);
                return Math.hypot(c.x, c.y) < 0.05 && c.areaMm2() >= 1.0;
            }
        }
        return false;
    }

    /** Notes for the drawn shapes and zone fills, after naming. */
    private static void polygonNotes(Result res) {
        List<String> exact = new ArrayList<>();
        for (Aperture a : res.apertures) {
            if (a.pad != null) {
                continue;
            }
            String size = String.format(Locale.ROOT, "%s (%.2f × %.2f mm)", a.name, a.w, a.h);
            if (a.approx == null) {
                exact.add(size);
            }
            else {
                res.notes.add(new Note("PASTE_POLY_APPROX", ": the paste shape " + a.name + " drawn on F.Paste is not "
                        + "a rectangle: its opening is taken as the surrounding rectangle (" + pct(a.polyAreaPct)
                        + " more area)."));
            }
        }
        if (!exact.isEmpty()) {
            res.notes.add(new Note("PASTE_POLY", exact.size() == 1
                    ? ": the paste shape drawn on F.Paste is opening " + exact.get(0) + "."
                    : ": the paste shapes drawn on F.Paste are openings " + String.join(", ", exact) + "."));
        }
    }

    // ---- text helpers --------------------------------------------------------------------------------------------

    private static String pads(List<String> names) {
        return (names.size() == 1 ? "pad " : "pads ") + listOrRange(names);
    }

    /** "1, 2, 3" or "33~1 … 33~9" for more than four names. */
    static String listOrRange(List<String> names) {
        if (names.size() <= 4) {
            return String.join(", ", names);
        }
        return names.get(0) + " … " + names.get(names.size() - 1);
    }

    private static String pct(double p) {
        return p < 1 ? String.format(Locale.ROOT, "%.1f %%", p) : String.format(Locale.ROOT, "%.0f %%", p);
    }

    private static double clamp(double r) {
        return Math.max(0, Math.min(100, r));
    }
}
