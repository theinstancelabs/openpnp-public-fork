"""The limit switch on PA5 (the user, 11-04): N1 and the needle press it to measure the needle's Z directly; M43 P5
reads it. N1 presses it as Z falls, the needle as Z rises (the see-saw). Each press trips at its own point (a repeat
noise), and a press past the switch's overtravel is reported in sim_errors.
"""
import re
import statistics
import unittest

from lumen_sim import machine
from lumen_sim.bench import Bench
from tests.hang import within

# The real board's answer to M43 P5 (runs/serial.log, 11-02, nothing wired: 0), byte for byte
PA5_LINE = "PIN: PA5  (A9)   M42 P53          <unused/unknown>                       Input  = %d"
PARK = (100.0, 50.0, 31.5)
OVER_06 = "PA5 switch overtravel: n1 0.60 mm past its trip point (overtravel 0.50 mm)"
N1 = {"tool": "n1", "x0": 99.0, "y0": 49.0, "x1": 101.0, "y1": 51.0, "trip_z": 12.0}
NEEDLE = {"tool": "needle", "x0": 199.0, "y0": 49.0, "x1": 201.0, "y1": 51.0, "trip_z": 52.0}


def homed(**kw):
    b = Bench(park=PARK, **kw)
    b.send("G28")
    return b


def switch(b, **kw):
    spec = dict({"presses": [N1, NEEDLE], "overtravel_mm": 0.5, "repeat_sd_mm": 0.0, "pressed_level": 1, "seed": 0},
                **kw)
    reply = b.sim.control({"cmd": "set", "pa5_switch": spec}, b.t)
    assert reply["ok"], reply
    return b


def to(b, x=None, y=None, z=None):
    b.send("G1" + "".join(" %s%s" % (a, v) for a, v in zip("XYZ", (x, y, z)) if v is not None) + " F3000")
    b.send("M400")


def level(b):
    """M43 P5 after the moves end, as the engine reads it: the pin's level."""
    lines = b.send("M43 P5").lines
    assert lines[-1] == "ok" and len(lines) == 2, lines
    m = re.fullmatch(re.escape(PA5_LINE[:-2]) + r"(\d)", lines[0])
    assert m, lines[0]
    return int(m.group(1))


def pa5(b):
    return b.sim.state(b.t)["pa5"]


def errors(b):
    return [e["error"] for e in b.sim.state(b.t)["sim_errors"]]


def errors_since(b, t0):
    """Each error with its time after t0 (s, to 0.01)."""
    return [(round(e["t"] - t0, 2), e["error"]) for e in b.sim.state(b.t)["sim_errors"]]


class Pa5Switch(unittest.TestCase):
    def test_without_a_switch_it_reads_as_the_board_did(self):
        b = homed()
        self.assertEqual(b.send("M43 P5").lines, [PA5_LINE % 0, "ok"])
        self.assertIsNone(pa5(b))

    def test_n1_trips_it_as_z_falls_to_the_trip(self):
        b = switch(homed())
        to(b, 100, 50, 12.1)
        self.assertEqual(level(b), 0)
        to(b, z=12.0)
        self.assertEqual(level(b), 1)
        self.assertEqual(pa5(b)["tool"], "n1")
        to(b, z=13)
        self.assertEqual(level(b), 0, "released on the way back up")

    def test_the_needle_trips_it_as_z_rises_to_the_trip(self):
        b = switch(homed())
        to(b, 200, 50, 51.9)
        self.assertEqual(level(b), 0)
        to(b, z=52.0)
        self.assertEqual(level(b), 1)
        self.assertEqual(pa5(b)["tool"], "needle")
        to(b, z=40)
        self.assertEqual(level(b), 0)

    def test_nothing_presses_it_outside_the_windows(self):
        b = switch(homed())
        to(b, 150, 50, 5)
        self.assertEqual(level(b), 0)
        to(b, z=60)
        self.assertEqual(level(b), 0)
        self.assertEqual(errors(b), [])

    def test_a_switch_that_pulls_the_pin_low(self):
        b = switch(homed(), pressed_level=0)
        to(b, 100, 50, 13)
        self.assertEqual(level(b), 1)
        to(b, z=12)
        self.assertEqual(level(b), 0)

    def test_the_read_does_not_wait_for_the_move(self):
        """M43 reads the pin at once: a read right behind a move sees the head where it is. The engine sends M400."""
        b = switch(homed())
        to(b, 100, 50, 13)
        b.send("G1 Z12 F6")  # 0.1 mm/s: 10 s down to the trip
        self.assertEqual(b.send("M43 P5").lines, [PA5_LINE % 0, "ok"])
        b.send("M400")
        self.assertEqual(level(b), 1)

    def test_each_press_trips_at_its_own_point(self):
        """Repeat noise: a new trip point per press, trip_z + N(0, repeat_sd_mm), the same for the same seed."""
        def trips(seed):
            b = switch(homed(), repeat_sd_mm=0.01, seed=seed)
            to(b, 100, 50, 13)
            out = []
            for _ in range(40):
                to(b, z=11.9)
                out.append(pa5(b)["trip_z"])
                to(b, z=13)
            return out
        a = trips(7)
        self.assertEqual(trips(7), a, "reproducible")
        self.assertNotEqual(trips(8), a)
        self.assertEqual(len(set(a)), 40, "a new point every press")
        self.assertAlmostEqual(statistics.mean(a), 12.0, delta=0.006)
        self.assertGreater(statistics.stdev(a), 0.005)
        self.assertLess(statistics.stdev(a), 0.02)

    def test_a_press_holds_its_point_until_released(self):
        b = switch(homed(), repeat_sd_mm=0.01, seed=7)
        to(b, 100, 50, 11.9)
        trip = pa5(b)["trip_z"]
        to(b, z=11.95)
        to(b, z=11.9)
        self.assertEqual(pa5(b)["trip_z"], trip)

    def test_overtravel_is_reported_once_per_press(self):
        b = switch(homed())
        to(b, 100, 50, 11.5)
        self.assertEqual(errors(b), [], "0.5 mm past the trip: within the overtravel")
        to(b, z=11.475)
        self.assertEqual(errors(b), ["PA5 switch overtravel: n1 0.53 mm past its trip point (overtravel 0.50 mm)"])
        to(b, z=11.0)
        self.assertEqual(len(errors(b)), 1, "one per press")
        self.assertEqual(pa5(b)["depth_mm"], 1.0)
        to(b, z=13)
        to(b, z=11.0)
        self.assertEqual(len(errors(b)), 2, "a new press, a new report")
        self.assertEqual(pa5(b)["overtravels"], 2)

    def test_a_move_through_the_switch_is_caught_without_a_read(self):
        b = switch(homed())
        to(b, 100, 50, 5)
        self.assertEqual(errors(b), ["PA5 switch overtravel: n1 7.00 mm past its trip point (overtravel 0.50 mm)"])

    def test_the_needles_overtravel(self):
        b = switch(homed())
        to(b, 200, 50, 52.6)
        self.assertEqual(errors(b), ["PA5 switch overtravel: needle 0.60 mm past its trip point (overtravel 0.50 mm)"])

    def test_a_one_step_back_off_releases_it(self):
        """The route steps down until it reads pressed, then one step up: released, and it stays released while the head
        stands still (a new press needs a move toward the switch), whatever each press's own trip point."""
        for seed in range(40):
            with self.subTest(seed=seed):
                b = switch(homed(), repeat_sd_mm=0.01, seed=seed)
                to(b, 100, 50, 12.2)
                z = 12.2
                while level(b) == 0:
                    z = round(z - 0.025, 3)
                    to(b, z=z)
                to(b, z=round(z + 0.025, 3))
                self.assertEqual([level(b), level(b)], [0, 0])

    def test_after_a_back_off_and_a_lift_the_next_press_trips_at_its_own_point(self):
        """The release point follows the head as it moves away: back at the switch, a press whose own point lies
        behind the release point trips there, not a step late (the anchor once stayed: 9 of 40 seeds N1, 7 the
        needle, read one step deep)."""
        for tool, x, start, way in (("n1", 100, 12.2, -1), ("needle", 200, 51.8, 1)):
            for seed in range(40):
                with self.subTest(tool=tool, seed=seed):
                    b = switch(homed(), repeat_sd_mm=0.01, seed=seed)
                    to(b, x, 50, start)
                    z = start
                    while level(b) == 0:
                        z = round(z + way * 0.025, 3)
                        to(b, z=z)
                    to(b, z=round(z - way * 0.025, 3))  # one step back: released
                    to(b, z=round(start - way * 0.3, 3))  # lifted away
                    z = start
                    to(b, z=z)
                    while level(b) == 0:
                        z = round(z + way * 0.025, 3)
                        to(b, z=z)
                    self.assertLess(pa5(b)["depth_mm"], 0.025, "pressed at the first step past its own point")

    def test_leaving_the_window_clears_the_release_point(self):
        """Back in the window from the side, a press starts afresh where the head is."""
        sw = machine.Pa5Switch([N1])
        sw.observe(100, 50, 11.99)
        sw.observe(100, 50, 12.001)  # released there
        sw.offset = 0.01  # the next press's point, 12.01, behind the release point
        self.assertEqual(sw.observe(100, 50, 12.001)[0], 0, "standing still: no new press")
        sw.observe(95, 50, 12.001)
        self.assertEqual(sw.observe(100, 50, 12.001)[0], 1)

    def test_a_new_press_needs_a_move_toward_the_switch(self):
        b = switch(homed(), repeat_sd_mm=0.01, seed=1)
        to(b, 100, 50, 11.9)
        to(b, z=12.3)
        self.assertEqual(level(b), 0)
        to(b, z=11.9)
        self.assertEqual(level(b), 1)

    def test_the_read_sees_the_head_mid_move(self):
        b = switch(homed())
        to(b, 100, 50, 13)
        b.send("G1 Z11.5 F6")  # 0.1 mm/s
        b.run(12.0)            # 1.2 mm down: past the trip, the move not ended
        self.assertEqual(b.send("M43 P5").lines, [PA5_LINE % 1, "ok"])

    def test_leaving_the_window_releases_it(self):
        b = switch(homed())
        to(b, 100, 50, 11.9)
        to(b, x=95)
        self.assertEqual(level(b), 0)
        self.assertFalse(pa5(b)["pressed"])

    def test_a_move_deep_and_back_is_caught_at_the_moves_end(self):
        """No read anywhere: the stepper's end of each move sees the press (the state request is no read)."""
        b = switch(homed())
        b.send("G1 X100 Y50 F3000")
        b.send("G1 Z5 F3000")
        b.send("G1 Z31 F3000")
        b.send("M400")
        self.assertEqual(errors(b), ["PA5 switch overtravel: n1 7.00 mm past its trip point (overtravel 0.50 mm)"])

    def test_the_state_request_is_no_read(self):
        """A state poll mid-move changes nothing: no press, no overtravel, no noise draw."""
        def run(polls):
            b = switch(homed(), repeat_sd_mm=0.01, seed=3)
            to(b, 95, 50, 5)
            b.send("G1 X105 F600")  # across N1's window at Z 5, ending outside it
            for _ in range(polls):
                b.run(0.3)
                pa5(b)
            b.send("M400")
            trips = []
            for _ in range(3):
                to(b, 100, 50, 13)
                to(b, z=11.9)
                trips.append(pa5(b)["trip_z"])
            return trips, errors(b)
        self.assertEqual(run(0), run(5))

    def test_a_z_drop_onto_the_switch_is_seen(self):
        b = switch(homed())
        to(b, 100, 50, 11.8)
        self.assertEqual(b.sim.control({"cmd": "fault", "fault": "z_drop", "z": -0.4}, b.t)["ok"], True)
        b.send("M18 Z")
        self.assertEqual(errors(b), ["PA5 switch overtravel: n1 0.60 mm past its trip point (overtravel 0.50 mm)"])

    def test_a_z_shift_onto_the_switch_is_seen(self):
        b = switch(homed())
        to(b, 100, 50, 11.9)
        self.assertEqual(b.sim.control({"cmd": "fault", "fault": "z_shift", "z": -0.6}, b.t)["ok"], True)
        self.assertEqual(errors(b), ["PA5 switch overtravel: n1 0.70 mm past its trip point (overtravel 0.50 mm)"])

    def test_a_fault_mid_move_sees_the_head_where_it_is(self):
        """A z_shift, a Z release after M112 (z_drop) and a home_offset while a move runs: the switch is read where
        the head is then, not where the move began."""
        for fault, then in (({"fault": "z_shift", "z": -0.4}, None), ({"fault": "z_drop", "z": -0.4}, "M112")):
            with self.subTest(fault["fault"]), within(self, 5):
                b = switch(homed())
                to(b, 100, 50, 11.8)  # pressed, 0.2 mm deep
                b.send("G1 Z20 F60")  # 1 mm/s up: off the switch within 0.2 s
                b.run(4.0)
                self.assertTrue(b.sim.control(dict(fault, cmd="fault"), b.t)["ok"])
                if then:
                    b.send(then)
                self.assertEqual(errors(b), [], "no overtravel: the head is ~4 mm above the switch")
                self.assertFalse(pa5(b)["pressed"])
        b = switch(homed())
        to(b, 100, 50, 13)
        b.send("G1 Z11.5 F6")  # 0.1 mm/s
        b.run(12.0)            # past the trip, the move not ended
        self.assertEqual(level(b), 1)
        self.assertTrue(b.sim.control({"cmd": "fault", "fault": "home_offset", "x": 0.1}, b.t)["ok"])
        self.assertTrue(pa5(b)["pressed"], "a fault that moves no head keeps the press")

    def test_a_kill_drops_z_after_its_coast(self):
        """Stock Marlin's minkill() turns the steppers off only after its 0.6 s coast: an M112 0.15 s into a move up
        off the switch drops Z where the coast ends, above the trip (nothing to report)."""
        with within(self, 5):
            b = switch(homed())
            to(b, 100, 50, 11.8)
            self.assertTrue(b.sim.control({"cmd": "fault", "fault": "z_drop", "z": -0.4}, b.t)["ok"])
            b.send("G1 Z20 F60")
            b.run(0.15)
            b.write("M112\n")
            b.run(1.0)
            self.assertEqual(errors(b), [])
            self.assertFalse(pa5(b)["pressed"])

    def test_a_kill_at_rest_drops_z_onto_the_switch(self):
        """minkill() lets the steppers go 0.85 s after the kill (cli() at 0.6 s, then 0.25 s): Z is held until then and
        drops onto the switch then. il-safety too: its kill()'s planner.quick_stop() ends the motion at once, not the
        drivers (il/safety MarlinCore.cpp kill(), minkill())."""
        for personality in ("stock", "il-safety"):
            with self.subTest(personality), within(self, 5):
                b = switch(homed(personality=personality))
                to(b, 100, 50, 11.8)
                self.assertTrue(b.sim.control({"cmd": "fault", "fault": "z_drop", "z": -0.4}, b.t)["ok"])
                t_kill = b.t
                b.write("M112\n")  # no ok comes: send would wait 60 s
                b.run(0.1)
                held = b.sim.state(b.t)
                self.assertEqual((held["head"]["physical"]["Z"], held["enabled"]["Z"], errors(b)), (11.8, True, []))
                b.run(1.0)
                self.assertEqual(errors_since(b, t_kill), [(0.85, OVER_06)])

    def test_a_kill_holds_z_through_its_coast(self):
        """minkill() turns the steppers off 0.25 s after its 0.6 s coast: until then Z is held. A move that ends in
        the coast went 0.4 mm into the switch, not 0.8; a read in the coast (a fault) sees Z where it is."""
        with within(self, 5):
            b = switch(homed())
            to(b, 100, 50, 12.3)
            self.assertTrue(b.sim.control({"cmd": "fault", "fault": "z_drop", "z": -0.4}, b.t)["ok"])
            b.send("G1 Z11.6 F600")
            b.send("G1 Z13 F600")
            b.run(0.005)
            b.write("M112\n")
            b.run(2.0)
            self.assertEqual(errors(b), [])
            b = switch(homed())
            to(b, 100, 50, 11.8)
            self.assertTrue(b.sim.control({"cmd": "fault", "fault": "z_drop", "z": -0.4}, b.t)["ok"])
            b.send("G1 Z20 F60")
            b.run(0.15)
            b.write("M112\n")
            b.run(0.01)
            self.assertTrue(b.sim.control({"cmd": "fault", "fault": "home_offset", "x": 0.1}, b.t)["ok"])
            b.run(1.0)
            self.assertEqual(errors(b), [])

    def test_a_move_that_ends_in_the_coast_is_seen_when_it_ends(self):
        """Stock minkill() spins 0.6 s with the stepper ISR on: a move that ends in that coast reaches the switch at its
        own end, as without the kill, not when the coast ends."""
        def run(kill):
            b = switch(homed())
            to(b, 100, 50, 12.3)
            t0 = b.t
            b.send("G1 Z11.4 F600")  # 0.6 mm past the trip
            if kill:
                b.write("M112\n")
            b.run(2.0)
            return errors_since(b, t0)
        with within(self, 5):
            self.assertEqual(run(kill=False), [(0.19, OVER_06)])
            self.assertEqual(run(kill=True), [(0.19, OVER_06)])

    def test_a_kills_drop_is_seen_when_it_happens_whoever_looks(self):
        """The steppers go off at the kill + 0.85 s (stock) whether or not anything reads the state meanwhile."""
        def run(poll):
            b = switch(homed())
            to(b, 100, 50, 11.8)
            self.assertTrue(b.sim.control({"cmd": "fault", "fault": "z_drop", "z": -0.4}, b.t)["ok"])
            b.write("M112\n")
            b.run(0.001)
            t_kill = b.t
            b.run(5.0)
            if poll:
                pa5(b)
            b.run(5.0)
            self.assertTrue(b.sim.control({"cmd": "fault", "fault": "z_shift", "z": 1.0}, b.t)["ok"])
            return errors_since(b, t_kill)
        with within(self, 5):
            self.assertEqual(run(poll=False), [(0.85, OVER_06)])
            self.assertEqual(run(poll=True), [(0.85, OVER_06)])

    def test_a_power_cycle_in_a_kills_window_drops_z_once(self):
        """Power off 0.3 s after an M112, while minkill() still holds Z: Z drops then, once (the dead board's
        disable_all_steppers() at 0.85 s never comes)."""
        with within(self, 5):
            b = switch(homed())
            to(b, 100, 50, 11.8)
            self.assertTrue(b.sim.control({"cmd": "fault", "fault": "z_drop", "z": -0.4}, b.t)["ok"])
            t_kill = b.t
            b.write("M112\n")
            b.run(0.3)
            self.assertTrue(b.sim.control({"cmd": "power_cycle"}, b.t)["ok"])
            b.run(2.0)
            self.assertEqual(errors_since(b, t_kill), [(0.3, OVER_06)])
            self.assertAlmostEqual(b.sim.state(b.t)["head"]["physical"]["Z"], 11.4, places=6)

    def test_a_kill_without_steppers_off_keeps_z_held(self):
        """validate_homing_move()'s kill("Homing Failed") passes no steppers_off (endstops.cpp:263; false by default,
        MarlinCore.h:41), so minkill() turns only the E steppers off: Z stays energised and does not drop. Stock: an
        M410 that cuts a move toward a switch."""
        with within(self, 5):
            b = homed()
            to(b, x=100)
            self.assertTrue(b.sim.control({"cmd": "fault", "fault": "z_drop", "z": -0.4}, b.t)["ok"])
            b.write("G28 X\n")
            b.run(0.2)         # X's approach toward its switch
            b.write("M410\n")  # on read: the approach ends short of the switch, and G28 kills
            b.run(3.0)
            st = b.sim.state(b.t)
            self.assertEqual((st["halt_reason"], st["enabled"]["Z"], st["head"]["physical"]["Z"]),
                             ("Homing Failed", True, 31.5))

    def test_another_tools_window_clears_the_release_point(self):
        """N1 released and lifted in its window to Z 53 (its release point follows), then the head moves into the
        needle's window at that height: the needle's own press, 1 mm past its trip at once."""
        b = switch(homed())
        to(b, 100, 50, 11.9)
        to(b, z=53)
        to(b, x=200)
        self.assertEqual(level(b), 1)
        self.assertEqual(errors(b), ["PA5 switch overtravel: needle 1.00 mm past its trip point (overtravel 0.50 mm)"])

    def test_the_state_is_right_once_the_switch_is_wired(self):
        b = homed()
        to(b, 100, 50, 11.9)
        switch(b)
        self.assertEqual((pa5(b)["level"], pa5(b)["pressed"], pa5(b)["depth_mm"]), (1, True, 0.1))

    def test_a_wired_switch_reads_its_own_levels_whatever_the_pull(self):
        """pressed_level and the other level are the switch's: M42's pull-up shows only with nothing wired."""
        b = switch(homed())
        to(b, 100, 50, 13)
        b.send("M42 P53 T2")
        self.assertEqual(level(b), 0)

    def test_the_pull_without_a_switch(self):
        """M42 P53 T2 (input pull-up) before the reads, as the route does: nothing wired reads 1; T3 pulls down, T0
        floats (0, as read 11-02); a power cycle resets the pin."""
        b = homed()
        for mode, expect in (("T2", 1), ("T3", 0), ("T0", 0), ("T2", 1)):
            b.send("M42 P53 " + mode)
            self.assertEqual(level(b), expect, mode)
        self.assertTrue(b.sim.control({"cmd": "power_cycle"}, b.t)["ok"])
        b.run(2.0)
        self.assertEqual(level(b), 0)

    def test_it_outlives_a_power_cycle_and_can_be_removed(self):
        b = switch(homed())
        self.assertTrue(b.sim.control({"cmd": "power_cycle"}, b.t)["ok"])
        b.run(2.0)
        self.assertEqual(pa5(b)["presses"], [N1, NEEDLE])
        self.assertTrue(b.sim.control({"cmd": "set", "pa5_switch": None}, b.t)["ok"])
        self.assertIsNone(pa5(b))

    def test_state(self):
        b = switch(homed())
        to(b, 100, 50, 11.9)
        self.assertEqual(pa5(b), {"presses": [N1, NEEDLE], "overtravel_mm": 0.5, "repeat_sd_mm": 0.0,
                                  "pressed_level": 1, "seed": 0, "level": 1, "pressed": True, "tool": "n1",
                                  "trip_z": 12.0, "depth_mm": 0.1, "overtravels": 0})

    def test_bad_settings_change_nothing(self):
        b = switch(homed())
        for bad in ({"presses": [dict(N1, tool="n2")]}, {"presses": [{"tool": "n1", "trip_z": 12}]},
                    {"presses": [dict(N1, x0=102.0)]}, {"presses": [dict(N1, trip_z="12")]},
                    {"overtravel_mm": -0.1}, {"repeat_sd_mm": float("nan")}, {"pressed_level": 2},
                    {"seed": 1.5}, {"presses": [dict(N1, extra=1)]}, {"overtravels": 3},
                    {"presses": [dict(N1, tool=["n1"])]}, {"presses": {"tool": "n1"}}, {"repeat_sd_mm": 2.0},
                    {"overtravel_mm": 11.0}):
            with self.subTest(bad):
                spec = dict({"presses": [N1], "overtravel_mm": 0.5, "repeat_sd_mm": 0.0, "pressed_level": 1,
                             "seed": 0}, **bad)
                reply = b.sim.control({"cmd": "set", "pa5_switch": spec}, b.t)
                self.assertFalse(reply["ok"], reply)
                self.assertRegex(reply["error"], r"^ValueError: (pa5_switch|a PA5 press|\w+ must be)", "a clean refusal")
                self.assertEqual(pa5(b)["presses"], [N1, NEEDLE])


if __name__ == "__main__":
    unittest.main()
