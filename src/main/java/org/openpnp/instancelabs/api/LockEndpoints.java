package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.pmw.tinylog.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * BR-72: the machine lock's routes, the guard on every write ({@link #admit}), Take control and the lease's lapse, and
 * the G-code passthrough's route (docs/backend-requests.md "BR-72 API").
 *
 * <p>The end of a holder's work (a take-over, a lapse) never sends anything inside a home (a STOP during a home hangs
 * this controller, F15): it waits up to {@value #HOME_WAIT_MS} ms for the home to end. Then a working holder (a call
 * running, a move queued since its last M400, an engine task, a pressure hold, a pad calibration, OpenPnP busy) is
 * stopped with the web STOP (the machine ends off and unhomed); an idle one has its outputs switched off (the machine
 * stays started and homed).</p>
 */
final class LockEndpoints {
    static final long HOME_WAIT_MS = 120_000;
    static final long STOP_CONFIRM_MS = 20_000;
    static final String GCODE = "/api/gcode";
    /** BR-75: the deployer's planned stop, a guarded write the web UI may never send. */
    static final String PLANNED_STOP = "/api/stop/planned";

    /** Writes everyone may always send ("METHOD path"): the stops and the lock's own routes. */
    private static final List<Pattern> OPEN = patterns("POST /api/stop", "POST /api/pressure/stop",
            "POST /api/pad-calibrations/[^/]+/cancel", "POST /api/lock/(acquire|keepalive|release|take)");
    /** Writes with no machine effect and no live value the machine uses: never locked. */
    private static final List<Pattern> DATA = patterns("POST /api/photos", "DELETE /api/photos/[^/]+",
            "POST /api/photos/[^/]+/(restore|main|links)", "POST /api/boards/import(/save|/attach)?",
            "POST /api/plan", "POST /api/pad-types", "(PUT|DELETE) /api/pad-types/[^/]+",
            "(PUT /api/pad-types/[^/]+/rule|POST /api/pad-types/[^/]+/copy)", "POST /api/package-mappings",
            "PUT /api/pad-calibrations/[^/]+/label", "POST /api/pad-calibrations/[^/]+/wrong-tip",
            "POST /api/coupons/[^/]+/reading", "POST /api/tip-setups", "PUT /api/tip-setups/[^/]+",
            "POST /api/tip-setups/[^/]+/(retire|unretire)");

    private final ApiContext ctx;
    private final MachineEndpoints machine;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "il-api-lock");
        t.setDaemon(true);
        return t;
    });
    private final MachineListener machineWatch = new MachineListener.Adapter() {
        @Override
        public void machineDisabled(Machine m, String reason) {
            ctx.passthrough.machineStopped();
        }

        @Override
        public void machineHomed(Machine m, boolean isHomed) {
            if (isHomed) {
                ctx.passthrough.machineHomed();
            }
        }
    };
    private Machine watched;

    LockEndpoints(ApiContext ctx, MachineEndpoints machine) {
        this.ctx = ctx;
        this.machine = machine;
        watch();
        timer.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    private static List<Pattern> patterns(String... p) {
        List<Pattern> l = new ArrayList<>();
        for (String s : p) {
            l.add(Pattern.compile(s));
        }
        return l;
    }

    private static boolean any(List<Pattern> l, String s) {
        for (Pattern p : l) {
            if (p.matcher(s).matches()) {
                return true;
            }
        }
        return false;
    }

    /** "guarded", "open" or "data": how the lock treats a write route (GETs are never guarded). */
    static String kindOf(String method, String path) {
        String key = method + " " + path;
        return any(OPEN, key) ? "open" : any(DATA, key) ? "data" : "guarded";
    }

    void register(ApiServer s) {
        s.route("GET", "/api/lock", r -> ctx.lock.view());
        s.route("POST", "/api/lock/acquire", this::acquire);
        s.route("POST", "/api/lock/keepalive", this::keepalive);
        s.route("POST", "/api/lock/release", this::release);
        s.route("POST", "/api/lock/take", this::take);
        s.route("POST", GCODE, this::gcode);
    }

    void shutdown() {
        timer.shutdownNow();
        synchronized (this) {
            if (watched != null) {
                watched.removeListener(machineWatch);
                watched = null;
            }
        }
    }

    private synchronized void watch() {
        Machine m = ctx.configuration.getMachine();
        if (m != null && m != watched) {
            if (watched != null) {
                watched.removeListener(machineWatch);
            }
            m.addListener(machineWatch);
            watched = m;
        }
    }

    static boolean isUi(ApiServer.Request r) {
        String c = r.header(MachineLock.UI_HEADER);
        return c != null && "ui".equalsIgnoreCase(c.trim());
    }

    static String token(ApiServer.Request r) {
        String t = r.header(MachineLock.TOKEN_HEADER);
        return t == null || t.trim().isEmpty() ? null : t.trim();
    }

    // ------------------------------------------------------------------------------------------
    // The guard
    // ------------------------------------------------------------------------------------------

    /**
     * Every routed request, before its handler: GETs, the open writes and the data writes pass; a guarded write passes
     * the lock's rules ({@link MachineLock#admit}) and, unless it is the passthrough, finds OpenPnP's coordinates
     * re-read after a passthrough move. The activity is ended by the caller ({@link MachineLock#end}).
     */
    MachineLock.Activity admit(ApiServer.Request r) throws ApiException {
        if ("GET".equals(r.method) || "HEAD".equals(r.method) || !"guarded".equals(kindOf(r.method, r.path))) {
            return null;
        }
        // BR-75 (the PM): never the web UI's, and refused before its request could make the user the holder
        if (PLANNED_STOP.equals(r.path) && isUi(r)) {
            throw ApiException.refused("the planned stop is for deploys; the UI's STOP is POST /api/stop");
        }
        watch();
        MachineLock.Activity a = ctx.lock.admit(isUi(r), token(r), "using the web UI");
        if (!GCODE.equals(r.path)) {
            try {
                ctx.passthrough.resyncIfStale();
            }
            catch (ApiException e) {
                ctx.lock.end(a);
                throw e;
            }
        }
        return a;
    }

    // ------------------------------------------------------------------------------------------
    // Agents
    // ------------------------------------------------------------------------------------------

    Object acquire(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        return ctx.lock.acquire(Json.str(b, "who", null), Json.str(b, "label", null), Json.str(b, "what", null),
                Json.str(b, "thread", null), Json.integer(b, "ttlS", null), token(r));
    }

    Object keepalive(ApiServer.Request r) throws Exception {
        String t = token(r);
        if (t == null) {
            throw new ApiException(409, "no-lock", "send the lock token as " + MachineLock.TOKEN_HEADER);
        }
        return ctx.lock.keepalive(t, Json.str(r.json(), "what", null));
    }

    Object release(ApiServer.Request r) throws Exception {
        String t = token(r);
        MachineLock.Holder h = ctx.lock.current();
        boolean mine = h != null && (t != null ? t.equals(h.token) : isUi(r) && h.kind == MachineLock.Kind.USER);
        if (mine) {
            // OpenPnP must know where the head is before the next holder moves it
            try {
                ctx.passthrough.resyncIfStale();
            }
            catch (ApiException e) {
                Logger.warn("release: {}", e.getMessage());
            }
        }
        return ctx.lock.release(t, isUi(r));
    }

    // ------------------------------------------------------------------------------------------
    // Take control (the web UI)
    // ------------------------------------------------------------------------------------------

    /** The take in progress: a second Take control (another window, a double click) joins it (#19 review MAJOR 3). */
    private final Object takeLock = new Object();
    private CompletableFuture<Object> takeRunning;

    Object take(ApiServer.Request r) throws Exception {
        if (!isUi(r)) {
            throw new ApiException(403, "not-ui", "Take control is the web UI's (" + MachineLock.UI_HEADER + ": ui): "
                    + "agents take the lock with POST /api/lock/acquire while nobody holds it");
        }
        CompletableFuture<Object> mine = null, joined;
        synchronized (takeLock) {
            joined = takeRunning;
            if (joined == null) {
                mine = new CompletableFuture<>();
                takeRunning = mine;
            }
        }
        if (joined != null) {
            try {
                return joined.get(HOME_WAIT_MS + STOP_CONFIRM_MS + 60_000L, TimeUnit.MILLISECONDS);
            }
            catch (ExecutionException e) {
                throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
            }
        }
        try {
            Object res = doTake();
            mine.complete(res);
            return res;
        }
        catch (Exception e) {
            mine.completeExceptionally(e);
            throw e;
        }
        finally {
            synchronized (takeLock) {
                takeRunning = null;
            }
        }
    }

    private Object doTake() throws Exception {
        long at = System.currentTimeMillis();
        MachineLock.Holder old = ctx.lock.takeForUser();
        Logger.info("Take control: the user holds the machine{}", old == null ? "" : " (taken from " + old.label
                + ": " + old.what + ")");
        if (old == null) {
            return Views.map("lock", ctx.lock.view(), "stopped", null, "atMs", at);
        }
        Map<String, Object> stopped;
        try {
            stopped = endHolderWork(old, "the user took control from " + old.label);
        }
        finally {
            ctx.lock.handoverDone();
        }
        Logger.info("Take control from {}: {}", old.label, stopped);
        ctx.passthrough.log("engine", "!", "the user took control from " + old.label + ": " + stopped.get("detail"));
        Map<String, Object> body = Views.map("lock", ctx.lock.view(), "stopped", stopped, "atMs", at);
        if (!Boolean.TRUE.equals(stopped.get("confirmed"))) {
            ApiException e = new ApiException(409, "stop-not-confirmed", "You have the machine, but stopping "
                    + old.label + "'s work is not confirmed: " + String.join("; ", actions(stopped))
                    + ". Press STOP; if anything still runs, switch the controller off.");
            e.details.putAll(body);
            throw e;
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private static List<String> actions(Map<String, Object> stopped) {
        return (List<String>) stopped.get("actions");
    }

    /**
     * Ends a holder's work after a take-over or a lapse (see the class comment). Never throws: what was done is in
     * {@code actions}, {@code confirmed} false when the machine may still run.
     */
    Map<String, Object> endHolderWork(MachineLock.Holder old, String why) {
        // actions: plain phrases the user reads verbatim (the UI's take-over confirmation); detail: what was sent
        List<String> actions = new ArrayList<>();
        List<String> detail = new ArrayList<>();
        Map<String, Object> out = Views.map("label", old.label, "who", old.who, "what", old.what, "mode", "stop",
                "actions", actions, "detail", detail, "machineOff", false, "confirmed", false);
        Machine m = ctx.configuration.getMachine();
        if (m == null) {
            actions.add("no machine");
            out.put("mode", "outputs-off");
            out.put("confirmed", true);
            return out;
        }
        // what runs now (the plain phrases), then nothing new starts: a batch stops before its next line, a pad
        // calibration queues no further step, and an engine task not yet at its G28 never sends it (homeSafely checks
        // the abort before it); a home already running is left to finish (#19 review MINOR 5)
        DispenseService svc = ctx.svcOrNull();
        boolean batch = ctx.passthrough.running();
        boolean run = svc != null && svc.isBusy();
        boolean hold = ctx.pressure.isActive();
        boolean padCal = ctx.padCalJobs.lockReason() != null;
        boolean working0 = ctx.lock.busy(old) || batch || ctx.passthrough.motionPending() || run || hold || padCal
                || m.isBusy() || homing(m);
        if (working0) {
            ctx.passthrough.abort(why);
            ctx.padCalJobs.stop(why);
            if (svc != null && !homing(m)) {
                svc.abort(why);
            }
        }
        long t0 = System.currentTimeMillis();
        while (homing(m) && System.currentTimeMillis() - t0 < HOME_WAIT_MS) {
            sleep(100);
        }
        long waited = System.currentTimeMillis() - t0;
        if (waited >= 200) {
            actions.add(String.format(Locale.ROOT, "waited %.0f s for its home to finish", Math.max(1, waited / 1000.0)));
            detail.add(String.format(Locale.ROOT, "waited %.1f s for the home in progress to end (a STOP inside a home "
                    + "hangs this controller, F15)", waited / 1000.0));
        }
        if (homing(m)) {
            actions.add("its home still runs: nothing was sent during it; press STOP when it ends");
            detail.add("a home still runs after " + HOME_WAIT_MS / 1000 + " s: nothing was sent during it");
            out.put("machineOff", !m.isEnabled());
            return out;
        }
        // after the home: what still runs decides (a home that ended on its own leaves the machine homed)
        boolean working = ctx.lock.busy(old) || ctx.passthrough.running() || ctx.passthrough.motionPending()
                || svc != null && svc.isBusy() || ctx.pressure.isActive() || ctx.padCalJobs.lockReason() != null
                || m.isBusy();
        if (!working) {
            if (!m.isEnabled()) {
                actions.add("the machine was off");
                out.put("mode", "outputs-off");
                out.put("machineOff", true);
                out.put("confirmed", true);
                return out;
            }
            try {
                detail.addAll(ctx.passthrough.outputsOff(m));
                actions.addAll(GcodePassthrough.OUTPUT_NAMES);
                out.put("mode", "outputs-off");
                out.put("confirmed", true);
                return out;
            }
            catch (Exception e) {
                actions.add("switching its outputs off failed, so it was stopped as STOP does");
                detail.add("switching the outputs off failed (" + MachineEndpoints.rootMessage(e) + "): STOP instead");
            }
        }
        if (batch) {
            actions.add("its G-code batch stopped");
        }
        if (run) {
            actions.add("its run stopped");
        }
        if (hold) {
            actions.add("the pressure hold vented");
        }
        if (padCal) {
            actions.add("its pad calibration stopped");
        }
        boolean sends = MachineEndpoints.sendsDisableCommand(svc); // BR-75: not once a planned stop closed the link
        Map<String, Object> stop = machine.stop(why);
        @SuppressWarnings("unchecked")
        List<String> stopActions = (List<String>) stop.get("actions");
        detail.addAll(stopActions);
        long t1 = System.currentTimeMillis();
        while ((m.isEnabled() || ctx.switchOff.inProgress()) && System.currentTimeMillis() - t1 < STOP_CONFIRM_MS) {
            sleep(50);
        }
        boolean off = !m.isEnabled();
        if (off) {
            actions.add(sends ? "the machine switched off (quick stop; valve, pump and screw off)" : "the machine "
                    + "switched off; a planned stop had closed the controller link, so nothing was sent and the screw's "
                    + "motor stays on until the next Start");
        }
        else {
            actions.add("the machine is still switching off");
            detail.add("the machine still reads started " + STOP_CONFIRM_MS / 1000 + " s after the STOP");
        }
        out.put("machineOff", off);
        out.put("confirmed", off);
        return out;
    }

    private static boolean homing(Machine m) {
        return m instanceof ReferenceMachine && ((ReferenceMachine) m).isHoming();
    }

    /** The lease's lapse, every second; never inside a home. Never throws (a throwing task is not run again). */
    private void tick() {
        try {
            Machine m = ctx.configuration.getMachine();
            MachineLock.Holder cur = ctx.lock.current();
            if (cur != null && cur.kind == MachineLock.Kind.AGENT) {
                // the agent's work on the machine (a 202 run, a home, a hold, a batch): never a lapse in the middle
                // of it, and it shows as busy (#19 review MAJOR 2)
                DispenseService svc = ctx.svcOrNull();
                boolean work = m != null && (m.isBusy() || homing(m)) || svc != null && svc.isBusy()
                        || ctx.pressure.isActive() || ctx.padCalJobs.lockReason() != null || ctx.passthrough.running();
                ctx.lock.markWorking(cur, work);
                if (work) {
                    return;
                }
            }
            if (m != null && homing(m)) {
                return;
            }
            MachineLock.Holder h = ctx.lock.beginExpiry();
            if (h == null) {
                return;
            }
            try {
                Map<String, Object> done = endHolderWork(h, h.label + "'s lease ran out");
                Logger.info("lock: {}'s lease ran out ({}): {}", h.label, h.what, done);
                ctx.passthrough.log("engine", "!", h.label + "'s lease ran out: " + done.get("detail"));
            }
            finally {
                ctx.lock.finishExpiry(h);
            }
        }
        catch (Throwable t) {
            Logger.warn(t, "lock: the lease check failed");
        }
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
    // The passthrough
    // ------------------------------------------------------------------------------------------

    Object gcode(ApiServer.Request r) throws Exception {
        MachineLock.Activity a = r.lockActivity;
        if (a == null) {
            throw new ApiException(409, "no-lock", "the G-code passthrough needs the lock: POST /api/lock/acquire, then "
                    + "send its token as " + MachineLock.TOKEN_HEADER).with("lock", ctx.lock.view());
        }
        JsonObject b = r.json();
        JsonElement l = b.get("lines");
        if (l == null || !l.isJsonArray()) {
            throw ApiException.badRequest("'lines' (an array of G-code lines) is required");
        }
        List<String> lines = new ArrayList<>();
        for (JsonElement e : (JsonArray) l) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                throw ApiException.badRequest("'lines' holds strings only");
            }
            lines.add(e.getAsString());
        }
        Integer t = Json.integer(b, "timeoutMs", (int) GcodePassthrough.DEFAULT_TIMEOUT_MS);
        String what = Json.str(b, "what", null);
        if (what != null && a.holder.token != null) {
            ctx.lock.keepalive(a.holder.token, what);
        }
        GcodePassthrough.Outcome o = ctx.passthrough.run(lines, t, a.holder, what);
        if ("ok".equals(o.status)) {
            return Views.map("results", o.results, "ms", o.ms, "lock", ctx.lock.view());
        }
        ApiException e;
        switch (o.status) {
            case "lock-lost":
                e = ctx.lock.lostError(a.holder);
                break;
            case "stopped":
                e = new ApiException(409, "stopped", "the batch stopped before line " + (o.index + 1) + " ('" + o.line
                        + "'): " + o.message);
                break;
            case "controller-timeout":
                e = new ApiException(504, "controller-timeout", "line " + (o.index + 1) + " '" + o.line + "' got no ok "
                        + "in " + t + " ms (" + o.message + "); the rest was not sent. Press STOP if the machine may "
                        + "still move");
                break;
            default:
                e = new ApiException(502, "controller-error", "line " + (o.index + 1) + " '" + o.line + "': "
                        + o.message + "; the rest was not sent");
                break;
        }
        return raise(e.with("index", o.index).with("line", o.line).with("replies", o.replies).with("results",
                o.results));
    }

    private static Object raise(ApiException e) throws ApiException {
        throw e;
    }

    /** For the docs and tests: every write route by how the lock treats it. */
    static List<String> describe(List<String> routes) {
        List<String> out = new ArrayList<>();
        for (String r : routes) {
            String[] p = r.split(" ", 2);
            if (!Arrays.asList("GET", "HEAD").contains(p[0])) {
                out.add(kindOf(p[0], p[1]) + " " + r);
            }
        }
        return out;
    }
}
