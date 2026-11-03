package org.openpnp.instancelabs.padprofile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The suggestion of §4: order, rules, kinds, names, ids and confidence. */
public class SuggesterTest {
    static List<PadInstance> part(String ref, String pkg, double x, double y, double w, double h, double r, double... xs) {
        List<PadInstance> l = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            l.add(new PadInstance(ref, pkg, String.valueOf(i + 1), x + xs[i], y, 0, w, h, r));
        }
        return l;
    }

    static void type(CheckInputs in, TypeInfo t, String pkg) {
        in.types.put(t.key, t);
        for (PadInstance p : in.pads) {
            if (p.packageId.equals(pkg)) {
                p.typeKey = t.key;
                p.typeVersion = t.version;
                p.typeStatus = t.status;
                p.typeRule = "pattern";
            }
        }
    }

    /** The seed SOT-23 type (D/DispenseDb.seedPadTypes): pad 1.325 x 0.60, not started. */
    static TypeInfo sot23Seed() {
        TypeInfo t = new TypeInfo();
        t.key = "sot-23";
        t.name = "SOT-23";
        t.family = Family.SMALL_OUTLINE;
        t.version = 1;
        t.status = TypeInfo.NOT_STARTED;
        t.padWMm = 1.325;
        t.padHMm = 0.60;
        t.origin = "seed";
        return t;
    }

    static CheckInputs sot23(TypeInfo t) {
        Map<String, List<PadInstance>> mobo = PadFixtures.packageLevel(PadFixtures.load("mobo"));
        CheckInputs in = PadFixtures.inputs(mobo.get("SOT-23"));
        type(in, t, "SOT-23");
        return in;
    }

    @Test
    public void theSot23ExampleOfSection46() {
        ProfileCheck.Result r = ProfileCheck.of(sot23(sot23Seed()));
        ProfileCheck.Group g = r.groups.get(0);
        assertEquals(ProfileCheck.BELOW_GATE, g.reason);
        assertEquals("SOT-23 · 1.48 × 0.60 mm", g.label);
        assertEquals("SOT-23 is not calibrated yet (status: not started). Boards need pad types that are at least "
                + "characterized: calibrate it automatically or by hand.", g.reasonText);
        assertEquals("not calibrated yet", g.shortText);
        assertEquals(1.90, g.minPitchMm, 0.005);
        Suggestion s = g.suggestion;
        assertEquals(Suggestion.CALIBRATE_EXISTING, s.kind);
        assertEquals("sot-23", s.typeKey);
        assertEquals(Integer.valueOf(1), s.typeVersion);
        assertEquals("SOT-23", s.name);
        assertEquals(Family.SMALL_OUTLINE, s.family);
        assertNull(s.mapping);
        assertEquals("suggested", s.planFrom);
        assertEquals(1.475, s.opening.wMm, 1e-9);
        assertEquals(0.600, s.opening.hMm, 1e-9);
        assertEquals(50, s.opening.roundness, 1e-9);
        assertEquals(0.866, s.opening.areaMm2, 0.0005);
        assertEquals(1.26, s.amount.targetMg, 0.005);
        assertEquals(1.0, s.amount.k, 0);
        assertEquals(1.455, s.amount.mgPerMm2, 0.0005);
        assertFalse(s.amount.capped);
        assertEquals("1.46 mg per mm² of opening: the half turn you validated on 0603 pads (1.06 mg on 0.73 mm²)",
                s.amount.rule);
        assertEquals(2, s.dots.perPad);
        assertEquals(400, s.dots.steps);
        assertEquals("quarter turn", s.dots.turn);
        assertEquals(90.09, s.dots.netDeg, 0);
        assertEquals(0.53, s.dots.mgPerDot, 0.005);
        assertEquals("model", s.dots.mgSource);
        assertArrayEquals(new double[] {0.83, 1.00}, s.dots.diameterMm, 0.006);
        assertEquals("model", s.dots.dSource);
        assertEquals(2, s.dots.positions.size());
        assertArrayEquals(new double[] {-0.43, 0.0}, s.dots.positions.get(0), 0.0005);
        assertArrayEquals(new double[] {0.43, 0.0}, s.dots.positions.get(1), 0.0005);
        assertEquals(1.06, s.predicted.mgPerPad, 0.006);
        assertEquals(-16, s.predicted.errorPct);
        assertEquals(0.20, s.predicted.overhangMm, 0.0005);
        assertEquals(0.39, s.predicted.dotGapMm, 0.005);
        assertEquals(0.46, s.predicted.padGapMm, 0.005);
        assertArrayEquals(new double[] {7, 17}, s.predicted.padRipplePct, 0);
        assertEquals("marginal", s.fit);
        assertEquals("block", s.phase.rule);
        assertEquals("Quarter-turn dots are laid together after the half-turn dots; 5 dots on the purge row first bring "
                + "the screw to the same start angle, so every quarter turn repeats, and each pad gets its 2 dots in a row "
                + "(half a screw turn, like the 0603 pads).", s.phase.text);
        assertEquals("low", s.confidence);
        assertEquals(Arrays.asList("a quarter turn has never been dispensed on this machine",
                "no validated Small-outline discretes pads yet: the amount is scaled from the 0603 half turn",
                "marginal: the dots reach 0.20 mm past the 0.60 mm pad (0.20 wanted); the measured dot size decides",
                "no weighing at this recipe yet"), s.confidenceWhy);
        assertEquals("Calibrate SOT-23 for these pads: 2 quarter-turn dots per pad (0.83–1.00 mm wide), about 1.06 mg "
                + "per pad, 16 % less than the 1.26 mg these pads would get at your 0603 rate; the dots may reach 0.20 mm "
                + "past the pad edge (the calibration's measured dot decides).", s.sentence);
        assertEquals(Collections.singletonList(400), s.calibration.doses);
        assertEquals(800, s.calibration.control);
        assertEquals(Collections.singletonList(400), s.calibration.weigh);
        assertEquals(800, s.alternatives.get(0).steps);
        assertEquals(1, s.alternatives.get(0).perPad);
        assertEquals(1.055, s.alternatives.get(0).mgPerPad, 0.0005);
        assertEquals("a half-turn dot (1.16 mm) reaches 0.28 mm past the 0.60 mm pad", s.alternatives.get(0).why);
        assertTrue(s.id.matches("s[0-9a-f]{12}"));
        assertTrue(g.group.matches("g[0-9a-f]{12}"));
        // the anchor the amount came from is listed once
        assertEquals(1, r.anchors.size());
        assertTrue(r.anchors.get(0).bench);
        assertEquals("the half turn you validated on 0603 pads after reflow (1.06 mg on 0.73 mm², weighed 2026-10-27)",
                r.anchors.get(0).text);
        assertEquals(1.455, r.anchors.get(0).mgPerMm2, 0.0005);
    }

    /** §14.3's board: J, Q, U, T, and R1-R4 covered by chip-0603 (characterized). */
    static CheckInputs padcalBoard() {
        List<PadInstance> pads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pads.addAll(part("R" + (i + 1), "R_0603_1608Metric", 10 + 5 * i, 10, 0.80, 0.95, 50, -0.825, 0.825));
        }
        pads.addAll(part("J1", "SW_SPST_3.9x2.9mm", 10, 20, 0.864, 1.829, 18, -2.0, 2.0));
        pads.addAll(part("J2", "SW_SPST_3.9x2.9mm", 20, 20, 0.864, 1.829, 18, -2.0, 2.0));
        for (int i = 0; i < 3; i++) {
            pads.addAll(part("Q" + (i + 1), "Test_Strip_0.62x1.50", 10 + 6 * i, 30, 0.62, 1.50, 0, -1.0, 1.0));
        }
        pads.addAll(part("T1", "TestChip_A", 10, 40, 0.80, 0.95, 50, -0.825, 0.825));
        PadFixtures.Fixture mobo = PadFixtures.load("mobo");
        String wson = null;
        for (PadFixtures.Pad p : mobo.pads) {
            if (p.packageId.startsWith("WSON-8") && (wson == null || wson.equals(p.ref))) {
                wson = p.ref;
                PadInstance i = PadFixtures.instance(p);
                i.ref = "U1";
                i.padId = "U1." + p.padName;
                i.x = 30 + p.localX;
                i.y = 40 + p.localY;
                i.rotationDeg = p.rotationDeg - mobo.parts.stream().filter(q -> q.ref.equals(p.ref)).findFirst().get().rotationDeg;
                pads.add(i);
            }
        }
        CheckInputs in = PadFixtures.inputs(pads);
        type(in, FixtureSuggestionsTest.chip0603(), "R_0603_1608Metric");
        return in;
    }

    @Test
    public void thePadCalibrationBoardOfSection143() {
        ProfileCheck.Result r = ProfileCheck.of(padcalBoard());
        assertEquals(3, r.fixable);
        assertEquals(2, r.tipBlocking);
        assertEquals(5, r.groups.size());
        for (ProfileCheck.Group g : r.groups) {
            String pkg = g.packageIds.get(0);
            Suggestion s = g.suggestion;
            switch (pkg) {
                case "SW_SPST_3.9x2.9mm":
                    assertEquals(ProfileCheck.NO_TYPE, g.reason);
                    assertEquals(Suggestion.NEW, s.kind);
                    assertEquals(2, s.dots.perPad);
                    assertEquals(800, s.dots.steps);
                    assertEquals(-8, s.predicted.errorPct);
                    assertEquals("No pad type for SW_SPST_3.9x2.9mm (0.86 × 1.83 mm openings, 4 pads on 2 parts): these "
                            + "pads get no paste until a pad type is chosen.", g.reasonText);
                    // dots 0.94 apart (the needle face clears the first dot), overhang 0.15
                    assertEquals(0.94, s.dots.positions.get(1)[1] - s.dots.positions.get(0)[1], 0.0005);
                    assertEquals(0.15, s.predicted.overhangMm, 0.005);
                    assertEquals("profile", s.phase.rule);
                    assertEquals("medium", s.confidence);
                    break;
                case "Test_Strip_0.62x1.50":
                    assertEquals(Suggestion.NEW, s.kind);
                    assertEquals(2, s.dots.perPad);
                    assertEquals(400, s.dots.steps);
                    assertEquals(-22, s.predicted.errorPct);
                    assertEquals("pass", s.fit);
                    assertEquals("test-strip-0-62x1-50-150x62", s.typeKey);
                    assertEquals("Test_Strip_0.62x1.50 · 1.50 × 0.62 mm", s.name);
                    assertEquals(Family.CHIP, s.family);
                    assertEquals("Test_Strip_0.62x1.50", s.mapping.packageId);
                    assertNull(s.mapping.padNames);
                    assertEquals(s.opening.areaMm2, s.pad.areaMm2, 0);
                    assertEquals("New pad type Test_Strip_0.62x1.50 · 1.50 × 0.62 mm: 2 quarter-turn dots per pad "
                            + "(0.83–1.00 mm wide), about 1.06 mg per pad, 22 % less than the 1.35 mg these pads would get "
                            + "at your 0603 rate; at most 0.19 mm past the pad edge.", s.sentence);
                    assertEquals(2.0, g.hopMm, 0.0005);
                    break;
                case "TestChip_A":
                    assertEquals(ProfileCheck.NO_TYPE, g.reason);
                    assertEquals(Suggestion.USE, s.kind);
                    assertEquals("chip-0603", s.typeKey);
                    assertEquals("TestChip_A", s.mapping.packageId);
                    assertNull(s.mapping.padNames);
                    assertEquals("Use Chip 0603: same pad (0.80 × 0.95 mm; these are 0.80 × 0.95 mm). 1 half-turn dot "
                            + "per pad, 1.06 mg, dots 1.16 mm wide.", s.sentence);
                    assertEquals("medium", s.confidence);
                    break;
                default:
                    assertTrue(pkg.startsWith("WSON-8"), pkg);
                    assertEquals(ProfileCheck.TIP, g.reason);
                    assertEquals(Suggestion.NONE, s.kind);
                    break;
            }
        }
    }

    @Test
    public void the0805GetsOneTurnBecauseTwoHalfTurnsOverhang() {
        Map<String, List<PadInstance>> mobo = PadFixtures.packageLevel(PadFixtures.load("mobo"));
        Suggestion s = ProfileCheck.of(PadFixtures.inputs(mobo.get("R_0805_2012Metric"))).groups.get(0).suggestion;
        assertEquals(1600, s.dots.steps);
        assertEquals(1, s.dots.perPad);
        assertEquals("pass", s.fit);
        // the profile dose comes first in the order, and is the first alternative with why it lost
        assertEquals(800, s.alternatives.get(0).steps);
        assertEquals(2, s.alternatives.get(0).perPad);
        assertEquals("2 half-turn dots (1.16 mm) reach 0.35 mm past the 1.40 mm pad", s.alternatives.get(0).why);
        assertEquals("block", s.phase.rule);
        assertEquals("One-turn dots are laid together after the half-turn dots; 4 dots on the purge row first flush the "
                + "screw (a whole turn needs no start angle), and one per pad.", s.phase.text);
    }

    @Test
    public void theProfileDoseComesFirstAndNoPadGetsLessThanHalfATurn() {
        // ftp's R_0603: one half turn passes; one turn would be +100 %, so the profile dose wins on class anyway
        ProfileCheck.Ctx c = ProfileCheck.prepare(PadFixtures.inputs(
                PadFixtures.packageLevel(PadFixtures.load("ftp")).get("R_0603_1608Metric")));
        ProfileCheck.Group g = c.groups.get(0);
        assertEquals(800, g.plan.steps);
        assertEquals(0, g.plan.cls);
        List<Suggester.Candidate> all = Suggester.candidates(c, g, c.views);
        assertEquals(800, all.get(0).steps);
        for (Suggester.Candidate x : all) {
            assertTrue(x.steps * x.n >= 800, "R6: " + x.n + " x " + x.steps);
        }
        assertEquals(6 + 6 + 5, all.size());
        // with a 1600-step profile, one turn is the class of the profile
        c.in.profileSteps = 1600;
        assertEquals(Arrays.asList(1600, 800, 400), Suggester.classOrder(1600));
    }

    @Test
    public void neighboursOwnDotsChangeTheResult() {
        // an 0603-like part, and the big pad of a characterized type 2.0 mm above it: one centre dot
        TypeInfo t = new TypeInfo();
        t.key = "big";
        t.name = "Big";
        t.status = TypeInfo.CHARACTERIZED;
        t.padWMm = 1.5;
        t.padHMm = 1.5;
        t.dotsPerPad = 1;
        List<PadInstance> pads = part("X1", "CHIPX", 0, 0, 0.80, 0.95, 50, -0.825, 0.825);
        PadInstance big = new PadInstance("Y1", "BIGPAD", "1", 0, 2.0, 0, 1.5, 1.5, 0);
        pads.add(big);
        CheckInputs preview = PadFixtures.inputs(pads);
        type(preview, t, "BIGPAD");
        assertEquals("pass", byPackage(ProfileCheck.of(preview), "CHIPX").suggestion.fit);
        // planned: two dots low on the big pad, 1.4 mm above the chip's dots
        CheckInputs planned = PadFixtures.inputs(pads);
        type(planned, t, "BIGPAD");
        planned.planned = true;
        big.dots = Arrays.asList(new double[] {-0.6, 1.4}, new double[] {0.6, 1.4});
        big.dotSteps = 800;
        Suggestion s = byPackage(ProfileCheck.of(planned), "CHIPX").suggestion;
        assertNotEquals("pass", s.fit);
        assertTrue(s.predicted.dotGapMm < 0.30, "" + s.predicted.dotGapMm);
    }

    static ProfileCheck.Group byPackage(ProfileCheck.Result r, String pkg) {
        for (ProfileCheck.Group g : r.groups) {
            if (g.packageIds.get(0).equals(pkg)) {
                return g;
            }
        }
        throw new AssertionError("no group of " + pkg);
    }

    @Test
    public void fallbackMarginalAndMarginalFallback() {
        Map<String, List<PadInstance>> mobo = PadFixtures.packageLevel(PadFixtures.load("mobo"));
        Map<String, List<PadInstance>> nano = PadFixtures.packageLevel(PadFixtures.load("nano"));
        Suggestion sod = ProfileCheck.of(PadFixtures.inputs(mobo.get("D_SOD-123"))).groups.get(0).suggestion;
        assertEquals("fallback", sod.fit);
        assertTrue(sod.confidenceWhy.contains("the closest amount that fits is -33 %"));
        Suggestion soic = ProfileCheck.of(PadFixtures.inputs(mobo.get("SOIC-8_3.9x4.9mm_P1.27mm"))).groups.get(0).suggestion;
        assertEquals("marginal", soic.fit);
        assertTrue(soic.confidenceWhy.contains("marginal: the dots leave 0.27 mm to the next pad (0.30 wanted); the measured "
                + "dot size decides"), soic.confidenceWhy.toString());
        assertTrue(soic.confidenceWhy.contains("no validated Leaded ICs pads yet: the amount is scaled from the 0603 half "
                + "turn and capped at a 0.25 mm print at this 1.27 mm pitch"), soic.confidenceWhy.toString());
        assertTrue(soic.amount.rule.startsWith("0.94 mg per mm² of opening: a 0.25 mm print, the most at this 1.27 mm "
                + "pitch"), soic.amount.rule);
        Suggestion c0603 = ProfileCheck.of(PadFixtures.inputs(nano.get("C_0603_1608Metric"))).groups.get(0).suggestion;
        assertEquals("marginal-fallback", c0603.fit);
        assertTrue(c0603.confidenceWhy.contains("the closest amount that fits is +14 %"));
    }

    @Test
    public void calibrateExistingKeepsAnEditedPlan() {
        TypeInfo t = sot23Seed();
        t.status = TypeInfo.PROVISIONAL;
        t.version = 2;
        t.dotSteps = 800;
        t.dotsPerPad = 1;
        t.origin = "manual";
        Suggestion s = ProfileCheck.of(sot23(t)).groups.get(0).suggestion;
        assertEquals(Suggestion.CALIBRATE_EXISTING, s.kind);
        assertEquals("your plan", s.planFrom);
        assertEquals(800, s.dots.steps);
        assertEquals(1, s.dots.perPad);
        assertEquals(Suggester.BREAKS_LIMITS, s.fit);
        assertTrue(s.sentence.startsWith("Calibrate SOT-23 for these pads with your plan: 1 half-turn dot per pad"));
        // §4.3's plan is the alternative the ladder measures too
        assertEquals(400, s.alternatives.get(0).steps);
        assertEquals(2, s.alternatives.get(0).perPad);
        assertEquals("the suggested plan (marginal): the ladder measures it too", s.alternatives.get(0).why);
        assertEquals(Arrays.asList(400, 800), s.calibration.doses);
    }

    @Test
    public void newKeysAreUniqueAndValid() {
        assertEquals("sot-23-148x60", Suggester.key("SOT-23", 1.475, 0.60));
        String k = Suggester.key("Resonator_SMD_Murata_CSTxExxV-3Pin_3.0x1.1mm", 1.848, 0.548);
        assertTrue(k.matches("[a-z0-9][a-z0-9-]{0,47}"), k);
        assertEquals("resonator-smd-murata-cstxexxv-3pin-185x55", k);
        CheckInputs in = padcalBoard();
        TypeInfo taken = new TypeInfo();
        taken.key = "test-strip-0-62x1-50-150x62";
        taken.name = "taken";
        taken.status = TypeInfo.NOT_STARTED;
        taken.padWMm = 5;
        taken.padHMm = 5;
        in.types.put(taken.key, taken);
        for (ProfileCheck.Group g : ProfileCheck.of(in).groups) {
            if (g.packageIds.get(0).startsWith("Test_Strip")) {
                assertEquals("test-strip-0-62x1-50-150x62-2", g.suggestion.typeKey);
            }
        }
    }

    @Test
    public void theIdFollowsTheEvidence() {
        CheckInputs in = sot23(sot23Seed());
        String a = ProfileCheck.of(in).groups.get(0).suggestion.id;
        assertEquals(a, ProfileCheck.of(sot23(sot23Seed())).groups.get(0).suggestion.id, "deterministic");
        DoseEvidence e = new DoseEvidence(400);
        e.massConfirmed = true;
        e.massReadings = 2;
        e.mgPerDot = 0.575;
        e.mgPerStep = 0.575 / 400;
        in.evidence.put(400, e);
        Suggestion s = ProfileCheck.of(in).groups.get(0).suggestion;
        assertNotEquals(a, s.id);
        assertEquals("weighed", s.dots.mgSource);
        assertEquals(1.15, s.predicted.mgPerPad, 0.0005);
        assertFalse(s.confidenceWhy.contains("no weighing at this recipe yet"));
        assertEquals(Collections.emptyList(), s.calibration.weigh);
        // a confirmed mass of another dose carries over per step
        in.evidence.clear();
        DoseEvidence half = new DoseEvidence(800);
        half.massConfirmed = true;
        half.massReadings = 2;
        half.mgPerDot = 1.15;
        half.mgPerStep = 1.15 / 800;
        in.evidence.put(800, half);
        s = ProfileCheck.of(in).groups.get(0).suggestion;
        assertEquals("weighed-per-step", s.dots.mgSource);
        assertEquals(0.575, s.dots.mgPerDot, 0.0005);
        assertTrue(s.confidenceWhy.contains("the mass per step comes from another turn size"));
    }

    static DoseEvidence confirmed(int steps, double mgPerStep) {
        DoseEvidence e = new DoseEvidence(steps);
        e.massConfirmed = true;
        e.massReadings = 2;
        e.mgPerStep = mgPerStep;
        e.mgPerDot = mgPerStep * steps;
        return e;
    }

    @Test
    public void theSameEvidenceInAnyMapOrderGivesTheSameSuggestion() {
        // 400 and 1600 weighed, 800 not: both are one octave from 800; the tie goes to the larger dose
        List<Suggestion> l = new ArrayList<>();
        for (boolean quarterFirst : new boolean[] {true, false}) {
            CheckInputs in = PadFixtures.inputs(PadFixtures.packageLevel(PadFixtures.load("ftp")).get("R_0603_1608Metric"));
            in.evidence = new java.util.HashMap<>();
            for (DoseEvidence e : quarterFirst ? Arrays.asList(confirmed(400, 1.40e-3), confirmed(1600, 1.45e-3))
                    : Arrays.asList(confirmed(1600, 1.45e-3), confirmed(400, 1.40e-3))) {
                in.evidence.put(e.steps, e);
            }
            l.add(ProfileCheck.of(in).groups.get(0).suggestion);
        }
        assertEquals("weighed-per-step", l.get(0).dots.mgSource);
        assertEquals(800 * 1.45e-3, l.get(0).dots.mgPerDot, 0.0005);
        assertEquals(l.get(0).dots.mgPerDot, l.get(1).dots.mgPerDot, 0);
        assertEquals(l.get(0).id, l.get(1).id);
    }

    @Test
    public void mediumConfidenceNeedsAnAnchorOfTheFamily() {
        // §4.5: a passing half or one turn of a family with no validated anchor stays low (mobo AVX-9155 1.30 x 1.15,
        // Custom, 1 one-turn dot, +2 %); a chip of the same plan is medium on the bench anchor
        Map<String, List<PadInstance>> mobo = PadFixtures.packageLevel(PadFixtures.load("mobo"));
        Suggestion avx = null;
        for (ProfileCheck.Group g : ProfileCheck.of(PadFixtures.inputs(mobo.get("AVX-9155-005-541"))).groups) {
            if (Math.abs(Math.max(g.aperture.wMm, g.aperture.hMm) - 1.30) < 0.006) {
                avx = g.suggestion;
            }
        }
        assertEquals("pass", avx.fit);
        assertEquals(1600, avx.dots.steps);
        assertEquals("low", avx.confidence);
        assertTrue(avx.confidenceWhy.contains("no validated Custom pads yet: the amount is scaled from the 0603 half turn"),
                avx.confidenceWhy.toString());
        Suggestion r0805 = ProfileCheck.of(PadFixtures.inputs(mobo.get("R_0805_2012Metric"))).groups.get(0).suggestion;
        assertEquals(1600, r0805.dots.steps);
        assertEquals("medium", r0805.confidence);
    }

    @Test
    public void onlyValidatedTypesWithKOneAreAnchors() {
        // a validated SOT-223 tab type (k 0.5: 5.5 mg weighed on 7.6 mm², half the chip rate) is not a mass per
        // area for the family's k 1 pads: SOT-23 pins keep the bench rate (anchored on it, 0.72 mg/mm² would leave
        // them with no plan at all)
        CheckInputs in = sot23(sot23Seed());
        TypeInfo tab = new TypeInfo();
        tab.key = "sot-223-tab";
        tab.name = "SOT-223 tab";
        tab.family = Family.SMALL_OUTLINE;
        tab.version = 3;
        tab.status = TypeInfo.VALIDATED;
        tab.padWMm = 3.8;
        tab.padHMm = 2.0;
        tab.k = 0.5;
        tab.massPerPadMg = 5.5;
        in.types.put(tab.key, tab);
        ProfileCheck.Group g = ProfileCheck.of(in).groups.get(0);
        assertEquals(ProfileCheck.BELOW_GATE, g.reason);
        assertEquals(1.26, g.suggestion.amount.targetMg, 0.005);
        assertEquals(1.455, g.suggestion.amount.mgPerMm2, 0.0005);
        // a validated k 1 type of the family is its anchor
        tab.k = 1.0;
        tab.massPerPadMg = 10.0;
        Suggestion s = ProfileCheck.of(in).groups.get(0).suggestion;
        assertEquals(10.0 / 7.6, s.amount.mgPerMm2, 0.0005);
        assertTrue(s.amount.rule.contains("SOT-223 tab validated on a board (10.00 mg on 7.60 mm²)"), s.amount.rule);
    }

    @Test
    public void confidenceLevels() {
        CheckInputs in = PadFixtures.inputs(PadFixtures.packageLevel(PadFixtures.load("ftp")).get("R_0603_1608Metric"));
        Suggestion s = ProfileCheck.of(in).groups.get(0).suggestion;
        assertEquals("medium", s.confidence);
        assertEquals(Arrays.asList("no fresh camera check of half-turn dots on the blank yet",
                "no weighing at this recipe yet"), s.confidenceWhy);
        DoseEvidence e = new DoseEvidence(800);
        e.opticalFresh = true;
        e.opticalPass = true;
        e.n = 28;
        e.dMeanMm = 1.16;
        e.d95Mm = 1.23;
        e.massConfirmed = true;
        e.massReadings = 2;
        e.mgPerDot = 1.056;
        e.mgPerStep = 1.056 / 800;
        in.evidence.put(800, e);
        s = ProfileCheck.of(in).groups.get(0).suggestion;
        assertEquals("high", s.confidence);
        assertEquals(Collections.emptyList(), s.confidenceWhy);
        assertEquals("measured", s.dots.dSource);
        assertArrayEquals(new double[] {1.16, 1.23}, s.dots.diameterMm, 0);
    }
}
