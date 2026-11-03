package org.openpnp.instancelabs.padprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/** The profile check of §3 with the main-thread decisions 4, 5 and 11: groups, reasons, blocking, counts, texts. */
public class ProfileCheckTest {
    private static final String NEEDS_OTHER = " A finer tip, a paste line or a stencil would be needed; this version "
            + "does not do those.";

    static List<PadInstance> part(String ref, String pkg, double x, double y, double w, double h, double r, double... xs) {
        return SuggesterTest.part(ref, pkg, x, y, w, h, r, xs);
    }

    static TypeInfo type(String key, String name, String status, double w, double h) {
        TypeInfo t = new TypeInfo();
        t.key = key;
        t.name = name;
        t.version = 2;
        t.status = status;
        t.padWMm = w;
        t.padHMm = h;
        return t;
    }

    static ProfileCheck.Group only(ProfileCheck.Result r, String pkg) {
        return SuggesterTest.byPackage(r, pkg);
    }

    @Test
    public void groupingTolerances() {
        List<PadInstance> pads = new ArrayList<>();
        pads.add(new PadInstance("A1", "PKG", "1", 0, 0, 0, 0.80, 0.95, 50));
        pads.add(new PadInstance("A1", "PKG", "2", 3, 0, 0, 0.81, 0.96, 55));
        pads.add(new PadInstance("A1", "PKG", "3", 6, 0, 0, 0.83, 0.95, 50));
        pads.add(new PadInstance("A1", "PKG", "4", 9, 0, 90, 0.95, 0.80, 56));
        ProfileCheck.Result r = ProfileCheck.of(PadFixtures.inputs(pads));
        assertEquals(3, r.groups.size());
        assertEquals(Arrays.asList("1", "2"), r.groups.get(0).padNames);
        assertEquals(Collections.singletonList("3"), r.groups.get(1).padNames);
        assertEquals(Collections.singletonList("4"), r.groups.get(2).padNames);
        assertFalse(r.groups.get(0).allPadsOfPackage);
        // the id hashes the first opening's key: package, long side, short side (0.001 mm), roundness (0.1)
        assertEquals("PKG|0.950|0.800|50.0", GroupKey.of(pads.get(0)).text());
        assertEquals("g" + GroupKey.sha12("PKG|0.950|0.800|50.0"), r.groups.get(0).group);
        assertEquals("PKG · 0.95 × 0.80 mm", r.groups.get(0).label);
    }

    @Test
    public void groupIdsAreTheSameForThePreviewTheBoardAndThePlan() {
        List<PadInstance> preview = PadFixtures.side(PadFixtures.load("ftp"), "top");
        List<PadInstance> plan = PadFixtures.side(PadFixtures.load("ftp"), "top");
        Collections.reverse(plan);
        for (PadInstance p : plan) {
            p.typeKey = "chip-0603";
            p.typeStatus = TypeInfo.NOT_STARTED;
            p.flags.add(ProfileCheck.FLAG_STATUS_BELOW_MIN);
            p.blocked = true;
        }
        CheckInputs planned = PadFixtures.inputs(plan);
        planned.planned = true;
        planned.types.put("chip-0603", type("chip-0603", "Chip 0603", TypeInfo.NOT_STARTED, 0.80, 0.95));
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        for (ProfileCheck.Group g : ProfileCheck.of(PadFixtures.inputs(preview)).groups) {
            a.add(g.group);
        }
        for (ProfileCheck.Group g : ProfileCheck.of(planned).groups) {
            b.add(g.group);
            assertEquals(ProfileCheck.BELOW_GATE, g.reason);
        }
        assertEquals(2, a.size());
        assertEquals(a, b);
    }

    @Test
    public void reasonsInOrder() {
        // tip beats no type
        List<PadInstance> fine = part("U1", "FINE", 0, 0, 0.25, 0.8, 0, -0.25, 0.25);
        assertEquals(ProfileCheck.TIP, ProfileCheck.of(PadFixtures.inputs(fine)).groups.get(0).reason);
        // no pad size beats no type
        PadInstance none = new PadInstance("U2", "NOSIZE", "1", 0, 0, 0, 0, 0, 0);
        none.flags.add(ProfileCheck.FLAG_NO_GEOMETRY);
        ProfileCheck.Group g = ProfileCheck.of(PadFixtures.inputs(new ArrayList<>(Collections.singletonList(none))))
                .groups.get(0);
        assertEquals(ProfileCheck.NO_PAD_SIZE, g.reason);
        assertEquals("NOSIZE has no pads in OpenPnP and its pad type (none) has no pad layout: add the footprint's pads "
                + "in OpenPnP, or upload the KiCad board.", g.reasonText);
        // below the gate beats outside (a provisional type far from its calibrated size)
        CheckInputs in = PadFixtures.inputs(part("R1", "RES", 0, 0, 0.8, 0.95, 50, -0.825, 0.825));
        SuggesterTest.type(in, type("tiny", "Tiny", TypeInfo.PROVISIONAL, 0.3, 0.3), "RES");
        assertEquals(ProfileCheck.BELOW_GATE, ProfileCheck.of(in).groups.get(0).reason);
        // the same type characterized: outside (area)
        in.types.get("tiny").status = TypeInfo.CHARACTERIZED;
        for (PadInstance p : in.pads) {
            p.typeStatus = TypeInfo.CHARACTERIZED;
        }
        g = ProfileCheck.of(in).groups.get(0);
        assertEquals(ProfileCheck.OUTSIDE, g.reason);
        assertEquals(ProfileCheck.OUTSIDE_AREA, g.outsideKind);
        assertFalse(g.blocking);
    }

    @Test
    public void tipTexts() {
        // gap > 0: the seed 0402 pitch (FixtureSuggestionsTest) ...
        List<PadInstance> p0402 = part("R1", "R_0402", 0, 0, 0.54, 0.64, 0, -0.51, 0.51);
        ProfileCheck.Group g = ProfileCheck.of(PadFixtures.inputs(p0402)).groups.get(0);
        assertEquals(ProfileCheck.TIP_PITCH, g.tipKind);
        assertEquals("Not dispensable with the 22G tip: these pads are 1.02 mm apart, and the smallest dot this tip makes "
                + "reliably (1.00 mm, a quarter turn) would leave only 0.02 mm to the next pad's dot (at least 0.20 mm "
                + "needed)." + NEEDS_OTHER, g.reasonText);
        assertEquals("cannot be dispensed with the 22G tip", g.shortText);
        // ... gap <= 0
        g = ProfileCheck.of(PadFixtures.inputs(part("U1", "FINE", 0, 0, 0.25, 0.8, 0, -0.25, 0.25))).groups.get(0);
        assertEquals("Not dispensable with the 22G tip: a 1.00 mm dot, the smallest this tip makes reliably, is wider than "
                + "the 0.50 mm pad pitch, so neighbouring dots would overlap by 0.50 mm." + NEEDS_OTHER, g.reasonText);
        // copper: a small pad's dot reaches 0.10 mm onto a big pad of another part
        List<PadInstance> pads = part("S1", "SMALL", 0, 0, 0.3, 0.3, 0, 0);
        pads.add(new PadInstance("B1", "BIG", "1", 1.9, 0, 0, 3.0, 3.0, 0));
        g = only(ProfileCheck.of(PadFixtures.inputs(pads)), "SMALL");
        assertEquals(ProfileCheck.TIP_COPPER, g.tipKind);
        assertEquals("Not dispensable with the 22G tip: the smallest dot this tip makes reliably (1.00 mm) would reach "
                + "0.10 mm onto the next pad." + NEEDS_OTHER, g.reasonText);
        // no plan: the DO-220AA pin; its tab, opted in, names the pin by its width
        Map<String, List<PadInstance>> nano = PadFixtures.packageLevel(PadFixtures.load("nano"));
        CheckInputs in = PadFixtures.inputs(nano.get("DO-220AA"));
        in.pastePartialPackages.add("DO-220AA");
        ProfileCheck.Result r = ProfileCheck.of(in);
        assertTrue(r.groups.get(0).reasonText.endsWith(" The other pads of DO-220AA (0.61 mm wide) cannot be dispensed "
                + "with this tip: paste these only if you solder those pins by hand."), r.groups.get(0).reasonText);
        ProfileCheck.Group pin = r.groups.get(1);
        assertEquals(ProfileCheck.TIP_NO_PLAN, pin.tipKind);
        assertEquals("Not dispensable with the 22G tip: on these 0.61 × 1.12 mm pads the closest plan (1 half-turn dot "
                + "per pad) reaches 0.28 mm past the pad edge (at most 0.25 mm).", pin.reasonText);
        assertEquals("Not dispensable with the 22G tip: on these 0.95 × 0.75 mm pads the closest plan (1 half-turn dot per "
                + "pad) comes within 0.11 mm of the next pad's dot (at least 0.20 mm).",
                Texts.tipNoPlan("22G tip", 0.948, 0.746, 1, 800, null, 0.112, null, 1.06, 0.93));
        assertEquals("Not dispensable with the 22G tip: on these 1.00 × 1.00 mm pads the closest plan (2 quarter-turn "
                + "dots per pad) reaches 0.30 mm past the pad edge (at most 0.25 mm) and comes within 0.05 mm of the next "
                + "pad (at least 0.10 mm).", Texts.tipNoPlan("22G tip", 1, 1, 2, 400, 0.30, null, 0.05, 1, 1));
    }

    @Test
    public void partialPackagesAreTreatedWholeUnlessOptedIn() {
        Map<String, List<PadInstance>> nano = PadFixtures.packageLevel(PadFixtures.load("nano"));
        String usb = "USB_Mini-B_Lumberg_2486_01_Horizontal";
        CheckInputs in = PadFixtures.inputs(nano.get(usb));
        ProfileCheck.Result r = ProfileCheck.of(in);
        ProfileCheck.Group tabs = r.groups.get(1);
        assertEquals(ProfileCheck.PART, tabs.reason);
        assertTrue(tabs.partial);
        assertTrue(tabs.blocking);
        assertFalse(tabs.fixable);
        assertEquals(Suggestion.NONE, tabs.suggestion.kind);
        assertEquals("The other pads of " + usb + " (0.80 mm apart) cannot be dispensed with the 22G tip, so none of this "
                + "part's pads are pasted: a part whose pins are soldered by hand gets no paste on its other pads either. "
                + "To paste these pads anyway, choose “Paste the dispensable pads of this part” for " + usb + ".",
                tabs.reasonText);
        assertEquals(2, r.tipBlocking);
        assertEquals(0, r.fixable);
        // opted in: the tabs keep their own reason and suggestion, with the partial sentence
        in = PadFixtures.inputs(nano.get(usb));
        in.pastePartialPackages.add(usb);
        tabs = ProfileCheck.of(in).groups.get(1);
        assertEquals(ProfileCheck.NO_TYPE, tabs.reason);
        assertTrue(tabs.partial);
        assertEquals(Suggestion.NEW, tabs.suggestion.kind);
        assertEquals("No pad type for " + usb + " (1.63 × 2.13 mm openings, 4 pads on 1 part): these pads get no paste "
                + "until a pad type is chosen. The other pads of " + usb + " (0.80 mm apart) cannot be dispensed with this "
                + "tip: paste these only if you solder those pins by hand.", tabs.reasonText);
    }

    /** Two SOT-223 parts (pins 1-3 and tab 4, nano openings) at (x, y). */
    static List<PadInstance> sot223(String ref, double x, double y) {
        List<PadInstance> l = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            l.add(new PadInstance(ref, "SOT-223", String.valueOf(i + 1), x - 2.3 + 2.3 * i, y - 3.15, 0, 1.067, 2.083, 0));
        }
        l.add(new PadInstance(ref, "SOT-223", "4", x, y + 3.15, 0, 3.448, 2.048, 0));
        return l;
    }

    /**
     * Decisions 14 and 4: a 0603 pad 1.10 mm from U1 pin 1 fails the tip test there only; U1 pin 1 is
     * its own sub-group and holds U1 whole, while U2 (same package, nothing near) keeps its plan.
     */
    @Test
    public void aNeighbourTooCloseHoldsBackItsPartNotThePackage() {
        List<PadInstance> pads = sot223("U1", 100, 80);
        pads.addAll(sot223("U2", 130, 80));
        pads.addAll(part("R9", "R_0603_1608Metric", 95.775, 76.85, 0.8, 0.95, 50, 0.825, -0.825));
        ProfileCheck.Result r = ProfileCheck.of(PadFixtures.inputs(pads));
        Set<String> near = new TreeSet<>();
        Set<String> part = new TreeSet<>();
        for (ProfileCheck.Group g : r.groups) {
            if (ProfileCheck.TIP.equals(g.reason)) {
                assertEquals(ProfileCheck.TIP_NEAR, g.tipKind, g.label);
                assertTrue(g.blocking);
                near.addAll(g.padIds);
            }
            else if (ProfileCheck.PART.equals(g.reason)) {
                part.addAll(g.padIds);
            }
            else {
                assertEquals(ProfileCheck.NO_TYPE, g.reason, g.label);
                assertFalse(g.partial, g.label);
            }
        }
        assertEquals(new TreeSet<>(Arrays.asList("U1.1", "R9.1")), near);
        assertEquals(new TreeSet<>(Arrays.asList("U1.2", "U1.3", "U1.4", "R9.2")), part);
        ProfileCheck.Group pin = r.groups.stream().filter(g -> g.padIds.equals(Collections.singletonList("U1.1")))
                .findFirst().get();
        assertEquals(Integer.valueOf(6), pin.nearOf);
        assertEquals("R9 pad 1", pin.bridgeTo);
        assertTrue(pin.reasonText.startsWith("1 of 6 pads is too close to a neighbour: "), pin.reasonText);
        // the package pasted partly: U1's other pads keep the plan too
        CheckInputs in = PadFixtures.inputs(pads);
        in.pastePartialPackages.add("SOT-223");
        for (ProfileCheck.Group g : ProfileCheck.of(in).groups) {
            if (g.packageIds.get(0).equals("SOT-223")) {
                assertEquals(g.padIds.contains("U1.1") ? ProfileCheck.TIP : ProfileCheck.NO_TYPE, g.reason, g.label);
            }
        }
    }

    @Test
    public void noTypeAndBelowGateTexts() {
        ProfileCheck.Group g = ProfileCheck.of(PadFixtures.inputs(part("D1", "SOLO", 0, 0, 1.2, 1.2, 0, 0))).groups.get(0);
        assertEquals("No pad type for SOLO (1.20 × 1.20 mm openings, 1 pad on 1 part): these pads get no paste until a pad "
                + "type is chosen.", g.reasonText);
        assertEquals("no pad type", g.shortText);
        CheckInputs in = PadFixtures.inputs(part("R1", "RES", 0, 0, 0.8, 0.95, 50, -0.825, 0.825));
        SuggesterTest.type(in, type("chip-0603", "Chip 0603", TypeInfo.CHARACTERIZED, 0.8, 0.95), "RES");
        in.gate = TypeInfo.VALIDATED;
        g = ProfileCheck.of(in).groups.get(0);
        assertEquals("Chip 0603 is not calibrated yet (status: characterized). Boards need pad types that are at least "
                + "validated on a board: calibrate it automatically or by hand.", g.reasonText);
        assertTrue(g.fixable);
    }

    @Test
    public void outsideArea() {
        // nano LED_0805 (1.088 mm²) against chip-0805 calibrated on the mobo R_0805 opening (1.381 mm²): 0.79
        Map<String, List<PadInstance>> nano = PadFixtures.packageLevel(PadFixtures.load("nano"));
        CheckInputs in = PadFixtures.inputs(nano.get("LED_0805_2012Metric"));
        TypeInfo t = type("chip-0805", "Chip 0805", TypeInfo.CHARACTERIZED, 1.025, 1.40);
        t.calAreaMm2 = 1.381;
        t.dotSteps = 1600;
        t.dotsPerPad = 1;
        SuggesterTest.type(in, t, "LED_0805_2012Metric");
        ProfileCheck.Result r = ProfileCheck.of(in);
        ProfileCheck.Group g = r.groups.get(0);
        assertEquals(ProfileCheck.OUTSIDE, g.reason);
        assertEquals(0.79, g.area / t.calAreaMm2, 0.005);
        assertEquals("Chip 0805 was calibrated for 1.03 × 1.40 mm pads (1.38 mm²); these are 1.05 × 1.05 mm (1.09 mm², 21 % "
                + "smaller), so each gets 2.11 mg instead of about 1.58 mg.", g.reasonText);
        assertEquals("21 % smaller than calibrated", g.shortText);
        assertEquals(1, r.notes);
        assertTrue(r.ok, "notes do not block");
        assertFalse(g.blocking);
        // the other way: 1.25 is still covered, 1.26 is not
        assertEquals(Suggestion.NEW, g.suggestion.kind);
    }

    @Test
    public void outsideGeometryOnlyBeyondTheMarginalBand() {
        // 0.70 x 1.60 pads: a type with one one-turn dot reaches 0.32 mm past the 0.70 mm side
        CheckInputs in = PadFixtures.inputs(part("W1", "WIDE", 0, 0, 0.70, 1.60, 0, -1.5, 1.5));
        TypeInfo t = type("wide", "Wide", TypeInfo.CHARACTERIZED, 0.70, 1.60);
        t.dotSteps = 1600;
        t.dotsPerPad = 1;
        SuggesterTest.type(in, t, "WIDE");
        ProfileCheck.Group g = ProfileCheck.of(in).groups.get(0);
        assertEquals(ProfileCheck.OUTSIDE, g.reason);
        assertEquals(ProfileCheck.OUTSIDE_GEOMETRY, g.outsideKind);
        assertEquals("Wide's dots on these pads would reach 0.32 mm past the pad edge (at most 0.25 mm): a pad type for "
                + "this size avoids it.", g.reasonText);
        // a half-turn dot reaches 0.23 mm: inside the marginal band, the type's measured dot decided
        t.dotSteps = 800;
        assertTrue(ProfileCheck.of(in).groups.isEmpty());
        assertEquals("Wide's dots on these pads would come within 0.05 mm of the next pad (at least 0.10 mm): a pad type "
                + "for this size avoids it.", Texts.outsideCopper("Wide", 0.05));
    }

    @Test
    public void aTypeOfAnotherTipIsBelowTheGate() {
        CheckInputs in = PadFixtures.inputs(part("R1", "RES", 0, 0, 0.8, 0.95, 50, -0.825, 0.825));
        TypeInfo t = type("chip-0603", "Chip 0603", TypeInfo.CHARACTERIZED, 0.8, 0.95);
        t.tipKey = "25g";
        SuggesterTest.type(in, t, "RES");
        ProfileCheck.Group g = ProfileCheck.of(in).groups.get(0);
        assertEquals(ProfileCheck.BELOW_GATE, g.reason);
        assertTrue(g.blocking);
        assertTrue(g.fixable);
        assertEquals("calibrated for another tip", g.shortText);
        assertEquals("Chip 0603 was calibrated with another tip (25g); the mounted tip is the 22G tip. Mass per turn and "
                + "dot size change with the tip: calibrate it again for this tip.", g.reasonText);
        assertEquals(Suggestion.CALIBRATE_EXISTING, g.suggestion.kind);
        // and is never offered for reuse
        t.tipKey = Tip.g22().key;
        assertTrue(ProfileCheck.of(in).groups.isEmpty());
    }

    @Test
    public void outsideRecipeAndInterlock() {
        CheckInputs in = PadFixtures.inputs(part("R1", "RES", 0, 0, 0.8, 0.95, 50, -0.825, 0.825));
        TypeInfo t = type("chip-0603", "Chip 0603", TypeInfo.CHARACTERIZED, 0.8, 0.95);
        t.doseFp = "psi=16.000 suck=10.000 rpm=180.000 acc=20000.0 dwell=250 gap=0.1000";
        in.doseFingerprint = "psi=18.000 suck=10.000 rpm=180.000 acc=20000.0 dwell=250 gap=0.1000";
        SuggesterTest.type(in, t, "RES");
        ProfileCheck.Group g = ProfileCheck.of(in).groups.get(0);
        assertEquals(ProfileCheck.OUTSIDE_RECIPE, g.outsideKind);
        assertEquals("Chip 0603 was calibrated with another recipe (psi 16 -> 18); calibrate it again, or "
                + "set the profile back.", g.reasonText);
        t.doseFp = null;
        t.dotSteps = 400;
        t.dotsPerPad = 1;
        g = ProfileCheck.of(in).groups.get(0);
        assertEquals(ProfileCheck.OUTSIDE_INTERLOCK, g.outsideKind);
        assertEquals("pad type chip-0603 v2 gives each pad 400 steps (a quarter turn); every pad needs at least half a "
                + "screw turn in a row, or its paste varies more with the screw angle than the validated 0603 pads: use "
                + "more dots or a larger turn", g.reasonText);
        t.dotSteps = 200;
        t.dotsPerPad = 4;
        g = ProfileCheck.of(in).groups.get(0);
        assertEquals("pad type chip-0603 v2 has eighth-turn dots; the 22G tip takes quarter, half or one-turn dots (a "
                + "quarter turn at least): edit it and plan again", g.reasonText);
    }

    @Test
    public void dotsThatMayBridgeAreAFlaggedReason() {
        // a 1.0 x 1.8 pad pair whose type lays 2 half-turn dots 0.94 apart; a neighbour's dot 1.8 mm above pad 1
        // (§4.3 would lay one one-turn dot: 0.55 mm from it)
        List<PadInstance> pads = part("C1", "TALL", 0, 0, 1.0, 1.8, 0, -1.0, 1.0);
        pads.add(new PadInstance("N1", "NB", "1", -1.0, 1.8, 0, 0.5, 0.5, 0));
        CheckInputs in = PadFixtures.inputs(pads);
        TypeInfo tall = type("tall", "Tall", TypeInfo.CHARACTERIZED, 1.0, 1.8);
        tall.dotSteps = 800;
        tall.dotsPerPad = 2;
        SuggesterTest.type(in, tall, "TALL");
        TypeInfo nb = type("nb", "Nb", TypeInfo.CHARACTERIZED, 0.5, 0.5);
        nb.dotsPerPad = 1;
        SuggesterTest.type(in, nb, "NB");
        ProfileCheck.Result r = ProfileCheck.of(in);
        ProfileCheck.Group g = only(r, "TALL");
        assertEquals(ProfileCheck.BRIDGE, g.reason);
        assertEquals("N1 pad 1", g.bridgeTo);
        assertEquals("dots may bridge to N1 pad 1", g.shortText);
        assertEquals("Tall's dots may bridge to N1 pad 1: they would leave 0.17 mm to its dot (at least 0.20 mm). A pad "
                + "type for this size avoids it.", g.reasonText);
        assertTrue(g.blocking, "before planning: the planner flags it");
        assertTrue(g.fixable);
        assertFalse(r.ok);
        assertEquals(1600, g.suggestion.dots.steps);
        // in a plan, blocking comes from the flags: MAY_BRIDGE set by the planner
        for (PadInstance p : in.pads) {
            p.dots = new ArrayList<>();
            for (double[] o : DotGeometry.layoutN(p.wMm, p.hMm, 1, 0, 0)) {
                p.dots.add(DotGeometry.toBoard(p, o[0], o[1]));
            }
            if (p.packageId.equals("TALL")) {
                p.dots = new ArrayList<>(Arrays.asList(DotGeometry.toBoard(p, 0, -0.47), DotGeometry.toBoard(p, 0, 0.47)));
            }
        }
        in.planned = true;
        g = only(ProfileCheck.of(in), "TALL");
        assertEquals(ProfileCheck.BRIDGE, g.reason);
        assertFalse(g.blocking);
        for (PadInstance p : in.pads) {
            if (p.packageId.equals("TALL")) {
                p.flags.add(ProfileCheck.FLAG_MAY_BRIDGE);
                p.blocked = true;
            }
        }
        assertTrue(only(ProfileCheck.of(in), "TALL").blocking);
    }

    @Test
    public void blockingComesOnlyFromTheFlagsOfAPlan() {
        List<PadInstance> pads = part("R1", "RES", 0, 0, 0.8, 0.95, 50, -0.825, 0.825);
        for (PadInstance p : pads) {
            p.flags.add(ProfileCheck.FLAG_NO_PAD_TYPE);
        }
        CheckInputs in = PadFixtures.inputs(pads);
        in.planned = true;
        ProfileCheck.Result r = ProfileCheck.of(in);
        assertEquals(ProfileCheck.NO_TYPE, r.groups.get(0).reason);
        assertFalse(r.groups.get(0).blocking, "refuseFlaggedPads off: nothing blocked");
        assertEquals(0, r.fixable);
        assertTrue(r.ok);
        pads.get(0).blocked = true;
        r = ProfileCheck.of(in);
        assertTrue(r.groups.get(0).blocking);
        assertEquals(1, r.fixable);
        assertFalse(r.ok);
        // the check never adds or removes a flag
        assertEquals(Collections.singletonList(ProfileCheck.FLAG_NO_PAD_TYPE), pads.get(1).flags);
    }

    @Test
    public void overlappingOpeningsOfOnePartAreOneDeposit() {
        // a paste window stacked 0.0125 mm off its pad (mobo AVX-9155): not neighbours
        List<PadInstance> pads = new ArrayList<>();
        pads.add(new PadInstance("J5", "STACK", "6", 0, 0, 0, 1.5, 2.15, 20));
        pads.add(new PadInstance("J5", "STACK", "6~1", 0, -0.0125, 0, 1.5, 2.125, 0));
        pads.add(new PadInstance("J5", "STACK", "7", 13.8, 0, 0, 1.5, 2.15, 20));
        pads.add(new PadInstance("J5", "STACK", "7~1", 13.8, -0.0125, 0, 1.5, 2.125, 0));
        ProfileCheck.Result r = ProfileCheck.of(PadFixtures.inputs(pads));
        for (ProfileCheck.Group g : r.groups) {
            assertEquals(ProfileCheck.NO_TYPE, g.reason, g.label);
        }
        // the same window on another part's pad is a neighbour (its dot lands on that copper)
        pads.get(1).ref = "J6";
        pads.get(1).padId = "J6.6~1";
        boolean tip = false;
        for (ProfileCheck.Group g : ProfileCheck.of(PadFixtures.inputs(pads)).groups) {
            tip |= ProfileCheck.TIP.equals(g.reason);
        }
        assertTrue(tip);
    }

    @Test
    public void countsAndTheResult() {
        List<PadInstance> pads = new ArrayList<>();
        pads.addAll(part("R1", "RES", 0, 0, 0.8, 0.95, 50, -0.825, 0.825));
        pads.addAll(part("D1", "SOLO", 10, 0, 1.2, 1.2, 0, 0));
        pads.addAll(part("U1", "FINE", 20, 0, 0.25, 0.8, 0, -0.25, 0.25));
        pads.addAll(part("L1", "LED", 30, 0, 1.048, 1.048, 20, -1.05, 1.05));
        CheckInputs in = PadFixtures.inputs(pads);
        SuggesterTest.type(in, type("chip-0603", "Chip 0603", TypeInfo.PROVISIONAL, 0.8, 0.95), "RES");
        TypeInfo t = type("chip-0805", "Chip 0805", TypeInfo.CHARACTERIZED, 1.025, 1.40);
        t.calAreaMm2 = 1.381;
        SuggesterTest.type(in, t, "LED");
        in.nowMs = 1793000000000L;
        ProfileCheck.Result r = ProfileCheck.of(in);
        assertEquals(4, r.groups.size());
        assertEquals(2, r.fixable, "below-gate RES, no-type SOLO");
        assertEquals(1, r.tipBlocking);
        assertEquals(1, r.notes);
        assertFalse(r.ok);
        assertEquals(1793000000000L, r.computedAtMs);
        assertEquals("ad22tta-b", r.tip.key);
        assertEquals("22G tip", r.tip.name);
        assertEquals(400, r.tip.minDotSteps);
        assertEquals(0.825, r.tip.minDotMm[0], 0.001);
        assertEquals(1.0, r.tip.minDotMm[1], 0.001);
    }

    @Test
    public void numberFormats() {
        assertEquals("1.48", Texts.mm(1.475));
        assertEquals("0.00", Texts.mm(-0.001));
        assertEquals("16", Texts.pct(-0.162));
        assertEquals("+33", Texts.signedPct(0.3328));
        assertEquals("-33", Texts.signedPct(-0.3336));
        assertEquals("1 pad", Texts.plural(1, "pad", "pads"));
        assertEquals("3 pads", Texts.plural(3, "pad", "pads"));
        assertEquals("validated on a board", Texts.statusWord("validated"));
        assertEquals("not started", Texts.statusWord("not-started"));
    }
}
