package org.openpnp.instancelabs.padprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** The decision of §7.6: measured dots, pooled evidence, the weighing, the amount band, the control, never validated. */
public class DeciderTest {
    static DoseEvidence measured(int steps, double dMean, double d95, Double mgPerDot, int readings) {
        DoseEvidence e = new DoseEvidence(steps);
        e.opticalFresh = true;
        e.opticalPass = true;
        e.n = 76;
        e.hops = 120;
        e.dMeanMm = dMean;
        e.d95Mm = d95;
        e.dCv = (d95 / dMean - 1) / 1.65;
        e.phaseStat = 0.09;
        if (mgPerDot != null) {
            e.mgPerDot = mgPerDot;
            e.mgPerStep = mgPerDot / steps;
            e.massReadings = readings;
            e.massConfirmed = readings >= 2;
        }
        return e;
    }

    /** A package of a fixture at package level, its pads mapped to a provisional type with a plan. */
    static Decider.Inputs inputs(String board, String pkg, int steps, int perPad, DoseEvidence... ev) {
        Map<String, List<PadInstance>> pads = PadFixtures.packageLevel(PadFixtures.load(board));
        CheckInputs in = PadFixtures.inputs(pads.get(pkg));
        PadInstance p = in.pads.get(0);
        TypeInfo t = new TypeInfo();
        t.key = "t";
        t.name = "T";
        t.version = 1;
        t.status = TypeInfo.PROVISIONAL;
        t.padWMm = p.wMm;
        t.padHMm = p.hMm;
        t.padRoundness = p.roundness;
        t.dotSteps = steps;
        t.dotsPerPad = perPad;
        t.origin = "suggested";
        SuggesterTest.type(in, t, pkg);
        for (DoseEvidence e : ev) {
            in.evidence.put(e.steps, e);
        }
        Decider.Inputs d = new Decider.Inputs();
        d.check = in;
        d.group = ProfileCheck.of(in).groups.get(0).group;
        d.steps = steps;
        d.perPad = perPad;
        d.controlPass = true;
        d.regulatorAttested = true;
        return d;
    }

    static Decider.Check check(Decider.Decision d, String key) {
        for (Decider.Check c : d.checks) {
            if (c.key.equals(key)) {
                return c;
            }
        }
        return null;
    }

    @Test
    public void measuredDotsTurnTheMarginalSoic8IntoAPass() {
        Decider.Inputs in = inputs("mobo", "SOIC-8_3.9x4.9mm_P1.27mm", 400, 2,
                measured(400, 0.84, 0.92, DoseModel.massMg(400), 2));
        Decider.Decision d = Decider.decide(in);
        assertEquals(Decider.CHARACTERIZED, d.outcome);
        assertEquals(400, d.steps);
        assertEquals(2, d.perPad);
        assertFalse(d.replaced);
        assertNull(d.why);
        assertEquals(1.055, d.massPerPadMg, 0.0005);
        assertEquals(1.08, d.targetMg, 0.005);
        assertEquals(-2, d.errorPct);
        assertTrue(check(d, "geometry").measured.startsWith("overhang 0.12, dot gap 0.35"), check(d, "geometry").measured);
        for (Decider.Check c : d.checks) {
            assertTrue(c.ok, c.key);
        }
        assertEquals(7, Math.round(d.padRipplePct[0]));
    }

    @Test
    public void wideMeasuredDotsLeaveTheSoic8Provisional() {
        Decider.Decision d = Decider.decide(inputs("mobo", "SOIC-8_3.9x4.9mm_P1.27mm", 400, 2,
                measured(400, 1.02, 1.10, DoseModel.massMg(400), 2)));
        assertEquals(Decider.PROVISIONAL, d.outcome);
        assertEquals("the dots, up to 1.10 mm wide, leave 0.17 mm to the next pad's dot (0.30 needed)", d.why);
    }

    @Test
    public void aLaterPassingRecordDoesNotEraseAFailure() {
        Random rnd = new Random(30);
        LadderStats.Record bad = LadderStatsTest.record(rnd, 400, 59, 0.10, 0.05, 0);
        for (int i = 0; i < 10; i++) {
            bad.dots.get(5 * i).threadToNext = true;
        }
        LadderStats.Record good = LadderStatsTest.record(rnd, 400, 59, 0.10, 0.05, 0);
        DoseEvidence e = LadderStats.of(400, Arrays.asList(bad, good), null).toEvidence();
        e.dMeanMm = 0.84;
        e.d95Mm = 0.92;
        LadderStats.mass(400, Arrays.asList(new LadderStats.MassReading(1, 400, 136, 136 * 0.5275),
                new LadderStats.MassReading(2, 400, 136, 136 * 0.53)), null).applyTo(e);
        Decider.Decision d = Decider.decide(inputs("mobo", "SOIC-8_3.9x4.9mm_P1.27mm", 400, 2, e));
        assertEquals(Decider.PROVISIONAL, d.outcome);
        assertEquals("quarter-turn dots trailed a paste thread to the next dot 10 times in 120 moves (at most 7 allowed)",
                d.why);
        assertTrue(check(d, "mass").ok);
    }

    @Test
    public void oneReadingIsNotAConfirmedMass() {
        Decider.Decision d = Decider.decide(inputs("mobo", "SOIC-8_3.9x4.9mm_P1.27mm", 400, 2,
                measured(400, 0.84, 0.92, 0.5275, 1)));
        assertEquals(Decider.PROVISIONAL, d.outcome);
        assertEquals("the amount was weighed once; a second weighing (another coupon or another job) confirms it", d.why);
        d = Decider.decide(inputs("mobo", "SOIC-8_3.9x4.9mm_P1.27mm", 400, 2, measured(400, 0.84, 0.92, null, 0)));
        assertEquals("the quarter turn was not weighed in this job: Calibrate again weighs it", d.why);
        d = Decider.decide(inputs("mobo", "SOIC-8_3.9x4.9mm_P1.27mm", 400, 2, measured(400, 0.84, 0.92, 0.5275, 2)));
        assertEquals(Decider.CHARACTERIZED, d.outcome);
    }

    @Test
    public void theAmountBandEdges() {
        // SOIC-8 target: a 0.25 mm print of its 1.151 mm² opening
        double target = Limits.PASTE_DENSITY_MG_MM3 * DotGeometry.padAreaMm2(1.95, 0.60, 50) * Limits.CAP_PRINT_MM;
        double[][] cases = {{-0.25, 1}, {-0.255, 0}, {0.10, 1}, {0.105, 0}};
        for (double[] c : cases) {
            double perDot = target * (1 + c[0]) / 2;
            Decider.Decision d = Decider.decide(inputs("mobo", "SOIC-8_3.9x4.9mm_P1.27mm", 400, 2,
                    measured(400, 0.84, 0.92, perDot, 2)));
            assertEquals(c[1] == 1, check(d, "amount").ok, "error " + c[0]);
            assertEquals(c[1] == 1 ? Decider.CHARACTERIZED : Decider.PROVISIONAL, d.outcome, "error " + c[0]);
        }
        Decider.Decision d = Decider.decide(inputs("mobo", "SOIC-8_3.9x4.9mm_P1.27mm", 400, 2,
                measured(400, 0.84, 0.92, target * 0.70 / 2, 2)));
        assertEquals("2 quarter-turn dots give 0.76 mg per pad, 30 % less than the 1.08 mg target", d.why);
    }

    @Test
    public void aFailedControlStopsCharacterization() {
        Decider.Inputs in = inputs("mobo", "SOIC-8_3.9x4.9mm_P1.27mm", 400, 2, measured(400, 0.84, 0.92, 0.5275, 2));
        in.controlPass = false;
        in.controlText = LadderStats.control(800, 0.82, null, 18, null).text;
        Decider.Decision d = Decider.decide(in);
        assertEquals(Decider.PROVISIONAL, d.outcome);
        assertEquals("the control half-turn dots are 0.82 mm², 22 % below the bench's 1.06 mm²: check the regulator "
                + "(18 psi) and the paste before calibrating", d.why);
        in.controlPass = true;
        in.regulatorAttested = false;
        assertEquals("the regulator reading was not confirmed for this calibration", Decider.decide(in).why);
        in.regulatorAttested = true;
        in.recordsTipKey = "25g";
        assertEquals("the dots were measured with another tip (25g)", Decider.decide(in).why);
        // the job must say the control passed and the regulator was read: a caller that does not, fails closed
        Decider.Inputs fresh = new Decider.Inputs();
        assertFalse(fresh.controlPass);
        assertFalse(fresh.regulatorAttested);
        in.recordsTipKey = null;
        in.controlPass = fresh.controlPass;
        in.controlText = null;
        Decider.Decision d2 = Decider.decide(in);
        assertEquals(Decider.PROVISIONAL, d2.outcome);
        assertEquals("the control dots were not checked or did not match the bench", d2.why);
    }

    @Test
    public void anEditedPlanThatFailsIsReplacedAndSaysSo() {
        // your 1 half-turn dot on the 0.60 mm SOT-23 pad; the ladder also measured the quarter turn
        Decider.Inputs in = inputs("mobo", "SOT-23", 800, 1, measured(800, 1.16, 1.22, 1.056, 2),
                measured(400, 0.84, 0.92, 0.528, 2));
        in.edited = true;
        Decider.Decision d = Decider.decide(in);
        assertEquals(Decider.CHARACTERIZED, d.outcome);
        assertTrue(d.replaced);
        assertEquals(400, d.steps);
        assertEquals(2, d.perPad);
        assertEquals("your 1 half-turn dot reaches 0.28 mm past the pad: calibrated 2 quarter-turn dots instead",
                d.replacedText);
        // without evidence of another dose the plan stays, provisional
        in.check.evidence.remove(400);
        d = Decider.decide(in);
        assertEquals(Decider.PROVISIONAL, d.outcome);
        assertFalse(d.replaced);
        assertEquals("the dots, 1.16 mm wide on average, reach 0.28 mm past the 0.60 mm pad (0.20 allowed)", d.why);
    }

    @Test
    public void fallbackPlansNeverReachCharacterized() {
        // nano LED_0805: one turn is +33 % (decision 3's fallback), measured and weighed
        Decider.Decision d = Decider.decide(inputs("nano", "LED_0805_2012Metric", 1600, 1,
                measured(1600, 1.345, 1.40, 2.11, 2)));
        assertEquals(Decider.PROVISIONAL, d.outcome);
        assertEquals("fallback", d.fit);
        assertTrue(d.notes.contains("the closest amount that fits is +33 %"), d.notes.toString());
        assertFalse(d.notes.contains("you can raise it by hand after a test board"));
        // a fallback of the profile dose can be raised by hand after a test board
        d = Decider.decide(inputs("mobo", "D_SOD-123", 800, 1, measured(800, 1.16, 1.22, 1.056, 2)));
        assertEquals(Decider.PROVISIONAL, d.outcome);
        assertTrue(d.notes.contains("the closest amount that fits is -33 %"), d.notes.toString());
        assertTrue(d.notes.contains("you can raise it by hand after a test board"));
    }

    @Test
    public void validatedIsNeverWritten() {
        Decider.Inputs in = inputs("mobo", "SOIC-8_3.9x4.9mm_P1.27mm", 400, 2, measured(400, 0.84, 0.92, 0.5275, 2));
        TypeInfo t = in.check.types.get("t");
        t.status = TypeInfo.VALIDATED;
        for (PadInstance p : in.check.pads) {
            p.typeStatus = TypeInfo.VALIDATED;
        }
        Decider.Decision d = Decider.decide(in);
        assertEquals(Decider.PROVISIONAL, d.outcome);
        assertEquals("T is validated on a board: calibrating it again needs your confirmation before the start", d.why);
        in.validatedNamed = true;
        d = Decider.decide(in);
        assertEquals(Decider.CHARACTERIZED, d.outcome);
        assertFalse(TypeInfo.VALIDATED.equals(d.outcome));
    }

    @Test
    public void unknownGroupsAreRefused() {
        Decider.Inputs in = inputs("mobo", "SOIC-8_3.9x4.9mm_P1.27mm", 400, 2);
        in.group = "gnothere00000";
        boolean thrown = false;
        try {
            Decider.decide(in);
        }
        catch (IllegalArgumentException e) {
            thrown = true;
        }
        assertTrue(thrown);
    }
}
