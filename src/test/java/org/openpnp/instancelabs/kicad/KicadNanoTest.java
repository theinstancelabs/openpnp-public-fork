package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
        String bottom = p.warnings("BOTTOM_SKIPPED").get(0).text; // parts, fiducials, headers and the logo alike
        assertTrue(bottom.startsWith("20 bottom-side footprints get no paste (not read yet): ")
                && bottom.endsWith(" and 10 more."), bottom);
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
    public void ic3CentrePadIsPastedOnItsCopper() {
        // BR-91 (a): pad 33 (F.Cu only) becomes one opening on the copper its drawn F.Mask circle (Ø2.667 mm) leaves
        // open, less the pad's paste margin −0.0762; the square drawn on F.Paste goes
        ImportProposal p = nano.proposal;
        assertEquals(ImportRules.COPPER, p.centrePads);
        PlacementP ic3 = p.placement("IC3");
        PackageP pk = p.packages.get(ic3.packageIndex);
        assertEquals(33, pk.pads.size());
        assertTrue(pk.pads.stream().noneMatch(x -> x.name.contains("~")), "no 33~1");
        PadP c = pk.pads.stream().filter(x -> x.name.equals("33")).findFirst().get();
        assertEquals("IC3.33", ic3.id + "." + c.name);
        assertEquals(0, c.x, 1e-3);
        assertEquals(0, c.y, 1e-3);
        assertEquals(2.667 - 2 * 0.0762, c.w, 1e-3, "the mask circle less the paste margin, not the 3.10 mm square");
        assertEquals(c.w, c.h, 1e-9);
        assertEquals(100, c.roundness, 1e-9, "round");
        assertEquals(55, p.counts.pastePads);
        assertTrue(p.warnings("PASTE_POLY").isEmpty());
        assertTrue(p.warnings("PAD_NAME_EMPTY").isEmpty());
        assertEquals(1, p.warnings("CENTRE_PAD_COPPER").size());
        assertEquals(List.of("IC3"), p.warnings("CENTRE_PAD_COPPER").get(0).refs);
        assertEquals("IC3's centre pad 33 has no paste of its own: it is pasted as one opening (Ø2.52 mm) on the "
                + "5.59 mm² of copper the solder mask leaves open. The design pastes 1.61 mm² of it (29 %).",
                p.warnings("CENTRE_PAD_COPPER").get(0).text);
    }

    @Test
    public void theDefaultChangesOnlyIc3sCentrePad() throws Exception {
        // BR-91 (a): the other 54 openings are the design's (NanoPasteOracleTest: KiCad's own paste Gerber)
        Map<String, String> copper = openings(nano.proposal);
        Map<String, String> design = openings(Fixtures.load(Fixtures.NANO,
                new ImportRules(true, false, ImportRules.DESIGN)).proposal);
        Map<String, String> same = new TreeMap<>(copper);
        same.entrySet().retainAll(design.entrySet());
        assertEquals(54, same.size());
        copper.keySet().removeAll(same.keySet());
        design.keySet().removeAll(same.keySet());
        assertEquals(Set.of("IC3.33"), copper.keySet());
        assertEquals(Set.of("IC3.33~1"), design.keySet());
    }

    /** Placement id + pad name → the opening (x, y, w, h, rotation, roundness), the parts' only. */
    static Map<String, String> openings(ImportProposal p) {
        Map<String, String> m = new TreeMap<>();
        for (PlacementP pl : p.placements) {
            if ("Placement".equals(pl.type)) {
                for (PadP pad : p.packages.get(pl.packageIndex).pads) {
                    m.put(pl.id + "." + pad.name, String.format(Locale.ROOT, "%.6f %.6f %.6f %.6f %.6f %.6f", pad.x,
                            pad.y, pad.w, pad.h, pad.rotation, pad.roundness));
                }
            }
        }
        return m;
    }

    @Test
    public void ic3PastePolygon() throws Exception {
        ImportProposal p = Fixtures.load(Fixtures.NANO, new ImportRules(true, false, ImportRules.DESIGN)).proposal;
        assertEquals(ImportRules.DESIGN, p.centrePads);
        assertTrue(p.warnings("CENTRE_PAD_COPPER").isEmpty());
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
