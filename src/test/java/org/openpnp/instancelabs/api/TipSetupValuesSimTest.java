package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseDb;
import org.openpnp.instancelabs.dispense.LandingResult;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.PadTypeStatus;
import org.openpnp.instancelabs.padprofile.Anchor;
import org.openpnp.instancelabs.padprofile.Family;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * BR-85 through the API on the SIM machine: the values that are a tip's own and follow it through a mount. The dots
 * its touch-off skips (its recipe's touchOffSkipDots) and its dose anchor (PUT /api/tip-setups/{key}/dose-anchor, a
 * guarded write), shown per setup in GET /api/tip-setups and for the mounted tip in status.dispense.tip.
 */
public class TipSetupValuesSimTest {
    TipSim t;

    @BeforeEach
    public void before() throws Exception {
        t = new TipSim();
        t.bench();
    }

    @AfterEach
    public void after() throws Exception {
        t.close();
    }

    /** A setup's view in GET /api/tip-setups. */
    JsonObject setup(String key) throws Exception {
        for (JsonElement e : TipSim.ok(t.get("/api/tip-setups")).getAsJsonArray("setups")) {
            if (key.equals(TipSim.str(e.getAsJsonObject(), "key"))) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no setup " + key);
    }

    int skipOf(String key) throws Exception {
        return setup(key).getAsJsonObject("profile").get("touchOffSkipDots").getAsInt();
    }

    JsonObject statusAnchor() throws Exception {
        return t.dispense().getAsJsonObject("tip").getAsJsonObject("doseAnchor");
    }

    @Test
    public void eachTipsTouchOffSkipIsItsRecipes() throws Exception {
        t.add25();
        t.mount(TipSim.K25);
        String recipe = "{\"forTip\":\"" + TipSim.K25 + "\",\"profile\":{\"touchOffSkipDots\":";
        TipSim.ok(t.call("PUT", "/api/config", recipe + "2}}"));
        TipSim.refused(t.call("PUT", "/api/config", recipe + "6}}"), 400, "bad-request");
        assertEquals(2, skipOf(TipSim.K25));
        assertEquals(0, skipOf(TipSim.SEED));
        // a ladder with no dot after the skipped ones and the reference pair to show the contact is refused before
        // anything moves
        JsonObject few = TipSim.refused(t.post("/api/calibrations/touch-off", "{\"steps\":4}"), 400, "bad-request");
        assertEquals("the 25G straight's recipe skips the first 2 dots after the prime (touchOffSkipDots), the "
                + "reference takes the next two and a dot after them shows the contact: lay at least 5 dots", TipSim
                        .str(few, "reason"));
        // the ranges' text says the real minimum, skip + 3
        String ranges = " to 20, step 0 to 0.5 mm, start gap at most 2 mm, pitch and jump ratio above 0";
        assertEquals("steps 5" + ranges, TipSim.str(TipSim.refused(t.post("/api/calibrations/touch-off",
                "{\"steps\":1}"), 400, "bad-request"), "reason"));
        // parked with the 25G's recipe while the 22G is on
        t.mount(TipSim.SEED);
        assertEquals(2, skipOf(TipSim.K25));
        assertEquals(0, skipOf(TipSim.SEED));
        JsonObject two = TipSim.refused(t.post("/api/calibrations/touch-off", "{\"steps\":2}"), 400, "bad-request");
        assertEquals("the reference takes the first two dots after the prime and a dot after them shows the contact: "
                + "lay at least 3 dots", TipSim.str(two, "reason"));
        assertEquals("steps 3" + ranges, TipSim.str(TipSim.refused(t.post("/api/calibrations/touch-off",
                "{\"steps\":21}"), 400, "bad-request"), "reason"));
    }

    /** The 25G mounted with a touch-off skip of {@code skip}; returns its recipe's PUT /api/config prefix. */
    String skipping(int skip) throws Exception {
        t.add25();
        t.mount(TipSim.K25);
        String recipe = "{\"forTip\":\"" + TipSim.K25 + "\",\"profile\":{\"touchOffSkipDots\":";
        TipSim.ok(t.call("PUT", "/api/config", recipe + skip + "}}"));
        return recipe;
    }

    /** As {@link #skipping}, ready to lay a touch-off: the 25G's frame and landing, and a camera. */
    String layingWith(int skip) throws Exception {
        String recipe = skipping(skip);
        t.frame();
        // where it lands, kept for the 25G: a test without the landing check (its job is PadCalibrationSimTest's)
        LandingResult l = new LandingResult();
        l.setupKey = TipSim.K25;
        l.needleSinceMs = t.token();
        l.n = 8;
        t.svc.setLanding(l, true, t.token());
        // the SIM scene has no paste: every dot's spot is empty
        t.svc.setDotImager(new ApiServerSimTest.FakeImager());
        return recipe;
    }

    /** A touch-off laid on the SIM machine and waited for: the answer, its run OK. */
    JsonObject laid(String body) throws Exception {
        JsonObject res = TipSim.ok(t.post("/api/calibrations/touch-off", body));
        JsonObject r = res.getAsJsonObject("result");
        assertEquals("OK", TipSim.str(r.getAsJsonObject("run"), "status"), r.toString());
        return res;
    }

    /** The commanded gaps of a laid touch-off (its analysis). */
    static double[] gaps(JsonObject laid) {
        JsonArray a = laid.getAsJsonObject("result").getAsJsonObject("analysis").getAsJsonArray("gapsMm");
        double[] g = new double[a.size()];
        for (int i = 0; i < g.length; i++) {
            g[i] = a.get(i).getAsDouble();
        }
        return g;
    }

    /**
     * BR-85 follow-up: the skipped dots go above the reference gap. With a skip of 3 a ladder from 0.125 mm in steps of
     * 0.05 would lay its reference pair at -0.025 and -0.075 mm, possibly at contact (a wrong needle frame): refused
     * before anything moves, with the start gap that keeps the reference where it sits without a skip; 1 µm under it
     * is refused too. A ladder past -0.1 mm names itself and the most such steps; a step so large that no ladder of
     * skip + 3 dots from at most 2 mm keeps its reference at 0.125 mm and its lowest gap at -0.1 mm names the largest
     * step that does.
     */
    @Test
    public void aTouchOffWhoseReferenceSitsBelowTheReferenceGapIsRefused() throws Exception {
        String recipe = skipping(3);
        // ahead of the lowest gap's own refusal (-0.125 mm here)
        JsonObject low = TipSim.refused(t.post("/api/calibrations/touch-off", "{\"startGapMm\":0.125,\"stepMm\":0.05,"
                + "\"steps\":6}"), 400, "bad-request");
        assertEquals("the 25G straight's recipe skips the first 3 dots after the prime (touchOffSkipDots): with a "
                + "start gap of 0.125 mm its reference dots would sit at -0.025 mm and -0.075 mm, possibly at contact; "
                + "start at 0.275 mm or more, so they sit at 0.125 mm and 0.075 mm as without a skip", TipSim.str(low,
                        "reason"));
        // the web UI's form as it opens: no other check refuses it
        JsonObject form = TipSim.refused(t.post("/api/calibrations/touch-off", "{\"startGapMm\":0.125,\"stepMm\":"
                + "0.025,\"steps\":6}"), 400, "bad-request");
        assertEquals("the 25G straight's recipe skips the first 3 dots after the prime (touchOffSkipDots): with a "
                + "start gap of 0.125 mm its reference dots would sit at 0.050 mm and 0.025 mm, possibly at contact; "
                + "start at 0.200 mm or more, so they sit at 0.125 mm and 0.100 mm as without a skip", TipSim.str(form,
                        "reason"));
        JsonObject deep = TipSim.refused(t.post("/api/calibrations/touch-off", "{\"stepMm\":0.05,\"steps\":13}"), 400,
                "bad-request");
        assertEquals("the lowest gap would be -0.325 mm (13 steps of 0.050 mm from 0.275 mm), i.e. the needle 0.325 mm "
                + "into the surface if the current offset is right; keep it at -0.1 mm or above: at most 8 such steps, "
                + "or start higher", TipSim.str(deep, "reason"));
        TipSim.ok(t.call("PUT", "/api/config", recipe + "2}}"));
        JsonObject under = TipSim.refused(t.post("/api/calibrations/touch-off", "{\"startGapMm\":0.174,\"steps\":5}"),
                400, "bad-request");
        assertEquals("the 25G straight's recipe skips the first 2 dots after the prime (touchOffSkipDots): with a "
                + "start gap of 0.174 mm its reference dots would sit at 0.124 mm and 0.099 mm, possibly at contact; "
                + "start at 0.175 mm or more, so they sit at 0.125 mm and 0.100 mm as without a skip", TipSim.str(under,
                        "reason"));
        // 7 dots from 2 mm end at -0.1 mm in steps of (2 + 0.1) / 6 = 0.35 mm: the floor binds before the 2 mm cap
        TipSim.ok(t.call("PUT", "/api/config", recipe + "4}}"));
        JsonObject step = TipSim.refused(t.post("/api/calibrations/touch-off", "{\"stepMm\":0.47}"), 400,
                "bad-request");
        assertEquals("the 25G straight's recipe skips the first 4 dots after the prime (touchOffSkipDots): in steps of "
                + "0.470 mm no ladder of 7 dots from at most 2 mm keeps its reference pair at 0.125 mm or higher and "
                + "its lowest gap at -0.1 mm or above; use steps of at most 0.350 mm", TipSim.str(step, "reason"));
    }

    /**
     * BR-85 follow-up: without a start gap a ladder with skipped dots starts that many steps above 0.125 mm, so its
     * reference sits where it does without a skip, and without a number of steps it lays the most that end at -0.1 mm
     * or above in its steps; a start gap at that minimum runs, the largest step a skip allows runs, and with no skip
     * nothing changes (a start under 0.125 mm runs). The answer names the start gap and steps the server chose.
     */
    @Test
    public void theTouchOffStartsAboveTheSkippedDots() throws Exception {
        String recipe = layingWith(3);
        assertArrayEquals(new double[] {0.275, 0.225, 0.175, 0.125, 0.075, 0.025}, gaps(laid("{\"startGapMm\":0.275,"
                + "\"stepMm\":0.05,\"steps\":6,\"wait\":true}")), 1e-9, "the first reference dot at 0.125 mm");
        JsonObject chosen = laid("{\"stepMm\":0.05,\"wait\":true}");
        assertArrayEquals(new double[] {0.275, 0.225, 0.175, 0.125, 0.075, 0.025, -0.025, -0.075}, gaps(chosen), 1e-9,
                "3 steps above 0.125 mm, and 3 + 1 + 4 steps: the next would be -0.125 mm");
        assertTrue(chosen.has("startGapMm") && chosen.has("steps"), chosen.toString());
        assertEquals(0.275, chosen.get("startGapMm").getAsDouble(), 1e-12);
        assertEquals(8, chosen.get("steps").getAsInt());
        // 0.175 - 2 x 0.025 is 0.12499999999999999 in doubles: the minimum itself is accepted
        TipSim.ok(t.call("PUT", "/api/config", recipe + "2}}"));
        assertEquals(0.125, gaps(laid("{\"startGapMm\":0.175,\"steps\":5,\"wait\":true}"))[2], 1e-12);
        // the largest step the refusal names for a skip of 4 lays 7 dots from 2 mm
        TipSim.ok(t.call("PUT", "/api/config", recipe + "4}}"));
        double[] widest = gaps(laid("{\"startGapMm\":2,\"stepMm\":0.35,\"steps\":7,\"wait\":true}"));
        assertEquals(7, widest.length);
        assertEquals(0.6, widest[4], 1e-9, "the first reference dot");
        assertEquals(-0.1, widest[6], 1e-9);

        // no skip: as before
        TipSim.ok(t.call("PUT", "/api/config", recipe + "0}}"));
        double[] none = gaps(laid("{\"startGapMm\":0.1,\"wait\":true}"));
        assertEquals(0.1, none[0], 1e-12);
        assertEquals(6, none.length);
    }

    /**
     * BR-85 review (BR-89's item 1): without a number of steps a ladder that skips dots lays 8 after its reference
     * pair, down to -0.1 mm in the default steps of 0.025 mm; with a skip of 5 it was refused for too few dots. The
     * answer names the steps and the start gap the server chose.
     */
    @Test
    public void aLadderThatSkipsDotsLaysEightAfterItsReferencePair() throws Exception {
        layingWith(5);
        JsonObject res = laid("{\"wait\":true}");
        double[] g = gaps(res);
        assertEquals(15, g.length, "5 skipped, the reference pair and 8 after it");
        assertEquals(0.25, g[0], 1e-12);
        assertEquals(0.125, g[5], 1e-12, "the first reference dot");
        assertEquals(-0.1, g[14], 1e-9, "down to the lowest gap allowed");
        assertTrue(res.has("steps") && res.has("startGapMm"), res.toString());
        assertEquals(15, res.get("steps").getAsInt());
        assertEquals(0.25, res.get("startGapMm").getAsDouble(), 1e-12);
    }

    /**
     * The camera of a touch-off on the 25G (skip 3, 13 dots from 0.20 mm): 3 droplets, the reference pair and 4 steady
     * dots at 1.2 mm landing where the pair did, then squashed dots at 1.8 mm (contact between 0.000 and -0.025 mm);
     * the 2nd check dot at {@code check2} from the pair's landing, the deepest dot at {@code floor}. Spots by the order
     * the before tour first visits them.
     */
    static final class LandingCamera extends ApiServerSimTest.FakeImager {
        final java.util.List<String> spots = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile double[] check2 = {0, 0}, floor = {0, 0};

        @Override
        public Frame capture(double xMm, double yMm) {
            String k = key(xMm, yMm);
            if (!spots.contains(k)) {
                spots.add(k);
            }
            return super.capture(xMm, yMm);
        }

        @Override
        public java.util.List<org.openpnp.instancelabs.vision.Dot> inspect(Frame before, Frame after) {
            int i = spots.indexOf(key(after.xMm, after.yMm));
            double[] at = i == 12 ? floor : i == 14 ? check2 : new double[] {0, 0};
            double d = i < 3 ? 0.5 : i < 9 || i > 12 ? 1.2 : 1.8;
            return java.util.Collections.singletonList(new org.openpnp.instancelabs.vision.Dot(at[0], at[1], d,
                    Math.PI * d * d / 4, 0.9));
        }
    }

    /**
     * BR-89: every ladder ends with 2 check dots at the reference gap. The 2nd landing 0.15 mm from where the reference
     * pair landed (a tip pushed sideways or slipping in its Luer at the floor) is not applied, the frame unchanged, the
     * analysis kept; 0.05 mm is applied; a squashed dot's shift alone is information. 15 dots laid, the last two at the
     * first counted dot's Z.
     */
    @Test
    public void aTipThatMovedBeforeItsCheckDotsIsNotApplied() throws Exception {
        layingWith(3);
        LandingCamera cam = new LandingCamera();
        t.svc.setDotImager(cam);
        double before = t.svc.getConfig().needleZOffsetMm;
        String body = "{\"startGapMm\":0.2,\"stepMm\":0.025,\"steps\":13,\"apply\":true,\"wait\":true}";
        cam.check2 = new double[] {0.15, 0};
        t.sim.server.mark();
        JsonObject moved = laid(body).getAsJsonObject("result");
        JsonObject a = moved.getAsJsonObject("analysis");
        assertFalse(moved.get("applied").getAsBoolean(), moved.toString());
        assertEquals(before, t.svc.getConfig().needleZOffsetMm, 0, "the frame unchanged");
        assertEquals(0.15, a.get("landingShiftMm").getAsDouble(), 1e-6);
        assertEquals("the 2nd check dot landed 0.150 mm from where the first two counted dots landed (at most 0.1 mm): "
                + "the tip may have moved; look at it from below and run the touch-off again", TipSim.str(a,
                        "landingProblem"));
        assertEquals(-0.0125, a.get("contactGapMm").getAsDouble(), 1e-9, "the contact is still reported");
        assertTrue(TipSim.str(moved.getAsJsonObject("run"), "message").startsWith("dot squashed between 0.000 and -0.025 "
                + "mm; not applied: the 2nd check dot landed 0.150 mm"), moved.toString());
        String kept = String.valueOf(t.svc.getDb().query("SELECT method FROM needle_touchoff ORDER BY id DESC LIMIT 1")
                .get(0).get("method"));
        assertTrue(kept.startsWith("dot-ladder-touch-off (not applied: the 2nd check dot landed 0.150 mm"), kept);
        assertEquals(15, a.get("dotsLaid").getAsInt());
        assertEquals(15, cam.spots.size(), "15 spots photographed");
        java.util.List<Double> z = dotZ(sent());
        assertEquals(1 + 15, z.size(), "the prime at the waste, then 15 dots: " + z);
        assertEquals(z.get(1 + 3), z.get(1 + 13), 0.002, "the 1st check dot at the first counted dot's Z: " + z);
        assertEquals(z.get(1 + 3), z.get(1 + 14), 0.002, "and the 2nd");
        assertEquals(z.get(1 + 2) + 0.025, z.get(1 + 3), 0.002, "a step deeper than the last dot not counted");
        cam.check2 = new double[] {0.05, 0};
        cam.floor = new double[] {0.2, 0};
        JsonObject held = laid(body).getAsJsonObject("result");
        JsonObject h = held.getAsJsonObject("analysis");
        assertTrue(held.get("applied").getAsBoolean(), held.toString());
        assertEquals(0.05, h.get("landingShiftMm").getAsDouble(), 1e-6);
        assertEquals(0.2, h.get("squashedShiftMm").getAsDouble(), 1e-6, "information only");
        assertTrue(h.get("landingProblem").isJsonNull(), h.toString());
        assertEquals(before - 0.0125, t.svc.getConfig().needleZOffsetMm, 1e-9, "applied: the contact gap");
    }

    private static final java.util.regex.Pattern Z = java.util.regex.Pattern.compile("\\bZ(-?\\d+(?:\\.\\d+)?)");
    private static final java.util.regex.Pattern B = java.util.regex.Pattern.compile("\\bB(-?\\d+(?:\\.\\d+)?)");

    /** The raw Z each dot of the stream was laid at: the Z in force at its first B move. */
    static java.util.List<Double> dotZ(java.util.List<String> sent) {
        java.util.List<Double> l = new java.util.ArrayList<>();
        Double z = null;
        boolean inDot = false;
        for (String s : sent) {
            if (!s.startsWith("G0 ") && !s.startsWith("G1 ")) {
                continue;
            }
            java.util.regex.Matcher mz = Z.matcher(s), mb = B.matcher(s);
            if (mz.find()) {
                z = Double.parseDouble(mz.group(1));
                inDot = false;
            }
            if (mb.find() && !inDot && z != null) {
                l.add(z);
                inDot = true;
            }
        }
        return l;
    }

    /** The lines the SIM controller got since the mark, without comments. */
    java.util.List<String> sent() {
        java.util.List<String> l = new java.util.ArrayList<>();
        for (String s : t.sim.server.sinceMark()) {
            String c = s.contains(";") ? s.substring(0, s.indexOf(';')).trim() : s.trim();
            if (!c.isEmpty()) {
                l.add(c);
            }
        }
        return l;
    }

    @Test
    public void aTipsDoseAnchorIsSetShownAndClearedAndStaysWithTheTip() throws Exception {
        t.add25();
        String path = "/api/tip-setups/" + TipSim.K25 + "/dose-anchor";
        // a live value Calibrate uses: guarded by the machine lock, unlike the tip-setup edits (data writes)
        assertEquals("guarded", LockEndpoints.kindOf("PUT", path));
        assertEquals("data", LockEndpoints.kindOf("PUT", "/api/tip-setups/" + TipSim.K25));
        // no tip has its own: none per setup, the family's (the bench half turn on 0603) for the mounted 22G
        assertTrue(setup(TipSim.K25).get("doseAnchor").isJsonNull());
        assertTrue(setup(TipSim.SEED).get("doseAnchor").isJsonNull());
        JsonObject fam = statusAnchor();
        assertTrue(fam.get("fromFamily").getAsBoolean(), fam.toString());
        assertEquals(Anchor.BENCH.massPerPadMg, fam.get("massPerPadMg").getAsDouble(), 1e-12);
        assertEquals(Anchor.BENCH.areaMm2, fam.get("areaMm2").getAsDouble(), 1e-12);
        assertEquals(Anchor.BENCH.mgPerMm2, fam.get("mgPerMm2").getAsDouble(), 1e-12);
        assertTrue(fam.get("padType").isJsonNull());
        assertEquals(Anchor.BENCH.text, TipSim.str(fam, "source"));

        // the parked 25G's own: the area from the pad type's opening, the rate from both, the time stamped
        double area = t.typeRow("chip-0603").get("padAreaMm2").getAsDouble();
        long before = System.currentTimeMillis();
        JsonObject a = TipSim.ok(t.call("PUT", path, "{\"massPerPadMg\":1.95,\"padType\":\"chip-0603\",\"source\":"
                + "\"weighed by Process\"}")).getAsJsonObject("doseAnchor");
        assertEquals(1.95, a.get("massPerPadMg").getAsDouble(), 1e-12);
        assertEquals(area, a.get("areaMm2").getAsDouble(), 1e-12);
        assertEquals(1.95 / area, a.get("mgPerMm2").getAsDouble(), 1e-12);
        assertEquals("chip-0603", TipSim.str(a, "padType"));
        assertEquals("weighed by Process", TipSim.str(a, "source"));
        assertTrue(a.get("setAtMs").getAsLong() >= before, a.toString());
        assertEquals(a, setup(TipSim.K25).getAsJsonObject("doseAnchor"));
        assertTrue(setup(TipSim.SEED).get("doseAnchor").isJsonNull());
        assertTrue(statusAnchor().get("fromFamily").getAsBoolean(), "the 22G is mounted");

        // what is refused: a mass outside 0..20 mg, a pad type that is not one, a tip that is not one, the settings
        for (String bad : new String[] {"{\"massPerPadMg\":0,\"padType\":\"chip-0603\"}", "{\"massPerPadMg\":20.5,"
                + "\"padType\":\"chip-0603\"}", "{\"padType\":\"chip-0603\"}", "{\"massPerPadMg\":1.95}",
            "{\"massPerPadMg\":1.95,\"padType\":\"no-such-type\"}"}) {
            TipSim.refused(t.call("PUT", path, bad), 400, "bad-request");
        }
        // a typo of 19.5 for 1.95 mg: 25.7 mg per mm² of the 0603 opening, over 4 x the bench's 1.46
        JsonObject typo = TipSim.refused(t.call("PUT", path, "{\"massPerPadMg\":19.5,\"padType\":\"chip-0603\"}"),
                400, "bad-request");
        assertEquals("'massPerPadMg' 19.5 mg on the chip-0603 opening (0.76 mm²) would be 25.66 mg per mm²: at most "
                + "5.82, 4 x the bench's 1.46 (a typo?)", TipSim.str(typo, "reason"));
        // no body at all is no clearing: only {} or null clears it
        for (String empty : new String[] {null, " "}) {
            JsonObject none = TipSim.refused(t.call("PUT", path, empty), 400, "bad-request");
            assertEquals("send {massPerPadMg, padType, source?}, or {} or null to clear the anchor", TipSim.str(none,
                    "reason"));
        }
        TipSim.refused(t.call("PUT", "/api/tip-setups/nope/dose-anchor", "{\"massPerPadMg\":1,\"padType\":"
                + "\"chip-0603\"}"), 404, "not-found");
        TipSim.refused(t.call("PUT", "/api/config", "{\"doseAnchor\":{\"massPerPadMg\":1.0}}"), 400, "bad-request");
        assertEquals(a, setup(TipSim.K25).getAsJsonObject("doseAnchor"), "unchanged");

        // mounted, it is the one that applies; parked again while the 22G is on, and back with the 25G
        t.mount(TipSim.K25);
        JsonObject on = statusAnchor();
        assertFalse(on.get("fromFamily").getAsBoolean(), on.toString());
        assertEquals(1.95, on.get("massPerPadMg").getAsDouble(), 1e-12);
        assertEquals("chip-0603", TipSim.str(on, "padType"));
        t.mount(TipSim.SEED);
        assertEquals(a, setup(TipSim.K25).getAsJsonObject("doseAnchor"));
        assertTrue(statusAnchor().get("fromFamily").getAsBoolean());
        t.mount(TipSim.K25);
        assertEquals(a, setup(TipSim.K25).getAsJsonObject("doseAnchor"));

        // {} or null clears it: the family's again
        assertTrue(TipSim.ok(t.call("PUT", path, "{}")).get("doseAnchor").isJsonNull());
        assertTrue(statusAnchor().get("fromFamily").getAsBoolean());
        TipSim.ok(t.call("PUT", path, "{\"massPerPadMg\":1.95,\"padType\":\"chip-0603\"}"));
        assertFalse(statusAnchor().get("fromFamily").getAsBoolean());
        assertTrue(TipSim.ok(t.call("PUT", path, "null")).get("doseAnchor").isJsonNull());
        assertTrue(statusAnchor().get("fromFamily").getAsBoolean());
    }

    /**
     * BR-85 review: the mounted tip's anchor is refused while the engine runs (a parked tip's is no live value: it is
     * set), and a retired pad type is no anchor's.
     */
    @Test
    public void theDoseAnchorIsRefusedWhileTheEngineRunsAndForARetiredPadType() throws Exception {
        t.add25();
        String body = "{\"massPerPadMg\":1.95,\"padType\":\"chip-0603\"}";
        TipSim.ok(t.call("DELETE", "/api/pad-types/chip-0805", "{\"forSetup\":\"" + TipSim.SEED + "\"}"));
        JsonObject retired = TipSim.refused(t.call("PUT", "/api/tip-setups/" + TipSim.K25 + "/dose-anchor",
                "{\"massPerPadMg\":1.95,\"padType\":\"chip-0805\"}"), 400, "bad-request");
        assertEquals("'padType' must be the key of a pad type that is not retired (GET /api/pad-types): not "
                + "'chip-0805'", TipSim.str(retired, "reason"));
        // a long run of shots at the waste (the SIM controller dwells for real, so the run lasts)
        t.sim.server.realDwell = true;
        TipSim.Res shots = t.post("/api/calibrations/shots-run", "{\"locationName\":\"waste\",\"rotationDeg\":360,"
                + "\"count\":200}");
        assertEquals(202, shots.status, shots.toString());
        long until = System.currentTimeMillis() + 30_000;
        while (!t.dispense().get("busy").getAsBoolean() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        try {
            assertTrue(t.dispense().get("busy").getAsBoolean(), "the shots run");
            JsonObject busy = TipSim.refused(t.call("PUT", "/api/tip-setups/" + TipSim.SEED + "/dose-anchor", body),
                    409, "refused");
            assertEquals("a dispense task is already running; wait for it or press STOP", TipSim.str(busy, "reason"));
            assertTrue(setup(TipSim.SEED).get("doseAnchor").isJsonNull(), "not set");
            TipSim.ok(t.call("PUT", "/api/tip-setups/" + TipSim.K25 + "/dose-anchor", body));
        }
        finally {
            TipSim.ok(t.post("/api/stop", null));
            until = System.currentTimeMillis() + 30_000;
            while (t.dispense().get("busy").getAsBoolean() && System.currentTimeMillis() < until) {
                Thread.sleep(20);
            }
        }
        assertEquals(1.95, setup(TipSim.K25).getAsJsonObject("doseAnchor").get("massPerPadMg").getAsDouble(), 0);
    }

    /**
     * BR-85 re-review: an anchor is a pad's whole amount, so only a pad type of k 1 on an opening of at most 4 mm² can
     * carry one, as the check aims at k 1 (§1.3: centre pads and larger ones take the k 0.5 placeholder). The seeded QFN
     * centre pad (k 0.5) and a 5 mm² opening are refused; 4 mm² is not.
     */
    @Test
    public void aDoseAnchorNeedsAPadTypeOfKOneOnAtMostFourMm2() throws Exception {
        t.add25();
        String path = "/api/tip-setups/" + TipSim.K25 + "/dose-anchor";
        JsonObject centre = TipSim.refused(t.call("PUT", path, "{\"massPerPadMg\":4.0,\"padType\":\"qfn-centre\"}"),
                400, "bad-request");
        assertEquals("'padType' qfn-centre (k 0.5, 9.61 mm²) cannot carry a dose anchor: an anchor needs k 1 and an "
                + "opening of at most 4 mm²", TipSim.str(centre, "reason"));
        for (String name : new String[] {"Big", "Square"}) {
            TipSim.ok(t.post("/api/pad-types", "{\"forSetup\":\"" + TipSim.SEED + "\",\"family\":\"Custom\",\"name\":\""
                    + name + "\",\"padWMm\":" + ("Big".equals(name) ? 2.5 : 2.0) + ",\"padHMm\":2.0}"));
        }
        JsonObject big = TipSim.refused(t.call("PUT", path, "{\"massPerPadMg\":6.0,\"padType\":\"custom-big\"}"), 400,
                "bad-request");
        assertEquals("'padType' custom-big (k 1, 5 mm²) cannot carry a dose anchor: an anchor needs k 1 and an opening "
                + "of at most 4 mm²", TipSim.str(big, "reason"));
        assertTrue(setup(TipSim.K25).get("doseAnchor").isJsonNull(), "nothing set");
        JsonObject square = TipSim.ok(t.call("PUT", path, "{\"massPerPadMg\":6.0,\"padType\":\"custom-square\"}"))
                .getAsJsonObject("doseAnchor");
        assertEquals(4.0, square.get("areaMm2").getAsDouble(), 1e-12);
    }

    /**
     * BR-85 review: the family's anchor status.dispense.tip shows for a tip without its own is cached. The next status
     * finds it without a query (it answers while another thread holds the database), a pad type written refreshes it.
     */
    @Test
    public void theStatusFamilyAnchorIsCachedUntilAPadTypeIsWritten() throws Exception {
        assertTrue(statusAnchor().get("fromFamily").getAsBoolean(), "the bench half turn, found once");
        DispenseDb db = t.svc.getDb();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (db) {
                held.countDown();
                try {
                    release.await();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        holder.start();
        try {
            held.await();
            Anchor again = CompletableFuture.supplyAsync(() -> {
                try {
                    return t.svc.familyAnchor(Family.CHIP, Anchor.BENCH.areaMm2);
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).get(5, TimeUnit.SECONDS);
            assertTrue(again.bench);
        }
        finally {
            release.countDown();
            holder.join();
        }
        // a validated, weighed 0603 of the 22G: it anchors the chips from the next status on
        PadType v = db.padTypeHistory("chip-0603").get(db.padTypeHistory("chip-0603").size() - 1).copy();
        v.tipKey = TipSim.SEED;
        v.status = PadTypeStatus.VALIDATED;
        v.dotSteps = 800;
        v.dotsPerPad = 1;
        v.massPerPadMg = 1.20;
        v.origin = PadType.ORIGIN_MANUAL;
        v.doseFp = null;
        v.calAreaMm2 = null;
        db.insertPadTypeVersion(v);
        JsonObject fam = statusAnchor();
        assertTrue(fam.get("fromFamily").getAsBoolean(), fam.toString());
        assertEquals("chip-0603", TipSim.str(fam, "padType"));
        assertEquals(1.20, fam.get("massPerPadMg").getAsDouble(), 1e-12);
    }
}
