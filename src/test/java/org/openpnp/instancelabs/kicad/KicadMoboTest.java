package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.kicad.ImportProposal.FootprintP;
import org.openpnp.instancelabs.kicad.ImportProposal.PackageP;
import org.openpnp.instancelabs.kicad.ImportProposal.PadP;
import org.openpnp.instancelabs.kicad.ImportProposal.PlacementP;

/** mobo (KiCad 9): arcs in the outline, cut-outs, a DNP QFN with paste windows, repeated pad names (design §6.2). */
public class KicadMoboTest {
    static Fixtures.Loaded mobo;
    static Fixtures.Loaded moboPasteDnp;

    @BeforeAll
    public static void load() throws Exception {
        mobo = Fixtures.load(Fixtures.MOBO);
        moboPasteDnp = Fixtures.load(Fixtures.MOBO, new ImportRules(true, true));
    }

    @Test
    public void exactOutlineExtents() {
        // with arc end points only, min X would be 99.95: every placement 0.22 mm off
        assertEquals(99.7323, mobo.frame.minX, 1e-4);
        assertEquals(51.5677, mobo.proposal.widthMm, 1e-4);
        assertEquals(89.0, mobo.proposal.heightMm, 1e-6);
        assertEquals("closed", mobo.proposal.outline);
        assertEquals(13, mobo.frame.cutouts.size());
    }

    @Test
    public void countsByDefaultDnpGetsNoPaste() {
        ImportProposal p = mobo.proposal;
        assertEquals(68, p.counts.footprints);
        assertEquals(54, p.counts.placements);
        assertEquals(3, p.counts.fiducials);
        assertEquals(220, p.counts.pastePads);
        assertEquals(1, p.counts.dnp);
        assertEquals(List.of("U2"), p.skipped.get("dnp"));
        assertNull(p.placement("U2"));
        assertEquals(FootprintP.DNP, footprint(p, "U2").kind);
        assertEquals(-1, footprint(p, "U2").placement);
        assertEquals(List.of("J1"), p.skipped.get("bottom"));
        assertEquals(List.of("TP3", "TP8", "TP5", "TP7", "TP1", "TP6", "J8", "TP4", "TP2"), p.skipped.get("artOnly"));
        assertEquals(1, p.warnings("DNP_SKIPPED").size());
        assertTrue(p.warnings("DNP_STILL_PASTED").isEmpty());
    }

    @Test
    public void pasteDnpMakesADisabledPlacement() {
        ImportProposal p = moboPasteDnp.proposal;
        assertEquals(55, p.counts.placements);
        assertEquals(261, p.counts.pastePads, "259 pads + J5's 2 polygons");
        PlacementP u2 = p.placement("U2");
        assertFalse(u2.enabled);
        assertEquals("dnp", u2.why);
        assertEquals(1, p.warnings("DNP_STILL_PASTED").size());
        assertTrue(p.warnings("DNP_SKIPPED").isEmpty());
        assertEquals(List.of("U2"), p.skipped.get("dnp"));
        List<String> windows = new ArrayList<>();
        for (PadP pad : p.packages.get(u2.packageIndex).pads) {
            if (pad.name.startsWith("33~")) {
                windows.add(pad.name);
            }
        }
        assertEquals(List.of("33~1", "33~2", "33~3", "33~4", "33~5", "33~6", "33~7", "33~8", "33~9"), windows);
        assertEquals(1, p.warnings("QFN_WINDOWS").stream().filter(w -> w.refs.contains("U2")).count());
        int empty = 0;
        for (ImportWarning w : p.warnings("PAD_NAME_EMPTY")) {
            empty += w.refs.size() * Integer.parseInt(w.text.replaceAll("^.*?: (\\d+) paste.*$", "$1"));
        }
        assertEquals(9 + 2 + 2 + 2, empty, "U2's nine, U7's and U8's two, J5's two polygons");
    }

    @Test
    public void repeatedAndEmptyPadNames() {
        ImportProposal p = mobo.proposal;
        assertEquals(List.of("1", "2", "2~2", "3"), names(p, "U4"));
        assertTrue(names(p, "J2").containsAll(List.of("MP", "MP~2")));
        assertTrue(names(p, "J3").containsAll(List.of("MP", "MP~2")));
        assertEquals(List.of("9~1", "9~2"), names(p, "U7").subList(0, 2));
        assertEquals(2, p.warnings("PAD_NAME_DUPLICATE").size(), "U4; J2 and J3 merged");
    }

    @Test
    public void j5PastePolygons() {
        PlacementP j5 = mobo.proposal.placement("J5");
        PackageP pk = mobo.proposal.packages.get(j5.packageIndex);
        assertEquals(9, pk.pads.size());
        assertEquals(-125, j5.rotation, 1e-9);
        for (PadP pad : pk.pads) {
            if (pad.name.contains("~")) {
                // a 1.5 × 2.125 mm filled rectangle with a 0.12 mm stroke
                assertEquals(1.62, pad.w, 1e-6);
                assertEquals(2.245, pad.h, 1e-6);
                assertEquals(0, pad.rotation, 1e-9);
                assertNull(pad.approx);
            }
            else {
                assertEquals(0, pad.rotation, 1e-9, "pads at 235° in a footprint at −125°");
            }
        }
        assertEquals(1, mobo.proposal.warnings("PASTE_POLY").size());
    }

    @Test
    public void tentingAndStackupColours() {
        assertTrue(mobo.pcb.setup.viasTentedFront, "(tenting front back)");
        assertEquals("Black", mobo.pcb.setup.maskColor);
        assertEquals("White", mobo.pcb.setup.silkColor);
        assertEquals(180, mobo.pcb.vias.size());
    }

    static FootprintP footprint(ImportProposal p, String ref) {
        return p.footprints.stream().filter(f -> f.ref.equals(ref)).findFirst().get();
    }

    static List<String> names(ImportProposal p, String ref) {
        List<String> l = new ArrayList<>();
        for (PadP pad : p.packages.get(p.placement(ref).packageIndex).pads) {
            l.add(pad.name);
        }
        return l;
    }
}
