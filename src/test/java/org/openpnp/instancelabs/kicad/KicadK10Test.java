package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** pi-interface at KiCad 10 (20260206): the newest forms read, the new setup tokens ignored silently (§6.2). */
public class KicadK10Test {
    static List<String> codes(ImportProposal p) {
        List<String> l = new ArrayList<>();
        for (ImportWarning w : p.warnings) {
            l.add(w.code);
        }
        return l;
    }

    @Test
    public void kicad10() throws Exception {
        Fixtures.Loaded k = Fixtures.load(Fixtures.PI10);
        assertEquals(20260206, k.pcb.version);
        assertEquals("10.0", k.pcb.generatorVersion);
        assertEquals(3, k.proposal.counts.footprints, "five kibuzzard-* footprints removed");
        assertEquals(2, k.proposal.counts.placements);
        assertEquals(12, k.proposal.counts.pastePads);
        assertEquals(List.of("J1"), k.proposal.skipped.get("artOnly"));
        assertTrue(k.pcb.setup.viasTentedFront, "(tenting (front yes) (back yes))");
        assertEquals(7, k.pcb.vias.size());
        assertTrue(k.pcb.vias.stream().allMatch(v -> v.tentedFront == null));
        // covering / plugging / capping / filling are ignored silently, and there is no KICAD_NEWER
        assertEquals(List.of("FIDUCIAL_COUNT", "PAD_NAME_DUPLICATE"), codes(k.proposal));
        assertEquals(0, k.pcb.ignoredItems);
        assertEquals(20.5, k.proposal.widthMm, 1e-5);
        assertEquals(24.75, k.proposal.heightMm, 1e-5);
        assertEquals("J3", k.proposal.placements.get(0).id, "property \"Reference\"");
    }
}
