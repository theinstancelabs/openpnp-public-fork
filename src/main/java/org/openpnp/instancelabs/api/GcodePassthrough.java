package org.openpnp.instancelabs.api;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.openpnp.instancelabs.dispense.ControllerCommands;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DriverGcodeChannel;
import org.openpnp.instancelabs.dispense.OpenPnpRawMapper;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.Axis;
import org.openpnp.spi.ControllerAxis;
import org.openpnp.spi.Driver;
import org.openpnp.spi.Machine;
import org.pmw.tinylog.Logger;

/**
 * BR-72: the G-code passthrough. The lock holder's lines go through OpenPnP's own driver on its machine thread, so they
 * never interleave with OpenPnP's or the engine's lines and the bench tools no longer open the serial port while
 * OpenPnP runs. One line at a time, each waiting for its {@code ok} as the firmware gives it (moves when queued, M400
 * when done), every reply line returned verbatim. Lines pass {@link PassthroughLines} before any is sent.
 *
 * <p>The holder's moves and G92 lines leave OpenPnP's coordinates stale: once the head is at rest (an M400 after the
 * last move) the batch re-reads the position (M114) and tells OpenPnP; otherwise the next guarded API write, a release
 * or a take-over re-reads it first ({@link #resyncIfStale}). A take-over or a STOP stops a running batch before its next
 * line. Every line and reply is logged with the holder: {@code <config>/instancelabs/gcode-passthrough.log}.</p>
 */
final class GcodePassthrough {
    static final long DEFAULT_TIMEOUT_MS = 60000;
    static final long MIN_TIMEOUT_MS = 1000;
    static final long MAX_TIMEOUT_MS = 300000;
    static final int MAX_LINES = 1000;
    /** The outputs a take-over from an idle holder or a lapse switches off: right valve first, as the Stop sequence. */
    static final List<String> OUTPUTS_OFF = Arrays.asList(ControllerCommands.VALVE_OFF, ControllerCommands.PUMP_OFF,
            ControllerCommands.LEFT_VALVE_OFF, ControllerCommands.LEFT_PUMP_OFF);
    /** How the user reads them (the take-over's confirmation shows these verbatim). */
    static final List<String> OUTPUT_NAMES = Arrays.asList("valve off", "pump off", "N1's valve off", "N1's pump off");
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS",
            Locale.ROOT);

    private final ApiContext ctx;
    private volatile Batch running;
    /** A G0/G1 sent since the last M400's ok: the head may still move. */
    private boolean motionPending;
    /** OpenPnP's coordinates may differ from the controller's (a passthrough move or G92 not re-read yet). */
    private boolean positionStale;
    private final Object logLock = new Object();

    /** One running batch. */
    static final class Batch {
        final MachineLock.Holder holder;
        /** The web STOP's reason once pressed. */
        volatile String stoppedBy;

        Batch(MachineLock.Holder holder) {
            this.holder = holder;
        }
    }

    GcodePassthrough(ApiContext ctx) {
        this.ctx = ctx;
    }

    boolean running() {
        return running != null;
    }

    synchronized boolean motionPending() {
        return motionPending;
    }

    synchronized boolean positionStale() {
        return positionStale;
    }

    /** The web STOP: a running batch sends nothing after its line in flight. Returns whether one ran. */
    boolean abort(String reason) {
        Batch b = running;
        if (b == null) {
            return false;
        }
        b.stoppedBy = reason;
        return true;
    }

    /** OpenPnP stopped the machine: its next home sets every coordinate, and nothing queued still moves. */
    synchronized void machineStopped() {
        motionPending = false;
        positionStale = false;
    }

    // ------------------------------------------------------------------------------------------
    // A batch
    // ------------------------------------------------------------------------------------------

    /**
     * Runs the holder's lines: the route's answer, or an ApiException ({@code lock-lost}: {@link Outcome#lockLost}).
     * {@code what}: the batch's reason, required with BR-97's Z moves while not homed.
     */
    Outcome run(List<String> raw, long timeoutMs, MachineLock.Holder holder, String what) throws Exception {
        if (raw.isEmpty() || raw.size() > MAX_LINES) {
            throw ApiException.badRequest("'lines' must hold 1.." + MAX_LINES + " lines");
        }
        if (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS) {
            throw ApiException.badRequest("'timeoutMs' (per line) must be " + MIN_TIMEOUT_MS + ".." + MAX_TIMEOUT_MS);
        }
        Machine m = ctx.machine();
        GcodeDriver d = driver(m);
        List<PassthroughLines.Line> lines = new ArrayList<>();
        Map<Character, double[]> limits = limits(m, d);
        boolean homed = m.isHomed();
        boolean levelling = false;
        for (int i = 0; i < raw.size(); i++) {
            PassthroughLines.Line l = PassthroughLines.check(raw.get(i), limits, homed);
            if (l.refusal != null) {
                throw refusedLine(i, raw.get(i), l.text, l.refusal);
            }
            lines.add(l);
            levelling = levelling || l.levelZ != null;
        }
        if (levelling && what == null) {
            throw ApiException.badRequest("'what' is required with Z moves while not homed (levelling the see-saw): "
                    + "say why, e.g. \"see-saw levelling, needle tip y 494 to 486\"");
        }
        DispenseService svc = ctx.svcOrNull();
        if (levelling && svc == null) {
            throw ApiException.refused("Z moves while not homed (levelling the see-saw) need the dispense engine, which "
                    + "journals each call and keeps the controller's reset on record: it is not running (see the "
                    + "OpenPnP log)");
        }
        if (!m.isEnabled()) {
            throw ApiException.refused("the machine is not started: press Start (POST /api/machine/enable) first");
        }
        ctx.requireNotSwitchingOff();
        if (svc != null) {
            ctx.requireEngineIdle(svc);
        }
        ctx.requireNoPressureHold();
        ctx.requireMachineIdle();
        Batch b = new Batch(holder);
        synchronized (this) {
            if (running != null) {
                throw ApiException.refused("a G-code batch is already running: wait for it");
            }
            running = b;
        }
        try {
            if (levelling) {
                checkLevelling(m, d, svc, raw, lines, holder, what);
            }
            Future<Outcome> f = m.submit(() -> execute(m, d, b, lines, timeoutMs), null, true);
            long bound = lines.size() * (timeoutMs + 1000L) + 30000L;
            try {
                return f.get(bound, TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException e) {
                b.stoppedBy = "the batch did not end within " + bound / 1000 + " s";
                throw new ApiException(504, "controller-timeout", "the batch did not end within " + bound / 1000
                        + " s: it stops before its next line; press STOP if the machine may still move");
            }
        }
        finally {
            synchronized (this) {
                if (running == b) {
                    running = null;
                }
            }
        }
    }

    private static ApiException refusedLine(int i, String raw, String text, String why) {
        return new ApiException(400, "refused-line", "line " + (i + 1) + " '" + text + "' refused: " + why
                + ". Nothing was sent.").with("index", i).with("line", raw).with("why", why);
    }

    /**
     * BR-97: the batch's Z moves while not homed, walked from the controller's Z (read first: M400, M114) by
     * {@link PassthroughLines#level}, within 32 mm of where the position became unknown
     * (the engine's {@code ControllerReset#levelReference}): a refused one sends nothing, else the call is journaled
     * (events {@code Z_LEVEL}: the holder, the lines, the reason).
     */
    private void checkLevelling(Machine m, GcodeDriver d, DispenseService svc, List<String> raw,
            List<PassthroughLines.Line> lines, MachineLock.Holder holder, String what) throws ApiException {
        resyncNow(m, d); // never OpenPnP's belief: a connect whose read got no answer leaves it as it was (review)
        double z = zNow(m, d);
        double first = z;
        double ref = svc.getControllerReset().levelReference(z);
        StringBuilder sent = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            PassthroughLines.Line l = lines.get(i);
            if ("M410".equals(l.text)) {
                // each step counts from the last target: a move a quick stop ended short makes the next one longer
                throw refusedLine(i, raw.get(i), l.text, "no M410 in a levelling call: it may end a move short, and the "
                        + "next line would travel more than 1 mm; send it in a call of its own (the STOP is POST "
                        + "/api/stop)");
            }
            if (l.levelZ != null) {
                String why = PassthroughLines.level(z, l.levelZ, first, ref);
                if (why != null) {
                    throw refusedLine(i, raw.get(i), l.text, why);
                }
                z = l.levelZ;
            }
            if (!l.empty) {
                sent.append(sent.length() == 0 ? "" : "; ").append(l.text);
            }
        }
        svc.getDb().event(null, "INFO", "Z_LEVEL", "Z levelling while not homed, by " + (holder == null ? "?"
                : holder.who) + " (" + what + "): " + sent);
    }

    /** The controller axis Z's coordinate as OpenPnP knows it (raw mm): the controller's own once re-read. */
    private static double zNow(Machine m, GcodeDriver d) throws ApiException {
        for (Axis a : m.getAxes()) {
            if (a instanceof ReferenceControllerAxis && ((ReferenceControllerAxis) a).getDriver() == d
                    && "Z".equalsIgnoreCase(((ReferenceControllerAxis) a).getLetter())) {
                return ((ReferenceControllerAxis) a).getDriverLengthCoordinate().convertToUnits(LengthUnit.Millimeters)
                        .getValue();
            }
        }
        throw ApiException.refused("OpenPnP's G-code driver has no Z axis: Z cannot be levelled through the "
                + "passthrough");
    }

    /** How a batch ended: {@link #status} "ok", "lock-lost", "stopped", "controller-error" or "controller-timeout". */
    static final class Outcome {
        final String status;
        final List<Map<String, Object>> results;
        final long ms;
        final Integer index;
        final String line;
        final List<String> replies;
        final String message;

        Outcome(String status, List<Map<String, Object>> results, long ms, Integer index, String line,
                List<String> replies, String message) {
            this.status = status;
            this.results = results;
            this.ms = ms;
            this.index = index;
            this.line = line;
            this.replies = replies;
            this.message = message;
        }

        boolean lockLost() {
            return "lock-lost".equals(status);
        }
    }

    /** On OpenPnP's machine thread. Never throws: OpenPnP drops every task queued behind one that does. */
    private Outcome execute(Machine m, GcodeDriver d, Batch b, List<PassthroughLines.Line> lines, long timeoutMs) {
        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> results = new ArrayList<>();
        String who = b.holder == null ? "?" : b.holder.who;
        String confirm = d.getCommand(null, GcodeDriver.CommandType.COMMAND_CONFIRM_REGEX);
        confirm = confirm == null ? "^ok.*" : confirm;
        String error = d.getCommand(null, GcodeDriver.CommandType.COMMAND_ERROR_REGEX);
        for (int i = 0; i < lines.size(); i++) {
            PassthroughLines.Line l = lines.get(i);
            if (l.empty) {
                continue;
            }
            long ls = System.currentTimeMillis();
            List<String> replies = new ArrayList<>();
            try {
                try {
                    d.receiveResponses(); // stale lines, and a stale controller error (not this line's)
                }
                catch (Exception stale) {
                    log(who, "!", "an earlier controller error was cleared: " + stale.getMessage());
                }
                // checked right before the send (#19 review MINOR 5): a take or STOP meanwhile sends nothing more
                String stop = stopReason(m, b);
                if (stop != null) {
                    return new Outcome(stop.startsWith("lock-lost") ? "lock-lost" : "stopped", results, ms(t0), i,
                            l.text, null, stop);
                }
                log(who, ">", l.text);
                d.sendCommand(l.text, timeoutMs);
                DispenseService engine = ctx.svcOrNull();
                if (engine != null) {
                    engine.noteControllerLine(l.text); // BR-02: M18 B switches the screw's motor off, G1 B turns it
                }
                for (GcodeDriver.Line got : d.receiveResponses(confirm, timeoutMs, r -> r)) {
                    if (!got.getLine().matches(confirm)) {
                        replies.add(got.getLine());
                    }
                }
            }
            catch (Exception e) {
                String why = String.valueOf(e.getMessage());
                String stop = stopReason(m, b);
                log(who, "!", why);
                if (stop != null) {
                    return interrupted(stop, results, t0, i, l, replies, ls);
                }
                synchronized (this) {
                    motionPending = motionPending || l.motion; // it may have been queued
                    positionStale = positionStale || l.frame;
                }
                return new Outcome(why.contains("timeout") ? "controller-timeout" : "controller-error", results,
                        ms(t0), i, l.text, replies, why);
            }
            for (String r : replies) {
                log(who, "<", r);
            }
            // taken or stopped while this line ran: its ok may be the STOP's flush (M410), not its end (Infra's
            // lumen-sim e2e: an M400 answered as if the head had arrived)
            String stop = stopReason(m, b);
            if (stop != null) {
                return interrupted(stop, results, t0, i, l, replies, ls);
            }
            synchronized (this) {
                if (l.motion) {
                    motionPending = true;
                }
                if (l.sync) {
                    motionPending = false;
                }
                if (l.frame) {
                    positionStale = true;
                }
            }
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("line", l.text);
            res.put("replies", replies);
            res.put("ms", ms(ls));
            results.add(res);
            if (error != null) {
                for (String r : replies) {
                    if (r.matches(error)) {
                        try {
                            d.receiveResponses(); // the driver noted it: clear it, it is answered here
                        }
                        catch (Exception cleared) {
                            // expected: bailOnError throws the error it clears
                        }
                        return new Outcome("controller-error", results, ms(t0), i, l.text, replies,
                                "the controller answered '" + r + "'");
                    }
                }
            }
        }
        // at rest after the batch's last move: OpenPnP re-reads where the head is
        try {
            boolean resync;
            synchronized (this) {
                resync = positionStale && !motionPending;
            }
            if (resync && stopReason(m, b) == null) {
                resync(m, d, Math.min(timeoutMs, 30000L));
            }
        }
        catch (Exception e) {
            Logger.warn(e, "G-code passthrough: re-reading the position failed");
            log(who, "!", "re-reading the position failed: " + e.getMessage());
        }
        return new Outcome("ok", results, ms(t0), null, null, null, null);
    }

    /**
     * The line in flight when the lock was taken or STOP pressed, marked {@code interrupted}: whatever answered it, the
     * agent must not take it as done (an M400's ok after a quick stop is not the head at its target).
     */
    private Outcome interrupted(String stop, List<Map<String, Object>> results, long t0, int i,
            PassthroughLines.Line l, List<String> replies, long ls) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("line", l.text);
        res.put("replies", replies);
        res.put("ms", ms(ls));
        res.put("interrupted", true);
        results.add(res);
        synchronized (this) {
            motionPending = motionPending || l.motion;
            positionStale = positionStale || l.frame;
        }
        return new Outcome(stop.startsWith("lock-lost") ? "lock-lost" : "stopped", results, ms(t0), i, l.text, replies,
                stop);
    }

    /** Why the batch must send nothing more ("lock-lost: ..." for a revoked holder), or null. */
    private static String stopReason(Machine m, Batch b) {
        if (b.holder != null && b.holder.revoked) {
            return "lock-lost: the lock was taken or ran out";
        }
        if (b.stoppedBy != null) {
            return b.stoppedBy;
        }
        if (!m.isEnabled()) {
            return "the machine was switched off";
        }
        return null;
    }

    private static long ms(long since) {
        return System.currentTimeMillis() - since;
    }

    // ------------------------------------------------------------------------------------------
    // OpenPnP's coordinates and the outputs
    // ------------------------------------------------------------------------------------------

    /**
     * Before a guarded API write, a release or a take-over: when a passthrough move or G92 was not re-read yet, waits
     * for the head to stop (M400) and tells OpenPnP where it is. Nothing while the machine is off or OpenPnP busy (a
     * home sets every coordinate itself).
     */
    void resyncIfStale() throws ApiException {
        Machine m = ctx.configuration.getMachine();
        if (!positionStale() || m == null || !m.isEnabled() || m.isBusy() || running != null) {
            return;
        }
        resyncNow(m, driver(m));
    }

    /** Waits for the head to stop (M400) and tells OpenPnP where it is, on the machine thread. */
    private void resyncNow(Machine m, GcodeDriver d) throws ApiException {
        try {
            Future<String> f = m.submit(() -> {
                try {
                    d.sendCommand("M400", 60000);
                    synchronized (this) {
                        motionPending = false;
                    }
                    resync(m, d, 30000);
                    return null;
                }
                catch (Exception e) {
                    return String.valueOf(e.getMessage());
                }
            }, null, true);
            String failed = f.get(90, TimeUnit.SECONDS);
            if (failed != null) {
                throw ApiException.refused("the position could not be re-read after the G-code passthrough (" + failed
                        + "): press STOP, then Start and Home");
            }
        }
        catch (ApiException e) {
            throw e;
        }
        catch (Exception e) {
            throw ApiException.refused("the position could not be re-read after the G-code passthrough ("
                    + MachineEndpoints.rootMessage(e) + "): press STOP, then Start and Home");
        }
    }

    /** Tells OpenPnP where the controller's axes are (M114), with the head at rest. On the machine thread. */
    private void resync(Machine m, GcodeDriver d, long timeoutMs) throws Exception {
        AxesLocation rep = d.getReportedLocation(timeoutMs);
        Set<Axis> done = new HashSet<>();
        DispenseService svc = ctx.svcOrNull();
        if (svc != null && svc.getProbeNozzle() != null && m instanceof ReferenceMachine) {
            // as the engine's own read-backs (homeSafely, recover): X/Y/Z, the planner's backlash state and B
            OpenPnpRawMapper n1 = OpenPnpRawMapper.forProbeNozzle((ReferenceMachine) m, svc.getProbeNozzle(),
                    svc.getConfig().bAxisName);
            ReferenceControllerAxis[] xyz = n1.xyzAxes();
            double[] pos = new double[3];
            for (int i = 0; i < 3; i++) {
                pos[i] = rep.getCoordinate(xyz[i], LengthUnit.Millimeters);
                done.add(xyz[i]);
            }
            n1.resync(pos, pos, rep.getCoordinate(n1.bAxis()));
            done.add(n1.bAxis());
        }
        for (ControllerAxis a : rep.getControllerAxes()) {
            if (done.contains(a) || !(a instanceof ReferenceControllerAxis) || a.getDriver() != d) {
                continue;
            }
            ReferenceControllerAxis ra = (ReferenceControllerAxis) a;
            if (ra.getType() == Axis.Type.Rotation) {
                ra.setCoordinate(rep.getCoordinate(ra));
                ra.setDriverCoordinate(rep.getCoordinate(ra));
            }
            else {
                Length v = new Length(rep.getCoordinate(ra, LengthUnit.Millimeters), LengthUnit.Millimeters);
                ra.setLengthCoordinate(v);
                ra.setDriverLengthCoordinate(v);
            }
        }
        synchronized (this) {
            positionStale = false;
        }
    }

    /**
     * Take-over from an idle holder, or a lapsed lease: valve, pump, N1 valve and pump off (no motion; the machine stays
     * started and homed), then OpenPnP's coordinates re-read if a move was not. The lines sent, in order (technical).
     */
    List<String> outputsOff(Machine m) throws Exception {
        GcodeDriver d = driver(m);
        Future<Object> f = m.submit(() -> {
            List<String> done = new ArrayList<>();
            try {
                DriverGcodeChannel ch = new DriverGcodeChannel(d);
                for (int i = 0; i < OUTPUTS_OFF.size(); i++) {
                    log("engine", ">", OUTPUTS_OFF.get(i));
                    ch.sendCritical(OUTPUTS_OFF.get(i), 10000);
                    done.add(OUTPUTS_OFF.get(i) + " (" + OUTPUT_NAMES.get(i) + ")");
                }
                boolean stale;
                synchronized (this) {
                    stale = positionStale;
                }
                if (stale) {
                    d.sendCommand("M400", 60000);
                    synchronized (this) {
                        motionPending = false;
                    }
                    resync(m, d, 30000);
                    done.add("M400, M114: OpenPnP re-read the head's position");
                }
                return done;
            }
            catch (Exception e) {
                return new Exception("after " + done + ": " + e.getMessage(), e);
            }
        }, null, true);
        Object r = f.get(90, TimeUnit.SECONDS);
        if (r instanceof Exception) {
            throw (Exception) r;
        }
        @SuppressWarnings("unchecked")
        List<String> done = (List<String>) r;
        return done;
    }

    // ------------------------------------------------------------------------------------------
    // The driver, the limits, the log
    // ------------------------------------------------------------------------------------------

    static GcodeDriver driver(Machine m) throws ApiException {
        for (Driver d : m.getDrivers()) {
            if (d instanceof GcodeDriver) {
                return (GcodeDriver) d;
            }
        }
        throw ApiException.unavailable("the machine has no G-code driver");
    }

    /**
     * The soft limits of the driver's axes, per letter {low, high}, NaN where none is set: X/Y/Z, and a rotation axis
     * with an enabled limit (N1's A: #19 review MAJOR 1). The screw (B) has none.
     */
    static Map<Character, double[]> limits(Machine m, GcodeDriver d) {
        Map<Character, double[]> out = new LinkedHashMap<>();
        for (Axis a : m.getAxes()) {
            if (!(a instanceof ReferenceControllerAxis) || ((ReferenceControllerAxis) a).getDriver() != d) {
                continue;
            }
            ReferenceControllerAxis ra = (ReferenceControllerAxis) a;
            String letter = ra.getLetter();
            boolean linear = ra.getType() == Axis.Type.X || ra.getType() == Axis.Type.Y || ra.getType() == Axis.Type.Z;
            boolean limited = ra.isSoftLimitLowEnabled() || ra.isSoftLimitHighEnabled();
            if (letter == null || letter.length() != 1 || !linear && !(ra.getType() == Axis.Type.Rotation && limited)) {
                continue;
            }
            // a rotation's limits are degrees: its Length value as is
            double lo = !ra.isSoftLimitLowEnabled() ? Double.NaN : linear ? ra.getSoftLimitLow().convertToUnits(
                    LengthUnit.Millimeters).getValue() : ra.getSoftLimitLow().getValue();
            double hi = !ra.isSoftLimitHighEnabled() ? Double.NaN : linear ? ra.getSoftLimitHigh().convertToUnits(
                    LengthUnit.Millimeters).getValue() : ra.getSoftLimitHigh().getValue();
            char c = Character.toUpperCase(letter.charAt(0));
            double[] prev = out.get(c);
            // two axes on one letter (never on this machine): the narrower band
            out.put(c, prev == null ? new double[] {lo, hi} : new double[] {max(prev[0], lo), min(prev[1], hi)});
        }
        return out;
    }

    private static double max(double a, double b) {
        return Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.max(a, b);
    }

    private static double min(double a, double b) {
        return Double.isNaN(a) ? b : Double.isNaN(b) ? a : Math.min(a, b);
    }

    /** {@code time holder > line} / {@code < reply} / {@code ! note}, as the bench tools' serial.log. */
    void log(String who, String dir, String text) {
        File dir0 = ctx.configuration.getConfigurationDirectory();
        if (dir0 == null) {
            return;
        }
        File f = new File(new File(dir0, "instancelabs"), "gcode-passthrough.log");
        synchronized (logLock) {
            try {
                f.getParentFile().mkdirs();
                try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                    w.write(LOG_TIME.format(LocalDateTime.now()) + " " + who + " " + dir + " " + text + "\n");
                }
            }
            catch (Exception e) {
                Logger.warn("G-code passthrough: could not write {}: {}", f, e.getMessage());
            }
        }
    }
}
