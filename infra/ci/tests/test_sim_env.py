"""Tests for infra/ci/sim_env.py: the SIM gates' input files from infra/fixtures in one directory, and their env.

Run from the repo root: python3 -m unittest discover -s infra/ci/tests -t infra/ci
"""
import filecmp
import os
import re
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import sim_env  # noqa: E402

REPO = sim_env.REPO
SCRIPT = os.path.join(REPO, "infra", "ci", "sim_env.py")
FIXTURES = os.path.join(REPO, "infra", "fixtures")
NAMES = {"OPENPNP_CONFIG", "IL_LIVE_CONFIG", "IL_TEST_BOARD", "IL_PATCHED_MACHINE_XML", "IL_DISPENSE_CONFIG",
         "IL_RUNS_DIR", "LUMEN_SIM_E2E_CONFIG", "LUMEN_SIM_E2E_DISPENSE_CONFIG"}
# Set by the sim-env action's uinav step, not by sim_env.py.
UINAV_NAMES = {"UINAV", "UINAV_DIR"}


def files_under(d):
    return sorted(os.path.relpath(os.path.join(r, f), d) for r, _, fs in os.walk(d) for f in fs)


def run(args, **kw):
    return subprocess.run([sys.executable, "-I", SCRIPT] + args, capture_output=True, text=True, timeout=120, **kw)


class PrepareTest(unittest.TestCase):
    def setUp(self):
        self.lab = os.path.join(tempfile.mkdtemp(), "lab")
        self.env = sim_env.prepare(self.lab)

    def test_env_names(self):
        self.assertEqual(set(self.env), NAMES)

    def test_every_value_is_an_absolute_path_inside_the_directory_that_exists(self):
        for k, v in self.env.items():
            self.assertTrue(os.path.isabs(v), k)
            self.assertTrue(v.startswith(self.lab + os.sep), (k, v))
            self.assertTrue(os.path.exists(v), (k, v))

    def test_live_config_is_the_fixture_with_a_boards_xml_naming_the_test_board(self):
        cfg = self.env["IL_LIVE_CONFIG"]
        self.assertEqual(self.env["OPENPNP_CONFIG"], cfg)
        self.assertEqual(self.env["LUMEN_SIM_E2E_CONFIG"], cfg)
        src = os.path.join(FIXTURES, "lab-config")
        self.assertEqual(files_under(cfg), sorted(files_under(src) + ["boards.xml"]))
        for f in files_under(src):
            self.assertTrue(filecmp.cmp(os.path.join(src, f), os.path.join(cfg, f), shallow=False), f)
        # il_sim_acceptance stats these four in the live config before and after (live_config_untouched)
        for f in ("machine.xml", "packages.xml", "parts.xml", "boards.xml"):
            self.assertTrue(os.path.isfile(os.path.join(cfg, f)), f)
        self.assertTrue(os.path.isdir(os.path.join(cfg, "scripts")))  # the stage copies il_n2_reference.js there
        with open(os.path.join(cfg, "boards.xml")) as f:
            self.assertEqual(f.read(), "<openpnp-boards>\n   <board>%s</board>\n</openpnp-boards>\n"
                             % self.env["IL_TEST_BOARD"])

    def test_test_board_is_the_repo_copy(self):
        self.assertTrue(filecmp.cmp(self.env["IL_TEST_BOARD"],
                                    os.path.join(REPO, "src", "test", "resources", "kicad", "ftp.board.xml"),
                                    shallow=False))
        self.assertEqual(os.path.basename(self.env["IL_TEST_BOARD"]), "ftp.board.xml")

    def test_patched_machine_xml_is_make_machine_patch_of_the_fixture(self):
        ref = os.path.join(tempfile.mkdtemp(), "ref.xml")
        subprocess.check_call([sys.executable, "-I", os.path.join(REPO, "tools", "instancelabs", "make_machine_patch.py"),
                               "--in", os.path.join(FIXTURES, "lab-config", "machine.xml"), "--out", ref],
                              stdout=subprocess.DEVNULL)
        self.assertTrue(filecmp.cmp(ref, self.env["IL_PATCHED_MACHINE_XML"], shallow=False))

    def test_dispense_config_and_runs_are_the_fixtures(self):
        self.assertEqual(self.env["LUMEN_SIM_E2E_DISPENSE_CONFIG"], self.env["IL_DISPENSE_CONFIG"])
        self.assertTrue(filecmp.cmp(os.path.join(FIXTURES, "lab-instancelabs", "config", "dispense-config.json"),
                                    self.env["IL_DISPENSE_CONFIG"], shallow=False))
        runs = os.path.join(FIXTURES, "lab-instancelabs", "runs")
        self.assertEqual(files_under(self.env["IL_RUNS_DIR"]), files_under(runs))
        for f in ("purge_spots.json", "p18_board1.json", "p18_board1/timing.json", "p16_board1/dots.csv"):
            self.assertTrue(os.path.isfile(os.path.join(self.env["IL_RUNS_DIR"], f)), f)

    def test_refuses_a_directory_that_is_not_empty(self):
        with self.assertRaises(sim_env.Refused):
            sim_env.prepare(self.lab)

    def test_takes_an_empty_existing_directory(self):
        d = tempfile.mkdtemp()
        self.assertEqual(set(sim_env.prepare(d)), NAMES)


class CommandLineTest(unittest.TestCase):
    def test_github_env_lines_only_on_stdout_and_nothing_written_outside_the_directory(self):
        home, cwd = tempfile.mkdtemp(), tempfile.mkdtemp()
        lab = os.path.join(tempfile.mkdtemp(), "lab")
        env = dict(os.environ, HOME=home)
        r = run([lab], env=env, cwd=cwd)
        self.assertEqual(r.returncode, 0, r.stderr)
        lines = r.stdout.splitlines()
        self.assertEqual(len(lines), len(NAMES), r.stdout)
        got = {}
        for line in lines:
            self.assertRegex(line, r"^[A-Z][A-Z0-9_]*=/\S")
            k, v = line.split("=", 1)
            got[k] = v
        self.assertEqual(set(got), NAMES)
        self.assertEqual(got["IL_TEST_BOARD"], os.path.join(lab, "ftp.board.xml"))
        self.assertEqual(os.listdir(home), [])
        self.assertEqual(os.listdir(cwd), [])

    def test_export_lines_are_shell_safe(self):
        lab = os.path.join(tempfile.mkdtemp(), "a lab's dir")
        r = run([lab, "--export"])
        self.assertEqual(r.returncode, 0, r.stderr)
        out = subprocess.run(["bash", "-c", 'eval "$1"; printf "%s" "$IL_TEST_BOARD"', "-", r.stdout],
                             capture_output=True, text=True, timeout=30)
        self.assertEqual(out.stdout, os.path.join(lab, "ftp.board.xml"))

    def test_refusal_exits_2_and_changes_nothing(self):
        d = tempfile.mkdtemp()
        with open(os.path.join(d, "keep"), "w") as f:
            f.write("x")
        r = run([d])
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout, "")
        self.assertIn("not empty", r.stderr)
        self.assertEqual(os.listdir(d), ["keep"])

    def test_a_newline_in_the_path_is_refused(self):
        # GITHUB_ENV's NAME=value format ends a value at the newline
        d = os.path.join(tempfile.mkdtemp(), "a\nb")
        r = run([d])
        self.assertEqual(r.returncode, 2)
        self.assertEqual(r.stdout, "")
        self.assertFalse(os.path.exists(d))


class GatesReadTheseTest(unittest.TestCase):
    """Every env override the gates read in place of a lab computer path is one sim_env.py sets (or uinav's)."""

    SOURCES = ["src/test/sim/il_sim_acceptance.py", "src/test/sim/il_ui_session.py", "src/test/sim/il_sim_scene.py",
               "src/test/web/run-webui-sim-test.sh", "src/test/web/run-boardview-test.sh",
               "src/test/java/org/openpnp/instancelabs/dispense/LiveBoardPlanTest.java",
               "infra/lumen-sim/e2e/test_openpnp_pty.py"]
    HOST = r"(?:/home/lumen|~/|\$HOME|\bHOME\b|\bhome\b)"
    PATTERNS = [
        # Python: os.environ.get("X") or <host path>, os.environ.get("X", "<host path>")
        re.compile(r"os\.environ\.get\(\"([A-Z][A-Z0-9_]*)\"\)?\s*(?:or|,)[^\n]*" + HOST),
        # shell: ${X:-<host path>}
        re.compile(r"\$\{([A-Z][A-Z0-9_]*):-" + HOST),
        # LiveBoardPlanTest: pick("X", <the lab computer's file>, <the repo's copy>)
        re.compile(r"pick\(\"([A-Z][A-Z0-9_]*)\",\s*new File\((?:" + HOST + r"|lab\b)"),
    ]

    def overrides(self):
        found = {}
        for rel in self.SOURCES:
            with open(os.path.join(REPO, rel)) as f:
                text = f.read()
            for p in self.PATTERNS:
                for name in p.findall(text):
                    found.setdefault(name, set()).add(rel)
        return found

    def test_scan_finds_the_known_overrides(self):
        # the scan itself works: it finds every name sim_env.py sets (and uinav's), each where it is read
        found = self.overrides()
        self.assertLessEqual(NAMES | UINAV_NAMES, set(found))
        self.assertEqual(found["IL_TEST_BOARD"], {"src/test/sim/il_sim_acceptance.py",
                                                  "src/test/web/run-webui-sim-test.sh",
                                                  "src/test/java/org/openpnp/instancelabs/dispense/LiveBoardPlanTest.java"})
        self.assertEqual(found["OPENPNP_CONFIG"], {"src/test/web/run-webui-sim-test.sh"})
        self.assertEqual(found["LUMEN_SIM_E2E_CONFIG"], {"infra/lumen-sim/e2e/test_openpnp_pty.py"})

    def test_every_override_is_set(self):
        lab = os.path.join(tempfile.mkdtemp(), "lab")
        env = sim_env.prepare(lab)
        missing = {k: sorted(v) for k, v in self.overrides().items() if k not in env and k not in UINAV_NAMES}
        self.assertEqual(missing, {})


class FixtureTest(unittest.TestCase):
    def test_fixture_has_no_legacy_automation_scripts(self):
        names = [f for _, _, fs in os.walk(os.path.join(FIXTURES, "lab-config")) for f in fs]
        self.assertNotIn("Automation_Reviewed_Command.js", names)
        self.assertNotIn("Paste_Experiment_Console.js", names)


if __name__ == "__main__":
    unittest.main()
