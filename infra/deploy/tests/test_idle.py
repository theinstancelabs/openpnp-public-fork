"""Idle checks: Holder, serial flock, device openers, quiet period, OpenPnP's API, hold latch."""
import fcntl
import os
import subprocess
import sys
import time
import unittest

# il_deploy is one level up: also when unittest imports this module by bare name (no -t)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import il_deploy  # noqa: E402
from fake_openpnp import FakeOpenPnP  # noqa: E402
from fakes import Lab  # noqa: E402


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

    def test_pressure_initial_value_is_not_activity(self):
        # PressureHold sets lastEventAtMs when OpenPnP starts ("pressure off"): not machine activity
        self.start_fake()
        self.assertEqual(self.reasons(), [])

    def test_recent_activity_in_openpnp(self):
        for key, word in (("last_run_ended_s_ago", "run"), ("run_finished_s_ago", "run"),
                          ("pad_cal_ended_s_ago", "pad calibration"), ("pressure_event_s_ago", "pressure")):
            with self.subTest(key=key, s_ago=60):
                self.start_fake(**{key: 60})
                try:
                    r = self.reasons()
                    self.assertTrue(any(word in x and "ago" in x for x in r), r)
                finally:
                    self.fake.shutdown()
            with self.subTest(key=key, s_ago=20 * 60):
                self.start_fake(**{key: 20 * 60})
                try:
                    self.assertEqual(self.reasons(), [])
                finally:
                    self.fake.shutdown()
                    self.fake = None

    def test_machine_switched_on_is_in_use(self):
        """Someone pressed Start (and Home) in the web UI and works at the machine by hand: no run, no pressure, a
        quiet serial.log, Holder still nobody. A deploy would turn the machine off under them and home it."""
        self.start_fake()
        self.fake.enable()
        r = self.reasons()
        self.assertTrue(any("switched on" in x for x in r), r)
        self.fake.stop()
        r = self.reasons()
        self.assertFalse(any("switched on" in x for x in r), r)
        # pressing Start connected the controller (dispense.profile.controllerConnectedAtMs): a person was there
        self.assertTrue(any("controller connected" in x and "ago" in x for x in r), r)

    def test_a_home_by_someone_is_activity(self):
        self.start_fake()
        self.fake.enable()
        self.fake.home({"wait": True})
        self.fake.stop()
        r = self.reasons()
        self.assertTrue(any("a home" in x and "ago" in x for x in r), r)

    def test_the_deployers_own_home_is_not_activity(self):
        """The health check's own home sets homeAtMs too: a rollback or the next deploy right after it is not held."""
        self.start_fake()
        os.makedirs(os.path.join(self.lab.dir, "openpnp-il"), exist_ok=True)
        cfg = il_deploy.config_from_args(self.lab.argv("status", extra=["--usb-sn", ""]))
        rep = il_deploy.Deployer(cfg, self.lab.deps(service=self.svc)).health_check()
        self.assertTrue(rep["ok"], rep)
        self.assertIsNotNone(self.fake.home_at_ms)
        self.assertEqual(self.reasons(), [])

    def test_tip_place_and_needle_edits_are_activity(self):
        for key, word in (("needle_changed_s_ago", "needle change"), ("needle_set_s_ago", "needle touch-off"),
                          ("landing_set_s_ago", "needle landing"), ("tip_mounted_s_ago", "tip mounted"),
                          ("new_seat_s_ago", "new seat"), ("location_set_s_ago", "waste"),
                          ("surface_set_s_ago", "board"), ("purge_reset_s_ago", "purge row"),
                          ("start_angle_set_s_ago", "start angle"), ("xy_trim_set_s_ago", "XY trim"),
                          ("anchor_set_s_ago", "anchor"), ("controller_connected_s_ago", "controller connected")):
            for ago, busy in ((60, True), (20 * 60, False)):
                with self.subTest(key=key, s_ago=ago):
                    self.start_fake(**{key: ago})
                    try:
                        r = self.reasons()
                        if busy:
                            self.assertTrue(any(word in x and "ago" in x for x in r), r)
                        else:
                            self.assertEqual(r, [])
                    finally:
                        self.fake.shutdown()
                        self.fake = None

    def test_status_fields_il_deploy_reads(self):
        """The key paths il_deploy reads in /api/status (the e2e checks them against the real build: a renamed field
        would silently turn a check off, since a missing time is skipped)."""
        self.start_fake()
        st = il_deploy.Api(self.lab.port).get("/api/status")[1]
        self.assertEqual(il_deploy.status_field_problems(st), [])
        self.assertIn("machine.zCheck.homeAtMs", il_deploy.STATUS_FIELDS)
        del st["machine"]["zCheck"]["homeAtMs"]
        st["dispense"]["needle"]["landing"] = None
        st["dispense"].pop("purgeRow")
        self.assertEqual(il_deploy.status_field_problems(st),
                         ["machine.zCheck.homeAtMs", "dispense.needle.landing.setAtMs", "dispense.purgeRow.resetAtMs"])
        st = {"run": None, "lastRun": None, "padCalibration": None}  # null where null is allowed: nothing deeper read
        self.assertNotIn("run.finishedAtMs", il_deploy.status_field_problems(st))
        self.assertIn("machine.enabled", il_deploy.status_field_problems(st))

    def test_openpnp_log_is_an_activity_file(self):
        """OpenPnP logs each change through its API (ApiServer: 'POST ... -> 200'), the engine's work, its start and its
        stop: after a person's work and a stop of the service (the API is not asked then), the quiet period runs from
        its last line. What il_deploy's own windows wrote does not count."""
        clock = il_deploy.Clock()
        log = os.path.join(self.lab.config, "log", "OpenPnP.log")
        os.makedirs(os.path.dirname(log))
        with open(log, "w") as f:
            f.write("2026-11-01 00:00:00.000 ApiServer INFO: Instance Labs API POST /api/prime -> 200\n")
        r = self.reasons(clock=clock)
        self.assertTrue(any("OpenPnP.log" in x and "ago" in x for x in r), r)
        il_deploy.write_json(os.path.join(self.lab.root, "own-activity.json"), [[time.time() - 30, time.time()]])
        self.assertEqual(self.reasons(clock=clock), [], "written while il_deploy alone drove OpenPnP")
        old = time.time() - 20 * 60
        os.utime(log, (old, old))
        il_deploy.write_json(os.path.join(self.lab.root, "own-activity.json"), [])
        self.assertEqual(self.reasons(clock=clock), [])

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
