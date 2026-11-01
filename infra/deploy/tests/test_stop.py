"""When a STOP is sent: never during a home (a STOP inside G28 hangs the controller), at once while outputs may
be on, never blind when OpenPnP's state is unknown. Covers stop_machine (deploy flows) and stop-openpnp (the
unit's ExecStop, and the subprocess manager's pre-stop)."""
import io
import os
import sys
import threading
import unittest

# il_deploy is one level up: also when unittest imports this module by bare name (no -t)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import il_deploy  # noqa: E402
from fake_openpnp import FakeOpenPnP  # noqa: E402
from fakes import FakeClock, Lab  # noqa: E402


class StubApi:
    """GET answers from a function of the call count; POST /api/stop is recorded and disables."""

    def __init__(self, status):
        self.status = status
        self.gets = 0
        self.posts = []

    def get(self, path, timeout=10):
        self.gets += 1
        r = self.status(self.gets)
        if isinstance(r, BaseException):
            raise r
        code, st = r
        if path == "/api/machine" and code == 200:
            return code, dict(st.get("machine") or {}, enabled=not self.posts)
        return code, st

    def post(self, path, body=None, timeout=10):
        self.posts.append(path)
        return 200, {"stopped": True, "machineEnabled": False}


class StubService:
    def __init__(self):
        self.stopped = False

    def is_active(self):
        return True

    def stop(self):
        self.stopped = True

    def pids(self):
        return set()


def status(busy=False, run=None, pressure=False, state=None):
    state = state or ("on" if pressure else "off")
    return 200, {"machine": {"busy": busy, "enabled": True}, "run": run,
                 "pressure": {"on": state == "on", "state": state}}


class Decisions(unittest.TestCase):
    def setUp(self):
        self.lab = Lab()
        self.svc = StubService()

    def tearDown(self):
        self.lab.cleanup()

    def deployer(self, api, *extra):
        cfg = il_deploy.config_from_args(self.lab.argv("status", extra=extra))
        d = il_deploy.Deployer(cfg, self.lab.deps(clock=FakeClock(), service=self.svc))
        d.api = api
        return d

    def test_timeout_is_not_nothing_to_stop(self):
        api = StubApi(lambda n: il_deploy.ApiTimeout("GET /api/machine: no answer within 10 s"))
        d = self.deployer(api)
        ok, detail = d.stop_machine()
        self.assertFalse(ok, detail)
        self.assertEqual(api.posts, [])
        self.assertFalse(d.stop_after_failure(), "a live but slow OpenPnP is not killed without a STOP")
        self.assertFalse(self.svc.stopped)

    def test_non_200_is_unknown_and_waits(self):
        api = StubApi(lambda n: (500, {"reason": "NullPointerException"}))
        ok, detail = self.deployer(api).stop_machine()
        self.assertFalse(ok)
        self.assertEqual(api.posts, [], "never STOP blind: it may be homing")
        self.assertGreater(api.gets, 1)

    def test_non_200_then_idle_stops(self):
        api = StubApi(lambda n: (500, {}) if n < 3 else status())
        ok, detail = self.deployer(api).stop_machine()
        self.assertTrue(ok, detail)
        self.assertEqual(api.posts, ["/api/stop"])

    def test_api_gone_is_nothing_to_stop(self):
        api = StubApi(lambda n: il_deploy.ApiDown("connection refused"))
        ok, detail = self.deployer(api).stop_machine()
        self.assertTrue(ok)
        self.assertEqual(api.posts, [])

    def test_busy_waits_then_stops(self):
        api = StubApi(lambda n: status(busy=n < 4))
        ok, detail = self.deployer(api).stop_machine()
        self.assertTrue(ok, detail)
        self.assertEqual(api.posts, ["/api/stop"])

    def test_engine_run_dispensing_is_stopped_at_once(self):
        run = {"running": True, "kind": "board", "phase": "dot 12 of 40"}
        api = StubApi(lambda n: status(busy=True, run=run))
        ok, detail = self.deployer(api).stop_machine()
        self.assertTrue(ok, detail)
        self.assertEqual((api.posts, api.gets), (["/api/stop"], 1), "STOP at once: the valve may be open")

    def test_engine_run_homing_is_not_stopped(self):
        run = {"running": True, "kind": "board", "phase": "home: the run homes first"}
        api = StubApi(lambda n: status(busy=True, run=run))
        ok, detail = self.deployer(api).stop_machine()
        self.assertFalse(ok)
        self.assertEqual(api.posts, [])

    def test_pressure_on_is_stopped_at_once(self):
        api = StubApi(lambda n: status(busy=True, pressure=True))
        ok, detail = self.deployer(api).stop_machine()
        self.assertTrue(ok, detail)
        self.assertEqual(api.posts, ["/api/stop"])

    def test_vent_failed_is_stopped_at_once(self):
        api = StubApi(lambda n: status(busy=True, state="vent_failed"))
        ok, detail = self.deployer(api).stop_machine()
        self.assertTrue(ok, detail)
        self.assertEqual((api.posts, api.gets), (["/api/stop"], 1))

    def test_pressure_switching_while_busy_waits_like_a_home(self):
        """PressureHold.start sets STARTING on the HTTP thread and queues its task behind whatever the machine thread
        runs, e.g. a home: 'starting' with the machine busy may be a G28 underway, and nothing is on yet."""
        for state in ("starting", "stopping"):
            with self.subTest(state=state):
                api = StubApi(lambda n, s=state: status(busy=True, state=s))
                ok, detail = self.deployer(api).stop_machine()
                self.assertFalse(ok, detail)
                self.assertEqual(api.posts, [], "never STOP while the machine thread may be homing")
                self.assertIn("busy", detail)
                # the home ends, the start runs (busy again), then the hold is on: STOP at once then
                seq = {1: status(busy=True, state=state), 2: status(busy=False, state=state),
                       3: status(busy=False, state="on")}
                api = StubApi(lambda n: seq.get(n, status(busy=True, state="on")))
                ok, detail = self.deployer(api).stop_machine()
                self.assertTrue(ok, detail)
                self.assertEqual((api.posts, api.gets), (["/api/stop"], 2))

    def test_exec_stop_uses_the_same_rules(self):
        for answer, posts, word in ((lambda n: status(busy=True), [], "not sent"),
                                    (lambda n: il_deploy.ApiTimeout("x"), [], "not sent"),
                                    (lambda n: il_deploy.ApiDown("refused"), [], "nothing to stop"),
                                    (lambda n: status(), ["/api/stop"], "disabled")):
            with self.subTest(posts=posts, word=word):
                api = StubApi(answer)
                d = self.deployer(api)
                d.out = io.StringIO()
                rc = d.stop_openpnp()
                self.assertEqual(rc, 0)
                self.assertEqual(api.posts, posts)
                self.assertIn(word, d.out.getvalue())


class AgainstTheFake(unittest.TestCase):
    """stop-openpnp and the subprocess manager's pre-stop against a fake OpenPnP that is homing."""

    def setUp(self):
        self.lab = Lab()
        os.makedirs(self.lab.config)
        self.fake = None

    def tearDown(self):
        if self.fake:
            self.fake.shutdown()
        self.lab.cleanup()

    def homing(self, seconds):
        self.fake = FakeOpenPnP(self.lab.port, self.lab.dir, self.lab.config, machine={"home_s": seconds},
                                requests=self.lab.requests_path).start_thread()
        self.fake.enable()
        t = threading.Thread(target=self.fake.home, args=({"wait": True},), daemon=True)
        t.start()
        while not self.fake.homing:
            pass
        return t

    def stops(self):
        return [r for r in self.lab.requests() if r.get("path") == "/api/stop"]

    def violations(self):
        return [r["violation"] for r in self.lab.requests() if "violation" in r]

    def test_exec_stop_waits_for_the_home(self):
        t = self.homing(1.0)
        self.assertEqual(self.lab.main("stop-openpnp"), 0)
        t.join()
        self.assertEqual(len(self.stops()), 1)
        self.assertFalse(self.fake.enabled)
        self.assertEqual(self.violations(), [])

    def test_exec_stop_never_stops_a_home_that_outlasts_the_wait(self):
        t = self.homing(2.0)
        self.assertEqual(self.lab.main("stop-openpnp", extra=["--home-busy-wait", "0.5"]), 0)
        self.assertIn("not sent", self.lab.last_out)
        t.join()
        self.assertEqual(self.stops(), [])
        self.assertEqual(self.violations(), [])

    def test_subprocess_pre_stop_uses_the_same_rules(self):
        t = self.homing(2.0)
        cfg = il_deploy.config_from_args(self.lab.argv("status", extra=["--home-busy-wait", "0.5"]))
        svc = il_deploy.make_service(cfg)
        svc.pre_stop()
        t.join()
        self.assertEqual(self.stops(), [])
        self.assertEqual(self.violations(), [])


if __name__ == "__main__":
    unittest.main()
