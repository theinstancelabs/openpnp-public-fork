package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * BR-81: the outline of every board on the bed in machine X/Y (each registration's board through its affine, each
 * imported tile), and the paste places that lie on one, within {@code outputZones.marginMm} (2 mm); a sink (the waste,
 * the towel, a cup) keeps its circle ({@code placeRadiusMm}, 6 mm) more. I13 refuses the runs that use one.
 */
public class BoardOutlinesTest {
    /** A registration of a 20 x 10 mm board with this board -> machine affine. */
    static BoardRegistration reg(String name, double... affine) {
        BoardRegistration r = new BoardRegistration();
        r.id = "abcd-" + name;
        r.boardKey = "file:/" + name;
        r.boardName = name;
        r.affine = affine;
        r.boardRectMm = new double[] {0, 0, 20, 10};
        return r;
    }

    static DispenseConfig with(BoardRegistration... regs) {
        DispenseConfig c = new DispenseConfig();
        for (BoardRegistration r : regs) {
            c.putBoardRegistration(r);
        }
        return c;
    }

    static List<double[]> poly(double... xy) {
        List<double[]> l = new ArrayList<>();
        for (int i = 0; i + 1 < xy.length; i += 2) {
            l.add(new double[] {xy[i], xy[i + 1]});
        }
        return l;
    }

    @Test
    public void aRotatedBoardsOutlineFollowsItsAffineWithTheMargin() {
        // 90 degrees: board (bx, by) -> machine (100 - by, 50 + bx): x 90..100, y 50..70
        BoardOutlines b = BoardOutlines.of(with(reg("r.board.xml", 0, 1, -1, 0, 100, 50)));
        assertEquals("board 'r.board.xml' (registration abcd…)", b.on(95, 60, 2));
        assertNotNull(b.on(101.9, 60, 2), "1.9 mm off its edge: inside the margin");
        assertNull(b.on(102.1, 60, 2), "2.1 mm off");
        assertNull(b.on(105, 52, 2), "where an unrotated board would lie");
        assertNull(b.on(101, 60, 0), "no margin: 1 mm off is off");
    }

    @Test
    public void aBottomSideBoardIsMirroredByItsAffine() {
        // mirrored X: board (bx, by) -> machine (100 - bx, 50 + by): x 80..100, y 50..60
        BoardOutlines b = BoardOutlines.of(with(reg("m.board.xml", -1, 0, 0, 1, 100, 50)));
        assertNotNull(b.on(85, 55, 2));
        assertNull(b.on(110, 55, 2), "where the board would lie unmirrored");
    }

    @Test
    public void aShearedAffineGivesAParallelogram() {
        // x' = 100 + bx + 0.5 by, y' = 50 + by: the top edge (by = 10) runs x 105..125
        BoardOutlines b = BoardOutlines.of(with(reg("s.board.xml", 1, 0, 0.5, 1, 100, 50)));
        assertNotNull(b.on(124, 59, 1));
        assertNull(b.on(101, 59, 1), "left of the slanted edge (x 105 at y 60, 104.5 at y 59)");
    }

    /** A record stored before BR-81 has no board size: its fiducials' box stands for it, a smaller outline. */
    @Test
    public void aRegistrationWithoutItsBoardSizeUsesItsFiducialsBox() {
        BoardRegistration r = reg("old.board.xml", 1, 0, 0, 1, 100, 50);
        r.boardRectMm = null;
        for (double[] f : new double[][] {{2, 2}, {18, 8}, {2, 8}}) {
            BoardRegistration.Fid fid = new BoardRegistration.Fid();
            fid.bx = f[0];
            fid.by = f[1];
            r.fiducials.add(fid);
        }
        BoardOutlines b = BoardOutlines.of(with(r));
        assertNotNull(b.on(100.5, 55, 2), "1.5 mm off the fiducials' box x 102..118");
        assertNull(b.on(99.5, 55, 2), "2.5 mm off it, though on the board");
        Map<String, Object> v = b.view().get(0);
        assertEquals(true, v.get("fromFiducials"), v.toString());
        assertEquals("registration", v.get("kind"), v.toString());
    }

    static DispenseConfig.Tile tile() {
        DispenseConfig.Tile t = new DispenseConfig.Tile();
        t.label = "Nano tile";
        t.outline = poly(60, 30, 120, 30, 120, 60, 60, 60);
        String[] names = {"A", "B", "C"};
        for (int i = 0; i < 3; i++) {
            DispenseConfig.TileBoard tb = new DispenseConfig.TileBoard();
            tb.name = names[i];
            double x = 62 + 20 * i;
            tb.outline = poly(x, 32, x + 16, 32, x + 16, 58, x, 58);
            t.boards.add(tb);
        }
        t.checkPoints = poly(70, 45, 90, 45, 110, 45);
        return t;
    }

    @Test
    public void aTileNamesTheNearestOfItsBoards() {
        DispenseConfig c = new DispenseConfig();
        c.tiles.put("nano", tile());
        BoardOutlines b = BoardOutlines.of(c);
        assertEquals("the Nano tile (board B)", b.on(80.5, 45, 2), "in the gap, nearer B");
        assertEquals("the Nano tile (board A)", b.on(79.5, 45, 2), "in the gap, nearer A");
        assertEquals("the Nano tile (board C)", b.on(121, 45, 2), "1 mm off the tile's edge");
        assertNull(b.on(123, 45, 2));
        // a box (the calibration area, a coupon) that overlaps the edge, one that holds the tile, one 3 mm off it
        assertNotNull(b.on(50, 40, 61, 42, 2));
        assertNotNull(b.on(0, 0, 200, 200, 2));
        assertNull(b.on(50, 40, 57, 42, 2));
        assertEquals("tile", b.view().get(0).get("kind"));
    }

    @Test
    public void aTileThatDoesNotHoldIsRefused() {
        assertNull(tile().problem());
        DispenseConfig.Tile t = tile();
        t.outline = poly(60, 30, 120, 60, 120, 30, 60, 60);
        assertTrue(String.valueOf(t.problem()).contains("crosses itself"), t.problem());
        t = tile();
        t.checkPoints = poly(70, 45, 90, 45, 130, 45);
        assertTrue(String.valueOf(t.problem()).contains("check point 3"), t.problem());
        t = tile();
        t.boards.get(2).outline = poly(202, 32, 218, 32, 218, 58, 202, 58);
        assertTrue(String.valueOf(t.problem()).contains("board C"), t.problem());
        t = tile();
        t.outline = poly(60, 30, 120, 30, Double.NaN, 60);
        assertNotNull(t.problem());
        DispenseConfig c = new DispenseConfig();
        c.tiles.put("nano", t);
        assertTrue(c.validate().toString().contains("tiles['nano']"), c.validate().toString());
    }

    /**
     * The paste places on a board, each once: the waste (its test-dot spot only when the waste itself is off), the
     * purge row by its first spot on a board, the calibration area and a coupon by their boxes; the towel is off.
     */
    @Test
    public void thePastePlacesOnABoardAreListedEachOnce() {
        DispenseConfig c = with(reg("p.board.xml", 1, 0, 0, 1, 100, 50)); // x 100..120, y 50..60
        c.locations.put("waste", new DispenseConfig.NamedLocation(110, 55, 5));
        c.locations.put("towel", new DispenseConfig.NamedLocation(150, 55, 5));
        c.locations.put("coupon1", new DispenseConfig.NamedLocation(140, 70, 5)); // 40 x 30: x 120..160, y 55..85
        c.purgeRow.x0Mm = 90.0;
        c.purgeRow.y0Mm = 45.0;
        c.purgeRow.pitchXMm = 4;
        c.purgeRow.spots = 6; // (90, 45) .. (110, 45): spot 4 (102, 45) is 5 mm below the board, none on it
        c.calibrationArea.x0Mm = 30.0;
        c.calibrationArea.y0Mm = 40.0;
        c.calibrationArea.widthMm = 69.0; // its edge x 99, 1 mm off the board
        c.calibrationArea.heightMm = 20.0;
        List<String> got = new ArrayList<>();
        for (BoardOutlines.Place p : BoardOutlines.of(c).placesOnBoards()) {
            got.add(p.place + "/" + p.kind);
        }
        assertEquals(Arrays.asList("waste/place", "calibration area/calibration-area", "coupon1/coupon"), got);
        c.purgeRow.y0Mm = 49.0; // 1 mm below the board: each spot from x 102 on (98 is 2.2 mm from its corner)
        c.locations.put("waste", new DispenseConfig.NamedLocation(91, 55, 5)); // 9 mm off; its test dot (99, 55) is not
        c.testDotOffsetXMm = 8;
        List<BoardOutlines.Place> l = BoardOutlines.of(c).placesOnBoards();
        assertEquals("waste-test-dot", l.get(0).place);
        assertEquals("test-dot", l.get(0).kind);
        assertEquals("the purge row's spot 4 of 6 (102.0, 49.0) lies on board 'p.board.xml' (registration abcd…): set "
                + "the row again off the board (Machine › Places)", l.get(1).text);
    }

    /** A sink keeps its 6 mm circle and the 2 mm margin off a board (Process, 11-04): its pile spreads; the blank does not. */
    @Test
    public void aSinkKeepsItsCircleAndTheMarginOffABoard() {
        DispenseConfig c = with(reg("s.board.xml", 1, 0, 0, 1, 100, 50)); // x 100..120, y 50..60
        c.locations.put("towel", new DispenseConfig.NamedLocation(125, 55, 5)); // 5 mm off
        c.locations.put("cup2", new DispenseConfig.NamedLocation(129, 55, 5)); // 9 mm off
        c.locations.put("blank", new DispenseConfig.NamedLocation(110, 65, 5)); // 5 mm off, no sink
        List<BoardOutlines.Place> l = BoardOutlines.of(c).placesOnBoards();
        assertEquals(1, l.size(), l.toString());
        assertEquals("the place 'towel' (125.0, 55.0) lies within 8.0 mm of board 's.board.xml' (registration abcd…) "
                + "(its 6.0 mm circle and the 2.0 mm margin): set it again off the board (Machine › Places)", l.get(0).text);
    }

    /**
     * A place with its own probed surface takes a dot ladder's or a touch-off's dots (the plan's 'tladder'): a paste
     * place. The board surface's namesake is the board's own place, never one.
     */
    @Test
    public void aPlaceWithItsOwnSurfaceIsAPastePlaceButNotTheBoardSurfaces() {
        DispenseConfig c = with(reg("t.board.xml", 1, 0, 0, 1, 100, 50));
        c.locations.put("tladder", new DispenseConfig.NamedLocation(105, 52, 5));
        c.surfaces.put("tladder", OutputZonesTest.patch(100, 50, 110, 50, 105, 58));
        c.locations.put("board", new DispenseConfig.NamedLocation(110, 55, 5));
        c.surfaces.put("board", OutputZonesTest.patch(100, 50, 120, 50, 110, 60));
        c.locations.put("park", new DispenseConfig.NamedLocation(112, 55, 31)); // no surface of its own: no paste
        List<String> got = new ArrayList<>();
        for (BoardOutlines.Place p : BoardOutlines.of(c).placesOnBoards()) {
            got.add(p.place + "/" + p.kind);
        }
        assertEquals(Arrays.asList("tladder/place"), got);
    }

    /** An outline that is not a number (an old record's fiducial) refuses every point: it fails closed. */
    @Test
    public void aNaNOutlineFailsClosed() {
        BoardRegistration r = reg("nan.board.xml", 1, 0, 0, 1, 100, 50);
        r.boardRectMm = null;
        BoardRegistration.Fid fid = new BoardRegistration.Fid();
        fid.bx = Double.NaN;
        fid.by = 2;
        r.fiducials.add(fid);
        assertNotNull(BoardOutlines.of(with(r)).on(500, 500, 2));
    }

    @Test
    public void aRegistrationsBoardRectIsValidated() {
        DispenseConfig c = with(reg("v.board.xml", 1, 0, 0, 1, 100, 50));
        BoardRegistration r = c.boardRegistrations.get("file:/v.board.xml");
        assertTrue(c.validate().isEmpty(), c.validate().toString());
        for (double[] bad : new double[][] {{0, 0, Double.NaN, 10}, {5, 0, 1, 10}, {0, 0, 20}}) {
            r.boardRectMm = bad;
            assertTrue(c.validate().toString().contains("boardRectMm"), Arrays.toString(bad));
        }
        r.boardRectMm = new double[] {0, 0, 0, 10}; // a board of one column of pads: a strip
        assertTrue(c.validate().isEmpty(), c.validate().toString());
    }

    /** Register board's outline of a board without its size: the box of its pads' and fiducials' centres. */
    @Test
    public void aBoardWithoutItsSizeIsTheBoxOfItsPadsAndFiducials() {
        BoardFeatures f = new BoardFeatures();
        assertNull(f.rectMm());
        BoardFeatures.Pad a = new BoardFeatures.Pad(), b = new BoardFeatures.Pad();
        a.x = 2;
        a.y = 3;
        b.x = 10;
        b.y = 1;
        f.pads.add(a);
        f.pads.add(b);
        BoardFeatures.Fiducial fd = new BoardFeatures.Fiducial();
        fd.x = 12;
        fd.y = 8;
        f.fiducials.add(fd);
        assertEquals(Arrays.toString(new double[] {2, 1, 12, 8}), Arrays.toString(f.rectMm()));
        f.widthMm = 20;
        f.heightMm = 10;
        assertEquals(Arrays.toString(new double[] {0, 0, 20, 10}), Arrays.toString(f.rectMm()));
    }

    static List<String> i13(DispenseConfig c, Interlocks.Scope sc, int purgeDots) {
        Interlocks.Inputs in = new Interlocks.Inputs();
        in.cfg = c;
        in.scope = sc;
        in.purgeDots = purgeDots;
        InterlockReport r = new InterlockReport();
        Interlocks.checkPlacesOnBoards(in, r);
        List<String> l = new ArrayList<>();
        for (InterlockReport.Violation v : r.violations) {
            l.add(v.toString());
        }
        return l;
    }

    /**
     * I13 refuses the runs that use a place on a board: the calibration area when a dot of the run lies in it (never a
     * board run's), a coupon the run names, the purge row when it lays purge dots, and a board run's own planned dots
     * on an outline recorded after its registration (or under a plan without one).
     */
    @Test
    public void i13RefusesTheRunsThatUseAPlaceOnABoard() {
        BoardRegistration reg = reg("i.board.xml", 1, 0, 0, 1, 100, 50); // x 100..120, y 50..60
        reg.atMs = 1000;
        DispenseConfig c = with(reg);
        c.calibrationArea.x0Mm = 60.0;
        c.calibrationArea.y0Mm = 40.0;
        c.calibrationArea.widthMm = 41.0; // its edge x 101, on the board
        c.calibrationArea.heightMm = 30.0;
        c.locations.put("coupon1", new DispenseConfig.NamedLocation(140, 55, 5)); // 40 x 30: x 120..160
        String board = "board 'i.board.xml' (registration abcd…)";
        Interlocks.Scope blocks = new Interlocks.Scope();
        blocks.surfaceName = "calib";
        blocks.surfaceTargets.add(new double[] {70, 45}); // in the area, off the board
        assertEquals(Arrays.asList("I13: the calibration area (60.0..101.0, 40.0..70.0) lies on " + board + ": move it "
                + "off the board (Calibrate › Pad calibration)"), i13(c, blocks, 0));
        Interlocks.Scope weigh = new Interlocks.Scope();
        weigh.pastePlaces.add("coupon1");
        assertEquals(Arrays.asList("I13: the coupon place 'coupon1' (140.0, 55.0) lies on " + board + ": set the coupon "
                + "place again off the board (Machine › Places)"), i13(c, weigh, 0));
        assertEquals(new ArrayList<String>(), i13(c, new Interlocks.Scope(), 0), "a run that names neither");

        Interlocks.Scope run = new Interlocks.Scope();
        run.checkPlanSurface = true;
        run.surfaceTargets.add(new double[] {70, 45}); // in the area: not the area's run
        run.surfaceTargets.add(new double[] {110, 55}); // on the board
        run.outlinesUpToMs = 1000L;
        assertEquals(new ArrayList<String>(), i13(c, run, 0), "its registration knew the board");
        run.outlinesUpToMs = 999L;
        assertEquals(Arrays.asList("I13: the plan's dot at (110.0, 55.0) lies on " + board + ", recorded after the "
                + "plan's board registration (the bed changed since): register the board again (Register board)"),
                i13(c, run, 0));
        run.outlinesUpToMs = null;
        assertEquals(Arrays.asList("I13: the plan's dot at (110.0, 55.0) lies on " + board + ", and the plan has no "
                + "board registration: register the board (Register board)"), i13(c, run, 0));
        // a plan without a registration: its own board's stored registration alone, never a tile over it
        run.ownOutlineId = reg.id;
        assertEquals(new ArrayList<String>(), i13(c, run, 0), "its own board's registration");
        c.tiles.put("nano", tile()); // x 60..120, y 30..60: over both dots
        List<String> onTile = i13(c, run, 0);
        assertTrue(onTile.size() == 1 && onTile.get(0).contains(") lies on the Nano tile (board ")
                && onTile.get(0).endsWith(", and the plan has no board registration: register the board (Register "
                        + "board)"), onTile.toString());
        c.tiles.clear();
        run.ownOutlineId = null;
        run.registrationGone = true; // made with a registration that was replaced or forgotten since
        assertEquals(Arrays.asList("I13: the plan's dot at (110.0, 55.0) lies on " + board + ", and the plan's board "
                + "registration is gone (registered again or forgotten): plan the board again"), i13(c, run, 0));
    }

    /** An outline's or a check's stamp is never older than one stored, whatever the clock did since (a set back). */
    @Test
    public void anOutlineStampIsNeverOlderThanTheStoredOnes() {
        BoardRegistration r = reg("c.board.xml", 1, 0, 0, 1, 100, 50);
        r.atMs = 5000;
        r.lastCheckedAtMs = 9000L;
        DispenseConfig c = with(r);
        DispenseConfig.Tile t = tile();
        t.atMs = 7000;
        c.tiles.put("nano", t);
        assertEquals(9001, c.outlineStampMs(1000), "the clock set back: after the newest stamp");
        t.atMs = 12000;
        assertEquals(12001, c.outlineStampMs(1000), "a tile's stamp counts too");
        assertEquals(20000, c.outlineStampMs(20000), "the clock past them all: now");
    }

    /**
     * Which outlines a board run's planned dots may lie on, from its plan: a plan made with a registration, those up to
     * its last check; one made without, its own board's stored registration (by its key) alone; one whose registration
     * is gone, none.
     */
    @Test
    public void aBoardRunsScopeCarriesTheOutlinesItsPlanCovers() {
        BoardRegistration reg = reg("k.board.xml", 1, 0, 0, 1, 100, 50);
        reg.atMs = 1000;
        reg.lastCheckedAtMs = 3000L;
        DispenseConfig c = with(reg);
        DispensePlan plan = new DispensePlan();
        plan.registrationId = reg.id;
        Interlocks.Scope sc = DispenseService.boardScope(c, plan);
        assertEquals(Long.valueOf(3000), sc.outlinesUpToMs, "its registration's last check");
        plan.registrationId = null;
        plan.boardKey = reg.boardKey;
        sc = DispenseService.boardScope(c, plan);
        assertNull(sc.outlinesUpToMs);
        assertEquals(reg.id, sc.ownOutlineId, "its own board's registration");
        plan.boardKey = "file:/another.board.xml";
        assertNull(DispenseService.boardScope(c, plan).ownOutlineId, "another board's: none");
        plan.registrationId = "gone";
        sc = DispenseService.boardScope(c, plan);
        assertTrue(sc.registrationGone && sc.ownOutlineId == null && sc.outlinesUpToMs == null, "gone: none");
    }
}
