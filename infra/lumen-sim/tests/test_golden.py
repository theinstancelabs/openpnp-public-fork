"""Golden tests: the simulator against excerpts of the real stock board's serial.log.

Reply text is compared exactly (after strip(), because the logger stripped every line). Timing is compared on the
fake clock with a 3 % tolerance. Where a log line depends on state from before the excerpt (G92 offsets, step counts,
motion settings), the test rebuilds that state with ordinary G-code and says how.
"""
import unittest

from lumen_sim.bench import Bench
from tests import goldens

TOL = 0.03  # relative timing tolerance
BUSY = "echo:busy: processing"


def text(reply):
    return [line.strip() for line in reply.lines]


class StaticReplies(unittest.TestCase):
    def test_m115_stock(self):
        rows = goldens.load("m115")
        want = [t for _, t in goldens.replies(rows, "M115")]
        self.assertEqual(text(Bench().send("M115")), want)

    def test_m114_with_workspace_offset(self):
        rows = goldens.load("m114")
        want = [t for _, t in goldens.replies(rows, "M114")]
        b = Bench()
        # counts 77155/68740/1060/888 = native 241.109375 / 214.8125 / 26.5 / 200; G92 sets the logical 241.12 / 215.95
        b.send("G1 X241.109375 Y214.8125 Z26.5 A200 F60000")
        b.send("M400")
        b.send("G92 X241.12 Y215.95")
        self.assertEqual(text(b.send("M114")), want)

    def test_m119_all_open(self):
        rows = goldens.load("m119")
        want = [t for _, t in goldens.replies(rows, "M119")]
        self.assertEqual(text(Bench(park=(241.0, 214.0, 26.5)).send("M119")), want)

    def test_m503_with_m204_s1000(self):
        rows = goldens.load("m503")
        want = [t for _, t in goldens.replies(rows, "M503")]
        b = Bench()
        b.send("M204 S1000")  # the log's M503 shows P1000/T1000: an M204 S1000 was in effect (the boot value is P2000 T2500)
        self.assertEqual(text(b.send("M503")), want)

    def test_m211_after_g92(self):
        rows = goldens.load("m211")
        b = Bench()
        b.send("G28")
        # counts 61696/72790 = native 192.8 / 227.47; the G92 offsets -0.10 / +1.05 shift the soft limits M211 prints
        b.send("G1 X192.8 Y227.47 F12000")
        b.send("M400")
        b.send("G92 X192.7 Y228.52")
        self.assertEqual(text(b.send("M114")), [t for _, t in goldens.replies(rows, "M114")])
        self.assertEqual(text(b.send("M211")), [t for _, t in goldens.replies(rows, "M211")])

    def test_m105(self):
        rows = goldens.load("m105")
        want = [t for _, t in goldens.replies(rows, "M105")]
        self.assertEqual(text(Bench().send("M105")), want)

    def test_m261_s1_format(self):
        rows = goldens.load("m261_s1")
        want = [t for _, t in goldens.replies(rows, "M261 A109 B3 S1")]
        b = Bench(vacuum=0x2B8900)  # the raw 24-bit reading behind 'data:2B 89 00'
        self.assertEqual(text(b.send("M261 A109 B3 S1")), want)

    def test_m485_reply_and_latency(self):
        rows = goldens.load("m485")
        req = "FF00000D0FC0003E00084648570620333535"
        got = goldens.replies(rows, "M485 " + req)
        b = Bench(rs485={req: "001700011000"})
        r = b.send("M485 " + req)
        self.assertEqual(text(r), [t for _, t in got])
        self.assertAlmostEqual(r.ok_at, got[-1][0], delta=0.005)


class MotionTiming(unittest.TestCase):
    def test_b_moves_m400_timing_and_busy(self):
        rows = goldens.load("b_moves")
        b = Bench()
        # Rebuild the state behind 'G92 B0' (log line 3433): counts X11805 Y56020 Z1880 A888 B-15984 are native
        # 36.890625 / 175.0625 / 47 / 200 / -3600; the G92 gives the logical 36.90 / 176.20.
        b.send("G1 X36.890625 Y175.0625 Z47 A200 B-3600 F60000")
        b.send("M400")
        b.send("G92 X36.9 Y176.2")
        # Motion settings in effect at line 3436: M204 S1000 (sent at line 3081, no reboot since), M201 and M203 at
        # their boot values (B 10000 deg/s^2, B 50000 deg/s): no M201/M203 appears in the log before this point.
        b.send("M204 S1000")

        r = b.send("G92 B0")
        self.assertEqual(text(r), [t for _, t in goldens.replies(rows, "G92 B0")])
        self.assertEqual(text(b.send("G1 B1800 F21600")), ["ok"])
        want = goldens.replies(rows, "M400")
        r = b.send("M400")
        self.assertEqual(text(r), [t for _, t in want])
        self.assertAlmostEqual(r.ok_at, want[-1][0], delta=TOL * want[-1][0])
        self.assertEqual([round(t, 3) for t in r.busy], [2.0, 4.0])

        r = b.send("G4 P2000")  # the busy line due at 2.000 s comes before the ok, as in the log
        self.assertEqual(text(r), [BUSY, "ok"])
        self.assertAlmostEqual(r.ok_at, 2.0, delta=0.002)

        second = [row for row in rows if row[0] >= 1793141926.0]
        r = b.send("G92 B0")
        self.assertEqual(text(r), [t for _, t in goldens.replies(second, "G92 B0")])
        b.send("G1 B21600 F43200")
        want = goldens.replies(second, "M400")
        r = b.send("M400")
        self.assertEqual(text(r), [t for _, t in want])
        self.assertEqual(len(r.busy), 15)
        self.assertAlmostEqual(r.ok_at, want[-1][0], delta=TOL * want[-1][0])

        r = b.send("G4 P3000")
        self.assertEqual(text(r), [BUSY, "ok"])
        self.assertAlmostEqual(r.ok_at, 3.0, delta=0.002)

    def test_g4_p4000_busy_on_the_tie(self):
        rows = goldens.load("b_moves")
        want = goldens.replies(rows, "G4 P4000")
        r = Bench().send("G4 P4000")
        self.assertEqual(text(r), [t for _, t in want])
        self.assertEqual([round(t, 3) for t in r.busy], [2.0, 4.0])


# The board's G28s come in two speeds (README, "Homing time"): lines 166293 and 166954 took ~1.4 s more than the
# source's kinematics, the later 168535 and 169216 (and the boot G28) none. The default is the fast one; the slow one
# is set {"homing_move_delay_s": 0.142}.
SLOW_HOMING_MOVE_DELAY_S = 0.142


class Homing(unittest.TestCase):
    def _g28_case(self, name, park, a_b_logical, b_native=126211.27, delay=None):
        rows = goldens.load(name)
        b = Bench(park=park)
        if delay is not None:
            self.assertTrue(b.sim.control({"cmd": "set", "homing_move_delay_s": delay}, b.t)["ok"])
        # A count 12965 = native 2920.04; B 560378 = 126211.27 (656282 = 147811.26); G92 gives the logged A/B
        b.send("G1 A2920.04 B%.2f F6000000" % b_native)
        b.send("M400")
        b.send("G92 " + a_b_logical)
        b.send("M204 S1500")
        want = goldens.replies(rows, "G28")
        r = b.send("G28")
        self.assertEqual(text(r), [t for _, t in want])
        self.assertAlmostEqual(r.ok_at, want[-1][0], delta=TOL * want[-1][0])
        self.assertEqual(text(b.send("G92 A0 B0")), [t for _, t in goldens.replies(rows, "G92 A0 B0")])
        return b, rows

    def test_g28_from_x217_y63_slow(self):
        # physical start = the native position from the last report and moves before line 166293
        self._g28_case("g28_166293", (217.171875, 63.17, 31.5), "A200 B0", delay=SLOW_HOMING_MOVE_DELAY_S)

    def test_g28_from_x241_y216_then_m211_slow(self):
        b, rows = self._g28_case("g28_166954", (240.940625, 215.878125, 31.5), "A0 B0",
                                 delay=SLOW_HOMING_MOVE_DELAY_S)
        self.assertEqual(text(b.send("M211")), [t for _, t in goldens.replies(rows, "M211")])

    def test_g28_from_x235_y199_at_the_default_speed(self):
        # the M114 at line 168526 (counts X75048 Y63717, no move since) gives the physical start; 5.246 s, 2 busy
        self._g28_case("g28_168535", (75048 / 320, 63717 / 320, 31.5), "A0 B0", b_native=147811.26)

    def test_g28_after_power_on_report(self):
        # Text and busy count only: the head's physical position after that power cycle is unknown, so the 2.82 s
        # the board took cannot be reproduced (see README, "Homing time").
        rows = goldens.load("g28_boot")
        b = Bench()
        b.send("M204 S2000")
        r = b.send("G28")
        self.assertEqual(text(r), [t for _, t in goldens.replies(rows, "G28")])


class Stop(unittest.TestCase):
    def test_lone_m410_ok_after_two_seconds(self):
        rows = goldens.load("m410_lone")
        b = Bench()
        # counts X32021 Y111643 Z1260 A5861 B1196524 behind the logical X100 Y350 Z31.5 A200 B0
        b.send("G1 X100.065625 Y348.884375 Z31.5 A1320.045 B269487.387 F6000000")
        b.send("M400")
        b.send("G92 X100 Y350 A200 B0")
        before = text(b.send("M114"))
        self.assertEqual(before, [t for _, t in goldens.replies(rows, "M114")])
        want = goldens.replies(rows, "M410")
        r = b.send("M410")
        self.assertEqual(text(r), ["ok"])  # no busy line: the first quick-stop runs while reading, not in a handler
        self.assertAlmostEqual(r.ok_at, want[-1][0], delta=TOL * want[-1][0])
        for line in ("M107 P3", "M107 P2", "M107 P1", "M107 P0", "M18 B"):
            r = b.send(line)
            self.assertEqual(text(r), ["ok"])
            self.assertLess(r.ok_at, 0.002)
        self.assertEqual(text(b.send("M114")), before)

    def test_stop_burst_first_ok_after_one_second(self):
        rows = goldens.load("stop_burst")
        b = Bench()
        b.send("G4 P250")
        b.run(0.24)
        t0 = b.t
        b.write("M410\nM107 P3\nM107 P2\nM107 P1\nM107 P0\nM18 B\n")  # lumen.py's STOP, one write()
        b.write("M107 P1\n")                                          # lumen.py's next send, same millisecond
        b.run(1.5)
        oks = [t - t0 for t, line in b.rx if t >= t0 and line.startswith("ok")]
        want = [t for t, _ in goldens.replies(rows, "M107 P1")][0]
        self.assertAlmostEqual(oks[0], want, delta=TOL * want)
        # the early quick-stop read the four M107 lines into the full 4-slot ring and then dropped the M410 itself:
        # 7 lines in, 6 oks out, all within a millisecond of the first
        self.assertEqual(len(oks), 6)
        self.assertLess(oks[-1] - oks[0], 0.002)


if __name__ == "__main__":
    unittest.main()
