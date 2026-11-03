package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.kicad.ImportProposal.PackageP;
import org.openpnp.instancelabs.kicad.ImportProposal.PadP;
import org.openpnp.instancelabs.kicad.ImportProposal.PlacementP;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The brief's acceptance (design §6.2): the ftp board (KiCad 8) reproduces the bench's ftp.board.xml within 0.01 mm,
 * except FID1, which was moved by hand on the bench (0.3136 mm), and its pads are KiCad's.
 */
public class KicadFtpTest {
    static Fixtures.Loaded ftp;

    /** One placement of ftp.board.xml. */
    static final class XmlPlacement {
        String id;
        String partId;
        String type;
        String side;
        double x;
        double y;
        double rotation;
    }

    @BeforeAll
    public static void load() throws Exception {
        ftp = Fixtures.load(Fixtures.FTP);
    }

    static List<XmlPlacement> boardXml() throws Exception {
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(Fixtures.bytes("ftp.board.xml"))).getDocumentElement();
        List<XmlPlacement> l = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName("placement");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            Element loc = (Element) e.getElementsByTagName("location").item(0);
            assertEquals("Millimeters", loc.getAttribute("units"));
            XmlPlacement p = new XmlPlacement();
            p.id = e.getAttribute("id");
            p.partId = e.getAttribute("part-id");
            p.type = e.getAttribute("type");
            p.side = e.getAttribute("side");
            p.x = Double.parseDouble(loc.getAttribute("x"));
            p.y = Double.parseDouble(loc.getAttribute("y"));
            p.rotation = Double.parseDouble(loc.getAttribute("rotation"));
            l.add(p);
        }
        return l;
    }

    @Test
    public void frameAndCounts() {
        ImportProposal p = ftp.proposal;
        assertEquals(20240108, ftp.pcb.version);
        assertEquals(75.0, p.widthMm, 1e-9);
        assertEquals(75.0, p.heightMm, 1e-9);
        assertEquals(0.0, p.minX, 1e-9);
        assertEquals(0.0, p.maxY, 1e-9);
        assertEquals("outline", p.origin);
        assertEquals("closed", p.outline);
        assertEquals(89, p.counts.footprints);
        assertEquals(80, p.counts.placements);
        assertEquals(3, p.counts.fiducials);
        assertEquals(160, p.counts.pastePads);
        assertEquals(1, p.counts.bottom);
        assertEquals(5, p.counts.artOnly);
        assertEquals(List.of("G2"), p.skipped.get("bottom"));
        assertEquals(List.of("H4", "H1", "J1", "H2", "H3"), p.skipped.get("artOnly"));
        // the only notable warning on ftp
        assertEquals(1, p.warnings.size(), p.warnings.toString());
        assertEquals("BOTTOM_SKIPPED", p.warnings.get(0).code);
        assertTrue(ftp.pcb.setup.viasTentedFront, "viasonmask no");
    }

    @Test
    public void reproducesTheBenchBoardFile() throws Exception {
        List<XmlPlacement> xml = boardXml();
        ImportProposal p = ftp.proposal;
        assertEquals(83, xml.size());
        assertEquals(83, p.placements.size());
        double worst = 0;
        for (XmlPlacement x : xml) {
            PlacementP k = p.placement(x.id);
            assertNotNull(k, x.id);
            assertEquals(x.type, k.type, x.id);
            assertEquals("Top", x.side, x.id);
            assertEquals(x.rotation, k.rotation, 0.0, x.id + " rotation");
            PackageP pk = p.packages.get(k.packageIndex);
            assertEquals(x.partId, pk.baseId + "-" + k.value, x.id + " part key");
            double d = Math.hypot(k.x - x.x, k.y - x.y);
            if (x.id.equals("FID1")) {
                // moved by hand on the bench: KiCad (62.5, 5.0) vs (62.4466, 4.6910)
                assertEquals(0.3136, d, 0.001, "FID1 offset");
                assertEquals(62.5, k.x, 1e-9);
                assertEquals(5.0, k.y, 1e-9);
            }
            else {
                assertTrue(d <= 0.01, x.id + " is " + d + " mm from ftp.board.xml");
                worst = Math.max(worst, d);
            }
        }
        assertTrue(worst < 1e-4, "worst " + worst);
        System.out.println("KicadFtpTest: worst placement offset (FID1 excluded) " + worst + " mm");
    }

    @Test
    public void padsAreKiCadsApertures() {
        ImportProposal p = ftp.proposal;
        int apertures = 0;
        for (PlacementP pl : p.placements) {
            PackageP pk = p.packages.get(pl.packageIndex);
            if ("Fiducial".equals(pl.type)) {
                assertTrue(pk.fiducial);
                assertEquals(1, pk.pads.size());
                PadP pad = pk.pads.get(0);
                assertEquals(1.0, pad.w, 1e-9);
                assertEquals(1.0, pad.h, 1e-9);
                assertEquals(100.0, pad.roundness, 1e-9);
                continue;
            }
            assertEquals(2, pk.pads.size(), pl.id);
            boolean r = pk.baseId.equals("R_0603_1608Metric");
            assertTrue(r || pk.baseId.equals("LED_0603_1608Metric"), pk.baseId);
            for (PadP pad : pk.pads) {
                apertures++;
                double sign = pad.name.equals("1") ? -1 : 1;
                assertTrue(pad.name.equals("1") || pad.name.equals("2"));
                assertEquals(sign * (r ? 0.825 : 0.7875), pad.x, 1e-9, pl.id);
                assertEquals(0.0, pad.y, 1e-9);
                assertEquals(r ? 0.8 : 0.875, pad.w, 1e-9);
                assertEquals(0.95, pad.h, 1e-9);
                assertEquals(50.0, pad.roundness, 1e-9);
                assertEquals(0.0, pad.rotation, 1e-9, pl.id + " pad rotation is relative to the part");
            }
        }
        assertEquals(160, apertures);
        assertEquals(3, p.packages.size());
    }
}
