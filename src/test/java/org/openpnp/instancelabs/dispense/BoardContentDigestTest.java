package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;

/** The board content digest: parts, packages and footprints a plan depends on beyond the placements. */
public class BoardContentDigestTest {
    @BeforeAll
    public static void setup() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("board-content").toFile();
        Configuration.initialize(dir);
        Configuration.get().load();
    }

    static Package r0603(String id) {
        Package p = new Package(id);
        Footprint fp = new Footprint();
        fp.setUnits(LengthUnit.Millimeters);
        fp.addPad(PlanBoardFrameTest.pad("1", -0.825, 0, 0.8, 0.95, 0, 50));
        fp.addPad(PlanBoardFrameTest.pad("2", 0.825, 0, 0.8, 0.95, 0, 50));
        p.setFootprint(fp);
        return p;
    }

    static Part part(String id, Package pkg) {
        Part p = new Part(id);
        p.setPackage(pkg);
        return p;
    }

    static Board board(Part r, Part fid) {
        Board b = new Board();
        b.addPlacement(BoardPlanningTest.placement("R1", r, 37.5, 42.5, 90));
        b.addPlacement(BoardPlanningTest.placement("R2", r, 37.5, 37.5, 90));
        Placement f = BoardPlanningTest.placement("FID1", fid, 62.5, 5, 0);
        f.setType(Placement.Type.Fiducial);
        b.addPlacement(f);
        return b;
    }

    static Board ftpLike() {
        Package fid = new Package("Fiducial_1mm_Mask3mm");
        fid.getFootprint().addPad(PlanBoardFrameTest.pad("1", 0, 0, 1, 1, 0, 100));
        return board(part("R_0603_1608Metric-R_Small", r0603("R_0603_1608Metric")),
                part("Fiducial_1mm_Mask3mm-Fiducial", fid));
    }

    @Test
    public void sameContentSameDigest() {
        String d = BoardContentDigest.of(ftpLike());
        assertEquals(16, d.length());
        assertEquals(d, BoardContentDigest.of(ftpLike()), "other objects with the same content");
    }

    @Test
    public void partIdChangesIt() {
        Board b = ftpLike();
        String before = BoardContentDigest.of(b);
        Part other = part("R_0603_1608Metric-10k", b.getPlacements().get(0).getPart().getPackage());
        b.getPlacements().get(1).setPart(other);
        assertNotEquals(before, BoardContentDigest.of(b));
    }

    @Test
    public void packageIdChangesIt() {
        Board b = ftpLike();
        String before = BoardContentDigest.of(b);
        b.getPlacements().get(0).getPart().setPackage(r0603("R_0603_1608Metric~2"));
        assertNotEquals(before, BoardContentDigest.of(b));
    }

    @Test
    public void padSizeAndRoundnessChangeIt() {
        Board b = ftpLike();
        String before = BoardContentDigest.of(b);
        Footprint.Pad pad = b.getPlacements().get(0).getPart().getPackage().getFootprint().getPads().get(0);
        pad.setWidth(0.75); // e.g. a paste margin of -0.025 mm
        String narrower = BoardContentDigest.of(b);
        assertNotEquals(before, narrower);
        pad.setWidth(0.8);
        assertEquals(before, BoardContentDigest.of(b));
        pad.setRoundness(0);
        assertNotEquals(before, BoardContentDigest.of(b));
        pad.setRoundness(50);
        // the fiducial's footprint counts too
        b.getPlacements().get(2).getPart().getPackage().getFootprint().getPads().get(0).setWidth(1.5);
        assertNotEquals(before, BoardContentDigest.of(b));
    }

    /** Each pad field the digest names (name, x, y, w, h, rotation, roundness) and the pad count. */
    @Test
    public void everyPadFieldChangesIt() {
        List<Consumer<Footprint.Pad>> edits = List.of(p -> p.setName("9"), p -> p.setX(p.getX() + 0.001),
                p -> p.setY(p.getY() + 0.001), p -> p.setWidth(p.getWidth() + 0.001),
                p -> p.setHeight(p.getHeight() + 0.001), p -> p.setRotation(p.getRotation() + 90),
                p -> p.setRoundness(p.getRoundness() + 1));
        String before = BoardContentDigest.of(ftpLike());
        for (int i = 0; i < edits.size(); i++) {
            Board b = ftpLike();
            edits.get(i).accept(b.getPlacements().get(0).getPart().getPackage().getFootprint().getPads().get(1));
            assertNotEquals(before, BoardContentDigest.of(b), "pad edit " + i);
        }
        Board b = ftpLike();
        b.getPlacements().get(0).getPart().getPackage().getFootprint()
                .addPad(PlanBoardFrameTest.pad("3", 0, 0, 0.5, 0.5, 0, 0));
        assertNotEquals(before, BoardContentDigest.of(b), "an added pad");
    }

    @Test
    public void footprintUnitsChangeIt() {
        Board b = ftpLike();
        String before = BoardContentDigest.of(b);
        b.getPlacements().get(0).getPart().getPackage().getFootprint().setUnits(LengthUnit.Inches);
        assertNotEquals(before, BoardContentDigest.of(b));
    }

    /** The digest reads the board's parts, never the order of OpenPnP's package and part lists. */
    @Test
    public void reorderingTheConfigurationDoesNot() {
        Configuration config = Configuration.get();
        Package a = r0603("A_0603");
        Package z = r0603("Z_0603");
        Part pa = part("A_0603-1k", a);
        Part pz = part("Z_0603-1k", z);
        config.addPackage(z);
        config.addPackage(a);
        config.addPart(pz);
        config.addPart(pa);
        Board b = board(config.getPart("A_0603-1k"), config.getPart("Z_0603-1k"));
        String before = BoardContentDigest.of(b);
        config.removePart(pz);
        config.removePart(pa);
        config.removePackage(z);
        config.removePackage(a);
        config.addPackage(a);
        config.addPackage(z);
        config.addPart(pa);
        config.addPart(pz);
        assertEquals(before, BoardContentDigest.of(board(config.getPart("A_0603-1k"), config.getPart("Z_0603-1k"))));
        config.removePart(pz);
        config.removePart(pa);
        config.removePackage(z);
        config.removePackage(a);
    }

    @Test
    public void placementsWithoutAPartAreFine() {
        Board b = new Board();
        Placement p = new Placement("X1");
        b.addPlacement(p);
        assertEquals(16, BoardContentDigest.of(b).length());
        assertEquals(BoardContentDigest.of(null), BoardContentDigest.of(new Board()));
    }
}
