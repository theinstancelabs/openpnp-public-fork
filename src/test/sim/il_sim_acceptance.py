#!/usr/bin/env python3
"""Instance Labs simulator acceptance for the dispense engine (never the real machine).

Every scenario runs this branch's OpenPnP jar through openpnp-uinav in SIM mode: a COPY of the
config dir, the GcodeDriver rewired to OpenPnP's own simulated controller (GcodeServer) behind a
capture proxy, simulated cameras, device stubs first on the classpath, its own Xvfb display.
The config copy is staged from the live dir (read only) with machine.xml replaced by the plan §10
patched copy and the engine's dispense-config.json added.

Scenarios (plan §4.4, §4.5, §8.1; bench recipe 2026-10-28):
  load        patched machine.xml loads; connect (M906 B580) / home (G92 A0 B0, homing fiducial) /
              Stop G-code; File>Exit saves it back with every patched item (N2 offset, homing fiducial)
  stop        STOP via the web API and via the Swing Stop button, during a pressure hold and
              during a long board run: captured G-code shows the F9 sequence, no motion after it
  board       plan + run ~/Desktop/ftp.board.xml (copy) with the bench profile: one pressure session,
              8 purge dots on the purge row (the first sets the screw phase), a cross without stopping,
              160 work dots of 800 net steps; raw XY/Z of every dot vs OpenPnP's own N2 move to that pad;
              M204 discipline, no M400 / extra G4 in the dots; the kinematic real-machine time / DPH
              estimate (tools/instancelabs/dph_estimate.py) of the streamed program
  register    Register board through the SIM top camera and the SIM vacuum (AUTO_REGISTER_DESIGN.md §10.4): the
              board truly sits moved, turned, scaled and sheared from where it is planned; the fit, the probe, the
              dots on the true pads, 20 dots run; moved again (re-seated); the same seat registered again (3 touches);
              STOP during a probe, then a run that raises N1, homes, checks Z and dispenses; refusals (a hidden
              fiducial, holes, paste, no nozzle tip, Z moved after a home)
  import      KiCad upload of ftp.kicad_pcb (BOARD_IMPORT_DESIGN.md §6.7): preview, save, plan (pads vs the bench
              board file), 20 dots run, attach, a restart keeps it; then the imported board registered on a moved
              seat and run
  padcal      Missing paste profiles (PROFILE_CALIBRATION_DESIGN.md §14.5) on the SIM paste and balance, one session per
              part. main: padcal.kicad_pcb uploaded (the preview's NEEDS_PROFILE and reasons per package), saved,
              planned; the profile check's groups; Use for TestChip_A; Calibrate automatically for SW_SPST and
              Test_Strip (the script is the operator: coupons on the SIM balance, wiped, weighed again, the readings
              typed); both characterized with the SIM's 2.30 mg per turn; the plan made at the end run with the WSON
              skipped (per-pad paste, dose blocks and the M114 Count lines of the controller); a reconnect then
              refuses the quarter-turn block (I11). nano: the Arduino Nano's upload and top-side split (40 tip, 15
              with a plan, 5 partial), Calibrate automatically for its two groups (SW_SPST characterized, the
              LED_0805 stays provisional: no allowed dose is within -25 %/+10 % of its amount), its next plan run.
              controls: §11.7's other negative controls, each ending with its reason. --padcal-parts runs some parts

    il_sim_acceptance.py [--out DIR] [--prefix P] [--api-port N] all | load | stop | board | register | import |
                         padcal [--padcal-parts main,nano,controls] | bench | dph --stream FILE

Sim-only changes on top of the patched copy (reported in results.json):
  * machine.xml copy (uinav --xml-set; not in the load scenario): head visual-homing-method=None (the
    simulated camera has no homing fiducial); B axis limits raised to the engine's M203/M201 B (2400 deg/s,
    20000 deg/s^2) so OpenPnP's simulated controller does not time the screw at machine.xml's 100 deg/s;
    the Top camera is the SIM top camera (SimBoardCamera, 1920 x 1080), which draws the board where the SIM scene
    says it truly sits;
  * the SIM scene (instancelabs/sim-scene.json in the copy, il_sim_scene.py; not in the load scenario): the board's
    true seat (BOARD_LOC itself, except in register and import), its N1 contact plane and the bench blank, read by
    the SIM top camera and the SIM vacuum sensor of OpenPnP's simulated controller;
  * through the web API, in the session copy only: needle Z frame (N1 5.5, bench relation K 65.59) and
    its offset confirmed (I4), board and blank surfaces from the bench N1 probes (I4), the bench purge row
    (I9), screw start angle confirmed after connect (I11), the clean-pad guard off (I10: the simulated
    top camera is a static image), valve settle 3000 ms (bench), chip-0603 'characterized' (§5.7 rule).
Python 3 standard library only.
"""
import argparse
import gzip
import json
import math
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(REPO, "tools", "instancelabs"))
import dph_estimate  # noqa: E402
import make_machine_patch as patch  # noqa: E402

sys.path.insert(0, HERE)
import il_sim_scene as scn  # noqa: E402

UINAV_DIR = os.environ.get("UINAV_DIR", "/home/lumen/instancelabs/openpnp-uinav")
sys.path.insert(0, os.path.join(UINAV_DIR, "python"))
import uinav  # noqa: E402

HOME = os.path.expanduser("~")
RUNS = "/home/lumen/instancelabs/runs"
DEF = {
    "jar": os.path.join(REPO, "target", "openpnp-gui-0.0.1-alpha-SNAPSHOT.jar"),
    "live": os.path.join(HOME, ".openpnp2"),
    "patched": "/home/lumen/instancelabs/config/machine.xml.patched",
    "dispense_config": "/home/lumen/instancelabs/config/dispense-config.json",
    "board": os.path.join(HOME, "Desktop", "ftp.board.xml"),
    "out": "/home/lumen/instancelabs/sim-acceptance/" + time.strftime("%Y%m%d-%H%M%S"),
}
# Board where the top camera found it on the real machine: runs/board_registration.json r7 (re-seated
# after weighing p18_board1, OpenPnP visual-homing frame), translation of the board->machine affine and
# the rotation of its first column. z = the r7 N1 plane at the board centre (surface Z, OpenPnP frame).
REG_AFFINE_R7 = [[1.0009800000000002, 0.0273550303029686, 295.5598978787922],
                 [-0.03115999999999963, 0.994329946851316, 114.81040372040786]]
BOARD_LOC = {"x": round(REG_AFFINE_R7[0][2], 4), "y": round(REG_AFFINE_R7[1][2], 4), "z": 5.43,
             "rotation": round(math.degrees(math.atan2(REG_AFFINE_R7[1][0], REG_AFFINE_R7[0][0])), 4)}
# N1 contacts on this seating of the board (runs/zprobe_r7.json, board mm): 8 mm in from the edges as the
# bench probes; the outermost pads lie <= 3.1 mm beyond (surfaceMarginMm 3.5), so no extra points.
BOARD_PROBES_R7 = [(8.0, 8.0, 5.425), (67.0, 8.0, 5.35), (67.0, 67.0, 5.45), (8.0, 67.0, 5.55), (37.0, 37.0, 5.4)]
# Blank: the bench's N1 contacts along the purge row (runs/zprobe_blank_band2.txt y 232,
# runs/zprobe_blank_238.txt y 238; machine XY). Plane residual 0.035 mm (limit 0.04).
BLANK_PROBES = [(288.0, 232.0, 5.6), (310.0, 232.0, 5.6), (345.0, 232.0, 5.55), (300.0, 238.0, 5.65),
                (360.0, 238.0, 5.475)]
# The bench purge row (runs/purge_spots.json y 237.5): 15 spots x 292..327, before the tape gap at x 331-339.
# usedSpots is the engine's (runs reserve spots; POST /api/purge-row/reset frees them after a wipe).
PURGE_ROW = {"surfaceName": "blank", "x0Mm": 292.0, "y0Mm": 237.5, "pitchXMm": 2.5, "pitchYMm": 0.0, "spots": 15}
PURGE_SPOTS = os.path.join(RUNS, "purge_spots.json")
# Needle Z frame from the bench relation: needle contact raw Z = K - N1 contact Z (side camera, K 65.59),
# i.e. N2 contact = N1 - 2.59 in OpenPnP's frame (z2 = 63 - raw). The needle dispenses at raw 65.49 - c.
K_RAW_SUM = 65.59
NEEDLE_FRAME = {"surfaceZ": 5.5, "needleContactRawSum": K_RAW_SUM,
                "note": "sim acceptance: bench relation needle contact raw Z = 65.59 - N1 contact Z"}
NEEDLE_OFFSET = -2.59
Z2_SUM = 63.0  # machine.xml ReferenceMappedAxis: raw z1 = 63 - z2
SIM_XML_SETS = [".//head@visual-homing-method=None",
                ".//axis[@letter='B']/feedrate-per-second@value=2400.0",
                ".//axis[@letter='B']/acceleration-per-second-2@value=20000.0"] + scn.CAMERA_XML_SETS
# N1 contact plane of the board in the SIM scene (board mm from its origin): the bench's r7 probes (§9.2).
BOARD_PLANE = scn.plane(5.43, -0.0006, 0.0009)
# The KiCad source of the bench board (BOARD_IMPORT_DESIGN.md §6.1).
KICAD_FTP = os.path.join(REPO, "src", "test", "resources", "kicad", "ftp.kicad_pcb.gz")
SIM_CONFIG = {"cleanPadCheck": {"enabled": False}, "purgeRow": PURGE_ROW, "valveSettleMs": 3000}
DISABLE_SEQ = list(patch.DISABLE_LINES)
# The engine's own abort / Stop lines (ControllerCommands.STOP_SEQUENCE) are the same F9 sequence.
ENGINE_I1 = list(patch.DISABLE_LINES)
SIM_COUNT_WARNING = "simulated controller (no M114 Count)"
UINAV = os.path.join(UINAV_DIR, "bin", "uinav")
MOTION = re.compile(r"^\s*G0*(0|1|2|3|28|29|30)(?![0-9])", re.I)
AXIS_WORD = re.compile(r"\b([XYZAB])\s*(-?\d+(?:\.\d*)?)", re.I)


def log(*a):
    print("[il-sim]", *a, file=sys.stderr, flush=True)


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def api_port(a):
    """The web API port of the next session: with --api-port N the first free one of N … N+8 (taken in turn, so a
    run stays inside the range it was given), else any free port."""
    first = getattr(a, "api_port", None)
    if not first:
        return free_port()
    for k in range(9):
        p = first + (getattr(a, "port_turn", 0) + k) % 9
        with socket.socket() as so:
            so.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                so.bind(("127.0.0.1", p))
            except OSError:
                continue
        a.port_turn = p - first + 1
        return p
    raise RuntimeError("no free web API port in %d..%d" % (first, first + 8))


def strip(line):
    return line.split(";", 1)[0].strip()


def is_motion(line):
    code = strip(line)
    return bool(MOTION.match(code)) and bool(AXIS_WORD.search(code))


def axes_of(line):
    return {k.upper() for k, _ in AXIS_WORD.findall(strip(line))} if is_motion(line) else set()


def find_subseq(lines, seq, start=0):
    """Indices of seq as an in-order (not necessarily contiguous) subsequence, or None."""
    codes = [strip(l) for l in lines]
    out, j = [], start
    for want in seq:
        while j < len(codes) and codes[j] != want:
            j += 1
        if j >= len(codes):
            return None
        out.append(j)
        j += 1
    return out


def find_seq(lines, seq, start=0):
    codes = [strip(l) for l in lines]
    for i in range(start, len(codes) - len(seq) + 1):
        if codes[i:i + len(seq)] == seq:
            return i
    return -1


def java_round(x):
    return int(math.floor(x + 0.5))


# --------------------------------------------------------------------------- staging / session

def nominal_seat():
    """BOARD_LOC as an affine (board mm -> machine mm): where the plans put the board."""
    return scn.seat(BOARD_LOC["x"], BOARD_LOC["y"], BOARD_LOC["rotation"])


def stage(a, name, scene=None):
    """Config copy: live dir (read only) + patched machine.xml + engine config + test script + board, and the SIM
    scene: scene(board_copy, stage_dir) -> dict, None for the board truly at BOARD_LOC, False for no scene."""
    st = os.path.join(a.out, "stage-" + name)
    if os.path.exists(st):
        shutil.rmtree(st)
    shutil.copytree(a.live, st, ignore=shutil.ignore_patterns("backups", "log"))
    shutil.copy2(a.patched, os.path.join(st, "machine.xml"))
    os.makedirs(os.path.join(st, "instancelabs"), exist_ok=True)
    shutil.copy2(a.dispense_config, os.path.join(st, "instancelabs", "dispense-config.json"))
    shutil.copy2(os.path.join(HERE, "il_n2_reference.js"), os.path.join(st, "scripts", "il_n2_reference.js"))
    board_copy = os.path.join(st, os.path.basename(a.board))
    shutil.copy2(a.board, board_copy)
    with open(os.path.join(st, "boards.xml"), "w") as f:
        f.write("<openpnp-boards>\n   <board>%s</board>\n</openpnp-boards>\n" % board_copy)
    if scene is not False:
        scn.write(st, scene(board_copy, st) if scene else scn.scene(board_copy, nominal_seat(), BOARD_PLANE,
                                                                    config_dir=st))
    return st


class Session:
    def __init__(self, a, name, xml_sets=SIM_XML_SETS, scene=None, stage_dir=None):
        """stage_dir: launch on this config dir (e.g. an earlier session's copy) instead of a new stage."""
        self.a, self.name = a, a.prefix + name
        self.stage = stage_dir or stage(a, name, scene)
        self.port = api_port(a)
        env = dict(os.environ, OPENPNP_IL_API_PORT=str(self.port))
        cmd = [UINAV, "--session", self.name, "launch", "--config", self.stage, "--openpnp-jar", a.jar]
        for x in xml_sets:
            cmd += ["--xml-set", x]
        log("launch", self.name, "api port", self.port)
        out = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=300)
        if out.returncode != 0:
            raise RuntimeError("uinav launch failed: %s %s" % (out.stdout, out.stderr))
        self.info = json.loads(out.stdout)
        self.sess_dir = uinav.session_dir(self.name)
        self.cfg = self.info["config_dir"]
        self.ui = uinav.Client(session=self.name, timeout=120)
        self.base = "http://127.0.0.1:%d" % self.port
        for _ in range(240):
            try:
                self.api("GET", "/api/status")
                break
            except Exception:
                time.sleep(0.5)
        else:
            raise RuntimeError("web API did not come up on %d" % self.port)

    def api(self, method, path, body=None, timeout=60, ok=(200, 201, 202)):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                payload, code = r.read(), r.status
        except urllib.error.HTTPError as e:
            payload, code = e.read(), e.code
        try:
            b = json.loads(payload)
        except ValueError:
            b = {"raw": payload.decode(errors="replace")}
        if code not in ok:
            raise RuntimeError("%s %s -> %d %s" % (method, path, code, json.dumps(b)[:800]))
        return b

    def mark(self):
        """Sequence number of the next captured G-code line."""
        return self.ui.get("/gcode", since=10 ** 9)["next"]

    def sent_since(self, n):
        return [e["line"] for e in self.ui.gcode(since=n)["entries"] if e.get("dir", ">") == ">"]

    def wait_status(self, pred, timeout=60, what="status"):
        t0 = time.time()
        last = None
        while time.time() - t0 < timeout:
            last = self.api("GET", "/api/status")
            if pred(last):
                return last
            time.sleep(0.2)
        raise TimeoutError("timed out waiting for %s; last: %s" % (what, json.dumps(last)[:1500]))

    def mcp_button(self, text):
        """The power button of OpenPnP's machine controls (startStopMachineAction); exactly one match."""
        mcps = self.ui.find(type="MachineControlsPanel", any="1")
        if len(mcps) != 1:
            raise RuntimeError("expected one MachineControlsPanel, found %d" % len(mcps))
        hits = self.ui.find(type="JButton", text=text, within=mcps[0]["path"])
        if len(hits) != 1:
            raise RuntimeError("expected one '%s' button, found %s" % (text, [h["path"] for h in hits]))
        return hits[0]

    def dialogs(self):
        return [w for w in self.ui.windows() if w.get("showing") and w.get("class") != "org.openpnp.gui.MainFrame"]

    def dialog_texts(self):
        out = []
        for w in self.dialogs():
            texts = []

            def walk(n):
                if isinstance(n, list):
                    for c in n:
                        walk(c)
                    return
                if not isinstance(n, dict):
                    return
                if n.get("text"):
                    texts.append(n["text"])
                walk(n.get("children", []))
            try:
                walk(self.ui.tree(path=w["path"]))
            except Exception as e:
                texts.append("(tree failed: %s)" % e)
            out.append({"title": w.get("title"), "texts": texts[:12]})
        return out

    def close_dialogs(self):
        for w in self.dialogs():
            try:
                self.ui.act("close_window", path=w["path"])
            except Exception as e:
                log("close dialog failed", e)

    def stop(self):
        subprocess.run([UINAV, "--session", self.name, "stop"], capture_output=True, timeout=60)


def setup_machine(s, characterize=True):
    """Enable, home (sim: no visual homing), the engine's board-run prerequisites through the web API."""
    s.api("POST", "/api/machine/enable")
    s.wait_status(lambda st: st["machine"]["enabled"], 30, "enabled")
    s.api("POST", "/api/machine/home", {"wait": True}, timeout=180)
    s.wait_status(lambda st: st["machine"]["homed"], 60, "homed")
    anchor = s.api("POST", "/api/needle/anchor", NEEDLE_FRAME)
    s.api("POST", "/api/needle/offset", {"offsetMm": NEEDLE_OFFSET,
                                          "note": "sim acceptance (no needle): the frame offset confirmed"})
    s.api("PUT", "/api/surfaces/blank", {"points": [list(p) for p in BLANK_PROBES],
                                         "note": "sim: bench N1 probes along the purge row"})
    s.api("PUT", "/api/config", SIM_CONFIG)
    s.api("POST", "/api/purge-row/reset", {})  # the sim's blank is clean
    # I11: the start angle is valid for this controller connection only (confirmed after enable).
    s.api("POST", "/api/screw/start-angle", {"note": "sim acceptance: confirmed after connect"})
    if characterize:
        s.api("PUT", "/api/pad-types/chip-0603", {"status": "characterized",
                                                   "notes": "SIM ONLY: marked characterized for the acceptance run"})
    cfg = s.api("GET", "/api/config")
    cfg["_anchor_response"] = anchor
    return cfg


def to_global(bx, by):
    r = math.radians(BOARD_LOC["rotation"])
    return (BOARD_LOC["x"] + bx * math.cos(r) - by * math.sin(r), BOARD_LOC["y"] + bx * math.sin(r) + by * math.cos(r))


def board_surface_points():
    """The r7 N1 board probes at their machine XY under the sim's board location (OpenPnP places the
    board rigidly; the bench affine differs from it by <= 0.35 mm, 0.001 mm of Z on this plane)."""
    return [[round(gx, 3), round(gy, 3), z] for (bx, by, z) in BOARD_PROBES_R7 for gx, gy in [to_global(bx, by)]]


def plan_board(s, board_file=None):
    """Plan, tie the board surface to that plan's board registration (I4), then plan on the surface. board_file:
    a board file of the session (default: the session's copy of the bench board)."""
    board = os.path.join(s.cfg, "uinav-boards")
    files = [board_file] if board_file else [os.path.join(board, f) for f in sorted(os.listdir(board))
                                             if f.endswith(os.path.basename(s.a.board))]
    body = dict(BOARD_LOC, source="file", file=files[0])
    first = s.api("POST", "/api/plan", body, timeout=120)
    surf = s.api("PUT", "/api/surfaces/board", {"points": board_surface_points(), "planId": first["planId"],
                                                "note": "sim: runs/zprobe_r7.json at the board location"})
    if not surf.get("boundToBoard"):
        raise RuntimeError("board surface not bound to the board registration: %s" % json.dumps(surf)[:400])
    return s.api("POST", "/api/plan", body, timeout=120), files[0]


def run_and_wait(s, plan_id, timeout_s=1800):
    """POST /api/run with wait (the server waits at most 600 s), then poll until the engine is idle."""
    run = s.api("POST", "/api/run", {"planId": plan_id, "wait": True}, timeout=900, ok=(200, 202))
    if not run.get("finished"):
        s.wait_status(lambda x: not x["dispense"]["busy"], timeout_s, "engine idle after the run")
    return s.api("GET", "/api/runs/last")["run"]


# --------------------------------------------------------------------------- scenario: load

def scenario_load(a):
    """Patched machine.xml with NO sim-only XML change: loads, connect/home/Stop G-code, save on exit."""
    s = Session(a, "load", xml_sets=[], scene=False)
    res = {"session": s.name, "cameras_replaced": s.info["cameras_replaced"]}
    try:
        olog = os.path.join(s.cfg, "log", "OpenPnP.log")
        errs = [l.strip() for l in open(olog) if re.search(r" (ERROR|WARN)", l)]
        benign = ("FileSystemPreferences", "SLF4J")
        res["log_errors"] = [l for l in errs if not any(b in l for b in benign)]
        res["log_errors_benign"] = errs
        res["dialogs_at_start"] = s.dialog_texts()
        st = s.ui.state(commands=True)
        cmds = {c["type"]: c["text"] for c in st["drivers"][0]["commands"] if c.get("head_mountable_id") is None}
        res["driver"] = {k: cmds.get(k) for k in ("CONNECT_COMMAND", "DISABLE_COMMAND", "HOME_COMMAND")}
        res["driver"]["VAC2"] = [c["text"] for c in st["drivers"][0]["commands"]
                                 if c.get("head_mountable_id") == "ACT171086f27295ab60"
                                 and c["type"] == "ACTUATE_BOOLEAN_COMMAND"]
        n0 = s.mark()
        s.api("POST", "/api/machine/enable")
        s.wait_status(lambda x: x["machine"]["enabled"], 30, "enabled")
        res["connect_gcode"] = s.sent_since(n0)
        n1 = s.mark()
        try:
            s.api("POST", "/api/machine/home", {"wait": True}, timeout=180)
            res["home_result"] = "homed"
        except RuntimeError as e:
            res["home_result"] = str(e)  # the simulated camera sees no homing fiducial
        res["home_gcode"] = s.sent_since(n1)
        fid = "X%.4f Y%.4f" % tuple(float(v) for v in patch.HOMING_FIDUCIAL)
        res["home_moves_to_homing_fiducial"] = any(fid in l for l in res["home_gcode"])
        n2 = s.mark()
        res["stop_response"] = s.api("POST", "/api/stop")
        s.wait_status(lambda x: not x["machine"]["enabled"], 30, "disabled")
        time.sleep(1.0)
        res["stop_gcode"] = s.sent_since(n2)
        res["dialogs_after_stop"] = s.dialog_texts()
        s.close_dialogs()
        # File>Exit: OpenPnP saves machine.xml on the way out.
        mx = os.path.join(s.cfg, "machine.xml")
        before = os.stat(mx).st_mtime
        try:
            s.ui.act("menu", menu="File>Exit")
        except Exception:
            pass  # the JVM exits while answering
        for _ in range(60):
            if os.stat(mx).st_mtime != before:
                break
            time.sleep(0.5)
        time.sleep(1.0)
        res["saved_on_exit"] = os.stat(mx).st_mtime != before
        saved = open(mx).read()
        m906 = patch.CONNECT_EXTRA.split(" ;")[0]
        checks = {
            "usb-serial-number": 'usb-serial-number="387932683335"' in saved,
            "usb-description": 'usb-description="MARLIN_OPULO_LUMEN_REV5"' in saved,
            "DISABLE_COMMAND": all(x in saved for x in ["<![CDATA[%s]]>" % l for l in DISABLE_SEQ]),
            "HOME G92 A0 B0": "G92 A0 B0" in saved,
            "CONNECT %s" % m906: m906 in saved and "M906 B540" not in saved,
            "VAC2 S255, OFF valve first": "{True:M106 P2 S255}{False:M107 P3};" in saved
                                           and "{True:M106 P3 S255}{False:M107 P2};" in saved and "M106 P3 S180" not in saved,
        }
        root = ET.fromstring(saved)
        for name in ("VAC1", "VAC2"):
            el = [e for e in root.iter("actuator") if e.get("name") == name][0]
            checks["%s ActuateOff" % name] = el.get("disabled-actuation") == "ActuateOff"
        n2el = [e for e in root.iter("nozzle") if e.get("name") == "N2"][0]
        checks["N2 no tips"] = len(list(n2el.find("compatible-nozzle-tip-ids"))) == 0 and \
            n2el.get("current-nozzle-tip-id") is None
        off = n2el.find("head-offsets")
        checks["N2 head-offsets %s, %s" % patch.N2_OFFSET] = \
            (float(off.get("x")), float(off.get("y"))) == tuple(float(v) for v in patch.N2_OFFSET)
        hf = root.find(".//head/homing-fiducial-location")
        checks["homing fiducial %s, %s" % patch.HOMING_FIDUCIAL] = \
            (float(hf.get("x")), float(hf.get("y"))) == tuple(float(v) for v in patch.HOMING_FIDUCIAL)
        res["saved_config_keeps_patch"] = checks
        res["sim_rewrites_in_saved_copy"] = "communications=tcp (uinav sim), cameras ImageCamera/SimulatedUpCamera (uinav sim)"
        ok = (not res["log_errors"] and not res["dialogs_at_start"] and patch.CONNECT_EXTRA in res["connect_gcode"]
              and find_seq(res["home_gcode"], ["G28", "G92 A0 B0"]) >= 0 and res["home_moves_to_homing_fiducial"]
              and find_seq(res["stop_gcode"], DISABLE_SEQ) >= 0 and res["saved_on_exit"] and all(checks.values()))
        res["pass"] = ok
    finally:
        s.stop()
    return res


# --------------------------------------------------------------------------- scenario: stop

def analyze_stop(lines):
    r = {"lines_after_press": lines}
    i410 = next((i for i, l in enumerate(lines) if strip(l) == "M410"), -1)
    r["first_M410_index"] = i410
    # contiguous = the six lines back to back; in order = as a subsequence (the engine's own F9 lines,
    # sent from the machine thread, may interleave with OpenPnP's DISABLE_COMMAND from the Stop thread)
    r["disable_command_contiguous_index"] = find_seq(lines, DISABLE_SEQ)
    r["disable_command_in_order_lines"] = find_subseq(lines, DISABLE_SEQ, max(i410, 0))
    r["engine_I1_in_order_lines"] = find_subseq(lines, ENGINE_I1, max(i410, 0))
    r["f9_line_counts"] = {c: sum(1 for l in lines if strip(l) == c) for c in DISABLE_SEQ}
    after = lines[i410 + 1:] if i410 >= 0 else lines
    r["motion_after_first_M410"] = [l for l in after if is_motion(l)]
    r["outputs_on_after_first_M410"] = [l for l in after if strip(l).upper().startswith("M106")]
    offs = [strip(l) for l in lines if strip(l) in ("M107 P2", "M107 P3")]
    r["first_off_pair"] = offs[:2]
    # valve vented before the pump stops: the first valve OFF precedes the first pump OFF. (During a run the
    # engine's F9 lines and OpenPnP's DISABLE_COMMAND interleave, so the first two OFFs can both be M107 P3.)
    r["valve_off_before_pump_off"] = bool(offs) and offs[0] == "M107 P3" and "M107 P2" in offs
    r["valve_off_present"] = any(strip(l) == "M107 P3" for l in lines)
    r["pump_off_present"] = any(strip(l) == "M107 P2" for l in lines)
    r["auger_disabled"] = any(strip(l) == "M18 B" for l in lines)
    r["pass"] = (i410 >= 0 and r["disable_command_in_order_lines"] is not None and not r["motion_after_first_M410"]
                 and not r["outputs_on_after_first_M410"] and r["valve_off_present"] and r["pump_off_present"]
                 and r["auger_disabled"] and r["valve_off_before_pump_off"])
    return r


def press_stop(s, how):
    if how == "web":
        return s.api("POST", "/api/stop")
    btn = s.mcp_button("Stop")
    sig = s.ui.classify("click", path=btn["path"])
    if "field:startstopmachineaction" not in json.dumps(sig).lower():  # uinav lower-cases signatures
        raise RuntimeError("the Stop button found is not OpenPnP's power button: %s" % json.dumps(sig))
    r = s.ui.act("click", path=btn["path"], timeout_ms=20000)
    r["button"] = {"path": btn["path"], "text": btn.get("text"), "action": btn.get("action"),
                   "guard_signature": sig.get("signature")}
    return r


def scenario_stop_one(a, what, how):
    s = Session(a, "stop-%s-%s" % (what, how))
    res = {"session": s.name, "what": what, "how": how}
    try:
        setup_machine(s)
        if what == "hold":
            n0 = s.mark()
            res["start"] = s.api("POST", "/api/pressure/start")
            s.wait_status(lambda x: (x.get("pressure") or {}).get("on"), 30, "pressure on")
            time.sleep(1.5)  # held: keepalive/I7 timers running
            res["before_press"] = s.sent_since(n0)
        else:
            plan, _ = plan_board(s)
            res["plan_summary"] = plan.get("summary")
            n0 = s.mark()
            res["start"] = s.api("POST", "/api/run", {"planId": plan["planId"]}, ok=(200, 202))
            st = s.wait_status(lambda x: ((x.get("run") or {}).get("dotsDone") or 0) >= 10, 240, "10 dots")
            res["dots_done_at_press"] = st["run"]["dotsDone"]
            res["before_press_tail"] = s.sent_since(n0)[-12:]
        mark = s.mark()
        t_press = time.time()
        res["press_response"] = press_stop(s, how)
        s.wait_status(lambda x: not x["machine"]["enabled"], 60, "machine disabled")
        res["seconds_to_disabled_upper_bound"] = round(time.time() - t_press, 2)  # incl. 0.2 s polling
        if what == "run":
            s.wait_status(lambda x: not x["dispense"]["busy"], 60, "engine idle")
        time.sleep(2.0)
        lines = s.sent_since(mark)
        res.update(analyze_stop(lines))
        st = s.api("GET", "/api/status")
        res["after"] = {"machine_enabled": st["machine"]["enabled"], "homed": st["machine"]["homed"],
                        "pressure": (st.get("pressure") or {}).get("state"),
                        "last_run": {k: (st.get("lastRun") or {}).get(k) for k in ("status", "message", "dotsDone", "dotsPlanned")}}
        res["dialogs"] = s.dialog_texts()
        s.close_dialogs()
        if what == "run":
            res["pass"] = res["pass"] and res["engine_I1_in_order_lines"] is not None \
                and res["after"]["last_run"]["status"] == "ABORTED"
        else:
            res["pass"] = res["pass"] and res["after"]["pressure"] in ("off", None)
    finally:
        s.stop()
    return res


def scenario_stop(a, out, save):
    for what in ("hold", "run"):
        for how in ("web", "swing"):
            try:
                r = scenario_stop_one(a, what, how)
            except Exception as e:
                r = {"what": what, "how": how, "pass": False, "error": repr(e)}
            log("stop", what, how, "PASS" if r["pass"] else "FAIL")
            out.append(r)
            save()
    return out


# --------------------------------------------------------------------------- scenario: board

def pads_from_board(board_file, pitch=1.65):
    """Independent pad centres (board coordinates) for the chip-0603 two-terminal pad type."""
    root = ET.parse(board_file).getroot()
    pads = {}
    for p in root.iter("placement"):
        if p.get("type") != "Placement":
            continue
        loc = p.find("location")
        px, py, r = float(loc.get("x")), float(loc.get("y")), math.radians(float(loc.get("rotation")))
        for name, lx in (("1", -pitch / 2), ("2", pitch / 2)):
            pads["%s.%s" % (p.get("id"), name)] = (px + lx * math.cos(r), py + lx * math.sin(r))
    return pads


def modal_walk(lines, start):
    """Yield (index, line, position-dict-after) with modal X/Y/Z/B tracking."""
    pos = dict(start)
    for i, l in enumerate(lines):
        code = strip(l).upper()
        if code.startswith("G92"):
            for k, v in AXIS_WORD.findall(code):
                pos[k.upper()] = float(v)
        elif MOTION.match(code):
            for k, v in AXIS_WORD.findall(code):
                pos[k.upper()] = float(v)
        yield i, l, dict(pos)


def engine_dots(lines, start):
    """Feeds (B increasing) with the raw XYZ at that moment, the suck-back that follows and the lift."""
    feeds = []
    prev = dict(start)
    for i, l, pos in modal_walk(lines, start):
        code = strip(l).upper()
        if MOTION.match(code) and "B" in [k.upper() for k, _ in AXIS_WORD.findall(code)]:
            if pos["B"] > prev["B"] + 1e-9:
                feeds.append({"line": i, "x": pos["X"], "y": pos["Y"], "z": pos["Z"], "b_from": prev["B"], "b_to": pos["B"]})
            elif feeds and "retract_line" not in feeds[-1]:
                feeds[-1]["retract_line"] = i
                feeds[-1]["retract_to"] = pos["B"]
        elif MOTION.match(code) and feeds and "retract_line" in feeds[-1] and "lift_z" not in feeds[-1] \
                and any(k.upper() == "Z" for k, _ in AXIS_WORD.findall(code)):
            feeds[-1]["lift_line"] = i
            feeds[-1]["lift_z"] = pos["Z"]
        prev = pos
    return feeds


def expected_screw(cfg, b_count=0):
    """The engine's phase lock (DispenseProfile.phaseSteps / startAngleSteps) recomputed independently."""
    p, rev = cfg["profile"], cfg["bStepsPerRev"]
    start = java_round(p["startAngleDeg"] / 360.0 * rev) % rev
    net, n = p["netStepsPerDot"], p["purgeDots"]
    first = (start - (n - 1) * net - b_count) % rev
    if first < java_round(20.0 / 360.0 * rev):
        first += rev
    suck = java_round(p["suckBackDeg"] * cfg["bStepsPerDeg"])
    work_start = (b_count + first + (n - 1) * net) % rev
    return {"start_angle_steps": start, "first_purge_net_steps": first, "net_steps": net, "suck_back_steps": suck,
            "forward_steps": net + suck, "work_start_count_mod_rev": work_start,
            "work_start_angle_deg": work_start * 360.0 / rev,
            "work_start_angles_deg": sorted({((work_start + k * net) % rev) * 360.0 / rev for k in range(rev)})}


def check_stream(streamed, start, cfg, n_purge):
    """Session structure, M204 discipline, dwell / M400 and screw steps of a board-run stream."""
    codes = [strip(l) for l in streamed]
    feeds = engine_dots(streamed, start)
    purge, work = feeds[:n_purge], feeds[n_purge:]
    spd = cfg["bStepsPerDeg"]
    xyz_acc = max(cfg["travelAccelMmS2"], cfg["zLiftAccelMmS2"])
    screw_acc = cfg["profile"]["screwAccelDegS2"]
    b_feed = "F%.0f" % (cfg["profile"]["screwRpm"] * 360)
    out = {"feeds_total": len(feeds), "purge_feeds": len(purge), "work_feeds": len(work)}

    # ---- pressure session: exactly one pump ON and one valve ON, pump -> wait -> valve -> settle
    i_pump_on = [i for i, c in enumerate(codes) if c == "M106 P2 S255"]
    i_valve_on = [i for i, c in enumerate(codes) if c == "M106 P3 S255"]
    i_valve_off = [i for i, c in enumerate(codes) if c == "M107 P3"]
    i_pump_off = [i for i, c in enumerate(codes) if c == "M107 P2"]
    i_g92 = codes.index("G92 B0") if "G92 B0" in codes else -1
    o = {"pump_on_lines": i_pump_on, "valve_on_lines": i_valve_on, "valve_off_lines": i_valve_off,
         "pump_off_lines": i_pump_off, "session_start": codes[:i_valve_on[0] + 2] if i_valve_on else None,
         "end_sequence": codes[i_valve_off[-1] - 3:] if i_valve_off else None,
         "g92_b0_line": i_g92, "first_b_move_line": feeds[0]["line"] if feeds else -1}
    o["one_pump_on_one_valve_on"] = len(i_pump_on) == 1 and len(i_valve_on) == 1
    o["pump_then_valve_on"] = o["one_pump_on_one_valve_on"] and i_pump_on[0] < i_valve_on[0]
    o["valve_then_pump_off"] = bool(i_valve_off) and bool(i_pump_off) and i_valve_off[-1] < i_pump_off[-1]
    o["g92_b0_before_first_b"] = 0 <= i_g92 < o["first_b_move_line"]
    o["settles"] = [codes[i] for i in range(i_pump_on[0], i_valve_on[0] + 2) if codes[i].startswith("G4")] \
        if o["one_pump_on_one_valve_on"] else None
    # ---- from the first purge feed to the last work lift: no M400, no G4 but one dwell per dot (the cross
    #      from the purge row to the work has no stop)
    lo, hi = feeds[0]["line"], feeds[-1].get("lift_line", feeds[-1]["line"])
    dwell = "G4 P%d" % cfg["profile"]["dwellMs"]
    g4 = [codes[i] for i in range(lo, hi + 1) if codes[i].startswith("G4")]
    o["M400_between_first_purge_and_last_dot"] = sum(1 for i in range(lo, hi + 1) if codes[i] == "M400")
    o["G4_between_first_purge_and_last_dot"] = len(g4)
    o["G4_other_than_dwell"] = sorted(set(g for g in g4 if g != dwell))
    o["only_per_dot_dwell"] = o["M400_between_first_purge_and_last_dot"] == 0 and len(g4) == len(feeds) \
        and not o["G4_other_than_dwell"]
    if purge and work:
        o["cross_lines"] = codes[purge[-1]["lift_line"] + 1:work[0]["line"]]
    o["M400_total"] = codes.count("M400")
    out["order"] = o

    # ---- M204: the screw acceleration covers the B moves only; X/Y/Z always at <= the XY/Z one
    acc, bad_xyz, bad_b, bad_s, bad_f = None, [], [], [], []
    for i, c in enumerate(codes):
        m = re.match(r"^M204 S(\d+(?:\.\d*)?)$", c)
        if m:
            acc = float(m.group(1))
            if acc > xyz_acc + 1e-9:
                nxt = next((codes[j] for j in range(i + 1, len(codes)) if codes[j]), "")
                if axes_of(nxt) != {"B"}:
                    bad_s.append((i, c, nxt))
            continue
        ax = axes_of(c)
        if ax & {"X", "Y", "Z"} and (acc is None or acc > xyz_acc + 1e-9):
            bad_xyz.append((i, c, acc))
        if "B" in ax:
            if acc is None or abs(acc - screw_acc) > 1e-9:
                bad_b.append((i, c, acc))
            if b_feed not in c.split():
                bad_f.append((i, c))
    out["m204"] = {"screw_accel": screw_acc, "xyz_accel": xyz_acc,
                   "M204_screw_lines": sum(1 for c in codes if c == "M204 S%.0f" % screw_acc),
                   "xyz_move_under_screw_accel": bad_xyz[:10], "b_move_not_under_screw_accel": bad_b[:10],
                   "screw_accel_not_followed_by_b_move": bad_s[:10], "b_move_not_at_profile_feed": bad_f[:10]}
    out["m204"]["ok"] = not (bad_xyz or bad_b or bad_s or bad_f) and out["m204"]["M204_screw_lines"] == len(feeds)

    # ---- screw: whole steps, the phase-locking first purge dot, every other dot net 800 / forward 844
    def steps(b):
        return b * spd

    bvals = [float(v) for c in codes if "B" in axes_of(c) for k, v in AXIS_WORD.findall(c) if k.upper() == "B"]
    worst = max((abs(steps(b) - round(steps(b))) for b in bvals), default=0.0)
    rows = []
    rest = 0
    for k, f in enumerate(feeds):
        fwd = round(steps(f["b_to"]) - steps(f["b_from"]))
        net = round(steps(f.get("retract_to", f["b_to"])) - steps(f["b_from"]))
        rows.append({"k": k, "purge": k < n_purge, "net_steps": net, "forward_steps": fwd,
                     "rest_count_before": rest})
        rest += net
    exp = expected_screw(cfg)
    work_rows = rows[n_purge:]
    first_work_rest = work_rows[0]["rest_count_before"] if work_rows else None
    out["screw"] = {
        "expected": exp,
        "b_targets_whole_steps_max_err": worst,
        "first_purge_net_steps": rows[0]["net_steps"] if rows else None,
        "first_purge_forward_steps": rows[0]["forward_steps"] if rows else None,
        "other_purge_net_steps": sorted({r["net_steps"] for r in rows[1:n_purge]}),
        "work_net_steps": sorted({r["net_steps"] for r in work_rows}),
        "work_forward_steps": sorted({r["forward_steps"] for r in work_rows}),
        "work_net_deg": sorted({round(r["net_steps"] / spd, 4) for r in work_rows}),
        "work_forward_deg": sorted({round(r["forward_steps"] / spd, 4) for r in work_rows}),
        "first_work_rest_count": first_work_rest,
        "first_work_start_angle_deg": None if first_work_rest is None
        else (first_work_rest % cfg["bStepsPerRev"]) * 360.0 / cfg["bStepsPerRev"],
        "work_start_angles_deg": sorted({(r["rest_count_before"] % cfg["bStepsPerRev"]) * 360.0 / cfg["bStepsPerRev"]
                                         for r in work_rows}),
    }
    sc = out["screw"]
    sc["ok"] = (worst < 0.01 and sc["first_purge_net_steps"] == exp["first_purge_net_steps"]
                and sc["other_purge_net_steps"] == ([exp["net_steps"]] if n_purge > 1 else [])
                and sc["work_net_steps"] == [exp["net_steps"]] and sc["work_forward_steps"] == [exp["forward_steps"]]
                and abs(sc["first_work_start_angle_deg"] - exp["work_start_angle_deg"]) < 1e-9)

    # ---- purge feeds lie on the purge row: same raw Y, raw X one pitch apart (raw X/Y = N2 X/Y + const)
    dx = [round(purge[k + 1]["x"] - purge[k]["x"], 4) for k in range(len(purge) - 1)]
    dy = [round(purge[k + 1]["y"] - purge[k]["y"], 4) for k in range(len(purge) - 1)]
    out["purge_row"] = {"raw_dx": sorted(set(dx)), "raw_dy": sorted(set(dy)),
                        "raw_z": [p["z"] for p in purge],
                        "lift_after_last_purge_raw_z": purge[-1].get("lift_z") if purge else None}
    out["purge_row"]["ok"] = len(purge) == n_purge and set(dx) <= {PURGE_ROW["pitchXMm"]} and set(dy) <= {0.0}
    return feeds, out


def scenario_board(a):
    s = Session(a, "board")
    res = {"session": s.name, "board_location": BOARD_LOC, "purge_row_config": PURGE_ROW}
    try:
        cfg = setup_machine(s)
        res["needle_anchor_response"] = cfg.pop("_anchor_response")
        res["engine_config_used"] = cfg
        plan, board_file = plan_board(s)
        res["plan_summary"] = plan.get("summary")
        res["plan_interlocks"] = plan.get("interlocks")
        res["plan_warnings"] = plan.get("warnings")
        dots = sorted(plan["dots"], key=lambda d: d["index"])
        res["plan_dots"] = len(dots)
        res["plan_pads"] = len(plan["pads"])
        res["plan_blocked_pads"] = sum(1 for p in plan["pads"] if p.get("blocked"))
        res["plan_flags"] = sorted({f for p in plan["pads"] for f in (p.get("flags") or [])})
        st = s.api("GET", "/api/status")
        res["status_profile"] = st["dispense"].get("profile")
        res["status_purge_row"] = st["dispense"].get("purgeRow")
        m = s.api("GET", "/api/machine")
        ax = {x["letter"]: x["driverCoordinate"] for x in m["axes"] if x.get("letter")}
        start = {k: float(ax.get(k, 0.0)) for k in ("X", "Y", "Z", "B")}
        n0 = s.mark()
        t0 = time.time()
        last = run_and_wait(s, plan["planId"])
        res["sim_run_seconds"] = round(time.time() - t0, 1)
        res["run"] = {k: last.get(k) for k in ("status", "message", "dotsPlanned", "dotsDone", "linesSent", "dphMotion",
                                               "dphOverall", "keepaliveActive", "warnings", "purgeDots", "bCountAtStart",
                                               "workStartAngleDeg", "cleanCheck")}
        res["run_interlocks"] = last.get("interlocks")
        streamed = last["streamedLines"]
        captured = s.sent_since(n0)
        # Lines OpenPnP and the engine send around the stream (M115 capability, M400 + M114 for the B
        # step count) are reported, not hidden.
        res["captured_equals_streamed"] = captured == streamed
        if not res["captured_equals_streamed"]:
            k = 0
            extra = []
            for l in captured:
                if k < len(streamed) and l == streamed[k]:
                    k += 1
                else:
                    extra.append(l)
            res["captured_extra_lines"] = extra[:40]
            res["streamed_all_received_in_order"] = k == len(streamed)
        with open(os.path.join(a.out, "board-run-streamed.gcode"), "w") as f:
            f.write("\n".join(streamed) + "\n")
        with open(os.path.join(a.out, "board-run-captured.gcode"), "w") as f:
            f.write("\n".join(captured) + "\n")

        n_purge = last.get("purgeDots") or 0
        feeds, chk = check_stream(streamed, start, cfg, n_purge)
        res.update(chk)
        purge_feeds, board_feeds = feeds[:n_purge], feeds[n_purge:]
        res["board_dots_streamed"] = len(board_feeds)
        res["dot_timings"] = len(last.get("dotTimings") or [])
        res["screw"]["run_reports_work_start_angle_deg"] = last.get("workStartAngleDeg")
        res["warnings_expected_only"] = all(SIM_COUNT_WARNING in w for w in (last.get("warnings") or []))
        res["sim_count_warning_present"] = any(SIM_COUNT_WARNING in w for w in (last.get("warnings") or []))
        # purge raw Z vs the bench's own purge_spots.json raw Z of the same spots (bench blank model)
        bench_spots = {(s_["x"], s_["y"]): s_["z"] for s_ in json.load(open(PURGE_SPOTS))}
        pz = []
        for k, f in enumerate(purge_feeds):
            xy = (PURGE_ROW["x0Mm"] + k * PURGE_ROW["pitchXMm"], PURGE_ROW["y0Mm"] + k * PURGE_ROW["pitchYMm"])
            if xy in bench_spots:
                pz.append(round(f["z"] - bench_spots[xy], 4))
        res["purge_row"]["raw_z_minus_bench_purge_spots"] = pz

        # ---- independent pad geometry (Python) vs the plan
        pads = pads_from_board(board_file)
        geo = []
        for d in dots:
            bx, by = pads[d["padId"]]
            gx, gy = to_global(bx, by)
            geo.append(math.hypot(gx - d["x"], gy - d["y"]))
        res["plan_vs_python_geometry_max_mm"] = max(geo)

        # ---- OpenPnP's own N2 move to every pad (Scripts menu, il_n2_reference.js)
        dz = dots[0]["dispenseZ"]
        inp = dict(BOARD_LOC, boardFile=board_file, nozzle=cfg["nozzle"], dispenseZ=dz,
                   pads=[{"padId": d["padId"], "bx": pads[d["padId"]][0], "by": pads[d["padId"]][1],
                          "dz": d["dispenseZ"]} for d in dots])
        sdir = os.path.join(s.cfg, "scripts")
        outp = os.path.join(sdir, "il_n2_reference.output.json")
        with open(os.path.join(sdir, "il_n2_reference.input.json"), "w") as f:
            json.dump(inp, f)
        if os.path.exists(outp):
            os.remove(outp)
        if not s.api("GET", "/api/status")["machine"]["homed"]:
            s.api("POST", "/api/machine/home", {"wait": True}, timeout=180)
        m2 = s.api("GET", "/api/machine")
        ax2 = {x["letter"]: x["driverCoordinate"] for x in m2["axes"] if x.get("letter")}
        rstart = {k: float(ax2.get(k, 0.0)) for k in ("X", "Y", "Z", "B")}
        n1 = s.mark()
        s.ui.act("menu", menu="Scripts>il_n2_reference.js", timeout_ms=30000)
        for _ in range(1200):
            if os.path.exists(outp):
                try:
                    ref = json.load(open(outp))
                    if ref.get("done"):
                        break
                except ValueError:
                    pass
            time.sleep(0.5)
        else:
            raise RuntimeError("reference script did not finish; dialogs: %s" % s.dialog_texts())
        ref_lines = s.sent_since(n1)
        with open(os.path.join(a.out, "board-reference-captured.gcode"), "w") as f:
            f.write("\n".join(ref_lines) + "\n")
        refpos = {}
        for _, l, pos in modal_walk(ref_lines, rstart):
            mm = re.match(r"^M118 P(\d+)$", strip(l))
            if mm:
                refpos[int(mm.group(1)) - 1] = pos
        res["reference_markers"] = len(refpos)
        cmp_rows = []
        for k, (d, f) in enumerate(zip(dots, board_feeds)):
            rp = refpos.get(k)
            rs = ref["pads"][k]
            row = {"index": d["index"], "padId": d["padId"],
                   "plan_xy": [d["x"], d["y"]], "python_global_xy": list(to_global(*pads[d["padId"]])),
                   "openpnp_utils2d_xy": [rs["gx"], rs["gy"]],
                   "engine_raw": [f["x"], f["y"], f["z"]], "openpnp_raw": [rp["X"], rp["Y"], rp["Z"]] if rp else None,
                   "engine_lift_raw_z": f.get("lift_z")}
            if rp:
                row["dxy_mm"] = math.hypot(f["x"] - rp["X"], f["y"] - rp["Y"])
                row["dz_mm"] = abs(f["z"] - rp["Z"])
            row["utils2d_vs_python_mm"] = math.hypot(rs["gx"] - row["python_global_xy"][0], rs["gy"] - row["python_global_xy"][1])
            cmp_rows.append(row)
        with open(os.path.join(a.out, "board-dot-comparison.json"), "w") as f:
            json.dump(cmp_rows, f, indent=1)
        dxy = [r["dxy_mm"] for r in cmp_rows if "dxy_mm" in r]
        dzz = [r["dz_mm"] for r in cmp_rows if "dz_mm" in r]
        res["xy_check"] = {"pads_compared": len(dxy), "max_dxy_mm": max(dxy) if dxy else None,
                           "max_dz_mm": max(dzz) if dzz else None,
                           "max_utils2d_vs_python_mm": max(r["utils2d_vs_python_mm"] for r in cmp_rows),
                           "within_0_01": bool(dxy) and len(dxy) == len(dots) and max(dxy) <= 0.01 and max(dzz) <= 0.01}
        # see-saw: z2 = 63 - z1 (machine.xml ReferenceMappedAxis); raw Z of the needle. Every dot has its own
        # Z from the probed board plane (I4). With the bench relation the needle dispenses at raw
        # (K - gap) - c, c = the N1 contact Z of the surface under the dot.
        gap = cfg["profile"]["gapMm"]
        dz_err, hop_err, ref_err, bench_err = [], [], [], []
        for k, (d, f) in enumerate(zip(dots, board_feeds)):
            dz_err.append(abs(f["z"] - (Z2_SUM - d["dispenseZ"])))
            hop_err.append(abs(f.get("lift_z", float("nan")) - (Z2_SUM - d["hopZ"])))
            bench_err.append(abs(f["z"] - (K_RAW_SUM - gap - d["surfaceZ"])))
            if refpos.get(k):
                ref_err.append(abs(f["z"] - refpos[k]["Z"]))
        raw = [f["z"] for f in board_feeds]
        bench_plan = json.load(open(os.path.join(RUNS, "p18_board1.json")))
        anchor = cfg.get("needleAnchor") or {}
        res["z_check"] = {"board_location_z": BOARD_LOC["z"], "needle_frame": NEEDLE_FRAME,
                          "anchor_needle_contact_z": anchor.get("needleContactZMm"),
                          "anchor_raw_sum": anchor.get("rawSumMm"),
                          "needle_offset": cfg["needleZOffsetMm"], "gap": gap,
                          "plan_surface_z_range": [min(d["surfaceZ"] for d in dots), max(d["surfaceZ"] for d in dots)],
                          "engine_raw_dispense_z_range": [min(raw), max(raw)],
                          "bench_raw_dispense_z_range_p18_board1": [min(d["z"] for d in bench_plan),
                                                                    max(d["z"] for d in bench_plan)],
                          "max_err_vs_expected_raw_dispense_z": max(dz_err), "max_err_vs_expected_raw_hop_z": max(hop_err),
                          "max_err_vs_bench_relation_raw_z": max(bench_err),
                          "max_err_vs_openpnp_raw_z": max(ref_err) if ref_err else None,
                          "openpnp_compared": len(ref_err)}
        zc = res["z_check"]
        zc["pass"] = (abs((zc["anchor_needle_contact_z"] or 0) - (NEEDLE_FRAME["surfaceZ"] + NEEDLE_OFFSET)) < 1e-6
                      and abs(zc["needle_offset"] - NEEDLE_OFFSET) < 1e-9
                      and zc["max_err_vs_expected_raw_dispense_z"] <= 1e-3 and zc["max_err_vs_expected_raw_hop_z"] <= 1e-3
                      and zc["max_err_vs_bench_relation_raw_z"] <= 1e-3
                      and zc["openpnp_compared"] == len(dots) and zc["max_err_vs_openpnp_raw_z"] <= 0.01
                      and 59.5 < min(raw) and max(raw) < 60.5)
        o = res["order"]
        res["pass"] = (res["run"]["status"] == "OK" and res["run"]["dotsDone"] == 160 and res["board_dots_streamed"] == 160
                       and n_purge == cfg["profile"]["purgeDots"] == 8 and res["purge_row"]["ok"]
                       and res["xy_check"]["within_0_01"] and zc["pass"] and o["pump_then_valve_on"]
                       and o["valve_then_pump_off"] and o["g92_b0_before_first_b"] and o["only_per_dot_dwell"]
                       and res["m204"]["ok"] and res["screw"]["ok"]
                       and res["run"]["workStartAngleDeg"] is not None
                       and abs(res["run"]["workStartAngleDeg"] - res["screw"]["expected"]["work_start_angle_deg"]) < 1e-9
                       and res["warnings_expected_only"])
        res["dph"] = estimate_dph(streamed, cfg, n_purge, dots)
    finally:
        s.stop()
    return res


# --------------------------------------------------------------------------- scenario: register

LEFT_VALVE_ON, LEFT_VALVE_OFF, LEFT_PUMP_OFF = "M106 P1 S180", "M107 P1", "M107 P0"
DISPENSER_ON = ("M106 P2 S255", "M106 P3 S255")
# What Register board may send (moves, waits, the N1 pump and valve, the VAC1 sensor over I2C, the capability query).
PROBE_ALLOWED = {"G0", "G1", "G4", "M106", "M107", "M114", "M115", "M204", "M260", "M261", "M400"}


def commands(lines):
    """The G-code of captured lines, comments and blanks dropped."""
    return [c for c in (strip(l) for l in lines) if c]


def driver_start(s):
    """The driver coordinates now (the modal start of a captured stream)."""
    m = s.api("GET", "/api/machine")
    ax = {x["letter"]: x["driverCoordinate"] for x in m["axes"] if x.get("letter")}
    return {k: float(ax.get(k, 0.0)) for k in ("X", "Y", "Z", "B")}


def session_board(s, name=None):
    """The session's copy of a board file (uinav copies the listed boards into uinav-boards)."""
    d = os.path.join(s.cfg, "uinav-boards")
    want = os.path.basename(name or s.a.board)
    return [os.path.join(d, f) for f in sorted(os.listdir(d)) if f.endswith(want)][0]


def pad_error(plan, seat_affine, pads_b):
    """Largest distance (mm) of the plan's pads and dots from the true pad centres seat(board pad), and the pad ids
    the board pads do not know."""
    worst, missing = 0.0, []
    for k in ("pads", "dots"):
        for p in plan[k]:
            b = pads_b.get(p["padId"])
            if b is None:
                missing.append(p["padId"])
                continue
            tx, ty = scn.apply(seat_affine, *b)
            worst = max(worst, math.hypot(p["x"] - tx, p["y"] - ty))
    return round(worst, 5), missing


def min_shift(points, a, b):
    return min(math.hypot(*(u - v for u, v in zip(scn.apply(a, *p), scn.apply(b, *p)))) for p in points)


def board_z(plane_, bx, by):
    return plane_["z0"] + plane_["slopeX"] * bx + plane_["slopeY"] * by


def probe_z_error(result, seat_affine, plane_):
    """Largest |contact Z - the scene's plane under it| over the probe's points (machine X/Y -> board mm)."""
    inv = scn.invert(seat_affine)
    errs = []
    for pt in (result.get("probe") or {}).get("points") or []:
        if pt.get("state") != "contact":
            return None
        bx, by = scn.apply(inv, pt["x"], pt["y"])
        errs.append(abs(pt["z"] - board_z(plane_, bx, by)))
    return round(max(errs), 4) if errs else None


def gcode_safety(cmds, floor_z):
    """The N1 probe's G-code (§10.4 step 4): VAC1 set up, every valve on switched off again and the pump after it,
    no dispenser output, every Z-only move between the probe floor and the travel Z."""
    on, off = cmds.count(LEFT_VALVE_ON), cmds.count(LEFT_VALVE_OFF)
    z = [float(m.group(1)) for c in cmds for m in [re.match(r"^G0?1 Z(-?\d+(?:\.\d*)?)(?: F\d+(?:\.\d*)?)?$", c)]
         if m]
    last_on = max((i for i, c in enumerate(cmds) if c == LEFT_VALVE_ON), default=-1)
    out = {"vac1_init_lines": sum(1 for c in cmds if c.startswith("M260 A109")), "valve_on": on, "valve_off": off,
           "pump_off_after_last_valve_on": max((i for i, c in enumerate(cmds) if c == LEFT_PUMP_OFF), default=-1)
           > last_on,
           "dispenser_on_lines": sum(1 for c in cmds if c in DISPENSER_ON),
           "z_only_moves": len(z), "z_min": min(z) if z else None, "z_max": max(z) if z else None,
           "floor_z": floor_z, "commands": sorted({c.split()[0] for c in cmds})}
    out["only_allowed_commands"] = set(out["commands"]) <= PROBE_ALLOWED
    out["ok"] = (out["only_allowed_commands"] and out["vac1_init_lines"] >= 1 and on >= 3 and off >= on
                 and out["pump_off_after_last_valve_on"]
                 and out["dispenser_on_lines"] == 0 and bool(z) and min(z) >= floor_z - 1e-6 and max(z) <= 31.5 + 1e-6)
    return out


def register(s, plan_id, body=None, timeout=900):
    """POST /api/plan/{id}/register with wait (the server answers at the latest after 600 s), then the result."""
    r = s.api("POST", "/api/plan/%s/register" % plan_id, dict(body or {}, wait=True), timeout=timeout)
    if r.get("finished"):
        return r["result"]
    s.wait_status(lambda x: x.get("run") and not x["run"]["running"], timeout, "registration finished")
    return s.api("GET", "/api/plan/" + plan_id)["registrationRun"]


def reg_summary(r):
    keep = ("status", "message", "seat", "movedMm", "registrationId", "newPlanId", "stoppedStage", "simulated",
            "warnings")
    out = {k: r.get(k) for k in keep}
    out["fiducials"] = [{k: f.get(k) for k in ("name", "state", "found", "iterations", "frames")}
                        for f in r.get("fiducials") or []]
    pr = r.get("probe") or {}
    out["probe"] = {k: pr.get(k) for k in ("needed", "why", "saved", "window")}
    out["probe"]["points"] = [{k: p.get(k) for k in ("n", "state", "x", "y", "z", "alternate", "failure")}
                              for p in pr.get("points") or []]
    if r.get("sameSeat"):
        out["sameSeat"] = r["sameSeat"]
    return out


def run_dots(s, plan_id, cfg, max_dots=20, ok=(200, 202)):
    """Runs the first max_dots dots of a plan (wait). Each streamed feed's raw X/Y minus its plan dot's X/Y is
    constant over the feeds and equals -(the dispense nozzle's head offset) - needleXyTrim (§10.4 step 6).
    Returns (summary, captured lines)."""
    s.api("POST", "/api/purge-row/reset", {})  # the SIM blank is clean: 15 spots, 8 purge dots per run
    start = driver_start(s)
    n0 = s.mark()
    acc = s.api("POST", "/api/run", {"planId": plan_id, "maxDots": max_dots, "wait": True}, timeout=900, ok=ok)
    if "error" in acc:
        return {"http": "refused", "reason": acc.get("reason"), "error": acc.get("error")}, s.sent_since(n0)
    if not acc.get("finished"):
        s.wait_status(lambda x: not x["dispense"]["busy"], 1800, "engine idle after the run")
    last = s.api("GET", "/api/runs/last")["run"]
    view = acc.get("result") or last
    out = {"accepted": {k: acc.get(k) for k in ("planId", "dots", "homeFirst", "homeWhyCode", "zCheckFirst")},
           "status": view.get("status"), "message": view.get("message"), "dotsDone": view.get("dotsDone"),
           "homedFirst": view.get("homedFirst"), "zCheck": view.get("zCheck"),
           "cleanCheckOk": (view.get("cleanCheck") or {}).get("ok")}
    captured = s.sent_since(n0)
    streamed = last.get("streamedLines") or []
    if out["status"] == "OK" and streamed:
        dots = sorted(s.api("GET", "/api/plan/" + acc["planId"])["dots"], key=lambda d: d["index"])
        feeds = engine_dots(streamed, start)
        work = feeds[(last.get("purgeDots") or 0):]
        off = [(f["x"] - d["x"], f["y"] - d["y"]) for f, d in zip(work, dots)]
        n2, _ = scn.needle_offsets(s.cfg)
        trim = cfg.get("needleXyTrim") or {}
        exp = (-n2[0] - (trim.get("dxMm") or 0.0), -n2[1] - (trim.get("dyMm") or 0.0))
        out["feeds"] = len(work)
        out["raw_minus_plan_spread_mm"] = round(max(max(o[0] for o in off) - min(o[0] for o in off),
                                                    max(o[1] for o in off) - min(o[1] for o in off)), 5)
        out["raw_minus_plan_vs_head_offset_mm"] = round(max(math.hypot(o[0] - exp[0], o[1] - exp[1]) for o in off), 5)
        out["expected_raw_minus_plan"] = exp
    out["ok"] = (out["status"] == "OK" and out.get("feeds") == max_dots and out.get("raw_minus_plan_spread_mm", 1) <= 0.01
                 and out.get("raw_minus_plan_vs_head_offset_mm", 1) <= 0.01)
    return out, captured


def refusal(r, text):
    return r.get("status") == "REFUSED" and text in (r.get("message") or "")


def scenario_register(a):
    """AUTO_REGISTER_DESIGN.md §10.4 on a copy of ~/Desktop/ftp.board.xml."""
    s0 = nominal_seat()
    s1 = scn.reseat(scn.seat(BOARD_LOC["x"], BOARD_LOC["y"], BOARD_LOC["rotation"], 1.002, 0.997, 0.1),
                    0.60, -0.45, 0.20)
    s2 = scn.reseat(s1, 0.8, 0.5, -0.4)
    plane1 = scn.plane(5.43, 0.0015, -0.0010)
    plane2 = scn.plane(5.40, 0.004, 0.0)  # 0.3 mm across the 75 mm board
    camera = {"uppError": [0.0, 0.03]}  # the bench's 3 % Y scale gap: the centring loop absorbs it

    s = Session(a, "register", scene=lambda board, st: scn.scene(board, s1, plane1, camera=camera, config_dir=st))
    res = {"session": s.name, "board_location": BOARD_LOC, "seat_s1": s1, "seat_s2": s2, "plane_s1": plane1,
           "plane_s2": plane2}
    ok = {}
    try:
        cfg = setup_machine(s)
        res["needle_anchor_response"] = cfg.pop("_anchor_response")
        s.api("PUT", "/api/config", {"cleanPadCheck": {"enabled": True}})  # the SIM camera draws the pads now
        board = session_board(s)
        pads_b = pads_from_board(board)
        fids_b = [(f["bx"], f["by"]) for f in scn.fiducials(board, s.cfg)]
        res["min_pad_shift_nominal_to_s1_mm"] = round(min_shift(pads_b.values(), s0, s1), 4)
        ok["sensitive"] = res["min_pad_shift_nominal_to_s1_mm"] >= 0.7

        # 3-5: plan at the nominal seat, register (bench probe timings), the dots on the true pads
        body = dict(BOARD_LOC, source="file", file=board)
        p0 = s.api("POST", "/api/plan", body, timeout=120)
        n0 = s.mark()
        t0 = time.time()
        r1 = register(s, p0["planId"])
        res["register_s1_seconds"] = round(time.time() - t0, 1)
        res["register_s1"] = reg_summary(r1)
        floor = ((r1.get("probe") or {}).get("window") or {}).get("floorZ")
        res["register_s1_gcode"] = gcode_safety(commands(s.sent_since(n0)), floor if floor is not None else 99)
        p1 = s.api("GET", "/api/plan/" + r1["newPlanId"]) if r1.get("newPlanId") else None
        res["s1_pad_error_mm"], miss = pad_error(p1, s1, pads_b) if p1 else (None, ["no plan"])
        res["s1_probe_z_error_mm"] = probe_z_error(r1, s1, plane1)
        surf = s.api("GET", "/api/surfaces")["surfaces"].get("board") or {}
        res["s1_surface"] = {k: surf.get(k) for k in ("boundToBoard", "simulated", "setAtMs", "points")}
        ok["register_s1"] = (r1.get("status") == "OK" and r1.get("seat") == "first" and not miss
                             and res["s1_pad_error_mm"] <= 0.02 and res["s1_probe_z_error_mm"] is not None
                             and res["s1_probe_z_error_mm"] <= 0.03 and bool(surf.get("boundToBoard"))
                             and res["register_s1_gcode"]["ok"])

        # the simulator's probe timings from here on (and the sensor time constants that go with them)
        s.api("PUT", "/api/config", scn.FAST_TIMINGS)

        def write_scene(seat_affine, plane_, **kw):
            sc = scn.scene(board, seat_affine, plane_, camera=camera, config_dir=s.cfg,
                           vacuum=dict(scn.FAST_VACUUM, **kw.pop("vacuum", {})), **kw)
            scn.write(s.cfg, sc)
            time.sleep(0.6)  # the session reloads it on its stamp (checked every 250 ms)

        write_scene(s1, plane1)

        # 6: 20 dots; the needle went to the plan dots, which lie on the true pads
        res["run_s1"], _ = run_dots(s, p1["planId"], cfg)
        ok["run_s1"] = res["run_s1"]["ok"] and res["run_s1"]["cleanCheckOk"] is True

        # 7: the board moved (S2, tilted 0.3 mm): the old plan's clean check sees mask, not copper; register finds
        #    it re-seated, probes it again; its dots run on S2's pads
        write_scene(s2, plane2)
        old, old_lines = run_dots(s, p1["planId"], cfg, ok=(200, 202, 409))
        res["run_old_plan_after_move"] = old
        ok["old_plan_refused"] = (old.get("status") == "REFUSED" or old.get("http") == "refused") and not any(
            c in DISPENSER_ON for c in commands(old_lines))
        r2 = register(s, p1["planId"])
        res["register_s2"] = reg_summary(r2)
        res["s2_moved_expected_mm"] = round(max(math.hypot(*(u - v for u, v in zip(scn.apply(s2, *f),
                                                                                    scn.apply(s1, *f))))
                                                for f in fids_b), 4)
        p2 = s.api("GET", "/api/plan/" + r2["newPlanId"]) if r2.get("newPlanId") else None
        res["s2_pad_error_mm"], miss2 = pad_error(p2, s2, pads_b) if p2 else (None, ["no plan"])
        res["s2_probe_z_error_mm"] = probe_z_error(r2, s2, plane2)
        ok["register_s2"] = (r2.get("status") == "OK" and r2.get("seat") == "reseated" and not miss2
                             and abs((r2.get("movedMm") or 0) - res["s2_moved_expected_mm"]) <= 0.03
                             and (r2.get("probe") or {}).get("saved") is True
                             and res["s2_pad_error_mm"] <= 0.02 and res["s2_probe_z_error_mm"] is not None
                             and res["s2_probe_z_error_mm"] <= 0.03)
        res["run_s2"], _ = run_dots(s, p2["planId"], cfg)
        ok["run_s2"] = res["run_s2"]["ok"] and res["run_s2"]["cleanCheckOk"] is True

        # the same seat again: the record kept, 3 stored points touched again, the surface and the plan kept
        surface_at = s.api("GET", "/api/surfaces")["surfaces"]["board"]["setAtMs"]
        n0 = s.mark()
        r3 = register(s, p2["planId"])
        res["register_same_seat"] = reg_summary(r3)
        touches = commands(s.sent_since(n0)).count(LEFT_VALVE_ON)
        res["same_seat_touches"] = touches
        ok["same_seat"] = (r3.get("status") == "OK" and r3.get("seat") == "same"
                           and r3.get("registrationId") == r2.get("registrationId")
                           and (r3.get("sameSeat") or {}).get("pass") is True and touches == 3
                           and r3.get("newPlanId") == p2["planId"]
                           and s.api("GET", "/api/surfaces")["surfaces"]["board"]["setAtMs"] == surface_at)

        # 8: STOP while N1 is down at a probe step: ABORTED, the F9 lines, no new surface; then the run raises
        #    N1 (Z only), homes, checks Z with one touch, and dispenses
        n0 = s.mark()
        s.api("POST", "/api/plan/%s/register" % p2["planId"], {"probe": "always"})

        def n1_down(x):
            d = (x.get("run") or {}).get("detail") or {}
            return d.get("stage") == "probe" and any(p.get("state") == "now" and p.get("lastZ") is not None
                                                     for p in d.get("probes") or [])
        s.wait_status(n1_down, 300, "N1 down at a probe step")
        s.api("POST", "/api/stop", {})
        st = s.wait_status(lambda x: x.get("run") and not x["run"]["running"], 60, "registration stopped")
        stop_lines = commands(s.sent_since(n0))
        # the engine's abort and OpenPnP's DISABLE_COMMAND both send the F9 lines; they may interleave
        f9 = find_subseq(stop_lines, ENGINE_I1, max((i for i, c in enumerate(stop_lines) if c == LEFT_VALVE_ON),
                                                      default=0))
        i_stop = f9[0] if f9 else -1
        rr = s.api("GET", "/api/plan/" + p2["planId"])["registrationRun"]
        res["stop_during_probe"] = {"status": st["run"].get("status"), "stoppedStage": rr.get("stoppedStage"),
                                    "probe_saved": (rr.get("probe") or {}).get("saved"),
                                    "f9_sequence_at": i_stop, "lines_from_the_last_touch": stop_lines[
                                        max((i for i, c in enumerate(stop_lines) if c == LEFT_VALVE_ON), default=0):][:40],
                                    "z_moves_after_f9": [c for c in stop_lines[i_stop:] if c.startswith("G1 Z")]
                                    if i_stop >= 0 else None,
                                    "n1_low": st["machine"]["home"].get("n1Low"),
                                    "enabled_after": st["machine"]["enabled"]}
        ok["stop_during_probe"] = (st["run"].get("status") == "ABORTED" and rr.get("stoppedStage") == "probe"
                                   and not (rr.get("probe") or {}).get("saved") and i_stop >= 0
                                   and not res["stop_during_probe"]["z_moves_after_f9"]
                                   and s.api("GET", "/api/surfaces")["surfaces"]["board"]["setAtMs"] == surface_at)
        s.api("POST", "/api/machine/enable")
        s.wait_status(lambda x: x["machine"]["enabled"], 30, "enabled")
        s.api("POST", "/api/screw/start-angle", {"note": "sim acceptance: confirmed after connect"})
        run_stop, lines = run_dots(s, p2["planId"], cfg)
        cmds = commands(lines)
        g28 = next((i for i, c in enumerate(cmds) if c.startswith("G28")), -1)
        raise_ = next((i for i, c in enumerate(cmds) if re.match(r"^G0?1 Z31\.5", c)), -1)
        first_dot = next((i for i, c in enumerate(cmds) if c == "M106 P2 S255"), -1)
        touches = [i for i, c in enumerate(cmds) if c == LEFT_VALVE_ON and (first_dot < 0 or i < first_dot)]
        run_stop.update({"raise_line": raise_, "g28_line": g28, "z_check_touches": len(touches),
                         "first_dot_line": first_dot})
        res["run_after_stop"] = run_stop
        acc = run_stop["accepted"]
        ok["run_after_stop"] = (run_stop["ok"] and acc.get("homeFirst") is True and acc.get("zCheckFirst") is True
                                and run_stop.get("homedFirst") is True and (run_stop.get("zCheck") or {}).get("pass")
                                is True and 0 <= raise_ < g28 and len(touches) == 1 and g28 < touches[0] < first_dot)

        # 9: refusals, each on a scene rewrite
        fails = res["refusals"] = {}
        write_scene(s2, plane2, hide=["FID2"])
        n0 = s.mark()
        r = register(s, p2["planId"])
        fails["fid2_hidden"] = {"status": r.get("status"), "message": r.get("message"),
                                "n1_lines": commands(s.sent_since(n0)).count(LEFT_VALVE_ON)}
        ok["fid2_hidden"] = refusal(r, "FID2 not found") and fails["fid2_hidden"]["n1_lines"] == 0
        t1 = s.api("GET", "/api/plan/%s/registration-targets" % p2["planId"])["probes"][0]
        spots = [(t1["bx"], t1["by"])] + [(x["bx"], x["by"]) for x in t1["alternates"]]
        write_scene(s2, plane2, leaky=[(x, y, 0.6) for x, y in spots])
        r = register(s, p2["planId"], {"probe": "always"})
        st = s.api("GET", "/api/status")
        fails["holes_at_point_1"] = {"status": r.get("status"), "message": r.get("message"),
                                     "n1_low": st["machine"]["home"].get("n1Low"), "homed": st["machine"]["homed"]}
        ok["holes_at_point_1"] = (refusal(r, "hole or via") and st["machine"]["home"].get("n1Low") is False
                                  and st["machine"]["homed"] is True)
        write_scene(s2, plane2, dirty_spots=spots)
        r = register(s, p2["planId"], {"probe": "always"})
        fails["paste_at_point_1"] = {"status": r.get("status"), "message": r.get("message")}
        ok["paste_at_point_1"] = refusal(r, "something on the surface")
        write_scene(s2, plane2, vacuum={"tip": "missing"})
        r = register(s, p2["planId"], {"probe": "always"})
        fails["tip_missing"] = {"status": r.get("status"), "message": r.get("message")}
        ok["tip_missing"] = refusal(r, "no nozzle tip")
        write_scene(s2, plane2)
        s.api("POST", "/api/machine/home", {"wait": True}, timeout=180)
        low = dict(plane2, z0=plane2["z0"] - 0.70)
        blank = json.loads(json.dumps(scn.DEFAULT_BLANK))
        blank["surface"]["z0"] -= 0.70
        write_scene(s2, low, blank=blank)
        zr, _ = run_dots(s, p2["planId"], cfg, ok=(200, 202, 409))
        fails["z_moved_after_home"] = {k: zr.get(k) for k in ("status", "message", "accepted")}
        # the stored Z data lie within a probe step of the scene's planes: -0.70 within 0.05 mm
        ok["z_moved_after_home"] = (zr.get("status") == "REFUSED"
                                    and re.search(r"Z moved -0\.(6[5-9]|7[0-5]) mm", zr.get("message") or "") is not None)
        write_scene(s2, plane2)
    except Exception:
        res["error"] = traceback.format_exc()
        log("register:", res["error"])
    finally:
        res["checks"] = ok
        res["pass"] = bool(ok) and all(ok.values()) and len(ok) == 14 and "error" not in res
        s.stop()
    return res


# --------------------------------------------------------------------------- scenario: import

def upload(s, path, data, timeout=120):
    """POST raw bytes (application/octet-stream) to the session's API; the JSON answer."""
    req = urllib.request.Request(s.base + path, data=data, method="POST")
    req.add_header("Content-Type", "application/octet-stream")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise RuntimeError("POST %s -> %d %s" % (path, e.code, e.read()[:800]))


def import_kicad(s, data, file_name, name, replace=False):
    """Preview, then save: (preview, saved)."""
    q = urllib.parse.quote
    pv = upload(s, "/api/boards/import?file=" + q(file_name), data)
    saved = upload(s, "/api/boards/import/save?file=%s&name=%s&replace=%d&sha256=%s&decisions=%s"
                   % (q(file_name), q(name), 1 if replace else 0, pv["file"]["sha256"], q(pv["decisions"])), data)
    return pv, saved


def plan_pads(plan):
    return {p["padId"]: (p["x"], p["y"]) for p in plan["pads"]}


def scenario_import(a):
    """BOARD_IMPORT_DESIGN.md §6.7, then the imported board registered on a moved seat (AR §10.4 on it)."""
    import gzip
    s = Session(a, "import")
    res = {"session": s.name, "board_location": BOARD_LOC}
    ok = {}
    s2 = None
    try:
        cfg = setup_machine(s)
        cfg.pop("_anchor_response")
        data = gzip.open(KICAD_FTP).read()
        bench = session_board(s)

        # 2-3: preview and save as ftp-kicad
        pv, saved = import_kicad(s, data, "ftp.kicad_pcb", "ftp-kicad")
        codes = {w["code"] for w in pv.get("warnings") or []}
        res["preview"] = {"counts": pv.get("counts"), "packages": [{k: p.get(k) for k in ("id", "state",
                                                                                           "usesPadTypesOf")}
                                                                    for p in pv.get("packages") or []],
                          "warning_codes": sorted(codes), "attach": pv.get("attach")}
        att = (pv.get("attach") or [{}])[0] if isinstance(pv.get("attach"), list) else {}
        ok["preview"] = (pv["counts"]["placements"] == 80 and pv["counts"]["fiducials"] == 3
                         and "PACKAGE_SUFFIXED" in codes
                         and {p["state"] for p in pv["packages"]} == {"suffixed"}
                         and os.path.basename(att.get("file") or "") == os.path.basename(bench)
                         and abs((att.get("maxOffsetMm") or 0) - 0.3136) <= 0.0005)
        board = saved["file"]
        slug_dir = os.path.dirname(board)
        files = sorted(os.listdir(slug_dir))
        xml = {f: open(os.path.join(s.cfg, f)).read() for f in ("packages.xml", "parts.xml", "boards.xml")}
        new_ids = [p["id"] for p in pv["packages"]]
        res["saved"] = {"file": board, "files": files, "art": saved.get("art"),
                        "index": os.path.exists(os.path.join(s.cfg, "instancelabs", "boards", "index.json"))}
        ok["save"] = (files == ["ftp-kicad.art.json", "ftp-kicad.board.xml", "ftp-kicad.kicad_pcb"]
                      and res["saved"]["index"] and all(('id="%s"' % i) in xml["packages.xml"] for i in new_ids)
                      and board in xml["boards.xml"])

        # 4: plan at BOARD_LOC with the surface tied: R pads equal the bench file's within 0.01 mm, LEDs 0.04
        def tied_plan(file_):
            body = dict(BOARD_LOC, source="file", file=file_)
            first = s.api("POST", "/api/plan", body, timeout=120)
            s.api("PUT", "/api/surfaces/board", {"points": board_surface_points(), "planId": first["planId"],
                                                 "note": "sim: runs/zprobe_r7.json at the board location"})
            return s.api("POST", "/api/plan", body, timeout=120)

        plan = tied_plan(board)
        bench_pads = pads_from_board(bench)
        dr, dl = [], []
        for pid, (x, y) in plan_pads(plan).items():
            gx, gy = to_global(*bench_pads[pid])
            (dr if pid.startswith("R") else dl).append(math.hypot(x - gx, y - gy))
        fid = {f["id"]: f for f in plan["board"]["fiducials"]}
        bench_fid = {f["name"]: f for f in scn.fiducials(bench)}
        fid_off = {n: round(math.hypot(fid[n]["x"] - to_global(bench_fid[n]["bx"], bench_fid[n]["by"])[0],
                                       fid[n]["y"] - to_global(bench_fid[n]["bx"], bench_fid[n]["by"])[1]), 4)
                   for n in fid}
        res["plan"] = {"pads": len(plan["pads"]), "sources": sorted({p.get("source") for p in plan["pads"]}),
                       "max_r_pad_vs_bench_mm": round(max(dr), 5), "max_led_pad_vs_bench_mm": round(max(dl), 5),
                       "fiducial_vs_bench_mm": fid_off, "art": plan["board"].get("art")}
        ok["plan"] = (len(plan["pads"]) == 160 and res["plan"]["sources"] == ["FOOTPRINT"] and max(dr) <= 0.01
                      and max(dl) <= 0.04 and fid_off["FID2"] <= 0.001 and fid_off["FID3"] <= 0.001
                      and abs(fid_off["FID1"] - 0.3136) <= 0.0005)

        # 5: 20 dots; the engine's dots are the plan's
        res["run"], _ = run_dots(s, plan["planId"], cfg)
        ok["run"] = res["run"]["ok"]

        # 6: attach the drawing to the bench board file: its plan's pads unchanged
        before = plan_pads(tied_plan(bench))
        pv2 = upload(s, "/api/boards/import?file=ftp.kicad_pcb", data)
        att2 = upload(s, "/api/boards/import/attach?file=ftp.kicad_pcb&board=%s&sha256=%s"
                      % (urllib.parse.quote(bench), pv2["file"]["sha256"]), data)
        after = plan_pads(tied_plan(bench))
        listed = {f["file"]: f for f in s.api("GET", "/api/boards").get("files") or []}
        res["attach"] = {"answer": {k: att2.get(k) for k in ("file", "art")},
                         "bench_art": (listed.get(bench) or {}).get("art"),
                         "pads_unchanged": before == after}
        ok["attach"] = before == after and ((listed.get(bench) or {}).get("art") or {}).get("kind") == "kicad"

        # 7: restart on the same configuration: the import and the drawing are still there
        s.stop()
        restart_stage = os.path.join(a.out, "stage-import-restart")
        if os.path.exists(restart_stage):
            shutil.rmtree(restart_stage)
        shutil.copytree(s.cfg, restart_stage, symlinks=True, ignore=shutil.ignore_patterns("backups", "log"))
        s = Session(a, "import-restart", stage_dir=restart_stage)
        cfg = setup_machine(s)
        cfg.pop("_anchor_response")
        listed = s.api("GET", "/api/boards").get("files") or []
        kicad = [f for f in listed if f.get("file", "").endswith("ftp-kicad.board.xml")]
        bench2 = [f for f in listed if f.get("file", "").endswith("ftp.board.xml")
                  and not f["file"].endswith("ftp-kicad.board.xml")]
        board = kicad[0]["file"] if kicad else board
        plan2 = s.api("POST", "/api/plan", dict(BOARD_LOC, source="file", file=board), timeout=120)
        res["restart"] = {"imported": [{k: f.get(k) for k in ("file", "art")} for f in kicad],
                          "bench": [{k: f.get(k) for k in ("file", "art")} for f in bench2],
                          "plan_pads": len(plan2["pads"]),
                          "plan_sources": sorted({p.get("source") for p in plan2["pads"]})}
        ok["restart"] = (len(kicad) == 1 and kicad[0]["art"]["kind"] == "kicad" and bool(bench2)
                         and all((f.get("art") or {}).get("kind") == "kicad" for f in bench2)
                         and len(plan2["pads"]) == 160 and res["restart"]["plan_sources"] == ["FOOTPRINT"])

        # 8: the imported board truly sits moved, turned and scaled: Register board, then 20 dots on its pads
        s2 = scn.reseat(scn.seat(BOARD_LOC["x"], BOARD_LOC["y"], BOARD_LOC["rotation"], 1.002, 0.997, 0.1),
                        0.60, -0.45, 0.20)
        plane_ = scn.plane(5.43, 0.0015, -0.0010)
        scn.write(s.cfg, scn.scene(board, s2, plane_, config_dir=s.cfg, vacuum=scn.FAST_VACUUM))
        s.api("PUT", "/api/config", dict(scn.FAST_TIMINGS, cleanPadCheck={"enabled": True}))
        time.sleep(0.6)
        pads_b = scn.pads(board, s.cfg)
        r = register(s, plan2["planId"])
        res["register"] = reg_summary(r)
        p3 = s.api("GET", "/api/plan/" + r["newPlanId"]) if r.get("newPlanId") else None
        res["register_pad_error_mm"], miss = pad_error(p3, s2, pads_b) if p3 else (None, ["no plan"])
        res["register_probe_z_error_mm"] = probe_z_error(r, s2, plane_)
        ok["register"] = (r.get("status") == "OK" and not miss and res["register_pad_error_mm"] <= 0.02
                          and res["register_probe_z_error_mm"] is not None
                          and res["register_probe_z_error_mm"] <= 0.03)
        res["register_run"], _ = run_dots(s, p3["planId"], cfg)
        ok["register_run"] = res["register_run"]["ok"] and res["register_run"]["cleanCheckOk"] is True
    except Exception:
        res["error"] = traceback.format_exc()
        log("import:", res["error"])
    finally:
        res["checks"] = ok
        res["pass"] = bool(ok) and all(ok.values()) and len(ok) == 8 and "error" not in res
        s.stop()
    return res


# --------------------------------------------------------------------------- scenario: padcal

# PROFILE_CALIBRATION_DESIGN.md §14.3's test board as a KiCad file (§20.1 item 18), and the Arduino Nano.
KICAD_PADCAL = os.path.join(REPO, "src", "test", "resources", "padprofile", "padcal.kicad_pcb")
KICAD_NANO = os.path.join(REPO, "src", "test", "resources", "kicad", "NanoV3.3.kicad_pcb.gz")
PADCAL_PARTS = ("main", "nano", "controls")
# The nano's top side (§1.9; the upload imports the top only): 40 pads the 22G tip cannot dispense, 15 with a plan, 5 of
# those on parts whose other pads it cannot dispense (decision 4: not pasted unless asked).
NANO_TOP = {"tip": 40, "planned": 15, "partial": 5}
STEPS_PER_DEG, STEPS_PER_REV = 4.44, 1600
# §20.1 item 15: the ripple control runs at 0.6 (il_sim_scene's ripple-0.45 sets the worst physical offset), so the
# half-turn split fails with margin.
RIPPLE_CLEAR_FAIL = 0.6
JOB_ENDED = ("done", "failed", "stopped", "cancelled")


def padcal_layout(sc):
    """Where the pad calibration works on the scene's blank (machine mm): the blank surface from points 3 mm inside its
    corners (on the scene's plane), the purge row 5 mm above its lower edge, the calibration area 3 mm above the row
    (its own blank place at the corner), so the blank probe of the idle-dead-0 control looks 3 mm inside the edge."""
    x0, y0, x1, y1 = sc["blank"]["rect"]

    def z(x, y):
        return round(scn.blank_contact_z(sc, x, y), 3)
    area = {"x0Mm": x0 + 5, "y0Mm": y0 + 8, "widthMm": x1 - x0 - 10, "heightMm": y1 - y0 - 11}
    return {"surface": [[x, y, z(x, y)] for x, y in ((x0 + 3, y0 + 3), (x1 - 3, y0 + 3), (x1 - 3, y1 - 3),
                                                     (x0 + 3, y1 - 3))],
            "purgeRow": {"surfaceName": "blank", "x0Mm": x0 + 5, "y0Mm": y0 + 5, "pitchXMm": 2.5, "pitchYMm": 0.0,
                         "spots": int((x1 - x0 - 10) / 2.5) + 1},
            "area": area, "blank": {"x": area["x0Mm"], "y": area["y0Mm"], "z": z(area["x0Mm"], area["y0Mm"])}}


def padcal_scene(s, board, variant=None, balance="display", **paste):
    """The session's SIM scene: the board truly at the planned seat, with §11.3's blocks (the SIM paste, the two weigh
    coupons, the SIM balance, the bench blank colour), one negative control of §11.7 and paste overrides. Written
    after setup_machine, so the paste's needle Z offset is the engine's (the session's config)."""
    sc = scn.scene(board, nominal_seat(), BOARD_PLANE, config_dir=s.cfg, vacuum=scn.FAST_VACUUM)
    scn.padcal(sc, balance=balance, config_dir=s.cfg)
    if variant:
        scn.variant(sc, variant, s.cfg)
    sc["paste"].update(paste)
    scn.write(s.cfg, sc)
    time.sleep(0.6)  # the session reads the scene again once its stamp changed (250 ms)
    return sc


def wiped(s, *regions):
    """The operator wiped the blank (and any coupon named): the SIM paste off, the purge row and the area free again."""
    for r in regions or ("blank",):
        s.api("POST", "/api/sim/paste/wipe", {"region": r})
    s.api("POST", "/api/purge-row/reset", {})
    s.api("POST", "/api/calibration-area/reset", {})


def padcal_setup(s, balance="display"):
    """setup_machine, the padcal scene, the simulator's probe timings, and the places on the SIM blank and coupons the
    pad calibration needs (§12.2's preflight): (cfg, scene, layout)."""
    cfg = setup_machine(s)
    cfg.pop("_anchor_response")
    sc = padcal_scene(s, session_board(s), balance=balance)
    lay = padcal_layout(sc)
    s.api("PUT", "/api/config", dict(scn.FAST_TIMINGS, purgeRow=lay["purgeRow"]))
    s.api("PUT", "/api/surfaces/blank", {"points": lay["surface"], "note": "sim padcal: the SIM blank's plane"})
    s.api("PUT", "/api/locations/blank", lay["blank"])
    s.api("PUT", "/api/calibration-area", lay["area"])
    for k in sc["coupons"]:
        r = k["rect"]
        s.api("PUT", "/api/locations/" + k["place"], {"x": (r[0] + r[2]) / 2, "y": (r[1] + r[3]) / 2, "z": k["z"]})
    wiped(s)
    return cfg, sc, lay


def padcal_import(s, path, name):
    """KiCad upload through BI's import (preview, then save): (preview, the saved board file)."""
    raw = open(path, "rb").read()
    gz = path.endswith(".gz")
    pv, saved = import_kicad(s, gzip.decompress(raw) if gz else raw, os.path.basename(path)[:-3] if gz
                             else os.path.basename(path), name)
    return pv, saved["file"]


def tied_plan(s, board, sc):
    """Plan the board at BOARD_LOC, tie the board surface (the scene's plane, 2 mm inside the corners and the centre)
    to that plan (I4), plan again."""
    body = dict(BOARD_LOC, source="file", file=board)
    first = s.api("POST", "/api/plan", body, timeout=120)
    w, h = scn.board_size(board)
    pts = [[round(c, 3) for c in scn.apply(nominal_seat(), bx, by)] + [round(scn.board_contact_z(sc, bx, by), 3)]
           for bx, by in ((2, 2), (w - 2, 2), (w - 2, h - 2), (2, h - 2), (w / 2, h / 2))]
    surf = s.api("PUT", "/api/surfaces/board", {"points": pts, "planId": first["planId"],
                                                "note": "sim padcal: the scene's board plane"})
    if not surf.get("boundToBoard"):
        raise RuntimeError("board surface not bound to the board registration: %s" % json.dumps(surf)[:400])
    return s.api("POST", "/api/plan", body, timeout=120)


def preview_summary(pv):
    """The upload preview's profile column: per package its outcome and reason, the warning codes and NEEDS_PROFILE."""
    w = {x["code"]: x for x in pv.get("warnings") or []}
    return {"counts": pv.get("counts"), "warning_codes": sorted(w),
            "needs_profile": (w.get("NEEDS_PROFILE") or {}).get("text"),
            "packages": {p["id"]: {"outcome": p.get("outcome"), "reason": p.get("profileReason"),
                                   "short": p.get("profileShort")} for p in pv.get("packages") or []}}


def groups_by_package(check):
    out = {}
    for g in check["groups"]:
        sg = g.get("suggestion") or {}
        out.setdefault(g["packageIds"][0], []).append(
            {"group": g["group"], "reason": g["reason"], "pads": g["pads"], "partial": g.get("partial"),
             "fixable": g.get("fixable"), "kind": sg.get("kind"), "steps": (sg.get("dots") or {}).get("steps"),
             "perPad": (sg.get("dots") or {}).get("perPad"), "errorPct": (sg.get("predicted") or {}).get("errorPct"),
             "confidence": sg.get("confidence"), "sentence": sg.get("sentence")})
    return out


def attested():
    """The browser's attestations: the regulator read at the profile's 18 psi, the paste within its life (now)."""
    now = int(time.time() * 1000)
    return {"regulator": {"psi": 18, "atMs": now}, "pasteLifeAtMs": now}


def calibrate_groups(s, plan_id, groups):
    """Calibrate automatically these groups of the plan's check (POST /api/pad-calibrations, as the UI's two
    presses), their suggestions saved as provisional pad types first."""
    body = dict(attested(), planId=plan_id, groups=[g["group"] for g in groups], save=True,
                suggestionIds={g["group"]: g["suggestion"]["id"] for g in groups})
    return s.api("POST", "/api/pad-calibrations", body)


def fixable(check):
    return [g for g in check["groups"] if g.get("fixable")]


def await_job(s, timeout=1800):
    """The pad calibration's view once it waits for the readings or has ended."""
    t0, last = time.time(), None
    while time.time() - t0 < timeout:
        last = s.api("GET", "/api/status")["padCalibration"]
        if last and (last["state"] == "awaiting-reading" or last["state"] in JOB_ENDED):
            return last
        time.sleep(1)
    raise TimeoutError("the pad calibration did not reach the readings: %s" % json.dumps(last)[:1500])


def balance_reading(s, place):
    """One coupon on the SIM balance, read once stable (grams, as the display shows)."""
    s.api("POST", "/api/sim/balance/load", {"place": place})
    t0 = time.time()
    while time.time() - t0 < 30:
        b = s.api("GET", "/api/sim/balance")
        if b["stable"] and b["loaded"] == place:
            return float(b["display"].split()[0])
        time.sleep(0.2)
    raise TimeoutError("the SIM balance did not settle with " + place)


def weigh_and_type(s, job, skip=False):
    """The operator at the SIM balance (§7.5, §11.6 display): each coupon weighed, wiped, weighed again; the readings
    typed. skip: the readings skipped (the types stay provisional). (the job's view at its end, the readings)"""
    readings = []
    for k in [] if skip else job["awaiting"]["coupons"]:
        full = balance_reading(s, k["place"])
        n = s.api("POST", "/api/sim/paste/wipe", {"region": k["place"]})["wiped"]
        empty = balance_reading(s, k["place"])
        s.api("POST", "/api/sim/balance/load", {"place": None})
        readings.append({"place": k["place"], "fullG": full, "emptyG": empty, "depositsWiped": n})
    body = {"step": "readings", "wait": True, "skip": True} if skip else {
        "step": "readings", "wait": True, "coupons": [{k: r[k] for k in ("place", "fullG", "emptyG")} for r in readings]}
    return s.api("POST", "/api/pad-calibrations/%s/answer" % job["id"], body, timeout=900), readings


def round_deposits(s, region):
    """The SIM paste of a region or coupon place, threads left out."""
    return [d for d in s.api("GET", "/api/sim/paste/deposits?region=" + region) if not d["thread"]]


def job_summary(job):
    r = job.get("result") or {}
    return {"id": job["id"], "state": job["state"], "steps": [(x["key"], x["state"], x["text"]) for x in job["steps"]],
            "ladder": [{k: l.get(k) for k in ("steps", "dots", "found", "hops", "threads", "residCv", "phase",
                                               "phaseLimit", "dMeanMm", "d95Mm", "landingMeanMm", "pass", "failures")}
                       for l in job.get("ladder") or []],
            "warnings": job.get("warnings"), "sentence": r.get("sentence"), "outcome": r.get("outcome"),
            "newPlanId": r.get("newPlanId"), "control": r.get("control"),
            "groups": [{k: g.get(k) for k in ("label", "typeKey", "outcome", "text", "steps", "perPad", "mgPerPad",
                                               "targetMg", "errorPct", "massConfirmed", "phase", "phaseLimit")}
                       for g in r.get("groups") or []],
            "crops": len(r.get("crops") or []), "predictions": [p.get("id") for p in r.get("predictions") or []]}


def controller_io(s, since_ms):
    """(sent, received) lines of the session's simulated controller since a time (uinav's gcode-io.log)."""
    sent, recv = [], []
    with open(os.path.join(s.sess_dir, "gcode-io.log")) as f:
        for row in f:
            p = row.rstrip("\n").split("\t", 3)
            if len(p) == 4 and p[0].isdigit() and int(p[0]) >= since_ms:
                if p[2] == ">":
                    sent.append(p[3])
                elif p[2] == "<":
                    recv.append(p[3])
    return sent, recv


def b_counts(lines, start_b, b_count):
    """Marlin's B step count before each line: steps since power-up (G92 moves only the origin, every B target is a
    whole step), from the count the engine read before the stream."""
    logical, count, out = start_b, b_count, []
    for l in lines:
        out.append(count)
        code = strip(l).upper()
        words = {k.upper(): float(v) for k, v in AXIS_WORD.findall(code)}
        if "B" not in words:
            continue
        if code.startswith("G92"):
            logical = words["B"]
        elif MOTION.match(code):
            count += java_round(words["B"] * STEPS_PER_DEG) - java_round(logical * STEPS_PER_DEG)
            logical = words["B"]
    return out


def dose_blocks(streamed, start, plan, b_count, cfg):
    """The run's B feeds against the plan's dose blocks (PC §6.2): per block the phase dot and flush dots on the purge
    row, then its work dots; net steps and the count each starts at. (rows, ok) ok: every flush and work dot turns the
    block's net steps, the first work dot of a partial-turn block starts at the start angle and every other one at the
    start angle plus whole multiples of its net steps (the phase lock, count based), and no feed is left over."""
    feeds = engine_dots(streamed, start)
    counts = b_counts(streamed, start["B"], b_count)
    angle = java_round(cfg["profile"]["startAngleDeg"] / 360.0 * STEPS_PER_REV) % STEPS_PER_REV
    rows, k, ok = [], 0, True
    for b in plan["blocks"]:
        roles = (["phase"] if b["phaseDot"] else []) + ["flush"] * b["flushDots"] + ["work"] * b["dots"]
        first_work = True
        for role in roles:
            if k >= len(feeds):
                return rows, False
            f = feeds[k]
            k += 1
            net = java_round((f.get("retract_to", f["b_to"]) - f["b_from"]) * STEPS_PER_DEG)
            at = counts[f["line"]] % STEPS_PER_REV
            rows.append({"block": b["index"], "role": role, "netSteps": net, "startCountMod1600": at})
            if role != "phase":
                ok &= net == b["netSteps"]
            if role == "work" and b["netSteps"] % STEPS_PER_REV:  # whole turns have no phase
                ok &= (at - angle) % b["netSteps"] == 0 and (not first_work or at == angle)
                first_work = False
    return rows, ok and k == len(feeds)


def pad_deposits(plan, laid, tol=0.3):
    """The SIM paste per pad (every deposit within tol of one of its dots) against the plan's predicted mass."""
    per_pad = {}
    for d in plan["dots"]:
        for p in laid:
            if math.hypot(p["x"] - d["x"], p["y"] - d["y"]) < tol:
                per_pad[d["padId"]] = per_pad.get(d["padId"], 0.0) + p["mg"]
    rows = {}
    for p in plan["pads"]:
        if not p.get("blocked") and p.get("predictedMassMg"):
            got = per_pad.get(p["padId"], 0.0)
            rows[p["padId"]] = {"packageId": p["packageId"], "laidMg": round(got, 3), "predictedMg": p["predictedMassMg"],
                                "ratio": round(got / p["predictedMassMg"], 3)}
    by_pkg = {}
    for r in rows.values():
        by_pkg.setdefault(r["packageId"], []).append(r["ratio"])
    means = {k: round(sum(v) / len(v), 3) for k, v in by_pkg.items()}
    ok = bool(rows) and all(0.7 < r["ratio"] < 1.3 for r in rows.values()) and all(
        abs(m - 1) <= 0.10 for m in means.values())
    return {"pads": rows, "packageMeanRatio": means, "ok": ok}


def padcal_run(s, plan, cfg):
    """Run a plan whole after the operator wiped the blank, the pads the tip cannot dispense skipped
    (refuseFlaggedPads false: PC §20 decision 6's per-run skip has no API yet). The stream's dose blocks, the M114
    Count lines of the controller, and the SIM paste per pad."""
    wiped(s)
    s.api("PUT", "/api/config", {"refuseFlaggedPads": False})
    start = driver_start(s)
    before = len(s.api("GET", "/api/sim/paste/deposits?region=board"))
    since = int(time.time() * 1000)
    acc_ = s.api("POST", "/api/run", {"planId": plan["planId"], "wait": True}, timeout=900, ok=(200, 202))
    if not acc_.get("finished"):
        s.wait_status(lambda x: not x["dispense"]["busy"], 1800, "engine idle after the run")
    last = s.api("GET", "/api/runs/last")["run"]
    sent, recv = controller_io(s, since)
    counts = [int(m.group(1)) for m in (re.search(r"\bCount\b.*\bB:(-?\d+)", l) for l in recv) if m]
    out = {"status": last.get("status"), "message": last.get("message"), "dotsPlanned": last.get("dotsPlanned"),
           "dotsDone": last.get("dotsDone"), "purgeDots": last.get("purgeDots"),
           "bCountAtStart": last.get("bCountAtStart"), "workStartAngleDeg": last.get("workStartAngleDeg"),
           "warnings": last.get("warnings"), "blocks": plan.get("blocks"),
           "m114_sent": sum(1 for l in sent if strip(l) == "M114"), "count_lines": counts}
    out["feeds"], blocks_ok = dose_blocks(last.get("streamedLines") or [], start, plan, last.get("bCountAtStart") or 0,
                                          cfg)
    order = [d["padId"] for d in sorted(plan["dots"], key=lambda d: d["index"])]
    at = {}
    for i, p in enumerate(order):
        at.setdefault(p, []).append(i)
    out["pads_whole"] = all(i == list(range(i[0], i[0] + len(i))) for i in at.values())  # R5: a pad's dots in a row
    out["deposits"] = pad_deposits(plan, s.api("GET", "/api/sim/paste/deposits?region=board")[before:])
    out["ok"] = (out["status"] == "OK" and out["dotsDone"] == len(plan["dots"]) and blocks_ok and out["pads_whole"]
                 and bool(counts) and counts[-1] == out["bCountAtStart"] and out["m114_sent"] >= 1
                 and not any(SIM_COUNT_WARNING in w for w in out["warnings"] or []) and out["deposits"]["ok"])
    out["blocks_ok"] = blocks_ok
    return out


def padcal_main(a):
    """§14.5 on padcal.kicad_pcb: the upload's profile column, the plan's groups, Use for TestChip_A, Calibrate
    automatically for SW_SPST and Test_Strip (the script is the operator at the SIM balance), characterized, the plan
    made at its end run with the WSON skipped, then the reconnect control of §11.7."""
    s = Session(a, "padcal-main")
    res, ok = {"session": s.name}, {}
    try:
        cfg, sc, res["layout"] = padcal_setup(s)
        pv, board = padcal_import(s, KICAD_PADCAL, "padcal")
        sc = padcal_scene(s, board)
        res["preview"] = pp = preview_summary(pv)
        reasons = {k: v["reason"] for k, v in pp["packages"].items() if v["reason"]}
        ok["preview"] = ("NEEDS_PROFILE" in pp["warning_codes"] and "NO_PAD_TYPE" not in pp["warning_codes"]
                         and reasons == {"TestChip_A": "no-type", "SW_SPST_3.9x2.9mm": "no-type",
                                         "Test_Strip_0.62x1.50": "no-type",
                                         "WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm": "tip"})

        # the plan's profile check: J and Q new pad types, T uses chip-0603, the WSON's two groups not dispensable
        plan = tied_plan(s, board, sc)
        g = groups_by_package(plan["profileCheck"])
        res["check"] = {"fixable": plan["profileCheck"]["fixable"], "groups": g}
        j, q, t = g["SW_SPST_3.9x2.9mm"][0], g["Test_Strip_0.62x1.50"][0], g["TestChip_A"][0]
        ok["check"] = (plan["profileCheck"]["fixable"] == 3 and (j["kind"], j["steps"], j["perPad"]) == ("new", 800, 2)
                       and (q["kind"], q["steps"], q["perPad"]) == ("new", 400, 2) and t["kind"] == "use"
                       and [u["reason"] for u in g["WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm"]] == ["tip", "tip"])

        # Use for TestChip_A: the mapping, planned again
        tg = next(x for x in plan["profileCheck"]["groups"] if x["group"] == t["group"])
        ap = s.api("POST", "/api/pad-profile/apply", {"planId": plan["planId"], "groups": [t["group"]], "choice": "use",
                                                      "suggestionIds": {t["group"]: tg["suggestion"]["id"]}})
        plan_id = ap.get("newPlanId")
        check = s.api("GET", "/api/pad-profile/check?planId=" + str(plan_id))
        res["use"] = {"applied": [(x["group"], x["kind"], x["typeKey"]) for x in ap.get("applied") or []],
                      "newPlanId": plan_id, "fixableAfter": check.get("fixable")}
        ok["use"] = bool(plan_id) and check["fixable"] == 2 and "TestChip_A" not in groups_by_package(check)

        # Calibrate automatically: the before-start summary, then the job up to the readings
        pv2 = s.api("GET", "/api/pad-calibrations/preview?planId=%s&groups=all&regulatorPsi=18&regulatorAtMs=%d"
                           "&pasteLifeAtMs=%d" % (plan_id, int(time.time() * 1000), int(time.time() * 1000)))
        res["before_start"] = {k: pv2.get(k) for k in ("sentence", "needsText", "refusal", "estimatedSeconds",
                                                       "pasteMg", "blank", "purgeSpots")}
        ok["before_start"] = (pv2.get("refusal") is None and "Lays 76 test dots on the blank (28 half-turn, 48 "
                              "quarter-turn)" in pv2["sentence"] and "it needs you once" in pv2["sentence"])
        t0 = time.time()
        started = calibrate_groups(s, plan_id, fixable(check))
        job = await_job(s)
        res["job_to_readings_s"] = round(time.time() - t0, 1)
        laid = {p: len(round_deposits(s, p)) for p in ("coupon1", "coupon2")}
        steps = {x["key"]: x["text"] for x in job["steps"]}
        res["awaiting"] = {"id": started.get("id"), "state": job["state"], "steps": steps,
                           "coupons": [{k: c.get(k) for k in ("place", "steps", "dots", "found", "expectedMg")}
                                       for c in (job.get("awaiting") or {}).get("coupons") or []],
                           "laid": laid, "warnings": job.get("warnings")}
        ok["engine_steps"] = (job["state"] == "awaiting-reading" and steps.get("coupons") == "coupon1 and coupon2 found "
                              "and touched" and steps.get("ladder") == "76 dots: half ok, quarter ok"
                              and steps.get("weigh") == "136 quarter-turn dots on coupon1, 68 half-turn dots on coupon2"
                              and laid == {"coupon1": 136, "coupon2": 68} and not job.get("warnings"))

        # the operator weighs, wipes and weighs again; the decision and the plan follow
        done, res["readings"] = weigh_and_type(s, job)
        res["result"] = jr = job_summary(done)
        by_group = {x["group"]: x for x in done["result"]["groups"]}
        jt, qt = by_group.get(j["group"]) or {}, by_group.get(q["group"]) or {}
        ok["result"] = (done["state"] == "done" and jr["outcome"] == "characterized"
                        and jr["sentence"].startswith("Characterized ") and jr["sentence"].endswith("; planned again.")
                        and all(x.get("outcome") == "characterized" and x.get("massConfirmed") for x in (jt, qt))
                        and abs((jt.get("mgPerPad") or 0) - 2.30) <= 2.30 * 0.05
                        and abs((qt.get("mgPerPad") or 0) - 1.15) <= 1.15 * 0.05
                        and (jr["control"].get("drift") or "").startswith("the validated half turn now gives 1.1")
                        and jr["crops"] >= 6 and len(jr["predictions"]) == 8)
        crop = (done["result"].get("crops") or [{}])[0].get("after")
        png = urllib.request.urlopen(s.base + crop, timeout=30).read() if crop else b""
        ok["crops_load"] = png[:4] == b"\x89PNG"

        # what was written: the types, one pad_calibrations row each, the weighings, all made in the simulator
        types = {t_["typeKey"]: t_ for f in s.api("GET", "/api/pad-types")["families"] for t_ in f["types"]}
        rows = s.api("GET", "/api/pad-calibrations")
        recs = s.api("GET", "/api/dose-records?freshOnly=true")
        mass = [r for r in recs if r["kind"] == "mass"]
        res["written"] = {
            "types": {k: {f: types[k].get(f) for f in ("status", "dotSteps", "dotsPerPad", "origin", "massPerPadMg")}
                      for k in (jt.get("typeKey"), qt.get("typeKey")) if k in types},
            "pad_calibrations": [{k: r.get(k) for k in ("typeKey", "outcome", "steps", "dotsPerPad", "massPerPadMg",
                                                         "simulated")} for r in rows],
            "mass_records": [{k: r.get(k) for k in ("couponPlace", "steps", "n", "totalMg", "mgPerDot", "mgPerStep",
                                                     "confirmedBy", "method", "simulated")} for r in mass]}
        ok["written"] = (all(types.get(k, {}).get("status") == "characterized"
                             and types[k].get("origin") == "auto-calibrated" for k in (jt.get("typeKey"), qt.get("typeKey")))
                         and len(rows) == 2 and all(r["simulated"] and r["outcome"] == "characterized" for r in rows)
                         and len(mass) == 2 and all(r["simulated"] and r["method"] == "typed"
                                                    and abs(r["mgPerStep"] - 0.575 / 400) <= 0.575 / 400 * 0.04
                                                    for r in mass))

        # the plan made at the end: nothing left to fix but the WSON, two dose blocks; run it with the WSON skipped
        nxt = s.api("GET", "/api/plan/" + str(jr["newPlanId"]))
        res["next_plan"] = {"planId": nxt["planId"], "fixable": nxt["profileCheck"]["fixable"],
                            "blocked": sorted({p["packageId"] for p in nxt["pads"] if p.get("blocked")}),
                            "blocks": [(b["netSteps"], b["dots"], b["pads"]) for b in nxt["blocks"]]}
        ok["next_plan"] = (nxt["profileCheck"]["fixable"] == 0 and res["next_plan"]["blocked"]
                           == ["WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm"] and res["next_plan"]["blocks"]
                           == [(800, 18, 14), (400, 12, 6)])
        res["run"] = padcal_run(s, nxt, cfg)
        ok["run"] = res["run"]["ok"]

        # §11.7 reconnect: the quarter-turn camera records are stale, the weighings are not; the run refuses the
        # 400 block (I11) until the start angle is confirmed
        s.api("POST", "/api/stop", {})
        s.wait_status(lambda x: not x["machine"]["enabled"], 30, "disabled")
        s.api("POST", "/api/machine/enable")
        s.wait_status(lambda x: x["machine"]["enabled"], 30, "enabled")
        s.api("POST", "/api/machine/home", {"wait": True}, timeout=180)
        wiped(s)
        refused = s.api("POST", "/api/run", {"planId": nxt["planId"]}, ok=(409,))
        recs = s.api("GET", "/api/dose-records")
        quarter = [r for r in recs if r["steps"] == 400 and r["kind"] in ("optical", "coupon")]
        s.api("POST", "/api/screw/start-angle", {"note": "sim padcal: the controller was not power-cycled"})
        after = s.api("GET", "/api/plan/" + nxt["planId"])["interlocks"]
        res["reconnect"] = {"run": refused.get("reason"), "quarter_records": [(r["kind"], r["whyStale"]) for r in quarter],
                            "mass_fresh": [r["fresh"] for r in recs if r["kind"] == "mass"],
                            "after_confirm": [v["code"] for v in after.get("violations") or []]}
        ok["reconnect"] = ("I11: the controller (re)connected after the screw start angle was set" in refused.get(
            "reason", "") and bool(quarter) and all(r["whyStale"] == "made before the controller last connected: the "
                                                    "screw angle may have moved" for r in quarter)
            and res["reconnect"]["mass_fresh"] == [True, True] and "I11" not in res["reconnect"]["after_confirm"])
    except Exception:
        res["error"] = traceback.format_exc()
        log("padcal main:", res["error"])
    finally:
        res["checks"] = ok
        res["pass"] = len(ok) == 11 and all(ok.values()) and "error" not in res
        s.stop()
    return res


def padcal_nano(a):
    """The Arduino Nano (the next job): its upload's profile column, the top side's split (§14.5: 40 tip, 15 with a
    plan, 5 partial), Calibrate automatically for its fixable groups, and the plan made at the end run."""
    s = Session(a, "padcal-nano")
    res, ok = {"session": s.name}, {}
    try:
        cfg, sc, res["layout"] = padcal_setup(s)
        pv, board = padcal_import(s, KICAD_NANO, "nano")
        sc = padcal_scene(s, board)
        res["preview"] = pp = preview_summary(pv)
        reasons = {k: v["reason"] for k, v in pp["packages"].items() if v["reason"]}
        ok["preview"] = ("NEEDS_PROFILE" in pp["warning_codes"] and "NO_PAD_TYPE" not in pp["warning_codes"]
                         and reasons.get("SW_SPST_3.9x2.9mm") == "no-type"
                         and reasons.get("LED_0805_2012Metric") == "below-gate"
                         and all(reasons.get(k) == "tip" for k in (
                             "USB_Mini-B_Lumberg_2486_01_Horizontal", "VQFN-32-1EP_5x5mm_P0.5mm_EP3.254x3.254mm",
                             "Resonator_SMD_Murata_CSTxExxV-3Pin_3.0x1.1mm")))
        plan = tied_plan(s, board, sc)
        chk = plan["profileCheck"]
        tip = sum(g["pads"] for g in chk["groups"] if g["reason"] == "tip")
        res["split"] = {"tip": tip, "planned": pv["counts"]["pastePads"] - tip,
                        "partial": sum(g["pads"] for g in chk["groups"] if g.get("partial"))}
        res["check"] = {"fixable": chk["fixable"], "groups": groups_by_package(chk)}
        ok["split"] = res["split"] == NANO_TOP
        fix = {k: v[0] for k, v in res["check"]["groups"].items() if v[0]["fixable"]}
        ok["groups"] = (sorted(fix) == ["LED_0805_2012Metric", "SW_SPST_3.9x2.9mm"]
                        and (fix["SW_SPST_3.9x2.9mm"]["steps"], fix["SW_SPST_3.9x2.9mm"]["perPad"]) == (800, 2)
                        and (fix["LED_0805_2012Metric"]["steps"], fix["LED_0805_2012Metric"]["perPad"],
                             fix["LED_0805_2012Metric"]["confidence"]) == (1600, 1, "low"))

        t0 = time.time()
        calibrate_groups(s, plan["planId"], fixable(chk))
        job = await_job(s)
        res["job_to_readings_s"] = round(time.time() - t0, 1)
        done, res["readings"] = weigh_and_type(s, job)
        res["result"] = jr = job_summary(done)
        by_pkg = {x["label"].split(" · ")[0]: x for x in jr["groups"]}
        led, sw = by_pkg.get("LED_0805_2012Metric") or {}, by_pkg.get("SW_SPST_3.9x2.9mm") or {}
        # the SW pads are characterized; no allowed dose brings the LED within -25 %/+10 % of its amount (one turn
        # +46 %, half a turn -27 % with the SIM's 2.30 mg per turn; ±33 % on the machine), so it stays provisional
        ok["result"] = (done["state"] == "done" and jr["outcome"] == "partly"
                        and sw.get("outcome") == "characterized" and led.get("outcome") == "provisional"
                        and (led.get("errorPct") or 0) > 10 and "stays provisional: 1 one-turn dot gives" in (
                            led.get("text") or "") and bool(jr["newPlanId"]))
        nxt = s.api("GET", "/api/plan/" + str(jr["newPlanId"]))
        pads = {p["padId"]: p for p in nxt["pads"]}
        res["next_plan"] = {"planId": nxt["planId"], "dots": len(nxt["dots"]),
                            "dispensed": sorted(k for k, p in pads.items() if not p.get("blocked")),
                            "led_flags": sorted({f for p in pads.values() if p["packageId"].startswith("LED_0805")
                                                 for f in p.get("flags") or []})}
        ok["next_plan"] = (all(p["packageId"].startswith("SW_SPST") and p["dotsPerPad"] == 2 and p["dotSteps"] == 800
                               for k, p in pads.items() if not p.get("blocked"))
                           and len(res["next_plan"]["dispensed"]) == 2 and res["next_plan"]["led_flags"]
                           == ["STATUS_BELOW_MIN"])
        res["run"] = padcal_run(s, nxt, cfg)
        ok["run"] = res["run"]["ok"]
    except Exception:
        res["error"] = traceback.format_exc()
        log("padcal nano:", res["error"])
    finally:
        res["checks"] = ok
        res["pass"] = len(ok) == 6 and all(ok.values()) and "error" not in res
        s.stop()
    return res


def by_hand(s, part, steps, type_key):
    """The by-hand path's engine step alone (§8): a ladder or a weighing of one dose, recorded, nothing decided."""
    r = s.api("POST", "/api/pad-calibrations", dict(attested(), steps=[part], doseSteps=steps, typeKeys=[type_key],
                                                    wait=True), timeout=900)
    return r["result"] if r.get("finished") else await_job(s)


def padcal_controls(a):
    """§11.7's negative controls (the reconnect one is in padcal_main), each from a clean blank with a new paste loaded
    (older dose records stop counting, so no control borrows another's evidence): every one ends with its reason."""
    s = Session(a, "padcal-controls")
    res, out = {"session": s.name, "variants": []}, []
    try:
        cfg, sc, res["layout"] = padcal_setup(s)
        pv, board = padcal_import(s, KICAD_PADCAL, "padcal")

        def control(name, how, act, expect, scene=None, **paste):
            wiped(s, "blank", "coupon")
            padcal_scene(s, board, scene or (name if name in scn.VARIANTS and scn.VARIANTS[name][0] else None),
                         **paste)
            s.api("POST", "/api/paste", {"name": "SIM paste", "lot": "control " + name})
            before = len(round_deposits(s, "all"))
            row = {"name": name, "changes": (scn.VARIANTS.get(name) or (None,))[0], "how": how,
                   "expected": (scn.VARIANTS.get(name) or (None, None))[1]}
            try:
                job = act()
                r = job.get("result") or {}
                row.update(state=job["state"], sentence=r.get("sentence"), outcome=r.get("outcome"),
                           laid=len(round_deposits(s, "all")) - before)
                row["pass"] = bool(expect(job, row))
                if name.startswith("ripple"):
                    row["groups"] = [{k: g.get(k) for k in ("label", "outcome", "text", "phase", "phaseLimit")}
                                     for g in r.get("groups") or []]
                    row["ladder"] = [{k: l.get(k) for k in ("steps", "pass", "failures")} for l in job.get("ladder") or []]
            except Exception:
                row["error"] = traceback.format_exc()
                row["pass"] = False
            out.append(row)
            log("padcal control", name, "PASS" if row["pass"] else "FAIL", row.get("sentence"))

        def has(*texts):
            return lambda job, row: all(t in (row.get("sentence") or "") for t in texts)

        # ripple: the full job on J and Q (shorter weighing), the readings skipped: both stay provisional, the
        # half-turn split and the quarter-turn swing named
        def ripple():
            plan = tied_plan(s, board, sc)
            s.api("PUT", "/api/config", {"padCalibration": {"weighTargetMg": 35}})
            calibrate_groups(s, plan["planId"], [g for g in fixable(plan["profileCheck"])
                                                 if g["suggestion"]["kind"] == "new"])
            job = await_job(s)
            done, _ = weigh_and_type(s, job, skip=True)
            s.api("PUT", "/api/config", {"padCalibration": {"weighTargetMg": 70}})
            return done

        def ripple_failed(job, row):
            r = job["result"]
            groups = {g["label"].split(" · ")[0]: g for g in r["groups"]}
            quarter = [l for l in job["ladder"] if l["steps"] == 400]
            return (r["outcome"] == "provisional" and len(groups) == 2
                    and all(g["outcome"] == "provisional" for g in groups.values())
                    and "the two start angles of the half-turn dots differ" in groups["SW_SPST_3.9x2.9mm"]["text"]
                    and quarter and quarter[0]["pass"] is False
                    and any("the quarter-turn dots swing" in f for f in quarter[0]["failures"]))
        control("ripple-0.45", "Calibrate automatically J and Q (weighTargetMg 35), readings skipped; ripple %.1f at "
                "the worst offset (PC §20.1 item 15)" % RIPPLE_CLEAR_FAIL, ripple, ripple_failed,
                ripple=RIPPLE_CLEAR_FAIL)
        control("report-count-off", "by hand: quarter-turn ladder", lambda: by_hand(s, "ladder", 400, "chip-0603"),
                lambda job, row: job["state"] == "failed" and row["laid"] == 0 and has(
                    "I11: the controller did not report the B step count (M114 Count): the start angle of a partial-turn "
                    "dose cannot be set. Nothing was sent.")(job, row))
        control("thread-zero-0.65", "by hand: quarter-turn ladder", lambda: by_hand(s, "ladder", 400, "chip-0603"),
                lambda job, row: job["state"] == "done" and re.search(
                    r"quarter-turn dots trailed a paste thread to the next dot \d+ times in \d+ moves \(at most \d+ "
                    r"allowed\)", row.get("sentence") or ""))
        control("cv-0.20", "by hand: half-turn ladder", lambda: by_hand(s, "ladder", 800, "chip-0603"),
                lambda job, row: job["state"] == "done" and has(
                    "the half-turn dots vary", "in area beyond the screw-angle pattern")(job, row))
        control("landing-bias-0.2", "by hand: half-turn ladder", lambda: by_hand(s, "ladder", 800, "chip-0603"),
                lambda job, row: job["state"] == "done" and has(
                    "the dots landed 0.20 mm off on average (at most 0.15 mm allowed): check the needle offset")(job, row))
        control("coupon1-off-1.5", "by hand: quarter-turn weighing", lambda: by_hand(s, "weigh", 400, "chip-0603"),
                lambda job, row: job["state"] == "failed" and row["laid"] == 0 and has(
                    "Coupon 1 is not at its place: its edge is 1.5 mm inside the weigh grid's margin")(job, row))
        control("needle-gap-0.25", "by hand: half-turn ladder", lambda: by_hand(s, "ladder", 800, "chip-0603"),
                lambda job, row: job["state"] == "failed" and has("were not found")(job, row))

        def dead():
            s.api("DELETE", "/api/surfaces/blank")  # the job probes the blank first
            return by_hand(s, "ladder", 800, "chip-0603")
        control("idle-dead-0", "by hand: half-turn ladder, no blank surface (the job probes it first)", dead,
                lambda job, row: job["state"] == "done" and (job["steps"][0]["text"] or "").startswith(
                    "blank surface probed: ") and (row.get("sentence") or "").endswith("every camera check passed.")
                and "P7" in [p.get("id") for p in job["result"].get("predictions") or []])
        dwell = cfg["profile"]["dwellMs"]

        def short_dwell():
            s.api("PUT", "/api/config", {"profile": {"dwellMs": 50}})
            s.api("POST", "/api/screw/start-angle", {"note": "sim padcal: dwell 50 ms"})
            try:
                return by_hand(s, "ladder", 1600, "chip-0805")
            finally:
                s.api("PUT", "/api/config", {"profile": {"dwellMs": dwell}})
                s.api("POST", "/api/screw/start-angle", {"note": "sim padcal: dwell back"})
        control("dwell-50-one-turn", "by hand: one-turn ladder at profile dwellMs 50", short_dwell,
                lambda job, row: job["state"] == "failed" and row["laid"] == 0 and has("asks the screw for", "I6")(
                    job, row))
    except Exception:
        res["error"] = traceback.format_exc()
        log("padcal controls:", res["error"])
    finally:
        res["variants"] = out
        names = sorted(r["name"] for r in out)
        res["pass"] = (names == sorted(k for k in scn.VARIANTS if k != "reconnect") and all(r["pass"] for r in out)
                       and "error" not in res)
        s.stop()
    return res


def scenario_padcal(a):
    """PROFILE_CALIBRATION_DESIGN.md §14.5, one session per part (types and records of one part are no evidence in the
    next): main (padcal.kicad_pcb, Calibrate automatically, the run, the reconnect control), nano (the next job's
    upload, split and calibration), controls (the other negative controls of §11.7)."""
    res = {}
    for part in a.padcal_parts:
        t0 = time.time()
        res[part] = {"main": padcal_main, "nano": padcal_nano, "controls": padcal_controls}[part](a)
        res[part]["seconds"] = round(time.time() - t0)
        log("padcal", part, "PASS" if res[part]["pass"] else "FAIL", "%d s" % res[part]["seconds"])
    res["pass"] = all(res[p]["pass"] for p in a.padcal_parts) and set(a.padcal_parts) == set(PADCAL_PARTS)
    return res


# --------------------------------------------------------------------------- DPH estimate

def stream_tags(streamed, n_purge):
    """Per-line tags: session-start, purge, work dot k (lines after dot k-1's lift through dot k's lift; dot 0
    carries the cross from the purge row), session-end."""
    feeds = engine_dots(streamed, {"X": 0.0, "Y": 0.0, "Z": 0.0, "B": 0.0})
    purge, work = feeds[:n_purge], feeds[n_purge:]
    codes = [strip(l) for l in streamed]
    first = feeds[0]["line"]
    begin = max(i for i in range(first) if codes[i].startswith("G4")) + 1  # after the valve settle
    purge_end = purge[-1]["lift_line"] if purge else begin - 1
    bounds = [f["lift_line"] for f in work]

    def tag_of(i):
        if i < begin:
            return "session-start"
        if i <= purge_end:
            return "purge"
        if i > bounds[-1]:
            return "session-end"
        lo, hi = 0, len(bounds) - 1
        while lo < hi:
            mid = (lo + hi) // 2
            if i <= bounds[mid]:
                hi = mid
            else:
                lo = mid + 1
        return lo
    return feeds, purge, work, tag_of


def estimate_dph(streamed, cfg, n_purge, plan_dots=None):
    """Real-machine time and DPH of the streamed board program (stock rev05 firmware limits).

    X/Y/Z limits are the firmware's (dph_estimate FW_*), not machine.xml's (200 mm/s, 1000 mm/s^2): the
    engine sends raw G-code, so only the firmware and the stream's own M204 / M201 B / M203 B apply.
    'bench_comparable' is timed as tools/disp.py times a board: from the cross after the purge row to the
    end of the last dot. Variants are text transforms of the same stream that explain the difference to
    the bench (NOT what the engine sends).
    """
    _, purge, work, tag_of = stream_tags(streamed, n_purge)
    n = len(work)

    def run(lines, rt, tag=tag_of):
        r = dph_estimate.simulate(lines, None, rt_ms=rt, tag_fn=lambda i, _l: tag(i))
        dots = {k: v for k, v in r["tags"].items() if isinstance(k, int)}
        steady = [v for k, v in dots.items() if k > 0]
        tot_work = sum(v["total"] for v in dots.values())
        tot_steady = sum(v["total"] for v in steady)
        br = {}
        for d in steady:
            for c, v in d.items():
                if c != "total":
                    br[c] = br.get(c, 0.0) + v
        return {"bench_comparable_seconds": round(tot_work, 3), "bench_comparable_dph": round(3600 * n / tot_work),
                "steady_state_dots": len(steady), "seconds_per_dot": round(tot_steady / len(steady), 4),
                "dph_steady": round(3600 * len(steady) / tot_steady),
                "per_dot_breakdown_s": {k: round(v / len(steady), 4) for k, v in sorted(br.items())},
                "dot0_seconds_incl_cross_from_purge_row": round(dots[0]["total"], 3),
                "program_seconds": round(r["seconds"], 2), "dph_program": round(3600 * n / r["seconds"]),
                "non_dot_seconds": {k: round(v["total"], 3) for k, v in r["tags"].items() if not isinstance(k, int)}}

    out = {"model": "tools/instancelabs/dph_estimate.py (Marlin-like trapezoids, junction deviation 0.013 mm, "
                    "G4/M400 drain, MINIMAL_STEP_RATE 120 steps/s, host round trip rt per line)",
           "limits": "firmware defaults (X/Y 1500 mm/s, Z 500 mm/s, 10000 mm/s^2) + the stream's M204 S / M201 B / "
                     "M203 B", "work_dots": n, "purge_dots": len(purge),
           "engine": {k: cfg.get(k) for k in ("travelFeedMmS", "travelAccelMmS2", "zDownFeedMmS", "zLiftFeedMmS",
                                              "zLiftAccelMmS2", "bMaxFeedDegS", "hopHeightMm", "travelSafeZMm",
                                              "pumpToValveSettleMs", "valveSettleMs", "honorBacklash")},
           "profile": cfg.get("profile")}
    for rt in (0.0, 1.5):
        out["as_streamed_rt%.1fms" % rt] = run(streamed, rt)

    # (1) the cross from the purge row at the bench's ZCROSS (raw 49.5) instead of the travel Z
    cross = list(streamed)
    if purge and work:
        lift = next(i for i in range(purge[-1]["lift_line"] + 1, work[0]["line"]) if axes_of(cross[i]) == {"Z"})
        cross[lift] = "G1 Z%.4f F3000" % dph_estimate.DISP_ZCROSS
        out["whatif_cross_at_bench_ZCROSS_rt0.0ms"] = run(cross, 0.0)
    # (2) without OpenPnP's one-sided backlash approach (the second, half-speed XY move of each travel): disp.py
    #     moves XY once per dot. The tags follow the original line numbers.
    keep = [i for i, l in enumerate(streamed)
            if not (i > 0 and axes_of(l) and axes_of(l) <= {"X", "Y"} and axes_of(streamed[i - 1]) <= {"X", "Y"}
                    and axes_of(streamed[i - 1]))]
    out["backlash_approach_moves"] = len(streamed) - len(keep)
    out["whatif_no_backlash_approach_rt0.0ms"] = run([streamed[i] for i in keep], 0.0, lambda j: tag_of(keep[j]))

    # (3) dph_estimate.profile(): the bench recipe with this plan's real pitches (dots 1..n-1, the order the
    #     engine streams) and with the bench run's own order (runs/p18_board1.json)
    p = cfg["profile"]
    knobs = dict(net_steps_per_dot=p["netStepsPerDot"], suck_back_deg=p["suckBackDeg"], screw_rpm=p["screwRpm"],
                 screw_accel=p["screwAccelDegS2"], dwell_ms=p["dwellMs"], xy_accel=cfg["travelAccelMmS2"],
                 hop_mm=cfg["hopHeightMm"], z_feed=cfg["zDownFeedMmS"] * 60, xy_feed=cfg["travelFeedMmS"] * 60)
    out["profile_function"] = {"knobs": knobs}
    if plan_dots:
        pit = [math.hypot(plan_dots[k]["x"] - plan_dots[k - 1]["x"], plan_dots[k]["y"] - plan_dots[k - 1]["y"])
               for k in range(1, len(plan_dots))]
        out["profile_function"]["engine_order"] = profile_summary(dph_estimate.profile(pit, rt_ms=0.0, **knobs), pit)
    bench = json.load(open(os.path.join(RUNS, "p18_board1.json")))
    bp = [math.hypot(bench[k]["x"] - bench[k - 1]["x"], bench[k]["y"] - bench[k - 1]["y"]) for k in range(1, len(bench))]
    out["profile_function"]["bench_p18_board1_order"] = profile_summary(dph_estimate.profile(bp, rt_ms=0.0, **knobs), bp)
    out["bench_p18_board1_measured"] = json.load(open(os.path.join(RUNS, "p18_board1", "timing.json")))
    return out


def profile_summary(r, pitches):
    return {"dots": r["dots"], "path_mm": round(sum(pitches), 1), "s_per_dot": round(r["s_per_dot"], 4),
            "dph": round(r["dph"]), "net_deg": round(r["net_deg"], 4), "forward_deg": round(r["forward_deg"], 4)}


def bench_validation():
    """dph_estimate's replay of the measured bench runs (tools/disp.py streams) vs timing.json."""
    runs = [os.path.join(RUNS, r) for r in ("p18_board1", "p16_board1")]
    out = {}
    for rt in (0.0, 1.5):
        rows = []
        for run in runs:
            if not os.path.exists(os.path.join(run, "dots.csv")):
                continue
            pre, lines, tags = dph_estimate.bench_lines(run)
            meas = json.load(open(os.path.join(run, "timing.json")))
            r = dph_estimate.simulate(pre + lines, rt_ms=rt)
            rows.append({"run": os.path.basename(run), "dots": meas["n"], "measured_s": round(meas["seconds"], 3),
                         "predicted_s": round(r["seconds"], 3),
                         "error_pct": round(100 * (r["seconds"] - meas["seconds"]) / meas["seconds"], 2),
                         "measured_dph": round(meas["dph"]), "predicted_dph": round(3600 * meas["n"] / r["seconds"])})
        out["rt%.1fms" % rt] = rows
    return out


# --------------------------------------------------------------------------- main

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("what", choices=["all", "load", "stop", "board", "register", "import", "padcal", "bench", "dph"])
    ap.add_argument("--stream", help="dph: a saved board-run-streamed.gcode")
    ap.add_argument("--purge-dots", type=int, default=8, help="dph: purge dots at the start of --stream")
    for k, v in DEF.items():
        ap.add_argument("--" + k.replace("_", "-"), dest=k, default=v)
    ap.add_argument("--prefix", default="il-acc-", help="uinav session name prefix")
    ap.add_argument("--api-port", type=int, help="the sessions' web API ports: the first free one of N … N+8 "
                                                 "(default any free port)")
    ap.add_argument("--padcal-parts", default=",".join(PADCAL_PARTS),
                    help="padcal: the parts to run (%s; the scenario passes only with all three)" % ", ".join(PADCAL_PARTS))
    a = ap.parse_args(argv)
    a.padcal_parts = tuple(x for x in a.padcal_parts.split(",") if x)
    if not a.padcal_parts or set(a.padcal_parts) - set(PADCAL_PARTS):
        ap.error("--padcal-parts: a comma-separated subset of " + ",".join(PADCAL_PARTS))
    a.jar = os.path.abspath(a.jar)  # uinav starts java in its session dir
    if a.what == "dph":
        res = json.load(open(os.path.join(os.path.dirname(os.path.abspath(a.stream)), "results.json")))
        cfg = res["board"]["engine_config_used"]
        print(json.dumps({"dph": estimate_dph(open(a.stream).read().splitlines(), cfg, a.purge_dots),
                          "dph_model_bench_validation": bench_validation()}, indent=1))
        return 0
    os.makedirs(a.out, exist_ok=True)
    live_before = {f: os.stat(os.path.join(a.live, f)).st_mtime for f in ("machine.xml", "packages.xml", "parts.xml", "boards.xml")}
    results = {"jar": a.jar, "patched": a.patched, "dispense_config": a.dispense_config, "board": a.board,
               "sim_only_changes": ["head visual-homing-method=None (uinav --xml-set; not in the load scenario)",
                                    "B axis 2400 deg/s, 20000 deg/s^2 in the machine.xml copy (uinav --xml-set; the "
                                    "engine's M203/M201 B; not in the load scenario)",
                                    "needle Z frame %s and offset %.2f confirmed (web API; I4)" % (NEEDLE_FRAME, NEEDLE_OFFSET),
                                    "board surface: runs/zprobe_r7.json at the board location, tied to the plan's "
                                    "board registration (web API; I4)",
                                    "blank surface: bench N1 probes y 232/238 (web API; I4)",
                                    "purge row %s (web API; I9)" % PURGE_ROW,
                                    "screw start angle confirmed after connect (web API; I11)",
                                    "cleanPadCheck.enabled=false (web API; I10: static simulated top camera)",
                                    "valveSettleMs=3000, the bench's (web API; the dist file is schema 2 with 1500)",
                                    "chip-0603 status characterized (web API; §5.7 rule)",
                                    "the Top camera is the SIM top camera, 1920 x 1080 (uinav --xml-set; not in the "
                                    "load scenario)",
                                    "the SIM scene (instancelabs/sim-scene.json in the copy; not in the load scenario): "
                                    "the board truly at BOARD_LOC; register and import move it (their results name "
                                    "the seats)",
                                    "register, import: cleanPadCheck on (the SIM camera draws the pads), the "
                                    "simulator's probe timings %s after the first registration" % scn.FAST_TIMINGS,
                                    "padcal: the SIM scene's pad-calibration blocks (PROFILE_CALIBRATION_DESIGN.md "
                                    "§11.3: SIM paste with 2.30 mg per turn, two weigh coupons, the SIM balance on "
                                    "display, the bench blank colour); the blank surface typed from the scene's plane, "
                                    "the purge row 5 mm above the SIM blank's lower edge, the calibration area and the "
                                    "blank place above it, the coupon places on the scene's coupons (web API); "
                                    "refuseFlaggedPads false for the runs (no per-run skip yet, PC §20 decision 6)"],
               "started": time.strftime("%Y-%m-%d %H:%M:%S")}
    rpath = os.path.join(a.out, "results.json")

    def save():
        with open(rpath, "w") as f:
            json.dump(results, f, indent=1)

    if a.what in ("all", "load"):
        results["load"] = scenario_load(a)
        log("load", "PASS" if results["load"]["pass"] else "FAIL")
        save()
    if a.what in ("all", "stop"):
        results["stop"] = []
        scenario_stop(a, results["stop"], save)
    if a.what in ("all", "board"):
        results["board"] = scenario_board(a)
        log("board", "PASS" if results["board"]["pass"] else "FAIL")
        save()
    if a.what in ("all", "register"):
        results["register"] = scenario_register(a)
        log("register", "PASS" if results["register"]["pass"] else "FAIL")
        save()
    if a.what in ("all", "import"):
        results["import"] = scenario_import(a)
        log("import", "PASS" if results["import"]["pass"] else "FAIL")
        save()
    if a.what in ("all", "padcal"):
        results["padcal"] = scenario_padcal(a)
        log("padcal", "PASS" if results["padcal"]["pass"] else "FAIL")
        save()
    if a.what in ("all", "board", "bench"):
        results["dph_model_bench_validation"] = bench_validation()
        save()
    live_after = {f: os.stat(os.path.join(a.live, f)).st_mtime for f in live_before}
    results["live_config_untouched"] = live_before == live_after
    results["finished"] = time.strftime("%Y-%m-%d %H:%M:%S")
    save()
    print(rpath)


if __name__ == "__main__":
    sys.exit(main())
