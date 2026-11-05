# infra/: CI, deploys and the simulated controller

Owner: the Infra role (project notes `roles/infra.md`). Backend and UI code and their tests live elsewhere; this
directory only holds what runs them and ships them. Python here is standard library only.

| Path | What | Request |
|---|---|---|
| `../.github/workflows/ci.yml` | The pipeline: one required check, `gate` | INF-01 |
| `../.github/actions/sim-env/` | Sets a runner up for the SIM gates (JDK, uinav, Xvfb, Playwright, the gates' input files) | INF-01 |
| `ci/` | CI helpers: `sim_env.py`, `acceptance.py`, `make_release.py`, `publish_release.py`, `surefire_summary.py` | INF-01 |
| `fixtures/` | The lab files the SIM gates read: `lab-config/` (= `~/.openpnp2` of 10-27, the config the gates are green against, minus backups, logs and two legacy automation scripts), `lab-instancelabs/` (`dispense-config.json`, bench run files without photos) | INF-01 |
| `deploy/` | The lab computer's deployer (`il_deploy.py`, systemd units) | INF-02 |
| `lumen-sim/` | Simulated LumenPnP Marlin controller on a pty | INF-03 |

## The pipeline (`ci.yml`)
Runs on every pull request, in the merge queue (`merge_group`), on pushes to `il/integration`, and by hand
(workflow_dispatch). `il/integration` takes changes only through the merge queue (ruleset "il/integration: merge
queue"): `gh pr merge <n> --merge` queues a PR, the queue runs the full gate on it on top of what is ahead, then merges.
A PR's own run is the fast gate: ui-e2e and acceptance are skipped unless the PR has the label `full-ci`.
The queue fast-forwards `il/integration` to the very commit its `merge_group` run gated, so the push run of that
commit skips each test job the queue run passed (job `queued`, by job id) and only builds, gates and releases: the
release comes minutes after the merge, not an hour. A job the queue run skipped or did not pass runs on the push.

| Job | What | About |
|---|---|---|
| build | `mvn -DskipTests package` (checkstyle runs at validate); the jar + lib become the artifact every later job tests | 3 min |
| queued | on a push to `il/integration`: the test jobs this commit's `merge_group` run passed (its gate's `gate-needs` artifact: the gate's `needs` by job id), read once that run's gate job has passed (GitHub can mark the run itself completed minutes later). Every test job below runs unless its id is in that list; anything unexpected (an API error, no such run, no artifact) answers `[]` | seconds |
| infra | `python3 -m unittest discover -s infra/<x>/tests -t infra/<x>` for every component here | 1 min |
| maven | `mvn test`: unit tests and the simulated-machine integration tests (in-JVM GcodeServer, `*SimTest`) | 13 min |
| controller-e2e | `infra/lumen-sim/e2e`: OpenPnP on lumen-sim's pty: home, STOP in G28, the 30-turn prime timeout | 6 min |
| ui-e2e | `src/test/web/run-webui-sim-test.sh` in parallel legs, each a fresh SIM session (`ci/webui_shards.py`): main (webui-sim, board-import, pad-profile, tip-setups, tip-flow) and rest (every other script in `ALL_SCRIPTS`); skipped on a PR without `full-ci` | ~55 / ~10 min |
| ui-boardview | `src/test/web/run-boardview-test.sh` | 5 min |
| deploy-e2e | `infra/deploy/e2e/test_deploy_openpnp.py`: il_deploy with the build's jar on lumen-sim (deploy, deploy over a running OpenPnP, rollback, a power-cycled controller) | 3 min |
| acceptance ×4 + 1 | `src/test/sim/il_sim_acceptance.py`, sharded on GcodeServer, plus `load` on lumen-sim's pty (`--controller-sim-cap`, BR-43/47); `ci/acceptance.py` fails the job unless every check passed (the script itself always exits 0) | 15 min each |
| gate | the one required status check: every job above succeeded (on a PR without `full-ci`, ui-e2e and acceptance may be skipped; on a push, exactly the test jobs `queued` names), and no job of the run failed or was cancelled (the run's job list, since `needs` can miss a matrix leg cancelled before pickup). In the queue it then uploads its `needs` as `gate-needs` for the push (a failed upload never fails it). On a PR run with a cancelled job (a newer run of the PR cancelled it: a push, or `full-ci` added while it ran) it reports as `gate (cancelled run)`, not a required check, so the commit waits for the newer run's `gate` (INF-14) | seconds |
| release | push to `il/integration` only, after `gate`: a prerelease `il-build-<run>-<sha7>` with the build (`ci/make_release.py`), its tarball attested first (`actions/attest-build-provenance`; the deployer verifies it with `gh attestation verify`), then published by `ci/publish_release.py`: a release already at the tag passes only as this run's own (a re-run), checked with the deployer's policy; anything else there fails the run (tests in `deploy/tests/`) | 1 min |
| prune-artifacts | after a green release: deletes every `openpnp-build` artifact of a run that succeeded or was cancelled, but this run's own (`ci/prune_artifacts.py`); a run still going or one that failed keeps its build (`gh run rerun --failed` reuses it); every upload expires after a day; a failed prune never turns the run red | < 1 min |

The SIM jobs need read access to the private `theinstancelabs/openpnp-uinav` (Actions secret `UINAV_TOKEN` or
`UINAV_DEPLOY_KEY`); without it they fail with a message saying so, never pass. uinav is pinned by `UINAV_REF`.

The gates default to the lab computer's paths (`~/.openpnp2`, `~/Desktop/ftp.board.xml`,
`/home/lumen/instancelabs/{config,runs}`) and read env overrides instead when set (BR-40). `ci/sim_env.py DIR` copies
the fixtures into DIR (new or empty; nothing outside it is written): the live config with a `boards.xml`, the test
board, `machine.xml.patched` from `tools/instancelabs/make_machine_patch.py`, the engine's `dispense-config.json` and
the bench run files, and prints the overrides in `GITHUB_ENV` format (`OPENPNP_CONFIG`, `IL_LIVE_CONFIG`,
`IL_TEST_BOARD`, `IL_PATCHED_MACHINE_XML`, `IL_DISPENSE_CONFIG`, `IL_RUNS_DIR`, `LUMEN_SIM_E2E_CONFIG`,
`LUMEN_SIM_E2E_DISPENSE_CONFIG`). CI: `python3 infra/ci/sim_env.py "$RUNNER_TEMP/lab" >> "$GITHUB_ENV"` (a refusal
exits 2 and fails the step). On any computer, the same env runs a gate on the fixtures instead of the lab's files:
`lab_env="$(python3 infra/ci/sim_env.py "$(mktemp -d)/lab" --export)" && eval "$lab_env"`, then the gate in that
shell, after another `&&` (`&& src/test/web/run-boardview-test.sh`). A new directory each run and the `&&`, because
a refused directory (not empty: a second run, or another user of the computer made it) prints nothing on stdout, and
`eval` of nothing succeeds, even under `set -e`: the gate would then read `~/.openpnp2` and the lab's files, unwarned.

`ci/tests/test_sim_env.py` scans the gates' sources (`src/test/sim`, `src/test/web`, `infra/*/e2e`, the Java tests
that read env) for an env override with a lab computer path as its default, and fails the infra job when sim_env.py
does not set it. It knows the usual spellings (`os.environ.get`/`os.getenv`/`os.environ[...]` in either quotes,
`${X:-...}`, `System.getenv`, `getOrDefault`, `pick`, `process.env`; `/home/`, `~/`, `expanduser`, `Path.home`,
`user.home`, `$HOME`, `homedir`, and names bound to those two levels deep, `acc.HOME` across files); its docstring
lists what it does not see.

## Tests
```
python3 -m unittest discover -s infra/ci/tests -t infra/ci
python3 -m unittest discover -s infra/deploy/tests -t infra/deploy
python3 -m unittest discover -s infra/lumen-sim/tests -t infra/lumen-sim
```

End-to-end tests with the real OpenPnP build start Java and need the jar (`mvn -DskipTests package`); they skip
without their env:
- `deploy/e2e/test_deploy_openpnp.py`: il_deploy with lumen-sim, no uinav, about 2 min. CI runs it as the
  `deploy-e2e` job (the `gate` needs it); the command is in `deploy/README.md` "End to end".
- `lumen-sim/e2e/test_openpnp_pty.py`: OpenPnP on lumen-sim through openpnp-uinav's `--controller pty`.
