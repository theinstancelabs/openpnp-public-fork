package org.openpnp.instancelabs.api;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseDb;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DoseRecord;
import org.openpnp.instancelabs.dispense.DoseRecords;
import org.openpnp.instancelabs.dispense.PadCalibrationRecord;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.TipSetup;
import org.openpnp.instancelabs.padprofile.DoseModel;
import org.openpnp.instancelabs.padprofile.Texts;
import org.openpnp.instancelabs.padprofile.Tip;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Pad calibrations (PROFILE_CALIBRATION_DESIGN.md §12.2): the before-start summary, the start, the job
 * (status, the readings, cancel, crops), the history of pad calibrations and dose records, and the
 * weigh coupons whose readings are still to be typed. A STOP stops the running job first
 * ({@link MachineEndpoints}).
 *
 * <p>Tip setups (§22.3, §22.5, §22.6): the jobs of records only ({@code measure}: the tip's dots, where it lands), a
 * record's "calibrated for" label and its edit, "Made with another tip", and the list per setup with labels, photos
 * and flags.</p>
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
        s.route("PUT", "/api/pad-calibrations/([0-9]{1,12})/label", this::putLabel);
        s.route("POST", "/api/pad-calibrations/([0-9]{1,12})/wrong-tip", this::wrongTip);
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
        q.measure = Json.str(b, "measure", null);
        q.wide = Json.bool(b, "wide", false);
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
        q.measure = r.query("measure", null);
        q.wide = "true".equals(r.query("wide", "false"));
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

    /**
     * GET /api/pad-calibrations?typeKey&setup&limit: the pad_calibrations rows, newest first (§22.6), each with its
     * setup and its name, how it was made, its "calibrated for" label, its pictures, its flags (wrong-tip) and whether
     * its job is still in this session's memory (its result card can open).
     */
    Object list(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.svc();
        DispenseDb db = svc.getDb();
        List<PadCalibrationRecord> l = db.padCalibrations(r.query("typeKey", null), r.query("setup", null),
                r.queryInt("limit", 50, 1, 500));
        Set<String> flagged = db.flaggedJobs();
        Map<String, String> names = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (PadCalibrationRecord x : l) {
            String setup = Tip.setupOf(x.setupKey);
            String name = names.computeIfAbsent(setup, k -> {
                try {
                    TipSetup t = db.currentTipSetup(k);
                    return t == null ? k : t.name;
                }
                catch (SQLException e) {
                    return k;
                }
            });
            Object[] label = label(svc, x);
            Map<String, Object> m = Views.padCalibration(x);
            m.put("setupKey", setup);
            m.put("setupName", name);
            m.put("method", x.method == null ? PadCalibrationRecord.METHOD_AUTO : x.method);
            m.put("label", label[0]);
            m.put("labelAuto", label[1]);
            m.put("photos", Views.photos(db.photos(null, x.id, null, null, true, 50)));
            List<String> flags = new ArrayList<>();
            if (flagged.contains(x.jobId)) {
                flags.add("wrong-tip");
            }
            m.put("flags", flags);
            m.put("jobInMemory", x.jobId != null && ctx.padCalJobs.get(x.jobId) != null);
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // The "calibrated for" label and "Made with another tip" (§22.3, §22.5)
    // ------------------------------------------------------------------------------------------

    /**
     * A record's "calibrated for" label {text, auto}: its newest {@code cal_labels} row, else (a record from before
     * schema 3) the automatic text computed now, auto.
     */
    static Object[] label(DispenseService svc, PadCalibrationRecord r) throws SQLException {
        PadCalibrationRecord.Label l = svc.getDb().calLabel(r.id);
        if (l != null) {
            return new Object[] {l.label, l.auto};
        }
        return new Object[] {computedLabel(svc, r), true};
    }

    /**
     * The automatic label of a record (§22.5): its first automatic row when it has one; a record from before schema 3
     * gets the text computed from its type, its setup (§22.1) and the pressure and paste of its evidence (else the
     * profile's and the loaded paste).
     */
    static String computedLabel(DispenseService svc, PadCalibrationRecord r) throws SQLException {
        DispenseDb db = svc.getDb();
        for (Map<String, Object> row : db.query("SELECT label FROM cal_labels WHERE pad_calibration_id = ? AND auto = 1 "
                + "ORDER BY id LIMIT 1", r.id)) {
            return String.valueOf(row.get("label"));
        }
        DispenseConfig c = svc.getConfig();
        String setup = Tip.setupOf(r.setupKey);
        TipSetup t = db.currentTipSetup(setup);
        PadType type = null;
        for (PadType h : db.padTypeHistory(r.typeKey)) {
            if (r.versionTo != null && h.version == r.versionTo || type == null) {
                type = h;
            }
        }
        PadType now = db.currentPadType(r.typeKey, setup);
        String family = now != null ? now.family : type == null ? null : type.family;
        String name = now != null ? now.name : type == null ? r.typeKey : type.name;
        double psi = c.profile.pressurePsi;
        String pasteName = c.paste.name;
        String lot = c.paste.lot;
        for (Long id : r.evidence) {
            DoseRecord d = db.doseRecord(id);
            if (d != null && d.pressurePsi > 0) {
                psi = d.pressurePsi;
                if (d.pasteKey != null && !d.pasteKey.equals(c.paste.key)) {
                    pasteName = d.pasteKey; // another paste than the loaded one: its key is all the record knows
                    lot = null;
                }
                break;
            }
        }
        return Texts.calLabel(family, name, t == null ? setup : t.name, t == null ? TipSetup.DEFAULT_DISPENSER
                : t.dispenser, psi, pasteName, lot);
    }

    /**
     * PUT /api/pad-calibrations/{id}/label {label} or {auto: true}: a new label row (§22.5); 1 to 160 characters after
     * trimming, control characters removed (400). A label is never part of a fingerprint: no plan goes stale.
     */
    Object putLabel(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        DispenseDb db = svc.getDb();
        long id = Long.parseLong(r.group(1));
        PadCalibrationRecord rec = db.padCalibration(id);
        if (rec == null) {
            throw ApiException.notFound("no pad calibration record " + id);
        }
        boolean auto = Json.bool(b, "auto", false);
        String text;
        if (auto) {
            text = computedLabel(svc, rec);
        }
        else {
            if (!Json.has(b, "label")) {
                throw ApiException.badRequest("give 'label' (the text) or auto: true (the automatic text again)");
            }
            text = DispenseService.cleanLabel(Json.str(b, "label", null)); // 400: 1 to 160 characters
        }
        PadCalibrationRecord.Label l = db.insertCalLabel(id, text, auto, Json.str(b, "operator", null));
        db.event(null, "INFO", "PCAL", "label of calibration " + id + (auto ? " back to the automatic text" : " edited")
                + ": " + text);
        return Views.map("id", id, "label", l.label, "auto", l.auto, "atMs", l.createdAtMs);
    }

    /**
     * POST /api/pad-calibrations/{id}/wrong-tip {operator?}: "Made with another tip" (§22.3): the record's job (a
     * by-hand record: the jobs of its evidence) is flagged, its records leave the setup's evidence and dot table, its
     * waiting coupons get skip rows, and the versions it wrote give way to the setup's previous own ones.
     */
    Object wrongTip(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        ctx.requireEngineIdle(svc);
        long id = Long.parseLong(r.group(1));
        PadCalibrationRecord rec = svc.getDb().padCalibration(id);
        if (rec == null) {
            throw ApiException.notFound("no pad calibration record " + id);
        }
        PadCalibrationJob j = ctx.padCalJobs.current();
        if (j != null && !j.ended() && j.id.equals(rec.jobId)) {
            throw ApiException.refused("The pad calibration " + j.id + " has not ended: cancel it first (Calibrate › "
                    + "Pad calibration).");
        }
        List<Map<String, Object>> restored = new ArrayList<>();
        for (PadType t : svc.markWrongTip(rec.jobId, Json.str(b, "operator", null))) {
            restored.add(Views.map("typeKey", t.typeKey, "version", t.version, "status", t.status == null ? null
                    : t.status.dbName));
        }
        return Views.map("flagged", rec.jobId, "restored", restored, "text", restored.isEmpty()
                ? "Calibration " + id + " is marked as made with another tip; no pad type version had to be restored."
                : "Calibration " + id + " is marked as made with another tip: " + restored.size() + " pad type "
                        + (restored.size() == 1 ? "version was" : "versions were") + " restored.");
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
            Tip tip = PadCalibrationJob.mountedTip(svc);
            for (DoseRecord d : svc.getDb().pendingCoupons(now - age)) {
                if (!DispenseService.weighable(d, now, c, ep)) {
                    continue;
                }
                int found = d.found == null ? d.n : d.found;
                l.add(Views.map("recordId", d.id, "place", d.couponPlace, "steps", d.steps, "turn", DoseModel.turnNoun(
                        d.steps), "dots", d.n, "found", found, "expectedMg", Math.round(found
                                * PadCalibrationJob.countMgPerDot(tip, d.steps)), "laidAtMs", d.createdAtMs, "staleAtMs",
                        d.createdAtMs + age, "jobId", d.jobId));
            }
        }
        catch (Exception e) {
            l.add(Views.map("error", "the weigh coupons could not be read: " + e.getMessage()));
        }
        return l;
    }

    /**
     * POST /api/coupons/{recordId}/reading {fullG, emptyG, confirm?} or {skip: true}: the readings of a coupon
     * whose job is gone (stopped, cancelled, OpenPnP restarted), or none (§7.5's "skip them": the coupon is free
     * again). A coupon its job waits for is answered there.
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
        if (Json.bool(b, "skip", false)) {
            DoseRecord m = PadCalibrationJob.skipRow(svc, coupon, Json.str(b, "operator", null), null);
            return Views.map("record", Views.map("id", m.id, "place", m.couponPlace, "steps", m.steps, "skipped",
                    true), "confirmed", false, "text", String.format(Locale.ROOT, "%s: weighing skipped; wipe its %d "
                            + "%s dots off before it is used again", m.couponPlace, coupon.n,
                            DoseModel.turnName(m.steps)));
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
                .doseRecordMaxAgeH * 3600e3)), c, ep, now, svc.getDb().flaggedJobs()).get(m.steps).massConfirmed;
        return Views.map("record", Views.map("id", m.id, "place", m.couponPlace, "steps", m.steps, "totalMg", m.totalMg,
                "mgPerDot", m.mgPerDot, "mgPerStep", m.mgPerStep, "confirmedBy", m.confirmedBy, "weighDelayS",
                m.weighDelayS), "confirmed", confirmed, "text", String.format(Locale.ROOT, "%s: %.0f mg of paste, %.3f "
                        + "mg per %s dot%s", m.couponPlace, m.totalMg, m.mgPerDot, DoseModel.turnName(m.steps),
                        confirmed ? "; the mass is confirmed" : "; a second weighing confirms it"));
    }
}
