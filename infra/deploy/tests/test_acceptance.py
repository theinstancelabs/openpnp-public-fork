"""INF-02 CI acceptance: "a deploy dry run to a test directory, and a rollback to the previous version".

Two green il/integration builds come from a (fake) GitHub; the timer's `run` stages and deploys the first, then
the second over a running OpenPnP (fake, started by the subprocess service manager); `rollback` goes back one
step with the pre-deploy state snapshot. Checked: links, state, STATE.md lines, the Holder, the journal, and that
OpenPnP was stopped through /api/stop (never SIGTERM with the machine enabled, never a STOP during a home).
"""
import json
import os
import sys
import unittest

# il_deploy is one level up: also when unittest imports this module by bare name (no -t)
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from fakes import Lab  # noqa: E402


class Inf02Acceptance(unittest.TestCase):
    def setUp(self):
        self.lab = Lab()

    def tearDown(self):
        self.lab.cleanup()

    def test_deploy_dry_run_and_rollback(self):
        lab = self.lab
        self.assertEqual(lab.main("init", "--seed-from", lab.seed), 0, lab.last_out)

        a = lab.gh.add_release(41, lab.b, jar={"sha": lab.b})
        self.assertEqual(lab.main("run"), 0, lab.last_out + lab.last_err)
        self.assertEqual(lab.link("current"), a)
        lab.start_service()

        b = lab.gh.add_release(42, lab.c, jar={"sha": lab.c, "schema": 4})
        self.assertEqual(lab.main("run"), 0, lab.last_out + lab.last_err)
        self.assertEqual((lab.link("current"), lab.link("previous")), (b, a))
        self.assertEqual(lab.schema(), 4)
        self.assertTrue(lab.service().is_active())
        self.assertIn(f"{b} ({lab.c[:7]}", lab.state_text())

        self.assertEqual(lab.main("rollback"), 0, lab.last_out + lab.last_err)
        self.assertEqual(lab.link("current"), a)
        self.assertEqual(lab.schema(), 3, "the pre-deploy snapshot comes back with the binary")
        self.assertTrue(lab.service().is_active(), "it ran before the rollback")
        self.assertIn(f"- **Deployed on the machine:** {a} ({lab.b[:7]}", lab.state_text())
        self.assertIn(f"rolled back from {b}", lab.state_text())
        h = lab.holder()
        self.assertTrue(h.idle, h.value)

        results = [r for r in lab.journal() if r["event"] == "result"]
        self.assertEqual([(r["action"], r["rc"]) for r in results], [("deploy", 0), ("deploy", 0), ("rollback", 0)])
        reqs = lab.requests()
        self.assertEqual([r["violation"] for r in reqs if "violation" in r], [])
        self.assertTrue(any(r.get("path") == "/api/machine/home" for r in reqs))
        with open(os.path.join(lab.root, "deployed.json")) as f:
            self.assertEqual(json.load(f)["current"]["tag"], a)


if __name__ == "__main__":
    unittest.main()
