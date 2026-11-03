package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.PlacementsHolderLocation.PlacementsTransformStatus;
import org.openpnp.util.Utils2D;

/** The registration record: applied to a copy only, a stable digest, JSON, identity and movement. */
public class BoardRegistrationTest {
    /** The bench's current affine (runs/board_registration.json): scales 1.0011 / 0.9946, a small shear. */
    static final double[] BENCH = {1.00112, -0.0057, 0.00103, 0.9946, 295.99, 114.52};

    @BeforeAll
    public static void setup() throws Exception {
        BoardFeaturesTest.setup();
    }

    static BoardLocation nominal() {
        BoardLocation bl = new BoardLocation(BoardFeaturesTest.ftp());
        bl.setLocation(new Location(LengthUnit.Millimeters, 296.0, 114.5, 5.5, 0));
        return bl;
    }

    static BoardRegistration reg(BoardLocation bl, String key, boolean job) {
        BoardRegistration r = new BoardRegistration();
        r.id = "3f2a91c0-0000-4000-8000-000000000001";
        r.boardKey = key;
        r.boardName = "ftp.board.xml";
        r.nominal = BoardRegistration.Nominal.of(bl, job);
        r.affine = BENCH.clone();
        r.method = "affine-3";
        r.atMs = 1_793_200_000_000L;
        r.fiducials = new ArrayList<>(Arrays.asList(fid("FID1", 358.67, 117.31), fid("FID2", 308.89, 184.30),
                fid("FID3", 358.94, 184.07)));
        // the board coordinates of ftp's fiducials (BoardFeaturesTest.ftp)
        double[][] board = {{62.4465812, 4.69103014}, {12.5, 70.0}, {62.5, 70.0}};
        for (int i = 0; i < 3; i++) {
            r.fiducials.get(i).bx = board[i][0];
            r.fiducials.get(i).by = board[i][1];
        }
        return r;
    }

    static BoardRegistration.Fid fid(String name, double x, double y) {
        BoardRegistration.Fid f = new BoardRegistration.Fid();
        f.name = name;
        f.x = x;
        f.y = y;
        return f;
    }

    @Test
    public void appliedToACopyOnly() throws Exception {
        BoardLocation bl = nominal();
        AffineTransform before = bl.getLocalToGlobalTransform();
        BoardRegistration r = reg(bl, BoardRegistration.fileKey(new File("/tmp/ftp.board.xml")), false);
        BoardLocation c = r.applyTo(bl);
        assertEquals(PlacementsTransformStatus.LocallySet, c.getPlacementsTransformStatus());
        assertEquals(PlacementsTransformStatus.NotSet, bl.getPlacementsTransformStatus(), "the source is untouched");
        assertEquals(before, bl.getLocalToGlobalTransform());
        assertEquals(r.transform(), c.getLocalToGlobalTransform());
        assertEquals(5.5, c.getGlobalLocation().convertToUnits(LengthUnit.Millimeters).getZ(), 1e-9,
                "Z stays the nominal: dot Z comes from the probed plane");
        // pads follow the affine
        Location fid1 = Utils2D.calculateBoardPlacementLocation(c, new Location(LengthUnit.Millimeters, 62.4465812,
                4.69103014, 0, 0));
        Point2D p = r.transform().transform(new Point2D.Double(62.4465812, 4.69103014), null);
        assertEquals(p.getX(), fid1.getX(), 1e-9);
        assertEquals(p.getY(), fid1.getY(), 1e-9);
        assertTrue(r.isAffineValid());
    }

    @Test
    public void theSameRecordGivesTheSameDigest() throws Exception {
        BoardLocation bl = nominal();
        BoardRegistration r = reg(bl, "file:/tmp/ftp.board.xml", false);
        String a = DispenseService.boardRegistration(r.applyTo(bl));
        r.lastCheckedAtMs = r.atMs + 60_000;
        r.lastCheckMovedMm = 0.02;
        assertEquals(a, DispenseService.boardRegistration(r.applyTo(bl)), "the same-seat stamps are not in it");
        assertNotEquals(a, DispenseService.boardRegistration(bl), "unregistered is another registration");
        r.affine[4] += 0.01;
        assertNotEquals(a, DispenseService.boardRegistration(r.applyTo(bl)));
    }

    @Test
    public void jsonRoundTrip() {
        BoardRegistration r = reg(nominal(), "job:Brd1", true);
        r.simulated = true;
        r.runId = "run-1";
        DispenseConfig c = new DispenseConfig();
        c.putBoardRegistration(r);
        DispenseConfig back = DispenseConfig.fromJson(c.toJson());
        BoardRegistration b = back.boardRegistrations.get("job:Brd1");
        assertEquals(r.id, b.id);
        assertTrue(Arrays.equals(r.affine, b.affine));
        assertEquals(r.nominal.openPnpTransform, b.nominal.openPnpTransform);
        assertEquals("FID3", b.fiducials.get(2).name);
        assertTrue(b.simulated);
        assertEquals(c.toJson(), back.toJson());
        for (Map.Entry<String, BoardRegistration> e : back.boardRegistrations.entrySet()) {
            assertTrue(e.getValue().matches(e.getKey(), r.nominal, BoardFeaturesTest.ftp()));
        }
    }

    @Test
    public void matchesAndMovement() throws Exception {
        BoardLocation bl = nominal();
        BoardRegistration file = reg(bl, "file:/a.board.xml", false);
        assertNull(file.nominal.openPnpTransform);
        assertTrue(file.matches("file:/a.board.xml", BoardRegistration.Nominal.of(bl, false), bl.getBoard()));
        assertFalse(file.matches("file:/b.board.xml", BoardRegistration.Nominal.of(bl, false), bl.getBoard()));
        BoardLocation moved = new BoardLocation(bl);
        moved.setLocation(new Location(LengthUnit.Millimeters, 296.001, 114.5, 5.5, 0));
        assertFalse(file.matches("file:/a.board.xml", BoardRegistration.Nominal.of(moved, false), bl.getBoard()));
        BoardLocation z = new BoardLocation(bl);
        z.setLocation(new Location(LengthUnit.Millimeters, 296.0, 114.5, 5.50005, 0));
        assertTrue(file.matches("file:/a.board.xml", BoardRegistration.Nominal.of(z, false), bl.getBoard()),
                "within 1e-4");
        // a job board carries OpenPnP's own transform: OpenPnP's later fiducial check is noticed
        BoardRegistration job = reg(bl, "job:Brd1", true);
        assertTrue(job.nominal.openPnpTransform.startsWith("NotSet;"), job.nominal.openPnpTransform);
        assertTrue(job.matches("job:Brd1", BoardRegistration.Nominal.of(bl, true), bl.getBoard()));
        BoardLocation checked = new BoardLocation(bl);
        AffineTransform t = checked.getLocalToGlobalTransform();
        t.translate(0.2, 0);
        checked.setLocalToGlobalTransform(t);
        assertFalse(job.matches("job:Brd1", BoardRegistration.Nominal.of(checked, true), bl.getBoard()));
        String why = job.refusal("job:Brd1", BoardRegistration.Nominal.of(checked, true), bl.getBoard(), () -> true);
        assertEquals("REG: OpenPnP's fiducial check of this job board changed since registration 3f2a… ("
                + ZCheck.clock(job.atMs) + "): register again", why);
        assertEquals("REG: registration 3f2a… belongs to another board or position",
                job.refusal("job:Brd1", BoardRegistration.Nominal.of(moved, true), bl.getBoard(), () -> true));
        assertNull(job.refusal("job:Brd1", BoardRegistration.Nominal.of(bl, true), bl.getBoard(), () -> false));
        // movement over the fiducials both found
        List<BoardRegistration.Fid> now = Arrays.asList(fid("FID1", 358.67 + 0.03, 117.31 - 0.04),
                fid("FID3", 358.94, 184.07 + 0.02), fid("FID9", 0, 0));
        assertEquals(0.05, file.maxMovedMm(now), 1e-9);
        assertTrue(Double.isNaN(file.maxMovedMm(Arrays.asList(fid("FID9", 0, 0)))));
    }

    /**
     * F8: a plan made again with only the board Z changed (4.50 → 5.35 after I4) keeps the registration, as
     * {@link DispenseService#boardRegistration} does: editing the nominal Z does not move the board.
     */
    @Test
    public void theBoardZIsNotPartOfTheSeat() throws Exception {
        BoardLocation bl = nominal();
        bl.setLocation(new Location(LengthUnit.Millimeters, 296.0, 114.5, 4.50, 0));
        BoardLocation z = new BoardLocation(bl);
        z.setLocation(new Location(LengthUnit.Millimeters, 296.0, 114.5, 5.35, 0));
        for (boolean job : new boolean[] {false, true}) {
            String key = job ? "job:Brd1" : "file:/a.board.xml";
            BoardRegistration r = reg(bl, key, job);
            BoardRegistration.Nominal n = BoardRegistration.Nominal.of(z, job);
            assertTrue(r.matches(key, n, bl.getBoard()), key);
            assertNull(r.refusal(key, n, bl.getBoard(), () -> false), key);
            assertEquals(5.35, r.applyTo(z).getGlobalLocation().convertToUnits(LengthUnit.Millimeters).getZ(), 1e-9,
                    "the plan's own board Z");
        }
        assertEquals(DispenseService.boardRegistration(bl), DispenseService.boardRegistration(z));
    }

    @Test
    public void aSimulatorRecordIsRefusedUnlessTheGuardSaysSimulator() {
        BoardLocation bl = nominal();
        BoardRegistration r = reg(bl, "file:/a.board.xml", false);
        BoardRegistration.Nominal n = BoardRegistration.Nominal.of(bl, false);
        r.simulated = true;
        assertEquals("REG: registration 3f2a… was made on the simulator: register again",
                r.refusal("file:/a.board.xml", n, bl.getBoard(), () -> false));
        assertNull(r.refusal("file:/a.board.xml", n, bl.getBoard(), () -> true));
        r.simulated = false;
        assertNull(r.refusal("file:/a.board.xml", n, bl.getBoard(), () -> false));
        assertEquals("3f2a…", r.shortId());
    }

    /**
     * Board-import design §10.2 item 1: a board file replaced or edited by hand that moved a fiducial
     * must not keep the old affine. The record matches only while every stored fiducial is the board's
     * top, enabled Fiducial placement of the same name within 1e-4 mm.
     */
    @Test
    public void aMovedOrGoneFiducialEndsTheMatch() {
        BoardLocation bl = nominal();
        BoardRegistration r = reg(bl, "file:/a.board.xml", false);
        BoardRegistration.Nominal n = BoardRegistration.Nominal.of(bl, false);
        org.openpnp.model.Board board = bl.getBoard();
        assertTrue(r.matches("file:/a.board.xml", n, board));
        assertTrue(r.fiducialsMatch(board));
        org.openpnp.model.Placement fid2 = null;
        for (org.openpnp.model.Placement p : board.getPlacements()) {
            fid2 = "FID2".equals(p.getId()) ? p : fid2;
        }
        Location was = fid2.getLocation();
        fid2.setLocation(was.add(new Location(LengthUnit.Millimeters, 0.00005, -0.00005, 0, 0)));
        assertTrue(r.matches("file:/a.board.xml", n, board), "within 1e-4 mm");
        fid2.setLocation(was.add(new Location(LengthUnit.Millimeters, 0.0002, 0, 0, 0)));
        assertFalse(r.matches("file:/a.board.xml", n, board), "FID2 moved 0.0002 mm");
        assertEquals("REG: FID2 moved or is gone in the board since registration 3f2a… (" + ZCheck.clock(r.atMs)
                + "): register again", r.refusal("file:/a.board.xml", n, board, () -> true));
        fid2.setLocation(was);
        assertTrue(r.matches("file:/a.board.xml", n, board));
        // disabled, turned into a placement, or on the bottom: no longer a top, enabled fiducial
        fid2.setEnabled(false);
        assertFalse(r.matches("file:/a.board.xml", n, board));
        fid2.setEnabled(true);
        fid2.setType(org.openpnp.model.Placement.Type.Placement);
        assertFalse(r.matches("file:/a.board.xml", n, board));
        fid2.setType(org.openpnp.model.Placement.Type.Fiducial);
        fid2.setSide(org.openpnp.model.Abstract2DLocatable.Side.Bottom);
        assertFalse(r.matches("file:/a.board.xml", n, board));
        fid2.setSide(org.openpnp.model.Abstract2DLocatable.Side.Top);
        // gone from the board (renamed), or no board at all
        fid2.setId("FID2b");
        assertFalse(r.matches("file:/a.board.xml", n, board));
        fid2.setId("FID2");
        assertFalse(r.matches("file:/a.board.xml", n, null), "no board: nothing vouches for the fiducials");
        // a fiducial added to the board does not matter: only the stored ones are checked
        org.openpnp.model.Placement extra = new org.openpnp.model.Placement("FID4");
        extra.setType(org.openpnp.model.Placement.Type.Fiducial);
        extra.setLocation(new Location(LengthUnit.Millimeters, 5, 5, 0, 0));
        board.addPlacement(extra);
        assertTrue(r.matches("file:/a.board.xml", n, board));
    }
}
