# INF-03 controller and serial layer for a Marlin pty emulator: research report

Everything below comes from reading files and git history only. Nothing was built, run, opened on a serial port, or moved.

## 0. Findings to read first

1. **`~/.openpnp2/machine.xml` is not the config the lab machine runs.**
   - It is dated Oct 27. It has `port-name="ttyS4"`, no `DISABLE_COMMAND`, and no `G92 A0 B0` after homing.
   - Real sessions run on copies at `~/instancelabs/realcfg-sw-YYYYMMDDx/`. The newest is `realcfg-sw-20261030n`.
   - OpenPnP output for those sessions is under `/tmp/openpnp-uinav-lumen/sw-realN/`. `realcfg-sw-20261030k/` itself has no log directory.
   - Section 1 quotes the realcfg version.
2. **Both known bugs are explained by stock-firmware code paths** (section 6).
   - **Prime timeout.** The engine gives every streamed line 10 s. Marlin's `G4` first waits for all queued motion, then dwells. So the `ok` for the `G4` after a 10 s screw move arrives late and the driver times out.
   - **STOP during homing.** On stock firmware `M410` is acted on the moment Marlin reads the line, even inside `G28`. That cuts the homing move short. `validate_homing_move()` then calls `kill("Homing Failed")` and the board stops answering.
   - This resolves the open question of how `M410` could act during `G28` when the firmware reports `EMERGENCY_PARSER:0`.
3. **The backend can tell a pty from the real port in three ways.**
   - (a) The real config carries a USB identity. A pty has none, so the port matcher logs warnings and falls back to the port name.
   - (b) SimGuard only treats a machine as simulated on loopback TCP with `FIRMWARE_NAME:GcodeServer`. A pty that answers like Marlin is "real", so all SIM-only features (sim cameras, sim balance, sim endpoints) switch off.
   - (c) The private uinav harness stubs jSerialComm and opens nothing.
   - Point (b) conflicts with INF-03's acceptance line "existing simulation scenarios pass against it". This needs a decision with the backend role.

## 1. Real machine config (`realcfg-sw-20261030n/machine.xml`, driver section)

### Driver
- Class: `org.openpnp.machine.reference.driver.GcodeDriver` (synchronous, not GcodeAsyncDriver).
- `motion-control-type="ConstantAcceleration"`, `communications="serial"`, `connection-keep-alive="true"`, `sync-initial-location="true"`, `allow-unhomed-motion="false"`, `units="Millimeters"`.
- `timeout-milliseconds="10000"`, `connect-wait-time-milliseconds="6000"`, `infinity-timeout-milliseconds="60000"`, `dollar-wait-time-milliseconds="50"`.
- `remove-comments="false"`, `compress-gcode="false"`, `logging-gcode="false"`, `using-letter-variables="true"`, `backslash-escaped-characters-enabled="false"`.

### Serial
- `line-ending-type="LF"`, `port-name="/dev/serial/by-id/usb-STMicroelectronics_MARLIN_OPULO_LUMEN_REV5_CDC_in_FS_Mode_387932683335-if00"` (resolves to ttyACM0).
- `baud="115200"`, `flow-control="RtsCts"`, 8N1, `set-dtr="false"`, `set-rts="false"`.
- `usb-serial-number="387932683335"`, `usb-description="MARLIN_OPULO_LUMEN_REV5"`.
- The log shows the board as USB 0483:5740.
- Stored identity: `detected-firmware`: `FIRMWARE_NAME:Marlin bugfix-2.1.x (Sep 19 2024 13:39:21) … MACHINE_TYPE:LumenPnP EXTRUDER_COUNT:0 AXIS_COUNT:5`.

### Commands
Comments are sent to the controller as written.

- `COMMAND_CONFIRM_REGEX`: `^ok.*`
- `COMMAND_ERROR_REGEX`: `(?i).*(?:\berror\s*:|!!|\bstart\b|reset|disconnect|\bresend\s*:|\brs\s+N?\d|unknown command|halted|fatal|\bkilled\b).*`
- `CONNECT_COMMAND`:
  - `G21 ; Set millimeters mode `
  - `G90 ; …`
  - `M260 A112 B1 S1`, `M260 A109`, `M260 B48`, `M260 B27`, `M260 S1` (VAC1 set-up)
  - the same five lines with `B2` (VAC2 set-up)
  - `M906 B580 ; auger motor (J/B) current…`
- `DISABLE_COMMAND`: `M410`, `M107 P3`, `M107 P2`, `M107 P1`, `M107 P0`, `M18 B`
- `HOME_COMMAND`:
  - `{Acceleration:M204 S%.2f ; Initialize acceleration}`. This becomes `M204 S500.00 …`, the lowest axis acceleration (A and B are 500).
  - `G28 ; Home all axes`
  - `G92 A0 B0 ; … Marlin boots at A=B=720`
- `MOVE_TO_COMMAND`: `{Acceleration:M204 S%.0f }` and `G1 {X:X%.4f} {Y:Y%.4f} {Z:Z%.4f} {A:A%.4f} {B:B%.4f} {FeedRate:F%.0f} ; move to target`
- `MOVE_TO_COMPLETE_COMMAND`: `M400 ; Wait for moves to complete before returning`
- `GET_POSITION_COMMAND`: `M114 ; get position`
- `POSITION_REPORT_REGEX`: `^.*X:(?<X>-?\d+\.\d+)\s*Y:(?<Y>-?\d+\.\d+)\s*Z:(?<Z>-?\d+\.\d+)\s*A:(?<A>-?\d+\.\d+)\s*B:(?<B>-?\d+\.\d+).*`. Count values must stay integers, or the greedy `.*` would match them.
- `SET_GLOBAL_OFFSETS_COMMAND`: `G92 {X:X%.4f} … ; reset coordinates`
- `DELAY_COMMAND`: `{TimeMS:G4 P%d} ; …`
- There is no `ENABLE_COMMAND`, `HOME_COMPLETE_REGEX` or `MOVE_TO_COMPLETE_REGEX`.

### Actuators that exist
Only four: VAC1, VAC2, LED, PhotonFeederData.

- **VAC1** (`ACT171086c0660cfc3b`)
  - Actuate: `{True:M106 P1 S180}{False:M107 P1};` then `{True:M106}{False:M107};`
  - Read: `M260 A112 B1 S1`, `M260 A109 B6 S1`, `M261 A109 B1 S2`
  - Read regex: `^.*data:(?<Value>.*)`
- **VAC2** (`ACT171086f27295ab60`)
  - Actuate: `{True:M106 P2 S255}{False:M107 P3};` then `{True:M106 P3 S255}{False:M107 P2};`
  - Read: same as VAC1 with `B2`
- **LED**: `{True:M150 P255 R255 U255 B255}{False:M150 P0}`
- **PhotonFeederData**: `M485 {Value}`, regex `rs485-reply: (?<Value>.*)`
- Commands for ids that no longer exist remain in the file (e.g. `M42 P35`, `M3426 G4 C1 I1 A6`, `M271`). They are stale.

### Axes
| Letter | OpenPnP axis | Notes |
|---|---|---|
| X | x | — |
| Y | y | — |
| Z | z1, home 31.5 | z2 is mapped as 63 − z1 (see-saw) |
| A | a | N1 rotation |
| B | b | N2 rotation, which is the paste screw |

- B is the screw per `instancelabs/dispense-config.json` `"bAxisName":"b"`, `M18 B`, and `M906 B580 ; auger (J/B)`. Marlin calls it the J axis, with `AXIS5_NAME 'B'`.
- Axis limits in OpenPnP: X 200 mm/s and 1000 mm/s²; B 100 deg/s and 500 deg/s².
- Profile in use (`dispense-config.json`): `screwRpm` 180, `screwAccelDegS2` 20000, `netStepsPerDot` 800, `suckBackDeg` 10, `dwellMs` 0, `pumpToValveSettleMs` 1000, `valveSettleMs` 1500.

## 2. How the backend talks to the controller

### Path
`DriverGcodeChannel` (`dispense/DriverGcodeChannel.java:33-99`) calls `GcodeDriver.sendCommand(line, timeoutMs)`.

- One line in flight at a time: it writes the line, then waits for the next `^ok` line.
- `echo:busy: processing` does not extend any timeout.
- Every line must pass the allowlist in `ControllerCommands.check`: `G0 G1 G4 G92 M18 M106 M107 M114 M115 M201 M203 M204 M400 M410 M990` (`ControllerCommands.java:99-101`).
  - M106 is allowed only as `M106 P2 S255` or `M106 P3 S255`.
  - M107 is allowed only with P0 to P3.
- The N1 probe also has these exact lines: `M106`, `M106 P1 S180`, the M260 set-up and select lines, and `M261 A109 B3 S1` (`ControllerCommands.java:58-69`).
- The backend never sends `G28` itself. It calls OpenPnP's `m.home()` (`DispenseService.java:3145`).

### Lines the backend sends

| Lines | Where | Timeout |
|---|---|---|
| `G92 B0`, `M201 B<20000>`, `M203 B<2400>`, `M204 S<a>`, `M990 S3000` (only when M115 shows `Cap:HOST_WATCHDOG:1`) | `GcodeStreamBuilder.java:506-523` | 10 s |
| `M106 P2 S255`, `G4 P1000`, `M106 P3 S255`, `G4 P1500` (pressurize) | `:525-534` | 10 s |
| Dot: `G0 X Y F12000` (plus a backlash second move), `G1 Z F3000`, `M204 S20000`, `G1 B<deg> F<rpm×360>`, `G4 P<dwell>`, `G1 B<back>`, `M204 S2000`, `G1 Z`, `M204 S2000` | `:596-640`, `:724` | 10 s |
| End: `M400`, `M107 P3`, `M107 P2`, `M990 S0`, `G92 B0`, `G1 Z`, `M400` | `:642-668` | M400 = −1, which means 60 s |
| `G4` is cut into pieces of at most 1000 ms when the watchdog is armed | `:670-681` | — |
| Shutdown or abort: `M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B` (+`M990 S0`), each via `sendCritical`, up to 3 tries after a stale error | `DispenseExecutor.java:273-291`, `DriverGcodeChannel.java:45-68` | 10 s each |
| Keepalive `M990` every 1000 ms when armed | `DispenseExecutor.java:203-208` | 10 s |
| Every connect and every run: `M115` (identity and watchdog) | `DispenseService.java:335-350`, `:5722-5745` | 10 s. A timeout means "no watchdog"; any other error refuses the run and un-homes. |
| B count: `M400` then `M114`, parsed by `Count\b.*?(?:^|\s)B:(-?\d+)` | `:5816-5818`, `:2699-2709` | −1 / 10 s |
| Recovery: `M400`, `M204 S2000`, `M114` | `:5872-5895` | −1 / 10 s |
| Home: `M114`, then if N1 is low `G1 Z31.5 F600` + `M400`, then `m.home()`. A disable during homing disconnects all GcodeDrivers. | `:3090-3165` | 10 s; 70 s; G28 = −1 (60 s) |
| Probe: `G1 Z… F…`, `G1 X Y`, `M400`, `G4 P`, `M261 A109 B3 S1` | `VacuumProber.java:750-822` | command timeout plus the move's own time |

### Web STOP (`MachineEndpoints.java:76-151`)
1. `svc.abort` stops the engine.
2. `ctx.pressure.emergencyStop` sends valve OFF then pump OFF.
3. `m.setEnabled(false)` runs on a separate thread ("il-api-stop") if the machine thread is busy. This sends `DISABLE_COMMAND`.

As a result, two threads can call `sendCommand` at the same time. Each one clears the shared confirmation queue, so `ok` lines can be credited to the wrong command.

## 3. OpenPnP driver internals relevant to emulation

### Connect (`GcodeDriver.java:500-533`)
1. Open the port.
2. Start the reader thread.
3. Sleep 6 s.
4. Drain any startup lines. Errors here are swallowed: `bailOnError` clears them inside a try/catch.
5. `setEnabled(false)`, which does nothing because `connected` is still false.
6. Send each `CONNECT_COMMAND` line with a 10 s timeout.

The startup banner is never needed. An early `start` line is harmless.

### Send (`:1247-1277`)
1. `bailOnError()` throws `"<conn> error response from controller: <line>"` if an error line arrived earlier. It also throws `"IO Error on reading from the controller."` if the reader thread is dead.
2. Clear the confirmation queue.
3. Write `line + "\n"`.
4. `waitForConfirmation` polls up to `timeout`. A timeout of −1 means 60 s.
5. On expiry it throws `"<conn> timeout waiting for response to <line>"` (`:1295`).
   - The connection name is `GcodeDriver:` plus the port name.
   - A later `ok` is credited to whatever command is sent next.

### Reader (`:1502-1543`)
- `readUntil("\r\n")`: either CR or LF ends a line, and empty lines are skipped (`ReferenceDriverCommunications.java:103-126`).
- Each line is matched against the confirm regex (adds to the confirmation queue), the error regex (sets `errorResponse`), and the position regex (adds to the position queue and calls `setToDriverCoordinates` unless motion is pending) (`:1552-1600`).
- **Pitfall:** `SerialPortCommunications.read()` returns `b[0]` as a signed byte (`:209-226`). A received `0xFF` becomes −1, which reads as end of stream. The reader thread then logs "Failed to read gcode response" and exits.
- `readBytes` returning −1 (EIO or the port is gone) throws IOException, and the reader also exits.

### Port open (`SerialPortCommunications.java:114-134`)
1. `portMatcher.resolve(usbSerialNumber, usbDescription, portName)`. Order: USB serial number, then description, then the port name as given (`SerialPortMatcher.java:91-150`).
2. `SerialPort.getCommPort(descriptor)`, then `openPort(0)`.
3. `setComPortParameters(115200, 8, 1, none)`.
4. `setFlowControl(CTS|RTS)`.
5. DTR and RTS are not touched.
6. Timeouts: `TIMEOUT_READ_SEMI_BLOCKING|TIMEOUT_WRITE_BLOCKING`, 0, 0. Reads block indefinitely, and writes block if the far end stops draining.

jSerialComm is version 2.10.2 (`pom.xml:66-70`). Its `getCommPort` bytecode does:
- expands `~/`;
- **resolves a symlink to its canonical path**;
- if the path does not exist, tries `/dev/<name>`, then `/dev/<basename>`;
- otherwise throws `SerialPortInvalidPortException("Unable to create a serial port object from the invalid port descriptor: …")`.

There is no rule against names like `/dev/pts/N`, but the path must exist at connect time.

Not verified here (nothing was run): whether the native `openPort` and termios setup succeed on a pty, and whether `CRTSCTS` is harmless on one. My inference is yes on both. Earlier research (`docs/simulation-research-final.md:377`) notes an old jSerialComm bug with socat symlinks. Smoke-test both a direct `/dev/pts/N` and a symlink.

### Completion and position
- `waitForCompletion` sends `M400` with 10 s divided by machine speed, or −1 for "indefinitely" (`:1038-1072`).
- `getReportedLocation` sends `M114` with −1, then polls the position queue (`:779-801`).
- `home()` sends the `HOME_COMMAND` lines with −1 (`:581-661`).
- `detectFirmware` (Issues & Solutions only) sends `M115`, then `M114` (`:1887-1895`).

### No reset handshake
On the STM32 USB CDC, opening or closing the port does not reset the board. Evidence: after the session-5 hang, reopening the port did not reset it (REAL_MACHINE_TODO item 32). There is no banner on connect.

## 4. GcodeServer and the fork's SIM hooks, and their limits

### `GcodeServer.java`
- TCP only, in-process, one worker per connection.
- `M115` answers `FIRMWARE_NAME:GcodeServer, …` (`:51`, `:603-614`).
- `M114` answers one line `ok C: X:… Y:…`, with the ok on the same line (`:577-601`).
- `G4`, `M400` and `G92` wait for the remaining planned motion plus P or S, **capped at 20 s** (`:44`, `:622-666`).
- Motion time comes from OpenPnP's axis limits via `Motion.java:274-299`, not from Marlin's M201/M203/M204 values.
- `G28` runs at ×5 speed to the OpenPnP home coordinates. There are no busy lines and no report.
- Codes it does not know get a bare `ok`. A parse error gets `*** Unknown syntax…` with no `ok`, which produces a driver timeout.
- Not modelled: `M410`, kill/halt, planner buffer limits, step counts, soft endstops, error lines.

### Fork hooks
- **`SimPaste`** emits a separate line `Count X:0 Y:0 Z:0 B:<n>` before M114's `ok C:` line. It uses 4.44 steps/deg and 1600 steps/rev (`SimPaste.java:60-77`).
- **`SimVacuum`** answers only `M261 A109 B3 S1`, with `echo:i2c-reply: from:109 bytes:3 data:%02X %02X %02X` (`SimVacuum.java:152-166`).
- Both live inside GcodeServer and only exist when a sim scene exists.

### Gating
- **`SimGuard`** treats a machine as simulated only when it uses loopback TCP and identifies as `FIRMWARE_NAME:GcodeServer` (`SimGuard.java:56-100`).
- It gates `SimTipCamera`, `SimBoardCamera`, `SimBalance` and `SimEndpoints`.
- **uinav** rewrites every driver to TCP and puts stubs for jSerialComm first on the classpath; the stubs open nothing (`openpnp-uinav/README.md:56-67`).

## 5. Real firmware

### Stock build
Source: `wt/marlin-stock-7776c73/Marlin/`. This is what the board runs. Every M115 in `runs/serial.log` up to 10-31 19:02 shows `EMERGENCY_PARSER:0` and no `HOST_WATCHDOG`.

| Setting | Value | Where |
|---|---|---|
| Axes | `AXIS4_NAME 'A'`, `AXIS5_NAME 'B'`, both `ROTATES` | `Configuration.h:185-190` |
| Steps per unit | {320, 320, 40, 4.44, 4.44} | `:1221` |
| Max feed (M203) | {1500, 1500, 500, 50000, 50000} | `:1228` |
| Max accel (M201) | 10000 on every axis | `:1241` |
| M204 defaults | P2000, R3000, T2500 | `:1258-1260` |
| Junction deviation | 0.013 | `:1304` |
| Home direction | X, Y, Z = −1; I, J = 0, so A and B are never homed | `:1749-1753` |
| Travel | X 0..433, Y 0..487, Z 0..63, A and B 0..720 | `:1762-1775` |
| Soft endstops | Clamp only axes that have been homed, silently | `motion.cpp:931-971` |
| Homing | `Z_SAFE_HOMING` at (0,0); `Z_AFTER_HOMING 31.5`; `VALIDATE_HOMING_ENDSTOPS` | `Configuration.h:2227-2239`, `:1745` |
| Homing feed | 150 mm/s on X, Y, Z | `:2236` |
| Bump | {10, 10, 2} mm, divisor {2, 2, 4} | `Configuration_adv.h:910-911` |
| Homing order | X, then Y, then Z (no QUICK_HOME, no HOME_Z_FIRST, no Z_HOMING_HEIGHT) | — |
| Keepalive | `HOST_KEEPALIVE_FEATURE`, `DEFAULT_KEEPALIVE_INTERVAL 2` | `Configuration.h:2331-2332` |
| Buffers | `BLOCK_BUFFER_SIZE 16`, `BUFSIZE 4`, `MAX_CMD_SIZE 96`, `TX_BUFFER_SIZE 0` | `Configuration_adv.h:2507-2527` |
| Parser options | `EMERGENCY_PARSER`, `ADVANCED_OK`, `M114_DETAIL`, `M114_REALTIME` all off. `M114 D` and `M114 R` behave as plain M114. | `:2565`, `:2594`, `:3798-3799` |
| Other features | `EXPERIMENTAL_I2CBUS`, `DIRECT_PIN_CONTROL`, `PINS_DEBUGGING`, `NEOPIXEL_LED`, `RS485_SERIAL_PORT 1`, `TMC_DEBUG`, `ARC_SUPPORT` | — |
| EEPROM | `EEPROM_SETTINGS` off | — |

### Command behaviour (stock source)
- `M114` reports the **projected** position: `current_position` plus `planner.position` counts. It does not wait for motion (`M114.cpp:150-151`, `motion.cpp:250-253`).
- `G92` prints a position report line (`G92.cpp:131`).
- `G28`: waits for motion, homes, then prints a report (`G28.cpp:247`, `:635`).
- `G4`: waits for motion, then dwells (`G4.cpp:30-40`).
- `M400`: waits for motion.
- `M18 B` and other `M18`/`M84` with an axis: wait for motion (`M17_M18_M84.cpp:228`).
- M106, M107, M150, M260 and M261 act at once and are **not** synchronised with motion.

### `M410` on stock is not purely queued
- `queue.cpp:543-548`: with EMERGENCY_PARSER off, an incoming `M410` runs `quickstop_stepper()` **when the line is read**.
- Lines are read by `get_available_commands()`, which `manage_inactivity()` calls from `idle()` (`MarlinCore.cpp:419-421`).
- Every wait (`planner.synchronize`, G4 dwell, M400, homing, a full planner) calls `idle()`.
- So `M410` acts within one USB round trip even during G4, M400 or G28, as long as the 4-slot command buffer has room. The queued `M410` then runs again later.
- Each quick-stop holds its `ok` about 1 s (`cleaning_buffer_counter`), and the planner drops new moves for that 1 s.
- Observed `M410` ack: 1.0 s inside STOP bursts, and 2.000 s for a lone `M410` (`serial.log:122929-122930`). The double quick-stop explains the 2 s case, but this is inferred.
- The roadmap's claim that M410 "waits behind a G4/M400" (`plan-2026-10-27…:343`) is contradicted by this code.

### Kill path
- `validate_homing_move()` calls `kill(GET_TEXT_F(MSG_KILL_HOMING_FAILED))` whenever the endstop is not triggered at the end of a homing move toward it (`endstops.cpp:261-264`, `motion.cpp:1900-1908`).
- `kill()` prints `echo:Homing Failed` and `Error:Printer halted. kill() called!` (`MarlinCore.cpp:908-925`).
- `minkill()` then waits 0.6 s, calls `cli()`, and loops forever (`:934-964`).
- The stepper interrupt runs until that `cli()`, so the move in progress coasts on for 0.6 s; nothing steps after it. On this path (`steppers_off` false) minkill() then disables only the E steppers (0.25 s after the `cli()`): X, Y and Z stay energised. Fan outputs (pump and valve) stay as they were. (il-safety's kill switches all four outputs off and stops the planner first: README "Personalities".)

### Strings
| Event | Text |
|---|---|
| Unknown command | `echo:Unknown command: "<cmd text>"` then `ok` (`parser.cpp:389-391`) |
| Busy | `echo:busy: processing` |
| Kill | `Error:Printer halted. kill() called!` |
| Boot banner | `start`, `Marlin bugfix-2.1.x`, `echo: Last Updated: … \| Author: (none, default config)`, `echo: Compiled: …`, `echo: Free Memory: … PlannerBufferBytes: …`, `echo:Hardcoded Default Settings Loaded` (`MarlinCore.cpp:1200-1308`, `settings.cpp:3444`) |
| M105 | `ok T:0` |
| M3426 | `V:<n> C:<c> G:<g> I:<0/1>` |
| M485 | `rs485-reply: <HEX>` or `rs485-reply: TIMEOUT` |

The boot banner is lost on USB CDC because the board re-enumerates after a reset.

M261 reply styles (`twibus.cpp`):
- S1: `echo:i2c-reply: from:109 bytes:3 data:E1 B7 0A`
- S2: a signed int16 made from the last two bytes, e.g. `data:-4482` or `data:255`
- S0: raw bytes, which can include NUL or 0xFF

### il/safety build (`wt/marlin-safety`, commit d973aef69 = `firmware/patched`, not flashed)
- `EMERGENCY_PARSER` on, so M108, M112 and M410 act on receipt.
- `M990` host watchdog:
  - `M990 S<ms>` arms it; replies `echo:Host watchdog: armed S<ms>` or `echo:Host watchdog: off`; `off tripped` after a trip.
  - Out of range: `Error:M990 S must be 0 or 100..3600000`.
  - On a trip it prints `Error:host watchdog`, quick-stops, turns all four outputs off, and disables J.
  - Silence is measured only while idle; a busy command holds the timer (`host_watchdog.cpp:36-74`).
- M115 adds `Cap:HOST_WATCHDOG:1` and shows `EMERGENCY_PARSER:1`.
- `M410` turns P3 then P2 off at once.
- An `M410` during G28 prints `echo:G28 aborted by quick-stop: axes not homed`, then a report, then `ok`, with no kill.
- Z is 160 steps/mm; J max acceleration is 20000; `M106 P2` and `M106 P3` are forced to S255; `M997` enters DFU.
- Transcripts: `firmware/native-test/*.out`.

## 6. The two bugs

### (a) Driver timeout on long primes
- **Run:** session 6, 2026-10-31 11:55:40. A touch-off with a 30-turn prime at the waste.
- **Record:** `runs/sw_session6/b1_prime30.json`. Status ERROR, `linesSent` 19, `totalSeconds` 18.356.
- **Log:** `/tmp/openpnp-uinav-lumen/sw-real19/openpnp.out:144-161`:
  `11:55:58.910 … GcodeDriver:/dev/serial/by-id/…-if00 timeout waiting for response to G4 P250` (raised at `GcodeDriver.java:1295` via `DispenseExecutor.java:216`).
- **Sequence:**
  1. `M201 B20000`, `M203 B2400`, `M204 S2000`
  2. `G0 X Y` to the waste
  3. `M106 P2 S255`, `G4 P1000`, `M106 P3 S255`, `G4 P1500`
  4. `G1 Z<down> F3000`, `M204 S20000`
  5. `G1 B10836.0000 F64800`, which is 30 turns at 180 rpm plus 10° suck-back. It gets its `ok` at once (planned only).
  6. `G4 P250`. Marlin waits about 10.05 s for the screw move, then 0.25 s, so the `ok` comes after 10.3 s or more, against a 10 s timeout.
- **What the controller did:** nothing wrong. Its `ok` was simply late, and no error line was sent.
- **Root cause:** `DispenseExecutor.java:215` uses a flat `cfg.commandTimeoutMs = 10000` (`DispenseConfig.java:153`) for every line that is not `M400`. A line that waits for motion (`G4`, or `G1` when the 16-block planner is full) needs the queued motion time added. The probe already adds it (`VacuumProber.java:767`, `:802-808`); the stream does not. No prime chunking exists in il/tip-setups.
- **Threshold:** about 3 × (10 − 0.25 − queued time ahead) turns at 180 rpm. That is 28 to 29 turns; the notes round to "~25" (`roles/process.md:46-47`). 20-turn chunks worked.
- **After the timeout:** the engine sends `M410` and the rest of the stop lines. The stock board acts on `M410` immediately, inside the G4 wait. The late `G4` `ok` is then credited to `M410`, and later `ok` lines shift by one.
- Then `recover()` runs and the machine is un-homed. The run ended "stopped safely".
- Also: any wait over 60 s fails even on lines sent with −1.

### (b) STOP during homing hangs the controller
- **Run:** session 5, 2026-10-30, OpenPnP sw-real11. Log `runs/sw_session5/log_sw-real11/OpenPnP.log`; frames `runs/sw_session5/stophome/`.
- **Timeline:**

| Time | Event |
|---|---|
| 16:18:32.345 | `POST /api/machine/home`. `M114`, then `M204 S500.00`, `G28` (timeout 60 s). |
| 16:18:36.36 | Web STOP. `DISABLE_COMMAND` writes `M410`. Camera frames show X/Y stop within about 0.3 s. |
| 16:18:46.363 | `timeout waiting for response to M410` (10 s, via `setEnabled` → `GcodeDriver.java:562`). The other five stop lines were **never sent**, because `sendGcode` throws on the first failure. |
| After that | The machine-disabled listener disconnects the driver (`DispenseService.java:3129-3138`). |
| 16:19:32.35 | The G28 wait ends at 60 s: "web home failed … machine disabled: User requested stop." |
| 16:20:34 | Reconnect. Start gives "Error actuating VAC1" and connect hangs. |

- No answer to anything, including `M115` over a raw serial link. Reopening the port did not reset it; only a power cycle did. The outputs were already off.
- **Root cause:**
  - Stock early `M410` (`queue.cpp:543-548`) runs inside G28's wait loop and quick-stops the homing move short of the endstop.
  - `validate_homing_move()` then calls `kill("Homing Failed")`, and `minkill()` disables interrupts and loops. The USB stack is interrupt-driven, so it dies with them, but the device stays enumerated.
  - The quick-stop also blocks new moves for 1 s, so any later homing move in the same G28 is dropped and fails validation too. In practice, an M410 any time before the final Z approach finishes kills the board.
  - The fix is commit d973aef69 on il/safety (not flashed). Its message describes the same kill.
- **Not established:** whether `echo:Homing Failed` and `Error:Printer halted. kill() called!` reached the host during the 0.6 s before `cli()`. The INFO-level log cannot show it.
- **Inferred:** "connect hangs" comes from USB control transfers to a halted MCU.

## 7. Behavioural spec for the emulator

### Transport
- A pty, with the slave published through a stable symlink, e.g. `$RUN/ttyLUMEN` → `/dev/pts/N`. Set the config's `port-name` to the symlink.
- Either drop `usb-serial-number` and `usb-description` from the config, or keep them and accept the "Falling back to the configured port name" warnings.
- Keep the master open across host close and reopen. On Linux, `EIO` or `POLLHUP` on the master means the host closed; the emulator state must survive that.
- Put the line discipline in raw mode with no echo, and confirm what jSerialComm's termios settings leave behind.
- Accept LF, CRLF or CR. Send ASCII lines ending in `\n`.
- Always drain input, so the host's blocking writes never stall, except under the "frozen" fault.
- `jSerialComm` stubs in uinav and `SimGuard` need a decision before UI e2e or SIM scenarios can use the pty.

### Parsing (as Marlin)
- Strip `;` comments. Keep at most 95 characters and drop the rest silently.
- Upper-case letters only.
- Unknown command: `echo:Unknown command: "<text>"` then `ok`.
- **Personality knob:**
  - `stock` (default; matches the board today): M115 exactly as `serial.log:1-40`.
  - `il-safety`: d973aef69 behaviour.

### State
- Logical position X Y Z A B = the planned end.
- A per-axis G92 offset.
- Realtime step counts from the motion model. Steps per unit {320, 320, 40 (160 for il), 4.44, 4.44}.
- Homed flags for X, Y and Z only.
- Settings for M201, M203, M204 P/T, and M205 J (0.01).
- Fans P0 to P3 (P0 left pump, P1 left valve, P2 right pump, P3 right valve), with a timestamped history.
- LED state, I2C mux, sensor register and value, RS485 response table, watchdog state (il).
- A planner of 16 blocks and a command ring of 4.
- **Boot state:** X = Y = Z = 0, A = B = 720.00 with counts 3197, nothing homed, outputs off.

### Responses and timing per command
| Command | Behaviour |
|---|---|
| `G0`/`G1` | `ok` when the block enters the planner (wait while 16 blocks are queued). Clamp homed axes to soft limits silently. F is in mm/min or deg/min. Each block is a trapezoid with v = min(F/60, M203) and a = min(M204 T, scaled M201), rest to rest. That is accurate enough for the stream, whose moves are separated by G4 or Z steps. |
| `G4 P/S` | Wait for motion, then dwell, then `ok`. |
| `M400` | Wait for motion, then `ok`. |
| `M18 <axes>` | Wait for motion, disable the axis, `ok`. |
| `G92` | Immediate. Change the logical position only; counts unchanged. Print a report line, then `ok`. |
| `M114` (any arguments) | Immediate `X:%.2f Y:%.2f Z:%.2f A:%.2f B:%.2f Count X:%d Y:%d Z:%d A:%d B:%d` then `ok`. Use planned-end values. |
| `M115` | Firmware line, the Cap list, `ok`. |
| `M106 [Pn] [Sv]` / `M107 [Pn]` | Immediate, not synced to motion. No S means 255; no P means P0. In il mode, P2 and P3 any S>0 become 255. `ok` only (no report). |
| `M201`, `M203`, `M204`, `M205`, `M906`, `M150`, `M260` | `ok` |
| `M261 A109 B3 S1` | Reading line from the vacuum model, then `ok`. Also support S2, S0 and other address/byte counts. |
| `M3426` | `V:` line |
| `M485` | `rs485-reply: …` |
| `M119` | `Reporting endstop status` / `x_min: open` / `y_min: open` / `z_min: open` / `ok` |
| `M105` | `ok T:0` |
| `M211` | As in `serial.log:29364` |
| `M503` | As in `serial.log:49-74` |
| `G28` | Wait for motion. Home X, then Y, then Z (safe point 0,0) toward min at 150 mm/s and the current M204 accel, with bump 10/10/2 mm at feed/2, /2, /4. Then Z to 31.5. A and B untouched. Print a report, then `ok`. |
| `M410` | Quick-stop: drop the planner, stop at once, recompute logical position from counts (giving values like `720.05`), refuse moves for 1 s, then `ok` about 1.0 s later. **Stock:** also acts when the line is read during any wait (G4, M400, G28, full planner), then once more when dequeued (knob: ack after 1 s or 2 s). **il:** acts on receipt; the queued copy does nothing. Valve and pump go off immediately. |
| `M990` | Stock: unknown command. il: as section 5. |
| `M112` | Kill (stock: when the line is read; il: on receipt). The drivers hold through minkill() and every stepper lets go 0.85 s after the kill (`cli()` at 0.6 s, `disable_all_steppers()` 0.25 s later); stock coasts until the `cli()`, il-safety stops the motion at once. |

### Keepalive
`echo:busy: processing` at 2.0 s after any command starts blocking, then every 2.0 s. This applies to G4, M400, G28 and a full planner. Golden evidence: 559 of 559 first-busy gaps were 2.0 s.

### State machine
1. `IDLE` → `BLOCKING` (G4, M400, G28, M18, full planner) → `IDLE`.
2. `G28` phases: `X_APPROACH` → `X_BUMP` → `X_SLOW` → `Y_*` → `Z_SAFE_XY` → `Z_*` → `Z_AFTER`.
3. `M410` during G28 in stock mode, any phase before the end of `Z_SLOW`, goes to `HALTED`:
   - motion stops within 0.3 s;
   - optional `echo:Homing Failed` and `Error:Printer halted. kill() called!` within 0.6 s (knob, default silent);
   - after that, no output at all and all input discarded;
   - fans unchanged and steppers stay energised;
   - survives pty close and reopen; cleared only by `power_cycle`.
   - During `Z_AFTER`, `M410` is only a normal quick-stop.
4. In il mode the same trigger goes to `ABORT_HOMING`: echo line, report, G28 `ok`, then the `M410` `ok`.
5. `QUICKSTOP_CLEANING` lasts 1 s; moves are dropped.
6. `power_cycle` / `reset`:
   - close the master, so the host gets EIO and "IO Error on reading from the controller";
   - recreate the pty and re-point the symlink, optionally to a new path the way ttyACM0 becomes ttyACM1;
   - return to the boot state and lose the B count;
   - optionally print the banner (`start` matches the host's error regex).

### Fault knobs
- No `ok` for the Nth line or for lines matching a regex.
- Fixed or random extra delay before `ok`.
- Duplicate `ok`.
- Inject an `Error:` line, a `start` line, or `echo:Unknown command`.
- Inject a `0xFF` byte (kills the OpenPnP reader thread).
- Partial line with no newline.
- Disconnect (close the master).
- Frozen: stop reading, so the host's blocking write stalls once the pty buffer fills.
- Firmware personality.
- Timing scale. Keep it at 1.0 for the timeout reproductions, because the host timeouts are wall-clock.
- Vacuum and paste model hooks fed by B and Z moves, reusing `SimVacuum` and `SimPaste`.

### INF-03 acceptance as concrete checks
- A touch-off with `primeRotationDeg` 10800 at 180 rpm, `dwellMs` 250 must end with `timeout waiting for response to G4 P250` at about 10 s after the G4.
- Web STOP 4 s into a home must give a `timeout waiting for response to M410`, the home failing at 60 s, and no answer to `M115` after reconnecting until `power_cycle`.

## 8. Golden transcripts

1. **`/home/lumen/instancelabs/runs/serial.log`**
   - The real stock board, recorded by `tools/lumen.py` and `mc.py`. Format: `<epoch> > cmd` / `<epoch> < reply`.
   - 166,107 lines, from 10-27 15:13 to 10-31 19:02. It is still being appended.
   - Excerpts:

| Lines | What it shows |
|---|---|
| 1-40 | M115 |
| 41-42 | M114 |
| 43-48 | M119 |
| 49-74 | M503 |
| 3436-3444 | `G1 B1800 F21600`; M400 with busy lines, ok after 5.39 s |
| 3445-3466 | `G92 B0` report; `G1 B21600 F43200`; M400 with 15 busy lines, ok after 30.77 s (accel about 1000) |
| 29118-29123 | G28: busy, then report, then ok |
| 166293-166300 | G28 in 5.8 s, then `G92 A0 B0` report |
| 168526-168542 | M114, then G28 from the same place in 5.25 s, about 1.4 s faster than 166293 for its distance (README, "Homing time") |
| 122929-122940 | Lone `M410`, ok after 2.000 s, then the stop lines |
| 96806-96815 | STOP burst, first ok after 1.0 s |
| 123330 | `M105` |
| 29364 | `M211` |
| 5497 | `M261 … S0` raw bytes |
| 96619 | `M485` |

   - Caveats: `STOP` stands for six lines written in one go, so `ok` attribution after it is lossy. This is not OpenPnP's own traffic.
2. **`/tmp/openpnp-uinav-lumen/sw-real19/openpnp.out:144-161`** together with `runs/sw_session6/b1_prime30.json`: the prime timeout. `/tmp` is cleared, so copy it into the repo as a fixture now.
3. **`runs/sw_session5/log_sw-real11/OpenPnP.log`** with the `stophome/` frames: the STOP-during-home incident, at INFO level with no serial lines.
4. **`firmware/native-test/native_watchdog_test.out`** and **`native_review_fixes_test.out`**: transcripts from the il/safety native build (M990, G28 abort, kill strings, M410 ok after 1.000 s).
5. **What the backend sends:** `/tmp/openpnp-uinav-lumen/<session>/gcode-io.log` (epoch ms, driver id, direction, line) and `sim-acceptance/*/board-run-streamed.gcode`. Use these as replay inputs only; the replies in them are GcodeServer's.
6. **Missing:** there is no real OpenPnP-to-Marlin trace, because `logging-gcode="false"` and driver lines are logged only at DEBUG/TRACE. Turn on `logging-gcode` for one INF-02 health-check run on the lab machine to capture one.