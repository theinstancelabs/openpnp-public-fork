package org.openpnp.instancelabs.dispense;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
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
    /** The Z frame of the touch-off ({@link ZCheck}: the home it was measured or last checked under); null = unknown. */
    public Long needleZOffsetHomeAtMs = null;
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

    // --- board registration, automatic N1 probe, homing (registration design §6.1) -------------
    /** Register board, the blank probe and a board run home first: "whenNeeded" or "always". */
    public String homeBeforeRun = HOME_WHEN_NEEDED;
    /** Fiducial check of Register board (search, minimum count, same-seat tolerance). */
    public FiducialCheck fiducialCheck = new FiducialCheck();
    /** The automatic N1 vacuum probe (bench probe.py window, open-air limits, timings, spots). */
    public VacuumProbe vacuumProbe = new VacuumProbe();
    /**
     * Engine-stamped: the latest registration per board key ("file:<path>" or "job:<id>"), at most
     * {@link #MAX_BOARD_REGISTRATIONS}; set only by Register board.
     */
    public Map<String, BoardRegistration> boardRegistrations = new LinkedHashMap<>();
    /**
     * Engine-stamped: when the operator said the next registration is a new seat (Next board, "new
     * seat"); null when cleared. A registration older than this is never a "same seat".
     */
    public Long newSeatAtMs = null;

    // --- pad calibration (profile calibration design §5.4) ---------------------------------------
    /** The needle: pad types and dose records name it; its OD sets the same-pad dot pitch. */
    public TipSpec tip = new TipSpec();
    /** The paste in the syringe; "New paste loaded" stamps it. */
    public PasteSpec paste = new PasteSpec();
    /** Where on the blank the pad calibration lays its test dots (default: 40 x 20 mm at the blank place). */
    public CalibrationArea calibrationArea = new CalibrationArea();
    /** Limits and sizes of the pad calibration. */
    public PadCalibrationSettings padCalibration = new PadCalibrationSettings();

    public static final String HOME_WHEN_NEEDED = "whenNeeded";
    public static final String HOME_ALWAYS = "always";
    public static final int MAX_BOARD_REGISTRATIONS = 20;

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

    /**
     * Fiducial check of Register board. The detector, centring and fit limits are code constants
     * (bench fid.py / locate.py); only these are site settings.
     */
    public static class FiducialCheck {
        /** Bench camera frame (1920 x 1080 px at OpenPnP's 0.01068376 x 0.01072961 mm/px). */
        public static final double DEFAULT_FRAME_W_MM = 1920 * 0.01068376;
        public static final double DEFAULT_FRAME_H_MM = 1080 * 0.01072961;
        /** Most camera frames the search of one fiducial may take. */
        public static final int MAX_SEARCH_FRAMES = 9;

        public int minFiducials = 2;
        /**
         * How far from its expected position (the previous registration, else the nominal board) a
         * fiducial may be found (bench re-seats moved up to 6.9 mm).
         */
        public double searchRadiusMm = 8.0;
        /** Every fiducial within this of the previous registration: the same seat. */
        public double seatToleranceMm = 0.10;

        /**
         * Camera frames needed to cover the search circle: frames centred at the prediction + (i 2hx,
         * j 2hy), where hx, hy are the usable half-frame (the 2r ring of a fiducial of diameter d,
         * 1.20 d, stays inside), counted while they touch the circle.
         */
        public static int framesNeeded(double radiusMm, double frameWMm, double frameHMm, double fiducialDiameterMm) {
            double m = 1.20 * fiducialDiameterMm;
            double hx = frameWMm / 2 - m, hy = frameHMm / 2 - m;
            if (!(hx > 0 && hy > 0)) {
                return Integer.MAX_VALUE;
            }
            int ni = (int) Math.ceil(radiusMm / (2 * hx)) + 1, nj = (int) Math.ceil(radiusMm / (2 * hy)) + 1;
            int n = 0;
            for (int i = -ni; i <= ni; i++) {
                for (int j = -nj; j <= nj; j++) {
                    double dx = Math.max(0, Math.abs(i) * 2 * hx - hx), dy = Math.max(0, Math.abs(j) * 2 * hy - hy);
                    if (Math.hypot(dx, dy) <= radiusMm) {
                        n++;
                    }
                }
            }
            return n;
        }

        /** Why the search radius does not fit a camera frame of this size, or null if it does. */
        public String searchProblem(double frameWMm, double frameHMm, double fiducialDiameterMm) {
            int n = framesNeeded(searchRadiusMm, frameWMm, frameHMm, fiducialDiameterMm);
            if (n > MAX_SEARCH_FRAMES) {
                return String.format(Locale.ROOT, "fiducialCheck.searchRadiusMm %.1f needs %s camera frames of "
                        + "%.1f x %.1f mm per fiducial (at most %d): use a smaller radius", searchRadiusMm,
                        n == Integer.MAX_VALUE ? "endless" : Integer.toString(n), frameWMm, frameHMm,
                        MAX_SEARCH_FRAMES);
            }
            return null;
        }
    }

    /**
     * The automatic N1 vacuum probe (bench probe.py / register.py). The step sizes, feeds, read
     * counts, drop rule, release wait and safety limits are code constants
     * ({@link VacuumProber.Settings}); only these are site settings.
     */
    public static class VacuumProbe {
        /** The real VAC1 needs a settle of at least this (a read 0.3 s after the valve opened looked sealed). */
        public static final int REAL_MIN_SETTLE_MS = 1000;
        /** M260 B27 sets 62.5 ms sleep-mode conversion: closer reads repeat stale values. */
        public static final int REAL_MIN_READ_DELAY_MS = 65;

        /** The descent starts this far above the nominal surface Z (register.py: 8.0 for a top of 5.5). */
        public double startAboveMm = 2.5;
        /** The floor lies this far below the higher of the nominal Z and the needle-frame surface. */
        public double floorBelowMm = 1.5;
        /** Open-air baseline window (counts): toward 0 = no tip, a leak or the pump off; below = blocked. */
        public int openAirMinCounts = -1900000;
        public int openAirMaxCounts = -1200000;
        /**
         * Nearby spots tried when a spot gives no contact or is not bare (board targets: 5 mm away and
         * spread in direction, so a mounting hole's ring cannot take them all).
         */
        public int alternates = 4;
        /** Target centre to pad edge (1.0 mm plus N1's measured 0.4 mm offset error). */
        public double padKeepOutMm = 1.4;
        /** Pump + valve ON -> first read (probe.py G4 P1500). */
        public int vacuumSettleMs = 1500;
        /** Host wait before each read (probe.py 70 ms). */
        public int readDelayMs = 70;
        /** Proven bare spots per board file name: 3..9 [bx, by] pairs in board mm (replace the computed ones). */
        public Map<String, java.util.List<double[]>> boardSpots = new LinkedHashMap<>();
    }

    public static class NamedLocation {
        public double x, y, z;
        /**
         * When the place was set, and the Z frame then (the home in effect, {@link HomeTracker#frameHomeAtMs()}); null
         * for a place saved before they were kept. After a failed Z check of a home, a place set before that home is
         * refused where its Z is used (I4, {@link ZCheck#placeMovedMessage}).
         */
        public Long setAtMs, homeAtMs;

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
        /** Its Z frame ({@link ZCheck}: the home it was measured or last checked under); null = unknown. */
        public Long homeAtMs;
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
        /** Its Z frame ({@link ZCheck}: the home it was measured or last checked under); null = unknown. */
        public Long homeAtMs;
        public String note;
        /**
         * Board surfaces: the board registration the points were probed on (board X/Y/rotation, side
         * and fiducial transform; {@link DispenseService#boardRegistration}). A re-seated board gets a
         * new registration, and I4 then asks for new probes; an untied board surface is refused.
         */
        public String boardFingerprint;
        /** True when probed on OpenPnP's simulator: refused on a machine that is not (I4). */
        public Boolean simulated;
        /**
         * The blank's points that a probe after a home replaced ({@link ZCheck#kept}): still a Z-check reference of
         * their Z frame, so a check of older data (the needle frame, the touch-off) has a point to touch. Null when
         * none was kept.
         */
        public SurfaceMap replaced;
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
        return needleFloorZMm(null);
    }

    /**
     * As above for dots that take their Z from a surface with its own frame reference (a board
     * surface is checked against the board's top Z, not the anchor): the lower of the anchor surface
     * and that reference. Null = the anchor alone.
     */
    public double needleFloorZMm(Double surfaceReferenceZMm) {
        if (needleAnchor == null) {
            return Double.NaN;
        }
        double ref = needleAnchor.surfaceZMm;
        if (surfaceReferenceZMm != null && Double.isFinite(surfaceReferenceZMm)) {
            ref = Math.min(ref, surfaceReferenceZMm);
        }
        return ref - zFrameToleranceMm + needleZOffsetMm - 0.1;
    }

    /**
     * Stores a registration as the latest of its board key, keeping at most
     * {@link #MAX_BOARD_REGISTRATIONS} keys (the oldest {@code atMs} goes).
     */
    public void putBoardRegistration(BoardRegistration reg) {
        boardRegistrations.remove(reg.boardKey);
        while (boardRegistrations.size() >= MAX_BOARD_REGISTRATIONS) {
            String oldest = null;
            long at = Long.MAX_VALUE;
            for (Map.Entry<String, BoardRegistration> e : boardRegistrations.entrySet()) {
                if (oldest == null || e.getValue() == null || e.getValue().atMs < at) {
                    oldest = e.getKey();
                    at = e.getValue() == null ? Long.MIN_VALUE : e.getValue().atMs;
                }
            }
            boardRegistrations.remove(oldest);
        }
        boardRegistrations.put(reg.boardKey, reg);
    }

    /** The registration stored with this id, or null. */
    public BoardRegistration boardRegistrationById(String id) {
        if (id == null || boardRegistrations == null) {
            return null;
        }
        for (BoardRegistration r : boardRegistrations.values()) {
            if (r != null && id.equals(r.id)) {
                return r;
            }
        }
        return null;
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
        if (c.homeBeforeRun == null) {
            c.homeBeforeRun = HOME_WHEN_NEEDED;
        }
        if (c.fiducialCheck == null) {
            c.fiducialCheck = new FiducialCheck();
        }
        if (c.vacuumProbe == null) {
            c.vacuumProbe = new VacuumProbe();
        }
        if (c.vacuumProbe.boardSpots == null) {
            c.vacuumProbe.boardSpots = new LinkedHashMap<>();
        }
        if (c.boardRegistrations == null) {
            c.boardRegistrations = new LinkedHashMap<>();
        }
        if (c.tip == null) {
            c.tip = new TipSpec();
        }
        if (c.paste == null) {
            c.paste = new PasteSpec();
        }
        if (c.calibrationArea == null) {
            c.calibrationArea = new CalibrationArea();
        }
        if (c.padCalibration == null) {
            c.padCalibration = new PadCalibrationSettings();
        }
        if (c.padCalibration.couponPlaces == null) {
            c.padCalibration.couponPlaces = new PadCalibrationSettings().couponPlaces;
        }
        if (c.padCalibration.couponSizeMm == null) {
            c.padCalibration.couponSizeMm = new PadCalibrationSettings().couponSizeMm;
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
        validateRegistration(p);
        validatePadCalibration(p);
        return p;
    }

    /** The tip, paste, calibration area and pad calibration settings (PUT /api/config may null an object). */
    private void validatePadCalibration(java.util.List<String> p) {
        if (tip == null || paste == null || calibrationArea == null || padCalibration == null) {
            p.add("tip, paste, calibrationArea and padCalibration must be objects");
            return;
        }
        tip.validate(p);
        paste.validate(p);
        padCalibration.validate(p);
        calibrationArea.validate(this, p);
    }

    /** Ranges of the registration, probe and homing settings. */
    private void validateRegistration(java.util.List<String> p) {
        if (!HOME_WHEN_NEEDED.equals(homeBeforeRun) && !HOME_ALWAYS.equals(homeBeforeRun)) {
            p.add("homeBeforeRun must be '" + HOME_WHEN_NEEDED + "' or '" + HOME_ALWAYS + "'");
        }
        FiducialCheck f = fiducialCheck;
        if (f.minFiducials < 2 || f.minFiducials > 10) {
            p.add("fiducialCheck.minFiducials must be 2 to 10");
        }
        if (!(f.searchRadiusMm >= 1 && f.searchRadiusMm <= 12)) {
            p.add("fiducialCheck.searchRadiusMm must be 1 to 12 mm");
        }
        else {
            String why = f.searchProblem(FiducialCheck.DEFAULT_FRAME_W_MM, FiducialCheck.DEFAULT_FRAME_H_MM, 1.0);
            if (why != null) {
                p.add(why);
            }
        }
        if (!(f.seatToleranceMm >= 0.01 && f.seatToleranceMm <= 1)) {
            p.add("fiducialCheck.seatToleranceMm must be 0.01 to 1 mm");
        }
        VacuumProbe v = vacuumProbe;
        if (!(v.startAboveMm >= 0.5 && v.startAboveMm <= 10)) {
            p.add("vacuumProbe.startAboveMm must be 0.5 to 10 mm");
        }
        if (!(v.floorBelowMm >= 0.2 && v.floorBelowMm <= 3)) {
            p.add("vacuumProbe.floorBelowMm must be 0.2 to 3 mm");
        }
        if (!(v.openAirMinCounts < v.openAirMaxCounts && v.openAirMaxCounts < 0)) {
            p.add("vacuumProbe.openAirMinCounts must be below openAirMaxCounts, and both below 0");
        }
        if (v.alternates < 0 || v.alternates > 4) {
            p.add("vacuumProbe.alternates must be 0 to 4");
        }
        if (!(v.padKeepOutMm >= 0.5 && v.padKeepOutMm <= 5)) {
            p.add("vacuumProbe.padKeepOutMm must be 0.5 to 5 mm");
        }
        if (v.vacuumSettleMs < 0 || v.vacuumSettleMs > 5000) {
            p.add("vacuumProbe.vacuumSettleMs must be 0 to 5000 ms");
        }
        if (v.readDelayMs < 0 || v.readDelayMs > 500) {
            p.add("vacuumProbe.readDelayMs must be 0 to 500 ms");
        }
        for (Map.Entry<String, java.util.List<double[]>> e : v.boardSpots.entrySet()) {
            java.util.List<double[]> spots = e.getValue();
            boolean ok = e.getKey() != null && !e.getKey().isEmpty() && spots != null && spots.size() >= 3
                    && spots.size() <= 9;
            for (int i = 0; ok && i < spots.size(); i++) {
                double[] xy = spots.get(i);
                ok = xy != null && xy.length == 2 && Double.isFinite(xy[0]) && Double.isFinite(xy[1]);
            }
            if (!ok) {
                p.add("vacuumProbe.boardSpots['" + e.getKey() + "'] must be 3 to 9 [x, y] pairs of numbers (board mm)");
            }
        }
        if (boardRegistrations.size() > MAX_BOARD_REGISTRATIONS) {
            p.add("boardRegistrations: at most " + MAX_BOARD_REGISTRATIONS + " boards");
        }
        for (Map.Entry<String, BoardRegistration> e : boardRegistrations.entrySet()) {
            BoardRegistration r = e.getValue();
            if (r == null || !e.getKey().equals(r.boardKey) || !r.isAffineValid()) {
                p.add("boardRegistrations['" + e.getKey() + "'] must carry its board key and a finite, invertible "
                        + "affine (6 numbers)");
            }
        }
    }
}
