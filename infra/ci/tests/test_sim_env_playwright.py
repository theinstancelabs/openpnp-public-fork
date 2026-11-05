"""The sim-env action's Playwright step (.github/actions/sim-env/action.yml), run as GitHub runs it with fake npm,
sudo, dpkg and playwright. The system packages' apt-get hung on a stalled Ubuntu mirror for a leg's whole 100-min
timeout (#92's run 42078968125): a try that stalls is cut, with the apt-get under sudo, and tried again; three failed
tries fail the step.

The fake sudo is sudo as a GitHub step meets it (no pty, the command runs as root): its command is out of reach of the
caller's process-group signals (here: its own session), and a TERM to sudo goes to its direct child only. So a cut from
outside sudo leaves the stalled apt-get running, holding apt's lock, as on the runner.

Run from the repo root: python3 -m unittest discover -s infra/ci/tests -t infra/ci
"""
import os
import re
import shutil
import signal
import subprocess
import tempfile
import time
import unittest

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
ACTION = os.path.join(REPO, ".github", "actions", "sim-env", "action.yml")

FAKE_SUDO = r"""#!/bin/bash
[ "$1" = -- ] && shift
export FAKE_ROOT=1
setsid "$@" &
child=$!
trap 'kill -TERM "$child" 2>/dev/null' TERM
wait "$child"; s=$?
while [ "$s" -gt 128 ] && kill -0 "$child" 2>/dev/null; do wait "$child"; s=$?; done
exit "$s"
"""
# One entry of $PLAN per apt-get run, of $BROWSER_PLAN per browser download (hang | fail | ok, default ok); a hang
# leaves its stand-in running.
FAKE_APT = r"""n=$(grep -c "$WHAT" "$CALLS")
case $(echo "$PLAN" | cut -d, -f$n) in
  hang) sleep 1000 >/dev/null 2>&1 & echo $! >> "$CHILDREN"; wait ;;
  ok) exit 0 ;;
  "") exit 0 ;;
  *) exit 1 ;;
esac
"""
# Playwright runs apt-get itself as root, through sudo otherwise (as playwright-core does).
FAKE_PLAYWRIGHT = r"""#!/bin/bash
echo "$*" >> "$CALLS"
case "$*" in
  "install-deps chromium"|"install --with-deps chromium")
    export WHAT=deps
    [ "${FAKE_ROOT:-}" = 1 ] && exec bash "$FAKE_APT"
    exec sudo -- bash "$FAKE_APT" ;;
  "install chromium") WHAT="install chromium" PLAN="${BROWSER_PLAN:-}" exec bash "$FAKE_APT" ;;
esac
exit 9
"""
FAKE_NPM = r"""#!/bin/bash
[ "$1" = install ] && [ "$2" = --prefix ] || exit 9
mkdir -p "$3/node_modules/.bin" && cp "$FAKE_PLAYWRIGHT" "$3/node_modules/.bin/playwright"
"""
FAKE_DPKG = r"""#!/bin/bash
echo "dpkg $*" >> "$CALLS"
"""


def playwright_step():
    """The run: script of the action's step that runs playwright install."""
    with open(ACTION) as f:
        steps = re.split(r"^    - ", f.read().split("\n  steps:\n", 1)[1], flags=re.M)
    found = [s for s in steps if re.search(r'"\$pw" install|playwright" install', s)]
    if len(found) != 1:
        raise ValueError(f"{len(found)} steps run playwright install")
    body = found[0].split("      run: |\n", 1)[1]
    lines = []
    for line in body.splitlines():
        if line.strip() and not line.startswith("        "):
            break
        lines.append(line[8:])
    return "\n".join(lines).strip("\n") + "\n"


def alive(pid):
    try:
        with open(f"/proc/{pid}/stat") as f:
            return f.read().rsplit(")", 1)[1].split()[0] != "Z"  # a zombie nobody reaped is dead
    except FileNotFoundError:
        return False


class PlaywrightStepTest(unittest.TestCase):
    def run_step(self, plan, browser_plan=""):
        tmp = tempfile.mkdtemp(prefix="il-sim-env-pw-")
        self.addCleanup(shutil.rmtree, tmp, True)
        bin_ = os.path.join(tmp, "bin")
        os.makedirs(bin_)
        for path, text in ((os.path.join(bin_, "npm"), FAKE_NPM), (os.path.join(bin_, "sudo"), FAKE_SUDO),
                           (os.path.join(bin_, "dpkg"), FAKE_DPKG), (os.path.join(tmp, "playwright"), FAKE_PLAYWRIGHT),
                           (os.path.join(tmp, "apt.sh"), FAKE_APT)):
            with open(path, "w") as f:
                f.write(text)
            os.chmod(path, 0o755)
        script = os.path.join(tmp, "step.sh")
        with open(script, "w") as f:
            f.write(playwright_step())
        self.calls, self.children, github_env = (os.path.join(tmp, n) for n in ("calls", "children", "github_env"))
        open(self.calls, "w").close()
        env = dict(os.environ, PATH=bin_ + os.pathsep + os.environ["PATH"], RUNNER_TEMP=os.path.join(tmp, "runner"),
                   GITHUB_ENV=github_env, PLAYWRIGHT_VERSION="1.64.1", FAKE_PLAYWRIGHT=os.path.join(tmp, "playwright"),
                   FAKE_APT=os.path.join(tmp, "apt.sh"), PLAN=plan, BROWSER_PLAN=browser_plan, CALLS=self.calls,
                   CHILDREN=self.children,
                   PW_INSTALL_TIMEOUT_S="1")
        self.addCleanup(self.kill_children)
        t0 = time.monotonic()
        p = subprocess.Popen(["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", script], env=env,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True)
        try:
            out, _ = p.communicate(timeout=120)
        except subprocess.TimeoutExpired:
            os.killpg(p.pid, signal.SIGKILL)
            raise
        self.elapsed = time.monotonic() - t0
        with open(github_env) as f:
            self.github_env = f.read()
        return p.returncode, out

    def tries(self):
        with open(self.calls) as f:
            return f.read().splitlines()

    def left_running(self):
        if not os.path.exists(self.children):
            return []
        time.sleep(0.2)
        with open(self.children) as f:
            return [pid for pid in f.read().split() if alive(pid)]

    def kill_children(self):
        for pid in self.left_running():
            os.kill(int(pid), signal.SIGKILL)

    def test_one_try_when_it_works(self):
        rc, out = self.run_step("ok")
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.tries(), ["install-deps chromium", "install chromium"])
        self.assertNotIn("::warning::", out)
        self.assertIn("PLAYWRIGHT_BROWSERS_PATH=", self.github_env)
        self.assertIn("PLAYWRIGHT_DIR=", self.github_env)

    def test_a_stalled_apt_get_under_sudo_is_cut_and_tried_again(self):
        rc, out = self.run_step("hang,ok")
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.tries(), ["install-deps chromium", "dpkg --configure -a", "install-deps chromium",
                                        "install chromium"])
        self.assertIn("::warning::", out)
        self.assertLess(self.elapsed, 20)
        self.assertEqual(self.left_running(), [], "the stalled apt-get still runs (it holds apt's lock)")

    def test_a_stalled_browser_download_is_cut_and_tried_again(self):
        rc, out = self.run_step("ok,ok", browser_plan="hang,ok")
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.tries().count("install chromium"), 2)
        self.assertLess(self.elapsed, 20)
        self.assertEqual(self.left_running(), [])

    def test_a_failed_try_is_tried_again(self):
        rc, out = self.run_step("fail,fail,ok")
        self.assertEqual(rc, 0, out)
        self.assertEqual(self.tries().count("install-deps chromium"), 3)
        self.assertEqual(self.tries().count("dpkg --configure -a"), 2)

    def test_three_failed_tries_fail_the_step(self):
        for plan in ("hang,hang,hang", "fail,fail,fail"):
            rc, out = self.run_step(plan)
            self.assertNotEqual(rc, 0, plan)
            self.assertEqual(self.tries().count("install-deps chromium"), 3, plan)
            self.assertNotIn("install chromium", self.tries(), plan)
            self.assertIn("::error::", out, plan)
            self.assertLess(self.elapsed, 30, plan)
            self.assertEqual(self.left_running(), [], plan)


if __name__ == "__main__":
    unittest.main()
