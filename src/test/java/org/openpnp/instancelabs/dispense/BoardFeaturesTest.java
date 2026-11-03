package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;

/** Board features in board mm: outline, fiducials (sizes from a footprint or the package name), pads. */
public class BoardFeaturesTest {
    static List<PadType> types;

    @BeforeAll
    public static void setup() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("dispense-features").toFile();
        Configuration.initialize(dir);
        Configuration.get().load();
        types = new ArrayList<>();
        for (PadType t : DispenseDb.seedPadTypes()) {
            t.version = 1;
            t.status = PadTypeStatus.PROVISIONAL;
            types.add(t);
        }
    }

    static PadTypeResolver resolver() {
        return new PadTypeResolver(types, null);
    }

    static Placement placement(String id, Part part, double x, double y, double rot, Placement.Type type) {
        Placement p = new Placement(id);
        p.setPart(part);
        p.setLocation(new Location(LengthUnit.Millimeters, x, y, 0, rot));
        p.setType(type);
        return p;
    }

    /**
     * The ftp board (~/Desktop/ftp.board.xml) as the bench knows it: 75 x 75 mm, FID1-FID3 of package
     * Fiducial_1mm_Mask3mm (no footprint pads, as in the live packages.xml), 0603 resistors and LEDs
     * at their positions and rotations (the LEDs disabled for pick-and-place, paste still wanted).
     */
    static Board ftp() {
        Package fidPkg = new Package("Fiducial_1mm_Mask3mm");
        Part fid = new Part("Fiducial_1mm_Mask3mm-Fiducial");
        fid.setPackage(fidPkg);
        Package r0603 = new Package("R_0603_1608Metric");
        Package led = new Package("LED_0603_1608Metric");
        Part r = new Part("R_0603_1608Metric-R_Small");
        r.setPackage(r0603);
        Part d = new Part("LED_0603_1608Metric-LED_Small");
        d.setPackage(led);
        Board b = new Board();
        b.setName("ftp.board.xml");
        b.setDimensions(new Location(LengthUnit.Millimeters, 75, 75, 0, 0));
        b.addPlacement(placement("FID1", fid, 62.4465812, 4.69103014, 0, Placement.Type.Fiducial));
        b.addPlacement(placement("FID2", fid, 12.5, 70.0, 0, Placement.Type.Fiducial));
        b.addPlacement(placement("FID3", fid, 62.5, 70.0, 0, Placement.Type.Fiducial));
        int n = 1;
        double[][] ring = {{37.5, 42.5, 90}, {41.0, 41.0, 45}, {42.5, 37.5, 0}, {41.0, 34.0, -45}, {37.5, 32.5, -90},
            {34.0, 34.0, -135}, {32.5, 37.5, 180}, {34.0, 41.0, 135}};
        for (double[] p : ring) {
            b.addPlacement(placement("R" + n++, r, p[0], p[1], p[2], Placement.Type.Placement));
        }
        for (double y : new double[] {45, 40, 35, 30}) {
            b.addPlacement(placement("R" + n++, r, 16.75, y, 0, Placement.Type.Placement));
        }
        for (double y : new double[] {45, 40, 35, 30}) {
            b.addPlacement(placement("R" + n++, r, 5.75, y, 0, Placement.Type.Placement));
        }
        for (int i = 0; i < 8; i++) {
            b.addPlacement(placement("R" + n++, r, 11.25 + 7.5 * i, 17, 90, Placement.Type.Placement));
        }
        for (double y : new double[] {30, 35, 40, 45}) {
            b.addPlacement(placement("R" + n++, r, 58.25, y, 180, Placement.Type.Placement));
        }
        for (double y : new double[] {30, 35, 40, 45}) {
            b.addPlacement(placement("R" + n++, r, 69.25, y, 180, Placement.Type.Placement));
        }
        for (int i = 0; i < 8; i++) {
            b.addPlacement(placement("R" + n++, r, 63.75 - 7.5 * i, 58, -90, Placement.Type.Placement));
        }
        int m = 1;
        double[][] leds = {{37.5, 47.5, 0}, {44.5, 44.5, -45}, {47.5, 37.5, -90}, {44.5, 30.5, -135},
            {37.5, 27.5, 180}, {30.5, 30.5, 135}, {27.5, 37.5, 90}, {30.5, 44.5, 45}};
        for (double[] p : leds) {
            b.addPlacement(placement("D" + m++, d, p[0], p[1], p[2], Placement.Type.Placement));
        }
        for (double x : new double[] {21.75, 10.75}) {
            for (double y : new double[] {45, 40, 35, 30}) {
                b.addPlacement(placement("D" + m++, d, x, y, 180, Placement.Type.Placement));
            }
        }
        for (int i = 0; i < 8; i++) {
            b.addPlacement(placement("D" + m++, d, 11.25 + 7.5 * i, 22, -90, Placement.Type.Placement));
        }
        for (double x : new double[] {53.25, 64.25}) {
            for (double y : new double[] {30, 35, 40, 45}) {
                b.addPlacement(placement("D" + m++, d, x, y, 0, Placement.Type.Placement));
            }
        }
        for (int i = 0; i < 8; i++) {
            b.addPlacement(placement("D" + m++, d, 63.75 - 7.5 * i, 53, 90, Placement.Type.Placement));
        }
        for (Placement p : b.getPlacements()) {
            if (p.getId().startsWith("D")) {
                p.setEnabled(false);
            }
        }
        return b;
    }

    static Map<String, BoardFeatures.Fiducial> fids(BoardFeatures f) {
        Map<String, BoardFeatures.Fiducial> m = new HashMap<>();
        for (BoardFeatures.Fiducial x : f.fiducials) {
            m.put(x.name, x);
        }
        return m;
    }

    @Test
    public void ftpFiducialsOutlineAndPads() {
        BoardFeatures f = BoardFeatures.of(ftp(), resolver());
        assertEquals(75, f.widthMm, 0);
        assertEquals(75, f.heightMm, 0);
        assertTrue(f.hasOutline());
        assertEquals("ftp.board.xml", f.boardName);
        assertEquals(3, f.fiducials.size());
        BoardFeatures.Fiducial f1 = fids(f).get("FID1");
        assertEquals(62.4465812, f1.x, 1e-9);
        assertEquals(4.69103014, f1.y, 1e-9);
        assertEquals(1.0, f1.diameterMm, 0, "parsed from Fiducial_1mm_Mask3mm");
        assertEquals(3.0, f1.maskDiameterMm, 0);
        assertEquals(70.0, fids(f).get("FID3").y, 0);
        // 40 resistors and 40 LEDs, two pads each, from the chip-0603 pad type, in board mm
        assertEquals(160, f.pads.size());
        for (BoardFeatures.Pad p : f.pads) {
            assertTrue(p.known, p.id);
            assertTrue(p.wMm > 0 && p.hMm > 0);
            assertTrue(p.x > 0 && p.x < 75 && p.y > 0 && p.y < 75, p.id);
        }
    }

    @Test
    public void aRoundFootprintPadWinsOverTheName() {
        Board b = new Board();
        b.setName("b");
        Package pkg = new Package("Fiducial_1mm_Mask3mm");
        Footprint fp = new Footprint();
        fp.setUnits(LengthUnit.Millimeters);
        Footprint.Pad pad = new Footprint.Pad();
        pad.setName("1");
        pad.setWidth(1.5);
        pad.setHeight(1.5);
        pad.setRoundness(100);
        fp.addPad(pad);
        pkg.setFootprint(fp);
        Part part = new Part("FID-ROUND");
        part.setPackage(pkg);
        b.addPlacement(placement("F1", part, 5, 5, 0, Placement.Type.Fiducial));
        Package other = new Package("SomethingElse");
        Part p2 = new Part("FID-PLAIN");
        p2.setPackage(other);
        b.addPlacement(placement("F2", p2, 50, 5, 0, Placement.Type.Fiducial));
        Placement off = placement("F3", p2, 50, 50, 0, Placement.Type.Fiducial);
        off.setEnabled(false);
        b.addPlacement(off);
        BoardFeatures f = BoardFeatures.of(b, resolver());
        assertEquals(2, f.fiducials.size(), "disabled fiducials are not used");
        assertEquals(1.5, fids(f).get("F1").diameterMm, 1e-9, "the footprint pad");
        assertEquals(3.0, fids(f).get("F1").maskDiameterMm, 1e-9, "the mask still from the name");
        assertEquals(BoardFeatures.DEFAULT_FIDUCIAL_MM, fids(f).get("F2").diameterMm, 0, "the default");
        assertEquals(BoardFeatures.DEFAULT_MASK_MM, fids(f).get("F2").maskDiameterMm, 0);
        assertFalse(f.hasOutline(), "no dimensions");
        assertEquals(0, f.widthMm, 0);
    }

    @Test
    public void padsWithoutGeometryAreUnknown() {
        Board b = new Board();
        b.setName("b");
        Package weird = new Package("WEIRD-THING");
        Part u = new Part("U");
        u.setPackage(weird);
        b.addPlacement(placement("U1", u, 20, 20, 0, Placement.Type.Placement));
        BoardFeatures f = BoardFeatures.of(b, resolver());
        assertEquals(1, f.pads.size());
        assertFalse(f.pads.get(0).known);
        assertEquals(20, f.pads.get(0).x, 1e-9);
    }
}
