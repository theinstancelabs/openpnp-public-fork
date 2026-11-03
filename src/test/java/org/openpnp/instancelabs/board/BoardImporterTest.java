package org.openpnp.instancelabs.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.kicad.ImportProposal;
import org.openpnp.instancelabs.kicad.ImportWarning;
import org.openpnp.instancelabs.kicad.KicadPcb;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;

/**
 * The import's own rules (design §3): names and slugs, what is refused before the file is read, the error kinds of a
 * file that cannot be read, the attach match, the version and sentence texts. The small synthetic boards here are used
 * by the API test too.
 */
public class BoardImporterTest {
    /** KiCad 8 (the file format of ftp). */
    public static final int K8 = 20240108;

    // ---- synthetic boards (also BoardImportSimTest) -----------------------------------------------------------------

    public static byte[] board(String items) {
        return ("(kicad_pcb (version " + K8 + ") (generator \"pcbnew\") (generator_version \"8.0\")\n" + items
                + "\n)\n").getBytes(StandardCharsets.UTF_8);
    }

    public static String outline(double w, double h) {
        return String.format(Locale.ROOT, "(gr_rect (start 0 0) (end %s %s) (layer \"Edge.Cuts\") (stroke (width 0.1)))\n",
                w, h);
    }

    /** A footprint {@code lib:name} at (x, y) in KiCad's frame (Y down) with a Reference and a Value. */
    public static String fp(String lib, String name, String ref, double x, double y, double angle, String value,
            String body) {
        return String.format(Locale.ROOT, "(footprint \"%s:%s\" (layer \"F.Cu\") (at %s %s %s)\n"
                + "  (property \"Reference\" \"%s\" (at 0 -1 0) (layer \"F.SilkS\"))\n"
                + "  (property \"Value\" \"%s\" (at 0 1 0) (layer \"F.Fab\"))\n  %s)\n", lib, name, x, y, angle,
                escape(ref), escape(value), body);
    }

    /** An SMD pad with paste. */
    public static String pad(String name, double x, double y, double w, double h) {
        return String.format(Locale.ROOT, "(pad \"%s\" smd roundrect (at %s %s) (size %s %s) (roundrect_rratio 0.25) "
                + "(layers \"F.Cu\" \"F.Paste\" \"F.Mask\"))\n", name, x, y, w, h);
    }

    /** A two-pad part of footprint lib:name; padW sets the variant (other pads, other package). */
    public static String part2(String lib, String name, String ref, double x, double y, String value, double padW) {
        return fp(lib, name, ref, x, y, 0, value, pad("1", -0.8, 0, padW, 0.95) + pad("2", 0.8, 0, padW, 0.95));
    }

    /** A 1 mm round fiducial (library Fiducial) with a 2 mm mask opening. */
    public static String fid(String ref, double x, double y) {
        return fp("Fiducial", "Fiducial_1mm_Mask2mm", ref, x, y, 0, "Fiducial",
                "(pad \"1\" smd circle (at 0 0) (size 1 1) (solder_mask_margin 0.5) (layers \"F.Cu\" \"F.Mask\"))");
    }

    /** KiCad's string escapes for a value written into a synthetic file. */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            if (c == '\n') {
                sb.append("\\n");
            }
            else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---- names and slugs ---------------------------------------------------------------------------------------------

    @Test
    public void slugsComeFromTheNameOnly() {
        assertEquals("ftp", BoardStore.slug("ftp"));
        assertEquals("ftp-test-board", BoardStore.slug("FTP test board"));
        assertEquals("cafe-v2", BoardStore.slug("  Café   v2!! "));
        assertEquals("a-b", BoardStore.slug("../a/../b"), "no path survives");
        String long60 = "abcdefghij-abcdefghij-abcdefghij-abcdefghij-abcdefghij-abcd";
        assertTrue(BoardStore.slug(long60).length() <= BoardStore.SLUG_MAX);
        assertFalse(BoardStore.slug(long60).endsWith("-"));
        String cjk = BoardStore.slug("板子");
        assertTrue(cjk.matches("board-[0-9a-f]{8}"), cjk);
        assertEquals(cjk, BoardStore.slug("板子"), "the same name, the same slug");
    }

    @Test
    public void boardNamesAreCleanedAndChecked() {
        assertEquals("FTP test board", BoardStore.cleanName("  FTP\ttest\u0007 board "));
        assertNull(BoardStore.cleanName("   "));
        assertNull(BoardStore.cleanName("--- !!"));
        assertNull(BoardStore.cleanName(null));
        assertEquals(BoardStore.NAME_MAX, BoardStore.cleanName("x".repeat(200)).length());
    }

    @Test
    public void theStoreKeepsEveryPathInsideItsRoot() throws Exception {
        BoardStore store = new BoardStore(Files.createTempDirectory("board-store").toFile());
        String root = store.root().getCanonicalPath();
        assertTrue(store.boardFile("ftp").getCanonicalPath().startsWith(root + File.separator));
        assertThrows(java.io.IOException.class, () -> store.dir(".."));
        assertThrows(java.io.IOException.class, () -> store.dir("a/b"));
        assertNull(store.existing("ftp"));
        File st = store.staging();
        assertTrue(st.isDirectory() && st.getName().startsWith(".staging-"));
        BoardStore.deleteTree(st);
        assertFalse(st.exists());
    }

    // ---- refused before or while reading -----------------------------------------------------------------------------

    @Test
    public void emptyAndJsonBodiesAreRefusedBeforeReading() {
        BoardImporter.ImportException e = assertThrows(BoardImporter.ImportException.class,
                () -> BoardImporter.checkBody(new byte[0]));
        assertEquals(400, e.status);
        assertEquals("The upload is empty.", e.getMessage());
        e = assertThrows(BoardImporter.ImportException.class,
                () -> BoardImporter.checkBody(" \n {\"file\":1}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Send the file itself, not JSON.", e.getMessage());
        e = assertThrows(BoardImporter.ImportException.class,
                () -> BoardImporter.checkBody(new byte[BoardImporter.MAX_UPLOAD + 1]));
        assertEquals(413, e.status);
    }

    @Test
    public void filesThatCannotBeReadSayWhy() {
        assertCode("not-kicad", "This is a KiCad footprint", "(footprint \"R\" (layer \"F.Cu\"))");
        assertCode("not-kicad", "This is a KiCad schematic", "(kicad_sch (version 20231120))");
        assertCode("not-kicad", "Not a KiCad board file (it starts with", "%FSLAX46Y46*%\nG04 gerber*\n");
        assertCode("kicad-syntax", "Line 3, column 1: the file ends before the list opened at line 2", "(kicad_pcb (version 20240108)\n (footprint \"a:b\" (at 1 2)\n");
        StringBuilder deep = new StringBuilder("(kicad_pcb (version 20240108) ");
        for (int i = 0; i < 70; i++) {
            deep.append("(a ");
        }
        for (int i = 0; i < 70; i++) {
            deep.append(')');
        }
        assertCode("too-complex", "The board file is too complex to read here: nested deeper than 64", deep + ")");
    }

    static void assertCode(String code, String start, String body) {
        BoardImporter.ImportException e = assertThrows(BoardImporter.ImportException.class,
                () -> BoardImporter.parse(body.getBytes(StandardCharsets.UTF_8), "x.kicad_pcb", false));
        assertEquals(400, e.status, e.getMessage());
        assertEquals(code, e.code, e.getMessage());
        assertTrue(e.getMessage().startsWith(start), e.getMessage());
    }

    @Test
    public void theParsedFileKnowsItsSourceAndTheFileNameIsCleaned() throws Exception {
        byte[] b = board(outline(20, 10) + part2("Resistor_SMD", "R_0603_1608Metric", "R1", 5, 5, "10k", 0.8));
        BoardImporter.Parsed p = BoardImporter.parse(b, "my\u0007board.kicad_pcb", false);
        assertEquals("myboard.kicad_pcb", p.fileName);
        assertEquals("myboard.kicad_pcb", p.art.art.source.name);
        assertEquals(b.length, p.art.art.source.bytes.longValue());
        assertEquals(BoardArt.sha256Hex(b), p.art.art.source.sha256);
        assertEquals(1, p.proposal.counts.placements);
        assertEquals("myboard", BoardImporter.suggestedName(p.fileName));
        assertEquals("board", BoardImporter.suggestedName("!!.kicad_pcb"));
    }

    // ---- attach ------------------------------------------------------------------------------------------------------

    @Test
    public void ftpMatchesTheBenchBoardFileExceptTheHandMovedFid1() throws Exception {
        try (org.openpnp.instancelabs.dispense.SimMachine sim = new org.openpnp.instancelabs.dispense.SimMachine(false)) {
            Board xml = ArtFixtures.ftpBoardXml(sim.configDir);
            BoardImporter.Parsed p = BoardImporter.parse(ArtFixtures.bytes(ArtFixtures.FTP), "ftp.kicad_pcb", false);
            BoardImporter.Listed l = BoardImporter.listed(xml);
            assertEquals(83, l.placements.size());
            BoardImporter.Match m = BoardImporter.match(p.proposal, l);
            assertTrue(m.ok, m.why);
            assertEquals(0.3136, m.maxOffsetMm, 1e-3);
            assertEquals(1, m.offsets.size());
            assertEquals("FID1", m.offsets.get(0).id);

            l.placements.get("R7")[0] += 2.1;
            m = BoardImporter.match(p.proposal, l);
            assertFalse(m.ok);
            assertEquals("R7 moved 2.1 mm", m.why);
            l.placements.get("R7")[0] -= 2.1;
            l.placements.get("D3")[2] += 90;
            assertEquals("D3 is turned 90.0°", BoardImporter.match(p.proposal, l).why);
            l.placements.get("D3")[2] -= 90;
            l.placements.remove("D40");
            assertEquals("D40 is not in the board file", BoardImporter.match(p.proposal, l).why);
            l.placements.put("D40", new double[] {0, 0, 0, 0});
            l.placements.put("X1", new double[] {0, 0, 0, 0});
            assertFalse(BoardImporter.match(p.proposal, l).ok);
        }
    }

    // ---- texts -------------------------------------------------------------------------------------------------------

    @Test
    public void kicadMajorVersions() {
        KicadPcb p = new KicadPcb();
        int[][] cases = {{20171130, 5}, {20211014, 6}, {20221018, 7}, {20240108, 8}, {20241229, 9}, {20260206, 10},
                {20260301, 10}};
        for (int[] c : cases) {
            p.version = c[0];
            assertEquals(c[1], BoardImporter.kicadMajor(p), "version " + c[0]);
        }
        p.generatorVersion = "9.0";
        assertEquals(9, BoardImporter.kicadMajor(p), "generator_version wins");
        p.generatorVersion = "99999999999.0";
        assertEquals(10, BoardImporter.kicadMajor(p), "a number no KiCad has: the file format date");
    }

    @Test
    public void padTypeSentences() {
        assertEquals("Chosen because the package name contains “0603”.",
                BoardImporter.ruleSentence("pattern", "0603", "R_0603_1608Metric~2"));
        assertEquals("Mapped to this package by hand in Profile › Pad types.",
                BoardImporter.ruleSentence("package-map", null, "X"));
        assertTrue(BoardImporter.ruleSentence("centre-pad", "QFN", "X").startsWith("Centre pad of a QFN/DFN"));
        assertEquals("No pad type matches the package WSON-8: it gets no paste until one is added.",
                BoardImporter.ruleSentence("none", null, "WSON-8"));
    }

    @Test
    public void warningsStayCappedAndInGroupOrder() {
        ImportProposal p = new ImportProposal();
        for (int i = 0; i < ImportProposal.MAX_WARNINGS; i++) {
            p.warn(ImportWarning.of("PAD_NAME_EMPTY", null, "note " + i));
        }
        List<ImportWarning> more = new ArrayList<>();
        more.add(ImportWarning.of("NO_PAD_TYPE", null, "blocks"));
        List<ImportWarning> l = BoardImporter.allWarnings(p, more);
        assertEquals("NO_PAD_TYPE", l.get(0).code, "what blocks dispensing is never pushed out");
        assertEquals("WARNINGS_TRUNCATED", l.get(l.size() - 1).code);
        assertEquals(ImportProposal.MAX_WARNINGS + 1, l.size());
        assertEquals(ImportProposal.MAX_WARNINGS, p.warnings.size(), "the proposal is left as it is");
    }

    // ---- a preview without the engine --------------------------------------------------------------------------------

    @Test
    public void previewWithoutPadTypesBlocksEveryPart() throws Exception {
        try (org.openpnp.instancelabs.dispense.SimMachine sim = new org.openpnp.instancelabs.dispense.SimMachine(false)) {
            byte[] b = board(outline(20, 10) + part2("Resistor_SMD", "R_0603_1608Metric", "R1", 5, 5, "10k", 0.8)
                    + part2("Resistor_SMD", "R_0603_1608Metric", "R2", 10, 5, "10k", 0.8) + fid("FID1", 2, 2)
                    + fid("FID2", 18, 8));
            BoardImporter imp = new BoardImporter(Configuration.get(), null);
            java.util.Map<String, Object> pv = imp.preview(b, "two.kicad_pcb", false);
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> pk = (List<java.util.Map<String, Object>>) pv.get("packages");
            assertEquals(2, pk.size());
            assertEquals("no-pad-type", pk.get(0).get("outcome"));
            assertEquals("not-dispensed", pk.get(1).get("outcome"), "the fiducial package");
            @SuppressWarnings("unchecked")
            List<ImportWarning> w = (List<ImportWarning>) pv.get("warnings");
            assertEquals("NO_PAD_TYPE", w.get(0).code);
            assertEquals("R1, R2 (4 pads) get no paste until their package has a pad type. Save the board, then add "
                    + "one: New pad type for R_0603_1608Metric.", w.get(0).text);
            assertEquals("two", pv.get("suggestedName"));
            assertNull(pv.get("exists"));
            assertEquals(1, BoardImporter.PIPELINE.availablePermits(), "the pipeline is free again");
        }
    }
}
