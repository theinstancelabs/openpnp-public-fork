"""The SIM scene writer's own checks (src/test/sim/il_sim_scene.py selftest) run with the infra tests, so CI gates them.

Run from the repo root: python3 -m unittest discover -s infra/ci/tests -t infra/ci
"""
import os
import subprocess
import sys
import unittest

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
SCRIPT = os.path.join(REPO, "src", "test", "sim", "il_sim_scene.py")


class SceneWriterSelftest(unittest.TestCase):
    def test_every_check_passes(self):
        out = subprocess.run([sys.executable, SCRIPT, "selftest"], capture_output=True, text=True, timeout=120)
        failed = [line for line in out.stdout.splitlines() if not line.startswith("PASS ")]
        self.assertEqual(out.returncode, 0, "\n".join(failed) + out.stderr)
        self.assertGreater(out.stdout.count("PASS "), 30, out.stdout)
        self.assertEqual(failed, [])


if __name__ == "__main__":
    unittest.main()
