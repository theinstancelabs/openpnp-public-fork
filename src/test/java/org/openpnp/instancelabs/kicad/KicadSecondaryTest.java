package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** secondary-fid-pcb (KiCad 9): a gr_rect outline, one bottom-side fiducial, nothing to dispense (design §6.2). */
public class KicadSecondaryTest {
    @Test
    public void rectOutlineAndNoPlacements() throws Exception {
        Fixtures.Loaded s = Fixtures.load(Fixtures.SECONDARY);
        assertEquals(20241229, s.pcb.version);
        assertEquals(10.0, s.proposal.widthMm, 1e-9);
        assertEquals(10.0, s.proposal.heightMm, 1e-9);
        assertEquals("closed", s.proposal.outline);
        assertEquals("outline", s.proposal.origin);
        assertEquals(1, s.proposal.counts.footprints);
        assertEquals(0, s.proposal.placements.size());
        assertEquals(List.of("REF**"), s.proposal.skipped.get("bottom"));
        assertEquals(List.of("NO_PLACEMENTS", "FIDUCIAL_COUNT", "BOTTOM_SKIPPED"),
                KicadK10Test.codes(s.proposal));
    }
}
