package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

/** Outcome and timing of one dispense run (board, calibration ladder, touch-off or shots). */
public class RunResult {
    public enum Status {
        RUNNING, OK, ABORTED, ERROR, REFUSED
    }

    public static class DotTiming {
        public final int dotIndex;
        public final String padId;
        public final double rotationDeg;
        /** Host time the dot's first line was sent (ms). */
        public final long startMs;
        /** Host time the dot's last line was acknowledged (ms); queued, not necessarily executed. */
        public final long endMs;

        public DotTiming(int dotIndex, String padId, double rotationDeg, long startMs, long endMs) {
            this.dotIndex = dotIndex;
            this.padId = padId;
            this.rotationDeg = rotationDeg;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    public String runId;
    /** board | dot-ladder | touch-off | shots | board-registration | surface-probe | z-check */
    public String kind;
    public String planId;
    public String boardName;
    public long calibrationId;
    public Status status = Status.RUNNING;
    public String message;
    public long startedAtMs;
    public long endedAtMs;
    public int dotsPlanned;
    public int dotsDone;
    /** Host time the first board dot started. */
    public long firstDotStartMs;
    /**
     * Host time the last board dot ended: the completion of the M400 that follows it (true
     * controller completion), or its last acknowledgement if the run stopped earlier.
     */
    public long lastDotEndMs;
    public boolean keepaliveActive;
    public int linesSent;
    public int keepalivePings;
    public final List<DotTiming> dotTimings = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final List<String> streamedLines = new ArrayList<>();
    public InterlockReport interlocks;
    /** Board runs: the profile used, the clean-pad check, purge dots laid, screw state at the start. */
    public DispenseProfile profile;
    public PadCleanReport cleanCheck;
    public int purgeDots;
    public Long bCountAtStart;
    public Double workStartAngleDeg;
    /** Register board: the plan made at the end of the task (the page and other clients follow it). */
    public String nextPlanId;
    /** Board runs: whether the run homed first (homeBeforeRun), and its Z check after the home. */
    public Boolean homedFirst;
    public ZCheck.Result zCheck;
    /** Board runs: pads the operator chose to skip for this run (pad ids), shown in Records. */
    public final List<String> skippedPads = new ArrayList<>();

    /** Seconds from the first dot's start to the last dot's end. */
    public Double motionSeconds() {
        if (firstDotStartMs <= 0 || lastDotEndMs <= firstDotStartMs) {
            return null;
        }
        return (lastDotEndMs - firstDotStartMs) / 1000.0;
    }

    public double totalSeconds() {
        long end = endedAtMs > 0 ? endedAtMs : System.currentTimeMillis();
        return Math.max(0, end - startedAtMs) / 1000.0;
    }

    /** DPH = dots / (last end - first start) * 3600. */
    public Double dphMotion() {
        Double s = motionSeconds();
        return s == null || dotsDone == 0 ? null : dotsDone / s * 3600.0;
    }

    /** DPH including all overhead (motion wait, pressurise, prime, test dot, re-sync). */
    public Double dphOverall() {
        double s = totalSeconds();
        return s <= 0 || dotsDone == 0 ? null : dotsDone / s * 3600.0;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "%s %s %s: %d/%d dots, DPH motion %s, overall %s%s", kind,
                runId, status, dotsDone, dotsPlanned, fmt(dphMotion()), fmt(dphOverall()),
                message == null ? "" : " (" + message + ")");
    }

    private static String fmt(Double d) {
        return d == null ? "n/a" : String.format(java.util.Locale.ROOT, "%.0f", d);
    }
}
