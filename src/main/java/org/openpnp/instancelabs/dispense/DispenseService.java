package org.openpnp.instancelabs.dispense;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.openpnp.ConfigurationListener;
import org.openpnp.instancelabs.board.EdtRunner;
import org.openpnp.instancelabs.padprofile.DoseModel;
import org.openpnp.instancelabs.padprofile.LadderStats;
import org.openpnp.instancelabs.padprofile.Limits;
import org.openpnp.instancelabs.padprofile.Texts;
import org.openpnp.instancelabs.padprofile.Tip;
import org.openpnp.instancelabs.sim.SimGuard;
import org.openpnp.instancelabs.vision.BareSpotCheck;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.instancelabs.vision.FiducialLocator;
import org.openpnp.instancelabs.vision.SpotDot;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Driver;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.NozzleTip;
import org.openpnp.util.Utils2D;
import org.pmw.tinylog.Logger;

import com.google.gson.Gson;

/**
 * Entry point of the Instance Labs dispense engine. One instance per OpenPnP configuration,
 * created when the configuration has loaded ({@link #install(Configuration)} is called from
 * Main). Null-safe: if the dispenser nozzle is missing the service exists but reports
 * {@link #isAvailable()} false and refuses to run.
 *
 * <p>All machine work runs on OpenPnP's machine thread (Machine.submit) and returns a Future. Only
 * one dispense task runs at a time. See docs/instancelabs/DISPENSE_API.md.</p>
 */
public final class DispenseService {
    private static volatile DispenseService instance;
    /** Longest the Stop path waits for an engine line that passed its abort check to be written. */
    static final long STOP_WRITE_GRACE_MS = 50;
    /** The vacuum probe nozzle (name or id). */
    public static final String PROBE_NOZZLE = "N1";
    /**
     * A board top Z further than this from the needle-frame surface is refused at planning: boards and
     * the blank sit on the same bed (the bench has them about 1 mm apart), while a height taken with
     * the needle instead of N1 is 2.59 mm off.
     */
    public static final double MAX_BOARD_Z_FROM_ANCHOR_MM = 2.0;
    /** Time estimates of Register board's steps (s): one fiducial (search and centring), one N1 touch. */
    public static final double FIDUCIAL_ESTIMATE_S = 5, PROBE_POINT_ESTIMATE_S = 17;
    /** The refusal of a task queued before a STOP (§7.8). */
    public static final String STOPPED_SINCE_QUEUED = "STOP was pressed after this step was queued: nothing was sent";

    /** A run refused before any G-code that moves or switches anything was sent. */
    public static class RunRefusedException extends Exception {
        private static final long serialVersionUID = 1L;

        public RunRefusedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A configuration edit applied to the live configuration under the service's lock ({@link #updateConfigAtomically}). */
    public interface ConfigEdit {
        /**
         * The new configuration, made from {@code live} (a copy of the live configuration: the edit's comparisons of
         * protected settings run against it); throw to refuse.
         */
        DispenseConfig apply(DispenseConfig live) throws Exception;
    }

    /**
     * What recording a needle Z frame or offset answers (tip setups design §22.3): the frame, the offset and two
     * warnings; the values are saved either way (they measure what is on).
     */
    public static class NeedleFrameResult {
        /** The needle Z frame now (null when only the offset was set). */
        public DispenseConfig.NeedleAnchor anchor;
        public double offsetMm;
        /**
         * "The {S}'s tip touches {d} mm higher than when it was on on {date}: is the {S} on the dispenser?" when the
         * frame moved more than needleOffsetMaxDriftMm from the setup's previous one, else null.
         */
        public String question;
        /** The I4 offset rule's warning (a frame too close to N1's to tell a height taken with the needle), or null. */
        public String warning;
    }

    private final Configuration configuration;
    private final File configDir;
    private final DispenseDb db;
    private volatile DispenseConfig cfg;
    private volatile Nozzle nozzle;
    private volatile String unavailableReason;
    private final AtomicBoolean bZeroed = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);
    /**
     * The engine task queued last: its run, its Future, when it was queued, and whether its body began (taken by the
     * machine thread, or ended by {@link #releaseDroppedTask}: whichever comes first).
     */
    private volatile Queued queued;
    private volatile DispenseExecutor activeExecutor;
    private volatile RunResult activeRun;
    private volatile RunResult lastRun;
    private final List<DispenseListener> listeners = new CopyOnWriteArrayList<>();
    private volatile BarrelPressureSensor pressureSensor;
    private volatile DotImager dotImagerOverride;
    private MachineListener machineListener;
    private MachineListener identityListener;
    /**
     * Whether the controller of the current connection said it is OpenPnP's simulator (M115 at the
     * connect or of the last task), null when not asked yet on this connection.
     */
    private volatile Boolean connectionSimulated;
    /** Connects, disables and homes: whether a task must home first, and Z data older than the last home. */
    private final HomeTracker homeTracker;
    /** The last good open-air N1 baseline (the probe's part-on-N1 check across tasks). */
    private final VacuumProber.BaselineMemory baselineMemory = new VacuumProber.BaselineMemory();
    /** The API's manual pressure hold: engine tasks refuse to start while it is on (machine thread). */
    private volatile BooleanSupplier pressureHoldActive = () -> false;
    /** Set by {@link #abort}: stops a camera-only clean-pad check (it has no executor). */
    private volatile boolean cameraTourAbort;
    /**
     * Counts every {@link #abort}: a task queued before a STOP refuses at its start, so a step a job
     * queued in the gap between two steps never pressurises (profile calibration design §7.8).
     */
    private final AtomicLong stopEpoch = new AtomicLong();
    /** Engine tasks started (an undo of a mount is refused once one has run since, §22.3). */
    private final AtomicLong tasksStarted = new AtomicLong();
    /** The camera that photographs the tip from below in tests ({@link #setTipCamera}); null = OpenPnP's. */
    private volatile TipCamera tipCameraOverride;
    /** The pictures (tip setups design §22.4). */
    private final PhotoStore photoStore;
    /** The mounted setup with its dot table and the identity it was made for ({@link #mountedTip}). */
    private volatile TipCache tipCache;
    /** The last mount of another setup, kept for its undo (§22.3), or null. */
    private volatile UndoSnapshot undo;
    /** The token the last needle change wrote and the one it replaced {new, old} (old null: none), for the undo. */
    private volatile Long[] lastChange;

    /** An undo window: how long after a mount of another setup it may be undone (§22.3). */
    public static final long UNDO_MS = 2 * 60 * 1000L;
    /** The events code of the tip setup facts (§22.3, §22.4): needle changes, mounts, landing, trims, pictures. */
    public static final String TIP_EVENT = "TIP";

    /**
     * Tests only (tip setups design §22.8, the race tests): called at named points of a path ("switch": a mount holding
     * the lock and the busy flag; "dose-blocks": a step on the machine thread before its checks) so a latch can pause
     * it there. Null in every other use.
     */
    static volatile java.util.function.Consumer<String> testHook;

    private static void hook(String point) {
        java.util.function.Consumer<String> h = testHook;
        if (h != null) {
            h.accept(point);
        }
    }

    /** The mounted tip with its dot table and the identity (setup, recipe, paste, records) it was made for. */
    private static final class TipCache {
        final String key;
        final Tip tip;

        TipCache(String key, Tip tip) {
            this.key = key;
            this.tip = tip;
        }
    }

    /** The configuration before a mount of another setup, and what has to stay untouched for its undo. */
    private static final class UndoSnapshot {
        DispenseConfig before;
        DispenseConfig after;
        String fromKey;
        String toKey;
        long atMs;
        long tasksAtMount;
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    /**
     * Registers the service to start once the configuration is complete. Safe to call more than
     * once; never throws (a failure only makes the service unavailable).
     */
    public static void install(Configuration configuration) {
        configuration.addListener(new ConfigurationListener.Adapter() {
            @Override
            public void configurationComplete(Configuration c) {
                try {
                    start(c);
                }
                catch (Throwable t) {
                    Logger.error(t, "Instance Labs dispense service failed to start");
                }
            }
        });
    }

    /** Starts (or restarts) the service for a loaded configuration. */
    public static synchronized DispenseService start(Configuration configuration) throws SQLException, IOException {
        if (instance != null) {
            instance.shutdown();
        }
        instance = new DispenseService(configuration);
        instance.askIdentityOnConnect();
        Logger.info("Instance Labs dispense service started: {}", instance.isAvailable() ? "available"
                : "unavailable (" + instance.unavailableReason + ")");
        return instance;
    }

    /** The running service, or null before the configuration has loaded. */
    public static DispenseService get() {
        return instance;
    }

    private DispenseService(Configuration configuration) throws SQLException, IOException {
        this.configuration = configuration;
        this.configDir = configuration.getConfigurationDirectory();
        this.cfg = DispenseConfig.load(configDir);
        this.db = DispenseDb.open(configDir);
        resolveNozzle();
        try {
            reconcileTip();
        }
        catch (SQLException | IOException | RuntimeException e) {
            // the settings file is the truth: the engine starts with it as it is, and the next start tries again
            Logger.warn(e, "dispense: the tip setup reconcile at start failed");
            db.event(null, "WARN", TIP_EVENT, "the tip setup reconcile at start failed (" + e.getMessage() + "): the "
                    + "settings file's tip is used as it is");
            if (!Tip.SEED_KEY.equals(cfg.tip.key)) {
                cfg.tip.model = null; // only the seeded setup has the bench model (§22.11 item 10)
            }
        }
        this.photoStore = new PhotoStore(PhotoStore.folderFor(configDir), db);
        try {
            photoStore.sweep(System.currentTimeMillis());
        }
        catch (Exception e) {
            Logger.warn(e, "dispense: the picture sweep at start failed");
        }
        Machine machine = configuration.getMachine();
        homeTracker = new HomeTracker(null, machine != null && machine.isHomed());
        if (machine != null) {
            machine.addListener(homeTracker);
            machineListener = new MachineListener.Adapter() {
                @Override
                public void machineHomed(Machine m, boolean isHomed) {
                    bZeroed.set(false);
                }

                @Override
                public void machineEnabled(Machine m) {
                    bZeroed.set(false);
                }

                @Override
                public void machineAboutToBeDisabled(Machine m, String reason) {
                    // Runs on the Stop thread right before the driver's DISABLE_COMMAND (M410, valve,
                    // pump, ...): stop the stream, then let a line that already passed its abort check
                    // reach the wire first, so no engine motion line can follow the M410.
                    abort("machine disabled: " + reason);
                    DispenseExecutor e = activeExecutor;
                    if (e != null) {
                        e.awaitLineWritten(STOP_WRITE_GRACE_MS);
                    }
                }

                @Override
                public void machineDisabled(Machine m, String reason) {
                    bZeroed.set(false);
                    abort("machine disabled: " + reason);
                }
            };
            machine.addListener(machineListener);
        }
    }

    public synchronized void shutdown() {
        abort("service shutdown");
        Machine machine = configuration.getMachine();
        if (machine != null && machineListener != null) {
            machine.removeListener(machineListener);
            machine.removeListener(homeTracker);
        }
        if (machine != null && identityListener != null) {
            machine.removeListener(identityListener);
            SimGuard.askAtConnect(machine, null);
        }
        try {
            db.close();
        }
        catch (SQLException e) {
            Logger.warn(e);
        }
    }

    /**
     * Asks the controller who it is (M115) right after every connect, so whether it is the simulator
     * is known from the start of a connection, not only after its first engine task: the API's checks
     * of records made on the simulator then hold right after a Start ({@link #isSimulatorConfirmed}).
     * SimGuard asks, and notes the answer, right after it forgot the last connection's answer.
     */
    private void askIdentityOnConnect() {
        Machine machine = configuration.getMachine();
        if (machine == null) {
            return;
        }
        SimGuard.askAtConnect(machine, () -> {
            connectionSimulated = null;
            if (!(machine instanceof ReferenceMachine) || nozzle == null) {
                return null;
            }
            OpenPnpRawMapper mapper = new OpenPnpRawMapper((ReferenceMachine) machine, nozzle, cfg.bAxisName);
            List<String> m115 = new DriverGcodeChannel(mapper.getDriver())
                    .sendAndCollect(ControllerCommands.CAPABILITY_QUERY, cfg.commandTimeoutMs);
            connectionSimulated = SimGuard.isSimulatorIdentity(m115);
            return m115;
        });
        identityListener = new MachineListener.Adapter() {
            @Override
            public void machineEnableFailed(Machine m, String reason) {
                connectionSimulated = null;
            }

            @Override
            public void machineAboutToBeDisabled(Machine m, String reason) {
                connectionSimulated = null;
            }

            @Override
            public void machineDisabled(Machine m, String reason) {
                connectionSimulated = null;
            }
        };
        machine.addListener(identityListener);
    }

    /** The controller's M115 answer on the current connection. */
    private void noteIdentity(List<String> m115) {
        SimGuard.noteIdentity(configuration.getMachine(), m115);
        connectionSimulated = SimGuard.isSimulatorIdentity(m115);
    }

    /**
     * Finds the dispenser nozzle. Each field is written once, the reason before a null nozzle: every config update
     * resolves again, and a transient null made concurrent readers (the status's interlocks, availability) fail with a
     * NullPointerException or "unavailable (null)".
     */
    private void resolveNozzle() {
        Machine machine = configuration.getMachine();
        Nozzle found = null;
        String why = null;
        if (!(machine instanceof ReferenceMachine)) {
            why = "machine is not a ReferenceMachine";
        }
        else {
            for (Head h : machine.getHeads()) {
                for (Nozzle n : h.getNozzles()) {
                    if (cfg.nozzle.equals(n.getId())) {
                        found = n;
                    }
                }
            }
            if (found == null) {
                for (Head h : machine.getHeads()) {
                    for (Nozzle n : h.getNozzles()) {
                        if (cfg.nozzle.equals(n.getName())) {
                            found = n;
                        }
                    }
                }
            }
            if (found == null) {
                why = "no dispenser nozzle '" + cfg.nozzle + "' in the machine";
            }
        }
        if (found == null) {
            unavailableReason = why;
            nozzle = null;
        }
        else {
            nozzle = found;
            unavailableReason = null;
        }
    }

    /**
     * The start-up reconcile of the mounted tip setup (tip setups design §22.1), in this order: (1) a {@code cfg.tip}
     * key that names no setup gets a setup made from {@code cfg.tip} (form and taper unknown); (2) {@code cfg.tip}
     * is compared with its setup's newest version on the ID, the OD and the smallest dot only (every config saved
     * before tip setups names the 22G "22G tip", {@link Tip#OLD_SEED_NAME}): when they differ a version with the
     * config's values is appended; a seed row still so named gets {@link Tip#SEED_NAME}; (3) {@code cfg.tip} is
     * refreshed from the setup, its name included; (4) an empty {@code tip_mounts} gets a {@code seed} row, one whose
     * newest row names another setup a {@code reconcile} row; (5) at that first start, a landing kept from before the
     * update when the needle change was finished. The config is the truth: it is written first, then the rows.
     */
    private void reconcileTip() throws SQLException, IOException {
        DispenseConfig c = cfg.copy();
        boolean changed = DispenseConfig.isBeforeTipSetups(readConfigFile());
        String key = c.tip.key;
        TipSetup s = db.currentTipSetup(key);
        if (s == null) {
            TipSetup n = new TipSetup();
            n.key = key;
            n.name = freeName(c.tip.name, key);
            n.dispenser = c.tip.dispenser == null ? TipSetup.DEFAULT_DISPENSER : c.tip.dispenser;
            n.idMm = c.tip.idMm;
            n.odMm = c.tip.odMm;
            n.odSource = TipSetup.OD_ASSUMED;
            n.form = TipSetup.FORM_UNKNOWN;
            n.taper = TipSetup.TAPER_UNKNOWN;
            n.minDotSteps = c.tip.minDotSteps;
            n.notes = "Made from the settings file, which named this tip: fill in its form and taper.";
            n.why = "the settings file names this tip";
            n.operator = "reconcile";
            s = db.insertTipSetupVersion(n);
            db.event(null, "WARN", TIP_EVENT, "the settings file names the tip " + key + ", which no tip setup had: "
                    + "made the setup " + s.name + " of it (form and taper to fill in)");
        }
        else if (!sameNumber(s.idMm, c.tip.idMm) || !sameNumber(s.odMm, c.tip.odMm) || s.minDotSteps != c.tip.minDotSteps) {
            TipSetup n = s.copy();
            n.idMm = c.tip.idMm;
            n.odMm = c.tip.odMm;
            n.odSource = n.odSource == null ? TipSetup.OD_ASSUMED : n.odSource;
            n.minDotSteps = c.tip.minDotSteps;
            n.why = "from the settings file at the update";
            n.operator = "reconcile";
            s = db.insertTipSetupVersion(n);
            db.event(null, "INFO", TIP_EVENT, "the settings file's tip differs from the " + s.name + "'s version "
                    + (s.version - 1) + ": version " + s.version + " takes its ID, OD and smallest dot");
        }
        if (Tip.SEED_KEY.equals(s.key) && Tip.OLD_SEED_NAME.equals(s.name) && ("seed".equals(s.operator)
                || "reconcile".equals(s.operator))) {
            // a seed written before it was named "22G bent taper" (§22.1), never renamed by the operator
            TipSetup n = s.copy();
            n.name = freeName(Tip.SEED_NAME, s.key);
            n.why = "the seed's name at the update";
            n.operator = "reconcile";
            s = db.insertTipSetupVersion(n);
        }
        TipSpec spec = s.toSpec();
        if (!new com.google.gson.Gson().toJson(spec).equals(new com.google.gson.Gson().toJson(c.tip))) {
            c.tip = spec;
            changed = true;
        }
        List<TipSetups.Mount> last = db.tipMounts(1);
        boolean first = last.isEmpty();
        double[] ho = headOffsetNow();
        if (first && c.needleLanding == null && (c.needleChangedAtMs == null || c.isNeedleTouchedOff())) {
            DispenseConfig.NeedleLanding l = new DispenseConfig.NeedleLanding();
            l.setAtMs = c.needleZOffsetSetAtMs != null ? c.needleZOffsetSetAtMs : System.currentTimeMillis();
            l.headOffsetXMm = ho == null ? null : ho[0];
            l.headOffsetYMm = ho == null ? null : ho[1];
            l.note = "kept from before the update (boards ran with it)";
            c.needleLanding = l;
            if (c.needleXyTrim.dxMm != 0 || c.needleXyTrim.dyMm != 0) {
                c.needleXyTrim.headOffsetXMm = l.headOffsetXMm;
                c.needleXyTrim.headOffsetYMm = l.headOffsetYMm;
            }
            changed = true;
        }
        if (changed) {
            List<String> problems = c.validate();
            if (!problems.isEmpty()) {
                Logger.warn("dispense: the tip setup reconcile left the settings invalid ({}): kept as they were",
                        problems);
            }
            else {
                c.save(configDir);
                cfg = c;
            }
        }
        if (first || !last.get(0).setupKey.equals(cfg.tip.key)) {
            TipSetups.Mount m = new TipSetups.Mount();
            m.kind = first ? TipSetups.Mount.SEED : TipSetups.Mount.RECONCILE;
            m.setupKey = cfg.tip.key;
            m.setupVersion = cfg.tip.setupVersion;
            m.fromSetupKey = first ? null : last.get(0).setupKey;
            m.needleChangedAtMs = cfg.needleChangedAtMs;
            m.headOffsetXMm = ho == null ? null : ho[0];
            m.headOffsetYMm = ho == null ? null : ho[1];
            m.landingJson = new com.google.gson.Gson().toJson(cfg.needleLanding);
            m.why = first ? "in use before the update" : "the settings file names the " + cfg.tip.name
                    + ", the last mount another tip";
            m.operator = "reconcile";
            db.insertTipMount(m);
        }
    }

    /** The settings file as it is on disk, or "" when there is none. */
    private String readConfigFile() {
        try {
            File f = DispenseConfig.fileFor(configDir);
            return f.isFile() ? new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8) : "";
        }
        catch (IOException e) {
            return "";
        }
    }

    /** A setup name not taken by another live setup: {@code name} (at most 40 characters), else "{name} ({key})". */
    private String freeName(String name, String key) throws SQLException {
        String n = name == null || name.trim().isEmpty() ? key : name.trim();
        if (n.length() > TipSetup.MAX_NAME) {
            n = n.substring(0, TipSetup.MAX_NAME - 1).trim() + "…";
        }
        for (TipSetup o : db.currentTipSetups(false)) {
            if (o.name.equalsIgnoreCase(n)) {
                return (n + " (" + key + ")").length() <= TipSetup.MAX_NAME ? n + " (" + key + ")" : key;
            }
        }
        return n;
    }

    private static boolean sameNumber(Double a, double b) {
        return a != null && Math.abs(a - b) < 1e-9;
    }

    // ------------------------------------------------------------------------------------------
    // State and configuration
    // ------------------------------------------------------------------------------------------

    public boolean isAvailable() {
        return nozzle != null;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    public Nozzle getDispenserNozzle() {
        return nozzle;
    }

    public DispenseDb getDb() {
        return db;
    }

    public File getConfigDir() {
        return configDir;
    }

    /** A copy of the current configuration. */
    public DispenseConfig getConfig() {
        return cfg.copy();
    }

    /** The mounted tip setup's key, without copying the configuration (the SIM reads it per line). */
    public String mountedKey() {
        return cfg.tip.key;
    }

    /**
     * Validates, saves to dispense-config.json and applies a new configuration. A changed start
     * angle or needle XY trim is stamped with the time it was set (calibration values). A
     * configuration made from a copy taken before a mount or a needle change is refused
     * ({@link #refuseStaleTip}).
     */
    public synchronized void updateConfig(DispenseConfig newCfg) throws IOException {
        List<String> problems = newCfg.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid dispense config: " + problems);
        }
        refuseStaleTip(newCfg);
        DispenseConfig c = newCfg.copy();
        long now = System.currentTimeMillis();
        if (cfg != null && c.profile.startAngleDeg != cfg.profile.startAngleDeg) {
            c.profile.startAngleSetAtMs = now;
        }
        if (cfg != null && (c.needleXyTrim.dxMm != cfg.needleXyTrim.dxMm || c.needleXyTrim.dyMm != cfg.needleXyTrim.dyMm)) {
            c.needleXyTrim.setAtMs = now;
            // §22.3: the trim belongs to the N2 head offset it was set under
            double[] ho = headOffsetNow();
            c.needleXyTrim.headOffsetXMm = ho == null ? null : ho[0];
            c.needleXyTrim.headOffsetYMm = ho == null ? null : ho[1];
        }
        writeConfig(c);
        db.event(null, "INFO", "CONFIG", "configuration updated");
    }

    /**
     * Tip setups (§22.1, §22.3): only a mount, its undo and a needle change write the mounted tip and the needle
     * token. A configuration made from a copy taken before one of them (a settings form, or a request that copied the
     * configuration outside this lock) would put back the tip that came off, or make the frame of the needle before
     * count again: it is refused (409 tip-changed or needle-changed). A tip key changed with the same token is no
     * stale copy but a tip changed by hand: 400, a tip is mounted.
     */
    private void refuseStaleTip(DispenseConfig next) {
        DispenseConfig live = cfg;
        if (live == null) {
            return;
        }
        Long token = live.needleChangedAtMs;
        boolean older = token != null && (next.needleChangedAtMs == null || next.needleChangedAtMs < token);
        if (!live.tip.key.equals(next.tip.key)) {
            if (older) {
                throw tipChanged();
            }
            throw new IllegalArgumentException("The tip is the mounted tip setup: change it in Setup › Dispenser & tip.");
        }
        if (older) {
            throw new EngineRefusal(409, "needle-changed", "The needle was changed at " + ZCheck.clock(token)
                    + " while this was open: reload.").with("needleChangedAtMs", token);
        }
    }

    /** Saves and applies a configuration as it is (no checks, no stamps): the caller validated it. */
    private void writeConfig(DispenseConfig c) throws IOException {
        c.save(configDir);
        cfg = c;
        resolveNozzle();
        tipCache = null;
    }

    /**
     * Applies an edit to the live configuration under this service's lock (tip setups design §22.1): the edit gets a
     * copy of the live configuration, and its result is validated and written as {@link #updateConfig} does, so
     * nothing a mount, a needle change or a run's reservation wrote meanwhile is put back. Returns the new config.
     */
    public synchronized DispenseConfig updateConfigAtomically(ConfigEdit edit) throws Exception {
        DispenseConfig next = edit.apply(cfg.copy());
        if (next != null) {
            updateConfig(next);
        }
        return cfg.copy();
    }

    /**
     * I11: sets (angle != null) or confirms the screw start angle for the current controller
     * power-up (the B step count restarts at power-up).
     */
    public synchronized DispenseProfile setStartAngle(Double angleDeg, String note) throws IOException {
        DispenseConfig c = cfg.copy();
        if (angleDeg != null) {
            c.profile.startAngleDeg = angleDeg;
        }
        c.profile.startAngleSetAtMs = System.currentTimeMillis();
        updateConfig(c);
        db.event(null, "INFO", "I11", String.format(java.util.Locale.ROOT, "screw start angle %s %.1f deg%s",
                angleDeg == null ? "confirmed" : "set to", c.profile.startAngleDeg, note == null ? "" : ": " + note));
        return c.profile.copy();
    }

    /** The purge row was wiped: all its spots are free again. */
    public synchronized void resetPurgeRow() throws IOException {
        DispenseConfig c = cfg.copy();
        c.purgeRow.usedSpots = 0;
        c.purgeRow.resetAtMs = System.currentTimeMillis();
        updateConfig(c);
        db.event(null, "INFO", "I9", "purge row reset (" + c.purgeRow.spots + " spots free)");
    }

    /** When OpenPnP last connected to the controller (epoch ms), or null. */
    public Long getControllerConnectedAtMs() {
        return homeTracker.getConnectedAtMs();
    }

    public synchronized void setLocation(String name, double x, double y, double z) throws IOException {
        setLocation(name, x, y, z, null);
    }

    /**
     * As above; a place derived from another ({@code from}: the test-dot spot follows the waste) keeps the time and the
     * Z frame of the place it follows, else it is stamped now (I4: its Z was taken under this home).
     */
    public synchronized void setLocation(String name, double x, double y, double z, DispenseConfig.NamedLocation from)
            throws IOException {
        DispenseConfig c = cfg.copy();
        DispenseConfig.NamedLocation l = new DispenseConfig.NamedLocation(x, y, z);
        l.setAtMs = from != null ? from.setAtMs : Long.valueOf(System.currentTimeMillis());
        l.homeAtMs = from != null ? from.homeAtMs : Long.valueOf(homeTracker.frameHomeAtMs());
        c.locations.put(name, l);
        updateConfig(c);
    }

    /**
     * Records a needle change (tip setups design §22.3); never refuses. Stops a running engine task first, sets the
     * needle token {@code needleChangedAtMs} = max(now, previous + 1), writes a skip row for every coupon still
     * waiting for its readings, a {@code tip_mounts} row {@code needle} and a TIP event: frame, offset and landing
     * are to do.
     */
    public synchronized NeedleChange recordNeedleChange(String why, String operator) throws IOException, SQLException {
        NeedleChange nc = new NeedleChange();
        DispenseConfig before = cfg;
        RunResult running = activeRun;
        if (busy.get()) {
            abort("the needle was reported changed");
            nc.voided.task = running == null ? "an engine task" : running.kind;
        }
        long now = System.currentTimeMillis();
        long token = Math.max(now, before.needleChangedAtMs == null ? 0 : before.needleChangedAtMs + 1);
        // later than everything measured for a needle so far (the mounted setup's and the parked ones'), also when the
        // clock was set back since: a frame stamped after the token would still count for the needle put on now
        token = Math.max(token, measuredStamp(before.needleAnchor, before.needleZOffsetSetAtMs, before.needleLanding) + 1);
        for (DispenseConfig.ParkedTip p : before.parkedTips.values()) {
            token = Math.max(token, measuredStamp(p.needleAnchor, p.needleZOffsetSetAtMs, p.needleLanding) + 1);
        }
        DispenseConfig c = before.copy();
        c.needleChangedAtMs = token;
        writeConfig(c); // a fact: never refused, so never held back by a check of other settings
        lastChange = new Long[] {token, before.needleChangedAtMs};
        String at = ZCheck.clock(token);
        DoseRecord.Epochs ep = new DoseRecord.Epochs(getControllerConnectedAtMs(), isSimulatorConfirmed());
        List<DoseRecord> pending = new ArrayList<>();
        try {
            pending = db.pendingCoupons(now - (long) (before.padCalibration.doseRecordMaxAgeH * 3600e3));
        }
        catch (SQLException e) {
            // their dots no longer count either way (dispensed before the change); only the skip rows are missing
            Logger.warn(e, "dispense: the coupons waiting for readings were not read at the needle change");
        }
        for (DoseRecord coupon : pending) {
            if (!weighable(coupon, now, before, ep)) {
                continue;
            }
            try {
                skipCoupon(coupon, "the needle was changed at " + at, operator, coupon.jobId);
                if (coupon.couponPlace != null && !nc.voided.coupons.contains(coupon.couponPlace)) {
                    nc.voided.coupons.add(coupon.couponPlace);
                }
                if (coupon.jobId != null) {
                    nc.voided.job = coupon.jobId;
                }
            }
            catch (SQLException | RuntimeException e) {
                Logger.warn(e, "dispense: the skip row of coupon record {} was not written", coupon.id);
            }
        }
        nc.atMs = token;
        nc.setupKey = c.tip.key;
        nc.setupName = c.tip.name;
        nc.why = why;
        nc.operator = operator;
        TipSetups.Mount m = mountRow(TipSetups.Mount.NEEDLE, c, null, why == null ? "I changed the needle" : why,
                operator);
        m.voidedJson = new Gson().toJson(nc.voided);
        try {
            db.insertTipMount(m);
        }
        catch (SQLException | RuntimeException x) {
            // the change is recorded in the settings, which are the truth; the row is only its history
            Logger.warn(x, "dispense: the needle change's row was not written");
        }
        db.event(null, "INFO", TIP_EVENT, "needle changed (" + c.tip.name + "): needle Z frame, offset and landing to do"
                + (nc.voided.coupons.isEmpty() ? "" : "; the dots on " + String.join(" and ", nc.voided.coupons)
                        + " no longer count")
                + (nc.voided.task == null ? "" : "; the " + nc.voided.task + " was stopped"));
        nc.text = "The needle change is recorded at " + at + ": record the " + c.tip.name + "'s needle Z frame and check "
                + "where it lands." + (nc.voided.coupons.isEmpty() ? "" : " Wipe " + String.join(" and ",
                        nc.voided.coupons) + " before " + (nc.voided.coupons.size() == 1 ? "its" : "their")
                        + " next use: the dots on " + (nc.voided.coupons.size() == 1 ? "it" : "them")
                        + " no longer count.");
        undo = null;
        return nc;
    }

    /** When a needle frame, offset or landing was last measured (epoch ms), or {@code Long.MIN_VALUE + 1} for none. */
    private static long measuredStamp(DispenseConfig.NeedleAnchor anchor, Long offsetSetAtMs,
            DispenseConfig.NeedleLanding landing) {
        long n = Long.MIN_VALUE + 1;
        for (Long t : new Long[] {anchor == null ? null : anchor.setAtMs, offsetSetAtMs,
            landing == null ? null : landing.setAtMs}) {
            if (t != null) {
                n = Math.max(n, t);
            }
        }
        return n;
    }

    /** A row of {@code tip_mounts} of the mounted setup now (its version, token, head offset, profile and landing). */
    private TipSetups.Mount mountRow(String kind, DispenseConfig c, String from, String why, String operator) {
        TipSetups.Mount m = new TipSetups.Mount();
        m.kind = kind;
        m.setupKey = c.tip.key;
        m.setupVersion = c.tip.setupVersion;
        m.fromSetupKey = from;
        m.needleChangedAtMs = c.needleChangedAtMs;
        double[] ho = headOffsetNow();
        m.headOffsetXMm = ho == null ? null : ho[0];
        m.headOffsetYMm = ho == null ? null : ho[1];
        m.profileJson = new Gson().toJson(c.profile);
        m.landingJson = c.needleLanding == null ? null : new Gson().toJson(c.needleLanding);
        m.why = why;
        m.simulated = isSimulatorConfirmed();
        m.operator = operator;
        return m;
    }

    /** "The needle change is recorded: ...": the end of every refusal of a switch (§22.3). */
    private static String recordedWords(String name) {
        return " The needle change is recorded: nothing dispenses until the " + name + " is mounted and its needle Z "
                + "frame recorded.";
    }

    /**
     * Mounts another tip setup (tip setups design §22.3, the choice after {@link #recordNeedleChange}): refused (409
     * {@link EngineRefusal} "refused") while a task runs, the pressure hold is on, or the setup is retired or
     * incomplete; else parks the mounted setup, brings in the incoming one (live start angle kept), sets
     * {@code cfg.tip} and writes the config, then a {@code tip_mounts} row {@code mount} and a TIP event.
     */
    public synchronized MountResult switchTip(String key, String why, String operator) throws IOException, SQLException {
        TipSetup in = db.currentTipSetup(key);
        if (in == null) {
            throw new EngineRefusal(404, "not-found", "There is no tip " + key + ".");
        }
        String fromKey = cfg.tip.key;
        String fromName = cfg.tip.name;
        if (!busy.compareAndSet(false, true)) {
            throw new EngineRefusal(409, "refused", "An engine task is running: a needle cannot come off while the head "
                    + "moves (press STOP first)." + recordedWords(in.name));
        }
        try {
            if (pressureHoldActive.getAsBoolean()) {
                throw new EngineRefusal(409, "refused", "Switch the pressure off first: a needle comes off with the "
                        + "pressure off." + recordedWords(in.name));
            }
            if (in.retired) {
                throw new EngineRefusal(409, "refused", "The " + in.name + " is retired: bring it back first (Change tip "
                        + "› Show retired tips)." + recordedWords(in.name));
            }
            if (in.missing() != null) {
                throw new EngineRefusal(409, "refused", "The " + in.name + " has no " + in.missing() + " yet: fill it in "
                        + "first (Edit…)." + recordedWords(in.name));
            }
            hook("switch");
            long now = System.currentTimeMillis();
            MountResult r = new MountResult();
            r.mounted = key;
            r.mountedName = in.name;
            r.from = fromKey;
            r.fromName = fromName;
            r.atMs = now;
            if (key.equals(fromKey)) {
                // a "mount" of the mounted setup is the needle change alone (§22.3)
                r.needleChangedAtMs = cfg.needleChangedAtMs;
                r.profile = cfg.profile.copy();
                r.text = in.name + " stays mounted: record its needle Z frame (Setup › Needle Z frame).";
                return r;
            }
            // the switch stands on the needle change recorded just before it (§22.3): without it, the incoming setup's
            // parked frame, offset or landing could be newer than the token and count for the needle put on now
            Long token = cfg.needleChangedAtMs;
            DispenseConfig.ParkedTip incoming = cfg.parkedTips.get(key);
            if (token == null || incoming != null && measuredStamp(incoming.needleAnchor, incoming.needleZOffsetSetAtMs,
                    incoming.needleLanding) >= token) {
                throw new IllegalStateException("A mount needs the needle change recorded first (recordNeedleChange): "
                        + "nothing was changed.");
            }
            DispenseConfig before = cfg.copy();
            Long[] change = lastChange;
            if (change != null && change[0].equals(before.needleChangedAtMs)) {
                // the mount's own needle change came just before: an undo puts back the token from before it
                before.needleChangedAtMs = change[1];
            }
            DispenseConfig c = cfg.copy();
            DispenseConfig.ParkedTip park = new DispenseConfig.ParkedTip();
            DispenseConfig.ParkedTip was = c.parkedTips.get(fromKey);
            park.profile = c.profile.copy();
            park.needleAnchor = c.needleAnchor;
            park.needleZOffsetMm = c.needleZOffsetMm;
            park.needleZOffsetSetAtMs = c.needleZOffsetSetAtMs;
            park.needleZOffsetHomeAtMs = c.needleZOffsetHomeAtMs;
            park.needleXyTrim = c.needleXyTrim;
            park.needleLanding = c.needleLanding;
            park.parkedAtMs = now;
            park.profileFrom = was == null ? null : was.profileFrom;
            DispenseConfig.ParkedTip e = c.parkedTips.remove(key);
            if (e == null) {
                e = TipSetups.newEntry(c, c.profile, fromKey, in.name); // a setup that never got its entry
            }
            c.parkedTips.put(fromKey, park);
            DispenseProfile prof = e.profile == null ? new DispenseProfile() : e.profile.copy();
            // the start angle belongs to the controller's power-up and the screw, not to the needle (§22.3)
            prof.startAngleDeg = c.profile.startAngleDeg;
            prof.startAngleSetAtMs = c.profile.startAngleSetAtMs;
            List<String> problems = prof.validate(c);
            if (!problems.isEmpty()) {
                DispenseProfile reset = new DispenseProfile();
                reset.name = in.name + " (reset: " + problems.get(0) + ")";
                reset.startAngleDeg = c.profile.startAngleDeg;
                reset.startAngleSetAtMs = c.profile.startAngleSetAtMs;
                r.warnings.add("The " + in.name + "'s dispense profile no longer validates (" + problems.get(0)
                        + "): it was reset to the default recipe.");
                prof = reset;
            }
            c.profile = prof;
            c.needleAnchor = e.needleAnchor;
            c.needleZOffsetMm = e.needleZOffsetMm;
            c.needleZOffsetSetAtMs = e.needleZOffsetSetAtMs;
            c.needleZOffsetHomeAtMs = e.needleZOffsetHomeAtMs;
            c.needleXyTrim = e.needleXyTrim != null ? e.needleXyTrim : new DispenseConfig.NeedleXyTrim();
            c.needleLanding = e.needleLanding;
            c.tip = in.toSpec();
            List<String> invalid = c.validate();
            if (!invalid.isEmpty()) {
                throw new IllegalArgumentException("invalid dispense config after the mount: " + invalid);
            }
            writeConfig(c); // the config is the truth: written first, a row that fails is repaired at the next start
            r.needleChangedAtMs = c.needleChangedAtMs;
            r.profile = c.profile.copy();
            r.stale.addAll(java.util.Arrays.asList("needle Z frame", "needle Z offset", "where it lands", "plans",
                    "regulator reading", "prime"));
            r.headOffsetNow = headOffsetNow();
            DispenseConfig.NeedleLanding l = c.needleLanding;
            if (l != null && l.headOffsetXMm != null && l.headOffsetYMm != null) {
                r.headOffsetLandingWas = new double[] {l.headOffsetXMm, l.headOffsetYMm};
            }
            UndoSnapshot u = new UndoSnapshot();
            u.before = before;
            u.after = c.copy();
            u.fromKey = fromKey;
            u.toKey = key;
            u.atMs = now;
            u.tasksAtMount = tasksStarted.get();
            undo = u;
            r.undoUntilMs = now + UNDO_MS;
            r.text = in.name + " mounted at " + ZCheck.clock(now) + ". Next: record its needle Z frame (Setup › Needle Z "
                    + "frame)." + headOffsetWords(in.name, r.headOffsetNow, r.headOffsetLandingWas);
            TipSetups.Mount m = mountRow(TipSetups.Mount.MOUNT, c, fromKey, why == null ? "mounted" : why, operator);
            try {
                db.insertTipMount(m);
            }
            catch (SQLException | RuntimeException x) {
                Logger.warn(x, "dispense: the mount row was not written (the next start repairs it)");
            }
            db.event(null, "INFO", TIP_EVENT, "mounted the " + in.name + " (was the " + fromName + ")"
                    + (r.warnings.isEmpty() ? "" : "; " + String.join(" ", r.warnings)));
            return r;
        }
        finally {
            busy.set(false);
        }
    }

    /** The mount text's head offset sentence (§22.3), or "" when N2's head offset is the one the landing had. */
    private static String headOffsetWords(String name, double[] now, double[] was) {
        if (now == null || was == null || Math.abs(now[0] - was[0]) <= 0.001 && Math.abs(now[1] - was[1]) <= 0.001) {
            return "";
        }
        return String.format(Locale.ROOT, " N2's head offset is (%.3f, %.3f) now; the %s's landing was checked with "
                + "(%.3f, %.3f): set it back in OpenPnP, or check where it lands.", now[0], now[1], name, was[0], was[1]);
    }

    /**
     * Restores the previous mount exactly (setup, profile, frame, offset, trim, landing and the needle token) within
     * 2 min of a mount of another setup when no task ran and nothing was measured since; a {@code tip_mounts} row
     * {@code undo}. Otherwise 409 "Too late to undo: ...".
     */
    public synchronized MountResult undoMount(String operator) throws IOException, SQLException {
        UndoSnapshot u = undo;
        if (u == null) {
            throw new EngineRefusal(409, "too-late", "Nothing to undo: no other tip was mounted in the last 2 min.");
        }
        String old = u.before.tip.name;
        String late = null;
        if (System.currentTimeMillis() > u.atMs + UNDO_MS) {
            late = "2 min have passed";
        }
        else if (tasksStarted.get() != u.tasksAtMount) {
            late = "something ran";
        }
        else if (!cfg.tip.key.equals(u.toKey) || !measuredSame(cfg, u.after)) {
            late = "the frame was recorded";
        }
        if (late != null) {
            throw new EngineRefusal(409, "too-late", "Too late to undo: " + late + ". Mount the " + old
                    + " again instead.");
        }
        if (!busy.compareAndSet(false, true)) {
            throw new EngineRefusal(409, "too-late", "Too late to undo: something ran. Mount the " + old
                    + " again instead.");
        }
        try {
            DispenseConfig c = cfg.copy();
            DispenseConfig b = u.before;
            c.tip = b.tip;
            c.profile = b.profile.copy();
            c.profile.startAngleDeg = cfg.profile.startAngleDeg;
            c.profile.startAngleSetAtMs = cfg.profile.startAngleSetAtMs;
            c.needleAnchor = b.needleAnchor;
            c.needleZOffsetMm = b.needleZOffsetMm;
            c.needleZOffsetSetAtMs = b.needleZOffsetSetAtMs;
            c.needleZOffsetHomeAtMs = b.needleZOffsetHomeAtMs;
            c.needleXyTrim = b.needleXyTrim;
            c.needleLanding = b.needleLanding;
            c.needleChangedAtMs = b.needleChangedAtMs;
            c.parkedTips = new java.util.LinkedHashMap<>(b.copy().parkedTips);
            for (Map.Entry<String, DispenseConfig.ParkedTip> e : cfg.parkedTips.entrySet()) {
                // a setup added since the mount keeps its entry: the undo puts back the mount, nothing else
                if (!e.getKey().equals(b.tip.key)) {
                    c.parkedTips.putIfAbsent(e.getKey(), e.getValue());
                }
            }
            writeConfig(c);
            undo = null;
            MountResult r = new MountResult();
            r.mounted = c.tip.key;
            r.mountedName = c.tip.name;
            r.from = u.toKey;
            r.fromName = u.after.tip.name;
            r.atMs = System.currentTimeMillis();
            r.needleChangedAtMs = c.needleChangedAtMs;
            r.profile = c.profile.copy();
            r.headOffsetNow = headOffsetNow();
            r.text = "The " + c.tip.name + " never left the dispenser: it is mounted again with its needle Z frame, "
                    + "offset and landing.";
            db.insertTipMount(mountRow(TipSetups.Mount.UNDO, c, u.toKey, "undo: the " + c.tip.name
                    + " never left the dispenser", operator));
            db.event(null, "INFO", TIP_EVENT, "undid the mount of the " + u.after.tip.name + ": the " + c.tip.name
                    + " never left the dispenser");
            return r;
        }
        finally {
            busy.set(false);
        }
    }

    /** The needle frame, offset, trim and landing are the ones the mount left (nothing was measured since). */
    private static boolean measuredSame(DispenseConfig now, DispenseConfig after) {
        Gson g = new Gson();
        return g.toJson(now.needleAnchor).equals(g.toJson(after.needleAnchor))
                && now.needleZOffsetMm == after.needleZOffsetMm
                && java.util.Objects.equals(now.needleZOffsetSetAtMs, after.needleZOffsetSetAtMs)
                && g.toJson(now.needleXyTrim).equals(g.toJson(after.needleXyTrim))
                && g.toJson(now.needleLanding).equals(g.toJson(after.needleLanding));
    }

    /** Until when the last mount of another setup can be undone (epoch ms), or null. */
    public Long undoUntilMs() {
        UndoSnapshot u = undo;
        if (u == null || System.currentTimeMillis() > u.atMs + UNDO_MS || tasksStarted.get() != u.tasksAtMount
                || !cfg.tip.key.equals(u.toKey) || !measuredSame(cfg, u.after)) {
            return null;
        }
        return u.atMs + UNDO_MS;
    }

    /** The needle token check (§22.3): values taken under another needle are refused (409 needle-changed). */
    private void requireToken(Long needleChangedAtMs) {
        Long now = cfg.needleChangedAtMs;
        if (!java.util.Objects.equals(needleChangedAtMs, now)) {
            throw new EngineRefusal(409, "needle-changed", "The needle was changed at " + (now == null ? "?"
                    : ZCheck.clock(now)) + " after these values were taken: take them again.")
                            .with("needleChangedAtMs", now);
        }
    }

    /**
     * Sets the hand trim on top of the landing (±1 mm, §22.3) with N2's head offset now; 409 {@code needle-changed}
     * when the token is not the needle's, refused while the engine is busy.
     */
    public synchronized DispenseConfig.NeedleXyTrim setXyTrim(double dx, double dy, String note,
            Long needleChangedAtMs) throws IOException {
        requireToken(needleChangedAtMs);
        if (busy.get()) {
            throw new EngineRefusal(409, "busy", "The trim cannot change while a task runs: wait for it to end.");
        }
        if (!(Math.abs(dx) <= 1 && Math.abs(dy) <= 1)) {
            throw new IllegalArgumentException("The trim must be within ±1 mm on each axis: where the needle lands "
                    + "is set by Check where it lands.");
        }
        DispenseConfig c = cfg.copy();
        c.needleXyTrim = trim(dx, dy, note);
        List<String> problems = c.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid dispense config: " + problems);
        }
        writeConfig(c);
        db.event(null, "INFO", TIP_EVENT, String.format(Locale.ROOT, "needle XY trim of the %s set to %+.3f, %+.3f mm%s",
                c.tip.name, dx, dy, note == null ? "" : ": " + note));
        return c.needleXyTrim;
    }

    /** A trim stamped now with N2's head offset now. */
    private DispenseConfig.NeedleXyTrim trim(double dx, double dy, String note) {
        DispenseConfig.NeedleXyTrim t = new DispenseConfig.NeedleXyTrim();
        t.dxMm = dx;
        t.dyMm = dy;
        t.setAtMs = System.currentTimeMillis();
        t.note = note;
        double[] ho = headOffsetNow();
        t.headOffsetXMm = ho == null ? null : ho[0];
        t.headOffsetYMm = ho == null ? null : ho[1];
        return t;
    }

    /** "toward −Y": a bend direction in words. */
    static String toward(String t) {
        return t == null ? null : "toward " + (t.startsWith("-") ? "−" : "+") + t.substring(1).toUpperCase(Locale.ROOT);
    }

    /**
     * Saves a landing check's landing as the mounted setup's (§22.3) with its job, photo and N2's head offset, and
     * resets the trim. Not accepted and more than 1.0 mm from the setup's own landing: nothing is saved, the result's
     * question is set and null is returned. 409 {@code needle-changed} for another needle's result.
     */
    public synchronized DispenseConfig.NeedleLanding setLanding(LandingResult r, boolean accepted,
            Long needleChangedAtMs) throws IOException, SQLException {
        requireToken(needleChangedAtMs);
        if (r.needleSinceMs != null && !r.needleSinceMs.equals(cfg.needleChangedAtMs)) {
            requireToken(r.needleSinceMs);
        }
        if (r.setupKey != null && !r.setupKey.equals(cfg.tip.key)) {
            throw tipChanged();
        }
        if (r.n < org.openpnp.instancelabs.padprofile.LandingFit.MIN_MATCHES) {
            // a landing is measured by a row that fitted (§22.3): no locator dot or too few dots is nothing to save
            throw new EngineRefusal(409, "refused", "The landing check found no landing to save (" + r.n + " of "
                    + org.openpnp.instancelabs.padprofile.LandingFit.ROW_MM.length + " dots near their spots): check "
                    + "where it lands again.");
        }
        if (!(Math.abs(r.dxMm) <= DispenseConfig.MAX_LANDING_MM && Math.abs(r.dyMm) <= DispenseConfig.MAX_LANDING_MM)) {
            throw new IllegalArgumentException("A landing must be within ±" + (int) DispenseConfig.MAX_LANDING_MM
                    + " mm on each axis.");
        }
        DispenseConfig c = cfg.copy();
        String name = c.tip.name;
        DispenseConfig.NeedleLanding own = c.needleLanding;
        double moved = own == null ? 0 : Math.hypot(r.dxMm - own.dxMm, r.dyMm - own.dyMm);
        if (own != null && moved > org.openpnp.instancelabs.padprofile.LandingFit.MOVED_MM + 1e-9 && !accepted) {
            r.saved = false;
            String dir = TipSetup.FORM_BENT.equals(c.tip.form) && c.tip.bendToward != null
                    ? ", its bend " + toward(c.tip.bendToward) + " as in its picture" : "";
            r.question = String.format(Locale.ROOT, "The %s lands %.1f mm from where it landed on %s: is the %s on the "
                    + "dispenser%s?", name, moved, own.setAtMs == null ? "the last check" : day(own.setAtMs), name, dir);
            r.text = r.question;
            db.event(null, "WARN", TIP_EVENT, "landing check of the " + name + " not saved: " + r.question);
            return null;
        }
        // not before the needle change it measures (as the frame and the offset: a clock set back meanwhile)
        long now = Math.max(System.currentTimeMillis(), c.needleChangedAtMs == null ? 0 : c.needleChangedAtMs);
        DispenseConfig.NeedleLanding l = new DispenseConfig.NeedleLanding();
        l.dxMm = r.dxMm;
        l.dyMm = r.dyMm;
        l.setAtMs = now;
        l.jobId = r.jobId;
        l.photoId = r.photoId;
        double[] ho = r.headOffset != null ? r.headOffset : headOffsetNow();
        l.headOffsetXMm = ho == null ? null : ho[0];
        l.headOffsetYMm = ho == null ? null : ho[1];
        l.note = accepted && own != null && moved > org.openpnp.instancelabs.padprofile.LandingFit.MOVED_MM
                ? "saved by the operator: the " + name + " is on" : "set by a landing check";
        c.needleLanding = l;
        c.needleXyTrim = trim(0, 0, "reset by the landing check");
        List<String> problems = c.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid dispense config: " + problems);
        }
        writeConfig(c);
        r.saved = true;
        r.question = null;
        r.text = String.format(Locale.ROOT, "The %s lands %s, %s mm from the N2 head offset (%d dots, scatter %.2f mm). "
                + "Saved: every dot is corrected by it.", name, signed(r.dxMm), signed(r.dyMm), r.n,
                r.sdMm == null ? 0 : r.sdMm);
        db.event(null, "INFO", TIP_EVENT, "landing of the " + name + " saved: " + new Gson().toJson(r));
        return l;
    }

    /** "+2.32", "−0.41": a signed landing for the texts. */
    static String signed(double v) {
        String n = String.format(Locale.ROOT, "%.2f", Math.abs(v));
        return (v < 0 && !"0.00".equals(n) ? "−" : "+") + n;
    }

    /** "2026-10-29": the day of a time. */
    static String day(long ms) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new java.util.Date(ms));
    }

    /** 409 tip-changed (§22.2): a write for a setup that is no longer mounted. */
    private EngineRefusal tipChanged() {
        String at = "";
        try {
            for (TipSetups.Mount m : db.tipMounts(20)) {
                if (m.setupKey.equals(cfg.tip.key) && !TipSetups.Mount.NEEDLE.equals(m.kind)) {
                    at = " at " + ZCheck.clock(m.createdAtMs);
                    break;
                }
            }
        }
        catch (SQLException e) {
            at = "";
        }
        return new EngineRefusal(409, "tip-changed", "The tip changed while this was open (the " + cfg.tip.name
                + " was mounted" + at + "): reload.").with("mounted", cfg.tip.key);
    }

    /**
     * Keeps where the mounted setup lands across a needle change (§22.3, straight setups only, a landing of its own
     * under N2's head offset now): stamps it set now; otherwise 409 with {@link #keepLandingProblem()}.
     */
    public synchronized DispenseConfig.NeedleLanding keepLanding(Long needleChangedAtMs)
            throws IOException, SQLException {
        requireToken(needleChangedAtMs);
        String why = keepLandingProblem();
        if (why != null) {
            throw new EngineRefusal(409, "refused", why);
        }
        DispenseConfig c = cfg.copy();
        c.needleLanding.setAtMs = Math.max(System.currentTimeMillis(), c.needleChangedAtMs == null ? 0
                : c.needleChangedAtMs);
        c.needleLanding.note = c.needleChangedAtMs == null ? "kept" : "kept after the needle change at "
                + ZCheck.clock(c.needleChangedAtMs);
        writeConfig(c);
        db.event(null, "INFO", TIP_EVENT, "kept where the " + c.tip.name + " lands: " + new Gson().toJson(c.needleLanding));
        return c.needleLanding;
    }

    /**
     * Why "Keep where it lands" is refused now (the §22.3 text: a bent tip, no landing of its own, N2's head offset
     * changed), or null when it is allowed.
     */
    public String keepLandingProblem() {
        DispenseConfig c = cfg;
        String name = c.tip.name;
        if (!TipSetup.FORM_STRAIGHT.equals(c.tip.form)) {
            return "A bent tip lands where its bend points: check where it lands.";
        }
        if (c.needleLanding == null) {
            return "The " + name + " has no landing of its own yet: check where it lands.";
        }
        if (!sameHeadOffset(c.needleLanding.headOffsetXMm, c.needleLanding.headOffsetYMm)) {
            return "N2's head offset changed since the " + name + "'s landing was checked: check where it lands.";
        }
        return null;
    }

    /** True when the recorded head offset is N2's now within 0.001 mm (unknown now: true). */
    private boolean sameHeadOffset(Double x, Double y) {
        double[] now = headOffsetNow();
        if (now == null) {
            return true;
        }
        return x != null && y != null && Math.abs(x - now[0]) <= 0.001 && Math.abs(y - now[1]) <= 0.001;
    }

    /**
     * Where the needle lands is checked (§22.3): the landing set since the needle change (or no change recorded)
     * under N2's head offset now (within 0.001 mm), and a trim that is not zero set under it too.
     */
    public boolean isNeedleXyChecked() {
        return needleXyProblem(cfg) == null;
    }

    /** Why where the needle lands is not checked (the readiness and I4 text), or null when it is. */
    public String needleXyProblem() {
        return needleXyProblem(cfg);
    }

    private String needleXyProblem(DispenseConfig c) {
        String name = c.tip.name;
        DispenseConfig.NeedleLanding l = c.needleLanding;
        String on = "the " + name + " was put on" + (c.needleChangedAtMs == null ? "" : " at "
                + ZCheck.clock(c.needleChangedAtMs));
        if (l == null || l.setAtMs == null || c.needleChangedAtMs != null && l.setAtMs < c.needleChangedAtMs) {
            return on + " and where it lands has not been checked: check where it lands (Setup › Dispenser & tip).";
        }
        if (!sameHeadOffset(l.headOffsetXMm, l.headOffsetYMm)) {
            return "N2's head offset changed in OpenPnP since the " + name + "'s landing was checked: set it back, or "
                    + "check where it lands";
        }
        DispenseConfig.NeedleXyTrim t = c.needleXyTrim;
        if ((t.dxMm != 0 || t.dyMm != 0) && !sameHeadOffset(t.headOffsetXMm, t.headOffsetYMm)) {
            return "N2's head offset changed in OpenPnP since the " + name + "'s XY trim was set: set it back, or "
                    + "check where it lands";
        }
        return null;
    }

    /** N2's head offset in OpenPnP now {x, y} (mm; {@code nozzle.getHeadOffsets()}, read only), or null. */
    public double[] headOffsetNow() {
        Nozzle n = nozzle;
        if (n == null) {
            return null;
        }
        try {
            Location l = n.getHeadOffsets().convertToUnits(LengthUnit.Millimeters);
            return new double[] {l.getX(), l.getY()};
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The frame of the mounted setup before its last needle change (§22.3: "is this the tip you think it is?"), kept
     * when a new frame replaces it; null when none was replaced since the start.
     */
    private volatile Object[] previousFrame;

    /**
     * Sets the needle Z offset (manual entry or touch-off result) and records it, for the values taken under the needle
     * token {@code needleChangedAtMs} (tip setups design §22.3): 409 {@code needle-changed} when the needle was changed
     * after they were taken; answers the frame question and the I4 offset warning.
     */
    public synchronized NeedleFrameResult setNeedleZOffset(double offsetMm, String method, String details,
            Long needleChangedAtMs) throws IOException, SQLException {
        requireToken(needleChangedAtMs);
        DispenseConfig c = cfg.copy();
        double prev = c.needleZOffsetMm;
        c.needleZOffsetMm = offsetMm;
        c.needleZOffsetSetAtMs = Math.max(System.currentTimeMillis(),
                c.needleChangedAtMs == null ? 0 : c.needleChangedAtMs);
        c.needleZOffsetHomeAtMs = homeTracker.frameHomeAtMs();
        updateConfig(c);
        db.insertTouchOff(method, offsetMm, prev, details, null);
        NeedleFrameResult r = new NeedleFrameResult();
        r.anchor = cfg.needleAnchor;
        r.offsetMm = offsetMm;
        Object[] pf = previousFrame;
        if (pf != null && c.tip.key.equals(pf[0])) {
            r.question = frameQuestion(c, offsetMm, (Double) pf[1], (Long) pf[2]);
        }
        r.warning = Interlocks.frameTooClose(cfg);
        return r;
    }

    /**
     * I4 needle Z frame: the same surface touched by N1 (OpenPnP frame, {@code surfaceZMm}) and by the needle tip
     * ({@code needleContactZMm}, N2 Z); sets the offset to their difference, and a touch-off (or a manual entry within
     * the drift limit) is still required before dispensing. {@code rawSumMm} records the bench relation the needle
     * contact came from (or null). The heights were captured under the needle token {@code needleChangedAtMs} (§22.3):
     * 409 {@code needle-changed} after a needle change; answers the frame question (the setup's previous frame) and the
     * I4 offset warning.
     */
    public synchronized NeedleFrameResult setNeedleAnchor(double surfaceZMm, double needleContactZMm, Double xMm,
            Double yMm, Double rawSumMm, Long needleChangedAtMs) throws IOException, SQLException {
        requireToken(needleChangedAtMs);
        DispenseConfig c = cfg.copy();
        DispenseConfig.NeedleAnchor was = c.needleAnchor;
        if (was != null && !c.isNeedleAnchored()) {
            previousFrame = new Object[] {c.tip.key, was.offsetMm(), was.setAtMs};
        }
        double prev = c.needleZOffsetMm;
        long now = Math.max(System.currentTimeMillis(), c.needleChangedAtMs == null ? 0 : c.needleChangedAtMs);
        c.needleAnchor = new DispenseConfig.NeedleAnchor(surfaceZMm, needleContactZMm, xMm, yMm, now);
        c.needleAnchor.rawSumMm = rawSumMm;
        c.needleAnchor.homeAtMs = homeTracker.frameHomeAtMs();
        c.needleZOffsetMm = c.needleAnchor.offsetMm();
        updateConfig(c);
        db.insertTouchOff("needle-frame (N1 + needle contact)", c.needleZOffsetMm, prev, new com.google.gson.Gson()
                .toJson(c.needleAnchor), null);
        db.event(null, "INFO", "I4", String.format(java.util.Locale.ROOT,
                "needle frame: N1 contact %.3f, needle contact %.3f -> offset %.3f; touch-off required",
                surfaceZMm, needleContactZMm, c.needleZOffsetMm));
        NeedleFrameResult r = new NeedleFrameResult();
        r.anchor = c.needleAnchor;
        r.offsetMm = c.needleZOffsetMm;
        Object[] pf = previousFrame;
        if (pf != null && c.tip.key.equals(pf[0])) {
            r.question = frameQuestion(c, c.needleZOffsetMm, (Double) pf[1], (Long) pf[2]);
        }
        r.warning = Interlocks.frameTooClose(cfg);
        if (r.question != null) {
            db.event(null, "WARN", TIP_EVENT, r.question);
        }
        return r;
    }

    /**
     * "The {S}'s tip touches {d} mm higher than when it was on on {date}: is the {S} on the dispenser?" when the
     * offset moved more than needleOffsetMaxDriftMm from the setup's previous frame (§22.3), else null.
     */
    static String frameQuestion(DispenseConfig c, double offsetMm, Double previousMm, Long previousAtMs) {
        if (previousMm == null || Math.abs(offsetMm - previousMm) <= c.needleOffsetMaxDriftMm + 1e-9) {
            return null;
        }
        double d = offsetMm - previousMm;
        String name = c.tip.name;
        return String.format(Locale.ROOT, "The %s's tip touches %.2f mm %s than when it was on on %s: is the %s on the "
                + "dispenser?", name, Math.abs(d), d > 0 ? "higher" : "lower", previousAtMs == null ? "the last frame"
                        : day(previousAtMs), name);
    }

    /**
     * Needle contact Z (N2, OpenPnP frame) from the bench relation "needle contact raw Z = K - N1
     * contact Z" (side camera, 2026-10-27: K = 65.59, so the needle touches 2.59 mm below N1 in
     * OpenPnP's N2 frame). N1's OpenPnP Z is its raw Z on this machine (N1 is not transformed).
     */
    public double needleContactZFromRawSum(double n1ContactZ, double rawSumMm) throws Exception {
        requireAvailable();
        OpenPnpRawMapper m = new OpenPnpRawMapper((ReferenceMachine) configuration.getMachine(), nozzle, cfg.bAxisName);
        double[] n = m.currentNozzleLocation();
        double r0 = m.toRaw(n[0], n[1], 0).z, r1 = m.toRaw(n[0], n[1], 10).z;
        if (Math.abs(r1 - r0) < 1e-9) {
            throw new IllegalStateException("N2's Z does not move the raw Z axis");
        }
        return (rawSumMm - n1ContactZ - r0) * 10 / (r1 - r0);
    }

    /**
     * Why the needle frame cannot be recorded from K now, or null. K is a sum of raw Z heights (N1's contact and
     * the needle's), so it holds for the home and the needle it was measured with only: a home that moved the Z
     * zero moves K by about twice the shift, and a frame "from K" then gives the old offset (63 - K) whatever N1
     * just touched (review: the needle 2 x 0.8 mm into the board after the Z-moved remedy). So while the Z check of
     * this home failed, and after a needle change, the frame is measured with both tips touching the same spot.
     */
    public String needleFrameFromKRefusal() {
        DispenseConfig c = cfg;
        ZCheck.Result z = homeTracker.getZCheck();
        if (ZCheck.failed(z, homeTracker.getLastHomeAtMs())) {
            return String.format(Locale.ROOT, "K was measured under another home: Z moved %.2f mm at the home of %s, "
                    + "and K moves by about twice that. Record the needle frame with both tips touching the same spot",
                    z.deltaMm, ZCheck.clock(z.homeAtMs));
        }
        if (c.needleAnchor != null && !c.isNeedleAnchored()) {
            return "K was measured with the needle before the change at " + ZCheck.clock(c.needleChangedAtMs)
                    + ": record the needle frame with both tips touching the same spot";
        }
        return null;
    }

    /** Stores a probed surface (validated: at least 3 points spanning an area). */
    public synchronized SurfacePlane setSurface(String name, List<DispenseConfig.SurfacePoint> points, String note)
            throws IOException {
        return setSurface(name, points, note, null);
    }

    /**
     * As above; {@code boardFingerprint} ties a board surface to the board registration it was
     * probed on (a re-seated board then needs new probes, I4).
     */
    public synchronized SurfacePlane setSurface(String name, List<DispenseConfig.SurfacePoint> points, String note,
            String boardFingerprint) throws IOException {
        return setSurface(name, points, note, boardFingerprint, null);
    }

    /** As above; {@code simulated} marks a surface probed on OpenPnP's simulator (refused elsewhere, I4). */
    public synchronized SurfacePlane setSurface(String name, List<DispenseConfig.SurfacePoint> points, String note,
            String boardFingerprint, Boolean simulated) throws IOException {
        SurfacePlane plane = SurfacePlane.fit(points); // throws IllegalArgumentException if unusable
        DispenseConfig c = cfg.copy();
        DispenseConfig.SurfaceMap m = new DispenseConfig.SurfaceMap();
        m.points = new ArrayList<>(points);
        m.setAtMs = System.currentTimeMillis();
        m.homeAtMs = homeTracker.frameHomeAtMs();
        m.note = note;
        m.boardFingerprint = boardFingerprint;
        m.simulated = simulated;
        if (name.equals(c.blankLocationName)) {
            m.replaced = ZCheck.kept(c.surface(name), homeTracker.getLastHomeAtMs());
        }
        c.surfaces.put(name, m);
        updateConfig(c);
        db.event(null, "INFO", "SURFACE", String.format(java.util.Locale.ROOT,
                "surface '%s': %d points, Z %.3f..%.3f, slope %.4f, largest residual %.3f mm", name, points.size(),
                plane.minZ, plane.maxZ, plane.slope(), plane.maxAbsResidualMm));
        return plane;
    }

    public synchronized void clearSurface(String name) throws IOException {
        DispenseConfig c = cfg.copy();
        if (c.surfaces.remove(name) != null) {
            updateConfig(c);
            db.event(null, "INFO", "SURFACE", "surface '" + name + "' cleared");
        }
    }

    /** The fitted plane of a stored surface, or null if there is none or it cannot be fitted. */
    public SurfacePlane surfacePlane(String name) {
        return planeOf(cfg, name);
    }

    static SurfacePlane planeOf(DispenseConfig c, String name) {
        DispenseConfig.SurfaceMap m = c.surface(name);
        if (m == null || m.points == null || m.points.size() < 3) {
            return null;
        }
        try {
            return SurfacePlane.fit(m.points);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Identity of a board location as a plan depends on it: global location, side, fiducial
     * transform (status and matrix), every placement (review finding: a board moved, re-checked
     * or edited after planning was not detected at Run) and the parts, packages and pads behind
     * them ({@link BoardContentDigest}: a board imported again with other pads).
     */
    public static String boardFingerprint(BoardLocation bl) {
        StringBuilder sb = new StringBuilder();
        Location g = bl.getGlobalLocation().convertToUnits(LengthUnit.Millimeters);
        sb.append(String.format(java.util.Locale.ROOT, "%.4f;", g.getZ()));
        appendRegistration(sb, bl);
        if (bl.getBoard() != null) {
            for (org.openpnp.model.Placement pl : bl.getBoard().getPlacements()) {
                Location l = pl.getLocation().convertToUnits(LengthUnit.Millimeters);
                sb.append(String.format(java.util.Locale.ROOT, "|%s,%s,%s,%s,%.4f,%.4f,%.4f,%.4f", pl.getId(),
                        pl.getSide(), pl.getType(), pl.isEnabled(), l.getX(), l.getY(), l.getZ(), l.getRotation()));
            }
            sb.append('|').append(BoardContentDigest.of(bl.getBoard()));
        }
        return digest(sb);
    }

    /**
     * Identity of where the board sits: X/Y/rotation, side and the fiducial transform (not its Z or
     * placements). A board surface is tied to it (I4: re-probe after a re-seat; editing a placement
     * or the board's nominal Z does not move the board).
     */
    public static String boardRegistration(BoardLocation bl) {
        return digest(appendRegistration(new StringBuilder("reg;"), bl));
    }

    private static StringBuilder appendRegistration(StringBuilder sb, BoardLocation bl) {
        Location g = bl.getGlobalLocation().convertToUnits(LengthUnit.Millimeters);
        sb.append(String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f;%s;%s;", g.getX(), g.getY(), g.getRotation(),
                bl.getGlobalSide(), bl.getPlacementsTransformStatus()));
        java.awt.geom.AffineTransform t;
        try {
            t = bl.getLocalToGlobalTransform();
        }
        catch (RuntimeException e) {
            t = null;
        }
        if (t != null) {
            double[] m = new double[6];
            t.getMatrix(m);
            for (double v : m) {
                sb.append(String.format(java.util.Locale.ROOT, "%.6f,", v));
            }
        }
        return sb;
    }

    private static String digest(StringBuilder sb) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", h[i]));
            }
            return hex.toString();
        }
        catch (java.security.NoSuchAlgorithmException e) {
            return sb.toString();
        }
    }

    /** Whether B has been zeroed (G92 B0) since the last home / reconnect (I2). */
    public boolean isBZeroed() {
        return bZeroed.get();
    }

    public boolean isBusy() {
        return busy.get();
    }

    public RunResult getActiveRun() {
        return activeRun;
    }

    public RunResult getLastRun() {
        return lastRun;
    }

    public void addListener(DispenseListener l) {
        listeners.add(l);
    }

    public void removeListener(DispenseListener l) {
        listeners.remove(l);
    }

    public void setPressureSensor(BarrelPressureSensor s) {
        pressureSensor = s;
    }

    /** Overrides the camera/vision used for test dots and calibrations (null = top camera). */
    public void setDotImager(DotImager imager) {
        dotImagerOverride = imager;
    }

    /** Overrides the camera that photographs the tip from below (tests; null = {@link OpenPnpTipCamera}). */
    public void setTipCamera(TipCamera camera) {
        tipCameraOverride = camera;
    }

    /** The camera that photographs the tip from below: the test's, else OpenPnP's first camera looking up. */
    TipCamera tipCamera() {
        TipCamera t = tipCameraOverride;
        if (t != null) {
            return t;
        }
        Machine m = configuration.getMachine();
        return m == null ? null : new OpenPnpTipCamera(m);
    }

    /**
     * Thread-safe: stops the active stream before its next line; pneumatics are switched off (I1).
     * Every task queued before it refuses at its start ({@link #stopEpoch}). A task OpenPnP dropped
     * ends here ({@link #releaseDroppedTask}).
     */
    public void abort(String reason) {
        stopEpoch.incrementAndGet();
        cameraTourAbort = true;
        DispenseExecutor e = activeExecutor;
        if (e != null) {
            e.requestAbort(reason);
        }
        releaseDroppedTask();
    }

    /** An engine task as queued on OpenPnP's machine thread. */
    private static final class Queued {
        final RunResult run;
        final long atMs = System.currentTimeMillis();
        final AtomicBoolean began = new AtomicBoolean(false);
        volatile Future<?> future;

        Queued(RunResult run) {
            this.run = run;
        }
    }

    /** How long a queued task may wait for an idle machine thread before it counts as dropped. */
    static final long DROPPED_AFTER_MS = 1000;

    /**
     * Ends an engine task OpenPnP dropped, and returns whether it did (rc1 review). OpenPnP removes every task queued
     * behind one of its own that throws (its Home or jog meeting a limit), without running or cancelling it, so the
     * task's finish() never ran and every engine endpoint then answered "a dispense task is already running … press
     * STOP" until OpenPnP restarted, STOP included. A task still not begun while the machine thread has been idle past
     * {@link #DROPPED_AFTER_MS} will never run: it ends ERROR here, and if it ran after all it would end at once.
     * Called by STOP, and by the API before it refuses a request as busy.
     */
    public boolean releaseDroppedTask() {
        Queued q = queued;
        Machine m = configuration.getMachine();
        if (q == null || !busy.get() || q.future == null || q.future.isDone() || m == null || m.isBusy()
                || System.currentTimeMillis() - q.atMs < DROPPED_AFTER_MS || !q.began.compareAndSet(false, true)) {
            return false;
        }
        q.run.status = RunResult.Status.ERROR;
        q.run.message = "OpenPnP dropped this task before it started (a task of its own ahead of it failed): nothing "
                + "was sent; start it again";
        Logger.warn("dispense {}: dropped by OpenPnP before it started", q.run.kind);
        finish(q.run);
        q.future.cancel(false); // a caller waiting for it (the pad calibration) learns at once
        return true;
    }

    // ------------------------------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------------------------------

    /** Every pad type as the mounted tip setup sees it (§22.2: {@link TipSetups#typesFor}). */
    public List<PadType> getPadTypes() throws SQLException {
        return TipSetups.typesFor(db, cfg.tip.key);
    }

    /**
     * Appends a pad type version for the mounted setup (tip setups design §22.2), under the lock: {@code forSetup}
     * must be the mounted setup (409 {@code tip-changed}). A pad edit sends the {@code padVersion} it showed (409
     * {@code pad-changed} when another was written meanwhile); a dose write ({@code padVersion} null) takes the pad
     * fields of the composed view now. Returns the stored row as the setup's composed view shows it.
     */
    public synchronized PadType savePadType(PadType v, String forSetup, Integer padVersion) throws SQLException {
        String s = cfg.tip.key;
        if (forSetup == null || !forSetup.equals(s)) {
            throw tipChanged();
        }
        PadType cur = db.currentPadType(v.typeKey, s);
        PadType w = v.copy();
        if (cur != null && padVersion == null) {
            // a dose write: the pad fields as every setup sees them now (a pad edit made meanwhile stays)
            w.family = cur.family;
            w.name = cur.name;
            w.padWMm = cur.padWMm;
            w.padHMm = cur.padHMm;
            w.padRoundness = cur.padRoundness;
            w.layout = cur.layout;
            w.pitchMm = cur.pitchMm;
            w.tMm = cur.tMm;
            w.k = cur.k;
            w.packagePattern = cur.packagePattern;
        }
        else if (cur != null && !padVersion.equals(cur.padVersion)) {
            TipSetup other = null;
            for (PadType h : db.padTypeHistory(v.typeKey)) {
                if (h.version == cur.padVersion) {
                    other = db.currentTipSetup(org.openpnp.instancelabs.padprofile.Tip.setupOf(h.tipKey));
                }
            }
            throw new EngineRefusal(409, "pad-changed", (cur.name == null ? v.typeKey : cur.name) + " was changed "
                    + "meanwhile (version " + cur.padVersion + ", with " + (other == null ? "another tip"
                            : "the " + other.name) + "): reload.").with("padVersion", cur.padVersion);
        }
        boolean raised = w.status != null && w.status.atLeast(PadTypeStatus.CHARACTERIZED)
                && (cur == null || cur.standIn || !cur.status.atLeast(PadTypeStatus.CHARACTERIZED));
        if (raised) {
            // §22.2: without the bench model nothing the mounted setup did not measure goes above provisional (a
            // stand-in or a copy raised by hand included); the bench setup is refused as before by the editor's rule
            requireEvidence(cfg, w);
        }
        w.tipKey = s; // every new version names its setup (§22.1)
        PadType stored = db.insertPadTypeVersion(w);
        db.event(null, "INFO", "PAD_TYPE", "saved " + stored + " for the " + cfg.tip.name);
        PadType view = db.currentPadType(stored.typeKey, s);
        return view != null ? view : stored;
    }

    /**
     * The pad-type editor's save that raises the mounted setup's version to characterized or validated (§22.5): the
     * version as {@link #savePadType(PadType, String, Integer)}, and a {@code pad_calibrations} record of method
     * by-hand with its label (null = the automatic one), the given photos and the main picture linked.
     */
    public synchronized PadCalibrationRecord savePadTypeByHand(PadType v, String forSetup, String label,
            List<Long> photoIds, String operator) throws SQLException {
        PadType before = db.currentPadType(v.typeKey, cfg.tip.key);
        return savePadTypeByHand(v, forSetup, before == null ? null : before.padVersion, label, photoIds, operator);
    }

    /**
     * As {@link #savePadTypeByHand(PadType, String, String, List, String)} for the editor that names the pad it showed
     * (§22.2): {@code padVersion} for a pad edited with the raise (409 {@code pad-changed} when another was written
     * meanwhile), null when only the status and the dose were edited (the pad as every setup sees it now).
     */
    public synchronized PadCalibrationRecord savePadTypeByHand(PadType v, String forSetup, Integer padVersion,
            String label, List<Long> photoIds, String operator) throws SQLException {
        if (v.status != PadTypeStatus.CHARACTERIZED && v.status != PadTypeStatus.VALIDATED) {
            throw new IllegalArgumentException("a by-hand calibration raises the status to characterized or validated");
        }
        // the label and the pictures are checked before anything is written: a refusal leaves no version or record
        String text = label == null ? null : cleanLabel(label);
        List<Photo> pictures = picturesFor(photoIds);
        PadType before = db.currentPadType(v.typeKey, cfg.tip.key);
        PadType stored = savePadType(v, forSetup, padVersion);
        DispenseConfig c = cfg;
        long now = System.currentTimeMillis();
        PadCalibrationRecord rec = new PadCalibrationRecord();
        rec.jobId = "hand-" + now;
        rec.typeKey = stored.typeKey;
        rec.versionFrom = before == null || before.standIn ? null : before.version;
        rec.versionTo = stored.version;
        rec.outcome = stored.status == PadTypeStatus.VALIDATED ? PadCalibrationRecord.VALIDATED
                : PadCalibrationRecord.CHARACTERIZED;
        rec.steps = stored.dotSteps;
        rec.dotsPerPad = stored.dotsPerPad;
        rec.massPerPadMg = stored.massPerPadMg;
        rec.method = PadCalibrationRecord.METHOD_BY_HAND;
        rec.setupKey = c.tip.key;
        rec.needleSinceMs = c.needleChangedAtMs;
        rec.simulated = isSimulatorConfirmed();
        rec.operator = operator;
        List<Long> evidence = new ArrayList<>();
        if (stored.status == PadTypeStatus.VALIDATED) {
            rec.checks.add(new PadCalibrationRecord.Check("validated-by-you", true, "validated on a board by "
                    + (operator == null ? "you" : operator)));
        }
        else {
            DoseRecord.Epochs ep = new DoseRecord.Epochs(getControllerConnectedAtMs(), isSimulatorConfirmed());
            List<DoseRecord> fresh = DoseRecords.fresh(db.doseRecords(null, now - (long) (c.padCalibration
                    .doseRecordMaxAgeH * 3600e3)), c, ep, now, db.flaggedJobs());
            int steps = stored.dotSteps != null ? stored.dotSteps : c.profile.netStepsPerDot;
            evidence.addAll(DoseRecords.ids(fresh, steps));
            PadCalibrationRecord.Check k = new PadCalibrationRecord.Check("status-evidence", true, "records "
                    + evidence);
            rec.checks.add(k);
        }
        rec.evidence = evidence;
        rec.message = "raised by hand to " + stored.status.dbName + " for the " + c.tip.name;
        PadCalibrationRecord out = db.insertPadCalibration(rec);
        labelAndLink(out, stored, text, operator);
        // the result photos of the jobs its evidence came from, the photos given, the main picture
        java.util.Set<String> jobs = new java.util.LinkedHashSet<>();
        for (Long id : evidence) {
            DoseRecord r = db.doseRecord(id);
            if (r != null && r.jobId != null) {
                jobs.add(r.jobId);
            }
        }
        for (String job : jobs) {
            for (Photo ph : db.photos(c.tip.key, null, job, Photo.KIND_RESULT, false, 20)) {
                linkQuietly(ph.id, out.id, Photo.ROLE_RESULT, operator);
            }
        }
        for (Photo p : pictures) {
            // a result photo (the guide ladder's) is the record's result, any other picture one of the tip (§22.4)
            linkPhotos(java.util.Collections.singletonList(p.id), java.util.Collections.singletonList(out.id),
                    Photo.KIND_RESULT.equals(p.kind) ? Photo.ROLE_RESULT : Photo.ROLE_TIP);
        }
        db.event(null, "INFO", "PCAL", "by hand: " + new Gson().toJson(out));
        return out;
    }

    /**
     * The pictures a by-hand record will show, checked as their links will be (§22.4): each exists, is of the mounted
     * setup (409 {@code setup-mismatch}), a picture from below of the needle on now (409 {@code needle-changed}), and
     * at most {@link PhotoStore#MAX_TIP_PER_RECORD} of the tip (409 {@code too-many}).
     */
    private List<Photo> picturesFor(List<Long> photoIds) throws SQLException {
        List<Photo> l = new ArrayList<>();
        int tips = 0;
        for (Long id : photoIds == null ? new ArrayList<Long>() : new ArrayList<>(new java.util.LinkedHashSet<>(
                photoIds))) {
            Photo p = db.photo(id);
            if (p == null) {
                throw new EngineRefusal(404, "not-found", "There is no picture " + id + ".");
            }
            if (!cfg.tip.key.equals(p.setupKey)) {
                throw new EngineRefusal(409, "setup-mismatch", "This picture is of the " + nameOf(p.setupKey)
                        + "; this calibration is made with the " + cfg.tip.name + ".");
            }
            if (Photo.SOURCE_BOTTOM_CAMERA.equals(p.source) && !java.util.Objects.equals(p.needleSinceMs,
                    cfg.needleChangedAtMs)) {
                throw new EngineRefusal(409, "needle-changed", "Picture " + id + " was taken with another needle than "
                        + "the one on the dispenser now: a needle change came between them.");
            }
            tips += Photo.KIND_TIP.equals(p.kind) ? 1 : 0;
            l.add(p);
        }
        if (tips > PhotoStore.MAX_TIP_PER_RECORD) {
            throw new EngineRefusal(409, "too-many", "A calibration shows at most " + PhotoStore.MAX_TIP_PER_RECORD
                    + " pictures of the tip: choose fewer.");
        }
        return l;
    }

    /**
     * The mounted tip setup as the check, the planner and the suggester see it (§22.2): {@code cfg.tip} with its dot
     * table at the current recipe, paste and simulator flag, cached until one of those or the records change.
     */
    public Tip mountedTip() throws SQLException {
        DispenseConfig c = cfg;
        TipSpec t = c.tip;
        boolean sim = isSimulatorConfirmed();
        String doseFp = c.profile.doseFingerprint();
        java.util.Set<String> flagged = db.flaggedJobs();
        // the statistics' settings too: a job's records of a dose are pooled as the job judged them
        String key = new Gson().toJson(t) + "|" + doseFp + "|" + c.paste.key + "|" + sim + "|"
                + db.newestDoseRecordId() + "|" + flagged + "|" + new Gson().toJson(c.padCalibration);
        TipCache cached = tipCache;
        if (cached != null && key.equals(cached.key)) {
            return copyTip(cached.tip);
        }
        Tip tip = PadInstances.specTip(t);
        DoseRecords.DotTable table = DoseRecords.dotTable(db.doseRecordsOf(t.key, doseFp, c.paste.key, sim), t.key,
                doseFp, c.paste.key, sim, flagged, DoseRecords.settings(c.padCalibration));
        tip.measured.putAll(table.doses);
        tip.mgPerStep = table.mgPerStep;
        tip.mgPerStepAtMs = table.mgPerStepAtMs;
        tip.mgPerStepConfirmed = table.mgPerStepConfirmed;
        tipCache = new TipCache(key, tip); // one write: a tip is never paired with another identity's key
        return copyTip(tip);
    }

    /** The mounted setup's dot table (the setup view's dots, §22.6). */
    public DoseRecords.DotTable dotTable() throws SQLException {
        DispenseConfig c = cfg;
        boolean sim = isSimulatorConfirmed();
        String doseFp = c.profile.doseFingerprint();
        return DoseRecords.dotTable(db.doseRecordsOf(c.tip.key, doseFp, c.paste.key, sim), c.tip.key, doseFp,
                c.paste.key, sim, db.flaggedJobs(), DoseRecords.settings(c.padCalibration));
    }

    private static Tip copyTip(Tip t) {
        Tip x = new Tip(t.key, t.name, t.idMm, t.odMm, t.minDotSteps);
        x.setupVersion = t.setupVersion;
        x.dispenser = t.dispenser;
        x.benchModel = t.benchModel;
        x.measured.putAll(t.measured);
        x.mgPerStep = t.mgPerStep;
        x.mgPerStepAtMs = t.mgPerStepAtMs;
        x.mgPerStepConfirmed = t.mgPerStepConfirmed;
        return x;
    }

    /**
     * Writes a coupon's skip mass row (no readings, {@code why}): the weighing never came (tip setups design §22.3: a
     * needle change, a wrong tip). Returns the row.
     */
    public synchronized DoseRecord skipCoupon(DoseRecord coupon, String why, String operator, String jobId)
            throws SQLException {
        DoseRecord m = new DoseRecord();
        m.kind = DoseRecord.MASS;
        m.steps = coupon.steps;
        m.n = coupon.n;
        m.couponRecordId = coupon.id;
        m.couponPlace = coupon.couponPlace;
        m.pressurePsi = coupon.pressurePsi;
        m.doseFp = coupon.doseFp;
        m.tipKey = coupon.tipKey;
        m.pasteKey = coupon.pasteKey;
        m.regulatorPsi = coupon.regulatorPsi;
        m.regulatorAtMs = coupon.regulatorAtMs;
        m.pasteLifeAtMs = coupon.pasteLifeAtMs;
        m.controllerConnectedAtMs = coupon.controllerConnectedAtMs;
        m.startAngleSetAtMs = coupon.startAngleSetAtMs;
        m.startAngleDeg = coupon.startAngleDeg;
        m.method = coupon.simulated ? "sim" : "typed";
        m.simulated = coupon.simulated;
        m.runUuid = coupon.runUuid;
        m.jobId = jobId;
        m.operator = operator;
        m.why = why == null || why.trim().isEmpty() ? "the weighing was skipped" : why;
        return db.insertDoseRecord(m);
    }

    /**
     * "Made with another tip" (§22.3): flags the job wrong-tip (its dose records leave the setup's evidence and dot
     * table), skips its waiting coupons, and for every record of the job whose version is still its setup's newest
     * of that type writes a version restoring the previous own dose fields. Returns the versions written.
     */
    public synchronized List<PadType> markWrongTip(String jobId, String operator) throws SQLException {
        List<PadCalibrationRecord> records = db.padCalibrationsOfJob(jobId);
        if (records.isEmpty()) {
            throw new EngineRefusal(404, "not-found", "There is no calibration of job " + jobId + ".");
        }
        List<String> jobs = new ArrayList<>();
        jobs.add(jobId);
        for (PadCalibrationRecord r : records) {
            if (PadCalibrationRecord.METHOD_BY_HAND.equals(r.method)) {
                for (Long id : r.evidence) {
                    DoseRecord d = db.doseRecord(id);
                    if (d != null && d.jobId != null && !jobs.contains(d.jobId)) {
                        jobs.add(d.jobId);
                    }
                }
            }
        }
        String setup = records.get(0).setupKey;
        for (String j : jobs) {
            db.insertCalFlag(j, setup, "wrong-tip", "made with another tip than the " + setup, operator);
        }
        DispenseConfig c = cfg;
        long now = System.currentTimeMillis();
        DoseRecord.Epochs ep = new DoseRecord.Epochs(getControllerConnectedAtMs(), isSimulatorConfirmed());
        for (DoseRecord coupon : db.pendingCoupons(now - (long) (c.padCalibration.doseRecordMaxAgeH * 3600e3))) {
            if (coupon.jobId != null && jobs.contains(coupon.jobId)) {
                skipCoupon(coupon, "the calibration was made with another tip", operator, coupon.jobId);
            }
        }
        List<PadType> restored = new ArrayList<>();
        for (PadCalibrationRecord r : records) {
            if (r.versionTo == null) {
                continue;
            }
            String s = r.setupKey;
            PadType newest = db.newestOwnVersion(r.typeKey, s);
            if (newest == null || newest.version != r.versionTo) {
                continue; // a later version of that setup stays
            }
            PadType previous = null;
            for (PadType h : db.padTypeHistory(r.typeKey)) {
                if (h.version < r.versionTo && org.openpnp.instancelabs.padprofile.Tip.setupOf(h.tipKey).equals(s)) {
                    previous = h;
                }
            }
            PadType pad = db.currentPadType(r.typeKey, s);
            PadType w = pad.copy();
            if (previous != null) {
                w.status = previous.status;
                w.dotSteps = previous.dotSteps;
                w.dotsPerPad = previous.dotsPerPad;
                w.calAreaMm2 = previous.calAreaMm2;
                w.doseFp = previous.doseFp;
                w.massPerPadMg = previous.massPerPadMg;
                w.origin = previous.origin;
            }
            else {
                w.status = PadTypeStatus.NOT_STARTED;
                w.dotSteps = null;
                w.dotsPerPad = null;
                w.calAreaMm2 = null;
                w.doseFp = null;
                w.massPerPadMg = null;
                w.origin = null;
            }
            w.tipKey = s;
            w.notes = "restored: calibration " + r.id + " was made with another tip";
            w.operator = operator;
            PadType stored = db.insertPadTypeVersion(w);
            restored.add(stored);
        }
        tipCache = null;
        db.event(null, "WARN", TIP_EVENT, "job " + jobId + " was made with another tip than the " + setup
                + ": its records left the evidence; restored " + restored);
        return restored;
    }

    /** Appends a new version of a per-type dot layout rule. */
    public PadRule savePadRule(PadRule r) throws SQLException {
        return db.insertPadRule(r);
    }

    public Calibration getCurrentCalibration() throws SQLException {
        return db.currentCalibration(System.currentTimeMillis());
    }

    public List<DotLadderRow> getDotLadder(long calibrationId) throws SQLException {
        return db.dotLadder(calibrationId);
    }

    public Calibration recordScaleShots(ScaleCalibration.ShotsEntry e) throws SQLException {
        Calibration c = ScaleCalibration.recordShots(db, e);
        db.event(null, "INFO", "CALIBRATION", "scale shots -> " + c);
        return c;
    }

    public DotLadderRow recordScaleDots(ScaleCalibration.DotsEntry e) throws SQLException {
        DotLadderRow r = ScaleCalibration.recordDots(db, e);
        db.event(null, "INFO", "CALIBRATION", "scale dots: calibration #" + e.calibrationId + " rotation "
                + e.rotationDeg + " -> " + r.mgPerDotMean + " mg/dot");
        return r;
    }

    // ------------------------------------------------------------------------------------------
    // Planning
    // ------------------------------------------------------------------------------------------

    public DispensePlanner newPlanner() throws SQLException {
        DispenseConfig.PurgeRow row = cfg.purgeRow;
        return new DispensePlanner(cfg, padTypeResolver()).withRules(db.currentPadRules())
                .withSurface(planeOf(cfg, cfg.boardSurfaceName)).withTip(mountedTip())
                .withPurgeSpots(k -> row.isDefined() && k < row.spotsLeft() ? row.spot(row.usedSpots + k) : null);
    }

    /** Plans a board location; the path starts at the end of the purge row (or N2's current XY). */
    public DispensePlan planBoard(BoardLocation bl) throws Exception {
        return planBoard(bl, null);
    }

    /**
     * As above, through a fiducial registration ({@code reg} null: none): the plan's pads follow its
     * board -> machine affine, the board Z stays the nominal seat's. Refused when the board top Z
     * cannot be an N1 contact height ({@link #boardZProblem}).
     */
    public DispensePlan planBoard(BoardLocation bl, BoardRegistration reg) throws Exception {
        requireAvailable();
        String zProblem = boardZProblem(cfg, bl.getGlobalLocation().convertToUnits(LengthUnit.Millimeters).getZ());
        if (zProblem != null) {
            throw new IllegalStateException(zProblem);
        }
        BoardLocation at = reg == null ? bl : reg.applyTo(bl);
        double[] start = planStart();
        DispensePlan plan = newPlanner().planBoard(at, start[0], start[1]);
        plan.boardFingerprint = boardFingerprint(at);
        plan.boardRegistration = boardRegistration(at);
        db.insertPadPlan(plan);
        db.event(null, "INFO", "PLAN", plan.summary());
        return plan;
    }

    /** Loads a board file and plans it at the given board location (top side up). */
    public DispensePlan planBoardFile(File boardFile, Location boardLocation) throws Exception {
        if (!boardFile.isFile()) {
            // getBoard would write an empty board file there and list it
            throw new FileNotFoundException("Board file not found: " + boardFile.getPath());
        }
        Board board = EdtRunner.call(() -> configuration.getBoard(boardFile), 10_000);
        BoardLocation bl = new BoardLocation(board);
        bl.setLocation(boardLocation);
        return planBoard(bl);
    }

    /**
     * Why a board top Z cannot be right, or null: more than {@link #MAX_BOARD_Z_FROM_ANCHOR_MM} from
     * the needle-frame surface. Without a needle frame there is nothing to compare it with (I4 refuses
     * the run then).
     */
    public static String boardZProblem(DispenseConfig c, double boardZ) {
        DispenseConfig.NeedleAnchor a = c.needleAnchor;
        if (a == null) {
            return null;
        }
        // §22.3: a frame whose offset o is near N1's would let a height taken with the needle pass: min(2.0, o - 0.5)
        double o = Math.abs(a.offsetMm());
        double limit = Math.min(MAX_BOARD_Z_FROM_ANCHOR_MM, o - 0.5);
        if (Math.abs(boardZ - a.surfaceZMm) <= limit + 1e-9) {
            return null;
        }
        return String.format(Locale.ROOT, "the board top Z %.3f is %.2f mm from the needle-frame surface %.3f (at most "
                + "%.1f mm: the board and the blank sit on the same bed). The board top Z is the height where N1 "
                + "touches the board; a height taken with the needle is about %.1f mm off. Set the board Z again.",
                boardZ, Math.abs(boardZ - a.surfaceZMm), a.surfaceZMm, Math.max(0, limit), o);
    }

    private double[] planStart() {
        DispenseConfig.PurgeRow row = cfg.purgeRow;
        if (cfg.profile.purgeDots > 0 && row.isDefined()) {
            return row.spot(Math.min(row.spots - 1, row.usedSpots + cfg.profile.purgeDots - 1));
        }
        Location l = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
        return new double[] {l.getX(), l.getY()};
    }

    /**
     * The next {@code profile.purgeDots} unused spots of the purge row as dot specs (Z from the purge
     * surface plane, the profile's gap). Empty if the profile has no purge or the row cannot supply
     * them (I9 refuses the run then).
     */
    public static List<GcodeStreamBuilder.DotSpec> purgeSpecs(DispenseConfig c) {
        return purgeSpecs(c, c.profile.purgeDots);
    }

    /**
     * The purge-row spots a board run of {@code plan} lays: the initial purge and the phase and flush
     * dots before every further dose block ({@link DoseBlocks#purgeSpotsNeeded}).
     */
    public static List<GcodeStreamBuilder.DotSpec> purgeSpecs(DispenseConfig c, DispensePlan plan) {
        return purgeSpecs(c, DoseBlocks.purgeSpotsNeeded(plan, c));
    }

    /**
     * The next {@code n} unused spots of the purge row as dot specs (their rotation is the profile's;
     * the builder turns each by its block's dose). Empty if {@code n} is 0 or the row cannot supply
     * them (I9 refuses the run then).
     */
    public static List<GcodeStreamBuilder.DotSpec> purgeSpecs(DispenseConfig c, int n) {
        List<GcodeStreamBuilder.DotSpec> l = new ArrayList<>();
        DispenseConfig.PurgeRow row = c.purgeRow;
        SurfacePlane plane = planeOf(c, row.surfaceName);
        if (n <= 0 || !row.isDefined() || row.spotsLeft() < n || plane == null) {
            return l;
        }
        for (int i = 0; i < n; i++) {
            int k = row.usedSpots + i;
            double[] xy = row.spot(k);
            double z = plane.z(xy[0], xy[1]) + c.needleZOffsetMm + c.gapMm();
            l.add(new GcodeStreamBuilder.DotSpec(xy[0], xy[1], z, z + c.hopHeightMm, c.profile.netDeg(c),
                    GcodeStreamBuilder.PURGE_INDEX, "purge#" + k));
        }
        return l;
    }

    /**
     * Pre-flight interlocks for a plan (I3, I4 with the board surface, I5, I7 estimate, I9, plan
     * rules); without a plan the generic checks (needle frame, touch-off) only.
     */
    public InterlockReport checkInterlocks(DispensePlan plan, double estimatedPressurisedSeconds) {
        return checkInterlocks(plan, estimatedPressurisedSeconds, false, false);
    }

    /**
     * As above for a run that homes first ({@code homeFirst}: "not homed" is a note) and checks Z
     * itself after the home ({@code zCheckFirst}: Z data older than the home are a note).
     */
    public InterlockReport checkInterlocks(DispensePlan plan, double estimatedPressurisedSeconds, boolean homeFirst,
            boolean zCheckFirst) {
        return Interlocks.check(interlockInputs(cfg, plan, estimatedPressurisedSeconds, boardScope(cfg, plan), homeFirst,
                zCheckFirst));
    }

    /** Pre-flight interlocks for a calibration / shots / hold scope (no plan rules, no I9-I11). */
    public InterlockReport checkInterlocks(Interlocks.Scope scope, double estimatedPressurisedSeconds) {
        return Interlocks.check(interlockInputs(cfg.copy(), null, estimatedPressurisedSeconds, scope, false, false));
    }

    /** CONFIG and MACHINE only (Register board, the surface probe, the Z check): they never dispense. */
    public InterlockReport checkBasicInterlocks(boolean homeFirst) {
        return Interlocks.checkBasic(interlockInputs(cfg.copy(), null, 0, null, homeFirst, false));
    }

    /** I4 scope of a board run: the board surface under every dot, the purge surface under the purge. */
    public static Interlocks.Scope boardScope(DispenseConfig c, DispensePlan plan) {
        Interlocks.Scope sc = new Interlocks.Scope();
        if (plan == null) {
            return sc;
        }
        sc.surfaceName = c.boardSurfaceName;
        for (DispensePlan.PlannedDot d : plan.dots) {
            sc.surfaceTargets.add(new double[] {d.xMm, d.yMm});
        }
        sc.checkPlanSurface = true;
        DispenseConfig.PurgeRow row = c.purgeRow;
        int purge = DoseBlocks.purgeSpotsNeeded(plan, c);
        if (purge > 0 && row.isDefined()) {
            List<double[]> t = new ArrayList<>();
            for (int i = 0; i < Math.min(purge, row.spotsLeft()); i++) {
                t.add(row.spot(row.usedSpots + i));
            }
            sc.moreSurfaces.put(row.surfaceName, t);
        }
        return sc;
    }

    /**
     * I4 scope of shots at a place: a place that stands for a needle-frame surface (waste, test-dot
     * spot) is checked against the frame; any other place (a cup on the scale) only gets the floor.
     */
    public static Interlocks.Scope shotsScope(String place, boolean referenceSurface) {
        Interlocks.Scope sc = new Interlocks.Scope();
        if (referenceSurface) {
            sc.referencePlaces.add(place);
        }
        sc.dispensePlaces.add(place);
        return sc;
    }

    /** I4 scope of a dot ladder / touch-off on the blank: its plane under every dot. */
    public static Interlocks.Scope blankScope(DispenseConfig c, String blankName, boolean primeAtWaste,
            boolean touchOff, List<GcodeStreamBuilder.DotSpec> specs) {
        Interlocks.Scope sc = new Interlocks.Scope();
        sc.touchOffWaiver = touchOff;
        sc.referencePlaces.add(blankName);
        if (primeAtWaste) {
            sc.referencePlaces.add(c.wasteLocationName);
            sc.dispensePlaces.add(c.wasteLocationName);
        }
        sc.surfaceName = blankName;
        if (specs != null) {
            for (GcodeStreamBuilder.DotSpec d : specs) {
                sc.surfaceTargets.add(new double[] {d.x, d.y});
            }
        }
        return sc;
    }

    private Interlocks.Inputs interlockInputs(DispenseConfig c, DispensePlan plan, double est, Interlocks.Scope scope,
            boolean homeFirst, boolean zCheckFirst) {
        Interlocks.Inputs in = new Interlocks.Inputs();
        Machine m = configuration.getMachine();
        in.cfg = c;
        in.machineEnabled = m != null && m.isEnabled();
        in.machineHomed = m != null && m.isHomed();
        Nozzle n = nozzle; // read once: a config update resolves it again on another thread
        in.nozzleName = n == null ? cfg.nozzle : n.getName();
        in.compatibleNozzleTips = new ArrayList<>();
        if (n != null) {
            for (NozzleTip t : n.getCompatibleNozzleTips()) {
                in.compatibleNozzleTips.add(t.getName());
            }
            NozzleTip loaded = n.getNozzleTip();
            in.loadedNozzleTip = loaded == null ? null : loaded.getName();
        }
        in.pressureSensor = pressureSensor;
        in.plan = plan;
        try {
            in.tip = mountedTip();
        }
        catch (Exception e) {
            in.tip = null; // unknown: R1 is still checked against cfg.tip
        }
        in.xyUncheckedWhy = needleXyProblem(c);
        in.xyChecked = in.xyUncheckedWhy == null;
        in.tipOnAtMs = c.needleChangedAtMs;
        if (plan != null) {
            try {
                PadTypeResolver now = new PadTypeResolver(TipSetups.typesFor(db, c.tip.key), db.packageMappings())
                        .withPastePartial(db.pastePartialPackages()).withSetup(c.tip.key, c.tip.setupVersion);
                in.padTypesNow = new java.util.HashMap<>();
                for (PadType t : now.types()) {
                    in.padTypesNow.put(t.typeKey, t);
                }
                in.padTypesFingerprintNow = now.fingerprint();
            }
            catch (Exception e) {
                // §22.2: a read that fails refuses the run (its version checks cannot be made)
                in.padTypesReadError = String.valueOf(e.getMessage());
            }
            in.setupDiff = setupDiff(c, plan);
            in.tipOnAtMs = mountedAtMs(c);
        }
        in.estimatedPressurisedSeconds = est;
        in.opticalCheckAvailable = imager() != null && imager().isAvailable();
        in.scope = scope == null ? new Interlocks.Scope() : scope;
        in.controllerConnectedAtMs = homeTracker.getConnectedAtMs();
        in.boardRun = plan != null;
        in.purgeDots = plan == null ? 0 : DoseBlocks.purgeSpotsNeeded(plan, c);
        in.homeFirst = homeFirst;
        in.zCheckFirst = zCheckFirst;
        in.lastHomeAtMs = homeTracker.getLastHomeAtMs();
        in.zCheck = homeTracker.getZCheck();
        in.simulatorConfirmed = isSimulatorConfirmed();
        return in;
    }

    /** The corrections of the mounted setup since the plan's version ("OD 0.72 → 0.90 mm"), or null. */
    private String setupDiff(DispenseConfig c, DispensePlan plan) {
        if (plan.setupKey == null || !plan.setupKey.equals(c.tip.key) || plan.setupVersion == null
                || plan.setupVersion == c.tip.setupVersion) {
            return null;
        }
        try {
            List<String> l = TipSetups.corrections(db.tipSetupVersion(c.tip.key, plan.setupVersion),
                    db.tipSetupVersion(c.tip.key, c.tip.setupVersion));
            return l.isEmpty() ? null : String.join(", ", l);
        }
        catch (SQLException e) {
            return null;
        }
    }

    /** When the mounted setup was mounted (its newest mount or reconcile row), else its needle change; or null. */
    private Long mountedAtMs(DispenseConfig c) {
        try {
            for (TipSetups.Mount m : db.tipMounts(20)) {
                if (m.setupKey.equals(c.tip.key) && (TipSetups.Mount.MOUNT.equals(m.kind)
                        || TipSetups.Mount.UNDO.equals(m.kind))) {
                    return m.createdAtMs;
                }
            }
        }
        catch (SQLException e) {
            // the needle change's time below
        }
        return c.needleChangedAtMs;
    }

    /** The test dot's place next to the waste (the API keeps it derived from the waste place). */
    public static final String WASTE_TEST_DOT = "waste-test-dot";

    /** A scope that dispenses only at the waste place or its test-dot spot (prime, the test dot), §22.3. */
    static boolean onlyAtTheWaste(DispenseConfig c, Interlocks.Scope sc) {
        if (sc.referencePlaces.isEmpty() || sc.surfaceName != null || !sc.moreSurfaces.isEmpty() || sc.touchOffWaiver) {
            return false;
        }
        for (String p : sc.referencePlaces) {
            if (!p.equals(c.wasteLocationName) && !p.equals(WASTE_TEST_DOT)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Where the needle lands as the engine moves every target (tip setups design §22.3): the mounted setup's landing
     * when it was set under N2's head offset now (else none: a landing check then searches around 0, 0), plus the
     * trim. The landing check's expected landing E is this landing.
     */
    public double[] landingInEffect() {
        return landingInEffect(cfg);
    }

    private double[] landingInEffect(DispenseConfig c) {
        DispenseConfig.NeedleLanding l = c.needleLanding;
        if (l == null || !sameHeadOffset(l.headOffsetXMm, l.headOffsetYMm)) {
            return new double[] {0, 0};
        }
        return new double[] {l.dxMm, l.dyMm};
    }

    /** The mounted tip's landing + trim and R7's paste per step for a program built from {@code c}. */
    GcodeStreamBuilder.TipXy tipXy(DispenseConfig c) {
        GcodeStreamBuilder.TipXy x = new GcodeStreamBuilder.TipXy();
        double[] l = landingInEffect(c);
        x.dxMm = l[0] + c.needleXyTrim.dxMm;
        x.dyMm = l[1] + c.needleXyTrim.dyMm;
        try {
            x.feedMgPerStep = DoseBlocks.feedMgPerStep(c, mountedTip());
        }
        catch (SQLException e) {
            x.feedMgPerStep = DoseBlocks.feedMgPerStep(c, PadInstances.specTip(c.tip));
        }
        return x;
    }

    // ------------------------------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------------------------------

    public Future<RunResult> runBoard(BoardLocation bl) throws Exception {
        return runPlan(planBoard(bl));
    }

    /**
     * Streams a plan on the machine thread as one pressure session (bench recipe): a home first when
     * one is needed (homeBeforeRun) and a Z check when Z data predate the last home, the clean-pad
     * check with the pressure off (I10), then pump/valve ON, the purge row, a cross to the work with
     * no stop, every dot, vent. The Future never throws for dispense failures; see status.
     */
    public Future<RunResult> runPlan(DispensePlan plan) throws Exception {
        requireAvailable();
        RunResult r = newRun("board", plan.planId, plan.boardName, 0, plan.dots.size());
        return submit(r, () -> {
            DispenseConfig c = cfg;
            if (plan.profileFingerprint == null || !plan.profileFingerprint.equals(c.profile.fingerprint())) {
                // before the session: nothing at all is sent to the controller
                r.startedAtMs = System.currentTimeMillis();
                r.status = RunResult.Status.REFUSED;
                r.message = "the dispense profile changed since this plan was made: plan the board again";
                return r;
            }
            Session s = new Session(c, r);
            r.profile = c.profile.copy();
            db.event(r.runId, "INFO", "PROFILE", new com.google.gson.Gson().toJson(r.profile));
            // Every check but the home and the Z check first (those two are notes): a refusal moves nothing.
            HomeTracker.Plan hp = homeTracker.plan(true, configuration.getMachine().isHomed(), c.homeBeforeRun);
            ZCheckPlan zp = zCheckPlan(plan, hp.needed);
            if (zp.needed && zp.reference == null) {
                return s.refuse(noZReference(hp.needed, zp.homeAtMs));
            }
            r.interlocks = Interlocks.check(interlockInputs(c, plan, 0, boardScope(c, plan), hp.needed, zp.needed));
            if (!r.interlocks.ok()) {
                return s.refuse(r.interlocks.toString());
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("stage", hp.needed ? "home" : zp.needed ? "z check" : "dispense");
            d.put("home", homeDetail(hp));
            detail(r, d);
            HomeReport home = new HomeReport();
            homeIfNeeded(s, home, s.exec::isAbortRequested);
            r.homedFirst = home.done;
            r.zCheck = zCheckIfNeeded(s, plan.boardRegistration, boardScope(c, plan), s.exec::isAbortRequested);
            d.put("stage", "dispense");
            d.put("zCheck", r.zCheck);
            detail(r, d);
            // Interlocks first (I4 Z frame before any program is built), then again with the estimate (I7).
            r.interlocks = Interlocks.check(interlockInputs(c, plan, 0, boardScope(c, plan), false, false));
            if (!r.interlocks.ok()) {
                return s.refuse(r.interlocks.toString());
            }
            List<GcodeStreamBuilder.DotSpec> purge = purgeSpecs(c, plan);
            GcodeStreamBuilder.TipXy xy = tipXy(c);
            GcodeProgram estimate = GcodeStreamBuilder.forPlan(c, s.mapper, plan, purge, bZeroed.get(), s.keepalive,
                    0L, xy);
            Interlocks.Inputs withEstimate = interlockInputs(c, plan, estimate.estimatedPressurisedSeconds,
                    boardScope(c, plan), false, false);
            withEstimate.programBlocks = estimate.blocks; // I6-feed: every block's paste demand
            r.interlocks = Interlocks.check(withEstimate);
            if (!r.interlocks.ok()) {
                return s.refuse(r.interlocks.toString());
            }
            if (c.cleanPadCheck.enabled) {
                phase(r, "I10 clean pads: top camera at every dot (pressure off)");
                r.cleanCheck = PadCleanReport.of(c.cleanPadCheck);
                cleanCheck(c, plan, r.cleanCheck, s.exec::isAbortRequested);
                if (!r.cleanCheck.ok) {
                    return s.refuse(r.cleanCheck.refusal());
                }
            }
            GcodeProgram p = s.runSegment(() -> {
                Long count = s.readBCount();
                if (count == null && DoseBlocks.anyPartial(plan, c)) {
                    throw new RunRefusedException("I11: the controller did not report the B step count (M114 "
                            + "Count): the start angle of a partial-turn dose cannot be set. Nothing was sent.",
                            null);
                }
                return GcodeStreamBuilder.forPlan(c, s.mapper, plan, purge, bZeroed.get(), s.keepalive, count, xy);
            }, "dispensing", () -> reservePurgeSpots(purge.size(), r.runId));
            r.purgeDots = p.purgeDotCount;
            r.bCountAtStart = p.bCountAtStart;
            r.workStartAngleDeg = p.workStartAngleDeg;
            return s.ok();
        });
    }

    /**
     * Clean-pad guard alone (camera only, no pressure): the bright share at every dot of a plan, so
     * the operator can wipe before a run. A run repeats it right before the pressure goes on.
     */
    public Future<PadCleanReport> checkCleanPads(DispensePlan plan) throws Exception {
        requireAvailable();
        RunResult r = newRun("clean-check", plan.planId, plan.boardName, 0, 0);
        PadCleanReport rep = PadCleanReport.of(cfg.cleanPadCheck);
        cameraTourAbort = false;
        return submitResult(r, rep, () -> {
            r.startedAtMs = System.currentTimeMillis();
            activeRun = r;
            phase(r, "I10 clean pads: top camera at every dot (pressure off)");
            cleanCheck(cfg, plan, rep, () -> cameraTourAbort);
            r.cleanCheck = rep;
            r.status = RunResult.Status.OK;
            r.message = rep.ok ? "all " + rep.pads.size() + " targets clean" : rep.refusal();
        });
    }

    /**
     * A picture of the mounted tip from below (tip setups design §22.4), an engine task of kind {@code tip-picture}:
     * N2 to the bottom camera's X/Y at the balanced travel height (refused when that is outside N2's safe Z zone,
     * unhomed, under the pressure hold, after a STOP, or for a setup that is not mounted), a lit capture, then the
     * photo stored (kind tip) and linked to the request's records unless the needle was changed meanwhile.
     */
    public Future<TipPictureResult> takeTipPicture(TipPictureRequest req) throws Exception {
        requireAvailable();
        TipCamera cam = tipCamera();
        if (cam == null || !cam.isAvailable()) {
            throw new EngineRefusal(409, "refused", "No bottom camera: OpenPnP has no camera looking up.");
        }
        String setup = req.setupKey != null ? req.setupKey : cfg.tip.key;
        if (!setup.equals(cfg.tip.key)) {
            throw new EngineRefusal(409, "refused", "Only the mounted tip can be photographed: mount the "
                    + nameOf(setup) + " first.");
        }
        if (pressureHoldActive.getAsBoolean()) {
            throw new EngineRefusal(409, "refused", "Switch the pressure off first: the head moves to the bottom camera.");
        }
        RunResult r = newRun("tip-picture", null, "tip picture", 0, 0);
        TipPictureResult res = new TipPictureResult();
        res.run = r;
        cameraTourAbort = false;
        return submitResult(r, res, () -> {
            DispenseConfig c = cfg;
            r.startedAtMs = System.currentTimeMillis();
            activeRun = r;
            refuseIfStoppedSince(req.stopEpoch);
            if (pressureHoldActive.getAsBoolean()) {
                throw refused("Switch the pressure off first: the head moves to the bottom camera.");
            }
            Machine m = configuration.getMachine();
            if (!m.isHomed()) {
                throw refused("Home first: the head moves to the bottom camera.");
            }
            ReferenceMachine rm = (ReferenceMachine) m;
            OpenPnpRawMapper mapper = new OpenPnpRawMapper(rm, nozzle, c.bAxisName);
            double[] at = cam.locationMm();
            double[] zone = mapper.zSafeZoneRaw();
            double raw = mapper.toRaw(at[0], at[1], c.travelSafeZMm).z;
            if (zone != null && (!Double.isNaN(zone[0]) && raw < zone[0] - 1e-6
                    || !Double.isNaN(zone[1]) && raw > zone[1] + 1e-6)) {
                throw refused(String.format(Locale.ROOT, "N2's safe Z zone (%s–%s mm) does not hold the balanced "
                        + "travel height %.1f mm: the picture would move N1 down.", Double.isNaN(zone[0]) ? "-"
                                : String.format(Locale.ROOT, "%.1f", zone[0]), Double.isNaN(zone[1]) ? "-"
                                        : String.format(Locale.ROOT, "%.1f", zone[1]), c.travelSafeZMm));
            }
            phase(r, "tip picture: moving to the bottom camera");
            Location here = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
            org.openpnp.util.MovableUtils.moveToLocationAtSafeZ(nozzle, new Location(LengthUnit.Millimeters, at[0],
                    at[1], c.travelSafeZMm, here.getRotation()));
            m.getMotionPlanner().waitForCompletion(null, CompletionType.WaitForStillstand);
            if (cameraTourAbort) {
                throw new DispenseExecutor.AbortedException("STOP during the move to the bottom camera");
            }
            refuseIfStoppedSince(req.stopEpoch);
            phase(r, "tip picture: capture");
            DotImager.Frame f = cam.capture();
            refuseIfStoppedSince(req.stopEpoch);
            if (cameraTourAbort) {
                throw new DispenseExecutor.AbortedException("STOP during the tip picture");
            }
            DispenseConfig now = cfg;
            if (!now.tip.key.equals(setup) || !java.util.Objects.equals(now.needleChangedAtMs, req.needleSinceMs)) {
                res.ok = false;
                res.message = "The needle was changed during the picture: it was not stored.";
                r.status = RunResult.Status.REFUSED;
                r.message = res.message;
                return;
            }
            if (f == null || f.image == null) {
                throw refused("the bottom camera gave no frame");
            }
            PhotoStore.Input in = new PhotoStore.Input();
            in.image = f.image;
            in.kind = Photo.KIND_TIP;
            in.source = Photo.SOURCE_BOTTOM_CAMERA;
            in.setupKey = setup;
            in.needleSinceMs = req.needleSinceMs;
            in.mmPerPx = f.mmPerPxX > 0 ? f.mmPerPxX : null;
            in.caption = "From below at " + ZCheck.clock(System.currentTimeMillis()) + ", at travel height (may be "
                    + "soft): the " + now.tip.name;
            in.calibrationIds = req.calibrationIds == null ? new ArrayList<>() : req.calibrationIds;
            in.simulated = isSimulatorConfirmed();
            in.operator = req.operator;
            res.photo = storePhoto(in);
            res.ok = true;
            res.message = "picture " + res.photo.id + " of the " + now.tip.name;
            db.event(r.runId, "INFO", TIP_EVENT, "picture " + res.photo.id + " of the " + now.tip.name);
            r.status = RunResult.Status.OK;
            r.message = res.message;
        }, () -> {
            if (r.status != RunResult.Status.OK && res.message == null) {
                res.message = r.message;
            }
        });
    }

    /** Top camera at every distinct dot of the plan; fills the report (machine thread). */
    private void cleanCheck(DispenseConfig c, DispensePlan plan, PadCleanReport rep,
            java.util.function.BooleanSupplier aborted) throws Exception {
        DotImager im = imager();
        if (im == null || !im.isAvailable()) {
            throw new RunRefusedException("I10: no top camera / dot vision available for the clean-pad check", null);
        }
        rep.planId = plan.planId;
        java.util.Map<String, DispensePlan.PlannedPad> pads = new java.util.HashMap<>();
        for (DispensePlan.PlannedPad pp : plan.pads) {
            pads.put(pp.padId, pp);
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (DispensePlan.PlannedDot d : plan.dots) {
            if (!seen.add(String.format(java.util.Locale.ROOT, "%.3f,%.3f", d.xMm, d.yMm))) {
                continue;
            }
            if (aborted.getAsBoolean()) {
                throw new DispenseExecutor.AbortedException("aborted during the clean-pad check");
            }
            PadCleanReport.Pad pad = new PadCleanReport.Pad();
            pad.padId = d.padId;
            pad.dotIndex = d.index;
            pad.xMm = d.xMm;
            pad.yMm = d.yMm;
            DispenseConfig.CleanPadCheck box = cleanBox(c.cleanPadCheck, pads.get(d.padId));
            pad.boxWMm = box.boxWMm;
            pad.boxHMm = box.boxHMm;
            DotImager.Frame f = im.capture(d.xMm, d.yMm);
            try {
                pad.brightFraction = im.brightFraction(f, box);
                pad.clean = pad.brightFraction >= c.cleanPadCheck.minBrightFraction;
            }
            catch (IllegalArgumentException e) {
                pad.error = e.getMessage();
                pad.clean = false;
            }
            rep.pads.add(pad);
        }
        rep.checkedAtMs = System.currentTimeMillis();
        rep.ok = !rep.pads.isEmpty() && rep.dirtyPadIds().isEmpty();
        db.event(null, rep.ok ? "INFO" : "WARN", "I10", rep.ok ? "clean-pad check: all " + rep.pads.size()
                + " targets clean" : rep.refusal());
    }

    /**
     * The clean-pad box for one dot: the bench box (0.806 x 0.807 mm, validated on 0.80 x 0.95 mm 0603
     * pads) where the pad (or the dot's grid cell) is at least about that large, otherwise 0.05 mm
     * inside its edges, so a clean small pad is not read as dirty because the box spills onto the
     * solder mask. Pads at other than a multiple of 90 deg get the square that fits inside.
     */
    static DispenseConfig.CleanPadCheck cleanBox(DispenseConfig.CleanPadCheck s, DispensePlan.PlannedPad pad) {
        DispenseConfig.CleanPadCheck b = new DispenseConfig.CleanPadCheck();
        b.enabled = s.enabled;
        b.grayLevel = s.grayLevel;
        b.minBrightFraction = s.minBrightFraction;
        b.boxWMm = s.boxWMm;
        b.boxHMm = s.boxHMm;
        if (pad == null || !(pad.wMm > 0 && pad.hMm > 0)) {
            return b;
        }
        int[] grid = DotSizer.bestGrid(Math.max(1, pad.dotCount), pad.wMm, pad.hMm);
        double w = pad.wMm / grid[0], h = pad.hMm / grid[1];
        double r = Math.toRadians(pad.rotationDeg);
        double c = Math.abs(Math.cos(r)), sn = Math.abs(Math.sin(r));
        double ex, ey;
        if (sn < 0.09) { // ~0/180 deg (within 5 deg)
            ex = w;
            ey = h;
        }
        else if (c < 0.09) { // ~90/270 deg
            ex = h;
            ey = w;
        }
        else {
            ex = ey = Math.min(w, h) / (c + sn);
        }
        b.boxWMm = fitBox(s.boxWMm, ex);
        b.boxHMm = fitBox(s.boxHMm, ey);
        return b;
    }

    private static double fitBox(double box, double extent) {
        return extent >= box - 0.02 ? box : Math.max(0.15, extent - 0.10);
    }

    /** Marks the next n purge spots used (before streaming: an aborted purge leaves them dirty). */
    private synchronized void reservePurgeSpots(int n, String runId) {
        if (n <= 0) {
            return;
        }
        try {
            DispenseConfig c = cfg.copy();
            c.purgeRow.usedSpots += n;
            updateConfig(c);
            db.event(runId, "INFO", "I9", "purge spots " + (c.purgeRow.usedSpots - n) + ".." + (c.purgeRow.usedSpots - 1)
                    + " used (" + c.purgeRow.spotsLeft() + " left)");
        }
        catch (IOException e) {
            throw new IllegalStateException("could not record the used purge spots: " + e.getMessage(), e);
        }
    }

    /** The Marlin step count of B in an M114 reply ("... Count X:.. B:1234"), or null. */
    static Long parseBCount(List<String> lines, String letterB) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("Count\\b.*?(?:^|\\s)"
                + java.util.regex.Pattern.quote(letterB) + ":(-?\\d+)");
        for (String line : lines) {
            java.util.regex.Matcher m = p.matcher(line);
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
        }
        return null;
    }

    /**
     * Dispenses `count` shots of rotationDeg at a named location: prime / test dot at the waste pad
     * ({@code referenceSurface}: the place is checked against the needle frame) or shots into a
     * cup on the scale. I4 applies in full (review finding: it was waived for prime, test dot and
     * shots, so they dispensed with a stale offset after a needle change).
     */
    public Future<RunResult> runShots(String locationName, double rotationDeg, int count, boolean referenceSurface)
            throws Exception {
        requireAvailable();
        DispenseConfig.NamedLocation loc = requireLocation(locationName);
        RunResult r = newRun("shots", null, locationName, getCurrentCalibration().id, count);
        return submit(r, () -> {
            DispenseConfig c = cfg;
            String reserved = couponReservation(locationName); // §7.5: a coupon waiting for its readings takes no dots
            if (reserved != null) {
                throw refused(reserved);
            }
            Session s = new Session(c, r);
            Interlocks.Scope scope = shotsScope(locationName, referenceSurface);
            if (!s.preflight(scope, 0)) {
                return r;
            }
            List<GcodeStreamBuilder.DotSpec> specs = new ArrayList<>();
            double z = loc.z + c.needleZOffsetMm + c.gapMm();
            for (int i = 0; i < count; i++) {
                specs.add(new GcodeStreamBuilder.DotSpec(loc.x, loc.y, z, z + c.hopHeightMm, rotationDeg, i, "shot"));
            }
            GcodeProgram p = GcodeStreamBuilder.forDots(c, s.mapper, specs, bZeroed.get(), s.keepalive, false, 0,
                    "shots x" + count + " @" + locationName, tipXy(c));
            if (!s.preflight(scope, p.estimatedPressurisedSeconds)) {
                return r;
            }
            s.runSegment(() -> p, "shots");
            return s.ok();
        });
    }

    /** Optical dot ladder at the blank location (plan §5.6); appends dot_ladder rows. */
    public Future<DotLadderCalibration.Result> runDotLadder(DotLadderCalibration.Request req) throws Exception {
        requireAvailable();
        String blankName = req.locationName == null ? cfg.blankLocationName : req.locationName;
        DispenseConfig.NamedLocation blank = requireLocation(blankName);
        Calibration cal = getCurrentCalibration();
        int n = req.rotationsDeg.length * req.repeats;
        RunResult r = newRun("dot-ladder", null, "blank", cal.id, n);
        DotLadderCalibration.Result res = new DotLadderCalibration.Result();
        res.calibrationId = cal.id;
        res.run = r;
        return submitResult(r, res, () -> {
            DispenseConfig c = cfg;
            Session s = new Session(c, r);
            SurfacePlane plane = planeOf(c, blankName);
            List<GcodeStreamBuilder.DotSpec> specs = DotLadderCalibration.layout(req, blank, c, plane);
            Interlocks.Scope scope = blankScope(c, blankName, req.primeAtWaste, false, specs);
            if (!s.preflight(scope, 0)) {
                return;
            }
            GcodeProgram p = GcodeStreamBuilder.forDots(c, s.mapper, specs, bZeroed.get(), s.keepalive,
                    req.primeAtWaste, 0, "dot ladder", tipXy(c));
            if (!s.preflight(scope, p.estimatedPressurisedSeconds)) {
                return;
            }
            List<Dot> found = s.imageDots(specs, req.primeAtWaste, 0, req.pitchMm / 2);
            res.rows.addAll(DotLadderCalibration.analyze(cal.id, req, specs, found, c.gapMm(), res.notes));
            for (DotLadderRow row : res.rows) {
                db.insertDotLadderRow(row);
            }
            s.ok();
        });
    }

    public static class TouchOffResult {
        public NeedleTouchOff.Analysis analysis;
        public Double previousOffsetMm;
        public Double newOffsetMm;
        public boolean applied;
        public RunResult run;
        /** Tip setups (§22.3): the frame question when the offset moved from the setup's previous one, else null. */
        public String frameQuestion;
    }

    /**
     * Needle touch-off by dot ladder at stepped gaps (plan §6.1). Applies the new offset if found and asked. Each dot
     * is found at its spot with the strings cut off; paste at a spot that is not one dot refuses the conclusion
     * ({@link NeedleTouchOff#analyze}). A pitch with no room between the dots is refused before anything is sent.
     */
    public Future<TouchOffResult> runNeedleTouchOff(NeedleTouchOff.Request req, boolean apply) throws Exception {
        requireAvailable();
        String blankName = req.locationName == null ? cfg.blankLocationName : req.locationName;
        DispenseConfig.NamedLocation blank = requireLocation(blankName);
        RunResult r = newRun("touch-off", null, "blank", getCurrentCalibration().id, req.steps);
        TouchOffResult res = new TouchOffResult();
        res.run = r;
        return submitResult(r, res, () -> {
            DispenseConfig c = cfg;
            String pitch = NeedleTouchOff.pitchProblem(req, c);
            if (pitch != null) {
                throw refused(pitch);
            }
            double dotMm = NeedleTouchOff.expectedDotDiameterMm(req, c);
            double steadyMm = NeedleTouchOff.steadyDotMm(req, c);
            Session s = new Session(c, r);
            SurfacePlane plane = planeOf(c, blankName);
            if (Double.isNaN(req.rotationDeg)) {
                req.rotationDeg = c.profile.netDeg(c);
            }
            List<GcodeStreamBuilder.DotSpec> specs = NeedleTouchOff.layout(req, blank, c, plane);
            // The named I4 waiver: this run sets the touched-off state. The needle frame (anchor) is
            // still required, and while the needle is not touched off the prime at the waste pad is
            // made higher, as the offset is only the coarse anchor value.
            Interlocks.Scope scope = blankScope(c, blankName, req.primeAtWaste, true, specs);
            if (!s.preflight(scope, 0)) {
                return;
            }
            double primeExtra = c.isNeedleTouchedOff() ? 0 : c.touchOffPrimeExtraClearanceMm;
            GcodeProgram p = GcodeStreamBuilder.forDots(c, s.mapper, specs, bZeroed.get(), s.keepalive,
                    req.primeAtWaste, primeExtra, "needle touch-off", tipXy(c));
            if (!s.preflight(scope, p.estimatedPressurisedSeconds)) {
                return;
            }
            List<SpotDot> seen = s.imageSpots(specs, req.primeAtWaste, primeExtra, NeedleTouchOff.cutMm(dotMm),
                    req.pitchMm / 2);
            res.analysis = NeedleTouchOff.analyze(NeedleTouchOff.gaps(req), seen, req.jumpRatio, steadyMm);
            res.previousOffsetMm = c.needleZOffsetMm;
            java.util.Map<String, Object> det = new java.util.LinkedHashMap<>();
            det.put("analysis", res.analysis);
            det.put("pitchMm", req.pitchMm);
            det.put("blank", blankName);
            det.put("blankPlane", plane == null ? null : java.util.Arrays.asList(plane.a, plane.b, plane.c));
            det.put("blankPlaneMaxResidualMm", plane == null ? null : plane.maxAbsResidualMm);
            det.put("dotZ", specs.stream().map(x -> x.dispenseZ).collect(java.util.stream.Collectors.toList()));
            String details = new com.google.gson.Gson().toJson(det);
            if (res.analysis.found()) {
                res.newOffsetMm = c.needleZOffsetMm + res.analysis.contactGapMm;
                if (apply) {
                    // under the needle the touch-off started with: a needle change meanwhile refuses it (§22.3)
                    NeedleFrameResult fr = setNeedleZOffset(res.newOffsetMm, "dot-ladder-touch-off", details,
                            c.needleChangedAtMs);
                    res.frameQuestion = fr.question;
                    res.applied = true;
                }
                else {
                    db.insertTouchOff("dot-ladder-touch-off (not applied)", res.newOffsetMm, c.needleZOffsetMm,
                            details, null);
                }
            }
            r.message = res.analysis.reason;
            s.ok();
        });
    }

    // ------------------------------------------------------------------------------------------
    // Home when needed, Z after a home, Register board, surface probe
    // (docs/instancelabs/AUTO_REGISTER_DESIGN.md)
    // ------------------------------------------------------------------------------------------

    /** Connects, disables, homes and the latest Z check. */
    public HomeTracker getHomeTracker() {
        return homeTracker;
    }

    /** Whether a task must home first now, and why (setting homeBeforeRun). */
    public HomeTracker.Plan homePlan() {
        Machine m = configuration.getMachine();
        return homeTracker.plan(m != null && m.isEnabled(), m != null && m.isHomed(), cfg.homeBeforeRun);
    }

    /**
     * Every driver is loopback TCP and the controller identified itself as OpenPnP's simulator on this
     * connection ({@link SimGuard#isSimulated}, or the answer this service noted at the connect).
     */
    public boolean isSimulatorConfirmed() {
        Machine m = configuration.getMachine();
        return SimGuard.isSimulated(m) || (Boolean.TRUE.equals(connectionSimulated) && SimGuard.isLoopbackOnly(m));
    }

    /** The API's manual pressure hold: engine tasks that move refuse to start while it is on. */
    public void setPressureHoldCheck(BooleanSupplier active) {
        pressureHoldActive = active == null ? () -> false : active;
    }

    /** The vacuum probe nozzle N1, or null if the machine has none. */
    public Nozzle getProbeNozzle() {
        Machine machine = configuration.getMachine();
        if (machine == null) {
            return null;
        }
        for (Head h : machine.getHeads()) {
            for (Nozzle n : h.getNozzles()) {
                if (PROBE_NOZZLE.equals(n.getName()) || PROBE_NOZZLE.equals(n.getId())) {
                    return n;
                }
            }
        }
        return null;
    }

    /**
     * N1's raw Z as OpenPnP knows it, whether it lies outside z1's safe zone (a STOP during a probe or a
     * board's dots left a tool low: the next home moves Z to the safe height first), and whether above it:
     * {z, outside, above}, or null without N1. On the shared Z axis N1 above its zone puts the needle low
     * (a STOP during board dots: N1 at 60.06, the needle at 2.94 over the pad).
     */
    public double[] probeNozzleZ() {
        Nozzle n = getProbeNozzle();
        Machine m = configuration.getMachine();
        if (n == null || !(m instanceof ReferenceMachine)) {
            return null;
        }
        try {
            OpenPnpRawMapper n1 = OpenPnpRawMapper.forProbeNozzle((ReferenceMachine) m, n, cfg.bAxisName);
            double z = n1.currentPlanned()[2];
            double[] zone = n1.zSafeZoneRaw();
            boolean above = zone != null && !Double.isNaN(zone[1]) && z > zone[1] + 1e-6;
            return new double[] {z, outsideSafeZone(zone, z) ? 1 : 0, above ? 1 : 0};
        }
        catch (Exception e) {
            return null;
        }
    }

    private static boolean outsideSafeZone(double[] zone, double z) {
        return zone != null && ((!Double.isNaN(zone[0]) && z < zone[0] - 1e-6) || (!Double.isNaN(zone[1])
                && z > zone[1] + 1e-6));
    }

    /** What a Z check after the last home needs for a task: its stale Z data and the first point it would touch. */
    public static final class ZCheckPlan {
        public boolean needed;
        public List<ZCheck.Stale> stale = new ArrayList<>();
        public Long homeAtMs;
        /**
         * The first stored point the check would touch, or null: not needed, or some stale datum has no
         * point in its Z frame left (the check is refused).
         */
        public ZCheck.Reference reference;
        public ZCheck.Result last;
        /**
         * N1 touches the check makes when the spots are bare: one per Z frame (home period) of the stale data,
         * so 2 when, e.g., the board was probed under a later home than the needle frame (0 when not needed).
         */
        public int touches;
    }

    /**
     * The Z check a board run of {@code plan} (null: the needle tasks on the blank) needs. With
     * {@code homeFirst} every Z datum predates the coming home.
     */
    public ZCheckPlan zCheckPlan(DispensePlan plan, boolean homeFirst) {
        DispenseConfig c = cfg;
        return zCheckPlan(c, plan == null ? needleTasksScope(c) : boardScope(c, plan),
                plan == null ? null : plan.boardRegistration, homeFirst);
    }

    /**
     * The Z check of a board run of a plan that may run now: tied to the board surface's registration (I4 refuses
     * any other), on the board and the blank (its purge row). For the status before a plan is chosen.
     */
    public ZCheckPlan boardRunZCheckPlan(boolean homeFirst) {
        DispenseConfig c = cfg;
        DispenseConfig.SurfaceMap board = c.surface(c.boardSurfaceName);
        return zCheckPlan(c, needleTasksScope(c), board == null ? null : board.boardFingerprint, homeFirst);
    }

    private ZCheckPlan zCheckPlan(DispenseConfig c, Interlocks.Scope sc, String planRegistration, boolean homeFirst) {
        ZCheckPlan p = new ZCheckPlan();
        p.last = homeTracker.getZCheck();
        p.homeAtMs = homeTracker.getLastHomeAtMs();
        p.stale = ZCheck.stale(c, sc, homeFirst ? Long.valueOf(Long.MAX_VALUE) : p.homeAtMs);
        List<ZCheck.Stale> open = homeFirst ? p.stale : ZCheck.unchecked(p.last, p.homeAtMs, p.stale);
        p.needed = !open.isEmpty();
        List<ZCheck.Reference> refs = ZCheck.references(c, planRegistration, homeFirst ? null : p.homeAtMs);
        p.reference = ZCheck.unreachable(refs, open).isEmpty() ? ZCheck.first(refs, open) : null;
        p.touches = p.needed && p.reference != null ? ZCheck.touches(refs, open) : 0;
        return p;
    }

    /** The I4 refusal of a run whose Z check (after a home it does itself, or the last one) has no point left. */
    public static String noZReference(boolean homesFirst, Long lastHomeAtMs) {
        return homesFirst ? "I4: the run homes first, and Z must then be checked, but no point measured before the "
                + "home is left to touch (no blank or board surface, no needle-frame X/Y): record the needle frame "
                + "again" : "I4: " + ZCheck.noReferenceMessage(lastHomeAtMs);
    }

    /** The Z data of the needle tasks (prime, test dot, touch-off, dot ladder, shots) and of Check Z. */
    static Interlocks.Scope needleTasksScope(DispenseConfig c) {
        Interlocks.Scope sc = blankScope(c, c.blankLocationName, false, false, null);
        sc.moreSurfaces.put(c.boardSurfaceName, new ArrayList<>());
        return sc;
    }

    /**
     * The top camera's frame {width, height} (mm), or null without one: the landing check lays out the frames that
     * cover its band with it (tip setups design §22.3).
     */
    public double[] topCameraFovMm() {
        DotImager im = imager();
        try {
            return im == null || !im.isAvailable() ? null : im.fieldOfViewMm();
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** A top camera (or a test imager) for the fiducials and the looks before N1 touches. */
    public boolean hasTopCamera() {
        DotImager im = imager();
        return im != null && im.isAvailable();
    }

    /** The pad types, package mappings and package options now (BoardFeatures, plans). */
    public PadTypeResolver padTypeResolver() throws SQLException {
        DispenseConfig c = cfg;
        return new PadTypeResolver(TipSetups.typesFor(db, c.tip.key), db.packageMappings())
                .withPastePartial(db.pastePartialPackages()).withSetup(c.tip.key, c.tip.setupVersion);
    }

    /**
     * The checks of a probe task before anything moves (§4.4), for the API's 409: N1 present and
     * untransformed, the needle frame (a board) or the frame band (a needle-frame surface), the
     * simulator timings, OpenPnP's N1 state and the window. On a loopback link whose controller has not
     * identified itself yet the timings are left to the task, which asks it first (M115).
     */
    public VacuumProber.Window probePreflight(boolean board, String what, double nominalZ) throws Exception {
        requireAvailable();
        ReferenceMachine m = (ReferenceMachine) configuration.getMachine();
        DispenseConfig c = cfg;
        Nozzle n = getProbeNozzle();
        if (n == null) {
            throw refused("PROBE: nozzle '" + PROBE_NOZZLE + "' is not in the machine");
        }
        OpenPnpRawMapper n1 = OpenPnpRawMapper.forProbeNozzle(m, n, c.bAxisName);
        String why = n1.probeNozzleProblem(nozzle);
        if (why != null) {
            throw refused(why);
        }
        OpenPnpRawMapper dispenser = new OpenPnpRawMapper(m, nozzle, c.bAxisName);
        double[] here = dispenser.currentNozzleLocation();
        double travel = dispenser.toRaw(here[0], here[1], c.travelSafeZMm).z;
        boolean unknown = connectionSimulated == null && SimGuard.isLoopbackOnly(m);
        VacuumProber.Settings ps = VacuumProber.Settings.of(c, isSimulatorConfirmed() || unknown);
        return VacuumProber.preflight(c, board, what, nominalZ, travel, ps, n);
    }

    /**
     * POST /api/machine/home: {@link #homeSafely(DispenseExecutor, OpenPnpRawMapper, BooleanSupplier,
     * HomeReport, RunResult)} on the machine thread, with an executor of its own that STOP aborts.
     */
    public HomeReport homeSafely() throws Exception {
        requireAvailable();
        ReferenceMachine m = (ReferenceMachine) configuration.getMachine();
        DispenseConfig c = cfg;
        OpenPnpRawMapper mapper = new OpenPnpRawMapper(m, nozzle, c.bAxisName);
        DispenseExecutor exec = new DispenseExecutor(c, new DriverGcodeChannel(mapper.getDriver()), mapper.letterB(),
                System::currentTimeMillis);
        HomeReport rep = HomeReport.of(homePlan());
        activeExecutor = exec;
        try {
            long t0 = System.currentTimeMillis();
            homeSafely(exec, mapper, exec::isAbortRequested, rep, null);
            rep.done = true;
            rep.seconds = (System.currentTimeMillis() - t0) / 1000.0;
        }
        finally {
            if (activeExecutor == exec) {
                activeExecutor = null;
            }
        }
        return rep;
    }

    /**
     * Homes on the machine thread (§2.3): first, when a STOP left N1 low, raises it to the travel Z
     * (Z only: X/Y never move before G28, whatever axis order the firmware homes in); then OpenPnP's
     * home, during which a disable disconnects every driver, so visual homing and the park cannot move
     * the head after a STOP. A STOP (or a disable) during the home un-homes and ends ABORTED. {@code r}
     * is the task's run (its phase, lines and warnings), null for the API's home.
     */
    private void homeSafely(DispenseExecutor exec, OpenPnpRawMapper dispenser, BooleanSupplier aborted, HomeReport rep,
            RunResult r) throws Exception {
        ReferenceMachine m = (ReferenceMachine) configuration.getMachine();
        DispenseConfig c = cfg;
        Nozzle n = getProbeNozzle();
        if (n != null) {
            OpenPnpRawMapper n1 = OpenPnpRawMapper.forProbeNozzle(m, n, c.bAxisName);
            double[] pos = new double[3];
            AxesLocation at;
            try {
                at = n1.getDriver().getReportedLocation(c.commandTimeoutMs);
            }
            catch (Exception e) {
                if (aborted.getAsBoolean()) {
                    throw new DispenseExecutor.AbortedException(abortReason(exec, "stopped before homing"));
                }
                throw refused("MACHINE: homing failed: the controller did not report its position (" + e.getMessage()
                        + "). Nothing else was done; home from the strip and try again.");
            }
            ReferenceControllerAxis[] xyz = n1.xyzAxes();
            for (int i = 0; i < 3; i++) {
                pos[i] = at.getCoordinate(xyz[i], LengthUnit.Millimeters);
            }
            n1.resync(pos, pos, n1.currentB());
            if (outsideSafeZone(n1.zSafeZoneRaw(), pos[2])) {
                double travel = homeRaiseZ(dispenser, c, n1.zSafeZoneRaw());
                rep.n1RaisedFromZ = pos[2];
                if (r != null) {
                    phase(r, String.format(Locale.ROOT, "home: %s · raising N1 from Z %.2f", rep.why, pos[2]));
                }
                exec.sendGated(DispenseExecutor.Scope.HOME, "G1 " + n1.letterZ() + GcodeStreamBuilder.f4(travel) + " F600",
                        c.commandTimeoutMs + 60_000L, r);
                exec.sendGated(DispenseExecutor.Scope.HOME, "M400", c.commandTimeoutMs + 60_000L, r);
                pos[2] = travel;
                n1.resync(pos, pos, n1.currentB());
                Logger.info("N1 was at Z {}: raised to {} before homing", rep.n1RaisedFromZ, travel);
            }
        }
        if (aborted.getAsBoolean()) {
            throw new DispenseExecutor.AbortedException(abortReason(exec, "stopped before homing"));
        }
        MachineListener cut = new MachineListener.Adapter() {
            @Override
            public void machineDisabled(Machine machine, String reason) {
                for (Driver d : machine.getDrivers()) {
                    if (d instanceof GcodeDriver) {
                        ((GcodeDriver) d).disconnect();
                    }
                }
            }
        };
        m.addListener(cut);
        Exception failed = null;
        try {
            homeTracker.homeStarted();
            m.home();
        }
        catch (Exception e) {
            failed = e;
        }
        finally {
            m.removeListener(cut);
            homeTracker.homeEnded();
        }
        if (aborted.getAsBoolean() || !m.isEnabled()) {
            m.setHomed(false);
            throw new DispenseExecutor.AbortedException(abortReason(exec, "stopped during homing"));
        }
        if (failed != null) {
            m.setHomed(false);
            throw new RunRefusedException("MACHINE: homing failed: " + failed.getMessage() + ". Nothing else was done; "
                    + "home from the strip and try again.", failed);
        }
    }

    /** The raw Z N1 is raised to before a home: the dispense travel Z (31.5, z1's home and the see-saw middle). */
    private static double homeRaiseZ(OpenPnpRawMapper dispenser, DispenseConfig c, double[] zone) throws Exception {
        double[] n = dispenser.currentNozzleLocation();
        double z = dispenser.toRaw(n[0], n[1], c.travelSafeZMm).z;
        if (outsideSafeZone(zone, z)) {
            z = (zone[0] + zone[1]) / 2; // keep N1 inside its safe zone whatever the travel Z
        }
        return z;
    }

    private static String abortReason(DispenseExecutor exec, String otherwise) {
        String why = exec == null ? null : exec.getAbortReason();
        return why == null ? otherwise : why;
    }

    /** Inside a task right after its Session: homes when the home plan says so (§2.3), else nothing. */
    private void homeIfNeeded(Session s, HomeReport rep, BooleanSupplier aborted) throws Exception {
        Machine m = configuration.getMachine();
        HomeTracker.Plan hp = homeTracker.plan(m.isEnabled(), m.isHomed(), s.c.homeBeforeRun);
        rep.needed = hp.needed;
        rep.whyCode = hp.whyCode;
        rep.why = hp.why;
        if (!hp.needed) {
            return;
        }
        if (aborted.getAsBoolean()) {
            throw new DispenseExecutor.AbortedException(abortReason(s.exec, "stopped before homing"));
        }
        phase(s.r, "home: " + hp.why);
        long t0 = System.currentTimeMillis();
        homeSafely(s.exec, s.mapper, aborted, rep, s.r);
        rep.done = true;
        rep.seconds = (System.currentTimeMillis() - t0) / 1000.0;
    }

    /**
     * The Z check of a task (§2.4): when Z data of its scope predate the last home and no passing
     * check for that home vouches for them, one N1 touch at the first stored point whose spot is bare,
     * per Z frame among those data (a point vouches only for data measured under its home). Returns
     * the last touch, or null when none was needed. Refused when no point is left, when it does not
     * touch, and when it fails ("Z moved").
     */
    private ZCheck.Result zCheckIfNeeded(Session s, String planRegistration, Interlocks.Scope scope,
            BooleanSupplier aborted) throws Exception {
        Long home = homeTracker.getLastHomeAtMs();
        List<ZCheck.Stale> open = ZCheck.unchecked(homeTracker.getZCheck(), home, ZCheck.stale(s.c, scope, home));
        if (open.isEmpty()) {
            return null;
        }
        List<ZCheck.Reference> refs = ZCheck.references(s.c, planRegistration, home);
        if (refs.isEmpty()) {
            throw refused("I4: " + ZCheck.noReferenceMessage(home));
        }
        List<ZCheck.Stale> unreachable = ZCheck.unreachable(refs, open);
        if (!unreachable.isEmpty()) {
            throw refused("I4: " + ZCheck.noFrameReferenceMessage(home, unreachable));
        }
        Probe pr = new Probe(s, aborted, null);
        for (ZCheck.Reference ref : refs) {
            if (!ZCheck.vouchesForAny(ref, open)) {
                continue; // its frame is checked already
            }
            phase(s.r, "z check: " + ZCheck.describe(ref.name));
            VacuumProber.Target t = new VacuumProber.Target();
            t.n = 1;
            t.x = ref.x;
            t.y = ref.y;
            t.storedZ = ref.z;
            VacuumProber.Point p = pr.run("z check", Collections.singletonList(t), VacuumProber.Window.around(ref.z,
                    pr.travelZ, pr.settings)).get(0);
            if ("not-bare".equals(p.state)) {
                s.r.warnings.add("Z check: " + p.failure + " (the next stored point is used)");
                continue;
            }
            if (p.z == null) {
                throw refused(p.failure);
            }
            ZCheck.Result res = ZCheck.vouch(ZCheck.result(ref, p.z, System.currentTimeMillis(), home), ref, s.c,
                    homeTracker.getZCheck());
            homeTracker.setZCheck(res);
            db.event(s.r.runId, res.pass ? "INFO" : "WARN", "ZCHECK", new Gson().toJson(res));
            if (!res.pass) {
                throw refused("I4: " + ZCheck.movedMessage(res));
            }
            restampZFrame(res);
            open = ZCheck.unchecked(res, home, open);
            if (open.isEmpty()) {
                return res;
            }
        }
        throw refused("I4: Z must be checked after the home at " + ZCheck.clock(home) + ", but something lies on every "
                + "stored point (paste, a part or print seen): wipe one of them, or record the needle frame again");
    }

    /**
     * A passing Z check: the data it vouched for are in its home's Z frame from now on ({@link ZCheck#restamp}).
     * Not saved: they keep their older frame, and the next home touches one more point.
     */
    private synchronized void restampZFrame(ZCheck.Result res) {
        DispenseConfig c = cfg.copy();
        try {
            if (ZCheck.restamp(c, res)) {
                updateConfig(c);
            }
        }
        catch (Exception e) {
            Logger.warn(e, "dispense: the Z frames checked at the home were not saved");
        }
    }

    /** The N1 vacuum probe of a task: N1's mapper and checks, the settings and the prober over the session's gate. */
    private final class Probe {
        final Session s;
        final OpenPnpRawMapper n1;
        final VacuumProber.Settings settings;
        final VacuumProber prober;
        /** The raw Z the dispense stream travels at, sent as N1 Z. */
        final double travelZ;
        final BooleanSupplier aborted;
        /** The points the prober reported in the current run: the record of a run that refused or stopped. */
        private final Map<Integer, VacuumProber.Point> seen = new LinkedHashMap<>();

        Probe(Session s, BooleanSupplier aborted, java.util.function.Consumer<VacuumProber.Point> progress)
                throws Exception {
            this.s = s;
            this.aborted = aborted;
            ReferenceMachine m = (ReferenceMachine) configuration.getMachine();
            Nozzle n = getProbeNozzle();
            if (n == null) {
                throw refused("PROBE: nozzle '" + PROBE_NOZZLE + "' is not in the machine");
            }
            n1 = OpenPnpRawMapper.forProbeNozzle(m, n, s.c.bAxisName);
            String why = n1.probeNozzleProblem(nozzle);
            if (why != null) {
                throw refused(why);
            }
            settings = VacuumProber.Settings.of(s.c, isSimulatorConfirmed());
            double[] here = s.mapper.currentNozzleLocation();
            travelZ = s.mapper.toRaw(here[0], here[1], s.c.travelSafeZMm).z;
            // everything OpenPnP queued must be done before the raw probe lines start
            m.getMotionPlanner().waitForCompletion(null, CompletionType.WaitForStillstand);
            prober = new VacuumProber(settings, n1, VacuumProber.gatedIo(s.exec, s.r), spotLook(imager()), s.r,
                    aborted, pt -> {
                        seen.put(pt.n, pt);
                        if (progress != null) {
                            progress.accept(pt);
                        }
                    }, baselineMemory);
        }

        /**
         * Probes the targets. After it returns or refuses, OpenPnP learns where the probe left N1;
         * after a STOP or failed end lines (the prober sent the I1 shutdown) the session recovers
         * instead (position read-back, un-home). A STOP is told by the flags, not by the exception:
         * a STOP during the camera look surfaces as the look's exception, which is rethrown as the
         * STOP so every task records it as stopped. However it ended, a PROBE event keeps every
         * point's readings, release wait and look ({@code what}: the z check, same seat, the probe).
         */
        List<VacuumProber.Point> run(String what, List<VacuumProber.Target> targets, VacuumProber.Window w)
                throws Exception {
            boolean recovered = false;
            List<VacuumProber.Point> out = null;
            seen.clear();
            try {
                out = prober.probe(targets, w);
                return out;
            }
            catch (Exception e) {
                boolean stop = aborted.getAsBoolean();
                if (prober.needsRecovery() || stop) {
                    recovered = true;
                    s.recover();
                }
                if (stop && !(e instanceof DispenseExecutor.AbortedException)) {
                    DispenseExecutor.AbortedException a = new DispenseExecutor.AbortedException(abortReason(s.exec,
                            "stopped during the probe"));
                    a.addSuppressed(e);
                    throw a;
                }
                throw e;
            }
            finally {
                if (!recovered && !prober.needsRecovery()) {
                    n1.resync(prober.finalPlanned(), prober.finalDriver(), n1.currentB());
                }
                record(what, w, out);
            }
        }

        /** The PROBE event of one run: the real machine needs the readings of a refused probe most (§5.4). */
        private void record(String what, VacuumProber.Window w, List<VacuumProber.Point> out) {
            try {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("what", what);
                m.put("window", w);
                m.put("points", out != null ? out : new ArrayList<>(seen.values()));
                db.event(s.r.runId, out != null && VacuumProber.refusal(out) == null ? "INFO" : "WARN", "PROBE",
                        new Gson().toJson(m));
            }
            catch (RuntimeException e) {
                Logger.warn(e, "dispense: the probe record was not written");
            }
        }
    }

    /** The camera look before an N1 touch (§4.5): one lit frame centred on the spot, BareSpotCheck. */
    private static VacuumProber.SpotLook spotLook(DotImager im) {
        if (im == null || !im.isAvailable()) {
            return null;
        }
        BareSpotCheck.Settings bs = new BareSpotCheck.Settings();
        return (x, y) -> {
            DotImager.Frame f = im.captureLit(x, y, null);
            if (f == null || f.image == null) {
                return null; // an imager without pictures (dot vision only): no look
            }
            BareSpotCheck.Result res = BareSpotCheck.check(f.image, f.mmPerPxX, f.mmPerPxY, bs);
            VacuumProber.Look l = new VacuumProber.Look();
            l.bare = res.bare;
            l.foreignShare = res.foreignShare;
            l.ringSpread = res.ringSpread;
            l.rawShare = res.rawShare;
            l.threshold = res.threshold;
            l.wideShare = res.wideShare;
            l.reason = res.reason;
            return l;
        };
    }

    /**
     * One engine task with stages (Register board, the surface probe): the stage a STOP came in, and
     * the STOP test, which also covers a STOP before the Session existed.
     */
    private final class Task {
        final RunResult r;
        volatile String stage;
        volatile Session s;
        /** The stage when the task ended other than OK. */
        volatile String endedIn;
        /** The task's live progress (status.run.detail). */
        final Map<String, Object> detail = new LinkedHashMap<>();
        final Map<Integer, VacuumProber.Point> points = new LinkedHashMap<>();
        int stepsPlanned;
        int stepsDone;
        double secondsLeft;
        private String lastPointPhase;

        Task(RunResult r, String stage) {
            this.r = r;
            this.stage = stage;
        }

        Session start(DispenseConfig c) throws Exception {
            s = new Session(c, r);
            if (cameraTourAbort) {
                s.exec.requestAbort("STOP before the start");
            }
            return s;
        }

        boolean aborted() {
            Session x = s;
            return cameraTourAbort || (x != null && x.exec.isAbortRequested());
        }

        /** Runs the body; a failure is ABORTED when STOP was pressed, else REFUSED or ERROR by its kind. */
        void run(Body body) throws Exception {
            try {
                body.run();
            }
            catch (Exception e) {
                endedIn = stage;
                if (r.status == RunResult.Status.RUNNING) {
                    boolean stop = aborted();
                    r.status = stop ? RunResult.Status.ABORTED
                            : e instanceof RunRefusedException ? RunResult.Status.REFUSED : RunResult.Status.ERROR;
                    r.message = stop ? abortReason(s == null ? null : s.exec, String.valueOf(e.getMessage()))
                            : String.valueOf(e.getMessage());
                }
                throw e;
            }
        }

        /** A probe point changed: phase on a new spot or look, and the live detail. */
        void point(String what, int total, VacuumProber.Point p) {
            points.put(p.n, p);
            if ("now".equals(p.state) || "look".equals(p.state)) {
                String ph = "look".equals(p.state) ? what + ": look"
                        : what + ": point " + p.n + " of " + total + (p.alternate > 0 ? " · alternate " + p.alternate : "");
                if (!ph.equals(lastPointPhase)) {
                    lastPointPhase = ph;
                    phase(r, ph);
                }
            }
            if ("contact".equals(p.state)) {
                stepsDone++;
                secondsLeft = Math.max(0, secondsLeft - PROBE_POINT_ESTIMATE_S);
            }
            send();
        }

        void send() {
            Map<String, Object> d = new LinkedHashMap<>(detail);
            d.put("stage", stage);
            d.put("stepsDone", stepsDone);
            d.put("stepsPlanned", stepsPlanned);
            d.put("estimatedSecondsLeft", Math.round(secondsLeft));
            List<Map<String, Object>> probes = new ArrayList<>();
            for (VacuumProber.Point p : points.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("n", p.n);
                m.put("x", p.x);
                m.put("y", p.y);
                m.put("state", p.state);
                m.put("z", p.z);
                m.put("lastZ", p.lastZ);
                m.put("alternate", p.alternate);
                probes.add(m);
            }
            if (!probes.isEmpty()) {
                d.put("probes", probes);
            }
            detail(r, d);
        }
    }

    /**
     * Register board (§5.2): home if needed, find the fiducials with the top camera, fit the board ->
     * machine affine, decide the seat, touch the board with N1's vacuum (or re-touch 3 stored points of
     * a same seat), save the surface tied to the registration and plan the board again. A registration
     * once stored stays (also after a refused probe or a STOP) and the board is planned with it. A board
     * with fewer fiducials than needed is refused; one without any is probed only when the request says so
     * ({@link RegisterRequest#probeOnly}, F10). The Future never throws for task failures; see the result.
     */
    public Future<RegistrationResult> registerBoard(RegisterRequest req) throws Exception {
        requireAvailable();
        RunResult r = newRun("board-registration", req.plan.planId, req.plan.boardName, 0, 0);
        RegistrationResult res = new RegistrationResult();
        res.run = r;
        res.home = new HomeReport();
        res.probe = new ProbeReport();
        res.probe.mode = req.probe.name().toLowerCase(Locale.ROOT);
        Task t = new Task(r, "home");
        cameraTourAbort = false;
        return submitResult(r, res, () -> t.run(() -> register(req, res, t)), () -> {
            res.ok = r.status == RunResult.Status.OK || r.status == RunResult.Status.RUNNING;
            res.stoppedStage = r.status == RunResult.Status.ABORTED ? t.endedIn : null;
            res.message = res.ok ? registrationSentence(res) : r.message;
            if (res.ok) {
                r.message = res.message;
            }
        });
    }

    private void register(RegisterRequest req, RegistrationResult res, Task t) throws Exception {
        RunResult r = t.r;
        DispenseConfig c = cfg;
        Session s = t.start(c);
        BooleanSupplier aborted = t::aborted;
        HomeTracker.Plan hp = homePlan();
        t.stepsPlanned = (hp.needed ? 1 : 0) + 1 + 5;
        t.secondsLeft = (hp.needed ? hp.estimateS : 0) + 3 * FIDUCIAL_ESTIMATE_S + 5 * PROBE_POINT_ESTIMATE_S;
        t.detail.put("home", homeDetail(hp));
        t.send();
        homeIfNeeded(s, res.home, aborted);
        if (res.home.done) {
            t.stepsDone++;
            t.secondsLeft = Math.max(0, t.secondsLeft - hp.estimateS);
        }
        r.interlocks = Interlocks.checkBasic(interlockInputs(c, null, 0, null, false, false));
        if (!r.interlocks.ok()) {
            throw refused(r.interlocks.toString());
        }

        // the fiducials
        t.stage = "fiducials";
        Board board = req.nominal.getBoard();
        boolean job = req.boardKey != null && req.boardKey.startsWith("job:");
        BoardFeatures f = BoardFeatures.of(board, padTypeResolver(),
                req.fiducialHints);
        String few = fewFiducials(f, c.fiducialCheck.minFiducials);
        // probe only (F10) is for a board without a single fiducial: one that has some is fitted by them, or refused
        boolean onlyProbe = few != null && req.probeOnly && f.fiducials.isEmpty();
        if (few != null && !onlyProbe) {
            throw refused(few);
        }
        DotImager im = imager();
        if (im == null || !im.isAvailable()) {
            throw refused(NO_CAMERA);
        }
        boolean sim = isSimulatorConfirmed();
        res.simulated = sim;
        if (onlyProbe) {
            probeOnly(req, res, t, f, few, sim);
            s.ok();
            return;
        }
        BoardRegistration prev = c.boardRegistrations.get(req.boardKey);
        if (prev != null && prev.refusal(req.boardKey, req.nominalSeat, board, () -> sim) != null) {
            prev = null;
        }
        BoardLocation guess = prev == null ? req.nominal : prev.applyTo(req.nominal);
        List<FiducialLocator.Target> targets = new ArrayList<>();
        double maxD = 0;
        for (BoardFeatures.Fiducial fd : f.fiducials) {
            Location e = Utils2D.calculateBoardPlacementLocation(guess, new Location(LengthUnit.Millimeters, fd.x, fd.y,
                    0, 0)).convertToUnits(LengthUnit.Millimeters);
            targets.add(new FiducialLocator.Target(fd.name, fd.x, fd.y, e.getX(), e.getY(), fd.diameterMm));
            RegistrationResult.FidResult fr = new RegistrationResult.FidResult();
            fr.name = fd.name;
            fr.bx = fd.x;
            fr.by = fd.y;
            fr.expectedX = e.getX();
            fr.expectedY = e.getY();
            fr.searchRadiusMm = c.fiducialCheck.searchRadiusMm;
            res.fiducials.add(fr);
            maxD = Math.max(maxD, fd.diameterMm);
        }
        t.stepsPlanned = (hp.needed ? 1 : 0) + targets.size() + 5 + 1;
        t.secondsLeft = targets.size() * FIDUCIAL_ESTIMATE_S + 5 * PROBE_POINT_ESTIMATE_S;
        t.detail.put("fiducials", fiducialDetail(res.fiducials));
        FiducialLocator.Settings ls = new FiducialLocator.Settings();
        ls.searchRadiusMm = c.fiducialCheck.searchRadiusMm;
        ls.minFiducials = c.fiducialCheck.minFiducials;
        double margin = maxD * ls.detector.maxDiameterFactor;
        boolean[] sized = {false};
        FiducialLocator.FrameSource cam = (x, y) -> {
            DotImager.Frame fr = im.captureLit(x, y, req.nominalZ);
            if (!sized[0] && fr != null && fr.image != null) {
                sized[0] = true;
                int n = FiducialLocator.searchFrameCount(fr.image.getWidth(), fr.image.getHeight(), fr.mmPerPxX,
                        fr.mmPerPxY, margin, ls.searchRadiusMm);
                if (n > FiducialLocator.MAX_SEARCH_FRAMES) {
                    throw refused(String.format(Locale.ROOT, "REG: fiducialCheck.searchRadiusMm %.1f needs %d camera "
                            + "frames of %.1f x %.1f mm per fiducial (at most %d): use a smaller radius",
                            ls.searchRadiusMm, n, fr.image.getWidth() * fr.mmPerPxX, fr.image.getHeight() * fr.mmPerPxY,
                            FiducialLocator.MAX_SEARCH_FRAMES));
                }
            }
            return fr;
        };
        FiducialLocator locator = new FiducialLocator(ls, cam, aborted, found -> {
            RegistrationResult.FidResult fr = fidResult(res, found.target.name);
            fr.x = found.x;
            fr.y = found.y;
            fr.state = "found";
            fr.iterations = found.iterations;
            fr.frames = found.frames;
            fr.offsetPx = found.lastOffsetPx;
            fr.offsetFromExpectedMm = found.offsetFromExpectedMm;
            t.stepsDone++;
            t.secondsLeft = Math.max(0, t.secondsLeft - FIDUCIAL_ESTIMATE_S);
            t.detail.put("fiducials", fiducialDetail(res.fiducials));
            t.send();
        }, step -> {
            phase(r, step);
            t.send();
        });
        double[] from = cameraXY();
        List<FiducialLocator.Found> found;
        try {
            found = from == null ? locator.locateAll(targets) : locator.locateAll(targets, from[0], from[1]);
        }
        catch (FiducialLocator.Failure e) {
            if (e.target != null) {
                fidResult(res, e.target.name).state = FiducialLocator.Failure.AMBIGUOUS.equals(e.code) ? "ambiguous"
                        : FiducialLocator.Failure.NOT_CENTRED.equals(e.code) ? "not-centred" : "not-found";
            }
            throw refused(e.messageFor(job));
        }
        phase(r, "fiducials: fit");
        FiducialLocator.Fit prevFit = prev == null ? null : FiducialLocator.Fit.of(prev.transform(), prev.method,
                prev.atMs);
        FiducialLocator.Fit fit;
        try {
            fit = FiducialLocator.fit(found, prevFit, ls);
        }
        catch (FiducialLocator.Failure e) {
            throw refused(e.getMessage());
        }
        res.fit = fitResult(fit, ls, prev);

        // the seat
        List<BoardRegistration.Fid> now = new ArrayList<>();
        for (FiducialLocator.Found x : found) {
            BoardRegistration.Fid fd = new BoardRegistration.Fid();
            fd.name = x.target.name;
            fd.bx = x.target.boardX;
            fd.by = x.target.boardY;
            fd.x = x.x;
            fd.y = x.y;
            fd.expectedX = x.target.expectedX;
            fd.expectedY = x.target.expectedY;
            fd.iterations = x.iterations;
            fd.frames = x.frames;
            fd.offsetPx = x.lastOffsetPx;
            fd.diameterMm = x.detection == null ? x.target.diameterMm : x.detection.diameterMm;
            fd.score = x.detection == null ? 0 : x.detection.score;
            now.add(fd);
        }
        long atMs = System.currentTimeMillis();
        boolean announced = false;
        if (prev == null) {
            res.seat = "first";
        }
        else {
            double moved = prev.maxMovedMm(now);
            announced = c.newSeatAtMs != null && c.newSeatAtMs > prev.atMs;
            res.movedMm = Double.isNaN(moved) ? null : moved;
            res.previousAtMs = prev.atMs;
            res.seat = announced || !(moved <= c.fiducialCheck.seatToleranceMm) ? "reseated" : "same";
            for (BoardRegistration.Fid fd : now) {
                for (BoardRegistration.Fid p : prev.fiducials) {
                    if (fd.name.equals(p.name)) {
                        fidResult(res, fd.name).movedMm = Math.hypot(fd.x - p.x, fd.y - p.y);
                    }
                }
            }
        }
        t.detail.put("seat", res.seat);
        t.detail.put("movedMm", res.movedMm);
        BoardRegistration reg;
        if ("same".equals(res.seat)) {
            reg = new Gson().fromJson(new Gson().toJson(prev), BoardRegistration.class);
            reg.lastCheckedAtMs = atMs;
            reg.lastCheckMovedMm = res.movedMm;
        }
        else {
            reg = new BoardRegistration();
            reg.id = java.util.UUID.randomUUID().toString();
            reg.boardKey = req.boardKey;
            reg.boardName = board.getName();
            reg.nominal = req.nominalSeat;
            reg.setTransform(fit.boardToMachine);
            reg.method = fit.method;
            reg.rotationDeg = fit.rotationDeg;
            reg.scaleX = fit.scaleX;
            reg.scaleY = fit.scaleY;
            reg.shearDeg = fit.shearDeg;
            reg.maxResidualMm = fit.maxResidualMm;
            reg.fiducials = now;
            reg.atMs = atMs;
            reg.simulated = sim;
            reg.runId = r.runId;
        }
        if (aborted.getAsBoolean()) {
            throw new DispenseExecutor.AbortedException(abortReason(s.exec, "stopped during the fiducial check"));
        }
        storeRegistration(reg, r.runId);
        res.registrationId = reg.id;
        res.registrationStored = true;
        try {
            probeBoard(req, res, t, f, reg, announced, sim);
        }
        finally {
            // the registration is real (also after a refused probe or a STOP): plan the board with it
            String was = t.stage;
            t.stage = "plan";
            if (req.replanner != null) {
                try {
                    res.newPlanId = req.replanner.replan(reg);
                    r.nextPlanId = res.newPlanId;
                    phase(r, "plan: planned again");
                }
                catch (Exception e) {
                    r.warnings.add("plan again failed: " + e.getMessage());
                }
            }
            t.stepsDone = t.stepsPlanned;
            t.secondsLeft = 0;
            t.send();
            t.stage = was;
        }
        s.ok();
    }

    /** The probe part of Register board: the same-seat check, or every target; the surface saved and tied. */
    private void probeBoard(RegisterRequest req, RegistrationResult res, Task t, BoardFeatures f, BoardRegistration reg,
            boolean announced, boolean sim) throws Exception {
        Session s = t.s;
        DispenseConfig c = cfg;
        ProbeReport pr = res.probe;
        String tie = boardRegistration(reg.applyTo(req.nominal));
        if (req.probe == RegisterRequest.ProbeMode.NEVER) {
            pr.skipped = true;
            pr.why = "registration only (probe: never)";
            return;
        }
        DispenseConfig.SurfaceMap surf = c.surface(c.boardSurfaceName);
        boolean tied = surf != null && tie.equals(surf.boardFingerprint) && surf.points != null
                && surf.points.size() >= 3 && (sim || !Boolean.TRUE.equals(surf.simulated));
        boolean full = req.probe == RegisterRequest.ProbeMode.ALWAYS || !"same".equals(res.seat) || !tied;
        pr.needed = full;
        pr.why = req.probe == RegisterRequest.ProbeMode.ALWAYS ? "probe: always"
                : "first".equals(res.seat) ? "first registration of this board"
                        : "reseated".equals(res.seat) ? (announced ? "a new seat was announced" : "re-seated")
                                : !tied ? "no surface tied to this registration" : "same seat: 3 stored points touched again";
        Probe p = new Probe(s, t::aborted, pt -> t.point("same-seat".equals(t.stage) ? "same seat" : "probe",
                "same-seat".equals(t.stage) ? 3 : Math.max(1, t.points.size()), pt));
        pr.window = VacuumProber.preflight(c, true, "the board top Z", req.nominalZ, p.travelZ, p.settings,
                p.n1.getNozzle());
        if (!full) {
            t.stage = "same-seat";
            res.sameSeat = sameSeat(t, p, surf, reg);
            if (res.sameSeat.pass) {
                pr.surfaceSetAtMs = surf.setAtMs;
                return;
            }
            pr.needed = true;
            pr.why = "same seat, surface changed: probed again";
            res.sameSeat.note = pr.why;
        }
        probeAll(req, res, t, p, f, reg.transform(), tie, "registration " + reg.shortId(), sim);
    }

    /**
     * Register board on a board without fiducials (F10), asked with probe "always" on a plan without a registration:
     * no fiducial fit and no registration stored; N1 probes the board's targets where the
     * plan puts the board (each spot looked at first), the surface is tied to that seat (a board planned at another
     * X/Y, rotation or side is not tied to it), and the board is planned again on it.
     */
    private void probeOnly(RegisterRequest req, RegistrationResult res, Task t, BoardFeatures f, String few,
            boolean sim) throws Exception {
        RunResult r = t.r;
        DispenseConfig c = cfg;
        ProbeReport pr = res.probe;
        if (req.probe != RegisterRequest.ProbeMode.ALWAYS) {
            throw refused(few);
        }
        String tie = boardRegistration(req.nominal);
        if (!tie.equals(req.plan.boardRegistration)) {
            throw refused("REG: probe only needs a plan of the board where its file or job puts it, without a "
                    + "registration: plan the board again with registration none");
        }
        res.probeOnly = true;
        t.stage = "probe";
        pr.needed = true;
        pr.why = PROBE_ONLY_NOTE;
        Probe p = new Probe(t.s, t::aborted, pt -> t.point("probe", Math.max(1, t.points.size()), pt));
        pr.window = VacuumProber.preflight(c, true, "the board top Z", req.nominalZ, p.travelZ, p.settings,
                p.n1.getNozzle());
        try {
            probeAll(req, res, t, p, f, req.planToBoard.createInverse(), tie, pr.why, sim);
        }
        finally {
            // a new surface: plan the board on it (nothing else changed when the probe was refused or stopped)
            if (pr.saved && req.replanner != null) {
                String was = t.stage;
                t.stage = "plan";
                try {
                    res.newPlanId = req.replanner.replan(null);
                    r.nextPlanId = res.newPlanId;
                    phase(r, "plan: planned again");
                }
                catch (Exception e) {
                    r.warnings.add("plan again failed: " + e.getMessage());
                }
                t.stepsDone = t.stepsPlanned;
                t.secondsLeft = 0;
                t.send();
                t.stage = was;
            }
        }
    }

    /** The result's word for a probe-only Register board (F10). */
    static final String PROBE_ONLY_NOTE = "no fiducials: probed only, X/Y as planned";

    /**
     * Every target of the board probed (§5.2), the plane checked and saved as the board surface tied to {@code tie}:
     * the targets and the plan's dots go to the machine through {@code boardToMachine} (the registration's affine, or
     * the plan's own seat when probing only); {@code what} names it in the surface's note.
     */
    private void probeAll(RegisterRequest req, RegistrationResult res, Task t, Probe p, BoardFeatures f,
            AffineTransform boardToMachine, String tie, String what, boolean sim) throws Exception {
        RunResult r = t.r;
        Session s = t.s;
        DispenseConfig c = cfg;
        ProbeReport pr = res.probe;
        t.stage = "probe";
        t.points.clear();
        List<VacuumProber.Target> targets = ProbeTargets.map(ProbeTargets.forBoard(f, ProbeTargets.dotsBoard(req.plan,
                req.planToBoard), c, req.keepOuts), boardToMachine);
        t.stepsPlanned = t.stepsDone + targets.size() + 1;
        t.secondsLeft = targets.size() * PROBE_POINT_ESTIMATE_S;
        for (VacuumProber.Target x : targets) {
            VacuumProber.Point todo = new VacuumProber.Point();
            todo.n = x.n;
            todo.x = x.x;
            todo.y = x.y;
            t.points.put(x.n, todo);
        }
        t.send();
        List<VacuumProber.Point> pts = p.run("board probe", targets, pr.window);
        pr.points = pts;
        String why = VacuumProber.refusal(pts);
        if (why != null) {
            throw refused(why);
        }
        List<DispenseConfig.SurfacePoint> sp = surfacePoints(pts);
        SurfacePlane plane = fitProbed(sp);
        pr.plane = PlaneReport.of(plane);
        String flat = flatness(plane, pts, c, true);
        if (flat != null) {
            throw refused(flat);
        }
        String band = band(pts, req.nominalZ, "the board top Z", c.zFrameToleranceMm);
        if (band != null) {
            throw refused(band);
        }
        AffineTransform planToMachine = new AffineTransform(boardToMachine);
        planToMachine.concatenate(req.planToBoard);
        int outside = 0;
        for (DispensePlan.PlannedDot d : req.plan.dots) {
            Point2D q = planToMachine.transform(new Point2D.Double(d.xMm, d.yMm), null);
            if (plane.distanceOutside(q.getX(), q.getY()) > c.surfaceMarginMm + 1e-9) {
                outside++;
            }
        }
        if (outside > 0) {
            throw refused(String.format(Locale.ROOT, "PROBE: the probed points leave %d dots more than %.1f mm outside "
                    + "the probed area (an alternate moved inward): probe by hand, or try again", outside,
                    c.surfaceMarginMm));
        }
        t.stage = "save";
        if (t.aborted()) {
            throw new DispenseExecutor.AbortedException(abortReason(s.exec, "stopped before the surface was saved"));
        }
        String note = String.format(Locale.ROOT, "Register board %s: %d points by N1 vacuum touch (%s, run %s)",
                ZCheck.clock(System.currentTimeMillis()), sp.size(), what, shortRun(r.runId));
        setSurface(c.boardSurfaceName, sp, note, tie, sim ? Boolean.TRUE : null);
        pr.saved = true;
        pr.surfaceSetAtMs = cfg.surface(c.boardSurfaceName).setAtMs;
        db.event(r.runId, "INFO", "PROBE", new Gson().toJson(pr));
        phase(r, "surface: saved");
        setNewSeat(false);
    }

    /**
     * The same-seat check (§3.6): the 3 stored points of the tied surface that span the largest
     * triangle, each touched again with the window around its stored Z. Passes when all 3 agree within
     * {@link ZCheck#TOLERANCE_MM}; then, if they predate the last home, it is that home's Z check too.
     */
    private SameSeatReport sameSeat(Task t, Probe p, DispenseConfig.SurfaceMap surf, BoardRegistration reg)
            throws Exception {
        SameSeatReport rep = new SameSeatReport();
        int[] idx = largestTriangle(surf.points);
        List<VacuumProber.Target> targets = new ArrayList<>();
        AffineTransform toBoard = reg.transform().createInverse();
        for (int k = 0; k < 3; k++) {
            DispenseConfig.SurfacePoint sp = surf.points.get(idx[k]);
            VacuumProber.Target x = new VacuumProber.Target();
            x.n = k + 1;
            x.x = sp.x;
            x.y = sp.y;
            x.storedZ = sp.z;
            Point2D b = toBoard.transform(new Point2D.Double(sp.x, sp.y), null);
            x.bx = b.getX();
            x.by = b.getY();
            targets.add(x);
        }
        t.stepsPlanned = t.stepsDone + 3 + 1;
        t.secondsLeft = 3 * PROBE_POINT_ESTIMATE_S;
        List<VacuumProber.Point> pts = p.run("same seat", targets, VacuumProber.Window.around(targets.get(0).storedZ,
                p.travelZ, p.settings));
        boolean pass = true;
        SameSeatReport.Point worst = null;
        for (int k = 0; k < 3; k++) {
            VacuumProber.Point pt = pts.get(k);
            SameSeatReport.Point q = new SameSeatReport.Point();
            q.n = k + 1;
            q.x = targets.get(k).x;
            q.y = targets.get(k).y;
            q.bx = targets.get(k).bx;
            q.by = targets.get(k).by;
            q.storedZ = targets.get(k).storedZ;
            q.z = "contact".equals(pt.state) && pt.alternate == 0 ? pt.z : null;
            q.deltaMm = q.z == null ? null : q.z - q.storedZ;
            q.pass = q.deltaMm != null && Math.abs(q.deltaMm) <= ZCheck.TOLERANCE_MM + 1e-9;
            pass &= q.pass;
            if (q.deltaMm != null && (worst == null || Math.abs(q.deltaMm) > Math.abs(worst.deltaMm))) {
                worst = q;
            }
            rep.points.add(q);
        }
        rep.pass = pass;
        Long home = homeTracker.getLastHomeAtMs();
        if (pass && home != null && surf.setAtMs != null && surf.setAtMs < home && worst != null) {
            ZCheck.Reference ref = new ZCheck.Reference();
            ref.name = cfg.boardSurfaceName;
            ref.point = idx[worst.n - 1];
            ref.x = worst.x;
            ref.y = worst.y;
            ref.z = worst.storedZ;
            ref.atMs = surf.setAtMs;
            ref.homeAtMs = surf.homeAtMs;
            ZCheck.Result zc = ZCheck.vouch(ZCheck.result(ref, worst.z, System.currentTimeMillis(), home), ref, cfg,
                    homeTracker.getZCheck());
            homeTracker.setZCheck(zc);
            db.event(t.r.runId, "INFO", "ZCHECK", new Gson().toJson(zc));
            restampZFrame(zc);
            rep.zCheckStamped = true;
        }
        return rep;
    }

    /** Indices of the 3 points spanning the largest triangle (the first such triple). */
    static int[] largestTriangle(List<DispenseConfig.SurfacePoint> pts) {
        int[] best = {0, 1, 2};
        double area = -1;
        for (int i = 0; i < pts.size(); i++) {
            for (int j = i + 1; j < pts.size(); j++) {
                for (int k = j + 1; k < pts.size(); k++) {
                    DispenseConfig.SurfacePoint a = pts.get(i), b = pts.get(j), q = pts.get(k);
                    double s = Math.abs((b.x - a.x) * (q.y - a.y) - (q.x - a.x) * (b.y - a.y));
                    if (s > area + 1e-12) {
                        area = s;
                        best = new int[] {i, j, k};
                    }
                }
            }
        }
        return best;
    }

    /**
     * Probe the blank (or another needle-frame surface) with N1 (§5.7): home if needed, targets around
     * the purge row (or the request's), the window from the place Z, the plane checks, saved untied.
     * Nothing is saved unless every point touched and the plane passed.
     */
    public Future<SurfaceProbeResult> probeSurface(SurfaceProbeRequest req) throws Exception {
        requireAvailable();
        RunResult r = newRun("surface-probe", null, req.surfaceName, 0, 0);
        SurfaceProbeResult res = new SurfaceProbeResult();
        res.run = r;
        res.surfaceName = req.surfaceName;
        res.home = new HomeReport();
        Task t = new Task(r, "home");
        cameraTourAbort = false;
        return submitResult(r, res, () -> t.run(() -> probeSurfaceBody(req, res, t)), () -> {
            res.ok = r.status == RunResult.Status.OK || r.status == RunResult.Status.RUNNING;
            res.stoppedStage = r.status == RunResult.Status.ABORTED ? t.endedIn : null;
            res.message = res.ok ? String.format(Locale.ROOT, "'%s' surface probed: %d points, Z %.3f..%.3f",
                    req.surfaceName, res.points.size(), res.plane.minZ, res.plane.maxZ) : r.message;
            if (res.ok) {
                r.message = res.message;
            }
        });
    }

    private void probeSurfaceBody(SurfaceProbeRequest req, SurfaceProbeResult res, Task t) throws Exception {
        RunResult r = t.r;
        DispenseConfig c = cfg;
        if (req.surfaceName == null || req.surfaceName.equals(c.boardSurfaceName)) {
            throw refused("PROBE: the '" + c.boardSurfaceName + "' surface is probed by Register board (Run › Register)");
        }
        String reserved = couponReservation(req.surfaceName);
        if (reserved != null) {
            throw refused(reserved);
        }
        Session s = t.start(c);
        HomeTracker.Plan hp = homePlan();
        t.detail.put("home", homeDetail(hp));
        homeIfNeeded(s, res.home, t::aborted);
        r.interlocks = Interlocks.checkBasic(interlockInputs(c, null, 0, null, false, false));
        if (!r.interlocks.ok()) {
            throw refused(r.interlocks.toString());
        }
        if (req.coupon != null) {
            // a coupon is probed in a narrow window around its place Z: from a Z that moved N1 would start inside it
            String moved = placeMovedRefusal(req.surfaceName, c.location(req.surfaceName));
            if (moved != null) {
                throw refused(moved);
            }
            t.stage = "look";
            couponLook(req, t);
        }
        t.stage = "probe";
        List<VacuumProber.Target> targets = req.targets == null ? ProbeTargets.forRow(c, req.surfaceName)
                : ProbeTargets.explicit(req.targets, c.vacuumProbe.alternates);
        t.stepsPlanned = targets.size() + 1;
        t.secondsLeft = targets.size() * PROBE_POINT_ESTIMATE_S;
        Probe p = new Probe(s, t::aborted, pt -> t.point("probe", targets.size(), pt));
        String what = "the '" + req.surfaceName + "' place Z";
        // a surface off the needle frame (a weigh coupon) is checked as a board is: against its own Z only
        res.window = VacuumProber.preflight(c, !req.frameCheck, what, req.nominalZ, req.windowMm, p.travelZ,
                p.settings, p.n1.getNozzle());
        List<VacuumProber.Point> pts = p.run("'" + req.surfaceName + "' probe", targets, res.window);
        res.points = pts;
        String why = VacuumProber.refusal(pts);
        if (why != null) {
            throw refused(why);
        }
        List<DispenseConfig.SurfacePoint> sp = surfacePoints(pts);
        SurfacePlane plane = fitProbed(sp);
        res.plane = PlaneReport.of(plane);
        String flat = flatness(plane, pts, c, false);
        if (flat != null) {
            throw refused(flat);
        }
        if (c.needleAnchor != null && req.frameCheck) {
            String band = band(pts, c.needleAnchor.surfaceZMm, "the needle-frame surface", c.zFrameToleranceMm);
            if (band != null) {
                throw refused(band);
            }
        }
        List<double[]> cover = new ArrayList<>();
        DispenseConfig.PurgeRow row = c.purgeRow;
        if (row.isDefined() && req.surfaceName.equals(row.surfaceName)) {
            for (int i = 0; i < row.spots; i++) {
                cover.add(row.spot(i));
            }
        }
        DispenseConfig.NamedLocation place = c.location(req.surfaceName);
        if (place != null) {
            cover.add(new double[] {place.x, place.y});
        }
        int outside = 0;
        for (double[] xy : cover) {
            if (plane.distanceOutside(xy[0], xy[1]) > c.surfaceMarginMm - 0.5 + 1e-9) {
                outside++;
            }
        }
        res.plane.spots = cover.size();
        res.plane.coversSpots = outside == 0;
        if (outside > 0) {
            throw refused(String.format(Locale.ROOT, "PROBE: the probed points leave %d spot(s) of the purge row or the "
                    + "place more than %.1f mm outside the probed area: give the targets, or probe by hand", outside,
                    c.surfaceMarginMm - 0.5));
        }
        t.stage = "save";
        if (t.aborted()) {
            throw new DispenseExecutor.AbortedException(abortReason(s.exec, "stopped before the surface was saved"));
        }
        boolean sim = isSimulatorConfirmed();
        String note = String.format(Locale.ROOT, "Probe the %s %s: %d points by N1 vacuum touch (run %s)", req.surfaceName,
                ZCheck.clock(System.currentTimeMillis()), sp.size(), shortRun(r.runId));
        setSurface(req.surfaceName, sp, note, null, sim ? Boolean.TRUE : null);
        res.saved = true;
        phase(r, "surface: saved");
        t.stepsDone = t.stepsPlanned;
        t.send();
        s.ok();
    }

    /**
     * Check Z (§5.8): the Z check alone, behind the I4 "Z not checked" refusal of prime, the test dot,
     * the touch-off, the dot ladder and shots. It never homes.
     */
    public Future<RunResult> checkZ() throws Exception {
        requireAvailable();
        RunResult r = newRun("z-check", null, "z check", 0, 0);
        return submit(r, () -> {
            DispenseConfig c = cfg;
            Session s = new Session(c, r);
            r.interlocks = Interlocks.checkBasic(interlockInputs(c, null, 0, null, false, false));
            if (!r.interlocks.ok()) {
                return s.refuse(r.interlocks.toString());
            }
            r.zCheck = zCheckIfNeeded(s, null, needleTasksScope(c), s.exec::isAbortRequested);
            r.message = r.zCheck == null ? "Z data were measured after the last home: nothing to check"
                    : String.format(Locale.ROOT, "Z checked: N1 touched %s at Z %.3f, %.3f mm from %.3f", ZCheck.describe(
                            r.zCheck.refName), r.zCheck.z, r.zCheck.deltaMm, r.zCheck.refZ);
            return s.ok();
        });
    }

    // ------------------------------------------------------------------------------------------
    // Pad calibration (docs/instancelabs/PROFILE_CALIBRATION_DESIGN.md §7)
    // ------------------------------------------------------------------------------------------

    /** Incremented by every STOP ({@link #abort}); a task queued at another value refuses at its start (§7.8). */
    public long stopEpoch() {
        return stopEpoch.get();
    }

    /**
     * The camera look at a weigh coupon before N1 touches it (§7.2 step 2): its colour is the blank's,
     * its middle is clean, and the margin around the weigh grid lies on it (the corners' looks cover the
     * margin up to the coupon's edge). A coupon off its place is told by where its edge is.
     */
    private void couponLook(SurfaceProbeRequest req, Task t) throws Exception {
        SurfaceProbeRequest.Coupon k = req.coupon;
        DotImager im = imager();
        if (im == null || !im.isAvailable()) {
            throw refused(NO_CAMERA_PCAL);
        }
        phase(t.r, "coupon: look");
        double[] blank = null;
        if (k.blankSpot != null) {
            DotImager.Frame f = im.captureLit(k.blankSpot[0], k.blankSpot[1], null);
            if (f != null && f.image != null) {
                BareSpotCheck.Result b = BareSpotCheck.check(f.image, f.mmPerPxX, f.mmPerPxY, null);
                blank = new double[] {b.refB, b.refG, b.refR};
            }
        }
        double[] centre = k.looks.get(k.looks.size() - 1);
        DotImager.Frame cf = im.captureLit(centre[0], centre[1], req.nominalZ);
        if (cf == null || cf.image == null) {
            throw refused(NO_CAMERA_PCAL);
        }
        BareSpotCheck.Result cr = BareSpotCheck.check(cf.image, cf.mmPerPxX, cf.mmPerPxY, null);
        double[] ref = {cr.refB, cr.refG, cr.refR};
        if (blank != null && PadLadder.colourDistance(ref, blank) > PadLadder.COUPON_COLOUR_DELTA) {
            throw refused(String.format(Locale.ROOT, "%s is missing or is not blank PCB: at X %.1f, Y %.1f its colour "
                    + "is %.0f from the blank's (at most %.0f): lay a clean coupon of the blank's PCB on %s", k.label,
                    centre[0], centre[1], PadLadder.colourDistance(ref, blank), PadLadder.COUPON_COLOUR_DELTA,
                    req.surfaceName));
        }
        if (!cr.bare) {
            throw refused(String.format(Locale.ROOT, "%s is not clean at X %.1f, Y %.1f (%s): wipe it and lay it back on "
                    + "%s", k.label, centre[0], centre[1], cr.reason, req.surfaceName));
        }
        BareSpotCheck.Settings corner = PadLadder.couponCornerSettings();
        for (int i = 0; i < k.looks.size() - 1; i++) {
            if (t.aborted()) {
                throw new DispenseExecutor.AbortedException(abortReason(t.s == null ? null : t.s.exec,
                        "stopped during the coupon look"));
            }
            double[] at = k.looks.get(i);
            DotImager.Frame f = im.captureLit(at[0], at[1], req.nominalZ);
            if (f == null || f.image == null) {
                throw refused(NO_CAMERA_PCAL);
            }
            // where its edges are: the margin around the weigh grid must lie on the coupon
            double shift = k.rect == null ? Double.NaN : PadLadder.couponEdgeShift(f, k.rect, ref);
            if (shift > k.slackMm + COUPON_SHIFT_MM) {
                throw refused(String.format(Locale.ROOT, "%s is not at its place: its edge is %.1f mm inside the weigh "
                        + "grid's margin (lay it on %s again, or set the place where it lies: Setup › Places)", k.label,
                        shift, req.surfaceName));
            }
            BareSpotCheck.Result b = BareSpotCheck.check(f.image, f.mmPerPxX, f.mmPerPxY, corner);
            if (!b.bare) {
                throw refused(String.format(Locale.ROOT, "%s is not clean or not flat in the weigh grid's margin at X "
                        + "%.1f, Y %.1f (%s): wipe it and lay it straight on %s", k.label, at[0], at[1], b.reason,
                        req.surfaceName));
            }
        }
    }

    /** A coupon edge this much farther inside its nominal place than the grid allows is a coupon off its place (mm). */
    static final double COUPON_SHIFT_MM = 0.1;
    static final String NO_CAMERA_PCAL = "the pad calibration needs the top camera: it looks at every spot before "
            + "dispensing and measures every dot";

    /**
     * Why an engine task may not dispense on or probe a coupon place now (§7.5 reservation): it carries
     * weigh dots whose readings are still to be typed. Null when it may.
     */
    public String couponReservation(String place) {
        if (place == null) {
            return null;
        }
        DispenseConfig c = cfg;
        long now = System.currentTimeMillis();
        try {
            DoseRecord.Epochs ep = new DoseRecord.Epochs(getControllerConnectedAtMs(), isSimulatorConfirmed());
            for (DoseRecord r : db.pendingCoupons(now - (long) (c.padCalibration.doseRecordMaxAgeH * 3600e3))) {
                if (place.equals(r.couponPlace) && weighable(r, now, c, ep)) {
                    return String.format(Locale.ROOT, "%s carries %d dots waiting to be weighed: type the readings or "
                            + "skip them (Calibrate › Pad calibration)", place, r.n);
                }
            }
        }
        catch (SQLException e) {
            return "the weigh coupons waiting for their readings could not be read: " + e.getMessage();
        }
        return null;
    }

    /**
     * A coupon record can still be weighed: its reading would be a fresh mass record (§1.7: the
     * recipe, the regulator, the tip, the paste, the age and the simulator flag; a reconnect does not
     * change what lies on the coupon).
     */
    public static boolean weighable(DoseRecord coupon, long nowMs, DispenseConfig c, DoseRecord.Epochs epochs) {
        DoseRecord asMass = new DoseRecord();
        asMass.kind = DoseRecord.MASS;
        asMass.createdAtMs = coupon.createdAtMs;
        asMass.steps = coupon.steps;
        asMass.doseFp = coupon.doseFp;
        asMass.regulatorPsi = coupon.regulatorPsi;
        asMass.tipKey = coupon.tipKey;
        asMass.pasteKey = coupon.pasteKey;
        asMass.simulated = coupon.simulated;
        return asMass.whyStale(nowMs, c, epochs) == null;
    }

    /** Stamps a band of the calibration area as used (before streaming: an aborted ladder leaves it dirty). */
    private synchronized void reserveArea(Double heightMm, String runId) {
        if (heightMm == null || heightMm <= 0) {
            return;
        }
        try {
            DispenseConfig c = cfg.copy();
            double from = c.calibrationArea.reserve(heightMm);
            updateConfig(c);
            db.event(runId, "INFO", "PCAL", String.format(Locale.ROOT, "calibration area %.1f..%.1f mm used (%.1f mm "
                    + "left)", from, from + heightMm, c.calibrationArea.leftMm()));
        }
        catch (IOException e) {
            throw new IllegalStateException("could not record the used calibration area: " + e.getMessage(), e);
        }
    }

    /**
     * One engine step of the pad calibration (§7.3): the ladder on the blank or the weigh dots on the
     * coupons, as dose blocks in one pressure session with the board runs' phase rules. Home and Z
     * check when needed; every spot looked at before (a spot that is not bare refuses everything);
     * after the stream every dot found, landed, phased, its thread to the next dot told; crops; the
     * head parked over the next purge spot. The Future never throws for task failures; see the result.
     */
    public Future<DoseBlocksResult> runDoseBlocks(DoseBlocksRequest req) throws Exception {
        requireAvailable();
        boolean weigh = DoseBlocksRequest.WEIGH.equals(req.kind);
        RunResult r = newRun(req.kind, null, weigh ? "weigh coupons" : "blank", 0, req.dots());
        DoseBlocksResult res = new DoseBlocksResult();
        res.run = r;
        res.home = new HomeReport();
        Task t = new Task(r, "home");
        cameraTourAbort = false;
        // a STOP since the job built the request refuses it at its start too (the body checks req.stopEpoch)
        return submitResult(r, res, () -> t.run(() -> doseBlocks(req, res, t)), () -> {
            res.ok = r.status == RunResult.Status.OK || r.status == RunResult.Status.RUNNING;
            res.stoppedStage = r.status == RunResult.Status.ABORTED ? t.endedIn : null;
            if (res.ok) {
                int found = 0;
                for (DoseBlocksResult.BlockOut b : res.blocks) {
                    found += b.found();
                }
                res.message = String.format(Locale.ROOT, "%d %s laid %s, %d found by the camera", req.dots(),
                        req.dots() == 1 ? "dot" : "dots", weigh ? "on the weigh coupons" : "on the blank", found);
                r.message = res.message;
            }
            else {
                res.message = r.message;
            }
        });
    }

    private void doseBlocks(DoseBlocksRequest req, DoseBlocksResult res, Task t) throws Exception {
        RunResult r = t.r;
        hook("dose-blocks");
        DispenseConfig c = cfg;
        if (stopEpoch.get() != req.stopEpoch) {
            throw refused(STOPPED_SINCE_QUEUED);
        }
        refuseOtherTip(req, c);
        res.setupKey = c.tip.key;
        res.needleSinceMs = c.needleChangedAtMs;
        if (req.blocks.isEmpty() || req.dots() == 0) {
            throw refused("PCAL: nothing to dispense");
        }
        for (DoseBlocksRequest.Block b : req.blocks) {
            String why = couponReservation(b.couponPlace);
            if (why != null) {
                throw refused(why);
            }
        }
        Session s = t.start(c);
        HomeTracker.Plan hp = homePlan();
        t.detail.put("home", homeDetail(hp));
        t.detail.put("blocks", req.blocks.size());
        t.detail.put("dots", req.dots());
        t.stepsPlanned = req.dots();
        t.send();
        homeIfNeeded(s, res.home, t::aborted);
        r.interlocks = Interlocks.checkBasic(interlockInputs(c, null, 0, null, false, false));
        if (!r.interlocks.ok()) {
            throw refused(r.interlocks.toString());
        }
        // the dots on their surfaces: the area's plane (I4 like the blank), a coupon's own plane (floor only)
        int flush = c.padCalibration.flushDots;
        List<GcodeStreamBuilder.BlockSpec> specs = new ArrayList<>();
        List<DoseBlocks.Run> runs = new ArrayList<>();
        Interlocks.Scope scope = new Interlocks.Scope();
        List<double[]> all = new ArrayList<>();
        List<Integer> blockOf = new ArrayList<>();
        int w = 0;
        for (int k = 0; k < req.blocks.size(); k++) {
            DoseBlocksRequest.Block b = req.blocks.get(k);
            if (!DoseBlocks.isAllowed(b.netSteps, c)) {
                throw refused(String.format(Locale.ROOT, "PCAL: %d steps is not a dot the %s takes (a quarter, half or "
                        + "one turn, %d steps at least)", b.netSteps, c.tip.name, c.tip.minDotSteps));
            }
            SurfacePlane plane = planeOf(c, b.surfaceName);
            if (plane == null) {
                throw refused("I4: no '" + b.surfaceName + "' surface: probe it first");
            }
            Double floorRef = null;
            if (b.couponPlace != null) {
                DispenseConfig.NamedLocation place = c.location(b.couponPlace);
                if (place == null) {
                    throw refused("PCAL: the weigh coupon place '" + b.couponPlace + "' is not set (Setup › Places)");
                }
                String why = couponPlane(c, b, place, plane, isSimulatorConfirmed());
                if (why == null) {
                    why = placeMovedRefusal(b.couponPlace, place);
                }
                if (why != null) {
                    throw refused(why);
                }
                floorRef = place.z;
            }
            else if (scope.surfaceName == null || scope.surfaceName.equals(b.surfaceName)) {
                scope.surfaceName = b.surfaceName;
                scope.surfaceTargets.addAll(b.xy);
            }
            else {
                scope.moreSurfaces.computeIfAbsent(b.surfaceName, n -> new ArrayList<>()).addAll(b.xy);
            }
            List<GcodeStreamBuilder.DotSpec> dots = new ArrayList<>();
            for (int i = 0; i < b.xy.size(); i++) {
                double[] xy = b.xy.get(i);
                double z = plane.z(xy[0], xy[1]) + c.needleZOffsetMm + c.gapMm();
                dots.add(new GcodeStreamBuilder.DotSpec(xy[0], xy[1], z, z + c.hopHeightMm, b.netSteps / c.bStepsPerDeg,
                        w + i, b.label + "#" + (i + 1)));
                all.add(xy);
                blockOf.add(k);
            }
            GcodeStreamBuilder.BlockSpec bs = new GcodeStreamBuilder.BlockSpec(b.netSteps, dots, b.label);
            bs.floorRefZ = floorRef;
            specs.add(bs);
            runs.add(new DoseBlocks.Run(b.netSteps, w, dots.size()));
            w += dots.size();
        }
        // the landing check purges in its own band (§22.3): explicit spots, no purge-row spot is used
        int need = req.purgeXy != null ? 0 : DoseBlocks.purgeSpotsNeeded(runs, c, flush);
        boolean partial = DoseBlocks.anyPartial(runs, c);
        DispenseConfig.PurgeRow row = c.purgeRow;
        if (need > 0 && row.isDefined()) {
            List<double[]> spots = new ArrayList<>();
            for (int i = 0; i < Math.min(need, row.spotsLeft()); i++) {
                spots.add(row.spot(row.usedSpots + i));
            }
            scope.moreSurfaces.computeIfAbsent(row.surfaceName, n -> new ArrayList<>()).addAll(spots);
        }
        if (req.purgeXy != null && !req.purgeXy.isEmpty()) {
            String surface = req.blocks.get(0).surfaceName;
            if (surface.equals(scope.surfaceName)) {
                scope.surfaceTargets.addAll(req.purgeXy);
            }
            else {
                scope.moreSurfaces.computeIfAbsent(surface, n -> new ArrayList<>()).addAll(req.purgeXy);
            }
        }
        if (req.landingCheck) {
            scope.landingWaiver = Interlocks.Scope.LANDING_CHECK;
        }
        t.stage = "z check";
        res.zCheck = zCheckIfNeeded(s, null, scope, t::aborted);
        for (DoseBlocksRequest.Block b : req.blocks) {
            String why = b.couponPlace == null ? null : couponFrame(b.surfaceName);
            if (why != null) {
                throw refused(why);
            }
        }
        t.stage = "interlocks";
        r.interlocks = Interlocks.check(blockInputs(c, scope, 0, need, partial, null));
        if (!r.interlocks.ok()) {
            throw refused(r.interlocks.toString());
        }
        List<GcodeStreamBuilder.DotSpec> purge = req.purgeXy != null ? bandPurge(c, req) : purgeSpecs(c, need);
        GcodeStreamBuilder.TipXy tipXy = tipXy(c);
        GcodeProgram estimate = GcodeStreamBuilder.forBlocks(c, s.mapper, specs, purge, flush, bZeroed.get(),
                s.keepalive, 0L, null, tipXy);
        r.interlocks = Interlocks.check(blockInputs(c, scope, estimate.estimatedPressurisedSeconds, need, partial,
                estimate.blocks));
        if (!r.interlocks.ok()) {
            throw refused(r.interlocks.toString());
        }
        // before frames: camera only, and every spot must be bare
        DotImager im = req.image ? imager() : null;
        if (req.image && (im == null || !im.isAvailable())) {
            throw refused(NO_CAMERA_PCAL);
        }
        List<PadLadder.Tile> tiles = !req.image ? new ArrayList<>() : req.lookXy != null
                ? lookTiles(req.lookXy, all, im.fieldOfViewMm())
                : PadLadder.tiles(all, im.fieldOfViewMm(), PadLadder.TILE_INSET_MM);
        int[] tileOf = new int[all.size()];
        List<DotImager.Frame> before = new ArrayList<>();
        if (req.image) {
            t.stage = "before images";
            phase(r, "pad calibration: looking at " + all.size() + " spots (" + frames(tiles.size()) + ")");
            for (int k = 0; k < tiles.size(); k++) {
                if (t.aborted()) {
                    throw new DispenseExecutor.AbortedException(abortReason(s.exec, "stopped before dispensing"));
                }
                PadLadder.Tile tile = tiles.get(k);
                DotImager.Frame f = im.capture(tile.cx, tile.cy);
                res.frames++;
                before.add(f);
                for (int i : tile.dots) {
                    tileOf[i] = k;
                    double[] xy = all.get(i);
                    int steps = req.blocks.get(blockOf.get(i)).netSteps;
                    BareSpotCheck.Result b = PadLadder.bareAt(f, xy[0], xy[1],
                            PadLadder.spotSettings(dotUpperMm(steps)));
                    if (b == null || !b.bare) {
                        res.refusedSpots.add(String.format(Locale.ROOT, "%.2f,%.2f: %s", xy[0], xy[1],
                                b == null ? "the spot is not inside the camera frame" : b.reason));
                    }
                }
            }
            if (!res.refusedSpots.isEmpty()) {
                String first = res.refusedSpots.get(0);
                String[] xy = first.substring(0, first.indexOf(':')).split(",");
                boolean coupon = req.blocks.get(0).couponPlace != null;
                throw refused(String.format(Locale.ROOT, "the %s is not clean or not flat under %d of the dots (first at "
                        + "X %s, Y %s: %s): %s", coupon ? "weigh coupon" : "calibration area", res.refusedSpots.size(),
                        xy[0], xy[1], first.substring(first.indexOf(':') + 2), coupon
                                ? "wipe it and lay it back on its place" : "wipe it, or move the calibration area "
                                        + "(Calibrate › Pad calibration)"));
            }
        }
        if (stopEpoch.get() != req.stopEpoch) {
            throw refused(STOPPED_SINCE_QUEUED);
        }
        refuseOtherTip(req, cfg);
        // the stream, block by block on the progress
        t.stage = "dispensing";
        int[] lastBlock = {-1};
        DispenseListener progress = new DispenseListener() {
            @Override
            public void onDot(RunResult run, RunResult.DotTiming timing, int done, int total) {
                if (run != r || timing.dotIndex < 0 || timing.dotIndex >= blockOf.size()) {
                    return;
                }
                int k = blockOf.get(timing.dotIndex);
                if (k != lastBlock[0]) {
                    lastBlock[0] = k;
                    phase(r, String.format(Locale.ROOT, "pad calibration: dispensing block %d of %d (%s)", k + 1,
                            req.blocks.size(), DoseBlocks.turnName(req.blocks.get(k).netSteps)));
                }
                t.detail.put("block", k + 1);
                t.detail.put("dot", timing.dotIndex + 1);
                t.stepsDone = timing.dotIndex + 1;
                double end = estimate.dotEndSeconds == null ? 0 : estimate.dotEndSeconds[timing.dotIndex];
                t.secondsLeft = Math.max(0, estimate.estimatedSeconds - end) + 0.9 * tiles.size();
                t.send();
            }
        };
        addListener(progress);
        GcodeProgram p;
        try {
            p = s.runSegment(() -> {
                Long count = s.readBCount(false);
                if (count == null && partial) {
                    throw new RunRefusedException("I11: the controller did not report the B step count (M114 Count): the "
                            + "start angle of a partial-turn dose cannot be set. Nothing was sent.", null);
                }
                return GcodeStreamBuilder.forBlocks(c, s.mapper, specs, purge, flush, bZeroed.get(), s.keepalive, count,
                        null, tipXy);
            }, "pad calibration: dispensing", () -> {
                reservePurgeSpots(req.purgeXy != null ? 0 : purge.size(), r.runId);
                reserveArea(req.areaReserveMm, r.runId);
            });
        }
        finally {
            removeListener(progress);
        }
        r.purgeDots = p.purgeDotCount;
        r.bCountAtStart = p.bCountAtStart;
        r.workStartAngleDeg = p.workStartAngleDeg;
        res.bCountAtStart = p.bCountAtStart;
        // what the camera finds
        for (int k = 0; k < req.blocks.size(); k++) {
            DoseBlocksRequest.Block b = req.blocks.get(k);
            DoseBlocksResult.BlockOut o = new DoseBlocksResult.BlockOut();
            o.netSteps = b.netSteps;
            o.label = b.label;
            o.couponPlace = b.couponPlace;
            o.hopMm = b.hopMm;
            res.blocks.add(o);
        }
        int startAngle = c.profile.startAngleSteps(c);
        for (int i = 0; i < all.size(); i++) {
            DoseBlocksRequest.Block b = req.blocks.get(blockOf.get(i));
            DoseBlocksResult.DotOut d = new DoseBlocksResult.DotOut();
            d.x = all.get(i)[0];
            d.y = all.get(i)[1];
            if (p.dotStartCount != null && i < p.dotStartCount.length) {
                d.startCount = p.dotStartCount[i];
                d.phase = DoseBlocks.isPartial(b.netSteps, c.bStepsPerRev)
                        ? LadderStats.phaseOf(d.startCount, startAngle, b.netSteps) : 0;
            }
            res.blocks.get(blockOf.get(i)).dots.add(d);
        }
        if (req.image) {
            t.stage = "after images";
            phase(r, "pad calibration: measuring the dots (" + frames(tiles.size()) + ")");
            afterImages(req, res, t, im, tiles, tileOf, before, all, blockOf);
        }
        if (req.parkAfter) {
            park(r);
        }
        t.stepsDone = t.stepsPlanned;
        t.secondsLeft = 0;
        t.send();
        s.ok();
    }

    /**
     * The program of dose blocks as {@link #runDoseBlocks} would build it now, for the time it takes (the
     * pad calibration's preview): every dot on its surface, or at its place's Z when the surface is not
     * probed yet; the purge row's next spots.
     */
    public GcodeProgram estimateBlocks(List<DoseBlocksRequest.Block> blocks) throws Exception {
        requireAvailable();
        DispenseConfig c = cfg;
        List<GcodeStreamBuilder.BlockSpec> specs = new ArrayList<>();
        List<DoseBlocks.Run> runs = new ArrayList<>();
        int w = 0;
        for (DoseBlocksRequest.Block b : blocks) {
            SurfacePlane plane = planeOf(c, b.surfaceName);
            DispenseConfig.NamedLocation place = c.location(b.couponPlace != null ? b.couponPlace : c.blankLocationName);
            double fallback = place != null ? place.z : c.needleAnchor != null ? c.needleAnchor.surfaceZMm : 0;
            List<GcodeStreamBuilder.DotSpec> dots = new ArrayList<>();
            for (double[] xy : b.xy) {
                double z = (plane != null ? plane.z(xy[0], xy[1]) : fallback) + c.needleZOffsetMm + c.gapMm();
                dots.add(new GcodeStreamBuilder.DotSpec(xy[0], xy[1], z, z + c.hopHeightMm,
                        b.netSteps / c.bStepsPerDeg, w++, b.label));
            }
            GcodeStreamBuilder.BlockSpec bs = new GcodeStreamBuilder.BlockSpec(b.netSteps, dots, b.label);
            bs.floorRefZ = b.couponPlace != null && place != null ? place.z : null;
            specs.add(bs);
            runs.add(new DoseBlocks.Run(b.netSteps, w - dots.size(), dots.size()));
        }
        int need = DoseBlocks.purgeSpotsNeeded(runs, c, c.padCalibration.flushDots);
        OpenPnpRawMapper mapper = new OpenPnpRawMapper((ReferenceMachine) configuration.getMachine(), nozzle,
                c.bAxisName);
        return GcodeStreamBuilder.forBlocks(c, mapper, specs, purgeSpecs(c, need), c.padCalibration.flushDots,
                bZeroed.get(), false, 0L, null, tipXy(c));
    }

    /** The Interlocks of a block program: I4 by the scope, I9 for its purge spots, I11 for a partial turn, I6-feed. */
    private Interlocks.Inputs blockInputs(DispenseConfig c, Interlocks.Scope scope, double est, int purge,
            boolean partial, List<GcodeProgram.BlockInfo> blocks) {
        Interlocks.Inputs in = interlockInputs(c, null, est, scope, false, false);
        in.purgeDots = purge;
        in.partialTurn = partial;
        in.programBlocks = blocks;
        return in;
    }

    /**
     * A weigh coupon's plane (§7.3 step 2): probed on this connection's kind of machine, flat, near its
     * place's Z, and under every weigh dot of the coupon's rectangle. Null when it is fine.
     */
    private static String couponPlane(DispenseConfig c, DoseBlocksRequest.Block b, DispenseConfig.NamedLocation place,
            SurfacePlane plane, boolean simulator) {
        DispenseConfig.SurfaceMap m = c.surface(b.surfaceName);
        if (m != null && Boolean.TRUE.equals(m.simulated) && !simulator) {
            return "I4: the '" + b.surfaceName + "' coupon was probed on the simulator: probe it again on this machine";
        }
        if (plane.maxAbsResidualMm > c.surfaceMaxResidualMm) {
            return String.format(Locale.ROOT, "I4: the '%s' coupon is not flat: largest residual %.3f mm, limit %.3f mm",
                    b.surfaceName, plane.maxAbsResidualMm, c.surfaceMaxResidualMm);
        }
        double window = c.padCalibration.couponWindowMm;
        if (plane.minZ < place.z - window - 1e-9 || plane.maxZ > place.z + window + 1e-9) {
            return String.format(Locale.ROOT, "I4: the '%s' coupon was touched at Z %.3f..%.3f, more than %.2f mm from "
                    + "its place Z %.3f: probe it again", b.surfaceName, plane.minZ, plane.maxZ, window, place.z);
        }
        return null;
    }

    /**
     * I4 refusal (code included) of a place set before this home while the Z check of this home failed (rc1 review: a
     * coupon's Z moved with the surfaces), or null.
     */
    private String placeMovedRefusal(String name, DispenseConfig.NamedLocation place) {
        ZCheck.Result z = homeTracker.getZCheck();
        Long home = homeTracker.getLastHomeAtMs();
        return ZCheck.failed(z, home) && ZCheck.placeBefore(place, home) ? "I4: " + ZCheck.placeMovedMessage(name, place,
                z) : null;
    }

    /** "1 frame", "4 frames": the camera frames of a pad calibration step, as its phase text says them. */
    private static String frames(int n) {
        return n + (n == 1 ? " frame" : " frames");
    }

    /**
     * I4 for a weigh coupon's plane: no Z check touches a coupon, so its touches count only in the Z frame they were
     * made in (measured after the last home), or when this home's Z check vouched for that frame. Null when it may
     * be used.
     */
    private String couponFrame(String surfaceName) {
        Long home = homeTracker.getLastHomeAtMs();
        DispenseConfig.SurfaceMap m = cfg.surface(surfaceName);
        if (home == null || m == null || m.setAtMs != null && m.setAtMs >= home) {
            return null;
        }
        ZCheck.Stale d = new ZCheck.Stale(ZCheck.surfaceDatum(surfaceName), m.setAtMs, m.homeAtMs);
        if (ZCheck.unchecked(homeTracker.getZCheck(), home, Collections.singletonList(d)).isEmpty()) {
            return null;
        }
        return "I4: the '" + surfaceName + "' coupon was touched before the home at " + ZCheck.clock(home)
                + ", and no Z check since vouches for its Z: touch it again before laying dots on it";
    }

    /** After frames of every tile; every dot found, landed, its thread to the next told; 3 crops per block. */
    private void afterImages(DoseBlocksRequest req, DoseBlocksResult res, Task t, DotImager im, List<PadLadder.Tile> tiles,
            int[] tileOf, List<DotImager.Frame> before, List<double[]> all, List<Integer> blockOf) throws Exception {
        List<PadLadder.FramePair> pairs = new ArrayList<>();
        List<List<double[]>> blobs = new ArrayList<>();
        List<List<Dot>> blobDots = new ArrayList<>();
        for (int k = 0; k < tiles.size(); k++) {
            PadLadder.Tile tile = tiles.get(k);
            DotImager.Frame after = im.capture(tile.cx, tile.cy);
            res.frames++;
            pairs.add(new PadLadder.FramePair(before.get(k), after));
            List<double[]> at = new ArrayList<>();
            List<Dot> found = im.inspectAll(before.get(k), after);
            for (Dot d : found) {
                at.add(new double[] {tile.cx + d.xMm, tile.cy + d.yMm});
                if (req.landingCheck) {
                    newBlob(res, tile.cx + d.xMm, tile.cy + d.yMm, d);
                }
            }
            blobs.add(at);
            blobDots.add(found);
        }
        blockPhotos(req, res, tiles, tileOf, pairs);
        // the nearest new blob within the block's radius; the blob a dot was matched to (two dots, one blob: a thread)
        int[] blobOf = new int[all.size()];
        int w = 0;
        for (int k = 0; k < res.blocks.size(); k++) {
            DoseBlocksRequest.Block b = req.blocks.get(k);
            for (DoseBlocksResult.DotOut d : res.blocks.get(k).dots) {
                int tile = tileOf[w];
                List<double[]> at = blobs.get(tile);
                int best = -1;
                double bestD = b.matchRadiusMm;
                for (int j = 0; j < at.size(); j++) {
                    double dist = Math.hypot(at.get(j)[0] - d.x, at.get(j)[1] - d.y);
                    if (dist <= bestD) {
                        bestD = dist;
                        best = j;
                    }
                }
                blobOf[w] = best < 0 ? -1 : tile * 100000 + best;
                if (best >= 0) {
                    Dot f = blobDots.get(tile).get(best);
                    d.dxMm = at.get(best)[0] - d.x;
                    d.dyMm = at.get(best)[1] - d.y;
                    d.found = new Dot(d.dxMm, d.dyMm, f.diameterMm, f.areaMm2, f.circularity);
                    d.string = f.circularity < Limits.STRING_CIRCULARITY;
                }
                w++;
            }
        }
        // threads between consecutive dots of a block (§7.4), crops
        w = 0;
        for (int k = 0; k < res.blocks.size(); k++) {
            DoseBlocksResult.BlockOut o = res.blocks.get(k);
            double model = dotUpperMm(o.netSteps) / 2;
            for (int i = 0; i < o.dots.size(); i++) {
                DoseBlocksResult.DotOut a = o.dots.get(i);
                if (i + 1 < o.dots.size()) {
                    DoseBlocksResult.DotOut b = o.dots.get(i + 1);
                    if (blobOf[w] >= 0 && blobOf[w] == blobOf[w + 1]) {
                        a.threadToNext = true;
                    }
                    else {
                        List<PadLadder.FramePair> fp = new ArrayList<>();
                        fp.add(pairs.get(tileOf[w]));
                        if (tileOf[w + 1] != tileOf[w]) {
                            fp.add(pairs.get(tileOf[w + 1]));
                        }
                        double ra = a.found != null ? a.found.diameterMm / 2 : model;
                        double rb = b.found != null ? b.found.diameterMm / 2 : model;
                        double share = PadLadder.corridorShare(fp, a.found != null ? a.x + a.dxMm : a.x,
                                a.found != null ? a.y + a.dyMm : a.y, ra, b.found != null ? b.x + b.dxMm : b.x,
                                b.found != null ? b.y + b.dyMm : b.y, rb);
                        a.threadToNext = share > PadLadder.THREAD_SHARE;
                    }
                }
                w++;
            }
            crops(res, k, o, pairs, tileOf, w - o.dots.size());
        }
    }

    /**
     * The machine thread's refusal of a step of a job that started under another tip setup or needle (tip setups
     * design §22.3): nothing is sent.
     */
    private void refuseOtherTip(DoseBlocksRequest req, DispenseConfig c) throws RunRefusedException {
        if (req.forTip != null && !req.forTip.equals(c.tip.key)) {
            throw refused("The tip changed after this calibration started (the " + c.tip.name + " was mounted"
                    + atWords(mountedAtMs(c)) + "): nothing was sent.");
        }
        if (req.needleSinceMs != null && !req.needleSinceMs.equals(c.needleChangedAtMs)) {
            throw refused("The needle was changed" + atWords(c.needleChangedAtMs) + " after this calibration started: "
                    + "nothing was sent.");
        }
    }

    /**
     * A dose's upper dot size for the looks and the threads (§22.2): the mounted tip's when it knows it, else the
     * bench model's, at least the tip's OD + 0.4 mm (a tip whose dots were never measured).
     */
    private double dotUpperMm(int steps) {
        try {
            Tip tip = mountedTip();
            if (tip.knowsSize(steps)) {
                return tip.dHiMm(steps);
            }
            return Math.max(DoseModel.dHiMm(steps), tip.odMm + 0.4);
        }
        catch (SQLException e) {
            return DoseModel.dHiMm(steps);
        }
    }

    /** The landing check's purge dots (§22.3): at the request's spots in its band, on the first block's surface. */
    private static List<GcodeStreamBuilder.DotSpec> bandPurge(DispenseConfig c, DoseBlocksRequest req) {
        List<GcodeStreamBuilder.DotSpec> l = new ArrayList<>();
        SurfacePlane plane = planeOf(c, req.blocks.get(0).surfaceName);
        for (int i = 0; plane != null && i < req.purgeXy.size(); i++) {
            double[] xy = req.purgeXy.get(i);
            double z = plane.z(xy[0], xy[1]) + c.needleZOffsetMm + c.gapMm();
            l.add(new GcodeStreamBuilder.DotSpec(xy[0], xy[1], z, z + c.hopHeightMm, c.profile.netDeg(c),
                    GcodeStreamBuilder.PURGE_INDEX, "purge#band" + i));
        }
        return l;
    }

    /**
     * Camera frames centred on the request's spots (the landing check's band, §22.3): every dot in the frame that
     * holds it farthest inside (else the nearest frame).
     */
    static List<PadLadder.Tile> lookTiles(List<double[]> looks, List<double[]> dots, double[] fov) {
        List<PadLadder.Tile> tiles = new ArrayList<>();
        for (double[] l : looks) {
            PadLadder.Tile t = new PadLadder.Tile();
            t.cx = l[0];
            t.cy = l[1];
            tiles.add(t);
        }
        for (int i = 0; i < dots.size(); i++) {
            double[] d = dots.get(i);
            int best = 0;
            double margin = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < tiles.size(); k++) {
                PadLadder.Tile t = tiles.get(k);
                double m = fov == null ? -Math.hypot(d[0] - t.cx, d[1] - t.cy)
                        : Math.min(fov[0] / 2 - Math.abs(d[0] - t.cx), fov[1] / 2 - Math.abs(d[1] - t.cy));
                if (m > margin) {
                    margin = m;
                    best = k;
                }
            }
            if (!tiles.isEmpty()) {
                tiles.get(best).dots.add(i);
            }
        }
        return tiles;
    }

    /** A new blob of the landing check's frames (machine mm), once: overlapping frames see it twice. */
    private static void newBlob(DoseBlocksResult res, double x, double y, Dot d) {
        for (DoseBlocksResult.Blob b : res.newBlobs) {
            if (Math.hypot(b.x - x, b.y - y) <= org.openpnp.instancelabs.padprofile.LandingFit.MATCH_MM) {
                return;
            }
        }
        DoseBlocksResult.Blob b = new DoseBlocksResult.Blob();
        b.x = x;
        b.y = y;
        b.diameterMm = d.diameterMm;
        b.areaMm2 = d.areaMm2;
        b.circularity = d.circularity;
        res.newBlobs.add(b);
    }

    /**
     * One result photo per block (§22.4): the after frame of its first dot's tile, at most
     * {@link PhotoStore#RESULT_MAX_WIDTH_PX} wide, as JPEG bytes (never the image: the jobs keep their results).
     */
    private static void blockPhotos(DoseBlocksRequest req, DoseBlocksResult res, List<PadLadder.Tile> tiles,
            int[] tileOf, List<PadLadder.FramePair> pairs) {
        int w = 0;
        for (int k = 0; k < req.blocks.size(); k++) {
            DoseBlocksRequest.Block b = req.blocks.get(k);
            int first = w;
            w += b.xy.size();
            if (b.xy.isEmpty() || first >= tileOf.length || tileOf[first] >= pairs.size()) {
                continue;
            }
            DotImager.Frame f = pairs.get(tileOf[first]).after;
            if (f == null || f.image == null) {
                continue;
            }
            try {
                java.awt.image.BufferedImage img = f.image;
                double scale = img.getWidth() > PhotoStore.RESULT_MAX_WIDTH_PX
                        ? (double) PhotoStore.RESULT_MAX_WIDTH_PX / img.getWidth() : 1;
                java.awt.image.BufferedImage out = PhotoStore.rgb(PhotoStore.scaleTo(img,
                        (int) Math.round(Math.max(img.getWidth(), img.getHeight()) * scale)));
                DoseBlocksResult.BlockPhoto p = new DoseBlocksResult.BlockPhoto();
                p.block = k;
                p.doseSteps = b.netSteps;
                p.couponPlace = b.couponPlace;
                PadLadder.Tile tile = tiles.get(tileOf[first]);
                p.xMm = tile.cx;
                p.yMm = tile.cy;
                p.mmPerPx = f.mmPerPxX * img.getWidth() / (double) out.getWidth();
                p.widthPx = out.getWidth();
                p.heightPx = out.getHeight();
                p.jpeg = PhotoStore.jpeg(out);
                res.photos.add(p);
            }
            catch (IOException | RuntimeException e) {
                Logger.warn(e, "dispense: no result photo of block {}", k + 1);
            }
        }
    }

    /** First found, median and worst dot of a block (by area; a miss is the worst), before and after. */
    private static void crops(DoseBlocksResult res, int k, DoseBlocksResult.BlockOut o, List<PadLadder.FramePair> pairs,
            int[] tileOf, int first) throws IOException {
        List<Integer> found = new ArrayList<>();
        int miss = -1;
        for (int i = 0; i < o.dots.size(); i++) {
            if (o.dots.get(i).found != null) {
                found.add(i);
            }
            else if (miss < 0) {
                miss = i;
            }
        }
        if (found.isEmpty() && miss < 0) {
            return;
        }
        List<int[]> picks = new ArrayList<>();
        if (!found.isEmpty()) {
            picks.add(new int[] {found.get(0), 0});
            List<Integer> byArea = new ArrayList<>(found);
            byArea.sort((x, y) -> Double.compare(o.dots.get(x).found.areaMm2, o.dots.get(y).found.areaMm2));
            int median = byArea.get(byArea.size() / 2);
            picks.add(new int[] {median, 1});
            double m = o.dots.get(median).found.areaMm2;
            int worst = miss;
            if (worst < 0) {
                double dev = -1;
                for (int i : found) {
                    double d = Math.abs(o.dots.get(i).found.areaMm2 / m - 1);
                    if (d > dev) {
                        dev = d;
                        worst = i;
                    }
                }
            }
            picks.add(new int[] {worst, 2});
        }
        else {
            picks.add(new int[] {miss, 2});
        }
        String[] what = {"first", "median", "worst"};
        for (int[] pick : picks) {
            DoseBlocksResult.DotOut d = o.dots.get(pick[0]);
            PadLadder.FramePair fp = pairs.get(tileOf[first + pick[0]]);
            DoseBlocksResult.Crop c = new DoseBlocksResult.Crop();
            c.id = "b" + (k + 1) + "-d" + (pick[0] + 1) + "-" + what[pick[1]];
            c.block = k;
            c.dot = pick[0];
            c.what = what[pick[1]];
            c.beforePng = PadLadder.cropPng(fp.before, d.x, d.y);
            c.afterPng = PadLadder.cropPng(fp.after, d.x, d.y);
            res.crops.add(c);
        }
    }

    /** N2 at travel height over the next unused purge spot (a park failure is only a warning). */
    private void park(RunResult r) {
        DispenseConfig cur = cfg;
        if (!cur.purgeRow.isDefined() || cur.purgeRow.spotsLeft() <= 0) {
            return;
        }
        double[] xy = cur.purgeRow.spot(cur.purgeRow.usedSpots);
        try {
            phase(r, "pad calibration: parking over the purge row");
            Location at = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
            org.openpnp.util.MovableUtils.moveToLocationAtSafeZ(nozzle, new Location(LengthUnit.Millimeters, xy[0],
                    xy[1], at.getZ(), at.getRotation()));
        }
        catch (Exception e) {
            r.warnings.add("the head was not parked over the purge row: " + e.getMessage());
        }
    }

    /**
     * Writes the outcome of one pad type's calibration (§7.7): the type's new version, when the
     * calibration wrote one, and the {@code pad_calibrations} row. The engine-side rule: a
     * {@code characterized} version needs a record whose every check passed; a calibration never
     * writes {@code validated} (that stays the user's decision after a test board).
     *
     * <p>Tip setups (§22.3, §22.5): the record carries the setup and needle token the job started with; a version
     * written for another setup mounted since (409 {@code tip-changed}) or after a needle change since (409
     * {@code needle-changed}) saves nothing. A record that writes no version (a stopped or failed job) is history: it
     * is kept with the setup and token it names. Without the bench model, {@code characterized} needs fresh evidence of
     * the dose (409 {@code status-evidence}). The record gets its automatic "calibrated for" label, the job's result
     * photos of its dose and the setup's main picture.</p>
     */
    public synchronized PadCalibrationRecord applyPadCalibration(PadCalibrationRecord rec, PadType version)
            throws SQLException {
        DispenseConfig c = cfg;
        boolean other = rec.setupKey != null && !rec.setupKey.equals(c.tip.key);
        if (version != null && other) {
            throw new EngineRefusal(409, "tip-changed", "The tip changed during the calibration (the " + c.tip.name
                    + " was mounted" + atWords(mountedAtMs(c)) + "): nothing was saved.");
        }
        if (version != null && rec.setupKey != null && !java.util.Objects.equals(rec.needleSinceMs,
                c.needleChangedAtMs)) {
            throw new EngineRefusal(409, "needle-changed", "The needle was changed" + atWords(c.needleChangedAtMs)
                    + " during the calibration: nothing was saved.");
        }
        if (version != null) {
            if (!version.typeKey.equals(rec.typeKey)) {
                throw new IllegalArgumentException("the version is of pad type '" + version.typeKey + "', the "
                        + "calibration of '" + rec.typeKey + "'");
            }
            if (version.status == PadTypeStatus.VALIDATED) {
                throw new IllegalArgumentException("a pad calibration never writes validated: that is the user's "
                        + "decision after a test board (Validate)");
            }
            if (version.status == PadTypeStatus.CHARACTERIZED
                    && !(PadCalibrationRecord.CHARACTERIZED.equals(rec.outcome) && rec.allChecksPass())) {
                throw new IllegalArgumentException("characterized needs every check of the calibration passed");
            }
        }
        if (PadCalibrationRecord.CHARACTERIZED.equals(rec.outcome)
                && (version == null || version.status != PadTypeStatus.CHARACTERIZED)) {
            throw new IllegalArgumentException("a characterized calibration writes the characterized version");
        }
        if (version != null && version.status == PadTypeStatus.CHARACTERIZED) {
            requireEvidence(c, version);
        }
        if (rec.setupKey == null) {
            // a record that names no setup is the mounted one's (the tests' and old callers' records)
            rec.setupKey = c.tip.key;
            rec.needleSinceMs = c.needleChangedAtMs;
        }
        if (rec.method == null) {
            rec.method = PadCalibrationRecord.METHOD_AUTO;
        }
        PadType stored = null;
        if (version != null) {
            stored = savePadType(version, c.tip.key, null);
            rec.versionTo = stored.version;
        }
        PadCalibrationRecord out = db.insertPadCalibration(rec);
        try {
            PadType type = stored != null ? stored : db.currentPadType(rec.typeKey, rec.setupKey);
            labelAndLink(out, type, null, rec.operator);
            if (rec.steps != null) {
                for (Photo ph : db.photos(rec.setupKey, null, rec.jobId, Photo.KIND_RESULT, false, 50)) {
                    if (rec.steps.equals(ph.doseSteps)) {
                        linkQuietly(ph.id, out.id, Photo.ROLE_RESULT, rec.operator);
                    }
                }
            }
        }
        catch (SQLException | RuntimeException e) {
            Logger.warn(e, "dispense: the label or the photos of calibration {} were not written", out.id);
        }
        db.event(null, PadCalibrationRecord.CHARACTERIZED.equals(rec.outcome) ? "INFO" : "WARN", "PCAL",
                new Gson().toJson(out));
        return out;
    }

    /** " at 14:07", or "" for an unknown time. */
    private static String atWords(Long ms) {
        return ms == null ? "" : " at " + ZCheck.clock(ms);
    }

    /**
     * §22.2: characterized without the bench model needs fresh optical and mass evidence of the version's dose, the
     * profile's dose included (409 {@code status-evidence}, the text of the pad-type editor's rule).
     */
    private void requireEvidence(DispenseConfig c, PadType v) throws SQLException {
        Tip tip = mountedTip();
        if (tip.benchModel) {
            return;
        }
        int steps = v.dotSteps != null ? v.dotSteps : c.profile.netStepsPerDot;
        long now = System.currentTimeMillis();
        org.openpnp.instancelabs.padprofile.DoseEvidence e = DoseRecords.evidence(db.doseRecords(null, now
                - (long) (c.padCalibration.doseRecordMaxAgeH * 3600e3)), c, new DoseRecord.Epochs(
                        getControllerConnectedAtMs(), isSimulatorConfirmed()), now, db.flaggedJobs()).get(steps);
        boolean optical = e != null && e.opticalFresh && e.opticalPass;
        boolean mass = e != null && e.massConfirmed;
        if (!optical || !mass) {
            String turn = DoseModel.turnName(steps);
            throw new EngineRefusal(409, "status-evidence", (v.name == null ? v.typeKey : v.name) + " uses " + turn
                    + " dots, which have " + (!optical ? "no fresh camera check on the blank" : "")
                    + (!optical && !mass ? " and " : "") + (!mass ? "no confirmed weighing at this recipe" : "")
                    + ": run the ladder and Weigh dots (Calibrate) first.");
        }
    }

    /**
     * A record's "calibrated for" label (§22.5: the given text, else the automatic one) and its link to the setup's
     * main picture of this moment (role reference).
     */
    private void labelAndLink(PadCalibrationRecord rec, PadType type, String label, String operator)
            throws SQLException {
        String auto = autoLabel(rec, type);
        String text = label == null ? auto : cleanLabel(label);
        db.insertCalLabel(rec.id, text, label == null || text.equals(auto), operator);
        Photo main = db.mainPhoto(rec.setupKey);
        if (main != null) {
            linkQuietly(main.id, rec.id, Photo.ROLE_REFERENCE, operator);
        }
    }

    /**
     * The automatic "calibrated for" label of a record (§22.5): the type as Profile names it, the record's setup and
     * dispenser, the profile's pressure and the loaded paste.
     */
    public String autoLabel(PadCalibrationRecord rec, PadType type) throws SQLException {
        DispenseConfig c = cfg;
        TipSetup s = db.currentTipSetup(rec.setupKey == null ? c.tip.key : rec.setupKey);
        String setupName = s == null ? c.tip.name : s.name;
        String dispenser = s == null ? c.tip.dispenser : s.dispenser;
        // the recipe of the record's setup: a record of a job a mount stopped names the setup that came off, whose
        // recipe is parked now (not the incoming setup's)
        DispenseConfig.ParkedTip parked = rec.setupKey == null || rec.setupKey.equals(c.tip.key) ? null
                : c.parkedTips.get(rec.setupKey);
        double psi = parked != null && parked.profile != null ? parked.profile.pressurePsi : c.profile.pressurePsi;
        return Texts.calLabel(type == null ? null : type.family, type == null ? rec.typeKey : type.name, setupName,
                dispenser, psi, c.paste.name, c.paste.lot);
    }

    /** A label as typed: control characters removed, spaces joined, 1-160 characters (else 400). */
    public static String cleanLabel(String label) {
        String t = label == null ? "" : label.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.length() > Texts.MAX_LABEL) {
            throw new EngineRefusal(400, "label", "The label must be 1 to " + Texts.MAX_LABEL + " characters.");
        }
        return t;
    }

    /** A link written when the photo and the record agree; nothing (a log line) when they do not. */
    private void linkQuietly(long photoId, long calibrationId, String role, String operator) {
        try {
            Photo p = db.photo(photoId);
            PhotoStore.checkLink(db, p.setupKey, p.source, p.needleSinceMs, calibrationId);
            db.insertPhotoLink(photoId, calibrationId, role, operator);
        }
        catch (SQLException | RuntimeException e) {
            Logger.info("dispense: picture {} not linked to calibration {}: {}", photoId, calibrationId, e.getMessage());
        }
    }

    /** The picture store (§22.4): files, removal, restore, the main picture and the start sweep. */
    public PhotoStore getPhotoStore() {
        return photoStore;
    }

    /**
     * Stores a picture (an upload, the tip picture task's frame) with its row and links (§22.4): a link to another
     * setup's record (409 {@code setup-mismatch}) and the store's limits refuse as {@link EngineRefusal}. Uploads are
     * checked for an idle engine by the API before (the tip picture task stores while it runs).
     */
    public Photo storePhoto(PhotoStore.Input in) throws IOException, SQLException {
        if (in.setupKey == null) {
            in.setupKey = cfg.tip.key;
        }
        Photo p = photoStore.store(in);
        db.event(null, "INFO", TIP_EVENT, "picture " + p.id + " (" + p.kind + ", " + p.source + ") of the "
                + nameOf(p.setupKey));
        return p;
    }

    private String nameOf(String setupKey) {
        try {
            TipSetup s = db.currentTipSetup(setupKey);
            return s == null ? setupKey : s.name;
        }
        catch (SQLException e) {
            return setupKey;
        }
    }

    /**
     * Stores a dose-blocks step's result photos when the step ends (§22.4): kind result, source top-camera, the job,
     * dose and coupon place, captioned "Top camera {HH:MM}: {n} {turn} test dots on the blank" (or "on {coupon}"),
     * of the setup and needle the step ran with (a mount since does not move them to the new setup).
     */
    public List<Photo> storeResultPhotos(String jobId, DoseBlocksResult result) throws IOException, SQLException {
        List<Photo> l = new ArrayList<>();
        DispenseConfig c = cfg;
        boolean ran = result.setupKey != null;
        for (DoseBlocksResult.BlockPhoto bp : result.photos) {
            if (bp.jpeg == null) {
                continue;
            }
            int n = bp.block >= 0 && bp.block < result.blocks.size() ? result.blocks.get(bp.block).dots.size() : 0;
            PhotoStore.Input in = new PhotoStore.Input();
            in.bytes = bp.jpeg;
            in.kind = Photo.KIND_RESULT;
            in.source = Photo.SOURCE_TOP_CAMERA;
            in.setupKey = ran ? result.setupKey : c.tip.key;
            in.needleSinceMs = ran ? result.needleSinceMs : c.needleChangedAtMs;
            in.jobId = jobId;
            in.doseSteps = bp.doseSteps;
            in.couponPlace = bp.couponPlace;
            in.mmPerPx = bp.mmPerPx;
            in.xMm = bp.xMm;
            in.yMm = bp.yMm;
            in.caption = "Top camera " + ZCheck.clock(System.currentTimeMillis()) + ": " + n + " "
                    + DoseModel.turnName(bp.doseSteps) + " test " + (n == 1 ? "dot" : "dots") + " on "
                    + (bp.couponPlace == null ? "the blank" : bp.couponPlace);
            in.simulated = isSimulatorConfirmed();
            l.add(photoStore.store(in));
            bp.jpeg = null; // §22.4: the bytes leave with the step
        }
        return l;
    }

    /**
     * Links photos to calibration records with a role (result | tip | reference, §22.4): only a photo of the record's
     * setup (409 {@code setup-mismatch}), a bottom-camera picture only to records of its needle (409
     * {@code needle-changed}). Returns the links written.
     */
    public List<Photo.Link> linkPhotos(List<Long> photoIds, List<Long> calibrationIds, String role)
            throws SQLException {
        String r = role == null ? Photo.ROLE_TIP : role;
        if (!Photo.ROLE_RESULT.equals(r) && !Photo.ROLE_TIP.equals(r) && !Photo.ROLE_REFERENCE.equals(r)) {
            throw new IllegalArgumentException("role must be result, tip or reference");
        }
        List<Photo> photos = new ArrayList<>();
        for (Long id : photoIds) {
            Photo p = db.photo(id);
            if (p == null) {
                throw new EngineRefusal(404, "not-found", "There is no picture " + id + ".");
            }
            for (Long cal : calibrationIds) {
                PhotoStore.checkLink(db, p.setupKey, p.source, p.needleSinceMs, cal);
            }
            photos.add(p);
        }
        for (Long cal : calibrationIds) {
            // the cap of tip pictures per record holds for links as for uploads (§22.4)
            int adding = 0;
            for (Photo p : photos) {
                adding += Photo.KIND_TIP.equals(p.kind) && !p.links.contains(cal) ? 1 : 0;
            }
            PhotoStore.checkTipCount(db, cal, adding);
        }
        List<Photo.Link> links = new ArrayList<>();
        for (Photo p : photos) {
            for (Long cal : calibrationIds) {
                links.add(db.insertPhotoLink(p.id, cal, r, p.operator));
            }
        }
        return links;
    }

    /** Sets (true, now) or clears the engine's new-seat flag (§3.6); returns it. */
    public synchronized Long setNewSeat(boolean newSeat) throws IOException {
        DispenseConfig c = cfg.copy();
        Long was = c.newSeatAtMs;
        c.newSeatAtMs = newSeat ? Long.valueOf(System.currentTimeMillis()) : null;
        if (was == null && c.newSeatAtMs == null) {
            return null;
        }
        updateConfig(c);
        db.event(null, "INFO", "REG", newSeat ? "new seat: the next registration probes again" : "new-seat flag cleared");
        return c.newSeatAtMs;
    }

    private synchronized void storeRegistration(BoardRegistration reg, String runId) throws IOException {
        DispenseConfig c = cfg.copy();
        c.putBoardRegistration(reg);
        updateConfig(c);
        db.event(runId, "INFO", "REG", new Gson().toJson(reg));
    }

    static final String NO_CAMERA = "REG: no top camera in OpenPnP: Register board needs it. Check the board X/Y in the "
            + "Board step and probe by hand.";

    /** The §6.2 text when the board has fewer fiducials than Register board needs, else null. */
    public static String fewFiducials(BoardFeatures f, int min) {
        if (f.fiducials.size() >= min) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (BoardFeatures.Fiducial x : f.fiducials) {
            names.add(x.name);
        }
        int n = f.fiducials.size();
        return "REG: the board has " + n + (n == 1 ? " fiducial" : " fiducials") + (n == 0 ? "" : " ("
                + String.join(", ", names) + ")") + "; Register board needs at least " + min + ". Check the board X/Y "
                + "in the Board step (Use camera X/Y on the board origin) and probe by hand (Do it by hand).";
    }

    private static RegistrationResult.FidResult fidResult(RegistrationResult res, String name) {
        for (RegistrationResult.FidResult f : res.fiducials) {
            if (f.name.equals(name)) {
                return f;
            }
        }
        throw new IllegalStateException("no fiducial " + name);
    }

    private static List<Map<String, Object>> fiducialDetail(List<RegistrationResult.FidResult> l) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RegistrationResult.FidResult f : l) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name);
            m.put("expected", xy(f.expectedX, f.expectedY));
            m.put("found", f.x == null ? null : xy(f.x, f.y));
            m.put("state", f.state);
            m.put("iterations", f.iterations);
            m.put("frames", f.frames);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> xy(double x, double y) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        return m;
    }

    private static RegistrationResult.FitResult fitResult(FiducialLocator.Fit fit, FiducialLocator.Settings ls,
            BoardRegistration prev) {
        RegistrationResult.FitResult f = new RegistrationResult.FitResult();
        f.method = fit.method;
        f.affine = new double[6];
        fit.boardToMachine.getMatrix(f.affine);
        f.rotationDeg = fit.rotationDeg;
        f.scaleX = fit.scaleX;
        f.scaleY = fit.scaleY;
        f.shearDeg = fit.shearDeg;
        f.maxResidualMm = fit.maxResidualMm;
        f.limits = new RegistrationResult.FitResult.Limits();
        f.limits.scalePct = ls.maxScaleErrorPct;
        f.limits.shearDeg = ls.maxShearDeg;
        f.limits.residualMm = ls.maxResidualMm;
        f.limits.scaleChangePct = ls.maxScaleChangePct;
        f.limits.shearChangeDeg = ls.maxShearChangeDeg;
        if (prev != null) {
            f.previous = new RegistrationResult.FitResult.Previous();
            f.previous.atMs = prev.atMs;
            FiducialLocator.Fit pf = FiducialLocator.Fit.of(prev.transform(), prev.method, prev.atMs);
            f.previous.scaleX = pf.scaleX;
            f.previous.scaleY = pf.scaleY;
            f.previous.shearDeg = pf.shearDeg;
        }
        return f;
    }

    private static List<DispenseConfig.SurfacePoint> surfacePoints(List<VacuumProber.Point> pts) {
        List<DispenseConfig.SurfacePoint> l = new ArrayList<>();
        for (VacuumProber.Point p : pts) {
            l.add(new DispenseConfig.SurfacePoint(p.x, p.y, p.z));
        }
        return l;
    }

    private static SurfacePlane fitProbed(List<DispenseConfig.SurfacePoint> sp) throws RunRefusedException {
        try {
            return SurfacePlane.fit(sp);
        }
        catch (IllegalArgumentException e) {
            throw refused("PROBE: the probed points do not give a plane (" + e.getMessage() + "): probe by hand");
        }
    }

    /** The flatness refusal (§6.2) when a point lies off the plane by more than the residual limit, else null. */
    private static String flatness(SurfacePlane plane, List<VacuumProber.Point> pts, DispenseConfig c, boolean board) {
        if (plane.maxAbsResidualMm <= c.surfaceMaxResidualMm + 1e-9) {
            return null;
        }
        int worst = 0;
        for (int i = 1; i < plane.residuals.length; i++) {
            worst = Math.abs(plane.residuals[i]) > Math.abs(plane.residuals[worst]) ? i : worst;
        }
        VacuumProber.Point p = pts.get(worst);
        String where = p.bx != null ? String.format(Locale.ROOT, "board X %.1f, Y %.1f", p.bx, p.by)
                : String.format(Locale.ROOT, "X %.2f, Y %.2f", p.x, p.y);
        return String.format(Locale.ROOT, "PROBE: the %s is not flat enough for one plane: point %d (%s) is %.3f mm off "
                + "(limit %.3f). Press the %s flat (clamp it) and %s; by hand needs the same flatness.", board ? "board"
                        : "surface", p.n, where, Math.abs(plane.residuals[worst]), c.surfaceMaxResidualMm,
                board ? "board" : "surface", board ? "register again" : "probe again");
    }

    /** The frame-band refusal: a contact further than the tolerance from the surface's reference, else null. */
    private static String band(List<VacuumProber.Point> pts, double refZ, String refName, double tol) {
        for (VacuumProber.Point p : pts) {
            if (Math.abs(p.z - refZ) > tol + 1e-9) {
                return String.format(Locale.ROOT, "I4: N1 touched at Z %.3f at point %d, %.3f mm %s %s %.3f (tolerance "
                        + "%.2f): %s or the surface is off", p.z, p.n, Math.abs(p.z - refZ), p.z < refZ ? "below" : "above",
                        refName, refZ, tol, refName);
            }
        }
        return null;
    }

    /** The result card's first sentence of a registration that ended OK. */
    private static String registrationSentence(RegistrationResult res) {
        StringBuilder sb = new StringBuilder("Registered " + ZCheck.clock(System.currentTimeMillis()) + ": ");
        ProbeReport p = res.probe;
        if (res.probeOnly) {
            sb.append(p.why);
            if (p.saved) {
                sb.append(String.format(Locale.ROOT, "; board probed at %d points", p.points.size()));
            }
            return sb.append('.').toString();
        }
        sb.append("first".equals(res.seat) ? "first seat" : "same".equals(res.seat) ? "same seat" : "re-seated");
        if (res.movedMm != null) {
            sb.append(String.format(Locale.ROOT, " (moved %.2f mm)", res.movedMm));
        }
        if (p != null && p.saved) {
            sb.append(String.format(Locale.ROOT, ", board probed at %d points", p.points.size()));
        }
        else if (res.sameSeat != null && res.sameSeat.pass) {
            sb.append(", surface kept (3 points agree)");
        }
        else if (p != null && p.skipped) {
            sb.append(", not probed");
        }
        return sb.append('.').toString();
    }

    private static String shortRun(String runId) {
        return runId == null ? "?" : runId.length() > 4 ? runId.substring(0, 4) + "…" : runId;
    }

    /**
     * Where the top camera is now (machine mm), or null when the frames do not come from OpenPnP's top
     * camera (a test imager): the fiducial search then starts at the first fiducial.
     */
    private double[] cameraXY() {
        if (dotImagerOverride != null) {
            return null;
        }
        try {
            Camera cam = configuration.getMachine().getDefaultHead().getDefaultCamera();
            Location l = cam.getLocation().convertToUnits(LengthUnit.Millimeters);
            return new double[] {l.getX(), l.getY()};
        }
        catch (Exception e) {
            return null;
        }
    }

    /** The home part of a task's live detail. */
    private static Map<String, Object> homeDetail(HomeTracker.Plan hp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("needed", hp.needed);
        m.put("whyCode", hp.whyCode);
        m.put("why", hp.why);
        m.put("estimateS", hp.estimateS);
        return m;
    }

    private void detail(RunResult r, Map<String, Object> d) {
        Map<String, Object> copy = Collections.unmodifiableMap(new LinkedHashMap<>(d));
        for (DispenseListener l : listeners) {
            try {
                l.onDetail(r, copy);
            }
            catch (Exception e) {
                Logger.warn(e, "dispense listener failed");
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------

    private void requireAvailable() {
        if (nozzle == null) {
            throw new IllegalStateException("dispense service unavailable: " + unavailableReason);
        }
    }

    private DispenseConfig.NamedLocation requireLocation(String name) {
        DispenseConfig.NamedLocation l = cfg.location(name);
        if (l == null) {
            throw new IllegalStateException("location '" + name + "' is not set");
        }
        return l;
    }

    private DotImager imager() {
        DotImager o = dotImagerOverride;
        if (o != null) {
            return o;
        }
        try {
            Machine m = configuration.getMachine();
            Camera cam = m == null || m.getDefaultHead() == null ? null : m.getDefaultHead().getDefaultCamera();
            return cam == null ? null : new OpenPnpDotImager(cam, null);
        }
        catch (Exception e) {
            return null;
        }
    }

    private RunResult newRun(String kind, String planId, String board, long calibrationId, int dots) {
        RunResult r = new RunResult();
        r.runId = java.util.UUID.randomUUID().toString();
        r.kind = kind;
        r.planId = planId;
        r.boardName = board;
        r.calibrationId = calibrationId;
        r.dotsPlanned = dots;
        r.setupKey = cfg.tip.key;
        return r;
    }

    private Future<RunResult> submit(RunResult r, Callable<RunResult> body) throws Exception {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("a dispense task is already running");
        }
        tasksStarted.incrementAndGet();
        long epoch = stopEpoch.get();
        Queued q = new Queued(r);
        queued = q;
        try {
            Future<RunResult> f = configuration.getMachine().submit(() -> {
                if (!q.began.compareAndSet(false, true)) {
                    return r; // ended as dropped meanwhile (releaseDroppedTask)
                }
                try {
                    refuseIfStoppedSince(epoch);
                    refuseUnlessStarted();
                    refuseWhilePressureHeld();
                    return body.call();
                }
                catch (Exception e) {
                    classifyFailure(r, e);
                    Logger.error(e, "dispense {} failed", r.kind);
                    return r;
                }
                finally {
                    finish(r);
                }
            }, null, true);
            q.future = f;
            return f;
        }
        catch (Exception e) {
            busy.set(false);
            throw e;
        }
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    private <T> Future<T> submitResult(RunResult r, T result, Body body) throws Exception {
        return submitResult(r, result, body, null);
    }

    /**
     * As above; {@code after} fills the result from the run once it has ended, also when the task was refused
     * before its body (a STOP since it was queued, the machine not started).
     */
    private <T> Future<T> submitResult(RunResult r, T result, Body body, Runnable after) throws Exception {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("a dispense task is already running");
        }
        tasksStarted.incrementAndGet();
        long epoch = stopEpoch.get();
        Queued q = new Queued(r);
        queued = q;
        try {
            Future<T> f = configuration.getMachine().submit(() -> {
                if (!q.began.compareAndSet(false, true)) {
                    return result; // ended as dropped meanwhile (releaseDroppedTask)
                }
                try {
                    refuseIfStoppedSince(epoch);
                    refuseUnlessStarted();
                    refuseWhilePressureHeld();
                    body.run();
                }
                catch (Exception e) {
                    classifyFailure(r, e);
                    Logger.error(e, "dispense {} failed", r.kind);
                }
                finally {
                    if (after != null) {
                        try {
                            after.run();
                        }
                        catch (RuntimeException e) {
                            Logger.warn(e, "dispense {}: the result was not completed", r.kind);
                        }
                    }
                    finish(r);
                }
                return result;
            }, null, true);
            q.future = f;
            return f;
        }
        catch (Exception e) {
            busy.set(false);
            throw e;
        }
    }

    /**
     * The status of a task that ended by an exception. A STOP is told by the executor's abort flag,
     * not by the exception: a STOP during a home, a camera move or a position read surfaces as that
     * call's exception, and must still end ABORTED (the UI opens Recovery) with the STOP's reason.
     */
    private void classifyFailure(RunResult r, Exception e) {
        if (r.status != RunResult.Status.RUNNING) {
            return;
        }
        DispenseExecutor x = activeExecutor;
        boolean stop = e instanceof DispenseExecutor.AbortedException || (x != null && x.isAbortRequested());
        r.status = stop ? RunResult.Status.ABORTED
                : e instanceof RunRefusedException ? RunResult.Status.REFUSED : RunResult.Status.ERROR;
        r.message = stop && !(e instanceof DispenseExecutor.AbortedException)
                ? abortReason(x, String.valueOf(e.getMessage())) : String.valueOf(e.getMessage());
    }

    /**
     * Engine tasks are submitted with ignoreEnabled, so a task queued while the machine is stopped
     * still ends (and clears the busy flag): it is refused here, before anything is sent.
     */
    private void refuseUnlessStarted() throws RunRefusedException {
        Machine m = configuration.getMachine();
        if (m == null || !m.isEnabled()) {
            throw refused("MACHINE: the machine is not started: press Start first");
        }
    }

    /** A STOP since the task was queued: nothing is sent (§7.8). */
    private void refuseIfStoppedSince(long epoch) throws RunRefusedException {
        if (stopEpoch.get() != epoch) {
            throw refused(STOPPED_SINCE_QUEUED);
        }
    }

    /**
     * On the machine thread, where the pressure hold's start is serialised with the engine's tasks: every
     * task refuses at its start while the hold is on (prime, the test dot and the clean-pad check as well as
     * runs, the probes and the calibrations), not only at the API's check before it was queued.
     */
    private void refuseWhilePressureHeld() throws RunRefusedException {
        if (pressureHoldActive.getAsBoolean()) {
            throw refused("the pressure hold is on: Pressure off first");
        }
    }

    private static RunRefusedException refused(String why) {
        return new RunRefusedException(why, null);
    }

    private void finish(RunResult r) {
        if (r.status == RunResult.Status.RUNNING) {
            r.status = RunResult.Status.ERROR;
            r.message = r.message == null ? "run ended without a status" : r.message;
        }
        r.endedAtMs = System.currentTimeMillis();
        try {
            db.insertRun(r);
        }
        catch (Exception e) {
            Logger.error(e, "could not store dispense run {}", r.runId);
        }
        db.event(r.runId, r.status == RunResult.Status.OK ? "INFO" : "WARN", "RUN_END", r.toString());
        activeExecutor = null;
        activeRun = null;
        lastRun = r;
        busy.set(false);
        for (DispenseListener l : listeners) {
            try {
                l.onFinished(r);
            }
            catch (Exception e) {
                Logger.warn(e, "dispense listener failed");
            }
        }
        Logger.info("dispense: {}", r);
    }

    private void phase(RunResult r, String phase) {
        db.event(r.runId, "INFO", "PHASE", phase);
        for (DispenseListener l : listeners) {
            try {
                l.onPhase(r, phase);
            }
            catch (Exception e) {
                Logger.warn(e, "dispense listener failed");
            }
        }
    }

    /** Fan-out listener passed to the executor. */
    private final DispenseListener fanOut = new DispenseListener() {
        @Override
        public void onDot(RunResult run, RunResult.DotTiming t, int done, int total) {
            for (DispenseListener l : listeners) {
                l.onDot(run, t, done, total);
            }
        }
    };

    /** What one before/after pair of a calibration dot gives (the dot nearest the centre, the dot at its spot). */
    private interface FrameMeasure<T> {
        T of(DotImager im, int i, DotImager.Frame before, DotImager.Frame after) throws Exception;
    }

    /** One task on the machine thread: OpenPnP motion settled, mapper, channel, executor. */
    private final class Session {
        final DispenseConfig c;
        final RunResult r;
        final OpenPnpRawMapper mapper;
        final DriverGcodeChannel channel;
        final DispenseExecutor exec;
        final boolean keepalive;
        boolean streamed;

        Session(DispenseConfig c, RunResult r) throws Exception {
            this.c = c;
            this.r = r;
            r.startedAtMs = System.currentTimeMillis();
            activeRun = r;
            ReferenceMachine m = (ReferenceMachine) configuration.getMachine();
            phase(r, "waiting for OpenPnP motion to complete");
            // Everything OpenPnP queued must be done before the raw stream starts.
            m.getMotionPlanner().waitForCompletion(null, CompletionType.WaitForStillstand);
            mapper = new OpenPnpRawMapper(m, nozzle, c.bAxisName);
            channel = new DriverGcodeChannel(mapper.getDriver());
            exec = new DispenseExecutor(c, channel, mapper.letterB(), System::currentTimeMillis);
            activeExecutor = exec;
            boolean watchdog = probeKeepalive(); // also notes the simulator identity (M115) of this connection
            keepalive = c.keepaliveEnabled && watchdog;
            r.keepaliveActive = keepalive;
        }

        /**
         * M115: does the firmware have the host watchdog? Only a timeout means "no capability". Any
         * other failure, in particular a controller error that arrived before the run (a 'start'
         * after a reset, an Error: line), refuses the run: B must be re-zeroed and the position
         * frame may be gone, so B is marked un-zeroed and the machine un-homed.
         */
        private boolean probeKeepalive() throws RunRefusedException {
            try {
                List<String> reply = channel.sendAndCollect(ControllerCommands.CAPABILITY_QUERY, c.commandTimeoutMs);
                noteIdentity(reply);
                for (String line : reply) {
                    if (line.contains(ControllerCommands.WATCHDOG_CAPABILITY)) {
                        return true;
                    }
                }
                return false;
            }
            catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("timeout waiting for response")) {
                    r.warnings.add("capability query timed out (no host watchdog assumed): " + msg);
                    return false;
                }
                bZeroed.set(false);
                Machine m = configuration.getMachine();
                if (m instanceof ReferenceMachine) {
                    ((ReferenceMachine) m).setHomed(false);
                }
                throw new RunRefusedException("the controller reported an error before the run (" + msg
                        + "). Nothing was sent; B will be re-zeroed and the machine must be homed again.", e);
            }
        }

        /** Interlocks for calibration/shots runs (no plan rules, no I9-I11; I4 per the scope). */
        boolean preflight(Interlocks.Scope scope, double estimatedPressurisedSeconds) {
            r.interlocks = Interlocks.check(interlockInputs(c.copy(), null, estimatedPressurisedSeconds, scope, false,
                    false));
            if (!r.interlocks.ok()) {
                refuse(r.interlocks.toString());
                return false;
            }
            return true;
        }

        /**
         * Settle OpenPnP motion, build (from the now-current axis state), validate, stream, re-sync
         * OpenPnP. On failure the I1 shutdown has already been sent; recover state.
         */
        GcodeProgram runSegment(Callable<GcodeProgram> build, String phaseName) throws Exception {
            return runSegment(build, phaseName, null);
        }

        /** As above; {@code beforeStream} runs after validation, right before the first line is sent. */
        GcodeProgram runSegment(Callable<GcodeProgram> build, String phaseName, Runnable beforeStream)
                throws Exception {
            // OpenPnP may have moved in between (camera for I10/calibration images): settle first.
            configuration.getMachine().getMotionPlanner().waitForCompletion(null, CompletionType.WaitForStillstand);
            GcodeProgram p = build.call();
            List<String> problems = GcodeStreamValidator.validate(p, c, mapper.letterB(),
                    GcodeStreamValidator.zWindow(c, mapper, p.floorReferenceZMm));
            if (!problems.isEmpty()) {
                throw new IllegalStateException("generated G-code failed validation: " + problems);
            }
            if (exec.isAbortRequested()) {
                throw new DispenseExecutor.AbortedException("aborted before " + phaseName);
            }
            if (beforeStream != null) {
                beforeStream.run();
            }
            phase(r, phaseName);
            streamed = true;
            try {
                exec.stream(p, r, fanOut);
            }
            catch (Exception e) {
                recover();
                r.status = exec.isAbortRequested() || e instanceof DispenseExecutor.AbortedException
                        ? RunResult.Status.ABORTED : RunResult.Status.ERROR;
                r.message = String.valueOf(e.getMessage());
                db.event(r.runId, "ERROR", "I1", "stream stopped, pneumatics off sent: " + r.message);
                throw e;
            }
            mapper.resync(p.finalPlanned, p.finalDriver, p.finalB);
            bZeroed.set(true);
            return p;
        }

        /**
         * The Marlin step count of B (M400, M114 Count): the screw angle reference, which restarts at
         * controller power-up. OpenPnP's simulator does not report it: on a loopback TCP link the
         * count is taken as 0 (with a warning); on a real link a missing count returns null.
         */
        Long readBCount() throws Exception {
            return readBCount(true);
        }

        /**
         * As above; without {@code assumeOnSimulator} a missing count is null on any link (the pad
         * calibration measures per-phase statistics: a count taken as 0 would mislabel every phase).
         */
        Long readBCount(boolean assumeOnSimulator) throws Exception {
            channel.send("M400", -1);
            Long n = parseBCount(channel.sendAndCollect("M114", c.commandTimeoutMs), mapper.letterB());
            if (n == null && assumeOnSimulator && channel.isLoopbackSimulator()) {
                r.warnings.add("simulated controller (no M114 Count): B step count taken as 0 for the start angle");
                return 0L;
            }
            return n;
        }

        /** Before/after images of every spec around one pressure session; returns the dot per spec. */
        List<Dot> imageDots(List<GcodeStreamBuilder.DotSpec> specs, boolean primeAtWaste, double primeExtra,
                double radius) throws Exception {
            return imageEach(specs, primeAtWaste, primeExtra, (im, i, before, after) -> TestDotCheck.nearest(
                    im.inspect(before, after), radius));
        }

        /**
         * As {@link #imageDots}, each dot found at its spot with the strings cut off ({@link DotImager#inspectSpots}):
         * the frame of dot i is centred on it, the other dots are its neighbours there.
         */
        List<SpotDot> imageSpots(List<GcodeStreamBuilder.DotSpec> specs, boolean primeAtWaste, double primeExtra,
                double cutMm, double radiusMm) throws Exception {
            return imageEach(specs, primeAtWaste, primeExtra, (im, i, before, after) -> {
                List<double[]> spots = new ArrayList<>();
                for (GcodeStreamBuilder.DotSpec d : specs) {
                    spots.add(new double[] {d.x - specs.get(i).x, d.y - specs.get(i).y});
                }
                return im.inspectSpots(before, after, spots, cutMm, radiusMm).get(i);
            });
        }

        /** Before images of every spec, one pressure session, then each spec's after image and its measure. */
        private <T> List<T> imageEach(List<GcodeStreamBuilder.DotSpec> specs, boolean primeAtWaste, double primeExtra,
                FrameMeasure<T> measure) throws Exception {
            DotImager im = imager();
            if (im == null || !im.isAvailable()) {
                throw new IllegalStateException("no top camera / dot vision available");
            }
            phase(r, "before images");
            List<DotImager.Frame> before = new ArrayList<>();
            for (GcodeStreamBuilder.DotSpec d : specs) {
                before.add(im.capture(d.x, d.y));
            }
            runSegment(() -> GcodeStreamBuilder.forDots(c, mapper, specs, bZeroed.get(), keepalive, primeAtWaste,
                    primeExtra, "calibration dots", tipXy(c)), "dispensing calibration dots");
            phase(r, "after images");
            List<T> found = new ArrayList<>();
            for (int i = 0; i < specs.size(); i++) {
                DotImager.Frame after = im.capture(specs.get(i).x, specs.get(i).y);
                found.add(measure.of(im, i, before.get(i), after));
            }
            return found;
        }

        /** After an aborted/failed stream: read back the controller position, un-home, re-zero B later. */
        void recover() {
            bZeroed.set(false);
            try {
                channel.send("M400", -1);
                // A stop between the screw's M204 S and the restore leaves the screw acceleration in force.
                channel.send("M204 S" + GcodeStreamBuilder.f0(c.travelAccelMmS2), c.commandTimeoutMs);
                AxesLocation rep = mapper.getDriver().getReportedLocation(c.commandTimeoutMs);
                ReferenceControllerAxis[] xyz = mapper.xyzAxes();
                double[] pos = new double[3];
                for (int i = 0; i < 3; i++) {
                    pos[i] = rep.getCoordinate(xyz[i], LengthUnit.Millimeters);
                }
                mapper.resync(pos, pos, rep.getCoordinate(mapper.bAxis()));
            }
            catch (Exception e) {
                r.warnings.add("position read-back after stop failed: " + e.getMessage());
                Logger.warn(e, "dispense: position read-back after stop failed");
            }
            // M410 may lose steps; force a re-home before further motion.
            Machine m = configuration.getMachine();
            if (m instanceof ReferenceMachine) {
                ((ReferenceMachine) m).setHomed(false);
            }
        }

        RunResult refuse(String why) {
            r.status = RunResult.Status.REFUSED;
            r.message = why;
            return r;
        }

        RunResult fail(String why) {
            r.status = RunResult.Status.ERROR;
            r.message = why;
            return r;
        }

        RunResult ok() {
            if (r.status == RunResult.Status.RUNNING) {
                r.status = RunResult.Status.OK;
            }
            return r;
        }
    }
}
