package org.openpnp.instancelabs.padprofile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseDb;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.PadTypeResolver;
import org.openpnp.instancelabs.dispense.PadTypeStatus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tip setups (§22.8): the suggester per tip setup. The bench setup (the seeded 22G bent taper, §1's model) gives
 * exactly the results of a4bb6d20d: a golden file of every §1.9 fixture check, written by that code. A setup the
 * bench model does not describe suggests from its own dot table only: unmeasured, it asks for its dots to be measured;
 * measured, it plans from its d95 and mass and never with a dose it does not know. Other setups' validated pads anchor
 * the amount; a stand-in says what the other setups have; a plan copied from another tip is low.
 */
public class SuggesterSetupTest {
    static final String GOLDEN = "/tips/suggester-bench-a4bb6d20d.json";

    /** The checks of the golden file: every package alone, every board side, the boards with the seed types. */
    static Map<String, CheckInputs> goldenInputs() {
        Map<String, CheckInputs> m = new LinkedHashMap<>();
        for (String board : new String[] {"ftp", "mobo", "nano"}) {
            PadFixtures.Fixture f = PadFixtures.load(board);
            for (Map.Entry<String, List<PadInstance>> e : PadFixtures.packageLevel(f).entrySet()) {
                CheckInputs in = benchInputs(e.getValue());
                in.pastePartialPackages.add(e.getValue().get(0).packageId);
                m.put(board + "/package/" + e.getKey(), in);
            }
            for (String side : new String[] {"top", "bottom"}) {
                m.put(board + "/" + side, benchInputs(PadFixtures.side(f, side)));
                m.put(board + "/" + side + "/seed-types", withSeedTypes(PadFixtures.side(f, side)));
            }
        }
        return m;
    }

    /** The bench tip as a4bb6d20d named it (its texts say "22G tip"). */
    static Tip goldenTip() {
        Tip t = Tip.g22();
        t.name = "22G tip";
        return t;
    }

    static CheckInputs benchInputs(List<PadInstance> pads) {
        CheckInputs in = PadFixtures.inputs(pads);
        in.tip = goldenTip();
        return in;
    }

    /**
     * The seed pad types resolved onto the pads as the planner would (D/PadTypeResolver), chip-0603 characterized
     * with its own half-turn plan and chip-0805 validated with a weighed mass (an anchor), the rest not started.
     */
    static CheckInputs withSeedTypes(List<PadInstance> pads) {
        List<PadType> types = DispenseDb.seedPadTypes();
        for (PadType t : types) {
            t.version = 1;
            if ("chip-0603".equals(t.typeKey)) {
                t.status = PadTypeStatus.CHARACTERIZED;
                t.dotSteps = 800;
                t.dotsPerPad = 1;
                t.tipKey = Tip.SEED_KEY;
            }
            else if ("chip-0805".equals(t.typeKey)) {
                t.status = PadTypeStatus.VALIDATED;
                t.dotSteps = 1600;
                t.dotsPerPad = 1;
                t.massPerPadMg = 2.0;
                t.tipKey = Tip.SEED_KEY;
            }
        }
        PadTypeResolver resolver = new PadTypeResolver(types, null);
        CheckInputs in = benchInputs(new ArrayList<>());
        for (PadType t : types) {
            TypeInfo i = new TypeInfo();
            i.key = t.typeKey;
            i.name = t.name;
            i.family = t.family;
            i.version = t.version;
            i.status = t.status.dbName;
            i.padWMm = t.padWMm;
            i.padHMm = t.padHMm;
            i.padRoundness = t.padRoundness;
            i.k = t.k;
            i.dotSteps = t.dotSteps;
            i.dotsPerPad = t.dotsPerPad;
            i.tipKey = t.tipKey;
            i.massPerPadMg = t.massPerPadMg;
            i.origin = t.origin;
            in.types.put(t.typeKey, i);
        }
        for (PadInstance p : pads) {
            PadType t = resolver.resolve(p.packageId, p.padName, Double.NaN, Double.NaN, p.areaMm2());
            if (t != null) {
                p.typeKey = t.typeKey;
                p.typeVersion = t.version;
                p.typeStatus = t.status.dbName;
            }
            in.pads.add(p);
        }
        return in;
    }

    /** The result as the golden file keeps it: the tip, the anchors and the groups (without the per-pad map). */
    static JsonObject golden(ProfileCheck.Result r) {
        Gson g = new GsonBuilder().serializeSpecialFloatingPointValues().create();
        JsonObject o = new JsonParser().parse(g.toJson(r)).getAsJsonObject();
        o.remove("pads");
        return o;
    }

    @Test
    public void writesTheGoldenFileWhenAsked() throws Exception {
        String out = System.getProperty("golden.write");
        if (out == null) {
            return;
        }
        JsonObject all = new JsonObject();
        for (Map.Entry<String, CheckInputs> e : goldenInputs().entrySet()) {
            all.add(e.getKey(), golden(ProfileCheck.of(e.getValue())));
        }
        Files.write(new File(out).toPath(), new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues()
                .create().toJson(all).getBytes(StandardCharsets.UTF_8));
        assertTrue(new File(out).length() > 0);
    }

    static JsonObject readGolden() throws Exception {
        try (InputStream in = SuggesterSetupTest.class.getResourceAsStream(GOLDEN)) {
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** Every key of the golden value is in the new one with the same value (new keys are allowed). */
    static void sameAsGolden(String path, JsonElement golden, JsonElement now, List<String> diffs) {
        if (golden.isJsonObject() && now != null && now.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : golden.getAsJsonObject().entrySet()) {
                sameAsGolden(path + "." + e.getKey(), e.getValue(), now.getAsJsonObject().get(e.getKey()), diffs);
            }
            return;
        }
        if (golden.isJsonArray() && now != null && now.isJsonArray()
                && golden.getAsJsonArray().size() == now.getAsJsonArray().size()) {
            for (int i = 0; i < golden.getAsJsonArray().size(); i++) {
                sameAsGolden(path + "[" + i + "]", golden.getAsJsonArray().get(i), now.getAsJsonArray().get(i), diffs);
            }
            return;
        }
        if (!golden.equals(now) && diffs.size() < 20) {
            diffs.add(path + ": " + golden + " -> " + now);
        }
    }

    @Test
    public void theBenchSetupGivesExactlyTheResultsOfA4bb6d20d() throws Exception {
        JsonObject golden = readGolden();
        Map<String, CheckInputs> inputs = goldenInputs();
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : golden.entrySet()) {
            keys.add(e.getKey());
        }
        assertEquals(keys, new ArrayList<>(inputs.keySet()), "the same checks");
        List<String> diffs = new ArrayList<>();
        int belowGate = 0;
        int named = 0;
        for (Map.Entry<String, CheckInputs> e : inputs.entrySet()) {
            JsonObject was = golden.getAsJsonObject(e.getKey());
            ProfileCheck.Result r = ProfileCheck.of(e.getValue());
            JsonObject now = golden(r);
            was.remove("computedAtMs");
            now.remove("computedAtMs");
            // BR-85 changes another: a family scaled from a validated chip type (the seed types' 0805) names it
            JsonArray anchors = was.getAsJsonArray("anchors");
            if (anchors.size() == 1 && !anchors.get(0).getAsJsonObject().get("bench").getAsBoolean()) {
                String w = was.toString();
                String half = "scaled from the 0603 half turn";
                named += w.split(Pattern.quote(half), -1).length - 1;
                was = new JsonParser().parse(w.replace(half, "scaled from " + anchors.get(0).getAsJsonObject().get(
                        "text").getAsString())).getAsJsonObject();
            }
            // §22.2 changes one text: a type below the gate names the mounted setup ("for the 22G tip")
            for (int i = 0; i < r.groups.size(); i++) {
                ProfileCheck.Group g = r.groups.get(i);
                if (ProfileCheck.BELOW_GATE.equals(g.reason) && !"calibrated for another tip".equals(g.shortText)) {
                    belowGate++;
                    assertTrue(BELOW_GATE_TEXT.matcher(g.reasonText).matches(), g.reasonText);
                    was.getAsJsonArray("groups").get(i).getAsJsonObject().remove("reasonText");
                    now.getAsJsonArray("groups").get(i).getAsJsonObject().remove("reasonText");
                }
            }
            sameAsGolden(e.getKey(), was, now, diffs);
        }
        assertTrue(diffs.isEmpty(), String.join("\n", diffs));
        assertTrue(belowGate > 0, "the seed types put groups below the gate");
        assertEquals(20, named, "the seed types' families scaled from their validated 0805");
    }

    static final Pattern BELOW_GATE_TEXT = Pattern.compile(".+ is not calibrated for the " + Pattern.quote(goldenTip().name)
            + " yet \\(status: (not started|provisional)\\)\\. Boards need pad types that are at least characterized: "
            + "calibrate it for this tip automatically or by hand\\.");

    static final String K25 = "25g-straight";

    /** The 25G straight (§22.1's example), unmeasured or with a dot table of quarter and half turns, weighed. */
    static Tip tip25(boolean measured) {
        Tip t = new Tip(K25, "25G straight", 0.25, 0.51, DoseModel.QUARTER);
        t.setupVersion = 1;
        t.dispenser = "Vieweg DV-5005";
        if (measured) {
            t.measured.put(DoseModel.QUARTER, measuredDot(0.40, 0.45));
            t.measured.put(DoseModel.HALF, measuredDot(0.50, 0.56));
            t.mgPerStep = 0.001;
            t.mgPerStepAtMs = 1_793_300_000_000L;
            t.mgPerStepConfirmed = true;
        }
        return t;
    }

    static Tip.Measured measuredDot(double mean, double d95) {
        Tip.Measured m = new Tip.Measured();
        m.dMeanMm = mean;
        m.d95Mm = d95;
        m.recordId = 7;
        m.atMs = 1_793_300_000_000L;
        return m;
    }

    static CheckInputs with(Tip tip, List<PadInstance> pads) {
        CheckInputs in = PadFixtures.inputs(pads);
        in.tip = tip;
        return in;
    }

    @Test
    public void anUnmeasuredSetupAsksForItsDotsAndBlocksNoGroupForTheTip() {
        int measureTip = 0;
        for (String board : new String[] {"ftp", "mobo", "nano"}) {
            PadFixtures.Fixture f = PadFixtures.load(board);
            for (String side : new String[] {"top", "bottom"}) {
                ProfileCheck.Result r = ProfileCheck.of(with(tip25(false), PadFixtures.side(f, side)));
                assertEquals("none", r.tip.dots);
                assertNull(r.tip.minDotMm);
                assertEquals(0, r.tipBlocking, board + "/" + side);
                for (ProfileCheck.Group g : r.groups) {
                    assertFalse(ProfileCheck.TIP.equals(g.reason) || ProfileCheck.PART.equals(g.reason), g.group);
                    assertNull(g.tipKind, g.group);
                    if (ProfileCheck.NO_TYPE.equals(g.reason) || ProfileCheck.BELOW_GATE.equals(g.reason)) {
                        assertEquals(Suggestion.MEASURE_TIP, g.suggestion.kind, g.group);
                        assertEquals(Texts.measureTip("25G straight"), g.suggestion.sentence);
                        assertNull(g.suggestion.dots, "no plan without dot sizes");
                        measureTip++;
                    }
                }
            }
        }
        assertTrue(measureTip > 0);
        // the bench setup on the same pads: tip groups (the nano's fine pitch)
        ProfileCheck.Result bench = ProfileCheck.of(benchInputs(PadFixtures.side(PadFixtures.load("nano"), "top")));
        assertTrue(bench.tipBlocking > 0);
    }

    @Test
    public void aMeasuredTablePlansFromItsD95AndMassAndNeverWithADoseItDoesNotKnow() {
        int plans = 0;
        for (String board : new String[] {"ftp", "mobo"}) {
            PadFixtures.Fixture f = PadFixtures.load(board);
            ProfileCheck.Result r = ProfileCheck.of(with(tip25(true), PadFixtures.side(f, "top")));
            assertEquals("measured", r.tip.dots);
            assertArrayEquals(new double[] {0.40, 0.45}, r.tip.minDotMm, 0);
            for (ProfileCheck.Group g : r.groups) {
                Suggestion s = g.suggestion;
                if (s == null || s.dots == null) {
                    continue;
                }
                plans++;
                assertTrue(s.dots.steps == DoseModel.QUARTER || s.dots.steps == DoseModel.HALF, g.group + " "
                        + s.dots.steps);
                Tip.Measured m = tip25(true).measured.get(s.dots.steps);
                assertArrayEquals(new double[] {m.dMeanMm, m.d95Mm}, s.dots.diameterMm, 1e-9, g.group);
                assertEquals("table", s.dots.dSource);
                assertEquals("table", s.dots.mgSource);
                assertEquals(0.001 * s.dots.steps, s.dots.mgPerDot, 1e-9);
                for (Suggestion.Alternative a : s.alternatives) {
                    assertTrue(a.steps != DoseModel.ONE_TURN, g.group + ": a one-turn alternative");
                }
                if (s.calibration != null) {
                    assertFalse(s.calibration.doses.contains(DoseModel.ONE_TURN), g.group + ": " + s.calibration.doses);
                }
                assertTrue(s.confidenceWhy.contains("the dot size comes from the 25G straight's earlier measurement, "
                        + "not a fresh one"), s.confidenceWhy.toString());
            }
        }
        assertTrue(plans > 0);
    }

    /** Two 0603 resistors at 0.80 x 0.95 mm, 1.65 mm apart, resolved to chip-0603 at this version and status. */
    static List<PadInstance> chips(TypeInfo t) {
        List<PadInstance> l = new ArrayList<>();
        for (int part = 0; part < 2; part++) {
            for (int i = 0; i < 2; i++) {
                PadInstance p = new PadInstance("R" + (part + 1), "R_0603_1608Metric", Integer.toString(i + 1), 100
                        + part * 5 + i * 1.65, 80, 0, 0.80, 0.95, 25);
                p.padId = p.ref + "." + (i + 1);
                if (t != null) {
                    p.typeKey = t.key;
                    p.typeVersion = t.version;
                    p.typeStatus = t.status;
                    p.typeRule = "pattern";
                }
                l.add(p);
            }
        }
        return l;
    }

    static TypeInfo chip0603(String status, int version) {
        TypeInfo t = new TypeInfo();
        t.key = "chip-0603";
        t.name = "0603 chip";
        t.family = Family.CHIP;
        t.version = version;
        t.status = status;
        t.padWMm = 0.80;
        t.padHMm = 0.95;
        t.padRoundness = 25;
        t.tipKey = K25;
        t.origin = "manual";
        return t;
    }

    @Test
    public void anotherSetupsValidatedPadsAnchorTheAmountWithTheirReason() {
        CheckInputs in = with(tip25(true), chips(null));
        Anchor a = new Anchor(Family.CHIP, "chip-0603", 3, 1.10, DotGeometry.padAreaMm2(0.80, 0.95, 25));
        a.setupName = Tip.SEED_NAME;
        a.text = "chip-0603 v3 validated with the " + Tip.SEED_NAME;
        in.moreAnchors.add(a);
        ProfileCheck.Result r = ProfileCheck.of(in);
        assertEquals(1, r.groups.size());
        ProfileCheck.Group g = r.groups.get(0);
        assertEquals(ProfileCheck.NO_TYPE, g.reason);
        assertEquals(Suggestion.NEW, g.suggestion.kind);
        assertEquals(1.10, g.suggestion.amount.targetMg, 0.01, "the 22G's validated amount, k = 1");
        assertTrue(g.suggestion.confidenceWhy.contains("the amount is anchored on pads validated with the "
                + Tip.SEED_NAME), g.suggestion.confidenceWhy.toString());
        assertEquals(1, r.anchors.size());
        assertEquals(Tip.SEED_NAME, r.anchors.get(0).setupName);
        // without it the chip family falls back to the bench anchor, which says nothing of another setup
        ProfileCheck.Result alone = ProfileCheck.of(with(tip25(true), chips(null)));
        assertTrue(alone.anchors.get(0).bench);
        assertFalse(alone.groups.get(0).suggestion.confidenceWhy.stream().anyMatch(w -> w.contains("validated with")));
    }

    /** Two parts of {@code pkg} ({@code ref}1, {@code ref}2), each two untyped w x h openings {@code pitch} apart. */
    static List<PadInstance> parts(String ref, String pkg, double w, double h, double pitch, double y) {
        List<PadInstance> l = new ArrayList<>();
        for (int part = 0; part < 2; part++) {
            for (int i = 0; i < 2; i++) {
                PadInstance p = new PadInstance(ref + (part + 1), pkg, Integer.toString(i + 1), 100 + part * 8 + i
                        * pitch, y, 0, w, h, 25);
                p.padId = p.ref + "." + (i + 1);
                l.add(p);
            }
        }
        return l;
    }

    /**
     * BR-85: the 25G's own anchor, 1.95 mg (a whole turn, weighed by Process) on the chip-0603 type's opening, 0.80 x
     * 0.95 mm: 0.760 mm² (the dose-anchor route takes the type's opening, not a board's).
     */
    static Anchor own0603() {
        Anchor a = new Anchor(Family.CHIP, "chip-0603", null, 1.95, DotGeometry.padAreaMm2(0.80, 0.95, 0));
        a.tipName = "25G straight";
        return a;
    }

    /** Each group's target (mg per pad) by its pad type. */
    static Map<String, Double> targets(CheckInputs in) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (ProfileCheck.Group g : ProfileCheck.prepare(in).groups) {
            m.put(g.typeKey, g.target);
        }
        return m;
    }

    /**
     * BR-85 re-review: a tip's own anchor is the amount for its own pad type only. Reflow accepting the 25G's whole
     * turn on 0603 pads says nothing of bigger ones: a 1206 and a QFN centre pad aim at what they aimed at before
     * BR-85, the bench's 0603 half turn (k 0.5 on the centre pad), not at the 25G's 0603 rate scaled up to their
     * openings (nearly twice as much). With the 22G's validated, weighed 0603 they aim at that one, as before; the
     * check lists both anchors of chip-0603, the tip's and the 22G's.
     */
    @Test
    public void aTipsOwnAnchorIsForItsOwnPadTypeOnly() {
        TypeInfo t0603 = chip0603(TypeInfo.NOT_STARTED, 1);
        List<PadInstance> pads = new ArrayList<>(chips(t0603));
        pads.addAll(parts("R1", "R_1206_3216Metric", 1.125, 1.75, 2.925, 90));
        PadInstance ep = new PadInstance("U1", "QFN-20-1EP_4x4mm_P0.5mm_EP3.1x3.1mm", "21", 120, 60, 0, 3.10, 3.10, 0);
        ep.centrePad = true;
        pads.add(ep);
        CheckInputs in = with(tip25(true), pads);
        in.types.put(t0603.key, t0603);
        TypeInfo t1206 = chip0603(TypeInfo.NOT_STARTED, 1);
        t1206.key = "chip-1206";
        t1206.name = "1206 chip";
        t1206.padWMm = 1.125;
        t1206.padHMm = 1.75;
        SuggesterTest.type(in, t1206, "R_1206_3216Metric");
        TypeInfo centre = chip0603(TypeInfo.NOT_STARTED, 1);
        centre.key = "qfn-centre";
        centre.name = "QFN centre pad";
        centre.family = Family.NO_LEAD;
        centre.padWMm = 3.10;
        centre.padHMm = 3.10;
        centre.padRoundness = 0;
        centre.k = 0.5;
        SuggesterTest.type(in, centre, ep.packageId);
        Map<String, Double> before = targets(in);
        assertEquals(3, before.size(), before.toString());
        Anchor own = own0603();
        in.anchors.put(own.typeKey, own);
        for (ProfileCheck.Group g : ProfileCheck.prepare(in).groups) {
            if (t0603.key.equals(g.typeKey)) {
                assertTrue(own == g.anchor, g.anchor.typeKey);
                assertEquals(1.95 * g.area / 0.76, g.target, 1e-9);
            }
            else {
                assertTrue(g.anchor.bench, g.typeKey + " aims at " + g.anchor.typeKey);
                assertEquals(before.get(g.typeKey), g.target, 1e-12, g.typeKey);
                assertEquals(Anchor.BENCH.mgPerMm2 * g.area * (g.centre ? 0.5 : 1), g.target, 1e-9, g.typeKey);
            }
        }
        // the 22G's validated, weighed 0603 (§22.2) anchors the other types, as before BR-85
        Anchor g22 = new Anchor(Family.CHIP, "chip-0603", 4, 1.10, DotGeometry.padAreaMm2(0.80, 0.95, 50));
        g22.setupName = Tip.SEED_NAME;
        in.moreAnchors.add(g22);
        in.anchors.clear();
        before = targets(in);
        in.anchors.put(own.typeKey, own);
        for (ProfileCheck.Group g : ProfileCheck.prepare(in).groups) {
            assertTrue((t0603.key.equals(g.typeKey) ? own : g22) == g.anchor, g.typeKey + " aims at " + g.anchor.typeKey);
            if (!t0603.key.equals(g.typeKey)) {
                assertEquals(before.get(g.typeKey), g.target, 1e-12, g.typeKey);
            }
        }
        ProfileCheck.Result r = ProfileCheck.of(in);
        assertEquals(2, r.anchors.size(), "the tip's and the 22G's, both of chip-0603");
        assertTrue(r.anchors.stream().anyMatch(a -> "25G straight".equals(a.tipName)));
        assertTrue(r.anchors.stream().anyMatch(a -> Tip.SEED_NAME.equals(a.setupName)));
    }

    /**
     * BR-85 re-review: on its own pad type the tip's anchor wins, also over the tip's own validated, weighed version of
     * that type. The anchor is on the type's opening (0.760 mm²), the version on the board's opening it was calibrated
     * on (R_0603_1608Metric, roundness 50: 0.726 mm², its calAreaMm2), which is the 0603 pads' own: by the nearest
     * opening the version would win. Other chips keep that version as their anchor, as before BR-85.
     */
    @Test
    public void onItsOwnPadTypeTheTipsAnchorWinsOverItsValidatedVersion() {
        TypeInfo v0603 = chip0603(TypeInfo.VALIDATED, 2);
        v0603.massPerPadMg = 1.30;
        v0603.calAreaMm2 = DotGeometry.padAreaMm2(0.80, 0.95, 50);
        List<PadInstance> pads = new ArrayList<>(chips(v0603));
        for (PadInstance p : pads) {
            p.roundness = 50; // the board's R_0603_1608Metric openings
        }
        pads.addAll(parts("R1", "R_1206_3216Metric", 1.125, 1.75, 2.925, 90));
        CheckInputs in = with(tip25(true), pads);
        in.types.put(v0603.key, v0603);
        Anchor own = own0603();
        in.anchors.put(own.typeKey, own);
        assertEquals(0.726, v0603.calAreaMm2, 0.0005);
        assertEquals(0.760, own.areaMm2, 1e-9);
        int n = 0;
        for (ProfileCheck.Group g : ProfileCheck.prepare(in).groups) {
            n++;
            if (v0603.key.equals(g.typeKey)) {
                assertEquals(v0603.calAreaMm2, g.area, 1e-12);
                assertTrue(own == g.anchor, "the tip's anchor, not its validated 0603 v" + g.anchor.version);
                assertEquals(1.95 * g.area / 0.76, g.target, 1e-9);
            }
            else {
                assertEquals(v0603.key, g.anchor.typeKey, "the 1206 (no type)");
                assertNull(g.anchor.tipName, "the 25G's validated 0603, as before BR-85");
                assertEquals(1.30 / v0603.calAreaMm2 * g.area, g.target, 1e-9);
            }
        }
        assertEquals(2, n);
    }

    /**
     * BR-85 re-review: a family with no anchor of its own scales from the chips' (§1.3), never from a tip's own anchor
     * (its pad type's only), and the reason names the anchor: "the 0603 half turn" only for the bench's.
     */
    @Test
    public void aFamilyWithNoAnchorOfItsOwnScalesFromTheChipsAndNamesTheAnchor() {
        CheckInputs in = with(tip25(true), parts("D", "D_SOD-123", 0.90, 1.20, 3.30, 100));
        in.anchors.put("chip-0603", own0603());
        ProfileCheck.Group g = ProfileCheck.of(in).groups.get(0);
        assertEquals(Family.SMALL_OUTLINE, g.family);
        assertFalse(g.ownAnchor);
        assertTrue(g.anchor.bench, "the bench's half turn, not the 25G's 0603 anchor");
        assertFalse(g.capped, "3.3 mm apart: no fine-pitch cap");
        assertEquals(Suggester.r3(Anchor.BENCH.mgPerMm2 * g.area), g.suggestion.amount.targetMg, 1e-9);
        assertTrue(g.suggestion.confidenceWhy.contains("no validated Small-outline discretes pads yet: the amount is "
                + "scaled from the 0603 half turn"), g.suggestion.confidenceWhy.toString());
        // the 22G's validated, weighed 0805 anchors the chips: the reason names it
        Anchor g22 = new Anchor(Family.CHIP, "chip-0805", 3, 2.00, DotGeometry.padAreaMm2(1.025, 1.40, 0));
        g22.setupName = Tip.SEED_NAME;
        in.moreAnchors.add(g22);
        g = ProfileCheck.of(in).groups.get(0);
        assertTrue(g22 == g.anchor);
        assertTrue(g.suggestion.confidenceWhy.contains("no validated Small-outline discretes pads yet: the amount is "
                + "scaled from chip-0805 validated on a board (2.00 mg on 1.43 mm²)"), g.suggestion.confidenceWhy
                        .toString());
    }

    @Test
    public void aStandInSaysWhatTheOtherSetupsHave() {
        TypeInfo t = chip0603(TypeInfo.NOT_STARTED, 0);
        t.standIn = true;
        t.setupName = "25G straight";
        TypeInfo.Other o = new TypeInfo.Other();
        o.setupKey = Tip.SEED_KEY;
        o.setupName = Tip.SEED_NAME;
        o.version = 2;
        o.status = TypeInfo.CHARACTERIZED;
        t.others.add(o);
        CheckInputs in = with(tip25(true), chips(t));
        in.types.put(t.key, t);
        ProfileCheck.Group g = ProfileCheck.of(in).groups.get(0);
        assertEquals(ProfileCheck.BELOW_GATE, g.reason);
        assertEquals("not calibrated yet", g.shortText);
        assertEquals("0603 chip is not calibrated for the 25G straight yet (status: not started; characterized for the "
                + Tip.SEED_NAME + "). Boards need pad types that are at least characterized: calibrate it for this tip "
                + "automatically or by hand.", g.reasonText);
        assertEquals(Suggestion.CALIBRATE_EXISTING, g.suggestion.kind);
        assertNotNull(g.suggestion.dots, "the 25G's own plan from its table");
    }

    @Test
    public void aPlanCopiedFromAnotherTipIsLow() {
        TypeInfo t = chip0603(TypeInfo.PROVISIONAL, 1);
        t.origin = Suggester.PadTypeOrigin.COPIED;
        t.copiedFrom = Tip.SEED_NAME;
        t.dotSteps = DoseModel.HALF;
        t.dotsPerPad = 1;
        CheckInputs in = with(tip25(true), chips(t));
        in.types.put(t.key, t);
        ProfileCheck.Group g = ProfileCheck.of(in).groups.get(0);
        assertEquals(ProfileCheck.BELOW_GATE, g.reason);
        Suggestion s = g.suggestion;
        assertEquals(Suggestion.CALIBRATE_EXISTING, s.kind);
        assertEquals("your plan", s.planFrom);
        assertEquals(DoseModel.HALF, s.dots.steps);
        assertEquals("low", s.confidence);
        assertTrue(s.confidenceWhy.contains("the plan was copied from the " + Tip.SEED_NAME + " and never measured with "
                + "this tip"), s.confidenceWhy.toString());
        // the same plan made for the 25G itself is not low for that reason
        t.origin = "manual";
        t.copiedFrom = null;
        Suggestion own = ProfileCheck.of(in).groups.get(0).suggestion;
        assertFalse(own.confidenceWhy.stream().anyMatch(w -> w.startsWith("the plan was copied")));
    }

    @Test
    public void theCheckOfAnUnmeasuredSetupIsValidJson() {
        // the API's Gson (A/Json.GSON) writes NaN as a bare token, which JSON.parse refuses
        Gson api = new GsonBuilder().serializeNulls().serializeSpecialFloatingPointValues().disableHtmlEscaping()
                .create();
        for (String board : new String[] {"ftp", "nano"}) {
            CheckInputs in = with(tip25(false), PadFixtures.side(PadFixtures.load(board), "top"));
            TypeInfo t = chip0603(TypeInfo.CHARACTERIZED, 1);
            t.dotSteps = DoseModel.HALF;
            in.types.put(t.key, t);
            String json = api.toJson(ProfileCheck.of(in));
            assertFalse(Pattern.compile("[:\\[,]\\s*-?(NaN|Infinity)").matcher(json).find(), json);
            JsonObject o = new JsonParser().parse(json).getAsJsonObject();
            assertTrue(o.getAsJsonObject("tip").get("minDotMm").isJsonNull());
            assertEquals("none", o.getAsJsonObject("tip").get("dots").getAsString());
        }
        // and a measured one that does not know one turn
        String json = api.toJson(ProfileCheck.of(with(tip25(true), PadFixtures.side(PadFixtures.load("mobo"), "top"))));
        assertFalse(Pattern.compile("[:\\[,]\\s*-?(NaN|Infinity)").matcher(json).find());
    }
}
