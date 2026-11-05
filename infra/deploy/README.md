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
   - the `gate` check-run of the CI run that built it (`BUILD-INFO.run_id`) is `completed/success`: the newest
     `gate` in that run's own check suite (`actions/runs/<run_id>` gives `check_suite_id`; a re-run attempt adds its
     check-runs to the same suite). A `gate` of another run on the same sha, a `workflow_dispatch` re-check or a PR
     from a fork, neither vetoes nor approves the build;
   - the artifact is CI's, not only its commit (a build of a dirty tree at the head reports the head's sha:
     buildnumber `doCheck=false`; anyone with push access could publish it under a matching tag). The release's
     author is `github-actions[bot]` (CI publishes with `github.token`), and `BUILD-INFO.run_id` is a
     `completed/success` run of `.github/workflows/ci.yml` with event `push`, `head_branch` `il/integration`,
     `head_sha` the build's sha and `run_number` the tag's. A release published by hand is refused for good, so its
     run number cannot stall CI's (which are numbered far below it). A run not completed yet (the release job
     publishes before its run ends) is retried at the next poll;
   - the tarball's bytes are what that run attested. CI's release job attests the tarball before publishing it
     (`actions/attest-build-provenance`: a Sigstore signature from the run's own GitHub OIDC identity, which the
     workflow cannot change). il_deploy runs
     ```
     gh attestation verify <tarball> --repo theinstancelabs/openpnp \
       --signer-workflow theinstancelabs/openpnp/.github/workflows/ci.yml --source-ref refs/heads/il/integration \
       --source-digest <BUILD-INFO.sha> --deny-self-hosted-runners --format json
     ```
     and from its JSON requires an attestation whose signing certificate names the run `BUILD-INFO.run_id`
     (`runInvocationURI` `.../actions/runs/<run_id>/attempts/<n>`, with the provenance's `runDetails` invocationId
     the same; the repository, ref, sha, signer workflow and `github-hosted` runner are checked again there).
     Fail closed. A **definite mismatch** is refused for good, like a hand-published release: gh verifies an
     attestation of these bytes in the repo, but of another run, workflow, ref, commit or a self-hosted runner (when
     the policy fails, a second `gh attestation verify --repo` without it tells this apart, and gives the field that
     differs). Anything else is refused and retried at the next poll, never marked bad: no `gh`, a `gh` without
     `attestation verify` or one of the policy flags, `gh` not logged in, a network error, no attestation. `gh` is
     checked before the tarball is downloaded. `GhAttestation` in il_deploy.py; tests pass one with a fake `gh`
     through `main(argv, deps)` (no flag or environment variable can replace it, so the units always verify);
   - the tarball matches its `.sha256`, holds only `openpnp-il/`, and its jar matches `jar_sha256`. It is
     verified (sha256, then attestation) before it is unpacked, and kept in `releases/<tag>/` for the deploy's
     re-check.

   Refusals go to `log/deploy.jsonl` with the reason. Unpacking happens in a temp dir that is renamed into
   place in one step.
2. **deploy** (and **run**, which polls first; the timer calls `run`):
   - **OpenPnP ended on its own.** If the OpenPnP service exited without il_deploy stopping it (the unit is
     `failed`: a JVM crash, the OOM killer, Xvfb lost, an ExecStop that could not confirm the STOP; under the
     subprocess manager, its pid file left behind), the outputs keep their state, nothing sent a STOP, and a person
     may be at the machine: `run`, `deploy` and `rollback` set the hold and exit **5** (`health` and `resume` set it
     too). Once per exit (`<root>/openpnp-exit.json`); a start the serial lock refused (75) is not one.
   - **Idle check.** All of these must hold:
     - STATE.md has exactly one `- **Holder:**` line, its value starts with `nobody` (`**nobody**` or `_nobody_` too), nobody `wants` the
       machine, and there are no notes (sub-bullets) under it: a note is for the next taker to read first;
     - no hold latch is set;
     - the serial flock can be taken, or only the OpenPnP service holds it;
     - no other process has the serial device open, and the device exists;
     - no other OpenPnP runs with a real-machine config: one whose machine.xml is unreadable, or has a driver on
       serial, unless that port is lumen-sim's (an absolute `ttyLUMEN*` link to an existing `/dev/pts/N`, no
       `/dev/<name>`, and no `usb-serial-number` or `usb-description` anywhere in the file: uinav's
       `--controller pty` sessions and the e2e), so SIM sessions on the lab computer do not stop deploys;
     - `runs/serial.log` (and any other `--activity` file) has been quiet for 15 min;
     - OpenPnP's own log (`state/config/log/OpenPnP.log`) has been quiet for 15 min, except what it wrote in
       il_deploy's own windows (below). OpenPnP logs each change through its API (`ApiServer ... POST ... -> 200`),
       the engine's work, its start and its stop, and nothing while idle, so the quiet period runs from its last
       line also after a person stopped the service or it died (the API is not asked then). `deploy`/`run` only: a
       person running `health` has just started OpenPnP;
     - if OpenPnP runs, its API reports no run, no pressure, nothing busy, no home running (`machine.homing`), the
       machine **switched off** (`machine.enabled` false: someone who pressed Start and Home in the web UI may be
       working at the machine by hand), and nothing recent in the last 15 min:
       - no run ended (`run.finishedAtMs`, `lastRun.endedAtMs`), no pad calibration ended
         (`padCalibration.endedAtMs`), no pressure event (`pressure.lastEventAtMs`). The pressure hold's first value,
         stamped when the API starts (`PressureHold`), is not an event: `lastEvent` exactly "pressure off" (every
         event after it has more text) and, with `server.startedAtMs` (BR-44, the JVM's start), stamped within 120 s
         of it (`STARTUP_STAMP_S`: the API comes up within `--status-timeout`'s 120 s, or il_deploy calls the start
         failed). Both must hold: a person's event early after the start counts, and so does a first value whose text
         changed (fails closed: the quiet period then runs from OpenPnP's start). A build before BR-44 has no
         `startedAtMs`: there the text alone;
       - no home (`machine.zCheck.homeAtMs`);
       - no hand work in the dispense config: needle change, touch-off, landing, XY trim and anchor
         (`dispense.needle.changedAtMs`, `setAtMs`, `landing.setAtMs`, `xyTrim.setAtMs`, `anchor.setAtMs`), tip
         mounted (`dispense.tip.mountedAtMs`), new seat (`newSeatAtMs`), a place set (`locations.*.setAtMs`), a
         surface probed (`surfaces.*.setAtMs`), the purge row wiped (`purgeRow.resetAtMs`), the screw's start angle
         confirmed at the machine (`profile.startAngleSetAtMs`), the controller connected, i.e. Start pressed
         (`profile.controllerConnectedAtMs`).

       The paths are `ACTIVITY_FIELDS` in il_deploy.py. A missing field is skipped, so the e2e checks every path
       il_deploy reads (`STATUS_FIELDS`, and `setAtMs` in each place and surface) against the real build: a
       backend rename fails the gate instead of turning a check off. A path behind a null, or in an empty map, is
       not there to check: the e2e seeds a place, a surface and a needle anchor, and requires the paths it still
       cannot check (`unchecked_status_fields`) to be exactly the five that need an engine run or a pad
       calibration (`run.*`, `lastRun.endedAtMs`, `padCalibration.*`; asked of Backend as a contract test). What OpenPnP stamped or logged while il_deploy alone drove it does not count: each claim, start
       and health check keeps its time window in `<root>/own-activity.json` (the health check's home; a build's
       first start can stamp the config itself, e.g. the needle landing "kept from before the update"). If OpenPnP
       is not running, nothing may answer on its port.
     - `run` (the timer) only: no `il_deploy.py resume`, `health`, `deploy` or `rollback` by hand in the last 15
       min, whatever its result (`<root>/person.json`): the person who ran it may still be at the machine, e.g.
       checking the build a rollback went back to. What those commands made OpenPnP stamp or log (the start, the
       connect, the home) is in il_deploy's own windows, so this is what says someone is there. A deploy by hand
       has its person there, so it does not wait.

     If any check fails, it exits **3** with `not idle: <reasons>`.
   - **Checks at deploy time.** The staged folder must agree with itself (BUILD-INFO, VERSION.txt, jar sha256)
     and with the GitHub release of its tag: the release's BUILD-INFO.json is downloaded again and its `sha` and
     `jar_sha256` and `run_id` compared, so a folder copied into `releases/` by hand is refused; the release's
     author and CI run are checked again (a release can be deleted and published again under its tag), and the kept
     tarball's attestation is verified again (an attestation can be deleted, e.g. to revoke a build; a folder without
     its tarball is refused). The sha is re-checked on `il/integration` with a green `gate` of its own CI run.
     `--version` of a build older than the current one is refused: the current build migrated the config one way;
     `rollback` goes back with the matching snapshot. While the
     current build is unverified (below), every deploy is refused (exit 2, so the timer unit fails visibly).
     With the systemd units, deploy and rollback are refused (exit 2) until the user manager may take long enough
     to stop at a reboot (below, "Installing").
   - **Claim.** It writes `Holder: Infra (deploy <tag>) since <time>` into STATE.md (atomic rename; it aborts
     if the file changed meanwhile) and checks idle again. The claim is also kept in `<root>/claim.json` until it
     is handed back (see "Killed outright" below). Only the Holder's value is replaced; its text is
     kept and comes back in the release (`...; earlier: <previous text>`), or exactly as it was if the
     re-check finds the machine busy. SIGTERM waits while the claim is written.

     The claim is checked again before OpenPnP is stopped, before the enable and before the home: STATE.md's own
     edit check protects only il_deploy's writes, and a save from a buffer opened before the claim (an editor, a
     read-modify-write script) replaces it silently. Gone: nothing more is done (a machine il_deploy enabled is
     STOPped), the hold is set, exit 5. A claim gone at the hand-back is said on stderr and in the journal, sets the
     hold and makes the exit code non-zero; someone else's Holder is never overwritten.
   - **Switch.** It stops OpenPnP gracefully (the planned stop below, waits until the machine is disabled, then
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
        in no fixed order, so a 200 without the engine is waited out up to `--status-timeout` (120 s) while
        `dispense.state` (BR-44) is `starting`; an engine that never starts fails the check then ("did not start
        within 120 s"). `dispense.state` `failed` (`DispenseService.start` threw) fails it at once, with
        `dispense.startError` in the reason. A build before BR-44 has no `state`: every 200 without the engine is
        waited out.
     2. Enable.
     3. The log shows `Serial port: connecting to ...` (`SerialPortCommunications.resolvePort`) matching
        `--serial-match` (default ` S/N <--usb-sn>`, i.e. ` S/N 387932683335`) after the enable or, when the enable
        logged no connect, as the last connect line of the current `OpenPnP.log`: with `connection-keep-alive` (the
        lab machine.xml) a STOP leaves the link open and the next enable does not connect again, and the file is this
        OpenPnP's own (tinylog's StartupPolicy starts a new one at each start). The e2e runs this step against the
        real jar with the lumen-sim link as the pattern.
     4. Controller axes are read: X, Y, Z and B must all have a driver coordinate (else "the controller position was
        not read"), and they must not be the power-cycle signature (X/Y/Z 0 with A or B at 720, within 0.5: Marlin's
        M410, sent by every STOP, recomputes the position from the step counts, so a reset controller STOPped once
        reads 720.05; `RESET_ROTATION_TOLERANCE`). Either way it does **not** home.
     5. Home. Whatever the answer, it never STOPs while the machine is busy or homing: on anything but `homed` it
        waits while `machine.busy` or `machine.homing` (BR-44) is true (up to `--home-busy-wait`); a build before
        BR-44 (a rollback can land there) has no `machine.homing`, so `machine.busy` alone (the home tests run on
        both status shapes). The API answers
        409 "the home did not start or did not end within 5 min" after its own limit (`MachineEndpoints.home`)
        while the home may run on; il_deploy's
        `--home-timeout` (330 s) is longer. Such a home that then ends homed passes; one that ends unhomed is a
        machine problem, one still busy is "a home that never ends" (below). A home that failed on OpenPnP's own
        timeout (`homing failed: ... timeout waiting for response to G28`: GcodeDriver sends G28 with
        `infinity-timeout-milliseconds`, 60 s in the lab machine.xml, e.g. a stalled axis or a broken endstop) is a
        home that never ends too: OpenPnP's task ended, so it is not busy, but the controller may still be in G28,
        where a STOP's M410 kills the board. No STOP, OpenPnP is left alone. Any other refusal or failure (`homing
        failed`: fiducial covered, endstop, obstruction; or something else holds the machine: the pressure, an
        engine task, OpenPnP busy) is a machine problem: no second home unwatched. Only a 404 (no home endpoint)
        is the build's.

        From the enable on, OpenPnP's API going away or not answering (the JVM dies mid-home, e.g. a native camera
        library crash; a person stops the service because something looked wrong) is a machine problem too, never
        the build's: the controller may still be finishing that G28 and the outputs keep their state.

        A controller power-cycled while OpenPnP ran with its link kept open (a health check by hand): since BR-44
        Start finds the link dead and connects again (`GcodeDriver.setEnabled`: "the kept-alive link to the
        controller is dead: connecting again"), so step 3 reads that new connect line and step 4 finds the boot
        position (no home). A build before BR-44 failed the enable on the dead port (`enable: HTTP 409 ... IO Error`,
        no home). A deploy restarts OpenPnP, so its enable always connects afresh.
     6. Homed and not busy.
     7. The planned stop (below), confirmed as a STOP.
   - **On failure it rolls back.** It STOPs (only if the STOP is safe; otherwise it leaves OpenPnP running,
     sets the hold and records the new build as installed but NOT verified), restores the snapshot (keeping the
     abandoned state as `snapshots/<ts>-abandoned-<tag>/`) and switches back. The restore and the switch are one
     recorded step (a rollback's too): the snapshot is copied next to the live config first (cut there, nothing
     changed), then `<root>/switch.json` records the renames, the links and the new deployed.json before the first
     rename, and they run with SIGTERM and SIGINT deferred. Whatever cuts it (an error, SIGKILL), the record
     finishes it: the exception path, or the next command. So the config, the binary and the records never
     disagree (no newer build on an older config). Then:
     - **A machine problem** (a reset controller, the serial lock taken at start, a failed or refused home): the
       previous build is put back with its config but not checked, so nothing moves before a person looks. It
       runs again (not enabled) if it ran before. The hold is set. Its record is marked `unchecked`, and the
       Deployed line says "not checked" until a health check passes.
     - **Otherwise** the previous build is started and checked; until that check passes it is pending (and the
       Deployed line says so). The new build goes on the **bad** list (`bad.json`, `run` never picks it again)
       only if the previous build passes. If the previous build fails too, the machine is suspect: nothing goes
       on the bad list, the hold is set, and the previous build stays pending (deploys refused) until a health
       check passes.
     - **A home that never ends** (or that OpenPnP timed out on), **the claim lost**, or **a lost controller
       link** (BR-93, kind `link-lost`): OpenPnP is left alone (running), the build stays installed but NOT
       verified, the hold is set.
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
   does not redeploy it. It marks the build it goes back to pending in the same recorded step as the config and the
   links (above), then writes the Deployed line; a rollback whose health check fails leaves it pending (deploys
   refused) until a health check passes. Cut before that step (Ctrl-C, SIGTERM, a full disk while copying the
   snapshot), nothing changed and it can simply be run again. The snapshot is from before the deploy, so a rollback undoes every config change
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
   `resume`) finds `claim.json` with no il_deploy holding the root lock. It first finishes a restore and switch
   that `switch.json` records. Then it sets the hold, turns the Holder into
   `Infra (deploy <tag>): a person must check the machine (... il_deploy was killed during the deploy ...)`, and
   writes the Deployed line: `<tag> installed but NOT verified: the deploy was cut short` after the switch, or the
   current build "not checked since the deploy of <tag> was cut short before its switch" (pending until a health
   check passes) before it. A `resume` that finds the claim itself refuses (exit 2): nobody has seen that hold yet,
   so nobody checked the machine for it; check the machine, then run `resume` again, which hands the Holder back as
   for any hold. Until a command runs, STATE.md shows the claim and the "being installed" Deployed line.

   After an unexpected exception (SIGTERM included) the Deployed line and deployed.json follow what deployed.json
   records against the `current` link, as here: a build switched to and not verified is "installed but NOT
   verified"; a pending build never switched to is undone (the current one then counts as not checked since); a
   verified build stays verified.

Exit codes:

| Code | Meaning |
|---|---|
| 0 | Done, or nothing to do |
| 1 | Error, or an unexpected exception after the claim |
| 2 | Refused (not staged, not eligible, not CI's, not on GitHub, older than current, current not verified, no previous version, config changed since the deploy; `resume` that just found a hold) |
| 3 | Not idle |
| 4 | Deploy failed its health check and was rolled back |
| 5 | Failed, and a person must check the machine (the hold is set; the Holder stays taken until `resume`), or OpenPnP ended on its own |

## Safety rules it enforces
- Never deploys a build that is not green, or whose sha is not on `il/integration`. It re-checks GitHub at
  deploy time too. `--version TAG` overrides only the bad list, never eligibility.
- Never while someone holds the machine. Any doubt counts as held: a missing or doubled Holder line, `none`,
  `free`, `Nobody?`, `~~nobody~~`, a `wants` note. Emphasis around `nobody` is not doubt (11-04: a bold release
  refused a deploy).
- Never STOPs during a home: a STOP inside G28 hangs the controller until a power cycle. One rule for every real
  STOP (failures, the exception path, the unit's ExecStop, the test service manager, a stop where no planned stop can be
  used); a planned stop is refused by OpenPnP itself while anything works, a home included:
  - `machine.homing` true (BR-44: from the start of any home, the web's, the engine's or OpenPnP's own Home button,
    until it ends; `ReferenceMachine.isHoming`): wait, whatever else the status shows (an engine run past its home
    phase, the pressure held: OpenPnP's Home button does not check the web's pressure hold, and the valve-off lines
    would queue behind the G28 a STOP kills). A build before BR-44 lacks the field: the rules below alone;
  - the pressure held (`pressure.state` `on` or `vent_failed`), or an engine run past its home (its phase is not
    `home: ...`): STOP at once, outputs may be on (homes refuse while the pressure is held);
  - the pressure `starting` or `stopping` with the machine busy: wait as for a home. `PressureHold.start` sets
    STARTING on the HTTP thread and queues its task behind the machine thread's work, which may be a home;
    nothing is on before that task runs, and a running start ends within seconds;
  - otherwise wait while the machine is busy or its state is unknown (no answer in time, HTTP not 200), up to
    `--home-busy-wait` (5 min), then STOP. Still busy: no STOP, OpenPnP is not killed by il_deploy, and the
    hold is set. If systemd stops OpenPnP anyway, SIGTERM closes the port and Marlin finishes the home.
    `machine.homing` false does not end this wait: it turns true only when OpenPnP's home starts, and the engine's
    home phase and the web's home first read the position and raise N1 (`DispenseService.homeSafely`), busy and not
    homing yet; a STOP then may meet the G28 that follows (WEB_API.md names this window). So busy and a run in its
    `home: ...` phase are waited out as before;
  - OpenPnP not answering at all (connection refused): nothing to stop.
- Never stops, starts or homes a machine whose controller link is lost (BR-93: `machine.linkLost` in `/api/status`
  and `/api/machine`, absent before BR-93, null while the link is fine, anything else counts as lost): the idle
  check is not idle, so no deploy (its restart would clear it). The stop before the switch reads it again after its
  planned stop: lost, or no answer, is not idle (exit 3, OpenPnP left running, nothing deployed). The recovery's
  service stop after a failed check reads it after its STOP: lost leaves OpenPnP alone (held, nothing put back or
  restarted, the build not marked bad). The health check reads it before its stop of an enabled machine and after
  it (before the Start), before its home, and before and after its last stop (#53's planned stop answers 200 on a
  lost link: nothing reaches the controller; after one that passed, a read without an answer counts as lost),
  and at every step that fails after the status step (a Start, home or read that failed on a link lost meanwhile:
  #53's 409 `MACHINE: …`, `homing failed: … IO Error …`, homed reading false). Set at any of them, the check fails
  with kind `link-lost`, handled like a home that never ends: no STOP (nothing reaches the controller), no config put
  back (the config from before the deploy lacks the engine's journal rows of the loss), no restart (it clears
  linkLost, as a connect does; a STOP does not); OpenPnP is left running with the loss showing, the build installed
  but not verified, the hold set, the build not marked bad.
- Never homes a controller that shows the power-cycle signature. It reports `controller was reset; a person
  must check the machine` and sets the hold.
- **The deployer's own stops are planned (BR-75):** the health check's last stop, its stop of a machine left enabled
  and the stop before the switch use `POST /api/stop/planned`: OpenPnP's STOP without the screw's motor-off line
  (`M18 B`), so the screw keeps its timing through a deploy; it also closes the controller link. It is confirmed as a
  STOP (below); a 200 whose lines went unanswered (`linesFailed`: the valve or pump may be on) is a failure, and in
  the health check a machine problem (nothing restarts and homes unattended); so is a request that timed out (it
  may have run: no second stop blind), and in the health check a 200 that sent nothing (`linesSent` empty, "machine
  was already stopped") after a refusal of the same stop: #41 answers 409 when its task outlives its 20 s wait, its
  lines out and their acks lost.
  - Before the switch the machine is off already (the idle check), so neither stop sends a line; the planned one
    guards the race of someone pressing Start after the idle check: `409 locked`, nothing sent, instead of a STOP on
    their session. The unit's ExecStop then finds the machine stopped and sends nothing; SIGTERM sends nothing either.
  - A refusal is never answered with a real STOP before the switch. `409 locked` ends the deploy or rollback at once,
    `409 refused` (something works) is asked again every 5 s for `--stop-timeout`, then it ends: exit 3 `not idle`,
    nothing changed, no hold, a later run deploys. Here only, a refusal while `/api/status` shows the machine
    stopped and idle and nobody holding the lock is nothing to stop: #41 refuses without its engine (a failed engine
    start) or a machine before it looks.
  - In the health check (OpenPnP it just started, and homed) `409 locked` fails the step at once, as someone else's
    session (`claim-lost`, left alone, as before); `409 refused` is asked again every 5 s for `--stop-timeout` first,
    and only one that lasts fails it, as a machine problem, which the deploy's failure path handles like any other
    (its STOP if safe, the previous build back unchecked, the hold).
  - No planned stop to use (a build before BR-75: 404 `no such endpoint`; no connection to the API, any transport
    error but a timeout): the stop as before BR-75 decides. That is the first deploy over an older build, and a
    rollback to one. Failures and the exception path STOP for real, as does the unit's ExecStop (`stop-openpnp`).
- Always a stop through the API before stopping the process. SIGTERM saves nothing and sends no Stop, and the
  outputs keep their state. A STOP is confirmed only when `/api/status` shows the machine disabled, the dispense
  engine idle and the pressure hold off: `MachineEndpoints.stop` disables the machine, but the engine's abort sends
  its valve-and-pump-off lines only after the move in progress (a 10 s prime), and with the plain GcodeDriver the
  DISABLE_COMMAND's M410 can time out behind it and skip the M107s. The confirm waits `--stop-timeout` (90 s: the lab
  machine.xml's longest driver timeout is 60 s) or what the home wait left, whichever is longer. If the STOP is not
  confirmed, il_deploy leaves OpenPnP running and sets the hold; the unit's ExecStop exits 1, so the unit ends
  `failed` and il_deploy's next command sets the hold.
- Rollback restores the config snapshot together with the binary, because the schema migrations are one-way.
- The Holder is handed back on every exit path: `nobody (...)`, or the held text above when a person must
  check. After an unexpected exception (SIGTERM included) the machine is stopped through `/api/stop` if that is
  safe, the note says what actually happened, the exception is recorded and the hold is set. SIGINT (Ctrl-C) and
  SIGHUP take the same path. SIGHUP is the terminal of a command by hand going away (closed, SSH dropped, tmux pane
  killed): writes to it fail from then on, so il_deploy first points its terminal output at /dev/null (files and
  pipes stay). Under `nohup` SIGHUP stays ignored. If STATE.md cannot
  be written, il_deploy says so on stderr, sets the hold and exits non-zero.
- **Hold latch** (`<root>/hold.json`): set when a person must look at the machine. It blocks deploys until
  `il_deploy.py resume`, which also hands back the Holder the hold left. A hold set while one is set (OpenPnP dies
  while a held deploy left it running) adds its reason: the Holder to hand back and the earlier reasons stay, and the
  not-idle message lists them all. The hold Holder is not free, so to check the machine before `resume` use
  `il_deploy.py health --no-claim`. After `resume` (and after `health`, `deploy` or `rollback` by hand) the timer
  waits the quiet period before it deploys and homes again: the person may still be at the machine.
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
il_deploy.py keep-up                    # start OpenPnP (never enabled or homed) if it is down and the machine is free
il_deploy.py rollback [--discard-config-changes]   # one step back
il_deploy.py health [--sha7 X] [--no-claim]   # JSON report; --no-claim if you hold the machine already
il_deploy.py status                     # JSON: versions, last deploy, idle verdict, hold, bad list
il_deploy.py resume                     # after checking the machine: clear the hold, hand back the Holder
il_deploy.py units --out DIR            # render the systemd user units (and the user manager's system drop-in)
```
`--trusted-origin URL` (repeatable): an origin the API must also answer, the real machine's web UI on the tailnet
(`https://lumen-thinkpad.tail3e978a.ts.net:8877` = `tailscale serve --bg --https=8877 http://127.0.0.1:18877`, no proxy
in between: tailscaled keeps the Host header and passes the browser's Origin). `units` puts it into OpenPnP's
environment (`OPENPNP_IL_API_TRUSTED_ORIGINS`, BR-73) and into the deployer's own flags; the health check then probes
each one before it enables anything: a POST with that Host and Origin must pass the API's browser guards (404 on a
route that does not exist) and a foreign Origin must still be refused (403). A build from before BR-73 lists no
trusted origins: passed with a note, so a rollback to it stays possible (the tailnet URL is refused there).

`keep-up` keeps the real machine's web UI up (the user's P0, 11-03): when OpenPnP is down it starts it, never enabled
or homed, unless the machine is not free: a Holder other than nobody (a bench session), a hold, the serial lock or
device taken, another OpenPnP or something else on the API port. An exit il_deploy did not cause sets the hold first,
so a crash is never restarted over a machine a person must check. It runs under the root lock (never during a deploy).
Before a bench session stops OpenPnP, write the Holder first, or keep-up starts it again within a minute: the UI comes
back while you work (a second person could press Start), and the bench tool refuses on the serial lock.

**BR-72's machine lock:** no deploy while `status.lock.holder` is set (an agent role, or the user, whose hold never
expires: automatic deploys wait for Give back in the web UI); a lock field it cannot read counts as held. A lock
taken during a health check (refusals `locked` or `taking-over`, or the machine kept on after the last STOP) is that
person's session: no STOP, no rollback over it, the build stays installed unverified, with the hold. `health` by
hand refuses while the lock is held.

**The run marker** (`<root>/openpnp-run.json`): the unit's second ExecStartPre, `mark-start`, refuses a taken serial
lock (java never starts then) and writes it with this boot's id; only `stop-openpnp` (the ExecStop) clears it, and only
after a confirmed STOP, or when a live JVM is stopped before its API is up. OpenPnP down with the marker standing ran
without a confirmed STOP: an exit il_deploy did not cause, so the hold, also across a reboot (systemd forgets
`failed` at a reboot; a freeze, a power cut or an unconfirmed STOP at shutdown leave the marker). A start that flock
refused (exit 75) drops it without a hold. `status` shows it (`run_marker`).

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
   units' own budgets (il-deploy 10 min, then il-openpnp 7 min) never run. The drop-in sorts after `timeout.conf`
   and sets 18 min:
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
| `il-openpnp.service` | Java with the three `--add-opens`, `-DconfigDir=<root>/state/config`, prefs in `<root>/prefs`, `-Xmx1536m`, API port 18877, `DISPLAY=:50`. Started under `flock -n -F -E 75 /tmp/lumen-serial.lock`, so the bench tools refuse while it runs; `-F` (no fork) makes java itself the main process, so the SIGTERM after ExecStop reaches it and its shutdown hooks run (prefs sync, log flushing; flock does not pass SIGTERM on, and `KillMode=mixed` SIGKILLs what is left). `SuccessExitStatus=143`: java's exit on SIGTERM is a clean stop, so the unit ends `failed` on a crash or an ExecStop that could not confirm the STOP (also after java ended by itself: systemd runs ExecStop then with `$MAINPID` unset, and `stop-openpnp` exits 1 when nothing answered). `ExecStop` = `stop-openpnp --home-busy-wait 300 --stop-timeout 90` (the STOP rule above; exit 1 if not confirmed); `TimeoutStopSec=420` covers that wait plus the STOP and its confirm; `KillMode=mixed`, `Restart=no`, higher CPU, IO and memory priority. It is not enabled at boot: deploys and people start it. |
| `il-openpnp-xvfb.service` | `Xvfb :50 -nolisten tcp` (outside uinav's :91-:299). `ExecStartPost=il_deploy.py wait-display` (up to 20 s for `/tmp/.X11-unix/X50`): Type=simple counts as started at the fork, and OpenPnP's first AWT call exits 1 without the socket, which at the first start after a boot would put a good build on the bad list. |
| `il-deploy.service` | oneshot `run`. Exit code 3 (not idle) counts as success; no start timeout (a deploy homes the machine); 10 min stop timeout. `After=il-openpnp.service`, so at shutdown it stops first: its SIGTERM handler waits out a home and STOPs before the OpenPnP unit's ExecStop runs. That needs the user manager's drop-in above; without it a reboot kills both after 5 s (then "Killed outright" applies). |
| `il-deploy.timer` | Starts the deploy service 2 min after the last run ended (and 5 min after boot). |
| `il-keepup.service` | oneshot `keep-up` with the deployer's flags; exit 3 (not free) counts as success. |
| `il-keepup.timer` | Starts keep-up 1 min after its last run ended (and 1 min after boot). Enable it on its own: `systemctl --user enable --now il-keepup.timer` (the deploy timer may stay off). |

GitHub access: `gh` when it is logged in, else unauthenticated urllib (60 requests/h). An idle poll costs one
request. Attestations are verified with `gh` only (2.97 on this host; `gh attestation verify` needs the policy flags
above), logged in for the user the units run as: without it every build is refused (and retried), never staged.
Check from the unit's environment with `systemd-run --user --wait --pipe gh auth status` (gh keeps its token in the
desktop keyring here).

## Tests
From the repo root (about 2.5 min, 320 tests; starts fake OpenPnP processes on free loopback ports, never a serial port):
```
python3 -m unittest discover -s infra/deploy/tests -t infra/deploy
```
`python3 -m unittest discover -s infra/deploy/tests` (no `-t`) works too: each test module puts
`infra/deploy` on `sys.path` itself (`test_discovery.py` checks it).
- `tests/fake_openpnp.py` is a fake OpenPnP API with configurable failures: version, enable, reset, no S/N,
  home, slow home, stop, busy, run (with its phase; a STOP ends it, `run_abort_s` later), pressure (a STOP vents
  it; `vent-fail`), crash, engine (not started), unavailable, machine-500, home-404, axes left out or without a
  driver coordinate, and failures for one build only; BR-75's planned stop (refused while anything works or for
  the first `planned_refusals` calls; `old-stop`: a build before it, 404; `lock-at-planned-stop`), and a STOP's
  `M18 B` on an open link recorded as `{"screw": "M18 B"}`. Its status has the real shape: `run.finishedAtMs`,
  `lastRun.endedAtMs`, `padCalibration.endedAtMs`, the pressure hold's start value, `machine.zCheck.homeAtMs`
  and the dispense config's times (needle, XY trim, anchor, tip, seat, places, surfaces, purge row, start angle,
  controller connected). It answers as the real server does:
  `engine_start_s` (the API answers before the dispense engine runs); home refusals before the home starts, and
  the 409 after the server's own limit while the home runs on (`home_cap_s`, 300 s like `MachineEndpoints`); a
  new `OpenPnP.log` at each start, and a link kept open across a STOP (`keep_alive`, true like the lab
  machine.xml), so only the first enable logs the connect line. BR-44's fields as the real server sets them
  (`machine.homing` while a home runs, or the engine run's home with `run_homing`; `server.startedAtMs`, `jvm_start_s`
  before the API; `dispense.state` `running`, `starting` before `engine_start_s`, `failed` with `startError` for the
  `engine` failure), or a build before them (`old-status`); `pressure_initial_event` renames the pressure hold's first
  value; `power_cycle()` kills a kept-open link (the next enable connects again and logs it, or with `old-status`
  fails on the dead port) and boots the controller at X/Y/Z 0, B 720 (720.05 after a STOP, as Marlin's M410).
- `tests/fakes.py` has a fake GitHub with a real commit graph, so the compare direction is tested, release authors,
  CI workflow runs (`actions/runs/<id>`) with their check suites and `gate` check-runs per suite, and the
  attestations GitHub stores per artifact digest; a fake `gh` (`FakeGhCli`) for `GhAttestation`: `attestation
  verify` with gh's policy flags, exit codes and JSON (`tests/gh-attestation-verify-cli-2.97.0.json` is a real
  output, of gh's own release, that the JSON check is tested on), `--help`, `auth status`, and no gh, an old gh, not
  logged in, offline; for CI's publish step also `api` (JSON and asset bytes), `release view` and `release create`
  (`tests/fake_gh.py` runs it as a `gh` executable, its FakeGitHub kept in a JSON file between calls); CI-style
  release tarballs, or exactly what `infra/ci/make_release.py` writes (`Lab.ci_release`, published by
  `FakeGitHub.add_release_dir` as `gh release create` does); and a throwaway lab (root, STATE.md, lock,
  device, activity file). `Lab.start_service` starts the fake OpenPnP as a person did an hour ago: it waits for its
  API (a deploy over an OpenPnP still starting is "not idle", which made the acceptance test fail about 1 run in 10)
  and dates its OpenPnP.log back. `Lab.kill_service` SIGKILLs it as a crash would.
- `tests/test_acceptance.py` is INF-02's CI acceptance: a deploy dry run into a test directory, a second
  deploy over a running OpenPnP, and a rollback to the previous version.
- `tests/test_release_contract.py`: make_release.py's output is staged, deployed and rolled back, also two
  builds of one jar under one sha7 (what the end-to-end test publishes).
- `tests/test_stop.py` covers when a STOP is sent (stubs with a fake clock, and the fake mid-home).
- `tests/test_service.py`: the subprocess service manager (tests and CI) runs while any process of its group
  does, and `stop` waits for all of them, as systemd does for the unit's cgroup. A process whose main thread exited
  first shows Z in `/proc/<pid>/stat` while its other threads run on and hold the serial lock: it counts as running
  while `/proc/<pid>/task` has a live thread. The unit's own command hands SIGTERM to the program (`flock -F`).
  `last_exit` of both managers: a crash versus a stop.
- `test_units.py` runs `systemd-analyze --user verify` on the rendered units when it is installed. It only
  verifies; it never installs. It also checks the user manager's drop-in covers both units' stop budgets, and
  `wait-display`.
- `test_deploy_flow.py` also kills il_deploy outright in a forked child (SIGKILL at a chosen step) to check what
  the next command finds, and covers a deploy and a rollback whose pre-switch STOP is not confirmed, a rollback cut
  while copying, signalled, failing or killed inside its switch, OpenPnP killed mid-home and on its own, a lost
  claim at each check and at the hand-back, and releases not published by CI.
- `test_ci_wiring.py`: `ci.yml` has one job that runs the end-to-end test below with `IL_DEPLOY_E2E=1`, and the
  `gate` needs it. It evaluates the concurrency expression (a small GitHub-expression evaluator): a PR's runs share
  a group and the newer cancels the older; a push or manual run never shares one with any other run. The release job
  needs the gate, runs only for a green push to `il/integration` (its `if:` evaluated per event, branch and gate
  result), attests `openpnp-il-*.tar.gz` from the directory it publishes from before the publish step, and has
  exactly `contents`, `id-token` and `attestations: write`. Its Publish step's own script runs as GitHub runs it
  (bash -eo pipefail, the job's environment) with `tests/fake_gh.py` as `gh`: it publishes this run's build, a
  re-run passes on that release, and a release someone else put at the tag first (a person; `github-actions[bot]`
  from a PR run's token, naming this run, its tarball not attested by it) fails the step. Each check is tested on a
  weakened copy of `ci.yml` too, among them the Publish step before the 11-01 review, which passed both.
- `test_publish_release.py`: `infra/ci/publish_release.py` (the Publish step). Nothing at the tag: `gh release
  create` with the three files, a prerelease at the commit, not latest. A release already there passes only as this
  run's own, judged as this deployer judges a release: author `github-actions[bot]`, at the commit, exactly the three
  files, BUILD-INFO naming this run, the tarball matching its .sha256 and attested by this run
  (`GhAttestation.command`, then `attestation_problems` with this run's id). Nothing of a release CI did not publish
  is downloaded, and the tarball only after BUILD-INFO names this run.
- `test_attestation.py`: `GhAttestation` against the fake `gh` (the command line, accepted, definite mismatches
  refused for good, everything else retried) and the real JSON shape.

## End to end with the real OpenPnP (`e2e/`)
`e2e/test_deploy_openpnp.py` runs the deploy flow with the real jar instead of the fake OpenPnP, in front of
lumen-sim (`../lumen-sim`), the simulated controller on a pty. OpenPnP connects through its real serial stack.
It needs no openpnp-uinav, so it runs on a stock GitHub runner. About 3 minutes (164 s on the lab computer,
11-03: deploy 26 s, deploy over a running OpenPnP 29 s, health by hand 25 s, rollback 29 s, the refused deploy 15 s,
test_3's two health checks 8 s each).
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
  runs in-process (`main(argv, deps)`: only GitHub and `gh` are faked: each tarball is attested by its run, and
  il_deploy's real `GhAttestation` verifies it through `FakeGhCli`, at staging and at each deploy) with
  `--service-manager subprocess` and the
  production OpenPnP command (flock, java, the unit's flags), with a temp root, STATE.md, lock and activity
  file. `--device` is the lumen-sim link, `--usb-sn ''` (a pty has no USB identity) and `--serial-match` names the
  link, so the serial step runs against the real connect line. lumen-sim gets `G92 B0` first: Marlin's boot
  position is the power-cycle signature, which is never homed. OpenPnP started by the test as a person would gets
  its OpenPnP.log dated back an hour (the quiet period).
- **test_1:** poll and deploy; OpenPnP started; `run` (exit 3 within the quiet period after the deploy by hand,
  then, with `person.json` dated back, poll and deploy of the newer build over the running OpenPnP); `health` by
  hand on that OpenPnP, whose link the STOP left open (`connection-keep-alive`): the serial step takes its
  kept-open fallback; rollback. Checked: exit codes, links, deployed.json, bad.json, the `server.version` each health
  check saw, STATE.md's Deployed line and Holder, deploy.jsonl. In lumen-sim's I/O log: one G28 per health check,
  each answered before any M410, and the DISABLE_COMMAND lines after each home. After `run`, the real
  `/api/status` has every key path il_deploy reads (`STATUS_FIELDS`, null allowed; `setAtMs` in each place and
  surface), the only paths it could not check are the five engine-task ones (`ENGINE_TASK_FIELDS`; the seeded
  place, surface and needle anchor cover the rest), and `machine.zCheck.homeAtMs` is the health check's home,
  inside il_deploy's own window.
- **test_2:** lumen-sim power-cycled, a third build: `run` exits 5 after reading X/Y/Z 0, B 720. Nothing moves
  (no G0/G1/G28), the previous build runs again unchecked, the new build is not marked bad, and the hold is set
  until `resume`. The deploy restarts OpenPnP, so BR-44's reconnect of a kept-alive link plays no part here.
- **test_3 (BR-44, BR-93):** `health --no-claim` on that build connects it (boot position: no home; its STOP keeps the
  link open). lumen-sim is power-cycled again: the kept-open link dies, BR-93 (#53) shows it as `machine.linkLost`, and
  a second `health --no-claim` is refused (exit 3, not idle) with no Start (no new connect line). Nothing moves. The
  reconnect itself is the operator's Stop and Start (Backend's PowerCycleSimTest).
- BR-44's fields on the real build (in `assert_status_fields`): `dispense.state` `running`, `startError` null,
  `machine.homing` false, and the pressure hold's first stamp within `STARTUP_STAMP_S` of `server.startedAtMs`
  (`results.json` keeps the gap, `measured`).

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
- 11-03 (test_3): the power-cycle signature compared A and B with 720 to 0.001. The health check that finds a reset
  controller STOPs it, and the STOP's M410 makes Marlin read A and B 720.05 from its step counts, so the next check
  (a health check by hand after `resume`, or the next deploy) took the reset controller for a homed one: it raised N1
  from a believed Z 0 and homed it (BR-02's hazard). Now within 0.5 of 720 (`test_health`:
  `test_reset_controller_stopped_once_is_still_never_homed`).
