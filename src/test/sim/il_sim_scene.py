#!/usr/bin/env python3
"""Instance Labs SIM scene: where the simulated board truly sits, for the SIM vacuum sensor and top camera.

Writes <configDir>/instancelabs/sim-scene.json (read by org.openpnp.instancelabs.sim.SimScene; format in
docs/instancelabs/AUTO_REGISTER_DESIGN.md §9.2). Only into a config COPY (a uinav SIM session's stage), never
into the live ~/.openpnp2. Fiducials, pads and the board size are read from the board file (placements, its
<dimensions>) and, when a config dir is given, the footprints of its parts.xml / packages.xml: nothing here is
specific to one board, so an imported board works the same way.

Affines are [[a, b, tx], [c, d, ty]], board mm -> machine mm (SimScene's "affine"). The pivot is the board
origin: the board origin lands at (tx, ty).

Pad calibration (docs/instancelabs/PROFILE_CALIBRATION_DESIGN.md §11.3, §11.7): padcal() adds the SIM paste model
(whose mass per turn, footprint law, ripple and noise differ on purpose from the engine's), two weigh coupons, the
SIM balance and the bench blank colour; variant() applies one negative control. The N2 offset and the needle Z offset
are read from the config copy (machine.xml, instancelabs/dispense-config.json) when it has them.

Python API (tests compute every expected value from the transforms):
  seat(x, y, rot_deg, sx=1, sy=1, shear_deg=0)   T(x, y)·R(rot)·Shear·Scale (Utils2D.affineInfo's order)
  reseat(base, dx, dy, drot_deg)                 a re-seat in the machine frame about base(board origin)
  apply(affine, bx, by), compose(a, b), invert(a)
  fiducials(board_file, config_dir=None)         [{name, bx, by, d, mask}] from type="Fiducial" placements
  pads(board_file, config_dir=None)              {pad id: (bx, by)}: footprint pads, else the part centre
  min_pad_shift(board_file, a, b), moved_mm(board_file, a, b)
  scene(...) -> dict, write(config_dir, scene), board_contact_z(scene, bx, by), blank_contact_z(scene, x, y)
  CAMERA_XML_SETS, FAST_TIMINGS, FAST_VACUUM
  padcal(sc, paste=True, coupons=True, balance="display", bench_blank=True, config_dir=None), variant(sc, name, ...),
  needle_offsets(config_dir), PASTE, COUPONS, BENCH_BLANK_BGR, VARIANTS

CLI:
  il_sim_scene.py write --config DIR --board FILE --x X --y Y --rot DEG --z Z
                        [--dx --dy --drot --sx --sy --shear] [--slope-x --slope-y] [--leaky bx,by[,r]]...
                        [--hide FID]... [--dirty-spot bx,by]... [--blank x0,y0,x1,y1 --blank-z Z
                        --blank-slope-x --blank-slope-y] [--tip normal|missing|blocked|part]
                        [--upp-error ex,ey] [--fast] [PADCAL]
  il_sim_scene.py amend --config DIR [PADCAL]      the session's scene file, changed in place (it reloads on its stamp)
  il_sim_scene.py selftest [--board FILE] [--config DIR]
  PADCAL: [--paste] [--coupons] [--balance display|connected] [--bench-blank] [--variant NAME]
Python 3 standard library only.
"""
import argparse
import json
import math
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET

SCENE_REL = os.path.join("instancelabs", "sim-scene.json")
LIVE_CONFIG = os.path.join(os.path.expanduser("~"), ".openpnp2")
# Installs the SIM top camera in a uinav SIM session (uinav first turns Top into an ImageCamera).
CAMERA_XML_SETS = [".//camera[@name='Top']@class=org.openpnp.instancelabs.sim.SimBoardCamera",
                   ".//camera[@name='Top']@width=1920",
                   ".//camera[@name='Top']@height=1080"]
# Engine settings (PUT /api/config) for fast simulated probes: refused on a real machine (spec §4.4).
FAST_TIMINGS = {"vacuumProbe": {"vacuumSettleMs": 300, "readDelayMs": 0}}
# Sensor time constants that go with FAST_TIMINGS: a baseline 300 ms after the valve opens, and reads right
# after M400, would otherwise be read before the simulated vacuum settles (it follows the wall clock).
FAST_VACUUM = {"tauS": 0.03, "sealTauS": 0.0}
FID_DIAMETER = re.compile(r"(?i)fiducial[_-]?(\d+(?:\.\d+)?)mm")
FID_MASK = re.compile(r"(?i)mask(\d+(?:\.\d+)?)mm")
# Bench defaults (SimScene): board plane from BOARD_PROBES_R7, blank from BLANK_PROBES (z0 at the rect corner).
DEFAULT_BLANK = {"rect": [280.0, 225.0, 365.0, 250.0], "surface": {"z0": 5.62, "slopeX": -0.0012, "slopeY": 0.0}}
# Pad calibration (§11.3). mRevMg 2.30 differs on purpose from the configured 2.11 (tools/lumen.py:97): a weighing
# must find 1.15 mg per half turn, not 1.06. needleZOffsetMm None = the engine configuration's.
PASTE = {"mRevMg": 2.30, "ripple": 0.15, "ripplePeakDeg": 299, "cv": 0.08, "seed": 11,
         "footprint": {"c": 0.875, "e": 0.77},
         "transferRatio": 0.75, "oozeFirst": 0.40, "refillDip": 0.30, "refillTauDots": 2, "idleFullS": 300,
         "idleDeadS": 3600, "threadShare": 0.5, "threadFullMg": 0.35, "threadZeroMg": 0.45, "missBelowMg": 0.12,
         "maxGapMm": 0.20, "needleZOffsetMm": None, "feedLimitMgS": 3.0,
         "landingSdMm": 0.03, "landingBiasMm": [0, 0], "n2OffsetMm": [25.291, -75.926], "reportCount": True}
COUPONS = [{"place": "coupon1", "rect": [330, 40, 370, 70], "z": 5.80, "bgr": [206, 25, 0], "massMg": 4100},
           {"place": "coupon2", "rect": [330, 75, 370, 105], "z": 5.80, "bgr": [206, 25, 0], "massMg": 4060}]
BALANCE = {"resolutionMg": 1, "noiseMg": 0.4, "settleS": 2}
BALANCE_MODES = ("display", "connected")
# The bench blank under the ring light (runs/p18_half frames); paste on it is 56 levels of blue away.
BENCH_BLANK_BGR = [206, 25, 0]
# ripple-0.45 puts the engine's 298 deg start (count based) at the ripple's worst phase for half turns.
RIPPLE_WORST_OFFSET_STEPS = 405
# cv-0.20 is §11.7's "cv 0.16" row: the tip bead (transferRatio 0.75) averages each dot's mass noise with the dots
# before it, so 0.16 gives an area CV of 0.123, which passes the repeat check on about one ladder in five; 0.20
# gives 0.154 (§11.7 meant 0.147 without the bead).
REPEAT_FAIL_CV = 0.20
# The negative controls of §11.7: name -> (what the scene changes, None for a test action; the expected outcome).
VARIANTS = {
    "report-count-off": ("paste.reportCount false", "the quarter-turn block refuses (I11 count text)"),
    "ripple-0.45": ("paste.ripple 0.45, offsetSteps %d" % RIPPLE_WORST_OFFSET_STEPS,
                    "the phase checks fail; J and Q stay provisional"),
    "thread-zero-0.65": ("paste.threadZeroMg 0.65", "quarter-turn threads > k95: Q stays provisional, text names threads"),
    "cv-0.20": ("paste.cv %.2f" % REPEAT_FAIL_CV, "the repeat check fails"),
    "landing-bias-0.2": ("paste.landingBiasMm [0.2, 0]", "the landing check fails (points to the needle offset)"),
    "coupon1-off-1.5": ("coupon1 rect moved +1.5 mm in X", "step 2 refuses (Coupon 1 is not at its place: its edge is "
                        "1.5 mm inside the weigh grid's margin)"),
    "needle-gap-0.25": ("paste.needleZOffsetMm = the engine's - 0.15 (true gap 0.25 for a commanded 0.10)",
                        "misses: transfer fails"),
    "reconnect": (None, "a reconnect between calibration and run: the run refuses the 400 block (I11) until the start "
                  "angle is confirmed; a new job cannot reuse the old quarter optical record"),
    "idle-dead-0": ("paste.idleDeadS 0", "the purge absorbs the dead first dots: the ladder still passes (P7 reported)"),
    "dwell-50-one-turn": (None, "profile dwellMs 50 with one-turn dots: I6-feed refuses before anything moves"),
}


# --------------------------------------------------------------------------- affines

def seat(x, y, rot_deg, sx=1.0, sy=1.0, shear_deg=0.0):
    """T(x, y)·R(rot)·Shear·Scale: board origin at (x, y); shear is an x-shear (tan(shear) in column 2)."""
    c, s = math.cos(math.radians(rot_deg)), math.sin(math.radians(rot_deg))
    k = math.tan(math.radians(shear_deg))
    return [[c * sx, (c * k - s) * sy, float(x)],
            [s * sx, (s * k + c) * sy, float(y)]]


def compose(a, b):
    """a ∘ b (b first)."""
    return [[a[0][0] * b[0][0] + a[0][1] * b[1][0], a[0][0] * b[0][1] + a[0][1] * b[1][1],
             a[0][0] * b[0][2] + a[0][1] * b[1][2] + a[0][2]],
            [a[1][0] * b[0][0] + a[1][1] * b[1][0], a[1][0] * b[0][1] + a[1][1] * b[1][1],
             a[1][0] * b[0][2] + a[1][1] * b[1][2] + a[1][2]]]


def invert(a):
    det = a[0][0] * a[1][1] - a[0][1] * a[1][0]
    i00, i01, i10, i11 = a[1][1] / det, -a[0][1] / det, -a[1][0] / det, a[0][0] / det
    return [[i00, i01, -(i00 * a[0][2] + i01 * a[1][2])],
            [i10, i11, -(i10 * a[0][2] + i11 * a[1][2])]]


def apply(affine, bx, by):
    return (affine[0][0] * bx + affine[0][1] * by + affine[0][2],
            affine[1][0] * bx + affine[1][1] * by + affine[1][2])


def translation(dx, dy):
    return [[1.0, 0.0, float(dx)], [0.0, 1.0, float(dy)]]


def reseat(base, dx, dy, drot_deg):
    """The board moved in the machine frame: T(base(0) + (dx, dy)) ∘ R(drot) ∘ T(−base(0)) ∘ base."""
    ox, oy = apply(base, 0.0, 0.0)
    return compose(translation(ox + dx, oy + dy),
                   compose(seat(0, 0, drot_deg), compose(translation(-ox, -oy), base)))


# --------------------------------------------------------------------------- board file

def _mm(value, units):
    return float(value) * (25.4 if (units or "Millimeters") == "Inches" else 1.0)


def _location(placement):
    loc = placement.find("location")
    u = loc.get("units")
    return _mm(loc.get("x"), u), _mm(loc.get("y"), u), float(loc.get("rotation") or 0.0)


def _footprints(config_dir):
    """part id -> (package id, [footprint pads {x, y, w, h, roundness} in mm]) from the config's parts/packages."""
    if not config_dir:
        return {}
    parts_xml, packages_xml = os.path.join(config_dir, "parts.xml"), os.path.join(config_dir, "packages.xml")
    if not (os.path.exists(parts_xml) and os.path.exists(packages_xml)):
        return {}
    packages = {}
    for pkg in ET.parse(packages_xml).getroot().iter("package"):
        fp = pkg.find("footprint")
        pads = []
        if fp is not None:
            u = fp.get("units")
            for p in fp.iter("pad"):
                pads.append({"x": _mm(p.get("x", 0), u), "y": _mm(p.get("y", 0), u),
                             "w": _mm(p.get("width", 0), u), "h": _mm(p.get("height", 0), u),
                             "roundness": float(p.get("roundness", 0))})
        packages[pkg.get("id")] = pads
    out = {}
    for part in ET.parse(parts_xml).getroot().iter("part"):
        pid = part.get("package-id")
        out[part.get("id")] = (pid, packages.get(pid, []))
    return out


def board_size(board_file):
    d = ET.parse(board_file).getroot().find("dimensions")
    if d is None:
        return 0.0, 0.0
    return _mm(d.get("x"), d.get("units")), _mm(d.get("y"), d.get("units"))


def fiducials(board_file, config_dir=None):
    """Enabled top-side type="Fiducial" placements: d from the first round footprint pad, else parsed from the
    package (or part) id as Fiducial_1mm_Mask3mm, else 1.0; mask parsed, else 3.0 (SimScene.fiducialsOf)."""
    fps = _footprints(config_dir)
    out = []
    for p in ET.parse(board_file).getroot().iter("placement"):
        if p.get("type") != "Fiducial" or p.get("enabled", "true") == "false" or p.get("side", "Top") != "Top":
            continue
        bx, by, _ = _location(p)
        pkg_id, pads = fps.get(p.get("part-id"), (None, []))
        name = pkg_id or p.get("part-id") or ""
        d = next((q["w"] for q in pads if q["roundness"] >= 100 and abs(q["w"] - q["h"]) < 1e-9 and q["w"] > 0),
                 None)
        if d is None:
            m = FID_DIAMETER.search(name)
            d = float(m.group(1)) if m else 1.0
        m = FID_MASK.search(name)
        out.append({"name": p.get("id"), "bx": bx, "by": by, "d": d, "mask": float(m.group(1)) if m else 3.0})
    return out


def pads(board_file, config_dir=None):
    """{pad id: (bx, by)} for every top-side type="Placement" placement: its footprint pads (config_dir's
    packages.xml), or the part centre when the footprint has none (pad-type packages)."""
    fps = _footprints(config_dir)
    out = {}
    for p in ET.parse(board_file).getroot().iter("placement"):
        if p.get("type") != "Placement" or p.get("side", "Top") != "Top":
            continue
        px, py, rot = _location(p)
        _, fpads = fps.get(p.get("part-id"), (None, []))
        if not fpads:
            out[p.get("id")] = (px, py)
            continue
        c, s = math.cos(math.radians(rot)), math.sin(math.radians(rot))
        for i, q in enumerate(fpads):
            out["%s.%d" % (p.get("id"), i + 1)] = (px + q["x"] * c - q["y"] * s, py + q["x"] * s + q["y"] * c)
    return out


def _shifts(points, a, b):
    out = []
    for bx, by in points:
        ax, ay = apply(a, bx, by)
        cx, cy = apply(b, bx, by)
        out.append(math.hypot(ax - cx, ay - cy))
    return out


def min_pad_shift(board_file, a, b, config_dir=None):
    """Smallest distance any pad moves between seats a and b (how sensitive a test is to the correction)."""
    return min(_shifts(pads(board_file, config_dir).values(), a, b))


def moved_mm(board_file, a, b, config_dir=None):
    """Largest fiducial move between seats a and b (the registration's movedMm)."""
    return max(_shifts([(f["bx"], f["by"]) for f in fiducials(board_file, config_dir)], a, b), default=0.0)


# --------------------------------------------------------------------------- scene

def plane(z0, slope_x=0.0, slope_y=0.0):
    return {"z0": float(z0), "slopeX": float(slope_x), "slopeY": float(slope_y)}


def scene(board_file, seat_affine, board_plane, blank=None, leaky=(), hide=(), dirty=(), dirty_spots=(),
          vacuum=None, camera=None, config_dir=None):
    """The scene dict. board_plane: plane(z0, sx, sy), N1 contact Z in board mm from the board origin.
    blank: {"rect": [x0, y0, x1, y1], "surface": plane(...) from (x0, y0), "dots": [{x, y}]} (machine mm),
    None for the bench default, False for none. leaky: [(bx, by[, r])]; dirty: pad or placement ids."""
    w, h = board_size(board_file)
    sc = {"version": 1,
          "board": {"file": os.path.basename(board_file), "size": [w, h],
                    "seat": {"affine": [list(map(float, r)) for r in seat_affine]},
                    "surface": dict(board_plane),
                    "fiducials": fiducials(board_file, config_dir),
                    "hideFiducials": list(hide), "dirtyPads": list(dirty),
                    "dirtySpots": [{"bx": float(x), "by": float(y)} for x, y in dirty_spots],
                    "leakySpots": [{"bx": float(l[0]), "by": float(l[1]), "r": float(l[2]) if len(l) > 2 else 0.6}
                                   for l in leaky]},
          "vacuum": dict(vacuum or {}),
          "camera": dict(camera or {})}
    if blank is False:
        sc["blank"] = None
    else:
        sc["blank"] = json.loads(json.dumps(blank if blank is not None else DEFAULT_BLANK))
    return sc


def board_contact_z(sc, bx, by):
    s = sc["board"]["surface"]
    return s["z0"] + s.get("slopeX", 0.0) * bx + s.get("slopeY", 0.0) * by


def blank_contact_z(sc, x, y):
    b = sc.get("blank") or DEFAULT_BLANK
    s = b.get("surface", DEFAULT_BLANK["surface"])
    x0, y0 = b.get("rect", DEFAULT_BLANK["rect"])[:2]
    return s["z0"] + s.get("slopeX", 0.0) * (x - x0) + s.get("slopeY", 0.0) * (y - y0)


def needle_offsets(config_dir):
    """(n2 [x, y] or None, needleZOffsetMm or None) from a config copy: the dispense nozzle's head offsets in
    machine.xml (instancelabs/dispense-config.json "nozzle", default N2) and the engine's needle Z offset."""
    if not config_dir:
        return None, None
    nozzle, needle_z = "N2", None
    cfg = os.path.join(config_dir, "instancelabs", "dispense-config.json")
    if os.path.exists(cfg):
        with open(cfg) as f:
            c = json.load(f)
        nozzle = c.get("nozzle", nozzle)
        needle_z = c.get("needleZOffsetMm")
    n2 = None
    machine = os.path.join(config_dir, "machine.xml")
    if os.path.exists(machine):
        for n in ET.parse(machine).getroot().iter("nozzle"):
            off = n.find("head-offsets")
            if n.get("name") == nozzle and off is not None:
                u = off.get("units")
                n2 = [_mm(off.get("x", 0), u), _mm(off.get("y", 0), u)]
                break
    return n2, needle_z


def padcal(sc, paste=True, coupons=True, balance="display", bench_blank=True, config_dir=None):
    """Adds the pad-calibration blocks (§11.3) to a scene dict: the paste model (N2 offset and needle Z offset from
    the config copy when it has them), the two weigh coupons, the SIM balance (display | connected | None) and the
    bench blank colour."""
    if paste:
        p = json.loads(json.dumps(PASTE))
        n2, needle_z = needle_offsets(config_dir)
        if n2 is not None:
            p["n2OffsetMm"] = n2
        if needle_z is not None:
            p["needleZOffsetMm"] = needle_z
        sc["paste"] = p
    if coupons:
        sc["coupons"] = json.loads(json.dumps(COUPONS))
    if balance:
        if balance not in BALANCE_MODES:
            raise ValueError("balance mode %r is not one of %s" % (balance, ", ".join(BALANCE_MODES)))
        sc["balance"] = dict(BALANCE, mode=balance)
    if bench_blank:
        if sc.get("blank") is None and "blank" in sc:
            raise ValueError("the scene has no blank to colour")
        sc["blank"] = dict(sc.get("blank") or DEFAULT_BLANK, bgr=list(BENCH_BLANK_BGR))
    return sc


def variant(sc, name, config_dir=None):
    """Applies a negative control of §11.7 to a padcal scene dict (a test-action variant leaves it alone)."""
    if name not in VARIANTS:
        raise ValueError("no variant %r (%s)" % (name, ", ".join(VARIANTS)))
    p = sc.get("paste")
    if VARIANTS[name][0] is not None and name != "coupon1-off-1.5" and p is None:
        raise ValueError("variant %s needs the paste block (--paste)" % name)
    if name == "report-count-off":
        p["reportCount"] = False
    elif name == "ripple-0.45":
        p["ripple"] = 0.45
        p["offsetSteps"] = RIPPLE_WORST_OFFSET_STEPS
    elif name == "thread-zero-0.65":
        p["threadZeroMg"] = 0.65
    elif name == "cv-0.20":
        p["cv"] = REPEAT_FAIL_CV
    elif name == "landing-bias-0.2":
        p["landingBiasMm"] = [0.2, 0.0]
    elif name == "coupon1-off-1.5":
        k = next((c for c in sc.get("coupons", []) if c["place"] == "coupon1"), None)
        if k is None:
            raise ValueError("variant coupon1-off-1.5 needs the coupons (--coupons)")
        k["rect"] = [k["rect"][0] + 1.5, k["rect"][1], k["rect"][2] + 1.5, k["rect"][3]]
    elif name == "needle-gap-0.25":
        base = p.get("needleZOffsetMm")
        if base is None:
            base = needle_offsets(config_dir)[1]
        p["needleZOffsetMm"] = (base if base is not None else 0.0) - 0.15
    elif name == "idle-dead-0":
        p["idleDeadS"] = 0
    return sc


def scene_path(config_dir):
    return os.path.join(config_dir, SCENE_REL)


def write(config_dir, sc):
    """Atomic (tmp + rename): a running session reloads it on change. Never into the live config."""
    real = os.path.realpath(config_dir)
    live = os.path.realpath(LIVE_CONFIG)
    if real == live or real.startswith(live + os.sep):
        raise SystemExit("il_sim_scene: refusing to write a sim scene into the live config %s" % LIVE_CONFIG)
    path = scene_path(config_dir)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=".sim-scene.", suffix=".tmp", dir=os.path.dirname(path))
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(sc, f, indent=1)
            f.write("\n")
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise
    return path


# --------------------------------------------------------------------------- CLI

def _floats(text, n_min, n_max=None):
    v = [float(t) for t in text.split(",")]
    if len(v) < n_min or len(v) > (n_max or n_min):
        raise argparse.ArgumentTypeError("expected %d numbers: %s" % (n_min, text))
    return v


def cmd_write(a):
    nominal = seat(a.x, a.y, a.rot, a.sx, a.sy, a.shear)
    true_seat = reseat(nominal, a.dx, a.dy, a.drot)
    blank = None
    if a.blank or a.blank_z is not None:
        rect = _floats(a.blank, 4) if a.blank else DEFAULT_BLANK["rect"]
        z0 = a.blank_z if a.blank_z is not None else DEFAULT_BLANK["surface"]["z0"]
        blank = {"rect": rect, "surface": plane(z0, a.blank_slope_x, a.blank_slope_y)}
    vacuum = dict(FAST_VACUUM) if a.fast else {}
    if a.tip:
        vacuum["tip"] = a.tip
    camera = {"uppError": _floats(a.upp_error, 2)} if a.upp_error else {}
    sc = scene(a.board, true_seat, plane(a.z, a.slope_x, a.slope_y), blank=blank,
               leaky=[_floats(l, 2, 3) for l in a.leaky], hide=a.hide,
               dirty_spots=[_floats(d, 2) for d in a.dirty_spot], vacuum=vacuum, camera=camera, config_dir=a.config)
    _padcal_args(sc, a)
    path = write(a.config, sc)
    print(json.dumps({"scene": path, "seat": true_seat, "fiducials": len(sc["board"]["fiducials"]),
                      "movedFromNominalMm": round(moved_mm(a.board, seat(a.x, a.y, a.rot), true_seat, a.config), 4),
                      "padcal": sorted(k for k in ("paste", "coupons", "balance") if k in sc)}))


def _padcal_args(sc, a):
    padcal(sc, paste=a.paste, coupons=a.coupons, balance=a.balance, bench_blank=a.bench_blank, config_dir=a.config)
    if a.variant:
        variant(sc, a.variant, a.config)


def cmd_amend(a):
    """The session's scene, changed in place: a running session reloads it on its stamp (SimScene.Source)."""
    path = scene_path(a.config)
    if not os.path.exists(path):
        raise SystemExit("il_sim_scene: no scene at %s (write one first)" % path)
    with open(path) as f:
        sc = json.load(f)
    _padcal_args(sc, a)
    write(a.config, sc)
    print(json.dumps({"scene": path, "padcal": sorted(k for k in ("paste", "coupons", "balance") if k in sc),
                      "variant": a.variant}))


def _add_padcal_args(p):
    p.add_argument("--paste", action="store_true", help="the SIM paste model (§11.3)")
    p.add_argument("--coupons", action="store_true", help="the two weigh coupons coupon1, coupon2")
    p.add_argument("--balance", choices=BALANCE_MODES, help="the SIM balance: display (typed) or connected")
    p.add_argument("--bench-blank", action="store_true", help="the bench blank colour (DotInspector sees the paste)")
    p.add_argument("--variant", choices=sorted(VARIANTS), help="one negative control of §11.7")


def _close(a, b, tol=1e-9):
    return all(abs(x - y) <= tol for x, y in zip(a, b))


def cmd_selftest(a):
    checks = []

    def check(name, ok):
        checks.append((name, bool(ok)))

    s = seat(10, 20, 90)
    check("seat: origin at (x, y)", _close(apply(s, 0, 0), (10, 20)))
    check("seat: +90 deg turns board +x to machine +y", _close(apply(s, 1, 0), (10, 21)))
    check("seat: x-shear moves +y toward +x", _close(apply(seat(0, 0, 0, 1, 1, 45), 0, 1), (1, 1)))
    check("seat: scale", _close(apply(seat(0, 0, 0, 2, 3), 1, 1), (2, 3)))
    b = seat(296.0, 114.5, -1.6, 1.002, 0.997, 0.1)
    check("invert", _close(apply(compose(invert(b), b), 3.3, 4.4), (3.3, 4.4)))
    r = reseat(b, 0.6, -0.45, 0.0)
    check("reseat: pure shift moves every point by (dx, dy)",
          _close(apply(r, 50, 60), tuple(p + d for p, d in zip(apply(b, 50, 60), (0.6, -0.45)))))
    r = reseat(b, 0.0, 0.0, 0.2)
    check("reseat: turns about the board origin", _close(apply(r, 0, 0), apply(b, 0, 0)))
    bx, by = 70.0, 0.0
    p0, p1 = apply(b, bx, by), apply(r, bx, by)
    o = apply(b, 0, 0)
    ang = math.degrees(math.atan2(p1[1] - o[1], p1[0] - o[0]) - math.atan2(p0[1] - o[1], p0[0] - o[0]))
    check("reseat: by drot", abs(ang - 0.2) < 1e-9)
    check("fiducial name parse", FID_DIAMETER.search("Fiducial_1mm_Mask3mm-Fiducial").group(1) == "1"
          and FID_MASK.search("Fiducial_1mm_Mask3mm").group(1) == "3")
    pc = padcal({"blank": None}, bench_blank=False)
    check("padcal: §11.3 blocks", pc["paste"]["mRevMg"] == 2.30 and len(pc["coupons"]) == 2
          and pc["balance"]["mode"] == "display" and json.loads(json.dumps(pc))["paste"]["needleZOffsetMm"] is None)
    check("padcal: bench blank keeps the rect", padcal({}, paste=False, coupons=False, balance=None)["blank"]
          == dict(DEFAULT_BLANK, bgr=BENCH_BLANK_BGR))
    check("variants: ten", len(VARIANTS) == 10)
    for name in VARIANTS:
        v = variant(padcal({}), name)
        check("variant %s changes the scene: %s" % (name, VARIANTS[name][0] is not None),
              (v != padcal({})) == (VARIANTS[name][0] is not None))
    check("variant needle-gap-0.25: 0.15 below the engine's", variant(padcal({}), "needle-gap-0.25")["paste"]
          ["needleZOffsetMm"] == -0.15)
    if a.board:
        f = fiducials(a.board, a.config)
        check("board fiducials found (%d)" % len(f), len(f) >= 2)
        sc = scene(a.board, b, plane(5.43), config_dir=a.config)
        check("scene is JSON", json.loads(json.dumps(sc))["board"]["file"] == os.path.basename(a.board))
        print(json.dumps({"fiducials": f, "size": board_size(a.board), "pads": len(pads(a.board, a.config))}))
    for name, ok in checks:
        print(("PASS " if ok else "FAIL ") + name)
    return 0 if all(ok for _, ok in checks) else 1


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd")
    w = sub.add_parser("write", help="write the scene into a config copy")
    w.add_argument("--config", required=True, help="the session's config COPY")
    w.add_argument("--board", required=True, help="board file (fiducials, pads and size are read from it)")
    for k in ("x", "y", "rot", "z"):
        w.add_argument("--" + k, type=float, required=True, help="nominal board location (z: N1 contact Z)")
    for k, d in (("dx", 0.0), ("dy", 0.0), ("drot", 0.0), ("sx", 1.0), ("sy", 1.0), ("shear", 0.0),
                 ("slope-x", 0.0), ("slope-y", 0.0), ("blank-slope-x", -0.0012), ("blank-slope-y", 0.0)):
        w.add_argument("--" + k, type=float, default=d)
    w.add_argument("--blank", help="x0,y0,x1,y1 (machine mm)")
    w.add_argument("--blank-z", type=float, help="blank N1 contact Z at (x0, y0)")
    w.add_argument("--leaky", action="append", default=[], help="bx,by[,r] (board mm)")
    w.add_argument("--hide", action="append", default=[], help="fiducial name not drawn")
    w.add_argument("--dirty-spot", action="append", default=[], help="bx,by: a paste dot (board mm)")
    w.add_argument("--tip", choices=["normal", "missing", "blocked", "part"])
    w.add_argument("--upp-error", help="ex,ey: true mm/px = configured x (1 + e)")
    w.add_argument("--fast", action="store_true", help="sensor time constants for FAST_TIMINGS")
    _add_padcal_args(w)
    m = sub.add_parser("amend", help="add the pad-calibration blocks (or a variant) to the session's scene")
    m.add_argument("--config", required=True, help="the session's config COPY")
    _add_padcal_args(m)
    t = sub.add_parser("selftest", help="check the transforms (and a board file)")
    t.add_argument("--board")
    t.add_argument("--config")
    a = ap.parse_args(argv)
    if a.cmd == "write":
        cmd_write(a)
        return 0
    if a.cmd == "amend":
        cmd_amend(a)
        return 0
    if a.cmd == "selftest":
        return cmd_selftest(a)
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
