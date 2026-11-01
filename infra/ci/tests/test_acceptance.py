"""Tests for infra/ci/acceptance.py: the CI wrapper around src/test/sim/il_sim_acceptance.py.

Run from the repo root: python3 -m unittest discover -s infra/ci/tests -t infra/ci
"""
import io
import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import acceptance  # noqa: E402


def write(d, obj):
    p = os.path.join(d, "results.json")
    with open(p, "w") as f:
        json.dump(obj, f)
    return p


GOOD = {"started": "x", "finished": "y", "live_config_untouched": True,
        "load": {"pass": True}, "board": {"pass": True}, "register": {"pass": True}, "import": {"pass": True},
        "tips": {"pass": True}, "stop": [{"what": "hold", "how": "web", "pass": True},
                                         {"what": "run", "how": "swing", "pass": True}],
        "padcal": {"main": {"pass": True}, "nano": {"pass": True}, "controls": {"pass": True}, "pass": True}}


class CheckTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()

    def check(self, obj, expect):
        p = write(self.d, obj) if obj is not None else os.path.join(self.d, "missing.json")
        return acceptance.check(p, expect)

    def test_all_scenarios_pass(self):
        for e in ("load", "board", "register", "import", "tips", "stop", "padcal", "padcal:main", "padcal:nano,controls"):
            ok, lines = self.check(GOOD, e)
            self.assertTrue(ok, (e, lines))

    def test_missing_file_fails(self):
        ok, lines = self.check(None, "load")
        self.assertFalse(ok)
        self.assertIn("no results", " ".join(lines))

    def test_unfinished_run_fails(self):
        r = dict(GOOD)
        del r["finished"]
        self.assertFalse(self.check(r, "load")[0])

    def test_live_config_touched_fails(self):
        self.assertFalse(self.check(dict(GOOD, live_config_untouched=False), "load")[0])
        r = dict(GOOD)
        del r["live_config_untouched"]
        self.assertFalse(self.check(r, "load")[0])

    def test_failed_scenario_fails(self):
        self.assertFalse(self.check(dict(GOOD, board={"pass": False}), "board")[0])
        self.assertFalse(self.check(dict(GOOD, tips={"pass": False, "error": "boom"}), "tips")[0])

    def test_scenario_missing_fails(self):
        r = dict(GOOD)
        del r["register"]
        self.assertFalse(self.check(r, "register")[0])

    def test_pass_must_be_true_not_truthy(self):
        self.assertFalse(self.check(dict(GOOD, load={"pass": "yes"}), "load")[0])
        self.assertFalse(self.check(dict(GOOD, load={}), "load")[0])

    def test_stop_needs_every_row(self):
        bad = dict(GOOD, stop=[{"what": "hold", "how": "web", "pass": True},
                               {"what": "run", "how": "web", "pass": False, "error": "x"}])
        ok, lines = self.check(bad, "stop")
        self.assertFalse(ok)
        self.assertIn("run/web", " ".join(lines))
        self.assertFalse(self.check(dict(GOOD, stop=[]), "stop")[0])

    def test_padcal_subset_uses_part_pass(self):
        # il_sim_acceptance sets padcal.pass false whenever --padcal-parts is a subset; the parts carry the verdict
        sub = dict(GOOD, padcal={"controls": {"pass": True}, "pass": False})
        self.assertTrue(self.check(sub, "padcal:controls")[0])
        self.assertFalse(self.check(sub, "padcal")[0])
        self.assertFalse(self.check(sub, "padcal:main")[0])
        sub2 = dict(GOOD, padcal={"main": {"pass": True}, "nano": {"pass": False}, "pass": False})
        self.assertFalse(self.check(sub2, "padcal:main,nano")[0])

    def test_unknown_scenario_is_an_error(self):
        with self.assertRaises(ValueError):
            acceptance.parse_item("bogus")
        with self.assertRaises(ValueError):
            acceptance.parse_item("padcal:sideways")


class PlanTest(unittest.TestCase):
    def test_items_to_commands(self):
        cmds = acceptance.plan("tips board padcal:main,nano", out="/o", jar="/j.jar")
        self.assertEqual([c["item"] for c in cmds], ["tips", "board", "padcal:main,nano"])
        self.assertEqual(cmds[0]["args"], ["tips", "--jar", "/j.jar", "--out", "/o/tips", "--prefix", "ci-tips-"])
        self.assertEqual(cmds[2]["args"], ["padcal", "--padcal-parts", "main,nano", "--jar", "/j.jar",
                                           "--out", "/o/padcal-main-nano", "--prefix", "ci-padcal-main-nano-"])
        self.assertEqual(cmds[2]["results"], "/o/padcal-main-nano/results.json")

    def test_empty_or_duplicate_shard_refused(self):
        with self.assertRaises(ValueError):
            acceptance.plan("", out="/o", jar="/j")
        with self.assertRaises(ValueError):
            acceptance.plan("tips tips", out="/o", jar="/j")


class RunTest(unittest.TestCase):
    def test_runs_every_item_even_after_a_failure_and_fails_overall(self):
        d = tempfile.mkdtemp()
        ran = []

        def runner(args, log_path):
            ran.append(args[0])
            out = args[args.index("--out") + 1]
            os.makedirs(out, exist_ok=True)
            r = dict(GOOD)
            if args[0] == "board":
                r["board"] = {"pass": False}
            write(out, r)
            return 0

        buf = io.StringIO()
        ok = acceptance.run("board tips", out=d, jar="/j.jar", runner=runner, stream=buf)
        self.assertFalse(ok)
        self.assertEqual(ran, ["board", "tips"])
        self.assertIn("FAIL board", buf.getvalue())
        self.assertIn("PASS tips", buf.getvalue())

    def test_crash_without_results_fails(self):
        d = tempfile.mkdtemp()
        ok = acceptance.run("load", out=d, jar="/j.jar", runner=lambda a, l: 1, stream=io.StringIO())
        self.assertFalse(ok)

    def test_nonzero_exit_fails_even_with_passing_results(self):
        d = tempfile.mkdtemp()

        def runner(args, log_path):
            out = args[args.index("--out") + 1]
            os.makedirs(out, exist_ok=True)
            write(out, GOOD)
            return 2

        self.assertFalse(acceptance.run("load", out=d, jar="/j.jar", runner=runner, stream=io.StringIO()))

    def test_all_pass(self):
        d = tempfile.mkdtemp()

        def runner(args, log_path):
            out = args[args.index("--out") + 1]
            os.makedirs(out, exist_ok=True)
            write(out, GOOD)
            return 0

        self.assertTrue(acceptance.run("load stop import", out=d, jar="/j.jar", runner=runner, stream=io.StringIO()))


if __name__ == "__main__":
    unittest.main()
