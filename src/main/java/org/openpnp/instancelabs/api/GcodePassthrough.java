package org.openpnp.instancelabs.api;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
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

import org.openpnp.instancelabs.dispense.CollisionCheck;
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

    /** Tests only (BR-97 review): runs between a batch's checks and its submit. */
    static volatile Runnable submitHook;

    private final ApiContext ctx;
    private volatile Batch running;
    /** A G0/G1 sent since the last M400's ok: the head may still move. */
    private boolean motionPending;
    /** OpenPnP's coordinates may differ from the controller's (a passthrough move or G92 not re-read yet). */
    private boolean positionStale;
    /**
     * BR-98: raw X, Y, Z where the passthrough's acknowledged moves leave the head, kept while OpenPnP's position is
     * stale (the next batch is walked from here); null when not known (a line cut short, a quick stop).
     */
    private double[] commanded;
    /**
     * OpenPnP's raw X, Y, Z when {@link #commanded} was last walked: another value at the next batch means OpenPnP or a
     * script moved the head meanwhile, so where it is is not known (review 2 MINOR 2). shortcut: a move that ends back
     * on the same raw X, Y, Z is not seen; upgrade to the driver's own count of motion lines when stage 2 hooks it.
     */
    private double[] openpnpAt;
    private final Object logLock = new Object();

    /** One running batch. */
    static final class Batch {
        final MachineLock.Holder holder;
        /** OpenPnP's homed state its lines were checked under (BR-97: unhomed, Z moves are levelling moves). */
        final boolean homed;
        /** The web STOP's reason once pressed. */
        volatile String stoppedBy;
        /** BR-97: the controller's Z its levelling lines were walked from (null: no levelling). */
        Double levelFromZ;

        Batch(MachineLock.Holder holder, boolean homed) {
            this.holder = holder;
            this.homed = homed;
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
        commanded = null;
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
        Batch b = new Batch(holder, homed);
        synchronized (this) {
            if (running != null) {
                throw ApiException.refused("a G-code batch is already running: wait for it");
            }
            running = b;
        }
        try {
            if (levelling) {
                b.levelFromZ = checkLevelling(m, d, svc, raw, lines, holder, what);
            }
            Runnable hook = submitHook;
            if (hook != null) {
                hook.run();
            }
            Future<Outcome> f = m.submit(() -> execute(m, d, b, lines, timeoutMs), null, true);
            long bound = lines.size() * (timeoutMs + 1000L) + 30000L;
            Outcome o;
            try {
                o = f.get(bound, TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException e) {
                b.stoppedBy = "the batch did not end within " + bound / 1000 + " s";
                throw new ApiException(504, "controller-timeout", "the batch did not end within " + bound / 1000
                        + " s: it stops before its next line; press STOP if the machine may still move");
            }
            if (o.refusal != null) {
                throw o.refusal;
            }
            return o;
        }
        finally {
            synchronized (this) {
                if (running == b) {
                    running = null;
                }
            }
        }
    }

    /**
     * BR-98: the batch's G0/G1 moves walked from where the head is (OpenPnP's raw position, or where the last batch
     * left it while that is not re-read yet) and proven before any line is sent; a blocked batch is refused whole. Raw
     * G-code has no semantics: a tip may go the model's contact depth into a measured touchable top, as in lumen-sim.
     * Where the head is may be unknown (the last batch cut short, or a quick stop earlier in this batch): then no line
     * moves X, Y or Z, whatever its legs (review MAJOR 1). Made on the machine thread before the first line, as jog and
     * move-to make theirs (review MINOR b).
     */
    private void prove(Machine m, Batch b, List<PassthroughLines.Line> lines) throws ApiException {
        // BR-97's see-saw levelling (Z alone while not homed) stays outside the check (design §3, §4): its own walk
        if (!ctx.bed.active() || b.levelFromZ != null) {
            synchronized (this) {
                commanded = null; // no walk: the next one starts from a re-read position (a model put in place, a home)
            }
            return;
        }
        BedGuard.Start s = ctx.bed.start(m);
        double[] now = {s.x, s.y, s.z}, at;
        boolean moved;
        synchronized (this) {
            moved = positionStale && commanded != null && !Arrays.equals(now, openpnpAt);
            at = !positionStale ? now : commanded == null || moved ? null : commanded.clone();
        }
        BedGuard.Start unknown = moved ? s.unknown("OpenPnP or a script moved the head after the last G-code batch, "
                + "before where that batch left it was read, so where the head is is not known", "send M400 alone "
                + "first: OpenPnP then reads where the head is") : at == null ? s.at(null) : null;
        List<CollisionCheck.Leg> legs = new ArrayList<>();
        double[] p = at;
        for (int i = 0; i < lines.size(); i++) {
            PassthroughLines.Line l = lines.get(i);
            if (l.movesHead() && unknown != null) {
                ctx.bed.prove("gcode", null, unknown, legs, CollisionCheck.Options.contact(null), false); // refuses
            }
            else if (l.motion && unknown == null) {
                double[] q = l.applyTo(p);
                if (!Arrays.equals(p, q)) {
                    legs.add(new CollisionCheck.Leg("line " + (i + 1) + ": " + l.text, p[0], p[1], p[2], q[0], q[1],
                            q[2]));
                }
                p = q;
            }
            if ("M410".equals(l.text)) {
                unknown = s.unknown("line " + (i + 1) + "'s M410 may stop the head short of its target", "send the "
                        + "lines after the M410 in a batch of their own: OpenPnP reads where the head is when this one "
                        + "ends");
            }
        }
        if (!legs.isEmpty()) {
            ctx.bed.prove("gcode", null, s.at(at), legs, CollisionCheck.Options.contact(null), false);
        }
        synchronized (this) {
            if (at != null) {
                commanded = at; // execute follows the batch's moves from here
                openpnpAt = now;
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
     * (the engine's {@code ControllerReset#levelReference}, on record: a reference it cannot write sends nothing, 409):
     * a refused one sends nothing, else the call is journaled first (events {@code Z_LEVEL}: the holder, the lines, the
     * reason); a failed write sends nothing either (409). Returns the Z the lines were walked from.
     */
    private double checkLevelling(Machine m, GcodeDriver d, DispenseService svc, List<String> raw,
            List<PassthroughLines.Line> lines, MachineLock.Holder holder, String what) throws ApiException {
        resyncNow(m, d); // never OpenPnP's belief: a connect whose read got no answer leaves it as it was (review)
        double z = zNow(m, d);
        double first = z;
        double ref;
        try {
            ref = svc.getControllerReset().levelReference(z);
        }
        catch (IllegalStateException e) {
            throw ApiException.refused(e.getMessage() + ", so nothing was sent: the 32 mm count goes on record first, "
                    + "to outlive an OpenPnP restart");
        }
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
        try {
            svc.getDb().insertEvent(null, "INFO", "Z_LEVEL", "Z levelling while not homed, by " + (holder == null ? "?"
                    : holder.name()) + " (" + what + "): " + sent, System.currentTimeMillis());
        }
        catch (SQLException e) {
            throw ApiException.refused("the levelling call could not be journaled (events Z_LEVEL: " + e.getMessage()
                    + "), so nothing was sent: every Z move while not homed goes on record first");
        }
        return first;
    }

    /** The controller axis Z's coordinate as OpenPnP knows it (raw mm): the controller's own once re-read. */
    private static double zNow(Machine m, GcodeDriver d) throws ApiException {
        return zAxis(m, d).getDriverLengthCoordinate().convertToUnits(LengthUnit.Millimeters).getValue();
    }

    private static ReferenceControllerAxis zAxis(Machine m, GcodeDriver d) throws ApiException {
        for (Axis a : m.getAxes()) {
            if (a instanceof ReferenceControllerAxis && ((ReferenceControllerAxis) a).getDriver() == d
                    && "Z".equalsIgnoreCase(((ReferenceControllerAxis) a).getLetter())) {
                return (ReferenceControllerAxis) a;
            }
        }
        throw ApiException.refused("OpenPnP's G-code driver has no Z axis: Z cannot be levelled through the "
                + "passthrough");
    }

    /**
     * BR-97 review 3: a home whose G28 ran but that ended unhomed, or a power cycle, between the checks and the first
     * line leaves OpenPnP's homed state as it was; the controller's Z (M400, M114) must still be the one the lines were
     * walked from. On the machine thread; a failed read is a reason too (nothing was sent).
     */
    private static String levelStartMoved(Machine m, GcodeDriver d, double from, long timeoutMs) {
        double z;
        try {
            d.sendCommand("M400", timeoutMs);
            z = d.getReportedLocation(timeoutMs).getCoordinate(zAxis(m, d), LengthUnit.Millimeters);
            d.receiveResponses(); // its answers are not the first line's replies
        }
        catch (Exception e) {
            return "Z could not be re-read before the first line (" + MachineEndpoints.rootMessage(e) + "): none of "
                    + "its lines was sent; press STOP if the machine may still move";
        }
        return Math.abs(z - from) <= 0.01 ? null : String.format(Locale.ROOT, "Z moved after the lines were checked "
                + "(raw Z %.2f then, %.2f now: a home, a jog or a power cycle ran meanwhile): walk again from the Z "
                + "now", from, z);
    }

    /**
     * How a batch ended: {@link #status} "ok", "lock-lost", "stopped", "controller-error", "controller-timeout", or
     * "refused" before its first line ({@link #refusal}, which {@link #run} throws).
     */
    static final class Outcome {
        final String status;
        final List<Map<String, Object>> results;
        final long ms;
        final Integer index;
        final String line;
        final List<String> replies;
        final String message;
        final ApiException refusal;

        Outcome(String status, List<Map<String, Object>> results, long ms, Integer index, String line,
                List<String> replies, String message) {
            this(status, results, ms, index, line, replies, message, null);
        }

        /** Refused before its first line: nothing was sent. */
        Outcome(ApiException refusal) {
            this("refused", new ArrayList<>(), 0, null, null, null, refusal.getMessage(), refusal);
        }

        private Outcome(String status, List<Map<String, Object>> results, long ms, Integer index, String line,
                List<String> replies, String message, ApiException refusal) {
            this.status = status;
            this.results = results;
            this.ms = ms;
            this.index = index;
            this.line = line;
            this.replies = replies;
            this.message = message;
            this.refusal = refusal;
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
        String queued = stopReason(m, b);
        if (queued != null) {
            // stopped while still queued: nothing to prove, nothing sent (review 2 NIT)
            return new Outcome(queued.startsWith("lock-lost") ? "lock-lost" : "stopped", results, ms(t0), 0,
                    lines.get(0).text, null, queued);
        }
        try {
            prove(m, b, lines);
        }
        catch (ApiException e) {
            return new Outcome(e);
        }
        catch (Exception e) {
            Logger.warn(e, "G-code passthrough: the collision check failed");
            return new Outcome(ApiException.refused("the collision check failed (" + e.getMessage() + "): nothing was "
                    + "sent"));
        }
        String confirm = d.getCommand(null, GcodeDriver.CommandType.COMMAND_CONFIRM_REGEX);
        confirm = confirm == null ? "^ok.*" : confirm;
        String error = d.getCommand(null, GcodeDriver.CommandType.COMMAND_ERROR_REGEX);
        boolean zChecked = b.levelFromZ == null;
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
                if (stop == null && !zChecked) {
                    zChecked = true;
                    String moved = levelStartMoved(m, d, b.levelFromZ, timeoutMs);
                    stop = stopReason(m, b); // a STOP or a take during the re-read's round trips names itself
                    if (stop == null) {
                        stop = moved;
                    }
                }
                if (stop != null) {
                    return new Outcome(stop.startsWith("lock-lost") ? "lock-lost" : "stopped", results, ms(t0), i,
                            l.text, null, stop);
                }
                d.sendCommand(l.text, timeoutMs); // nothing between the last check and the write
                log(who, ">", l.text);
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
                    // an M410 whose ok is lost may still have stopped the head short (review 2 MINOR 3)
                    commanded = l.motion || "M410".equals(l.text) ? null : commanded;
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
                    commanded = commanded == null ? null : l.applyTo(commanded);
                }
                if (l.sync) {
                    motionPending = false;
                }
                if (l.frame) {
                    positionStale = true;
                }
                if ("M410".equals(l.text)) {
                    commanded = null; // a quick stop leaves the head short of its target
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
                        synchronized (this) {
                            commanded = l.motion ? null : commanded; // an answered error: maybe not queued
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
            commanded = l.motion || "M410".equals(l.text) ? null : commanded;
        }
        return new Outcome(stop.startsWith("lock-lost") ? "lock-lost" : "stopped", results, ms(t0), i, l.text, replies,
                stop);
    }

    /** Why the batch must send nothing more ("lock-lost: ..." for a revoked holder), or null. */
    private String stopReason(Machine m, Batch b) {
        if (b.holder != null && b.holder.revoked) {
            return "lock-lost: the lock was taken or ran out";
        }
        if (b.stoppedBy != null) {
            return b.stoppedBy;
        }
        if (ctx.switchOff.inProgress()) {
            // OpenPnP's own Stop, from its announce: its M410 may cut a move short, so no line goes after it
            return "OpenPnP is switching the machine off";
        }
        if (!m.isEnabled()) {
            return "the machine was switched off";
        }
        // a home (or a Stop and Start) between the checks and this line: they no longer hold (BR-97 review)
        if (m.isHomed() != b.homed) {
            return "OpenPnP " + (b.homed ? "is no longer homed" : "homed") + " after the lines were checked: send them "
                    + "again";
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
            commanded = null;
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
