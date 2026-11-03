"""GhAttestation: `gh attestation verify` of a release tarball, the way CI's release job attests it
(actions/attest-build-provenance), and the run in its JSON. gh itself is faked (tests/fakes.py FakeGhCli); the JSON
shape is checked against a real output too (gh's own release, tests/gh-attestation-verify-cli-2.97.0.json)."""
import json
import os
import shutil
import sys
import tempfile
import unittest
from unittest import mock

# il_deploy is one level up: also when unittest imports this module by bare name (no -t)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import il_deploy  # noqa: E402
from fakes import BRANCH, REPO, FakeGhCli, FakeGitHub, sha_of  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
SHA = sha_of("c")
RUN = 1005


class Verify(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="il-attest-test-")
        self.tar = os.path.join(self.dir, "openpnp-il-%s.tar.gz" % SHA[:7])
        self.data = b"a release tarball"
        with open(self.tar, "wb") as f:
            f.write(self.data)
        self.gh = FakeGitHub()
        self.cli = FakeGhCli(self.gh)

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def verifier(self, **kw):
        if kw:
            self.cli = FakeGhCli(self.gh, **kw)
        return il_deploy.GhAttestation(gh="gh", runner=self.cli)

    def verify(self, run_id=RUN, sha=SHA, **kw):
        v = self.verifier(**kw)
        v.ready()
        return v.verify(self.tar, REPO, BRANCH, sha, run_id)

    def refused(self, **kw):
        with self.assertRaises(il_deploy.Refused) as cm:
            self.verify(**kw)
        return cm.exception

    # --- accepted ------------------------------------------------------------------------------------------------
    def test_cis_attestation_of_the_run_is_accepted(self):
        self.gh.attest(self.data, RUN, SHA)
        why = self.verify()
        self.assertIn(f"https://github.com/{REPO}/actions/runs/{RUN}/attempts/1", why)
        self.assertEqual(self.cli.verified[-1][0], self.tar)

    def test_the_command_line(self):
        """Exactly the policy: CI's workflow, the branch, the build's full sha, GitHub-hosted runners, JSON."""
        self.gh.attest(self.data, RUN, SHA)
        self.verify(run_id=str(RUN))  # BUILD-INFO's run_id is a string (make_release.py)
        verify = [c for c in self.cli.calls if c[1:3] == ["attestation", "verify"] and c[3] != "--help"]
        self.assertEqual(verify, [["gh", "attestation", "verify", self.tar, "--repo", REPO,
                                   "--signer-workflow", f"{REPO}/.github/workflows/ci.yml",
                                   "--source-ref", "refs/heads/il/integration", "--source-digest", SHA,
                                   "--deny-self-hosted-runners", "--format", "json"]])

    def test_a_rerun_attempt_of_the_run_is_the_run(self):
        self.gh.attest(self.data, RUN, SHA, attempt=2)
        self.assertIn(f"runs/{RUN}/attempts/2", self.verify())

    def test_one_matching_attestation_among_others_is_enough(self):
        self.gh.attest(self.data, 2001, SHA)
        self.gh.attest(self.data, RUN, SHA)
        self.assertIn(f"runs/{RUN}/", self.verify())

    def test_the_real_json_shape(self):
        """gh 2.97.0's output for its own linux_amd64 tarball (deployment.yml, workflow_dispatch on trunk)."""
        with open(os.path.join(HERE, "gh-attestation-verify-cli-2.97.0.json")) as f:
            entries = json.load(f)
        sha = "55dbb4dc6b7edb10b48e3d7fc5bccd32318d1b55"
        args = dict(repo="cli/cli", branch="trunk", sha=sha, workflow=".github/workflows/deployment.yml")
        self.assertEqual(il_deploy.attestation_problems(entries[0], run_id="30597407850", **args), [])
        problems = il_deploy.attestation_problems(entries[0], run_id="30597407851", **args)
        self.assertEqual(len(problems), 1)
        self.assertIn("30597407850", problems[0])
        self.assertTrue(il_deploy.attestation_problems(entries[0], run_id="30597407850",
                                                       **dict(args, branch="main")))
        self.assertTrue(il_deploy.attestation_problems(entries[0], run_id="30597407850",
                                                       **dict(args, workflow=".github/workflows/ci.yml")))

    # --- a definite mismatch: refused for good -------------------------------------------------------------------
    def test_attested_by_another_run_is_refused_for_good(self):
        self.gh.attest(self.data, 2001, SHA)
        e = self.refused()
        self.assertTrue(e.permanent)
        self.assertIn("2001", str(e))
        self.assertIn(str(RUN), str(e))

    def test_attested_by_another_workflow_ref_commit_or_runner_is_refused_for_good(self):
        for overrides, word in (({"workflow": ".github/workflows/release-by-hand.yml"}, "release-by-hand.yml"),
                                ({"ref": "refs/heads/il/scratch"}, "il/scratch"),
                                ({"sha": sha_of("b")}, sha_of("b")),
                                ({"runner": "self-hosted"}, "self-hosted"),
                                ({"invocation": f"https://github.com/{REPO}/actions/runs/2001/attempts/1"}, "2001")):
            with self.subTest(word=word):
                self.gh.attestations.clear()
                self.gh.attest(self.data, RUN, SHA, **overrides)
                e = self.refused()
                self.assertTrue(e.permanent, str(e))
                self.assertIn(word, str(e))

    # --- cannot verify: refused, retried at the next poll -------------------------------------------------------
    def test_no_attestation_is_retried(self):
        e = self.refused()
        self.assertFalse(e.permanent)
        self.assertIn("no attestations found", str(e))

    def test_an_attestation_in_another_repo_only_is_retried(self):
        self.gh.attest(self.data, RUN, SHA, repo="someone/fork")
        self.assertFalse(self.refused().permanent)

    def test_no_gh_is_retried(self):
        e = self.refused(installed=False)
        self.assertFalse(e.permanent)
        self.assertIn("gh", str(e))
        with mock.patch.dict(os.environ, {"PATH": self.dir}):
            with self.assertRaises(il_deploy.Refused) as cm:
                il_deploy.GhAttestation().ready()
        self.assertFalse(cm.exception.permanent)
        self.assertIn("no gh", str(cm.exception))

    def test_gh_without_the_attestation_command_is_retried(self):
        e = self.refused(attestation_command=False)
        self.assertFalse(e.permanent)
        self.assertIn("update gh", str(e))

    def test_gh_without_a_policy_flag_is_retried(self):
        flags = tuple(f for f in FakeGhCli(self.gh).flags if f != "--source-digest")
        e = self.refused(flags=flags)
        self.assertFalse(e.permanent)
        self.assertIn("--source-digest", str(e))

    def test_gh_not_logged_in_is_retried(self):
        e = self.refused(logged_in=False)
        self.assertFalse(e.permanent)
        self.assertIn("logged in", str(e))

    def test_a_network_error_is_retried(self):
        self.gh.attest(self.data, RUN, SHA)
        e = self.refused(offline=True)
        self.assertFalse(e.permanent)
        self.assertIn("Sigstore", str(e))

    def test_unreadable_json_is_retried(self):
        self.gh.attest(self.data, RUN, SHA)
        for out in ("not json", "{}", "[]", '[{"verificationResult": {}}]'):
            with self.subTest(out=out):
                e = self.refused(stdout=out)
                self.assertFalse(e.permanent, str(e))

    def test_gh_timing_out_is_retried(self):
        def runner(argv, **kw):
            raise il_deploy.subprocess.TimeoutExpired(argv, kw.get("timeout"))
        with self.assertRaises(il_deploy.Refused) as cm:
            il_deploy.GhAttestation(gh="gh", runner=runner).ready()
        self.assertFalse(cm.exception.permanent)

    def test_ready_asks_gh_once(self):
        v = self.verifier()
        v.ready()
        v.ready()
        self.assertEqual(len(self.cli.calls), 2)  # attestation verify --help, auth status


if __name__ == "__main__":
    unittest.main()
