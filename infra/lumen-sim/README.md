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
symlink and opens the pty. Either drop `usb-serial-number` and `usb-description` from the driver, or keep them and
accept the "Falling back to the configured port name" warnings.

**The link's name must start with `ttyLUMEN`**, and lumen-sim refuses any other (CLI and power cycle). When the
path does not exist and is not a symlink, jSerialComm's `getCommPort` opens `/dev/<path>` or `/dev/<name>` instead.
The link is missing whenever lumen-sim is stopped or its port is down, so a link named `ttyACM0` would make OpenPnP's
reconnect open the real board. Never name a link like a device.

| Option | Meaning |
|---|---|
| `--link PATH` | Where the pty slave is published (name `ttyLUMEN*`). The link exists only while the port does: it is removed when the port goes down (disconnect, DFU, a power cycle's down time) and on exit, and made again for the new pty. |
| `--personality stock\|il-safety` | Firmware build (below). Default `stock`. |
| `--control SOCK` | Unix socket for the control channel (JSON lines). |
| `--io-log FILE` | Append the traffic in `serial.log` format. |
| `--time-scale 1.0` | Simulator seconds per wall second. Keep 1.0 when host timeouts matter: they are wall clock. |
| `--park X,Y,Z` | Physical head position at power-on, in mm from the X/Y/Z switches. Default `5,5,31.5`. |
| `--kill-messages` | Print `echo:<reason>` and `Error:Printer halted. kill() called!` when the board kills (default silent). |
| `--vacuum N` | Raw 24-bit N1 vacuum reading (default -1500000, open air). |
| `--seed N` | Seed for random fault delays. |

It stops on SIGINT, SIGTERM or `ctl stop`, and removes its symlink and socket.

```sh
python3 -m lumen_sim ctl --control SOCK state
python3 -m lumen_sim ctl --control SOCK fault '{"fault": "no_ok", "match": "^G4"}'
python3 -m lumen_sim ctl --control SOCK set '{"vacuum": -1600000, "rs485": {"FF00": "0017"}}'
python3 -m lumen_sim ctl --control SOCK power-cycle [--link NEW_PATH] [--down-s 2]
python3 -m lumen_sim ctl --control SOCK clear
python3 -m lumen_sim ctl --control SOCK stop
```

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
- Boot state: X=Y=Z=0, A=B=720.00 (counts 3197), nothing homed, outputs off. Settings are the firmware defaults; there
  is no EEPROM.

**The physical head.**
- It is tracked apart from Marlin's belief. The X/Y/Z min switches trigger at physical 0.
- A power cycle resets the belief and the counts; the head stays where it is.
- `M119` reads the switches.

**Commands.**
- Motion and waits: `G0 G1` (`G2 G3` move straight to the end point), `G4`, `G28`, `G90 G91 G92`, `M400`.
- Steppers: `M17`, `M18`/`M84` (wait for motion, then disable; `S` is accepted and ignored).
- Reports: `M105` (`ok T:0`), `M114`, `M115`, `M119`, `M211`, `M503` (from the current settings, as in the log),
  `M113`, `M220`.
- Settings: `M92`, `M201`, `M203`, `M204`, `M205`, `M502`, `M906`.
- Outputs: `M106`/`M107` (immediate, not synchronised with motion; no S means 255, no P means P0), `M150`.
- Peripherals:
  - `M260`/`M261`: style S0 raw, S1 hex, S2 int16 of the last two bytes, S3 decimal; `Error:Bad i2c request`.
  - `M485`: `rs485-reply: TIMEOUT` after 100 ms unless the reply table has the request; a reply comes after 54 ms.
  - `M3426`: `Error:MCP342X i2c error`; no MCP3426 is assumed.
- Accepted and only acked: `M42 M43 M108 M110 M111 M122 M206 M569 M999 G17 G18 G19 G21`.
- Quick-stop and kill: `M410` and `M112` (below).

**Vacuum model.** `M261 A109 B<n>` returns the reading big-endian as a 24-bit two's complement. The default is a
constant open-air -1500000. Pass `vacuum=` an int or a callable `(firmware) -> int` to `Simulator`, or set an int
through the control channel.

**Homing (`G28`).**
- It waits for motion, then homes X, then Y, then Z. Z homes at the safe point (0,0), then moves to Z 31.5. A and B
  are untouched.
- Each axis does a fast approach (1.5 x travel at 150 mm/s and the current M204 accel), backs off 10/10/2 mm, then
  re-bumps at 1/2, 1/2, 1/4 speed.
- After each move toward a switch, `validate_homing_move()` kills the board if the switch was not hit.
- `G28 Z` alone also homes unhomed X/Y first; with X/Y unhomed it prints `echo:Home XY First`.
- The phase (`X_APPROACH`, `X_BUMP`, `X_SLOW`, ..., `Z_SAFE_XY`, `Z_AFTER`) is in the control state.

**Homing time.**
- The source's kinematics come out about 1.4 s shorter per G28 than the board's. The sim adds 0.142 s to each of the
  10 homing moves, fitted to the two logged G28s whose start positions are known: 5.73 s vs 5.80 s and 6.90 s vs
  6.86 s on the board.
- The one logged G28 right after a power cycle took 2.82 s. The model needs at least ~3.4 s even with the head
  already at the switches, so the cause is something not understood (perhaps the speed or acceleration during
  homing). Its start position is unknown, so it is checked for text only.

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

**`M112`.** It kills at once (stock: on read) with the steppers off.

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

## Faults and the control channel

Connect to `--control` and send one JSON object per line; each gets one JSON reply line with `"ok": true|false`.
A malformed request (bad JSON, a bad regex, a `count` that is not an integer >= 1, a non-finite or negative time, a
link that is not `ttyLUMEN*` or has no directory) gets `"ok": false` with an `error` and changes nothing.

| Request | Effect |
|---|---|
| `{"cmd": "state"}` | `state` holds: time, personality, boots, halted and halt_reason, logical/native/counts/stepper_counts, physical, homed, homing_phase, moving, planner_blocks, ring, fans, fan_history (`t`, `p`, `s`, `source`), led, enabled, soft_endstops, settings, watchdog, faults, lines_read, sim_errors (below), and the transport: link, pts (null while down), host_open, frozen, link_down. |
| `{"cmd": "fault", "fault": "no_ok", "nth": 3}` | No `ok` for the 3rd line read from now. Use `"match": "<regex>"` instead for every matching line; `"count"` limits the hits. |
| `{"cmd": "fault", "fault": "delay_ok", "s": 0.5, ...}` | Extra delay before that `ok`; `"random": [lo, hi]` for a seeded random delay. Later output queues behind it. |
| `{"cmd": "fault", "fault": "dup_ok", ...}` | The `ok` is sent twice. |
| `{"cmd": "fault", "fault": "inject", "kind": "error"\|"start"\|"unknown"}` | Sends `Error:simulated fault`, `start` or `echo:Unknown command: "M999999"`; `"text": "..."` for any line; `"after_s": 1.5` to delay. |
| `{"cmd": "fault", "fault": "inject_bytes", "hex": "ff"}` | Raw bytes. A 0xFF kills OpenPnP's reader thread, which reads it as end of stream. |
| `{"cmd": "fault", "fault": "partial", "text": "X:1"}` | Text with no newline. |
| `{"cmd": "fault", "fault": "halt"}` | Dead MCU: no output, input discarded, until a power cycle. |
| `{"cmd": "fault", "fault": "disconnect", "down_s": 1.0}` | Close the pty master (the host gets EIO) and remove the link, so the port is absent, as an unplugged one. After `down_s`, a new pty behind the link; the controller state is kept. `"down_s": null` stays down until a power cycle. |
| `{"cmd": "fault", "fault": "frozen", "on": true}` | Stop reading the port. The host's writes block once the pty buffer fills. |
| `{"cmd": "power_cycle", "link": null, "down_s": 0}` | Close the master, boot state, new pty behind the link. The head stays put. `"link"` publishes it at a new path instead and removes the old link (the board's ttyACM0 becoming ttyACM1; here e.g. ttyLUMEN0 to ttyLUMEN1). `"down_s": 2` keeps the port absent for 2 s first, as while the board resets and USB re-enumerates (the board is reset at once; default 0). |
| `{"cmd": "set", "kill_messages": true, "vacuum": -1600000, "rs485": {"REQHEX": "REPLYHEX"}, "mcp3426": 1234}` | Knobs. |
| `{"cmd": "clear"}` | Drop all ok faults and pending injections, unfreeze. |
| `{"cmd": "stop"}` | Exit. |

**Model errors.** The board answers whatever the values; the simulator must not die on one stray line either.
- A line the model cannot run (for example `M203 X0` then `G1 X10`, a division by zero) is acked as the board would
  ack it. It is recorded in the state's `sim_errors` (`t`, `line`, `error`; the last 100, kept across power cycles),
  on stderr and as a `#` line in the I/O log. Check `sim_errors` after a test: an entry is a gap in the model, not
  a board reply.
- `M92` with a zero value is acked and ignored for that axis (recorded): on the board the axis would stop moving.
- An error outside any command halts the board with `halt_reason` `sim error: <error>`. The process, the port and the
  control channel stay up, and a power cycle recovers.

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

- 125 tests, about 60 s; set `LUMEN_SIM_SKIP_REALTIME=1` to skip the 4 real-time reproductions (about 10 s left).
- Most tests run on the fake clock and finish in milliseconds. The pty tests start a `lumen_sim` process each and
  take a few seconds.
- `tests/golden/` holds excerpts of `runs/serial.log`; see its README.
- The golden tests compare reply text exactly and timing within 3 %.

| Golden | Board | lumen-sim |
|---|---|---|
| `G1 B1800 F21600` + M400, M204 S1000 | 5.393 s, 2 busy | 5.406 s, 2 busy |
| `G1 B21600 F43200` + M400 | 30.766 s, 15 busy | 30.765 s, 15 busy |
| lone M410 | 2.000 s | 2.000 s |
| STOP burst, first ok | 0.999 s | 1.000 s |
| G28 from X217 Y63, M204 S1500 | 5.796 s | 5.727 s |
| G28 from X241 Y216, M204 S1500 | 6.855 s | 6.904 s |

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
| (b) STOP 4 s into a home, head parked at 300, 300 | `POST /api/stop` | The STOP lands in `X_APPROACH`. OpenPnP logs `timeout waiting for response to M410`. The other five Stop lines are never sent. The home fails after 63 s (`homing failed: machine disabled: User requested stop.`). lumen-sim is halted (`Homing Failed`) and silent. A reconnect fails with `timeout waiting for response to G21`. After `uinav controller power-cycle` it connects, and M114 shows the boot position (B 720, count 3197). |
| (c) 30-turn prime at 180 rpm | the engine's needle touch-off (`POST /api/calibrations/touch-off {"primeAtWaste":true}`, `primeRotationDeg` 10800, the profile's `dwellMs` 250) | run ERROR `GcodeDriver:…/ttyLUMEN timeout waiting for response to G4 P250`. 20 lines. `G1 B10809.9099 F64800`, then `G4 P250` answered only by busy lines. The engine's M410 comes 10.0 s later. The late `ok` (11.25 s) is credited to M410, and later oks shift by one, as in RESEARCH.md section 6a. |

SimGuard reports `simulatorConfirmed: false` on this set-up. That refuses nothing in (a) to (c): the touch-off's
prime fails before any camera is needed.

The backend's SIM acceptance runs unchanged on lumen-sim. Point `UINAV_DIR` at the uinav branch and set
`UINAV_SIM_CONTROLLER=pty` and `LUMEN_SIM_DIR`; uinav then puts every session on lumen-sim. Results on 10-31:

- **`load`**: every check passes except two of `saved_config_keeps_patch`, `usb-serial-number` and
  `usb-description`. The pty mode removes the real board's USB identity on purpose, so those two can only pass with
  GcodeServer.
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

- **SimGuard.** SimGuard treats only loopback TCP with `FIRMWARE_NAME:GcodeServer` as a simulator.
  - On this pty OpenPnP runs as a real machine: the SIM top camera (SimBoardCamera refuses to open: "simulation-only
    camera on a non-simulated machine"), the SIM bottom camera, the SIM balance and the `/api/sim` endpoints are
    off.
  - SIM-only settings (shortened calibration statistics, fast probe timings) are refused.
  - Scenarios that need those (register, padcal, tips) cannot run here until the backend recognises lumen-sim.
    Proposed: a sim-only M115 field, see the backend request.
- Not modelled:
  - stepper idle timeout and current;
  - TMC drivers (`M122` prints nothing);
  - lost steps or crashes at the far ends (the head goes where the steps say);
  - USB identity: a pty has none;
  - the boot banner, which is lost on USB CDC anyway;
  - `N` line numbers and checksums;
  - M206 offsets.
- The il-safety firmware line keeps the stock build date; the real il build's date is unknown.
