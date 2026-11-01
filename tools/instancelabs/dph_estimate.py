#!/usr/bin/env python3
"""Predict real-machine dispense time from a G-code stream with Marlin-like kinematics.

Model (Marlin 2.1 as built for the LumenPnP rev05 board, il/lumen-rev05 7776c73):
  * each G0/G1 is one planner block; length = |XYZ| if any of X/Y/Z moves, else |B| (degrees),
    as get_move_distance() (Marlin/src/module/motion.cpp:1078);
  * block acceleration = M204 S (travel acceleration) limited per axis by the max acceleration
    (M201, firmware default), as Planner::_populate_block (planner.cpp:2477-2557). So a B-only move
    accelerates at the current M204 S value unless M201 B is lower;
  * nominal speed = F limited per axis by the max feedrate (M203 / firmware default);
  * junction speeds by junction deviation (JUNCTION_DEVIATION_MM 0.013) with look-ahead
    forward/backward passes; G4 and M400 drain the planner (G4.cpp:36 planner.synchronize());
  * trapezoid (constant acceleration, no S-curve: S_CURVE_ACCELERATION is off);
  * host latency: every line costs `rt_ms` of host round trip. Lines overlap motion while the
    planner has work; after a drain (G4 / M400) the controller idles until the next moves arrive:
    motion restarts when 3 moves are queued or the next sync line arrives (planner.cpp:745-750,
    BLOCK_DELAY_FOR_1ST_MOVE).
  * a block starts and ends at >= MINIMAL_STEP_RATE (120 steps/s) on its fastest axis
    (planner.cpp:727, :795-796), e.g. 27 deg/s for B at 4.44 steps/deg;
Not modelled: SLOWDOWN (only stretches segments shorter than 20 ms when 2..7 moves are queued),
step rounding, USB jitter.

Validation (bench replay of the exact tools/disp.py stream, 2026-10-27; predicted vs measured):
  rt-ms              0                  1.5                3
  p18_board1   107.76 s (+2.7%)   108.97 s (+3.9%)   110.18 s (+5.0%)   measured 104.92 s, 5,490 DPH
  p16_board1   134.32 s (+2.1%)   135.53 s (+3.0%)   136.73 s (+4.0%)   measured 131.52 s, 4,380 DPH
runs/serial.log puts the host round trip of a queued line under 1 ms (rt-ms 0 is closest); the model is a
constant ~18 ms per dot over the measured G4-to-G4 cycle (any XY pitch, 190 or 370 deg turn), consistent
with Marlin's discrete stepping finishing each block a few ms before the continuous trapezoid.

Usage:
  dph_estimate.py gcode FILE [--limits machine-limits.json] [--rt-ms 1.5] [--json]
  dph_estimate.py bench RUN_DIR... [--rt-ms 1.5]     # replay tools/disp.py runs and compare with timing.json
  dph_estimate.py profile --pitch 160*2.5 --net-steps 800 --suck-back 10 --rpm 180 --screw-accel 20000
                  --dwell-ms 250 [--xy-accel 2000] [--hop 1] [--z-feed 3000] [--rt-ms 1.5] [--json]
                  # what-if s/dot and DPH of the bench recipe; also profile() for scripts
"""
import argparse
import csv
import json
import math
import os
import re
import sys

AXES = ("X", "Y", "Z", "B")
JD_MM = 0.013
MIN_PLANNER_SPEED = 0.05

# Firmware defaults of il/lumen-rev05 (Marlin/Configuration.h:1228,1241,1258-1260)
FW_MAX_FEED = {"X": 1500.0, "Y": 1500.0, "Z": 500.0, "B": 50000.0}
FW_MAX_ACCEL = {"X": 10000.0, "Y": 10000.0, "Z": 10000.0, "B": 10000.0}
FW_TRAVEL_ACCEL = 2500.0
# Steps per unit (Configuration.h:1221) and MINIMAL_STEP_RATE (planner.cpp:727, :795-796): a block
# never starts or ends below 120 steps/s on its fastest-stepping axis.
FW_STEPS = {"X": 320.0, "Y": 320.0, "Z": 40.0, "B": 4.44}
MINIMAL_STEP_RATE = 120.0

WORD = re.compile(r"([A-Z])\s*(-?\d+(?:\.\d*)?)")


def parse(line):
    code = line.split(";", 1)[0].strip().upper()
    if not code:
        return None, {}
    words = WORD.findall(code)
    if not words:
        return None, {}
    cmd = words[0][0] + str(int(float(words[0][1])))
    return cmd, {k: float(v) for k, v in words[1:]}


def trapezoid_time(L, v0, v1, vmax, a):
    """Time for a block of length L from entry v0 to exit v1, cruise <= vmax, accel a."""
    if L <= 0:
        return 0.0
    d1 = max(0.0, (vmax * vmax - v0 * v0) / (2 * a))
    d2 = max(0.0, (vmax * vmax - v1 * v1) / (2 * a))
    if d1 + d2 <= L:
        return (vmax - v0) / a + (vmax - v1) / a + (L - d1 - d2) / vmax
    vp = math.sqrt(max(v0 * v0, v1 * v1, (2 * a * L + v0 * v0 + v1 * v1) / 2))
    return (vp - v0) / a + (vp - v1) / a


class Block:
    __slots__ = ("L", "v", "a", "u", "cat", "line", "tag", "entry", "exit", "t", "vmin")

    def __init__(self, L, v, a, u, cat, line, tag, vmin=0.0):
        self.L, self.v, self.a, self.u, self.cat, self.line, self.tag = L, v, a, u, cat, line, tag
        self.vmin = min(vmin, v)
        self.entry = self.exit = 0.0
        self.t = 0.0


def junction_vmax_sq(prev, cur):
    cos_theta = -sum(p * c for p, c in zip(prev.u, cur.u))
    if cos_theta > 0.999999:
        return MIN_PLANNER_SPEED ** 2
    cos_theta = max(cos_theta, -0.999999)
    sin_d2 = math.sqrt(0.5 * (1.0 - cos_theta))
    a = min(prev.a, cur.a)
    vj2 = a * JD_MM * sin_d2 / (1.0 - sin_d2)
    return min(vj2, prev.v ** 2, cur.v ** 2)


def plan_segment(blocks):
    """Look-ahead over blocks between two drains: entry 0, exit 0, junction-deviation limits."""
    n = len(blocks)
    if not n:
        return
    vj = [0.0] * (n + 1)  # vj[i] = max speed at the start of block i (vj[n] = 0 end)
    for i in range(1, n):
        vj[i] = math.sqrt(junction_vmax_sq(blocks[i - 1], blocks[i]))
    # backward pass
    for i in range(n - 1, -1, -1):
        b = blocks[i]
        vj[i] = min(vj[i], math.sqrt(vj[i + 1] ** 2 + 2 * b.a * b.L))
    # forward pass
    for i in range(n):
        b = blocks[i]
        vj[i + 1] = min(vj[i + 1], math.sqrt(vj[i] ** 2 + 2 * b.a * b.L))
    for i, b in enumerate(blocks):
        b.entry, b.exit = max(vj[i], b.vmin), max(vj[i + 1], b.vmin)
        b.t = trapezoid_time(b.L, b.entry, b.exit, b.v, b.a)


def simulate(lines, limits=None, rt_ms=1.5, tag_fn=None):
    """Return dict with total seconds, per-category seconds and per-tag seconds.

    limits: {"maxFeed": {axis: unit/s}, "maxAccel": {axis: unit/s^2}, "travelAccel": float}
    tag_fn(index, line) -> tag or None (e.g. the dot a line belongs to); tags inherit forward.
    """
    max_feed = dict(FW_MAX_FEED)
    max_accel = dict(FW_MAX_ACCEL)
    steps = dict(FW_STEPS)
    accel = FW_TRAVEL_ACCEL
    if limits:
        max_feed.update(limits.get("maxFeed", {}))
        max_accel.update(limits.get("maxAccel", {}))
        steps.update(limits.get("stepsPerUnit", {}))
        accel = limits.get("travelAccel", accel)
    rt = rt_ms / 1000.0
    pos = {a: 0.0 for a in AXES}
    known = {a: False for a in AXES}
    feed = 1000.0  # mm/s, until an F word
    absolute = True
    cats = {"travel_xy": 0.0, "z": 0.0, "auger_b": 0.0, "mixed": 0.0, "dwell": 0.0, "host_idle": 0.0}
    tags = {}
    tag = None
    segment = []
    seg_lines = []  # (count of lines since the last drain when each block arrived)
    t_total = 0.0
    lines_since_drain = 0
    moves_since_drain = 0
    restart_lines = None  # lines needed after a drain before motion starts

    def add_tag(tg, cat, dt):
        if tg is None:
            return
        d = tags.setdefault(tg, {"total": 0.0})
        d[cat] = d.get(cat, 0.0) + dt
        d["total"] += dt

    def drain(extra_dwell=0.0, sync_tag=None):
        nonlocal segment, t_total, lines_since_drain, moves_since_drain, restart_lines
        plan_segment(segment)
        # host idle before motion restarts after the previous drain
        if segment and restart_lines is not None:
            idle = restart_lines * rt
            cats["host_idle"] += idle
            add_tag(segment[0].tag, "host_idle", idle)
            t_total += idle
        for b in segment:
            cats[b.cat] += b.t
            add_tag(b.tag, b.cat, b.t)
            t_total += b.t
        # the sync line's own round trip (its ok) + the dwell
        cats["dwell"] += extra_dwell
        add_tag(sync_tag, "dwell", extra_dwell)
        t_total += extra_dwell + rt
        cats["host_idle"] += rt
        add_tag(sync_tag, "host_idle", rt)
        segment = []
        lines_since_drain = 0
        moves_since_drain = 0
        restart_lines = None

    for i, raw in enumerate(lines):
        if tag_fn is not None:
            tg = tag_fn(i, raw)
            if tg is not None:
                tag = tg
        if raw.strip() == "; @t0":
            # clock start (e.g. the host timer of a measured run): settle what came before, zero
            if segment:
                drain(0.0, tag)
            t_total = 0.0
            for k in cats:
                cats[k] = 0.0
            tags.clear()
            lines_since_drain = 0
            moves_since_drain = 0
            restart_lines = None
            continue
        cmd, w = parse(raw)
        if cmd is None:
            continue
        lines_since_drain += 1
        if cmd in ("G0", "G1"):
            if "F" in w:
                feed = w["F"] / 60.0
            target = dict(pos)
            for a in AXES:
                if a in w:
                    target[a] = w[a] if absolute else pos[a] + w[a]
            d = {a: target[a] - pos[a] if known[a] else 0.0 for a in AXES}
            for a in AXES:
                if a in w:
                    known[a] = True
            pos = target
            lin = math.sqrt(d["X"] ** 2 + d["Y"] ** 2 + d["Z"] ** 2)
            L = lin if lin > 1e-9 else abs(d["B"])
            if L < 1e-9:
                continue
            u = tuple(d[a] / L for a in AXES)
            v = feed
            acc = accel
            for k, a in enumerate(AXES):
                ua = abs(u[k])
                if ua > 0:
                    v = min(v, max_feed[a] / ua)
                    acc = min(acc, max_accel[a] / ua)
            moved = [a for a in AXES if abs(d[a]) > 1e-9]
            if set(moved) <= {"X", "Y"}:
                cat = "travel_xy"
            elif moved == ["Z"]:
                cat = "z"
            elif moved == ["B"]:
                cat = "auger_b"
            else:
                cat = "mixed"
            vmin = MINIMAL_STEP_RATE / max(steps[a] * abs(u[k]) for k, a in enumerate(AXES))
            segment.append(Block(L, v, acc, u, cat, raw, tag, vmin))
            moves_since_drain += 1
            if restart_lines is None and moves_since_drain >= 3:
                restart_lines = lines_since_drain
        elif cmd == "G4":
            dwell = w.get("P", 0.0) / 1000.0 + w.get("S", 0.0)
            if restart_lines is None:
                restart_lines = lines_since_drain
            drain(dwell, tag)
        elif cmd in ("M400", "M410"):
            if restart_lines is None:
                restart_lines = lines_since_drain
            drain(0.0, tag)
        elif cmd == "M204":
            if "S" in w:
                accel = w["S"]
            if "T" in w:
                accel = w["T"]
        elif cmd == "M201":
            for a in AXES:
                if a in w:
                    max_accel[a] = w[a]
        elif cmd == "M203":
            for a in AXES:
                if a in w:
                    max_feed[a] = w[a]
        elif cmd == "G92":
            for a in AXES:
                if a in w:
                    pos[a] = w[a]
                    known[a] = True
        elif cmd == "G90":
            absolute = True
        elif cmd == "G91":
            absolute = False
    if segment:
        if restart_lines is None:
            restart_lines = lines_since_drain
        drain(0.0, tag)
    return {"seconds": t_total, "categories": cats, "tags": tags}


# --------------------------------------------------------------------------- bench replay

# tools/disp.py constants: needle = camera + OFF (only shifts coordinates, so timing does not depend on
# it), defaults of the per-dot recipe, travel height above the dot, cross height, XY/Z acceleration.
DISP_OFF = (25.291, -75.926)
DISP_RETRACT, DISP_DWELL_MS, DISP_DZ, DISP_ZCROSS, DISP_ACC = 10.0, 100, 1.0, 49.5, 2000


def disp_dot(x, y, z, b, th, retract, dwell_ms, bf, bf_retract, bacc=None, brk=None, cross=False,
             xy_feed=12000, z_feed=3000, acc=DISP_ACC, dz=DISP_DZ):
    """The lines tools/disp.py dot() sends for one dot (x, y = needle; b = B before the feed)."""
    out = ["G1 Z%.3f F3000" % DISP_ZCROSS] if cross else []
    out += ["G1 X%.3f Y%.3f F%g" % (x - DISP_OFF[0], y - DISP_OFF[1], xy_feed), "G1 Z%.3f F%g" % (z, z_feed)]
    if bacc:
        out.append("M204 S%g" % bacc)
    out += ["G1 B%.2f F%g" % (b + th, bf), "G4 P%d" % dwell_ms, "G1 B%.2f F%g" % (b + th - retract, bf_retract)]
    if bacc:
        out.append("M204 S%g" % acc)
    if brk:
        out += ["G1 Z%.3f F%g" % (z - brk[0], brk[1]), "G4 P%d" % brk[2]]
    out.append("G1 Z%.3f F%g" % (z - dz, z_feed))
    return out


def bench_lines(run_dir):
    """Rebuild the exact stream tools/disp.py sent for a run (pre, timed lines, per-line dot tags).

    dots.csv gives x, y, z, the forward turn actually sent (theta) and B before it (b_start);
    timing.json the session feed (bf), screw acceleration (bacc) and purge count; the plan
    <run_dir>.json (when present, in dots.csv order after the purge rows) the per-dot dwell,
    suck-back, feed, cross and break-off. Purge dots go into `pre` (before the clock); the cross to
    the first work dot (Z to ZCROSS, then XY) is timed, as in disp.py.
    """
    rows = list(csv.DictReader(open(os.path.join(run_dir, "dots.csv"))))
    meas = json.load(open(os.path.join(run_dir, "timing.json")))
    plan_file = run_dir.rstrip("/") + ".json"
    plan = json.load(open(plan_file)) if os.path.exists(plan_file) else []
    bf = float(meas.get("bf") or 43200)
    bacc = float(meas["bacc"]) if meas.get("bacc") else None
    purge = [r for r in rows if r["id"] == "purge"]
    work = [r for r in rows if r["id"] != "purge"]
    if len(purge) != meas.get("purge", len(purge)) or len(work) != meas["n"]:
        raise ValueError("%s: dots.csv has %d purge + %d dots, timing.json %s + %s"
                         % (run_dir, len(purge), len(work), meas.get("purge"), meas["n"]))
    if plan:
        bad = [(r["id"], p["id"]) for r, p in zip(work, plan) if r["id"] != p["id"]]
        if bad:
            raise ValueError("%s: dots.csv and %s disagree (first %s)" % (run_dir, plan_file, bad[0]))
    # purge dots repeat the first plan dot's turn, suck-back, dwell and feed (disp.py `recipe`)
    recipe = {k: plan[0][k] for k in ("retract", "dwell", "bf") if plan and k in plan[0]}
    plan_rows = iter(plan)

    def lines_of(r):
        d = recipe if r["id"] == "purge" else next(plan_rows, {})
        return disp_dot(float(r["x"]), float(r["y"]), float(r["z"]), float(r["b_start"]), float(r["theta"]),
                        float(d.get("retract", DISP_RETRACT)), int(d.get("dwell", DISP_DWELL_MS)),
                        float(d.get("bf", bf)), max(bf, 43200.0), bacc, d.get("brk"), bool(d.get("cross")))

    first = rows[0]
    pre = ["M204 S%d" % DISP_ACC, "M201 B%d" % max(10000, int(bacc or 0)),
           "G92 X%.3f Y%.3f Z%.3f B0" % (float(first["x"]) - DISP_OFF[0], float(first["y"]) - DISP_OFF[1],
                                       float(first["z"]) - DISP_DZ)]
    lines = []
    tags = []
    for r in purge:
        pre += lines_of(r)
    if purge:
        pre.append("M400")
        cross = ["G1 Z%.3f F3000" % DISP_ZCROSS,
                 "G1 X%.3f Y%.3f F12000" % (float(work[0]["x"]) - DISP_OFF[0], float(work[0]["y"]) - DISP_OFF[1])]
        lines += cross
        tags += [int(work[0]["i"])] * len(cross)
    pre.append("; @t0")
    for r in work:
        dot = lines_of(r)
        lines += dot
        tags += [int(r["i"])] * len(dot)
    lines.append("M400")
    tags.append(tags[-1])
    return pre, lines, tags


# --------------------------------------------------------------------------- what-if profile

def profile(pitches, net_steps_per_dot, suck_back_deg, screw_rpm, screw_accel, dwell_ms, xy_accel=2000.0,
            hop_mm=1.0, z_feed=3000.0, xy_feed=12000.0, retract_rpm=None, rt_ms=1.5, limits=None):
    """Seconds per dot and DPH of the bench recipe (disp_dot, validated by `bench`) with engine-style knobs.

    pitches: XY travel before each dot (mm, one per dot). Per dot: XY at xy_feed, Z down hop_mm at z_feed,
    M204 S<screw_accel>, B forward (net + suck-back) at screw_rpm, G4 P<dwell_ms>, B suck-back at
    retract_rpm (default max(screw_rpm, 120) as disp.py), M204 S<xy_accel>, Z up hop_mm at z_feed.
    Session: M204 S<xy_accel>, M201 B<max(10000, screw_accel)>. Timed from the first XY to the final M400.
    """
    net = net_steps_per_dot / (limits or {}).get("stepsPerUnit", {}).get("B", FW_STEPS["B"])
    bf = screw_rpm * 360.0
    bf_retract = retract_rpm * 360.0 if retract_rpm else max(bf, 43200.0)
    bacc = screw_accel if abs(screw_accel - xy_accel) > 1e-9 else None
    z = 60.0
    lines = ["M204 S%g" % xy_accel, "M201 B%d" % max(10000, int(screw_accel)),
             "G92 X%.3f Y%.3f Z%.3f B0" % (-DISP_OFF[0], -DISP_OFF[1], z - hop_mm), "; @t0"]
    x = b = 0.0
    for p in pitches:
        x += p
        lines += disp_dot(x, 0.0, z, b, net + suck_back_deg, suck_back_deg, dwell_ms, bf, bf_retract, bacc,
                          xy_feed=xy_feed, z_feed=z_feed, acc=xy_accel, dz=hop_mm)
        b += net
    lines.append("M400")
    res = simulate(lines, limits, rt_ms=rt_ms)
    n = len(pitches)
    return {"dots": n, "seconds": res["seconds"], "s_per_dot": res["seconds"] / n, "dph": 3600 * n / res["seconds"],
            "net_deg": net, "forward_deg": net + suck_back_deg, "categories": res["categories"]}


def parse_pitches(text):
    """'1.7,2.5,160*1.7' -> list of mm (N*P repeats P N times)."""
    out = []
    for tok in text.split(","):
        n, _, v = tok.strip().rpartition("*")
        out += [float(v)] * (int(n) if n else 1)
    return out


def cmd_profile(a):
    limits = json.load(open(a.limits)) if a.limits else None
    res = profile(parse_pitches(a.pitch), a.net_steps, a.suck_back, a.rpm, a.screw_accel, a.dwell_ms, a.xy_accel,
                  a.hop, a.z_feed, a.xy_feed, a.retract_rpm, a.rt_ms, limits)
    if a.json:
        print(json.dumps(res, indent=2))
    else:
        print("%d dots  %.3f s  %.4f s/dot  %.0f DPH  (B %.2f deg forward, %.2f net)"
              % (res["dots"], res["seconds"], res["s_per_dot"], res["dph"], res["forward_deg"], res["net_deg"]))
        for k, v in res["categories"].items():
            print("  %-10s %8.3f s" % (k, v))


def cmd_bench(a):
    out = []
    for run in a.runs:
        pre, lines, tags = bench_lines(run)
        meas = json.load(open(os.path.join(run, "timing.json")))
        res = simulate(pre + lines, rt_ms=a.rt_ms, tag_fn=lambda i, l, n=len(pre): tags[i - n] if i >= n else None)
        n = meas["n"]
        out.append({"run": os.path.basename(run.rstrip("/")), "dots": n, "measured_s": round(meas["seconds"], 3),
                    "predicted_s": round(res["seconds"], 3),
                    "error_pct": round(100 * (res["seconds"] - meas["seconds"]) / meas["seconds"], 2),
                    "measured_dph": round(meas["dph"]), "predicted_dph": round(3600 * n / res["seconds"]),
                    "categories_s": {k: round(v, 3) for k, v in res["categories"].items()}})
    print(json.dumps({"rt_ms": a.rt_ms, "runs": out}, indent=2))


def cmd_gcode(a):
    lines = open(a.file).read().splitlines()
    limits = json.load(open(a.limits)) if a.limits else None
    res = simulate(lines, limits, rt_ms=a.rt_ms)
    if a.json:
        print(json.dumps(res, indent=2))
    else:
        print("%.3f s" % res["seconds"])
        for k, v in res["categories"].items():
            print("  %-10s %8.3f s" % (k, v))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("gcode")
    p.add_argument("file")
    p.add_argument("--limits")
    p.add_argument("--rt-ms", type=float, default=1.5)
    p.add_argument("--json", action="store_true")
    p = sub.add_parser("bench")
    p.add_argument("runs", nargs="+")
    p.add_argument("--rt-ms", type=float, default=1.5)
    p = sub.add_parser("profile")
    p.add_argument("--pitch", required=True, help="XY travel before each dot, mm: '1.7,2.5,160*1.7'")
    p.add_argument("--net-steps", type=float, required=True, help="net B steps per dot (M92 B4.44: 800 = 180.18 deg)")
    p.add_argument("--suck-back", type=float, required=True, help="suck-back, deg")
    p.add_argument("--rpm", type=float, required=True, help="screw feed, rpm")
    p.add_argument("--screw-accel", type=float, required=True, help="screw acceleration, deg/s^2")
    p.add_argument("--dwell-ms", type=int, required=True)
    p.add_argument("--xy-accel", type=float, default=2000.0)
    p.add_argument("--hop", type=float, default=1.0, help="Z hop, mm")
    p.add_argument("--z-feed", type=float, default=3000.0, help="mm/min")
    p.add_argument("--xy-feed", type=float, default=12000.0, help="mm/min")
    p.add_argument("--retract-rpm", type=float, help="suck-back feed (default max(rpm, 120))")
    p.add_argument("--limits")
    p.add_argument("--rt-ms", type=float, default=1.5)
    p.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    {"gcode": cmd_gcode, "bench": cmd_bench, "profile": cmd_profile}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
