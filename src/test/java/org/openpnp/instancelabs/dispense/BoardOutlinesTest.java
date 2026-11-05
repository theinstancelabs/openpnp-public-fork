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
 * imported tile), and the paste places that lie on one, within {@code outputZones.marginMm} (2 mm).
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
        c.locations.put("waste", new DispenseConfig.NamedLocation(95, 55, 5)); // off; its test dot (99, 55) is not
        c.testDotOffsetXMm = 4;
        List<BoardOutlines.Place> l = BoardOutlines.of(c).placesOnBoards();
        assertEquals("waste-test-dot", l.get(0).place);
        assertEquals("test-dot", l.get(0).kind);
        assertEquals("the purge row's spot 4 of 6 (102.0, 49.0) lies on board 'p.board.xml' (registration abcd…): set "
                + "the row again off the board (Machine › Places)", l.get(1).text);
    }
}
