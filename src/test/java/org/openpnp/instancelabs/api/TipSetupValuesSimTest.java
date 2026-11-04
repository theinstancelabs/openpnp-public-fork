package org.openpnp.instancelabs.api;

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
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.PadTypeStatus;
import org.openpnp.instancelabs.padprofile.Anchor;
import org.openpnp.instancelabs.padprofile.Family;

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
        // parked with the 25G's recipe while the 22G is on
        t.mount(TipSim.SEED);
        assertEquals(2, skipOf(TipSim.K25));
        assertEquals(0, skipOf(TipSim.SEED));
        JsonObject two = TipSim.refused(t.post("/api/calibrations/touch-off", "{\"steps\":2}"), 400, "bad-request");
        assertEquals("the reference takes the first two dots after the prime and a dot after them shows the contact: "
                + "lay at least 3 dots", TipSim.str(two, "reason"));
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
