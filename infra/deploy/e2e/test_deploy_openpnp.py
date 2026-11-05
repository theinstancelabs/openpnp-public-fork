"""INF-02 end to end: il_deploy with the real OpenPnP build and lumen-sim, the simulated LumenPnP controller.

tests/test_acceptance.py proves the deploy flow against a fake OpenPnP. This runs the same flow against the real
jar, which connects over its real serial stack (jSerialComm) to lumen-sim's pty. It needs no openpnp-uinav
(private), so it runs on a stock GitHub runner. The steps, as on the lab computer:

  1. Two releases in CI's exact format (infra/ci/make_release.py, CI's command line) from the one jar. The jar's
     build sha7 (manifest Implementation-Version, which OpenPnP reports at the end of server.version) starts both
     fake commits' shas, so il_deploy's version check runs unchanged.
  2. The fake GitHub of the unit tests (tests/fakes.py) serves them as `gh release create` publishes them, with
     green `gate` checks in their runs' check suites and each tarball attested by its run, which il_deploy's real
     GhAttestation verifies through the unit tests' fake `gh` (FakeGhCli: `gh attestation verify`'s policy and JSON).
  3. state/config comes from `il_deploy.py init --seed-from` a copy of infra/fixtures/lab-config (boards.xml empty)
     and the lab-instancelabs engine config, with one place, one surface and a needle anchor set two hours before
     (so /api/status shows their setAtMs keys). machine.xml is tools/instancelabs/make_machine_patch.py's output,
     rewritten for the simulator:
     - the GcodeDriver's port-name is the lumen-sim link (ttyLUMEN*);
     - usb-serial-number and usb-description are removed everywhere;
     - the head's visual homing is off (no camera sees a homing fiducial);
     - the two OpenPnpCaptureCameras become an ImageCamera (Top) and a SimulatedUpCamera (Bottom).
     Java starts only if every port-name resolves to lumen-sim's /dev/pts/N and no USB identity or capture
     camera is left.
  4. lumen-sim on a link in the temp dir, and Xvfb on a free display in :60-:69.
  5. il_deploy.py in-process (main(argv, deps): only GitHub and gh are faked). It uses the production OpenPnP command
     line (flock + java + the unit's flags) under --service-manager subprocess, a temp STATE.md (Holder: nobody), and a
     temp lock, activity file and root. The device is the lumen-sim link. A pty has no USB identity, so --usb-sn '' and
     --serial-match names the link: the health check's serial step runs against the real connect line
     (SerialPortCommunications.resolvePort, by port name).
  6. test_1: poll, deploy; OpenPnP started; run (poll + deploy of the newer build over the running OpenPnP);
     health by hand on that OpenPnP, whose link the STOP left open (connection-keep-alive): no new connect line, so
     the serial step takes its kept-open fallback; rollback. Before each `run`, the person who ran a command by
     hand is taken to have left (person.json dated back past the quiet period).
     test_2: lumen-sim power-cycled and a third build: the deploy must not home the reset controller (exit 5, the
     previous build back unchecked, the hold set), then `resume`.
     test_3 (BR-44): the controller power-cycled again while OpenPnP runs with its link kept open; a health check by
     hand: Start finds the link dead and connects again (a new connect line for the serial step), and the reset
     controller is not homed.

Checked: rc of each step, the current/previous links, deployed.json, bad.json, the server.version each health check
saw, STATE.md's "Deployed on the machine" line and Holder, the deploy.jsonl records, and lumen-sim's I/O log. The
log must show one G28 per health check, each answered before any M410 (a STOP inside G28 hangs the board), and
make_machine_patch's DISABLE_COMMAND lines after each home; after the power cycle no G0/G1/G28 at all. lumen-sim
must end with no model errors and not halted. And the real /api/status has every key path il_deploy reads
(il_deploy.STATUS_FIELDS; a missing time is skipped there, so a backend rename would silently turn a check off), with
the health check's home in machine.zCheck.homeAtMs, inside il_deploy's own window. The paths it could not check
(il_deploy.unchecked_status_fields: behind a null, or in an empty map) must be exactly ENGINE_TASK_FIELDS, which only an
engine run or a pad calibration fills in; this test drives neither (Backend's contract test covers them). BR-44's
fields: dispense.state 'running', machine.homing false, and the pressure hold's first stamp within
il_deploy.STARTUP_STAMP_S of server.startedAtMs (results.json keeps the gap measured).

Why the cameras are swapped. With no camera device, OpenPnP would start with the fixture's OpenPnpCaptureCameras:
open() only logs "No camera found with ID ..." and returns (OpenPnpCaptureCamera.java:174), and the native library
loaded at construction needs only libstdc++ and libc. But the fixture's unique-ids are the lab's own Top and Bottom
cameras, so on the lab computer OpenPnP would open the real cameras, which another role may be using. With the
swap, the test opens no camera on any host.

Skipped unless IL_DEPLOY_E2E=1, and IL_DEPLOY_E2E_JAR and IL_DEPLOY_E2E_LIB name a built jar and its lib/
directory. Run as a script (CI), a skip then exits 1. About 3 minutes. From the repository root:

    mvn -B -T1 -DskipTests package
    IL_DEPLOY_E2E=1 IL_DEPLOY_E2E_JAR=target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar IL_DEPLOY_E2E_LIB=target/lib \\
        python3 infra/deploy/e2e/test_deploy_openpnp.py -v

Env: IL_DEPLOY_E2E_OUT (keeps the logs and results.json there), IL_DEPLOY_E2E_JAVA (default: java on PATH).
Ports 18930-18949 and X displays :60-:69 only; one OpenPnP, stopped (and lumen-sim and Xvfb) at the end. It never
opens a real serial device, a camera, the real STATE.md or a host deploy root.
"""
import atexit
import contextlib
import hashlib
import io
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
DEPLOY_DIR = os.path.dirname(HERE)
INFRA = os.path.dirname(DEPLOY_DIR)
REPO = os.path.dirname(INFRA)
LUMEN_SIM_DIR = os.path.join(INFRA, "lumen-sim")
MAKE_RELEASE = os.path.join(INFRA, "ci", "make_release.py")
PATCH_TOOL = os.path.join(REPO, "tools", "instancelabs", "make_machine_patch.py")
LAB_CONFIG = os.path.join(INFRA, "fixtures", "lab-config")
ENGINE_CONFIG = os.path.join(INFRA, "fixtures", "lab-instancelabs", "config", "dispense-config.json")
for p in (DEPLOY_DIR, os.path.join(DEPLOY_DIR, "tests"), LUMEN_SIM_DIR, os.path.dirname(PATCH_TOOL)):
    if p not in sys.path:
        sys.path.insert(0, p)
import il_deploy  # noqa: E402
import make_machine_patch  # noqa: E402
from fakes import BRANCH, REPO as GH_REPO, STATE_MD, FakeGhCli, FakeGitHub  # noqa: E402
from lumen_sim.hostclient import DriverClient  # noqa: E402

E2E = os.environ.get("IL_DEPLOY_E2E") == "1"
JAR = os.path.abspath(os.environ["IL_DEPLOY_E2E_JAR"]) if os.environ.get("IL_DEPLOY_E2E_JAR") else ""
LIB = os.path.abspath(os.environ["IL_DEPLOY_E2E_LIB"]) if os.environ.get("IL_DEPLOY_E2E_LIB") else ""
JAVA = os.environ.get("IL_DEPLOY_E2E_JAVA") or shutil.which("java") or ""
OUT = os.environ.get("IL_DEPLOY_E2E_OUT")
API_PORTS = range(18930, 18950)
DISPLAYS = range(60, 70)

LINK_NAME = "ttyLUMEN0"
PTS = re.compile(r"^/dev/pts/\d+$")
USB_IDENTITY = ("usb-serial-number", "usb-description")
CAPTURE_CAMERA = "org.openpnp.machine.reference.camera.OpenPnpCaptureCamera"
IMAGE_CAMERA = "org.openpnp.machine.reference.camera.ImageCamera"
UP_CAMERA = "org.openpnp.machine.reference.camera.SimulatedUpCamera"
# OpenPnpCaptureCamera's own XML fields (OpenPnpCaptureCamera.java:50-96). Every other name on the fixture's cameras
# is ReferenceCamera's, which ImageCamera and SimulatedUpCamera extend too; machine.xml is read in strict mode.
CAPTURE_ATTRIBUTES = ("unique-id", "format-id", "freeze-properties")
CAPTURE_ELEMENTS = ("back-light-compensation", "brightness", "contrast", "exposure", "focus", "gain", "gamma", "hue",
                    "power-line-frequency", "saturation", "sharpness", "white-balance", "zoom")
IMAGE_SOURCE = "classpath://samples/pnp-test/pnp-test.png"
DISABLE_LINES = make_machine_patch.DISABLE_LINES  # the Stop sequence OpenPnP sends on every disable
PLANNED_LINES = [line for line in DISABLE_LINES if line != "M18 B"]  # BR-75: the screw's motor stays on
# Key paths il_deploy reads that /api/status has only after an engine run (RunTracker.snapshot, DispenseService.lastRun)
# or a pad calibration (PadCalibrationJob.view): null here, so not checked by this test (Backend's contract test)
ENGINE_TASK_FIELDS = ("run.finishedAtMs", "lastRun.endedAtMs", "padCalibration.endedAtMs", "run.running",
                      "padCalibration.state")
SEEDED_AGO_S = 2 * 3600  # the seeded place, surface and anchor: set long before the quiet period


def why_skip():
    if not E2E:
        return "set IL_DEPLOY_E2E=1 (starts the real OpenPnP and lumen-sim; about 2 minutes)"
    if not (JAR and os.path.isfile(JAR)):
        return "set IL_DEPLOY_E2E_JAR to the built OpenPnP jar (got %r)" % JAR
    if not (LIB and os.path.isdir(LIB)):
        return "set IL_DEPLOY_E2E_LIB to the jar's lib/ directory (got %r)" % LIB
    missing = [n for n, p in (("java", JAVA), ("Xvfb", shutil.which("Xvfb")), ("/usr/bin/flock",
                                                                             shutil.which("/usr/bin/flock"))) if not p]
    if missing:
        return "not found: " + ", ".join(missing)
    if not sys.platform.startswith("linux"):
        return "lumen-sim needs Linux ptys"
    return None


# ------------------------------------------------------------------------------------------------
# The config: the lab's, on lumen-sim
# ------------------------------------------------------------------------------------------------
def rewrite_for_simulator(root, link):
    """machine.xml (an ElementTree root) for lumen-sim on link: the one GcodeDriver on serial at link, no USB
    identity, visual homing off, the capture cameras swapped for simulated ones. ValueError if the config has a
    driver or camera this does not know how to make safe."""
    drivers = list(root.iter("driver"))
    if len(drivers) != 1 or not drivers[0].get("class", "").endswith(("GcodeDriver", "GcodeAsyncDriver")):
        raise ValueError("lumen-sim is one controller: expected one GcodeDriver, found %s"
                         % [d.get("class") for d in drivers])
    serial = drivers[0].find("serial")
    if serial is None:
        raise ValueError("the GcodeDriver has no <serial> settings")
    drivers[0].set("communications", "serial")
    serial.set("port-name", link)
    for el in root.iter():
        for a in USB_IDENTITY:
            el.attrib.pop(a, None)
    for head in root.iter("head"):
        head.set("visual-homing-method", "None")
    for cams in root.iter("cameras"):
        for cam in cams.findall("camera"):
            cls = cam.get("class")
            if cls in (IMAGE_CAMERA, UP_CAMERA):
                continue
            if cls != CAPTURE_CAMERA:
                raise ValueError("camera %s: do not know how to simulate %s" % (cam.get("name"), cls))
            for a in CAPTURE_ATTRIBUTES:
                cam.attrib.pop(a, None)
            for ch in [c for c in cam if c.tag in CAPTURE_ELEMENTS]:
                cam.remove(ch)
            if cam.get("looking") == "Up":
                cam.set("class", UP_CAMERA)
            else:
                cam.set("class", IMAGE_CAMERA)
                ET.SubElement(cam, "source-uri").text = IMAGE_SOURCE


def config_problems(root, link):
    """Why this machine.xml could reach real hardware ([] if it cannot): every port-name is the lumen-sim link and
    resolves to /dev/pts/N, every driver is on serial (a TCP driver could reach a bridge to the real board), no
    USB identity (the port matcher would look for the real board) and no capture camera is left."""
    out = []
    for el in root.iter():
        out += ["<%s> carries %s=%r" % (el.tag, a, el.get(a)) for a in USB_IDENTITY if a in el.attrib]
        port = el.get("port-name")
        if port is not None and not (port == link and os.path.basename(port).startswith("ttyLUMEN")
                                     and PTS.match(os.path.realpath(port))):
            out.append("port-name %r is not lumen-sim's link %s (-> /dev/pts/N)" % (port, link))
    for d in root.iter("driver"):
        if d.get("communications") != "serial":
            out.append("driver %s communicates over %s" % (d.get("id"), d.get("communications")))
    for cam in root.iter("camera"):
        if cam.get("class") not in (IMAGE_CAMERA, UP_CAMERA):
            out.append("camera %s is a %s" % (cam.get("name"), cam.get("class")))
    return out


def make_seed_config(dest, link):
    """The lab config for lumen-sim in dest (what `il_deploy.py init --seed-from` copies into state/config)."""
    shutil.copytree(LAB_CONFIG, dest)
    with open(os.path.join(dest, "boards.xml"), "w") as f:
        f.write("<openpnp-boards>\n</openpnp-boards>\n")
    machine = os.path.join(dest, "machine.xml")
    subprocess.run([sys.executable, "-I", PATCH_TOOL, "--in", os.path.join(LAB_CONFIG, "machine.xml"), "--out",
                    machine], check=True, capture_output=True, timeout=60)
    tree = ET.parse(machine)
    rewrite_for_simulator(tree.getroot(), link)
    tree.write(machine, encoding="UTF-8", xml_declaration=True)
    os.makedirs(os.path.join(dest, "instancelabs"))
    with open(ENGINE_CONFIG) as f:
        engine = json.load(f)
    # one of each named or nullable entry il_deploy reads a time from (DispenseConfig: NamedLocation, SurfaceMap,
    # NeedleAnchor), so the real /api/status shows their keys; set long ago, they are no one's recent work
    at = int((time.time() - SEEDED_AGO_S) * 1000)
    engine["locations"] = dict(engine.get("locations") or {}, e2e_place={"x": 100.0, "y": 100.0, "z": -5.0,
                                                                         "setAtMs": at, "homeAtMs": None})
    engine["surfaces"] = dict(engine.get("surfaces") or {}, e2e_surface={
        "points": [{"x": 100.0, "y": 100.0, "z": -10.0}, {"x": 140.0, "y": 100.0, "z": -10.0},
                   {"x": 100.0, "y": 140.0, "z": -10.0}], "setAtMs": at, "homeAtMs": None, "note": "deploy e2e",
        "simulated": True})
    engine["needleAnchor"] = {"surfaceZMm": -10.0, "needleContactZMm": -12.5, "xMm": 100.0, "yMm": 100.0, "setAtMs": at}
    with open(os.path.join(dest, "instancelabs", "dispense-config.json"), "w") as f:
        json.dump(engine, f, indent=1)


def jar_build_sha7(jar):
    """The sha7 OpenPnP reports at the end of server.version: the manifest's Implementation-Version
    (<build time>.<buildNumber>, pom.xml), or None."""
    with zipfile.ZipFile(jar) as z:
        text = z.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
    text = re.sub(r"\r?\n ", "", text)  # manifest continuation lines
    m = re.search(r"^Implementation-Version: *\S*\.([0-9a-f]{7})\s*$", text, re.M)
    return m.group(1) if m else None


# ------------------------------------------------------------------------------------------------
# Processes and ports
# ------------------------------------------------------------------------------------------------

ORIGIN = "https://deploy-e2e.example.ts.net:8877"  # the real machine's tailnet origin's stand-in (BR-73)


def step(report, name):
    return next(x for x in report["steps"] if x["step"] == name)


class ScopedProcs(il_deploy.Procs):
    """The host's /proc, but only OpenPnPs whose config is under this test's directory count: another role's
    OpenPnP on the lab computer must not make this test 'not idle' (it is only read, never touched)."""

    def __init__(self, under):
        self.under = os.path.realpath(under) + "/"

    def openpnp_processes(self):
        return [p for p in super().openpnp_processes() if os.path.realpath(p[1]).startswith(self.under)]


def free_port(ports):
    for p in ports:
        with socket.socket() as s:
            try:
                s.bind(("127.0.0.1", p))
            except OSError:
                continue
            return p
    raise RuntimeError("no free port in %s-%s" % (ports[0], ports[-1]))


def start_xvfb(log):
    for n in DISPLAYS:
        if os.path.exists("/tmp/.X11-unix/X%d" % n) or os.path.exists("/tmp/.X%d-lock" % n):
            continue
        p = subprocess.Popen(["Xvfb", ":%d" % n, "-nolisten", "tcp", "-screen", "0", "1280x1024x24"], stdout=log,
                             stderr=log, stdin=subprocess.DEVNULL, start_new_session=True)
        end = time.monotonic() + 10
        while time.monotonic() < end and p.poll() is None:
            if os.path.exists("/tmp/.X11-unix/X%d" % n):
                return p, ":%d" % n
            time.sleep(0.05)
        stop_process(p)
    raise RuntimeError("could not start Xvfb on any of :%d-:%d" % (DISPLAYS[0], DISPLAYS[-1]))


def stop_process(p, timeout=10):
    if p is None or p.poll() is not None:
        return
    p.terminate()
    try:
        p.wait(timeout)
    except subprocess.TimeoutExpired:
        p.kill()
        p.wait(5)


class LumenSim:
    def __init__(self, d):
        self.link = os.path.join(d, LINK_NAME)
        self.sock = os.path.join(d, "lumen-sim.sock")
        self.io_log = os.path.join(d, "lumen-sim-io.log")
        self.err_path = os.path.join(d, "lumen-sim.err")
        with open(self.err_path, "ab") as err:
            self.proc = subprocess.Popen([sys.executable, "-m", "lumen_sim", "--link", self.link, "--control",
                                          self.sock, "--io-log", self.io_log], cwd=LUMEN_SIM_DIR,
                                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=err)
        end = time.monotonic() + 15
        while not (os.path.islink(self.link) and os.path.exists(self.sock)):
            if self.proc.poll() is not None or time.monotonic() > end:
                self.stop()
                raise RuntimeError("lumen-sim did not start: " + self.stderr())
            time.sleep(0.05)

    def ctl(self, request):
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
            s.settimeout(10)
            s.connect(self.sock)
            s.sendall((json.dumps(request) + "\n").encode())
            buf = b""
            while not buf.endswith(b"\n"):
                chunk = s.recv(65536)
                if not chunk:
                    break
                buf += chunk
        return json.loads(buf)

    def state(self):
        return self.ctl({"cmd": "state"})["state"]

    def stderr(self):
        with contextlib.suppress(OSError), open(self.err_path, errors="replace") as f:
            return f.read()[-3000:]
        return ""

    def io(self, start=0):
        """[(t, dir, line)] from line `start` of the I/O log; sent lines without their ';' comment."""
        with open(self.io_log, encoding="ascii", errors="replace") as f:
            rows = f.read().split("\n")[:-1]
        out = []
        for row in rows[start:]:
            t, d, line = (row.split(" ", 2) + ["", ""])[:3]
            out.append((float(t), d, line.split(";", 1)[0].strip() if d == ">" else line))
        return out

    def io_count(self):
        with open(self.io_log, encoding="ascii", errors="replace") as f:
            return f.read().count("\n")

    def stop(self):
        if self.proc.poll() is None:
            with contextlib.suppress(OSError, ValueError):
                self.ctl({"cmd": "stop"})
            try:
                self.proc.wait(10)
            except subprocess.TimeoutExpired:
                stop_process(self.proc)


def sent(io_rows):
    return [(i, line) for i, (t, d, line) in enumerate(io_rows) if d == ">"]


# ------------------------------------------------------------------------------------------------
# The test
# ------------------------------------------------------------------------------------------------
class DeployRealOpenPnP(unittest.TestCase):
    """One lab: the steps build on each other, so test_1 runs before test_2."""

    @classmethod
    def setUpClass(cls):
        reason = why_skip()
        if reason:
            raise unittest.SkipTest(reason)
        cls.timings = {}
        cls.measured = {}
        cls.dir = tempfile.mkdtemp(prefix="il-deploy-e2e-")
        cls.xvfb = cls.sim = cls.svc_cfg = None
        atexit.register(cls._kill_leftovers)
        try:
            cls._set_up()
        except BaseException:
            cls._tear_down()
            raise

    @classmethod
    def _set_up(cls):
        d = cls.dir
        cls.root = os.path.join(d, "deploy")
        cls.state_md = os.path.join(d, "STATE.md")
        cls.lock = os.path.join(d, "lumen-serial.lock")
        cls.activity = os.path.join(d, "serial.log")
        with open(cls.state_md, "w") as f:
            f.write(STATE_MD)
        with open(cls.activity, "w") as f:
            f.write("bench tools' serial log: quiet for an hour\n")
        old = time.time() - 3600
        os.utime(cls.activity, (old, old))

        cls.xvfb_log = open(os.path.join(d, "xvfb.log"), "ab")
        cls.xvfb, cls.display = start_xvfb(cls.xvfb_log)
        cls.sim = LumenSim(d)
        # Marlin boots at X/Y/Z 0, A and B 720: il_deploy (and BR-02) read that as a power cycle and never home it
        # (test_2). The lab controller at deploy time has run since its power-on and was homed by an earlier session:
        # G28 then G92 A0 B0, as the lab's patched HOME_COMMAND does (lumen-sim RESEARCH.md section 1; INF-10: a G92 B0
        # alone left it unhomed at Z 0).
        c = DriverClient(cls.sim.link)
        try:
            c.send("G28", timeout_ms=60000)
            c.send("G92 A0 B0")
        finally:
            c.close()
        cls.io_start = cls.sim.io_count()

        cls.sha7 = jar_build_sha7(JAR)
        if not cls.sha7:
            raise RuntimeError("%s has no Implementation-Version ending in a sha7 (built outside git?)" % JAR)
        gh = cls.gh = FakeGitHub()
        cls.gh_cli = FakeGhCli(gh)
        base = gh.commit("e2e-base")
        cls.sha_a = gh.commit("e2e-a", base, sha=cls.sha7 + hashlib.sha1(b"e2e-a").hexdigest()[:33])
        cls.sha_b = gh.commit("e2e-b", cls.sha_a, sha=cls.sha7 + hashlib.sha1(b"e2e-b").hexdigest()[:33])
        gh.head = cls.sha_a
        cls.rel_a = cls._make_release(41, cls.sha_a)
        cls.rel_b = cls._make_release(42, cls.sha_b)
        cls.tag_a = "il-build-41-" + cls.sha7
        cls.tag_b = "il-build-42-" + cls.sha7

        cls.seed = os.path.join(d, "seed-config")
        make_seed_config(cls.seed, cls.sim.link)
        problems = config_problems(ET.parse(os.path.join(cls.seed, "machine.xml")).getroot(), cls.sim.link)
        if problems:
            raise RuntimeError("the config could reach real hardware: " + "; ".join(problems))
        cls.port = free_port(API_PORTS)
        cls.svc_cfg = il_deploy.config_from_args(cls.argv("status"))

    @classmethod
    def _make_release(cls, run, sha):
        """CI's release job, command line as in .github/workflows/ci.yml."""
        out = os.path.join(cls.dir, "release-%d" % run)
        url = "https://github.com/%s/actions/runs/%d" % (GH_REPO, 9000 + run)
        subprocess.run([sys.executable, "-I", MAKE_RELEASE, "--jar", JAR, "--lib", LIB,
                        "--tag", "il-build-%d-%s" % (run, sha[:7]), "--sha", sha, "--branch", BRANCH,
                        "--run-id", str(9000 + run), "--run-number", str(run), "--run-url", url, "--out", out],
                       check=True, capture_output=True, timeout=300)
        return out

    @classmethod
    def argv(cls, *cmd):
        return list(cmd) + [
            "--root", cls.root, "--state-md", cls.state_md, "--lock", cls.lock, "--device", cls.sim.link,
            "--activity", cls.activity, "--quiet-min", "15", "--api-port", str(cls.port), "--usb-sn", "",
            "--serial-match", "connecting to " + re.escape(cls.sim.link) + " by ",
            "--service-manager", "subprocess", "--java", JAVA, "--display", cls.display,
            "--status-timeout", "180", "--home-timeout", "150", "--home-busy-wait", "90", "--stop-timeout", "20",
            "--poll-interval", "0.25", "--trusted-origin", ORIGIN]

    @classmethod
    def service(cls):
        return il_deploy.make_service(cls.svc_cfg)

    @classmethod
    def _tear_down(cls):
        try:
            if cls.svc_cfg is not None:
                cls.service().stop()  # STOP through the API first (pre_stop), then SIGTERM, SIGKILL after 20 s
        finally:
            if cls.sim is not None:
                cls.sim.stop()
            stop_process(cls.xvfb)
            if getattr(cls, "xvfb_log", None):
                cls.xvfb_log.close()
            cls._keep_logs()
            shutil.rmtree(cls.dir, ignore_errors=True)

    @classmethod
    def tearDownClass(cls):
        cls._tear_down()

    @classmethod
    def _kill_leftovers(cls):
        """atexit: an interrupted run must not leave OpenPnP or lumen-sim behind."""
        if cls.svc_cfg is not None:
            for pid in cls.service().pids():  # checked against the recorded start time: never a reused pid
                with contextlib.suppress(OSError):
                    os.kill(pid, signal.SIGKILL)
        for p in (getattr(cls.sim, "proc", None), cls.xvfb):
            if p is not None and p.poll() is None:
                p.kill()

    @classmethod
    def _keep_logs(cls):
        if not OUT or not os.path.isdir(cls.dir):
            return
        os.makedirs(OUT, exist_ok=True)
        for src, name in ((os.path.join(cls.root, "log", "deploy.jsonl"), "deploy.jsonl"),
                          (os.path.join(cls.root, "log", "il-openpnp.out"), "il-openpnp.out"),
                          (os.path.join(cls.root, "state", "config", "log", "OpenPnP.log"), "OpenPnP.log"),
                          (os.path.join(cls.dir, "lumen-sim-io.log"), "lumen-sim-io.log"),
                          (os.path.join(cls.dir, "lumen-sim.err"), "lumen-sim.err"),
                          (cls.state_md, "STATE.md")):
            if os.path.exists(src):
                shutil.copy2(src, os.path.join(OUT, name))
        with open(os.path.join(OUT, "results.json"), "w") as f:
            json.dump({"timings_s": cls.timings, "sha7": getattr(cls, "sha7", None),
                       "measured": getattr(cls, "measured", {})}, f, indent=1)

    # --- helpers --------------------------------------------------------------------------------
    def main(self, *cmd, expect=0):
        """il_deploy in-process (only GitHub and gh are faked); asserts its exit code, with the logs when it differs."""
        t = time.monotonic()
        out, err = io.StringIO(), io.StringIO()
        deps = il_deploy.Deps(github=self.gh, procs=ScopedProcs(self.dir),
                              attestation=il_deploy.GhAttestation(gh="gh", runner=self.gh_cli))
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = il_deploy.main(self.argv(*cmd), deps=deps)
        self.timings["%d %s" % (len(self.timings) + 1, cmd[0])] = round(time.monotonic() - t, 1)
        self.last_stdout = out.getvalue()
        self.last_out = out.getvalue() + err.getvalue()
        if rc != expect:
            self.fail("il_deploy %s: exit %s, expected %s\n%s\n%s" % (" ".join(cmd), rc, expect, self.last_out,
                                                                       self.diagnostics()))
        return self.last_out

    def diagnostics(self):
        parts = []
        for path in (os.path.join(self.root, "log", "deploy.jsonl"), os.path.join(self.root, "log", "il-openpnp.out"),
                     os.path.join(self.root, "state", "config", "log", "OpenPnP.log")):
            with contextlib.suppress(OSError), open(path, errors="replace") as f:
                parts.append("--- %s (tail)\n%s" % (path, f.read()[-4000:]))
        parts.append("--- lumen-sim stderr\n" + self.sim.stderr())
        return "\n".join(parts)

    def journal(self):
        with open(os.path.join(self.root, "log", "deploy.jsonl")) as f:
            return [json.loads(line) for line in f if line.strip()]

    def link(self, name):
        p = os.path.join(self.root, name)
        return os.path.basename(os.readlink(p)) if os.path.islink(p) else None

    def state_text(self):
        with open(self.state_md) as f:
            return f.read()

    def holder(self):
        return il_deploy.parse_holder(self.state_text())

    def deployed(self):
        return il_deploy.read_json(os.path.join(self.root, "deployed.json"))

    def last_health(self):
        return [r for r in self.journal() if r["event"] == "health"][-1]

    def wait_api(self, timeout=180):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            with contextlib.suppress(OSError, ValueError):
                with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(
                        "http://127.0.0.1:%d/api/status" % self.port, timeout=5) as r:
                    if r.status == 200:
                        return json.load(r)
            time.sleep(0.5)
        self.fail("OpenPnP's API did not answer on %d within %d s\n%s" % (self.port, timeout, self.diagnostics()))

    def age_openpnp_log(self):
        """OpenPnP started by a person (as the unit after `systemctl --user start`): an hour ago, not now. Its log is an
        activity file (the quiet period runs from its last line): wait until it is quiet, then date it back."""
        log = os.path.join(self.root, "state", "config", "log", "OpenPnP.log")
        last, end = None, time.monotonic() + 30
        while time.monotonic() < end:
            st = os.stat(log)
            if last == (st.st_size, st.st_mtime_ns) and time.time() - st.st_mtime > 2:
                break
            last = (st.st_size, st.st_mtime_ns)
            time.sleep(0.5)
        old = time.time() - 3600
        os.utime(log, (old, old))

    def assert_status_fields(self):
        """Every key path il_deploy reads in the real /api/status is there (null is fine), the only ones it could not
        check are ENGINE_TASK_FIELDS, and the health check's home is in machine.zCheck.homeAtMs, inside il_deploy's own
        window (what keeps it from counting as a person's)."""
        st = self.wait_api()
        self.assertEqual(il_deploy.status_field_problems(st), [], json.dumps(st)[:4000])
        self.assertEqual(sorted(il_deploy.unchecked_status_fields(st)), sorted(ENGINE_TASK_FIELDS),
                         "a key path il_deploy reads was not there to check: seed it (make_seed_config) or drive what "
                         "sets it\n" + json.dumps(st.get("dispense"))[:3000])
        seeded = [ms for what, ms in il_deploy.api_activity_times(st)
                  if what in ("place 'e2e_place' set", "surface 'e2e_surface' probed", "a needle anchor set")]
        self.assertEqual(len(seeded), 3, il_deploy.api_activity_times(st))
        homes = [ms for what, ms in il_deploy.api_activity_times(st) if what == "a home"]
        self.assertEqual(len(homes), 1, il_deploy.api_activity_times(st))
        since, until = il_deploy.read_json(os.path.join(self.root, "own-activity.json"))[-1]
        self.assertTrue(since * 1000 <= homes[0] <= until * 1000, (since, homes[0], until))
        # BR-44 on the real build: the engine runs, no home runs, and the pressure hold's first value was stamped while
        # this OpenPnP started (pressure_start_up_stamp: its text, 'pressure off', within STARTUP_STAMP_S of
        # server.startedAtMs)
        self.assertEqual((st["dispense"]["state"], st["dispense"]["startError"], st["machine"]["homing"]),
                         ("running", None, False))
        gap_ms = st["pressure"]["lastEventAtMs"] - st["server"]["startedAtMs"]
        self.measured["pressure first stamp after the JVM start (s)"] = round(gap_ms / 1000, 2)
        self.assertTrue(0 <= gap_ms <= il_deploy.STARTUP_STAMP_S * 1000, gap_ms)
        self.assertTrue(il_deploy.pressure_start_up_stamp(st), st["pressure"])

    def person_left(self):
        """The person who ran il_deploy by hand has left the machine: their note (person.json) is dated back past the
        quiet period, so the timer's run may deploy."""
        p = os.path.join(self.root, "person.json")
        r = il_deploy.read_json(p)
        self.assertTrue(r, "no person.json after a command by hand")
        il_deploy.write_json(p, dict(r, ts=r["ts"] - 16 * 60))

    def assert_deployed(self, tag, sha, action, running):
        """After a passed deploy or rollback of tag: links, records, the version the health check saw, STATE.md."""
        self.assertEqual(self.link("current"), tag)
        cur = self.deployed()["current"]
        self.assertEqual((cur["tag"], cur["sha"]), (tag, sha))
        self.assertNotIn("pending", cur)
        rep = self.last_health()
        self.assertTrue(rep["ok"], rep)
        version = rep["report"]["version"]
        self.assertTrue(version.startswith(tag + "_") and version.endswith("." + self.sha7), version)
        self.assertEqual([s["step"] for s in rep["report"]["steps"]], ["status", "origins", "enable", "serial", "axes",
                                                                        "home", "homed", "stop"])
        self.assertIn("Serial port: connecting to %s by " % self.sim.link, step(rep["report"], "serial")["detail"])
        self.assert_origins(rep["report"])
        text = self.state_text()
        self.assertIn("- **Deployed on the machine:** %s (%s, %s, CI https://github.com/%s/actions/runs/%d)"
                      % (tag, self.sha7, BRANCH, GH_REPO, 9000 + int(tag.split("-")[2])), text)
        h = self.holder()
        self.assertTrue(h.idle, h.value)
        self.assertTrue(h.value.startswith("nobody (released by Infra %s at " % action), h.value)
        self.assertFalse(os.path.exists(os.path.join(self.root, "hold.json")))
        self.assertEqual(self.service().is_active(), running)

    def assert_origins(self, report):
        """BR-73 on the real jar: its own origin passes the API's Host and Origin guards, a foreign Origin is refused.
        A jar from before BR-73 reports no trusted origins and passes with a note (a rollback to it stays possible)."""
        detail = step(report, "origins")["detail"]
        if report["trusted_origins"] is not None:
            self.assertEqual(report["trusted_origins"], [ORIGIN])
            self.assertEqual(detail, ORIGIN + ": accepted with its Host and Origin; a foreign Origin and Host refused")
        else:
            self.assertIn("before BR-73", detail)

    # --- the steps ------------------------------------------------------------------------------
    def test_1_poll_deploy_redeploy_rollback(self):
        self.main("init", "--seed-from", self.seed)
        cfg = os.path.join(self.root, "state", "config")
        self.assertEqual(config_problems(ET.parse(os.path.join(cfg, "machine.xml")).getroot(), self.sim.link), [])
        self.assertFalse(il_deploy.config_is_real(cfg), "a lumen-sim config must not count as the real machine's")

        # the first green build: poll stages it, deploy installs it (OpenPnP was not running: it is stopped after)
        self.gh.add_release_dir(self.rel_a)
        self.assertIn("poll: staged " + self.tag_a, self.main("poll"))
        lib_jar = sorted(n for n in os.listdir(LIB) if n.endswith(".jar"))[0]
        self.assertTrue(os.path.isfile(os.path.join(self.root, "releases", self.tag_a, "lib", lib_jar)))
        staged = il_deploy.read_json(os.path.join(self.root, "releases", self.tag_a, "STAGED.json"))
        self.assertIn("/actions/runs/9041/attempts/1", staged["attestation"])  # make_release's --run-id
        self.assertIn("deployed " + self.tag_a, self.main("deploy"))
        self.assert_deployed(self.tag_a, self.sha_a, "deploy", running=False)
        self.assertIsNone(self.link("previous"))

        # OpenPnP runs (as the unit after a person starts it); a newer green build lands on il/integration
        self.service().start()
        self.wait_api()
        self.age_openpnp_log()
        self.gh.head = self.sha_b
        self.gh.add_release_dir(self.rel_b)
        out = self.main("run", expect=3)  # within the quiet period after the deploy by hand
        self.assertIn("poll: staged " + self.tag_b, out)
        self.assertIn("a person ran il_deploy.py deploy", out)
        self.person_left()
        self.assertIn("deployed %s (previous %s)" % (self.tag_b, self.tag_a), self.main("run"))
        self.assert_deployed(self.tag_b, self.sha_b, "deploy", running=True)
        self.assertEqual(self.link("previous"), self.tag_a)
        self.assertIn("; previous %s. Config state: %s." % (self.tag_a, cfg), self.state_text())
        stops = [r for r in self.journal() if r["event"] == "stop"]
        self.assertTrue(stops and stops[-1]["ok"] and stops[-1]["detail"] == il_deploy.STOP_DONE, stops)
        self.assert_status_fields()  # build B runs on after its health check

        # a health check by hand on that OpenPnP: the deploy's planned stop closed its link (BR-75: keep-alive or not,
        # else OpenPnP's quit would send M18 B on it), so the enable connects again and the serial step reads that line
        self.main("health")
        rep = self.last_health()
        self.assertTrue(rep["ok"], rep)
        self.assertEqual([s["step"] for s in rep["report"]["steps"]], ["status", "origins", "enable", "serial", "axes",
                                                                        "home", "homed", "stop"])
        serial = step(rep["report"], "serial")["detail"]
        self.assertIn("Serial port: connecting to %s by " % self.sim.link, serial)
        self.assertNotIn("the link is kept open", serial)
        self.assertTrue(self.service().is_active())
        self.assertTrue(self.holder().value.startswith("nobody (released by Infra health check at "),
                        self.holder().value)

        # one step back, with the pre-deploy config snapshot
        self.assertIn("rolled back from %s to %s" % (self.tag_b, self.tag_a), self.main("rollback"))
        self.assert_deployed(self.tag_a, self.sha_a, "rollback", running=True)
        self.assertIsNone(self.link("previous"))
        self.assertEqual(self.deployed()["current"]["rolled_back_from"], self.tag_b)
        self.assertIn(self.tag_b, il_deploy.read_json(os.path.join(self.root, "bad.json")))
        self.assertIn("rolled back from %s by Infra (il_deploy.py rollback)" % self.tag_b, self.state_text())

        results = [(r["action"], r["rc"]) for r in self.journal() if r["event"] == "result"]
        self.assertEqual(results, [("deploy", 0), ("deploy", 0), ("health check", 0), ("rollback", 0)])
        self.assertEqual([r for r in self.journal() if r["event"] in ("hold", "exception", "bad")
                          and r.get("tag") != self.tag_b], [])

        # the serial traffic: one home per health check, never a STOP inside it, the planned stop after each (BR-75:
        # the Stop sequence without M18 B), and no M18 B at all: the screw keeps its timing through it all
        io_rows = self.sim.io(self.io_start)
        lines = sent(io_rows)
        homes = [i for i, line in lines if line == "G28"]
        self.assertEqual(len(homes), 4, [line for _, line in lines])
        # BR-02's hazard (INF-10): motion while the position is only believed, e.g. a Z raise before the home. After
        # each connect (CONNECT_COMMAND's G21) no G0/G1 before that connection's first G28; moves after a home are fine
        connects = [i for i, line in lines if line.split(";")[0].strip() == "G21"]
        self.assertEqual(len(connects), 4, "deploy, deploy over it, the health check by hand (the planned stop closed "
                                           "the link), rollback")
        for k, c in enumerate(connects):
            first = next((g for g in homes if g > c), None)
            end = connects[k + 1] if k + 1 < len(connects) else len(io_rows)
            self.assertTrue(first is not None and first < end, "no home after connect #%d" % (k + 1))
            moves = [line for i, line in lines if c < i < first and line.split(" ")[0] in ("G0", "G1")]
            self.assertEqual(moves, [], "motion before the home of connect #%d" % (k + 1))
        for k, g in enumerate(homes):
            ok = next((j for j in range(g + 1, len(io_rows)) if io_rows[j][1] == "<" and io_rows[j][2] == "ok"), None)
            self.assertIsNotNone(ok, "G28 #%d never answered" % (k + 1))
            self.assertEqual([io_rows[j][2] for j in range(g, ok) if io_rows[j][1] == ">" and io_rows[j][2] == "M410"],
                             [], "an M410 inside G28 #%d" % (k + 1))
            after = [line for i, line in lines if i > ok and (k + 1 == len(homes) or i < homes[k + 1])]
            self.assertTrue(any(after[i:i + len(PLANNED_LINES)] == PLANNED_LINES for i in range(len(after))),
                            "no planned stop %s after G28 #%d: %s" % (PLANNED_LINES, k + 1, after))
        self.assertEqual([line for _, line in lines if line.split(" ")[0] in ("M18", "M84")], [],
                         "a motor switched off (the screw's: M18 B)")
        st = self.sim.state()
        self.assertFalse(st["halted"], st.get("halt_reason"))
        self.assertEqual(st["sim_errors"], [])
        type(self).deployed_ok = True

    def test_2_a_power_cycled_controller_is_never_homed(self):
        """lumen-sim power-cycled (Marlin's boot position: X/Y/Z 0, A and B 720) while a newer green build lands: the deploy
        reads the controller after the enable and does not home it. The previous build goes back with its config,
        unchecked (it ran before: it runs again, not enabled), the new build is not marked bad, and the hold is set
        until a person runs `resume`. Nothing moves."""
        if not getattr(type(self), "deployed_ok", False):
            self.skipTest("needs test_1's deploys")
        start = self.sim.io_count()
        self.assertTrue(self.sim.ctl({"cmd": "power_cycle"})["ok"])
        sha_c = self.gh.commit("e2e-c", self.sha_b, sha=self.sha7 + hashlib.sha1(b"e2e-c").hexdigest()[:33])
        self.gh.head = sha_c
        tag_c = self.gh.add_release_dir(self._make_release(43, sha_c))
        self.person_left()  # test_1's rollback by hand
        out = self.main("run", expect=5)
        self.assertIn("poll: staged " + tag_c, out)
        self.assertIn("controller was reset", out)
        rep = self.last_health()["report"]
        self.assertEqual((rep["ok"], rep["kind"]), (False, "machine"), rep)
        self.assertTrue(rep["version"].startswith(tag_c + "_"), rep["version"])
        self.assertEqual([s["step"] for s in rep["steps"]], ["status", "origins", "enable", "serial", "axes"])
        self.assertEqual(rep["steps"][-1]["detail"][:24], "controller was reset; a ")

        self.assertEqual(self.link("current"), self.tag_a)
        self.assertTrue(self.service().is_active(), "it ran before the deploy: it runs again, not enabled")
        self.assertNotIn(tag_c, il_deploy.read_json(os.path.join(self.root, "bad.json")))
        self.assertTrue(os.path.exists(os.path.join(self.root, "hold.json")))
        self.assertIn("- **Deployed on the machine:** %s (%s" % (self.tag_a, self.sha7), self.state_text())
        self.assertIn("reinstalled with its config, not checked", self.state_text())
        h = self.holder()
        self.assertFalse(h.idle)
        self.assertIn("a person must check the machine", h.value)

        lines = [line for _, line in sent(self.sim.io(start))]
        self.assertEqual([x for x in lines if x.split(" ")[0] in ("G0", "G1", "G28")], [], "something moved")
        self.assertTrue(any(lines[i:i + len(DISABLE_LINES)] == DISABLE_LINES for i in range(len(lines))), lines)
        st = self.sim.state()
        self.assertEqual((st["halted"], st["homed"]), (False, {"X": False, "Y": False, "Z": False}))
        self.assertEqual(st["sim_errors"], [])

        self.main("resume")
        h = self.holder()
        self.assertTrue(h.idle and h.value.startswith("nobody (released by Infra resume at "), h.value)
        self.assertFalse(os.path.exists(os.path.join(self.root, "hold.json")))
        type(self).power_cycled_ok = True

    def test_3_a_link_kept_alive_through_a_power_cycle_reconnects_and_is_never_homed(self):
        """BR-44: OpenPnP runs with its controller link kept open (connection-keep-alive) and the controller is switched
        off and on. A health check by hand: Start finds the link dead and connects again (GcodeDriver.setEnabled), so
        the serial step reads the new connect line, not the kept-open fallback, and the controller's boot position stops
        the check before any home. Before BR-44 that enable failed on the dead port."""
        if not getattr(type(self), "power_cycled_ok", False):
            self.skipTest("needs test_2's power cycle")
        start = self.sim.io_count()
        # The previous build runs since test_2, never enabled. A health check by hand connects it: the controller still
        # reads its boot position (not homed since the power cycle; A and B 720.05, as test_2's STOP's M410 recomputed
        # them from the step counts), so no home; the STOP leaves the link open.
        self.main("health", "--no-claim", expect=1)
        rep = json.loads(self.last_stdout)
        self.assertEqual([s["step"] for s in rep["steps"]], ["status", "origins", "enable", "serial", "axes"], rep)
        self.assertNotIn("kept open", step(rep, "serial")["detail"])
        self.assertIn("controller was reset", rep["failure"])
        self.assertTrue(rep["stopped"]["ok"], rep)
        log = os.path.join(self.root, "state", "config", "log", "OpenPnP.log")
        with open(log, errors="replace") as f:
            connects = f.read().count("Serial port: connecting to ")

        self.assertTrue(self.sim.ctl({"cmd": "power_cycle"})["ok"])  # the link OpenPnP keeps open dies
        self.main("health", "--no-claim", expect=1)
        rep = json.loads(self.last_stdout)
        self.assertEqual([s["step"] for s in rep["steps"]], ["status", "origins", "enable", "serial", "axes"], rep)
        self.assertNotIn("kept open", step(rep, "serial")["detail"], "Start connected again")
        self.assertIn("Serial port: connecting to %s by " % self.sim.link, step(rep, "serial")["detail"])
        self.assertIn("controller was reset", rep["failure"])
        self.assertTrue(rep["stopped"]["ok"], rep)
        with open(log, errors="replace") as f:
            text = f.read()
        self.assertIn("the kept-alive link to the controller is dead: connecting again", text)
        self.assertEqual(text.count("Serial port: connecting to "), connects + 1)

        lines = [line for _, line in sent(self.sim.io(start))]
        self.assertEqual([x for x in lines if x.split(" ")[0] in ("G0", "G1", "G28")], [], "something moved")
        st = self.sim.state()
        self.assertEqual((st["halted"], st["sim_errors"]), (False, []))

if __name__ == "__main__":
    # CI runs this file directly: with IL_DEPLOY_E2E=1 a skip means a miswired job (no jar, no Xvfb), not a pass
    result = unittest.main(exit=False).result
    print("timings (s):", json.dumps(getattr(DeployRealOpenPnP, "timings", {})))
    sys.exit(0 if result.wasSuccessful() and not (E2E and result.skipped) else 1)
