package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;

/** Board -> pads (OpenPnP transform incl. fiducial-corrected one) -> plan. */
public class BoardPlanningTest {
    static List<PadType> types;

    @BeforeAll
    public static void setup() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("dispense-plan").toFile();
        Configuration.initialize(dir);
        Configuration.get().load();
        types = new ArrayList<>();
        for (PadType t : DispenseDb.seedPadTypes()) {
            t.version = 1;
            t.status = PadTypeStatus.PROVISIONAL;
            types.add(t);
        }
    }

    static Placement placement(String id, Part part, double x, double y, double rot) {
        Placement p = new Placement(id);
        p.setPart(part);
        p.setLocation(new Location(LengthUnit.Millimeters, x, y, 0, rot));
        p.setType(Placement.Type.Placement);
        return p;
    }

    static Board board() {
        Package r0603 = new Package("R_0603_1608Metric"); // no footprint pads (like the live packages.xml)
        Package led = new Package("LED_0603_1608Metric");
        Package qfn = new Package("QFN-16_3x3mm");
        Footprint fp = new Footprint();
        fp.setUnits(LengthUnit.Millimeters);
        Footprint.Pad perim = new Footprint.Pad();
        perim.setName("1");
        perim.setX(-1.45);
        perim.setY(0.75);
        perim.setWidth(0.8);
        perim.setHeight(0.25);
        fp.addPad(perim);
        Footprint.Pad centre = new Footprint.Pad();
        centre.setName("17");
        centre.setX(0);
        centre.setY(0);
        centre.setWidth(1.7);
        centre.setHeight(1.7);
        fp.addPad(centre);
        qfn.setFootprint(fp);
        Package unknown = new Package("WEIRD-THING");
        Part pr = new Part("R");
        pr.setPackage(r0603);
        Part pl = new Part("LED");
        pl.setPackage(led);
        Part pq = new Part("QFN");
        pq.setPackage(qfn);
        Part pu = new Part("U");
        pu.setPackage(unknown);
        Board b = new Board();
        b.setName("toy");
        b.addPlacement(placement("R1", pr, 10, 10, 0));
        b.addPlacement(placement("R2", pr, 20, 10, 90));
        b.addPlacement(placement("D1", pl, 30, 20, -45));
        b.addPlacement(placement("U1", pq, 40, 40, 0));
        b.addPlacement(placement("X1", pu, 50, 50, 0));
        Placement fid = placement("FID1", pr, 5, 5, 0);
        fid.setType(Placement.Type.Fiducial);
        b.addPlacement(fid);
        Placement disabled = placement("R3", pr, 60, 10, 0);
        disabled.setEnabled(false);
        b.addPlacement(disabled);
        return b;
    }

    static Map<String, PadGeometry> byId(List<PadGeometry> l) {
        Map<String, PadGeometry> m = new HashMap<>();
        for (PadGeometry g : l) {
            m.put(g.padId, g);
        }
        return m;
    }

    @Test
    public void padsFromPadTypesAndFootprintsInMachineCoordinates() {
        BoardLocation bl = new BoardLocation(board());
        bl.setLocation(new Location(LengthUnit.Millimeters, 100, 50, 1.6, 0));
        BoardPadExtractor.Result r = new BoardPadExtractor(new PadTypeResolver(types, null), true).extract(bl);
        Map<String, PadGeometry> m = byId(r.pads);
        // R1 two-terminal 0603: pads at +/-0.825 along part X
        assertEquals(110 - 0.825, m.get("R1.1").xMm, 1e-9);
        assertEquals(110 + 0.825, m.get("R1.2").xMm, 1e-9);
        assertEquals(60, m.get("R1.1").yMm, 1e-9);
        assertEquals(1.6, m.get("R1.1").surfaceZMm, 1e-9);
        assertEquals("chip-0603", m.get("R1.1").padType.typeKey);
        assertEquals(PadGeometry.Source.PAD_TYPE, m.get("R1.1").source);
        // R2 rotated 90: pads along Y
        assertEquals(120, m.get("R2.1").xMm, 1e-9);
        assertEquals(60 - 0.825, m.get("R2.1").yMm, 1e-9);
        assertEquals(90, m.get("R2.1").rotationDeg, 1e-9);
        // LED_0603 also resolves to chip-0603
        assertEquals("chip-0603", m.get("D1.1").padType.typeKey);
        double c = Math.cos(Math.toRadians(-45)), s = Math.sin(Math.toRadians(-45));
        assertEquals(130 - 0.825 * c, m.get("D1.1").xMm, 1e-9);
        assertEquals(70 - 0.825 * s, m.get("D1.1").yMm, 1e-9);
        // QFN footprint pads: perimeter vs centre (k < 1)
        assertEquals(PadGeometry.Source.FOOTPRINT, m.get("U1.1").source);
        assertEquals("qfn-dfn-perimeter", m.get("U1.1").padType.typeKey);
        assertEquals("qfn-centre", m.get("U1.17").padType.typeKey);
        assertEquals(140 - 1.45, m.get("U1.1").xMm, 1e-9);
        // unknown package -> pad with no type (planner flags it); fiducials excluded; disabled included
        assertNotNull(m.get("X1"));
        assertEquals(null, m.get("X1").padType);
        assertFalse(m.containsKey("FID1.1"));
        assertTrue(m.containsKey("R3.1"));
        assertFalse(byId(new BoardPadExtractor(new PadTypeResolver(types, null), false).extract(bl).pads)
                .containsKey("R3.1"));
    }

    @Test
    public void boardRotationAndFiducialTransformAreApplied() throws Exception {
        BoardLocation bl = new BoardLocation(board());
        bl.setLocation(new Location(LengthUnit.Millimeters, 100, 50, 1.6, 90));
        Map<String, PadGeometry> m = byId(new BoardPadExtractor(new PadTypeResolver(types, null), true).extract(bl).pads);
        // board rotated 90: board (x, y) -> (100 - y, 50 + x)
        assertEquals(100 - 10, m.get("R1.1").xMm, 1e-9);
        assertEquals(50 + 10 - 0.825, m.get("R1.1").yMm, 1e-9);
        assertEquals(90, m.get("R1.1").rotationDeg, 1e-9);

        // A fiducial check sets a corrected local->global transform; pads must follow it.
        AffineTransform tx = new AffineTransform();
        tx.translate(100.3, 49.8);
        tx.rotate(Math.toRadians(0.5));
        bl.setLocalToGlobalTransform(tx);
        m = byId(new BoardPadExtractor(new PadTypeResolver(types, null), true).extract(bl).pads);
        java.awt.geom.Point2D p = tx.transform(new java.awt.geom.Point2D.Double(10 - 0.825, 10), null);
        assertEquals(p.getX(), m.get("R1.1").xMm, 1e-9);
        assertEquals(p.getY(), m.get("R1.1").yMm, 1e-9);
        assertEquals(0.5, m.get("R1.1").rotationDeg, 1e-6);
    }

    /** One profile dot per pad (no dose model): the plan carries the profile and its fingerprint. */
    @Test
    public void plannerProfileDotsFlagsAndOrder() {
        DispenseConfig cfg = new DispenseConfig();
        cfg.minPadTypeStatus = "provisional";
        cfg.needleZOffsetMm = -2.59;
        DispensePlanner planner = new DispensePlanner(cfg, new PadTypeResolver(types, null));
        BoardLocation bl = new BoardLocation(board());
        bl.setLocation(new Location(LengthUnit.Millimeters, 100, 50, 5.5, 0));
        DispensePlan plan = planner.planBoard(bl, 100, 50);
        assertEquals(cfg.profile.fingerprint(), plan.profileFingerprint);
        assertEquals(cfg.profile.fingerprint(), plan.profile.fingerprint());
        assertEquals(0.10, plan.needleGapMm, 0);
        // R1, R2, R3, D1: 2 pads each; U1: 2 pads; X1: 1 (blocked)
        assertEquals(11, plan.pads.size());
        DispensePlan.PlannedPad r11 = null, x1 = null, centre = null;
        for (DispensePlan.PlannedPad p : plan.pads) {
            if (p.padId.equals("R1.1")) {
                r11 = p;
            }
            if (p.padId.equals("X1")) {
                x1 = p;
            }
            if (p.padId.equals("U1.17")) {
                centre = p;
            }
        }
        // informational stencil volume; the dot is the profile's half turn (800 steps)
        assertEquals(0.076, r11.targetVolumeMm3, 1e-12);
        assertEquals(800 / 4.44, r11.rotationPerDotDeg, 1e-12);
        assertEquals(1, r11.dotCount);
        assertTrue(r11.flags.isEmpty(), r11.flagNames().toString());
        assertFalse(r11.isBlocked());
        assertTrue(x1.flags.contains(PadFlag.NO_PAD_TYPE));
        assertTrue(x1.isBlocked());
        assertEquals(0.5 * 1.7 * 1.7 * 0.1, centre.targetVolumeMm3, 1e-12);
        assertEquals(1, centre.dotCount, "no pad rule: one dot even on the QFN centre pad");
        // 10 dispensable pads -> 10 dots, each cites the pad and has Z = surface + offset + gap (+hop)
        assertEquals(10, plan.dots.size());
        Set<String> pads = new HashSet<>();
        for (int i = 0; i < plan.dots.size(); i++) {
            DispensePlan.PlannedDot d = plan.dots.get(i);
            assertEquals(i, d.index);
            pads.add(d.padId);
            assertEquals(5.5 - 2.59 + 0.10, d.dispenseZMm, 1e-12);
            assertEquals(d.dispenseZMm + 1.0, d.hopZMm, 1e-12);
            assertEquals(cfg.profile.netDeg(cfg), d.rotationDeg, 0);
        }
        assertEquals(10, pads.size());
        assertEquals(10 * 800 / 4.44, plan.totalRotationDeg(), 1e-9);
        assertTrue(plan.pathLengthMm <= plan.pathLengthNearestNeighbourMm + 1e-9);
        // first dot is the one nearest the start point (100, 50): R1.1 at (109.175, 60)
        assertEquals("R1.1", plan.dots.get(0).padId);
        assertTrue(plan.summary().contains(cfg.profile.fingerprint()), plan.summary());
    }

    /** A pad type's rule asks for a grid of profile dots on a large pad (here the QFN centre pad). */
    @Test
    public void padRuleGivesAGridOfProfileDots() {
        DispenseConfig cfg = new DispenseConfig();
        cfg.minPadTypeStatus = "provisional";
        Map<String, PadRule> rules = new HashMap<>();
        rules.put("qfn-centre", new PadRule("qfn-centre", 4, null, 0.8));
        DispensePlanner planner = new DispensePlanner(cfg, new PadTypeResolver(types, null)).withRules(rules);
        BoardLocation bl = new BoardLocation(board());
        bl.setLocation(new Location(LengthUnit.Millimeters, 100, 50, 5.5, 0));
        DispensePlan plan = planner.planBoard(bl, 100, 50);
        List<DispensePlan.PlannedDot> centre = new ArrayList<>();
        for (DispensePlan.PlannedDot d : plan.dots) {
            if (d.padId.equals("U1.17")) {
                centre.add(d);
            }
        }
        // 2.89 mm^2 / 0.8 -> 4 dots (maxDots 4) on a 2 x 2 grid around the pad centre (140, 90)
        assertEquals(4, centre.size());
        for (DispensePlan.PlannedDot d : centre) {
            assertEquals(1.7 / 4, Math.abs(d.xMm - 140), 1e-9);
            assertEquals(1.7 / 4, Math.abs(d.yMm - 90), 1e-9);
            assertEquals(cfg.profile.netDeg(cfg), d.rotationDeg, 0);
        }
        assertEquals(13, plan.dots.size());
    }

    @Test
    public void statusBelowMinimumBlocks() {
        DispenseConfig cfg = new DispenseConfig(); // default minimum: characterized
        DispensePlanner planner = new DispensePlanner(cfg, new PadTypeResolver(types, null));
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(new PadGeometry("P1", 0, 0, 0, 0.8, 0.95, 0, types.get(1)));
        DispensePlan plan = planner.plan("t", pads, 0, 0);
        assertTrue(plan.pads.get(0).flags.contains(PadFlag.STATUS_BELOW_MIN));
        assertEquals(0, plan.dots.size());
        assertEquals(1, plan.blockedPads().size());
    }

    @Test
    public void ftpBoardShapeGives160Pads() {
        // Same layout family as /home/lumen/Desktop/ftp.board.xml: 40 R_0603 + 40 LED_0603 + 3 fiducials.
        Package r = new Package("R_0603_1608Metric");
        Package l = new Package("LED_0603_1608Metric");
        Part pr = new Part("R_0603_1608Metric-R_Small");
        pr.setPackage(r);
        Part pl = new Part("LED_0603_1608Metric-LED_Small");
        pl.setPackage(l);
        Board b = new Board();
        for (int i = 0; i < 40; i++) {
            b.addPlacement(placement("R" + i, pr, 5 + (i % 10) * 7, 5 + (i / 10) * 7, (i % 4) * 45));
            b.addPlacement(placement("D" + i, pl, 8 + (i % 10) * 7, 40 + (i / 10) * 7, -(i % 4) * 45));
        }
        for (int i = 0; i < 3; i++) {
            Placement f = placement("FID" + i, pr, 2 + i * 30, 2, 0);
            f.setType(Placement.Type.Fiducial);
            b.addPlacement(f);
        }
        DispenseConfig cfg = new DispenseConfig();
        cfg.minPadTypeStatus = "provisional";
        BoardLocation bl = new BoardLocation(b);
        bl.setLocation(new Location(LengthUnit.Millimeters, 150, 100, 1.6, 0));
        DispensePlan plan = new DispensePlanner(cfg, new PadTypeResolver(types, null)).planBoard(bl, 150, 100);
        assertEquals(160, plan.pads.size());
        assertEquals(160, plan.dots.size());
        assertEquals(0, plan.blockedPads().size());
    }
}
