# lumen-sim: a simulated LumenPnP controller on a pseudo-terminal

`lumen-sim` answers at the serial port the way the LumenPnP's Marlin board does: same replies, same timing, same
position and step-count reports, same faults. OpenPnP connects to it exactly as to the real port. It is INF-03
part 1 (`docs/infra-requests.md` in the project notes); the research behind it is `RESEARCH.md` in this folder.

Python 3.12, standard library only. Linux (it needs `os.openpty`).

## Run it

```sh
cd infra/lumen-sim
python3 -m lumen_sim --link /tmp/lumen/ttyLUMEN --control /tmp/lumen/ctl.sock --io-log /tmp/lumen/io.log
```

Point the OpenPnP driver's `port-name` at the `--link` path (a symlink to `/dev/pts/N`). jSerialComm resolves the
symlink and opens the pty.

**Remove `usb-serial-number` and `usb-description` from the driver.** This fork's `SerialPortMatcher` looks up the USB
serial number before the port name. With the real board plugged in, as on the lab computer, a config that keeps them
opens the board (`/dev/ttyACM0`), with no warning, whatever `port-name` says: OpenPnP then drives the real machine
while you believe it is on the simulator. `make_machine_patch.py` writes both. The easy way is uinav's
`--controller pty`, which removes them and refuses to start Java on a config that still has one. By hand, on a copy
of machine.xml (never `~/.openpnp2`), with the link below:

```sh
sed -i -E -e 's/ usb-(serial-number|description)="[^"]*"//g' \
    -e 's#port-name="[^"]*"#port-name="/tmp/lumen/ttyLUMEN"#g' machine.xml
```

Then `grep -E 'usb-|port-name' machine.xml` must show no `usb-` and only the link.

**The link's name must start with `ttyLUMEN`**, and lumen-sim refuses any other (CLI and power cycle). When the
path does not exist and is not a symlink, jSerialComm's `getCommPort` opens `/dev/<path>` or `/dev/<name>` instead.
The link is missing whenever lumen-sim is stopped or its port is down, so a link named `ttyACM0` would make OpenPnP's
reconnect open the real board. Never name a link like a device. After `ttyLUMEN` only letters, digits, `_` and `-`
may follow: `<link>.lock` and `<link>.tmp<pid>` are lumen-sim's own files, and no link may replace one. Publishing
replaces what is at the path, so a path that holds anything but a link to a `/dev/pts/N` (a killed lumen-sim's) is
refused too.

| Option | Meaning |
|---|---|
| `--link PATH` | Where the pty slave is published (name `ttyLUMEN*`). The link exists only while the port does: it is removed when the port goes down (disconnect, DFU, a power cycle's down time) and on exit, and made again for the new pty. |
| `--personality stock\|il-safety` | Firmware build (below). Default `stock`. |
| `--control SOCK` | Unix socket for the control channel (JSON lines). |
| `--io-log FILE` | Append the traffic in `serial.log` format. |
| `--time-scale 1.0` | Simulator seconds per wall second, a finite number > 0. Keep 1.0 when host timeouts matter: they are wall clock. |
| `--park X,Y,Z` | Physical head position at power-on, in mm from the X/Y/Z switches (finite numbers). Default `5,5,31.5`. |
| `--kill-messages` | Print `echo:<reason>` and `Error:Printer halted. kill() called!` when the board kills (default silent). |
| `--vacuum N` | Raw 24-bit N1 vacuum reading in open air (default -1500000). |
| `--surface-z Z` | Physical Z (mm) at which N1 touches a surface and seals (default: nothing under N1). See "Vacuum model". |
| `--seed N` | Seed for random fault delays. |
| `--sim-cap` | M115 also sends `Cap:SIMULATOR:lumen-sim`, which OpenPnP's SimGuard takes as a simulator. Default off. Only for SIM-feature scenarios, never for the reproductions: "The simulator line" below. |

It stops on SIGINT, SIGTERM, SIGHUP (its terminal or ssh session closed) or `ctl stop`, and removes its symlink and
socket. A SIGKILL (a stop that escalated, the OOM killer) gives it no chance to, and a link left behind names a freed
`/dev/pts/N` that the kernel hands to the next pty opened on the host. A pts is no proof of a simulator: on the lab
computer Klipper's G-code input (`~/printer_data/comms/klippy.serial`) is one, so a host reconnecting through a stale
link could drive a real machine. So lumen-sim forks a guardian at start (`guard_pid` in the state): a process in a
session of its own that holds none of lumen-sim's files and, when lumen-sim dies however it dies, removes each link
still pointing at lumen-sim's pty whose `<link>.lock` nobody holds, then exits. The socket a SIGKILL leaves is
harmless and replaced by the next start. Killing the guardian too (a whole cgroup) leaves the link: `uinav stop`
removes such links in its sessions; elsewhere remove it by hand.

One lumen-sim per path. Each holds an flock on `<link>.lock` while it runs (the file stays; the lock dies with the
process), and a second one on the same link, or on a socket that still answers, exits with `... is in use` and changes
nothing. Lock files are never opened through a symlink: a `<link>.lock` that is one (lumen-sim never makes one) is
refused, and the guardian and `uinav stop` leave its link alone. A socket nobody answers on (a killed lumen-sim's) is replaced; a `--control` path that is not a socket is
refused and left alone. On exit lumen-sim removes the socket only if it is still its own.

```sh
python3 -m lumen_sim ctl --control SOCK state
python3 -m lumen_sim ctl --control SOCK fault '{"fault": "no_ok", "match": "^G4"}'
python3 -m lumen_sim ctl --control SOCK set '{"vacuum": -1600000, "rs485": {"FF00": "0017"}}'
python3 -m lumen_sim ctl --control SOCK power-cycle [--link NEW_PATH] [--down-s 2]
python3 -m lumen_sim ctl --control SOCK clear
python3 -m lumen_sim ctl --control SOCK stop
```

`ctl` makes a relative `--link` absolute in its own working directory (lumen-sim refuses relative links: its working
directory is not the client's). It exits 1 when the reply is not ok or carries `link_error`.

## What it models

Everything below follows the stock source (`wt/marlin-stock-7776c73`), checked against `runs/serial.log`.

**Serial and parser.**
- LF, CR and CRLF all end a line; empty lines are skipped. Replies end in LF only.
- `;` comments are stripped, `\` escapes and quoted strings are handled, 95 characters are kept.
- Upper-case letters only: `g4` is an unknown command.
- Numbers have no exponent: Marlin's `value_float()` cuts a value at `e`/`E`/`x`/`X`, and integer values are
  `strtol`, so `P1e999` and `X1e999` read as 1.
- Unknown commands get `echo:Unknown command: "<text>"` and then `ok`.
- A 4-slot command ring: the running command holds a slot, and reading stops while the ring is full.
- Not modelled: `N` line numbers and checksums (the line number is skipped, the checksum is ignored).

**Keepalive.** `echo:busy: processing` 2.0 s after a command starts blocking, then every 2.0 s. This covers G4, M400,
G28, M18 and a full planner. A busy line due at the very moment a wait ends comes before the `ok`, as in the log.
`M113 S<n>` sets the interval as the board does: `value_byte()` clamps to 0..255, then at most 60, so `S-1` is 0
(off) and `S300` is 60. `M113` or `M113 S` with no value reports it (`echo:M113 S2`).

**Planner and timing.**
- 16 block slots (15 usable). Each `G0`/`G1` is acked when its block is planned. When the planner is full it waits,
  and is acked the moment the head block ends and frees a slot.
- Each block is a trapezoid in steps:
  - nominal rate `ceil(steps x feed / distance)`;
  - acceleration `ceil(M204 T x steps/mm)`, capped per axis by `M201 x steps/unit`; speed capped by `M203`;
  - it starts and ends at 120 steps/s (`MINIMAL_STEP_RATE`).
- Rotation-only moves use degrees (the LinuxCNC rule).
- A block put into an empty planner waits 100 ms (`BLOCK_DELAY_FOR_1ST_MOVE`), unless 3 blocks are queued first.
- Blocks run rest to rest. Junction speeds between back-to-back blocks are not modelled; the backend separates its
  moves with G4/M400.
- `SLOWDOWN` and the minimum segment time are not modelled.
- Arithmetic is float32 where Marlin's is.
- `M114` prints the logical position and the planned step counts at once (`X:%.2f ... Count X:%d ...`), with
  Marlin's rounding. `G92` and `G28` reports use the stepper's counts at that moment.

**State.**
- Native position, the G92 shift (logical = native + shift), planner and stepper step counts.
- Homed flags for X, Y and Z. A and B have no switch and are never homed.
- Soft endstops 0..433 / 0..487 / 0..63 clamp homed axes only, silently. `M211` reports them in logical coordinates.
- Fans P0-P3 with a timestamped history, LED, I2C mux and register, stepper enable flags.
- Idle timeout (`DEFAULT_STEPPER_TIMEOUT_SEC` 3600, `DISABLE_IDLE_X/Y/Z/I/J`): once the planner has been empty that
  long, counted from the end of the last block, a `G28` or an `M18`/`M84 S`, every axis is released, silently. Once
  only, until a block is queued again (`M17` alone does not re-arm it). It also runs inside waits such as `G4`. The
  check and the firmware's wake for it use one expression (last move + timeout), so the release lands on its deadline
  at any time; computed two ways, rounding once made the loop spin at that instant for ever.
- The board compares the time as a signed 32-bit number (`ELAPSED`, `core/millis_t.h`). So `M18`/`M84 S` of
  2147484 s or more (2^31 ms; a bigger value saturates at 0xFFFFFFFF ms) has run out at once: every axis is released
  on the next pass and again each time the planner runs empty, and Z drops. Use `S0` for "never".
- Boot state: X=Y=Z=0, A=B=720.00 (counts 3197), nothing homed, outputs off. Settings are the firmware defaults; there
  is no EEPROM.

**The physical head.**
- It is tracked apart from Marlin's belief, in the machine's true frame: X/Y/Z 0 is where a correct home puts it.
- The X/Y/Z min switches trigger at physical 0, or where a `home_offset` or `z_shift` fault has moved them. A
  `lost_steps` or `home_offset` that lands while a homing move runs toward a switch is checked again at once: the
  move ends where the switch now triggers (at once if the head is already past it), and so does its wait.
- A power cycle resets the belief and the counts; the head stays where it is (a released Z drops, below).
- Releasing an energised Z (`M18`/`M84` with Z or no axis, the idle timeout, `M112` 0.85 s after the kill, a power
  cycle) moves the head by the `z_drop` fault's distance (default 0), or to the see-saw's rest with a collision
  model (below). It stays there: the next block with Z steps re-energises Z (at once when planned: `Z_LATE_ENABLE`
  is off, planner.cpp), as does `M17`. A move with no Z steps leaves Z released.
- The control state's `head` gives X/Y/Z `physical`, `logical` (the step counts now, in G92 coordinates; M114 prints
  the planned end) and `offset` = physical - logical: what the controller does not know. At boot the offset is the
  park position; after a true home and no faults it is 0. Values are rounded to 1e-6 mm.
- `M119` reads the switches.

**Commands.**
- Motion and waits: `G0 G1` (`G2 G3` move straight to the end point), `G4`, `G28`, `G90 G91 G92`, `M400`.
- Steppers: `M17`, `M18`/`M84` (wait for motion, then release the axes given, or all). `M18 S<s>`/`M84 S<s>` sets the
  idle timeout only, with no wait and nothing released; `S0` means never. It is `stepper_timeout_s` in the state,
  and a power cycle restores 3600.
- Reports: `M105` (`ok T:0`), `M114`, `M115`, `M119`, `M211`, `M503` (from the current settings, as in the log),
  `M113`, `M220`.
- Settings: `M92`, `M201`, `M203`, `M204`, `M205`, `M502`, `M906`.
- Outputs: `M106`/`M107` (immediate, not synchronised with motion; no S means 255, no P means P0), `M150`.
- Peripherals:
  - `M260`/`M261`: style S0 raw, S1 hex, S2 int16 of the last two bytes, S3 decimal; `Error:Bad i2c request`.
  - `M485`: `rs485-reply: TIMEOUT` after 100 ms unless the reply table has the request; a reply comes after 54 ms.
  - `M3426`: `Error:MCP342X i2c error`; no MCP3426 is assumed.
- Accepted and only acked: `M42 M108 M110 M111 M122 M206 M569 M999 G17 G18 G19 G21`, and `M43` but for `M43 P5` (below).
- Quick-stop and kill: `M410` and `M112` (below).

**Vacuum model.** `M261 A109 B<n>` returns the reading big-endian as a 24-bit two's complement. The open-air value is
-1500000 (`--vacuum`, `set {"vacuum": n}`).
- With a surface (`--surface-z`, `set {"surface_z": 5.3}`), N1 seals when the physical head Z is at or below it while
  the pump (P0, `M106`) and the valve (P1, `M106 P1 S180`) are on. The reading is then lower by `seal_drop` (default
  329000 counts, the median of SimScene's seal drops; `set {"seal_drop": n}`). N1 goes down as Z falls; its contact
  reads `surface_z` after a true home.
- `surface_z` is the height everywhere. Regions can have heights of their own: `set {"surfaces": [{"x0": 150,
  "y0": 100, "x1": 250, "y1": 200, "z": 5.6}, ...]}` (physical mm, edges included; where regions overlap the first
  listed wins; outside every region `surface_z` applies, or open air if it is null). A new list replaces the old one;
  `null` or `[]` removes them. So one place (a blank) can move while another (a fixed reference) does not.
- The change is instant: no partial seal, no time constant, no leaks. The pump-off reading is the open-air value too.
  `surface_z: null` removes it. Surfaces are the machine's, so they outlive a power cycle and `clear`.
- Because it follows the physical head, a `z_shift` or a `z_drop` moves the contact the backend's N1 touch reads.
- The state shows `surface_z`, `surfaces`, `seal_drop` and `n1_sealed`. The I2C mux and register are not checked.
- With a collision model loaded, its touchable tops replace `surface_z` and `surfaces` ("The collision model", below).
- A callable `vacuum=(firmware) -> int` passed to `Simulator` replaces all of this.

**The PA5 switch (the user, 11-04).** A limit switch near the first feeder on PA5 (the HACK header); N1 and the needle
press it to measure the needle's Z directly. `M43 P5` answers the real board's line, then `ok`:
`PIN: PA5  (A9)   M42 P53          <unused/unknown>                       Input  = 0` (runs/serial.log, 11-02). It reads
the pin where the head is when the line runs (no wait for moves: send `M400` first). Other pins and a bare `M43` print
nothing.
- Without a switch it reads what the pin's input mode gives (`M42 P53 T2` pull-up: 1; `T3` pull-down or `T0`
  floating: 0, as the board read 11-02; a power cycle resets it to that). `set {"pa5_switch": {"presses": [{"tool": "n1",
  "x0": .., "y0": .., "x1": .., "y1": .., "trip_z": 12.0}, {"tool": "needle", ..., "trip_z": 52.0}], "overtravel_mm":
  0.5, "repeat_sd_mm": 0.003, "pressed_level": 1, "seed": 0}}` wires one (all but `presses` optional, these are the
  defaults but `repeat_sd_mm`, 0; `overtravel_mm` 0-10, `repeat_sd_mm` 0-1, `pressed_level` 0 or 1, `seed` an
  integer); `null` removes it. Press windows are physical X/Y mm, edges included, the first listed wins.
- `n1` presses it as Z falls to the trip point, `needle` as Z rises to it (the see-saw: needle contact raw Z = K - N1
  contact). Each press trips at its own point, `trip_z` + N(0, `repeat_sd_mm`) from `seed`, and holds it until the
  tool goes back past it or leaves the window; after a release no new press starts until the head moves toward the
  switch again (no chatter while it stands still): the release point follows the head as it moves away, so back at the
  switch the next press trips at its own point. `pressed_level` is the pin's level pressed, the other one released,
  whatever M42 set (the real polarity: Process's check on the machine).
- A press deeper than `overtravel_mm` past its point is reported once in `sim_errors` ("PA5 switch overtravel: n1 0.62
  mm past its trip point (overtravel 0.50 mm)", `line` null), seen at the end of each move (a quick stop's too, and
  one that ends in a stock kill's coast), at each read, at a Z release (`z_drop`) and at a physical fault (where the
  head is then, also mid-move), and when it is wired under the head (a new switch wired under a head already past its
  point reports it once more). At M112 both personalities hold Z and let every stepper go 0.85 s after the kill
  (minkill(): cli() at 0.6 s, disable_all_steppers() 0.25 s later; stock coasts until the cli(), il-safety's kill
  stops the motion at once), so a `z_drop` lands then, where the motion ended, whether or not anything reads the
  state. Not seen: an X/Y move that crosses a window deep and ends outside it. The head is not stopped (no hard stop
  is modelled).
- The state's `pa5`: the settings, `level`, `pressed`, `tool`, `trip_z` and `depth_mm` (the deepest of this press),
  `overtravels`, as of the last observation (from its wiring on): the state request reads nothing. The switch is the
  machine's: it outlives a power cycle and `clear`.

**The collision model (INF-12).** Backend's `<config>/instancelabs/collision-model.json` (schema 1; the design is
`docs/instancelabs/collision-model-design.md`, sections 2 and 3): the bed plane, the objects on the bed and the tools'
shapes. Load it with `--collision-model PATH` or `set {"collision_model": <the file's JSON object>}`; `null` removes
it. lumen-sim is the physical truth: it reports a tool layer that really goes below an object's top, with no margin and
no error term (the engine's proof adds those). So a clear proof never meets a collision here, and an engine bug, or a
fault the engine cannot see, shows up in `sim_errors`.
- Loading checks what lumen-sim uses: schema 1, an integer `version`, every outline (`box` [x0, y0, x1, y1] with
  x0 < x1 and y0 < y1, `circle` [cx, cy, r] with r > 0, `polygon` [[x, y], ...] convex and counter-clockwise with no
  point on a line between two others), the see-saw (knees, `mmPerRaw` > 0, `K.raw`, `endStopsRaw` [low, high],
  `restRaw` or null), `contactDepthMm` (>= 0; null or absent: 0.25), each object's `id`, `state`, `topZ` (or null)
  and `touchable` (true or false; null or absent: false) and each tool's `moves` (`n1`, `needle` or `fixed`),
  `tipEnd` (an object with `dx` and `dy`; needed on the see-saw, while a fixed part's null or absent one is the head)
  and layers (`at` `head` or `tip-end`, and one of `aboveTipMm` and `bottomZ`; a fixed part takes only `bottomZ`). It
  ignores the measurement records, the margins (`marginMm`, `zMarginMm`), `errMm`, `homeAtMs` and `homeRepeatMm`:
  those are the proof's error terms; and any other key (notes). A bad model is refused whole
  (`ValueError: collision_model...`) and the one loaded stays; the CLI exits 2.
- The bed: rc(x, y), N1's contact raw with the bed, is the least-squares plane through `bed.plane.points`. lumen-sim
  places every height on it, so it needs at least 3 points not on one line and refuses fewer, as the engine's loader
  does (its writer never writes such a file): a "plane unmeasured" fixture is for the in-process SIM only. The bed is
  an object too: top 0 inside `bed.outline`, id `bed`, not touchable.
- Tip heights, each at its own tip's X/Y (the head + `tipEnd`), with r the head's physical Z: N1 h1 = g1 (min(r, k1) -
  rc), the needle h2 = g2 ((K - rc) - max(r, k2)). A layer's bottom is its tool's tip + `aboveTipMm`; a part that does
  not ride the see-saw has `bottomZ` above the bed at the head (+ its `tipEnd`, if it has one). Heights are compared in
  the gantry's frame, where the bed is g1 rc(x, y) and an object's top is bed + `topZ`.
- The sweep: at the end of each block (also one a quick stop or a kill cuts short) and at each fall of Z (below), each
  layer's swept area, the convex hull of its outline where the leg starts and ends, is clipped against each object.
  The layer collides when its lowest bottom on the leg (at an end: the see-saw maps are monotonic) is below the
  object's highest top over the overlap. A circle is the 64-gon around it (at most 0.12 % of r outside it); sides
  that only touch do not overlap. An object not measured (`topZ` null, or state added, moved or removed) blocks its
  outline at every height (`overlapMm` null). It has no top here, not even the one a moved entry keeps, so N1 never
  seals on it: an engine Measure of a moved or added blank reports a collision and finds no seal in lumen-sim (its
  top reads not measured) until the file marks the object measured.
- Contact: a tip (a layer at the tip end) up to `contactDepthMm` into the measured top of an object marked
  `touchable: true` (a blank, a board, the PA5 switch, a feeder's pick pocket) is resting on it, as on what it probes,
  presses or dispenses on, and is no collision. The default 0.25 mm is a guess (one coarse probe step: a touch stops
  within a step past the top) until Process weighs the tips' give. Anything else below a top collides at any depth:
  a tip deeper, a tip in the bed or in an object not touchable (no correct path rests a tip there), any other layer.
- Each collision goes to `sim_errors` once, as `{"t", "line", "error": "collision: needle (hub) 0.400 mm below the top
  of block", "object", "tool", "layer", "overlapMm"}` ("collision: camera (camera and light) over added, not measured"
  for one not measured); `line` is the block's G-code line, null for a fall. It is not reported again while that
  layer stays in that object, and again after a leg that ended clear of it. The head is not stopped. A physical
  fault's jump (`lost_steps`, `z_shift`) is not swept; after it a collision stays reported only while its layer is
  still in that object, so the next leg reports one the jump carried it out of and back into, or landed it in.
- The see-saw: when the Z driver lets go (a power cycle, `M18`/`M84` with Z, the idle timeout, `M112` 0.85 s after
  the kill: stock where its coast ended, il-safety where the head stopped at once), Z goes to `seesaw.restRaw`, or by
  the `z_drop` when the rest is null, as without a model. The fall is swept. A model set while Z is not powered
  (since the boot, as `--collision-model` always is, or since a release) puts Z at its `restRaw` at once (the arm
  has been resting there) and sweeps that fall. `seesaw.endStopsRaw` hold Z, also before a home: steps past a stop
  are lost (the counts go on, the head does not).
- N1's vacuum: with a model, N1 seals only on the top of a measured object marked `touchable: true` (Process's flag,
  not inferred from `surface`), at least 2 mm inside its outline (Process 11-04). Never on the bare bed (out of N1's
  reach) or an object not touchable; `surface_z` and `surfaces` count for nothing then.
- The state's `collision_model`: `version`, and `tips`, each see-saw tip's height above the bed under it now; null
  without a model. The model is the machine's: it outlives a power cycle and `clear`.

**Homing (`G28`).**
- It waits for motion, then homes X, then Y, then Z. Z homes at the safe point (0,0), then moves to Z 31.5. A and B
  are untouched.
- Each axis does a fast approach (1.5 x travel at 150 mm/s and the current M204 accel), backs off 10/10/2 mm, then
  re-bumps at 1/2, 1/2, 1/4 speed.
- After each move toward a switch, `validate_homing_move()` kills the board if the switch was not hit.
- `G28 Z` alone also homes unhomed X/Y first; with X/Y unhomed it prints `echo:Home XY First`.
- The phase (`X_APPROACH`, `X_BUMP`, `X_SLOW`, ..., `Z_SAFE_XY`, `Z_AFTER`) is in the control state.

**Homing time.** The board's G28s come in two speeds, with a 1.4 s spread; the cause is not known.
- Four logged G28s start from a known position (an M114 just before, no move since), all with `M204 S1500`. Lines
  168535 and 169216 (from X234.5 Y199.1) took 5.25 s, and the source's kinematics give 5.33 s. Lines 166293 (from
  X217.2 Y63.2) and 166954 (from X240.9 Y215.9) took 5.80 s and 6.86 s, about 1.4 s more than the kinematics (4.31 s
  and 5.48 s). The G28 right after a power cycle (line 29118, start unknown, `M204 S2000`) took 2.82 s: the
  kinematics need 2.24 s from the switches themselves, so it cannot have had the 1.4 s either.
- So the default is the kinematics alone (3 of the 5). `set {"homing_move_delay_s": 0.142}` adds 0.142 s before each
  of the 10 moves of a full home and reproduces 166293 and 166954 (5.73 s and 6.90 s). It is a knob, not a fault: it
  outlives a power cycle and `clear`. It moves the end of every phase, so it changes where a STOP at a given time
  lands (a STOP 5.5 s after a G28 from X234.5 Y199.1 is a plain quick-stop at the default, a kill at 0.142).
- It is not the `M201 B20000` that came before 168535: the boot G28 is fast with the boot values.

**Quick-stop, `M410`.**
- The planner is dropped, the head stops where the steps are, and the position is re-read from the step counts
  (`A:720.05` after a stop at boot). New moves are refused for 1 s, and the `ok` comes about 1 s later.
- **stock:**
  - With `EMERGENCY_PARSER` off, an `M410` also acts the moment the line is read, inside any wait (G4, M400, G28, a
    full planner); the queued copy runs again later. So a lone M410 acks after 2.0 s.
  - While that first stop waits, the reader keeps reading into the shared line buffer. In the `STOP` burst
    (M410 + 5 lines in one write) the M410 is overwritten and dropped: 6 oks for 7 lines, the first after 1.0 s.
    Both cases match the log.
  - **M410 during G28** cuts the move short. If that move was toward a switch, the board kills: motion stops, then
    no output at all, all input discarded, fans as they were, motors energised. Reopening the port does not help;
    only a power cycle does.
  - An M410 during a back-off move does not kill: the re-bump still reaches the switch, which is what the source
    does. RESEARCH.md section 7 says "any phase". An M410 in `Z_AFTER` is a plain quick-stop.
- **il-safety:** below.

**`M112`.** It kills at once (stock: on read). In both personalities the drivers hold through minkill() and every
stepper lets go 0.85 s after the kill (`cli()` at 0.6 s, then 0.25 s); a power cycle before then lets them go at the
power off (a released Z drops once). How the motion ends: "kill" below. The `Homing Failed` kill leaves the motors
energised.

## Personalities

| | `stock` (default) | `il-safety` (il/safety d973aef69, not flashed) |
|---|---|---|
| M115 | exactly `serial.log` lines 2-38, `EMERGENCY_PARSER:0` | `EMERGENCY_PARSER:1`, plus `Cap:HOST_WATCHDOG:1` last (the firmware line is the stock one) |
| M410 | acts on read and again when dequeued (2.0 s alone) | acts on receipt (emergency parser), turns P3 then P2 off at once, the queued copy is a no-op (1.0 s) |
| M410 in G28 | kill: the board halts until a power cycle | `echo:Home <axes> First` if X/Y were not homed, then `echo:G28 aborted by quick-stop: axes not homed`, the report, `ok`, then M410's `ok`; nothing homed. Later homing moves are refused, but Z_AFTER_HOMING is still written to the position (Z:31.50, not moved), as in the native-build transcript. |
| M990 | unknown command | `M990 S<ms>` arms (100..3600000, else `Error:M990 S must be 0 or 100..3600000`), `echo:Host watchdog: armed S<ms>` / `off` / `off tripped`. Any line read restarts it; a busy command or a queued line holds it. On a trip: quick-stop, P3 P2 P1 P0 off, B disabled, `Error:host watchdog`. |
| M106 P2/P3 | S as given | any S>0 is 255 |
| Z steps/mm, B max accel, B current | 40, 10000, 200 mA | 160, 20000, 540 mA |
| kill | fans unchanged, motion runs 0.6 s more | all four outputs off, planner stopped first |
| M997 | unknown | reboot to DFU: the port goes away until a power cycle |

## The simulator line (`--sim-cap`, BR-47)

With `--sim-cap`, or `set {"sim_cap": true}` on a running lumen-sim, M115 sends one more line,
`Cap:SIMULATOR:lumen-sim`. It comes last of the Cap lines (after `Cap:HOST_WATCHDOG:1` on il-safety), then the `ok`.
Nothing else changes, and the real board never sends it. Off (the default), M115 is the board's byte for byte.

OpenPnP's SimGuard (BR-47, `SimGuard.java`) takes the machine as simulated when both hold:
- every driver is on a serial port that resolves to `/dev/pts/N` and was chosen by its name (no USB serial number or
  description in the config);
- that connection's M115 carries exactly this line. SimGuard asks at every connect and forgets at a disconnect.

Then the SIM cameras, the SIM balance, `/api/sim` and the SIM-only settings work on lumen-sim, and `GET /api/status`
says `server.simulatorConfirmed: true` once the machine is started.

- **Use it** for scenarios that need those SIM features on the pty: the acceptance's `register`, `tips` and `padcal`.
  In uinav: `--controller-sim-cap`, or `UINAV_SIM_CONTROLLER_CAP=1` next to `UINAV_SIM_CONTROLLER=pty`. Those three
  still fail on the pty until BR-69 feeds the SIM vacuum and the SIM paste there ("Known gaps"), so keep them on
  GcodeServer, in CI too, until then.
- **Never for the bug reproductions** (`tests/test_repro.py`, `e2e/test_openpnp_pty.py`, the deployer's e2e). They
  must see the board's exact M115, and the backend must treat them as the real machine. `e2e/test_openpnp_pty.py`
  drops `UINAV_SIM_CONTROLLER_CAP` from its launches and checks `simulatorConfirmed` is false after the connect.
- The flag is lumen-sim's, not the board's: a power cycle keeps it. A `set` shows from the next M115; SimGuard reads
  M115 at a connect, so set it before Start (or stop and start again).

## Faults and the control channel

Connect to `--control` and send one JSON object per line; each gets one JSON reply line with `"ok": true|false`.
A malformed request (bad JSON, also JSON nested too deep to parse, a bad regex, a `count` that is not an integer >= 1,
a non-finite or negative time, a key the request does not take, a flag (`on`, `kill_messages`, `sim_cap`) that is not
`true` or `false`, a physical fault with no axis key, or a non-finite value) gets `"ok": false` with an `error` and
changes nothing; so does a power cycle's `link` that is relative, not a `ttyLUMEN*` name (above), without a directory, on a
path that holds something else, or on one another lumen-sim holds. A `set` with one bad value applies none of it. Every request refuses a key it does not take, so a misspelt one (`"surfaceZ"`, `"downs"`,
`"Nth"`) is an error, not a silent default; the keys are those in the table below. An ok fault with no `nth` and no
`match` hits every line.

Replies never block the simulator: each client's replies wait in lumen-sim until its socket takes them, and a client
with replies waiting is not read meanwhile (its own writes block instead). A client that pipelines requests and reads
later gets every reply, in order. A request line over 1 MiB drops the client.

The physical faults (`home_offset`, `lost_steps`, `z_shift`, `z_drop`) are the machine's, not the controller's: they
outlive a power cycle and are not listed in `faults`. All values are signed mm along the axis.

| Request | Effect |
|---|---|
| `{"cmd": "state"}` | `state` holds: time, personality, boots, halted and halt_reason, logical/native/counts/stepper_counts, physical, head (above), switches, z_drop, surface_z, surfaces, seal_drop, n1_sealed, pa5, collision_model, homing_move_delay_s, stepper_timeout_s, homed, homing_phase, moving, planner_blocks, ring, fans, fan_history (`t`, `p`, `s`, `source`; the last 1000 changes), led, enabled, soft_endstops, settings, watchdog, kill_messages, sim_cap, faults, lines_read, sim_errors (below), and the transport: link, pts (null while down), host_open, frozen, link_down, time_scale, guard_pid. |
| `{"cmd": "fault", "fault": "no_ok", "nth": 3}` | No `ok` for the 3rd line read from now; with `"count": 2`, the 3rd and 4th. Use `"match": "<regex>"` instead for every matching line; `"count"` limits the hits. Keys: `nth`, `match`, `count`. Several rules may hit one line; each is used up. A power cycle ends every ok fault: they are the controller's, and the reboot clears them (the physical faults stay). |
| `{"cmd": "fault", "fault": "delay_ok", "s": 0.5, ...}` | Extra delay before that `ok`; `"random": [lo, hi]` for a seeded random delay. Later output queues behind it, until `clear` or a power cycle. |
| `{"cmd": "fault", "fault": "dup_ok", ...}` | The `ok` is sent twice. |
| `{"cmd": "fault", "fault": "inject", "kind": "error"\|"start"\|"unknown"}` | Sends `Error:simulated fault`, `start` or `echo:Unknown command: "M999999"`; `"text": "..."` for any line; `"after_s": 1.5` to delay. Refused on a halted board. |
| `{"cmd": "fault", "fault": "inject_bytes", "hex": "ff"}` | Raw bytes (or `"text"`; `"after_s"` as above). A 0xFF kills OpenPnP's reader thread, which reads it as end of stream. Refused on a halted board. |
| `{"cmd": "fault", "fault": "partial", "text": "X:1"}` | Text with no newline (`"after_s"` as above). Refused on a halted board. |
| `{"cmd": "fault", "fault": "halt"}` | Dead MCU: no output, input discarded, until a power cycle. Output it had not sent by then (an `ok` a `delay_ok` holds, and all queued behind it) and pending injections are dropped, as after any stop of the board (kill, DFU, sim error). `"reason": "..."` sets `halt_reason`. |
| `{"cmd": "fault", "fault": "disconnect", "down_s": 1.0}` | Close the pty master (the host gets EIO) and remove the link, so the port is absent, as an unplugged one. After `down_s`, a new pty behind the link; the controller state is kept. `"down_s": null` stays down until a power cycle, and so does any `down_s` while the board is in DFU (its bootloader shows no serial port). |
| `{"cmd": "fault", "fault": "frozen", "on": true}` | Stop reading the port (`on` is `true` or `false`, default `true`). The host's writes block once the pty buffer fills. Ends with `clear`, `"on": false` or a power cycle. |
| `{"cmd": "power_cycle", "link": null, "down_s": 0}` | Close the master, boot state, new pty behind the link, not frozen; output the old board had not sent and pending injections are dropped. The head stays put (a stock kill's last 0.6 s of motion ends at power off). `"link"` publishes it at a new path instead and removes the old link (the board's ttyACM0 becoming ttyACM1; here e.g. ttyLUMEN0 to ttyLUMEN1). `"down_s": 2` keeps the port absent for 2 s first, as while the board resets and USB re-enumerates (the board is reset at once; default 0). The new `link` must be an absolute path; it is checked and its lock taken before the reset, and held through the down time. If the port still cannot be published after the reset (no pty left, the folder gone), the reply is `"ok": true` (the board was reset) with `link_error`, and `link` says where the port is (back at the old path if it could). Ok faults end (above). |
| `{"cmd": "set", "kill_messages": true, "sim_cap": true, "vacuum": -1600000, "rs485": {"REQHEX": "REPLYHEX"}, "mcp3426": 1234, "surface_z": 5.3, "surfaces": [{"x0": 0, "y0": 0, "x1": 50, "y1": 50, "z": 5.6}], "seal_drop": 329000, "homing_move_delay_s": 0.142, "pa5_switch": {"presses": [...]}, "collision_model": {"schema": 1, ...}}` | Knobs, any subset (`surface_z: null` removes the surface, `surfaces: null` the regions; "Vacuum model"; `pa5_switch`: "The PA5 switch"; `collision_model`: "The collision model"; `homing_move_delay_s`: "Homing time", default 0; `sim_cap`: "The simulator line"). |
| `{"cmd": "fault", "fault": "home_offset", "x": 0.5, "y": -0.2}` | The X/Y switches now trigger at these physical positions (mm), so every later home of that axis lands that far off while M114 says 0. Nothing moves until a home. A new value replaces the old one per axis. |
| `{"cmd": "fault", "fault": "lost_steps", "x": 0.3, "y": 0}` | The head moves by this much now, with no command, also mid-move. M114 and the counts do not change. Calls add up; a home removes it. |
| `{"cmd": "fault", "fault": "z_shift", "z": 0.1}` | The whole Z frame shifts: the head moves by z now and the Z switch moves with it, so later homes keep the shift. Calls add up. |
| `{"cmd": "fault", "fault": "z_drop", "z": -0.4}` | From now on, each release of an energised Z moves the head by z (above). `0` turns it off. |
| `{"cmd": "clear"}` | Drop all ok faults and pending injections, unfreeze. Output a `delay_ok` holds back (and everything queued behind it) goes at once. Switches back to 0 and no Z drop; the head stays where it is until the next home, except that a homing move running toward a switch now ends at the true one. |
| `{"cmd": "stop"}` | Exit. |

**The backend's acceptance checks (INF-08).** In a uinav pty session the same requests go through
`uinav controller fault '{"fault": "home_offset", "x": 0.5}'` (uinav passes the JSON on as it is).

| Check | In lumen-sim |
|---|---|
| BR-02 power cycle vs reconnect | `power_cycle`: M114 then reads `X:0.00 Y:0.00 Z:0.00 A:720.00 B:720.00 Count X:0 Y:0 Z:0 A:3197 B:3197` with nothing homed, and the head stays where it was (`head.offset`). A host close and reopen, or a `disconnect` fault, keeps every position and count. |
| BR-09 controller reads | `M503` prints the board's lines and `M115` its capabilities (goldens `m503.txt`, `m115.txt`). Dead link: `halt` (no reply to anything, reopening does not help, a power cycle does), or `frozen` (the port is not read; a power cycle ends it too). |
| BR-10 Z check | `set {"surface_z": ...}` near the config's N1 contact height first: without a surface N1 reads open air at every Z and the touch refuses ("no contact"). `z-moved`: `z_shift` (e.g. 0.1, then a home) moves the contact every touch reads by that much. `surface-moved`: give the blank a region of its own, `set {"surfaces": [{"x0": .., "y0": .., "x1": .., "y1": .., "z": <surface_z + 0.3>}]}` around its XY (physical mm), and only touches there read the change; the fixed references keep `surface_z`. |
| BR-10 X/Y home faults | **Cannot pass yet.** `home_offset` (a G28 that lands off) and `lost_steps` move only X/Y, which the backend can see only through a camera. The homing fiducial in the top camera is INF-05 (not built yet), to be drawn from `head.physical`; until then only the control state's `head.offset` shows them. |
| BR-12 Z release | `surface_z` set, `z_drop` (e.g. -0.4), then `M18 Z`: the next N1 touch reads 0.4 mm higher. `M84 S0` sets `stepper_timeout_s` to 0 and the line is in the I/O log. |

**Model errors.** The board answers whatever the values; the simulator must not die on one stray line either.
- A line the model cannot run (for example `M203 X0` then `G1 X10`, a division by zero) is acked as the board would
  ack it. It is recorded in the state's `sim_errors` (`t`, `line`, `error`; the last 100, kept across power cycles),
  on stderr and as a `#` line in the I/O log (bytes outside ASCII escaped, so line noise cannot break the log).
  Check `sim_errors` after a test: an entry is a gap in the model or a simulated physical event (a PA5 overtravel, a
  collision), not a board reply.
- `M92` with a zero value is acked and ignored for that axis (recorded): on the board the axis would stop moving.
- An error outside any command halts the board with `halt_reason` `sim error: <error>`. The process, the port and the
  control channel stay up, and a power cycle recovers.
- A wait that never moves on (something due 'now' that no pass acts on, a model bug) would spin the process at one
  instant and keep it from SIGTERM. After 10000 such passes it is a model error instead (`no progress: ...`): inside a
  command the line is acked and recorded, and the idle loop then halts the board as above.

## I/O log

`--io-log` appends `<epoch seconds, 3 decimals> > <line>` for every line the host wrote (CR or LF ends a line) and
`<epoch> < <line>` for every line sent. This is the format of `runs/serial.log`, except that lines are not stripped
and non-printable bytes are written as `\xNN`. Transport events are `<epoch> # <text>`. Output while no host has the
port open is dropped, as on the closed USB port, and not logged. Bytes the host left unread when it closed are dropped
too, as a USB port's buffers go with the last close (`# host closed, 7 unread bytes dropped`); the `<` lines they
came from were logged when they were written.

## Python API

- `lumen_sim.Simulator`: the deterministic core on an injected clock.
  - `receive(bytes, t)`, `run_until(t)`, `next_event_time()`, `take_output(t)`, `control(request, t)`, `state(t)`,
    `power_cycle(t)`.
  - No I/O and no threads.
- `lumen_sim.bench.Bench`: a fake-clock host around it.
  - `send(line)` returns the lines, `ok_at` and the busy times; there are also `write`, `run(seconds)` and `rx`.
  - Unit tests run in milliseconds.
- `lumen_sim.hostclient.DriverClient`: a host that behaves like OpenPnP's GcodeDriver.
  - One line in flight; confirm regex `^ok.*` and the machine's error regex.
  - 10 s per line, -1 = 60 s; a shared confirmation queue; 0xFF read as end of stream.

## Tests

From the repository root:

```sh
python3 -m unittest discover -s infra/lumen-sim/tests -t infra/lumen-sim
```

- 333 tests, about 65 s; set `LUMEN_SIM_SKIP_REALTIME=1` to skip the 4 real-time reproductions (about 16 s left).
- Most tests run on the fake clock and finish in milliseconds. The pty tests start a `lumen_sim` process each and
  take a few seconds.
- `tests/golden/` holds excerpts of `runs/serial.log`; see its README.
- `tests/test_physical.py` covers INF-08: the physical faults (also during a home), the Z release and drop, the idle
  timeout (also at non-round times, under a hang guard, `tests/hang.py`) and the N1 touch against surfaces.
- `tests/test_collision_model.py` covers INF-12: loading, tip heights (a tilted bed, unequal gains, the plane's fit,
  mid-move), the sweep (the 64-gon, contact, once, a fault's jump), the see-saw's rest (also for a model set with Z
  off) and end stops, N1's seal, and that nothing changes without a model.
- `tests/test_docs.py` checks the README's USB-identity instructions, including that its `sed` line works.
- The golden tests compare reply text exactly and timing within 3 %.
- `--sim-cap` (BR-47): with it off, M115 equals the golden byte for byte; with it on, the line comes last before the
  `ok` on both personalities (`test_golden.py` `SimulatorCapability`, and through the pty in `test_transport.py`).

| Golden | Board | lumen-sim |
|---|---|---|
| `G1 B1800 F21600` + M400, M204 S1000 | 5.393 s, 2 busy | 5.406 s, 2 busy |
| `G1 B21600 F43200` + M400 | 30.766 s, 15 busy | 30.765 s, 15 busy |
| lone M410 | 2.000 s | 2.000 s |
| STOP burst, first ok | 0.999 s | 1.000 s |
| G28 from X235 Y199, M204 S1500 (default) | 5.246 s, 2 busy | 5.329 s, 2 busy |
| G28 from X217 Y63, M204 S1500, `homing_move_delay_s` 0.142 | 5.796 s | 5.727 s |
| G28 from X241 Y216, M204 S1500, `homing_move_delay_s` 0.142 | 6.855 s | 6.904 s |

`tests/test_repro.py` reproduces both machine bugs in real time through the pty, with `DriverClient`:

- **Prime timeout.** The touch-off stream with `M204 S20000` and `G1 B10836.0000 F64800`, then `G4 P250`.
  - The client times out on `G4 P250` after 10.0 s.
  - The late `ok` arrives at 10.56 s: the 100 ms first-move delay, the 5 mm Z move queued ahead (0.12 s), the screw
    move (10.08 s) and the dwell.
  - The same prime cut to 20 turns gets its `ok` at 7.2 s.
- **STOP during homing, stock.**
  - G28 from (300, 300), M410 4 s later: no ok for M410 within 10 s, G28 never completes, and M115 is unanswered
    before and after the host reopens the port.
  - After a power cycle the board answers again, in the boot state with the B count lost.
- **STOP during homing, il-safety.** The abort lines, the report, two oks, and the board answers.

## OpenPnP in front of it, end to end

openpnp-uinav's `--controller pty` (branch `infra/pty-controller`, its README) starts lumen-sim in the session
directory and keeps OpenPnP's GcodeDriver on serial, with `port-name` set to the session's link. It removes the USB
identity from the config copy and refuses to start Java unless every `port-name` resolves to `/dev/pts/N`.
jSerialComm is the real one: only the camera stubs stay on the classpath.

`e2e/test_openpnp_pty.py` drives this fork's jar on that set-up through the web API. The config is `~/.openpnp2`
(read only) with machine.xml from `tools/instancelabs/make_machine_patch.py`, and visual homing off. It is skipped
unless `LUMEN_SIM_E2E=1` and `LUMEN_SIM_E2E_JAR` are set. From the repository root:

```sh
LUMEN_SIM_E2E=1 LUMEN_SIM_E2E_JAR=target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar \
UINAV_DIR=/home/lumen/instancelabs/wt/uinav-pty LUMEN_SIM_E2E_OUT=/tmp/lumen-e2e \
    python3 infra/lumen-sim/e2e/test_openpnp_pty.py -v
```

Three tests in two OpenPnP sessions, one after the other, about 2.5 min. The 60 s is OpenPnP's own G28 timeout. Two
runs on 10-31 after the test's last fix passed all three:

| Check | Through | Result |
|---|---|---|
| (a) enable + home | `POST /api/machine/enable`, `/api/machine/home {"wait":true}` | homed in 24 s. OpenPnP's X/Y (Top camera), Z, A (N1), B (N2) equal lumen-sim's logical position within 0.01. The JVM holds only lumen-sim's `/dev/pts/N`. |
| (b) STOP 4 s into a home, head parked at 300, 300 | `POST /api/stop` | The STOP lands in `X_APPROACH`. OpenPnP logs `timeout waiting for response to M410`. The other five Stop lines then go out once each, in order, about 1 s apart, unanswered (since BR-66, PR #10; before it they were never sent). The home fails after 63 s (`homing failed: machine disabled: User requested stop.`). lumen-sim is halted (`Homing Failed`) and silent. A reconnect fails with `timeout waiting for response to G21`. After `uinav controller power-cycle` it connects, and M114 shows the boot position (B 720, count 3197). |
| (c) 30-turn prime at 180 rpm | the engine's needle touch-off (`POST /api/calibrations/touch-off {"primeAtWaste":true}`, `primeRotationDeg` 10800, the profile's `dwellMs` 250) | run ERROR `GcodeDriver:…/ttyLUMEN timeout waiting for response to G4 P250`. 20 lines. `G1 B10809.9099 F64800`, then `G4 P250` answered only by busy lines. The engine's M410 comes 10.0 s later. The late `ok` (11.25 s) is credited to M410, and later oks shift by one, as in RESEARCH.md section 6a. |

SimGuard reports `simulatorConfirmed: false` on this set-up, also after the connect (test (a) checks both). The test
drops `UINAV_SIM_CONTROLLER_CAP` from its launches, so lumen-sim never sends its simulator line here. That refuses
nothing in (a) to (c): the touch-off's prime fails before any camera is needed. On 11-03 (il/integration 43a97c453,
the fixtures' config) it passed 3 of 3 with `UINAV_SIM_CONTROLLER_CAP=1` in the environment. Run (a) without that
drop and with the cap on, and SimGuard reports `simulatorConfirmed: true` after the connect.

The backend's SIM acceptance runs unchanged on lumen-sim. Point `UINAV_DIR` at the uinav branch and set
`UINAV_SIM_CONTROLLER=pty` and `LUMEN_SIM_DIR`; uinav then puts every session on lumen-sim. Add
`UINAV_SIM_CONTROLLER_CAP=1` for the scenarios that need SimGuard ("The simulator line"). Results on 11-03
(il/integration 43a97c453, BR-40's env overrides pointed at `infra/fixtures`):

- **`load`**: passes with the cap on and with it off. Since BR-43 it checks that the USB identity is absent on the
  pty.
- **`register`** (cap on): fails. SimGuard confirms the session (the registration says `simulated: true`) and the
  SIM top camera finds all three fiducials. The N1 probe then finds no contact (`N1 went down to Z 4.180, 1.25 mm
  below the board top`), no board surface is saved, and the run is refused (`I4: no 'board' surface`).
- **`tips`** (cap on): seed, add, mount, frame and prime pass. The landing check finds no dot within ±6 mm, and
  measuring is refused because "where it lands has not been checked".
- Both have the same cause, in the backend: the SIM vacuum (`SimVacuum`) and the SIM paste (`SimPaste`) are fed
  only inside OpenPnP's GcodeServer (`GcodeServer.interpretGcode`). On the pty, lumen-sim answers M261 from its own
  vacuum model, which knows nothing of the SIM scene, so N1 reads open air. Nothing lays SIM paste for the SIM
  cameras. Infra's request to the backend (BR-69): feed both from the GcodeDriver's line stream on a
  SimGuard-confirmed pty. `padcal` needs the same two models.

Results on 10-31 (before BR-43/47):
- **`stop`**: hold/web, hold/Swing and run/web pass. **run/Swing fails, 2 runs of 2.**
  - After the Swing Stop, OpenPnP's DISABLE_COMMAND thread and the engine's abort send on the one GcodeDriver at
    the same time.
  - Each `sendCommand` clears the shared confirmation queue. With the board's M410 timing (acts on read, ok after
    1 s) one `ok` goes to the wrong waiter, although lumen-sim answered every line.
  - The engine's recovery `M400` then waits OpenPnP's full 60 s (`position read-back after stop failed: … timeout
    waiting for response to M400`).
  - The scenario's 60 s wait for the engine to go idle runs out about 40 ms before that.
  - GcodeServer acks M410 at once and hides this. It is the race RESEARCH.md section 2 describes, and a backend
    matter.

## Known gaps

- **The SIM scene.** SimGuard accepts lumen-sim with `--sim-cap` (BR-47), so the SIM cameras, the SIM balance,
  `/api/sim` and the SIM-only settings work on the pty. The SIM vacuum and the SIM paste do not: they live in
  GcodeServer only (above). lumen-sim's own N1 model sees only the surfaces given by `--surface-z` or `set`, and not
  `instancelabs/sim-scene.json`. Until the backend feeds both models on the pty, `register`, `tips` and `padcal` fail
  on lumen-sim.
- Not modelled:
  - stepper current;
  - TMC drivers (`M122` prints nothing);
  - crashes at the far ends: the head goes where the steps say, and loses steps only through `lost_steps` (and Z at
    a collision model's end stops);
  - how a released Z moves: the `z_drop` (or the see-saw's rest) is instant and fixed, with no slow sag and no snap to
    a full step when re-energised;
  - a collision's force: the head is not stopped, and a tip pressing on a top has no give but `contactDepthMm`;
  - USB identity: a pty has none;
  - the boot banner, which is lost on USB CDC anyway;
  - `N` line numbers and checksums;
  - M206 offsets.
- The il-safety firmware line keeps the stock build date; the real il build's date is unknown.
