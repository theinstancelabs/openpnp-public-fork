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
import org.openpnp.instancelabs.sim.SimGuard;
import org.openpnp.instancelabs.vision.BareSpotCheck;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.instancelabs.vision.FiducialLocator;
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

    private final Configuration configuration;
    private final File configDir;
    private final DispenseDb db;
    private volatile DispenseConfig cfg;
    private volatile Nozzle nozzle;
    private volatile String unavailableReason;
    private final AtomicBoolean bZeroed = new AtomicBoolean(false);
    private final AtomicBoolean busy = new AtomicBoolean(false);
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
     * connect or of the last task), null when not asked yet. Kept here as well as in SimGuard because
     * OpenPnP's listeners run in no fixed order: SimGuard may forget the answer of the connect after
     * it was noted.
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
        SimGuard.attach(configuration.getMachine());
        instance.askIdentityOnConnect(); // after SimGuard's listener, which forgets the last connection's answer
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
     */
    private void askIdentityOnConnect() {
        Machine machine = configuration.getMachine();
        if (machine == null) {
            return;
        }
        identityListener = new MachineListener.Adapter() {
            @Override
            public void machineEnabled(Machine m) {
                connectionSimulated = null;
                if (!(m instanceof ReferenceMachine) || nozzle == null) {
                    return;
                }
                try {
                    OpenPnpRawMapper mapper = new OpenPnpRawMapper((ReferenceMachine) m, nozzle, cfg.bAxisName);
                    noteIdentity(new DriverGcodeChannel(mapper.getDriver())
                            .sendAndCollect(ControllerCommands.CAPABILITY_QUERY, cfg.commandTimeoutMs));
                }
                catch (Exception e) {
                    Logger.debug("controller identity not read at connect: {}", e.getMessage());
                }
            }

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

    private void resolveNozzle() {
        nozzle = null;
        unavailableReason = null;
        Machine machine = configuration.getMachine();
        if (!(machine instanceof ReferenceMachine)) {
            unavailableReason = "machine is not a ReferenceMachine";
            return;
        }
        for (Head h : machine.getHeads()) {
            for (Nozzle n : h.getNozzles()) {
                if (cfg.nozzle.equals(n.getId())) {
                    nozzle = n;
                }
            }
        }
        if (nozzle == null) {
            for (Head h : machine.getHeads()) {
                for (Nozzle n : h.getNozzles()) {
                    if (cfg.nozzle.equals(n.getName())) {
                        nozzle = n;
                    }
                }
            }
        }
        if (nozzle == null) {
            unavailableReason = "no dispenser nozzle '" + cfg.nozzle + "' in the machine";
        }
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

    /**
     * Validates, saves to dispense-config.json and applies a new configuration. A changed start
     * angle or needle XY trim is stamped with the time it was set (calibration values).
     */
    public synchronized void updateConfig(DispenseConfig newCfg) throws IOException {
        List<String> problems = newCfg.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid dispense config: " + problems);
        }
        DispenseConfig c = newCfg.copy();
        long now = System.currentTimeMillis();
        if (cfg != null && c.profile.startAngleDeg != cfg.profile.startAngleDeg) {
            c.profile.startAngleSetAtMs = now;
        }
        if (cfg != null && (c.needleXyTrim.dxMm != cfg.needleXyTrim.dxMm || c.needleXyTrim.dyMm != cfg.needleXyTrim.dyMm)) {
            c.needleXyTrim.setAtMs = now;
        }
        c.save(configDir);
        cfg = c;
        resolveNozzle();
        db.event(null, "INFO", "CONFIG", "configuration updated");
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
        DispenseConfig c = cfg.copy();
        c.locations.put(name, new DispenseConfig.NamedLocation(x, y, z));
        updateConfig(c);
    }

    /** I4: a needle change invalidates the needle Z offset until the next touch-off. */
    public synchronized void markNeedleChanged() throws IOException {
        DispenseConfig c = cfg.copy();
        c.needleChangedAtMs = System.currentTimeMillis();
        updateConfig(c);
        db.event(null, "INFO", "I4", "needle changed; touch-off required");
    }

    /** Sets the needle Z offset (manual entry or touch-off result) and records it. */
    public synchronized void setNeedleZOffset(double offsetMm, String method, String details)
            throws IOException, SQLException {
        DispenseConfig c = cfg.copy();
        double prev = c.needleZOffsetMm;
        c.needleZOffsetMm = offsetMm;
        c.needleZOffsetSetAtMs = Math.max(System.currentTimeMillis(),
                c.needleChangedAtMs == null ? 0 : c.needleChangedAtMs);
        updateConfig(c);
        db.insertTouchOff(method, offsetMm, prev, details, null);
    }

    /**
     * I4 needle Z frame: the same surface touched by N1 (OpenPnP frame, {@code surfaceZMm}) and by the
     * needle tip ({@code needleContactZMm}, N2 Z). Sets the offset to their difference; a touch-off
     * (or a manual entry within the drift limit) is still required before dispensing.
     */
    public synchronized DispenseConfig.NeedleAnchor setNeedleAnchor(double surfaceZMm, double needleContactZMm,
            Double xMm, Double yMm) throws IOException, SQLException {
        return setNeedleAnchor(surfaceZMm, needleContactZMm, xMm, yMm, null);
    }

    /** As above; {@code rawSumMm} records the bench relation the needle contact came from (or null). */
    public synchronized DispenseConfig.NeedleAnchor setNeedleAnchor(double surfaceZMm, double needleContactZMm,
            Double xMm, Double yMm, Double rawSumMm) throws IOException, SQLException {
        DispenseConfig c = cfg.copy();
        double prev = c.needleZOffsetMm;
        long now = Math.max(System.currentTimeMillis(), c.needleChangedAtMs == null ? 0 : c.needleChangedAtMs);
        c.needleAnchor = new DispenseConfig.NeedleAnchor(surfaceZMm, needleContactZMm, xMm, yMm, now);
        c.needleAnchor.rawSumMm = rawSumMm;
        c.needleZOffsetMm = c.needleAnchor.offsetMm();
        updateConfig(c);
        db.insertTouchOff("needle-frame (N1 + needle contact)", c.needleZOffsetMm, prev, new com.google.gson.Gson()
                .toJson(c.needleAnchor), null);
        db.event(null, "INFO", "I4", String.format(java.util.Locale.ROOT,
                "needle frame: N1 contact %.3f, needle contact %.3f -> offset %.3f; touch-off required",
                surfaceZMm, needleContactZMm, c.needleZOffsetMm));
        return c.needleAnchor;
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
        m.note = note;
        m.boardFingerprint = boardFingerprint;
        m.simulated = simulated;
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

    /**
     * Thread-safe: stops the active stream before its next line; pneumatics are switched off (I1).
     * Every task queued before it refuses at its start ({@link #stopEpoch}).
     */
    public void abort(String reason) {
        stopEpoch.incrementAndGet();
        cameraTourAbort = true;
        DispenseExecutor e = activeExecutor;
        if (e != null) {
            e.requestAbort(reason);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------------------------------

    public List<PadType> getPadTypes() throws SQLException {
        return db.currentPadTypes();
    }

    /** Appends a new version of a pad type (new type if the key is new). */
    public PadType savePadType(PadType p) throws SQLException {
        PadType stored = db.insertPadTypeVersion(p);
        db.event(null, "INFO", "PAD_TYPE", "saved " + stored);
        return stored;
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
                .withSurface(planeOf(cfg, cfg.boardSurfaceName))
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
        if (a == null || Math.abs(boardZ - a.surfaceZMm) <= MAX_BOARD_Z_FROM_ANCHOR_MM + 1e-9) {
            return null;
        }
        return String.format(Locale.ROOT, "the board top Z %.3f is %.2f mm from the needle-frame surface %.3f (at most "
                + "%.1f mm: the board and the blank sit on the same bed). The board top Z is the height where N1 "
                + "touches the board; a height taken with the needle is about 2.6 mm off. Set the board Z again.",
                boardZ, Math.abs(boardZ - a.surfaceZMm), a.surfaceZMm, MAX_BOARD_Z_FROM_ANCHOR_MM);
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
        in.nozzleName = nozzle == null ? cfg.nozzle : nozzle.getName();
        in.compatibleNozzleTips = new ArrayList<>();
        if (nozzle != null) {
            for (NozzleTip t : nozzle.getCompatibleNozzleTips()) {
                in.compatibleNozzleTips.add(t.getName());
            }
            in.loadedNozzleTip = nozzle.getNozzleTip() == null ? null : nozzle.getNozzleTip().getName();
        }
        in.pressureSensor = pressureSensor;
        in.plan = plan;
        if (plan != null) {
            try {
                List<PadType> types = db.currentPadTypes();
                in.padTypesNow = new java.util.HashMap<>();
                for (PadType t : types) {
                    in.padTypesNow.put(t.typeKey, t);
                }
                in.padTypesFingerprintNow = new PadTypeResolver(types, db.packageMappings())
                        .withPastePartial(db.pastePartialPackages()).fingerprint();
            }
            catch (Exception e) {
                in.padTypesNow = null; // unknown: the version checks are skipped, the gate check still runs
                in.padTypesFingerprintNow = null;
            }
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
            refuseWhilePressureHeld();
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
            GcodeProgram estimate = GcodeStreamBuilder.forPlan(c, s.mapper, plan, purge, bZeroed.get(), s.keepalive,
                    0L);
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
                return GcodeStreamBuilder.forPlan(c, s.mapper, plan, purge, bZeroed.get(), s.keepalive, count);
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
            GcodeProgram p = GcodeStreamBuilder.forDots(c, s.mapper, specs, bZeroed.get(), s.keepalive, false,
                    "shots x" + count + " @" + locationName);
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
                    req.primeAtWaste, "dot ladder");
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
    }

    /** Needle touch-off by dot ladder at stepped gaps (plan §6.1). Applies the new offset if found and asked. */
    public Future<TouchOffResult> runNeedleTouchOff(NeedleTouchOff.Request req, boolean apply) throws Exception {
        requireAvailable();
        String blankName = req.locationName == null ? cfg.blankLocationName : req.locationName;
        DispenseConfig.NamedLocation blank = requireLocation(blankName);
        RunResult r = newRun("touch-off", null, "blank", getCurrentCalibration().id, req.steps);
        TouchOffResult res = new TouchOffResult();
        res.run = r;
        return submitResult(r, res, () -> {
            DispenseConfig c = cfg;
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
                    req.primeAtWaste, primeExtra, "needle touch-off");
            if (!s.preflight(scope, p.estimatedPressurisedSeconds)) {
                return;
            }
            List<Dot> found = s.imageDots(specs, req.primeAtWaste, primeExtra, req.pitchMm / 2);
            double[] gaps = NeedleTouchOff.gaps(req);
            Double[] d = new Double[gaps.length];
            for (int i = 0; i < d.length; i++) {
                d[i] = found.get(i) == null ? null : found.get(i).diameterMm;
            }
            res.analysis = NeedleTouchOff.analyze(gaps, d, req.jumpRatio);
            res.previousOffsetMm = c.needleZOffsetMm;
            java.util.Map<String, Object> det = new java.util.LinkedHashMap<>();
            det.put("analysis", res.analysis);
            det.put("blank", blankName);
            det.put("blankPlane", plane == null ? null : java.util.Arrays.asList(plane.a, plane.b, plane.c));
            det.put("blankPlaneMaxResidualMm", plane == null ? null : plane.maxAbsResidualMm);
            det.put("dotZ", specs.stream().map(x -> x.dispenseZ).collect(java.util.stream.Collectors.toList()));
            String details = new com.google.gson.Gson().toJson(det);
            if (res.analysis.found()) {
                res.newOffsetMm = c.needleZOffsetMm + res.analysis.contactGapMm;
                if (apply) {
                    setNeedleZOffset(res.newOffsetMm, "dot-ladder-touch-off", details);
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
     * N1's raw Z as OpenPnP knows it, and whether it lies outside z1's safe zone (a STOP during a probe
     * left it low: the next home raises it first): {z, low}, or null without N1.
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
            return new double[] {z, outsideSafeZone(n1.zSafeZoneRaw(), z) ? 1 : 0};
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
        /** The first stored point the check would touch, or null (none left: the check is refused). */
        public ZCheck.Reference reference;
        public ZCheck.Result last;
    }

    /**
     * The Z check a board run of {@code plan} (null: the needle tasks on the blank) needs. With
     * {@code homeFirst} every Z datum predates the coming home.
     */
    public ZCheckPlan zCheckPlan(DispensePlan plan, boolean homeFirst) {
        DispenseConfig c = cfg;
        ZCheckPlan p = new ZCheckPlan();
        p.last = homeTracker.getZCheck();
        p.homeAtMs = homeTracker.getLastHomeAtMs();
        Interlocks.Scope sc = plan == null ? needleTasksScope(c) : boardScope(c, plan);
        p.stale = ZCheck.stale(c, sc, homeFirst ? Long.valueOf(Long.MAX_VALUE) : p.homeAtMs);
        p.needed = !p.stale.isEmpty() && (homeFirst || !ZCheck.accepts(p.last, p.homeAtMs));
        List<ZCheck.Reference> refs = ZCheck.references(c, plan == null ? null : plan.boardRegistration,
                homeFirst ? null : p.homeAtMs);
        p.reference = refs.isEmpty() ? null : refs.get(0);
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

    /** A top camera (or a test imager) for the fiducials and the looks before N1 touches. */
    public boolean hasTopCamera() {
        DotImager im = imager();
        return im != null && im.isAvailable();
    }

    /** The pad types, package mappings and package options now (BoardFeatures, plans). */
    public PadTypeResolver padTypeResolver() throws SQLException {
        return new PadTypeResolver(db.currentPadTypes(), db.packageMappings())
                .withPastePartial(db.pastePartialPackages());
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
     * check for that home exists, one N1 touch at the first stored point whose spot is bare. Returns
     * the check, or null when none was needed. Refused when no point is left, when it does not touch,
     * and when it fails ("Z moved").
     */
    private ZCheck.Result zCheckIfNeeded(Session s, String planRegistration, Interlocks.Scope scope,
            BooleanSupplier aborted) throws Exception {
        Long home = homeTracker.getLastHomeAtMs();
        if (ZCheck.stale(s.c, scope, home).isEmpty() || ZCheck.accepts(homeTracker.getZCheck(), home)) {
            return null;
        }
        List<ZCheck.Reference> refs = ZCheck.references(s.c, planRegistration, home);
        if (refs.isEmpty()) {
            throw refused("I4: " + ZCheck.noReferenceMessage(home));
        }
        Probe pr = new Probe(s, aborted, null);
        for (ZCheck.Reference ref : refs) {
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
            ZCheck.Result res = ZCheck.result(ref, p.z, System.currentTimeMillis(), home);
            homeTracker.setZCheck(res);
            db.event(s.r.runId, res.pass ? "INFO" : "WARN", "ZCHECK", new Gson().toJson(res));
            if (!res.pass) {
                throw refused("I4: " + ZCheck.movedMessage(res));
            }
            return res;
        }
        throw refused("I4: Z must be checked after the home at " + ZCheck.clock(home) + ", but something lies on every "
                + "stored point (paste, a part or print seen): wipe one of them, or record the needle frame again");
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
            refuseWhilePressureHeld();
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
     * once stored stays (also after a refused probe or a STOP) and the board is planned with it. The
     * Future never throws for task failures; see the result.
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
        BoardFeatures f = BoardFeatures.of(board, new PadTypeResolver(db.currentPadTypes(), db.packageMappings()));
        String few = fewFiducials(f, c.fiducialCheck.minFiducials);
        if (few != null) {
            throw refused(few);
        }
        DotImager im = imager();
        if (im == null || !im.isAvailable()) {
            throw refused(NO_CAMERA);
        }
        boolean sim = isSimulatorConfirmed();
        res.simulated = sim;
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
        RunResult r = t.r;
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
        t.stage = "probe";
        t.points.clear();
        List<VacuumProber.Target> targets = ProbeTargets.map(ProbeTargets.forBoard(f, ProbeTargets.dotsBoard(req.plan,
                req.planToBoard), c), reg.transform());
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
        AffineTransform planToMachine = new AffineTransform(reg.transform());
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
        String note = String.format(Locale.ROOT, "Register board %s: %d points by N1 vacuum touch (registration %s, run %s)",
                ZCheck.clock(System.currentTimeMillis()), sp.size(), reg.shortId(), shortRun(r.runId));
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
            ZCheck.Result zc = ZCheck.result(ref, worst.z, System.currentTimeMillis(), home);
            homeTracker.setZCheck(zc);
            db.event(t.r.runId, "INFO", "ZCHECK", new Gson().toJson(zc));
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
        res.window = VacuumProber.preflight(c, !req.frameCheck, what, req.nominalZ, p.travelZ, p.settings,
                p.n1.getNozzle());
        if (req.windowMm != null) {
            narrow(res.window, req.nominalZ, req.windowMm, p.settings);
        }
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
            refuseWhilePressureHeld();
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

    /** A probe window of +-{@code mm} around the nominal Z (a weigh coupon: its own place Z, §7.2 step 2). */
    private static void narrow(VacuumProber.Window w, double nominalZ, double mm, VacuumProber.Settings s)
            throws RunRefusedException {
        w.nominalZ = nominalZ;
        w.refZ = nominalZ;
        w.startZ = nominalZ + mm;
        w.approachZ = w.startZ + s.approachAboveStartMm;
        w.floorZ = nominalZ - mm;
        if (w.floorZ < s.absoluteFloorZ - 1e-9) {
            throw refused(String.format(Locale.ROOT, "PROBE: the place Z %.3f puts the probe floor at %.3f, below the "
                    + "lowest N1 Z the probe may use (%.3f)", nominalZ, w.floorZ, s.absoluteFloorZ));
        }
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
        DispenseConfig c = cfg;
        if (stopEpoch.get() != req.stopEpoch) {
            throw refused(STOPPED_SINCE_QUEUED);
        }
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
        int need = DoseBlocks.purgeSpotsNeeded(runs, c, flush);
        boolean partial = DoseBlocks.anyPartial(runs, c);
        DispenseConfig.PurgeRow row = c.purgeRow;
        if (need > 0 && row.isDefined()) {
            List<double[]> spots = new ArrayList<>();
            for (int i = 0; i < Math.min(need, row.spotsLeft()); i++) {
                spots.add(row.spot(row.usedSpots + i));
            }
            scope.moreSurfaces.computeIfAbsent(row.surfaceName, n -> new ArrayList<>()).addAll(spots);
        }
        t.stage = "z check";
        res.zCheck = zCheckIfNeeded(s, null, scope, t::aborted);
        t.stage = "interlocks";
        r.interlocks = Interlocks.check(blockInputs(c, scope, 0, need, partial, null));
        if (!r.interlocks.ok()) {
            throw refused(r.interlocks.toString());
        }
        List<GcodeStreamBuilder.DotSpec> purge = purgeSpecs(c, need);
        GcodeProgram estimate = GcodeStreamBuilder.forBlocks(c, s.mapper, specs, purge, flush, bZeroed.get(),
                s.keepalive, 0L, null);
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
        List<PadLadder.Tile> tiles = req.image ? PadLadder.tiles(all, im.fieldOfViewMm(), PadLadder.TILE_INSET_MM)
                : new ArrayList<>();
        int[] tileOf = new int[all.size()];
        List<DotImager.Frame> before = new ArrayList<>();
        if (req.image) {
            t.stage = "before images";
            phase(r, "pad calibration: looking at " + all.size() + " spots (" + tiles.size() + " frames)");
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
                            PadLadder.spotSettings(DoseModel.dHiMm(steps)));
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
                        null);
            }, "pad calibration: dispensing", () -> {
                reservePurgeSpots(purge.size(), r.runId);
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
            phase(r, "pad calibration: measuring the dots (" + tiles.size() + " frames)");
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
                bZeroed.get(), false, 0L, null);
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
            }
            blobs.add(at);
            blobDots.add(found);
        }
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
            double model = DoseModel.dHiMm(o.netSteps) / 2;
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
     */
    public synchronized PadCalibrationRecord applyPadCalibration(PadCalibrationRecord rec, PadType version)
            throws SQLException {
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
        if (version != null) {
            PadType stored = savePadType(version);
            rec.versionTo = stored.version;
        }
        PadCalibrationRecord stored = db.insertPadCalibration(rec);
        db.event(null, PadCalibrationRecord.CHARACTERIZED.equals(rec.outcome) ? "INFO" : "WARN", "PCAL",
                new Gson().toJson(stored));
        return stored;
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
        sb.append("first".equals(res.seat) ? "first seat" : "same".equals(res.seat) ? "same seat" : "re-seated");
        if (res.movedMm != null) {
            sb.append(String.format(Locale.ROOT, " (moved %.2f mm)", res.movedMm));
        }
        ProbeReport p = res.probe;
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
        return r;
    }

    private Future<RunResult> submit(RunResult r, Callable<RunResult> body) throws Exception {
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("a dispense task is already running");
        }
        long epoch = stopEpoch.get();
        try {
            return configuration.getMachine().submit(() -> {
                try {
                    refuseIfStoppedSince(epoch);
                    refuseUnlessStarted();
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
        long epoch = stopEpoch.get();
        try {
            return configuration.getMachine().submit(() -> {
                try {
                    refuseIfStoppedSince(epoch);
                    refuseUnlessStarted();
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

    /** On the machine thread, where the pressure hold's start is serialised with the engine's tasks. */
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
                    primeExtra, "calibration dots"), "dispensing calibration dots");
            phase(r, "after images");
            List<Dot> found = new ArrayList<>();
            for (int i = 0; i < specs.size(); i++) {
                DotImager.Frame after = im.capture(specs.get(i).x, specs.get(i).y);
                found.add(TestDotCheck.nearest(im.inspect(before.get(i), after), radius));
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
