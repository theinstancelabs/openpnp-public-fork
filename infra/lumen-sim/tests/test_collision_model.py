"""INF-12: the machine collision model (docs/instancelabs/collision-model-design.md, sections 2, 3 and 7).

lumen-sim is the physical truth: a tool layer below an object's top over its outline is a collision, reported once in
sim_errors, with no margins and no error terms (the engine's proof adds those). The model also gives the see-saw its
rest with the Z motor off and its end stops, and N1 seals only on a measured flat object's top.
"""
import copy
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

from lumen_sim.bench import Bench
from tests.simproc import PKG_ROOT, SimProcess

PARK = (100.0, 50.0, 31.5)  # whole steps from the switches: homes land exactly on them
FLAT = [[0, 0, 4.0], [100, 0, 4.0], [0, 100, 4.0]]    # N1 meets the bed at raw 4 everywhere
TILTED = [[0, 0, 4.0], [100, 0, 4.1], [0, 100, 4.2]]  # rc(x, y) = 4 + 0.001 x + 0.002 y
BLOCK = {"id": "block", "name": "a block", "kind": "fixed", "role": "fixture", "outline": {"box": [170, 70, 190, 90]},
         "topZ": 15.4, "marginMm": 1.0, "noPaste": False, "surface": None, "touchable": False,
         "measured": {"top": {"how": "n1-touch", "by": "Infra", "atMs": 0, "errMm": 0.05}}, "state": "measured",
         "invalidatedBy": ["moved", "touched"]}
BLANK = {"id": "blank", "name": "the front blank", "kind": "movable", "role": "blank",
         "outline": {"box": [200, 200, 260, 260]}, "topZ": 1.6, "marginMm": 1.0, "noPaste": False,
         "surface": "blank-front", "touchable": True, "state": "measured", "invalidatedBy": ["moved"]}
MODEL = {
    "schema": 1, "version": 7, "changedAtMs": 0, "changedBy": "Infra", "frame": {"xy": "machine", "z": "above-bed"},
    "bed": {"outline": {"box": [-30, -70, 450, 490]},
            "plane": {"how": "n1-touch", "points": FLAT, "homeAtMs": 0, "by": "Infra", "atMs": 0, "errMm": 0.05},
            "reach": {"headX": [0, 408], "headY": [0, 487], "cameraXEnd": 413}},
    "objects": [BLOCK, BLANK],
    "tools": [
        {"id": "n1", "moves": "n1", "tipEnd": {"dx": -20, "dy": -60, "setup": "N045", "atMs": 0},
         "layers": [{"name": "tip", "at": "tip-end", "outline": {"circle": [0, 0, 0.5]}, "aboveTipMm": 0}]},
        {"id": "needle", "moves": "needle", "tipEnd": {"dx": 20, "dy": -70, "setup": "25G", "atMs": 0},
         "layers": [{"name": "tip", "at": "tip-end", "outline": {"circle": [0, 0, 0.3]}, "aboveTipMm": 0},
                    {"name": "hub", "at": "head", "outline": {"circle": [20, -70, 6]}, "aboveTipMm": 5}]},
        {"id": "camera", "moves": "fixed",
         "layers": [{"name": "camera and light", "at": "head", "outline": {"circle": [0, 0, 20]}, "bottomZ": 40}]}],
    "seesaw": {"endStopsRaw": [-1, 66], "restRaw": None, "n1": {"kneeRaw": 31.5, "mmPerRaw": 1},
               "needle": {"kneeRaw": 31.5, "mmPerRaw": 1}, "K": {"raw": 64.0, "homeAtMs": 0, "surface": "blank-front"},
               "homeRepeatMm": 0.1,
               "measured": {"how": "n1-touch", "by": "Infra", "atMs": 0, "errMm": 0.05, "homeAtMs": 0}}}
# On the flat bed the needle's hub bottom is 4 + (64 - 4 - r) + 5 = 69 - r for r >= 31.5: 19 at r 50, 0.4 mm below
# the block's top (4 + 15.4); its edge, 6 mm from the tip, overlaps the block's x 170..190 when the head is at x 145.
DELETE = object()


def model(*edits):
    """MODEL with edits: (path, value) pairs; DELETE removes the key."""
    m = copy.deepcopy(MODEL)
    for path, value in edits:
        d = m
        for k in path[:-1]:
            d = d[k]
        if value is DELETE:
            del d[path[-1]]
        else:
            d[path[-1]] = value
    return m


def put(b, m):
    reply = b.sim.control({"cmd": "set", "collision_model": m}, b.t)
    assert reply["ok"], reply
    return b


def loaded(m=None, **kw):
    """Homed first, then the model: the home's own legs (N1 to raw 0 at X0 Y0) are not part of these tests."""
    b = Bench(park=PARK, **kw)
    b.send("G28")
    return put(b, MODEL if m is None else m)


def to(b, x=None, y=None, z=None):
    b.send("G1" + "".join(" %s%s" % (a, v) for a, v in zip("XYZ", (x, y, z)) if v is not None) + " F3000")
    b.send("M400")


def state(b):
    return b.sim.state(b.t)


def cm(b):
    return state(b)["collision_model"]


def hits(b):
    return [(e["object"], e["tool"], e["layer"], e["overlapMm"]) for e in state(b)["sim_errors"]]


def z(b):
    return state(b)["head"]["physical"]["Z"]


def sealed(b, x, y, zs):
    """N1's seal with the head at x, y and each raw Z in zs (pump and valve on); up to raw 31.5 first."""
    to(b, z=31.5)
    to(b, x, y)
    b.send("M106")
    b.send("M106 P1 S180")
    out = []
    for r in zs:
        to(b, z=r)
        out.append(state(b)["n1_sealed"])
    return out


class Load(unittest.TestCase):
    def test_the_state_shows_the_models_version(self):
        b = loaded()
        self.assertEqual(cm(b)["version"], 7)
        b.sim.power_cycle(b.t)  # the machine's: it outlives a power cycle and clear
        self.assertTrue(b.sim.control({"cmd": "clear"}, b.t)["ok"])
        self.assertEqual(cm(b)["version"], 7)
        put(b, None)
        self.assertIsNone(cm(b))

    BAD = [
        [(("schema",), 2)], [(("schema",), True)], [(("schema",), DELETE)], [(("version",), "7")],
        [(("version",), DELETE)],
        [(("bed", "outline"), {"box": [10, 0, 10, 5]})],
        [(("objects", 0, "outline"), {"box": [0, 10, 5, 0]})],
        [(("objects", 0, "outline"), {"circle": [0, 0, 0]})],
        [(("objects", 0, "outline"), {"circle": [0, 0, -1]})],
        [(("objects", 0, "outline"), {"polygon": [[0, 0], [0, 10], [10, 0]]})],  # clockwise
        [(("objects", 0, "outline"), {"polygon": [[0, 0], [10, 0], [5, 2], [10, 10], [0, 10]]})],  # concave
        [(("objects", 0, "outline"), {"polygon": [[0, 0], [10, 0]]})],
        [(("objects", 0, "outline"), {"box": [0, 0, 1, 1], "circle": [0, 0, 1]})],
        [(("objects", 0, "outline"), {"square": [0, 0, 1]})],
        [(("objects", 0, "outline"), {"box": [0, 0, "1", 1]})],
        [(("objects", 0, "topZ"), float("nan"))], [(("objects", 0, "topZ"), "15.4")],
        [(("objects", 0, "state"), "lost")], [(("objects", 0, "id"), 3)],
        [(("bed", "plane", "points"), FLAT[:2])],
        [(("bed", "plane", "points"), [[0, 0, 4], [50, 50, 4], [100, 100, 4]])],  # on one line
        [(("tools", 0, "moves"), "n2")],
        [(("tools", 1, "layers", 1, "bottomZ"), 3.0)],  # both aboveTipMm and bottomZ
        [(("tools", 1, "layers", 1, "aboveTipMm"), DELETE)],  # neither
        [(("tools", 2, "layers", 0), {"name": "camera", "at": "head", "outline": {"circle": [0, 0, 20]},
                                      "aboveTipMm": 2})],  # a fixed part has no tip to be above
        [(("tools", 1, "tipEnd"), DELETE)],
        [(("tools", 1, "tipEnd"), {})], [(("tools", 1, "tipEnd"), [])], [(("tools", 1, "tipEnd"), 0)],
        [(("tools", 1, "tipEnd"), "")], [(("tools", 2, "tipEnd"), {})],  # an empty tipEnd is not (0, 0)
        [(("contactDepthMm",), -0.1)], [(("contactDepthMm",), "0.25")], [(("contactDepthMm",), True)],
        [(("contactDepthMm",), 1.01)], [(("contactDepthMm",), 5.0)],
        [(("objects", 1, "touchable"), "true")], [(("objects", 1, "touchable"), 1)], [(("objects", 1, "touchable"), 0)],
        # Backend's rules (23:1x), as the engine's loader: ids of 1-64 of A-Z a-z 0-9 . _ -, each once among the
        # objects and among the tools, "bed" the bed's; kind fixed or movable
        [(("objects", 0, "id"), "")], [(("objects", 0, "id"), "a block")], [(("objects", 0, "id"), "x" * 65)],
        [(("objects", 0, "id"), "block\n")], [(("tools", 0, "id"), "n1/tip")], [(("tools", 0, "id"), None)],
        [(("objects", 1, "id"), "block")], [(("tools", 1, "id"), "n1")], [(("objects", 0, "id"), "bed")],
        [(("objects", 0, "kind"), "fixture")], [(("objects", 0, "kind"), DELETE)], [(("objects", 0, "kind"), None)],
        [(("tools", 1, "layers", 0, "at"), "tip")],
        [(("seesaw", "n1", "mmPerRaw"), 0)],
        [(("seesaw", "endStopsRaw"), [66, -1])],
        [(("seesaw", "K"), DELETE)],
        [(("seesaw", "restRaw"), "60")],
    ]

    def test_a_bad_model_changes_nothing(self):
        b = loaded()
        before = cm(b)
        for edits in [[(("objects",), "x")]] + self.BAD:
            with self.subTest(edits):
                reply = b.sim.control({"cmd": "set", "collision_model": model(*edits)}, b.t)
                self.assertFalse(reply["ok"], reply)
                self.assertRegex(reply["error"], r"^ValueError: collision_model", "a clean refusal")
                self.assertEqual(cm(b), before)
        reply = b.sim.control({"cmd": "set", "collision_model": [MODEL]}, b.t)
        self.assertRegex(reply["error"], r"^ValueError: collision_model")
        reply = b.sim.control({"cmd": "set", "collision_model": None, "seal_drop": -1}, b.t)  # one bad value: none
        self.assertFalse(reply["ok"])
        self.assertEqual(cm(b), before)

    def test_keys_it_does_not_use_load(self):
        """The engine's zMarginMm (lumen-sim uses no margins), notes; a fixed part's tipEnd null is no tipEnd."""
        b = loaded(model((("zMarginMm",), 2.0), (("_comment",), ["a fixture"]), (("objects", 0, "note"), "a guess"),
                         (("tools", 2, "tipEnd"), None)))
        self.assertEqual(cm(b)["version"], 7)

    def test_the_ids_and_depths_backend_allows_load(self):
        """contactDepthMm 0 and 1.0; a 64-character id of every allowed kind of character; an object with a tool's id
        (only objects among themselves and tools among themselves must differ). 1.01 is refused in Backend's words."""
        for depth in (0, 1.0):
            self.assertEqual(cm(loaded(model((("contactDepthMm",), depth))))["version"], 7)
        b = loaded(model((("objects", 0, "id"), "needle"), (("objects", 1, "id"), "Az09._-" + "x" * 57)))
        to(b, 145, 150)
        to(b, z=50)  # the needle's hub over the block, now called "needle"
        self.assertEqual(hits(b), [("needle", "needle", "hub", 0.4)])
        reply = b.sim.control({"cmd": "set", "collision_model": model((("contactDepthMm",), 1.01))}, b.t)
        self.assertEqual(reply["error"], "ValueError: collision_model.contactDepthMm must be 0 to 1.0, not 1.01")

    def test_the_cli_flag_loads_the_file(self):
        """At boot Z is not powered: the head starts at restRaw, the fall swept (the hub's edge over the block, as in
        SeeSaw's fall test)."""
        tmp = tempfile.mkdtemp(prefix="lumen-sim-test-")
        self.addCleanup(shutil.rmtree, tmp, True)
        path = os.path.join(tmp, "collision-model.json")
        with open(path, "w") as f:
            json.dump(model((("seesaw", "restRaw"), 60.0)), f)
        sim = SimProcess("--park", "145,150,31.5", "--collision-model", path)
        try:
            st = sim.ctl({"cmd": "state"})["state"]
            self.assertEqual(st["collision_model"]["version"], 7)
            self.assertEqual(st["head"]["physical"]["Z"], 60.0)
            self.assertEqual([(e["object"], e["layer"], e["overlapMm"]) for e in st["sim_errors"]],
                             [("block", "hub", 10.4)])
        finally:
            sim.stop()

    def test_the_cli_refuses_a_bad_or_missing_file(self):
        tmp = tempfile.mkdtemp(prefix="lumen-sim-test-")
        self.addCleanup(shutil.rmtree, tmp, True)
        bad = os.path.join(tmp, "bad.json")
        with open(bad, "w") as f:
            json.dump(model((("schema",), 2)), f)
        link = os.path.join(tmp, "ttyLUMEN")
        for path, why in ((bad, "collision_model must be a schema 1 model"),
                          (os.path.join(tmp, "missing.json"), "No such file")):
            out = subprocess.run([sys.executable, "-m", "lumen_sim", "--link", link, "--collision-model", path],
                                 cwd=PKG_ROOT, capture_output=True, text=True, timeout=10)
            self.assertEqual(out.returncode, 2, out.stderr)
            self.assertRegex(out.stderr, "--collision-model: .*" + why)
            self.assertFalse(os.path.lexists(link))


class TipHeights(unittest.TestCase):
    def test_n1_and_the_needle_below_at_and_above_their_knees_on_a_tilted_bed(self):
        """rc(x, y) = 4 + 0.001 x + 0.002 y. Head at (120, 150): N1's tip at (100, 90), rc 4.28; the needle's at
        (140, 80), rc 4.30, K - rc = 59.70. h1 = 1.0 (min(r, 31.5) - 4.28); h2 = 0.5 (59.70 - max(r, 32.5))."""
        b = loaded(model((("bed", "plane", "points"), TILTED),
                         (("seesaw", "needle"), {"kneeRaw": 32.5, "mmPerRaw": 0.5})))
        to(b, 120, 150)
        for r, n1, needle in ((20, 15.72, 13.6), (31.5, 27.22, 13.6), (32.5, 27.22, 13.6), (40, 27.22, 9.85)):
            with self.subTest(r=r):
                to(b, z=r)
                self.assertEqual(cm(b)["tips"], {"n1": n1, "needle": needle})
        self.assertEqual(hits(b), [])

    def test_unequal_gains_and_the_tilt_in_n1s_mm(self):
        """N1's gain 0.5, the needle's 2; the bed rises 0.01 raw per mm in X (rc = 4 + 0.01 x). Head at (200, 250),
        raw 31.5: N1's tip at (180, 190), h1 = 0.5 (31.5 - 5.8) = 12.85; the needle's at (220, 180),
        h2 = 2 ((64 - 6.2) - 31.5) = 52.6. The tilt counts in N1's mm (the plane is N1's contact raw): the camera, 40
        above the bed at the head, moves on in Y over a block 40.1 high 10 to 12 mm to its right, which stands
        0.5 x 0.01 x 12 = 0.06 mm higher on the bed: 0.16 mm into it."""
        tall = {"id": "tall", "kind": "fixed", "outline": {"box": [210, 299, 212, 301]}, "topZ": 40.1,
                "state": "measured"}
        b = loaded(model((("bed", "plane", "points"), [[0, 0, 4.0], [100, 0, 5.0], [0, 100, 4.0]]),
                         (("seesaw", "n1", "mmPerRaw"), 0.5), (("seesaw", "needle", "mmPerRaw"), 2),
                         (("objects",), [tall])))
        to(b, 200, 250)
        self.assertEqual(cm(b)["tips"], {"n1": 12.85, "needle": 52.6})
        to(b, y=300)
        self.assertEqual(hits(b), [("tall", "camera", "camera and light", 0.16)])

    def test_the_plane_is_fitted_through_every_point(self):
        """Four points off one plane: the least-squares fit is rc = 3.9 + 0.002 x + 0.002 y (the first three alone
        give 4 everywhere). Head at (120, 150): N1's tip at (100, 90), rc 4.28, h1 = 31.5 - 4.28; the needle's at
        (140, 80), rc 4.34, h2 = (64 - 4.34) - 31.5."""
        b = loaded(model((("bed", "plane", "points"), FLAT + [[100, 100, 4.4]])))
        to(b, 120, 150)
        self.assertEqual(cm(b)["tips"], {"n1": 27.22, "needle": 28.16})

    def test_the_tips_follow_the_head_during_a_move(self):
        """Read mid-move, the tips are where the head is then: on the flat bed the needle's h2 = 60 - r."""
        b = loaded()
        b.send("G1 Z50 F600")
        b.run(1.0)
        s = state(b)
        r = s["head"]["physical"]["Z"]
        self.assertTrue(s["moving"] and 31.5 < r < 50, r)
        self.assertAlmostEqual(s["collision_model"]["tips"]["needle"], 60 - r, places=5)


class Sweep(unittest.TestCase):
    def at_the_start(self, m=None):
        b = loaded(m)
        to(b, 145, 120)  # the hub at (165, 50): 6 mm short of the block's y 70
        to(b, z=50)      # the hub's bottom at 19, the block's top at 19.4
        self.assertEqual(hits(b), [])
        return b

    def test_the_needles_hub_swept_across_the_blocks_top(self):
        """Neither end of the leg overlaps the block: only the sweep does, 1 mm wide, 0.4 mm deep. The block as a box
        and as the same counter-clockwise polygon, starting at another corner."""
        polygon = {"polygon": [[190, 70], [190, 90], [170, 90], [170, 70]]}
        for m in (MODEL, model((("objects", 0, "outline"), polygon))):
            b = self.at_the_start(m)
            to(b, y=190)
            self.assertEqual(hits(b), [("block", "needle", "hub", 0.4)])
            e = state(b)["sim_errors"][0]
            self.assertEqual(e["line"], "G1 Y190 F3000")
            self.assertEqual(e["error"], "collision: needle (hub) 0.400 mm below the top of block")

    def test_the_same_leg_0_1_mm_clear_passes(self):
        b = self.at_the_start(model((("objects", 0, "outline"), {"box": [171.1, 70, 191.1, 90]})))
        to(b, y=190)
        to(b, y=120)
        self.assertEqual(hits(b), [])

    def test_a_leg_counts_at_its_lowest_end(self):
        """Up and across in one leg: the hub starts at 19, 0.4 mm below the block's top, and ends 10 mm higher."""
        b = self.at_the_start()
        to(b, y=190, z=40)
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.4)])

    def test_on_a_tilted_bed_an_objects_top_is_its_highest_over_the_overlap(self):
        """rc(x, y) = 4 + 0.001 x + 0.002 y. The hub's path covers all of a 4 mm block (topZ 15): its top is highest at
        (164, 84), 4.332 + 15 = 19.332; the hub's bottom is 19 wherever it is (equal gains)."""
        small = {"id": "small", "kind": "fixed", "outline": {"box": [160, 80, 164, 84]}, "topZ": 15.0,
                 "state": "measured"}
        b = self.at_the_start(model((("bed", "plane", "points"), TILTED), (("objects",), [small])))
        to(b, y=190)
        self.assertEqual(hits(b), [("small", "needle", "hub", 0.332)])

    def test_a_move_cut_short_is_swept_as_far_as_it_went(self):
        """A quick stop ends the block where the head is: the hub, 30 mm along, is over the block."""
        b = self.at_the_start()
        b.send("G1 Y190 F600")  # 10 mm/s
        b.run(3.1)
        b.write("M410\n")
        b.run(3.0)
        self.assertLess(state(b)["head"]["physical"]["Y"], 160)
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.4)])
        self.assertEqual(state(b)["sim_errors"][0]["line"], "G1 Y190 F600")

    def test_a_collision_is_reported_once(self):
        """Once per collision: not again while the layer stays in it, again once it was clear at a leg's end."""
        b = self.at_the_start()
        to(b, y=150)       # into the block: the hub's edge over it, 0.4 mm below its top
        to(b, z=50.5)      # deeper, still in it
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.4)])
        to(b, z=40)        # up and clear at the leg's end
        self.assertEqual(len(hits(b)), 1)
        to(b, z=50)        # a new collision
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.4)] * 2)
        to(b, y=190)       # on through it and clear at the end
        to(b, y=120)       # back across: a new one
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.4)] * 3)

    def test_the_same_model_set_again_reports_nothing_again(self):
        """A set keeps what was reported where the leg under way began: no second report at rest, nor at the end of
        a move during which it was set, as the hub was leaving the block."""
        b = self.at_the_start()
        to(b, y=150)            # the hub over the block
        put(b, MODEL)
        b.send("G1 Y190 F600")  # 10 mm/s: out of it from Y 166
        b.run(3.0)
        put(b, MODEL)
        b.send("M400")
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.4)])

    def test_after_a_fault_jump_once_counts_from_where_the_head_is(self):
        """A jump that leaves the hub in the block keeps that collision reported; one that carries it out makes a
        move back in a new collision; one that lands it in the block is reported by the next leg."""
        def lost(**xy):
            self.assertTrue(b.sim.control(dict(cmd="fault", fault="lost_steps", **xy), b.t)["ok"])
        b = self.at_the_start()
        to(b, y=150)  # the hub's edge 1 mm over the block
        lost(x=-0.5)  # still 0.5 mm over it
        to(b, z=50.5)
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.4)])
        lost(y=-40)   # out, 40 mm to the front
        to(b, y=190)  # back in: Y 150 on the machine
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.4), ("block", "needle", "hub", 0.9)])
        to(b, y=150)  # out: Y 110
        lost(y=40)    # in
        to(b, z=50.6)
        self.assertEqual(hits(b)[2:], [("block", "needle", "hub", 1.0)])

    def test_a_circle_is_the_64_gon_around_it(self):
        """The hub (r 6) as the 64-gon around it, a vertex at 6 / cos(pi / 64) = 6.0072 mm on X each side of its
        centre: a block 6.004 mm to its right is hit, 0.4 mm below its top; one 6.008 mm to its left is not."""
        near = {"id": "near", "kind": "fixed", "outline": {"box": [126.004, 129, 140, 131]}, "topZ": 15.4,
                "state": "measured"}
        far = {"id": "far", "kind": "fixed", "outline": {"box": [100, 129, 113.992, 131]}, "topZ": 15.4,
               "state": "measured"}
        b = loaded(model((("objects",), [near, far])))
        to(b, 100, 200)  # the hub at (120, 130)
        to(b, z=50)
        self.assertEqual(hits(b), [("near", "needle", "hub", 0.4)])

    def test_an_unmeasured_object_blocks_at_every_height(self):
        """Added (no topZ) or moved (a topZ it no longer has): the camera, 40 mm up, is over it."""
        added = {"id": "added", "kind": "movable", "outline": {"box": [300, 300, 320, 320]}, "topZ": None,
                 "state": "added"}
        moved = {"id": "moved", "kind": "movable", "outline": {"box": [300, 360, 320, 380]}, "topZ": 1.0,
                 "state": "moved"}
        b = loaded(model((("objects",), [BLOCK, BLANK, added, moved])))
        to(b, 310, 310)
        to(b, y=370)
        self.assertEqual(hits(b), [("added", "camera", "camera and light", None),
                                   ("moved", "camera", "camera and light", None)])
        self.assertEqual(state(b)["sim_errors"][0]["error"], "collision: camera (camera and light) over added, "
                                                             "not measured")

    def test_a_tip_resting_on_a_touchable_top_is_contact(self):
        """N1's tip on the touchable blank's top (raw 5.6) and down to contactDepthMm into it (0.25 when the file has
        none) is resting on it; deeper is a collision. Anything else below a top collides at any depth: N1's tip in
        the blank not touchable, or in the bare bed, and the hub 0.1 mm into the block."""
        for edits, resting, deeper, mm in (((), 5.35, 5.325, 0.275),
                                           (((("contactDepthMm",), 0.1),), 5.5, 5.475, 0.125)):
            b = loaded(model(*edits))
            to(b, 250, 290)  # N1's tip at (230, 230), over the blank
            for r in (5.6, resting):
                to(b, z=r)
                self.assertEqual(hits(b), [], (edits, r))
            to(b, z=deeper)
            self.assertEqual(hits(b), [("blank", "n1", "tip", mm)])
        b = loaded(model((("objects", 1, "touchable"), False)))
        to(b, 250, 290)
        to(b, z=5.575)
        to(b, z=31.5)
        to(b, 120, 290)  # N1's tip at (100, 230), over the bare bed
        to(b, z=3.9)
        self.assertEqual(hits(b), [("blank", "n1", "tip", 0.025), ("bed", "n1", "tip", 0.1)])
        b = self.at_the_start()
        to(b, z=49.7)
        to(b, y=190)
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.1)])

    def test_a_tip_rests_only_inside_the_top_at_both_ends_of_the_leg(self):
        """As the engine's check: contact needs the tip's outline inside the touchable object's at both ends. N1 0.2 mm
        into the blank (raw 5.4) pressed and slid within it rests; slid out over its edge, in from the side or pressed
        down astride its edge, it collides. So does the needle's tip slid into a touchable switch it may press from
        above (h2 = 60 - r: 0.2 mm below the switch's top, 4 + 12, at raw 48.2)."""
        b = loaded()
        to(b, 250, 290)  # N1's tip at (230, 230), over the blank (x 200..260)
        to(b, z=5.4)
        to(b, x=240)
        self.assertEqual(hits(b), [])
        to(b, x=210)     # its tip out to x 190
        self.assertEqual(hits(b), [("blank", "n1", "tip", 0.2)])
        for start, end in (((210, 290), (250, 290)), ((220, 290), (220, 290))):  # in from x 190; astride x 200
            b = loaded()
            to(b, *start)
            to(b, z=5.4)
            to(b, *end)
            self.assertEqual(hits(b), [("blank", "n1", "tip", 0.2)], start)
        switch = {"id": "height-switch", "kind": "fixed", "outline": {"circle": [165, 80, 5]}, "topZ": 12.0,
                  "state": "measured", "touchable": True}
        b = loaded(model((("objects",), [switch])))
        to(b, 145, 150)  # the needle's tip at the switch's centre
        to(b, z=48.2)
        to(b, z=31.5)
        self.assertEqual(hits(b), [])
        to(b, x=120)     # 25 mm to its left
        to(b, z=48.2)
        to(b, x=145)
        self.assertEqual(hits(b), [("height-switch", "needle", "tip", 0.2)])

    def test_every_other_layer_is_strict_on_a_touchable_top(self):
        """Only a tip rests: the hub 0.1 mm into the block made touchable collides, swept across it as above or
        pressed down inside it at both ends (the needle without its tip layer, which would be 5.1 mm into it)."""
        touchable = (("objects", 0, "touchable"), True)
        b = self.at_the_start(model(touchable))
        to(b, z=49.7)
        to(b, y=190)
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.1)])
        b = loaded(model(touchable, (("tools", 1, "layers"), MODEL["tools"][1]["layers"][1:])))
        to(b, 160, 150)  # the hub (r 6) at (180, 80), the block's centre
        to(b, z=49.7)
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.1)])

    def test_a_fault_mid_move_splits_its_sweep(self):
        """A lost_steps jump during a move: the move is swept as far as the head went (its line), then on from where
        the head landed. G1 Y190 F600 (10 mm/s) carries the hub across the block before 5 s: a jump 40 mm to the
        left at 6 s does not unsee that, and from X 105 a jump 40 mm to the right at 6 s, past the block's Y, adds
        no crossing the head never made. A jump that leaves the hub in the block keeps it reported once."""
        def lost(**xy):
            self.assertTrue(b.sim.control(dict(cmd="fault", fault="lost_steps", **xy), b.t)["ok"])
        b = self.at_the_start()
        b.send("G1 Y190 F600")
        b.run(6.0)
        lost(x=-40)
        b.send("M400")
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.4)])
        self.assertEqual(state(b)["sim_errors"][0]["line"], "G1 Y190 F600")
        b = loaded()
        to(b, 105, 120)  # the hub 39 mm left of the block
        to(b, z=50)
        b.send("G1 Y190 F600")
        b.run(6.0)
        lost(x=40)
        b.send("M400")
        self.assertEqual(state(b)["head"]["physical"], {"X": 145.0, "Y": 190.0, "Z": 50.0})
        self.assertEqual(hits(b), [])
        to(b, y=120)  # back across it at X 145, and on: each move swept from its own start
        to(b, y=110)
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.4)])
        b = self.at_the_start()
        b.send("G1 Y150 F600")  # the hub over the block from Y 134
        b.run(2.8)
        self.assertGreater(state(b)["head"]["physical"]["Y"], 135)
        lost(x=-0.5)            # still 0.5 mm over it
        b.send("M400")
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.4)])


class SeeSaw(unittest.TestCase):
    def test_with_the_z_motor_off_it_rests_at_rest_raw(self):
        for release in ("power cycle", "M18 Z", "M112", "il-safety M112 mid-move"):
            with self.subTest(release):
                b = loaded(model((("seesaw", "restRaw"), 60.0)),
                           personality="il-safety" if release.startswith("il") else "stock")
                if release == "power cycle":
                    b.sim.power_cycle(b.t)
                elif release == "M18 Z":
                    b.send(release)
                elif release == "M112":
                    b.write("M112\n")
                    b.run(1.0)  # stock: the steppers go off 0.85 s after the kill
                else:  # il-safety's kill stops the head at once; it falls from there when Z lets go, 0.85 s later
                    b.send("G1 Z20 F60")
                    b.run(2.0)
                    b.write("M112\n")
                    b.run(1.0)
                self.assertEqual(z(b), 60.0)
                self.assertEqual(hits(b), [])

    def test_the_fall_is_swept(self):
        """The needle falls onto what is under it: the hub over the block drops from 37.5 to 9 above the bed."""
        b = loaded(model((("seesaw", "restRaw"), 60.0)))
        to(b, 145, 150)  # the hub at (165, 80), its edge over the block; the needle up at raw 31.5
        b.sim.power_cycle(b.t)
        self.assertEqual(hits(b), [("block", "needle", "hub", 10.4)])
        self.assertIsNone(state(b)["sim_errors"][0]["line"])

    def test_a_model_set_while_z_is_off_puts_it_at_rest(self):
        """Z not powered since the boot, or let go before the model came: the arm already rests at restRaw, so the
        head goes there when the model is set, the fall swept as above; a power cycle then leaves it there."""
        for how in ("boot", "M18 Z"):
            with self.subTest(how):
                b = Bench(park=(145.0, 150.0, 31.5))
                if how == "M18 Z":
                    b.send("G28")
                    to(b, 145, 150)
                    b.send("M18 Z")
                put(b, model((("seesaw", "restRaw"), 60.0)))
                self.assertEqual(z(b), 60.0)
                self.assertEqual(hits(b), [("block", "needle", "hub", 10.4)])
                b.sim.power_cycle(b.t)
                self.assertEqual((z(b), len(hits(b))), (60.0, 1))

    def test_a_model_set_with_z_off_checks_its_rest_and_sweeps_nothing(self):
        """The arm has rested at the new model's restRaw all along: the head is checked there, not swept from where
        the old model or the park had it. A collision the old model reported stays reported while its layer is still
        in that object there: the same file again adds nothing; after a model without the block, the block's is new
        again. Rest 60 with the blank only, then rest 40 with the block: the hub is 9.6 mm over it."""
        r60 = model((("seesaw", "restRaw"), 60.0))
        no_block = model((("seesaw", "restRaw"), 60.0), (("objects",), [BLANK]))
        b = put(put(Bench(park=(145.0, 150.0, 31.5)), r60), r60)
        self.assertEqual(hits(b), [("block", "needle", "hub", 10.4)])
        put(put(b, no_block), r60)
        self.assertEqual(hits(b), [("block", "needle", "hub", 10.4)] * 2)
        b = put(put(Bench(park=(145.0, 150.0, 31.5)), no_block), model((("seesaw", "restRaw"), 40.0)))
        self.assertEqual((z(b), hits(b)), (40.0, []))

    def test_the_pa5_switch_sees_the_rest(self):
        """A model set with Z off has the head at its rest for the PA5 switch too: the needle there, 0.6 mm past its
        trip, is a press and an overtravel before any read."""
        b = Bench(park=(200.0, 50.0, 31.5))
        press = {"tool": "needle", "x0": 199.0, "y0": 49.0, "x1": 201.0, "y1": 51.0, "trip_z": 52.0}
        self.assertTrue(b.sim.control({"cmd": "set", "pa5_switch": {"presses": [press]}}, b.t)["ok"])
        put(b, model((("seesaw", "restRaw"), 52.6)))
        self.assertTrue(state(b)["pa5"]["pressed"])
        self.assertEqual([e["error"] for e in state(b)["sim_errors"]],
                         ["PA5 switch overtravel: needle 0.60 mm past its trip point (overtravel 0.50 mm)"])

    def test_the_end_stops_hold_it(self):
        """Also where the fall is swept, and during a move. The fall stops at 66: the needle's tip 6 mm into the bed
        (it meets it at 60), the hub over the block 16.4 mm into it and 1 mm into the bed (at 70: 10, 20.4, 5)."""
        b = loaded(model((("seesaw", "restRaw"), 70.0)))
        to(b, 145, 150)
        b.sim.power_cycle(b.t)
        self.assertEqual(z(b), 66.0)
        self.assertEqual(hits(b), [("bed", "needle", "tip", 6.0), ("block", "needle", "hub", 16.4),
                                   ("bed", "needle", "hub", 1.0)])
        b = put(Bench(park=PARK), MODEL)  # not homed: only the arm's end stops limit Z
        b.send("G1 Z40 F600")
        b.run(3.9)  # 69.5 by now without the stop, still moving
        self.assertEqual((z(b), state(b)["moving"]), (66.0, True))
        b.send("M400")
        self.assertEqual(z(b), 66.0)
        to(b, z=0)
        self.assertEqual(z(b), 26.0, "the 5.5 mm the arm could not go are lost")
        to(b, z=-40)
        self.assertEqual(z(b), -1.0)

    def test_the_end_stops_hold_it_at_a_set_and_a_z_shift(self):
        """Parked at raw 70, the head is at 66 once the model is set: the needle's tip 6 mm into the bed and its hub
        1 mm, not 10 and 5, and 2 mm down is 64. A z_shift of 10 mm at raw 63 (the tip 3 mm into the bed) also stops
        at 66: the next leg finds the hub 1 mm into the bed, not 8, and ends 1 mm down at 65."""
        b = put(Bench(park=(100.0, 300.0, 70.0)), MODEL)
        to(b, z=-2)  # not homed: Z 0 is where the head was parked
        self.assertEqual((z(b), hits(b)), (64.0, [("bed", "needle", "tip", 6.0), ("bed", "needle", "hub", 1.0)]))
        b = loaded()
        to(b, 100, 300)
        to(b, z=63)
        self.assertTrue(b.sim.control({"cmd": "fault", "fault": "z_shift", "z": 10.0}, b.t)["ok"])
        to(b, z=62)
        self.assertEqual((z(b), hits(b)), (65.0, [("bed", "needle", "tip", 3.0), ("bed", "needle", "hub", 1.0)]))

    def test_without_a_rest_the_z_drop_applies(self):
        b = loaded()
        self.assertTrue(b.sim.control({"cmd": "fault", "fault": "z_drop", "z": -0.4}, b.t)["ok"])
        b.sim.power_cycle(b.t)
        self.assertAlmostEqual(z(b), 31.1, places=6)


class Vacuum(unittest.TestCase):
    def test_n1_seals_on_a_measured_touchable_blank_at_least_2_mm_inside(self):
        b = loaded()
        self.assertEqual(sealed(b, 250, 290, (5.625, 5.6)), [False, True], "the blank's top: 4 + 1.6")
        self.assertEqual(sealed(b, 222, 290, (5.625, 5.6)), [False, True], "2 mm inside its edge")
        self.assertEqual(sealed(b, 221, 290, (5.6, 5.0)), [False, False], "1 mm inside its edge")

    def test_never_on_the_bare_bed_or_an_object_not_touchable(self):
        b = loaded()
        self.assertTrue(b.sim.control({"cmd": "set", "surface_z": 5.3}, b.t)["ok"])  # replaced by the model
        self.assertEqual(sealed(b, 120, 290, (5.3, 0.5)), [False, False], "the bare bed")
        self.assertEqual(hits(b), [("bed", "n1", "tip", 3.5)], "N1 at raw 0.5 is 3.5 mm below the bed plane")
        self.assertEqual(sealed(b, 200, 140, (19.4, 19.0)), [False, False], "the block: not touchable")
        b = loaded(model((("objects", 1, "state"), "moved")))
        self.assertEqual(sealed(b, 250, 290, (5.6, 5.0)), [False, False], "a touchable blank moved: no top")

    def test_touchable_says_where_not_surface(self):
        """The blank keeps its surface but is not touchable: no seal. The block, touchable with no surface, seals at
        its top (4 + 15.4)."""
        b = loaded(model((("objects", 1, "touchable"), False), (("objects", 0, "touchable"), True)))
        self.assertEqual(sealed(b, 250, 290, (5.6, 5.5)), [False, False], "the blank, not touchable")
        self.assertEqual(sealed(b, 200, 140, (19.425, 19.4)), [False, True], "the block, touchable")


class NoModel(unittest.TestCase):
    def test_without_a_model_nothing_changes(self):
        b = Bench(park=PARK)
        b.send("G28")
        self.assertIsNone(cm(b))
        put(put(b, MODEL), None)
        self.assertIsNone(cm(b))
        self.assertTrue(b.sim.control({"cmd": "set", "surface_z": 5.3}, b.t)["ok"])
        self.assertTrue(b.sim.control({"cmd": "fault", "fault": "z_drop", "z": -0.4}, b.t)["ok"])
        self.assertEqual(sealed(b, 120, 290, (5.325, 5.3)), [False, True], "surface_z, as today")
        to(b, 145, 150, 63)  # the needle's hub would be deep in the block
        b.sim.power_cycle(b.t)
        self.assertAlmostEqual(z(b), 62.6, places=6)
        to(b, z=80)
        self.assertEqual(hits(b), [])


if __name__ == "__main__":
    unittest.main()
