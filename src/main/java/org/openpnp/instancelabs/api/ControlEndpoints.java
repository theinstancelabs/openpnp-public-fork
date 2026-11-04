package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.SurfacePlane;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MotionPlanner;
import org.openpnp.util.MovableUtils;
import org.pmw.tinylog.Logger;

import com.google.gson.JsonObject;

/**
 * BR-49 + BR-53 (P0 after BR-72, docs/backend-requests.md "BR-49 + BR-53 API"): the user's Machine › Control. Jog
 * and move-to through OpenPnP's own motion on its machine thread (guarded writes: BR-72's lock), and the top and
 * bottom cameras read through OpenPnP's own capture (GETs, never locked).
 *
 * <p>Rules as the engine's moves: refused while the machine is off, busy (a task, a G-code batch, a pressure hold,
 * OpenPnP moving) or switching off; before a home only a raise; inside the soft limits (OpenPnP's own check); one press
 * at most {@value #MAX_XY_MM} mm in X/Y and {@value #MAX_Z_MM} mm in Z. Z floors where the engine knows the surface:
 * N1 not more than {@value #N1_BELOW_SURFACE_MM} mm below a probed surface under it (it may touch: the by-hand probe),
 * the needle not below its I4 floor ({@link DispenseConfig#needleFloorZMm()}); elsewhere the person lowers press by
 * press, watching. Move-to travels at safe Z and ends there: it never lowers. STOP ends either at once.</p>
 */
final class ControlEndpoints {
    static final double MAX_XY_MM = 10;
    static final double MAX_Z_MM = 2;
    static final double N1_BELOW_SURFACE_MM = 0.5;
    /** A surface counts under a tool within this margin of its probed points' extent. */
    static final double SURFACE_MARGIN_MM = 2;
    static final long MAX_SETTLE_MS = 2000;
    private static final long MOVE_TIMEOUT_S = 120;

    private final ApiContext ctx;
    private final MachineEndpoints machine;

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
        if ("camera".equals(tool) && dz != 0) {
            throw ApiException.refused("the camera has no Z of its own: jog N1 or the needle up or down");
        }
        Machine m = ctx.machine();
        HeadMountable hm = tool(tool);
        requireFree(m);
        if (!m.isHomed() && (dx != 0 || dy != 0 || dz < 0)) {
            throw ApiException.refused("the machine is not homed: Home first (only a raise works now)");
        }
        Location from = hm.getLocation().convertToUnits(LengthUnit.Millimeters);
        Location to = from.add(new Location(LengthUnit.Millimeters, dx, dy, dz, 0));
        if (dz < 0) {
            double floor = floor(tool, to.getX(), to.getY());
            if (Double.isFinite(floor) && to.getZ() < floor - 1e-9) {
                throw ApiException.refused(String.format(Locale.ROOT, "%s would go to Z %.2f, below its floor %.2f "
                        + "(%s): raise it, or jog in smaller steps", label(tool), to.getZ(), floor, floorWhy(tool)));
            }
        }
        move(m, hm, to, false);
        return answer(tool, hm, from);
    }

    Object moveTo(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        String tool = Json.str(b, "tool", null);
        String place = Json.str(b, "place", null);
        Double x = Json.num(b, "x", null), y = Json.num(b, "y", null);
        Machine m = ctx.machine();
        HeadMountable hm = tool(tool);
        if (place != null) {
            DispenseService svc = ctx.svc();
            DispenseConfig.NamedLocation p = svc.getConfig().location(place);
            if (p == null) {
                throw ApiException.notFound("no place named '" + place + "' (GET /api/locations)");
            }
            x = p.x;
            y = p.y;
        }
        if (x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y)) {
            throw ApiException.badRequest("a place, or x and y");
        }
        requireFree(m);
        if (!m.isHomed()) {
            throw ApiException.refused("the machine is not homed: Home first");
        }
        Location from = hm.getLocation().convertToUnits(LengthUnit.Millimeters);
        // it ends at safe Z over the target: it never lowers (lowering is jog's, a press at a time)
        double safeZ = hm.getSafeZ().convertToUnits(LengthUnit.Millimeters).getValue();
        Location to = new Location(LengthUnit.Millimeters, x, y, Math.max(safeZ, from.getZ()), from.getRotation());
        move(m, hm, to, true);
        return answer(tool, hm, from);
    }

    /** The checks every motion route makes before it queues anything. */
    private void requireFree(Machine m) throws ApiException {
        if (!m.isEnabled()) {
            throw ApiException.refused("the machine is not started: press Start first");
        }
        ctx.requireNotSwitchingOff();
        DispenseService svc = ctx.svcOrNull();
        if (svc != null) {
            ctx.requireEngineIdle(svc);
        }
        if (ctx.passthrough.running()) {
            throw ApiException.refused("a G-code batch is running: wait for it or press STOP");
        }
        if (ctx.pressure.isActive()) {
            throw ApiException.refused("the pressure is on: Pressure off first (nothing moves under pressure here)");
        }
        ctx.requireMachineIdle();
    }

    /** On OpenPnP's machine thread, waiting for the head to stop; STOP (a disable) ends it with a refusal. */
    private void move(Machine m, HeadMountable hm, Location to, boolean atSafeZ) throws Exception {
        long stops = ctx.stops.get();
        Future<Object> f = m.submit(() -> {
            try {
                if (atSafeZ) {
                    MovableUtils.moveToLocationAtSafeZ(hm, to);
                }
                else {
                    hm.moveTo(to);
                }
                m.getMotionPlanner().waitForCompletion(hm, MotionPlanner.CompletionType.WaitForStillstand);
                return null;
            }
            catch (Exception e) {
                return e; // returned, not thrown: OpenPnP drops every task queued behind one that throws
            }
        }, null, true);
        Object out;
        try {
            out = f.get(MOVE_TIMEOUT_S, TimeUnit.SECONDS);
        }
        catch (ExecutionException e) {
            out = e.getCause();
        }
        if (out instanceof Exception) {
            String why = MachineEndpoints.rootMessage((Exception) out);
            Logger.info("web {}: refused or stopped: {}", atSafeZ ? "move-to" : "jog", why);
            throw ApiException.refused((m.isEnabled() ? "the move was refused: " : "the move was stopped: ") + why);
        }
        // a STOP during the move: its last ok may be the STOP's flush (M410), not the head at its target
        if (ctx.stops.get() != stops || !m.isEnabled() || ctx.switchOff.inProgress()) {
            throw ApiException.refused("the move was stopped (STOP): the head may be short of its target; Start, then "
                    + "Home");
        }
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

    /** The lowest Z the tool may go to at (x, y) (NaN: no floor known there; the soft limits still hold). */
    private double floor(String tool, double x, double y) {
        DispenseService svc = ctx.svcOrNull();
        if (svc == null) {
            return Double.NaN;
        }
        DispenseConfig c = svc.getConfig();
        if ("needle".equals(tool)) {
            return c.needleFloorZMm();
        }
        if (!"n1".equals(tool) || c.surfaces == null) {
            return Double.NaN;
        }
        double floor = Double.NaN;
        for (DispenseConfig.SurfaceMap sm : c.surfaces.values()) {
            if (sm == null || sm.points == null || sm.points.size() < 3 || !covers(sm.points, x, y)) {
                continue;
            }
            try {
                double z = SurfacePlane.fit(sm.points).z(x, y) - N1_BELOW_SURFACE_MM;
                floor = Double.isNaN(floor) ? z : Math.max(floor, z);
            }
            catch (RuntimeException e) {
                // a degenerate surface (collinear points) gives no floor
            }
        }
        return floor;
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

    private static String floorWhy(String tool) {
        return "needle".equals(tool) ? "the needle's frame floor, I4" : plain(N1_BELOW_SURFACE_MM)
                + " mm below the probed surface under it";
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
