# Paste dispenser web UI: final design and implementation spec

Status: final design, 2026-10-28. Audience: the engineer who rebuilds
`src/main/resources/org/openpnp/instancelabs/web/{index.html, app.css, app.js}` (worktree
`~/instancelabs/wt/bench-sync-uirevamp`, branch `il/bench-sync-uirevamp`).

Updated 2026-10-29 for what is built on `il/bench-sync-autoreg` and its web branches: the board drawing, path
preview and pad inspector (merged), Register board, the blank probe, home at run and the Z check (`il/ar-webui`,
`AUTO_REGISTER_DESIGN.md` §8), and the KiCad upload, board thumbnails and "Camera is on FID1" (`il/bi-web-flow`,
`BOARD_IMPORT_DESIGN.md` §5). Those sections say "as built"; where they differ from the mockups, the code is right.
Profile › Needs a profile, the suggestion card, the pad calibration view and Calibrate › Pad calibration
(`PROFILE_CALIBRATION_DESIGN.md` §13, slice pc-web, `padprofile.js`) are built: §8.3, §8.4, §9.1 item 5, §13, §16
and §17 say "as built (pc-web)", written from the code on `il/pc-integration`. Not in this PR: the test-board ladder
(PC §20 decision 8, `pc-ladder`, a follow-up) and the per-run "Skip these N pads" (decision 6; no API yet).

Mockups (self-contained, no script, light theme) and their screenshots:

| Mockup | State shown | Screenshots (`shots/`) |
|---|---|---|
| `mockup-run.html` | Board 3 planned. 8 of 9 checks ready; 2 pads had paste (needs you, NEXT). | `mockup-run-1440.png`, `mockup-run-1280.png` |
| `mockup-run-confirm.html` | The in-page run confirm over the Run screen. STOP stays live. | `mockup-run-confirm-1440.png`, `-1280.png` |
| `mockup-run-progress.html` | Board running, 96 of 160 dots, pressure ON (energised). | `mockup-run-progress-1440.png`, `-1280.png` |
| `mockup-run-stopped.html` | After STOP at dot 61: recovery checklist, board decision locked. | `mockup-run-stopped-1440.png`, `-1280.png` |
| `mockup-setup.html` | Setup after a STOP and reconnect: I11 question, purge row reset pending (two-press). | `mockup-setup-1440.png`, `-1280.png`, `mockup-setup-1440-tall.png` (all cards) |

Sources: `src/final.css` (the design system; port it to `app.css`), `src/build.py` (mockup generator),
`src/board.py` (to-scale board SVG, same class names as the live UI). Rebuild and check:
`tools/shots.sh` (builds and screenshots) and `node tools/check.cjs <final dir>` (no clipped text, no strip
cell under STOP, dialog and panel footers inside the viewport, at 1440×900 and 1280×800).

---

## 0. What this design is

The winner is **C "Calm Studio"** (2 of 3 judges; highest total 42/41/39): a light, calm UI in the Laser XY
lineage with a fixed frame (status strip, left navigation, context panel) and a to-scale board as the hero of
the production screen. It is extended with the best ideas of A "Machine Console" and B "Guided readiness":

- From A: a STOP block that fills the whole strip height and names the motion stop; a state strip that
  answers Pressure (with live on-time and auto-off), Start angle and Purge row on every screen; "What STOP did"
  with each G-code in plain words; the session timeline under the board; the start-angle question in one click
  from anywhere; tab title showing the run.
- From B: one pure readiness model of 9 checks ("Ready to dispense? 8 of 9", fixed-order segment bar, NEXT tag);
  checks the machine senses kept apart from things only the operator can confirm ("you, 13:04"); a second,
  identical STOP in the context panel while anything is energised; the heartbeat in the strip; the
  "hands clear" tick.
- Every must-fix from the three judges is resolved (table in §19).

Principles, in order: STOP always reachable; never claim "ready" when a board cannot run; colour means state
only; the per-board loop on one screen; every greyed control says why; plain words with units.

## 1. Hard constraints (from the brief, binding)

- Simulation only for development and QA. Never open serial ports or cameras, never start OpenPnP in real mode.
- CSP `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:;
  connect-src 'self'`: no inline `<script>`, no `on*=` attributes, no CDN, no web fonts. System font stacks only.
  Static files are flat names matching `[A-Za-z0-9_-]+\.(html|js|css|svg|png)` under `/static/`. Keep
  `app.js`, `app.css`, `favicon.svg` (ApiServerSimTest fetches them); new files must be added to that test.
- Every existing function stays reachable (§10 is the complete map).
- STOP visible on every screen and scroll position, never in a menu, never disabled, keyboard reachable.
  Safety texts plain and exact: `M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B`.
- The run confirm names the profile's regulator pressure: `Set the regulator to 18 psi (profile 18 psi half turn)`.
- Polling `/api/status` every 0.7 s causes no layout jank and never steals focus or overwrites edits.
- No native `confirm()`, `alert()`, `prompt()` or `dialog.showModal()` anywhere (they block STOP).

---

## 2. Frame and layout

```
┌─────────────┬──────────────────────────────────────────────────────────────┬──┬────────────┐
│ brand       │ MACHINE │ PRESSURE·SET 18 PSI │ START ANGLE │ PURGE ROW │ … BOARD RUN ›│  │   STOP     │ strip 80 px
│ ● 0.4 s ago │ cells 56 px tall, glyph + eyebrow + value                    │20│ F9 motion… │ (fixed)
├─────────────┼────────────────────────────────────────────┬─────────────────┴──┴────────────┤
│ Run    (1)  │ main column (scrolls by itself)            │ context panel (scrolls by itself │
│ Setup       │  page head: eyebrow · h1 · meta · actions  │  between a pinned header and a   │
│ Profile     │  cards / hero                              │  pinned footer)                  │
│ Calibrate   │                                            │                                  │
│ Records     │                                            │                                  │
│ theme, keys │                                            │                                  │
└─────────────┴────────────────────────────────────────────┴──────────────────────────────────┘
  nav 212 px (84 px rail ≤ 1360)                              panel 400 px (388 px ≤ 1360)
```

- CSS grid on `.app`: columns `var(--nav-w) minmax(0,1fr) var(--panel-w)`, rows `var(--strip-h) minmax(0,1fr)`,
  `height: 100vh`. The page itself never scrolls; `.main`, `.panel__scroll` and the nav scroll on their own. So
  STOP and the strip stay put at every scroll position without `position: sticky` tricks.
- Breakpoint ≤ 1360 px: nav becomes an 84 px icon rail (label under the icon, badge on the corner, no sublines,
  no foot); brand shows the mark and the heartbeat seconds only; panel 388 px; STOP 216 × 80 px; recipe token
  drops the profile-name segment. Minimum supported viewport 1280 × 800. Below 1100 px (not a target): the panel
  moves under the main column; STOP stays in the strip.
- Workspaces without a context-panel need (none today) still keep the panel: every workspace has a default
  panel view (§7).

## 3. Routing (hash routes)

Grammar: `#<workspace>[/<section>[/<id>]][?panel=<view>]`. One `hashchange` handler; `history.replaceState` for
in-page state changes that should not create history entries (step changes inside Run), `pushState` for
workspace changes.

| Hash | Shows |
|---|---|
| `#run` | Run; context panel = the step that matters now (Board if no plan; Probe if I4 board surface needs this seat; Checks otherwise; Run while a board runs; Result after it; Recovery after STOP/ABORTED/ERROR) |
| `#run/board`, `#run/probe`, `#run/checks`, `#run/run`, `#run/result` | Run with that step's panel |
| `#setup`, `#setup/<card>` | Setup, scrolled to the card (`needle`, `surfaces`, `purge`, `pressure`, `places`, `camera`, `angle`, `settings`) with a 1.5 s focus outline on it |
| `#profile`, `#profile/pad-types/<key>` | Profile; the pad-type side panel for `<key>` |
| `#calibrate`, `#calibrate/<card>` | Calibrate (`touch-off`, `angle`, `shots`, `dots`, `ladder`) |
| `#records`, `#records/<tab>[/<id>]` | Records (`boards`, `activity`, `calibrations`, `touch-offs`); `<id>` opens the detail panel |
| `…?panel=interlocks\|pressure\|machine\|camera\|activity\|angle\|purge` | Any workspace with that context panel view pushed (back arrow returns to the default) |
| Legacy `#dispense` | redirects to `#setup`; `#profiles` → `#profile`; `#calibrate` unchanged |

- `Alt+1…5` = Run, Setup, Profile, Calibrate, Records.
- Navigation is never blocked: unsaved edits survive a workspace change (forms keep their dirty state in memory;
  §15) and the nav item shows a dot "unsaved".
- The Recovery panel is driven by state, not by the hash: it overrides the Run panel and appears as a pushed
  panel on other workspaces (with a back arrow) until the machine is ready again.

## 4. Status strip (every screen)

Left to right: brand (with heartbeat) · 4 state cells · (gap) · Board run verdict cell · 20 px clear bay · STOP.
Each cell is a `<button>` 56 px tall: status glyph (shape + colour), an eyebrow key (10.5 px caps), a value
(15.5 px / 650, `white-space: nowrap`). Cells never truncate: values have deliberate short forms; the layout is
verified at 1280 and 1440 for the widest states (stopped, running). Clicking a cell opens its panel view. *As built
(rc1 review):* the page measures the strip whenever the room of the cells changes (not only the window) and shortens
in tiers until the verdict fits whole, the other cells first: t1 drops their level-1 long parts (`Connected · `,
` · vented`, ` · wipe`), t2 all their long parts (` · set 18 psi`), t2v the verdict's own (` · homes first
(≈ 25 s)`, a task's progress, the stopped verdict's time: `Stopped`; final gate: before, t2 dropped them with the
others', so at 1440 px a running pad calibration read `Pad calibration` beside 300 px of free strip), t3 the pressure
cell while it reads a plain `Off`, t4 the other cells' keys; the stopped state is checked at 1440, 1280 and 1024 (at
1440 it keeps its time), a running pad calibration at 1440 (it keeps its step).
The cells container is `aria-live="polite"`; safety changes (STOP result, vent_failed, offline) are announced
by `role="alert"` regions instead.

| Cell (id) | Key | Value and state (glyph · tint) | Click opens |
|---|---|---|---|
| Brand + heartbeat (`#heartbeat`) | — | `● OpenPnP 0.4 s ago` (seconds since the last good `/api/status`, updated every 250 ms). Grey dot after 1.5 s, red + `No answer 3 s` after 3 s (offline state, §8.7) | Activity panel |
| Machine (`#pill-conn` + `#pill-homed` spans inside) | MACHINE | `Connected · Homed` ok · `Connected · Not homed` needs-you + inline **Home** (`#btn-home`) · `Homing…` energised (from `machine.busy` after a Home) · `Not connected` blocked + inline **Connect** (`#btn-start`); `#pill-homed` is kept in the DOM (visually hidden) reading `Not homed` | Machine panel |
| Pressure (`#pill-pressure`) | PRESSURE · SET 18 PSI (profile psi) | `Off · vented` off (grey, never green) · `Switching on…` / `Switching off…` energised · `ON 1:15 · auto-off 10:00` energised (hold or run; on-time ticks each second, limit = `maxContinuousOnTimeS`) · `NOT off` blocked (vent_failed) | Pressure panel (opens by itself on vent_failed) |
| Start angle (`#chip-angle`) | START ANGLE | `298.0°` ok · `Confirm` needs-you (I11 reconnect) · `Not set` blocked · `Whole turns` off (I11 off) | Start-angle question in place (§8.1 popover) |
| Purge row (`#chip-purge`) | PURGE ROW | Always spots **free of total**: `9 of 15` ok when `spotsLeft ≥ perBoard`; `7 of 15 · wipe` needs-you when fewer than one board's purge dots are free; `Not set` blocked | Purge panel (strip map + two-press reset) |
| Board run verdict (`#pill-engine`) | BOARD RUN | Idle: `Ready to run` ok · `8 of 9 · wipe 2 pads` needs-you (count + the NEXT item, short) · `Blocked · start angle` blocked. Task running: `96/160 · 0:44 left` energised, `Pad check 57/160` energised, `Prime` / `Test dot` / `Touch-off` energised. After STOP/ABORTED/ERROR: `Stopped 14:19` blocked. Engine down: `Dispenser unavailable` blocked | Run › Checks (or Recovery / Run step) |
| Inline actions | — | `Connect` (`#btn-start`) only when not connected; `Home` (`#btn-home`) only when connected and not homed, and never while a task runs or pressure is held. They appear right after the Machine cell | — |

The verdict never says "Ready" unless §9 says a board can run now. The strip shows no other "ready" wording.

## 5. STOP

**Look.** `#btn-stop` (`data-testid="stop"`, `aria-keyshortcuts="F9"`): a solid `--stop` block filling the full
strip height (80 px) at the right edge, 232 px wide (216 px at ≤ 1360), bottom-left radius 14 px, a 2 px white
inner ring 5 px inside the edge, a 2 px `--stop-edge` shadow below. Word `STOP` 30 px / 800 / +0.16 em; under it
a keycap `F9` and `motion · vent · screw off` (12.5 px / 700). No glyph (a square reads as a checkbox). Nothing
else within 20 px (`.stop-bay` padding and a hairline). Hover darkens; focus ring is a 3 px ink outline inset
12 px (visible on red). It is never disabled, faded, hidden, covered or moved.

**Second STOP.** While anything is energised (board run, clean-check tour, prime, test dot, touch-off, ladder,
shots, homing, pressure hold) the context panel shows an identical copy at the top of its scroll area
(`.stop--panel`, `[data-stop-copy]`, 64 px tall, full panel width, same label, same handler, same F9 hint). It is
placed at the panel top, never where the Run button or a dialog's commit button sits, so a double-click on
"Run" can never land on it.

**Title / accessible description (exact):** `Stops everything now. Sends M410, M107 P3, M107 P2, M107 P1, M107 P0,
M18 B (quick stop, dispenser valve and pump off, pick valve and pump off, screw motor off), vents a pressure hold,
then stops OpenPnP (the machine must be connected and homed again). Key: F9.`

**Behaviour.**
1. Press (pointer `click`, `Enter` on the focused button, or `F9` anywhere): the button reads `STOPPING…` at once,
   the strip gets a 2 px red bottom border, `POST /api/stop` goes out with a 2 s timeout (`AbortController`);
   OpenPnP answers within 1.5 s, also while a dwell holds the controller (the machine reads off a little later).
   Repeated presses are allowed and harmless (each sends again).
2. Answer received: the toast `STOPPED hh:mm:ss: <server actions, joined with "; ">` (kept for the Playwright
   test, also written to Activity), the button returns to `STOP`, and the **Recovery** state starts (§8.1.8).
3. No answer in 2 s, or an error: a full-width red band directly under the strip, `role="alert"`, id
   `#stop-unconfirmed`: `STOP NOT CONFIRMED: press OpenPnP's Stop button or switch the machine off at its power
   switch now. Retrying every second.` STOP is re-sent every 1 s until one answer arrives; then the band turns into
   `STOP confirmed hh:mm:ss after n tries` for 10 s. Nothing else is ever re-sent automatically.
4. Offline before the press (§8.7): STOP stays clickable and still posts; the offline band already gives the
   physical fallback.
5. STOP is first in the tab order (`index.html` puts the STOP bay first in the DOM inside the strip and uses CSS
   `order` to place it right). A skip link "Skip to main" follows it.

**Space never stops.** The STOP buttons cancel the default action of `Space` (`keydown` and `keyup` with
`preventDefault`), so a stray Space while STOP has focus (it is the first Tab stop) does nothing. `Enter`
activates STOP; `F9` always stops. `Esc` never stops (it closes things, §14). Pointer click always works.

## 6. Navigation (left)

`<nav class="nav tabs" role="tablist">` with five `<a role="tab" data-tab="…" aria-selected>` items:
`run`, `setup`, `profile`, `calibrate`, `records`. Each item: icon, label, a live subline in plain words, and a
badge.

| Item | Subline examples (never bare I-codes) | Badge |
|---|---|---|
| Run | `Wipe 2 pads` · `Ready: board 4` · `96 of 160` (energised colour) · `Stopped at dot 61` (red) · `No plan yet` | count of needs-you items (amber), `96` while running (energised), `!` after STOP (red) |
| Setup | `All 11 steps ready` · `2 need you` · `Locked · running` | needs-you count |
| Profile | `18 psi half turn` · `Confirm angle` · `Unsaved changes` | — |
| Calibrate | `Touch-off 12:51` · `Locked · running` | — |
| Records | `2 boards today` · `Board 4 stopped` | — |

Foot (≥ 1361 px only): Light/Dark switch and the key hints `F9 STOP`, `Alt 1–5 workspaces`, `Esc closes a dialog`.

## 7. Context panel

One `<aside id="panel">` whose content is a **view**. Each workspace has a default view; cells, steps and
fix-it links push another view (`?panel=`) with a back arrow. Swapping views never changes the main column's
width (no reflow). Views with an action keep it in a pinned `.panel__foot`.

| View (`data-view`) | Default for | Content |
|---|---|---|
| `board` | Run with no plan | §8.1.2 |
| `probe` | Run when this seat needs its surface | §8.1.3 |
| `checks` | Run with a plan | §8.1.4 |
| `run` | Run while a task runs | §8.1.6 |
| `result` | Run after a board | §8.1.7 |
| `recovery` | forced after STOP / ABORTED / ERROR | §8.1.8 |
| `head` | Setup | live N1 / N2 needle / camera X·Y·Z table (fixed layout, tabular numbers), the line `Jog in OpenPnP's window: this page cannot move the head. Capture buttons take these values.`, then the Bring-up order (§8.2) |
| `pt` | Profile | default: `What this profile does` and the pad-type gate; with a selected pad type: its side panel (§8.3) |
| `cal` | Calibrate | progress and result of the running procedure; head position |
| `record` | Records | detail of the selected row |
| `interlocks` | — (from verdict, "All 13") | `#interlock-list`: MACHINE, CONFIG, I1–I11 rows with state class `ok/blocked/warning/off/info`, glyph, plain name, the engine text verbatim, fix-it button(s); I1 shows `detail.lines` against `detail.expected`; I10 shows the overlay rule of §9.3 |
| `pressure` | — (Pressure cell) | hold state (every state incl. `vent_failed` and `lastError`), target psi and window, needle distance to the waste, countdown, keepalive armed and last ping, last event, Pressure on/off |
| `machine` | — (Machine cell) | connected since, Connect, Home again (two-press), I1 and I3 detail, drivers, live positions |
| `angle` | — (Start angle cell) | the I11 question with its two equal answers, angle, alternating start angles, set time, controller connected time |
| `purge` | — (Purge cell) | purge strip map, spots free/used, next spot X/Y, last wiped, two-press reset, link to Setup › Purge row |
| `camera` | — (camera buttons) | snapshot with overlays (camera X/Y, frame time, clean-check box, pad id and bright share) |
| `activity` | — (heartbeat) | `GET /api/events?limit=50` newest first + every toast and refusal of this page |

---

## 8. Workspaces

### 8.1 Run (production cockpit) — `mockup-run*.html`

**Main column.** Page head: eyebrow `Board 3 this session · board file · top side` (job boards: `job board ·
fiducials checked 13:01`); h1 = board name (wraps, never truncated); meta `Seat X 120.00 · Y 120.00 mm · Rot 0.0°`,
`Board top Z 5.62 mm`, and when it was entered (`entered 13:02 for this seat`); actions `Change board`
(`#btn-board-change`, opens the Board step) and `Plan again` (`#btn-plan-again`).

Hero card:
- Head: plan chip `#plan-chip` and the recipe token. Plan chip states: `Plan 7f3c · current` (ok) ·
  `Plan 7f3c · stale: profile changed 13:20` (needs-you; names what changed: profile, surface, start angle,
  needle, board registration) · `Plan forgotten (OpenPnP restarted): plan again` (needs-you, on 404) ·
  `Plan a91e · needs this seat's surface` (needs-you) · `Dispensing · next D9.1` (energised) · `Ended by STOP ·
  61 of 160 dots` (blocked). The recipe token turns amber with `Changed since planning: plan again` when the
  plan's profile fingerprint differs from the live one.
- Stage: `#plan-svg` to scale (mm viewBox, board +Y up, `vector-effect: non-scaling-stroke`), drawn once per plan;
  polls only toggle classes. Classes kept for tests: `rect.pad`, `rect.pad.dirty`, `circle.dot`, `circle.dot.done`;
  added: `rect.pad.pasted`, `rect.pad.check`, `.pv-next`, `.pv-first`, `.pv-path`, `.pv-path--done`,
  `.pv-hull`, `.pv-probe`. Dirty pads get a dashed ring and a callout `R17.1 0.41` (pad id + bright share). The
  view fit (`Dots`) includes fiducials plus 2 mm so nothing is clipped. A click on a pad opens the Camera panel for
  it (nothing moves).
  **As built (board import):** the board's drawing lies under the dots in a second SVG (`g.pv-l-art`: substrate,
  copper, mask, exposed copper, silkscreen, holes, drawn once per art id) with the picture control `#pv-picture`
  (`Full` · `Quiet` (default, remembered in `localStorage['il.pv.picture']`: pads, holes and the outline, so the dots
  stand out) · `Off` (the drawing without art)). A click on a pad or a part opens the **pad inspector** (panel view
  `pad`) instead of the camera; `#btn-pad-inspect` opens the first pad in path order. The **path preview**
  (`#pv-play`) expands the row `#run-preview` (`#pv-scrub`, `#pv-speed` 1× · 4× · 16×, `#pv-readout`, `#pv-reset`):
  a ghost needle moves dot to dot on the plan's clock (`dots[i].ta / te`) and the dots appear as they would be
  dispensed; nothing moves. Register board's overlay (`g.pv-l-fids`): the search circle at each expected fiducial,
  a cross there, a filled dot at the found one; probe targets with their alternates as small rings, a refused
  target struck through.
- Stage bar: legend that doubles as the layer switch (only states present, with counts), `Dots | Whole board`
  (running: `Follow needle | Dots`), zoom − / +.
- While running: the session **timeline** (`#run-timeline`) between stage and stage bar: proportional segments
  `Camera check · 160 pads · 3:08` | pump/valve/purge/crossing | `96 dots · 1:04` | `≈ 0:44 left`.
- Footer tiles `#plan-summary` (idle): **Dots** `160` `on 160 pads` · **Board time** `5 min 16 s`
  `incl. 3:10 pad check` (the camera tour is included whenever I10 is on) · **DPH estimate** `5,410` dots only,
  `4,560` overall, `bench 5,490` · **Last board** (`#run-last`, from `GET /api/runs`, never replaced by a prime,
  test dot or clean check) `5,388 DPH` / `dots only · board 2, 12:58 · finished, all 160 dots`.
  Running (`#run-live`): **Dots** `96 of 160` 44 px + `#run-bar` · **Time left** `0:44` /
  `dispensing 1:15 · whole board ≈ 5:07 incl. pad check` · **Live DPH** `5,362` dots only, `4,608` overall so
  far / `estimate 5,410 · 4,560`, `bench 5,490`.
  After STOP: the board decision footer (§8.1.8).

**Context panel steps.** A five-step stepper on top: `Board → Probe → Checks → Run → Result`, each with a glyph
and a subline (`ftp.board`, `tied 13:05`, `1 needs you`, `≈ 5 min`, `board 2 ok`). Choosing a step changes the
panel only; the board stays in view. This replaces the ~5 tab switches per board.

#### 8.1.1 Run states (summary)

| State | Main | Panel |
|---|---|---|
| No board chosen | hero shows the picker hint `Choose a board on the right` and a faint outline | Board step |
| Board chosen, no plan | outline, `Plan to see the dots`; as built: the board's drawing at the form's seat (Full), the live top camera and a 5 mm scale bar (`#board-preplan`) | Board step with Plan primary |
| Planned, this seat not registered / tie missing | board + expected fiducials and probe targets | Register step |
| Registering | live fiducials and probe points on the board | Register step (phases, tables) |
| Planned, checks | `mockup-run.html` | Checks step |
| Confirm open | dialog over everything except the strip | unchanged under the scrim |
| Running | `mockup-run-progress.html`; plan-changing controls locked | Run step |
| Finished | done map, Result tiles | Result step |
| Stopped / aborted / error | `mockup-run-stopped.html` | Recovery |
| Refused (409 at start, or REFUSED task) | inline refusal under Run + card banner | Checks with the refusing items on top |

#### 8.1.2 Board step (`data-view=board`)

- `Board` select `#board-select` (job boards first with `fiducials checked hh:mm` or `run a fiducial check in
  OpenPnP`; board files; `Other board file…` → `#board-file`), **Refresh** `#btn-board-refresh` (reloads
  `GET /api/boards`).
- Position (file boards): `#board-x`, `#board-y`, `#board-z` (N1 Z touching the board top, required), `#board-rot`,
  each a 46 px field with its unit on the label line. Capture buttons that name the live value:
  `Use camera X/Y 387.06, 165.17` (`#btn-board-use-cam`), `Use live N1 Z 5.62` (`#btn-board-use-n1z`). Job boards:
  read-only with `(fiducial-corrected)`.
- Remembered values (`localStorage il.boardloc.<file>`) show `entered 13:02` and the question
  **`Was the board re-seated or changed since then?`** `Yes: new seat` (clears the surface tie expectation and
  opens Probe after planning) / `No, same seat`.
- Primary `Plan` (`#btn-plan`), 60 px. Plan errors appear under it (inline refusal, §11).
- **As built (Register board):** `#board-z` is `Board top Z (approximate, ±1 mm)`, prefilled when empty from the board
  surface probed for it or the needle frame (`#board-z-note` says from where); Register board measures it. The plan's
  frame check shows under it. The re-seat answer is the engine's new-seat flag (`POST /api/board/new-seat`), shared by
  every browser: `New seat: after planning, Register board checks the fiducials and probes this seat.`
- **As built (board import, `boardflow.js`):** `Upload KiCad board…` (`#btn-board-upload`) opens the upload dialog
  (§11); `#board-thumbs`, one row of board pictures (a click chooses that board); `#board-imported` says where the
  drawing comes from; **`Camera is on FID1`** (`#btn-board-use-fid`, next to `Use camera X/Y`) sets the origin from
  the top camera centred on the board's first fiducial: origin = camera − R(rotation)·(FID1's board X/Y). After a save,
  `Use this board` chooses it here.

#### 8.1.3 Register step (`data-view=probe`; was "Probe")

**As built** (`il/ar-webui`, `AUTO_REGISTER_DESIGN.md` §8.1). The step's key and route stay `probe`; the stepper
reads `Board → Register → Checks → Run → Result`.

- `#reg-correction`: what the plan's dots use (`fiducial check 13:05`, `… (before the last Next board)`, `no fiducial
  correction`). `#reg-preview` (idle): the seat it expects and the spots, `Fiducials · searched within 8 mm`, `N1
  vacuum touch, pressure off`, the home line when Register homes first.
- Live: `ol.phases#reg-phases` (Home · Fiducials · Fit the board to the fiducials · Probe or Same-seat check · Plan
  again), `table#reg-fids` (expected, found, moved) and `table#reg-probes` (target, Z, state; a refused point has a
  row with its reason in words).
- `div.ri#reg-result`, first sentence machine-readable: `Registered 13:05: …` · `Same seat 13:12: …` · `Re-seated …` ·
  `Not registered: …` · `Registered 13:05, but the probe failed: …` · `Stopped 13:06 during …`; then
  `#btn-reg-again` and `#btn-reg-probe` (`probe: always`). Refusals carry fix-its (`Look with the camera`, `Change
  board`, `Plan without fiducial correction`, `Register again`, `Do it by hand`).
- Foot: `#register-note`, **`#btn-register`** (two-press, `Register board · ≈ 1:45` from the plan's
  `registrationTargets.estimatedSeconds` and the live home plan; `Registering… · ≈ 0:41 left` while it runs),
  `#btn-reg-continue` (`Checks ›` after a good result), `#register-why`. `STOP (F9) stops it at once: N1 stays where it
  stopped, its vacuum off; Home raises it first.`
- `details#probe-by-hand` keeps the by-hand probe below (targets from `plan.registrationTargets.probes`, the
  bounding-box targets as the fallback; ids unchanged: `#probe-targets`, `#btn-probe-add`, `#btn-probe-save`), with
  its own foot. Without a top camera: `No top camera in OpenPnP: do it by hand`.

The design text below is the by-hand probe as first specified (C's `run-probe.html`, with fixes):
- Banner `New seat: probing from empty` (never appends to the last seat's points); link `Same seat? Keep them`.
- Targets table in machine mm computed from the plan (corners and centre of the dot hull, inset so the
  margin-inflated hull covers every dot): `#probe-targets` rows `n · X · Y · measured Z · Delete`, next target
  highlighted.
- Hero overlay: probe targets (filled when probed, dashed when not), the probed area drawn **inflated by the
  3.5 mm margin** (so "covers every dot" is visibly true), live N1 crosshair `N1 now`.
- Tiles: points `3 of 5`, worst residual (limit 0.04 mm), tilt so far, Z against the frame (limit ±1.00 mm).
- Guidance text computed from the same live values shown next to it: `Jog N1 to target 4 in OpenPnP's window and
  lower it until the tip touches. N1 is 0.44 mm from target 4.` (distance = hypot of the live N1 X/Y minus the
  target X/Y; never a separately rounded figure).
- Pinned foot: N1 live X/Y/Z and `Add point 4 · N1 tip touching` (`#btn-probe-add`); after ≥ 3 points (5 by
  default) the action becomes **`Save 5 points and plan again`** (`#btn-probe-save`): `PUT /api/surfaces/board`
  with `planId`, then `POST /api/plan` with the same board and position, in one press. If the save comes back
  untied, the step shows `Not tied: the board or its position changed since this plan. Plan again, then save.`
  with `Plan again` inline.

#### 8.1.4 Checks step (`data-view=checks`) — `mockup-run.html`

Top: eyebrow `Board 3 · plan 7f3c`, `Ready to dispense?` and the count `8 of 9` (32 px), then the 9-segment
bar `#ready-segbar` in the fixed order of §9.1 (colours by state; `you` attestations blue), then the line
`#plan-interlocks`: `13 interlocks: 10 ok · 1 needs you · 2 not fitted` + `All 13` (opens the Interlocks view).
When every plan interlock passes, this line reads `All interlocks pass for this plan · 11 ok · 2 not fitted`.

**Needs you (n)**: one card per not-ready item, in the fixed order, the first carrying a black `NEXT` tag (as built, rc1
review: the `NEXT` card is the first blocked item, the one the verdict names, else the first not-ready item, and it
comes first; the nav subline is its short name, or a shorter form where that would not fit: `Purge row short`). Card:
glyph, title in plain words, detail with numbers, the I-code at the right, fix-it buttons (44–46 px). Pads clean
card = `#clean-result`; its first sentence is machine-readable for tests and always one of:
`All 160 pads are clean (13:09).` · `158 of 160 pads clean, 2 with paste left (13:09).` ·
`0 of 160 pads clean: the board position looks wrong (solder mask under every box), not 160 dirty pads.` ·
`No pad was checked: <reason>.` Below it the dirty pads with their share (max 6, then `and 12 more` opening the
list) and the buttons `Wiped: check again` (`#btn-clean-check`, two-press, `≈ 3 min`) and a camera button.

**Ready (n)**: single-line tiles in two columns (42 px): glyph · title · value right-aligned
(`Machine 13:03`, `Start angle 298.0°`, `Needle −2.590 mm`, `Regulator 18 psi` with the blue `you` glyph,
`Pad types 160/160`, `Surface tied`, `Plan current`, `Purge row 9/15`). The tile's `title` carries the long
form; a click opens the detail.

**Pinned foot**:
- When something still needs the operator but the run itself handles it (only dirty pads, I10): reason line
  above the button `! 1 needs you. Run anyway: the run checks every pad first (≈ 3 min) and refuses while paste is
  left.` and the Run button in the **hold** style (white, 2 px teal border, teal text) — never a filled green
  button next to an unresolved item.
- When all 9 are ready: filled teal `Run board · 160 dots` (60 px).
- When truly blocked: the button is disabled, and the line above names what to finish:
  `Finish 2 checks first: start angle, purge row.`
- Under the button: `Full board · all 160 dots` and `Test run with fewer dots…` (`#btn-run-test`) which reveals
  `#run-maxdots` (`Only the first [  ] dots`). While a limit is set the button turns into the amber TEST style
  `TEST: first 20 of 160 dots` and the confirm repeats it. The limit is cleared after every run.

#### 8.1.5 Run confirm — `mockup-run-confirm.html`

In-page `<div class="scrim">` (starts under the strip; STOP and the strip stay clickable) + `<div role="dialog"
aria-modal="false" id="dlg-run">`, width ≤ 760 px, `max-height: calc(100vh - strip - 48px)`, scrolling body,
**pinned footer** (verified at 1280 × 800). Focus goes to the first unticked tick box, never to the Run button.

1. Head: eyebrow `Run board · <board> · plan <short id>`; title `Dispense 160 dots on 160 pads?`
   (test: `Dispense the first 20 of 160 dots?`; finishing a stopped board: `Dispense the 99 pads left?`);
   key hint `Esc closes · F9 stops`.
2. Regulator block, first and largest (22 px / 750, info tint, gauge icon): `#run-confirm-psi` =
   **`Set the regulator to 18 psi (profile 18 psi half turn)`**; under it `There is no pressure sensor: read the
   gauge. 16 psi starved the screw, 20 psi drooled.` Then on one row the tick `The gauge reads 18 psi`
   (`#run-tick-gauge`, shows `you, 13:04` when a valid attestation exists, §9.4) and `Pressure on to adjust`
   (opens the Pressure view; it is two-press when the needle is not over the waste).
3. `The machine checked` (2-column grid of sensed items): board surface tied, start angle, purge spots
   `7 to 14` with what is left after, the clean-check state (`2 pads had paste at 13:09 · all pads checked again
   first, ≈ 3 min` as needs-you), needle offset if relevant.
4. `You confirm (nothing senses these)`: `Paste is within its working life` (`#run-tick-paste`, I8),
   `Hands and tools are clear of the head` (`#run-tick-hands`, asked every run, never remembered).
5. Loud amber line `#run-test-line` when a limit or a pad subset is active.
6. Footer: summary `Full board, all 160 dots. Pad check ≈ 3:10, then pressure on, 8 purge dots, the board,
   vent.`; `Cancel` (`#btn-run-cancel`); primary `Run 160 dots · ≈ 5:16` (`#btn-run-go`). Disabled until every
   tick is set, with the visible label `Tick 2 more to run`.
7. `#btn-run-go` posts `POST /api/run {planId, maxDots?|padIds?}`; a 409 keeps the dialog open and shows the
   refusal inside it with fix-it buttons.
8. **As built:** `#run-confirm-home` (an info note at the top) when the run homes or checks Z first: `Homes first
   (stopped at 14:19), ≈ 22 s: the head goes to its home corner and the top camera finds the homing fiducial. The
   pressure stays off until homing is done. Then one N1 touch checks Z (≈ 17 s).` or `Checks Z first: one N1 touch on
   the blank (≈ 17 s), because the N1 heights predate the home at 14:19.` The Run step's phases add Home and Z check
   rows (`run.detail.stage`).

#### 8.1.6 Run step (`data-view=run`) — `mockup-run-progress.html`

- Second STOP (§5) at the top.
- `One pressure session` phase list `#run-phases` (from `run.phase`/`run.phases` mapped to plain words, with
  durations measured client-side): `Pads checked with the camera · 160 of 160 clean · pressure off` →
  `Pump on, valve open, settle` → `8 purge dots on spots 7 to 14` → `Cross to the board` →
  **`Board dots 96 of 160 · next D9.1 · 5,362 DPH dots only`** (energised tint) → `Vent, pump off, screw released ·
  automatic at the end`. The camera tour shows `pad 57 of 160 · 1:52 left` (from the optional API field of
  §17, else an estimate from elapsed time labelled `≈`).
- `After this board` consequence box (e.g. `the purge row has 1 spot left. Wipe it and reset it before board 4.`).
- The exact stop line: `STOP sends M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B: quick stop, dispenser valve
  and pump off, pick valve and pump off, screw motor off.`
- Locked while a task runs, each with the visible reason `Locked while the board runs` (page-head note lists
  them): Change board, Plan again, board select and position fields, Refresh, Probe, every Setup save, Pressure
  on, Prime, Test dot, Calibrate runs, Home and Home again, Connect (already connected), purge reset, profile save.
  Never locked: STOP, Pressure off, workspace navigation, reading anything.

#### 8.1.7 Result step (`data-view=result`)

`Finished · all 160 dots` (status apart from outcome: `Finished`/`Stopped`/`Refused`/`Ended by an error` +
`all 160 dots` / `61 of 160 dots` / `2 pads not clean`), DPH dots only and overall against the estimate and the
bench 5,490, total time, purge spots left, the profile token, and **`Next board`** (`#btn-next-board`): asks
`Unload this board and load the next. New seat?` → opens the Board step with the re-seat question answered
`Yes` by default and the surface check set back to needs-you (§9.1 item 6).

A board with paste on part of its pads (a test run of the first N dots, or a finish that did not complete it) shows the
same board decision as after STOP, without the red: `Finish the 140 pads left` (`#btn-run-rest` / the Run button in
Checks / `#btn-result-rest`) or `Wipe the 20 pads, run all` (`#btn-run-all`); `Test run with fewer dots…` is not offered
on a board that already has paste. Planning the same seat again (no `Next board` in between) is the same board: its
number and its dispensed dots carry over. A STOP before the first board dot has nothing to keep or wipe: `No dot
reached the board` and the one action `Run the board again`. *As built (rc1 review):* the server keeps each seated
board's paste and number (`plan.pasted`, `status.run.boardNumber`; `Next board` posts `POST /api/board/next`), so a
new tab, another browser or a reload sees the same board; a run that lays pads that already have paste (the whole
board, `Wipe all, run all`, a test run over them) asks `I wiped this board: no paste on its 150 pads now`
(`#run-tick-wiped`, `This board (board 3) already has paste on 150 pads this run lays: wipe them first, or press
Cancel and finish the pads left.`) before `Run`, and tells the engine (`{wiped: true}`). The Result step's
`board 1 ok` names the board that ran, without its tick once `Next board` was pressed. A board with every dot already
reads `Board done · Next board` (strip and Checks; nav `Board 3 done`), with `Next board` (`#btn-run-next`) as the
primary action, `Run it again · 160 dots` second and no `Test run with fewer dots…`.

#### 8.1.8 Recovery (`data-view=recovery`) — `mockup-run-stopped.html`

Triggered by our STOP response, or by `run.status` in `ABORTED`/`ERROR`, or `machine.enabled` going false while
a task ran. Persistent until every step is done; it clears itself (no close button). A stale `ABORTED` banner
never outlives it.

- Strip: `Not connected` (red) + Connect; `Off · vented` (grey); Start angle `Confirm` (amber); Purge row
  `7 of 15 · wipe` if short (amber); verdict `Stopped 14:19` (red).
- Main: eyebrow (red) `Board 4 this session · ended by STOP 14:19:42 · 61 of 160 dots`; meta `Keep the board where
  it is: the 61 dots belong to this seat.`; plan chip `Ended by STOP · 61 of 160 dots`; note `Interrupted during
  board dots · pressure had been on 0:52 · dot 62 on R6.2 may be partial`; the preview with done dots and the
  in-progress pad as `R6.2 · check` (amber ring). Footer: `This board` — `Finish 99 pads` (`#btn-run-rest`,
  `POST /api/run {planId, padIds}` from `GET /api/runs/last` dot timings; the in-progress pad counts as not done)
  and `Wipe all, run all` (`#btn-run-all`); both disabled with the visible reason `Both unlock after steps 2 to 5
  on the right. A whole-board run is refused while the 61 dispensed pads read dirty (I10).`
- Panel: red `Stopped 14:19:42` card (`role="alert"`) — `By this page's STOP (F9). OpenPnP confirmed it. Sent
  before the next line:` + the code line `M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B`. For ERROR:
  `Ended by an error: <message>`; for OpenPnP's own Stop: `Stopped in OpenPnP`.
  **Back to ready** `n of 6`, live ticks from status:
  1. `Pressure vented` (`valve closed 14:19:42, pump off`; red `Pressure is NOT off` + `Pressure off` if not).
  2. `Connect` (`reconnects to the controller; nothing moves`) — button.
  3. `Was the controller switched off?` — asked after Connect with the two equal answers of §8.2 (never asserted).
  4. `Home` (`M410 can lose steps: home before any move`) — two-press button. **As built:** done without a home when
     Run will home by itself (`homeBeforeRun`), unless a STOP left N1 low (`status.machine.home.n1Low`): then Home
     (which raises N1 first) is needed.
  5. `Purge row: 7 left, 8 needed` — only when short; available at once (`can be done now`), two-press
     `Wiped: reset row`. Comes **before** the board decision because every run, including a pad subset, purges.
  6. `Decide about this board` — points to the main-column buttons, which unlock when 2–5 are done.
  Then `What STOP did` (server action list, each in plain words with its code): `Motion stopped at once · M410`,
  `Dispenser valve off: barrel vented · M107 P3`, `Dispenser pump off · M107 P2`, `Pick valve and pump off ·
  P1 · P0`, `Screw motor off (turns freely) · M18 B`, `OpenPnP stopped: machine disabled`.

### 8.2 Setup (bring-up and by-hand tools) — `mockup-setup.html`

Page head: `Setup` + `9 of 11 steps ready`; meta `Ticks come from the machine. Steps marked [you] have no sensor:
your tick is kept in this browser.`; filter `All steps | Needs you · 2`.

**Needs-you band** (`#setup-needs`, only when something needs the operator, in bring-up order). Examples:
- I11: `Was the controller switched off since 13:04?` · `I11 · screw start angle 298.0°` · `OpenPnP reconnected at
  14:22 after the STOP at 14:19. STOP and Connect keep the screw step count; only switching the controller off loses
  it.` Answers with **equal visual weight, same size, no default focus**: `No, it stayed on: 298.0° still holds`
  (`POST /api/screw/start-angle {}`) and `Yes, it was off: measure again` (opens Calibrate › Start angle).
- I9: `Purge row: 7 of 15 spots left, 8 needed` · `Wipe the strip on the blank (pressure off, needle away), then
  reset the row.` · two-press `Wiped: reset row` (mockup shows the pending state) + the purge strip.

**Cards** (two-column grid, fixed order, never reorders; `id="card-…"`):
1. **Needle Z frame** (`#card-needle`, state `#needle-state` starting `Touched off 12:51` / `NOT touched off`):
   minis Frame offset / Needle offset (Δ and limit ±1.00); method switch `From K | Both tips touch`;
   `#anchor-n1` with `Use live N1 Z 5.50`; K `#anchor-k` (default 65.59) with the derived needle contact;
   both-tips: `#anchor-n2` + `Use live N2 Z`; last touch-off result; foot `Record from K` (`#btn-anchor-k`) /
   `Record needle frame` (`#btn-anchor`), **`Confirm −2.590 mm`** (`#btn-needle-offset`: posts the frame offset;
   the manual offset field `#needle-offset` sits behind `Other offset…` and is always refilled from the server
   unless focused or edited), `Touch-off ›`, `I changed the needle` (`#btn-needle-changed`, two-press).
   Below: **Needle XY trim** row with current dx/dy, set time and note, `Edit trim` opening an editor in the
   panel with a sign sketch (target vs landing), `#trim-dx`, `#trim-dy`, `#trim-note`, `#btn-trim`, limit ±1 mm,
   and `Plan the board again` as a follow-up action after save.
2. **Probed surfaces** (`#card-surfaces`, `#surf-state`): Board (`tied to plan a91e`, seat, points, worst residual,
   tilt, time, mini map) and Blank (points, worst, Z range, covers). `Probe the board…` (opens Run › Probe),
   `Probe the blank…` (same probe UI in the panel for the blank: point table with per-point residual and delete,
   plane fit before saving, coverage of purge row and places). Disclosure `Advanced: paste points or a zmap.json`
   (`#surf-advanced`) holds `#surf-name`, `#surf-points`, `#btn-surf-add` (`Add live N1 point`), `#btn-surf-clear`
   (`Clear points`), `#btn-surf-save`; `Delete surface` (two-press) lives here, away from Add. When the board
   surface belongs to another seat (`Yes: new seat`, or probed on another registration) the editor starts empty
   and Save waits for new points: one press never ties the old seat's points to the new plan.
   **As built:** `Probe the board…` opens Run › Register. `Probe the blank…` opens the `probe-blank` view: automatic
   first (`ol.phases#blank-phases`, `table#blank-targets`, `div.ri#blank-result`: `Saved 13:20: 6 points, worst 0.012
   mm, covers 15 of 15 spots.` · `Not saved: …` · `Stopped …: nothing was saved.`; foot `#blank-note`,
   `#btn-blank-auto` (two-press, `POST /api/surfaces/blank/probe`), `#blank-why`), by hand below
   (`details#blank-by-hand`, `#btn-blank-add` / `#btn-blank-save`). Settings add `#set-homebefore`
   (`homeBeforeRun`: when needed / always); the JSON editor never sends `boardRegistrations` or `newSeatAtMs`.
3. **Purge row** (`#card-purge`, `#pr-state`): large strip (used / this board / free), `8 used · 7 free`, last
   wiped; `#pr-surface`, `#pr-x0`, `#pr-y0`, `#pr-pitchx`, `#pr-pitchy`, `#pr-spots`, `Use live N2 X/Y …`
   (`#btn-pr-usexy`), `Save purge row` (`#btn-pr-save`, disabled with `No unsaved changes`),
   `Wiped: reset row` (`#btn-pr-reset`, two-press). A moved row asks in-page `The row moved. Is the new row clean?`.
4. **Pressure and paste flow** (`#card-pressure`, `#pressure-state` starting `Off` / `ON` / `NOT off`): target
   block `Set the regulator to 18 psi` + `profile 18 psi half turn · 16 psi starved the screw, 20 psi drooled`;
   needle check `Needle over the waste: 0.4 mm from 'waste'` (red `Needle is not over the waste (41 mm away):
   park N2 over the waste in OpenPnP first` when > 3 mm; Pressure on then needs two presses); `Pressure on`
   (`#btn-pressure-on`) / `Pressure off` (`#btn-pressure-off`); `Switches off by itself after 10:00 on (I7).
   Prime, test dot and runs are locked while it is on.`; rows `Prime at the waste` `#prime-rot` (360.36°)
   `Prime` (`#btn-prime`, two-press) and `Test dot` `#testdot-rot` (one profile dot, follows the profile)
   `Test dot` (`#btn-testdot`, two-press); progress and result **in the card** (`#dispense-result`), with an
   optional `I saw the dot` attestation.
5. **Places** (`#card-places`, full width): table `#places-table`, `tr[data-place]` rows with
   `input[data-axis=x|y|z]`, `Used for`, `From head ▾` (menu: `N2 X/Y`, `N1 Z (tip touching)`, `N2 Z (tip
   touching)` — the last only with a needle frame; each item names the live value) and `button[data-action=save]`
   (`Saved` disabled when clean). Edited fields get the amber left bar; the card head counts `1 unsaved`.
   Above the table a small bed map (SVG, machine mm): board outline, waste, blank, purge-row spots (used/free),
   live head; labels sit outside the shapes with leader lines and never overlap another shape.
   `waste-test-dot` is a derived read-only row `follows waste + 2.00 mm X`. Add place `#place-new` +
   `#btn-place-add` (the new row survives re-renders until saved or removed). Note: `A Z taken with the needle is
   off by about 2.59 mm: take Z with N1.`
6. **Top camera** (`#card-camera`): `<img id="cam-img">` with overlays (camera X/Y, frame time, crosshair),
   `#cam-empty` reason text (404 no camera / 503 no frame in 10 s / simulator static picture), `Snapshot`
   (`#btn-cam`), `refresh every 2 s` (`#cam-auto`; stops when the card is off-screen, the tab is hidden, or STOP).
7. **Profile and start angle** (`#card-angle`): recipe summary, angle, alternating start angles, set time,
   controller connected time; `Open Profile ›`, `Measure the angle ›`.
8. **Settings** (`#card-settings`, full width): `#set-maxon` (s, I7), `#set-valvesettle` (ms, `valveSettleMs`),
   `#set-pumpsettle` (ms), `#set-minstatus` labelled **Lowest pad-type status allowed on a board**, `#set-clean`
   `Clean-pad check before each board (I10)` (with `Off (simulator)` wording in sim), `#set-refuseflagged`
   **Refuse flagged pads** (`refuseFlaggedPads`; `On: a board with pads that cannot be dispensed is refused` / `Off:
   those pads are skipped, the rest dispensed`; the deep link `#setup/settings/refuse-flagged` focuses it), I10 thresholds (gray level,
   min bright share, box) in a disclosure, `Save settings` (`#btn-settings`). Disclosure **All settings (advanced
   JSON)** (`#set-advanced`): `#set-json` editor, inline JSON validation, a diff of changed keys
   (`#set-json-diff`), stamped keys marked read-only, `Reload and keep my edits`, `Save changed keys`
   (`#btn-settings-json`) which sends **only the changed keys** as a partial `PUT /api/config` (never the stamped
   `purgeRow.usedSpots`, `purgeRow.resetAtMs`, `profile.startAngleSetAtMs`, `needleXyTrim.setAtMs`, `needle*`,
   `surfaces`, `schemaVersion`).

**Default panel** (`head`): live head table and **Bring-up order** with progress (`9 of 11`): 1 Machine,
2 Settings, 3 Regulator (you), 4 Places, 5 Needle Z frame, 6 Blank surface, 7 Prime and test dot (you),
8 Needle XY trim, 9 Screw start angle, 10 Purge row, 11 Pad types. The first not-ready step carries `NOW`;
each row links to its card. Start angle comes before Purge row so the strip verdict's NEXT and the list agree.
*As built (rc1 review):* after a failed Z check of this home, 4 Places is blocked while any place in
`status.machine.zCheck.places` waits for its Z: `Z moved: retake 2`, `Z moved at this home: take the Z of waste, blank
again with N1 touching.`, its link opens the first one's Z (the engine refuses those places meanwhile, I4).

### 8.3 Profile (not mocked; same frame)

- **Dispense profile** `#pf-card`: two-column form, label line with unit and range, bench evidence next to its
  field (`16 psi starved the screw, 20 psi drooled`, `180 rpm proven: never faster than the paste feeds the screw`,
  `0.20 mm gap misses dots`); fields `#pf-name`, `#pf-psi` (+ `#pf-psi-note`), `#pf-dwell`, `#pf-turn` (whole
  fractions of 1600 steps), `#pf-suck`, `#pf-gap`, `#pf-rpm` (+ `#pf-rpm-warn` above 180), `#pf-accel`,
  `#pf-purge`, `#pf-notes`; derived line `#pf-turn-info` (`forward 190.09° · back 9.91° · net 180.18° per dot ·
  800 steps · G1 B F64800`); problems `#pf-problems` at the field. A sticky **unsaved bar** (`#pf-diff`) appears as
  soon as a field differs from the server: `dwell 250 → 260 ms · plans become stale · regulator unchanged` with
  `Discard` (`#btn-pf-discard`, two-press) and `Save profile` (`#btn-pf-save`; above 180 rpm the save asks in-page).
- **Screw start angle** block: angle, `#pf-angle-kv`, warning `#pf-angle-warn` (hidden when confirmed), the two
  equal answers `Controller stayed on: 298.0° still holds` (`#btn-pf-angle-confirm`) and `It was switched off:
  measure again` (`#btn-pf-angle-measure`, opens Calibrate › Start angle where `#pf-angle` + `#btn-pf-angle-set`
  live). A confirm can therefore never become a profile change by accident.
- **Pad types** `#profiles`: `.family` sections with sticky `h2` headers in the §5.7 order; one 44 px row per type
  `tr[data-key]`: name, status `.badge.<status>` (icon + word), pad size, dot plan, pads in the current plan,
  last validated, and two buttons: `Edit` (opens `#dlg-padtype`) and `More ›` (side panel: Set up, Validate,
  History `#dlg-history`, Retire (two-press), **Rule editor** (`#pt-rule-maxdots`, `#pt-rule-fit`,
  `#pt-rule-largest`, `#pt-rule-notes`, `#btn-pt-rule-save` → `PUT /api/pad-types/{key}/rule`), matched packages).
  `New pad type` `#btn-padtype-new` (`data-testid="new-pad-type"`), `show retired` `#show-retired`, filter
  `Used by this plan`. Dialogs `#dlg-padtype` and `#dlg-history` stay `<dialog>` elements but open with
  `dialog.show()` (non-modal) plus our scrim, never `showModal()`; the `[open]` attribute still marks them.
- Default panel: `What this profile does` and the gate `Lowest pad-type status allowed on a board: characterized`.

**As built (pc-web, PC §13.3–13.4):**

- **Needs a profile** `section.card#pt-needs` above the pad types while the plan shown (or a board file chosen after an
  upload) has pads without a usable profile: `Needs a profile` + `#pt-needs-count` (the sizes to choose), the source
  (`From the plan of padcal mvufckey (20:40)`), the lead `These pads get no paste until each has a pad type. Use an
  existing type of the same size, or let the machine calibrate the suggested one.` and `table#pt-needs-table`, one
  row per pad size `tr[data-group][data-reason]`: package, parts and pads (`J1, J2 · 4 pads`), openings (`0.86 × 1.83
  mm openings`), the reason badge (`no pad type`, `not calibrated yet`, …), the suggestion in a few words with its
  confidence (`New: 2 half turns per pad, ≈ 2.11 mg · medium confidence`, `Use Chip 0603 · medium confidence`), and
  `Use Chip 0603 · plans again` (one click: the mapping, then the board planned again) or `Calibrate automatically…`
  (opens the pad-cal view before start), `I’ll do it by hand`, `Suggestion…`. Collapsed below: `Cannot be dispensed
  with the 22G tip (9 pads)` (`#pt-needs-tip`, no actions) and the notes (`#pt-needs-notes`). Foot:
  `#btn-needs-use-all` (`Use the matching pad types (n) · plans again`) and `#btn-needs-auto-all` (`Calibrate all
  automatically… · 2 sizes · ≈ 3:26`). The filter segment gets `Needs a profile · 2` (`#pt-filter-needs`), the nav
  sub `Needs 3 profiles`. One filled button per screen: the list's own filled button turns plain while the side panel
  shows a suggestion or the calibration.
- **The suggestion card** (side panel `#profile/needs/<group>`): `#pt-sugg-reason` (the reason notebox),
  `#pt-sugg-text` (the sentence: `New pad type SW_SPST_3.9x2.9mm · 1.83 × 0.86 mm: 2 half-turn dots per pad (1.16 mm
  wide), about 2.11 mg per pad, 8 % less than the 2.29 mg these pads would get at your 0603 rate; at most 0.15 mm
  past the pad edge.`), `#pt-sugg-preview` (the board's pads with the suggested dots drawn at their size,
  `circle.pv-sugg`; the plan's own `circle.dot` count is unchanged), `#pt-sugg-kv` (Opening, `Target amount` with its
  rule after a `·`, Dots per pad and turn, Dot size, Per pad, Spread, Clearances, Phase, Confidence and why),
  `#pt-sugg-alt` (`Other plans` with why each was not chosen), and `#btn-pt-sugg-use` / `#btn-pt-sugg-auto` /
  `#btn-pt-sugg-manual` / `#pt-sugg-edit` (`Edit before saving…`); a group the tip cannot dispense shows only
  `#pt-sugg-tip`.
- **Pad-type editor and side panel**: `#pt-dotsteps` (`Turn per dot`: `the profile’s (half turn)`, `quarter turn (400
  steps)`, `half turn (800)`, `one turn (1600)`), `#pt-dotsperpad` (1 to 6, empty = by the pad size), read-only
  `#pt-calinfo` (tip, recipe, calibrated pad area, weighed mass per pad, origin, last calibration);
  `Calibrate automatically…` (`#btn-pt-auto`) next to Set up / Validate. Texts replaced: the meta `One recipe; a pad
  type may use another turn per dot (quarter, half or one turn). Plans made before a change are refused: plan
  again.`, the column hint `Dots per pad and turn`, a type's dots `2 × ¼ turn`.

### 8.4 Calibrate (not mocked)

Banner `#cal-history-note`: `Records only: boards use the dispense profile.` Cards, each listing its
prerequisites as readiness rows with fix-it links and showing progress and result in place:
**Touch-off** (`#to-start` 0.125, `#to-step` 0.025, `#to-steps` 6, `#to-rot` empty = one profile dot,
`#to-place` blank, `#to-apply` ticked by default when it is the I4 step (on the real machine untick it, and use the
contact only when it agrees with the after-photos and the needle's measured K, `REAL_MACHINE_TODO.md` item 7: no
contact has been found on the machine yet), `#btn-to-run` two-press; result table
gap → diameter, contact, `offset −2.590 → −2.600 mm, Δ −0.010`, explicit `Use for the needle offset`, history of
the last 20 touch-offs), **Start angle** (guided dot-pair procedure; `#pf-angle`, `#btn-pf-angle-set`),
**Scale shots** (`#shots-place` with a link to Places when empty, `#shots-rot`, `#shots-count`,
`#btn-shots-run` two-press; then the record form `#ss-*` and `#btn-ss-save`; set pressure prefilled from the
profile), **Dot batch** (`#sd-*`, `#btn-sd-save`), **Dot ladder** (`#dl-*`, `#btn-dl-run` two-press),
records `#cal-current`, `#cal-table`, `#ladder-table` (ladder of any record via `GET /api/calibrations/{id}/ladder`),
`#cal-result`. Set up / Validate from a pad type open this workspace with `#setup-guide` filled.

**As built (fix round 2, F9, F14):** the touch-off has `#to-pitch` (`Pitch`, mm, placeholder `1.6`, `at least the dot +
0.4 mm`; sent as `pitchMm` when typed), the plan line ends `· 1.6 mm apart`, and the engine's 400 for a pitch with no
room between the dots is the field's error. Its result table reads `not measured` (with the engine's note, e.g. `joined
to the dot at gap 0.200 mm`) for a dot the camera could not measure, and `no dot` only where there was no paste; a run
that recorded nothing shows its message word for word.

**As built (pc-web, PC §8, §13.5–13.6):** the page meta `The touch-off sets the needle offset, the start angle sets
where dots start and the pad calibration sets a pad type’s dots. Everything else is a record.`; the banner
`#cal-history-note` now reads `Boards use the profile’s dot unless a pad type has its own turn (set by Calibrate
automatically or by hand). The dot ladder and scale cards below are records only: their dots are not phase-locked, so
partial turns there vary about ±50 % with the screw angle.`; the dot ladder's subtitle `camera · records only (no phase
lock)`; the cup shots are folded into `Older method: shots into a cup (records only)` (`#cal-shots-older`).

- **Pad calibration** card `#cal-padcal` (`where the test dots go on the blank, the paste, the weigh coupons`): a map
  `svg#pca-map` (the area and its used band, the purge spots, the coupon places), the area `#pca-x0`, `#pca-y0`,
  `#pca-w`, `#pca-h` (`Lower-left corner and size. Default: 40 × 20 mm at the blank place, off the purge row.`),
  `Save the area` `#btn-pca-save`, `Use the default` `#btn-pca-default`; `Left on the blank` `#pca-left` (`… mm
  left`) and `Blank wiped` `#btn-pca-wiped` (two presses, `The calibration area is free again: 14.0 mm left.`);
  `Paste`: `New paste loaded` `#btn-pca-paste` (two presses: weighings and camera results from before stop counting);
  `Weigh coupons`: their places and `#pca-pending` (coupons whose dots wait for their readings, typed here after a
  STOP or a restart: `#pcal-pf-k`, `#pcal-pe-k`).
- **Weigh dots** card `#cal-weigh` (the by-hand weighing, PC §8): `#wd-type` (pad type), `#wd-steps` (turn per dot),
  `#wd-count` (dots per coupon: a whole number of screw turns in total, so the angle ripple cancels), `#wd-plan`,
  `#wd-pre` (before it runs), `Lay the weigh dots` `#btn-wd-run` (two presses); the readings are then asked for in the
  pad-cal view.
- **The guide** (`#setup-guide`, from `I’ll do it by hand`): names the suggestion, `#btn-guide-ladder` lays its
  ladder (two presses) and `table#pcal-ladder` shows the measured numbers against their limits; then Weigh dots,
  prefilled, then the type's dots and status in Profile.
- **The pad-cal panel view** (`?panel=pad-cal`; stays open from every workspace while the operator weighs): before
  start `#pcal-note` (the summary: `Checks and touches your two weigh coupons (≈ 1:44). Lays 76 test dots on the
  blank … Then it needs you once: weigh each coupon, wipe it and weigh it again (4 readings). About 0.23 g of paste,
  ≈ 5:45 before your readings.` and, from the preview's `purgeSpots`, `It uses 26 of the 31 free purge spots: wipe the
  purge row and reset it before the next board.`), `#pcal-needs` (`You need: two clean weigh coupons on coupon1 and
  coupon2 (blank PCB, 40 × 30 mm, at least 3 g), the balance, a lint-free wipe and isopropyl alcohol.`),
  `table#pcal-plan` (the steps with their times, and `Purge row · 26 of 31 free spots (5 left after)`),
  `#pcal-groups-list`, `#pcal-fixes` (fix-its for a refusal), `Calibrate · ≈ 5:45`
  (`#btn-pcal-start`, two presses: the only start); running `ol.phases#pcal-phases` (Home, Z check, Probe the blank,
  Find and touch the coupons, Test dots on the blank, Weigh dots on the coupons, Your readings, Decide, Plan again), `#pcal-live`
  (`0 of 6 dots`), `table#pcal-ladder`, `STOP stops it at once (F9).`; the readings `div.ri.ri--need#pcal-ask`
  (`#pcal-ask-text`, per coupon its camera picture `#pcal-coupon-k`, `#pcal-full-k` and `#pcal-empty-k` in grams,
  `#pcal-mg-k` `= 78 mg of paste (0.574 mg per dot)`), `Save the readings` `#btn-pcal-readings`, `Skip weighing (the
  types stay provisional: boards still refuse these pads)` `#btn-pcal-skip`, `Cancel the calibration`
  `#btn-pcal-cancel`; a reload or a second browser brings the prompt back; the result `div.ri#pcal-result` (the first
  sentence, e.g. `Characterized 20:47: …; planned again.`, the control's drift, and when a board would be short of
  purge spots `#pcal-result-purge` `Next: wipe the purge row and reset it before the next board (5 of 31 spots left,
  13 needed).` with `Wiped: reset row`), `table#pcal-ladder` under `Camera checks of the dots` (every dot the top
  camera looked at per dot size: the blank's test dots and the coupons' weigh dots), `#pcal-groups` (per group its numbers
  and checks), `#pcal-crops` (camera crops, 12 px under the card), `#pcal-pred` (`Predicted and measured`, P1–P8),
  and `Calibrate again…` `#btn-pcal-again`, `Type the readings` `#btn-pcal-weigh-now` (a stopped job's coupons),
  `Finish the calibration` `#btn-pcal-finish`, `I’ll do it by hand` `#btn-pcal-manual`, `Checks ›` `#btn-pcal-plan`
  (the board planned again).

### 8.5 Records (not mocked)

Segmented control `#records-seg`: **Boards** (`#records-runs`, `GET /api/runs?limit=50`: time, board, status,
outcome, dots, DPH dots only / overall, profile; detail panel with dot timings, done-dot map and `Finish the
remaining pads` for an aborted board; a run of part of the board, a test or a finish, counts and finishes only its
own pads, and the run confirm drops any pad this tab saw pasted) · **Activity** (`#records-events`,
`GET /api/events?limit=100` + this page's log) · **Calibrations** · **Touch-offs**.

**As built:** the done-dot map draws the plan's board drawing under it (Quiet); the new task kinds read
`Registration · <board>`, `Surface probe · <name>` and `Z check`.

---

## 9. Readiness model (one pure function)

`readiness(status, plan, planFresh, attestations, nowMs) → { items[9], verdict, count, next, blocked[] }` drives
the verdict cell, the Run nav badge and subline, the Checks header, segment bar, needs-you cards, Ready tiles,
the Run button state and the tab title. Nothing else may compute "ready". Unit-test it with fixtures.

### 9.1 The nine checks (fixed order)

| # | Item | Ready when | Not ready: severity → fix |
|---|---|---|---|
| 1 | Machine | `machine.enabled && homed && !busy` | not connected → blocked: `Connect`; not homed → needs-you: `Home` (two-press); homing → energised. **As built:** when Run homes first (`machine.home.policy`), a note that counts ready (`homes first`, `homes first, checks Z`; verdict `Ready to run · homes first (≈ 25 s)`); needs-you only when a STOP left N1 low. Homed with Z data from before the home: `checks Z`, fix-it `Check Z` (two-press) |
| 2 | Start angle (I11) | I11 `ok` or `off` | reconnect → needs-you: the two answers in place; never set → blocked: `Measure the angle` |
| 3 | Needle offset (I4 needle) | frame set, touched off or confirmed, drift ≤ 1.0 mm | not touched off but frame set → needs-you: `Confirm frame offset −2.590 mm`; frame missing / needle changed → blocked: `Open Needle Z frame` |
| 4 | Regulator (I6, you) | a valid attestation (§9.4) | needs-you: `Pressure on to read the gauge` + `Reads 18 psi` |
| 5 | Pad types (PLAN) | no blocked pads | blocked: `3 pads below characterized: chip-0603 ×2, sot-23 ×1` → each opens that type. **As built (pc-web), with the plan's profile check:** groups that need a profile → `Blocked · needs a profile`, `21 pads cannot be dispensed: 12 need a profile, 9 not with the 22G tip (WSON-8…)`, fixes `Use Chip 0603 for TestChip_A · plans again` / `Needs a profile: SW_SPST_3.9x2.9mm` (up to 3), `All 3 in Profile › Needs a profile`, `Lowest pad-type status`; only pads the tip cannot dispense → blocked, `9 pads cannot be dispensed with the 22G tip: WSON-8… (9 pads).`, fixes `Dispense the rest (Setup › Settings › Refuse flagged pads)` and `Why`; with Refuse flagged pads off (`status.dispense.refuseFlaggedPads` false: the engine skips them) → a Note that counts ready, `151/160 · 9 skipped`, `9 pads skipped (Refuse flagged pads is off): WSON-8 (9 pads) not with the 22G tip. The other 151 are dispensed.`, fixes `Refuse flagged pads` and `Why` (or `Needs a profile`), and the run confirm adds `Skips 9 pads that cannot be dispensed` with the pads by package (its title counts only the pads dispensed); only notes → a Note that counts ready (`160/160 · 2 notes`). The Pad types tile goes to `#profile/needs` |
| 6 | Board surface (I4 board) | tied to this plan's registration, covers every dot, fit ok, and the operator did not answer "new seat" since | needs-you: `Probe this seat` / `Same board, not moved`; fit or coverage problem → blocked with the bad points/dots. **As built:** the fix is `Register board` (`register`, two-press) or `Same seat? Check the fiducials` (`register-check`), with `Do it by hand`, and the item's short name is `register board` (strip `8 of 9 · register board`, nav `Register board`; final gate: it was `probe seat`); texts `No board surface for this seat yet: register the board (fiducials, then N1 touches the board).`, `You said the board was re-seated: register it …`, `The board surface belongs to another seat of this board: register it again.` A board without fiducials (`registrationTargets.probeOnly`, F10): the fix is `Probe only (X/Y as planned)` (`register-probe-only`), the short name `probe board` (blocked: `probe again`), texts `No board surface for this seat yet: probe the board (no fiducials: N1 touches it where it is planned).`, `You said the board was re-seated: probe it …`, `The board surface belongs to another seat of this board: probe it again.` |
| 7 | Plan current | plan exists on the server and its fingerprints match (profile, surface, needle, registration) | needs-you: `Plan again` (in place); 404 → `Plan forgotten (OpenPnP restarted): plan again` |
| 8 | Purge row (I9) | `spotsLeft ≥` the plan's `purgeSpotsNeeded` (else `perBoard`; as built, rc1 review: a plan of two dot sizes needs 8 + 5 = 13, `this plan uses 13 (8 to start, 5 for its other dot sizes)`; the strip cell, the run confirm's `Purge spots 1 to 13`, `after this board` and the purge row's `next` cells count the same, `IL.purgeNeed`) | needs-you: `Wiped: reset row` (two-press); fewer spots than the need → blocked `too short`; not set → blocked: `Set the purge row` |
| 9 | Pads clean (I10) | last clean check of this plan passed, or I10 off (then grey `Off: not checked`, counted ready) | needs-you: `Wiped: check again`; the run may still start (it re-checks) |

Other interlocks (I1, I2, I3, I5, I7, I8, CONFIG) do not appear among the nine; a `blocked` one is added as an
extra blocked card at the top (e.g. `CONFIG: purge dots must be at least 2 for a partial turn` → field link),
and I1 `warning` adds an amber card with its fix outside the UI. They are all listed in the Interlocks view.

`verdict`: blocked if any item or extra card is blocked; else needs-you if any; else ok (`Ready to run`).
`canRun`: no blocked item, plan current, machine idle, pressure off; items 4 and 9 may be not ready (4 is asked in
the confirm, 9 is re-checked by the run). The Run button style follows §8.1.4.

### 9.2 Freshness (fixes the stale "All interlocks pass")

Re-read `GET /api/plan/{id}` (debounced 300 ms) whenever any of these change in `/api/status`:
`machine.enabled`, `machine.homed`, `dispense.profile.controllerConnectedAtMs`, `profile.fingerprint`,
`profile.startAngleSetAtMs`, any `surfaces[*]` fit or `boundToBoard`, `purgeRow.usedSpots/resetAtMs/defined`,
`needle.setAtMs/changedAtMs/anchor`, `cleanPadCheck.enabled`, a task ending; and every 10 s while the page is
visible. While a re-read is pending, the Checks header shows `checking…` and Run is disabled for at most that time.

**Plan restore**: on load use `status.run.planId` (running or last task; strip `-subset-…` only for display),
else `localStorage['il.planId']`; `GET /api/plan/{id}` restores preview, summary and clean-check result; 404 →
plan chip `Plan forgotten (OpenPnP restarted): plan again`. Optional `GET /api/plans` (§17) lists recent plans.

### 9.3 Severity rules (one vocabulary everywhere)

Glyph + colour + word, never colour alone. **OK** green ✓ · **Needs you** amber ! (a quick action here: confirm,
wipe, tick, check again, plan again, home) · **Blocked** red ✕ (refused until setup work is done, or a fault) ·
**Off / not fitted** grey – (I5, I8, I10 off, pressure off; never green) · **Note** blue i (I2, I6 text) ·
**You** blue ring ✓ (an operator attestation, never shown as sensed) · **Energised** solid burnt orange with a
pulsing dot (pressure starting/on/stopping, any task moving or dispensing, homing).
Engine interlock states map: `ok`→OK, `blocked`→per the table of §9.1 (default Blocked), `warning`→Needs you,
`off`→Off, `info`→Note. **I10 overlay**: if the shown plan's last clean check found paste, I10 displays as Needs
you (`paste found on 2 pads at 13:09`) even when the engine reports `ok` (which only means "camera present").
A task's status (`Running`, `Finished`, `Stopped`, `Refused`, `Ended by an error`) is always shown apart from its
outcome (`all 160 dots`, `61 of 160 dots`, `2 pads not clean`).

### 9.4 Attestations (no sensor)

Stored in `localStorage['il.attest']` as `{key, value, atMs}`: `regulator` (psi), `paste-life`, `prime-seen`,
`test-dot-seen`. Shown as `you, 13:04`. The regulator attestation expires when the profile psi changes (for good:
setting the psi back does not revive it), after `I changed the needle`, or after 8 h; paste life after 8 h.
`hands-clear` is never stored. The Setup page head says these ticks live in this browser only (all its tabs: a
tick or a `new seat` answer in one tab counts in the others at once).

---

## 10. Function and API map (every must-keep function)

| Function | API | Primary place (ids) | Also from |
|---|---|---|---|
| UI files | `GET /`, `/static/app.js`, `/static/app.css`, `/static/favicon.svg` | `index.html` contains `id="btn-stop"` | — |
| Status poll | `GET /api/status` (0.7 s) | strip, every view | — |
| STOP | `POST /api/stop` | strip `#btn-stop` `[data-testid=stop]`; F9 | panel copy `[data-stop-copy]` while energised |
| Connect (OpenPnP Start) | `POST /api/machine/enable` | strip `#btn-start` (when not connected) | Recovery step 2, Machine view |
| Home | `POST /api/machine/home` | strip `#btn-home` (when not homed; two-press) | Recovery step 4, Machine view `Home again` |
| Machine detail | `GET /api/machine` (optional; status covers it) | Machine view | — |
| Board list | `GET /api/boards` | Run › Board `#board-select`, `#btn-board-refresh`, `#board-file` | — |
| Board position (file boards) | localStorage `il.boardloc.<file>` | `#board-x/y/z/rot`, capture buttons, re-seat question | — |
| Plan | `POST /api/plan` | Run › Board `#btn-plan`; page head `#btn-plan-again` | plan chip, Probe `Save … and plan again`, fix-its |
| Plan preview / summary / interlocks | plan response, `GET /api/plan/{id}` | `#plan-svg`, `#plan-summary`, `#plan-interlocks`, `#plan-chip` | Interlocks view |
| Flagged pads, warnings | plan response | Checks needs-you cards (pad types grouped by type), callouts | Interlocks view |
| Clean-pad check | `POST /api/plan/{id}/clean-check` | Checks `#btn-clean-check`, `#clean-result`; red pads on `#plan-svg` | Camera view |
| Run board + confirm | `POST /api/run {planId, maxDots?}` | Checks `#btn-run` → `#dlg-run` → `#btn-run-go` | — |
| First N dots | `maxDots` | `#btn-run-test` → `#run-maxdots`; TEST style; reset after a run | confirm `#run-test-line` |
| Finish remaining pads | `POST /api/run {planId, padIds}` + `GET /api/runs/last` | Recovery `#btn-run-rest` | Records › board detail |
| Progress | `run` in status | `#run-live`, `#run-bar`, `#run-phases`, `#run-timeline`, done dots in `#plan-svg` | verdict cell, nav, tab title |
| Last board / last task | `GET /api/runs`, `lastRun` | `#run-last` tile, Result step `#run-result` | Records › Boards |
| Run and event history | `GET /api/runs?limit=`, `GET /api/runs/last`, `GET /api/events?limit=` | Records | Activity view |
| Pressure hold | `POST /api/pressure/start`, `/stop` | Setup › Pressure `#btn-pressure-on/off`, `#pressure-state` | Pressure view (any workspace), confirm `Pressure on to adjust` |
| Prime | `POST /api/prime {rotationDeg}` | Setup › Pressure `#prime-rot`, `#btn-prime` | — |
| Test dot | `POST /api/test-dot {rotationDeg}` | `#testdot-rot`, `#btn-testdot`, `#dispense-result` | — |
| Camera snapshot | `GET /api/camera/top.jpg?w=` | Setup › Top camera `#cam-img`, `#btn-cam`, `#cam-auto` | Camera view (pad overlays) |
| Places | `GET /api/locations`, `PUT /api/locations/{name}` | Setup › Places `#places-table` `tr[data-place] input[data-axis] button[data-action=save]`, `#place-new`, `#btn-place-add` | Head view |
| Needle changed | `POST /api/needle/changed` | Needle card `#btn-needle-changed` (two-press) | — |
| Needle frame by touch | `POST /api/needle/anchor {surfaceZ, needleContactZ}` | `#anchor-n1`, `#anchor-n2`, `#btn-anchor` | I4 fix-it |
| Needle frame from K | `POST /api/needle/anchor {surfaceZ, needleContactRawSum}` | `#anchor-k`, `#btn-anchor-k`; locked with `#anchor-k-why` while `status.dispense.needle.kRefusal` (a failed Z check of this home, a needle change): the card switches to Both tips | — |
| Needle offset (confirm/manual) | `POST /api/needle/offset {offsetMm}` | `#btn-needle-offset` (`Confirm −2.590 mm`), `#needle-offset` | Checks item 3 fix-it |
| Needle XY trim | `PUT /api/config {needleXyTrim}` | Needle card › Edit trim: `#trim-dx/dy/note`, `#btn-trim`, `#trim-state` | — |
| Surfaces | `GET /api/surfaces`, `PUT /api/surfaces/{name}`, `DELETE /api/surfaces/{name}` | Run › Probe (board, every seat), Setup › Probed surfaces (blank; advanced `#surf-name`, `#surf-points`, `#btn-surf-save`) | I4 fix-its |
| Purge row define | `PUT /api/config {purgeRow}` | Setup › Purge row `#pr-*`, `#btn-pr-save` | — |
| Purge row reset | `POST /api/purge-row/reset` | `#btn-pr-reset` (two-press) | Checks item 8, Recovery step 5, Purge view, Setup band |
| Interlock list | `interlocks` in status | Interlocks view `#interlock-list li.<state>` | verdict cell, `All 13` |
| Settings | `PUT /api/config {maxContinuousOnTimeS, minPadTypeStatus, cleanPadCheck, valveSettleMs, pumpToValveSettleMs}` | Setup › Settings `#set-*`, `#btn-settings` | — |
| All settings (advanced) | `GET /api/config` + partial `PUT /api/config` | `#set-json`, `#btn-settings-json` | — |
| Dispense profile | `PUT /api/config {profile}` | Profile `#pf-*`, `#btn-pf-save` | recipe token everywhere opens it |
| Start angle confirm | `POST /api/screw/start-angle {}` | Profile `#btn-pf-angle-confirm` | strip cell, Setup band, Checks item 2, Recovery step 3 (`data-fix="I11-confirm"`) |
| Start angle set | `POST /api/screw/start-angle {startAngleDeg}` | Calibrate › Start angle `#pf-angle`, `#btn-pf-angle-set` | Profile `Measure again` |
| Pad types | `GET/POST /api/pad-types`, `GET/PUT/DELETE /api/pad-types/{key}` | Profile `#profiles`, `#dlg-padtype`, `#dlg-history`, side panel | PLAN fix-its |
| Pad rule | `PUT /api/pad-types/{key}/rule` | pad-type side panel `#pt-rule-*`, `#btn-pt-rule-save` | — |
| Packages | `GET /api/packages` | `#pt-footprint` picker in the pad-type dialog | side panel `matched packages` |
| Set up / Validate guide | UI only | pad-type side panel → Calibrate `#setup-guide` | — |
| Calibrations | `GET /api/calibrations`, `GET /api/calibrations/{id}/ladder` | Calibrate records, Records › Calibrations | — |
| Scale shots | `POST /api/calibrations/shots-run`, `/scale-shots` | Calibrate › Scale shots | — |
| Dot batch | `POST /api/calibrations/scale-dots` | Calibrate › Dot batch | — |
| Dot ladder | `POST /api/calibrations/dot-ladder` | Calibrate › Dot ladder | — |
| Touch-off | `POST /api/calibrations/touch-off` | Calibrate › Touch-off | Needle card `Touch-off ›` |
| Route list | `GET /api` | not shown (diagnostics) | — |
| Deep links | hash | §3 | Alt+1…5 |

---

## 11. Components and their states

| Component | Anatomy | States |
|---|---|---|
| State cell `.cell` | glyph · eyebrow · value · optional chevron | `--ok`, `--need`, `--block`, `--off`, `--info`, `--plain`, `--live` (energised); offline: all machine cells `--off` with value `unknown since 14:31:07` |
| Inline action `.cell-act` | 44 px teal button after a cell | shown/hidden by state; disabled never (hidden instead) |
| STOP `.stop` | word · keycap · subtitle | idle · hover · focus · `STOPPING…` · (never disabled) |
| Button `.btn` | 46 px (sm 40, lg 60), 10 px radius | default · hover · focus ring · disabled (`--disabled-bg`, `--ink-disabled`, **plus a visible reason line `.reason`**) · `--primary` (filled teal) · `--hold` (outlined teal: conditional primary) · `--quiet` · `--press` (two-press pending) |
| Two-press | any `[data-two-press="verb"]` button | idle → first press: `--press` style (striped amber tint, 2 px `--press-line` border, dark text, **never a solid fill**), label `Press again: <verb>`, 4 px bar shrinking over 4 s → second press (≥ 300 ms later, not a key auto-repeat) runs it; Esc, blur, another action or timeout cancels |
| Capture button `.capture` | icon · `Use live N1 Z` · **live value** | live value updates on each poll; disabled with reason when not connected |
| Field `.field` | label line (name left, unit right) · 46 px input | clean · focused · dirty (3 px amber left bar) · invalid (red border + message under it) · read-only (derived) |
| Card `.card` | head (title, sub, state at right) · body · foot | normal · needs-you (state amber) · locked (foot shows `Locked while the board runs`) · banner inside for card-level refusals |
| Readiness card `.ri` | glyph · title (+NEXT) · detail · I-code · fix-its | need · block · checking (spinner text `checking…`) |
| Ready tile `.rt--one` | glyph · title · value | ok · you |
| Segment bar `.segbar` | 9 segments, fixed order | per segment `ok/you/need/block/off` |
| Stepper `.steps` | 5 steps · glyph · title · subline | done (ok) · need · block · live · todo (number) · current (`aria-current="step"`, teal underline) |
| Plan chip `.plan-chip` | glyph · text | current · stale (need) · forgotten (need) · needs seat (need) · live · ended (block) |
| Recipe token `.recipe` | name · psi · turn · dwell · gap · purge | normal · changed since planning (amber with text) |
| Purge strip `.purge` | one cell per spot | used (grey fill) · this board (blue outline on blue tint) · free (outline); wraps into rows of 24 above 30 spots |
| Tile `.tile` | eyebrow · 31 px number + unit · context line | normal · big (44 px while running) |
| Phase list `.phases` | glyph · title · detail · duration | done · now (energised tint) · todo · cut (stopped here, red) |
| Dialog `.scrim` + `.dialog` | head · scrolling body · pinned foot | in-page, `aria-modal="false"`, starts under the strip; focus trap that **excludes** the strip (Tab from the last control goes to STOP) |
| Tick `.tick` | 26 px checkbox in a 52 px row · text · `you, 13:04` | unticked · ticked (teal tint) · sensed (status glyph instead of a checkbox) |
| Refusals | inline line under the action `Refused: <why> — <fix button>` · card banner (4 px left border) · Activity log | the I-code prefix of a 409 message (`I10: …`) becomes a link to its fix |
| Toast `#toast` | at most 3 lines, top-left of the main column (over the page title, never over the live tiles or the board decision), ≤ 4 s | never over the panel or STOP; always repeated in Activity; never the only record of a safety event |
| Sticky message area `#msg-area` | top of the main column | holds offline / vent_failed / STOP NOT CONFIRMED bands; replaces the old top-of-page `#banner` |
| Upload dialog `#dlg-board-import` (as built, `boardimport.js`) | drop zone and file picker · reading (Cancel) · preview: the board drawn, what was found, warnings in four groups (Blocks dispensing, Registration, Check in the preview, Notes), packages and their pad types, the name and slug · saved: pad types to add, `Use this board`, `Upload another` | choose · reading · preview · saving (Esc and Cancel do nothing) · saved · refused (reason + `Choose another file`); `exists` asks a two-press replace; attach offered when a listed board file matches |
| Pad inspector (panel view `pad`, `padinspect.js`) | the part drawn to scale (its paste openings, its dots at their real size in path order), the pad type and the rule that chose it in a sentence, the stencil-equivalent amount, flags and why a pad is blocked, `Edit pad type Chip 0603` (or `New pad type for <package>`), prev / next pad | opens on a pad or part click; Esc back |
| Path preview row `#run-preview` (`pathpreview.js`) | `#pv-play` · scrub `#pv-scrub` · speed `#pv-speed` · readout `#pv-readout` (`Dot 37 of 160 · R12.1 · ½ turn · Z 2.91 mm · 0:41 of 1:36`) · `#pv-reset` | collapsed until it starts; hidden while a task runs or after a STOP; times from the plan's estimate, else illustrative (50 mm/s, 0.6 s per dot) |
| Board thumbnails `#board-thumbs` | one row of board pictures from `art.thumb` (blob: images) | a click chooses the board |

---

## 12. Design tokens (port `src/final.css` `:root` and `[data-theme="dark"]` verbatim)

```css
:root {
  --font-ui: system-ui, -apple-system, "Segoe UI", Roboto, Ubuntu, Cantarell, "Noto Sans", "Helvetica Neue", Arial, sans-serif;
  --font-code: ui-monospace, "SF Mono", "Cascadia Mono", "Segoe UI Mono", "DejaVu Sans Mono", "Liberation Mono", Consolas, monospace;
  --canvas:#f2f5f4; --surface:#ffffff; --tile:#f5f7f6; --sunken:#eaefed; --line:#dde4e2; --line-2:#c6d0cd;
  --ink:#142a2e; --ink-2:#44575c; --ink-3:#5d6f74; --ink-disabled:#6e7b80; --disabled-bg:#eef1f3;
  --accent:#176f59; --accent-hover:#105841; --accent-tint:#e4f1ec; --accent-ink:#145e4b; --on-accent:#fff; --mark:#173f3b;
  --data:#1f5fd1; --data-tint:#e7eefc;                       /* paste and dots only, never actions */
  --ok:#166b30;   --ok-bg:#e5f4e9;   --ok-line:#a9d4b5;   --ok-dot:#1f7a3d;
  --need:#7a5000; --need-bg:#fff3d1; --need-line:#e3b54a; --need-dot:#e9a23b; --need-glyph:#3d2600;
  --block:#b42318;--block-bg:#fdeae7;--block-line:#efa49a;--block-dot:#b42318;
  --off:#55626a;  --off-bg:#eef1f3;  --off-line:#d3d9dc;  --off-dot:#75838a;
  --info:#1d4ed8; --info-bg:#e9f0fd; --info-line:#b5c7f5; --info-dot:#2f62e0;
  --live:#a84c08; --on-live:#fff;    --live-tint:#fdf0e3; --live-line:#f0bf8f;   /* energised */
  --press-a:#fff3d1; --press-b:#ffe6a6; --press-line:#b7791f; --press-ink:#3d2600;  /* two-press pending */
  --stop:#c62828; --stop-hover:#a61b1b; --stop-edge:#7f1d1d; --on-stop:#fff;
  --focus:#2563eb;
  --pv-bg:#f7f9f8; --pv-board:#edf2ef; --pv-board-edge:#c9d5cf; --pv-grid:#dfe7e3; --pv-pad:#e6dccb;
  --pv-pad-edge:#9c8762; --pv-pad-pasted:#d9e3f7; --pv-path:#a9b8c0; --pv-path-done:#7d9be0;
  --r-xs:6px; --r-sm:8px; --r-md:10px; --r-lg:14px; --r-xl:18px; --r-pill:999px;
  --e1:0 1px 2px rgba(16,24,40,.05); --e2:0 6px 20px rgba(16,40,48,.08),0 1px 2px rgba(16,24,40,.05);
  --e3:0 28px 72px rgba(16,24,40,.26),0 2px 8px rgba(16,24,40,.10); --scrim:rgba(20,42,46,.48);
  --strip-h:80px; --nav-w:212px; --panel-w:400px; --stop-w:232px;   /* ≤1360: 84px, 388px, 216px */
}
[data-theme="dark"] {
  --canvas:#0e1416; --surface:#151d20; --tile:#1b2528; --sunken:#111a1c; --line:#27343a; --line-2:#364650;
  --ink:#e7eeef; --ink-2:#b2c0c4; --ink-3:#93a3a8; --ink-disabled:#8595a0; --disabled-bg:#1d272b;
  --accent:#2aa486; --accent-hover:#34b796; --accent-tint:#10302a; --accent-ink:#6fd3b8; --on-accent:#05201a; --mark:#2aa486;
  --data:#6f9cf5; --data-tint:#15233f;
  --ok:#6fd28f; --ok-bg:#11291a; --ok-line:#24553a; --ok-dot:#3fae66;
  --need:#f3c565; --need-bg:#2c2209; --need-line:#6d5313; --need-dot:#e9a23b; --need-glyph:#2a1900;
  --block:#ff8f80; --block-bg:#35140f; --block-line:#7a2c22; --block-dot:#e5483a;
  --off:#a1aeb3; --off-bg:#1d272b; --off-line:#33424a; --off-dot:#6b7b82;
  --info:#93b2ff; --info-bg:#131d36; --info-line:#2c3f73; --info-dot:#4a78f0;
  --live:#b0560a; --live-tint:#2c1a0b; --live-line:#6b3e14;
  --press-a:#3a2c0c; --press-b:#4a3810; --press-line:#e0a63a; --press-ink:#ffe3a3;
  --stop:#d32f2f; --stop-hover:#e53935; --stop-edge:#000; --focus:#7aa7ff;
  --pv-bg:#10181a; --pv-board:#142124; --pv-board-edge:#2b3d42; --pv-grid:#1c2b2f; --pv-pad:#4a4231;
  --pv-pad-edge:#a8916a; --pv-pad-pasted:#1f3157; --pv-path:#3c5058; --pv-path-done:#4f74c9;
  --scrim:rgba(0,0,0,.6);
}
```

- Contrast (WCAG 2.x, measured in the concepts): ink 13.7:1 on canvas; ink-2 7.6:1; ink-3 5.3:1 on white /
  4.8:1 on canvas (the lightest text allowed); status text on its tint 5.7–6.4:1; white on accent 6.1:1, on
  energised 5.7:1, on STOP 5.6:1; press-ink on press tint ≥ 7:1. Disabled labels (3.9:1) always carry a visible
  reason line. Dark theme pairs ≥ 5:1 except disabled.
- Theme: light by default; dark follows `prefers-color-scheme` unless the Light/Dark switch stored
  `localStorage['il.theme']`. Set `data-theme` on `<html>` before first paint (in `app.js`, loaded with `defer`;
  the default light avoids a flash).
- Type: UI stack everywhere with `font-variant-numeric: tabular-nums` on `body` (numbers stay steady); the code
  stack only for G-code and engine text. Sizes: eyebrow 12 px / 700 / +0.09 em caps; cell key 10.5 px; meta 13–14.5
  px; body and buttons 15 px; card title 17 px / 650; h1 25 px / 700; tile number 31 px / 650; running tile 44 px;
  readiness count 32 px; STOP 30 px / 800.
- Space: 4 px base, 8 px rhythm; card padding 16–18 px; main padding 20/24 px; gaps 14–16 px.
- Targets: ≥ 44 px everywhere an operator acts (inputs 46, buttons 46, primary 60, ticks 52 rows, cells 56,
  STOP 80); ≥ 8 px between adjacent targets.
- Motion: 120 ms ease-out on hover/press only; panel swap 150 ms cross-fade; pulses only on energised dots and the
  next-dot halo (2 s), none under `prefers-reduced-motion`; numbers never animate; countdowns tick once a second.

**As built (board import):** `board.css` adds the board palette, one for both themes (only the stage and the board edge
follow the theme): `--pv-a-sub`, `--pv-a-cu`, `--pv-a-mask` (slate, at `--pv-a-mask-op` 0.86), `--pv-a-bare`,
`--pv-a-silk`, `--pv-a-hole`, `--pv-a-edge`, `--pv-a-fab`, `--pv-a-ref`, `--pv-a-fabref`, `--pv-a-paste`; and the
overlay marks restated on the board under `.pv--art` with a two-tone casing: `--pv-o-ap`, `--pv-o-dot`, `--pv-o-next`,
`--pv-o-block`, `--pv-o-check`, `--pv-o-probe`, `--pv-o-path`, `--pv-o-path-q`, `--pv-o-trail-q`, `--pv-o-ghost`,
`--pv-o-case-d`, `--pv-o-case-l` (≥ 3:1 on exposed copper, casing ≥ 6.5:1 elsewhere). The art's mask and silkscreen
colours come from the KiCad file (slate, green, red, blue, purple, black, white, yellow).

## 13. Copy guidelines and exact key texts

Rules: plain words, operator's point of view; every number has a unit (`5.62 mm`, `298.0°`, `18 psi`,
`250 ms`, `600 s`); signed values always show the sign (`−2.590`, `+0.086`, real minus `−`); fixed decimals
(mm 0.01 for places and positions, 0.001 for offsets and residuals; angles 0.1°; psi as entered, no trailing
`.0`: 18 → `18`, 18.5 → `18.5`); 24 h times (`13:06`, safety events `14:19:42`) with a relative age in `title`;
counts as `n of N`; DPH always as `dots only` and `overall`; jargon in `<abbr title>` (DPH, K, I1–I11, touch-off,
purge row, start angle, bright share, frame offset); "Connect" (never "Start") for OpenPnP's Start; "profile" only
for the dispense recipe; "pad-type status" for the gate; no marketing copy; no exclamation marks.

| Where | Exact text |
|---|---|
| STOP button | `STOP` · `F9` · `motion · vent · screw off` |
| STOP title | §5 |
| STOP pending | `STOPPING…` |
| STOP not confirmed | `STOP NOT CONFIRMED: press OpenPnP's Stop button or switch the machine off at its power switch now. Retrying every second.` |
| Stop sequence (running panel) | `STOP sends M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B: quick stop, dispenser valve and pump off, pick valve and pump off, screw motor off.` |
| Recovery card | `Stopped 14:19:42` · `By this page's STOP (F9). OpenPnP confirmed it. Sent before the next line:` · `M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B` |
| Run confirm regulator | `Set the regulator to {psi} psi (profile {name})` — e.g. `Set the regulator to 18 psi (profile 18 psi half turn)` |
| Run confirm sub | `There is no pressure sensor: read the gauge. 16 psi starved the screw, 20 psi drooled.` |
| Run confirm ticks | `The gauge reads {psi} psi` · `Paste is within its working life` · `Hands and tools are clear of the head` |
| Run confirm test line | `TEST: only the first {n} of {total} dots` · `Only the {n} pads left of the stopped run` |
| Run confirm button | `Run {n} dots · ≈ {m:ss}` · disabled `Tick {k} more to run` |
| Run reason (soft) | `{n} needs you. Run anyway: the run checks every pad first (≈ 3 min) and refuses while paste is left.` |
| Run reason (blocked) | `Finish {k} checks first: {names}.` |
| Locked control | `Locked while the board runs` · `Pressure is held: switch it off first` (+ inline `Pressure off`) · `Not connected: connect first` |
| I11 question | `Was the controller switched off since {time}?` · `No, it stayed on: {angle}° still holds` · `Yes, it was off: measure again` |
| Offline | `No answer from OpenPnP for 3 s: is it running? The web STOP cannot reach the machine: use OpenPnP's Stop button or switch the machine off.` |
| vent_failed | `Pressure is NOT off: the valve did not confirm. Press Pressure off or STOP again. If the firmware watchdog is armed, it vents by itself.` + `lastError` |
| Lost reply to a moving command | `Outcome unknown: look at the machine.` (never re-sent) |
| Clean result first sentence | `All {N} pads are clean ({t}).` · `{k} of {N} pads clean, {m} with paste left ({t}).` · `0 of {N} pads clean: the board position looks wrong (solder mask under every box), not {N} dirty pads.` · `No pad was checked: {reason}.` |
| Plan forgotten | `Plan forgotten (OpenPnP restarted): plan again` |
| Simulator | brand tag `Simulator`; I10 `Off (simulator): the simulated camera is a static picture`; run warning `simulated controller: screw step count taken as 0` |

**Interlock fix-it texts** (Interlocks view and needs-you cards; engine text shown verbatim under the plain
line):

| Code / case | Plain line | Fix-it (target) |
|---|---|---|
| MACHINE not enabled | `Not connected to the controller.` | `Connect` (here) |
| MACHINE not homed | `Not homed: positions are unknown (always after STOP).` | `Home` (here, two-press) |
| CONFIG `<problem>` | `Dispenser settings are not valid: {problem}.` | `Open the field` (Profile or Settings, field focused) |
| I1 warning | `OpenPnP's own Stop button does not switch the pneumatics off. Missing: {valveOff/pumpOff/quickStop}.` | outside the UI: `Close OpenPnP and install the machine.xml patch (dist/README.md). Until then use this page's STOP.` + `Show lines` (detail.lines vs expected) |
| I2 | `The screw is zeroed (G92 B0) at the start of every run.` | — (note) |
| I3 | `N2 has nozzle tips, so a pick could fire the paste pneumatics.` | in OpenPnP: `Remove N2's compatible nozzle tips / unload its tip` |
| I4 frame missing / needle changed | `The needle Z frame is not set (or the needle changed after it).` | `Open Needle Z frame` |
| I4 not touched off | `Needle offset not confirmed since the last needle change or frame.` | `Confirm frame offset −2.590 mm` (here) · `Run a touch-off` |
| I4 drift | `Needle offset {x} mm is {d} mm from the frame (limit 1.00 mm).` | `Record the frame again` · `Touch off again` |
| I4 place Z | `Place '{name}' Z is {d} mm from the frame surface: probably taken with the needle.` | `Retake Z with N1` (Places row focused) |
| I4 surface missing / unfit | `No '{s}' surface` / `'{s}' cannot be fitted` / `'{s}' is not flat enough (worst {r} mm, limit 0.04)` / `'{s}' is {d} mm from the frame surface` | `Probe {s}` with the bad point highlighted |
| I4 coverage | `{n} dots lie outside the probed '{s}' surface.` | `Add probe points near them` (dots highlighted) |
| I4 not tied / other registration | `The board surface belongs to another seat of this board.` | `Probe this seat` (Run › Probe, empty point set) |
| I4 plan without/on another surface | `This plan was made before the surface was saved.` | `Plan again` (here) |
| I5 off | `Barrel pressure sensor: not fitted.` | — |
| I6 info | `Set the regulator to {psi} psi for profile {name} (no sensor).` | `Pressure on to read the gauge` · `Reads {psi} psi` |
| I7 plan | `Estimated pressure on-time {t} is over the limit {limit}.` | `Run fewer dots` · `Raise the limit in Settings` |
| I7 hold tripped | `The pressure switched off by itself after {limit}.` | `Pressure on` again |
| I8 off | `Paste working life is not tracked: check the paste date.` | tick in the run confirm |
| I9 not set / short | `The purge row is not set.` / `Only {n} unused purge spots left, {m} needed.` | `Set the purge row` · `Wipe the row, then: Wiped: reset row` (two-press) |
| I10 no camera | `Clean-pad check is on but there is no top camera.` | in OpenPnP: check the camera · in the simulator: `Switch the check off in Settings` |
| I10 paste found | `Paste on {k} pads at {t}.` | `Wiped: check again` · `Show on board` |
| I11 reconnect / never set | `The controller reconnected after the start angle was set.` / `The start angle was never set.` | the two answers · `Measure the angle` |
| PLAN no dots / blocked pads | `This plan has no dots.` / `{n} pads cannot be dispensed: {types}.` | `Choose another board` · per type: `Set its status`, `New pad type` (prefilled `^<package>$`), `Lowest pad-type status` |
| Machine homes first (as built) | `Ready to run · homes first (≈ 25 s)` | none: Run homes by itself |
| I4 Z before the home (as built) | `… The N1 heights predate this home: Run checks Z first, one N1 touch on the {name}.` | `Check Z` (two-press) |
| I4 surface, no seat (as built) | `No board surface for this seat yet: register the board (fiducials, then N1 touches the board).` | `Register board` · `Do it by hand` |
| REG / PROBE refusals (as built) | the engine's text (`AUTO_REGISTER_DESIGN.md` §6.2) | `Look with the camera` · `Change board` · `Plan without fiducial correction` · `Register again` · `Do it by hand` · `Probe the blank…` |
| Upload while a task runs (as built) | `Upload the board when the run has finished.` (also while a pad calibration is between its engine steps) | — |
| I4 Z moved (as built, final round) | the engine's text: `Z moved 0.80 mm at the home of 14:02: … Record the needle frame again with both tips (Setup › Needle Z frame), probe the blank, take the places' Z again with N1 (Setup › Places), touch off and register the board again.` | the next step only, in turn: `Open Needle Z frame` (the card switches to Both tips, locks From K and says why: `status.dispense.needle.kRefusal`) · `Probe the blank…` · `Retake Z with N1 (waste)` per place in `status.machine.zCheck.places`, up to 3 (Places flags each Z, its button reads `Retake Z` and saves once N1 took the Z, the same value too; the card state `Z moved · retake waste, blank`) · `Run a touch-off` (not the frame's own offset: Z moved at this home) · `Register board` / `Do it by hand` |
| Register: board top under the probe's reach (as built, final round) | `the board top Z 3.500 lies 2.000 mm under the needle-frame surface 5.500, … it cannot reach that height. …` | `Board step` · `Needle Z frame` · `Do it by hand` |
| Locked by the engine's own 409s (as built, final round) | `A board file is being read (an upload takes up to about 20 s): wait for it to finish` (every engine action) · `OpenPnP is busy (homing or moving): wait for it to finish` (what uses the machine) | — |
| Clean-pad check refused at its start (as built, final round) | `Refused 14:05: the pressure hold is on: Pressure off first.` on the Pads clean card | `Check pads` |

**Missing paste profiles and the pad calibration (as built, pc-web; PC §13):**

| Where | Exact text |
|---|---|
| Strip, a job's engine step | `Pad calibration · test dots 14/48 · ≈ 2:50 left` (`Pad calibration` alone when the strip is narrow); between steps `Pad calibration · deciding`; at the readings `Waiting for you · weigh` (a click opens the pad-cal view) |
| Locked control while a job runs | `Locked while the pad calibration runs`; while it waits for readings, a new calibration: `A pad calibration is waiting for its readings: finish or cancel it first` |
| Run › Checks item 5 | `Blocked · needs a profile`, `21 pads cannot be dispensed: 12 need a profile, 9 not with the 22G tip (WSON-8…)`; tip only: `9 pads cannot be dispensed with the 22G tip: WSON-8… (9 pads).` + `Dispense the rest (Setup › Settings › Refuse flagged pads)` |
| Upload preview warning (`NEEDS_PROFILE`) | `4 packages need a profile: TestChip_A (no pad type), SW_SPST_3.9x2.9mm (no pad type), Test_Strip_0.62x1.50 (no pad type), WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm (cannot be dispensed with the 22G tip). After saving, choose a suggestion for each in Profile › Needs a profile.` (one line per reason in the dialog, the tip's last) |
| Needs a profile lead | `These pads get no paste until each has a pad type. Use an existing type of the same size, or let the machine calibrate the suggested one.` |
| Reason badges | `no pad type` · `not calibrated yet` · `cannot be dispensed with the 22G tip` · the group's `short` for the others |
| Before start | the engine's sentence (`WEB_API.md` §3.5) and `You need: two clean weigh coupons on coupon1 and coupon2 (blank PCB, 40 × 30 mm, at least 3 g), the balance, a lint-free wipe and isopropyl alcohol.`; button `Calibrate · ≈ 5:45`; started `Pad calibration started: STOP stops it at once (F9).` |
| Readings prompt | `The dots are laid and the head is parked over the purge row. For each coupon: put it on the balance and type the reading; wipe the paste off with a lint-free wipe and isopropyl alcohol, let it dry, put it back on the balance and type the second reading. Coupon 1 has 136 quarter-turn dots (about 0.072 g); … Waiting for you since 21:25.`; echo `= 78 mg of paste (0.574 mg per dot)`, else `Type both readings in grams.`; `Save the readings` · `Skip weighing (the types stay provisional: boards still refuse these pads)` · `Cancel the calibration` |
| Result, first sentence | `Characterized 21:50: SW_SPST_3.9x2.9mm · 1.83 × 0.86 mm gets 2 half-turn dots per pad, about 2.29 mg per pad (0 % more than the 2.29 mg target); …; planned again.` · `Not characterized 21:58: … stays provisional: the two start angles of the half-turn dots differ 19 % in area (at most 13 % allowed); …` · `Measured 22:02: 62 half-turn dots checked by the camera: the dots landed 0.20 mm off on average (at most 0.15 mm allowed): check the needle offset.` · `Failed 22:03: Coupon 1 is not at its place: …` · `Stopped 13:06 during weigh: the blank results were kept; the coupons still need weighing until 21:13.`; the control's drift `The validated half turn now gives 1.15 mg, 9 % more than the 1.06 mg weighed on 2026-10-27.` |
| Decider check lines (shown as the engine words them) | `Amount 2.37 mg per pad (target 2.29 mg per pad, -25 % to +10 %)`, `overhang 0.12, dot gap 2.83, copper gap 2.99 mm (limits overhang 0.20, gaps 0.30 / 0.15 mm)`: the engine still writes a hyphen for the minus and a single mm for the list (open UI need: `−25 %`, a unit on every number) |
| Calibrate › Pad calibration | `Lower-left corner and size. Default: 40 × 20 mm at the blank place, off the purge row.` · `The calibration area is free again: 14.0 mm left.` · `New paste noted: weighings and camera results from before stop counting.` · `Wipe the area with a lint-free wipe and isopropyl alcohol first.` |

## 14. Keyboard and pointer

| Key | Action |
|---|---|
| `F9` (any modifiers) | STOP, from anywhere: captured on `window` in the capture phase, `preventDefault()`, works with focus in inputs and with dialogs open |
| `Enter` on focused STOP | STOP |
| `Space` on focused STOP | **nothing** (suppressed on keydown and keyup) |
| `Space` anywhere else | normal browser behaviour only; never bound to a shortcut |
| `Esc` | closes the top-most thing: two-press pending → popover/menu → dialog (same as Cancel) → pushed panel view (back). **Never stops the machine.** |
| `Alt+1…5` | workspaces |
| `Enter` in a form field | submits only that card's Save button (`data-enter-submits`); never a moving or dispensing action |
| `Tab` order | STOP → skip link → strip cells and inline actions → nav → main → panel; inside an open dialog Tab cycles the dialog and STOP |
| `?` (not in a field) | shows the key sheet |

Pointer: every action is a click/tap (no hover-only information, no long-press, no drag needed). Machine-moving or
dispensing actions are two-press or behind the run confirm: Home, Clean check (camera tour), Prime, Test dot,
Touch-off, Dot ladder, Dispense shots, Pressure on away from the waste; also destructive saves: purge reset,
Delete surface, I changed the needle, Retire, Discard profile edits. Single press: STOP, Connect, Pressure off,
Pressure on over the waste, saves, navigation. Two-press ignores key auto-repeat (`event.repeat`) and needs
≥ 300 ms between presses.

## 15. Polling and render rules

- One poll loop: `setTimeout` chain, 700 ms after each response (not `setInterval`); status fetch timeout 2.5 s;
  other requests 10 s; STOP 2 s (§5). When `document.hidden`, poll every 2 s (titles still update).
- Heartbeat = now − last good status, rendered every 250 ms. After 3 s without a good status: offline state
  (machine cells grey `unknown since hh:mm:ss`, `#msg-area` offline band, every control except STOP disabled with
  that reason). Never keep stale green values.
- Single store `S`. Each region has a `render<Region>(S)` that compares the new view-model with the last one and
  patches only changed text/attributes (`data-bind` keys). No `innerHTML` rebuild of regions that contain inputs.
- **Never overwrite a focused or dirty field.** Forms track `dirty` per field against the server value (profile,
  purge row, trim, places, settings, JSON, board position, probe points); dirty fields get the amber bar and the
  card shows `Unsaved: Save · Discard`. Existing guards (`pfDirty`, `prDirty`, places, surfaces, trim, set-json)
  become this one mechanism. `#needle-offset` is refilled from the server unless focused or dirty (fixes the
  stale 0).
- Fixed layout: cells, tiles and numbers have reserved widths (`tabular-nums`, `min-width`), message lines have
  reserved height; the panel and main scroll internally; no element appears above content in a way that shifts it.
- Countdowns (pressure on-time and auto-off, elapsed, time left) interpolate on the client from the last poll's
  value and its receive time, ticking once a second, re-synced on every poll.
- Board SVG: built once per plan (`plan.dots`, `plan.pads`); polls toggle `done`, `pasted`, `dirty`, `next`,
  `check` classes from `run.doneDots` (incremental). ~5,000 elements is fine; beyond that switch to canvas.
- **As built (board import):** the drawing is its own SVG under the overlay, built once per art id per view (15–25
  merged paths, at most ~200 labels); polls, preview frames and hover repaint only the overlay; a re-plan of the same
  board changes only the art group's `transform`. The path preview runs on `requestAnimationFrame` only while playing
  and visible, at most 30 updates per second, rewriting the path only when the dot count changes. Drawings come
  through the browser's HTTP cache (immutable); the newest 8 are kept in memory. Every replaced view is destroyed.
  Gate (`boardview.playwright.cjs`, CPU throttled 4×): mobo's art drawn ≤ 1,000 ms; p95 frame ≤ 33 ms during a 16×
  preview; `update()` plus paint ≤ 16 ms.
- Loads on demand: `GET /api/boards` on Board step open and Refresh; `GET /api/calibrations` on Calibrate and
  after a task ends; `GET /api/runs` on load, after a task ends and on Records; `GET /api/events` every 5 s only
  while Activity or Records › Activity is visible; `GET /api/config` on Settings open and after saves; camera
  auto-refresh only while visible (IntersectionObserver + visibility).
- Task end (`run.runId` changes to not running): refresh plan, runs, calibrations, settings JSON (purge spots),
  show the Result step or Recovery, write Activity. Do not raise a top banner.
- Tab title: `▶ 96/160 · 0:44 left · Paste dispenser` · `■ Stopped · Paste dispenser` · `! 8 of 9 ready · Paste
  dispenser` · `Ready · Paste dispenser` · `✕ No answer · Paste dispenser`. Favicon swaps between three variants
  built as `data:` SVG URLs (allowed by `img-src`).

## 16. Element ids and the Playwright test

**Must exist:** `id="btn-stop"` with `data-testid="stop"` in `index.html` (ApiServerSimTest).

**Kept with the same meaning** (tests drive them): `btn-start`, `btn-home`, `pill-conn` (`Connected` /
`Not connected`), `pill-homed` (`Homed` / `Not homed`), `pill-pressure`, `pill-engine` (now the Board run
verdict), `toast` (`Settings saved`, `STOPPED…`), `board-select`, `board-file`, `board-x/y/z/rot`, `btn-plan`,
`plan-svg` (+ `rect.pad`, `rect.pad.dirty`, `circle.dot`, `circle.dot.done`), `plan-summary`, `plan-interlocks`,
`btn-clean-check`, `clean-result`, `run-maxdots`, `btn-run`, `run-live`, `run-bar`, `run-last`, `btn-prime`,
`prime-rot`, `btn-testdot`, `testdot-rot`, `dispense-result`, `btn-pressure-on`, `btn-pressure-off`,
`pressure-state` (`/^ON/`, `/^Off/`), `btn-cam`, `cam-img` (an `<img>`), `cam-empty`, `cam-auto`,
`places-table` + `tr[data-place] input[data-axis] button[data-action=save]`, `place-new`, `btn-place-add`,
`needle-state` (`/^Touched off/`), `btn-needle-changed`, `anchor-state`, `anchor-n1`, `btn-anchor-n1`, `anchor-n2`,
`btn-anchor-n2`, `btn-anchor`, `anchor-k`, `btn-anchor-k`, `needle-offset`, `btn-needle-offset`, `trim-state`,
`trim-dx`, `trim-dy`, `trim-note`, `btn-trim`, `surf-name`, `btn-surf-add`, `btn-surf-clear`, `surf-points`,
`btn-surf-save`, `surf-state`, `pr-state`, `pr-surface`, `pr-x0`, `pr-y0`, `pr-pitchx`, `pr-pitchy`, `pr-spots`,
`btn-pr-usexy`, `btn-pr-save`, `btn-pr-reset`, `pr-kv`, `interlock-list` (`li.ok/.warning/.blocked/.off/.info`),
`set-maxon`, `set-minstatus`, `set-refuseflagged`, `set-clean`, `btn-settings`, `set-json`, `btn-settings-json`, `cal-history-note`,
`setup-guide`, all `shots-*`, `ss-*`, `dl-*`, `sd-*`, `to-*` and their buttons, `cal-current`, `cal-table`,
`ladder-table`, `cal-result`, `pf-card`, `pf-title-name`, `pf-name`, `pf-psi`, `pf-dwell`, `pf-psi-note`, `pf-turn`,
`pf-suck`, `pf-gap`, `pf-turn-info`, `pf-rpm`, `pf-accel`, `pf-purge`, `pf-rpm-warn`, `pf-notes`, `pf-problems`,
`btn-pf-save`, `pf-angle-kv`, `pf-angle-warn`, `pf-angle`, `btn-pf-angle-set`, `btn-pf-angle-confirm`,
`btn-padtype-new` (`data-testid="new-pad-type"`), `show-retired`, `profiles-hint`, `profiles` (`.family h2`,
`tr[data-key]`, `.badge.<status>`, row button text `Edit`), `dlg-padtype`, `padtype-form`, `dlg-padtype-title`,
all `pt-*`, `pt-error`, `pt-cancel`, `pt-save`, `dlg-history`, `dlg-history-title`, `history-body`, `history-close`.

**New:** `heartbeat`, `chip-angle`, `chip-purge`, `msg-area`, `stop-unconfirmed`, `[data-stop-copy]`, `panel`
(`[data-view]`), `btn-plan-again`, `btn-board-change`, `btn-board-refresh`, `btn-board-use-cam`,
`btn-board-use-n1z`, `plan-chip`, `probe-targets`, `btn-probe-add`, `btn-probe-save`, `ready-segbar`,
`btn-run-test`, `dlg-run`, `run-confirm-psi`, `run-tick-gauge`, `run-tick-paste`, `run-tick-hands`,
`run-test-line`, `btn-run-cancel`, `btn-run-go`, `run-phases`, `run-timeline`, `run-result`, `btn-next-board`,
`btn-run-rest`, `btn-run-all`, `setup-needs`, `card-needle`, `card-surfaces`, `card-purge`, `card-pressure`,
`card-places`, `card-camera`, `card-angle`, `card-settings`, `surf-advanced`, `set-valvesettle`, `set-pumpsettle`,
`set-advanced`, `set-json-diff`, `btn-pf-discard`, `pf-diff`, `btn-pf-angle-measure`, `pt-rule-maxdots`,
`pt-rule-fit`, `pt-rule-largest`, `pt-rule-notes`, `btn-pt-rule-save`, `records-seg`, `records-runs`,
`records-events`, `records-cals`, `records-touchoffs`. Fix-it aliases elsewhere use `data-fix="<code>"` (never a
duplicate id). `#run-live` carries `data-label` and `data-status` mirroring `run.label` / `run.status`.

**New, as built (2026-10-29):**
- Register board (`il/ar-webui`): `reg-correction`, `reg-corr`, `reg-preview`, `reg-live`, `reg-phases`, `reg-tables`,
  `reg-fids`, `reg-probes`, `reg-result`, `reg-result-t`, `reg-stop-line`, `reg-foot`, `register-note`,
  `btn-register`, `btn-reg-continue`, `register-why`, `btn-reg-again`, `btn-reg-probe`, `probe-by-hand`,
  `probe-foot-hand`, `board-z-note`, `run-confirm-home`; Setup: `blank-phases`, `blank-targets`, `blank-result`,
  `blank-note`, `btn-blank-auto`, `blank-why`, `blank-by-hand`, `set-homebefore`; fix-its `data-fix` = `register`,
  `register-check`, `probe-by-hand`, `look-camera`, `plan-unregistered`, `board-step`, `check-z`.
- Board drawing (merged): `pv-picture`, `btn-pad-inspect`, `pv-play`, `run-preview`, `pv-scrub`, `pv-speed`,
  `pv-readout`, `pv-reset`, `pad-insp-*` (`svg`, `part`, `kv`, `rule`, `types`, `pads`, `dots`, `flags`, `why`, `cam`,
  `edit`, `prev`, `next`).
- Board upload (`il/bi-web-flow`): `btn-board-upload`, `board-thumbs`, `board-imported`, `btn-board-use-fid`,
  `board-preplan`, `dlg-board-import`, `board-import-*` (`drop`, `file`, `size`, `status`, `preview`, `found`, `warn`,
  `packages`, `paste-dnp`, `name`, `name-err`, `slug`, `exists`, `stale`, `attach`, `saved`), `btn-board-import-*`
  (`choose`, `cancel`, `save`, `attach`, `use`, `again`, `other`).
- Missing paste profiles (pc-web, `padprofile.js`, PC §13): Profile `pt-needs` (`-count`, `-src`, `-state`, `-lead`,
  `-table` with `tr[data-group][data-reason]` and `button[data-action=open|use|auto|manual]`, `-tip`, `-tip-s`,
  `-notes`, `-notes-s`, `-host`), `btn-needs-use-all`, `btn-needs-auto-all`, `needs-why`, `pt-filter-needs`; the
  suggestion card `pt-sugg` (`-back`, `-e`, `-n`, `-b`, `-reason`, `-text`, `-preview`, `-pvhost`, `-cap`, `-kv`,
  `-alt`, `-tip`, `-why`, `-edit`), `btn-pt-sugg-use`, `btn-pt-sugg-auto`, `btn-pt-sugg-manual`; the editor
  `pt-dotsteps`, `pt-dotsperpad`, `pt-calinfo`, side panel `btn-pt-auto`; the pad-cal view `pcal-*` (`note`, `needs`,
  `plan`, `groups-list`, `fixes`, `refusal`, `why`, `start-l`, `phases`, `live`, `ladder`, `ladder-box`, `ask`,
  `ask-text`, `ask-ref`, `coupons`, `coupon-k`, `full-k`, `empty-k`, `mg-k`, `pending`, `pf-k`, `pe-k`, `result`,
  `result-t`, `result-more`, `groups`, `crops`, `pred`, `pred-d`, `drift`, `foot-why`, `stopline`), `btn-pcal-*`
  (`start`, `readings`, `skip`, `cancel`, `again`, `weigh-now`, `finish`, `manual`, `plan`); the upload's
  `board-import-profile`; the pad inspector's `pad-insp-profile`, `btn-pad-insp-use`, `btn-pad-insp-auto`,
  `btn-pad-insp-manual`; Calibrate `cal-padcal` (`pca-map`, `pca-x0`, `pca-y0`, `pca-w`, `pca-h`, `pca-hint`,
  `pca-left`, `pca-far`, `pca-coupons`, `pca-pending`, `pca-paste-now`, `pca-wiped-reason`), `btn-pca-save`,
  `btn-pca-default`, `btn-pca-wiped`, `btn-pca-paste`, `cal-weigh` (`wd-type`, `wd-steps`, `wd-count`, `wd-plan`,
  `wd-pre`, `wd-reason`), `btn-wd-run`, `btn-guide-ladder`, `guide-target`, `cal-shots-older`; fix-its `data-fix` =
  `profile-use`, `profile-needs`, `pcal-open`, `pcal-area`, `pcal-wiped`. The board view's suggested dots are
  `g.pv-l-sugg circle.pv-sugg` (never `circle.dot`). Tested by `pad-profile.playwright.cjs` (PC §14.4).

**Removed:** `banner`, `banner-text`, `banner-close` (replaced by `msg-area` and inline refusals), `tab-run`,
`tab-dispense`, `tab-calibrate`, `tab-profiles` containers (replaced by `[data-workspace]` sections with ids
`ws-run`, `ws-setup`, `ws-profile`, `ws-calibrate`, `ws-records`), `tabbtn-*` (nav items get `tab-btn-<name>`),
`run-purge` (purge cell and Ready tile), `plan-stale` (plan chip), `plan-flags*` (needs-you cards).

**`webui-sim.playwright.cjs` changes in the same commit:**
1. Tabs: `data-tab` values `run|setup|profile|calibrate|records`; the tab loop checks `['run','#btn-plan'],
   ['setup','#pr-state'], ['calibrate','#cal-history-note'], ['profile','#pf-card'], ['profile','#profiles .family'],
   ['records','#records-runs']`. The scroll check scrolls `#main` (the page does not scroll) and still expects
   STOP at `y < 120`.
2. Engine pill: wait for `#pill-engine` to contain `/Ready to run|ready|Blocked|unavailable|Stopped/` instead of
   `/Dispenser/`.
3. Native dialogs are gone: remove the `page.on('dialog')` reliance; `#btn-pr-reset`, `#btn-home`, `#btn-prime`,
   `#btn-testdot`, `#btn-clean-check`, and `#btn-pressure-on` (when not over the waste) use a helper `press2(sel)`
   that clicks, waits for `.btn--press`, clicks again. Replace `dialogs.some(/wiped/)` with a check that the first
   press showed `Press again: reset row`.
4. Run: open `#btn-run-test`, fill `#run-maxdots`, click `#btn-run`, assert `#run-confirm-psi` matches
   `/Set the regulator to 18 psi \(profile .+\)/` (was `18\.0 psi`), tick `#run-tick-gauge` (if unticked),
   `#run-tick-paste`, `#run-tick-hands`, click `#btn-run-go`. Wait for `#run-live[data-label=board][data-status]`
   instead of the old `Task board … finished` text.
5. Summary assertions: `#plan-summary` contains `DPH estimate` followed by a number and `dots only`; the recipe
   token reads `18 psi · ½ turn · dwell 260 ms · gap 0.10 mm · purge 8` (token parts joined by ` · ` in its
   `aria-label`); `All interlocks pass` stays the wording of `#plan-interlocks` when every plan interlock passes.
6. After STOP: `#pill-conn` reads `Not connected` (was `Not started`).
7. `#pill-pressure` assertion: `#pill-pressure .cell__v` matches `/^ON /` (was `/Pressure ON/`).
8. Surfaces: open `#surf-advanced` before `#surf-name`/`#surf-points`. Board surface flow (since b9f96b0a1 I4
   refuses an untied board surface): plan → save the board surface with the plan (Probe step `Save … and plan
   again`, or the advanced editor while the plan is shown) → plan again → `All interlocks pass`.
9. Interlocks: open `#setup?panel=interlocks` (or click the verdict cell and `All 13`) before reading
   `#interlock-list`.
10. Pad types: `#dlg-padtype[open]` still works (non-modal `show()`); the row button text stays `Edit`.

## 17. Optional small API additions (allowed by the brief; each needs ApiServerSimTest + WEB_API.md)

| Addition | Why | Fallback without it |
|---|---|---|
| `GET /api/plans` (recent plans in PlanStore: id, board, createdAtMs, dots) | restore a plan in a fresh browser when no run references it | `status.run.planId`, then `localStorage['il.planId']` |
| `run.checkDone`, `run.checkPlanned` (clean-check tour progress via a listener callback) | `pad 57 of 160` during the 3-minute camera tour | estimate from elapsed time vs the plan's tour estimate, labelled `≈` |
| `run.pressureOnAtMs` | exact pressure on-time during a run | client stamps the first poll with phase `dispensing`; after a reload `≈ elapsed − tour time` |
| `server.simulated` (true when the driver is the simulator) | the `Simulator` tag and sim wording | infer from `drivers[].connection` or the run warning `simulated controller` |

**As built:** `GET /api/plans`, `run.pressureOnAtMs` and `server.simulated` (with `server.simulatorConfirmed`) exist
(`WEB_API.md`); `run.checkDone` / `run.checkPlanned` do not (the camera tour is estimated, labelled `≈`). Register board
added `run.detail`, `run.nextPlanId`, `machine.home`, `machine.zCheck` and `dispense.newSeatAtMs` (`WEB_API.md` §3.1).
The pad calibration (pc-task, pc-web) added `status.padCalibration` (the job view, with `result.newPlanId` loaded once
per job id), `run.label` `pad calibration`, the plan's `profileCheck` and per-pad / per-dot dose fields, the upload
preview's profile column, and the routes of `WEB_API.md` §3.4–3.5 (the crops as PNG; the SIM balance and paste for
tests). Still missing for the UI: a per-run skip of undispensable pads (PC §20 decision 6: `POST /api/run`
`{skipPads}` recorded as `skippedPads`); until then the Pad types card points to the Refuse flagged pads setting (`#set-refuseflagged`; off, the item is a note that counts ready and the confirm names the pads skipped).
| Run-only abort (stop after this dot, stay connected and homed) | softer stop | not offered; STOP is the only stop (open question, not required) |

## 18. Acceptance checklist

Frame and safety
- [ ] STOP is visible and clickable at 1280×800 and 1440×900 on every workspace, every scroll position, with the
      run confirm, a pad-type dialog and any popover open; it is 232×80 (216×80 ≤ 1360) and never disabled.
- [ ] `F9` stops with focus in a text field, in the run confirm and in the JSON editor. `Space` on the focused STOP
      does nothing; `Enter` stops. `Esc` never stops; it closes the top-most thing.
- [ ] STOP shows `STOPPING…` immediately; with the server paused > 2 s the `STOP NOT CONFIRMED` band appears and
      STOP is re-sent every second; nothing else is re-sent.
- [ ] After STOP: Recovery panel on Run (pushed view elsewhere), red `Not connected`, `Stopped hh:mm`, exact code
      line, plain-meaning list; board decision buttons disabled with the visible reason until Connect, start angle,
      Home and (if short) purge reset are done; the panel clears itself when ready again.
- [ ] No `confirm(`, `alert(`, `prompt(` or `showModal(` in `app.js` (grep in CI).
- [ ] `vent_failed` reads red `NOT off` in the strip and `NOT off` in `#pressure-state`, opens the Pressure view
      with `lastError`, and persists until the hold reads off.
- [ ] Offline (stop OpenPnP's API): within 3 s the heartbeat turns red, machine cells read `unknown since …`, the
      offline band shows, every control except STOP is disabled with the reason.

Readiness and Run
- [ ] `Ready` / `Ready to run` appears only when §9 says a board can run; after STOP the plan never shows
      `All interlocks pass`; after Connect + Home the plan re-reads by itself (no manual refresh).
- [ ] The segment bar, count, NEXT tag, verdict cell, Run nav badge and tab title always agree (one function).
- [ ] Dirty pads are needs-you; Run is available in the hold style with the reason line above it; with all 9 ready
      Run is filled teal.
- [ ] The run confirm fits 1280×800 with its footer visible, names `Set the regulator to 18 psi (profile 18 psi half
      turn)` first and largest, separates sensed checks from the three ticks (incl. hands clear), and cannot be
      committed until all ticks are set.
- [ ] `Only the first N dots` shows the TEST style and is cleared after the run.
- [ ] Running: pressure cell `ON m:ss · auto-off 10:00` on one line at both widths; verdict `96/160 · 0:44 left`;
      second STOP in the panel; phase list and timeline; plan-changing controls and Home disabled with the reason.
- [ ] Last board tile keeps the last board run after a prime, test dot or clean check; DPH reads `dots only` and
      `overall` everywhere with `bench 5,490`.
- [ ] Probe step starts from an empty point set, shows targets in machine mm, per-point delete, live distance
      computed from the shown live values, the margin-inflated covered area, and `Save 5 points and plan again`
      ties and re-plans in one press.
- [ ] Reloading the page during or after a run restores the plan preview, done dots and the clean-check result.

Setup, Profile, Calibrate, Records
- [ ] The I11 question offers two equal-weight answers with no default focus, from the strip cell, the Setup
      band, Checks, Recovery and Profile.
- [ ] Two-press pending looks striped/outlined (never the solid energised fill) and cancels after 4 s.
- [ ] Capture buttons show the live value they will record; head position table never collides at 1280.
- [ ] `Confirm −2.590 mm` clears the touched-off part of I4 in one press after `Record from K`.
- [ ] Settings exposes valve settle time; the advanced JSON editor sends only changed keys and survives a run that
      changed `purgeRow.usedSpots`.
- [ ] Profile shows an unsaved bar with the diff and consequences; the poll never overwrites edits.
- [ ] Pad types: compact rows with `Edit` + `More ›`; the rule editor saves via `PUT /api/pad-types/{key}/rule`.
- [ ] Touch-off shows its result and the offset change in place; Records lists boards, events, calibrations and
      touch-offs.

Quality
- [ ] No horizontal page scroll and no clipped strip value at 1280×800 and 1440×900 in idle, running, stopped,
      offline and vent_failed states (`tools/check.cjs`-style check on the live UI).
- [ ] Polling causes no layout shift (CLS 0 in a 60 s run recording) and never moves focus or the caret.
- [ ] CSP: no console CSP violations; no inline handlers; system fonts only; works with the network unplugged.
- [ ] Light and dark themes pass WCAG AA for text (disabled labels excepted, each with a reason line).
- [ ] `mvn -T1 test -Dtest='ApiServerSimTest,ReviewSafetySimTest'` and `src/test/web/run-webui-sim-test.sh` pass in
      SIM mode with the test changes of §16; no JavaScript errors.

## 19. Judges' must-fix items and how this design resolves them

| Must-fix | Resolution here |
|---|---|
| A and B: run-confirm footer below the fold at 1280×800 | dialog max-height, scrolling body, pinned footer; verified in `mockup-run-confirm-1280.png` |
| A: Home enabled during a run | Home hidden when homed; `Home again` and every moving/plan-changing control locked with the visible reason (§8.1.6) |
| B: Finish/Wipe enabled before Connect/Home | disabled with `Both unlock after steps 2 to 5…` (`mockup-run-stopped`) |
| B: board refresh/position editable during a run | locked list in §8.1.6 |
| B: Run locked on dirty pads (second 3-min tour) | I10 is needs-you; Run allowed in hold style; the run re-checks |
| C: STOP 204×56 | 232×80 full strip height + second STOP while energised |
| C: no "hands clear" tick | added (`#run-tick-hands`, every run) |
| C: "Elapsed 1:16 · board 4:24" | `Time left 0:44 · dispensing 1:15 · whole board ≈ 5:07 incl. pad check` |
| C: pressure chip without timer, footer wrap at 1280 | `ON 1:15 · auto-off 10:00` in the cell, one line at both widths |
| C: Last board DPH without "dots only"; status merged with outcome | `5,388 DPH · dots only · board 2, 12:58 · finished, all 160 dots` |
| C: nav sublines with bare I-codes | plain words (`Confirm angle`, `Wipe 2 pads`) |
| C: green Run next to "1 needs you" | hold style + reason above; filled only when 9 of 9 |
| C: Ready list hidden and verdict reason lost at 1280 | single-line ready tiles; verdict keeps its reason at 1280 (`8 of 9 · wipe 2 pads`) |
| C: I10 meter green while paste was found | I10 overlay rule (§9.3) |
| C: recovery without purge reset; STOP subtitle without motion | recovery step 5; subtitle `motion · vent · screw off` |
| C: probe distance contradicts live values | distance computed from the shown live values (§8.1.3) |
| A: Checks badge count vs amber items | one readiness function feeds every count |
| A: purge cell changes meaning while running | purge cell is always spots free of total |
| A: fiducials clipped, head numbers collide | view fit includes fiducials + 2 mm; fixed-layout head table |
| A: two-press amber ≈ energised amber | striped outlined pending style vs solid burnt-orange energised |
| A: probe outline through P1–P4 with pads outside | covered area drawn inflated by the 3.5 mm margin |
| A: purge reset after "Run the pads left" | purge reset is step 5, before the decision, available at once |
| A: I11 shown amber and red | one rule: reconnect → needs-you; never set → blocked |
| A: stopped screen asserts the controller stayed on | asks `Was the controller switched off?` |
| B: board name truncated; STOP glyph like a checkbox | h1 wraps, never truncates; STOP has no glyph |
| B: Not homed grey after STOP | Not connected red, Not homed amber |
| B: purge chip neutral at 1/15 | needs-you whenever fewer spots than one board needs |
| B, C, A: biased I11 answers | equal weight, no default focus everywhere |
| B: bed-map labels overlap geometry | bed map labels sit outside shapes with leader lines; no label inside another shape's box |

## 20. Open questions (not blocking)

- Run-only abort needs a new endpoint (§17); until then STOP + Recovery is the only stop.
- The start angle can only be measured by the guided manual dot-pair procedure; no endpoint dispenses at a chosen
  angle.
- Attestations are browser-local; a second browser asks again (by design).
- Boards with more than ~5,000 SVG elements need a canvas renderer.
- Esc was deliberately not made a STOP key (the laser console does): an accidental STOP costs a reconnect, re-home,
  I11 confirm and a partial board, and Esc is the universal "close" key. F9 is the bench's Stop key.
