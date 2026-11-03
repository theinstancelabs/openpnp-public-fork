package org.openpnp.instancelabs.kicad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.openpnp.instancelabs.kicad.KicadPcb.Footprint;
import org.openpnp.instancelabs.kicad.KicadPcb.Graphic;
import org.openpnp.instancelabs.kicad.KicadPcb.Pad;
import org.openpnp.instancelabs.kicad.KicadPcb.Text;
import org.openpnp.instancelabs.kicad.KicadPcb.Track;
import org.openpnp.instancelabs.kicad.KicadPcb.Via;
import org.openpnp.instancelabs.kicad.KicadPcb.Zone;

/**
 * SExpr tree → {@link KicadPcb}, every form of KiCad 5 to 10 (design §2.4, §2.5). Every form is accepted in every
 * version; unknown heads are counted, never fatal. A number that cannot be read or is out of KiCad's own range skips
 * its item with a BAD_NUMBER warning, so an absurd value cannot blow up the steps after parsing.
 */
public final class KicadParser {
    /** KiCad's own range: 32-bit nanometres. */
    public static final double MAX_MM = 2147.0;
    static final int MAX_WARNINGS = 500;

    private static final Set<String> IGNORED_TOP = new HashSet<>(Arrays.asList("version", "generator",
            "generator_version", "host", "general", "paper", "page", "title_block", "layers", "net", "net_class",
            "property", "dimension", "target", "table", "group", "image", "embedded_fonts", "embedded_files",
            "generated", "gr_text_box", "gr_bbox", "point"));
    private static final Set<String> FOOTPRINT_KNOWN = new HashSet<>(Arrays.asList("layer", "at", "attr", "property",
            "fp_text", "solder_paste_margin", "solder_paste_ratio", "solder_paste_margin_ratio", "solder_mask_margin",
            "fp_line", "fp_arc", "fp_circle", "fp_rect", "fp_poly", "fp_curve", "pad", "zone", "descr", "tags", "path",
            "sheetname", "sheetfile", "uuid", "tstamp", "tedit", "model", "net_tie_pad_groups", "fp_text_box",
            "dnp", "exclude_from_pos_files", "exclude_from_bom", "board_only", "locked", "placed", "clearance",
            "zone_connect", "thermal_width", "thermal_gap", "private_layers", "net_tie_pad_groups", "group",
            "embedded_fonts", "embedded_files", "units", "duplicate_pad_numbers_are_jumpers", "jumper_pad_groups",
            "component_classes", "dimension", "table", "point", "autoplace_cost90", "autoplace_cost180"));
    private static final List<String> FLAG_ATTRS = Collections.unmodifiableList(
            Arrays.asList("dnp", "exclude_from_pos_files", "exclude_from_bom", "board_only"));

    /** A number that cannot be read or is out of range: the item that holds it is skipped. */
    private static final class Bad extends Exception {
        private static final long serialVersionUID = 1L;

        Bad() {
            super(null, null, false, false);
        }
    }

    private final KicadPcb pcb = new KicadPcb();
    private final Set<String> padstackRefs = new LinkedHashSet<>(); // cleaned: they go into the warning text
    private String currentRef = "";
    private long arcPoints; // made by flattening polygon arcs, against KGeom.MAX_FLAT_POINTS

    private KicadParser() {
    }

    /** Reader and parser in one, sharing the deadline. */
    public static KicadPcb read(byte[] bytes, Deadline deadline) throws SExprException {
        return parse(SExprReader.read(bytes, deadline), deadline);
    }

    public static KicadPcb parse(SExpr root, Deadline deadline) throws SExprException {
        return new KicadParser().run(root, deadline);
    }

    private KicadPcb run(SExpr root, Deadline deadline) throws SExprException {
        if (!"kicad_pcb".equals(root.head)) {
            throw SExprException.notKicad("Not a KiCad board file: choose a .kicad_pcb file (KiCad 5 to 10).");
        }
        SExpr v = root.child("version");
        pcb.version = v == null ? 0 : (int) Math.max(0, Math.min(Integer.MAX_VALUE, v.num(0, 0)));
        pcb.generator = root.str("generator");
        pcb.generatorVersion = root.str("generator_version");
        SExpr host = root.child("host");
        if (pcb.generator == null && host != null) {
            pcb.generator = host.atom(0);
            pcb.generatorVersion = host.atom(1);
        }
        boolean modules = false;
        for (Object o : root.items) {
            if (!(o instanceof SExpr)) {
                continue;
            }
            SExpr e = (SExpr) o;
            String h = e.head == null ? "" : e.head;
            switch (h) {
                case "setup":
                    parseSetup(e);
                    break;
                case "module":
                    modules = true;
                    deadline.check();
                    parseFootprint(e);
                    break;
                case "footprint":
                    deadline.check();
                    parseFootprint(e);
                    break;
                case "gr_line":
                case "gr_arc":
                case "gr_circle":
                case "gr_rect":
                case "gr_poly":
                case "gr_curve":
                    addGraphic(pcb.graphics, e);
                    break;
                case "gr_text":
                    parseText(e);
                    break;
                case "segment":
                case "arc":
                    parseTrack(e);
                    break;
                case "via":
                    parseVia(e);
                    break;
                case "zone":
                    deadline.check();
                    Zone z = parseZone(e);
                    if (z != null) {
                        pcb.zones.add(z);
                    }
                    break;
                default:
                    if (!IGNORED_TOP.contains(h)) {
                        pcb.ignoredItems++;
                    }
                    break;
            }
        }
        pcb.legacy = modules || pcb.version < KicadPcb.V_KICAD6;
        if (!padstackRefs.isEmpty()) {
            List<String> refs = new ArrayList<>(padstackRefs);
            warn(ImportWarning.of("PADSTACK_IGNORED", refs, ImportWarning.names(refs)
                    + ": pads with a different shape per copper layer (a pad stack): only the front shape is read."));
        }
        if (pcb.version > KicadPcb.V_KICAD10) {
            warn(ImportWarning.of("KICAD_NEWER", null, "This file is newer than KiCad 10: it was read with the KiCad 10 "
                    + "rules. Check the outline and the parts in the preview."));
        }
        if (pcb.legacy) {
            warn(ImportWarning.of("KICAD5", null, "KiCad 5 board: read on a best effort basis. Check the outline and "
                    + "the parts in the preview."));
        }
        return pcb;
    }

    // ---- setup ---------------------------------------------------------------------------------------------------

    private void parseSetup(SExpr s) {
        pcb.setup.padToMaskClearance = optNum(s, "pad_to_mask_clearance");
        pcb.setup.padToPasteClearance = optNum(s, "pad_to_paste_clearance");
        pcb.setup.padToPasteClearanceRatio = optNum(s, "pad_to_paste_clearance_ratio");
        SExpr tenting = s.child("tenting");
        SExpr plot = s.child("pcbplotparams");
        if (tenting != null) {
            pcb.setup.viasTentedFront = tenting.yes("front"); // (tenting (front yes) …) or (tenting front back)
        }
        else if (plot != null && plot.child("viasonmask") != null) {
            String a = plot.str("viasonmask");
            pcb.setup.viasTentedFront = !("true".equals(a) || "yes".equals(a));
        }
        SExpr stackup = s.child("stackup");
        if (stackup != null) {
            for (SExpr l : stackup.children("layer")) {
                if ("F.Mask".equals(l.atom(0))) {
                    pcb.setup.maskColor = l.str("color");
                }
                else if ("F.SilkS".equals(l.atom(0))) {
                    pcb.setup.silkColor = l.str("color");
                }
            }
        }
    }

    // ---- footprints ----------------------------------------------------------------------------------------------

    private void parseFootprint(SExpr e) throws SExprException {
        Footprint f = new Footprint();
        f.line = e.line;
        String full = e.atom(0) == null ? "" : e.atom(0);
        int colon = full.indexOf(':');
        f.lib = colon < 0 ? "" : full.substring(0, colon);
        f.name = colon < 0 ? full : full.substring(colon + 1);
        f.ref = "";
        f.value = "";
        // the reference first, so warnings about this footprint can name it
        for (SExpr c : e.lists()) {
            if ("property".equals(c.head) && "Reference".equals(c.atom(0))
                    || "fp_text".equals(c.head) && "reference".equals(c.atom(0))) {
                f.ref = c.atom(1) == null ? "" : c.atom(1);
            }
        }
        currentRef = f.ref;
        try {
            parseFootprintBody(e, f);
        }
        finally {
            currentRef = "";
        }
    }

    private void parseFootprintBody(SExpr e, Footprint f) throws SExprException {
        try {
            String layer = e.str("layer");
            f.top = !"B.Cu".equals(layer);
            SExpr at = e.child("at");
            if (at != null) {
                f.x = coord(at, 0);
                f.y = coord(at, 1);
                f.angle = angle(at, 2);
            }
        }
        catch (Bad b) {
            badNumber(e.line);
            return;
        }
        SExpr attr = e.child("attr");
        if (attr != null) {
            for (Object o : attr.items) {
                if (o instanceof String) {
                    f.attrs.add((String) o);
                }
            }
        }
        for (String a : FLAG_ATTRS) {
            if (e.child(a) != null && e.yes(a)) {
                f.attrs.add(a);
            }
        }
        if (f.hasAttr("virtual")) {
            f.attrs.add("board_only"); // KiCad 5: virtual = board only
        }
        f.pasteMargin = optNum(e, "solder_paste_margin");
        f.pasteRatio = optNum(e, "solder_paste_ratio");
        if (f.pasteRatio == null) {
            f.pasteRatio = optNum(e, "solder_paste_margin_ratio");
        }
        f.maskMargin = optNum(e, "solder_mask_margin");
        for (SExpr c : e.lists()) {
            String h = c.head == null ? "" : c.head;
            switch (h) {
                case "property":
                    if ("Reference".equals(c.atom(0))) {
                        label(f, c, c.yes("hide"));
                    }
                    else if ("Value".equals(c.atom(0))) {
                        f.value = c.atom(1) == null ? "" : c.atom(1);
                    }
                    break;
                case "fp_text":
                    if ("reference".equals(c.atom(0))) {
                        label(f, c, c.yes("hide"));
                    }
                    else if ("value".equals(c.atom(0))) {
                        f.value = c.atom(1) == null ? "" : c.atom(1);
                    }
                    break;
                case "fp_line":
                case "fp_arc":
                case "fp_circle":
                case "fp_rect":
                case "fp_poly":
                case "fp_curve":
                    addGraphic(f.graphics, c);
                    break;
                case "pad":
                    Pad p = parsePad(c);
                    if (p != null) {
                        f.pads.add(p);
                    }
                    break;
                case "zone":
                    Zone z = parseZone(c);
                    if (z != null) {
                        f.zones.add(z);
                    }
                    break;
                default:
                    if (!FOOTPRINT_KNOWN.contains(h)) {
                        pcb.ignoredItems++;
                    }
                    break;
            }
        }
        pcb.footprints.add(f);
    }

    private void label(Footprint f, SExpr c, boolean hidden) {
        SExpr effects = c.child("effects");
        f.refHidden = hidden || effects != null && effects.yes("hide");
        f.refLayer = c.str("layer");
        SExpr at = c.child("at");
        if (at != null) {
            f.refX = finite(at.num(0, 0));
            f.refY = finite(at.num(1, 0));
            f.refAngle = finite(at.num(2, 0));
        }
        f.refSize = fontHeight(effects);
    }

    private static double fontHeight(SExpr effects) {
        SExpr font = effects == null ? null : effects.child("font");
        SExpr size = font == null ? null : font.child("size");
        double h = size == null ? 0 : size.num(0, 0);
        return h > 0 && h <= MAX_MM ? h : 0;
    }

    private Pad parsePad(SExpr e) throws SExprException {
        Pad p = new Pad();
        p.line = e.line;
        p.name = e.atom(0) == null ? "" : e.atom(0);
        p.type = e.atom(1) == null ? "" : e.atom(1);
        p.shape = e.atom(2) == null ? "" : e.atom(2);
        try {
            SExpr at = e.child("at");
            if (at == null) {
                throw new Bad();
            }
            p.x = coord(at, 0);
            p.y = coord(at, 1);
            p.angle = angle(at, 2);
            SExpr size = e.child("size");
            if (size == null) {
                throw new Bad();
            }
            p.w = size(size, 0);
            p.h = size.atom(1) == null ? p.w : size(size, 1);
            SExpr drill = e.child("drill");
            if (drill != null) {
                List<Double> nums = new ArrayList<>();
                for (int i = 0; i < drill.size(); i++) {
                    String a = drill.atom(i);
                    if (a != null && !"oval".equals(a)) {
                        nums.add(size(drill, i));
                    }
                }
                if (!nums.isEmpty()) {
                    p.drillW = nums.get(0);
                    p.drillH = drill.flag("oval") && nums.size() > 1 ? nums.get(1) : p.drillW;
                }
                SExpr off = drill.child("offset");
                if (off != null) {
                    p.offX = coord(off, 0);
                    p.offY = coord(off, 1);
                }
            }
            SExpr delta = e.child("rect_delta");
            if (delta != null) {
                p.deltaX = coord(delta, 0);
                p.deltaY = coord(delta, 1);
            }
            for (SExpr c : e.lists()) {
                if ("primitives".equals(c.head)) {
                    for (SExpr g : c.lists()) {
                        addGraphic(p.primitives, g);
                    }
                }
            }
        }
        catch (Bad b) {
            badNumber(e.line);
            return null;
        }
        SExpr layers = e.child("layers");
        if (layers != null) {
            for (Object o : layers.items) {
                if (o instanceof String) {
                    p.layers.add((String) o);
                }
            }
        }
        double rr = e.child("roundrect_rratio") == null ? 0 : e.child("roundrect_rratio").num(0, 0);
        p.rratio = Math.max(0, Math.min(0.5, rr));
        p.chamferRatio = optNum(e, "chamfer_ratio");
        SExpr chamfer = e.child("chamfer");
        if (chamfer != null) {
            for (Object o : chamfer.items) {
                if (o instanceof String) {
                    p.chamfer.add((String) o);
                }
            }
        }
        p.pasteMargin = optNum(e, "solder_paste_margin");
        p.pasteRatio = optNum(e, "solder_paste_margin_ratio");
        if (p.pasteRatio == null) {
            p.pasteRatio = optNum(e, "solder_paste_ratio");
        }
        p.maskMargin = optNum(e, "solder_mask_margin");
        SExpr options = e.child("options");
        if (options != null && options.str("anchor") != null) {
            p.anchor = options.str("anchor");
        }
        SExpr padstack = e.child("padstack");
        String mode = padstack == null ? null : padstack.str("mode");
        if (mode != null && !"normal".equals(mode)) {
            padstackRefs.add(KText.clean(currentRef, KText.ID_MAX));
        }
        return p;
    }

    // ---- graphics ------------------------------------------------------------------------------------------------

    private void addGraphic(List<Graphic> into, SExpr e) throws SExprException {
        try {
            Graphic g = parseGraphic(e);
            if (g != null) {
                into.add(g);
            }
        }
        catch (Bad b) {
            badNumber(e.line);
        }
    }

    private Graphic parseGraphic(SExpr e) throws Bad, SExprException {
        String h = e.head == null ? "" : e.head;
        String kind = h.substring(h.indexOf('_') + 1);
        Graphic g = new Graphic();
        g.line = e.line;
        g.layer = e.str("layer");
        SExpr stroke = e.child("stroke");
        SExpr w = e.child("width");
        if (w == null && stroke != null) {
            w = stroke.child("width");
        }
        g.width = w == null ? 0 : width(w, 0);
        switch (kind) {
            case "line":
                g.kind = Graphic.Kind.LINE;
                g.pts = new double[] {xy(e, "start", 0), xy(e, "start", 1), xy(e, "end", 0), xy(e, "end", 1)};
                break;
            case "arc":
                g.kind = Graphic.Kind.ARC;
                if (e.child("mid") != null) {
                    g.pts = new double[] {xy(e, "start", 0), xy(e, "start", 1), xy(e, "mid", 0), xy(e, "mid", 1),
                            xy(e, "end", 0), xy(e, "end", 1)};
                }
                else {
                    // KiCad 5: start = centre, end = arc start, angle = sweep (clockwise on screen when positive)
                    SExpr a = e.child("angle");
                    if (a == null) {
                        throw new Bad();
                    }
                    g.pts = KGeom.arcKicad5(xy(e, "start", 0), xy(e, "start", 1), xy(e, "end", 0), xy(e, "end", 1),
                            angle(a, 0));
                    for (double c : g.pts) {
                        checkCoord(c);
                    }
                }
                checkArc(g.pts);
                break;
            case "circle":
                g.kind = Graphic.Kind.CIRCLE;
                g.pts = new double[] {xy(e, "center", 0), xy(e, "center", 1), xy(e, "end", 0), xy(e, "end", 1)};
                double r = Math.hypot(g.pts[2] - g.pts[0], g.pts[3] - g.pts[1]);
                if (!(r > 0 && r <= MAX_MM)) {
                    throw new Bad();
                }
                break;
            case "rect":
                g.kind = Graphic.Kind.RECT;
                g.pts = new double[] {xy(e, "start", 0), xy(e, "start", 1), xy(e, "end", 0), xy(e, "end", 1)};
                break;
            case "poly":
                g.kind = Graphic.Kind.POLY;
                g.pts = pts(e.child("pts"));
                if (g.pts.length < 4) {
                    return null;
                }
                break;
            case "curve":
                g.kind = Graphic.Kind.BEZIER;
                double[] c = pts(e.child("pts"));
                if (c.length != 8) {
                    throw new Bad();
                }
                g.pts = c;
                break;
            default:
                return null;
        }
        SExpr fill = e.child("fill");
        if (fill != null) {
            String a = fill.atom(0);
            if (a == null && fill.child("type") != null) {
                a = fill.str("type");
            }
            g.filled = "yes".equals(a) || "solid".equals(a) || "true".equals(a);
        }
        else {
            // no fill token (KiCad 5, early KiCad 6): polygons off Edge.Cuts and zero-width rects/circles are filled
            g.filled = g.kind == Graphic.Kind.POLY && !"Edge.Cuts".equals(g.layer)
                    || (g.kind == Graphic.Kind.RECT || g.kind == Graphic.Kind.CIRCLE) && g.width <= 0;
        }
        return g;
    }

    /** (pts (xy x y) … (arc (start) (mid) (end)) …) → flattened points, the arcs within the file's point budget. */
    private double[] pts(SExpr pts) throws Bad, SExprException {
        KGeom.Pts out = new KGeom.Pts();
        if (pts == null) {
            return out.toArray();
        }
        for (SExpr c : pts.lists()) {
            if ("xy".equals(c.head)) {
                out.add(coord(c, 0), coord(c, 1));
            }
            else if ("arc".equals(c.head)) {
                double[] a = {xy(c, "start", 0), xy(c, "start", 1), xy(c, "mid", 0), xy(c, "mid", 1), xy(c, "end", 0),
                        xy(c, "end", 1)};
                checkArc(a);
                boolean joined = out.size() > 0 && Math.hypot(out.x(out.size() - 1) - a[0],
                        out.y(out.size() - 1) - a[1]) < 1e-9;
                int before = out.size();
                KGeom.flattenArc(a[0], a[1], a[2], a[3], a[4], a[5], KGeom.CHORD_MM, out, !joined);
                arcPoints += out.size() - before;
                if (arcPoints > KGeom.MAX_FLAT_POINTS) {
                    throw SExprException.limit(c.line, String.format(Locale.ROOT, "its shapes have more than %,d "
                            + "points once their arcs are drawn (line %d)", KGeom.MAX_FLAT_POINTS, c.line));
                }
            }
        }
        double[] p = out.toArray();
        int n = p.length;
        if (n >= 6 && p[0] == p[n - 2] && p[1] == p[n - 1]) {
            p = Arrays.copyOf(p, n - 2); // a repeated closing point
        }
        return p;
    }

    private void parseText(SExpr e) {
        Text t = new Text();
        t.text = e.atom(0) == null ? "" : e.atom(0);
        t.layer = e.str("layer");
        SExpr at = e.child("at");
        try {
            if (at != null) {
                t.x = coord(at, 0);
                t.y = coord(at, 1);
                t.angle = angle(at, 2);
            }
        }
        catch (Bad b) {
            badNumber(e.line);
            return;
        }
        SExpr effects = e.child("effects");
        t.size = fontHeight(effects);
        SExpr justify = effects == null ? null : effects.child("justify");
        if (justify != null) {
            StringBuilder sb = new StringBuilder();
            for (Object o : justify.items) {
                if (o instanceof String) {
                    if ("mirror".equals(o)) {
                        t.mirror = true;
                        continue;
                    }
                    sb.append(sb.length() == 0 ? "" : " ").append(o);
                }
            }
            t.justify = sb.length() == 0 ? null : sb.toString();
        }
        t.hidden = e.yes("hide") || effects != null && effects.yes("hide");
        pcb.texts.add(t);
    }

    private void parseTrack(SExpr e) {
        Track t = new Track();
        try {
            t.x1 = xy(e, "start", 0);
            t.y1 = xy(e, "start", 1);
            t.x2 = xy(e, "end", 0);
            t.y2 = xy(e, "end", 1);
            if ("arc".equals(e.head)) {
                t.mx = xy(e, "mid", 0);
                t.my = xy(e, "mid", 1);
                checkArc(new double[] {t.x1, t.y1, t.mx, t.my, t.x2, t.y2});
            }
            SExpr w = e.child("width");
            t.width = w == null ? 0 : width(w, 0);
        }
        catch (Bad b) {
            badNumber(e.line);
            return;
        }
        t.layer = e.str("layer");
        pcb.tracks.add(t);
    }

    private void parseVia(SExpr e) {
        Via v = new Via();
        try {
            SExpr at = e.child("at");
            SExpr size = e.child("size");
            SExpr drill = e.child("drill");
            if (at == null || size == null || drill == null) {
                throw new Bad();
            }
            v.x = coord(at, 0);
            v.y = coord(at, 1);
            v.size = size(size, 0);
            v.drill = size(drill, 0);
        }
        catch (Bad b) {
            badNumber(e.line);
            return;
        }
        SExpr layers = e.child("layers");
        if (layers != null) {
            for (Object o : layers.items) {
                if (o instanceof String) {
                    v.layers.add((String) o);
                }
            }
        }
        SExpr tenting = e.child("tenting");
        if (tenting != null) {
            v.tentedFront = tenting.yes("front");
        }
        pcb.vias.add(v);
    }

    private Zone parseZone(SExpr e) throws SExprException {
        Zone z = new Zone();
        z.line = e.line;
        String one = e.str("layer");
        if (one != null) {
            z.layers.add(one);
        }
        SExpr layers = e.child("layers");
        if (layers != null) {
            for (Object o : layers.items) {
                if (o instanceof String) {
                    z.layers.add((String) o);
                }
            }
        }
        z.keepout = e.child("keepout") != null;
        if (z.keepout) {
            return z;
        }
        for (SExpr fp : e.children("filled_polygon")) {
            String layer = fp.str("layer");
            if (layer == null && z.layers.size() == 1) {
                layer = z.layers.iterator().next();
            }
            if (layer == null) {
                continue;
            }
            try {
                double[] p = pts(fp.child("pts"));
                if (p.length >= 6) {
                    z.filled.computeIfAbsent(layer, k -> new ArrayList<>()).add(p);
                }
            }
            catch (Bad b) {
                badNumber(fp.line);
            }
        }
        return z;
    }

    // ---- numbers -------------------------------------------------------------------------------------------------

    private static double xy(SExpr e, String head, int i) throws Bad {
        SExpr c = e.child(head);
        if (c == null) {
            throw new Bad();
        }
        return coord(c, i);
    }

    private static double num(SExpr e, int i) throws Bad {
        String a = e.atom(i);
        if (a == null) {
            throw new Bad();
        }
        try {
            double v = Double.parseDouble(a);
            if (!Double.isFinite(v)) {
                throw new Bad();
            }
            return v;
        }
        catch (NumberFormatException x) {
            throw new Bad();
        }
    }

    private static double coord(SExpr e, int i) throws Bad {
        return checkCoord(num(e, i));
    }

    private static double checkCoord(double v) throws Bad {
        if (Math.abs(v) > MAX_MM) {
            throw new Bad();
        }
        return v;
    }

    /** Sizes, radii and drills: (0, 2147] mm. */
    private static double size(SExpr e, int i) throws Bad {
        double v = num(e, i);
        if (!(v > 0 && v <= MAX_MM)) {
            throw new Bad();
        }
        return v;
    }

    /** Widths: [0, 2147] mm. */
    private static double width(SExpr e, int i) throws Bad {
        double v = num(e, i);
        if (!(v >= 0 && v <= MAX_MM)) {
            throw new Bad();
        }
        return v;
    }

    /** An optional angle: absent is 0; present must be a finite number. */
    private static double angle(SExpr e, int i) throws Bad {
        return e.atom(i) == null ? 0 : num(e, i);
    }

    /** An arc must have a finite radius within KiCad's range (three almost collinear points do not). */
    private static void checkArc(double[] p) throws Bad {
        double[] a = KGeom.arc3(p[0], p[1], p[2], p[3], p[4], p[5]);
        if (a == null || !(a[2] > 0 && a[2] <= MAX_MM)) {
            throw new Bad();
        }
    }

    private static Double optNum(SExpr e, String head) {
        SExpr c = e.child(head);
        if (c == null || c.atom(0) == null) {
            return null;
        }
        double v = c.num(0, Double.NaN);
        return Double.isNaN(v) || Math.abs(v) > MAX_MM ? null : v;
    }

    private static double finite(double v) {
        return Double.isFinite(v) && Math.abs(v) <= MAX_MM ? v : 0;
    }

    private void badNumber(int line) {
        List<String> refs = currentRef.isEmpty() ? null : Collections.singletonList(KText.clean(currentRef, 100));
        warn(ImportWarning.of("BAD_NUMBER", refs, String.format(Locale.ROOT,
                "Line %d: a number could not be read or is out of range; that item was skipped.", line)));
    }

    private void warn(ImportWarning w) {
        if (!ImportWarning.addCapped(pcb.warnings, w, MAX_WARNINGS)) {
            pcb.warningsDropped++;
        }
    }
}
