package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Anchor;

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
        // a ladder with no reference pair after the skipped dots is refused before anything moves
        JsonObject few = TipSim.refused(t.post("/api/calibrations/touch-off", "{\"steps\":3}"), 400, "bad-request");
        assertEquals("the 25G straight's recipe skips the first 2 dots after the prime (touchOffSkipDots) and the "
                + "reference takes the next two: lay at least 4 dots", TipSim.str(few, "reason"));
        // parked with the 25G's recipe while the 22G is on
        t.mount(TipSim.SEED);
        assertEquals(2, skipOf(TipSim.K25));
        assertEquals(0, skipOf(TipSim.SEED));
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
}
