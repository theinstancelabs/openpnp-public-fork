package org.openpnp.instancelabs.board;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.instancelabs.kicad.KGeom;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/** Fallback art from a board file without a KiCad drawing (design §4.3, §6.3): the bench's ftp.board.xml. */
public class BoardArtFallbackTest {
    @Test
    public void ftpBoardXmlOnTheSimMachine() throws Exception {
        try (SimMachine sim = new SimMachine(false)) {
            Board b = ArtFixtures.ftpBoardXml(sim.configDir);
            BoardArt a = BoardArtFallback.build(b, ArtFixtures.seedResolver());
            assertEquals("fallback", a.kind);
            assertNull(a.origin);
            assertNull(a.imported);
            assertEquals(b.getFile().getAbsolutePath(), a.source.boardFile);
            assertEquals("ftp.board.xml", a.source.name);
            assertArrayEquals(new double[] {75, 75}, a.size, 0);
            assertArrayEquals(new double[] {0, 0, 75, 75},
                    ArtFixtures.box(Collections.singletonList(a.outline.d)), 0);
            assertTrue(a.outline.closed);
            assertEquals("slate", a.finish.mask);

            // 160 copper pads in the pad type's layout (the packages have no pads) and 3 round fiducials
            List<ArtPath.Sub> exposed = ArtPath.parse(a.layers.exposed.fill);
            assertEquals(163, exposed.size());
            assertEquals(a.layers.cu.fill, a.layers.exposed.fill);
            assertEquals(163, ArtPath.parse(a.layers.mask.open).size());
            // R1 at (37.5, 42.5), 90°: chip-0603 pads ±0.825 mm along the part's X
            assertTrue(ArtFixtures.inside(a.layers.exposed.fill, 37.5, 42.5 + 0.825));
            assertTrue(ArtFixtures.inside(a.layers.exposed.fill, 37.5, 42.5 - 0.825));
            assertFalse(ArtFixtures.inside(a.layers.exposed.fill, 37.5, 42.5));
            assertEquals(3, a.fiducials.size());
            for (BoardArt.FidArt f : a.fiducials) {
                assertEquals(1.0, f.copperMm, 0, f.name);
                assertEquals(3.0, f.maskMm, 0, f.name);
            }
            assertEquals("FID1", a.fiducials.get(0).name);
            assertEquals(62.446581, a.fiducials.get(0).bx, 1e-6, "the board file's (hand-moved) FID1");
            assertEquals(4.69103, a.fiducials.get(0).by, 1e-6);
            int fidCircles = 0;
            for (double[] c : ArtFixtures.circles(a.layers.mask.open)) {
                fidCircles += Math.abs(c[2] - 1.5) < 0.006 ? 1 : 0;
            }
            assertEquals(3, fidCircles);

            Map<String, Integer> kinds = new HashMap<>();
            for (BoardArt.Part p : a.parts) {
                kinds.merge(p.kind, 1, Integer::sum);
                assertNull(p.label);
                assertTrue(p.box[2] > p.box[0] && p.box[3] > p.box[1], p.ref);
            }
            assertEquals(40, (int) kinds.get("placement"), "R1–R40");
            assertEquals(40, (int) kinds.get("pasteOnly"), "D1–D40 are disabled in the board file");
            assertEquals(3, (int) kinds.get("fiducial"));
            BoardArt.Part r1 = a.parts.stream().filter(p -> p.ref.equals("R1")).findFirst().get();
            assertEquals("R_0603_1608Metric", r1.pkg);
            assertEquals(2, r1.pads);
            // the pads (0.8 mm along the part's X at ±0.825) grown 0.3 mm
            assertEquals(-1.525, r1.box[0], 0.011);
            assertEquals(1.525, r1.box[2], 0.011);

            assertTrue(a.keepOutHoles.isEmpty());
            assertTrue(a.layers.cu.tracks.isEmpty() && a.layers.silk.lines.isEmpty() && a.texts.isEmpty());
            assertEquals("", a.layers.holes.pth);
            assertEquals("M0 0H75V75H0Z", a.thumb.outline);
            assertTrue(a.thumb.exposed.length() > 0, a.thumb.exposed);
            assertEquals(3, ArtFixtures.circles(a.thumb.mask).size(), "the fiducials' openings; the pads' are "
                    + "0.05 mm, too little to show");
            int thumb = a.thumb.outline.length() + a.thumb.mask.length() + a.thumb.exposed.length();
            assertTrue(thumb <= 8 * 1024, thumb + " bytes");
            assertEquals(a.toBytes().length, a.stats.bytes);
            assertArrayEquals(a.toBytes(), BoardArtFallback.build(b, ArtFixtures.seedResolver()).toBytes());
        }
    }

    @Test
    public void withoutPadTypesOnlyFootprintPadsAndFiducials() throws Exception {
        try (SimMachine sim = new SimMachine(false)) {
            Board b = ArtFixtures.ftpBoardXml(sim.configDir);
            BoardArt a = BoardArtFallback.build(b, null);
            assertEquals(3, ArtPath.parse(a.layers.exposed.fill).size(), "the packages have no pads");
            assertEquals(3, ArtFixtures.circles(a.layers.exposed.fill).size());

            // a footprint on the resistor package: its pads, roundness 50 → corners of radius 0.2 mm
            Footprint fp = new Footprint();
            fp.setUnits(LengthUnit.Millimeters);
            for (int i = 0; i < 2; i++) {
                Footprint.Pad p = new Footprint.Pad();
                p.setName(Integer.toString(i + 1));
                p.setX(i == 0 ? -0.825 : 0.825);
                p.setWidth(0.8);
                p.setHeight(0.95);
                p.setRoundness(50);
                fp.addPad(p);
            }
            Configuration.get().getPackage("R_0603_1608Metric").setFootprint(fp);
            a = BoardArtFallback.build(b, null);
            assertEquals(83, ArtPath.parse(a.layers.exposed.fill).size(), "40 resistors and 3 fiducials");
            assertTrue(a.layers.exposed.fill.contains("A0.2 0.2"));
            assertTrue(ArtFixtures.inside(a.layers.exposed.fill, 37.5, 42.5 + 0.825));
        }
    }

    @Test
    public void aBoardWithoutDimensionsIsOutlinedByItsPads() throws Exception {
        try (SimMachine sim = new SimMachine(false)) {
            Board b = ArtFixtures.ftpBoardXml(sim.configDir);
            b.setDimensions(new Location(LengthUnit.Millimeters));
            BoardArt a = BoardArtFallback.build(b, ArtFixtures.seedResolver());
            double[] box = ArtFixtures.box(Collections.singletonList(a.outline.d));
            // the hull of the pads and the fiducials' 3 mm mask openings, grown 2 mm
            double[] pads = ArtFixtures.box(Collections.singletonList(a.layers.exposed.fill));
            for (BoardArt.FidArt f : a.fiducials) {
                KGeom.extend(pads, f.bx - f.maskMm / 2, f.by - f.maskMm / 2);
                KGeom.extend(pads, f.bx + f.maskMm / 2, f.by + f.maskMm / 2);
            }
            assertEquals(pads[0] - 2, box[0], 0.02);
            assertEquals(pads[1] - 2, box[1], 0.02);
            assertEquals(pads[2] + 2, box[2], 0.02);
            assertEquals(pads[3] + 2, box[3], 0.02);
            assertEquals(box[2] - box[0], a.size[0], 0.02); // the path rounds both ends to 0.01 mm
            assertTrue(ArtPath.parse(a.outline.d).get(0).pts.length / 2 > 8, "rounded corners");
        }
    }
}
