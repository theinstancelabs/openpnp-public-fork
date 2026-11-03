package org.openpnp.instancelabs.board;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import org.openpnp.instancelabs.kicad.ImportProposal;
import org.openpnp.instancelabs.kicad.ImportWarning;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

/**
 * The board art (design §4.1): a compact drawing of the board's top side in board millimetres (+Y up, origin at the
 * bottom-left of the outline box), made at import from KiCad or as a fallback from a board file. Paths are SVG path
 * data ({@link ArtPath}); filled paths use the nonzero rule, outline + cut-outs the evenodd rule. Serialized with Gson
 * in field order, so the same art always gives the same bytes and the same {@link #idOf id}.
 */
public final class BoardArt {
    public static final String FORMAT = "il-board-art";
    public static final String KIND_KICAD = "kicad";
    public static final String KIND_FALLBACK = "fallback";

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    public String format = FORMAT;
    public int version = 1;
    public String kind;
    public String units = "mm";
    public String frame = "board";
    public double precision = 0.01;
    public double[] size;
    /** outline | edges | estimated (§2.6); null in fallback art. */
    public String origin;
    public Finish finish = new Finish();
    public Source source = new Source();
    /** Present only in KiCad art; filled by the import (§3.6) or the attach (§3.10). */
    @SerializedName("import")
    public Import imported;
    public Outline outline = new Outline();
    public Layers layers = new Layers();
    public List<Text> texts = new ArrayList<>();
    public List<Part> parts = new ArrayList<>();
    public List<FidArt> fiducials = new ArrayList<>();
    /**
     * Keep-out circles of the drills ([x, y, r] in board mm): plated holes (r = half the larger side of their top
     * copper, else drill / 2 + 0.25), unplated holes (drill / 2) and vias through the top (size / 2). The store adds
     * the outline's circles (§4.4). Empty in fallback art.
     */
    public List<double[]> keepOutHoles = new ArrayList<>();
    public Thumb thumb = new Thumb();
    public Stats stats = new Stats();

    public static final class Finish {
        /** slate (default), green, red, blue, purple, black, white, yellow. */
        public String mask = "slate";
        /** white (default), black, yellow. */
        public String silk = "white";
    }

    public static final class Source {
        public String name;
        public Long bytes;
        public String sha256;
        public Integer kicadVersion;
        public String generator;
        /** Fallback art only: the board file it was drawn from. */
        public String boardFile;
    }

    /** What the import (or the attach) made of the file (§3.2), for the board list and the Board step. */
    public static final class Import {
        public String how;                    // import | attach
        public String name;
        public String slug;
        public long atMs;
        public int importer = 1;
        /** The geometry digest (§3.7) of the board this art was saved for. */
        public String boardDigest;
        /** attach: the board file the drawing was attached to; null for an import. */
        public String board;
        public List<Offset> offsets = new ArrayList<>();
        public ImportProposal.Counts counts;
        public List<IdState> packages = new ArrayList<>();
        public List<IdState> parts = new ArrayList<>();
        public List<ImportWarning> warnings = new ArrayList<>();

        /** The counts and warnings of a proposal; the caller sets the rest (ids come from the id allocator). */
        public static Import from(ImportProposal p, List<ImportWarning> artWarnings) {
            Import i = new Import();
            i.counts = p.counts;
            i.warnings.addAll(p.warnings);
            if (artWarnings != null) {
                i.warnings.addAll(artWarnings);
                ImportWarning.sort(i.warnings);
            }
            return i;
        }
    }

    public static final class Offset {
        public String id;
        public double mm;

        public Offset(String id, double mm) {
            this.id = id;
            this.mm = mm;
        }
    }

    public static final class IdState {
        public String id;
        public String state;

        public IdState(String id, String state) {
            this.id = id;
            this.state = state;
        }
    }

    public static final class Outline {
        /** The outline loop (Z-closed when closed), or the open chains, or the board box when there is no outline. */
        public String d = "";
        public String cutouts = "";
        public boolean closed;
    }

    public static final class Layers {
        public Cu cu = new Cu();
        public Mask mask = new Mask();
        public Fill exposed = new Fill();
        public Fill paste = new Fill();
        public Silk silk = new Silk();
        public Holes holes = new Holes();
        public Fab fab = new Fab();
    }

    public static final class Cu {
        public String fill = "";
        public List<Stroke> tracks = new ArrayList<>();
    }

    public static final class Mask {
        public String open = "";
    }

    public static final class Fill {
        public String fill = "";
    }

    public static final class Silk {
        public String fill = "";
        public List<Stroke> lines = new ArrayList<>();
    }

    public static final class Holes {
        public String pth = "";
        public String npth = "";
        public String via = "";
    }

    public static final class Fab {
        public List<Stroke> lines = new ArrayList<>();
    }

    /** Lines of one width (mm), merged into one path. */
    public static final class Stroke {
        public double w;
        public String d;

        public Stroke(double w, String d) {
            this.w = w;
            this.d = d;
        }
    }

    /** A board-level silkscreen text; \n separates lines; (x, y) is the middle of the block's height. */
    public static final class Text {
        public String t;
        public double x;
        public double y;
        public double a;
        public double h;
        public String anchor;                 // start | middle | end
    }

    /** One footprint: its position, its body box in the part frame (x0, y0, x1, y1) and its label. */
    public static final class Part {
        public String ref;
        /** placement, fiducial, dnp, pasteOnly, art, bottom. */
        public String kind;
        public String pkg;
        public String value;
        public double x;
        public double y;
        public double a;
        public double[] box;
        public int pads;
        public Label label;
    }

    public static final class Label {
        public double x;
        public double y;
        public double a;
        public double h;
    }

    public static final class FidArt {
        public String name;
        public double bx;
        public double by;
        public double copperMm;
        public double maskMm;
    }

    public static final class Thumb {
        public double toleranceMm = 0.1;
        public String outline = "";
        public String mask = "";
        public String exposed = "";
    }

    public static final class Stats {
        /** Points of every path. */
        public long points;
        /** Size of the art file in bytes ({@link BoardArt#measure}). */
        public long bytes;
        public double toleranceMm = 0.01;
        /** The tolerance of copper, tracks, silkscreen and fab after fitting the size budget (§4.2). */
        public double coarseToleranceMm = 0.01;
    }

    // ---- bytes ---------------------------------------------------------------------------------------------------

    /** The JSON bytes (UTF-8): the same art gives the same bytes. */
    public byte[] toBytes() {
        return GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Sets stats.bytes to the size of this art's bytes (its own digits included). The builders call it; call it again
     * after adding the source and import blocks, before {@link #toBytes} makes the file and its id.
     */
    public void measure() {
        long n = 0;
        for (int i = 0; i < 4; i++) {
            stats.bytes = n;
            long len = toBytes().length;
            if (len == n) {
                return;
            }
            n = len;
        }
        stats.bytes = n;
    }

    public static BoardArt parse(byte[] bytes) {
        try {
            BoardArt a = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), BoardArt.class);
            if (a == null || !FORMAT.equals(a.format)) {
                throw new IllegalArgumentException("not board art");
            }
            return a;
        }
        catch (JsonParseException e) {
            throw new IllegalArgumentException("not board art: " + e.getMessage(), e);
        }
    }

    /** The art id: the first 16 hex digits of the SHA-256 of the bytes (a content address, §4.4). */
    public static String idOf(byte[] bytes) {
        return sha256Hex(bytes).substring(0, 16);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JVM has SHA-256
        }
    }

    /** Stroke groups of a map width (0.01 mm units) → path, ascending width. */
    static List<Stroke> strokes(SortedMap<Long, ArtPath> byWidth) {
        List<Stroke> l = new ArrayList<>();
        for (Map.Entry<Long, ArtPath> e : byWidth.entrySet()) {
            if (!e.getValue().isEmpty()) {
                l.add(new Stroke(e.getKey() / 100.0, e.getValue().toString()));
            }
        }
        return l;
    }
}
