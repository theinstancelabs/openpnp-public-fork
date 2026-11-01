"""End to end: OpenPnP (this fork's jar) in front of lumen-sim exactly as in front of the real controller.

OpenPnP runs through openpnp-uinav in SIM mode with '--controller pty': the GcodeDriver stays on serial, its port-name
is the session's lumen-sim pty link, its USB identity is removed, and jSerialComm is the real one (only the camera
stubs are on the classpath). Everything is driven through the backend's web API (OPENPNP_IL_API_PORT), as the UI
does. The config is a copy of ~/.openpnp2 (read only) with machine.xml from tools/instancelabs/make_machine_patch.py,
the config the SIM acceptance uses, plus visual homing off (the SIM camera has no homing fiducial).

  (a) enable + home through /api/machine/enable and /api/machine/home {"wait": true}: homed, and OpenPnP's axes
      match lumen-sim's state;
  (b) STOP 4 s into a home (POST /api/stop): 'timeout waiting for response to M410' in OpenPnP's log, the home
      fails, lumen-sim is halted, a reconnect cannot talk to it until 'uinav controller power-cycle';
  (c) the 30-turn prime at 180 rpm through the engine (a needle touch-off with primeRotationDeg 10800 and the
      profile's dwellMs 250, RESEARCH.md section 6a): 'timeout waiting for response to G4 P250' while the engine
      streams it as one move; once the engine chunks primes (BR-30) a clean run passes too (CI's gate runs this).

Skipped unless LUMEN_SIM_E2E=1 and LUMEN_SIM_E2E_JAR names the OpenPnP jar. Takes about 5 minutes (OpenPnP's own
60 s G28 timeout is part of (b)). One OpenPnP at a time, stopped at the end.

    LUMEN_SIM_E2E=1 LUMEN_SIM_E2E_JAR=target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar \\
    UINAV_DIR=/home/lumen/instancelabs/wt/uinav-pty \\
        python3 infra/lumen-sim/e2e/test_openpnp_pty.py -v      (from the repository root)

Env: UINAV_DIR (openpnp-uinav with --controller pty), LUMEN_SIM_E2E_CONFIG (config dir to copy, default
~/.openpnp2), LUMEN_SIM_E2E_DISPENSE_CONFIG (engine config to stage, default the SIM acceptance's if present),
LUMEN_SIM_E2E_PORTS (web API ports to try, default 18930-18939), LUMEN_SIM_E2E_OUT (keeps logs and results.json).
"""
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
LUMEN_SIM_DIR = os.path.dirname(HERE)
REPO = os.path.abspath(os.path.join(LUMEN_SIM_DIR, "..", ".."))
PATCH_TOOL = os.path.join(REPO, "tools", "instancelabs", "make_machine_patch.py")
UINAV_DIR = os.environ.get("UINAV_DIR", "/home/lumen/instancelabs/openpnp-uinav")
UINAV = os.path.join(UINAV_DIR, "bin", "uinav")
JAR = os.environ.get("LUMEN_SIM_E2E_JAR", "")
LIVE = os.path.expanduser(os.environ.get("LUMEN_SIM_E2E_CONFIG", "~/.openpnp2"))
DISPENSE_CONFIG = os.environ.get("LUMEN_SIM_E2E_DISPENSE_CONFIG", "/home/lumen/instancelabs/config/dispense-config.json")
PORTS = [int(p) for p in os.environ.get("LUMEN_SIM_E2E_PORTS", ",".join(str(p) for p in range(18930, 18940))).split(",")]
OUT = os.environ.get("LUMEN_SIM_E2E_OUT")
PARK = "300,300,31.5"  # far from the switches: a home takes ~7 s, so a STOP 4 s in lands in a homing move
XML_SETS = [".//head@visual-homing-method=None"]
# The bench's blank (N1 contacts along the purge row) and places on it, as the SIM acceptance uses them.
BLANK_PROBES = [[288.0, 232.0, 5.6], [310.0, 232.0, 5.6], [345.0, 232.0, 5.55], [300.0, 238.0, 5.65],
                [360.0, 238.0, 5.475]]
BLANK = {"x": 320.0, "y": 235.0, "z": 5.6}
WASTE = {"x": 300.0, "y": 236.0, "z": 5.6}
PRIME_DEG = 10800.0  # 30 turns

sys.path.insert(0, os.path.join(UINAV_DIR, "python"))
try:
    import uinav  # noqa: E402
except ImportError:
    uinav = None


def why_skip():
    if os.environ.get("LUMEN_SIM_E2E") != "1":
        return "set LUMEN_SIM_E2E=1 (starts OpenPnP; about 5 minutes)"
    if not JAR or not os.path.isfile(JAR):
        return "set LUMEN_SIM_E2E_JAR to the OpenPnP jar (got %r)" % JAR
    if uinav is None or not hasattr(uinav, "pty_drivers"):
        return "openpnp-uinav at %s has no --controller pty (branch infra/pty-controller); set UINAV_DIR" % UINAV_DIR
    if not os.path.isfile(os.path.join(LIVE, "machine.xml")):
        return "no config to copy at %s" % LIVE
    return None


def free_api_port():
    for p in PORTS:
        with socket.socket() as s:
            try:
                s.bind(("127.0.0.1", p))
            except OSError:
                continue
        return p
    raise RuntimeError("no free web API port in %s" % PORTS)


def stage(dest):
    """~/.openpnp2 (read only) + make_machine_patch.py's machine.xml + the engine's config."""
    shutil.copytree(LIVE, dest, ignore=shutil.ignore_patterns("backups", "log"))
    subprocess.run([sys.executable, "-I", PATCH_TOOL, "--in", os.path.join(LIVE, "machine.xml"),
                    "--out", os.path.join(dest, "machine.xml")], check=True, capture_output=True, timeout=60)
    if DISPENSE_CONFIG and os.path.isfile(DISPENSE_CONFIG):
        os.makedirs(os.path.join(dest, "instancelabs"), exist_ok=True)
        shutil.copy2(DISPENSE_CONFIG, os.path.join(dest, "instancelabs", "dispense-config.json"))
    return dest


class PtySession:
    """One uinav session: OpenPnP + lumen-sim (stock personality)."""

    def __init__(self, name, tmp):
        self.name = name
        self.port = free_api_port()
        env = dict(os.environ, OPENPNP_IL_API_PORT=str(self.port), LUMEN_SIM_DIR=LUMEN_SIM_DIR)
        env.setdefault("JAVA_TOOL_OPTIONS", "-Xmx1536m")
        cmd = [UINAV, "--session", name, "launch", "--controller", "pty", "--controller-park", PARK,
               "--config", stage(os.path.join(tmp, "stage-" + name)), "--openpnp-jar", os.path.abspath(JAR)]
        for x in XML_SETS:
            cmd += ["--xml-set", x]
        out = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=300)
        if out.returncode != 0:
            subprocess.run([UINAV, "--session", name, "stop"], capture_output=True, timeout=60)
            raise RuntimeError("uinav launch failed: %s %s" % (out.stdout[-2000:], out.stderr[-2000:]))
        try:  # setUpClass failing skips tearDownClass: stop the session here
            self.info = json.loads(out.stdout)
            self.dir = uinav.session_dir(name)
            self.session = uinav.read_json(os.path.join(self.dir, "session.json"))
            self.lumen = self.info["lumen_sim"]
            self.ui = uinav.Client(session=name, timeout=120)
            self.base = "http://127.0.0.1:%d" % self.port
            for _ in range(240):
                try:
                    self.api("GET", "/api/status")
                    break
                except Exception:
                    time.sleep(0.5)
            else:
                raise RuntimeError("web API did not come up on %d" % self.port)
        except BaseException:
            self.stop()
            raise

    def api(self, method, path, body=None, timeout=60):
        """(HTTP status, JSON body)."""
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            payload = e.read()
            try:
                return e.code, json.loads(payload or b"{}")
            except ValueError:
                return e.code, {"raw": payload.decode(errors="replace")}

    def status(self):
        return self.api("GET", "/api/status")[1]

    def wait_status(self, pred, timeout, what):
        end = time.time() + timeout
        last = None
        while time.time() < end:
            last = self.status()
            if pred(last):
                return last
            time.sleep(0.2)
        raise AssertionError("timed out waiting for %s; last machine: %s" % (what, json.dumps(last.get("machine"))[:600]))

    def controller(self, *args):
        out = subprocess.run([UINAV, "--session", self.name, "controller", *args], capture_output=True, text=True,
                             timeout=30)
        return json.loads(out.stdout)

    def sim(self):
        return self.controller("state")["state"]

    def io(self, since=0):
        """lumen-sim's io log as (t, dir, line) from entry `since`, and the next entry number."""
        r = self.ui.gcode(since=since, all=True)
        return [(e["t"] / 1000.0, e["dir"], e["line"]) for e in r["entries"]], r["next"]

    def openpnp_log(self):
        with open(os.path.join(self.session["config_dir"], "log", "OpenPnP.log"), errors="replace") as f:
            return f.read()

    def java_ttys(self):
        """The terminal devices OpenPnP's JVM holds open (from /proc, nothing is opened)."""
        fd_dir = "/proc/%d/fd" % self.session["java_pid"]
        out = set()
        for fd in os.listdir(fd_dir):
            try:
                target = os.readlink(os.path.join(fd_dir, fd))
            except OSError:
                continue
            if target.startswith("/dev/") and ("tty" in target or "/pts/" in target or "serial" in target):
                out.add(target)
        return out

    def keep_logs(self, label):
        if not OUT:
            return
        os.makedirs(OUT, exist_ok=True)
        for src, name in ((self.lumen["io_log"], "lumen-sim-io.log"),
                          (os.path.join(self.session["config_dir"], "log", "OpenPnP.log"), "OpenPnP.log")):
            if os.path.exists(src):
                shutil.copy2(src, os.path.join(OUT, "%s-%s" % (label, name)))

    def stop(self):
        subprocess.run([UINAV, "--session", self.name, "stop"], capture_output=True, timeout=120)


def record(key, value):
    if not OUT:
        return
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "results.json")
    data = {}
    if os.path.exists(path):
        with open(path) as f:
            data = json.load(f)
    data[key] = value
    with open(path, "w") as f:
        json.dump(data, f, indent=2)


def lines_sent(io, text):
    return [t for t, d, l in io if d == ">" and l.split(";", 1)[0].strip() == text]


class SessionTest(unittest.TestCase):
    NAME = None

    @classmethod
    def setUpClass(cls):
        reason = why_skip()
        if reason:
            raise unittest.SkipTest(reason)
        cls.tmp = tempfile.mkdtemp(prefix="lumen-e2e-")
        try:
            cls.s = PtySession(cls.NAME, cls.tmp)
        except BaseException:
            shutil.rmtree(cls.tmp, ignore_errors=True)
            raise

    @classmethod
    def tearDownClass(cls):
        try:
            cls.s.keep_logs(cls.NAME)
            cls.s.stop()
        finally:
            shutil.rmtree(cls.tmp, ignore_errors=True)

    def check_safe_copy(self):
        """The copy cannot reach the real board: one port-name, resolving to lumen-sim's pty; no USB identity."""
        with open(os.path.join(self.s.session["config_dir"], "machine.xml")) as f:
            text = f.read()
        for word in ("usb-serial-number", "usb-description", "387932683335", "/dev/serial"):
            self.assertNotIn(word, text)
        ports = re.findall(r'port-name="([^"]*)"', text)
        self.assertEqual(ports, [self.s.lumen["link"]])
        self.assertRegex(os.path.realpath(ports[0]), r"^/dev/pts/\d+$")

    def enable(self):
        code, r = self.s.api("POST", "/api/machine/enable", timeout=120)
        self.assertEqual(code, 200, r)
        self.s.wait_status(lambda st: st["machine"]["enabled"] and st["machine"]["connected"], 30, "enabled")
        ttys = self.s.java_ttys()
        self.assertEqual(ttys, {os.path.realpath(self.s.lumen["link"])}, "OpenPnP holds %s" % ttys)
        return r


class HomeAndPrimeTest(SessionTest):
    """(a) then (c) in one session (the prime needs a homed machine)."""
    NAME = "lumen-e2e-home"

    def test_1_enable_and_home_axes_match_lumen_sim(self):
        self.check_safe_copy()
        st = self.s.status()
        self.assertFalse(st["server"]["simulatorConfirmed"])  # SimGuard: a serial board, i.e. a real machine
        self.enable()
        n0 = self.s.io()[1]
        t0 = time.time()
        code, r = self.s.api("POST", "/api/machine/home", {"wait": True}, timeout=180)
        home_s = time.time() - t0
        self.assertEqual(code, 200, r)
        self.assertTrue(r.get("homed"), r)
        self.s.wait_status(lambda x: x["machine"]["homed"] and not x["machine"]["busy"], 60, "homed and idle")
        io, _ = self.s.io(n0)
        self.assertTrue(lines_sent(io, "G28"), "no G28 sent")
        self.assertTrue(lines_sent(io, "G92 A0 B0"), "no G92 A0 B0 after G28")
        sim = self.s.sim()
        self.assertEqual(sim["homed"], {"X": True, "Y": True, "Z": True})
        self.assertFalse(sim["halted"])
        self.assertFalse(sim["moving"])
        head = self.s.ui.state()["heads"][0]
        top = next(c for c in head["cameras"] if c["name"] == "Top")["location"]
        n1 = next(n for n in head["nozzles"] if n["name"] == "N1")["location"]
        n2 = next(n for n in head["nozzles"] if n["name"] == "N2")["location"]
        lg = sim["logical"]
        openpnp = {"X": top["x"], "Y": top["y"], "Z": n1["z"], "A": n1["rotation"], "B": n2["rotation"]}
        record("a_home", {"seconds": round(home_s, 2), "openpnp_axes": openpnp, "lumen_sim_logical": lg,
                          "lumen_sim_physical": sim["physical"], "counts": sim["counts"]})
        for axis in ("X", "Y", "Z"):  # the Top camera has no head offset; N1 rides Z directly
            self.assertAlmostEqual(openpnp[axis], lg[axis], delta=0.01, msg=axis)
        self.assertAlmostEqual((openpnp["A"] - lg["A"] + 180) % 360 - 180, 0, delta=0.01, msg="A")
        self.assertAlmostEqual(openpnp["B"], lg["B"], delta=0.01, msg="B (N2 rotation = the screw)")
        self.assertAlmostEqual(n2["z"], 63.0 - lg["Z"], delta=0.01, msg="N2 = 63 - Z (see-saw)")
        for i, axis in enumerate("XYZ"):
            self.assertAlmostEqual(sim["physical"][i], lg[axis], delta=0.01, msg="physical " + axis)

    def test_2_prime_30_turns_times_out_on_G4(self):
        st = self.s.status()
        if not st["machine"]["homed"]:
            self.enable()
            self.assertEqual(self.s.api("POST", "/api/machine/home", {"wait": True}, timeout=180)[0], 200)
        tip = st["dispense"]["tip"]["key"]
        token = st["dispense"]["needle"].get("changedAtMs")
        setup = [
            ("POST", "/api/needle/anchor", {"surfaceZ": 5.5, "needleContactRawSum": 65.59, "needleChangedAtMs": token,
                                            "note": "lumen-sim e2e: the bench relation K"}),
            ("POST", "/api/needle/offset", {"offsetMm": -2.59, "needleChangedAtMs": token,
                                            "note": "lumen-sim e2e: the frame offset confirmed"}),
            ("PUT", "/api/surfaces/blank", {"points": BLANK_PROBES, "note": "lumen-sim e2e: the bench blank"}),
            ("PUT", "/api/locations/blank", BLANK),
            ("PUT", "/api/locations/waste", WASTE),
            ("PUT", "/api/config", {"cleanPadCheck": {"enabled": False}, "primeRotationDeg": PRIME_DEG}),
            ("PUT", "/api/config", {"profile": {"screwRpm": 180.0, "dwellMs": 250}, "forTip": tip}),
            ("POST", "/api/screw/start-angle", {"note": "lumen-sim e2e: confirmed after connect"}),
        ]
        for method, path, body in setup:
            code, r = self.s.api(method, path, body)
            self.assertEqual(code, 200, "%s %s -> %s" % (method, path, json.dumps(r)[:600]))
        n0 = self.s.io()[1]
        code, r = self.s.api("POST", "/api/calibrations/touch-off", {"primeAtWaste": True, "wait": True}, timeout=300)
        self.assertEqual(code, 200, r)
        run = r["result"]["run"]
        io, _ = self.s.io(n0)
        record("c_prime", {"run": {k: run.get(k) for k in ("kind", "status", "message", "linesSent", "totalSeconds")},
                           "stream": ["%.3f %s %s" % x for x in io if x[1] != "<" or not x[2].startswith("Cap:")]})
        # This test is in CI's gate, so it must not block the engine fix (chunked primes, BR-30). Two outcomes
        # pass: the run completes with no line timing out (fixed), or it fails with exactly the real board's
        # timeout below (the known bug, reproduced). Anything else fails. lumen-sim's own pty-level test
        # (tests/test_repro.py PrimeTimeout) keeps proving the controller side of the reproduction.
        if run["status"] == "OK":
            record("c_prime_outcome", "fixed: the 30-turn prime ran without a timeout")
            self.assertNotIn("timeout waiting for response", self.s.openpnp_log())
            return
        record("c_prime_outcome", "known bug reproduced: timeout waiting for response to G4 P250")
        self.assertEqual(run["status"], "ERROR", run)
        self.assertRegex(run["message"], r"^GcodeDriver:.*ttyLUMEN timeout waiting for response to G4 P250$")
        self.assertIn("timeout waiting for response to G4 P250", self.s.openpnp_log())
        # the screw move: 30 turns plus the phase, at 180 rpm with the engine's M204 S20000
        moves = [(t, l) for t, d, l in io if d == ">" and l.startswith("G1 B")]
        self.assertEqual(len(moves), 1, moves)
        self.assertRegex(moves[0][1], r"^G1 B108\d\d\.\d+ F64800$")
        g4 = [i for i, (t, d, l) in enumerate(io) if d == ">" and l == "G4 P250"]
        self.assertEqual(len(g4), 1)
        t_g4, after = io[g4[0]][0], io[g4[0] + 1:]  # by position: the G1's ok can share the G4's millisecond
        i_abort = next(i for i, (t, d, l) in enumerate(after) if d == ">" and l == "M410")
        # nothing but busy lines answers the G4 for 10 s; then the engine aborts with the Stop lines
        self.assertEqual({l for t, d, l in after[:i_abort] if d == "<"}, {"echo:busy: processing"})
        self.assertAlmostEqual(after[i_abort][0] - t_g4, 10.0, delta=0.5)
        late_ok = next(t for t, d, l in after if d == "<" and l == "ok")
        self.assertGreater(late_ok - t_g4, 10.0)  # the G4's own ok, credited to the M410
        for line in ("M107 P3", "M107 P2", "M18 B"):
            self.assertTrue(lines_sent(after, line), line)


class StopDuringHomingTest(SessionTest):
    """(b) in its own session: the controller ends halted."""
    NAME = "lumen-e2e-stop"

    def test_stop_during_home_halts_until_power_cycle(self):
        self.check_safe_copy()
        self.enable()
        n0 = self.s.io()[1]
        res = {}

        def home():
            t = time.time()
            res["home"] = self.s.api("POST", "/api/machine/home", {"wait": True}, timeout=200)
            res["home_s"] = time.time() - t

        th = threading.Thread(target=home)
        t_home = time.time()
        th.start()
        time.sleep(4.0)
        phase = self.s.sim()["homing_phase"]
        code, stop = self.s.api("POST", "/api/stop")
        t_stop = time.time() - t_home
        self.assertEqual(code, 200, stop)
        th.join(150)
        self.assertFalse(th.is_alive(), "the home did not end")
        sim = self.s.sim()
        io, n1 = self.s.io(n0)
        log = self.s.openpnp_log()
        # the reconnect: the board does not answer
        code_re, reconnect = self.s.api("POST", "/api/machine/enable", timeout=120)
        still_halted = self.s.sim()["halted"]
        pc = self.s.controller("power-cycle")
        code_pc, after_pc = self.s.api("POST", "/api/machine/enable", timeout=120)
        io_pc, _ = self.s.io(n1)
        boot_m114 = [l for t, d, l in io_pc if d == "<" and l.startswith("X:")]
        record("b_stop", {"phase_at_stop": phase, "stop_at_s": round(t_stop, 2), "stop": stop,
                          "home": res.get("home"), "home_s": round(res.get("home_s", -1), 1),
                          "halted": sim["halted"], "halt_reason": sim["halt_reason"],
                          "reconnect": [code_re, reconnect], "halted_after_reconnect": still_halted,
                          "power_cycle": pc.get("ok"), "enable_after_power_cycle": [code_pc, after_pc],
                          "m114_after_power_cycle": boot_m114[:1],
                          "io": ["%.3f %s %s" % x for x in io if not x[2].startswith("Cap:")]})
        self.assertIn(phase, ("X_APPROACH", "X_BUMP", "X_SLOW", "Y_APPROACH", "Y_BUMP", "Y_SLOW", "Z_SAFE_XY",
                              "Z_APPROACH", "Z_BUMP", "Z_SLOW"), phase)
        self.assertIn("timeout waiting for response to M410", log)
        self.assertNotEqual(res["home"][0], 200, res["home"])
        self.assertIn("homing failed", json.dumps(res["home"][1]))
        self.assertGreater(res["home_s"], 55)  # G28 is sent with OpenPnP's 60 s 'infinite' timeout
        self.assertTrue(sim["halted"], sim["halt_reason"])
        self.assertEqual(sim["halt_reason"], "Homing Failed")
        m410 = [i for i, (t, d, l) in enumerate(io) if d == ">" and l == "M410"]
        self.assertEqual(len(m410), 1)
        self.assertFalse([l for t, d, l in io[m410[0] + 1:] if d == "<"], "the halted board answered")
        self.assertFalse(lines_sent(io, "M107 P3"), "the rest of DISABLE_COMMAND is never sent")
        self.assertNotEqual(code_re, 200, reconnect)
        self.assertIn("timeout waiting for response to G21", json.dumps(reconnect))
        self.assertTrue(still_halted)
        self.assertTrue(pc.get("ok"), pc)
        self.assertEqual(code_pc, 200, after_pc)
        self.assertTrue(after_pc.get("enabled"), after_pc)
        # a power cycle is the stock boot: X Y Z 0, A B 720, the screw's count lost
        self.assertTrue(boot_m114 and boot_m114[0].startswith("X:0.00 Y:0.00 Z:0.00 A:720.00 B:720.00 Count"),
                        boot_m114)
        self.assertEqual(self.s.java_ttys(), {os.path.realpath(self.s.lumen["link"])})


if __name__ == "__main__":
    # With LUMEN_SIM_E2E=1 (CI's controller-e2e job) a skip means a miswired job, not a pass.
    result = unittest.main(exit=False).result
    sys.exit(0 if result.wasSuccessful() and not (os.environ.get("LUMEN_SIM_E2E") == "1" and result.skipped) else 1)
