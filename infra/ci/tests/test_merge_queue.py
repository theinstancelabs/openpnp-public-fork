"""ci.yml for a merge queue: a fast gate on pull requests, the full gate in the queue (merge_group) and on pushes.

Run from the repo root: python3 -m unittest discover -s infra/ci/tests -t infra/ci
"""
import os
import re
import unittest

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
CI = os.path.join(REPO, ".github", "workflows", "ci.yml")
SLOW = ("ui-e2e", "acceptance")  # skipped on a PR without the full-ci label; always run in the queue and on pushes
SKIP_ON_PR = ("github.event_name != 'pull_request' || contains(github.event.pull_request.labels.*.name, "
              "'full-ci')")


def job(ci, name):
    return re.search(r"\n  %s:\n(.*?)(?=\n  [a-z][a-z0-9-]*:\n|\Z)" % re.escape(name), ci, re.S).group(1)


class MergeQueue(unittest.TestCase):
    def setUp(self):
        with open(CI) as f:
            self.ci = f.read()

    def test_the_queue_runs_the_workflow(self):
        on = re.search(r"\non:\n(.*?)\n\S", self.ci, re.S).group(1)
        self.assertIn("merge_group:", on)
        self.assertIn("checks_requested", on)

    def test_labelling_a_pr_full_ci_runs_it_again(self):
        on = re.search(r"\non:\n(.*?)\n\S", self.ci, re.S).group(1)
        self.assertRegex(on, r"pull_request:\n    types: \[opened, synchronize, reopened, labeled\]")

    def test_queue_runs_never_cancel_each_other(self):
        """Only a PR's own runs share a group (a newer push cancels the older run); every other run has its own."""
        conc = re.search(r"\nconcurrency:\n(.*?)\n\S", self.ci, re.S).group(1)
        self.assertIn("format('ci-{0}-{1}', github.event_name, github.run_id)", conc)
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", conc)

    def test_the_slow_jobs_skip_only_on_a_pull_request_without_full_ci(self):
        for name in SLOW:
            self.assertIn("if: " + SKIP_ON_PR, job(self.ci, name), name)

    def test_gate_accepts_a_skip_only_for_the_slow_jobs_on_a_pull_request(self):
        g = job(self.ci, "gate")
        self.assertIn("if: always()", g)
        self.assertIn("EVENT: ${{ github.event_name }}", g)
        self.assertIn("FULL: ${{ contains(github.event.pull_request.labels.*.name, 'full-ci') }}", g)
        for name in SLOW:
            self.assertIn('.key == "%s"' % name, g)
        # in the queue and on pushes every job must pass: the full gate
        self.assertIn('all(.[]; .result == "success")', g)

    def test_releases_still_come_only_from_a_push_to_il_integration(self):
        self.assertIn("if: github.event_name == 'push' && github.ref == 'refs/heads/il/integration' && "
                      "needs.gate.result == 'success'", job(self.ci, "release"))


import importlib.util
import json
import stat
import subprocess
import tempfile

import sys  # noqa: E402

# test_ci_wiring's workflow parser (jobs, job_steps, step_run); it imports its siblings by bare name
for _d in (os.path.join(REPO, "infra", "deploy", "tests"), os.path.join(REPO, "infra", "deploy"),
           os.path.join(REPO, "infra", "ci")):
    if _d not in sys.path:
        sys.path.insert(0, _d)
_spec = importlib.util.spec_from_file_location(
    "ci_wiring", os.path.join(REPO, "infra", "deploy", "tests", "test_ci_wiring.py"))
ci_wiring = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ci_wiring)


def gate_step(ci, name):
    for step in ci_wiring.job_steps(ci_wiring.jobs(ci)["gate"]):
        if f"name: {name}" in step:
            return ci_wiring.step_run(step)
    raise AssertionError(f"gate has no step {name!r}")


class GateBehaviour(unittest.TestCase):
    """The gate's own scripts, run under bash -e (GitHub's default shell) on a table of cases."""

    def setUp(self):
        with open(CI) as f:
            self.ci = f.read()
        self.needs = ci_wiring.jobs(self.ci)["gate"]

    def verdict(self, results, event, full=False):
        needs = {k: {"result": v, "outputs": {}} for k, v in results.items()}
        env = dict(os.environ, NEEDS=json.dumps(needs), EVENT=event, FULL="true" if full else "false")
        return subprocess.run(["bash", "-e", "-c", gate_step(self.ci, "Every job passed")], env=env,
                              capture_output=True, text=True).returncode == 0

    def all_jobs(self, result="success", **override):
        names = [n.strip() for n in re.search(r"needs: \[([^\]]*)\]", self.needs).group(1).split(",")]
        return dict({n: result for n in names}, **override)

    def test_gate_needs_every_job_but_itself_and_the_release(self):
        names = {n.strip() for n in re.search(r"needs: \[([^\]]*)\]", self.needs).group(1).split(",")}
        self.assertEqual(names, set(ci_wiring.jobs(self.ci)) - {"gate", "release"})

    def test_the_fast_gate_only_on_an_unlabelled_pr_and_only_for_the_slow_jobs(self):
        fast = self.all_jobs(**{s: "skipped" for s in SLOW})
        self.assertTrue(self.verdict(fast, "pull_request"))
        self.assertFalse(self.verdict(fast, "pull_request", full=True))
        for event in ("merge_group", "push", "workflow_dispatch"):
            self.assertFalse(self.verdict(fast, event), event)
            self.assertTrue(self.verdict(self.all_jobs(), event), event)
        self.assertFalse(self.verdict(self.all_jobs(build="skipped"), "pull_request"))
        for bad in ("failure", "cancelled"):
            self.assertFalse(self.verdict(self.all_jobs(**{"ui-e2e": bad}), "pull_request"), bad)
            self.assertFalse(self.verdict(self.all_jobs(**{"maven": bad}), "merge_group"), bad)
        self.assertFalse(self.verdict({}, "push"))

    def run_jobs_check(self, jobs):
        """The second step with a stand-in gh that answers the run's jobs."""
        tmp = tempfile.mkdtemp(prefix="gate-jobs-")
        self.addCleanup(subprocess.run, ["rm", "-rf", tmp])
        with open(os.path.join(tmp, "jobs.fixture.json"), "w") as f:
            json.dump({"total_count": len(jobs), "jobs": jobs}, f)
        gh = os.path.join(tmp, "gh")
        with open(gh, "w") as f:
            f.write(f"#!/bin/sh\ncat {tmp}/jobs.fixture.json\n")
        os.chmod(gh, os.stat(gh).st_mode | stat.S_IEXEC)
        env = dict(os.environ, PATH=tmp + os.pathsep + os.environ["PATH"], GITHUB_REPOSITORY="o/r", GITHUB_RUN_ID="1")
        return subprocess.run(["bash", "-e", "-c", gate_step(self.ci, "No job of this run failed")], env=env,
                              cwd=tmp, capture_output=True, text=True)

    def test_a_cancelled_leg_needs_did_not_report_fails_the_gate(self):
        """GitHub can report a matrix job 'success' in needs when one leg was cancelled before a runner took it."""
        ok = [{"name": "Build (checkstyle, compile, package)", "conclusion": "success"},
              {"name": "UI end-to-end (lock and the other UI scripts)", "conclusion": "skipped"},
              {"name": "gate", "conclusion": None}, {"name": "Release (versioned build for the deployer)",
                                                     "conclusion": None}]
        self.assertEqual(self.run_jobs_check(ok).returncode, 0)
        bad = ok + [{"name": "SIM acceptance (tips board)", "conclusion": "cancelled"}]
        r = self.run_jobs_check(bad)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("SIM acceptance (tips board)", r.stdout)

    def test_the_shard_step_fails_when_its_script_fails(self):
        """`webui_shards.py … | tee` without pipefail exits 0 and leaves the leg with no scripts: green, ran nothing."""
        step = next(s for s in ci_wiring.job_steps(ci_wiring.jobs(self.ci)["ui-e2e"]) if "name: The leg's scripts" in s)
        self.assertRegex(step, r"\n\s+shell: bash(\s+#[^\n]*)?\n")  # GitHub runs bash as -eo pipefail
