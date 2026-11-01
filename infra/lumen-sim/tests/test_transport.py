"""The pty transport, I/O log, control socket and CLI, through a real lumen-sim process (a few seconds)."""
import json
import os
import select
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest

from lumen_sim.control import ControlServer
from lumen_sim.core import Simulator
from lumen_sim.hostclient import ControllerError, DriverClient
from lumen_sim.pty_link import PtyLink, check_link_path
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


def temp_dir(test):
    """A fresh directory, removed after the test whatever happens."""
    tmp = tempfile.mkdtemp(prefix="lumen-sim-test-")
    test.addCleanup(shutil.rmtree, tmp, True)
    return tmp


def in_process_runner(test, clock=None, **kw):
    """A Runner on a pty of its own in a temp directory, shut down after the test whatever happens."""
    tmp = temp_dir(test)
    r = Runner(Simulator(), os.path.join(tmp, "ttyLUMEN"), clock=clock or time.monotonic, **kw)
    test.addCleanup(r.shutdown)
    return r


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
        self.addCleanup(sim.stop)
        fd = open_raw(sim.link)
        self.addCleanup(os.close, fd)
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
        fd2 = open_raw(sim.link)
        self.addCleanup(os.close, fd2)
        os.write(fd2, b"M114\n")
        self.assertEqual(read_until_ok(fd2),
                         ["X:0.00 Y:0.00 Z:0.00 A:720.00 B:720.00 Count X:0 Y:0 Z:0 A:3197 B:3197", "ok"])

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
        r = in_process_runner(self)
        r.link.open()
        fd = open_raw(r.link.link)
        r._service()
        self.assertTrue(r.link.host_open)
        r.pending = b"stale\n"  # as when the host stopped draining
        os.close(fd)
        r._service()
        self.assertEqual(r.pending, b"")
        fd = open_raw(r.link.link)
        self.addCleanup(os.close, fd)
        r._service()
        r._service()
        self.assertEqual(read_some(fd, 0.1), b"")


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


class RunnerFaults(unittest.TestCase):
    """In process, on a fake clock: transport failures are logged and the loop goes on."""

    def runner(self, **kw):
        self.clock = [0.0]
        return in_process_runner(self, clock=lambda: self.clock[0], **kw)

    def test_a_link_that_cannot_come_back_at_its_new_path_comes_back_at_the_old_one(self):
        r = self.runner()
        r.link.open()
        old = r.link.link
        other = os.path.join(os.path.dirname(old), "other")
        os.mkdir(other)
        new = os.path.join(other, "ttyLUMEN1")
        self.assertTrue(r._handle_control({"cmd": "power_cycle", "link": new, "down_s": 1.0})["ok"])
        os.rmdir(other)  # gone during the down time
        self.clock[0] = 1.5
        r._service()     # must not raise: that would end the process
        self.assertEqual(r.link.link, old)
        self.assertEqual(os.path.realpath(old), r.link.pts)
        self.assertFalse(r.transport_state()["link_down"])

    def test_a_link_that_cannot_come_back_anywhere_is_retried(self):
        r = self.runner()
        r.link.open()
        link = r.link.link
        tmp = os.path.dirname(link)
        self.assertTrue(r._handle_control({"cmd": "fault", "fault": "disconnect", "down_s": 1.0})["ok"])
        shutil.rmtree(tmp)  # the session directory went away while the port was down
        self.clock[0] = 1.5
        r._service()
        self.assertTrue(r.transport_state()["link_down"])
        os.mkdir(tmp)
        self.clock[0] = 5.0
        r._service()
        self.assertFalse(r.transport_state()["link_down"])
        self.assertEqual(os.path.realpath(link), r.link.pts)

    def test_a_power_cycle_ends_frozen(self):
        r = self.runner()
        r.link.open()
        self.assertTrue(r._handle_control({"cmd": "fault", "fault": "frozen", "on": True})["ok"])
        self.assertTrue(r.frozen)
        reply = r._handle_control({"cmd": "power_cycle"})
        self.assertTrue(reply["ok"], reply)
        self.assertFalse(reply["frozen"])

    def test_the_io_log_takes_any_byte_a_model_error_quotes(self):
        """A line the model cannot run is noted in the I/O log with its bytes, whatever they are; the board answers."""
        tmp = temp_dir(self)
        log = os.path.join(tmp, "io.log")
        r = self.runner(io_log=log)
        r.sim.receive(b"M203 X0\nG1 X10 \xe9\nM105\n", 0.0)
        r.sim.run_until(1.0)
        self.assertFalse(r.sim.fw.halted, r.sim.fw.halt_reason)
        self.assertIn(b"ok T:0\n", b"".join(d for _, d in r.sim.take_output(1.0)))
        r.io.f.flush()
        with open(log, encoding="ascii") as f:
            self.assertRegex(f.read(), r"# sim error in 'G1 X10 \\xe9'")


class OneInstancePerPath(unittest.TestCase):
    """Two lumen-sims on the same paths would silently swap controllers under a host: the second is refused."""

    def test_a_link_held_by_another_instance_is_refused(self):
        tmp = temp_dir(self)
        path = os.path.join(tmp, "ttyLUMEN")
        a = PtyLink(path)
        self.addCleanup(a.release)
        a.open()
        with self.assertRaisesRegex(ValueError, "in use"):
            PtyLink(path)
        self.assertEqual(os.path.realpath(path), a.pts)
        a.release()
        b = PtyLink(path)
        self.addCleanup(b.release)
        b.open()
        self.assertEqual(os.path.realpath(path), b.pts)

    def test_a_power_cycle_cannot_move_onto_another_instances_link(self):
        tmp = temp_dir(self)
        a, b = PtyLink(os.path.join(tmp, "ttyLUMEN0")), PtyLink(os.path.join(tmp, "ttyLUMEN1"))
        self.addCleanup(a.release)
        self.addCleanup(b.release)
        a.open()
        b.open()
        with self.assertRaisesRegex(ValueError, "in use"):
            a.open(b.link)
        self.assertEqual(os.path.realpath(b.link), b.pts)

    def test_close_leaves_a_link_that_is_no_longer_its_own(self):
        tmp = temp_dir(self)
        a = PtyLink(os.path.join(tmp, "ttyLUMEN"))
        self.addCleanup(a.release)
        a.open()
        os.symlink("/dev/null", a.link + ".other")
        os.replace(a.link + ".other", a.link)
        a.close()
        self.assertEqual(os.readlink(a.link), "/dev/null")

    def test_a_live_control_socket_is_refused_and_a_stale_one_replaced(self):
        tmp = temp_dir(self)
        path = os.path.join(tmp, "ctl.sock")
        a = ControlServer(path)
        self.addCleanup(a.close)
        with self.assertRaisesRegex(ValueError, "in use"):
            ControlServer(path)
        self.assertTrue(os.path.exists(path))
        a.close()
        stale = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        stale.bind(path)  # a socket file with nobody listening, as a killed lumen-sim leaves it
        stale.close()
        b = ControlServer(path)
        self.addCleanup(b.close)
        self.assertTrue(os.path.exists(path))

    def test_a_control_path_that_is_not_a_socket_is_left_alone(self):
        tmp = temp_dir(self)
        path = os.path.join(tmp, "ctl.sock")
        with open(path, "w") as f:
            f.write("keep me")
        with self.assertRaises(ValueError):
            ControlServer(path)
        with open(path) as f:
            self.assertEqual(f.read(), "keep me")

    def test_close_leaves_a_socket_that_is_no_longer_its_own(self):
        tmp = temp_dir(self)
        path = os.path.join(tmp, "ctl.sock")
        a = ControlServer(path)
        self.addCleanup(a.close)
        os.unlink(path)
        b = ControlServer(path)
        self.addCleanup(b.close)
        a.close()
        self.assertTrue(os.path.exists(path))
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as c:
            c.connect(path)

    def test_a_second_process_on_the_same_paths_exits_and_the_first_keeps_serving(self):
        sim = SimProcess()
        self.addCleanup(sim.stop)
        pts = os.path.realpath(sim.link)
        for link in (sim.link, os.path.join(sim.dir, "ttyLUMEN9")):  # the same link; the same socket
            out = subprocess.run([sys.executable, "-m", "lumen_sim", "--link", link, "--control", sim.sock],
                                 cwd=PKG_ROOT, capture_output=True, text=True, timeout=10)
            self.assertNotEqual(out.returncode, 0, link)
            self.assertIn("in use", out.stderr)
        self.assertFalse(os.path.lexists(os.path.join(sim.dir, "ttyLUMEN9")))
        self.assertEqual(os.path.realpath(sim.link), pts)
        self.assertTrue(sim.ctl({"cmd": "state"})["ok"])


class Signals(unittest.TestCase):
    def test_sighup_stops_it_cleanly(self):
        """A closed terminal or ssh session sends SIGHUP: the link to the freed /dev/pts/N must go too."""
        sim = SimProcess()
        self.addCleanup(sim.stop)
        sim.proc.send_signal(signal.SIGHUP)
        self.assertEqual(sim.proc.wait(timeout=5), 0)
        self.assertFalse(os.path.lexists(sim.link))
        self.assertFalse(os.path.exists(sim.sock))


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

    def test_a_client_that_closed_is_dropped(self):
        """Else select() reports its end of file for ever and the loop spins."""
        tmp = temp_dir(self)
        srv = ControlServer(os.path.join(tmp, "ctl.sock"))
        self.addCleanup(srv.close)
        c = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        c.connect(srv.path)
        srv.handle(srv.sock, None)  # accept
        (conn,) = list(srv.clients)
        c.close()
        srv.handle(conn, None)
        self.assertEqual(srv.clients, {})


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
