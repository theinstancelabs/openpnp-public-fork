package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.kicad.KicadPcb.Graphic;

/** pi-interface at KiCad 7 (20221018): stroke widths, viasonmask (design §6.2). */
public class KicadK7Test {
    @Test
    public void kicad7() throws Exception {
        Fixtures.Loaded k = Fixtures.load(Fixtures.PI7);
        assertEquals(20221018, k.pcb.version);
        assertEquals(2, k.proposal.counts.footprints, "two kibuzzard-* footprints removed");
        assertEquals(1, k.proposal.counts.placements);
        assertEquals(8, k.proposal.counts.pastePads);
        assertEquals(List.of("J1"), k.proposal.skipped.get("artOnly"));
        assertTrue(k.pcb.setup.viasTentedFront, "viasonmask false");
        boolean stroke = false;
        for (Graphic g : k.pcb.graphics) {
            stroke |= "Edge.Cuts".equals(g.layer) && g.width > 0;
        }
        assertTrue(stroke, "widths from (stroke (width …))");
        assertEquals(List.of("FIDUCIAL_COUNT", "PAD_NAME_DUPLICATE"), KicadK10Test.codes(k.proposal));
        assertEquals(20.955, k.proposal.widthMm, 1e-5);
        assertEquals(19.05, k.proposal.heightMm, 1e-5);
    }
}
