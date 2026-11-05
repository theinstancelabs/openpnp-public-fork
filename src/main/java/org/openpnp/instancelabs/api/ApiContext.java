package org.openpnp.instancelabs.api;

import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.openpnp.instancelabs.board.BoardImporter;
import org.openpnp.instancelabs.dispense.ControllerReset;
import org.openpnp.instancelabs.dispense.DispensePlan;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.InterlockReport;
import org.openpnp.instancelabs.dispense.Interlocks;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Driver;
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
    /** OpenPnP switching the machine off (BR-52): Start waits for it, nothing else starts meanwhile. */
    final SwitchOff switchOff = new SwitchOff();
    /** Web STOPs so far (a take-over's too): a move that saw one end meanwhile did not arrive (BR-49). */
    final AtomicLong stops = new AtomicLong();
    /** Tests only: added to the lock's clock (a lease's lapse without waiting for it). */
    final AtomicLong lockClockSkewMs = new AtomicLong();
    /** BR-72: the one machine lock. */
    final MachineLock lock = new MachineLock(() -> System.currentTimeMillis() + lockClockSkewMs.get());
    /** BR-72: the lock holder's G-code through OpenPnP's driver. */
    final GcodePassthrough passthrough = new GcodePassthrough(this);
    /** BR-98: the collision check of the API's moves, and the bed's views. */
    final BedGuard bed = new BedGuard(this);
    private DispenseService listened;

    ApiContext(Configuration configuration) {
        this.configuration = configuration;
        this.pressure = new PressureHold(this);
        switchOff.attach(configuration.getMachine());
    }

    Machine machine() throws ApiException {
        Machine m = configuration.getMachine();
        if (m == null) {
            throw ApiException.unavailable("OpenPnP has no machine configured");
        }
        switchOff.attach(m);
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
        requireNotSwitchingOff();
        requireLink();
        if (machine().isBusy()) {
            throw ApiException.refused("OpenPnP is busy (homing or moving): wait for it to finish");
        }
    }

    /**
     * BR-93: a G-code driver's lost link to the controller ({@link GcodeDriver#getLinkLoss}), or null. The engine puts
     * it on record as it is read, so the status and the refusals show the same state.
     */
    GcodeDriver.LinkLoss linkLoss() {
        Machine m = configuration.getMachine();
        for (Driver d : m == null ? java.util.Collections.<Driver> emptyList() : m.getDrivers()) {
            GcodeDriver.LinkLoss l = d instanceof GcodeDriver ? ((GcodeDriver) d).getLinkLoss() : null;
            if (l != null) {
                DispenseService s = svcOrNull();
                if (s != null) {
                    s.noteLinkLoss(l);
                }
                return l;
            }
        }
        return null;
    }

    /** BR-93: nothing that moves or sends a line starts on a lost link (STOP still runs: it switches off what it can). */
    void requireLink() throws ApiException {
        GcodeDriver.LinkLoss l = linkLoss();
        if (l != null) {
            throw ApiException.refused("MACHINE: " + ControllerReset.linkLostWhy(l.atMs, l.cause) + ": "
                    + ControllerReset.LINK_LOST_REMEDY);
        }
    }

    /**
     * BR-02: after a controller reset nothing moves until a home (the home route itself asks for the operator's word
     * that nothing is below). The engine's interlocks refuse too; this answers before anything else is checked.
     */
    void requirePositionKnown(DispenseService s) throws ApiException {
        String why = s == null ? null : s.positionUnknownWhy();
        if (why != null) {
            throw ApiException.refused("MACHINE: " + why + ": " + Interlocks.POSITION_UNKNOWN_REMEDY);
        }
    }

    /**
     * BR-52: while OpenPnP switches the machine off (a STOP's DISABLE_COMMAND, the link closing) it still reads started:
     * nothing that moves or switches an output starts then.
     */
    void requireNotSwitchingOff() throws ApiException {
        if (switchOff.inProgress()) {
            throw ApiException.refused(SwitchOff.REFUSAL);
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
        switchOff.detach();
        synchronized (this) {
            if (listened != null) {
                listened.removeListener(tracker);
                listened.removeListener(pressure);
                listened = null;
            }
        }
    }
}
