"""Planner, trapezoids, soft endstops and homing (fake clock)."""
import math
import unittest

from lumen_sim.bench import Bench
from lumen_sim.motion import trapezoid

FIRST_MOVE_DELAY = 0.100  # Marlin BLOCK_DELAY_FOR_1ST_MOVE


def rest_to_rest(n, rate, accel, r0=120.0):
    """Seconds for n steps at nominal `rate` steps/s, `accel` steps/s^2, starting and ending at r0 steps/s."""
    s_ramp = (rate * rate - r0 * r0) / (2 * accel)
    if 2 * s_ramp > n:
        peak = math.sqrt(accel * n + r0 * r0)
        return 2 * (peak - r0) / accel
    return 2 * (rate - r0) / accel + (n - 2 * s_ramp) / rate


class Trapezoid(unittest.TestCase):
    def test_cruise_case(self):
        t = trapezoid(n=7992, rate=1599, accel=4440, r0=120, rf=120)
        self.assertAlmostEqual(t.duration, rest_to_rest(7992, 1599, 4440), places=9)
        self.assertAlmostEqual(t.steps_at(t.duration), 7992, places=6)
        self.assertAlmostEqual(t.steps_at(t.t_acc), (1599 ** 2 - 120 ** 2) / (2 * 4440), places=6)

    def test_triangle_case(self):
        t = trapezoid(n=100, rate=48000, accel=640000, r0=120, rf=120)
        self.assertAlmostEqual(t.duration, rest_to_rest(100, 48000, 640000), places=9)

    def test_time_for_steps_inverts_steps_at(self):
        t = trapezoid(n=48112, rate=4796, accel=88800, r0=120, rf=120)
        for k in (0, 1, 50, 200, 24000, 48000, 48112):
            self.assertAlmostEqual(t.steps_at(t.time_for_steps(k)), k, places=5)

    def test_slow_move_runs_at_its_nominal_rate(self):
        t = trapezoid(n=40, rate=40, accel=80000, r0=120, rf=120)  # Z at 1 mm/s
        self.assertAlmostEqual(t.duration, 1.0, places=9)


class Planner(unittest.TestCase):
    def test_single_move_waits_the_first_move_delay(self):
        b = Bench()
        b.send("M204 S2000")
        b.send("G1 X10 F6000")
        r = b.send("M400")
        # X: 3200 steps, 32000 steps/s nominal, 2000 mm/s^2 = 640000 steps/s^2
        self.assertAlmostEqual(r.ok_at, FIRST_MOVE_DELAY + rest_to_rest(3200, 32000, 640000), delta=1e-4)

    def test_third_block_ends_the_delay(self):
        b = Bench()
        b.send("M204 S2000")
        for x in (10, 20, 30):
            b.send("G1 X%d F6000" % x)
        r = b.send("M400")
        self.assertAlmostEqual(r.ok_at, 3 * rest_to_rest(3200, 32000, 640000), delta=1e-4)

    def test_rotation_uses_degrees_and_axis_limits(self):
        b = Bench()
        b.send("M201 B20000")
        b.send("M203 B2400")
        b.send("M204 S20000")
        b.send("G92 B0")
        b.send("G1 B10836.0000 F64800")
        r = b.send("M400")
        n = round(10836 * 4.44)
        rate = math.ceil(1080 * 4.44)  # F64800 deg/min = 1080 deg/s
        self.assertAlmostEqual(r.ok_at, FIRST_MOVE_DELAY + rest_to_rest(n, rate, 20000 * 4.44), delta=1e-4)
        self.assertGreater(r.ok_at, 10.1)

    def test_m201_caps_the_screw_acceleration(self):
        b = Bench()
        b.send("M204 S20000")  # M201 B stays at the boot 10000
        b.send("G92 B0")
        b.send("G1 B360 F64800")
        r = b.send("M400")
        n = round(360 * 4.44)
        self.assertAlmostEqual(r.ok_at, FIRST_MOVE_DELAY + rest_to_rest(n, math.ceil(1080 * 4.44), 10000 * 4.44),
                               delta=1e-4)

    def test_m203_caps_the_feed(self):
        b = Bench()
        b.send("M203 X10")
        b.send("G1 X10 F6000")
        r = b.send("M400")
        self.assertGreater(r.ok_at, 1.0)

    def test_full_planner_blocks_the_next_move(self):
        b = Bench()
        b.send("M204 S2000")
        oks = []
        for i in range(1, 17):
            oks.append(b.send("G1 X%d F600" % (i * 10)).ok_at)  # 1 s per block
        self.assertTrue(all(t < 0.001 for t in oks[:15]), oks)  # 16 slots, 15 usable
        self.assertGreater(oks[15], 0.9)  # waits for the first block to finish

    def test_full_planner_acks_when_the_head_block_ends(self):
        """Marlin re-checks moves_free() in its idle() loop: the ok comes as the slot frees, not at a later wake."""
        b = Bench()
        b.send("M204 S2000")
        for i in range(1, 16):
            b.send("G1 X%d F600" % (i * 10))  # 15 blocks of 1 s, started at once (3+ queued)
        head_end = b.sim.fw.stepper.blocks[0].t_end
        r = b.send("G1 X160 F600")
        self.assertAlmostEqual(r.ok_at, head_end, delta=0.01)
        self.assertEqual(r.busy, [])

    def test_full_planner_wait_longer_than_the_keepalive(self):
        b = Bench()
        b.send("M204 S2000")
        for i in range(1, 16):
            b.send("G1 X%d F60" % (i * 3))  # 15 blocks of about 3 s
        head_end = b.sim.fw.stepper.blocks[0].t_end
        self.assertGreater(head_end, 2.5)
        r = b.send("G1 X48 F60")
        self.assertAlmostEqual(r.ok_at, head_end, delta=0.01)
        self.assertEqual(r.busy, [2.0])  # one keepalive while waiting, none after the slot frees

    def test_m18_waits_for_motion(self):
        b = Bench()
        b.send("G1 B1720 F6000")  # 1000 deg from the boot B=720 at 100 deg/s
        r = b.send("M18 B")
        self.assertGreater(r.ok_at, 9.0)
        self.assertFalse(b.sim.state(b.t)["enabled"]["B"])

    def test_relative_mode(self):
        b = Bench()
        b.send("G91")
        b.send("G1 X5 F6000")
        b.send("G1 X5 F6000")
        self.assertTrue(b.send("M114").lines[0].startswith("X:10.00 "))


class SoftEndstops(unittest.TestCase):
    def test_only_homed_axes_are_clamped(self):
        b = Bench()
        b.send("G1 X-5 A-100 F6000")
        self.assertTrue(b.send("M114").lines[0].startswith("X:-5.00 Y:0.00 Z:0.00 A:-100.00"))
        b.send("M400")
        b.send("G28")
        b.send("G1 X500 Y-3 Z70 A-200 F12000")
        self.assertTrue(b.send("M114").lines[0].startswith("X:433.00 Y:0.00 Z:63.00 A:-200.00"))


class Homing(unittest.TestCase):
    def test_g28_homes_xyz_leaves_ab_and_reports(self):
        b = Bench(park=(100.0, 50.0, 31.5))
        b.send("G92 X7 A3")
        r = b.send("G28")
        self.assertEqual(r.lines[-2:], [
            "X:0.00 Y:0.00 Z:31.50 A:3.00 B:720.00 Count X:0 Y:0 Z:1260 A:3197 B:3197", "ok"])
        state = b.sim.state(b.t)
        self.assertEqual(state["homed"], {"X": True, "Y": True, "Z": True})
        self.assertEqual([round(v, 6) for v in state["physical"][:3]], [0.0, 0.0, 31.5])

    def test_power_cycle_keeps_the_physical_position(self):
        b = Bench(park=(10.0, 10.0, 31.5))
        b.send("G28")
        b.send("G1 X100 Y200 F12000")
        b.send("M400")
        b.sim.power_cycle(b.t)
        self.assertEqual(b.send("M114").lines[0],
                         "X:0.00 Y:0.00 Z:0.00 A:720.00 B:720.00 Count X:0 Y:0 Z:0 A:3197 B:3197")
        state = b.sim.state(b.t)
        self.assertEqual([round(v, 6) for v in state["physical"][:3]], [100.0, 200.0, 31.5])
        self.assertEqual(state["homed"], {"X": False, "Y": False, "Z": False})


if __name__ == "__main__":
    unittest.main()
