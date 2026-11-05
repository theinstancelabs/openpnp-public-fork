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
         "topZ": 15.4, "marginMm": 1.0, "noPaste": False, "surface": None,
         "measured": {"top": {"how": "n1-touch", "by": "Infra", "atMs": 0, "errMm": 0.05}}, "state": "measured",
         "invalidatedBy": ["moved", "touched"]}
BLANK = {"id": "blank", "name": "the front blank", "kind": "movable", "role": "blank",
         "outline": {"box": [200, 200, 260, 260]}, "topZ": 1.6, "marginMm": 1.0, "noPaste": False,
         "surface": "blank-front", "state": "measured", "invalidatedBy": ["moved"]}
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

    def test_the_cli_flag_loads_the_file(self):
        tmp = tempfile.mkdtemp(prefix="lumen-sim-test-")
        self.addCleanup(shutil.rmtree, tmp, True)
        path = os.path.join(tmp, "collision-model.json")
        with open(path, "w") as f:
            json.dump(MODEL, f)
        sim = SimProcess("--collision-model", path)
        try:
            self.assertEqual(sim.ctl({"cmd": "state"})["state"]["collision_model"]["version"], 7)
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
        small = {"id": "small", "outline": {"box": [160, 80, 164, 84]}, "topZ": 15.0, "state": "measured"}
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

    def test_an_unmeasured_object_blocks_at_every_height(self):
        """Added (no topZ) or moved (a topZ it no longer has): the camera, 40 mm up, is over it."""
        added = {"id": "added", "outline": {"box": [300, 300, 320, 320]}, "topZ": None, "state": "added"}
        moved = {"id": "moved", "outline": {"box": [300, 360, 320, 380]}, "topZ": 1.0, "state": "moved"}
        b = loaded(model((("objects",), [BLOCK, BLANK, added, moved])))
        to(b, 310, 310)
        to(b, y=370)
        self.assertEqual(hits(b), [("added", "camera", "camera and light", None),
                                   ("moved", "camera", "camera and light", None)])
        self.assertEqual(state(b)["sim_errors"][0]["error"], "collision: camera (camera and light) over added, "
                                                             "not measured")

    def test_a_tip_resting_on_a_top_is_contact(self):
        """N1's tip on the blank's top (raw 5.6) and down to 0.25 mm into it is resting on it; deeper is a collision.
        Any other layer below a top is one: the hub 0.1 mm into the block."""
        b = loaded()
        to(b, 250, 290)  # N1's tip at (230, 230), over the blank
        for r in (5.6, 5.35):
            to(b, z=r)
            self.assertEqual(hits(b), [], r)
        to(b, z=5.325)
        self.assertEqual(hits(b), [("blank", "n1", "tip", 0.275)])
        b = self.at_the_start()
        to(b, z=49.7)
        to(b, y=190)
        self.assertEqual(hits(b), [("block", "needle", "hub", 0.1)])


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
                else:  # il-safety's kill stops the head at once and lets go: it falls from where it stopped
                    b.send("G1 Z20 F60")
                    b.run(2.0)
                    b.write("M112\n")
                    b.run(0.1)
                self.assertEqual(z(b), 60.0)
                self.assertEqual(hits(b), [])

    def test_the_fall_is_swept(self):
        """The needle falls onto what is under it: the hub over the block drops from 37.5 to 9 above the bed."""
        b = loaded(model((("seesaw", "restRaw"), 60.0)))
        to(b, 145, 150)  # the hub at (165, 80), its edge over the block; the needle up at raw 31.5
        b.sim.power_cycle(b.t)
        self.assertEqual(hits(b), [("block", "needle", "hub", 10.4)])
        self.assertIsNone(state(b)["sim_errors"][0]["line"])

    def test_the_end_stops_hold_it(self):
        b = loaded(model((("seesaw", "restRaw"), 70.0)))
        b.sim.power_cycle(b.t)
        self.assertEqual(z(b), 66.0)
        b = put(Bench(park=PARK), MODEL)  # not homed: only the arm's end stops limit Z
        to(b, z=40)
        self.assertEqual(z(b), 66.0)
        to(b, z=0)
        self.assertEqual(z(b), 26.0, "the 5.5 mm the arm could not go are lost")
        to(b, z=-40)
        self.assertEqual(z(b), -1.0)

    def test_without_a_rest_the_z_drop_applies(self):
        b = loaded()
        self.assertTrue(b.sim.control({"cmd": "fault", "fault": "z_drop", "z": -0.4}, b.t)["ok"])
        b.sim.power_cycle(b.t)
        self.assertAlmostEqual(z(b), 31.1, places=6)


class Vacuum(unittest.TestCase):
    def test_n1_seals_on_a_measured_blank_at_least_2_mm_inside(self):
        b = loaded()
        self.assertEqual(sealed(b, 250, 290, (5.625, 5.6)), [False, True], "the blank's top: 4 + 1.6")
        self.assertEqual(sealed(b, 222, 290, (5.625, 5.6)), [False, True], "2 mm inside its edge")
        self.assertEqual(sealed(b, 221, 290, (5.6, 5.0)), [False, False], "1 mm inside its edge")

    def test_never_on_the_bare_bed_or_a_fixture(self):
        b = loaded()
        self.assertTrue(b.sim.control({"cmd": "set", "surface_z": 5.3}, b.t)["ok"])  # replaced by the model
        self.assertEqual(sealed(b, 120, 290, (5.3, 0.5)), [False, False], "the bare bed")
        self.assertEqual(hits(b), [("bed", "n1", "tip", 3.5)], "N1 at raw 0.5 is 3.5 mm below the bed plane")
        self.assertEqual(sealed(b, 200, 140, (19.4, 19.0)), [False, False], "the block: no surface")


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
