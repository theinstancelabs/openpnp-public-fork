package org.openpnp.instancelabs.api;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.HeightSwitch;
import org.openpnp.instancelabs.dispense.NeedleEndMeasure;

import com.google.gson.JsonObject;

/**
 * BR-94: the bed's height switch on PA5. N1 and the needle press it through the engine ({@link HeightSwitch}); each
 * accepted measure is that tool's trip in this home, and K_switch is their sum. The needle's end from below
 * ({@link NeedleEndMeasure}) aims it. Every route is the lock holder's.
 */
final class HeightSwitchEndpoints {
    private final ApiContext ctx;

    HeightSwitchEndpoints(ApiContext ctx) {
        this.ctx = ctx;
    }

    void register(ApiServer s) {
        s.route("POST", "/api/height-switch/measure", this::measure);
        s.route("POST", "/api/height-switch/read", r -> read());
        s.route("POST", "/api/needle/end/measure", r -> measureEnd());
    }

    /**
     * POST /api/height-switch/measure {tool: "n1" | "needle", presses? (1..20, 5), zStart?, zFloor? (raw Z)}: waits for
     * the measure and answers its result; a refused, stopped or failed one answers 409 with its reason, its code (the
     * needle's end from below: needle-end-not-found, needle-end-paste), next and photoId. A tipClean field is ignored:
     * the engine checks the tip itself.
     */
    Object measure(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        String tool = Json.str(b, "tool", null);
        int presses = Json.integer(b, "presses", 5);
        Double zStart = Json.num(b, "zStart", null), zFloor = Json.num(b, "zFloor", null);
        DispenseService svc = ctx.availableSvc();
        ctx.requireEngineIdle(svc);
        ctx.requireNoPressureHold();
        ctx.requireMachineIdle();
        ctx.requirePositionKnown(svc);
        ctx.tracker.expect("height switch");
        HeightSwitch.Result res = svc.measureHeightSwitch(tool, presses, zStart, zFloor).get(ApiContext.MAX_WAIT_MS,
                TimeUnit.MILLISECONDS);
        if (res.refused) {
            throw refusal(res.code, res.why, res.next, res.photoId).with("result", res);
        }
        return res;
    }

    /**
     * POST /api/needle/end/measure: the needle's end from below and K by parallax ({@link NeedleEndMeasure}), recorded
     * when every check passes ({@code dispense.needleEnd}); 409 with the reason, its code, next and photoId otherwise.
     */
    Object measureEnd() throws Exception {
        DispenseService svc = ctx.availableSvc();
        ctx.requireEngineIdle(svc);
        ctx.requireNoPressureHold();
        ctx.requireMachineIdle();
        ctx.requirePositionKnown(svc);
        ctx.tracker.expect("needle end");
        NeedleEndMeasure.Result res = svc.measureNeedleEnd().get(ApiContext.MAX_WAIT_MS, TimeUnit.MILLISECONDS);
        if (!res.ok) {
            throw refusal(res.code, res.why, res.next, res.photoId).with("result", res);
        }
        return res;
    }

    /** 409 with the stable code (refused when the task gave none), what it offers and its picture. */
    private static ApiException refusal(String code, String why, String next, Long photoId) {
        return new ApiException(409, code == null ? "refused" : code, why).with("next", next).with("photoId", photoId);
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
        // a sum of raw Z heights holds for the home and the place both were measured under only, while it is this home
        // and the position is known (a reset or a lost link voids that home before the next one)
        Long home = svc.getHomeTracker().getLastHomeAtMs();
        String who = !HeightSwitch.counts(c, n1, home) ? "N1" : !HeightSwitch.counts(c, needle, home) ? "the needle"
                : null;
        String why = svc.positionUnknownWhy();
        if (why == null && who != null) {
            why = who + " has no accepted trip on the height switch in this home since its place was set";
        }
        HeightSwitch.Read last = svc.getLastHeightSwitchRead();
        HeightSwitch.Window w = svc.needleWindow();
        return Views.map("place", c.location(HeightSwitch.PLACE), "n1", n1, "needle", needle, "kSwitch",
                why == null ? Double.valueOf(n1.tripRawZ + needle.tripRawZ) : null, "kSwitchWhy", why, "lastRead",
                last == null ? null : lastRead(last), "needleWindow", w.why != null ? null : Views.map("zStart",
                        w.zStart, "zFloor", w.zFloor, "expectedRawZ", w.expectedRawZ, "from", w.from),
                "needleWindowWhy", w.why);
    }

    private static Map<String, Object> lastRead(HeightSwitch.Read r) {
        return Views.map("level", r.level, "atMs", r.atMs);
    }
}
