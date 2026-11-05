"""ci.yml for a merge queue: a fast gate on pull requests, the full gate in the queue (merge_group); a push of a commit
the queue gated skips each test job that commit's queue run passed.

Run from the repo root: python3 -m unittest discover -s infra/ci/tests -t infra/ci
"""
import os
import re
import unittest

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
CI = os.path.join(REPO, ".github", "workflows", "ci.yml")
SLOW = ("ui-e2e", "acceptance")  # skipped on a PR without the full-ci label; always run in the queue and on pushes
TESTS = ("infra", "maven", "deploy-e2e", "controller-e2e", "ui-e2e", "ui-boardview", "acceptance")  # the test jobs
SHA = "5ddcd936ff4077e06b3edd8293a3bc8f5b3052f7"


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

    def runs(self, name, event, passed=(), labels=()):
        """Whether job `name` runs for this event, the queued job's answer and the PR's labels (its if:)."""
        cond = ci_wiring.job_value(ci_wiring.jobs(self.ci)[name], "if")
        ctx = {"github.event_name": event, "needs.queued.outputs.passed": json.dumps(list(passed)),
               "github.event.pull_request.labels.*.name": list(labels)}
        return cond is None or ci_wiring.condition(cond, ctx)

    def test_the_slow_jobs_skip_only_on_a_pull_request_without_full_ci(self):
        for name in SLOW:
            self.assertFalse(self.runs(name, "pull_request"), name)
            self.assertTrue(self.runs(name, "pull_request", labels=["full-ci"]), name)
            for event in ("merge_group", "push", "workflow_dispatch"):
                self.assertTrue(self.runs(name, event), (name, event))

    def test_a_push_skips_a_test_job_only_if_the_commits_queue_run_passed_that_job(self):
        js = ci_wiring.jobs(self.ci)
        self.assertEqual(set(TESTS), set(js) - {"build", "queued", "gate", "release", "prune-artifacts"})  # a new job must decide too
        for name in TESTS:
            needs = [n.strip() for n in (ci_wiring.job_value(js[name], "needs") or "").strip("[]").split(",")]
            self.assertIn("queued", needs, name)
            self.assertFalse(self.runs(name, "push", passed=[name]), name)
            self.assertTrue(self.runs(name, "push", passed=[t for t in TESTS if t != name]), name)
            self.assertTrue(self.runs(name, "push"), name)
            for event in ("pull_request", "merge_group", "workflow_dispatch"):  # queued answers [] there
                self.assertTrue(self.runs(name, event, labels=["full-ci"]), (name, event))

    def test_the_queued_job_may_read_the_queue_runs_and_is_bounded(self):
        block = ci_wiring.jobs(self.ci)["queued"]
        perms = {k: v.split("#")[0].strip() for k, v in ci_wiring.job_map(block, "permissions").items()}
        self.assertEqual(perms, {"contents": "read", "actions": "read"})
        self.assertRegex(block, r"\n    timeout-minutes: [1-5]\n")
        # a stalled API call must answer [] (every test job runs), not time the job out (every test job skipped)
        step = ci_wiring.step_run(next(s for s in ci_wiring.job_steps(block) if "run:" in s))
        calls = re.findall(r"(\S+\s+\S+\s+)?\bgh (api|run download)\b", step)
        self.assertEqual(len(calls), 3, step)  # the queue runs, their gate job, the artifact
        for prefix, _ in calls:
            self.assertRegex(prefix, r"^timeout \d+\s+$")
        minutes = int(re.search(r"\n    timeout-minutes: (\d+)\n", block).group(1))
        self.assertLess(sum(int(p.split()[1]) for p, _ in calls), 60 * minutes - 30)  # the calls end before the job

    def test_the_gate_is_bounded_and_pushes_keep_the_cache_prs_restore(self):
        js = ci_wiring.jobs(self.ci)
        self.assertRegex(js["gate"], r"\n    timeout-minutes: ([1-9]|10)\b")  # a hung step would hold the queue's check
        # a PR restores the Maven cache its base branch's pushes saved (GitHub's cache scopes): without them every PR
        # run downloads everything from Maven Central, which answers 429 under load (seen 11-04 on #34)
        for name in ("build", "maven"):
            m = re.search(r"cache: \$\{\{ (.*?) \}\}", js[name])
            ctx = lambda e: {"github.event_name": e}
            for event in ("pull_request", "push", "workflow_dispatch"):
                self.assertEqual(ci_wiring.interpolate("${{ %s }}" % m.group(1), ctx(event)), "maven", (name, event))
            self.assertEqual(ci_wiring.interpolate("${{ %s }}" % m.group(1), ctx("merge_group")), "", name)

    def test_the_build_and_the_queued_job_always_run(self):
        js = ci_wiring.jobs(self.ci)
        for name in ("build", "queued"):
            self.assertIsNone(ci_wiring.job_value(js[name], "if"), name)
            self.assertIsNone(ci_wiring.job_value(js[name], "needs"), name)

    def test_the_queue_gate_keeps_what_its_run_passed_by_job(self):
        """After both verdicts, a merge_group gate uploads its needs (by job id) as gate-needs. A failed upload never
        fails the gate, so it cannot eject a PR: the push then runs every test job."""
        steps = ci_wiring.job_steps(ci_wiring.jobs(self.ci)["gate"])
        verdicts = [i for i, s in enumerate(steps) if "name: Every job passed" in s or "name: No job of this run" in s]
        keep = [i for i, s in enumerate(steps) if "needs.json" in s]
        self.assertEqual(len(verdicts), 2)
        self.assertEqual(len(keep), 2)
        self.assertGreater(min(keep), max(verdicts))
        for i in keep:
            self.assertIn("if: github.event_name == 'merge_group'", steps[i])
            self.assertIn("continue-on-error: true", steps[i])
            self.assertRegex(steps[i], r"\n\s+timeout-minutes: [12](\s+#[^\n]*)?\n")  # a hang must not hold the gate
        write, upload = (steps[i] for i in keep)
        self.assertIn("NEEDS: ${{ toJSON(needs) }}", write)
        self.assertIn('echo "$NEEDS" > needs.json', write)
        self.assertIn("actions/upload-artifact", upload)
        self.assertIn("name: gate-needs", upload)

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
        self.assertIn("if: ${{ !cancelled() && github.event_name == 'push' && github.ref == 'refs/heads/il/integration' "
                      "&& needs.gate.result == 'success' }}", job(self.ci, "release"))  # !cancelled(): see release_problems


import importlib.util
import json
import shutil
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

    def verdict(self, results, event, full=False, passed=()):
        needs = {k: {"result": v, "outputs": {}} for k, v in results.items()}
        if "queued" in needs:
            needs["queued"]["outputs"] = {"passed": json.dumps(list(passed))}
        env = dict(os.environ, NEEDS=json.dumps(needs), EVENT=event, FULL="true" if full else "false")
        return subprocess.run(["bash", "-e", "-c", gate_step(self.ci, "Every job passed")], env=env,
                              capture_output=True, text=True).returncode == 0

    def all_jobs(self, result="success", **override):
        names = [n.strip() for n in re.search(r"needs: \[([^\]]*)\]", self.needs).group(1).split(",")]
        return dict({n: result for n in names}, **override)

    def test_gate_needs_every_job_but_itself_and_the_release(self):
        names = {n.strip() for n in re.search(r"needs: \[([^\]]*)\]", self.needs).group(1).split(",")}
        self.assertEqual(names, set(ci_wiring.jobs(self.ci)) - {"gate", "release", "prune-artifacts"})

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

    def test_a_push_may_skip_exactly_the_test_jobs_its_queue_run_passed(self):
        skipped = {t: "skipped" for t in TESTS}
        everything = list(TESTS) + ["build", "queued"]
        self.assertTrue(self.verdict(self.all_jobs(**skipped), "push", passed=everything))
        self.assertTrue(self.verdict(self.all_jobs(**skipped), "push", passed=TESTS))
        # a partial queue run (say, a label-dependent workflow ran no UI end-to-end or SIM acceptance): those must pass
        partial = [t for t in TESTS if t not in SLOW]
        self.assertFalse(self.verdict(self.all_jobs(**skipped), "push", passed=partial))
        self.assertTrue(self.verdict(self.all_jobs(**{t: "skipped" for t in partial}), "push", passed=partial))
        self.assertFalse(self.verdict(self.all_jobs(infra="skipped"), "push"))  # the queue passed nothing
        self.assertTrue(self.verdict(self.all_jobs(), "push", passed=TESTS))  # ran anyway and passed
        for bad in ("failure", "cancelled"):
            self.assertFalse(self.verdict(self.all_jobs(**dict(skipped, maven=bad)), "push", passed=TESTS), bad)
            self.assertFalse(self.verdict(self.all_jobs(**dict(skipped, queued=bad)), "push", passed=TESTS), bad)
        self.assertFalse(self.verdict(self.all_jobs(**dict(skipped, build="skipped")), "push", passed=everything))
        for event in ("merge_group", "workflow_dispatch"):  # only a push may skip
            self.assertFalse(self.verdict(self.all_jobs(**skipped), event, passed=everything), event)
        self.assertFalse(self.verdict(self.all_jobs(**skipped), "pull_request", full=True, passed=everything))
        self.assertFalse(self.verdict(self.all_jobs(infra="skipped"), "pull_request", passed=everything))

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


# A stand-in gh: `api …/runs/N/jobs…` answers $FAKE/jobs-N.json, any other `api` $FAKE/runs.json (none there: the
# call fails); `run download … -D dir` puts $FAKE/needs.json into dir (none there: no artifact). Every call is logged
# to $FAKE/gh.log.
FAKE_GH = """#!/bin/sh
printf '%s\\n' "$*" >> "$FAKE/gh.log"
case "$1" in
  api) case "$2" in */jobs*) id=${2#*/runs/}; exec cat "$FAKE/jobs-${id%%/*}.json" ;; *) exec cat "$FAKE/runs.json" ;; esac ;;
  run) { [ "$2" = download ] && [ -f "$FAKE/needs.json" ]; } || { echo "no valid artifacts found" >&2; exit 1; }
       dir=.; while [ $# -gt 0 ]; do [ "$1" = -D ] && dir=$2; shift; done
       mkdir -p "$dir" && exec cp "$FAKE/needs.json" "$dir/needs.json" ;;
  *) exit 2 ;;
esac
"""


class QueueVerdict(unittest.TestCase):
    """The queued job's script under bash -e with a stand-in gh: which test jobs a push of this commit may skip."""
    NEEDS = {n: {"result": "success", "outputs": {}} for n in ("queued", "build") + TESTS}
    GATE_OK = [{"name": "build", "status": "completed", "conclusion": "success"},
               {"name": "gate", "status": "completed", "conclusion": "success"}]

    def setUp(self):
        with open(CI) as f:
            block = ci_wiring.jobs(f.read())["queued"]
        self.step = next(ci_wiring.step_run(s) for s in ci_wiring.job_steps(block) if "run:" in s)
        self.tmp = tempfile.mkdtemp(prefix="queued-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        gh = os.path.join(self.tmp, "bin", "gh")
        os.makedirs(os.path.dirname(gh))
        with open(gh, "w") as f:
            f.write(FAKE_GH)
        os.chmod(gh, os.stat(gh).st_mode | stat.S_IEXEC)

    @staticmethod
    def queue_run(**over):
        return dict({"id": 41, "event": "merge_group", "path": ".github/workflows/ci.yml", "head_sha": SHA,
                     "head_branch": "gh-readonly-queue/il/integration/pr-29-" + "a" * 40, "status": "completed",
                     "conclusion": "success"}, **over)

    def passed(self, runs, needs, event="push", ref="refs/heads/il/integration", jobs=GATE_OK):
        """(the step's passed list, the gh calls) for these merge_group runs (None: the API call fails), this
        gate-needs artifact (None: none; a str: its raw text) and run 41's jobs (None: the API call fails; a dict:
        the jobs by run id)."""
        fake = os.path.join(self.tmp, "fake")
        shutil.rmtree(fake, True)
        os.makedirs(fake)
        if runs is not None:
            with open(os.path.join(fake, "runs.json"), "w") as f:
                json.dump({"total_count": len(runs), "workflow_runs": runs}, f)
        for run_id, run_jobs in (jobs if isinstance(jobs, dict) else {41: jobs}).items():
            if run_jobs is not None:
                with open(os.path.join(fake, "jobs-%d.json" % run_id), "w") as f:
                    json.dump({"total_count": len(run_jobs), "jobs": run_jobs}, f)
        if needs is not None:
            with open(os.path.join(fake, "needs.json"), "w") as f:
                f.write(needs if isinstance(needs, str) else json.dumps(needs))
        work = tempfile.mkdtemp(dir=self.tmp)
        out = os.path.join(work, "github_output")
        env = dict(os.environ, PATH=os.path.join(self.tmp, "bin") + os.pathsep + os.environ["PATH"], FAKE=fake,
                   EVENT=event, GITHUB_REF=ref, GITHUB_SHA=SHA, GITHUB_REPOSITORY="o/r", GITHUB_OUTPUT=out)
        r = subprocess.run(["bash", "-e", "-c", self.step], env=env, cwd=work, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)  # never fails: anything unexpected answers []
        with open(out) as f:
            lines = f.read().splitlines()
        self.assertEqual(len(lines), 1, lines)
        key, _, value = lines[0].partition("=")
        self.assertEqual(key, "passed")
        log = os.path.join(fake, "gh.log")
        calls = open(log).read().splitlines() if os.path.exists(log) else []
        return json.loads(value), calls

    def test_a_push_skips_what_the_commits_queue_run_passed(self):
        passed, calls = self.passed([self.queue_run()], self.NEEDS)
        self.assertEqual(passed, sorted(("queued", "build") + TESTS))
        self.assertEqual(len(calls), 3, calls)
        self.assertIn("api repos/o/r/actions/runs?event=merge_group&head_sha=" + SHA, calls[0])
        self.assertIn("api repos/o/r/actions/runs/41/jobs?filter=latest", calls[1])
        self.assertRegex(calls[2], r"^run download 41 ")
        self.assertIn("-R o/r", calls[2])
        self.assertIn("-n gate-needs", calls[2])

    def test_the_queue_runs_gate_job_counts_before_github_marks_the_run_completed(self):
        """11-04, in an Actions incident: #36's queue run read in_progress 12 min after its last job, so its push ran
        every test job again. The queue merges on the gate job's check: that job has finished when the push looks."""
        self.assertEqual(self.passed([self.queue_run(status="in_progress", conclusion=None)], self.NEEDS)[0],
                         sorted(("queued", "build") + TESTS))

    def test_a_job_the_queue_run_did_not_run_runs_on_the_push(self):
        """A partial or label-dependent queue run (here: no UI end-to-end, no SIM acceptance): those jobs run here."""
        needs = dict(self.NEEDS, **{s: {"result": "skipped", "outputs": {}} for s in SLOW})
        self.assertEqual(self.passed([self.queue_run()], needs)[0],
                         sorted(n for n in ("queued", "build") + TESTS if n not in SLOW))

    def test_anything_unexpected_runs_every_test_job(self):
        other_queue = "gh-readonly-queue/main/pr-3-" + "b" * 40
        cases = {
            "the API call fails": (None, self.NEEDS),
            "no artifact (an older workflow, or its upload failed)": ([self.queue_run()], None),
            "the artifact is not JSON": ([self.queue_run()], "{oops"),
            "the artifact is empty": ([self.queue_run()], " \n"),
            "the artifact is not the gate's needs": ([self.queue_run()], '["infra"]'),
            "a need that is not an object": ([self.queue_run()], '{"infra": "success"}'),
            "no queue run of this commit": ([], self.NEEDS),
            "the queue run failed": ([self.queue_run(conclusion="failure")], self.NEEDS),
            "the queue run is still going": ([self.queue_run(status="in_progress", conclusion=None)], self.NEEDS),
            "another workflow": ([self.queue_run(path=".github/workflows/build_and_deploy.yml")], self.NEEDS),
            "another branch's queue": ([self.queue_run(head_branch=other_queue)], self.NEEDS),
            "another commit": ([self.queue_run(head_sha="f" * 40)], self.NEEDS),
            "not a queue run": ([self.queue_run(event="push")], self.NEEDS),
        }
        running = [dict(self.GATE_OK[1], status="in_progress", conclusion=None)]
        jobs_cases = {
            "its gate job is still going": running,
            "its gate job failed": [dict(self.GATE_OK[1], conclusion="failure")],
            "its gate job was cancelled": [dict(self.GATE_OK[1], conclusion="cancelled")],
            "no gate job in its jobs": self.GATE_OK[:1],
            "two gate jobs (filter=all lists every attempt's)": self.GATE_OK + running,
            "the jobs call fails": None,
        }
        for why, jobs in jobs_cases.items():
            with self.subTest(why):
                running_run = self.queue_run(status="in_progress", conclusion=None)
                self.assertEqual(self.passed([running_run], self.NEEDS, jobs=jobs)[0], [])
        for why, (runs, needs) in cases.items():
            with self.subTest(why):
                self.assertEqual(self.passed(runs, needs, jobs=running if "still going" in why else self.GATE_OK)[0],
                                 [])

    def test_the_newest_queue_run_of_the_commit_counts(self):
        """Two queue runs of one commit (an incident's retry): the newer one decides; still running, it answers []."""
        runs = [self.queue_run(), self.queue_run(id=42, status="in_progress", conclusion=None)]
        running = [dict(self.GATE_OK[1], status="in_progress", conclusion=None)]
        self.assertEqual(self.passed(runs, self.NEEDS, jobs={41: self.GATE_OK, 42: running})[0], [])
        self.assertEqual(self.passed(runs, self.NEEDS, jobs={41: running, 42: self.GATE_OK})[0],
                         sorted(("queued", "build") + TESTS))

    def test_only_a_push_to_il_integration_asks(self):
        queue_ref = "refs/heads/gh-readonly-queue/il/integration/pr-7-" + "a" * 40
        for event, ref in (("pull_request", "refs/pull/7/merge"), ("merge_group", queue_ref),
                           ("workflow_dispatch", "refs/heads/il/integration"), ("push", "refs/heads/il/scratch")):
            with self.subTest(event=event, ref=ref):
                self.assertEqual(self.passed([self.queue_run()], self.NEEDS, event=event, ref=ref), ([], []))
