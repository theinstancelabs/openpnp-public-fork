package org.openpnp.instancelabs.padprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * §1.9 on the three KiCad fixtures (R/README.md): the package-level survey (each footprint's openings
 * with their own neighbours, every group planned from scratch with no pad type), the nano split, and
 * the board-level checks the upload preview shows. Main-thread decisions applied: 3 (the fallback is
 * the nearer allowed dose, ties to the larger: LED_0805, the nano SOT-223 pins and the 1TS003B
 * 1.15 x 1.00 pads now get one-turn dots) and 4 (a part with an undispensable pad is treated whole by
 * default; {@link CheckInputs#pastePartialPackages} opts a package in).
 */
public class FixtureSuggestionsTest {
    /** board, package, long side, short side, steps x dots ("tip" = not dispensable), error %, fit, partial. */
    private static final Object[][] ROWS = {
        {"ftp", "R_0603_1608Metric", 0.95, 0.80, 800, 1, 0, "pass", false},
        {"ftp", "LED_0603_1608Metric", 0.95, 0.875, 800, 1, -8, "pass", false},
        {"mobo", "R_0805_2012Metric", 1.40, 1.025, 1600, 1, 5, "pass", false},
        {"mobo", "C_0805_2012Metric", 1.45, 1.00, 1600, 1, 4, "pass", false},
        {"mobo", "D_SOD-123", 1.20, 0.90, 800, 1, -33, "fallback", false},
        {"mobo", "SOT-23", 1.475, 0.60, 400, 2, -16, "marginal", false},
        {"mobo", "SOIC-8_3.9x4.9mm_P1.27mm", 1.95, 0.60, 400, 2, -2, "marginal", false},
        {"mobo", "PinHeader_2x05_P1.27mm_Vertical_SMD", 2.40, 0.74, 400, 3, -5, "marginal", false},
        {"mobo", "Molex_PicoBlade_53261-0671_1x06-1MP_P1.25mm_Horizontal", 1.60, 0.80, 400, 2, -10, "marginal", false},
        {"mobo", "Molex_PicoBlade_53261-0671_1x06-1MP_P1.25mm_Horizontal", 3.00, 2.10, 800, 4, -7, "pass", false},
        {"mobo", "Molex_PicoBlade_53261-0271_1x02-1MP_P1.25mm_Horizontal", 1.60, 0.80, 400, 2, -10, "marginal", false},
        {"mobo", "Molex_PicoBlade_53261-0271_1x02-1MP_P1.25mm_Horizontal", 3.00, 2.10, 800, 4, -7, "pass", false},
        {"mobo", "SOT-223-3_TabPin2", 2.00, 1.50, 1600, 2, -3, "pass", false},
        {"mobo", "SOT-223-3_TabPin2", 3.80, 2.00, 800, 4, -24, "pass", false},
        {"mobo", "C_Elec_6.3x7.7", 3.325, 1.60, 800, 3, -17, "pass", false},
        {"mobo", "Crystal_SMD_3225-4Pin_3.2x2.5mm_HandSoldering", 2.10, 1.80, 1600, 2, -23, "pass", false},
        {"mobo", "L_6.3x6.3_H3", 2.40, 1.50, 1600, 2, -19, "pass", false},
        {"mobo", "AVX-9155-005-541", 1.30, 1.15, 1600, 1, 2, "pass", false},
        {"mobo", "AVX-9155-005-541", 2.15, 1.50, 1600, 2, -10, "pass", false},
        {"mobo", "AVX-9155-005-541", 2.125, 1.50, 1600, 2, -9, "pass", false},
        // decision 3: one turn (+28 %) is nearer than a half turn (-36 %)
        {"mobo", "1TS003B-1400-3500A-CT", 1.15, 1.00, 1600, 1, 28, "fallback", false},
        {"mobo", "1TS003B-1400-3500A-CT", 1.05, 1.00, 800, 1, -30, "fallback", false},
        {"mobo", "1TS003B-1400-3500A-CT", 1.30, 1.20, 1600, 1, -6, "pass", false},
        {"mobo", "LQFP-48_7x7mm_P0.5mm", 1.475, 0.30, "tip"},
        {"mobo", "LQFP-48_7x7mm_P0.5mm", 1.30, 0.30, "tip"},
        {"mobo", "WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm", 0.50, 0.25, "tip"},
        {"mobo", "WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm", 0.75, 0.65, "tip"},
        {"mobo", "QFN-32-1EP_5x5mm_P0.5mm_EP3.45x3.45mm", 0.875, 0.25, "tip"},
        // the 9 paste windows of the exposed pad: dot gap 0.15
        {"mobo", "QFN-32-1EP_5x5mm_P0.5mm_EP3.45x3.45mm", 0.93, 0.93, "tip"},
        {"mobo", "MHS110FRGBCT", 1.20, 0.70, "tip"},
        {"mobo", "MHS110FRGBCT", 0.80, 0.50, "tip"},
        {"mobo", "LED_LiteOn_LTST-C19HE1WT", 0.85, 0.65, "tip"},
        // decision 3: a tie at 33 % goes to the larger dose, one turn (the bench thread's "about one turn")
        {"nano", "LED_0805_2012Metric", 1.048, 1.048, 1600, 1, 33, "fallback", false},
        {"nano", "SW_SPST_3.9x2.9mm", 1.829, 0.864, 800, 2, -8, "pass", false},
        {"nano", "USB_Mini-B_Lumberg_2486_01_Horizontal", 2.388, 0.356, "tip"},
        {"nano", "USB_Mini-B_Lumberg_2486_01_Horizontal", 2.134, 1.626, 1600, 2, -16, "pass", true},
        {"nano", "VQFN-32-1EP_5x5mm_P0.5mm_EP3.254x3.254mm", 0.548, 0.128, "tip"},
        {"nano", "VQFN-32-1EP_5x5mm_P0.5mm_EP3.254x3.254mm", 1.27, 1.27, 800, 1, -10, "pass", true},
        {"nano", "Resonator_SMD_Murata_CSTxExxV-3Pin_3.0x1.1mm", 1.848, 0.548, "tip"},
        {"nano", "C_0603_1608Metric", 0.948, 0.746, 800, 1, 14, "marginal-fallback", false},
        {"nano", "Fuse_0603_1608Metric", 0.848, 0.798, 800, 1, 8, "pass", false},
        {"nano", "CP_EIA-3216-18_Kemet-A", 1.626, 0.965, 1600, 1, -7, "pass", false},
        // decision 3: two one-turn dots (+31 %) are nearer than two half turns (-34 %)
        {"nano", "SOT-223", 2.083, 1.067, 1600, 2, 31, "fallback", false},
        {"nano", "SOT-223", 3.448, 2.048, 800, 4, -17, "pass", false},
        {"nano", "DO-220AA", 1.118, 0.610, "tip"},
        {"nano", "DO-220AA", 2.515, 2.388, 800, 4, -2, "pass", true},
        {"nano", "R_CAY16-4", 0.498, 0.348, "tip"},
        {"nano", "SSOP-28_5.3x10.2mm_P0.65mm", 1.880, 0.229, "tip"},
    };

    /** The bench thread's 84 too-fine nano pads (calibration brief), by ref and pad. */
    private static final String[] BENCH_84 = {
        "IC1: 1-28 (SSOP-28, 0.65 mm)", "IC3: 1-32 (VQFN-32, 0.5 mm)", "J3: 1-5 (USB data, 0.8 mm)",
        "RP1: 1-8 (R_CAY16-4, 0.8 mm)", "RP2: 1-8 (R_CAY16-4, 0.8 mm)", "Y1: 1-3 (resonator, 1.0 mm)",
    };

    /** The package-level check of one footprint (its first part alone), all partial packages opted in. */
    private static ProfileCheck.Result packageCheck(List<PadInstance> pads, boolean optIn) {
        CheckInputs in = PadFixtures.inputs(pads);
        if (optIn) {
            in.pastePartialPackages.add(pads.get(0).packageId);
        }
        return ProfileCheck.of(in);
    }

    private static ProfileCheck.Group find(ProfileCheck.Result r, double l, double s) {
        for (ProfileCheck.Group g : r.groups) {
            double gl = Math.max(g.aperture.wMm, g.aperture.hMm);
            double gs = Math.min(g.aperture.wMm, g.aperture.hMm);
            if (Math.abs(gl - l) <= 0.006 && Math.abs(gs - s) <= 0.006) {
                return g;
            }
        }
        return null;
    }

    @Test
    public void everyRowOfTheSurvey() {
        int checked = 0;
        for (String board : new String[] {"ftp", "mobo", "nano"}) {
            PadFixtures.Fixture f = PadFixtures.load(board);
            Map<String, List<PadInstance>> pkgs = PadFixtures.packageLevel(f);
            Set<String> seen = new HashSet<>();
            for (Object[] row : ROWS) {
                if (!board.equals(row[0])) {
                    continue;
                }
                String pkg = (String) row[1];
                seen.add(pkg);
                ProfileCheck.Group g = find(packageCheck(pkgs.get(pkg), true), (Double) row[2], (Double) row[3]);
                String what = board + " " + pkg + " " + row[2] + " x " + row[3];
                assertNotNull(g, what);
                if ("tip".equals(row[4])) {
                    assertEquals(ProfileCheck.TIP, g.reason, what);
                    assertEquals(Suggestion.NONE, g.suggestion.kind, what);
                }
                else {
                    assertEquals(ProfileCheck.NO_TYPE, g.reason, what);
                    Suggestion s = g.suggestion;
                    assertEquals(Suggestion.NEW, s.kind, what);
                    assertEquals(row[4], s.dots.steps, what);
                    assertEquals(row[5], s.dots.perPad, what);
                    double err = s.predicted.mgPerPad / s.amount.targetMg * 100 - 100;
                    assertEquals((Integer) row[6], err, 1.0, what);
                    assertEquals(row[7], s.fit, what);
                    assertEquals(row[8], g.partial, what);
                    if (!"pass".equals(row[7])) {
                        assertEquals("low", s.confidence, what);
                    }
                }
                checked++;
            }
            assertEquals(seen, pkgs.keySet(), board + ": every package of the fixture is a row");
        }
        assertEquals(ROWS.length, checked);
    }

    @Test
    public void amountsTheSurveyNames() {
        Map<String, List<PadInstance>> mobo = PadFixtures.packageLevel(PadFixtures.load("mobo"));
        // the leaded family has no anchor of its own: capped at a 0.25 mm print at 1.27 mm pitch
        ProfileCheck.Group soic = find(packageCheck(mobo.get("SOIC-8_3.9x4.9mm_P1.27mm"), false), 1.95, 0.60);
        assertEquals(1.08, soic.suggestion.amount.targetMg, 0.005);
        assertTrue(soic.suggestion.amount.capped);
        assertEquals(1.151, soic.aperture.areaMm2, 0.0005);
        // k 0.5 above 4 mm²
        ProfileCheck.Group tab = find(packageCheck(mobo.get("SOT-223-3_TabPin2"), false), 3.80, 2.00);
        assertEquals(0.5, tab.suggestion.amount.k, 0);
        assertEquals(5.53, tab.suggestion.amount.targetMg, 0.005);
        // SOT-223: two groups, two types, mapped by pad names (§5.2)
        ProfileCheck.Result sot = packageCheck(mobo.get("SOT-223-3_TabPin2"), false);
        assertEquals(2, sot.groups.size());
        Set<String> keys = new HashSet<>();
        for (ProfileCheck.Group g : sot.groups) {
            assertFalse(g.allPadsOfPackage);
            assertNotNull(g.suggestion.mapping.padNames);
            keys.add(g.suggestion.typeKey);
        }
        assertEquals(new HashSet<>(Arrays.asList("sot-223-3-tabpin2-200x150", "sot-223-3-tabpin2-380x200")), keys);
        assertEquals(Arrays.asList("1", "2", "3"), find(sot, 2.00, 1.50).suggestion.mapping.padNames);
        assertEquals("SOT-223-3_TabPin2 · 2.00 × 1.50 mm pads 1–3", find(sot, 2.00, 1.50).suggestion.name);
        // the tab is the second pad named "2" (BI §2.8: repeated names get ~2)
        assertEquals("SOT-223-3_TabPin2 · 3.80 × 2.00 mm pad 2~2", find(sot, 3.80, 2.00).suggestion.name);
        assertEquals(Arrays.asList("2~2"), find(sot, 3.80, 2.00).suggestion.mapping.padNames);
        // the nano VQFN exposed pad is a centre pad (k 0.5): 1.17 mg
        Map<String, List<PadInstance>> nano = PadFixtures.packageLevel(PadFixtures.load("nano"));
        ProfileCheck.Group ep = find(packageCheck(nano.get("VQFN-32-1EP_5x5mm_P0.5mm_EP3.254x3.254mm"), true), 1.27, 1.27);
        assertEquals(0.5, ep.suggestion.amount.k, 0);
        assertEquals(1.17, ep.suggestion.amount.targetMg, 0.005);
        assertTrue(ep.suggestion.confidenceWhy.contains("the amount for a centre pad is a placeholder (k 0.5)"));
    }

    @Test
    public void decisionThreeGivesTheNanoLed0805OneTurn() {
        Map<String, List<PadInstance>> nano = PadFixtures.packageLevel(PadFixtures.load("nano"));
        ProfileCheck.Group led = find(packageCheck(nano.get("LED_0805_2012Metric"), false), 1.048, 1.048);
        Suggestion s = led.suggestion;
        assertEquals(1600, s.dots.steps);
        assertEquals(1, s.dots.perPad);
        assertEquals(33, s.predicted.errorPct);
        assertEquals("low", s.confidence);
        assertTrue(s.confidenceWhy.contains("the closest amount that fits is +33 %"), s.confidenceWhy.toString());
        // the half turn is the alternative, 33 % short
        assertEquals(800, s.alternatives.get(0).steps);
        assertEquals("a half-turn dot (1.16 mm) gives 1.06 mg per pad, 33 % less than the 1.58 mg target",
                s.alternatives.get(0).why);
    }

    @Test
    public void theNanoSplitPackageLevel() {
        PadFixtures.Fixture f = PadFixtures.load("nano");
        Map<String, Integer> refs = PadFixtures.refsPerPackage(f);
        for (boolean optIn : new boolean[] {false, true}) {
            int tip = 0;
            int part = 0;
            int plan = 0;
            int partial = 0;
            for (Map.Entry<String, List<PadInstance>> e : PadFixtures.packageLevel(f).entrySet()) {
                for (ProfileCheck.Group g : packageCheck(e.getValue(), optIn).groups) {
                    int pads = g.pads * refs.get(e.getKey());
                    if (ProfileCheck.TIP.equals(g.reason)) {
                        tip += pads;
                    }
                    else if (ProfileCheck.PART.equals(g.reason)) {
                        part += pads;
                        assertEquals(Suggestion.NONE, g.suggestion.kind);
                    }
                    else {
                        plan += pads;
                        partial += g.partial ? pads : 0;
                    }
                }
            }
            assertEquals(125, tip + part + plan);
            assertEquals(85, tip, "the bench's 84 too-fine pads plus the DO-220AA pin");
            if (optIn) {
                assertEquals(0, part);
                assertEquals(40, plan);
                assertEquals(6, partial, "USB tabs 4, VQFN EP 1, DO-220AA tab 1");
            }
            else {
                assertEquals(6, part, "decision 4: those 6 pads belong to parts treated whole");
                assertEquals(34, plan);
                assertEquals(0, partial);
            }
        }
    }

    @Test
    public void theNanoTopSideAsTheUploadPreviewShowsIt() {
        PadFixtures.Fixture f = PadFixtures.load("nano");
        ProfileCheck.Result r = ProfileCheck.of(PadFixtures.inputs(PadFixtures.side(f, "top")));
        int[] n = count(r);
        assertEquals(55, n[0] + n[1] + n[2]);
        assertEquals(40, n[0]);
        assertEquals(5, n[1]);
        assertEquals(10, n[2]);
        // the parts treated whole block like the tip's pads; the rest has no pad type yet
        assertEquals(5, r.tipBlocking);
        assertEquals(2, r.fixable);
        assertFalse(r.ok);
        CheckInputs in = PadFixtures.inputs(PadFixtures.side(f, "top"));
        in.pastePartialPackages.add("USB_Mini-B_Lumberg_2486_01_Horizontal");
        in.pastePartialPackages.add("VQFN-32-1EP_5x5mm_P0.5mm_EP3.254x3.254mm");
        r = ProfileCheck.of(in);
        n = count(r);
        assertEquals(40, n[0]);
        assertEquals(0, n[1]);
        assertEquals(15, n[2]);
        int partial = 0;
        for (ProfileCheck.Group g : r.groups) {
            partial += g.partial ? g.pads : 0;
        }
        assertEquals(5, partial);
    }

    @Test
    public void theBenchsTooFinePadsByRefAndPad() {
        PadFixtures.Fixture f = PadFixtures.load("nano");
        Set<String> pitch = new TreeSet<>(GroupKey.NATURAL);
        List<String> noPlan = new ArrayList<>();
        for (String side : new String[] {"top", "bottom"}) {
            for (ProfileCheck.Group g : ProfileCheck.of(PadFixtures.inputs(PadFixtures.side(f, side))).groups) {
                if (ProfileCheck.TIP_PITCH.equals(g.tipKind)) {
                    pitch.addAll(g.padIds);
                }
                else if (ProfileCheck.TIP_NO_PLAN.equals(g.tipKind)) {
                    noPlan.add(g.label + ": " + g.padIds);
                }
            }
        }
        Set<String> bench = new TreeSet<>(GroupKey.NATURAL);
        for (String line : BENCH_84) {
            String ref = line.substring(0, line.indexOf(':'));
            String[] range = line.substring(line.indexOf(':') + 2, line.indexOf(' ', line.indexOf(':') + 2)).split("-");
            for (int i = Integer.parseInt(range[0]); i <= Integer.parseInt(range[1]); i++) {
                bench.add(ref + "." + i);
            }
        }
        assertEquals(84, bench.size());
        assertEquals(bench, pitch, "the dot-gap test finds exactly the bench's too-fine pads");
        // and the pad the bench counted as dispensable: a half-turn dot reaches 0.28 mm past the 0.61 mm pin
        assertTrue(noPlan.contains("DO-220AA · 1.12 × 0.61 mm: [D1.2]"), noPlan.toString());
    }

    @Test
    public void theNanoBottomSideBoardWide() {
        // Board-wide (not in the package-level survey): C3 and C4 face each other 1.27 mm apart, so their
        // half-turn dots would leave 0.11 mm. Decision 14: the group keeps its plan for the other C_0603 pads;
        // C3.1 and C4.1 are a sub-group of their own, and decision 4 holds C3 and C4 whole.
        PadFixtures.Fixture f = PadFixtures.load("nano");
        ProfileCheck.Result r = ProfileCheck.of(PadFixtures.inputs(PadFixtures.side(f, "bottom")));
        Map<String, ProfileCheck.Group> c = new java.util.TreeMap<>();
        for (ProfileCheck.Group g : r.groups) {
            if (g.packageIds.get(0).equals("C_0603_1608Metric")) {
                c.put(g.reason + (g.tipKind == null ? "" : ":" + g.tipKind), g);
            }
        }
        assertEquals(new TreeSet<>(Arrays.asList("no-type", "part", "tip:near")), c.keySet());
        assertEquals(10, c.get("no-type").pads);
        ProfileCheck.Group near = c.get("tip:near");
        assertEquals(Arrays.asList("C3.1", "C4.1"), near.padIds);
        assertEquals(Integer.valueOf(14), near.nearOf);
        assertEquals("2 of 14 pads are too close to a neighbour: their dots would leave 0.11 mm to the dot of C4 pad 1 "
                + "(at least 0.20 mm), so they get no paste. The other 12 keep the plan.", near.reasonText);
        assertEquals(Suggestion.NONE, near.suggestion.kind);
        assertTrue(near.blocking);
        ProfileCheck.Group part = c.get("part");
        assertEquals(Arrays.asList("C3.2", "C4.2"), part.padIds);
        assertEquals("Another pad of this part is too close to a neighbour and gets no paste, so none of this part's pads "
                + "are pasted: a part whose pins are soldered by hand gets no paste on its other pads either. To paste "
                + "these pads anyway, choose “Paste the dispensable pads of this part” for C_0603_1608Metric.",
                part.reasonText);
        assertFalse(c.get("no-type").partial, "a pad's own neighbour holds back its part, not the package");
        int[] n = count(r);
        assertEquals(70, n[0] + n[1] + n[2]);
        assertEquals(44 + 1 + 2, n[0]);
        assertEquals(1 + 2, n[1]);
        // the package pasted partly: C3.2 and C4.2 keep the plan
        CheckInputs in = PadFixtures.inputs(PadFixtures.side(f, "bottom"));
        in.pastePartialPackages.add("C_0603_1608Metric");
        int noType = 0;
        for (ProfileCheck.Group g : ProfileCheck.of(in).groups) {
            if (g.packageIds.get(0).equals("C_0603_1608Metric") && ProfileCheck.NO_TYPE.equals(g.reason)) {
                noType += g.pads;
            }
        }
        assertEquals(12, noType);
    }

    @Test
    public void coveredByChip0603() {
        // C_0603 (0.638 mm²) and Fuse_0603 (0.672 mm²) against chip-0603 calibrated on the R opening (0.7257 mm²)
        Map<String, List<PadInstance>> nano = PadFixtures.packageLevel(PadFixtures.load("nano"));
        for (String pkg : new String[] {"C_0603_1608Metric", "Fuse_0603_1608Metric"}) {
            CheckInputs in = PadFixtures.inputs(nano.get(pkg));
            TypeInfo t = chip0603();
            in.types.put(t.key, t);
            for (PadInstance p : in.pads) {
                p.typeKey = t.key;
                p.typeVersion = 2;
                p.typeStatus = t.status;
                p.typeRule = "pattern";
            }
            ProfileCheck.Result r = ProfileCheck.of(in);
            assertTrue(r.groups.isEmpty(), pkg + " is covered: " + (r.groups.isEmpty() ? "" : r.groups.get(0).reasonText));
            assertTrue(r.ok);
        }
        assertEquals(0.88, PadFixtures.instance(PadFixtures.load("nano").pads.stream()
                .filter(p -> p.packageId.equals("C_0603_1608Metric")).findFirst().get()).areaMm2() / 0.7257, 0.005);
    }

    @Test
    public void ftpAndMoboTotals() {
        ProfileCheck.Result ftp = ProfileCheck.of(PadFixtures.inputs(PadFixtures.side(PadFixtures.load("ftp"), "top")));
        int halfTurns = 0;
        for (ProfileCheck.Group g : ftp.groups) {
            assertEquals(800, g.suggestion.dots.steps);
            assertEquals(1, g.suggestion.dots.perPad);
            halfTurns += g.pads;
        }
        assertEquals(160, halfTurns);
        // mobo, package level: 107 pads with a plan (10 fallback), 47 marginal, 117 not dispensable
        PadFixtures.Fixture f = PadFixtures.load("mobo");
        Map<String, Integer> refs = PadFixtures.refsPerPackage(f);
        int disp = 0;
        int marginal = 0;
        int not = 0;
        int fallback = 0;
        for (Map.Entry<String, List<PadInstance>> e : PadFixtures.packageLevel(f).entrySet()) {
            for (ProfileCheck.Group g : packageCheck(e.getValue(), true).groups) {
                int pads = g.pads * refs.get(e.getKey());
                if (ProfileCheck.TIP.equals(g.reason)) {
                    not += pads;
                }
                else if (g.suggestion.fit.startsWith("marginal")) {
                    marginal += pads;
                }
                else {
                    disp += pads;
                    fallback += "fallback".equals(g.suggestion.fit) ? pads : 0;
                }
            }
        }
        assertEquals(107, disp);
        assertEquals(10, fallback);
        assertEquals(47, marginal);
        assertEquals(117, not);
    }

    @Test
    public void the0402SeedPitchIsNotDispensable() {
        // seed chip-0402: 0.54 x 0.64 pads 1.02 mm apart: centre dots of 1.00 mm leave 0.02 mm
        List<PadInstance> pads = new ArrayList<>();
        pads.add(new PadInstance("R1", "R_0402_1005Metric", "1", -0.51, 0, 0, 0.54, 0.64, 0));
        pads.add(new PadInstance("R1", "R_0402_1005Metric", "2", 0.51, 0, 0, 0.54, 0.64, 0));
        ProfileCheck.Group g = ProfileCheck.of(PadFixtures.inputs(pads)).groups.get(0);
        assertEquals(ProfileCheck.TIP, g.reason);
        assertEquals(ProfileCheck.TIP_PITCH, g.tipKind);
        assertTrue(g.reasonText.contains("would leave only 0.02 mm to the next pad's dot"), g.reasonText);
    }

    /** chip-0603 characterized, calibrated on the R_0603 opening. */
    static TypeInfo chip0603() {
        TypeInfo t = new TypeInfo();
        t.key = "chip-0603";
        t.name = "Chip 0603";
        t.family = Family.CHIP;
        t.version = 2;
        t.status = TypeInfo.CHARACTERIZED;
        t.padWMm = 0.80;
        t.padHMm = 0.95;
        t.padRoundness = 50;
        t.calAreaMm2 = 0.7257;
        return t;
    }

    /** Pads {tip, part, everything else} of the listed groups. */
    private static int[] count(ProfileCheck.Result r) {
        int[] n = new int[3];
        for (ProfileCheck.Group g : r.groups) {
            if (ProfileCheck.TIP.equals(g.reason)) {
                n[0] += g.pads;
            }
            else if (ProfileCheck.PART.equals(g.reason)) {
                n[1] += g.pads;
            }
            else if (g.reason != null) {
                n[2] += g.pads;
            }
            else {
                fail("a listed group has a reason");
            }
        }
        assertNull(r.group("none"));
        return n;
    }
}
