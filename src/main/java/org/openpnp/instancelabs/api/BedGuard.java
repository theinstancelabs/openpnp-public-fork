package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openpnp.instancelabs.dispense.BedModel;
import org.openpnp.instancelabs.dispense.BedModelStore;
import org.openpnp.instancelabs.dispense.CollisionCheck;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.HomeTracker;
import org.openpnp.instancelabs.dispense.OpenPnpRawMapper;
import org.openpnp.instancelabs.dispense.ZCheck;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Axis;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Machine;
import org.openpnp.spi.base.AbstractHeadMountable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * BR-98 stage 1 (collision-model-design.md §3, §6): the collision check where the API moves the machine (jog, move-to,
 * the G-code passthrough) and the bed's read views. The model is the engine's ({@link BedModelStore}). No file: the
 * check is off and every route runs as before. A file that fails validation: every hooked motion is refused (fail
 * closed). A valid one: each path is proven before its first line; clear journals one {@value #PROOF} row, blocked
 * answers 409 {@code collision} (§6.3; a stable key the deployer maps) and journals a {@value #REFUSED} row with
 * that body. A needle tip end or a K measured before the engine's last needle change cannot prove anything: every
 * hooked motion answers 409 {@code measurement-needed} until both are measured again (#66 review MAJOR 3, review 2
 * MAJOR 1).
 *
 * <p>The start in this stage is OpenPnP's raw position while the head's place is known (homed in this connection and
 * BR-02's position known): source {@code homed}, X/Y error 0. Otherwise it is unknown, and the routes already refuse
 * before (nothing moves unhomed); a model in place refuses then too.</p>
 */
final class BedGuard {
    static final String NO_MODEL = "no bed model yet, moves are not checked for collisions";
    static final String COLLISION = "collision";
    static final String PROOF = "MOTION_PROOF";
    static final String REFUSED = "MOTION_REFUSED";

    private final ApiContext ctx;

    BedGuard(ApiContext ctx) {
        this.ctx = ctx;
    }

    /** Where a path starts: raw X/Y (= head X/Y) and raw Z, with their source. */
    static final class Start {
        final boolean known;
        /** homed | unknown */
        final String source;
        /** Why it is unknown, and what makes it known again; null when known. */
        final String why, next;
        final double x, y, z;
        /** The home the raw Z is in (for the model's values of other homes), or null. */
        final Long homeAtMs;

        Start(String why, String next, double x, double y, double z, Long homeAtMs) {
            this.known = why == null;
            this.source = known ? "homed" : "unknown";
            this.why = why;
            this.next = next;
            this.x = x;
            this.y = y;
            this.z = z;
            this.homeAtMs = homeAtMs;
        }

        /** The same start at another raw position (where the passthrough's last lines left the head; null: unknown). */
        Start at(double[] xyz) {
            return xyz == null ? unknown("the last G-code batch was cut short, so where it left the head is not known",
                    "send M400 alone first: OpenPnP then reads where the head is")
                    : new Start(why, next, xyz[0], xyz[1], xyz[2], homeAtMs);
        }

        /** The same start, not known: why, and what makes it known again. */
        Start unknown(String why, String next) {
            return new Start(why, next, x, y, z, homeAtMs);
        }
    }

    /** The engine's model state, or null without an engine. */
    BedModelStore.State state() {
        DispenseService s = ctx.svcOrNull();
        return s == null ? null : s.getBedModel().state();
    }

    /** Whether the check is on: a model file is there (valid or not), so the hooked routes build and prove paths. */
    boolean active() {
        BedModelStore.State st = state();
        return st != null ? st.exists : BedModelStore.fileFor(ctx.configuration.getConfigurationDirectory()).isFile();
    }

    /**
     * OpenPnP's raw X, Y and Z controller axes: N1's, as the engine's read-backs take them (#66 NIT); without the
     * engine (nothing is proven then), the machine's first X, Y and Z controller axes.
     */
    ReferenceControllerAxis[] xyz(Machine m) throws ApiException {
        DispenseService svc = ctx.svcOrNull();
        if (svc != null && svc.getProbeNozzle() != null && m instanceof ReferenceMachine) {
            try {
                return OpenPnpRawMapper.forProbeNozzle((ReferenceMachine) m, svc.getProbeNozzle(), svc.getConfig()
                        .bAxisName).xyzAxes();
            }
            catch (Exception e) {
                throw ApiException.unavailable("N1's X, Y and Z axes cannot be read (" + e.getMessage() + ")");
            }
        }
        ReferenceControllerAxis[] a = new ReferenceControllerAxis[3];
        for (Axis x : m.getAxes()) {
            if (x instanceof ReferenceControllerAxis) {
                int i = x.getType() == Axis.Type.X ? 0 : x.getType() == Axis.Type.Y ? 1 : x.getType() == Axis.Type.Z ? 2
                        : -1;
                if (i >= 0 && a[i] == null) {
                    a[i] = (ReferenceControllerAxis) x;
                }
            }
        }
        if (a[0] == null || a[1] == null || a[2] == null) {
            throw ApiException.unavailable("the machine has no X, Y and Z controller axes");
        }
        return a;
    }

    Start start(Machine m) throws ApiException {
        ReferenceControllerAxis[] a = xyz(m);
        double x = mm(a[0]), y = mm(a[1]), z = mm(a[2]);
        DispenseService s = ctx.svcOrNull();
        String why = s == null ? "the dispense engine is not running" : !m.isEnabled() ? "the machine is not started"
                : !m.isHomed() ? "the machine is not homed" : s.positionUnknownWhy();
        return new Start(why, why == null ? null : "home first", x, y, z, s == null ? null : s.getHomeTracker()
                .frameHomeAtMs());
    }

    private static double mm(ReferenceControllerAxis a) {
        return a.getLengthCoordinate().convertToUnits(LengthUnit.Millimeters).getValue();
    }

    /** The raw X, Y, Z OpenPnP would drive to for {@code hm} at {@code to}, from {@code base} (null: where it is). */
    double[] rawTarget(Machine m, HeadMountable hm, Location to, AxesLocation base) throws Exception {
        ReferenceControllerAxis[] a = xyz(m);
        AxesLocation t = (base == null ? new AxesLocation(m) : base).put(((AbstractHeadMountable) hm).toRaw(
                hm.toHeadLocation(to)));
        return new double[] {t.getCoordinate(a[0], LengthUnit.Millimeters), t.getCoordinate(a[1],
                LengthUnit.Millimeters), t.getCoordinate(a[2], LengthUnit.Millimeters)};
    }

    /**
     * Move-to's three legs, as {@code MovableUtils.moveToLocationAtSafeZ} drives them: the head up into the Z axis's
     * safe zone (every head-mountable to its safe Z: raw clamped to the zone), X/Y with the tool to its safe Z, then
     * the move to the target, which is no move here (the route's target is the tool's safe Z).
     */
    List<CollisionCheck.Leg> moveToLegs(Machine m, HeadMountable hm, Location to, Start s, String tool)
            throws Exception {
        ReferenceControllerAxis z = xyz(m)[2];
        double zs = s.z;
        if (z.isSafeZoneLowEnabled()) {
            zs = Math.max(zs, z.getSafeZoneLow().convertToUnits(LengthUnit.Millimeters).getValue());
        }
        if (z.isSafeZoneHighEnabled()) {
            zs = Math.min(zs, z.getSafeZoneHigh().convertToUnits(LengthUnit.Millimeters).getValue());
        }
        double[] t = rawTarget(m, hm, to, new AxesLocation(m).put(new AxesLocation(z, zs)));
        List<CollisionCheck.Leg> l = new ArrayList<>();
        l.add(new CollisionCheck.Leg("up to safe Z", s.x, s.y, s.z, s.x, s.y, zs));
        l.add(new CollisionCheck.Leg("X/Y at safe Z", s.x, s.y, zs, t[0], t[1], t[2]));
        l.add(new CollisionCheck.Leg(who(tool) + " at the target (no move)", t[0], t[1], t[2], t[0], t[1], t[2]));
        return l;
    }

    /**
     * Proves a path before anything moves. Null when the check is off (no model file); otherwise the proof
     * (journaled as {@value #PROOF} unless {@code dry}), or a 409 {@code collision} (journaled as {@value #REFUSED}
     * unless {@code dry}).
     */
    Map<String, Object> prove(String what, String tool, Start s, List<CollisionCheck.Leg> legs,
            CollisionCheck.Options o, boolean dry) throws ApiException {
        if (!active()) {
            return null;
        }
        DispenseService svc = ctx.svcOrNull();
        BedModelStore.State st = svc == null ? null : svc.getBedModel().state();
        if (st == null) {
            throw refusal(COLLISION, null, "the dispense engine is not running, so the bed model cannot be checked",
                    "restart OpenPnP (il-openpnp) so its engine starts", legs, null, dry);
        }
        if (st.model == null) {
            throw refusal(COLLISION, null, "the bed model is not valid (" + st.error + ")", "send a valid model with "
                    + "PUT /api/bed", legs, null, dry);
        }
        BedModel m = st.model;
        Long changed = svc.getConfig().needleChangedAtMs;
        List<String> old = new ArrayList<>(), steps = new ArrayList<>();
        for (BedModel.Tool t : m.tools()) {
            // the full fix: the engine derives the tip end from N2's head offset, the landing and the trim at a mount
            if ("needle".equals(t.moves) && changed != null && (t.tipEndAtMs == null || t.tipEndAtMs < changed)) {
                old.add("the needle's tip end (tools '" + t.id + "'.tipEnd)");
                steps.add("measure the mounted needle's tip end, then send it with PUT /api/bed/tools/" + t.id
                        + " (a later tipEnd.atMs and a measurement record)");
            }
        }
        // every needle height in a proof rests on K (review 2 MAJOR 1): its record is the see-saw's
        if (changed != null && (m.seesawAtMs == null || m.seesawAtMs < changed)) {
            old.add("K, the needle's length (seesaw.K, measured as seesaw.measured)");
            steps.add("measure K with the mounted needle, then send it with PUT /api/bed/seesaw (a new seesaw.measured "
                    + "record)");
        }
        if (!old.isEmpty()) {
            throw refusal(BedEndpoints.MEASUREMENT_NEEDED, null, String.join(" and ", old) + (old.size() > 1 ? " are"
                    : " is") + " from before the needle change at " + ZCheck.clock(changed), String.join("; ", steps),
                    legs, m.version, dry);
        }
        if (!s.known) {
            throw refusal(COLLISION, null, "the head's place is not known (" + s.why + ")", s.next, legs, m.version,
                    dry);
        }
        o.homeAtMs = s.homeAtMs;
        o.xyErrMm = 0;
        long t0 = System.nanoTime();
        CollisionCheck.Result r = CollisionCheck.check(m, legs, o);
        double ms = (System.nanoTime() - t0) / 1e6;
        if (!r.clear) {
            throw refusal(COLLISION, r.hit, why(r.hit), next(r.hit), legs, m.version, dry);
        }
        Map<String, Object> proof = Views.map("what", what, "tool", tool, "modelVersion", m.version, "start",
                startView(s), "legs", path(legs), "closest", r.hit == null ? null : Views.map("id", r.hit.object.id,
                        "name", r.hit.object.name, "tool", r.hit.tool.id, "layer", r.hit.layer.name, "leg", r.hit.leg,
                        "at", Views.map("x", r.hit.atX, "y", r.hit.atY), "clearanceMm", r.hit.mm, "vertical",
                        r.hit.vertical), "checkMs", Math.round(ms * 1000) / 1000.0);
        if (!dry) {
            journal("INFO", PROOF, proof);
        }
        return proof;
    }

    /** The 409 every hooked route and the dry run give (§6.3); journaled unless {@code dry}. */
    private ApiException refusal(String code, CollisionCheck.Hit h, String why, String next,
            List<CollisionCheck.Leg> legs, Integer version, boolean dry) {
        ApiException e = new ApiException(409, code, why + ": " + next).with("why", why).with("next", next)
                .with("tool", h == null ? null : h.tool.id).with("layer", h == null ? null : h.layer.name)
                .with("blocker", h == null ? null : Views.map("id", h.object.id, "name", h.object.name, "kind",
                        h.object.kind, "state", h.object.state)).with("shortfallMm", h == null ? null : h.mm)
                .with("toolLowestZ", h == null ? null : h.toolLowestZ).with("blockerTopZ", h == null ? null
                        : h.blockerTopZ).with("at", h == null ? null : Views.map("x", h.atX, "y", h.atY))
                .with("leg", h == null ? null : h.leg).with("path", path(legs)).with("modelVersion", version);
        if (!dry) {
            Map<String, Object> body = Views.map("error", e.code, "reason", e.getMessage());
            body.putAll(e.details);
            journal("WARN", REFUSED, body);
        }
        return e;
    }

    private static String why(CollisionCheck.Hit h) {
        String who = who(h.tool.id) + (h.layer.tip ? "" : "'s " + h.layer.name);
        if (!h.object.blocksEveryHeight()) {
            return who + " would hit " + h.object.name;
        }
        String state = h.object.state;
        return who + " would cross " + h.object.name + ", " + ("removed".equals(state) ? "removed and not checked since"
                : "measured".equals(state) ? "its top not measured" : "not measured since it was " + state);
    }

    private static String next(CollisionCheck.Hit h) {
        if (h.mm == null) {
            return "go around " + h.object.name + " or measure it first";
        }
        return String.format(Locale.ROOT, "stay %.2f mm higher there or go around %s", h.mm, h.object.name);
    }

    /** n1: N1; needle: the needle; another tool: the tool. */
    static String who(String tool) {
        return "n1".equals(tool) ? "N1" : tool == null ? "the head" : "the " + tool;
    }

    static List<Map<String, Object>> path(List<CollisionCheck.Leg> legs) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (CollisionCheck.Leg g : legs) {
            l.add(Views.map("leg", g.label, "from", Views.map("x", g.ax, "y", g.ay, "z", g.az), "to", Views.map("x",
                    g.bx, "y", g.by, "z", g.bz)));
        }
        return l;
    }

    static Map<String, Object> startView(Start s) {
        return Views.map("x", s.x, "y", s.y, "z", s.z, "source", s.source, "xyErrMm", s.known ? 0.0 : null, "seesaw",
                s.known ? "homed" : "worst-case", "homeAtMs", s.homeAtMs);
    }

    private void journal(String level, String code, Map<String, Object> row) {
        DispenseService s = ctx.svcOrNull();
        if (s != null) {
            s.getDb().event(null, level, code, Json.toJson(row));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------------------------------

    /** /api/status's {@code bed}: version, valid, unmeasured, headKnown, why. */
    Map<String, Object> statusView() {
        BedModelStore.State st = state();
        BedModel m = st == null ? null : st.model;
        Integer unmeasured = null;
        if (m != null) {
            unmeasured = 0;
            for (BedModel.Obj o : m.obstacles()) {
                unmeasured += o.blocksEveryHeight() ? 1 : 0;
            }
        }
        return Views.map("version", m == null ? null : m.version, "valid", st == null || !st.exists ? null
                : st.model != null, "unmeasured", unmeasured, "headKnown", headKnown(), "why", why(st));
    }

    private Boolean headKnown() {
        Machine m = ctx.configuration.getMachine();
        try {
            return m != null && start(m).known;
        }
        catch (ApiException e) {
            return false;
        }
    }

    private static String why(BedModelStore.State st) {
        if (st == null) {
            return "the dispense engine is not running: the bed model is not read";
        }
        if (!st.exists) {
            return NO_MODEL;
        }
        return st.model == null ? "the bed model is not valid (" + st.error + "): every move is refused" : null;
    }

    /** GET /api/bed (§6.1): the model as stored, each tool's lowest point now, the head and the see-saw. */
    Map<String, Object> view() throws ApiException {
        BedModelStore.State st = state();
        BedModel m = st == null ? null : st.model;
        Machine mach = ctx.configuration.getMachine();
        Start s = null;
        try {
            s = mach == null ? null : start(mach);
        }
        catch (ApiException e) {
            // N1's axes cannot be read: the head shows as unknown, the tools at their worst case
        }
        Map<String, Object> out = Views.map("version", m == null ? null : m.version, "valid", st == null || !st.exists
                ? null : m != null, "error", st == null ? null : st.error, "why", why(st), "digest", st == null ? null
                        : st.digest);
        out.put("head", s == null ? Views.map("x", null, "y", null, "known", false, "source", "unknown", "errMm", null,
                "atMs", null) : Views.map("x", s.x, "y", s.y, "known", s.known, "source", s.source, "errMm", s.known
                        ? 0.0 : null, "atMs", s.known ? s.homeAtMs : null));
        if (m == null) {
            return out;
        }
        JsonObject j = m.json();
        out.put("changedAtMs", j.get("changedAtMs"));
        out.put("changedBy", j.get("changedBy"));
        out.put("zMarginMm", m.zMarginMm);
        out.put("contactDepthMm", m.contactDepthMm);
        out.put("bed", j.get("bed"));
        for (JsonElement e : j.getAsJsonArray("objects")) {
            JsonObject o = e.getAsJsonObject();
            o.addProperty("stateLabel", stateLabel(m.object(o.get("id").getAsString())));
        }
        out.put("objects", j.get("objects"));
        for (JsonElement e : j.getAsJsonArray("tools")) {
            JsonObject t = e.getAsJsonObject();
            BedModel.Tool tool = m.tool(t.get("id").getAsString());
            double low = lowestNow(m, tool, s);
            t.addProperty("lowestZNow", Double.isFinite(low) ? low : null);
            t.addProperty("lowest", !tool.rides() ? "measured" : s != null && s.known && m.mapKnown(tool) ? "homed"
                    : "worst-case");
        }
        out.put("tools", j.get("tools"));
        JsonObject seesaw = j.getAsJsonObject("seesaw");
        boolean known = s != null && s.known;
        seesaw.addProperty("state", known ? "homed" : "worst-case");
        seesaw.addProperty("source", known ? "home" : "unknown");
        seesaw.addProperty("atMs", known ? s.homeAtMs : null);
        seesaw.addProperty("rawZ", known ? s.z : null);
        out.put("seesaw", seesaw);
        out.put("reach", j.getAsJsonObject("bed").get("reach"));
        return out;
    }

    /** "removed, not checked" until both measurements clear it; added and moved: not measured. */
    static String stateLabel(BedModel.Obj o) {
        switch (o.state) {
            case "removed":
                return "removed, not checked";
            case "moved":
            case "added":
                return o.state + ", not measured";
            default:
                return o.topZ == null ? "top not measured" : "measured";
        }
    }

    /**
     * The tool's lowest layer bottom now: at the head and raw Z when known; else the worst case (the tips at their
     * end stops) over the bed's corners.
     */
    private static double lowestNow(BedModel m, BedModel.Tool t, Start s) {
        double low = Double.POSITIVE_INFINITY;
        BedModel.Poly bed = m.obstacles().get(0).poly;
        for (BedModel.Layer l : t.layers) {
            double bottom;
            if (l.bottomZ != null) {
                bottom = l.bottomZ;
            }
            else if (s != null && s.known) {
                bottom = m.tipZ(t, s.z, s.x, s.y) + l.aboveTipMm;
            }
            else {
                bottom = Double.POSITIVE_INFINITY;
                for (int i = 0; i < bed.size(); i++) {
                    bottom = Math.min(bottom, m.tipZ(t, null, bed.x(i) - t.tipDx, bed.y(i) - t.tipDy) + l.aboveTipMm);
                }
            }
            low = Math.min(low, bottom);
        }
        return low;
    }
}
