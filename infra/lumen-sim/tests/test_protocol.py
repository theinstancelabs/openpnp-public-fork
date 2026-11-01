"""Line handling, parsing and per-command replies (fake clock)."""
import re
import unittest

from lumen_sim.bench import Bench

# OpenPnP POSITION_REPORT_REGEX from the real machine.xml (Java named groups -> Python)
POSITION_RE = re.compile(r"^.*X:(?P<X>-?\d+\.\d+)\s*Y:(?P<Y>-?\d+\.\d+)\s*Z:(?P<Z>-?\d+\.\d+)\s*"
                         r"A:(?P<A>-?\d+\.\d+)\s*B:(?P<B>-?\d+\.\d+).*")
BOOT_M114 = "X:0.00 Y:0.00 Z:0.00 A:720.00 B:720.00 Count X:0 Y:0 Z:0 A:3197 B:3197"


class LineEndings(unittest.TestCase):
    def test_lf_cr_crlf_each_give_one_ok(self):
        for eol in ("\n", "\r", "\r\n"):
            b = Bench()
            b.write("M400" + eol + "M400" + eol)
            b.run(0.01)
            self.assertEqual([l for _, l in b.rx], ["ok", "ok"], repr(eol))

    def test_replies_end_in_lf_only(self):
        b = Bench()
        b.write("M114\n")
        b.run(0.01)
        self.assertEqual(b.raw, (BOOT_M114 + "\nok\n").encode())

    def test_empty_lines_are_ignored(self):
        b = Bench()
        b.write("\n\n  \nM400\n")
        b.run(0.01)
        # a line of spaces is not empty to Marlin: it is parsed and echoed as an unknown, empty command
        self.assertEqual([l for _, l in b.rx], ['echo:Unknown command: ""', "ok", "ok"])


class Parsing(unittest.TestCase):
    def test_comment_is_stripped_before_the_unknown_command_echo(self):
        r = Bench().send("M990 S3000 ; arm the watchdog")
        self.assertEqual(r.lines, ['echo:Unknown command: "M990 S3000 "', "ok"])

    def test_line_is_cut_at_95_characters(self):
        line = "M9999 " + "A" * 200
        r = Bench().send(line)
        self.assertEqual(r.lines, ['echo:Unknown command: "%s"' % line[:95], "ok"])

    def test_lowercase_command_is_unknown(self):
        self.assertEqual(Bench().send("g4 p0").lines, ['echo:Unknown command: "g4 p0"', "ok"])

    def test_leading_spaces(self):
        self.assertEqual(Bench().send("   M400").lines, ["ok"])

    def test_g21_is_a_noop_and_g20_unknown(self):
        b = Bench()
        self.assertEqual(b.send("G21 ; Set millimeters mode ").lines, ["ok"])
        self.assertEqual(b.send("G20").lines, ['echo:Unknown command: "G20"', "ok"])


class Reports(unittest.TestCase):
    def test_boot_m114(self):
        self.assertEqual(Bench().send("M114").lines, [BOOT_M114, "ok"])

    def test_m114_arguments_are_ignored(self):
        b = Bench()
        self.assertEqual(b.send("M114 D").lines, [BOOT_M114, "ok"])
        self.assertEqual(b.send("M114 R").lines, [BOOT_M114, "ok"])

    def test_position_regex_reads_logical_values_not_counts(self):
        b = Bench()
        b.send("G1 X12.5 Y-3.25 Z7 A10 B-90 F60000")
        line = b.send("M114").lines[0]
        m = POSITION_RE.match(line)
        self.assertIsNotNone(m)
        self.assertEqual({k: float(v) for k, v in m.groupdict().items()},
                         {"X": 12.5, "Y": -3.25, "Z": 7.0, "A": 10.0, "B": -90.0})
        self.assertRegex(line, r" Count X:-?\d+ Y:-?\d+ Z:-?\d+ A:-?\d+ B:-?\d+$")

    def test_m114_projects_the_planned_end_at_once(self):
        b = Bench()
        b.send("G1 X100 F600")  # 10 s move
        r = b.send("M114")
        self.assertLess(r.ok_at, 0.001)
        self.assertTrue(r.lines[0].startswith("X:100.00 Y:0.00"), r.lines[0])
        self.assertIn("Count X:32000 ", r.lines[0])

    def test_g92_changes_logical_only_and_reports(self):
        b = Bench()
        r = b.send("G92 X10 B0")
        self.assertEqual(r.lines, ["X:10.00 Y:0.00 Z:0.00 A:720.00 B:0.00 Count X:0 Y:0 Z:0 A:3197 B:3197", "ok"])
        b.send("G1 X20 F6000")
        self.assertIn("Count X:3200 ", b.send("M114").lines[0])

    def test_m105(self):
        self.assertEqual(Bench().send("M105").lines, ["ok T:0"])

    def test_m115_il_safety_caps(self):
        lines = Bench(personality="il-safety").send("M115").lines
        self.assertIn("Cap:EMERGENCY_PARSER:1", lines)
        self.assertEqual(lines[-2:], ["Cap:HOST_WATCHDOG:1", "ok"])
        stock = Bench().send("M115").lines
        self.assertIn("Cap:EMERGENCY_PARSER:0", stock)
        self.assertFalse(any("HOST_WATCHDOG" in l for l in stock))

    def test_m119_triggered_at_the_switch(self):
        b = Bench(park=(0.0, 3.0, 0.0))
        self.assertEqual(b.send("M119").lines,
                         ["Reporting endstop status", "x_min: TRIGGERED", "y_min: open", "z_min: TRIGGERED", "ok"])

    def test_m211_report_and_toggle(self):
        b = Bench()
        self.assertEqual(b.send("M211").lines, [
            "  M211 S1 ; ON",
            "  Min:  X0.00 Y0.00 Z0.00 A0.00 B0.00   Max:  X433.00 Y487.00 Z63.00 A720.00 B720.00", "ok"])
        self.assertEqual(b.send("M211 S0").lines, ["ok"])
        self.assertEqual(b.send("M211").lines[0], "  M211 S0 ; OFF")


class Peripherals(unittest.TestCase):
    def test_vacuum_default_is_open_air(self):
        line = Bench().send("M261 A109 B3 S1").lines[0]
        m = re.match(r"echo:i2c-reply: from:109 bytes:3 data:([0-9A-F]{2}) ([0-9A-F]{2}) ([0-9A-F]{2})$", line)
        self.assertIsNotNone(m, line)
        raw = int("".join(m.groups()), 16)
        value = raw - (1 << 24) if raw & 0x800000 else raw
        self.assertTrue(-1.75e6 <= value <= -1.0e6, value)

    def test_vacuum_s2_is_int16_of_the_last_two_bytes(self):
        b = Bench(vacuum=-1_500_000)  # 0xE91CA0 -> int16 0x1CA0 = 7328
        self.assertEqual(b.send("M261 A109 B3 S2").lines, ["echo:i2c-reply: from:109 bytes:3 data:7328", "ok"])

    def test_vacuum_model_is_pluggable(self):
        b = Bench(vacuum=lambda fw: -1_200_000 if fw.fans[1] else -1_600_000)
        self.assertIn("data:E7 96 00", b.send("M261 A109 B3 S1").lines[0])
        b.send("M106 P1 S180")
        self.assertIn("data:ED B0 80", b.send("M261 A109 B3 S1").lines[0])

    def test_bad_i2c_request(self):
        self.assertEqual(Bench().send("M261 B3").lines, ["Error:Bad i2c request", "ok"])

    def test_m485_default_timeout_after_100ms(self):
        r = Bench().send("M485 FF00000D0FC0003E00084648570620333535")
        self.assertEqual(r.lines, ["rs485-reply: TIMEOUT", "ok"])
        self.assertAlmostEqual(r.ok_at, 0.100, delta=0.002)

    def test_fans_are_immediate_and_logged(self):
        b = Bench()
        b.send("G1 X100 F600")
        r = b.send("M106 P2 S255")
        self.assertLess(r.ok_at, 0.001)  # not synchronised with motion
        b.send("M106")  # P0, S255
        b.send("M107 P2")
        state = b.sim.state(b.t)
        self.assertEqual(state["fans"], [255, 0, 0, 0])
        self.assertEqual([(h["p"], h["s"]) for h in state["fan_history"]], [(2, 255), (0, 255), (2, 0)])


class Keepalive(unittest.TestCase):
    def test_busy_every_two_seconds_while_blocking(self):
        r = Bench().send("G4 P7000")
        self.assertEqual([round(t, 3) for t in r.busy], [2.0, 4.0, 6.0])
        self.assertAlmostEqual(r.ok_at, 7.0, delta=0.001)

    def test_no_busy_while_idle(self):
        b = Bench()
        b.run(10)
        self.assertEqual(b.rx, [])


if __name__ == "__main__":
    unittest.main()
