package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.DotGeometry;
import org.openpnp.instancelabs.padprofile.Tip;

/**
 * Tip setups (§22.2, §22.8): the planner with a measured setup. A dose the 25G's dot table does not know is held back
 * as DOSE_UNKNOWN with its warning, while a pad of a known dose is laid with the 25G's own d95 and OD and is still
 * MAY_BRIDGE when too close; after a recipe edit (an empty table) every pad is held back; Run's checkDoses refuses an
 * unknown dose; and the planner refuses to plan without a tip.
 */
public class PlannerMeasuredTipTest {
    static final String K25 = "25g-straight";

    static Tip.Measured m(double mean, double d95) {
        Tip.Measured x = new Tip.Measured();
        x.dMeanMm = mean;
        x.d95Mm = d95;
        x.recordId = 1;
        x.atMs = 1000;
        return x;
    }

    /** A measured 25G straight: quarter and half turns in its dot table, no one-turn record, weighed. */
    static Tip measured25() {
        Tip t = new Tip(K25, "25G straight", 0.25, 0.51, 400);
        t.setupVersion = 1;
        t.dispenser = TipSetup.DEFAULT_DISPENSER;
        t.measured.put(400, m(0.58, 0.64));
        t.measured.put(800, m(0.74, 0.80));
        t.mgPerStep = 0.0009;
        t.mgPerStepConfirmed = true;
        return t;
    }

    static DispenseConfig cfg25() {
        DispenseConfig c = GcodeBlocksTest.cfg();
        c.tip = TipSetupsTest.straight25().toSpec();
        return c;
    }

    static PadType of25(PadType t) {
        PadType x = t.copy();
        x.tipKey = K25;
        return x;
    }

    static List<PadGeometry> board(PadType tab, PadType sot) {
        List<PadGeometry> l = new ArrayList<>();
        l.addAll(PlannerDoseTest.part("U1", "TAB_PKG", 102, 90, 4.0, 2.0, 1.5, tab));
        l.add(PlannerDoseTest.pad("Q1", "SOT_Q", "1", 110, 90, 0, 1.475, 0.6, sot));
        l.add(PlannerDoseTest.pad("Q1", "SOT_Q", "2", 110, 92, 0, 1.475, 0.6, sot));
        return l;
    }

    @Test
    public void anUnknownDoseIsHeldBackAndAKnownOneLaidWithTheTipsOwnSizes() {
        DispenseConfig c = cfg25();
        PadType tab = of25(PlannerDoseTest.TAB);
        PadType sot = of25(PlannerDoseTest.SOT);
        DispensePlan p = new DispensePlanner(c, new PadTypeResolver(Arrays.asList(tab, sot), null))
                .withTip(measured25()).plan("measured", board(tab, sot), 77.5, 40);
        assertEquals(K25, p.setupKey);
        assertEquals("25G straight", p.setupName);
        assertEquals(1, (int) p.setupVersion);
        for (String id : new String[] {"U1.1", "U1.2"}) {
            DispensePlan.PlannedPad u = PlannerDoseTest.padOf(p, id);
            assertTrue(u.flags.contains(PadFlag.DOSE_UNKNOWN), u.flagNames().toString());
            assertTrue(u.isBlocked());
            assertEquals(0, u.dotCount);
        }
        assertTrue(p.warnings.contains("2 pads get no paste (DOSE_UNKNOWN): the 25G straight never measured one-turn "
                + "dots at this recipe and paste: measure its dots (Setup › Dispenser & tip)."), p.warnings.toString());
        // the quarter turns: two per pad, spread to the 25G's d95/2 + OD/2 (0.32 + 0.255), not the 22G's 0.86
        List<double[]> expected = DotGeometry.layoutN(1.475, 0.6, 2, 0.64 / 2 + 0.51 / 2, 0.64 / 2);
        double pitch = expected.get(1)[0] - expected.get(0)[0];
        List<DispensePlan.PlannedDot> q = new ArrayList<>();
        for (DispensePlan.PlannedDot d : p.dots) {
            if (d.padId.equals("Q1.1")) {
                q.add(d);
            }
        }
        assertEquals(2, q.size());
        assertEquals(pitch, Math.abs(q.get(1).xMm - q.get(0).xMm), 1e-9);
        assertTrue(pitch < 0.86 - 0.1, "the 22G's pitch would be 0.86: " + pitch);
        assertFalse(PlannerDoseTest.padOf(p, "Q1.1").isBlocked());
    }

    @Test
    public void aTooClosePadOfAKnownDoseIsStillMayBridge() {
        DispenseConfig c = cfg25();
        PadType half = of25(PlannerDoseTest.type("half-1", "Half one", 0.8, 0.95, 800, 1));
        // two parts whose half-turn dots (d95 0.80) come 0.10 mm apart
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(PlannerDoseTest.pad("R1", "HALF", "1", 100, 80, 0, 0.8, 0.95, half));
        pads.add(PlannerDoseTest.pad("R2", "HALF", "1", 100.9, 80, 0, 0.8, 0.95, half));
        DispensePlan p = new DispensePlanner(c, new PadTypeResolver(Arrays.asList(half), null))
                .withTip(measured25()).plan("close", pads, 77.5, 40);
        assertTrue(PlannerDoseTest.padOf(p, "R1.1").flags.contains(PadFlag.MAY_BRIDGE), p.warnings.toString());
        assertFalse(PlannerDoseTest.padOf(p, "R1.1").flags.contains(PadFlag.DOSE_UNKNOWN));
    }

    @Test
    public void afterARecipeEditEveryPadIsDoseUnknown() {
        DispenseConfig c = cfg25();
        PadType sot = of25(PlannerDoseTest.SOT);
        PadType profileDose = of25(PlannerDoseTest.chip0603()); // no dose of its own: the profile's half turn
        Tip edited = measured25();
        edited.measured.clear(); // the table is per recipe: nothing measured at the new dwell yet
        edited.mgPerStep = null;
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(PlannerDoseTest.pad("Q1", "SOT_Q", "1", 110, 90, 0, 1.475, 0.6, sot));
        pads.addAll(PlannerDoseTest.part("R1", "R_0603_1608Metric", 100, 80, 1.65, 0.8, 0.95, profileDose));
        DispensePlan p = new DispensePlanner(c, new PadTypeResolver(Arrays.asList(sot, profileDose), null))
                .withTip(edited).plan("edited", pads, 77.5, 40);
        for (DispensePlan.PlannedPad x : p.pads) {
            assertTrue(x.flags.contains(PadFlag.DOSE_UNKNOWN), x.padId + " " + x.flagNames());
        }
        assertTrue(p.dots.isEmpty());
    }

    @Test
    public void runRefusesAPlannedDoseTheTipDoesNotKnow() {
        DispenseConfig c = cfg25();
        PadType sot = of25(PlannerDoseTest.SOT);
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(PlannerDoseTest.pad("Q1", "SOT_Q", "1", 110, 90, 0, 1.475, 0.6, sot));
        DispensePlan p = new DispensePlanner(c, new PadTypeResolver(Arrays.asList(sot), null)).withTip(measured25())
                .plan("q", pads, 77.5, 40);
        assertFalse(p.dots.isEmpty());
        Interlocks.Inputs in = InterlocksTest.ok();
        in.cfg.tip = c.tip;
        in.plan = p;
        Tip later = measured25();
        later.measured.remove(400);
        in.tip = later;
        InterlockReport r = Interlocks.check(in);
        assertTrue(r.toString().contains("PLAN: pad type sot-q v1 has quarter-turn dots, which the 25G straight never "
                + "measured at this recipe and paste: measure its dots (Setup › Dispenser & tip)"), r.toString());
        in.tip = measured25();
        assertFalse(Interlocks.check(in).toString().contains("never measured"), Interlocks.check(in).toString());
    }

    @Test
    public void aVersionWithoutATipKeyFromTheStoredRowsIsBelowTheGateUnderAnotherTip() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            PadType v = db.currentPadType("chip-0603", Tip.SEED_KEY).copy();
            v.status = PadTypeStatus.CHARACTERIZED;
            v.tipKey = null; // as every version written before tip setups
            v.dotSteps = 400;
            v.dotsPerPad = 2;
            db.insertPadTypeVersion(v);
            // a resolver of the newest rows as stored (pad_types_current), not of the 25G's composed view
            List<PadType> raw = new ArrayList<>();
            for (PadType t : db.currentPadTypes(Tip.SEED_KEY)) {
                List<PadType> h = db.padTypeHistory(t.typeKey);
                raw.add(h.get(h.size() - 1));
            }
            PadTypeResolver r = new PadTypeResolver(raw, null);
            PadType chip = r.resolve("R_0603_1608Metric", Double.NaN, Double.NaN, Double.NaN);
            assertEquals("chip-0603", chip.typeKey);
            assertEquals(null, chip.tipKey);
            assertEquals(PadTypeStatus.CHARACTERIZED, chip.status);
            List<PadGeometry> pads = PlannerDoseTest.part("R1", "R_0603_1608Metric", 100, 80, 1.65, 0.8, 0.95, chip);
            DispensePlan p = new DispensePlanner(cfg25(), r).withTip(measured25()).plan("raw", pads, 77.5, 40);
            for (DispensePlan.PlannedPad x : p.pads) {
                assertTrue(x.flags.contains(PadFlag.STATUS_BELOW_MIN), x.padId + " " + x.flagNames());
            }
            assertTrue(p.dots.isEmpty());
            assertTrue(p.warnings.contains(chip.name + " was calibrated with another tip (" + Tip.SEED_KEY + "); the mounted "
                    + "tip is the 25G straight. Mass per turn and dot size change with the tip: calibrate it again for "
                    + "this tip. 2 pads of chip-0603 get no paste (STATUS_BELOW_MIN)."), p.warnings.toString());
            // the same rows with the 22G mounted: its own version
            DispensePlan p22 = new DispensePlanner(GcodeBlocksTest.cfg(), r).withTip(Tip.g22()).plan("raw", pads,
                    77.5, 40);
            for (DispensePlan.PlannedPad x : p22.pads) {
                assertFalse(x.flags.contains(PadFlag.STATUS_BELOW_MIN), x.padId + " " + x.flagNames());
            }
            assertFalse(p22.dots.isEmpty());
            assertEquals(Tip.SEED_KEY, PadInstances.typeInfo(chip, null).tipKey);
        }
    }

    @Test
    public void thePlannerRefusesWithoutATip() {
        DispenseConfig c = cfg25();
        PadType sot = of25(PlannerDoseTest.SOT);
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(PlannerDoseTest.pad("Q1", "SOT_Q", "1", 110, 90, 0, 1.475, 0.6, sot));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DispensePlanner(c, new PadTypeResolver(Arrays.asList(sot), null)).plan("q", pads, 77.5, 40));
        assertEquals("the planner needs the mounted tip", e.getMessage());
    }
}
