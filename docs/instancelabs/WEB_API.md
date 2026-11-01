# Instance Labs web API and web UI (`org.openpnp.instancelabs.api`)

Branch `feat/web-ui`, on top of `feat/dispense` (engine contract: `docs/instancelabs/DISPENSE_API.md`). Updated on
`il/bench-sync` for the bench recipe of 2026-10-28 (dispense profile, purge row, start angle, clean-pad guard, F9 Stop).
Spec: `docs/plan-2026-10-27-evidence-backed-roadmap.md` §3.3 (local API inside the OpenPnP process, web UI on
localhost), §4.4–4.5 (Stop, on/off order), §5.5 (interlocks), §5.7 (Profiles tab), §8 items 7–10.

## 1. Where it runs

- Started by one line in `org.openpnp.Main` (`ApiServer.install(configuration)`), right after the engine's
  `DispenseService.install`. Both start on OpenPnP's `configurationComplete`.
- URL: **http://127.0.0.1:8765/** (UI) and `/api/...` (JSON). JDK `com.sun.net.httpserver`, Gson, no new dependency.
- Settings: `<configDir>/instancelabs/web-api.json` (never written by the API):
  `{"port": 8765, "lanAccess": false, "enabled": true, "threads": 8}`.
  Overrides: system properties `instancelabs.api.port|lan|enabled`, then env `OPENPNP_IL_API_PORT|LAN|ENABLED`.
- `lanAccess: true` binds all interfaces (e.g. a tablet next to the machine). There is **no authentication**: turn it
  on only on a trusted network. Off by default.
- If the port is taken, the API logs an error and OpenPnP continues without it.

**Browser safety.** No CORS headers are ever sent. The `Host` header must be `127.0.0.1`/`localhost`/`[::1]` unless
LAN access is on (DNS-rebinding guard). Requests other than GET/HEAD with a foreign `Origin`, or
`Sec-Fetch-Site` other than `same-origin`/`none`, get 403 (CSRF guard), so a web page elsewhere cannot press buttons.
`curl -X POST http://127.0.0.1:8765/api/stop` works. The UI is served with a strict CSP (`script-src 'self'`).

## 2. Errors and refusals

Every error is JSON: `{"error": "<slug>", "reason": "<plain language>", ...details}`.

| Status | `error` | When |
|---|---|---|
| 400 | `bad-request` | invalid field, out-of-range value, bad JSON |
| 403 | `forbidden` | Host / Origin / Sec-Fetch-Site guard |
| 404 | `not-found` | unknown endpoint, plan, pad type, calibration, no camera |
| 405 | `method-not-allowed` | |
| 409 | `refused` | machine state or an interlock refuses; `interlocks: {ok, violations:[{code,message}], notes}` included when the engine's interlocks refused |
| 503 | `unavailable` | engine not running, dispenser unavailable (no N2), camera gave no frame |

Every mutating machine action goes through the engine (`DispenseService`) and its interlocks. The API runs the
engine's pre-flight check synchronously so a refusal comes back at once with the reason; the engine re-checks when
it runs (authoritative). Runs return `202 {"started": true}` immediately; add `"wait": true` to block until the run
ends (max 10 min) and get `"result"` (RunResult view).

## 3. Endpoints

| Method + path | Body | Does |
|---|---|---|
| `GET /api/status` | | Everything the UI polls (every 0.7 s): `server`, `machine` (enabled = connected, homed, busy, drivers, nozzle/camera positions, axes incl. B), `dispense` (see below), `pressure` (hold state, auto-off time, keepalive), `run` (live progress: label, phase, dots done/planned, done dot indexes, live DPH, `pressureOnAtMs` = when the run's pressure session started, i.e. its first pressurised phase `dispensing` / `shots` / `dispensing calibration dots`, null before), `lastRun` (run view, incl. `dphMotion`, `dphOverall`), `interlocks` (MACHINE, CONFIG, I1–I11 with state ok/blocked/warning/off/info and plain text) |
| `POST /api/stop` | | **Always allowed.** Engine `abort()` (before its next line it sends the bench F9 Stop sequence `M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B`: quick stop, dispenser valve and pump off, pick valve and pump off, screw driver off), vents a pressure hold, then OpenPnP's Stop path (`machine.setEnabled(false)`: emergency thread if the machine thread is busy, as OpenPnP's own Stop button). Unless the driver's DISABLE_COMMAND contains that sequence, STOP first gives the engine up to 1 s to send its own lines. Answers within 1.5 s, waits included (the web UI says STOP NOT CONFIRMED after 2 s without an answer): `{stopped, machineEnabled, actions}`. A disable queued behind a dwell (valve settle, up to 3 s) goes on after the answer; `machineEnabled` is then still `true`, an action says so, and `GET /api/status` shows the machine off. |
| `POST /api/machine/enable` | | OpenPnP Start (connects the driver). |
| `POST /api/machine/home` | `{wait?}` | OpenPnP home. Refused while the engine runs or pressure is held. |
| `POST /api/pressure/start` | | Manual pressure hold: pump ON, wait `pumpToValveSettleMs` (1000 ms), valve ON, wait `valveSettleMs` (3000 ms) (bench `disp.py`; host-side waits, cancellable). Pre-flight = engine interlocks without I4 and the board-run checks I9–I11. A controller error pending at the `M115` probe refuses it. |
| `POST /api/pressure/stop` | | Valve OFF (barrel vents), then pump OFF. |
| `POST /api/prime` | `{rotationDeg?, wait?}` | Engine `runShots(waste, rotation, 1)`: a complete session (pressurise, prime, vent). Default `primeRotationDeg` (360.36° = one screw revolution of 1600 steps). Full I4. Manual only: board runs purge on the purge row. |
| `POST /api/test-dot` | `{rotationDeg?, wait?}` | Engine `runShots("waste-test-dot", rotation, 1)`; that place is kept = waste + `testDotOffset`. Default rotation = one dot of the dispense profile (`dispense.testDot.defaultRotationDeg`, 180.1802° for half a turn). Full I4. |
| `GET /api/locations` · `PUT /api/locations/{name}` | `{x, y, z}` | Named places: x/y of N2, z = surface Z in OpenPnP's frame (the N1 nozzle Z touching it). `waste-test-dot` is derived and cannot be set. |
| `POST /api/needle/changed` | | I4: blocks dispensing until the needle Z frame is recorded again and the needle is touched off. |
| `POST /api/needle/anchor` | `{surfaceZ, needleContactZ, x?, y?}` or `{surfaceZ, needleContactRawSum, x?, y?}` | I4 needle Z frame: N1 contact Z and needle (N2) contact Z on the same surface; offset := difference. Instead of touching the needle, `needleContactRawSum` K uses the bench relation "needle contact raw Z = K − N1 contact Z" (side camera, 2026-10-27: K = 65.59; with OpenPnP's see-saw map z2 = 63 − raw that is N2 contact = N1 contact − 2.59, e.g. 5.5 → 2.91, offset −2.59 mm). Both at once: 400. The anchor records `rawSumMm`. A touch-off (or a confirmed offset) is still needed. |
| `POST /api/needle/offset` | `{offsetMm, note?}` | Manual needle Z offset (records a `needle_touchoff` row; clears the touched-off part of I4 if within `needleOffsetMaxDriftMm` of the frame). |
| `GET /api/surfaces` · `PUT /api/surfaces/{name}` · `DELETE /api/surfaces/{name}` | `{points: [{x,y,z}] or [[x,y,z]], note?, planId?}` | Probed surfaces in OpenPnP's frame (N1 contact heights), >= 3 points spanning an area; returns the plane fit (coefficients, slope, residuals, Z range) and `boundToBoard`. `board`: every dot's Z on a board run. `blank`: purge row, dot ladder and touch-off. A zmap.json `points` list is accepted as is. `planId` ties a board surface to that plan's board registration (X/Y/rotation, side, fiducial transform; not placements or the nominal Z): a plan of a re-seated or moved board is then refused by I4 until the board is probed again (bench F3: one seating was tilted 0.9 mm). Board runs refuse an untied board surface (I4), so the flow is: plan, probe, save with the plan (the web UI sends the planId when the plan shown is for the board selected on the Run tab). Unknown plan: 404. Saving a surface makes plans made on the old one stale (plan again). |
| `GET /api/config` · `PUT /api/config` | partial `DispenseConfig` | Partial update, validated by the engine (`schemaVersion` 3; older files are migrated on load). Pump/valve/watchdog G-code are fixed (not settings: unknown keys). Removed keys (`needleGapMm`, `primeAndTestDot`, `testDotOpticalCheck`, `bFeedDegS`, `retractDeg`, `dwellMs`, `provisionalQMm3PerRev`, ...) are unknown settings (400). `profile`, `purgeRow`, `cleanPadCheck` and `needleXyTrim` are merged **one level deep**: `{"profile":{"dwellMs":300}}` keeps the other profile fields; an unknown nested key is a 400 naming it (`profile.noSuchKnob`). Stamped by the engine, so they must be sent unchanged (or left out): `profile.startAngleSetAtMs` (a changed `profile.startAngleDeg` is stamped with the time it was set), `needleXyTrim.setAtMs` (stamped when dx/dy change), `purgeRow.resetAtMs`, and `purgeRow.usedSpots` (runs reserve
spots, `POST /api/purge-row/reset` frees them; a stale full-settings save must not put paste-covered spots back). The needle frame, offset state and surfaces cannot be changed here. Refused while a run is active. |
| `GET /api/boards` | | `job`: board locations of the job open in OpenPnP's window (position, side, fiducial transform status; empty headless); `files`: OpenPnP's board list (`boards.xml`). |
| `GET /api/packages` | | Packages with footprint pads in mm (for "pick from a footprint"). |
| `POST /api/plan` | `{source:"job", boardLocationId}` or `{source:"file", file, x, y, z, rotation?}` (z required) | Engine `planBoard` / `planBoardFile`. Every dot is one dot of the dispense profile (`rotationDeg` = the profile's net turn). Returns `planId`, `profile` (the profile it was made with) and `profileFingerprint`, `summary` (pads, blocked pads, dots, total rotation, path, `estimatedSeconds` of the whole program (settle, purge row, crossing, dots, vent; moves with acceleration and dwells, no host latency), `estimatedDph` (board dots per hour of that program), `estimatedWorkSeconds` / `estimatedWorkDph` (from the crossing after the purge to the last dot: the bench's DPH window; the UI shows "dots only" and "overall"), `purgeDots`), `pads` (geometry, type, status, stencil-equivalent volume/mass, dots, rotation, flags, blocked), `dots` (order, X/Y, dispense/hop Z, rotation), `warnings`, `interlocks` (pre-flight incl. §5.7 rule, I4 board and purge surfaces, I9–I11), `cleanCheck` / `cleanCheckRunning` (the last clean-pad check of this plan). No `calibration` block: the volumetric q model is gone. The estimate builds the exact program the engine will stream, so soft-limit problems show up here. |
| `GET /api/plan/{planId}` | | A plan from this session (last 20 kept in memory), with fresh interlocks and its last clean-pad check. The fresh interlocks add a `PLAN` violation "… changed after planning …; plan again" when the pad-type gate (`minPadTypeStatus`) or a pad type of the plan (edited, status changed, retired: a new version) changed since it was made, or when any other pad type or package mapping changed (a new type, or a pattern or mapping edit, can resolve the plan's packages to another type); `POST /api/run` is refused the same way. |
| `GET /api/plans` | | The plans kept in memory (last 20), newest first, without the run subsets (`<planId>-subset-…`): `[{planId, boardName, boardLocationId, createdAtMs, source, pads, dots, blockedPads, profile, profileFingerprint}]` (the profile the plan was made with; no dots or interlocks: `GET /api/plan/{id}` for those). A browser opened later shows the latest plan instead of none. |
| `POST /api/plan/{planId}/clean-check` | `{wait?}` | I10 clean-pad guard alone (top camera only, the pressure stays off): the camera visits every distinct dot and measures the share of bright pixels (gray > `cleanPadCheck.grayLevel`) in a `boxWMm × boxHMm` box; a pad is clean at >= `minBrightFraction` (bare copper ≥ 0.90; paste reads dark blue; a mis-registered board puts solder mask under the box, which reads dark too). Result `{planId, ok, complete, targets, dirtyPadIds, pads: [{padId, dotIndex, x, y, brightFraction, clean, error, boxWMm, boxHMm}], grayLevel, minBrightFraction, boxWMm, boxHMm, checkedAtMs}` (`complete` false: stopped or failed part way; the box is smaller than the configured one on pads smaller than it), kept with the plan (`GET /api/plan/{id}` → `cleanCheck`). Without a camera the task ends REFUSED (I10) and the report has no targets. Works whether or not `cleanPadCheck.enabled` is set. |
| `POST /api/screw/start-angle` | `{startAngleDeg?, note?}` | I11. With `startAngleDeg` (0 to < 360, else 400): sets the screw start angle (a new angle changes the profile: plan again). Without: confirms the current angle for this controller power-up. Either way stamps `profile.startAngleSetAtMs`. The angle is `(M114 Count B mod 1600) × 360/1600`, counted since the controller was powered on; a reconnect (`controllerConnectedAtMs`) asks for a confirm, a power cycle for a new measurement. Returns the profile view. |
| `POST /api/purge-row/reset` | | I9: the purge row was wiped: `usedSpots := 0`, stamps `resetAtMs`. Returns the purge-row view. |
| `POST /api/run` | `{planId, maxDots?, padIds?, wait?}` | Engine `runPlan`: profile check, interlocks, estimate, clean-pad check (I10, if enabled; a dirty pad ends the run REFUSED with a message starting `I10:` and the report in `cleanCheck`, before any pressure), then one pressure session: pump, valve, the purge dots on the next unused purge-row spots (reserved before streaming; the first one turns the screw to the start angle from the M114 B step count), a crossing to the board without stopping, every dot, vent. `maxDots`: only the first N dots in path order; `padIds`: only those pads. A subset is a new plan (`<planId>-subset-…`, stored in `pad_plan`), so `runs`/`dot_log` record exactly what was dispensed. Refused (409) if the dispense profile, needle offset, gap or hop changed since planning, if the board's location, fiducial transform or placements changed in OpenPnP (board fingerprint), or if the board surface was re-probed (plan again). |
| `GET /api/runs?limit=` · `GET /api/runs/last` · `GET /api/events?limit=` | | `runs` and `events` tables; the last run with dot timings and streamed G-code. |
| `GET /api/calibrations` | | Current calibration, all calibrations, dot ladder of the current one, last 20 touch-offs. |
| `GET /api/calibrations/{id}/ladder` | | |
| `POST /api/calibrations/scale-shots` | `{rotationDegPerShot, perShotMassMg? (list or "a, b, c"), totalMassMg?, count?, densityMgMm3?, tip?, cartridge?, pasteLot?, setPressurePsi?, barrelTempC?, barrelPressurePsi?, pasteHoursRt?, validForHours?, operator?, notes?, markCharacterized?}` | Engine `recordScaleShots` → new calibration record (q, CV, n). `setPressurePsi` and the screw feed default to the profile's. A record only: boards use the dispense profile. |
| `POST /api/calibrations/scale-dots` | `{calibrationId?, rotationDeg, count, totalMassMg, scaleSdMg?, densityMgMm3?, diameterMeanMm?, diameterCv?}` | Engine `recordScaleDots` → dot-ladder row (mg/dot ± SE). |
| `POST /api/calibrations/shots-run` | `{locationName, rotationDeg, count (1–200), wait?}` | Engine `runShots` (shots into a cup for the scale). |
| `POST /api/calibrations/dot-ladder` | `{rotationsDeg, repeats, pitchMm, locationName?, primeAtWaste?, wait?}` | Engine `runDotLadder` (requires I4 incl. the blank surface, and dot vision). |
| `POST /api/calibrations/touch-off` | `{startGapMm, stepMm, steps, rotationDeg?, pitchMm?, locationName?, primeAtWaste?, jumpRatio?, apply?, wait?}` | Engine `runNeedleTouchOff` (the named I4 waiver; needs the needle frame and the blank surface). `rotationDeg` left out = one dot of the dispense profile (other partial turns vary with the screw angle and hide the squash). Refused if the lowest gap is below −0.1 mm. |
| `GET /api/pad-types` | | Grouped by family in the §5.7 order (Chip, Small-outline discretes, Leaded ICs, No-lead ICs, Custom, others, Retired); per type: current version, status, pad, t, k, `dotsPerPad` (1, or a grid if the pad rule allows more dots on a large pad), `rotationPerDotDeg` (the profile's net turn) and `profileName`, stencil-equivalent volume/mass (information), pad rule, versions, last validated, whether the §5.7 rule lets it be dispensed. |
| `POST /api/pad-types` | `{family, name, padWMm, padHMm, padRoundness?, layout?, pitchMm?, tMm?, k?, status?, packagePattern?, notes?, typeKey?}` | New type (key from family + name if not given) → 201. |
| `GET /api/pad-types/{key}` | | Current + full history. |
| `PUT /api/pad-types/{key}` | any of the fields above | Appends version n+1 (nothing is overwritten). |
| `DELETE /api/pad-types/{key}` | | **Retire** (append-only): new version in family "Retired", status not-started, no package pattern, so it is never matched or dispensed; history kept. |
| `PUT /api/pad-types/{key}/rule` | `{maxDots?, fitFactor?, largestSingleDotPadMm2?, notes?}` | Engine `savePadRule` (versioned). |
| `GET /api/camera/top.jpg?w=` | | JPEG from the head's default (top) camera via `captureTransformed()`: no motion, no light actuation, no scripts; 10 s timeout. |
| `GET /api` | | Route list. |

### `dispense` in `GET /api/status`

| Field | Content |
|---|---|
| `available`, `busy`, `nozzle`, `bZeroed` | Engine state; `bZeroed` = I2. |
| `needle` | `touchedOff`, `zOffsetMm`, `setAtMs`, `changedAtMs`, `gapMm` (the profile's), `anchored`, `anchor` (incl. `rawSumMm`), `anchorOffsetMm`, `xyTrim` `{dxMm, dyMm, setAtMs, note}` (the needle lands at camera + N2 head offset + trim; every dot is moved by minus the trim). |
| `profile` | The dispense profile: `name`, `pressurePsi` (a note: set by hand on the regulator), `netStepsPerDot`, `suckBackDeg`, `startAngleDeg`, `startAngleSetAtMs`, `screwRpm`, `screwAccelDegS2`, `dwellMs`, `gapMm`, `purgeDots`, `notes`; derived: `stepsPerRev`, `stepsPerDeg`, `revolutionsPerDot`, `netDeg` (180.1802 for 800 steps), `forwardDeg` (net + round(suckBackDeg × 4.44) steps: 190.0901), `suckBackSteps` (44), `feedDegPerMin` (rpm × 360: 64800), `partialTurn`, `startAngles` (the angles the dots alternate between, e.g. 297.9 / 117.9 for 298°; null for whole turns), `startAngleNeedsConfirm` (I11), `controllerConnectedAtMs`, `fingerprint`, `problems` (validation, empty if OK). |
| `purgeRow` | `surfaceName`, `x0Mm`, `y0Mm`, `pitchXMm`, `pitchYMm`, `spots`, `usedSpots`, `spotsLeft`, `defined`, `resetAtMs`, `perBoard` (the profile's purge dots), `boardsLeft`, `surfaceProbed`. |
| `cleanPadCheck` | `enabled`, `grayLevel`, `minBrightFraction`, `boxWMm`, `boxHMm` (I10). |
| `surfaces`, `boardSurfaceName`, `locations`, `wasteLocationName`, `blankLocationName` | Probed surfaces (with plane fit and `boundToBoard`) and named places. |
| `primeRotationDeg`, `testDot` | Manual prime default; test-dot offset and `defaultRotationDeg` (one profile dot). |
| `maxContinuousOnTimeS`, `minPadTypeStatus` | I7 limit, §5.7 rule. |

The run view (`lastRun`, `/api/runs/last`, a run's `result`) adds `profile` (the profile used), `cleanCheck`
(the clean-pad report of a board run), `purgeDots`, `bCountAtStart` (M114 B step count before the purge;
the simulator reports none: 0 with a warning) and `workStartAngleDeg` (the screw angle the first board dot
starts at).

Interlock list items: **I1** `ok` only if the driver's DISABLE_COMMAND contains `M410, M107 P3, M107 P2, M107 P1,
M107 P0, M18 B` as one contiguous block (comments and extra lines around it allowed; `detail` lists the lines,
`valveOff`, `pumpOff`, `quickStop`, `stopSequence`, `expected`), otherwise `warning`. **I6** (info): the regulator
pressure of the profile, set by hand. **I9**: purge row set with enough unused spots. **I10**: clean-pad check (needs
the top camera when enabled; `off` when disabled). **I11**: start angle set or confirmed since the controller
connected (`off` for whole turns).

## 4. Pressure hold (Dispense tab)

The engine pressurises inside each run. The hold is the manual "pressure on" for reading the regulator gauge:

- On: pump, wait, valve, settle (fixed G-code, `S255` only). Runs on OpenPnP's machine thread after motion has
  stopped, one line at a time with host-side waits; the state is checked before every line, so a Stop, Pressure off or
  web STOP during the switch-on vents at once and the valve is never switched on after it. If `M115` reports
  `Cap:HOST_WATCHDOG:1`, it arms `M990 S<keepaliveWindowMs>` and pings `M990` every second through the machine thread
  (if that thread is busy longer than the window, the firmware vents: fail-safe).
- Off: valve (vents the barrel), then pump, then `M990 S0` if armed, each sent again if a stale controller error refused
  it. The hold is marked off only once the valve OFF went out; otherwise it reports `vent_failed`, keeps the watchdog
  armed, stops pinging (the firmware then vents by itself) and Pressure off / STOP try again.
- **I7**: switched off automatically after `maxContinuousOnTimeS`.
- **I1**: vented by the web STOP, and by OpenPnP's own Stop/disable (machine listener, before the driver disconnects).
- Prime, test dot, runs and calibrations are refused while a hold is on (they switch the pressure on and off
  themselves; this keeps one owner of the on-time).

## 5. Web UI

Vanilla HTML/CSS/JS in `src/main/resources/org/openpnp/instancelabs/web/` (no build step). Design and behaviour:
`docs/instancelabs/UI_DESIGN.md` (binding spec); module contract: `DEV_NOTES.md` next to the files.

- Frame: a status strip on every screen (machine, pressure with its on-time and auto-off, start angle, purge row,
  the board-run verdict, Connect / Home) with **STOP** at its right edge (F9 from anywhere; a second STOP in the
  context panel while anything is energised); a left navigation of five workspaces; a context panel on the right.
  No native `confirm()` / `alert()` / `prompt()` / modal dialogs: in-page dialogs and two-press buttons instead.
- Files: `index.html` (frame), `app.css` (design tokens light/dark, shared components), `core.js` (`IL`: API client,
  store and 0.7 s status poll, router, the readiness model, strip, STOP and recovery, two-press, fix-its, dialogs, panel
  views), `boardview.js` (the to-scale board SVG), one module per workspace: `run.js` + `run.css`, `setup.js` +
  `setup.css`, `profile.js` + `profile.css` (also used by `calibrate.js` and `records.js`), and `app.js` (boot).
- **Run**: the per-board loop on one screen: the board drawn to scale (pads, dots in path order, done dots, dirty pads,
  probe targets), plan summary with time and DPH (dots only / overall / bench), and the steps Board → Probe → Checks →
  Run → Result in the panel: board and position, probing this seat (`PUT /api/surfaces/board` with the planId, then plan
  again), the nine readiness checks with fix-its, the clean-pad check, the run confirm ("Set the regulator to 18 psi
  (profile …)", gauge / paste / hands-clear ticks), live progress and phases, the result, and Recovery after STOP
  (Connect, the I11 question, Home, purge reset, then finish the pads left or run all).
- **Setup**: bring-up order and the by-hand tools: needle Z frame / offset / XY trim, probed surfaces (incl. the
  advanced point editor), purge row, pressure hold with prime and test dot, places, top camera, profile summary,
  settings with the advanced JSON editor (sends only changed keys).
- **Profile**: the dispense profile form (unsaved bar, field checks as the engine's), screw start angle, pad types by
  family (dialog, history, retire, pad rule).
- **Calibrate**: touch-off, start angle, scale shots, dot batch, dot ladder, records (history and experiments only).
- **Records**: board runs and tasks, activity, calibrations, touch-offs, with a detail panel (done-dot map, dot
  timings of the last run, finish the pads left of a stopped board through Run's confirm).
- A browser opened later restores the plan of a running board, else its own last plan, else the newest plan OpenPnP
  keeps (`GET /api/plans`).

Jargon is explained in tooltips (`title`/`abbr`).

## 6. Tests

- `mvn -T1 test -Dtest='ApiServerSimTest,ReviewSafetySimTest'` (part of `mvn test`): 23 + 7 tests on the engine's simulated machine
  (OpenPnP GcodeDriver over localhost TCP to `RecordingGcodeServer`), real `DispenseService`, real `ApiServer` on an
  ephemeral port. Board runs there use the bench geometry (N1 contact 5.5, K 65.59 → needle 2.91, offset −2.59), a purge
  row on the blank, a confirmed start angle and the clean-pad check off (no camera), or a fake `DotImager`
  (`DispenseService.setDotImager`) for the I10 tests.
- `src/test/web/run-webui-sim-test.sh`: Playwright (headless Chromium) against this jar running in OpenPnP **sim**
  through openpnp-uinav (config copy, simulated controller and cameras, Xvfb, device stubs). Needs the jar
  (`mvn -q -T1 -DskipTests package`), openpnp-uinav built, and Playwright in `$PLAYWRIGHT_DIR`
  (`npm i playwright && npx playwright install chromium-headless-shell`, browsers in `$PLAYWRIGHT_BROWSERS_PATH`).
  It drives the element ids of `UI_DESIGN.md` §16 (two-press buttons, the in-page run confirm, Recovery after STOP).
- `src/test/sim/il_ui_session.py start --jar … --name …`: a seeded SIM session for UI work (machine homed, needle
  frame, blank surface and purge row, start angle, a planned board with its surface tied); `src/test/web/devproxy.py`
  serves the web files from disk against it (no jar rebuild).

## 7. Known limits

- In the OpenPnP simulator the top camera is a static image: the clean-pad check judges that image, so untick
  "Check that the pads are clean before each board (I10)" there (Settings). The simulated controller reports no M114
  B step count: the engine takes 0 (with a warning in the run), so the start angle is only exercised, not physical.
- A board placed by hand (file + position) gets no fiducial correction; use a job board in OpenPnP for that.
  `planBoardFile` adds the file to OpenPnP's board list, which OpenPnP writes to `boards.xml` when it saves.
- Job boards are listed only while OpenPnP's window runs (the job lives in the Swing job panel).
- Plans live in memory (last 20) for "Run what you previewed"; every plan is also stored in `pad_plan`.
- Progress is polled (0.7 s), not pushed.
- DPH from the simulator is not a physical number (the simulated controller has its own timing model).
- Paste working life (I8) is not tracked by the engine; shown as "off".
