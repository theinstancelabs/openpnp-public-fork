package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openpnp.instancelabs.dispense.ControllerCommands;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseListener;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DriverGcodeChannel;
import org.openpnp.instancelabs.dispense.GcodeChannel;
import org.openpnp.instancelabs.dispense.OpenPnpRawMapper;
import org.openpnp.instancelabs.dispense.RunResult;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.pmw.tinylog.Logger;

/**
 * Manual pressure hold for the Dispense tab: switch the barrel pressure on and leave it on (e.g. to
 * read the regulator gauge), then off. Order per plan §4.5: on = pump, wait, valve, settle; off =
 * valve (barrel vents), then pump. The G-code is fixed ({@link ControllerCommands}: full-on
 * {@code S255} only, never a setting). The switch-on is cancellable between lines: a Stop or Pressure
 * off is seen before the valve is switched on, and no lock is held across the waits.
 *
 * <ul>
 * <li>I7: switched off automatically after {@code maxContinuousOnTimeS}.</li>
 * <li>I1: vented when OpenPnP stops/disables the machine, and by the web STOP.</li>
 * <li>Host watchdog: armed and pinged (on the machine thread) when the firmware reports the
 * capability, as the engine does. If the machine thread is busy for longer than the watchdog
 * window, the firmware vents on its own (fail-safe).</li>
 * </ul>
 *
 * <p>Prime, test dot, board runs and calibrations are engine runs that pressurise and vent on their
 * own; the API refuses them while a hold is on, so on-time accounting (I7) stays with one owner.</p>
 */
final class PressureHold implements DispenseListener {
    enum State {
        OFF, STARTING, ON, STOPPING,
        /** The valve OFF could not be sent: the hold is NOT off. Pressure off / STOP try again. */
        VENT_FAILED
    }

    /** Host-side wait step while switching on (the switch-on is cancellable between steps). */
    private static final long WAIT_STEP_MS = 20;

    private final ApiContext ctx;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "il-api-pressure-timer");
        t.setDaemon(true);
        return t;
    });
    /**
     * Serialises the G-code lines of this class, one line at a time: it is never held across a
     * wait, so a Stop that needs it waits for at most one line's round trip.
     */
    private final Object io = new Object();
    private final AtomicBoolean pingQueued = new AtomicBoolean();

    private volatile State state = State.OFF;
    private volatile long onSinceMs;
    private volatile long autoOffAtMs;
    private volatile boolean keepaliveArmed;
    private volatile long lastPingMs;
    private volatile int pings;
    private volatile String lastEvent = "pressure off";
    private volatile long lastEventAtMs = System.currentTimeMillis();
    private volatile String lastError;
    private DispenseConfig cfg;
    private GcodeChannel channel;
    private ScheduledFuture<?> autoOff;
    private ScheduledFuture<?> pinger;
    private Machine listenedMachine;

    private final MachineListener machineListener = new MachineListener.Adapter() {
        @Override
        public void machineAboutToBeDisabled(Machine machine, String reason) {
            // Still connected here: cancel a switch-on in progress and vent from this (stopping)
            // thread, like OpenPnP's DISABLE_COMMAND. Waits for at most one line in flight.
            if (state != State.OFF) {
                requestStop();
                ventNow("OpenPnP stop/disable: " + reason);
            }
        }

        @Override
        public void machineDisabled(Machine machine, String reason) {
            if (state != State.OFF) {
                markOff("machine disabled (" + reason + "); the controller link is closed");
            }
        }
    };

    PressureHold(ApiContext ctx) {
        this.ctx = ctx;
    }

    boolean isActive() {
        return state != State.OFF;
    }

    State getState() {
        return state;
    }

    /**
     * Switches the pressure on (pump, wait, valve, settle) on the machine thread. Waits for the
     * sequence to be acknowledged; returns early with state STARTING if the machine thread is busy.
     */
    Map<String, Object> start(DispenseService svc) throws Exception {
        Machine m = ctx.machine();
        synchronized (this) {
            if (state != State.OFF) {
                throw ApiException.refused("pressure is already " + state.name().toLowerCase());
            }
            state = State.STARTING;
            lastError = null;
        }
        attach(m);
        DispenseConfig c = svc.getConfig();
        Future<String> f;
        try {
            f = m.submit(() -> doStart(m, svc, c));
        }
        catch (RuntimeException e) {
            markOff("could not queue the pressure start: " + e.getMessage());
            throw e;
        }
        long timeout = c.pumpToValveSettleMs + c.valveSettleMs + 4L * c.commandTimeoutMs + 5000;
        String refused;
        try {
            refused = f.get(timeout, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e) {
            Map<String, Object> s = snapshot();
            s.put("note", "queued behind other machine work; the pressure switches on when OpenPnP is free");
            return s;
        }
        catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw ApiException.refused("could not switch the pressure on: " + cause.getMessage())
                    .with("pressure", snapshot());
        }
        if (refused != null) {
            throw ApiException.refused("could not switch the pressure on: " + refused).with("pressure", snapshot());
        }
        return snapshot();
    }

    /**
     * Machine thread. One line at a time, and every line only while the state is still STARTING:
     * a Stop or Pressure off during the switch-on is seen before the next line (in particular
     * before valve ON), and the waits are host-side, so no lock is held across them. Returns why it
     * did not start before any G-code was sent, else null: those refusals return rather than throw,
     * because OpenPnP drops every task queued behind one that throws, and an engine task dropped so
     * would never end (its busy flag would stay set).
     */
    private String doStart(Machine m, DispenseService svc, DispenseConfig c) throws Exception {
        if (state != State.STARTING) {
            String why = lastEvent;
            markOff("pressure start cancelled before any G-code was sent (" + why + ")");
            return "cancelled before it started (" + why + ")";
        }
        // On the machine thread, serialised with the engine's tasks: a task queued at the same time
        // (its busy flag is set before it is queued) refuses or runs, never both with the hold.
        if (svc.isBusy()) {
            markOff("pressure start refused: a dispense task is starting or running");
            return "a dispense task is starting or running: wait for it or press STOP";
        }
        boolean completed;
        try {
            m.getMotionPlanner().waitForCompletion(null, CompletionType.WaitForStillstand);
            OpenPnpRawMapper mapper = new OpenPnpRawMapper((ReferenceMachine) m, svc.getDispenserNozzle(),
                    c.bAxisName);
            GcodeChannel ch = new DriverGcodeChannel(mapper.getDriver());
            synchronized (this) {
                cfg = c;
                channel = ch; // from here on a Stop vents through this channel
            }
            completed = switchOn(ch, c);
        }
        catch (Exception e) {
            lastError = e.getMessage();
            ventNow("pressure start failed: " + e.getMessage());
            throw e;
        }
        if (completed) {
            synchronized (this) {
                completed = state == State.STARTING;
                if (completed) {
                    state = State.ON;
                    long limitMs = Math.round(c.maxContinuousOnTimeS * 1000);
                    autoOffAtMs = onSinceMs + limitMs;
                    autoOff = timer.schedule(() -> stopQuietly("I7: on-time limit of " + c.maxContinuousOnTimeS
                            + " s reached"), Math.max(0, autoOffAtMs - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                    if (keepaliveArmed) {
                        pinger = timer.scheduleAtFixedRate(this::ping, c.keepaliveIntervalMs, c.keepaliveIntervalMs,
                                TimeUnit.MILLISECONDS);
                    }
                }
            }
        }
        if (!completed) {
            if (state != State.OFF) {
                ventNow("stop requested while the pressure was switching on");
            }
            throw new Exception("stopped while switching on");
        }
        event("pressure on: " + ControllerCommands.PUMP_ON + ", wait " + c.pumpToValveSettleMs + " ms, "
                + ControllerCommands.VALVE_ON + ", settle " + c.valveSettleMs + " ms"
                + (keepaliveArmed ? "; host watchdog armed" : ""));
        return null;
    }

    /** The switch-on lines and waits; false as soon as a stop was requested. */
    private boolean switchOn(GcodeChannel ch, DispenseConfig c) throws Exception {
        boolean ka;
        synchronized (io) {
            if (state != State.STARTING) {
                return false;
            }
            ka = c.keepaliveEnabled && hasWatchdog(ch, c);
        }
        if (ka) {
            if (!sendWhileStarting(ch, ControllerCommands.keepaliveArm(c.keepaliveWindowMs), c)) {
                return false;
            }
            keepaliveArmed = true;
            lastPingMs = System.currentTimeMillis();
        }
        if (!sendWhileStarting(ch, ControllerCommands.PUMP_ON, c)) {
            return false;
        }
        onSinceMs = System.currentTimeMillis(); // I7 counts from the pump
        if (!waitWhileStarting(ch, c, c.pumpToValveSettleMs)) {
            return false;
        }
        if (!sendWhileStarting(ch, ControllerCommands.VALVE_ON, c)) {
            return false;
        }
        return waitWhileStarting(ch, c, c.valveSettleMs);
    }

    /** Sends one line under {@code io} if the switch-on has not been cancelled. */
    private boolean sendWhileStarting(GcodeChannel ch, String line, DispenseConfig c) throws Exception {
        synchronized (io) {
            if (state != State.STARTING) {
                return false;
            }
            ch.send(line, c.commandTimeoutMs);
            return true;
        }
    }

    /** Host-side wait (no lock held), keepalive pings when armed; false when cancelled. */
    private boolean waitWhileStarting(GcodeChannel ch, DispenseConfig c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (state == State.STARTING) {
            long now = System.currentTimeMillis();
            if (now >= end) {
                return true;
            }
            if (keepaliveArmed && now - lastPingMs >= c.keepaliveIntervalMs) {
                if (!sendWhileStarting(ch, ControllerCommands.KEEPALIVE_PING, c)) {
                    return false;
                }
                lastPingMs = System.currentTimeMillis();
                pings++;
                continue;
            }
            Thread.sleep(Math.min(WAIT_STEP_MS, end - now));
        }
        return false;
    }

    /**
     * M115: does the firmware have the host watchdog? Only a timeout means "no". A controller error
     * (e.g. 'start' after a reset) refuses the hold instead of being swallowed.
     */
    private static boolean hasWatchdog(GcodeChannel ch, DispenseConfig c) throws Exception {
        try {
            for (String line : ch.sendAndCollect(ControllerCommands.CAPABILITY_QUERY, c.commandTimeoutMs)) {
                if (line.contains(ControllerCommands.WATCHDOG_CAPABILITY)) {
                    return true;
                }
            }
            return false;
        }
        catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("timeout waiting for response")) {
                Logger.warn(e, "pressure hold: capability query timed out (no host watchdog assumed)");
                return false;
            }
            throw new Exception("the controller reported an error before the pressure was switched on ("
                    + e.getMessage() + "); nothing was switched on", e);
        }
    }

    /** Keepalive ping on the machine thread (never two queued at once). */
    private void ping() {
        Machine m = listenedMachine;
        if (state != State.ON || !keepaliveArmed || m == null || !pingQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            m.submit(() -> {
                try {
                    synchronized (io) {
                        GcodeChannel ch;
                        DispenseConfig c;
                        synchronized (this) {
                            ch = channel;
                            c = cfg;
                        }
                        if (state == State.ON && keepaliveArmed && ch != null) {
                            ch.send(ControllerCommands.KEEPALIVE_PING, c.commandTimeoutMs);
                            lastPingMs = System.currentTimeMillis();
                            pings++;
                        }
                    }
                }
                finally {
                    pingQueued.set(false);
                }
                return null;
            }, null, true);
        }
        catch (RuntimeException e) {
            pingQueued.set(false);
        }
    }

    /** Marks a stop as requested (a switch-on in progress sees it before its next line). */
    private synchronized void requestStop() {
        if (state != State.OFF && state != State.VENT_FAILED) {
            state = State.STOPPING;
        }
        cancelTimers();
    }

    /** Switches the pressure off (valve, then pump). Safe to call when already off. */
    Map<String, Object> stop(String reason) {
        synchronized (this) {
            if (state == State.OFF) {
                Map<String, Object> s = snapshot();
                s.put("note", "pressure was already off");
                return s;
            }
            if (state == State.STARTING) {
                event("stop requested while switching on: " + reason);
            }
        }
        requestStop();
        vent(reason);
        return snapshot();
    }

    private void stopQuietly(String reason) {
        try {
            stop(reason);
        }
        catch (Exception e) {
            Logger.error(e, "pressure hold: automatic stop failed");
        }
    }

    /**
     * Emergency path for the web STOP: cancels a switch-on in progress and vents now (from the
     * calling thread when the machine thread is busy, otherwise through it).
     */
    void emergencyStop(String reason) {
        if (state == State.OFF) {
            return;
        }
        if (state == State.STARTING) {
            event("STOP while switching on: " + reason);
        }
        requestStop();
        vent(reason);
    }

    private void vent(String reason) {
        Machine m = listenedMachine;
        if (m != null && !m.isTask(Thread.currentThread()) && !m.isBusy()) {
            try {
                m.submit(() -> {
                    ventNow(reason);
                    return null;
                }, null, true).get(30, TimeUnit.SECONDS);
                return;
            }
            catch (Exception e) {
                Logger.warn(e, "pressure hold: vent on the machine thread failed; sending directly");
            }
        }
        // Machine thread busy (or this is it): send directly, as OpenPnP's emergency Stop does.
        ventNow(reason);
    }

    /**
     * Valve OFF, pump OFF, then the watchdog disarm; any thread. Each line goes through
     * {@link GcodeChannel#sendCritical}, so a controller error that is still pending cannot
     * swallow it. The hold is marked OFF only once the valve OFF has been sent; otherwise it stays
     * VENT_FAILED, the watchdog stays armed and its pings stop (an armed firmware watchdog then
     * vents by itself).
     *
     * @return true if the valve OFF was sent (or nothing had been switched on)
     */
    private boolean ventNow(String reason) {
        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean valveOff;
        boolean ka;
        synchronized (io) {
            GcodeChannel ch;
            DispenseConfig c;
            synchronized (this) {
                ch = channel;
                c = cfg;
                ka = keepaliveArmed;
            }
            valveOff = ch == null || c == null; // nothing was sent yet: nothing to vent
            if (!valveOff) {
                valveOff = sendCritical(ch, ControllerCommands.VALVE_OFF, c, errors, notes);
                sendCritical(ch, ControllerCommands.PUMP_OFF, c, errors, notes);
                if (ka && valveOff) {
                    sendCritical(ch, ControllerCommands.KEEPALIVE_DISARM, c, errors, notes);
                }
            }
        }
        if (!errors.isEmpty()) {
            lastError = String.join("; ", errors);
        }
        if (!valveOff) {
            synchronized (this) {
                state = State.VENT_FAILED;
                cancelTimers();
            }
            event("VALVE OFF FAILED (" + reason + "): " + errors + ". The barrel may still be pressurised: press "
                    + "Pressure off or STOP again" + (ka ? "; the firmware host watchdog vents on its own when its "
                            + "window ends (pings stopped)" : "; if that fails, switch the machine off"));
            return false;
        }
        markOff("pressure off (valve, then pump): " + reason + (notes.isEmpty() ? "" : " - " + notes)
                + (errors.isEmpty() ? "" : " - FAILED lines: " + errors));
        return true;
    }

    private static boolean sendCritical(GcodeChannel ch, String line, DispenseConfig c, List<String> errors,
            List<String> notes) {
        try {
            String note = ch.sendCritical(line, c.commandTimeoutMs);
            if (note != null) {
                notes.add(note);
            }
            return true;
        }
        catch (Exception e) {
            errors.add(line + ": " + e.getMessage());
            Logger.error(e, "pressure hold: '{}' failed", line);
            return false;
        }
    }

    private void markOff(String why) {
        synchronized (this) {
            state = State.OFF;
            cancelTimers();
            channel = null;
            keepaliveArmed = false;
            onSinceMs = 0;
            autoOffAtMs = 0;
        }
        event(why);
    }

    private void cancelTimers() {
        if (autoOff != null) {
            autoOff.cancel(false);
            autoOff = null;
        }
        if (pinger != null) {
            pinger.cancel(false);
            pinger = null;
        }
    }

    private void event(String text) {
        lastEvent = text;
        lastEventAtMs = System.currentTimeMillis();
        Logger.info("Instance Labs pressure hold: {}", text);
        DispenseService svc = DispenseService.get();
        if (svc != null) {
            svc.getDb().event(null, "INFO", "PRESSURE_HOLD", text);
        }
    }

    private synchronized void attach(Machine m) {
        if (listenedMachine != m) {
            if (listenedMachine != null) {
                listenedMachine.removeListener(machineListener);
            }
            m.addListener(machineListener);
            listenedMachine = m;
        }
    }

    // --- DispenseListener: an engine run always ends with valve + pump off -------------------

    @Override
    public void onFinished(RunResult run) {
        // A task refused before it sent a line (on the machine thread, because this hold is on) left the
        // pneumatics as they were: the barrel is still held under pressure.
        if (state == State.ON && run.linesSent > 0) {
            markOff("the " + run.kind + " run switched the pneumatics off at its end");
        }
    }

    Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        State s = state;
        m.put("state", s.name().toLowerCase());
        m.put("on", s == State.ON);
        m.put("onSinceMs", onSinceMs == 0 ? null : onSinceMs);
        m.put("autoOffAtMs", autoOffAtMs == 0 ? null : autoOffAtMs);
        m.put("remainingS", autoOffAtMs == 0 ? null : Math.max(0, (autoOffAtMs - now) / 1000.0));
        m.put("keepaliveArmed", keepaliveArmed);
        m.put("lastPingAgeMs", keepaliveArmed ? now - lastPingMs : null);
        m.put("pings", pings);
        m.put("lastEvent", lastEvent);
        m.put("lastEventAtMs", lastEventAtMs);
        m.put("lastError", lastError);
        return m;
    }

    void shutdown() {
        if (state != State.OFF) {
            emergencyStop("web API shutting down");
        }
        Machine m = listenedMachine;
        if (m != null) {
            m.removeListener(machineListener);
        }
        timer.shutdownNow();
    }
}
