package org.openpnp.instancelabs.dispense;

import java.util.List;
import java.util.function.LongSupplier;

import org.pmw.tinylog.Logger;

/**
 * Streams a {@link GcodeProgram} line by line through a {@link GcodeChannel}, i.e. through the
 * driver's flow control, without any per-dot M400. Interleaves host-watchdog pings, enforces I7
 * (maximum continuous pneumatic on-time) and runs the I1 shutdown sequence (M410, valve OFF, pump
 * OFF, M18 B) on any error, abort request or Stop.
 */
public class DispenseExecutor {
    public static class AbortedException extends Exception {
        private static final long serialVersionUID = 1L;

        public AbortedException(String message) {
            super(message);
        }
    }

    /**
     * What a gated line belongs to: {@link #PROBE} lines pass {@link ControllerCommands#checkProbe}
     * (the N1 vacuum and VAC1 lines), every other scope {@link ControllerCommands#check}.
     */
    public enum Scope {
        DISPENSE, HOME, PROBE
    }

    private final DispenseConfig cfg;
    private final GcodeChannel channel;
    private final LongSupplier clock;
    private final String letterB;
    private volatile String abortReason;
    /** Guards the step "abort checked -> line handed to the channel" (see {@link #awaitLineWritten}). */
    private final Object gate = new Object();
    private boolean inFlight;
    private long inFlightSinceNanos;
    private boolean armed;
    private long lastPingMs;
    private long pressureOnMs = -1;

    public DispenseExecutor(DispenseConfig cfg, GcodeChannel channel, String letterB, LongSupplier clock) {
        this.cfg = cfg;
        this.channel = channel;
        this.letterB = letterB;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** Thread-safe; the streaming loop stops before its next line and shuts the pneumatics off. */
    public void requestAbort(String reason) {
        abortReason = reason == null ? "abort requested" : reason;
    }

    public boolean isAbortRequested() {
        return abortReason != null;
    }

    /** Why the abort was requested ("STOP pressed in the web UI"), or null. */
    public String getAbortReason() {
        return abortReason;
    }

    /**
     * For the Stop path, after {@link #requestAbort}: waits until a line that passed its abort check
     * just before the abort has been handed to the channel, so that OpenPnP's DISABLE_COMMAND (M410,
     * valve, pump, ...) cannot be overtaken by an engine motion line. The wait ends as soon as the
     * line is acknowledged, or {@code graceMs} after the line was started: by then it has been
     * written (the write follows the check within microseconds), and a line still waiting for its
     * "ok" (G4, M400, a full planner) is already on the wire, before the Stop lines.
     *
     * @return true if no engine line was between its abort check and its acknowledgement at return
     */
    public boolean awaitLineWritten(long graceMs) {
        synchronized (gate) {
            long deadline = (inFlight ? inFlightSinceNanos : System.nanoTime()) + graceMs * 1_000_000L;
            while (inFlight) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    gate.wait(Math.max(1, left / 1_000_000L));
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Sends one program or keepalive line: command guard, then the abort flag is checked again
     * immediately before the line is handed to the channel (a Stop that arrived during the previous
     * line's or a ping's round trip must not let another motion line out).
     */
    private void sendLine(String text, long timeoutMs) throws Exception {
        ControllerCommands.requireAllowed(text);
        synchronized (gate) {
            if (abortReason != null) {
                throw new AbortedException(abortReason);
            }
            inFlight = true;
            inFlightSinceNanos = System.nanoTime();
        }
        try {
            channel.send(text, timeoutMs);
        }
        finally {
            synchronized (gate) {
                inFlight = false;
                gate.notifyAll();
            }
        }
    }

    /**
     * One line outside a program (homing's Z lift, the N1 probe), on the same path as a streamed
     * line: the allowlist of its scope, then the abort flag under the gate, marked in flight for
     * {@link #awaitLineWritten} (a Stop cannot be overtaken by it). Recorded in the result.
     *
     * @param timeoutMs > 0: the caller passes the command timeout plus the move's own time, so its
     *        own limits (the probe's vacuum on-time) can act between lines
     */
    public void sendGated(Scope scope, String line, long timeoutMs, RunResult r) throws Exception {
        gated(scope, line, timeoutMs, r, false);
    }

    /** As {@link #sendGated}, returning every response line up to the confirmation (M114, M261). */
    public List<String> queryGated(Scope scope, String line, long timeoutMs, RunResult r) throws Exception {
        return gated(scope, line, timeoutMs, r, true);
    }

    private List<String> gated(Scope scope, String line, long timeoutMs, RunResult r, boolean collect)
            throws Exception {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("a gated line needs a timeout > 0 ms: '" + line + "'");
        }
        boolean probe = scope == Scope.PROBE;
        if (probe) {
            ControllerCommands.requireProbeAllowed(line);
        }
        else {
            ControllerCommands.requireAllowed(line);
        }
        synchronized (gate) {
            if (abortReason != null) {
                throw new AbortedException(abortReason);
            }
            inFlight = true;
            inFlightSinceNanos = System.nanoTime();
        }
        List<String> reply = null;
        try {
            if (collect) {
                reply = probe ? channel.collectProbe(line, timeoutMs) : channel.sendAndCollect(line, timeoutMs);
            }
            else if (probe) {
                channel.sendProbe(line, timeoutMs);
            }
            else {
                channel.send(line, timeoutMs);
            }
        }
        finally {
            synchronized (gate) {
                inFlight = false;
                gate.notifyAll();
            }
        }
        if (r != null) {
            r.linesSent++;
            r.streamedLines.add(line);
        }
        return reply;
    }

    /**
     * Streams the program. On any exception the shutdown sequence is sent (best effort) and the
     * exception is rethrown; the result carries what was achieved.
     */
    public void stream(GcodeProgram prog, RunResult result, DispenseListener listener) throws Exception {
        long dotStart = 0;
        boolean dotEndedSinceSync = false;
        int total = result.dotsPlanned;
        try {
            for (GcodeProgram.Line line : prog.lines) {
                if (line.isComment()) {
                    continue;
                }
                long now = clock.getAsLong();
                if (abortReason != null) {
                    throw new AbortedException(abortReason);
                }
                if (pressureOnMs >= 0 && now - pressureOnMs > cfg.maxContinuousOnTimeS * 1000.0) {
                    throw new AbortedException(String.format(java.util.Locale.ROOT,
                            "I7: pneumatics on for %.1f s, limit %.0f s", (now - pressureOnMs) / 1000.0,
                            cfg.maxContinuousOnTimeS));
                }
                if (armed && now - lastPingMs >= cfg.keepaliveIntervalMs) {
                    sendLine(ControllerCommands.KEEPALIVE_PING, cfg.commandTimeoutMs);
                    result.keepalivePings++;
                    result.linesSent++;
                    lastPingMs = clock.getAsLong();
                }
                if (line.has(GcodeProgram.Marker.DOT_BEGIN)) {
                    dotStart = clock.getAsLong();
                    if (line.dotIndex >= 0 && result.firstDotStartMs == 0) {
                        result.firstDotStartMs = dotStart;
                    }
                }
                long timeout = line.has(GcodeProgram.Marker.SYNC) ? -1 : cfg.commandTimeoutMs;
                sendLine(line.text, timeout);
                result.linesSent++;
                result.streamedLines.add(line.text);
                now = clock.getAsLong();
                if (line.has(GcodeProgram.Marker.PUMP_ON)) {
                    pressureOnMs = now;
                }
                if (line.has(GcodeProgram.Marker.PUMP_OFF)) {
                    pressureOnMs = -1;
                }
                if (line.has(GcodeProgram.Marker.KEEPALIVE_ARM)) {
                    armed = true;
                    lastPingMs = now;
                }
                if (line.has(GcodeProgram.Marker.KEEPALIVE_DISARM)) {
                    armed = false;
                }
                if (line.has(GcodeProgram.Marker.SYNC) && dotEndedSinceSync) {
                    // The controller has now physically finished the last streamed dot.
                    result.lastDotEndMs = now;
                    dotEndedSinceSync = false;
                }
                if (line.has(GcodeProgram.Marker.DOT_END) && line.dotIndex >= 0) {
                    RunResult.DotTiming t = new RunResult.DotTiming(line.dotIndex, line.padId,
                            line.rotationDeg, dotStart, now);
                    result.dotTimings.add(t);
                    result.dotsDone++;
                    result.lastDotEndMs = now;
                    dotEndedSinceSync = true;
                    if (listener != null) {
                        try {
                            listener.onDot(result, t, result.dotsDone, total);
                        }
                        catch (Exception e) {
                            Logger.warn(e, "dispense listener failed");
                        }
                    }
                }
            }
        }
        catch (Exception e) {
            shutdown(result);
            throw e;
        }
        catch (Error e) {
            // I1 on anything that ends the stream (rc1 review: an OutOfMemoryError, or a listener's Error, left the
            // pump and the valve on: no M410, no vents, no I7 limit after it)
            shutdown(result);
            throw e;
        }
    }

    /**
     * I1: quick-stop and pneumatics off, in the plan §4.4 order. Every line is attempted even if
     * an earlier one fails, and each goes through {@link GcodeChannel#sendCritical}, so a stale
     * controller error cannot swallow M410 or the valve OFF.
     */
    public void shutdown(RunResult result) {
        List<String> lines = GcodeStreamBuilder.abortLines(cfg, armed, letterB);
        for (String l : lines) {
            try {
                String note = channel.sendCritical(l, cfg.commandTimeoutMs);
                if (note != null) {
                    result.warnings.add(note);
                }
                result.linesSent++;
                result.streamedLines.add(l);
            }
            catch (Exception e) {
                result.warnings.add("shutdown line '" + l + "' failed: " + e.getMessage());
                Logger.error(e, "dispense shutdown line {} failed", l);
            }
        }
        armed = false;
        pressureOnMs = -1;
    }
}
