package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openpnp.instancelabs.dispense.DispenseListener;
import org.openpnp.instancelabs.dispense.RunResult;

/**
 * Live progress of the engine's current run, from its listener callbacks (machine thread). The UI
 * polls {@link #snapshot()}; nothing here touches the machine. RunResult's own lists are not read
 * while a run is active (they are not thread-safe).
 */
final class RunTracker implements DispenseListener {
    static final class Progress {
        String runId;
        String kind;
        String label;
        String boardName;
        String planId;
        volatile String phase;
        volatile String status = "RUNNING";
        volatile String message;
        long startedAtMs;
        volatile long finishedAtMs;
        volatile int dotsDone;
        volatile int dotsPlanned;
        volatile long firstDotStartMs;
        volatile long lastDotEndMs;
        /** When the run's pressure session started (its first pressurised phase), 0 before. */
        volatile long pressureOnAtMs;
        /** The latest structured progress (Register board, the probes, a run's home and Z check), or null. */
        volatile Map<String, Object> detail;
        /** Register board: the plan made at its end (another page follows it). */
        volatile String nextPlanId;
        final List<Integer> doneDots = new ArrayList<>();
        final List<String> phases = new ArrayList<>();
    }

    /** Phases streamed under pressure (one pressure session each: pump, valve, ..., vent). */
    static final Set<String> PRESSURE_PHASES = new HashSet<>(
            Arrays.asList("dispensing", "shots", "dispensing calibration dots"));

    private volatile Progress current;
    private volatile String pendingLabel;

    /** Label (e.g. "prime", "test dot") for the next run the engine starts. */
    void expect(String label) {
        pendingLabel = label;
    }

    private synchronized Progress forRun(RunResult run) {
        Progress p = current;
        if (p == null || !run.runId.equals(p.runId)) {
            p = new Progress();
            p.runId = run.runId;
            p.kind = run.kind;
            p.label = pendingLabel == null ? run.kind : pendingLabel;
            pendingLabel = null;
            p.boardName = run.boardName;
            p.planId = run.planId;
            p.startedAtMs = run.startedAtMs > 0 ? run.startedAtMs : System.currentTimeMillis();
            p.dotsPlanned = run.dotsPlanned;
            current = p;
        }
        return p;
    }

    @Override
    public void onPhase(RunResult run, String phase) {
        Progress p = forRun(run);
        p.phase = phase;
        if (p.pressureOnAtMs == 0 && PRESSURE_PHASES.contains(phase)) {
            p.pressureOnAtMs = System.currentTimeMillis();
        }
        synchronized (p) {
            p.phases.add(phase);
        }
    }

    @Override
    public void onDot(RunResult run, RunResult.DotTiming t, int done, int total) {
        Progress p = forRun(run);
        synchronized (p) {
            if (p.firstDotStartMs == 0) {
                p.firstDotStartMs = t.startMs;
            }
            p.lastDotEndMs = t.endMs;
            p.dotsDone = done;
            p.dotsPlanned = total;
            p.doneDots.add(t.dotIndex);
        }
    }

    @Override
    public void onDetail(RunResult run, Map<String, Object> detail) {
        forRun(run).detail = detail == null ? null : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }

    @Override
    public void onFinished(RunResult run) {
        Progress p = forRun(run);
        p.message = run.message;
        p.nextPlanId = run.nextPlanId;
        p.finishedAtMs = run.endedAtMs > 0 ? run.endedAtMs : System.currentTimeMillis();
        p.dotsDone = run.dotsDone;
        p.status = String.valueOf(run.status);
        p.phase = "finished: " + run.status;
    }

    /** The latest run's progress (running or finished), or null if none since start-up. */
    Map<String, Object> snapshot() {
        Progress p = current;
        if (p == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        boolean running = "RUNNING".equals(p.status);
        m.put("runId", p.runId);
        m.put("kind", p.kind);
        m.put("label", p.label);
        m.put("boardName", p.boardName);
        m.put("planId", p.planId);
        m.put("running", running);
        m.put("status", p.status);
        m.put("message", p.message);
        m.put("phase", p.phase);
        m.put("startedAtMs", p.startedAtMs);
        m.put("finishedAtMs", p.finishedAtMs == 0 ? null : p.finishedAtMs);
        m.put("elapsedS", ((running ? now : p.finishedAtMs) - p.startedAtMs) / 1000.0);
        m.put("pressureOnAtMs", p.pressureOnAtMs == 0 ? null : p.pressureOnAtMs);
        m.put("detail", p.detail);
        m.put("nextPlanId", p.nextPlanId);
        synchronized (p) {
            m.put("dotsDone", p.dotsDone);
            m.put("dotsPlanned", p.dotsPlanned);
            m.put("doneDots", new ArrayList<>(p.doneDots));
            m.put("phases", new ArrayList<>(p.phases));
            // Live DPH: dots so far over the time since the first dot started (host clock).
            Double dph = null;
            if (p.dotsDone > 0 && p.firstDotStartMs > 0) {
                long end = running ? now : p.lastDotEndMs;
                if (end > p.firstDotStartMs) {
                    dph = p.dotsDone / ((end - p.firstDotStartMs) / 1000.0) * 3600.0;
                }
            }
            m.put("liveDph", dph);
        }
        return m;
    }
}
