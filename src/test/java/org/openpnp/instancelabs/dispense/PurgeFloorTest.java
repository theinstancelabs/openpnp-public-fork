package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The needle floor per segment of a board program: the purge dots lie on the blank, whose frame
 * reference is the needle-frame anchor, so they keep the anchor's floor; only the board's dots take
 * the lower floor of a board that sits under the anchor (one frame reference per surface).
 */
public class PurgeFloorTest {
    static GcodeStreamBuilder.DotSpec purge(double z, int k) {
        return new GcodeStreamBuilder.DotSpec(300 + 2.5 * k, 130, z, z + 0.5, 180, GcodeStreamBuilder.PURGE_INDEX,
                "purge#" + k);
    }

    @Test
    public void purgeDotsKeepTheAnchorFloorUnderALowerBoard() throws Exception {
        DispenseConfig c = BoardFrameReferenceTest.cfg(-1.0); // board 1 mm under the blank anchor 5.5
        c.zFrameToleranceMm = 0.5;
        DispensePlan p = BoardFrameReferenceTest.plan(c, 4.5);
        double anchorFloor = c.needleFloorZMm(), boardFloor = c.needleFloorZMm(4.5);
        assertEquals(2.31, anchorFloor, 1e-9);
        assertEquals(1.31, boardFloor, 1e-9);
        ToyMapper m = new ToyMapper(290, 125, 31.5, 0);

        // a purge dot between the two floors: under the blank's own floor, so refused, although the
        // board's dots (0.1 mm above a board 1 mm lower) are fine
        List<GcodeStreamBuilder.DotSpec> low = Arrays.asList(purge(3.01, 0), purge(2.20, 1));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> GcodeStreamBuilder.forPlan(c, m, p, low, false, false, 0L));
        assertTrue(e.getMessage().startsWith("I4: dot " + GcodeStreamBuilder.PURGE_INDEX + " (purge#1) at N2 Z 2.200 "
                + "is below the needle floor 2.310 (needle-frame surface"), e.getMessage());

        // purge dots on the blank's frame, board dots on the board's: the program builds and validates
        List<GcodeStreamBuilder.DotSpec> ok = Arrays.asList(purge(3.01, 0), purge(2.32, 1));
        GcodeProgram prog = GcodeStreamBuilder.forPlan(c, m, p, ok, false, false, 0L);
        assertEquals(2, prog.purgeDotCount);
        assertEquals(p.dots.size(), prog.dotCount);
        for (DispensePlan.PlannedDot d : p.dots) {
            assertTrue(d.dispenseZMm < anchorFloor && d.dispenseZMm > boardFloor, "board dots between the floors");
        }
        assertEquals(Arrays.asList(), GcodeStreamValidator.validate(prog, c, "B",
                GcodeStreamValidator.zWindow(c, m, prog.floorReferenceZMm)));

        // a board dot under the board's floor is still refused
        DispensePlan.PlannedDot d0 = p.dots.get(0);
        d0.dispenseZMm = boardFloor - 0.01;
        d0.hopZMm = d0.dispenseZMm + c.hopHeightMm;
        e = assertThrows(IllegalStateException.class, () -> GcodeStreamBuilder.forPlan(c, m, p, ok, false, false, 0L));
        assertTrue(e.getMessage().startsWith("I4: dot 0") && e.getMessage().contains("below the needle floor 1.310 "
                + "(the lower of the needle-frame surface and the board top Z"), e.getMessage());
    }
}
