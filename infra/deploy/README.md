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
     - if OpenPnP runs, its API reports no run, no pressure, nothing busy, and nothing recent: no run ended
       (`run.finishedAtMs`, `lastRun.endedAtMs`), no pad calibration ended (`padCalibration.endedAtMs`) and no
       pressure event (`pressure.lastEventAtMs`) in the last 15 min. The pressure hold's first value
       (`lastEvent` "pressure off", stamped when the API starts) is not an event. If OpenPnP is not running,
       nothing may answer on its port.

     If any check fails, it exits **3** with `not idle: <reasons>`.
   - **Checks at deploy time.** The staged folder must agree with itself (BUILD-INFO, VERSION.txt, jar sha256)
     and with the GitHub release of its tag: the release's BUILD-INFO.json is downloaded again and its `sha` and
     `jar_sha256` compared, so a folder copied into `releases/` by hand is refused. The sha is re-checked on
     `il/integration` with a green `gate`. `--version` of a build older than the current one is refused: the
     current build migrated the config one way; `rollback` goes back with the matching snapshot. While the
     current build is unverified (below), every deploy is refused (exit 2, so the timer unit fails visibly).
   - **Claim.** It writes `Holder: Infra (deploy <tag>) since <time>` into STATE.md (atomic rename; it aborts
     if the file changed meanwhile) and checks idle again. Only the Holder's value is replaced; its text is
     kept and comes back in the release (`...; earlier: <previous text>`), or exactly as it was if the
     re-check finds the machine busy. SIGTERM waits while the claim is written.
   - **Switch.** It stops OpenPnP gracefully (`POST /api/stop`, waits until the machine is disabled, then
     stops the service). It snapshots `state/config` to `snapshots/<ts>-pre-<tag>/` (`dispense.db`, `-wal` and
     `-shm` are copied together). It records the new build in `deployed.json` as **pending** (with the snapshot
     and the previous version), then switches the `previous`/`current` symlinks and starts the service. Pending
     means installed but not verified: whatever stops the run from here (a crash, SIGTERM from `systemctl stop`
     or a reboot), `rollback` finds the build and its snapshot.
   - **Health check:**
     1. `/api/status` answers 200 and `server.version` ends with `.<sha7>`; `simulated` is false; the dispense
        engine runs and is available (`dispense.running` and `dispense.available`: OpenPnP keeps running when
        the engine fails to start, e.g. on a failed config migration).
     2. Enable.
     3. The log shows `Serial port: connecting ... S/N 387932683335`.
     4. Controller axes are read, and they must not be the power-cycle signature (X/Y/Z 0, B 720). If they
        are, it does **not** home.
     5. Home, waiting up to 5 min. It never STOPs while the machine is busy. A failed home (`homing failed`:
        fiducial covered, endstop, obstruction) is a machine problem.
     6. Homed and not busy.
     7. `POST /api/stop`, and the machine is disabled.
   - **On failure it rolls back.** It STOPs (only if the STOP is safe; otherwise it leaves OpenPnP running,
     sets the hold and records the new build as installed but NOT verified), restores the snapshot (keeping the
     abandoned state as `snapshots/<ts>-abandoned-<tag>/`) and switches back. Then:
     - **A machine problem** (a reset controller, the serial lock taken at start, a failed home): the
       previous build is put back with its config but not checked, so nothing moves before a person looks. It
       runs again (not enabled) if it ran before. The hold is set.
     - **Otherwise** the previous build is started and checked. The new build goes on the **bad** list
       (`bad.json`, `run` never picks it again) only if the previous build passes. If the previous build fails
       too, the machine is suspect: nothing goes on the bad list and the hold is set.
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
   does not redeploy it. The snapshot is from before the deploy, so a rollback undoes every config change
   since (head offsets, needle Z, tip setups, K, the db). It compares `state/config` with the manifest taken
   after the deploy and **refuses** if anything changed, listing the files, unless `--discard-config-changes`
   is given; it also refuses for a build that was never verified (no baseline). The discarded config is kept in
   `snapshots/<ts>-abandoned-<tag>/`, named in STATE.md, and never pruned (a person deletes it).
4. **After an interrupted deploy** (the Deployed line says `<tag> installed but NOT verified`): check the
   machine, `il_deploy.py resume`, then `il_deploy.py health` (a pass verifies the build and writes the
   Deployed line) or `il_deploy.py rollback --discard-config-changes`.

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
  - the pressure held, or an engine run past its home (its phase is not `home: ...`): STOP at once, outputs
    may be on (homes refuse while the pressure is held);
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
  check the machine before `resume` use `il_deploy.py health --no-claim`.
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
il_deploy.py units --out DIR            # render the systemd user units
```
`stop-openpnp` (the unit's ExecStop) and `check-device` (its ExecStartPre) are called by the unit.

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
4. The first deploy is by hand, with a person watching: `il_deploy.py poll && il_deploy.py deploy`. Then
   `il_deploy.py rollback` and deploy again (INF-02's real-environment acceptance). Then
   `systemctl --user enable --now il-deploy.timer`.

The units:

| Unit | What it is |
|---|---|
| `il-openpnp.service` | Java with the three `--add-opens`, `-DconfigDir=<root>/state/config`, prefs in `<root>/prefs`, `-Xmx1536m`, API port 18877, `DISPLAY=:50`. Started under `flock -n -E 75 /tmp/lumen-serial.lock`, so the bench tools refuse while it runs. `ExecStop` = `stop-openpnp --home-busy-wait 300 --stop-timeout 10` (the STOP rule above); `TimeoutStopSec=360` covers that wait plus the STOP; `KillMode=mixed`, `Restart=no`, higher CPU, IO and memory priority. It is not enabled at boot: deploys and people start it. |
| `il-openpnp-xvfb.service` | `Xvfb :50 -nolisten tcp` (outside uinav's :91-:299). |
| `il-deploy.service` | oneshot `run`. Exit code 3 (not idle) counts as success; no start timeout (a deploy homes the machine); 10 min stop timeout. `After=il-openpnp.service`, so at shutdown it stops first: its SIGTERM handler waits out a home and STOPs before the OpenPnP unit's ExecStop runs. |
| `il-deploy.timer` | Starts the deploy service 2 min after the last run ended (and 5 min after boot). |

GitHub access: `gh` when it is logged in, else unauthenticated urllib (60 requests/h). An idle poll costs one
request.

## Tests
From the repo root (about 60 s; starts fake OpenPnP processes on free loopback ports, never a serial port):
```
python3 -m unittest discover -s infra/deploy/tests -t infra/deploy
```
`python3 -m unittest discover -s infra/deploy/tests` (no `-t`) works too: each test module puts
`infra/deploy` on `sys.path` itself (`test_discovery.py` checks it).
- `tests/fake_openpnp.py` is a fake OpenPnP API with configurable failures: version, enable, reset, no S/N,
  home, slow home, stop, busy, run (with its phase), pressure, crash, engine (not started), unavailable,
  machine-500, and failures for one build only. Its status has the real shape: `run.finishedAtMs`,
  `lastRun.endedAtMs`, `padCalibration.endedAtMs`, and the pressure hold's start value.
- `tests/fakes.py` has a fake GitHub with a real commit graph, so the compare direction is tested; CI-style
  release tarballs; and a throwaway lab (root, STATE.md, lock, device, activity file).
- `tests/test_acceptance.py` is INF-02's CI acceptance: a deploy dry run into a test directory, a second
  deploy over a running OpenPnP, and a rollback to the previous version.
- `tests/test_stop.py` covers when a STOP is sent (stubs with a fake clock, and the fake mid-home).
- `test_units.py` runs `systemd-analyze --user verify` on the rendered units when it is installed. It only
  verifies; it never installs.
