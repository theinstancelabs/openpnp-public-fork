"""Tests for infra/ci/webui_shards.py: the UI end-to-end job's shards (WEBUI_SCRIPTS per matrix leg).

Run from the repo root: python3 -m unittest discover -s infra/ci/tests -t infra/ci
"""
import os
import re
import subprocess
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import webui_shards  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
CI = os.path.join(REPO, ".github", "workflows", "ci.yml")
RUNNER = os.path.join(REPO, webui_shards.RUNNER)


class Shards(unittest.TestCase):
    ALL = ["webui-sim", "board-import", "pad-profile", "tip-setups", "lock", "lock-real"]

    def test_the_named_shards_and_the_rest_cover_every_script_once(self):
        legs = {k: webui_shards.scripts_for(self.ALL, k) for k in webui_shards.KEYS}
        ran = [s for v in legs.values() for s in v]
        self.assertEqual(sorted(ran), sorted(self.ALL))
        self.assertEqual(legs["rest"], ["lock", "lock-real"])

    def test_a_new_script_runs_in_the_rest_without_a_ci_change(self):
        self.assertEqual(webui_shards.scripts_for(self.ALL + ["tip-flow", "jog"], "rest"),
                         ["lock", "lock-real", "tip-flow", "jog"])

    def test_a_named_script_gone_from_all_scripts_fails_loudly(self):
        """A rename in run-webui-sim-test.sh must not drop a script from CI: the leg fails, it does not shrink."""
        with self.assertRaisesRegex(ValueError, "pad-profile"):
            webui_shards.scripts_for([s for s in self.ALL if s != "pad-profile"], "main")

    def test_an_unknown_key_is_refused(self):
        with self.assertRaises(ValueError):
            webui_shards.scripts_for(self.ALL, "everything")

    def test_all_scripts_is_read_from_the_runner(self):
        self.assertEqual(webui_shards.all_scripts('x=1\nALL_SCRIPTS="a b  c"\n'), ["a", "b", "c"])
        with self.assertRaises(ValueError):
            webui_shards.all_scripts("no list here\n")

    def test_the_cli_prints_the_github_output_line(self):
        out = subprocess.run([sys.executable, os.path.join(REPO, "infra", "ci", "webui_shards.py"), "rest"],
                             cwd=REPO, capture_output=True, text=True, check=True).stdout
        self.assertRegex(out, r"^scripts=\S")

    def test_the_repos_runner_has_every_named_script(self):
        with open(RUNNER) as f:
            all_ = webui_shards.all_scripts(f.read())
        for key in webui_shards.KEYS:
            webui_shards.scripts_for(all_, key)  # raises if a named one is missing


class Wiring(unittest.TestCase):
    """ci.yml runs one ui-e2e leg per key, each with its own WEBUI_SCRIPTS and artifact, and gate waits for all."""

    def setUp(self):
        with open(CI) as f:
            self.ci = f.read()
        self.job = re.search(r"\n  ui-e2e:\n(.*?)\n  [a-z][a-z0-9-]*:\n", self.ci, re.S).group(1)

    def test_one_leg_per_key(self):
        legs = re.findall(r"- \{shard: ([a-z]+),", self.job)
        self.assertEqual(legs, list(webui_shards.KEYS))
        self.assertIn("fail-fast: false", self.job)

    def test_each_leg_runs_its_own_scripts_and_keeps_its_artifact(self):
        self.assertIn("infra/ci/webui_shards.py ${{ matrix.shard }}", self.job)
        self.assertIn("WEBUI_SCRIPTS: ${{ steps.shard.outputs.scripts }}", self.job)
        self.assertIn("name: ui-e2e-${{ matrix.shard }}", self.job)

    def test_gate_waits_for_the_ui_legs(self):
        needs = re.search(r"\n  gate:\n.*?needs: \[([^\]]*)\]", self.ci, re.S).group(1)
        self.assertIn("ui-e2e", [n.strip() for n in needs.split(",")])
