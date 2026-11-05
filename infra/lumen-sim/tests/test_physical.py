"""INF-08: the physical head apart from the controller's belief (fake clock).

Home offset, lost steps, a Z shift of the whole frame, Z release with a drop, and the M18/M84 S idle timeout. The
controller never sees these: M114 keeps its belief; the control state's `head` shows physical, logical and offset.
"""
import random
import re
import unittest

from lumen_sim.bench import Bench
from lumen_sim.core import Simulator
from tests.hang import within

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


def run_until_phase(b, phase, limit=30.0, step=0.005):
    """Advance until a move of the G28 phase `phase` is running."""
    end = b.t + limit
    while b.t < end:
        st = state(b)
        if st["homing_phase"] == phase and st["moving"]:
            return
        b.run(step)
    raise AssertionError("phase %s not reached" % phase)


VAC_READ = "M261 A109 B3 S1"


def n1_reading(b):
    m = re.search(r"data:([0-9A-F]{2}) ([0-9A-F]{2}) ([0-9A-F]{2})$", b.send(VAC_READ).lines[0])
    raw = int("".join(m.groups()), 16)
    return raw - (1 << 24) if raw & 0x800000 else raw


def touch(b, start=6.0, step=0.025, floor=4.0):
    """An N1 vacuum touch as the backend's prober does it: pump and valve on, open-air baseline, then down in steps
    until the reading falls 200 k counts below it. The N1 Z of the first sealed step, or None."""
    for line in ("M106", "M106 P1 S180", "M260 A112 B1 S1", "M260 A109 B6 S1"):
        b.send(line)
    b.send("G1 Z%.4f F600" % (start + 1.0))
    b.send("M400")
    base = n1_reading(b)
    found = None
    for k in range(int(round((start - floor) / step)) + 1):
        z = round(start - k * step, 4)
        b.send("G1 Z%.4f F600" % z)
        b.send("M400")
        if n1_reading(b) < base - 200_000:
            found = z
            break
    b.send("G1 Z%.4f F600" % (start + 1.0))
    b.send("M400")
    b.send("M107 P1")
    b.send("M107 P0")
    return found


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


class FaultsDuringHoming(unittest.TestCase):
    """A physical fault that lands while a homing move runs toward a switch: the switch stops the head where it now
    triggers, as a real switch would."""

    def home_x_with_fault_in_x_slow(self, **spec):
        b = Bench(park=PARK)
        b.write("G28 X\n")
        run_until_phase(b, "X_SLOW")
        b.run(0.05)  # about half way through the 10 mm re-bump
        fault(b, **spec)
        b.run(5.0)
        self.assertEqual(state(b)["homing_phase"], None)
        self.assertTrue(state(b)["homed"]["X"])
        return b

    def test_lost_steps_away_from_the_switch_mid_bump_still_ends_on_the_switch(self):
        b = self.home_x_with_fault_in_x_slow(fault="lost_steps", x=0.3)
        self.assertEqual(head(b)["physical"]["X"], 0.0)
        self.assertEqual(head(b)["offset"]["X"], 0.0)
        self.assertIn("x_min: TRIGGERED", b.send("M119").lines)

    def test_lost_steps_toward_the_switch_mid_bump_stops_at_the_switch(self):
        b = self.home_x_with_fault_in_x_slow(fault="lost_steps", x=-0.3)
        self.assertEqual(head(b)["physical"]["X"], 0.0)
        self.assertIn("x_min: TRIGGERED", b.send("M119").lines)

    def test_a_switch_moved_mid_bump_stops_the_head_at_its_new_place(self):
        b = self.home_x_with_fault_in_x_slow(fault="home_offset", x=0.5)
        self.assertEqual(head(b)["physical"]["X"], 0.5)
        self.assertEqual(head(b)["offset"]["X"], 0.5)
        self.assertIn("x_min: TRIGGERED", b.send("M119").lines)

    def test_clear_mid_bump_puts_the_switch_back_under_the_running_home(self):
        """Review round 3: clear moves the switches back to 0 like a fault moves them, so a home in progress ends on
        the true switch, not where the cleared home_offset had put it."""
        for x in (0.5, -0.5):
            b = Bench(park=PARK)
            fault(b, fault="home_offset", x=x)
            b.write("G28 X\n")
            run_until_phase(b, "X_SLOW")
            b.run(0.05)
            clear(b)
            b.run(5.0)
            self.assertEqual(state(b)["homing_phase"], None, x)
            self.assertEqual(state(b)["switches"]["X"], 0.0, x)
            self.assertEqual(head(b)["physical"]["X"], 0.0, x)
            self.assertEqual(head(b)["offset"]["X"], 0.0, x)
            self.assertIn("x_min: TRIGGERED", b.send("M119").lines)

    def test_the_ok_comes_when_the_head_reaches_the_switch(self):
        """The G28's report and ok come at the switch hit worked out again, not at the old one: later or sooner."""
        times = []
        for x in (0.0, 0.3, -0.3):
            b = Bench(park=PARK)
            b.write("G28 X\n")
            run_until_phase(b, "X_SLOW")
            b.run(0.05)
            fault(b, fault="lost_steps", x=x)
            b.run(5.0)
            times.append(next(t for t, line in b.rx if line == "ok"))
        # 0.3 mm more or less at the re-bump's 75 mm/s
        self.assertAlmostEqual(times[1] - times[0], 0.3 / 75.0, delta=0.0005)
        self.assertAlmostEqual(times[2] - times[0], -0.3 / 75.0, delta=0.0005)


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


    def test_clear_ends_the_drop(self):
        b = homed(z_drop={"z": -0.4})
        clear(b)
        self.assertEqual(state(b)["z_drop"], 0.0)
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
        with within(self, 5):
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

    def test_a_timeout_of_2_to_the_31_ms_or_more_has_run_out_at_once(self):
        """millis_t compares are signed 32-bit (PENDING/ELAPSED, core/millis_t.h:32-33): previous_move_ms + S
        lies 'in the past' when S is 2^31 ms or more, so manage_inactivity() releases every axis on its next pass,
        and again each time the planner runs empty."""
        for s in ("2147484", "3000000", "9999999999"):  # the last saturates at 0xFFFFFFFF ms
            b = homed(z_drop={"z": -0.4})
            self.assertEqual(b.send("M18 S" + s).lines, ["ok"])
            b.run(0.01)
            self.assertEqual(state(b)["enabled"], self.ALL_OFF, s)
            self.assertEqual(head(b)["offset"]["Z"], -0.4, s)
            b.send("G1 Z20 F600")
            b.send("M400")
            b.run(0.01)
            self.assertEqual(state(b)["enabled"], self.ALL_OFF, s)
            self.assertEqual(head(b)["offset"]["Z"], -0.8, s)

    def test_just_under_2_to_the_31_ms_waits(self):
        b = homed()
        b.send("M84 S2147483")  # 2147483000 ms < 2^31
        b.run(3600.0)
        self.assertTrue(state(b)["enabled"]["Z"])

    def test_a_power_cycle_restores_the_default(self):
        b = homed()
        b.send("M84 S0")
        b.sim.power_cycle(b.t)
        self.assertEqual(state(b)["stepper_timeout_s"], 3600.0)

    # The idle deadline is previous_move + T/1000; at many non-round times (p + T/1000 - p) * 1000 < T in floating
    # point. A wake at the deadline must still see the timeout run out, or the firmware loops at that instant for
    # ever and the process ignores SIGTERM (review round 2).
    def test_m84_s1_at_a_non_round_time_releases_a_second_later(self):
        b = Bench()
        b.send("M17")
        b.run_until(3.1011751469749993)
        b.send("M84 S1")
        with within(self, 5):
            b.run(5.0)
        self.assertEqual(state(b)["enabled"], self.ALL_OFF)
        self.assertFalse(state(b)["halted"])

    def test_m18_s0_339_releases_at_0_839(self):
        b = Bench()
        b.send("M17")
        b.run_until(0.5)
        b.send("M18 S0.339")
        with within(self, 5):
            b.run_until(0.8389)
            self.assertEqual(state(b)["enabled"], self.ALL_ON)
            b.run_until(1.0)
        self.assertEqual(state(b)["enabled"], self.ALL_OFF)

    def test_the_default_hour_after_a_move_that_ends_late(self):
        b = Bench()
        b.run_until(777.7)
        b.send("G1 X10 F6000")
        b.send("M400")
        t_end = b.t
        with within(self, 5):
            b.run_until(t_end + 3599.9)
            self.assertTrue(state(b)["enabled"]["X"])
            b.run_until(t_end + 3700.0)
        self.assertEqual(state(b)["enabled"], self.ALL_OFF)

    def test_the_release_lands_on_its_deadline_at_any_time(self):
        """The timer starts at power-on: boot at many times (the bad ones are ~5-19 % past 500 s) and wait."""
        rng = random.Random(2026)
        times = [7887.233511355132] + [rng.uniform(500.0, 50000.0) for _ in range(300)]
        with within(self, 30):
            for t0 in times:
                sim = Simulator(t0=t0)
                sim.receive(b"M17\n", t0)
                sim.run_until(t0 + 3599.999)
                self.assertTrue(sim.fw.enabled["Z"], t0)
                sim.run_until(t0 + 3600.001)
                self.assertEqual(sim.fw.enabled, self.ALL_OFF, t0)
                self.assertFalse(sim.fw.halted, (t0, sim.fw.halt_reason))

    def test_fractional_and_negative_seconds(self):
        b = Bench()
        b.send("M18 S0.25")
        self.assertEqual(state(b)["stepper_timeout_s"], 0.25)
        b.send("M18 S-5")  # float to unsigned on the STM32 saturates at 0: never
        self.assertEqual(state(b)["stepper_timeout_s"], 0.0)


class NoProgress(unittest.TestCase):
    """A model bug that leaves a wait due 'now' for ever must not spin the process (it would ignore SIGTERM and burn a
    core): the board halts with a sim error instead, and the process stays up."""

    def stuck(self):
        b = Bench()
        fw = b.sim.fw
        fw._idle_timeout_change = lambda: fw.now  # due now, and nothing ever acts on it
        return b

    def test_idle_with_an_empty_ring(self):
        b = self.stuck()
        with within(self, 5):
            r = b.send("M400")
            b.run(1.0)
        self.assertEqual(r.lines, ["ok"])
        st = state(b)
        self.assertTrue(st["halted"])
        self.assertRegex(st["halt_reason"], r"^sim error: RuntimeError: no progress")
        self.assertEqual(len(st["sim_errors"]), 1)

    def test_inside_a_command_s_wait(self):
        b = self.stuck()
        with within(self, 5):
            r = b.send("G4 P1000")
            b.run(1.0)
        self.assertEqual(r.lines, ["ok"])  # the line is acked as a model error, then the idle loop halts
        st = state(b)
        self.assertTrue(st["halted"])
        self.assertEqual([e["line"] for e in st["sim_errors"]], ["G4 P1000", None])


class NozzleTouch(unittest.TestCase):
    """The N1 vacuum reading follows the physical head: with a surface set, N1 seals when its tip reaches it (pump
    P0 and valve P1 on), so a Z fault is read by the next touch, as the backend's Z check and release test do."""

    SURFACE = 5.3

    def surfaced(self, **faults):
        b = homed(**faults)
        self.assertTrue(b.sim.control({"cmd": "set", "surface_z": self.SURFACE}, b.t)["ok"])
        return b

    def test_without_a_surface_n1_reads_open_air_at_every_z(self):
        b = homed()
        self.assertIsNone(touch(b))
        self.assertIsNone(state(b)["surface_z"])

    def test_n1_seals_at_the_surface(self):
        b = self.surfaced()
        self.assertEqual(touch(b), self.SURFACE)
        self.assertEqual(state(b)["surface_z"], self.SURFACE)

    def test_the_seal_needs_the_pump_and_the_valve(self):
        b = self.surfaced()
        b.send("G1 Z5 F600")
        b.send("M400")
        b.send("M106")
        open_air = n1_reading(b)
        b.send("M106 P1 S180")
        sealed = n1_reading(b)
        self.assertTrue(state(b)["n1_sealed"])
        self.assertEqual(open_air - sealed, state(b)["seal_drop"])
        b.send("M107 P0")
        self.assertEqual(n1_reading(b), open_air)
        self.assertFalse(state(b)["n1_sealed"])

    def test_a_z_shift_moves_the_contact_after_a_home(self):
        """BR-10: shift Z by 0.1 mm, home, and the touch reads 0.1 mm lower."""
        b = self.surfaced()
        self.assertEqual(touch(b), self.SURFACE)
        fault(b, fault="z_shift", z=0.1)
        b.send("G28")
        self.assertEqual(touch(b), round(self.SURFACE - 0.1, 4))

    def test_a_z_drop_is_read_by_the_next_touch(self):
        """BR-12: Z released, the head drops 0.4 mm, and the next touch reads 0.4 mm higher."""
        b = self.surfaced(z_drop={"z": -0.4})
        b.send("M18 Z")
        self.assertEqual(touch(b), round(self.SURFACE + 0.4, 4))

    def test_the_surface_outlives_a_power_cycle_and_can_be_removed(self):
        b = self.surfaced()
        b.sim.power_cycle(b.t)
        self.assertEqual(state(b)["surface_z"], self.SURFACE)
        self.assertTrue(b.sim.control({"cmd": "set", "surface_z": None}, b.t)["ok"])
        b.send("G28")
        self.assertIsNone(touch(b))

    BLANK = {"x0": 150.0, "y0": 100.0, "x1": 250.0, "y1": 200.0}

    def at(self, b, x, y):
        b.send("G1 X%g Y%g F6000" % (x, y))
        b.send("M400")

    def test_a_surface_region_moves_alone(self):
        """BR-10 'surface-moved': the blank is 0.3 mm higher, the fixed reference is not; only touches over the blank
        read the change."""
        b = self.surfaced()
        reply = b.sim.control({"cmd": "set", "surfaces": [dict(self.BLANK, z=self.SURFACE + 0.3)]}, b.t)
        self.assertTrue(reply["ok"], reply)
        self.assertEqual(state(b)["surfaces"], [dict(self.BLANK, z=self.SURFACE + 0.3)])
        self.at(b, 50, 50)
        self.assertEqual(touch(b), self.SURFACE)  # the fixed reference: surface_z
        self.at(b, 200, 150)
        self.assertEqual(touch(b), round(self.SURFACE + 0.3, 4))
        self.at(b, 250, 200)  # edges belong to the region
        self.assertEqual(touch(b), round(self.SURFACE + 0.3, 4))

    def test_regions_follow_a_z_shift_and_the_first_listed_wins(self):
        b = self.surfaced()
        regions = [dict(self.BLANK, z=5.6), {"x0": 0, "y0": 0, "x1": 400, "y1": 400, "z": 4.8}]
        self.assertTrue(b.sim.control({"cmd": "set", "surfaces": regions}, b.t)["ok"])
        self.at(b, 200, 150)
        self.assertEqual(touch(b), 5.6)
        self.at(b, 50, 50)
        self.assertEqual(touch(b), 4.8)
        fault(b, fault="z_shift", z=0.1)  # 'z-moved': every reference reads it
        b.send("G28")
        self.at(b, 200, 150)
        self.assertEqual(touch(b), 5.5)
        self.at(b, 50, 50)
        self.assertEqual(touch(b), 4.7)

    def test_regions_alone_leave_open_air_elsewhere_and_can_be_removed(self):
        b = homed()
        self.assertTrue(b.sim.control({"cmd": "set", "surfaces": [dict(self.BLANK, z=5.3)]}, b.t)["ok"])
        self.at(b, 50, 50)
        self.assertIsNone(touch(b))
        self.at(b, 200, 150)
        self.assertEqual(touch(b), 5.3)
        b.sim.power_cycle(b.t)  # the machine's: kept
        self.assertEqual(len(state(b)["surfaces"]), 1)
        self.assertTrue(b.sim.control({"cmd": "set", "surfaces": None}, b.t)["ok"])
        self.assertEqual(state(b)["surfaces"], [])
        b.send("G28")
        self.at(b, 200, 150)
        self.assertIsNone(touch(b))

    def test_bad_regions_are_refused(self):
        good = dict(self.BLANK, z=5.3)
        for surfaces in ({"x0": 0}, [good, "x"], [dict(good, z="5")], [dict(good, z=float("nan"))],
                         [dict(good, x0=300.0)], [dict(good, y1=50.0)], [{k: v for k, v in good.items() if k != "z"}],
                         [dict(good, Z=5.0)], [dict(good, x0=True)]):
            b = Bench()
            reply = b.sim.control({"cmd": "set", "surfaces": surfaces}, b.t)
            self.assertFalse(reply["ok"], surfaces)
            self.assertEqual(state(b)["surfaces"], [], surfaces)

    def test_bad_values_are_refused(self):
        for req in ({"surface_z": "5.3"}, {"surface_z": float("nan")}, {"surface_z": True},
                    {"seal_drop": -1}, {"seal_drop": 1.5}, {"seal_drop": "x"}):
            b = Bench()
            reply = b.sim.control(dict(cmd="set", **req), b.t)
            self.assertFalse(reply["ok"], req)
            self.assertIsNone(state(b)["surface_z"], req)
            self.assertEqual(state(b)["seal_drop"], 329_000, req)


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
