"""Only the newest build artifact is kept (the user, 11-04 20:2x: 62 GB of artifacts, 50 GB of them openpnp-build):
every upload expires after a day, and after each green release a job deletes every openpnp-build but that run's own
and those of runs still going (their own jobs download theirs within the run; no job reads an older run's build)."""
import importlib.util
import json
import os
import re
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import prune_artifacts  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
CI = os.path.join(REPO, ".github", "workflows", "ci.yml")
for _d in (os.path.join(REPO, "infra", "deploy", "tests"), os.path.join(REPO, "infra", "deploy")):
    if _d not in sys.path:
        sys.path.insert(0, _d)
_spec = importlib.util.spec_from_file_location(
    "ci_wiring", os.path.join(REPO, "infra", "deploy", "tests", "test_ci_wiring.py"))
ci_wiring = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ci_wiring)


def art(i, run, expired=False, name="openpnp-build"):
    return {"id": i, "name": name, "expired": expired, "size_in_bytes": 208_000_000, "workflow_run": {"id": run}}


class Plan(unittest.TestCase):
    STATE = {1: ("completed", "success"), 2: ("in_progress",), 3: ("queued",), 4: ("completed", "cancelled"),
             5: None, 6: ("completed", "failure"), 7: ("completed", "timed_out")}

    def doomed(self, arts, keep=9):
        return [a["id"] for a in prune_artifacts.plan(arts, keep, self.STATE.get)]

    def test_completed_runs_builds_go_this_runs_and_unfinished_ones_stay(self):
        arts = [art(10, 1), art(20, 2), art(30, 3), art(40, 4), art(90, 9)]
        self.assertEqual(self.doomed(arts), [10, 40])

    def test_a_failed_runs_build_stays_for_its_re_run(self):
        """`gh run rerun --failed` reuses the first attempt's build: a failed or timed-out run keeps its own until it
        expires (a day)."""
        self.assertEqual(self.doomed([art(60, 6), art(70, 7)]), [])

    def test_an_unknown_run_or_status_is_kept(self):
        self.assertEqual(self.doomed([art(50, 5), {"id": 60, "name": "openpnp-build", "workflow_run": None}]), [])

    def test_expired_ones_are_left_to_github(self):
        self.assertEqual(self.doomed([art(10, 1, expired=True)]), [])


class Main(unittest.TestCase):
    """main() against a fake `gh api`: pages of 100, one status read per run, a DELETE per doomed artifact."""

    def run_main(self, pages, statuses, fail_delete=()):
        calls = []

        def gh(*args):
            calls.append(args)
            path = next(a for a in args if a.startswith("repos/"))
            if "-X" in args:
                ok = int(path.rsplit("/", 1)[1]) not in fail_delete
                return prune_artifacts.Result(0 if ok else 1, "", "" if ok else "HTTP 500")
            m = re.search(r"actions/runs/(\d+)$", path)
            if m:
                self.assertEqual(args[args.index("--jq") + 1], prune_artifacts.STATE_JQ)
                s = statuses.get(int(m.group(1)))  # real gh ends its output with a newline
                return prune_artifacts.Result(0 if s else 1, (s + "\n") if s else "", "" if s else "HTTP 404")
            page = int(re.search(r"[?&]page=(\d+)", path).group(1))
            return prune_artifacts.Result(0, json.dumps({"artifacts": pages[page - 1] if page <= len(pages) else []}), "")

        rc = prune_artifacts.main(["--repo", "o/r", "--keep-run", "9"], gh=gh)
        return rc, calls

    def test_pages_until_a_short_one_and_deletes_completed_runs_builds(self):
        full = [art(1000 + i, 1) for i in range(100)]
        rc, calls = self.run_main([full, [art(7, 2), art(8, 9)]], {1: "completed success", 2: "in_progress "})
        self.assertEqual(rc, 0)
        deletes = [c for c in calls if "-X" in c]
        self.assertEqual(len(deletes), 100)
        self.assertTrue(all(c[c.index("-X") + 1] == "DELETE" for c in deletes))
        self.assertEqual(sum(1 for c in calls if re.search(r"actions/runs/1( |$)", " ".join(c))), 1, "one read per run")
        self.assertTrue(any("page=2" in " ".join(c) for c in calls))
        self.assertFalse(any("page=3" in " ".join(c) for c in calls), "a page under 100 is the last")
        self.assertTrue(all("name=openpnp-build" in " ".join(c) for c in calls if "artifacts?" in " ".join(c)))

    def test_a_failed_delete_is_reported_and_the_rest_still_go(self):
        rc, calls = self.run_main([[art(1, 1), art(2, 1)]], {1: "completed success"}, fail_delete=(1,))
        self.assertEqual(rc, 1)
        self.assertEqual(len([c for c in calls if "-X" in c]), 2)

    def test_a_failed_listing_deletes_nothing(self):
        def gh(*args):
            return prune_artifacts.Result(1, "", "HTTP 502")
        self.assertEqual(prune_artifacts.main(["--repo", "o/r", "--keep-run", "9"], gh=gh), 1)


class Wiring(unittest.TestCase):
    def setUp(self):
        with open(CI) as f:
            self.ci = f.read()

    def test_every_upload_expires_after_a_day(self):
        steps = [s for s in re.split(r"\n      - ", self.ci) if "uses: actions/upload-artifact@" in s.split("\n", 1)[0]
                 or re.match(r"(name: [^\n]*\n\s*)?uses: actions/upload-artifact@", s)]
        self.assertGreater(len(steps), 5)
        for s in steps:
            self.assertIn("\n          retention-days: 1\n", s + "\n", s)

    def test_the_prune_job_runs_after_a_green_release_with_only_the_rights_it_needs(self):
        m = re.search(r"\n  prune-artifacts:\n(.*?)(?=\n  [a-z][a-z0-9-]*:\n|\Z)", self.ci, re.S)
        self.assertIsNotNone(m, "no prune-artifacts job")
        block = m.group(1)
        self.assertRegex(block, r"\n    needs: \[release\]\n")
        cond = ci_wiring.job_value(ci_wiring.jobs(self.ci)["prune-artifacts"], "if")
        for result, runs in (("success", True), ("failure", False), ("skipped", False), ("cancelled", False)):
            self.assertIs(ci_wiring.condition(cond, {"needs.release.result": result}), runs, result)
        self.assertRegex(block, r"\n    continue-on-error: true\n", "a failed or timed-out job never turns the run red")
        self.assertRegex(block, r"\n        timeout-minutes: [1-3]\n", "the step ends before the job's limit")
        perms = dict(re.findall(r"\n      ([a-z-]+): (read|write)", block.split("permissions:", 1)[1].split("steps:")[0]))
        self.assertEqual(perms, {"contents": "read", "actions": "write"})
        self.assertIn("continue-on-error: true", block, "a failed prune never turns a released run red")
        self.assertIn("infra/ci/prune_artifacts.py", block)
        self.assertIn('--keep-run "$GITHUB_RUN_ID"', block)


if __name__ == "__main__":
    unittest.main()
