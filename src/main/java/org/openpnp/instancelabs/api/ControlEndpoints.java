package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.HeightSwitch;
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
 * X/Y only with both nozzles at travel height (their safe zones; none on Z: no X/Y); no Z press without the engine and
 * both its nozzles, never one nozzle as both (it holds the floors); a Z press puts BOTH nozzles where OpenPnP's
 * transforms say and holds each to its floor at its own X/Y: N1 at most {@value #N1_BELOW_SURFACE_MM} mm below a probed
 * surface under it (it may touch: the by-hand probe), the needle with a current frame and its landing checked not below
 * the surface where its tip lands (N2 + its landing and trim; contact = surface + offset) nor the one under N2, nor its
 * I4 floor, without them not below its safe zone (none on Z: not lower at all); over the height switch (BR-94: the
 * circle around its place; the needle where its tip lands) neither below its safe zone, whatever its floor (none on Z:
 * no press down there at all). Move-to travels at safe Z and ends at the tool's safe Z (both nozzles at travel height).
 * A STOP ends either at once; a move that saw one answers 409.</p>
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
        s.route("GET", "/api/cameras", r -> cameras());
        s.route("GET", "/api/cameras/([a-z]{1,10})/frame\\.jpg", this::frame);
    }

    // ------------------------------------------------------------------------------------------
    // Jog and move-to
    // ------------------------------------------------------------------------------------------

    Object jog(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        String tool = Json.str(b, "tool", null);
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
        Machine m = ctx.machine();
        HeadMountable hm = tool(tool);
        return motion(m, "jog", () -> {
            Location from = mm(hm.getLocation());
            Location to = from.add(new Location(LengthUnit.Millimeters, dx, dy, dz, 0));
            if (dx != 0 || dy != 0) {
                requireTravelHeight();
            }
            if (dz != 0) {
                requireFloors(m, hm, to);
            }
            hm.moveTo(to);
            m.getMotionPlanner().waitForCompletion(hm, MotionPlanner.CompletionType.WaitForStillstand);
            return answer(tool, hm, from);
        });
    }

    Object moveTo(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        String tool = Json.str(b, "tool", null);
        String place = Json.str(b, "place", null);
        Double x = Json.num(b, "x", null), y = Json.num(b, "y", null);
        Machine m = ctx.machine();
        HeadMountable hm = tool(tool);
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
        double tx = x, ty = y;
        return motion(m, "move-to", () -> {
            Location from = mm(hm.getLocation());
            // it ends at the tool's safe Z, the bottom of its safe zone: on the see-saw that is the other nozzle's
            // zone too, so both end at travel height (#19 review of BR-49: never a tool left low at the new place)
            double z = "camera".equals(tool) ? from.getZ() : safeLow(hm);
            Location to = new Location(LengthUnit.Millimeters, tx, ty, z, from.getRotation());
            MovableUtils.moveToLocationAtSafeZ(hm, to);
            m.getMotionPlanner().waitForCompletion(hm, MotionPlanner.CompletionType.WaitForStillstand);
            return answer(tool, hm, from);
        });
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

    /**
     * X/Y moves only with both nozzles at travel height: nothing is dragged across the bed (#19 review of BR-49); with
     * no lower safe zone on Z there is no travel height, so no X/Y (BR-94 follow-up review, MAJOR 1).
     */
    private void requireTravelHeight() throws ApiException {
        DispenseService svc = ctx.svcOrNull();
        for (HeadMountable n : nozzles(svc)) {
            double z = mm(n.getLocation()).getZ(), low = safeLow(n);
            if (!Double.isFinite(low)) {
                throw ApiException.refused("Z has no lower safe zone in OpenPnP (the Z axis's Safe Zone): X and Y move "
                        + "only at travel height");
            }
            if (z < low - 1e-6) {
                throw ApiException.refused(String.format(Locale.ROOT, "%s is below its safe zone (Z %.2f, the zone "
                        + "starts at %.2f): raise it first; X and Y move only at travel height", name(svc, n), z, low));
            }
        }
    }

    /**
     * Both nozzles where they would be if {@code hm} went to {@code to} (N1 and the needle share one Z axis: a see-saw,
     * lowering one raises the other), each against its own floor at its own X/Y (#19 review of BR-49, BLOCKER 1).
     * The engine is read once and passed down: one that went mid-check (a configuration load restarts it) took the
     * needle for N1, with no floor and no switch check.
     */
    private void requireFloors(Machine m, HeadMountable hm, Location to) throws Exception {
        DispenseService svc = ctx.svcOrNull();
        if (svc == null) {
            throw ApiException.refused("the dispense engine is not running: no Z press without the floors it holds (a "
                    + "configuration load restarts it: try again in a moment; if it failed to start, see the OpenPnP "
                    + "log)");
        }
        // both read once: a press moves both, and one the engine has not found (renamed in OpenPnP) has no floor; one
        // nozzle as both (renamed since the engine found them) is checked twice and the other never
        HeadMountable n1 = svc.getProbeNozzle(), n2 = svc.getDispenserNozzle();
        if (n1 == null || n2 == null || n1 == n2) {
            String which = n1 == null ? "N1 (no nozzle named '" + DispenseService.PROBE_NOZZLE + "' in OpenPnP)"
                    : "the needle (" + (n2 == null ? svc.getUnavailableReason() : "the settings' nozzle '"
                            + svc.getConfig().nozzle + "' is N1, the probe nozzle") + ")";
            throw ApiException.refused("the dispense engine has not found " + which + ": no Z press without the "
                    + "floors of both (N1 and the needle share one Z: a press moves both): name it back in OpenPnP"
                    + (n1 == null ? "" : ", or set 'nozzle' in the settings"));
        }
        AxesLocation target = new AxesLocation(m).put(((AbstractHeadMountable) hm).toRaw(hm.toHeadLocation(to)));
        for (HeadMountable n : new HeadMountable[] {n1, n2}) {
            Location at = mm(n.toHeadMountableLocation(((AbstractHeadMountable) n).toTransformed(target)));
            if (at.getZ() >= mm(n.getLocation()).getZ() - 1e-9) {
                continue; // going up (or not moving): always allowed, also out of a spot below a floor that rose
            }
            double low = safeLow(n);
            // the needle where its tip lands (N2 + its landing and trim), as the measure and I12 aim: over the switch
            // and for its floor (over a surface's edge N2 and the tip lie over different surfaces)
            boolean needle = n == n2;
            double[] tip = needle ? svc.needleXY(at.getX(), at.getY()) : new double[] {at.getX(), at.getY()};
            // without a lower safe zone on Z every press down over the switch is refused (fail closed)
            if ((!Double.isFinite(low) || at.getZ() < low - 1e-6) && overHeightSwitch(svc, tip[0], tip[1])) {
                throw ApiException.refused(String.format(Locale.ROOT, "that would put %s at Z %.2f over the height "
                        + "switch (%.1f, %.1f), %s: only its measure presses it (POST /api/height-switch/measure)%s",
                        name(svc, n), at.getZ(), tip[0], tip[1], Double.isFinite(low) ? String.format(Locale.ROOT,
                                "below its safe zone %.2f", low) : "with no lower safe zone on Z",
                        n == hm ? "" : ": N1 and the needle share one Z (raising one lowers the other)"));
            }
            double floor = needle ? needleFloor(svc, n, tip[0], tip[1]) : n1Floor(svc, tip[0], tip[1]);
            double[] where = tip;
            if (needle) { // N2 and its tip can lie over different surfaces: the higher floor holds (NaN: no floor there)
                double underN2 = needleFloor(svc, n, at.getX(), at.getY());
                if (Double.isNaN(floor) || underN2 > floor) {
                    floor = underN2;
                    where = new double[] {at.getX(), at.getY()};
                }
            }
            if (Double.isFinite(floor) && at.getZ() < floor - 1e-9) {
                String why = needle ? needleFloorWhy(svc)
                        : plain(N1_BELOW_SURFACE_MM) + " mm below the probed surface under it";
                throw ApiException.refused(String.format(Locale.ROOT, "that would put %s at Z %.2f over (%.1f, %.1f), "
                        + "below its floor %.2f (%s)%s", name(svc, n), at.getZ(), where[0], where[1], floor, why,
                        n == hm ? "" : ": N1 and the needle share one Z (raising one lowers the other)"));
            }
        }
    }

    /** BR-94: (x, y) lies in the height switch's circle (its place, once set). */
    private static boolean overHeightSwitch(DispenseService svc, double x, double y) {
        DispenseConfig c = svc.getConfig();
        DispenseConfig.NamedLocation p = c.location(HeightSwitch.PLACE);
        return p != null && Math.hypot(x - p.x, y - p.y) <= c.outputZones.heightSwitchRadiusMm;
    }

    /** N1 at most 0.5 mm below a probed surface under it (it may touch: the by-hand probe); NaN: none known there. */
    private static double n1Floor(DispenseService svc, double x, double y) {
        double s = surfaceUnder(svc, x, y);
        return Double.isFinite(s) ? s - N1_BELOW_SURFACE_MM : Double.NaN;
    }

    /**
     * The needle with its tip landing at (x, y): with its frame current (anchored and touched off since the last needle
     * change) and where its tip lands checked, not below the surface there (needle contact = surface + offset) nor its
     * I4 floor; without, not below its safe zone, anywhere (over the switch too: the old tip's landing, or 0, 0 after
     * N2's head offset changed, puts (x, y) up to 15 mm off for a bent tip).
     */
    private static double needleFloor(DispenseService svc, HeadMountable needle, double x, double y)
            throws ApiException {
        if (!needleFrameCurrent(svc) || !svc.isNeedleXyChecked()) {
            double low = safeLow(needle);
            if (Double.isNaN(low)) { // no lower safe zone on Z: no floor at all
                throw ApiException.refused("the needle has no floor, so no press lowers it (raising N1 lowers it too: "
                        + "they share one Z): Z has no lower safe zone in OpenPnP (the Z axis's Safe Zone), and its "
                        + "floor is " + needleFloorWhy(svc));
            }
            return low;
        }
        DispenseConfig c = svc.getConfig();
        double floor = c.needleFloorZMm();
        double s = surfaceUnder(svc, x, y);
        if (Double.isFinite(s)) {
            floor = Double.isFinite(floor) ? Math.max(floor, s + c.needleZOffsetMm) : s + c.needleZOffsetMm;
        }
        return floor;
    }

    private static String needleFloorWhy(DispenseService svc) {
        if (!needleFrameCurrent(svc)) {
            return "its safe zone: its Z frame is not set for this needle, or this home's Z check failed";
        }
        String xy = svc.needleXyProblem(); // the engine's sentence: here inside brackets, so without its period
        return xy == null ? "the surface under its tip or under N2, or its I4 floor"
                : "its safe zone: " + xy.replaceFirst("\\.$", "");
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
    private static double surfaceUnder(DispenseService svc, double x, double y) {
        DispenseConfig c = svc.getConfig();
        if (c.surfaces == null) {
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

    /**
     * N1 and the needle (the engine's), else every nozzle of the head (the engine starting or failed, or one of the
     * two not found, or one nozzle as both: renamed in OpenPnP).
     */
    private List<HeadMountable> nozzles(DispenseService svc) throws ApiException {
        List<HeadMountable> l = new ArrayList<>();
        HeadMountable n1 = svc == null ? null : svc.getProbeNozzle();
        HeadMountable n2 = svc == null ? null : svc.getDispenserNozzle();
        if (n1 != null && n2 != null && n1 != n2) {
            l.add(n1);
            l.add(n2);
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

    private static String name(DispenseService svc, HeadMountable n) {
        return svc != null && n == svc.getDispenserNozzle() ? "the needle" : n.getName();
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
            hm = ctx.svc().getProbeNozzle(); // the engine finds N1 and the needle (503 while it is not running)
        }
        else if ("needle".equals(tool)) {
            hm = ctx.availableSvc().getDispenserNozzle();
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
