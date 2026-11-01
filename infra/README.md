# infra/: CI, deploys and the simulated controller

Owner: the Infra role (project notes `roles/infra.md`). Backend and UI code and their tests live elsewhere; this
directory only holds what runs them and ships them. Python here is standard library only.

| Path | What | Request |
|---|---|---|
| `../.github/workflows/ci.yml` | The pipeline: one required check, `gate` | INF-01 |
| `../.github/actions/sim-env/` | Sets a runner up for the SIM gates (JDK, uinav, Xvfb, Playwright, lab layout) | INF-01 |
| `ci/` | CI helpers: `lab_layout.py`, `acceptance.py`, `make_release.py`, `surefire_summary.py` | INF-01 |
| `fixtures/` | The lab files the SIM gates read: `lab-config/` (= `~/.openpnp2` of 10-27, the config the gates are green against, minus backups, logs and two legacy automation scripts), `lab-instancelabs/` (`dispense-config.json`, bench run files without photos) | INF-01 |
| `deploy/` | The lab computer's deployer (`il_deploy.py`, systemd units) | INF-02 |
| `lumen-sim/` | Simulated LumenPnP Marlin controller on a pty | INF-03 |

## The pipeline (`ci.yml`)
Runs on every pull request, on pushes to `il/integration`, and by hand (workflow_dispatch).

| Job | What | About |
|---|---|---|
| build | `mvn -DskipTests package` (checkstyle runs at validate); the jar + lib become the artifact every later job tests | 3 min |
| infra | `python3 -m unittest discover -s infra/<x>/tests -t infra/<x>` for every component here | 1 min |
| maven | `mvn test`: unit tests and the simulated-machine integration tests (in-JVM GcodeServer, `*SimTest`) | 13 min |
| ui-e2e | `src/test/web/run-webui-sim-test.sh`: webui-sim, board-import, pad-profile, tip-setups in one SIM session | 55 min |
| ui-boardview | `src/test/web/run-boardview-test.sh` | 5 min |
| acceptance ×4 | `src/test/sim/il_sim_acceptance.py`, sharded; `ci/acceptance.py` fails the job unless every check passed (the script itself always exits 0) | 15 min each |
| gate | green only if every job above succeeded; the one required status check | seconds |
| release | push to `il/integration` only, after `gate`: a prerelease `il-build-<run>-<sha7>` with the build (`ci/make_release.py`) | 1 min |

The SIM jobs need read access to the private `theinstancelabs/openpnp-uinav` (Actions secret `UINAV_TOKEN` or
`UINAV_DEPLOY_KEY`); without it they fail with a message saying so, never pass. uinav is pinned by `UINAV_REF`.

The gates read host paths (`~/.openpnp2`, `~/Desktop/ftp.board.xml`, `/home/lumen/instancelabs/{config,runs}`).
`ci/lab_layout.py` lays the fixtures out at those paths on the runner, so the gates run unchanged. It refuses to run
on the lab computer and never overwrites.

## Tests
```
python3 -m unittest discover -s infra/ci/tests -t infra/ci
python3 -m unittest discover -s infra/deploy/tests -t infra/deploy
python3 -m unittest discover -s infra/lumen-sim/tests -t infra/lumen-sim
```
