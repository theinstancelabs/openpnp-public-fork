package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.kicad.ImportProposal.PackageP;
import org.openpnp.instancelabs.kicad.ImportProposal.PadP;
import org.openpnp.instancelabs.kicad.ImportProposal.PlacementP;

/** Nano (KiCad 6, CRLF): fiducials by footprint name, a paste polygon, bottom-side parts (design §6.2). */
public class KicadNanoTest {
    static Fixtures.Loaded nano;

    @BeforeAll
    public static void load() throws Exception {
        nano = Fixtures.load(Fixtures.NANO);
    }

    @Test
    public void frame() {
        assertEquals(20211014, nano.pcb.version);
        assertFalse(nano.pcb.legacy);
        assertEquals(43.18, nano.proposal.widthMm, 1e-6);
        assertEquals(17.78, nano.proposal.heightMm, 1e-6);
        assertEquals("closed", nano.proposal.outline);
        assertEquals(4, nano.frame.cutouts.size(), "four gr_circle holes");
        for (double[] c : nano.frame.cutouts) {
            double[] box = KGeom.emptyBox();
            KGeom.extendPts(box, c);
            assertTrue(box[0] > 0 && box[2] < 43.18 && box[1] > 0 && box[3] < 17.78, "a cut-out lies inside");
        }
    }

    @Test
    public void partsAndFiducialsByName() {
        ImportProposal p = nano.proposal;
        assertEquals(34, p.counts.footprints);
        assertEquals(8, p.counts.placements);
        assertEquals(2, p.counts.fiducials);
        assertEquals(55, p.counts.pastePads);
        assertEquals(20, p.counts.bottom);
        assertEquals(20, p.skipped.get("bottom").size());
        assertEquals(List.of("Silkscreen_Front", "D+0", "D-0", "J4"), p.skipped.get("artOnly"));
        assertEquals("Fiducial", p.placement("FD1").type);
        assertEquals("Fiducial", p.placement("FD2").type);
        assertTrue(p.placement("FD1").enabled);
        PackageP fid = p.packages.get(p.placement("FD1").packageIndex);
        assertEquals(100.0, fid.pads.get(0).roundness, 1e-9, "rratio 0.5 square → round");
        assertEquals(1.016, fid.pads.get(0).w, 1e-9);
        for (String ref : new String[] {"TX0", "RX0", "PWR0", "L0", "Y1", "J3", "SW1", "IC3"}) {
            assertEquals("Placement", p.placement(ref).type, ref);
        }
        assertTrue(p.warnings("FIDUCIAL_COUNT").isEmpty());
    }

    @Test
    public void withoutTheNameRuleThereAreNoFiducials() throws Exception {
        ImportProposal p = Fixtures.load(Fixtures.NANO, new ImportRules(false, false)).proposal;
        assertEquals(0, p.counts.fiducials);
        assertEquals(8, p.counts.placements);
        assertEquals(6, p.counts.artOnly);
        assertEquals(1, p.warnings("FIDUCIAL_COUNT").size());
    }

    @Test
    public void ic3PastePolygon() {
        ImportProposal p = nano.proposal;
        PlacementP ic3 = p.placement("IC3");
        PackageP pk = p.packages.get(ic3.packageIndex);
        assertEquals(33, pk.pads.size());
        PadP poly = pk.pads.stream().filter(x -> x.name.equals("33~1")).findFirst().get();
        assertEquals(0, poly.x, 1e-9);
        assertEquals(0, poly.y, 1e-9);
        assertEquals(1.27, poly.w, 1e-9);
        assertEquals(1.27, poly.h, 1e-9);
        assertEquals(0, poly.roundness, 1e-9);
        assertEquals(null, poly.approx);
        assertEquals(1, p.warnings("PASTE_POLY").size());
        assertEquals(List.of("IC3"), p.warnings("PASTE_POLY").get(0).refs);
        assertTrue(p.warnings("PASTE_POLY").get(0).text.contains("33~1 (1.27 × 1.27 mm)"));
        assertEquals(1, p.warnings("PAD_NAME_EMPTY").size());
    }

    @Test
    public void viasonmaskFalseMeansTented() {
        assertTrue(nano.pcb.setup.viasTentedFront);
        assertEquals(43, nano.pcb.vias.size());
        assertTrue(nano.pcb.vias.stream().allMatch(v -> v.tentedFront(nano.pcb.setup)));
    }

    @Test
    public void kicad6FormsAreRead() {
        KicadPcb.Footprint ic3 = nano.pcb.footprints.stream().filter(f -> f.ref.equals("IC3")).findFirst().get();
        assertEquals("328P-MUR", ic3.value, "fp_text value");
        assertTrue(ic3.refHidden, "bare hide");
        assertEquals(-135, ic3.angle, 0);
        assertEquals(-0.0762, ic3.pads.get(0).pasteMargin, 1e-12);
        KicadPcb.Footprint j1 = nano.pcb.footprints.stream().filter(f -> f.ref.equals("J1")).findFirst().get();
        assertFalse(j1.top);
        assertEquals(15, j1.pads.size());
    }
}
