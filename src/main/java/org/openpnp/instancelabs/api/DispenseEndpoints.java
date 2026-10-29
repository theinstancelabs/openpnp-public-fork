package org.openpnp.instancelabs.api;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import org.openpnp.Main;
import org.openpnp.gui.MainFrame;
import org.openpnp.instancelabs.board.BoardArtStore;
import org.openpnp.instancelabs.board.EdtRunner;
import org.openpnp.instancelabs.dispense.BoardRegistration;
import org.openpnp.instancelabs.dispense.Calibration;
import org.openpnp.instancelabs.dispense.ControllerCommands;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispensePlan;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DoseBlocks;
import org.openpnp.instancelabs.dispense.DotLadder;
import org.openpnp.instancelabs.dispense.GcodeProgram;
import org.openpnp.instancelabs.dispense.GcodeStreamBuilder;
import org.openpnp.instancelabs.dispense.HomeTracker;
import org.openpnp.instancelabs.dispense.InterlockReport;
import org.openpnp.instancelabs.dispense.Interlocks;
import org.openpnp.instancelabs.dispense.OpenPnpRawMapper;
import org.openpnp.instancelabs.dispense.PadCleanReport;
import org.openpnp.instancelabs.dispense.RunResult;
import org.openpnp.instancelabs.sim.SimGuard;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Footprint;
import org.openpnp.model.Job;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.spi.Machine;
import org.pmw.tinylog.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Status, manual pressure hold, prime, test dot, named places, needle, engine settings, board plan
 * and run, run history.
 */
final class DispenseEndpoints {
    /** Named location the test dot is dispensed at: waste + the configured test-dot offset. */
    static final String TEST_DOT_LOCATION = "waste-test-dot";
    /** Settings that only the needle endpoints may change (they record a needle_touchoff row). */
    static final java.util.Set<String> PROTECTED_SETTINGS = new java.util.HashSet<>(java.util.Arrays.asList(
            "needleZOffsetMm", "needleZOffsetSetAtMs", "needleChangedAtMs", "needleAnchor", "surfaces",
            "schemaVersion", "boardRegistrations", "newSeatAtMs"));
    /** Protected settings that Register board and POST /api/board/new-seat stamp (not the needle's). */
    static final java.util.Set<String> REGISTRATION_SETTINGS = new java.util.HashSet<>(java.util.Arrays.asList(
            "boardRegistrations", "newSeatAtMs"));
    /** Nested settings PUT /api/config merges one level deep (a partial object keeps the other fields). */
    static final java.util.Set<String> NESTED_SETTINGS = new java.util.HashSet<>(java.util.Arrays.asList(
            "profile", "purgeRow", "cleanPadCheck", "needleXyTrim", "fiducialCheck", "vacuumProbe", "tip", "paste",
            "calibrationArea", "padCalibration"));
    /**
     * Nested fields only the engine or their own endpoints change: the start angle's set time (I11:
     * POST /api/screw/start-angle, or a changed angle), the times of the trim and the purge reset,
     * and the used purge spots (runs reserve them, POST /api/purge-row/reset frees them: a stale
     * full-settings save must not put paste-covered spots back in use); likewise the used calibration
     * area and its wipe (POST /api/calibration-area/reset), and when the paste was loaded (POST /api/paste).
     */
    static final java.util.Set<String> PROTECTED_NESTED = new java.util.HashSet<>(java.util.Arrays.asList(
            "profile.startAngleSetAtMs", "needleXyTrim.setAtMs", "purgeRow.resetAtMs", "purgeRow.usedSpots",
            "calibrationArea.usedMm", "calibrationArea.resetAtMs", "paste.loadedAtMs"));

    /** How long OpenPnP's thread may take to start a read of its board list (BOARD_IMPORT_DESIGN.md §3.6). */
    static final long EDT_START_MS = 10_000;

    private final ApiContext ctx;
    private final MachineEndpoints machine;
    /** Register board, the surface probe, Check Z and the new-seat flag (and the plan's registration preview). */
    final RegistrationEndpoints registration;

    DispenseEndpoints(ApiContext ctx, MachineEndpoints machine) {
        this.ctx = ctx;
        this.machine = machine;
        this.registration = new RegistrationEndpoints(ctx, this);
    }

    void register(ApiServer s) {
        s.route("GET", "/api/status", r -> status(s));
        s.route("POST", "/api/pressure/start", r -> pressureStart());
        s.route("POST", "/api/pressure/stop", r -> ctx.pressure.stop("Pressure off pressed"));
        s.route("POST", "/api/prime", this::prime);
        s.route("POST", "/api/test-dot", this::testDot);
        s.route("GET", "/api/locations", r -> ctx.svc().getConfig().locations);
        s.route("PUT", "/api/locations/([A-Za-z0-9_-]{1,40})", this::setLocation);
        s.route("POST", "/api/needle/changed", r -> needleChanged());
        s.route("POST", "/api/needle/offset", this::needleOffset);
        s.route("POST", "/api/needle/anchor", this::needleAnchor);
        s.route("GET", "/api/surfaces", r -> surfaces());
        s.route("PUT", "/api/surfaces/([A-Za-z0-9_-]{1,40})", this::putSurface);
        s.route("DELETE", "/api/surfaces/([A-Za-z0-9_-]{1,40})", this::deleteSurface);
        s.route("GET", "/api/config", r -> ctx.svc().getConfig());
        s.route("PUT", "/api/config", this::putConfig);
        s.route("GET", "/api/boards", r -> boards());
        s.route("GET", "/api/packages", r -> packages());
        s.route("POST", "/api/plan", this::plan);
        s.route("GET", "/api/plans", r -> plans());
        s.route("GET", "/api/plan/([A-Za-z0-9-]+)", this::getPlan);
        s.route("POST", "/api/plan/([A-Za-z0-9-]+)/clean-check", this::cleanCheck);
        s.route("POST", "/api/screw/start-angle", this::startAngle);
        s.route("POST", "/api/purge-row/reset", r -> purgeReset());
        s.route("POST", "/api/run", this::run);
        s.route("GET", "/api/runs", r -> ctx.svc().getDb().recentRuns(r.queryInt("limit", 20, 1, 500)));
        s.route("GET", "/api/runs/last", r -> lastRun());
        s.route("GET", "/api/events", r -> ctx.svc().getDb().recentEvents(r.queryInt("limit", 50, 1, 1000)));
        s.route("GET", "/api", r -> Views.map("routes", s.describeRoutes()));
    }

    // ------------------------------------------------------------------------------------------
    // Status (polled by the UI)
    // ------------------------------------------------------------------------------------------

    Object status(ApiServer s) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        Machine mach = ctx.configuration.getMachine();
        DispenseService svc = ctx.svcOrNull();
        out.put("server", Views.map("version", Main.getVersion(), "timeMs", System.currentTimeMillis(), "port",
                s.getPort(), "lanAccess", s.getSettings().lanAccess, "simulated", SimGuard.isLoopbackOnly(mach),
                "simulatorConfirmed", svc != null ? svc.isSimulatorConfirmed() : SimGuard.isSimulated(mach)));
        out.put("machine", machine.machineStatus());
        Map<String, Object> d = new LinkedHashMap<>();
        out.put("dispense", d);
        Map<String, Object> pressure = ctx.pressure.snapshot();
        pressure.put("engineRunActive", svc != null && svc.isBusy());
        out.put("pressure", pressure);
        out.put("run", ctx.tracker.snapshot());
        PadCalibrationJob pcal = ctx.padCalJobs.current();
        out.put("padCalibration", pcal == null || svc == null ? null : pcal.view());
        if (svc == null) {
            d.put("running", false);
            d.put("available", false);
            d.put("unavailableReason", "the dispense engine is not running");
            return out;
        }
        DispenseConfig cfg = svc.getConfig();
        d.put("running", true);
        d.put("available", svc.isAvailable());
        d.put("unavailableReason", svc.getUnavailableReason());
        d.put("busy", svc.isBusy());
        d.put("nozzle", cfg.nozzle);
        d.put("bZeroed", svc.isBZeroed());
        d.put("needle", Views.map("touchedOff", cfg.isNeedleTouchedOff(), "zOffsetMm", cfg.needleZOffsetMm,
                "setAtMs", cfg.needleZOffsetSetAtMs, "changedAtMs", cfg.needleChangedAtMs, "gapMm",
                cfg.gapMm(), "anchored", cfg.isNeedleAnchored(), "anchor", cfg.needleAnchor,
                "anchorOffsetMm", cfg.needleAnchor == null ? null : cfg.needleAnchor.offsetMm(), "xyTrim",
                cfg.needleXyTrim));
        d.put("profile", Views.profile(cfg, svc.getControllerConnectedAtMs()));
        d.put("purgeRow", Views.purgeRow(cfg));
        d.put("cleanPadCheck", cfg.cleanPadCheck);
        Map<String, Object> surf = new LinkedHashMap<>();
        for (Map.Entry<String, DispenseConfig.SurfaceMap> e : cfg.surfaces.entrySet()) {
            surf.put(e.getKey(), Views.surface(e.getKey(), e.getValue()));
        }
        d.put("surfaces", surf);
        d.put("boardSurfaceName", cfg.boardSurfaceName);
        d.put("locations", cfg.locations);
        d.put("wasteLocationName", cfg.wasteLocationName);
        d.put("blankLocationName", cfg.blankLocationName);
        d.put("primeRotationDeg", cfg.primeRotationDeg);
        d.put("testDot", Views.map("offsetXMm", cfg.testDotOffsetXMm, "offsetYMm", cfg.testDotOffsetYMm,
                "defaultRotationDeg", defaultTestDotRotation(cfg)));
        d.put("maxContinuousOnTimeS", cfg.maxContinuousOnTimeS);
        d.put("minPadTypeStatus", cfg.minPadTypeStatus);
        d.put("homeBeforeRun", cfg.homeBeforeRun);
        d.put("newSeatAtMs", cfg.newSeatAtMs);
        RunResult last = svc.getLastRun();
        out.put("lastRun", Views.run(last, false));
        out.put("interlocks", interlockList(svc, cfg));
        return out;
    }

    /** Plain-language state of each §5.5 interlock (I1..I9) plus machine and config checks. */
    List<Map<String, Object>> interlockList(DispenseService svc, DispenseConfig cfg) {
        InterlockReport r = svc.checkInterlocks((DispensePlan) null, 0);
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(item("MACHINE", "Machine started and homed", r, "ok", "started and homed"));
        l.add(item("CONFIG", "Dispenser settings valid (pump and valve full-on only)", r, "ok", "valid"));
        Map<String, Object> stopCheck = machine.stopCommandCheck(cfg);
        boolean stopOk = stopCheck != null && Boolean.TRUE.equals(stopCheck.get("ok"));
        l.add(Views.map("id", "I1", "name", "Stop switches the pneumatics off", "state", stopOk ? "ok" : "warning",
                "text", stopOk ? "dispense runs stop their own pneumatics, and OpenPnP's Stop sends "
                        + String.join(", ", ControllerCommands.STOP_SEQUENCE)
                        : "dispense runs and this page's STOP switch the pneumatics off, but OpenPnP's own Stop button does not: "
                                + "set the driver's DISABLE_COMMAND to " + String.join(", ", ControllerCommands.STOP_SEQUENCE)
                                + " (machine.xml patch)",
                "detail", stopCheck));
        l.add(Views.map("id", "I2", "name", "Auger (B) zeroed before it moves", "state", "ok", "text",
                svc.isBZeroed() ? "B zeroed since the last home/start"
                        : "B is zeroed (G92 B0) at the start of the next run"));
        l.add(item("I3", "Dispenser nozzle can never pick or discard", r, "ok", cfg.nozzle + " has no nozzle tips"));
        l.add(item("I4", "Needle Z frame set and needle touched off since the last needle change", r, "ok",
                "needle Z offset " + cfg.needleZOffsetMm + " mm (frame " + (cfg.needleAnchor == null ? "-"
                        : String.format(java.util.Locale.ROOT, "%.3f", cfg.needleAnchor.offsetMm())) + " mm)"));
        if (cfg.pressureInterlockEnabled) {
            l.add(item("I5", "Barrel pressure inside the window", r, "ok", "inside the window"));
        }
        else {
            l.add(Views.map("id", "I5", "name", "Barrel pressure inside the window", "state", "off", "text",
                    "off: no barrel pressure sensor is fitted yet"));
        }
        l.add(Views.map("id", "I6", "name", "Regulator at the profile's pressure", "state", "info", "text",
                String.format(java.util.Locale.ROOT, "set by hand: %.1f psi for profile '%s' (no sensor). The window is "
                        + "narrow: 16 psi starved the screw, 20 psi drooled", cfg.profile.pressurePsi, cfg.profile.name)));
        l.add(Views.map("id", "I7", "name", "Maximum continuous pressure time", "state", "ok", "text",
                "pressure is switched off after " + Math.round(cfg.maxContinuousOnTimeS) + " s"));
        l.add(Views.map("id", "I8", "name", "Paste within its working life", "state", "off", "text",
                "not tracked yet: check the paste date yourself"));
        DispenseConfig.PurgeRow row = cfg.purgeRow;
        InterlockReport board = svc.checkInterlocks(new DispensePlan(), 0);
        if (cfg.profile.purgeDots > 0) {
            l.add(item("I9", "Purge row before each board (same recipe, no stop before the work)", board, "ok",
                    cfg.profile.purgeDots + " purge dots per board; " + row.spotsLeft() + " of " + row.spots
                            + " spots left on '" + row.surfaceName + "'"));
        }
        else {
            l.add(Views.map("id", "I9", "name", "Purge row before each board", "state", "off", "text",
                    "the profile has no purge dots"));
        }
        if (cfg.cleanPadCheck.enabled) {
            l.add(item("I10", "Clean pads (no leftover paste) before the pressure goes on", board, "ok",
                    String.format(java.util.Locale.ROOT, "top camera at every dot: bright share >= %.2f",
                            cfg.cleanPadCheck.minBrightFraction)));
        }
        else {
            l.add(Views.map("id", "I10", "name", "Clean pads before the pressure goes on", "state", "off", "text",
                    "switched off in the dispenser settings"));
        }
        if (cfg.profile.isPartialTurn(cfg)) {
            l.add(item("I11", "Screw start angle set for this controller power-up", board, "ok",
                    String.format(java.util.Locale.ROOT, "dots start at %s deg (the first purge dot sets it)",
                            Views.angles(cfg.profile.startAngles(cfg)))));
        }
        else {
            l.add(Views.map("id", "I11", "name", "Screw start angle", "state", "off", "text",
                    "whole revolutions per dot: the start angle does not matter"));
        }
        return l;
    }

    private static Map<String, Object> item(String id, String name, InterlockReport r, String okState, String okText) {
        List<String> msgs = new ArrayList<>();
        for (InterlockReport.Violation v : r.violations) {
            if (v.code.equals(id)) {
                msgs.add(v.message);
            }
        }
        return Views.map("id", id, "name", name, "state", msgs.isEmpty() ? okState : "blocked", "text",
                msgs.isEmpty() ? okText : String.join("; ", msgs));
    }

    /** A test dot is one dot of the dispense profile (net screw turn, deg of B). */
    static double defaultTestDotRotation(DispenseConfig cfg) {
        return Math.round(cfg.profile.netDeg(cfg) * 10000) / 10000.0;
    }

    // ------------------------------------------------------------------------------------------
    // Pressure, prime, test dot
    // ------------------------------------------------------------------------------------------

    Object pressureStart() throws Exception {
        DispenseService svc = ctx.availableSvc();
        ctx.requireEngineIdle(svc);
        if (ctx.pressure.isActive()) {
            throw ApiException.refused("the pressure is already " + ctx.pressure.getState().name().toLowerCase());
        }
        ApiContext.refuseUnlessOk(ctx.preflight(svc, Interlocks.Scope.noNeedle()), "Pressure on");
        return ctx.pressure.start(svc);
    }

    Object prime(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        DispenseConfig cfg = svc.getConfig();
        double rot = Json.num(b, "rotationDeg", cfg.primeRotationDeg);
        checkRotation(rot, 3600);
        DispenseConfig.NamedLocation waste = requireWaste(cfg);
        startChecks(svc, DispenseService.shotsScope(cfg.wasteLocationName, true), "Prime");
        ctx.tracker.expect("prime");
        Future<RunResult> f = svc.runShots(cfg.wasteLocationName, rot, 1, true);
        return ApiContext.awaitIf(Json.bool(b, "wait", false), f, rr -> Views.run(rr, false),
                Views.map("kind", "prime", "rotationDeg", rot, "location", waste));
    }

    Object testDot(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        DispenseConfig cfg = svc.getConfig();
        double rot = Json.num(b, "rotationDeg", defaultTestDotRotation(cfg));
        checkRotation(rot, maxDotRotation(cfg));
        DispenseConfig.NamedLocation waste = requireWaste(cfg);
        startChecks(svc, DispenseService.shotsScope(cfg.wasteLocationName, true), "Test dot");
        DispenseConfig.NamedLocation spot = new DispenseConfig.NamedLocation(waste.x + cfg.testDotOffsetXMm,
                waste.y + cfg.testDotOffsetYMm, waste.z);
        DispenseConfig.NamedLocation cur = cfg.location(TEST_DOT_LOCATION);
        if (cur == null || cur.x != spot.x || cur.y != spot.y || cur.z != spot.z) {
            // The engine dispenses shots at named locations; keep this one derived from the waste place.
            svc.setLocation(TEST_DOT_LOCATION, spot.x, spot.y, spot.z);
        }
        ctx.tracker.expect("test dot");
        Future<RunResult> f = svc.runShots(TEST_DOT_LOCATION, rot, 1, true);
        return ApiContext.awaitIf(Json.bool(b, "wait", false), f, rr -> Views.run(rr, false),
                Views.map("kind", "test dot", "rotationDeg", rot, "location", spot));
    }

    /** Engine idle, no manual hold, interlocks (as the engine applies them to shots/calibration). */
    void startChecks(DispenseService svc, Interlocks.Scope scope, String what) throws ApiException {
        ctx.requireEngineIdle(svc);
        ctx.requireNoPressureHold();
        ApiContext.refuseUnlessOk(ctx.preflight(svc, scope), what);
    }

    /** Largest single-dot rotation: 720 deg, or more for a profile of two revolutions (720.72 deg). */
    static double maxDotRotation(DispenseConfig cfg) {
        return Math.max(720, cfg.profile.forwardDeg(cfg));
    }

    static void checkRotation(double rot, double max) throws ApiException {
        if (rot <= 0 || rot > max) {
            throw ApiException.badRequest("rotation must be more than 0 and at most " + max + " degrees of the auger");
        }
    }

    static DispenseConfig.NamedLocation requireWaste(DispenseConfig cfg) throws ApiException {
        DispenseConfig.NamedLocation w = cfg.location(cfg.wasteLocationName);
        if (w == null) {
            throw ApiException.refused("set the waste place first (Dispense tab, Places: '" + cfg.wasteLocationName + "')");
        }
        return w;
    }

    // ------------------------------------------------------------------------------------------
    // Places, needle, settings
    // ------------------------------------------------------------------------------------------

    Object setLocation(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        String name = r.group(1);
        if (TEST_DOT_LOCATION.equals(name)) {
            throw ApiException.badRequest("'" + TEST_DOT_LOCATION + "' follows the waste place; set the waste place instead");
        }
        double x = Json.reqNum(b, "x"), y = Json.reqNum(b, "y"), z = Json.reqNum(b, "z");
        svc.setLocation(name, x, y, z);
        return svc.getConfig().locations;
    }

    Object needleChanged() throws Exception {
        DispenseService svc = ctx.svc();
        svc.markNeedleChanged();
        return Views.map("needleChanged", true, "note", "dispensing is blocked (I4) until the needle Z frame is "
                + "recorded again (N1 and needle contact) and the needle is touched off");
    }

    Object needleOffset(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        double off = Json.reqNum(b, "offsetMm");
        if (Math.abs(off) > 20) {
            throw ApiException.badRequest("needle Z offset must be within +/- 20 mm");
        }
        svc.setNeedleZOffset(off, "manual", Json.str(b, "note", "entered in the web UI"));
        return Views.map("zOffsetMm", off, "touchedOff", svc.getConfig().isNeedleTouchedOff());
    }

    /**
     * I4 needle Z frame: the same surface touched by N1 (its Z in OpenPnP's frame) and by the needle
     * tip (N2 Z). Body: {"surfaceZ": n1ContactZ, "needleContactZ": n2ContactZ, "x"?, "y"?}, or instead
     * of needleContactZ the bench relation {"needleContactRawSum": K}: needle contact raw Z = K - N1
     * contact Z (side camera, 2026-10-27: K = 65.59, i.e. N2 contact = N1 contact - 2.59).
     */
    Object needleAnchor(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        double sz = Json.reqNum(b, "surfaceZ");
        Double rawSum = Json.num(b, "needleContactRawSum", null);
        if (rawSum != null && b.has("needleContactZ")) {
            throw ApiException.badRequest("give either needleContactZ or needleContactRawSum, not both");
        }
        double nz = rawSum == null ? Json.reqNum(b, "needleContactZ") : svc.needleContactZFromRawSum(sz, rawSum);
        if (!Double.isFinite(sz) || !Double.isFinite(nz) || Math.abs(nz - sz) > 20) {
            throw ApiException.badRequest("the needle contact Z must be within 20 mm of the N1 contact Z");
        }
        DispenseConfig.NeedleAnchor a = svc.setNeedleAnchor(sz, nz, Json.num(b, "x", null), Json.num(b, "y", null),
                rawSum);
        return Views.map("anchor", a, "offsetMm", a.offsetMm(), "touchedOff", svc.getConfig().isNeedleTouchedOff(),
                "note", "needle Z offset set to " + String.format(java.util.Locale.ROOT, "%.3f", a.offsetMm())
                        + " mm; run a needle touch-off (or confirm the offset) before dispensing");
    }

    Object surfaces() throws ApiException {
        DispenseConfig cfg = ctx.svc().getConfig();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, DispenseConfig.SurfaceMap> e : cfg.surfaces.entrySet()) {
            out.put(e.getKey(), Views.surface(e.getKey(), e.getValue()));
        }
        return Views.map("boardSurfaceName", cfg.boardSurfaceName, "blankSurfaceName", cfg.blankLocationName,
                "surfaces", out);
    }

    /**
     * Stores a probed surface: {"points": [{"x","y","z"}...] or [[x, y, z]...], "note"?}. Z is the N1
     * contact height (OpenPnP frame). At least 3 points spanning an area.
     */
    Object putSurface(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        if (svc.isBusy()) {
            throw ApiException.refused("surfaces cannot change while a dispense task runs");
        }
        JsonElement pe = b.get("points");
        if (pe == null || !pe.isJsonArray()) {
            throw ApiException.badRequest("'points' must be a list of {x, y, z} (N1 contact heights)");
        }
        List<DispenseConfig.SurfacePoint> pts = new ArrayList<>();
        for (JsonElement e : pe.getAsJsonArray()) {
            try {
                if (e.isJsonArray() && e.getAsJsonArray().size() == 3) {
                    pts.add(new DispenseConfig.SurfacePoint(e.getAsJsonArray().get(0).getAsDouble(),
                            e.getAsJsonArray().get(1).getAsDouble(), e.getAsJsonArray().get(2).getAsDouble()));
                }
                else {
                    JsonObject o = e.getAsJsonObject();
                    pts.add(new DispenseConfig.SurfacePoint(o.get("x").getAsDouble(), o.get("y").getAsDouble(),
                            o.get("z").getAsDouble()));
                }
            }
            catch (RuntimeException ex) {
                throw ApiException.badRequest("each point needs numeric x, y and z: " + e);
            }
        }
        if (pts.size() > 500) {
            throw ApiException.badRequest("at most 500 points");
        }
        // A board surface can be tied to the board registration of a plan (I4: re-probe on re-seat).
        String planId = Json.str(b, "planId", null);
        String boardFp = null;
        if (planId != null) {
            boardFp = currentBoardRegistration(svc, requirePlan(planId));
            if (boardFp == null || boardFp.startsWith("unavailable")) {
                throw ApiException.refused("the board of plan " + planId + " cannot be found: " + boardFp);
            }
        }
        try {
            svc.setSurface(r.group(1), pts, Json.str(b, "note", null), boardFp);
        }
        catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        if (boardFp != null && r.group(1).equals(svc.getConfig().boardSurfaceName)) {
            svc.setNewSeat(false); // the re-seated board was probed by hand
        }
        return Views.surface(r.group(1), svc.getConfig().surface(r.group(1)));
    }

    Object deleteSurface(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.svc();
        if (svc.isBusy()) {
            throw ApiException.refused("surfaces cannot change while a dispense task runs");
        }
        svc.clearSurface(r.group(1));
        return surfaces();
    }

    /** Partial update: only the fields given replace the current settings. */
    Object putConfig(ApiServer.Request r) throws Exception {
        JsonObject patch = r.json();
        DispenseService svc = ctx.svc();
        if (svc.isBusy()) {
            throw ApiException.refused("settings cannot change while a dispense task runs");
        }
        JsonObject cur = Json.GSON.toJsonTree(svc.getConfig()).getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : patch.entrySet()) {
            if (!cur.has(e.getKey()) && !PROTECTED_SETTINGS.contains(e.getKey())) {
                throw ApiException.badRequest("unknown setting '" + e.getKey() + "'");
            }
            if (NESTED_SETTINGS.contains(e.getKey())) {
                if (!e.getValue().isJsonObject()) {
                    throw ApiException.badRequest("'" + e.getKey() + "' must be an object");
                }
                JsonObject sub = cur.getAsJsonObject(e.getKey());
                for (Map.Entry<String, JsonElement> f : e.getValue().getAsJsonObject().entrySet()) {
                    String path = e.getKey() + "." + f.getKey();
                    if (!sub.has(f.getKey())) {
                        throw ApiException.badRequest("unknown setting '" + path + "'");
                    }
                    if (PROTECTED_NESTED.contains(path)) {
                        if (!sub.get(f.getKey()).equals(f.getValue())) {
                            throw ApiException.badRequest("'" + path + "' is stamped by the engine: set or confirm "
                                    + "the value with its own action");
                        }
                        continue;
                    }
                    sub.add(f.getKey(), f.getValue());
                }
                continue;
            }
            if (PROTECTED_SETTINGS.contains(e.getKey())) {
                JsonElement was = cur.has(e.getKey()) ? cur.get(e.getKey()) : com.google.gson.JsonNull.INSTANCE;
                if (!was.equals(e.getValue())) {
                    throw ApiException.badRequest(REGISTRATION_SETTINGS.contains(e.getKey())
                            ? "'" + e.getKey() + "' is stamped by the engine: Register board and the new-seat answer "
                                    + "set it"
                            : "'" + e.getKey() + "' is the needle touch-off state (I4): change it with the Needle card "
                                    + "or a touch-off, which also record it");
                }
                continue; // unchanged round-trip from the full settings editor
            }
            cur.add(e.getKey(), e.getValue());
        }
        DispenseConfig next = Json.fromJson(cur, DispenseConfig.class);
        if (next.locations == null) {
            next.locations = new LinkedHashMap<>();
        }
        if (next.padCalibration != null && !SimGuard.isSimulated(ctx.machine())) {
            // the shortened statistics of the SIM tests are refused on the machine (PROFILE_CALIBRATION_DESIGN.md §5.4)
            List<String> floors = next.padCalibration.realMachineProblems();
            floors.removeAll(svc.getConfig().padCalibration.realMachineProblems());
            if (!floors.isEmpty()) {
                throw ApiException.badRequest(String.join("; ", floors));
            }
        }
        svc.updateConfig(next); // validates; IllegalArgumentException -> 400 with the problems
        return svc.getConfig();
    }

    // ------------------------------------------------------------------------------------------
    // Boards, packages
    // ------------------------------------------------------------------------------------------

    /** OpenPnP's job boards and board files, read on OpenPnP's thread (an import may be changing the list). */
    Map<String, Object> boards() throws Exception {
        return EdtRunner.call(this::boardsNow, EDT_START_MS);
    }

    private Map<String, Object> boardsNow() {
        DispenseService svc = ctx.svcOrNull();
        Map<String, BoardRegistration> regs = svc == null ? new LinkedHashMap<>() : svc.getConfig().boardRegistrations;
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> job = new ArrayList<>();
        Job j = currentJob();
        if (j != null) {
            for (BoardLocation bl : j.getBoardLocations()) {
                Board b = bl.getBoard();
                Location l = bl.getGlobalLocation().convertToUnits(LengthUnit.Millimeters);
                Map<String, Object> entry = Views.map("boardLocationId", bl.getId(), "name", b == null ? bl.getId()
                        : b.getName(), "file", b == null || b.getFile() == null ? null : b.getFile().getAbsolutePath(),
                        "x", l.getX(), "y", l.getY(), "z", l.getZ(), "rotation", l.getRotation(), "side",
                        String.valueOf(bl.getGlobalSide()), "enabled", bl.isEnabled(), "fiducialTransform",
                        String.valueOf(bl.getPlacementsTransformStatus()), "registration",
                        registrationListed(regs.get(BoardRegistration.jobKey(bl.getId()))));
                BoardListView.addTo(entry, b);
                job.add(entry);
            }
        }
        out.put("jobAvailable", j != null);
        out.put("job", job);
        List<Map<String, Object>> files = new ArrayList<>();
        for (Board b : ctx.configuration.getBoards()) {
            Map<String, Object> entry = Views.map("name", b.getName(), "file", b.getFile() == null ? null
                    : b.getFile().getAbsolutePath(), "placements", b.getPlacements().size(), "registration",
                    b.getFile() == null ? null : registrationListed(regs.get(BoardRegistration.fileKey(b.getFile()))));
            BoardListView.addTo(entry, b);
            files.add(entry);
        }
        out.put("files", files);
        return out;
    }

    /** A board's latest registration as GET /api/boards lists it, or null. */
    private static Map<String, Object> registrationListed(BoardRegistration r) {
        return r == null ? null : Views.map("id", r.id, "atMs", r.atMs, "nominal", r.nominal);
    }

    /** The job open in OpenPnP's window (null when headless). */
    static Job currentJob() {
        try {
            MainFrame f = MainFrame.get();
            return f == null || f.getJobTab() == null ? null : f.getJobTab().getJob();
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** OpenPnP's packages, read on OpenPnP's thread (an import may be adding some). */
    Object packages() throws Exception {
        return EdtRunner.call(this::packagesNow, EDT_START_MS);
    }

    private List<Map<String, Object>> packagesNow() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Package p : ctx.configuration.getPackages()) {
            Footprint fp = p.getFootprint();
            List<Map<String, Object>> pads = new ArrayList<>();
            if (fp != null) {
                double toMm = new org.openpnp.model.Length(1, fp.getUnits()).convertToUnits(LengthUnit.Millimeters)
                        .getValue();
                for (Footprint.Pad pad : fp.getPads()) {
                    pads.add(Views.map("name", pad.getName(), "x", pad.getX() * toMm, "y", pad.getY() * toMm, "w",
                            pad.getWidth() * toMm, "h", pad.getHeight() * toMm, "rotation", pad.getRotation(),
                            "roundness", pad.getRoundness()));
                }
            }
            l.add(Views.map("id", p.getId(), "description", p.getDescription(), "pads", pads));
        }
        return l;
    }

    // ------------------------------------------------------------------------------------------
    // Plan and run
    // ------------------------------------------------------------------------------------------

    Object plan(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        String source = Json.str(b, "source", "file");
        String regMode = Json.str(b, "registration", "auto");
        DispensePlan plan;
        Map<String, Object> src;
        BoardRegistration reg;
        if ("job".equals(source)) {
            String id = Json.str(b, "boardLocationId", null);
            BoardLocation bl = findJobBoard(id);
            src = Views.map("source", "job", "boardLocationId", id);
            reg = registrationFor(svc, regMode, src, bl);
            plan = svc.planBoard(bl, reg);
            artRef(plan, bl);
        }
        else if ("file".equals(source)) {
            String path = Json.str(b, "file", null);
            if (path == null) {
                throw ApiException.badRequest("'file' (a .board.xml path) is required");
            }
            File f = new File(path);
            if (!f.isAbsolute() || !f.getName().endsWith(".board.xml")) {
                throw ApiException.badRequest("'" + path + "' is not an absolute .board.xml path");
            }
            if (!f.isFile()) {
                throw ApiException.notFound("Board file not found: " + path);
            }
            // z is required: a silent 0 put the needle ~10 mm into the board (review finding)
            double x = Json.reqNum(b, "x"), y = Json.reqNum(b, "y"), z = Json.reqNum(b, "z"),
                    rot = Json.num(b, "rotation", 0.0);
            src = Views.map("source", "file", "file", f.getAbsolutePath(), "x", x, "y", y, "z", z, "rotation", rot);
            BoardLocation bl = baseBoardLocation(src);
            reg = registrationFor(svc, regMode, src, bl);
            plan = svc.planBoard(bl, reg);
            artRef(plan, bl);
        }
        else {
            throw ApiException.badRequest("'source' must be 'job' or 'file'");
        }
        src.put("registrationId", reg == null ? null : reg.id);
        PlanStore.Entry e = new PlanStore.Entry(plan, src);
        e.registrationRecord = reg;
        estimate(svc, e);
        e.registrationTargets = registration.targets(svc, e);
        ctx.plans.put(e);
        return Views.plan(e, runPreflight(svc, plan, nz(e.estimatedPressurisedSeconds)), svc.getConfig().newSeatAtMs);
    }

    Object getPlan(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.availableSvc();
        PlanStore.Entry e = requirePlan(r.group(1));
        return Views.plan(e, runPreflight(svc, e.plan, nz(e.estimatedPressurisedSeconds)), svc.getConfig().newSeatAtMs);
    }

    /**
     * The interlocks of a plan as Run faces them: a home Run does by itself (homeBeforeRun), and the Z
     * check after it, are notes, not violations; a Z check with no point left to touch stays one.
     */
    InterlockReport runPreflight(DispenseService svc, DispensePlan plan, double estimatedPressurisedSeconds) {
        HomeTracker.Plan home = svc.homePlan();
        DispenseService.ZCheckPlan zc = svc.zCheckPlan(plan, home.needed);
        boolean noReference = zc.needed && zc.reference == null;
        InterlockReport r = ctx.preflightPlan(svc, plan, estimatedPressurisedSeconds, home.needed,
                home.needed || zc.needed);
        if (noReference) {
            r.add("I4", DispenseService.noZReference(home.needed, zc.homeAtMs).substring("I4: ".length()));
        }
        return r;
    }

    /**
     * The registration a plan of this board uses (§7.1): "auto" (default) the stored record of its board
     * key when it still applies to the board and its nominal seat, "none", or a registration id (409
     * when it is gone or does not apply).
     */
    BoardRegistration registrationFor(DispenseService svc, String mode, Map<String, Object> src, BoardLocation nominal)
            throws ApiException {
        if ("none".equals(mode)) {
            return null;
        }
        DispenseConfig cfg = svc.getConfig();
        String key = boardKey(src);
        BoardRegistration.Nominal n = BoardRegistration.Nominal.of(nominal, isJob(src));
        if ("auto".equals(mode)) {
            BoardRegistration reg = cfg.boardRegistrations.get(key);
            return reg != null && reg.refusal(key, n, nominal.getBoard(), svc::isSimulatorConfirmed) == null ? reg : null;
        }
        BoardRegistration reg = cfg.boardRegistrationById(mode);
        if (reg == null) {
            throw ApiException.refused("REG: registration " + (mode.length() > 4 ? mode.substring(0, 4) + "…" : mode)
                    + " is not stored any more (the board was registered again since): plan again");
        }
        String why = reg.refusal(key, n, nominal.getBoard(), svc::isSimulatorConfirmed);
        if (why != null) {
            throw ApiException.refused(why);
        }
        return reg;
    }

    /** "file:<path>" or "job:<boardLocationId>" (BoardRegistration's keys). */
    static String boardKey(Map<String, Object> src) {
        return isJob(src) ? BoardRegistration.jobKey((String) src.get("boardLocationId"))
                : BoardRegistration.fileKey(new File((String) src.get("file")));
    }

    static boolean isJob(Map<String, Object> src) {
        return "job".equals(src.get("source"));
    }

    /**
     * The plans kept in memory (last {@value PlanStore#MAX}, run subsets left out), newest first, without dots or
     * interlocks: a browser opened later shows the latest plan (GET /api/plan/{id} for the rest). Each carries the
     * profile it was made with (small), so a list of runs can name its profile without fetching every full plan.
     */
    Object plans() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (PlanStore.Entry e : ctx.plans.recent()) {
            DispensePlan p = e.plan;
            l.add(Views.map("planId", p.planId, "boardName", p.boardName, "boardLocationId", p.boardLocationId,
                    "createdAtMs", p.createdAtMs, "source", e.source, "pads", p.pads.size(), "dots", p.dots.size(),
                    "blockedPads", p.blockedPads().size(), "profile", p.profile, "profileFingerprint", p.profileFingerprint));
        }
        return l;
    }

    PlanStore.Entry requirePlan(String id) throws ApiException {
        PlanStore.Entry e = id == null ? null : ctx.plans.get(id);
        if (e == null) {
            throw ApiException.notFound("plan '" + id + "' is not known (plans are kept for this session only): plan the board again");
        }
        return e;
    }

    private static double nz(Double d) {
        return d == null ? 0 : d;
    }

    /**
     * Kinematic estimate of the whole program, built exactly as the engine will build it (also
     * checks every dot against the soft limits). Read-only.
     */
    void estimate(DispenseService svc, PlanStore.Entry e) {
        try {
            Machine m = ctx.machine();
            DispenseConfig cfg = svc.getConfig();
            OpenPnpRawMapper mapper = new OpenPnpRawMapper((ReferenceMachine) m, svc.getDispenserNozzle(), cfg.bAxisName);
            GcodeProgram p = GcodeStreamBuilder.forPlan(cfg, mapper, e.plan, DispenseService.purgeSpecs(cfg, e.plan),
                    svc.isBZeroed(), false, 0L);
            DoseBlocks.fillDemand(e.plan, p);
            e.estimatedSeconds = p.estimatedSeconds;
            e.estimatedPressurisedSeconds = p.estimatedPressurisedSeconds;
            e.estimatedWorkSeconds = p.estimatedWorkSeconds;
            e.dotArriveS = p.dotArriveSeconds;
            e.dotEndS = p.dotEndSeconds;
        }
        catch (Exception ex) {
            e.estimateError = MachineEndpoints.rootMessage(ex);
        }
        e.dotDiameterMm = dotDiameter(svc, e.plan);
    }

    /** The dot size at the plan's turn from the current calibration's dot ladder, mm; null when not measured there. */
    static Double dotDiameter(DispenseService svc, DispensePlan plan) {
        try {
            Calibration cal = svc.getCurrentCalibration();
            if (cal == null || plan.dots.isEmpty()) {
                return null;
            }
            return DotLadder.of(svc.getDotLadder(cal.id)).diameterAt(plan.dots.get(0).rotationDeg);
        }
        catch (Exception ex) {
            return null;
        }
    }

    /**
     * The board drawing of the plan, fixed now (a later replace or edit cannot put another drawing under these dots),
     * read where OpenPnP's lists are read. Every place that makes a board plan calls it (also Register board's plan).
     */
    void artRef(DispensePlan plan, BoardLocation bl) throws Exception {
        Board board = bl.getBoard();
        if (board == null) {
            return;
        }
        BoardArtStore.ArtRef ref;
        try {
            ref = EdtRunner.call(() -> BoardArtStore.get().refFor(board), EDT_START_MS);
        }
        catch (RuntimeException e) {
            Logger.warn(e, "plan {} has no board drawing", plan.planId); // the plan stands without it
            return;
        }
        plan.boardArtId = ref.id;
        plan.boardArtKind = ref.kind;
        plan.boardArtNote = ref.note;
    }

    /** Fingerprint of the board the plan came from, as it is now (null if it cannot be found). */
    private String currentBoardFingerprint(DispenseService svc, PlanStore.Entry e) {
        try {
            return DispenseService.boardFingerprint(currentBoardLocation(e));
        }
        catch (Exception ex) {
            return "unavailable: " + ex.getMessage();
        }
    }

    /** Registration (X/Y/rotation, side, fiducial transform) of the plan's board as it is now. */
    private String currentBoardRegistration(DispenseService svc, PlanStore.Entry e) {
        try {
            return DispenseService.boardRegistration(currentBoardLocation(e));
        }
        catch (Exception ex) {
            return "unavailable: " + ex.getMessage();
        }
    }

    /**
     * The plan's board as it is now, with the registration the plan was made with
     * ({@code source.registrationId}) applied again; refused when that record is gone or no longer
     * applies (registered again, the board moved, OpenPnP re-checked a job board, a fiducial moved).
     */
    BoardLocation currentBoardLocation(PlanStore.Entry e) throws Exception {
        BoardLocation bl = baseBoardLocation(e.source);
        String regId = (String) e.source.get("registrationId");
        if (regId == null) {
            return bl;
        }
        DispenseService svc = ctx.svc();
        BoardRegistration reg = registrationFor(svc, regId, e.source, bl);
        return reg.applyTo(bl);
    }

    /** The plan's board at its nominal seat, without our registration (a job board as OpenPnP has it). */
    BoardLocation baseBoardLocation(Map<String, Object> src) throws Exception {
        if ("job".equals(src.get("source"))) {
            return findJobBoard((String) src.get("boardLocationId"));
        }
        File f = new File((String) src.get("file"));
        if (!f.isFile()) {
            // getBoard would write an empty board file there and list it
            throw ApiException.notFound("Board file not found: " + f.getPath());
        }
        Board board = EdtRunner.call(() -> ctx.configuration.getBoard(f), EDT_START_MS);
        BoardLocation bl = new BoardLocation(board);
        bl.setLocation(new Location(LengthUnit.Millimeters, ((Number) src.get("x")).doubleValue(),
                ((Number) src.get("y")).doubleValue(), ((Number) src.get("z")).doubleValue(),
                ((Number) src.get("rotation")).doubleValue()));
        return bl;
    }

    BoardLocation findJobBoard(String id) throws ApiException {
        Job j = currentJob();
        if (j == null) {
            throw ApiException.refused("no OpenPnP job is open (the window is not running): choose a board file instead");
        }
        for (BoardLocation bl : j.getBoardLocations()) {
            if (bl.getId().equals(id)) {
                return bl;
            }
        }
        throw ApiException.notFound("board '" + id + "' is not in the open job");
    }

    Object run(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        ctx.requireEngineIdle(svc);
        ctx.requireNoPressureHold();
        ctx.requireMachineIdle();
        PlanStore.Entry e = requirePlan(Json.str(b, "planId", null));
        DispenseConfig cfg = svc.getConfig();
        DispensePlan full = e.plan;
        if (!cfg.profile.fingerprint().equals(full.profileFingerprint) || cfg.needleZOffsetMm != full.needleZOffsetMm
                || cfg.gapMm() != full.needleGapMm || cfg.hopHeightMm != full.hopHeightMm) {
            throw ApiException.refused("the dispense profile, needle offset, gap or hop height changed since this plan "
                    + "was made (its dots would be wrong): plan the board again");
        }
        // Review finding: a board moved, re-checked (fiducials) or edited after planning was not detected.
        String now = currentBoardFingerprint(svc, e);
        if (full.boardFingerprint != null && !full.boardFingerprint.equals(now)) {
            if (now.startsWith("unavailable:")) {
                throw ApiException.refused("the board's registration or position changed since this plan was made ("
                        + now.substring("unavailable:".length()).trim() + "): plan the board again");
            }
            throw ApiException.refused("the board's position, fiducial check or placements changed in OpenPnP since this "
                    + "plan was made (its dot positions would be wrong): plan the board again");
        }
        DispensePlan p = PlanStore.subset(full, Json.strings(b, "padIds"), Json.integer(b, "maxDots", null));
        PlanStore.Entry runEntry = e;
        if (p != full) {
            svc.getDb().insertPadPlan(p); // the subset is recorded like any plan
            runEntry = new PlanStore.Entry(p, e.source);
            estimate(svc, runEntry);
            ctx.plans.put(runEntry);
        }
        // The run homes by itself when a home is needed, and checks Z after it when Z data predate it.
        HomeTracker.Plan home = svc.homePlan();
        DispenseService.ZCheckPlan zc = svc.zCheckPlan(p, home.needed);
        boolean zCheckFirst = home.needed || zc.needed;
        if (zc.needed && zc.reference == null) {
            throw ApiException.refused(DispenseService.noZReference(home.needed, zc.homeAtMs));
        }
        InterlockReport pre = ctx.preflightPlan(svc, p, nz(runEntry.estimatedPressurisedSeconds), home.needed,
                zCheckFirst);
        ApiContext.refuseUnlessOk(pre, "Run");
        ctx.tracker.expect("board");
        Future<RunResult> f = svc.runPlan(p);
        Map<String, Object> accepted = Views.map("kind", "board", "planId", p.planId, "parentPlanId",
                p == full ? null : full.planId, "dots", p.dots.size(), "pads", p.pads.size(), "homeFirst", home.needed,
                "homeWhyCode", home.whyCode, "homeWhy", home.why, "homeSinceMs", home.sinceMs, "homeEstimateS",
                home.estimateS, "zCheckFirst", zCheckFirst, "zCheckReference", zc.reference == null ? null
                        : Views.map("name", zc.reference.name, "x", zc.reference.x, "y", zc.reference.y, "z",
                                zc.reference.z));
        return ApiContext.awaitIf(Json.bool(b, "wait", false), f, rr -> Views.run(rr, false), accepted);
    }

    /**
     * Clean-pad check alone (camera only, pressure off): {"wait"?: bool}. The result is kept with the
     * plan (GET /api/plan/{id} -> cleanCheck); a run repeats the check before the pressure goes on.
     */
    Object cleanCheck(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        ctx.requireEngineIdle(svc);
        ctx.requireNoPressureHold();
        PlanStore.Entry e = requirePlan(r.group(1));
        ctx.tracker.expect("clean-pad check");
        Future<PadCleanReport> f = svc.checkCleanPads(e.plan);
        e.cleanCheckFuture = f;
        return ApiContext.awaitIf(Json.bool(b, "wait", false), f, Views::cleanCheck,
                Views.map("kind", "clean-pad check", "planId", e.plan.planId, "dots", e.plan.dots.size()));
    }

    /**
     * I11: set ({"startAngleDeg": a}) or confirm ({} or {"confirm": true}) the screw start angle for
     * the current controller power-up (the B step count restarts at power-up).
     */
    Object startAngle(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        if (svc.isBusy()) {
            throw ApiException.refused("the start angle cannot change while a dispense task runs");
        }
        Double a = Json.num(b, "startAngleDeg", null);
        if (a != null && !(a >= 0 && a < 360)) {
            throw ApiException.badRequest("startAngleDeg must be 0 to < 360");
        }
        svc.setStartAngle(a, Json.str(b, "note", null));
        return Views.profile(svc.getConfig(), svc.getControllerConnectedAtMs());
    }

    Object purgeReset() throws Exception {
        DispenseService svc = ctx.svc();
        if (svc.isBusy()) {
            throw ApiException.refused("the purge row cannot be reset while a dispense task runs");
        }
        svc.resetPurgeRow();
        return Views.purgeRow(svc.getConfig());
    }

    Object lastRun() throws ApiException {
        DispenseService svc = ctx.svc();
        return Views.map("run", Views.run(svc.getLastRun(), true));
    }
}
