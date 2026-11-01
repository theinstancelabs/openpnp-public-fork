"""INF-08: the physical head apart from the controller's belief (fake clock).

Home offset, lost steps, a Z shift of the whole frame, Z release with a drop, and the M18/M84 S idle timeout. The
controller never sees these: M114 keeps its belief; the control state's `head` shows physical, logical and offset.
"""
import unittest

from lumen_sim.bench import Bench

HOME_REPORT = "X:0.00 Y:0.00 Z:31.50 A:720.00 B:720.00 Count X:0 Y:0 Z:1260 A:3197 B:3197"
ZERO = {"X": 0.0, "Y": 0.0, "Z": 0.0}
PARK = (100.0, 50.0, 31.5)  # whole steps from the switches, so homes land exactly on them


def fault(b, **spec):
    reply = b.sim.control(dict(cmd="fault", **spec), b.t)
    assert reply["ok"], reply
    return reply


def clear(b):
    assert b.sim.control({"cmd": "clear"}, b.t)["ok"]


def state(b):
    return b.sim.state(b.t)


def head(b):
    return state(b)["head"]


def homed(**faults):
    b = Bench(park=PARK)
    for kind, spec in faults.items():
        fault(b, fault=kind, **spec)
    b.send("G28")
    return b


class Head(unittest.TestCase):
    def test_at_boot_the_controller_believes_zero_and_the_head_is_parked(self):
        h = head(Bench(park=(5.0, 7.5, 31.5)))
        self.assertEqual(h["physical"], {"X": 5.0, "Y": 7.5, "Z": 31.5})
        self.assertEqual(h["logical"], ZERO)
        self.assertEqual(h["offset"], {"X": 5.0, "Y": 7.5, "Z": 31.5})

    def test_after_a_true_home_physical_equals_logical(self):
        b = homed()
        b.send("G1 X120.5 Y30.25 Z20 F12000")
        b.send("M400")
        h = head(b)
        self.assertEqual(h["physical"], {"X": 120.5, "Y": 30.25, "Z": 20.0})
        self.assertEqual(h["logical"], {"X": 120.5, "Y": 30.25, "Z": 20.0})
        self.assertEqual(h["offset"], ZERO)

    def test_logical_is_in_g92_coordinates(self):
        b = homed()
        b.send("G92 X10 Y-2")
        h = head(b)
        self.assertEqual(h["logical"], {"X": 10.0, "Y": -2.0, "Z": 31.5})
        self.assertEqual(h["offset"], {"X": -10.0, "Y": 2.0, "Z": 0.0})

    def test_logical_is_where_the_steps_are_during_a_move(self):
        b = homed()
        b.send("G1 X100 F600")  # 10 mm/s: 10 s
        b.run(5.0)
        h = head(b)
        self.assertGreater(h["logical"]["X"], 10.0)
        self.assertLess(h["logical"]["X"], 90.0)
        self.assertEqual(h["offset"], ZERO)
        self.assertTrue(b.send("M114").lines[0].startswith("X:100.00 "))  # M114 prints the planned end

    def test_defaults(self):
        st = state(Bench())
        self.assertEqual(st["switches"], ZERO)
        self.assertEqual(st["z_drop"], 0.0)
        self.assertEqual(st["stepper_timeout_s"], 3600.0)


class HomeOffset(unittest.TestCase):
    def test_g28_lands_off_while_the_controller_reports_home(self):
        b = Bench(park=PARK)
        fault(b, fault="home_offset", x=0.5, y=-0.25)
        self.assertEqual(head(b)["physical"], {"X": 100.0, "Y": 50.0, "Z": 31.5})  # nothing moves until a home
        self.assertEqual(state(b)["switches"], {"X": 0.5, "Y": -0.25, "Z": 0.0})
        r = b.send("G28")
        self.assertEqual(r.lines[-2:], [HOME_REPORT, "ok"])
        self.assertEqual(b.send("M114").lines, [HOME_REPORT, "ok"])
        h = head(b)
        self.assertEqual(h["physical"], {"X": 0.5, "Y": -0.25, "Z": 31.5})
        self.assertEqual(h["logical"], {"X": 0.0, "Y": 0.0, "Z": 31.5})
        self.assertEqual(h["offset"], {"X": 0.5, "Y": -0.25, "Z": 0.0})
        self.assertEqual(b.send("M119").lines,  # the switches it homed on, where they now are
                         ["Reporting endstop status", "x_min: TRIGGERED", "y_min: TRIGGERED", "z_min: open", "ok"])

    def test_every_later_home_lands_off_until_cleared(self):
        b = homed(home_offset={"x": 0.5})
        b.send("G1 X200 Y100 F12000")
        b.send("M400")
        self.assertEqual(head(b)["physical"]["X"], 200.5)
        b.send("G28")
        self.assertEqual(head(b)["offset"], {"X": 0.5, "Y": 0.0, "Z": 0.0})
        clear(b)
        self.assertEqual(state(b)["switches"], ZERO)
        self.assertEqual(head(b)["offset"]["X"], 0.5)  # clear moves nothing; the next home finds the true switch
        b.send("G28")
        self.assertEqual(head(b)["offset"], ZERO)

    def test_a_new_value_replaces_the_old_one_per_axis(self):
        b = Bench(park=PARK)
        fault(b, fault="home_offset", x=0.5, y=0.2)
        fault(b, fault="home_offset", x=-0.1)
        self.assertEqual(state(b)["switches"], {"X": -0.1, "Y": 0.2, "Z": 0.0})
        b.send("G28")
        self.assertEqual(head(b)["offset"], {"X": -0.1, "Y": 0.2, "Z": 0.0})

    def test_g28_x_alone_lands_off_in_x_only(self):
        b = homed()
        fault(b, fault="home_offset", x=0.5, y=0.3)
        b.send("G1 X50 Y50 F12000")
        b.send("G28 X")
        self.assertEqual(head(b)["offset"], {"X": 0.5, "Y": 0.0, "Z": 0.0})


class LostSteps(unittest.TestCase):
    def test_the_head_moves_with_no_command_and_m114_does_not_change(self):
        b = homed()
        b.send("G1 X100 Y100 F12000")
        b.send("M400")
        before = b.send("M114").lines
        said = len(b.rx)
        fault(b, fault="lost_steps", x=0.3, y=-0.1)
        b.run(1.0)
        self.assertEqual(len(b.rx), said)  # the controller says nothing
        self.assertEqual(b.send("M114").lines, before)
        h = head(b)
        self.assertAlmostEqual(h["physical"]["X"], 100.3, places=6)
        self.assertAlmostEqual(h["physical"]["Y"], 99.9, places=6)
        self.assertEqual(h["logical"], {"X": 100.0, "Y": 100.0, "Z": 31.5})
        self.assertEqual(h["offset"], {"X": 0.3, "Y": -0.1, "Z": 0.0})

    def test_later_moves_carry_it_and_a_home_removes_it(self):
        b = homed()
        fault(b, fault="lost_steps", x=0.3)
        fault(b, fault="lost_steps", x=0.2)  # they add up
        b.send("G1 X150 F12000")
        b.send("M400")
        self.assertAlmostEqual(head(b)["physical"]["X"], 150.5, places=6)
        b.send("G28")
        self.assertEqual(head(b)["offset"], ZERO)

    def test_steps_lost_during_a_move(self):
        b = homed()
        b.send("G1 X100 F600")
        b.run(3.0)
        fault(b, fault="lost_steps", y=0.25)
        b.send("M400")
        self.assertEqual(head(b)["physical"], {"X": 100.0, "Y": 0.25, "Z": 31.5})


class ZShift(unittest.TestCase):
    def test_the_whole_z_frame_moves_now_and_a_home_keeps_it(self):
        b = homed()
        before = b.send("M114").lines
        fault(b, fault="z_shift", z=0.1)
        self.assertEqual(b.send("M114").lines, before)
        self.assertAlmostEqual(head(b)["physical"]["Z"], 31.6, places=6)
        self.assertEqual(head(b)["offset"], {"X": 0.0, "Y": 0.0, "Z": 0.1})
        b.send("G1 Z20 F600")
        b.send("M400")
        self.assertAlmostEqual(head(b)["physical"]["Z"], 20.1, places=6)
        b.send("G28")
        self.assertEqual(b.send("M114").lines, before)
        self.assertEqual(head(b)["offset"], {"X": 0.0, "Y": 0.0, "Z": 0.1})
        self.assertAlmostEqual(state(b)["switches"]["Z"], 0.1, places=9)

    def test_shifts_add_up_and_clear_puts_the_switch_back(self):
        b = homed()
        fault(b, fault="z_shift", z=0.1)
        fault(b, fault="z_shift", z=-0.3)
        self.assertEqual(head(b)["offset"]["Z"], -0.2)
        clear(b)
        self.assertEqual(head(b)["offset"]["Z"], -0.2)
        b.send("G28")
        self.assertEqual(head(b)["offset"], ZERO)


class ZRelease(unittest.TestCase):
    def test_no_drop_by_default(self):
        b = homed()
        b.send("M18 Z")
        self.assertFalse(state(b)["enabled"]["Z"])
        self.assertEqual(head(b)["offset"], ZERO)

    def test_m18_z_releases_z_and_the_head_drops(self):
        b = homed()
        self.assertTrue(state(b)["enabled"]["Z"])
        fault(b, fault="z_drop", z=-0.4)
        self.assertEqual(state(b)["z_drop"], -0.4)
        self.assertEqual(head(b)["physical"]["Z"], 31.5)  # setting the drop moves nothing
        before = b.send("M114").lines
        self.assertEqual(b.send("M18 Z").lines, ["ok"])
        st = state(b)
        self.assertEqual(st["enabled"], {"X": True, "Y": True, "Z": False, "A": False, "B": False})
        self.assertEqual(st["head"]["offset"], {"X": 0.0, "Y": 0.0, "Z": -0.4})
        self.assertEqual(b.send("M114").lines, before)
        b.send("M18 Z")  # already released: no second drop
        b.send("G4 P1000")
        self.assertAlmostEqual(head(b)["physical"]["Z"], 31.1, places=6)

    def test_the_next_z_move_reenergises_z_where_the_head_fell(self):
        b = homed(z_drop={"z": -0.4})
        b.send("M18 Z")
        b.send("G1 X10 F6000")
        b.send("G1 Z31.5 F600")  # no Z steps: Z stays released (planner.cpp enables only axes with steps)
        b.send("M400")
        self.assertFalse(state(b)["enabled"]["Z"])
        b.send("G1 Z30 F600")
        self.assertTrue(state(b)["enabled"]["Z"])  # at once, when the block is planned (Z_LATE_ENABLE is off)
        b.send("M400")
        self.assertAlmostEqual(head(b)["physical"]["Z"], 29.6, places=6)
        self.assertEqual(head(b)["offset"]["Z"], -0.4)
        b.send("M18 Z")  # a second release drops again
        self.assertEqual(head(b)["offset"]["Z"], -0.8)

    def test_m17_reenergises_without_moving(self):
        b = homed(z_drop={"z": -0.4})
        b.send("M18 Z")
        b.send("M17 Z")
        self.assertTrue(state(b)["enabled"]["Z"])
        self.assertEqual(head(b)["offset"]["Z"], -0.4)

    def test_every_release_of_z_drops_it_and_only_those(self):
        for line, dropped in (("M18", True), ("M84", True), ("M84 Z", True), ("M18 X Y Z", True), ("M18 X", False),
                              ("M84 B", False)):
            b = homed(z_drop={"z": 0.25})
            b.send(line)
            self.assertEqual(head(b)["offset"]["Z"], 0.25 if dropped else 0.0, line)
            self.assertEqual(state(b)["enabled"]["Z"], not dropped, line)

    def test_the_release_waits_for_motion(self):
        b = homed(z_drop={"z": -0.4})
        b.send("G1 Z20 F600")  # 11.5 mm at 10 mm/s
        r = b.send("M18 Z")
        self.assertGreater(r.ok_at, 1.1)
        self.assertAlmostEqual(head(b)["physical"]["Z"], 19.6, places=6)

    def test_a_power_cycle_releases_z(self):
        b = homed(z_drop={"z": -0.4})
        b.sim.power_cycle(b.t)
        self.assertEqual(b.send("M114").lines[0],
                         "X:0.00 Y:0.00 Z:0.00 A:720.00 B:720.00 Count X:0 Y:0 Z:0 A:3197 B:3197")
        self.assertAlmostEqual(head(b)["physical"]["Z"], 31.1, places=6)
        b.sim.power_cycle(b.t)  # Z was never energised in this boot: nothing more falls
        self.assertAlmostEqual(head(b)["physical"]["Z"], 31.1, places=6)

    def test_m112_releases_z(self):
        b = homed(z_drop={"z": -0.4})
        b.write("M112\n")
        b.run(1.0)
        self.assertTrue(state(b)["halted"])
        self.assertAlmostEqual(head(b)["physical"]["Z"], 31.1, places=6)

    def test_a_home_after_the_drop_puts_z_back(self):
        b = homed(z_drop={"z": -0.4})
        b.send("M18 Z")
        b.send("G4 P100")
        b.send("G28")
        self.assertEqual(head(b)["offset"], ZERO)
        self.assertTrue(state(b)["enabled"]["Z"])


class IdleTimeout(unittest.TestCase):
    """manage_inactivity(): DEFAULT_STEPPER_TIMEOUT_SEC 3600 and DISABLE_IDLE_X/Y/Z/I/J in the stock config."""

    ALL_ON = {"X": True, "Y": True, "Z": True, "A": True, "B": True}
    ALL_OFF = {"X": False, "Y": False, "Z": False, "A": False, "B": False}

    def test_an_hour_after_the_last_move_every_axis_is_released(self):
        b = homed(z_drop={"z": -0.4})
        b.send("G1 A10 B800 F60000")
        b.send("M400")
        t_end = b.t
        said = len(b.rx)
        b.run_until(t_end + 3599.9)
        self.assertEqual(state(b)["enabled"], self.ALL_ON)
        b.run_until(t_end + 3600.1)
        self.assertEqual(state(b)["enabled"], self.ALL_OFF)
        self.assertEqual(head(b)["offset"]["Z"], -0.4)
        self.assertEqual(len(b.rx), said)  # silently

    def test_m84_s0_is_recorded_and_never_releases(self):
        b = homed(z_drop={"z": -0.4})
        r = b.send("M84 S0")
        self.assertEqual(r.lines, ["ok"])
        self.assertLess(r.ok_at, 0.001)
        self.assertEqual(state(b)["stepper_timeout_s"], 0.0)
        b.run(10 * 3600.0)
        self.assertEqual(state(b)["enabled"], {"X": True, "Y": True, "Z": True, "A": False, "B": False})
        self.assertEqual(head(b)["offset"], ZERO)

    def test_m18_s0_is_the_same(self):
        b = homed()
        self.assertEqual(b.send("M18 S0").lines, ["ok"])
        self.assertEqual(state(b)["stepper_timeout_s"], 0.0)

    def test_m18_s_counts_from_the_end_of_the_last_move(self):
        b = homed()
        b.send("M18 S5")
        self.assertEqual(state(b)["stepper_timeout_s"], 5.0)
        t0 = b.t
        b.send("G1 X200 F6000")  # about 2 s
        b.send("M400")
        t_end = b.t
        self.assertGreater(t_end - t0, 2.0)
        b.run_until(t_end + 4.99)
        self.assertTrue(state(b)["enabled"]["X"])
        b.run_until(t_end + 5.01)
        self.assertFalse(state(b)["enabled"]["X"])

    def test_m84_s_restarts_the_timer(self):
        b = homed()
        b.send("M18 S5")
        t0 = b.t
        b.send("G4 P3000")
        b.send("M84 S5")
        b.run_until(t0 + 7.99)
        self.assertTrue(state(b)["enabled"]["X"])
        b.run_until(t0 + 8.01)
        self.assertFalse(state(b)["enabled"]["X"])

    def test_m84_s_neither_waits_nor_releases_and_queued_moves_hold_the_steppers(self):
        b = homed()
        b.send("M18 S5")
        b.send("G1 X200 F600")  # 20 s
        r = b.send("M84 S5")
        self.assertEqual(r.lines, ["ok"])
        self.assertLess(r.ok_at, 0.001)  # no planner.synchronize() with S
        self.assertTrue(state(b)["enabled"]["X"])
        b.run(10.0)
        self.assertTrue(state(b)["enabled"]["X"])
        b.send("M400")
        t_end = b.t
        b.run_until(t_end + 4.99)
        self.assertTrue(state(b)["enabled"]["X"])
        b.run_until(t_end + 5.01)
        self.assertFalse(state(b)["enabled"]["X"])

    def test_a_dwell_does_not_hold_the_steppers(self):
        b = homed(z_drop={"z": -0.4})
        b.send("M18 S2")
        r = b.send("G4 P5000")
        self.assertEqual(r.lines, ["echo:busy: processing", "echo:busy: processing", "ok"])
        self.assertFalse(state(b)["enabled"]["Z"])  # released inside the dwell, at 2 s
        self.assertEqual(head(b)["offset"]["Z"], -0.4)

    def test_released_steppers_are_not_released_again(self):
        b = homed(z_drop={"z": -0.4})
        b.send("M18 S1")
        b.run(2.0)
        b.send("M17")
        b.run(5.0)
        self.assertTrue(state(b)["enabled"]["Z"])  # M17 queues no block: the timer does not restart
        self.assertEqual(head(b)["offset"]["Z"], -0.4)
        b.send("G1 X5 F6000")
        b.send("M400")
        b.run(2.0)
        self.assertFalse(state(b)["enabled"]["Z"])  # a move re-arms it
        self.assertEqual(head(b)["offset"]["Z"], -0.8)

    def test_a_power_cycle_restores_the_default(self):
        b = homed()
        b.send("M84 S0")
        b.sim.power_cycle(b.t)
        self.assertEqual(state(b)["stepper_timeout_s"], 3600.0)

    def test_fractional_and_negative_seconds(self):
        b = Bench()
        b.send("M18 S0.25")
        self.assertEqual(state(b)["stepper_timeout_s"], 0.25)
        b.send("M18 S-5")  # float to unsigned on the STM32 saturates at 0: never
        self.assertEqual(state(b)["stepper_timeout_s"], 0.0)


class Requests(unittest.TestCase):
    BAD = [
        {"fault": "home_offset"},
        {"fault": "home_offset", "X": 0.5},
        {"fault": "home_offset", "x": "0.5"},
        {"fault": "home_offset", "x": True},
        {"fault": "home_offset", "x": float("nan")},
        {"fault": "home_offset", "z": 0.5},
        {"fault": "lost_steps"},
        {"fault": "lost_steps", "z": 0.1},
        {"fault": "lost_steps", "x": float("inf")},
        {"fault": "z_shift"},
        {"fault": "z_shift", "z": None},
        {"fault": "z_shift", "x": 0.1},
        {"fault": "z_drop"},
        {"fault": "z_drop", "z": [1]},
        {"fault": "z_drop", "z": -0.4, "after_s": 1},
    ]

    def test_bad_requests_change_nothing(self):
        for spec in self.BAD:
            b = Bench(park=PARK)
            before = state(b)
            reply = b.sim.control(dict(cmd="fault", **spec), b.t)
            self.assertFalse(reply["ok"], spec)
            self.assertIn("error", reply)
            after = state(b)
            for key in ("head", "switches", "z_drop", "physical"):
                self.assertEqual(after[key], before[key], spec)

    def test_physical_faults_survive_a_power_cycle_and_are_not_ok_faults(self):
        b = Bench(park=PARK)
        fault(b, fault="home_offset", x=0.5)
        fault(b, fault="z_drop", z=-0.4)
        self.assertEqual(state(b)["faults"], [])
        b.sim.power_cycle(b.t)
        self.assertEqual(state(b)["switches"]["X"], 0.5)
        self.assertEqual(state(b)["z_drop"], -0.4)


if __name__ == "__main__":
    unittest.main()
