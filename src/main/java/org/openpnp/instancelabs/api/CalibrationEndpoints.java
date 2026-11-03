package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import org.openpnp.instancelabs.dispense.Calibration;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DotLadderCalibration;
import org.openpnp.instancelabs.dispense.DotLadderRow;
import org.openpnp.instancelabs.dispense.NeedleTouchOff;
import org.openpnp.instancelabs.dispense.RunResult;
import org.openpnp.instancelabs.dispense.ScaleCalibration;

import com.google.gson.JsonObject;

/**
 * Calibration workflows (plan §5.2, §5.6, §6.1): optical dot ladder, needle touch-off by dot
 * ladder, scale shots (dispense, then type the masses), and the records they produce.
 */
final class CalibrationEndpoints {
    private final ApiContext ctx;
    private final DispenseEndpoints dispense;

    CalibrationEndpoints(ApiContext ctx, DispenseEndpoints dispense) {
        this.ctx = ctx;
        this.dispense = dispense;
    }

    void register(ApiServer s) {
        s.route("GET", "/api/calibrations", r -> list());
        s.route("GET", "/api/calibrations/(\\d+)/ladder", this::ladder);
        s.route("POST", "/api/calibrations/scale-shots", this::scaleShots);
        s.route("POST", "/api/calibrations/scale-dots", this::scaleDots);
        s.route("POST", "/api/calibrations/shots-run", this::shotsRun);
        s.route("POST", "/api/calibrations/dot-ladder", this::dotLadder);
        s.route("POST", "/api/calibrations/touch-off", this::touchOff);
    }

    Object list() throws Exception {
        DispenseService svc = ctx.svc();
        Calibration cur = svc.getCurrentCalibration();
        List<Map<String, Object>> all = new ArrayList<>();
        for (Calibration c : svc.getDb().calibrations()) {
            all.add(Views.calibration(c));
        }
        List<Map<String, Object>> ladder = new ArrayList<>();
        if (cur != null) {
            for (DotLadderRow row : svc.getDotLadder(cur.id)) {
                ladder.add(Views.ladderRow(row));
            }
        }
        return Views.map("current", Views.calibration(cur), "calibrations", all, "ladder", ladder,
                "touchOffs", svc.getDb().query("SELECT * FROM needle_touchoff ORDER BY id DESC LIMIT 20"));
    }

    Object ladder(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.svc();
        long id = Long.parseLong(r.group(1));
        if (svc.getDb().calibration(id) == null) {
            throw ApiException.notFound("no calibration #" + id);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DotLadderRow row : svc.getDotLadder(id)) {
            rows.add(Views.ladderRow(row));
        }
        return rows;
    }

    /** Manual scale entry: masses of N shots of a known rotation -> q (mm3 per auger turn). */
    Object scaleShots(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        DispenseConfig cfg = svc.getConfig();
        ScaleCalibration.ShotsEntry e = new ScaleCalibration.ShotsEntry();
        e.rotationDegPerShot = Json.reqNum(b, "rotationDegPerShot");
        e.perShotMassMg = Json.numbers(b, "perShotMassMg", null);
        e.totalMassMg = Json.num(b, "totalMassMg", null);
        Integer count = Json.integer(b, "count", e.perShotMassMg == null ? null : e.perShotMassMg.length);
        e.count = count == null ? 0 : count;
        e.densityMgMm3 = Json.num(b, "densityMgMm3", cfg.pasteDensityMgMm3);
        e.tip = Json.str(b, "tip", null);
        e.cartridge = Json.str(b, "cartridge", null);
        e.pasteLot = Json.str(b, "pasteLot", null);
        e.operator = Json.str(b, "operator", null);
        e.notes = Json.str(b, "notes", null);
        // Scale records note the profile's pressure and screw feed unless given.
        e.setPressurePsi = Json.num(b, "setPressurePsi", cfg.profile.pressurePsi);
        e.bFeedDegS = Json.num(b, "bFeedDegS", cfg.profile.feedDegS());
        e.barrelTempC = Json.num(b, "barrelTempC", null);
        e.barrelPressurePsi = Json.num(b, "barrelPressurePsi", null);
        e.pasteHoursRt = Json.num(b, "pasteHoursRt", null);
        Double validH = Json.num(b, "validForHours", null);
        e.validUntilMs = validH == null ? null : System.currentTimeMillis() + Math.round(validH * 3600_000.0);
        e.markCharacterized = Json.bool(b, "markCharacterized", false);
        if (e.perShotMassMg == null && (e.totalMassMg == null || e.count <= 0)) {
            throw ApiException.badRequest("enter the mass of each shot, or the total mass and the number of shots");
        }
        Calibration c = svc.recordScaleShots(e);
        return Views.map("calibration", Views.calibration(c), "note",
                "new calibration #" + c.id + "; plans made from now on use it if it is the current one");
    }

    /** Manual scale entry: a batch of equal dots weighed together -> mg per dot for one rung. */
    Object scaleDots(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        DispenseConfig cfg = svc.getConfig();
        ScaleCalibration.DotsEntry e = new ScaleCalibration.DotsEntry();
        Calibration cur = svc.getCurrentCalibration();
        Double calId = Json.num(b, "calibrationId", cur == null ? null : (double) cur.id);
        if (calId == null) {
            throw ApiException.badRequest("'calibrationId' is required");
        }
        e.calibrationId = calId.longValue();
        e.rotationDeg = Json.reqNum(b, "rotationDeg");
        Integer count = Json.integer(b, "count", null);
        if (count == null) {
            throw ApiException.badRequest("'count' is required");
        }
        e.count = count;
        e.totalMassMg = Json.reqNum(b, "totalMassMg");
        e.scaleSdMg = Json.num(b, "scaleSdMg", null);
        e.densityMgMm3 = Json.num(b, "densityMgMm3", cfg.pasteDensityMgMm3);
        e.diameterMeanMm = Json.num(b, "diameterMeanMm", null);
        e.diameterCv = Json.num(b, "diameterCv", null);
        e.operator = Json.str(b, "operator", null);
        e.notes = Json.str(b, "notes", null);
        DotLadderRow row = svc.recordScaleDots(e);
        return Views.map("row", Views.ladderRow(row));
    }

    /** Dispense N shots at a named place (e.g. a cup on the scale) for the scale workflow. */
    Object shotsRun(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        DispenseConfig cfg = svc.getConfig();
        String loc = Json.str(b, "locationName", null);
        if (loc == null) {
            throw ApiException.badRequest("'locationName' (the place of the cup) is required");
        }
        if (cfg.location(loc) == null) {
            throw ApiException.refused("place '" + loc + "' is not set (Dispense tab, Places)");
        }
        double rot = Json.reqNum(b, "rotationDeg");
        DispenseEndpoints.checkRotation(rot, 3600);
        Integer count = Json.integer(b, "count", 30);
        if (count == null || count < 1 || count > 200) {
            throw ApiException.badRequest("count must be 1 to 200");
        }
        boolean reference = loc.equals(cfg.wasteLocationName) || loc.equals(cfg.blankLocationName)
                || loc.equals(DispenseEndpoints.TEST_DOT_LOCATION);
        refuseReservedCoupon(svc, loc);
        dispense.startChecks(svc, DispenseService.shotsScope(loc, reference), "Scale shots");
        ctx.tracker.expect("scale shots");
        Future<RunResult> f = svc.runShots(loc, rot, count, reference);
        return ApiContext.awaitIf(Json.bool(b, "wait", false), f, rr -> Views.run(rr, false),
                Views.map("kind", "scale shots", "locationName", loc, "rotationDeg", rot, "count", count));
    }

    /**
     * PROFILE_CALIBRATION_DESIGN §7.5 (rc1 review): while a coupon's dots wait for their readings, no task dispenses or
     * probes at that place (the probe and the weigh dots refused already; shots, the dot ladder and the touch-off now).
     */
    static void refuseReservedCoupon(DispenseService svc, String place) throws ApiException {
        String why = svc.couponReservation(place);
        if (why != null) {
            throw ApiException.refused(why);
        }
    }

    Object dotLadder(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        DotLadderCalibration.Request req = new DotLadderCalibration.Request();
        req.rotationsDeg = Json.numbers(b, "rotationsDeg", req.rotationsDeg);
        req.repeats = Json.integer(b, "repeats", req.repeats);
        req.pitchMm = Json.num(b, "pitchMm", req.pitchMm);
        req.locationName = Json.str(b, "locationName", null);
        req.primeAtWaste = Json.bool(b, "primeAtWaste", req.primeAtWaste);
        for (double rot : req.rotationsDeg) {
            DispenseEndpoints.checkRotation(rot, 720);
        }
        if (req.repeats < 1 || req.repeats > 50 || req.pitchMm <= 0 || req.pitchMm > 10) {
            throw ApiException.badRequest("repeats must be 1 to 50 and the pitch 0 to 10 mm");
        }
        DispenseConfig cfg = svc.getConfig();
        refuseReservedCoupon(svc, req.locationName == null ? cfg.blankLocationName : req.locationName);
        dispense.startChecks(svc, DispenseService.blankScope(cfg, req.locationName == null ? cfg.blankLocationName
                : req.locationName, req.primeAtWaste, false, null), "Dot ladder");
        ctx.tracker.expect("dot ladder");
        Future<DotLadderCalibration.Result> f = svc.runDotLadder(req);
        return ApiContext.awaitIf(Json.bool(b, "wait", false), f, res -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (DotLadderRow row : res.rows) {
                rows.add(Views.ladderRow(row));
            }
            return Views.map("calibrationId", res.calibrationId, "rows", rows, "notes", res.notes, "run",
                    Views.run(res.run, false));
        }, Views.map("kind", "dot ladder", "dots", req.rotationsDeg.length * req.repeats));
    }

    Object touchOff(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        NeedleTouchOff.Request req = new NeedleTouchOff.Request();
        req.startGapMm = Json.num(b, "startGapMm", req.startGapMm);
        req.stepMm = Json.num(b, "stepMm", req.stepMm);
        req.steps = Json.integer(b, "steps", req.steps);
        req.rotationDeg = Json.num(b, "rotationDeg", req.rotationDeg);
        req.locationName = Json.str(b, "locationName", null);
        req.primeAtWaste = Json.bool(b, "primeAtWaste", req.primeAtWaste);
        req.jumpRatio = Json.num(b, "jumpRatio", req.jumpRatio);
        boolean apply = Json.bool(b, "apply", false);
        if (!Double.isNaN(req.rotationDeg)) {
            DispenseEndpoints.checkRotation(req.rotationDeg, DispenseEndpoints.maxDotRotation(svc.getConfig()));
        }
        // without a pitch: 1.6 mm, or the smallest that leaves room between larger dots (F14)
        req.pitchMm = Json.num(b, "pitchMm", NeedleTouchOff.defaultPitchMm(req, svc.getConfig()));
        if (req.steps < 2 || req.steps > 20 || req.stepMm <= 0 || req.stepMm > 0.5 || req.startGapMm > 2
                || req.pitchMm <= 0 || req.jumpRatio <= 0) {
            throw ApiException.badRequest("steps 2 to 20, step 0 to 0.5 mm, start gap at most 2 mm, pitch and jump ratio above 0");
        }
        String pitch = NeedleTouchOff.pitchProblem(req, svc.getConfig());
        if (pitch != null) {
            throw ApiException.badRequest(pitch);
        }
        double lowest = req.startGapMm - (req.steps - 1) * req.stepMm;
        if (lowest < -0.1) {
            throw ApiException.badRequest(String.format(java.util.Locale.ROOT,
                    "the lowest gap would be %.3f mm, i.e. the needle %.3f mm into the surface if the current offset is right; "
                            + "keep it at -0.1 mm or above", lowest, -lowest));
        }
        DispenseConfig cfg = svc.getConfig();
        refuseReservedCoupon(svc, req.locationName == null ? cfg.blankLocationName : req.locationName);
        dispense.startChecks(svc, DispenseService.blankScope(cfg, req.locationName == null ? cfg.blankLocationName
                : req.locationName, req.primeAtWaste, true, null), "Needle touch-off");
        ctx.tracker.expect("needle touch-off");
        Future<org.openpnp.instancelabs.dispense.DispenseService.TouchOffResult> f = svc.runNeedleTouchOff(req, apply);
        return ApiContext.awaitIf(Json.bool(b, "wait", false), f, res -> Views.map("analysis", res.analysis,
                "previousOffsetMm", res.previousOffsetMm, "newOffsetMm", res.newOffsetMm, "applied", res.applied, "run",
                Views.run(res.run, false)), Views.map("kind", "needle touch-off", "apply", apply, "pitchMm",
                        req.pitchMm));
    }
}
