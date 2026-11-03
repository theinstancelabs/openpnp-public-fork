package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.openpnp.instancelabs.dispense.Calibration;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseDb;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DotMath;
import org.openpnp.instancelabs.dispense.DotSizer;
import org.openpnp.instancelabs.dispense.PadCalibrationRecord;
import org.openpnp.instancelabs.dispense.PadInstances;
import org.openpnp.instancelabs.dispense.PadRule;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.PadTypeStatus;
import org.openpnp.instancelabs.padprofile.DoseEvidence;
import org.openpnp.instancelabs.padprofile.DoseModel;

import com.google.gson.JsonObject;

/**
 * Pad types for the Profiles tab (plan §5.7): grouped by package family, user-addable, every edit
 * appends a version (nothing is overwritten), status not-started -> provisional -> characterized
 * -> validated. "Delete" retires a type: a new version in the "Retired" family with no package
 * pattern and status not-started, so it can never be matched or dispensed; its history stays.
 *
 * <p>A type may carry its own dose (PROFILE_CALIBRATION_DESIGN.md §5.1, §12.7): a turn per dot the tip takes and dots
 * per pad that give every pad at least half a turn. Raising such a type to characterized needs the evidence engine-side
 * (409 status-evidence), and an edit of the pad or the dose drops the calibration it had (decision 17).</p>
 */
final class PadTypeEndpoints {
    /** Display order of the families (plan §5.7); others follow alphabetically, Retired last. */
    static final List<String> FAMILY_ORDER = Arrays.asList("Chip (2-terminal SMD)", "Small-outline discretes",
            "Leaded ICs", "No-lead ICs", "Custom");
    static final String RETIRED = "Retired";
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");

    private final ApiContext ctx;

    PadTypeEndpoints(ApiContext ctx) {
        this.ctx = ctx;
    }

    void register(ApiServer s) {
        s.route("GET", "/api/pad-types", r -> list());
        s.route("POST", "/api/pad-types", this::create);
        s.route("GET", "/api/pad-types/([a-z0-9-]+)", this::get);
        s.route("PUT", "/api/pad-types/([a-z0-9-]+)", this::update);
        s.route("DELETE", "/api/pad-types/([a-z0-9-]+)", this::retire);
        s.route("PUT", "/api/pad-types/([a-z0-9-]+)/rule", this::rule);
    }

    Object list() throws Exception {
        DispenseService svc = ctx.svc();
        Calibration cal = svc.getCurrentCalibration();
        Map<String, PadRule> rules = svc.getDb().currentPadRules();
        Map<String, List<Map<String, Object>>> byFamily = new LinkedHashMap<>();
        for (String f : FAMILY_ORDER) {
            byFamily.put(f, new ArrayList<>());
        }
        List<PadType> types = svc.getPadTypes();
        List<String> others = new ArrayList<>();
        for (PadType t : types) {
            String f = t.family == null || t.family.trim().isEmpty() ? "Custom" : t.family;
            if (!byFamily.containsKey(f) && !RETIRED.equals(f) && !others.contains(f)) {
                others.add(f);
            }
        }
        others.sort(String.CASE_INSENSITIVE_ORDER);
        for (String f : others) {
            byFamily.put(f, new ArrayList<>());
        }
        byFamily.put(RETIRED, new ArrayList<>());
        for (PadType t : types) {
            String f = t.family == null || t.family.trim().isEmpty() ? "Custom" : t.family;
            byFamily.get(f).add(row(svc, t, cal, rules.get(t.typeKey)));
        }
        List<Map<String, Object>> families = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byFamily.entrySet()) {
            if (e.getValue().isEmpty() && (RETIRED.equals(e.getKey()) || !FAMILY_ORDER.contains(e.getKey()))) {
                continue;
            }
            families.add(Views.map("family", e.getKey(), "retired", RETIRED.equals(e.getKey()), "types", e.getValue()));
        }
        return Views.map("families", families, "statuses", statusNames(), "layouts", layoutNames(),
                "minPadTypeStatus", svc.getConfig().minPadTypeStatus, "calibration", Views.calibration(cal));
    }

    /**
     * One Profiles row: the current version plus its dot plan (profile dots per pad; the dose is the
     * dispense profile's, the stencil-equivalent volume is for information).
     */
    private Map<String, Object> row(DispenseService svc, PadType t, Calibration cal, PadRule rule) throws Exception {
        Map<String, Object> m = Views.padType(t);
        double area = DotMath.padAreaMm2(t.padWMm, t.padHMm, t.padRoundness);
        double v = DotMath.targetVolumeMm3(area, t.tMm, t.k);
        m.put("padAreaMm2", area);
        m.put("targetVolumeMm3", v);
        m.put("targetMassMg", v * svc.getConfig().pasteDensityMgMm3);
        DispenseConfig c = svc.getConfig();
        m.put("dotsPerPad", t.dotsPerPad != null ? t.dotsPerPad
                : DotSizer.layout(t.padWMm, t.padHMm, area, rule).size());
        m.put("rotationPerDotDeg", t.dotSteps != null ? t.dotSteps / c.bStepsPerDeg : c.profile.netDeg(c));
        m.put("profileName", c.profile.name);
        m.put("rule", rule == null ? null : Views.map("maxDots", rule.maxDots, "fitFactor", rule.fitFactor,
                "largestSingleDotPadMm2", rule.largestSingleDotPadMm2, "version", rule.version));
        List<PadType> history = svc.getDb().padTypeHistory(t.typeKey);
        m.put("versions", history.size());
        Long validatedAt = null;
        for (PadType h : history) {
            if (h.status == PadTypeStatus.VALIDATED
                    && (validatedAt == null || h.createdAtMs > validatedAt)) {
                validatedAt = h.createdAtMs;
            }
        }
        m.put("lastValidatedAtMs", validatedAt);
        List<PadCalibrationRecord> cals = svc.getDb().padCalibrations(t.typeKey, 1);
        m.put("lastCalibration", cals.isEmpty() ? null : cals.get(0));
        m.put("dispensable", t.status.atLeast(svc.getConfig().minPadTypeStatus()));
        return m;
    }

    Object get(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.svc();
        PadType t = require(svc, r.group(1));
        List<Map<String, Object>> history = new ArrayList<>();
        for (PadType h : svc.getDb().padTypeHistory(t.typeKey)) {
            history.add(Views.padType(h));
        }
        return Views.map("current", Views.padType(t), "history", history);
    }

    Object create(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        PadType t = new PadType();
        t.family = Json.str(b, "family", "Custom");
        t.name = Json.str(b, "name", null);
        if (t.name == null) {
            throw ApiException.badRequest("a name is required");
        }
        String key = Json.str(b, "typeKey", null);
        if (key == null) {
            key = uniqueKey(svc, slug(familyPrefix(t.family) + "-" + t.name));
        }
        else if (svc.getDb().currentPadType(key) != null) {
            throw ApiException.refused("pad type '" + key + "' already exists; edit it instead");
        }
        if (!KEY.matcher(key).matches()) {
            throw ApiException.badRequest("typeKey must be lower-case letters, digits and '-' (max 48)");
        }
        t.typeKey = key;
        t.tMm = svc.getConfig().pasteThicknessMm;
        t.layout = null; // PadType defaults to two-terminal; a new type picks its layout below
        t.origin = PadType.ORIGIN_MANUAL;
        if (Json.has(b, "origin")) {
            // a suggestion edited before saving keeps its origin (§4.2: a later calibration keeps the edited plan)
            t.origin = Json.str(b, "origin", null);
            if (!PadType.ORIGIN_MANUAL.equals(t.origin) && !PadType.ORIGIN_SUGGESTED.equals(t.origin)) {
                throw ApiException.badRequest("origin of a new pad type must be 'manual' or 'suggested'");
            }
        }
        apply(t, b);
        if (t.layout == null) {
            t.layout = t.pitchMm != null ? PadType.Layout.TWO_TERMINAL : PadType.Layout.SINGLE;
        }
        checkDose(svc, null, t);
        JsonObject mapping = b.has("mapping") && b.get("mapping").isJsonObject() ? b.getAsJsonObject("mapping") : null;
        String pkg = mapping == null ? null : Json.str(mapping, "packageId", null);
        if (mapping != null && pkg == null) {
            throw ApiException.badRequest("'mapping' needs a packageId (and padNames for some pads only)");
        }
        List<String> names = mapping == null ? null : Json.strings(mapping, "padNames");
        DispenseDb.joinPadNames(names); // checks the names before saving (400)
        PadType stored = svc.savePadType(t);
        if (pkg != null) {
            svc.getDb().mapPackage(pkg, names, stored.typeKey);
            svc.getDb().event(null, "INFO", "PAD_TYPE", "package " + pkg + (names.isEmpty() ? "" : " pads "
                    + String.join(",", names)) + " mapped to " + stored.typeKey);
        }
        return ApiServer.Response.json(201, Views.map("padType", Views.padType(stored), "mapping", pkg == null ? null
                : Views.map("packageId", pkg, "padNames", names.isEmpty() ? null : names)));
    }

    Object update(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        PadType was = require(svc, r.group(1));
        PadType t = was.copy();
        if (Json.has(b, "typeKey") && !r.group(1).equals(Json.str(b, "typeKey", null))) {
            throw ApiException.badRequest("the key of a pad type cannot change; create a new type instead");
        }
        if (Json.has(b, "name")) {
            t.name = Json.str(b, "name", t.name);
        }
        if (Json.has(b, "family")) {
            t.family = Json.str(b, "family", t.family);
        }
        apply(t, b);
        checkDose(svc, was, t);
        PadType stored = svc.savePadType(t);
        return Views.map("padType", Views.padType(stored));
    }

    Object retire(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.svc();
        PadType t = require(svc, r.group(1)).copy();
        if (RETIRED.equals(t.family)) {
            return Views.map("padType", Views.padType(t), "note", "already retired");
        }
        String was = t.family;
        t.family = RETIRED;
        t.status = PadTypeStatus.NOT_STARTED;
        t.packagePattern = null;
        t.notes = "Retired (was in '" + was + "')" + (t.notes == null ? "" : ". " + t.notes);
        PadType stored = svc.savePadType(t);
        return Views.map("padType", Views.padType(stored), "note",
                "retired: kept in the history, never matched to a package, never dispensed");
    }

    Object rule(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        PadType t = require(svc, r.group(1));
        PadRule rule = new PadRule(t.typeKey, Json.integer(b, "maxDots", null), Json.num(b, "fitFactor", null),
                Json.num(b, "largestSingleDotPadMm2", null));
        if (rule.maxDots != null && (rule.maxDots < 1 || rule.maxDots > 64)) {
            throw ApiException.badRequest("maxDots must be 1 to 64");
        }
        if (rule.fitFactor != null && rule.fitFactor <= 0) {
            throw ApiException.badRequest("fitFactor must be above 0");
        }
        rule.notes = Json.str(b, "notes", null);
        PadRule stored = svc.savePadRule(rule);
        return Views.map("typeKey", stored.typeKey, "version", stored.version, "maxDots", stored.maxDots,
                "fitFactor", stored.fitFactor, "largestSingleDotPadMm2", stored.largestSingleDotPadMm2);
    }

    // ------------------------------------------------------------------------------------------

    private static PadType require(DispenseService svc, String key) throws Exception {
        PadType t = svc.getDb().currentPadType(key);
        if (t == null) {
            throw ApiException.notFound("no pad type '" + key + "'");
        }
        return t;
    }

    /** Applies the editable fields present in the body (validation by the engine on save). */
    private static void apply(PadType t, JsonObject b) throws ApiException {
        t.padWMm = Json.num(b, "padWMm", t.padWMm);
        t.padHMm = Json.num(b, "padHMm", t.padHMm);
        t.padRoundness = Json.num(b, "padRoundness", t.padRoundness);
        if (t.padRoundness < 0 || t.padRoundness > 100) {
            throw ApiException.badRequest("roundness is 0 to 100 %");
        }
        if (Json.has(b, "layout")) {
            try {
                t.layout = PadType.Layout.fromDb(Json.str(b, "layout", null));
            }
            catch (IllegalArgumentException e) {
                throw ApiException.badRequest("layout must be one of " + layoutNames());
            }
        }
        if (b.has("pitchMm")) {
            t.pitchMm = Json.num(b, "pitchMm", null);
        }
        t.tMm = Json.num(b, "tMm", t.tMm);
        t.k = Json.num(b, "k", t.k);
        if (t.k > 2) {
            throw ApiException.badRequest("k above 2 is not plausible (1.0 = full pad area)");
        }
        if (Json.has(b, "status")) {
            try {
                t.status = PadTypeStatus.fromDb(Json.str(b, "status", null));
            }
            catch (IllegalArgumentException e) {
                throw ApiException.badRequest("status must be one of " + statusNames());
            }
        }
        if (b.has("packagePattern")) {
            String p = Json.str(b, "packagePattern", null);
            if (p != null) {
                try {
                    Pattern.compile(p);
                }
                catch (PatternSyntaxException e) {
                    throw ApiException.badRequest("the package pattern is not a valid regular expression: "
                            + e.getDescription());
                }
            }
            t.packagePattern = p;
        }
        if (b.has("notes")) {
            t.notes = Json.str(b, "notes", null);
        }
        if (b.has("dotSteps")) {
            t.dotSteps = Json.integer(b, "dotSteps", null);
        }
        if (b.has("dotsPerPad")) {
            t.dotsPerPad = Json.integer(b, "dotsPerPad", null);
        }
        if (b.has("calAreaMm2")) {
            t.calAreaMm2 = Json.num(b, "calAreaMm2", null);
        }
        t.operator = Json.str(b, "operator", t.operator);
        if (t.padWMm <= 0 || t.padHMm <= 0 || t.padWMm > 50 || t.padHMm > 50) {
            throw ApiException.badRequest("pad width and height must be above 0 and at most 50 mm");
        }
        if (t.tMm <= 0 || t.tMm > 1) {
            throw ApiException.badRequest("paste thickness t must be above 0 and at most 1 mm");
        }
        if (t.k <= 0) {
            throw ApiException.badRequest("k must be above 0");
        }
    }

    /**
     * The dose of a type version to be saved (§5.1): allowed for the mounted tip, every pad at least half a turn, and
     * the status evidence of an own-dose type raised to characterized or validated (409 status-evidence). An edit of
     * the pad or the dose drops the calibration it had: origin manual, no recipe or weighed mass (decision 17).
     */
    static void checkDose(DispenseService svc, PadType was, PadType t) throws Exception {
        String problem = t.doseProblem();
        if (problem != null) {
            throw ApiException.badRequest(problem);
        }
        DispenseConfig c = svc.getConfig();
        if (t.dotSteps != null && t.dotSteps < c.tip.minDotSteps) {
            throw ApiException.badRequest("the " + c.tip.name + " takes " + DoseModel.turnWithArticle(c.tip.minDotSteps)
                    + " per dot at least (" + c.tip.minDotSteps + " steps), not " + t.dotSteps + " steps");
        }
        boolean padChanged = was != null && (was.padWMm != t.padWMm || was.padHMm != t.padHMm
                || was.padRoundness != t.padRoundness);
        boolean doseChanged = was == null ? t.dotSteps != null || t.dotsPerPad != null
                : !Objects.equals(was.dotSteps, t.dotSteps) || !Objects.equals(was.dotsPerPad, t.dotsPerPad);
        if (was != null && (padChanged || doseChanged)) {
            t.origin = PadType.ORIGIN_MANUAL;
            t.doseFp = null;
            t.massPerPadMg = null;
        }
        if (doseChanged && (t.dotSteps != null || t.dotsPerPad != null)) {
            t.tipKey = c.tip.key;
        }
        boolean raised = was == null || !was.status.atLeast(t.status);
        if (t.dotSteps == null || t.dotSteps == c.profile.netStepsPerDot || !t.status.atLeast(PadTypeStatus.CHARACTERIZED)
                || !(raised || doseChanged)) {
            return;
        }
        String turn = DoseModel.turnName(t.dotSteps);
        if (t.status == PadTypeStatus.VALIDATED) {
            boolean characterized = false;
            for (PadType h : was == null ? new ArrayList<PadType>() : svc.getDb().padTypeHistory(t.typeKey)) {
                characterized |= h.status.atLeast(PadTypeStatus.CHARACTERIZED) && Objects.equals(h.dotSteps, t.dotSteps)
                        && Objects.equals(h.dotsPerPad, t.dotsPerPad);
            }
            if (!characterized) {
                throw new ApiException(409, "status-evidence", t.name + " can be validated on a board only after its "
                        + turn + " dots were characterized: calibrate it first (Calibrate automatically or by hand).");
            }
            return;
        }
        DoseEvidence e = PadInstances.inputs(svc, new ArrayList<>(), false).evidence.get(t.dotSteps);
        boolean optical = e != null && e.opticalFresh && e.opticalPass;
        boolean mass = e != null && e.massConfirmed;
        if (!optical || !mass) {
            throw new ApiException(409, "status-evidence", t.name + " uses " + turn + " dots, which have "
                    + (!optical ? "no fresh camera check on the blank" : "") + (!optical && !mass ? " and " : "")
                    + (!mass ? "no confirmed weighing at this recipe" : "") + ": run the ladder and Weigh dots "
                    + "(Calibrate) first.");
        }
    }

    static List<String> statusNames() {
        List<String> l = new ArrayList<>();
        for (PadTypeStatus s : PadTypeStatus.values()) {
            l.add(s.dbName);
        }
        return l;
    }

    static List<String> layoutNames() {
        List<String> l = new ArrayList<>();
        for (PadType.Layout x : PadType.Layout.values()) {
            l.add(x.dbName);
        }
        return l;
    }

    static String familyPrefix(String family) {
        String f = family == null ? "" : family.toLowerCase(Locale.ROOT);
        if (f.startsWith("chip")) {
            return "chip";
        }
        if (f.startsWith("small-outline")) {
            return "so";
        }
        if (f.startsWith("leaded")) {
            return "ic";
        }
        if (f.startsWith("no-lead")) {
            return "nolead";
        }
        if (f.startsWith("custom")) {
            return "custom";
        }
        return f;
    }

    static String slug(String s) {
        String k = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (k.length() > 40) {
            k = k.substring(0, 40).replaceAll("-+$", "");
        }
        return k.isEmpty() ? "custom" : k;
    }

    private static String uniqueKey(DispenseService svc, String base) throws Exception {
        String k = base;
        for (int i = 2; svc.getDb().currentPadType(k) != null; i++) {
            k = base + "-" + i;
        }
        return k;
    }
}
