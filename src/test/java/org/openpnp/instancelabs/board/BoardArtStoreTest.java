package org.openpnp.instancelabs.board;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.kicad.KGeom;
import org.openpnp.instancelabs.kicad.KicadPcb.Footprint;
import org.openpnp.instancelabs.kicad.KicadPcb.Pad;
import org.openpnp.model.Board;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Placement;

/** The board art store and its index (design §3.7, §4.4, §6.3). */
public class BoardArtStoreTest {
    /** A KiCad drawing saved as an import would save it: art file + index entry. */
    static final class Saved {
        final ArtFixtures.Drawn drawn;
        final String id;
        final File boardFile;

        Saved(ArtFixtures.Drawn drawn, String id, File boardFile) {
            this.drawn = drawn;
            this.id = id;
            this.boardFile = boardFile;
        }

        Board board() {
            return ArtFixtures.boardOf(drawn.proposal, boardFile);
        }
    }

    static final long AT = 1793000000000L;

    static Saved save(BoardArtStore store, String fixture, String slug) throws Exception {
        return save(store, fixture, slug, null, AT);
    }

    /**
     * An import (attachedTo null) or an attach to that board file, saved as BoardImporter saves it; when the slug has a
     * drawing, a replace: that drawing moves to history/.
     */
    static Saved save(BoardArtStore store, String fixture, String slug, File attachedTo, long atMs) throws Exception {
        Saved s = write(store, fixture, slug, attachedTo, atMs);
        store.invalidate();
        return s;
    }

    /** The files and the index entry of {@link #save}, without the store's invalidate that ends a save. */
    static Saved write(BoardArtStore store, String fixture, String slug, File attachedTo, long atMs) throws Exception {
        ArtFixtures.Drawn d = ArtFixtures.draw(fixture);
        BoardArt art = d.art();
        art.source.name = fixture.replace(".gz", "");
        art.imported = BoardArt.Import.from(d.proposal, d.result.warnings);
        art.imported.how = attachedTo == null ? "import" : "attach";
        art.imported.name = slug;
        art.imported.slug = slug;
        art.imported.atMs = atMs;
        art.imported.boardDigest = BoardArtIndex.digest(d.proposal);
        art.imported.board = attachedTo == null ? null : attachedTo.getCanonicalPath();
        art.measure();
        byte[] bytes = art.toBytes();
        String id = BoardArt.idOf(bytes);
        File dir = new File(store.index().root(), slug);
        File current = new File(dir, slug + ".art.json");
        Map<String, String> arts = new LinkedHashMap<>();
        arts.put(id, BoardStore.artPath(slug));
        if (current.isFile()) {
            String old = BoardArt.idOf(Files.readAllBytes(current.toPath()));
            Files.createDirectories(new File(dir, BoardStore.HISTORY).toPath());
            Files.move(current.toPath(), new File(store.index().root(), BoardStore.historyArtPath(slug, old)).toPath());
            arts.put(old, BoardStore.historyArtPath(slug, old));
        }
        Files.createDirectories(dir.toPath());
        Files.write(current.toPath(), bytes);
        File boardFile = attachedTo == null ? new File(dir, slug + ".board.xml") : attachedTo;
        store.index().put(art.imported.boardDigest, new BoardArtIndex.Entry(id, slug, art.imported.how,
                boardFile.getAbsolutePath(), atMs), arts);
        return new Saved(d, id, boardFile);
    }

    static BoardArtStore freshStore() throws IOException {
        return BoardArtStore.forDir(Files.createTempDirectory("board-art-store").toFile());
    }

    @Test
    public void theIdIsTheSha256PrefixOfTheBytes() throws Exception {
        BoardArtStore store = freshStore();
        Saved s = save(store, ArtFixtures.FTP, "ftp");
        byte[] b = store.bytes(s.id);
        assertNotNull(b);
        assertEquals(s.id, BoardArt.idOf(b));
        assertTrue(BoardArt.sha256Hex(b).startsWith(s.id));
        assertTrue(s.id.matches("[0-9a-f]{16}"));
        assertNull(store.bytes("0123456789abcdef"));
        assertNull(store.bytes("../../etc/passwd"));
        assertNull(store.bytes(null));
        // the index is a file of its own, written in full
        String index = new String(Files.readAllBytes(new File(store.index().root(), "index.json").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(index.contains("\"" + s.id + "\": \"ftp/ftp.art.json\""), index);
    }

    @Test
    public void boardsFindTheirDrawingByGeometry() throws Exception {
        BoardArtStore store = freshStore();
        Saved s = save(store, ArtFixtures.FTP, "ftp");
        Board b = s.board();
        assertEquals(BoardArtIndex.digest(s.drawn.proposal), BoardArtIndex.digest(b));
        BoardArtStore.ArtRef r = store.refFor(b, null);
        assertEquals(s.id, r.id);
        assertEquals("kicad", r.kind);
        assertNull(r.note);

        // a copy with the same placements under another path (uinav's 00-ftp.board.xml): the same drawing
        Board copy = ArtFixtures.boardOf(s.drawn.proposal, new File("/tmp/uinav-boards/00-ftp.board.xml"));
        assertEquals(s.id, store.refFor(copy, null).id);

        // toggling a part on or off keeps it
        b.getPlacements().get(0).setEnabled(false);
        assertEquals(s.id, store.refFor(b, null).id);

        // a moved placement (FID1, as the bench's hand edit did) at the same path: drawn from the file, with a note
        Placement fid1 = b.getPlacements().stream().filter(p -> p.getId().equals("FID1")).findFirst().get();
        fid1.setLocation(new Location(LengthUnit.Millimeters, 62.4465812, 4.69103014, 0, 0));
        BoardArtStore.ArtRef moved = store.refFor(b, null);
        assertEquals("fallback", moved.kind);
        assertNotEquals(s.id, moved.id);
        assertEquals(BoardArtStore.NOTE_CHANGED, moved.note);
        assertNotNull(store.bytes(moved.id), "fallback art is served by its id");
        assertEquals("fallback", BoardArt.parse(store.bytes(moved.id)).kind);
        assertEquals(moved.id, store.refFor(b, null).id, "made once");

        // the same moved board under another path: no note (that file never had a drawing)
        copy.getPlacements().stream().filter(p -> p.getId().equals("FID1")).findFirst().get()
                .setLocation(fid1.getLocation());
        assertNull(store.refFor(copy, null).note);
        assertTrue(store.keepOuts(b).isEmpty());
        assertTrue(store.fiducials(b).isEmpty());
        assertTrue(store.outlineLoops(b).isEmpty());
        assertNull(store.imported(b));
    }

    @Test
    public void importRecord() throws Exception {
        BoardArtStore store = freshStore();
        Saved s = save(store, ArtFixtures.NANO, "nano");
        BoardArtStore.Imported i = store.imported(s.board());
        assertEquals("nano", i.slug);
        assertEquals("import", i.how);
        assertEquals(1793000000000L, i.atMs);
        assertEquals("NanoV3.3.kicad_pcb", i.sourceName);
        assertEquals(20211014, (int) i.kicadVersion);
        assertEquals("outline", i.origin);
        assertNull(i.board);
        assertEquals("copper", i.centrePads, "BR-91 (a): the upload's centre pads");
    }

    /** BR-88: a board lists its own upload only while it has that upload's geometry (else its drawing's upload). */
    @Test
    public void theOwnUploadCountsAtItsGeometryOnly() throws Exception {
        BoardArtStore store = freshStore();
        Saved ftp = save(store, ArtFixtures.FTP, "ftp");
        Saved nano = save(store, ArtFixtures.NANO, "nano");
        assertEquals("ftp", store.imported(ftp.board()).slug);
        Board b = ArtFixtures.boardOf(nano.drawn.proposal, ftp.boardFile); // ftp's file, the nano's placements
        assertEquals(nano.id, store.refFor(b, null).id);
        assertEquals("nano", store.imported(b).slug);
    }

    /**
     * BR-88: two current attaches name one board file: the newer one is its upload (saved first here, so the drawing's
     * upload is the older one). The index lists drawings by id, random here (the temp path is in them): it runs until
     * the newer one has come both first and last.
     */
    @Test
    public void theNewerOfTwoAttachesCounts() throws Exception {
        boolean first = false;
        boolean last = false;
        for (int run = 0; run < 30 && !(first && last); run++) {
            BoardArtStore store = freshStore();
            File bench = new File(store.index().root().getParentFile(), "ftp.board.xml");
            Saved newer = save(store, ArtFixtures.FTP, "a", bench, AT + 2);
            Saved older = save(store, ArtFixtures.FTP, "b", bench, AT + 1);
            assertEquals("a", store.imported(newer.board()).slug);
            first |= newer.id.compareTo(older.id) < 0;
            last |= newer.id.compareTo(older.id) > 0;
        }
        assertTrue(first && last, "the newer drawing came both first and last by id");
    }

    /**
     * BR-88: attach "s" to F, then replace "s" by attaching it to G, F's byte copy: F's own drawing went to history/,
     * so F lists its drawing's upload (s at t2), not s at t1.
     */
    @Test
    public void anAttachDisplacedToHistoryNoLongerCounts() throws Exception {
        BoardArtStore store = freshStore();
        File dir = store.index().root().getParentFile();
        Saved f = save(store, ArtFixtures.FTP, "s", new File(dir, "f.board.xml"), AT + 1);
        assertEquals(AT + 1, store.imported(f.board()).atMs);
        Saved g = save(store, ArtFixtures.FTP, "s", new File(dir, "g.board.xml"), AT + 2);
        BoardArtStore.Imported i = store.imported(f.board());
        assertEquals(AT + 2, i.atMs);
        assertEquals(g.boardFile.getCanonicalPath(), i.board);
    }

    /**
     * BR-88: once a save is announced (status.boardImport.savedAtMs) the board list gives its upload, also before the
     * save's finally invalidates the store: a page that polls the status and reads the list at once sees the replace.
     */
    @Test
    public void aSaveIsListedOnceItIsAnnounced() throws Exception {
        BoardArtStore store = freshStore();
        Saved ftp = save(store, ArtFixtures.FTP, "ftp", null, AT + 1);
        assertEquals(AT + 1, store.imported(ftp.board()).atMs);
        write(store, ArtFixtures.FTP, "ftp", null, AT + 2); // a replace's index change, inside the save
        store.saved(AT + 2);
        assertEquals(AT + 2, store.imported(ftp.board()).atMs);
    }

    /** BR-88: an import's board file linked by hand to a file elsewhere still lists its own upload. */
    @Test
    public void aLinkedBoardFileKeepsItsOwnUpload() throws Exception {
        BoardArtStore store = freshStore();
        Saved ftp = save(store, ArtFixtures.FTP, "ftp");
        save(store, ArtFixtures.FTP, "ftp2"); // saved later: the drawing of this geometry is its upload
        File target = new File(store.index().root().getParentFile(), "ftp.board.xml");
        Files.createFile(target.toPath());
        Files.createSymbolicLink(ftp.boardFile.toPath(), target.toPath());
        assertEquals("ftp", store.imported(ftp.board()).slug);
    }

    @Test
    public void historyFilesAreServedAndBadFilesAreNot() throws Exception {
        BoardArtStore store = freshStore();
        Saved s = save(store, ArtFixtures.SECONDARY, "fid");
        byte[] bytes = store.bytes(s.id);
        // a replace moves the drawing to history/: older plans keep it
        File history = new File(store.index().root(), "fid/history");
        Files.createDirectories(history.toPath());
        Files.move(new File(store.index().root(), "fid/fid.art.json").toPath(),
                new File(history, s.id + ".art.json").toPath());
        assertNull(store.bytes(s.id), "the index still points at the old place");
        store.index().put(null, null, Collections.singletonMap(s.id, "fid/history/" + s.id + ".art.json"));
        assertArrayEquals(bytes, store.bytes(s.id));
        // a file changed by hand no longer matches its id
        Files.write(new File(history, s.id + ".art.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        assertNull(store.bytes(s.id));
        // paths outside the root are refused
        assertThrows(IOException.class, () -> store.index().put(null, null,
                Collections.singletonMap("0123456789abcdef", "../../outside.art.json")));
    }

    @Test
    public void anIndexEditedOnDiskIsReadOnInvalidate() throws Exception {
        BoardArtStore store = freshStore();
        Saved s = save(store, ArtFixtures.SECONDARY, "fid");
        assertEquals(s.id, store.refFor(s.board(), null).id);
        File index = new File(store.index().root(), "index.json");
        String text = new String(Files.readAllBytes(index.toPath()), StandardCharsets.UTF_8);
        Files.write(index.toPath(), text.replace(s.id, "0000000000000000").getBytes(StandardCharsets.UTF_8));
        assertEquals(s.id, store.refFor(s.board(), null).id, "read once, kept");
        store.invalidate();
        // the entry now names an id its file does not hash to: drawn from the board file
        assertEquals("fallback", store.refFor(s.board(), null).kind);
        assertNull(store.bytes("0000000000000000"));
        Files.write(index.toPath(), "not json".getBytes(StandardCharsets.UTF_8));
        store.invalidate();
        assertEquals("fallback", store.refFor(s.board(), null).kind, "an unreadable index is an empty one");
    }

    @Test
    public void fallbackArtIsCachedAndEvicted() throws Exception {
        BoardArtStore store = freshStore();
        File dir = store.index().root();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < BoardArtStore.FALLBACK_CACHE + 1; i++) {
            Board b = new Board(new File(dir, "b" + i + ".board.xml"));
            b.setDimensions(new Location(LengthUnit.Millimeters, 10 + i, 10, 0, 0));
            ids.add(store.refFor(b, null).id);
        }
        assertEquals(BoardArtStore.FALLBACK_CACHE + 1, new java.util.HashSet<>(ids).size());
        assertNull(store.bytes(ids.get(0)), "the oldest of 65 was evicted");
        assertNotNull(store.bytes(ids.get(1)));
        assertNotNull(store.bytes(ids.get(BoardArtStore.FALLBACK_CACHE)));
    }

    @Test
    public void oneStorePerConfigurationDirectory() throws Exception {
        File a = Files.createTempDirectory("board-art-a").toFile();
        File b = Files.createTempDirectory("board-art-b").toFile();
        BoardArtStore sa = BoardArtStore.forDir(a);
        assertSame(sa, BoardArtStore.forDir(a));
        assertSame(sa.index(), BoardArtIndex.forDir(a));
        assertEquals(new File(a, "instancelabs/boards"), sa.index().root());
        BoardArtStore sb = BoardArtStore.forDir(b);
        assertNotEquals(sa, sb);
        assertEquals(new File(b, "instancelabs/boards"), sb.index().root());
    }

    @Test
    public void ftpKeepOuts() throws Exception {
        BoardArtStore store = freshStore();
        Saved s = save(store, ArtFixtures.FTP, "ftp");
        List<KeepOut> k = store.keepOuts(s.board());
        List<KeepOut> holes = new ArrayList<>();
        List<KeepOut> edge = new ArrayList<>();
        for (KeepOut c : k) {
            if (c.r == KeepOut.EDGE_R) {
                edge.add(c);
            }
            else if (c.r == KeepOut.FILL_R) {
                throw new AssertionError("ftp's outline is its box: no fill circles, got " + c);
            }
            else {
                holes.add(c);
            }
        }
        assertEquals(6, holes.size(), "H1–H4 and J1's two pins");
        int mounting = 0;
        for (KeepOut h : holes) {
            mounting += h.r == 2.8 ? 1 : 0; // a 5.6 mm pad around the 3.2 mm drill
        }
        assertEquals(4, mounting);
        assertTrue(edge.size() >= 300, edge.size() + " edge circles");
        for (int i = 0; i < edge.size(); i++) {
            KeepOut a = edge.get(i);
            KeepOut b = edge.get((i + 1) % edge.size());
            assertTrue(Math.hypot(a.x - b.x, a.y - b.y) <= 1.0 + 1e-3, a + " to " + b);
            assertTrue(a.x == 0 || a.x == 75 || a.y == 0 || a.y == 75, "on the outline: " + a);
        }
        assertEquals(1, store.outlineLoops(s.board()).size());
        assertArrayEquals(new double[] {0, 0, 75, 75}, box(store.outlineLoops(s.board()).get(0)), 0);
        List<BoardArt.FidArt> f = store.fiducials(s.board());
        assertEquals(3, f.size());
        assertEquals(1.0, f.get(0).copperMm, 0);
        assertEquals(3.0, f.get(0).maskMm, 0);
    }

    @Test
    public void nanoKeepOutsHoldTheBottomPinsAndTheCutouts() throws Exception {
        BoardArtStore store = freshStore();
        Saved s = save(store, ArtFixtures.NANO, "nano");
        List<KeepOut> k = store.keepOuts(s.board());
        ArtFixtures.Drawn d = s.drawn;
        int pins = 0;
        for (Footprint fp : d.pcb.footprints) {
            if (fp.top || !(fp.ref.equals("J1") || fp.ref.equals("J2"))) {
                continue;
            }
            for (Pad p : fp.pads) {
                double[] at = d.frame.localToBoard(fp, p.x, p.y);
                boolean found = false;
                for (KeepOut c : k) {
                    found |= Math.hypot(c.x - at[0], c.y - at[1]) < 0.01 && c.r >= p.drillW / 2;
                }
                assertTrue(found, fp.ref + "." + p.name);
                pins++;
            }
        }
        assertEquals(30, pins);
        List<double[]> loops = store.outlineLoops(s.board());
        assertEquals(5, loops.size(), "the outline and 4 cut-outs");
        for (double[] cut : loops.subList(1, 5)) {
            double[] b = box(cut);
            double cx = (b[0] + b[2]) / 2;
            double cy = (b[1] + b[3]) / 2;
            double r = (b[2] - b[0]) / 2;
            int around = 0;
            for (KeepOut c : k) {
                around += c.r == KeepOut.EDGE_R && Math.abs(Math.hypot(c.x - cx, c.y - cy) - r) < 0.02 ? 1 : 0;
            }
            assertTrue(around >= Math.ceil(2 * Math.PI * r) - 1, "a chain around the cut-out at " + cx + ", " + cy);
        }
        assertEquals(2, store.fiducials(s.board()).size());
    }

    @Test
    public void moboFillCirclesLieOutsideTheOutlineOrInCutouts() throws Exception {
        BoardArtStore store = freshStore();
        Saved s = save(store, ArtFixtures.MOBO, "mobo");
        List<KeepOut> k = store.keepOuts(s.board());
        List<double[]> loops = store.outlineLoops(s.board());
        assertEquals(14, loops.size(), "the outline and 13 cut-outs");
        double[] outline = loops.get(0);
        int corners = 0;
        int holes = 0;
        for (KeepOut c : k) {
            assertTrue(c.x >= -1 && c.y >= -1 && c.x <= 51.57 + 1 && c.y <= 89 + 1, c.toString());
            if (c.r != KeepOut.FILL_R) {
                continue;
            }
            assertEquals(0.5, c.x - Math.floor(c.x), 1e-9, "on the 1 mm grid: " + c);
            if (!KGeom.pointInPolygon(c.x, c.y, outline)) {
                corners++;
                continue;
            }
            boolean inCutout = false;
            for (double[] cut : loops.subList(1, loops.size())) {
                inCutout |= KGeom.pointInPolygon(c.x, c.y, cut);
            }
            assertTrue(inCutout, "outside the outline or inside a cut-out: " + c);
            holes++;
        }
        // the outline's notch and rounded corners; and inside the cut-outs (§4.4): the seven 3.2 mm mounting holes
        // and one 0.7 mm hole whose loop holds the cell centre (25.5, 9.5)
        assertTrue(corners > 100, corners + " cells between the box and the outline");
        assertEquals(60, holes);
        for (BoardArt.FidArt f : store.fiducials(s.board())) {
            assertEquals(2.0, f.maskMm, 0, f.name);
        }
    }

    static double[] box(double[] pts) {
        double[] b = KGeom.emptyBox();
        KGeom.extendPts(b, pts);
        return b;
    }
}
