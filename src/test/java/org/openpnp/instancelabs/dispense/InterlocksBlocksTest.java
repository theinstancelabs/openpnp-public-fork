package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The run interlocks with dose blocks (profile calibration design §6.5): I9 counts the purge-row
 * spots of every switch, I11 follows the blocks' doses (not the profile's) for board runs and
 * calibration blocks, I6-feed refuses a block that asks the screw for more paste per second than it
 * was seen to sustain.
 */
public class InterlocksBlocksTest {
    /** The three-dose plan (half, one and quarter turns) in a board run that otherwise passes. */
    static Interlocks.Inputs threeDoses() {
        Interlocks.Inputs in = InterlocksTest.ok();
        in.cfg.purgeRow.spots = 24;
        in.plan = PlannerDoseTest.threeDosePlan(in.cfg);
        in.purgeDots = DoseBlocks.purgeSpotsNeeded(in.plan, in.cfg);
        return in;
    }

    @Test
    public void i9CountsTheSpotsOfEverySwitch() {
        Interlocks.Inputs in = threeDoses();
        assertEquals(8 + 4 + 5, in.purgeDots);
        assertFalse(Interlocks.check(in).has("I9"), Interlocks.check(in).toString());
        in.cfg.purgeRow.usedSpots = 8; // 16 left
        InterlockReport r = Interlocks.check(in);
        assertTrue(r.toString().contains("I9: only 16 unused purge spot(s) left, 17 needed (8 to start and 4 + 5 for "
                + "each further dot size): wipe the purge row and reset it"), r.toString());
        // one dose: today's text
        Interlocks.Inputs one = InterlocksTest.ok();
        one.cfg.purgeRow.usedSpots = 10;
        assertTrue(Interlocks.check(one).toString().contains("only 6 unused purge spot(s) left, 8 needed: wipe the "
                + "purge row and reset it (or set a clean new row and reset it)"));
        // calibration blocks lay purge-row dots too (not a board run)
        Interlocks.Inputs cal = InterlocksTest.ok();
        cal.boardRun = false;
        cal.plan = null;
        cal.purgeDots = 17;
        assertTrue(Interlocks.check(cal).has("I9"));
    }

    /** I11: a quarter-turn block needs the start angle even when the profile turns whole revolutions. */
    @Test
    public void i11FollowsTheBlocks() {
        Interlocks.Inputs in = threeDoses();
        in.cfg.profile.startAngleSetAtMs = null;
        assertTrue(Interlocks.check(in).has("I11"));
        // a whole-turn profile and only whole turns planned: no angle needed
        DispenseConfig c = in.cfg;
        c.profile.netStepsPerDot = 1600;
        List<PadGeometry> pads = PlannerDoseTest.part("U1", "TAB_PKG", 102, 90, 4.0, 2.0, 1.5, PlannerDoseTest.TAB);
        DispensePlanner pl = TestTips.planner(c, new PadTypeResolver(Collections.singletonList(PlannerDoseTest.TAB),
                null));
        in.plan = pl.plan("whole", pads, 77.5, 40);
        in.purgeDots = DoseBlocks.purgeSpotsNeeded(in.plan, c);
        assertFalse(Interlocks.check(in).has("I11"), Interlocks.check(in).toString());
        // the same profile with a quarter-turn type on the board: I11
        pads.addAll(PlannerDoseTest.part("Q1", "SOT_Q", 120, 90, 3.0, 1.475, 0.6, PlannerDoseTest.SOT));
        in.plan = TestTips.planner(c, new PadTypeResolver(java.util.Arrays.asList(PlannerDoseTest.TAB,
                PlannerDoseTest.SOT), null)).plan("whole", pads, 77.5, 40);
        assertEquals(2, in.plan.blocks.size());
        assertTrue(Interlocks.check(in).toString().contains("I11: the screw start angle has never been set"),
                Interlocks.check(in).toString());
        // calibration blocks: not a board run, a partial block
        Interlocks.Inputs cal = InterlocksTest.ok();
        cal.boardRun = false;
        cal.plan = null;
        cal.purgeDots = 0;
        cal.cfg.profile.netStepsPerDot = 1600;
        cal.cfg.profile.startAngleSetAtMs = null;
        assertFalse(Interlocks.check(cal).has("I11"));
        cal.partialTurn = true;
        assertTrue(Interlocks.check(cal).has("I11"));
        cal.cfg.profile.startAngleSetAtMs = 1000L; // set before the controller connected at 2500
        assertTrue(Interlocks.check(cal).toString().contains("(re)connected after the screw start angle was set"));
    }

    /** One-turn dots on a 2 mm grid. */
    static DispensePlan oneTurnRow(DispenseConfig c, int parts) {
        List<PadGeometry> pads = new ArrayList<>();
        PadType t = PlannerDoseTest.type("one", "One turn", 1.5, 1.5, 1600, 1);
        for (int i = 0; i < parts; i++) {
            pads.add(PlannerDoseTest.pad("C" + i, "PKG", "1", 100 + 2.0 * (i % 10), 80 + 2.0 * (i / 10), 0, 1.5, 1.5, t));
        }
        DispensePlan p = TestTips.planner(c, new PadTypeResolver(Collections.singletonList(t), null)).plan("feed",
                pads, 77.5, 40);
        p.boardZMm = 5.5;
        return p;
    }

    /** I6-feed: one-turn dots at a 50 ms dwell ask the screw for more than 2.6 mg/s; at 250 ms they do not. */
    @Test
    public void i6FeedRefusesAStarvingBlock() throws Exception {
        Interlocks.Inputs in = InterlocksTest.ok();
        DispenseConfig c = in.cfg;
        c.profile.dwellMs = 50;
        in.plan = oneTurnRow(c, 40);
        in.purgeDots = DoseBlocks.purgeSpotsNeeded(in.plan, c);
        GcodeProgram est = GcodeStreamBuilder.forPlan(c, GcodeStreamTest.mapper(), in.plan,
                DispenseService.purgeSpecs(c, in.plan), false, false, 0L);
        assertEquals(1, est.blocks.size());
        double demand = est.blocks.get(0).demandMgS;
        assertTrue(demand > 2.6, "demand " + demand);
        in.programBlocks = est.blocks;
        InterlockReport r = Interlocks.check(in);
        assertTrue(r.has("I6"), r.toString());
        assertTrue(r.toString().contains(String.format(java.util.Locale.ROOT, "I6: block 1 (one turn) asks the screw for "
                + "%.2f mg/s; the most it was seen to keep up with at 18.0 psi is 2.60 mg/s: lengthen the dwell or use "
                + "more, smaller dots", demand)), r.toString());
        // the plan's blocks carry the estimate's demand for the plan view and the API's checks
        in.programBlocks = null;
        assertFalse(Interlocks.check(in).has("I6"));
        DoseBlocks.fillDemand(in.plan, est);
        assertTrue(Interlocks.check(in).has("I6"));
        // the bench recipe's 250 ms dwell: within what the screw kept up with (36 one-turn dots, 2.56 mg/s)
        c.profile.dwellMs = 250;
        in.plan = oneTurnRow(c, 40);
        est = GcodeStreamBuilder.forPlan(c, GcodeStreamTest.mapper(), in.plan, DispenseService.purgeSpecs(c, in.plan),
                false, false, 0L);
        assertTrue(est.blocks.get(0).demandMgS < 2.6, "demand " + est.blocks.get(0).demandMgS);
        in.programBlocks = est.blocks;
        assertFalse(Interlocks.check(in).has("I6"), Interlocks.check(in).toString());
    }
}
