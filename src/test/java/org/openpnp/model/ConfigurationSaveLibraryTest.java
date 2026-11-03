package org.openpnp.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Instance Labs: Configuration.saveLibraryFiles writes packages.xml, parts.xml and boards.xml (in
 * that order, with backups), never machine.xml or a board file, opens no dialog, and refuses before
 * any file changes when a file would not read back with the same ids.
 */
public class ConfigurationSaveLibraryTest {
    File dir;
    Configuration config;

    @BeforeEach
    public void setup() throws Exception {
        dir = Files.createTempDirectory("save-library").toFile();
        Configuration.initialize(dir);
        Configuration.get().load(); // writes the default files
        config = Configuration.get();
    }

    String read(String name) throws IOException {
        return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
    }

    /** Every file under the configuration directory with its bytes. */
    Map<String, byte[]> snapshot() throws IOException {
        Map<String, byte[]> m = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir.toPath())) {
            for (Path p : (Iterable<Path>) s::iterator) {
                m.put(dir.toPath().relativize(p).toString(), Files.isDirectory(p) ? new byte[0] : Files.readAllBytes(p));
            }
        }
        return m;
    }

    /** A new package with a pad, a part of it, and a dirty board file using the part. */
    Board addLibraryContent() throws Exception {
        Package pkg = new Package("R_0603_1608Metric~2");
        Footprint.Pad pad = new Footprint.Pad();
        pad.setName("1");
        pad.setX(-0.825);
        pad.setWidth(0.8);
        pad.setHeight(0.95);
        pad.setRoundness(50);
        pkg.getFootprint().addPad(pad);
        config.addPackage(pkg);
        Part part = new Part("R_0603_1608Metric~2-R_Small");
        part.setPackage(pkg);
        config.addPart(part);
        Board board = config.getBoard(new File(dir, "imported.board.xml")); // writes an empty board file
        Placement p = new Placement("R1");
        p.setPart(part);
        p.setLocation(new Location(LengthUnit.Millimeters, 37.5, 42.5, 0, 90));
        board.addPlacement(p);
        config.saveBoard(board);
        p.setEnabled(false);
        board.setDirty(true); // OpenPnP's save() would ask about it in a dialog
        return board;
    }

    @Test
    public void writesTheThreeFilesNeverMachineXmlOrBoardFiles() throws Exception {
        Board board = addLibraryContent();
        byte[] machine = Files.readAllBytes(new File(dir, "machine.xml").toPath());
        byte[] boardFile = Files.readAllBytes(board.getFile().toPath());
        String packagesBefore = read("packages.xml");
        String partsBefore = read("parts.xml");
        String boardsBefore = read("boards.xml");
        assertFalse(packagesBefore.contains("R_0603_1608Metric~2"));

        config.saveLibraryFiles(); // headless: a dialog would throw HeadlessException

        assertTrue(read("packages.xml").contains("id=\"R_0603_1608Metric~2\""));
        assertTrue(read("parts.xml").contains("id=\"R_0603_1608Metric~2-R_Small\""));
        assertTrue(read("boards.xml").contains(board.getFile().getPath()));
        assertArrayEquals(machine, Files.readAllBytes(new File(dir, "machine.xml").toPath()));
        assertArrayEquals(boardFile, Files.readAllBytes(board.getFile().toPath()), "board files are never written");
        assertTrue(board.isDirty(), "no save-board question was asked");

        File[] backups = new File(dir, "backups").listFiles();
        assertNotNull(backups);
        assertEquals(1, backups.length);
        assertEquals(packagesBefore, new String(Files.readAllBytes(new File(backups[0], "packages.xml").toPath()),
                StandardCharsets.UTF_8));
        assertEquals(partsBefore, new String(Files.readAllBytes(new File(backups[0], "parts.xml").toPath()),
                StandardCharsets.UTF_8));
        assertEquals(boardsBefore, new String(Files.readAllBytes(new File(backups[0], "boards.xml").toPath()),
                StandardCharsets.UTF_8));
        assertFalse(new File(backups[0], "machine.xml").exists());
        for (String name : dir.list()) {
            assertFalse(name.endsWith(".tmp"), name);
        }
    }

    /** packages.xml first, then parts.xml, then boards.xml: shown by making each later file unwritable. */
    @Test
    public void writesInTheOrderPackagesPartsBoards() throws Exception {
        addLibraryContent();
        String boardsBefore = read("boards.xml");
        blockWithADirectory("parts.xml");
        Exception e = assertThrows(Exception.class, () -> config.saveLibraryFiles());
        assertTrue(e.getMessage().contains("parts.xml") && e.getMessage().contains("already saved: packages.xml"),
                e.getMessage());
        assertTrue(read("packages.xml").contains("R_0603_1608Metric~2"));
        assertEquals(boardsBefore, read("boards.xml"));
        assertFalse(new File(dir, "parts.xml.tmp").exists(), "the temp file of the failed move is removed");

        unblock("parts.xml");
        blockWithADirectory("boards.xml");
        e = assertThrows(Exception.class, () -> config.saveLibraryFiles());
        assertTrue(e.getMessage().contains("already saved: packages.xml, parts.xml"), e.getMessage());
        assertTrue(read("parts.xml").contains("R_0603_1608Metric~2-R_Small"));
    }

    void blockWithADirectory(String name) throws IOException {
        File f = new File(dir, name);
        Files.delete(f.toPath());
        assertTrue(new File(f, "keep").mkdirs());
    }

    void unblock(String name) throws IOException {
        File f = new File(dir, name);
        Files.delete(new File(f, "keep").toPath());
        Files.delete(f.toPath());
    }

    @Test
    public void anIdThatWouldNotReadBackChangesNoFile() throws Exception {
        addLibraryContent();
        Map<String, byte[]> before = snapshot();
        Package bad = new Package("BAD\u0007ID");
        config.addPackage(bad);
        Exception e = assertThrows(Exception.class, () -> config.saveLibraryFiles());
        assertTrue(e.getMessage().startsWith("packages.xml would not read back"), e.getMessage());
        assertTrue(e.getMessage().endsWith("nothing was saved"), e.getMessage());
        assertSnapshotEquals(before, snapshot());
        config.removePackage(bad);

        // a line feed is written as is and reads back as a space: another id, so refused too
        Part odd = new Part("ODD\nPART");
        odd.setPackage(config.getPackage("R_0603_1608Metric~2"));
        config.addPart(odd);
        e = assertThrows(Exception.class, () -> config.saveLibraryFiles());
        assertTrue(e.getMessage().startsWith("parts.xml would read back other ids"), e.getMessage());
        assertTrue(e.getMessage().contains("\"ODD\\u000APART\" reads back as \"ODD PART\""), e.getMessage());
        assertSnapshotEquals(before, snapshot());
        config.removePart(odd);

        // a part without a package cannot be written at all
        Part orphan = new Part("ORPHAN");
        config.addPart(orphan);
        e = assertThrows(Exception.class, () -> config.saveLibraryFiles());
        assertTrue(e.getMessage().startsWith("Error while saving parts.xml"), e.getMessage());
        assertSnapshotEquals(before, snapshot());
        config.removePart(orphan);

        // not only ids: a control character anywhere in a file (here a pad name) fails the read too
        Footprint.Pad pad = config.getPackage("R_0603_1608Metric~2").getFootprint().getPads().get(0);
        pad.setName("1\u0007");
        e = assertThrows(Exception.class, () -> config.saveLibraryFiles());
        assertTrue(e.getMessage().startsWith("packages.xml would not read back"), e.getMessage());
        assertSnapshotEquals(before, snapshot());
        pad.setName("1");

        config.saveLibraryFiles();
        assertTrue(read("packages.xml").contains("R_0603_1608Metric~2"));
    }

    static void assertSnapshotEquals(Map<String, byte[]> a, Map<String, byte[]> b) {
        assertEquals(a.keySet(), b.keySet());
        for (String k : a.keySet()) {
            assertArrayEquals(a.get(k), b.get(k), k);
        }
    }

    @Test
    public void reloadResolvesPartsAndPackages() throws Exception {
        Board board = addLibraryContent();
        config.saveBoard(board);
        config.saveLibraryFiles();

        Configuration.initialize(dir);
        Configuration.get().load();
        Configuration again = Configuration.get();
        Package pkg = again.getPackage("R_0603_1608Metric~2");
        assertNotNull(pkg);
        assertEquals(1, pkg.getFootprint().getPads().size());
        assertEquals(50, pkg.getFootprint().getPads().get(0).getRoundness(), 0);
        Part part = again.getPart("R_0603_1608Metric~2-R_Small");
        assertNotNull(part);
        assertSame(pkg, part.getPackage());
        Board reloaded = null;
        for (Board b : again.getBoards()) {
            if (b.getFile().getName().equals("imported.board.xml")) {
                reloaded = b;
            }
        }
        assertNotNull(reloaded, "the board is in OpenPnP's list after a restart");
        assertEquals("R1", reloaded.getPlacement(0).getId());
        assertSame(part, reloaded.getPlacement(0).getPart());
    }
}
