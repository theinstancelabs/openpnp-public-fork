package org.openpnp.instancelabs.api;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;

import org.openpnp.instancelabs.dispense.ControllerCommands;
import org.openpnp.instancelabs.dispense.ControllerReset;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.HomeReport;
import org.openpnp.machine.reference.ReferenceMachine;
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

import com.google.gson.JsonElement;

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
    /**
     * Longest Start waits for a switch-off still running (BR-52): one DISABLE_COMMAND line waiting the driver's timeout
     * for a lost ok (10 s on the machines so far; the lines after it wait at most 1 s each, BR-66) and a closing link's
     * reader join (3 s), with room to spare.
     */
    static final long START_WAITS_FOR_SWITCH_OFF_MS = 20000;
    /** BR-96: a reset home's evidence (a run id, picture ids, a model's proof) as JSON text, at most this long. */
    static final int MAX_EVIDENCE_BYTES = 4096;
    static final int MAX_ATTESTED_BY = 200;
    /** BR-75: how long the planned stop waits for its machine task to start and end; tests shorten it. */
    static volatile long plannedStopWaitMs = START_WAITS_FOR_SWITCH_OFF_MS;
    /** Tests only (BR-75 review): runs between the planned stop's checks and its task's submit (the task checks again). */
    static volatile Runnable plannedStopHook;

    private final ApiContext ctx;
    /** One capture thread per camera: a hung camera never holds up the other (BR-53 review). */
    private final Map<Camera, ExecutorService> cameraExecutors = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean noJpegLogged;

    MachineEndpoints(ApiContext ctx) {
        this.ctx = ctx;
    }

    void register(ApiServer s) {
        s.route("POST", "/api/stop", r -> stop());
        s.route("POST", LockEndpoints.PLANNED_STOP, r -> plannedStop());
        s.route("POST", "/api/machine/enable", r -> enable());
        s.route("POST", "/api/machine/home", this::home);
        s.route("GET", "/api/machine", r -> machineStatus());
        s.route("GET", "/api/camera/top\\.jpg", this::snapshot);
    }

    // ------------------------------------------------------------------------------------------
    // STOP: always allowed, no interlock can refuse it.
    // ------------------------------------------------------------------------------------------

    Object stop() {
        return stop("STOP pressed in the web UI");
    }

    /** The web STOP, also a take-over's from a working lock holder (BR-72). */
    Map<String, Object> stop(String reason) {
        ctx.stops.incrementAndGet();
        long answerBy = System.currentTimeMillis() + STOP_ANSWER_MS;
        List<String> actions = new ArrayList<>();
        DispenseService svc = ctx.svcOrNull();
        boolean engineWasBusy = svc != null && svc.isBusy();
        // BR-72: a G-code batch of the lock holder sends nothing after its line in flight
        if (ctx.passthrough.abort(reason)) {
            actions.add("G-code batch: stopped before its next line");
        }
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
            Runnable task = () -> ctx.switchOff.stop(m);
            String sends = sendsDisableCommand(svc) ? "sends the driver's DISABLE_COMMAND" : SCREW_LINK_CLOSED;
            if (m.isBusy()) {
                // As OpenPnP's own Stop button: bypass the busy machine thread (emergency path).
                Thread t = new Thread(task, "il-api-stop");
                t.setDaemon(true);
                t.start();
                actions.add("OpenPnP Stop (emergency path: the machine thread was busy); " + sends);
            }
            else {
                m.submit(() -> {
                    task.run();
                    return null;
                }, null, true);
                actions.add("OpenPnP Stop; " + sends);
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
            if (!sendsDisableCommand(svc)) {
                actions.add(SCREW_LINK_CLOSED); // BR-75: after a planned stop the screw's motor stays powered
            }
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

    /** What a STOP's answer says when the screw's link is closed, as a planned stop leaves it (BR-75 review). */
    static final String SCREW_LINK_CLOSED = "the screw's controller link is closed (as a planned stop leaves it): no "
            + "DISABLE_COMMAND goes out, and nothing can switch the screw's motor off until the next Start";

    /**
     * BR-75 review: whether OpenPnP's Stop sends the DISABLE_COMMAND to the screw's controller: not once a planned stop
     * closed its link. For the answer's words only: it never throws (unknown counts as sent), so it cannot keep a STOP
     * from switching off (the PM, re-review); a few field reads, no lock.
     */
    static boolean sendsDisableCommand(DispenseService svc) {
        try {
            return svc == null || svc.screwDriverConnected();
        }
        catch (RuntimeException e) {
            Logger.warn(e, "STOP: whether the screw's controller link is open is not known");
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------
    // The planned stop (BR-75): the deployer's, never the STOP button's
    // ------------------------------------------------------------------------------------------

    /**
     * BR-75: as STOP the outputs go off and the machine is disabled, but without the screw's M18 B, so its motor stays
     * powered and after the next Start its start angle needs a confirm, not a measure. Refused unless nothing works.
     * One OpenPnP machine task, in STOP's order: OpenPnP's disable listeners on the open link (outputs off), the
     * DISABLE_COMMAND's other lines, then the link closes. Afterwards nothing can switch the screw's motor off until the
     * next Start (no link).
     */
    Object plannedStop() throws Exception {
        Machine m = ctx.configuration.getMachine();
        DispenseService svc = ctx.svcOrNull();
        if (m == null || svc == null) {
            throw ApiException.refused("No planned stop: " + (m == null ? "OpenPnP has no machine configured"
                    : "the dispense engine is not running") + ". The real STOP is POST /api/stop");
        }
        String working = working(svc, m);
        if (working != null) {
            throw refusedWhile(working);
        }
        Runnable hook = plannedStopHook;
        if (hook != null) {
            hook.run();
        }
        List<String> actions = new ArrayList<>();
        List<String> sent = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        // claimed by the task when it begins, or by the timeout first: then it does nothing (as ControlEndpoints.motion)
        AtomicBoolean claimed = new AtomicBoolean();
        Future<Object> f = m.submit(() -> {
            if (!claimed.compareAndSet(false, true)) {
                return null; // the request timed out before this began: nothing is done
            }
            // again on the machine thread; returned, not thrown: OpenPnP drops every task queued behind one that throws
            String now = working(svc, m);
            if (now != null) {
                return now;
            }
            try {
                GcodeDriver.PlannedStop p = svc.plannedStop(m, () -> ctx.switchOff.stop(m));
                if (p == null) {
                    actions.add("machine was already stopped");
                    return null;
                }
                if (m.isEnabled() || svc.screwDriverConnected()) {
                    // a disable listener that threw ends OpenPnP's Stop before its drivers (SwitchOff logs it): a 200
                    // would let the deployer quit OpenPnP, and the quit's disable sends the whole DISABLE_COMMAND
                    return new IllegalStateException("OpenPnP's Stop did not switch the machine off (" + (m.isEnabled()
                            ? "it still reads started" : "the screw's controller link is still open")
                            + "; see the OpenPnP log)");
                }
                sent.addAll(p.acknowledged);
                failed.addAll(p.failed);
                actions.add("planned stop: OpenPnP Stop, its DISABLE_COMMAND without the screw-off line: "
                        + (sent.isEmpty() ? "no line acknowledged" : String.join(", ", sent))
                        + (failed.isEmpty() ? "" : " (no ok for " + String.join(", ", failed) + ")")
                        + "; the link closed, the screw's motor stays powered");
                return null;
            }
            catch (Exception e) {
                Logger.warn(e, "web planned stop failed");
                return e;
            }
        }, null, true);
        long waitS = plannedStopWaitMs / 1000;
        Object out;
        try {
            out = f.get(plannedStopWaitMs, TimeUnit.MILLISECONDS); // as long as a switch-off may take
        }
        catch (InterruptedException e) {
            claimed.compareAndSet(false, true); // not started yet: it never runs (nobody hears its answer)
            throw e;
        }
        catch (TimeoutException e) {
            // no f.cancel: a task a timeout claimed does nothing when it runs (a cancelled one keeps OpenPnP busy)
            throw ApiException.refused(claimed.compareAndSet(false, true) ? "the planned stop did not start within "
                    + waitS + " s (OpenPnP was busy): it will not run. The real STOP is POST /api/stop"
                    : "the planned stop has not ended after " + waitS + " s: the machine may still be switching off. "
                            + "The real STOP is POST /api/stop");
        }
        if (out instanceof String) {
            throw refusedWhile((String) out);
        }
        if (out instanceof Exception) {
            throw ApiException.refused("No planned stop: " + rootMessage((Exception) out) + ". The real STOP is "
                    + "POST /api/stop");
        }
        return Views.map("stopped", true, "machineEnabled", m.isEnabled(), "actions", actions, "planned", true,
                "linesSent", sent, "linesFailed", failed);
    }

    /** BR-75: what works now, in words, or null. On the machine thread OpenPnP reads busy for the planned stop itself. */
    private String working(DispenseService svc, Machine m) {
        if (ctx.switchOff.inProgress()) {
            return "OpenPnP is switching the machine off (a STOP)";
        }
        // a task OpenPnP dropped (one of its own ahead of it failed) never ends by itself: it ends here (rc1 review)
        if (svc.isBusy() && !svc.releaseDroppedTask()) {
            return "a dispense task runs";
        }
        if (ctx.padCalJobs.lockReason() != null) {
            return "a pad calibration runs";
        }
        if (ctx.pressure.isActive()) {
            return "the pressure hold is on";
        }
        if (ctx.passthrough.running() || ctx.passthrough.motionPending()) {
            return "a G-code batch runs, or its moves have not ended";
        }
        if (m instanceof ReferenceMachine && ((ReferenceMachine) m).isHoming()
                || !m.isTask(Thread.currentThread()) && m.isBusy()) {
            return "OpenPnP is busy (a home, a jog or a move)";
        }
        // OpenPnP's tasks end without waiting for their moves: its window's jog of the screw (N2's rotation) may run on.
        // Only with the machine started (off, only a Start clears the flag); the task's read on the machine thread is
        // the reliable one (a plain field)
        GcodeDriver d = svc.screwDriver(m);
        if (m.isEnabled() && d != null && d.isMotionPending()) {
            return "OpenPnP's last move was not waited for (a jog in its window): the screw may still turn; wait for it, "
                    + "then Home";
        }
        return null;
    }

    private static ApiException refusedWhile(String working) {
        return ApiException.refused("No planned stop: " + working + ". It runs only while nothing works: wait for "
                + "it to end, or stop it now with the real STOP (POST /api/stop), which also switches the screw's "
                + "motor off");
    }

    // ------------------------------------------------------------------------------------------
    // Start (connect) and home
    // ------------------------------------------------------------------------------------------

    Object enable() throws Exception {
        Machine m = ctx.machine();
        refuseWhileEngineStarts("Start");
        // BR-52: a machine switching off still reads started; start it again once the switch-off has ended
        if (!ctx.switchOff.awaitEnd(START_WAITS_FOR_SWITCH_OFF_MS)) {
            throw ApiException.refused("OpenPnP is still switching the machine off after "
                    + START_WAITS_FOR_SWITCH_OFF_MS / 1000 + " s (it waits for the controller's answers): press Start "
                    + "again in a moment; if it stays, switch the controller off and on");
        }
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

    /**
     * BR-02 (#11 review 11-04): OpenPnP starts the web API and the engine in no fixed order; until the engine runs it
     * is not watching the connect, so a Start or a Home then would skip its reset checks (a home after a reset that
     * moves no Z frame). "failed" is not waited for: the status says why.
     */
    private void refuseWhileEngineStarts(String what) throws ApiException {
        if (ctx.svcOrNull() == null && "starting".equals(DispenseService.startState())) {
            throw ApiException.refused("the dispense engine is still starting (it reads the controller at Start): "
                    + "press " + what + " again in a few seconds");
        }
    }

    Object home(ApiServer.Request r) throws Exception {
        Machine m = ctx.machine();
        refuseWhileEngineStarts("Home");
        if (!m.isEnabled()) {
            throw ApiException.refused("the machine is not started: press Start first");
        }
        DispenseService svc = ctx.svcOrNull();
        if (svc != null) {
            ctx.requireEngineIdle(svc);
        }
        ctx.requireNoPressureHold();
        // OpenPnP homing or jogging by itself: a home queued behind a task of its own that fails is dropped (rc1 review)
        ctx.requireMachineIdle();
        boolean wait = Json.bool(r.json(), "wait", false);
        // BR-02: after a controller reset the home is G28 straight away, only on the word of whoever checked that
        // nothing is under either tool (the engine checks again on the machine thread)
        boolean attested = Json.bool(r.json(), "clearBelowAttested", false);
        // BR-96: who attests it (the lock holder unless named; then the holder too) and their evidence, opaque, kept as
        // its JSON text
        MachineLock.Holder holder = r.lockActivity == null ? null : r.lockActivity.holder;
        String lockName = holder == null ? MachineLock.USER_NAME : holder.name();
        String named = Json.line(r.json(), "attestedBy", lockName);
        if (named.length() > MAX_ATTESTED_BY) {
            throw ApiException.badRequest("'attestedBy' is at most " + MAX_ATTESTED_BY + " characters");
        }
        String by = holder == null || named.equals(lockName) ? named : named + " (lock: " + lockName + ")";
        JsonElement ev = r.json().get("evidence");
        String evidence = ev == null || ev.isJsonNull() ? null : ev.toString();
        int evidenceBytes = evidence == null ? 0 : evidence.getBytes(StandardCharsets.UTF_8).length;
        if (evidenceBytes > MAX_EVIDENCE_BYTES) {
            throw ApiException.badRequest("'evidence' is at most " + MAX_EVIDENCE_BYTES + " bytes of JSON (a run id, "
                    + "picture ids): this one is " + evidenceBytes);
        }
        String unknown = svc == null ? null : svc.positionUnknownWhy();
        if (unknown != null && !attested) {
            throw ApiException.refused("MACHINE: " + unknown + ". " + capitalize(ControllerReset.HOME_CHECK)
                    + "; then home again saying so (clearBelowAttested; attestedBy and evidence say who checked and "
                    + "how): G28 is then the first line, with no Z move before it.");
        }
        ControllerReset reset = svc == null ? null : svc.getControllerReset();
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
            // A failed or stopped home is returned too (and the task never meets OpenPnP's "not started"
            // throw): an engine task queued behind it would be dropped, and its busy flag stay set for good.
            try {
                if (!m.isEnabled()) {
                    throw new Exception("the machine is not started: press Start first");
                }
                if (attested && reset != null) {
                    reset.attest(by, evidence);
                }
                if (safe) {
                    return svc.homeSafely(attested);
                }
                if (reset != null) {
                    reset.homeStarted(); // BR-97, as homeSafely does
                }
                m.home();
                return null;
            }
            catch (Exception e) {
                Logger.warn(e, "web home failed");
                return e;
            }
            finally {
                if (attested && reset != null) {
                    reset.attest(null, null); // a home that ended unhomed leaves no word for a later one
                }
            }
        }, null, true);
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
        catch (TimeoutException e) {
            // OpenPnP dropped the home (a task of its own ahead of it failed), or it never ended: nothing homed
            throw ApiException.refused("the home did not start or did not end within 5 min: nothing homed; press Home "
                    + "again");
        }
        if (rep == held) {
            throw ApiException.refused("the pressure hold came on before the home started: switch the pressure off "
                    + "first, then home");
        }
        if (rep instanceof Exception) {
            throw ApiException.refused("homing failed: " + rootMessage((Exception) rep));
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
        // BR-44: from the start of any home until it ends (the deployer never sends STOP while it is true)
        s.put("homing", m instanceof ReferenceMachine && ((ReferenceMachine) m).isHoming());
        s.put("busy", m.isBusy());
        DispenseService svc = ctx.svcOrNull();
        if (svc != null) {
            // BR-02: a controller reset seen at a connect; nothing moves until a home the operator confirmed
            s.putAll(svc.getControllerReset().view());
            Map<String, Object> home = Views.home(svc.homePlan());
            double[] n1 = svc.probeNozzleZ();
            if (svc.positionUnknownWhy() != null) {
                n1 = null; // the believed Z is the boot's, and the attested home goes straight to G28 (no raise)
            }
            home.put("n1Z", n1 == null ? null : n1[0]);
            home.put("n1Low", n1 != null && n1[1] > 0);
            // which tool is low: N1 under its safe zone, or N1 above it, which on the shared Z puts the needle low
            home.put("lowTool", n1 == null || n1[1] <= 0 ? null : n1[2] > 0 ? "needle" : "N1");
            s.put("home", home);
            DispenseService.ZCheckPlan zc = svc.zCheckPlan(null, false);
            // a board run's check after the home it does first (every height predates it): what Run announces
            DispenseService.ZCheckPlan za = svc.boardRunZCheckPlan(true);
            List<Map<String, Object>> stale = new ArrayList<>();
            for (org.openpnp.instancelabs.dispense.ZCheck.Stale x : zc.stale) {
                stale.add(Views.map("what", x.what, "atMs", x.atMs));
            }
            // after a failed check of this home: the places set before it (their Z moved too), the derived test dot aside
            List<Map<String, Object>> places = new ArrayList<>();
            DispenseConfig cfg = svc.getConfig();
            for (String name : org.openpnp.instancelabs.dispense.ZCheck.movedPlaces(cfg, zc.last, zc.homeAtMs)) {
                if (!DispenseEndpoints.TEST_DOT_LOCATION.equals(name)) {
                    places.add(Views.map("name", name, "atMs", cfg.location(name).setAtMs));
                }
            }
            s.put("zCheck", Views.map("needed", zc.needed, "stale", stale, "homeAtMs", zc.homeAtMs, "reference",
                    zc.reference == null ? null : Views.map("name", zc.reference.name, "x", zc.reference.x, "y",
                            zc.reference.y, "z", zc.reference.z), "last", zc.last, "estimateS",
                    DispenseService.PROBE_POINT_ESTIMATE_S, "touches", zc.touches, "touchesAfterHome", za.touches,
                    "places", places));
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
        return frame(cam, r.queryInt("w", 0, 0, 4096), 0);
    }

    /**
     * One frame of an OpenPnP camera (no motion, no lights, no scripts), at most {@code maxW} wide (0 = as is), taken
     * {@code settleMs} after the call: JPEG (PNG without a JPEG writer), with {@code X-Frame-At} the server time when
     * it was taken (BR-53: the picture's age).
     */
    ApiServer.Response frame(Camera cam, int maxW, long settleMs) throws Exception {
        if (settleMs > 0) {
            Thread.sleep(settleMs);
        }
        BufferedImage img;
        long at;
        ExecutorService ex = cameraExecutors.computeIfAbsent(cam, c -> Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "il-api-camera-" + c.getName());
            t.setDaemon(true);
            return t;
        }));
        Future<BufferedImage> f = ex.submit(cam::captureTransformed);
        try {
            img = f.get(10, TimeUnit.SECONDS);
            at = System.currentTimeMillis();
        }
        catch (TimeoutException e) {
            f.cancel(true);
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
        return encodeFrame(rgb).header("X-Frame-At", String.valueOf(at));
    }

    /** The camera frames' JPEG quality (0..1): light enough for a 2-5 per second view over the tailnet. */
    static final float JPEG_QUALITY = 0.85f;
    /** Tests' bound for a 1920 x 1080 frame at {@link #JPEG_QUALITY} (a PNG of it was 1.4-1.8 MB on 11-03). */
    static final int FRAME_JPEG_BOUND_BYTES = 600_000;
    /**
     * JPEG at {@link #JPEG_QUALITY} ({@link org.openpnp.instancelabs.dispense.JpegEncoder}: ImageIO's writer, else
     * OpenCV's; the deployed JVM has no ImageIO JPEG writer, so frames went out as 1.4-1.8 MB PNGs, 11-03); PNG only
     * when neither works. Never an empty 200.
     */
    ApiServer.Response encodeFrame(BufferedImage rgb) throws IOException, ApiException {
        byte[] jpg = org.openpnp.instancelabs.dispense.JpegEncoder.encode(rgb, JPEG_QUALITY);
        if (jpg != null) {
            return ApiServer.Response.bytes(200, "image/jpeg", jpg);
        }
        if (!noJpegLogged) {
            noJpegLogged = true;
            Logger.warn("Camera frames: no JPEG encoder (ImageIO writers: {}); sending PNG",
                    String.join(",", ImageIO.getWriterFormatNames()));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(rgb, "png", out) || out.size() == 0) {
            throw ApiException.unavailable("the camera frame could not be encoded (no JPEG or PNG writer)");
        }
        return ApiServer.Response.bytes(200, "image/png", out.toByteArray());
    }

    void shutdown() {
        for (ExecutorService ex : cameraExecutors.values()) {
            ex.shutdownNow();
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
