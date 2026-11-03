"""The contract between CI's release job and the deployer: what infra/ci/make_release.py writes, published as
`gh release create` publishes it, is what il_deploy stages, deploys and rolls back (here behind the fake OpenPnP;
e2e/test_deploy_openpnp.py does the same with the real build and lumen-sim)."""
import os
import sys
import unittest

# il_deploy is one level up: also when unittest imports this module by bare name (no -t)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from fakes import Lab  # noqa: E402


class CiReleaseContract(unittest.TestCase):
    def setUp(self):
        self.lab = Lab()

    def tearDown(self):
        self.lab.cleanup()

    def test_make_release_output_is_staged_and_deployed(self):
        lab = self.lab
        tag = lab.gh.add_release_dir(lab.ci_release(41, lab.b))
        self.assertEqual(tag, f"il-build-41-{lab.b[:7]}")
        self.assertEqual(lab.main("init", "--seed-from", lab.seed), 0, lab.last_out)
        self.assertEqual(lab.main("poll"), 0, lab.last_out + lab.last_err)
        d = os.path.join(lab.root, "releases", tag)
        for name in ("openpnp-il.jar", "lib/dep-1.0.jar", "VERSION.txt", "BUILD-INFO.json", "STAGED.json"):
            self.assertTrue(os.path.isfile(os.path.join(d, name)), name)
        self.assertEqual(lab.main("deploy"), 0, lab.last_out + lab.last_err)
        self.assertEqual(lab.link("current"), tag)
        self.assertIn(f"- **Deployed on the machine:** {tag} ({lab.b[:7]}, il/integration, CI "
                      f"https://github.com/theinstancelabs/openpnp/actions/runs/1041)", lab.state_text())

    def test_one_jar_published_twice_under_one_sha7(self):
        """The e2e test publishes its one jar twice: two commits whose shas share the jar's sha7 (server.version ends
        with it), so the production version check runs unchanged."""
        lab = self.lab
        sha7 = lab.c[:7]
        first = lab.gh.commit("first", lab.c, sha=sha7 + "0" * 33)
        second = lab.gh.commit("second", first, sha=sha7 + "1" * 33)
        self.assertEqual(lab.gh.compare(second, first), "behind")
        lab.gh.head = second
        self.assertEqual(lab.main("init", "--seed-from", lab.seed), 0, lab.last_out)
        a = lab.gh.add_release_dir(lab.ci_release(41, first))
        self.assertEqual(lab.main("run"), 0, lab.last_out + lab.last_err)
        b = lab.gh.add_release_dir(lab.ci_release(42, second))
        self.assertEqual(lab.main("run"), 0, lab.last_out + lab.last_err)
        self.assertEqual((lab.link("current"), lab.link("previous")), (b, a))
        self.assertEqual(lab.main("rollback"), 0, lab.last_out + lab.last_err)
        self.assertEqual(lab.link("current"), a)
        self.assertEqual([(r["action"], r["rc"]) for r in lab.journal() if r["event"] == "result"],
                         [("deploy", 0), ("deploy", 0), ("rollback", 0)])


if __name__ == "__main__":
    unittest.main()
