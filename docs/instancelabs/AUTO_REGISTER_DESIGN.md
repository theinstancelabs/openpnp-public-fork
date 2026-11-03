# Board registration, automatic Z probe and homing at job start: design spec

Status: design for implementation, **revision 2**. It answers two critiques (safety-bench, ux-test); §15 logs what
was done with each point. Branch `il/bench-sync-autoreg` (based on `il/bench-sync` 5e14c13de).
Brief: `/home/lumen/.t3/projects/openpnp-revamp/tasks/autoreg-brief.md`. **Simulation only** while this is built: no
serial port, no `/dev/video*`, no real-mode OpenPnP. The bench tools in `~/instancelabs/tools` define what the machine
does; this spec copies their numbers and G-code, and says where it deliberately departs from them (and on what data).

Path abbreviations used in the citations:

- `D/` = `src/main/java/org/openpnp/instancelabs/dispense/`
- `A/` = `src/main/java/org/openpnp/instancelabs/api/`
- `V/` = `src/main/java/org/openpnp/instancelabs/vision/`
- `S/` = `src/main/java/org/openpnp/instancelabs/sim/` (new package)
- `O/` = `src/main/java/org/openpnp/` (OpenPnP itself)
- `W/` = `src/main/resources/org/openpnp/instancelabs/web/`
- `T/` = `src/test/java/org/openpnp/instancelabs/`
- `PW` = `src/test/web/webui-sim.playwright.cjs`
- `MX` = `~/.openpnp2/machine.xml` (live file, only read)
- `PT/` = `/home/lumen/.t3/projects/openpnp-revamp/tasks/` (`todo.md`, `lessons.md`)
- `tools/` = `~/instancelabs/tools/`; `runs/` = `~/instancelabs/runs/`

Every claim about existing code carries `file:line`. Numbers taken from bench data say where they come from. "Offline
parse of `runs/serial.log`" means: the probe.py sequences in that log (`M106 P1 S180`, then `G4 P1500`, then exactly
5 reads before the first `G1 Z… F300`): 158 sequences over 12 sessions.

---

## 0. What gets built

One new engine task, **Register board**, run from Run's second step:

1. **Home, if needed.** If a STOP left N1 low, raise it (Z only) first.
2. **Find the fiducials** with the top camera: a Java port of `tools/fid.py` and the centring loop of
   `tools/locate.py`, searching up to 8 mm from where each one is expected.
3. **Fit the board→machine affine.** Refuse an implausible fit, both in absolute terms and against this board's
   previous registration.
4. **Decide the seat**: `same` (every fiducial moved ≤ 0.1 mm and nobody said "new seat") or `first`/`reseated`.
5. **Touch the board with N1's vacuum.** First look at each spot with the camera (paste, a part). Then touch it with
   the bench method, plus two corrections measured in the bench log: wait for the vacuum to release before the fine
   pass, and accept a fine contact only below the last open coarse step.
   - Same seat: re-touch 3 stored points instead. The surface is kept only if all 3 agree within 0.03 mm.
6. **Save** the plane as the board surface, tied to the registration, and plan the board again.

Three more pieces use the same parts:

- **Probe the blank**: the automatic probe of the purge-row surface, from Setup.
- **Home when needed**: Register, the blank probe and the board Run home by themselves when needed (setting
  `homeBeforeRun`). Nothing else homes on its own.
- **Z check after a home** (added after the safety review). A home can move the Z zero: it moved 0.7 mm on 2026-10-28
  (PT/todo.md:184). On the see-saw the needle then moves about twice that. So needle tasks refuse Z data measured
  before the last home until one N1 touch at a stored point agrees within 0.03 mm.
  - Run does that touch by itself.
  - Register board stamps it as a by-product of its same-seat check.
  - A "Check Z" action covers prime, the test dot and the calibrations.

The simulator gets two new parts, so the whole flow can be tested end to end without the machine:

- a vacuum model inside the simulated G-code server, with the bench's slow release and partial seals;
- a top camera that draws the board's fiducials and pads at a deliberately shifted, rotated and scaled seat.

Neither works unless the controller identified itself as OpenPnP's `GcodeServer` (M115) over loopback TCP.

---

## 1. Decisions from the brief, and how this design honours them

| Brief decision | Where | Notes |
|---|---|---|
| `homeBeforeRun` = `whenNeeded` (default) \| `always`; Register, Probe and Run home first; say so before they start; nothing else homes silently | §2, §5, §8 | "Needed" also covers a STOP that lands during G28 (§2.2). A STOP during homing disconnects the driver, so visual homing cannot move after it (§2.3). The run also checks Z after a home (§2.4); that is a touch, not a home. |
| Engine-side fiducial check; bench method; board files and job boards; ≥ 2 fiducials, 3 preferred; affine from 3 (least squares if more); sanity limits; search radius; registration feeds the plan and ties like surfaces (I4); not found → stop, say where it looked, keep the manual path | §3 | Search radius 8 mm: the bench re-seats moved fiducials up to 6.9 mm (§3.3). With 2 fiducials the fit is a similarity (rotation and one scale). |
| Vacuum probe at bare spots mapped through the registration; window +2.5 / −1.5 mm around the nominal board top; up to 2 alternates; open-air baseline check; plane fit with the existing residual limit; saved as the board surface tied to the plan; the same for the blank; STOP aborts at any step | §4 | The floor is −1.5 mm under the higher of the board Z and the needle-frame surface, and tightens after the first contact (§4.4). A part on N1 is caught by comparing with recent baselines, not by an absolute window (§4.4). |
| One primary "Register board" action with live progress and a result card; manual paths stay as "Do it by hand"; moved > 0.1 mm → "re-seated" and probe | §8 | The "same seat" rule needs registrations kept by identity, not by their matrix hash (§3.6). A same seat is confirmed by 3 touches before its surface is kept. |
| SIM: G-code server answers M260/M261 with a vacuum that seals on a tilted surface, with leaky spots; top camera shows fiducials and pads at an offset, rotated, scaled seat; impossible in real mode | §9 | "Impossible in real mode" = loopback TCP **and** the controller's M115 says `FIRMWARE_NAME:GcodeServer` (§9.1). |

None of the decisions conflicts with the code. Two need more than the brief says, and §3.6 handles both:

- **"Same seat if moved < 0.1 mm."** This needs the registration kept by identity. `boardRegistration` hashes the
  transform matrix to 6 decimals (D/DispenseService.java:428-451), so any re-fit would force a re-probe.
- **Carrying the registration of a board file.** The server rebuilds a file board's position from x/y/z/rotation
  only (A/DispenseEndpoints.java:668-679), so the registration must travel in the plan source.

---

## 2. Home when needed, and Z after a home

### 2.1 Today

- **The only home** is `POST /api/machine/home`. It submits `m.home()` as its own machine task
  (A/MachineEndpoints.java:179-204).
- **What `ReferenceMachine.home` does** (O/machine/reference/ReferenceMachine.java:527-549):
  1. clears the homed flags;
  2. homes the motion planner: every driver's HOME_COMMAND, i.e. `G28` (MX:466-468), sent with an infinite timeout
     (O/machine/reference/driver/GcodeDriver.java:649-650). The planner sets its own `homed = true` when G28 returns
     (O/machine/reference/driver/AbstractMotionPlanner.java:229-249);
  3. runs visual homing: the camera moves to the homing fiducial with `hm.moveTo`
     (O/machine/reference/ReferenceHead.java:63-84);
  4. calls `setHomed(true)` (:542), then parks if `isParkAfterHomed`.
- **How a run is refused unhomed:** `Interlocks.check` adds `MACHINE: machine is not homed`
  (D/Interlocks.java:258-260). It is used by the API pre-flight (A/DispenseEndpoints.java:721-722) and again inside
  the task (D/DispenseService.java:746).
- **What a disable does:**
  - `setEnabled(false)` un-homes the planner and the machine, disables the drivers, then fires `machineDisabled`
    (ReferenceMachine.java:263-287).
  - With `connection-keep-alive="true"` (MX:436), the real driver stays connected after a disable
    (GcodeDriver.java:573-577).
  - STOP always disables (A/MachineEndpoints.java:112-141).
- **After an engine STOP**, `Session.recover()` reads the position back and un-homes. It does not lift Z
  (D/DispenseService.java:1362-1384). A STOP during a probe therefore leaves N1 down at the board.
- **What the engine tracks:** only the connect time (D/DispenseService.java:70-71, 127-131). The page's `homedAt` is
  client-side only (W/core.js:1532-1533).
- **What a home does to Z.** On 2026-10-28 the Z zero moved about −0.7 mm across an OpenPnP home (PT/todo.md:184;
  `tools/place.py:22`). The same entry records two more effects of that shift:
  - the needle moved about −1.1 mm, i.e. twice the shift, as expected for a see-saw tool;
  - board 2 probed 0.85 mm low.

  The see-saw maps z2 = 63 − z1 (MX:222-227). So a zero error δ moves every N1 contact by δ, and moves the needle
  relation (needle contact + N1 contact) by 2δ. Today nothing ties Z data to a home: I4 (D/Interlocks.java:138-245)
  and `isNeedleAnchored`/`isNeedleTouchedOff` (D/DispenseConfig.java:314-326) never look at one.

### 2.2 New class `D/HomeTracker.java` (state lives here)

`public final class HomeTracker extends MachineListener.Adapter`, created by `DispenseService` and added as a machine
listener next to the existing one (D/DispenseService.java:119-152). Fields are volatile, in epoch ms, with an
injectable `LongSupplier clock`:

| field | set by |
|---|---|
| `connectedAtMs` | `machineEnabled`. Moves the existing `controllerConnectedAtMs` here; `getControllerConnectedAtMs()` delegates, so I11 is unchanged (D/Interlocks.java:334). |
| `disabledAtMs` | `machineDisabled` |
| `homeStartedAtMs` | `homeStarted()`, called right before every engine or API home (§2.3). It is cleared at the next `machineHomed(true)`, and by `homeEnded()` when that home ends without one (a STOP or a failure; review 1). |
| `lastHomeAtMs` | every `machineHomed(m, true)`, from any caller: engine, API, OpenPnP's own Home button (ReferenceMachine.java:542). `null` = no home seen since the service started. |
| `homeUnconfirmed` | at `machineHomed(true)`: true iff `!m.isEnabled()`, or `homeStartedAtMs != null && disabledAtMs > homeStartedAtMs`. Otherwise false, so a later clean home clears it. A home from OpenPnP's window has no known start and counts as confirmed when the machine is enabled. |
| `lastHomeMs` | `machineHomed(true) − homeStartedAtMs`, for a confirmed home with a known start |
| `zCheck` | the latest Z check (§2.4), memory only |
| `serviceStartMs`, `homedAtStart` | construction: `new HomeTracker(clock, machine.isHomed())` |

The service can start while OpenPnP is homed, e.g. on a homed `SimMachine` (T/api/ApiServerSimTest.java:61-68). That
home has no known time: `lastHomeAtMs` stays `null`. That neither forces a home nor makes Z data stale (§2.4).
OpenPnP itself always starts unhomed, so after a real restart the first home sets `lastHomeAtMs`.

`public Plan plan(boolean enabled, boolean homed, String policy)` returns
`Plan {boolean needed; String whyCode; String why; Long sinceMs; String policy; Long homedAtMs; double estimateS}`.
`homedAtMs` here is `lastHomeAtMs`.

```
lastHome = lastHomeAtMs != null ? lastHomeAtMs : (homedAtStart ? serviceStartMs : -inf)
needed = policy == "always" || !homed || homeUnconfirmed || max(connectedAtMs, disabledAtMs) > lastHome
```

The `whyCode` and `why` (plain words, no clock times; the UI adds the time from `sinceMs`) are chosen in this order:

| whyCode | why | sinceMs |
|---|---|---|
| `always` | "the setting is: always home first" | null |
| `home-unconfirmed` | "a stop came during the last home" | disabledAtMs |
| `stopped` | "stopped or reconnected since the last home" | max(disabledAtMs, connectedAtMs) |
| `not-homed` | "the machine is not homed" | null |
| `homed` (needed = false) | "homed" | lastHomeAtMs |

There is no separate `reconnected` code. In OpenPnP a reconnect is a disable followed by an enable, so `stopped`
always describes it.

`estimateS = lastHomeMs / 1000` if known, else **25 s** (a guess, shown with ≈; the real figure is §11 item 2).

### 2.3 Homing safely: `DispenseService.homeSafely(GcodeChannel ch, BooleanSupplier aborted, HomeReport rep)`

Two callers use it, so both homes behave the same:

- the engine's `homeIfNeeded`;
- `POST /api/machine/home` (replacing its bare `m.home()` when the service exists).

The steps, on the machine thread:

1. **N1 low after a STOP.**
   - Read the controller position with `M114` (gated query). `recover()` has already resynced it
     (D/DispenseService.java:1362-1384).
   - If z1 lies outside its safe zone 26.5–36.5 (MX:215-216), send `G1 Z31.5000 F600` and `M400` through the gate,
     **Z only**. That is the bench's own end line (`tools/probe.py:60`). The detail says "N1 was at Z 5.40: raised
     to 31.5 before homing".
   - Raw Z 31.5 is z1's home coordinate and the see-saw midpoint (MX:208-227), so both N1 and the needle end up
     clear.
   - X/Y never move before G28. The axis order of the Lumen firmware's G28 is unknown (§11 item 1), and this makes it
     irrelevant for Z.
2. **STOP during the home.**
   - For the duration of `m.home()`, add a one-shot `MachineListener`. On `machineDisabled` it calls `disconnect()`
     on every `GcodeDriver` (GcodeDriver.java:1185). `machineDisabled` fires after OpenPnP has sent the
     DISABLE_COMMAND (ReferenceMachine.java:270-287).
   - Why: once G28 returns, the planner is homed again (AbstractMotionPlanner.java:249), and visual homing and the
     park would move the head after the STOP. Moves are not refused while disabled: only task submission checks
     "enabled" (O/spi/base/AbstractMachine.java:584-587).
   - Disconnected, the next move throws and `home()` ends. Start reconnects (GcodeDriver.java:500), and the
     HomeTracker sees a disable.
3. `homeTracker.homeStarted()`, then `m.home()` on the machine thread. If `aborted` is true or the machine is not
   enabled after it (or it threw), call `setHomed(false)` and end ABORTED. Any other exception ends REFUSED
   `MACHINE: homing failed: … Nothing else was done; home from the strip and try again.`

`homeIfNeeded(Session s, HomeReport rep, BooleanSupplier aborted)` runs inside the engine task, right after the
`Session` is built. `new Session(...)` publishes the executor that STOP aborts (D/DispenseService.java:1233-1234):

```
Plan hp = homeTracker.plan(m.isEnabled(), m.isHomed(), cfg.homeBeforeRun);
rep.needed = hp.needed; rep.whyCode = hp.whyCode; rep.why = hp.why;
if (!hp.needed) return;
if (aborted.getAsBoolean()) throw new AbortedException("stopped before homing");
phase(r, "home: " + hp.why);  detail(stage "home")
homeSafely(s.channel, aborted, rep);             // steps 1-3
rep.done = true; rep.seconds = …
```

### 2.4 Z after a home: the Z check

**Rule, a new part of I4** (`Interlocks.checkI4`, D/Interlocks.java:138-188), for scopes with needle motion:

- `Inputs` gets `Long lastHomeAtMs`, `ZCheck.Result zCheck` and `boolean zCheckFirst`.
- **Z data in the scope** are:
  - `needleAnchor.setAtMs`;
  - `needleZOffsetSetAtMs`;
  - `setAtMs` of every surface the scope uses (`sc.surfaceName` and `sc.moreSurfaces`).

  Places carry no time (D/DispenseConfig.java:229-238). The same check covers them. *As built (rc1 review):* a place
  carries the time it was set and its Z frame (`NamedLocation.setAtMs`, `homeAtMs`; `PUT /api/locations` stamps them,
  the derived test-dot spot keeps the waste's), but a touch never vouches for it. After a failed check of this home, a
  place set before it is refused where its Z is used (I4 `place 'waste' Z 5.500 was taken at 13:40, before the home at
  14:02 where Z moved 0.80 mm: take its Z again with N1 touching (Setup › Places)`): prime, the test dot, shots, the
  dot ladder's and the touch-off's prime at the waste (`Scope.dispensePlaces`), a coupon's probe and its weigh dots.
- **Stale** = `lastHomeAtMs != null` and any of those Z data is older than it. Stale data are accepted only if
  `zCheck.homeAtMs == lastHomeAtMs && zCheck.pass`. Otherwise:
  - with `zCheckFirst` (the task will check Z itself): a note, `I4: Z data predate the home at 14:19: checks Z first (one N1 touch)`;
  - after a failed check for this home: the "Z moved" violation (§6.2);
  - otherwise: the "Z not checked" violation (§6.2), naming each stale datum and its time.

**`D/ZCheck.java`** (new, pure):

- `references(cfg, planBoardRegistration, lastHomeAtMs)` lists stored N1 contacts measured **before** that home, in
  this order:
  1. the points of the board surface, when it is tied to the plan's registration. Its spots were chosen bare (§4.6);
  2. the points of the blank surface;
  3. the needle anchor's X/Y with `anchor.surfaceZMm`, when `xMm`/`yMm` are set (D/DispenseConfig.java:248-256).

  A surface probed after the home is already in the new frame and is not a reference.
- `verdict(ref, z)`: pass iff `|z − ref.z| ≤ ZCheck.TOLERANCE_MM` = **0.03 mm**.
  - That is just over one 0.025 mm fine step. The bench's re-probe of board 2 at 3 stored spots agreed within
    0.025 mm (PT/todo.md:187: "4.500/4.525/4.675 vs 4.500/4.550/4.675").
  - The needle then moves at most about 0.06 mm, inside the 0.15 mm gap.

**Procedure**, `DispenseService.zCheckIfNeeded(...)`:

1. Take the first reference whose spot passes the camera look (§4.5).
2. Probe it as one `VacuumProber` point, with the window built around `ref.z` (§4.4).
3. Store `HomeTracker.zCheck = {atMs, homeAtMs, refName, x, y, refZ, z, deltaMm, pass}` and write a `ZCHECK` event.
4. On a fail the task ends REFUSED with the "Z moved" text. If there is no reference, it is refused before anything
   moves.

**Where it runs:**

- **Run** (§5.5): after `homeIfNeeded`, before the in-task interlocks.
- **`POST /api/machine/z-check`** (§5.8): a small task behind the "Check Z" fix-it, for prime, the test dot, the
  touch-off, the dot ladder and shots. These keep their refusal and never check Z silently.
- **Register board**: a same-seat check whose 3 board points predate the home and all pass also stamps the Z check
  (§3.6). Register itself never uses the needle, so it never needs the check.

What it costs: one N1 touch, ≈ 20 s, only after a home, and only when Z data predate that home.

**Z frames (review 1, machine safety).** A touch proves only that the frame its point was measured in equals the
frame now; a home between two older data may have moved Z (the bench's frame vs OpenPnP's: 0.8 mm). So:

- every Z datum stores the home it was measured under (`needleAnchor.homeAtMs`, `needleZOffsetHomeAtMs`,
  `SurfaceMap.homeAtMs`; `HomeTracker.frameHomeAtMs()`: the last home seen, else the service start). The touch-off's is
  protected like `needleZOffsetSetAtMs`;
- a passing touch vouches for the data of its point's frame and for its own datum (`ZCheck.vouch`, `Result.covers`).
  A datum of unknown frame (saved before this) vouches only for itself, so a lone touch-off of unknown frame cannot be
  checked: record the needle frame again and touch off;
- stale data are accepted only when a pass for this home covers each of them. The check touches one point per frame
  among them (the first bare one), in the reference order above; a datum with no point of its frame left is refused
  before anything moves;
- a passing touch moves the data it vouched for to its home (`ZCheck.restamp`, saved), so after the next home one touch
  is enough again. The same-seat stamp follows the same rules.
- a needle frame recorded after the touch-off sets the offset again, so that touch-off is no Z datum any more
  (`ZCheck.touchOffReplaced`; I4 asks for a new touch-off). After "Z moved" the remedy works under the same home: the
  needle frame, the blank, the touch-off, then the board, each measured again; a datum not measured again keeps the
  refusal. The frame is measured with both tips touching (rc1 review): K is a sum of raw Z heights, so a zero shift
  moves it by about twice the shift and a frame "from K" gives the old offset (63 − K) whatever N1 touched;
  `POST /api/needle/anchor` refuses `needleContactRawSum` while the check of this home failed and after a needle
  change (`DispenseService.needleFrameFromKRefusal`), and the web remedy offers the touch-off, not the frame's own
  offset.
- the blank probed again after a home (by hand, or the pad calibration's blank step) keeps the points it replaced
  (`SurfaceMap.replaced`, `ZCheck.kept`) when they were measured before that home in a known frame: they stay a
  reference of their frame, listed after the blank's own points, so a check of the needle frame and the touch-off
  still has a point to touch. One frame is kept; a later probe under the same home carries it on.

### 2.5 Where homing happens

`homeIfNeeded` is called from:

- `registerBoard` (§5.2), before the interlocks;
- `probeSurface` (§5.7);
- `runPlan`, between `new Session(c, r)` (D/DispenseService.java:742) and the first `Interlocks.check`
  (D/DispenseService.java:746), followed by `zCheckIfNeeded`. The profile check before the session
  (D/DispenseService.java:735-741) stays first, so nothing is sent for a stale plan.

It is not called from prime, the test dot, shots, the dot ladder, the touch-off, the clean check or the pressure hold.
These keep their `MACHINE: machine is not homed` refusal: nothing else homes silently.

### 2.6 Interlocks: the run no longer refuses "not homed" when it will home

- `Interlocks.Inputs` gets `homeFirst` and `zCheckFirst`. In `Interlocks.check` (D/Interlocks.java:258-260):
  `if (!in.machineHomed) { if (in.homeFirst) r.notes.add("MACHINE: not homed: homes first"); else r.add("MACHINE", "machine is not homed"); }`.
  `machine is not enabled` stays a violation: homing needs a started machine, and nothing presses Start for the
  operator.
- A run that will home makes every Z datum stale. So in `POST /api/run`:
  - `zCheckFirst = homeFirst || (stale && no passing check)`;
  - the pre-flight also needs `ZCheck.references(...)` to be non-empty. Otherwise it refuses with the "no reference"
    text before any G28.
- New `public static InterlockReport checkBasic(Inputs in)` returns only CONFIG and MACHINE. Register and the blank
  probe use it: I4/I5/I7/I9–I11 are about dispensing.
- `DispenseService.checkInterlocks(plan, est, homeFirst, zCheckFirst)` and `checkBasicInterlocks(homeFirst)` are new
  overloads. The old signatures pass `false`.
- Inside `runPlan`, after `homeIfNeeded` and `zCheckIfNeeded`, the in-task checks run with both flags `false`.

### 2.7 The engine's busy flag, and the pressure race

- **Disabled machine.** If the machine is disabled when an engine task starts, `AbstractMachine.submit`'s wrapper
  throws before the body runs (O/spi/base/AbstractMachine.java:584-587). `finish()` never runs, and `busy` stays true
  for good (D/DispenseService.java:1097-1160).
  - Fix: `submit` and `submitResult` call `machine.submit(callable, null, true)` (ignoreEnabled).
  - The body checks `if (!m.isEnabled())` and ends REFUSED `MACHINE: the machine is not started: press Start first`
    before the Session.
- **Busy machine thread.** A task queued behind another machine task that throws is removed and never runs
  (AbstractMachine.java:616-631).
  - Fix: `ApiContext.requireMachineIdle()` refuses 409 `OpenPnP is busy (homing or moving): wait for it to finish`
    when `m.isBusy()`.
  - It is called by `POST /api/run`, `/api/plan/{id}/register`, `/api/surfaces/{name}/probe` and
    `/api/machine/z-check`, and (review 1) by every other engine endpoint: `/api/prime`, `/api/test-dot`,
    `/api/plan/{id}/clean-check`, `/api/calibrations/shots-run`, `/api/calibrations/dot-ladder` and
    `/api/calibrations/touch-off`.
  - `POST /api/machine/home` returns its failure from its machine task instead of throwing it (and is queued with
    ignoreEnabled, checking "started" itself), so a STOP or a failure during the web home cannot drop an engine
    task queued behind it.
- **Pressure hold against a starting task.**
  - Today `/api/run` checks the hold and `/api/pressure/start` checks "engine idle", each on its own API thread
    (A/DispenseEndpoints.java:243-250, 694-697). Both then queue on the machine thread, and `PressureHold.doStart`
    never checks the engine (A/PressureHold.java:165-181).
  - Fix, on the machine thread where the two are serialised:
    - `registerBoard`, `probeSurface`, `runPlan` and the Z check refuse at task start when the hold is active (a
      `BooleanSupplier` from `ApiContext`). As built (review 1) every engine task does, in `submit` /
      `submitResult`: prime, the test dot and scale shots (`runShots`), the dot ladder, the touch-off, the clean-pad
      check and the dose blocks too;
    - `PressureHold.doStart` refuses when `svc.isBusy()`. The busy flag is set before the engine task is queued
      (D/DispenseService.java:1098, 1133).
  - So nothing homes or probes with the barrel pressurised: paste would ooze over the board (PT/lessons.md:4).

---

## 3. Fiducial registration

### 3.1 Board features: `D/BoardFeatures.java` (new, pure)

`public static BoardFeatures of(Board board, PadTypeResolver resolver)` gives, in **board millimetres**:

- **`widthMm, heightMm`**: from `board.getDimensions()`, or 0 when unknown. ftp is 75 × 75
  (`~/Desktop/ftp.board.xml:2`).
- **`fiducials`**: `{name, x, y, diameterMm, maskDiameterMm}` for every placement with `type == Fiducial`, side Top,
  enabled.
  - The diameter comes from the first round footprint pad (roundness 100, w == h).
  - If there is none, it is parsed from the package id with `(?i)fiducial[_-]?(\d+(?:\.\d+)?)mm` and
    `(?i)mask(\d+(?:\.\d+)?)mm`.
  - If that fails too, the defaults are 1.0 / 3.0 mm.
  - ftp's package `Fiducial_1mm_Mask3mm` has no footprint pads (`~/.openpnp2/packages.xml:87-93`), so the parse gives
    1.0 / 3.0 there.
  - ftp places FID1 at (62.4465812, 4.69103014), FID2 at (12.5, 70.0) and FID3 at (62.5, 70.0) (`tools/register.py:14`).
- **`pads`**: `{id, x, y, wMm, hMm, rotationDeg, known}`. They come from the existing extractor, run on the board
  placed at the origin: `new BoardPadExtractor(resolver, true).extract(bl)`, with `bl = new BoardLocation(board)` at
  `Location(mm, 0, 0, 0, 0)`.
  - At that location the "global" pad centres are the board coordinates (D/BoardPadExtractor.java:117-143).
  - A `PadGeometry` with no width or height (no pad type or no geometry, D/BoardPadExtractor.java:78-108) becomes
    `known = false`, and is kept out as a circle (§4.6).

### 3.2 Detector: `V/FiducialDetector.java` (new, a port of `tools/fid.py`)

```java
public final class FiducialDetector {
  public static final class Settings {            // code constants = fid.py; a test seam, not persisted (§6.1)
    public double diameterMm = 1.0;
    public double minDiameterFactor = 0.76, maxDiameterFactor = 1.20;
    public double minCircularity = 0.82, minFill = 0.80;
    public int thresholdHigh = 250, thresholdLow = 130, thresholdStep = 10, blurKernelPx = 7;
    public double ambiguousScoreRatio = 0.9;
  }
  public static final class Detection {
    public double xPx, yPx;                        // centroid (moments), frame pixels
    public double offsetXMm, offsetYMm;            // from the frame centre, OpenPnP convention (+x right, +y up)
    public double diameterMm, circularity, fill, contrast, score; public int threshold;
    public double secondScore;                     // best score of another mark > 0.3 mm away, 0 if none
    public int candidates;
  }
  /** Best fiducial whose centre lies within searchRadiusMm of (centreXMm, centreYMm) (mm from the frame centre)
      and whose 2r ring lies inside the frame; null if none. Infinity = the whole frame. */
  public static Detection detect(BufferedImage img, double mmPerPxX, double mmPerPxY,
                                 double centreXMm, double centreYMm, double searchRadiusMm, Settings s);
}
```

The algorithm follows `tools/fid.py:5-24` line by line:

1. **Grey and blur.** Convert with `OpenCvUtils.toMat` (8-bit BGR), then to grey with
   `Imgproc.cvtColor(BGR2GRAY)`, then `Imgproc.GaussianBlur(g, g, Size(7,7), 0)`. OpenCV is OpenPnP's bundled 4.5.5,
   loaded with `nu.pattern.OpenCV.loadLocally()` as in V/DotInspector.java:57-59.
2. **Threshold sweep.** `t = 250, 240 … 130` (13 levels, as `range(250, 120, -10)`). At each level run
   `Imgproc.threshold(THRESH_BINARY)`, then `findContours(RETR_LIST, CHAIN_APPROX_NONE)`.
3. **Keep a contour only if all of these hold:**
   - **Area window in mm²:** `π/4·(0.76·d)² < A·mmPerPxX·mmPerPxY < π/4·(1.20·d)²`, which is 0.454 … 1.131 mm² at
     d = 1.0.
     - This is the bench's pixel window 3800 … 9500 px² (`tools/fid.py:12`) at the bench scale
       0.01074 × 0.01106 mm/px (`tools/locate.py:6`).
     - Working in mm makes it independent of OpenPnP's own scale, 0.01068376 × 0.01072961 (MX:298).
   - **Circularity** `4πA/P² ≥ 0.82`.
   - **Fill** `A/(π r²) ≥ 0.80` (`minEnclosingCircle`).
   - **Centre** `(m10/m00, m01/m00)` within the search radius. The radius is measured in mm, so it is an ellipse in
     pixels.
   - **The `2r` ring lies inside the frame.** This check is new: a mark at the frame edge cannot be scored.
4. **Score.** Contrast = mean grey in the disk of radius `int(r)` minus the mean in the ring `int(1.5r)`…`int(2r)`
   (`tools/fid.py:19-21`). Score = circularity × contrast. Keep the best.
5. **Ambiguity.** Track the best score of candidates more than 0.3 mm from the winner. If
   `secondScore ≥ 0.9 · score`, the caller fails AMBIGUOUS.
6. **Offsets** use OpenPnP's convention: `offX = (xPx − W/2)·mmPerPxX`, `offY = −(yPx − H/2)·mmPerPxY`
   (O/util/VisionUtils.java:65-91).
   - The engine sees frames after OpenPnP's transform. The Top camera has flip-x and flip-y (MX:297), so a frame is
     the raw image turned by 180°.
   - The bench mapping on raw frames, `fid = cam − (px−960)·SX, cam + (py−540)·SY` (`tools/locate.py:3-4,14`), then
     becomes this convention.

### 3.3 Search, centring and fit: `V/FiducialLocator.java` (new)

```java
public final class FiducialLocator {
  public interface FrameSource { DotImager.Frame capture(double xMm, double yMm) throws Exception; } // camera centred, lit
  public static final class Settings {           // persisted: searchRadiusMm, minFiducials (§6.1); the rest are constants
    public FiducialDetector.Settings detector = new FiducialDetector.Settings();
    public double searchRadiusMm = 8.0, centringRadiusMm = 1.0, centringTolerancePx = 3.0;
    public int centringIterations = 3;
    public double maxScaleErrorPct = 1.0, maxShearDeg = 0.5, maxResidualMm = 0.05;
    public double maxScaleChangePct = 0.15, maxShearChangeDeg = 0.15;   // against the board's previous registration
  }
  public static final class Target { public String name; public double boardX, boardY, expectedX, expectedY, diameterMm; }
  public static final class Found  { public Target target; public double x, y; public int iterations, frames;
                                     public double lastOffsetPx, offsetFromExpectedMm; public FiducialDetector.Detection detection; }
  public static final class Fit    { public AffineTransform boardToMachine; public String method;  // affine-3 | affine-lsq-N | similarity-2
                                     public double rotationDeg, scaleX, scaleY, shearDeg, maxResidualMm; public double[] residualsMm; }
  public static final class Failure extends Exception { public final String code; /* NOT_FOUND, AMBIGUOUS, NOT_CENTRED, FIT, FEW */ }
  public FiducialLocator(Settings s, FrameSource cam, BooleanSupplier aborted, Consumer<Found> onFound, Consumer<String> onStep);
  public List<Found> locateAll(List<Target> targets) throws Exception;   // Failure or DispenseExecutor.AbortedException
  public static Fit fit(List<Found> found, Fit previous /* null if none */, Settings s) throws Failure;
}
```

**Why the search reaches 8 mm.** The bench re-seats moved fiducials far beyond a few millimetres, measured from the
previous registration, which is where §3.7 starts:

| file (vs its `previous`) | FID1 | FID2 | FID3 | rotation |
|---|---|---|---|---|
| `runs/board_registration_board2.json` | 4.60 | 5.18 | 6.91 mm | |
| `runs/board_registration_reflow1.json` | 2.75 | 3.24 | 4.23 mm | −4.6° |
| `runs/board_registration.json` | 1.40 | 1.72 | 2.23 mm | |

`tools/fid.py:7-23` has no search radius: it searches the whole 1920 × 1080 frame.

**Search tiles.**

- The usable half-frame is `hx = W·mmPerPxX/2 − m`, `hy = H·mmPerPxY/2 − m`, with `m = 1.20·d`, so the `2r` ring stays
  inside. At 1920 × 1080 and 0.0107 mm/px that is ±9.1 × ±4.6 mm.
- The first frame of each fiducial is centred on its prediction, and searched over its whole usable area. Only marks
  within `searchRadiusMm` of the prediction count.
- If that frame finds nothing, further frames are centred at `prediction + (i·2hx, j·2hy)`, nearest first, until the
  tiles cover the search circle.
  - At the defaults that is 2 more frames, at (0, ±9.2) mm. The 3 frames cover |dx| ≤ 9.1, |dy| ≤ 13.8 mm, which
    contains the 8 mm circle.
  - A smaller frame needs more tiles. The count is capped at 9; the config validation refuses a radius that would need
    more.
- The search stops at the first frame with a candidate.

**Prediction.** `locateAll` visits the targets in nearest-neighbour order, starting from the camera's position.

- After the first fiducial is found, every remaining prediction moves by its offset (found − predicted).
- After two, they are re-predicted with the similarity of the two (`Utils2D.deriveAffineTransform`,
  O/util/Utils2D.java:653).
- With reflow1's −4.6° the translation-only prediction for the second fiducial is still off by up to
  82 mm · sin 4.6° ≈ 6.6 mm (FID1 to FID2 is 82 mm). That is inside 8 mm.

**Centring**, for each target once a candidate is found:

```
(cx, cy) = frame centre that found it; radius = searchRadiusMm (from the prediction)
for it in 1..centringIterations:
    if aborted -> AbortedException("stopped during the fiducial check")
    f = cam.capture(cx, cy)
    d = FiducialDetector.detect(f.image, f.mmPerPxX, f.mmPerPxY, …, radius, s.detector)
    if d == null: Failure(NOT_FOUND, …where it looked: prediction, radius, frames…)
    if d.secondScore >= 0.9 * d.score: Failure(AMBIGUOUS, …)
    fx = cx + d.offsetXMm; fy = cy + d.offsetYMm; offPx = hypot(d.xPx - W/2, d.yPx - H/2)
    if offPx < centringTolerancePx: found(fx, fy, it, offPx); break
    (cx, cy) = (fx, fy); radius = centringRadiusMm
if not found: Failure(NOT_CENTRED, …)
```

- **Converge or fail** is a deliberate change from the bench. `tools/locate.py:9-19` prints a result even when it did
  not converge; here the measurement counts only if the last frame's offset is under 3 px. The 3 % gap between
  OpenPnP's camera scale and the true one (REAL_MACHINE_TODO.md:32-35) then costs under 0.1 px.

**Fit:**

- `Utils2D.deriveAffineTransform(boardPoints, machinePoints)` (O/util/Utils2D.java:653): least-squares affine for n > 2
  (exact for 3, as `tools/register.py:28-31`), similarity for n = 2.
- `Utils2D.affineInfo` decomposes it into scale X/Y, x-shear and rotation (O/util/Utils2D.java:159-189).
  `shearDeg = toDegrees(atan(xShear))`.
- Residual `i` = |fit(board_i) − found_i|. It is zero for 3 points by construction, so it is checked only for n ≥ 4.

**Sanity limits** (Failure FIT; nothing is stored):

1. **Absolute.**
   - `|scaleX − 1|` and `|scaleY − 1|` ≤ 1 %. For n = 2 the uniform scale is checked.
   - `|shearDeg|` ≤ 0.5° (n ≥ 3).
   - Every residual ≤ 0.05 mm (n ≥ 4).
2. **Against this board key's previous registration**, when there is one:
   - `|scaleX − prev|` and `|scaleY − prev|` ≤ 0.15 %;
   - `|shearDeg − prev|` ≤ 0.15°.

   This machine's distortion is stable from seat to seat. All 10 `runs/board_registration*.json` affines
   (decomposed offline) have:
   - X scale 1.0011–1.0016;
   - Y scale 0.9942–0.9947;
   - shear −0.20° … −0.28°.

   So a 3-point fit has a check after all. One fiducial found 0.3 mm off at 60–80 mm spacing changes the scale by
   about 0.4–0.5 % or the shear by about 0.25°, and is caught.
   - For reference, the current `runs/board_registration.json` is [[1.00112, 0.00103], [−0.0057, 0.9946]]. (The
     first revision quoted board3's matrix here by mistake.)
3. **Fewer found than `minFiducials`** → Failure FEW. The API refuses earlier, so this only happens if the board
   changed.

The failure texts are in millimetres. They name the pair of fiducials whose distance disagrees most with the board
file (§6.2).

### 3.4 Capture with the light on

- **The gap.** `CameraCapture.capture` uses `settleAndCapture`, which does not switch the light
  (V/CameraCapture.java:30-33). `lightSettleAndCapture` does
  (O/machine/reference/camera/AbstractSettlingCamera.java:641-648).
  - The Top camera's light actuator is the LED, `M150 P255 R255 U255 B255` (MX:297 `light-actuator-id`, MX:480-481),
    the same command the bench sends (`tools/disp.py:66`).
  - The LED is OpenPnP's own actuator. It does not go through the dispenser allowlist and is not a dispenser output.
- **New `public static Capture captureLit(Camera cam, Length z)`** in V/CameraCapture.java.
  - It is `capture(cam, z)` with `lightSettleAndCapture()` instead, and it reuses the private `of(...)`
    (V/CameraCapture.java:44).
  - The engine-core slice owns this one change (§13).
- **New `DotImager` method:**
  `default Frame captureLit(double xMm, double yMm, Double surfaceZMm) throws Exception { return capture(xMm, yMm); }`
  (D/DotImager.java:33).
- **`OpenPnpDotImager` overrides it:** the same `MovableUtils.moveToLocationAtSafeZ` move
  (D/OpenPnpDotImager.java:30-36), then `CameraCapture.captureLit(camera, surfaceZ)`.

### 3.5 Registration record: `D/BoardRegistration.java` (new, Gson-friendly public fields)

```java
public class BoardRegistration {
  public String id;                 // UUID
  public String boardKey;           // "file:<absolute .board.xml path>" | "job:<boardLocationId>"
  public String boardName;
  public Nominal nominal;           // {double x, y, z, rotation; String side; String openPnpTransform}
  public double[] affine;           // board mm -> machine mm, AffineTransform.getMatrix order m00 m10 m01 m11 m02 m12
  public String method;             // affine-3 | affine-lsq-N | similarity-2
  public double rotationDeg, scaleX, scaleY, shearDeg, maxResidualMm;
  public List<Fid> fiducials;       // {name, bx, by, x, y (found), expectedX, expectedY, iterations, frames, offsetPx, diameterMm, score}
  public long atMs;                 // measured
  public Long lastCheckedAtMs;      // last "same seat" check that kept this record
  public Double lastCheckMovedMm;
  public boolean simulated;         // made with SimGuard.isSimulated(machine) true (§9.1)
  public String runId;
  public AffineTransform transform();
  /** A copy of the nominal board location carrying this transform (the original is never changed). */
  public BoardLocation applyTo(BoardLocation nominal) throws NoninvertibleTransformException;
  public boolean matches(String boardKey, Nominal n);          // key equal, x/y/rotation within 1e-4 (not z: F8), same side, same openPnpTransform
  public double maxMovedMm(List<Fid> now);                     // max |found now − found here| over common names
}
```

- **`Nominal.openPnpTransform`** is null for a file board.
  - For a job board it is the job board's `getPlacementsTransformStatus()` plus its 6 matrix values to 6 decimals,
    as `appendRegistration` formats them (D/DispenseService.java:432-447).
  - If OpenPnP's own fiducial check later changes the live job board, `matches` fails. Plan and run then refuse with
    `REG: OpenPnP's fiducial check of this job board changed since registration 3f2a… (13:05): register again`.
- **`applyTo`** is `BoardLocation c = new BoardLocation(nominal); c.setLocalToGlobalTransform(transform()); return c;`.
  - The copy constructor is deep (O/model/BoardLocation.java:86-88).
  - `setLocalToGlobalTransform` handles a parent panel and sets the status to LocallySet
    (O/model/PlacementsHolderLocation.java:342-351).
  - It must be the last call: `setLocation` would clear the transform (PlacementsHolderLocation.java:357-365).
- **How the affine reaches the dots, for both board kinds.**
  - `BoardPadExtractor` → `Utils2D.calculateBoardPlacementLocation(bl, …)` → `bl.getLocalToGlobalTransform()`
    (D/BoardPadExtractor.java:124-127; O/util/Utils2D.java:250-283).
  - Pad XY and rotation follow the affine. Z stays the board's nominal Z + placement Z, so dot Z still comes from the
    probed plane (D/DispensePlanner.java:43-50).
  - The live OpenPnP job board is never changed. On the planning copy, our affine replaces OpenPnP's own fiducial
    transform (§14, question 4).

### 3.6 Storage, identity, "same seat" and the new-seat flag

**Storage.**

- `DispenseConfig.boardRegistrations: Map<String, BoardRegistration>` holds the **latest** record per board key and
  is persisted in `dispense-config.json` (D/DispenseConfig.java:14-27).
- It is engine-stamped: added to `PROTECTED_SETTINGS` (A/DispenseEndpoints.java:44-46) and to the `STAMPED_TOP`
  mirror (W/setup.js:24).
- At most 20 keys; the engine drops the oldest `atMs`.
- Every registration also writes a `REG` event holding the record's JSON (`db.event(runId, "INFO", "REG", json)`,
  D/DispenseDb.java:709-722).

**The new-seat flag moves to the engine.**

- Today the operator's "new seat / next board" answer lives in the browser's localStorage (W/core.js:507, 607-609).
  Another client or the API never sees it.
- New protected `DispenseConfig.newSeatAtMs` (persisted), set and cleared by `POST /api/board/new-seat`
  `{"newSeat": true|false}` (§7.6).
  - **Set by:** Next board, and the Board step's "Yes: new seat".
  - **Cleared by:** a Register that saved a probed surface; a by-hand board-surface save with a `planId`; or
    `{"newSeat": false}`, which the UI offers only for boards that cannot be registered (§8.2).
- `status.dispense.newSeatAtMs` replaces the client copy.

**Same seat or re-seated.** `prev = boardRegistrations.get(key)`, used only if `prev.matches(key, nominal)`:

- **`first`**: no usable previous record. A new record is stored, and the probe runs.
- **`reseated`**: some fiducial moved more than `seatToleranceMm` (0.10), or `newSeatAtMs` is newer than
  `prev.atMs`. A new record replaces the previous one, and the probe runs.
- **`same`**: every fiducial moved ≤ 0.10 mm and the flag is not newer. The previous record is kept unchanged (same
  id, same affine, same `boardRegistration` digest); only `lastCheckedAtMs`/`lastCheckMovedMm` change, and they are not
  in the digest. Then the **same-seat check**:
  - It needs the board surface tied to `prev`'s digest (D/Interlocks.java:238-245). If it is not tied, the full probe
    runs.
  - Take the 3 stored surface points that span the largest triangle. Look at each (§4.5) and touch it (§4.4), with the
    window built around its stored Z.
  - All 3 within `ZCheck.TOLERANCE_MM` (0.03 mm) of their stored Z → **keep the surface**: no re-probe, the tie stays
    valid. If those points predate the last home, this also stamps the Z check (§2.4).
  - Otherwise → probe every target. The result says "same seat, surface changed: probed again".
- **Why 3 touches, not none.** Fiducial XY cannot see:
  - a board that sits tilted after re-seating ("one seating was tilted 0.9 mm", D/Interlocks.java:236-237;
    `runs/zprobe_r5.json` spans 5.05–5.99);
  - another board of the same design in a repeatable seat. `runs/zprobe_board2.json` 4.50–4.725 vs
    `runs/zprobe_board3.json` 4.50–4.55 differ by up to 0.18 mm, against a 0.15 mm gap.
- **Why 3, not 2.** A plane has three degrees of freedom: two points on a diagonal miss a tilt about that diagonal.

**The plan carries the registration explicitly.**

- `POST /api/plan` takes `registration`: `"auto"` (default), `"none"` or an id (§7.1). The source map stored with the
  plan gets `registrationId` (A/PlanStore.java:21-54; A/DispenseEndpoints.java:568, 583).
- `DispenseService.planBoard(BoardLocation bl, BoardRegistration reg)` plans `reg == null ? bl : reg.applyTo(bl)`.
  `plan.boardFingerprint` and `plan.boardRegistration` are computed on that location (D/DispenseService.java:566-567).
- `DispenseEndpoints.currentBoardLocation(e)` (A/DispenseEndpoints.java:668-679) re-applies the registration named by
  `source.registrationId`. Both of its users, the run's board check (:707-712) and the surface tie (:417-425),
  therefore see the same registered board.
- If the record is gone (registered again at a new seat), or no longer matches the key and nominal (a job board moved
  or re-checked in OpenPnP), `currentBoardLocation` throws. The run is then refused with that reason (§5.6).
- A record with `simulated = true` is refused unless `SimGuard.isSimulated(machine)`.

### 3.7 Expected positions

- **First guess.**
  - If `prev` matches: `prev.applyTo(nominal)`. The bench also starts from the current registration
    (`tools/register.py:4,20-21`).
  - Otherwise the nominal board location. For a job board that includes OpenPnP's transform if its check ran
    (A/DispenseEndpoints.java:681-692).
  - Then `Utils2D.calculateBoardPlacementLocation(bl, fiducialPlacement.getLocation())`, as `ReferenceFiducialLocator`
    does (O/machine/reference/vision/ReferenceFiducialLocator.java:196).
- **Search** (§3.3): up to `fiducialCheck.searchRadiusMm` (8.0) from the prediction, over 1–3 frames, then
  `centringRadiusMm` (1.0) for later captures of the same fiducial.

---

## 4. Automatic vacuum probe (N1)

### 4.1 Controller commands: a separate probe allowlist (`D/ControllerCommands.java`)

Today `M260`/`M261` are not allowed words, and M106 must be one of two right-side constants
(D/ControllerCommands.java:53-56, 71-73, 118-123). `check()` is the single global gate used by
`DriverGcodeChannel.send`, `sendCritical` and `sendAndCollect` (D/DriverGcodeChannel.java:40-43, 52, 77). The pressure
hold sends through it too (A/PressureHold.java:176, 282).

**`check()` stays unchanged**, so neither a dispense stream nor the pressure hold can ever switch N1's vacuum or write
I2C. A new, narrower gate allows the probe's lines:

```java
public static final String LEFT_PUMP_ON  = "M106";          // N1 pump, Marlin fan 0 full on (MX:566 {True:M106})
public static final String LEFT_VALVE_ON = "M106 P1 S180";  // N1 valve as the stock VAC1 actuator (MX:565)
public static final List<String> VAC1_INIT   = List.of("M260 A112 B1 S1", "M260 A109", "M260 B48", "M260 B27", "M260 S1");
public static final List<String> VAC1_SELECT = List.of("M260 A112 B1 S1", "M260 A109 B6 S1");
public static final String VAC1_READ = "M261 A109 B3 S1";
/** check(line), or one of the exact probe lines above (2 outputs + 6 distinct M260 + 1 M261). */
public static String checkProbe(String line);
```

- These are the bench's lines (`tools/probe.py:19-20, 49, 51, 60`) and machine.xml's VAC1 actuator (MX:565-566).
  M260 can write any I2C address, so only these exact texts pass.
- The OFF side already has `M107 P1` and `M107 P0` (D/ControllerCommands.java:43-44, 55-56). The bench's bare `M107`
  becomes the equivalent `M107 P0`.
- `DispenseExecutor.sendGated`/`queryGated` (§4.2) take a scope. `PROBE` uses `checkProbe`; everything else uses
  `check`.
- `GcodeStreamValidator` names the reason when a dispense program holds one of these lines: "N1 vacuum and I2C lines
  belong to the probe".

### 4.2 Gated controller I/O (`D/DispenseExecutor.java`)

- **Today** only the private `sendLine` checks the abort flag under the gate, and marks a line in flight for
  `awaitLineWritten` (D/DispenseExecutor.java:62-105). `Session.sendAndCollect` bypasses both
  (D/DispenseService.java:1247, 1330).
- **New public methods** on the same `gate` and `inFlight` path:
  - `sendGated(Scope scope, String line, long timeoutMs, RunResult r)`: allowlist for the scope → abort check →
    `channel.send`. It appends to `r.streamedLines` and increments `r.linesSent`.
  - `queryGated(Scope scope, String line, long timeoutMs, RunResult r)`: the same, with `channel.sendAndCollect`
    (D/DriverGcodeChannel.java:78-89).
  - A timeout ≤ 0 is refused (`IllegalArgumentException`). The prober passes `commandTimeoutMs` plus the move's
    estimated time, so the vacuum on-time cap (§4.4) can always fire between lines.
- **What STOP then does to the probe:**
  - `machineAboutToBeDisabled` → `awaitLineWritten(50 ms)` also covers probe lines (D/DispenseService.java:134-143),
    so no probe Z step can follow the M410.
  - `exec.shutdown(r)` (D/DispenseExecutor.java:193-211) sends `M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B`
    (D/ControllerCommands.java:63-66), so N1's vacuum is vented.

### 4.3 N1 raw mapper (`D/OpenPnpRawMapper.java`)

- **The problem.** `new OpenPnpRawMapper(m, N1, "b")` throws, because N1's rotation maps to axis A
  (D/OpenPnpRawMapper.java:74-77).
- **New factory** `public static OpenPnpRawMapper forProbeNozzle(ReferenceMachine m, Nozzle n, String bAxisName)`.
  - It calls a private constructor with `requireRotationOnB = false`: the same X/Y/Z/driver checks.
  - `axB` stays the configured auger axis, used for `M18 B` and `resync`.
- **Two runtime checks** when a probe task starts, each refused with a PROBE message:
  - N1's Z is untransformed: `toRaw(x, y, 5).z == 5` and `toRaw(x, y, 10).z == 10`, both within 1e-6. The engine
    already relies on "N1's OpenPnP Z is its raw Z" (D/DispenseService.java:335-338).
  - N1 is not the dispenser nozzle.
- **N1 in the live config:** head offset (−24.178, −63.91) (MX:275), the bench's `N1` constant (`tools/probe.py:11`).
  Z is z1, with safe zone 26.5–36.5 (MX:208-216).
  - The bench later measured N1's true axis 0.39 mm from this offset (`tools/place.py:18`;
    PT/lessons.md:26). The engine keeps OpenPnP's offset and widens every keep-out by that error (§4.6).

### 4.4 The probe of one point: `D/VacuumProber.java` (new)

```java
public final class VacuumProber {
  public interface Io { void send(String line, long timeoutMs) throws Exception;            // exec.sendGated(PROBE, …)
                        List<String> query(String line, long timeoutMs) throws Exception;   // exec.queryGated(PROBE, …)
                        void sleep(long ms) throws Exception; }                             // abort-checked sleep
  public interface SpotLook { Look look(double x, double y) throws Exception; }             // §4.5; null = no look
  public static final class Settings { /* code constants (bench values) + the persisted knobs of §6.1 */ }
  public static final class Window { public double nominalZ, refZ, startZ, approachZ, floorZ, travelZ;
      public static Window of(double nominalZ, Double anchorZ, double travelRawZ, Settings s) throws DispenseService.RunRefusedException; }
  public static final class Target { public int n; public double x, y; public Double bx, by; public Double storedZ; // storedZ: check touches
                                     public List<double[]> alternates; public List<double[]> alternatesBoard; }
  public static final class Point  { public int n; public double x, y; public Double bx, by; public Double z;
                                     public String state;            // todo | look | now | contact | no-contact | skipped | not-bare
                                     public int alternate; public double baseline, drop, threshold;
                                     public Double coarseZ, lastOpenZ; public boolean fine, retried; public List<double[]> readings; // [z, mean]
                                     public List<String> notes; }
  public VacuumProber(Settings s, RawMapper n1, Io io, SpotLook look, RunResult r, BooleanSupplier aborted,
                      Consumer<Point> progress, BaselineMemory mem);
  public List<Point> probe(List<Target> targets, Window w) throws Exception;   // stops at the first target with no usable spot
  public double[] finalPlanned(); public double[] finalDriver();             // for OpenPnpRawMapper.resync
}
```

**Window**, all in N1's frame, which is raw z1. The example uses today's board top 4.50 (`runs/zprobe_current.json`
4.50–4.55):

| quantity | rule | bench | example: nominal 4.50, anchor 4.50 / 5.50 |
|---|---|---|---|
| `refZ` | `max(nominal, anchor.surfaceZMm)`; nominal alone without an anchor | – | 4.50 / 5.50 |
| `startZ` | nominal + `startAboveMm` (2.5) | 8.0 for a top ≈ 5.5 (`tools/register.py:40`) | 7.00 |
| `approachZ` | start + 1.0 | `z0 + 1` (`tools/probe.py:50`) | 8.00 |
| `floorZ` | `refZ − floorBelowMm` (1.5) | 4.0 (`tools/register.py:40`), ≈ 0.5 below today's board | 3.00 / 4.00 |
| `travelZ` | the dispenser mapper's `toRaw(travelSafeZMm).z`, i.e. the raw Z the dispense stream travels at (D/GcodeStreamBuilder.java:106-124), sent as N1 Z | `ZSAFE 31.5` (`tools/probe.py:15`) | 31.5 |

- **The floor is never deeper than either reference.** Without this, a board Z typed 1.0–1.2 mm low passes the anchor
  check (tolerance up to 1.2, D/DispenseConfig.java:104, 449-450), and N1 could push 2.5 mm into a hole.
- **After the first contact `zc1` in a task**, later points use `startZ = zc1 + startAboveMm` and
  `floorZ = max(floorZ, zc1 − 1.0)`. 1.0 mm covers the worst tilt seen, 0.94 mm corner to corner (`runs/zprobe_r5.json`).
- **Floor pushes** (descents that reach the floor without contact) are capped at **3 per task**. The task is
  REFUSED at the third: the no-contact text.
- `travelZ` uses the dispenser mapper because `travelSafeZMm` is an N2 height. It equals 31.5 in N1 terms only because
  31.5 is the see-saw midpoint (z2 = 63 − z1, MX:222-227). It must lie in z1's safe zone.
- A **check touch** (same seat, Z check) builds its window around the stored Z: start = storedZ + 2.5,
  floor = storedZ − 1.5.

**Refusals before anything moves:**

- `floorZ < 2.0` (an absolute N1 floor; it catches a board Z typed as 0).
- `floorZ` at or above the nominal (review 1): planning accepts a board top up to 2.0 mm under the needle-frame
  surface, but the floor sits 1.5 mm under the higher of the two, so the probe could not reach such a board. Refused
  as `PROBE: the board top Z 3.500 lies 2.000 mm under the needle-frame surface 5.500, and the probe goes at most 1.5
  mm under the higher of the two (down to Z 4.000): it cannot reach that height. Set the board top Z to roughly the N1
  contact height, or record the needle frame again; if both are right, probe by hand` (Register's preview shows it in
  `frame.message`). A weigh coupon's window is its own (±`couponWindowMm` around its place Z) and is not refused so.
- A needle anchor exists and `|nominal − anchor.surfaceZMm| > zFrameToleranceMm`. I4 would refuse such a surface anyway
  (D/Interlocks.java:212-216). The text names where and when the anchor was taken (§6.2; §14 question 1).
- A **board** probe without an anchor. I4 refuses an unanchored surface ("the needle Z frame is not set",
  D/Interlocks.java:145-148), so probing would be wasted.
- `travelZ` outside z1's safe zone.
- On a non-simulated machine, `vacuumSettleMs < 1000` or `readDelayMs < 65`.
  - Those values are only for the simulator. On the real VAC1, a reading 0.3 s after the valve opened was still
    falling and looked like a seal (PT/lessons.md:25).
  - B27 sets 62.5 ms sleep-mode conversion (MX:452), so reads closer together repeat stale values.
- N1's OpenPnP state:
  - `N1.getPart() != null` → "OpenPnP says N1 holds a part" (O/spi/Nozzle.java:168);
  - `N1.getNozzleTip() == null` → "no nozzle tip on N1" (O/spi/Nozzle.java:24).

**Exact G-code** for the example (nominal 4.50, target N1 tip (303.806, 122.623), i.e. raw head (327.984, 186.533)).
Every line goes through `io.send`/`io.query`, i.e. the probe allowlist plus the abort gate.

```
; once per task
M204 S2000                       ; cfg.travelAccelMmS2: a known acceleration for raw moves
M260 A112 B1 S1                  ; VAC1 init (tools/probe.py:49; the connect sequence MX:449-453)
M260 A109
M260 B48
M260 B27
M260 S1
; per point (target or alternate), after its camera look (§4.5)
G1 Z31.5000 F600                 ; travel Z, only if not already there (Z only)
G1 X… Y… F6000                   ; XY at travel Z through BacklashEmulator: one or two lines, exactly as
                                 ; GcodeStreamBuilder.moveNozzle does for N2 (D/GcodeStreamBuilder.java:459-481)
G1 Z8.0000 F600                  ; approach
M400
M106                             ; N1 pump on
M106 P1 S180                     ; N1 valve on
G4 P1500                         ; settle (PT/lessons.md:25)
5 x { sleep 70 ms; M260 A112 B1 S1; M260 A109 B6 S1; M261 A109 B3 S1 }     ; baseline (tools/probe.py:18-31, 52)
; coarse: z = start, start-0.25, ... while z >= floor (tools/probe.py:35-44, 54)
G1 Z7.0000 F300
M400
3 x { sleep 70 ms; read }        ; until mean < baseline - drop -> coarse contact zc; zo = last open coarse Z (zc + 0.25)
; release (new): back to the last open step and wait for the seal to let go
G1 Z{zo} F300
M400
3-read means every 70 ms until |mean - baseline| < drop/2; at most 1.5 s (else refuse "N1 did not release")
; fine (changed): z = zo-0.025, zo-0.05, ... down to max(zc-0.10, floor)
G1 Z{z} F300
M400
3 x read ... contact = the first z whose mean, and the next step's mean, are both < baseline - drop
; end of the point (also after no contact, and after any refusal inside the point)
G1 Z31.5000 F600
M400
M107 P1                          ; N1 valve off
M107 P0                          ; N1 pump off (the bench's bare M107, tools/probe.py:60)
```

**Why the fine pass differs from the bench** (offline parse of `runs/serial.log`, 158 sequences):

- The bench's fine pass starts at `zc + 0.30` right after the coarse seal (`tools/probe.py:57`). The vacuum has not
  let go by then:
  - 29 of 156 fine passes reported contact at their first step;
  - 79 reported it at or above the last coarse step that had read open air.
- The first fine reading sat 95–346 k counts below the baseline, against a threshold of about 230 k. Example,
  10-27 17:45:36: Z 5.75 read open, Z 5.50 sealed, and the result was "CONTACT 5.80".
- Contact Z comes out up to 0.3 mm high, against a 0.10–0.15 mm gap budget.
- The fix:
  - wait for the release;
  - start below the last open coarse step, where the bench data show the surface must lie;
  - require two sealed steps in a row. Partial seals reach ~250 k, the threshold's size (seal-drop deciles 257–404 k).
- **The fine pass finds nothing:** re-probe the point once from the start, with a new baseline. Still nothing → the
  point counts as no contact and the next alternate is tried. The coarse `zc` is never accepted (the bench does
  accept it, `tools/probe.py:58`).

**Reading.** Take the reply line containing `data:`. Parse its three hex bytes as a big-endian 24-bit two's-complement
number (`tools/probe.py:18-25`).

- The real reply is `echo:i2c-reply: from:109 bytes:3 data:E8 F2 F6`, then `ok` (`runs/serial.log:5550-5551`).
- No `data:` line → `PROBE: no vacuum reading from the controller (M261 answered: '<lines>')`.

**Threshold.** `drop = max(40000, 0.15·|baseline|)` (`tools/probe.py:53`); contact is `mean < baseline − drop`. At the
median baseline (−1.55 M) that is 232 k, under the first seal drop of 9 sequences in 10 (deciles 248–404 k).

**Open-air checks**, before any descent:

1. **Absolute window** `openAirMinCounts ≤ baseline ≤ openAirMaxCounts` (−1,900,000 … −1,200,000).
   - Settled probe.py baselines lie between −1.674 M and −1.480 M.
   - Toward 0 → "no tip, a leak, or the N1 pump is off". Below → "blocked".
2. **Spread** of the 5 reads ≤ 15,000. The largest spread seen was 4.1 k.
   - Too large: `G4 P<vacuumSettleMs>` once more, then read again. Still too large: refuse.
3. **Relative: a part on N1 or a partial blockage.**
   - The checks:
     - vs the first point of this task: |Δ| ≤ 50 k;
     - vs `BaselineMemory.last` (memory, updated after every good baseline, used if under 2 h old): |Δ| ≤ 60 k.
   - The data:
     - consecutive probe.py baselines differ by at most 36 k;
     - a session spans at most 64 k;
     - a held 0603 reads at least 80 k below open air (`tools/place.py:37`: "a held 0603 reads >= 2*SEAL below open
       air", SEAL 40000).

   A part on the tip would seal against the board about 0.45 mm high, so the surface would be saved 0.45 mm high and
   the dots would not transfer. Parts do stay on the N045 tip on this machine (PT/todo.md:186).
4. **Already sealed at `startZ`** (the first coarse mean is under the threshold): refuse. Something is under N1, or the
   board is ≥ 2.5 mm higher than its Z says.

**No contact down to the floor:** the point ends with the normal end lines, and the next alternate is tried with a new
baseline (up to `alternates`, 2). If all fail, `probe()` returns that point as `no-contact` and the following points
as `skipped`; the task refuses with the "probably a hole or via" text.

**Contact Z** is the N1 Z of the contact step (N1 Z = raw Z, §4.3). The surface point is `(x, y, z)`, with x/y the N1
tip's machine XY, the frame of `SurfacePoint` (D/DispenseConfig.java:278-290).

**Exits.** The bench lifts on every exit (`tools/probe.py:59-60`). So does the prober, except on STOP:

| what ended the point | what the prober does | then |
|---|---|---|
| STOP (`AbortedException`) | no motion; `exec.shutdown(r)` vents N1 | ABORTED; the caller runs `Session.recover()` (un-home). N1 stays where M410 stopped it; the next home raises it first (§2.3). |
| a refusal inside the point (baseline, release, sealed at start, OpenPnP state), a parse error, the on-time cap, a controller `Error:` line | the end lines above (`G1 Z<travel> F600`, `M400`, `M107 P1`, `M107 P0`) through the gate; resync N1 from `finalPlanned()` | REFUSED or ERROR; the machine stays homed (no M410 was sent) |
| the end lines themselves fail | `exec.shutdown(r)` | ERROR; `Session.recover()` |

**Safety asserts in the prober** (each a unit test):

- every Z it sends lies within `[floorZ, travelZ]`;
- X/Y words appear only while Z == travelZ;
- the only output lines are `M106`, `M106 P1 S180`, `M107 P1`, `M107 P0`;
- vacuum on-time per point ≤ 60 s (ERROR "N1 vacuum on for 60 s at one point" after the end lines).

**After a normal end**, the caller resyncs OpenPnP:
`n1Mapper.resync(prober.finalPlanned(), prober.finalDriver(), n1Mapper.currentB())` (D/OpenPnpRawMapper.java:198-212).
Before the first line the caller waits for still-stand, as `runSegment` does (D/DispenseService.java:1292).

**Timing per point** with the defaults, ≈ 17 s:

| part | time |
|---|---|
| camera look | ≈ 1.5 s |
| travel | ≈ 2 s |
| settle | 1.5 s |
| baseline | 0.4 s |
| ≈ 10 coarse steps | 3.7 s |
| release | ≈ 0.5–1.5 s |
| ≈ 14 fine steps | 5.2 s |
| lift | ≈ 1.5 s |

In `runs/serial.log` the bench's steps were 0.37 s apart (median; p10–p90 0.33–0.38 s).

### 4.5 Look before touching: `V/BareSpotCheck.java` (new, pure)

N1 must not touch wet paste or a loose part. Paste in the tip fouls N1, and the next baseline then reads "blocked".

- **On the blank:** it slid ~+2 mm in Y between sessions (PT/todo.md:209). Spots 2 mm beside a purge row can then
  land on old purge dots, and a loose resistor sat on the blank.
- **On the board:** paste can sit ~0.4 mm off a pad (PT/todo.md:186).

So every target, and every alternate when it is used, gets one top-camera frame before N1 goes there:

- The board's camera tour runs right after the fiducials, while the camera is already over the board. The blank needs
  one frame per spot.
- `BareSpotCheck.check(BufferedImage img, double mmPerPxX, double mmPerPxY, Settings s)` → `{bare, foreignShare,
  ringSpread, reason}`. In the frame centred on the spot:
  - **reference colour** = the median BGR in the ring 1.5–3.0 mm from the centre (the local surface);
  - **foreign pixels** = pixels in the disc of radius 1.0 mm whose BGR distance from the reference exceeds
    `colourDelta`. 1.0 mm = tip radius 0.35 + N1 offset error 0.4 + 0.25 margin;
  - **not bare** if the foreign share is above `maxForeignShare`, or the ring itself is not uniform (its MAD is above
    `maxRingSpread`: an edge, the blank's border, tape).
- **Defaults** (code constants) are fixed offline by the vision slice on bench frames:
  - bare: `runs/bare1.jpg`, `runs/bare2.jpg`;
  - purge dots on the blank: `runs/blk*.jpg`, `runs/blank_edge.jpg`;
  - mask around fiducials: `runs/board3_FID*_*.jpg`.

  The test records the margin each frame has. Real-machine confirmation is §11 item 10.
- **A spot that is not bare** is treated like a no-contact spot: the next alternate, note "paste, a part or print
  seen". All spots of a target not bare → REFUSED (text §6.2). Silkscreen also fails the check; that only costs an
  alternate.
- **In the engine:** `VacuumProber.SpotLook`, implemented in DispenseService with `imager().captureLit(x, y, refZ)`
  and `BareSpotCheck`. Without a camera there is no look, and the result says so.

### 4.6 Where to probe: `D/ProbeTargets.java` (new, pure, deterministic)

**Board.** Inputs: `BoardFeatures`, the plan's dots in board mm (the inverse of the plan's own transform, §5.1), and
the settings. Everything is in board mm.

1. **Allowed region**: inside the outline, shrunk by 2.0 mm. If the outline is unknown: the bbox of all pads and
   fiducials, grown by 3 mm.
2. **Keep-outs.** `padKeepOutMm` (persisted, default **1.4** = 1.0 + 0.4 for N1's measured offset error) is measured
   from the pad edge to the target centre.
   - every pad rectangle, oriented, grown by `padKeepOutMm`;
   - every fiducial, radius = mask radius + `padKeepOutMm` (1.5 + 1.4 = 2.9);
   - every pad with `known = false`, radius 1.5 + `padKeepOutMm`.
3. **Ideal points**:
   - `vacuumProbe.boardSpots[<board file name>]`, when set: an operator list of proven bare spots in board mm, e.g.
     the bench's ftp spots (8,8), (67,8), (67,67), (8,67), (37,37) (`tools/register.py:15`; alternates from
     `tools/zmap.py:6`);
   - otherwise the four corners of the dots' bbox and its centre. On ftp the bench's spots leave the outermost pads
     ≤ 3.1 mm outside (src/test/sim/il_sim_acceptance.py:74-76), inside the 3.5 mm I4 margin.
4. **Nearest free point** for each ideal point:
   - candidates on a 0.25 mm grid within 6.0 mm of the ideal point, in the allowed region and outside every keep-out;
   - sorted by distance to the ideal point, then by distance from the dots' centroid (larger first: keeps the hull
     wide), then by y, then x. Take the first;
   - none → `PROBE: no bare spot within 6.0 mm of board X 70.0, Y 5.0 (pads, fiducials or the board edge all round): probe by hand`.
5. **Drop duplicates** closer than 3 mm.
6. **Coverage, only for uncovered dots.** While a dot lies more than `surfaceMarginMm − 0.5` outside the targets'
   convex hull, and there are fewer than 9 targets: take the worst dot, push it 1 mm outward from the centroid, add its
   nearest free point.
   - More points do not help flatness. The bench's 9-point `runs/zmap.json` has a 0.048 mm residual, over the
     0.04 limit (D/DispenseConfig.java:106); its 5-point maps sit at 0.015–0.039. So ftp normally gets 5.
   - The hull distance is `SurfacePlane.distanceOutside` of a z = 0 fit of the targets (D/SurfacePlane.java:107-122).
   - Still uncovered → `PROBE: the dots cannot be covered with 9 probe points: probe by hand`.
7. **Alternates** for each target:
   - candidates on rings of 2.0 mm and 4.0 mm, every 30°;
   - free, in the allowed region, at least 2.0 mm from the target and from each other;
   - preferred if their distance from the centroid is ≥ the target's minus 0.5 mm; then by radius, then by angle;
   - take up to `alternates` (2).
8. **Mapping**: through the registration affine, to the machine XY of the N1 tip. The preview (§7.1) uses the plan's
   current transform.

**Blank** (`ProbeTargets.forRow`, machine mm):

- **Cover set** = every purge-row spot, used or not (D/DispenseConfig.java:192-213), plus the blank place if this
  surface is the blank.
- **Frame**: u = the row direction (the pitch vector, normalised; (1, 0) for fewer than 2 spots), n = its normal.
  Project the cover set onto them: s ∈ [s0, s1], t ∈ [t0, t1].
- **Ideal targets**: s ∈ {s0, (s0+s1)/2, s1} (only the ends when s1 − s0 < 6) × t ∈ {t0 − 2.0, t1 + 2.0}: 4 or 6
  points. This probes close to where the purge dots go, because the blank is bowed (REAL_MACHINE_TODO.md:20).
- **Keep-outs**:
  - used purge spots, radius 1.5 + 0.4 (they carry paste, W/setup.js:191-196);
  - the waste place and the test-dot spot, radius 3.0.
- **Nearest free point**, shifted along s first. **Alternates**: ±2.0 mm along s, outward first.
- **Coverage**: every cover point within `surfaceMarginMm − 0.5` of the hull.
- **Explicit targets** in the request (§7.4) replace all of this, e.g. for a blank with tape gaps
  (src/test/sim/il_sim_acceptance.py:82: "before the tape gap at x 331-339").
- The camera look (§4.5) is what catches a slid blank. Config coordinates alone cannot.

---

## 5. The tasks

### 5.1 Request, result and report objects (engine-core, Gson-friendly)

```java
public final class RegisterRequest {                 // D/RegisterRequest.java
  public DispensePlan plan;                          // the plan "Register board" was pressed on
  public BoardLocation nominal;                      // the board at its nominal seat, without our registration
  public String boardKey; public BoardRegistration.Nominal nominalSeat;
  public AffineTransform planToBoard;                // inverse of the plan's own board->machine transform (dots -> board mm)
  public double nominalZ;                            // file: source z; job: board global Z
  public ProbeMode probe = ProbeMode.AUTO;           // AUTO | ALWAYS | NEVER
  public Replanner replanner;                        // API callback, §5.3
  public interface Replanner { String replan(BoardRegistration reg) throws Exception; }   // returns the new planId
}
public final class RegistrationResult {              // D/RegistrationResult.java
  public RunResult run; public boolean ok; public String message;
  public String stoppedStage;                        // home | fiducials | same-seat | probe | save | plan, when ABORTED
  public HomeReport home;                            // D/HomeReport.java {needed, whyCode, why, done, seconds, n1RaisedFromZ}
  public List<FidResult> fiducials;                  // name, bx, by, expectedX, expectedY, x, y (null if not found),
                                                     // state found|not-found|ambiguous|not-centred|todo, iterations, frames,
                                                     // offsetPx, offsetFromExpectedMm, movedMm (vs the previous record)
  public FitResult fit;                              // method, affine[6], rotationDeg, scaleX, scaleY, shearDeg, maxResidualMm, limits, previous
  public String seat; public Double movedMm; public Long previousAtMs;   // first|same|reseated
  public String registrationId; public boolean registrationStored;
  public SameSeatReport sameSeat;                    // D/SameSeatReport.java {points[3]: stored z, z, delta; pass; zCheckStamped}
  public ProbeReport probe;                          // D/ProbeReport.java {mode, needed, why, skipped, window, points[], plane, saved, surfaceSetAtMs}
  public String newPlanId; public boolean simulated;
}
public final class SurfaceProbeRequest { public String surfaceName; public double nominalZ; public List<double[]> targets; /* null = ProbeTargets.forRow */ }
public final class SurfaceProbeResult  { public RunResult run; public boolean ok; public String message; public String stoppedStage;
                                         public HomeReport home; public String surfaceName; public VacuumProber.Window window;
                                         public List<VacuumProber.Point> points; public PlaneReport plane; public boolean saved; }
// D/PlaneReport.java {a, b, c, maxResidualMm, slopeMmPerMm, minZ, maxZ, coversSpots, spots}
// D/ZCheck.Result {atMs, homeAtMs, refName, x, y, refZ, z, deltaMm, pass}
```

Other engine additions:

- **`RunResult`** gets `public String nextPlanId`, `public Boolean homedFirst` and `public ZCheck.Result zCheck`
  (D/RunResult.java:30-61).
- **`DispenseListener`** gets `default void onDetail(RunResult run, Map<String, Object> detail) {}`
  (D/DispenseListener.java:3-13), for live structured progress (§7.5). `RunTracker` and `PressureHold` implement the
  interface and need no change unless they use it.

### 5.2 Register board: `DispenseService.registerBoard(RegisterRequest req)` → `Future<RegistrationResult>`

`newRun("board-registration", plan.planId, plan.boardName, 0, 0)`. `runs.kind` is free text (D/DispenseDb.java:119-124).
Before submitting, set `cameraTourAbort = false` (as D/DispenseService.java:790). Submit with `submitResult`, so the task
shares the busy flag and `finish()` (D/DispenseService.java:1132-1188).

```
if (!m.isEnabled()) REFUSED "MACHINE: the machine is not started: press Start first"          // §2.7
if (pressureHoldActive()) REFUSED "the pressure hold is on: Pressure off first"                // §2.7
Session s = new Session(c, r);                       // publishes the executor; M115 (1222-1268) -> SimGuard.noteIdentity (§9.1)
if (cameraTourAbort) s.exec.requestAbort("STOP before the start");
BooleanSupplier aborted = () -> s.exec.isAbortRequested() || cameraTourAbort;
stage = "home"; homeIfNeeded(s, res.home, aborted);                    // §2.3
r.interlocks = Interlocks.checkBasic(inputs(homeFirst = false)); refuse on violations (MACHINE, CONFIG)
BoardRegistration stored = null;
try {
  stage = "fiducials"                                                   // phase "fiducials: FID2 (2 of 3)"
  f = boardFeatures(req.nominal.getBoard()); prev = matching record (§3.6)
  found = FiducialLocator(over imager()::captureLit with nominalZ, aborted).locateAll(expected (§3.7))
  fit = FiducialLocator.fit(found, prev, settings)                      // REFUSED "REG: …" on Failure; nothing stored
  seat, movedMm = §3.6 (uses cfg.newSeatAtMs); reg = seat == same ? prev(+lastChecked*) : new BoardRegistration(…)
  if (aborted) throw AbortedException                                   // nothing stored yet
  storeRegistration(reg); db.event(r.runId, "INFO", "REG", json); stored = reg
  registered = reg.applyTo(req.nominal); tie = boardRegistration(registered)
  if (mode == NEVER) { probe.skipped = true; s.ok(); return }
  full = mode == ALWAYS || seat != same || !surfaceTiedTo(tie)
  n1 = OpenPnpRawMapper.forProbeNozzle(m, n1Nozzle(c), c.bAxisName); checks (§4.3); window refusals (§4.4)
  m.getMotionPlanner().waitForCompletion(null, WaitForStillstand)
  prober = new VacuumProber(settings, n1, gatedIo(s.exec, PROBE, r), spotLook, r, aborted, progress, baselineMemory)
  if (!full) {
     stage = "same-seat"; check = prober.probe(3 stored points (§3.6), check windows)
     if all |z - stored| <= 0.03: keep surface; if those points predate lastHomeAtMs: homeTracker.zCheck = pass (§2.4)
     else full = true; note "same seat, surface changed: probed again"
  }
  if (full) {
     stage = "probe"
     targets = ProbeTargets.forBoard(f, dotsBoard(req.plan, req.planToBoard), cfg) mapped through reg
     window = Window.of(req.nominalZ, anchorZ, travelRaw, settings)       // REFUSED "PROBE: …"
     points = prober.probe(targets, window)
     n1.resync(prober.finalPlanned(), prober.finalDriver(), n1.currentB())
     any no-contact / not-bare -> REFUSED "PROBE: …"
     plane = SurfacePlane.fit(points)                                   // REFUSED on IllegalArgumentException
     checks: residual <= surfaceMaxResidualMm; every z within anchor ± zFrameToleranceMm;
             every plan dot (mapped through reg) within surfaceMarginMm of the points' hull
     stage = "save"
     if (aborted) throw AbortedException                                // a STOP after the last point saves nothing
     setSurface(cfg.boardSurfaceName, points, note, tie, simulated); db.event(…, "PROBE", json); clear newSeatAtMs
  }
  s.ok()
} catch (prober exception) {                                            // the exits of §4.4 already ran
  ABORTED -> s.recover(); ERROR -> s.recover() only if the end lines failed
} finally {
  if (stored != null) {                       // after a refused probe or a STOP too: the registration is real
     stage = "plan"
     try { res.newPlanId = req.replanner.replan(stored); r.nextPlanId = res.newPlanId; phase(r, "plan: planned again"); }
     catch (Exception e) { r.warnings.add("plan again failed: " + msg); }
  }
}
```

- **`setSurface`** gets an overload with `simulated` (D/DispenseService.java:360-375). `SurfaceMap` gets
  `public Boolean simulated` (D/DispenseConfig.java:292-303).
- **The surface note**: `"Register board HH:MM: N points by N1 vacuum touch (registration 3f2a…, run 7c1e…)"`.
- **The run's `message`** is the result card's first sentence (§8.1). `ok` = status OK.
- **Outcome classes.** An ERROR or ABORTED opens Recovery in the UI (W/core.js:1592-1594).

| Outcome | RunResult.status | Saved |
|---|---|---|
| everything done | OK | the registration (new or kept), the surface if probed (kept on a passing same-seat check), a new plan |
| fiducial not found / ambiguous / not centred / fit outside limits / fewer than minFiducials | REFUSED | nothing. No N1 line was sent. |
| probe refused (window, baseline, release, sealed at start, not bare, no contact after alternates, flatness, anchor band, coverage, no reading, OpenPnP N1 state) | REFUSED | the registration and a new plan; **no** surface. N1 is lifted and vented, and the machine stays homed. |
| homing failed | REFUSED (`MACHINE: homing failed …`) | nothing |
| STOP during home or fiducials | ABORTED | nothing |
| STOP during the same-seat check, the probe or right before the save | ABORTED | the registration and a new plan; never a partial surface |
| controller error / timeout / vacuum on-time cap | ERROR | as for a probe refusal (N1 lifted when the link still answers) |

### 5.3 Plan again at the end (`Replanner`, implemented by the API)

The `PlanStore` is API state (A/ApiContext.java:23), so the endpoint passes a `Replanner`. It runs on the machine
thread at the end of the task:

```
src = copy of entry.source + {"registrationId": reg.id}
plan = svc.planBoard(dispense.baseBoardLocation(src), reg)     // baseBoardLocation = currentBoardLocation without the registration
e2 = new PlanStore.Entry(plan, src); e2.registrationFuture = theSameFuture; estimate(svc, e2); ctx.plans.put(e2)
return plan.planId
```

Optimisation: if the seat is `same`, the surface was kept, and the entry's plan already carries this registration id
and the current surface fingerprint, return the same `planId` and plan nothing.

### 5.4 Progress and records

**Phase strings** (`phase()` → a PHASE event + `RunTracker.onPhase`, D/DispenseService.java:1190-1200). None of them is
in `RunTracker.PRESSURE_PHASES` (A/RunTracker.java:41-43), so the UI never shows the pressure as on.

```
waiting for OpenPnP motion to complete      (existing, Session)
home: <why>                                 (" · raising N1 from Z 5.40" when it was low)
z check: <ref name>                         (Run and the Z check task)
fiducials: FID1 (1 of 3)                    (per move: "fiducials: FID1 (1 of 3) · move 2"; " · frame 2 of 3" when searching)
fiducials: fit
same seat: point 2 of 3
probe: look                                 (the camera tour of §4.5)
probe: point 2 of 5                         (" · alternate 1" when an alternate is tried)
surface: saved
plan: planned again
```

**Structured detail**: `onDetail(run, map)`, exposed as `status.run.detail` (§7.5). It is sent at every phase, every
fiducial capture and every probe Z step, at most once per step; the map is a small copy.

**Records**:

- the `runs` row (`db.insertRun`, D/DispenseService.java:1169) and a `RUN_END` event;
- `REG`, `PROBE` and `ZCHECK` events with the full JSON: positions, readings at each Z, the release wait, and the
  camera look's shares. The real machine will need them for diagnosis.

### 5.5 Board run with homing and the Z check

In `runPlan`, between `new Session` and the first interlock check:

1. the pressure-hold refusal (§2.7);
2. `homeIfNeeded(s, rep, s.exec::isAbortRequested)`;
3. `zCheckIfNeeded(s, plan, …)` (§2.4).

`RunResult.homedFirst` and `RunResult.zCheck` record what happened. The UI's run phase list shows a "Home" row and a
"Z check" row when `run.phases` contains `home:` / `z check:` (§8.3).

### 5.6 Run endpoint: registration, homing and Z

`POST /api/run` (A/DispenseEndpoints.java:694-728):

- **`currentBoardFingerprint`** catches `unavailable: …`. If `now` starts with `unavailable:`, the run is refused with
  `"the board's registration or position changed since this plan was made (" + reason + "): plan the board again"`
  instead of the generic text (:709-712).
- **`homeFirst`** = `svc.homePlan().needed`; **`zCheckFirst`** as in §2.6. Both go to `preflightPlan`. The 202 body
  gets `homeFirst, homeWhyCode, homeWhy, homeSinceMs, homeEstimateS, zCheckFirst, zCheckReference`.
- **`ctx.requireMachineIdle()`** (§2.7).

### 5.7 Probe the blank: `DispenseService.probeSurface(SurfaceProbeRequest)` → `Future<SurfaceProbeResult>`

- **Kind and label**: kind `surface-probe`, label `blank probe`.
- **Steps**:
  1. the enabled and pressure-hold refusals;
  2. the Session;
  3. `homeIfNeeded`;
  4. `checkBasic`;
  5. targets: `ProbeTargets.forRow`, or the explicit ones (the alternates of explicit targets are ±2 mm in X);
  6. `Window.of(nominalZ, anchorZ, …)`. The nominal is the blank place's z (D/DispenseConfig.java:229-245) or the
     request's `z`;
  7. `VacuumProber`, with the camera look per spot;
  8. `SurfacePlane.fit` and the checks: residual, anchor band when an anchor exists, cover set within the margin;
  9. the STOP check before the save;
  10. `setSurface(name, points, note, null, simulated)`. The blank stays untied, as today: D/DispenseEndpoints.java:418-425
      ties only with a planId.
- **Events**: a PROBE event.
- **Outcomes**: the same classes as §5.2, without the fiducial rows. Nothing is saved unless every point touched and
  the plane passed.

### 5.8 Check Z: `DispenseService.checkZ()` → `Future<RunResult>`

- **Kind and label**: kind `z-check`, label `z check`.
- **Steps**: the enabled and pressure-hold refusals, then the Session, then `checkBasic`, then `zCheckIfNeeded`. It
  never homes: it refuses `MACHINE: machine is not homed` like prime.
- **Not needed** (no stale Z data): OK, message "Z data were measured after the last home: nothing to check".
- **Cost**: one camera look and one N1 touch, ≈ 20 s.
- This is the fix-it behind the I4 "Z not checked" refusal of prime, the test dot, the touch-off, the dot ladder and
  shots (§8.2).

---

## 6. Settings

### 6.1 Settings (in `DispenseConfig`, persisted to `dispense-config.json`, never machine.xml)

Only the knobs a site might tune are persisted. Each gets a null guard in `fromJson` (D/DispenseConfig.java:357-382)
and range checks in `validate()` (D/DispenseConfig.java:434-508). Those surface as CONFIG violations
(D/Interlocks.java:252-254) and as 400s on `PUT /api/config` (A/DispenseEndpoints.java:490).

- **Merge and protection.**
  - `fiducialCheck` and `vacuumProbe` are added to `NESTED_SETTINGS` (A/DispenseEndpoints.java:48-49) and to `NESTED`
    in W/setup.js:26.
  - `boardRegistrations` and `newSeatAtMs` are added to `PROTECTED_SETTINGS` (A/DispenseEndpoints.java:44-46) and to
    `STAMPED_TOP` in W/setup.js:24.
- **No schema bump.** These are new fields with defaults; `migrate()` only acts below schema 3
  (D/DispenseConfig.java:389-404).

| Setting | Default | Valid | Meaning / source |
|---|---|---|---|
| `homeBeforeRun` | `"whenNeeded"` | `whenNeeded` \| `always` | §2 (brief) |
| `fiducialCheck.minFiducials` | 2 | 2..10 | brief |
| `fiducialCheck.searchRadiusMm` | 8.0 | 1..12, and ≤ 9 frames at the camera's size | §3.3 (bench re-seats up to 6.9 mm) |
| `fiducialCheck.seatToleranceMm` | 0.10 | 0.01..1 | brief "moved > 0.1 mm → re-seated" |
| `vacuumProbe.startAboveMm` | 2.5 | 0.5..10 | brief, `tools/register.py:40` |
| `vacuumProbe.floorBelowMm` | 1.5 | 0.2..3 | brief, `tools/register.py:40` |
| `vacuumProbe.openAirMinCounts` / `openAirMaxCounts` | −1900000 / −1200000 | min < max < 0 | §4.4 |
| `vacuumProbe.alternates` | 2 | 0..4 | brief |
| `vacuumProbe.padKeepOutMm` | 1.4 | 0.5..5 | §4.6 (1.0 + N1 offset error 0.4) |
| `vacuumProbe.vacuumSettleMs` | 1500 | 0..5000; ≥ 1000 enforced at task start unless simulated (§4.4) | `tools/probe.py:51`, PT/lessons.md:25 |
| `vacuumProbe.readDelayMs` | 70 | 0..500; ≥ 65 enforced at task start unless simulated | `tools/probe.py:31`, MX:452 |
| `vacuumProbe.boardSpots` | `{}` | board file name → 3..9 `[bx, by]` pairs, finite | §4.6 |
| `boardRegistrations` | `{}` | engine only (protected); ≤ 20; each affine finite and invertible | §3.6 |
| `newSeatAtMs` | null | engine only (protected) | §3.6 |

**Code constants**, not persisted: bench values in `FiducialDetector.Settings`, `FiducialLocator.Settings`,
`VacuumProber.Settings`, `BareSpotCheck.Settings` and `ZCheck`. Tests set them directly. They are:

- **Fiducial detection:** the `fid.py` detector numbers; the centring loop (1.0 mm, 3 px, 3 moves); the fit limits
  (1 %, 0.5°, 0.05 mm; 0.15 %, 0.15° against the previous registration).
- **Probe motion:** the steps (0.25 / 0.025 mm), feeds (F300 / F600 / F6000), reads (5 / 3), the drop rule
  (40 000, 15 %), the release wait (1.5 s), the spread limit 15 k and the relative baseline limits (50 k, 60 k, 2 h).
- **Probe limits:** the 60 s on-time cap, the absolute floor 2.0, the first-contact band 1.0 mm, 3 floor pushes per
  task, the Z-check tolerance 0.03 mm.
- **Probe targets:** the edge, fiducial and blank keep-outs, the 6 mm target search, 9 points, the 2 mm alternate
  distance and the blank offset 2 mm.

Each can be promoted to a setting once the real machine shows it needs tuning (§11).

### 6.2 Refusal and failure texts (exact; the prefixes drive the UI's fix-its)

The prefixes `REG:` and `PROBE:` are added to the client's code regex `^(I\d+|MACHINE|CONFIG|PLAN):`
(W/core.js:2340). Counts carry the unit "counts". Where a text says "Board step", the UI links it.

| When | Status / HTTP | Text |
|---|---|---|
| fewer fiducials than `minFiducials` | 409 | `REG: the board has 1 fiducial (FID1); Register board needs at least 2. Check the board X/Y in the Board step (Use camera X/Y on the board origin) and probe by hand (Do it by hand).` |
| no top camera / dot imager | 503 | `REG: no top camera in OpenPnP: Register board needs it. Check the board X/Y in the Board step and probe by hand.` |
| board bottom-up | 409 | `REG: the board is placed bottom-up; only the top side can be registered` |
| not started | 409 (interlocks) | `Register board refused by the interlocks: MACHINE: machine is not enabled` |
| busy / pressure hold / OpenPnP busy | 409 | existing texts (A/ApiContext.java:80-92) / `OpenPnP is busy (homing or moving): wait for it to finish` |
| not found (file board) | REFUSED | `REG: FID2 not found within 8.0 mm of X 310.16, Y 184.22 (3 camera frames). Is FID2 clean and uncovered, and does the board sit roughly where it was planned? Check the board X/Y in the Board step (Use camera X/Y on the board origin), then register again.` |
| not found (job board) | REFUSED | the same, ending `…, or run OpenPnP's fiducial check on this job board, then register again.` |
| ambiguous | REFUSED | `REG: another round mark near FID2 (within 8.0 mm of X 310.16, Y 184.22): look with the camera there, then register again` |
| not centred | REFUSED | `REG: FID1 did not hold still in the camera (still 4.8 px off after 3 moves): wipe FID1 (paste or dust on it?) and register again` |
| fit, absolute | REFUSED | `REG: the fiducials do not fit the board: FID1 to FID3 measure 65.69 mm, the board file says 65.30 mm. One was probably found on the wrong mark: look with the camera at FID1 and FID3, then register again.` |
| fit, vs previous | REFUSED | `REG: the fiducials do not fit this board's last registration (13:05): FID2 to FID3 measure 50.18 mm, 50.05 mm then. One was probably found on the wrong mark: look with the camera, then register again.` |
| residual (n ≥ 4) | REFUSED | `REG: FID4 is 0.12 mm off where the other fiducials put it (limit 0.05 mm): it was probably found on the wrong mark` |
| OpenPnP re-checked the job board | 409 | `REG: OpenPnP's fiducial check of this job board changed since registration 3f2a… (13:05): register again` |
| window floor | 409 or REFUSED | `PROBE: the board top Z 0.000 puts the probe floor at -1.500, below the lowest N1 Z the probe may use (2.000): set the board top Z to roughly the N1 contact height` |
| board probe without needle frame | 409 or REFUSED | `I4: the needle Z frame is not set: record it first (Setup › Needle Z frame); the probe checks its heights against it` |
| nominal vs frame | 409 or REFUSED | `PROBE: the board top Z 4.000 is 1.500 mm from the needle-frame surface 5.500 (recorded at X 322.0, Y 225.5 on 10-28 13:02; tolerance 1.00): set the board top Z to roughly the N1 contact height, or record the needle frame on this board` |
| simulator timings on the machine | 409 or REFUSED | `PROBE: vacuumProbe.vacuumSettleMs 300 is a simulator setting: the real VAC1 needs at least 1000 ms (a reading 0.3 s after the valve opened looked like a seal)` |
| OpenPnP N1 state | 409 or REFUSED | `PROBE: OpenPnP says N1 holds a part (R5): discard it first` / `PROBE: OpenPnP shows no nozzle tip on N1: load the N045 tip first` |
| baseline toward 0 | REFUSED | `PROBE: N1's vacuum reads -0.41 M counts in open air at X 303.81, Y 122.62 (normal -1.90 M to -1.20 M counts): no nozzle tip on N1, a leak, or the N1 pump is off. Check N1, then try again.` |
| baseline too strong | REFUSED | `PROBE: N1's vacuum reads -2.11 M counts in open air (normal -1.90 M to -1.20 M counts): the tip is blocked or a part is stuck on it. Check N1, then try again.` |
| baseline vs recent | REFUSED | `PROBE: N1's vacuum reads -1.64 M counts in open air, 95 k counts stronger than at 13:02 (-1.55 M counts; limit 60 k): a part may be stuck on N1 or the tip is partly blocked. Look at N1, then try again.` |
| unsteady | REFUSED | `PROBE: N1's vacuum is not steady in open air (spread 85 k counts, limit 15 k) after a second settle: check the N1 tubing and pump` |
| did not release | REFUSED | `PROBE: N1 did not let go of the surface within 1.5 s after the touch at X 303.81, Y 122.62: check the N1 tip and tubing, then try again` |
| sealed at start | REFUSED | `PROBE: N1 sealed already at Z 7.000, 2.50 mm above the board top: something is under N1 or the board is higher than its Z. Check, then try again.` |
| not bare | REFUSED | `PROBE: something on the surface at board X 8.0, Y 8.0 and 2 spots nearby (paste, a part or print: up to 14 % of the spot): wipe it, or probe by hand` (blank: `at X …, Y …` in machine mm) |
| no contact | REFUSED | `PROBE: no contact at board X 8.0, Y 8.0 or 2 spots nearby (N1 went down to Z 3.000, 1.50 mm below the board top): probably a hole or via there, or the board top Z is wrong. Probe by hand, or check the board Z and try again.` |
| flatness | REFUSED | `PROBE: the board is not flat enough for one plane: point 3 (board X 67.0, Y 67.0) is 0.061 mm off (limit 0.040). Press the board flat (clamp it) and register again; by hand needs the same flatness.` |
| anchor band | REFUSED | `I4: N1 touched at Z 4.120 at point 2, 1.380 mm below the needle-frame surface 5.500 (tolerance 1.00): the board top Z or the needle frame is off` |
| coverage | REFUSED | `PROBE: the probed points leave 3 dots more than 3.5 mm outside the probed area (an alternate moved inward): probe by hand, or try again` |
| no reading | REFUSED | `PROBE: no vacuum reading from the controller (M261 answered: 'ok'): probe by hand` |
| target search | 409 or REFUSED | `PROBE: no bare spot within 6.0 mm of board X 70.0, Y 5.0 (pads, fiducials or the board edge all round): probe by hand` |
| N1 config | 409 | `PROBE: nozzle 'N1' is not in the machine` / `PROBE: N1's Z is transformed in OpenPnP; the probe needs N1 Z = raw Z` |
| homing failed | REFUSED | `MACHINE: homing failed: <reason>. Nothing else was done; home from the strip and try again.` |
| Z not checked | I4 violation | `I4: the needle frame (13:02), the touch-off (13:05) and the 'board' surface (13:40) were measured before the home at 14:19, and Z has not been checked since: Check Z (one N1 touch, ≈ 20 s); Run does it by itself` |
| Z moved | REFUSED / I4 violation | `I4: Z moved -0.70 mm at the home of 14:19: N1 touched the blank at X 322.00, Y 225.50 at Z 4.920, where it touched 5.620 at 13:40. The needle frame, the touch-off and the surfaces are off by this (the needle by about twice). Record the needle frame again with both tips (Setup › Needle Z frame), probe the blank, take the places' Z again with N1 (Setup › Places), touch off and register the board again.` |
| no Z reference | 409 / REFUSED | `I4: Z must be checked after the home at 14:19, but no point measured before it is left to touch (no blank or board surface, no needle-frame X/Y): record the needle frame again` |
| STOP | ABORTED | the executor's abort reason, e.g. `STOP pressed in the web UI`; the result card says what was kept (§8.1) |
| vacuum on too long | ERROR | `N1 vacuum on for 60 s at one point: stopped (N1 lifted, pump and valve off)` |
| registration gone (plan/run) | 409 | `REG: registration 3f2a… is not stored any more (the board was registered again since): plan again` |
| registration of another board | 409 | `REG: registration 3f2a… belongs to another board or position` |
| simulated registration on the machine | 409 | `REG: registration 3f2a… was made on the simulator: register again` |
| simulated surface on the machine | I4 violation | `the 'board' surface was probed on the simulator: probe it again on this machine` |
| blank probe of the board surface | 409 | `PROBE: the 'board' surface is probed by Register board (Run › Register)` |
| blank probe without a nominal | 409 | `PROBE: set the 'blank' place first: its Z (the N1 contact height) sets the probe window` |

---

## 7. Web API (conventions of WEB_API.md §2–3; A/ApiServer.java:322-353; A/ApiContext.java:106-137)

All new routes live in a new endpoint group, `A/RegistrationEndpoints.java`, registered in the `ApiServer` constructor
next to the others (A/ApiServer.java:111-117). That keeps `DispenseEndpoints` (778 lines) from growing further. The
plan, run and status changes stay in `DispenseEndpoints`, and the home change in `MachineEndpoints`.

### 7.1 `POST /api/plan` (changed)

- **Body** adds `"registration": "auto" | "none" | "<registrationId>"` (default `auto`).
  - `auto` applies the stored record for the board key if it matches the nominal (§3.6).
  - `none` plans without fiducial correction. The UI offers it after a fiducial failure (§8.2).
  - An id must match, else 409 (texts §6.2).
- **Response** (`Views.plan`, A/Views.java:205-290) adds:
  - `source.registrationId` (null when none);
  - `registration`: `{id, atMs, method, seat?, fiducials:[{name, x, y}], rotationDeg, scaleX, scaleY, shearDeg, simulated, lastCheckedAtMs, lastCheckMovedMm, beforeNextBoard}` or null. `beforeNextBoard` = `atMs < newSeatAtMs`;
  - `registrationTargets`: the preview, computed when the plan is made and kept in `PlanStore.Entry`:
    `{fiducials:[{name, bx, by, x, y, searchRadiusMm}], probes:[{n, bx, by, x, y, alternates:[{bx, by, x, y}]}], window:{nominalZ, refZ, startZ, floorZ}, frame:{anchorZ, anchorAtMs, anchorX, anchorY, ok, message}, estimatedSeconds:{home, fiducials, sameSeat, probe, total}, home:{…}, fiducialCount, error}`.
    - `error` holds the target-search refusal text, if any.
    - `frame` is the §4.4 window and anchor check, so the page can show it before planning (§8.1).
  - `registrationRun` (the registration view of this entry's last register task, like `cleanCheck`, A/Views.java:288)
    and `registrationRunning`.
- `GET /api/plan/{id}` returns the same.

### 7.2 `POST /api/plan/{planId}/register`

**Body**: `{"wait"?: bool, "probe"?: "auto" | "always" | "never"}`. An unknown `probe` value is a 400.

**Checks, in order:**

1. `availableSvc`;
2. `requireEngineIdle`;
3. `requireNoPressureHold`;
4. `requireMachineIdle`;
5. `requirePlan` (404 `plan … is not known …`, A/DispenseEndpoints.java:616-622);
6. the board seat: 409 if the job is gone, the board is bottom-up, the source is invalid, or OpenPnP re-checked the job
   board;
7. the fiducial count;
8. a camera / imager is present;
9. `ApiContext.refuseUnlessOk(svc.checkBasicInterlocks(homeFirst), "Register board")`;
10. when the probe may run: the window, needle-frame, simulator-timing and OpenPnP-N1-state checks of §4.4 (409,
    `PROBE:` / `I4:` texts), so an obvious misconfiguration is refused before anything moves.

Then `ctx.tracker.expect("board registration")` and submit. `entry.registrationFuture = f`.

**Response 202** (no wait):

```json
{"kind": "board registration", "planId": "…", "started": true,
 "home": {"needed": true, "whyCode": "stopped", "why": "stopped or reconnected since the last home", "sinceMs": 1793…, "estimateS": 25},
 "fiducials": [{"name": "FID1", "x": 358.23, "y": 117.63, "searchRadiusMm": 8.0}, …],
 "probeTargets": [{"n": 1, "x": 303.81, "y": 122.62, "bx": 8.0, "by": 8.0, "alternates": […]}, …],
 "probe": "auto", "estimatedSeconds": 125}
```

With `wait: true`: the same plus `"finished": true, "result": <registration view>`, or 202 `finished: false` after
10 min (A/ApiContext.java:121-137).

**Registration view** (`Views.registration(RegistrationResult)`):

```json
{"ok": true, "status": "OK", "message": "Registered 13:05: …", "runId": "…", "atMs": 1793…, "simulated": false,
 "stoppedStage": null,
 "home": {"needed": false, "whyCode": "homed", "why": "homed", "done": false, "seconds": null, "n1RaisedFromZ": null},
 "fiducials": [{"name": "FID1", "board": {"x": 62.45, "y": 4.69}, "expected": {"x": 358.23, "y": 117.63},
                "found": {"x": 358.67, "y": 117.31}, "state": "found", "iterations": 2, "frames": 1, "offsetPx": 0.8,
                "offsetFromExpectedMm": 0.54, "movedMm": null, "searchRadiusMm": 8.0}],
 "fit": {"method": "affine-3", "affine": [6 numbers], "rotationDeg": -1.58, "scaleX": 1.0013, "scaleY": 0.9946,
         "shearDeg": -0.26, "maxResidualMm": 0.0,
         "limits": {"scalePct": 1.0, "shearDeg": 0.5, "residualMm": 0.05, "scaleChangePct": 0.15, "shearChangeDeg": 0.15},
         "previous": {"atMs": 1793…, "scaleX": 1.0011, "scaleY": 0.9946, "shearDeg": -0.27}},
 "seat": "first", "movedMm": null, "previousAtMs": null, "registrationId": "…", "registrationStored": true,
 "sameSeat": null,
 "probe": {"mode": "auto", "needed": true, "why": "no surface tied to this registration", "skipped": false,
           "window": {"nominalZ": 4.50, "refZ": 4.50, "startZ": 7.00, "floorZ": 3.00},
           "points": [{"n": 1, "board": {"x": 8.0, "y": 8.0}, "x": 303.81, "y": 122.62, "z": 4.525, "state": "contact",
                       "alternate": 0, "baseline": -1531200, "drop": 229680, "fine": true, "lastOpenZ": 4.75,
                       "look": {"bare": true, "foreignShare": 0.004}}],
           "plane": {"a": …, "b": …, "c": …, "maxResidualMm": 0.012, "slopeMmPerMm": 0.0007, "minZ": 4.50, "maxZ": 4.55},
           "saved": true, "surfaceSetAtMs": 1793…},
 "newPlanId": "…"}
```

### 7.3 `GET /api/plan/{planId}/registration-targets`

Returns the preview of §7.1 again, computed now, because the home plan changes with time.

### 7.4 Blank: `POST /api/surfaces/{name}/probe` and `GET /api/surfaces/{name}/probe-targets`

- **POST body**: `{"wait"?: bool, "targets"?: [[x, y], …] (3..20, machine mm of the N1 tip), "z"?: number}`.
- **Checks**: busy, pressure, OpenPnP busy; `name != boardSurfaceName`; a nominal Z; valid targets (400 otherwise);
  `checkBasic` interlocks; the window, simulator-timing and N1-state checks.
- `expect("blank probe")`.
- **202**: `{kind: "blank probe", surface, targets, window, home, estimatedSeconds, started: true}`.
- **With wait**: `result` = `{ok, status, message, stoppedStage, home, surfaceName, window, points[], plane, saved, surface: Views.surface(...)}`.
- **GET**: `{targets, window, covers: {spots, spotsCovered, places}, home, estimatedSeconds, error}`.
- `PUT /api/surfaces/{name}` keeps working unchanged (the manual path).

### 7.5 `GET /api/status` additions

- **`server.simulated`**: every driver is loopback TCP (`SimGuard.isLoopbackOnly`). It is a UI label: the client
  already reads it (W/core.js:642-648).
- **`server.simulatorConfirmed`**: `SimGuard.isSimulated(machine)`, the strict test (§9.1). Records and the
  simulated camera use this one.
- **`machine.home`**: `{needed, whyCode, why, sinceMs, policy, homedAtMs, estimateS, n1Z, n1Low}`, from
  `svc.homePlan()`.
  - It is filled in `MachineEndpoints.machineStatus` (A/MachineEndpoints.java:217-273) when the service exists.
  - `n1Low` = N1's Z lies outside z1's safe zone (a STOP during a probe). *As built (rc1 review):* also above it, a
    STOP during board dots, where the shared Z axis puts the needle low; `lowTool` (`N1` | `needle`) says which, and
    the UI names that tool (`The needle (N2) stopped low (Z 2.94 mm) at R3.1`), never a high N1 as low.
- **`machine.zCheck`**: `{needed, stale:[{what, atMs}], homeAtMs, reference:{name, x, y, z} | null, last: ZCheck.Result | null, estimateS}`.
- **`dispense.homeBeforeRun`**, **`dispense.newSeatAtMs`**.
- **`run.detail`**: the latest `onDetail` map, while that run is current. `RunTracker.Progress` gets
  `volatile Map<String, Object> detail`, replaced with an unmodifiable copy (A/RunTracker.java:20-39, 107-144). For
  `board registration`:

  ```json
  {"stage": "probe", "stepsDone": 6, "stepsPlanned": 10, "estimatedSecondsLeft": 41,
   "home": {"needed": false},
   "fiducials": [{"name": "FID1", "expected": {"x":…, "y":…}, "found": {"x":…, "y":…}, "state": "found", "iterations": 2, "frames": 1}],
   "seat": "reseated", "movedMm": 0.62,
   "probes": [{"n": 1, "x":…, "y":…, "state": "contact", "z": 4.525, "alternate": 0},
              {"n": 2, "x":…, "y":…, "state": "now", "lastZ": 5.18, "alternate": 0}, …]}
  ```

  - `stepsPlanned` = home (1 if needed) + fiducials + check or probe points + 1 (plan).
  - The blank probe uses the same shape without `fiducials` and `seat`. A run adds `home` and `zCheck` to its detail.
- **`run.nextPlanId`**: set from `RunResult.nextPlanId` in `onFinished` (A/RunTracker.java:96-104).
  - The tracker keeps only the plan id it started with (A/RunTracker.java:53-68). `nextPlanId` is how a page, or a
    second browser, finds the new plan (§8.1 "Reload").

### 7.6 Other

- **`GET /api/boards`**: each job board and file gets `registration: {id, atMs, nominal}` for its key, or null
  (A/DispenseEndpoints.java:498-522).
- **`POST /api/machine/home`** runs `svc.homeSafely` when the service exists (§2.3: N1 raised first, STOP disconnects),
  else `m.home()` as today. The response adds `n1RaisedFromZ`.
- **`POST /api/machine/z-check`** `{"wait"?: bool}`: checks `availableSvc`, engine idle, pressure, OpenPnP idle, then
  submits `svc.checkZ()` (§5.8). `expect("z check")`. 202 `{kind: "z check", needed, reference, estimatedSeconds}`.
- **`POST /api/board/new-seat`** `{"newSeat": true|false}` sets or clears `newSeatAtMs` (server time, §3.6). It
  returns `{newSeatAtMs}` and is refused while a task runs.
- **`PUT /api/config`** refuses a change of `boardRegistrations` or `newSeatAtMs` with the stamped-setting text
  (A/DispenseEndpoints.java:477-483).

---

## 8. Web UI

The binding rules from the UI brief still apply: CSP `script-src 'self'`, no inline handlers, STOP always visible,
plain language with units, a 0.7 s poll without jank.

- Ids already used by Playwright are kept (UI_DESIGN.md §16, :865-933).
- **No new static files**: the file list in `servesTheUiAndStatus` (T/api/ApiServerSimTest.java:230-237) stays valid.
- **New CSS** goes into run.css / setup.css, which the web-ui slice owns. app.css is not touched.

### 8.1 Run › step 2 is "Register" (key and route stay `probe`)

- **Step title.** `STEP_T.probe = 'Register'` (W/run.js:21).
  - These are unchanged, because Playwright relies on them (PW:460-543): the key, `data-view="probe"`, `#run/probe`,
    `FALLBACK_FIX.probe` (W/core.js:2416) and the readiness key `surface`.
- **Stepper subline** (`stepStates`, W/run.js:1021-1073): `registered 13:05`, `by hand 13:05`, `same seat 13:12` or
  `this seat`.
- **`autoStep`** (W/run.js:337-346) gets one rule before the surface rule. Return `'probe'` when either:
  - `s.run.label === 'board registration' && s.run.running && IL.planMatches(s.run.planId)`;
  - `R.regResultRunId` is set.

  Without it, a same-seat result (surface still tied) would land on Checks and hide the live phases.
- **The correction in use.** A `#reg-correction` line at the top of the body (and the plan chip's title) names what
  the plan's dots use, from `plan.registration`:
  - `fiducial check 13:05`;
  - `fiducial check 13:05 (before the last Next board)`, when `beforeNextBoard`;
  - `no fiducial correction`.

**Body** (`buildProbe`/`renderProbe`, W/run.js:1381-1521), top to bottom:

| element | content |
|---|---|
| `#probe-banner` | unchanged (the seat state) |
| `#reg-correction` | above |
| `#reg-preview` / `ol.phases#reg-phases` + `table#reg-fids` + `table#reg-probes` / `div.ri#reg-result` | idle / running / result, below |
| `#probe-tiles` | unchanged: the points of the current surface, from either path |
| `<details class="disclose" id="probe-by-hand">` | summary `Do it by hand: jog N1 in OpenPnP and add points`. Holds `#probe-guide`, `#probe-targets` and `#probe-untied`, ids and behaviour unchanged (W/run.js:1336-1427). Its open state is panel state `R.byHand` (a route parameter would be lost, W/core.js:2084-2087). |

**Foot** (`buildProbeFoot`, W/run.js:1390-1408). Both sets are built once; `R.byHand` chooses which one is shown
(`hidden`). The foot keeps its fixed height (app.css:488) and never holds the disclosure.

| `R.byHand` | foot shows |
|---|---|
| false | `#register-note`, `#btn-register`, `#register-why` |
| true | `#probe-live`, `#probe-warn`, `#btn-probe-add`, `#btn-probe-save`, `#probe-why` (unchanged) |

- **`#register-note`** (`.note`) is composed from `plan.registrationTargets` and `status.machine.home`:
  "Homes first (stopped at 14:19), ≈ 25 s. Then the top camera finds FID1, FID2 and FID3 (≈ 15 s) and N1 touches the
  board at 5 bare spots with its vacuum, after a camera look at each (≈ 1:25). The paste pressure stays off. Before you
  press: N1 has its tip and no part on it; hands clear of the head."
  - The first sentence is left out without a home.
  - On a same seat the middle reads "re-touches 3 points of the surface probed at 13:05 (≈ 50 s)".
- **`#btn-register`**: `btn btn--primary btn--lg btn--block`, two-press with verb `register board` (IL.press2,
  W/core.js:2241-2275; machine motion is two-press, UI_DESIGN.md:828-833).
  - Label: `Register board · ≈ M:SS`, from `registrationTargets.estimatedSeconds.total` + the live home estimate.
    Never hard-coded.
  - It posts `/api/plan/{id}/register` with `probe: 'auto'`. The engine's new-seat flag forces the probe (§3.6), so
    the client no longer picks `always`.
- **`#register-why`** (`.reason`) via `IL.gate(btn, reason)`. The reason is the first of:
  1. `IL.lockReason(s, {connected: true, engine: true})` (homed is **not** required);
  2. `Plan a board first`;
  3. `This board has 1 fiducial (2 needed): do it by hand`;
  4. `plan.registrationTargets.error`;
  5. `Record the needle Z frame first (Setup › Needle Z frame)`, with a link, when there is no anchor;
  6. `plan.registrationTargets.frame.message` when `!frame.ok` (the §4.4 window and anchor check);
  7. `OpenPnP says N1 holds a part` / `no tip on N1`, from `status.machine`.
- **The by-hand path uses the engine's targets.** When `plan.registrationTargets.probes` is present (no `error`),
  `probeTargets()` (W/run.js:1305-1335) returns those spots (machine XY, alternates on demand). The client's
  bounding-box heuristic stays only as the fallback. The hero overlay therefore draws one set.

**Body states:**

1. **Idle.**
   - `#reg-preview`: one line per fiducial (name, expected X/Y, search radius 8 mm), the number of probe spots, and
     the seat it expects ("same seat as 13:05: re-touches 3 points" or "probes 5 spots").
2. **Running** (`s.run.label === 'board registration' && s.run.running`).
   - `ol.phases#reg-phases`, with the li states and glyphs of `#run-phases` (W/run.js:1840-1849):
     1. Home (`not needed` / now / done; `raised N1 from Z 5.40` when it was low);
     2. Fiducials (`FID2 · 2 of 3 · move 2` or `· frame 2 of 3`);
     3. Fit (`moved 0.54 mm · rotation +0.35° · scale +0.20 % / −0.30 %`);
     4. Same-seat check or Probe (`3 of 5 · last Z 4.512 mm`, `alternate 1 at point 3`, `look at point 4`);
     5. Plan again.
   - `table#reg-fids`: name, expected, found, moved (mm). `table#reg-probes`: n, X, Y, Z, state.
   - The line `STOP stops it at once (F9).`
   - All of it comes from `status.run.detail` (§7.5).
   - The foot button becomes a disabled `Registering… · ≈ 0:41 left`.
3. **Result** (after `taskend` with that label).
   - `div.ri#reg-result` (`role="status"`, `ri--ok | ri--need | ri--block`, with `data-refusals`), built like
     `#clean-result` (W/run.js:1637).
   - `.ri--ok` is added to run.css: `background: var(--ok-bg)` (app.css:35).
   - Its first sentence is machine-readable (UI_DESIGN.md:308-310):
     - `Registered 13:05: 3 of 3 fiducials, board 0.54 mm from where it was planned; 5 points, worst 0.012 mm; planned again.`
     - `Same seat 13:12: the board moved 0.04 mm since 13:05 (limit 0.10 mm) and 3 re-touched points agree within 0.02 mm; the surface probed then still applies; planned again.` (or `; plan unchanged.`)
     - `Same seat 13:12, but the surface changed: point 2 is 0.09 mm off (limit 0.03 mm); probed 5 points again, worst 0.014 mm; planned again.`
     - `Re-seated 13:20: the board moved 0.62 mm since 13:05 (FID3 most); probed 5 points again, worst 0.014 mm; planned again.`
     - `Not registered: FID2 not found within 8.0 mm of X 310.16, Y 184.22.` + the rest of the message.
     - `Registered 13:05, but the probe failed: no contact at board X 8.0, Y 8.0 or 2 spots nearby …`
   - Buttons:
     - `#btn-reg-continue`: primary `Checks ›`, when OK;
     - `#btn-reg-again`: `Register again`;
     - `#btn-reg-probe`: `Probe again`, posts `probe: "always"`. Shown when the surface was kept on a same seat.
     - On a failure, the fixes of §8.2 (REG / PROBE).
   - The result stays on screen as Result does. `R.regResultRunId` is set in the `taskend` handler and kept in
     sessionStorage (`ssSet('il.run.regResult', …)`, like `il.run.probe`). It is cleared by `Checks ›`, a new plan from
     the Board step, or another task.
4. **Stopped.** Recovery takes the panel (W/core.js:1592-1594; W/run.js:348). Afterwards `#reg-result` says what was
   kept, from `stoppedStage` and `seat`:
   - home / fiducials: `Stopped 13:06 during the fiducial check (FID2): nothing was saved.`
   - seat `same`, stopped in the same-seat check or in a forced probe (`Probe again`):
     `Stopped 13:06 during the same-seat check (point 2 of 3): the board surface was not changed; the seat checked at 13:05 still holds.`
     (or `… during probe point 3 of 5: …`)
   - seat `first` or `reseated`, stopped in the probe or right before the save:
     `Stopped 13:06 during probe point 3 of 5: the fiducials (board moved 0.62 mm) were saved and the board planned again; the surface was not: Register again to probe.`

   Partial points are never reused.

**`taskend` handler** for label `board registration` (next to W/run.js:2198-2204):

```
if run.nextPlanId and run.nextPlanId != plan().planId:
    IL.loadPlan(run.nextPlanId); R.alias[next] = R.alias[old] || old        // the same physical board: count and dots carry
R.regResultRunId = run.runId; ssSet(…); goStep('probe')
```

- Only "Next board" starts a new board key, as today (W/run.js:160-207).
- The new-seat flag is cleared by the engine (§3.6), and the client reads `status.dispense.newSeatAtMs`.

**Reload and a second browser.** On every status: if `s.run.label === 'board registration'`, it is not running,
`s.run.nextPlanId` is set and `IL.planMatches(s.run.planId)`, then call `IL.loadPlan(s.run.nextPlanId)` once per run
id.

- A tab that did not see the task end, or a reload, catches up this way. `onTaskEnd` needs a previous status
  (W/core.js:1546-1556), and `restorePlan` prefers the stored `il.planId` (W/core.js:1605-1621).
- The result card itself comes from `GET /api/plan/{nextPlanId}`.`registrationRun`.

**Hero overlay** (boardview, W/boardview.js:11-15, 380-419). `bv.update({probe: {targets, points, margin}, fids: [...]})`
gets a new layer, `g.pv-fids`:

- dashed circles of the search radius at the expected positions;
- a filled dot at the found ones, labelled with the name;
- probe targets as today, plus small rings for the alternates.

The overlay shows on the Register step both when idle and while running (it is idle-only today, W/run.js:748-757). The
plan chip during the task (`renderChip`, W/run.js:595-611) reads `Registering · probe 3/5`, kind `live`.

**Board step.**

- **Board top Z** (W/run.js:1168):
  - Label: `Board top Z (approximate, ±1 mm)`.
  - Title: `Roughly the height of the board top as N1 sees it (OpenPnP frame). Register board measures it; within ±1 mm is enough.`
  - When the field is empty it is prefilled, in this order:
    - the last board surface of this board key (plane at the board centre);
    - else the needle-frame surface `status.dispense.needle.anchor.surfaceZMm`.
  - The prefill reads `prefilled from the needle frame (Setup)`, so it is not mistaken for a measurement.
  - `Use live N1 Z` stays as the by-hand option (W/run.js:1173).
  - Under the field, `registrationTargets.frame.message` shows the §4.4 window and anchor check before planning.
- **Copy** (W/run.js:1252-1254, 1277, 1294, 1879):
  - file: `After planning, Register board finds the fiducials with the top camera and probes this seat.`;
  - job: `Position from the OpenPnP job. Register board checks the fiducials again with the top camera.`;
  - `#board-seat-h` new seat: `New seat: after planning, Register board checks the fiducials and probes this seat.`;
  - `#plan-next-hint`: `then: register the board`;
  - the Next board dialog body: `… the surface check goes back to needs you until Register board has checked this seat.`;
  - page-head meta (W/run.js:573, 582): `registered 13:05` / `not registered`.
- **Probe only** (§5.2, F10; web UI of fix round 2). When `registrationTargets.probeOnly` is true the foot's primary
  is `Probe only (X/Y as planned) · ≈ M:SS` (two-press, `POST …/register {probe: "always"}`) with the note `This board
  has no fiducials: N1 probes it where it is planned; its X/Y are not checked.`; Do it by hand stays. The result card
  reads `Probed 13:05: no fiducials: probed only, X/Y as planned; 5 points, worst 0.012 mm; planned again.` (with
  `Probe again`). A REG refusal that ends ` Or probe it only, where it is planned: Register with probe "always".` offers
  `Probe only (X/Y as planned)` first; the readiness item, the I4 seat fixes and the Z-moved step offer it instead of
  Register board for such a plan, and the item's short name is `probe board` (blocked: `probe again`), the plan chip's
  `probe this seat`, the Register step's `probed`.

### 8.2 Readiness, strip, fix-its

- **Machine item** (W/core.js:886-900).
  - Case `m.enabled && !m.homed && !m.busy` and `status.machine.home.policy` set: `{state: 'info', ready: true,
    value: 'homes first', short: 'homes first', homeFirst: true, detail: 'Not homed since the stop at 14:19: Register
    board and Run home first (≈ 25 s).', fixes: [{fix: 'home', label: 'Home now'}]}`, with the Note glyph.
  - It counts as ready. So the verdict reads `Ready to run · homes first (≈ 25 s)`, and "Needs you" keeps meaning a
    quick action (UI_DESIGN.md §9.3, :581-592).
  - When `machine.zCheck.needed`, the value is `homes first, checks Z`, and the detail adds `then one N1 touch checks
    Z (≈ 20 s)`.
  - When `machine.home.n1Low`, the state is `need`: `N1 stopped low (Z 5.40): Home raises it first, then homes`, with
    the `Home now` fix.
  - The client's `homedAt` is replaced by `status.machine.home.homedAtMs` (W/core.js:898, 1532-1533).
- **Surface item** (W/core.js:1001-1030).
  - Seat fixes: `[{fix: 'register', label: 'Register board', primary: true}, {fix: 'probe-by-hand', label: 'Do it by hand'}]`.
  - For a new seat (`dispense.newSeatAtMs`):
    - when the board has ≥ 2 fiducials and a camera, `same-seat` is replaced by
      `{fix: 'register-check', label: 'Same seat? Check the fiducials'}`. That clears the flag, then registers with
      `probe: 'auto'`, so the 0.1 mm rule and the 3-point check decide;
    - the plain `Same board, not moved` attestation (W/core.js:1018, 2493) stays only for boards that cannot be
      registered.
  - The OK detail adds `· registered 13:05 (3 fiducials)` or `· not registered (no fiducial check)`.
  - The item gets `registered: {atMs, seat} | null`. There is no new readiness item: there are still 9 segments.
- **Fix-its** (in run.js next to W/run.js:394-406):
  - `IL.fix('register', {press2: 'register board', gate, run: () => { goStep('probe'); post… }})`;
  - `IL.fix('register-check', …)` as above;
  - `IL.fix('probe-by-hand', {run: () => { R.byHand = true; goStep('probe'); }})`;
  - `IL.fix('look-camera', {run: () => IL.go('#setup/camera')})`: Setup's Top camera card (W/setup.js:186, 1539-1548),
    whose Snapshot moves nothing. After a REFUSED fiducial task, the head is still at the last search spot, so the
    snapshot shows what the camera saw. The card's sim tag `simulator: static picture` (W/setup.js:1544) reads
    `simulator: drawn board` when `server.simulatorConfirmed`;
  - `IL.fix('plan-unregistered', {run: () => post /api/plan with the plan's source + registration: 'none'})`;
  - `IL.fix('board-step', {run: () => goStep('board')})`;
  - `IL.fix('check-z', {press2: 'check Z', run: post /api/machine/z-check})`.
- **`fixesForCode`** (W/core.js:2418-2446):
  - I4 seat / surface-bad / plan → register, probe-by-hand.
  - I4 whose message starts `the needle frame (…), … were measured before the home` → `check-z`. `Z moved` → the
    remedy's next step under the same home (`IL.zMoved`, from `machine.zCheck.stale` and `needle.touchedOff`; final
    round): `Open Needle Z frame`, then `Probe the blank…`, then `Run a touch-off` or `Confirm frame offset …`, then
    `Register board` / `Do it by hand`; the touch-off and dot ladder cards list the frame or the blank first.
  - `REG`, ordered: `Look with the camera` (look-camera), `Change board` (board-step), `Plan without fiducial
    correction` (plan-unregistered), `Register again` (register), `Do it by hand` (probe-by-hand).
  - `PROBE` → `Register again`, `Do it by hand`; plus `[{view: 'probe-blank', label: 'Probe the blank…'}]` when the
    message names the blank; a board top the probe `cannot reach` (§4.4, final round) → `Board step`, `Open Needle Z
    frame`, `Do it by hand` (also the links of `#register-why`).
  - `MACHINE` keeps `Home`.
- **Task words.**
  - `TASK_WORD` (W/core.js:1258) gets `'board registration': 'Register'`, `'blank probe': 'Blank probe'` and
    `'z check': 'Z check'`; so does `pcr.TASK` (W/profile.js:71).
  - `records.js` `runTitle` (W/records.js:20-26) gets `board-registration` → `Registration · <board>`,
    `surface-probe` → `Surface probe · <name>` and `z-check` → `Z check`.
  - `taskParts` (W/core.js:1282-1298) for `board registration`:
    `['Register · ' + (stage == 'probe' ? 'probe ' + k + '/' + n : stage == 'same-seat' ? 'check ' + k + '/3' : stage == 'fiducials' ? 'FID ' + k + '/' + n : stage), ' · ≈ ' + fmtClock(estimatedSecondsLeft) + ' left']`.
  - `lockReason` reads `Locked while the board registration runs` (W/core.js:670-671).
- **Status strip.** `#pill-homed` keeps `Not homed`, with the title `Register board and Run home first (≈ 25 s)`.
  `#btn-home` is unchanged (W/core.js:1806, 1817-1820); it now raises N1 first when it is low (§7.6).
- **Recovery** (W/core.js:1411). The home step is `done` when `m.enabled && (m.homed || (policy && !home.n1Low))`.
  - Detail: `Register board and Run home first (≈ 25 s); or home now`, with an optional `Home now` fix.
  - With `n1Low` it stays needed: `N1 stopped low (Z 5.40): look at the board under it; Home raises N1 first, then
    homes.`
  - The clean check, prime and the pressure hold keep their `homed: true` gates (W/run.js:398; W/core.js:2478).
- **New-seat flag.** `IL.setNewSeat` posts `/api/board/new-seat` and `S.newSeatAtMs` mirrors
  `status.dispense.newSeatAtMs`. One-time migration: a value left in localStorage `il.newSeatAtMs` is posted once, then
  removed.

### 8.3 Run confirm (`#dlg-run`, W/run.js:1969-2022)

- **New `#run-confirm-home`**, a notebox above `#run-confirm-sum`, shown when `status.machine.home.needed`:
  `Homes first (stopped at 14:19), ≈ 25 s: the head goes to its home corner and the top camera finds the homing fiducial. The pressure stays off until homing is done.`
  - It adds ` Then one N1 touch checks Z (≈ 20 s).` when `zCheckFirst`.
  - With `always`: `Homes first (setting: always), ≈ 25 s: …`.
  - A Z check without a home: `Checks Z first: one N1 touch on the blank (≈ 20 s), because the needle frame predates
    the home at 14:19.`
- **The summary** `#run-confirm-sum` (W/run.js:2124-2125) starts with `Homes first, then` in that case.
- **Run step phase list** (W/run.js:1787-1862) gets first rows `Home` and `Z check` (`not needed` / now / done) when
  `run.phases` has a `home:` / `z check:` entry, or when `homeFirst` / `zCheckFirst` was in the 202.

### 8.4 Setup › Probe the blank (`IL.view('probe-blank')`, W/setup.js:901-970)

- **Body:**
  - `ol.phases#blank-phases`;
  - `table#blank-targets` (n, X, Y, Z, state, look);
  - `div.ri#blank-result`, with the existing tiles (Points, Worst, Tilt, Covers) filled from `result.plane`;
  - `<details class="disclose" id="blank-by-hand">` holding today's table, ids unchanged.
- **Foot**, chosen by the disclosure's open state as on Run:
  - closed:
    - `#blank-note`: `Homes first …, ≈ 25 s. Then N1 touches the blank at 6 spots beside the purge row (clear of the 3 used spots), after a camera look at each. ≈ 1:45.`;
    - the primary `#btn-blank-auto`: press2 `probe the blank`, label `Probe the blank · 6 spots ≈ 1:45`;
    - `#blank-why`;
  - open: `#btn-blank-add`, `#btn-blank-save` and their reason line, as today (W/setup.js:955).
- **`#blank-result` texts** (first sentence machine-readable):
  - `Saved 13:20: 6 points, worst 0.012 mm, covers 15 of 15 spots.`
  - `Not saved: ` + the refusal message.
  - `Stopped 13:21 during point 3 of 6: nothing was saved.`
- **Data**: the preview comes from `GET /api/surfaces/{name}/probe-targets`. Unlike today's by-hand save, the panel
  stays open after success to show the result.

### 8.5 Settings card (W/setup.js:1640-1670)

- **`#set-homebefore`** `<select>`, label `Before Register board, Probe and Run`. Options:
  - `whenNeeded`: `Home first when needed (not homed, or stopped since the last home)`;
  - `always`: `Always home first`.
- It is saved with `#btn-settings`, like the other `#set-*` fields.
- The other persisted settings of §6.1 stay in the advanced JSON editor (nested merge).

---

## 9. Simulator

### 9.1 The guard: `S/SimGuard.java`

- **`isLoopbackOnly(Machine m)`**: the machine has at least one driver, every driver is a `GcodeDriver`, and every
  one uses communications type `tcp` with a loopback IP.
  - This moves here from `DriverGcodeChannel.isLoopbackSimulator` (D/DriverGcodeChannel.java:23-35), which then calls
    `SimGuard.isLoopbackTcp(driver)`.
  - The real machine.xml uses `serial` but also has an unused `tcp 127.0.0.1:23` entry (MX:436-438). The selected type
    is what counts, so it reads false.
  - This is the UI label `server.simulated`.
- **`isSimulated(Machine m)`**, the strict test. Both of these must hold:
  - `isLoopbackOnly`;
  - the controller identified itself as OpenPnP's simulator **on the current connection**: its M115 reply contains
    `FIRMWARE_NAME:GcodeServer` (O/util/GcodeServer.java:44, 580-590).

  How the identity is kept:
  - `SimGuard.attach(machine)`, called by `DispenseService.start`, adds a listener that forgets the identity on every
    `machineEnabled` and `machineDisabled`.
  - `SimGuard.noteIdentity(machine, lines)` is called with the M115 lines every engine Session already collects first
    (D/DispenseService.java:1240-1266).

  Why it holds in each case:
  - A serial-over-TCP bridge on 127.0.0.1 passes the loopback test but answers M115 as Marlin, so it reads false.
  - Under uinav, the driver talks to uinav's in-process proxy, which forwards every line to a fresh `GcodeServer`
    (`~/instancelabs/openpnp-uinav/agent/src/org/openpnp/instancelabs/uinav/GcodeCapture.java:137-144`). So M115
    comes from `GcodeServer` and it reads true. (A port registry would not work: the driver's port is the proxy's,
    not the server's.)
  - `RecordingGcodeServer` answers `FIRMWARE_NAME:Marlin (RecordingGcodeServer)` (T/dispense/RecordingGcodeServer.java:90-94).
    A new field, `firmwareName` (default unchanged), lets sim tests answer `GcodeServer (RecordingGcodeServer)`.
- **Before the first engine task on a connection**, `isSimulated` is false. The simulated camera then draws a blank
  frame, which a Register's own Session fixes before its first capture.
- **`sceneFile(Configuration c)`** = `<configDir>/instancelabs/sim-scene.json`.
- **Sim-only code runs only when all of these hold:**
  - **The vacuum model** lives inside `org.openpnp.util.GcodeServer` and the test `RecordingGcodeServer`. Neither runs
    behind a serial port: OpenPnP creates `GcodeServer` only for the pseudo address `GcodeServer`, for
    `SimulatedCommunications`, and in the uinav agent. The scene file must also exist in the configuration directory.
    Only the sim scripts write it, and only into a config **copy**.
  - **The camera:**
    - `SimBoardCamera.open()` throws `IllegalStateException("simulation-only camera on a non-simulated machine: driver
      GcodeDriver uses serial")` unless `isLoopbackOnly` holds and the scene exists;
    - `internalCapture()` draws the board only when `isSimulated`, and otherwise a uniform grey frame stamped
      `SIM CAMERA: controller not confirmed`, with no marks.

    So even a mis-set `--xml-set` in real mode (uinav applies it there too, uinav.py:403) can only make Register find
    nothing.
- **Records made on the simulator** are marked `simulated`, and are refused when `isSimulated` is false (§3.6, §6.2).
  Engine-core takes the guard as an injected `BooleanSupplier`.
- **The engine has no simulator branch.** It sends the same M260/M261 lines everywhere. The only relaxation is the
  timing lower bounds of §4.4, which apply only when `isSimulated` is false.

### 9.2 Scene file: `<configDir>/instancelabs/sim-scene.json`

It is read by `S/SimScene.java` and reloaded when its mtime changes (checked at most every 250 ms), so a test can move
the board in a running session.

```json
{"version": 1,
 "board": {"file": "ftp.board.xml",
           "seat": {"affine": [[1.00298, 0.03343, 295.99], [-0.02507, 0.99714, 114.52]]},
           "surface": {"z0": 5.43, "slopeX": -0.0006, "slopeY": 0.0009},
           "fiducials": [{"name": "FID1", "bx": 62.4466, "by": 4.6910, "d": 1.0, "mask": 3.0}, …],
           "hideFiducials": [], "dirtyPads": [], "dirtySpots": [], "leakySpots": [{"bx": 8.0, "by": 8.0, "r": 0.6}]},
 "blank": {"rect": [280, 225, 365, 250], "surface": {"z0": 5.62, "slopeX": -0.0012, "slopeY": 0.0}, "dots": []},
 "bedZ": 2.5, "n1OffsetMm": [-24.178, -63.91], "tipRadiusMm": 0.35,
 "vacuum": {"openAir": -1550000, "sealDrops": [257000, 271000, 290000, 303000, 316000, 329000, 350000, 370000, 404000],
            "partialBandMm": 0.03, "leakDrop": 15000, "offValue": 0,
            "tauS": 0.3, "sealTauS": 0.03, "releaseTauS": 0.4, "noise": 1500, "seed": 7, "tip": "normal"},
 "camera": {"uppError": [0.0, 0.0], "noise": 4, "blurPx": 1}}
```

- **`board.file`** is matched against the end of each board path in `Configuration.get().getBoards()`. uinav copies
  boards into `uinav-boards` (src/test/sim/il_sim_acceptance.py:315-318).
- **`seat`** is the true board→machine transform. **The pivot is the board origin**: the board origin lands at
  `(tx, ty)`. Instead of `affine` it may be given as `{x, y, rotationDeg, scaleX, scaleY, shearDeg}`
  = T(x, y)·R(rotation)·Shear·Scale.
- **`fiducials`** are written by `il_sim_scene.py` from the board file's `type="Fiducial"` placements, with d/mask
  parsed from the package name as in §3.1. The renderer reads them from here, so the sim slice needs no engine class
  (§13).
- **`surface`** gives N1 contact Z in board mm; `blank.surface` gives it in machine mm.
- **`bedZ`** is the contact everywhere else. It lies below every probe floor of the scene on purpose: off the board,
  there is no contact.
- **`tip`**: `normal` | `missing` (open air −400 k; an assumption, see §11) | `blocked` (−2.1 M) | `part` (open air
  −100 k from normal, the bench's ≥ 80 k for a held 0603).
- **`dirtySpots`** (board mm) and **`blank.dots`** (machine mm): paste dots of 1.0 mm, drawn in the paste colour, for
  the camera look.
- **The defaults** come from the bench: the open-air value, seal drops (deciles), partial seals and release from the
  offline parse of `runs/serial.log` (§4.4); the board surface from `BOARD_PROBES_R7`
  (src/test/sim/il_sim_acceptance.py:76); the blank from `BLANK_PROBES` (:79-80).

### 9.3 Vacuum model: `S/SimVacuum.java` + hooks

**State, driven by the line text:**

- pump on: `M106`, `M106 P0…`; off: `M107`, `M107 P0`;
- valve on: `M106 P1 S>0`; off: `M107 P1` (MX:565-566);
- multiplexer channel from `M260 A112 B<n> S1`; register from `M260 A109 B6 S1`.

**The reading** for `M261 A109 B3 S1`:

- **Target value.**
  - Pump and valve on → `openAir`.
  - Sealed → `openAir − sealDrop(spot)`, with the drop drawn per spot (seeded) from `sealDrops`.
  - Within `partialBandMm` above contact → a partial seal: half the drop.
  - Sealed at a leaky spot → `openAir − leakDrop`.
  - Otherwise, or on a channel ≠ 1 → `offValue`.
- **Moving to the target.** The value moves toward it with a time constant on the wall clock (an injectable
  `LongSupplier`):
  - `tauS` after a pump or valve change;
  - `sealTauS` when a seal forms;
  - `releaseTauS` when it breaks.

  So a baseline read too early comes out wrong (PT/lessons.md:25), and a fine step right after the coarse seal still
  reads sealed, as in 29 of 156 bench fine passes.
- Plus Gaussian noise (σ = `noise`) from a seeded `Random`.
- **Sealed** when N1's raw Z ≤ the contact Z at the tip XY, and the whole tip disc (`tipRadiusMm`) lies on that
  surface.
  - Tip XY = raw head X/Y + `n1OffsetMm` (N1 = head + offset, `tools/probe.py:11,50`).
  - Contact Z: the inverse true affine to board mm; inside the outline → the board plane; inside `blank.rect` → the
    blank plane; else `bedZ`.
- **Reply**: `echo:i2c-reply: from:109 bytes:3 data:E8 F9 80`, the 24-bit two's complement, big-endian, upper-case
  hex. The format is `runs/serial.log:5550`; the example value −1,508,992 is `E8 F9 80` (`runs/serial.log:7053`).
- OpenPnP's own VAC1 read (`M261 A109 B1 S2`) is not modelled: pick-and-place is out of scope. It answers plain `ok`,
  as today.

**Hooks:**

- **`org.openpnp.util.GcodeServer`** (upstream file, one small hook):
  - In `setDriver` (O/util/GcodeServer.java:84-87): `this.vacuum = SimVacuum.forConfiguration(Configuration.get())`.
    It returns null unless the scene file exists.
  - At the start of `Worker.interpretGcode` (O/util/GcodeServer.java:304-306), if `vacuum != null`, pass
    `(line, momentary X, Y, Z of this driver)`. The position comes the way `M114.1` gets it (`getMomentaryMotion`,
    O/util/GcodeServer.java:561-566).
  - `SimVacuum.onLine` returns extra reply lines or null. Non-null → `setResponse(lines + "\nok")` and return.
    M106/M107/M260 return null and are parsed as today (plain `ok`).
  - Without a scene, behaviour is byte-identical to today, so OpenPnP's own tests are unaffected. uinav creates one
    server per connection (GcodeCapture.java:142), so a reconnect is like a power cycle.
- **`T/dispense/RecordingGcodeServer.java`** (next to `reportBCount`, :39-43) gets:
  - `public volatile SimVacuum vacuum` (null = today's behaviour). It is fed from the tracked X/Y/Z (:120-140), and
    its replies go before `ok` (:86-111);
  - `public volatile String firmwareName` for the M115 reply (§9.1);
  - `public final Map<String, Long> delayBeforeOkMs`, by line prefix (e.g. `"G28" → 3000`), for the STOP-during-homing
    test. Today only `G4 P` waits, with `realDwell` (:106).

### 9.4 Top camera: `S/SimBoardRenderer.java` + `S/SimBoardCamera.java`

**`SimBoardRenderer.render(SimScene sc, Board board, double camX, double camY, int w, int h, double uppX, double uppY)`
→ `BufferedImage` (TYPE_3BYTE_BGR)**, in OpenPnP's orientation (+x right, +y up):

- **Pixel to machine**: `X = camX + (u − w/2)·uppX·(1+uppError[0])`, `Y = camY − (v − h/2)·uppY·(1+uppError[1])`.
- Drawn with Java2D:
  - a board→pixel transform composed from the true seat;
  - antialiasing on;
  - a `ConvolveOp` box blur of `blurPx`;
  - seeded noise of ±`noise` grey levels.
- **Colours** (BGR measured on `runs/board3_FID1_2.jpg`; PadCleanCheck.java:9-11):

  | element | BGR |
  |---|---|
  | solder mask | (142, 59, 0) |
  | 3 mm fiducial mask opening | (128, 47, 0) |
  | fiducial copper, 1 mm disk | (241, 240, 199) |
  | pad copper | (246, 244, 222) |
  | paste on `dirtyPads`, `dirtySpots`, `blank.dots` | (160, 63, 13) |
  | blank (bare mask) | (142, 59, 0) |
  | outside the board and blank | (40, 40, 40) |

- **Fiducials** come from the scene JSON (§9.2). **Pads** come from the existing
  `new BoardPadExtractor(resolver, true).extract(board at the origin)`. The resolver is built from
  `DispenseService.get().getDb()` when the service exists (D/DispenseService.java:214, 556-558); otherwise footprint
  pads only.
- **A small `SIM` stamp** sits in the top-left corner: 8 px glyphs, far below the 0.45 mm² fiducial area window, and
  outside every probe spot's 3 mm look ring.

**`SimBoardCamera extends ImageCamera`**:

- **`open()`**: the guard (§9.1), then `setSourceUri("classpath://org/openpnp/instancelabs/sim/sim-1px.png")` (a 1×1
  PNG resource), then `super.open()`. The 7002 × 4000 test image is never loaded (ImageCamera.java:256-265).
- **`internalCapture()`**:
  1. the guard: a blank frame unless `isSimulated`;
  2. `loc = SimulationModeMachine.getSimulatedPhysicalLocation(this, getLooking(), false)`, as ImageCamera does
     (ImageCamera.java:268-275);
  3. render at `getWidth()`/`getHeight()` with `getUnitsPerPixel()`;
  4. **pre-apply the configured flips**: mirror X if `isFlipX()`, Y if `isFlipY()`. ReferenceCamera's
     `transformImage` flips them back, so the engine sees the board the right way round. Today the kept flips turn
     uinav's sim image by 180° (O/machine/reference/camera/ReferenceCamera.java:626, 1124-1130), which would make the
     centring loop move away;
  5. any other transform set on the camera (rotation, crop, scale, deinterlace) refuses with
     `SimBoardCamera supports flips only`;
  6. cache the frame per (camX, camY, scene version): the Swing view captures about 10 times a second.
- **Installation** needs no change to uinav; it goes through `--xml-set`. uinav first turns Top into an `ImageCamera`,
  keeping only the attributes ImageCamera accepts (uinav.py:279-305), and applies `--xml-set` afterwards (uinav.py:403):
  - `.//camera[@name='Top']@class=org.openpnp.instancelabs.sim.SimBoardCamera`
  - `.//camera[@name='Top']@width=1920`
  - `.//camera[@name='Top']@height=1080`

  ImageCamera's `width`/`height` are XML attributes (ImageCamera.java:68-73).
- **Side effect**: with pads drawn, the I10 clean-pad check reads real bright shares in the simulator. Before the
  registration, the nominal plan's boxes sit ≥ 0.7 mm off the true pads; after it, on them. The simulator's clean check
  can stay on (§10.4).

### 9.5 Scripts

- **New `src/test/sim/il_sim_scene.py`** (standard library only):
  - `seat(x, y, rot_deg, sx=1, sy=1, shear_deg=0)` → affine. The pivot is the board origin, which lands at (x, y).
  - `reseat(base, dx, dy, drot_deg)`: a re-seat in the machine frame about `base(board origin)`:
    `T(base(0) + (dx, dy)) ∘ R(drot) ∘ T(−base(0)) ∘ base`.
  - `fiducials(board_file)`: the `type="Fiducial"` placements with d/mask (§9.2).
  - `scene(board_file, seat_affine, board_plane, blank=…, leaky=[], hide=[], dirty=[], dirty_spots=[], vacuum={}, camera={})` → dict.
  - `apply(affine, bx, by)` and `min_pad_shift(board_file, a, b)`, so tests compute every expected value from the
    transforms.
  - `write(config_dir, scene)`: atomic (tmp + rename).
  - `CAMERA_XML_SETS`, and `FAST_TIMINGS = {"vacuumProbe": {"vacuumSettleMs": 300, "readDelayMs": 0}}`.
  - A CLI `il_sim_scene.py write --config DIR --board FILE --x --y --rot --z [--dx --dy --drot --sx --sy --shear] [--leaky bx,by]`
    for the shell runner.
- **`il_sim_acceptance.py`**:
  - `stage()` (src/test/sim/il_sim_acceptance.py:156-171) writes a scene whose true seat equals `BOARD_LOC`. The
    existing `board` scenario is then unchanged: its raw-vs-OpenPnP checks assume the rigid seat.
  - `Session` appends `CAMERA_XML_SETS` to `SIM_XML_SETS` (:92-94).
  - A new `register` scenario (§10.4).
  - The list of sim-only changes (:1040-1052) names the scene and the camera.
- **`il_ui_session.py`** (src/test/sim/il_ui_session.py:24-49):
  - The scene's true seat is `BOARD_LOC` re-seated by `reseat(…, +0.60, −0.45, +0.20)` with scale 1.002/0.997 and
    shear 0.1°, the camera installed, and `FAST_TIMINGS`.
  - **`--register` is opt-in.** With it, the session plans the board and then calls
    `POST /api/plan/{id}/register {wait: true}` instead of tying the bench's surface points, and prints the expected
    extra time first. Without it, today's manual tie stays.
  - `--clean-check` is meaningful now that pads are drawn.
  - The JSON line adds `registration: {seat, movedMm, newPlanId}` when registered. The docstring's "static picture"
    text (:10) is corrected.
- **`src/test/web/run-webui-sim-test.sh`** (web-ui slice):
  - stages `~/.openpnp2` into `$OUT_DIR/stage` (a read-only copy). Today it passes `--config "$HOME/.openpnp2"`
    directly (src/test/web/run-webui-sim-test.sh:29-32), and nothing may be written there;
  - writes the scene with `il_sim_scene.py write`, launches with the camera `--xml-set`s, and exports
    `SIM_SCENE=<path>` to the test;
  - the Playwright board is planned at x 120, y 120, z 10, rot 0 (PW:448-451). Its scene seat is that, re-seated by
    (+0.60, −0.45) mm and +0.20° with scale 1.002/0.997 and shear 0.1°, and a surface at z0 10 with slopes 0.0005.
    The blank rect covers the Playwright purge row (x0 70, y0 45, 48 spots at 2.5 mm, PW:406-410) at z 10.

---

## 10. Tests

### 10.1 Unit tests (JUnit, `mvn -B -o -T1 test`)

**engine-core**

- **`ProbeCommandsTest`**:
  - `checkProbe` allows exactly `M106`, `M106 P1 S180`, the six M260 lines and `M261 A109 B3 S1`. It refuses
    `M106 P1 S255`, `M106 P3 S180`, `M106 P0 S128`, `M260 A112 B2 S1`, `M260 A110 B1`, `M261 A109 B1 S2`;
  - `check()` still refuses every probe line, so a dispense stream and the pressure hold cannot send them;
  - the validator names the reason for N1 lines inside a dispense program.
- **`AutoregConfigTest`**: defaults; every range of §6.1 (including searchRadiusMm needing ≤ 9 frames and boardSpots
  shapes); an old JSON without the new keys loads with the defaults; a `copy()` round trip.
- **`ExecutorGatedIoTest`** (fake `GcodeChannel`):
  - `sendGated`/`queryGated` throw `AbortedException` after `requestAbort`;
  - the PROBE scope uses `checkProbe`, the others `check`;
  - a timeout ≤ 0 is refused;
  - lines are recorded;
  - `awaitLineWritten` waits for a gated line in flight.
- **`N1MapperTest`** (SimMachine, T/dispense/SimMachine.java:23-48):
  - the old constructor still throws for N1;
  - `forProbeNozzle(N1).toRaw(x, y, z) = (x + 24.178, y + 63.91, z)`;
  - N1's Z is untransformed.
- **`HomeTrackerTest`** (fake clock):
  - not homed → `not-homed`;
  - disable then enable → `stopped`;
  - `homeStarted → disabled → homed(true)` → `home-unconfirmed`, needed;
  - **an OpenPnP home after a STOP** (no `homeStarted`, enabled) → not needed;
  - **unconfirmed, then a clean home** → not needed;
  - homed at service start → `lastHomeAtMs` null, not needed;
  - `always`;
  - the estimate: measured, or 25 s.
- **`ZCheckTest`**:
  - `references` order (board tied → blank → anchor XY) and the filter "measured before the home";
  - `verdict` at 0.029 / 0.031 mm;
  - the I4 rule: no rule when `lastHomeAtMs` is null; each stale datum named with its time; a passing check for
    this home accepts; a passing check for an older home does not; a failed check → the "Z moved" text;
    `zCheckFirst` → a note; the no-reference text.
- **`InterlocksHomeFirstTest`**: `homeFirst` turns "not homed" into a note; "not enabled" stays; `checkBasic` returns
  only MACHINE/CONFIG.
- **`BoardFeaturesTest`**:
  - an ftp-like board written in the test: 3 fiducials, d 1.0 / mask 3.0 parsed from `Fiducial_1mm_Mask3mm`;
  - a footprint round pad wins over the parse;
  - pads from pad types;
  - outline 75 × 75.
- **`BoardRegistrationTest`**:
  - `applyTo` returns a copy with LocallySet and leaves the source untouched;
  - the same record gives the same `boardRegistration` digest;
  - JSON round trip;
  - `matches`, including `openPnpTransform`; `maxMovedMm`;
  - `simulated` refused when the injected guard says false.
- **`ProbeTargetsTest`**:
  - on the ftp-like geometry, every target is ≥ its keep-outs (1.4 from pad edges, 2.9 from fiducials) and ≥ 2.0 from
    the edge;
  - every dot within margin − 0.5 of the hull, with **5** targets on ftp (no coverage points);
  - deterministic;
  - `boardSpots` replaces the ideal points;
  - alternates ≥ 2 mm apart;
  - a board full of pads → the PROBE message;
  - row targets avoid used spots by ≥ 1.9 mm and cover every spot.
- **`VacuumProberTest`** (a scripted fake channel models a seal at a known Z, with release lag and partial seals;
  fast, `readDelayMs` 0, `simulated = true`):
  - **Sequence.** The line sequence equals the block in §4.4: VAC1 init once; per point Z31.5, XY, approach, M400,
    M106, `M106 P1 S180`, `G4 P1500`, 5 reads, coarse steps, the move back to the last open step, the release reads,
    fine steps; then `G1 Z31.5 F600`, `M400`, `M107 P1`, `M107 P0`.
  - **Fine-pass rules.**
    - Contact is below the last open coarse Z and needs two sealed steps in a row.
    - **Bench replay**: the 10-27 17:45:36 trace (5.75 open, 5.50 sealed, slow release) yields a contact below 5.75,
      not the bench's 5.80.
    - A fine miss → one re-probe, never the coarse zc.
    - "did not release" after 1.5 s.
  - **Window.**
    - Floor = max(nominal, anchor) − 1.5; the first-contact band 1.0; 3 floor pushes per task;
    - no Z below the floor; XY only at travel Z; travel Z from the dispenser mapper.
  - **Exits.** Every refusal inside a point, a parse error, an `Error:` line and the on-time cap end with exactly the
    4 end lines. STOP → `AbortedException`, with only the shutdown lines (`M410 … M107 P1, M107 P0, M18 B`) and no Z
    move. If the end lines fail → shutdown.
  - **Open air.**
    - absolute high/low; spread 15 k with one retry;
    - relative 50 k vs the first point, 60 k vs memory; memory older than 2 h ignored;
    - OpenPnP part/tip state;
    - sealed at the start.
  - **Simulator timings** (300 ms / 0 ms) are refused when `simulated = false`.
  - **Camera look**: a not-bare spot → the next alternate; all not bare → the refusal; no `SpotLook` → no look, with a
    note.
  - **Alternates**: no contact → 2 alternates, each with its own baseline, then the failure text.
  - The window refusals.

**vision**

- **`FiducialDetectorTest`**:
  - synthetic frames (Java2D) of a 1 mm disk in a 3 mm opening at a sub-pixel position → found within 0.2 px;
  - 0.6 mm and 1.4 mm disks rejected;
  - a 0.80 × 0.95 mm pad rejected (circularity 0.78, fill 0.63);
  - a mark at the frame edge rejected;
  - the search radius excludes a far fiducial;
  - two fiducials → ambiguous.
  - **Bench parity**: 6 frames copied from `runs/*_FID*_*.jpg` (77 exist, ~110 KB each) into
    `src/test/resources/org/openpnp/instancelabs/vision/fid/`, with `expected.json` written once by running
    `tools/fid.py` offline in the bench venv. The test turns each frame by 180°, uses the bench scale
    0.01074 × 0.01106, and requires the centre within 0.5 px of `(W−1−px, H−1−py)` and the diameter within 3 %.
- **`FiducialLocatorTest`** (a fake `FrameSource` draws a disk at a true machine position relative to the camera):
  - converges in ≤ 2 moves, also with a 3 % scale error in the "camera";
  - **search tiles**: a fiducial 7 mm off in Y is not in frame 1 and is found in frame 2; 9 mm off → not found, and
    the text names the radius, the X/Y and "3 camera frames";
  - **the bench re-seats**: board2's 4.6/5.2/6.9 mm and reflow1's −4.6° rotation, from their `previous` predictions →
    all found (the translation update after the first fiducial is what makes the second one fit);
  - not centred → Failure;
  - fit: 3 points exact (1e-9), 4 points least squares with residuals, 2 points similarity;
  - each absolute limit, and the limits against a previous fit (a fiducial 0.3 mm off → FIT);
  - abort between captures.
- **`BareSpotCheckTest`**:
  - bench frames copied into `src/test/resources/org/openpnp/instancelabs/vision/bare/`: bare (`bare1.jpg`,
    `bare2.jpg`, mask near the fiducials of `board3_FID1_2.jpg`) → bare; frames with purge dots (`blk7_*.jpg`,
    `blk_y243_*.jpg`) at a dot → not bare; `blank_edge.jpg` at the edge → not bare (ring not uniform);
  - the defaults sit with a recorded margin on both sides.

**sim**

- **`SimGuardTest`**:
  - loopback tcp + a `GcodeServer` M115 → true;
  - **loopback tcp + a Marlin M115 → false**;
  - loopback without any M115 yet → false;
  - serial → false;
  - tcp to 192.168.x → false;
  - serial + an unused tcp loopback entry (the real file's shape, MX:436-438) → false;
  - the identity is forgotten on disable and on enable.
- **`SimVacuumTest`**:
  - −1,508,992 ↔ `E8 F9 80`;
  - the off value;
  - settling with tau (a read at 0.3 s is still falling);
  - seal at z ≤ contact; the partial band; **release with `releaseTauS`** (a read 0.37 s after lifting still reads
    sealed); a leaky spot; the edge (tip disc partly off the board);
  - tip states, including `part`;
  - seal drops drawn from the deciles; seeded determinism;
  - channel 2;
  - reload when the scene file changes.
- **`GcodeServerVacuumHookTest`**: no scene → `M261 …` answers plain `ok`; with a scene in a temp config → the data
  line, then `ok`; M106 is still parsed.
- **`SimBoardRendererTest`**:
  - at a known pose, the fiducial centre pixel (from the scene JSON) is copper-bright, the pixel 1.2 mm away is
    opening-dark, and a pad centre is copper;
  - a `dirtySpots` dot is paste-coloured;
  - after the flips, `transformImage` on a SimMachine-like camera gives the board the right way round.
- **`SimBoardCameraTest`**: with SimMachine, a scene and a `GcodeServer` identity → it draws the board; loopback but
  unconfirmed → a blank frame; a serial driver → throws; no scene → throws.

### 10.2 ApiServerSimTest / ReviewSafetySimTest / new RegistrationSimTest (tasks-api)

**Test seams**:

- `sim.server.vacuum = new SimVacuum(scene)` (with `tauS = 0`, `releaseTauS` as the case needs);
- `sim.server.firmwareName = "GcodeServer (RecordingGcodeServer)"`;
- `svc.setDotImager(new SceneImager(scene))`, where `SceneImager` (test) renders with `SimBoardRenderer` at
  1920 × 1080 and 0.01068376 × 0.01072961;
- `writeBoardWithFiducials()`: 3 `Fiducial` placements with package `Fiducial_1mm_Mask3mm`. Today's `writeBoard`
  writes an empty `<fiducials/>` (T/api/ApiServerSimTest.java:514-533);
- config `FAST_TIMINGS`.

**Existing tests.**

- Board runs are unchanged: the HomeTracker starts with `lastHomeAtMs = null` on the homed SimMachine
  (T/api/ApiServerSimTest.java:61-68), so nothing is stale.
- `reconnect()` homes (T/api/ApiServerSimTest.java:172-176). After it, Z data are stale, and a run checks Z itself. The
  shared setup therefore installs a flat `SimVacuum` that matches `setUpBoardRuns`' surfaces and places.
- Tests that run after `reconnect()` confirm the start angle (`POST /api/screw/start-angle`), as the I11 test already
  does (T/api/ApiServerSimTest.java:707).
- The prime refusal (T/api/ApiServerSimTest.java:493-499) still holds.
- The tasks-api builder runs the full suite. It fixes any test that relied on a run being refused while not homed, or
  that sets Z data before a home.

**`RegistrationSimTest` (new).** Every expected position is computed from the scene transforms (`apply`); none is
hard-coded.

1. **First seat.** `wait: true` → OK.
   - 3 fiducials found; each correction equals `S(b_i) − N(b_i)` within 0.01 mm; `seat: first`.
   - The surface is tied (`boundToBoard`).
   - `newPlanId` is a different plan, whose pad centres lie within 0.02 mm of `S(pad)`.
   - G-code: VAC1 init, then per point `M106` … `M107 P1`, `M107 P0`; no Z below the floor; no `M106 P2`/`P3`.
   - `status.run.nextPlanId` and `run.detail` were seen during the task (polled).
2. **Same seat** → `seat: same`.
   - exactly 3 `M106 P1 S180` since the mark (the 3 check touches);
   - `sameSeat.pass`; the registration id and the surface `setAtMs` unchanged.
3. **Same seat, surface changed**: scene surface z0 + 0.08 → full probe, a new surface, and the result says "surface
   changed".
4. **New-seat flag**: `POST /api/board/new-seat {"newSeat": true}` with the board untouched → `reseated` (flag), full
   probe; the flag is cleared afterwards.
5. **Re-seated**: the scene moves to S2 → `reseated`, `movedMm` = max over fiducials of |S2(b) − S1(b)|, probed again,
   a new id. The old plan's `POST /api/run` → 409 "registration … not stored any more … plan again".
6. **Big re-seat**: a 5 mm, −4.6° re-seat (the board2 and reflow1 cases) → OK, and FID1 reports `frames > 1` or
   iterations ≤ 3.
7. **FID2 hidden** → REFUSED with `REG: FID2 not found within 8.0 mm of X …` and "3 camera frames". No M106 sent,
   nothing stored.
8. **Fit vs previous**: register OK, then FID2 drawn 0.3 mm off its board position → REFUSED with the
   "last registration" text.
9. **Leaky spots**: one at the first target → OK, point 1 `alternate: 1`.
   - All three spots leaky → REFUSED "probably a hole or via".
   - The registration is stored, a new plan made, the surface unchanged, and the machine **still homed**.
   - The end lines (`G1 Z31.5000 F600`, `M400`, `M107 P1`, `M107 P0`) follow the last point.
10. **Paste on a spot** (`dirtySpots` at the first target) → alternate, with `look.bare = false` reported. All three
    dirty → REFUSED "something on the surface".
11. **N1 state**:
    - `tip: missing` → REFUSED "no nozzle tip";
    - `tip: blocked` → REFUSED "blocked";
    - `tip: part` on a second registration → REFUSED with the "stronger than at" text;
    - `N1.setPart(...)` → 409 "OpenPnP says N1 holds a part".
12. **Release lag**: `releaseTauS 0.4` → every contact Z within 0.03 mm of the scene plane, and below its point's
    `lastOpenZ`.
13. **Refusals**:
    - pressure hold, busy, OpenPnP busy;
    - a board with no fiducials (409 REG); `probe: "bogus"` (400); no needle frame (409 I4);
    - `FAST_TIMINGS` with `firmwareName` Marlin → 409 "simulator setting".
14. **Plan registration parameter**: `"none"` gives an unregistered plan; an id of another board → 409; `"auto"` picks
    the stored record.
15. **Job board**: a `Job` with a `BoardLocation` built from the fiducial board, set as the current job.
    - Register → OK, `seat: first`, `boardKey` `job:<id>`, the new plan's pads within 0.02 mm of `S(pad)`.
    - The live job board's own transform and location are unchanged.
    - Set a placements transform on the live job board (as OpenPnP's check would) → plan and run refused with
      "OpenPnP's fiducial check … changed".
    - Move the job board → "belongs to another board or position".
16. **Config**: a PUT of `boardRegistrations` or `newSeatAtMs` → 400; a nested partial `vacuumProbe` merge is kept; an
    invalid range → 400.
17. **Blank**: `POST /api/surfaces/blank/probe {wait}` → saved, 4–6 points, used purge spots avoided by ≥ 1.9 mm. A
    `blank.dots` dot under a target → alternate. `POST /api/surfaces/board/probe` → 409.
18. **Home at run**: `sim.machine.setEnabled(false); setEnabled(true)` (no home), then `POST /api/screw/start-angle`,
    then `POST /api/run`:
    - 202 with `homeFirst: true`, `homeWhyCode: "stopped"`, `zCheckFirst: true`;
    - the G-code has the HOME_COMMAND (G28), then one Z-check touch (`M106 P1 S180` … `M107 P0`), before the first
      `M106 P2 S255`;
    - OK, with `RunResult.zCheck.pass`.
    - With `homeBeforeRun: always` it homes even when homed.
    - `POST /api/prime` while not homed → still 409 MACHINE (nothing else homes).
19. **Z check**:
    - after a home, `POST /api/prime` → 409 I4 "measured before the home";
    - `POST /api/machine/z-check {wait}` → OK, pass; prime → OK.
    - Home again, then shift every scene surface by −0.70 mm (a Z-zero shift) → z-check REFUSED "Z moved -0.70 mm",
      and `POST /api/run` → 409 with the same I4 text.
20. **N1 low before a home**: a STOP during a probe, then Start, the start-angle confirm and `POST /api/machine/home`
    → `G1 Z31.5000 F600` before G28, no X/Y line before G28, and `n1RaisedFromZ` set.
21. **Status fields**: `machine.home.*`, `machine.zCheck.*`, `server.simulated`, `server.simulatorConfirmed == true`,
    `dispense.homeBeforeRun`, `dispense.newSeatAtMs`.
22. **Simulated records on a "real" link**: switch `firmwareName` to Marlin and reconnect → plan with the stored
    registration → 409 "made on the simulator"; a run with the simulated surface → I4 "probed on the simulator".
23. **Disabled machine**: a task submitted while disabled ends REFUSED, and `svc.isBusy()` is false afterwards.
24. **Pressure race**: start the hold and submit a registration back to back (both pass their API checks) → exactly
    one of them refuses on the machine thread; never both running.

**ReviewSafetySimTest additions** (each names its rule):

- **STOP during a probe point** (a long settle): ABORTED; `indexOfSubList(STOP_SEQUENCE)` ≥ 0 after the stop; no
  `G1 Z` after the M410; no new board surface; the machine un-homed.
- **STOP during homing** inside a run (`delayBeforeOkMs` `G28` → 3000, keep-alive on): ABORTED; `homed == false`; no
  G0/G1 after the M410 (park and visual homing cannot move: the driver was disconnected); the next home plan says
  needed (`home-unconfirmed` or `stopped`).
- A run with the pressure hold on → 409 before any G28.
- A no-contact point never commands Z below `floorZ`.
- Every prober refusal and the on-time cap end with exactly `G1 Z<travel> F600`, `M400`, `M107 P1`, `M107 P0`.
- `PressureHold` and `stream()` still refuse `M106 P1 S180` and `M260 A109`: `check()` is unchanged.

### 10.3 Playwright (PW)

**Placement.** The new block goes after the camera-snapshot checks (PW:776-781) and before the final "no native
dialogs" and "no JavaScript errors" checks.

- At that point the machine is stopped (the F9 STOP at PW:756-770, during a pressure hold), so N1 is in the safe zone.
- The earlier checks are untouched. They were written for the state before this block: purge spots used = 8 (PW:609),
  "Finish 140 pads left" (PW:595-598), the clean-check fix needs homed (W/run.js:398).
- The scene and `SIM_SCENE` come from run-webui-sim-test.sh (§9.5). The block starts with `PUT /api/config` of
  `FAST_TIMINGS` and `POST /api/purge-row/reset` (A/DispenseEndpoints.java:90).
- **47 new checks; 176 in all** (129 before: `/tmp/il-final2-webui-20261028-163355/results.json`).

The checks (number: check):

1. **Recovery without Home** (Connect fix, then "No, it stayed on" when the I11 step shows):
   1. the home step reads `home first` and is done;
   2. Recovery is done; machine started, not homed.
2. **Register step, idle** (`IL.go('#run/probe')`):
   3. `#panel[data-view=probe]`, step title `Register`;
   4. `#register-note` matches `/FID1, FID2 and FID3/`, `/≈ \d/` and `/^Homes first/`;
   5. `#probe-by-hand` is in the panel body, not in `.panel__foot`, and is closed;
   6. the Board step's `#board-z` title names Register board and `±1 mm`.
3. **Register** (`press2('#btn-register')`):
   7. the label reads `Press again: register board`;
   8. `s.run.label === 'board registration' && s.run.running` seen, and `#reg-phases li.now`;
   9. the Home row is shown and ends done.
4. **Result:**
   10. `s.run.status === 'OK'`;
   11. `#reg-result` matches `/^Registered \d\d:\d\d: 3 of 3 fiducials/`;
   12. `IL.S.plan.planId === s.run.nextPlanId`;
   13. `s.dispense.surfaces.board.boundToBoard`;
   14. `#reg-fids tbody tr` count is 3;
   15. the FID1 correction equals `S(FID1) − N(FID1)` from `SIM_SCENE` within 0.05 mm;
   16. `#reg-correction` matches `/^fiducial check \d\d:\d\d/`.
5. **G-code** (`gcode(since)`):
   17. G28 comes before the first `M106 P1 S180`;
   18. `inOrder(['M106', 'M106 P1 S180', 'G4 P300', 'M261 A109 B3 S1', 'M107 P1', 'M107 P0'])`;
   19. every `G1 Z` between `M106 P1 S180` and `M107 P1` is ≥ `window.floorZ`;
   20. X/Y lines appear only while the last Z was the travel Z;
   21. the last Z move is `G1 Z31.5000`.
6. **Same seat** (press2 again):
   22. `#reg-result` matches `/^Same seat \d\d:\d\d/`;
   23. exactly 3 `M106 P1 S180` since the mark;
   24. the registration id and `surfaces.board.setAtMs` are unchanged;
   25. `#btn-reg-probe` is visible.
7. **Do it by hand on Run** (open `#probe-by-hand`):
   26. the foot now shows `#btn-probe-add`, and `#probe-targets` lists `plan.registrationTargets.probes`;
   27. three `press2('#btn-probe-add')` (the second press, "add this point anyway", covers N1 away from the target)
       → 3 points;
   28. `#btn-probe-save` → `boundToBoard` and a new planId.
8. **STOP during the probe** (`press2('#btn-reg-probe')`, wait for `run.detail.stage === 'probe'`, click `#btn-stop`):
   29. ABORTED, and Recovery shown;
   30. the F9 lines follow M410, with no `G1 Z` after M410;
   31. `surfaces.board.setAtMs` unchanged.
9. **Recovery with N1 low:**
   32. the home step is needed and names N1's Z (`/N1 stopped low \(Z \d/`);
   33. Home → `G1 Z31.5000 F600` before G28; `#pill-homed` reads `Homed`;
   34. after Recovery, `#reg-result` matches
       `/^Stopped \d\d:\d\d during probe point \d of \d: the board surface was not changed; the seat checked at/`
       (the seat was `same`, and `Probe again` forced the probe).
10. **Home at run** (F9 STOP while idle; Start; the I11 answer; purge row reset):
    35. the machine item has state `info`, value `homes first`, and `IL.ready().canRun`;
    36. the verdict reads `/^Ready to run · homes first/`;
    37. the run confirm's `#run-confirm-home` matches `/^Homes first/` and mentions the Z check;
    38. run the first 4 dots (`runFirstDots`, PW:125-141): G28, then one Z-check touch, before the first
        `M106 P2 S255`;
    39. the run is OK and `#pill-homed` reads `Homed` after it.
11. **Setup › Probe the blank** (`IL.panel.open('probe-blank')`):
    40. `#blank-note` matches `/beside the purge row/`;
    41. `press2('#btn-blank-auto')`, wait for the label `blank probe` to finish; `#blank-result` matches
        `/^Saved \d\d:\d\d: \d+ points/`;
    42. `s.dispense.surfaces.blank.points.length` ≥ 4, and every point ≥ 1.9 mm from used purge spots;
    43. `#blank-by-hand` is in the body. Opening it shows `#btn-blank-add`/`#btn-blank-save` in the foot; three adds
        and a save store the surface.
12. **Setting:**
    44. `#set-homebefore` saves `always`, and back.
13. **Fiducial failure** (rewrite `SIM_SCENE` with FID2 hidden, register, restore):
    45. `#reg-result` matches `/^Not registered: FID2 not found within 8\.0 mm/`;
    46. the REG fixes offered, in order: `Look with the camera`, `Change board`, `Plan without fiducial correction`,
        `Register again`, `Do it by hand`;
    47. `Plan without fiducial correction` → a plan with `registration === null`, and `#reg-correction` reads
        `no fiducial correction`.

The id list in UI_DESIGN.md §16 is extended with every new id above.

### 10.4 Sim acceptance: new scenario `register` (`il_sim_acceptance.py register`, also in `all`)

1. **Stage** with the scene's true seat **S1** = `reseat(seat(BOARD_LOC) ∘ scale 1.002/0.997 ∘ shear 0.1°, +0.60,
   −0.45, +0.20°)`.
   - Camera `uppError` (0, 0.03): the bench's 3 % Y scale gap, to show the centring loop absorbs it.
   - The board plane is tilted.
   - **Bench timing** (`vacuumSettleMs` 1500, `readDelayMs` 70) for the first registration. `FAST_TIMINGS`
     afterwards.
2. `setup_machine` without the board tie. The clean check stays **on** (pads are drawn now).
3. **Plan at `BOARD_LOC`** (nominal) and record the plan's pad centres. Require
   `min_pad_shift(N, S1) ≥ 0.7 mm` (0.74 computed offline for the ftp pads), so the test is sensitive by
   construction.
4. **`POST /api/plan/{id}/register {wait}`**:
   - OK, `seat first`;
   - the fit equals S1 within 0.01 mm at every pad;
   - 5 probe points; contact Z within 0.03 mm of the scene plane;
   - the surface tied;
   - G-code safety: the allowlist, Z ≥ floor, the vacuum sequence order, the release step.
5. **Dots on pads**: GET the new plan. Every pad centre lies within **0.02 mm** of `S1(board pad centre)`
   (`pads_from_board`, src/test/sim/il_sim_acceptance.py:525-537).
6. **Run 20 dots** (`maxDots`) → OK.
   - For each streamed feed (`engine_dots`, :553-572), `raw XY − plan dot XY` is constant over all feeds within
     0.01 mm. It equals −(N2 head offset in the patched machine.xml) − `needleXyTrim` within 0.01 mm.
   - So the needle went to the plan dots, which lie on the true pads. The existing `board` scenario independently
     checks the engine's raw XY against OpenPnP's own N2 moves.
7. **Move the board**: rewrite the scene to **S2** = `reseat(S1, +0.8, +0.5, −0.4°)`, with a plane tilted 0.3 mm
   across.
   - Running the old plan → 409 (fingerprint/registration).
   - Register → `reseated`; `movedMm` = max over fiducials of |S2(b) − S1(b)| (0.84 / 1.35 / 1.29 mm offline, so
     1.35); probed again.
   - Run 20 dots → the dots lie on S2's pads (the same checks as 5–6). The clean check passes before the run.
8. **STOP, then run**:
   - `POST /api/stop` during the probe of a third registration (`probe: "always"`) → ABORTED; F9 seen; no new surface.
   - Then `POST /api/machine/enable`, `POST /api/screw/start-angle`, and `POST /api/run`:
     - `homeFirst: true`, `zCheckFirst: true`;
     - `G1 Z31.5000` (N1 was low), then G28, then one Z-check touch, before the first dot;
     - OK.
9. **Failure cases** in one session, each on a scene rewrite:
   - FID2 hidden → REFUSED with the not-found text, and no N1 line;
   - leaky spots at all three spots of point 1 → REFUSED "hole or via", N1 lifted, still homed;
   - paste at all three spots of point 1 → REFUSED "something on the surface";
   - tip `missing` → REFUSED "no nozzle tip";
   - after a home, every surface shifted −0.70 mm → `POST /api/run` REFUSED "Z moved -0.70 mm".
10. `results.json` gets a `register` section with all of the above. `live_config_untouched` stays true
    (src/test/sim/il_sim_acceptance.py:1074-1075).

---

## 11. What only the real machine can confirm (to REAL_MACHINE_TODO.md)

Items 1 and 3 should be confirmed in the first real-machine session, before anyone relies on automatic homing
(§14 question 8).

1. **STOP during homing, and G28's axis order.**
   - G28 is sent with an infinite timeout (O/machine/reference/driver/GcodeDriver.java:649-650). Does M410 interrupt
     it on the Lumen firmware?
   - Does the disconnect-on-disable (§2.3) stop visual homing and the park, as the sim test shows?
   - In which order does G28 home the axes (Z first)? The design raises N1 before G28 either way.
2. **The real home duration**, which replaces the 25 s estimate. It is measured automatically after the first engine
   home.
3. **Z repeatability across homes.**
   - How often does the 0.03 mm Z check fail after a normal home?
   - Was the 0.7 mm shift of 2026-10-28 (PT/todo.md:184) a property of OpenPnP's home sequence against the bench's
     `G28` + `G92` (PT/lessons.md:10)? If homes routinely move Z by more than 0.03 mm, every run after a STOP will
     ask for the needle frame again.
4. **Fiducials through OpenPnP's capture path.**
   - `OpenPnpCaptureCamera` format 0 must be 1920 × 1080 (not stored in MX:297).
   - The flips must give the bench's orientation.
   - The LED must be on, via `lightSettleAndCapture`.
   - Check: register the bench board and compare the found fiducials with `runs/board_registration.json` (≤ 0.05 mm
     after a fresh home).
5. **Camera scale** 0.01068 × 0.01073 vs the bench's 0.01074 × 0.01106 (REAL_MACHINE_TODO.md:32-35). The centring
   loop absorbs it, but the mm area window is ±3 % off in Y, and the search tiles are ±3 % smaller in Y than computed.
6. **Same seat after a STOP and a home.** Register, STOP, Start, Register: does an untouched board read `same`
   (≤ 0.10 mm), and do its 3 re-touched points agree within 0.03 mm?
7. **Open air.**
   - The baseline window for this pump and tip (−1.2 M … −1.9 M counts).
   - The readings with **no tip**, a **blocked** tip, the **pump off** (the sim assumes 0), and **a 0603 on the tip**.
     That last one confirms the relative limits of 50 k / 60 k against the ≥ 80 k of `tools/place.py:37`.
8. **Seal and release.**
   - The seal drop on mask and on bare copper at the chosen spots, vs the 15 % threshold.
   - The release time after a seal: is 1.5 s always enough?
   - Does the two-in-a-row fine rule leave contact Z unbiased? Compare with a slow manual descent at one spot.
9. **VAC1 sensor mode**: the bench writes `M260 B27` (`tools/probe.py:49`), while OpenPnP's actuator read sequence
   writes `M260 B10` (MX:514-520). The engine sends the bench init at every probe task. Check that a following OpenPnP
   pick still reads VAC1 sensibly.
10. **The camera look** (`BareSpotCheck`) on real frames: paste dots on the blank, silkscreen and vias on the board,
    the blank's edge and tape. How many false "not bare" spots on ftp?
11. **Pushing to the floor**: N1 now goes at most 1.5 mm below the higher of the board Z and the needle frame, and at
    most 1.0 mm below the first contact of the task. Confirm N1's compliance makes that harmless.
12. **Probe targets on real boards**: holes and vias are not in the board file. Count how often alternates are needed
    with `padKeepOutMm` 1.4 and the N045 tip.
13. **Flatness refusals**: a bowed board fails the 0.04 mm residual limit. The bench's 9-point `runs/zmap.json` is at
    0.048. Expect occasional "not flat enough" on boards that are not clamped.
14. **Time per probe point** (≈ 17 s predicted) and per registration: ≈ 2:05 with a home (25 s + fiducials ≈ 15 s +
    5 × 17 s); ≈ 1:30 for a same seat with a home.
15. **The blank around the purge row** (tape gaps, the bowed area): are 2 mm offsets on bare blank? Is the plane
    within 0.04 mm there (REAL_MACHINE_TODO.md:20 found a bowed blank)?
16. **STOP during a probe on the real link**: the vent order, and that no Z step follows M410 (log check).
17. **Job boards**: our registration replaces OpenPnP's own transform on the planning copy. Confirm it matches
    OpenPnP's fiducial check on the same seat (within 0.05 mm).
18. **Reconnect after a STOP during homing** (the driver is disconnected, §2.3): does reopening the serial port reset
    the controller? If it does, the B step count is lost, and I11 asks for the start angle.

---

## 12. Docs to update (integration slice)

- **`docs/instancelabs/WEB_API.md`**:
  - §3: the new routes (register, registration-targets, surface probe, z-check, new-seat), the plan fields, the
    status fields, the run 202 fields;
  - §5: the UI (Register step, blank probe, home at run, Z check);
  - §6: test counts and the new test classes (the "23 + 7 tests" at :164 changes);
  - §7: replace "A board placed by hand … gets no fiducial correction" (:183) and the "static image" camera limit
    (:179-181) with the SimBoardCamera note.
- **`docs/instancelabs/DISPENSE_API.md`**:
  - §2: the settings table;
  - §4: registrations and `planBoard(bl, reg)`;
  - §5: `registerBoard`, `probeSurface`, `checkZ`, `homeSafely`, gated I/O, `checkProbe`;
  - §7: MACHINE `homeFirst`, the I4 Z-after-home rule, `checkBasic`;
  - §8: the known limits from §11.
- **`docs/instancelabs/UI_DESIGN.md`**:
  - §8.1.3 (now "Register"), §8.1.5 (the run confirm home and Z lines), §8.1.8 (the recovery home step, N1 low);
  - §8.2 (blank probe), §9.1 (the machine item as a Note when it homes first);
  - §13 (key texts), §16 (ids), §17 (the API additions, now made).
- **`docs/instancelabs/REAL_MACHINE_TODO.md`**: the list of §11, as new numbered items.

`W/DEV_NOTES.md` is updated by the web-ui slice (§13): the new fixes, `R.byHand`, `R.regResultRunId`, `run.detail`,
and the new-seat flag on the engine.

---

## 13. Implementation slices (disjoint files)

| key | wave | depends on | owns |
|---|---|---|---|
| engine-core | 1 | – | D/DispenseConfig, ControllerCommands, GcodeStreamValidator, DispenseExecutor, OpenPnpRawMapper, Interlocks, RunResult, DispenseListener, DotImager, OpenPnpDotImager; **V/CameraCapture** (`captureLit` only); new D/HomeTracker, ZCheck, BoardFeatures, BoardRegistration, ProbeTargets, VacuumProber, RegisterRequest, RegistrationResult, SurfaceProbeRequest, SurfaceProbeResult, HomeReport, PlaneReport, ProbeReport, SameSeatReport; their tests; existing T/dispense/InterlocksTest, ZFrameTest (only if needed) |
| vision | 1 | – | V/FiducialDetector, FiducialLocator, BareSpotCheck (new); their tests; test resources `vision/fid/`, `vision/bare/` |
| sim | 1 | – | S/SimGuard, SimScene, SimVacuum, SimBoardRenderer, SimBoardCamera (new); `src/main/resources/org/openpnp/instancelabs/sim/sim-1px.png`; O/util/GcodeServer (hook); D/DriverGcodeChannel (SimGuard); T/dispense/RecordingGcodeServer (`vacuum`, `firmwareName`, `delayBeforeOkMs`); sim tests; `src/test/sim/il_sim_scene.py` |
| tasks-api | 2 | engine-core, vision, sim | D/DispenseService; A/RegistrationEndpoints (new), ApiServer, ApiContext, DispenseEndpoints, MachineEndpoints, PlanStore, RunTracker, Views, PressureHold; T/api/ApiServerSimTest, ReviewSafetySimTest, RegistrationSimTest (new), SceneImager (new test helper); T/dispense/DispenseServiceSimTest, ReviewFixesTest, LiveBoardPlanTest, PadCleanGuardTest (only if the home/Z rules need a seam) |
| web-ui | 1 (codes against §7–§8), finishes after tasks-api | tasks-api (to run), sim (`il_sim_scene.py`) | W/run.js, core.js, setup.js, boardview.js, records.js, profile.js, run.css, setup.css, DEV_NOTES.md; PW; `src/test/web/run-webui-sim-test.sh` |
| integration | 3 | all | `src/test/sim/il_sim_acceptance.py`, `il_ui_session.py`; docs/instancelabs/WEB_API.md, DISPENSE_API.md, UI_DESIGN.md, REAL_MACHINE_TODO.md; after the merge, fixes anywhere |

**Why each wave-1 slice compiles alone:**

- **sim:** `SimBoardRenderer` reads fiducials from the scene JSON, and pads through the existing `BoardPadExtractor`.
  It needs no `BoardFeatures`.
- **engine-core:**
  - `OpenPnpDotImager.captureLit` uses `CameraCapture.captureLit`, in the same slice;
  - the simulated-record checks take an injected `BooleanSupplier` instead of `SimGuard`;
  - the camera look is the `VacuumProber.SpotLook` interface, implemented by tasks-api with `BareSpotCheck`.
- **vision:** `FiducialLocator.FrameSource` returns the existing `DotImager.Frame`.

The tests that need several slices (RegistrationSimTest, the Playwright block, acceptance) live in waves 2–3.

**The contracts** that wave-1 slices code against without seeing each other:

- `FiducialLocator` / `FiducialDetector` / `BareSpotCheck` signatures (§3.2–3.3, §4.5);
- `VacuumProber` (with `Io`, `SpotLook`, `Settings`, `Window`), `ProbeTargets`, `HomeTracker`, `ZCheck`,
  `BoardRegistration`, `BoardFeatures` (§2.2, §2.4, §3.1, §3.5, §4.4–4.6);
- `ControllerCommands.checkProbe`, `DispenseExecutor.sendGated/queryGated(Scope, …)`;
- `new SimVacuum(SimScene)` with `String onLine(String line, double x, double y, double z)`;
  `SimScene.load(File)` / `SimScene.of(JsonObject)`; `SimBoardRenderer.render(...)` (§9.2–9.4);
- `SimGuard.isLoopbackOnly/isSimulated/attach/noteIdentity` (§9.1);
- the JSON of §7.

The build must stay green at every merge (merge order: vision, sim, engine-core, then tasks-api, web-ui,
integration). The checks are `env -u DISPLAY -u WAYLAND_DISPLAY mvn -B -o -q -T1 -Dmaven.test.skip=true package` and
`mvn -B -o -T1 test`.

---

## 14. Open questions (for the main thread; the design takes the stated default)

1. **The needle frame vs the board surface.**
   - Today's data put the board (N1 4.50–4.55, `runs/zprobe_current.json`) and the blank (5.30–5.80,
     `runs/blank_probe_b3.json`) about 1 mm apart.
   - I4 already refuses any surface more than `zFrameToleranceMm` (1.0, max 1.2) from the anchor
     (D/Interlocks.java:212-216). With the needle frame recorded on the blank, as it often is, Register would refuse
     the real board, and so would a by-hand board surface.
   - The texts now name where and when the anchor was taken.
   - Decide before implementing: record the needle frame on the board, or allow one anchor per surface.
2. **Persisting registrations.** The design keeps the latest registration per board in `dispense-config.json`
   (protected), so an OpenPnP restart keeps the seat and its tied surface. The alternative is memory only, next to the
   plans (A/PlanStore.java:13-19): after a restart the board would then have to be registered again.
3. **`registration: "auto"` on `POST /api/plan`.** The default applies a stored registration when the board key and
   nominal match. This is safer than in the first revision:
   - the same-seat check now re-touches 3 points;
   - the new-seat flag is on the engine.

   A board re-seated without anyone saying so still gets the old correction until Register is pressed. Keep `auto`,
   or require an explicit id?
4. **Job boards.** Our registration replaces OpenPnP's own fiducial transform on the copy used for planning. OpenPnP's
   job board is never changed, so OpenPnP's window does not show our fit. Should the page also write the transform
   back to the job board? The default is no: it would change OpenPnP's state from the web page.
5. **No `never` value for `homeBeforeRun`.** The brief allows only `whenNeeded` | `always`, so "refuse when not homed"
   (today's behaviour for runs) is gone. Is a `never` value wanted for bench sessions?
6. **The blank pattern.**
   - The pattern (4–6 spots 2 mm either side of the purge row) assumes bare blank there. The bench blank has a tape gap
     at x 331–339 (src/test/sim/il_sim_acceptance.py:82).
   - The camera look now turns a bad spot into an alternate, and explicit targets are supported.
   - Should the purge row also carry keep-out zones?
7. **A failed Z check refuses; it does not correct.** The data would allow shifting N1 data by δ and the needle
   relation by 2δ (§2.1). The design refuses and asks for the needle frame, the touch-off and the surfaces again, as
   the bench had to. Is automatic correction wanted later?
8. **Automatic homing on the real machine before §11 items 1 and 3 are confirmed.** The brief makes `whenNeeded` the
   default. Should the first real sessions run with homing by hand until a STOP during G28 has been tried on the
   bench?
9. **The Z check also gates prime, the test dot and the calibrations after a home** (the new I4 rule). After a STOP
   the flow becomes: Home, Check Z (≈ 20 s, one press), then prime. Is that acceptable? The alternative is to apply
   the rule to board runs only.

---

## 15. Critique log

Each critique point, what was done, and where. "Fixed" points were checked in the code or the bench files first; the
check is cited in the section named.

### safety-bench

| # | sev. | point | verdict |
|---|---|---|---|
| 1 | blocker | Z data measured before an automatic home | **Fixed**: §2.1 (evidence), §2.2 (`lastHomeAtMs`, null at service start), §2.4 (I4 rule, `ZCheck`, 0.03 mm, Run checks itself, `POST /api/machine/z-check`, Register stamps via the same-seat check), §2.6, §11 item 3. Changed from the proposal: (a) the reference is any stored N1 contact that predates the home (tied board surface, blank, anchor X/Y), not only the anchor X/Y, because that spot may carry needle paste and its X/Y is optional (D/DispenseConfig.java:254); (b) Register does not force `probe: always` when the surface predates the home, because its same-seat check re-touches 3 points anyway (§3.6). |
| 2 | major | failure exits leave N1 pressed on the board | **Fixed**: §4.4 exits table (end lines on every non-STOP exit, shutdown only on STOP or failed end lines), §4.2 (no timeout ≤ 0), tests §10.1 and §10.2. |
| 3 | major | automatic G28 with N1 left low by a STOP | **Fixed**: §2.3 step 1 (Z-only raise to 31.5 before any home, engine and API), §7.5 `n1Low`, §8.2 Recovery keeps the home step needed when N1 is low, §11 item 1. |
| 4 | major | fine pass trips before the vacuum releases | **Fixed**: §4.4 (release wait ≤ 1.5 s, fine pass from the last open step, two in a row, one re-probe, never the coarse zc); §9.2–9.3 (`releaseTauS`, partial band, seal-drop deciles). Re-parsed `runs/serial.log`: 29 of 156 first-step trips, 79 at or above the last open step. |
| 5 | major | 3 mm search radius misses real re-seats | **Fixed**: §3.3 (8 mm, whole-frame tiles, translation update after the first fiducial), §6.1, tests with 5 mm / −4.6°. Verified: board2 4.60/5.18/6.91 mm, reflow1 2.75/3.24/4.23 mm. |
| 6 | major | "same seat" keeps a surface fiducials cannot vouch for | **Fixed**: §3.6 (re-touch 3 points, not 2: a plane has three degrees of freedom), the new-seat flag on the engine (§3.6, §7.6, §8.2). |
| 7 | major | the open-air check cannot see a part on N1 | **Fixed**: §4.4 (50 k vs the task's first point, 60 k vs recent memory, spread 15 k, OpenPnP part/tip state). Verified: consecutive ≤ 36 k, session ≤ 64 k, spread ≤ 4.1 k. Changed: the last good baseline is kept in memory, not persisted; an OpenPnP restart starts a new session, and the absolute window still applies. Old question 1 is closed. |
| 8 | major | floor only below the typed board Z | **Fixed**: §4.4 (`max(nominal, anchor) − 1.5`, first-contact band 1.0 mm, 3 floor pushes per task, examples at 4.50). |
| 9 | major | N1 can touch wet paste | **Fixed**: §4.5 (camera look per spot, `BareSpotCheck` fixed offline on bench frames), §4.6 (keep-outs + 0.4 mm N1 offset error). **Rejected** in part: refusing the automatic probe after dots were dispensed. Its spots are ≥ 1.4 mm from pads and looked at first, which is safer than jogging N1 by hand near the paste. |
| 10 | major | loopback TCP does not prove the simulator | **Fixed**: §9.1 (M115 `FIRMWARE_NAME:GcodeServer` per connection; `RecordingGcodeServer.firmwareName`), tests §10.1. A port registry was not used: under uinav the driver's port is the proxy's, not the server's (GcodeCapture.java:137-144). |
| 11 | major | visual homing keeps moving after a STOP | **Fixed**: §2.3 step 2 (disconnect the drivers on `machineDisabled` while homing), §10.2 (`delayBeforeOkMs`), §11 items 1 and 18, §14 question 8. |
| 12 | minor | test timings valid in a real config | **Fixed**: §4.4 / §6.1 (≥ 1000 ms and ≥ 65 ms enforced at task start unless `isSimulated`). |
| 13 | minor | N1 lines legal on every path | **Fixed**: §4.1 (`checkProbe` for the PROBE scope only; `check()` unchanged), tests §10.1 and §10.2. |
| 14 | minor | pressure hold vs a starting task | **Fixed**: §2.7 (both re-checked on the machine thread), §10.2 case 24. |
| 15 | minor | OpenPnP's later fiducial check goes unnoticed | **Fixed**: §3.5 (`Nominal.openPnpTransform` in `matches`), §6.2, §10.2 case 15. |
| 16 | minor | 3-point fits have no residual check; wrong citation | **Fixed**: §3.3 (limits against the previous registration; citation corrected). Changed: 0.15° instead of 0.1°. The 10 bench shears span about 0.09°, and 0.15° still catches a 0.3 mm error (≈ 0.25°). |
| 17 | minor | `home-unconfirmed` sticks after an OpenPnP home | **Fixed**: §2.2 (decided at each `machineHomed`, start cleared), tests §10.1. |
| 18 | minor | needle frame vs board conflict | **Raised** as §14 question 1. The refusal names where the anchor was taken (§6.2). |
| 19 | minor | coverage points push residuals over the limit | **Fixed**: §4.6 (coverage only for uncovered dots, `boardSpots` override, 5 points on ftp), §11 item 13. |
| 20 | minor | travel Z is an N2 height | **Fixed**: §4.4 (the dispenser mapper's raw Z, sent as N1 Z, safe-zone check). |
| 21 | minor | a STOP after the last point can still save | **Fixed**: §5.2 (`aborted` checked before the save and before storing the registration), §10.2. |

### ux-test

| # | sev. | point | verdict |
|---|---|---|---|
| 1 | major | I11 after reconnect; `reconnected` never reached | **Fixed**: §2.2 (no `reconnected`; disable + enable → `stopped`), §10.2 cases 18 and 20 (start-angle confirm), §10.3 checks 1–2 and 35 (the I11 answer), §10.4 step 8. |
| 2 | major | Playwright block breaks later checks | **Fixed**: §10.3 (after the camera snapshot; purge reset; 47 new checks, 176 in all). |
| 3 | major | wrong expected geometry; no pivot | **Fixed**: §9.2 / §9.5 (pivot = board origin, `reseat`, `apply`, `min_pad_shift`), §10.2–10.4 (expectations computed from the scene; sensitivity ≥ 0.7 mm by construction, 0.74 offline; S2 moves 0.84/1.35/1.29 mm). |
| 4 | major | "register by hand" does not exist | **Fixed**: §6.2 texts, §8.2 REG fixes (look with the camera, change board, plan without correction), `#reg-correction` (§8.1), §10.3 checks 45–47. |
| 5 | major | Board top Z still needs a manual touch | **Fixed**: §8.1 (relabelled approximate ±1 mm, prefilled, frame check under the field). |
| 6 | major | by-hand disclosure in the fixed-height foot | **Fixed**: §8.1 / §8.4 (disclosure in the body, the foot chosen by `R.byHand`, every `#probe-*` id placed). |
| 7 | major | two sets of probe targets | **Fixed**: §8.1 (the by-hand path uses `registrationTargets.probes`; one overlay set). |
| 8 | major | manual path untested | **Fixed**: §10.3 checks 26–28 and 43. |
| 9 | major | "Same board, not moved" skips both checks | **Fixed**: §8.2 (`register-check` for registrable boards), §3.6. |
| 10 | major | `home-unconfirmed` never clears | **Fixed**: §2.2 (same as safety 17). |
| 11 | major | slices not disjoint | **Fixed**: §13. (a) the renderer reads the scene JSON; (b) run-webui-sim-test.sh moves to web-ui; (c) DEV_NOTES.md is web-ui only; (d) `delayBeforeOkMs` in the sim contract; (e) the report classes belong to engine-core; (f) `CameraCapture.captureLit` belongs to engine-core. |
| 12 | major | job boards untested | **Fixed**: §10.2 case 15. **Rejected** in part: the Playwright job-board copy check, because the Playwright session opens no OpenPnP job, and the copy is a static string. |
| 13 | major | stopped text misstates what was saved | **Fixed**: §8.1 state 4 (by stage and seat), §8.4 (`#blank-result` texts), §10.3 check 34. |
| 14 | major | 53 persisted settings | **Fixed**: §6.1 (15 persisted: 13 knobs + 2 engine stamps, instead of about 53; the rest are code constants). |
| 15 | minor | jargon in texts | **Fixed**: §6.2 (next steps, mm instead of scale/shear, "counts", flatness, "clean and uncovered"). |
| 16 | minor | reload / second browser | **Fixed**: §8.1 (sessionStorage, `nextPlanId` on every status). |
| 17 | minor | autoStep hides the registration | **Fixed**: §8.1; the register fix calls `goStep('probe')` (§8.2). |
| 18 | minor | "homes first" shown as needs-you | **Fixed**: §8.2 (state `info`, counts ready; `need` only when N1 is low). |
| 19 | minor | alias starts a new board on a re-seat | **Fixed**: §8.1 (`R.alias[next] = R.alias[old] \|\| old`). |
| 20 | minor | `notebox--ok` does not exist | **Fixed**: §8.1 (`.ri` like `#clean-result`, `.ri--ok` in run.css). |
| 21 | minor | leftover "Probe step" copy | **Fixed**: §8.1 board-step copy. |
| 22 | minor | the gate misses 409 preconditions | **Fixed**: §8.1 `#register-why` (needle frame, window, N1 state) and the pre-press line. |
| 23 | minor | time label does not add up | **Fixed**: §8.1 (computed from `estimatedSeconds`), §11 item 14. |
| 24 | minor | sim parts nobody uses; untested refusals | **Fixed**: §9.2–9.3 (dropped `B1 S2` and `rotationDeg`); §10.1 / §10.2 cases 22–23. |
| 25 | minor | real-time sim waits | **Fixed**: §9.5 / §10.3 (`FAST_TIMINGS` in Playwright and il_ui_session; `--register` opt-in), §10.4 (bench timing for one registration). |

**One conflict between the two critiques.** Safety 3 asks Recovery to keep the home step "needed"; ux 1 asks that
Recovery complete after Start without Home. Resolved by N1's height: the step is done without Home when N1 is in the
safe zone (an automatic home is then harmless), and stays needed, showing N1's Z, when a STOP left N1 low (§8.2).

---

## 16. Main-thread decisions on §14 (2026-10-29, binding for the build)

1. **One frame reference per surface (not "record the needle frame on the board").** The frame check exists to catch
   a height taken with the needle instead of N1 (2.59 mm apart); it must not refuse a board that is simply 1 mm lower
   than the blank. So:
   - the blank surface and named places are checked against the needle-frame anchor, as today;
   - a board surface is checked against the board's nominal top Z (the plan's board location Z: the file seat's `z`
     or the job board's Z), with the same `zFrameToleranceMm`;
   - the needle hard floor (`DispenseConfig.needleFloorZMm`, D/DispenseConfig.java:340) and every check built on it use
     the lower of the anchor surface and the reference of the surface the dots take their Z from (minus the tolerance,
     plus the offset, minus 0.1 as today). Automatic probe surfaces are N1 heights by construction.
   - Owner: engine-core (Interlocks, DispenseConfig, GcodeStreamValidator/planner call sites). Unit tests: a board
     surface 1.0 mm under the blank anchor passes; a board surface 2.59 mm off its nominal is refused; the floor
     allows a dot 0.1 mm above a board 1.0 mm under the anchor and still refuses one below it.
2. Registrations persist in `dispense-config.json` (the default).
3. Keep `registration: "auto"` on `POST /api/plan` (with the same-seat touches and the engine new-seat flag).
4. Job boards: never write our transform back to OpenPnP's job board.
5. No `never` value: `homeBeforeRun` stays `whenNeeded` (default) | `always`.
6. Blank: no keep-out zones; the camera look and explicit targets are enough.
7. A failed Z check refuses; no automatic correction in this build.
8. Keep `whenNeeded` as the default; REAL_MACHINE_TODO puts "STOP during G28" and "Z repeatability across homes" first,
   to be tried before the first real automatic home.
9. Keep the Z check on every needle task after a home (the design default).
10. **KiCad upload / board render** (`docs/instancelabs/BOARD_IMPORT_DESIGN.md`, designed in parallel) will add
    board art with drills, vias and copper. `ProbeTargets` must accept an optional list of extra keep-out circles
    (`{x, y, r}` in board mm) so the art can feed it later without changing its contract; the SIM renderer must take
    its fiducials and pads from the board (placements/footprints), not from hard-coded ftp geometry.

---

## 17. As built (2026-10-29)

What is merged on `il/bench-sync-autoreg` (b81d73d21): wave 1 (vision, sim, engine-core), tasks-api, the fixes from
the real sessions of 2026-10-28/29 (F1, F2, F4, F6, F8; `REAL_MACHINE_TODO.md` has the findings) and review 1. The
web-ui slice (`il/ar-webui`) is merged with BI's web-flow on `il/rc1` (the AR + BI integration slice), and Register
board passes the board art's keep-outs to `ProbeTargets` (§16 item 10, `RegistrationEndpoints`; a board without art
gets the assumed corner holes below). The API as built is in `WEB_API.md` §3.2.
Where the code differs from the sections above, the code is right:

- **§3.3, the search bound (F4).** `searchRadiusMm` bounds the distance of a mark from the fiducial's **expected**
  position (the board key's last registration when it still applies, else the nominal seat, §3.7), not from the
  prediction. The prediction (moved by the first find, then the similarity of two) only orders the search frames, which
  tile the circle around the expected position (at most 9), the one nearest the prediction first (final fix round). A
  mark that centring carries past the bound is not found either. The failure names the expected position: `REG: FID1
  not found within 8.0 mm of X 352.11, Y 108.55 (3 camera frames). …`. On the machine a first registration of board 3,
  which sits 9.2 mm off its nominal, needs the board X/Y corrected or the radius widened (12 worked). §3.7's "from the
  prediction" reads "from the expected position".
- **§3.6, the seat (F8).** `BoardRegistration.matches` compares the board key, X/Y/rotation within 1e-4, the side,
  OpenPnP's transform and the stored fiducials' board coordinates, **not the board Z**, as
  `DispenseService.boardRegistration` already did. Planning again with only a new board top Z (what the I4 band refusal
  asks for) keeps the stored registration; the probe window and I4 use the plan's own board Z.
- **§2.4, Z frames (review 1).** Every Z datum carries the home it was measured under, and a passing touch vouches
  only for data of its own frame (the paragraph "Z frames" in §2.4). The run, Check Z and the same-seat check all
  follow it; a datum with no point of its frame left is refused before anything moves.
- **§4.4, a progressive seal (F6).** The guard "a fine contact must lie below the last open coarse step" is gone. Real
  N1 seals build up over Z and time (board 3: open at 5.40, 1.78 M at 5.375, sealed at 5.325 / 5.30 on the fine pass,
  while the quick coarse reads at 5.25 stayed just above the threshold); the guard fired only after the one-step-higher
  release, exactly when that step held a partial seal, and rejected the true contact. A fine contact at or above the
  last open coarse step is now the surface. `VacuumProberTest.aRealProgressiveSealAboveTheLastOpenStepIsTheContact`
  replays the real readings. On board 3 every point then gave a contact (15 of 15 in three full probes), 14 of them
  after the one-step-higher release.
- **§4.4, the end of a point (F7, not changed).** Every point still ends with the bench's lift, then the vent
  (`G1 Z<travel>`, `M400`, `M107 P1`, `M107 P0`). On a surface that follows N1 (the loose blank) the lift carries it up.
  Proposal: vent, wait for the seal to let go, then lift; to be verified on the machine first (`REAL_MACHINE_TODO.md`
  item 22).
- **§4.5, the look (F1).** The reference is no longer the ring's median colour but a smooth colour field fitted to the
  ring (per BGR channel a quadratic in x and y, least squares over the half of the ring pixels nearest to it, refitted
  4 times), so the ring light's shading on glossy mask and blank belongs to the surface. Then:
  - **texture** = the median distance of that nearest half from the field; a pixel differs above
    max(`colourDelta` 18, `textureFactor` 4 × texture), so on a rough mask only what stands out of the texture counts;
  - **solid patches**: differing pixels count only in patches that hold a disc of radius 0.10 mm (a 3 × 3 majority
    vote, then an opening); texture, scratches and thin print drop out;
  - **wide patches**: pixels 22 or more from the field (`wideDelta`) count whatever the texture in patches that hold a
    disc of radius 0.20 mm (0.4 mm across), so a rough mask cannot hide a paste dot, a part, a hole's ring or an edge;
  - not bare above a foreign share of 5 % of the 1.0 mm disc, or a **ring spread** (the median BGR distance of the
    ring from its median colour) above 50: shading reads up to 24, board 3's scratched mask with two parts in the ring
    38, the blank's edge 76.

  All 20 labelled real frames of 2026-10-29 are test fixtures: bare spots read 0–1.3 %, paste, holes and edges
  25–87 %. The PROBE record's look also holds `rawShare`, `threshold` and `wideShare`; the API's `look` stays
  `{bare, foreignShare, ringSpread, reason}`.
- **§4.6, board targets (F2).** Without dots the ideal corners sit **8 mm** inside the outline (`CORNER_INSET_MM`, the
  bench's ftp spots), not on the bbox of nothing. Without board art a mounting hole is **assumed in each corner** of an
  outline of at least 20 mm: centre 4.5 mm in from both edges, ring radius 3.2 mm, kept out like every feature. Step 7
  reads: alternates on rings of **5 mm and 2 mm**, every 30°, at least 2 mm from the target and each other; the first
  on the 5 mm ring (keeping the hull if it can), each next one on the outermost ring left and as far in direction from
  those taken as it can be, so a hole on one side of a target cannot take every alternate; 2 mm only where 5 mm is not
  free. Explicit blank targets keep their alternates 2 mm either side in X.
- **§6.1, settings.** `vacuumProbe.alternates` defaults to **4** (valid 0..4; a saved file keeps its own value). The
  rest of the table stands. `fiducialCheck.searchRadiusMm` is checked against the 9-frame cap at the default camera
  size (1920 × 1080 at 0.01068 × 0.01073 mm/px) in `validate()`, and at the real camera's size inside the task.
- **§5.2, probe only (F10, real session 4).** A board file with no fiducials could not get a `board` surface: the
  surface probe refuses `board`, and Register board refused fewer than `minFiducials` fiducials (the workaround was a
  probe under another name, then `PUT /api/surfaces/board {points, planId}`). Now Register with `probe: "always"` on a
  plan without a registration (`registration: "none"`, or `auto` with nothing stored) of a board without fiducials
  probes it only: home if needed, the camera look and the N1 probe of the board's targets where the plan puts the
  board (X/Y as planned), no fiducial fit and no registration stored, the surface tied to that seat
  (`boardRegistration` of the nominal board: a plan at another X/Y, rotation or side is refused by I4 until probed
  there), the new-seat flag cleared, the board planned again on the surface. The result says `no fiducials: probed
  only, X/Y as planned` (`probeOnly: true`); the preview has `probeOnly`. Other probe modes keep the refusal (it names
  the way out), a board with enough fiducials always registers by them (every refusal of §5.2 stands), and a board
  with some fiducials, fewer than `minFiducials`, is refused as before: it is never probed without fitting them
  (review of F10). A STOP during a probe only vents N1 and saves nothing.
  `RegistrationSimTest.aBoardWithoutFiducialsIsProbedOnlyWhereItIsPlanned`,
  `probeOnlyNeverSkipsTheFiducialsOfABoardThatHasThem`.
- **Departures accepted during the build.** `FiducialDetector` keeps only marks brighter than their ring (contrast > 0:
  drops dark via centres). `VacuumProber` releases one coarse step higher when the last open step does not let go
  (verified on `runs/serial.log`). `BoardRegistration.matches` also compares the fiducials' board coordinates (a
  board file edited to move a fiducial must not keep the old affine). Purge dots keep the anchor's needle floor; work
  dots use the lower of the anchor and the board's reference (§16 item 1). `POST /api/plan` refuses a board top Z
  more than 2.0 mm from the needle-frame surface (`MAX_BOARD_Z_FROM_ANCHOR_MM`).
- **Not built or not covered.** No endpoint clears the needle anchor. §10.2 cases 3 (surface changed by +0.08 → full
  probe), 6 (5 mm / −4.6° re-seat), 8 (fit against the previous registration), 12 (release lag 0.4 s) and 15 (job
  board: no headless seam) have no `RegistrationSimTest` case; the engine-core and vision unit tests cover the code
  paths. Case 22 (a simulated surface on a Marlin link) is covered since the rc1 review: `ZFrameTest`, the plan and
  the run after `RegistrationSimTest`'s switch to Marlin, and `DoseBlocksCouponFrameTest` for a coupon; only the
  same-seat check's own guard (`probeBoard`'s `tied`) has no case, as `register()` drops a SIM-made registration on
  a real link before it. Review 1's probe floor of a board far below the anchor is fixed in the final round
  (`VacuumProber.Window` refuses a floor at or above the board top); the camera look's mm/px is listed in
  `REAL_MACHINE_TODO.md`; the frames against the acceptance circle and the rotation-dependent fit check are fixed
  (the fit against the previous registration compares the machine-frame stretch, `FiducialLocator.machineFrame`).
