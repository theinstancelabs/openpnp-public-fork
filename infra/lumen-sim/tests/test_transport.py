"""The pty transport, I/O log, control socket and CLI, through a real lumen-sim process (a few seconds)."""
import errno
import json
import os
import re
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
from lumen_sim.pty_link import PtyLink, check_link_path, lock_link, remove_stale_link
from lumen_sim.runner import Runner
from tests import goldens
from tests.hang import within
from tests.simproc import PKG_ROOT, SimProcess, open_raw, read_until_ok


DEEP_JSON = b"[" * 100000 + b"]" * 100000 + b"\n"  # 200 kB, under the 1 MiB line limit


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
    """True as soon as cond() reads true (never read again: see WaitFor), False after timeout s."""
    end = time.monotonic() + timeout
    while True:
        if cond():
            return True
        if time.monotonic() >= end:
            return False
        time.sleep(0.02)


def temp_dir(test):
    """A fresh directory, removed after the test whatever happens."""
    tmp = tempfile.mkdtemp(prefix="lumen-sim-test-")
    test.addCleanup(shutil.rmtree, tmp, True)
    return tmp


def in_process_runner(test, clock=None, personality="stock", **kw):
    """A Runner on a pty of its own in a temp directory, shut down after the test whatever happens."""
    tmp = temp_dir(test)
    r = Runner(Simulator(personality), os.path.join(tmp, "ttyLUMEN"), clock=clock or time.monotonic, **kw)
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


class SimCapOption(unittest.TestCase):
    """BR-47: '--sim-cap' puts 'Cap:SIMULATOR:lumen-sim' into M115 on the pty (default off: PtyTransport's golden)."""

    def test_the_cli_flag_sends_the_line_last_before_the_ok(self):
        sim = SimProcess("--sim-cap")
        self.addCleanup(sim.stop)
        self.assertTrue(sim.ctl({"cmd": "state"})["state"]["sim_cap"])
        fd = open_raw(sim.link)
        try:
            os.write(fd, b"M115\n")
            want = [t for _, t in goldens.replies(goldens.load("m115"), "M115")]
            self.assertEqual(read_until_ok(fd), want[:-1] + ["Cap:SIMULATOR:lumen-sim", "ok"])
        finally:
            os.close(fd)

    def test_ctl_set_turns_it_on(self):
        sim = SimProcess()
        self.addCleanup(sim.stop)
        self.assertFalse(sim.ctl({"cmd": "state"})["state"]["sim_cap"])
        out = subprocess.run([sys.executable, "-m", "lumen_sim", "ctl", "--control", sim.sock, "set",
                              '{"sim_cap": true}'], cwd=PKG_ROOT, capture_output=True, text=True, timeout=10)
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
        fd = open_raw(sim.link)
        try:
            os.write(fd, b"M115\n")
            self.assertEqual(read_until_ok(fd)[-2:], ["Cap:SIMULATOR:lumen-sim", "ok"])
        finally:
            os.close(fd)


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


    def test_ctl_power_cycle_takes_a_link_relative_to_its_own_directory(self):
        """Review round 3: lumen-sim resolved it against its own working directory (in uinav, the source tree)."""
        sim = SimProcess()
        self.addCleanup(sim.stop)
        out = subprocess.run([sys.executable, "-m", "lumen_sim", "ctl", "--control", sim.sock, "power-cycle",
                              "--link", "ttyLUMEN1"], cwd=sim.dir, env=dict(os.environ, PYTHONPATH=PKG_ROOT),
                             capture_output=True, text=True, timeout=10)
        self.assertEqual(out.returncode, 0, out.stderr + out.stdout)
        new = os.path.join(sim.dir, "ttyLUMEN1")
        self.assertEqual(json.loads(out.stdout)["link"], new)
        self.assertEqual(os.path.realpath(new), sim.ctl({"cmd": "state"})["state"]["pts"])
        self.assertFalse(os.path.lexists(os.path.join(PKG_ROOT, "ttyLUMEN1")))


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
        # long enough that the checks below run while it is down, however loaded the host
        self.assertTrue(self.sim.ctl({"cmd": "fault", "fault": "disconnect", "down_s": 2.0})["ok"])
        self.assertTrue(self.sim.ctl({"cmd": "state"})["state"]["link_down"])
        self.assertFalse(os.path.lexists(self.sim.link))  # gone, as an unplugged port: its pts number is free
        try:
            self.assertEqual(os.read(fd, 100), b"")
        except OSError as e:
            self.assertEqual(e.errno, 5)
        os.close(fd)
        self.assertTrue(wait_for(lambda: not self.sim.ctl({"cmd": "state"})["state"]["link_down"], 10.0))
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

    def host_open(self):
        return self.sim.ctl({"cmd": "state"})["state"]["host_open"]

    def test_reply_left_unread_at_close_is_not_seen_by_the_next_host(self):
        fd = open_raw(self.sim.link)
        self.assertTrue(wait_for(self.host_open))
        os.write(fd, b"M105\n")
        self.assertEqual(select.select([fd], [], [], 5.0)[0], [fd])  # 'ok T:0' is in the pty, unread
        os.close(fd)
        self.assertTrue(wait_for(lambda: not self.host_open()))  # lumen-sim saw the close (and flushed)
        fd = open_raw(self.sim.link)
        try:
            self.assertEqual(read_some(fd, 0.3), b"")
            os.write(fd, b"M400\n")
            self.assertEqual(read_until_ok(fd), ["ok"])
        finally:
            os.close(fd)
        def logged():
            with open(self.sim.io_log, encoding="latin-1") as f:
                return re.search(r"# host closed, 7 unread bytes dropped\n", f.read())
        self.assertTrue(wait_for(logged))

    def test_output_while_frozen_and_closed_is_dropped(self):
        fd = open_raw(self.sim.link)
        read = self.sim.ctl({"cmd": "state"})["state"]["lines_read"]
        os.write(fd, b"G4 P300\n")
        self.assertTrue(wait_for(lambda: self.sim.ctl({"cmd": "state"})["state"]["lines_read"] == read + 1))
        self.assertTrue(self.sim.ctl({"cmd": "fault", "fault": "frozen", "on": True})["ok"])
        os.close(fd)
        self.assertTrue(wait_for(lambda: not self.host_open()))
        # the G4's ok comes while no host has the port: wait until it is done, and one more request, so the
        # loop has passed its output step (and dropped the ok) since
        self.assertTrue(wait_for(lambda: self.sim.ctl({"cmd": "state"})["state"]["ring"] == []))
        self.sim.ctl({"cmd": "state"})
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
        self.assertTrue(self.sim.ctl({"cmd": "power_cycle", "down_s": 1.5})["ok"])  # room for a loaded host
        self.assertFalse(os.path.lexists(self.sim.link))
        st = self.state()
        self.assertTrue(st["link_down"])
        self.assertEqual(st["boots"], boots + 1)
        self.assertTrue(wait_for(lambda: os.path.lexists(self.sim.link), 10.0))
        self.assertGreater(time.monotonic() - t0, 1.45)
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
        for line in (b"not json\n", DEEP_JSON):  # DEEP_JSON: RecursionError, not a ValueError (review round 3)
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as c:
                c.settimeout(5)
                c.connect(self.sim.sock)
                c.sendall(line)
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

    def test_names_of_lumen_sim_s_own_files_are_refused(self):
        """Review round 3: a link named <link>.lock or <link>.tmp<pid> would replace a lock or a link in the making."""
        def never(path):
            raise AssertionError("stat'ed %s" % path)
        for bad in ("/x/ttyLUMEN.lock", "/x/ttyLUMEN1.lock", "/x/ttyLUMEN.tmp4242", "/x/ttyLUMEN.", "/x/ttyLUMEN 1"):
            with self.assertRaises(ValueError):
                check_link_path(bad, exists=never)

    def test_an_existing_path_that_is_not_a_link_to_a_pty_is_refused(self):
        """Publishing replaces what is at the path: only a link to a /dev/pts/N (a killed lumen-sim's) may go."""
        tmp = temp_dir(self)
        p = lambda name: os.path.join(tmp, name)  # noqa: E731
        with open(p("ttyLUMEN"), "w") as f:
            f.write("keep")
        os.symlink(os.devnull, p("ttyLUMEN1"))
        os.mkdir(p("ttyLUMEN2"))
        os.symlink("/dev/pts/999999", p("ttyLUMEN3"))  # never opened: only its name is read
        for bad in ("ttyLUMEN", "ttyLUMEN1", "ttyLUMEN2"):
            with self.assertRaisesRegex(ValueError, "not a link to a pty"):
                check_link_path(p(bad))
        self.assertEqual(check_link_path(p("ttyLUMEN3")), p("ttyLUMEN3"))
        with open(p("ttyLUMEN")) as f:
            self.assertEqual(f.read(), "keep")
        self.assertEqual(os.readlink(p("ttyLUMEN1")), os.devnull)

    def test_cli_refuses_non_finite_numbers(self):
        """Review round 3: --time-scale nan or inf passed the '<= 0' check and spun a core at 100 %."""
        tmp = temp_dir(self)
        link = os.path.join(tmp, "ttyLUMEN")
        for args in (["--time-scale", "nan"], ["--time-scale", "inf"], ["--park", "nan,5,31.5"],
                     ["--park", "5,inf,31.5"], ["--surface-z", "nan"]):
            out = subprocess.run([sys.executable, "-m", "lumen_sim", "--link", link] + args, cwd=PKG_ROOT,
                                 capture_output=True, text=True, timeout=10)
            self.assertEqual(out.returncode, 2, (args, out.stderr))
            self.assertFalse(os.path.lexists(link), args)

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
        shutil.rmtree(other)  # gone during the down time (with the lock the power cycle took there)
        self.clock[0] = 1.5
        r._service()     # must not raise: that would end the process
        self.assertEqual(r.link.link, old)
        self.assertEqual(os.path.realpath(old), r.link.pts)
        self.assertFalse(r.transport_state()["link_down"])

    def test_a_power_cycle_with_a_new_link_and_a_down_time_comes_back_at_the_new_link(self):
        r = self.runner()
        r.link.open()
        old = r.link.link
        new = os.path.join(os.path.dirname(old), "ttyLUMEN1")
        self.assertTrue(r._handle_control({"cmd": "power_cycle", "link": new, "down_s": 1.0})["ok"])
        self.assertFalse(os.path.lexists(old))
        self.assertFalse(os.path.lexists(new))  # absent while the board restarts
        self.clock[0] = 1.5
        r._service()
        self.assertEqual(r.link.link, new)
        self.assertEqual(os.path.realpath(new), r.link.pts)
        self.assertFalse(os.path.lexists(old))

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

    def test_a_power_cycle_onto_a_link_another_instance_holds_is_refused_before_the_reset(self):
        """Review round 3: it used to reset the board, then fail to publish the port and reply ok:false."""
        r = self.runner()
        r.link.open()
        old, pts = r.link.link, r.link.pts
        other = PtyLink(os.path.join(os.path.dirname(old), "ttyLUMEN1"))
        self.addCleanup(other.release)
        other.open()
        reply = r._handle_control({"cmd": "power_cycle", "link": other.link})
        self.assertFalse(reply["ok"], reply)
        self.assertIn("in use", reply["error"])
        self.assertEqual(r.sim.state(0.0)["boots"], 1)  # nothing changed
        self.assertEqual((r.link.link, r.link.pts), (old, pts))
        self.assertEqual(os.path.realpath(other.link), other.pts)

    def test_a_power_cycle_holds_its_new_link_through_the_down_time(self):
        r = self.runner()
        r.link.open()
        new = os.path.join(os.path.dirname(r.link.link), "ttyLUMEN1")
        self.assertTrue(r._handle_control({"cmd": "power_cycle", "link": new, "down_s": 1.0})["ok"])
        with self.assertRaisesRegex(ValueError, "in use"):
            PtyLink(new)  # another lumen-sim cannot take the path while this board restarts
        self.clock[0] = 1.5
        r._service()
        self.assertEqual(r.link.link, new)
        self.assertEqual(os.path.realpath(new), r.link.pts)

    def test_a_refused_or_replaced_power_cycle_lets_go_of_its_new_link(self):
        r = self.runner()
        r.link.open()
        one, two = (os.path.join(os.path.dirname(r.link.link), n) for n in ("ttyLUMEN1", "ttyLUMEN2"))
        self.assertFalse(r._handle_control({"cmd": "power_cycle", "link": one, "down_s": "x"})["ok"])
        os.close(lock_link(one))
        self.assertTrue(r._handle_control({"cmd": "power_cycle", "link": one, "down_s": 1.0})["ok"])
        self.assertTrue(r._handle_control({"cmd": "power_cycle", "link": two, "down_s": 1.0})["ok"])
        os.close(lock_link(one))  # the second power cycle's path replaced the first one's
        self.clock[0] = 2.5
        r._service()
        self.assertEqual(os.path.realpath(two), r.link.pts)

    def test_a_port_that_cannot_be_published_after_the_reset_is_reported_with_the_reset(self):
        """Review round 3: the board was reset either way, so ok:false ('nothing happened') was wrong; the reply is
        ok with link_error and the link where the port really is."""
        r = self.runner()
        r.link.open()
        old = r.link.link
        new = os.path.join(os.path.dirname(old), "ttyLUMEN1")
        real_open = r.link.open

        def no_pty_at_new(link=None):
            if link:
                raise OSError(errno.EAGAIN, "out of ptys")
            return real_open(link)
        r.link.open = no_pty_at_new
        reply = r._handle_control({"cmd": "power_cycle", "link": new})
        self.assertTrue(reply["ok"], reply)
        self.assertIn("out of ptys", reply["link_error"])
        self.assertEqual(reply["link"], old)
        self.assertEqual(os.path.realpath(old), reply["pts"])
        self.assertEqual(r.sim.state(0.0)["boots"], 2)
        os.close(lock_link(new))  # its claim on the new path is let go

    def test_a_relative_power_cycle_link_is_refused(self):
        """Review round 3: it was resolved against lumen-sim's working directory, not the client's."""
        r = self.runner()
        r.link.open()
        work = temp_dir(self)
        self.addCleanup(os.chdir, os.getcwd())
        os.chdir(work)
        reply = r._handle_control({"cmd": "power_cycle", "link": "ttyLUMEN1"})
        self.assertFalse(reply["ok"], reply)
        self.assertIn("absolute", reply["error"])
        self.assertEqual(os.listdir(work), [])
        self.assertEqual(r.sim.state(0.0)["boots"], 1)

    def test_a_power_cycle_onto_a_lock_or_temp_file_is_refused(self):
        r = self.runner()
        r.link.open()
        lock = r.link.link + ".lock"
        for link in (lock, r.link.link + ".tmp%d" % os.getpid()):
            reply = r._handle_control({"cmd": "power_cycle", "link": link})
            self.assertFalse(reply["ok"], link)
        self.assertTrue(os.path.isfile(lock) and not os.path.islink(lock))
        self.assertEqual(r.sim.state(0.0)["boots"], 1)
        self.assertEqual(os.path.realpath(r.link.link), r.link.pts)

    def test_frozen_off_reads_the_port_again(self):
        r = self.runner()
        r.link.open()
        host = open_raw(r.link.link)
        self.addCleanup(os.close, host)
        self.assertTrue(r._handle_control({"cmd": "fault", "fault": "frozen", "on": True})["ok"])
        r._service()
        os.write(host, b"M400\n")
        r._service()
        self.assertEqual(read_some(host, 0.1), b"")
        self.assertTrue(r._handle_control({"cmd": "fault", "fault": "frozen", "on": False})["ok"])
        self.assertFalse(r.transport_state()["frozen"])
        r._service()
        self.assertEqual(read_some(host, 1.0), b"ok\n")

    def test_bytes_the_host_had_not_taken_are_dropped_by_a_disconnect(self):
        r = self.runner()
        r.link.open()
        host = open_raw(r.link.link)
        r._service()
        self.assertTrue(r.link.host_open)
        self.assertTrue(r._handle_control({"cmd": "fault", "fault": "inject", "text": "x" * 300000})["ok"])
        r._service()
        self.assertTrue(r.pending)  # more than the pty takes: the rest waits in lumen-sim
        self.assertTrue(r._handle_control({"cmd": "fault", "fault": "disconnect", "down_s": 1.0})["ok"])
        os.close(host)
        self.clock[0] = 1.5
        r._service()
        host = open_raw(r.link.link)
        self.addCleanup(os.close, host)
        r._service()
        r._service()
        self.assertEqual(read_some(host, 0.3), b"")  # the next host starts clean, as on a USB port

    def test_a_disconnect_does_not_bring_back_the_port_of_a_board_in_dfu(self):
        with within(self, 5):
            tmp = temp_dir(self)
            self.clock = [0.0]
            r = Runner(Simulator("il-safety"), os.path.join(tmp, "ttyLUMEN"), clock=lambda: self.clock[0])
            self.addCleanup(r.shutdown)
            r.link.open()
            r.sim.receive(b"M997\n", 0.0)
            self.clock[0] = 0.2  # past flashFirmware()'s 0.1 s for USB CDC: in DFU
            r._service()
            self.assertFalse(os.path.lexists(r.link.link))
            self.assertTrue(r._handle_control({"cmd": "fault", "fault": "disconnect", "down_s": 0.5})["ok"])
            self.clock[0] = 1.0
            r._service()
            # the bootloader has no serial port: down until a power cycle
            self.assertFalse(os.path.lexists(r.link.link))
            self.assertTrue(r.transport_state()["link_down"])
            self.assertTrue(r._handle_control({"cmd": "power_cycle"})["ok"])
            self.assertEqual(os.path.realpath(r.link.link), r.link.pts)

    def m997_over_the_pty(self, lines):
        """An il-safety board whose host wrote `lines` (M997 last), serviced at 0.05 s: the runner, and what the host
        reads."""
        r = self.runner(personality="il-safety")
        r.link.open()
        host = open_raw(r.link.link)
        self.addCleanup(os.close, host)
        os.write(host, lines)
        self.clock[0] = 0.05
        r._service()
        return r, read_some(host, 0.3)

    def test_m997_s_echo_reaches_the_host_before_the_port_goes(self):
        """flashFirmware() gives USB CDC ~0.1 s to send the echo before the reset into DFU (dfu_bootloader.cpp), and a
        host reads it on the board (firmware/verify_fw.py, D1). A pty master closed at once takes the bytes the host
        has not read with it."""
        with within(self, 5):
            r, got = self.m997_over_the_pty(b"M997\n")
            self.assertEqual(got, b"echo:M997: entering STM32 DFU bootloader (0483:df11)\n")
            self.assertTrue(os.path.lexists(r.link.link))
            self.clock[0] = 0.2
            r._service()
            self.assertFalse(os.path.lexists(r.link.link))

    def test_a_reply_due_before_m997_reaches_the_host_too(self):
        with within(self, 5):
            _, got = self.m997_over_the_pty(b"M105\nM997\n")
            self.assertEqual(got, b"ok T:0\necho:M997: entering STM32 DFU bootloader (0483:df11)\n")

    def test_a_late_wake_still_sends_the_m997_echo_before_the_port_goes(self):
        """A loaded host can wake the runner late: the drain counts from when the runner sees the halt, not from the
        halt, else the port goes before the echo is written (here M997 runs at 0.05 behind a G4, the runner wakes at
        0.3, past 0.05 + flashFirmware()'s 0.1 s). Also with an injection due at 0.15, so run_until's injection loop
        sees the halt (the drain counts from the runner's 0.3, not the injection's 0.15; the halt drops the injection)."""
        for inject in (None, {"cmd": "fault", "fault": "inject", "text": "x", "after_s": 0.15}):
            with self.subTest(inject=inject), within(self, 5):
                r = self.runner(personality="il-safety")
                r.link.open()
                host = open_raw(r.link.link)
                self.addCleanup(os.close, host)
                if inject:
                    self.assertTrue(r._handle_control(inject)["ok"])
                os.write(host, b"G4 P50\nM997\n")
                r._service()  # read at 0.0: the dwell ends at 0.05, M997 behind it
                self.clock[0] = 0.3
                r._service()
                self.assertEqual(read_some(host, 0.3), b"ok\necho:M997: entering STM32 DFU bootloader (0483:df11)\n")
                self.clock[0] = 0.35
                r._service()
                self.assertTrue(os.path.lexists(r.link.link))  # 0.1 s from 0.3
                self.clock[0] = 0.45
                r._service()
                self.assertFalse(os.path.lexists(r.link.link))

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

    def test_a_lock_that_is_a_symlink_is_never_followed(self):
        """Review round 3: lock files were opened through symlinks, so a planted <link>.lock could make lumen-sim (or
        its guardian) open a file, or a device, it does not own."""
        tmp = temp_dir(self)
        target = os.path.join(tmp, "victim")
        with open(target, "w") as f:
            f.write("keep")
        link = os.path.join(tmp, "ttyLUMEN")
        os.symlink(target, link + ".lock")
        with self.assertRaisesRegex(ValueError, "lock"):
            PtyLink(link)
        os.symlink("/dev/pts/999999", link)  # never opened: only its name is read
        remove_stale_link(link, "/dev/pts/999999")
        self.assertTrue(os.path.islink(link))  # a lock lumen-sim did not make: the link is left for a person
        with open(target) as f:
            self.assertEqual(f.read(), "keep")

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


def alive(pid):
    """A process that exists and is not a zombie (its new parent may reap it late) or dead (X: being reaped)."""
    try:
        with open("/proc/%d/stat" % pid) as f:
            return f.read().rsplit(")", 1)[1].split()[0] not in ("Z", "X")
    except (FileNotFoundError, ProcessLookupError):
        return False


class WaitFor(unittest.TestCase):
    def test_a_condition_seen_true_once_is_met(self):
        """A dead process can read alive a moment later (its PID in /proc's X state while it is reaped, or reused):
        a second reading made wait_for say False 44 ms into PR #16's gate (run 41607411975)."""
        readings = iter([False, True, False])
        self.assertTrue(wait_for(lambda: next(readings), timeout=1.0))

    def test_a_condition_never_true_times_out(self):
        t0 = time.monotonic()
        self.assertFalse(wait_for(lambda: False, timeout=0.1))
        self.assertGreaterEqual(time.monotonic() - t0, 0.1)


class LinkGuardian(unittest.TestCase):
    """A SIGKILLed lumen-sim cannot remove its link, which then names a freed /dev/pts/N. On the lab computer the next
    pty may be a real machine's G-code input (Klipper's klippy.serial is one), and a host reconnecting through the
    stale link would stream G-code into it. A guardian process removes the link (review round 2)."""

    def killed(self, sim):
        guard = sim.ctl({"cmd": "state"})["state"]["guard_pid"]
        self.assertTrue(alive(guard))
        sim.proc.kill()  # as the OOM killer, or a stop that escalated to SIGKILL
        sim.proc.wait(timeout=5)
        return guard

    def test_a_sigkilled_lumen_sim_leaves_no_link(self):
        sim = SimProcess()
        self.addCleanup(sim.stop)
        guard = self.killed(sim)
        self.assertTrue(wait_for(lambda: not os.path.lexists(sim.link)))
        self.assertTrue(wait_for(lambda: not alive(guard)))

    def test_nor_after_a_power_cycle_to_a_new_link(self):
        sim = SimProcess()
        self.addCleanup(sim.stop)
        new = os.path.join(sim.dir, "ttyLUMEN1")
        self.assertTrue(sim.ctl({"cmd": "power_cycle", "link": new})["ok"])
        self.assertTrue(os.path.islink(new))
        guard = self.killed(sim)
        self.assertTrue(wait_for(lambda: not os.path.lexists(new)))
        self.assertFalse(os.path.lexists(sim.link))
        self.assertTrue(wait_for(lambda: not alive(guard)))

    def test_a_kill_of_lumen_sim_s_whole_process_group_leaves_no_link(self):
        """uinav's kill_tree ends with os.killpg(pid, SIGKILL): the guardian, in a session of its own, outlives it."""
        sim = SimProcess(new_session=True)
        self.addCleanup(sim.stop)
        pgid = sim.proc.pid
        self.assertEqual(os.getpgid(pgid), pgid)  # its own group: the kill below reaches nothing of this test's
        guard = sim.ctl({"cmd": "state"})["state"]["guard_pid"]
        os.killpg(pgid, signal.SIGKILL)
        sim.proc.wait(timeout=5)
        self.assertTrue(wait_for(lambda: not os.path.lexists(sim.link)))
        self.assertTrue(wait_for(lambda: not alive(guard)))

    def test_a_clean_stop_ends_the_guardian_too(self):
        sim = SimProcess()
        self.addCleanup(sim.stop)
        guard = sim.ctl({"cmd": "state"})["state"]["guard_pid"]
        sim.ctl({"cmd": "stop"})
        self.assertEqual(sim.proc.wait(timeout=5), 0)
        self.assertTrue(wait_for(lambda: not alive(guard)))

    def test_only_a_link_still_on_the_dead_one_s_pty_and_not_locked_is_removed(self):
        tmp = temp_dir(self)
        link = os.path.join(tmp, "ttyLUMEN")
        os.symlink("/dev/pts/999999", link)  # never opened: only its name is compared
        remove_stale_link(link, "/dev/pts/999998")  # another pty by now
        self.assertTrue(os.path.islink(link))
        lock = lock_link(link)  # another lumen-sim has the path
        remove_stale_link(link, "/dev/pts/999999")
        self.assertTrue(os.path.islink(link))
        os.close(lock)
        remove_stale_link(link, "/dev/pts/999999")
        self.assertFalse(os.path.lexists(link))
        remove_stale_link(link, "/dev/pts/999999")  # already gone: nothing to do


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

    def test_a_request_nested_too_deep_is_a_refusal_not_a_crash(self):
        """Review round 3: json.loads raises RecursionError, not ValueError, on JSON nested 100000 deep; it went up
        through the loop and the process exited."""
        tmp = temp_dir(self)
        srv = ControlServer(os.path.join(tmp, "ctl.sock"))
        self.addCleanup(srv.close)
        c = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(c.close)
        c.connect(srv.path)
        srv.handle(srv.sock, None)  # accept
        c.setblocking(False)
        data, buf, replies = DEEP_JSON + b'{"cmd": "state"}\n', b"", []
        with within(self, 10):
            while len(replies) < 2:
                if data:
                    try:
                        data = data[c.send(data):]
                    except BlockingIOError:
                        pass
                r, w, _ = select.select(srv.readers(), srv.writers(), [], 0.01)
                srv.service(r, w, lambda req: {"ok": True, "cmd": req["cmd"]})
                try:
                    buf += c.recv(65536)
                except BlockingIOError:
                    pass
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    replies.append(json.loads(line))
        self.assertFalse(replies[0]["ok"])
        self.assertIn("bad request", replies[0]["error"])
        self.assertEqual(replies[1], {"ok": True, "cmd": "state"})

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


class SlowControlClients(unittest.TestCase):
    """A control client that does not read its replies must never stall the loop that serves the serial port."""

    def test_pipelined_requests_with_no_read_do_not_block_and_the_replies_come_in_order(self):
        tmp = temp_dir(self)
        srv = ControlServer(os.path.join(tmp, "ctl.sock"))
        self.addCleanup(srv.close)
        c = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(c.close)
        c.connect(srv.path)
        srv.handle(srv.sock, None)  # accept
        (conn,) = list(srv.clients)
        pad = "x" * 4000  # 1000 replies of 4 kB: far more than a socket buffer
        c.sendall(b"".join(b'{"cmd": "state", "n": %d}\n' % i for i in range(1000)))
        with within(self, 2):
            srv.handle(conn, lambda req: {"ok": True, "n": req["n"], "pad": pad})
        self.assertIn(conn, srv.writers())     # its replies wait for the client
        self.assertNotIn(conn, srv.readers())  # and it is not read meanwhile: it cannot pile up more
        got, buf = [], b""
        c.setblocking(False)
        with within(self, 10):
            while len(got) < 1000:
                r, w, _ = select.select(srv.readers(), srv.writers(), [], 0.05)
                srv.service(r, w, lambda req: {"ok": True, "n": req["n"], "pad": pad})
                try:
                    buf += c.recv(1 << 20)
                except BlockingIOError:
                    pass
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    got.append(json.loads(line)["n"])
        self.assertEqual(got, list(range(1000)))
        self.assertEqual(srv.writers(), [])

    def test_an_endless_request_line_drops_the_client(self):
        tmp = temp_dir(self)
        srv = ControlServer(os.path.join(tmp, "ctl.sock"))
        self.addCleanup(srv.close)
        c = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(c.close)
        c.connect(srv.path)
        srv.handle(srv.sock, None)
        (conn,) = list(srv.clients)
        c.setblocking(False)
        chunk = b"x" * 65536
        with within(self, 10):
            while srv.clients:
                try:
                    c.send(chunk)
                except (BlockingIOError, BrokenPipeError, ConnectionResetError):
                    pass
                for s in select.select(srv.readers(), [], [], 0.01)[0]:
                    srv.handle(s, None)
        self.assertEqual(srv.clients, {})

    def test_the_serial_port_is_served_while_a_control_client_does_not_read(self):
        sim = SimProcess()
        self.addCleanup(sim.stop)
        c = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(c.close)
        c.connect(sim.sock)
        c.sendall(b'{"cmd": "state"}\n' * 1000)  # and never read
        fd = open_raw(sim.link)
        try:
            os.write(fd, b"M400\n")
            self.assertEqual(read_until_ok(fd, timeout=5.0), ["ok"])
        finally:
            os.close(fd)
        sim.proc.terminate()
        self.assertEqual(sim.proc.wait(timeout=5), 0)
        self.assertFalse(os.path.lexists(sim.link))


class TimeScale(unittest.TestCase):
    def test_simulator_time_is_wall_time_times_the_scale(self):
        clock = [100.0]
        r = in_process_runner(self, clock=lambda: clock[0], time_scale=4)
        self.assertEqual(r.now(), 0.0)
        clock[0] = 100.4
        self.assertAlmostEqual(r.now(), 1.6, places=9)

    def test_a_time_scale_that_is_not_finite_and_positive_is_refused(self):
        tmp = temp_dir(self)
        link = os.path.join(tmp, "ttyLUMEN")
        for scale in (float("nan"), float("inf"), 0.0, -1.0):
            with self.assertRaisesRegex(ValueError, "time.scale", msg=scale):
                Runner(Simulator(), link, time_scale=scale)
        PtyLink(link).release()  # and the path was let go

    def test_time_scale_speeds_up_the_simulator_clock(self):
        """End to end through the CLI flag: 1.6 s of G4 at scale 4. Only the bounds a loaded host cannot break: not
        before 0.4 s of wall clock, well before the 1.6 s it would take at scale 1."""
        sim = SimProcess("--time-scale", "4")
        try:
            fd = open_raw(sim.link)
            try:
                t0 = time.monotonic()
                os.write(fd, b"G4 P1600\n")
                self.assertEqual(read_until_ok(fd), ["ok"])
                elapsed = time.monotonic() - t0
                self.assertGreaterEqual(elapsed, 0.39)
                self.assertLess(elapsed, 1.2)
            finally:
                os.close(fd)
        finally:
            sim.stop()


class StoppingTheProcess(unittest.TestCase):
    def test_stop_kills_a_process_that_ignores_sigterm(self):
        """A lumen-sim spinning past its stop request and SIGTERM is killed, never left behind (stop() waits 5 s
        twice)."""
        sim = SimProcess.__new__(SimProcess)
        sim.dir = tempfile.mkdtemp(prefix="lumen-sim-test-")
        sim.sock = os.path.join(sim.dir, "ctl.sock")  # nothing listens: the stop request fails
        sim.proc = subprocess.Popen(
            [sys.executable, "-c", "import signal, time\nsignal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                                   "print(flush=True)\ntime.sleep(60)"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        sim.proc.stdout.readline()  # SIGTERM is ignored from here on
        try:
            with within(self, 20):
                sim.stop()
            rc = sim.proc.poll()  # before the kill below, which is only there to never leave it behind
        finally:
            if sim.proc.poll() is None:
                sim.proc.kill()
                sim.proc.wait()
            sim.proc.stdout.close()
        self.assertEqual(rc, -signal.SIGKILL)
        self.assertFalse(os.path.exists(sim.dir))


if __name__ == "__main__":
    unittest.main()
