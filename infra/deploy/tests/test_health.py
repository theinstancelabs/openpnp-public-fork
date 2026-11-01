"""The health check against a fake OpenPnP: each failure mode, and never a STOP during a home."""
import contextlib
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


class Health(unittest.TestCase):
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

    def start(self, jar_fail=(), sha=SHA, **machine):
        with open(os.path.join(self.rel, "VERSION.txt"), "w") as f:
            f.write("il-build-5-" + SHA[:7] + "\n")
        with open(os.path.join(self.rel, "openpnp-il.jar"), "w") as f:
            json.dump({"sha": sha, "fail": list(jar_fail)}, f)
        self.fake = FakeOpenPnP(self.lab.port, self.rel, self.lab.config, machine=machine,
                                requests=self.lab.requests_path).start_thread()

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

    def test_healthy(self):
        self.start()
        rep = self.check()
        self.assertTrue(rep["ok"], rep)
        self.assertEqual([s["step"] for s in rep["steps"]],
                         ["status", "enable", "serial", "axes", "home", "homed", "stop"])
        self.assertIn(("POST", "/api/machine/home"), self.paths())
        self.assertEqual(self.posts()[-1], ("POST", "/api/stop"))
        self.assertFalse(self.fake.enabled)
        self.assertEqual(self.violations(), [])
        json.dumps(rep)

    def test_already_enabled_is_stopped_first(self):
        self.start()
        self.fake.enable()
        rep = self.check()
        self.assertTrue(rep["ok"], rep)

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
        self.start(jar_fail=["engine"])
        t = time.monotonic()
        rep = self.check("--status-timeout", "1")
        self.assertGreaterEqual(time.monotonic() - t, 1.0)
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["kind"], "build")
        self.assertIn("the dispense engine is not usable (dispense.running False, available False", rep["failure"])
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

    def test_slow_home_finishes_late_without_a_stop(self):
        self.start(home_s=1.0)
        rep = self.check("--home-timeout", "0.3", "--home-busy-wait", "5")
        self.assertTrue(rep["ok"], rep)
        self.assertEqual(self.violations(), [])

    def test_home_that_never_ends_is_left_alone(self):
        self.start(home_s=2.0)
        rep = self.check("--home-timeout", "0.3", "--home-busy-wait", "0.5")
        self.assertFalse(rep["ok"])
        self.assertEqual(rep["kind"], "home-stuck")
        self.assertNotIn(("POST", "/api/stop"), self.paths())
        self.assertEqual(self.violations(), [])

    # --- the real server's own 5 min limit (MachineEndpoints.home: f.get(5, MINUTES), then 409 while the home runs on)
    def test_a_home_the_server_gave_up_on_is_waited_out_and_passes(self):
        self.start(home_s=1.5, home_cap_s=0.3)
        rep = self.check("--home-timeout", "5", "--home-busy-wait", "5")
        self.assertTrue(rep["ok"], rep)
        self.assertIn("ended after", [s for s in rep["steps"] if s["step"] == "home"][0]["detail"])
        self.assertEqual(self.violations(), [])
        self.assertEqual(len([p for p in self.paths() if p == ("POST", "/api/machine/home")]), 1)

    def test_a_home_the_server_gave_up_on_that_never_ends_is_left_alone(self):
        self.start(home_s=3.0, home_cap_s=0.3)
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


if __name__ == "__main__":
    unittest.main()
