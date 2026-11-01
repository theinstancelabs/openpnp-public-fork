# Instance Labs dispense engine — Java API (`org.openpnp.instancelabs.dispense`)

Branch `il/bench-sync` (engine commit `271d4738f`: the bench recipe of 2026-10-28, `runs/p18_board1`: 160/160 pads,
0.714 mm², CV 13 %, 5,490 DPH). Spec: `docs/plan-2026-10-27-evidence-backed-roadmap.md` §4.4–4.6, §5.1–5.7, §6.1 (in
the openpnp-revamp project), changed by the bench recipe where this file says so. This file is the contract for the
web API layer (`docs/instancelabs/WEB_API.md`). Every machine action runs on OpenPnP's machine thread and returns a
`java.util.concurrent.Future`; only one dispense task runs at a time (`IllegalStateException("a dispense task is
already running")` otherwise).

## 1. Lifecycle

| Signature | Notes |
|---|---|
| `public static void DispenseService.install(Configuration configuration)` | Called from `org.openpnp.Main` right after `Configuration.initialize()`. Registers a `ConfigurationListener`; the service starts on `configurationComplete`. Never throws. |
| `public static synchronized DispenseService DispenseService.start(Configuration configuration) throws SQLException, IOException` | (Re)starts the service. Used by `install` and tests. |
| `public static DispenseService DispenseService.get()` | `null` before the configuration has loaded. |
| `public synchronized void shutdown()` | Aborts any run, removes the machine listener, closes the DB. |
| `public boolean isAvailable()` / `public String getUnavailableReason()` | Null-safe: if the dispenser nozzle (config `nozzle`, default `N2`, matched by id then name) does not exist, the service exists but every run method throws `IllegalStateException`. |
| `public Nozzle getDispenserNozzle()` | May be null. |
| `public Long getControllerConnectedAtMs()` | When OpenPnP last enabled (connected to) the controller; set by the machine listener. The B step count restarts at controller power-up, so the screw start angle is only valid after this time (I11). |

## 2. Configuration (`<configDir>/instancelabs/dispense-config.json`, never machine.xml)

| Signature | Notes |
|---|---|
| `public DispenseConfig getConfig()` | A copy (Gson-serialisable POJO, public fields). |
| `public synchronized void updateConfig(DispenseConfig newCfg) throws IOException` | Validates (`DispenseConfig.validate()`, which includes `profile.validate(cfg)`), writes atomically, applies. `IllegalArgumentException` on invalid input. A changed `profile.startAngleDeg` stamps `profile.startAngleSetAtMs`; a changed `needleXyTrim` stamps `needleXyTrim.setAtMs`. |
| `public synchronized DispenseProfile setStartAngle(Double angleDeg, String note) throws IOException` | I11: sets (`angleDeg` != null) or confirms (null) the screw start angle for the current controller power-up; stamps `startAngleSetAtMs`, logs an `I11` event. |
| `public synchronized void resetPurgeRow() throws IOException` | The purge row was wiped: `usedSpots = 0`, `resetAtMs = now` (I9). |
| `public synchronized void setLocation(String name, double x, double y, double z) throws IOException` | Named places (`waste`, `blank`, calibration cup, …): x/y in N2 machine coordinates, `z` = surface Z in OpenPnP's frame (the N1 nozzle Z touching that surface). |
| `public synchronized void markNeedleChanged() throws IOException` | I4: invalidates the needle Z frame and the offset until a new frame and a touch-off. |
| `public synchronized NeedleAnchor setNeedleAnchor(double surfaceZMm, double needleContactZMm, Double x, Double y[, Double rawSumMm])` | I4 needle Z frame: the same surface touched by N1 (`surfaceZMm`) and by the needle tip (`needleContactZMm`, N2 Z); offset := difference. `rawSumMm` records the bench relation K the contact came from (or null). Not a touch-off. |
| `public double needleContactZFromRawSum(double n1ContactZ, double rawSumMm) throws Exception` | Needle contact (N2 Z, OpenPnP frame) from the bench relation "needle contact raw Z = K − N1 contact Z" (side camera, 2026-10-27: K = 65.59). On the Lumen see-saw (z2 = 63 − raw) that is N1 − 2.59: N1 5.5 → needle 2.91, offset −2.59. |
| `public synchronized void setNeedleZOffset(double offsetMm, String method, String details) throws IOException, SQLException` | Manual offset entry or touch-off result; appends a `needle_touchoff` row. Must stay within `needleOffsetMaxDriftMm` of the frame's offset (I4). |
| `public synchronized SurfacePlane setSurface(String name, List<SurfacePoint> points, String note[, String boardFingerprint])` / `clearSurface(name)` / `surfacePlane(name)` | Probed surfaces (N1 contact heights, >= 3 points spanning an area; least-squares plane + convex hull). `board`: per-dot Z of board runs; `boardFingerprint` ties it to the board registration it was probed on (I4 asks for new probes after a re-seat). `blank`: the purge row, dot ladder and touch-off. |
| `public static String boardFingerprint(BoardLocation bl)` | Global location (incl. Z), side, fiducial transform and every placement, hashed: plan staleness at Run. |
| `public static String boardRegistration(BoardLocation bl)` | Board X/Y/rotation, side and fiducial transform only, hashed (`plan.boardRegistration`). A board surface is tied to it: I4 refuses an untied board surface and one probed on another registration (re-seat); editing placements or the nominal board Z does not change it. |
| `public boolean isBZeroed()` | I2 state: `G92 B0` sent since the last home / enable / disable. |

### `DispenseConfig` (schema 3)

`schemaVersion 3`. Schema 1/2 files load: removed keys are ignored, and `migrate()` brings the motion values to the
bench recipe (`travelAccelMmS2` and `zLiftAccelMmS2` 2000, `zLiftFeedMmS` >= 50, `bMaxFeedDegS` >= 2400,
`surfaceMaxResidualMm` <= 0.04, `surfaceMarginMm` >= 3.5, `zFrameToleranceMm` <= 1.2, `primeRotationDeg` 360 → 360.36).
Values a schema-2 file sets explicitly and `migrate()` does not touch (for example `valveSettleMs 1500` in
`/home/lumen/instancelabs/config/dispense-config.json`) stay as they are.

Removed in schema 3: `needleGapMm` (now `profile.gapMm`, read through `cfg.gapMm()`), `bFeedDegS`, `bAccelDegS2`,
`retractDeg`, `retractFeedDegS`, `dwellMs`, `abortDisablesB`, `primeAndTestDot`, `testDotOpticalCheck`,
`rePrimeRotationDeg`, `testDotMinDiameterRatio`, `provisionalQMm3PerRev`, `maxDotsPerPad`, `dotFitFactor`.

Fields and defaults:
- identity: `nozzle "N2"`, `bAxisName "b"`;
- pneumatics: `pumpToValveSettleMs 1000`, `valveSettleMs 3000` (bench `disp.py`);
- recipe: `profile` (below), `purgeRow` (below), `cleanPadCheck` (below);
- places: `locations {}`, `wasteLocationName "waste"`, `blankLocationName "blank"`;
- Z: `hopHeightMm 1.0` (0 < hop <= 10), `travelSafeZMm 31.5` (must map into the Z axis safe zone),
  `needleZOffsetMm 0`, `needleZOffsetSetAtMs null`, `needleChangedAtMs null`, `needleAnchor null`
  (`surfaceZMm`, `needleContactZMm`, `xMm`, `yMm`, `setAtMs`, `rawSumMm`), `surfaces {}` (`points`, `setAtMs`, `note`,
  `boardFingerprint` = the board registration it was probed on), `boardSurfaceName "board"`, `zFrameToleranceMm 1.0`
  (0 < tol <= 1.2: a height taken with the needle is 2.59 mm off, and only a tolerance below half of that always
  catches it), `surfaceMaxResidualMm 0.04` (below the gap),
  `surfaceMarginMm 3.5` (the bench probes 8 mm in from the board edge, the edge pads lie ~3 mm beyond),
  `needleOffsetMaxDriftMm 1.0`, `touchOffPrimeExtraClearanceMm 0.5`;
- needle XY: `needleXyTrim {dxMm 0, dyMm 0, setAtMs null, note null}` (within ±1 mm): the needle lands at camera +
  N2 head offset + trim, so every dispensed target is moved by minus the trim;
- motion: `travelFeedMmS 200`, `travelAccelMmS2 2000`, `zDownFeedMmS 50`, `zLiftFeedMmS 50`, `zLiftAccelMmS2 2000`
  (each <= 5000), `bMaxFeedDegS 2400` (sent as `M203 B`; <= 400 rpm, the Vieweg maximum), `bStepsPerDeg 4.44`
  (Marlin `M92 B`; one screw revolution of 1600 steps is 360.36°), `bStepsPerRev 1600`, `bExtrudeSign +1`,
  `honorBacklash true`, `commandTimeoutMs 10000`;
- manual prime / test dot at the waste place and calibration primes: `primeRotationDeg 360.36`, `testDotOffsetXMm 2`,
  `testDotOffsetYMm 0` (board runs purge on the purge row instead);
- keepalive: `keepaliveEnabled true`, `keepaliveWindowMs 3000` (>= 2 × interval), `keepaliveIntervalMs 1000`;
- interlocks: `maxContinuousOnTimeS 600` (I7), `pressureInterlockEnabled false`, `pressureMinPsi 1`,
  `pressureMaxPsi 19` (I5), `minPadTypeStatus "characterized"`, `refuseFlaggedPads true`;
- planning: `pasteThicknessMm 0.10`, `pasteDensityMgMm3 3.7` (informational / scale records),
  `includeDisabledPlacements true`, `twoOptMaxPasses 50`.

### `DispenseProfile` (`cfg.profile`): the recipe

One profile per pressure session, the same for the purge row and the work. Defaults = the bench's `p18_board1`:

| Field | Default | Meaning |
|---|---|---|
| `name` | `"18 psi half turn"` | |
| `pressurePsi` | 18 | A note: the regulator is set by hand (no sensor; I6). The window is narrow: 16 psi starved the screw, 20 psi drooled. Validated `0 < psi <= 21.7` (DV-5005 1.5 bar). |
| `netStepsPerDot` | 800 | Net screw turn per dot in motor steps (half a revolution). Must divide 1600 (1600, 800, 400, …) or be 1600/3200, so the start angle repeats. |
| `suckBackDeg` | 10 | Suck-back after each dot, sent as `round(10 × 4.44)` = 44 steps. |
| `startAngleDeg` | 298 | Screw angle at which the first work dot starts (calibration). |
| `startAngleSetAtMs` | null | When it was set or confirmed; I11 needs it after the last controller connect. |
| `screwRpm` | 180 | B feed = rpm × 360 deg/min (`F64800`). <= 400 rpm and <= `bMaxFeedDegS`. |
| `screwAccelDegS2` | 20000 | Sent as `M204 S` around the B moves only (1000..30000). |
| `dwellMs` | 250 | Dwell after the forward turn, before the suck-back: the dot-size knob at fixed pressure. |
| `gapMm` | 0.10 | Needle-to-surface gap while dispensing (0.05..0.15; >= 0.20 misses dots). |
| `purgeDots` | 8 | Purge dots on the purge row before the work (>= 2 for a partial turn: the first sets the start angle, at least one normal dot flushes it). |
| `notes` | | Operator notes. |

Derived: `netDeg(c)` = 800 / 4.44 = 180.1802°, `forwardDeg(c)` = 844 / 4.44 = 190.0901°, `suckBackSteps(c)` = 44,
`revolutionsPerDot(c)` = 0.5, `feedDegS()` = 1080, `isPartialTurn(c)` (net steps not a multiple of 1600),
`startAngleSteps(c)` = `round(298 / 360 × 1600) mod 1600` = 1324 (297.9°), `startAngles(c)` = [297.9, 117.9],
`phaseSteps(c, restCount, dotsAfter)`, `static angleOfCount(count, stepsPerRev)` = `(count mod 1600) × 360 / 1600`,
`fingerprint()` (every field that changes a dot; a plan made on another profile is stale), `validate(c)`, `copy()`.

### `PurgeRow` (`cfg.purgeRow`, I9)

`surfaceName "blank"`, `x0Mm`, `y0Mm` (null = not set), `pitchXMm 2.5`, `pitchYMm 0`, `spots 0`, `usedSpots 0`,
`resetAtMs`. Spot i is at `(x0 + i·pitchX, y0 + i·pitchY)`; its Z is the `surfaceName` plane there + offset + gap.
`isDefined()`, `spotsLeft()`, `spot(i)`. A board run uses the next `profile.purgeDots` free spots and marks them used
right before its first line is sent (an aborted purge leaves them dirty). The pitch must be >= 1.5 mm. The bench row:
y 237.5, x0 292, pitch 2.5 (x 292..327, before the tape gap at x 331–339).

### `CleanPadCheck` (`cfg.cleanPadCheck`, I10)

`enabled true`, `grayLevel 200`, `minBrightFraction 0.8`, `boxWMm 0.806`, `boxHMm 0.807`. Before the pressure goes on,
the top camera is centred on every distinct work dot; the share of pixels brighter than `grayLevel` (OpenCV
`COLOR_BGR2GRAY`) in a `boxWMm × boxHMm` box at the frame centre must be >= `minBrightFraction` (the box is the bench's
where the pad, or the dot's grid cell, is at least about that large, as on 0603 pads of 0.80 × 0.95 mm; on smaller pads
it is 0.05 mm inside the pad edges, so a clean 0402 or fine-pitch pad is not read as dirty). Bare copper reads
>= 0.90; paste left from an earlier run reads dark blue, and so does solder mask (a mis-registered board). This is
`tools/disp.py --require-clean` (`[504:577, 923:998]` of a 1920×1080 frame at 0.01074 × 0.01106 mm/px) in mm, computed
by `vision.PadCleanCheck` through `DotImager.brightFraction` (a default method; fakes can override it).

**Fixed controller commands** (`ControllerCommands`; not settings, schema 1 keys are ignored on load and refused by
`PUT /api/config`): pump `M106 P2 S255` / `M107 P2`, valve `M106 P3 S255` / `M107 P3`, left valve and pump OFF
`M107 P1` / `M107 P0` (off only: switching an output off is always safe), capability query `M115`
(`Cap:HOST_WATCHDOG:1`), watchdog `M990 S<keepaliveWindowMs>` / `M990` / `M990 S0`, and the Stop sequence
`STOP_SEQUENCE` = `stopSequence("B")` = `M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B` (the bench's F9, also the
machine.xml `DISABLE_COMMAND`). Every line the engine, its pings, its abort and the pressure hold send passes
`ControllerCommands.check()`: an allow-list of command words (`G0 G1 G4 G92 M18 M106 M107 M114 M115 M201 M203 M204
M400 M410 M990`), `M106` only as the two ON constants exactly, `M107` only as `P0`..`P3` exactly, no M42/M43 or other
output command. `GcodeStreamValidator` applies the same check.

Coordinates: X/Y are OpenPnP machine coordinates of the N2 needle tip (what `N2.moveTo()` takes); the stream goes to
X/Y minus `needleXyTrim`. Surface Z (places, board, surfaces) is in OpenPnP's frame: the N1 nozzle Z touching the
surface. Dispense Z (N2) = surface Z + `needleZOffsetMm` + `profile.gapMm`; on a board the surface Z of each dot is the
probed `board` plane at the dot, on the purge row the `blank` plane; hop Z = dispense Z + `hopHeightMm`. On the Lumen
(z2 = 63 − raw, K 65.59) the needle dispenses at raw Z = 65.49 − c for a surface at N1 contact c. B is degrees of the
auger (Marlin units).

## 3. Data (`<configDir>/instancelabs/dispense.db`, SQLite, every table append-only via triggers)

| Signature | Notes |
|---|---|
| `public DispenseDb getDb()` | Direct access; `DispenseDb.query(String sql, Object... args)` returns `List<Map<String,Object>>`. `recentRuns(int)`, `recentEvents(int)`. |
| `public List<PadType> getPadTypes() throws SQLException` | Current version of each type, creation order. Seeds (§5.7): chip-0402/0603/0805/1206, sod-123, sot-23, soic, tssop, qfp, qfn-dfn-perimeter, qfn-centre (k 0.5), custom; all `not-started`. |
| `public PadType savePadType(PadType p) throws SQLException` | Appends version n+1 (new key = new type, i.e. "New pad type"). Status: `not-started → provisional → characterized → validated`. |
| `public PadRule savePadRule(PadRule r) throws SQLException` | Per-type layout rule: `largestSingleDotPadMm2` and `maxDots` (a pad larger than that gets a grid, §4). |
| `public Calibration getCurrentCalibration() throws SQLException` | Scale records and dot-ladder rows hang off a calibration record (seed #1). The planner no longer uses it (no q model). |
| `public List<DotLadderRow> getDotLadder(long calibrationId) throws SQLException` | |
| `public Calibration recordScaleShots(ScaleCalibration.ShotsEntry e) throws SQLException` | Manual scale entry (§5.2): per-shot masses or total mass + count. |
| `public DotLadderRow recordScaleDots(ScaleCalibration.DotsEntry e) throws SQLException` | Dot batch on a coupon: mg/dot, SE = √2·s_scale/n, spherical-cap height. |

Tables: `pad_types`, `pad_rules`, `package_pad_type` (package → type overrides, `DispenseDb.mapPackage(String, String)`),
`calibration`, `dot_ladder`, `pad_plan`, `runs` (per-run timings, dots, `dph_motion`, `dph_overall`), `dot_log`
(per-dot host timestamps), `events` (also `PROFILE` per run, `I9` purge spots used / reset, `I10` clean checks, `I11`
start angle), `needle_touchoff`, `scale_entries`, view `pad_types_current`.

## 4. Planning

| Signature | Notes |
|---|---|
| `public DispensePlan planBoard(BoardLocation bl) throws Exception` | Pads from `packages.xml` footprint pads if present, else from the pad type layout. Uses `Utils2D.calculateBoardPlacementLocation`, i.e. the fiducial-corrected local→global transform when a fiducial check has set one. Bottom-up boards: `IllegalArgumentException`. Sets `plan.boardFingerprint`, stores `pad_plan` rows. |
| `public DispensePlan planBoardFile(File boardFile, Location boardLocation) throws Exception` | Loads a `.board.xml` and plans it top-side up at `boardLocation`. |
| `public DispensePlanner newPlanner() throws SQLException` | `new DispensePlanner(cfg, resolver).withRules(rules).withSurface(plane)`; `plan(String boardName, List<PadGeometry> pads, double startX, double startY)` for custom pad lists. |
| `public static List<GcodeStreamBuilder.DotSpec> purgeSpecs(DispenseConfig c)` | The next `profile.purgeDots` free purge spots as dot specs (index `PURGE_INDEX` = −3), or empty if the row cannot supply them (I9 refuses then). |
| `public InterlockReport checkInterlocks(DispensePlan plan, double estimatedPressurisedSeconds)` | Pre-flight only; `runPlan` re-checks with the program's own estimate. |

There is no dose model: the volumetric chain (q per revolution, ladder interpolation) was removed after the bench
showed the dose follows the turn, the pressure and the dwell. Every dot is one profile dot: `rotationDeg` =
`profile.netDeg` (180.1802° for 800 steps). `DotSizer.layout(w, h, area, rule)`: one dot per pad, or, for a pad larger
than its rule's `largestSingleDotPadMm2`, `ceil(area / largestSingleDotPadMm2)` dots (capped by `maxDots`, default 6)
on the grid that maximises the short side of a cell (`bestGrid`). Order: nearest neighbour from the start + 2-opt; the
start is the last purge spot of the next run (so the cross is short), else N2's XY.

`DispensePlan` carries `profile` (a copy) and `profileFingerprint`, `needleGapMm`, `needleZOffsetMm`, `hopHeightMm`,
`surfaceFingerprint`, `boardFingerprint`, `pads`, `dots`, `warnings`, path lengths. `PlannedDot`: `index`, `padId`,
`xMm`, `yMm`, `surfaceZMm`, `dispenseZMm`, `hopZMm`, `rotationDeg`. `PlannedPad`: geometry, type, area, target volume
and mass (informational), `dotCount`, `rotationPerDotDeg`, `flags`. Blocking flags: `NO_PAD_TYPE`, `STATUS_BELOW_MIN`
(§5.7 rule), `NO_GEOMETRY`.

## 5. Execution

| Signature | Notes |
|---|---|
| `public Future<RunResult> runBoard(BoardLocation bl) throws Exception` | `planBoard` + `runPlan`. |
| `public Future<RunResult> runPlan(DispensePlan plan) throws Exception` | Never completes exceptionally for dispense failures: inspect `RunResult.status` (`OK`, `ABORTED`, `ERROR`, `REFUSED`), `message`, `interlocks`. |
| `public Future<PadCleanReport> checkCleanPads(DispensePlan plan) throws Exception` | The clean-pad guard alone (camera only, pressure off), so the operator can wipe before a run. A run repeats it. |
| `public Future<RunResult> runShots(String locationName, double rotationDeg, int count, boolean referenceSurface) throws Exception` | Manual prime / test dot at the waste place (`referenceSurface` true: the place is checked against the needle frame) and the scale workflow (shots into a cup: only the floor). Full I4. |
| `public void abort(String reason)` | Thread-safe. The stream stops before its next line and sends the Stop sequence (I1). Also triggered by OpenPnP Stop/disable. |
| `public boolean isBusy()`, `public RunResult getActiveRun()`, `public RunResult getLastRun()` | |
| `public void addListener(DispenseListener l)` / `removeListener` | `onPhase(RunResult, String)`, `onDot(RunResult, RunResult.DotTiming, int done, int total)` (work dots only), `onFinished(RunResult)`; called on the machine thread. |
| `public void setPressureSensor(BarrelPressureSensor s)` | I5 hook (`Double readPsi()`); no sensor fitted yet. |
| `public void setDotImager(DotImager imager)` | Override the camera/vision (default: top camera + `CameraCapture`/`DotInspector`; `brightFraction` for I10). |

What `runPlan` does, in order:
1. Refuses (REFUSED, nothing sent, before the session opens) if the profile's fingerprint differs from the plan's: plan
   the board again.
2. Waits for OpenPnP motion to complete; sends `M115` (the host-watchdog keepalive is used only if a response line
   contains `Cap:HOST_WATCHDOG:1`; a pending controller error refuses the run, un-zeroes B and un-homes the machine).
3. Interlocks (I3, I4 with the board and purge surfaces, I5, I6 note, I9, I10, I11, plan rules), then builds the
   program once with a B count of 0 for the estimate and checks again with its pressurised time (I7).
4. With `cleanPadCheck.enabled`: the top camera at every distinct work dot, pressure off. Any dirty or off-pad target
   refuses the run with the I10 list (`RunResult.cleanCheck`).
5. Waits for motion again, reads the B step count: `M400`, `M114`, `parseBCount` (`Count ... B:<n>`). OpenPnP's
   simulator does not report it: on a loopback TCP link the count is taken as 0 with the run warning "simulated
   controller (no M114 Count): B step count taken as 0 for the start angle". On a real link without a count a
   partial-turn profile is refused (I11, nothing sent).
6. Builds the session through `OpenPnpRawMapper` (= `nozzle.toRaw(nozzle.toHeadLocation(loc))`, soft limits via
   `MotionPlanner.isValidLocation`) and `BacklashEmulator` (reproduces `AbstractMotionPlanner.createBacklashCompensatedMotion`),
   validates it with `GcodeStreamValidator`, marks the purge spots used, then streams it line by line via
   `GcodeDriver.sendCommand()` (the driver's own flow control/error regex).
7. Re-syncs OpenPnP: X/Y/Z planned and driver coordinates, the motion planner's directional-backlash state, B = 0.
8. Stores `runs` + `dot_log`, fires `onFinished`. `RunResult.purgeDots`, `bCountAtStart`, `workStartAngleDeg`.

One pressure session per board (`GcodeStreamBuilder.forPlan(cfg, mapper, plan, List<DotSpec> purge, boolean bZeroed,
boolean keepalive, Long bCount)`):
```
G92 B0                 ; only if B not zeroed since home/reconnect (I2)
M201 B<screwAccelDegS2> / M203 B<bMaxFeedDegS> / M204 S<travelAccelMmS2>
M990 S3000             ; only with the capability; then M990 every keepaliveIntervalMs, long G4s split
G1 Z<travel> / G0 X.. Y..   ; to the first purge spot at travelSafeZ (+ OpenPnP's backlash approach moves)
M106 P2 S255 / G4 P<pumpToValveSettleMs> / M106 P3 S255 / G4 P<valveSettleMs>
<purge dots>           ; the per-dot block on the purge row; the first one sets the phase (below)
G1 Z<travel> / G0 X.. Y..   ; cross to the first work dot: no M400, no G4 (a pause under pressure oozes paste)
<work dots>
M400 / M107 P3 / M107 P2 / M990 S0 / G92 B0 / G1 Z<travelSafeZ> / M400
```
Per dot (purge and work alike):
```
G0 X.. Y..             ; at the current (hop) height
G1 Z<dispense> F<zDownFeedMmS·60>
M204 S<screwAccelDegS2>                  ; 20000: only the B moves run under it
G1 B<rest + net + suck> F<screwRpm·360>  ; forward: 844 steps = 190.0901°
G4 P<dwellMs>
G1 B<rest + net> F<screwRpm·360>         ; suck-back: 44 steps
M204 S<zLiftAccelMmS2>                   ; 2000 (M204 S also caps B, and governs X/Y/Z)
G1 Z<hop> F<zLiftFeedMmS·60>
```
B targets are absolute from the session's `G92 B0`: origin + steps / `bStepsPerDeg`, printed `%.4f`, always whole
motor steps (Marlin rounds each absolute target to steps), so the screw rests exactly `netStepsPerDot` further after
every dot and the start angle repeats (298, 118, 298, … for half turns). `dot(spec)` rounds `rotationDeg × 4.44` to
steps; `dot(spec, netSteps)` takes steps.

**Phase lock.** Screw angle = (Marlin step count of B mod 1600) × 360 / 1600. The step count is counted since
controller power-up and is not reset by `G92 B` (Marlin workspace offset; to be confirmed on the Lumen firmware, see
`REAL_MACHINE_TODO.md`), so the start angle is a calibration of the current power-up (`startAngleSetAtMs` after
`controllerConnectedAtMs`, I11). The first purge dot turns `profile.phaseSteps(c, restCount, purgeDots − 1)` steps:
the turn that, after the remaining purge dots, leaves the screw at `startAngleSteps` for the first work dot (at least
20°, at most one revolution more); the remaining purge dots flush that odd-sized dot. Example (count 0, 298°, 8
purge dots): first purge dot 524 steps (forward 568), then 7 × 800, first work dot at count 6124 = 297.9°.
`GcodeProgram` reports `purgeDotCount`, `bCountAtStart`, `workStartCount`, `workStartAngleDeg`.

**Stop / abort / error (I1).** `abortLines` = `ControllerCommands.stopSequence(B)` (`M410, M107 P3, M107 P2, M107 P1,
M107 P0, M18 B`), then `M990 S0` if armed, each through `GcodeChannel.sendCritical` (a controller error that is still
pending makes GcodeDriver refuse a line without sending it; critical lines are sent again). Then `M400`,
`M204 S<travelAccelMmS2>` (a stop between the screw's `M204 S` and the restore leaves 20000 in force), `M114` read-back,
OpenPnP re-synced to the reported position, machine marked un-homed (M410 may lose steps), B marked not zeroed. The
abort flag is checked again right before every line (also after a keepalive ping), and OpenPnP's Stop waits up to
50 ms for a line that already passed its check to be written, so no engine motion line follows the driver's
`DISABLE_COMMAND`. No `M410` at a normal session end: after `M400` nothing is left to stop, and stock Marlin holds
`M410`'s `ok` for about 1 s (planner clean-up), which kept the valve open 1 s after the last dot.

`GcodeStreamValidator` refuses a program with: a line `ControllerCommands.check()` refuses; valve ON before pump ON or
pump OFF while the valve is ON; a B move before `G92 B0` (I2) or outside a pressure session; any G0/G1 with a non-B axis
word while the last `M204 S` exceeds `max(travelAccelMmS2, zLiftAccelMmS2)` ("X/Y/Z move under the screw
acceleration"); a B feed above `bMaxFeedDegS × 60`; an `M400` inside a dot; a raw Z outside the window between the
I4 needle floor and the travel Z; pneumatics ON or the watchdog armed at the end.

`GcodeProgram.estimatedSeconds` / `estimatedPressurisedSeconds`: moves as trapezoids at the M204 in force (B at
`min(M204 S, M201 B)`), dwells; no look-ahead or host latency. `estimatedWorkSeconds`: from the cross after the purge
to the last dot's lift, the window the bench's DPH is timed over (web UI: "dots only"; the whole program is
"overall"). Against the bench's p18_board1 the move model is about 3 % slow.

`RunResult`: `runId`, `kind` (`board`/`shots`/`dot-ladder`/`touch-off`/`clean-check`), `status`, `message`,
`startedAtMs`, `endedAtMs`, `dotsPlanned`, `dotsDone` (work dots), `firstDotStartMs`, `lastDotEndMs`, `dotTimings`
(`dotIndex`, `padId`, `rotationDeg`, `startMs`, `endMs`; work dots), `keepaliveActive`, `keepalivePings`, `linesSent`,
`streamedLines`, `warnings`, `interlocks`, `profile` (the copy used), `cleanCheck` (`PadCleanReport`), `purgeDots`,
`bCountAtStart`, `workStartAngleDeg`, `Double dphMotion()` = dots / (lastDotEnd − firstDotStart) · 3600,
`Double dphOverall()` = dots / (end − start) · 3600. Timestamps are host-side: a dot's `endMs` is when its last line
was acknowledged (queued in Marlin, not executed); `lastDotEndMs` is the completion of the `M400` that follows the
last dot (true completion).

`PadCleanReport`: `planId`, `ok`, `grayLevel`, `minBrightFraction`, `boxWMm`, `boxHMm`, `checkedAtMs`, `pads`
(`padId`, `dotIndex`, `xMm`, `yMm`, `brightFraction`, `clean`, `error`, `boxWMm`, `boxHMm` used at that dot),
`dirtyPadIds()`, `refusal()`, `isComplete()` (`checkedAtMs > 0`: false for a check that was stopped or failed).

## 6. Calibration

| Signature | Notes |
|---|---|
| `public Future<DotLadderCalibration.Result> runDotLadder(DotLadderCalibration.Request req) throws Exception` | Request: `rotationsDeg` (default 30/45/58/75/90), `repeats` 5, `pitchMm` 1.5, `locationName` (default blank), `primeAtWaste`. Every dot at the probed blank plane under it + offset + gap (the profile's suck-back, dwell and screw feed). Before images (pressure off) → one pressure session → after images → `DotInspector` → one `dot_ladder` row per rotation on the current calibration record. Requires I4 (incl. the blank surface). |
| `public Future<DispenseService.TouchOffResult> runNeedleTouchOff(NeedleTouchOff.Request req, boolean apply) throws Exception` | §6.1 option 1. Gaps `startGapMm` 0.125 down by `stepMm` 0.025, `steps` 6, relative to the current offset, each above the probed blank plane at that dot; `rotationDeg` NaN (default) = one profile dot. Contact = midpoint between the last normal dot and the first squashed (`> (1+jumpRatio)·reference`) or missing one; new offset = old + contact. `apply` writes it (sets `needleZOffsetSetAtMs`, clears I4). The only I4 waiver (named): it needs the needle frame and the blank surface; while not touched off its prime is `touchOffPrimeExtraClearanceMm` higher. |
| `recordScaleShots` / `recordScaleDots` | See §3. |

Pure helpers usable without a machine: `DispenseProfile.*` (`phaseSteps`, `startAngles`, `angleOfCount`, `validate`),
`NeedleTouchOff.analyze(double[] gapsMm, Double[] diametersMm, double jumpRatio)`, `DotLadderCalibration.analyze(...)`,
`DotSizer.layout(...)`, `DotMath.*`, `DispenseService.parseBCount(List<String>, String)` (package-private),
`GcodeStreamBuilder.forPlan(...)` / `forDots(...)` / `abortLines(...)`, `vision.PadCleanCheck.measure(...)`.

## 7. Interlocks (§5.5)

| Id | Where | Behaviour |
|---|---|---|
| I1 | `DispenseExecutor.shutdown` | The Stop sequence (F9) on any error, abort, I7 trip, OpenPnP Stop/disable (machine listener). Each line attempted, through `sendCritical`. The web status reports `stopCommandCheck`: OpenPnP's own Stop is OK only if the driver's `DISABLE_COMMAND` contains the exact contiguous F9 sequence. |
| I2 | `GcodeStreamBuilder` + `GcodeStreamValidator` | No B move before `G92 B0`; flag cleared on home, enable, disable, abort. |
| I3 | `Interlocks` | Refuses if N2 has compatible nozzle tips or a loaded tip (names reported). |
| I4 | `Interlocks` (+ builder floor, validator Z window) | Refuses without a needle Z frame recorded since the last needle change; unless touched off since the change and the frame (named waiver: the touch-off run); if the offset is more than `needleOffsetMaxDriftMm` from the frame's; if a waste/blank place Z or any surface point is more than `zFrameToleranceMm` from the frame surface; board / ladder runs: without the probed surface, with a plane residual above `surfaceMaxResidualMm`, with a dot more than `surfaceMarginMm` outside the probed hull, (board) a plan made on another surface map, or a board surface probed on another board registration (`boardFingerprint`). The purge row's surface gets the same checks for the purge spots (`Scope.moreSurfaces`). Hard floor: no dot below frame surface − tolerance + offset − 0.1. |
| I5 | `Interlocks` | Pressure window via `BarrelPressureSensor`; disabled by default. |
| I6 | `Interlocks` (note) | The regulator is set by hand to `profile.pressurePsi` (18); reported with every check. |
| I7 | `Interlocks` (estimate) + `DispenseExecutor` (runtime) | Max continuous pump+valve on-time. |
| I9 | `Interlocks` (board runs) | Purge row defined and at least `profile.purgeDots` free spots. |
| I10 | `Interlocks` + `runPlan` (board runs) | With `cleanPadCheck.enabled`: a top camera must be available, and the clean-pad check must pass right before the pressure goes on. |
| I11 | `Interlocks` (board runs, partial-turn profile) | `profile.startAngleSetAtMs` must exist and be >= `controllerConnectedAtMs` (set or confirm after every connect); `runPlan` also refuses when the controller does not report the B step count. |

## 8. Known limits (measured or read, not guessed)

- Marlin `G4` calls `planner.synchronize()` (`Marlin/src/gcode/motion/G4.cpp:36` on `il/lumen-rev05`), so the per-dot
  dwell drains the motion queue once per dot (the bench recipe wants that dwell).
- Marlin accelerates a B-only move at the current `M204 S` (`M201 B` is only a cap), hence the `M204 S20000` around
  the B moves; the validator keeps X/Y/Z out from under it.
- `M201`/`M203` for B persist in Marlin RAM until reset (not saved without `M500`).
- `M990` semantics are the shared contract; the firmware side is on `il/safety`, which is not flashed. Pings are sent
  between lines, so a single command that blocks longer than the watchdog window delays the next ping.
- OpenPnP's simulated controller (GcodeServer) does not report `Count` in `M114` (the engine takes 0, with a warning)
  and times B with machine.xml's B axis limits (100 °/s, 500 °/s²); the sim acceptance raises them in its copy.
- In the simulator the top camera is a static image: the clean-pad guard and dot vision cannot pass there.
- `honorBacklash` with this machine's X/Y `OneSidedPositioning` makes every XY move end with OpenPnP's backlash
  approach (a second, half-speed 0.1 mm move): about 10 ms per dot that `tools/disp.py` does not spend
  (`src/test/sim/il_sim_acceptance.py` DPH what-ifs: 1.5 s per 160-dot board).
- No HTTP endpoints here; this is the Java service the web layer calls (`docs/instancelabs/WEB_API.md`; the bench
  recipe added `POST /api/plan/{id}/clean-check`, `POST /api/screw/start-angle`, `POST /api/purge-row/reset`).
