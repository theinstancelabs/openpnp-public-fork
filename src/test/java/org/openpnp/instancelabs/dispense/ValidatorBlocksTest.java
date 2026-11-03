package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * The validator's screw-turn rules (profile calibration design §6.4), each on a hand-edited copy of
 * a valid three-block program (half, one and quarter turns, step count 1234): a wrong net turn, a
 * dose that is not a quarter, half or whole turn, an over-long purge dot, a block that starts off the
 * start angle, a dose change without a switch, a split pad and an over-long total.
 */
public class ValidatorBlocksTest {
    static GcodeProgram valid(DispenseConfig c) throws Exception {
        return GcodeStreamBuilder.forBlocks(c, GcodeStreamTest.mapper(), GcodeBlocksTest.threeBlocks(),
                DispenseService.purgeSpecs(c, 17), 4, false, false, (long) GcodeBlocksTest.COUNT, null);
    }

    /** A copy of a program with other lines (the same start state and blocks). */
    static GcodeProgram with(GcodeProgram p, List<GcodeProgram.Line> lines) {
        GcodeProgram q = new GcodeProgram();
        q.lines.addAll(lines);
        q.blocks = p.blocks;
        q.bCountAtStart = p.bCountAtStart;
        q.bZeroedAtStart = p.bZeroedAtStart;
        q.bStartSteps = p.bStartSteps;
        return q;
    }

    /** Every B target from line {@code from} (inclusive) to {@code to} (exclusive) moved by {@code steps}. */
    static GcodeProgram shiftB(GcodeProgram p, int from, int to, long steps) {
        List<GcodeProgram.Line> l = new ArrayList<>();
        for (int i = 0; i < p.lines.size(); i++) {
            GcodeProgram.Line x = p.lines.get(i);
            if (i >= from && i < to && x.text.startsWith("G1 B")) {
                String[] w = x.text.split(" ");
                double b = Double.parseDouble(w[1].substring(1)) + steps / 4.44;
                x = new GcodeProgram.Line("G1 B" + String.format(Locale.ROOT, "%.4f", b) + " " + w[2], x.marker,
                        x.dotIndex, x.padId, x.rotationDeg, x.extra, x.netSteps, x.padIndex);
            }
            l.add(x);
        }
        return with(p, l);
    }

    /** Line indices {begin, end} of every dot, purge-row dots included, in order. */
    static List<int[]> dots(GcodeProgram p) {
        List<int[]> l = new ArrayList<>();
        int begin = -1;
        for (int i = 0; i < p.lines.size(); i++) {
            GcodeProgram.Line x = p.lines.get(i);
            if (x.has(GcodeProgram.Marker.DOT_BEGIN)) {
                begin = i;
            }
            if (x.has(GcodeProgram.Marker.DOT_END)) {
                l.add(new int[] {begin, i});
            }
        }
        return l;
    }

    static boolean has(List<String> problems, String text) {
        return problems.stream().anyMatch(s -> s.contains(text));
    }

    @Test
    public void theValidProgramPasses() throws Exception {
        DispenseConfig c = GcodeBlocksTest.cfg();
        assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(valid(c), c, "B"));
    }

    @Test
    public void aWorkDotTurningOtherThanPlanned() throws Exception {
        DispenseConfig c = GcodeBlocksTest.cfg();
        GcodeProgram p = valid(c);
        int[] work0 = dots(p).get(8); // after the 8 purge dots
        List<String> v = GcodeStreamValidator.validate(shiftB(p, work0[0], work0[1] + 1, 10), c, "B");
        assertTrue(v.get(0).matches("line \\d+ '.*': dot 0 turns 810 steps, its plan says 800"), v.toString());
        assertTrue(has(v, "dot 0 turns 810 steps: not a quarter, half or whole turn (1600 steps)"), v.toString());
    }

    @Test
    public void aDoseThatIsNotAQuarterHalfOrWholeTurn() throws Exception {
        DispenseConfig c = GcodeBlocksTest.cfg();
        List<GcodeStreamBuilder.BlockSpec> b = new ArrayList<>();
        b.add(new GcodeStreamBuilder.BlockSpec(800, GcodeBlocksTest.dots(3, 100, 80, 0), "half turn"));
        b.add(new GcodeStreamBuilder.BlockSpec(533, GcodeBlocksTest.dots(2, 100, 84, 3), "a third"));
        GcodeProgram p = GcodeStreamBuilder.forBlocks(c, GcodeStreamTest.mapper(), b, DispenseService.purgeSpecs(c, 13),
                4, false, false, 1234L, null);
        List<String> v = GcodeStreamValidator.validate(p, c, "B");
        assertEquals(2, v.size(), v.toString());
        assertTrue(v.get(0).matches("line \\d+ '.*': dot 3 turns 533 steps: not a quarter, half or whole turn \\(1600 "
                + "steps\\)"), v.toString());
        // a quarter turn below the tip's smallest dose
        c.tip.minDotSteps = 800;
        assertTrue(has(GcodeStreamValidator.validate(valid(c), c, "B"), "dot 5 turns 400 steps: less than the 22G bent "
                + "taper takes (800 steps at least)"));
    }

    @Test
    public void aPurgeDotOfMoreThanARevolutionAndThePhaseTurn() throws Exception {
        DispenseConfig c = GcodeBlocksTest.cfg();
        GcodeProgram p = valid(c);
        int[] flush = dots(p).get(11); // the first one-turn flush dot (purge-row dot 8)
        List<String> v = GcodeStreamValidator.validate(shiftB(p, flush[0], p.lines.size(), 400), c, "B");
        assertTrue(has(v, "line " + (flush[0] + 1) + ": purge dot 8 turns 2000 steps, more than one revolution and the "
                + "20° phase turn"), v.toString());
    }

    /** The program built for another step count: every partial block starts a quarter turn off, at 28°. */
    @Test
    public void aBlockStartingOffTheStartAngle() throws Exception {
        DispenseConfig c = GcodeBlocksTest.cfg();
        GcodeProgram p = valid(c);
        GcodeProgram q = with(p, p.lines);
        q.bCountAtStart = p.bCountAtStart + 400;
        assertEquals(Arrays.asList("block 1 (half turn) starts at 27.9°, not at the start angle 297.9°",
                "block 3 (quarter turn) starts at 27.9°, not at the start angle 297.9°"),
                GcodeStreamValidator.validate(q, c, "B"));
        // a phase dot that turns 400 steps more than it was built to: block 3 only
        int[] phase = dots(p).get(8 + 3 + 4 + 2);
        List<String> v = GcodeStreamValidator.validate(shiftB(p, phase[0], p.lines.size(), 400), c, "B");
        assertTrue(v.get(0).matches("line \\d+ '.*': dot -3 turns 1200 steps, its plan says 800"), v.toString());
        assertEquals("block 3 (quarter turn) starts at 27.9°, not at the start angle 297.9°", v.get(1));
    }

    @Test
    public void aDoseChangeWithoutASwitch() throws Exception {
        DispenseConfig c = GcodeBlocksTest.cfg();
        GcodeProgram p = valid(c);
        List<int[]> d = dots(p);
        // drop the quarter turn's phase dot and flush dots, and the screw turns they made
        int from = d.get(17)[0];
        int to = d.get(21)[1] + 1;
        long removed = 0;
        List<long[]> nets = GcodeBlocksTest.nets(p);
        for (int i = 17; i < 22; i++) {
            removed += nets.get(i)[1];
        }
        GcodeProgram q = shiftB(p, to, p.lines.size(), -removed);
        List<GcodeProgram.Line> lines = new ArrayList<>(q.lines.subList(0, from));
        lines.addAll(q.lines.subList(to, q.lines.size()));
        List<String> v = GcodeStreamValidator.validate(with(p, lines), c, "B");
        assertEquals(Collections.singletonList("dots 4 and 5 have different turns without a block start (phase dot and "
                + "flush) between them"), v);
    }

    @Test
    public void aSplitPad() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        DispensePlan plan = new DispensePlan();
        plan.planId = "split";
        for (String id : new String[] {"A.1", "B.1"}) {
            DispensePlan.PlannedPad pad = new DispensePlan.PlannedPad();
            pad.padId = id;
            plan.pads.add(pad);
        }
        String[] order = {"A.1", "B.1", "A.1"};
        for (int i = 0; i < 3; i++) {
            DispensePlan.PlannedDot d = new DispensePlan.PlannedDot();
            d.index = i;
            d.padId = order[i];
            d.xMm = 100 + i;
            d.yMm = 80;
            d.dispenseZMm = 5.5 - 2.59 + 0.10;
            d.hopZMm = d.dispenseZMm + 1;
            d.rotationDeg = c.profile.netDeg(c);
            plan.dots.add(d);
        }
        GcodeProgram p = GcodeStreamTest.board(c, GcodeStreamTest.mapper(), plan, false, false, 0L);
        assertEquals(Collections.singletonList("pad A.1's dots are split (dots 0 and 2)"),
                GcodeStreamValidator.validate(p, c, "B"));
    }

    /**
     * Review 1: two pads of one footprint with the same name (KiCad shield tabs "6" and "6", which OpenPnP's
     * importer keeps) share the pad id J1.6. The planner's pad index keeps them apart: not a split pad.
     */
    @Test
    public void twoPadsOfOneNameAreNotASplitPad() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        PadType t = DispenseDb.seedPadTypes().get(1); // chip-0603
        t.status = PadTypeStatus.PROVISIONAL;
        t.version = 1;
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(new PadGeometry("J1.6", 100.0, 80.0, 0, 0.8, 0.95, 5.5, t));
        pads.add(new PadGeometry("J1.1", 103.0, 80.0, 0, 0.8, 0.95, 5.5, t));
        pads.add(new PadGeometry("J1.6", 106.0, 80.0, 0, 0.8, 0.95, 5.5, t));
        DispensePlan plan = TestTips.planner(c, new PadTypeResolver(Collections.singletonList(t), null))
                .plan("toy", pads, 77.5, 40);
        List<String> order = new ArrayList<>();
        for (DispensePlan.PlannedDot d : plan.dots) {
            order.add(d.padId + "@" + d.xMm);
        }
        assertEquals(Arrays.asList("J1.6@100.0", "J1.1@103.0", "J1.6@106.0"), order);
        GcodeProgram p = GcodeStreamTest.board(c, GcodeStreamTest.mapper(), plan, false, false, 0L);
        assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(p, c, "B"));
    }

    @Test
    public void aProgramTurningMoreThanItsDots() throws Exception {
        DispenseConfig c = GcodeBlocksTest.cfg();
        GcodeProgram p = valid(c);
        List<Double> b = GcodeStreamTest.bTargets(p);
        int sync = p.text().indexOf("M400");
        List<GcodeProgram.Line> lines = new ArrayList<>(p.lines);
        lines.add(sync, new GcodeProgram.Line(String.format(Locale.ROOT, "G1 B%.4f F64800",
                b.get(b.size() - 1) + 20000 / 4.44), GcodeProgram.Marker.NONE, -9, null, 0, null));
        List<String> v = GcodeStreamValidator.validate(with(p, lines), c, "B");
        // 3 x 800 + 2 x 1600 + 4 x 400 work steps and 17 purge-row dots of at most 1689
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).matches("the program turns the screw \\d+ steps; the plan allows at most "
                + (7200 + 17 * 1689)), v.toString());
    }
}
