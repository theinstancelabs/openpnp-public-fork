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
     - STATE.md has exactly one `- **Holder:**` line, its value starts with `nobody`, and nobody `wants` the
       machine;
     - no hold latch is set;
     - the serial flock can be taken, or only the OpenPnP service holds it;
     - no other process has the serial device open, and the device exists;
     - no other OpenPnP runs with a real-machine config;
     - `runs/serial.log` (and any other `--activity` file) has been quiet for 15 min;
     - if OpenPnP runs, its API reports no run, no pressure, nothing busy, no recent run or pressure event.
       If OpenPnP is not running, nothing may answer on its port.

     If any check fails, it exits **3** with `not idle: <reasons>`.
   - **Claim.** It writes `Holder: Infra (deploy <tag>) since <time>` into STATE.md (atomic rename; it aborts
     if the file changed meanwhile) and checks idle again.
   - **Switch.** It stops OpenPnP gracefully (`POST /api/stop`, waits until the machine is disabled, then
     stops the service). It snapshots `state/config` to `snapshots/<ts>-pre-<tag>/` (`dispense.db`, `-wal` and
     `-shm` are copied together), switches the `previous`/`current` symlinks and starts the service.
   - **Health check:**
     1. `/api/status` answers 200 and `server.version` ends with `.<sha7>`; `simulated` is false.
     2. Enable.
     3. The log shows `Serial port: connecting ... S/N 387932683335`.
     4. Controller axes are read, and they must not be the power-cycle signature (X/Y/Z 0, B 720). If they
        are, it does **not** home.
     5. Home, waiting up to 5 min. It never STOPs while the machine is busy.
     6. Homed and not busy.
     7. `POST /api/stop`, and the machine is disabled.
   - **On failure it rolls back.** It restores the snapshot (keeping the abandoned state as
     `snapshots/<ts>-abandoned-<tag>/`), switches back, starts the previous build and checks it again. A build
     that fails its own check goes on the **bad** list (`bad.json`), and `run` never picks it again. Machine
     problems do not count against the build; they set the **hold** latch instead. Examples: a reset
     controller, the serial lock taken at start, a home that never ends.
   - **At the end** it writes the `- **Deployed on the machine:**` line in STATE.md's `## Code` section. It
     puts OpenPnP back as it was (running or stopped) and releases the Holder:
     `nobody (released by Infra deploy at HH:MM[; note])`. Only its own claim text is replaced, so a request
     someone added under the claim during the deploy ("Backend wants ...") stays and keeps holding off the
     next taker.
3. **rollback:** one step back to `previous` with the pre-deploy snapshot of `current`, under the same
   idle, claim, health and STATE.md rules. The version rolled back from goes on the bad list, so the timer
   does not redeploy it.

Exit codes:

| Code | Meaning |
|---|---|
| 0 | Done, or nothing to do |
| 1 | Error, or an unexpected exception after the claim |
| 2 | Refused (not staged, not eligible, no previous version) |
| 3 | Not idle |
| 4 | Deploy failed its health check and was rolled back |
| 5 | Failed, and a person must check the machine (the hold is set) |

## Safety rules it enforces
- Never deploys a build that is not green, or whose sha is not on `il/integration`. It re-checks GitHub at
  deploy time too. `--version TAG` overrides only the bad list, never eligibility.
- Never while someone holds the machine. Any doubt counts as held: a missing or doubled Holder line, `none`,
  `free`, `Nobody?`, bold markers, a `wants` note.
- Never STOPs during a home: a STOP inside G28 hangs the controller until a power cycle. If a home does not
  end, it leaves OpenPnP alone and sets the hold.
- Never homes a controller that shows the power-cycle signature. It reports `controller was reset; a person
  must check the machine` and sets the hold.
- Always `POST /api/stop` before stopping the process. SIGTERM saves nothing and sends no Stop, and the
  outputs keep their state.
- Rollback restores the config snapshot together with the binary, because the schema migrations are one-way.
- The Holder is released on every exit path. After an unexpected exception the machine is stopped through
  `/api/stop`, the exception is recorded and the hold is set.
- **Hold latch** (`<root>/hold.json`): set when a person must look at the machine. It blocks deploys until
  `il_deploy.py resume`. `health` still runs while the hold is set, so the person can verify.
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
il_deploy.py rollback                   # one step back
il_deploy.py health [--sha7 X] [--no-claim]   # JSON report; --no-claim if you hold the machine already
il_deploy.py status                     # JSON: versions, last deploy, idle verdict, hold, bad list
il_deploy.py resume                     # clear the hold after checking the machine
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
| `il-openpnp.service` | Java with the three `--add-opens`, `-DconfigDir=<root>/state/config`, prefs in `<root>/prefs`, `-Xmx1536m`, API port 18877, `DISPLAY=:50`. Started under `flock -n -E 75 /tmp/lumen-serial.lock`, so the bench tools refuse while it runs. `ExecStop` = `stop-openpnp`; `KillMode=mixed`, `TimeoutStopSec=30`, `Restart=no`, higher CPU, IO and memory priority. It is not enabled at boot: deploys and people start it. |
| `il-openpnp-xvfb.service` | `Xvfb :50 -nolisten tcp` (outside uinav's :91-:299). |
| `il-deploy.service` | oneshot `run`. Exit code 3 (not idle) counts as success; no start timeout (a deploy homes the machine); 10 min stop timeout. |
| `il-deploy.timer` | Starts the deploy service 2 min after the last run ended (and 5 min after boot). |

GitHub access: `gh` when it is logged in, else unauthenticated urllib (60 requests/h). An idle poll costs one
request.

## Tests
From the repo root (about 40 s; starts fake OpenPnP processes on free loopback ports, never a serial port):
```
python3 -m unittest discover -s infra/deploy/tests -t infra/deploy
```
- `tests/fake_openpnp.py` is a fake OpenPnP API with configurable failures: version, enable, reset, no S/N,
  home, slow home, stop, busy, run, pressure, crash.
- `tests/fakes.py` has a fake GitHub with a real commit graph, so the compare direction is tested; CI-style
  release tarballs; and a throwaway lab (root, STATE.md, lock, device, activity file).
- `tests/test_acceptance.py` is INF-02's CI acceptance: a deploy dry run into a test directory, a second
  deploy over a running OpenPnP, and a rollback to the previous version.
- `test_units.py` runs `systemd-analyze --user verify` on the rendered units when it is installed. It only
  verifies; it never installs.
