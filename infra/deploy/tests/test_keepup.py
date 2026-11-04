"""keep-up: the real machine's web UI is up whenever nobody holds the machine (the user's P0, 11-03). It starts
OpenPnP, never enabled or homed, when it is down and the machine is free, and leaves it down otherwise."""
import fcntl
import http.server
import os
import re
import signal
import sys
import threading
import time
import unittest
from unittest import mock

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


    # --- review round 1 (11-03 16:45) -------------------------------------------------------------
    def test_a_crash_between_two_state_reads_is_never_restarted(self):
        """main's exit check reads the unit still running (or stopping after a JVM crash); it is 'failed' a moment later
        when keep-up looks: a start would clear that state, and nothing would ever see the crash."""
        svc = StubService(active=[False], exits=[None, {"id": "4242@99", "detail": "il-openpnp.service failed (signal)"}])
        self.assertEqual(self.lab.main("keep-up", deps=self.lab.deps(service=svc)), 5, self.lab.last_out)
        self.assertIn("OpenPnP ended without il_deploy stopping it", self.lab.last_out)
        self.assertEqual(svc.starts, 0)
        self.assertTrue(il_deploy.read_json(os.path.join(self.lab.root, "hold.json")))

    def test_a_run_marker_from_before_a_reboot_sets_the_hold(self):
        """systemd forgets a 'failed' unit at a reboot (a freeze, a power cut, an unconfirmed STOP at shutdown): the
        marker mark-start wrote stands, so the run never had a confirmed STOP."""
        il_deploy.write_json(os.path.join(self.lab.root, il_deploy.RUN_MARKER), {"boot_id": "an-earlier-boot",
                                                                                "at": "2026-11-03T03:00:00-08:00"})
        self.assertEqual(self.lab.main("keep-up"), 5, self.lab.last_out)
        self.assertIn("down before the last reboot with no confirmed STOP", self.lab.last_out)
        self.assertFalse(self.lab.service().is_active())
        self.assertFalse(os.path.exists(os.path.join(self.lab.root, il_deploy.RUN_MARKER)))  # the hold carries it
        self.assertIn("on hold since", self.keep_up(3))

    def test_mark_start_refuses_a_taken_serial_lock_and_writes_this_boots_marker(self):
        fd = os.open(self.lab.lock, os.O_RDWR | os.O_CREAT, 0o666)
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.assertEqual(self.lab.main("mark-start"), 1, self.lab.last_out)
        self.assertFalse(os.path.exists(os.path.join(self.lab.root, il_deploy.RUN_MARKER)))
        os.close(fd)
        self.assertEqual(self.lab.main("mark-start"), 0, self.lab.last_out)
        m = il_deploy.read_json(os.path.join(self.lab.root, il_deploy.RUN_MARKER))
        self.assertEqual(m["boot_id"], il_deploy.boot_id())

    def test_a_confirmed_stop_clears_the_run_marker(self):
        self.keep_up(0)
        marker = os.path.join(self.lab.root, il_deploy.RUN_MARKER)
        il_deploy.write_json(marker, {"boot_id": il_deploy.boot_id(), "at": "now"})  # as the unit's mark-start
        self.assertEqual(self.lab.main("stop-openpnp"), 0, self.lab.last_out)
        self.assertFalse(os.path.exists(marker))

    def test_another_il_deploy_running_means_not_now(self):
        fd = os.open(os.path.join(self.lab.root, ".il-deploy.lock"), os.O_RDWR | os.O_CREAT, 0o644)
        self.addCleanup(os.close, fd)
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)  # a deploy or rollback in progress
        self.assertIn("another il_deploy is running", self.keep_up(3))
        self.assertFalse(self.lab.service().is_active())

    def test_something_else_on_the_api_port_keeps_it_stopped(self):
        srv = http.server.HTTPServer(("127.0.0.1", self.lab.port), http.server.BaseHTTPRequestHandler)
        self.addCleanup(srv.server_close)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.shutdown)
        self.assertIn(f"something answers on 127.0.0.1:{self.lab.port}", self.keep_up(3))
        self.assertFalse(self.lab.service().is_active())


    # --- review round 2 (11-03 17:55) -------------------------------------------------------------
    def marker(self, **kw):
        path = os.path.join(self.lab.root, il_deploy.RUN_MARKER)
        il_deploy.write_json(path, dict({"boot_id": il_deploy.boot_id(), "at": "2026-11-03T17:00:00-08:00"}, **kw))
        return path

    def test_a_running_unit_with_its_marker_is_no_exit(self):
        path = self.marker()
        svc = StubService(active=[True], exits=[None])
        self.assertEqual(self.lab.main("keep-up", deps=self.lab.deps(service=svc)), 0, self.lab.last_out)
        self.assertTrue(os.path.exists(path))
        self.assertFalse(il_deploy.read_json(os.path.join(self.lab.root, "hold.json")))

    def test_an_unreadable_unit_state_is_no_exit(self):
        """No user manager to ask (su without XDG_RUNTIME_DIR): never a hold on a unit nobody could read."""
        path = self.marker()
        svc = StubService(active=[False], exits=[None], down=False)
        il_deploy.Deployer(il_deploy.config_from_args(self.lab.argv("status")),
                           self.lab.deps(service=svc)).check_openpnp_exit()
        self.assertTrue(os.path.exists(path))
        self.assertFalse(il_deploy.read_json(os.path.join(self.lab.root, "hold.json")))

    def test_a_start_the_serial_lock_refused_drops_the_marker_without_a_hold(self):
        path = self.marker()
        svc = StubService(active=[False], exits=[None], status=il_deploy.LOCK_CONFLICT)
        d = il_deploy.Deployer(il_deploy.config_from_args(self.lab.argv("status")), self.lab.deps(service=svc))
        self.assertIsNone(d.check_openpnp_exit())
        self.assertFalse(os.path.exists(path))
        self.assertFalse(il_deploy.read_json(os.path.join(self.lab.root, "hold.json")))

    def test_an_exit_on_its_own_keeps_the_marker_through_exec_stop(self):
        """systemd runs ExecStop also after the JVM ended by itself with 143 (a kill from outside), with $MAINPID unset:
        'nothing to stop' then confirms nothing. The marker stays, stop-openpnp fails, keep-up sets the hold."""
        path = self.marker()  # OpenPnP is down (setUp's deploy left it stopped): its API does not answer
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("MAINPID", None)
            self.assertEqual(self.lab.main("stop-openpnp"), 1, self.lab.last_out)
        self.assertIn("no STOP confirmed", self.lab.last_out)
        self.assertTrue(os.path.exists(path))
        self.assertEqual(self.lab.main("keep-up"), 5, self.lab.last_out)
        self.assertFalse(self.lab.service().is_active())

    def test_a_stop_of_a_live_jvm_before_its_api_is_up_clears_the_marker(self):
        path = self.marker()
        with mock.patch.dict(os.environ, {"MAINPID": "4242"}):
            self.assertEqual(self.lab.main("stop-openpnp"), 0, self.lab.last_out)
        self.assertFalse(os.path.exists(path))

    def test_a_stop_that_is_not_confirmed_keeps_the_marker(self):
        self.keep_up(0)
        self.lab.machine(fail=["busy"])  # never idle: no STOP is sent (never inside a home)
        self.lab.service().stop()
        self.keep_up(0)  # the fake reads fake-machine.json at its start
        path = self.marker()
        with mock.patch.dict(os.environ, {"MAINPID": "4242"}):
            self.assertEqual(self.lab.main("stop-openpnp", extra=["--home-busy-wait", "1"]), 1, self.lab.last_out)
        self.assertTrue(os.path.exists(path))

    def test_nothing_deployed_yet_keeps_it_stopped(self):
        os.unlink(os.path.join(self.lab.root, "current"))
        self.assertIn("nothing deployed yet", self.keep_up(3))


class StubService:
    """systemd-shaped: is_active and last_exit answer in turn (the last answer repeats); start counts."""

    def __init__(self, active, exits, down=None, status=None):
        self.active, self.exits, self.starts, self.down, self.status = list(active), list(exits), 0, down, status

    def is_down(self):
        return (not self.active[0]) if self.down is None else self.down

    def exit_status(self):
        return self.status

    def _next(self, seq):
        return seq.pop(0) if len(seq) > 1 else seq[0]

    def is_active(self):
        return self._next(self.active)

    def last_exit(self):
        return self._next(self.exits)

    def start(self):
        self.starts += 1

    def stop(self):
        pass

    def pids(self):
        return set()


if __name__ == "__main__":
    unittest.main()
