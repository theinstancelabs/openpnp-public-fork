package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
import org.openpnp.instancelabs.dispense.Photo;
import org.openpnp.instancelabs.dispense.TipSetup;
import org.openpnp.instancelabs.dispense.TipSetups;
import org.openpnp.instancelabs.padprofile.DoseEvidence;
import org.openpnp.instancelabs.padprofile.DoseModel;
import org.openpnp.instancelabs.padprofile.Tip;

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
 *
 * <p>Tip setups (§22.2): the rows are the mounted setup's composed view (the pad of the type's newest version of any
 * setup, the dose of the mounted setup's own); every write names the setup it was made for ({@code forSetup}, 409
 * tip-changed) and a pad edit the version of the pad it showed ({@code padVersion}, 409 pad-changed). A status raised
 * to characterized or validated in the editor is a by-hand calibration record with its label (§22.5). A plan of
 * another setup is copied only when asked, as a provisional start.</p>
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
        s.route("POST", "/api/pad-types/([a-z0-9-]+)/copy", this::copy);
    }

    Object list() throws Exception {
        DispenseService svc = ctx.svc();
        DispenseConfig cfg = svc.getConfig();
        Others others = Others.read(svc, cfg.tip.key);
        Calibration cal = svc.getCurrentCalibration();
        Map<String, PadRule> rules = svc.getDb().currentPadRules();
        Map<String, List<Map<String, Object>>> byFamily = new LinkedHashMap<>();
        for (String f : FAMILY_ORDER) {
            byFamily.put(f, new ArrayList<>());
        }
        List<PadType> types = svc.getPadTypes();
        List<String> more = new ArrayList<>();
        for (PadType t : types) {
            String f = t.family == null || t.family.trim().isEmpty() ? "Custom" : t.family;
            if (!byFamily.containsKey(f) && !RETIRED.equals(f) && !more.contains(f)) {
                more.add(f);
            }
        }
        more.sort(String.CASE_INSENSITIVE_ORDER);
        for (String f : more) {
            byFamily.put(f, new ArrayList<>());
        }
        byFamily.put(RETIRED, new ArrayList<>());
        for (PadType t : types) {
            String f = t.family == null || t.family.trim().isEmpty() ? "Custom" : t.family;
            byFamily.get(f).add(row(svc, t, cal, rules.get(t.typeKey), others));
        }
        List<Map<String, Object>> families = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : byFamily.entrySet()) {
            if (e.getValue().isEmpty() && (RETIRED.equals(e.getKey()) || !FAMILY_ORDER.contains(e.getKey()))) {
                continue;
            }
            families.add(Views.map("family", e.getKey(), "retired", RETIRED.equals(e.getKey()), "types", e.getValue()));
        }
        return Views.map("forSetup", Views.map("key", cfg.tip.key, "name", cfg.tip.name), "families", families,
                "statuses", statusNames(), "layouts", layoutNames(), "minPadTypeStatus", cfg.minPadTypeStatus,
                "calibration", Views.calibration(cal));
    }

    /**
     * One Profiles row: the current version plus its dot plan (profile dots per pad; the dose is the
     * dispense profile's, the stencil-equivalent volume is for information).
     */
    private Map<String, Object> row(DispenseService svc, PadType t, Calibration cal, PadRule rule, Others others)
            throws Exception {
        Map<String, Object> m = Views.padType(t);
        String setup = svc.getConfig().tip.key;
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
        // the counts are the mounted setup's (§22.6); version numbers count across setups
        List<PadType> history = svc.getDb().padTypeHistory(t.typeKey);
        int versions = 0;
        int newest = 0;
        Long validatedAt = null;
        for (PadType h : history) {
            newest = Math.max(newest, h.version);
            if (!setup.equals(setupOf(h.tipKey))) {
                continue;
            }
            versions++;
            if (h.status == PadTypeStatus.VALIDATED
                    && (validatedAt == null || h.createdAtMs > validatedAt)) {
                validatedAt = h.createdAtMs;
            }
        }
        m.put("versions", versions);
        m.put("nextVersion", newest + 1);
        m.put("lastValidatedAtMs", validatedAt);
        List<PadCalibrationRecord> cals = calibrations(svc, t.typeKey, setup, 1);
        m.put("lastCalibration", cals.isEmpty() ? null : cals.get(0));
        m.put("dispensable", t.status.atLeast(svc.getConfig().minPadTypeStatus()));
        m.put("label", t.standIn ? null : others.label(t.typeKey, setup, t.version));
        m.put("others", others.of(t.typeKey));
        return m;
    }

    /**
     * The other setups' own versions of every type with their labels and result photos (§22.6 {@code others}), read
     * once per list: what By tip and the side panel's Other tips show.
     */
    static final class Others {
        final Map<String, List<Map<String, Object>>> byType = new LinkedHashMap<>();
        final Map<String, PadCalibrationRecord> wrote = new HashMap<>();
        DispenseService svc;

        static Others read(DispenseService svc, String mounted) throws Exception {
            Others o = new Others();
            o.svc = svc;
            DispenseDb db = svc.getDb();
            for (PadCalibrationRecord r : calibrations(svc, null, null, 5000)) {
                if (r.versionTo != null) {
                    o.wrote.putIfAbsent(r.typeKey + "|" + setupOf(r.setupKey) + "|" + r.versionTo, r);
                }
            }
            for (TipSetup s : db.currentTipSetups(false)) {
                if (s.key.equals(mounted)) {
                    continue;
                }
                for (PadType t : TipSetups.typesFor(db, s.key)) {
                    if (t.standIn) {
                        continue;
                    }
                    PadCalibrationRecord r = o.wrote.get(t.typeKey + "|" + s.key + "|" + t.version);
                    Photo p = r == null ? null : resultPhoto(db, r.id);
                    o.byType.computeIfAbsent(t.typeKey, k -> new ArrayList<>()).add(Views.map("setupKey", s.key,
                            "setupName", s.name, "version", t.version, "status", t.status == null ? null
                                    : t.status.dbName, "label", o.label(t.typeKey, s.key, t.version), "atMs",
                            t.createdAtMs, "calibrationId", r == null ? null : r.id, "thumbUrl", p == null ? null
                                    : Views.photoUrl(p.id, true)));
                }
            }
            return o;
        }

        List<Map<String, Object>> of(String typeKey) {
            return byType.getOrDefault(typeKey, new ArrayList<>());
        }

        /** The "calibrated for" label of the record that wrote this version of the setup, or null. */
        String label(String typeKey, String setupKey, int version) throws Exception {
            PadCalibrationRecord r = wrote.get(typeKey + "|" + setupKey + "|" + version);
            if (r == null) {
                return null;
            }
            Object[] l = PadCalibrationEndpoints.label(svc, r);
            return l == null ? null : (String) l[0];
        }
    }

    /** The newest result photo linked to a record, or null. */
    static Photo resultPhoto(DispenseDb db, long calibrationId) throws Exception {
        List<Photo> l = db.photos(null, calibrationId, null, Photo.KIND_RESULT, false, 1);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Pad calibrations of a type and a setup (null: any), newest first. */
    static List<PadCalibrationRecord> calibrations(DispenseService svc, String typeKey, String setupKey, int limit)
            throws Exception {
        DispenseDb db = svc.getDb();
        return db.padCalibrations(typeKey, setupKey, limit);
    }

    /** The setup a version or record belongs to (§22.1): its tip key, the seeded setup when it names none. */
    static String setupOf(String tipKey) {
        return Tip.setupOf(tipKey);
    }

    /**
     * GET /api/pad-types/{key}: {@code current} as the mounted setup sees it, the {@code history} of every setup (each
     * version naming its setup) and {@code bySetup}: each setup's own newest version with its label.
     */
    Object get(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.svc();
        DispenseDb db = svc.getDb();
        DispenseConfig cfg = svc.getConfig();
        PadType t = require(svc, r.group(1));
        Map<String, String> names = new LinkedHashMap<>();
        for (TipSetup s : db.currentTipSetups(true)) {
            names.put(s.key, s.name);
        }
        names.putIfAbsent(cfg.tip.key, cfg.tip.name);
        Others others = Others.read(svc, cfg.tip.key);
        List<Map<String, Object>> history = new ArrayList<>();
        Map<String, Map<String, Object>> bySetup = new LinkedHashMap<>();
        int newest = 0;
        for (PadType h : db.padTypeHistory(t.typeKey)) {
            String setup = setupOf(h.tipKey);
            Map<String, Object> v = Views.padType(h);
            v.put("setupKey", setup);
            v.put("setupName", names.get(setup));
            v.put("label", others.label(h.typeKey, setup, h.version));
            history.add(v);
            bySetup.put(setup, v); // oldest first: the setup's newest version stays
            newest = Math.max(newest, h.version);
        }
        Map<String, Object> current = Views.padType(t);
        current.put("nextVersion", newest + 1);
        current.put("label", t.standIn ? null : others.label(t.typeKey, cfg.tip.key, t.version));
        current.put("others", others.of(t.typeKey));
        return Views.map("forSetup", Views.map("key", cfg.tip.key, "name", cfg.tip.name), "current", current,
                "history", history, "bySetup", bySetup);
    }

    Object create(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        String forSetup = forSetup(svc, b);
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
        else if (exists(svc, key)) {
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
        Saved saved = save(svc, b, null, t, forSetup, null);
        PadType stored = saved.type;
        if (pkg != null) {
            svc.getDb().mapPackage(pkg, names, stored.typeKey);
            svc.getDb().event(null, "INFO", "PAD_TYPE", "package " + pkg + (names.isEmpty() ? "" : " pads "
                    + String.join(",", names)) + " mapped to " + stored.typeKey);
        }
        return ApiServer.Response.json(201, Views.map("padType", Views.padType(stored), "mapping", pkg == null ? null
                : Views.map("packageId", pkg, "padNames", names.isEmpty() ? null : names), "calibration",
                saved.calibration));
    }

    Object update(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        String forSetup = forSetup(svc, b);
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
        // a pad edit names the pad version it showed (left out: the one read now, no conflict check); a dose write
        // takes the pad of the composed view under the engine's lock
        Integer padVersion = padEdit(was, t) ? Json.integer(b, "padVersion", was.padVersion) : null;
        Saved saved = save(svc, b, was, t, forSetup, padVersion);
        return Views.map("padType", Views.padType(saved.type), "calibration", saved.calibration);
    }

    /**
     * DELETE /api/pad-types/{key} {forSetup, padVersion?}: retire for every tip (§22.2): the family and the pattern are
     * the pad's, shared by every setup, so the type is never matched or dispensed whichever tip is on.
     */
    Object retire(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        String forSetup = forSetup(svc, b);
        PadType was = require(svc, r.group(1));
        PadType t = was.copy();
        if (RETIRED.equals(t.family)) {
            return Views.map("padType", Views.padType(t), "note", "already retired");
        }
        String family = t.family;
        t.family = RETIRED;
        t.status = PadTypeStatus.NOT_STARTED;
        t.packagePattern = null;
        t.notes = "Retired (was in '" + family + "')" + (t.notes == null ? "" : ". " + t.notes);
        t.tipKey = forSetup;
        t.operator = Json.str(b, "operator", t.operator);
        Integer padVersion = Json.integer(b, "padVersion", was.padVersion);
        PadType stored = svc.savePadType(t, forSetup, padVersion);
        return Views.map("padType", Views.padType(stored), "note",
                "retired for every tip: kept in the history, never matched to a package, never dispensed");
    }

    /**
     * POST /api/pad-types/{key}/copy {fromSetup, forSetup}: another setup's dot plan as a provisional start for the
     * mounted setup (§22.2, D2: only when asked; nothing measured comes with it). The editor's dose rules apply (400);
     * a version of the mounted setup that is characterized or validated already is not replaced (409).
     */
    Object copy(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        DispenseDb db = svc.getDb();
        String forSetup = forSetup(svc, b);
        String fromSetup = Json.str(b, "fromSetup", null);
        if (fromSetup == null) {
            throw ApiException.badRequest("'fromSetup' is required: the tip setup whose dot plan is copied");
        }
        if (fromSetup.equals(forSetup)) {
            throw ApiException.badRequest("copy from another tip: the " + svc.getConfig().tip.name + " is the one "
                    + "mounted");
        }
        TipSetup source = TipSetupEndpoints.require(svc, fromSetup);
        TipSetup target = TipSetupEndpoints.require(svc, forSetup);
        PadType from = db.currentPadType(r.group(1), fromSetup);
        if (from == null) {
            throw ApiException.notFound("no pad type '" + r.group(1) + "'");
        }
        if (from.standIn) {
            throw ApiException.refused(from.name + " is not calibrated for the " + source.name + ": there is no plan to "
                    + "copy.");
        }
        PadType own = db.currentPadType(r.group(1), forSetup);
        PadType v = TipSetups.copyOf(from, source.name, own, target);
        v.operator = Json.str(b, "operator", null);
        checkDoseRules(svc, v);
        PadType stored = svc.savePadType(v, forSetup, null);
        db.event(null, "INFO", "PAD_TYPE", "copied " + from.typeKey + " v" + from.version + " of the " + source.name
                + " as v" + stored.version + " for the " + target.name + " (provisional: nothing measured with it)");
        return Views.map("padType", Views.padType(stored), "copiedFrom", Views.map("setupKey", source.key,
                "setupName", source.name, "version", from.version, "status", from.status == null ? null
                        : from.status.dbName));
    }

    /** A saved version, and the by-hand calibration record when its status was raised (§22.5), else null. */
    private static final class Saved {
        PadType type;
        Map<String, Object> calibration;
    }

    /**
     * Saves a version for the mounted setup. A status raised to characterized or validated is the editor's by-hand
     * calibration (§22.5): a record of method by-hand with its label (the body's {@code calibration.label}, else the
     * automatic one) and photos ({@code calibration.photoIds}), the main picture and the evidence's result photos.
     */
    private static Saved save(DispenseService svc, JsonObject b, PadType was, PadType t, String forSetup,
            Integer padVersion) throws Exception {
        Saved out = new Saved();
        boolean raised = t.status.atLeast(PadTypeStatus.CHARACTERIZED) && (was == null || !was.status.atLeast(t.status));
        if (!raised) {
            out.type = svc.savePadType(t, forSetup, padVersion);
            return out;
        }
        JsonObject c = b.has("calibration") && b.get("calibration").isJsonObject() ? b.getAsJsonObject("calibration")
                : new JsonObject();
        String label = Json.has(c, "label") ? DispenseService.cleanLabel(Json.str(c, "label", null)) : null;
        List<Long> photoIds = PhotoEndpoints.ids(c, "photoIds");
        // the pad the editor showed (409 pad-changed under the engine's lock), or null: the pad as it is now
        PadCalibrationRecord rec = svc.savePadTypeByHand(t, forSetup, padVersion, label, photoIds, t.operator);
        DispenseDb db = svc.getDb();
        out.type = db.currentPadType(t.typeKey, forSetup);
        if (rec != null) {
            Object[] l = PadCalibrationEndpoints.label(svc, rec);
            out.calibration = Views.map("id", rec.id, "label", l == null ? null : l[0], "labelAuto", l == null ? null
                    : l[1], "photos", Views.photos(db.photos(null, rec.id, null, null, false, 50)));
        }
        return out;
    }

    /** A pad edit changes a field of the pad (shared by every setup, §22.2), not only its dose. */
    static boolean padEdit(PadType was, PadType t) {
        return !Objects.equals(was.family, t.family) || !Objects.equals(was.name, t.name) || was.padWMm != t.padWMm
                || was.padHMm != t.padHMm || was.padRoundness != t.padRoundness || was.layout != t.layout
                || !Objects.equals(was.pitchMm, t.pitchMm) || was.tMm != t.tMm || was.k != t.k
                || !Objects.equals(was.packagePattern, t.packagePattern);
    }

    /**
     * The setup a write is for (§22.2): required, and the mounted one (409 tip-changed: the form was filled under
     * another setup; the engine checks again under its lock).
     */
    static String forSetup(DispenseService svc, JsonObject b) throws ApiException {
        String f = Json.str(b, "forSetup", null);
        if (f == null) {
            throw ApiException.badRequest("'forSetup' is required: the tip setup the pad type was edited for (status "
                    + "dispense.tip.key)");
        }
        DispenseConfig cfg = svc.getConfig();
        if (!f.equals(cfg.tip.key)) {
            throw TipSetupEndpoints.tipChanged(svc, cfg);
        }
        return f;
    }

    /** Whether any version of the key exists, of any setup. */
    static boolean exists(DispenseService svc, String key) throws Exception {
        DispenseDb db = svc.getDb();
        return svc.getDb().typeKeyExists(key);
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

    /** The type as the mounted setup sees it (a stand-in when the setup has no version of it), or 404. */
    static PadType require(DispenseService svc, String key) throws Exception {
        DispenseDb db = svc.getDb();
        String setup = svc.getConfig().tip.key;
        PadType t = db.currentPadType(key, setup);
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
        checkDoseRules(svc, t);
        DispenseConfig c = svc.getConfig();
        boolean padChanged = was != null && (was.padWMm != t.padWMm || was.padHMm != t.padHMm
                || was.padRoundness != t.padRoundness);
        boolean doseChanged = was == null ? t.dotSteps != null || t.dotsPerPad != null
                : !Objects.equals(was.dotSteps, t.dotSteps) || !Objects.equals(was.dotsPerPad, t.dotsPerPad);
        if (was != null && (padChanged || doseChanged)) {
            t.origin = PadType.ORIGIN_MANUAL;
            t.doseFp = null;
            t.massPerPadMg = null;
        }
        t.tipKey = c.tip.key; // every version names its setup (tip setups design §22.1)
        boolean raised = was == null || !was.status.atLeast(t.status);
        if (!t.status.atLeast(PadTypeStatus.CHARACTERIZED) || !(raised || doseChanged)) {
            return;
        }
        // the profile's dose was measured on the bench with the 22G bent taper: exempt only with §1's model (§22.2)
        int dose = t.dotSteps != null ? t.dotSteps : c.profile.netStepsPerDot;
        if (TipSetup.MODEL_BENCH.equals(c.tip.model) && dose == c.profile.netStepsPerDot) {
            return;
        }
        String turn = DoseModel.turnName(dose);
        if (t.status == PadTypeStatus.VALIDATED) {
            boolean characterized = false;
            for (PadType h : was == null ? new ArrayList<PadType>() : svc.getDb().padTypeHistory(t.typeKey)) {
                // only the mounted setup's own characterized versions count
                characterized |= c.tip.key.equals(setupOf(h.tipKey)) && h.status.atLeast(PadTypeStatus.CHARACTERIZED)
                        && Objects.equals(h.dotSteps, t.dotSteps) && Objects.equals(h.dotsPerPad, t.dotsPerPad);
            }
            if (!characterized) {
                throw new ApiException(409, "status-evidence", t.name + " can be validated on a board only after its "
                        + turn + " dots were characterized: calibrate it first (Calibrate automatically or by hand).");
            }
            return;
        }
        DoseEvidence e = PadInstances.inputs(svc, new ArrayList<>(), false).evidence.get(dose);
        boolean optical = e != null && e.opticalFresh && e.opticalPass;
        boolean mass = e != null && e.massConfirmed;
        if (!optical || !mass) {
            throw new ApiException(409, "status-evidence", t.name + " uses " + turn + " dots, which have "
                    + (!optical ? "no fresh camera check on the blank" : "") + (!optical && !mass ? " and " : "")
                    + (!mass ? "no confirmed weighing at this recipe" : "") + ": run the ladder and Weigh dots "
                    + "(Calibrate) first.");
        }
    }

    /** The dose rules of a version (§5.1, R1, R6) and the mounted tip's smallest dot: 400 with their text. */
    static void checkDoseRules(DispenseService svc, PadType t) throws ApiException {
        String problem = t.doseProblem();
        if (problem != null) {
            throw ApiException.badRequest(problem);
        }
        DispenseConfig c = svc.getConfig();
        if (t.dotSteps != null && t.dotSteps < c.tip.minDotSteps) {
            throw ApiException.badRequest("the " + c.tip.name + " takes " + DoseModel.turnWithArticle(c.tip.minDotSteps)
                    + " per dot at least (" + c.tip.minDotSteps + " steps), not " + t.dotSteps + " steps");
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
        for (int i = 2; exists(svc, k); i++) {
            k = base + "-" + i;
        }
        return k;
    }
}
