"""The health check against a fake OpenPnP: each failure mode, and never a STOP during a home."""
import contextlib
import io
import json
import os
import sys
import time
import unittest
from unittest import mock

# il_deploy is one level up: also when unittest imports this module by bare name (no -t)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import il_deploy  # noqa: E402
from fake_openpnp import FakeOpenPnP  # noqa: E402
from fakes import Lab, sha_of  # noqa: E402

SHA = sha_of("c")


class HealthLab:
    """A fake OpenPnP and the health check against it (no tests of its own)."""

    def setUp(self):
        self.lab = Lab()
        self.rel = os.path.join(self.lab.dir, "release")
        os.makedirs(self.rel)
        os.makedirs(self.lab.config)
        self.fake = None

    def tearDown(self):
        if self.fake:
            self.fake.shutdown()
        self.lab.cleanup()

    def start(self, jar_fail=(), sha=SHA, trusted=(), **machine):
        with open(os.path.join(self.rel, "VERSION.txt"), "w") as f:
            f.write("il-build-5-" + SHA[:7] + "\n")
        with open(os.path.join(self.rel, "openpnp-il.jar"), "w") as f:
            json.dump({"sha": sha, "fail": list(jar_fail)}, f)
        self.fake = FakeOpenPnP(self.lab.port, self.rel, self.lab.config, machine=machine,
                                requests=self.lab.requests_path, trusted_origins=list(trusted)).start_thread()

    def check(self, *extra, expect=SHA[:7]):
        argv = self.lab.argv("status") + list(extra)
        cfg = il_deploy.config_from_args(argv)
        d = il_deploy.Deployer(cfg, self.lab.deps())
        return d.health_check(expect_sha7=expect)

    def paths(self):
        return [(r.get("method"), r.get("path")) for r in self.lab.requests() if "path" in r]

    def posts(self):
        return [p for p in self.paths() if p[0] == "POST"]

    def violations(self):
        return [r["violation"] for r in self.lab.requests() if "violation" in r]


class Health(HealthLab, unittest.TestCase):

    def test_healthy(self):
        self.start()
        rep = self.check()
        self.assertTrue(rep["ok"], rep)
        self.assertEqual([s["step"] for s in rep["steps"]],
                         ["status", "enable", "serial", "axes", "home", "homed", "stop"])
        self.assertIn(("POST", "/api/machine/home"), self.paths())
        self.assertEqual(self.posts()[-1], ("POST", "/api/stop/planned"), "BR-75: the screw's motor stays on")
        self.assertFalse(self.fake.enabled)
        self.assertEqual(self.violations(), [])
        self.assertEqual([r for r in self.lab.requests() if "screw" in r], [], "no M18 B")

    def test_healthy_on_a_build_before_br75(self):
        """A build without the planned stop (a rollback target): its STOP, as before."""
        self.start(jar_fail=["old-stop"])
        rep = self.check()
        self.assertTrue(rep["ok"], rep)
        self.assertEqual(self.posts()[-2:], [("POST", "/api/stop/planned"), ("POST", "/api/stop")])
        self.assertFalse(self.fake.enabled)

    def test_a_planned_stop_refused_after_the_home_is_the_machines(self):
        """Something works after the check's own home and keeps the planned stop refused: nothing sent here, a machine
        problem for the deploy to recover from (its STOP)."""
        self.start(planned_refusals=1000)
        rep = self.check("--stop-timeout", "0.5")
        self.assertFalse(rep["ok"])
        self.assertEqual((rep["kind"], rep["steps"][-1]["step"]), ("machine", "stop"), rep)
        self.assertIn("a G-code batch", rep["failure"])
        self.assertEqual(self.posts()[-1], ("POST", "/api/stop/planned"))
        self.assertNotIn(("POST", "/api/stop"), self.posts())

    def test_unanswered_lines_at_the_last_stop_are_the_machines(self):
        """The valve or pump off line got no ok: the outputs are not known to be off. A machine problem: nothing
        restarts and homes the previous build unattended, and the build is not marked bad for it."""
        self.start(jar_fail=["planned-lines-failed"])
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["kind"], "machine", rep)
        self.assertIn("M107 P3", rep["failure"])

    def test_unanswered_lines_at_the_enable_steps_stop_are_the_machines(self):
        self.start(jar_fail=["planned-lines-failed"])
        self.fake.enable()
        rep = self.check()
        self.assertEqual((rep["kind"], rep["failure"][:8]), ("machine", "enable: "), rep)
        self.assertIn("M107 P3", rep["failure"])

    def test_already_stopped_after_a_refused_stop_that_ran_is_the_machines(self):
        """#41 answers 409 when its task outlives the API's wait: its lines went out, their acks unknown. The retry
        finds the machine off and answers 200 'already stopped', sending nothing: the outputs are not known off."""
        self.start(jar_fail=["planned-slow"])
        rep = self.check("--stop-timeout", "1")
        self.assertFalse(rep["ok"])
        self.assertEqual((rep["kind"], rep["steps"][-1]["step"]), ("machine", "stop"), rep)
        self.assertIn("has not ended after 20 s", rep["failure"])
        self.assertNotIn(("POST", "/api/stop"), self.posts())

    def test_already_stopped_after_a_refused_stop_that_ran_at_the_enable_step_is_the_machines(self):
        self.start(jar_fail=["planned-slow"])
        self.fake.enable()
        rep = self.check("--stop-timeout", "1")
        self.assertFalse(rep["ok"])
        self.assertEqual((rep["kind"], rep["failure"][:8]), ("machine", "enable: "), rep)
        self.assertIn("has not ended after 20 s", rep["failure"])

    def planned_stop_times_out(self):
        """POST /api/stop/planned gets no answer in time (the lock guard's resync can hold a request ~90 s)."""
        post = il_deploy.Api.post

        def timed_out(api, path, body=None, timeout=10):
            if path == "/api/stop/planned":
                raise il_deploy.ApiTimeout(f"POST {path}: no answer within {timeout:g} s")
            return post(api, path, body, timeout)
        return mock.patch.object(il_deploy.Api, "post", timed_out)

    def test_a_planned_stop_that_timed_out_is_the_machines(self):
        """It may have run with its valve and pump off acks unknown: nothing restarts and homes unattended."""
        self.start()
        with self.planned_stop_times_out():
            rep = self.check()
        self.assertEqual((rep["kind"], rep["steps"][-1]["step"]), ("machine", "stop"), rep)
        self.assertIn("no answer within 60 s", rep["failure"])
        self.assertEqual(rep["failure"].count("POST /api/stop/planned"), 1, rep["failure"])

    def test_a_planned_stop_that_timed_out_at_the_enable_step_is_the_machines(self):
        self.start()
        self.fake.enable()
        with self.planned_stop_times_out():
            rep = self.check()
        self.assertEqual((rep["kind"], rep["failure"][:8]), ("machine", "enable: "), rep)

    def test_a_locked_answer_at_the_enable_step_is_proof_enough(self):
        self.start(jar_fail=["lock-at-planned-stop"])
        self.fake.enable()
        with mock.patch.object(il_deploy.Deployer, "_lock_taken", return_value=None):
            rep = self.check()
        self.assertEqual((rep["kind"], rep["failure"][:8]), ("claim-lost", "enable: "), rep)

    def test_a_locked_answer_is_proof_enough(self):
        """409 locked is someone else's session even if the status read after it fails."""
        self.start(jar_fail=["lock-at-planned-stop"])
        with mock.patch.object(il_deploy.Deployer, "_lock_taken", return_value=None):
            rep = self.check()
        self.assertEqual(rep["kind"], "claim-lost", rep)

    def test_a_lock_taken_at_the_planned_stop_is_left_alone(self):
        self.start(jar_fail=["lock-at-planned-stop"])
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["kind"], "claim-lost", rep)
        self.assertNotIn(("POST", "/api/stop"), self.posts())  # STOP would end the new holder's work
        json.dumps(rep)

    def test_already_enabled_is_stopped_first(self):
        self.start()
        self.fake.enable()
        rep = self.check()
        self.assertTrue(rep["ok"], rep)
        posts = self.posts()
        self.assertEqual(posts[0], ("POST", "/api/stop/planned"), "BR-75: planned, as every stop of the check's own")
        self.assertNotIn(("POST", "/api/stop"), posts)
        self.assertEqual([r for r in self.lab.requests() if "screw" in r], [], "no M18 B")

    def test_an_enabled_machine_whose_lock_is_taken_is_left_alone(self):
        """Someone starts the machine (and so holds the lock) just before the check reads it: their session, never
        the build's fault, no STOP."""
        self.start(jar_fail=["lock-at-planned-stop"])
        self.fake.enable()
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["kind"], "claim-lost", rep)
        self.assertTrue(rep["failure"].startswith("enable: "), rep["failure"])
        self.assertNotIn(("POST", "/api/stop"), self.posts())

    def test_link_kept_open_since_an_earlier_enable(self):
        """connection-keep-alive (the lab machine.xml): a STOP leaves the link open, so the next enable logs no
        'Serial port: connecting' line. The line from this OpenPnP's earlier connect (its own OpenPnP.log) counts."""
        self.start()
        self.fake.enable()
        self.fake.stop()
        rep = self.check()
        self.assertTrue(rep["ok"], rep)
        serial = [s for s in rep["steps"] if s["step"] == "serial"][0]["detail"]
        self.assertIn("S/N 387932683335", serial)
        self.assertIn("kept open", serial)

    def test_link_kept_open_to_another_device_fails(self):
        self.start(usb_sn="111111111111")
        self.fake.enable()
        self.fake.stop()
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["failure"].split(":")[0], "serial")

    def test_controller_power_cycled_behind_a_kept_alive_link(self):
        """The controller switched off and on while OpenPnP ran with its link kept open (a health check by hand later).
        BR-44: Start finds the link dead and connects again (GcodeDriver.setEnabled), so the connect line is new after
        the enable, and the controller reads Marlin's boot position: never homed, a machine problem."""
        self.start()
        self.fake.enable()
        self.fake.stop()
        self.fake.power_cycle()
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["kind"], "machine", rep)
        self.assertEqual([s["step"] for s in rep["steps"]], ["status", "enable", "serial", "axes"])
        serial = rep["steps"][2]["detail"]
        self.assertIn("S/N 387932683335", serial)
        self.assertNotIn("kept open", serial, "the enable connected again")
        self.assertTrue(rep["failure"].startswith("axes: controller was reset"), rep)
        self.assertNotIn(("POST", "/api/machine/home"), self.paths())
        self.assertEqual(self.posts()[-1], ("POST", "/api/stop"))

    def test_controller_power_cycled_behind_a_kept_alive_link_before_br44(self):
        """A build before BR-44 (a rollback can land on one): the enable fails on the dead port. No home."""
        self.start(jar_fail=["old-status"])
        self.fake.enable()
        self.fake.stop()
        self.fake.power_cycle()
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertTrue(rep["failure"].startswith("enable: HTTP 409"), rep)
        self.assertIn("IO Error", rep["failure"])
        self.assertNotIn(("POST", "/api/machine/home"), self.paths())

    def test_each_enable_connects_without_keep_alive(self):
        self.start(keep_alive=False)
        self.fake.enable()
        self.fake.stop()
        log = os.path.join(self.lab.config, "log", "OpenPnP.log")
        size = os.path.getsize(log)
        rep = self.check()
        self.assertTrue(rep["ok"], rep)
        self.assertGreater(os.path.getsize(log), size, "the health check's enable connected again")
        self.assertNotIn("kept open", [s for s in rep["steps"] if s["step"] == "serial"][0]["detail"])

    def test_version_mismatch(self):
        self.start(sha=sha_of("b"))
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["kind"], "build")
        self.assertIn(SHA[:7], rep["failure"])
        self.assertNotIn(("POST", "/api/machine/enable"), self.paths())

    def test_simulated_mismatch(self):
        self.start(simulated=True)
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertIn("simulated", rep["failure"])
        self.fake.shutdown()
        self.start(simulated=True)
        self.assertTrue(self.check("--expect-simulated")["ok"])

    def test_status_never_answers(self):
        argv = self.lab.argv("status")
        argv[argv.index("--status-timeout") + 1] = "0.5"
        cfg = il_deploy.config_from_args(argv)
        rep = il_deploy.Deployer(cfg, self.lab.deps()).health_check(expect_sha7=SHA[:7])
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["steps"][0]["step"], "status")

    def test_enable_fails(self):
        self.start(jar_fail=["enable"])
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertIn("enable", rep["failure"])
        self.assertNotIn(("POST", "/api/machine/home"), self.paths())
        self.assertEqual(self.posts()[-1], ("POST", "/api/stop"))

    def test_reset_signature_never_homes(self):
        self.start(fail=["reset"])
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["kind"], "machine")
        self.assertIn("controller was reset; a person must check the machine", rep["failure"])
        self.assertNotIn(("POST", "/api/machine/home"), self.paths())
        self.assertFalse(self.fake.enabled)

    def test_reset_signature_unit(self):
        axes = [{"letter": "X", "driverCoordinate": 0.0}, {"letter": "Y", "driverCoordinate": 0},
                {"letter": "Z", "driverCoordinate": 0.0}, {"letter": "B", "driverCoordinate": 720.0}]
        self.assertTrue(il_deploy.reset_signature(axes))
        axes[3]["driverCoordinate"] = 360.0
        self.assertFalse(il_deploy.reset_signature(axes))
        axes[3]["driverCoordinate"] = 720.0
        axes[0]["driverCoordinate"] = 0.5
        self.assertFalse(il_deploy.reset_signature(axes))

    def test_reset_signature_after_a_quick_stop(self):
        """Marlin's M410 (every STOP's DISABLE_COMMAND) recomputes the logical position from the step counts: at boot,
        3197 counts at 4.44 steps/deg read A and B 720.05 (lumen-sim RESEARCH.md, M410). A power-cycled controller that
        was STOPped once (the first health check's own STOP after it found the reset) still reads as reset: within a
        step or two of 720, never homed. Found by the deploy e2e's test_3 (a health check by hand homed it)."""
        def axes(b):
            return [{"letter": "X", "driverCoordinate": 0.0}, {"letter": "Y", "driverCoordinate": 0.0},
                    {"letter": "Z", "driverCoordinate": 0.0}, {"letter": "B", "driverCoordinate": b}]
        for b in (720.05, 719.82, 720.27):
            self.assertTrue(il_deploy.reset_signature(axes(b)), b)
        for b in (721.0, 719.0, 0.05):
            self.assertFalse(il_deploy.reset_signature(axes(b)), b)

    def test_reset_controller_stopped_once_is_still_never_homed(self):
        """The controller reset, the first check finds it and STOPs (M410: B now reads 720.05); a person runs the health
        check again (after `resume`): still the reset controller, still no home."""
        self.start(fail=["reset"])
        self.assertIn("controller was reset", self.check()["failure"])
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["kind"], "machine")
        self.assertEqual([s["step"] for s in rep["steps"]][-1], "axes")
        self.assertEqual(rep["steps"][-1]["detail"][:20], "controller was reset")
        self.assertNotIn(("POST", "/api/machine/home"), self.paths())

    def test_reset_signature_is_A_or_B_at_720(self):
        """BR-02's rule (INF-10): X, Y, Z at 0 with A or B at 720. Marlin boots both rotation axes at 720; a G92 B0 alone
        (what a dry run once did) leaves A at 720 and the controller still unhomed."""
        def axes(a, b):
            out = [{"letter": "X", "driverCoordinate": 0.0}, {"letter": "Y", "driverCoordinate": 0.0},
                   {"letter": "Z", "driverCoordinate": 0.0}, {"letter": "B", "driverCoordinate": b}]
            return out if a is None else out + [{"letter": "A", "driverCoordinate": a}]
        self.assertTrue(il_deploy.reset_signature(axes(720.0, 0.0)))
        self.assertTrue(il_deploy.reset_signature(axes(0.0, 720.0)))
        self.assertTrue(il_deploy.reset_signature(axes(720.0, 720.0)))
        self.assertTrue(il_deploy.reset_signature(axes(None, 720.0)))
        self.assertFalse(il_deploy.reset_signature(axes(0.0, 0.0)))
        self.assertFalse(il_deploy.reset_signature(axes(None, 0.0)))
        homed = axes(0.0, 0.0)
        homed[2]["driverCoordinate"] = 31.5  # Z after G28 (Z_AFTER_HOMING)
        homed[4]["driverCoordinate"] = 720.0
        self.assertFalse(il_deploy.reset_signature(homed), "a homed head is not a reset, whatever A reads")

    def test_controller_position_not_read_is_never_homed(self):
        """reset_signature needs B too: a power-cycled controller (X/Y/Z 0) whose B is not reported (its letter changed
        in machine.xml, no driverCoordinate) must not pass as 'not reset' and be homed."""
        cases = [dict(fail=["reset"], axes_missing=["B"]), dict(fail=["reset"], axes_null=["B"]),
                 dict(axes_missing=["X"]), dict(axes_null=["X"]), dict(axes_missing=["B"])]
        for machine in cases:
            with self.subTest(**machine):
                self.start(**machine)
                try:
                    rep = self.check()
                    self.assertFalse(rep["ok"], rep)
                    self.assertTrue(rep["failure"].startswith("axes: the controller position was not read"), rep)
                    self.assertNotIn(("POST", "/api/machine/home"), self.paths())
                    self.assertFalse(self.fake.enabled)
                finally:
                    self.fake.shutdown()
                    self.fake = None
                    with contextlib.suppress(FileNotFoundError):
                        os.unlink(self.lab.requests_path)

    def test_serial_match_pattern(self):
        """--serial-match: the connect line's pattern, for a link without a USB identity (lumen-sim's pty in the e2e);
        the default is ' S/N <--usb-sn>'."""
        self.start()
        rep = self.check("--usb-sn", "", "--serial-match", r"connecting to ttyACM0 by ")
        self.assertTrue(rep["ok"], rep)
        self.assertIn("serial", [s["step"] for s in rep["steps"]])
        self.fake.shutdown()
        self.start()
        rep = self.check("--usb-sn", "", "--serial-match", r"connecting to /dev/pts/7 by ")
        self.assertFalse(rep["ok"])
        self.assertTrue(rep["failure"].startswith("serial: "), rep)
        self.assertIn("/dev/pts/7", rep["failure"])

    def test_openpnp_gone_after_the_enable_is_the_machines(self):
        """The API going away after the enable (the JVM dies mid-home, a person stops the service) is not the build's:
        the controller may still be homing and the outputs keep their state; no second home by the previous build."""
        real_get, real_post = il_deploy.Api.get, il_deploy.Api.post
        for where in ("enable", "axes", "home", "homed"):
            with self.subTest(where=where):
                self.start()
                seen = {"machine_gets": 0, "homed": False}

                def get(api, path, timeout=10):
                    if path == "/api/machine":
                        seen["machine_gets"] += 1
                        # GET /api/machine: 1 before the enable, 2 the axes; after the home, the homed check
                        if (where == "axes" and seen["machine_gets"] == 2) or (where == "homed" and seen["homed"]):
                            raise il_deploy.ApiDown(f"GET {path}: Connection refused")
                    return real_get(api, path, timeout)

                def post(api, path, body=None, timeout=10):
                    if (where, path) in (("enable", "/api/machine/enable"), ("home", "/api/machine/home")):
                        real_post(api, path, {"wait": False} if where == "home" else body, timeout)
                        raise il_deploy.ApiDown(f"POST {path}: Connection reset by peer")
                    out = real_post(api, path, body, timeout)
                    seen["homed"] = seen["homed"] or path == "/api/machine/home"
                    return out
                try:
                    with mock.patch.object(il_deploy.Api, "get", get), mock.patch.object(il_deploy.Api, "post", post):
                        rep = self.check("--home-busy-wait", "0.5")
                    self.assertFalse(rep["ok"])
                    self.assertEqual(rep["kind"], "machine", rep)
                    self.assertTrue(rep["failure"].startswith(where + ": "), rep)
                finally:
                    self.fake.shutdown()
                    self.fake = None

    def test_serial_number_missing(self):
        self.start(jar_fail=["no-sn"])
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertIn("387932683335", rep["failure"])

    def test_serial_number_wrong(self):
        self.start(usb_sn="111111111111")
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertIn("387932683335", rep["failure"])

    def test_stale_serial_line_does_not_count(self):
        os.makedirs(os.path.join(self.lab.config, "log"), exist_ok=True)
        with open(os.path.join(self.lab.config, "log", "OpenPnP.log"), "w") as f:
            f.write("2026-10-31 13:59:10.848 SerialPortCommunications INFO: Serial port: connecting to ttyACM0 by USB "
                    "serial number: ttyACM0 (/dev/ttyACM0) \"MARLIN\" S/N 387932683335 USB 0483:5740\n")
        self.start(jar_fail=["no-sn"])
        self.assertFalse(self.check()["ok"])

    def test_no_serial_check_without_sn(self):
        self.start(jar_fail=["no-sn"])
        rep = self.check("--usb-sn", "")
        self.assertTrue(rep["ok"], rep)
        self.assertNotIn("serial", [s["step"] for s in rep["steps"]])

    def test_engine_starting_after_the_api_is_waited_for(self):
        """OpenPnP starts its web API and the dispense engine on configurationComplete, in no fixed order (its
        listeners are a HashSet), so /api/status can answer 200 before the engine runs (seen with the real build,
        e2e/test_deploy_openpnp.py). That is OpenPnP still starting, not a failed build."""
        self.start(engine_start_s=0.6)
        rep = self.check()
        self.assertTrue(rep["ok"], rep)
        self.assertGreaterEqual(len([p for p in self.paths() if p == ("GET", "/api/status")]), 2)

    def test_engine_that_never_starts_fails_at_the_status_timeout(self):
        """A build before BR-44 cannot tell a failed engine from one still starting: the 200 without it is waited out
        up to --status-timeout."""
        self.start(jar_fail=["engine", "old-status"])
        t = time.monotonic()
        rep = self.check("--status-timeout", "1")
        self.assertGreaterEqual(time.monotonic() - t, 1.0)
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["kind"], "build")
        self.assertIn("the dispense engine is not usable (dispense.running False, available False", rep["failure"])
        self.assertNotIn(("POST", "/api/machine/enable"), self.paths())

    def test_engine_that_failed_to_start_fails_at_once(self):
        """BR-44: dispense.state 'failed' (DispenseService.start threw; startError says what) is final: no wait for
        --status-timeout, and the reason names the error."""
        self.start(jar_fail=["engine"])
        t = time.monotonic()
        rep = self.check("--status-timeout", "60")
        self.assertLess(time.monotonic() - t, 10)
        self.assertFalse(rep["ok"])
        self.assertEqual((rep["kind"], rep["steps"][-1]["step"]), ("build", "status"))
        self.assertIn("the dispense engine failed to start", rep["failure"])
        self.assertIn("SQLITE_CORRUPT", rep["failure"])
        self.assertNotIn(("POST", "/api/machine/enable"), self.paths())

    def test_engine_still_starting_at_the_status_timeout_says_so(self):
        """dispense.state 'starting' is waited for, up to --status-timeout."""
        self.start(engine_start_s=600)
        t = time.monotonic()
        rep = self.check("--status-timeout", "1")
        self.assertGreaterEqual(time.monotonic() - t, 1.0)
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["kind"], "build")
        self.assertIn("did not start within 1 s (dispense.state starting)", rep["failure"])
        self.assertNotIn(("POST", "/api/machine/enable"), self.paths())

    def test_dispense_engine_must_run(self):
        for fail in ("engine", "unavailable"):
            with self.subTest(fail=fail):
                self.start(jar_fail=[fail])
                try:
                    rep = self.check("--status-timeout", "1")
                    self.assertFalse(rep["ok"])
                    self.assertEqual(rep["kind"], "build")
                    self.assertEqual(rep["steps"][-1]["step"], "status")
                    self.assertIn("dispense", rep["failure"])
                    self.assertNotIn(("POST", "/api/machine/enable"), self.paths())
                finally:
                    self.fake.shutdown()
                    self.fake = None

    def test_home_fails(self):
        self.start(jar_fail=["home"])
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertIn("hom", rep["failure"])
        self.assertEqual(rep["kind"], "machine", "a failed home is the machine's, not the build's")
        self.assertEqual(self.posts()[-1], ("POST", "/api/stop"))
        self.assertEqual(self.violations(), [])

    def test_stop_fails(self):
        self.start(jar_fail=["stop"])
        rep = self.check("--stop-timeout", "0.3")
        self.assertFalse(rep["ok"])
        self.assertIn("stop", rep["failure"])

    # The home waits (Deployer._wait_not_busy) on each /api/status shape a deploy can meet: BR-44's (machine.homing true
    # while a home runs) and a build before it, where a rollback can land: no machine.homing, so machine.busy alone
    # shows the home
    STATUS_SHAPES = (("BR-44", ()), ("before BR-44", ("old-status",)))

    @contextlib.contextmanager
    def status_shape(self, name, jar_fail, **machine):
        """A subtest against a fresh fake of that shape, with a request log of its own."""
        with self.subTest(status=name):
            self.start(jar_fail=jar_fail, **machine)
            try:
                yield
            finally:
                self.fake.shutdown()
                self.fake = None
                with contextlib.suppress(FileNotFoundError):
                    os.unlink(self.lab.requests_path)

    def test_slow_home_finishes_late_without_a_stop(self):
        for shape in self.STATUS_SHAPES:
            with self.status_shape(*shape, home_s=1.0):
                rep = self.check("--home-timeout", "0.3", "--home-busy-wait", "5")
                self.assertTrue(rep["ok"], rep)
                self.assertEqual(self.violations(), [])

    def test_home_that_never_ends_is_left_alone(self):
        for shape in self.STATUS_SHAPES:
            with self.status_shape(*shape, home_s=2.0):
                rep = self.check("--home-timeout", "0.3", "--home-busy-wait", "0.5")
                self.assertFalse(rep["ok"])
                self.assertEqual(rep["kind"], "home-stuck", rep)
                self.assertNotIn(("POST", "/api/stop"), self.paths())
                self.assertEqual(self.violations(), [])

    # --- the real server's own 5 min limit (MachineEndpoints.home: f.get(5, MINUTES), then 409 while the home runs on)
    def test_a_home_the_server_gave_up_on_is_waited_out_and_passes(self):
        for shape in self.STATUS_SHAPES:
            with self.status_shape(*shape, home_s=1.5, home_cap_s=0.3):
                rep = self.check("--home-timeout", "5", "--home-busy-wait", "5")
                self.assertTrue(rep["ok"], rep)
                self.assertIn("ended after", [s for s in rep["steps"] if s["step"] == "home"][0]["detail"])
                self.assertEqual(self.violations(), [])
                self.assertEqual(len([p for p in self.paths() if p == ("POST", "/api/machine/home")]), 1)

    def test_a_home_the_server_gave_up_on_that_never_ends_is_left_alone(self):
        for shape in self.STATUS_SHAPES:
            with self.status_shape(*shape, home_s=3.0, home_cap_s=0.3):
                rep = self.check("--home-timeout", "5", "--home-busy-wait", "0.5")
                self.assertFalse(rep["ok"])
                self.assertEqual(rep["kind"], "home-stuck", rep)
                self.assertNotIn(("POST", "/api/stop"), self.paths())
                self.assertEqual(self.violations(), [])

    def test_a_home_dropped_by_openpnp_is_the_machines(self):
        """The same 409 when OpenPnP dropped the home (a task of its own ahead of it failed): not busy, not homed."""
        self.start(home_s=0.6, home_cap_s=0.3, fail=["home"])
        rep = self.check("--home-timeout", "5", "--home-busy-wait", "5")
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["kind"], "machine", rep)
        self.assertEqual(self.violations(), [])

    def test_a_home_refused_before_it_starts_is_the_machines(self):
        """Refusals mean something else holds the machine (the pressure held, OpenPnP busy, an engine task): a person
        may be at it, so the previous build is not homed after it unwatched."""
        self.start(jar_fail=["pressure"])
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["kind"], "machine", rep)
        self.assertIn("pressure", rep["failure"])

    def test_no_home_endpoint_is_the_builds(self):
        self.start(jar_fail=["home-404"])
        rep = self.check()
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["kind"], "build", rep)

    def test_health_cli_prints_json(self):
        self.start()
        rc = self.lab.main("health", "--no-claim", "--sha7", SHA[:7])
        self.assertEqual(rc, 0, self.lab.last_out + self.lab.last_err)
        rep = json.loads(self.lab.last_out)
        self.assertTrue(rep["ok"])
        self.assertTrue(self.lab.holder().idle)

    def test_health_cli_claims_and_releases(self):
        self.start(jar_fail=["enable"])
        rc = self.lab.main("health", "--sha7", SHA[:7])
        self.assertEqual(rc, 1)
        self.assertFalse(json.loads(self.lab.last_out)["ok"])
        h = self.lab.holder()
        self.assertTrue(h.idle, h.value)
        self.assertIn("released by Infra health", h.value)

    def test_health_cli_machine_problem_keeps_the_holder(self):
        self.start(jar_fail=["home"])
        self.assertEqual(self.lab.main("health", "--sha7", SHA[:7]), 1)
        h = self.lab.holder()
        self.assertFalse(h.idle, h.value)
        self.assertIn("a person must check the machine", h.value)
        self.assertEqual(self.lab.main("resume"), 0, self.lab.last_out)
        h = self.lab.holder()
        self.assertTrue(h.idle, h.value)
        self.assertIn("released by Infra resume", h.value)

    def test_health_cli_refuses_when_busy(self):
        self.start(fail=["run"])
        rc = self.lab.main("health", "--no-claim", "--sha7", SHA[:7])
        self.assertEqual(rc, 3)
        self.assertIn("not idle", self.lab.last_out)
        self.assertNotIn(("POST", "/api/machine/enable"), self.paths())



class HealthLock(HealthLab, unittest.TestCase):
    """BR-72: a lock taken between the idle check and the health check's enable is someone at the machine."""

    def test_a_lock_taken_after_the_idle_check_is_the_machines_not_the_builds(self):
        self.start(lock={"holder": {"kind": "agent", "who": "process", "label": "Process", "what": "a bench session"},
                         "takenFrom": None})
        rep = self.check()
        self.assertFalse(rep["ok"], rep)
        self.assertEqual(rep["kind"], "claim-lost")  # their session: left alone (no rollback over it), a hold
        self.assertTrue(rep["failure"].startswith("enable: someone took the machine lock: Process has the machine"),
                        rep["failure"])
        self.assertNotIn(("POST", "/api/machine/home"), self.posts())
        self.assertNotIn(("POST", "/api/stop"), self.posts())  # STOP would end the new holder's work

    def test_a_lock_taken_between_the_enable_and_the_home_is_left_alone_too(self):
        self.start(jar_fail=["lock-at-home"])
        rep = self.check()
        self.assertFalse(rep["ok"], rep)
        self.assertEqual(rep["kind"], "claim-lost")
        self.assertTrue(rep["failure"].startswith("home: someone took the machine lock"), rep["failure"])
        self.assertNotIn(("POST", "/api/stop"), self.posts())

    def test_taking_over_is_a_lock_refusal_too(self):
        """An anonymous caller hears 'locked' or, while the web UI's Take control runs, 'taking-over'."""
        self.start(jar_fail=["taking-over-enable"])
        rep = self.check()
        self.assertEqual(rep["kind"], "claim-lost", rep)
        self.assertNotIn(("POST", "/api/stop"), self.posts())

    def test_a_holder_who_keeps_the_machine_on_after_the_last_stop_is_theirs(self):
        self.start(jar_fail=["lock-at-stop"])
        rep = self.check("--stop-timeout", "2")
        self.assertFalse(rep["ok"], rep)
        self.assertEqual(rep["kind"], "claim-lost")  # never the bad list for someone else's session
        self.assertTrue(rep["failure"].startswith("stop: "), rep["failure"])
        self.assertIn("User holds the machine lock", rep["failure"])

    def test_a_refused_enable_that_is_not_the_lock_stays_the_builds(self):
        self.start(jar_fail=["enable"])
        rep = self.check()
        self.assertFalse(rep["ok"], rep)
        self.assertEqual(rep["kind"], "build")


ORIGIN = "https://lumen.example.ts.net:8877"


class TrustedOrigins(HealthLab, unittest.TestCase):
    """BR-73: the real machine's web UI through `tailscale serve`, checked on every deploy before anything is enabled."""

    def origins(self, *flags):
        return self.check(*[x for o in flags for x in ("--trusted-origin", o)])

    def probes(self):
        return [r.get("origin") for r in self.lab.requests() if r.get("path") == il_deploy.ORIGIN_PROBE]

    def test_its_own_origin_is_accepted_and_a_foreign_one_refused(self):
        self.start(trusted=[ORIGIN])
        rep = self.origins(ORIGIN)
        self.assertTrue(rep["ok"], rep)
        self.assertEqual([s["step"] for s in rep["steps"]][:3], ["status", "origins", "enable"])
        # every build: a foreign Origin on its own Host (CSRF) and a foreign Host (DNS rebinding); then the tailnet's
        self.assertEqual(self.probes(), [il_deploy.FOREIGN_ORIGIN, "http://" + il_deploy.FOREIGN_HOST] + [ORIGIN, il_deploy.FOREIGN_ORIGIN])
        self.assertEqual(rep["trusted_origins"], [ORIGIN])

    def test_without_the_flag_there_is_no_origins_step(self):
        self.start(trusted=[ORIGIN])
        rep = self.check()
        self.assertTrue(rep["ok"], rep)
        self.assertNotIn("origins", [s["step"] for s in rep["steps"]])
        self.assertEqual(self.probes(), [])

    def test_a_build_from_before_br73_passes_with_a_note(self):
        """A rollback to it stays possible; the tailnet URL is refused there and the report says so."""
        self.start(jar_fail=["old-origins"])
        rep = self.origins(ORIGIN)
        self.assertTrue(rep["ok"], rep)
        step = next(s for s in rep["steps"] if s["step"] == "origins")
        self.assertIn("before BR-73", step["detail"])
        self.assertEqual(self.probes(), [il_deploy.FOREIGN_ORIGIN, "http://" + il_deploy.FOREIGN_HOST])  # its guards are still checked
        self.assertIsNone(rep["trusted_origins"])

    def test_a_build_that_refuses_its_origin_fails_before_the_enable(self):
        self.start(jar_fail=["origins-refused"], trusted=[ORIGIN])
        rep = self.origins(ORIGIN)
        self.assertFalse(rep["ok"], rep)
        self.assertEqual(rep["kind"], "build")
        self.assertTrue(rep["failure"].startswith("origins: "), rep["failure"])
        self.assertIn("its own origin is refused", rep["failure"])
        self.assertNotIn(("POST", "/api/machine/enable"), self.posts())
        self.assertNotIn(("POST", "/api/stop"), self.posts())

    def test_an_origin_that_did_not_reach_openpnp_is_the_units_not_the_builds(self):
        """The same for every build (the unit's environment): never a reason to bad-list a build."""
        self.start(trusted=[])
        rep = self.origins(ORIGIN)
        self.assertFalse(rep["ok"], rep)
        self.assertEqual(rep["kind"], "config")
        self.assertIn("lacks https://lumen.example.ts.net:8877", rep["failure"])
        self.assertNotIn(("POST", "/api/machine/enable"), self.posts())

    def test_a_foreign_host_accepted_fails(self):
        self.start(jar_fail=["hosts-open"], trusted=[ORIGIN])
        rep = self.origins(ORIGIN)
        self.assertFalse(rep["ok"], rep)
        self.assertEqual(rep["kind"], "build")
        self.assertIn("a foreign Host is accepted", rep["failure"])

    def test_a_foreign_origin_accepted_fails(self):
        self.start(jar_fail=["origins-open"], trusted=[ORIGIN])
        rep = self.origins(ORIGIN)
        self.assertFalse(rep["ok"], rep)
        self.assertIn("a foreign Origin is accepted", rep["failure"])

    def test_the_flag_is_normalised_as_openpnp_does(self):
        self.start(trusted=[ORIGIN])
        rep = self.origins("HTTPS://Lumen.Example.ts.net:8877/")
        self.assertTrue(rep["ok"], rep)
        self.assertEqual(self.probes(), [il_deploy.FOREIGN_ORIGIN, "http://" + il_deploy.FOREIGN_HOST] + [ORIGIN, il_deploy.FOREIGN_ORIGIN])

    def test_normalise_origin(self):
        n = il_deploy.normalise_origin
        self.assertEqual(n("HTTPS://Lumen.example.ts.net:8877/"),
                         ("https://lumen.example.ts.net:8877", "lumen.example.ts.net:8877"))
        self.assertEqual(n("https://x.ts.net:443"), ("https://x.ts.net", "x.ts.net"))
        self.assertEqual(n("http://x.ts.net:80"), ("http://x.ts.net", "x.ts.net"))
        # as java.net.URI and ApiSettings.normalizeOrigin (PR #18): the port as a number, RFC 2396 host names
        self.assertEqual(n("https://x.ts.net:08877"), ("https://x.ts.net:8877", "x.ts.net:8877"))
        self.assertEqual(n("http://127.0.0.1:18877"), ("http://127.0.0.1:18877", "127.0.0.1:18877"))
        for bad in ("https://x.ts.net/path", "ftp://x.ts.net", "x.ts.net:8877", "https://", "https://x:99999x",
                    "https://x..ts.net", "https://-x.ts.net", "https://x-.ts.net", "https://x.ts.net:0",
                    "https://x.ts.net:99999", "https://1.2.3.999", "https://x.ts.net//", "https://u@x.ts.net",
                    "\u00a0https://x.ts.net", "https://\u212aelvin.ts.net", "https://x.ts.net:\u0668877"):
            with self.assertRaises(ValueError, msg=bad):
                n(bad)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            il_deploy.config_from_args(self.lab.argv("status") + ["--trusted-origin", "https://x.ts.net/path"])

if __name__ == "__main__":
    unittest.main()
