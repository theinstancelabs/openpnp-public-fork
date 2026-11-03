package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.geom.AffineTransform;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;

/**
 * The plan's board frame (§4.5 of the board import design): the board -> machine affine, the
 * fiducials and the pad details agree with the pads for a nominal, a rotated and a fiducial-corrected
 * (scaled and sheared) board location.
 */
public class PlanBoardFrameTest {
    static List<PadType> types;
    static File boardFile;

    @BeforeAll
    public static void setup() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("plan-board-frame").toFile();
        Configuration.initialize(dir);
        Configuration.get().load();
        boardFile = new File(dir, "frame.board.xml");
        types = PadTypeResolverExplainTest.seeds();
    }

    static Footprint.Pad pad(String name, double x, double y, double w, double h, double rot, double roundness) {
        Footprint.Pad p = new Footprint.Pad();
        p.setName(name);
        p.setX(x);
        p.setY(y);
        p.setWidth(w);
        p.setHeight(h);
        p.setRotation(rot);
        p.setRoundness(roundness);
        return p;
    }

    /**
     * A KiCad-like 0603 package with paste pads (roundrect 25 % -> roundness 50), a pad-less one
     * (pads from the pad type), a fiducial package, four fiducials (one in inches, one disabled, one on
     * the bottom).
     */
    static Board board() {
        Package r = new Package("R_0603_1608Metric~2");
        Footprint fp = new Footprint();
        fp.setUnits(LengthUnit.Millimeters);
        fp.addPad(pad("1", -0.825, 0, 0.8, 0.95, 0, 50));
        fp.addPad(pad("2", 0.825, 0, 0.8, 0.95, 0, 50));
        r.setFootprint(fp);
        Package led = new Package("LED_0603_1608Metric");
        Package fid = new Package("Fiducial_1mm_Mask3mm");
        Footprint ffp = new Footprint();
        ffp.addPad(pad("1", 0, 0, 1, 1, 0, 100));
        fid.setFootprint(ffp);
        Part pr = new Part("R_0603_1608Metric~2-R_Small");
        pr.setPackage(r);
        Part pl = new Part("LED_0603_1608Metric-LED");
        pl.setPackage(led);
        Part pf = new Part("Fiducial_1mm_Mask3mm-Fiducial");
        pf.setPackage(fid);
        Board b = new Board(boardFile);
        b.setName("frame");
        b.setDimensions(new Location(LengthUnit.Inches, 75 / 25.4, 60 / 25.4, 0, 0));
        b.addPlacement(BoardPlanningTest.placement("R1", pr, 37.5, 42.5, 90));
        b.addPlacement(BoardPlanningTest.placement("R2", pr, 12.25, 7.75, 30));
        b.addPlacement(BoardPlanningTest.placement("D1", pl, 37.5, 47.5, 0));
        b.addPlacement(fiducial("FID1", pf, 62.5, 5.0, true, Side.Top));
        Placement fid2 = fiducial("FID2", pf, 12.5, 55.0, true, Side.Top);
        fid2.setLocation(new Location(LengthUnit.Inches, 12.5 / 25.4, 55.0 / 25.4, 0, 0)); // the plan has it in mm
        b.addPlacement(fid2);
        b.addPlacement(fiducial("FID3", pf, 62.5, 55.0, false, Side.Top));
        b.addPlacement(fiducial("FID4", pf, 30.0, 30.0, true, Side.Bottom));
        return b;
    }

    static Placement fiducial(String id, Part part, double x, double y, boolean enabled, Side side) {
        Placement p = BoardPlanningTest.placement(id, part, x, y, 0);
        p.setType(Placement.Type.Fiducial);
        p.setEnabled(enabled);
        p.setSide(side);
        return p;
    }

    /** Board-frame centres of every pad, from the placements and footprints (the test's own sum). */
    static Map<String, double[]> boardFrameCentres(Board b) {
        Map<String, double[]> m = new HashMap<>();
        for (Placement p : b.getPlacements()) {
            if (p.getType() != Placement.Type.Placement) {
                continue;
            }
            Location l = p.getLocation();
            double c = Math.cos(Math.toRadians(l.getRotation())), s = Math.sin(Math.toRadians(l.getRotation()));
            List<double[]> local = new ArrayList<>();
            List<String> names = new ArrayList<>();
            Footprint fp = p.getPart().getPackage().getFootprint();
            if (fp.getPads().isEmpty()) {
                local.add(new double[] {-1.65 / 2, 0}); // chip-0603 pad type: pitch 1.65
                local.add(new double[] {1.65 / 2, 0});
                names.add("1");
                names.add("2");
            }
            for (Footprint.Pad fpad : fp.getPads()) {
                local.add(new double[] {fpad.getX(), fpad.getY()});
                names.add(fpad.getName());
            }
            for (int i = 0; i < local.size(); i++) {
                double[] o = local.get(i);
                m.put(p.getId() + "." + names.get(i),
                        new double[] {l.getX() + o[0] * c - o[1] * s, l.getY() + o[0] * s + o[1] * c});
            }
        }
        return m;
    }

    static double[] apply(double[] a, double x, double y) {
        return new double[] {a[0] * x + a[2] * y + a[4], a[1] * x + a[3] * y + a[5]};
    }

    static DispensePlan plan(BoardLocation bl) {
        DispenseConfig cfg = new DispenseConfig();
        cfg.minPadTypeStatus = "provisional";
        return new DispensePlanner(cfg, new PadTypeResolver(types, null)).planBoard(bl, 0, 0);
    }

    static BoardLocation location(Board b, double rotation) {
        BoardLocation bl = new BoardLocation(b);
        bl.setLocation(new Location(LengthUnit.Millimeters, 120, 120, 10, rotation));
        return bl;
    }

    /** The plan's affine is the location's own, and every pad and fiducial sits where it maps. */
    static void assertFrame(BoardLocation bl, String status) {
        DispensePlan plan = plan(bl);
        double[] expected = new double[6];
        bl.getLocalToGlobalTransform().getMatrix(expected);
        assertArrayEquals(expected, plan.boardToMachine, 1e-9);
        assertEquals(status, plan.boardTransformStatus);
        assertEquals(boardFile.getAbsolutePath(), plan.boardFile);
        assertEquals(75.0, plan.boardWidthMm, 1e-9);
        assertEquals(60.0, plan.boardHeightMm, 1e-9);

        Map<String, double[]> centres = boardFrameCentres(bl.getBoard());
        assertEquals(6, plan.pads.size());
        for (DispensePlan.PlannedPad p : plan.pads) {
            double[] b = centres.get(p.padId);
            assertNotNull(b, p.padId);
            double[] m = apply(plan.boardToMachine, b[0], b[1]);
            assertEquals(m[0], p.xMm, 1e-9, p.padId);
            assertEquals(m[1], p.yMm, 1e-9, p.padId);
        }
        // what the render draws: every dot (one per pad here) on the affine image of its pad's centre
        assertEquals(6, plan.dots.size());
        for (DispensePlan.PlannedDot d : plan.dots) {
            double[] b = centres.get(d.padId);
            double[] m = apply(plan.boardToMachine, b[0], b[1]);
            assertEquals(m[0], d.xMm, 1e-9, d.padId);
            assertEquals(m[1], d.yMm, 1e-9, d.padId);
        }

        assertEquals(2, plan.fiducials.size(), "top, enabled fiducials only");
        DispensePlan.BoardFiducial f1 = plan.fiducials.get(0);
        assertEquals("FID1", f1.id);
        assertEquals("Fiducial_1mm_Mask3mm-Fiducial", f1.partId);
        assertEquals(62.5, f1.bx, 1e-12);
        assertEquals(5.0, f1.by, 1e-12);
        DispensePlan.BoardFiducial f2 = plan.fiducials.get(1);
        assertEquals("FID2", f2.id);
        assertEquals(12.5, f2.bx, 1e-9, "board mm, though the board file has inches");
        assertEquals(55.0, f2.by, 1e-9);
        for (DispensePlan.BoardFiducial f : plan.fiducials) {
            double[] m = apply(plan.boardToMachine, f.bx, f.by);
            assertEquals(m[0], f.xMm, 1e-9, f.id);
            assertEquals(m[1], f.yMm, 1e-9, f.id);
        }
    }

    @Test
    public void nominalLocation() {
        BoardLocation bl = location(board(), 0);
        assertFrame(bl, "NotSet");
        assertArrayEquals(new double[] {1, 0, 0, 1, 120, 120}, plan(bl).boardToMachine, 1e-12);
    }

    @Test
    public void rotatedLocation() {
        assertFrame(location(board(), 30), "NotSet");
    }

    @Test
    public void fiducialCorrectedLocationWithScaleAndShear() throws Exception {
        BoardLocation bl = location(board(), 0);
        AffineTransform tx = new AffineTransform();
        tx.translate(120.31, 119.82);
        tx.rotate(Math.toRadians(0.7));
        tx.shear(0.0021, 0);
        tx.scale(1.0013, 0.9943);
        bl.setLocalToGlobalTransform(tx);
        assertFrame(bl, "LocallySet");
        double[] m = new double[6];
        tx.getMatrix(m);
        assertArrayEquals(m, plan(bl).boardToMachine, 1e-9);
    }

    @Test
    public void padDetailsReachThePlan() {
        DispensePlan plan = plan(location(board(), 0));
        Map<String, DispensePlan.PlannedPad> pads = new HashMap<>();
        for (DispensePlan.PlannedPad p : plan.pads) {
            pads.put(p.padId, p);
        }
        DispensePlan.PlannedPad r = pads.get("R1.2");
        assertEquals("2", r.padName);
        assertEquals("R_0603_1608Metric~2-R_Small", r.partId);
        assertEquals(50, r.roundness, 0);
        assertEquals(PadGeometry.Source.FOOTPRINT, r.source);
        assertEquals(PadTypeResolver.Resolution.PATTERN, r.typeRule);
        assertEquals("(^|[^0-9])0603([^0-9]|$)|1608Metric", r.typeRuleDetail);
        assertEquals("0603", r.typeRuleMatched);
        assertEquals(DotMath.padAreaMm2(0.8, 0.95, 50), r.areaMm2, 1e-12);
        DispensePlan.PlannedPad d = pads.get("D1.1");
        assertEquals("1", d.padName);
        assertEquals("LED_0603_1608Metric-LED", d.partId);
        assertEquals(PadGeometry.Source.PAD_TYPE, d.source);
        assertEquals("chip-0603", d.typeKey);
        assertEquals(PadTypeResolver.Resolution.PATTERN, d.typeRule);
    }

    @Test
    public void padsWithoutAPadTypeCarryTheRuleNone() {
        Board b = new Board(boardFile);
        Part u = new Part("WSON-8-X");
        u.setPackage(new Package("WSON-8"));
        b.addPlacement(BoardPlanningTest.placement("U1", u, 10, 10, 0));
        DispensePlan plan = plan(location(b, 0));
        DispensePlan.PlannedPad p = plan.pads.get(0);
        assertEquals("U1", p.padId);
        assertEquals("WSON-8-X", p.partId);
        assertNull(p.padName);
        assertEquals(PadTypeResolver.Resolution.NONE, p.typeRule);
        assertEquals(0, plan.fiducials.size());
        assertEquals(0, plan.boardWidthMm, 0);
    }

    @Test
    public void copyBoardFieldsToCopiesEveryNewField() {
        DispensePlan p = plan(location(board(), 30));
        p.boardArtId = "4b7d0e19a2c3f856";
        p.boardArtKind = "kicad";
        p.boardArtNote = "note";
        DispensePlan s = new DispensePlan();
        p.copyBoardFieldsTo(s);
        assertArrayEquals(p.boardToMachine, s.boardToMachine, 0);
        assertNotSame(p.boardToMachine, s.boardToMachine);
        assertEquals(p.boardFile, s.boardFile);
        assertEquals(p.boardWidthMm, s.boardWidthMm, 0);
        assertEquals(p.boardHeightMm, s.boardHeightMm, 0);
        assertEquals(p.boardTransformStatus, s.boardTransformStatus);
        assertEquals(p.fiducials, s.fiducials);
        assertEquals("4b7d0e19a2c3f856", s.boardArtId);
        assertEquals("kicad", s.boardArtKind);
        assertEquals("note", s.boardArtNote);
        // a plan without board fields copies its nulls over
        new DispensePlan().copyBoardFieldsTo(s);
        assertNull(s.boardToMachine);
        assertNull(s.boardFile);
        assertNull(s.boardArtId);
        assertEquals(0, s.fiducials.size());
    }
}
