package org.openpnp.instancelabs.dispense;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openpnp.ConfigurationListener;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.NozzleTip;
import org.pmw.tinylog.Logger;

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
    /** When OpenPnP last enabled (connected to) the controller: the B step count may restart (I11). */
    private volatile Long controllerConnectedAtMs;
    /** Set by {@link #abort}: stops a camera-only clean-pad check (it has no executor). */
    private volatile boolean cameraTourAbort;

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
        if (machine != null) {
            machineListener = new MachineListener.Adapter() {
                @Override
                public void machineHomed(Machine m, boolean isHomed) {
                    bZeroed.set(false);
                }

                @Override
                public void machineEnabled(Machine m) {
                    bZeroed.set(false);
                    controllerConnectedAtMs = System.currentTimeMillis();
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
        }
        try {
            db.close();
        }
        catch (SQLException e) {
            Logger.warn(e);
        }
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
        return controllerConnectedAtMs;
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
        SurfacePlane plane = SurfacePlane.fit(points); // throws IllegalArgumentException if unusable
        DispenseConfig c = cfg.copy();
        DispenseConfig.SurfaceMap m = new DispenseConfig.SurfaceMap();
        m.points = new ArrayList<>(points);
        m.setAtMs = System.currentTimeMillis();
        m.note = note;
        m.boardFingerprint = boardFingerprint;
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
     * transform (status and matrix) and every placement (review finding: a board moved, re-checked
     * or edited after planning was not detected at Run).
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

    /** Thread-safe: stops the active stream before its next line; pneumatics are switched off (I1). */
    public void abort(String reason) {
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
        return new DispensePlanner(cfg, new PadTypeResolver(db.currentPadTypes(), db.packageMappings()))
                .withRules(db.currentPadRules()).withSurface(planeOf(cfg, cfg.boardSurfaceName));
    }

    /** Plans a board location; the path starts at the end of the purge row (or N2's current XY). */
    public DispensePlan planBoard(BoardLocation bl) throws Exception {
        requireAvailable();
        double[] start = planStart();
        DispensePlan plan = newPlanner().planBoard(bl, start[0], start[1]);
        plan.boardFingerprint = boardFingerprint(bl);
        plan.boardRegistration = boardRegistration(bl);
        db.insertPadPlan(plan);
        db.event(null, "INFO", "PLAN", plan.summary());
        return plan;
    }

    /** Loads a board file and plans it at the given board location (top side up). */
    public DispensePlan planBoardFile(File boardFile, Location boardLocation) throws Exception {
        Board board = configuration.getBoard(boardFile);
        BoardLocation bl = new BoardLocation(board);
        bl.setLocation(boardLocation);
        return planBoard(bl);
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
        List<GcodeStreamBuilder.DotSpec> l = new ArrayList<>();
        DispenseConfig.PurgeRow row = c.purgeRow;
        int n = c.profile.purgeDots;
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
        return Interlocks.check(interlockInputs(cfg, plan, estimatedPressurisedSeconds, boardScope(cfg, plan)));
    }

    /** Pre-flight interlocks for a calibration / shots / hold scope (no plan rules, no I9-I11). */
    public InterlockReport checkInterlocks(Interlocks.Scope scope, double estimatedPressurisedSeconds) {
        return Interlocks.check(interlockInputs(cfg.copy(), null, estimatedPressurisedSeconds, scope));
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
        if (c.profile.purgeDots > 0 && row.isDefined()) {
            List<double[]> t = new ArrayList<>();
            for (int i = 0; i < Math.min(c.profile.purgeDots, row.spotsLeft()); i++) {
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

    private Interlocks.Inputs interlockInputs(DispenseConfig c, DispensePlan plan, double est, Interlocks.Scope scope) {
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
                in.padTypesFingerprintNow = new PadTypeResolver(types, db.packageMappings()).fingerprint();
            }
            catch (Exception e) {
                in.padTypesNow = null; // unknown: the version checks are skipped, the gate check still runs
                in.padTypesFingerprintNow = null;
            }
        }
        in.estimatedPressurisedSeconds = est;
        in.opticalCheckAvailable = imager() != null && imager().isAvailable();
        in.scope = scope == null ? new Interlocks.Scope() : scope;
        in.controllerConnectedAtMs = controllerConnectedAtMs;
        in.boardRun = plan != null;
        in.purgeDots = plan == null ? 0 : c.profile.purgeDots;
        return in;
    }

    // ------------------------------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------------------------------

    public Future<RunResult> runBoard(BoardLocation bl) throws Exception {
        return runPlan(planBoard(bl));
    }

    /**
     * Streams a plan on the machine thread as one pressure session (bench recipe): the clean-pad
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
            // Interlocks first (I4 Z frame before any program is built), then again with the estimate (I7).
            r.interlocks = Interlocks.check(interlockInputs(c, plan, 0, boardScope(c, plan)));
            if (!r.interlocks.ok()) {
                return s.refuse(r.interlocks.toString());
            }
            List<GcodeStreamBuilder.DotSpec> purge = purgeSpecs(c);
            GcodeProgram estimate = GcodeStreamBuilder.forPlan(c, s.mapper, plan, purge, bZeroed.get(), s.keepalive,
                    0L);
            r.interlocks = Interlocks.check(interlockInputs(c, plan, estimate.estimatedPressurisedSeconds,
                    boardScope(c, plan)));
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
                if (count == null && c.profile.isPartialTurn(c)) {
                    throw new RunRefusedException("I11: the controller did not report the B step count (M114 "
                            + "Count): the start angle of a partial-turn profile cannot be set. Nothing was sent.",
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
        try {
            return configuration.getMachine().submit(() -> {
                try {
                    return body.call();
                }
                catch (Exception e) {
                    if (r.status == RunResult.Status.RUNNING) {
                        r.status = e instanceof RunRefusedException ? RunResult.Status.REFUSED
                                : e instanceof DispenseExecutor.AbortedException ? RunResult.Status.ABORTED
                                        : RunResult.Status.ERROR;
                        r.message = String.valueOf(e.getMessage());
                    }
                    Logger.error(e, "dispense {} failed", r.kind);
                    return r;
                }
                finally {
                    finish(r);
                }
            });
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
        if (!busy.compareAndSet(false, true)) {
            throw new IllegalStateException("a dispense task is already running");
        }
        try {
            return configuration.getMachine().submit(() -> {
                try {
                    body.run();
                }
                catch (Exception e) {
                    if (r.status == RunResult.Status.RUNNING) {
                        r.status = e instanceof RunRefusedException ? RunResult.Status.REFUSED
                                : e instanceof DispenseExecutor.AbortedException ? RunResult.Status.ABORTED
                                        : RunResult.Status.ERROR;
                        r.message = String.valueOf(e.getMessage());
                    }
                    Logger.error(e, "dispense {} failed", r.kind);
                }
                finally {
                    finish(r);
                }
                return result;
            });
        }
        catch (Exception e) {
            busy.set(false);
            throw e;
        }
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
            keepalive = c.keepaliveEnabled && probeKeepalive();
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
                for (String line : channel.sendAndCollect(ControllerCommands.CAPABILITY_QUERY, c.commandTimeoutMs)) {
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
            r.interlocks = Interlocks.check(interlockInputs(c.copy(), null, estimatedPressurisedSeconds, scope));
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
                    GcodeStreamValidator.zWindow(c, mapper));
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
            channel.send("M400", -1);
            Long n = parseBCount(channel.sendAndCollect("M114", c.commandTimeoutMs), mapper.letterB());
            if (n == null && channel.isLoopbackSimulator()) {
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
