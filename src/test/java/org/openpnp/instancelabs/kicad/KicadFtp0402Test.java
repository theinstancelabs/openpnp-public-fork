package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** ftp0402 (KiCad 9): the 0402 version of the functional test board (design §6.2). */
public class KicadFtp0402Test {
    @Test
    public void ftp0402() throws Exception {
        Fixtures.Loaded k = Fixtures.load(Fixtures.FTP0402);
        ImportProposal p = k.proposal;
        assertEquals(20241229, k.pcb.version);
        assertEquals(75.0, p.widthMm, 1e-9);
        assertEquals(75.0, p.heightMm, 1e-9);
        assertEquals(0.0, p.minX, 1e-9);
        assertEquals(0.0, p.maxY, 1e-9);
        assertEquals(89, p.counts.footprints, "the logo footprint removed");
        assertEquals(80, p.counts.placements);
        assertEquals(3, p.counts.fiducials);
        assertEquals(160, p.counts.pastePads);
        assertEquals(List.of("G2"), p.skipped.get("bottom"));
        assertEquals(List.of("BOTTOM_SKIPPED"), KicadK10Test.codes(p));
        assertTrue(k.pcb.setup.viasTentedFront, "(tenting front back)");
        assertEquals(3, p.packages.size());
        for (ImportProposal.PackageP pk : p.packages) {
            assertTrue(pk.baseId.matches("R_0402_1005Metric|LED_0402_1005Metric|Fiducial_1mm_Mask3mm"), pk.baseId);
        }
        assertNull(p.placement("G1"));
    }
}
