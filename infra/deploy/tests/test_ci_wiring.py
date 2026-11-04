"""ci.yml as il_deploy relies on it (stdlib only: ci.yml is read as text).

- INF-02's end-to-end test with the real jar (e2e/test_deploy_openpnp.py) is a job the `gate` needs: a backend change
  that breaks a field il_deploy reads must not be released.
- Concurrency: a PR's newer run cancels its older one; a push or manual run never shares a group with another run
  (GitHub keeps one pending run per group and cancels the older pending one: an il/integration merge would lose its
  gate and its release).
- The release job publishes only after the gate, only for a push to il/integration, and attests the tarball before
  publishing it (the deployer verifies that attestation), with exactly the permissions that needs.
- Its Publish step, run as GitHub runs it against a fake gh (tests/fake_gh.py): it publishes this run's build, a re-run
  passes on that release, and a release anyone else put at the tag first fails the step (the run must not end green
  while its build is never released).
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

import fake_gh
import make_release
from fakes import BRANCH, REPO, FakeGitHub

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
CI_YML = os.path.join(ROOT, ".github", "workflows", "ci.yml")
PUBLISHER = "infra/ci/publish_release.py"
E2E = "infra/deploy/e2e/test_deploy_openpnp.py"
INTEGRATION = "refs/heads/il/integration"
RELEASE_PERMISSIONS = {"contents": "write", "id-token": "write", "attestations": "write"}


def read_ci():
    with open(CI_YML) as f:
        return f.read()


def jobs(text):
    """{job id: its block of text} of a GitHub Actions workflow (jobs at two spaces of indent)."""
    body = text.split("\njobs:\n", 1)[1]
    out, name = {}, None
    for line in body.splitlines():
        m = re.match(r"^  ([\w-]+):\s*$", line)
        if m:
            name = m.group(1)
            out[name] = ""
        elif re.match(r"^\S", line):
            break
        elif name:
            out[name] += line + "\n"
    return out


def job_value(block, key):
    """A job's one-line key (four spaces of indent), or None; a folded `>-` value is its lines joined by spaces."""
    m = re.search(rf"^    {re.escape(key)}: *(.*)$((?:\n      .*)*)", block, re.M)
    if m and m.group(1).strip() in (">", ">-"):
        return " ".join(line.strip() for line in m.group(2).splitlines() if line.strip())
    return m.group(1).strip() if m else None


def job_map(block, key):
    """A job's block mapping (`    key:` then `      k: v` lines), or None."""
    m = re.search(rf"^    {re.escape(key)}:\s*\n((?:      \S.*\n)*)", block, re.M)
    if not m:
        return None
    return dict(re.match(r"^\s+([\w-]+):\s*(.*?)\s*$", line).groups() for line in m.group(1).splitlines())


def job_steps(block):
    """A job's steps, each a block of text that starts with `      - `."""
    m = re.search(r"^    steps:\s*\n((?:      .*\n|\s*\n)*)", block, re.M)
    if not m:
        return []
    return ["      - " + s for s in re.split(r"^      - ", m.group(1), flags=re.M) if s.strip()]


def step_run(step):
    """The shell script of a step's `run:` (a `|` block or one line); ValueError if it has none this can read."""
    lines = step.splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^([ -]*)run:\s*(.*?)\s*$", line)
        if not m:
            continue
        if m.group(2) != "|":
            if m.group(2)[:1] in (">", "|", ""):
                raise ValueError(f"cannot read the run: {m.group(2)!r}")
            return m.group(2) + "\n"
        body = []
        for l in lines[i + 1:]:
            if l.strip() and len(l) - len(l.lstrip()) <= len(m.group(1)):
                break
            body.append(l)
        indent = min(len(l) - len(l.lstrip()) for l in body if l.strip())
        return "\n".join(l[indent:] for l in body).strip("\n") + "\n"
    raise ValueError("the step has no run:")


# --- a GitHub Actions expression evaluator: the part of the language ci.yml uses -----------------------------------
_TOKEN = re.compile(r"\s*(?:(?P<str>'(?:[^']|'')*')|(?P<num>\d+)|(?P<op>==|!=|&&|\|\||[!(),])"
                    r"|(?P<name>[A-Za-z_][\w-]*(?:\.(?:[A-Za-z_][\w-]*|\*))*))")


def evaluate(expr, ctx):
    """Evaluate an expression the way GitHub does: '...' strings ('' is a quote), numbers, true/false/null, context
    paths (one not in ctx is null; `labels.*.name` is one path), ==/!= (strings compared case-insensitively), !, && and
    || (which return an operand, not a boolean), parentheses, format(), contains() and fromJSON(). Anything else raises ValueError, so a change to ci.yml the
    evaluator cannot read fails the test instead of passing it."""
    expr, toks, pos = expr.strip(), [], 0
    while pos < len(expr):
        m = _TOKEN.match(expr, pos)
        if not m or m.end() == pos:
            raise ValueError(f"cannot read {expr[pos:]!r} in {expr!r}")
        pos = m.end()
        toks.append((m.lastgroup, m.group(m.lastgroup)))
    toks.append(("end", None))
    i = 0

    def peek():
        return toks[i]

    def take(kind=None, value=None):
        nonlocal i
        k, v = toks[i]
        if (kind and k != kind) or (value and v != value):
            raise ValueError(f"expected {value or kind}, got {v!r} in {expr!r}")
        i += 1
        return v

    def disjunction():
        left = conjunction()
        while peek() == ("op", "||"):
            take()
            right = conjunction()
            left = left if truthy(left) else right
        return left

    def conjunction():
        left = comparison()
        while peek() == ("op", "&&"):
            take()
            right = comparison()
            left = right if truthy(left) else left
        return left

    def comparison():
        left = unary()
        if peek() in (("op", "=="), ("op", "!=")):
            op = take()
            same = equal(left, unary())
            return same if op == "==" else not same
        return left

    def unary():
        if peek() == ("op", "!"):
            take()
            return not truthy(unary())
        return primary()

    def primary():
        kind, v = peek()
        if kind == "str":
            take()
            return v[1:-1].replace("''", "'")
        if kind == "num":
            take()
            return int(v)
        if (kind, v) == ("op", "("):
            take()
            out = disjunction()
            take("op", ")")
            return out
        if kind == "name":
            take()
            if v in ("true", "false", "null"):
                return {"true": True, "false": False, "null": None}[v]
            if peek() == ("op", "("):
                take()
                args = [] if peek() == ("op", ")") else [disjunction()]
                while peek() == ("op", ","):
                    take()
                    args.append(disjunction())
                take("op", ")")
                if v not in FUNCTIONS:
                    raise ValueError(f"function {v}() is not known to this evaluator")
                return FUNCTIONS[v](*args)
            return ctx.get(v)
        raise ValueError(f"unexpected {v!r} in {expr!r}")

    out = disjunction()
    take("end")
    return out


def truthy(v):
    return v not in (None, False, 0, "")


def equal(a, b):
    if isinstance(a, str) and isinstance(b, str):
        return a.lower() == b.lower()
    return a == b


def to_str(v):
    if v is None:
        return ""
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)


def gh_format(fmt, *args):
    return re.sub(r"\{\{|\}\}|\{(\d+)\}",
                  lambda m: {"{{": "{", "}}": "}"}.get(m.group(0)) or to_str(args[int(m.group(1))]), fmt)


def gh_contains(search, item):
    """GitHub's contains(): an element of an array, or a part of a string; strings ignore case."""
    if isinstance(search, list):
        return any(equal(x, item) for x in search)
    return to_str(item).lower() in to_str(search).lower()


def gh_from_json(value):
    try:
        return json.loads(value)
    except (TypeError, ValueError) as e:
        raise ValueError(f"fromJSON({value!r}): {e}")


FUNCTIONS = {"format": gh_format, "contains": gh_contains, "fromJSON": gh_from_json}


def interpolate(value, ctx):
    """A YAML value with ${{ }}: one expression alone keeps its type, else the parts are joined as strings."""
    m = re.fullmatch(r"\$\{\{(.*?)\}\}", value.strip())
    if m:
        return evaluate(m.group(1), ctx)
    return re.sub(r"\$\{\{(.*?)\}\}", lambda e: to_str(evaluate(e.group(1), ctx)), value)


def condition(value, ctx):
    """An `if:`: the ${{ }} around it is optional."""
    m = re.fullmatch(r"\$\{\{(.*)\}\}", value.strip())
    return truthy(evaluate(m.group(1) if m else value, ctx))


def concurrency(text, ctx):
    """(group, cancel-in-progress) of the workflow for a run with these contexts."""
    m = re.search(r"^concurrency:\n  group: (.+)\n  cancel-in-progress: (.+)$", text, re.M)
    if not m:
        raise ValueError("no workflow-level concurrency group and cancel-in-progress")
    return to_str(interpolate(m.group(1), ctx)), truthy(interpolate(m.group(2), ctx))


# --- the checks ---------------------------------------------------------------------------------------------------
def e2e_gate_problems(text):
    """Why the deploy e2e does not gate releases ([] = it does)."""
    js = jobs(text)
    runs = [n for n, b in js.items() if re.search(r"^\s+run: .*python3 " + re.escape(E2E), b, re.M)]
    if len(runs) != 1:
        return [f"{len(runs)} jobs run {E2E} (expected one)"]
    job, block = runs[0], js[runs[0]]
    out = []
    if not re.search(r"^\s+IL_DEPLOY_E2E: '1'\s*$", block, re.M):
        out.append(f"{job}: IL_DEPLOY_E2E is not '1' (the test would skip)")
    for key in ("IL_DEPLOY_E2E_JAR", "IL_DEPLOY_E2E_LIB"):
        if not re.search(rf"^\s+{key}: \S", block, re.M):
            out.append(f"{job}: no {key}")
    if "continue-on-error" in block:
        out.append(f"{job}: continue-on-error")
    if not re.search(r"^    needs: \[?[^\n]*\bbuild\b", block, re.M) or "name: openpnp-build" not in block:
        out.append(f"{job}: does not test the build job's jar (needs: build, download openpnp-build)")
    m = re.search(r"^    needs: \[([^\]]*)\]", js.get("gate", ""), re.M)
    if not m or job not in [x.strip() for x in m.group(1).split(",")]:
        out.append(f"the gate does not need {job}")
    return out


def _runs_release(cond, event, ref, gate):
    return condition(cond, {"github.event_name": event, "github.ref": ref, "needs.gate.result": gate})


def _path(p):
    return re.sub(r"\$\{\{\s*runner\.temp\s*\}\}|\$\{?RUNNER_TEMP\}?", "<tmp>", p.strip('"\'')).rstrip("/")


def release_problems(text):
    """Why the release job could publish what the deployer must not install, or publish it unattested ([] = it
    cannot)."""
    block = jobs(text).get("release")
    if block is None:
        return ["no release job"]
    out = []
    needs = job_value(block, "needs") or ""
    if "gate" not in [n.strip() for n in needs.strip("[]").split(",")]:
        out.append(f"release needs {needs or 'nothing'}, not the gate")
    cond = job_value(block, "if")
    if not cond:
        out.append("release has no if: it runs for every event")
    else:
        if not _runs_release(cond, "push", INTEGRATION, "success"):
            out.append("release does not run for a green push to il/integration")
        for event, ref, gate in (("push", "refs/heads/il/scratch", "success"),
                                 ("workflow_dispatch", INTEGRATION, "success"),
                                 ("pull_request", "refs/pull/7/merge", "success"),
                                 ("push", INTEGRATION, "failure"), ("push", INTEGRATION, "cancelled"),
                                 ("push", INTEGRATION, "skipped")):
            if _runs_release(cond, event, ref, gate):
                out.append(f"release runs for {event} on {ref} with the gate {gate}")
    perms = job_map(block, "permissions")
    if perms != RELEASE_PERMISSIONS:
        out.append(f"release permissions are {perms}, not exactly {RELEASE_PERMISSIONS}")
    if "continue-on-error" in block:
        out.append("release: continue-on-error")
    steps = job_steps(block)
    attest = [i for i, s in enumerate(steps)
              if re.search(r"^\s+(- )?uses: actions/attest-build-provenance@v\d", s, re.M)]
    publish = [i for i, s in enumerate(steps) if PUBLISHER in s]
    by_hand = [i for i, s in enumerate(steps) if re.search(r"\bgh release (create|upload|edit|delete)\b", s)]
    if by_hand:
        out.append(f"release step {by_hand[0] + 1} runs gh release itself: only {PUBLISHER} may publish (it checks "
                   "that a release already at the tag is this run's own)")
    if len(publish) != 1:
        return out + [f"{len(publish)} release steps run {PUBLISHER} (expected one)"]
    if len(attest) != 1:
        return out + [f"{len(attest)} release steps use actions/attest-build-provenance (expected one)"]
    a, p = steps[attest[0]], steps[publish[0]]
    if attest[0] > publish[0]:
        out.append("the attest step runs after the publish step")
    for name, step in (("attest", a), ("publish", p)):
        if re.search(r"^\s+(if|continue-on-error):", step, re.M):
            out.append(f"the {name} step can be skipped or fail (if/continue-on-error)")
    subject = re.search(r"^\s+subject-path: *(.+?)\s*$", a, re.M)
    published = re.search(r"--dir[ =](\"[^\"]*\"|\S+)", p)
    if not subject or not subject.group(1).endswith("/openpnp-il-*.tar.gz"):
        out.append(f"the attest step's subject-path is {subject and subject.group(1)!r}, not the openpnp-il-*.tar.gz "
                   "tarball")
    elif not published or os.path.dirname(_path(subject.group(1))) != _path(published.group(1)):
        out.append("the attest step's tarball is not the one the publish step publishes "
                   f"({subject.group(1)} vs --dir {published and published.group(1)})")
    return out


# --- the Publish step's own script, run against a fake gh ---------------------------------------------------------
# The push run whose Publish step runs (run id 1000 + run number, as FakeGitHub.add_release names a release's run)
STEP_SHA, STEP_RUN_ID, STEP_RUN_NUMBER = "c" * 40, 1007, 7
STEP_TAG = f"il-build-{STEP_RUN_NUMBER}-{STEP_SHA[:7]}"


class StepRun:
    """The release job's step that publishes, run as GitHub Actions runs a step (bash -eo pipefail in the checkout,
    the job's environment) with tests/fake_gh.py as gh, for a push run of il/integration that the gate passed."""

    def __init__(self, text, tmp):
        steps = [s for s in job_steps(jobs(text)["release"]) if PUBLISHER in s or re.search(r"\bgh release\b", s)]
        if len(steps) != 1:
            raise ValueError(f"{len(steps)} release steps publish (expected one)")
        self.tmp = tmp
        self.script = os.path.join(tmp, "publish.sh")
        with open(self.script, "w") as f:
            f.write(step_run(steps[0]))
        self.bin = os.path.join(tmp, "bin")
        os.makedirs(self.bin)
        with open(os.path.join(self.bin, "gh"), "w") as f:
            f.write(f'#!/bin/sh\nexec "{sys.executable}" "{os.path.join(HERE, "fake_gh.py")}" "$@"\n')
        os.chmod(os.path.join(self.bin, "gh"), 0o755)
        self.state = os.path.join(tmp, "gh-state.json")
        self.github = FakeGitHub()

    def package(self, attempt):
        """$RUNNER_TEMP/rel as the Package step writes it, its tarball attested by this run's attempt (attest step)."""
        rel = os.path.join(self.tmp, "runner", "rel")
        shutil.rmtree(rel, ignore_errors=True)
        src = os.path.join(self.tmp, f"build{attempt}")
        os.makedirs(os.path.join(src, "lib"), exist_ok=True)
        for name in ("openpnp.jar", "lib/dep.jar"):
            with open(os.path.join(src, name), "wb") as f:
                f.write(f"{name} of attempt {attempt}".encode())
        make_release.make(os.path.join(src, "openpnp.jar"), os.path.join(src, "lib"), STEP_TAG, STEP_SHA,
                          BRANCH, STEP_RUN_ID, STEP_RUN_NUMBER,
                          f"https://github.com/{REPO}/actions/runs/{STEP_RUN_ID}", rel)
        with open(os.path.join(rel, f"openpnp-il-{STEP_SHA[:7]}.tar.gz"), "rb") as f:
            self.github.attest(f.read(), STEP_RUN_ID, STEP_SHA, attempt=attempt)
        return rel

    def run(self, attempt=1):
        """(exit code, output, gh release create calls) of the step in this run's attempt."""
        self.package(attempt)
        fake_gh.dump(self.github, self.state)
        env = {"PATH": self.bin + os.pathsep + os.environ["PATH"], "HOME": self.tmp, "FAKE_GH_STATE": self.state,
               "GITHUB_REPOSITORY": REPO, "GITHUB_SHA": STEP_SHA, "GITHUB_RUN_ID": str(STEP_RUN_ID),
               "GITHUB_RUN_NUMBER": str(STEP_RUN_NUMBER), "GITHUB_RUN_ATTEMPT": str(attempt),
               "GITHUB_REF": INTEGRATION, "GITHUB_REF_NAME": BRANCH, "GITHUB_EVENT_NAME": "push",
               "GITHUB_SERVER_URL": "https://github.com", "GITHUB_WORKSPACE": ROOT,
               "RUNNER_TEMP": os.path.join(self.tmp, "runner"), "TAG": STEP_TAG, "GH_TOKEN": "a-fake-token"}
        p = subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", self.script], cwd=ROOT, env=env,
                           capture_output=True, text=True, timeout=120)
        self.github, _, calls = fake_gh.load(self.state)
        return p.returncode, p.stdout + p.stderr, [c for c in calls if c[1:3] == ["release", "create"]]


def publish_problems(text):
    """Why the release job's Publish step could leave a run green with its build unreleased, or not publish it ([] =
    it cannot): run with nothing at the tag, then as a re-run, then with a release someone else put there first."""
    out = []
    with tempfile.TemporaryDirectory(prefix="il-ci-publish-") as tmp:
        step = StepRun(text, tmp)
        rc, said, creates = step.run(attempt=1)
        rel = next((r for r in step.github.releases if r["tag_name"] == STEP_TAG), None)
        if rc != 0 or len(creates) != 1 or not rel or rel["target_commitish"] != STEP_SHA \
                or sorted(a["name"] for a in rel["assets"]) != sorted(
                    ["BUILD-INFO.json", f"openpnp-il-{STEP_SHA[:7]}.tar.gz", f"openpnp-il-{STEP_SHA[:7]}.tar.gz.sha256"]):
            return [f"the step does not publish this run's build at {STEP_SHA[:7]} (exit {rc}): {said[-400:]}"]
        rc, said, creates = step.run(attempt=2)
        if rc != 0 or creates:
            out.append(f"a re-run of the step fails on the release its first attempt published (exit {rc}, "
                       f"{len(creates)} creates): {said[-400:]}")
    first = {"a person": dict(author="mallory"),
             "github-actions[bot] (a PR run's token), naming this run, its tarball not attested by this run":
                 dict(jar={"not": "this run's build"}, attest=False)}
    for who, kw in first.items():
        with tempfile.TemporaryDirectory(prefix="il-ci-publish-") as tmp:
            step = StepRun(text, tmp)
            step.github.add_release(STEP_RUN_NUMBER, STEP_SHA, gate=None, **kw)
            rc, said, creates = step.run(attempt=1)
            if rc == 0:
                out.append(f"the step passes on a release at its tag published first by {who}: the run would end "
                           "green with its build never released")
    return out


class CiWiring(unittest.TestCase):
    def test_the_gate_needs_the_deploy_e2e(self):
        self.assertEqual(e2e_gate_problems(read_ci()), [])

    def test_the_checker_sees_a_missing_job(self):
        text = read_ci()
        gate = re.search(r"^    needs: \[[^\]]*\]", jobs(text)["gate"], re.M).group(0)
        self.assertTrue(e2e_gate_problems(text.replace(gate, "    needs: [build, infra]")))
        self.assertTrue(e2e_gate_problems(text.replace("IL_DEPLOY_E2E: '1'", "IL_DEPLOY_E2E: '0'")))


class Concurrency(unittest.TestCase):
    """GitHub cancels a pending run of a group when a newer one queues. Only push runs release (il_deploy wants event
    push): neither a newer push nor a workflow_dispatch on il/integration may replace a merge's pending push run."""
    RUNS = {
        "push 101": {"github.event_name": "push", "github.ref": INTEGRATION, "github.run_id": 101},
        "push 102": {"github.event_name": "push", "github.ref": INTEGRATION, "github.run_id": 102},
        "dispatch 103": {"github.event_name": "workflow_dispatch", "github.ref": INTEGRATION, "github.run_id": 103},
        "dispatch 104": {"github.event_name": "workflow_dispatch", "github.ref": INTEGRATION, "github.run_id": 104},
        "pr7 105": {"github.event_name": "pull_request", "github.ref": "refs/pull/7/merge", "github.run_id": 105,
                    "github.event.pull_request.number": 7},
        "pr7 106": {"github.event_name": "pull_request", "github.ref": "refs/pull/7/merge", "github.run_id": 106,
                    "github.event.pull_request.number": 7},
        "pr8 107": {"github.event_name": "pull_request", "github.ref": "refs/pull/8/merge", "github.run_id": 107,
                    "github.event.pull_request.number": 8},
    }

    def setUp(self):
        text = read_ci()
        self.groups = {name: concurrency(text, ctx) for name, ctx in self.RUNS.items()}

    def test_a_prs_runs_share_a_group_and_the_newer_cancels_the_older(self):
        self.assertEqual(self.groups["pr7 105"][0], self.groups["pr7 106"][0])
        self.assertTrue(self.groups["pr7 105"][1] and self.groups["pr7 106"][1])
        self.assertNotEqual(self.groups["pr7 105"][0], self.groups["pr8 107"][0])

    def test_push_and_manual_runs_never_share_a_group(self):
        for a in self.RUNS:
            for b in self.RUNS:
                if a < b and not (a.startswith("pr7") and b.startswith("pr7")):
                    with self.subTest(a=a, b=b):
                        self.assertNotEqual(self.groups[a][0], self.groups[b][0])

    def test_push_and_manual_runs_cancel_nothing(self):
        for name in ("push 101", "dispatch 103"):
            self.assertFalse(self.groups[name][1], name)

    def test_the_evaluator(self):
        ctx = {"github.event_name": "push", "n": 7}
        self.assertEqual(evaluate("github.event_name == 'PUSH' && format('a-{0}-{1}', n, x) || 'no'", ctx), "a-7-")
        self.assertEqual(evaluate("github.event_name == 'pull_request' && 'pr' || 'other'", ctx), "other")
        self.assertEqual(evaluate("!(n != 7) && 'it''s'", ctx), "it's")
        self.assertEqual(evaluate("format('{{{0}}}', 'x')", ctx), "{x}")
        ctx = {"labels.*.name": ["Full-CI"], "passed": '["infra", "ui-e2e"]', "ref": "refs/heads/il/integration"}
        self.assertTrue(evaluate("contains(labels.*.name, 'full-ci')", ctx))  # an element, ignoring case
        self.assertFalse(evaluate("contains(labels.*.name, 'full')", ctx))  # an element, not a part of one
        self.assertTrue(evaluate("contains(ref, 'IL/integ')", ctx))  # a string: a part of it, ignoring case
        self.assertTrue(evaluate("!contains(fromJSON(passed), 'maven') && contains(fromJSON(passed), 'ui-e2e')", ctx))
        for bad in ("startsWith(github.ref, 'x')", "fromJSON('[')", "github.ref = 'x'", "github.ref[0]", "'open"):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                evaluate(bad, ctx)


# The Publish step before the 11-01 review: a release at the tag passed if it was at this commit with the three file
# names, whoever published it.
OLD_PUBLISH = """\
      - name: Publish
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          set -euo pipefail
          SHA7="${GITHUB_SHA:0:7}"
          if gh release view "$TAG" --json tagName >/dev/null 2>&1; then
            target="$(gh api "repos/$GITHUB_REPOSITORY/releases/tags/$TAG" --jq .target_commitish)"
            assets="$(gh release view "$TAG" --json assets --jq '[.assets[].name] | sort | join(" ")')"
            want="BUILD-INFO.json openpnp-il-$SHA7.tar.gz openpnp-il-$SHA7.tar.gz.sha256"
            if [ "$target" = "$GITHUB_SHA" ] && [ "$assets" = "$want" ]; then
              echo "release $TAG already published by an earlier attempt of this run"; exit 0
            fi
            echo "::error::release $TAG exists but is incomplete or at another commit"
            exit 1
          fi
          gh release create "$TAG" --target "$GITHUB_SHA" --prerelease --latest=false --title "$TAG" \\
            --notes "Green build" \\
            "$RUNNER_TEMP/rel/openpnp-il-$SHA7.tar.gz" "$RUNNER_TEMP/rel/openpnp-il-$SHA7.tar.gz.sha256" \\
            "$RUNNER_TEMP/rel/BUILD-INFO.json"
"""


class Release(unittest.TestCase):
    def test_the_release_job_publishes_only_attested_green_pushes_to_il_integration(self):
        self.assertEqual(release_problems(read_ci()), [])

    def test_the_publish_step_publishes_this_runs_build_and_nothing_else_passes_it(self):
        self.assertEqual(publish_problems(read_ci()), [])

    def test_the_step_checker_sees_the_old_publish_step(self):
        # the review's reproduction: the old step exits 0 on a release someone else put at the tag first
        text = read_ci()
        publish = next(s for s in job_steps(jobs(text)["release"]) if PUBLISHER in s)
        old = text.replace(publish, OLD_PUBLISH)
        problems = publish_problems(old)
        self.assertTrue(any("a person" in p for p in problems), problems)
        self.assertTrue(any("not attested by this run" in p for p in problems), problems)
        self.assertTrue(release_problems(old))
        lenient = text.replace(publish, publish.replace('--run-id "$GITHUB_RUN_ID"', '--run-id "$GITHUB_RUN_NUMBER"'))
        self.assertNotEqual(lenient, text, "the mutation did not apply")
        self.assertTrue(publish_problems(lenient), "the step must judge the release by this run's id")

    def test_step_run(self):
        step = "      - name: x\n        run: |\n          a \\\n            b\n\n          c\n        env:\n          K: v\n"
        self.assertEqual(step_run(step), "a \\\n  b\n\nc\n")
        self.assertEqual(step_run("      - run: echo hi\n"), "echo hi\n")
        for bad in ("      - run: >-\n          a\n", "      - uses: x\n"):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                step_run(bad)

    def test_the_checker_sees_a_weaker_release_job(self):
        text = read_ci()
        block = jobs(text)["release"]
        steps = job_steps(block)
        attest = next(s for s in steps if "attest-build-provenance" in s)
        publish = next(s for s in steps if PUBLISHER in s)
        cond = job_value(block, "if")
        mutations = {
            "no gate": text.replace(block, block.replace("    needs: [gate]", "    needs: [build]")),
            "manual runs release": text.replace(cond, cond.replace("github.event_name == 'push'",
                                                                   "github.event_name != 'pull_request'")),
            "any branch": text.replace(cond, cond.replace(" && github.ref == 'refs/heads/il/integration'", "")),
            "red gate": text.replace(cond, cond.replace(" && needs.gate.result == 'success'", "")),
            "no if": text.replace(f"    if: {cond}\n", ""),
            "more permissions": text.replace("      attestations: write\n",
                                             "      attestations: write\n      packages: write\n"),
            "no id-token": text.replace("      id-token: write\n", ""),
            "no attest step": text.replace(attest, ""),
            "attest after publish": text.replace(attest, "").replace(publish, publish + attest),
            "attest skippable": text.replace(attest, attest.replace("        uses: actions/attest",
                                                                    "        if: false\n        uses: actions/attest")),
            "attests the jar": text.replace(attest, attest.replace("rel/openpnp-il-*.tar.gz", "target/*.jar")),
            "attests another dir": text.replace(attest, attest.replace("${{ runner.temp }}/rel/",
                                                                       "${{ runner.temp }}/x/")),
            "publishes by hand": text.replace(publish, OLD_PUBLISH),
            "publish skippable": text.replace(publish, publish.replace("        run: |",
                                                                       "        if: false\n        run: |")),
            "publish may fail": text.replace(publish, publish.replace("        run: |",
                                                                      "        continue-on-error: true\n        run: |")),
        }
        for name, mutated in mutations.items():
            with self.subTest(name):
                self.assertNotEqual(mutated, text, "the mutation did not apply")
                self.assertTrue(release_problems(mutated), name)


if __name__ == "__main__":
    unittest.main()
