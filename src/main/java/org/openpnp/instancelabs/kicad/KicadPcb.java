package org.openpnp.instancelabs.kicad;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A .kicad_pcb file as read by {@link KicadParser} (design §2.3): KiCad millimetres in KiCad's frame (Y down, as in
 * the file). Strings are raw; everything that reaches OpenPnP or the art goes through {@link KText} first.
 */
public final class KicadPcb {
    /** KiCad 8 made local overrides optional values: from this version a present 0 margin overrides (§2.5). */
    public static final int V_KICAD8 = 20240108;
    public static final int V_KICAD6 = 20211014;
    public static final int V_KICAD10 = 20260206;

    public int version;                       // (version N); 0 if absent
    public String generator;
    public String generatorVersion;
    public boolean legacy;                    // KiCad 5 rules: footprints are (module …) or the version is older than 6
    public Setup setup = new Setup();
    public final List<Footprint> footprints = new ArrayList<>(); // top AND bottom (classification decides)
    public final List<Graphic> graphics = new ArrayList<>();     // board-level gr_*
    public final List<Text> texts = new ArrayList<>();           // board-level gr_text
    public final List<Track> tracks = new ArrayList<>();         // segment and arc
    public final List<Via> vias = new ArrayList<>();
    public final List<Zone> zones = new ArrayList<>();           // board-level zones
    public int ignoredItems;                                     // unknown heads, counted for the info line
    /** What the parser itself found (BAD_NUMBER, KICAD5, KICAD_NEWER, PADSTACK_IGNORED), file order. */
    public final List<ImportWarning> warnings = new ArrayList<>();
    public int warningsDropped;                                  // beyond the 500 kept

    public static final class Setup {
        public Double padToMaskClearance;
        public Double padToPasteClearance;
        public Double padToPasteClearanceRatio;
        public boolean viasTentedFront = true;  // board default for vias (§2.4: three forms)
        public String maskColor;                // stackup F.Mask (color "…"), null if absent
        public String silkColor;                // stackup F.SilkS (color "…"), null if absent
    }

    public static final class Footprint {
        public String lib;                      // "Resistor_SMD"; "" when the name has no library (mobo "LOGO")
        public String name;                     // "R_0603_1608Metric"
        public String ref;                      // Reference (property or fp_text), raw; "" if absent
        public String value;                    // Value (property or fp_text), raw; "" if absent
        public boolean refHidden;
        public String refLayer;
        public double refX;                     // label position as written (footprint-local)
        public double refY;
        public double refAngle;
        public double refSize;                  // font height, 0 if absent
        public boolean top;                     // (layer "F.Cu")
        public double x;                        // (at x y [angle]); angle degrees CCW as shown, 0 when omitted
        public double y;
        public double angle;
        public final Set<String> attrs = new LinkedHashSet<>(); // smd through_hole board_only dnp virtual …
        public Double pasteMargin;              // footprint-level overrides (null = not present)
        public Double pasteRatio;
        public Double maskMargin;
        public final List<Pad> pads = new ArrayList<>();
        public final List<Graphic> graphics = new ArrayList<>(); // fp_*, footprint-local coordinates
        /** Zones inside the footprint. Their points are board coordinates (KiCad frame), as KiCad stores them. */
        public final List<Zone> zones = new ArrayList<>();
        public int line;                        // for warnings

        public boolean hasAttr(String a) {
            return attrs.contains(a);
        }
    }

    public static final class Pad {
        public String name;                     // raw; may be "" or repeated
        public String type;                     // smd thru_hole np_thru_hole connect
        public String shape;                    // rect roundrect circle oval trapezoid custom
        public double x;                        // footprint-local; for a bottom footprint already mirrored by KiCad
        public double y;
        public double angle;                    // as written: absolute (includes the footprint angle)
        public double w;
        public double h;
        public double rratio;                   // roundrect_rratio, 0 if absent
        public Double chamferRatio;
        public final Set<String> chamfer = new HashSet<>();
        public double deltaX;                   // rect_delta (trapezoid)
        public double deltaY;
        public double drillW;                   // drill (circle: w = h); 0 = no drill
        public double drillH;
        public double offX;                     // shape offset from the pad position (drill offset), pad frame
        public double offY;
        public final Set<String> layers = new HashSet<>();
        public Double pasteMargin;
        public Double pasteRatio;
        public Double maskMargin;
        public String anchor = "circle";        // custom pads: the anchor shape (options (anchor rect|circle))
        public final List<Graphic> primitives = new ArrayList<>(); // custom pads, pad-local
        public int line;

        public boolean on(String layer) {
            return layers.contains(layer);
        }

        /** On F.Cu: "F.Cu", "*.Cu" or "F&B.Cu". */
        public boolean onFrontCopper() {
            return on("F.Cu") || on("*.Cu") || on("F&B.Cu");
        }

        public boolean onFrontPaste() {
            return on("F.Paste") || on("*.Paste");
        }

        public boolean onFrontMask() {
            return on("F.Mask") || on("*.Mask");
        }
    }

    /** gr_* and fp_*; arcs normalised to start/mid/end (KiCad 5 arcs are converted). */
    public static final class Graphic {
        public enum Kind {
            LINE, ARC, CIRCLE, RECT, POLY, BEZIER
        }

        public Kind kind;
        public String layer;
        public double width;
        public boolean filled;
        /**
         * LINE x1 y1 x2 y2; ARC sx sy mx my ex ey; CIRCLE cx cy ex ey; RECT x1 y1 x2 y2; POLY flattened x0 y0 x1 y1 …
         * (not repeating the first point); BEZIER 4 control points.
         */
        public double[] pts;
        public int line;
    }

    public static final class Text {
        public String text;
        public String layer;
        public String justify;                  // the justify atoms joined by a space ("left bottom"), null if absent
        public double x;
        public double y;
        public double angle;
        public double size;                     // font height
        public boolean hidden;
        public boolean mirror;
    }

    public static final class Zone {
        public final Set<String> layers = new HashSet<>();   // "F.Cu", "F&B.Cu", "*.Cu" …
        public boolean keepout;                              // keepouts keep no fills
        public final Map<String, List<double[]>> filled = new LinkedHashMap<>(); // layer -> polygons x0 y0 x1 y1 …
        public int line;
    }

    public static final class Track {
        public double x1;
        public double y1;
        public double x2;
        public double y2;
        public Double mx;                       // arc midpoint; null for a straight segment
        public Double my;
        public double width;
        public String layer;
    }

    public static final class Via {
        public double x;
        public double y;
        public double size;
        public double drill;
        public final Set<String> layers = new HashSet<>();
        public Boolean tentedFront;             // null = the board default

        public boolean tentedFront(Setup s) {
            return tentedFront != null ? tentedFront : s.viasTentedFront;
        }
    }
}
