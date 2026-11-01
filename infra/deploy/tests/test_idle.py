"""Idle checks: Holder, serial flock, device openers, quiet period, OpenPnP's API, hold latch."""
import fcntl
import os
import subprocess
import time
import unittest

import il_deploy
from fake_openpnp import FakeOpenPnP
from fakes import Lab


class FakeService:
    def __init__(self, active=False, pids=()):
        self.active = active
        self._pids = set(pids)

    def is_active(self):
        return self.active

    def pids(self):
        return set(self._pids)

    def exit_status(self):
        return None


class Idle(unittest.TestCase):
    def setUp(self):
        self.lab = Lab()
        self.svc = FakeService()
        self.fake = None

    def tearDown(self):
        if self.fake:
            self.fake.shutdown()
        self.lab.cleanup()

    def reasons(self, our_claim=None, **deps):
        cfg = il_deploy.config_from_args(self.lab.argv("status"))
        d = self.lab.deps(service=self.svc, **deps)
        return il_deploy.Deployer(cfg, d).idle_reasons(our_claim=our_claim)

    def start_fake(self, **machine):
        os.makedirs(self.lab.config, exist_ok=True)
        self.fake = FakeOpenPnP(self.lab.port, self.lab.dir, self.lab.config, machine=machine,
                                requests=self.lab.requests_path).start_thread()
        self.svc.active = True

    def test_all_clear(self):
        self.assertEqual(self.reasons(), [])

    def test_holder_held(self):
        il_deploy.edit_state_file(self.lab.state_md, lambda t: il_deploy.replace_entry(t, "Holder", "Process role"))
        r = self.reasons()
        self.assertEqual(len(r), 1)
        self.assertIn("Holder", r[0])

    def test_our_claim_counts_as_ours(self):
        claim = "Infra (deploy x) since 2026-10-31 20:15"
        il_deploy.claim_holder(self.lab.state_md, claim)
        self.assertEqual(self.reasons(our_claim=claim), [])
        self.assertEqual(len(self.reasons()), 1)

    def test_missing_state_md_is_held(self):
        os.unlink(self.lab.state_md)
        self.assertTrue(any("STATE.md" in r for r in self.reasons()))

    def test_lock_held_by_someone(self):
        with open(self.lab.lock, "w") as f:
            fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
            r = self.reasons()
        self.assertTrue(any("lock" in x and str(os.getpid()) in x for x in r), r)
        self.assertEqual(self.reasons(), [])

    def test_lock_held_by_the_service(self):
        p = subprocess.Popen(["/usr/bin/flock", "-n", self.lab.lock, "sleep", "30"])
        try:
            deadline = time.time() + 5
            while time.time() < deadline and not il_deploy.Procs().lock_holders(self.lab.lock):
                time.sleep(0.02)
            self.assertTrue(any("lock" in x for x in self.reasons()))
            self.svc._pids = {p.pid}
            self.assertEqual(self.reasons(), [])
        finally:
            p.kill()
            p.wait()

    def test_device_open_by_someone(self):
        with open(self.lab.device):
            r = self.reasons()
        self.assertTrue(any("open" in x and str(os.getpid()) in x for x in r), r)

    def test_device_open_by_the_service(self):
        with open(self.lab.device):
            self.svc._pids = {os.getpid()}
            self.assertEqual(self.reasons(), [])

    def test_device_symlink_resolved(self):
        link = os.path.join(self.lab.dir, "by-id-link")
        os.symlink(self.lab.device, link)
        argv = self.lab.argv("status")
        argv[argv.index("--device") + 1] = link
        cfg = il_deploy.config_from_args(argv)
        with open(self.lab.device):
            r = il_deploy.Deployer(cfg, self.lab.deps(service=self.svc)).idle_reasons()
        self.assertTrue(any("open" in x for x in r), r)

    def test_device_missing(self):
        os.unlink(self.lab.device)
        self.assertTrue(any("missing" in x for x in self.reasons()))

    def test_quiet_period(self):
        clock = il_deploy.Clock()
        now = time.time()
        os.utime(self.lab.activity, (now - 300, now - 300))
        r = self.reasons(clock=clock)
        self.assertTrue(any("serial.log" in x for x in r), r)
        os.utime(self.lab.activity, (now - 20 * 60, now - 20 * 60))
        self.assertEqual(self.reasons(clock=clock), [])
        os.utime(self.lab.activity, (now + 600, now + 600))
        self.assertTrue(any("serial.log" in x for x in self.reasons(clock=clock)))
        os.unlink(self.lab.activity)
        self.assertEqual(self.reasons(clock=clock), [])

    def test_api_idle(self):
        self.start_fake()
        self.assertEqual(self.reasons(), [])

    def test_api_busy_states(self):
        for fail, word in (("busy", "busy"), ("run", "run"), ("pressure", "pressure")):
            with self.subTest(fail=fail):
                self.start_fake(fail=[fail])
                r = self.reasons()
                self.assertTrue(any(word in x for x in r), r)
                self.fake.shutdown()
                self.fake = None

    def test_api_down_while_service_runs(self):
        self.svc.active = True
        self.assertTrue(any("API" in x for x in self.reasons()))

    def test_port_answered_without_the_service(self):
        self.start_fake()
        self.svc.active = False
        self.assertTrue(any(str(self.lab.port) in x for x in self.reasons()))

    def test_hold_latch(self):
        os.makedirs(self.lab.root, exist_ok=True)
        with open(os.path.join(self.lab.root, "hold.json"), "w") as f:
            f.write('{"reason": "controller was reset; a person must check the machine", "at": "x"}')
        self.assertTrue(any("hold" in x for x in self.reasons()))

    def test_other_real_openpnp(self):
        self.lab.procs.openpnp = [(4242, "/tmp/some/config", True)]
        r = self.reasons()
        self.assertTrue(any("4242" in x for x in r), r)
        self.lab.procs.openpnp = [(4243, "/tmp/sim/config", False)]
        self.assertEqual(self.reasons(), [])


class PadCalibrationAndImport(unittest.TestCase):
    def test_api_busy_reasons(self):
        base = {"machine": {"busy": False}, "run": None, "pressure": {"on": False}, "dispense": {"busy": False},
                "padCalibration": None, "boardImport": {"reading": False}}
        self.assertEqual(il_deploy.api_busy_reasons(base), [])
        for key, val in (("padCalibration", {"state": "running"}), ("padCalibration", {"state": "awaiting-reading"}),
                         ("boardImport", {"reading": True}), ("run", {"running": True}),
                         ("dispense", {"busy": True}), ("machine", {"busy": True}), ("machine", None)):
            with self.subTest(key=key, val=val):
                self.assertTrue(il_deploy.api_busy_reasons(dict(base, **{key: val})))
        self.assertEqual(il_deploy.api_busy_reasons(dict(base, padCalibration={"state": "done"})), [])
        self.assertEqual(il_deploy.api_busy_reasons(dict(base, run={"running": False})), [])


class ProcScan(unittest.TestCase):
    def test_openpnp_process_detection(self):
        cmd = ["/usr/bin/java", "-Xmx1g", "-DconfigDir=/x/cfg", "-jar", "openpnp-il.jar"]
        self.assertEqual(il_deploy.openpnp_config_dir(cmd, "/cwd", "/home/u"), "/x/cfg")
        cmd = ["java", "-javaagent:a.jar", "-cp", "/j/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar", "org.openpnp.Main"]
        self.assertEqual(il_deploy.openpnp_config_dir(cmd, "/cwd", "/home/u"), "/home/u/.openpnp2")
        cmd = ["java", "-DconfigDir=rel", "-jar", "/d/openpnp-il.jar"]
        self.assertEqual(il_deploy.openpnp_config_dir(cmd, "/cwd", "/home/u"), "/cwd/rel")
        for other in (["vim", "openpnp-il.jar"], ["java", "-jar", "other.jar"], ["python3", "org.openpnp.Main"]):
            self.assertIsNone(il_deploy.openpnp_config_dir(other, "/cwd", "/home/u"), other)

    def test_real_config_detection(self):
        lab = Lab()
        try:
            self.assertFalse(il_deploy.config_is_real(lab.seed))
            with open(os.path.join(lab.seed, "machine.xml"), "w") as f:
                f.write('<openpnp-machine><machine><drivers><driver communications="serial"/></drivers></machine>'
                        '</openpnp-machine>')
            self.assertTrue(il_deploy.config_is_real(lab.seed))
            self.assertTrue(il_deploy.config_is_real(os.path.join(lab.dir, "nonexistent")))
        finally:
            lab.cleanup()


if __name__ == "__main__":
    unittest.main()
