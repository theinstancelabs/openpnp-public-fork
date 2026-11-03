package org.openpnp.instancelabs.kicad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What an import would make of a KiCad board (design §2.9): pure data in the board frame, no OpenPnP objects and no
 * final package or part ids (the id allocator gives those, §3.5).
 */
public final class ImportProposal {
    public static final int MAX_WARNINGS = 500;

    public static final String SKIP_BOTTOM = "bottom";
    public static final String SKIP_ART_ONLY = "artOnly";
    public static final String SKIP_DNP = "dnp";
    public static final String SKIP_PASTE_ONLY = "pasteOnly";

    public double widthMm;
    public double heightMm;
    /** KiCad coordinates of the board origin: (minX, maxY). */
    public double minX;
    public double maxY;
    public String outline;                    // "closed" | "open" | "none"
    public String origin;                     // "outline" | "edges" | "estimated" (§2.6)
    /** Do-not-place footprints with paste became disabled placements (the preview's checkbox, §13 item 1). */
    public boolean pasteDnp;
    public final List<PlacementP> placements = new ArrayList<>();  // file order, fiducials included
    public final List<PackageP> packages = new ArrayList<>();      // one per distinct (name, signature) = variant
    /** bottom / artOnly / dnp / pasteOnly → cleaned references, file order (always all four keys). */
    public final Map<String, List<String>> skipped = new LinkedHashMap<>();
    /** One entry per KiCad footprint, in the file's order: how it was classified (the art draws every one). */
    public final List<FootprintP> footprints = new ArrayList<>();
    public final List<ImportWarning> warnings = new ArrayList<>();
    public int warningsDropped;
    public Counts counts = new Counts();

    public ImportProposal() {
        for (String k : Arrays.asList(SKIP_BOTTOM, SKIP_ART_ONLY, SKIP_DNP, SKIP_PASTE_ONLY)) {
            skipped.put(k, new ArrayList<>());
        }
    }

    public static final class Counts {
        public int footprints;
        public int placements;                // WITHOUT fiducials, everywhere
        public int fiducials;
        public int pastePads;
        public int dnp;
        public int pasteOnly;
        public int bottom;
        public int artOnly;
    }

    public static final class PlacementP {
        public String id;                     // cleaned, unique; the OpenPnP placement id
        public String ref;                    // cleaned KiCad reference
        public String value;                  // cleaned; "" when blank
        public int packageIndex;
        public String type;                   // "Placement" | "Fiducial"
        public boolean enabled;
        public double x;                      // board frame, mm, rounded to 1e-6
        public double y;
        public double rotation;               // KiCad's angle as written
        public int pads;
        public String why;                    // null; "dnp" (pasted on request) or "pasteOnly" (pasted, not placed)
        public int footprintIndex;            // into KicadPcb.footprints
    }

    public static final class PackageP {
        public String baseId;                 // cleaned footprint name (no library)
        public int variant;                   // 1, 2, … in file order
        public String lib;                    // cleaned
        public String kicadName;              // cleaned
        public boolean fiducial;
        public final List<PadP> pads = new ArrayList<>();  // mm, part frame
        public String signature;
        public double bodyW;                  // F.Fab box, else courtyard; 0 if none
        public double bodyH;
        public final List<String> refs = new ArrayList<>();
    }

    public static final class PadP {
        public String name;
        public double x;
        public double y;
        public double w;
        public double h;
        public double rotation;
        public double roundness;
        public String approx;                 // null, "trapezoid", "custom", "polygon"
    }

    public static final class FootprintP {
        public static final String PLACEMENT = "placement";
        public static final String FIDUCIAL = "fiducial";
        public static final String DNP = "dnp";
        public static final String PASTE_ONLY = "pasteOnly";
        public static final String ART = "art";
        public static final String BOTTOM = "bottom";

        public int index;                     // into KicadPcb.footprints
        public String ref;                    // the placement id for placements, else the cleaned reference
        public String kind;                   // placement fiducial dnp pasteOnly art bottom (the art's parts[].kind)
        public int placement = -1;            // into placements, or -1
        public String why;                    // art only: "no paste pads", "through-hole", "board only", …
    }

    /**
     * Adds a warning; beyond 500 the least important are dropped and counted for one WARNINGS_TRUNCATED note (other
     * slices add theirs, NO_PAD_TYPE for one, the same way, then call sortWarnings again).
     */
    public void warn(ImportWarning w) {
        if (!ImportWarning.addCapped(warnings, w, MAX_WARNINGS)) {
            warningsDropped++;
        }
    }

    /** Group order (stable), then the WARNINGS_TRUNCATED note if any were dropped. Safe to call again. */
    public void sortWarnings() {
        warnings.removeIf(w -> "WARNINGS_TRUNCATED".equals(w.code));
        ImportWarning.sort(warnings);
        if (warningsDropped > 0) {
            warnings.add(ImportWarning.of("WARNINGS_TRUNCATED", null,
                    String.format(Locale.ROOT, "%,d more notes not shown.", warningsDropped)));
        }
    }

    public List<ImportWarning> warnings(String code) {
        List<ImportWarning> l = new ArrayList<>();
        for (ImportWarning w : warnings) {
            if (w.code.equals(code)) {
                l.add(w);
            }
        }
        return l;
    }

    public PlacementP placement(String id) {
        for (PlacementP p : placements) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }
}
