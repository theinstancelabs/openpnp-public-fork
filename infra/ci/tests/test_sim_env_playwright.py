"""The sim-env action's Playwright step (.github/actions/sim-env/action.yml), run as GitHub runs it with a fake npm and
playwright. `playwright install --with-deps` runs apt-get, which hung on a stalled Ubuntu mirror for a leg's whole
100-min timeout (#92's run 42078968125): a try that stalls is cut, with what it started, and tried again; three
failed tries fail the step.

Run from the repo root: python3 -m unittest discover -s infra/ci/tests -t infra/ci
"""
import os
import re
import subprocess
import tempfile
import time
import unittest

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
ACTION = os.path.join(REPO, ".github", "actions", "sim-env", "action.yml")

# Fake playwright: one entry of $PLAN per call (hang | fail | ok); a hang leaves a child running, as apt-get under sudo.
FAKE_PLAYWRIGHT = r"""#!/bin/bash
n=$(( $(cat "$CALLS" 2>/dev/null | wc -l) + 1 ))
echo "$*" >> "$CALLS"
case $(echo "$PLAN" | cut -d, -f$n) in
  hang) sleep 1000 >/dev/null 2>&1 & echo $! >> "$CHILDREN"; wait ;;
  ok) exit 0 ;;
  *) exit 1 ;;
esac
"""
FAKE_NPM = r"""#!/bin/bash
[ "$1" = install ] && [ "$2" = --prefix ] || exit 9
mkdir -p "$3/node_modules/.bin" && cp "$FAKE_PLAYWRIGHT" "$3/node_modules/.bin/playwright"
"""


def playwright_step():
    """The run: script of the action's step that runs `playwright install`."""
    with open(ACTION) as f:
        steps = re.split(r"^    - ", f.read().split("\n  steps:\n", 1)[1], flags=re.M)
    found = [s for s in steps if "playwright\" install" in s]
    if len(found) != 1:
        raise ValueError(f"{len(found)} steps run playwright install")
    body = found[0].split("      run: |\n", 1)[1]
    lines = []
    for line in body.splitlines():
        if line.strip() and not line.startswith("        "):
            break
        lines.append(line[8:])
    return "\n".join(lines).strip("\n") + "\n"


class PlaywrightStepTest(unittest.TestCase):
    def run_step(self, plan):
        tmp = tempfile.mkdtemp(prefix="il-sim-env-pw-")
        bin_ = os.path.join(tmp, "bin")
        os.makedirs(bin_)
        for name, text in (("npm", FAKE_NPM), ("playwright", FAKE_PLAYWRIGHT)):
            with open(os.path.join(bin_ if name == "npm" else tmp, name), "w") as f:
                f.write(text)
            os.chmod(os.path.join(bin_ if name == "npm" else tmp, name), 0o755)
        script = os.path.join(tmp, "step.sh")
        with open(script, "w") as f:
            f.write(playwright_step())
        self.calls, self.children, github_env = (os.path.join(tmp, n) for n in ("calls", "children", "github_env"))
        env = dict(os.environ, PATH=bin_ + os.pathsep + os.environ["PATH"], RUNNER_TEMP=os.path.join(tmp, "runner"),
                   GITHUB_ENV=github_env, PLAYWRIGHT_VERSION="1.64.1", FAKE_PLAYWRIGHT=os.path.join(tmp, "playwright"),
                   PLAN=plan, CALLS=self.calls, CHILDREN=self.children, PW_INSTALL_TIMEOUT_S="1")
        t0 = time.monotonic()
        p = subprocess.run(["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", script], env=env,
                           capture_output=True, text=True, timeout=120)
        self.elapsed = time.monotonic() - t0
        with open(github_env) as f:
            self.github_env = f.read()
        return p

    def tries(self):
        with open(self.calls) as f:
            return f.read().splitlines()

    def assert_no_child_left(self):
        if not os.path.exists(self.children):
            return
        with open(self.children) as f:
            for pid in f.read().split():
                time.sleep(0.2)
                with self.assertRaises(ProcessLookupError, msg=f"a stalled try's child {pid} still runs"):
                    os.kill(int(pid), 0)

    def test_one_try_when_it_works(self):
        p = self.run_step("ok")
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertEqual(self.tries(), ["install --with-deps chromium"])
        self.assertNotIn("::warning::", p.stdout)
        self.assertIn("PLAYWRIGHT_BROWSERS_PATH=", self.github_env)
        self.assertIn("PLAYWRIGHT_DIR=", self.github_env)

    def test_a_stalled_try_is_cut_with_its_child_and_tried_again(self):
        p = self.run_step("hang,ok")
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertEqual(self.tries(), ["install --with-deps chromium"] * 2)
        self.assertIn("::warning::", p.stdout)
        self.assertLess(self.elapsed, 20)
        self.assert_no_child_left()

    def test_a_failed_try_is_tried_again(self):
        p = self.run_step("fail,fail,ok")
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        self.assertEqual(len(self.tries()), 3)

    def test_three_failed_tries_fail_the_step(self):
        for plan in ("hang,hang,hang", "fail,fail,fail"):
            p = self.run_step(plan)
            self.assertNotEqual(p.returncode, 0, plan)
            self.assertEqual(len(self.tries()), 3, plan)
            self.assertIn("::error::", p.stdout, plan)
            self.assertLess(self.elapsed, 30, plan)
            self.assert_no_child_left()


if __name__ == "__main__":
    unittest.main()
