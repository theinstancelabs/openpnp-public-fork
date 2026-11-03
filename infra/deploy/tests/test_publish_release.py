"""infra/ci/publish_release.py, the release job's Publish step. No release at the tag: it publishes this run's three
files. A release already there (a re-run finds what an earlier attempt published) passes only if it is this run's own,
judged as il_deploy judges a release: published by CI's github.token, at this commit, exactly the three files,
BUILD-INFO naming this run, the tarball matching its .sha256 and attested by this run (gh attestation verify with the
deployer's policy, then the run in the certificate). Anything else at the tag fails the step: whoever put it there
first must not leave the run green while its build is never released. gh is FakeGhCli over FakeGitHub."""
import contextlib
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

# publish_release and make_release are CI's (infra/ci); fakes puts that on sys.path too
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "ci"))
import il_deploy  # noqa: E402
import make_release  # noqa: E402
import publish_release  # noqa: E402
from fakes import BRANCH, REPO, FakeGhCli, FakeGitHub, sha_of  # noqa: E402

SHA = sha_of("c")
RUN_NUMBER = 7
RUN_ID = 1007  # FakeGitHub.add_release's run for run number 7
TAG = f"il-build-{RUN_NUMBER}-{SHA[:7]}"
TAR = f"openpnp-il-{SHA[:7]}.tar.gz"
RUN_URL = f"https://github.com/{REPO}/actions/runs/{RUN_ID}"


def read(path):
    with open(path, "rb") as f:
        return f.read()


class Publish(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="il-publish-test-")
        self.github = FakeGitHub()
        self.cli = FakeGhCli(self.github)
        self.dir = self.package("attempt1")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def package(self, name, **attest):
        """What the job's Package step writes (make_release.py) for this run, its tarball then attested by this run
        (the job's attest step; attest: FakeGitHub.attest's overrides, e.g. attempt=2)."""
        src = os.path.join(self.tmp, name, "target")
        os.makedirs(os.path.join(src, "lib"))
        with open(os.path.join(src, "openpnp.jar"), "wb") as f:
            f.write(b"the jar of " + name.encode())
        with open(os.path.join(src, "lib", "dep.jar"), "wb") as f:
            f.write(b"a dependency")
        out = os.path.join(self.tmp, name, "rel")
        make_release.make(os.path.join(src, "openpnp.jar"), os.path.join(src, "lib"), TAG, SHA, BRANCH, RUN_ID,
                          RUN_NUMBER, RUN_URL, out)
        self.github.attest(read(os.path.join(out, TAR)), RUN_ID, SHA, **attest)
        return out

    def publish(self, runner=None, **kw):
        args = dict(dir=self.dir, tag=TAG, sha=SHA, repo=REPO, branch=BRANCH, run_id=str(RUN_ID), run_url=RUN_URL)
        args.update(kw)
        argv = [x for k, v in args.items() for x in ("--" + k.replace("_", "-"), v)]
        self.out = io.StringIO()
        with contextlib.redirect_stdout(self.out):
            return publish_release.main(argv, runner=runner or self.cli)

    def gh_calls(self, *prefix):
        return [c for c in self.cli.calls if c[1:1 + len(prefix)] == list(prefix)]

    def downloads(self):
        return [c[-1] for c in self.gh_calls("api") if "Accept: application/octet-stream" in c]

    def asset_name(self, api_path):
        rel = self.github.releases[0]
        return next(a["name"] for a in rel["assets"] if a["url"].endswith("/" + api_path))

    def assertRefused(self, rc, *said):
        self.assertEqual(rc, 1, self.out.getvalue())
        self.assertEqual(self.gh_calls("release", "create"), [], "it must not publish over a release at the tag")
        self.assertIn("::error::", self.out.getvalue())
        for s in said:
            self.assertIn(s, self.out.getvalue())

    # --- the first attempt: nothing at the tag yet ---------------------------------------------------------------
    def test_it_publishes_the_three_files_as_a_prerelease_at_the_commit(self):
        self.assertEqual(self.publish(), 0, self.out.getvalue())
        (create,) = self.gh_calls("release", "create")
        for flag in (["--repo", REPO], ["--target", SHA], ["--title", TAG]):
            self.assertIn(flag, [create[i:i + 2] for i in range(len(create))])
        self.assertIn("--prerelease", create)
        self.assertIn("--latest=false", create)  # the newest release stays the deployer's choice, not GitHub's
        (rel,) = self.github.releases
        self.assertEqual((rel["tag_name"], rel["target_commitish"], rel["author"]["login"], rel["prerelease"]),
                         (TAG, SHA, il_deploy.RELEASE_AUTHOR, True))
        self.assertIn(RUN_URL, rel["body"])
        for name in (TAR, TAR + ".sha256", "BUILD-INFO.json"):
            self.assertEqual(self.github.asset_bytes(TAG, name), read(os.path.join(self.dir, name)), name)

    def test_a_failed_create_fails_the_step(self):
        def runner(argv, **kw):
            if argv[1:3] == ["release", "create"]:
                return subprocess.CompletedProcess(argv, 1, "", "HTTP 502: Bad Gateway\n")
            return self.cli(argv, **kw)
        self.assertEqual(self.publish(runner=runner), 1)

    def test_a_failed_lookup_publishes_nothing(self):
        with mock.patch.object(self.github, "get_json", side_effect=il_deploy.GitHubError("Bad Gateway", 502)):
            self.assertRefused(self.publish(), "cannot look up")

    def test_a_tag_of_another_commit_is_refused_before_anything(self):
        self.assertEqual(self.publish(tag=f"il-build-{RUN_NUMBER}-{sha_of('d')[:7]}"), 2)
        self.assertEqual(self.cli.calls, [])

    # --- a re-run: the release is this run's own ------------------------------------------------------------------
    def test_a_rerun_passes_on_what_an_earlier_attempt_of_this_run_published(self):
        self.assertEqual(self.publish(), 0, self.out.getvalue())
        published = self.github.asset_bytes(TAG, TAR)
        self.dir = self.package("attempt2", attempt=2)  # the re-run packages and attests its own tarball again
        self.assertEqual(self.publish(), 0, self.out.getvalue())
        self.assertEqual(len(self.gh_calls("release", "create")), 1)
        self.assertEqual(self.cli.verified[-1][1], hashlib.sha256(published).hexdigest(),
                         "the published tarball itself is verified, not the re-run's")
        self.assertIn(f"runs/{RUN_ID}/attempts/1", self.out.getvalue())

    def test_the_policy_is_the_deployers(self):
        self.github.add_release(RUN_NUMBER, SHA, gate=None)
        self.assertEqual(self.publish(), 0, self.out.getvalue())
        (verify,) = self.gh_calls("attestation", "verify")
        tar = verify[3]
        self.assertEqual(verify[1:], il_deploy.GhAttestation.command(tar, REPO, BRANCH, SHA))

    # --- a release this run did not publish ------------------------------------------------------------------------
    def test_a_release_published_by_a_person_is_refused_unread(self):
        self.github.add_release(RUN_NUMBER, SHA, author="mallory", gate=None)  # CI-shaped, attested by this run
        self.assertRefused(self.publish(), "'mallory'")
        self.assertEqual(self.downloads(), [], "nothing of a release CI did not publish is downloaded")

    def test_a_bot_release_naming_this_run_whose_tarball_this_run_did_not_attest_is_refused(self):
        # a workflow on another branch or a PR run with contents: write publishes as github-actions[bot]
        self.github.add_release(RUN_NUMBER, SHA, jar={"not": "this run's build"}, attest=False, gate=None)
        self.assertRefused(self.publish(), "attest")
        self.assertTrue(self.gh_calls("attestation", "verify"))

    def test_a_tarball_attested_by_anything_but_this_runs_release_job_is_refused(self):
        for why, attest in (("a PR run", {"ref": "refs/pull/7/merge"}),
                            ("another workflow", {"workflow": ".github/workflows/build_and_deploy.yml"}),
                            ("a self-hosted runner", {"runner": "self-hosted"}),
                            ("another commit", {"sha": sha_of("d")}),
                            ("another run of ci.yml on the branch at this commit", {"run_id": 1999})):
            with self.subTest(why):
                self.github = FakeGitHub()
                self.cli = FakeGhCli(self.github)
                self.github.add_release(RUN_NUMBER, SHA, attest=attest, gate=None)
                self.assertRefused(self.publish(), "attest")

    def test_a_build_info_of_another_run_is_refused_before_the_tarball_is_downloaded(self):
        self.github.add_release(RUN_NUMBER, SHA, info_overrides={"run_id": 1999}, gate=None)
        self.assertRefused(self.publish(), "BUILD-INFO", "1999")
        self.assertEqual([self.asset_name(p) for p in self.downloads()], ["BUILD-INFO.json"])

    def test_a_release_at_another_commit_is_refused_unread(self):
        self.github.add_release(RUN_NUMBER, SHA, target=sha_of("d"), gate=None)
        self.assertRefused(self.publish(), sha_of("d"))
        self.assertEqual(self.downloads(), [])

    def test_a_release_missing_a_file_is_refused_unread(self):
        self.github.add_release(RUN_NUMBER, SHA, omit=(TAR + ".sha256",), gate=None)
        self.assertRefused(self.publish(), "assets")
        self.assertEqual(self.downloads(), [])

    def test_a_tarball_that_does_not_match_its_sha256_is_refused(self):
        self.github.add_release(RUN_NUMBER, SHA, corrupt_sha256=True, gate=None)
        self.assertRefused(self.publish(), "sha256")


if __name__ == "__main__":
    unittest.main()
