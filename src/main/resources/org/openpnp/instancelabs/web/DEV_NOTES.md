# Web UI: developer notes and module contract

Spec: `docs/instancelabs/UI_DESIGN.md` (binding; § numbers below refer to it). API: `docs/instancelabs/WEB_API.md`.
Design system source: `ui-revamp/final/src/final.css` (tokens and components, ported to `app.css`).

The foundation (frame, store, poll, router, readiness, strip, STOP, panel host, dialogs, two-press) lives in
`core.js`, `app.css` and `index.html`. The workspaces are built in parallel **without touching those three
files**: each workspace module registers itself with `IL.workspace()` and fills its own `<section>`. If you need
something new from core, ask for it (or add it to your module and propose it for core afterwards).

## 1. Files and owners

| File | Owner | Content |
|---|---|---|
| `index.html` | core | Frame only: SVG icon sprite, status strip (STOP first in the DOM), nav, `#main` with `#msg-area` and five **empty** `<section data-workspace>`, `#panel` host, `#stop-unconfirmed`, `#toast`. Script/link tags. |
| `app.css` | core | Tokens (light + `[data-theme=dark]`), frame, strip, STOP, nav, panel, shared components (§11). |
| `core.js` | core | The `IL` namespace (this document). Loaded **synchronously** in `<head>`: sets the theme before first paint. |
| `app.js` | core | `IL.boot()`. Loaded last. Kept because `ApiServerSimTest` fetches `/static/app.js`. |
| `boardview.js` | Run stage, board import (web-render) | `IL.boardview`: the to-scale board view (hero, probe and fiducial overlay, pad inspector, upload preview, Records done-dot map): two stacked SVGs, the drawing under the job overlay (§5 › Board drawing), and the shared geometry (`hull`, `inflate`, `outside`, `bbox`, `planeFit`, `cased`). |
| `boardart.js` | board import (web-render) | `IL.boardart`: the board drawing (art JSON) as SVG, thumbnails, the board → machine affine helpers (§5 › Board drawing). |
| `pathpreview.js` | board import (web-render) | `IL.pathPreview`: the path preview row `#run-preview` (§5 › Board drawing). |
| `padinspect.js` | board import (web-render) | `IL.padInspect`, panel view `pad`: the pad inspector (§5 › Board drawing). |
| `board.css` | board import (web-render) | The board palette, `.pv--art` and the casing, picture modes, the preview row, the inspector. Loaded after `run.css`. |
| `boardflow.js` | board import (web-flow) | `IL.boardFlow`: fills the hooks H1–H5 of `run.js` / `records.js` (§11; §5 › Board drawing). |
| `boardimport.js` + `boardimport.css` | board import (web-flow) | `IL.boardImport`: the upload dialog `#dlg-board-import` and the page-wide drop guard (§5 › Board drawing). |
| `padprofile.js` + `padprofile.css` | profile calibration (pc-web) | `IL.padProfile`, the `pad-cal` view, Profile › Needs a profile and the suggestion card, the profile column of the upload, the pad inspector's profile notebox (§5 › Missing paste profiles; `PROFILE_CALIBRATION_DESIGN.md` §13). |
| `run.js` + `run.css` | Run stage | `#ws-run`, Run panel steps (board, probe, checks, run, result, recovery override), run confirm `#dlg-run`. |
| `setup.js` + `setup.css` | Setup stage | `#ws-setup` (cards §8.2), default panel `head`. `calibrate.js` may reuse `setup.css`. |
| `profile.js` + `profile.css` | Profile stage | `#ws-profile` (§8.3), `#dlg-padtype`, `#dlg-history`, panel `pt`. |
| `calibrate.js` | Calibrate stage | `#ws-calibrate` (§8.4), panel `cal`; the Pad calibration and Weigh dots cards and the guide's ladder (pc-web). |
| `records.js` | Records stage | `#ws-records` (§8.5), panel `record`. |
| `favicon.svg` | core | Idle favicon (live / stopped variants are `data:` URLs in core). |

New static files: flat names `[A-Za-z0-9_-]+\.(html|js|css|svg|png)`, served from `/static/<name>`; add them to
`ApiServerSimTest.servesTheUiAndStatus`. CSP is `script-src 'self'`: no inline scripts, no `on*=` attributes, no
`eval`, no external URLs or fonts. **Never** `confirm()`, `alert()`, `prompt()` or `dialog.showModal()` (they block
STOP): use `IL.press2`, `IL.dialog.open` / `IL.dialog.showNative`.

## 2. Load order and lifecycle

```
<head>  core.js (sync)  → IL defined, theme applied, F9 / Space / Esc key handlers and the STOP clicks installed
        boardart.js, boardview.js, pathpreview.js, padinspect.js, boardflow.js, boardimport.js, padprofile.js,
        run.js, setup.js, profile.js, calibrate.js, records.js (defer, in this order)
        app.js (defer)  → IL.boot()   (core boots by itself at DOMContentLoaded if app.js never ran)
boot:   mount(root) of every registered workspace → route applied → show() of the active one
        → poll /api/status (0.7 s chain) → render loop
```

Per workspace (`IL.workspace(name, spec)`; name ∈ `run setup profile calibrate records`):

| Hook | When | Rules |
|---|---|---|
| `mount(root)` | once at boot (`root` = `section[data-workspace=name]`, id `ws-<name>`) | Build the static DOM here (cards, fields, buttons with their ids from §16). Wire events. |
| `show(route)` / `hide()` | workspace switch | Load on demand (e.g. `IL.loadConfig()`), start/stop timers. `#main` scroll position is remembered per workspace. |
| `render(status)` | every poll (0.7 s), every second (`tick`), and after `IL.render()`; **only for the visible workspace** and only once a status exists | Patch, never rebuild regions with inputs (§15). Use `IL.setText/setAttr/patch/keyed/gate`. |
| `panel(parts, status, first)` | the default context-panel view of this workspace, same cadence as render | `first === true`: parts were just cleared, build. Otherwise patch. `parts = {root, top, body, foot, scroll}`. |
| `route(route)` | every hash change while this workspace is active (also right after `show`) | Handle `route.section` / `route.id` (e.g. `IL.flash(card)` for `#setup/purge`). |
| `nav(status)` | every render | Return `{sub, kind, badge, badgeKind, badgeLabel, dirty, title}` to override the nav item (kind / badgeKind: `need`, `block`, `live`, `ok`). Core has defaults for Run (from readiness / task / recovery), Setup and Calibrate (`Locked · running`), Profile (name / `Confirm angle`). |
| `defaultView` | string | `#panel[data-view]` for the default panel (else the workspace name). Run changes it per step with `IL.panel.setView('checks')`. |
| `ownsRecovery` | boolean | Run sets `true`: then core does **not** push its Recovery view on Run, and Run shows Recovery as its own step. On other workspaces core pushes the `recovery` view while a recovery is open. |

Registering after boot works (mounted at once).

## 3. State: `IL.S` (single store; read it, change it only through the IL functions)

| Key | Content |
|---|---|
| `status`, `prevStatus` | last good `GET /api/status` and the one before (null before the first answer) |
| `statusAt`, `lastOkAt` | client ms of the last good status |
| `online` | `null` before the first answer, `true`, or `false` after 3 s without one (offline mode) |
| `skewMs` | server clock − client clock; server stamps (`…AtMs` in status) → client time: `ms - IL.S.skewMs`; `IL.serverNow()` |
| `plan`, `planId` | the plan shown (POST/GET plan response) |
| `planPending` | a change-triggered re-read is in flight (readiness item 7 reads `checking`) |
| `planForgotten` | `GET /api/plan/{id}` answered 404 (OpenPnP restarted) → plan chip `Plan forgotten (OpenPnP restarted): plan again` |
| `planAt` | client ms of the last plan read |
| `cleanCheck` | newest clean-pad report of the plan shown (from the plan, a subset run, or `lastRun.cleanCheck`) |
| `newSeatAtMs` | the engine's new-seat flag (`status.dispense.newSeatAtMs`, as client ms): readiness item 6 needs Register board after it. `IL.setNewSeat(atMs \| null)` posts `/api/board/new-seat`; `newSeatPending` keeps the answer for 3 s while the post is in flight |
| `route` | `{ws, section, id, panel, params}` |
| `recovery` | open recovery or null: `{cause: 'stop'|'aborted'|'error'|'openpnp', atMs, actions (server STOP answer), source ('F9'|'button'|'key'), run: {runId, label, boardName, planId, status, message, phase, dotsDone, dotsPlanned, doneDots, pressureOnS}, message, seenDown, purgeShort}` |
| `lastStop` | the last recovery after it cleared (Run's board decision: finish the pads left / wipe all) |
| `activity` | this page's log, newest first `[{atMs, text, kind}]` (toasts and refusals land here too) |
| `runs`, `config`, `events` | caches filled by `IL.loadRuns()`, `IL.loadConfig()`, `IL.loadEvents()` |
| `connectedAt`, `homedAt` | client-observed transition times |
| `runPressureSince` | client stamp of the first poll of a run's pressure session (phase `dispensing` / `shots` / `dispensing calibration dots`) |

Browser storage: `localStorage['il.theme']`, `['il.planId']`, `['il.attest']`, (Run: `['il.boardloc.<file>']`,
`['il.run.source']`, `['il.run.sel']`, `['il.pv.picture']`); `sessionStorage['il.recovery']`, (Run:
`['il.run.regResult']`, `['il.run.kinds']`, `['il.run.probe']`, `['il.run.decidedAt']`, Setup:
`['il.setup.blankRun']`, the pad calibration's of §5 › Missing paste profiles). The board's paste and number are the server's (`plan.pasted`): no
browser keeps them. A value left in
`localStorage['il.newSeatAtMs']` by an older page is posted to the engine once, then removed.

## 4. Events (`IL.on(name, fn)` returns the unsubscribe function)

| Event | Args | When |
|---|---|---|
| `status` | `(status, prev)` | every good poll, before rendering |
| `plan` | `(plan, prev)` | `setPlan`, `loadPlan`, `planRefresh`, 404 (plan null + `S.planForgotten`) |
| `cleancheck` | `(report)` | a newer clean-pad report of the plan shown was adopted |
| `taskend` | `(run)` | a task (board, clean-pad check, prime, test dot, touch-off, ladder, shots, board registration, blank probe, z check) ended; core already re-read the plan and runs |
| `stop` | `(answer)` | our STOP was confirmed (`answer.actions`) |
| `recovery` | `(rec or null)` | a recovery opened / cleared |
| `online` | `(bool)` | offline after 3 s without an answer / back |
| `route` | `(route, prev)` | after every hash change |
| `tick` | — | every second (countdowns) |
| `theme`, `attest`, `activity`, `runs`, `config`, `events`, `boot` | | as named |

## 5. API reference (`core.js`)

### Network and data
- `IL.api(method, path, body?, {timeoutMs?})` → parsed JSON; throws `IL.ApiError {status, data, code, message}`.
  `status 0` = no answer (`code` `timeout` / `network`): show `Outcome unknown: look at the machine.`, never re-send.
  Default timeout 10 s (status 2.5 s, STOP 2 s are internal).
- `IL.errorText(e)` one line incl. interlock violations.
- `IL.pollNow()` poll at once (after an action). `IL.render()` schedule one render (coalesced per frame).
- `IL.setPlan(plan)` (after `POST /api/plan`; remembers `il.planId`), `IL.clearPlan()`, `IL.loadPlan(id)`,
  `IL.planRefresh({quiet})`, `IL.planRefreshSoon()` (300 ms debounce; marks `planPending`). Core re-reads the plan by
  itself when any freshness input of §9.2 changes, every 10 s while visible, and after every task.
  Plan restore on load: the running task's plan, else `localStorage['il.planId']` if OpenPnP still keeps it, else the
  newest plan of `GET /api/plans` (a fresh browser shows the latest plan), else the last task's plan (404 → "Plan
  forgotten").
- `IL.basePlanId(id)` (strips `-subset-…`), `IL.planMatches(planId)`, `IL.adoptCleanCheck(cc, planId)`,
  `IL.dirtyPads(cc)` → `[{padId, share, error}]` worst first, `IL.cleanSummary(cc)` → the machine-readable first
  sentence of `#clean-result` (§8.1.4).
- `IL.loadRuns()` (`/api/runs?limit=50`, also at load and after each task), `IL.loadConfig()`, `IL.loadEvents(limit)`.
  A failed load is kept in `IL.loadError('runs' | 'config' | 'events')` (`{text, atMs}`, cleared by the next good load):
  show it with a Retry instead of "Loading…" forever.
- `IL.cleanPositionWrong(cc)`: a finished report with no clean pad ("the board position looks wrong", §13): never
  painted as paste, never "wipe".
- `IL.attest.{all(), get(key), set(key, value), clear(key), valid(key, status)}` keys `regulator` (psi),
  `paste-life`, `prime-seen`, `test-dot-seen` (§9.4; regulator cleared by any psi change a page sees, also a change
  back, unless the tick is already at the new psi; invalid after a needle change or 8 h). `hands-clear` is never
  stored. `IL.setNewSeat(atMs | null)`. Both live in localStorage: another tab's tick or new-seat answer counts at
  once (`storage` event).
- Machine helpers: `IL.positions(status)` → `{n1, n2, cam}`, `IL.wasteDistance(status)` (mm or null),
  `IL.isSim(status)`, `IL.taskRunning(status)`, `IL.energised(status)`, `IL.interlock(status, id)`,
  `IL.taskLabel(run)` (`96/160 · 0:44 left`, `Pad check · 1:52`, `Prime`).

### Readiness (§9) — the only place that computes "ready"
- `IL.readiness(status, plan, ctx)` — **pure**. `ctx = {nowMs, attest, planPending, planForgotten, cleanCheck,
  newSeatAtMs, homedAt}`. Returns:
  ```
  { items: [9 × Item] in IL.READY_ORDER = machine, angle, needle, regulator, padtypes, surface, plan, purge, clean,
    extras: [Item]   // other blockers shown as extra cards on top: CONFIG, I1 warning, I3, I7, place Z, blank surface,
                     // pressure held (need) / vent_failed (block), engine unavailable
    count, total: 9, next (first not-ready card; extras first), needs: [extras + not-ready items, fixed order],
    blocked: [...], verdict: {state: ok|need|block, text: 'Ready to run' | '8 of 9 · wipe 2 pads' | 'Blocked · start angle' | 'No plan yet', title},
    canRun, runStyle: 'go' (filled teal) | 'hold' (outlined; items 4 / 9 not ready) | 'blocked', runReason (the line above the Run button),
    planLabel }
  Item = { key, n, code ('MACHINE','I11','I4','I6','PLAN','I9','I10',...), title, state, ready, value (short, right-aligned),
           short (for the verdict / nav), detail (plain sentence with numbers), tip, fixes: [Fix], you?, stale?, dirty?,
           seat? (surface item, need or block, when the seat needs a probe: new | none | other | untied) }
  state: ok | you (operator attestation, blue ring) | off (counted ready) | need | block | live | checking | wait (needs a plan)
  Fix = {fix: '<id>', label, arg?, primary?} | {href: '#setup/needle', label} | {view: 'pressure', label}
  ```
- `IL.ready()` the same for the store now (memoised per status / plan / attestation change). Use it for the Checks
  header, segment bar, needs-you cards, ready tiles, Run button and run confirm.
- `IL.verdict()` the strip verdict now (task / recovery / offline / engine override the readiness verdict).

### STOP and recovery (§5, §8.1.8)
- `IL.stop(source)` = the STOP press (all `#btn-stop` / `[data-stop-copy]` clicks are wired by delegation when core.js
  loads, before boot; F9 is global in the capture phase, also in fields and dialogs; Space on STOP is suppressed; Esc
  never stops). OpenPnP answers within 1.5 s (`machineEnabled` may still be true: Recovery follows the status). Shows
  `STOPPING…`, posts `/api/stop` (2 s), on no answer shows `#stop-unconfirmed` and re-sends every second until one
  answer; toast `STOPPED hh:mm:ss: <actions joined "; ">`; opens the recovery.
- Second STOP: core shows the copy at the top of the panel scroll area whenever `IL.energised(status)`. Do not add
  your own; never place a Run / commit button where it sits (top of the panel).
- `IL.stoppedRun(s)`: the status still shows the run our STOP just stopped (≤ 5 s): the strip, the plan chip and Run
  show it as stopped / switching off, never as dispensing. `IL.noDotsReached(run)`: a stopped board run put no dot down
  (one action: run the board again).
- `IL.regProgress(run)`: where a Register board / blank probe task is, from `status.run.detail`: `{stage, k, n, part
  ('home' | 'FID 2/3' | 'check 2/3' | 'probe 3/5' | 'plan again'), left (s, counting down between the engine's steps)}`.
  The strip, the plan chip, the stepper and the foot button share it. `IL.TASK_WORD` maps task labels to words
  (`board registration` → `Register`, `blank probe`, `z check`). `IL.homeWhy(status.machine.home)`: why a task homes
  first, for "Homes first (…)": `setting: always`, `a stop came during the last home`, `not homed since 07:27` (the
  engine's `sinceMs` is the later of the last stop and the last connect) or `not homed`.
- `IL.boardLeftS(run)`: seconds left of a board run, the one number for the strip, tiles, timeline and stepper (the
  live rate blended with the plan estimate over the first 20 dots). `IL.tourProgress` (set by run.js): the camera tour
  progress `{text, left, k, n, approx}` the strip and the Pads clean card share.
- `IL.recoveryActive()`, `IL.recoverySteps(status, rec)` → `[{key: vent|connect|angle|home|purge|decide, n, title,
  detail, done, wait, now, state, fixes}]` (`purge` only when the row is / was short, `decide` only for a board),
  `IL.recoveryHeadline(rec)`. Recovery clears itself when vent, connect, angle, home and purge are done
  (`S.lastStop` keeps it). Core's `recovery` view renders the red card, Back to ready and What STOP did; Run may
  replace it (`IL.view('recovery', …)`) and should own it on Run (`ownsRecovery`).

### Actions, two-press, fix-its, refusals
- `IL.act(btn, async fn, {ok, refusalIn, what})` runs an action: button disabled + `aria-busy` meanwhile; errors →
  `IL.refuse`; then `pollNow` + `render`. `ok`: toast text or `fn(result)` (an empty text: no toast, e.g. an action that
  showed its refusal at a field itself).
- `IL.refuse(err, {host, what})` toast + activity + an inline `.refusal` box in `host` (default: the nearest
  `[data-refusals]` ancestor of the button) listing violations with their I-code and fix-it buttons.
- `IL.press2(btn, verb, action, opts)` makes a **two-press** button (§11): first press → `.btn--press`, label
  `Press again: <verb>`, 4 s bar; second press ≥ 300 ms later (no key auto-repeat) runs `IL.act(btn, action, opts)`;
  Esc, blur, a press elsewhere or 4 s cancel. `verb` may be `fn(status) → verb | null` (null = single press now).
  Use it for Home, clean check, prime, test dot, touch-off, ladder, shots, purge reset, delete surface, needle
  changed, retire, discard, pressure on away from the waste. `IL.isArmed(btn)`: do not patch an armed button's label.
- Fix-its: `IL.fix(id, {run(btn, arg, status), press2, ok, gate(status) → reason | null, href})` registers;
  `IL.fixButton(fix, {cls})` builds a `<button data-fix=id>` (handler looked up at click time); `IL.fixRow(fixes, cls)`;
  `IL.runFix(id, btn, arg)`; `IL.fixesForCode(code)` for refusals. **Gates**: after every render core greys each
  `button[data-fix]` on the page with its spec's `gate` reason (readiness cards, the Setup band, Recovery, refusals all
  say why a fix is locked); it only re-enables what its own gate disabled, so a module may gate the same button too.
  Core registers (with gates): `connect`, `home` (two-press), `I11-confirm`, `needle-confirm` (posts the frame offset),
  `purge-reset` (two-press), `pressure-on` (two-press unless N2 ≤ 3 mm from the waste), `pressure-off`,
  `attest-regulator`, `attest-paste`, `same-seat`. Run registers `plan-again`, `clean-check` (two-press), `probe`,
  `run-rest` (`{planId, padIds}` → the run confirm for those pads, never one this tab saw pasted), `run-fewer` (I7: a
  test run of the first N dots that fits the on-time limit), and Register board's (AUTO_REGISTER_DESIGN.md §8.2):
  `register` (two-press, `probe: auto`), `register-probe-only` (two-press, `probe: always`: a board without fiducials,
  F10), `register-check` (clears the new-seat flag, then registers: the fiducials decide
  "same seat"), `probe-by-hand` (opens "Do it by hand" on Register), `look-camera` (Setup's Top camera card),
  `plan-unregistered` (plans the board with `registration: 'none'`), `board-step`, `check-z` (two-press,
  `POST /api/machine/z-check`); Setup registers `su-go-<card>`, `su-probe-blank`. Every gated fix-it row gets a visible
  `.fix-why` reason line from core (rows marked `[data-own-reason]` show their own).
  `IL.fixesForCode(code, status, message)`: an I4 refusal that names a place offers `Retake Z with N1` (#setup/places/<name>);
  I4 seat / surface → `Register board` (`Probe only (X/Y as planned)` when the plan shown has
  `registrationTargets.probeOnly`, as in the readiness item and the Z-moved step), `Do it by hand`; I4 "measured before
  the home" → `Check Z`; "no point
  measured before the home" → Needle Z frame; I4 "Z moved" → the remedy's next step, `IL.zMoved(status)` →
  `{step: frame | blank | touch-off | register | done, fixes}` (null unless the last Z check of this home failed:
  `Open Needle Z frame`, `Probe the blank…`, `Run a touch-off` + `Confirm frame offset …`, `Register board` + `Do it by
  hand`, from `machine.zCheck.stale` and `needle.touchedOff`) (`IL.i4Kind` → `zcheck` for all three); I4 "(run a
  touch-off)" → `Run a touch-off`, `Confirm frame offset …`; `REG` → `Look with the camera`, `Change board`, `Plan
  without fiducial correction`, `Register again`, `Do it by hand` (a REG text ending in ` Or probe it only, where it is
  planned: Register with probe "always".`: `Probe only (X/Y as planned)` first, then `Look with the camera`, `Change
  board`, `Do it by hand`); `PROBE` → `Register again`, `Do it by hand` (+
  `Probe the blank…` when it names the blank; a board top the probe "cannot reach" → `Board step`, `Open Needle Z
  frame`, `Do it by hand`).
  `IL.CODE_RE` is the code prefix of an engine text (`I4:`, `MACHINE:`, `CONFIG:`, `PLAN:`, `REG:`, `PROBE:`).
- `IL.lockReason(status, {connected, homed, idle = true, pressureOff = true, engine, homes})` → the spec's locked-control
  text (`Locked while the board runs`, `Pressure is held: switch it off first`, `Not connected: connect first`, offline)
  or null; with `idle`, also the engine's own 409s: an upload reading its file (`status.boardImport.reading`: `A board
  file is being read (an upload takes up to about 20 s): wait for it to finish`) and, for what uses the machine
  (`connected` or `homed`), OpenPnP homing or jogging by itself (`machine.busy`: `OpenPnP is busy (homing or moving):
  wait for it to finish`); `homes`: the task homes by itself (Register board, Probe the blank, the pad calibration) and waits, as Run
  does, while a STOP left N1 low (`IL.n1Low(status)`, the text `IL.n1LowText(status)`). `IL.gate(btn, reason, reasonEl?)` disables with the reason (title + optional visible `.reason` line).

### Router, panel, dialogs, messages
- Hash grammar `#<ws>[/<section>[/<id>]][?panel=<view>]`; `#dispense` → `#setup`, `#profiles` → `#profile`.
  `IL.go('#setup/purge')` or `IL.go({section: 'checks'})` (workspace changes push history, in-page changes replace),
  `IL.openView(name)` (toggles a pushed view), `IL.parseHash`, `IL.hashFor`, `IL.active()`.
- Panel host: `#panel[data-view]` > `#panel-bar` (back + title, core) · `#panel-top` (pinned head, e.g. the Run
  stepper) · `#panel-scroll` (`#panel-stop` STOP copy, core; `#panel-body`) · `#panel-foot` (pinned; hidden when
  empty). `IL.panel.show(view, fn(parts, status, first) | {title, render, unmount}, {title})` pushes a view with a back
  arrow; `IL.panel.close()`, `.reset()` (re-run with `first`), `.setView(name)`, `.current()`, `.isPushed(view)`.
  `IL.view(name, spec)` registers a routable view (`?panel=name`). Core views: `interlocks` (`#interlock-list`
  `li.<engine state>`), `machine`, `pressure`, `angle` (I11 question), `purge`, `activity`, `camera` (simple
  snapshot; Setup may override with overlays), `recovery`. A module may re-register any of them.
  Esc closes the top-most thing: pending two-press → dialog → pushed view.
- `IL.dialog.open({id, eyebrow, title, keys, body, foot, size: 'sm', initialFocus, onClose})` → `{el, body, foot,
  close(reason)}`: in-page `.scrim` (starts under the strip; STOP and the strip stay live) + `.dialog[role=dialog]
  [aria-modal=false]`; Tab cycles the dialog and STOP; Esc = close. `IL.dialog.showNative(<dialog>, opts)` for
  `#dlg-padtype` / `#dlg-history` (`dialog.show()` + scrim; `[open]` still marks them). `IL.dialog.top()`.
  `IL.onEsc(fn → true if handled)` for your popovers / menus.
- `IL.toast(text, kind)` (≤ 4 s, top-left of the main column over the page title, also logged), `IL.msg(key, {kind, text, sub,
  actions} | null)` page band in `#msg-area` (core uses keys `offline`, `vent`), `IL.log(text, kind)`.
- Keyboard: `Alt+1…5` workspaces, `?` key sheet, `Enter` in a field inside `[data-enter-submits="<button id>"]`
  clicks that (Save) button only. Never bind Space or Esc to a machine action.

### DOM and format helpers
`IL.$(id)`, `IL.$$(sel, root)`, `IL.el(tag, {class, text, title, dataset, onclick, 'data-x'}, ...children)`,
`IL.append`, `IL.svgEl(tag, attrs, title)`, `IL.icon('i-gauge', cls)` (sprite ids below), `IL.glyph(kind, size,
label, text)` / `IL.setGlyph(span, …)` (status glyph: shape + colour + word), `IL.setText`, `IL.setAttr`,
`IL.setClass`, `IL.show(node, on)` (hidden attribute; SVG too), `IL.patch(node, {text, class, title, hidden,
disabled, attrs, dataset})`, `IL.keyed(container, key, build)` (rebuild only when `key` changes; keeps focus by
id / `data-focus-key` / `data-fix`; never while a two-press inside is pending), `IL.kv(dl, [[k, v, title]])`,
`IL.timeEl(ms, secs)`, `IL.flash(node)` (scroll + 1.5 s outline), `IL.sectionK(text, count)`.
Values: `IL.isNum(v)` (finite number or numeric string), `IL.toClient(serverMs)` (a server `…AtMs` stamp as client
ms; a client stamp as server time is `ms + IL.S.skewMs`), `IL.nb(text)` (no-break spaces between a number and its unit
or label: "2,343 ms", "bench 5,490"), `IL.unitText(h)` (header text whose units stay lowercase under the uppercase table
headers), `IL.eventText(msg)` (OpenPnP event text with UUIDs, fingerprints and JSON folded; full text as title),
`IL.startAnglesText(profile)` ("297.9° / 117.9° (in whole motor steps)"), `IL.lsJson / lsSet` (localStorage), `IL.ssJson(key, fallback) / IL.ssSet(key, value)` (sessionStorage, JSON).
Format (display only; real minus `−`): `IL.fmt(n, d=2)`, `fmtSigned`, `fmtInt` (`5,410`), `fmtPsi` (`18`, `18.5`),
`fmtMm`, `fmtDeg` (`298.0°`), `fmtClock` (`1:15`), `fmtDuration` (`4.0 s`, `5 min 16 s`), `fmtSecs` (an estimate:
`25 s`, `1 min 25 s`), `fmtTime` (`13:06`),
`fmtTimeS` (`14:19:42`), `fmtDateTime`, `fmtAgo`, `plural(n, 'dot')`, `turnLabel(800, 1600)` (`½ turn`),
`recipeParts(profile)`, `recipeEl(profile, {changed, href})` (the `.recipe` token; `aria-label` = parts without the
name joined by ` · `), `profileText`. Inputs: `IL.inputNum(n, d)` (ASCII; d decimals unless that would lose digits:
never rounds a stored value), `IL.num(idOrEl)`, `IL.str(idOrEl)`.
Constants: `IL.STOP_CODES`, `IL.STOP_TITLE`, `IL.STOP_LINE` (the exact §13 texts), `IL.STOP_DID`, `IL.LIMITS`,
`IL.PRESSURE_PHASES`, `IL.WORD`.

### Board drawing, path preview, pad inspector, upload (BOARD_IMPORT_DESIGN.md §5, 2026-10-29)

The drawing, the preview, the inspector, the upload dialog and the glue (`boardflow.js`, `boardimport.js`) are merged
with §11 (the AR + BI integration slice, on `il/rc1`). Nothing here moves the machine.

- **`IL.boardview`** (`boardview.js` header has the full API): `create(host, {id, aria, onPad, onPart, lite})` makes
  two stacked SVGs in `.pv-host`: `svg.pv.pv-art` (the drawing, `aria-hidden`, no pointer events) under
  `svg#plan-svg.pv` (the job overlay), with the same viewBox. `setPlan(plan)` builds once per plan (reads `plan.board`
  and `plan.dot.diameterMm`: dots at their real size, never below 1.5 px); `setArt(art)` builds the drawing once per
  art, a re-plan changes only its transform; `setBoard({art, toMachine})` / `setAffine(m)` draw a board without a
  plan; `picture('full' | 'quiet' | 'off')`; `update({done, dirty, next, check, needle, n1, probe, fids, cam})` only
  toggles classes and moves markers; `preview({n, ghost})`; `select({padIds, refs})`; `dotLabels(indexes)`;
  `fit(box)`, `setView('dots' | 'board' | 'follow')`, `zoomBy`, `destroy()`. `IL.boardview.of(host)`. Test classes kept:
  `rect.pad`, `rect.pad.dirty`, `circle.dot`, `circle.dot.done` (no art element uses `pad` or `dot`).
- **`IL.boardart`**: `get(id)` (`GET /api/board-art/<id>` through the browser's HTTP cache: the server marks drawings
  immutable, so there is no client cache; null on 404), `build(art, {uid})` (one merged path per layer; labels in three
  size buckets `pv-lb1/2/3`), `thumbUrl(art, {w, h})` (a `blob:` image from `art.thumb`), `toSvgMatrix(m)`,
  `apply(m, x, y)`, `outlineBox`, `originMarker`.
- **`IL.pathPreview.create({host, bv, button, before, onChange})`** → `pp`: `setPlan`, `allow(on)` (false while live,
  stopped or a task runs), `play`, `pause`, `clear`, `seek(v)`, `state`, `destroy`. Times from `plan.dots[i].ta / te`,
  else illustrative (50 mm/s, 0.6 s per dot, said in the readout). `requestAnimationFrame` only while playing and
  visible, at most 30 updates per second.
- **`IL.padInspect.open({padId} | {ref}, {bv})`**, `current()`, view `IL.views.pad` (`#pad-insp-*`): the part's
  openings and dots, the pad type and the rule sentence (`plan.pads[i].typeRule.sentence`), flags in words, the camera
  (`#pad-insp-cam`), `Edit pad type …` / `New pad type for <package>`.
- **`IL.boardFlow`** (web-flow): `mountBoardStep(body, {select, form, setXY, loadBoards})` (H1: `#btn-board-upload`,
  `#board-thumbs`, `#board-imported`, `#btn-board-use-fid`), `hero(bv, {plan, sel, form, mode, s, hero, stageBar,
  row})` → `{shown}` (H3: the drawing, `#pv-picture`, the preview, `#btn-pad-inspect`; before planning the chosen
  board at the form's seat with the live camera, `#board-preplan`), `onPad` / `onPart` (H2), `recordsMap(bv, plan)`
  (H4), `onImported(saved)`, `preview()` (the hero's path preview). Keeps the newest 8 drawings;
  `localStorage['il.pv.picture']` (default Quiet). Board pictures say `75 × 75 mm` and `80 parts`; the attach keeps the
  drawing under the board file's earlier name, else a free one (never another board's).
- **`IL.boardImport.open({file, onSaved})`**, `isOpen()` (web-flow): `#dlg-board-import`, states choose → reading
  (Cancel aborts, 60 s) → preview → saving (Esc and Cancel do nothing) → saved; 12 MB checked before sending; a refused
  read shows its reason and `Choose another file`. The drop guard: a file dropped outside the drop zone never replaces
  the page; a `.kicad_pcb` opens the dialog.
- Tests: `boardview.playwright.cjs` (`run-boardview-test.sh`: a SIM session plans its upload of ftp.kicad_pcb, devproxy
  `--fixtures` adds mobo and a dot size; the drawing, the overlay, the preview, the inspector, the performance gate at 4×
  CPU throttling, through boardflow's own glue), `board-import.playwright.cjs` (web-flow, 57
  checks, run after `webui-sim.playwright.cjs` in the same SIM session).

### Missing paste profiles and the pad calibration (PROFILE_CALIBRATION_DESIGN.md §13, as built 2026-10-29)

`padprofile.js` + `padprofile.css` (pc-web) load after `boardimport.js` / `boardimport.css` and before the workspace
modules (H-P9 in `index.html`). Every hook in another module's file is a no-op without them. API: `WEB_API.md`
§3.4–3.5. The file header lists its API; in short:

- **`IL.padProfile`**: `groups(plan)` → `{fix, tip, notes}`; `padTypesItem(plan, d, viol)` (pure: Run readiness item
  5 from `plan.profileCheck`, or null); `setSource({planId} | {boardFile, boardName})` and `current()` → `{kind, check,
  label, planId?, boardFile?}` (the check Profile shows: the plan shown, or a board file chosen in the upload);
  `mountNeeds(host)`; `profileHooks(pt)`; `importRows(table, preview)` / `importSaved(preview, {leaveTo})` (the upload
  dialog); `inspectorBlock(host, pad, plan)` → shown?, `amountText(pad)` (the pad inspector); `openCal(request)` (the
  `pad-cal` view before start), `startManual(request)` (the by-hand ladder or weighing, Calibrate's cards),
  `estimate(request)` (the preview, cached per request); `taskParts(run)`, `verdict(status)`, `jobLock(status)` (a
  job runs, decides or plans: everything else is locked), `jobActive(status)` (also while it waits for the readings),
  `needsHref()` (`#profile/needs` while the check has groups); `pendingForm(host, pending)` (readings of coupons whose
  job is gone, Save or Skip (two presses, `{skip: true}`) per coupon; either emits `coupon-reading`, on which Calibrate
  reads its pending list again); `turnAdj(steps)` (`quarter-turn`, `half-turn`, `one-turn`).
- **Fix-its** (`data-fix`): `profile-use` (arg: the group id; plans again), `profile-needs`, `pcal-open`, `pcal-area`,
  `pcal-wiped` (two-press).
- **Views and routes**: Profile › Needs a profile is `#pt-needs` (the card above the pad types, filter button
  `#pt-filter-needs`), the suggestion card is Profile's side panel at `#profile/needs/<group>`, the pad calibration
  is the panel view `pad-cal` (`?panel=pad-cal`: before start, running, the readings prompt `#pcal-ask`, the result
  `#pcal-result`). A page that finds a job of its own tab (or one waiting for readings) running opens `pad-cal` (H-P2
  in `core.js`).
- **`IL.pt`** (H-P3, `profile.js`): the hook object profile.js calls and padprofile.js fills (`profileHooks`):
  `route`, `panel`, `planStats`, `newForPackage`, `openPadType`, `selectType`, `filters`, `sectionExtras`,
  `renderExtras`, `editorExtras` (the pad-type editor's `#pt-dotsteps`, `#pt-dotsperpad`, `#pt-calinfo`),
  `sidePanelExtras` (`Calibrate automatically…` `#btn-pt-auto` next to Set up / Validate), `navSubs` (`Needs 3
  profiles`), `texts`. Without padprofile.js they are profile.js's own.
- **Hooks in other owners' files** (PC §13.9): H-P1 `core.js` readiness item 5; H-P2 `core.js` `taskParts`, the
  strip verdict (`Pad calibration · test dots 14/48 · ≈ 2:50 left`, `Waiting for you · weigh`), `lockReason` (`Locked
  while the pad calibration runs`) and the `pad-cal` view after a reload; H-P3 `profile.js` `IL.pt`; H-P4 `run.js`
  the Pad types tile goes to `needsHref()`; H-P5 `records.js` run titles `Pad calibration · blank` / `Pad calibration
  · coupons` (kinds `pad-ladder`, `pad-weigh`); H-P6 `boardimport.js` the profile column (`importRows`) and the saved
  state's actions (`importSaved`); H-P7 `padinspect.js` `#pad-insp-profile` (one notebox instead of the flag
  noteboxes and the new-type link) and the Amount row; H-P8 `boardview.js` a dot's own size from
  `plan.dots[i].diameterMm` and `update({sugg})` (`g.pv-l-sugg circle.pv-sugg`, never `circle.dot`: dot counts stay
  the plan's); H-P9 `index.html`. `calibrate.js` (owned by pc-web for this): the Pad calibration card `#cal-padcal`
  (`#pca-*`: area left, `Blank wiped` `#btn-pca-wiped`, paste loaded `#btn-pca-paste`, coupon places, pending
  coupons), the Weigh dots card `#cal-weigh` (`#wd-*`) and the guide's ladder `#btn-guide-ladder`.
- **sessionStorage** (per tab): `il.pp.source` (a board file as Profile's source), `il.pcal.req` (the calibration
  being set up before start), `il.pcal.job` (the job this tab started), `il.pcal.loaded` (job ids whose new plan this
  tab loaded: a result's `newPlanId` is loaded once).
- **Rules kept**: one filled button per screen (the Needs list's turns plain while the side panel shows a suggestion
  or the calibration); every start is a two-press button with its time (`Calibrate · ≈ 5:45`); the readings are
  typed in grams and echoed in mg (`= 78 mg of paste`); STOP stays visible in every state (`STOP stops it at once
  (F9).`).
- Tests: `PadProfileUiSimTest` (the files are served and loaded), `pad-profile.playwright.cjs` (71 checks, third in
  `run-webui-sim-test.sh`'s SIM session; `SIM_SCENE` lets it add the SIM paste, coupons and balance), fixtures for
  devproxy in `src/test/web/fixtures/padprofile/` (`devproxy.py --route-fixtures`), and the SIM acceptance
  `il_sim_acceptance.py padcal` (API level, `WEB_API.md` §6). A seeded SIM session: `il_ui_session.py start --padcal`.
- Not in this PR: the test-board ladder (PC §20 decision 8, `pc-ladder`, a follow-up) and the per-run "Skip these N
  pads" (no API yet: the Pad types card points to Setup › Settings › Refuse flagged pads).

### Since §10

`server.simulated` and `server.simulatorConfirmed` now exist (`WEB_API.md` §3.1): §10's fallback (the driver
connection) is no longer needed.

## 6. Render rules (§15)

- Build once (mount / `first`), patch on every render. Never `replaceChildren` a region that contains inputs on a
  poll; never move focus or the caret; never overwrite a focused or dirty field (track dirty per field against the
  server value; dirty fields get `.input--dirty`; the nav item shows `dirty: true`).
- Lists that change rarely: `IL.keyed(container, JSON key, build)`.
- Gate every action button each render with `IL.gate(btn, IL.lockReason(status, {...}) || ownReason, reasonEl)`.
  Every greyed control says why (`.reason` line or the button title).
- Countdowns interpolate from `IL.S.statusAt` and tick on the `tick` event; numbers use tabular figures already.
- Offline: core sets `[inert]` on every `.ws`, `#panel-top/body/foot` and the strip actions, shows the band and
  dims; you do not need to handle it, but do not cache "ready" from before.
- A render may run every 700 ms and every second: keep it cheap (no layout reads in loops, no network).

## 7. CSS vocabulary (`app.css`; use these, do not redefine them; workspace CSS goes in run/setup/profile.css)

Tokens (`:root`, `[data-theme=dark]`): surfaces `--canvas --surface --tile --sunken --line --line-2`, ink `--ink
--ink-2 --ink-3 --ink-disabled --disabled-bg`, actions `--accent --accent-hover --accent-tint --accent-ink
--on-accent`, data (paste and dots only) `--data --data-tint`, states `--ok|need|block|off|info` + `-bg -line -dot`,
energised `--live --on-live --live-tint --live-line`, two-press `--press-a --press-b --press-line --press-ink`, STOP
`--stop --stop-hover --stop-edge --on-stop`, board preview `--pv-*`, radii `--r-xs…--r-xl --r-pill`, elevation
`--e1 --e2 --e3 --scrim`, frame `--strip-h --nav-w --panel-w --stop-w`, fonts `--font-ui --font-code`.
Colour means state only; never green for "off".

| Area | Classes |
|---|---|
| Frame | `.app .strip .brand .cells .cell(--ok/need/block/off/info/plain/live/verdict) .cell__k .cell__v .long .cell-act .stop-bay .stop .stop--panel .nav .nav__item .main .ws .panel .panel__bar/__top/__scroll/__body/__foot/__lead .msg-area .msg(--block/need/info/live) .stop-band .toast` |
| Page | `.page-head .page-head__text .page-head__actions .eyebrow(--block/live/need) h1 .h1-sub h2 h3 .meta .lock-note .ws-empty` |
| Cards | `.card(--need/block) .card__head .card__title .card__sub .card__state(--ok/need/block/off/live) .card__body .card__foot .card__banner(--need/info) .grid-cards .span2 .stack .flash` |
| Glyph | `.st .st--ok/need/block/off/info/you/num/now/live/wait/checking .st--sm/xs .pulse` (via `IL.glyph`) |
| Buttons | `.btn .btn--primary .btn--hold .btn--test .btn--quiet .btn--lg .btn--sm .btn--block .btn--icon .btn--danger-ghost .btn--stack .btn__main .btn__sub .btn--press .btn-row .reason(--need/block) .refusal .link .capture .seg(--block)` |
| Tags, chips | `.tag(--next/you/now/test/off) .icode .pill-tie .chip(--ok/need/block/info/live) .plan-chip(--need/block/live/off) .recipe(--changed)` |
| Tiles | `.tiles .tile(--big) .tile__k .tile__v .n .u .tile__s .pair .bar .progress .minis .mini .mini__k/__v/__s` |
| Readiness | `.ready-head .ready-head__q .ready-head__n .segbar > i.ok/you/need/block/live/wait .ready-sub .ri(--need/block/live/checking) .ri__t .ri__d .ri__aside .ri__act .ri__note .rtiles .rt .rt--one .rt--wide .rt__t .rt__v .ilk-line .section-k .count .rule` |
| Panel parts | `.steps .step(--done/need/live/block) .step__t .step__s [aria-current=step]`, `.ilist li.<state> .ilist__t/__d/__e/__act`, `.purge(--lg) > i.used/.next`, `.notebox(--need/block/info) .gcode .stopcard .did .rsteps .rstep(--now/done/wait)` |
| Forms | `.field .field__l .unit .field__h .field__err .input(--dirty/--invalid) .row .fgrid .check .capture details.disclose` |
| Tables | `.kv(--wrap) .tbl (td.r) .ptable .headtable (td.hl)` |
| Dialog | `.scrim .dialog(--sm) .dialog__head/__keys/__body/__foot/__sum .grid2 .tick .tick__t/__d .who .keysheet` |
| Utility | `.sr .spacer .muted .dim .nowrap .mono .hide-narrow .show-narrow` |

Board picture (`board.css`): one physical palette for both themes, `--pv-a-sub --pv-a-cu --pv-a-mask --pv-a-mask-op
--pv-a-bare --pv-a-silk --pv-a-hole --pv-a-edge --pv-a-fab --pv-a-ref --pv-a-fabref --pv-a-paste` (only `--pv-a-edge`
follows the theme); overlay marks on the board `--pv-o-ap --pv-o-dot --pv-o-next --pv-o-block --pv-o-check
--pv-o-probe --pv-o-path --pv-o-path-q --pv-o-trail-q --pv-o-ghost` with the two-tone casing `--pv-o-case-d
--pv-o-case-l`. **Casing rule:** a mark drawn on the board (`.pv--art`) gets a casing (`IL.boardview.cased`, class
`.pv-case`, no state class), so it reads on mask, copper and silkscreen alike; marks off the board keep `run.css`'s
`.pv` styles. Picture modes: Off hides the art SVG; Quiet sets `.pv-art--quiet` on it and `.pv--quiet` on the
overlay; the overlay gets `.pv--art` whenever the drawing shows.

Breakpoint ≤ 1360 px: nav rail 84 px, panel 388 px, STOP 216 px. The strip drops `.long` value parts by itself
when it would run under STOP, the other cells' before the verdict's (`.cells--t1` / `--t2` / `--t2v`).

Icon sprite ids: `i-run i-setup i-profile i-cal i-records i-gauge i-chev i-plus i-minus i-camera i-refresh i-swap
i-play i-capture i-mark i-lock i-plug i-home i-wipe i-hand i-paste i-back i-needle i-close i-sun i-moon i-shield
i-list i-angle i-purge i-keyboard`; glyphs `g-check g-x g-bang g-dash g-info`.

## 8. Ids owned by core (do not create them again)

`btn-stop` (`data-testid=stop`), `skip-main`, `brand`, `heartbeat`, `heartbeat-short`, `sim-tag`, `cells`,
`cell-machine`, `pill-conn`, `pill-homed`, `btn-start`, `btn-home`, `pill-pressure`, `chip-angle`, `chip-purge`,
`pill-engine`, `nav`, `tab-btn-<ws>`, `btn-theme`, `main`, `msg-area`, `ws-<ws>`, `panel`, `panel-bar`,
`panel-back`, `panel-bar-title`, `panel-top`, `panel-scroll`, `panel-stop`, `panel-body`, `panel-foot`,
`stop-unconfirmed`, `toast`, `favicon`, `interlock-list`, `dlg-keys`, and the `pv-*` ids inside core's views.
Fix-it copies elsewhere use `data-fix="<id>"`, never a duplicate id (§16).

## 9. Working on it (SIM only)

```
python3 src/test/sim/il_ui_session.py start --jar target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar --name <you>
python3 src/test/web/devproxy.py --web src/main/resources/org/openpnp/instancelabs/web --api http://127.0.0.1:<port> --port <189xx> &
open http://127.0.0.1:<189xx>/        (files are served from disk: edit, reload; no jar rebuild)
WAIT_MS=2500 node /tmp/shots-tool/shot-live.cjs http://127.0.0.1:<189xx>/#setup out.png 1440 900
python3 src/test/sim/il_ui_session.py stop --name <you>
```
Console helpers: `IL.S`, `IL.ready()`, `IL.readiness(IL.S.status, IL.S.plan, IL.readyCtx())`, `IL.go('#run/checks')`.
Never `/dev/ttyACM*`, `/dev/serial/*`, `/dev/video*` or a real-mode OpenPnP.

## 10. Status (integration, 2026-10-28)

- All five workspaces are built on this contract; `webui-sim.playwright.cjs` drives the §16 ids (tabs
  `run|setup|profile|calibrate|records`, `press2` helper, the in-page run confirm, `#pill-pressure .cell__v` `/^ON /`,
  `Not connected`, interlocks via `?panel=interlocks`, the board surface tied to the plan then plan again).
- Every static file is checked by `ApiServerSimTest.servesTheUiAndStatus` (200, non-empty, loaded by `index.html`).
- The sim test also covers the STOP keys (Space / Esc send nothing, Enter stops), STOP NOT CONFIRMED (band in its own
  grid row, retries, "confirmed after n tries" stays up), the offline state (band, inert workspace and dialog), the board
  decision after a 20-dot test run ("Finish 140 pads left", "Wipe all, run all"); STOP answers in those checks come
  from a stub in a second page (nothing reaches OpenPnP). Final review fixes: the regulator tick expires for good on a
  psi change and back (a tick already at the new psi stays); "new seat" empties Setup's board-surface editor (Save
  waits for new points) and counts in a second tab at once, a board planned 15 mm away does the same; the run of the
  140 pads left is stopped and Records › Finish offers only its own pads (none of the test run's 20); a run confirm
  in a tab that saw no status during a test dot closes; at 1024 px the offline band sits below STOP NOT CONFIRMED.
- Board identity: a plan made again for the same seat (no "Next board" since) is the same board, its dispensed dots
  carry over; a part-done board shows the board decision (`partialDecision`). As built (rc1 review), the server keeps
  it, so every tab and browser sees the same board: `plan.pasted` (`{board, nextBoard, sinceMs, dots, pads: {padId:
  dots laid}}` of the plan's seat) and `status.run.boardNumber`; `trackDone` takes each pad's dots laid in path order,
  with the live dots of this board's run (`runOfThisBoard`); `boardNumber()` is the seat's board, else the next one;
  `Next board` posts `/api/board/next`; a change elsewhere (`status.dispense.pasteVersion`) reads the plan again. A
  run that lays pads with paste needs `I wiped this board` (`#run-tick-wiped`) and posts `{wiped: true}`. The old
  per-tab keys `il.run.done`, `il.run.alias` and `il.run.boardStarts` are gone.
- Optional API additions of §17 in place: `GET /api/plans` (plan restore in a fresh browser) and `run.pressureOnAtMs`
  (pressure on-time of a run, also after a reload). Not added (fallbacks in use): `run.checkDone/checkPlanned` (camera
  tour progress is estimated, labelled `≈`), `server.simulated` (inferred from the driver connection).

## 11. Register board, home at run, Z check (AUTO_REGISTER_DESIGN.md §8, 2026-10-29)

- **Run › Register** (key and route stay `probe`): `#reg-correction` (what the plan's dots use: `fiducial check 13:05`,
  `… (before the last Next board)`, `no fiducial correction`), `#reg-preview` (idle: the seat it expects and the spots),
  `ol.phases#reg-phases` (live), `table#reg-fids` and `table#reg-probes` (expected / found, Z and state; a refused point
  gets a row with its reason in words), `div.ri#reg-result` (the result card: its first sentence is machine-readable,
  `Registered 13:05: …`, `Same seat 13:12: …`, `Re-seated …`, `Not registered: …`, `Registered 13:05, but the probe
  failed: …`, `Stopped 13:06 during …`), `#probe-tiles` (the points by hand, else the tied surface), and
  `details#probe-by-hand` (today's guide, targets and untied note, ids unchanged). The foot is built once with both sets:
  `#register-note`, `#btn-register` (two-press, `Register board · ≈ M:SS` from `registrationTargets.estimatedSeconds`
  and the live home plan; `Registering… · ≈ 0:41 left` while it runs), `#btn-reg-continue` (`Checks ›` after a good
  result), `#register-why`; or the by-hand `#probe-live`, `#probe-warn`, `#btn-probe-add`, `#btn-probe-save`,
  `#probe-why`. `R.byHand` (panel state) chooses. `#btn-reg-again`, `#btn-reg-probe` (`probe: always`) sit in the card.
- **Run state:** `R.regResultRunId` (sessionStorage `il.run.regResult`; cleared by `Checks ›`, a plan from the Board
  step, another task), `R.regLoaded` (the run ids whose `nextPlanId` this tab loaded: a reload or a second browser
  catches up from `status.run`), `R.regDetail` (the last live detail, for the stopped texts). `autoStep` returns
  `probe` while the plan's registration runs or a result is shown. The by-hand targets are the engine's
  (`plan.registrationTargets.probes`); the bounding-box targets stay as the fallback. The plan's preview is computed
  again (`GET /api/plan/{id}/registration-targets`) when the machine is stopped, started or homed.
- **Hero overlay:** `bv.update({probe, fids})` on the Register step (idle, live, result). `g.pv-l-fids` (boardview.js
  `drawFids`, cased with the §5.3 overlay tokens): a dashed circle of the search radius at the expected position, a
  cross there, a filled dot at the found one with a short leg; the name. Probe targets carry their alternates as small
  rings; a refused target is struck through. The view keeps the search circles in sight.
- **Home and Z:** the machine item is a note that counts ready (`state: 'info'`) when the engine homes first
  (`status.machine.home.policy`): `homes first` / `homes first, checks Z`; the verdict reads `Ready to run · homes first
  (≈ 25 s)`. `need` only when a STOP left N1 low (`home.n1Low`). Homed with Z data that predate the home:
  `checks Z` with the `check-z` fix. The status I4 "measured before the home" no longer shows as a Place heights
  blocker (a board run checks Z itself); "no point measured before the home" and "Z moved" (status or plan) are the
  extra blocker `I4-z` (`Z check`, fix: Needle Z frame), never the surface item (Register board would not clear them). Recovery's home step is done without a home unless N1 is low. The run confirm
  shows `#run-confirm-home`; the Run step's phases add Home and Z check rows from `run.detail`.
- **Board step:** `Board top Z (approximate, ±1 mm)`, prefilled when empty (`#board-z-note` says from where), the
  plan's frame check under it; copy names Register board. Another board chosen there (picked, or "Use this board"
  after an upload): the main column shows that board before planning (`R.pending` keeps the plan, which the other
  steps still show) until Plan.
- **Setup › Probe the blank** (`probe-blank` view): automatic (`ol.phases#blank-phases`, `table#blank-targets`,
  `div.ri#blank-result`: `Saved 13:20: 6 points, worst 0.012 mm, covers 15 of 15 spots.` / `Not saved: …` / `Stopped …:
  nothing was saved.`), the foot `#blank-note`, `#btn-blank-auto` (two-press), `#blank-why`; `details#blank-by-hand`
  keeps today's table with `#btn-blank-add` / `#btn-blank-save` in the foot. Settings: `#set-homebefore`
  (`homeBeforeRun`). The JSON editor never sends `boardRegistrations` or `newSeatAtMs` (engine stamps).
- **Probe only** (F10, fix round 2): a plan whose `registrationTargets.probeOnly` is true (a board without fiducials, no
  registration) turns `#btn-register` into `Probe only (X/Y as planned) · ≈ M:SS` (`data-mode="probe-only"`, two-press
  `probe only`, `POST …/register {probe: 'always'}`; `Probing… · ≈ 0:41 left` while it runs) with `#register-note`
  `This board has no fiducials: N1 probes it where it is planned; its X/Y are not checked.` and the N1 spots; Do it by
  hand stays. The board's missing fiducials lock nothing then (`registerWhy(s, true)`); the live phases drop the
  fiducial and fit rows. The result card: `Probed 13:05: no fiducials: probed only, X/Y as planned; 5 points, worst
  0.012 mm; planned again.` with `Probe again` (`#btn-reg-probe`), `Not probed: …`, or `Stopped … during probe point k
  of n: nothing was saved.`. The readiness item, the I4 seat fixes and the Z-moved step offer `Probe only (X/Y as
  planned)` (`register-probe-only`) instead of `Register board` for such a plan; the item's short name and value say
  the same action (`probe board`, `probe`, blocked `probe again`; else `register board`).
- **Records:** `board-registration` → `Registration · <board>`, `surface-probe` → `Surface probe · <name>`, `z-check`.
- **Board import hooks** (BOARD_IMPORT_DESIGN.md §5.1.1, no-ops until `boardflow.js` loads): H1 `mountBoardStep` in the
  Board step, H2 `onPad` / `onPart` of the hero view, H3 `hero(bv, …)` before the empty-state switch (the SVG and the
  stage bar show with a plan or a drawing), H4 `recordsMap(bv, plan)` after the Records map's `setPlan`, H5 the
  replaced Records map view is destroyed.

## 12. Needle touch-off (fix round 2: F9, F14, 2026-10-30)

- `#to-pitch` (`Pitch`, mm, placeholder `1.6`, `at least the dot + 0.4 mm`) is sent as `pitchMm` when typed; empty, the
  engine takes 1.6 mm (more for a dot above half a turn). `#to-plan` ends `· 1.6 mm apart` (the typed pitch, or `the
  dot + 0.4 mm apart (at least 1.6 mm)` above half a turn); the started toast names the 202's `pitchMm`. The engine's
  400 for a pitch with no room between the dots (`the touch-off pitch 1.00 mm is too small for these dots: …`) is
  `#to-pitch-err` (a block toast too, no refusal box) until the pitch or the dose changes; `#to-reason` then reads `The
  pitch is too small for these dots: see the Pitch field.`.
- The result table (`#to-result` and Records › Touch-offs): `analysis.dotStates[i]` `unmeasured` reads `not measured`
  with `dotNotes[i]` in the dim third column and as the cell's title (Records: the title); `no dot` only for `none` (or
  rows without `dotStates`). `IL.pcr.touchOffDot(analysis, i)` → `{text, note}`. A run that recorded nothing shows its
  message word for word under `Finished hh:mm · no contact found: nothing recorded` (`#to-live`, and the `cal` panel);
  the older row below is then `Last recorded touch-off · …`.
