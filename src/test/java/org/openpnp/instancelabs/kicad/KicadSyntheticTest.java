package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.kicad.ImportProposal.PackageP;
import org.openpnp.instancelabs.kicad.ImportProposal.PadP;
import org.openpnp.instancelabs.kicad.ImportProposal.PlacementP;
import org.openpnp.instancelabs.kicad.KicadPcb.Graphic;

/** Small boards written in the test for what no fixture has (design §6.2). */
public class KicadSyntheticTest {
    static final String OUTLINE = "(gr_rect (start 0 0) (end 50 50) (layer \"Edge.Cuts\") (stroke (width 0.1)))\n";
    static final String PASTE = "(layers \"F.Cu\" \"F.Paste\" \"F.Mask\")";

    static String fp(String ref, String name, double x, double y, double angle, String value, String body) {
        return String.format(Locale.ROOT, "(footprint \"Lib:%s\" (layer \"F.Cu\") (at %s %s %s)\n"
                + "  (property \"Reference\" \"%s\" (at 0 -1 0) (layer \"F.SilkS\"))\n"
                + "  (property \"Value\" \"%s\" (at 0 1 0) (layer \"F.Fab\"))\n  %s)\n", name, x, y, angle, ref, value,
                body);
    }

    static String pad(String name, String shape, double x, double y, double w, double h, String extra) {
        return String.format(Locale.ROOT, "(pad \"%s\" smd %s (at %s %s) (size %s %s) %s %s)\n", name, shape, x, y,
                w, h, PASTE, extra);
    }

    static PadP pad(ImportProposal p, String ref, String name) {
        PlacementP pl = p.placement(ref);
        assertNotNull(pl, ref);
        for (PadP pad : p.packages.get(pl.packageIndex).pads) {
            if (pad.name.equals(name)) {
                return pad;
            }
        }
        throw new AssertionError(ref + " has no pad " + name);
    }

    static List<String> codes(ImportProposal p) {
        List<String> l = new ArrayList<>();
        for (ImportWarning w : p.warnings) {
            l.add(w.code);
        }
        return l;
    }

    // ---- KiCad 5 ------------------------------------------------------------------------------------------------

    @Test
    public void kicad5Module() throws Exception {
        String board = "(host pcbnew 5.1.9)\n"
                + "(gr_line (start 0 0) (end 20 0) (layer Edge.Cuts) (width 0.1))\n"
                + "(gr_line (start 20 0) (end 20 10) (layer Edge.Cuts) (width 0.1))\n"
                // centre (10, 10), arc start (20, 10), +180°: clockwise on screen, so it bulges down to y = 20
                + "(gr_arc (start 10 10) (end 20 10) (angle 180) (layer Edge.Cuts) (width 0.1))\n"
                + "(gr_line (start 0 10) (end 0 0) (layer Edge.Cuts) (width 0.1))\n"
                + "(gr_circle (center 15 5) (end 15.5 5) (layer F.SilkS) (width 0))\n"
                + "(module Resistor_SMD:R_0603 (layer F.Cu) (tedit 5B301BBD) (at 5 5 90)\n"
                + "  (fp_text reference R1 (at 0 -1.43) (layer F.SilkS) hide (effects (font (size 1 1))))\n"
                + "  (fp_text value 10k (at 0 1.43) (layer F.Fab) (effects (font (size 1 1))))\n"
                + "  (fp_line (start -0.8 0.4) (end 0.8 0.4) (layer F.Fab) (width 0.1))\n"
                + "  (pad 1 smd rect (at -0.8 0 90) (size 0.8 0.95) (layers F.Cu F.Paste F.Mask))\n"
                + "  (pad 2 smd rect (at 0.8 0 90) (size 0.8 0.95) (layers F.Cu F.Paste F.Mask))\n"
                + "  (fp_poly (pts (xy 2 -0.5) (xy 3 -0.5) (xy 3 0.5) (xy 2 0.5)) (layer F.Paste) (width 0)))";
        Fixtures.Loaded l = Fixtures.board(20171130, board);
        assertTrue(l.pcb.legacy);
        assertEquals("pcbnew", l.pcb.generator);
        assertEquals("5.1.9", l.pcb.generatorVersion);
        assertTrue(codes(l.proposal).contains("KICAD5"));
        assertEquals(20.0, l.proposal.widthMm, 1e-9);
        assertEquals(20.0, l.proposal.heightMm, 1e-9, "the arc's lowest point (y 20) is in the box");
        assertEquals("closed", l.proposal.outline);
        PlacementP r1 = l.proposal.placement("R1");
        assertEquals(5.0, r1.x, 1e-9);
        assertEquals(15.0, r1.y, 1e-9);
        assertEquals(90.0, r1.rotation, 0);
        assertEquals("10k", r1.value);
        assertTrue(l.pcb.footprints.get(0).refHidden);
        assertEquals(3, r1.pads, "two pads and the paste polygon without a fill token");
        PadP poly = pad(l.proposal, "R1", "P~1");
        assertEquals(2.5, poly.x, 1e-9);
        assertEquals(0, poly.y, 1e-9);
        assertEquals(0, pad(l.proposal, "R1", "1").rotation, 1e-9);
        Graphic circle = l.pcb.graphics.stream().filter(g -> g.kind == Graphic.Kind.CIRCLE).findFirst().get();
        assertTrue(circle.filled, "a zero-width circle without a fill token is filled");
        Graphic edge = l.pcb.graphics.get(0);
        assertFalse(edge.filled);
    }

    // ---- versions, DNP --------------------------------------------------------------------------------------------

    @Test
    public void kicad7DnpAndNewerVersions() throws Exception {
        String board = OUTLINE + fp("U1", "SOT-23", 10, 10, 0, "X", "(attr smd dnp)\n"
                + "(fp_line (start -1 -1) (end 1 -1) (stroke (width 0.12) (type solid)) (layer \"F.SilkS\"))\n"
                + pad("1", "rect", -1, 0, 0.6, 0.6, "") + pad("2", "rect", 1, 0, 0.6, 0.6, ""));
        Fixtures.Loaded l = Fixtures.board(20221018, board);
        assertEquals(0.12, l.pcb.footprints.get(0).graphics.get(0).width, 1e-12);
        assertNull(l.proposal.placement("U1"), "do not populate: no paste by default");
        assertEquals(List.of("U1"), l.proposal.skipped.get("dnp"));
        assertTrue(codes(l.proposal).contains("DNP_SKIPPED"));
        assertFalse(codes(l.proposal).contains("KICAD_NEWER"));

        Fixtures.Loaded pasted = Fixtures.board(20221018, board, new ImportRules(true, true));
        assertFalse(pasted.proposal.placement("U1").enabled);
        assertTrue(codes(pasted.proposal).contains("DNP_STILL_PASTED"));
        assertTrue(pasted.proposal.pasteDnp);

        Fixtures.Loaded newer = Fixtures.board(20260207, OUTLINE);
        assertTrue(codes(newer.proposal).contains("KICAD_NEWER"));
        assertFalse(codes(Fixtures.board(20260206, OUTLINE).proposal).contains("KICAD_NEWER"));
    }

    @Test
    public void classificationExtensions() throws Exception {
        String board = OUTLINE
                + fp("R5", "R_0603", 10, 10, 0, "DNP", pad("1", "rect", -1, 0, 0.6, 0.6, ""))
                + fp("R5", "R_0603", 20, 10, 0, "1k", pad("1", "rect", -1, 0, 0.6, 0.6, ""))
                + fp("TP1", "TP", 30, 10, 0, "", "(attr exclude_from_pos_files) " + pad("1", "circle", 0, 0, 1, 1, ""))
                + fp("J3", "Conn", 40, 10, 0, "", "(attr through_hole) (pad \"1\" thru_hole circle (at 0 0) "
                        + "(size 1.7 1.7) (drill 1) (layers \"*.Cu\" \"*.Mask\" \"F.Paste\"))")
                + fp("FIDX", "Fiducial_0.5mm", 45, 45, 0, "", "(pad \"\" smd circle (at 0 0) (size 0.5 0.5) "
                        + "(layers \"F.Cu\" \"F.Mask\"))")
                + fp("M1", "Mark", 45, 40, 0, "", "(attr board_only)");
        ImportProposal p = Fixtures.board(20240108, board).proposal;
        assertEquals("R5", p.placements.get(0).id);
        assertEquals("R5~2", p.placements.get(1).id);
        assertTrue(codes(p).contains("DUPLICATE_REF"));
        assertTrue(codes(p).contains("VALUE_SAYS_DNP"));
        assertEquals("", p.placement("TP1").value);
        assertFalse(p.placement("TP1").enabled, "excluded from position files, still pasted");
        assertEquals("pasteOnly", p.placement("TP1").why);
        assertEquals(List.of("TP1"), p.skipped.get("pasteOnly"));
        assertTrue(codes(p).contains("THT_PASTE_SKIPPED"));
        assertEquals(List.of("J3", "M1"), p.skipped.get("artOnly"));
        assertEquals("through-hole", p.footprints.get(3).why);
        assertEquals("board only", p.footprints.get(5).why);
        assertEquals("Fiducial", p.placement("FIDX").type, "ref FID…");
        assertEquals(1, p.counts.fiducials);
        assertTrue(codes(p).contains("FIDUCIAL_COUNT"));
        assertEquals(3, p.counts.placements);
    }

    // ---- frame ----------------------------------------------------------------------------------------------------

    @Test
    public void noEdgeCutsUsesTheCourtyards() throws Exception {
        String crt = "(fp_rect (start -1 -1) (end 1 1) (stroke (width 0.05)) (fill no) (layer \"F.CrtYd\"))\n";
        String board = fp("R1", "R", 10, 10, 0, "", crt + pad("1", "rect", 0, 0, 0.5, 0.5, ""))
                + fp("R2", "R", 20, 15, 0, "", crt + pad("1", "rect", 0, 0, 0.5, 0.5, ""));
        Fixtures.Loaded l = Fixtures.board(20240108, board);
        assertEquals("estimated", l.proposal.origin);
        assertEquals("none", l.proposal.outline);
        assertEquals(12.0, l.proposal.widthMm, 1e-9);
        assertEquals(7.0, l.proposal.heightMm, 1e-9);
        assertEquals(1.0, l.proposal.placement("R1").x, 1e-9);
        assertEquals(6.0, l.proposal.placement("R1").y, 1e-9);
        assertTrue(codes(l.proposal).contains("NO_EDGE_CUTS"));
    }

    @Test
    public void openOutline() throws Exception {
        String board = "(gr_line (start 0 0) (end 30 0) (layer \"Edge.Cuts\") (stroke (width 0.1)))\n"
                + "(gr_line (start 30 0) (end 30 20) (layer \"Edge.Cuts\") (stroke (width 0.1)))\n"
                + "(gr_line (start 30 20) (end 0 20) (layer \"Edge.Cuts\") (stroke (width 0.1)))\n"
                + "(gr_line (start 0 20) (end 0 0.12) (layer \"Edge.Cuts\") (stroke (width 0.1)))\n";
        Fixtures.Loaded l = Fixtures.board(20240108, board);
        assertEquals("open", l.proposal.outline);
        assertEquals("edges", l.proposal.origin);
        assertEquals(30.0, l.proposal.widthMm, 1e-9);
        assertEquals(20.0, l.proposal.heightMm, 1e-9);
        ImportWarning w = l.proposal.warnings("OUTLINE_OPEN").get(0);
        assertTrue(w.text.contains("gap 0.12 mm"), w.text);
        assertEquals(1, l.frame.openChains.size());

        // with a gap in the outline, a mounting hole is a cut-out, not the outline
        String hole = "(gr_circle (center 5 5) (end 6.6 5) (layer \"Edge.Cuts\") (stroke (width 0.1)))\n";
        BoardFrame gap = Fixtures.board(20240108, board + hole).frame;
        assertNull(gap.outlineLoop);
        assertEquals(1, gap.cutouts.size());
        // a closed outline with a stray Edge.Cuts line: the outline loop stays the outline
        String stray = "(gr_line (start 10 10) (end 12 10) (layer \"Edge.Cuts\") (stroke (width 0.1)))\n";
        BoardFrame strayLine = Fixtures.board(20240108, OUTLINE + stray).frame;
        assertEquals("open", strayLine.outline);
        assertNotNull(strayLine.outlineLoop);
        assertEquals(0, strayLine.cutouts.size());
        assertEquals(50.0, strayLine.widthMm(), 1e-9);
    }

    @Test
    public void rotatedFootprintEdgeCuts() throws Exception {
        // a 4 × 4 mm cut-out drawn as fp_rect in a footprint turned 45°: a diamond, not the box of two corners
        String body = "(fp_rect (start -2 -2) (end 2 2) (stroke (width 0.1)) (fill no) (layer \"Edge.Cuts\"))";
        Fixtures.Loaded l = Fixtures.board(20240108, OUTLINE + fp("H1", "Slot", 25, 25, 45, "", body));
        assertEquals(1, l.frame.cutouts.size());
        double[] c = l.frame.cutouts.get(0);
        assertEquals(8, c.length);
        double r = 2 * Math.sqrt(2);
        double[] box = KGeom.emptyBox();
        KGeom.extendPts(box, c);
        assertEquals(25 - r, box[0], 1e-9);
        assertEquals(25 + r, box[2], 1e-9);
        assertEquals(50.0, l.proposal.widthMm, 1e-9, "the outline is the larger loop");
    }

    @Test
    public void hugeArcs() throws Exception {
        String board = "(gr_arc (start 2000 0) (mid 0 -2000) (end -2000 0) (layer \"Edge.Cuts\") (stroke (width 0.1)))\n"
                + "(gr_line (start -2000 0) (end 2000 0) (layer \"Edge.Cuts\") (stroke (width 0.1)))\n";
        Fixtures.Loaded l = Fixtures.board(20240108, board);
        assertEquals(4000, l.proposal.widthMm, 1e-6);
        assertEquals(2000, l.proposal.heightMm, 1e-6);
        assertTrue(l.frame.outlineLoop.length / 2 <= 182, "a half circle of 2,000 mm in ≤ 180 segments");
    }

    // ---- paste rules ----------------------------------------------------------------------------------------------

    @Test
    public void marginPrecedenceAndRatioPerAxis() throws Exception {
        String setup = "(setup (pad_to_paste_clearance -0.05) (pad_to_paste_clearance_ratio -0.1))\n";
        String board = setup + OUTLINE
                + fp("A1", "A", 10, 10, 0, "", pad("1", "rect", 0, 0, 1, 2, ""))
                + fp("B1", "B", 20, 10, 0, "", "(solder_paste_margin -0.02) "
                        + pad("1", "rect", 0, 0, 1, 2, "(solder_paste_margin -0.01)"))
                + fp("C1", "C", 30, 10, 0, "", "(solder_paste_margin -0.02) (solder_paste_ratio 0) "
                        + pad("1", "rect", 0, 0, 1, 2, ""));
        ImportProposal p = Fixtures.board(20240108, board).proposal;
        // board: m −0.05, ρ −0.1 → mx −0.15, my −0.25
        assertEquals(0.7, pad(p, "A1", "1").w, 1e-9);
        assertEquals(1.5, pad(p, "A1", "1").h, 1e-9);
        // pad margin −0.01 over the footprint's −0.02; ratio from the board
        assertEquals(1 - 0.02 - 0.2, pad(p, "B1", "1").w, 1e-9);
        assertEquals(2 - 0.02 - 0.4, pad(p, "B1", "1").h, 1e-9);
        // KiCad 8: the footprint's ratio 0 overrides the board's −0.1
        assertEquals(1 - 0.04, pad(p, "C1", "1").w, 1e-9);
        assertEquals(2 - 0.04, pad(p, "C1", "1").h, 1e-9);
        assertTrue(codes(p).contains("PASTE_ZERO_OVERRIDE"));
    }

    @Test
    public void zeroMarginBeforeKicad8MeansNotSet() throws Exception {
        String setup = "(setup (pad_to_paste_clearance -0.05))\n";
        String board = setup + OUTLINE + fp("A1", "A", 10, 10, 0, "", pad("1", "rect", 0, 0, 1, 2,
                "(solder_paste_margin 0)"));
        ImportProposal k7 = Fixtures.board(20221018, board).proposal;
        assertEquals(0.9, pad(k7, "A1", "1").w, 1e-9, "KiCad 7: 0 = not set, the board's −0.05 applies");
        ImportProposal k8 = Fixtures.board(20240108, board).proposal;
        assertEquals(1.0, pad(k8, "A1", "1").w, 1e-9, "KiCad 8: 0 overrides");
        assertTrue(codes(k7).contains("PASTE_ZERO_OVERRIDE"));
        assertTrue(codes(k8).contains("PASTE_ZERO_OVERRIDE"));
    }

    @Test
    public void shapes() throws Exception {
        double t = Math.toRadians(30);
        StringBuilder rot = new StringBuilder("(fp_poly (pts");
        double[][] c = {{-0.5, -0.25}, {0.5, -0.25}, {0.5, 0.25}, {-0.5, 0.25}};
        for (double[] q : c) { // a 1 × 0.5 rectangle turned 30° in the part frame, written Y down
            double x = 5 + q[0] * Math.cos(t) - q[1] * Math.sin(t);
            double y = q[0] * Math.sin(t) + q[1] * Math.cos(t);
            rot.append(String.format(Locale.ROOT, " (xy %.9f %.9f)", x, -y));
        }
        rot.append(") (stroke (width 0)) (fill yes) (layer \"F.Paste\"))\n");
        String body = pad("R+", "rect", 0, 0, 1, 2, "(solder_paste_margin 0.1)")
                + pad("R-", "rect", 0, 3, 1, 2, "(solder_paste_margin -0.1)")
                + pad("RR", "roundrect", 0, 6, 1, 2, "(roundrect_rratio 0.25) (solder_paste_margin -0.1)")
                + pad("RC", "roundrect", 0, 9, 1, 2, "(roundrect_rratio 0.5) (solder_paste_margin_ratio 0.2)")
                + pad("C", "circle", 3, 0, 1, 1, "(solder_paste_margin -0.1)")
                + pad("O", "oval", 3, 3, 1, 2, "")
                + pad("T", "trapezoid", 3, 6, 1, 1, "(rect_delta 0 0.4)")
                + pad("CH", "roundrect", 3, 9, 1, 1, "(roundrect_rratio 0) (chamfer_ratio 0.2) (chamfer top_left)")
                + pad("G", "rect", 6, 0, 0.1, 0.1, "(solder_paste_margin -0.05)")
                + "(pad \"CU\" smd custom (at 10 0 90) (size 0.5 0.5) " + PASTE
                + " (options (clearance outline) (anchor circle)) (primitives (gr_poly (pts (xy 1 -0.25) (xy 2 -0.25)"
                + " (xy 2 0.25) (xy 1 0.25)) (width 0.1) (fill yes))))\n"
                + rot
                + "(fp_poly (pts (xy 0 10) (xy 2 10) (xy 2 11) (xy 0 11.5)) (stroke (width 0)) (fill yes) "
                + "(layer \"F.Paste\"))\n";
        ImportProposal p = Fixtures.board(20240108, OUTLINE + fp("U1", "X", 25, 25, 0, "", body)).proposal;
        PadP rp = pad(p, "U1", "R+");
        assertEquals(1.2, rp.w, 1e-9);
        assertEquals(2.2, rp.h, 1e-9);
        assertEquals(200 * 0.1 / 1.2, rp.roundness, 1e-6, "growing a rect rounds it by the margin");
        PadP rm = pad(p, "U1", "R-");
        assertEquals(0.8, rm.w, 1e-9);
        assertEquals(0, rm.roundness, 0);
        assertEquals(-3, rm.y, 1e-9, "part frame is +Y up");
        PadP rr = pad(p, "U1", "RR");
        assertEquals(200 * 0.15 / 0.8, rr.roundness, 1e-6, "radius 0.25 shrinks to 0.15");
        PadP rc = pad(p, "U1", "RC");
        assertEquals(1.4, rc.w, 1e-9, "ratio per axis: 0.2 × 1 mm");
        assertEquals(2.8, rc.h, 1e-9, "ratio per axis: 0.2 × 2 mm");
        assertEquals(100, rc.roundness, 1e-9, "radius 0.5 + 0.2 on a 1.4 mm side: clamped to round ends");
        PadP circle = pad(p, "U1", "C");
        assertEquals(0.8, circle.w, 1e-9);
        assertEquals(0.8, circle.h, 1e-9);
        assertEquals(100, circle.roundness, 0);
        assertEquals(100, pad(p, "U1", "O").roundness, 0);
        PadP trap = pad(p, "U1", "T");
        assertEquals(1.4, trap.w, 1e-9);
        assertEquals(1.0, trap.h, 1e-9);
        assertEquals("trapezoid", trap.approx);
        PadP custom = pad(p, "U1", "CU");
        // pad frame box: anchor ±0.25, primitive 1..2 × ±0.25 grown by 0.05 → x −0.25..2.05, y ±0.3
        assertEquals(2.3, custom.w, 1e-9);
        assertEquals(0.6, custom.h, 1e-9);
        assertEquals(90, custom.rotation, 1e-9);
        assertEquals(10, custom.x, 1e-9);
        assertEquals(0.9, custom.y, 1e-9, "the box centre, turned with the pad, not the anchor");
        PadP turned = pad(p, "U1", "P~1");
        assertEquals(1.0, turned.w, 1e-9);
        assertEquals(0.5, turned.h, 1e-9);
        assertEquals(30, turned.rotation, 1e-6);
        assertEquals(5, turned.x, 1e-9);
        assertNull(turned.approx);
        PadP quad = pad(p, "U1", "P~2");
        assertEquals("polygon", quad.approx);
        assertTrue(codes(p).contains("PAD_SHAPE_APPROX"));
        assertTrue(codes(p).contains("CHAMFER_IGNORED"));
        assertTrue(codes(p).contains("APERTURE_GONE"));
        assertTrue(codes(p).contains("PASTE_POLY"));
        assertTrue(codes(p).contains("PASTE_POLY_APPROX"));
        assertTrue(p.warnings("PAD_SHAPE_APPROX").stream().anyMatch(w -> w.text.contains("(40 % more area)")));
        assertTrue(p.warnings("PAD_SHAPE_APPROX").stream().anyMatch(w -> w.text.equals("U1 pad CU has a custom shape: "
                + "its paste opening is taken as the surrounding rectangle.")));
        assertTrue(p.warnings("APERTURE_GONE").get(0).text.startsWith("U1 pad G gets no paste"));
        assertEquals(1, p.warnings("APERTURE_GONE").get(0).group);
    }

    @Test
    public void padOffsetAndFootprintZones() throws Exception {
        String offset = "(pad \"1\" smd rect (at 1 0 90) (size 0.5 0.5) (drill (offset 0.2 0)) " + PASTE + ")\n";
        String zoned = pad("1", "rect", -3, 0, 0.5, 0.5, "")
                + "(zone (net 0) (layer \"F.Paste\") (polygon (pts (xy 31 30) (xy 32 30) (xy 32 31) (xy 31 31)))"
                + " (filled_polygon (layer \"F.Paste\") (pts (xy 31 30) (xy 32 30) (xy 32 31) (xy 31 31))))\n";
        // U2 at (30, 30) turned 90°: its zone points are board coordinates
        ImportProposal p = Fixtures.board(20240108, OUTLINE + fp("U1", "X", 10, 10, 0, "", offset)
                + fp("U2", "Y", 30, 30, 90, "", zoned)).proposal;
        PadP off = pad(p, "U1", "1");
        assertEquals(1, off.x, 1e-9);
        assertEquals(0.2, off.y, 1e-9, "the offset turns with the pad");
        assertEquals(90, off.rotation, 1e-9);
        PadP zone = pad(p, "U2", "P~1");
        assertEquals(1, zone.w, 1e-9);
        assertEquals(1, zone.h, 1e-9);
        double[] local = BoardFrame.kicadToLocal(Fixtures.board(20240108, fp("U1", "X", 30, 30, 90, "", ""))
                .pcb.footprints.get(0), 31.5, 30.5);
        assertEquals(local[0], zone.x, 1e-9);
        assertEquals(-local[1], zone.y, 1e-9);

        String unfilled = "(zone (net 0) (layer \"F.Paste\") (polygon (pts (xy 31 30) (xy 32 30) (xy 32 31))))\n"
                + pad("1", "rect", 0, 0, 1, 1, "");
        ImportProposal u = Fixtures.board(20240108, OUTLINE + fp("U1", "X", 30, 30, 0, "", unfilled)).proposal;
        assertTrue(codes(u).contains("PASTE_ZONE_IGNORED"));
        String boardPaste = OUTLINE + "(gr_rect (start 1 1) (end 2 2) (fill yes) (layer \"F.Paste\"))\n";
        assertTrue(codes(Fixtures.board(20240108, boardPaste).proposal).contains("BOARD_PASTE_GRAPHIC"));
    }

    // ---- ids ------------------------------------------------------------------------------------------------------

    @Test
    public void cleaning() throws Exception {
        String board = OUTLINE + "(footprint \"Lib:SOT\\n23\" (layer \"F.Cu\") (at 10 10)\n"
                + "  (property \"Reference\" \"U\uFFFE5\") (property \"Value\" \"10k\u0007 1%\")\n"
                + pad("1", "rect", 0, 0, 1, 1, "") + ")";
        ImportProposal p = Fixtures.board(20240108, board).proposal;
        PlacementP u5 = p.placement("U5");
        assertNotNull(u5);
        assertEquals("10k 1%", u5.value);
        assertEquals("SOT 23", p.packages.get(u5.packageIndex).baseId);
        List<ImportWarning> cleaned = p.warnings("ID_CLEANED");
        assertEquals(3, cleaned.size());
        assertTrue(cleaned.stream().anyMatch(w -> w.text.equals("U5's footprint name had characters OpenPnP cannot "
                + "store (a line break): saved as \"SOT 23\".")));
        assertTrue(cleaned.stream().allMatch(w -> w.refs.equals(List.of("U5"))));
    }

    @Test
    public void padIdClashAndVariants() throws Exception {
        String board = OUTLINE
                + fp("U1.1", "SOT-23", 10, 10, 0, "A", pad("2", "rect", 0, 0, 1, 1, ""))
                + fp("U1", "SOT-23", 20, 10, 0, "A", pad("1.2", "rect", 0, 0, 1, 1, ""))
                + fp("U3", "SOT-23", 30, 10, 0, "A", pad("2", "rect", 0, 0, 1, 1, ""));
        ImportProposal p = Fixtures.board(20240108, board).proposal;
        assertEquals("U1.1", p.placements.get(0).id);
        assertEquals("U1~2", p.placements.get(1).id, "U1 pad 1.2 would be U1.1.2, the id of U1.1 pad 2");
        assertTrue(codes(p).contains("PAD_ID_CLASH"));
        // U1.1 and U3 have the same pads; U1 differs: two variants of SOT-23, without final ids
        assertEquals(2, p.packages.size());
        assertEquals("SOT-23", p.packages.get(0).baseId);
        assertEquals(1, p.packages.get(0).variant);
        assertEquals(2, p.packages.get(1).variant);
        assertEquals(List.of("U1.1", "U3"), p.packages.get(0).refs);
        assertEquals(p.placements.get(0).packageIndex, p.placements.get(2).packageIndex);
        ImportWarning v = p.warnings("PACKAGE_VARIANT").get(0);
        assertEquals(List.of("U1.1", "U1~2"), v.refs);
    }

    /**
     * Review 1: KiCad writes absolute pad angles, so a footprint at 76.1° has its 180° pad at 256.1°. 256.1 − 76.1 is
     * 180 + ε in floating point, which normalises to −180 + ε and gave −180.000 in the package signature, while the
     * copy at 0° gave 180.000: one footprint was split into two packages with a false PACKAGE_VARIANT warning.
     */
    @Test
    public void aPadTurned180IsOnePackageAtAnyAngle() throws Exception {
        String d1 = "(pad \"1\" smd rect (at -1.5 0) (size 1 1) " + PASTE + ")\n"
                + "(pad \"2\" smd rect (at 1.5 0 180) (size 1 1) " + PASTE + ")\n";
        String d2 = "(pad \"1\" smd rect (at -1.5 0 76.1) (size 1 1) " + PASTE + ")\n"
                + "(pad \"2\" smd rect (at 1.5 0 256.1) (size 1 1) " + PASTE + ")\n";
        ImportProposal p = Fixtures.board(20240108, OUTLINE + fp("D1", "SOD-123", 10, 10, 0, "", d1)
                + fp("D2", "SOD-123", 30, 30, 76.1, "", d2)).proposal;
        assertEquals(1, p.packages.size(), p.packages.size() > 1 ? p.packages.get(0).signature + " / "
                + p.packages.get(1).signature : "");
        assertEquals(List.of("D1", "D2"), p.packages.get(0).refs);
        assertFalse(codes(p).contains("PACKAGE_VARIANT"), codes(p).toString());
    }

    /**
     * Review 1: a footprint outside the board outline (a spare parked next to the board after "Update PCB from
     * schematic") or with a paste opening past its edge became a pasted placement, and nothing warned. It is drawn
     * only, with OFF_BOARD; a pad that reaches exactly to the edge is on the board.
     */
    @Test
    public void footprintsOutsideTheBoardGetNoPaste() throws Exception {
        String r = pad("1", "rect", -0.8, 0, 0.8, 0.95, "") + pad("2", "rect", 0.8, 0, 0.8, 0.95, "");
        String turned = "(pad \"1\" smd rect (at -0.8 0 90) (size 0.8 0.95) " + PASTE + ")\n"
                + "(pad \"2\" smd rect (at 0.8 0 90) (size 0.8 0.95) " + PASTE + ")\n";
        ImportProposal p = Fixtures.board(20240108, OUTLINE + fp("R1", "R_0603", 25, 25, 0, "10k", r)
                + fp("R2", "R_0603", -15, 25, 0, "10k", r) // parked next to the board
                + fp("R3", "R_0603", 49.5, 25, 0, "10k", r) // pad 2 reaches X 50.7, the board ends at 50
                + fp("R4", "R_0603", 48.8, 25, 0, "10k", r) // pad 2 ends on the edge
                + fp("R5", "R_0603", 25, 49.4, 90, "10k", turned)) // turned: pad 2 reaches Y -0.6
                .proposal;
        List<String> placed = new ArrayList<>();
        for (PlacementP pl : p.placements) {
            placed.add(pl.id);
        }
        assertEquals(List.of("R1", "R4"), placed);
        assertEquals(List.of("R2", "R3", "R5"), p.skipped.get(ImportProposal.SKIP_ART_ONLY));
        assertEquals(2, p.counts.placements);
        assertEquals(4, p.counts.pastePads);
        assertEquals("outside the board", p.footprints.get(1).why);
        assertEquals(ImportProposal.FootprintP.ART, p.footprints.get(1).kind);
        ImportWarning w = p.warnings("OFF_BOARD").get(0);
        assertEquals(List.of("R2", "R3", "R5"), w.refs);
        assertEquals(3, w.group);
        assertEquals("R2, R3, R5 are not inside the board outline: they get no paste (their paste would land off the "
                + "board). Move them onto the board in KiCad if they are part of this board.", w.text);
        ImportProposal one = Fixtures.board(20240108, OUTLINE + fp("R1", "R_0603", 25, 25, 0, "10k", r)
                + fp("R2", "R_0603", -15, 25, 0, "10k", r)).proposal;
        assertEquals("R2 is not inside the board outline: it gets no paste (its paste would land off the board). "
                + "Move it onto the board in KiCad if it is part of this board.", one.warnings("OFF_BOARD").get(0).text);
    }

    @Test
    public void customPadsShareOneNote() throws Exception {
        String custom = "(options (clearance outline) (anchor rect)) (primitives (gr_poly (pts (xy 0 0) (xy 1 0) (xy 1 1)) "
                + "(width 0) (fill yes)))";
        String body = "(pad \"1\" smd custom (at 0 0) (size 0.5 0.5) " + PASTE + " " + custom + ")\n"
                + "(pad \"2\" smd custom (at 2 0) (size 0.5 0.5) " + PASTE + " " + custom + ")\n";
        ImportProposal p = Fixtures.board(20240108, OUTLINE + fp("U1", "X", 10, 10, 0, "", body)
                + fp("U2", "X", 20, 10, 0, "", body)).proposal;
        List<ImportWarning> approx = p.warnings("PAD_SHAPE_APPROX");
        assertEquals(1, approx.size(), approx.toString());
        assertEquals("U1, U2 pads 1, 2 have a custom shape: their paste openings are taken as the surrounding "
                + "rectangle.", approx.get(0).text);
    }

    @Test
    public void padstackRefsAreCleaned() throws Exception {
        String body = "(pad \"1\" smd rect (at 0 0) (size 1 1) " + PASTE + " (padstack (mode custom)))\n";
        ImportProposal p = Fixtures.board(20241229, OUTLINE + fp("U\u00071", "X", 10, 10, 0, "", body + body))
                .proposal;
        ImportWarning w = p.warnings("PADSTACK_IGNORED").get(0);
        assertEquals(List.of("U1"), w.refs);
        assertTrue(w.text.startsWith("U1: "), w.text);
    }

    @Test
    public void emptyReferences() throws Exception {
        String board = OUTLINE + fp("", "R", 10, 10, 0, "", pad("1", "rect", 0, 0, 1, 1, ""))
                + fp("", "R", 20, 10, 0, "", pad("1", "rect", 0, 0, 1, 1, ""));
        ImportProposal p = Fixtures.board(20240108, board).proposal;
        assertEquals("P~1", p.placements.get(0).id);
        assertEquals("P~2", p.placements.get(1).id);
    }

    // ---- hostile numbers ------------------------------------------------------------------------------------------

    @Test
    public void hostileNumbersSkipTheirItem() throws Exception {
        String board = OUTLINE
                + "(gr_line (start 1e300 0) (end 0 0) (layer \"F.SilkS\") (stroke (width 0.1)))\n"
                + "(gr_arc (start 0 0) (mid 1 0.0000000005) (end 2 0) (layer \"F.SilkS\") (stroke (width 0.1)))\n"
                + "(gr_line (start 1 1) (end 2 2) (layer \"F.SilkS\") (stroke (width 0.1)))\n"
                + "(segment (start 0 0) (end NaN 1) (width 0.25) (layer \"F.Cu\"))\n"
                + fp("R1", "R", 10, 10, 0, "", pad("1", "rect", 0, 0, 1e9, 1, "") + pad("2", "rect", 1, 0, 1, 1, ""));
        Fixtures.Loaded l = Fixtures.board(20240108, board);
        List<ImportWarning> bad = l.proposal.warnings("BAD_NUMBER");
        assertEquals(4, bad.size(), bad.toString());
        assertEquals(2, l.pcb.graphics.size(), "the outline and the good line");
        assertEquals(0, l.pcb.tracks.size());
        assertEquals(1, l.proposal.placement("R1").pads);
        assertTrue(bad.stream().anyMatch(w -> w.refs.equals(List.of("R1"))));
        assertTrue(bad.get(0).text.matches("Line \\d+: a number could not be read or is out of range; that item was "
                + "skipped\\."));
    }

    /**
     * Full circles of 2,000 mm are 360 points each, so a file inside the item limit could make about 50 million points
     * (gigabytes) in a polygon's arcs or in the outline. Both stop at KGeom.MAX_FLAT_POINTS with a limit.
     */
    @Test
    public void arcsAndCirclesMakeAtMostTwoMillionPoints() {
        int n = KGeom.MAX_FLAT_POINTS / 360 + 100;
        StringBuilder poly = new StringBuilder(OUTLINE).append("(gr_poly (pts");
        StringBuilder circles = new StringBuilder();
        for (int i = 0; i < n; i++) {
            poly.append(" (arc (start 2000 0) (mid -2000 1) (end 2000 2))");
            circles.append("(gr_circle (center 0 0) (end 2000 0) (layer \"Edge.Cuts\"))\n");
        }
        poly.append(") (layer \"F.SilkS\") (fill yes))\n");
        SExprException inShapes = assertThrows(SExprException.class, () -> Fixtures.board(20240108, poly.toString()));
        assertEquals(SExprException.LIMIT, inShapes.kind);
        assertTrue(inShapes.getMessage().contains("its shapes have more than 2,000,000 points once their arcs are "
                + "drawn (line 3)"), inShapes.getMessage());
        SExprException inOutline = assertThrows(SExprException.class,
                () -> Fixtures.board(20240108, circles.toString()));
        assertEquals(SExprException.LIMIT, inOutline.kind);
        assertTrue(inOutline.getMessage().contains("its outline has more than 2,000,000 points"),
                inOutline.getMessage());
    }

    @Test
    public void aFullListDropsItsLeastImportantWarning() {
        List<ImportWarning> l = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ImportWarning.addCapped(l, ImportWarning.of("PASTE_POLY", null, "note " + i), 4);
        }
        assertTrue(ImportWarning.addCapped(l, ImportWarning.of("BAD_NUMBER", null, "check"), 4));
        assertFalse(ImportWarning.addCapped(l, ImportWarning.of("NO_PLACEMENTS", null, "blocks"), 4));
        assertEquals(List.of("note 0", "note 1", "check", "blocks"), texts(l), "a note goes, not the group 3 warning");
        assertFalse(ImportWarning.addCapped(l, ImportWarning.of("PASTE_POLY", null, "note 3"), 4));
        assertEquals(List.of("note 0", "note 1", "check", "blocks"), texts(l), "nothing less important to drop");
    }

    private static List<String> texts(List<ImportWarning> l) {
        List<String> t = new ArrayList<>();
        for (ImportWarning w : l) {
            t.add(w.text);
        }
        return t;
    }

    // ---- tenting, colours -----------------------------------------------------------------------------------------

    @Test
    public void viaTentingFormsAndOverrides() throws Exception {
        String via = "(via (at 5 5) (size 0.6) (drill 0.3) (layers \"F.Cu\" \"B.Cu\") (net 0))\n"
                + "(via (at 6 6) (size 0.6) (drill 0.3) (layers \"F.Cu\" \"B.Cu\") (tenting (front no) (back yes)))\n"
                + "(via (at 7 7) (size 0.6) (drill 0.3) (layers \"F.Cu\" \"B.Cu\") (tenting front))\n";
        KicadPcb k10 = Fixtures.board(20260206, "(setup (tenting (front no) (back yes)))" + via).pcb;
        assertFalse(k10.setup.viasTentedFront);
        assertFalse(k10.vias.get(0).tentedFront(k10.setup));
        assertFalse(k10.vias.get(1).tentedFront(k10.setup));
        assertTrue(k10.vias.get(2).tentedFront(k10.setup), "per-via override");
        assertTrue(Fixtures.board(20241229, "(setup (tenting front back))").pcb.setup.viasTentedFront);
        assertFalse(Fixtures.board(20241229, "(setup (tenting back))").pcb.setup.viasTentedFront);
        assertFalse(Fixtures.board(20240108, "(setup (pcbplotparams (viasonmask yes)))").pcb.setup.viasTentedFront);
        assertTrue(Fixtures.board(20240108, "(setup (pcbplotparams (viasonmask no)))").pcb.setup.viasTentedFront);
        assertTrue(Fixtures.board(20240108, "(setup (pad_to_mask_clearance 0))").pcb.setup.viasTentedFront);
        KicadPcb colours = Fixtures.board(20240108, "(setup (stackup (layer \"F.SilkS\" (type \"Top Silk Screen\")"
                + " (color \"Yellow\")) (layer \"F.Paste\" (type \"Top Solder Paste\")) (layer \"F.Mask\" (type "
                + "\"Top Solder Mask\") (color \"Red\") (thickness 0.01))))").pcb;
        assertEquals("Red", colours.setup.maskColor);
        assertEquals("Yellow", colours.setup.silkColor);
    }

    @Test
    public void unknownTokensAreCountedNeverFatal() throws Exception {
        KicadPcb pcb = Fixtures.board(20240108, OUTLINE + "(flux_capacitor 1.21 (gw yes))\n"
                + fp("R1", "R", 10, 10, 0, "", "(sparkle on) " + pad("1", "rect", 0, 0, 1, 1, "(glow 3)"))).pcb;
        assertEquals(2, pcb.ignoredItems);
        assertEquals(1, pcb.footprints.get(0).pads.size());
    }

    @Test
    public void warningsAreCappedAndOrdered() throws Exception {
        StringBuilder sb = new StringBuilder(OUTLINE);
        for (int i = 0; i < 600; i++) {
            sb.append("(gr_line (start 1e9 0) (end 0 0) (layer \"F.SilkS\"))\n");
        }
        ImportProposal p = Fixtures.board(20240108, sb.toString()).proposal;
        assertEquals(ImportProposal.MAX_WARNINGS + 1, p.warnings.size());
        assertEquals("NO_PLACEMENTS", p.warnings.get(0).code, "a flood of notes does not push it out");
        assertEquals(102, p.warningsDropped);
        ImportWarning last = p.warnings.get(p.warnings.size() - 1);
        assertEquals("WARNINGS_TRUNCATED", last.code);
        int group = 0;
        for (ImportWarning w : p.warnings) {
            assertTrue(w.group >= group, "group order");
            group = w.group;
        }
        p.sortWarnings();
        assertEquals(ImportProposal.MAX_WARNINGS + 1, p.warnings.size(), "sorting again adds no second note");
        PackageP none = p.packages.isEmpty() ? null : p.packages.get(0);
        assertNull(none);
    }
}
