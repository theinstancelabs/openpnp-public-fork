package org.openpnp.instancelabs.api;

import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Future;

import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseDb;
import org.openpnp.instancelabs.dispense.DispenseProfile;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.EngineRefusal;
import org.openpnp.instancelabs.dispense.MountResult;
import org.openpnp.instancelabs.dispense.NeedleChange;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.PadTypeStatus;
import org.openpnp.instancelabs.dispense.Photo;
import org.openpnp.instancelabs.dispense.PhotoStore;
import org.openpnp.instancelabs.dispense.TipPictureRequest;
import org.openpnp.instancelabs.dispense.TipPictureResult;
import org.openpnp.instancelabs.dispense.TipSetup;
import org.openpnp.instancelabs.dispense.TipSetups;
import org.openpnp.instancelabs.dispense.TipSpec;
import org.openpnp.instancelabs.dispense.ZCheck;
import org.openpnp.instancelabs.padprofile.Tip;

import com.google.gson.JsonObject;

/**
 * Tip setups (PROFILE_CALIBRATION_DESIGN.md §22.1, §22.3, §22.6): the library of a dispenser and the needle on it, one
 * setup mounted. Add (its recipe copied from a setup or the defaults, nothing measured), edit (another needle is
 * another setup; a correction of the same needle takes two presses), retire and bring back; what a mount would do,
 * the mount itself (the needle change, a fact never refused, then the switch of the setup, which alone may be refused),
 * its undo for 2 min, and a picture of the mounted tip from below. Nothing here moves the machine but the picture.
 */
final class TipSetupEndpoints {
    static final String KEY = "([a-z0-9][a-z0-9-]{0,47})";

    private final ApiContext ctx;
    private final DispenseEndpoints dispense;

    TipSetupEndpoints(ApiContext ctx, DispenseEndpoints dispense) {
        this.ctx = ctx;
        this.dispense = dispense;
    }

    void register(ApiServer s) {
        s.route("GET", "/api/tip-setups", this::list);
        s.route("POST", "/api/tip-setups", this::add);
        s.route("POST", "/api/tip-setups/undo-mount", this::undo);
        s.route("PUT", "/api/tip-setups/" + KEY, this::edit);
        s.route("POST", "/api/tip-setups/" + KEY + "/retire", r -> retire(r, true));
        s.route("POST", "/api/tip-setups/" + KEY + "/unretire", r -> retire(r, false));
        s.route("GET", "/api/tip-setups/" + KEY + "/mount-preview", this::mountPreview);
        s.route("POST", "/api/tip-setups/" + KEY + "/mount", this::mount);
        s.route("POST", "/api/tip-setups/" + KEY + "/picture", this::picture);
    }

    // ------------------------------------------------------------------------------------------
    // The library
    // ------------------------------------------------------------------------------------------

    /** GET /api/tip-setups?retired=true: every setup (retired ones when asked), the mounted one and its times. */
    Object list(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.svc();
        DispenseDb db = svc.getDb();
        DispenseConfig cfg = svc.getConfig();
        Long mountedAt = mountedAtMs(db, cfg.tip.key);
        List<Map<String, Object>> l = new ArrayList<>();
        for (TipSetup s : db.currentTipSetups("true".equals(r.query("retired", "false")))) {
            l.add(Views.setup(svc, s, cfg, mountedAt));
        }
        return Views.map("mounted", cfg.tip.key, "mountedAtMs", mountedAt, "needleChangedAtMs", cfg.needleChangedAtMs,
                "undoUntilMs", svc.undoUntilMs(), "setups", l);
    }

    /**
     * POST /api/tip-setups: version 1 of a new setup and its parked entry (§22.1) with a copy of the recipe it starts
     * from ({@code profileFrom}: a setup's key or "default"; the mounted one's when left out), named "{recipe} · {setup}":
     * a recipe to start from, nothing measured (D2). With {@code mount: true} ("Add and mount") it is mounted too.
     */
    Object add(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        DispenseDb db = svc.getDb();
        if (Json.has(b, "model")) {
            throw ApiException.badRequest("'model' is the engine's: only the seeded 22G bent taper uses the bench model");
        }
        TipSetup s = new TipSetup();
        fields(s, b);
        if (s.odMm != null && s.odSource == null) {
            s.odSource = TipSetup.OD_ASSUMED; // the presets fill the OD from the chart
        }
        List<TipSetup> all = db.currentTipSetups(true);
        Set<String> taken = new LinkedHashSet<>();
        for (TipSetup x : all) {
            taken.add(x.key);
        }
        String key = Json.str(b, "key", null);
        if (key == null) {
            key = TipSetups.deriveKey(s, taken);
        }
        else if (TipSetup.RESERVED_KEY.equals(key)) {
            throw ApiException.badRequest("'" + key + "' cannot be a tip's key (it is a route): choose another, or leave "
                    + "it out");
        }
        else if (!TipSpec.KEY.matcher(key).matches()) {
            throw ApiException.badRequest("a tip's key is lower-case letters, digits and '-' (at most 48)");
        }
        else if (taken.contains(key)) {
            throw new ApiException(409, "exists", "A tip with the key '" + key + "' exists already: choose another key, "
                    + "or leave it out.");
        }
        s.key = key;
        s.why = "added";
        s.operator = Json.str(b, "operator", null);
        problems(s);
        requireFreeName(all, s.name, null);
        DispenseConfig cfg = svc.getConfig();
        String profileFrom = Json.str(b, "profileFrom", cfg.tip.key);
        DispenseProfile from;
        String fromWords;
        if ("default".equals(profileFrom)) {
            from = new DispenseProfile();
            fromWords = "the defaults (" + from.name + ")";
        }
        else if (profileFrom.equals(cfg.tip.key)) {
            from = cfg.profile;
            fromWords = "the " + cfg.tip.name + "'s " + from.name;
        }
        else {
            DispenseConfig.ParkedTip p = cfg.parkedTips.get(profileFrom);
            TipSetup src = db.currentTipSetup(profileFrom);
            if (p == null || p.profile == null || src == null) {
                throw ApiException.badRequest("'profileFrom' must be a tip setup's key or 'default': no recipe of '"
                        + profileFrom + "'");
            }
            from = p.profile;
            fromWords = "the " + src.name + "'s " + from.name;
        }
        DispenseProfile copy = from.copy();
        copy.name = from.name + " · " + s.name;
        TipSetup stored = db.insertTipSetupVersion(s);
        String fromKey = profileFrom;
        svc.updateConfigAtomically(live -> {
            live.parkedTips.put(stored.key, TipSetups.newEntry(live, copy, fromKey));
            return live;
        });
        db.event(null, "INFO", "TIP", "added the " + stored.name + " (" + stored.key + "), its recipe a copy of "
                + fromWords + ": nothing measured with it yet");
        Map<String, Object> out = Views.map("setup", null, "mount", null, "note", "Added. It has no pad profiles yet.");
        if (Json.bool(b, "mount", false)) {
            try {
                out.put("mount", mountNow(svc, stored, Json.str(b, "why", null), stored.operator));
            }
            catch (ApiException e) {
                // the setup is added either way: the mount's refusal (with the needle change it recorded) is its part
                Map<String, Object> refused = Views.map("refused", true, "status", e.status, "error", e.code, "reason",
                        e.getMessage());
                refused.putAll(e.details);
                out.put("mount", refused);
            }
        }
        DispenseConfig now = svc.getConfig();
        out.put("setup", Views.setup(svc, db.currentTipSetup(stored.key), now, mountedAtMs(db, now.tip.key)));
        return ApiServer.Response.json(201, out);
    }

    /**
     * PUT /api/tip-setups/{key}: a new version with the fields given. Dispenser, gauge, form and taper of a setup with
     * records make another needle (409 another-needle); tip model, ID within 0.03 mm, bend angle and length are
     * corrections ({@code correction: true}, else 409 correction, the UI's second press); for the mounted setup the
     * engine's view of it is refreshed (refused while the engine is busy) and plans go stale.
     */
    Object edit(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        DispenseDb db = svc.getDb();
        TipSetup was = require(svc, r.group(1));
        if (Json.has(b, "key") && !was.key.equals(Json.str(b, "key", null))) {
            throw ApiException.badRequest("the key of a tip cannot change: add a tip instead");
        }
        if (Json.has(b, "model") && !Objects.equals(was.model, Json.str(b, "model", null))) {
            throw ApiException.badRequest("'model' is the engine's: only the seeded 22G bent taper uses the bench model");
        }
        if (b.has("retired")) {
            throw ApiException.badRequest("retire a tip with POST /api/tip-setups/" + was.key + "/retire (and bring it "
                    + "back with /unretire)");
        }
        TipSetup next = was.copy();
        fields(next, b);
        boolean correction = Json.bool(b, "correction", false);
        List<String> changes = changes(was, next);
        boolean mounted = was.key.equals(svc.getConfig().tip.key);
        if (changes.isEmpty()) {
            if (mounted && svc.getConfig().tip.setupVersion != was.version) {
                // a save whose refresh a task refused (below): saving again brings the details to the engine
                ctx.requireEngineIdle(svc);
                refreshMounted(svc, was);
            }
            return setupView(svc, was);
        }
        TipSetups.checkEdit(was, next, db.tipSetupInUse(was.key), correction);
        requireFreeName(db.currentTipSetups(false), next.name, was.key);
        if (mounted) {
            ctx.requireEngineIdle(svc);
        }
        next.why = (correction ? "corrected: " : "edited: ") + String.join(", ", changes);
        next.operator = Json.str(b, "operator", null);
        TipSetup stored = db.insertTipSetupVersion(next);
        if (mounted) {
            refreshMounted(svc, stored);
        }
        db.event(null, "INFO", "TIP", (correction ? "corrected the " : "edited the ") + was.name + ": "
                + String.join(", ", changes));
        return setupView(svc, stored);
    }

    /**
     * The engine's view of the mounted setup ({@code cfg.tip}) refreshed from a version of it: the new setup version
     * makes plans stale (the resolver's fingerprint). Refused while a dispense task runs; the version stays saved.
     */
    private static void refreshMounted(DispenseService svc, TipSetup stored) throws Exception {
        svc.updateConfigAtomically(live -> {
            if (stored.key.equals(live.tip.key)) {
                if (svc.isBusy()) {
                    throw ApiException.refused("A dispense task started: the " + stored.name + "'s details are saved; "
                            + "save them again when it has ended, so the engine uses them.");
                }
                live.tip = stored.toSpec();
            }
            return live;
        });
    }

    /** POST /api/tip-setups/{key}/retire and /unretire: a retired setup cannot be mounted; the mounted one stays. */
    Object retire(ApiServer.Request r, boolean retire) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        DispenseDb db = svc.getDb();
        TipSetup was = require(svc, r.group(1));
        if (retire && was.key.equals(svc.getConfig().tip.key)) {
            throw ApiException.refused("The mounted tip cannot be retired.");
        }
        if (was.retired == retire) {
            return setupView(svc, was);
        }
        if (!retire) {
            requireFreeName(db.currentTipSetups(false), was.name, was.key);
        }
        TipSetup next = was.copy();
        next.retired = retire;
        next.why = retire ? "retired" : "brought back";
        next.operator = Json.str(b, "operator", null);
        TipSetup stored = db.insertTipSetupVersion(next);
        db.event(null, "INFO", "TIP", (retire ? "retired the " : "brought back the ") + stored.name);
        return setupView(svc, stored);
    }

    // ------------------------------------------------------------------------------------------
    // Mounting
    // ------------------------------------------------------------------------------------------

    /**
     * GET /api/tip-setups/{key}/mount-preview: the change panel's text (what goes stale, what stays, the recipe, the
     * coupons whose dots no longer count), its warnings and what the switch would refuse now.
     */
    Object mountPreview(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.svc();
        DispenseDb db = svc.getDb();
        TipSetup s = require(svc, r.group(1));
        DispenseConfig cfg = svc.getConfig();
        boolean same = s.key.equals(cfg.tip.key);
        List<String> consequences = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        DispenseConfig.ParkedTip parked = cfg.parkedTips.get(s.key);
        if (same) {
            consequences.add("A new needle: record its needle Z frame and check where it lands; its pad types and recipe "
                    + "stay.");
        }
        else {
            boolean neverOn = parked == null || parked.needleAnchor == null;
            consequences.add("Nothing dispenses until you record the " + s.name + "'s needle Z frame" + (neverOn
                    ? " (Both tips touch, or touch off)" : "") + " and check where it lands.");
            int ours = calibrated(TipSetups.typesFor(db, cfg.tip.key));
            int theirs = calibrated(TipSetups.typesFor(db, s.key));
            consequences.add("The " + cfg.tip.name + " keeps its " + ours + " calibrated pad " + (ours == 1 ? "type"
                    : "types") + "; the " + s.name + " has " + (theirs == 0 ? "none" : Integer.toString(theirs))
                    + " yet.");
            if (parked != null && parked.profile != null) {
                boolean copied = parked.parkedAtMs == null && parked.profileFrom != null;
                consequences.add("Recipe: " + parked.profile.name + (copied ? " (copied " + day(firstVersionAt(db,
                        s.key)) + ")" : "") + ".");
            }
            consequences.add("Plans made before go stale.");
            DispenseConfig.NeedleLanding l = parked == null ? null : parked.needleLanding;
            String head = headOffsetWarning(s.name, svc.headOffsetNow(),
                    l == null || l.headOffsetXMm == null || l.headOffsetYMm == null ? null
                            : new double[] {l.headOffsetXMm, l.headOffsetYMm});
            if (head != null) {
                warnings.add(head);
            }
        }
        String coupons = pendingCouponsWords(svc);
        if (coupons != null) {
            consequences.add(coupons);
        }
        String refusal = null;
        if (!same) {
            PadCalibrationJob j = ctx.padCalJobs.current();
            if (svc.isBusy() || j != null && j.holdsLock()) {
                refusal = "A needle cannot come off while the head moves: press STOP first.";
            }
            else if (ctx.pressure.isActive()) {
                refusal = "Switch the pressure off first: a needle comes off with the pressure off.";
            }
            else if (s.retired) {
                refusal = "The " + s.name + " is retired: bring it back first (Change tip › Show retired tips).";
            }
            else if (!s.isComplete()) {
                refusal = "The " + s.name + " has no " + missing(s) + " yet: fill it in first (Edit…).";
            }
        }
        return Views.map("key", s.key, "same", same, "text", String.join(" ", consequences), "consequences",
                consequences, "warnings", warnings, "refusal", refusal);
    }

    /** POST /api/tip-setups/{key}/mount {why?, operator?}: this tip is on the dispenser now (§22.3). */
    Object mount(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        TipSetup s = require(svc, r.group(1));
        return mountNow(svc, s, Json.str(b, "why", null), Json.str(b, "operator", null));
    }

    /**
     * (a) the needle change, always and first; (b) the switch of the setup when another one is mounted, on the jobs'
     * monitor so no pad calibration starts between its checks and its write. A refusal of (b) carries the change (a)
     * recorded: nothing dispenses until the setup is mounted and its needle Z frame recorded.
     */
    Map<String, Object> mountNow(DispenseService svc, TipSetup s, String why, String operator) throws Exception {
        NeedleChange nc = dispense.recordNeedleChange(svc, why, operator);
        DispenseConfig cfg = svc.getConfig();
        if (s.key.equals(cfg.tip.key)) {
            // "I changed the needle" for the same kind of tip: (a) alone
            Map<String, Object> change = Views.needleChange(nc);
            List<String> stale = new ArrayList<>(Arrays.asList("needle Z frame", "needle Z offset", "where it lands",
                    "regulator reading", "prime"));
            return Views.map("mounted", s.key, "mountedName", s.name, "from", s.key, "fromName", s.name, "atMs",
                    nc.atMs, "needleChangedAtMs", nc.atMs, "same", true, "needleChange", change, "voided",
                    change.get("voided"), "stale", stale, "next", next(svc, cfg, false), "undoUntilMs", null, "text",
                    "A new " + s.name + " at " + ZCheck.clock(nc.atMs) + ". Next: record its needle Z frame (Setup › "
                            + "Needle Z frame), then check where it lands.");
        }
        MountResult m;
        try {
            m = ctx.padCalJobs.whileNoJob(() -> svc.switchTip(s.key, why, operator));
        }
        catch (ApiException e) {
            throw mountRefused(e.status, e.code, e.getMessage(), s, nc);
        }
        catch (EngineRefusal e) {
            ApiException x = mountRefused(e.status, e.code, e.getMessage(), s, nc);
            for (Map.Entry<String, Object> d : e.details.entrySet()) {
                x.with(d.getKey(), d.getValue());
            }
            throw x;
        }
        catch (IllegalStateException e) {
            throw mountRefused(409, "refused", e.getMessage(), s, nc);
        }
        m.needleChange = nc;
        return mountView(svc, m);
    }

    /** §22.3: every refusal of the switch ends with the needle change it recorded and carries it. */
    private static ApiException mountRefused(int status, String code, String reason, TipSetup s, NeedleChange nc) {
        String end = "The needle change is recorded: nothing dispenses until the " + s.name + " is mounted and its "
                + "needle Z frame recorded.";
        String text = reason == null ? end : reason.contains(end) ? reason : reason + " " + end;
        return new ApiException(status, code, text).with("needleChange", Views.needleChange(nc));
    }

    /**
     * POST /api/tip-setups/undo-mount {operator?}: the previous mount restored exactly (setup, recipe, frame, offset,
     * trim, landing and needle token) within 2 min of a mount of another setup, on the operator's word that the old
     * tip never left the dispenser; 409 too late.
     */
    Object undo(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        String operator = Json.str(b, "operator", null);
        MountResult m = ctx.padCalJobs.whileNoJob(() -> svc.undoMount(operator));
        return mountView(svc, m);
    }

    /** The mount response (§22.3), the head-offset sentence added when N2's head offset moved since the landing. */
    private Map<String, Object> mountView(DispenseService svc, MountResult m) throws Exception {
        String text = m.text;
        String head = headOffsetWarning(m.mountedName, m.headOffsetNow, m.headOffsetLandingWas);
        if (head != null && (text == null || !text.contains("head offset"))) {
            text = text == null ? head : text + " " + head;
        }
        DispenseConfig cfg = svc.getConfig();
        return Views.mount(m, m.needleChange, next(svc, cfg, cfg.needleAnchor == null), text);
    }

    /**
     * "N2's head offset is ({x}, {y}) now; the {S}'s landing was checked with ({x'}, {y'}): set it back in OpenPnP, or
     * check where it lands." when they differ by more than 0.001 mm, else null.
     */
    static String headOffsetWarning(String setupName, double[] now, double[] was) {
        if (now == null || was == null || !DispenseEndpoints.moved(now, was[0], was[1])) {
            return null;
        }
        return String.format(Locale.ROOT, "N2's head offset is (%.3f, %.3f) now; the %s's landing was checked with "
                + "(%.3f, %.3f): set it back in OpenPnP, or check where it lands.", now[0], now[1], setupName, was[0],
                was[1]);
    }

    /**
     * The order after a needle change (§22.3, the response's {@code next} and Setup's #tip-next): frame, offset,
     * regulator, prime, where it lands, touch-off, and the tip's dots when its dot table does not know them. Each step
     * says whether it is done where the engine knows it (the regulator and prime ticks are the browser's).
     */
    static List<Map<String, Object>> next(DispenseService svc, DispenseConfig cfg, boolean neverOn) {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(Views.map("key", "frame", "label", neverOn ? "Record its needle Z frame: Both tips touch, or touch off"
                : "Record its needle Z frame", "href", "#setup/needle", "done", cfg.isNeedleAnchored()));
        if (!neverOn) {
            l.add(Views.map("key", "offset", "label", "Confirm the frame's offset (or enter a measured one)", "href",
                    "#setup/needle", "done", cfg.isNeedleTouchedOff()));
        }
        l.add(Views.map("key", "regulator", "label", "Set the regulator to " + Views.num(cfg.profile.pressurePsi)
                + " psi and read it", "fix", "attest-regulator", "done", null));
        l.add(Views.map("key", "prime", "label", "Prime until paste comes out", "href", "#setup/prime", "done", null));
        l.add(Views.map("key", "landing", "label", "Check where it lands", "fix", "landing-check", "done",
                svc.isNeedleXyChecked()));
        l.add(Views.map("key", "touch-off", "label", neverOn ? "Touch off (recommended: this tip was never on)"
                : "Touch off", "href", "#calibrate/touch-off", "done", null));
        Tip tip;
        try {
            tip = svc.mountedTip();
        }
        catch (SQLException e) {
            tip = null; // the step list without the dots step: the database could not be read
        }
        if (tip != null && !tip.benchModel && !tip.isMeasured()) {
            l.add(Views.map("key", "measure", "label", "Measure its dots", "fix", "measure-tip", "done", false));
        }
        return l;
    }

    /**
     * POST /api/tip-setups/{key}/picture {calibrationIds?, wait?}: N2 to the bottom camera at the balanced travel
     * height and one lit frame of the mounted tip (§22.4), linked to the records named. Only the mounted tip.
     */
    Object picture(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.availableSvc();
        TipSetup s = require(svc, r.group(1));
        ctx.requireEngineIdle(svc);
        if (ctx.pressure.isActive()) {
            throw ApiException.refused("Switch the pressure off first: the head moves to the bottom camera.");
        }
        ctx.requireMachineIdle();
        DispenseConfig cfg = svc.getConfig();
        if (!s.key.equals(cfg.tip.key)) {
            throw ApiException.refused("Only the mounted tip can be photographed: mount the " + s.name + " first.");
        }
        TipPictureRequest q = new TipPictureRequest();
        q.setupKey = s.key;
        q.needleSinceMs = cfg.needleChangedAtMs;
        q.calibrationIds.addAll(PhotoEndpoints.ids(b, "calibrationIds"));
        for (Long id : q.calibrationIds) {
            // the records it will show checked before the head moves (§22.4: another setup's, or a record of the
            // needle before a change, would leave the picture unstored after the move)
            PhotoStore.checkLink(svc.getDb(), s.key, Photo.SOURCE_BOTTOM_CAMERA, q.needleSinceMs, id);
            PhotoStore.checkTipCount(svc.getDb(), id, 1);
        }
        q.stopEpoch = svc.stopEpoch();
        q.operator = Json.str(b, "operator", null);
        ctx.tracker.expect("tip picture");
        Future<TipPictureResult> f = svc.takeTipPicture(q);
        return ApiContext.awaitIf(Json.bool(b, "wait", false), f, Views::tipPicture, Views.map("kind", "tip picture",
                "setupKey", s.key));
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private Map<String, Object> setupView(DispenseService svc, TipSetup s) throws Exception {
        DispenseConfig cfg = svc.getConfig();
        return Views.setup(svc, s, cfg, mountedAtMs(svc.getDb(), cfg.tip.key));
    }

    static TipSetup require(DispenseService svc, String key) throws Exception {
        TipSetup s = svc.getDb().currentTipSetup(key);
        if (s == null) {
            throw ApiException.notFound("no tip setup '" + key + "'");
        }
        return s;
    }

    /**
     * When the setup was mounted: its newest {@code mount} row (an undo restores the mount before it), or null when it
     * has been on since before the update.
     */
    static Long mountedAtMs(DispenseDb db, String key) throws SQLException {
        for (TipSetups.Mount m : db.tipMounts(500)) {
            if (TipSetups.Mount.MOUNT.equals(m.kind) && key.equals(m.setupKey)) {
                return m.createdAtMs;
            }
        }
        return null;
    }

    /** 409 tip-changed (§22.1, §22.2): a form filled under another setup than the one mounted now. */
    static ApiException tipChanged(DispenseService svc, DispenseConfig live) {
        Long at = null;
        try {
            at = mountedAtMs(svc.getDb(), live.tip.key);
        }
        catch (Exception e) {
            at = null; // the text names the setup without its time
        }
        return new ApiException(409, "tip-changed", "The tip changed while this was open (the " + live.tip.name
                + (at == null ? " is mounted" : " was mounted at " + ZCheck.clock(at)) + "): reload.");
    }

    /** The §22.1 fields present in the body; a field sent as null clears it (where it may be null). */
    static void fields(TipSetup s, JsonObject b) throws ApiException {
        if (b.has("name")) {
            s.name = Json.str(b, "name", null);
        }
        if (b.has("dispenser")) {
            s.dispenser = Json.str(b, "dispenser", null);
        }
        if (b.has("tipModel")) {
            s.tipModel = Json.str(b, "tipModel", null);
        }
        if (b.has("gauge")) {
            s.gauge = Json.integer(b, "gauge", null);
        }
        if (b.has("idMm")) {
            s.idMm = Json.num(b, "idMm", null);
        }
        if (b.has("odMm")) {
            s.odMm = Json.num(b, "odMm", null);
        }
        if (b.has("odSource")) {
            s.odSource = Json.str(b, "odSource", null);
        }
        if (b.has("form")) {
            s.form = Json.str(b, "form", null);
        }
        if (b.has("bendDeg")) {
            s.bendDeg = Json.num(b, "bendDeg", null);
        }
        if (b.has("bendToward")) {
            s.bendToward = Json.str(b, "bendToward", null);
        }
        if (b.has("taper")) {
            s.taper = Json.str(b, "taper", null);
        }
        if (b.has("lengthMm")) {
            s.lengthMm = Json.num(b, "lengthMm", null);
        }
        if (Json.has(b, "minDotSteps")) {
            s.minDotSteps = Json.integer(b, "minDotSteps", s.minDotSteps);
        }
        if (b.has("notes")) {
            s.notes = Json.str(b, "notes", null);
        }
    }

    /** The engine's field check (§22.1 "allowed"): 400 with every problem. */
    private static void problems(TipSetup s) throws ApiException {
        List<String> p = s.problems();
        if (!p.isEmpty()) {
            throw ApiException.badRequest(String.join("; ", p));
        }
    }

    /** A name is unique among the setups that are not retired (409 exists). */
    private static void requireFreeName(List<TipSetup> live, String name, String except) throws ApiException {
        if (name == null) {
            return;
        }
        for (TipSetup x : live) {
            if (!x.retired && !x.key.equals(except) && x.name != null && x.name.trim().equalsIgnoreCase(name.trim())) {
                throw new ApiException(409, "exists", "A tip named '" + x.name + "' exists already: give this one "
                        + "another name.");
            }
        }
    }

    /** What an edit changes, as the event and the version's why say it: "OD 0.72 → 0.80 mm". */
    static List<String> changes(TipSetup a, TipSetup b) {
        List<String> l = new ArrayList<>();
        change(l, "name", a.name, b.name, "");
        change(l, "dispenser", a.dispenser, b.dispenser, "");
        change(l, "tip model", a.tipModel, b.tipModel, "");
        change(l, "gauge", a.gauge, b.gauge, "G");
        change(l, "ID", a.idMm, b.idMm, " mm");
        change(l, "OD", a.odMm, b.odMm, " mm");
        change(l, "OD source", a.odSource, b.odSource, "");
        change(l, "form", a.form, b.form, "");
        change(l, "bend", a.bendDeg, b.bendDeg, "°");
        change(l, "bend toward", a.bendToward, b.bendToward, "");
        change(l, "taper", a.taper, b.taper, "");
        change(l, "length", a.lengthMm, b.lengthMm, " mm");
        change(l, "smallest dot", a.minDotSteps, b.minDotSteps, " steps");
        change(l, "notes", a.notes, b.notes, "");
        return l;
    }

    private static void change(List<String> l, String what, Object a, Object b, String unit) {
        if (!Objects.equals(a, b)) {
            l.add(what + " " + words(a, unit) + " → " + words(b, unit));
        }
    }

    private static String words(Object v, String unit) {
        if (v == null) {
            return "none";
        }
        if (v instanceof Double) {
            return Views.num((Double) v) + unit;
        }
        return v + unit;
    }

    /** "ID, OD or form": what a placeholder lacks before it can be mounted. */
    static String missing(TipSetup s) {
        List<String> l = new ArrayList<>();
        if (s.idMm == null) {
            l.add("ID");
        }
        if (s.odMm == null) {
            l.add("OD");
        }
        if (s.form == null || TipSetup.FORM_UNKNOWN.equals(s.form)) {
            l.add("form");
        }
        if (s.taper == null || TipSetup.TAPER_UNKNOWN.equals(s.taper)) {
            l.add("taper");
        }
        if (l.isEmpty()) {
            return "complete details";
        }
        return l.size() == 1 ? l.get(0) : String.join(", ", l.subList(0, l.size() - 1)) + " or " + l.get(l.size() - 1);
    }

    /** A setup's characterized and validated pad types (stand-ins and retired types left out). */
    private static int calibrated(List<PadType> types) {
        int n = 0;
        for (PadType t : types) {
            if (!t.standIn && !PadTypeEndpoints.RETIRED.equals(t.family) && t.status != null
                    && t.status.atLeast(PadTypeStatus.CHARACTERIZED)) {
                n++;
            }
        }
        return n;
    }

    /** "coupon1's dots will not count: wipe it." for the coupons still waiting for their readings, or null. */
    private static String pendingCouponsWords(DispenseService svc) {
        List<String> places = new ArrayList<>();
        for (Map<String, Object> p : PadCalibrationEndpoints.pendingView(svc)) {
            if (p.get("place") != null && !places.contains(String.valueOf(p.get("place")))) {
                places.add(String.valueOf(p.get("place")));
            }
        }
        if (places.isEmpty()) {
            return null;
        }
        return String.join(" and ", places) + "'s dots will not count: wipe " + (places.size() == 1 ? "it." : "them.");
    }

    private static long firstVersionAt(DispenseDb db, String key) throws SQLException {
        List<TipSetup> h = db.tipSetupHistory(key);
        return h.isEmpty() ? System.currentTimeMillis() : h.get(0).createdAtMs;
    }

    private static String day(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(ms));
    }

    /** Where a bend points, as the texts write it: "+X", "−Y"; null when not recorded. */
    static String direction(String bendToward) {
        if (bendToward == null || bendToward.length() != 2) {
            return null;
        }
        return (bendToward.charAt(0) == '-' ? "−" : "+") + bendToward.substring(1).toUpperCase(Locale.ROOT);
    }
}
