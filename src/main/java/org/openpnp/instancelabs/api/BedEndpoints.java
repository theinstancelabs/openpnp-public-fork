package org.openpnp.instancelabs.api;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.openpnp.instancelabs.dispense.BedModel;
import org.openpnp.instancelabs.dispense.BedModelStore;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.Photo;
import org.openpnp.instancelabs.dispense.ZCheck;
import org.openpnp.spi.Machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * BR-98 (collision-model-design.md §6.1, §6.4; Process's correction of 11-04 20:5x): the bed model's routes.
 * {@code GET /api/bed} reads it (no lock). Every other route is a guarded write: the lock holder's only, refused while
 * anything moves, validated as the loader does (400 with the problem, nothing written), then version + 1, the file
 * rewritten atomically and a BED_MODEL row (who, before, after).
 *
 * <p>A person's word may add an object (it blocks its outline at every height until measured), mark one moved or
 * removed, grow an outline or a tool layer, raise a top. Anything that frees space needs a new measurement record (a
 * later {@code atMs} than the stored one, a non-empty {@code how}, {@code evidence} naming journal events or photos
 * that exist, none older than the stored record and no bed-model or motion row) where that part keeps its records: a
 * lower or newly measured top, a smaller top error or a touchable top ({@code measured.top}); a smaller outline or
 * margin, or an unmeasured object becoming measured ({@code measured.xy}); any change of the bed plane or the bed's
 * smaller outline ({@code bed.plane}); any see-saw change ({@code seesaw.measured}); a smaller, higher or moved tool
 * layer, a changed tipEnd (its atMs too) or moves ({@code tools[].measured}). A removed object stops blocking only
 * through {@code /cleared} with both of Process's measurements since it was removed: the top camera against the stored
 * empty-bed picture of that spot (the engine's fixed threshold, no person judging) and N1 lowered to its floor at 3
 * points inside the outline with no contact (N1 cannot touch the bare bed). A smaller Z margin or a deeper contact
 * depth frees space everywhere: not through the API in this build.</p>
 */
final class BedEndpoints {
    static final String MEASUREMENT_NEEDED = "measurement-needed";
    /** Process's X/Y margin (design §8, desk answer 4) for an object a person adds. */
    static final double ADDED_MARGIN_MM = 1.0;
    /** Tests only (#66 review MAJOR 2): runs between a write's checks and its write. */
    static volatile Runnable writeHook;

    private final ApiContext ctx;

    BedEndpoints(ApiContext ctx) {
        this.ctx = ctx;
    }

    void register(ApiServer s) {
        s.route("GET", "/api/bed", r -> ctx.bed.view());
        s.route("PUT", "/api/bed", this::putModel);
        s.route("POST", "/api/bed/objects", this::add);
        s.route("PUT", "/api/bed/objects/([^/]+)", this::putObject);
        s.route("POST", "/api/bed/objects/([^/]+)/(moved|removed|cleared)", this::mark);
        s.route("PUT", "/api/bed/tools/([^/]+)", this::putTool);
        s.route("PUT", "/api/bed/(plane|seesaw)", this::putPart);
    }

    // ------------------------------------------------------------------------------------------
    // The routes
    // ------------------------------------------------------------------------------------------

    /** The first whole model (no file, or one that fails validation); after that the same rules as the parts. */
    Object putModel(ApiServer.Request r) throws Exception {
        BedModelStore st = store(r);
        return save(r, st, st.state().model, r.json(), "PUT /api/bed", null, null);
    }

    /** A person's word: the object blocks its outline at every height until it is measured. */
    Object add(ApiServer.Request r) throws Exception {
        BedModelStore st = store(r);
        BedModel m = current(st);
        JsonObject b = r.json();
        for (String k : new String[] {"topZ", "measured", "state"}) {
            if (b.has(k) && !b.get(k).isJsonNull()) {
                throw ApiException.badRequest("an added object blocks at every height until it is measured: send its "
                        + "name and outline, then its top with PUT /api/bed/objects/{id} and a measurement record");
            }
        }
        String name = Json.str(b, "name", null);
        if (name == null || !b.has("outline")) {
            throw ApiException.badRequest("'name' and 'outline' are required");
        }
        String id = Json.str(b, "id", null);
        if (id != null && m.object(id) != null) {
            throw new ApiException(409, "exists", "an object '" + id + "' is in the model already");
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", id != null ? id : unique(m, name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "")));
        o.addProperty("name", name);
        o.addProperty("kind", Json.str(b, "kind", "movable"));
        if (b.has("role")) {
            o.add("role", b.get("role"));
        }
        o.add("outline", b.get("outline"));
        // no topZ and no surface: not known, so left out (null nowhere, design §2)
        o.addProperty("marginMm", Json.num(b, "marginMm", ADDED_MARGIN_MM));
        o.addProperty("noPaste", Json.bool(b, "noPaste", false));
        o.addProperty("touchable", Json.bool(b, "touchable", false));
        o.add("measured", new JsonObject());
        o.addProperty("state", "added");
        o.add("invalidatedBy", b.has("invalidatedBy") ? b.get("invalidatedBy") : new JsonParser().parse(
                "[\"moved\", \"touched\"]"));
        JsonObject next = m.json();
        next.getAsJsonArray("objects").add(o);
        return save(r, st, m, next, "POST /api/bed/objects", JsonNull.INSTANCE, o);
    }

    /** Process's numbers for one object: the whole object as the file holds it. */
    Object putObject(ApiServer.Request r) throws Exception {
        BedModelStore st = store(r);
        BedModel m = current(st);
        String id = r.group(1);
        JsonObject b = r.json();
        if (b.has("id") && !id.equals(Json.str(b, "id", null))) {
            throw ApiException.badRequest("the body's id differs from the path's");
        }
        b.addProperty("id", id);
        JsonObject next = m.json();
        JsonObject before = replace(next, "objects", id, b);
        return save(r, st, m, next, "PUT /api/bed/objects/" + id, before, b);
    }

    /** Moved (the old entry blocks, a new one at the given outline), removed (it blocks), cleared (both checks). */
    Object mark(ApiServer.Request r) throws Exception {
        BedModelStore st = store(r);
        BedModel m = current(st);
        String id = r.group(1), what = r.group(2);
        JsonObject next = m.json();
        JsonObject o = find(next, "objects", id);
        String state = o.get("state").getAsString();
        JsonObject b = r.json();
        String route = "POST /api/bed/objects/" + id + "/" + what;
        JsonObject before = copy(o);
        if ("cleared".equals(what)) {
            if (!"removed".equals(state)) {
                throw ApiException.refused(id + " is " + state + ": only a removed object is cleared (mark it removed "
                        + "first)");
            }
            JsonObject cleared = cleared(b, removedAt(id));
            replace(next, "objects", id, null);
            JsonObject after = new JsonObject();
            after.add("cleared", cleared);
            return save(r, st, m, next, route, before, after, id);
        }
        if ("removed".equals(state)) {
            if ("removed".equals(what)) {
                return ctx.bed.view();
            }
            throw ApiException.refused(id + " is removed: put down again, it is a new object (POST /api/bed/objects)");
        }
        o.addProperty("state", what);
        JsonArray after = new JsonArray();
        after.add(o);
        if ("moved".equals(what) && b.has("outline") && !b.get("outline").isJsonNull()) {
            JsonObject n = copy(o);
            n.addProperty("id", unique(m, id + "-2"));
            n.add("outline", b.get("outline"));
            n.remove("topZ");
            n.add("measured", new JsonObject());
            n.addProperty("state", "added");
            next.getAsJsonArray("objects").add(n);
            after.add(n);
        }
        return save(r, st, m, next, route, before, after);
    }

    Object putTool(ApiServer.Request r) throws Exception {
        BedModelStore st = store(r);
        BedModel m = current(st);
        String id = r.group(1);
        JsonObject b = r.json();
        if (b.has("id") && !id.equals(Json.str(b, "id", null))) {
            throw ApiException.badRequest("the body's id differs from the path's");
        }
        b.addProperty("id", id);
        JsonObject next = m.json();
        JsonObject before = m.tool(id) == null ? null : replace(next, "tools", id, b);
        if (before == null) {
            next.getAsJsonArray("tools").add(b);
        }
        return save(r, st, m, next, "PUT /api/bed/tools/" + id, before == null ? JsonNull.INSTANCE : before, b);
    }

    /** The bed plane (its record is the block: how, by, atMs, errMm, homeAtMs, evidence) or the see-saw block. */
    Object putPart(ApiServer.Request r) throws Exception {
        BedModelStore st = store(r);
        BedModel m = current(st);
        String part = r.group(1);
        JsonObject next = m.json(), b = r.json();
        JsonObject holder = "plane".equals(part) ? next.getAsJsonObject("bed") : next;
        String key = "plane".equals(part) ? "plane" : "seesaw";
        JsonElement before = holder.get(key);
        holder.add(key, b);
        return save(r, st, m, next, "PUT /api/bed/" + part, before, b);
    }

    // ------------------------------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------------------------------

    /** The engine's store, for a write: the lock holder's only, never while anything moves. */
    private BedModelStore store(ApiServer.Request r) throws ApiException {
        if (r.lockActivity == null) {
            // nobody holds the lock and the caller sent neither a token nor the UI's header (#66 review MINOR d)
            throw new ApiException(409, "no-lock", "the bed model changes only by the lock holder: POST "
                    + "/api/lock/acquire, then send its token as " + MachineLock.TOKEN_HEADER).with("lock", ctx.lock
                            .view());
        }
        DispenseService svc = ctx.svc();
        Machine m = ctx.configuration.getMachine();
        if (m != null && m.isBusy()) {
            throw ApiException.refused("the machine is moving (OpenPnP busy): the bed model changes only while nothing "
                    + "moves");
        }
        if (svc.isBusy() || ctx.padCalJobs.lockReason() != null) {
            throw ApiException.refused("the machine is moving (an engine task runs): the bed model changes only while "
                    + "nothing moves");
        }
        if (ctx.passthrough.running() || ctx.passthrough.motionPending()) {
            throw ApiException.refused("the machine is moving (a G-code batch, or its moves not ended by an M400): the "
                    + "bed model changes only while nothing moves");
        }
        return svc.getBedModel();
    }

    private static BedModel current(BedModelStore st) throws ApiException {
        BedModelStore.State s = st.state();
        if (s.model == null) {
            throw ApiException.refused(s.exists ? "the bed model is not valid (" + s.error + "): send a whole valid "
                    + "model with PUT /api/bed first" : "there is no bed model yet: send the first whole model with PUT "
                            + "/api/bed");
        }
        return s.model;
    }

    private Object save(ApiServer.Request r, BedModelStore st, BedModel before, JsonObject next, String what,
            JsonElement b, JsonElement a) throws Exception {
        return save(r, st, before, next, what, b, a, null);
    }

    /**
     * Validates {@code next} (400 with the problem), holds it to the rules against {@code before} (409
     * {@value #MEASUREMENT_NEEDED} listing what frees space without its record; none for the first model), writes it
     * unless another write came since {@code before} was read (409 refused: read it again). {@code cleared}: the
     * removed object {@code /cleared} took out (its records were checked there).
     */
    private Object save(ApiServer.Request r, BedModelStore st, BedModel before, JsonObject next, String what,
            JsonElement b, JsonElement a, String cleared) throws Exception {
        next.addProperty("version", before == null ? 0 : before.version);
        BedModel after;
        try {
            after = BedModel.parse(next);
        }
        catch (IllegalArgumentException e) {
            throw ApiException.badRequest("the bed model would not be valid: " + e.getMessage());
        }
        if (before != null) {
            List<String> needs = freed(before, after, cleared);
            if (!needs.isEmpty()) {
                throw new ApiException(409, MEASUREMENT_NEEDED, String.join("; ", needs)).with("needs", needs);
            }
        }
        String who = r.lockActivity.holder.who;
        if (b == null) {
            b = before == null ? JsonNull.INSTANCE : before.json();
            a = next;
        }
        Runnable hook = writeHook;
        if (hook != null) {
            hook.run();
        }
        try {
            st.write(before, next, who, what, b, a);
        }
        catch (IllegalStateException e) {
            throw ApiException.refused(e.getMessage());
        }
        return ctx.bed.view();
    }

    /** What {@code after} frees against {@code before} without the new record that frees it, in words. */
    private List<String> freed(BedModel before, BedModel after, String cleared) throws SQLException, ApiException {
        List<String> needs = new ArrayList<>();
        // what rests on the needle (its tip end, K) is measured with the needle on now (review 2 MINOR 4)
        Long needle = ctx.svc().getConfig().needleChangedAtMs;
        JsonObject bj = before.json(), aj = after.json();
        for (BedModel.Obj o : before.obstacles()) {
            if (BedModel.BED_ID.equals(o.id) || o.id.equals(cleared)) {
                continue;
            }
            BedModel.Obj n = after.object(o.id);
            if (n == null || "removed".equals(o.state) && !"removed".equals(n.state)) {
                needs.add(o.id + (n == null ? " leaves the model" : " is removed") + ": it stops blocking only through "
                        + "POST /api/bed/objects/" + o.id + "/cleared, after it is marked removed (the top camera's "
                        + "comparison and N1's 3-point check)");
                continue;
            }
            boolean top = !o.touchable && n.touchable, xy = !n.poly.contains(o.poly) || n.marginMm < o.marginMm;
            if (o.blocksEveryHeight() && !n.blocksEveryHeight()) {
                top = xy = true;
            }
            else if (!n.blocksEveryHeight() && (n.topZ < o.topZ || n.topErrMm < o.topErrMm)) {
                top = true;
            }
            JsonObject om = member(find(bj, "objects", o.id), "measured"), nm = member(find(aj, "objects", o.id),
                    "measured");
            if (top) {
                need(needs, "objects '" + o.id + "'.measured.top", member(om, "top"), member(nm, "top"), null);
            }
            if (xy) {
                need(needs, "objects '" + o.id + "'.measured.xy", member(om, "xy"), member(nm, "xy"), null);
            }
        }
        JsonObject bp = bj.getAsJsonObject("bed").getAsJsonObject("plane");
        JsonObject ap = aj.getAsJsonObject("bed").getAsJsonObject("plane");
        if (!bp.equals(ap) || !after.obstacles().get(0).poly.contains(before.obstacles().get(0).poly)) {
            need(needs, "bed.plane", bp, ap, null);
        }
        if (!bj.get("seesaw").equals(aj.get("seesaw"))) {
            need(needs, "seesaw.measured", member(bj.getAsJsonObject("seesaw"), "measured"), member(aj.getAsJsonObject(
                    "seesaw"), "measured"), needle);
        }
        for (BedModel.Tool t : before.tools()) {
            BedModel.Tool n = after.tool(t.id);
            if (n == null) {
                needs.add("the tool " + t.id + " leaves the model: not through the API in this build");
                continue;
            }
            // a new tipEnd.atMs too: it lets the needle prove again after a needle change (#66 review MAJOR 3)
            boolean tipEnd = Double.compare(t.tipDx, n.tipDx) != 0 || Double.compare(t.tipDy, n.tipDy) != 0
                    || !Objects.equals(t.tipEndAtMs, n.tipEndAtMs);
            boolean freed = !t.moves.equals(n.moves) || tipEnd;
            for (BedModel.Layer l : t.layers) {
                BedModel.Layer nl = null;
                for (BedModel.Layer x : n.layers) {
                    nl = x.name.equals(l.name) ? x : nl;
                }
                freed |= nl == null || nl.tip != l.tip || !nl.poly.contains(l.poly) || (l.bottomZ == null) != (nl.bottomZ
                        == null) || l.bottomZ != null && nl.bottomZ > l.bottomZ || l.aboveTipMm != null
                                && nl.aboveTipMm > l.aboveTipMm;
            }
            if (freed) {
                need(needs, "tools '" + t.id + "'.measured", member(find(bj, "tools", t.id), "measured"), member(find(aj,
                        "tools", t.id), "measured"), tipEnd && "needle".equals(n.moves) ? needle : null);
            }
        }
        if (after.zMarginMm < before.zMarginMm || after.contactDepthMm > before.contactDepthMm) {
            needs.add("a smaller zMarginMm or a deeper contactDepthMm frees space everywhere: not through the API in this "
                    + "build");
        }
        return needs;
    }

    /**
     * Adds what is missing for {@code rec} to be a new measurement record at {@code path}; {@code needle}: the needle
     * change its evidence must also be from since (null: none).
     */
    private void need(List<String> needs, String path, JsonObject old, JsonObject rec, Long needle)
            throws SQLException {
        String problem;
        if (rec == null) {
            problem = "there is none";
        }
        else if (!rec.has("how") || !rec.get("how").isJsonPrimitive() || rec.get("how").getAsString().trim().isEmpty()) {
            problem = "it does not say how it was measured";
        }
        else if (!number(rec, "atMs")) {
            problem = "atMs (when it was measured) is required";
        }
        else if (old != null && number(old, "atMs") && rec.get("atMs").getAsDouble() <= old.get("atMs").getAsDouble()) {
            problem = "it is not later than the stored record: a new measurement has its own time";
        }
        else {
            Long stored = old != null && number(old, "atMs") ? Long.valueOf(Math.round(old.get("atMs").getAsDouble()))
                    : null;
            boolean since = needle != null && (stored == null || needle > stored);
            problem = evidence(rec.get("evidence"), since ? needle : stored, since ? "the needle change at "
                    + ZCheck.clock(needle) : "the stored record");
        }
        if (problem != null) {
            needs.add("frees space: a new measurement record at " + path + " is needed (" + problem + ")");
        }
    }

    /**
     * Null when {@code e} names journal events ({@code event:<id>}) or photos ({@code photo:<id>}) that all exist, each
     * a measurement's own (#66 review MINOR c): no bed-model or motion row, none older than {@code notBefore} (the
     * time of {@code since}; null: any).
     */
    private String evidence(JsonElement e, Long notBefore, String since) throws SQLException {
        List<String> items = new ArrayList<>();
        if (e != null && e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                items.add(x.isJsonPrimitive() ? x.getAsString().trim() : "?");
            }
        }
        else if (e != null && e.isJsonPrimitive()) {
            for (String x : e.getAsString().split("[,\\s]+")) {
                if (!x.isEmpty()) {
                    items.add(x);
                }
            }
        }
        if (items.isEmpty()) {
            return "evidence must name its journal event (event:<id>) or its photos (photo:<id>)";
        }
        DispenseService svc = ctx.svcOrNull();
        for (String x : items) {
            String[] p = x.split(":", 2);
            long n;
            try {
                n = p.length == 2 ? Long.parseLong(p[1]) : -1;
            }
            catch (NumberFormatException ex) {
                n = -1;
            }
            Long at = null;
            String code = null;
            if (svc != null && n >= 0 && "event".equals(p[0])) {
                List<Map<String, Object>> row = svc.getDb().query("SELECT ts, code FROM events WHERE id = ?", n);
                at = row.isEmpty() ? null : ((Number) row.get(0).get("ts")).longValue();
                code = row.isEmpty() ? null : (String) row.get(0).get("code");
            }
            else if (svc != null && n >= 0 && "photo".equals(p[0])) {
                Photo photo = svc.getDb().photo(n);
                at = photo == null ? null : photo.createdAtMs;
            }
            if (at == null) {
                return "'" + x + "' is not a journal event or photo that exists (event:<id>, photo:<id>)";
            }
            if (code != null && (BedModelStore.EVENT.equals(code) || code.startsWith("MOTION_"))) {
                return "'" + x + "' is a " + code + " row, not a measurement's";
            }
            if (notBefore != null && at < notBefore) {
                return "'" + x + "' is older than " + since + ": a new measurement names its own rows";
            }
        }
        return null;
    }

    /** When {@code id} was last marked removed (its {@value BedModelStore#EVENT} row), or null. */
    private Long removedAt(String id) throws SQLException, ApiException {
        String route = "POST /api/bed/objects/" + id + "/removed";
        for (Map<String, Object> e : ctx.svc().getDb().query("SELECT ts, message FROM events WHERE code = ? ORDER BY "
                + "id DESC", BedModelStore.EVENT)) {
            JsonElement m = new JsonParser().parse(String.valueOf(e.get("message")));
            if (m.isJsonObject() && route.equals(Json.str(m.getAsJsonObject(), "what", null))) {
                return ((Number) e.get("ts")).longValue();
            }
        }
        return null;
    }

    /**
     * Process's two measurements that clear a removed object (20:5x): the top camera's comparison of the old outline's
     * area with the stored empty-bed reference picture of that spot (diff within the engine's fixed threshold, never
     * the caller's), and N1 lowered to its floor at 3 points or more inside the outline with no contact, both since it
     * was marked removed ({@code removedAt}; null: any time). Anything less: 409, it stays removed.
     */
    private JsonObject cleared(JsonObject b, Long removedAt) throws ApiException, SQLException {
        List<String> needs = new ArrayList<>();
        JsonObject cam = member(b, "camera"), n1 = member(b, "n1");
        if (cam == null) {
            needs.add("camera: the top camera's comparison with the empty-bed reference picture {reference, diff, "
                    + "evidence}");
        }
        else {
            if (!cam.has("reference") || !cam.get("reference").isJsonPrimitive() || cam.get("reference").getAsString()
                    .trim().isEmpty()) {
                needs.add("camera: the reference picture it was compared with");
            }
            Double max = ctx.svc().getConfig().emptyBedMaxDiff;
            if (!number(cam, "diff")) {
                needs.add("camera: diff, the comparison's difference");
            }
            else if (max == null) {
                needs.add("camera: the engine's threshold for the comparison is not set (dispense-config "
                        + "emptyBedMaxDiff, Process's number)");
            }
            else if (!(cam.get("diff").getAsDouble() <= max)) {
                needs.add("camera: the difference " + cam.get("diff") + " is above the engine's threshold " + max
                        + ": something is still there");
            }
            else {
                cam.addProperty("threshold", max); // the threshold on record is the one applied
            }
            String e = evidence(cam.get("evidence"), removedAt, "its removal");
            if (e != null) {
                needs.add("camera: " + e);
            }
        }
        if (n1 == null) {
            needs.add("n1: N1 lowered to its floor at 3 points inside the outline with no contact {points, floorRaw, "
                    + "evidence}");
        }
        else {
            if (!number(n1, "points") || n1.get("points").getAsDouble() < 3) {
                needs.add("n1: N1 to its floor at at least 3 points inside the outline");
            }
            if (!number(n1, "floorRaw")) {
                needs.add("n1: floorRaw, the raw Z it was lowered to");
            }
            String e = evidence(n1.get("evidence"), removedAt, "its removal");
            if (e != null) {
                needs.add("n1: " + e);
            }
        }
        if (!needs.isEmpty()) {
            throw new ApiException(409, MEASUREMENT_NEEDED, "clearing a removed object needs both measurements: "
                    + String.join("; ", needs)).with("needs", needs);
        }
        JsonObject c = new JsonObject();
        c.add("camera", cam);
        c.add("n1", n1);
        c.add("by", b.has("by") ? b.get("by") : JsonNull.INSTANCE);
        c.add("atMs", b.has("atMs") ? b.get("atMs") : new JsonPrimitive(System.currentTimeMillis()));
        return c;
    }

    // ------------------------------------------------------------------------------------------
    // The tree
    // ------------------------------------------------------------------------------------------

    private static boolean number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && Double.isFinite(e.getAsDouble());
    }

    private static JsonObject member(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonObject copy(JsonObject o) {
        return new JsonParser().parse(o.toString()).getAsJsonObject();
    }

    /** The entry {@code id} of the list {@code key}; 404 if none. */
    private static JsonObject find(JsonObject model, String key, String id) throws ApiException {
        for (JsonElement e : model.getAsJsonArray(key)) {
            if (e.isJsonObject() && id.equals(e.getAsJsonObject().get("id").getAsString())) {
                return e.getAsJsonObject();
            }
        }
        throw ApiException.notFound("no " + ("objects".equals(key) ? "object" : "tool") + " '" + id + "' in the bed "
                + "model (GET /api/bed)");
    }

    /** Replaces the entry {@code id} of the list {@code key} by {@code with} (null: takes it out); returns the old one. */
    private static JsonObject replace(JsonObject model, String key, String id, JsonObject with) throws ApiException {
        JsonObject old = find(model, key, id);
        JsonArray l = new JsonArray();
        for (JsonElement e : model.getAsJsonArray(key)) {
            if (e != old) {
                l.add(e);
            }
            else if (with != null) {
                l.add(with);
            }
        }
        model.add(key, l);
        return old;
    }

    /** {@code base}, or base-2, base-3 ... until no object has it. */
    private static String unique(BedModel m, String base) {
        Set<String> ids = new HashSet<>();
        for (BedModel.Obj o : m.obstacles()) {
            ids.add(o.id);
        }
        String b = base.isEmpty() ? "object" : base.length() > 56 ? base.substring(0, 56) : base;
        String id = b;
        for (int i = 2; ids.contains(id); i++) {
            id = b.replaceAll("-\\d+$", "") + "-" + i;
        }
        return id;
    }
}
