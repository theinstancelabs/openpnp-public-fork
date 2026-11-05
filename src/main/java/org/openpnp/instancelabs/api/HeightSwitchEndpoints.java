package org.openpnp.instancelabs.api;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.HeightSwitch;

import com.google.gson.JsonObject;

/**
 * BR-94: the bed's height switch on PA5. N1 and the needle press it through the engine ({@link HeightSwitch}); each
 * accepted measure is that tool's trip in this home, and K_switch is their sum. Both routes are the lock holder's.
 */
final class HeightSwitchEndpoints {
    private final ApiContext ctx;

    HeightSwitchEndpoints(ApiContext ctx) {
        this.ctx = ctx;
    }

    void register(ApiServer s) {
        s.route("POST", "/api/height-switch/measure", this::measure);
        s.route("POST", "/api/height-switch/read", r -> read());
    }

    /**
     * POST /api/height-switch/measure {tool: "n1" | "needle", presses? (1..20, 5), zStart?, zFloor? (raw Z),
     * tipClean?}: waits for the measure and answers its result; a refused, stopped or failed one answers 409 with its
     * reason.
     */
    Object measure(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        String tool = Json.str(b, "tool", null);
        int presses = Json.integer(b, "presses", 5);
        Double zStart = Json.num(b, "zStart", null), zFloor = Json.num(b, "zFloor", null);
        boolean tipClean = Json.bool(b, "tipClean", false);
        DispenseService svc = ctx.availableSvc();
        ctx.requireEngineIdle(svc);
        ctx.requireNoPressureHold();
        ctx.requireMachineIdle();
        ctx.requirePositionKnown(svc);
        ctx.tracker.expect("height switch");
        HeightSwitch.Result res = svc.measureHeightSwitch(tool, presses, zStart, zFloor, tipClean).get(
                ApiContext.MAX_WAIT_MS, TimeUnit.MILLISECONDS);
        if (res.refused) {
            throw ApiException.refused(res.why).with("result", res);
        }
        return res;
    }

    /** POST /api/height-switch/read: the pin's level now, with the machine idle ({level, atMs}). */
    Object read() throws Exception {
        DispenseService svc = ctx.availableSvc();
        ctx.requireEngineIdle(svc);
        ctx.requireNoPressureHold();
        ctx.requireMachineIdle();
        ctx.tracker.expect("height switch read");
        HeightSwitch.Read res = svc.readHeightSwitch().get(ApiContext.MAX_WAIT_MS, TimeUnit.MILLISECONDS);
        if (res.level == null) {
            throw ApiException.refused(res.why);
        }
        return lastRead(res);
    }

    /** {@code dispense.heightSwitch} of the status, from the settings and memory only: it never sends G-code. */
    static Map<String, Object> status(DispenseService svc, DispenseConfig c) {
        DispenseConfig.HeightSwitchTrip n1 = c.heightSwitch.n1, needle = HeightSwitch.needleTrip(c);
        // a sum of raw Z heights holds for the home both were measured under only, while it is this home
        Long home = svc.getHomeTracker().getLastHomeAtMs();
        String who = !thisHome(n1, home) ? "N1" : !thisHome(needle, home) ? "the needle" : null;
        HeightSwitch.Read last = svc.getLastHeightSwitchRead();
        return Views.map("place", c.location(HeightSwitch.PLACE), "n1", n1, "needle", needle, "kSwitch",
                who == null ? Double.valueOf(n1.tripRawZ + needle.tripRawZ) : null, "kSwitchWhy",
                who == null ? null : who + " has no accepted trip on the height switch in this home", "lastRead",
                last == null ? null : lastRead(last));
    }

    private static boolean thisHome(DispenseConfig.HeightSwitchTrip t, Long home) {
        return t != null && home != null && home.equals(t.homeAtMs);
    }

    private static Map<String, Object> lastRead(HeightSwitch.Read r) {
        return Views.map("level", r.level, "atMs", r.atMs);
    }
}
