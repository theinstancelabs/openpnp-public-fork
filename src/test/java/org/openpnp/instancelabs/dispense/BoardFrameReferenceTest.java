package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/**
 * One frame reference per surface: the blank and the places are checked against the needle-frame
 * anchor, a board surface against the board's own top Z (the plan's board location Z), so a board that
 * is simply lower than the blank is not refused, while a height taken with the needle (2.59 mm off) still
 * is. The needle floor follows the lower of the anchor and the board's reference.
 */
public class BoardFrameReferenceTest {
    /** The ZFrameTest configuration (anchor 5.5 on the blank) with the board probed {@code dz} lower. */
    static DispenseConfig cfg(double dz) {
        DispenseConfig c = ZFrameTest.cfg();
        c.surfaces.get("board").points = ZFrameTest.points(ZFrameTest.ZMAP, dz);
        return c;
    }

    static DispensePlan plan(DispenseConfig c, Double boardZ) {
        DispensePlan p = ZFrameTest.plan(c, boardZ == null ? 5.5 : boardZ);
        p.boardZMm = boardZ;
        return p;
    }

    @Test
    public void aBoardOneMillimetreUnderTheBlankAnchorPasses() {
        DispenseConfig c = cfg(-1.0); // board contacts 4.35..4.55, the blank anchor 5.5
        DispensePlan p = plan(c, 4.5);
        InterlockReport r = ZFrameTest.check(c, p);
        assertTrue(r.ok(), r.toString());
        // the bench's case: board 4.50, blank 5.80 (1.3 mm apart)
        c = cfg(-1.0);
        c.needleAnchor = new DispenseConfig.NeedleAnchor(5.8, 3.21, 333.1, 151.3, 1000L);
        c.needleZOffsetMm = -2.59;
        assertTrue(ZFrameTest.check(c, plan(c, 4.5)).ok(), ZFrameTest.check(c, plan(c, 4.5)).toString());
        // without a board reference (a plan made without a board location) the anchor still applies
        List<String> v = ZFrameTest.i4(ZFrameTest.check(cfg(-1.0), plan(cfg(-1.0), null)));
        assertTrue(v.stream().anyMatch(x -> x.contains("outside the needle-frame surface")), v.toString());
    }

    @Test
    public void aBoardSurfaceTakenWithTheNeedleIsRefused() {
        DispenseConfig c = cfg(-2.59); // probed with the needle instead of N1
        List<String> v = ZFrameTest.i4(ZFrameTest.check(c, plan(c, 5.5)));
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).startsWith("'board' surface Z 2.760..2.960 is outside the board top Z 5.500 +/- 1.00"),
                v.get(0));
        // ... also when the board top Z itself is 2.59 mm off the surface the other way
        c = cfg(0);
        v = ZFrameTest.i4(ZFrameTest.check(c, plan(c, 5.5 - 2.59)));
        assertTrue(v.get(0).contains("outside the board top Z 2.910"), v.toString());
        // the blank and the places stay on the needle frame
        c = cfg(-1.0);
        c.locations.put("waste", new DispenseConfig.NamedLocation(290, 125, 4.4));
        v = ZFrameTest.i4(ZFrameTest.check(c, null, DispenseService.shotsScope("waste", true)));
        assertTrue(v.get(0).contains("place 'waste'"), v.toString());
    }

    @Test
    public void theNeedleFloorFollowsTheLowerReference() throws Exception {
        DispenseConfig c = cfg(-1.0);
        assertEquals(5.5 - 1.0 - 2.59 - 0.1, c.needleFloorZMm(), 1e-9);
        assertEquals(4.5 - 1.0 - 2.59 - 0.1, c.needleFloorZMm(4.5), 1e-9, "the lower of anchor and board top");
        assertEquals(c.needleFloorZMm(), c.needleFloorZMm(6.0), 1e-9, "a board above the anchor leaves the anchor's floor");
        assertEquals(c.needleFloorZMm(), c.needleFloorZMm(null), 1e-9);
        assertTrue(Double.isNaN(new DispenseConfig().needleFloorZMm(4.5)), "no anchor: no floor (I4 refuses)");
    }

    @Test
    public void theFloorAllowsADotJustAboveALowerBoardAndRefusesOneBelowIt() throws Exception {
        // a tight frame tolerance makes the difference visible: the anchor-only floor is above this board's dots
        DispenseConfig c = cfg(-1.0);
        c.zFrameToleranceMm = 0.5;
        DispensePlan p = plan(c, 4.5);
        assertTrue(ZFrameTest.check(c, p).ok(), ZFrameTest.check(c, p).toString());
        double floor = c.needleFloorZMm(4.5); // 4.5 - 0.5 - 2.59 - 0.1 = 1.31
        assertEquals(1.31, floor, 1e-9);
        for (DispensePlan.PlannedDot d : p.dots) {
            assertEquals(d.surfaceZMm - 2.59 + 0.10, d.dispenseZMm, 1e-9, "0.1 mm above the board");
            assertTrue(d.dispenseZMm < c.needleFloorZMm(), "below the anchor-only floor");
            assertTrue(d.dispenseZMm > floor);
        }
        ToyMapper m = new ToyMapper(290, 125, 31.5, 0);
        GcodeProgram prog = GcodeStreamBuilder.forPlan(c, m, p, Collections.emptyList(), false, false, 0L);
        assertEquals(Double.valueOf(4.5), prog.floorReferenceZMm);
        assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(prog, c, "B",
                GcodeStreamValidator.zWindow(c, m, prog.floorReferenceZMm)));
        assertFalse(GcodeStreamValidator.validate(prog, c, "B", GcodeStreamValidator.zWindow(c, m)).isEmpty(),
                "the anchor-only window would refuse these dots");
        // one dot below the floor is still refused
        DispensePlan.PlannedDot low = p.dots.get(0);
        low.dispenseZMm = floor - 0.01;
        low.hopZMm = low.dispenseZMm + c.hopHeightMm;
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> GcodeStreamBuilder.forPlan(c, m, p, Collections.emptyList(), false, false, 0L));
        assertTrue(e.getMessage().startsWith("I4: dot 0") && e.getMessage().contains("below the needle floor 1.310"),
                e.getMessage());
    }

    @Test
    public void planBoardRecordsTheBoardLocationZ() throws Exception {
        BoardFeaturesTest.setup();
        Board b = BoardFeaturesTest.ftp();
        BoardLocation bl = new BoardLocation(b);
        bl.setLocation(new Location(LengthUnit.Millimeters, 296, 114.5, 4.5, 0));
        DispenseConfig c = cfg(-1.0);
        List<PadType> types = new ArrayList<>(BoardFeaturesTest.types);
        DispensePlan p = TestTips.planner(c, new PadTypeResolver(types, null)).planBoard(bl, 290, 125);
        assertEquals(Double.valueOf(4.5), p.boardZMm);
        assertEquals(Double.valueOf(4.5), GcodeStreamBuilder.forPlan(c, new ToyMapper(290, 125, 31.5, 0), p,
                Collections.emptyList(), false, false, 0L).floorReferenceZMm);
    }
}
