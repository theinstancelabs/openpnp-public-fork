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

    def test_someone_holding_the_machine_lock_is_not_idle(self):
        """BR-72: an agent role or the user holds the machine lock: they are working at it, also when it looks quiet."""
        since = int(time.time() * 1000) - 600000
        self.start_fake(lock={"holder": {"kind": "agent", "who": "process", "label": "Process", "what": "27G quarter map",
                                         "sinceMs": since}, "takenFrom": None})
        r = self.reasons()
        hhmm = time.strftime("%H:%M", time.localtime(since / 1000))
        self.assertEqual(r, [f"Process holds the machine lock (27G quarter map, since {hhmm})"])

    def test_the_users_hold_and_a_malformed_holder_are_not_idle(self):
        for holder, word in (({"kind": "user", "who": "user", "label": "User", "what": "using the web UI"}, "User holds"),
                             ("someone", "someone holds")):
            with self.subTest(holder=holder):
                if self.fake:
                    self.fake.shutdown()
                self.start_fake(lock={"holder": holder, "takenFrom": None})
                self.assertTrue(any(word in x for x in self.reasons()), self.reasons())

    def test_a_lock_field_nobody_can_read_is_not_idle(self):
        for value in ("held", [], {}, {"owner": "process"}):
            with self.subTest(lock=value):
                if self.fake:
                    self.fake.shutdown()
                self.start_fake(lock=value)
                self.assertTrue(any("does not explain" in r for r in self.reasons()), self.reasons())

    def test_nobody_holding_the_lock_and_a_build_without_one_are_idle(self):
        self.start_fake(lock={"holder": None, "takenFrom": {"label": "Process", "by": "user"}})
        self.assertEqual(self.reasons(), [])
        self.fake.shutdown()
        self.start_fake(fail=["old-lock"])
        self.assertEqual(self.reasons(), [])

    def test_pressure_initial_value_is_not_activity(self):
        # PressureHold sets lastEventAtMs when OpenPnP starts ("pressure off"): not machine activity
        self.start_fake()
        self.assertEqual(self.reasons(), [])

    def test_pressure_start_up_stamp_is_its_text_at_the_start(self):
        """The pressure hold's first value is exactly 'pressure off' (PressureHold.java:77; every event after it has
        more text), stamped while OpenPnP starts. BR-44's server.startedAtMs (the JVM's start) narrows it: also within
        STARTUP_STAMP_S of it (the API is up within --status-timeout's 120 s, or il_deploy calls the start failed). A
        person's event early after the start is an event, and so is a first value whose text changed (that fails
        closed: the quiet period then runs from OpenPnP's start)."""
        self.start_fake()  # 'pressure off', 1 s after the JVM start
        self.assertEqual(self.reasons(), [])
        for name, machine in (("a person's event 70 s after the JVM start", dict(jvm_start_s=100,
                                                                                    pressure_event_s_ago=30)),
                              ("the first value's text changed", dict(pressure_initial_event="off")),
                              ("'pressure off' long after the JVM start", dict(jvm_start_s=il_deploy.STARTUP_STAMP_S
                                                                                + 60))):
            with self.subTest(name):
                self.fake.shutdown()
                self.start_fake(**machine)
                r = self.reasons()
                self.assertTrue(any("a pressure event" in x and "ago" in x for x in r), r)

    def test_pressure_start_up_stamp_rule(self):
        """pressure_start_up_stamp on its own: the text and, with server.startedAtMs, the time; both must hold."""
        t0 = 1_800_000_000_000
        real = "pressure off (valve, then pump): API request"
        window = il_deploy.STARTUP_STAMP_S * 1000
        for text, at, started, stamp in (("pressure off", t0 + 2000, t0, True),
                                         ("pressure off", t0 + window, t0, True),
                                         ("pressure off", t0 + window + 1, t0, False),
                                         ("pressure off", t0 - 1, t0, False),
                                         ("pressure off", None, t0, False),
                                         (real, t0 + 100_000, t0, False),
                                         ("off", t0 + 2000, t0, False),
                                         ("pressure off", t0 + 10 * window, None, True),
                                         (real, t0 + 2000, None, False)):
            with self.subTest(text=text, at=at, started=started):
                st = {"server": {} if started is None else {"startedAtMs": started},
                      "pressure": {"lastEvent": text, "lastEventAtMs": at}}
                self.assertIs(il_deploy.pressure_start_up_stamp(st), stamp)
                events = [ms for what, ms in il_deploy.api_activity_times(st) if what == "a pressure event"]
                self.assertEqual(events, [] if stamp or at is None else [at])

    def test_pressure_start_up_stamp_of_a_build_before_br44(self):
        """No server.startedAtMs (a rollback to an older build): the first value is told by its text, as before."""
        self.start_fake(fail=["old-status"], jvm_start_s=il_deploy.STARTUP_STAMP_S + 60)
        self.assertEqual(self.reasons(), [])
        self.fake.shutdown()
        self.start_fake(fail=["old-status"], pressure_initial_event="off")
        r = self.reasons()
        self.assertTrue(any("a pressure event" in x for x in r), r)

    def test_machine_homing_is_busy(self):
        st = {"machine": {"busy": False, "homing": True, "enabled": False}}
        self.assertEqual(il_deploy.api_busy_reasons(st), ["OpenPnP is homing (machine.homing)"])
        st["machine"]["homing"] = False
        self.assertEqual(il_deploy.api_busy_reasons(st), [])

    def test_a_lost_controller_link_is_not_idle(self):
        """BR-93: no deploy while machine.linkLost is set (the restart would clear it and the health check home a
        controller that may have reset); its why and next step are named. Not explained: taken as lost."""
        lost = {"atMs": 1, "why": "the link to the controller was lost at 15:07 (the controller left USB)",
                "next": "once the board is back on USB: Stop, then Start, then Home as after a reset"}
        st = {"machine": {"busy": False, "enabled": False, "linkLost": lost}}
        self.assertEqual(il_deploy.api_busy_reasons(st), [f"the controller link is lost (machine.linkLost): "
                                                          f"{lost['why']}; {lost['next']}"])
        for v in (None, "lost", 1, {}):
            with self.subTest(v):
                st["machine"]["linkLost"] = v
                self.assertEqual(bool(il_deploy.api_busy_reasons(st)), v is not None)
        del st["machine"]["linkLost"]  # a build before BR-93
        self.assertEqual(il_deploy.api_busy_reasons(st), [])

    def test_a_lost_controller_link_through_the_api(self):
        self.start_fake(link_lost={"atMs": 1, "why": "the link to the controller was lost at 15:07", "next": "Stop"})
        r = self.reasons()
        self.assertTrue(any("machine.linkLost" in x for x in r), r)

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

    def test_status_fields_of_br44(self):
        """BR-44's fields are always in the new build's status (the e2e checks them on the real jar); a build before it
        lacks exactly these, and il_deploy falls back to its older rules there."""
        br44 = ["server.startedAtMs", "machine.homing", "dispense.state", "dispense.startError"]
        for p in br44:
            self.assertIn(p, il_deploy.STATUS_FIELDS)
        self.start_fake(fail=["old-status"])
        st = il_deploy.Api(self.lab.port).get("/api/status")[1]
        # and BR-93's machine.linkLost, which came after
        self.assertEqual(sorted(il_deploy.status_field_problems(st)), sorted(br44 + ["machine.linkLost"]))

    def test_status_fields_of_br93(self):
        """BR-93's machine.linkLost is read by every idle and health check, and absent counts as no loss (a build
        before #53): a rename would turn the guard off silently, so the e2e checks it on the real jar."""
        self.assertIn("machine.linkLost", il_deploy.STATUS_FIELDS)
        self.start_fake()
        st = il_deploy.Api(self.lab.port).get("/api/status")[1]
        self.assertIsNone(st["machine"]["linkLost"])
        self.assertEqual(il_deploy.status_field_problems(st), [])
        del st["machine"]["linkLost"]
        self.assertEqual(il_deploy.status_field_problems(st), ["machine.linkLost"])

    def test_status_fields_inside_places_and_surfaces_and_behind_nulls(self):
        """A place or surface without setAtMs is a rename (status_field_problems). What a null 'key?' or an empty map
        hides was not checked at all: unchecked_status_fields lists it, so the e2e sees every path it did not check."""
        self.start_fake()
        st = il_deploy.Api(self.lab.port).get("/api/status")[1]
        self.assertEqual(il_deploy.unchecked_status_fields(st), ["run.finishedAtMs", "lastRun.endedAtMs",
                                                                 "padCalibration.endedAtMs",
                                                                 "dispense.needle.xyTrim.setAtMs",
                                                                 "dispense.needle.anchor.setAtMs", "run.running",
                                                                 "padCalibration.state"])
        st["dispense"]["locations"]["waste"]["placedAtMs"] = st["dispense"]["locations"]["waste"].pop("setAtMs")
        del st["dispense"]["surfaces"]["board"]["setAtMs"]
        self.assertEqual(il_deploy.status_field_problems(st), ["dispense.locations.waste.setAtMs",
                                                               "dispense.surfaces.board.setAtMs"])
        st["dispense"]["locations"], st["dispense"]["surfaces"] = {}, {}
        self.assertEqual(il_deploy.status_field_problems(st), [])
        self.assertEqual(il_deploy.unchecked_status_fields(st)[-2:], ["dispense.locations.*.setAtMs",
                                                                      "dispense.surfaces.*.setAtMs"])
        st["run"] = {"running": False, "finishedAtMs": None}
        self.assertNotIn("run.running", il_deploy.unchecked_status_fields(st))
        self.assertNotIn("run.finishedAtMs", il_deploy.unchecked_status_fields(st), "a null leaf is there: checked")

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

    def test_a_lumen_sim_config_is_not_real(self):
        """uinav --controller pty (and the deploy e2e) keep the GcodeDriver on serial with port-name a ttyLUMEN* link to
        lumen-sim's /dev/pts/N and no USB identity: OpenPnP cannot reach the board with it, and a SIM session on the lab
        computer must not stop the timer's deploys. Anything else on serial stays real (fail closed)."""
        lab = Lab()
        master, slave = os.openpty()
        try:
            pts = os.ttyname(slave)
            link = os.path.join(lab.dir, "ttyLUMEN0")
            os.symlink(pts, link)

            def real(port, attrs=""):
                with open(os.path.join(lab.seed, "machine.xml"), "w") as f:
                    f.write('<openpnp-machine><machine><drivers><driver class="org.openpnp.machine.reference.driver.'
                            f'GcodeDriver" id="D1" communications="serial"><serial port-name="{port}" baud="115200"'
                            f'{attrs}/><tcp ip-address="127.0.0.1" port="23"/></driver></drivers></machine>'
                            '</openpnp-machine>')
                return il_deploy.config_is_real(lab.seed)

            self.assertFalse(real(link))
            self.assertTrue(real(link, ' usb-serial-number="387932683335"'), "the port matcher looks for the board")
            self.assertTrue(real(link, ' usb-description="MARLIN_OPULO_LUMEN_REV5"'))
            other = os.path.join(lab.dir, "ttyUSB9")
            os.symlink(pts, other)
            self.assertTrue(real(other), "jSerialComm opens /dev/<name> while a link is missing: only ttyLUMEN*")
            plain = os.path.join(lab.dir, "ttyLUMEN1")
            open(plain, "w").close()
            self.assertTrue(real(plain), "not a pty")
            dangling = os.path.join(lab.dir, "ttyLUMEN2")
            os.symlink("/dev/pts/999999", dangling)
            self.assertTrue(real(dangling), "a pty that does not exist (lumen-sim stopped)")
            self.assertTrue(real("ttyLUMEN0"), "a relative name resolves against OpenPnP's directory, not ours")
            self.assertTrue(real(""))
            with open(os.path.join(lab.seed, "machine.xml"), "w") as f:
                f.write('<openpnp-machine><machine><drivers><driver communications="serial">'
                        f'<serial port-name="{link}"/></driver><driver communications="serial">'
                        '<serial port-name="/dev/ttyACM0"/></driver></drivers></machine></openpnp-machine>')
            self.assertTrue(il_deploy.config_is_real(lab.seed), "one real driver is enough")
            with open(os.path.join(lab.seed, "machine.xml"), "w") as f:
                f.write('<openpnp-machine><machine><drivers><driver communications="serial"/></drivers></machine>'
                        '</openpnp-machine>')
            self.assertTrue(il_deploy.config_is_real(lab.seed), "serial without settings")
        finally:
            os.close(master)
            os.close(slave)
            lab.cleanup()


if __name__ == "__main__":
    unittest.main()
