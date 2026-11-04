package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * BR-85 through the API on the SIM machine: the values that are a tip's own and follow it through a mount: the dots
 * its touch-off skips (its recipe's touchOffSkipDots), shown per setup in GET /api/tip-setups.
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
}
