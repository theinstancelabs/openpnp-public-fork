"""The pty transport, I/O log, control socket and CLI, through a real lumen-sim process (a few seconds)."""
import json
import os
import select
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest

from lumen_sim.control import ControlServer
from lumen_sim.core import Simulator
from lumen_sim.hostclient import ControllerError, DriverClient
from lumen_sim.pty_link import check_link_path
from lumen_sim.runner import Runner
from tests import goldens
from tests.simproc import PKG_ROOT, SimProcess, open_raw, read_until_ok


def read_some(fd, timeout):
    """Whatever arrives on fd within timeout seconds."""
    buf, end = b"", time.monotonic() + timeout
    while True:
        left = end - time.monotonic()
        if left <= 0:
            return buf
        r, _, _ = select.select([fd], [], [], left)
        if not r:
            return buf
        try:
            chunk = os.read(fd, 4096)
        except OSError:
            return buf
        if not chunk:
            return buf
        buf += chunk


def wait_for(cond, timeout=3.0):
    end = time.monotonic() + timeout
    while not cond() and time.monotonic() < end:
        time.sleep(0.02)
    return cond()


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

    def test_reopen_keeps_every_position_and_count(self):
        """BR-02's plain reconnect: the host closes and reopens the port; the controller is not reset."""
        fd = open_raw(self.sim.link)
        try:
            self.cmd(fd, "G1 X12.5 Z5 B800 F60000")
            self.cmd(fd, "M400")
            before = self.cmd(fd, "M114")
            self.assertEqual(before[0], "X:12.50 Y:0.00 Z:5.00 A:720.00 B:800.00 "
                                        "Count X:4000 Y:0 Z:200 A:3197 B:3552")
        finally:
            os.close(fd)
        time.sleep(0.1)
        fd = open_raw(self.sim.link)
        try:
            self.assertEqual(self.cmd(fd, "M114"), before)
        finally:
            self.cmd(fd, "G1 X0 Z0 B720 F60000")
            self.cmd(fd, "M400")
            os.close(fd)

    def test_physical_faults_and_the_head_through_the_socket(self):
        before = self.sim.ctl({"cmd": "state"})["state"]["head"]
        self.assertTrue(self.sim.ctl({"cmd": "fault", "fault": "lost_steps", "x": 0.3})["ok"])
        try:
            head = self.sim.ctl({"cmd": "state"})["state"]["head"]
            self.assertEqual(head["logical"], before["logical"])
            self.assertAlmostEqual(head["physical"]["X"] - before["physical"]["X"], 0.3, places=6)
            self.assertAlmostEqual(head["offset"]["X"] - before["offset"]["X"], 0.3, places=6)
        finally:
            self.assertTrue(self.sim.ctl({"cmd": "fault", "fault": "lost_steps", "x": -0.3})["ok"])

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
        self.assertFalse(os.path.lexists(self.sim.link))  # gone, as an unplugged port: its pts number is free
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


class HostClose(unittest.TestCase):
    """A USB port's buffers go with the host's last close: nothing the host left unread reaches the next host."""

    @classmethod
    def setUpClass(cls):
        cls.sim = SimProcess()

    @classmethod
    def tearDownClass(cls):
        cls.sim.stop()

    def test_reply_left_unread_at_close_is_not_seen_by_the_next_host(self):
        fd = open_raw(self.sim.link)
        os.write(fd, b"M105\n")
        time.sleep(0.1)  # 'ok T:0' is in the pty by now, unread
        os.close(fd)
        time.sleep(0.1)
        fd = open_raw(self.sim.link)
        try:
            self.assertEqual(read_some(fd, 0.3), b"")
            os.write(fd, b"M400\n")
            self.assertEqual(read_until_ok(fd), ["ok"])
        finally:
            os.close(fd)
        with open(self.sim.io_log, encoding="latin-1") as f:
            self.assertRegex(f.read(), r"# host closed, 7 unread bytes dropped\n")

    def test_output_while_frozen_and_closed_is_dropped(self):
        fd = open_raw(self.sim.link)
        os.write(fd, b"G4 P300\n")
        time.sleep(0.05)
        self.assertTrue(self.sim.ctl({"cmd": "fault", "fault": "frozen", "on": True})["ok"])
        os.close(fd)
        self.assertTrue(wait_for(lambda: not self.sim.ctl({"cmd": "state"})["state"]["host_open"], 1.0))
        time.sleep(0.4)  # the G4's ok comes while no host has the port
        fd = open_raw(self.sim.link)
        try:
            self.assertTrue(self.sim.ctl({"cmd": "clear"})["ok"])
            self.assertEqual(read_some(fd, 0.3), b"")
            os.write(fd, b"M400\n")
            self.assertEqual(read_until_ok(fd), ["ok"])
        finally:
            os.close(fd)


class RunnerHostClose(unittest.TestCase):
    """In process, on a pty of its own: bytes still waiting to be written go when the host closes."""

    def test_pending_bytes_are_dropped_when_the_host_closes(self):
        tmp = tempfile.mkdtemp(prefix="lumen-sim-test-")
        r = Runner(Simulator(), os.path.join(tmp, "ttyLUMEN"))
        fd = None
        try:
            r.link.open()
            fd = open_raw(r.link.link)
            r._service()
            self.assertTrue(r.link.host_open)
            r.pending = b"stale\n"  # as when the host stopped draining
            os.close(fd)
            r._service()
            self.assertEqual(r.pending, b"")
            fd = open_raw(r.link.link)
            r._service()
            r._service()
            self.assertEqual(read_some(fd, 0.1), b"")
        finally:
            if fd is not None:
                os.close(fd)
            r.shutdown()
            shutil.rmtree(tmp, ignore_errors=True)


class LinkLifecycle(unittest.TestCase):
    """The link exists exactly while lumen-sim's pty does (a freed /dev/pts/N is the kernel's to hand out again),
    and its name can never fall back to a real device in jSerialComm's getCommPort."""

    @classmethod
    def setUpClass(cls):
        cls.sim = SimProcess()

    @classmethod
    def tearDownClass(cls):
        cls.sim.stop()

    def state(self):
        return self.sim.ctl({"cmd": "state"})["state"]

    def assert_answers(self, link):
        fd = open_raw(link)
        try:
            os.write(fd, b"M400\n")
            self.assertEqual(read_until_ok(fd), ["ok"])
        finally:
            os.close(fd)

    def test_disconnect_until_power_cycle_has_no_link(self):
        self.assertTrue(self.sim.ctl({"cmd": "fault", "fault": "disconnect", "down_s": None})["ok"])
        self.assertFalse(os.path.lexists(self.sim.link))
        st = self.state()
        self.assertTrue(st["link_down"])
        self.assertIsNone(st["pts"])
        self.assertTrue(self.sim.ctl({"cmd": "power_cycle"})["ok"])
        self.assertEqual(os.path.realpath(self.sim.link), self.state()["pts"])
        self.assert_answers(self.sim.link)

    def test_power_cycle_to_a_new_link_removes_the_old_one(self):
        new = os.path.join(self.sim.dir, "ttyLUMEN1")
        self.assertTrue(self.sim.ctl({"cmd": "power_cycle", "link": new})["ok"])
        self.assertFalse(os.path.lexists(self.sim.link))
        self.assertEqual(os.path.realpath(new), self.state()["pts"])
        self.assert_answers(new)
        self.assertTrue(self.sim.ctl({"cmd": "power_cycle", "link": self.sim.link})["ok"])
        self.assertFalse(os.path.lexists(new))
        self.assert_answers(self.sim.link)

    def test_power_cycle_with_a_down_time_keeps_the_port_absent(self):
        boots = self.state()["boots"]
        t0 = time.monotonic()
        self.assertTrue(self.sim.ctl({"cmd": "power_cycle", "down_s": 0.5})["ok"])
        self.assertFalse(os.path.lexists(self.sim.link))
        st = self.state()
        self.assertTrue(st["link_down"])
        self.assertEqual(st["boots"], boots + 1)
        self.assertTrue(wait_for(lambda: os.path.lexists(self.sim.link)))
        self.assertGreater(time.monotonic() - t0, 0.45)
        fd = open_raw(self.sim.link)
        try:
            os.write(fd, b"M114\n")
            self.assertEqual(read_until_ok(fd)[0],
                             "X:0.00 Y:0.00 Z:0.00 A:720.00 B:720.00 Count X:0 Y:0 Z:0 A:3197 B:3197")
        finally:
            os.close(fd)

    def test_power_cycle_to_a_device_like_or_unusable_link_is_refused(self):
        boots, pts = self.state()["boots"], os.path.realpath(self.sim.link)
        for link in (os.path.join(self.sim.dir, "ttyACM1"), os.path.join(self.sim.dir, "no-such-dir", "ttyLUMEN")):
            reply = self.sim.ctl({"cmd": "power_cycle", "link": link})
            self.assertFalse(reply["ok"], link)
            self.assertFalse(os.path.lexists(link))
        self.assertEqual(self.state()["boots"], boots)  # refused before the reset
        self.assertEqual(os.path.realpath(self.sim.link), pts)
        self.assert_answers(self.sim.link)

    def test_malformed_requests_through_the_socket_leave_it_running(self):
        for req in ({"cmd": "fault", "fault": "no_ok", "match": "^G4("},
                    {"cmd": "set", "rs485": ["FF00", "0017"]},
                    {"cmd": "fault", "fault": "no_ok", "count": "2"},
                    {"cmd": "fault", "fault": "delay_ok", "random": "ab"},
                    [1, 2]):
            reply = self.sim.ctl(req)
            self.assertFalse(reply["ok"], req)
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as c:
            c.settimeout(5)
            c.connect(self.sim.sock)
            c.sendall(b"not json\n")
            self.assertFalse(json.loads(c.recv(65536))["ok"])
        self.assertIsNone(self.sim.proc.poll())
        self.assertEqual(self.state()["faults"], [])
        self.assert_answers(self.sim.link)


class DfuLink(unittest.TestCase):
    def test_dfu_removes_the_link_until_a_power_cycle(self):
        sim = SimProcess("--personality", "il-safety")
        try:
            fd = open_raw(sim.link)
            os.write(fd, b"M997\n")
            os.close(fd)
            self.assertTrue(wait_for(lambda: not os.path.lexists(sim.link)))
            self.assertTrue(sim.ctl({"cmd": "power_cycle"})["ok"])
            self.assertEqual(os.path.realpath(sim.link), sim.ctl({"cmd": "state"})["state"]["pts"])
        finally:
            sim.stop()


class LinkNames(unittest.TestCase):
    def test_check_link_path(self):
        # names are checked before anything is stat'ed: a device-like name never reaches the /dev lookup
        def never(path):
            raise AssertionError("stat'ed %s" % path)
        for bad in ("/x/ttyACM0", "/x/ttyUSB0", "/x/ttyS4", "/x/lumen", "/x/ttyLUMEN/ttyACM0", ""):
            with self.assertRaises(ValueError):
                check_link_path(bad, exists=never)
        self.assertEqual(check_link_path("/x/ttyLUMEN1", exists=lambda p: p == "/x"), "/x/ttyLUMEN1")
        with self.assertRaises(ValueError):  # jSerialComm would open /dev/<basename> while the link is missing
            check_link_path("/x/ttyLUMEN", exists=lambda p: p in ("/x", "/dev/ttyLUMEN"))
        with self.assertRaises(ValueError):  # ... or /dev/<path>
            check_link_path("/x/ttyLUMEN", exists=lambda p: p in ("/x", "/dev//x/ttyLUMEN"))
        with self.assertRaises(ValueError):
            check_link_path("/x/ttyLUMEN", exists=lambda p: False)  # no such directory

    def test_cli_refuses_a_device_like_link(self):
        tmp = tempfile.mkdtemp(prefix="lumen-sim-test-")
        try:
            link = os.path.join(tmp, "ttyACM0")
            out = subprocess.run([sys.executable, "-m", "lumen_sim", "--link", link], cwd=PKG_ROOT,
                                 capture_output=True, text=True, timeout=5)
            self.assertEqual(out.returncode, 2, out.stderr)
            self.assertIn("ttyLUMEN", out.stderr)
            self.assertFalse(os.path.lexists(link))
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


class ControlServerErrors(unittest.TestCase):
    def test_a_handler_error_is_a_refusal_not_a_crash(self):
        tmp = tempfile.mkdtemp(prefix="lumen-sim-test-")
        srv = ControlServer(os.path.join(tmp, "ctl.sock"))
        c = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            c.settimeout(5)
            c.connect(srv.path)
            srv.handle(srv.sock, None)  # accept
            c.sendall(b'{"cmd": "state"}\n')

            def boom(req):
                raise RuntimeError("handler gap")
            (conn,) = list(srv.clients)
            srv.handle(conn, boom)
            reply = json.loads(c.recv(65536))
            self.assertFalse(reply["ok"])
            self.assertIn("RuntimeError: handler gap", reply["error"])
        finally:
            c.close()
            srv.close()
            shutil.rmtree(tmp, ignore_errors=True)


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
