package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openpnp.instancelabs.dispense.Balance;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.sim.SimBalance;
import org.openpnp.instancelabs.sim.SimGuard;
import org.openpnp.instancelabs.sim.SimPaste;

import com.google.gson.JsonObject;

/**
 * The simulator's balance and paste (PROFILE_CALIBRATION_DESIGN.md §12.4), for the operator of a SIM test:
 * load a coupon on the SIM balance and read it, see and wipe the SIM paste. Refused unless the machine
 * is confirmed as OpenPnP's simulator ({@link SimGuard}).
 */
final class SimEndpoints {
    private final ApiContext ctx;

    SimEndpoints(ApiContext ctx) {
        this.ctx = ctx;
    }

    void register(ApiServer s) {
        s.route("GET", "/api/sim/balance", r -> balance());
        s.route("POST", "/api/sim/balance/load", this::load);
        s.route("GET", "/api/sim/paste/deposits", this::deposits);
        s.route("POST", "/api/sim/paste/wipe", this::wipe);
    }

    /** 409 unless the controller of this connection is OpenPnP's simulator. */
    private void requireSimulator() throws ApiException {
        DispenseService svc = ctx.svcOrNull();
        if (!(svc != null ? svc.isSimulatorConfirmed() : SimGuard.isSimulated(ctx.machine()))) {
            throw ApiException.refused("the simulator is not confirmed on this connection");
        }
    }

    private SimBalance simBalance() throws ApiException {
        requireSimulator();
        SimBalance b = SimBalance.forConfiguration(ctx.configuration);
        if (b == null) {
            throw ApiException.refused("the sim scene has no balance (il_sim_scene.py --balance)");
        }
        return b;
    }

    private SimPaste.Store store() throws ApiException {
        requireSimulator();
        if (!SimGuard.sceneFile(ctx.configuration).isFile()) {
            throw ApiException.refused("no sim scene: the simulator has no paste");
        }
        return SimPaste.Store.forFile(SimGuard.sceneFile(ctx.configuration));
    }

    /** GET /api/sim/balance: {mg, display: "4.172 g", stable, loaded}. */
    Object balance() throws Exception {
        SimBalance b = simBalance();
        Balance.Reading r = b.display();
        return Views.map("mg", r.mg, "display", String.format(Locale.ROOT, "%.3f g", r.mg / 1000), "stable", r.stable,
                "loaded", b.loaded(), "mode", b.mode(), "resolutionMg", b.resolutionMg());
    }

    /** POST /api/sim/balance/load {place}: that coupon on the pan (null: the pan empty). */
    Object load(ApiServer.Request r) throws Exception {
        JsonObject o = r.json();
        SimBalance b = simBalance();
        try {
            b.load(Json.str(o, "place", null));
        }
        catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        return balance();
    }

    /** GET /api/sim/paste/deposits?region=: [{x, y, mg, dMm, region, place, thread}]. */
    Object deposits(ApiServer.Request r) throws Exception {
        List<Map<String, Object>> l = new ArrayList<>();
        for (SimPaste.Deposit d : store().deposits(r.query("region", null))) {
            l.add(Views.map("x", d.x, "y", d.y, "mg", d.mg, "dMm", d.diameterMm(), "region", d.region.name()
                    .toLowerCase(Locale.ROOT), "place", d.place, "thread", d.thread, "string", d.string));
        }
        return l;
    }

    /** POST /api/sim/paste/wipe {region}: the paste of a region or coupon place wiped off. */
    Object wipe(ApiServer.Request r) throws Exception {
        JsonObject o = r.json();
        String region = Json.str(o, "region", null);
        if (region == null) {
            throw ApiException.badRequest("'region': blank, board, coupon, bed, a coupon's place, or all");
        }
        return Views.map("wiped", store().wipe(region));
    }
}
