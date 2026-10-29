package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.openpnp.instancelabs.dispense.Balance;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DoseBlocksRequest;
import org.openpnp.instancelabs.dispense.DoseBlocksResult;
import org.openpnp.instancelabs.dispense.ManualBalance;
import org.openpnp.instancelabs.dispense.SurfaceProbeRequest;
import org.openpnp.instancelabs.dispense.SurfaceProbeResult;
import org.openpnp.instancelabs.sim.SimBalance;

/**
 * The pad calibrations of the API (PROFILE_CALIBRATION_DESIGN.md §7.1): one job at a time, run on a
 * thread of its own that hands each engine step to the machine thread and waits for it, the jobs of
 * this session kept for their crops (the last {@value #KEEP}), and the balance the jobs weigh on.
 * While a job is running, deciding or planning, every other dispense task is refused
 * ({@link ApiContext#requireEngineIdle}); while it waits for the readings nothing is locked but its
 * coupons (§7.5).
 */
final class PadCalibrationJobs {
    /** Jobs kept in memory for their crops and results. */
    static final int KEEP = 5;

    private final ApiContext ctx;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "il-pad-calibration");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, PadCalibrationJob> recent = new LinkedHashMap<>();
    private volatile PadCalibrationJob current;
    /** The engine steps the jobs queue: the dispense service's, or a test's. */
    volatile Engine engine;

    /** The engine steps of a job (§7.1): each an ordinary engine task. */
    interface Engine {
        Future<SurfaceProbeResult> probeSurface(SurfaceProbeRequest r) throws Exception;

        Future<DoseBlocksResult> runDoseBlocks(DoseBlocksRequest r) throws Exception;

        long stopEpoch();

        void abort(String reason);
    }

    PadCalibrationJobs(ApiContext ctx) {
        this.ctx = ctx;
    }

    /** The engine of the jobs: a test's when set, else the dispense service's. */
    Engine engine(DispenseService svc) {
        Engine e = engine;
        return e != null ? e : new Engine() {
            @Override
            public Future<SurfaceProbeResult> probeSurface(SurfaceProbeRequest r) throws Exception {
                return svc.probeSurface(r);
            }

            @Override
            public Future<DoseBlocksResult> runDoseBlocks(DoseBlocksRequest r) throws Exception {
                return svc.runDoseBlocks(r);
            }

            @Override
            public long stopEpoch() {
                return svc.stopEpoch();
            }

            @Override
            public void abort(String reason) {
                svc.abort(reason);
            }
        };
    }

    /** The newest job (running, waiting or finished), or null. */
    PadCalibrationJob current() {
        return current;
    }

    synchronized PadCalibrationJob get(String id) {
        return recent.get(id);
    }

    /** Why other dispense tasks must wait, or null: a job runs, decides or plans (§7.1). */
    String lockReason() {
        PadCalibrationJob j = current;
        return j != null && j.holdsLock() ? "a pad calibration is running (" + j.stepWords()
                + "): wait for it or press STOP" : null;
    }

    /** One job at a time: 409 while another has not ended (its readings included). */
    synchronized void start(PadCalibrationJob job) throws ApiException {
        PadCalibrationJob j = current;
        if (j != null && !j.ended()) {
            throw ApiException.refused("a pad calibration is " + (j.holdsLock() ? "running (" + j.stepWords() + ")"
                    : "waiting for the readings of its coupons") + ": finish, cancel or stop it first");
        }
        current = job;
        recent.put(job.id, job);
        while (recent.size() > KEEP) {
            recent.remove(recent.keySet().iterator().next());
        }
        executor.submit(job::run);
    }

    /** Runs a step of a job on the jobs' thread (the decision after the readings). */
    void later(Runnable r) {
        executor.submit(r);
    }

    /** STOP: the running job stops before its next engine step (called before the engine's abort, §7.8). */
    void stop(String reason) {
        PadCalibrationJob j = current;
        if (j != null) {
            j.stop(reason);
        }
    }

    /** The jobs of this session, newest first. */
    synchronized List<PadCalibrationJob> list() {
        List<PadCalibrationJob> l = new ArrayList<>(recent.values());
        java.util.Collections.reverse(l);
        return l;
    }

    /**
     * The balance a job weighs on (§10): the SIM balance when the simulator is confirmed and the sim
     * scene has a balance, else the operator types the readings.
     */
    Balance balance() {
        Balance b = SimBalance.forConfiguration(ctx.configuration);
        return b != null ? b : new ManualBalance();
    }

    void shutdown() {
        PadCalibrationJob j = current;
        if (j != null) {
            j.stop("the web API is shutting down");
        }
        executor.shutdownNow();
    }
}
