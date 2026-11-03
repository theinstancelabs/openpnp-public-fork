#!/usr/bin/env python3
"""Paste openings of a KiCad board as a padprofile fixture (offline, standard library only).

Usage (from any directory; run with -I so nothing next to the board file is imported):

    python3 -I extract_pads.py BOARD.kicad_pcb NAME "SOURCE" "LICENCE" > NAME.pads.json

Every top and bottom footprint's paste openings (pads of type smd/connect on the side's Paste
layer, and filled paste polygons, rectangles and circles inside the footprint), with the rules of
BOARD_IMPORT_DESIGN.md §2.8 (margins pad > footprint > board, aperture shapes and roundness, pad
names) in the board frame of §2.6 (origin at the bottom-left of the Edge.Cuts box, +Y up).
Fiducials are left out (no paste). Nothing is written next to the board file.
"""
import json
import math
import sys


def tokens(s):
    i, n = 0, len(s)
    while i < n:
        c = s[i]
        if c in " \t\r\n":
            i += 1
        elif c in "()":
            yield c
            i += 1
        elif c == '"':
            j, buf = i + 1, []
            while j < n and s[j] != '"':
                if s[j] == "\\" and j + 1 < n:
                    buf.append(s[j + 1])
                    j += 2
                    continue
                buf.append(s[j])
                j += 1
            yield ("S", "".join(buf))
            i = j + 1
        else:
            j = i
            while j < n and s[j] not in " \t\r\n()":
                j += 1
            yield s[i:j]
            i = j


def parse(s):
    stack = [[]]
    for t in tokens(s):
        if t == "(":
            stack.append([])
        elif t == ")":
            x = stack.pop()
            stack[-1].append(x)
        else:
            stack[-1].append(t[1] if isinstance(t, tuple) else t)
    return stack[0][0]


def kids(node, name):
    return [k for k in node[1:] if isinstance(k, list) and k and k[0] == name]


def kid(node, name):
    k = kids(node, name)
    return k[0] if k else None


def val(node, name, default=None):
    k = kid(node, name)
    return float(k[1]) if k is not None and len(k) > 1 else default


def atoms(node):
    return [a for a in node[1:] if not isinstance(a, list)]


def rot(u, v, deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return u * c - v * s, u * s + v * c


def norm180(a):
    a = math.fmod(a, 360.0)
    if a <= -180:
        a += 360
    if a > 180:
        a -= 360
    return 0.0 if abs(a) < 1e-9 else a


# ---- Edge.Cuts box (§2.6 step 3: arcs add the axis points inside their sweep) ----------------
def arc_points(s, m, e):
    ax, ay = s
    bx, by = m
    cx, cy = e
    d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    if abs(d) < 1e-12:
        return [s, m, e]
    ux = ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
    uy = ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
    r = math.hypot(ax - ux, ay - uy)
    a0 = math.atan2(ay - uy, ax - ux)
    am = math.atan2(by - uy, bx - ux)
    a1 = math.atan2(cy - uy, cx - ux)

    def ccw(a, b):
        return (b - a) % (2 * math.pi)

    sweep, mid = ccw(a0, a1), ccw(a0, am)
    pts = [s, e]
    for k in range(4):
        t = k * math.pi / 2
        inside = ccw(a0, t) <= sweep if mid <= sweep else ccw(a1, t) <= (2 * math.pi - sweep)
        if inside:
            pts.append((ux + r * math.cos(t), uy + r * math.sin(t)))
    return pts


def xy(node, name):
    k = kid(node, name)
    return (float(k[1]), float(k[2]))


def edge_points(g, tf):
    head = g[0]
    if head in ("gr_line", "fp_line"):
        return [tf(xy(g, "start")), tf(xy(g, "end"))]
    if head in ("gr_arc", "fp_arc"):
        if kid(g, "mid") is None:
            raise SystemExit("KiCad 5 arcs on Edge.Cuts are not handled by this script")
        return [tf(p) for p in arc_points(xy(g, "start"), xy(g, "mid"), xy(g, "end"))]
    if head in ("gr_rect", "fp_rect"):
        (x1, y1), (x2, y2) = xy(g, "start"), xy(g, "end")
        return [tf((x1, y1)), tf((x2, y1)), tf((x2, y2)), tf((x1, y2))]
    if head in ("gr_circle", "fp_circle"):
        (cx, cy), (ex, ey) = xy(g, "center"), xy(g, "end")
        r = math.hypot(ex - cx, ey - cy)
        return [tf((cx + r, cy)), tf((cx - r, cy)), tf((cx, cy + r)), tf((cx, cy - r))]
    if head in ("gr_poly", "fp_poly"):
        return [tf((float(p[1]), float(p[2]))) for p in kid(g, "pts")[1:] if p[0] == "xy"]
    return []


def layer_of(g):
    k = kid(g, "layer")
    return k[1] if k is not None else None


# ---- apertures (§2.8) ---------------------------------------------------------------------------
def margin(pad, fp, board, version, names):
    """First present value of pad, footprint, board (KiCad < 8: an explicit 0 means not set)."""
    for node in (pad, fp):
        for nm in names:
            v = val(node, nm)
            if v is not None and (v != 0 or version >= 20240108):
                return v
    return board


def pad_aperture(pad, fp, setup_m, setup_r, version):
    shape = pad[3]
    w, h = xy(pad, "size")
    m = margin(pad, fp, setup_m, version, ("solder_paste_margin",))
    rho = margin(pad, fp, setup_r, version, ("solder_paste_margin_ratio", "solder_paste_ratio"))
    mx, my = m + rho * w, m + rho * h
    cx = cy = 0.0
    if shape == "trapezoid":
        dx, dy = xy(pad, "rect_delta") if kid(pad, "rect_delta") is not None else (0.0, 0.0)
        w, h = w + abs(dy), h + abs(dx)
    elif shape == "custom":
        xs, ys = [-w / 2, w / 2], [-h / 2, h / 2]
        prims = kid(pad, "primitives")
        for g in (prims[1:] if prims is not None else []):
            hw = (val(g, "width", 0.0) or 0.0) / 2
            stroke = kid(g, "stroke")
            if stroke is not None:
                hw = (val(stroke, "width", 0.0) or 0.0) / 2
            for px, py in edge_points(g, lambda p: p):
                xs += [px - hw, px + hw]
                ys += [py - hw, py + hw]
        cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
        w, h = max(xs) - min(xs), max(ys) - min(ys)
    w2, h2 = w + 2 * mx, h + 2 * my
    if w2 <= 0.01 or h2 <= 0.01:
        return None
    if shape in ("circle", "oval"):
        rnd = 100.0
    elif shape == "roundrect":
        rr = val(pad, "roundrect_rratio", 0.25)
        r2 = max(0.0, rr * min(w, h) + min(mx, my))
        rnd = max(0.0, min(100.0, 200 * r2 / min(w2, h2)))
    elif shape == "rect":
        r2 = max(0.0, min(mx, my))
        rnd = 200 * r2 / min(w2, h2) if m > 0 else 0.0
    else:
        rnd = 0.0
    return w2, h2, rnd, cx, cy


def poly_rect(pts):
    """A rectangle at any angle exactly; any other polygon: its minimum-area enclosing rectangle."""
    n = len(pts)
    best = None
    for i in range(n):
        (x0, y0), (x1, y1) = pts[i], pts[(i + 1) % n]
        a = math.atan2(y1 - y0, x1 - x0)
        us = [x * math.cos(a) + y * math.sin(a) for x, y in pts]
        vs = [-x * math.sin(a) + y * math.cos(a) for x, y in pts]
        w, h = max(us) - min(us), max(vs) - min(vs)
        if best is None or w * h < best[0] - 1e-12:
            uc, vc = (max(us) + min(us)) / 2, (max(vs) + min(vs)) / 2
            best = (w * h, w, h, uc * math.cos(a) - vc * math.sin(a), uc * math.sin(a) + vc * math.cos(a),
                    math.degrees(a))
    _, w, h, cx, cy, a = best
    a = norm180(a)
    if a > 90:
        a -= 180
    elif a <= -90:
        a += 180
    if abs(a) > 45:
        w, h, a = h, w, a - math.copysign(90, a)
    return w, h, cx, cy, norm180(a)


def graphic_apertures(fp, paste):
    """(centre in KiCad's footprint frame, w, h, rotation in the +Y-up part frame, roundness)."""
    out = []
    for g in kids(fp, "fp_poly") + kids(fp, "fp_rect") + kids(fp, "fp_circle"):
        if layer_of(g) != paste:
            continue
        fill = kid(g, "fill")
        filled = fill[1] in ("solid", "yes") if fill is not None else g[0] == "fp_poly"
        if not filled:
            continue
        if g[0] == "fp_circle":
            (cx, cy), (ex, ey) = xy(g, "center"), xy(g, "end")
            d = 2 * math.hypot(ex - cx, ey - cy)
            out.append((cx, cy, d, d, 0.0, 100.0))
            continue
        w, h, cx, cy, a = poly_rect(edge_points(g, lambda p: (p[0], -p[1])))
        out.append((cx, -cy, w, h, a, 0.0))
    for z in kids(fp, "zone"):
        for fpoly in kids(z, "filled_polygon"):
            if layer_of(fpoly) not in (paste, None):
                continue
            pts = [(float(p[1]), -float(p[2])) for p in kid(fpoly, "pts")[1:] if p[0] == "xy"]
            w, h, cx, cy, a = poly_rect(pts)
            out.append((cx, -cy, w, h, a, 0.0))
    return out


def inside(px, py, pad, fa):
    x, y = xy(pad, "at")
    at = kid(pad, "at")
    a = (float(at[3]) if len(at) > 3 else 0.0) - fa
    w, h = xy(pad, "size")
    # KiCad's footprint frame is Y down; angles are counter-clockwise as shown
    u, v = rot(px - x, -(py - y), -a)
    return abs(u) <= w / 2 + 1e-9 and abs(v) <= h / 2 + 1e-9


def same_signature(a, b):
    if len(a) != len(b):
        return False
    for p, q in zip(a, b):
        if p[0] != q[0] or abs(norm180(p[5] - q[5])) > 0.01 or abs(p[6] - q[6]) > 0.5:
            return False
        if any(abs(p[i] - q[i]) > 0.002 for i in (1, 2, 3, 4)):
            return False
    return True


def main(path, name, source, licence):
    root = parse(open(path, encoding="utf-8").read())
    version = int(val(root, "version", 0))
    setup = kid(root, "setup")
    setup_m = val(setup, "pad_to_paste_clearance", 0.0) if setup is not None else 0.0
    setup_r = val(setup, "pad_to_paste_clearance_ratio", 0.0) if setup is not None else 0.0
    fps = kids(root, "footprint")

    def fp_at(fp):
        at = kid(fp, "at")
        return float(at[1]), float(at[2]), (float(at[3]) if len(at) > 3 else 0.0)

    def fp_tf(fp):
        fx, fy, fa = fp_at(fp)

        def tf(p):
            u, v = rot(p[0], -p[1], fa)
            return fx + u, -fy + v
        return tf

    edge = []
    for g in root[1:]:
        if isinstance(g, list) and g and g[0].startswith("gr_") and layer_of(g) == "Edge.Cuts":
            edge += edge_points(g, lambda p: (p[0], -p[1]))
    for fp in fps:
        for g in fp[1:]:
            if isinstance(g, list) and g and g[0].startswith("fp_") and layer_of(g) == "Edge.Cuts":
                edge += edge_points(g, fp_tf(fp))
    min_x = min(p[0] for p in edge)
    min_y = min(p[1] for p in edge)  # +Y up: the KiCad maxY
    max_x = max(p[0] for p in edge)
    max_y = max(p[1] for p in edge)

    parts, pads, used_refs, signatures = [], [], set(), {}
    for fp in fps:
        lib_name = fp[1]
        base = lib_name.split(":", 1)[-1]
        side = "top" if kid(fp, "layer")[1] == "F.Cu" else "bottom"
        cu, paste = ("F.Cu", "F.Paste") if side == "top" else ("B.Cu", "B.Paste")
        ref = None
        for p in kids(fp, "property"):
            if p[1] == "Reference":
                ref = p[2]
        for t in kids(fp, "fp_text"):
            if t[1] == "reference":
                ref = t[2]
        ref = (ref or "").strip()
        lib = lib_name.split(":", 1)[0] if ":" in lib_name else ""
        if lib.lower() == "fiducial" or ref.upper().startswith("FID") or base.lower().startswith("fiducial"):
            continue
        attrs = set(atoms(kid(fp, "attr"))) if kid(fp, "attr") is not None else set()
        fx, fy, fa = fp_at(fp)
        apertures, cu_pads = [], []
        for pad in kids(fp, "pad"):
            layers = set(atoms(kid(pad, "layers")) if kid(pad, "layers") is not None else [])
            if cu in layers or "*.Cu" in layers:
                cu_pads.append(pad)
            if pad[2] not in ("smd", "connect") or not ({paste, "*.Paste"} & layers):
                continue
            ap = pad_aperture(pad, fp, setup_m, setup_r, version)
            if ap is None:
                continue
            w, h, rnd, ox, oy = ap
            at = kid(pad, "at")
            px, py = float(at[1]), float(at[2])
            pa = float(at[3]) if len(at) > 3 else 0.0
            dx, dy = rot(ox, -oy, pa - fa)
            apertures.append([pad[1], px + dx, py - dy, w, h, norm180(pa - fa), rnd])
        for cx, cy, w, h, a, rnd in graphic_apertures(fp, paste):
            apertures.append([None, cx, cy, w, h, a, rnd])
        if not apertures:
            continue
        # pad names (§2.8): repeated names get ~2, ~3; empty names and graphics <host>~k
        named = [p for p in cu_pads if p[1]] + [p for p in cu_pads if not p[1]]
        seen, used = {}, set(a[0] for a in apertures if a[0])
        host_k = {}
        for a in apertures:
            if a[0]:
                seen[a[0]] = seen.get(a[0], 0) + 1
                if seen[a[0]] > 1:
                    a[0] = "%s~%d" % (a[0], seen[a[0]])
                    used.add(a[0])
                continue
            host = next((p[1] for p in named if p[1] and inside(a[1], a[2], p, fa)), "P")
            k = host_k.get(host, 0)
            while True:
                k += 1
                if "%s~%d" % (host, k) not in used:
                    break
            host_k[host] = k
            a[0] = "%s~%d" % (host, k)
            used.add(a[0])
        # package signature (§2.8), compared within 0.002 mm, 0.01 deg and 0.5 % (rotated footprints
        # store their pad positions with float noise)
        sig = sorted((a[0], a[1], -a[2], a[3], a[4], a[5], a[6]) for a in apertures)
        variants = signatures.setdefault(base, [])
        index = next((i for i, v in enumerate(variants) if same_signature(v, sig)), None)
        if index is None:
            variants.append(sig)
            index = len(variants) - 1
        package = base if index == 0 else "%s~%d" % (base, index + 1)
        pid = ref or "P"
        k = 1
        while pid in used_refs:
            k += 1
            pid = "%s~%d" % (ref or "P", k)
        used_refs.add(pid)
        bx, by = fx - min_x, -fy - min_y
        parts.append({"ref": pid, "packageId": package, "side": side, "x": round(bx, 6), "y": round(by, 6),
                      "rotationDeg": round(norm180(fa), 6), "dnp": "dnp" in attrs})
        for nm, px, py, w, h, rel, rnd in apertures:
            u, v = px, -py
            du, dv = rot(u, v, fa)
            pads.append({"padId": pid + "." + nm, "ref": pid, "packageId": package, "padName": nm, "side": side,
                         "x": round(bx + du, 6), "y": round(by + dv, 6), "rotationDeg": round(norm180(fa + rel), 6),
                         "wMm": round(w, 6), "hMm": round(h, 6), "roundness": round(rnd, 3),
                         "localX": round(u, 6), "localY": round(v, 6)})
    out = {"board": name, "source": source, "licence": licence,
           "generator": "src/test/resources/padprofile/extract_pads.py", "kicadVersion": version,
           "boardMm": [round(max_x - min_x, 4), round(max_y - min_y, 4)],
           "parts": parts, "pads": pads}
    text = json.dumps({k: v for k, v in out.items() if k not in ("parts", "pads")}, ensure_ascii=False)[:-1]
    lines = [text + ',\n "parts": [']
    lines.append(",\n".join("  " + json.dumps(p, ensure_ascii=False) for p in parts))
    lines.append(' ],\n "pads": [')
    lines.append(",\n".join("  " + json.dumps(p, ensure_ascii=False) for p in pads))
    lines.append(" ]\n}\n")
    sys.stdout.write("\n".join(lines))


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit(__doc__)
    main(*sys.argv[1:])
