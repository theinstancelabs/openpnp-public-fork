package org.openpnp.instancelabs.api;

import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.openpnp.instancelabs.board.BoardImporter;
import org.openpnp.instancelabs.dispense.DispensePlan;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.InterlockReport;
import org.openpnp.instancelabs.dispense.Interlocks;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Machine;

/** State and helpers shared by the endpoint groups. */
final class ApiContext {
    /** Longest a {@code "wait": true} request blocks for its run. */
    static final long MAX_WAIT_MS = 10 * 60 * 1000L;

    final Configuration configuration;
    final RunTracker tracker = new RunTracker();
    final PlanStore plans = new PlanStore();
    /** The paste on each seated board (every tab and browser sees the same board). */
    final BoardPaste paste = new BoardPaste(plans);
    final PressureHold pressure;
    /** The pad calibrations (PROFILE_CALIBRATION_DESIGN.md §7): one at a time, its lock on the other tasks. */
    final PadCalibrationJobs padCalJobs = new PadCalibrationJobs(this);
    private DispenseService listened;

    ApiContext(Configuration configuration) {
        this.configuration = configuration;
        this.pressure = new PressureHold(this);
    }

    Machine machine() throws ApiException {
        Machine m = configuration.getMachine();
        if (m == null) {
            throw ApiException.unavailable("OpenPnP has no machine configured");
        }
        return m;
    }

    /** The engine, or null before the configuration has loaded. Keeps our listeners attached. */
    DispenseService svcOrNull() {
        DispenseService s = DispenseService.get();
        attach(s);
        return s;
    }

    /** The engine (may be "unavailable", e.g. no N2). */
    DispenseService svc() throws ApiException {
        DispenseService s = svcOrNull();
        if (s == null) {
            throw ApiException.unavailable(
                    "the dispense engine is not running (configuration not loaded, or it failed to start: see the OpenPnP log)");
        }
        return s;
    }

    /** The engine, which must have its dispenser nozzle. */
    DispenseService availableSvc() throws ApiException {
        DispenseService s = svc();
        if (!s.isAvailable()) {
            throw ApiException.unavailable("the dispenser is unavailable: " + s.getUnavailableReason());
        }
        return s;
    }

    private synchronized void attach(DispenseService s) {
        if (s == null || s == listened) {
            return;
        }
        if (listened != null) {
            listened.removeListener(tracker);
            listened.removeListener(pressure);
            listened.removeListener(paste);
        }
        s.addListener(tracker);
        s.addListener(pressure);
        s.addListener(paste);
        s.setPressureHoldCheck(pressure::isActive);
        listened = s;
    }

    /** Engine runs pressurise on their own: not while the manual pressure hold is on. */
    void requireNoPressureHold() throws ApiException {
        if (pressure.isActive()) {
            throw ApiException.refused("the pressure is being held on from the Dispense tab. Switch it off first: "
                    + "prime, test dot, runs and calibrations switch the pressure on and off themselves")
                    .with("pressure", pressure.snapshot());
        }
    }

    /**
     * No engine task runs, no pad calibration is between its engine steps (§7.1), and no board upload is reading
     * its file (BI §3.4: no parse runs while a dispense, registration or probe task streams).
     */
    void requireEngineIdle(DispenseService s) throws ApiException {
        String job = padCalJobs.lockReason();
        if (job != null) {
            throw ApiException.refused(job);
        }
        // a task OpenPnP dropped (one of its own ahead of it failed) never ends by itself: it ends here (rc1 review)
        if (s.isBusy() && !s.releaseDroppedTask()) {
            throw ApiException.refused("a dispense task is already running; wait for it or press STOP");
        }
        if (BoardImporter.reading()) {
            throw ApiException.refused("a board file is being read (an upload takes up to about 20 s): wait for it "
                    + "to finish");
        }
    }

    /**
     * A task queued behind another OpenPnP machine task that fails is dropped and never runs: the
     * tasks that home or move refuse while OpenPnP is busy.
     */
    void requireMachineIdle() throws ApiException {
        if (machine().isBusy()) {
            throw ApiException.refused("OpenPnP is busy (homing or moving): wait for it to finish");
        }
    }

    /**
     * The engine's pre-flight interlocks for a run without a board plan (no plan rules, no I9),
     * with the run's I4 scope. The engine re-checks, with the exact dots, when it runs.
     */
    InterlockReport preflight(DispenseService s, Interlocks.Scope scope) {
        return s.checkInterlocks(scope, 0);
    }

    InterlockReport preflightPlan(DispenseService s, DispensePlan plan, double estimatedPressurisedSeconds) {
        return s.checkInterlocks(plan, estimatedPressurisedSeconds);
    }

    /** As above for a run that homes first and / or checks Z itself after the home. */
    InterlockReport preflightPlan(DispenseService s, DispensePlan plan, double estimatedPressurisedSeconds,
            boolean homeFirst, boolean zCheckFirst) {
        return s.checkInterlocks(plan, estimatedPressurisedSeconds, homeFirst, zCheckFirst);
    }

    static void refuseUnlessOk(InterlockReport r, String what) throws ApiException {
        if (!r.ok()) {
            StringBuilder sb = new StringBuilder(what + " refused by the interlocks: ");
            for (int i = 0; i < r.violations.size(); i++) {
                InterlockReport.Violation v = r.violations.get(i);
                sb.append(i == 0 ? "" : "; ").append(v.code).append(": ").append(v.message);
            }
            throw ApiException.refused(sb.toString()).with("interlocks", Views.interlocks(r));
        }
    }

    /**
     * With {@code wait}, blocks until the engine task finishes (bounded) and returns its result;
     * otherwise returns at once. The UI follows progress through /api/status either way.
     */
    static <T> Object awaitIf(boolean wait, Future<T> f, Function<T, Object> view, Map<String, Object> accepted)
            throws Exception {
        if (!wait) {
            accepted.put("started", true);
            return ApiServer.Response.json(202, accepted);
        }
        try {
            accepted.put("result", view.apply(f.get(MAX_WAIT_MS, TimeUnit.MILLISECONDS)));
            accepted.put("finished", true);
            return accepted;
        }
        catch (TimeoutException e) {
            accepted.put("finished", false);
            accepted.put("note", "still running after " + MAX_WAIT_MS / 1000 + " s; follow /api/status");
            return ApiServer.Response.json(202, accepted);
        }
    }

    void shutdown() {
        padCalJobs.shutdown();
        pressure.shutdown();
        synchronized (this) {
            if (listened != null) {
                listened.removeListener(tracker);
                listened.removeListener(pressure);
                listened = null;
            }
        }
    }
}
