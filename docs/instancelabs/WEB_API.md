# Instance Labs web API and web UI (`org.openpnp.instancelabs.api`)

Branch `feat/web-ui`, on top of `feat/dispense` (engine contract: `docs/instancelabs/DISPENSE_API.md`). Updated on
`il/bench-sync` for the bench recipe of 2026-10-28 (dispense profile, purge row, start angle, clean-pad guard, F9 Stop).
Updated on `il/bench-sync-autoreg` (2026-10-29) for Register board, the automatic N1 probe, home at job start and the
Z check (`AUTO_REGISTER_DESIGN.md`, §3.2 below), the KiCad upload and the board drawing (`BOARD_IMPORT_DESIGN.md`,
§3.3), and missing paste profiles (`PROFILE_CALIBRATION_DESIGN.md`, "PC"): the profile check and its suggestions
(§3.4), Calibrate automatically and the by-hand steps with the weigh coupons (§3.5), and their web UI (§5). Written
from the code on `il/pc-integration` (2026-10-29); where the code and PC §12–§13 differ, this file follows the code
and says so. **Not in this PR:** the test-board ladder (PC §20 decision 8, slice `pc-ladder`) is a follow-up; until it
comes, `validated` stays on the existing Validate guide.
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
| 400 | `not-kicad`, `kicad-syntax`, `too-complex`, `no-origin` | board upload: not a board file, a syntax error (line and column), a reader limit, no outline and fewer than 2 fiducials (§3.3) |
| 403 | `forbidden` | Host / Origin / Sec-Fetch-Site guard |
| 404 | `not-found` | unknown endpoint, plan, pad type, calibration, no camera, a board file that is gone (`Board file not found: …`) |
| 404 | `art-gone` | `GET /api/board-art/{id}` of an unknown drawing |
| 405 | `method-not-allowed` | |
| 409 | `refused` | machine state or an interlock refuses; `interlocks: {ok, violations:[{code,message}], notes}` included when the engine's interlocks refused |
| 409 | `busy`, `exists`, `preview-stale`, `ids-exhausted` | board upload (§3.3) |
| 409 | `suggestion-stale`, `status-evidence` | profile check, pad calibrations and pad types (§3.4, §3.5) |
| 409 | `implausible`, `late`, `wrong-step` | a pad calibration's readings: far from the expected mass, typed long after the dots (both saved with `confirm: true`), or an answer to a step the job does not ask (§3.5) |
| 413 | `too-large` | request body over its route's limit: 2 MB, the board uploads 12 MB (with the "unfill zones" hint) |
| 500 | `internal` | a board save or attach failed part way; every step was undone |
| 503 | `unavailable` | engine not running, dispenser unavailable (no N2), camera gave no frame, no top camera for Register board |

Engine texts start with a code the web UI turns into fix-its: `I<n>:`, `MACHINE:`, `CONFIG:`, `PLAN:`, `REG:`
(fiducial registration), `PROBE:` (the N1 probe), `PCAL:` (a pad calibration's engine step). The exact texts are in
`AUTO_REGISTER_DESIGN.md` §6.2 and PC §7.

Every mutating machine action goes through the engine (`DispenseService`) and its interlocks. The API runs the
engine's pre-flight check synchronously so a refusal comes back at once with the reason; the engine re-checks when
it runs (authoritative). Runs return `202 {"started": true}` immediately; add `"wait": true` to block until the run
ends (max 10 min) and get `"result"` (RunResult view).

Every engine endpoint (runs, the clean-pad check, prime, the test dot, the calibrations' shots, dot ladder and
touch-off, Pressure on, Register board, the probes, Check Z, Home, the pad calibration's start, and the profile writes
of §3.4) answers 409 `refused` at once while another dispense task runs or a pad calibration is between its engine
steps, and while a board upload holds the import pipeline (`a board file is being read (an upload takes up to about
20 s): wait for it to finish`; `status.boardImport.reading`). Those that pressurise or move (not the profile writes)
also refuse while the pressure is held. Those that move also wait for OpenPnP's own work (`OpenPnP is busy (homing or
moving): wait for it to finish`; Home included): a task queued behind one of OpenPnP's own that fails would be dropped
(one dropped anyway no longer keeps the engine busy: it ends at the next STOP or engine request). The pad
calibration's preview (`GET /api/pad-calibrations/preview`) always answers 200 and says what Start would refuse in its
`refusal` and `refusalCode` fields (§3.5). Inside the task, on OpenPnP's machine thread, a STOP since it was queued,
a stopped machine and a pressure hold switched on in between refuse it again: the run ends REFUSED (`the pressure hold
is on: Pressure off first`).

## 3. Endpoints

| Method + path | Body | Does |
|---|---|---|
| `GET /api/status` | | Everything the UI polls (every 0.7 s): `server`, `machine` (enabled = connected, homed, busy, drivers, nozzle/camera positions, axes incl. B), `dispense` (see below), `pressure` (hold state, auto-off time, keepalive), `run` (live progress: label, phase, dots done/planned, done dot indexes, live DPH, `pressureOnAtMs` = when the run's pressure session started, i.e. its first pressurised phase `dispensing` / `shots` / `dispensing calibration dots`, null before; `boardNumber` = the number of the board a board run's dots went on, null before its first dot), `lastRun` (run view, incl. `dphMotion`, `dphOverall`, `boardNumber`), `interlocks` (MACHINE, CONFIG, I1–I11 with state ok/blocked/warning/off/info and plain text) |
| `GET /api/machine` | | The `machine` block of `GET /api/status` alone (enabled, homed, busy, home, zCheck, drivers, positions, axes). |
| `POST /api/stop` | | **Always allowed.** Engine `abort()` (before its next line it sends the bench F9 Stop sequence `M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B`: quick stop, dispenser valve and pump off, pick valve and pump off, screw driver off), vents a pressure hold, then OpenPnP's Stop path (`machine.setEnabled(false)`: emergency thread if the machine thread is busy, as OpenPnP's own Stop button). Unless the driver's DISABLE_COMMAND contains that sequence, STOP first gives the engine up to 1 s to send its own lines. Answers within 1.5 s, waits included (the web UI says STOP NOT CONFIRMED after 2 s without an answer): `{stopped, machineEnabled, actions}`. A disable queued behind a dwell (valve settle, up to 3 s) goes on after the answer; `machineEnabled` is then still `true`, an action says so, and `GET /api/status` shows the machine off. |
| `POST /api/machine/enable` | | OpenPnP Start (connects the driver). |
| `POST /api/machine/home` | `{wait?}` | OpenPnP home through the engine (`homeSafely`): when a STOP left N1 low, N1 is first raised Z-only to the travel Z (31.5); a STOP during the home stops the head for good (the drivers are disconnected). The engine times the home for its estimate. Refused while the engine runs, the pressure is held, OpenPnP is busy (`OpenPnP is busy (homing or moving): wait for it to finish`), or the machine is not started. 202 `{started, homing}`; with `wait`: `{homed, n1RaisedFromZ}` (409 `homing failed: …`, e.g. `homing failed: the machine is not started: press Start first` when the machine was stopped before the home task started; 409 `the home did not start or did not end within 5 min: nothing homed; press Home again` when it waited 5 min, e.g. dropped behind one of OpenPnP's own tasks that failed). The home's machine task returns its failure instead of throwing it, so no engine task queued behind it is dropped; a home a STOP or a failure ended leaves no start time behind (the next clean home is `homed`). |
| `POST /api/pressure/start` | | Manual pressure hold: pump ON, wait `pumpToValveSettleMs` (1000 ms), valve ON, wait `valveSettleMs` (3000 ms) (bench `disp.py`; host-side waits, cancellable). Pre-flight = engine interlocks without I4 and the board-run checks I9–I11. A controller error pending at the `M115` probe refuses it. |
| `POST /api/pressure/stop` | | Valve OFF (barrel vents), then pump OFF. |
| `POST /api/prime` | `{rotationDeg?, wait?}` | Engine `runShots(waste, rotation, 1)`: a complete session (pressurise, prime, vent). Default `primeRotationDeg` (360.36° = one screw revolution of 1600 steps). Full I4. Manual only: board runs purge on the purge row. 409 while OpenPnP is busy (§2). |
| `POST /api/test-dot` | `{rotationDeg?, wait?}` | Engine `runShots("waste-test-dot", rotation, 1)`; that place is kept = waste + `testDotOffset`. Default rotation = one dot of the dispense profile (`dispense.testDot.defaultRotationDeg`, 180.1802° for half a turn). Full I4. 409 while OpenPnP is busy (§2). |
| `GET /api/locations` · `PUT /api/locations/{name}` | `{x, y, z}` | Named places: x/y of N2, z = surface Z in OpenPnP's frame (the N1 nozzle Z touching it). `waste-test-dot` is derived and cannot be set. Each place carries `setAtMs` and `homeAtMs` (the Z frame it was set under; null for a place saved before they were kept): after a failed Z check of this home ("Z moved"), a place set before that home is refused (409, I4 `place 'waste' Z 5.500 was taken at 13:40, before the home at 14:02 where Z moved 0.80 mm: take its Z again with N1 touching (Setup › Places)`) by prime, the test dot, shots, the dot ladder's and the touch-off's prime at the waste, a coupon's probe and weigh dots, until it is set again (the same values too). |
| `POST /api/needle/changed` | | I4: blocks dispensing until the needle Z frame is recorded again and the needle is touched off. |
| `POST /api/needle/anchor` | `{surfaceZ, needleContactZ, x?, y?}` or `{surfaceZ, needleContactRawSum, x?, y?}` | I4 needle Z frame: N1 contact Z and needle (N2) contact Z on the same surface; offset := difference. Instead of touching the needle, `needleContactRawSum` K uses the bench relation "needle contact raw Z = K − N1 contact Z" (side camera, 2026-10-27: K = 65.59; with OpenPnP's see-saw map z2 = 63 − raw that is N2 contact = N1 contact − 2.59, e.g. 5.5 → 2.91, offset −2.59 mm). Both at once: 400. The anchor records `rawSumMm`. A touch-off (or a confirmed offset) is still needed. K holds only for the home and the needle it was measured with (a sum of raw Z heights: a home that moved the Z zero moves it by about twice the shift, and a frame from an old K gives the old offset whatever N1 touched): while the last Z check of this home failed (`Z moved`), and after `POST /api/needle/changed`, `needleContactRawSum` answers 409 with `K was measured under another home: Z moved 0.80 mm at the home of 14:02, and K moves by about twice that. Record the needle frame with both tips touching the same spot` or `K was measured with the needle before the change at 13:40: record the needle frame with both tips touching the same spot`; `status.dispense.needle.kRefusal` carries that reason (null when K is accepted). |
| `POST /api/needle/offset` | `{offsetMm, note?}` | Manual needle Z offset (records a `needle_touchoff` row; clears the touched-off part of I4 if within `needleOffsetMaxDriftMm` of the frame). |
| `GET /api/surfaces` · `PUT /api/surfaces/{name}` · `DELETE /api/surfaces/{name}` | `{points: [{x,y,z}] or [[x,y,z]], note?, planId?}` | Probed surfaces in OpenPnP's frame (N1 contact heights), >= 3 points spanning an area; returns the plane fit (coefficients, slope, residuals, Z range), `boundToBoard` and `simulated` (probed on the simulator: refused by I4 on a machine that is not). The automatic probes are `POST /api/surfaces/{name}/probe` and Register board (§3.2); a board surface saved by hand with a `planId` also clears the engine's new-seat flag. `board`: every dot's Z on a board run. `blank`: purge row, dot ladder and touch-off. A zmap.json `points` list is accepted as is. `planId` ties a board surface to that plan's board registration (X/Y/rotation, side, fiducial transform; not placements or the nominal Z): a plan of a re-seated or moved board is then refused by I4 until the board is probed again (bench F3: one seating was tilted 0.9 mm). Board runs refuse an untied board surface (I4), so the flow is: plan, probe, save with the plan (the web UI sends the planId when the plan shown is for the board selected on the Run tab). Unknown plan: 404. Saving a surface makes plans made on the old one stale (plan again). |
| `GET /api/config` · `PUT /api/config` | partial `DispenseConfig` | Partial update, validated by the engine (`schemaVersion` 3; older files are migrated on load). Pump/valve/watchdog G-code are fixed (not settings: unknown keys). Removed keys (`needleGapMm`, `primeAndTestDot`, `testDotOpticalCheck`, `bFeedDegS`, `retractDeg`, `dwellMs`, `provisionalQMm3PerRev`, ...) are unknown settings (400). `profile`, `purgeRow`, `cleanPadCheck`, `needleXyTrim`, `fiducialCheck` and `vacuumProbe` (and `tip`, `paste`, `calibrationArea`, `padCalibration`, PC §5.4) are merged **one level deep**: `{"profile":{"dwellMs":300}}` keeps the other profile fields; an unknown nested key is a 400 naming it (`profile.noSuchKnob`). Stamped by the engine, so they must be sent unchanged (or left out): `profile.startAngleSetAtMs` (a changed `profile.startAngleDeg` is stamped with the time it was set), `needleXyTrim.setAtMs` (stamped when dx/dy change), `purgeRow.resetAtMs`, and `purgeRow.usedSpots` (runs reserve spots, `POST /api/purge-row/reset` frees them; a stale full-settings save must not put paste-covered spots back). The needle frame, offset state (incl. `needleZOffsetHomeAtMs`, the home the touch-off was measured under) and surfaces cannot be changed here. `boardRegistrations` and `newSeatAtMs` may only come back unchanged: `'…' is stamped by the engine: Register board and the new-seat answer set it` (400). `calibrationArea.usedMm`, `calibrationArea.resetAtMs` and `paste.loadedAtMs` are stamped too, and off the simulator the shortened calibration statistics are refused, one text per setting (`padCalibration.minHops below 60 is allowed in the simulator only`; floors: `ladderDotsPerPhase` 12, `minLadderDots` 28, `minHops` 60, `weighTargetMg` 50), unless the stored value was already below; a pad calibration then refuses to start off the simulator while one is below (§3.5). `profile.netStepsPerDot` must be 400, 800 or 1600 (400: `profile netStepsPerDot must be 400, 800 or 1600 steps (a quarter, half or whole turn of the 1600 steps of a revolution): the start angle repeats and a purge dot turns at most one revolution`; a stored profile from before the rule refuses runs with the same text as a `CONFIG` interlock until it is set again). Refused while a run is active. |
| `GET /api/boards` | | `job`: board locations of the job open in OpenPnP's window (position, side, fiducial transform status; empty headless); `files`: OpenPnP's board list (`boards.xml`). Every entry adds `registration` `{id, atMs, nominal}` or null (this board key's stored registration, §3.2) and the drawing fields of §3.3 (`widthMm`, `heightMm`, `fiducials`, `art`, `origin`, `imported`). Read on OpenPnP's thread (an import may be changing the list; 10 s to start). |
| `GET /api/packages` | | Packages with footprint pads in mm (for "pick from a footprint"). Read on OpenPnP's thread. |
| `POST /api/plan` | `{source:"job", boardLocationId}` or `{source:"file", file, x, y, z, rotation?}` (z required), plus `registration?`: `"auto"` (default: this board's stored registration when it still applies to the board and its seat), `"none"`, or a registration id (409 `REG: registration 3f2a… is not stored any more …`, `… belongs to another board or position`, `… was made on the simulator: register again`) | Engine `planBoard(bl, reg)`. A board file that is gone: 404 `Board file not found: …`. A board top `z` more than 2.0 mm from the needle-frame surface: 409 `the board top Z 2.910 is 2.59 mm from the needle-frame surface 5.500 (at most 2.0 mm: …). … Set the board Z again.` Returns, besides the fields below, Register board's fields (§3.2), the board drawing fields (§3.3) and the profile check with its per-pad and per-dot dose fields (§3.4). Every dot is one dot of the dispense profile (`rotationDeg` = the profile's net turn). Returns `planId`, `profile` (the profile it was made with) and `profileFingerprint`, `summary` (pads, blocked pads, dots, total rotation, path, `estimatedSeconds` of the whole program (settle, purge row, crossing, dots, vent; moves with acceleration and dwells, no host latency), `estimatedDph` (board dots per hour of that program), `estimatedWorkSeconds` / `estimatedWorkDph` (from the crossing after the purge to the last dot: the bench's DPH window; the UI shows "dots only" and "overall"), `purgeDots`), `purgeSpotsNeeded` (the purge-row spots a run of it lays, as I9 counts them: the profile's `purgeDots`, and the phase and flush dots of each further dot size, e.g. 8 + 5 = 13 for half and quarter turns; also on `GET /api/plan/{planId}`), `pasted` (the board on the plan's seat as the server keeps it, the same for every browser: `{board: its number this session or null before its first dot, nextBoard, sinceMs, dots, pads: {padId: dots laid}}`; a seat is the job board location, or the board file at its X/Y/rotation, so a plan made again for the same seat is the same board; memory only), `pads` (geometry, type, status, stencil-equivalent volume/mass, dots, rotation, flags, blocked), `dots` (order, X/Y, dispense/hop Z, rotation), `warnings`, `interlocks` (pre-flight incl. §5.7 rule, I4 board and purge surfaces, I9–I11), `cleanCheck` / `cleanCheckRunning` (the last clean-pad check of this plan). No `calibration` block: the volumetric q model is gone. The estimate builds the exact program the engine will stream, so soft-limit problems show up here. |
| `GET /api/plan/{planId}` | | A plan from this session (last 20 kept in memory), with fresh interlocks and its last clean-pad check. The interlocks are the ones Run faces: a home Run does by itself (`homeBeforeRun`) and the Z check after it are notes, not violations; a Z check with no point left to touch stays an I4 violation. The fresh interlocks add a `PLAN` violation "… changed after planning …; plan again" when the pad-type gate (`minPadTypeStatus`) or a pad type of the plan (edited, status changed, retired: a new version) changed since it was made, or when any other pad type or package mapping changed (a new type, or a pattern or mapping edit, can resolve the plan's packages to another type); `POST /api/run` is refused the same way. |
| `GET /api/plans` | | The plans kept in memory (last 20), newest first, without the run subsets (`<planId>-subset-…`): `[{planId, boardName, boardLocationId, createdAtMs, source, pads, dots, blockedPads, profile, profileFingerprint}]` (the profile the plan was made with; no dots or interlocks: `GET /api/plan/{id}` for those). A browser opened later shows the latest plan instead of none. |
| `POST /api/plan/{planId}/clean-check` | `{wait?}` | I10 clean-pad guard alone (top camera only, the pressure stays off): the camera visits every distinct dot and measures the share of bright pixels (gray > `cleanPadCheck.grayLevel`) in a `boxWMm × boxHMm` box; a pad is clean at >= `minBrightFraction` (bare copper ≥ 0.90; paste reads dark blue; a mis-registered board puts solder mask under the box, which reads dark too). Result `{planId, ok, complete, targets, dirtyPadIds, pads: [{padId, dotIndex, x, y, brightFraction, clean, error, boxWMm, boxHMm}], grayLevel, minBrightFraction, boxWMm, boxHMm, checkedAtMs}` (`complete` false: stopped or failed part way; the box is smaller than the configured one on pads smaller than it), kept with the plan (`GET /api/plan/{id}` → `cleanCheck`). Without a camera the task ends REFUSED (I10) and the report has no targets. Refused at its start (a STOP since it was queued, the machine not started, the pressure hold switched on: `the pressure hold is on: Pressure off first`), the report is empty (`ok` false, no pads, `checkedAtMs` 0) and the reason is the run's (`status.run`, `GET /api/runs/last`). 409 while OpenPnP is busy (§2). Works whether or not `cleanPadCheck.enabled` is set. |
| `POST /api/screw/start-angle` | `{startAngleDeg?, note?}` | I11. With `startAngleDeg` (0 to < 360, else 400): sets the screw start angle (a new angle changes the profile: plan again). Without: confirms the current angle for this controller power-up. Either way stamps `profile.startAngleSetAtMs`. The angle is `(M114 Count B mod 1600) × 360/1600`, counted since the controller was powered on; a reconnect (`controllerConnectedAtMs`) asks for a confirm, a power cycle for a new measurement. Returns the profile view. |
| `POST /api/purge-row/reset` | | I9: the purge row was wiped: `usedSpots := 0`, stamps `resetAtMs`. Returns the purge-row view. |
| `POST /api/run` | `{planId, maxDots?, padIds?, wiped?, wait?}` | Home first when needed (`homeBeforeRun`, §3.2), then the Z check when Z data predate the last home, then engine `runPlan`: profile check, interlocks, estimate, clean-pad check (I10, if enabled; a dirty pad ends the run REFUSED with a message starting `I10:` and the report in `cleanCheck`, before any pressure), then one pressure session: pump, valve, the purge dots on the next unused purge-row spots (reserved before streaming; the first one turns the screw to the start angle from the M114 B step count), a crossing to the board without stopping, every dot, vent. `maxDots`: only the first N dots in path order, whole pads (a pad's dose is all its dots): the cut falls before the pad it would split, so the 202's `dots` can be fewer than asked, and the subset's note says so (`subset of plan <id>: 2 of 5 dots on 1 of 3 pads (path order kept; whole pads, so 2 of the 3 dots asked for)`); fewer than the first pad's dots is a 400 (`a test run lays whole pads: the first pad, A.1, has 2 dots, so ask for at least 2`). `padIds`: only those pads. A subset is a new plan (`<planId>-subset-…`, stored in `pad_plan`), so `runs`/`dot_log` record exactly what was dispensed. Refused (409) if the dispense profile, needle offset, gap or hop changed since planning, if the board's location, fiducial transform or placements changed in OpenPnP (board fingerprint), if the plan's registration no longer applies (`the board's registration or position changed since this plan was made (…): plan the board again`), if the board surface was re-probed (plan again), if OpenPnP is busy (`OpenPnP is busy (homing or moving): wait for it to finish`), or if Z must be checked and no point measured before the home is left to touch. The 202 adds `homeFirst`, `homeWhyCode`, `homeWhy`, `homeSinceMs`, `homeEstimateS`, `zCheckFirst`, `zCheckReference` `{name, x, y, z}` or null; the run view adds `homedFirst` and `zCheck`. `wiped: true`: the operator wiped the board, so the server forgets the paste on its seat (`plan.pasted`) before the run; nothing is refused without it (the web UI asks `I wiped this board` when a run lays pads that have paste). |
| `GET /api/runs?limit=` · `GET /api/runs/last` · `GET /api/events?limit=` | | `runs` and `events` tables; the last run with dot timings and streamed G-code. |
| `GET /api/calibrations` | | Current calibration, all calibrations, dot ladder of the current one, last 20 touch-offs. |
| `GET /api/calibrations/{id}/ladder` | | |
| `POST /api/calibrations/scale-shots` | `{rotationDegPerShot, perShotMassMg? (list or "a, b, c"), totalMassMg?, count?, densityMgMm3?, tip?, cartridge?, pasteLot?, setPressurePsi?, barrelTempC?, barrelPressurePsi?, pasteHoursRt?, validForHours?, operator?, notes?, markCharacterized?}` | Engine `recordScaleShots` → new calibration record (q, CV, n). `setPressurePsi` and the screw feed default to the profile's. A record only: boards use the dispense profile. |
| `POST /api/calibrations/scale-dots` | `{calibrationId?, rotationDeg, count, totalMassMg, scaleSdMg?, densityMgMm3?, diameterMeanMm?, diameterCv?}` | Engine `recordScaleDots` → dot-ladder row (mg/dot ± SE). |
| `POST /api/calibrations/shots-run` | `{locationName, rotationDeg, count (1–200), wait?}` | Engine `runShots` (shots into a cup for the scale). 409 while OpenPnP is busy (§2). |
| `POST /api/calibrations/dot-ladder` | `{rotationsDeg, repeats, pitchMm, locationName?, primeAtWaste?, wait?}` | Engine `runDotLadder` (requires I4 incl. the blank surface, and dot vision). 409 while OpenPnP is busy (§2). |
| `POST /api/calibrations/touch-off` | `{startGapMm, stepMm, steps, rotationDeg?, pitchMm?, locationName?, primeAtWaste?, jumpRatio?, apply?, wait?}` | Engine `runNeedleTouchOff` (the named I4 waiver; needs the needle frame and the blank surface). `rotationDeg` left out = one dot of the dispense profile (other partial turns vary with the screw angle and hide the squash). Refused if the lowest gap is below −0.1 mm, and while OpenPnP is busy (§2). After a failed Z check it runs once the needle frame and the blank were measured again under the same home (§3.2). `pitchMm` left out = 1.6 mm, or the smallest pitch for larger dots (to 0.05 mm); a pitch below the expected dot diameter (dose model) + 0.4 mm is a 400 (`the touch-off pitch 1.00 mm is too small for these dots: a half-turn dot is about 1.16 mm across, …; use a pitch of at least 1.56 mm (the dot + 0.40 mm)`). 202 adds `pitchMm`. Each dot is found at its spot with the paste strings cut off; `analysis` adds `dotStates` (`dot` \| `none` \| `unmeasured`) and `dotNotes` (why paste at a spot was not measured, e.g. `joined to the dot at gap 0.200 mm`). Paste it cannot measure before the contact refuses the conclusion (`reason` `dots touch or are strung together: the paste at gap 0.400 mm is joined to the dot at gap 0.350 mm; use a larger pitch`), as do first two dots that are not both there and within `jumpRatio` of each other (a contact needs two steady dots before it) or that are under 60 % of the steady dot of their dose (droplets from a needle too high: `the first two dots (0.400 mm at gap 0.125, 0.420 mm at gap 0.100) are under 60 % of the 1.16 mm a steady dot of this dose makes on the blank: …; run the ladder again from a lower start gap`); nothing is recorded or applied then (the run ends OK with the reason as its message). |
| `GET /api/pad-types` | | Grouped by family in the §5.7 order (Chip, Small-outline discretes, Leaded ICs, No-lead ICs, Custom, others, Retired); per type: current version, status, pad, t, k, `dotsPerPad` (1, or a grid if the pad rule allows more dots on a large pad; the type's own when it has a dose), `rotationPerDotDeg` (the profile's net turn, or the type's `dotSteps`) and `profileName`, stencil-equivalent volume/mass (information), pad rule, versions, last validated, whether the §5.7 rule lets it be dispensed. |
| `POST /api/pad-types` | `{family, name, padWMm, padHMm, padRoundness?, layout?, pitchMm?, tMm?, k?, status?, packagePattern?, notes?, typeKey?}` | New type (key from family + name if not given) → 201. Also `dotSteps?`, `dotsPerPad?`, `calAreaMm2?`, `origin?` (`manual` \| `suggested`) and `mapping?` `{packageId, padNames?}` (written with the type); a dose below the tip's smallest is a 400; raising a type whose own dose differs from the profile's to characterized needs a fresh camera check and a confirmed weighing at that dose, and to validated a characterized version at that dose (409 `status-evidence`). |
| `GET /api/pad-types/{key}` | | Current + full history. |
| `PUT /api/pad-types/{key}` | any of the fields above | Appends version n+1 (nothing is overwritten). A change of the pad or the dose writes origin `manual` and drops the calibration it had (`doseFp`, `massPerPadMg`). |
| `DELETE /api/pad-types/{key}` | | **Retire** (append-only): new version in family "Retired", status not-started, no package pattern, so it is never matched or dispensed; history kept. |
| `PUT /api/pad-types/{key}/rule` | `{maxDots?, fitFactor?, largestSingleDotPadMm2?, notes?}` | Engine `savePadRule` (versioned). |
| `GET /api/camera/top.jpg?w=` | | JPEG from the head's default (top) camera via `captureTransformed()`: no motion, no light actuation, no scripts; 10 s timeout. A JVM without a JPEG writer (seen in real mode, session 2) answers a PNG with its own content type. |
| `POST /api/plan/{planId}/register` · `GET /api/plan/{planId}/registration-targets` | | Register board and its preview (§3.2). |
| `POST /api/surfaces/{name}/probe` · `GET /api/surfaces/{name}/probe-targets` | | The automatic N1 probe of the blank (§3.2). |
| `POST /api/machine/z-check` · `POST /api/board/new-seat` | | Check Z after a home; the engine's new-seat flag (§3.2). |
| `POST /api/boards/import` · `…/save` · `…/attach` · `GET /api/board-art/{id}` | raw `.kicad_pcb` bytes | KiCad upload, preview, save, attach, and the board drawings (§3.3). |
| `GET /api/pad-profile/check` · `POST /api/pad-profile/apply` · `GET/POST /api/package-mappings` · `POST /api/paste` · `GET/PUT /api/calibration-area` · `POST /api/calibration-area/reset` | | Profile check and its suggestions, mappings, paste and the calibration area (§3.4). |
| `GET /api/pad-calibrations/preview` · `POST /api/pad-calibrations` · `GET /api/pad-calibrations/current` · `GET /api/pad-calibrations/{id}` · `…/{id}/answer` · `…/{id}/cancel` · `…/{id}/crops/{crop}.png` · `GET /api/pad-calibrations` · `GET /api/dose-records` · `GET /api/coupons/pending` · `POST /api/coupons/{recordId}/reading` | | Calibrate automatically, the by-hand ladder and weighing, the readings, the records (§3.5). |
| `GET /api/sim/balance` · `POST /api/sim/balance/load` · `GET /api/sim/paste/deposits` · `POST /api/sim/paste/wipe` | | The simulator's balance and paste, for the operator of a SIM test; refused unless the controller is OpenPnP's simulator (§3.5). |
| `GET /api` | | Route list. |

### 3.1 `GET /api/status`

`dispense`:

| Field | Content |
|---|---|
| `available`, `busy`, `nozzle`, `bZeroed` | Engine state; `bZeroed` = I2. |
| `needle` | `touchedOff`, `zOffsetMm`, `setAtMs`, `changedAtMs`, `gapMm` (the profile's), `anchored`, `anchor` (incl. `rawSumMm`), `anchorOffsetMm`, `xyTrim` `{dxMm, dyMm, setAtMs, note}` (the needle lands at camera + N2 head offset + trim; every dot is moved by minus the trim). |
| `profile` | The dispense profile: `name`, `pressurePsi` (a note: set by hand on the regulator), `netStepsPerDot`, `suckBackDeg`, `startAngleDeg`, `startAngleSetAtMs`, `screwRpm`, `screwAccelDegS2`, `dwellMs`, `gapMm`, `purgeDots`, `notes`; derived: `stepsPerRev`, `stepsPerDeg`, `revolutionsPerDot`, `netDeg` (180.1802 for 800 steps), `forwardDeg` (net + round(suckBackDeg × 4.44) steps: 190.0901), `suckBackSteps` (44), `feedDegPerMin` (rpm × 360: 64800), `partialTurn`, `startAngles` (the angles the dots alternate between, e.g. 297.9 / 117.9 for 298°; null for whole turns), `startAngleNeedsConfirm` (I11), `controllerConnectedAtMs`, `fingerprint`, `problems` (validation, empty if OK). |
| `purgeRow` | `surfaceName`, `x0Mm`, `y0Mm`, `pitchXMm`, `pitchYMm`, `spots`, `usedSpots`, `spotsLeft`, `defined`, `resetAtMs`, `perBoard` (the profile's purge dots), `boardsLeft`, `surfaceProbed`. |
| `cleanPadCheck` | `enabled`, `grayLevel`, `minBrightFraction`, `boxWMm`, `boxHMm` (I10). |
| `surfaces`, `boardSurfaceName`, `locations`, `wasteLocationName`, `blankLocationName` | Probed surfaces (with plane fit and `boundToBoard`) and named places. |
| `primeRotationDeg`, `testDot` | Manual prime default; test-dot offset and `defaultRotationDeg` (one profile dot). |
| `maxContinuousOnTimeS`, `minPadTypeStatus`, `refuseFlaggedPads` | I7 limit, §5.7 rule; whether a plan with pads that cannot be dispensed is refused (`true`, the default) or those pads are skipped (`false`: the plan's interlocks note `… flagged pad(s) will be skipped`). |
| `homeBeforeRun`, `newSeatAtMs` | `whenNeeded` \| `always`; the engine's new-seat flag (§3.2), null when cleared. |
| `pasteVersion` | Changes when the server numbers a board, takes the boards off (`POST /api/board/next`) or forgets a wiped board's paste: a page reads its plan's `pasted` again. |

Also added for Register board and the home at run:

| Field | Content |
|---|---|
| `server.simulated`, `server.simulatorConfirmed` | Every driver is a loopback TCP link; the controller answered `M115` as OpenPnP's simulator (the SIM camera and vacuum need this). |
| `machine.home` | `{needed, whyCode, why, sinceMs, policy, homedAtMs, estimateS, n1Z, n1Low, lowTool}`. `whyCode`: `always` (the setting), `home-unconfirmed` (a stop came during the last home), `stopped` (stopped or reconnected since the last home), `not-homed`, `homed`. `estimateS`: the last home the engine timed, 25 s before the first. `n1Low`: a STOP left a tool outside z1's safe zone; the next home moves Z to the travel Z first. `lowTool` (null when not): `N1` (N1 under its safe zone: a STOP during a probe), or `needle` (N1 above it: on the shared Z axis the needle is low, a STOP during board dots, e.g. N1 at 60.06 and the needle at 2.94 over the pad). The web UI names that tool, its Z and the pad under the needle (`The needle (N2) stopped low (Z 2.94 mm) at R3.1: … Home lifts it first, then homes.`). |
| `machine.zCheck` | `{needed, stale: [{what, atMs}], homeAtMs, reference: {name, x, y, z} \| null, last, estimateS, touches, touchesAfterHome, places: [{name, atMs}]}` (`places`: after a failed check of this home, the places set before it, which are refused until set again; the derived `waste-test-dot` aside): the Z data measured before the last home and the point the check would touch. `what`: `the needle frame`, `the touch-off`, `the '<name>' surface` (the needle tasks' scope: the blank and the board). A needle frame recorded after the home replaces the touch-off (no longer listed; the needle item asks for a touch-off again: `needle Z offset has not been set since the last needle change / needle frame (run a touch-off)`). `last` `{atMs, homeAtMs, refName, x, y, refZ, z, deltaMm, pass, covers}`: a failed check of this home (`pass` false) is "Z moved" until every datum listed is measured again. The reference may be one of the blank's points that a probe after a home replaced (`GET /api/config` → `surfaces.blank.replaced {points, setAtMs, homeAtMs}`, one frame kept). |
| `boardImport` | `{reading}`: a board upload holds the import pipeline (a preview, save or attach reads its file); every engine endpoint refuses meanwhile (§2), and the web UI locks them. |
| `run.detail` | Structured progress. Register board: `{stage, stepsDone, stepsPlanned, estimatedSecondsLeft, home: {needed, whyCode, why, estimateS}, fiducials: [{name, expected, found, state, iterations, frames}], seat, movedMm, probes: [{n, x, y, state, z, lastZ, alternate}]}`; the blank probe: the same without fiducials and seat; a board run: `{stage: home \| z check \| dispense, home, zCheck}`. |
| `run.nextPlanId` | Register board: the plan made at its end (another page loads it once). |
| `run.label` | Adds `board registration`, `blank probe`, `z check` (`runs` kinds `board-registration`, `surface-probe`, `z-check`), and `pad calibration` while a pad calibration's engine step runs (`runs` kinds `pad-ladder`, `pad-weigh`; `run.detail` `{stage, home, blocks, dots, stepsDone, stepsPlanned, estimatedSecondsLeft}`). |
| `padCalibration` | The pad calibration of this session (running, waiting for the readings, or the last one that ended), else null: the job view of §3.5. Next to `run`; a second browser shows the same job. |

The run view (`lastRun`, `/api/runs/last`, a run's `result`) adds `profile` (the profile used), `cleanCheck`
(the clean-pad report of a board run), `purgeDots`, `bCountAtStart` (M114 B step count before the purge;
the simulator reports none: 0 with a warning), `workStartAngleDeg` (the screw angle the first board dot
starts at), and `homedFirst`, `zCheck` (a board run's home and Z check) and `nextPlanId` (Register board).

Interlock list items: **I1** `ok` only if the driver's DISABLE_COMMAND contains `M410, M107 P3, M107 P2, M107 P1,
M107 P0, M18 B` as one contiguous block (comments and extra lines around it allowed; `detail` lists the lines,
`valveOff`, `pumpOff`, `quickStop`, `stopSequence`, `expected`), otherwise `warning`. **I6** (info): the regulator
pressure of the profile, set by hand. **I9**: purge row set with enough unused spots. **I10**: clean-pad check (needs
the top camera when enabled; `off` when disabled). **I11**: start angle set or confirmed since the controller
connected (`off` for whole turns).

### 3.2 Register board, probes, home and Z check (`AUTO_REGISTER_DESIGN.md` §7)

Endpoint group `RegistrationEndpoints`. Every task here is refused at once (409) while the engine runs, the pressure is
held or OpenPnP is busy; inside the task, on OpenPnP's machine thread, a hold that came on in between refuses it again
(`the pressure hold is on: Pressure off first`). A STOP stops each of them; N1 then stays where `M410` stopped it,
vented, and the next home raises it first (`machine.home.n1Low`).

| Method + path | Body | Does |
|---|---|---|
| `POST /api/plan/{planId}/register` | `{wait?, probe?: "auto" \| "always" \| "never"}` | Home if needed, find the fiducials with the top camera, decide the seat, probe the board with N1 (or, for the same seat, touch 3 stored points), save the surface tied to the registration, and plan again. Refused before anything moves (409, or 503 without a top camera): plan unknown (404), the plan's own registration no longer applies (plan again), the board bottom-up, fewer fiducials than `fiducialCheck.minFiducials` (except probe only, below), the basic interlocks with home-first (`Register board refused by the interlocks: MACHINE: machine is not enabled`), and, unless `probe: never`, the probe window (`PROBE:` / `I4:` texts). 202 `{kind: "board registration", planId, started, home, fiducials, probeTargets, probe, probeOnly, estimatedSeconds}` (the total, s); with `wait` also `finished` and `result` (the registration view below). **Probe only** (F10): a board without fiducials, on a plan without a registration (`registration: "none"`, or `auto` with nothing stored), with `probe: "always"`: home if needed, look at and N1-probe the board's targets where the plan puts the board (X/Y as planned, no fiducial fit, no registration stored), save the surface tied to that seat (a plan of the board at another X/Y, rotation or side is refused by I4 until it is probed there) and plan again; the result has `probeOnly: true`, `probe.why` `no fiducials: probed only, X/Y as planned` and the message `Registered HH:MM: no fiducials: probed only, X/Y as planned; board probed at N points.` Other probe modes keep the fiducial refusal, which then adds ` Or probe it only, where it is planned: Register with probe "always".`. A board with fiducials is never probed only: with enough it registers by them, with too few it is refused (no way out offered). |
| `GET /api/plan/{planId}/registration-targets` | | What Register board would do now: `{fiducials: [{name, bx, by, x, y, searchRadiusMm}], probes: [{n, bx, by, x, y, alternates: [{bx, by, x, y}]}], home, fiducialCount, frame: {anchorZ, anchorAtMs, anchorX, anchorY, ok, message}, window: {nominalZ, refZ, startZ, floorZ}, error, probeOnly, estimatedSeconds: {home, fiducials, sameSeat, probe, total}}`. `probeOnly`: the board has no fiducials (`error`) and the plan no registration, so Register with `probe: "always"` probes it only. `window` is absent when `frame.ok` is false. The plan view carries the same block as `registrationTargets`, computed when the plan was made. Besides the needle-frame checks, `frame.message` refuses a board top so far under the needle frame that the probe's floor (1.5 mm under the higher of the two) lies at or above it (planning accepts up to 2.0 mm): `PROBE: the board top Z 3.500 lies 2.000 mm under the needle-frame surface 5.500, and the probe goes at most 1.5 mm under the higher of the two (down to Z 4.000): it cannot reach that height. Set the board top Z to roughly the N1 contact height, or record the needle frame again; if both are right, probe by hand` (also the 409 of `POST …/register`; the web UI offers the Board step, the Needle Z frame and Do it by hand). |
| `POST /api/surfaces/{name}/probe` | `{wait?, targets?: [[x, y], …] (3 to 20, N1 tip, machine mm), z?}` | The automatic N1 probe of a needle-frame surface (the blank): home if needed, look at each spot, touch it, fit the plane, save it. Without `targets` they lie 2 mm either side of the purge row; `z` (else the place's Z) is the nominal N1 contact height. The board surface is refused (`PROBE: the 'board' surface is probed by Register board (Run › Register)`), and so is a surface with neither a place nor `z`. 202 `{kind: "blank probe", surface, targets: [{n, x, y, alternates: [{x, y}]}], window, home, estimatedSeconds}`; with `wait`, `result` = `{ok, status, message, runId, stoppedStage, home, surfaceName, window, points, plane: {a, b, c, maxResidualMm, slopeMmPerMm, minZ, maxZ, coversSpots, spots}, saved, warnings, surface}`. |
| `GET /api/surfaces/{name}/probe-targets?z=` | | `{targets, window, covers: {spots, spotsCovered, places}, home, estimatedSeconds, error}`: the targets, the probe window and how many purge spots their area covers. |
| `POST /api/machine/z-check` | `{wait?}` | One N1 touch per Z frame among the stale data (a frame is the home a datum was measured under: usually one touch, two when, e.g., the board was probed under a later home than the needle frame), each at a point measured before the last home (the tied board surface, the blank, the needle-frame X/Y), compared with its stored Z (tolerance 0.03 mm); a point with paste on it is skipped for the next stored one. 202 `{kind: "z check", needed, reference: {name, x, y, z} \| null, touches, estimatedSeconds}` (`estimatedSeconds` = touches × 17 s); with `wait`, `result` is the run view with `zCheck`. With no point left to touch the task ends REFUSED. A check that fails ends REFUSED with `I4: Z moved 0.80 mm at the home of 14:02: N1 touched the blank at X …, Y … at Z …, where it touched … at 13:40. The needle frame, the touch-off and the surfaces are off by this (the needle by about twice). Record the needle frame again with both tips (Setup › Needle Z frame), probe the blank, take the places' Z again with N1 (Setup › Places), touch off and register the board again.`; every task whose Z data predate the home is refused with that text until they are measured again, in that order, under the same home (`machine.zCheck.stale` and `machine.zCheck.places` drop each; the web UI's Z check blocker offers the next step). |
| `POST /api/board/next` | | "Next board": the boards on the machine are taken off: the server forgets every seat's paste, and the next board dot starts the next board number. `{nextBoard}`. 409 while a task runs. |
| `POST /api/board/new-seat` | `{newSeat: true \| false}` | The engine's new-seat flag: the next registration of any board is a new seat (Next board, "Yes: new seat"). `{newSeatAtMs}` (null when cleared). 400 when `newSeat` is not a boolean; 409 while a task runs. Register board clears it when it saves a probed surface. |

**The plan view** adds:

- `registration`: the registration the plan was made with, or null: `{id, atMs, method, fiducials: [{name, x, y}],
  rotationDeg, scaleX, scaleY, shearDeg, simulated, lastCheckedAtMs, lastCheckMovedMm, beforeNextBoard}`
  (`beforeNextBoard`: made before the new-seat flag was set);
- `registrationTargets` (above), `registrationRun` (the registration view of this plan's last Register board) and
  `registrationRunning`.

**The registration view** (`result`, `registrationRun`): `{ok, status, message, runId, atMs, simulated,
stoppedStage, home, fiducials, fit, seat, movedMm, previousAtMs, registrationId, registrationStored, sameSeat, probe,
newPlanId, probeOnly, warnings}` (probe only: `fiducials` empty, `fit`, `seat` and `registrationId` null):

- `home` `{needed, whyCode, why, done, seconds, n1RaisedFromZ}`;
- `fiducials[]` `{name, board: {x, y}, expected: {x, y}, found: {x, y} | null, state, iterations, frames, offsetPx,
  offsetFromExpectedMm, movedMm, searchRadiusMm}`; the search radius bounds the distance from `expected`;
- `fit` `{method (affine-3 | affine-lsq-N | similarity-2), affine, rotationDeg, scaleX, scaleY, shearDeg,
  maxResidualMm, limits, previous}`; `seat` `first` | `same` | `reseated`;
- `sameSeat` `{points: [{n, x, y, bx, by, storedZ, z, deltaMm, pass}], pass, zCheckStamped, note}`;
- `probe` `{mode, needed, why, skipped, window, points, plane, saved, surfaceSetAtMs}`. `why`: `first registration of
  this board` | `re-seated` | `a new seat was announced` | `no surface tied to this registration` | `same seat: 3
  stored points touched again` | `same seat, surface changed: probed again` | `probe: always` | `registration only
  (probe: never)` | `no fiducials: probed only, X/Y as planned`;
- each probe point `{n, board: {x, y} | null, x, y, z, state, alternate, baseline, drop, fine, lastOpenZ, retried,
  notes, look: {bare, foreignShare, ringSpread, reason}, failure}`;
- `stoppedStage`: `home` | `fiducials` | `same-seat` | `probe` | `save` | `plan`.

At its end Register board plans the board again with the registration (`newPlanId`, also `run.nextPlanId`), unless a
same seat kept its surface and the plan already carries both. Registrations are kept in `dispense-config.json`
(`boardRegistrations`, the latest per board key `file:<path>` or `job:<id>`, at most 20) and logged as `REG` events.

### 3.3 Board upload, drawings and the plan's board (`BOARD_IMPORT_DESIGN.md` §3.2, §4)

Endpoint group `BoardImportEndpoints`. The body is the raw `.kicad_pcb` file (any content type; JSON is refused), at
most 12 MB. One file is read at a time (409 `busy`), never while the engine is busy or a pad calibration is between its
engine steps (409 `Upload the board when the run has finished.`, checked once the upload holds the import pipeline;
while it holds it every engine endpoint refuses, §2, `status.boardImport.reading`), and the browser keeps the file:
save and attach send the same bytes again with the preview's `sha256` (and `decisions`). A footprint with a paste
opening outside the board outline's box (a spare parked next to the board) is drawn only (`skipped.artOnly`,
`footprints[].kind` `art`, `why` `outside the board`) with one `OFF_BOARD` warning in group 3 (`R2 is not inside the
board outline: it gets no paste (its paste would land off the board). Move it onto the board in KiCad if it is part of
this board.`; plural `R2, R3 are not inside …: they get no paste (their paste …)`). A pad turned 180° gives one
package signature at any footprint angle.

| Method + path | Query | Does |
|---|---|---|
| `POST /api/boards/import` | `file` (the browser's file name), `pasteDnp?` (0 \| 1) | The preview: `{file: {name, bytes, sha256}, decisions, kicad: {version, generator, generatorVersion, major}, board: {widthMm, heightMm, outline, origin, originKicad}, suggestedName, slug, exists, pasteDnp, counts, placements, fiducials, skipped: {bottom, artOnly, dnp, pasteOnly}, packages, parts, warnings, attach, art}`. A package: `{baseId, variant, id, state (new \| reuse \| suffixed), why, usesPadTypesOf, lib, kicadName, refs, fiducial, bodyW, bodyH, pads, padTypes, untypedPads, outcome}`; a placement: `{id, ref, value, type, enabled, x, y, rotation, packageId, partId, pads, why, outcome}` with `outcome` `paste` \| `no-pad-type` \| `not-dispensed`. |
| `POST /api/boards/import/save` | `file`, `name` (1 to 60 characters, a letter or digit), `replace` (0 \| 1), `sha256`, `decisions`, `pasteDnp?` | Writes the board file, the drawing and the upload under `<configDir>/instancelabs/boards/<slug>/`, and OpenPnP's parts, packages and board list, in one transaction that undoes every step on a failure. 201 `{file, name, slug, replaced, packages, parts, art: {id, kind}, counts, warnings, boards}` (`boards` = `GET /api/boards`). |
| `POST /api/boards/import/attach` | `file`, `board` (a listed board file), `name?`, `replace`, `sha256`, `pasteDnp?` | Gives the KiCad drawing to an existing board file whose placements match (e.g. `ftp.board.xml`); the board file is not changed. 201 `{slug, name, board, art, maxOffsetMm, offsets, replaced, boards}`. |
| `GET /api/board-art/{id}` | | The drawing JSON of an art id (`BOARD_IMPORT_DESIGN.md` §4.1), `Cache-Control: private, max-age=31536000, immutable` (content addressed). 404 `art-gone`: `This board drawing is no longer known: plan the board again`. |

Errors besides the common ones: 400 `bad-request` (`The upload is empty.`, `Send the file itself, not JSON.`, a bad
name, `The file changed since the preview: upload it again.`), `not-kicad`, `kicad-syntax` (`Line 1203, column 17:
…`), `too-complex`, `no-origin`; 409 `exists` (`A board named "ftp" exists (imported 13:02): replace it?`, with
`exists`), `preview-stale` (OpenPnP's packages or parts changed since the preview; carries the new `preview`),
`ids-exhausted`, `refused` (OpenPnP's window did not respond within 10 s; attach: not listed, or no longer matching);
413 `too-large`; 500 `internal` (`Could not save the board: …. Nothing was changed.`).

**`GET /api/boards`** entries add `widthMm`, `heightMm`, `fiducials: [{id, bx, by}]`, `art: {id, kind (kicad |
fallback), note}` (a note when the board changed since its KiCad drawing), `origin` and `imported: {slug, name, atMs,
sourceName, kicadVersion, how, board}`. A board without KiCad art gets a drawing made from the board file.

**The plan view** adds `board` `{name, file, widthMm, heightMm, side, toMachine (the board → machine affine, 6
numbers), transformStatus, fiducials: [{id, partId, bx, by, x, y}], art: {id, kind, note} | null}` (the drawing is
fixed when the plan is made, also by Register board and plan-again), `dot` `{diameterMm, source (ladder | none)}` (the
dot size at the plan's turn from the current calibration's ladder), per pad `padName`, `partId`, `roundness`,
`typeRule` `{kind, detail, matched, sentence}` (the rule that chose its pad type, in words), and per dot `ta` / `te`
(arrive and end, s from the program start: the path preview's clock).

### 3.4 Profile check and package mappings (`PROFILE_CALIBRATION_DESIGN.md` §12.1)

Endpoint group `PadProfileEndpoints`. The writes are refused while the engine is busy or a pad calibration runs. Where
the code differs from PC §12.1, PC §21 lists it.

| Method + path | Body / query | Does |
|---|---|---|
| `GET /api/pad-profile/check` | `planId` or `boardFile` (a board file OpenPnP lists; 409 otherwise) | The profile check (PC §3.5): `{ok, fixable, tipBlocking, notes, computedAtMs, tip: {key, name, odMm, minDotSteps, minDotMm}, anchors, groups, pastePartialPackages, source}`. A group: `{group, label, packageIds, padNames, allPadsOfPackage, refs, parts, pads, padIds, aperture, family, minPitchMm, minEdgeGapMm, hopMm, typeKey, typeVersion, typeStatus, typeRule, reason, tipKind, outsideKind, bridgeTo, nearOf, partial, blocking, fixable, short, reasonText, suggestion}`; `reason` `tip` \| `part` \| `no-pad-size` \| `no-type` \| `below-gate` \| `bridge` \| `outside`; a suggestion's `kind` `use` \| `calibrate-existing` \| `new` \| `none`. |
| `POST /api/pad-profile/apply` | `{planId? \| boardFile?, groups: [ids] \| "all", choice: "use" \| "save", suggestionIds: {group: id}, operator?}` | `use` writes the mappings of the groups whose suggestion is an existing pad type; `save` also saves the new and calibrate-existing suggestions as provisional pad type versions with their mappings. Nothing is written unless every chosen suggestion is still current (409 `suggestion-stale` with the new `profileCheck`). A plan source is planned again: `{applied: [{group, kind, typeKey, version, mapping, padType}], skipped: [{group, why}], newPlanId, planStale, planError}`. |
| `GET /api/package-mappings` | | `[{packageId, padNames, typeKey, version, atMs}]` (the current mapping per package and pad-name set). |
| `POST /api/package-mappings` | `{packageId, padNames?, typeKey?: "…" \| null, pastePartial?}` | Maps a package, or some of its pads, to a pad type (null clears), and / or sets decision 4's option "paste the dispensable pads of this part anyway". `{mapping, pastePartial, padTypesFingerprint, note: "Plans made before this are stale: plan again."}`. |
| `POST /api/paste` | `{key?, name?, lot?}` | "New paste loaded": names the paste and stamps `loadedAtMs` (older dose records stop counting). |
| `GET /api/calibration-area` · `PUT /api/calibration-area` · `POST /api/calibration-area/reset` | `{surfaceName?, x0Mm, y0Mm, widthMm, heightMm}` | Where the pad calibration lays its test dots on the blank (default 40 × 20 mm at the blank place): `{surfaceName, x0Mm, y0Mm, widthMm, heightMm, usedMm, resetAtMs, defined, leftMm, effective, default}`; reset = "Blank wiped". |

The plan view adds `profileCheck` (or `profileCheckError`), per pad `profileGroup`, `dotSteps`, `dotsPerPad`,
`startPhase`, `predictedMassMg`, `padRipplePct`, `anchorMassMg`, `dotDiameterMm`, `clearanceMm`, per dot `netSteps`,
`block`, `padIndex`, `diameterMm`, and `blocks` `[{index, netSteps, turn, dots, pads, firstDot, lastDot, phaseDot,
flushDots, demandMgS}]`. The upload preview's packages add `profileCheck`, `profileReason` and `profileShort`, their
`outcome` becomes `needs-profile` or `not-dispensed`, and one `NEEDS_PROFILE` warning replaces the `NO_PAD_TYPE` ones.
Pad types add `dotSteps`, `dotsPerPad`, `turn`, `tipKey`, `doseFp`, `calAreaMm2`, `massPerPadMg`, `origin` and
`lastCalibration`.

### 3.5 Pad calibrations: Calibrate automatically, by hand, the weigh coupons (PC §7, §8, §12.2–§12.4)

Endpoint groups `PadCalibrationEndpoints` and `SimEndpoints`. One job at a time (`PadCalibrationJobs`), on a thread of
its own that hands each engine step (the blank probe, the ladder or the weigh dots: `DispenseService.runDoseBlocks`) to
OpenPnP's machine thread and waits for it. While a job runs, decides or plans, every other dispense task is refused
(the UI says `Locked while the pad calibration runs`); while it waits for the readings only its coupons are reserved. A
STOP stops the job first (it queues no further engine step; a step queued before the STOP refuses at its start, the
engine's stop epoch). A job lives in memory: after a restart it is gone, its coupons are not (`/api/coupons/pending`).

| Method + path | Body / query | Does |
|---|---|---|
| `GET /api/pad-calibrations/preview` | the start's fields as a query: `planId` or `boardFile`, `groups` (ids, comma-separated, or `all`) or `typeKeys`, `steps`, `doseSteps`, `weighDots`, `save`, `suggestionIds` (`group:id,…`), `regulatorPsi`, `regulatorAtMs`, `pasteLifeAtMs`, `demotes` | The before-start summary (PC §7.9) and what Start would refuse: `{steps: [{key, state, text, estimatedSeconds}], estimatedSeconds, pasteMg, blank: {surfaceName, x0, x1, y0, y1, usesMm, leftMm, leftAfterMm, heightMm, probe, probeWhy}, ladder: [{steps, turn, dots, label, pitchMm}], coupons: [{place, label, steps, turn, dots, pitchMm, expectedMg}], notWeighed, purgeSpots: {need, left}, home, homes, zCheck, probes: [{surface, x, y}], groups: [{group, label, kind, typeKey, steps, perPad, turn, doses, hopMm, validated}], saves, demotes, skipped, needs, notes, sentence, needsText, source, manual, refusal, refusalCode}`. `sentence` e.g. `Checks and touches your two weigh coupons (≈ 1:44). Lays 76 test dots on the blank (28 half-turn, 48 quarter-turn) in X 285.0–360.0 mm, Y 233.0–242.1 mm (4.9 of 14.0 mm left after this) and looks at them with the top camera (≈ 1:16). Then lays 136 quarter-turn dots on coupon 1 and 68 half-turn dots on coupon 2 (≈ 2:45) and parks the head over the purge row. Then it needs you once: weigh each coupon, wipe it and weigh it again (4 readings). About 0.23 g of paste, ≈ 5:45 before your readings.` `homes`: the homes it makes (under `homeBeforeRun: always` one before each engine step: the sentence starts `Homes before each of its 5 steps (the setting is: always home first; ≈ 2:05)` and `estimatedSeconds` counts each; `home.estimateS` stays one home's). |
| `POST /api/pad-calibrations` | `{planId? \| boardFile?, groups?: [ids] \| "all", typeKeys?, steps?: ["ladder"] \| ["weigh"], doseSteps?, weighDots?, save: true, suggestionIds: {group: id}, regulator: {psi, atMs}, pasteLifeAtMs, operator?, demotes?, wait?}` | The checks below, then the suggestions saved as provisional pad types with their mappings (PC §7.2 step 0), then the job: 202 `{kind: "pad calibration", id, steps, preview, started: true}`; with `wait` also `finished` and `result` (the job view) once the job ended or waits for the readings (at most 10 min). `steps` with `doseSteps` and one `typeKeys` entry is the by-hand path (PC §8): one dose laid on the blank (`ladder`) or on the coupons (`weigh`, `weighDots` dots), recorded, nothing decided. With fresh records of every dose and no `steps`, nothing moves: it decides and plans again (`Nothing to dispense: the blank and coupon results are still fresh; it decides and plans again.`). |
| `GET /api/pad-calibrations/current` · `GET /api/pad-calibrations/{id}` | | The job view (null, or 404, for a job not in this session: the last 5 are kept for their crops). |
| `POST /api/pad-calibrations/{id}/answer` | `{step: "readings", coupons: [{place, fullG, emptyG}], confirm?, wait?}` or `{step: "readings", skip: true}` | The operator's readings in grams (each coupon with its paste, then wiped and weighed again), for every coupon the job laid dots on; or skip (the types stay provisional). Then the decision and the plan made again (`wait` waits for both). 400, before anything is written: a reading missing, `fullG` not above `emptyG` (`… was the wiped coupon weighed second?`), a coupon the job does not have, a coupon typed twice (`coupon1 is typed twice: type each coupon's two readings once`); 409 `implausible` (outside 0.5–2 × the expected mass: `You typed 4.178 g − 4.100 g = 78 mg; about 72 mg was expected for 136 quarter-turn dots. …`) and 409 `late` (more than `readingConfirmAfterMin`, 15 min, after the dots: the flux dries), both kept by sending again with `confirm: true`; 409 `refused` when the job is not waiting for readings or the dots are no evidence any more; 409 `wrong-step`. |
| `POST /api/pad-calibrations/{id}/cancel` | | Aborts the engine step that runs; at the readings the job ends `cancelled` and its coupons stay pending. |
| `GET /api/pad-calibrations/{id}/crops/{cropId}-before.png` · `…-after.png` | | The result card's camera crops (PNG, 96 × 96 px of 3 × 3 mm around a dot, before and after it was laid). |
| `GET /api/pad-calibrations` | `typeKey?`, `limit?` (1–500, default 50) | The `pad_calibrations` rows, newest first: `{id, createdAtMs, jobId, typeKey, versionFrom, versionTo, outcome, steps, dotsPerPad, massPerPadMg, checks: [{key, pass, text, predicted, measured, limit}], evidence, runs, simulated, message, operator}`; `checks[].key` `optical`, `mass`, `amount`, `r6`, `geometry`, `control`, `tip`, `regulator`, `validated`. |
| `GET /api/dose-records` | `steps?`, `freshOnly?`, `limit?` (default 200) | The dose records, newest first (`kind` `optical`, `coupon`, `mass`; `method` `auto`, `manual`, `typed`, `sim`), each with `fresh` and `whyStale` (PC §1.7, e.g. `made before the controller last connected: the screw angle may have moved`, `made before the new paste was loaded`, `older than 8 h (made 9.2 h ago)`, `made in the simulator`). |
| `GET /api/coupons/pending` | | Coupons whose dots wait for their readings and can still be weighed: `[{recordId, place, steps, turn, dots, found, expectedMg, laidAtMs, staleAtMs, jobId}]`. A pending coupon is reserved: no engine step lays dots on it or probes it (`coupon1 carries 136 dots waiting to be weighed: type the readings or skip them (Calibrate › Pad calibration)`): the coupon probe, the weigh dots and (rc1 review) scale shots, the dot ladder and the touch-off at that place, which answer 409 with that text. |
| `POST /api/coupons/{recordId}/reading` | `{fullG, emptyG, confirm?, operator?}` or `{skip: true, operator?}` | The readings of a coupon whose job is gone (stopped, cancelled, OpenPnP restarted), with the checks of `answer`: `{record: {id, place, steps, totalMg, mgPerDot, mgPerStep, confirmedBy, weighDelayS}, confirmed, text}` (`coupon1: 78 mg of paste, 0.574 mg per quarter-turn dot; a second weighing confirms it`). Mass per dot is the total over the dots laid (PC §7.5). `skip`: no readings (a mass row without them), the coupon's place is free again: `{record: {id, place, steps, skipped: true}, confirmed: false, text: "coupon2: weighing skipped; wipe its 68 half-turn dots off before it is used again"}` (the web UI's Skip next to Save, two presses). 404 for a coupon that is not pending; 409 while its own job waits for it (type it there). |

**The start's checks**, in this order (409 `refused` with the `preview`): the engine available and idle (a running job
included), no pressure hold, OpenPnP idle; no other job waiting for its readings (`a pad calibration is waiting for the
readings of its coupons: type them, skip them or cancel it first (Calibrate › Pad calibration)`); something to
calibrate (`Nothing to calibrate: every chosen group cannot be dispensed with the 22G tip.`, or `… the chosen groups
have a pad type of their size already (Use it).`); the suggestion ids still current (409 `suggestion-stale` with the
new `profileCheck`); the regulator attestation (`Read the regulator first: it must show 18.0 psi (Run › Checks ›
Regulator).`: at the profile's psi, at most 8 h old, not older than the last needle change) and the paste life
(`Confirm the paste is within its working life first (Run › Checks).`); off the simulator, no shortened statistics
(`padCalibration.minHops below 60 is allowed in the simulator only: set them back to calibrate on this machine`); the
blank place, the calibration area's room and what it overlaps (`The calibration area overlaps the purge row: move it
(Calibrate › Pad calibration).`, or a coupon place, or the plan's pads + 3 mm); the coupon places (a place within 1.5 mm of the purge row: `The weigh coupon place coupon1 overlaps the purge row: move it
(Setup › Places › coupon1).`; touching the plan's pads + 3 mm: `The weigh coupon place coupon1 overlaps the pads of
<board name> (3 mm around them): move it (Setup › Places › coupon1).`), the weigh grid on each coupon, no dots waiting
on them; a weighing refused under `homeBeforeRun: always` (every step would home, so the
coupons' Z would predate the weigh step's home); the purge spots the steps need (the I9 text); the basic interlocks
with home first.

**The job view** (`status.padCalibration`, `current`, `answer`, `cancel`, a start's `result`): `{id, state, step,
manual, startedAtMs, endedAtMs, planId, boardFile, groups, typeKeys, steps: [{key, state, text}], ladder: [{steps, turn,
dots, found, hops, threads, residCv, phase, phaseLimit, dMeanMm, d95Mm, driftPct, landingMeanMm, mgPerDot,
massConfirmed, pass, failures}], coupons: [{place, label, steps, turn, dots, recordId, found, totalMg, mgPerDot,
skipped}], awaiting, pendingCoupons, balance: {kind: manual | sim, connected}, estimatedSecondsLeft, warnings,
result}`. `warnings` adds, after a ladder that changed a group's dose (`the ladder changed a group's dose: the weighing
weighs coupon1 and coupon2`), a reweigh that keeps the checked grid (`the quarter-turn weigh dots do not fit where the
camera checked coupon1: it weighs the half turn as planned; Calibrate again weighs the quarter turn`).

- `state`: `running` | `awaiting-reading` | `deciding` | `planning` | `done` | `stopped` | `cancelled` | `failed`.
  `steps[].key`: `blank`, `coupons`, `ladder`, `weigh`, `readings`, `decide`, `plan`; `steps[].state`: `todo`, `now`,
  `done`, `skipped`, `failed`, `stopped`; texts like `blank surface probed 21:44 (still valid)`, `coupon1 and coupon2
  found and touched`, `76 dots: half ok, quarter ok`, `136 quarter-turn dots on coupon1, 68 half-turn dots on
  coupon2`, `coupon1 78 mg (0.574 mg per quarter-turn dot), coupon2 78 mg (1.147 mg per half-turn dot)`, `2 of 2
  characterized`, `planned again`. The `ladder` rows pool every fresh camera record of a dose (the weigh dots are
  imaged too).
- `awaiting` (at the readings, else null): `{kind: "readings", text, sinceMs, confirmAfterMs, staleAtMs, coupons:
  [{place, label, steps, turn, dots, found, expectedMg, recordId, laidAtMs, crop}], unit: "g", resolutionMg}`; the
  text starts `The dots are laid and the head is parked over the purge row. For each coupon: put it on the balance and
  type the reading; wipe the paste off with a lint-free wipe and isopropyl alcohol, let it dry, put it back on the
  balance and type the second reading.`
- `result` (PC §7.10): `{outcome, sentence, atMs, newPlanId, planError, refusal, groups, crops, predictions, control:
  {pass, text, medianAreaMm2, drift}, pendingCoupons, finishable}`. `outcome`: `characterized`, `partly`,
  `provisional` (Calibrate automatically), `measured` (by hand), `failed`, `stopped`, `cancelled`. The first sentence
  is machine-readable: `Characterized 21:50: SW_SPST_3.9x2.9mm · 1.83 × 0.86 mm gets 2 half-turn dots per pad, about
  2.29 mg per pad (0 % more than the 2.29 mg target); …; planned again.`, `Not characterized 21:58: … stays
  provisional: …`, `Measured 22:00: 64 quarter-turn dots on the blank: every camera check passed.`, `Weighed …; the
  mass is confirmed.`, `Failed 21:58: I11: …` (an internal error: state `failed`, `refusal` `an internal error stopped the calibration
(<ErrorClass>: <message>); see the OpenPnP log` and the same in the sentence; the job's lock is released), `Stopped 13:06 during weigh: the blank results were kept; the coupons
  still need weighing until 21:13.`. A group: `{group, label, typeKey, outcome, text, steps, perPad, turn, mgPerPad,
  targetMg, errorPct, dMeanMm, d95Mm, residCv, phase, phaseLimit, threads, hops, massReadings, massConfirmed,
  padRipplePct, measuredRipplePct, replaced, replacedText, notes, checks, version, calibrationId}`. `predictions`: P1–P8
  of PC §1.8, predicted and measured. `newPlanId`: the plan made at the end; a page loads it once per job id when the
  plan it shows is of that board.

**SIM** (`SimEndpoints`, PC §12.4; 409 `the simulator is not confirmed on this connection` unless the controller
answered `M115` as OpenPnP's simulator): `GET /api/sim/balance` → `{mg, display: "4.178 g", stable, loaded, mode,
resolutionMg}`; `POST /api/sim/balance/load {place}` (null: the pan empty) → the same; `GET /api/sim/paste/deposits
?region=` (`blank`, `board`, `coupon`, `bed`, a coupon place, `all`) → `[{x, y, mg, dMm, region, place, thread,
string}]`; `POST /api/sim/paste/wipe {region}` → `{wiped}`. 409 `the sim scene has no balance (il_sim_scene.py
--balance)` and `no sim scene: the simulator has no paste`. Records made in the simulator carry `simulated: true` and
are never evidence on the machine (nor machine records in the simulator).

**Where the code differs from PC §12.2–§12.4** (the code is right): the preview takes the start's fields as a query
and says what Start would refuse (`refusal`, `refusalCode`); the start also takes `weighDots` (the Weigh dots card's
count), `operator` and `demotes`, refuses off the simulator while a shortened statistics setting is below its floor,
and refuses a weighing under `homeBeforeRun: always`; `GET /api/pad-calibrations/{id}` and the answer's `wait` exist; a
wrong step is 409 `wrong-step`; `POST /api/coupons/{id}/reading` answers `{record, confirmed, text}`; the SIM balance
view adds `mode` and `resolutionMg`, the deposits `place` and `string` (a smeared dot). Not built: PC §20 decision 6's
per-run "Skip these N pads" (`RunResult.skippedPads` and `runs.skipped_pads` exist, but no route takes a skip list:
Run › Checks points to Setup › Settings › Refuse flagged pads (`#set-refuseflagged`) instead; with it off the Pad types item is a note that counts ready and the run confirm names the pads skipped).

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
  views), `boardview.js` (the to-scale board SVG), `boardart.js` (the board drawing as SVG), `pathpreview.js` (the
  path preview), `padinspect.js` (the pad inspector) and `board.css`, one module per workspace: `run.js` + `run.css`,
  `setup.js` + `setup.css`, `profile.js` + `profile.css` (also used by `calibrate.js` and `records.js`), and `app.js`
  (boot), `boardflow.js` (the drawing's glue to Run and Records), `boardimport.js` + `boardimport.css` (the upload
  dialog) and `padprofile.js` + `padprofile.css` (missing paste profiles and the pad calibration).
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

Added on `il/bench-sync-autoreg` and its web branches (`UI_DESIGN.md` §8.1.2–8.1.3, §8.2; `DEV_NOTES.md` §11–12):

- **Register board** (`il/ar-webui`): Run's second step is Register (route stays `probe`): the correction the plan's
  dots use, the preview (expected fiducials with their search circles, probe targets with their alternates), the live
  phases and tables of fiducials and probe points, a result card whose first sentence is machine-readable
  (`Registered 13:05: …`, `Same seat 13:12: …`, `Not registered: …`, `Stopped 13:06 during …`), and the two-press
  `Register board · ≈ M:SS`. "Do it by hand" keeps the by-hand probe. Fix-its for `REG:` and `PROBE:` refusals.
- **Home at run and Z check:** the machine check is a note that counts ready when Run homes first (`Ready to run ·
  homes first (≈ 25 s)`); `Check Z` is a fix-it; the run confirm and the Run step's phases show the home and the Z
  check. The new-seat answer lives in the engine (`POST /api/board/new-seat`).
- **Setup › Probe the blank:** the automatic blank probe with its targets, phases and result (`Saved 13:20: 6 points,
  worst 0.012 mm, covers 15 of 15 spots.`), by hand kept below. Settings: `homeBeforeRun`.
- **Board drawing** (merged): the board's copper, mask, silkscreen and holes under the dots (Full, Quiet, Off), the
  path preview (play, scrub, speeds 1×, 4×, 16× on the plan's clock), the pad inspector (a pad or part click: its
  pads, dots, pad type and the rule that chose it, flags), the Records map on the drawing.
- **KiCad upload**: `Upload KiCad board…` in the Board step opens the dialog (choose → reading →
  preview → saving → saved), board thumbnails, "Camera is on FID1" (the origin from the top camera on the first
  fiducial), the chosen board's drawing at its seat before planning.
- **Missing paste profiles** (pc-web, `padprofile.js` + `padprofile.css`, `PROFILE_CALIBRATION_DESIGN.md` §13;
  `UI_DESIGN.md` §8.3–8.4, ids in §16): Run › Checks item 5 "Pad types" names what blocks (`Blocked · needs a
  profile`: `21 pads cannot be dispensed: 12 need a profile, 9 not with the 22G tip (WSON-8…)`) and links to Profile ›
  **Needs a profile** (`#pt-needs`): one row per pad size that needs a pad type (package, parts, opening, why, the
  suggestion with its confidence, `Use Chip 0603 · plans again` or `Calibrate automatically…`, `I'll do it by hand`,
  `Suggestion…`), the pads the 22G tip cannot dispense collapsed, `Calibrate all automatically… · 2 sizes · ≈ 3:26`.
  The **suggestion card** (side panel `#profile/needs/<g>`) explains the plan in numbers and draws the suggested dots
  on the board's pads. **Calibrate automatically** opens the `pad-cal` view: the before-start summary (time, paste,
  blank area, coupons, when it needs you) and `Calibrate · ≈ 5:45` (two presses), the live steps, the readings prompt
  (each coupon's camera picture, full and wiped readings in grams, the paste in mg echoed; it comes back after a
  reload), the result card (first sentence `Characterized 21:50: …`, per group its checks, camera crops, predicted vs
  measured, the control's drift) and the board planned again. **Calibrate › Pad calibration** (`#cal-padcal`): the
  area left on the blank, `Blank wiped` (two presses), the paste loaded, the coupon places, coupons waiting for their
  readings (typed there after a STOP or a restart); **Weigh dots** (`#cal-weigh`) and the by-hand guide's ladder
  (`#btn-guide-ladder`) are the by-hand path. The upload preview gets a Profile column per package and the
  `NEEDS_PROFILE` warning; the pad inspector shows one profile notebox and the planned amount. While a job runs the
  strip reads `Pad calibration · test dots 14/48 · ≈ 2:50 left` (`Waiting for you · weigh` at the readings) and every
  other task is locked.
- **The final fix round** (`il/rc1`): after "Z moved" the Z check blocker offers the remedy's next step under the same
  home (`Open Needle Z frame`, then `Probe the blank…`, then `Run a touch-off` or `Confirm frame offset …`, then
  `Register board`; `IL.zMoved`), and the touch-off and dot ladder cards say when the frame or the blank comes first;
  what uses the machine is locked while OpenPnP homes or jogs (`OpenPnP is busy (homing or moving): wait for it to
  finish`) and every engine action while an upload reads its file (`status.boardImport.reading`), the upload itself
  while a pad calibration is between its steps; a clean-pad check refused at its start shows `Refused 14:05: the
  pressure hold is on: Pressure off first.` on its card; Register board's probe-window refusal links the Board step,
  the Needle Z frame and Do it by hand; Profile offers a quarter, half or whole turn only; Test run with fewer dots
  says when it lays whole pads (`TEST · first 18 of 160 dots (whole pads: 18 of the 20 asked for)`) and the first
  pad's minimum; Coupons to weigh has Skip (two presses) next to Save; the pad calibration's coupon-place refusals link
  to the place, and under `homeBeforeRun: always` its steps table shows a home before each step.

Jargon is explained in tooltips (`title`/`abbr`).

## 6. Tests

- `mvn -T1 test -Dtest='ApiServerSimTest,ReviewSafetySimTest,RegistrationSimTest,BoardImportSimTest'` (part of `mvn
  test`): 23 + 11 + 11 + 12 tests on the engine's simulated machine (OpenPnP GcodeDriver over localhost TCP to
  `RecordingGcodeServer`), real `DispenseService`, real `ApiServer` on an ephemeral port. `RegistrationSimTest` runs
  Register board, the blank probe, the home at run, the Z check and probe only for a board without fiducials end to end
  on the SIM vacuum and the SIM camera (`SceneImager`); `BoardImportSimTest` uploads, saves and attaches the KiCad
  fixtures and plans them; `CameraSnapshotEncodingTest` (2) the PNG fallback of the camera snapshot. Missing paste
  profiles:
  `PadProfileApiSimTest` (8: the check, apply, mappings, the upload preview), `PadCalibrationJobTest` (14: the job's
  states, readings, cancel, STOP, refusals in order, on fake engine steps), `PadCalibrationSimTest` (13: PC §14.3 end
  to end on the SIM paste, camera and balance, every negative control of §11.7, the SIM balance after a reconnect, the
  look before a ladder's dots and the control's tolerance in a job),
  `PadProfileUiSimTest` (3: the files are served and loaded).
  Board runs there use the bench geometry (N1 contact 5.5, K 65.59 → needle 2.91, offset −2.59), a purge row on the
  blank, a confirmed start angle and the clean-pad check off, or a fake `DotImager` (`DispenseService.setDotImager`)
  for the I10 tests. The whole suite on `il/rc1` 5aba4636e (fix round 2 merged): 1133 tests, 847 of them in
  `org.openpnp.instancelabs` (dispense 349, api 102, sim 87, kicad 79, padprofile 71, vision 70, board 62, serial 27).
- `src/test/web/run-webui-sim-test.sh`: Playwright (headless Chromium) against this jar running in OpenPnP **sim**
  through openpnp-uinav (config copy, simulated controller and cameras, Xvfb, device stubs). Needs the jar
  (`mvn -q -T1 -DskipTests package`), openpnp-uinav built, and Playwright in `$PLAYWRIGHT_DIR`
  (`npm i playwright && npx playwright install chromium-headless-shell`, browsers in `$PLAYWRIGHT_BROWSERS_PATH`).
  It drives the element ids of `UI_DESIGN.md` §16 (two-press buttons, the in-page run confirm, Recovery after STOP);
  on `il/ar-webui` also Register board, the blank probe, home at run and Check Z, and on `il/rc1` the final round's
  locks, the refused clean-pad check, the dose rule, the Z-moved remedy in the SIM and the probe-window refusal, and
  fix round 2's needle touch-off (the pitch and the engine's 400 at the field, a run that records nothing, dots not
  measured) and probe only for a board without fiducials (244 checks in all at the release candidate),
  and `board-import.playwright.cjs` runs after it in the same session (62 checks:
  upload, the off-board warning, save, replace, attach, Camera is on FID1, the drawing under the plan, the inspector,
  the preview, the Records map, 1280 and 1024 px, dark theme).
  `boardview.playwright.cjs` (`run-boardview-test.sh`, 67 checks) checks the drawing on the session's import of
  `ftp.kicad_pcb` through devproxy (mobo from the fixtures for the performance gate).
  `pad-profile.playwright.cjs` runs third in the same session (71 checks, PC §14.4: the padcal upload's profile column,
  Run › Checks, Needs a profile, the suggestion card, Use, the pad inspector, Calibrate automatically through the
  readings (the test is the operator at the SIM balance) to the result, the by-hand guide, Calibrate › Pad
  calibration, STOP at the readings, a test run that lays whole pads, a coupon weighed and one skipped; one filled
  button per screen; screenshots at 1440 and 1280 px, light and dark).
- `python3 -I src/test/sim/il_sim_acceptance.py padcal --prefix P --api-port N` (also in `all`): PC §14.5 in three
  uinav SIM sessions on this jar. **main**: `padcal.kicad_pcb` uploaded (NEEDS_PROFILE, a reason per package), saved
  and planned; the check's groups (SW_SPST new 2 × ½ turn, Test_Strip new 2 × ¼ turn, TestChip_A Use 0603, the WSON's
  two groups not dispensable); Use; Calibrate automatically with the script as the operator at the SIM balance (weigh,
  wipe, weigh, readings typed); both types characterized at the SIM's 2.30 mg per turn (not the model's 2.11); the
  plan made at the end run with the WSON skipped: the SIM paste of every pad within 30 % of the plan's predicted mass
  and each package's mean within 10 %, the two dose blocks (phase and flush dots on the purge row, every block start
  at 298° by the controller's count), the M114 `Count` reply in the controller log equal to the run's B count;
  `pad_calibrations` and the weighings `simulated`; a reconnect then refuses the quarter-turn block (I11) and makes
  the quarter-turn camera records stale. **nano**: the Arduino Nano's upload (40 pads `tip`, 15 with a plan, 5 of them
  partial), Calibrate automatically for its two groups (SW_SPST characterized; LED_0805 stays provisional: one turn is
  47 % over its amount in the SIM, 33 % on the machine), its next plan run (the switch's 4 dots). **controls**: every
  other negative control of §11.7 ends with its reason. About 27 min (main 8, nano 6, controls 14).
- `src/test/sim/il_ui_session.py start --jar … --name …`: a seeded SIM session for UI work (machine homed, needle
  frame, blank surface and purge row, start angle, a planned board with its surface tied); `--padcal` adds the SIM
  paste, coupons and balance, the calibration area and coupon places, and plans `padcal.kicad_pcb` (Needs a profile
  lists its groups); `src/test/web/devproxy.py` serves the web files from disk against it (no jar rebuild).

## 7. Known limits

- In a SIM session (`il_ui_session.py`, `il_sim_scene.py`) the top camera is `SimBoardCamera`: it draws the scene's
  board, fiducials, pads and paste where the camera looks, so Register board, the camera look and the clean-pad check
  run there; it draws only while the controller is confirmed as the simulator (else a grey `SIM CAMERA: controller not
  confirmed` frame). The SIM vacuum (`SimVacuum`, inside OpenPnP's simulated controller) answers N1's reads from the
  scene's surfaces. A plain OpenPnP simulator config still has a static image: untick the clean-pad check there. The
  simulated controller reports no M114 B step count: the engine takes 0 (with a warning in the run), so the start angle
  is only exercised, not physical; with the scene's SIM paste (`il_sim_scene.py --paste`) it reports Marlin's count
  and lays paste by its own model (2.30 mg per turn, its own dot-size law, ripple, noise, threads), which the pad
  calibration has to measure.
- Pad calibrations: one job at a time; a job lives in memory (OpenPnP's restart forgets it; its coupons keep their
  dots and are weighed through `/api/coupons/{id}/reading`); crops of the last 5 jobs. The SIM paste models the needle
  Z only as the gap to the surface. There is no balance driver: readings are typed (the SIM balance is for tests).
  PC §20 decision 6's per-run skip of undispensable pads has no API yet: runs skip them only with
  `refuseFlaggedPads: false`. The test-board ladder (PC §20 decision 8, slice `pc-ladder`) is not in this PR: a
  follow-up; `validated` stays on the Validate guide.
- A board placed by hand (file + position) gets its fiducial correction from Register board (`registration: auto` on
  the next plan). `planBoardFile` adds the file to OpenPnP's board list, which OpenPnP writes to `boards.xml` when it
  saves; a KiCad import writes its files and OpenPnP's lists at once (save transaction).
- Job boards are listed only while OpenPnP's window runs (the job lives in the Swing job panel).
- Plans live in memory (last 20) for "Run what you previewed"; every plan is also stored in `pad_plan`.
- Progress is polled (0.7 s), not pushed.
- DPH from the simulator is not a physical number (the simulated controller has its own timing model).
- Paste working life (I8) is not tracked by the engine; shown as "off".
- Board import: KiCad 5 is read on a best-effort basis (6 to 10 fully); no Gerber; bottom-side parts are skipped; a job
  open in OpenPnP keeps its old copy of a replaced board until it is reopened; uploads are refused while a task runs.
  A footprint outside the board outline is drawn only (art only, why `outside the board`: no paste) with one
  `OFF_BOARD` warning (§3.3); the check uses the outline's box, so a part in a notch of a non-rectangular board is not
  flagged.
- Registrations are not written back to a job board in OpenPnP. The home estimate is learned per OpenPnP session
  (25 s until the first home). The fiducial search radius is checked against the 9-frame cap at the default camera
  size when settings are saved, at the real camera's size only inside the task.
