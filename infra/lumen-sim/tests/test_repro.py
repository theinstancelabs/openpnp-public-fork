"""Protocol-level reproductions of the two machine bugs, in real time through the pty.

The client mimics OpenPnP's GcodeDriver (one line in flight, confirm regex ^ok.*, the machine's error regex, 10 s per
line, -1 = 60 s). Each test takes 7-19 s of wall time. Skip them with LUMEN_SIM_SKIP_REALTIME=1.
"""
import math
import os
import threading
import time
import unittest

from lumen_sim.hostclient import DriverClient, DriverTimeout
from tests.simproc import SimProcess

SKIP = os.environ.get("LUMEN_SIM_SKIP_REALTIME") == "1"


def prime(client, turns_deg):
    """The touch-off prime stream of RESEARCH.md section 6a, up to the G4 after the screw move."""
    for line in ("G92 B0", "M201 B20000", "M203 B2400", "M204 S2000", "G0 X50 Y50 F12000",
                 "M106 P2 S255", "G4 P1000", "M106 P3 S255", "G4 P1500", "G1 Z5 F3000", "M204 S20000",
                 "G1 B%.4f F64800" % turns_deg):
        client.send(line)


def predicted_g4_ok(turns_deg):
    """Seconds from writing 'G4 P250' to its ok: first-move delay, the 5 mm Z move, the screw move, the dwell."""
    def rest_to_rest(n, rate, accel, r0=120.0):
        ramp = (rate * rate - r0 * r0) / (2 * accel)
        if 2 * ramp > n:
            return 2 * (math.sqrt(accel * n + r0 * r0) - r0) / accel
        return 2 * (rate - r0) / accel + (n - 2 * ramp) / rate
    z = rest_to_rest(5 * 40, 50 * 40, 2000 * 40)
    n = round(turns_deg * 4.44)
    b = rest_to_rest(n, math.ceil(1080 * 4.44), 20000 * 4.44)
    return 0.100 + z + b + 0.250


@unittest.skipIf(SKIP, "LUMEN_SIM_SKIP_REALTIME=1")
class PrimeTimeout(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sim = SimProcess("--personality", "stock")

    @classmethod
    def tearDownClass(cls):
        cls.sim.stop()

    def test_30_turns_at_180_rpm_time_out_on_g4(self):
        c = DriverClient(self.sim.link)
        try:
            prime(c, 10836.0)  # the screw line of the failed session-6 touch-off: 30 turns and a little more
            t0 = time.monotonic()
            with self.assertRaises(DriverTimeout) as cm:
                c.send("G4 P250")
            waited = time.monotonic() - t0
            self.assertIn("timeout waiting for response to G4 P250", str(cm.exception))
            self.assertAlmostEqual(waited, 10.0, delta=0.3)
            late = c.wait_line(lambda line: line.startswith("ok"), after=t0, timeout=3.0)
            self.assertIsNotNone(late, "the late ok never came")
            self.assertAlmostEqual(late - t0, predicted_g4_ok(10836.0), delta=0.15)
            self.assertGreater(late - t0, 10.0)
        finally:
            c.close()

    def test_20_turn_chunks_do_not_time_out(self):
        c = DriverClient(self.sim.link)
        try:
            prime(c, 7236.0)  # the same prime cut to 20 turns
            t0 = time.monotonic()
            c.send("G4 P250")
            self.assertAlmostEqual(time.monotonic() - t0, predicted_g4_ok(7236.0), delta=0.15)
        finally:
            c.close()


def home_and_stop(test, sim):
    """G28 on the machine thread, M410 4 s later from a second thread (the web STOP), as in session 5."""
    c = DriverClient(sim.link)
    home = {}

    def run_home():
        try:
            c.send("G28", timeout_ms=-1)
            home["result"] = "ok"
        except Exception as e:  # DriverTimeout, or the port closed under it
            home["result"] = e

    t0 = time.monotonic()
    th = threading.Thread(target=run_home, daemon=True)
    th.start()
    time.sleep(4.0)
    return c, th, home, t0


@unittest.skipIf(SKIP, "LUMEN_SIM_SKIP_REALTIME=1")
class StopDuringHoming(unittest.TestCase):
    def test_stock_board_halts_until_power_cycle(self):
        sim = SimProcess("--personality", "stock", "--park", "300,300,31.5")
        try:
            c, th, home, t0 = home_and_stop(self, sim)
            with self.assertRaises(DriverTimeout):
                c.send("M410")  # 10 s
            self.assertNotIn("result", home)  # G28 never completed
            self.assertEqual([l for t, l in c.lines if t > t0 and "busy" not in l], [])
            with self.assertRaises(DriverTimeout):
                c.send("M115", timeout_ms=2000)
            c.close()
            th.join(1.0)
            c = DriverClient(sim.link)  # reopening the port does not reset it
            with self.assertRaises(DriverTimeout):
                c.send("M115", timeout_ms=2000)
            c.close()
            self.assertTrue(sim.ctl({"cmd": "state"})["state"]["halted"])
            self.assertTrue(sim.ctl({"cmd": "power_cycle"})["ok"])
            c = DriverClient(sim.link)
            try:
                self.assertTrue(c.send("M115").startswith("ok"))
                self.assertIn("FIRMWARE_NAME:Marlin", c.responses[0])
                c.send("M114")
                self.assertEqual(c.responses,
                                 ["X:0.00 Y:0.00 Z:0.00 A:720.00 B:720.00 Count X:0 Y:0 Z:0 A:3197 B:3197"])
            finally:
                c.close()
        finally:
            sim.stop()

    def test_il_safety_aborts_homing_and_answers(self):
        sim = SimProcess("--personality", "il-safety", "--park", "300,300,31.5")
        try:
            c, th, home, t0 = home_and_stop(self, sim)
            try:
                t1 = time.monotonic()
                c.send("M410")
                th.join(2.0)
                self.assertEqual(home.get("result"), "ok")
                self.assertAlmostEqual(time.monotonic() - t1, 1.0, delta=0.3)
                got = [l for t, l in c.lines if t > t0 and "busy" not in l]
                self.assertEqual(got[:2], ["echo:Home Y First", "echo:G28 aborted by quick-stop: axes not homed"])
                self.assertRegex(got[2], r"^X:0\.00 Y:-\d+\.\d\d Z:31\.50 ")
                self.assertEqual(got[3:], ["ok", "ok"])
                self.assertTrue(c.send("M115").startswith("ok"))
            finally:
                c.close()
        finally:
            sim.stop()


if __name__ == "__main__":
    unittest.main()
