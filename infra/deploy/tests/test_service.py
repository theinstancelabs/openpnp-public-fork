"""Service managers: systemd-user (commands only, never run here) and the subprocess one."""
import contextlib
import fcntl
import os
import signal
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

# il_deploy is one level up: also when unittest imports this module by bare name (no -t)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import il_deploy  # noqa: E402


class Recorder:
    def __init__(self, answers=None):
        self.calls = []
        self.answers = answers or {}

    def __call__(self, cmd, **kw):
        self.calls.append(cmd)
        out = self.answers.get(tuple(cmd[2:4]), "")
        return subprocess.CompletedProcess(cmd, 0, stdout=out, stderr="")


class Systemd(unittest.TestCase):
    def test_commands(self):
        rec = Recorder({("is-active", "il-openpnp.service"): "active\n"})
        svc = il_deploy.SystemdUserService("il-openpnp.service", runner=rec)
        self.assertTrue(svc.is_active())
        svc.start()
        svc.stop()
        self.assertEqual(rec.calls[0], ["systemctl", "--user", "is-active", "il-openpnp.service"])
        self.assertIn(["systemctl", "--user", "start", "il-openpnp.service"], rec.calls)
        self.assertIn(["systemctl", "--user", "stop", "il-openpnp.service"], rec.calls)

    def test_inactive_and_exit_status(self):
        rec = Recorder({("is-active", "il-openpnp.service"): "failed\n",
                        ("show", "-p"): "75\n"})
        svc = il_deploy.SystemdUserService("il-openpnp.service", runner=rec)
        self.assertFalse(svc.is_active())
        self.assertEqual(svc.exit_status(), 75)

    def test_last_exit(self):
        """A failed unit is an exit il_deploy did not cause (a stop through systemctl ends 'inactive': SIGTERM, and java's
        143, are clean). Not one: a start refused by the serial lock (75) or by ExecStartPre (no main process)."""
        def show(**props):
            text = "".join(f"{k}={v}\n" for k, v in props.items())
            return il_deploy.SystemdUserService("il-openpnp.service", runner=Recorder({("show", "-p"): text}))
        failed = dict(ActiveState="failed", Result="signal", ExecMainCode="2", ExecMainStatus="9", ExecMainPID="4242",
                      ExecMainExitTimestampMonotonic="123456789")
        ex = show(**failed).last_exit()
        self.assertTrue(ex and "4242" in ex["id"] and "123456789" in ex["id"], ex)
        self.assertIn("signal", ex["detail"])
        self.assertIsNone(show(**dict(failed, ActiveState="inactive", Result="success")).last_exit())
        self.assertIsNone(show(**dict(failed, ActiveState="active", Result="success")).last_exit())
        self.assertIsNone(show(**dict(failed, Result="exit-code", ExecMainCode="1", ExecMainStatus="75")).last_exit())
        self.assertIsNone(show(**dict(failed, Result="exit-code", ExecMainPID="0")).last_exit())
        self.assertTrue(show(**dict(failed, Result="exit-code", ExecMainCode="1", ExecMainStatus="1")).last_exit())

    def test_pids_from_cgroup(self):
        d = tempfile.mkdtemp()
        try:
            cg = os.path.join(d, "user.slice", "app.slice", "il-openpnp.service")
            os.makedirs(os.path.join(cg, "sub"))
            with open(os.path.join(cg, "cgroup.procs"), "w") as f:
                f.write("101\n102\n")
            with open(os.path.join(cg, "sub", "cgroup.procs"), "w") as f:
                f.write("103\n")
            rec = Recorder({("show", "-p"): "/user.slice/app.slice/il-openpnp.service\n"})
            svc = il_deploy.SystemdUserService("il-openpnp.service", runner=rec, cgroup_root=d)
            self.assertEqual(svc.pids(), {101, 102, 103})
        finally:
            shutil.rmtree(d)


class Subprocess(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.cwd = os.path.join(self.dir, "cwd")
        os.makedirs(self.cwd)
        self.svcs = []

    def tearDown(self):
        for s in self.svcs:
            if s.is_active():
                s.stop()
        shutil.rmtree(self.dir)

    def make(self, cmd, pre_stop=None):
        s = il_deploy.SubprocessService(cmd, self.cwd, dict(os.environ), os.path.join(self.dir, "run"),
                                        os.path.join(self.dir, "log"), pre_stop=pre_stop, stop_timeout=5)
        self.svcs.append(s)
        return s

    def test_start_track_stop(self):
        stopped = []
        s = self.make([sys.executable, "-c", "import time; time.sleep(30)"], pre_stop=lambda: stopped.append(1))
        self.assertFalse(s.is_active())
        s.start()
        self.assertTrue(s.is_active())
        pid = next(iter(s.pids()))
        # a fresh manager (another CLI call) finds it through the pid file
        other = self.make(s.cmd, pre_stop=lambda: stopped.append(2))
        self.assertTrue(other.is_active())
        self.assertIn(pid, other.pids())
        other.stop()
        self.assertEqual(stopped, [2])
        self.assertFalse(s.is_active())
        st = il_deploy._proc_stat(pid)  # one reading: gone, a zombie, or dead (X) while it is reaped
        self.assertTrue(st is None or st[0] in ("Z", "X"), st)

    def test_children_are_service_pids(self):
        s = self.make(["/bin/sh", "-c", "sleep 30 & wait"])
        s.start()
        deadline = time.time() + 5
        while time.time() < deadline and len(s.pids()) < 2:
            time.sleep(0.02)
        self.assertGreaterEqual(len(s.pids()), 2)
        s.stop()
        self.assertFalse(s.is_active())

    def flock_slow_child(self):
        """The unit's shape: flock -n LOCK <program>. flock dies on SIGTERM at once; the program (java) takes a
        second to exit and holds the lock (and the API port) until then."""
        lock, ready = os.path.join(self.dir, "lock"), os.path.join(self.dir, "ready")
        child = ("import signal, sys, time; signal.signal(signal.SIGTERM, lambda *a: (time.sleep(1.0), sys.exit(0)));"
                 f"open({ready!r}, 'w').close(); time.sleep(60)")
        s = self.make(["/usr/bin/flock", "-n", "-E", "75", lock, sys.executable, "-c", child])
        s.start()
        deadline = time.time() + 10
        while time.time() < deadline and not os.path.exists(ready):
            time.sleep(0.02)
        self.assertTrue(os.path.exists(ready))
        return s, lock

    def lock_is_free(self, lock):
        fd = os.open(lock, os.O_RDWR)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return True
        except BlockingIOError:
            return False
        finally:
            os.close(fd)

    def test_stop_waits_for_the_whole_process_group(self):
        """As `systemctl stop` waits for the unit's cgroup: stop() returns only when the program flock ran is gone
        too. Otherwise a start right after it fails on the lock (exit 75), and a config manifest taken right after
        it misses what OpenPnP writes while it exits (seen with the real OpenPnP, e2e/test_deploy_openpnp.py)."""
        s, lock = self.flock_slow_child()
        s.stop()
        self.assertFalse(s.is_active())
        self.assertTrue(self.lock_is_free(lock), "the program flock ran still holds the lock after stop()")

    def test_a_live_child_keeps_the_service_active(self):
        s, lock = self.flock_slow_child()
        leader = il_deploy.read_json(s.pidfile)["pid"]
        children = s.pids() - {leader}
        self.assertTrue(children)
        os.kill(leader, signal.SIGKILL)  # flock gone, java still running: OpenPnP is still up
        deadline = time.time() + 5
        while time.time() < deadline:
            st = il_deploy._proc_stat(leader)  # one reading per pass: it may be reaped between two
            if st is None or st[0] in ("Z", "X"):
                break
            time.sleep(0.02)
        self.assertTrue(s.is_active())
        self.assertTrue(children <= s.pids(), (children, s.pids()))
        s.stop()
        self.assertFalse(s.is_active())
        self.assertTrue(self.lock_is_free(lock))

    def test_a_leader_zombie_whose_threads_run_keeps_the_service_active(self):
        """When the main thread of a process exits first, /proc/<pid>/stat says Z while its other threads run on and
        keep its files (the serial lock, the API port): a JVM can be so while it exits. Counted as gone, stop()
        returned while java still held the lock, and the next start failed on it (exit 75)."""
        lock, ready = os.path.join(self.dir, "lock"), os.path.join(self.dir, "ready")
        child = ("import ctypes, fcntl, os, threading, time\n"
                 f"f = open({lock!r}, 'w'); fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)\n"
                 "threading.Thread(target=lambda: (time.sleep(1.5), os._exit(0))).start()\n"
                 f"open({ready!r}, 'w').close()\n"
                 "ctypes.CDLL(None).pthread_exit(None)\n")
        s = self.make([sys.executable, "-c", child])
        s.start()
        leader = il_deploy.read_json(s.pidfile)["pid"]
        deadline = time.time() + 10
        while time.time() < deadline:
            st = il_deploy._proc_stat(leader)
            if os.path.exists(ready) and st and st[0] == "Z":
                break
            time.sleep(0.01)
        self.assertEqual(il_deploy._proc_stat(leader)[0], "Z", "the main thread did not exit first")
        self.assertFalse(self.lock_is_free(lock))
        self.assertTrue(s.is_active(), "its threads run on and hold the lock")
        self.assertIn(leader, s.pids())
        s.stop()
        self.assertTrue(self.lock_is_free(lock), "stop() returned while the process still held the lock")

    def test_the_units_command_hands_sigterm_to_the_program(self):
        """KillMode=mixed sends SIGTERM to the unit's main process only, then SIGKILL to what is left. With flock's
        default fork, flock is the main process and does not pass SIGTERM on: java was SIGKILLed and its shutdown hooks
        (the prefs sync, log flushing) never ran. flock -F (no fork) runs the program as the main process."""
        lock = os.path.join(self.dir, "lock")
        ready, termed = os.path.join(self.dir, "ready"), os.path.join(self.dir, "termed")
        cfg = il_deploy.config_from_args(["status", "--lock", lock, "--java", sys.executable])
        cmd = il_deploy.openpnp_command(cfg, use_override=False)
        prefix = cmd[:cmd.index(sys.executable)]
        child = ("import signal, sys, time\n"
                 f"signal.signal(signal.SIGTERM, lambda *a: (open({termed!r}, 'w').close(), sys.exit(0)))\n"
                 f"open({ready!r}, 'w').close()\n"
                 "time.sleep(60)\n")
        p = subprocess.Popen(prefix + [sys.executable, "-c", child], start_new_session=True)
        try:
            deadline = time.time() + 10
            while time.time() < deadline and not os.path.exists(ready):
                time.sleep(0.02)
            self.assertTrue(os.path.exists(ready))
            os.kill(p.pid, signal.SIGTERM)  # the main process only, as systemd's KillMode=mixed
            p.wait(10)
            deadline = time.time() + 5
            while time.time() < deadline and not os.path.exists(termed):
                time.sleep(0.02)
            self.assertTrue(os.path.exists(termed), "the program never got the SIGTERM")
        finally:
            with contextlib.suppress(ProcessLookupError):
                os.killpg(p.pid, signal.SIGKILL)
            with contextlib.suppress(subprocess.TimeoutExpired):
                p.wait(5)

    def test_last_exit(self):
        """An exit il_deploy did not cause (no stop()): what a crash looks like to the next il_deploy command."""
        s = self.make([sys.executable, "-c", "import time; time.sleep(30)"])
        self.assertIsNone(s.last_exit(), "never started")
        s.start()
        self.assertIsNone(s.last_exit(), "running")
        os.killpg(il_deploy.read_json(s.pidfile)["pid"], signal.SIGKILL)
        deadline = time.time() + 5
        while time.time() < deadline and s.is_active():
            time.sleep(0.02)
        ex = s.last_exit()
        self.assertTrue(ex and ex["id"], ex)
        self.assertIn("-9", ex["detail"])
        self.assertEqual(ex, self.make(s.cmd).last_exit(), "another CLI call sees the same exit")
        s.stop()
        self.assertIsNone(s.last_exit(), "stopped by il_deploy: not a crash")
        for code, crash in ((75, False), (0, False), (1, True)):
            with self.subTest(code=code):
                s = self.make([sys.executable, "-c", f"import sys; sys.exit({code})"])
                s.start()
                deadline = time.time() + 5
                while time.time() < deadline and s.is_active():
                    time.sleep(0.02)
                self.assertEqual(bool(s.last_exit()), crash, s.last_exit())

    def test_exit_status(self):
        s = self.make([sys.executable, "-c", "import sys; sys.exit(75)"])
        s.start()
        deadline = time.time() + 5
        while time.time() < deadline and s.is_active():
            time.sleep(0.02)
        self.assertFalse(s.is_active())
        self.assertEqual(s.exit_status(), 75)

    def test_stale_pid_file(self):
        s = self.make([sys.executable, "-c", "pass"])
        os.makedirs(os.path.join(self.dir, "run"))
        with open(os.path.join(self.dir, "run", "il-openpnp.json"), "w") as f:
            f.write('{"pid": %d, "starttime": "1"}' % os.getpid())
        self.assertFalse(s.is_active())

    def test_stale_pid_file_naming_a_live_group_leader(self):
        """The recorded pid is another process now, leader of a group of its own: not ours, never signalled."""
        other = subprocess.Popen(["sleep", "30"], start_new_session=True)
        try:
            s = self.make([sys.executable, "-c", "pass"])
            os.makedirs(os.path.join(self.dir, "run"))
            with open(os.path.join(self.dir, "run", "il-openpnp.json"), "w") as f:
                f.write('{"pid": %d, "starttime": "1"}' % other.pid)
            self.assertFalse(s.is_active())
            self.assertEqual(s.pids(), set())
            s.stop()
            self.assertIsNone(other.poll())
        finally:
            other.kill()
            other.wait()

    def test_cwd_resolves_the_current_link(self):
        rel = os.path.join(self.dir, "releases", "r1")
        os.makedirs(rel)
        link = os.path.join(self.dir, "current")
        os.symlink(rel, link)
        out = os.path.join(self.dir, "cwd.txt")
        s = il_deploy.SubprocessService([sys.executable, "-c", f"import os; open({out!r},'w').write(os.getcwd())"],
                                        link, dict(os.environ), os.path.join(self.dir, "run"),
                                        os.path.join(self.dir, "log"))
        self.svcs.append(s)
        s.start()
        deadline = time.time() + 5
        while time.time() < deadline and not os.path.exists(out):
            time.sleep(0.02)
        time.sleep(0.1)
        with open(out) as f:
            self.assertEqual(f.read(), rel)


if __name__ == "__main__":
    unittest.main()
