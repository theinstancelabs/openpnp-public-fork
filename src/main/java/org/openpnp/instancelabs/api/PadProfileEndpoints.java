package org.openpnp.instancelabs.api;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.openpnp.instancelabs.board.EdtRunner;
import org.openpnp.instancelabs.dispense.CalibrationArea;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseDb;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.PadInstances;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.PadTypeResolver;
import org.openpnp.instancelabs.dispense.PadTypeStatus;
import org.openpnp.instancelabs.padprofile.CheckInputs;
import org.openpnp.instancelabs.padprofile.PadInstance;
import org.openpnp.instancelabs.padprofile.ProfileCheck;
import org.openpnp.instancelabs.padprofile.Suggestion;
import org.openpnp.model.Board;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Missing paste profiles (PROFILE_CALIBRATION_DESIGN.md §12.1): the profile check of a plan or of a listed board file
 * with a suggestion per group, applying suggestions (Use an existing pad type, or save a new provisional one), the
 * package mappings and the "paste the dispensable pads anyway" option, the paste in the syringe and the calibration
 * area on the blank.
 */
final class PadProfileEndpoints {
    static final String STALE = "suggestion-stale";

    private final ApiContext ctx;
    private final DispenseEndpoints dispense;

    PadProfileEndpoints(ApiContext ctx, DispenseEndpoints dispense) {
        this.ctx = ctx;
        this.dispense = dispense;
    }

    void register(ApiServer s) {
        s.route("GET", "/api/pad-profile/check", this::check);
        s.route("POST", "/api/pad-profile/apply", this::apply);
        s.route("GET", "/api/package-mappings", r -> mappings());
        s.route("POST", "/api/package-mappings", this::map);
        s.route("POST", "/api/paste", this::paste);
        s.route("GET", "/api/calibration-area", r -> area(ctx.svc().getConfig()));
        s.route("PUT", "/api/calibration-area", this::putArea);
        s.route("POST", "/api/calibration-area/reset", r -> resetArea());
    }

    // ------------------------------------------------------------------------------------------
    // The check and the suggestions
    // ------------------------------------------------------------------------------------------

    /** A check and what it was made of: a plan entry, or a listed board file before planning (board at the origin). */
    static final class Checked {
        PlanStore.Entry entry;
        String boardFile;
        String boardName;
        CheckInputs in;
        ProfileCheck.Result result;

        Map<String, Object> view() {
            Map<String, Object> m = PadProfileViews.check(result, in.pastePartialPackages);
            m.put("source", entry != null ? Views.map("planId", entry.plan.planId, "boardName", entry.plan.boardName,
                    "createdAtMs", entry.plan.createdAtMs) : Views.map("boardFile", boardFile, "boardName", boardName));
            return m;
        }
    }

    Checked checked(DispenseService svc, String planId, String boardFile) throws Exception {
        if ((planId == null) == (boardFile == null)) {
            throw ApiException.badRequest("give 'planId' (a plan) or 'boardFile' (a board file OpenPnP lists)");
        }
        Checked c = new Checked();
        if (planId != null) {
            c.entry = dispense.requirePlan(planId);
            c.in = PadProfileViews.inputs(svc, c.entry.plan);
            c.result = PadProfileViews.check(c.entry, c.in);
            return c;
        }
        Board b = listedBoard(boardFile);
        PadTypeResolver resolver = svc.padTypeResolver();
        boolean disabled = svc.getConfig().includeDisabledPlacements;
        List<PadInstance> pads = EdtRunner.call(() -> PadInstances.fromBoard(b, resolver, disabled),
                DispenseEndpoints.EDT_START_MS);
        c.boardFile = b.getFile().getAbsolutePath();
        c.boardName = b.getName();
        c.in = PadInstances.inputs(svc, pads, false);
        c.result = ProfileCheck.of(c.in);
        return c;
    }

    /** A board file in OpenPnP's list (GET /api/boards files), read on OpenPnP's thread. */
    private Board listedBoard(String path) throws Exception {
        File f = new File(path);
        if (!f.isAbsolute() || !f.getName().endsWith(".board.xml")) {
            throw ApiException.badRequest("'" + path + "' is not an absolute .board.xml path");
        }
        String want = f.getCanonicalPath();
        Board b = EdtRunner.call(() -> {
            for (Board x : ctx.configuration.getBoards()) {
                if (x.getFile() != null && x.getFile().getCanonicalPath().equals(want)) {
                    return x;
                }
            }
            return null;
        }, DispenseEndpoints.EDT_START_MS);
        if (b == null) {
            throw ApiException.refused("'" + path + "' is not a board file OpenPnP lists: upload it, or add it in "
                    + "OpenPnP first");
        }
        return b;
    }

    Object check(ApiServer.Request r) throws Exception {
        return checked(ctx.svc(), r.query("planId", null), r.query("boardFile", null)).view();
    }

    /**
     * Applies the suggestions of some groups (§12.1): {@code use} writes the mappings of the groups whose suggestion is
     * an existing pad type; {@code save} also saves the new and calibrate-existing suggestions as provisional pad type
     * versions with their mappings. Every chosen group's suggestion id must still be the current one (else 409
     * suggestion-stale with the new check), and nothing is written until all are. A plan source is planned again.
     */
    Object apply(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        ctx.requireEngineIdle(svc);
        String choice = Json.str(b, "choice", null);
        if (!"use".equals(choice) && !"save".equals(choice)) {
            throw ApiException.badRequest("'choice' must be 'use' (map to existing pad types) or 'save' (also save "
                    + "new pad types)");
        }
        Checked c = checked(svc, Json.str(b, "planId", null), Json.str(b, "boardFile", null));
        List<ProfileCheck.Group> chosen = chosen(c, b.get("groups"), choice);
        requireCurrent(c, chosen, suggestionIds(b));
        return write(svc, c, chosen, choice, Json.str(b, "operator", null));
    }

    /** 409 suggestion-stale (with the new check) unless every chosen group's suggestion id is the one shown. */
    static void requireCurrent(Checked c, List<ProfileCheck.Group> chosen, Map<String, String> ids)
            throws ApiException {
        for (ProfileCheck.Group g : chosen) {
            String want = ids.get(g.group);
            if (want == null || g.suggestion == null || !want.equals(g.suggestion.id)) {
                throw new ApiException(409, STALE, "The suggestion for " + g.label + " changed since it was shown "
                        + "(pad types, mappings, the tip or the calibration records changed): check the new one.")
                        .with("profileCheck", c.view());
            }
        }
    }

    /**
     * Writes the chosen groups' suggestions (their ids are current) and plans a plan source again: apply's
     * writes, and the pad calibration's step 0 (§7.2: the suggestions saved as provisional types first).
     */
    Map<String, Object> write(DispenseService svc, Checked c, List<ProfileCheck.Group> chosen, String choice,
            String operator) throws Exception {
        DispenseConfig cfg = svc.getConfig();
        DispenseDb db = svc.getDb();
        List<Change> changes = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (ProfileCheck.Group g : chosen) {
            Suggestion s = g.suggestion;
            if (Suggestion.NONE.equals(s.kind)) {
                skipped.add(Views.map("group", g.group, "why", g.shortText));
            }
            else if ("use".equals(choice) && !Suggestion.USE.equals(s.kind)) {
                skipped.add(Views.map("group", g.group, "why", Suggestion.CALIBRATE_EXISTING.equals(s.kind)
                        ? s.name + " is not calibrated for these pads yet: save the suggestion as a provisional "
                                + "version of it, then calibrate it"
                        : "no pad type of this size passes the gate yet: save the suggestion as a new provisional pad "
                                + "type, then calibrate it"));
            }
            else {
                Change x = change(db, cfg, g, s, operator);
                if (x == null) {
                    skipped.add(Views.map("group", g.group, "why", s.name + " already has this plan: calibrate it"));
                }
                else {
                    changes.add(x);
                }
            }
        }
        for (Change x : changes) {
            String problem = x.type == null ? null : x.type.doseProblem();
            if (problem != null) {
                throw ApiException.badRequest("the suggestion for " + x.group.label + " cannot be saved: " + problem);
            }
        }
        List<Map<String, Object>> applied = new ArrayList<>();
        for (Change x : changes) {
            PadType stored = x.type == null ? null : svc.savePadType(x.type);
            if (x.mapping != null) {
                db.mapPackage(x.mapping.packageId, x.mapping.padNames, x.typeKey);
                db.event(null, "INFO", "PAD_TYPE", "package " + x.mapping.packageId + (x.mapping.padNames == null
                        ? "" : " pads " + String.join(",", x.mapping.padNames)) + " mapped to " + x.typeKey
                        + " (suggestion " + x.group.suggestion.id + ")");
            }
            applied.add(Views.map("group", x.group.group, "kind", x.group.suggestion.kind, "typeKey", x.typeKey,
                    "version", stored != null ? stored.version : x.version, "mapping", x.mapping == null ? null
                            : Views.map("packageId", x.mapping.packageId, "padNames", x.mapping.padNames),
                    "padType", stored == null ? null : Views.padType(stored)));
        }
        String newPlanId = null;
        String planError = null;
        if (!applied.isEmpty() && c.entry != null && !svc.isBusy()) {
            try {
                newPlanId = PlanAgain.of(ctx, dispense, c.entry).plan.planId;
            }
            catch (Exception x) {
                planError = "Plan again: " + MachineEndpoints.rootMessage(x);
            }
        }
        return Views.map("applied", applied, "skipped", skipped, "newPlanId", newPlanId, "planStale",
                !applied.isEmpty() && newPlanId == null, "planError", planError);
    }

    /** One suggestion to write: a pad type version (new or calibrate-existing) and / or a mapping. */
    private static final class Change {
        ProfileCheck.Group group;
        PadType type;
        Suggestion.Mapping mapping;
        String typeKey;
        Integer version;
    }

    /** The writes of one suggestion; null when a calibrate-existing type already carries its plan. */
    private static Change change(DispenseDb db, DispenseConfig cfg, ProfileCheck.Group g, Suggestion s,
            String operator) throws Exception {
        Change x = new Change();
        x.group = g;
        x.typeKey = s.typeKey;
        x.version = s.typeVersion;
        x.mapping = s.mapping;
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date());
        if (Suggestion.NEW.equals(s.kind)) {
            if (db.currentPadType(s.typeKey) != null) {
                throw new ApiException(409, STALE, "A pad type '" + s.typeKey + "' was added since the suggestion "
                        + "was shown: check the new one.");
            }
            PadType t = new PadType();
            t.typeKey = s.typeKey;
            t.family = s.family;
            t.name = s.name;
            t.padWMm = s.pad.wMm;
            t.padHMm = s.pad.hMm;
            t.padRoundness = s.pad.roundness;
            t.layout = PadType.Layout.FOOTPRINT_ONLY;
            t.pitchMm = null;
            t.tMm = cfg.pasteThicknessMm;
            t.k = s.amount.k;
            t.status = PadTypeStatus.PROVISIONAL;
            t.notes = s.sentence + " (suggested " + when + ")";
            plan(t, s, cfg);
            t.origin = PadType.ORIGIN_SUGGESTED;
            t.operator = operator;
            x.type = t;
        }
        else if (Suggestion.CALIBRATE_EXISTING.equals(s.kind)) {
            PadType cur = db.currentPadType(s.typeKey);
            if (cur == null || !Objects.equals(cur.version, s.typeVersion)) {
                throw new ApiException(409, STALE, "Pad type '" + s.typeKey + "' changed since the suggestion was "
                        + "shown: check the new one.");
            }
            PadType t = cur.copy();
            boolean planChanged = !Objects.equals(cur.dotSteps, s.dots.steps)
                    || !Objects.equals(cur.dotsPerPad, s.dots.perPad) || !cfg.tip.key.equals(cur.tipKey);
            plan(t, s, cfg);
            if (planChanged || !cur.status.atLeast(PadTypeStatus.PROVISIONAL)) {
                // a plan of its own is not calibrated yet: provisional, no recipe or weighed mass (decision 17)
                t.status = PadTypeStatus.PROVISIONAL;
                t.doseFp = null;
                t.massPerPadMg = null;
            }
            if (!"your plan".equals(s.planFrom)) {
                t.origin = PadType.ORIGIN_SUGGESTED;
            }
            if (!planChanged && cur.status == t.status && Objects.equals(cur.calAreaMm2, t.calAreaMm2)) {
                return null;
            }
            t.operator = operator;
            x.type = t;
        }
        return x;
    }

    /** The suggestion's dot plan on a pad type: its dose, dots per pad, the tip and the opening it was made for. */
    private static void plan(PadType t, Suggestion s, DispenseConfig cfg) {
        t.dotSteps = s.dots.steps;
        t.dotsPerPad = s.dots.perPad;
        t.tipKey = cfg.tip.key;
        t.calAreaMm2 = s.opening.areaMm2;
    }

    /** The chosen groups: a list of ids (unknown ones: stale), or "all" (every group with something to apply). */
    static List<ProfileCheck.Group> chosen(Checked c, JsonElement groups, String choice) throws ApiException {
        List<ProfileCheck.Group> l = new ArrayList<>();
        if (groups != null && groups.isJsonPrimitive() && "all".equals(groups.getAsString())) {
            for (ProfileCheck.Group g : c.result.groups) {
                Suggestion s = g.suggestion;
                if (s != null && ("use".equals(choice) ? Suggestion.USE.equals(s.kind) : !Suggestion.NONE.equals(s.kind))) {
                    l.add(g);
                }
            }
            return l;
        }
        if (groups == null || !groups.isJsonArray() || groups.getAsJsonArray().size() == 0) {
            throw ApiException.badRequest("'groups' must be a list of group ids or \"all\"");
        }
        for (JsonElement e : groups.getAsJsonArray()) {
            ProfileCheck.Group g = c.result.group(e.getAsString());
            if (g == null) {
                throw new ApiException(409, STALE, "Group " + e.getAsString() + " is not in the check any more (its "
                        + "pads changed or have a profile now): check again.").with("profileCheck", c.view());
            }
            if (!l.contains(g)) {
                l.add(g);
            }
        }
        return l;
    }

    static Map<String, String> suggestionIds(JsonObject b) throws ApiException {
        Map<String, String> m = new LinkedHashMap<>();
        JsonElement e = b.get("suggestionIds");
        if (e == null || e.isJsonNull()) {
            return m;
        }
        if (!e.isJsonObject()) {
            throw ApiException.badRequest("'suggestionIds' must be an object {group: suggestion id}");
        }
        for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
            m.put(x.getKey(), x.getValue().isJsonNull() ? null : x.getValue().getAsString());
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------
    // Package mappings and options
    // ------------------------------------------------------------------------------------------

    Object mappings() throws Exception {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Map<String, Object> row : ctx.svc().getDb().query("SELECT p.package_id, p.pad_names, p.type_key,"
                + " p.version, p.created_at FROM package_pad_type p JOIN (SELECT package_id, IFNULL(pad_names, '')"
                + " AS n, MAX(version) AS v FROM package_pad_type GROUP BY package_id, IFNULL(pad_names, '')) m"
                + " ON p.package_id = m.package_id AND IFNULL(p.pad_names, '') = m.n AND p.version = m.v"
                + " WHERE p.type_key IS NOT NULL ORDER BY p.package_id, p.version")) {
            String names = (String) row.get("pad_names");
            l.add(Views.map("packageId", row.get("package_id"), "padNames", names == null ? null
                    : DispenseDb.splitPadNames(names), "typeKey", row.get("type_key"), "version", row.get("version"),
                    "atMs", row.get("created_at")));
        }
        return l;
    }

    /**
     * Maps a package, or some of its pads, to a pad type ({@code typeKey} null clears it), and / or sets whether its
     * dispensable pads are pasted when other pads of the part cannot be (decision 4, {@code pastePartial}).
     */
    Object map(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        ctx.requireEngineIdle(svc);
        DispenseDb db = svc.getDb();
        String pkg = Json.str(b, "packageId", null);
        if (pkg == null || pkg.trim().isEmpty()) {
            throw ApiException.badRequest("'packageId' is required");
        }
        boolean mapping = b.has("typeKey");
        Boolean partial = Json.has(b, "pastePartial") ? Json.bool(b, "pastePartial", false) : null;
        if (!mapping && partial == null) {
            throw ApiException.badRequest("give 'typeKey' (a pad type, or null to clear the mapping) or "
                    + "'pastePartial'");
        }
        List<String> names = Json.strings(b, "padNames");
        String joined = DispenseDb.joinPadNames(names); // checks the names (400)
        String key = Json.str(b, "typeKey", null);
        if (key != null) {
            PadType t = db.currentPadType(key);
            if (t == null || PadTypeEndpoints.RETIRED.equals(t.family)) {
                throw ApiException.badRequest("no pad type '" + key + "'" + (t == null ? "" : " (it is retired)"));
            }
        }
        if (mapping) {
            db.mapPackage(pkg, names, key);
            db.event(null, "INFO", "PAD_TYPE", "package " + pkg + (joined == null ? "" : " pads " + joined)
                    + (key == null ? " mapping cleared" : " mapped to " + key));
        }
        if (partial != null) {
            db.setPastePartial(pkg, partial);
            db.event(null, "INFO", "PAD_TYPE", "package " + pkg + (partial ? ": the dispensable pads are pasted when "
                    + "other pads of the part cannot be" : ": parts with pads the tip cannot dispense get no paste"));
        }
        return Views.map("mapping", mapping ? Views.map("packageId", pkg, "padNames", joined == null ? null
                : DispenseDb.splitPadNames(joined), "typeKey", key) : null, "pastePartial",
                db.pastePartialPackages().contains(pkg), "padTypesFingerprint", svc.padTypeResolver().fingerprint(),
                "note", "Plans made before this are stale: plan again.");
    }

    // ------------------------------------------------------------------------------------------
    // Paste and the calibration area
    // ------------------------------------------------------------------------------------------

    /** "New paste loaded": names the paste and stamps when it was loaded (older dose records stop counting). */
    Object paste(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        ctx.requireEngineIdle(svc);
        DispenseConfig c = svc.getConfig();
        c.paste.key = Json.str(b, "key", c.paste.key);
        c.paste.name = Json.str(b, "name", c.paste.name);
        c.paste.lot = Json.str(b, "lot", null);
        c.paste.loadedAtMs = System.currentTimeMillis();
        svc.updateConfig(c); // validates the key and name (400)
        svc.getDb().event(null, "INFO", "PASTE", "new paste loaded: " + c.paste.name + " (" + c.paste.key + ")"
                + (c.paste.lot == null ? "" : ", lot " + c.paste.lot));
        return svc.getConfig().paste;
    }

    Object putArea(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        ctx.requireEngineIdle(svc);
        DispenseConfig c = svc.getConfig();
        CalibrationArea a = c.calibrationArea;
        a.surfaceName = Json.str(b, "surfaceName", a.surfaceName);
        a.x0Mm = Json.num(b, "x0Mm", null);
        a.y0Mm = Json.num(b, "y0Mm", null);
        a.widthMm = Json.num(b, "widthMm", null);
        a.heightMm = Json.num(b, "heightMm", null);
        List<String> problems = new ArrayList<>();
        for (String p : c.validate()) {
            if (p.startsWith("calibrationArea")) {
                problems.add(p);
            }
        }
        if (!problems.isEmpty()) {
            throw ApiException.badRequest(String.join("; ", problems));
        }
        svc.updateConfig(c);
        return area(svc.getConfig());
    }

    /** "Blank wiped": the whole area is free again. */
    Object resetArea() throws Exception {
        DispenseService svc = ctx.svc();
        ctx.requireEngineIdle(svc);
        DispenseConfig c = svc.getConfig();
        c.calibrationArea.usedMm = 0;
        c.calibrationArea.resetAtMs = System.currentTimeMillis();
        svc.updateConfig(c);
        svc.getDb().event(null, "INFO", "PAD_CALIBRATION", "calibration area wiped (" + c.calibrationArea.heightMm()
                + " mm free)");
        return area(svc.getConfig());
    }

    /** The area as set, where it is in effect (the default at the blank place when unset) and what is left. */
    static Map<String, Object> area(DispenseConfig c) {
        CalibrationArea a = c.calibrationArea;
        CalibrationArea e = a.effective(c);
        return Views.map("surfaceName", a.surfaceName, "x0Mm", a.x0Mm, "y0Mm", a.y0Mm, "widthMm", a.widthMm,
                "heightMm", a.heightMm, "usedMm", a.usedMm, "resetAtMs", a.resetAtMs, "defined", a.isDefined(),
                "leftMm", a.leftMm(), "effective", e == null ? null : Views.map("x0Mm", e.x0Mm, "y0Mm", e.y0Mm,
                        "widthMm", e.widthMm, "heightMm", e.heightMm), "default", Views.map("widthMm",
                                CalibrationArea.DEFAULT_WIDTH_MM, "heightMm", CalibrationArea.DEFAULT_HEIGHT_MM,
                                "place", c.blankLocationName));
    }
}
