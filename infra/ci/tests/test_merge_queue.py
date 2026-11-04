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
