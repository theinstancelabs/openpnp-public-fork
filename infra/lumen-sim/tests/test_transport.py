"""The pty transport, I/O log, control socket and CLI, through a real lumen-sim process (a few seconds)."""
import json
import os
import subprocess
import sys
import time
import unittest

from lumen_sim.hostclient import ControllerError, DriverClient
from tests import goldens
from tests.simproc import PKG_ROOT, SimProcess, open_raw, read_until_ok


class PtyTransport(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sim = SimProcess()

    @classmethod
    def tearDownClass(cls):
        cls.sim.stop()

    def cmd(self, fd, line, eol="\n", timeout=5.0):
        os.write(fd, (line + eol).encode())
        return read_until_ok(fd, timeout)

    def test_m115_through_the_symlink(self):
        self.assertTrue(os.path.realpath(self.sim.link).startswith("/dev/pts/"))
        fd = open_raw(self.sim.link)
        try:
            want = [t for _, t in goldens.replies(goldens.load("m115"), "M115")]
            self.assertEqual(self.cmd(fd, "M115"), want)
        finally:
            os.close(fd)

    def test_line_endings(self):
        fd = open_raw(self.sim.link)
        try:
            for eol in ("\n", "\r", "\r\n"):
                self.assertEqual(self.cmd(fd, "M400", eol), ["ok"], repr(eol))
        finally:
            os.close(fd)

    def test_state_survives_host_close_and_reopen(self):
        fd = open_raw(self.sim.link)
        self.cmd(fd, "G92 X12.5")
        os.close(fd)
        time.sleep(0.1)
        fd = open_raw(self.sim.link)
        try:
            self.assertTrue(self.cmd(fd, "M114")[0].startswith("X:12.50 "))
        finally:
            self.cmd(fd, "G92 X0")
            os.close(fd)

    def test_io_log_format(self):
        fd = open_raw(self.sim.link)
        try:
            self.cmd(fd, "M105")
        finally:
            os.close(fd)
        time.sleep(0.1)
        with open(self.sim.io_log, encoding="latin-1") as f:
            rows = f.read().splitlines()
        self.assertTrue(rows)
        for row in rows:
            self.assertRegex(row, r"^\d{10}\.\d{3} [<>#] ")
        self.assertTrue(any(r.endswith("> M105") for r in rows))
        self.assertTrue(any(r.endswith("< ok T:0") for r in rows))

    def test_ctl_cli_state(self):
        out = subprocess.run([sys.executable, "-m", "lumen_sim", "ctl", "--control", self.sim.sock, "state"],
                             cwd=PKG_ROOT, capture_output=True, text=True, timeout=10)
        self.assertEqual(out.returncode, 0, out.stderr)
        state = json.loads(out.stdout)["state"]
        self.assertEqual(state["personality"], "stock")
        self.assertEqual(state["pts"], os.path.realpath(self.sim.link))


class PowerCycle(unittest.TestCase):
    def test_power_cycle_drops_the_link_and_repoints_the_symlink(self):
        sim = SimProcess("--personality", "stock")
        try:
            fd = open_raw(sim.link)
            os.write(fd, b"G92 B0\nG1 B360 F360000\n")
            read_until_ok(fd)
            read_until_ok(fd)
            old = os.path.realpath(sim.link)
            reply = sim.ctl({"cmd": "power_cycle"})
            self.assertTrue(reply["ok"], reply)
            self.assertNotEqual(os.path.realpath(sim.link), old)
            # the host's open descriptor is dead: EIO, or end of file
            try:
                self.assertEqual(os.read(fd, 100), b"")
            except OSError as e:
                self.assertEqual(e.errno, 5)
            os.close(fd)
            fd = open_raw(sim.link)
            try:
                os.write(fd, b"M114\n")
                self.assertEqual(read_until_ok(fd),
                                 ["X:0.00 Y:0.00 Z:0.00 A:720.00 B:720.00 Count X:0 Y:0 Z:0 A:3197 B:3197", "ok"])
            finally:
                os.close(fd)
        finally:
            sim.stop()

    def test_ctl_cli_fault_and_clear(self):
        sim = SimProcess()
        try:
            def ctl(*args):
                out = subprocess.run([sys.executable, "-m", "lumen_sim", "ctl", "--control", sim.sock, *args],
                                     cwd=PKG_ROOT, capture_output=True, text=True, timeout=10)
                self.assertEqual(out.returncode, 0, out.stderr)
                return json.loads(out.stdout)
            self.assertTrue(ctl("fault", '{"fault": "no_ok", "match": "^M400"}')["ok"])
            fd = open_raw(sim.link)
            try:
                os.write(fd, b"M400\n")
                self.assertEqual(read_until_ok(fd, timeout=1.0), [])
                self.assertTrue(ctl("clear")["ok"])
                os.write(fd, b"M400\n")
                self.assertEqual(read_until_ok(fd), ["ok"])
            finally:
                os.close(fd)
        finally:
            sim.stop()


class LinkFaults(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sim = SimProcess()

    @classmethod
    def tearDownClass(cls):
        cls.sim.stop()

    def test_disconnect_comes_back_on_a_new_pty_with_state_kept(self):
        fd = open_raw(self.sim.link)
        os.write(fd, b"G92 X7\n")
        read_until_ok(fd)
        self.assertTrue(self.sim.ctl({"cmd": "fault", "fault": "disconnect", "down_s": 0.3})["ok"])
        self.assertTrue(self.sim.ctl({"cmd": "state"})["state"]["link_down"])
        try:
            self.assertEqual(os.read(fd, 100), b"")
        except OSError as e:
            self.assertEqual(e.errno, 5)
        os.close(fd)
        end = time.monotonic() + 3
        while self.sim.ctl({"cmd": "state"})["state"]["link_down"] and time.monotonic() < end:
            time.sleep(0.05)
        self.assertFalse(self.sim.ctl({"cmd": "state"})["state"]["link_down"])
        fd = open_raw(self.sim.link)
        try:
            os.write(fd, b"M114\n")
            self.assertTrue(read_until_ok(fd)[0].startswith("X:7.00 "))  # no reset: only the link went away
        finally:
            os.write(fd, b"G92 X0\n")
            read_until_ok(fd)
            os.close(fd)

    def test_frozen_stops_reading_until_cleared(self):
        fd = open_raw(self.sim.link)
        try:
            self.assertTrue(self.sim.ctl({"cmd": "fault", "fault": "frozen", "on": True})["ok"])
            os.write(fd, b"M400\n")
            self.assertEqual(read_until_ok(fd, timeout=0.5), [])
            self.assertTrue(self.sim.ctl({"cmd": "clear"})["ok"])
            self.assertEqual(read_until_ok(fd), ["ok"])
        finally:
            os.close(fd)

    def test_ff_byte_ends_an_openpnp_style_reader(self):
        c = DriverClient(self.sim.link)
        try:
            self.assertTrue(self.sim.ctl({"cmd": "fault", "fault": "inject_bytes", "hex": "ff"})["ok"])
            end = time.monotonic() + 2
            while c.reader_error is None and time.monotonic() < end:
                time.sleep(0.02)
            self.assertIsNotNone(c.reader_error)
            with self.assertRaises(ControllerError):
                c.send("M400")
        finally:
            c.close()


class TimeScale(unittest.TestCase):
    def test_time_scale_speeds_up_the_simulator_clock(self):
        sim = SimProcess("--time-scale", "4")
        try:
            fd = open_raw(sim.link)
            try:
                t0 = time.monotonic()
                os.write(fd, b"G4 P1600\n")
                self.assertEqual(read_until_ok(fd), ["ok"])
                self.assertAlmostEqual(time.monotonic() - t0, 0.4, delta=0.1)
            finally:
                os.close(fd)
        finally:
            sim.stop()


if __name__ == "__main__":
    unittest.main()
