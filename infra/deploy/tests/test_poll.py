"""poll: only green il/integration builds whose sha is on il/integration are staged, verified and atomic."""
import json
import os
import sys
import unittest

# il_deploy is one level up: also when unittest imports this module by bare name (no -t)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import il_deploy  # noqa: E402
from fakes import Lab  # noqa: E402


class Poll(unittest.TestCase):
    def setUp(self):
        self.lab = Lab()
        self.gh = self.lab.gh

    def tearDown(self):
        self.lab.cleanup()

    def poll(self):
        rc = self.lab.main("poll")
        self.assertEqual(rc, 0, self.lab.last_out + self.lab.last_err)
        return self.lab.last_out

    def releases(self):
        d = os.path.join(self.lab.root, "releases")
        return sorted(os.listdir(d)) if os.path.isdir(d) else []

    def refused(self):
        return {r["tag"]: r["reason"] for r in self.lab.journal() if r["event"] == "refused"}

    def test_head_build_is_staged(self):
        tag = self.gh.add_release(5, self.lab.c)
        self.poll()
        self.assertEqual(self.releases(), [tag])
        d = os.path.join(self.lab.root, "releases", tag)
        for name in ("openpnp-il.jar", "lib/dep-1.0.jar", "VERSION.txt", "BUILD-INFO.json", "STAGED.json"):
            self.assertTrue(os.path.exists(os.path.join(d, name)), name)
        with open(os.path.join(d, "VERSION.txt")) as f:
            self.assertEqual(f.read().strip(), tag)

    def test_ancestor_of_head_is_staged(self):
        tag = self.gh.add_release(4, self.lab.b)
        self.poll()
        self.assertEqual(self.releases(), [tag])

    def test_compare_direction(self):
        # base = il/integration head, head = the build's sha: an ancestor of the head is "behind" it
        self.gh.add_release(4, self.lab.b)
        self.poll()
        compares = [c for c in self.gh.calls if "/compare/" in c]
        self.assertEqual(compares, [f"repos/theinstancelabs/openpnp/compare/{self.lab.c}...{self.lab.b}"])
        self.assertEqual(self.gh.compare(self.lab.c, self.lab.b), "behind")
        self.assertEqual(il_deploy.ELIGIBLE_COMPARE, ("identical", "behind"))

    def test_scratch_merge_is_refused(self):
        side = self.gh.commit("feature")
        scratch = self.gh.commit("scratch merge", self.lab.c, side)
        tag = self.gh.add_release(6, scratch)
        self.poll()
        self.assertEqual(self.releases(), [])
        self.assertIn("scratch", self.refused()[tag])
        self.assertIn("ahead", self.refused()[tag])

    def test_diverged_is_refused(self):
        other = self.gh.commit("other", self.lab.a)
        tag = self.gh.add_release(6, other)
        self.poll()
        self.assertEqual(self.releases(), [])
        self.assertIn("diverged", self.refused()[tag])

    def test_unknown_commit_is_refused(self):
        stranger = "f" * 40
        tag = self.gh.add_release(6, stranger)
        self.poll()
        self.assertEqual(self.releases(), [])
        self.assertIn(tag, self.refused())

    def test_gate_failed_is_refused(self):
        tag = self.gh.add_release(5, self.lab.c, gate="failure")
        self.poll()
        self.assertEqual(self.releases(), [])
        self.assertIn("gate", self.refused()[tag])

    def test_gate_rerun_success_counts(self):
        self.gh.add_release(5, self.lab.c, gate="failure")
        self.gh.set_gate(self.lab.c, "success")
        self.poll()
        self.assertEqual(len(self.releases()), 1)

    def test_gate_missing_or_running_is_refused(self):
        tag = self.gh.add_release(5, self.lab.c, gate=None)
        self.gh.set_gate(self.lab.c, conclusion=None, status="in_progress")
        self.poll()
        self.assertEqual(self.releases(), [])
        self.assertIn("gate", self.refused()[tag])
        self.gh.checks.clear()
        self.poll()
        self.assertIn("gate", self.refused()[tag])

    def test_other_check_named_gate_only(self):
        tag = self.gh.add_release(5, self.lab.c, gate=None)
        self.gh.set_gate(self.lab.c, "success", name="build")
        self.poll()
        self.assertEqual(self.releases(), [])
        self.assertIn("gate", self.refused()[tag])

    def test_wrong_branch_is_refused(self):
        tag = self.gh.add_release(5, self.lab.c, branch="il/scratch")
        self.poll()
        self.assertEqual(self.releases(), [])
        self.assertIn("il/scratch", self.refused()[tag])

    def test_sha256_mismatch_is_refused(self):
        tag = self.gh.add_release(5, self.lab.c, corrupt_sha256=True)
        self.poll()
        self.assertEqual(self.releases(), [])
        self.assertIn("sha256", self.refused()[tag])
        self.assertEqual([n for n in os.listdir(os.path.join(self.lab.root, "releases"))], [])

    def test_metadata_mismatch_is_refused(self):
        t1 = self.gh.add_release(5, self.lab.c, target=self.lab.b)
        self.poll()
        self.assertIn("target_commitish", self.refused()[t1])
        t2 = self.gh.add_release(6, self.lab.c, info_overrides={"sha": self.lab.b})
        self.poll()
        self.assertIn(t2, self.refused())
        self.assertEqual(self.releases(), [])

    def test_jar_hash_mismatch_is_refused(self):
        tag = self.gh.add_release(5, self.lab.c, info_overrides={"jar_sha256": "0" * 64})
        self.poll()
        self.assertEqual(self.releases(), [])
        self.assertIn("jar", self.refused()[tag])

    def test_missing_asset_is_refused(self):
        tag = self.gh.add_release(5, self.lab.c, omit=("BUILD-INFO.json",))
        self.poll()
        self.assertIn("BUILD-INFO.json", self.refused()[tag])

    def test_bad_tarball_layout_is_refused(self):
        tag = self.gh.add_release(5, self.lab.c, members={"../evil": b"x", "openpnp-il/openpnp-il.jar": b"{}"})
        self.poll()
        self.assertEqual(self.releases(), [])
        self.assertIn(tag, self.refused())
        self.assertFalse(os.path.exists(os.path.join(self.lab.root, "evil")))

    # --- provenance: the artifact is CI's, not only the commit (a dirty tree built at the head reports the head's sha)
    def test_release_not_published_by_ci_is_refused(self):
        hand = self.gh.add_release(9999, self.lab.c, author="aabdel0181", run=False)
        ci = self.gh.add_release(5, self.lab.c)
        self.poll()
        self.assertEqual(self.releases(), [ci])
        self.assertIn("github-actions[bot]", self.refused()[hand])
        self.gh.downloads.clear()
        self.poll()
        self.assertEqual(self.gh.downloads, [], "a permanent refusal: not downloaded again")

    def test_release_whose_run_is_not_cis_push_on_the_branch_is_refused(self):
        for overrides, word in (({"event": "pull_request"}, "pull_request"),
                                ({"event": "workflow_dispatch"}, "workflow_dispatch"),
                                ({"head_branch": "il/scratch"}, "il/scratch"),
                                ({"path": ".github/workflows/scratch.yml"}, "scratch.yml"),
                                ({"head_sha": self.lab.b}, self.lab.b[:7]),
                                ({"run_number": 77}, "77"),
                                ({"conclusion": "failure"}, "failure")):
            with self.subTest(word=word):
                lab = Lab()
                try:
                    tag = lab.gh.add_release(5, lab.c, run_overrides=overrides)
                    self.assertEqual(lab.main("poll"), 0, lab.last_out)
                    refused = {r["tag"]: r["reason"] for r in lab.journal() if r["event"] == "refused"}
                    self.assertIn(word, refused.get(tag, ""), refused)
                    self.assertFalse(os.path.isdir(os.path.join(lab.root, "releases", tag)))
                finally:
                    lab.cleanup()

    def test_release_whose_run_is_unknown_is_refused(self):
        tag = self.gh.add_release(5, self.lab.c, run=False)
        self.poll()
        self.assertEqual(self.releases(), [])
        self.assertIn("run", self.refused()[tag])

    def test_release_whose_run_is_still_running_is_staged_later(self):
        """The release job publishes before its run ends: not yet, but not a permanent refusal."""
        tag = self.gh.add_release(5, self.lab.c, run_overrides={"status": "in_progress", "conclusion": None})
        self.poll()
        self.assertEqual(self.releases(), [])
        self.assertIn("in_progress", self.refused()[tag])
        self.gh.runs[1005].update(status="completed", conclusion="success")
        self.poll()
        self.assertEqual(self.releases(), [tag])

    def test_newest_eligible_wins(self):
        good = self.gh.add_release(10, self.lab.c)
        side = self.gh.commit("feature")
        scratch = self.gh.commit("scratch", self.lab.c, side)
        bad = self.gh.add_release(11, scratch)
        self.gh.add_release(9, self.lab.b)
        self.poll()
        self.assertEqual(self.releases(), [good])
        self.assertIn(bad, self.refused())

    def test_ordering_by_run_number_not_list_order(self):
        old = self.gh.add_release(9, self.lab.b)
        new = self.gh.add_release(12, self.lab.c)
        self.gh.releases.reverse()
        self.poll()
        self.assertEqual(self.releases(), [new])
        self.assertNotIn(old, self.releases())

    def test_already_staged_costs_one_call(self):
        self.gh.add_release(5, self.lab.c)
        self.poll()
        self.gh.calls.clear()
        self.gh.downloads.clear()
        self.poll()
        self.assertEqual(len(self.gh.calls), 1)
        self.assertEqual(self.gh.downloads, [])

    def test_bad_listed_release_is_skipped(self):
        tag = self.gh.add_release(5, self.lab.c)
        os.makedirs(self.lab.root, exist_ok=True)
        with open(os.path.join(self.lab.root, "bad.json"), "w") as f:
            json.dump({tag: {"reason": "health failed", "at": "2026-10-31T20:00:00"}}, f)
        self.poll()
        self.assertEqual(self.releases(), [])

    def test_permanent_refusal_is_not_rechecked(self):
        self.gh.add_release(5, self.lab.c, branch="il/scratch")
        self.poll()
        self.gh.downloads.clear()
        self.poll()
        self.assertEqual(self.gh.downloads, [])
        self.assertEqual(len([r for r in self.lab.journal() if r["event"] == "refused"]), 1)

    def test_non_build_tags_ignored(self):
        self.gh.releases.append({"tag_name": "2.6", "target_commitish": "main", "draft": False, "assets": []})
        self.poll()
        self.assertEqual(self.releases(), [])

    def test_tag_parsing(self):
        self.assertEqual(il_deploy.parse_tag("il-build-12-abc1234"), (12, "abc1234"))
        for bad in ("il-build-12", "il-build-x-abc1234", "il-build-12-ABC1234", "v1.0", "il-build-12-abc12345"):
            self.assertIsNone(il_deploy.parse_tag(bad), bad)


if __name__ == "__main__":
    unittest.main()
