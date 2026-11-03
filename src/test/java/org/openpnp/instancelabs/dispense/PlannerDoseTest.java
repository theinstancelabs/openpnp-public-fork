package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.DoseModel;
import org.openpnp.instancelabs.padprofile.DotGeometry;
import org.openpnp.instancelabs.padprofile.Texts;

/**
 * The planner with per-type doses (profile calibration design §6.1, §1.2 R5/R6): the type's dose and
 * dots per pad, blocks (the profile's dose first, then descending), every pad's dots in a row, the
 * screw angle each pad starts at; one dot per pad keeps today's order. Main-thread decisions 4
 * (a part with an undispensable pad gets no paste), 5/16 (a type of another tip is below the gate)
 * and 11/14 (a pad whose dots may bridge is flagged on its own).
 */
public class PlannerDoseTest {
    static PadType type(String key, String name, double w, double h, Integer steps, Integer perPad) {
        PadType t = new PadType(key, "Custom", name, w, h, PadType.Layout.FOOTPRINT_ONLY, null, 1.0, null, null);
        t.version = 1;
        t.status = PadTypeStatus.CHARACTERIZED;
        t.dotSteps = steps;
        t.dotsPerPad = perPad;
        return t;
    }

    static PadType chip0603() {
        PadType t = DispenseDb.seedPadTypes().get(1);
        t.version = 1;
        t.status = PadTypeStatus.CHARACTERIZED;
        return t;
    }

    static PadGeometry pad(String ref, String pkg, String name, double x, double y, double rot, double w, double h,
            PadType t) {
        PadGeometry g = new PadGeometry(ref + "." + name, x, y, rot, w, h, 5.5, t);
        g.placementId = ref;
        g.packageId = pkg;
        g.padName = name;
        return g;
    }

    /** A two-pad part along x (pitch p) at (x, y). */
    static List<PadGeometry> part(String ref, String pkg, double x, double y, double pitch, double w, double h,
            PadType t) {
        return new ArrayList<>(Arrays.asList(pad(ref, pkg, "1", x - pitch / 2, y, 0, w, h, t),
                pad(ref, pkg, "2", x + pitch / 2, y, 0, w, h, t)));
    }

    static final PadType TAB = type("tab-2x15", "Tab 2.0 x 1.5", 2.0, 1.5, 1600, 2);
    static final PadType SOT = type("sot-q", "SOT quarter", 1.475, 0.6, 400, 2);

    /** Two 0603 resistors (profile dose), a two-tab part (2 one-turn dots each), two SOT-like parts (2 quarter turns). */
    static List<PadGeometry> threeDoseBoard() {
        PadType chip = chip0603();
        List<PadGeometry> l = new ArrayList<>();
        l.addAll(part("R1", "R_0603_1608Metric", 100, 80, 1.65, 0.8, 0.95, chip));
        l.addAll(part("R2", "R_0603_1608Metric", 110, 80, 1.65, 0.8, 0.95, chip));
        l.addAll(part("U1", "TAB_PKG", 102, 90, 4.0, 2.0, 1.5, TAB));
        // pads 2 mm apart along y, dots along the pads' long side
        l.add(pad("Q1", "SOT_Q", "1", 110, 90, 0, 1.475, 0.6, SOT));
        l.add(pad("Q1", "SOT_Q", "2", 110, 92, 0, 1.475, 0.6, SOT));
        l.add(pad("Q2", "SOT_Q", "1", 120, 90, 0, 1.475, 0.6, SOT));
        l.add(pad("Q2", "SOT_Q", "2", 120, 92, 0, 1.475, 0.6, SOT));
        return l;
    }

    static DispensePlanner planner(DispenseConfig c) {
        PadTypeResolver r = new PadTypeResolver(Arrays.asList(chip0603(), TAB, SOT), null);
        DispenseConfig.PurgeRow row = c.purgeRow;
        return new DispensePlanner(c, r).withPurgeSpots(k -> k < row.spotsLeft() ? row.spot(row.usedSpots + k) : null);
    }

    static DispensePlan threeDosePlan(DispenseConfig c) {
        DispensePlan p = planner(c).plan("three doses", threeDoseBoard(), 77.5, 40);
        p.planId = "three-doses";
        p.boardZMm = 5.5;
        return p;
    }

    static DispensePlan.PlannedPad padOf(DispensePlan p, String id) {
        for (DispensePlan.PlannedPad x : p.pads) {
            if (x.padId.equals(id)) {
                return x;
            }
        }
        throw new AssertionError(id);
    }

    @Test
    public void perTypeDoseAndDots() {
        DispenseConfig c = GcodeBlocksTest.cfg();
        DispensePlan p = threeDosePlan(c);
        assertTrue(p.blockedPads().isEmpty(), p.warnings.toString());
        assertEquals(4 + 4 + 8, p.dots.size());
        DispensePlan.PlannedPad r = padOf(p, "R1.1");
        assertEquals(Integer.valueOf(800), r.dotSteps);
        assertEquals(1, r.dotsPerPad);
        assertEquals(800 / 4.44, r.rotationPerDotDeg, 1e-12);
        DispensePlan.PlannedPad u = padOf(p, "U1.1");
        assertEquals(Integer.valueOf(1600), u.dotSteps);
        assertEquals(2, u.dotsPerPad);
        assertEquals(2, u.dotCount);
        assertEquals(1600 / 4.44, u.rotationPerDotDeg, 1e-12);
        // one-turn dots spread to the same-pad pitch d/2 + OD/2 = 1.345/2 + 0.72/2 along the 2 mm side
        List<double[]> expected = DotGeometry.layoutN(2.0, 1.5, 2, 1.345 / 2 + 0.36, 1.345 / 2);
        assertEquals(1.345 / 2 + 0.36, expected.get(1)[0] - expected.get(0)[0], 1e-9);
        Set<Double> xs = new HashSet<>();
        for (DispensePlan.PlannedDot d : p.dots) {
            if (d.padId.equals("U1.1")) {
                assertEquals(1600, d.netSteps);
                assertEquals(1600 / 4.44, d.rotationDeg, 1e-12);
                assertEquals(90, d.yMm, 1e-12);
                xs.add(Math.round((d.xMm - 100) * 1e6) / 1e6);
            }
        }
        assertEquals(new HashSet<>(Arrays.asList(Math.round(expected.get(0)[0] * 1e6) / 1e6,
                Math.round(expected.get(1)[0] * 1e6) / 1e6)), xs);
        // the quarter turns: 2 per pad, spread to 0.86 mm (dHi 1.00 + the OD) as the suggestion lays them
        DispensePlan.PlannedPad q = padOf(p, "Q1.1");
        assertEquals(Integer.valueOf(400), q.dotSteps);
        assertEquals(2, q.dotCount);
        double r400 = DoseModel.dHiMm(400) / 2;
        assertEquals(0.86, DotGeometry.layoutN(1.475, 0.6, 2, r400 + 0.36, r400).get(1)[0] * 2, 0.005);
    }

    /** Blocks: the profile's dose first, then descending; indices run across them; pads whole (R5). */
    @Test
    public void blocksAndWholePads() {
        DispenseConfig c = GcodeBlocksTest.cfg();
        DispensePlan p = threeDosePlan(c);
        assertEquals(3, p.blocks.size());
        int[][] want = {{800, 4, 4}, {1600, 4, 2}, {400, 8, 4}}; // steps, dots, pads
        int next = 0;
        for (int k = 0; k < 3; k++) {
            DispensePlan.Block b = p.blocks.get(k);
            assertEquals(k, b.index);
            assertEquals(want[k][0], b.netSteps);
            assertEquals(want[k][1], b.dots);
            assertEquals(want[k][2], b.pads);
            assertEquals(next, b.firstDot);
            assertEquals(next + b.dots - 1, b.lastDot);
            for (int i = b.firstDot; i <= b.lastDot; i++) {
                assertEquals(i, p.dots.get(i).index);
                assertEquals(k, p.dots.get(i).block);
                assertEquals(b.netSteps, p.dots.get(i).netSteps);
            }
            next = b.lastDot + 1;
        }
        assertEquals("half turn", p.blocks.get(0).turn);
        assertTrue(p.blocks.get(0).phaseDot);
        assertEquals(7, p.blocks.get(0).flushDots);
        assertFalse(p.blocks.get(1).phaseDot, "a whole turn needs no start angle");
        assertTrue(p.blocks.get(2).phaseDot);
        assertEquals(4, p.blocks.get(2).flushDots);
        assertTrue(p.summary().endsWith(" · 3 dot sizes: half turn ×4, one turn ×4, quarter turn ×8"), p.summary());
        // R5: a pad's dots follow one another; padIndex names the pad
        Set<String> done = new HashSet<>();
        String cur = null;
        for (DispensePlan.PlannedDot d : p.dots) {
            assertEquals(d.padId, p.pads.get(d.padIndex).padId);
            if (!d.padId.equals(cur)) {
                assertTrue(done.add(d.padId), d.padId + " resumes");
                cur = d.padId;
            }
        }
        // the 400 block starts from its switch's last flush spot (8 + 4 + 5 - 1 = 16th spot: X 100): Q1 first
        assertEquals("Q1.1", p.dots.get(p.blocks.get(2).firstDot).padId);
        // nearest first inside a pad: from Q1.1's last dot the next pad is Q1.2, entered at its nearer dot
        assertTrue(p.pathLengthMm <= p.pathLengthNearestNeighbourMm + 1e-9);
        assertEquals(17, DoseBlocks.purgeSpotsNeeded(p, c));
    }

    /** The screw angle each pad starts at: 1324 / 524 for half turns; whole turns where block 1 ended. */
    @Test
    public void startPhase() {
        DispenseConfig c = GcodeBlocksTest.cfg();
        DispensePlan p = threeDosePlan(c);
        List<Integer> half = new ArrayList<>();
        List<Integer> quarter = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DispensePlan.PlannedDot d : p.dots) {
            if (seen.add(d.padId)) {
                DispensePlan.PlannedPad pad = padOf(p, d.padId);
                if (d.netSteps == 800) {
                    half.add(pad.startPhase);
                }
                else if (d.netSteps == 400) {
                    quarter.add(pad.startPhase);
                }
                else {
                    assertEquals(Math.floorMod(1324 + 4 * 800, 1600), pad.startPhase);
                }
            }
        }
        assertEquals(Arrays.asList(1324, 524, 1324, 524), half);
        assertEquals(Arrays.asList(1324, 524, 1324, 524), quarter);
    }

    /** One profile dot per pad: the order and the path lengths of the planner before dose blocks. */
    @Test
    public void oneDotPerPadKeepsTodaysOrder() {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        PadType chip = chip0603();
        List<PadGeometry> pads = new ArrayList<>();
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 40; i++) {
            double rot = rnd.nextInt(4) * 90;
            pads.addAll(rotated("R" + i, 100 + rnd.nextDouble() * 40, 80 + rnd.nextDouble() * 30, rot, chip));
        }
        DispensePlan p = new DispensePlanner(c, new PadTypeResolver(Collections.singletonList(chip), null))
                .plan("random", pads, 77.5, 40);
        // the old planner: nearest neighbour + 2-opt over every dot from the start (the dots are the pad centres)
        List<PadGeometry> laid = new ArrayList<>();
        for (int i = 0; i < pads.size(); i++) {
            if (!p.pads.get(i).isBlocked()) {
                laid.add(pads.get(i));
            }
        }
        double[] xs = new double[laid.size()];
        double[] ys = new double[laid.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = laid.get(i).xMm;
            ys[i] = laid.get(i).yMm;
        }
        PathOrder.Result o = PathOrder.order(xs, ys, 77.5, 40, c.twoOptMaxPasses);
        assertEquals(laid.size(), p.dots.size());
        for (int i = 0; i < o.order.length; i++) {
            assertEquals(laid.get(o.order[i]).padId, p.dots.get(i).padId, "dot " + i);
            assertEquals(xs[o.order[i]], p.dots.get(i).xMm, 0);
        }
        assertEquals(o.length, p.pathLengthMm, 0);
        assertEquals(o.nearestNeighbourLength, p.pathLengthNearestNeighbourMm, 0);
        assertEquals(1, p.blocks.size());
        assertFalse(p.summary().contains("dot sizes"));
    }

    static List<PadGeometry> rotated(String ref, double x, double y, double rot, PadType t) {
        double c = Math.cos(Math.toRadians(rot)), s = Math.sin(Math.toRadians(rot));
        return new ArrayList<>(Arrays.asList(
                pad(ref, "R_0603_1608Metric", "1", x - 0.825 * c, y - 0.825 * s, rot, 0.8, 0.95, t),
                pad(ref, "R_0603_1608Metric", "2", x + 0.825 * c, y + 0.825 * s, rot, 0.8, 0.95, t)));
    }

    /** §6.5 PLAN refusals: a dose the tip cannot make, and less than half a turn per pad (R6). */
    @Test
    public void r6AndTipMinimumRefusals() {
        Interlocks.Inputs in = InterlocksTest.ok();
        DispenseConfig c = in.cfg;
        c.purgeRow.spots = 24;
        in.plan = threeDosePlan(c);
        in.purgeDots = DoseBlocks.purgeSpotsNeeded(in.plan, c);
        InterlockReport r = Interlocks.check(in);
        assertFalse(r.has("PLAN"), r.toString());
        // a tip whose smallest dose is a half turn
        c.tip.minDotSteps = 800;
        r = Interlocks.check(in);
        assertTrue(r.toString().contains("PLAN: " + Texts.interlockDose("sot-q", 1, 400, "22G tip", 800)), r.toString());
        assertTrue(r.toString().contains("pad type sot-q v1 has quarter-turn dots; the 22G tip takes quarter, half or "
                + "one-turn dots (a half turn at least): edit it and plan again"), r.toString());
        // one quarter turn per pad
        c.tip.minDotSteps = 400;
        PadType one = type("sot-q1", "SOT one quarter", 1.475, 0.6, 400, 1);
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(pad("Q9", "SOT_Q1", "1", 110, 90, 0, 1.475, 0.6, one));
        pads.add(pad("Q9", "SOT_Q1", "2", 110, 92, 0, 1.475, 0.6, one));
        in.plan = new DispensePlanner(c, new PadTypeResolver(Collections.singletonList(one), null)).plan("r6", pads,
                77.5, 40);
        in.purgeDots = DoseBlocks.purgeSpotsNeeded(in.plan, c);
        r = Interlocks.check(in);
        assertTrue(r.toString().contains("PLAN: pad type sot-q1 v1 gives each pad 400 steps (a quarter turn); every pad "
                + "needs at least half a screw turn in a row, or its paste varies more with the screw angle than the "
                + "validated 0603 pads: use more dots or a larger turn"), r.toString());
    }

    /** Decisions 5 and 16: a type calibrated with another tip is below the gate in plans too, with the tip text. */
    @Test
    public void typeOfAnotherTipIsBelowTheGate() {
        Interlocks.Inputs in = InterlocksTest.ok();
        DispenseConfig c = in.cfg;
        PadType chip = chip0603();
        chip.tipKey = "ad25g";
        List<PadGeometry> pads = part("R1", "R_0603_1608Metric", 100, 80, 1.65, 0.8, 0.95, chip);
        DispensePlan p = new DispensePlanner(c, new PadTypeResolver(Collections.singletonList(chip), null))
                .plan("tip", pads, 77.5, 40);
        for (DispensePlan.PlannedPad x : p.pads) {
            assertEquals(Collections.singletonList("STATUS_BELOW_MIN"), x.flagNames());
            assertEquals("ad25g", x.typeTipKey);
            assertEquals(0, x.dotCount);
        }
        assertTrue(p.warnings.contains(Texts.belowGateTip("0603", "ad25g", "22G tip") + " 2 pads of chip-0603 get no "
                + "paste (STATUS_BELOW_MIN)."), p.warnings.toString());
        assertTrue(p.warnings.get(0).startsWith("0603 was calibrated with another tip (ad25g); the mounted tip is the 22G "
                + "tip."), p.warnings.toString());
        // the plan is not stale for it: the pads would not pass now either (no plan-again loop)
        in.plan = p;
        c.refuseFlaggedPads = false;
        assertFalse(Interlocks.check(in).toString().contains("would pass now"), Interlocks.check(in).toString());
        // the 25G tip mounted after planning: these pads would pass now, plan again
        c.tip.key = "ad25g";
        assertTrue(Interlocks.check(in).toString().contains("would pass now"), Interlocks.check(in).toString());
        // the other way round: planned with a matching tip, then the tip changed
        DispensePlan q = new DispensePlanner(c, new PadTypeResolver(Collections.singletonList(chip), null))
                .plan("tip", pads, 77.5, 40);
        assertTrue(q.blockedPads().isEmpty());
        in.plan = q;
        c.tip.key = "ad22tta-b";
        assertTrue(Interlocks.check(in).toString().contains("PLAN: the tip changed after planning: 2 pad(s) of this "
                + "plan have pad types calibrated with another tip than the 22G tip; plan again"),
                Interlocks.check(in).toString());
    }

    /**
     * Decisions 11 and 14: only the pads whose own neighbour is too close get MAY_BRIDGE; the rest keep
     * their dots. (A half turn fits there, so the tip can dispense these pads: the type's one turn is what bridges.)
     */
    @Test
    public void bridgingPadsAreFlaggedOneByOne() {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        PadType chip = type("chip-0603-one", "0603 one turn", 0.8, 0.95, 1600, 1);
        List<PadGeometry> pads = new ArrayList<>();
        pads.addAll(part("R1", "R_0603_1608Metric", 100, 80, 1.65, 0.8, 0.95, chip));
        // R3.1 at 102.275: 1.45 mm from R1.2, the 1.345 mm one-turn dots leave 0.105 mm
        pads.addAll(part("R3", "R_0603_1608Metric", 103.1, 80, 1.65, 0.8, 0.95, chip));
        pads.addAll(part("R4", "R_0603_1608Metric", 110, 80, 1.65, 0.8, 0.95, chip));
        DispensePlan p = new DispensePlanner(c, new PadTypeResolver(Collections.singletonList(chip), null))
                .plan("bridge", pads, 77.5, 40);
        Set<String> flagged = new HashSet<>();
        for (DispensePlan.PlannedPad x : p.pads) {
            if (x.flags.contains(PadFlag.MAY_BRIDGE)) {
                flagged.add(x.padId);
                assertTrue(x.isBlocked());
                assertEquals(0, x.dotCount);
                assertEquals(1, x.dotsPerPad);
            }
        }
        assertEquals(new HashSet<>(Arrays.asList("R1.2", "R3.1")), flagged);
        assertEquals(4, p.dots.size());
        assertTrue(p.warnings.contains("2 pads may bridge: their dots would come within 0.20 mm of another pad's dots "
                + "(R1.2 to R3.1, R3.1 to R1.2); they get no paste (MAY_BRIDGE). A pad type for this size avoids it."),
                p.warnings.toString());
        // measured dots smaller than the model's: no bridge (1.45 - 1.20 = 0.25 mm)
        DispensePlan q = new DispensePlanner(c, new PadTypeResolver(Collections.singletonList(chip), null))
                .withDotDiameters(Collections.singletonMap(1600, 1.20)).plan("bridge", pads, 77.5, 40);
        assertTrue(q.blockedPads().isEmpty(), q.warnings.toString());
    }

    /**
     * Decision 4: a part with a pad the tip cannot dispense is treated whole (none of its pads pasted)
     * unless its package is set to paste the dispensable pads anyway; the setting is part of the
     * resolver's fingerprint (a plan made before it changed is stale).
     */
    @Test
    public void aPartWithAnUndispensablePadIsTreatedWhole() {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        PadType mixed = type("mixed", "Mixed", 2.0, 2.0, null, null);
        mixed.status = PadTypeStatus.PROVISIONAL;
        List<PadGeometry> pads = new ArrayList<>();
        // two 0.25 mm pins 0.5 mm apart (no dot fits between them) and a 2 x 2 mm tab
        pads.add(pad("J1", "MIXED", "1", 99.75, 80, 0, 0.25, 1.0, mixed));
        pads.add(pad("J1", "MIXED", "2", 100.25, 80, 0, 0.25, 1.0, mixed));
        pads.add(pad("J1", "MIXED", "3", 100, 83, 0, 2.0, 2.0, mixed));
        pads.addAll(part("R1", "R_0603_1608Metric", 110, 80, 1.65, 0.8, 0.95, chip0603()));
        List<PadType> types = Arrays.asList(mixed, chip0603());
        PadTypeResolver plain = new PadTypeResolver(types, null);
        DispensePlan p = new DispensePlanner(c, plain).plan("mixed", pads, 77.5, 40);
        DispensePlan.PlannedPad tab = padOf(p, "J1.3");
        assertTrue(tab.flags.contains(PadFlag.PART_NOT_DISPENSABLE), tab.flagNames().toString());
        assertEquals(0, tab.dotCount);
        // the pins themselves: the tip cannot dispense them, whatever their type says
        for (String pin : new String[] {"J1.1", "J1.2"}) {
            assertEquals(Collections.singletonList("PART_NOT_DISPENSABLE"), padOf(p, pin).flagNames());
            assertEquals(0, padOf(p, pin).dotCount);
        }
        assertTrue(padOf(p, "R1.1").flags.isEmpty());
        assertTrue(p.warnings.contains("1 pad of 1 part gets no paste (J1): another pad of the part cannot be dispensed "
                + "with the 22G tip, and a part soldered by hand gets no paste on its other pads either "
                + "(PART_NOT_DISPENSABLE). Profile can paste the dispensable pads of a package anyway."),
                p.warnings.toString());
        assertTrue(p.warnings.contains("2 pads of 1 part get no paste (J1): the 22G tip cannot dispense them, also "
                + "where the other pads of the part are pasted (PART_NOT_DISPENSABLE)."), p.warnings.toString());
        assertEquals(2, p.dots.size());
        // paste the dispensable pads of MIXED anyway: the tab gets its dot, the pins still none
        PadTypeResolver partial = new PadTypeResolver(types, null).withPastePartial(Collections.singleton("MIXED"));
        DispensePlan q = new DispensePlanner(c, partial).plan("mixed", pads, 77.5, 40);
        assertTrue(padOf(q, "J1.3").flags.isEmpty(), padOf(q, "J1.3").flagNames().toString());
        assertEquals(1, padOf(q, "J1.3").dotCount);
        assertEquals(Collections.singletonList("PART_NOT_DISPENSABLE"), padOf(q, "J1.1").flagNames());
        assertEquals(3, q.dots.size());
        assertNotEquals(plain.fingerprint(), partial.fingerprint());
        assertEquals(plain.fingerprint(), new PadTypeResolver(types, null).withPastePartial(null).fingerprint());
    }

    /**
     * Decisions 14 and 4: a 0603 pad 1.10 mm from U1 pin 1 (the tip's smallest dots leave 0.10 mm)
     * holds back U1 and R9 whole; U2, the same package with nothing near, keeps its paste.
     */
    @Test
    public void aPadTooCloseToANeighbourHoldsBackItsPartNotThePackage() {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        PadType pin = type("sot223-pin", "SOT-223 pin", 1.067, 2.083, 1600, 2);
        PadType tab = type("sot223-tab", "SOT-223 tab", 3.448, 2.048, 800, 4);
        List<PadGeometry> pads = new ArrayList<>();
        for (String ref : new String[] {"U1", "U2"}) {
            double x = ref.equals("U1") ? 100 : 130;
            for (int i = 0; i < 3; i++) {
                pads.add(pad(ref, "SOT-223", String.valueOf(i + 1), x - 2.3 + 2.3 * i, 76.85, 0, 1.067, 2.083, pin));
            }
            pads.add(pad(ref, "SOT-223", "4", x, 83.15, 0, 3.448, 2.048, tab));
        }
        pads.add(pad("R9", "R_0603_1608Metric", "1", 96.6, 76.85, 0, 0.8, 0.95, chip0603()));
        pads.add(pad("R9", "R_0603_1608Metric", "2", 94.95, 76.85, 0, 0.8, 0.95, chip0603()));
        List<PadType> types = Arrays.asList(chip0603(), pin, tab);
        DispensePlan p = new DispensePlanner(c, new PadTypeResolver(types, null)).plan("near", pads, 77.5, 40);
        for (String id : new String[] {"U2.1", "U2.2", "U2.3", "U2.4"}) {
            assertTrue(padOf(p, id).flags.isEmpty(), id + " " + padOf(p, id).flagNames());
            assertEquals(id.equals("U2.4") ? 4 : 2, padOf(p, id).dotCount, id);
        }
        for (String id : new String[] {"U1.1", "U1.2", "U1.3", "U1.4", "R9.1", "R9.2"}) {
            assertEquals(Collections.singletonList("PART_NOT_DISPENSABLE"), padOf(p, id).flagNames(), id);
        }
        assertEquals(10, p.dots.size());
        for (String w : p.warnings) {
            assertFalse(w.contains("U2"), w);
        }
        // SOT-223 pasted partly: U1's other pads get their dots, pin 1 still none
        DispensePlan q = new DispensePlanner(c, new PadTypeResolver(types, null)
                .withPastePartial(Collections.singleton("SOT-223"))).plan("near", pads, 77.5, 40);
        assertEquals(Collections.singletonList("PART_NOT_DISPENSABLE"), padOf(q, "U1.1").flagNames());
        for (String id : new String[] {"U1.2", "U1.3", "U1.4"}) {
            assertTrue(padOf(q, id).flags.isEmpty(), id + " " + padOf(q, id).flagNames());
        }
        assertEquals(18, q.dots.size());
    }

    /**
     * Decision 14: a pad too close to a neighbour is held back also when every pad of its part is: R2
     * sits 0.85 mm from R1 and R3 (the tip's smallest dot would land on their copper), while R4, far
     * off, keeps its dots.
     */
    @Test
    public void aPartMadeOnlyOfPadsTooCloseToANeighbourIsHeldBack() {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        PadType chip = chip0603();
        List<PadGeometry> pads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            pads.addAll(part("R" + (i + 1), "R_0603_1608Metric", 100 + 2.5 * i, 80, 1.65, 0.8, 0.95, chip));
        }
        pads.addAll(part("R4", "R_0603_1608Metric", 115, 80, 1.65, 0.8, 0.95, chip));
        DispensePlan p = new DispensePlanner(c, new PadTypeResolver(Collections.singletonList(chip), null))
                .plan("row", pads, 77.5, 40);
        for (String id : new String[] {"R1.1", "R1.2", "R2.1", "R2.2", "R3.1", "R3.2"}) {
            assertEquals(Collections.singletonList("PART_NOT_DISPENSABLE"), padOf(p, id).flagNames(), id);
        }
        assertEquals(2, p.dots.size());
        assertTrue(padOf(p, "R4.1").flags.isEmpty());
    }

    /**
     * Decision 4 where no dot would bridge: a DO-220AA mapped whole to its tab's type (4 half turns).
     * The pin is too narrow for any plan (a half-turn dot reaches 0.28 mm past it), so neither the pin
     * nor the tab gets paste; with the package set to paste partly, only the tab.
     */
    @Test
    public void theUndispensablePinOfAPartIsNeverPasted() {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        PadType tab = type("do220-tab", "DO-220AA tab", 2.515, 2.388, 800, 4);
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(pad("D1", "DO-220AA", "1", 100, 80, 0, 0.61, 1.118, tab));
        pads.add(pad("D1", "DO-220AA", "2", 103, 80, 0, 2.515, 2.388, tab));
        List<PadType> types = Collections.singletonList(tab);
        DispensePlan p = new DispensePlanner(c, new PadTypeResolver(types, null)).plan("do220", pads, 77.5, 40);
        assertEquals(Collections.singletonList("PART_NOT_DISPENSABLE"), padOf(p, "D1.1").flagNames());
        assertEquals(Collections.singletonList("PART_NOT_DISPENSABLE"), padOf(p, "D1.2").flagNames());
        assertTrue(p.dots.isEmpty());
        DispensePlan q = new DispensePlanner(c, new PadTypeResolver(types, null)
                .withPastePartial(Collections.singleton("DO-220AA"))).plan("do220", pads, 77.5, 40);
        assertEquals(Collections.singletonList("PART_NOT_DISPENSABLE"), padOf(q, "D1.1").flagNames());
        assertTrue(padOf(q, "D1.2").flags.isEmpty(), padOf(q, "D1.2").flagNames().toString());
        assertEquals(4, q.dots.size());
        for (DispensePlan.PlannedDot d : q.dots) {
            assertEquals("D1.2", d.padId);
        }
    }
}
