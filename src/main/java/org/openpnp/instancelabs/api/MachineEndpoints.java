package org.openpnp.instancelabs.api;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.imageio.ImageIO;

import org.openpnp.instancelabs.dispense.ControllerCommands;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.HomeReport;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Axis;
import org.openpnp.spi.Camera;
import org.openpnp.spi.ControllerAxis;
import org.openpnp.spi.CoordinateAxis;
import org.openpnp.spi.Driver;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.pmw.tinylog.Logger;

/** STOP, start, home, machine state and the top-camera snapshot. */
final class MachineEndpoints {
    /**
     * How long STOP lets the engine send its own I1 lines before OpenPnP disables the driver, when
     * the driver's DISABLE_COMMAND does not vent by itself.
     */
    static final long ENGINE_STOP_GRACE_MS = 1000;
    /**
     * STOP answers within this time, waits included: the page shows "STOP NOT CONFIRMED: ... switch the machine off"
     * when no answer comes in 2 s. A disable queued behind a dwell (valve settle, up to 3 s) goes on after the answer
     * ({@code machineEnabled} true then); the status shows when the machine is off.
     */
    static final long STOP_ANSWER_MS = 1500;

    private final ApiContext ctx;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "il-api-camera");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean noJpegLogged;

    MachineEndpoints(ApiContext ctx) {
        this.ctx = ctx;
    }

    void register(ApiServer s) {
        s.route("POST", "/api/stop", r -> stop());
        s.route("POST", "/api/machine/enable", r -> enable());
        s.route("POST", "/api/machine/home", this::home);
        s.route("GET", "/api/machine", r -> machineStatus());
        s.route("GET", "/api/camera/top\\.jpg", this::snapshot);
    }

    // ------------------------------------------------------------------------------------------
    // STOP: always allowed, no interlock can refuse it.
    // ------------------------------------------------------------------------------------------

    Object stop() {
        long answerBy = System.currentTimeMillis() + STOP_ANSWER_MS;
        List<String> actions = new ArrayList<>();
        String reason = "STOP pressed in the web UI";
        DispenseService svc = ctx.svcOrNull();
        boolean engineWasBusy = svc != null && svc.isBusy();
        // a pad calibration first: it queues no further engine step once its stop flag is set (§7.8)
        ctx.padCalJobs.stop(reason);
        if (svc != null) {
            svc.abort(reason);
            actions.add(engineWasBusy
                    ? "dispense engine: abort requested (it sends " + String.join(", ", ControllerCommands.STOP_SEQUENCE)
                            + " before its next line)"
                    : "dispense engine: idle");
        }
        if (ctx.pressure.isActive()) {
            ctx.pressure.emergencyStop(reason);
            actions.add("pressure hold: valve off, then pump off");
        }
        Machine m = ctx.configuration.getMachine();
        if (m == null) {
            actions.add("no machine configured");
            return Views.map("stopped", true, "actions", actions);
        }
        if (engineWasBusy) {
            Map<String, Object> check = stopCommandCheck(svc.getConfig());
            if (check != null && Boolean.TRUE.equals(check.get("ok"))) {
                // OpenPnP's Stop vents too (DISABLE_COMMAND): do not delay it.
                actions.add("not waiting for the engine: the driver's DISABLE_COMMAND also quick-stops and vents");
            }
            else {
                // OpenPnP's Stop would not vent and may close the link: let the engine's own
                // shutdown lines go out first (bounded).
                long until = Math.min(System.currentTimeMillis() + ENGINE_STOP_GRACE_MS, answerBy);
                while (svc.isBusy() && System.currentTimeMillis() < until) {
                    sleep(20);
                }
                actions.add(svc.isBusy() ? "engine still finishing its abort after " + ENGINE_STOP_GRACE_MS
                        + " ms; stopping OpenPnP anyway" : "engine stopped");
            }
        }
        if (m.isEnabled()) {
            Runnable task = () -> {
                try {
                    m.setEnabled(false);
                }
                catch (Exception e) {
                    Logger.warn(e, "web STOP: disabling the machine failed");
                }
            };
            if (m.isBusy()) {
                // As OpenPnP's own Stop button: bypass the busy machine thread (emergency path).
                Thread t = new Thread(task, "il-api-stop");
                t.setDaemon(true);
                t.start();
                actions.add("OpenPnP Stop (emergency path: the machine thread was busy); sends the driver's DISABLE_COMMAND");
            }
            else {
                m.submit(() -> {
                    task.run();
                    return null;
                }, null, true);
                actions.add("OpenPnP Stop; sends the driver's DISABLE_COMMAND");
            }
            while (m.isEnabled() && System.currentTimeMillis() < answerBy) {
                sleep(20);
            }
            if (m.isEnabled()) {
                actions.add("OpenPnP is still switching the machine off (a dwell or move must end first)");
            }
        }
        else {
            actions.add("machine was already stopped");
        }
        return Views.map("stopped", true, "machineEnabled", m.isEnabled(), "actions", actions);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Start (connect) and home
    // ------------------------------------------------------------------------------------------

    Object enable() throws Exception {
        Machine m = ctx.machine();
        if (m.isEnabled()) {
            return Views.map("enabled", true, "homed", m.isHomed(), "note", "the machine was already started");
        }
        Future<Object> f = m.submit(() -> {
            m.setEnabled(true);
            return null;
        }, null, true);
        try {
            f.get(90, TimeUnit.SECONDS);
        }
        catch (ExecutionException e) {
            throw ApiException.refused("could not start the machine: " + rootMessage(e));
        }
        return Views.map("enabled", m.isEnabled(), "homed", m.isHomed());
    }

    Object home(ApiServer.Request r) throws Exception {
        Machine m = ctx.machine();
        if (!m.isEnabled()) {
            throw ApiException.refused("the machine is not started: press Start first");
        }
        DispenseService svc = ctx.svcOrNull();
        if (svc != null) {
            ctx.requireEngineIdle(svc);
        }
        ctx.requireNoPressureHold();
        boolean wait = Json.bool(r.json(), "wait", false);
        // With the engine: N1 raised first when a STOP left it low, and a STOP during the home stops the
        // head for good (the drivers are disconnected), as the engine's own homes (§2.3).
        boolean safe = svc != null && svc.isAvailable();
        Object held = new Object();
        Future<Object> f = m.submit(() -> {
            // Again on the machine thread, where the hold's start is serialised with this home (§2.7): a
            // hold queued between the check above and this task must not see the head home under
            // pressure. Returned, not thrown: OpenPnP drops every task queued behind one that throws.
            if (ctx.pressure.isActive()) {
                Logger.info("web home not started: the pressure hold came on before it");
                return held;
            }
            if (safe) {
                return svc.homeSafely();
            }
            m.home();
            return null;
        });
        if (!wait) {
            return ApiServer.Response.json(202, Views.map("started", true, "homing", true));
        }
        Object rep;
        try {
            rep = f.get(5, TimeUnit.MINUTES);
        }
        catch (ExecutionException e) {
            throw ApiException.refused("homing failed: " + rootMessage(e));
        }
        if (rep == held) {
            throw ApiException.refused("the pressure hold came on before the home started: switch the pressure off "
                    + "first, then home");
        }
        return Views.map("homed", m.isHomed(), "n1RaisedFromZ", rep instanceof HomeReport
                ? ((HomeReport) rep).n1RaisedFromZ : null);
    }

    static String rootMessage(Throwable t) {
        while ((t instanceof ExecutionException || t.getMessage() == null) && t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    // ------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------

    Map<String, Object> machineStatus() {
        Map<String, Object> s = new LinkedHashMap<>();
        Machine m = ctx.configuration.getMachine();
        s.put("present", m != null);
        if (m == null) {
            return s;
        }
        s.put("enabled", m.isEnabled());
        // OpenPnP opens the controller link when the machine is started (enabled).
        s.put("connected", m.isEnabled());
        s.put("homed", m.isHomed());
        s.put("busy", m.isBusy());
        DispenseService svc = ctx.svcOrNull();
        if (svc != null) {
            Map<String, Object> home = Views.home(svc.homePlan());
            double[] n1 = svc.probeNozzleZ();
            home.put("n1Z", n1 == null ? null : n1[0]);
            home.put("n1Low", n1 != null && n1[1] > 0);
            s.put("home", home);
            DispenseService.ZCheckPlan zc = svc.zCheckPlan(null, false);
            List<Map<String, Object>> stale = new ArrayList<>();
            for (org.openpnp.instancelabs.dispense.ZCheck.Stale x : zc.stale) {
                stale.add(Views.map("what", x.what, "atMs", x.atMs));
            }
            s.put("zCheck", Views.map("needed", zc.needed, "stale", stale, "homeAtMs", zc.homeAtMs, "reference",
                    zc.reference == null ? null : Views.map("name", zc.reference.name, "x", zc.reference.x, "y",
                            zc.reference.y, "z", zc.reference.z), "last", zc.last, "estimateS",
                    DispenseService.PROBE_POINT_ESTIMATE_S));
        }
        List<Map<String, Object>> drivers = new ArrayList<>();
        for (Driver d : m.getDrivers()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("name", d.getName());
            dm.put("class", d.getClass().getSimpleName());
            if (d instanceof GcodeDriver) {
                GcodeDriver g = (GcodeDriver) d;
                try {
                    dm.put("connection", g.getCommunications().getConnectionName());
                }
                catch (Exception e) {
                    dm.put("connection", null);
                }
            }
            drivers.add(dm);
        }
        s.put("drivers", drivers);
        List<Map<String, Object>> tools = new ArrayList<>();
        Head head = defaultHead(m);
        if (head != null) {
            for (Nozzle n : head.getNozzles()) {
                tools.add(tool("nozzle", n.getName(), n.getId(), n.getLocation()));
            }
            for (Camera c : head.getCameras()) {
                tools.add(tool("camera", c.getName(), c.getId(), c.getLocation()));
            }
        }
        s.put("positions", tools);
        List<Map<String, Object>> axes = new ArrayList<>();
        for (Axis a : m.getAxes()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("name", a.getName());
            am.put("type", String.valueOf(a.getType()));
            if (a instanceof ControllerAxis) {
                am.put("letter", ((ControllerAxis) a).getLetter());
                am.put("driverCoordinate", ((ControllerAxis) a).getDriverCoordinate());
            }
            if (a instanceof CoordinateAxis) {
                am.put("coordinate", ((CoordinateAxis) a).getCoordinate());
            }
            axes.add(am);
        }
        s.put("axes", axes);
        return s;
    }

    private static Head defaultHead(Machine m) {
        try {
            return m.getDefaultHead();
        }
        catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> tool(String kind, String name, String id, Location l) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("kind", kind);
        t.put("name", name);
        t.put("id", id);
        if (l != null) {
            l = l.convertToUnits(LengthUnit.Millimeters);
            t.put("x", l.getX());
            t.put("y", l.getY());
            t.put("z", l.getZ());
            t.put("rotation", l.getRotation());
        }
        return t;
    }

    /**
     * I1 outside dispense runs depends on the driver's DISABLE_COMMAND (plan §4.4): reports whether
     * OpenPnP's own Stop sends the Stop sequence (M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B) in
     * this order, comments removed (extra lines around it are allowed).
     */
    Map<String, Object> stopCommandCheck(DispenseConfig cfg) {
        Machine m = ctx.configuration.getMachine();
        if (m == null || cfg == null) {
            return null;
        }
        for (Driver d : m.getDrivers()) {
            if (!(d instanceof GcodeDriver)) {
                continue;
            }
            String dis = ((GcodeDriver) d).getCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND);
            List<String> lines = new ArrayList<>();
            List<String> norm = new ArrayList<>();
            if (dis != null) {
                for (String l : dis.split("\n")) {
                    if (!l.trim().isEmpty()) {
                        lines.add(l.trim());
                        String c = l.contains(";") ? l.substring(0, l.indexOf(';')) : l;
                        if (!c.trim().isEmpty()) {
                            norm.add(c.trim().replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT));
                        }
                    }
                }
            }
            boolean valve = norm.contains(ControllerCommands.VALVE_OFF);
            boolean pump = norm.contains(ControllerCommands.PUMP_OFF);
            boolean quick = norm.contains("M410");
            boolean sequence = java.util.Collections.indexOfSubList(norm, ControllerCommands.STOP_SEQUENCE) >= 0;
            return Views.map("driver", d.getName(), "disableCommand", lines, "valveOff", valve, "pumpOff", pump,
                    "quickStop", quick, "stopSequence", sequence, "expected", ControllerCommands.STOP_SEQUENCE, "ok",
                    sequence);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Top camera snapshot (no motion, no lights, no scripts)
    // ------------------------------------------------------------------------------------------

    Object snapshot(ApiServer.Request r) throws Exception {
        Machine m = ctx.machine();
        Head head = defaultHead(m);
        Camera cam = null;
        try {
            cam = head == null ? null : head.getDefaultCamera();
        }
        catch (Exception e) {
            // OpenPnP throws when the head has no camera
        }
        if (cam == null) {
            throw ApiException.notFound("no top (head) camera is configured");
        }
        int maxW = r.queryInt("w", 0, 0, 4096);
        BufferedImage img;
        try {
            img = cameraExecutor.submit(cam::captureTransformed).get(10, TimeUnit.SECONDS);
        }
        catch (TimeoutException e) {
            throw ApiException.unavailable("the camera did not deliver a frame within 10 s");
        }
        catch (ExecutionException e) {
            throw ApiException.unavailable("camera capture failed: " + rootMessage(e));
        }
        if (img == null) {
            throw ApiException.unavailable("the camera returned no image");
        }
        int w = img.getWidth(), h = img.getHeight();
        if (maxW > 0 && w > maxW) {
            h = Math.max(1, Math.round(h * (maxW / (float) w)));
            w = maxW;
        }
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return encodeFrame(rgb);
    }

    /** JPEG, else PNG: never an empty 200. */
    ApiServer.Response encodeFrame(BufferedImage rgb) throws IOException, ApiException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (ImageIO.write(rgb, "jpg", out) && out.size() > 0) {
            return ApiServer.Response.bytes(200, "image/jpeg", out.toByteArray());
        }
        // ImageIO answers false (no exception) when it has no writer for the image. Seen in real mode on the bench
        // ThinkPad (2026-10-28): the JVM's writers were tif, bmp, gif, wbmp, png, no JPEG. PNG is always there.
        if (!noJpegLogged) {
            noJpegLogged = true;
            Logger.warn("Top camera snapshot: no JPEG writer in this JVM (writers: {}); sending PNG",
                    String.join(",", ImageIO.getWriterFormatNames()));
        }
        out.reset();
        if (!ImageIO.write(rgb, "png", out) || out.size() == 0) {
            throw ApiException.unavailable("the camera frame could not be encoded (no JPEG or PNG writer)");
        }
        return ApiServer.Response.bytes(200, "image/png", out.toByteArray());
    }

    void shutdown() {
        cameraExecutor.shutdownNow();
    }
}
