package org.openpnp.instancelabs.board;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
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

    /** Index.json again (after a save or an attach). The caches stay: art ids are content addresses. */
    public synchronized void invalidate() {
        index.reload();
        verified.clear();
    }

    // ---- what the KiCad art offers -------------------------------------------------------------------------------

    /** The import record of a board with KiCad art, else null. */
    public synchronized Imported imported(Board b) {
        BoardArt a = kicadArt(b);
        if (a == null) {
            return null;
        }
        Imported i = new Imported();
        if (a.imported != null) {
            i.slug = a.imported.slug;
            i.name = a.imported.name;
            i.atMs = a.imported.atMs;
            i.how = a.imported.how;
            i.board = a.imported.board;
        }
        if (a.source != null) {
            i.sourceName = a.source.name;
            i.kicadVersion = a.source.kicadVersion;
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
        byte[] bytes = bytes(e.artId);
        if (bytes == null) {
            return null;
        }
        try {
            a = BoardArt.parse(bytes);
        }
        catch (IllegalArgumentException x) {
            Logger.warn("board art {} cannot be read: {}", e.artId, x.getMessage());
            return null;
        }
        parsed.put(e.artId, a);
        return a;
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
            return new PadTypeResolver(db.currentPadTypes(), db.packageMappings());
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
