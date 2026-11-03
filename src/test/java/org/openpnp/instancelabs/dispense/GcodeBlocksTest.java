package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Dose blocks in the stream (profile calibration design §1.2 R2/R3, §6.2): the initial purge sets
 * the phase for block 0; before every further block a phase dot (partial turns) and the flush dots
 * of the new dose on the purge row, then the cross; every block of a partial turn starts at the
 * profile's start angle; a plan of one dose is the program the builder made before blocks existed.
 */
public class GcodeBlocksTest {
    static final int COUNT = 1234;

    /** The toy setup with a purge row long enough for three blocks (8 + 4 + 5 spots and more). */
    static DispenseConfig cfg() {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        c.purgeRow.spots = 24;
        return c;
    }

    static List<GcodeStreamBuilder.DotSpec> dots(int n, double x0, double y0, int firstIndex) {
        List<GcodeStreamBuilder.DotSpec> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double z = 5.5 - 2.59 + 0.10;
            l.add(new GcodeStreamBuilder.DotSpec(x0 + 2.0 * i, y0, z, z + 1.0, 0, firstIndex + i, "P" + (firstIndex + i),
                    firstIndex + i));
        }
        return l;
    }

    /** Half turns (the profile's), then one turn, then quarter turns: 3, 2 and 4 dots. */
    static List<GcodeStreamBuilder.BlockSpec> threeBlocks() {
        List<GcodeStreamBuilder.BlockSpec> b = new ArrayList<>();
        b.add(new GcodeStreamBuilder.BlockSpec(800, dots(3, 100, 80, 0), "half turn"));
        b.add(new GcodeStreamBuilder.BlockSpec(1600, dots(2, 100, 84, 3), "one turn"));
        b.add(new GcodeStreamBuilder.BlockSpec(400, dots(4, 100, 88, 5), "quarter turn"));
        return b;
    }

    /** Net steps of every dot (purge-row dots too), in order, from the B targets. */
    static List<long[]> nets(GcodeProgram p) {
        List<long[]> l = new ArrayList<>();
        long rest = 0;
        for (List<GcodeProgram.Line> d : GcodeStreamTest.dotLines(p)) {
            long after = rest;
            for (GcodeProgram.Line x : d) {
                if (x.text.startsWith("G1 B")) {
                    after = GcodeStreamTest.steps(Double.parseDouble(x.text.split(" ")[1].substring(1)));
                }
            }
            l.add(new long[] {d.get(0).dotIndex, after - rest, d.get(0).netSteps});
            rest = after;
        }
        return l;
    }

    @Test
    public void threeBlocksWithSwitchesOnThePurgeRow() throws Exception {
        DispenseConfig c = cfg();
        List<GcodeStreamBuilder.BlockSpec> blocks = threeBlocks();
        List<GcodeStreamBuilder.DotSpec> purge = DispenseService.purgeSpecs(c, 8 + 4 + 5);
        assertEquals(17, purge.size());
        GcodeProgram p = GcodeStreamBuilder.forBlocks(c, GcodeStreamTest.mapper(), blocks, purge, 4, false, false,
                (long) COUNT, null);
        assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(p, c, "B"));
        assertEquals(17, p.purgeDotCount);
        assertEquals(9, p.dotCount);
        List<long[]> nets = nets(p);
        assertEquals(17 + 9, nets.size());
        // initial purge: the phase dot, then 7 half turns
        assertEquals(GcodeStreamBuilder.PURGE_INDEX, nets.get(0)[0]);
        for (int i = 1; i < 8; i++) {
            assertEquals(800, nets.get(i)[1], "purge dot " + i);
        }
        // block 1: 3 half turns
        for (int i = 8; i < 11; i++) {
            assertEquals(800, nets.get(i)[1]);
            assertEquals(800, nets.get(i)[2]);
        }
        // switch to the one turn: 4 flush dots of one turn, no phase dot (a whole turn needs no angle)
        for (int i = 11; i < 15; i++) {
            assertEquals(GcodeStreamBuilder.PURGE_INDEX, nets.get(i)[0]);
            assertEquals(1600, nets.get(i)[1]);
        }
        assertEquals(1600, nets.get(15)[1]);
        assertEquals(1600, nets.get(16)[1]);
        // switch to the quarter turn: a phase dot, then 4 quarter-turn flush dots
        long phase = nets.get(17)[1];
        assertTrue(phase >= 89 && phase < 1600 + 89, "phase " + phase);
        for (int i = 18; i < 22; i++) {
            assertEquals(400, nets.get(i)[1]);
        }
        for (int i = 22; i < 26; i++) {
            assertEquals(400, nets.get(i)[1]);
            assertEquals(i - 17, nets.get(i)[0], "work dot indices run across the blocks");
        }
        // every partial block starts at 298 deg (1324 of 1600 steps); the whole turn where block 1 ended
        assertEquals(3, p.blocks.size());
        assertEquals(1324, Math.floorMod(p.blocks.get(0).restCountAtStart, 1600));
        assertEquals(Math.floorMod(1324 + 3 * 800, 1600), Math.floorMod(p.blocks.get(1).restCountAtStart, 1600));
        assertEquals(1324, Math.floorMod(p.blocks.get(2).restCountAtStart, 1600));
        assertEquals(297.9, p.blocks.get(2).startAngleDeg, 1e-9);
        assertEquals(Arrays.asList(0, 3, 5), Arrays.asList(p.blocks.get(0).firstDot, p.blocks.get(1).firstDot,
                p.blocks.get(2).firstDot));
        // the count at every work dot: a quarter turn further each time inside the quarter-turn block
        for (int w = 5; w < 9; w++) {
            assertEquals(Math.floorMod(1324 + 400 * (w - 5), 1600), Math.floorMod(p.dotStartCount[w], 1600));
        }
        assertEquals(COUNT + GcodeStreamTest.steps(GcodeStreamTest.bTargets(p).get(15)), (long) p.workStartCount);
        String text = p.asString();
        assertTrue(text.contains("; block 2 (one turn, 2 dots): purge row, 4 flush dots"), text);
        assertTrue(text.contains("; block 3 (quarter turn, 4 dots): purge row, phase dot and 4 flush dots"), text);
        assertTrue(text.contains("block 3 starts at 297.9 deg"), text);
    }

    /** Every count: the phase dot of every partial block brings the screw to the start angle. */
    @Test
    public void everyPartialBlockStartsAtTheStartAngleForAnyCount() throws Exception {
        DispenseConfig c = cfg();
        for (long count : new long[] {0, 1, 799, 1324, 4321, -5000}) {
            GcodeProgram p = GcodeStreamBuilder.forBlocks(c, GcodeStreamTest.mapper(), threeBlocks(),
                    DispenseService.purgeSpecs(c, 17), 4, false, false, count, null);
            assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(p, c, "B"), "count " + count);
            assertEquals(1324, Math.floorMod(p.blocks.get(0).restCountAtStart, 1600), "count " + count);
            assertEquals(1324, Math.floorMod(p.blocks.get(2).restCountAtStart, 1600), "count " + count);
        }
        // the screw extrudes with negative B: the count falls, the angles hold
        c.bExtrudeSign = -1;
        GcodeProgram p = GcodeStreamBuilder.forBlocks(c, GcodeStreamTest.mapper(), threeBlocks(),
                DispenseService.purgeSpecs(c, 17), 4, false, false, 777L, null);
        assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(p, c, "B"));
        assertEquals(1324, Math.floorMod(p.blocks.get(2).restCountAtStart, 1600));
    }

    /** A partial block needs the step count; whole turns do not. Too few purge spots are refused. */
    @Test
    public void countAndPurgeSpotsAreRequired() throws Exception {
        DispenseConfig c = cfg();
        Exception e = assertThrows(IllegalStateException.class, () -> GcodeStreamBuilder.forBlocks(c,
                GcodeStreamTest.mapper(), threeBlocks(), DispenseService.purgeSpecs(c, 17), 4, false, false, null, null));
        assertTrue(e.getMessage().contains("M114 Count"), e.getMessage());
        c.profile.netStepsPerDot = 1600;
        List<GcodeStreamBuilder.BlockSpec> whole = new ArrayList<>();
        whole.add(new GcodeStreamBuilder.BlockSpec(1600, dots(2, 100, 80, 0), "one turn"));
        whole.add(new GcodeStreamBuilder.BlockSpec(1600, dots(2, 100, 84, 2), "one turn"));
        GcodeProgram p = GcodeStreamBuilder.forBlocks(c, GcodeStreamTest.mapper(), whole,
                DispenseService.purgeSpecs(c, 8 + 4), 4, false, false, null, null);
        assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(p, c, "B"));
        assertNull(p.dotStartCount);
        assertNull(p.blocks.get(1).restCountAtStart);
        DispenseConfig d = cfg();
        e = assertThrows(IllegalStateException.class, () -> GcodeStreamBuilder.forBlocks(d, GcodeStreamTest.mapper(),
                threeBlocks(), DispenseService.purgeSpecs(d, 16), 4, false, false, (long) COUNT, null));
        assertEquals("this program lays 3 dot sizes: it needs 17 purge spots (8 to start and 9 for the block switches), "
                + "the purge row has 16 free: wipe the purge row and reset it", e.getMessage());
        // rc1 review: a row too short for the program gives no spots at all; the text counts its free ones, not 0
        d.purgeRow.usedSpots = 10;
        e = assertThrows(IllegalStateException.class, () -> GcodeStreamBuilder.forBlocks(d, GcodeStreamTest.mapper(),
                threeBlocks(), DispenseService.purgeSpecs(d, 17), 4, false, false, (long) COUNT, null));
        assertTrue(e.getMessage().endsWith("the purge row has 14 free: wipe the purge row and reset it"), e.getMessage());
        d.purgeRow.spots = 12;
        d.purgeRow.usedSpots = 0;
        e = assertThrows(IllegalStateException.class, () -> GcodeStreamBuilder.forBlocks(d, GcodeStreamTest.mapper(),
                threeBlocks(), DispenseService.purgeSpecs(d, 17), 4, false, false, (long) COUNT, null));
        assertTrue(e.getMessage().endsWith("the purge row has 12 free: give the row more spots"), e.getMessage());
    }

    /**
     * A plan of one dose (the profile's half turn, two dots per pad) builds byte for byte the
     * program the builder made before dose blocks (golden file and times written by that builder).
     */
    @Test
    public void onePlanOfOneDoseIsTodaysProgram() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        c.needleXyTrim.dxMm = 0.02;
        c.needleXyTrim.dyMm = -0.01;
        DispensePlan plan = twoDotPlan(c);
        ToyMapper m = new ToyMapper(150, 100, 31.5, 100);
        GcodeProgram p = GcodeStreamBuilder.forPlan(c, m, plan, DispenseService.purgeSpecs(c), true, true, 12345L);
        GcodeStreamTest.assertGolden("golden-800-two-dot.gcode", p.asString());
        assertEquals(20.648213214299464, p.estimatedSeconds, 1e-12);
        assertEquals(9.437370517351379, p.estimatedWorkSeconds, 1e-12);
        assertEquals(19.4325250442495, p.estimatedPressurisedSeconds, 1e-12);
        assertEquals(18924L, (long) p.workStartCount);
        assertEquals(11.540169266799252, p.dotArriveSeconds[0], 1e-12);
        assertEquals(20.073413214299464, p.dotEndSeconds[11], 1e-12);
        assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(p, c, "B"));
        assertEquals(1, p.blocks.size());
        // the plan's dots made without their dose fields take the pads' (none here: the profile's)
        assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(GcodeStreamBuilder.forPlan(c, m, plan,
                DispenseService.purgeSpecs(c, plan), true, true, 12345L), c, "B"));
    }

    /** Six pads with two dots each, hand-made (no planner): dots without dose fields. */
    static DispensePlan twoDotPlan(DispenseConfig c) {
        DispensePlan plan = new DispensePlan();
        plan.planId = "golden-800";
        plan.boardName = "two-dot pads";
        plan.boardZMm = 5.5;
        int k = 0;
        for (int p = 0; p < 6; p++) {
            DispensePlan.PlannedPad pad = new DispensePlan.PlannedPad();
            pad.padId = "C" + (p / 2 + 1) + "." + (p % 2 + 1);
            pad.xMm = 100 + (p / 2) * 4.0;
            pad.yMm = 80 + (p % 2) * 1.6;
            plan.pads.add(pad);
            for (int d = 0; d < 2; d++) {
                DispensePlan.PlannedDot dot = new DispensePlan.PlannedDot();
                dot.index = k++;
                dot.padId = pad.padId;
                dot.xMm = pad.xMm + (d == 0 ? -0.47 : 0.47) * (p % 2 == 0 ? 1 : -1);
                dot.yMm = pad.yMm;
                dot.surfaceZMm = 5.5;
                dot.dispenseZMm = 5.5 - 2.59 + 0.10;
                dot.hopZMm = dot.dispenseZMm + 1;
                dot.rotationDeg = c.profile.netDeg(c);
                plan.dots.add(dot);
            }
        }
        return plan;
    }

    /**
     * The per-dot times of the board drawing's clock extend over the blocks: the work time is the
     * cross to block 1, every block's span and every switch between them.
     */
    @Test
    public void dotTimesAddUpOverTheBlocks() throws Exception {
        DispenseConfig c = cfg();
        GcodeProgram p = GcodeStreamBuilder.forBlocks(c, GcodeStreamTest.mapper(), threeBlocks(),
                DispenseService.purgeSpecs(c, 17), 4, false, false, (long) COUNT, null);
        DotTimesTest.assertOrdered(p, 9);
        double sum = 0;
        for (GcodeProgram.BlockInfo b : p.blocks) {
            int last = b.firstDot + b.dots - 1;
            double span = p.dotEndSeconds[last] - p.dotArriveSeconds[b.firstDot];
            assertTrue(b.switchSeconds > 0);
            sum += b.switchSeconds + span;
            if (b.firstDot == 0) {
                // block 1's time: the cross from the last purge dot, then its dots
                assertEquals(b.switchSeconds + span, b.seconds, 1e-9);
            }
            else {
                // from the last flush dot: the cross only, not the way to the purge row and its dots
                assertEquals(p.dotArriveSeconds[b.firstDot] - p.dotEndSeconds[b.firstDot - 1], b.switchSeconds, 1e-9);
                assertTrue(b.seconds > span && b.seconds < b.switchSeconds + span, b.seconds + " " + span);
            }
            assertEquals(b.dots * b.netSteps * 2.11 / 1600 / b.seconds, b.demandMgS, 1e-9);
        }
        assertEquals(p.estimatedWorkSeconds, sum, 1e-9);
        // a switch lays 4 or 5 purge-row dots: each takes at least its screw turn and dwell
        assertTrue(p.blocks.get(2).switchSeconds > 5 * 0.25);
    }

    /**
     * The needle floor per segment: the switch's purge-row dots lie on the blank (the anchor's
     * floor) even between two blocks of a board that sits lower; the board's dots take its floor.
     */
    @Test
    public void switchDotsKeepTheAnchorFloor() throws Exception {
        DispenseConfig c = BoardFrameReferenceTest.cfg(-1.0); // board 1 mm under the blank anchor 5.5
        c.zFrameToleranceMm = 0.5;
        c.profile.purgeDots = 2; // 2 to start, 4 flush dots for the one turn
        double boardFloor = c.needleFloorZMm(4.5);
        assertEquals(2.31, c.needleFloorZMm(), 1e-9);
        double z = 4.5 - 2.59 + 0.10; // a dot 0.1 above the lower board: under the anchor floor, above the board's
        assertTrue(z < c.needleFloorZMm() && z > boardFloor);
        List<GcodeStreamBuilder.BlockSpec> blocks = new ArrayList<>();
        blocks.add(new GcodeStreamBuilder.BlockSpec(800, Arrays.asList(new GcodeStreamBuilder.DotSpec(290, 125, z,
                z + 1, 0, 0, "A")), "half turn"));
        blocks.add(new GcodeStreamBuilder.BlockSpec(1600, Arrays.asList(new GcodeStreamBuilder.DotSpec(292, 125, z,
                z + 1, 0, 1, "B")), "one turn"));
        List<GcodeStreamBuilder.DotSpec> ok = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            ok.add(PurgeFloorTest.purge(3.01, k));
        }
        ToyMapper m = new ToyMapper(290, 125, 31.5, 0);
        GcodeProgram p = GcodeStreamBuilder.forBlocks(c, m, blocks, ok, 4, false, false, 0L, 4.5);
        assertEquals(Double.valueOf(4.5), p.floorReferenceZMm);
        assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(p, c, "B",
                GcodeStreamValidator.zWindow(c, m, p.floorReferenceZMm)));
        // a flush dot between the floors is refused, although the board dots around it are fine
        List<GcodeStreamBuilder.DotSpec> low = new ArrayList<>(ok);
        low.set(4, PurgeFloorTest.purge(2.20, 4));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> GcodeStreamBuilder.forBlocks(c, m, blocks, low, 4, false, false, 0L, 4.5));
        assertTrue(e.getMessage().startsWith("I4: dot " + GcodeStreamBuilder.PURGE_INDEX + " (purge#4) at N2 Z 2.200 "
                + "is below the needle floor 2.310 (needle-frame surface"), e.getMessage());
        // a block on its own reference (a coupon place) takes its own floor
        blocks.get(1).floorRefZ = 5.5;
        e = assertThrows(IllegalStateException.class, () -> GcodeStreamBuilder.forBlocks(c, m, blocks, ok, 4, false,
                false, 0L, 4.5));
        assertTrue(e.getMessage().startsWith("I4: dot 1 (B)"), e.getMessage());
    }

    /** A planned board of three doses: blocks of the plan, purge spots from the row, a valid program. */
    @Test
    public void aPlanOfThreeDosesStreamsBlockByBlock() throws Exception {
        DispenseConfig c = cfg();
        c.profile.startAngleSetAtMs = 1L;
        DispensePlan plan = PlannerDoseTest.threeDosePlan(c);
        assertEquals(3, plan.blocks.size());
        List<GcodeStreamBuilder.DotSpec> purge = DispenseService.purgeSpecs(c, plan);
        assertEquals(8 + 4 + 5, purge.size());
        // the profile's 8 purge spots alone are too few for the switches
        assertThrows(IllegalStateException.class, () -> GcodeStreamTest.board(c, GcodeStreamTest.mapper(), plan, false,
                false, (long) COUNT));
        GcodeProgram full = GcodeStreamBuilder.forPlan(c, GcodeStreamTest.mapper(), plan, purge, false, false,
                (long) COUNT);
        assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(full, c, "B"));
        assertEquals(plan.dots.size(), full.dotCount);
        for (int k = 0; k < 3; k++) {
            assertEquals(plan.blocks.get(k).netSteps, full.blocks.get(k).netSteps);
            assertEquals(plan.blocks.get(k).firstDot, full.blocks.get(k).firstDot);
        }
        // the work dots' lines carry their pad, in the plan's pad order
        for (GcodeProgram.Line l : full.lines) {
            if (l.has(GcodeProgram.Marker.DOT_BEGIN) && l.dotIndex >= 0) {
                assertEquals(plan.dots.get(l.dotIndex).padId, plan.pads.get(l.padIndex).padId);
                assertEquals(plan.dots.get(l.dotIndex).netSteps, l.netSteps);
            }
        }
        DoseBlocks.fillDemand(plan, full);
        for (DispensePlan.Block b : plan.blocks) {
            assertTrue(b.demandMgS > 0);
        }
    }

    /**
     * The screw angle the plan gives each pad is the one the program dispenses it at (the step
     * count before the pad's first dot), for any count and either screw direction.
     */
    @Test
    public void everyPadStartsAtThePlannedAngle() throws Exception {
        for (int sign : new int[] {1, -1}) {
            for (long count : new long[] {0, 1234, -777}) {
                DispenseConfig c = cfg();
                c.bExtrudeSign = sign;
                DispensePlan plan = PlannerDoseTest.threeDosePlan(c);
                GcodeProgram p = GcodeStreamBuilder.forPlan(c, GcodeStreamTest.mapper(), plan,
                        DispenseService.purgeSpecs(c, plan), false, false, count);
                assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(p, c, "B"));
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (DispensePlan.PlannedDot d : plan.dots) {
                    if (seen.add(d.padId)) {
                        assertEquals(plan.pads.get(d.padIndex).startPhase,
                                Math.floorMod(p.dotStartCount[d.index], 1600L),
                                "pad " + d.padId + ", sign " + sign + ", count " + count);
                    }
                }
                assertEquals(10, seen.size());
            }
        }
    }
}
