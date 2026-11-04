"""keep-up: the real machine's web UI is up whenever nobody holds the machine (the user's P0, 11-03). It starts
OpenPnP, never enabled or homed, when it is down and the machine is free, and leaves it down otherwise."""
import fcntl
import os
import re
import signal
import sys
import time
import unittest

# il_deploy is one level up: also when unittest imports this module by bare name (no -t)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import il_deploy  # noqa: E402
from fakes import Lab  # noqa: E402


class KeepUp(unittest.TestCase):
    def setUp(self):
        self.lab = Lab()
        self.assertEqual(self.lab.main("init", "--seed-from", self.lab.seed), 0, self.lab.last_out)
        self.lab.gh.add_release(5, self.lab.c, jar={"sha": self.lab.c})
        self.assertEqual(self.lab.main("poll"), 0, self.lab.last_out + self.lab.last_err)
        self.assertEqual(self.lab.main("deploy"), 0, self.lab.last_out + self.lab.last_err)
        self.assertFalse(self.lab.service().is_active())  # the deploy left it as it found it: stopped
        self.n0 = len(self.lab.requests())

    def tearDown(self):
        svc = self.lab.service()
        if svc.is_active():
            svc.stop()
        self.lab.cleanup()

    def keep_up(self, rc):
        got = self.lab.main("keep-up")
        self.assertEqual(got, rc, self.lab.last_out + self.lab.last_err)
        return self.lab.last_out

    def since(self):
        return [(r.get("method"), r.get("path")) for r in self.lab.requests()[self.n0:] if "path" in r]

    def set_holder(self, value):
        text, n = re.subn(r"(?m)^- \*\*Holder:\*\* .*$", lambda m: "- **Holder:** " + value, self.lab.state_text())
        self.assertEqual(n, 1)
        with open(self.lab.state_md, "w") as f:
            f.write(text)

    def test_down_with_the_machine_free_it_starts_never_enabled_or_homed(self):
        out = self.keep_up(0)
        self.assertTrue(self.lab.service().is_active())
        self.assertIn("OpenPnP runs, machine not enabled", out)
        self.assertIn(("GET", "/api/status"), self.since())
        self.assertEqual([p for p in self.since() if p[0] != "GET"], [])  # no enable, no home, no STOP
        h = self.lab.holder()
        self.assertTrue(h.idle and h.value.startswith("nobody"), h.value)  # it claims nothing

    def test_running_it_leaves_it_alone(self):
        self.keep_up(0)
        pids = self.lab.service().pids()
        self.keep_up(0)
        self.assertEqual(self.lab.service().pids(), pids)

    def test_a_holder_keeps_it_stopped(self):
        """A bench session: the Holder stopped OpenPnP to drive the serial port itself."""
        self.set_holder("Process `mcp:c07b84cf` (from 14:24: bench session)")
        out = self.keep_up(3)
        self.assertFalse(self.lab.service().is_active())
        self.assertIn("STATE.md Holder: Process", out)
        self.assertEqual(self.since(), [])

    def test_a_hold_keeps_it_stopped(self):
        il_deploy.Deployer(il_deploy.config_from_args(self.lab.argv("status")), self.lab.deps()).set_hold(
            "a health check failed: a person must check the machine")
        out = self.keep_up(3)
        self.assertFalse(self.lab.service().is_active())
        self.assertIn("on hold since", out)

    def test_a_taken_serial_lock_keeps_it_stopped(self):
        fd = os.open(self.lab.lock, os.O_RDWR | os.O_CREAT, 0o666)
        self.addCleanup(os.close, fd)
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)  # a bench tool on the port
        out = self.keep_up(3)
        self.assertFalse(self.lab.service().is_active())
        self.assertIn(f"serial lock {self.lab.lock} is held by", out)

    def test_an_exit_il_deploy_did_not_cause_sets_the_hold_and_is_never_restarted(self):
        """A crash leaves the valve and pump as they were, and nothing sent a STOP: a person must check first."""
        self.keep_up(0)
        svc = self.lab.service()
        for pid in svc.pids():
            os.kill(pid, signal.SIGKILL)
        end = time.monotonic() + 10
        while svc.is_active() and time.monotonic() < end:
            time.sleep(0.05)
        self.assertFalse(svc.is_active())
        self.assertEqual(self.lab.main("keep-up"), 5, self.lab.last_out)
        self.assertIn("OpenPnP ended without il_deploy stopping it", self.lab.last_out)
        self.assertFalse(svc.is_active())
        out = self.keep_up(3)
        self.assertIn("on hold since", out)
        self.assertFalse(self.lab.service().is_active())


if __name__ == "__main__":
    unittest.main()
