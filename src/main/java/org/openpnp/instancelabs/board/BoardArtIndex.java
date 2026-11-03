package org.openpnp.instancelabs.board;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.openpnp.instancelabs.kicad.ImportProposal;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Placement;
import org.pmw.tinylog.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * {@code <configDir>/instancelabs/boards/index.json} (design §3.7): which drawing belongs to a board, found by the
 * board's geometry digest (not its path, so a copy under another name keeps its drawing), and where each drawing's
 * file is (the current one, or one a replace moved to {@code history/}). One per configuration directory,
 * synchronized; written atomically (a temp file moved into place).
 */
public final class BoardArtIndex {
    /** Under the configuration directory. */
    public static final String DIR = "instancelabs/boards";
    public static final String FILE = "index.json";
    static final Pattern ART_ID = Pattern.compile("[0-9a-f]{16}");

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting()
            .create();
    private static BoardArtIndex current;

    /** A board's drawing: which art, which import, where the board file was then. */
    public static final class Entry {
        public String artId;
        public String slug;
        public String how;                    // import | attach
        public String file;                   // absolute path of the board file
        public long atMs;

        public Entry() {
        }

        public Entry(String artId, String slug, String how, String file, long atMs) {
            this.artId = artId;
            this.slug = slug;
            this.how = how;
            this.file = file;
            this.atMs = atMs;
        }
    }

    static final class Data {
        int version = 1;
        TreeMap<String, Entry> boards = new TreeMap<>();
        /** art id → path relative to the root ("ftp/ftp.art.json", "ftp/history/<id>.art.json"). */
        TreeMap<String, String> arts = new TreeMap<>();
    }

    private final File root;
    private Data data;

    private BoardArtIndex(File configDir) {
        this.root = new File(configDir, DIR);
    }

    /** The index of OpenPnP's configuration directory (a new one when the directory changed). */
    public static synchronized BoardArtIndex get() {
        Configuration c = Configuration.get();
        if (c == null) {
            throw new IllegalStateException("OpenPnP's configuration is not loaded");
        }
        return forDir(c.getConfigurationDirectory());
    }

    public static synchronized BoardArtIndex forDir(File configDir) {
        File root = new File(configDir, DIR);
        if (current == null || !current.root.equals(root)) {
            current = new BoardArtIndex(configDir);
        }
        return current;
    }

    /** {@code <configDir>/instancelabs/boards}. */
    public File root() {
        return root;
    }

    public synchronized Entry board(String digest) {
        return data().boards.get(digest);
    }

    /** Every entry whose board file is this path (canonical), digest → entry. */
    public synchronized Map<String, Entry> boardsAt(File file) {
        Map<String, Entry> m = new TreeMap<>();
        if (file == null) {
            return m;
        }
        String path = canonical(file);
        for (Map.Entry<String, Entry> e : data().boards.entrySet()) {
            if (e.getValue().file != null && path.equals(canonical(new File(e.getValue().file)))) {
                m.put(e.getKey(), e.getValue());
            }
        }
        return m;
    }

    /** The file of an art id when the index knows it and it lies inside the root; null otherwise. */
    public synchronized File artFile(String artId) {
        if (artId == null || !ART_ID.matcher(artId).matches()) {
            return null;
        }
        String rel = data().arts.get(artId);
        return rel == null ? null : inside(rel);
    }

    /**
     * Records a board's drawing and the art paths that changed (the new art, and on a replace where the displaced
     * one went), in one atomic write.
     *
     * @param digest the board's geometry digest, or null to record only art paths
     * @param artPaths art id → path relative to the root
     */
    public synchronized void put(String digest, Entry entry, Map<String, String> artPaths) throws IOException {
        Data d = copy(data());
        if (artPaths != null) {
            for (Map.Entry<String, String> e : artPaths.entrySet()) {
                if (!ART_ID.matcher(e.getKey()).matches() || inside(e.getValue()) == null) {
                    throw new IOException("not an art path inside " + root + ": " + e.getKey() + " → "
                            + e.getValue());
                }
                d.arts.put(e.getKey(), e.getValue());
            }
        }
        if (digest != null) {
            d.boards.put(digest, entry);
        }
        write(d);
        data = d;
    }

    /** Reads index.json again on the next call (after a save, or a change by hand). */
    public synchronized void reload() {
        data = null;
    }

    // ---- file ----------------------------------------------------------------------------------------------------

    private Data data() {
        if (data == null) {
            data = read();
        }
        return data;
    }

    private Data read() {
        File f = new File(root, FILE);
        if (!f.isFile()) {
            return new Data();
        }
        try {
            Data d = GSON.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), Data.class);
            if (d == null) {
                return new Data();
            }
            d.boards = d.boards == null ? new TreeMap<>() : d.boards;
            d.arts = d.arts == null ? new TreeMap<>() : d.arts;
            d.boards.values().removeIf(e -> e == null || e.artId == null || !ART_ID.matcher(e.artId).matches());
            return d;
        }
        catch (IOException | JsonParseException e) {
            Logger.warn("{} cannot be read, board drawings fall back to the board files: {}", f, e.getMessage());
            return new Data();
        }
    }

    private void write(Data d) throws IOException {
        Files.createDirectories(root.toPath());
        File f = new File(root, FILE);
        File tmp = File.createTempFile(FILE, ".tmp", root);
        try {
            Files.write(tmp.toPath(), GSON.toJson(d).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }

    private static Data copy(Data d) {
        Data c = new Data();
        c.boards.putAll(d.boards);
        c.arts.putAll(d.arts);
        return c;
    }

    /** A relative path resolved inside the root (an index edited by hand cannot point elsewhere), else null. */
    private File inside(String rel) {
        if (rel == null || rel.isEmpty() || !rel.endsWith(".art.json")) {
            return null;
        }
        File f = new File(root, rel);
        String r = canonical(root);
        String p = canonical(f);
        return p.startsWith(r + File.separator) ? f : null;
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        }
        catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    // ---- the geometry digest -------------------------------------------------------------------------------------

    /**
     * The geometry digest of a board (§3.7): the first 16 hex digits of the SHA-256 of its dimensions and, per
     * placement sorted by id, id|type|side|x|y|rotation in mm at 4 decimals. Not the part and not enabled: a copy
     * of the board, or toggling a part on or off, keeps its drawing; a moved, added or removed placement does not.
     */
    public static String digest(Board b) {
        Location d = b.getDimensions() == null ? null : b.getDimensions().convertToUnits(LengthUnit.Millimeters);
        List<String> rows = new ArrayList<>();
        for (Placement p : b.getPlacements()) {
            Location l = p.getLocation().convertToUnits(LengthUnit.Millimeters);
            rows.add(row(p.getId(), p.getType() == null ? "" : p.getType().name(),
                    p.getSide() == null ? "" : p.getSide().name(), l.getX(), l.getY(), l.getRotation()));
        }
        return digest(d == null ? 0 : d.getX(), d == null ? 0 : d.getY(), rows);
    }

    /**
     * The digest of the board an import of this proposal writes (top side, the proposal's placements and size): the
     * same value as {@link #digest(Board)} of that board.
     */
    public static String digest(ImportProposal p) {
        List<String> rows = new ArrayList<>();
        for (ImportProposal.PlacementP x : p.placements) {
            rows.add(row(x.id, x.type, "Top", x.x, x.y, x.rotation));
        }
        return digest(p.widthMm, p.heightMm, rows);
    }

    private static String digest(double w, double h, List<String> rows) {
        Collections.sort(rows);
        StringBuilder sb = new StringBuilder();
        sb.append(f4(w)).append('|').append(f4(h)).append('\n');
        for (String r : rows) {
            sb.append(r).append('\n');
        }
        return BoardArt.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }

    private static String row(String id, String type, String side, double x, double y, double rot) {
        return id + '|' + type + '|' + side + '|' + f4(x) + '|' + f4(y) + '|' + f4(rot);
    }

    private static String f4(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        return s.equals("-0.0000") ? "0.0000" : s;
    }
}
