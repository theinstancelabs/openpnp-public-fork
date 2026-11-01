"""Full deploy / rollback / run flows into a temp root, with a fake OpenPnP started by the subprocess
service manager. Every exit path hands the Holder back: 'nobody (...)', or held while a person must check."""
import fcntl
import hashlib
import json
import os
import re
import shutil
import signal
import sys
import unittest
from unittest import mock

# il_deploy is one level up: also when unittest imports this module by bare name (no -t)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import il_deploy  # noqa: E402
from fakes import Lab  # noqa: E402

RELEASED = re.compile(r"^nobody \(released by Infra (deploy|rollback) at \d\d:\d\d")


class Flow(unittest.TestCase):
    def setUp(self):
        self.lab = Lab()
        self.gh = self.lab.gh
        self.assertEqual(self.lab.main("init", "--seed-from", self.lab.seed), 0, self.lab.last_out)

    def tearDown(self):
        self.lab.cleanup()

    # --- helpers -------------------------------------------------------------------------------
    def release(self, n, sha, **jar):
        jar.setdefault("sha", sha)
        tag = self.gh.add_release(n, sha, jar=jar)
        self.assertEqual(self.lab.main("poll"), 0, self.lab.last_out + self.lab.last_err)
        return tag

    def deploy(self, *args, rc=0):
        got = self.lab.main("deploy", *args)
        self.assertEqual(got, rc, self.lab.last_out + self.lab.last_err)
        return got

    def assert_released(self):
        h = self.lab.holder()
        self.assertTrue(h.idle, h.value)
        self.assertRegex(h.value, RELEASED)

    def assert_released_by(self, action):
        h = self.lab.holder()
        self.assertTrue(h.idle, h.value)
        self.assertTrue(h.value.startswith(f"nobody (released by Infra {action} at "), h.value)

    def assert_held(self, *words):
        """A person must check the machine: the Holder stays taken (other roles must not read it as free)."""
        h = self.lab.holder()
        self.assertFalse(h.idle, h.value)
        self.assertTrue(h.value.startswith("Infra ("), h.value)
        self.assertIn("a person must check the machine", h.value)
        for w in words:
            self.assertIn(w, h.value)
        self.assertTrue(self.hold())

    def deployed(self):
        return il_deploy.read_json(os.path.join(self.lab.root, "deployed.json"), {})

    def home_requests(self, since=0):
        return [r for r in self.lab.requests()[since:] if r.get("path") == "/api/machine/home"]

    def deployed_line(self):
        for line in self.lab.state_text().splitlines():
            if line.startswith("- **Deployed on the machine:**"):
                return line
        self.fail("no Deployed line")

    def bad(self):
        p = os.path.join(self.lab.root, "bad.json")
        return il_deploy.read_json(p, {})

    def hold(self):
        return os.path.exists(os.path.join(self.lab.root, "hold.json"))

    def violations(self):
        return [r["violation"] for r in self.lab.requests() if "violation" in r]

    def snapshots(self, kind):
        d = os.path.join(self.lab.root, "snapshots")
        return sorted(n for n in os.listdir(d) if f"-{kind}-" in n)

    # --- tests ---------------------------------------------------------------------------------
    def test_first_deploy_with_openpnp_stopped(self):
        tag = self.release(5, self.lab.c, schema=4)
        self.deploy()
        self.assertEqual(self.lab.link("current"), tag)
        self.assertIsNone(self.lab.link("previous"))
        self.assertFalse(self.lab.service().is_active(), "was stopped before, must be stopped after")
        self.assertIn(tag, self.deployed_line())
        self.assertIn(self.lab.c[:7], self.deployed_line())
        self.assert_released()
        self.assertEqual(self.lab.schema(), 4)
        pre = self.snapshots("pre")
        self.assertEqual(len(pre), 1)
        snap = os.path.join(self.lab.root, "snapshots", pre[0], "instancelabs")
        self.assertEqual(sorted(os.listdir(snap)),
                         ["dispense-config.json", "dispense.db", "dispense.db-shm", "dispense.db-wal"])
        events = [r["event"] for r in self.lab.journal()]
        for e in ("claim", "snapshot", "switch", "start", "health", "deployed", "release"):
            self.assertIn(e, events)
        self.assertEqual(self.violations(), [])

    def test_deploy_over_running_openpnp_keeps_it_running(self):
        a = self.release(4, self.lab.b)
        self.deploy()
        self.lab.start_service()
        b = self.release(5, self.lab.c)
        self.deploy()
        self.assertEqual(self.lab.link("current"), b)
        self.assertEqual(self.lab.link("previous"), a)
        self.assertTrue(self.lab.service().is_active(), "was running before, must run after")
        self.assertIn(f"previous {a}", self.deployed_line())
        self.assertEqual(self.violations(), [])
        self.assertIn({"event": "sigterm"}, [{"event": r.get("event")} for r in self.lab.requests()])
        self.assert_released()

    def test_failed_health_rolls_back_automatically(self):
        a = self.release(4, self.lab.b)
        self.deploy()
        self.lab.start_service()
        b = self.release(5, self.lab.c, schema=5, fail=["enable"])
        self.deploy(rc=4)
        self.assertEqual(self.lab.link("current"), a)
        self.assertIsNone(self.lab.link("previous"))
        self.assertEqual(self.lab.schema(), 3, "the pre-deploy snapshot must be restored with the binary")
        abandoned = self.snapshots("abandoned")
        self.assertEqual(len(abandoned), 1)
        with open(os.path.join(self.lab.root, "snapshots", abandoned[0], "instancelabs",
                               "dispense-config.json")) as f:
            self.assertEqual(json.load(f)["schema"], 5)
        self.assertIn(b, self.bad())
        self.assertFalse(self.hold())
        self.assertTrue(self.lab.service().is_active())
        line = self.deployed_line()
        self.assertTrue(line.startswith(f"- **Deployed on the machine:** {a}"), line)
        self.assertIn(f"{b} failed its health check", line)
        self.assertRegex(line, r" since \d{4}-\d\d-\d\d \d\d:\d\d; at ")
        self.assert_released()
        self.assertIn("rolled back", self.lab.holder().value)
        self.assertEqual(self.violations(), [])

    def test_bad_list_and_forced_version(self):
        a = self.release(4, self.lab.b)
        self.deploy()
        self.lab.machine(fail_for={self.lab.c[:7]: ["enable"]})  # only build c fails
        b = self.release(5, self.lab.c)
        self.deploy(rc=4)
        self.assertIn(b, self.bad(), "the previous build passed the same check: the new build is bad")
        self.lab.machine()
        self.assertEqual(self.lab.main("run"), 0, self.lab.last_out)
        self.assertEqual(self.lab.link("current"), a)
        self.assertIn("nothing to deploy", self.lab.last_out)
        self.deploy("--version", b)
        self.assertEqual(self.lab.link("current"), b)
        self.assertNotIn(b, self.bad())

    def test_machine_failure_at_the_same_step_is_not_the_builds(self):
        a = self.release(4, self.lab.b)
        self.deploy()
        self.lab.machine(fail=["enable"])  # e.g. a hung controller: every build fails to start it
        b = self.release(5, self.lab.c)
        self.deploy(rc=5)
        self.assertNotIn(b, self.bad())
        self.assertEqual(self.lab.link("current"), a)
        self.assert_held()
        self.lab.machine()
        self.assertEqual(self.lab.main("resume"), 0, self.lab.last_out)
        self.assert_released_by("resume")
        self.assertEqual(self.lab.main("run"), 0, self.lab.last_out)
        self.assertEqual(self.lab.link("current"), b)

    def test_failed_home_holds_and_never_homes_again(self):
        a = self.release(4, self.lab.b)
        self.deploy()
        self.lab.start_service()
        self.lab.machine(fail=["home"])  # e.g. the homing fiducial covered, an obstruction
        b = self.release(5, self.lab.c, schema=5)
        seen = len(self.lab.requests())
        self.deploy(rc=5)
        self.assertEqual(len(self.home_requests(seen)), 1, "no second home without a person")
        self.assertNotIn(b, self.bad())
        self.assertEqual(self.lab.link("current"), a)
        self.assertEqual(self.lab.schema(), 3)
        self.assertTrue(self.lab.service().is_active(), "it ran before: the previous build runs again, unchecked")
        self.assertTrue(self.deployed_line().startswith(f"- **Deployed on the machine:** {a}"), self.deployed_line())
        self.assertIn("not checked", self.deployed_line())
        self.assert_held()
        self.assertEqual(self.violations(), [])

    def test_engine_that_did_not_start_rolls_back(self):
        a = self.release(4, self.lab.b)
        self.deploy()
        b = self.release(5, self.lab.c, fail=["engine"])
        self.assertEqual(self.lab.main("deploy", extra=("--status-timeout", "1")), 4, self.lab.last_out)
        self.assertEqual(self.lab.link("current"), a)
        self.assertIn(b, self.bad())
        self.assertIn("dispense", self.bad()[b]["reason"])

    def test_engine_starting_after_the_api_is_not_a_failure(self):
        """The real OpenPnP can answer /api/status before its dispense engine runs (e2e): a deploy waits for it."""
        self.lab.machine(engine_start_s=0.5)
        a = self.release(4, self.lab.b)
        self.deploy()
        self.assertEqual(self.lab.link("current"), a)
        self.assertEqual(self.bad(), {})

    def test_rollback_command(self):
        a = self.release(4, self.lab.b)
        self.deploy()
        b = self.release(5, self.lab.c, schema=5)
        self.deploy()
        self.assertEqual(self.lab.schema(), 5)
        self.assertEqual(self.lab.main("rollback"), 0, self.lab.last_out + self.lab.last_err)
        self.assertEqual(self.lab.link("current"), a)
        self.assertIsNone(self.lab.link("previous"))
        self.assertEqual(self.lab.schema(), 3)
        self.assertEqual(len(self.snapshots("abandoned")), 1)
        self.assertIn(b, self.bad())
        self.assertIn(f"rolled back from {b}", self.deployed_line())
        self.assertFalse(self.lab.service().is_active())
        self.assert_released()
        # one step only, and the timer does not undo it
        self.assertEqual(self.lab.main("rollback"), 2)
        self.assertIn("no previous", self.lab.last_out)
        self.assertEqual(self.lab.main("run"), 0)
        self.assertEqual(self.lab.link("current"), a)

    def test_not_idle_exits_3_and_changes_nothing(self):
        self.release(5, self.lab.c)
        il_deploy.edit_state_file(self.lab.state_md,
                                  lambda t: il_deploy.replace_entry(t, "Holder", "Process role, since 19:00"))
        before = self.lab.state_text()
        self.deploy(rc=3)
        self.assertIn("not idle: ", self.lab.last_out)
        self.assertIn("Process role", self.lab.last_out)
        self.assertEqual(self.lab.state_text(), before)
        self.assertIsNone(self.lab.link("current"))

    def test_not_idle_at_recheck_restores_holder(self):
        self.release(5, self.lab.c)
        before = self.lab.state_text()
        with mock.patch.object(il_deploy.Deployer, "idle_reasons", side_effect=[[], ["a bench tool started"]]):
            self.deploy(rc=3)
        self.assertEqual(self.lab.state_text(), before)
        self.assertIsNone(self.lab.link("current"))

    def test_lock_taken_at_start_is_a_machine_problem(self):
        a = self.release(4, self.lab.b)
        self.deploy()
        self.release(5, self.lab.c)
        held = []
        real_start = il_deploy.SubprocessService.start

        def start_after_bench_tool_grabs_lock(svc):
            if not held:
                f = open(self.lab.lock, "w")
                fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
                held.append(f)
            return real_start(svc)

        with mock.patch.object(il_deploy.SubprocessService, "start", start_after_bench_tool_grabs_lock):
            self.deploy(rc=5)
        held[0].close()
        self.assertEqual(self.bad(), {})
        self.assertEqual(self.lab.link("current"), a)
        self.assert_held()

    def test_unexpected_exception_after_claim(self):
        self.release(4, self.lab.b)
        self.deploy()
        self.lab.start_service()
        self.release(5, self.lab.c)
        with mock.patch.object(il_deploy.Deployer, "snapshot", side_effect=OSError("disk full")):
            self.deploy(rc=1)
        self.assert_held("disk full", "nothing to stop")  # stop_service had stopped it before the snapshot
        self.assertTrue(any(r["event"] == "exception" for r in self.lab.journal()))
        self.assertTrue(any(r.get("path") == "/api/stop" for r in self.lab.requests()))
        self.assertEqual(self.violations(), [])
        # the hold latch blocks the next run
        self.assertEqual(self.lab.main("run"), 3)
        self.assertIn("hold", self.lab.last_out)

    def test_reset_controller_rollback_fails_and_holds(self):
        self.release(4, self.lab.b)
        self.deploy()
        self.lab.machine(fail=["reset"])
        b = self.release(5, self.lab.c)
        seen = len(self.lab.requests())
        self.deploy(rc=5)
        self.assertNotIn(b, self.bad(), "a reset controller is not the build's fault")
        self.assertTrue(self.hold())
        self.assertFalse(self.lab.service().is_active())
        self.assertFalse(any(r.get("path") == "/api/machine/home" for r in self.lab.requests()[seen:]))
        self.assert_held()

    def test_first_deploy_failure_leaves_nothing_running(self):
        tag = self.release(5, self.lab.c, fail=["enable"], schema=9)
        self.deploy(rc=4)
        self.assertIn(tag, self.bad())
        self.assertFalse(self.hold())
        self.assertFalse(self.lab.service().is_active())
        self.assertIsNone(self.lab.link("current"))
        self.assertEqual(self.lab.schema(), 3)
        self.assert_released()

    def test_home_that_never_ends_leaves_openpnp_alone(self):
        self.release(4, self.lab.b)
        self.deploy()
        self.lab.machine(home_s=3.0)
        self.release(5, self.lab.c)
        b = f"il-build-5-{self.lab.c[:7]}"
        rc = self.lab.main("deploy", extra=["--home-timeout", "0.3", "--home-busy-wait", "0.5"])
        self.assertEqual(rc, 5, self.lab.last_out)
        self.assertTrue(self.lab.service().is_active(), "never stop OpenPnP while it may be homing")
        self.assertEqual(self.violations(), [])
        self.assert_held()
        self.assertEqual(self.deployed()["current"]["tag"], b, "rollback must find the unverified build")
        self.assertIn(f"{b} installed but NOT verified", self.deployed_line())

    def test_status_command(self):
        a = self.release(4, self.lab.b)
        self.deploy()
        self.assertEqual(self.lab.main("status"), 0)
        st = json.loads(self.lab.last_out)
        self.assertEqual(st["current"]["tag"], a)
        self.assertTrue(st["idle"], st["reasons"])
        self.assertEqual(st["last_deploy"]["event"], "result")
        self.assertEqual(st["last_deploy"]["rc"], 0)
        self.assertFalse(st["service_active"])

    def test_run_polls_and_deploys(self):
        self.gh.add_release(5, self.lab.c)
        self.assertEqual(self.lab.main("run"), 0, self.lab.last_out + self.lab.last_err)
        self.assertEqual(self.lab.link("current"), f"il-build-5-{self.lab.c[:7]}")
        self.assertEqual(self.lab.main("run"), 0)
        self.assertIn("nothing to deploy", self.lab.last_out)

    def test_deploy_reverifies_on_github(self):
        tag = self.release(5, self.lab.c)
        # il/integration was rewritten under the staged build: it is no longer an ancestor of the head
        self.gh.head = self.gh.commit("rewritten", self.lab.a)
        self.deploy(rc=2)
        self.assertIn("scratch", self.lab.last_out)
        self.assertIsNone(self.lab.link("current"))
        self.assertNotIn(tag, self.bad())

    # --- interrupted deploys (SIGTERM: systemctl stop, a reboot) --------------------------------
    def test_interrupted_health_check_leaves_an_unverified_build_that_rollback_undoes(self):
        a = self.release(4, self.lab.b)
        self.deploy()
        b = self.release(5, self.lab.c, schema=5)
        with mock.patch.object(il_deploy.Deployer, "health_check", side_effect=il_deploy.Terminated("signal 15")):
            self.deploy(rc=1)
        self.assertEqual(self.lab.link("current"), b)
        self.assertIn(f"{b} installed but NOT verified", self.deployed_line())
        self.assertEqual(self.deployed()["current"]["tag"], b)
        self.assert_held("Terminated")
        self.assertEqual(self.lab.main("resume"), 0, self.lab.last_out)
        self.assert_released_by("resume")
        # the timer does not settle on an unchecked build (refused: the unit fails visibly), and an older build is
        # not run on its config
        self.assertEqual(self.lab.main("run"), 2, self.lab.last_out)
        self.assertIn("not verified", self.lab.last_out)
        self.assertEqual(self.lab.main("deploy", "--version", a), 2, self.lab.last_out)
        self.assertIn("rollback", self.lab.last_out)
        # no baseline to compare the config with: say so, and go back only when told to
        self.assertEqual(self.lab.main("rollback"), 2, self.lab.last_out)
        self.assertIn("--discard-config-changes", self.lab.last_out)
        self.assertEqual(self.lab.main("rollback", extra=["--discard-config-changes"]), 0,
                         self.lab.last_out + self.lab.last_err)
        self.assertEqual(self.lab.link("current"), a)
        self.assertEqual(self.lab.schema(), 3, "the pre-deploy snapshot comes back with the binary")
        self.assert_released()

    def test_health_command_verifies_an_unverified_build(self):
        self.release(4, self.lab.b)
        self.deploy()
        b = self.release(5, self.lab.c)
        with mock.patch.object(il_deploy.Deployer, "health_check", side_effect=il_deploy.Terminated("signal 15")):
            self.deploy(rc=1)
        self.assertEqual(self.lab.main("resume"), 0)
        self.assertEqual(self.lab.main("health"), 0, self.lab.last_out + self.lab.last_err)
        self.assertNotIn("pending", self.deployed()["current"])
        self.assertTrue(self.deployed_line().startswith(f"- **Deployed on the machine:** {b}"), self.deployed_line())
        self.assertNotIn("NOT verified", self.deployed_line())

    def test_new_build_left_alone_after_its_failure_can_be_rolled_back(self):
        a = self.release(4, self.lab.b)
        self.deploy()
        b = self.release(5, self.lab.c, fail=["enable"])
        with mock.patch.object(il_deploy.Deployer, "stop_after_failure", return_value=False):
            self.deploy(rc=5)
        self.assertEqual(self.lab.link("current"), b)
        self.assertIn(f"{b} installed but NOT verified", self.deployed_line())
        self.assertNotIn(b, self.bad(), "not judged: the previous build was never tried")
        self.assert_held()
        self.assertEqual(self.lab.main("resume"), 0)
        self.assertEqual(self.lab.main("rollback", extra=["--discard-config-changes"]), 0,
                         self.lab.last_out + self.lab.last_err)
        self.assertEqual(self.lab.link("current"), a)

    def terminated_during_home(self, home_s, *extra):
        self.release(4, self.lab.b)
        self.deploy()
        self.lab.machine(home_s=home_s)
        self.release(5, self.lab.c)
        real_post = il_deploy.Api.post

        def post(api, path, body=None, timeout=10):
            if path == "/api/machine/home":
                real_post(api, path, {"wait": False}, timeout)  # the home runs on
                raise il_deploy.Terminated("signal 15")
            return real_post(api, path, body, timeout)
        with mock.patch.object(il_deploy.Api, "post", post):
            rc = self.lab.main("deploy", extra=extra)
        self.assertEqual(rc, 1, self.lab.last_out + self.lab.last_err)
        self.assertEqual(self.violations(), [], "never a STOP during a home")
        return [r for r in self.lab.journal() if r["event"] == "stop"][-1]

    def test_terminated_during_a_home_stops_after_it(self):
        stop = self.terminated_during_home(1.0)
        self.assertTrue(stop["ok"], stop)
        self.assert_held("machine stopped through /api/stop")

    def test_terminated_during_a_long_home_sends_no_stop_and_says_so(self):
        stop = self.terminated_during_home(3.0, "--home-busy-wait", "0.5")
        self.assertFalse(stop["ok"], stop)
        self.assert_held("STOP not sent")
        self.assertNotIn("machine stopped", self.lab.holder().value)
        self.assertTrue(self.lab.service().is_active(), "not killed mid-home")

    def test_terminated_right_after_the_claim(self):
        self.release(5, self.lab.c)
        real = il_deploy.claim_holder

        def claim_then_sigterm(*a, **kw):
            prev = real(*a, **kw)
            il_deploy._terminate(signal.SIGTERM, None)  # the handler runs here, before the try block
            return prev
        try:
            with mock.patch.object(il_deploy, "claim_holder", claim_then_sigterm):
                rc = self.lab.main("deploy")
        except il_deploy.Terminated:
            rc = "escaped"
        self.assertEqual(rc, 1)
        self.assertIsNone(self.lab.link("current"))
        self.assert_held("Terminated")

    def test_failed_release_is_loud_and_holds(self):
        self.release(5, self.lab.c)
        with mock.patch.object(il_deploy, "release_holder", side_effect=OSError("read-only file system")):
            rc = self.lab.main("deploy")
        self.assertNotEqual(rc, 0)
        self.assertIn("Holder", self.lab.last_err)
        self.assertIn("read-only file system", self.lab.last_err)
        self.assertTrue(self.hold())

    # --- the Holder's text ---------------------------------------------------------------------
    def test_previous_holder_text_survives_a_deploy(self):
        il_deploy.edit_state_file(self.lab.state_md, lambda t: il_deploy.replace_entry(
            t, "Holder", "nobody (released by Process at 18:00,\n  valve off, needle parked)"))
        self.release(5, self.lab.c)
        self.deploy()
        self.assert_released()
        self.assertIn("earlier: (released by Process at 18:00, valve off, needle parked)", self.lab.holder().value)

    def test_not_idle_at_recheck_restores_a_multiline_holder_exactly(self):
        il_deploy.edit_state_file(self.lab.state_md, lambda t: il_deploy.replace_entry(
            t, "Holder", "nobody (released by Process at 18:00,\n  valve off, needle parked)"))
        self.release(5, self.lab.c)
        before = self.lab.state_text()
        with mock.patch.object(il_deploy.Deployer, "idle_reasons", side_effect=[[], ["a bench tool started"]]):
            self.deploy(rc=3)
        self.assertEqual(self.lab.state_text(), before)

    # --- rollback and config changes since the deploy -----------------------------------------
    def test_rollback_refuses_to_drop_config_changes_unless_told(self):
        self.release(4, self.lab.b)
        self.deploy()
        b = self.release(5, self.lab.c, schema=5)
        self.deploy()
        with open(os.path.join(self.lab.config, "machine.xml"), "a") as f:
            f.write("<!-- N2 head offset recalibrated -->\n")
        before = self.lab.state_text()
        self.assertEqual(self.lab.main("rollback"), 2, self.lab.last_out)
        self.assertIn("machine.xml", self.lab.last_out)
        self.assertIn("--discard-config-changes", self.lab.last_out)
        self.assertEqual(self.lab.link("current"), b)
        self.assertEqual(self.lab.state_text(), before)
        self.assertEqual(self.lab.main("rollback", extra=["--discard-config-changes"]), 0,
                         self.lab.last_out + self.lab.last_err)
        self.assertEqual(self.lab.schema(), 3)
        line = self.deployed_line()
        self.assertIn("machine.xml", line)
        abandoned = self.snapshots("abandoned")
        self.assertEqual(len(abandoned), 1)
        self.assertIn(abandoned[0], line)
        self.assertIn("machine.xml", self.lab.holder().value)
        # discarded work is never pruned automatically
        for i in range(15):
            os.makedirs(os.path.join(self.lab.root, "snapshots", f"29990101-0000{i:02d}-pre-x"))
        cfg = il_deploy.config_from_args(self.lab.argv("status"))
        il_deploy.Deployer(cfg, self.lab.deps()).prune()
        self.assertEqual(self.snapshots("abandoned"), abandoned)

    def test_older_version_is_refused(self):
        a = self.release(4, self.lab.b)
        self.deploy()
        b = self.release(5, self.lab.c)
        self.deploy()
        self.assertEqual(self.lab.main("deploy", "--version", a), 2)
        self.assertIn("rollback", self.lab.last_out)
        self.assertEqual(self.lab.link("current"), b)

    # --- the staged folder is checked against GitHub at deploy time ---------------------------
    def test_hand_placed_release_is_refused(self):
        tag = self.release(5, self.lab.c)
        rel = os.path.join(self.lab.root, "releases")
        fake = f"il-build-999-{self.lab.c[:7]}"
        shutil.copytree(os.path.join(rel, tag), os.path.join(rel, fake))
        jar = json.dumps({"sha": self.lab.c, "built": "by hand"}).encode()
        with open(os.path.join(rel, fake, "openpnp-il.jar"), "wb") as f:
            f.write(jar)
        with open(os.path.join(rel, fake, "VERSION.txt"), "w") as f:
            f.write(fake + "\n")
        info = il_deploy.read_json(os.path.join(rel, tag, "BUILD-INFO.json"))
        info.update(version=fake, jar_sha256=hashlib.sha256(jar).hexdigest())
        with open(os.path.join(rel, fake, "BUILD-INFO.json"), "w") as f:
            json.dump(info, f)
        self.assertEqual(self.lab.main("deploy"), 2, self.lab.last_out)
        self.assertIn("GitHub", self.lab.last_out)
        self.assertIsNone(self.lab.link("current"))

    def test_staged_jar_swapped_by_hand_is_refused(self):
        tag = self.release(5, self.lab.c)
        d = os.path.join(self.lab.root, "releases", tag)
        jar = json.dumps({"sha": self.lab.c, "built": "dirty tree"}).encode()
        with open(os.path.join(d, "openpnp-il.jar"), "wb") as f:
            f.write(jar)
        info = il_deploy.read_json(os.path.join(d, "BUILD-INFO.json"))
        info["jar_sha256"] = hashlib.sha256(jar).hexdigest()
        with open(os.path.join(d, "BUILD-INFO.json"), "w") as f:
            json.dump(info, f)
        self.assertEqual(self.lab.main("deploy"), 2, self.lab.last_out)
        self.assertIn("jar", self.lab.last_out)
        self.assertIsNone(self.lab.link("current"))


if __name__ == "__main__":
    unittest.main()
