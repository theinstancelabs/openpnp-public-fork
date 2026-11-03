package org.openpnp.instancelabs.dispense;

/** Progress callbacks. Called on the machine thread; implementations must return quickly. */
public interface DispenseListener {
    default void onPhase(RunResult run, String phase) {
    }

    default void onDot(RunResult run, RunResult.DotTiming timing, int done, int total) {
    }

    default void onFinished(RunResult run) {
    }

    /**
     * Structured live progress (Register board, the probes, a run's home and Z check): a small copy,
     * sent at every phase, fiducial capture and probe Z step.
     */
    default void onDetail(RunResult run, java.util.Map<String, Object> detail) {
    }
}
