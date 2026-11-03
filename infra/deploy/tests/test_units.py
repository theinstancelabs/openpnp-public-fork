"""units: rendered systemd user units (never installed by the tests)."""
import contextlib
import io
import os
import shlex
import shutil
import socket
import subprocess
import sys
import threading
import time
import unittest
from unittest import mock

# il_deploy is one level up: also when unittest imports this module by bare name (no -t)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import il_deploy  # noqa: E402
from fakes import Lab  # noqa: E402


class Units(unittest.TestCase):
    def setUp(self):
        self.lab = Lab()
        self.out = os.path.join(self.lab.dir, "units")
        argv = self.lab.argv("units", "--out", self.out)
        # units are rendered for systemd, not for the subprocess manager
        i = argv.index("--service-manager")
        del argv[i:i + 2]
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(il_deploy.main(argv, deps=self.lab.deps()), 0)

    def tearDown(self):
        self.lab.cleanup()

    def read(self, name):
        with open(os.path.join(self.out, name)) as f:
            return f.read()

    def test_files(self):
        self.assertEqual(sorted(os.listdir(self.out)),
                         ["il-deploy.service", "il-deploy.timer", "il-openpnp-xvfb.service", "il-openpnp.service",
                          "system"])

    def test_the_timers_run_verifies_attestations_with_gh_and_nothing_turns_that_off(self):
        """The tests' fake gh goes in through main(argv, deps) only: no flag and no environment variable changes the
        verifier. So the unit's run, with its own command line, verifies every build with gh and CI's policy, also with
        the e2e's variables in the user manager's environment."""
        line = [l for l in self.read("il-deploy.service").splitlines() if l.startswith("ExecStart=")][0]
        argv = shlex.split(line[len("ExecStart="):])
        self.assertEqual(argv[1:3], [os.path.join(self.lab.root, "bin", "il_deploy.py"), "run"])
        e2e_env = {"IL_DEPLOY_E2E": "1", "IL_DEPLOY_E2E_JAR": "/x.jar", "IL_DEPLOY_E2E_LIB": "/lib"}
        with mock.patch.dict(os.environ, e2e_env):
            d = il_deploy.Deployer(il_deploy.config_from_args(argv[2:]))
        self.assertIs(type(d.attest), il_deploy.GhAttestation)
        self.assertIsNone(d.attest.gh)  # gh from PATH
        self.assertIs(d.attest.runner, subprocess.run)
        cmd = d.attest.command("t.tar.gz", d.cfg.repo, d.cfg.branch, "0" * 40)
        for flag in ("--repo", "--signer-workflow", "--source-ref", "--source-digest", "--deny-self-hosted-runners"):
            self.assertIn(flag, cmd)
        for name in os.listdir(self.out):
            if os.path.isfile(os.path.join(self.out, name)):
                text = self.read(name)
                for word in ("IL_DEPLOY_E2E", "attest"):
                    self.assertNotIn(word, text, name)

    def test_openpnp_service(self):
        u = self.read("il-openpnp.service")
        root = self.lab.root
        self.assertIn(f"WorkingDirectory={root}/current\n", u)
        start = [l for l in u.splitlines() if l.startswith("ExecStart=")][0]
        # -F: no fork, java is the unit's main process (KillMode=mixed sends SIGTERM to the main process only)
        self.assertTrue(start.startswith(f"ExecStart=/usr/bin/flock -n -F -E 75 {self.lab.lock} /usr/bin/java "), start)
        for part in ("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
                     "--add-opens=java.desktop/java.awt.color=ALL-UNNAMED", f"-DconfigDir={root}/state/config",
                     f"-Djava.util.prefs.userRoot={root}/prefs", "-jar openpnp-il.jar"):
            self.assertIn(part, start)
        self.assertIn(f'Environment="OPENPNP_IL_API_PORT={self.lab.port}"', u)
        self.assertIn('Environment="JAVA_TOOL_OPTIONS=-Xmx1536m"', u)
        self.assertIn('Environment="DISPLAY=:50"', u)
        stop = [l for l in u.splitlines() if l.startswith("ExecStop=")][0]
        self.assertIn("il_deploy.py stop-openpnp", stop)
        self.assertIn(f"--api-port {self.lab.port}", stop)
        # java's exit on SIGTERM (128 + 15) is a clean stop: only a crash leaves the unit failed (a hold)
        for line in ("KillMode=mixed", "SuccessExitStatus=143", "Restart=no", "Requires=il-openpnp-xvfb.service",
                     "After=il-openpnp-xvfb.service"):
            self.assertIn(line + "\n", u)
        self.assertRegex(u, r"CPUWeight=\d{3,}")
        self.assertRegex(u, r"MemoryLow=\d+[MG]")
        self.assertIn("ExecStartPre=", u)

    def test_exec_stop_never_stops_a_home_and_fits_its_timeout(self):
        u = self.read("il-openpnp.service")
        stop = [l for l in u.splitlines() if l.startswith("ExecStop=")][0].split()
        wait = float(stop[stop.index("--home-busy-wait") + 1])
        stop_timeout = float(stop[stop.index("--stop-timeout") + 1])
        limit = float([l for l in u.splitlines() if l.startswith("TimeoutStopSec=")][0].split("=")[1])
        # API homes take 21-23 s and up to 5 min (RESEARCH.md §2): wait out a whole home before any STOP
        self.assertGreaterEqual(wait, 300)
        # after the STOP, the valve and pump must be off: the engine's own abort lines wait for the move in progress,
        # which can take the driver's longest timeout (60 s in the lab machine.xml)
        self.assertGreaterEqual(stop_timeout, 60 + 15)
        # worst case: the wait, one slow status read (5 s), POST /api/stop (10 s), the disable wait, a margin
        self.assertGreaterEqual(limit, wait + 5 + 10 + stop_timeout + 15)

    def test_deploy_stops_before_openpnp_at_shutdown(self):
        # units ordered After= stop first: il_deploy's own handler finishes a home before ExecStop runs
        self.assertIn("After=il-openpnp.service\n", self.read("il-deploy.service"))

    def test_xvfb(self):
        u = self.read("il-openpnp-xvfb.service")
        self.assertIn("ExecStart=/usr/bin/Xvfb :50 -nolisten tcp", u)

    def test_xvfb_is_ready_before_openpnp_starts(self):
        """Type=simple is 'started' at the fork, before Xvfb listens; OpenPnP's first AWT call (Main: setLookAndFeel)
        exits 1 without the X socket, and a good build would go on the bad list. The unit is active only after
        ExecStartPost, and il-openpnp is After= it."""
        post = [l for l in self.read("il-openpnp-xvfb.service").splitlines() if l.startswith("ExecStartPost=")]
        self.assertEqual(len(post), 1, post)
        self.assertIn("il_deploy.py wait-display --display :50", post[0])

    def test_user_manager_drop_in(self):
        """Ubuntu's user@.service.d/timeout.conf gives the whole user manager 5 s at shutdown: rendered here is the
        system drop-in (installed by root, by hand) that lets the units' own stop budgets run, one after the other."""
        uid = os.getuid()
        path = os.path.join(self.out, "system", f"user@{uid}.service.d", "zz-il-deploy-stop-timeout.conf")
        with open(path) as f:
            text = f.read()
        self.assertIn("[Service]\n", text)
        budget = il_deploy.parse_timespan(text.split("TimeoutStopSec=")[1].split()[0])

        def stop_s(name):
            return il_deploy.parse_timespan([l for l in self.read(name).splitlines()
                                             if l.startswith("TimeoutStopSec=")][0].split("=")[1])
        self.assertGreaterEqual(budget, stop_s("il-deploy.service") + stop_s("il-openpnp.service"))
        self.assertGreaterEqual(budget, il_deploy.USER_MANAGER_STOP_S)
        self.assertFalse(any(n.startswith("zz-") for n in os.listdir(self.out)), "never copied with the user units")

    def test_timespans(self):
        for text, s in (("5s", 5), ("17min", 1020), ("1min 30s", 90), ("500ms", 0.5), ("1h", 3600), ("2min 1s", 121),
                        ("360", 360), ("infinity", float("inf"))):
            with self.subTest(text=text):
                self.assertEqual(il_deploy.parse_timespan(text), s)
        for text in ("", "soon", "5 parsecs", "5 s 3", "-5s"):
            with self.subTest(text=text):
                self.assertIsNone(il_deploy.parse_timespan(text))

    def test_deploy_service_and_timer(self):
        s = self.read("il-deploy.service")
        self.assertIn("Type=oneshot", s)
        start = [l for l in s.splitlines() if l.startswith("ExecStart=")][0]
        self.assertIn(" run ", start + " ")
        for flag in (f"--root {self.lab.root}", f"--state-md {self.lab.state_md}", f"--lock {self.lab.lock}",
                     f"--device {self.lab.device}", f"--activity {self.lab.activity}", f"--api-port {self.lab.port}",
                     "--usb-sn 387932683335"):
            self.assertIn(flag, start)
        self.assertNotIn("--openpnp-cmd", start)
        self.assertIn("SuccessExitStatus=3", s)
        self.assertIn("TimeoutStartSec=infinity", s)
        t = self.read("il-deploy.timer")
        self.assertIn("OnUnitInactiveSec=2min", t)
        self.assertIn("WantedBy=timers.target", t)

    def test_quoting(self):
        self.assertEqual(il_deploy.sd_quote("/a/b-c_d.e:f"), "/a/b-c_d.e:f")
        self.assertEqual(il_deploy.sd_quote("/a b/c"), '"/a b/c"')
        self.assertEqual(il_deploy.sd_quote("50%"), "50%%")
        self.assertEqual(il_deploy.sd_quote('a"b'), '"a\\"b"')

    @unittest.skipUnless(shutil.which("systemd-analyze"), "systemd-analyze not installed")
    def test_systemd_analyze_verify(self):
        os.makedirs(os.path.join(self.lab.root, "current"), exist_ok=True)
        files = [os.path.join(self.out, n) for n in sorted(os.listdir(self.out)) if n.startswith("il-")]
        p = subprocess.run(["systemd-analyze", "--user", "verify"] + files, capture_output=True, text=True,
                           cwd=self.out, timeout=60)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertNotIn(self.out, p.stderr, p.stderr)


if __name__ == "__main__":
    unittest.main()


class WaitDisplay(unittest.TestCase):
    """wait-display: the Xvfb unit's ExecStartPost waits for the X socket."""

    def setUp(self):
        self.lab = Lab()
        self.x11 = os.path.join(self.lab.dir, "x11")
        os.makedirs(self.x11)

    def tearDown(self):
        self.lab.cleanup()

    def run_wait(self, timeout):
        return self.lab.main("wait-display", "--display", ":57", "--timeout", str(timeout), "--x11-dir", self.x11)

    def test_returns_when_the_socket_appears(self):
        def listen():
            time.sleep(0.4)
            s = socket.socket(socket.AF_UNIX)
            s.bind(os.path.join(self.x11, "X57"))
            self.addCleanup(s.close)
        t = threading.Thread(target=listen)
        t.start()
        start = time.monotonic()
        self.assertEqual(self.run_wait(10), 0, self.lab.last_out)
        t.join()
        self.assertLess(time.monotonic() - start, 5)

    def test_fails_when_it_never_appears(self):
        start = time.monotonic()
        self.assertEqual(self.run_wait(0.5), 1)
        self.assertGreaterEqual(time.monotonic() - start, 0.5)
        self.assertIn("X57", self.lab.last_out)
