"""When a STOP is sent: never during a home (a STOP inside G28 hangs the controller), at once while outputs may
be on, never blind when OpenPnP's state is unknown. Covers stop_machine (deploy flows) and stop-openpnp (the
unit's ExecStop, and the subprocess manager's pre-stop)."""
import contextlib
import io
import os
import sys
import threading
import unittest
from unittest import mock

# il_deploy is one level up: also when unittest imports this module by bare name (no -t)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import il_deploy  # noqa: E402
from fake_openpnp import FakeOpenPnP  # noqa: E402
from fakes import FakeClock, Lab  # noqa: E402


class StubApi:
    """GET answers from a function of the call count (before the STOP); POST /api/stop is recorded, and after it the
    machine is disabled with the valve and pump off, unless after_stop(n) answers (n: GETs since the STOP)."""

    def __init__(self, status, after_stop=None):
        self.status = status
        self.after_stop = after_stop
        self.gets = 0
        self.gets_before_stop = None
        self.posts = []

    def get(self, path, timeout=10):
        self.gets += 1
        if self.posts and self.after_stop:
            r = self.after_stop(self.gets - self.gets_before_stop)
        else:
            r = self.status(self.gets)
        if isinstance(r, BaseException):
            raise r
        code, st = r
        if self.posts and not self.after_stop and code == 200:
            st = dict(st, machine=dict(st.get("machine") or {}, enabled=False, busy=False), run=None,
                      pressure={"on": False, "state": "off"}, dispense={"busy": False})
        if path == "/api/machine" and code == 200:
            return code, st.get("machine") or {}
        return code, st

    def post(self, path, body=None, timeout=10):
        if not self.posts:
            self.gets_before_stop = self.gets
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


def status(busy=False, run=None, pressure=False, state=None, homing=None):
    """/api/status; homing None: a build before BR-44 (no machine.homing)."""
    state = state or ("on" if pressure else "off")
    machine = {"busy": busy, "enabled": True}
    if homing is not None:
        machine["homing"] = homing
    return 200, {"machine": machine, "run": run, "pressure": {"on": state == "on", "state": state}}


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
        self.assertEqual((api.posts, api.gets_before_stop), (["/api/stop"], 1), "STOP at once: the valve may be open")

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
        self.assertEqual((api.posts, api.gets_before_stop), (["/api/stop"], 1))

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
                self.assertEqual((api.posts, api.gets_before_stop), (["/api/stop"], 2))

    # --- BR-44: machine.homing, true from the start of any home until it ends (ReferenceMachine.isHoming) ----------
    def test_no_stop_while_machine_homing_says_a_home_runs(self):
        """machine.homing decides that a home runs, whatever else the status shows: an engine run past its home phase,
        the pressure held or vent_failed (OpenPnP's own Home button does not check the web's pressure hold), or a
        machine not busy. A STOP's M410 inside G28 hangs the controller, and the valve-off lines queue behind it."""
        dots = {"running": True, "kind": "board", "phase": "dot 12 of 40"}
        for name, kw in (("a run past its home", {"busy": True, "run": dots}),
                         ("the pressure held", {"busy": True, "pressure": True}),
                         ("vent failed", {"busy": True, "state": "vent_failed"}),
                         ("not busy", {"busy": False})):
            with self.subTest(name):
                api = StubApi(lambda n, kw=kw: status(homing=True, **kw))
                ok, detail = self.deployer(api).stop_machine()
                self.assertFalse(ok, detail)
                self.assertEqual(api.posts, [], "never STOP while machine.homing is true")
                self.assertIn("machine.homing", detail)
                # the home ends: the STOP goes out at the first answer that says so
                api = StubApi(lambda n, kw=kw: status(homing=n < 4, **kw))
                ok, detail = self.deployer(api).stop_machine()
                self.assertTrue(ok, detail)
                self.assertEqual((api.posts, api.gets_before_stop), (["/api/stop"], 4))

    def test_not_homing_yet_keeps_the_pre_home_wait(self):
        """machine.homing turns true when OpenPnP's home starts. The engine's home phase and the web's home first read
        the position and raise N1 (DispenseService.homeSafely), busy and not homing yet: a STOP there may meet the G28
        that follows (WEB_API.md: the check-then-STOP window). So a run in its home phase, and a busy machine, are
        still waited out; what is STOPped at once stays as before (outputs on)."""
        pre_home = {"running": True, "kind": "board", "phase": "home: not homed · raising N1 from Z 12.00"}
        for name, kw in (("the run's home phase", {"busy": True, "run": pre_home}), ("busy", {"busy": True})):
            with self.subTest(name):
                api = StubApi(lambda n, kw=kw: status(homing=False, **kw))
                ok, detail = self.deployer(api).stop_machine()
                self.assertFalse(ok, detail)
                self.assertEqual(api.posts, [])
        dots = {"running": True, "kind": "board", "phase": "dot 12 of 40"}
        for name, kw in (("a run past its home", {"busy": True, "run": dots}),
                         ("the pressure held", {"pressure": True}), ("idle", {})):
            with self.subTest(name):
                api = StubApi(lambda n, kw=kw: status(homing=False, **kw))
                ok, detail = self.deployer(api).stop_machine()
                self.assertTrue(ok, detail)
                self.assertEqual((api.posts, api.gets_before_stop), (["/api/stop"], 1))

    def test_a_home_ends_when_machine_homing_says_so(self):
        """The health check's wait for a home its POST did not see end (Deployer._wait_not_busy) also waits while
        machine.homing is true."""
        api = StubApi(lambda n: status(busy=False, homing=n < 4))
        d = self.deployer(api)
        self.assertTrue(d._wait_not_busy(5))
        self.assertEqual(api.gets, 4)
        api = StubApi(lambda n: status(busy=False, homing=True))
        self.assertFalse(self.deployer(api, "--poll-interval", "0.5")._wait_not_busy(2))

    def test_a_home_ends_when_busy_says_so(self):
        """The same wait holds while machine.busy is true: with machine.homing false (BR-44), and without it (a build
        before BR-44, where a rollback can land), where machine.busy alone shows the home."""
        for homing in (False, None):
            with self.subTest(homing=homing):
                api = StubApi(lambda n, homing=homing: status(busy=n < 4, homing=homing))
                self.assertTrue(self.deployer(api)._wait_not_busy(5))
                self.assertEqual(api.gets, 4)
                api = StubApi(lambda n, homing=homing: status(busy=True, homing=homing))
                self.assertFalse(self.deployer(api, "--poll-interval", "0.5")._wait_not_busy(2))

    def test_exec_stop_uses_the_same_rules(self):
        """Exit 0 when the machine is off (or a live JVM's API is not up yet: systemd sets $MAINPID); 1 when it may not
        be: systemd then marks the unit failed, which il_deploy treats as an exit it did not cause (a hold). systemd
        also runs ExecStop after the JVM ended by itself with 0 or 143 (a kill from outside), $MAINPID unset: nothing
        answers then, and nothing confirmed a STOP (1, the run marker stays)."""
        for answer, mainpid, marker, posts, word, rc in (
                (lambda n: status(busy=True), "4242", True, [], "not sent", 1),
                (lambda n: il_deploy.ApiTimeout("x"), "4242", True, [], "not sent", 1),
                # a live JVM started after its last log: its API never came up, nothing is on yet
                (lambda n: il_deploy.ApiDown("refused"), "4242", True, [], "nothing to stop", 0),
                # no marker (a unit from before mark-start): whether its API ran is unknown: fail closed
                (lambda n: il_deploy.ApiDown("refused"), "4242", False, [], "answered earlier in this run", 1),
                (lambda n: il_deploy.ApiDown("refused"), None, True, [], "no STOP confirmed", 1),
                (lambda n: status(), "4242", True, ["/api/stop"], "disabled", 0),
                (lambda n: status(), None, True, ["/api/stop"], "disabled", 0)):
            with self.subTest(posts=posts, word=word, mainpid=mainpid, marker=marker), \
                    mock.patch.dict(os.environ, {"MAINPID": mainpid} if mainpid else {}):
                if not mainpid:
                    os.environ.pop("MAINPID", None)
                api = StubApi(answer)
                d = self.deployer(api)
                d.out = io.StringIO()
                if marker:
                    il_deploy.write_json(d.run_marker_path, {"boot_id": il_deploy.boot_id(), "at": d.iso()})
                else:
                    with contextlib.suppress(FileNotFoundError):
                        os.unlink(d.run_marker_path)
                self.assertEqual(d.stop_openpnp(), rc)
                self.assertEqual(api.posts, posts)
                self.assertIn(word, d.out.getvalue())

    def test_the_stop_is_confirmed_by_the_outputs_not_only_the_disable(self):
        """MachineEndpoints.stop disables the machine, but the engine's abort (its STOP_SEQUENCE: valve, then pump off)
        goes out only after the move in progress, e.g. a 10 s prime: enabled false is not the end of it."""
        def disabled(busy, state):
            return 200, {"machine": {"busy": False, "enabled": False}, "run": None,
                         "pressure": {"on": state == "on", "state": state}, "dispense": {"busy": busy}}
        for name, after, ok in (("engine still busy", lambda n: disabled(n < 5, "off"), True),
                                ("pressure still on", lambda n: disabled(False, "on" if n < 5 else "off"), True),
                                ("vent failed", lambda n: disabled(False, "vent_failed"), False),
                                ("never off", lambda n: disabled(True, "on"), False)):
            with self.subTest(name):
                api = StubApi(lambda n: status(), after_stop=after)
                ok_, detail = self.deployer(api, "--stop-timeout", "3").stop_machine()
                self.assertEqual(ok_, ok, detail)
                self.assertEqual(api.posts, ["/api/stop"])
                if ok:
                    self.assertGreaterEqual(api.gets - api.gets_before_stop, 5)
                else:
                    self.assertTrue(any(w in detail for w in ("pressure", "engine")), detail)

    def test_the_confirm_may_use_what_the_home_wait_left(self):
        """The ExecStop's budget is the home wait plus the confirm: time not spent waiting for a home is the STOP's (a
        driver timeout of 60 s on a blocking M400 delays the engine's own abort lines)."""
        def disabled(busy):
            return 200, {"machine": {"busy": False, "enabled": False}, "run": None,
                         "pressure": {"on": False, "state": "off"}, "dispense": {"busy": busy}}
        api = StubApi(lambda n: status(), after_stop=lambda n: disabled(n < 200))  # 200 polls of 0.5 s: 100 s
        clock = FakeClock()
        cfg = il_deploy.config_from_args(self.lab.argv("status", extra=["--home-busy-wait", "300", "--stop-timeout",
                                                                           "10", "--poll-interval", "0.5"]))
        d = il_deploy.Deployer(cfg, self.lab.deps(clock=clock, service=self.svc))
        d.api = api
        t0 = clock.monotonic()
        ok, detail = d.stop_machine()
        self.assertTrue(ok, detail)
        self.assertLess(clock.monotonic() - t0, 300 + 10)


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
        # no STOP: exit 1, the unit ends 'failed' and il_deploy holds (the home was cut by the kill)
        self.assertEqual(self.lab.main("stop-openpnp", extra=["--home-busy-wait", "0.5"]), 1)
        self.assertIn("not sent", self.lab.last_out)
        t.join()
        self.assertEqual(self.stops(), [])
        self.assertEqual(self.violations(), [])

    def test_exec_stop_waits_for_a_home_the_run_phase_does_not_show(self):
        """An engine run past its home phase (dots) while a home runs (OpenPnP's own Home button, a pad calibration's
        home between its steps): the run's phase alone says STOP at once; machine.homing says wait (BR-44)."""
        self.fake = FakeOpenPnP(self.lab.port, self.lab.dir, self.lab.config, machine={"home_s": 1.0},
                                requests=self.lab.requests_path)
        self.fake.fail.add("run")
        self.fake.run_active = True
        self.fake.start_thread()
        self.fake.enable()
        t = threading.Thread(target=self.fake.home, args=({"wait": True},), daemon=True)
        t.start()
        while not self.fake.homing:
            pass
        self.assertEqual(self.lab.main("stop-openpnp"), 0, self.lab.last_out)
        t.join()
        self.assertEqual(len(self.stops()), 1)
        self.assertEqual(self.violations(), [])
        self.assertFalse(self.fake.enabled)

    def test_exec_stop_waits_for_the_engines_abort(self):
        """An engine run (a long prime) during the ExecStop: the STOP disables the machine at once, the run's own abort
        ends 1 s later. systemd kills java right after the ExecStop: it must not return before the run ended."""
        self.fake = FakeOpenPnP(self.lab.port, self.lab.dir, self.lab.config, machine={"run_abort_s": 1.0},
                                requests=self.lab.requests_path)
        self.fake.fail.update({"run", "pressure"})
        self.fake.run_active, self.fake.pressure_state = True, "on"
        self.fake.start_thread()
        self.fake.enable()
        self.assertEqual(self.lab.main("stop-openpnp"), 0, self.lab.last_out)
        self.assertFalse(self.fake.enabled)
        self.assertFalse(self.fake.run_active, "returned while the engine was still running its abort")
        self.assertEqual(self.fake.pressure_state, "off")

    def test_exec_stop_fails_when_the_pressure_does_not_vent(self):
        self.fake = FakeOpenPnP(self.lab.port, self.lab.dir, self.lab.config, machine={},
                                requests=self.lab.requests_path)
        self.fake.fail.update({"pressure", "vent-fail"})
        self.fake.pressure_state = "on"
        self.fake.start_thread()
        self.assertEqual(self.lab.main("stop-openpnp", extra=["--stop-timeout", "0.5", "--home-busy-wait", "0.5"]), 1)
        self.assertIn("vent_failed", self.lab.last_out)

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
