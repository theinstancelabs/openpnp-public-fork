# Instance Labs dispense engine — Java API (`org.openpnp.instancelabs.dispense`)

Branch `il/bench-sync` (engine commit `271d4738f`: the bench recipe of 2026-10-28, `runs/p18_board1`: 160/160 pads,
0.714 mm², CV 13 %, 5,490 DPH). Spec: `docs/plan-2026-10-27-evidence-backed-roadmap.md` §4.4–4.6, §5.1–5.7, §6.1 (in
the openpnp-revamp project), changed by the bench recipe where this file says so. This file is the contract for the
web API layer (`docs/instancelabs/WEB_API.md`). Every machine action runs on OpenPnP's machine thread and returns a
`java.util.concurrent.Future`; only one dispense task runs at a time (`IllegalStateException("a dispense task is
already running")` otherwise).

Updated on `il/bench-sync-autoreg` (b81d73d21, 2026-10-29) for Register board, the N1 vacuum probe, home at job start
and the Z check (`AUTO_REGISTER_DESIGN.md`, marked *autoreg*), the board frame and drawing in plans
(`BOARD_IMPORT_DESIGN.md` plumbing, *board import*), and per-pad-type doses and the pad calibration
(`PROFILE_CALIBRATION_DESIGN.md`, "PC", marked *profile*: the pure model `org.openpnp.instancelabs.padprofile`, the dose
stream, `runDoseBlocks`, dose records and `applyPadCalibration`; the job that drives them is in the API layer,
`WEB_API.md` §3.5). Written from the code on `il/pc-integration`; where it differs from PC §5–§7 this file follows the
code. The test-board ladder (PC §20 decision 8, slice `pc-ladder`) is not in this PR: a follow-up. Updated on `il/rc1`
for the final fix round (E1 engine safety, E2 engine correctness): the pressure hold looked at again on the machine
thread by every task, a home that a STOP or a failure ends, the Z-check remedy under the same home, the blank's old
points kept as a Z reference, the probe window against the board top, the stream's pad index, whole-pad test runs,
the dose rule, the fiducial search and fit, and mass per dot laid. Updated on `il/tip-setups` for tip setups (PC §22,
marked *tips*, §9 below): a library of dispenser-and-needle setups with one mounted, pad profiles and the dispense
profile per setup, mounting as a needle change, where each needle lands, pictures and "calibrated for" labels.

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
| `public HomeTracker getHomeTracker()` · `public HomeTracker.Plan homePlan()` | *autoreg.* Whether a task must home first under `homeBeforeRun` and why (`whyCode` `always`, `home-unconfirmed`, `stopped`, `not-homed`, `homed`), the last home, and the estimate (the last home the engine timed, in memory; 25 s before). Every engine or API home ends with `HomeTracker.homeEnded()`, also one a STOP or a failure ended: no start time stays behind, so the next clean home (OpenPnP's own Home button) is `homed`, not `home-unconfirmed`. |
| `public boolean isSimulatorConfirmed()` | *autoreg.* The controller answered as OpenPnP's simulator (`SimGuard`, or this service's `M115` at connect). Simulated registrations and surfaces are refused on a machine that is not. |
| `public boolean hasTopCamera()` · `public Nozzle getProbeNozzle()` · `public double[] probeNozzleZ()` | *autoreg.* The top camera Register board needs; N1 (`PROBE_NOZZLE`); N1's raw Z and whether a STOP left it below the safe zone (`{z, low}`). |

## 2. Configuration (`<configDir>/instancelabs/dispense-config.json`, never machine.xml)

| Signature | Notes |
|---|---|
| `public DispenseConfig getConfig()` | A copy (Gson-serialisable POJO, public fields). |
| `public synchronized void updateConfig(DispenseConfig newCfg) throws IOException` | Validates (`DispenseConfig.validate()`, which includes `profile.validate(cfg)`), writes atomically, applies. `IllegalArgumentException` on invalid input. A changed `profile.startAngleDeg` stamps `profile.startAngleSetAtMs`; a changed `needleXyTrim` stamps `needleXyTrim.setAtMs`. |
| `public synchronized DispenseConfig updateConfigAtomically(ConfigEdit edit) throws Exception` | *tips.* `ConfigEdit.apply(DispenseConfig live)` gets a copy of the **live** config and returns the new one, which is validated and written under the same lock (`PUT /api/config`): nothing a mount, a needle change, a run's purge reservation or another save wrote meanwhile is put back. |
| `public synchronized DispenseProfile setStartAngle(Double angleDeg, String note) throws IOException` | I11: sets (`angleDeg` != null) or confirms (null) the screw start angle for the current controller power-up; stamps `startAngleSetAtMs`, logs an `I11` event. |
| `public synchronized void resetPurgeRow() throws IOException` | The purge row was wiped: `usedSpots = 0`, `resetAtMs = now` (I9). |
| `public synchronized void setLocation(String name, double x, double y, double z) throws IOException` | Named places (`waste`, `blank`, calibration cup, …): x/y in N2 machine coordinates, `z` = surface Z in OpenPnP's frame (the N1 nozzle Z touching that surface). |
| `public synchronized NeedleChange recordNeedleChange(String why, String operator) throws IOException, SQLException` | *tips* (replaces `markNeedleChanged`). I4: a fact, never refused: the needle Z frame, the offset and where it lands are to do again (§9). |
| `public synchronized NeedleFrameResult setNeedleAnchor(double surfaceZMm, double needleContactZMm, Double x, Double y, Double rawSumMm, Long needleChangedAtMs)` | I4 needle Z frame: the same surface touched by N1 (`surfaceZMm`) and by the needle tip (`needleContactZMm`, N2 Z); offset := difference. `rawSumMm` records the bench relation K the contact came from (or null). Not a touch-off. *Tips:* `needleChangedAtMs` is the needle token the heights were taken under (`EngineRefusal` 409 `needle-changed` when the needle was changed since); the result carries the frame, the offset, `question` (the frame more than `needleOffsetMaxDriftMm` from the setup's previous one: `… touches 1.20 mm higher than when it was on on 10-29: is the … on the dispenser?`) and `warning` (I4's offset rule). |
| `public String needleFrameFromKRefusal()` | *rc1.* Why the frame cannot be recorded from K now (a failed Z check of this home, or a needle change since the frame), else null: the API refuses `needleContactRawSum` with it. |
| `public double needleContactZFromRawSum(double n1ContactZ, double rawSumMm) throws Exception` | Needle contact (N2 Z, OpenPnP frame) from the bench relation "needle contact raw Z = K − N1 contact Z" (side camera, 2026-10-27: K = 65.59). On the Lumen see-saw (z2 = 63 − raw) that is N1 − 2.59: N1 5.5 → needle 2.91, offset −2.59. |
| `public synchronized NeedleFrameResult setNeedleZOffset(double offsetMm, String method, String details, Long needleChangedAtMs) throws IOException, SQLException` | Manual offset entry or touch-off result; appends a `needle_touchoff` row. Must stay within `needleOffsetMaxDriftMm` of the frame's offset (I4). *Tips:* the needle token as `setNeedleAnchor`. |
| `public synchronized SurfacePlane setSurface(String name, List<SurfacePoint> points, String note[, String boardFingerprint[, Boolean simulated]])` / `clearSurface(name)` / `surfacePlane(name)` | Probed surfaces (N1 contact heights, >= 3 points spanning an area; least-squares plane + convex hull). `board`: per-dot Z of board runs; `boardFingerprint` ties it to the board registration it was probed on (I4 asks for new probes after a re-seat). `blank`: the purge row, dot ladder and touch-off. |
| `public static String boardFingerprint(BoardLocation bl)` | Global location (incl. Z), side, fiducial transform and every placement, hashed: plan staleness at Run. |
| `public static String boardRegistration(BoardLocation bl)` | Board X/Y/rotation, side and fiducial transform only, hashed (`plan.boardRegistration`). A board surface is tied to it: I4 refuses an untied board surface and one probed on another registration (re-seat); editing placements or the nominal board Z does not change it. |
| `public boolean isBZeroed()` | I2 state: `G92 B0` sent since the last home / enable / disable. |
| `public synchronized Long setNewSeat(boolean newSeat)` | *autoreg.* The new-seat flag (`newSeatAtMs`, null when cleared): the next registration is a new seat. Register board clears it when it saves a probed surface. |
| `public static String boardZProblem(DispenseConfig c, double boardZ)` | *autoreg.* Why a board top Z cannot be an N1 contact height (more than `MAX_BOARD_Z_FROM_ANCHOR_MM` 2.0 from the needle-frame surface), or null. `planBoard` refuses with it. |

### `DispenseConfig` (schema 4)

`schemaVersion 4`. Schema 1/2 files load: removed keys are ignored, and `migrate()` brings the motion values to the
bench recipe (`travelAccelMmS2` and `zLiftAccelMmS2` 2000, `zLiftFeedMmS` >= 50, `bMaxFeedDegS` >= 2400,
`surfaceMaxResidualMm` <= 0.04, `surfaceMarginMm` >= 3.5, `zFrameToleranceMm` <= 1.2, `primeRotationDeg` 360 → 360.36).
*Tips:* `migrate()` is stepwise (`schemaVersion < 3`: the block above; `< 4`: nothing changes), so a schema-3 file
keeps its tuned motion values. A file from before tip setups (no `parkedTips` key: `isBeforeTipSetups`, which also
catches a file an older jar saved over) is first copied to `dispense-config.json.v3-<yyyyMMdd-HHmmss>.bak` at the first
save (an older jar, when the machine is bisected, drops `parkedTips` and `needleLanding`), and the start's reconcile
(§9) makes the setups agree with it.
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
- needle XY: `needleXyTrim {dxMm 0, dyMm 0, setAtMs null, note null, headOffsetXMm, headOffsetYMm}` (within ±1 mm;
  *tips:* stamped with N2's head offset in OpenPnP when set) and *tips* `needleLanding {dxMm, dyMm, setAtMs, jobId,
  photoId, headOffsetXMm, headOffsetYMm, note}` or null (each axis within `MAX_LANDING_MM` 15, set only by a landing
  check or Keep): the needle lands at camera + N2 head offset + landing + trim, so every dispensed target is moved by
  minus (landing + trim);
- *tips:* `parkedTips {setupKey: {profile, needleAnchor, needleZOffsetMm, needleZOffsetSetAtMs, needleZOffsetHomeAtMs,
  needleXyTrim, needleLanding, parkedAtMs, profileFrom}}`: what every setup that is not mounted keeps (a new setup's
  entry: a copy of a recipe, no frame, the mounted offset as a placeholder with both times null, a zero trim noted
  `new tip`, no landing);
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
| `netStepsPerDot` | 800 | Net screw turn per dot in motor steps (half a revolution). Must be 400, 800 or 1600 (a quarter, half or whole turn of the 1600 steps of a revolution): the start angle repeats and a purge dot (the profile's dose) turns at most one revolution plus the 20° phase turn, the stream validator's cap. Anything else is a validation problem: `profile netStepsPerDot must be 400, 800 or 1600 steps (a quarter, half or whole turn of the 1600 steps of a revolution): the start angle repeats and a purge dot turns at most one revolution` (`PUT /api/config` 400, the `CONFIG` interlock for a stored profile). |
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

### Registration, probe and homing (*autoreg*, `AUTO_REGISTER_DESIGN.md` §6.1)

- `homeBeforeRun "whenNeeded"` (or `"always"`): Register board, the blank probe and a board run home first when
  needed;
- `fiducialCheck {minFiducials 2 (2..10), searchRadiusMm 8.0 (1..12, at most 9 camera frames at the default camera
  size), seatToleranceMm 0.10 (0.01..1)}`: the radius bounds a fiducial's distance from its expected position (the
  board's last registration, else its nominal seat); the search frames tile that circle around the expected position
  (at most 9, the one nearest the prediction first), and a fit is compared with the board's last registration in the
  machine frame (the symmetric stretch of the polar decomposition, `FiducialLocator.machineFrame`), so a correct
  re-seat at another angle is not refused for the machine's own X/Y anisotropy;
- `vacuumProbe {startAboveMm 2.5, floorBelowMm 1.5, openAirMinCounts −1900000, openAirMaxCounts −1200000,
  alternates 4 (0..4), padKeepOutMm 1.4, vacuumSettleMs 1500, readDelayMs 70, boardSpots {}}` (`boardSpots`: board
  file name → 3..9 proven `[bx, by]` spots). Off the simulator a probe task refuses `vacuumSettleMs` below 1000 and
  `readDelayMs` below 65;
- engine-stamped (protected): `boardRegistrations` (the latest `BoardRegistration` per board key `file:<path>` or
  `job:<boardLocationId>`, at most 20, oldest dropped), `newSeatAtMs`;
- Z frames: `needleAnchor.homeAtMs`, `needleZOffsetHomeAtMs` (protected) and every surface's `homeAtMs`: the home each
  datum was measured under (null = unknown, saved before this). A surface also records `simulated`.
- `needleFloorZMm(Double surfaceReferenceZMm)`: the needle floor from the lower of the anchor surface and the
  surface's own reference (a board surface's board top Z), minus `zFrameToleranceMm`, plus the offset, minus 0.1.
  Purge dots keep the anchor's floor.

### Tip, paste and pad calibration (*profile*, `PROFILE_CALIBRATION_DESIGN.md` §5.4)

- `tip {key "ad22tta-b", name "22G bent taper", idMm 0.41, odMm 0.72, minDotSteps 400, setupVersion, model, dispenser,
  form, bendDeg, bendToward}`: *tips:* the engine's view of the **mounted** tip setup (engine-stamped, protected in
  `PUT /api/config`; the start's reconcile refreshes it from the setup, §9); pad types and dose records name its key;
  the OD (an assumption until measured) sets the same-pad dot pitch; a dose below `minDotSteps` is not allowed;
  `profile` is the mounted setup's recipe;
- `paste {key "gc50-t5", name "Loctite GC50 SAC305 T5", lot, loadedAtMs}`;
- `calibrationArea {surfaceName "blank", x0Mm, y0Mm, widthMm, heightMm, usedMm, resetAtMs}` (unset: 40 × 20 mm at the
  blank place);
- `padCalibration {maxResidCv 0.13, maxHalfSplit 0.13, maxQuarterAmplitude 0.20, massTolLow 0.25, massTolHigh 0.10,
  confirmTolerance 0.05, controlTolerance 0.15, weighTargetMg 70, doseRecordMaxAgeH 8, readingConfirmAfterMin 15,
  couponPlaces ["coupon1", "coupon2"], couponSizeMm [40, 30], couponMarginMm 3, couponWindowMm 0.3, flushDots 4,
  ladderDotsPerPhase 12, minLadderDots 28, minHops 60, threadRateOk 0.03, missRateOk 0.005}`;
- `profile.sustainedFeedMgS 2.6` (the bench saw 2.56 mg/s at 18 psi); `profile.doseFingerprint()`,
  `DispenseProfile.fingerprintDiff(was, now)` (one text for "what changed"), `phaseSteps(c, rest, dotsAfter,
  stepsPerDot)` for a block of another dose.
- `PadCalibrationSettings.realMachineProblems()`: the shortened statistics a SIM test may set (`ladderDotsPerPhase`
  below 12, `minLadderDots` below 28, `minHops` below 60, `weighTargetMg` below 50), each as `padCalibration.minHops
  below 60 is allowed in the simulator only`; `PUT /api/config` refuses them off the simulator and a pad calibration
  refuses to start there while one is stored.

**Fixed controller commands** (`ControllerCommands`; not settings, schema 1 keys are ignored on load and refused by
`PUT /api/config`): pump `M106 P2 S255` / `M107 P2`, valve `M106 P3 S255` / `M107 P3`, left valve and pump OFF
`M107 P1` / `M107 P0` (off only: switching an output off is always safe), capability query `M115`
(`Cap:HOST_WATCHDOG:1`), watchdog `M990 S<keepaliveWindowMs>` / `M990` / `M990 S0`, and the Stop sequence
`STOP_SEQUENCE` = `stopSequence("B")` = `M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B` (the bench's F9, also the
machine.xml `DISABLE_COMMAND`). Every line the engine, its pings, its abort and the pressure hold send passes
`ControllerCommands.check()`: an allow-list of command words (`G0 G1 G4 G92 M18 M106 M107 M114 M115 M201 M203 M204
M400 M410 M990`), `M106` only as the two ON constants exactly, `M107` only as `P0`..`P3` exactly, no M42/M43 or other
output command. `GcodeStreamValidator` applies the same check, and refuses the probe's lines in a dispense program.
*autoreg:* `ControllerCommands.checkProbe()` is the N1 probe's gate: `check()`, or one of its exact lines (N1 pump
`M106`, N1 valve `M106 P1 S180`, the VAC1 sensor set-up and select `M260 …`, the read `M261 A109 B3 S1`). The probe's
I/O goes through `DispenseExecutor`'s gate (the abort flag checked before every line).

Coordinates: X/Y are OpenPnP machine coordinates of the N2 needle tip (what `N2.moveTo()` takes); the stream goes to X/Y
minus (`needleLanding` + `needleXyTrim`) (*tips*; `landingInEffect()`). Surface Z (places, board, surfaces) is in
OpenPnP's frame: the N1 nozzle Z touching the surface. Dispense Z (N2) = surface Z + `needleZOffsetMm` +
`profile.gapMm`; on a board the surface Z of each dot is the probed `board` plane at the dot, on the purge row the
`blank` plane; hop Z = dispense Z + `hopHeightMm`. On the Lumen (z2 = 63 − raw, K 65.59) the needle dispenses at raw Z =
65.49 − c for a surface at N1 contact c. B is degrees of the auger (Marlin units).

## 3. Data (`<configDir>/instancelabs/dispense.db`, SQLite, every table append-only via triggers)

| Signature | Notes |
|---|---|
| `public DispenseDb getDb()` | Direct access; `DispenseDb.query(String sql, Object... args)` returns `List<Map<String,Object>>`. `recentRuns(int)`, `recentEvents(int)`. |
| `public List<PadType> getPadTypes() throws SQLException` | Current version of each type, creation order. Seeds (§5.7): chip-0402/0603/0805/1206, sod-123, sot-23, soic, tssop, qfp, qfn-dfn-perimeter, qfn-centre (k 0.5), custom; all `not-started`. *Tips:* the mounted setup's composed view (`TipSetups.typesFor`, §9). |
| `public synchronized PadType savePadType(PadType v, String forSetup, Integer padVersion) throws SQLException` | Appends version n+1 (new key = new type, i.e. "New pad type"). Status: `not-started → provisional → characterized → validated`. *Tips:* `forSetup` must be the mounted setup (409 `tip-changed`); a pad edit names the `padVersion` it showed (409 `pad-changed`), a dose write (null) takes the pad fields from the composed view under the lock. `savePadTypeByHand(v, forSetup, padVersion, label, photoIds, operator)`: a status raised to characterized or validated, with its `by-hand` calibration record, label and pictures. |
| `public PadRule savePadRule(PadRule r) throws SQLException` | Per-type layout rule: `largestSingleDotPadMm2` and `maxDots` (a pad larger than that gets a grid, §4). |
| `public Calibration getCurrentCalibration() throws SQLException` | Scale records and dot-ladder rows hang off a calibration record (seed #1). The planner no longer uses it (no q model). |
| `public List<DotLadderRow> getDotLadder(long calibrationId) throws SQLException` | |
| `public Calibration recordScaleShots(ScaleCalibration.ShotsEntry e) throws SQLException` | Manual scale entry (§5.2): per-shot masses or total mass + count. |
| `public DotLadderRow recordScaleDots(ScaleCalibration.DotsEntry e) throws SQLException` | Dot batch on a coupon: mg/dot, SE = √2·s_scale/n, spherical-cap height. |

Tables: `pad_types`, `pad_rules`, `package_pad_type` (package → type overrides, `DispenseDb.mapPackage(String, String)`),
`calibration`, `dot_ladder`, `pad_plan`, `runs` (per-run timings, dots, `dph_motion`, `dph_overall`), `dot_log`
(per-dot host timestamps), `events` (also `PROFILE` per run, `I9` purge spots used / reset, `I10` clean checks, `I11`
start angle; *autoreg:* `REG` with each registration's JSON, `PROBE` with each probe's points and looks, `ZCHECK`),
`needle_touchoff`, `scale_entries`, view `pad_types_current`. `runs.kind` adds `board-registration`, `surface-probe`
and `z-check`.

*Profile:* `dispense.db` schema 2 (`SCHEMA_VERSION`, table `schema_info`). A schema-1 file is first copied next to
itself (`dispense.db.v1-<time>.bak`), then one transaction adds the nullable columns `pad_types.dot_steps`,
`dots_per_pad`, `tip_key`, `dose_fp`, `cal_area_mm2`, `mass_per_pad_mg`, `origin`, `package_pad_type.pad_names` (a
mapping of some pads of a package: `mapPackage(String, Collection<String>, String)`), `runs.skipped_pads`, and the
append-only tables `dose_records` (optical, coupon and mass records of a dose), `pad_calibrations` (one row per
calibrated type) and `package_options` (decision 4's `paste_partial`: `setPastePartial`, `pastePartialPackages`). A
second open is a no-op. Dose records: `insertDoseRecord`, `doseRecord(id)`, `doseRecords(steps, sinceMs)`,
`pendingCoupons(sinceMs)` (coupon records without a mass row yet); `insertPadCalibration`, `padCalibrations(typeKey,
limit)`. `runs.kind` adds `pad-ladder` and `pad-weigh`; `events` add `PCAL` (each calibration, the area used, each
job's end).

*Tips:* `dispense.db` schema 3 (§9): a `.v2-<time>.bak` copy before the first step that runs, then one transaction
each: `to2()`, then `to3()`, which drops and recreates `pad_types_current` and the new view `pad_types_by_setup`
together, adds the nullable columns `pad_calibrations.setup_key`, `method` (`auto` | `by-hand`; null = `auto`),
`needle_since_ms`, `dose_records.landing_dx_mm`, `landing_dy_mm` and `runs.setup_key`, the append-only tables
`tip_setups`, `tip_mounts`, `photos`, `photo_links`, `photo_events`, `cal_labels`, `cal_flags`, the seeded setup's row
(the 22G bent taper) and a placeholder setup (form and taper `unknown`, no ID or OD: it cannot be mounted until it is
completed) for every other tip key of `pad_types` and `dose_records`. Nothing existing is rewritten; `schema_info` 3
is written only on an upgrade. `pad_types` rows with `tip_key` null belong to the seeded setup (`Tip.setupOf`, in SQL
`COALESCE(tip_key, 'ad22tta-b')`); a `pad_calibrations` row without `setup_key` resolves to its job's dose records'
tip, else its version's, else the seed. `events` add `TIP` (adds, edits, mounts, needle changes, landing checks,
pictures). The no-arg `currentPadTypes()` / `currentPadType(key)` are gone: `currentPadTypes(setup)`,
`currentPadType(key, setup)` (composed), `padTypeHistory(key)`, `typeKeyExists(key)`, `newestOwnVersion(key, setup)`,
`padCalibrations(typeKey, setup, limit)`.

## 4. Planning

| Signature | Notes |
|---|---|
| `public DispensePlan planBoard(BoardLocation bl) throws Exception` | Pads from `packages.xml` footprint pads if present, else from the pad type layout. Uses `Utils2D.calculateBoardPlacementLocation`, i.e. the fiducial-corrected local→global transform when a fiducial check has set one. Bottom-up boards: `IllegalArgumentException`. Sets `plan.boardFingerprint`, stores `pad_plan` rows. |
| `public DispensePlan planBoard(BoardLocation bl, BoardRegistration reg) throws Exception` | *autoreg.* Plans `reg == null ? bl : reg.applyTo(bl)`: the pads follow the registration's board → machine affine, the board Z stays the nominal seat's (`plan.boardZMm`, the reference I4 checks a board surface against). Refused (`IllegalStateException`, a 409 at the API) when `boardZProblem` says the board top cannot be an N1 contact height. `plan.boardFingerprint` and `plan.boardRegistration` are computed on the registered location. |
| `public DispensePlan planBoardFile(File boardFile, Location boardLocation) throws Exception` | Loads a `.board.xml` and plans it top-side up at `boardLocation`. (The API now plans file boards through `planBoard(bl, reg)` too.) |
| `public DispensePlanner newPlanner() throws SQLException` | `new DispensePlanner(cfg, resolver).withRules(rules).withSurface(plane)`; `plan(String boardName, List<PadGeometry> pads, double startX, double startY)` for custom pad lists. |
| `public static List<GcodeStreamBuilder.DotSpec> purgeSpecs(DispenseConfig c)` | The next `profile.purgeDots` free purge spots as dot specs (index `PURGE_INDEX` = −3), or empty if the row cannot supply them (I9 refuses then). *Profile:* `purgeSpecs(c, plan)` = the spots a run of that plan lays (`DoseBlocks.purgeSpotsNeeded`: the initial purge, then a phase dot for a partial turn and `padCalibration.flushDots` flush dots before every further dose block); `purgeSpecs(c, n)`. |
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

*Board import.* A board plan also carries the board frame: `boardToMachine` (6 affine numbers), `boardFile`,
`boardWidthMm`, `boardHeightMm`, `boardTransformStatus`, `fiducials` (`id`, `partId`, board and machine X/Y),
`boardArtId` / `boardArtKind` / `boardArtNote` (the drawing, fixed by the API when the plan is made), and per pad
`padName`, `partId`, `roundness`, and the rule that chose its type (`typeRule`, `typeRuleDetail`, `typeRuleMatched`,
from `PadTypeResolver.explain`). `copyBoardFieldsTo(sub)` keeps them on run subsets. The board fingerprint includes a
digest of the board's content (`BoardContentDigest`), so a board imported again with other pads makes the plan stale.
`GcodeProgram.dotArriveSeconds` / `dotEndSeconds`: each dot's arrive and end on the program's clock (the path
preview).

*Profile.* A pad type may carry its own dose: `PadType.dotSteps` (a quarter, half or whole turn, 400 / 800 / 1600 steps,
at or above the tip's `minDotSteps`) and `dotsPerPad`; without them a pad gets the profile's dot as before. `PlannedPad`
adds `dotSteps`, `dotsPerPad`, `startPhase`, `typeTipKey`; `PlannedDot` adds `netSteps`, `block` and `padIndex`;
`DispensePlan.blocks` lists the runs of consecutive dots of one dose (`index`, `netSteps`, `turn`, `dots`, `pads`,
`firstDot`, `lastDot`, `phaseDot`, `flushDots`, `demandMgS`). The planner keeps a pad's dots together, forms one block
per dose (the profile's dose first, then descending; nearest neighbour + 2-opt inside each block, from where the block
starts on the purge row), and holds back a pad that is too close to a neighbour for its dots (`MAY_BRIDGE`, decision 14
of PC §20.1: per pad, not per group). A type calibrated with another tip is `STATUS_BELOW_MIN` with the tip in its text
(decision 16). `DoseBlocks` (pure): `isAllowed`, `dosesOf(plan, profileSteps)`, `purgeSpotsNeeded`, `fillDemand`.

*Tips.* Plans carry `setupKey`, `setupName` and `setupVersion` (a plan of another setup, or another version of it, is
stale) and `PlannedPad.typePadVersion`; the resolver's fingerprint is `key:padVersion/version@S` per type plus
`|tip:S@setupVersion`. `newPlanner()` takes the mounted tip (`withTip(svc.mountedTip())`; no tip: `the planner needs
the mounted tip`): the same-pad pitch and the dot-gap flag use its dot sizes, and a pad whose dose its dot table does
not know at this recipe and paste gets the blocking flag `DOSE_UNKNOWN` (no dots, a plan warning naming the dose).
Stand-ins (no version of the mounted setup) are `STATUS_BELOW_MIN`.

## 5. Execution

| Signature | Notes |
|---|---|
| `public Future<RunResult> runBoard(BoardLocation bl) throws Exception` | `planBoard` + `runPlan`. |
| `public Future<RunResult> runPlan(DispensePlan plan) throws Exception` | Never completes exceptionally for dispense failures: inspect `RunResult.status` (`OK`, `ABORTED`, `ERROR`, `REFUSED`), `message`, `interlocks`. |
| `public Future<PadCleanReport> checkCleanPads(DispensePlan plan) throws Exception` | The clean-pad guard alone (camera only, pressure off), so the operator can wipe before a run. A run repeats it. Refused at its start (a STOP since it was queued, the machine not started, the pressure hold on): the returned report is empty (`ok` false, no pads, `checkedAtMs` 0, `isComplete()` false) and the reason is the run's (`getLastRun()`, `status.run`). |
| `public Future<RunResult> runShots(String locationName, double rotationDeg, int count, boolean referenceSurface) throws Exception` | Manual prime / test dot at the waste place (`referenceSurface` true: the place is checked against the needle frame) and the scale workflow (shots into a cup: only the floor). Full I4. |

| `public void abort(String reason)` | Thread-safe. The stream stops before its next line and sends the Stop sequence (I1). Also triggered by OpenPnP Stop/disable. Ends a task OpenPnP dropped (`releaseDroppedTask`). |
| `public boolean releaseDroppedTask()` | *rc1 review.* OpenPnP removes every task queued behind one of its own that throws, without running or cancelling it, so the engine's `finish()` never ran and `busy` stayed set. A task not begun while the machine thread has been idle for 1 s (`DROPPED_AFTER_MS`) ends ERROR (`OpenPnP dropped this task before it started …: nothing was sent; start it again`) and its Future is cancelled; STOP and the API's engine-idle check call it. |
| `public boolean isBusy()`, `public RunResult getActiveRun()`, `public RunResult getLastRun()` | |
| `public void addListener(DispenseListener l)` / `removeListener` | `onPhase(RunResult, String)`, `onDot(RunResult, RunResult.DotTiming, int done, int total)` (work dots only), `onDetail(RunResult, Map)` (*autoreg:* structured progress of Register board, the probes and a run's home and Z check), `onFinished(RunResult)`; called on the machine thread. |
| `public void setPressureSensor(BarrelPressureSensor s)` | I5 hook (`Double readPsi()`); no sensor fitted yet. |
| `public void setDotImager(DotImager imager)` | Override the camera/vision (default: top camera + `CameraCapture`/`DotInspector`; `brightFraction` for I10). |
| `public Future<RegistrationResult> registerBoard(RegisterRequest req) throws Exception` | *autoreg.* Register board (`AUTO_REGISTER_DESIGN.md` §5.2): home if needed, the fiducials (`FiducialLocator`, the light on), the seat (`first` / `same` / `reseated`, §3.6), then for `probe` `AUTO` / `ALWAYS` the N1 probe of the board targets (`ProbeTargets`, each spot looked at first with `BareSpotCheck`) or, for a same seat with a tied surface, 3 stored points touched again; the surface saved tied to the registration; then `req.replanner` plans again. `RegisterRequest`: `plan`, `nominal`, `boardKey`, `nominalSeat`, `planToBoard`, `nominalZ`, `probe` (`AUTO` \| `ALWAYS` \| `NEVER`), `probeOnly`, `replanner` (a null registration plans without one). **Probe only** (F10): a board with fewer fiducials than `fiducialCheck.minFiducials` is refused; one without any fiducial, with `probeOnly` and `ALWAYS` on a plan made at the nominal seat (no registration), is probed only: no fiducial fit and no registration stored; the board targets are probed where the plan puts the board, the surface is tied to that seat (`boardRegistration(nominal)`), and the board is planned again on it (`RegistrationResult.probeOnly`, `probe.why` `no fiducials: probed only, X/Y as planned`). A board with fiducials ignores `probeOnly`. Never completes exceptionally for registration failures: `RegistrationResult.ok`, `message`, `stoppedStage`, `run` (a `RunResult` of kind `board-registration`). |
| `public Future<SurfaceProbeResult> probeSurface(SurfaceProbeRequest req) throws Exception` | *autoreg.* The automatic N1 probe of a needle-frame surface (the blank): `surfaceName`, `nominalZ`, `targets` (null: either side of the purge row, `ProbeTargets.forRow`). Saves the plane when it fits. |
| `public Future<RunResult> checkZ() throws Exception` | *autoreg.* One N1 touch per Z frame of the stale data at points measured before the last home (kind `z-check`; a point with paste on it is skipped for the next stored one): passes within `ZCheck.TOLERANCE_MM` 0.03 mm; a pass vouches for the data of that point's Z frame (§7, I4). A failed check ("Z moved") refuses every task whose Z data predate the home, with the remedy, which works under the same home in this order: `Record the needle frame again with both tips (Setup › Needle Z frame), probe the blank, take the places' Z again with N1 (Setup › Places), touch off and register the board again.` The frame is recorded with both tips: K (`needleContactRawSum`) moves by about twice a zero shift, so `POST /api/needle/anchor` refuses it while the check of this home failed and after a needle change (`needleFrameFromKRefusal()`). A needle frame recorded after the home replaces the touch-off it covers (`ZCheck.touchOffReplaced`: the touch-off is no Z datum any more, and I4 asks for a new one); data not measured again keep the refusal. The blank probed again after a home keeps the points it replaces as a reference of their Z frame (`SurfaceMap.replaced`, `ZCheck.kept`; one frame kept), so a later check of older data still has a point to touch. A place carries the time it was set (`NamedLocation.setAtMs`, `homeAtMs`; `setLocation` stamps them): after "Z moved" a place set before the home is refused (I4) where its Z is used (prime, the test dot, shots, the prime at the waste of the dot ladder and the touch-off, a coupon's probe and weigh dots) until it is set again (`ZCheck.placeBefore`, `movedPlaces`, `placeMovedMessage`). |
| `public HomeReport homeSafely() throws Exception` | *autoreg.* OpenPnP's home on the machine thread with N1 raised Z-only to the travel Z first when a STOP left it low; a STOP during the home disconnects the drivers. `{needed, whyCode, why, done, seconds, n1RaisedFromZ}`. Every engine task that may move calls `homeIfNeeded` first (`homeBeforeRun`). |
| `public VacuumProber.Window probePreflight(boolean board, String what, double nominalZ)` · `public ZCheckPlan zCheckPlan(DispensePlan plan, boolean homeFirst)` · `public InterlockReport checkBasicInterlocks(boolean homeFirst)` | *autoreg.* The checks the API runs before 202: the probe window (start = nominal + `startAboveMm`, floor = the higher of the nominal and the anchor − `floorBelowMm`, refused against the needle frame), whether Z must be checked and where, and the interlocks of a task without a plan. A floor at or above the board top is refused before anything moves (`PROBE: the board top Z 3.500 lies 2.000 mm under the needle-frame surface 5.500, and the probe goes at most 1.5 mm under the higher of the two (down to Z 4.000): it cannot reach that height. Set the board top Z to roughly the N1 contact height, or record the needle frame again; if both are right, probe by hand`): planning accepts a board top up to 2.0 mm from the anchor, so a board 1.5 to 2.0 mm under it is probed by hand. A weigh coupon's window is ±`couponWindowMm` around its own place Z (`Window.narrow`), never the needle frame's. |
| `public Future<DoseBlocksResult> runDoseBlocks(DoseBlocksRequest req) throws Exception` | *profile.* One engine step of a pad calibration (PC §7.3), `runs.kind` `pad-ladder` (test dots on the calibration area of the blank) or `pad-weigh` (weigh dots on the coupons): refuses at its start when a STOP came after `req.stopEpoch`, a dose the tip does not take (`PCAL: 533 steps is not a dot the 22G tip takes …`), a coupon place unset or carrying dots still to be weighed, a coupon touched before the last home with no Z check since (`I4: the 'coupon1' coupon was touched before the home at 13:02, …: touch it again before laying dots on it`); homes if needed; the basic interlocks and I4 on the area's or the coupon's surface; then, pressure off, the top camera looks at every spot in tiles (`PadLadder.tiles`: each spot ≥ 2 mm inside a frame) and refuses everything if one spot is not bare (`the calibration area is not clean or not flat under 3 of the dots …`; no camera: `the pad calibration needs the top camera …`); stamps the area's band as used (`areaReserveMm`) before streaming; one pressure session with `GcodeStreamBuilder.forBlocks` (the M114 count is required for a partial turn: `I11: the controller did not report the B step count (M114 Count): …. Nothing was sent.`); the after frames: every dot found (`DotInspector`), its landing offset, the screw phase it started at (by the count), a thread to the next dot (the corridor between them), three crops per block (`Crop`: the first dot, the median one and the worst one or a miss, before and after, PNG); parks N2 at the travel Z over the next unused purge spot. `DoseBlocksResult`: `ok`, `message`, `home`, `zCheck`, `stoppedStage`, `bCountAtStart`, `blocks` (`BlockOut`: `netSteps`, `label`, `couponPlace`, `hopMm`, `dots` (`DotOut`: `x`, `y`, `startCount`, `phase`, `found`, `dxMm`, `dyMm`, `threadToNext`, `string`)), `crops`, `refusedSpots`, `frames`, `run`. Never completes exceptionally for task failures. |
| `public GcodeProgram estimateBlocks(List<DoseBlocksRequest.Block> blocks) throws Exception` | *profile.* The program a dose-blocks step would stream, for the before-start time and the I7/I6-feed checks. |
| `public long stopEpoch()` | *profile.* Incremented by every STOP (`abort`): a step queued before it refuses at its start (`StopEpochTest`). |
| `public String couponReservation(String place)` · `public static boolean weighable(DoseRecord coupon, long nowMs, DispenseConfig c, DoseRecord.Epochs e)` | *profile.* Why no step may lay dots on or probe a coupon place now (`coupon1 carries 136 dots waiting to be weighed: type the readings or skip them (Calibrate › Pad calibration)`), else null; whether a coupon record's reading would still be a fresh mass record (the recipe, regulator, tip, paste, age and simulator flag; a reconnect does not change what lies on a coupon). |
| `public synchronized PadCalibrationRecord applyPadCalibration(PadCalibrationRecord rec, PadType version) throws SQLException` | *profile.* Writes one pad type's outcome (PC §7.7): the type's new version (when the calibration wrote one) and the `pad_calibrations` row, with a `PCAL` event. A `characterized` version needs a record whose every check passed; a calibration never writes `validated` (`IllegalArgumentException`). |

Every engine task (`runPlan`, `runShots`, `runDotLadder`, `runNeedleTouchOff`, `checkCleanPads`, `runDoseBlocks`, the
registration, the probes and `checkZ`) checks at its start, on the machine thread, a STOP since it was queued, that
the machine is started, and the pressure hold (`refuseWhilePressureHeld` inside `submit` / `submitResult`): a hold
switched on after the API's own check refuses it REFUSED with `the pressure hold is on: Pressure off first`.

What `runPlan` does, in order:
1. Refuses (REFUSED, nothing sent, before the session opens) if the profile's fingerprint differs from the plan's: plan
   the board again. Every other check first, with the home and the Z check as notes (*autoreg*); then it homes if
   needed (`homeIfNeeded`, `RunResult.homedFirst`) and checks Z when Z data predate the last home (`zCheckIfNeeded`,
   `RunResult.zCheck`; a failed check ends the run REFUSED before any pressure).
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
boolean keepalive, Long bCount)`; *profile:* it builds `forBlocks(cfg, mapper, List<BlockSpec>, …)`, one block per
dose, each after the first preceded on the purge row by a phase dot (partial turns) and `flushDots` flush dots of the
new dose):
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

`GcodeStreamValidator` refuses a program with: a line `ControllerCommands.check()` refuses (and any N1 vacuum or I2C
line: they belong to the probe); valve ON before pump ON or pump OFF while the valve is ON; *profile:* a dot whose turn
differs from its plan's, is not an allowed dose or is below the tip's smallest, two doses without a block start between
them, a block that does not start at the start angle, a pad whose dots are split (a dot's pad is the planner's
`PlannedDot.padIndex`, so two pads of one name in a footprint are two pads; the pad id only for a plan made without the
index), a purge dot of more than one revolution plus the 20° phase turn, a program that turns the screw more than its plan allows; a B move before `G92 B0`
(I2) or outside a pressure session; any G0/G1 with a non-B axis word while the last `M204 S` exceeds
`max(travelAccelMmS2, zLiftAccelMmS2)` ("X/Y/Z move under the screw acceleration"); a B feed above `bMaxFeedDegS × 60`;
an `M400` inside a dot; a raw Z outside the window between the I4 needle floor and the travel Z; pneumatics ON or the
watchdog armed at the end.

`GcodeProgram.estimatedSeconds` / `estimatedPressurisedSeconds`: moves as trapezoids at the M204 in force (B at
`min(M204 S, M201 B)`), dwells; no look-ahead or host latency. `estimatedWorkSeconds`: from the cross after the purge
to the last dot's lift, the window the bench's DPH is timed over (web UI: "dots only"; the whole program is
"overall"). Against the bench's p18_board1 the move model is about 3 % slow.

`RunResult`: `runId`, `kind` (`board`/`shots`/`dot-ladder`/`touch-off`/`clean-check`; *autoreg*
`board-registration`/`surface-probe`/`z-check`), `status`, `message`, `startedAtMs`, `endedAtMs`, `dotsPlanned`,
`dotsDone` (work dots), `firstDotStartMs`, `lastDotEndMs`, `dotTimings` (`dotIndex`, `padId`, `rotationDeg`, `startMs`,
`endMs`; work dots), `keepaliveActive`, `keepalivePings`, `linesSent`, `streamedLines`, `warnings`, `interlocks`,
`profile` (the copy used), `cleanCheck` (`PadCleanReport`), `purgeDots`, `bCountAtStart`, `workStartAngleDeg`, *autoreg*
`homedFirst`, `zCheck`, `nextPlanId`, `Double dphMotion()` = dots / (lastDotEnd − firstDotStart) · 3600, `Double
dphOverall()` = dots / (end − start) · 3600. Timestamps are host-side: a dot's `endMs` is when its last line was
acknowledged (queued in Marlin, not executed); `lastDotEndMs` is the completion of the `M400` that follows the last dot
(true completion).

`PadCleanReport`: `planId`, `ok`, `grayLevel`, `minBrightFraction`, `boxWMm`, `boxHMm`, `checkedAtMs`, `pads`
(`padId`, `dotIndex`, `xMm`, `yMm`, `brightFraction`, `clean`, `error`, `boxWMm`, `boxHMm` used at that dot),
`dirtyPadIds()`, `refusal()`, `isComplete()` (`checkedAtMs > 0`: false for a check that was stopped or failed).

## 6. Calibration

| Signature | Notes |
|---|---|
| `public Future<DotLadderCalibration.Result> runDotLadder(DotLadderCalibration.Request req) throws Exception` | Request: `rotationsDeg` (default 30/45/58/75/90), `repeats` 5, `pitchMm` 1.5, `locationName` (default blank), `primeAtWaste`. Every dot at the probed blank plane under it + offset + gap (the profile's suck-back, dwell and screw feed). Before images (pressure off) → one pressure session → after images → `DotInspector` → one `dot_ladder` row per rotation on the current calibration record. Requires I4 (incl. the blank surface). |
| `public Future<DispenseService.TouchOffResult> runNeedleTouchOff(NeedleTouchOff.Request req, boolean apply) throws Exception` | §6.1 option 1. Gaps `startGapMm` 0.125 down by `stepMm` 0.025, `steps` 6, relative to the current offset, each above the probed blank plane at that dot; `rotationDeg` NaN (default) = one profile dot; `pitchMm` 1.6 (F14: at least the expected dot diameter, `DoseModel.dHiMm` of the dot's turn, + 0.4 mm, else REFUSED before anything is sent: `NeedleTouchOff.pitchProblem`). Each after frame is measured at the spots of all the ladder's dots (`DotImager.inspectSpots`: the new paste opened with a disc 0.45 × the expected dot, so strings and necks thinner than that go; the piece at each spot is its dot), F9. Contact = midpoint between the last normal dot and the first squashed (`> (1+jumpRatio)·reference`) or missing one; new offset = old + contact. The reference is the first two dots, which must both form, agree within `jumpRatio` (a contact needs two steady dots before it) and be at least `MIN_REFERENCE_SHARE` 60 % of the steady dot of their dose (`NeedleTouchOff.steadyDotMm`, `DoseModel.dLoMm`: 1.16 mm for a half turn; smaller ones are droplets from a needle too high, which would make the first full dot look squashed); paste at a spot that is not one dot (joined to a neighbour, strings) before the contact refuses the conclusion (`dots touch or are strung together: …; use a larger pitch`), never "no dot". `apply` writes it (sets `needleZOffsetSetAtMs`, clears I4). The only I4 waiver (named): it needs the needle frame and the blank surface; while not touched off its prime is `touchOffPrimeExtraClearanceMm` higher. |
| `recordScaleShots` / `recordScaleDots` | See §3. |

Pure helpers usable without a machine: `DispenseProfile.*` (`phaseSteps`, `startAngles`, `angleOfCount`, `validate`),
`NeedleTouchOff.analyze(double[] gapsMm, Double[] diametersMm[, String[] unmeasured], double jumpRatio)` and
`analyze(double[] gapsMm, Double[] diametersMm, String[] unmeasured, double jumpRatio, double steadyDotMm)` and
`analyze(double[] gapsMm, List<SpotDot> seen, double jumpRatio, double steadyDotMm)`, `NeedleTouchOff.pitchProblem(...)`,
`vision.DotInspector.inspectSpots(...)`, `DotLadderCalibration.analyze(...)`,
`DotSizer.layout(...)`, `DotMath.*`, `DispenseService.parseBCount(List<String>, String)` (package-private),
`GcodeStreamBuilder.forPlan(...)` / `forDots(...)` / `abortLines(...)`, `vision.PadCleanCheck.measure(...)`.

*Profile:* **dose records and the pad calibration** (PC §1.7, §7). Every engine step and every reading writes a
`DoseRecord` (append-only `dose_records`): `optical` (a ladder block on the blank: dots, found, misses, hops,
threads, area mean, residual CV, diameters, per-phase statistics, drift, landing), `coupon` (the weigh dots on a
coupon: the same camera statistics and the place) and `mass` (the readings of a coupon record: full, empty, total,
mg per dot, mg per step, the record it confirms within `confirmTolerance` 5 %; a skipped weighing is a mass row
without readings). A record is evidence only for the setup it was made in: `whyStale(nowMs, cfg, epochs)` gives the
first rule that fails (the simulator flag, the dose recipe `profile.doseFingerprint()`, the attested regulator psi,
the tip, the needle change, the paste and its load, `doseRecordMaxAgeH` 8 h; for optical and coupon records of a
partial turn also the controller's connect and the start angle). `DoseRecords.evidence(records, cfg, epochs, now)`
pools every fresh record of a dose into a `padprofile.DoseEvidence` (`LadderStats`: the transfer, threads against
`k95`, the repeat CV, the half-turn split or the quarter-turn harmonic fit, drift, landing; the mass readings and
whether two confirm each other); a failed record is not erased by a later passing one. The mass per dot of a reading
is its total over the dots laid on the coupon (PC §7.5; the dots the camera found still set the plausibility check's
expected mass). `padprofile.Decider` decides each group from that evidence (`characterized` only when every check
passes: optical, mass confirmed, amount within −25 %/+10 %, R6, geometry, the control dot, tip, regulator, not
validated; a fallback plan stays `provisional`, `validated` is never written), and the API's job writes it with
`applyPadCalibration`. The job itself (steps, readings, the result) is the API's `PadCalibrationJob` (`WEB_API.md`
§3.5). Pure helpers: `PadLadder` (doses, pitch, the area's band, camera tiles, bare looks, crops, the corridor thread
test), `WeighPlan` (the dots per coupon for `weighTargetMg`, their grid inside the coupon margin, the coupon looks and
touches, which dose goes on which coupon; `within`: whether a new grid stays inside the margin the camera checked,
else the job's post-ladder reweigh keeps the checked grid and dose and says so), `DoseRecords`, and the `padprofile` package (`DoseModel`, `DotGeometry`,
`LadderStats`, `ProfileCheck`, `Suggester`, `Decider`, `Texts`, `Limits`).

## 7. Interlocks (§5.5)

| Id | Where | Behaviour |
|---|---|---|
| I1 | `DispenseExecutor.shutdown` | The Stop sequence (F9) on any error (an `Error` too, rc1 review: an OutOfMemoryError used to leave the pump and valve on; the N1 probe's points likewise), abort, I7 trip, OpenPnP Stop/disable (machine listener). Each line attempted, through `sendCritical`. The web status reports `stopCommandCheck`: OpenPnP's own Stop is OK only if the driver's `DISABLE_COMMAND` contains the exact contiguous F9 sequence. |
| I2 | `GcodeStreamBuilder` + `GcodeStreamValidator` | No B move before `G92 B0`; flag cleared on home, enable, disable, abort. |
| I3 | `Interlocks` | Refuses if N2 has compatible nozzle tips or a loaded tip (names reported). |
| I4 | `Interlocks` (+ builder floor, validator Z window) | *tips:* texts name the setup (`I4: the 25G straight was put on at 14:07: record its needle Z frame (Setup › Needle Z frame)`); **the landing rule**: while where the needle lands is not checked (`isNeedleXyChecked()`: `needleLanding` set since the needle change under N2's head offset now, and a non-zero trim set under it too) only prime, the test dot and the landing check dispense (`Scope.landingWaiver` `waste` \| `landing-check`): `I4: the … was put on at 14:07 and where it lands has not been checked: check where it lands (Setup › Dispenser & tip).`; **the offset rule**: a frame whose offset is below 2 × `zFrameToleranceMm` + 0.1 mm refuses dispensing (`The …'s tip is 1.50 mm from N1's: I4 cannot tell a height taken with the needle; lower zFrameToleranceMm below 0.70 mm (Settings).`) and planning takes a board top within min(2.0, offset − 0.5) mm of the frame. *Autoreg:* a board surface tied to a plan is checked against the plan's board top Z, every other surface and place against the needle-frame anchor (one reference per surface, `AUTO_REGISTER_DESIGN.md` §16 item 1); a surface probed on the simulator is refused on a machine that is not. **Z after a home:** the needle frame, the touch-off and the surfaces measured before the last home are accepted only after a passing Z check for this home that vouches for each of them (a touch vouches only for data of its own Z frame); a failed check refuses (`I4: Z moved …`); a task that homes or checks Z first gets a note instead. Before: refuses without a needle Z frame recorded since the last needle change; unless touched off since the change and the frame (named waiver: the touch-off run); if the offset is more than `needleOffsetMaxDriftMm` from the frame's; if a waste/blank place Z or any surface point is more than `zFrameToleranceMm` from the frame surface; board / ladder runs: without the probed surface, with a plane residual above `surfaceMaxResidualMm`, with a dot more than `surfaceMarginMm` outside the probed hull, (board) a plan made on another surface map, or a board surface probed on another board registration (`boardFingerprint`). The purge row's surface gets the same checks for the purge spots (`Scope.moreSurfaces`). Hard floor: no dot below frame surface − tolerance + offset − 0.1. |
| I5 | `Interlocks` | Pressure window via `BarrelPressureSensor`; disabled by default. |
| I6 | `Interlocks` (note) | The regulator is set by hand to `profile.pressurePsi` (18); reported with every check. *Profile:* **I6-feed** refuses a plan or a dose-blocks step whose block asks the screw for more paste per second than `profile.sustainedFeedMgS` (2.6 mg/s, the bench's 2.56 at 18 psi): `I6: block 2 (one turn) asks the screw for 3.07 mg/s; the most it was seen to keep up with at 18.0 psi is 2.60 mg/s: lengthen the dwell or use more, smaller dots`. |
| I7 | `Interlocks` (estimate) + `DispenseExecutor` (runtime) | Max continuous pump+valve on-time. |
| I9 | `Interlocks` (board runs) | Purge row defined and at least `profile.purgeDots` free spots; *profile:* the spots the plan's blocks need (`DoseBlocks.purgeSpotsNeeded`), named per switch. |
| I10 | `Interlocks` + `runPlan` (board runs) | With `cleanPadCheck.enabled`: a top camera must be available, and the clean-pad check must pass right before the pressure goes on. |
| I11 | `Interlocks` (board runs, partial-turn profile) | `profile.startAngleSetAtMs` must exist and be >= `controllerConnectedAtMs` (set or confirm after every connect); `runPlan` also refuses when the controller does not report the B step count. *Profile:* also when any block of the plan is a partial turn. |
| MACHINE | `Interlocks` | *autoreg:* "not homed" is a note (`MACHINE: not homed: homes first`) when the task homes first (`homeFirst`); "not enabled" always refuses (nothing presses Start for the operator). |
| PLAN | `Interlocks` | Blocked pads (`… pad(s) not dispensable: …`, a note `… flagged pad(s) will be skipped` with `refuseFlaggedPads: false`), a plan with no dots. *Profile:* a pad type whose dose the tip does not take (`pad type x v2 has … dots; the 22G tip takes quarter, half or one-turn dots (a quarter turn at least): edit it and plan again`), a pad of less than half a turn in a row (R6: `… gives each pad 400 steps (a quarter turn); every pad needs at least half a screw turn in a row …`), and a plan made before the tip, the gate, a pad type or a mapping changed (`the tip changed after planning: …`, `the pad-type gate changed after planning: …`, `pad types or package mappings changed after planning (…); plan again`). A type calibrated with another tip is `STATUS_BELOW_MIN` (decision 16); a pad too close to a neighbour for its dots is held back as `MAY_BRIDGE` (decision 14). *Tips:* the plan's setup and version must be the mounted one's (`the tip changed after planning (the 25G straight was mounted at 14:07): plan again`, `the 25G straight changed after planning (OD 0.51 → 0.55 mm): plan again`), a pad-type read that fails refuses the run (`the pad types could not be read (…): plan again`), and a planned dose the tip does not know is refused (`pad type … v8 has half-turn dots, which the 25G straight never measured at this recipe and paste: measure its dots (Setup › Dispenser & tip)`). |

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
- In a SIM session the top camera is `SimBoardCamera` (it draws the scene's board, pads and paste), so the clean-pad
  guard and dot vision pass there (the SIM acceptance's register and import scenarios run the clean-pad check on); on a
  plain simulator config with a static image they cannot pass: switch the clean-pad check off there.
- `honorBacklash` with this machine's X/Y `OneSidedPositioning` makes every XY move end with OpenPnP's backlash
  approach (a second, half-speed 0.1 mm move): about 10 ms per dot that `tools/disp.py` does not spend
  (`src/test/sim/il_sim_acceptance.py` DPH what-ifs: 1.5 s per 160-dot board).
- *Autoreg*, measured on the machine (2026-10-28/29): a home takes 21–23 s; fiducials re-found within 0.003–0.04 mm on
  the same seat; OpenPnP's homes repeat Z to 0.025 mm; the bench's Z frame is about 0.8 mm off OpenPnP's, so
  bench-measured Z numbers and the typed needle anchor are not valid in OpenPnP's frame. The rest of
  `AUTO_REGISTER_DESIGN.md` §11 (STOP during G28, VAC1 sensor mode, open-air readings, …) and the open review-1
  limits are in `REAL_MACHINE_TODO.md`.
- *Autoreg:* each probe point ends with the bench's lift, then the vent; a loose surface comes up with N1
  (`REAL_MACHINE_TODO.md` F7). The engine learns the home duration per OpenPnP session only.
- *Profile:* **the paste model's limits** (PC §1, the confidence each number carries). Fitted on steady-state dots
  only: the mass per step at 18 psi (2.11 mg per turn, `tools/lumen.py`) and the 0603 half turn the user validated
  after reflow (1.06 mg on 0.73 mm², the per-family anchor) are measured; the footprint law's quarter- and one-turn
  ends (d 0.83–1.00 mm at a quarter turn), the ripple factors, the 0.72 mm tip OD behind the same-pad pitch, the
  `k = 0.5` above 4 mm², the fine-pitch cap (0.25 mm print) and the 8 h freshness are assumptions until the machine
  measures them (`REAL_MACHINE_TODO.md` items 38–52). The pad calibration measures mass, dot size, threads, repeat,
  phase and landing on the machine itself, so a type is `characterized` on measured numbers, never on the model's;
  `validated` needs a reflowed board (the test-board ladder, PC §9 / §20 decision 8, is a follow-up, not in this PR).
  An amount no allowed dose reaches within −25 %/+10 % (the Nano's LED_0805: one turn +33 %, half a turn −33 % on the
  machine) stays `provisional` whatever is measured.
- *Profile:* the SIM paste (`sim.SimPaste`, `il_sim_scene.py --paste`) differs from the model on purpose (2.30 mg per
  turn, its own footprint law, ripple, noise, threads, bead carry-over, feed limit); it models the needle Z only as
  the gap to the surface, and its records are `simulated` (never evidence on the machine).
- No HTTP endpoints here; this is the Java service the web layer calls (`docs/instancelabs/WEB_API.md`; the bench
  recipe added `POST /api/plan/{id}/clean-check`, `POST /api/screw/start-angle`, `POST /api/purge-row/reset`).

## 9. Tip setups, pictures and labels (*tips*, `PROFILE_CALIBRATION_DESIGN.md` §22)

A **tip setup** is a dispenser with a needle (`TipSetup`: `key` = the tip key records already carry, `name`,
`dispenser` (default `Vieweg DV-5005`), `tipModel`, `gauge`, `idMm`, `odMm`, `odSource` (`measured` | `assumed`),
`form` (`bent` | `straight` | `unknown`), `bendDeg`, `bendToward` (`+x`, `-x`, `+y`, `-y`), `taper`
(`tapered-plastic` | `straight-steel` | `unknown`), `lengthMm`, `minDotSteps` (400, 800 or 1600), `model`
(`bench-22g` for the seed only), `notes`, `retired`; append-only versions in `tip_setups`, the newest counts;
`problems()`, `isComplete()` (ID, OD, form and taper known), `summary()`, `toSpec()`). The seed is the 22G bent taper
(`ad22tta-b`, ADL AD22TTA-B, 22G, ID 0.41, OD 0.72 assumed, bent 30°, tapered plastic, `bench-22g`). One setup is
mounted: `cfg.tip.key`; the others are parked in `cfg.parkedTips`. `TipSetups` (pure helpers): `typesFor(db, setup)`,
`deriveKey(setup, taken)` (`25g-straight`, `27g-bent45`, `22g-taper`, then `-2`, `-3`), `checkEdit(was, next, inUse,
correction)` (identity fields of a setup with records: `another-needle`; tip model, ID within 0.03 mm, bend and length:
`correction`), `copyOf(...)`, `newEntry(live, profile, profileFrom)`.

**What the mounted setup uses** (`getPadTypes()` → `TipSetups.typesFor(db, S)`): per type key the pad fields (name,
family with `Retired`, pattern, pad size and roundness, layout, pitch, t, k; `padVersion`) from the newest version of
**any** setup, and the dose fields (status, dotSteps, dotsPerPad, calAreaMm2, doseFp, massPerPadMg, origin, notes;
`version`) from S's newest own version; a type with none is a **stand-in** (`standIn`, `version` 0, `not-started`, no
plan, notes `Not calibrated for the {S} yet.`). Creation order and version numbers stay one line per key across
setups. `mountedTip()` → `padprofile.Tip` with S's dot table (`DoseRecords.dotTable(records, S, doseFp, pasteKey,
simulated, flaggedJobs)`: per dose the newest passing optical record of S at S's recipe, the loaded paste and the
simulator flag, any age, outside jobs flagged `wrong-tip`; the mass per step from S's newest confirmed weighing),
cached per (setup version, recipe, paste, simulator flag, newest dose record, flagged jobs). `Tip.knows(steps)`,
`dLoMm`, `dHiMm`, `massMg` give the bench model's values for the seed and the measured ones otherwise, and throw for a
dose the tip does not know (no NaN reaches a comparison or a view); `isMeasured()` (its smallest dose has a size and
it has a weighing). A tip without the bench model and not measured gets no suggestions but `measure-tip`;
`characterized` without the bench model needs S's own evidence for every dose; the control compares with the bench
only for the seed, else with S's own dots, or passes as `first measurement of this tip`. Anchors (the amount a joint
needs) stay shared across setups.

**Needle changes and mounting.** `recordNeedleChange(why, operator) → NeedleChange` (synchronized, never refuses): a
running engine task is stopped first (`abort("the needle was reported changed")`), `needleChangedAtMs` = max(now,
previous + 1) (the **needle token**: strictly increasing), every coupon waiting for its readings gets a skip row
(`skipCoupon`), a `tip_mounts` row `needle` and a `TIP` event. `switchTip(key, why, operator) → MountResult` (the API
runs it inside `PadCalibrationJobs.whileNoJob` and the engine holds its busy flag around the write): refused while a
task runs, the pressure is held, or for a retired or incomplete setup; parks S (profile, frame, offset with its times,
trim, landing, `parkedAtMs`), brings in the incoming entry (its recipe takes the live start angle and its time; one
that no longer validates is reset to the default profile named `{name} (reset: {problem})`), `cfg.tip` = the incoming
view, one config write without the start-angle and trim stamping (restored values keep their times), a `tip_mounts`
row `mount` and a `TIP` event. `undoMount(operator)`: within 2 min of a mount of another setup, no task run and
nothing measured since, the previous mount exactly from a snapshot in memory (`too-late` otherwise); `undoUntilMs()`.
Every value measured for a needle carries the token (`setNeedleAnchor`, `setNeedleZOffset`, `setXyTrim`, `setLanding`,
`keepLanding`: `EngineRefusal` 409 `needle-changed`), every pad-type write its setup (`savePadType`: `tip-changed`,
`pad-changed`), and a pad calibration's every dose-blocks step its setup and token (`DoseBlocksRequest.forTip`,
`needleSinceMs`: refused on the machine thread; `applyPadCalibration` refuses a write that names another setup or
needle). Stale after a change: the frame (`isNeedleAnchored`), the offset (`isNeedleTouchedOff`), where it lands
(`isNeedleXyChecked`), dose records made before it; not the surfaces, places, purge row or calibration area.

**Where the needle lands.** `needleLanding` per setup (parked with it), measured by the landing check
(`PadCalibrationJob` `measure: "landing"`: a locator dot, then the purge and a row of 8 dots fitted by
`padprofile.LandingFit`, `PadLadder.landingLayout` for the band and its frames; `DoseBlocksRequest.landingCheck`,
`DoseBlocksResult.newBlobs`) and saved with `setLanding(LandingResult r, boolean accepted, Long needleChangedAtMs)`:
saved at once when the fit holds and the setup had no landing of its own within 1.0 mm, else the result's `question`
and nothing saved until accepted. `keepLanding(token)` (a straight setup whose landing a check set under today's head
offset; `keepLandingProblem()` says why not), `setXyTrim(dx, dy, note, token)` (±1 mm, stamped with the head offset),
`headOffsetNow()` (N2's head offset in OpenPnP, read only), `landingInEffect()`. A change of N2's head offset in
OpenPnP stales every landing and trim set under the old one. The trim resets to 0 when a landing is saved. Every pad
calibration ladder also records `landing_dx_mm` / `landing_dy_mm`.

**Pictures** (`PhotoStore`, synchronized; `Photo`: `kind` `tip` | `setup` | `result`, `source` `bottom-camera` |
`top-camera` | `upload`, the setup, `needleSinceMs`, job, dose, coupon, file, SHA-256, size, mm/px, frame centre,
caption, simulated): every picture is re-encoded (JPEG 0.85, at most 2048 px on the long side, no metadata; result
photos at most 1600 px wide) with a 320 px thumbnail, written to a temporary file in its month folder and moved to
`<configDir>/instancelabs/photos/<yyyy-MM>/<20 hex of the SHA-256>.jpg` (equal pictures share a file); the decode reads
the header first and subsamples to at most 2048 px (never the full raster); alpha and indexed PNGs are drawn onto RGB.
Limits: JPEG or PNG by its bytes, 64–12,000 px a side, 40 Mpx, 8 MiB per request (`MAX_REQUEST_BYTES`), 6 tip pictures
per record, 20 unlinked pictures per setup, 2 GB of removable pictures, captions ≤ 200 characters. One upload at a time
(`PhotoStore.PIPELINE`, a `Semaphore(1)` taken without waiting). Removal appends a `photo_events` `remove` row (the
file stays 7 days; `restore`); result photos cannot be removed or chosen as the main picture (`result-photo`). The
start sweep deletes files no live row names, files removed more than 7 days ago and result photos never linked after
30 days. Links (`linkPhotos(photoIds, calibrationIds, role)`, roles `result` | `tip` | `reference`) need the
record's setup (`setup-mismatch`) and, for a bottom-camera picture, the same needle (`needle-changed`).
`storePhoto(PhotoStore.Input)` (an upload, with its `TIP` event), `storeResultPhotos(jobId, DoseBlocksResult)` (one
after frame per block, carried as JPEG bytes in `DoseBlocksResult.photos`), `takeTipPicture(TipPictureRequest) →
Future<TipPictureResult>` (an engine task, run kind `tip-picture`: N2 to the bottom camera's X/Y at `travelSafeZMm`,
never lower, refused when N2's safe Z zone does not hold it, unhomed, under the pressure hold, for a setup not mounted;
`refuseIfStoppedSince` after the move and before the store; the token checked again: a needle change meanwhile stores
nothing), `TipCamera` (`isAvailable`, `locationMm`, `capture`; `OpenPnpTipCamera` = OpenPnP's first up-looking camera,
`setTipCamera` for tests). At its decision a calibration links each record to the result photos of its chosen dose
and to its setup's main picture of that moment (role `reference`).

**Labels and the wrong tip.** Every calibration record gets a "calibrated for" label (`cal_labels`, the newest row
counts): written automatically by the job's decision and the by-hand save (`autoLabel`: `Texts.calLabel` `Chip 0603 ·
22G bent taper · Vieweg DV-5005 · 18 psi · Loctite GC50 SAC305 T5`, type first, over 160 characters the paste then the
type cut with `…`), editable (`cleanLabel`: 1–160 characters), computed when read for records from before schema 3.
`markWrongTip(jobId, operator) → List<PadType>`: a `cal_flags` row for the job, its waiting coupons skipped, its dose
records out of the setup's evidence and dot table, and every version it wrote that is still its setup's newest replaced
by a version restoring the setup's previous own dose fields (or a stand-in's cleared plan), notes `restored:
calibration {id} was made with another tip`.

**At start** (`reconcileTip()`, once the config and the database are open): (1) a `cfg.tip.key` that names no setup
gets one made from `cfg.tip` (form and taper `unknown`); (2) `cfg.tip` is compared with its setup on `idMm`, `odMm`
and `minDotSteps` only (never the name) and a version appended from the file when they differ; (3) `cfg.tip` is
refreshed from the setup (so an old `22G tip` reads `22G bent taper`); (4) an empty `tip_mounts` gets a row `seed`, a
newest row of another key a row `reconcile`; (5) the first time, a needle change finished before the update keeps
its landing (`{0, 0}` with N2's head offset now, note `kept from before the update (boards ran with it)`) and an open
one asks for it.
