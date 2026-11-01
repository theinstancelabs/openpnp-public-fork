"""Full deploy / rollback / run flows into a temp root, with a fake OpenPnP started by the subprocess
service manager. Every exit path must release the Holder."""
import fcntl
import json
import os
import re
import unittest
from unittest import mock

import il_deploy
from fakes import Lab

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
        svc = self.lab.service()
        svc.start()
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
        self.lab.service().start()
        b = self.release(5, self.lab.c, schema=5, fail=["home"])
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
        self.lab.machine(fail=["home"])
        b = self.release(5, self.lab.c)
        self.deploy(rc=5)  # the machine-level failure fails the rollback's check too
        self.lab.machine()
        os.unlink(os.path.join(self.lab.root, "hold.json"))
        self.assertIn(b, self.bad())
        self.assertEqual(self.lab.main("run"), 0, self.lab.last_out)
        self.assertEqual(self.lab.link("current"), a)
        self.assertIn("nothing to deploy", self.lab.last_out)
        self.deploy("--version", b)
        self.assertEqual(self.lab.link("current"), b)
        self.assertNotIn(b, self.bad())

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
        self.assertTrue(self.hold())
        self.assertEqual(self.lab.link("current"), a)
        self.assert_released()

    def test_unexpected_exception_after_claim(self):
        self.release(4, self.lab.b)
        self.deploy()
        self.lab.service().start()
        self.release(5, self.lab.c)
        with mock.patch.object(il_deploy.Deployer, "snapshot", side_effect=OSError("disk full")):
            self.deploy(rc=1)
        h = self.lab.holder()
        self.assertTrue(h.idle, h.value)
        self.assertIn("disk full", h.value)
        self.assertTrue(self.hold())
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
        self.assertIn("a person must check", self.lab.holder().value)
        self.assert_released()

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
        rc = self.lab.main("deploy", extra=["--home-timeout", "0.3", "--home-busy-wait", "0.5"])
        self.assertEqual(rc, 5, self.lab.last_out)
        self.assertTrue(self.lab.service().is_active(), "never stop OpenPnP while it may be homing")
        self.assertTrue(self.hold())
        self.assertEqual(self.violations(), [])
        self.assert_released()

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


if __name__ == "__main__":
    unittest.main()
