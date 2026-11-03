package org.openpnp.instancelabs.board;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.kicad.BoardFrame;
import org.openpnp.instancelabs.kicad.Deadline;
import org.openpnp.instancelabs.kicad.ImportProposal;
import org.openpnp.instancelabs.kicad.ImportWarning;
import org.openpnp.instancelabs.kicad.KGeom;
import org.openpnp.instancelabs.kicad.KicadPcb.Footprint;
import org.openpnp.instancelabs.kicad.KicadPcb.Pad;
import org.openpnp.instancelabs.kicad.SExprException;

/** Board art from the KiCad fixtures (design §4.1, §4.2, §6.3). */
public class BoardArtBuilderTest {
    static final Map<String, ArtFixtures.Drawn> DRAWN = new HashMap<>();

    @BeforeAll
    public static void draw() throws Exception {
        for (String n : ArtFixtures.BOARDS) {
            DRAWN.put(n, ArtFixtures.draw(n));
        }
    }

    static BoardArt art(String name) {
        return DRAWN.get(name).art();
    }

    @Test
    public void theSameFileGivesTheSameBytes() throws Exception {
        for (String n : ArtFixtures.BOARDS) {
            byte[] again = ArtFixtures.draw(n).art().toBytes();
            assertArrayEquals(art(n).toBytes(), again, n);
            assertEquals(BoardArt.idOf(again), BoardArt.idOf(art(n).toBytes()));
        }
    }

    @Test
    public void everyFixtureFitsTheBudget() {
        for (String n : ArtFixtures.BOARDS) {
            BoardArt a = art(n);
            int bytes = a.toBytes().length;
            assertTrue(bytes <= BoardArtBuilder.TARGET_BYTES, n + ": " + bytes + " bytes");
            assertEquals(bytes, a.stats.bytes, n);
            assertEquals(0.01, a.stats.toleranceMm, 0, n);
            assertEquals(0.01, a.stats.coarseToleranceMm, 0, n + " needed no coarsening");
            assertTrue(DRAWN.get(n).result.warnings.isEmpty(), n + ": " + DRAWN.get(n).result.warnings);
            int elements = elements(a);
            assertTrue(elements < 1500, n + ": " + elements + " SVG elements");
            String json = new String(a.toBytes(), StandardCharsets.UTF_8);
            assertFalse(json.contains("NaN") || json.contains("Infinity"), n);
            int thumb = a.thumb.outline.length() + a.thumb.mask.length() + a.thumb.exposed.length();
            assertTrue(thumb <= 8 * 1024, n + ": thumbnail " + thumb + " bytes");
            assertEquals(0.1, a.thumb.toleranceMm, 0);
            assertTrue(a.thumb.exposed.length() > 0 || n.equals(ArtFixtures.SECONDARY), n);
            System.out.println(String.format(Locale.ROOT, "BoardArtBuilderTest: %s %,d bytes, %,d points, %d SVG "
                    + "elements, thumbnail %,d bytes", n, bytes, a.stats.points, elements, thumb));
        }
    }

    /** What boardart.js builds from the art (one path per layer and line width, text rows, labels, origin). */
    static int elements(BoardArt a) {
        int n = 11 + 3 + 2 + 5; // layer paths, line groups, text and label groups, origin marker
        n += a.layers.cu.tracks.size() + a.layers.silk.lines.size() + a.layers.fab.lines.size();
        for (BoardArt.Text t : a.texts) {
            n += t.t.split("\n").length;
        }
        for (BoardArt.Part p : a.parts) {
            n += "bottom".equals(p.kind) ? 0 : 1;
        }
        return n;
    }

    @Test
    public void everyPathParsesAndLiesOnTheBoard() {
        for (String n : ArtFixtures.BOARDS) {
            BoardArt a = art(n);
            List<String> board = new ArrayList<>(ArtFixtures.paths(a));
            List<String> fab = new ArrayList<>();
            for (BoardArt.Stroke s : a.layers.fab.lines) {
                board.remove(s.d);
                fab.add(s.d);
                assertTrue(s.w > 0, n);
            }
            double[] box = ArtFixtures.box(board);
            String where = n + " " + java.util.Arrays.toString(box);
            assertTrue(box[0] >= -1 && box[1] >= -1 && box[2] <= a.size[0] + 1 && box[3] <= a.size[1] + 1, where);
            // part outlines draw part bodies, which may overhang the edge (nano's USB connector: 1.83 mm)
            double[] fb = ArtFixtures.box(fab);
            assertTrue(KGeom.isEmpty(fb) || fb[0] >= -5 && fb[1] >= -5 && fb[2] <= a.size[0] + 5
                    && fb[3] <= a.size[1] + 5, n + " fab " + java.util.Arrays.toString(fb));
            for (String t : new String[] {a.thumb.outline, a.thumb.mask, a.thumb.exposed}) {
                ArtPath.parse(t);
            }
        }
    }

    @Test
    public void outlinesAndCutouts() {
        Map<String, Integer> cutouts = new HashMap<>();
        cutouts.put(ArtFixtures.NANO, 4);
        cutouts.put(ArtFixtures.MOBO, 13);
        for (String n : ArtFixtures.BOARDS) {
            BoardArt a = art(n);
            assertTrue(a.outline.closed, n);
            List<ArtPath.Sub> o = ArtPath.parse(a.outline.d);
            assertEquals(1, o.size(), n);
            assertTrue(o.get(0).closed, n);
            int cut = 0;
            for (ArtPath.Sub s : ArtPath.parse(a.outline.cutouts)) {
                assertTrue(s.closed, n);
                cut++;
            }
            assertEquals((int) cutouts.getOrDefault(n, 0), cut, n);
            assertEquals("outline", a.origin, n);
            assertEquals(DRAWN.get(n).frame.widthMm(), a.size[0], 0, n);
        }
        assertArrayEquals(new double[] {0, 0, 75, 75}, ArtFixtures.box(java.util.Collections.singletonList(
                art(ArtFixtures.FTP).outline.d)), 0);
        assertEquals(4, ArtPath.parse(art(ArtFixtures.FTP).outline.d).get(0).pts.length / 2);
        assertArrayEquals(new double[] {51.5677, 89.0}, art(ArtFixtures.MOBO).size, 1e-4);
        assertArrayEquals(new double[] {43.18, 17.78}, art(ArtFixtures.NANO).size, 1e-6);
    }

    @Test
    public void ftpHoles() {
        BoardArt a = art(ArtFixtures.FTP);
        List<double[]> pth = ArtFixtures.circles(a.layers.holes.pth);
        assertEquals(6, pth.size(), "H1–H4 and J1's two pins");
        assertEquals("", a.layers.holes.npth);
        assertEquals("", a.layers.holes.via);
        for (double[] at : new double[][] {{5, 5}, {70, 5}, {5, 70}, {70, 70}}) {
            boolean found = false;
            for (double[] c : pth) {
                found |= Math.hypot(c[0] - at[0], c[1] - at[1]) < 0.01 && Math.abs(2 * c[2] - 3.2) < 0.01;
            }
            assertTrue(found, "a 3.2 mm hole at " + at[0] + ", " + at[1]);
        }
        assertEquals(6, a.keepOutHoles.size());
        for (double[] h : a.keepOutHoles) {
            assertTrue(h[2] > 0.5, java.util.Arrays.toString(h)); // copper size, not the drill
        }
    }

    @Test
    public void nanoBottomPinsAreDrawnFromTheTop() {
        ArtFixtures.Drawn d = DRAWN.get(ArtFixtures.NANO);
        BoardArt a = d.art();
        List<double[]> pth = ArtFixtures.circles(a.layers.holes.pth);
        int pins = 0;
        for (Footprint fp : d.pcb.footprints) {
            if (fp.top || !(fp.ref.equals("J1") || fp.ref.equals("J2"))) {
                continue;
            }
            for (Pad p : fp.pads) {
                double[] at = d.frame.localToBoard(fp, p.x, p.y);
                boolean hole = false;
                for (double[] c : pth) {
                    hole |= Math.hypot(c[0] - at[0], c[1] - at[1]) < 0.01 && Math.abs(2 * c[2] - p.drillW) < 0.011;
                }
                assertTrue(hole, fp.ref + "." + p.name + " hole");
                assertTrue(ArtFixtures.inside(a.layers.cu.fill, at[0], at[1]), fp.ref + "." + p.name + " copper");
                assertTrue(ArtFixtures.inside(a.layers.exposed.fill, at[0], at[1]), fp.ref + "." + p.name);
                pins++;
            }
        }
        assertEquals(30, pins);
        TreeSet<String> bottom = new TreeSet<>();
        for (BoardArt.Part p : a.parts) {
            if ("bottom".equals(p.kind)) {
                bottom.add(p.ref);
                assertNull(p.label);
            }
        }
        assertEquals("[J1, J2]", bottom.toString());
        assertTrue(ArtFixtures.circles(a.layers.holes.via).size() > 0);
    }

    @Test
    public void exposedCopperHoldsEveryTopPadAndFiducial() {
        for (String n : ArtFixtures.BOARDS) {
            ArtFixtures.Drawn d = DRAWN.get(n);
            BoardArt a = d.art();
            int checked = 0;
            int drawnOpen = 0;
            for (ImportProposal.PlacementP pl : d.proposal.placements) {
                Footprint fp = d.pcb.footprints.get(pl.footprintIndex);
                for (Pad p : fp.pads) {
                    boolean bareHole = "np_thru_hole".equals(p.type) && p.w <= p.drillW + 0.01; // no copper
                    double t = Math.toRadians(p.angle - fp.angle);
                    double[] at = d.frame.localToBoard(fp, p.x + p.offX * Math.cos(t) + p.offY * Math.sin(t),
                            p.y - p.offX * Math.sin(t) + p.offY * Math.cos(t));
                    // a pad without a mask opening of its own counts when a drawn F.Mask shape opens its centre
                    boolean opened = p.onFrontMask() || ArtFixtures.inside(a.layers.mask.open, at[0], at[1]);
                    if (!p.onFrontCopper() || !opened || bareHole) {
                        continue;
                    }
                    drawnOpen += p.onFrontMask() ? 0 : 1;
                    String what = n + " " + pl.id + "." + p.name;
                    assertTrue(ArtFixtures.inside(a.layers.exposed.fill, at[0], at[1]), what);
                    assertTrue(ArtFixtures.inside(a.layers.cu.fill, at[0], at[1]), what);
                    assertTrue(ArtFixtures.inside(a.layers.mask.open, at[0], at[1]), what);
                    checked++;
                }
            }
            for (BoardArt.FidArt f : a.fiducials) {
                assertTrue(ArtFixtures.inside(a.layers.exposed.fill, f.bx, f.by), n + " " + f.name);
            }
            assertTrue(checked > 0 || n.equals(ArtFixtures.SECONDARY), n);
            // nano IC3's centre pad 33 is on F.Cu only; a polygon drawn on F.Mask opens it
            assertEquals(n.equals(ArtFixtures.NANO) ? 1 : 0, drawnOpen, n);
        }
    }

    @Test
    public void pasteIsTheImportsApertures() {
        for (String n : ArtFixtures.BOARDS) {
            ArtFixtures.Drawn d = DRAWN.get(n);
            assertEquals(d.proposal.counts.pastePads, ArtPath.parse(d.art().layers.paste.fill).size(), n);
        }
        BoardArt a = art(ArtFixtures.FTP);
        // R1 at (37.5, 42.5), 90°: its pads ±0.825 mm along the part's X, so along the board's Y
        assertTrue(ArtFixtures.inside(a.layers.paste.fill, 37.5, 42.5 + 0.825));
        assertTrue(ArtFixtures.inside(a.layers.paste.fill, 37.5, 42.5 - 0.825));
        assertFalse(ArtFixtures.inside(a.layers.paste.fill, 37.5, 42.5));
    }

    @Test
    public void partsLabelsAndTexts() {
        BoardArt a = art(ArtFixtures.FTP);
        assertEquals(88, a.parts.size(), "every top footprint (G2 is on the bottom)");
        Map<String, Integer> kinds = new HashMap<>();
        BoardArt.Part r1 = null;
        for (BoardArt.Part p : a.parts) {
            kinds.merge(p.kind, 1, Integer::sum);
            if (p.ref.equals("R1")) {
                r1 = p;
            }
        }
        assertEquals(80, (int) kinds.get("placement"));
        assertEquals(3, (int) kinds.get("fiducial"));
        assertEquals(5, (int) kinds.get("art"));
        assertNotNull(r1);
        assertEquals("R_0603_1608Metric", r1.pkg);
        assertEquals("R_Small", r1.value);
        assertEquals(37.499966, r1.x, 1e-9);
        assertEquals(42.499987, r1.y, 1e-9);
        assertEquals(90, r1.a, 0);
        assertEquals(2, r1.pads);
        assertTrue(r1.box[0] < -0.7 && r1.box[2] > 0.7 && r1.box[1] < -0.3 && r1.box[3] > 0.3,
                java.util.Arrays.toString(r1.box));
        // the Reference on F.SilkS at (2.25, 0) in the part, KiCad's absolute angle 180
        assertEquals(37.5, r1.label.x, 0.01);
        assertEquals(44.75, r1.label.y, 0.01);
        assertEquals(180, r1.label.a, 0);
        assertEquals(1.0, r1.label.h, 0);

        assertEquals(3, a.texts.size());
        BoardArt.Text t = a.texts.get(0);
        assertEquals("Functional Test Placement REV00\n<<hash>>", t.t);
        assertEquals("start", t.anchor);
        assertEquals(10, t.x, 0);
        assertEquals(6.25, t.y, 0);
        assertEquals(1.5, t.h, 0);
        assertEquals("middle", a.texts.get(1).anchor);
        assertEquals(3, art(ArtFixtures.MOBO).texts.size());
        assertEquals(0, art(ArtFixtures.NANO).texts.size());
    }

    @Test
    public void finishAndFiducials() {
        for (String n : ArtFixtures.BOARDS) {
            BoardArt a = art(n);
            assertEquals(n.equals(ArtFixtures.MOBO) ? "black" : "slate", a.finish.mask, n);
            assertEquals("white", a.finish.silk, n);
            assertEquals("kicad", a.kind);
            assertNull(a.imported, "the import adds its record");
        }
        BoardArt ftp = art(ArtFixtures.FTP);
        assertEquals(3, ftp.fiducials.size());
        for (BoardArt.FidArt f : ftp.fiducials) {
            assertEquals(1.0, f.copperMm, 1e-9, f.name);
            assertEquals(3.0, f.maskMm, 1e-9, f.name);
        }
        BoardArt.FidArt fid1 = ftp.fiducials.get(1);
        assertEquals("FID1", fid1.name);
        assertEquals(62.5, fid1.bx, 1e-6);
        assertEquals(5.0, fid1.by, 1e-6);
        assertEquals(3, art(ArtFixtures.MOBO).fiducials.size());
        for (BoardArt.FidArt f : art(ArtFixtures.MOBO).fiducials) {
            assertEquals(2.0, f.maskMm, 1e-9, f.name);
        }
        // nano draws its fiducial openings on F.Mask (a ring of width 1.2 around r 0.5) instead of a margin
        for (BoardArt.FidArt f : art(ArtFixtures.NANO).fiducials) {
            assertEquals(1.016, f.copperMm, 1e-9, f.name);
            assertEquals(2.2, f.maskMm, 1e-9, f.name);
        }
        assertEquals(20240108, (int) ftp.source.kicadVersion);
        assertEquals("pcbnew 8.0", ftp.source.generator);
    }

    @Test
    public void aSmallBudgetCoarsensOnlyCopperSilkscreenAndOutlines() throws Exception {
        ArtFixtures.Drawn d = ArtFixtures.draw(ArtFixtures.FTP, ArtFixtures.bytes(ArtFixtures.FTP),
                new BoardArtBuilder().budget(60_000, BoardArtBuilder.MAX_BYTES, BoardArtBuilder.MAX_POINTS));
        BoardArt small = d.art();
        BoardArt full = art(ArtFixtures.FTP);
        assertEquals(0.08, small.stats.coarseToleranceMm, 0);
        assertEquals(0.01, small.stats.toleranceMm, 0);
        assertTrue(small.toBytes().length < full.toBytes().length);
        assertNotEquals(full.layers.cu.fill, small.layers.cu.fill);
        assertEquals(full.outline.d, small.outline.d);
        assertEquals(full.layers.exposed.fill, small.layers.exposed.fill);
        assertEquals(full.layers.mask.open, small.layers.mask.open);
        assertEquals(full.layers.paste.fill, small.layers.paste.fill);
        assertEquals(full.layers.holes.pth, small.layers.holes.pth);
        assertEquals(1, d.result.warnings.size());
        ImportWarning w = d.result.warnings.get(0);
        assertEquals("ART_SIMPLIFIED", w.code);
        assertEquals("info", w.level);
        assertTrue(w.text.contains("0.08 mm"), w.text);
    }

    @Test
    public void tooManyPointsDropTheTracksWithinTheDeadline() throws Exception {
        // 6,000 track arcs of radius 1,000 mm sweeping 350°: 351 points each once drawn at 1° steps
        StringBuilder sb = new StringBuilder("(gr_rect (start 0 0) (end 100 100) (layer \"Edge.Cuts\") (width 0.1))\n");
        double r = 1000;
        for (int i = 0; i < 6000; i++) {
            double a0 = Math.toRadians(5);
            sb.append(String.format(Locale.ROOT, "(arc (start %.4f %.4f) (mid %.4f %.4f) (end %.4f %.4f) "
                    + "(width 0.2) (layer \"F.Cu\"))%n", 50 + r * Math.cos(a0), 50 + r * Math.sin(a0), 50 - r,
                    50.0 + i * 1e-4, 50 + r * Math.cos(-a0), 50 + r * Math.sin(-a0)));
        }
        Deadline deadline = new Deadline(Deadline.DEFAULT_MS);
        byte[] file = ArtFixtures.board(20240108, sb.toString());
        long t0 = System.nanoTime();
        ArtFixtures.Drawn d = ArtFixtures.draw("arcs", file, new BoardArtBuilder());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertFalse(deadline.expired());
        assertTrue(ms < Deadline.DEFAULT_MS, ms + " ms");
        assertEquals(6000, d.pcb.tracks.size());
        assertTrue(d.art().layers.cu.tracks.isEmpty());
        ImportWarning w = d.result.warnings.get(0);
        assertEquals("ART_REDUCED", w.code);
        assertEquals("warn", w.level);
        assertEquals("The board is too detailed to draw in full: tracks are not drawn.", w.text);
        assertArrayEquals(new double[] {0, 0, 100, 100},
                ArtFixtures.box(java.util.Collections.singletonList(d.art().outline.d)), 0);
    }

    @Test
    public void aDrawingThatCannotBeReducedIsALimit() throws Exception {
        byte[] file = ArtFixtures.bytes(ArtFixtures.FTP);
        SExprException e = assertThrows(SExprException.class, () -> ArtFixtures.draw(ArtFixtures.FTP, file,
                new BoardArtBuilder().budget(BoardArtBuilder.TARGET_BYTES, BoardArtBuilder.MAX_BYTES, 1000)));
        assertEquals("limit", e.kind);
    }

    @Test
    public void unfilledZonesAreNoted() throws Exception {
        String items = "(gr_rect (start 0 0) (end 20 10) (layer \"Edge.Cuts\") (width 0.1))\n"
                + "(zone (net 0) (layer \"F.Cu\") (polygon (pts (xy 1 1) (xy 9 1) (xy 9 9) (xy 1 9))))\n"
                + "(zone (net 0) (layer \"F.Cu\") (polygon (pts (xy 11 1) (xy 19 1) (xy 19 9) (xy 11 9)))"
                + " (filled_polygon (layer \"F.Cu\") (pts (xy 11 1) (xy 19 1) (xy 19 9) (xy 11 9))))\n";
        ArtFixtures.Drawn d = ArtFixtures.draw("zones", ArtFixtures.board(20240108, items), new BoardArtBuilder());
        assertEquals(1, d.result.warnings.size());
        assertEquals("ZONES_UNFILLED", d.result.warnings.get(0).code);
        assertEquals("info", d.result.warnings.get(0).level);
        assertTrue(ArtFixtures.inside(d.art().layers.cu.fill, 15, 5));
        assertFalse(ArtFixtures.inside(d.art().layers.cu.fill, 5, 5));
    }

    @Test
    public void aDrawnMaskOpeningExposesTheCopperUnderIt() throws Exception {
        // pads on F.Cu only: under a larger drawn opening (pad 1), around a smaller one (pad 2), half under one (3)
        String items = "(gr_rect (start 0 -10) (end 20 0) (layer \"Edge.Cuts\") (width 0.1))\n"
                + "(footprint \"Lib:Drawn\" (layer \"F.Cu\") (at 10 -5)\n"
                + "  (pad \"1\" smd rect (at -5 0) (size 1 1) (layers \"F.Cu\"))\n"
                + "  (fp_rect (start -6 -1) (end -4 1) (layer \"F.Mask\") (width 0) (fill solid))\n"
                + "  (pad \"2\" smd rect (at 0 0) (size 3 3) (layers \"F.Cu\"))\n"
                + "  (fp_circle (center 0 0) (end 0.5 0) (layer \"F.Mask\") (width 0) (fill solid))\n"
                + "  (pad \"3\" smd rect (at 5 0) (size 1 1) (layers \"F.Cu\"))\n"
                + "  (fp_rect (start 5 -1) (end 7 1) (layer \"F.Mask\") (width 0) (fill solid))\n"
                + ")\n";
        BoardArt a = ArtFixtures.draw("drawn", ArtFixtures.board(20240108, items), new BoardArtBuilder()).art();
        // pad 1 at board (5, 5): its whole 1 × 1 copper is exposed, not the 2 × 2 opening
        assertTrue(ArtFixtures.inside(a.layers.exposed.fill, 5.45, 5.45));
        assertFalse(ArtFixtures.inside(a.layers.exposed.fill, 5.8, 5));
        // pad 2 at (10, 5): only the 1 mm opening inside it
        assertTrue(ArtFixtures.inside(a.layers.exposed.fill, 10.4, 5));
        assertFalse(ArtFixtures.inside(a.layers.exposed.fill, 11, 5));
        // pad 3 at (15, 5), half under its opening: left covered
        assertFalse(ArtFixtures.inside(a.layers.exposed.fill, 15.3, 5));
        assertEquals(2, ArtPath.parse(a.layers.exposed.fill).size());
    }

    @Test
    public void padShapesMasksAndHoles() throws Exception {
        // one footprint at (10, 5) turned 90°, with every pad shape; the board is 20 × 10 (KiCad Y down: -10..0)
        String items = "(gr_rect (start 0 -10) (end 20 0) (layer \"Edge.Cuts\") (width 0.1))\n"
                + "(setup (pad_to_mask_clearance 0.05))\n"
                + "(footprint \"Lib:Test\" (layer \"F.Cu\") (at 10 -5 90)\n"
                + "  (property \"Reference\" \"U1\" (at 0 0) (layer \"F.SilkS\") (hide yes))\n"
                + "  (pad \"1\" smd rect (at -3 0 90) (size 1 2) (layers \"F.Cu\" \"F.Paste\" \"F.Mask\"))\n"
                + "  (pad \"2\" smd circle (at 3 0 90) (size 1 1) (layers \"F.Cu\" \"F.Mask\")\n"
                + "    (solder_mask_margin -0.1))\n"
                + "  (pad \"3\" thru_hole oval (at 0 2 90) (size 1.2 2) (drill oval 0.6 1.2)\n"
                + "    (layers \"*.Cu\" \"*.Mask\"))\n"
                + "  (pad \"\" np_thru_hole circle (at 0 -2 90) (size 1 1) (drill 1) (layers \"*.Cu\" \"*.Mask\"))\n"
                + "  (pad \"5\" smd trapezoid (at 0 0 90) (size 1 1) (rect_delta 0 0.4) (layers \"F.Cu\" \"F.Mask\"))\n"
                + ")\n"
                + "(via (at 2 -2) (size 0.6) (drill 0.3) (layers \"F.Cu\" \"B.Cu\") (tenting none))\n"
                + "(via (at 3 -2) (size 0.6) (drill 0.3) (layers \"In1.Cu\" \"B.Cu\"))\n";
        ArtFixtures.Drawn d = ArtFixtures.draw("shapes", ArtFixtures.board(20240108, items), new BoardArtBuilder());
        BoardArt a = d.art();
        BoardFrame f = d.frame;
        assertEquals(20, f.widthMm(), 0);
        // pad 1 at local (-3, 0), footprint turned 90° CCW: board (10, 5) + R(90)·(-3, 0) = (10, 2)
        assertTrue(ArtFixtures.inside(a.layers.cu.fill, 10, 2));
        assertTrue(ArtFixtures.inside(a.layers.paste.fill, 10, 2));
        // its 1 × 2 copper turns with the footprint (KiCad writes the absolute angle, 90): the 2 mm side runs along
        // the board's X
        assertTrue(ArtFixtures.inside(a.layers.cu.fill, 10 + 0.9, 2));
        assertFalse(ArtFixtures.inside(a.layers.cu.fill, 10, 2 + 0.6));
        // pad 2: a 1 mm circle with a -0.1 mm mask margin: the opening (0.8 mm) is the exposed copper
        List<double[]> exposedCircles = ArtFixtures.circles(a.layers.exposed.fill);
        boolean shrunk = false;
        for (double[] c : exposedCircles) {
            shrunk |= Math.hypot(c[0] - 10, c[1] - 8) < 0.01 && Math.abs(c[2] - 0.4) < 0.006;
        }
        assertTrue(shrunk, "exposed = the shrunk opening");
        // the board's 0.05 mm clearance grows the rectangle's opening, with corners of radius 0.05
        assertTrue(a.layers.mask.open.contains("A0.05 0.05"), a.layers.mask.open);
        // the oval hole and the unplated hole; the unplated hole has no copper (pad = drill)
        assertEquals(1, ArtPath.parse(a.layers.holes.pth).size());
        assertEquals(1, ArtFixtures.circles(a.layers.holes.npth).size());
        double[] npth = ArtFixtures.circles(a.layers.holes.npth).get(0);
        assertFalse(ArtFixtures.inside(a.layers.cu.fill, npth[0], npth[1]));
        assertTrue(ArtFixtures.inside(a.layers.mask.open, npth[0], npth[1]));
        // vias (board Y = −KiCad Y): only the one through the top, untented, opens the mask; the buried one is
        // not drawn
        assertEquals(1, ArtFixtures.circles(a.layers.holes.via).size());
        assertTrue(ArtFixtures.inside(a.layers.exposed.fill, 2, 2));
        assertTrue(ArtFixtures.inside(a.layers.mask.open, 2, 2.34));
        assertFalse(ArtFixtures.inside(a.layers.cu.fill, 3, 2));
        // keep-outs: the oval hole by its copper (2 mm → 1.0), the unplated hole by its drill, the via by its size
        assertEquals(3, a.keepOutHoles.size());
        assertEquals(1.0, a.keepOutHoles.get(0)[2], 0);
        assertEquals(0.5, a.keepOutHoles.get(1)[2], 0);
        assertEquals(0.3, a.keepOutHoles.get(2)[2], 0);
        // the trapezoid is drawn as its four corners
        assertTrue(ArtFixtures.inside(a.layers.cu.fill, 10, 5));
        // the hidden reference gives no label; the part box comes from its pads
        assertNull(a.parts.get(0).label);
        assertEquals("U1", a.parts.get(0).ref);
    }
}
