package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DoseRecord;
import org.openpnp.instancelabs.dispense.DoseRecords;
import org.openpnp.instancelabs.dispense.PadCalibrationRecord;
import org.openpnp.instancelabs.padprofile.DoseModel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Pad calibrations (PROFILE_CALIBRATION_DESIGN.md §12.2): the before-start summary, the start, the job
 * (status, the readings, cancel, crops), the history of pad calibrations and dose records, and the
 * weigh coupons whose readings are still to be typed. A STOP stops the running job first
 * ({@link MachineEndpoints}).
 */
final class PadCalibrationEndpoints {
    private static final String JOB = "(pc-[0-9]+)";

    private final ApiContext ctx;
    private final DispenseEndpoints dispense;
    private final PadProfileEndpoints profile;

    PadCalibrationEndpoints(ApiContext ctx, DispenseEndpoints dispense, PadProfileEndpoints profile) {
        this.ctx = ctx;
        this.dispense = dispense;
        this.profile = profile;
    }

    void register(ApiServer s) {
        s.route("GET", "/api/pad-calibrations/preview", this::preview);
        s.route("GET", "/api/pad-calibrations/current", r -> current());
        s.route("POST", "/api/pad-calibrations", this::start);
        s.route("GET", "/api/pad-calibrations", this::list);
        s.route("GET", "/api/pad-calibrations/" + JOB, r -> requireJob(r.group(1)).view());
        s.route("POST", "/api/pad-calibrations/" + JOB + "/answer", this::answer);
        s.route("POST", "/api/pad-calibrations/" + JOB + "/cancel", r -> cancel(r.group(1)));
        s.route("GET", "/api/pad-calibrations/" + JOB + "/crops/([A-Za-z0-9-]{1,60})\\.png", this::crop);
        s.route("GET", "/api/dose-records", this::doseRecords);
        s.route("GET", "/api/coupons/pending", r -> pendingView(ctx.svc()));
        s.route("POST", "/api/coupons/([0-9]{1,12})/reading", this::reading);
    }

    // ------------------------------------------------------------------------------------------
    // The request
    // ------------------------------------------------------------------------------------------

    /** The POST body (§12.2). */
    static PadCalibrationJob.Request request(JsonObject b) throws ApiException {
        PadCalibrationJob.Request q = new PadCalibrationJob.Request();
        q.planId = Json.str(b, "planId", null);
        q.boardFile = Json.str(b, "boardFile", null);
        q.groups = b.has("groups") && !b.get("groups").isJsonNull() ? b.get("groups") : null;
        q.typeKeys = Json.has(b, "typeKeys") ? Json.strings(b, "typeKeys") : null;
        q.steps = Json.has(b, "steps") ? Json.strings(b, "steps") : null;
        q.doseSteps = Json.integer(b, "doseSteps", null);
        q.weighDots = Json.integer(b, "weighDots", null);
        q.save = Json.bool(b, "save", true);
        q.suggestionIds = PadProfileEndpoints.suggestionIds(b);
        JsonElement reg = b.get("regulator");
        if (reg != null && !reg.isJsonNull()) {
            if (!reg.isJsonObject()) {
                throw ApiException.badRequest("'regulator' must be {psi, atMs}: the reading you attested");
            }
            q.regulatorPsi = Json.num(reg.getAsJsonObject(), "psi", null);
            Double at = Json.num(reg.getAsJsonObject(), "atMs", null);
            q.regulatorAtMs = at == null ? null : at.longValue();
        }
        Double life = Json.num(b, "pasteLifeAtMs", null);
        q.pasteLifeAtMs = life == null ? null : life.longValue();
        q.operator = Json.str(b, "operator", null);
        if (Json.has(b, "demotes")) {
            q.demotes = Json.strings(b, "demotes");
        }
        return q;
    }

    /** The preview's query: the same fields, lists comma-separated, suggestionIds as group:id pairs. */
    static PadCalibrationJob.Request request(ApiServer.Request r) throws ApiException {
        PadCalibrationJob.Request q = new PadCalibrationJob.Request();
        q.planId = r.query("planId", null);
        q.boardFile = r.query("boardFile", null);
        String groups = r.query("groups", null);
        if (groups != null) {
            if ("all".equals(groups)) {
                q.groups = new JsonPrimitive("all");
            }
            else {
                JsonArray a = new JsonArray();
                for (String g : list(groups)) {
                    a.add(new JsonPrimitive(g));
                }
                q.groups = a;
            }
        }
        String keys = r.query("typeKeys", null);
        q.typeKeys = keys == null ? null : list(keys);
        String steps = r.query("steps", null);
        q.steps = steps == null ? null : list(steps);
        q.doseSteps = intOf(r.query("doseSteps", null), "doseSteps");
        q.weighDots = intOf(r.query("weighDots", null), "weighDots");
        q.save = !"false".equals(r.query("save", "true"));
        String ids = r.query("suggestionIds", null);
        q.checkIds = ids != null;
        for (String pair : ids == null ? Collections.<String>emptyList() : list(ids)) {
            int at = pair.indexOf(':');
            if (at > 0) {
                q.suggestionIds.put(pair.substring(0, at), pair.substring(at + 1));
            }
        }
        // the preview says what it would refuse: the attestations are the browser's, given when it has them
        Double psi = numOf(r.query("regulatorPsi", null), "regulatorPsi");
        Double at = numOf(r.query("regulatorAtMs", null), "regulatorAtMs");
        Double life = numOf(r.query("pasteLifeAtMs", null), "pasteLifeAtMs");
        q.regulatorPsi = psi;
        q.regulatorAtMs = at == null ? null : at.longValue();
        q.pasteLifeAtMs = life == null ? null : life.longValue();
        String demotes = r.query("demotes", null);
        if (demotes != null) {
            q.demotes = list(demotes);
        }
        return q;
    }

    private static List<String> list(String s) {
        List<String> l = new ArrayList<>();
        for (String x : s.split(",")) {
            if (!x.trim().isEmpty()) {
                l.add(x.trim());
            }
        }
        return l;
    }

    private static Integer intOf(String s, String name) throws ApiException {
        try {
            return s == null ? null : Integer.valueOf(s.trim());
        }
        catch (NumberFormatException e) {
            throw ApiException.badRequest("'" + name + "' must be a whole number");
        }
    }

    private static Double numOf(String s, String name) throws ApiException {
        try {
            return s == null ? null : Double.valueOf(s.trim());
        }
        catch (NumberFormatException e) {
            throw ApiException.badRequest("'" + name + "' must be a number");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Preview, start, the job
    // ------------------------------------------------------------------------------------------

    /** GET /api/pad-calibrations/preview: §7.9 (what it would do, and what it would refuse). */
    Object preview(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.availableSvc();
        return PadCalibrationJob.prepare(ctx, profile, dispense, svc, request(r)).preview();
    }

    /**
     * POST /api/pad-calibrations: the checks of §12.2 in order (409 with the text; 409 suggestion-stale with
     * the new check), then the suggestions saved as provisional types (§7.2 step 0), then the job.
     */
    Object start(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        PadCalibrationJob job = PadCalibrationJob.prepare(ctx, profile, dispense, svc, request(b));
        if (job.refusal != null) {
            ApiException e = new ApiException(409, job.refusalCode, job.refusal).with("preview", job.preview());
            if (PadProfileEndpoints.STALE.equals(job.refusalCode) && job.checked != null) {
                e.with("profileCheck", job.checked.view());
            }
            throw e;
        }
        job.save();
        ctx.padCalJobs.start(job);
        Map<String, Object> preview = job.preview();
        Map<String, Object> accepted = Views.map("kind", "pad calibration", "id", job.id, "steps", preview.get("steps"),
                "preview", preview);
        if (!Json.bool(b, "wait", false)) {
            accepted.put("started", true);
            return ApiServer.Response.json(202, accepted);
        }
        accepted.put("started", true);
        boolean finished = await(job);
        accepted.put("finished", finished);
        accepted.put("result", job.view());
        return finished ? accepted : ApiServer.Response.json(202, accepted);
    }

    /** Waits (bounded) until the job ends or waits for the operator. */
    private static boolean await(PadCalibrationJob job) throws InterruptedException {
        long until = System.currentTimeMillis() + ApiContext.MAX_WAIT_MS;
        while (System.currentTimeMillis() < until) {
            if (job.ended() || PadCalibrationJob.AWAITING.equals(job.state)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    Object current() {
        PadCalibrationJob j = ctx.padCalJobs.current();
        return j == null ? null : j.view();
    }

    private PadCalibrationJob requireJob(String id) throws ApiException {
        PadCalibrationJob j = ctx.padCalJobs.get(id);
        if (j == null) {
            throw ApiException.notFound("no pad calibration " + id + " in this session (they are kept until "
                    + "OpenPnP restarts; the coupons waiting for readings are in /api/coupons/pending)");
        }
        return j;
    }

    /**
     * POST /api/pad-calibrations/{id}/answer: {step: "readings", coupons: [{place, fullG, emptyG}], confirm?}
     * or {step: "readings", skip: true}. 409 implausible / late (save again with confirm), 409 when the job
     * is not waiting for readings.
     */
    Object answer(ApiServer.Request r) throws Exception {
        PadCalibrationJob j = requireJob(r.group(1));
        JsonObject b = r.json();
        String step = Json.str(b, "step", "readings");
        if (!"readings".equals(step)) {
            throw new ApiException(409, "wrong-step", "the pad calibration asks for nothing but the readings of its "
                    + "coupons");
        }
        List<PadCalibrationJob.Reading> l = new ArrayList<>();
        boolean skip = Json.bool(b, "skip", false);
        if (!skip) {
            JsonElement cs = b.get("coupons");
            if (cs == null || !cs.isJsonArray() || cs.getAsJsonArray().size() == 0) {
                throw ApiException.badRequest("'coupons' must be [{place, fullG, emptyG}] (grams), or skip: true");
            }
            for (JsonElement e : cs.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    throw ApiException.badRequest("'coupons' must be [{place, fullG, emptyG}] (grams)");
                }
                JsonObject o = e.getAsJsonObject();
                String place = Json.str(o, "place", null);
                if (place == null) {
                    throw ApiException.badRequest("every reading names its coupon 'place'");
                }
                l.add(new PadCalibrationJob.Reading(place, Json.reqNum(o, "fullG"), Json.reqNum(o, "emptyG")));
            }
        }
        j.answer(l, Json.bool(b, "confirm", false), skip);
        if (Json.bool(b, "wait", false)) {
            long until = System.currentTimeMillis() + ApiContext.MAX_WAIT_MS;
            while (!j.ended() && System.currentTimeMillis() < until) {
                Thread.sleep(50);
            }
        }
        return j.view();
    }

    Object cancel(String id) throws Exception {
        PadCalibrationJob j = requireJob(id);
        j.cancel();
        return j.view();
    }

    Object crop(ApiServer.Request r) throws Exception {
        byte[] png = requireJob(r.group(1)).crop(r.group(2));
        if (png == null) {
            throw ApiException.notFound("no crop " + r.group(2));
        }
        return ApiServer.Response.bytes(200, "image/png", png);
    }

    /** GET /api/pad-calibrations?typeKey&limit: the pad_calibrations rows, newest first. */
    Object list(ApiServer.Request r) throws Exception {
        List<PadCalibrationRecord> l = ctx.svc().getDb().padCalibrations(r.query("typeKey", null),
                r.queryInt("limit", 50, 1, 500));
        return l;
    }

    // ------------------------------------------------------------------------------------------
    // Dose records and the coupons waiting for their readings
    // ------------------------------------------------------------------------------------------

    /** GET /api/dose-records?steps&freshOnly&limit: rows (newest first) with whether they are evidence now and why not. */
    Object doseRecords(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.svc();
        DispenseConfig c = svc.getConfig();
        Integer steps = intOf(r.query("steps", null), "steps");
        boolean freshOnly = "true".equals(r.query("freshOnly", "false"));
        int limit = r.queryInt("limit", 200, 1, 2000);
        long now = System.currentTimeMillis();
        DoseRecord.Epochs ep = new DoseRecord.Epochs(svc.getControllerConnectedAtMs(), svc.isSimulatorConfirmed());
        List<DoseRecord> all = svc.getDb().doseRecords(steps, 0);
        Collections.reverse(all);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DoseRecord d : all) {
            String why = d.whyStale(now, c, ep);
            if (freshOnly && why != null) {
                continue;
            }
            Map<String, Object> m = Views.map("id", d.id, "createdAtMs", d.createdAtMs, "kind", d.kind, "steps", d.steps,
                    "turn", DoseModel.turnNoun(d.steps), "n", d.n, "found", d.found, "misses", d.misses, "hops", d.hops,
                    "threads", d.threads, "areaMeanMm2", d.areaMeanMm2, "residCv", d.residCv, "diameterMeanMm",
                    d.diameterMeanMm, "diameterP95Mm", d.diameterP95Mm, "drift", d.drift, "landingMeanMm",
                    d.landingMeanMm, "pass", d.pass, "why", d.why, "couponPlace", d.couponPlace, "couponRecordId",
                    d.couponRecordId, "totalMg", d.totalMg, "mgPerDot", d.mgPerDot, "mgPerStep", d.mgPerStep,
                    "confirmedBy", d.confirmedBy, "method", d.method, "simulated", d.simulated, "jobId", d.jobId,
                    "fresh", why == null, "whyStale", why);
            out.add(m);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    /** The weigh coupons whose readings are still to be typed and can still be (§7.5, §7.8). */
    static List<Map<String, Object>> pendingView(DispenseService svc) {
        List<Map<String, Object>> l = new ArrayList<>();
        DispenseConfig c = svc.getConfig();
        long now = System.currentTimeMillis();
        long age = (long) (c.padCalibration.doseRecordMaxAgeH * 3600e3);
        DoseRecord.Epochs ep = new DoseRecord.Epochs(svc.getControllerConnectedAtMs(), svc.isSimulatorConfirmed());
        try {
            for (DoseRecord d : svc.getDb().pendingCoupons(now - age)) {
                if (!DispenseService.weighable(d, now, c, ep)) {
                    continue;
                }
                int found = d.found == null ? d.n : d.found;
                l.add(Views.map("recordId", d.id, "place", d.couponPlace, "steps", d.steps, "turn", DoseModel.turnNoun(
                        d.steps), "dots", d.n, "found", found, "expectedMg", Math.round(found * DoseModel.massMg(
                                d.steps)), "laidAtMs", d.createdAtMs, "staleAtMs", d.createdAtMs + age, "jobId",
                        d.jobId));
            }
        }
        catch (Exception e) {
            l.add(Views.map("error", "the weigh coupons could not be read: " + e.getMessage()));
        }
        return l;
    }

    /**
     * POST /api/coupons/{recordId}/reading {fullG, emptyG, confirm?}: the readings of a coupon whose job is
     * gone (stopped, cancelled, OpenPnP restarted). A coupon its job waits for is answered there.
     */
    Object reading(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.svc();
        long id = Long.parseLong(r.group(1));
        JsonObject b = r.json();
        DoseRecord coupon = null;
        for (Map<String, Object> p : pendingView(svc)) {
            if (Long.valueOf(id).equals(p.get("recordId"))) {
                coupon = svc.getDb().doseRecord(id);
            }
        }
        if (coupon == null) {
            throw ApiException.notFound("no weigh coupon " + id + " waiting for its readings (weighed, skipped, or no "
                    + "evidence any more)");
        }
        PadCalibrationJob j = ctx.padCalJobs.current();
        if (j != null && PadCalibrationJob.AWAITING.equals(j.state) && j.id.equals(coupon.jobId)) {
            throw ApiException.refused("the pad calibration " + j.id + " is waiting for these readings: type them "
                    + "there (Calibrate › Pad calibration)");
        }
        double full = Json.reqNum(b, "fullG");
        double empty = Json.reqNum(b, "emptyG");
        PadCalibrationJob.check(svc, coupon, full, empty, Json.bool(b, "confirm", false));
        DoseRecord m = PadCalibrationJob.massRow(svc, coupon, full, empty, "typed", Json.str(b, "operator", null),
                null);
        DispenseConfig c = svc.getConfig();
        long now = System.currentTimeMillis();
        DoseRecord.Epochs ep = new DoseRecord.Epochs(svc.getControllerConnectedAtMs(), svc.isSimulatorConfirmed());
        boolean confirmed = DoseRecords.evidence(svc.getDb().doseRecords(null, now - (long) (c.padCalibration
                .doseRecordMaxAgeH * 3600e3)), c, ep, now).get(m.steps).massConfirmed;
        return Views.map("record", Views.map("id", m.id, "place", m.couponPlace, "steps", m.steps, "totalMg", m.totalMg,
                "mgPerDot", m.mgPerDot, "mgPerStep", m.mgPerStep, "confirmedBy", m.confirmedBy, "weighDelayS",
                m.weighDelayS), "confirmed", confirmed, "text", String.format(Locale.ROOT, "%s: %.0f mg of paste, %.3f "
                        + "mg per %s dot%s", m.couponPlace, m.totalMg, m.mgPerDot, DoseModel.turnName(m.steps),
                        confirmed ? "; the mass is confirmed" : "; a second weighing confirms it"));
    }
}
