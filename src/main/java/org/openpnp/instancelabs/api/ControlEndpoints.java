package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.openpnp.instancelabs.dispense.CollisionCheck;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.SurfacePlane;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MotionPlanner;
import org.openpnp.spi.base.AbstractHeadMountable;
import org.openpnp.util.MovableUtils;
import org.pmw.tinylog.Logger;

import com.google.gson.JsonObject;

/**
 * BR-49 + BR-53 (P0 after BR-72, docs/backend-requests.md "BR-49 + BR-53 API"): the user's Machine › Control. Jog
 * and move-to through OpenPnP's own motion on its machine thread (guarded writes: BR-72's lock), and the top and
 * bottom cameras read through OpenPnP's own capture (GETs, never locked).
 *
 * <p>Rules (#19 review of BR-49: N1 and the needle share one Z axis, a see-saw): nothing moves unhomed, off, busy
 * (a task, a G-code batch, a pressure hold, a pad calibration) or switching off; one motion request at a time, checked
 * again on OpenPnP's machine thread; one press at most {@value #MAX_XY_MM} mm in X/Y and {@value #MAX_Z_MM} mm in Z;
 * X/Y only with both nozzles at travel height (their safe zones); a Z press puts BOTH nozzles where OpenPnP's
 * transforms say and holds each to its floor at its own X/Y: N1 at most {@value #N1_BELOW_SURFACE_MM} mm below a probed
 * surface under it (it may touch: the by-hand probe), the needle with a current frame not below the surface under it
 * (contact = surface + offset) nor its I4 floor, without one not below its safe zone. Move-to travels at safe Z and
 * ends at the tool's safe Z (both nozzles at travel height). A STOP ends either at once; a move that saw one answers
 * 409.</p>
 */
final class ControlEndpoints {
    static final double MAX_XY_MM = 10;
    static final double MAX_Z_MM = 2;
    static final double N1_BELOW_SURFACE_MM = 0.5;
    /** A surface counts under a tool within this margin of its probed points' extent. */
    static final double SURFACE_MARGIN_MM = 2;
    static final long MAX_SETTLE_MS = 2000;
    /** How long a motion request waits for its move (tests shorten it). */
    static volatile long moveTimeoutMs = 120_000;

    private final ApiContext ctx;
    private final MachineEndpoints machine;
    /** One motion request at a time (#19 review of BR-49, MINOR 5). */
    private final ReentrantLock motionLock = new ReentrantLock();

    ControlEndpoints(ApiContext ctx, MachineEndpoints machine) {
        this.ctx = ctx;
        this.machine = machine;
    }

    void register(ApiServer s) {
        s.route("POST", "/api/jog", this::jog);
        s.route("POST", "/api/move-to", this::moveTo);
        s.route("POST", "/api/motion/check", this::check);
        s.route("GET", "/api/cameras", r -> cameras());
        s.route("GET", "/api/cameras/([a-z]{1,10})/frame\\.jpg", this::frame);
    }

    // ------------------------------------------------------------------------------------------
    // Jog and move-to
    // ------------------------------------------------------------------------------------------

    Object jog(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        String tool = Json.str(b, "tool", null);
        double[] d = press(b, tool);
        Machine m = ctx.machine();
        HeadMountable hm = tool(tool);
        return motion(m, "jog", () -> {
            Location from = mm(hm.getLocation());
            Location to = from.add(new Location(LengthUnit.Millimeters, d[0], d[1], d[2], 0));
            proveJog(m, hm, tool, to, d, false);
            hm.moveTo(to);
            m.getMotionPlanner().waitForCompletion(hm, MotionPlanner.CompletionType.WaitForStillstand);
            return answer(tool, hm, from);
        });
    }

    /** A press as BR-49 limits it: {dx, dy, dz}. */
    private static double[] press(JsonObject b, String tool) throws ApiException {
        double dx = num(b, "dx"), dy = num(b, "dy"), dz = num(b, "dz");
        if (Math.abs(dx) > MAX_XY_MM || Math.abs(dy) > MAX_XY_MM) {
            throw ApiException.badRequest("a press moves at most " + plain(MAX_XY_MM) + " mm in X and Y");
        }
        if (Math.abs(dz) > MAX_Z_MM) {
            throw ApiException.badRequest("a press moves at most " + plain(MAX_Z_MM) + " mm in Z");
        }
        if (dx == 0 && dy == 0 && dz == 0) {
            throw ApiException.badRequest("dx, dy or dz: how far to move");
        }
        if (dz != 0 && (dx != 0 || dy != 0)) {
            // X/Y only at travel height: a press that also changes Z would leave it on the way (re-review MINOR 2)
            throw ApiException.badRequest("one press moves X/Y or Z, not both");
        }
        if ("camera".equals(tool) && dz != 0) {
            throw ApiException.refused("the camera has no Z of its own: jog N1 or the needle up or down");
        }
        return new double[] {dx, dy, dz};
    }

    /**
     * The jog's own checks (travel height for X/Y, the floors for Z: they stay), then BR-98's proof of its one leg; a
     * refusal throws before anything moves.
     */
    private Map<String, Object> proveJog(Machine m, HeadMountable hm, String tool, Location to, double[] d,
            boolean dry) throws Exception {
        if (d[0] != 0 || d[1] != 0) {
            requireTravelHeight();
        }
        if (d[2] != 0) {
            requireFloors(m, hm, to);
        }
        if (!ctx.bed.active()) {
            return null;
        }
        BedGuard.Start s = ctx.bed.start(m);
        double[] t = ctx.bed.rawTarget(m, hm, to, null);
        StringBuilder label = new StringBuilder("jog");
        for (int i = 0; i < 3; i++) {
            if (d[i] != 0) {
                label.append(' ').append("XYZ".charAt(i)).append(' ').append(d[i] > 0 ? "+" : "").append(plain(d[i]));
            }
        }
        return ctx.bed.prove("jog", tool, s, Collections.singletonList(new CollisionCheck.Leg(label.toString(), s.x,
                s.y, s.z, t[0], t[1], t[2])), jogOptions(m, hm, tool, to), dry);
    }

    /**
     * BR-98's jog exemption (design §3 step 4): over a probed surface the jogged tool's tip may go down to the floor
     * this route already holds it to ({@link #requireFloors}); every other layer and tool stays strict.
     */
    private CollisionCheck.Options jogOptions(Machine m, HeadMountable hm, String tool, Location to) throws Exception {
        CollisionCheck.Options o = CollisionCheck.Options.strict(null);
        if ("camera".equals(tool) || !Double.isFinite(surfaceUnder(to.getX(), to.getY()))) {
            return o;
        }
        double floor = isNeedle(hm) ? needleFloor(hm, to.getX(), to.getY()) : n1Floor(to.getX(), to.getY());
        if (Double.isFinite(floor)) {
            o.jogTool = tool;
            o.jogFloorRaw = ctx.bed.rawTarget(m, hm, new Location(LengthUnit.Millimeters, to.getX(), to.getY(), floor,
                    to.getRotation()), null)[2];
        }
        return o;
    }

    Object moveTo(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        String tool = Json.str(b, "tool", null);
        Machine m = ctx.machine();
        HeadMountable hm = tool(tool);
        double[] xy = place(b);
        return motion(m, "move-to", () -> {
            Location from = mm(hm.getLocation());
            Location to = moveToTarget(hm, tool, from, xy);
            if (ctx.bed.active()) {
                BedGuard.Start s = ctx.bed.start(m);
                ctx.bed.prove("move-to", tool, s, ctx.bed.moveToLegs(m, hm, to, s, tool), CollisionCheck.Options
                        .strict(null), false);
            }
            MovableUtils.moveToLocationAtSafeZ(hm, to);
            m.getMotionPlanner().waitForCompletion(hm, MotionPlanner.CompletionType.WaitForStillstand);
            return answer(tool, hm, from);
        });
    }

    /**
     * It ends at the tool's safe Z, the bottom of its safe zone: on the see-saw that is the other nozzle's zone too, so
     * both end at travel height (#19 review of BR-49: never a tool left low at the new place).
     */
    private static Location moveToTarget(HeadMountable hm, String tool, Location from, double[] xy) {
        double z = "camera".equals(tool) ? from.getZ() : safeLow(hm);
        return new Location(LengthUnit.Millimeters, xy[0], xy[1], z, from.getRotation());
    }

    /** A named place's X/Y, or x and y. */
    private double[] place(JsonObject b) throws ApiException {
        String place = Json.str(b, "place", null);
        Double x = Json.num(b, "x", null), y = Json.num(b, "y", null);
        if (place != null) {
            DispenseConfig.NamedLocation p = ctx.svc().getConfig().location(place);
            if (p == null) {
                throw ApiException.notFound("no place named '" + place + "' (GET /api/locations)");
            }
            x = p.x;
            y = p.y;
        }
        if (x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y)) {
            throw ApiException.badRequest("a place, or x and y");
        }
        return new double[] {x, y};
    }

    /**
     * BR-98 (design §6.2): the dry run of a jog or a move-to: the route's own checks and the path's proof, nothing
     * moved, no lock, nothing journaled. Clear: 200 with the closest object; blocked: the 409 the route would give.
     */
    Object check(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        String kind = Json.str(b, "kind", null);
        if ("home".equals(kind) || "run".equals(kind)) {
            throw ApiException.badRequest("kind '" + kind + "' is not in this build yet: it checks jog and move-to");
        }
        if (!"jog".equals(kind) && !"move-to".equals(kind)) {
            throw ApiException.badRequest("'kind' is jog, move-to, home or run");
        }
        String tool = Json.str(b, "tool", null);
        double[] d = "jog".equals(kind) ? press(b, tool) : place(b);
        Machine m = ctx.machine();
        HeadMountable hm = tool(tool);
        requireFree(m);
        if (ctx.passthrough.positionStale()) {
            // the real routes re-read it first; a dry run sends nothing and holds no lock (#66 review MINOR e)
            throw ApiException.refused("OpenPnP's position is not re-read after the G-code passthrough's moves yet, so "
                    + "the path cannot be checked from where the head is: the move itself reads it first; for a dry run "
                    + "the lock holder sends M400");
        }
        Location from = mm(hm.getLocation());
        Map<String, Object> proof;
        if ("jog".equals(kind)) {
            proof = proveJog(m, hm, tool, from.add(new Location(LengthUnit.Millimeters, d[0], d[1], d[2], 0)), d, true);
        }
        else if (ctx.bed.active()) {
            Location to = moveToTarget(hm, tool, from, d);
            BedGuard.Start s = ctx.bed.start(m);
            proof = ctx.bed.prove("move-to", tool, s, ctx.bed.moveToLegs(m, hm, to, s, tool),
                    CollisionCheck.Options.strict(null), true);
        }
        else {
            proof = null;
        }
        if (proof == null) {
            return Views.map("clear", null, "why", BedGuard.NO_MODEL);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> closest = (Map<String, Object>) proof.get("closest");
        return Views.map("clear", true, "clearanceMm", closest == null ? null : closest.get("clearanceMm"), "closest",
                closest, "path", proof.get("legs"), "modelVersion", proof.get("modelVersion"), "start", proof.get(
                        "start"));
    }

    @FunctionalInterface
    private interface Task {
        Object run() throws Exception;
    }

    /**
     * One motion request at a time (a double press, two tabs), on OpenPnP's machine thread where the state is checked
     * again and the target and the floors are worked out (nothing else moves the machine meanwhile). A STOP while it
     * ran answers 409 even when the move's last ok came (the STOP's flush is not the head arriving).
     */
    private Object motion(Machine m, String what, Task task) throws Exception {
        if (!motionLock.tryLock()) {
            throw ApiException.refused("another move is still running: wait for it");
        }
        try {
            long stops = ctx.stops.get();
            requireFree(m);
            // OpenPnP busy (its own task: a home, a jog of its window): refused at once, never queued behind it (a
            // task OpenPnP drops would leave this press unanswered for minutes; re-review MINOR 1)
            ctx.requireMachineIdle();
            ctx.passthrough.resyncIfStale();
            // claimed by the task when it begins, or by the timeout first: then it does nothing (cancel(false) is true
            // for a running task too, so it cannot tell; #21 review MAJOR 1)
            AtomicBoolean claimed = new AtomicBoolean();
            Future<Object> f = m.submit(() -> {
                if (!claimed.compareAndSet(false, true)) {
                    return null; // the request timed out before this began: nothing is done
                }
                try {
                    requireFree(m);
                    if (ctx.passthrough.positionStale()) {
                        throw ApiException.refused("OpenPnP's position is not re-read after the G-code passthrough yet: "
                                + "try again in a moment");
                    }
                    return task.run();
                }
                catch (Exception e) {
                    return e; // returned, not thrown: OpenPnP drops every task queued behind one that throws
                }
            }, null, true);
            Object out;
            try {
                out = f.get(moveTimeoutMs, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e) {
                claimed.compareAndSet(false, true); // not started yet: it never runs (nobody hears its answer)
                throw e;
            }
            catch (TimeoutException e) {
                // no f.cancel: a cancelled task left in OpenPnP's queue keeps the machine busy after the task ahead of
                // it ends (follow-up review); a task the timeout claimed returns null when it runs
                boolean neverRan = claimed.compareAndSet(false, true);
                long s = moveTimeoutMs / 1000;
                throw ApiException.refused("the " + what + " did not " + (neverRan ? "start within " + s
                        + " s: it will not run" : "end within " + s + " s: look at the machine, press STOP if it still "
                                + "moves"));
            }
            catch (ExecutionException e) {
                out = e.getCause();
            }
            if (out instanceof ApiException) {
                throw (ApiException) out;
            }
            if (out instanceof Exception) {
                String why = MachineEndpoints.rootMessage((Exception) out);
                Logger.info("web {}: refused or stopped: {}", what, why);
                throw ApiException.refused((m.isEnabled() ? "the move was refused: " : "the move was stopped: ") + why);
            }
            if (ctx.stops.get() != stops || !m.isEnabled() || ctx.switchOff.inProgress()) {
                throw ApiException.refused("the move was stopped (STOP): the head may be short of its target; Start, "
                        + "then Home");
            }
            return out;
        }
        finally {
            motionLock.unlock();
        }
    }

    /** The checks every motion makes, before it is queued and again on the machine thread. */
    private void requireFree(Machine m) throws ApiException {
        if (!m.isEnabled()) {
            throw ApiException.refused("the machine is not started: press Start first");
        }
        ctx.requireNotSwitchingOff();
        if (!m.isHomed()) {
            throw ApiException.refused("the machine is not homed: Home first");
        }
        DispenseService svc = ctx.svcOrNull();
        if (svc != null && svc.isBusy()) {
            throw ApiException.refused("a dispense task is running: wait for it or press STOP");
        }
        if (ctx.padCalJobs.lockReason() != null) {
            throw ApiException.refused(ctx.padCalJobs.lockReason());
        }
        if (ctx.passthrough.running()) {
            throw ApiException.refused("a G-code batch is running: wait for it or press STOP");
        }
        if (ctx.pressure.isActive()) {
            throw ApiException.refused("the pressure is on: Pressure off first (nothing moves under pressure here)");
        }
    }

    /** X/Y moves only with both nozzles at travel height: nothing is dragged across the bed (#19 review of BR-49). */
    private void requireTravelHeight() throws ApiException {
        for (HeadMountable n : nozzles()) {
            double z = mm(n.getLocation()).getZ(), low = safeLow(n);
            if (Double.isFinite(low) && z < low - 1e-6) {
                throw ApiException.refused(String.format(Locale.ROOT, "%s is below its safe zone (Z %.2f, the zone "
                        + "starts at %.2f): raise it first; X and Y move only at travel height", name(n), z, low));
            }
        }
    }

    /**
     * Both nozzles where they would be if {@code hm} went to {@code to} (N1 and the needle share one Z axis: a see-saw,
     * lowering one raises the other), each against its own floor at its own X/Y (#19 review of BR-49, BLOCKER 1).
     */
    private void requireFloors(Machine m, HeadMountable hm, Location to) throws Exception {
        AxesLocation target = new AxesLocation(m).put(((AbstractHeadMountable) hm).toRaw(hm.toHeadLocation(to)));
        for (HeadMountable n : nozzles()) {
            Location at = mm(n.toHeadMountableLocation(((AbstractHeadMountable) n).toTransformed(target)));
            if (at.getZ() >= mm(n.getLocation()).getZ() - 1e-9) {
                continue; // going up (or not moving): always allowed, also out of a spot below a floor that rose
            }
            boolean needle = isNeedle(n);
            double floor = needle ? needleFloor(n, at.getX(), at.getY()) : n1Floor(at.getX(), at.getY());
            if (Double.isFinite(floor) && at.getZ() < floor - 1e-9) {
                throw ApiException.refused(String.format(Locale.ROOT, "that would put %s at Z %.2f over (%.1f, %.1f), "
                        + "below its floor %.2f (%s)%s", name(n), at.getZ(), at.getX(), at.getY(), floor,
                        needle ? needleFloorWhy() : plain(N1_BELOW_SURFACE_MM) + " mm below the probed surface under it",
                        n == hm ? "" : ": N1 and the needle share one Z (raising one lowers the other)"));
            }
        }
    }

    /** N1 at most 0.5 mm below a probed surface under it (it may touch: the by-hand probe); NaN: none known there. */
    private double n1Floor(double x, double y) {
        double s = surfaceUnder(x, y);
        return Double.isFinite(s) ? s - N1_BELOW_SURFACE_MM : Double.NaN;
    }

    /**
     * The needle: with its frame current (anchored and touched off since the last needle change), not below the
     * surface under it (needle contact = surface + offset) nor its I4 floor; without, not below its safe zone.
     */
    private double needleFloor(HeadMountable needle, double x, double y) {
        DispenseService svc = ctx.svcOrNull();
        if (svc == null) {
            return safeLow(needle);
        }
        DispenseConfig c = svc.getConfig();
        if (!needleFrameCurrent(svc)) {
            return safeLow(needle);
        }
        double floor = c.needleFloorZMm();
        double s = surfaceUnder(x, y);
        if (Double.isFinite(s)) {
            floor = Double.isFinite(floor) ? Math.max(floor, s + c.needleZOffsetMm) : s + c.needleZOffsetMm;
        }
        return floor;
    }

    private String needleFloorWhy() {
        DispenseService svc = ctx.svcOrNull();
        return svc != null && needleFrameCurrent(svc) ? "the surface under it, or its I4 floor"
                : "its safe zone: its Z frame is not set for this needle, or this home's Z check failed";
    }

    /**
     * The needle's Z frame holds now: anchored and touched off since the last needle change, and no failed Z check
     * of this home (re-review MINOR 5: after "Z moved" the frame's surface + offset is not to be trusted).
     */
    private static boolean needleFrameCurrent(DispenseService svc) {
        DispenseConfig c = svc.getConfig();
        if (!c.isNeedleAnchored() || !c.isNeedleTouchedOff()) {
            return false;
        }
        DispenseService.ZCheckPlan zc = svc.zCheckPlan(null, false);
        return zc == null || zc.last == null || zc.last.pass || !java.util.Objects.equals(zc.last.homeAtMs,
                zc.homeAtMs);
    }

    /** The highest probed surface under (x, y) (N1-contact Z), NaN where none is known. */
    private double surfaceUnder(double x, double y) {
        DispenseService svc = ctx.svcOrNull();
        DispenseConfig c = svc == null ? null : svc.getConfig();
        if (c == null || c.surfaces == null) {
            return Double.NaN;
        }
        double top = Double.NaN;
        for (DispenseConfig.SurfaceMap sm : c.surfaces.values()) {
            if (sm == null || sm.points == null || sm.points.size() < 3 || !covers(sm.points, x, y)) {
                continue;
            }
            try {
                double z = SurfacePlane.fit(sm.points).z(x, y);
                top = Double.isNaN(top) ? z : Math.max(top, z);
            }
            catch (RuntimeException e) {
                // a degenerate surface (collinear points) gives no floor
            }
        }
        return top;
    }

    /** N1 and the needle (the engine's), else every nozzle of the head (the engine starting or failed). */
    private List<HeadMountable> nozzles() throws ApiException {
        List<HeadMountable> l = new ArrayList<>();
        DispenseService svc = ctx.svcOrNull();
        if (svc != null) {
            if (svc.getProbeNozzle() != null) {
                l.add(svc.getProbeNozzle());
            }
            if (svc.getDispenserNozzle() != null) {
                l.add(svc.getDispenserNozzle());
            }
        }
        if (l.isEmpty()) {
            try {
                Head h = ctx.machine().getDefaultHead();
                if (h != null) {
                    l.addAll(h.getNozzles());
                }
            }
            catch (Exception e) {
                throw ApiException.unavailable("the machine has no head: " + e.getMessage());
            }
        }
        return l;
    }

    private boolean isNeedle(HeadMountable n) {
        DispenseService svc = ctx.svcOrNull();
        return svc != null && n == svc.getDispenserNozzle();
    }

    private String name(HeadMountable n) {
        return isNeedle(n) ? "the needle" : "N1";
    }

    /** The bottom of the tool's safe Z zone (OpenPnP's safe Z), NaN if none. */
    private static double safeLow(HeadMountable hm) {
        org.openpnp.model.Length z = hm.getSafeZ();
        return z == null ? Double.NaN : z.convertToUnits(LengthUnit.Millimeters).getValue();
    }

    private static Location mm(Location l) {
        return l.convertToUnits(LengthUnit.Millimeters);
    }

    private Map<String, Object> answer(String tool, HeadMountable hm, Location from) {
        Location now = hm.getLocation().convertToUnits(LengthUnit.Millimeters);
        return Views.map("tool", tool, "from", xyz(from), "to", xyz(now), "positions",
                machine.machineStatus().get("positions"));
    }

    private static Map<String, Object> xyz(Location l) {
        return Views.map("x", l.getX(), "y", l.getY(), "z", l.getZ());
    }

    /** camera = the head's down-looking camera; n1 = the probe nozzle; needle = the dispenser nozzle (N2). */
    private HeadMountable tool(String tool) throws ApiException {
        DispenseService svc = ctx.svcOrNull();
        HeadMountable hm = null;
        if ("camera".equals(tool)) {
            try {
                Head h = ctx.machine().getDefaultHead();
                hm = h == null ? null : h.getDefaultCamera();
            }
            catch (Exception e) {
                hm = null;
            }
        }
        else if ("n1".equals(tool)) {
            hm = svc == null ? null : svc.getProbeNozzle();
        }
        else if ("needle".equals(tool)) {
            hm = svc == null ? null : svc.getDispenserNozzle();
        }
        else {
            throw ApiException.badRequest("'tool' is camera, n1 or needle");
        }
        if (hm == null) {
            throw ApiException.unavailable("this machine has no " + label(tool));
        }
        return hm;
    }

    private static boolean covers(List<DispenseConfig.SurfacePoint> pts, double x, double y) {
        double x0 = Double.POSITIVE_INFINITY, x1 = Double.NEGATIVE_INFINITY;
        double y0 = Double.POSITIVE_INFINITY, y1 = Double.NEGATIVE_INFINITY;
        for (DispenseConfig.SurfacePoint p : pts) {
            x0 = Math.min(x0, p.x);
            x1 = Math.max(x1, p.x);
            y0 = Math.min(y0, p.y);
            y1 = Math.max(y1, p.y);
        }
        return x >= x0 - SURFACE_MARGIN_MM && x <= x1 + SURFACE_MARGIN_MM && y >= y0 - SURFACE_MARGIN_MM
                && y <= y1 + SURFACE_MARGIN_MM;
    }

    private static String label(String tool) {
        return "camera".equals(tool) ? "top camera" : "n1".equals(tool) ? "N1" : "the needle";
    }

    private static double num(JsonObject b, String name) throws ApiException {
        Double v = Json.num(b, name, 0.0);
        if (v == null || !Double.isFinite(v)) {
            throw ApiException.badRequest("'" + name + "' must be a number");
        }
        return v;
    }

    private static String plain(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ------------------------------------------------------------------------------------------
    // Cameras (read only)
    // ------------------------------------------------------------------------------------------

    Object cameras() throws ApiException {
        List<Map<String, Object>> out = new ArrayList<>();
        Camera top = camera("top"), bottom = camera("bottom");
        for (Camera c : new Camera[] {top, bottom}) {
            if (c != null) {
                out.add(Views.map("id", c == top ? "top" : "bottom", "name", c.getName(), "looking",
                        c.getLooking() == Camera.Looking.Up ? "up" : "down", "width", c.getWidth(), "height",
                        c.getHeight()));
            }
        }
        return Views.map("cameras", out);
    }

    Object frame(ApiServer.Request r) throws Exception {
        String id = r.group(1);
        Camera c = camera(id);
        if (c == null) {
            throw ApiException.notFound("no " + id + " camera here (GET /api/cameras)");
        }
        int w = r.queryInt("w", 0, 0, 4096);
        int settle = r.queryInt("settleMs", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);
        if (settle < 0 || settle > MAX_SETTLE_MS) {
            throw ApiException.badRequest("'settleMs' is 0.." + MAX_SETTLE_MS);
        }
        return machine.frame(c, w, settle);
    }

    /** top = the head's first camera looking down; bottom = the machine's first camera looking up. */
    private Camera camera(String id) throws ApiException {
        Machine m = ctx.machine();
        if ("top".equals(id)) {
            for (Head h : m.getHeads()) {
                for (Camera c : h.getCameras()) {
                    if (c.getLooking() == Camera.Looking.Down) {
                        return c;
                    }
                }
            }
        }
        else if ("bottom".equals(id)) {
            for (Camera c : m.getCameras()) {
                if (c.getLooking() == Camera.Looking.Up) {
                    return c;
                }
            }
        }
        return null;
    }
}
