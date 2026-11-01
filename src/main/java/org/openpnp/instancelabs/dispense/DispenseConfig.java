package org.openpnp.instancelabs.dispense;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Dispenser configuration. Persisted as JSON in {@code <configDir>/instancelabs/dispense-config.json},
 * deliberately NOT in machine.xml so the OpenPnP machine configuration is never touched.
 *
 * <p>All lengths are millimetres in OpenPnP machine coordinates of the dispenser nozzle (N2) unless
 * stated otherwise. Rotations of the auger axis are degrees of the B axis (Marlin J axis, 4.44
 * steps/deg). Defaults follow plan §4.5, §5.1, §5.6 and §6.1.</p>
 *
 * <p>Fields are public for Gson and the web API. Use {@link #copy()} before mutating a shared
 * instance.</p>
 */
public class DispenseConfig {
    public static final String FILE_NAME = "dispense-config.json";
    public static final String SUBDIR = "instancelabs";

    /**
     * Version of this file's schema. 2: the pneumatic and watchdog G-code strings were removed (they
     * are constants in ControllerCommands). 3: the dose and per-dot motion moved into
     * {@link #profile}; prime + test dot + optical test-dot split replaced by the purge row; the
     * volumetric q model removed (bench 2026-10-28). Older files load, the removed keys are ignored,
     * and {@link #migrate()} brings the motion values to the bench recipe.
     */
    public static final int SCHEMA_VERSION = 3;
    /** Largest I4 frame tolerance: below half the needle's 2.59 mm offset from N1 (bench, new mount). */
    public static final double MAX_FRAME_TOLERANCE_MM = 1.2;
    public int schemaVersion = SCHEMA_VERSION;

    // --- identity of the dispenser in the OpenPnP machine -----------------------------------
    /** Nozzle carrying the auger. Matched against nozzle id first, then name. */
    public String nozzle = "N2";
    /** OpenPnP axis name of the auger motor ("b" = Marlin J axis, G-code letter B). */
    public String bAxisName = "b";

    // --- pneumatics (plan §4.1, §4.2a: pump ON/OFF only, never PWM; valve S255 only) ---------
    // The pump/valve G-code is fixed in ControllerCommands (M106 P2 S255 / M107 P2, M106 P3 S255 /
    // M107 P3): it is not a setting, so it cannot be PWM'd, swapped or left on by a settings edit.
    /** Pump ON -> wait -> valve ON (plan §4.5 recommendation 1; bench disp.py 1000 ms). */
    public int pumpToValveSettleMs = 1000;
    /** Valve ON -> settle before the first rotation (bench disp.py 3000 ms). */
    public int valveSettleMs = 3000;

    // --- the dispense recipe ---------------------------------------------------------------
    /** Active dispense profile: pressure note, screw turn per dot, dwell, gap, purge (bench recipe). */
    public DispenseProfile profile = new DispenseProfile();
    /** Purge row: distinct spots on the blank for the purge dots before the work (consumed). */
    public PurgeRow purgeRow = new PurgeRow();
    /** Clean-pad guard (I10): refuse to dispense on pads that still carry paste. */
    public CleanPadCheck cleanPadCheck = new CleanPadCheck();

    // --- named locations (x, y: N2 nozzle coordinates; z: surface Z in OpenPnP's frame, i.e. the
    // N1 nozzle Z touching that surface, the frame of OpenPnP's board Z) -------------------------
    public Map<String, NamedLocation> locations = new LinkedHashMap<>();
    /** Waste / prime pad location name (plan §4.5, I9). */
    public String wasteLocationName = "waste";
    /** Blank coupon (glass/copper) for dot-ladder calibration and needle touch-off. */
    public String blankLocationName = "blank";

    // --- Z geometry (the dispense gap is profile.gapMm) ------------------------------------------
    /** Hop height above the dispense Z between dots on the same board. */
    public double hopHeightMm = 1.0;
    /** N2 Z used for travel to/from the board, waste and blank. 31.5 = see-saw balanced (LIVE home). */
    public double travelSafeZMm = 31.5;
    /**
     * Needle Z offset: N2 Z at which the needle tip touches a surface whose OpenPnP Z (N1 contact
     * Z) is s equals s + needleZOffsetMm. Bootstrapped by {@link #needleAnchor}, refined by touch-offs.
     */
    public double needleZOffsetMm = 0.0;
    /** Epoch ms of the last needle touch-off; null = never (I4). */
    public Long needleZOffsetSetAtMs = null;
    /** Epoch ms of the last needle change; I4 requires a touch-off after it. */
    public Long needleChangedAtMs = null;
    /**
     * Needle XY correction (calibration): the needle lands at camera + N2 head offset + this. The
     * head offset (machine.xml) is the bench's (25.291, -75.926); refinements from landing data go
     * here, not into machine.xml. Every dispensed target is moved by minus this.
     */
    public NeedleXyTrim needleXyTrim = new NeedleXyTrim();
    /**
     * I4 Z frame: one surface touched by N1 and by the needle (review finding: nothing tied the
     * surface Z to the frame of the needle offset). Set only by POST /api/needle/anchor.
     */
    public NeedleAnchor needleAnchor = null;
    /**
     * Probed surfaces in OpenPnP's frame (N1 contact Z), by name: {@link #boardSurfaceName} for
     * board runs (per-dot Z from its plane), {@link #blankLocationName} for the dot ladder and the
     * touch-off. Set only through /api/surfaces.
     */
    public Map<String, SurfaceMap> surfaces = new LinkedHashMap<>();
    public String boardSurfaceName = "board";
    /** I4: waste, blank and every surface point must lie within this of the anchor surface (mm). */
    public double zFrameToleranceMm = 1.0;
    /** I4: largest plane-fit residual of a surface map (gap budget; must be below the gap). */
    public double surfaceMaxResidualMm = 0.04;
    /**
     * I4: a dot may lie at most this far outside the probed area (convex hull of the points). The
     * bench probes boards at 8 mm from the edge; the edge pads are ~3 mm beyond (plane extrapolation
     * error there 0.01-0.035 mm on boards with residuals <= 0.017).
     */
    public double surfaceMarginMm = 3.5;
    /** I4: touch-offs and manual entries may move the offset at most this far from the anchor's. */
    public double needleOffsetMaxDriftMm = 1.0;
    /** Extra height of the prime at the waste pad while the needle is not touched off yet. */
    public double touchOffPrimeExtraClearanceMm = 0.5;

    // --- motion --------------------------------------------------------------------------------
    // XY and Z at M204 S2000 (bench). The screw's acceleration and feed are in the profile; M204 S
    // also caps the B axis, so the profile's value is sent around the B moves only (F5).
    public double travelFeedMmS = 200;
    public double travelAccelMmS2 = 2000;
    public double zDownFeedMmS = 50;
    public double zLiftFeedMmS = 50;
    /** Dispense-lift acceleration (bench: the same 2000 as XY; no slow break-off). */
    public double zLiftAccelMmS2 = 2000;
    /** B axis feed limit sent with M203 (deg/s); 2400 = 400 rpm, the Vieweg maximum. */
    public double bMaxFeedDegS = 2400;
    /**
     * Marlin M92 B: steps per degree of B. 360 deg = 1598.4 steps, so one screw revolution (1600
     * steps) is 360.36 deg; B targets are computed from whole steps so dots never drift.
     */
    public double bStepsPerDeg = 4.44;
    /** Motor steps per screw revolution (200 full steps x 8 microsteps). */
    public int bStepsPerRev = 1600;
    /** +1 if positive B extrudes, -1 if negative B extrudes. */
    public int bExtrudeSign = 1;
    /** Follow the axes' backlash compensation settings when streaming (as OpenPnP would). */
    public boolean honorBacklash = true;
    /** Timeout for one streamed command acknowledgement. */
    public long commandTimeoutMs = 10000;

    // --- manual prime / test dot at the waste place, calibration prime ---------------------------
    // Board runs purge on the purge row instead (I9), in the same pressure session as the work.
    /** Prime turn at the waste place for calibration runs and the manual prime (one revolution). */
    public double primeRotationDeg = 360.36;
    /** Manual test dot position relative to the waste location (mm). */
    public double testDotOffsetXMm = 2.0;
    public double testDotOffsetYMm = 0.0;

    // --- host watchdog keepalive (only used if M115 reports Cap:HOST_WATCHDOG:1) ----------------
    // Query, arm, ping and disarm commands are fixed in ControllerCommands (M115, M990 S<window>,
    // M990, M990 S0); only the timing is a setting.
    public boolean keepaliveEnabled = true;
    /** Firmware watchdog window sent with M990 S (ms); at least 2 x keepaliveIntervalMs. */
    public int keepaliveWindowMs = 3000;
    public int keepaliveIntervalMs = 1000;

    // --- interlocks ----------------------------------------------------------------------------
    /** I7: maximum continuous pump+valve on-time per session (s). */
    public double maxContinuousOnTimeS = 600;
    /** I5: barrel pressure window (no sensor yet; disabled by default). */
    public boolean pressureInterlockEnabled = false;
    public double pressureMinPsi = 1.0;
    public double pressureMaxPsi = 19.0;
    /** §5.7 rule: refuse pads whose type status is below this (not-started..validated). */
    public String minPadTypeStatus = PadTypeStatus.CHARACTERIZED.dbName;
    /** Refuse the board if any pad is flagged (true), or skip flagged pads (false). */
    public boolean refuseFlaggedPads = true;

    // --- planning (one profile dot per pad unless the pad type's rule asks for a grid) ----------
    /** Equivalent stencil thickness t for new pad types (informational: paste volume a stencil would print). */
    public double pasteThicknessMm = 0.10;
    /** Paste density, mg/mm^3 (GC 50 T5 84 %: 3.76), for scale records. */
    public double pasteDensityMgMm3 = 3.7;
    /** Include placements disabled for pick-and-place (paste is still wanted on their pads). */
    public boolean includeDisabledPlacements = true;
    public int twoOptMaxPasses = 50;

    /** Needle XY correction on top of the N2 head offset (calibration value). */
    public static class NeedleXyTrim {
        public double dxMm, dyMm;
        public Long setAtMs;
        public String note;
    }

    /**
     * A row of purge spots on a probed surface: spot i at (x0 + i pitchX, y0 + i pitchY), Z from the
     * surface plane. Spots are used once; {@link #usedSpots} counts the ones already used (reserved
     * before a run streams) and is reset after the surface is wiped.
     */
    public static class PurgeRow {
        /** Surface the spots lie on (its N1 probe points give the Z). */
        public String surfaceName = "blank";
        public Double x0Mm, y0Mm;
        public double pitchXMm = 2.5;
        public double pitchYMm = 0;
        public int spots = 0;
        public int usedSpots = 0;
        public Long resetAtMs;

        public boolean isDefined() {
            return x0Mm != null && y0Mm != null && spots > 0;
        }

        public int spotsLeft() {
            return Math.max(0, spots - usedSpots);
        }

        public double[] spot(int i) {
            return new double[] {x0Mm + i * pitchXMm, y0Mm + i * pitchYMm};
        }
    }

    /**
     * Clean-pad guard (bench disp.py --require-clean): before the pressure goes on, the top camera
     * looks at every work dot; the share of bright pixels (OpenCV gray > grayLevel) in a box of
     * boxWMm x boxHMm at the target must be >= minBrightFraction. Bare copper pads read >= 0.90,
     * paste left from an earlier run reads dark blue. The box is the bench's 75 x 73 px at its scale.
     */
    public static class CleanPadCheck {
        public boolean enabled = true;
        public int grayLevel = 200;
        public double minBrightFraction = 0.8;
        public double boxWMm = 0.806;
        public double boxHMm = 0.807;
    }

    public static class NamedLocation {
        public double x, y, z;

        public NamedLocation() {
        }

        public NamedLocation(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public String toString() {
            return String.format("(%.3f, %.3f, %.3f)", x, y, z);
        }
    }

    /** N1 contact and needle contact on one surface: the needle Z frame (I4). */
    public static class NeedleAnchor {
        /** N1 nozzle Z (OpenPnP frame) with N1 touching the reference surface. */
        public double surfaceZMm;
        /** N2 Z with the needle tip touching the same surface. */
        public double needleContactZMm;
        /** Where (informational). */
        public Double xMm, yMm;
        public long setAtMs;
        /**
         * Set when the needle contact came from the bench relation "needle contact raw Z = K - N1
         * contact Z" (side camera): K, else null (needle touched the surface).
         */
        public Double rawSumMm;

        public NeedleAnchor() {
        }

        public NeedleAnchor(double surfaceZMm, double needleContactZMm, Double xMm, Double yMm, long setAtMs) {
            this.surfaceZMm = surfaceZMm;
            this.needleContactZMm = needleContactZMm;
            this.xMm = xMm;
            this.yMm = yMm;
            this.setAtMs = setAtMs;
        }

        public double offsetMm() {
            return needleContactZMm - surfaceZMm;
        }
    }

    /** One probed point: machine X/Y, Z = N1 contact Z (OpenPnP frame). */
    public static class SurfacePoint {
        public double x, y, z;

        public SurfacePoint() {
        }

        public SurfacePoint(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** A probed surface (board Z-map or blank plane). */
    public static class SurfaceMap {
        public java.util.List<SurfacePoint> points = new java.util.ArrayList<>();
        public Long setAtMs;
        public String note;
        /**
         * Board surfaces: the board registration the points were probed on (board X/Y/rotation, side
         * and fiducial transform; {@link DispenseService#boardRegistration}). A re-seated board gets a
         * new registration, and I4 then asks for new probes; an untied board surface is refused.
         */
        public String boardFingerprint;
    }

    public NamedLocation location(String name) {
        return locations == null ? null : locations.get(name);
    }

    public SurfaceMap surface(String name) {
        return surfaces == null || name == null ? null : surfaces.get(name);
    }

    /** Needle frame anchored since the last needle change (I4). */
    public boolean isNeedleAnchored() {
        return needleAnchor != null && (needleChangedAtMs == null || needleAnchor.setAtMs >= needleChangedAtMs);
    }

    /** Offset touched off (or entered) since the last needle change and since the last anchor (I4). */
    public boolean isNeedleTouchedOff() {
        if (needleZOffsetSetAtMs == null) {
            return false;
        }
        if (needleAnchor != null && needleZOffsetSetAtMs < needleAnchor.setAtMs) {
            return false;
        }
        return needleChangedAtMs == null || needleZOffsetSetAtMs >= needleChangedAtMs;
    }

    /** The dispense gap of the active profile. */
    public double gapMm() {
        return profile.gapMm;
    }

    /**
     * Lowest N2 Z the needle may ever be commanded to (I4 hard floor): the anchor surface minus the
     * frame tolerance, plus the offset, minus the deepest touch-off gap (0.1 mm). NaN without anchor.
     */
    public double needleFloorZMm() {
        if (needleAnchor == null) {
            return Double.NaN;
        }
        return needleAnchor.surfaceZMm - zFrameToleranceMm + needleZOffsetMm - 0.1;
    }

    public PadTypeStatus minPadTypeStatus() {
        return PadTypeStatus.fromDb(minPadTypeStatus);
    }

    private static Gson gson() {
        return new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    }

    public String toJson() {
        return gson().toJson(this);
    }

    public static DispenseConfig fromJson(String json) {
        DispenseConfig c = gson().fromJson(json, DispenseConfig.class);
        if (c == null) {
            c = new DispenseConfig();
        }
        if (c.locations == null) {
            c.locations = new LinkedHashMap<>();
        }
        if (c.surfaces == null) {
            c.surfaces = new LinkedHashMap<>();
        }
        if (c.profile == null) {
            c.profile = new DispenseProfile();
        }
        if (c.purgeRow == null) {
            c.purgeRow = new PurgeRow();
        }
        if (c.cleanPadCheck == null) {
            c.cleanPadCheck = new CleanPadCheck();
        }
        if (c.needleXyTrim == null) {
            c.needleXyTrim = new NeedleXyTrim();
        }
        c.migrate();
        return c;
    }

    /**
     * Schema 1/2 -> 3: the per-dot motion becomes the bench recipe (XY and Z at 2000 mm/s², lift
     * at 50 mm/s, B limit 400 rpm) and the I4 tolerances follow the new needle frame (offset
     * -2.59 mm, so the frame tolerance must stay below it). The removed keys were dropped on load.
     */
    void migrate() {
        if (schemaVersion >= SCHEMA_VERSION) {
            return;
        }
        travelAccelMmS2 = 2000;
        zLiftAccelMmS2 = 2000;
        zLiftFeedMmS = Math.max(zLiftFeedMmS, 50);
        bMaxFeedDegS = Math.max(bMaxFeedDegS, 2400);
        surfaceMaxResidualMm = Math.min(surfaceMaxResidualMm, 0.04);
        surfaceMarginMm = Math.max(surfaceMarginMm, 3.5);
        zFrameToleranceMm = Math.min(zFrameToleranceMm, MAX_FRAME_TOLERANCE_MM);
        if (Math.abs(primeRotationDeg - 360) < 1e-9) {
            primeRotationDeg = 360.36;
        }
        schemaVersion = SCHEMA_VERSION;
    }

    public DispenseConfig copy() {
        return fromJson(toJson());
    }

    public static File fileFor(File configDir) {
        return new File(new File(configDir, SUBDIR), FILE_NAME);
    }

    /** Loads the config, or returns defaults if the file does not exist. */
    public static DispenseConfig load(File configDir) throws IOException {
        File f = fileFor(configDir);
        if (!f.exists()) {
            return new DispenseConfig();
        }
        return fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    /** Atomically writes the config (temp file + rename). */
    public void save(File configDir) throws IOException {
        File f = fileFor(configDir);
        f.getParentFile().mkdirs();
        File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
        Files.write(tmp.toPath(), toJson().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /** Basic sanity checks; returns a list of problems (empty if OK). */
    public java.util.List<String> validate() {
        java.util.List<String> p = new java.util.ArrayList<>();
        // Pump and valve G-code: constants (ControllerCommands), nothing to validate here.
        // Z geometry (review finding: hop/travel values could move a nozzle down on the see-saw).
        // Machine-dependent bounds (travel Z inside the Z axis safe zone, hop <= travel) are checked
        // against the live axes when a program is built (GcodeStreamBuilder).
        for (String problem : profile.validate(this)) {
            p.add(problem);
        }
        if (!(hopHeightMm > 0 && hopHeightMm <= 10)) {
            p.add("hopHeightMm must be > 0 and <= 10");
        }
        if (!Double.isFinite(travelSafeZMm)) {
            p.add("travelSafeZMm must be a number");
        }
        if (!(zFrameToleranceMm > 0 && zFrameToleranceMm <= MAX_FRAME_TOLERANCE_MM)) {
            p.add("zFrameToleranceMm must be > 0 and <= 1.2: a height taken with the needle instead of N1 is 2.59 mm "
                    + "off, and only a tolerance below half of that always catches it");
        }
        if (!(surfaceMaxResidualMm > 0 && surfaceMaxResidualMm < profile.gapMm)) {
            p.add("surfaceMaxResidualMm must be > 0 and below the profile gap");
        }
        if (!(surfaceMarginMm >= 0 && surfaceMarginMm <= 20)) {
            p.add("surfaceMarginMm must be 0 to 20");
        }
        if (!(needleOffsetMaxDriftMm > 0 && needleOffsetMaxDriftMm <= 2)) {
            p.add("needleOffsetMaxDriftMm must be > 0 and <= 2");
        }
        if (!(touchOffPrimeExtraClearanceMm >= 0 && touchOffPrimeExtraClearanceMm <= 5)) {
            p.add("touchOffPrimeExtraClearanceMm must be 0 to 5");
        }
        if (!(bMaxFeedDegS > 0 && bMaxFeedDegS <= DispenseProfile.MAX_RPM * 6)) {
            p.add("bMaxFeedDegS must be > 0 and <= 2400 (400 rpm, the Vieweg maximum)");
        }
        if (!(bStepsPerDeg > 0) || bStepsPerRev < 1) {
            p.add("bStepsPerDeg and bStepsPerRev must be > 0");
        }
        if (bExtrudeSign != 1 && bExtrudeSign != -1) {
            p.add("bExtrudeSign must be +1 or -1");
        }
        if (!(zLiftAccelMmS2 > 0 && zLiftAccelMmS2 <= 5000 && travelAccelMmS2 > 0 && travelAccelMmS2 <= 5000)) {
            p.add("travelAccelMmS2 and zLiftAccelMmS2 must be > 0 and <= 5000 (the screw's acceleration is the "
                    + "profile's)");
        }
        if (!(maxContinuousOnTimeS > 0 && maxContinuousOnTimeS <= 3600)) {
            p.add("maxContinuousOnTimeS must be > 0 and <= 3600 s (I7: a pressure hold switches off after it)");
        }
        if (pumpToValveSettleMs < 0 || valveSettleMs < 0 || pumpToValveSettleMs > 9000 || valveSettleMs > 9000) {
            p.add("settle values must be 0 to 9000 ms");
        }
        if (purgeRow.spots < 0 || purgeRow.usedSpots < 0 || !(Math.hypot(purgeRow.pitchXMm, purgeRow.pitchYMm) >= 1.5
                || purgeRow.spots <= 1)) {
            p.add("purgeRow: spots and usedSpots must be >= 0 and the pitch at least 1.5 mm (distinct dots)");
        }
        if (!(cleanPadCheck.grayLevel >= 0 && cleanPadCheck.grayLevel <= 254
                && cleanPadCheck.minBrightFraction > 0 && cleanPadCheck.minBrightFraction <= 1
                && cleanPadCheck.boxWMm > 0 && cleanPadCheck.boxWMm <= 3 && cleanPadCheck.boxHMm > 0
                && cleanPadCheck.boxHMm <= 3)) {
            p.add("cleanPadCheck: grayLevel 0..254, minBrightFraction 0..1, box 0..3 mm");
        }
        if (!(Math.abs(needleXyTrim.dxMm) <= 1 && Math.abs(needleXyTrim.dyMm) <= 1)) {
            p.add("needleXyTrim must be within +-1 mm (larger: fix the N2 head offset in OpenPnP)");
        }
        if (keepaliveIntervalMs < 100) {
            p.add("keepaliveIntervalMs must be >= 100");
        }
        if (keepaliveWindowMs < Math.max(ControllerCommands.KEEPALIVE_MIN_MS, 2L * keepaliveIntervalMs)
                || keepaliveWindowMs > 60000) {
            p.add("keepaliveWindowMs must be at least 2 x keepaliveIntervalMs and at most 60000");
        }
        if (pasteThicknessMm <= 0 || pasteDensityMgMm3 <= 0) {
            p.add("paste thickness and density must be > 0");
        }
        return p;
    }
}
