package org.openpnp.instancelabs.board;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.text.Normalizer;

import org.openpnp.instancelabs.kicad.KText;
import org.pmw.tinylog.Logger;

/**
 * Where imported boards live (design §3.7): {@code <configDir>/instancelabs/boards/<slug>/} with
 * {@code <slug>.board.xml} (an import only), {@code <slug>.art.json} (the current drawing),
 * {@code <slug>.kicad_pcb} (the upload, unchanged) and {@code history/} (what a replace displaced, so older plans keep
 * their drawing). The slug comes from the operator's name only: nothing from the file is ever used as a path.
 */
public final class BoardStore {
    public static final int SLUG_MAX = 40;
    public static final int NAME_MAX = 60;
    public static final String HISTORY = "history";
    static final String STAGING_PREFIX = ".staging-";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final File root;

    public BoardStore(File configDir) {
        this.root = new File(configDir, BoardArtIndex.DIR);
    }

    /** {@code <configDir>/instancelabs/boards}. */
    public File root() {
        return root;
    }

    /** What a slug already holds (the board or drawing a save under that name would replace). */
    public static final class Existing {
        public String slug;
        /** The board file: the import's own, or the board file a drawing was attached to; null if unknown. */
        public String file;
        public String name;
        public Long importedAtMs;
        /** The id of the current drawing, or null when there is none. */
        public String artId;
        /** import | attach, or null when only a board file is there. */
        public String how;
    }

    // ---- names -----------------------------------------------------------------------------------------------------

    /**
     * The operator's board name: cleaned (§2.2), 1 to {@value #NAME_MAX} characters with at least one letter or
     * digit; null when it is not acceptable.
     */
    public static String cleanName(String raw) {
        String n = KText.clean(raw, NAME_MAX);
        for (int i = 0; i < n.length();) {
            int cp = n.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                return n;
            }
            i += Character.charCount(cp);
        }
        return null;
    }

    /**
     * The directory name of a board name: NFKD, ASCII letters and digits lower-cased, every other run a '-', trimmed,
     * at most {@value #SLUG_MAX} characters. A name without any ASCII letter or digit gets "board-" and a digest of the
     * name, so the slug is never empty.
     */
    public static String slug(String name) {
        String n = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFKD);
        StringBuilder sb = new StringBuilder();
        boolean dash = false;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c < 128 && Character.isLetterOrDigit(c)) {
                if (dash && sb.length() > 0) {
                    sb.append('-');
                }
                dash = false;
                sb.append(Character.toLowerCase(c));
            }
            else if (Character.getType(c) != Character.NON_SPACING_MARK) {
                dash = true; // accents fall away (é → e), anything else separates
            }
        }
        String s = sb.length() > SLUG_MAX ? sb.substring(0, SLUG_MAX) : sb.toString();
        while (s.endsWith("-")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) {
            s = "board-" + BoardArt.sha256Hex(n.getBytes(StandardCharsets.UTF_8)).substring(0, 8);
        }
        return s;
    }

    // ---- paths -----------------------------------------------------------------------------------------------------

    /** The directory of a slug, checked to lie inside the root (on the canonical path). */
    public File dir(String slug) throws IOException {
        if (slug == null || !slug.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IOException("not a board slug: " + slug);
        }
        File d = new File(root, slug);
        String r = root.getCanonicalPath();
        if (!d.getCanonicalPath().startsWith(r + File.separator)) {
            throw new IOException(d + " is not inside " + r);
        }
        return d;
    }

    public File boardFile(String slug) throws IOException {
        return new File(dir(slug), slug + ".board.xml");
    }

    public File artFile(String slug) throws IOException {
        return new File(dir(slug), slug + ".art.json");
    }

    public File kicadFile(String slug) throws IOException {
        return new File(dir(slug), slug + ".kicad_pcb");
    }

    public File historyDir(String slug) throws IOException {
        return new File(dir(slug), HISTORY);
    }

    /** {@code <slug>/<slug>.art.json} relative to the root (the index's form). */
    public static String artPath(String slug) {
        return slug + "/" + slug + ".art.json";
    }

    /** {@code <slug>/history/<artId>.art.json} relative to the root. */
    public static String historyArtPath(String slug, String artId) {
        return slug + "/" + HISTORY + "/" + artId + ".art.json";
    }

    /** A new, empty staging directory under the root ({@code .staging-<random>}): files are written here first. */
    public File staging() throws IOException {
        Files.createDirectories(root.toPath());
        for (int i = 0; i < 10; i++) {
            File d = new File(root, STAGING_PREFIX + Long.toHexString(RANDOM.nextLong() & Long.MAX_VALUE));
            if (d.mkdir()) {
                return d;
            }
        }
        throw new IOException("cannot make a staging directory in " + root);
    }

    // ---- what a slug holds -----------------------------------------------------------------------------------------

    /** What the slug holds now, or null when its directory has neither a board file nor a drawing. */
    public Existing existing(String slug) throws IOException {
        File board = boardFile(slug);
        File art = artFile(slug);
        if (!board.isFile() && !art.isFile()) {
            return null;
        }
        Existing e = new Existing();
        e.slug = slug;
        e.file = board.isFile() ? board.getCanonicalPath() : null;
        if (art.isFile()) {
            try {
                byte[] bytes = Files.readAllBytes(art.toPath());
                e.artId = BoardArt.idOf(bytes);
                BoardArt a = BoardArt.parse(bytes);
                if (a.imported != null) {
                    e.name = a.imported.name;
                    e.importedAtMs = a.imported.atMs;
                    e.how = a.imported.how;
                    if (a.imported.board != null) {
                        e.file = a.imported.board;
                    }
                }
            }
            catch (IOException | IllegalArgumentException x) {
                Logger.warn("{} cannot be read: {}", art, x.getMessage());
            }
        }
        return e;
    }

    /** Deletes a directory tree (staging); logs what it cannot delete. */
    public static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        if (!f.delete()) {
            Logger.warn("cannot delete {}", f);
        }
    }
}
