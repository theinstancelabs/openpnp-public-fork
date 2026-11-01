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

    il_sim_acceptance.py [--out DIR] all | load | stop | board | bench | dph --stream FILE

Sim-only changes on top of the patched copy (reported in results.json):
  * machine.xml copy (uinav --xml-set; not in the load scenario): head visual-homing-method=None (the
    simulated camera has no homing fiducial); B axis limits raised to the engine's M203/M201 B (2400 deg/s,
    20000 deg/s^2) so OpenPnP's simulated controller does not time the screw at machine.xml's 100 deg/s;
  * through the web API, in the session copy only: needle Z frame (N1 5.5, bench relation K 65.59) and
    its offset confirmed (I4), board and blank surfaces from the bench N1 probes (I4), the bench purge row
    (I9), screw start angle confirmed after connect (I11), the clean-pad guard off (I10: the simulated
    top camera is a static image), valve settle 3000 ms (bench), chip-0603 'characterized' (§5.7 rule).
Python 3 standard library only.
"""
import argparse
import json
import math
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, os.path.join(REPO, "tools", "instancelabs"))
import dph_estimate  # noqa: E402
import make_machine_patch as patch  # noqa: E402

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
                ".//axis[@letter='B']/acceleration-per-second-2@value=20000.0"]
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

def stage(a, name):
    """Config copy: live dir (read only) + patched machine.xml + engine config + test script + board."""
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
    return st


class Session:
    def __init__(self, a, name, xml_sets=SIM_XML_SETS):
        self.a, self.name = a, a.prefix + name
        self.stage = stage(a, name)
        self.port = free_port()
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


def plan_board(s):
    """Plan, tie the board surface to that plan's board registration (I4), then plan on the surface."""
    board = os.path.join(s.cfg, "uinav-boards")
    files = [os.path.join(board, f) for f in sorted(os.listdir(board)) if f.endswith(os.path.basename(s.a.board))]
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
    s = Session(a, "load", xml_sets=[])
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
    ap.add_argument("what", choices=["all", "load", "stop", "board", "bench", "dph"])
    ap.add_argument("--stream", help="dph: a saved board-run-streamed.gcode")
    ap.add_argument("--purge-dots", type=int, default=8, help="dph: purge dots at the start of --stream")
    for k, v in DEF.items():
        ap.add_argument("--" + k.replace("_", "-"), dest=k, default=v)
    ap.add_argument("--prefix", default="il-acc-", help="uinav session name prefix")
    a = ap.parse_args(argv)
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
                                    "chip-0603 status characterized (web API; §5.7 rule)"],
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
