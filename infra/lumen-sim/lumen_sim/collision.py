"""The machine collision model (INF-12): Backend's collision-model.json, schema 1 (docs/instancelabs/
collision-model-design.md, sections 2 and 3). The bed plane, the objects on the bed and the tools' shapes.

lumen-sim is the physical truth: it uses no margin and no error term (the engine's proof adds those), so a clear proof
never meets a collision here and an engine bug shows up in sim_errors.
"""
import json
import math
import re
import time
from collections import namedtuple

CIRCLE_SIDES = 64  # a circle is the 64-gon around it: at most 0.12 % of its radius outside it
# contactDepthMm when the file has none: a tip (a layer at the tip end) up to this far into a measured top is resting on
# it, as on the surface it probes or dispenses on: contact, not a collision. One coarse probe step (probe.py, 0.25 mm):
# a touch stops within one step past the top. A guess until Process weighs the tips' give.
TIP_CONTACT_MM = 0.25
SEAL_INSIDE_MM = 2.0  # N1 seals on a touchable top only this far inside its outline (Process 11-04 17:3x)
STATES = ("measured", "added", "moved", "removed")
KINDS = ("fixed", "movable")
ID = re.compile(r"[A-Za-z0-9._-]{1,64}")  # an object's or a tool's id (Backend's rule, as the engine's BedModel.ID)
MOVES = ("n1", "needle", "fixed")  # which side of the see-saw a tool rides, or none

Object = namedtuple("Object", "id pts box top touchable")  # top: topZ, None when not measured; touchable: measured too
Tool = namedtuple("Tool", "id moves dx dy layers")    # dx, dy: tipEnd, the tip's end from the head
Layer = namedtuple("Layer", "name pts above bottom tip")  # pts from the head; above: aboveTipMm, bottom: bottomZ


def _num(v, what):
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
        raise ValueError("%s must be a finite number, not %r" % (what, v))
    return float(v)


def _get(d, key, what, kind=dict):
    v = d.get(key)
    if not isinstance(v, kind):
        raise ValueError("%s.%s must be a JSON %s, not %r" % (what, key, "object" if kind is dict else "list", v))
    return v


TOO_DEEP = "%s: nested too deep to read (a bed model is a few levels deep)"  # the engine's words


def _scan(v, path, latest):
    """The whole file before any field is read, as the engine's BedModel.scan: null nowhere (a key is given a value or
    left out), no NaN or Infinity (strict JSON has none), and every atMs a time, ms since 1970, no later than latest
    (now + 1 min: not microseconds)."""
    if v is None:
        raise ValueError("%s is null: give the key a value or leave it out" % path)
    if isinstance(v, float) and not math.isfinite(v):
        raise ValueError("%s is %r: strict JSON has no NaN or Infinity" % (path, v))
    for k, x in v.items() if isinstance(v, dict) else enumerate(v) if isinstance(v, list) else ():
        p = "%s.%s" % (path, k) if isinstance(v, dict) else "%s[%d]" % (path, k)
        _scan(x, p, latest)
        if k == "atMs" and isinstance(v, dict):
            if isinstance(x, bool) or not isinstance(x, (int, float)):
                raise ValueError("%s must be a time (ms since 1970, a number), not %r" % (p, x))
            if x > latest:
                raise ValueError("%s is later than now + 1 min: a time is ms since 1970, not %r" % (p, x))


def parse(text):
    """The model in a file's text (--collision-model), read as strictly as the engine's BedModel.parse."""
    try:
        raw = json.loads(text)
    except RecursionError:
        raise ValueError(TOO_DEEP % "collision_model") from None
    except json.JSONDecodeError as e:
        raise ValueError("collision_model: not JSON: %s" % e) from None
    return Model(raw)


def _id(d, taken, what):
    """d's id: 1-64 of A-Z a-z 0-9 . _ - and none of taken (the ids so far), which it joins."""
    v = d.get("id")
    if not isinstance(v, str) or not ID.fullmatch(v):
        raise ValueError("%s.id must be 1-64 of A-Z a-z 0-9 . _ -, not %r" % (what, v))
    if v in taken:
        raise ValueError("%s.id %r is taken" % (what, v))
    taken.add(v)


def _cross(o, a, b):
    return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])


def _inside(pts, poly):
    """How far every point of pts lies inside the convex counter-clockwise polygon poly (mm; below 0: one is out)."""
    return min(_cross(p, q, r) / math.dist(p, q) for p, q in zip(poly[-1:] + poly[:-1], poly) for r in pts)


def _hull(points):
    """The convex hull, counter-clockwise from the lowest (x, y), without collinear points (monotone chain)."""
    pts = sorted(set(points))
    if len(pts) < 3:
        return pts

    def chain(seq):
        out = []
        for p in seq:
            while len(out) >= 2 and _cross(out[-2], out[-1], p) <= 0:
                out.pop()
            out.append(p)
        return out[:-1]
    return chain(pts) + chain(pts[::-1])


def _outline(raw, what):
    """A box [x0, y0, x1, y1], circle [cx, cy, r] or convex counter-clockwise polygon [[x, y], ...], as the
    counter-clockwise points of a convex polygon (machine mm)."""
    if not isinstance(raw, dict) or len(raw) != 1 or not set(raw) <= {"box", "circle", "polygon"}:
        raise ValueError("%s must be one of {box: [x0, y0, x1, y1]}, {circle: [cx, cy, r]} or {polygon: [[x, y], "
                         "...]}, not %r" % (what, raw))
    (kind, v), = raw.items()
    size = {"box": 4, "circle": 3}.get(kind)
    if size and not (isinstance(v, list) and len(v) == size):
        raise ValueError("%s: a %s is a list of %d numbers, not %r" % (what, kind, size, v))
    if kind == "box":
        x0, y0, x1, y1 = (_num(n, what) for n in v)
        if not (x0 < x1 and y0 < y1):
            raise ValueError("%s: a box needs x0 < x1 and y0 < y1, not %r" % (what, v))
        return [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]
    if kind == "circle":
        cx, cy, r = (_num(n, what) for n in v)
        if r <= 0:
            raise ValueError("%s: a circle needs r > 0, not %r" % (what, v))
        r /= math.cos(math.pi / CIRCLE_SIDES)
        return [(cx + r * math.cos(a), cy + r * math.sin(a))
                for a in (2 * math.pi * k / CIRCLE_SIDES for k in range(CIRCLE_SIDES))]
    if not (isinstance(v, list) and len(v) >= 3 and all(isinstance(p, list) and len(p) == 2 for p in v)):
        raise ValueError("%s: a polygon is at least 3 points [x, y], not %r" % (what, v))
    pts = [(_num(x, what), _num(y, what)) for x, y in v]
    # the engine's rule, op for op (BedModel.outline): no turn both ways and one whole turn in all (a star winding
    # twice turns 4 pi; a point on a straight edge, or repeated there, turns by 0), then a positive area
    left = right = False
    turn = area = 0.0
    for (x0, y0), (x1, y1), (x2, y2) in zip(pts, pts[1:] + pts[:1], pts[2:] + pts[:2]):
        area += x0 * y1 - x1 * y0
        ax, ay, bx, by = x1 - x0, y1 - y0, x2 - x1, y2 - y1
        cr = ax * by - ay * bx
        left, right = left or cr > 1e-9, right or cr < -1e-9
        turn += math.atan2(cr, ax * bx + ay * by)
    if left and right or abs(abs(turn) - 2 * math.pi) > 1e-6:
        raise ValueError("%s: the polygon is not convex (the writer splits a concave outline into convex ones), not "
                         "%r" % (what, v))
    if not area > 0:
        raise ValueError("%s: the polygon's points must run counter-clockwise, not %r" % (what, v))
    return _hull(pts)  # the same outline without its points on a side (_inside divides by each side's length)


def _plane(points, what):
    """(a, b, c) of raw = a x + b y + c, the least-squares plane through [x, y, rawN1] points (exact through 3)."""
    if not (isinstance(points, list) and all(isinstance(p, list) and len(p) == 3 for p in points)):
        raise ValueError("%s must be a list of [x, y, rawN1], not %r" % (what, points))
    pts = [[_num(v, what) for v in p] for p in points]
    n = len(pts)
    if n >= 3:
        mx, my, mz = (sum(p[i] for p in pts) / n for i in range(3))
        sxx, syy = sum((p[0] - mx) ** 2 for p in pts), sum((p[1] - my) ** 2 for p in pts)
        sxy = sum((p[0] - mx) * (p[1] - my) for p in pts)
        sxz, syz = sum((p[0] - mx) * (p[2] - mz) for p in pts), sum((p[1] - my) * (p[2] - mz) for p in pts)
        det = sxx * syy - sxy * sxy
        if det > 1e-9 * sxx * syy:
            a, b = (sxz * syy - syz * sxy) / det, (syz * sxx - sxz * sxy) / det
            return a, b, mz - a * mx - b * my
    raise ValueError("%s: lumen-sim places every height on the bed plane, so it needs at least 3 points not on one "
                     "line, not %r" % (what, points))


def _box(pts):
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys)


def _clip(poly, by):
    """poly inside the convex counter-clockwise polygon by (Sutherland-Hodgman): their overlap, maybe empty."""
    for a, b in zip(by[-1:] + by[:-1], by):
        pts, poly = poly, []
        for p, q in zip(pts[-1:] + pts[:-1], pts):
            dp, dq = _cross(a, b, p), _cross(a, b, q)
            if (dp >= 0) != (dq >= 0):
                t = dp / (dp - dq)
                poly.append((p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1])))
            if dq >= 0:
                poly.append(q)
    return poly


def _area(poly):
    return sum(_cross((0.0, 0.0), p, q) for p, q in zip(poly[-1:] + poly[:-1], poly)) / 2


class Model:
    def __init__(self, raw):
        """From the file's JSON object; ValueError on anything lumen-sim cannot use (it then changes nothing)."""
        w = "collision_model"
        if not isinstance(raw, dict) or raw.get("schema") != 1 or isinstance(raw.get("schema"), bool):
            raise ValueError("%s must be a schema 1 model, a JSON object with \"schema\": 1, not %s" % (
                w, "schema %r" % (raw.get("schema"),) if isinstance(raw, dict) else type(raw).__name__))
        try:  # the host's clock, as the engine's: the stamps are wall-clock ms (never the sim's clock)
            _scan(raw, w, time.time() * 1000 + 60000)
        except RecursionError:
            raise ValueError(TOO_DEEP % w) from None
        self.version = raw.get("version")
        if isinstance(self.version, bool) or not isinstance(self.version, int):
            raise ValueError("%s.version must be an integer, not %r" % (w, self.version))
        depth = raw.get("contactDepthMm")
        self.contact = TIP_CONTACT_MM if depth is None else _num(depth, w + ".contactDepthMm")
        if not 0 <= self.contact <= 1.0:  # Backend's cap (23:1x)
            raise ValueError("%s.contactDepthMm must be 0 to 1.0, not %r" % (w, depth))
        bed = _get(raw, "bed", w)
        self.a, self.b, self.c = _plane(_get(bed, "plane", w + ".bed").get("points"), w + ".bed.plane.points")
        bed_pts = _outline(bed.get("outline"), w + ".bed.outline")
        ss = _get(raw, "seesaw", w)
        n1, needle = _get(ss, "n1", w + ".seesaw"), _get(ss, "needle", w + ".seesaw")
        # the engine's proof takes no K or knee as the worst case; the truth cannot place a tip without them (design §2)
        for side, key in (("n1", "kneeRaw"), ("needle", "kneeRaw"), ("K", "raw")):
            if key not in _get(ss, side, w + ".seesaw"):
                raise ValueError("%s.seesaw.%s.%s is required: lumen-sim cannot place a tip without it" % (w, side, key))
        self.k1, self.k2 = _num(n1["kneeRaw"], w + ".seesaw.n1.kneeRaw"), \
            _num(needle["kneeRaw"], w + ".seesaw.needle.kneeRaw")
        self.g1, self.g2 = _num(n1.get("mmPerRaw"), w + ".seesaw.n1.mmPerRaw"), \
            _num(needle.get("mmPerRaw"), w + ".seesaw.needle.mmPerRaw")
        if self.g1 <= 0 or self.g2 <= 0:
            raise ValueError("%s.seesaw: mmPerRaw must be > 0, not %r and %r" % (w, self.g1, self.g2))
        self.K = _num(ss["K"]["raw"], w + ".seesaw.K.raw")
        stops = _get(ss, "endStopsRaw", w + ".seesaw", list)
        self.stops = tuple(_num(v, w + ".seesaw.endStopsRaw") for v in stops)
        if len(self.stops) != 2 or not self.stops[0] < self.stops[1]:
            raise ValueError("%s.seesaw.endStopsRaw must be [low, high] with low < high, not %r" % (w, stops))
        self.rest = _num(ss["restRaw"], w + ".seesaw.restRaw") if "restRaw" in ss else None  # absent: no rest model
        self.objects, ids = [], {"bed"}  # "bed" is the bed's
        for i, o in enumerate(_get(raw, "objects", w, list)):
            what = "%s.objects[%d]" % (w, i)
            if not isinstance(o, dict) or o.get("kind") not in KINDS or o.get("state") not in STATES:
                raise ValueError("%s needs a kind (%s) and a state (%s), not %r" % (
                    what, " or ".join(KINDS), ", ".join(STATES), o))
            _id(o, ids, what)
            pts = _outline(o.get("outline"), what + ".outline")
            top = _num(o["topZ"], what + ".topZ") if "topZ" in o else None  # absent: not measured
            if "name" in o and not (isinstance(o["name"], str) and o["name"]):  # absent: the id (shown nowhere)
                raise ValueError("%s (%s).name is required (text), not %r" % (what, o["id"], o["name"]))
            if o["state"] == "measured" and top is not None:  # the engine's proof needs the record's error
                rec = o["measured"].get("top") if isinstance(o.get("measured"), dict) else None
                if not isinstance(rec, dict):
                    raise ValueError("%s (%s): a measured object with a topZ needs measured.top {how, errMm, ...}"
                                     % (what, o["id"]))
                if not (isinstance(rec.get("how"), str) and rec["how"].strip()):
                    raise ValueError("%s (%s).measured.top.how must say how it was measured, not %r"
                                     % (what, o["id"], rec.get("how")))
                if _num(rec.get("errMm"), what + ".measured.top.errMm") < 0:
                    raise ValueError("%s.measured.top.errMm must be >= 0, not %r" % (what, rec["errMm"]))
            if o["state"] != "measured":
                top = None  # added, moved or removed: it blocks its outline at every height
            touchable = o.get("touchable", False)  # absent: not touchable
            if not isinstance(touchable, bool):
                raise ValueError("%s.touchable must be true or false, not %r" % (what, touchable))
            self.objects.append(Object(o["id"], pts, _box(pts), top, top is not None and touchable))
        self.objects.append(Object("bed", bed_pts, _box(bed_pts), 0.0, False))  # the bed plane: Z 0 inside it
        self.tools, ids = [], set()
        for i, t in enumerate(_get(raw, "tools", w, list)):
            what = "%s.tools[%d]" % (w, i)
            if not isinstance(t, dict) or t.get("moves") not in MOVES:
                raise ValueError("%s needs moves (%s), not %r" % (what, ", ".join(MOVES), t))
            _id(t, ids, what)
            if t.get("tipEnd") is None and t["moves"] != "fixed":
                raise ValueError("%s: a tool on the see-saw needs its tipEnd" % what)
            # a fixed part's tip end: the head, unless it has one
            end = {"dx": 0, "dy": 0} if t.get("tipEnd") is None else _get(t, "tipEnd", what)
            dx, dy = (_num(end.get(k), what + ".tipEnd." + k) for k in ("dx", "dy"))
            layers = []
            for j, la in enumerate(_get(t, "layers", what, list)):
                lw = "%s.layers[%d]" % (what, j)
                if not isinstance(la, dict) or not isinstance(la.get("name"), str) \
                        or la.get("at") not in ("head", "tip-end") or ("aboveTipMm" in la) == ("bottomZ" in la):
                    raise ValueError("%s needs a name, at (head or tip-end) and one of aboveTipMm and bottomZ, not %r"
                                     % (lw, la))
                if "aboveTipMm" in la and t["moves"] == "fixed":
                    raise ValueError("%s: a fixed part has no tip to be above: give it a bottomZ" % lw)
                ox, oy = (dx, dy) if la["at"] == "tip-end" else (0.0, 0.0)
                pts = [(x + ox, y + oy) for x, y in _outline(la.get("outline"), lw + ".outline")]
                above = _num(la["aboveTipMm"], lw + ".aboveTipMm") if "aboveTipMm" in la else None
                bottom = _num(la["bottomZ"], lw + ".bottomZ") if "bottomZ" in la else None
                layers.append(Layer(la["name"], pts, above, bottom, la["at"] == "tip-end"))
            if t["moves"] != "fixed" and not any(la.tip and la.above == 0 for la in layers):
                raise ValueError("%s (%s): a tool on the see-saw needs its tip: a tip-end layer with aboveTipMm 0"
                                 % (what, t["id"]))
            self.tools.append(Tool(t["id"], t["moves"], dx, dy, layers))
        n1s, needles = (sum(t.moves == m for t in self.tools) for m in ("n1", "needle"))
        if (n1s, needles) != (1, 1):  # the engine's words; tip() and n1_sealed() read one of each
            raise ValueError("%s.tools: exactly one with moves n1 and one with moves needle (here %d and %d): every leg "
                             "sweeps both tips" % (w, n1s, needles))
        self.colliding = set()  # (object, tool, layer) in collision where the last leg ended

    def rc(self, x, y):
        """N1's contact raw with the bed at (x, y): the least-squares plane through bed.plane.points."""
        return self.a * x + self.b * y + self.c

    def tip(self, tool, x, y, r):
        """The tool's tip X/Y (the head at x, y + its tipEnd) and its height above the bed there with the head at raw
        Z r: N1 h1 = g1 (min(r, k1) - rc), the needle h2 = g2 ((K - rc) - max(r, k2)); None for a fixed part."""
        tx, ty = x + tool.dx, y + tool.dy
        if tool.moves == "n1":
            return tx, ty, self.g1 * (min(r, self.k1) - self.rc(tx, ty))
        if tool.moves == "needle":
            return tx, ty, self.g2 * ((self.K - self.rc(tx, ty)) - max(r, self.k2))
        return tx, ty, None

    def bed(self, x, y):
        """The bed's height at (x, y) in the gantry's frame, N1's mm: tips, layers and tops are compared there."""
        return self.g1 * self.rc(x, y)

    def _bottom(self, tool, layer, pos):
        """The layer's bottom in the gantry's frame with the head at pos (X, Y, raw Z)."""
        tx, ty, h = self.tip(tool, *pos)
        return self.bed(tx, ty) + (layer.bottom if layer.above is None else h + layer.above)

    def _hits(self, a, b):
        """{(object, tool, layer): mm} for each layer whose leg a -> b (head X, Y, raw Z) reaches below an object's
        top: its swept area (the hull of its outline at both ends) overlaps the object, and its lowest bottom on the
        leg (at an end: the see-saw maps are monotonic) is below the object's highest top over that overlap. An
        object not measured blocks its outline at every height (mm None)."""
        hits = {}
        for tool in self.tools:
            for layer in tool.layers:
                swept = _hull([(x + p[0], y + p[1]) for p in (a, b) for x, y in layer.pts])
                box, low = _box(swept), None
                for obj in self.objects:
                    if box[2] <= obj.box[0] or obj.box[2] <= box[0] or box[3] <= obj.box[1] or obj.box[3] <= box[1]:
                        continue
                    over = _clip(swept, obj.pts)
                    if _area(over) <= 1e-9:  # mm^2: touching sides are no overlap
                        continue
                    if obj.top is None:
                        hits[(obj.id, tool.id, layer.name)] = None
                        continue
                    if low is None:
                        low = min(self._bottom(tool, layer, p) for p in (a, b))
                    depth = obj.top + max(self.bed(x, y) for x, y in over) - low
                    rests = layer.tip and obj.touchable and all(  # inside the top at both ends: the engine's rule
                        _inside([(p[0] + x, p[1] + y) for x, y in layer.pts], obj.pts) >= -1e-9 for p in (a, b))
                    if depth > (self.contact if rests else 0.0) + 1e-6:
                        hits[(obj.id, tool.id, layer.name)] = depth
        return hits

    def sweep(self, a, b):
        """The head's leg a -> b (X, Y, raw Z): each new collision as sim_errors' fields (error, object, tool, layer,
        overlapMm). A collision is new unless the layer was already in that object where the last leg ended."""
        hits = self._hits(a, b)
        new = [k for k in hits if k not in self.colliding]
        self.colliding = set(self._hits(b, b)) if hits else set()
        out = []
        for obj, tool, layer in new:
            mm = hits[(obj, tool, layer)]
            out.append({"error": "collision: %s (%s) " % (tool, layer) + (
                "over %s, not measured" % obj if mm is None else "%.3f mm below the top of %s" % (mm, obj)),
                "object": obj, "tool": tool, "layer": layer, "overlapMm": None if mm is None else round(mm, 6) + 0.0})
        return out

    def jumped(self, p):
        """The head jumped to p (X, Y, raw Z) with no move (a physical fault): a collision stays reported only while
        its layer is still in that object there, so the next leg reports one the jump left or landed in."""
        self.colliding &= set(self._hits(p, p))

    def n1_sealed(self, x, y, r):
        """N1's tip at or below the top of a measured object marked touchable (a board, a blank, the PA5 switch), at
        least 2 mm inside its outline, with the head at x, y and raw Z r. Never on the bare bed (out of N1's reach)."""
        for tool in self.tools:
            if tool.moves == "n1":
                tx, ty, h = self.tip(tool, x, y, r)
                return any(o.touchable and h <= o.top + 1e-9 and _inside([(tx, ty)], o.pts) >= SEAL_INSIDE_MM - 1e-9
                           for o in self.objects)
        return False

    def describe(self, x, y, r):
        """For the state, with the head at x, y and raw Z r: each see-saw tip's height above the bed under it."""
        return {"version": self.version,
                "tips": {t.id: round(self.tip(t, x, y, r)[2], 6) + 0.0 for t in self.tools if t.moves != "fixed"}}
