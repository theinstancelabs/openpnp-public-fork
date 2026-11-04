package org.openpnp.instancelabs.board;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.openpnp.instancelabs.dispense.DispenseDb;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.PadTypeResolver;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.pmw.tinylog.Logger;

/**
 * The board drawings of one configuration directory (design §4.4): finds a board's art by its geometry digest
 * ({@link BoardArtIndex}), else makes fallback art from the board file ({@link BoardArtFallback}, kept in a small
 * cache), serves art bytes by id, and gives the automatic probe and registration what the art knows (keep-outs,
 * fiducials, the outline). It never reads OpenPnP's configuration itself: callers pass a Board they read where
 * OpenPnP's lists may be read (§3.6). Synchronized.
 */
public final class BoardArtStore {
    public static final String NOTE_CHANGED = "The board file changed since the KiCad drawing was saved.";
    static final int FALLBACK_CACHE = 64;
    static final int PARSED_CACHE = 16;

    private static BoardArtStore current;

    /** What a plan or the board list records about a board's drawing: {id, kind, note}. */
    public static final class ArtRef {
        public final String id;
        /** kicad | fallback. */
        public final String kind;
        /** Null, or why a board that had a KiCad drawing is drawn from its file. */
        public final String note;

        ArtRef(String id, String kind, String note) {
            this.id = id;
            this.kind = kind;
            this.note = note;
        }
    }

    /** The import record of a board with KiCad art (GET /api/boards, §3.8). */
    public static final class Imported {
        public String slug;
        public String name;
        public long atMs;
        public String sourceName;
        public Integer kicadVersion;
        /** import | attach. */
        public String how;
        /** outline | edges | estimated (§2.6). */
        public String origin;
        /** attach: the board file the drawing was attached to. */
        public String board;
        /** BR-88: the footprints the upload does not place and its pasteDnp; null for drawings saved before them. */
        public BoardArt.Skipped skipped;
        public Boolean pasteDnp;
    }

    private static final class Fallback {
        final String id;
        final byte[] bytes;

        Fallback(String id, byte[] bytes) {
            this.id = id;
            this.bytes = bytes;
        }
    }

    private final BoardArtIndex index;
    /** Art ids whose file was read and matched its id since the last invalidate. */
    private final Set<String> verified = new HashSet<>();
    private final LinkedHashMap<String, Fallback> fallbacks = new LinkedHashMap<String, Fallback>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Fallback> e) {
            return size() > FALLBACK_CACHE;
        }
    };
    private final LinkedHashMap<String, BoardArt> parsed = new LinkedHashMap<String, BoardArt>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BoardArt> e) {
            return size() > PARSED_CACHE;
        }
    };
    /**
     * BR-88: art id → its source and import blocks (a BoardArt without the drawing, a few KB), one per drawing read in
     * this run; never stale (an id is a content address), so a new index parses only the new drawings.
     */
    private final Map<String, BoardArt> records = new HashMap<>();
    /** BR-88: board file (canonical) + '|' + geometry digest → its own upload; null until read after an invalidate. */
    private Map<String, BoardArt> uploads;
    /** BR-88: the atMs of the last save, replace or attach of this run that succeeded; null before one. */
    private volatile Long savedAtMs;

    private BoardArtStore(BoardArtIndex index) {
        this.index = index;
    }

    /** The store of OpenPnP's configuration directory (a new one when the directory changed). */
    public static synchronized BoardArtStore get() {
        Configuration c = Configuration.get();
        if (c == null) {
            throw new IllegalStateException("OpenPnP's configuration is not loaded");
        }
        return forDir(c.getConfigurationDirectory());
    }

    public static synchronized BoardArtStore forDir(File configDir) {
        BoardArtIndex idx = BoardArtIndex.forDir(configDir);
        if (current == null || current.index != idx) {
            current = new BoardArtStore(idx);
        }
        return current;
    }

    public BoardArtIndex index() {
        return index;
    }

    // ---- references ----------------------------------------------------------------------------------------------

    /** The art of a board, with the planner's pad types for fallback art (from the dispense service, if running). */
    public ArtRef refFor(Board b) {
        return refFor(b, serviceResolver());
    }

    /**
     * The art of a board: its KiCad art when the index knows its geometry digest, else fallback art (made now, or
     * from the cache), with a note when the same file had a KiCad drawing under another digest.
     *
     * @param resolver the planner's pad types for fallback pads; null: footprint pads only
     */
    public synchronized ArtRef refFor(Board b, PadTypeResolver resolver) {
        String digest = BoardArtIndex.digest(b);
        BoardArtIndex.Entry e = index.board(digest);
        if (e != null) {
            if (verified.contains(e.artId) || indexedBytes(e.artId) != null) {
                verified.add(e.artId);
                return new ArtRef(e.artId, BoardArt.KIND_KICAD, null);
            }
            Logger.warn("board art {} of {} is missing: drawing the board from its file", e.artId, e.file);
        }
        File file = b.getFile();
        String key = digest + '|' + (resolver == null ? "-" : resolver.fingerprint()) + '|' + contentDigest(b) + '|'
                + (file == null ? "" : file.getAbsolutePath());
        Fallback fb = fallbacks.get(key);
        if (fb == null) {
            byte[] bytes = BoardArtFallback.build(b, resolver).toBytes();
            fb = new Fallback(BoardArt.idOf(bytes), bytes);
            fallbacks.put(key, fb);
        }
        String note = null;
        for (String d : index.boardsAt(file).keySet()) {
            if (!d.equals(digest)) {
                note = NOTE_CHANGED;
                break;
            }
        }
        return new ArtRef(fb.id, BoardArt.KIND_FALLBACK, note);
    }

    /**
     * The art bytes of an id: a drawing the index knows (current or history; its SHA-256 must still match the id),
     * else fallback art made earlier, else null.
     */
    public synchronized byte[] bytes(String id) {
        if (id == null || !BoardArtIndex.ART_ID.matcher(id).matches()) {
            return null;
        }
        byte[] b = indexedBytes(id);
        if (b != null) {
            return b;
        }
        for (Fallback fb : fallbacks.values()) {
            if (fb.id.equals(id)) {
                return fb.bytes;
            }
        }
        return null;
    }

    /** The file the index gives for an id, when it can be read and its SHA-256 still matches the id. */
    private byte[] indexedBytes(String id) {
        File f = index.artFile(id);
        if (f == null || !f.isFile()) {
            return null;
        }
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            if (id.equals(BoardArt.idOf(b))) {
                return b;
            }
            Logger.warn("board art {} does not match its id {}: not used", f, id);
        }
        catch (IOException e) {
            Logger.warn("board art {} cannot be read: {}", f, e.getMessage());
        }
        verified.remove(id);
        return null;
    }

    /**
     * Index.json again (after a save or an attach). The caches by art id stay: art ids are content addresses. BR-88:
     * each board file's own upload is found again.
     */
    public synchronized void invalidate() {
        index.reload();
        verified.clear();
        uploads = null;
    }

    /** BR-88: a save, replace or attach succeeded (its atMs): GET /api/status gives it as boardImport.savedAtMs. */
    public void saved(long atMs) {
        savedAtMs = atMs;
    }

    /** BR-88: the record time (atMs, taken as it started) of the last save, replace or attach that succeeded; or null. */
    public Long savedAtMs() {
        return savedAtMs;
    }

    // ---- what the KiCad art offers -------------------------------------------------------------------------------

    /**
     * The import record of a board with KiCad art, else null. BR-88: the record of the board's own upload when it has
     * one ({@link #ownUpload}), else the drawing's (a byte copy; a board file whose drawing came by geometry only); the
     * origin is always the drawing's.
     */
    public synchronized Imported imported(Board b) {
        BoardArt a = kicadArt(b);
        if (a == null) {
            return null;
        }
        BoardArt own = ownUpload(b);
        BoardArt r = own == null ? a : own;
        Imported i = new Imported();
        if (r.imported != null) {
            i.slug = r.imported.slug;
            i.name = r.imported.name;
            i.atMs = r.imported.atMs;
            i.how = r.imported.how;
            i.board = r.imported.board;
            i.skipped = r.imported.skipped;
            i.pasteDnp = r.imported.pasteDnp;
        }
        if (r.source != null) {
            i.sourceName = r.source.name;
            i.kicadVersion = r.source.kicadVersion;
        }
        i.origin = a.origin;
        return i;
    }

    /** True when the board has a KiCad drawing (its keep-outs and fiducial sizes are known, even if there are none). */
    public synchronized boolean hasKicadArt(Board b) {
        return kicadArt(b) != null;
    }

    /** Keep-out circles {x, y, r} in board mm for the automatic probe; empty without KiCad art. */
    public synchronized List<KeepOut> keepOuts(Board b) {
        return KeepOut.of(kicadArt(b));
    }

    /** The fiducials with their copper and mask diameters; empty without KiCad art. */
    public synchronized List<BoardArt.FidArt> fiducials(Board b) {
        BoardArt a = kicadArt(b);
        return a == null || a.fiducials == null ? Collections.emptyList() : new ArrayList<>(a.fiducials);
    }

    /**
     * The closed outline loop first, then the cut-outs (board mm, x0 y0 x1 y1 …, within 0.01 mm); empty without
     * KiCad art or when the outline is not closed.
     */
    public synchronized List<double[]> outlineLoops(Board b) {
        BoardArt a = kicadArt(b);
        List<double[]> l = new ArrayList<>();
        if (a == null || !a.outline.closed) {
            return l;
        }
        for (ArtPath.Sub s : ArtPath.parse(a.outline.d)) {
            l.add(s.pts);
        }
        for (ArtPath.Sub s : ArtPath.parse(a.outline.cutouts)) {
            if (s.closed) {
                l.add(s.pts);
            }
        }
        return l;
    }

    /** The KiCad art of a board (parsed, cached by id), or null. */
    private BoardArt kicadArt(Board b) {
        BoardArtIndex.Entry e = index.board(BoardArtIndex.digest(b));
        if (e == null) {
            return null;
        }
        BoardArt a = parsed.get(e.artId);
        if (a != null) {
            return a;
        }
        a = read(e.artId);
        if (a != null) {
            parsed.put(e.artId, a);
        }
        return a;
    }

    /**
     * BR-88: the newest current drawing (not one a replace moved to history/) saved for this board file at its present
     * geometry: an import's, whose board file is {@code <slug>/<slug>.board.xml} next to its {@code <slug>.art.json},
     * or an attach's, which names the board file. Its source and import blocks only; null when there is none. The
     * current drawings are sorted by board file once per index ({@link #invalidate}), not per board.
     */
    private BoardArt ownUpload(Board b) {
        if (uploads == null) {
            uploads = new HashMap<>();
            for (Map.Entry<String, String> e : index.arts().entrySet()) {
                String slug = e.getValue() == null ? "" : e.getValue().split("/", 2)[0]; // null: a hand-edited index
                if (!BoardStore.artPath(slug).equals(e.getValue())) {
                    continue; // history/: a replace displaced it
                }
                BoardArt r = record(e.getKey());
                if (r == null || r.imported == null) {
                    continue;
                }
                File saved = r.imported.board != null ? new File(r.imported.board)
                        : new File(new File(index.root(), slug), slug + ".board.xml");
                uploads.merge(BoardArtIndex.canonical(saved) + '|' + r.imported.boardDigest, r,
                        (x, y) -> y.imported.atMs > x.imported.atMs ? y : x);
            }
        }
        return b.getFile() == null ? null
                : uploads.get(BoardArtIndex.canonical(b.getFile()) + '|' + BoardArtIndex.digest(b));
    }

    /** BR-88: the source and import blocks of an art id (read once: the id is a content address), or null. */
    private BoardArt record(String id) {
        BoardArt r = records.get(id);
        if (r != null) {
            return r;
        }
        BoardArt a = parsed.get(id);
        if (a == null) {
            a = read(id);
        }
        if (a == null) {
            return null;
        }
        r = new BoardArt();
        r.source = a.source;
        r.imported = a.imported;
        records.put(id, r);
        return r;
    }

    /** The art of an id, parsed; null when its file is gone, changed or not art. */
    private BoardArt read(String id) {
        byte[] bytes = bytes(id);
        if (bytes == null) {
            return null;
        }
        try {
            return BoardArt.parse(bytes);
        }
        catch (IllegalArgumentException x) {
            Logger.warn("board art {} cannot be read: {}", id, x.getMessage());
            return null;
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** The planner's resolver when the dispense service runs, else null (fallback pads from footprints only). */
    static PadTypeResolver serviceResolver() {
        DispenseService svc = DispenseService.get();
        DispenseDb db = svc == null ? null : svc.getDb();
        if (db == null) {
            return null;
        }
        try {
            return svc.padTypeResolver(); // the mounted tip setup's view (§22.2)
        }
        catch (SQLException e) {
            Logger.warn("pad types cannot be read for the board drawing: {}", e.getMessage());
            return null;
        }
    }

    /**
     * What fallback art also depends on beyond the geometry digest: each placement's part and package, and each
     * package's footprint pads (a changed pad redraws the board).
     */
    static String contentDigest(Board b) {
        StringBuilder sb = new StringBuilder();
        Map<String, Package> packages = new TreeMap<>();
        for (Placement p : b.getPlacements()) {
            Part part = p.getPart();
            Package pkg = part == null ? null : part.getPackage();
            sb.append(p.getId()).append('|').append(p.isEnabled()).append('|')
                    .append(part == null ? "" : part.getId()).append('|').append(pkg == null ? "" : pkg.getId())
                    .append('\n');
            if (pkg != null && pkg.getId() != null) {
                packages.put(pkg.getId(), pkg);
            }
        }
        for (Package pkg : packages.values()) {
            Footprint fp = pkg.getFootprint();
            sb.append(pkg.getId()).append(':');
            if (fp != null && fp.getPads() != null) {
                sb.append(fp.getUnits());
                for (Footprint.Pad pad : fp.getPads()) {
                    sb.append(String.format(Locale.ROOT, ";%s,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f", pad.getName(),
                            pad.getX(), pad.getY(), pad.getWidth(), pad.getHeight(), pad.getRotation(),
                            pad.getRoundness()));
                }
            }
            sb.append('\n');
        }
        return BoardArt.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }
}
