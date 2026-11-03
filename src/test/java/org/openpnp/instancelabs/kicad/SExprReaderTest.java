package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** The defensive S-expression reader (design §2.2, §6.2). */
public class SExprReaderTest {
    static SExpr read(String s) throws SExprException {
        return SExprReader.read(s.getBytes(StandardCharsets.UTF_8), Deadline.none());
    }

    static SExprException fails(String s) {
        return assertThrows(SExprException.class, () -> read(s));
    }

    @Test
    public void atomsStringsAndEscapes() throws Exception {
        SExpr r = read("(kicad_pcb (version 20240108) (gr_text \"a \\\"b\\\" c\\\\d\\ne\" (at 1.5 -2)) x)");
        assertEquals("kicad_pcb", r.head);
        assertEquals(3, r.size());
        assertEquals(20240108, r.child("version").num(0, 0), 0);
        SExpr t = r.child("gr_text");
        assertEquals("a \"b\" c\\d\ne", t.atom(0));
        assertTrue(t.isQuoted(0));
        assertEquals(1.5, t.child("at").num(0, 0), 0);
        assertEquals(-2, t.child("at").num(1, 0), 0);
        assertEquals("x", r.atom(2));
        assertNull(r.atom(0));
        assertEquals(7, t.child("at").num(5, 7), 0);
    }

    @Test
    public void flagsAndYes() throws Exception {
        SExpr r = read("(kicad_pcb (a hide \"locked\") (b (hide yes)) (c (hide no)) (d (hide)))");
        assertTrue(r.child("a").flag("hide"));
        assertFalse(r.child("a").flag("locked"), "a quoted string is not a bare flag");
        assertTrue(r.child("a").yes("hide"));
        assertTrue(r.child("b").yes("hide"));
        assertFalse(r.child("c").yes("hide"));
        assertTrue(r.child("d").yes("hide"));
        assertEquals("yes", r.child("b").str("hide"));
    }

    @Test
    public void unknownEscapeIsKeptAsWritten() throws Exception {
        assertEquals("a\\qb\tc", read("(kicad_pcb (t \"a\\qb\\tc\"))").child("t").atom(0));
    }

    @Test
    public void crlfAndBomAndLineNumbers() throws Exception {
        byte[] body = "(kicad_pcb\r\n  (version 20211014)\r\n  (net 0 \"\")\r\n)\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[body.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, bom, 3, body.length);
        SExpr r = SExprReader.read(bom, Deadline.none());
        assertEquals(2, r.child("version").line);
        assertEquals(3, r.child("net").line);
        assertEquals("", r.child("net").atom(1));
    }

    @Test
    public void unquotedKicad5Tokens() throws Exception {
        SExpr r = read("(kicad_pcb (module Resistor_SMD:R_0603 (layer F.Cu) (fp_text reference R1 (at 0 -1.4)) "
                + "(pad 1 smd rect (at -0.8 0) (size 0.8 0.95) (layers F.Cu F.Paste F.Mask))))");
        SExpr m = r.child("module");
        assertEquals("Resistor_SMD:R_0603", m.atom(0));
        assertEquals("F.Cu", m.str("layer"));
        assertEquals("R1", m.child("fp_text").atom(1));
        assertEquals(Arrays.asList("F.Cu", "F.Paste", "F.Mask"), m.child("pad").child("layers").items);
    }

    @Test
    public void skippedSubtreesCostNoNodes() throws Exception {
        StringBuilder blob = new StringBuilder();
        while (blob.length() < 2 * 1024 * 1024) {
            blob.append("QUJDRA==");
        }
        String s = "(kicad_pcb (version 20260206) (embedded_files (file (name \"f.ttf\") (data |" + blob + "|)))"
                + " (footprint \"X:Y\" (model \"/Users/someone/x.step\" (offset (xyz 0 0 0))) (layer \"F.Cu\")))";
        SExprReader.Limits lim = new SExprReader.Limits();
        lim.maxItems = 12; // root, its head, version + 2, footprint + 2 atoms, layer + 2 … fits only without the skipped
        SExpr r = SExprReader.read(s.getBytes(StandardCharsets.UTF_8), lim, Deadline.none());
        assertNull(r.child("embedded_files"));
        assertNull(r.child("footprint").child("model"));
        assertEquals("F.Cu", r.child("footprint").str("layer"));
    }

    @Test
    public void depthLimit() throws Exception {
        StringBuilder ok = new StringBuilder("(kicad_pcb ");
        StringBuilder deep = new StringBuilder("(kicad_pcb ");
        for (int i = 1; i < 64; i++) {
            ok.append("(a ");
        }
        for (int i = 1; i < 65; i++) {
            deep.append("(a ");
        }
        for (int i = 1; i < 64; i++) {
            ok.append(')');
        }
        for (int i = 1; i < 65; i++) {
            deep.append(')');
        }
        read(ok.append(')').toString()); // 64 levels
        SExprException e = fails(deep.append(')').toString());
        assertEquals(SExprException.LIMIT, e.kind);
        assertTrue(e.getMessage().contains("nested deeper than 64 levels at line 1"), e.getMessage());
    }

    @Test
    public void itemLimit() throws Exception {
        // the root list and its head are 2 items
        read(atoms(1_500_000 - 2));
        SExprException e = fails(atoms(1_500_000 - 1));
        assertEquals(SExprException.LIMIT, e.kind);
        assertTrue(e.getMessage().contains("more than 1,500,000 items"), e.getMessage());
    }

    private static String atoms(int n) {
        StringBuilder sb = new StringBuilder(n * 2 + 16).append("(kicad_pcb");
        for (int i = 0; i < n; i++) {
            sb.append(" a");
        }
        return sb.append(')').toString();
    }

    @Test
    public void atomLimit() throws Exception {
        char[] big = new char[1024 * 1024];
        Arrays.fill(big, 'x');
        String mib = new String(big);
        assertEquals(mib.length(), read("(kicad_pcb (t \"" + mib + "\"))").child("t").atom(0).length());
        SExprException e = fails("(kicad_pcb\n(t \"" + mib + "x\"))");
        assertEquals(SExprException.LIMIT, e.kind);
        assertTrue(e.getMessage().contains("a value longer than 1 MiB at line 2"), e.getMessage());
        assertEquals(SExprException.LIMIT, fails("(kicad_pcb (t " + mib + "x))").kind);
    }

    @Test
    public void sizeLimit() {
        SExprReader.Limits lim = new SExprReader.Limits();
        byte[] b = new byte[lim.maxBytes + 1];
        SExprException e = assertThrows(SExprException.class, () -> SExprReader.read(b, lim, Deadline.none()));
        assertEquals(SExprException.LIMIT, e.kind);
        assertTrue(e.getMessage().contains("larger than 12 MB"), e.getMessage());
        assertTrue(e.getMessage().endsWith(SExprException.UNFILL_HINT));
    }

    @Test
    public void oneDeadlineForTheWholePipeline() throws Exception {
        AtomicLong now = new AtomicLong();
        Deadline d = new Deadline(Deadline.DEFAULT_MS, now::get);
        byte[] board = ("(kicad_pcb (version 20240108) (gr_rect (start 0 0) (end 10 10) (layer \"Edge.Cuts\"))"
                + " (footprint \"R:R\" (layer \"F.Cu\") (at 5 5) (pad \"1\" smd rect (at 0 0) (size 1 1)"
                + " (layers \"F.Cu\" \"F.Paste\"))))").getBytes(StandardCharsets.UTF_8);
        KicadPcb pcb = KicadParser.read(board, d); // a fast parse
        BoardFrame frame = BoardFrame.of(pcb, d);
        now.set(20_001); // the budget runs out in a later step
        SExprException e = assertThrows(SExprException.class, () -> new ImportRules().propose(pcb, frame, d));
        assertEquals(SExprException.LIMIT, e.kind);
        assertTrue(e.getMessage().contains("took longer than 20 s"), e.getMessage());
        assertThrows(SExprException.class, d::check); // and every step after it
    }

    /**
     * Hostile files put their work where no per-footprint check sees it: thousands of unnamed pads in one footprint
     * (each looks for its host pad) or thousands of Edge.Cuts lines meeting at one point (each chaining step searches
     * them all). The deadline is checked inside those loops too: a clock that ticks once per check runs out there.
     */
    @Test
    public void deadlineInsideOneFootprintAndInsideTheOutline() throws Exception {
        StringBuilder pads = new StringBuilder("(kicad_pcb (version 20240108)\n"
                + "(gr_rect (start 0 0) (end 50 50) (layer \"Edge.Cuts\"))\n"
                + "(footprint \"L:X\" (layer \"F.Cu\") (at 10 10) (property \"Reference\" \"U1\")\n");
        for (int i = 0; i < 3000; i++) {
            pads.append("(pad \"\" smd rect (at 0 0) (size 1 1) (layers \"F.Paste\"))\n");
        }
        byte[] onePart = pads.append("))").toString().getBytes(StandardCharsets.UTF_8);
        AtomicLong ticks = new AtomicLong();
        Deadline d = new Deadline(1000, ticks::getAndIncrement);
        KicadPcb pcb = KicadParser.read(onePart, d);
        BoardFrame frame = BoardFrame.of(pcb, d);
        SExprException e = assertThrows(SExprException.class, () -> new ImportRules().propose(pcb, frame, d));
        assertTrue(e.getMessage().contains("took longer than 1 s"), e.getMessage());
        Deadline none = Deadline.none();
        KicadPcb again = KicadParser.read(onePart, none);
        assertEquals(3000, new ImportRules().propose(again, BoardFrame.of(again, none), none).counts.pastePads);

        StringBuilder edges = new StringBuilder("(kicad_pcb (version 20240108)\n");
        for (int i = 0; i < 3000; i++) {
            edges.append("(gr_line (start 0 0) (end 1 0) (layer \"Edge.Cuts\"))\n");
        }
        KicadPcb star = KicadParser.read(edges.append(')').toString().getBytes(StandardCharsets.UTF_8), none);
        AtomicLong t2 = new AtomicLong();
        assertThrows(SExprException.class, () -> BoardFrame.of(star, new Deadline(1000, t2::getAndIncrement)));

        double[] zigzag = new double[2 * 5000]; // Douglas–Peucker peels one point per split: quadratic
        for (int k = 0; k < 5000; k++) {
            zigzag[2 * k] = k;
            zigzag[2 * k + 1] = (k % 2 == 0 ? 1 : -1) * k;
        }
        AtomicLong t3 = new AtomicLong();
        Deadline five = new Deadline(5, t3::getAndIncrement);
        assertThrows(SExprException.class, () -> KGeom.simplify(zigzag, 0.01, false, five));
        assertEquals(5000, KGeom.simplify(zigzag, 0.01, false).length / 2);
    }

    @Test
    public void deadlineDuringReading() {
        AtomicLong now = new AtomicLong();
        Deadline d = new Deadline(1000, now::get);
        now.set(5000);
        byte[] b = atoms(200_000).getBytes(StandardCharsets.UTF_8);
        SExprException e = assertThrows(SExprException.class, () -> SExprReader.read(b, d));
        assertTrue(e.getMessage().contains("took longer than 1 s"), e.getMessage());
    }

    @Test
    public void syntaxErrorsCarryLineAndColumn() {
        SExprException e = fails("(kicad_pcb\n  (version 20240108)\n  (net 0 \"x\"\n");
        assertEquals(SExprException.SYNTAX, e.kind);
        assertEquals(4, e.line);
        assertEquals(1, e.column);
        assertTrue(e.getMessage().startsWith("Line 4, column 1: the file ends before the list opened at line 3"),
                e.getMessage());
        assertTrue(e.getMessage().endsWith("The file may be cut short or not saved by KiCad."));
        SExprException q = fails("(kicad_pcb\n (t \"open");
        assertEquals(SExprException.SYNTAX, q.kind);
        assertEquals(2, q.line);
        assertEquals(5, q.column);
        assertTrue(q.getMessage().contains("a quoted text is not closed"));
    }

    @Test
    public void notKicadTexts() {
        assertEquals("This is a KiCad footprint (.kicad_mod), not a board: choose the board's .kicad_pcb file.",
                fails("(footprint \"R_0603\" (layer \"F.Cu\"))").getMessage());
        assertEquals("This is a KiCad footprint (.kicad_mod), not a board: choose the board's .kicad_pcb file.",
                fails("(module R_0603 (layer F.Cu))").getMessage());
        assertEquals("This is a KiCad schematic, not a board: choose the board's .kicad_pcb file.",
                fails("(kicad_sch (version 20231120))").getMessage());
        SExprException g = fails("%TF.GenerationSoftware,KiCad,Pcbnew,10.0.5*%\nG04 x*");
        assertEquals(SExprException.NOT_KICAD, g.kind);
        assertEquals("Not a KiCad board file (it starts with \"%TF.GenerationSoftwa\"): choose a .kicad_pcb file "
                + "(KiCad 5 to 10).", g.getMessage());
        assertEquals(SExprException.NOT_KICAD, fails("   ").kind);
    }
}
