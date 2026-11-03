# Lab-computer deploy research for INF-02 (read-only; host state as of 2026-10-31 ~19:15 PDT)

**Short answers:**
- **Who holds the machine:** the Process role (STATE.md:8). It is running bench tools right now: `runs/serial.log` was last written at 19:11:06. Real OpenPnP is not running.
- **Deployed build:** `~/instancelabs/realjar-sw8`, a scratch merge (`il/real9` c868c6f3f). It was built and copied by hand.
- **Live machine state:** the config folder `~/instancelabs/realcfg-sw-20261030n`. `~/.openpnp2` is stale.
- **Build commit:** no endpoint reports a full sha. `GET /api/status` → `server.version` ends with the 7-character short sha.
- **Controller reads:** no endpoint queries the controller on demand. OpenPnP reads M114 itself when the machine is started.
- **Clean stop:** `POST /api/stop`, then SIGTERM. SIGTERM does not save `machine.xml` and does not send the Stop sequence.

## 1. How OpenPnP runs on the lab computer today

**Builds (by hand)**
- Steps: `mvn -DskipTests package` in a worktree, then copy `target/*.jar` and `target/lib/` (99 jars) into `~/instancelabs/realjar-swN/`, then write `BUILD.txt` by hand (sw and sw2 have none).
- The jar manifest's `Implementation-Version` gives the commit:

| Folder | Commit |
|---|---|
| realjar-sw | b9f96b0 |
| realjar-sw2 | 37fe1af |
| realjar-sw3 | 2df93ed |
| realjar-sw4 | bdc9d2d |
| realjar-sw5 | ca51a27 |
| realjar-sw6 | 70a42c9 |
| realjar-sw7 | a4bb6d2 |
| realjar-sw8 | `2026-10-30_23-04-45.c868c6f` |

- realjar-sw8 is identical (same sha256, dd5e6c5a…) to `wt/real9/target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar`.
- `il/real9` = `il/rc1` e932de678 + `il/fr2-wire` 127a03171 (commit message: "not for PR"). It exists only locally; `git branch -r` shows no `origin/il/real9`.
- `dist/openpnp-il/` is the output of `make_dist.sh` from 10-27 (`il/integration` 16ce9cc9, BUILD-INFO). It was never used for a real run.

**Launch (sessions 5–6, recorded in roles/infra.md:50-55 and the transcripts)**
```
env JAVA_TOOL_OPTIONS=-Xmx1536m OPENPNP_IL_API_PORT=18877 systemd-run --user --scope --unit=sw-openpnp-realN --collect \
  ~/instancelabs/openpnp-uinav/bin/uinav --session sw-realN launch --mode real --confirm-real \
  --config ~/instancelabs/realcfg-sw-20261030m --openpnp-jar ~/instancelabs/realjar-sw8/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar --allow enable+home
```
After launch they pressed Start (uinav click on MachineControlsPanel, or `POST /api/machine/enable`), waited with `uinav wait-state machine.enabled=true`, then sent `POST /api/machine/home {"wait":true}` and `POST /api/machine/z-check`.
- **Why the scope:** OpenPnP launched from a Claude shell died at 13:09 when the session restarted (lessons.md:90; software-autoregister.md:505-506).
- **Heap cap:** real OpenPnP reached 3.9 GB RSS during a host overload (load 29, swap full) at 03:20. With `-Xmx1536m` it ran at about 1.4 GB (software-autoregister.md:146-148).

**What uinav real mode does** (openpnp-uinav/python/uinav.py)
- Refuses a session name that is still running. Otherwise it **deletes the old session folder** with `rmtree` (:375-383).
- Copies the config (without `backups/` and `log/`) to `/tmp/openpnp-uinav-lumen/<session>/config` (:385-388).
- Rewrites `machine.xml` through ElementTree **even in real mode** (:389-403).
- Starts `java -javaagent:uinav-agent.jar -DconfigDir=<copy>`, with Java prefs and tmpdir kept per session, `-cp <jar> org.openpnp.Main`, working directory = the session folder, `start_new_session=True` (:425-445).
- Real mode uses `DISPLAY=:0` (GNOME on Wayland through Xwayland). SIM mode uses Xvfb on displays :91–:299 (:340-356).
- `uinav stop` sends SIGTERM to the process group, waits 10 s, then SIGKILL (:483-519).

**Stop and save**
- Command: `uinav --session sw-real21 stop; rsync -a --exclude uinav-boards --exclude log /tmp/openpnp-uinav-lumen/sw-real21/config/ ~/instancelabs/realcfg-sw-20261030n/; systemctl --user stop sw-serial-lock` (transcript).
- Each session's changes survive only by being copied into a new `realcfg-*` folder. There are 22 of them.

**Config in use**

| | `realcfg-sw-20261030n` (live state) | `~/.openpnp2` |
|---|---|---|
| machine.xml sha | a250da15… | 5cc805eb… |
| N2 head offset | (18.500, −70.990) | (23.615, −63.947) |
| Serial port | `/dev/serial/by-id/usb-STMicroelectronics_MARLIN_OPULO_LUMEN_REV5_…387932683335-if00`, matched by USB serial number 387932683335 | `ttyS4` |
| DISABLE_COMMAND | `M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B` | none |
| Other driver settings | timeout 10000 ms, `sync-initial-location="true"`, GET_POSITION_COMMAND `M114` | — |
| `instancelabs/` | `dispense-config.json` schema 3, `dispense.db` schema 2 (`schema_info` rows 1, 2) plus a 4 MB WAL and `-shm` | no `instancelabs/` |
| `web-api.json` | none (port comes from the env) | none |

`~/.openpnp2` was never edited (roles/infra.md:56-58).

**How machine.xml patches are applied**
- `tools/instancelabs/make_machine_patch.py` writes a patched *copy* and refuses to overwrite its input (:4, :225-232).
- Its constants are stale: N2_OFFSET is (25.291, −75.926).
- Installing a patch is a hand step with OpenPnP closed (dist/README.md:42-60).
- In practice it was never installed live. The `realcfg-*` copies carry the patch and were edited by hand between sessions (e.g. the N2 offset in realcfg-…g and …j; software-autoregister.md:416, 446).
- **Never regenerate machine.xml in a deploy: the config is state.**

**Launchers**
- `openpnp-il.sh` (the copy in the fork and in dist are the same) supports `--config` and `--api-port` (exported as `OPENPNP_IL_API_PORT`).
- It exits 3 if *any* OpenPnP is running. I checked its pattern against live processes: right now it matches the SIM sessions il-ui-demo, gate-pr1-webui and il-ui-pcbwebui. So it cannot be used unchanged while the demo or CI sessions run.
- `make_dist.sh` refuses uncommitted changes under `src/main` or `pom.xml` and writes BUILD-INFO (branch, full commit, sha256) (:11-29).

**Other facts**
- Stock OpenPnP 2.6 (install4j) is at `/opt/openpnp`. It uses `~/.openpnp2` and refuses the patched machine.xml (dist/README.md:62).
- systemd user units:
  - unit files: `calibration-workspace.service` (legacy `~/lumenpnp/automation`, 127.0.0.1:8766, enabled at boot) and `t3code.service`;
  - transient: `ui-demo-sim.scope`, `ui-demo-proxy.service` and the `t3code-claude-*.scope` units;
  - no `sw-openpnp-real*` or `sw-serial-lock` unit is active now;
  - `Linger=yes`.
- The serial lock `/tmp/lumen-serial.lock` (flock) is taken only by the bench tools (`tools/lumen.py:6,16-21`; `mc.py:16`). **OpenPnP never takes it** (guide/sources/software-facts.md:51).

## 2. Web API (docs/instancelabs/WEB_API.md §1-3; `src/main/java/org/openpnp/instancelabs/api/`)

**Basics**
- Base URL `http://127.0.0.1:<port>/`. Default port 8765 (ApiSettings.java:22). Overrides: system property `instancelabs.api.port`, then env `OPENPNP_IL_API_PORT`, then `<configDir>/instancelabs/web-api.json` (ApiSettings.java:13-15, 55). Real sessions used 18877.
- No authentication. It binds loopback only unless `lanAccess` is on.
- The Host header must be loopback (ApiServer.java:417-420). A POST with a foreign `Origin` or a cross-site `Sec-Fetch-Site` gets 403 (:425-430). `curl` without an Origin header works.
- A health check must therefore run on the host itself.

**Endpoints a health check should call**

| Call | What it does |
|---|---|
| `GET /api/status` | `server {version, timeMs, port, lanAccess, simulated, simulatorConfirmed}` (DispenseEndpoints.java:140-142), plus `machine`, `dispense`, `pressure`, `run`, `padCalibration`, `boardImport` |
| `GET /api/machine` | `present, enabled, connected (= enabled), homed, busy, home{n1Z,n1Low,…}, zCheck, drivers[{name,class,connection}], positions[], axes[{name,type,letter,driverCoordinate,coordinate}]` (MachineEndpoints.java:263-347) |
| `POST /api/machine/enable` | OpenPnP Start, 90 s timeout → `{enabled, homed}` (:166-182) |
| `POST /api/machine/home {"wait":true}` | `homeSafely`, up to 5 min → `{homed:true, n1RaisedFromZ}`, or 409 `homing failed: …` (:184-250). Refused while the engine runs, the pressure is held, or OpenPnP is busy |
| `POST /api/stop` | Always allowed. Sends the F9 Stop sequence and disables the machine; answers within 1.5 s → `{stopped, machineEnabled, actions}` (:76-151) |
| `GET /api` | Route list. The route count fingerprints the build: real9 has 80, tip-setups 102 (backend-requests.md:118, 128) |
| `GET /api/camera/top.jpg` | Optional camera check. Can answer PNG on this JVM |

**Build commit**
- `server.version` = `Main.getVersion()` = the contents of `VERSION.txt` + `_` + manifest `Implementation-Version` (`yyyy-MM-dd_HH-mm-ss` UTC + `.` + 7-character sha; pom.xml:16-17, 279-293, 331; Main.java:57-82).
- `VERSION.txt` is read **relative to the working directory** (Main.java:64). It gives "n/a" when absent; the repo's copy says "2.7".
- The buildnumber plugin runs with `doCheck=false`, so a dirty tree still reports HEAD's sha.
- There is no `/api/version` or `/api/contract`. BR-29 proposes `GET /api/contract {build, sha}` (backend-requests.md:519-521).

**Reading controller state**
- No endpoint sends M114 or M115 on demand.
- When the machine is started, OpenPnP sends M114 and sets the axis positions from the reply (`sync-initial-location`, ReferenceMachine.java:230-237). `axes[].driverCoordinate` then holds the controller's numbers.
- `drivers[].connection` shows the *configured* port name (SerialPortCommunications.java:238-240), not the port actually opened.
- The proof of the real port is this line in `<configDir>/log/OpenPnP.log`: `Serial port: connecting to ttyACM0 by USB serial number: … S/N 387932683335` (seen in sw-real21's log, line 9).
- `server.simulated` is true only when every driver is a loopback TCP connection (SimGuard.java:80-90). A simulated controller on a pty would report `simulated:false`.

**What healthy looks like**
- `server.version` ends with `.<sha7>`; `server.simulated` false.
- After enable: `machine.enabled/connected` true.
- After home: `homed` true, `busy` false; `positions` (top camera) near the homing fiducial (217.081, 200.223).
- `dispense.available` true; `pressure.on` false; `run` is null or `run.running` false; `boardImport.reading` false; the log line above is present.

**Timings from sw-real21's log:** launch 13:59:07 → API up 13:59:09.55 → serial connect 13:59:10.85 → home done 13:59:38. API homes took 21–23 s each time.

## 3. UI demo link: don't collide with it

| Part | Details |
|---|---|
| `ui-demo-sim.scope` | `il_ui_session.py start --name demo --tip-camera --api-port 18890`. uinav session `il-ui-demo`, folder `/tmp/openpnp-uinav-lumen/il-ui-demo`, Xvfb :91 (pid 2964751), java pid 2964756 |
| Demo API | 127.0.0.1:18890; `il_ui_session` takes the first free port of 18890…18898 |
| OpenPnP's simulator (GcodeServer) | listening on `*:34485`, all interfaces (uinav README:214) |
| Demo jar | `wt/tip-setups/target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar` |
| `ui-demo-proxy.service` | `devproxy.py`, 127.0.0.1:18990. Serves web files live from `wt/tip-setups/src/main/resources/org/openpnp/instancelabs/web` |
| Tailscale | `tailscale serve`: 443 → 127.0.0.1:3773 (T3) and 8443 → 18990 |
| Teardown | software-autoregister.md:552-560 |

Things that would break the demo:
- running `mvn package` or `clean` in `wt/tip-setups` (the demo runs that jar and the proxy serves those files);
- running `il_ui_session.py ... --name demo` (it runs `uinav stop` on that session first, :137);
- reusing the uinav session name `il-ui-demo` (launch deletes its folder);
- changing `tailscale serve` on 8443 or 443.
- devproxy strips the Origin header, so it is SIM-only. Never point it at the real machine (memory note ui-demo-link).

Other ports in use: 8766 (calibration-workspace), 3773 (T3), 7125 and 80 (Moonraker and nginx; Klipper's MCU serial ports are placeholders, so it doesn't touch the Lumen port), 22 and 2222. Backend gate runs use ports 18951-18959 (software-autoregister.md, 00:40 entry) and pc-web 18971-18979 (:225); gates gate-pr1/gate-pr2 are running now.

## 4. Host facts (read-only commands)
- **OS:** Ubuntu 24.04.4, kernel 7.0.0-34. 12 threads, 15 GB RAM (about 5 GB available now, with 3 SIM OpenPnPs and 2 mvn gates running; load 10). 255 GB free disk.
- **Java:** OpenJDK 17.0.20.1 only (`/usr/lib/jvm/java-17-openjdk-amd64`); no `~/.sdkman`. **Maven** 3.8.7 (`/usr/share/maven`).
- **Node/npm:** node v22.23.2, npm 10.9.8.
- **Python:** 3.12.3 with pyserial 3.5; `~/instancelabs/venv` has pyserial and OpenCV 5.0.0.
- **Browsers/display:** Xvfb, xvfb-run, google-chrome 151.0.7922.169.
- **Playwright:** 1.64.1 in `/tmp/il-webui-pw`. It has no bundled browser (`~/.cache/ms-playwright` is absent), so use `executablePath: '/usr/bin/google-chrome'`. It is in /tmp, so a reboot loses it.
- **Other tools:** tailscale 1.104.2, socat 1.8.0.0, gh, git, jq, curl, rsync, flock, fuser. **Not installed:** sqlite3 CLI, xdotool.
- **Desktop:** GNOME on Wayland, `DISPLAY=:0` through Xwayland; XAUTHORITY `/run/user/1000/.mutter-Xwaylandauth.SS92W3` is in the systemd user environment.
- **Devices:** user is in `dialout` and `video`. `/dev/ttyACM0` is crw-rw-rw-; the by-id link points to it. No process has it open at this moment.

## 5. STATE.md Holder line
- Format today (STATE.md:8-11): `- **Holder:** <free text>`, continued on lines indented two spaces. Example: "Process role, since 10-31 19:00 (bench tools …)".
- "Holder: nobody" is the agreed idle value (roles/infra.md:28, backend.md:17, infra-requests.md:34). STATE.md is untracked in its repo (git status `??`), so it has no history.
- **Parse, failing closed:**
  - Exactly one line must match `^\s*-\s*\*\*Holder:\*\*\s*(.*)$`. Join the following lines while they start with two spaces and not `-`.
  - The machine is idle only if the value matches `(?i)^nobody\b`.
  - Anything else counts as held: no match, more than one match, "none", "free", "Nobody?", bold markers removed, a "wants" note.
- **Risks:**
  - Hand edits change the markup.
  - A stale holder (someone forgot to release) blocks deploys forever. That is acceptable, but it needs a notice.
  - Writes race: the file changed twice while I worked. Write by re-reading, replacing only that one line, and renaming a temp file over it after checking the mtime hasn't changed.
  - The text is no proof of idle. Also require:
    - the flock is free;
    - no process has the serial device open (fuser);
    - the API reports idle;
    - a quiet period: the bench tools open and close the port per command, so a single "port free" check is not enough. `runs/serial.log` and `/dev/ttyACM0` mtimes show the last activity.

## 6. Safety: restarting OpenPnP and deploying over a running session
- **File > Exit** (`MainFrame.quit()`, :1096-1136): saves the config (rewrites `machine.xml`). It may open a modal "job modified" dialog that blocks a headless exit. It then sends DISABLE_COMMAND (outputs off), closes the machine and exits.
- **SIGTERM** (what `uinav stop` and `systemctl stop` send): OpenPnP has no shutdown hook (only SystemLogger.java:23). So no config save, no "Shutting down" line (sw-real21's log just ends) and **no Stop sequence**. Changes made in the Swing GUI and not saved are lost. Engine data is safe: `dispense-config.json` is written on each API write and the db is SQLite WAL.
- **Outputs keep their last state after OpenPnP dies.**
  - Stock Marlin has EMERGENCY_PARSER:0, and the host watchdog M990 was never flashed (guide/sources/machine-facts.md:16).
  - The pressure auto-off (I7) and keepalive run on the host side (PressureHold.java:200-240), so they die with the JVM.
  - When the machine starts again, VAC1/VAC2 `enabled-actuation="AssumeUnknown"`, so nothing is switched off.
  - Killing OpenPnP mid-run therefore leaves the valve and pump on, the auger driver energized, and Marlin finishing its buffered moves.
- **Always send `POST /api/stop` before stopping the process,** and send it again after any crash.
- **STOP during homing hangs the controller:** M410 got no answer and recovery needed a power cycle; reopening the port did not reset it (software-autoregister.md:378-383; lessons.md:63-65). A health check must never STOP or kill during a home. Let the home finish (the API waits up to 5 min).
- **Homing after a controller power cycle:** `homeSafely` may raise Z by 31.5 mm from a position Marlin only believes it has (BR-02, backend-requests.md:132-142; not implemented). Check `axes` after enable: the reset signature is X, Y and Z at 0 with B at 720. If you see it, do not home; report it.
- **Serial port matching** goes by USB serial number first, then description, then port name (SerialPortMatcher.java:94-108). A SIM or pty config that keeps `usb-serial-number="387932683335"` opens the **real** Lumen. This matters for CI on this host and for INF-03.
- **Schema migrations make rollback by binary alone unsafe.**
  - The first start of the tip-setups build migrates the config 3 → 4 (DispenseConfig.java:38, 679-697) and the db 2 → 3, writing a `.bak` of the db only (DispenseDb.java:44-48, 52).
  - It also turns the config into the seeded 22G setup, model `bench-22g` (BR-01, backend-requests.md:119-120).
  - real9 opens a newer db without refusing (real9 DispenseDb.java:226-227).
  - real9 loads the config through Gson, which ignores unknown fields, so its next save would drop the tip fields. **Rollback must restore the state snapshot together with the binary.**
- **Host load:** CI and gates on this host compete with the real OpenPnP (the 03:20 overload). Give the OpenPnP service priority (CPU and memory weights) and cap CI.
- **Lessons on launching and stopping:**
  - systemd scopes and units, not Claude shells (lessons.md:90);
  - `pkill -f` and `pgrep -f` match the shell's own command line: wait on PIDs (lessons.md:7, 28, 36-38);
  - run STOP tests last (lessons.md:63-65);
  - never re-run a motion script to read its output; tee logs to a file (lessons.md:24);
  - don't stop a deploy halfway (roles/README.md:26).

## 7. Deploy design sketch

**Layout:** `~/instancelabs/deploy/`
- `releases/<UTCts>-<sha12>/`: `openpnp-il.jar`, `lib/`, `BUILD-INFO` (branch, full sha, CI run URL, jar sha256, expected route count) and `VERSION.txt` = `il-<sha12>`. `server.version` then shows `il-<sha12>_<ts>.<sha7>` with no code change, because VERSION.txt is read from the working directory.
- `current` and `previous`: symlinks, switched atomically (`ln -sfn` to a temp name, then `mv -T`).
- `state/config/`: the single live config. Seed it once from `realcfg-sw-20261030n` with OpenPnP stopped, copying db, WAL and shm together.
- `snapshots/<ts>-pre-<sha12>/`: rsync of `state/config` taken after the stop and before the switch; keep the last N.
- `log/deploy.jsonl`.

**Unit:** `~/.config/systemd/user/il-openpnp.service` (a real file: it survives reboots, and Linger=yes starts it at boot)
- `Requires`/`After=il-openpnp-xvfb.service` (Xvfb `:50 -nolisten tcp`, outside uinav's :91–:299; this avoids depending on the GNOME session's :0).
- `WorkingDirectory=%h/instancelabs/deploy/current`.
- `Environment=OPENPNP_IL_API_PORT=18877 JAVA_TOOL_OPTIONS=-Xmx1536m DISPLAY=:50`.
- `ExecStart=/usr/bin/flock -n /tmp/lumen-serial.lock /usr/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED -DconfigDir=%h/instancelabs/deploy/state/config -Djava.util.prefs.userRoot=%h/instancelabs/deploy/prefs -jar openpnp-il.jar`.
  - Holding the flock for OpenPnP's lifetime makes the bench tools refuse automatically (lumen.py:16-17).
  - `ExecStartPre` refuses if fuser shows another process on the device.
- `ExecStop=` a script that sends `curl -X POST 127.0.0.1:18877/api/stop` and waits until `machine.enabled` is false; systemd then sends SIGTERM. `TimeoutStopSec=20`, `KillMode=mixed`.
- `Restart=no`: a crashed controller host is inspected, not respawned.
- `CPUWeight` and `MemoryLow` high for this unit; caps on CI units.
- Don't use `openpnp-il.sh`: its any-OpenPnP check blocks on SIM sessions.

**Trigger:** a timer on the host polls with `gh` for green CI runs on `il/integration`, with no inbound access needed. For each run:
1. Download the artifact; check its sha256, that the commit is on `origin/il/integration`, and that the run's conclusion is success.
2. Stage it into `releases/`.

**Deploy:**
1. Wait for idle:
   - Holder is nobody, the flock is ours or free, no other process has the device open;
   - quiet for at least 15 min (`serial.log` / tty mtimes, `lastRun.finishedAtMs`, `pressure.lastEventAtMs`);
   - the API reports idle: `run` null or not running, `pressure.on` false, `dispense.busy` false, `machine.busy` false, `padCalibration` null, `boardImport.reading` false.
2. Claim: Holder = "Infra (deploy <sha7>) since HH:MM". Then re-check idle.
3. `POST /api/stop`, then `systemctl --user stop il-openpnp`.
4. Snapshot the state.
5. Point `previous` at the old release and `current` at the new one; start the service.
6. Health check:
   - (a) `GET /api/status` answers 200 within 60 s; the version ends with the expected sha; `simulated` false; `GET /api` route count is at least the expected number.
   - (b) `POST /api/machine/enable` → `enabled:true`; the log shows the S/N 387932683335 line; `axes[].driverCoordinate` is present and not the reset signature.
   - (c) `POST /api/machine/home {"wait":true}` → `homed:true` within 5 min, never interrupted; camera within tolerance of (217.081, 200.223); `busy` false.
   - (d) `GET /api/camera/top.jpg` gives non-empty bytes.
   - (e) `POST /api/stop` → `machineEnabled:false`.
7. Success: update STATE.md "Deployed on the machine: <sha7> (il/integration, CI run …) since …, previous <sha7>" and set Holder back to nobody.

**Rollback** (on a failed check, or by command):
1. `POST /api/stop`, then stop the service.
2. Restore the matching snapshot into `state/config`.
3. Swap `current` back to `previous`, start, run the same health check without the version step.
4. Record it in STATE.md.

**CI dry run (INF-02 acceptance):** set `DEPLOY_ROOT` to a temp folder and use a SIM config with `usb-serial-number` and `usb-description` removed.

## Facts relied on, with sources
- Real launch and stop commands, API port 18877, `-Xmx1536m`: roles/infra.md:50-58; transcript `54ef640f…jsonl`; software-autoregister.md:71, 146-148, 505-506.
- uinav copy, rmtree, ElementTree rewrite, cwd, SIGTERM then SIGKILL, Xvfb range: openpnp-uinav/python/uinav.py:340-356, 375-445, 483-519; README:50-95, 214.
- realjar commits: jar manifests; `BUILD.txt` files; sha256 comparison with `wt/real9/target`.
- Config facts: `realcfg-sw-20261030n/machine.xml` (driver line 437-438, actuators, homing fiducial, N2); `dispense-config.json` and `dispense.db` read-only; `~/.openpnp2/machine.xml`.
- Launcher and dist: `tools/instancelabs/{openpnp-il.sh, make_dist.sh:11-29, make_machine_patch.py:4-48, 225-232}`; dist/README.md:42-62; dist/openpnp-il/BUILD-INFO.
- API: WEB_API.md §1-3; ApiSettings.java:13-27, 55-56; ApiServer.java:417-430; MachineEndpoints.java:44-50, 64-70, 76-151, 166-250, 263-347; DispenseEndpoints.java:136-200; SimGuard.java:80-99; PressureHold.java:530-544; RunTracker.java:117-140.
- Version: Main.java:57-82; pom.xml:16-17, 279-293, 331; VERSION.txt.
- OpenPnP behaviour: MainFrame.java:1096-1136; SystemLogger.java:23; ReferenceMachine.java:230-237; SerialPortCommunications.java:150, 238-240; SerialPortMatcher.java:79-108.
- Migrations: DispenseConfig.java:38, 679-697; DispenseDb.java:44-52; real9 DispenseDb.java:46, 226-227; real9 DispenseConfig.java:37, 546-547; backend-requests.md:18-20, 114-131, 132-142, 519-521.
- Session timings and the S/N log line: `/tmp/openpnp-uinav-lumen/sw-real21/config/log/OpenPnP.log`.
- Demo: `systemctl --user status` of both demo units; `ss -ltnp`; `tailscale serve status`; il_ui_session.py:34, 110-161; memory note ui-demo-link.md; software-autoregister.md:552-560.
- Safety: lessons.md:7, 24, 28, 36-38, 63-65, 90; software-autoregister.md:378-383; guide/sources/machine-facts.md:16-18; guide/sources/software-facts.md:47-51; roles/README.md:26; roles/process.md:16-18.
- Holder: STATE.md:8-11, 25; roles/infra.md:28; roles/backend.md:17, 46; docs/infra-requests.md:31-39.
- Host: `java -version`, `mvn -v`, `node -v`, `npm -v`, `python3 --version`, `which`, `ls /usr/lib/jvm`, `/tmp/il-webui-pw/node_modules/playwright/package.json`, `google-chrome --version`, `socat -V`, `tailscale version`, `nproc`/`free`/`df`, `loginctl` (Linger), `systemctl --user show-environment`, `id`, `ls -l /dev/ttyACM0`, `/proc/*/fd` scan, `runs/serial.log` tail.
- Not found: any `/api/version`, `/api/contract`, shutdown or save-config endpoint; any API route that sends M114 or M115 on demand; any sqlite3 CLI on the host.