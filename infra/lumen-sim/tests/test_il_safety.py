"""Personality il-safety (il/safety d973aef69): M990 watchdog, forced on/off right outputs, settings (fake clock)."""
import unittest

from lumen_sim.bench import Bench
from tests.hang import within


def il():
    return Bench(personality="il-safety")


class Watchdog(unittest.TestCase):
    def test_m990_arguments(self):
        b = il()
        self.assertEqual(b.send("M990").lines, ["echo:Host watchdog: off", "ok"])
        self.assertEqual(b.send("M990 S50").lines, ["Error:M990 S must be 0 or 100..3600000", "ok"])
        self.assertEqual(b.send("M990 S3600001").lines, ["Error:M990 S must be 0 or 100..3600000", "ok"])
        self.assertEqual(b.send("M990 S100").lines, ["echo:Host watchdog: armed S100", "ok"])
        self.assertEqual(b.send("M990 S0").lines, ["echo:Host watchdog: off", "ok"])

    def test_silence_trips_outputs_off_and_auger_disabled(self):
        b = il()
        b.send("G1 Z1 F600")  # energises Z
        b.send("M106 P0 S255")
        b.send("M106 P2 S255")
        b.send("M106 P3 S255")
        b.send("M990 S500")
        t0 = b.t
        b.run(2.0)
        self.assertEqual([(round(t - t0, 3), l) for t, l in b.rx if t > t0], [(0.5, "Error:host watchdog")])
        st = b.sim.state(b.t)
        self.assertEqual(st["fans"], [0, 0, 0, 0])
        self.assertFalse(st["enabled"]["B"])
        self.assertTrue(st["enabled"]["Z"])
        self.assertEqual(b.send("M990").lines, ["echo:Host watchdog: off tripped", "ok"])
        self.assertEqual(b.send("M990 S2000").lines, ["echo:Host watchdog: armed S2000", "ok"])

    def test_lines_and_busy_commands_hold_the_watchdog(self):
        b = il()
        b.send("M990 S500")
        for _ in range(5):
            b.run(0.4)
            b.send("M400")
        r = b.send("G4 P2000")
        self.assertEqual(r.lines, ["echo:busy: processing", "ok"])
        t0 = b.t
        b.run(1.0)
        self.assertEqual([(round(t - t0, 3), l) for t, l in b.rx if t > t0], [(0.5, "Error:host watchdog")])

    def test_a_trip_during_a_move_stops_the_head_where_it_is(self):
        b = il()
        b.send("M990 S500")
        b.send("G1 X100 F600")  # acked when planned; the watchdog counts from this line
        t0 = b.t
        b.run(2.0)
        self.assertEqual([(round(t - t0, 3), l) for t, l in b.rx if t > t0], [(0.5, "Error:host watchdog")])
        h = b.sim.state(b.t)["head"]
        # 0.1 s first-move delay, then 0.4 s at 10 mm/s from the park at X 5
        self.assertAlmostEqual(h["physical"]["X"], 9.0, delta=0.02)
        b.run(5.0)
        self.assertEqual(b.sim.state(b.t)["head"], h)
        line = b.send("M114").lines[0]  # the quick-stop re-read the position from the steps
        self.assertTrue(line.startswith("X:%.2f " % h["logical"]["X"]), (line, h))

    def test_stock_has_no_m990(self):
        self.assertEqual(Bench().send("M990 S3000").lines, ['echo:Unknown command: "M990 S3000"', "ok"])


class Kill(unittest.TestCase):
    def test_m112_turns_every_output_off_and_stops_motion_at_once(self):
        with within(self, 5):
            b = il()
            for line in ("M106", "M106 P1 S180", "M106 P2 S255", "M106 P3 S255"):
                b.send(line)
            b.send("G1 X100 F600")
            b.run(5.0)
            x = b.sim.state(b.t)["head"]["physical"]["X"]
            b.write("M112\n")
            b.run(3.0)
            st = b.sim.state(b.t)
            self.assertTrue(st["halted"])
            self.assertEqual(st["fans"], [0, 0, 0, 0])
            self.assertEqual([h["source"] for h in st["fan_history"][-4:]], ["kill"] * 4)
            self.assertAlmostEqual(st["head"]["physical"]["X"], x, places=6)


class Settings(unittest.TestCase):
    def test_right_outputs_are_on_off(self):
        b = il()
        b.send("M106 P2 S100")
        b.send("M106 P3 S1")
        b.send("M106 P1 S100")
        self.assertEqual(b.sim.state(b.t)["fans"], [0, 100, 255, 255])

    def test_m503_shows_the_il_numbers(self):
        lines = il().send("M503").lines
        self.assertIn("echo:  M92 X320.00 Y320.00 Z160.00 A4.44 B4.44", lines)
        self.assertIn("echo:  M201 X10000.00 Y10000.00 Z10000.00 A10000 B20000", lines)
        self.assertIn("echo:  M906 X800 Y1000 Z800 A200 B540", lines)

    def test_m997_drops_the_port_until_a_power_cycle(self):
        with within(self, 5):
            b = il()
            b.write("M997\n")
            b.run(0.05)
            self.assertEqual(b.sim.take_transport_actions(), [])  # 0.1 s for USB to send what was due
            self.assertEqual(b.sim.next_event_time(), 0.1)  # then the reset into DFU: the runner wakes for it
            b.run(0.1)
            self.assertEqual(b.sim.state(b.t)["halt_reason"], "DFU")
            self.assertEqual(b.sim.take_transport_actions(), [{"action": "disconnect", "down_s": None}])
            self.assertEqual(Bench().send("M997").lines, ['echo:Unknown command: "M997"', "ok"])

    def test_m997_switches_every_output_off_and_lets_every_stepper_go_before_dfu(self):
        """flashFirmware() (dfu_bootloader.cpp): planner.quick_stop(), il_outputs_off() (valve, pump, FAN1, FAN0),
        disable_all_steppers() (a released Z drops), the echo, then the DFU bootloader."""
        with within(self, 5):
            b = il()
            b.send("G28")
            b.send("M17")  # A and B too: G28 energises X, Y and Z only
            for p in (3, 2, 1, 0):
                b.send("M106 P%d S255" % p)
            b.sim.control({"cmd": "fault", "fault": "z_drop", "z": -0.4}, b.t)
            st = b.sim.state(b.t)
            self.assertEqual(st["enabled"], dict.fromkeys("XYZAB", True))
            z = st["head"]["physical"]["Z"]
            b.write("M997\n")
            b.run(0.1)
            st = b.sim.state(b.t)
            self.assertEqual(st["halt_reason"], "DFU")
            self.assertEqual(st["fans"], [0, 0, 0, 0])
            self.assertEqual(st["enabled"], dict.fromkeys("XYZAB", False))
            self.assertAlmostEqual(st["head"]["physical"]["Z"], z - 0.4, places=6)
            self.assertIn("echo:M997: entering STM32 DFU bootloader (0483:df11)", [l for _, l in b.rx])

    def test_m997_stops_a_move_where_the_head_is(self):
        """planner.quick_stop() first: the move under way ends at once, short of its end."""
        with within(self, 5):
            b = il()
            b.send("G1 X100 F600")  # acked when planned: M997 runs while it moves
            b.run(5.0)
            x = b.sim.state(b.t)["head"]["physical"]["X"]
            b.write("M997\n")
            b.run(3.0)
            self.assertLess(x, 99)
            self.assertAlmostEqual(b.sim.state(b.t)["head"]["physical"]["X"], x, places=6)

    def test_boot_report_with_160_steps_on_z(self):
        b = il()
        b.send("G1 Z10 F600")
        self.assertIn("Count X:0 Y:0 Z:1600 ", b.send("M114").lines[0])


if __name__ == "__main__":
    unittest.main()
