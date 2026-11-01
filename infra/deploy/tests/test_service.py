"""Service managers: systemd-user (commands only, never run here) and the subprocess one."""
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
        self.assertFalse(os.path.exists(f"/proc/{pid}") and open(f"/proc/{pid}/stat").read().split()[2] != "Z")

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
        while time.time() < deadline and il_deploy._proc_stat(leader) and il_deploy._proc_stat(leader)[0] != "Z":
            time.sleep(0.02)
        self.assertTrue(s.is_active())
        self.assertTrue(children <= s.pids(), (children, s.pids()))
        s.stop()
        self.assertFalse(s.is_active())
        self.assertTrue(self.lock_is_free(lock))

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
