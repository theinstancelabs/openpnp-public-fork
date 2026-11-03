package org.openpnp.instancelabs.api;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;

import org.openpnp.instancelabs.board.BoardArt;
import org.openpnp.instancelabs.board.BoardArtStore;
import org.openpnp.instancelabs.board.EdtRunner;
import org.openpnp.instancelabs.board.KeepOut;
import org.openpnp.instancelabs.dispense.BoardFeatures;
import org.openpnp.instancelabs.dispense.BoardRegistration;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispensePlan;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.HomeTracker;
import org.openpnp.instancelabs.dispense.ProbeTargets;
import org.openpnp.instancelabs.dispense.RegisterRequest;
import org.openpnp.instancelabs.dispense.RegistrationResult;
import org.openpnp.instancelabs.dispense.RunResult;
import org.openpnp.instancelabs.dispense.SurfacePlane;
import org.openpnp.instancelabs.dispense.SurfaceProbeRequest;
import org.openpnp.instancelabs.dispense.SurfaceProbeResult;
import org.openpnp.instancelabs.dispense.VacuumProber;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.util.Utils2D;
import org.pmw.tinylog.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Register board (fiducials, N1 vacuum probe, plan again), the automatic probe of the blank, Check Z
 * and the engine's new-seat flag (docs/instancelabs/AUTO_REGISTER_DESIGN.md §7). The plan, run and
 * status changes live in {@link DispenseEndpoints}, the home in {@link MachineEndpoints}.
 */
final class RegistrationEndpoints {
    private final ApiContext ctx;
    private final DispenseEndpoints dispense;

    RegistrationEndpoints(ApiContext ctx, DispenseEndpoints dispense) {
        this.ctx = ctx;
        this.dispense = dispense;
    }

    void register(ApiServer s) {
        s.route("POST", "/api/plan/([A-Za-z0-9-]+)/register", this::registerBoard);
        s.route("GET", "/api/plan/([A-Za-z0-9-]+)/registration-targets", this::registrationTargets);
        s.route("POST", "/api/surfaces/([A-Za-z0-9_-]{1,40})/probe", this::probeSurface);
        s.route("GET", "/api/surfaces/([A-Za-z0-9_-]{1,40})/probe-targets", this::probeTargets);
        s.route("POST", "/api/machine/z-check", this::zCheck);
        s.route("POST", "/api/board/new-seat", this::newSeat);
    }

    // ------------------------------------------------------------------------------------------
    // Register board
    // ------------------------------------------------------------------------------------------

    /**
     * POST /api/plan/{planId}/register {"wait"?, "probe"?: "auto" | "always" | "never"}: home if needed,
     * the fiducials, the probe, plan again. Obvious misconfigurations are refused (409) before anything
     * moves. A board with fewer fiducials than Register board needs is refused; one without any, with "probe":
     * "always" on a plan without a registration, is probed only (F10), where the plan puts it.
     */
    Object registerBoard(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        String probe = Json.str(b, "probe", "auto");
        RegisterRequest.ProbeMode mode;
        try {
            mode = RegisterRequest.ProbeMode.valueOf(probe.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            throw ApiException.badRequest("'probe' must be auto, always or never");
        }
        DispenseService svc = ctx.availableSvc();
        ctx.requireEngineIdle(svc);
        ctx.requireNoPressureHold();
        ctx.requireMachineIdle();
        PlanStore.Entry e = dispense.requirePlan(r.group(1));
        BoardLocation nominal = seat(e);
        DispenseConfig c = svc.getConfig();
        BoardFeatures f = BoardFeatures.of(nominal.getBoard(), svc.padTypeResolver());
        String few = DispenseService.fewFiducials(f, c.fiducialCheck.minFiducials);
        boolean probeOnly = probeOnlyBoard(f, few, e) && mode == RegisterRequest.ProbeMode.ALWAYS;
        if (few != null && !probeOnly) {
            throw ApiException.refused(few + (probeOnlyBoard(f, few, e) ? " Or probe it only, where it is planned: "
                    + "Register with probe \"always\"." : ""));
        }
        if (!svc.hasTopCamera()) {
            throw ApiException.unavailable("REG: no top camera in OpenPnP: Register board needs it. Check the board X/Y "
                    + "in the Board step and probe by hand.");
        }
        HomeTracker.Plan home = svc.homePlan();
        ApiContext.refuseUnlessOk(svc.checkBasicInterlocks(home.needed), "Register board");
        double nominalZ = nominal.getGlobalLocation().convertToUnits(LengthUnit.Millimeters).getZ();
        if (mode != RegisterRequest.ProbeMode.NEVER) {
            preflight(svc, true, "the board top Z", nominalZ);
        }
        RegisterRequest req = new RegisterRequest();
        req.plan = e.plan;
        req.nominal = nominal;
        req.boardKey = DispenseEndpoints.boardKey(e.source);
        req.nominalSeat = BoardRegistration.Nominal.of(nominal, DispenseEndpoints.isJob(e.source));
        req.planToBoard = dispense.currentBoardLocation(e).getLocalToGlobalTransform().createInverse();
        req.nominalZ = nominalZ;
        req.probe = mode;
        req.probeOnly = probeOnly;
        req.replanner = reg -> replan(svc, e, reg);
        ArtHints art = ArtHints.of(nominal.getBoard());
        req.fiducialHints = art.fiducials;
        req.keepOuts = art.keepOuts;
        Map<String, Object> preview = targets(svc, e);
        ctx.tracker.expect("board registration");
        Future<RegistrationResult> f2 = svc.registerBoard(req);
        e.registrationFuture = f2;
        Map<String, Object> accepted = Views.map("kind", "board registration", "planId", e.plan.planId, "home",
                Views.home(home), "fiducials", preview.get("fiducials"), "probeTargets", preview.get("probes"), "probe",
                probe.toLowerCase(Locale.ROOT), "probeOnly", probeOnly, "estimatedSeconds", estimatedTotal(preview));
        return ApiContext.awaitIf(Json.bool(b, "wait", false), f2, Views::registration, accepted);
    }

    /**
     * Probe only may run (F10): a board without a single fiducial (one with some is fitted by them, or refused), on a
     * plan without a registration (registration "none", or "auto" with nothing stored).
     */
    private static boolean probeOnlyBoard(BoardFeatures f, String few, PlanStore.Entry e) {
        return few != null && f.fiducials.isEmpty() && e.source.get("registrationId") == null;
    }

    /** The plan's board at its nominal seat; 409 when it is gone, bottom-up or its registration no longer applies. */
    private BoardLocation seat(PlanStore.Entry e) throws Exception {
        BoardLocation nominal;
        try {
            nominal = dispense.baseBoardLocation(e.source);
            dispense.currentBoardLocation(e); // the plan's own registration must still apply (§3.6)
        }
        catch (ApiException x) {
            throw x;
        }
        catch (Exception x) {
            throw ApiException.refused("the board of plan " + e.plan.planId + " cannot be found: "
                    + MachineEndpoints.rootMessage(x));
        }
        if (nominal.getGlobalSide() != Side.Top) {
            throw ApiException.refused("REG: the board is placed bottom-up; only the top side can be registered");
        }
        return nominal;
    }

    /**
     * The Replanner of §5.3, on the machine thread at the end of Register board: the board planned again
     * with the registration (without one after a probe only, {@code reg} null). A same seat that kept its surface
     * keeps a plan that already carries both.
     */
    private String replan(DispenseService svc, PlanStore.Entry e, BoardRegistration reg) throws Exception {
        SurfacePlane surface = svc.surfacePlane(svc.getConfig().boardSurfaceName);
        String regId = reg == null ? null : reg.id;
        if (java.util.Objects.equals(regId, e.source.get("registrationId")) && surface != null
                && surface.fingerprint().equals(e.plan.surfaceFingerprint)) {
            e.registrationRecord = reg;
            return e.plan.planId;
        }
        Map<String, Object> src = new LinkedHashMap<>(e.source);
        src.put("registrationId", regId);
        BoardLocation bl = dispense.baseBoardLocation(src);
        DispensePlan plan = svc.planBoard(bl, reg);
        dispense.artRef(plan, bl);
        PlanStore.Entry next = new PlanStore.Entry(plan, src);
        next.registrationRecord = reg;
        next.registrationFuture = e.registrationFuture;
        dispense.estimate(svc, next);
        next.registrationTargets = targets(svc, next);
        ctx.plans.put(next);
        return plan.planId;
    }

    /** GET /api/plan/{planId}/registration-targets: the preview of §7.1, computed now. */
    Object registrationTargets(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.availableSvc();
        PlanStore.Entry e = dispense.requirePlan(r.group(1));
        Map<String, Object> t = targets(svc, e);
        e.registrationTargets = t;
        return t;
    }

    /**
     * What Register board would do with this plan (§7.1): where each fiducial is expected, the probe
     * targets with their alternates (through the plan's current transform), the probe window and the
     * frame check, the home plan and the time. Problems are reported in {@code error} / {@code frame}.
     */
    Map<String, Object> targets(DispenseService svc, PlanStore.Entry e) {
        Map<String, Object> out = new LinkedHashMap<>();
        DispenseConfig c = svc.getConfig();
        HomeTracker.Plan home = svc.homePlan();
        double homeS = home.needed ? home.estimateS : 0;
        List<Map<String, Object>> fids = new ArrayList<>();
        List<Map<String, Object>> probes = new ArrayList<>();
        out.put("fiducials", fids);
        out.put("probes", probes);
        out.put("probeOnly", false);
        out.put("home", Views.home(home));
        try {
            BoardLocation bl = dispense.currentBoardLocation(e);
            BoardLocation nominal = dispense.baseBoardLocation(e.source);
            ArtHints art = ArtHints.of(bl.getBoard());
            BoardFeatures f = BoardFeatures.of(bl.getBoard(), svc.padTypeResolver(), art.fiducials);
            out.put("fiducialCount", f.fiducials.size());
            for (BoardFeatures.Fiducial fd : f.fiducials) {
                Location l = Utils2D.calculateBoardPlacementLocation(bl, new Location(LengthUnit.Millimeters, fd.x, fd.y,
                        0, 0)).convertToUnits(LengthUnit.Millimeters);
                fids.add(Views.map("name", fd.name, "bx", fd.x, "by", fd.y, "x", l.getX(), "y", l.getY(),
                        "searchRadiusMm", c.fiducialCheck.searchRadiusMm));
            }
            double nominalZ = nominal.getGlobalLocation().convertToUnits(LengthUnit.Millimeters).getZ();
            DispenseConfig.NeedleAnchor a = c.needleAnchor;
            Map<String, Object> frame = Views.map("anchorZ", a == null ? null : a.surfaceZMm, "anchorAtMs",
                    a == null ? null : a.setAtMs, "anchorX", a == null ? null : a.xMm, "anchorY",
                    a == null ? null : a.yMm, "ok", true, "message", null);
            out.put("frame", frame);
            try {
                out.put("window", Views.window(svc.probePreflight(true, "the board top Z", nominalZ)));
            }
            catch (DispenseService.RunRefusedException x) {
                frame.put("ok", false);
                frame.put("message", x.getMessage());
            }
            String few = DispenseService.fewFiducials(f, c.fiducialCheck.minFiducials);
            AffineTransform boardToMachine = bl.getLocalToGlobalTransform();
            List<VacuumProber.Target> board = ProbeTargets.forBoard(f, ProbeTargets.dotsBoard(e.plan,
                    boardToMachine.createInverse()), c, art.keepOuts);
            for (VacuumProber.Target t : ProbeTargets.map(board, boardToMachine)) {
                List<Map<String, Object>> alts = new ArrayList<>();
                for (int k = 0; k < t.alternates.size(); k++) {
                    alts.add(Views.map("bx", t.alternatesBoard.get(k)[0], "by", t.alternatesBoard.get(k)[1], "x",
                            t.alternates.get(k)[0], "y", t.alternates.get(k)[1]));
                }
                probes.add(Views.map("n", t.n, "bx", t.bx, "by", t.by, "x", t.x, "y", t.y, "alternates", alts));
            }
            out.put("error", few);
            // no fiducials, on a plan without a registration: Register with probe "always" probes it only (F10)
            out.put("probeOnly", probeOnlyBoard(f, few, e));
        }
        catch (Exception x) {
            out.put("error", MachineEndpoints.rootMessage(x));
        }
        double fidS = fids.size() * DispenseService.FIDUCIAL_ESTIMATE_S;
        double probeS = probes.size() * DispenseService.PROBE_POINT_ESTIMATE_S;
        out.put("estimatedSeconds", Views.map("home", homeS, "fiducials", fidS, "sameSeat",
                3 * DispenseService.PROBE_POINT_ESTIMATE_S, "probe", probeS, "total", homeS + fidS + probeS));
        return out;
    }

    /**
     * What the board's KiCad drawing tells Register board (BOARD_IMPORT_DESIGN.md §10.2 item 4, AR §16 item 10): the
     * fiducials' copper and mask diameters, and the circles N1 must not touch (every hole and via, the outline and
     * the cut-outs). Both null without a KiCad drawing, or when it cannot be read: the sizes then come from the
     * package and the probe assumes a mounting hole in each corner (AR §4.6).
     */
    static final class ArtHints {
        /** Fiducial name -> {copper, mask} diameter (mm). */
        Map<String, double[]> fiducials;
        List<ProbeTargets.KeepOut> keepOuts;

        /** Read where OpenPnP's lists are read (the art store never reads the configuration itself). */
        static ArtHints of(Board board) {
            ArtHints h = new ArtHints();
            if (board == null) {
                return h;
            }
            try {
                EdtRunner.call(() -> {
                    BoardArtStore store = BoardArtStore.get();
                    if (!store.hasKicadArt(board)) {
                        return null;
                    }
                    Map<String, double[]> fids = new HashMap<>();
                    for (BoardArt.FidArt fd : store.fiducials(board)) {
                        if (fd.name != null && fd.copperMm > 0) {
                            fids.put(fd.name, new double[] {fd.copperMm, fd.maskMm});
                        }
                    }
                    List<ProbeTargets.KeepOut> ko = new ArrayList<>();
                    for (KeepOut k : store.keepOuts(board)) {
                        ko.add(new ProbeTargets.KeepOut(k.x, k.y, k.r));
                    }
                    h.fiducials = fids;
                    h.keepOuts = ko;
                    return null;
                }, DispenseEndpoints.EDT_START_MS);
            }
            catch (Exception x) {
                Logger.warn(x, "the drawing of board {} cannot be read: corner mounting holes assumed",
                        board.getName());
                h.fiducials = null;
                h.keepOuts = null;
            }
            return h;
        }
    }

    private static Object estimatedTotal(Map<String, Object> preview) {
        Object s = preview.get("estimatedSeconds");
        return s instanceof Map ? ((Map<?, ?>) s).get("total") : null;
    }

    // ------------------------------------------------------------------------------------------
    // Probe the blank
    // ------------------------------------------------------------------------------------------

    /**
     * POST /api/surfaces/{name}/probe {"wait"?, "targets"?: [[x, y], ...] (3..20, N1 tip machine mm),
     * "z"?}: the automatic N1 probe of a needle-frame surface (the blank). The board surface is
     * Register board's.
     */
    Object probeSurface(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        String name = r.group(1);
        DispenseService svc = ctx.availableSvc();
        ctx.requireEngineIdle(svc);
        ctx.requireNoPressureHold();
        ctx.requireMachineIdle();
        DispenseConfig c = svc.getConfig();
        double nominalZ = surfaceZ(c, name, Json.num(b, "z", null));
        List<double[]> explicit = targetsOf(b);
        HomeTracker.Plan home = svc.homePlan();
        ApiContext.refuseUnlessOk(svc.checkBasicInterlocks(home.needed), "Probe the " + name);
        VacuumProber.Window w = preflight(svc, false, "the '" + name + "' place Z", nominalZ);
        List<VacuumProber.Target> targets;
        try {
            targets = explicit == null ? ProbeTargets.forRow(c, name)
                    : ProbeTargets.explicit(explicit, c.vacuumProbe.alternates);
        }
        catch (DispenseService.RunRefusedException e) {
            throw ApiException.refused(e.getMessage());
        }
        SurfaceProbeRequest req = new SurfaceProbeRequest();
        req.surfaceName = name;
        req.nominalZ = nominalZ;
        req.targets = explicit;
        ctx.tracker.expect("blank probe");
        Future<SurfaceProbeResult> f = svc.probeSurface(req);
        Map<String, Object> accepted = Views.map("kind", "blank probe", "surface", name, "targets", targetList(targets),
                "window", Views.window(w), "home", Views.home(home), "estimatedSeconds", (home.needed ? home.estimateS
                        : 0) + targets.size() * DispenseService.PROBE_POINT_ESTIMATE_S);
        return ApiContext.awaitIf(Json.bool(b, "wait", false), f, res -> {
            Map<String, Object> m = Views.surfaceProbe(res);
            DispenseConfig.SurfaceMap sm = svc.getConfig().surface(name);
            m.put("surface", res.saved && sm != null ? Views.surface(name, sm) : null);
            return m;
        }, accepted);
    }

    /** GET /api/surfaces/{name}/probe-targets: the targets, window and coverage the probe would use. */
    Object probeTargets(ApiServer.Request r) throws Exception {
        String name = r.group(1);
        DispenseService svc = ctx.availableSvc();
        DispenseConfig c = svc.getConfig();
        HomeTracker.Plan home = svc.homePlan();
        Map<String, Object> out = Views.map("targets", new ArrayList<>(), "window", null, "covers", null, "home",
                Views.home(home), "estimatedSeconds", null, "error", null);
        try {
            String z = r.query("z", null);
            double nominalZ = surfaceZ(c, name, z == null ? null : Double.valueOf(z));
            List<VacuumProber.Target> targets = ProbeTargets.forRow(c, name);
            out.put("targets", targetList(targets));
            DispenseConfig.PurgeRow row = c.purgeRow;
            int spots = row.isDefined() && name.equals(row.surfaceName) ? row.spots : 0;
            // the purge spots within the probe's coverage margin of the targets' hull, as the probe checks them
            List<DispenseConfig.SurfacePoint> hull = new ArrayList<>();
            for (VacuumProber.Target t : targets) {
                hull.add(new DispenseConfig.SurfacePoint(t.x, t.y, 0));
            }
            SurfacePlane flat = SurfacePlane.fit(hull);
            int covered = 0;
            for (int i = 0; i < spots; i++) {
                double[] xy = row.spot(i);
                covered += flat.distanceOutside(xy[0], xy[1]) <= c.surfaceMarginMm - 0.5 + 1e-9 ? 1 : 0;
            }
            out.put("covers", Views.map("spots", spots, "spotsCovered", covered, "places",
                    c.location(name) == null ? new ArrayList<>() : java.util.Collections.singletonList(name)));
            out.put("estimatedSeconds", (home.needed ? home.estimateS : 0)
                    + targets.size() * DispenseService.PROBE_POINT_ESTIMATE_S);
            out.put("window", Views.window(svc.probePreflight(false, "the '" + name + "' place Z", nominalZ)));
        }
        catch (ApiException | DispenseService.RunRefusedException | IllegalArgumentException e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** The nominal N1 contact Z of a surface: the request's, else its place's; the board's is refused. */
    private static double surfaceZ(DispenseConfig c, String name, Double z) throws ApiException {
        if (name.equals(c.boardSurfaceName)) {
            throw ApiException.refused("PROBE: the '" + name + "' surface is probed by Register board (Run › Register)");
        }
        DispenseConfig.NamedLocation place = c.location(name);
        if (z == null && place == null) {
            throw ApiException.refused("PROBE: set the '" + name + "' place first: its Z (the N1 contact height) sets "
                    + "the probe window");
        }
        return z != null ? z : place.z;
    }

    /** The request's explicit targets (null when absent): 3..20 [x, y] pairs of finite numbers. */
    private static List<double[]> targetsOf(JsonObject b) throws ApiException {
        if (!b.has("targets") || b.get("targets").isJsonNull()) {
            return null;
        }
        String bad = "'targets' must be 3 to 20 [x, y] pairs of numbers (N1 tip, machine mm)";
        if (!b.get("targets").isJsonArray()) {
            throw ApiException.badRequest(bad);
        }
        JsonArray a = b.getAsJsonArray("targets");
        if (a.size() < 3 || a.size() > 20) {
            throw ApiException.badRequest(bad);
        }
        List<double[]> l = new ArrayList<>();
        for (JsonElement e : a) {
            try {
                JsonArray p = e.getAsJsonArray();
                double x = p.get(0).getAsDouble(), y = p.get(1).getAsDouble();
                if (p.size() != 2 || !Double.isFinite(x) || !Double.isFinite(y)) {
                    throw ApiException.badRequest(bad);
                }
                l.add(new double[] {x, y});
            }
            catch (RuntimeException x) {
                throw ApiException.badRequest(bad);
            }
        }
        return l;
    }

    private static List<Map<String, Object>> targetList(List<VacuumProber.Target> targets) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (VacuumProber.Target t : targets) {
            List<Map<String, Object>> alts = new ArrayList<>();
            for (double[] a : t.alternates) {
                alts.add(Views.map("x", a[0], "y", a[1]));
            }
            l.add(Views.map("n", t.n, "x", t.x, "y", t.y, "alternates", alts));
        }
        return l;
    }

    // ------------------------------------------------------------------------------------------
    // Check Z, new seat
    // ------------------------------------------------------------------------------------------

    /** POST /api/machine/z-check {"wait"?}: N1 touches stored points after a home, one per Z frame (§5.8). */
    Object zCheck(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        ctx.requireEngineIdle(svc);
        ctx.requireNoPressureHold();
        ctx.requireMachineIdle();
        DispenseService.ZCheckPlan zp = svc.zCheckPlan(null, false);
        ctx.tracker.expect("z check");
        Future<RunResult> f = svc.checkZ();
        return ApiContext.awaitIf(Json.bool(b, "wait", false), f, rr -> Views.run(rr, false), Views.map("kind",
                "z check", "needed", zp.needed, "reference", zp.reference == null ? null : Views.map("name",
                        zp.reference.name, "x", zp.reference.x, "y", zp.reference.y, "z", zp.reference.z),
                "touches", zp.touches, "estimatedSeconds", zp.touches * DispenseService.PROBE_POINT_ESTIMATE_S));
    }

    /** POST /api/board/new-seat {"newSeat": true | false}: the engine's new-seat flag (§3.6). */
    Object newSeat(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        if (svc.isBusy()) {
            throw ApiException.refused("the new-seat answer cannot change while a dispense task runs");
        }
        if (!b.has("newSeat") || !b.get("newSeat").isJsonPrimitive() || !b.get("newSeat").getAsJsonPrimitive()
                .isBoolean()) {
            throw ApiException.badRequest("'newSeat' must be true or false");
        }
        return Views.map("newSeatAtMs", svc.setNewSeat(b.get("newSeat").getAsBoolean()));
    }

    /** The probe checks of §4.4 before anything moves, as a 409. */
    private static VacuumProber.Window preflight(DispenseService svc, boolean board, String what, double nominalZ)
            throws Exception {
        try {
            return svc.probePreflight(board, what, nominalZ);
        }
        catch (DispenseService.RunRefusedException e) {
            throw ApiException.refused(e.getMessage());
        }
    }
}
