# il_deploy: automatic deploys to the lab computer (INF-02)

`il_deploy.py` puts green `il/integration` builds of the OpenPnP fork on the lab computer that drives the
LumenPnP. It deploys only when nobody uses the machine, health-checks the new build on the real machine,
rolls back by itself if the check fails, and records what runs in STATE.md. Python 3.12 standard library only.
`RESEARCH.md` maps how OpenPnP runs on this host and the safety facts behind the rules below.

## What it does
1. **poll:** reads the GitHub prereleases `il-build-<run>-<sha7>` that CI publishes for each green push to
   `il/integration`. It stages the newest eligible one into `<root>/releases/<tag>/`. A build is eligible
   only if all of these hold:
   - `BUILD-INFO.branch` is `il/integration`, and the tag, `target_commitish` and BUILD-INFO agree on the sha;
   - the sha is the head of `il/integration` or one of its ancestors (compare `<head>...<sha>` is
     `identical` or `behind`). A scratch merge is `ahead` or `diverged` and is refused;
   - the commit's `gate` check-run is `completed/success`;
   - the tarball matches its `.sha256`, holds only `openpnp-il/`, and its jar matches `jar_sha256`.

   Refusals go to `log/deploy.jsonl` with the reason. Unpacking happens in a temp dir that is renamed into
   place in one step.
2. **deploy** (and **run**, which polls first; the timer calls `run`):
   - **Idle check.** All of these must hold:
     - STATE.md has exactly one `- **Holder:**` line, its value starts with `nobody`, nobody `wants` the
       machine, and there are no notes (sub-bullets) under it: a note is for the next taker to read first;
     - no hold latch is set;
     - the serial flock can be taken, or only the OpenPnP service holds it;
     - no other process has the serial device open, and the device exists;
     - no other OpenPnP runs with a real-machine config;
     - `runs/serial.log` (and any other `--activity` file) has been quiet for 15 min;
     - if OpenPnP runs, its API reports no run, no pressure, nothing busy, the machine **switched off**
       (`machine.enabled` false: someone who pressed Start and Home in the web UI may be working at the machine by
       hand), and nothing recent in the last 15 min:
       - no run ended (`run.finishedAtMs`, `lastRun.endedAtMs`), no pad calibration ended
         (`padCalibration.endedAtMs`), no pressure event (`pressure.lastEventAtMs`; the pressure hold's first value,
         `lastEvent` "pressure off" stamped when the API starts, is not an event);
       - no home (`machine.zCheck.homeAtMs`);
       - no hand work in the dispense config: needle change, touch-off and landing (`dispense.needle.changedAtMs`,
         `setAtMs`, `landing.setAtMs`), tip mounted (`dispense.tip.mountedAtMs`), new seat (`newSeatAtMs`), a place
         set (`locations.*.setAtMs`), a surface probed (`surfaces.*.setAtMs`).

       What OpenPnP stamped while il_deploy alone drove it does not count: each start and health check keeps its
       time window in `<root>/own-activity.json` (the health check's home; a build's first start can stamp the
       config itself, e.g. the needle landing "kept from before the update"). If OpenPnP is not running, nothing
       may answer on its port.
     - `run` (the timer) only: no `il_deploy.py resume` or `health` by hand in the last 15 min (`<root>/person.json`):
       the person who ran it may still be at the machine. A deploy by hand has its person there.

     If any check fails, it exits **3** with `not idle: <reasons>`.
   - **Checks at deploy time.** The staged folder must agree with itself (BUILD-INFO, VERSION.txt, jar sha256)
     and with the GitHub release of its tag: the release's BUILD-INFO.json is downloaded again and its `sha` and
     `jar_sha256` compared, so a folder copied into `releases/` by hand is refused. The sha is re-checked on
     `il/integration` with a green `gate`. `--version` of a build older than the current one is refused: the
     current build migrated the config one way; `rollback` goes back with the matching snapshot. While the
     current build is unverified (below), every deploy is refused (exit 2, so the timer unit fails visibly).
     With the systemd units, deploy and rollback are refused (exit 2) until the user manager may take long enough
     to stop at a reboot (below, "Installing").
   - **Claim.** It writes `Holder: Infra (deploy <tag>) since <time>` into STATE.md (atomic rename; it aborts
     if the file changed meanwhile) and checks idle again. The claim is also kept in `<root>/claim.json` until it
     is handed back (see "Killed outright" below). Only the Holder's value is replaced; its text is
     kept and comes back in the release (`...; earlier: <previous text>`), or exactly as it was if the
     re-check finds the machine busy. SIGTERM waits while the claim is written.
   - **Switch.** It stops OpenPnP gracefully (`POST /api/stop`, waits until the machine is disabled, then
     stops the service). It snapshots `state/config` to `snapshots/<ts>-pre-<tag>/` (`dispense.db`, `-wal` and
     `-shm` are copied together). It records the new build in `deployed.json` as **pending** (with the snapshot,
     the previous version and the records before it) and writes the Deployed line `<tag> being installed over
     <previous> since <time>: NOT verified ...`, then switches the `previous`/`current` symlinks and starts the
     service. Pending means installed but not verified: whatever stops the run from here (a crash, SIGTERM from
     `systemctl stop`, a reboot, a SIGKILL), `rollback` finds the build and its snapshot, and STATE.md never
     names the previous build while the new one is installed.
   - **Health check:**
     1. `/api/status` answers 200 and `server.version` ends with `.<sha7>`; `simulated` is false; the dispense
        engine runs and is available (`dispense.running` and `dispense.available`: OpenPnP keeps running when
        the engine fails to start, e.g. on a failed config migration). OpenPnP starts its web API and the engine
        in no fixed order, so a 200 without the engine is waited out up to `--status-timeout` (120 s); an engine
        that never starts fails the check then.
     2. Enable.
     3. The log shows `Serial port: connecting ... S/N 387932683335` after the enable or, when the enable logged no
        connect, as the last connect line of the current `OpenPnP.log`: with `connection-keep-alive` (the lab
        machine.xml) a STOP leaves the link open and the next enable does not connect again, and the file is this
        OpenPnP's own (tinylog's StartupPolicy starts a new one at each start).
     4. Controller axes are read, and they must not be the power-cycle signature (X/Y/Z 0, B 720). If they
        are, it does **not** home.
     5. Home. Whatever the answer, it never STOPs while the machine is busy: on anything but `homed` it waits
        while busy (up to `--home-busy-wait`). The API answers 409 "the home did not start or did not end within
        5 min" after its own limit (`MachineEndpoints.home`) while the home may run on; il_deploy's
        `--home-timeout` (330 s) is longer. Such a home that then ends homed passes; one that ends unhomed is a
        machine problem, one still busy is "a home that never ends" (below). Any other refusal or failure (`homing
        failed`: fiducial covered, endstop, obstruction; or something else holds the machine: the pressure, an
        engine task, OpenPnP busy) is a machine problem: no second home unwatched. Only a 404 (no home endpoint)
        is the build's.
     6. Homed and not busy.
     7. `POST /api/stop`, and the machine is disabled.
   - **On failure it rolls back.** It STOPs (only if the STOP is safe; otherwise it leaves OpenPnP running,
     sets the hold and records the new build as installed but NOT verified), restores the snapshot (keeping the
     abandoned state as `snapshots/<ts>-abandoned-<tag>/`) and switches back. Then:
     - **A machine problem** (a reset controller, the serial lock taken at start, a failed or refused home): the
       previous build is put back with its config but not checked, so nothing moves before a person looks. It
       runs again (not enabled) if it ran before. The hold is set. Its record is marked `unchecked`, and the
       Deployed line says "not checked" until a health check passes.
     - **Otherwise** the previous build is started and checked; until that check passes it is pending (and the
       Deployed line says so). The new build goes on the **bad** list (`bad.json`, `run` never picks it again)
       only if the previous build passes. If the previous build fails too, the machine is suspect: nothing goes
       on the bad list, the hold is set, and the previous build stays pending (deploys refused) until a health
       check passes.
     - **A home that never ends:** OpenPnP is left alone, the build stays installed but NOT verified, the hold
       is set.
   - **At the end** it writes the `- **Deployed on the machine:**` line in STATE.md's `## Code` section,
     records the build as verified with a manifest of the config (file hashes, without `log/` and `-shm`)
     as the baseline for a later rollback, and puts OpenPnP back as it was (running or stopped). The Holder:
     - all well: `nobody (released by Infra deploy at HH:MM[; note]); earlier: <previous text>`;
     - a person must check (the hold is set: exit 5, an unexpected exception, a failed release):
       `Infra (deploy <tag>): a person must check the machine (hold set at HH:MM: <note>); after the check run
       il_deploy.py resume`. It does not read as free; `resume` hands it back.

     Only its own claim text is replaced, so a request someone added under the claim during the deploy
     ("Backend wants ...") stays and keeps holding off the next taker.
3. **rollback:** one step back to `previous` with the pre-deploy snapshot of `current`, under the same
   idle, claim, health and STATE.md rules. The version rolled back from goes on the bad list, so the timer
   does not redeploy it. As a deploy, it marks the build it goes back to pending and writes the Deployed line
   before the switch; a rollback whose health check fails leaves it pending (deploys refused) until a health check
   passes. The snapshot is from before the deploy, so a rollback undoes every config change
   since (head offsets, needle Z, tip setups, K, the db). It compares `state/config` with the manifest taken
   after the deploy and **refuses** if anything changed, listing the files, unless `--discard-config-changes`
   is given; it also refuses for a build that was never verified (no baseline). The discarded config is kept in
   `snapshots/<ts>-abandoned-<tag>/`, named in STATE.md, and never pruned (a person deletes it).
4. **After an interrupted deploy** (the Deployed line says `<tag> installed but NOT verified`, or `being
   installed`): check the machine, `il_deploy.py resume`, then `il_deploy.py health` (a pass verifies the build
   and writes the Deployed line) or `il_deploy.py rollback --discard-config-changes`. A health check refuses
   while the machine is switched on: press Stop in OpenPnP first.
5. **Killed outright** (SIGKILL: power loss, the OOM killer, the user manager's stop timeout at a reboot): no exit
   path runs, so the Holder keeps the claim. The next il_deploy command on the root (the timer's `run`, or
   `resume`) finds `claim.json` with no il_deploy holding the root lock: it sets the hold, turns the Holder into
   `Infra (deploy <tag>): a person must check the machine (... il_deploy was killed during the deploy ...)`, and
   writes the Deployed line: `<tag> installed but NOT verified: the deploy was cut short` after the switch, or the
   current build "not checked since the deploy of <tag> was cut short before its switch" (pending until a health
   check passes) before it. `resume` then hands the Holder back as for any hold. Until a command runs, STATE.md
   shows the claim and the "being installed" Deployed line.

Exit codes:

| Code | Meaning |
|---|---|
| 0 | Done, or nothing to do |
| 1 | Error, or an unexpected exception after the claim |
| 2 | Refused (not staged, not eligible, not on GitHub, older than current, current not verified, no previous version, config changed since the deploy) |
| 3 | Not idle |
| 4 | Deploy failed its health check and was rolled back |
| 5 | Failed, and a person must check the machine (the hold is set; the Holder stays taken until `resume`) |

## Safety rules it enforces
- Never deploys a build that is not green, or whose sha is not on `il/integration`. It re-checks GitHub at
  deploy time too. `--version TAG` overrides only the bad list, never eligibility.
- Never while someone holds the machine. Any doubt counts as held: a missing or doubled Holder line, `none`,
  `free`, `Nobody?`, bold markers, a `wants` note.
- Never STOPs during a home: a STOP inside G28 hangs the controller until a power cycle. One rule for every
  STOP (deploys, the exception path, the unit's ExecStop, the test service manager):
  - the pressure held (`pressure.state` `on` or `vent_failed`), or an engine run past its home (its phase is not
    `home: ...`): STOP at once, outputs may be on (homes refuse while the pressure is held);
  - the pressure `starting` or `stopping` with the machine busy: wait as for a home. `PressureHold.start` sets
    STARTING on the HTTP thread and queues its task behind the machine thread's work, which may be a home;
    nothing is on before that task runs, and a running start ends within seconds;
  - otherwise wait while the machine is busy or its state is unknown (no answer in time, HTTP not 200), up to
    `--home-busy-wait` (5 min), then STOP. Still busy: no STOP, OpenPnP is not killed by il_deploy, and the
    hold is set. If systemd stops OpenPnP anyway, SIGTERM closes the port and Marlin finishes the home;
  - OpenPnP not answering at all (connection refused): nothing to stop.
- Never homes a controller that shows the power-cycle signature. It reports `controller was reset; a person
  must check the machine` and sets the hold.
- Always `POST /api/stop` before stopping the process. SIGTERM saves nothing and sends no Stop, and the
  outputs keep their state. If the STOP is not confirmed, il_deploy leaves OpenPnP running and sets the hold.
- Rollback restores the config snapshot together with the binary, because the schema migrations are one-way.
- The Holder is handed back on every exit path: `nobody (...)`, or the held text above when a person must
  check. After an unexpected exception (SIGTERM included) the machine is stopped through `/api/stop` if that is
  safe, the note says what actually happened, the exception is recorded and the hold is set. If STATE.md cannot
  be written, il_deploy says so on stderr, sets the hold and exits non-zero.
- **Hold latch** (`<root>/hold.json`): set when a person must look at the machine. It blocks deploys until
  `il_deploy.py resume`, which also hands back the Holder the hold left. The hold Holder is not free, so to
  check the machine before `resume` use `il_deploy.py health --no-claim`. After `resume` (and after `health` by
  hand) the timer waits the quiet period before it deploys and homes again: the person may still be at the
  machine.
- One `il_deploy` at a time per root (flock on `<root>/.il-deploy.lock`).

## Commands
Common flags go **after** the subcommand. Their defaults are this host's values: `--root
~/instancelabs/deploy`, `--state-md`, `--lock /tmp/lumen-serial.lock`, `--device` (the Lumen by-id path),
`--activity ~/instancelabs/runs/serial.log`, `--quiet-min 15`, `--api-port 18877`, `--usb-sn 387932683335`,
`--service il-openpnp.service`, `--service-manager systemd`. Run `il_deploy.py <cmd> -h` for all flags.

```
il_deploy.py init --seed-from ~/instancelabs/realcfg-sw-20261030n   # once, with OpenPnP stopped
il_deploy.py poll                       # stage the newest eligible release
il_deploy.py deploy [--version TAG]     # deploy when idle
il_deploy.py run                        # poll + deploy (the timer)
il_deploy.py rollback [--discard-config-changes]   # one step back
il_deploy.py health [--sha7 X] [--no-claim]   # JSON report; --no-claim if you hold the machine already
il_deploy.py status                     # JSON: versions, last deploy, idle verdict, hold, bad list
il_deploy.py resume                     # after checking the machine: clear the hold, hand back the Holder
il_deploy.py units --out DIR            # render the systemd user units (and the user manager's system drop-in)
```
`stop-openpnp` (the unit's ExecStop) and `check-device` (its ExecStartPre) are called by the OpenPnP unit,
`wait-display` (its ExecStartPost: wait until Xvfb listens) by the Xvfb unit.

## Installing on the lab computer (not done yet: needs the machine idle and a person around)
1. Stop any hand-run OpenPnP (save its `/tmp/openpnp-uinav-lumen/<session>/config` first). Then seed the
   state once: `il_deploy.py init --seed-from ~/instancelabs/realcfg-sw-20261030n`.
2. Copy the tool outside any git worktree:
   `install -D -m 755 infra/deploy/il_deploy.py ~/instancelabs/deploy/bin/il_deploy.py`.
3. Render and install the units:
   ```
   il_deploy.py units --out /tmp/il-units
   cp /tmp/il-units/il-* ~/.config/systemd/user/ && systemctl --user daemon-reload
   ```
   And, as root with the user's OK, the user manager's stop budget. Ubuntu's
   `/usr/lib/systemd/system/user@.service.d/timeout.conf` gives the whole user manager 5 s at shutdown, then
   SIGKILLs everything in it (on this host `systemctl show user@1000.service -p TimeoutStopUSec` says `5s`), so the
   units' own budgets (il-deploy 10 min, then il-openpnp 6 min) never run. The drop-in sorts after `timeout.conf`
   and sets 17 min:
   ```
   sudo install -D -m 644 /tmp/il-units/system/user@1000.service.d/zz-il-deploy-stop-timeout.conf \
     /etc/systemd/system/user@1000.service.d/zz-il-deploy-stop-timeout.conf && sudo systemctl daemon-reload
   ```
   Until `systemctl show` reports at least that, deploy and rollback refuse (exit 2) and `status` says why.
4. The first deploy is by hand, with a person watching: `il_deploy.py poll && il_deploy.py deploy`. Then
   `il_deploy.py rollback` and deploy again (INF-02's real-environment acceptance). Then
   `systemctl --user enable --now il-deploy.timer`.

The units:

| Unit | What it is |
|---|---|
| `il-openpnp.service` | Java with the three `--add-opens`, `-DconfigDir=<root>/state/config`, prefs in `<root>/prefs`, `-Xmx1536m`, API port 18877, `DISPLAY=:50`. Started under `flock -n -E 75 /tmp/lumen-serial.lock`, so the bench tools refuse while it runs. `ExecStop` = `stop-openpnp --home-busy-wait 300 --stop-timeout 10` (the STOP rule above); `TimeoutStopSec=360` covers that wait plus the STOP; `KillMode=mixed`, `Restart=no`, higher CPU, IO and memory priority. It is not enabled at boot: deploys and people start it. |
| `il-openpnp-xvfb.service` | `Xvfb :50 -nolisten tcp` (outside uinav's :91-:299). `ExecStartPost=il_deploy.py wait-display` (up to 20 s for `/tmp/.X11-unix/X50`): Type=simple counts as started at the fork, and OpenPnP's first AWT call exits 1 without the socket, which at the first start after a boot would put a good build on the bad list. |
| `il-deploy.service` | oneshot `run`. Exit code 3 (not idle) counts as success; no start timeout (a deploy homes the machine); 10 min stop timeout. `After=il-openpnp.service`, so at shutdown it stops first: its SIGTERM handler waits out a home and STOPs before the OpenPnP unit's ExecStop runs. That needs the user manager's drop-in above; without it a reboot kills both after 5 s (then "Killed outright" applies). |
| `il-deploy.timer` | Starts the deploy service 2 min after the last run ended (and 5 min after boot). |

GitHub access: `gh` when it is logged in, else unauthenticated urllib (60 requests/h). An idle poll costs one
request.

## Tests
From the repo root (about 2 min, 206 tests; starts fake OpenPnP processes on free loopback ports, never a serial port):
```
python3 -m unittest discover -s infra/deploy/tests -t infra/deploy
```
`python3 -m unittest discover -s infra/deploy/tests` (no `-t`) works too: each test module puts
`infra/deploy` on `sys.path` itself (`test_discovery.py` checks it).
- `tests/fake_openpnp.py` is a fake OpenPnP API with configurable failures: version, enable, reset, no S/N,
  home, slow home, stop, busy, run (with its phase), pressure, crash, engine (not started), unavailable,
  machine-500, home-404, and failures for one build only. Its status has the real shape: `run.finishedAtMs`,
  `lastRun.endedAtMs`, `padCalibration.endedAtMs`, the pressure hold's start value, `machine.zCheck.homeAtMs`
  and the dispense config's times (needle, tip, seat, places, surfaces). It answers as the real server does:
  `engine_start_s` (the API answers before the dispense engine runs); home refusals before the home starts, and
  the 409 after the server's own limit while the home runs on (`home_cap_s`, 300 s like `MachineEndpoints`); a
  new `OpenPnP.log` at each start, and a link kept open across a STOP (`keep_alive`, true like the lab
  machine.xml), so only the first enable logs the connect line.
- `tests/fakes.py` has a fake GitHub with a real commit graph, so the compare direction is tested; CI-style
  release tarballs, or exactly what `infra/ci/make_release.py` writes (`Lab.ci_release`, published by
  `FakeGitHub.add_release_dir` as `gh release create` does); and a throwaway lab (root, STATE.md, lock,
  device, activity file). `Lab.start_service` starts the fake OpenPnP and waits for its API: a deploy over an
  OpenPnP still starting is "not idle", which made the acceptance test fail about 1 run in 10.
- `tests/test_acceptance.py` is INF-02's CI acceptance: a deploy dry run into a test directory, a second
  deploy over a running OpenPnP, and a rollback to the previous version.
- `tests/test_release_contract.py`: make_release.py's output is staged, deployed and rolled back, also two
  builds of one jar under one sha7 (what the end-to-end test publishes).
- `tests/test_stop.py` covers when a STOP is sent (stubs with a fake clock, and the fake mid-home).
- `tests/test_service.py`: the subprocess service manager (tests and CI) runs while any process of its group
  does, and `stop` waits for all of them, as systemd does for the unit's cgroup. flock dies on SIGTERM at once;
  the java it runs holds the serial lock and the API port for a second or two longer.
- `test_units.py` runs `systemd-analyze --user verify` on the rendered units when it is installed. It only
  verifies; it never installs. It also checks the user manager's drop-in covers both units' stop budgets, and
  `wait-display`.
- `test_deploy_flow.py` also kills il_deploy outright in a forked child (SIGKILL at a chosen step) to check what
  the next command finds, and covers a deploy and a rollback whose pre-switch STOP is not confirmed.
- `test_ci_wiring.py`: `ci.yml` has one job that runs the end-to-end test below with `IL_DEPLOY_E2E=1`, and the
  `gate` needs it.

## End to end with the real OpenPnP (`e2e/`)
`e2e/test_deploy_openpnp.py` runs the deploy flow with the real jar instead of the fake OpenPnP, in front of
lumen-sim (`../lumen-sim`), the simulated controller on a pty. OpenPnP connects through its real serial stack.
It needs no openpnp-uinav, so it runs on a stock GitHub runner. About 2 minutes (122 s on the lab computer,
11-01: deploy 31 s, deploy over a running OpenPnP 32 s, rollback 30 s, the refused deploy 13 s).
- **Releases:** `infra/ci/make_release.py` twice from the one jar, with CI's command line. The jar's build sha7
  (manifest `Implementation-Version`; OpenPnP ends `server.version` with it) starts both fake commits' shas,
  so the production version check runs unchanged. The fake GitHub of the unit tests serves them.
- **Config:** `init --seed-from` the fixture lab config (`infra/fixtures/lab-config`, boards.xml empty) plus the
  fixture engine config. machine.xml is `tools/instancelabs/make_machine_patch.py`'s output, rewritten for
  lumen-sim:
  - `port-name` = the lumen-sim link (`ttyLUMEN0`);
  - `usb-serial-number` and `usb-description` removed;
  - visual homing off;
  - the OpenPnpCaptureCameras become an ImageCamera and a SimulatedUpCamera.

  Java starts only if every port-name resolves to lumen-sim's `/dev/pts/N` and no USB identity or capture camera
  is left. With no camera device, OpenPnP would start with the capture cameras too: `open()` only logs "No camera
  found with ID" (OpenPnpCaptureCamera.java:174), and the native library needs only libstdc++ and libc. But the
  fixture's unique-ids name the lab's real cameras, so the swap keeps the test camera-free on every host.
- **Run:** lumen-sim, Xvfb on a free display in `:60`-`:69`, the API on a free port in 18930-18949. il_deploy
  runs in-process (`main(argv, deps)`: only GitHub is faked) with `--service-manager subprocess` and the
  production OpenPnP command (flock, java, the unit's flags), with a temp root, STATE.md, lock and activity
  file. `--device` is the lumen-sim link and `--usb-sn ''` (a pty has no USB identity). lumen-sim gets `G92 B0`
  first: Marlin's boot position is the power-cycle signature, which is never homed.
- **test_1:** poll and deploy; OpenPnP started; `run` (poll and deploy of the newer build over the running
  OpenPnP); rollback. Checked: exit codes, links, deployed.json, bad.json, the `server.version` each health
  check saw, STATE.md's Deployed line and Holder, deploy.jsonl. In lumen-sim's I/O log: one G28 per health check,
  each answered before any M410, and the DISABLE_COMMAND lines after each home.
- **test_2:** lumen-sim power-cycled, a third build: `run` exits 5 after reading X/Y/Z 0, B 720. Nothing moves
  (no G0/G1/G28), the previous build runs again unchecked, the new build is not marked bad, and the hold is set
  until `resume`.

From the repo root, with the build in `target/`:
```
IL_DEPLOY_E2E=1 IL_DEPLOY_E2E_JAR=target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar IL_DEPLOY_E2E_LIB=target/lib \
  IL_DEPLOY_E2E_OUT="$RUNNER_TEMP/deploy-e2e" python3 infra/deploy/e2e/test_deploy_openpnp.py -v
```
- Without `IL_DEPLOY_E2E=1` (or without the jar or lib) the test skips. Run as a script with
  `IL_DEPLOY_E2E=1`, a skip exits 1, so a miswired CI job cannot pass by skipping.
- `IL_DEPLOY_E2E_OUT` keeps deploy.jsonl, OpenPnP's log and output, lumen-sim's I/O log, STATE.md and
  results.json (timings).

CI job `deploy-e2e` in `ci.yml` (`needs: build`; the `gate` needs it, so the release job publishes only builds
that passed it): `actions/checkout`, `actions/setup-java` (temurin 17), `actions/download-artifact` of
`openpnp-build` into `target`, then the command above. Upload `$RUNNER_TEMP/deploy-e2e` with `if: always()`.
- Beyond ubuntu-latest's defaults it needs only `xvfb`:
  `command -v Xvfb >/dev/null || { sudo apt-get update -q && sudo apt-get install -y -q xvfb; }`, as in
  `.github/actions/sim-env`.
- Python 3.12 and util-linux `flock` are on the image already.
- `timeout-minutes: 15`.

Found by it, fixed test-first:
- The health check took the first 200 from `/api/status` as final. OpenPnP starts its API and the dispense
  engine on `configurationComplete`, in no fixed order (its listeners are a HashSet). So the first deploy failed
  with "the dispense engine is not usable" and marked a good build bad (`test_health`,
  `test_deploy_flow`: `engine_start_s`).
- The subprocess manager's `stop` returned as soon as flock exited, while java still held the serial lock: the
  next start failed with exit 75 (`test_service`).
- 11-01: a build's first start stamps the config itself (DispenseService: the needle landing "kept from before
  the update" gets `setAtMs` now), which the activity check took for a person's: the rollback right after a deploy
  was "not idle". What OpenPnP stamps while il_deploy alone drives it does not count (`test_deploy_flow`:
  `landing_set_at_start`).
