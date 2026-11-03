"""M410 quick-stop, STOP during homing, kill and power cycle (fake clock)."""
import re
import unittest

from lumen_sim.bench import Bench

REPORT = re.compile(r"^X:(-?\d+\.\d\d) Y:(-?\d+\.\d\d) Z:(-?\d+\.\d\d) A:(-?\d+\.\d\d) B:(-?\d+\.\d\d) "
                    r"Count X:(-?\d+) Y:(-?\d+) Z:(-?\d+) A:(-?\d+) B:(-?\d+)$")


def run_until_phase(b, phase, limit=30.0, step=0.01):
    """Advance until the G28 in progress is executing `phase` (a move of that phase has started)."""
    end = b.t + limit
    while b.t < end:
        st = b.sim.state(b.t)
        if st["homing_phase"] == phase and st["moving"]:
            return
        b.run(step)
    raise AssertionError("phase %s not reached" % phase)


class StockQuickStop(unittest.TestCase):
    def test_m410_recomputes_position_from_counts(self):
        b = Bench()
        self.assertAlmostEqual(b.send("M410").ok_at, 2.0, delta=0.001)
        self.assertEqual(b.send("M114").lines[0],
                         "X:0.00 Y:0.00 Z:0.00 A:720.05 B:720.05 Count X:0 Y:0 Z:0 A:3197 B:3197")

    def test_m410_stops_a_move_inside_m400(self):
        b = Bench()
        b.send("G1 X100 F600")  # 10 mm/s
        t0 = b.t
        b.write("M400\n")
        b.run(2.0)
        b.write("M410\n")  # a second thread writes while M400 waits (the web STOP)
        b.run(3.0)
        oks = [round(t - t0, 3) for t, line in b.rx if line == "ok" and t > t0]
        self.assertEqual(oks, [3.0, 4.0])  # M400 after the 1 s quick-stop wait, then the queued M410 after 1 s more
        x = float(REPORT.match(b.send("M114").lines[0]).group(1))
        self.assertAlmostEqual(x, 19.0, delta=0.05)  # (2.0 s - 0.1 s first-move delay) at 10 mm/s
        self.assertAlmostEqual(b.sim.state(b.t)["physical"][0], 5.0 + x, delta=0.01)

    def test_m410_inside_g4_keeps_the_dwell(self):
        b = Bench()
        t0 = b.t
        b.write("G4 P5000\n")
        b.run(1.0)
        b.write("M410\n")
        b.run(6.0)
        oks = [round(t - t0, 3) for t, line in b.rx if line == "ok"]
        self.assertEqual(oks, [5.0, 6.0])


class StockStopDuringHoming(unittest.TestCase):
    def _home_then_stop(self, **kw):
        b = Bench(park=(300.0, 300.0, 31.5), **kw)
        b.send("M106 P2 S255")
        b.send("M106 P3 S255")
        b.write("G28\n")
        t0 = b.t
        b.run(4.0)
        self.assertEqual(b.sim.state(b.t)["homing_phase"], "Y_APPROACH")
        b.write("M410\n")
        return b, t0

    def test_m410_in_an_approach_halts_the_board(self):
        b, t0 = self._home_then_stop()
        b.run(0.3)
        stopped = b.sim.state(b.t)["physical"]
        b.run(20.0)
        self.assertEqual([round(t - t0, 3) for t, l in b.rx if t > t0], [2.0, 4.0])  # G28's busy lines, then silence
        st = b.sim.state(b.t)
        self.assertTrue(st["halted"])
        self.assertEqual(st["halt_reason"], "Homing Failed")
        self.assertEqual(st["physical"], stopped)  # motion stopped at the M410
        self.assertEqual(st["fans"], [0, 0, 255, 255])  # outputs as they were
        b.write("M115\n")
        b.run(5.0)
        self.assertEqual([l for t, l in b.rx if t > t0 + 5.0], [])

    def test_power_cycle_recovers_a_halted_board(self):
        b, _ = self._home_then_stop()
        b.run(5.0)
        b.sim.power_cycle(b.t)
        self.assertEqual(b.send("M114").lines[0],
                         "X:0.00 Y:0.00 Z:0.00 A:720.00 B:720.00 Count X:0 Y:0 Z:0 A:3197 B:3197")
        st = b.sim.state(b.t)
        self.assertFalse(st["halted"])
        self.assertEqual(st["fans"], [0, 0, 0, 0])

    def test_kill_messages_knob(self):
        b, t0 = self._home_then_stop(kill_messages=True)
        b.run(5.0)
        lines = [(round(t - t0, 3), l) for t, l in b.rx if t > t0]
        self.assertEqual(lines, [(2.0, "echo:busy: processing"), (4.0, "echo:busy: processing"),
                                 (5.0, "echo:Homing Failed"), (5.0, "Error:Printer halted. kill() called!")])

    def test_a_stop_after_the_home_ended_is_a_plain_quick_stop(self):
        """Review round 3: from the start of serial.log line 168535 the board's G28 acks after 5.25 s, so the web STOP
        5.5 s in lands after it, acks, and the board stays alive (the sim's homing used to run 1.5 s long and kill)."""
        b = Bench(park=(75048 / 320, 63717 / 320, 31.5))
        b.send("M204 S1500")
        t0 = b.t
        b.write("G28\n")
        b.run(5.5)
        self.assertEqual([l for t, l in b.rx if t > t0 and l == "ok"], ["ok"])
        self.assertEqual(sum(1 for t, l in b.rx if t > t0 and "busy" in l), 2)
        b.write("M410\n")
        b.run(3.0)
        self.assertFalse(b.sim.state(b.t)["halted"])
        self.assertEqual(b.send("M115").lines[-1], "ok")

    def test_m410_in_the_z_after_move_is_a_plain_quick_stop(self):
        b = Bench(park=(20.0, 20.0, 31.5))
        b.write("G28\n")
        t0 = b.t
        run_until_phase(b, "Z_AFTER")
        b.run(0.05)
        b.write("M410\n")
        b.run(3.0)
        lines = [l for t, l in b.rx if t > t0 and "busy" not in l]
        self.assertEqual(len(lines), 3, lines)
        m = REPORT.match(lines[0])
        self.assertIsNotNone(m, lines[0])
        self.assertLess(float(m.group(3)), 31.5)  # Z stopped short of Z_AFTER_HOMING
        self.assertEqual(lines[1:], ["ok", "ok"])
        self.assertFalse(b.sim.state(b.t)["halted"])

    def test_m410_in_a_bump_away_move_does_not_kill(self):
        # Source-faithful: validate_homing_move() runs only after moves toward the switch; the re-bump that follows
        # still reaches the switch, so this G28 completes. (RESEARCH.md section 7 simplifies this to "any phase".)
        b = Bench(park=(20.0, 20.0, 31.5))
        b.write("G28\n")
        t0 = b.t
        run_until_phase(b, "X_BUMP")
        b.write("M410\n")
        b.run(8.0)
        lines = [l for t, l in b.rx if t > t0 and "busy" not in l]
        # A/B read 720.05: the quick-stop re-read every axis from its step count (3197 / 4.44)
        self.assertEqual(lines, ["X:0.00 Y:0.00 Z:31.50 A:720.05 B:720.05 Count X:0 Y:0 Z:1260 A:3197 B:3197",
                                 "ok", "ok"])

    def test_m112_kills_on_read(self):
        b = Bench()
        b.send("G1 X100 F600")
        b.write("M112\n")
        b.run(2.0)
        st = b.sim.state(b.t)
        self.assertTrue(st["halted"])
        self.assertEqual(st["halt_reason"], "M112 Shutdown")
        self.assertFalse(any(st["enabled"].values()))


def head_x(b):
    return b.sim.state(b.t)["head"]["physical"]["X"]


class KillAndPowerMidMove(unittest.TestCase):
    """Where the head ends up when the board is killed or loses power during G1 X100 F600 (10 mm/s, from the boot
    park at X 5): BR-02's 'the head stays where it physically is'."""

    def moving(self, personality="stock", seconds=5.0):
        b = Bench(personality=personality)
        for line in ("M106", "M106 P1 S180", "M106 P2 S255", "M106 P3 S255"):
            b.send(line)
        b.send("G1 X100 F600")
        b.run(seconds)
        return b

    def test_stock_m112_mid_move_runs_0_6_s_more_then_stops_with_fans_as_they_were(self):
        b = self.moving()
        x_kill = head_x(b)
        self.assertGreater(x_kill, 50.0)
        b.write("M112\n")
        b.run(3.0)
        self.assertAlmostEqual(head_x(b) - x_kill, 6.0, delta=0.01)  # minkill(): 0.6 s at 10 mm/s
        st = b.sim.state(b.t)
        self.assertTrue(st["halted"])
        self.assertEqual(st["fans"], [255, 180, 255, 255])

    def test_power_cycle_mid_move_leaves_the_head_where_it_is(self):
        b = self.moving()
        x = head_x(b)
        self.assertGreater(x, 50.0)
        b.sim.power_cycle(b.t)
        self.assertAlmostEqual(head_x(b), x, places=6)
        b.run(5.0)
        self.assertAlmostEqual(head_x(b), x, places=6)
        self.assertEqual(b.sim.state(b.t)["fans"], [0, 0, 0, 0])

    def test_power_cycle_inside_the_kill_window_stops_the_head_at_power_off(self):
        """A stock kill lets the stepper run 0.6 s more; power off 0.2 s after the kill stops it there."""
        b = self.moving()
        b.write("M112\n")
        b.run(0.2)
        x = head_x(b)
        b.sim.power_cycle(b.t)
        self.assertAlmostEqual(head_x(b), x, places=6)
        b.run(2.0)
        self.assertAlmostEqual(head_x(b), x, places=6)


class IlSafetyQuickStop(unittest.TestCase):
    def test_m410_acks_after_one_second_and_vents_the_right_side_at_once(self):
        b = Bench(personality="il-safety")
        for line in ("M106 P0 S255", "M106 P1 S180", "M106 P2 S255", "M106 P3 S255"):
            b.send(line)
        t0 = b.t
        b.write("M410\n")
        b.run(0.001)
        self.assertEqual(b.sim.state(b.t)["fans"], [255, 180, 0, 0])
        b.run(2.0)
        self.assertEqual([round(t - t0, 3) for t, l in b.rx if l == "ok" and t > t0], [1.0])
        hist = b.sim.state(b.t)["fan_history"][-2:]
        self.assertEqual([(h["p"], h["s"]) for h in hist], [(3, 0), (2, 0)])

    def test_stop_during_homing_aborts_g28_without_kill(self):
        b = Bench(personality="il-safety", park=(300.0, 300.0, 31.5))
        b.write("G28\n")
        t0 = b.t
        b.run(4.0)
        b.write("M410\n")
        b.run(3.0)
        got = [(round(t - t0, 3), l) for t, l in b.rx if t > t0 and "busy" not in l]
        self.assertEqual([l for _, l in got][:2], ["echo:Home Y First",
                                                   "echo:G28 aborted by quick-stop: axes not homed"])
        m = REPORT.match(got[2][1])
        self.assertIsNotNone(m, got[2][1])
        self.assertEqual(m.group(1), "0.00")          # X had homed before the stop
        self.assertLess(float(m.group(2)), 0.0)       # Y stopped mid-approach, counted from 0
        self.assertEqual(m.group(3), "31.50")         # Z_AFTER_HOMING set but never moved (moves refused)
        self.assertEqual(m.group(8), "5040")          # il: Z 160 steps/mm
        self.assertEqual([l for _, l in got][3:], ["ok", "ok"])
        self.assertAlmostEqual(got[3][0], 5.0, delta=0.01)
        st = b.sim.state(b.t)
        self.assertFalse(st["halted"])
        self.assertEqual(st["homed"], {"X": False, "Y": False, "Z": False})
        self.assertAlmostEqual(st["physical"][2], 31.5, places=6)


if __name__ == "__main__":
    unittest.main()
