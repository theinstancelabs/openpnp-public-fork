"""Tests for infra/ci/sim_env.py: the SIM gates' input files from infra/fixtures in one directory, and their env.

Run from the repo root: python3 -m unittest discover -s infra/ci/tests -t infra/ci
"""
import filecmp
import glob
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


# The gates' sources: what the SIM jobs run, and the Java tests that read env (maven runs them all).
SOURCES = ["src/test/sim/*.py", "src/test/web/*.sh", "src/test/web/*.cjs", "src/test/web/*.py", "infra/*/e2e/*.py",
           "src/test/java/**/*.java"]
NAME = r"([A-Z][A-Z0-9_]*)"
Q = r"[\"']"
# An env read; the name is the one group of the alternative that matched.
READ = re.compile("|".join([
    r"\bos\.environ\.get\(\s*" + Q + NAME + Q,  # Python
    r"\bos\.getenv\(\s*" + Q + NAME + Q,
    r"\bos\.environ\[\s*" + Q + NAME + Q + r"\s*\]",
    r"\$\{" + NAME + r":?-",  # shell: ${X:-default}, ${X-default}
    r"\bSystem\.getenv\(\s*\"" + NAME + "\"",  # Java
    r"\bgetOrDefault\(\s*\"" + NAME + "\"",  # System.getenv().getOrDefault("X", default)
    r"\bpick\(\s*\"" + NAME + "\"",  # LiveBoardPlanTest: pick("X", the lab computer's file, the repo's copy)
    r"\bprocess\.env\." + NAME + r"\b",  # JavaScript
    r"\bprocess\.env\[\s*" + Q + NAME + Q + r"\s*\]",
]))
# A lab computer path, spelt out: /home/..., ~/..., "~", expanduser(, Path.home(, user.home, $HOME, homedir(, "HOME"
HOST = (r"/home/|~/|" + Q + "~" + Q + r"|\bexpanduser\(|\bPath\.home\(|\buser\.home\b|\$\{?HOME\b|\bhomedir\(|"
        + Q + "HOME" + Q)
# NAME = ... at the start of a line (Python, shell, Java and JavaScript declarations, attributes)
BIND = re.compile(r"^[ \t]*(?:[\w<>\[\],.]+[ \t]+)*?(?:\w+\.)*([A-Za-z_]\w*)[ \t]*=(?!=)", re.M)
# a line that ends before its statement does, or one that goes on with the line before's
TRAILING = re.compile(r"(?:\\|\|\||&&|[?+])[ \t]*$")
CONTINUES = re.compile(r"[ \t]*(?:\?|:(?!:)|\|\||&&|\+|\.|or\b|and\b)")
# docstrings and comment lines: their shell examples (UINAV_DIR=/home/... python3 ...) bind nothing
COMMENTS = {".py": re.compile(r'"""[\s\S]*?"""|' + r"'''[\s\S]*?'''|^[ \t]*#[^\n]*", re.M),
            ".sh": re.compile(r"^[ \t]*#[^\n]*", re.M),
            ".java": re.compile(r"/\*[\s\S]*?\*/|^[ \t]*//[^\n]*", re.M)}
COMMENTS[".cjs"] = COMMENTS[".java"]


def gate_sources():
    """{repo-relative path: text} of the gates' sources (Java: only the files that read env)."""
    out = {}
    for pattern in SOURCES:
        for path in sorted(glob.glob(os.path.join(REPO, pattern), recursive=True)):
            with open(path, encoding="utf-8", errors="replace") as f:
                text = f.read()
            if not path.endswith(".java") or "getenv" in text:
                out[os.path.relpath(path, REPO)] = text
    return out


def statement(text, start, end):
    """text[start:] to the end of text[end]'s line, and on (6 lines at most) while a bracket opened in it is open,
    the line ends in a backslash or an operator, or the next line starts with an operator."""
    stop = text.find("\n", end)
    for _ in range(6):
        if stop < 0:
            break
        w = text[start:stop]
        if (sum(map(w.count, "([{")) <= sum(map(w.count, ")]}")) and not TRAILING.search(w)
                and not CONTINUES.match(text, stop + 1)):
            return w
        stop = text.find("\n", stop + 1)
    return text[start:] if stop < 0 else text[start:stop]


def host(names=(), consts=()):
    """HOST, or one of names (a word, not a quoted env name), or .CONST (acc.HOME: another file's constant)."""
    alts = [HOST]
    if names:
        alts.append(r"(?<![\w\"'])(?:%s)\b(?![\"'])" % "|".join(map(re.escape, sorted(names))))
    if consts:
        alts.append(r"\.(?:%s)\b" % "|".join(map(re.escape, sorted(consts))))
    return re.compile("|".join(alts))


def scan(sources):
    """{env name: {path}}: the env vars sources ({path: text}) read with a lab computer path as the default.

    A default counts when the read's statement, from the read on, has a lab computer path spelt out (HOST), or a
    name its file binds to one (NAME = <one spelt out, no env read>, or NAME = <built from such a name>: two
    levels), or such an upper-case name of another file as an attribute (acc.HOME). Not seen: a default built at a
    third level or in a function, a read through a variable (os.environ.get(name)), a default before the read in its
    statement (A if not os.getenv("X") else B), a file outside SOURCES."""
    texts = {}
    for rel, text in sources.items():
        strip = COMMENTS.get(os.path.splitext(rel)[1])
        texts[rel] = strip.sub("", text) if strip else text
    bound = {rel: set() for rel in texts}
    for _ in range(2):
        consts = {n for b in bound.values() for n in b if n.isupper()}
        marks = {rel: host(bound[rel], consts) for rel in texts}
        for rel, text in texts.items():
            for m in BIND.finditer(text):
                rhs = statement(text, m.end(), m.end())
                if marks[rel].search(rhs) and not READ.search(rhs):
                    bound[rel].add(m.group(1))
    consts = {n for b in bound.values() for n in b if n.isupper()}
    found = {}
    for rel, text in texts.items():
        mark = host(bound[rel], consts)
        for m in READ.finditer(text):
            name = next(g for g in m.groups() if g)
            if name != "HOME" and mark.search(statement(text, m.start(), m.end())):
                found.setdefault(name, set()).add(rel)
    return found


class GatesReadTheseTest(unittest.TestCase):
    """Every env override the gates read in place of a lab computer path is one sim_env.py sets (or uinav's)."""

    def test_scan_finds_the_known_overrides(self):
        # the scan itself works: it finds every name sim_env.py sets (and uinav's), each where it is read
        found = scan(gate_sources())
        self.assertLessEqual(NAMES | UINAV_NAMES, set(found))
        # at least these readers (a new reader of a name sim_env.py sets is fine, so not an exact match)
        self.assertLessEqual({"src/test/sim/il_sim_acceptance.py", "src/test/web/run-webui-sim-test.sh",
                              "src/test/java/org/openpnp/instancelabs/dispense/LiveBoardPlanTest.java"},
                             found["IL_TEST_BOARD"])
        self.assertLessEqual({"src/test/sim/il_sim_acceptance.py",
                              "src/test/java/org/openpnp/instancelabs/dispense/LiveBoardPlanTest.java"},
                             found["IL_LIVE_CONFIG"])
        self.assertLessEqual({"src/test/web/run-webui-sim-test.sh"}, found["OPENPNP_CONFIG"])
        self.assertLessEqual({"infra/lumen-sim/e2e/test_openpnp_pty.py"}, found["LUMEN_SIM_E2E_CONFIG"])

    def test_every_override_is_set(self):
        lab = os.path.join(tempfile.mkdtemp(), "lab")
        env = sim_env.prepare(lab)
        missing = {k: sorted(v) for k, v in scan(gate_sources()).items() if k not in env and k not in UINAV_NAMES}
        self.assertEqual(missing, {})


class ScanTest(unittest.TestCase):
    """The scan's patterns: the spellings of an env override with a lab computer path as its default."""

    def names(self, *texts):
        return set(scan({"f%d" % i: t for i, t in enumerate(texts)}))

    def assertFound(self, *texts):
        self.assertEqual(self.names(*texts), {"IL_JOBS_DIR"}, texts)

    def test_python(self):
        self.assertFound('JOBS = os.environ.get("IL_JOBS_DIR") or "/home/lumen/instancelabs/jobs"')
        self.assertFound('JOBS = os.environ.get("IL_JOBS_DIR", "/home/lumen/instancelabs/jobs")')
        self.assertFound('JOBS = os.getenv("IL_JOBS_DIR", "/home/lumen/instancelabs/jobs")')
        self.assertFound('JOBS = os.getenv("IL_JOBS_DIR") or "/home/lumen/instancelabs/jobs"')
        self.assertFound("JOBS = os.environ.get('IL_JOBS_DIR') or '/home/lumen/instancelabs/jobs'")
        self.assertFound('JOBS = os.environ.get("IL_JOBS_DIR") or os.path.join(os.path.expanduser("~"), "jobs")')
        self.assertFound('JOBS = os.path.expanduser(os.environ.get("IL_JOBS_DIR", "~/jobs"))')
        self.assertFound('JOBS = pathlib.Path(os.environ.get("IL_JOBS_DIR") or pathlib.Path.home() / "jobs")')
        self.assertFound('JOBS = os.environ.get("IL_JOBS_DIR") or os.path.join(os.environ["HOME"], "jobs")')
        self.assertFound('JOBS = os.environ["IL_JOBS_DIR"] if "IL_JOBS_DIR" in os.environ else "/home/lumen/jobs"')
        self.assertFound('JOBS = os.environ.get(\n    "IL_JOBS_DIR",\n    "/home/lumen/instancelabs/jobs")')

    def test_shell(self):
        self.assertFound('JOBS="${IL_JOBS_DIR:-$HOME/jobs}"')
        self.assertFound('JOBS="${IL_JOBS_DIR:-${HOME}/jobs}"')
        self.assertFound('JOBS="${IL_JOBS_DIR-~/jobs}"')
        self.assertFound('JOBS="${IL_JOBS_DIR:-/home/lumen/instancelabs/jobs}"')

    def test_java(self):
        self.assertFound('File jobs = pick("IL_JOBS_DIR", new File(System.getProperty("user.home"), "jobs"), "x");')
        self.assertFound('String jobs = System.getenv().getOrDefault("IL_JOBS_DIR", System.getProperty("user.home"));')
        self.assertFound('File jobs = System.getenv("IL_JOBS_DIR") != null ? new File(System.getenv("IL_JOBS_DIR"))\n'
                         '        : new File(System.getProperty("user.home"), "jobs");')

    def test_javascript(self):
        self.assertFound("const JOBS = process.env.IL_JOBS_DIR || path.join(os.homedir(), 'jobs');")
        self.assertFound("const JOBS = process.env['IL_JOBS_DIR'] || '/home/lumen/instancelabs/jobs';")

    def test_through_a_name_bound_to_a_lab_computer_path(self):
        # LiveBoardPlanTest: home = user.home, lab = home/.openpnp2, pick("IL_LIVE_CONFIG", new File(lab, ...
        self.assertFound('File home = new File(System.getProperty("user.home"));\n'
                         'File lab = new File(home, ".openpnp2");\n'
                         'File live = pick("IL_JOBS_DIR", new File(lab, "packages.xml").exists()\n'
                         '        ? lab : null, "infra/fixtures/lab-config");')
        # il_ui_session.py's acc.HOME is il_sim_acceptance.py's HOME
        self.assertFound('HOME = os.path.expanduser("~")',
                         'BOARD = os.environ.get("IL_JOBS_DIR") or os.path.join(acc.HOME, "Desktop", "x.xml")')

    def test_defaults_that_are_not_lab_computer_paths(self):
        self.assertEqual(self.names(
            'OUT = os.environ.get("OUT_DIR") or "/tmp/il-out"',
            'JAVA = os.environ.get("IL_DEPLOY_E2E_JAVA") or shutil.which("java") or ""',
            'JAR="${OPENPNP_JAR:-$REPO/target/openpnp.jar}"',
            '[ -n "${IL_API_PORT:-}" ] && PORT_ARGS=(--api-port "$IL_API_PORT")',
            "const OUT = process.env.OUT_DIR || '/tmp/il-out';",
            'r = client.post("/api/machine/home", HOME_BODY, timeout=os.environ.get("IL_TIMEOUT"))',
            'HOME = {"wait": True}\nX = os.environ.get("IL_HOMING") or HOME'), set())
        # HOME is a lab computer path in one file, a request body in the other
        self.assertEqual(self.names('HOME = os.path.expanduser("~")',
                                    'HOME = {"wait": True}\nX = os.environ.get("IL_HOMING") or HOME'), set())

    def test_a_name_bound_to_an_override_is_not_a_lab_computer_path(self):
        # RUNS is IL_RUNS_DIR's value when that is set, as in CI; IL_X's default under it needs no override of its own
        self.assertEqual(self.names('RUNS = os.environ.get("IL_RUNS_DIR") or "/home/lumen/instancelabs/runs"\n'
                                    'X = os.environ.get("IL_X") or os.path.join(RUNS, "x")'), {"IL_RUNS_DIR"})

    def test_docstrings_and_comments_are_skipped(self):
        # test_openpnp_pty.py's docstring has a shell example, UINAV_DIR=/home/lumen/... at the start of a line
        self.assertEqual(set(scan({
            "a.py": '"""Run:\n  LAB=/home/lumen/x python3 a.py\n"""\nL = os.environ.get("IL_LAB") or LAB\n'
                    '# was: os.environ.get("IL_OLD") or "/home/lumen/old"',
            "b.cjs": "/*\n  OUT=/home/lumen/out node b.cjs\n */\nconst S = process.env.SHOTS_DIR || OUT;\n"
                     "// was: process.env.IL_OLD || '/home/lumen/old'",
            "c.sh": '# was: ${IL_OLD:-$HOME/old}\nX="${IL_X:-/tmp/x}"'})), set())

    def test_home_itself_is_not_an_override(self):
        self.assertEqual(self.names('H = os.environ.get("HOME") or os.path.expanduser("~")'), set())


class ReadmeTest(unittest.TestCase):
    """infra/README.md's command that points a gate at the fixtures on any computer (sim_env.py --export)."""

    def command(self):
        with open(os.path.join(REPO, "infra", "README.md")) as f:
            cmds = re.findall(r"`([^`\n]*sim_env\.py[^`\n]*--export[^`\n]*)`", f.read())
        self.assertEqual(len(cmds), 1, cmds)
        return cmds[0]

    def bash(self, script, tmp, path=None):
        env = {k: v for k, v in os.environ.items() if k not in NAMES}
        env.update(TMPDIR=tmp, HOME=tempfile.mkdtemp())
        if path:
            env["PATH"] = path + os.pathsep + env["PATH"]
        return subprocess.run(["bash", "-c", script], cwd=REPO, env=env, capture_output=True, text=True, timeout=120)

    def test_a_fresh_directory_each_run(self):
        # a fixed path (/tmp/lab) is refused once anyone has made it: a second run, another agent on the computer
        cmd, tmp = self.command(), tempfile.mkdtemp()
        self.assertIn('"$(mktemp -d)/', cmd)  # before running it: no fixed path gets made by this test
        configs = []
        for _ in range(2):
            r = self.bash(cmd + ' && printf "%s" "$IL_LIVE_CONFIG"', tmp)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertTrue(r.stdout.startswith(tmp + os.sep) and os.path.isdir(r.stdout), r.stdout)
            configs.append(r.stdout)
        self.assertNotEqual(configs[0], configs[1])

    def test_a_refusal_is_not_swallowed(self):
        # eval of an empty $(...) succeeds: the gate would then read ~/.openpnp2 and the lab's files, unwarned
        stub = tempfile.mkdtemp()
        with open(os.path.join(stub, "python3"), "w") as f:
            f.write("#!/bin/sh\necho 'refused: not empty, not overwriting: x' >&2\nexit 2\n")
        os.chmod(os.path.join(stub, "python3"), 0o755)
        r = self.bash(self.command() + '\nprintf "%s|%s" "$?" "${IL_LIVE_CONFIG:-}"', tempfile.mkdtemp(), path=stub)
        self.assertEqual(r.stdout, "2|", r.stderr)


class FixtureTest(unittest.TestCase):
    def test_fixture_has_no_legacy_automation_scripts(self):
        names = [f for _, _, fs in os.walk(os.path.join(FIXTURES, "lab-config")) for f in fs]
        self.assertNotIn("Automation_Reviewed_Command.js", names)
        self.assertNotIn("Paste_Experiment_Console.js", names)


if __name__ == "__main__":
    unittest.main()
