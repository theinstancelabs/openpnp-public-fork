"""ci.yml as il_deploy relies on it (stdlib only: ci.yml is read as text).

- INF-02's end-to-end test with the real jar (e2e/test_deploy_openpnp.py) is a job the `gate` needs: a backend change
  that breaks a field il_deploy reads must not be released.
- Concurrency: a PR's newer run cancels its older one; a push or manual run never shares a group with another run
  (GitHub keeps one pending run per group and cancels the older pending one: an il/integration merge would lose its
  gate and its release).
- The release job publishes only after the gate, only for a push to il/integration, and attests the tarball before
  publishing it (the deployer verifies that attestation), with exactly the permissions that needs.
"""
import os
import re
import unittest

CI_YML = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", ".github", "workflows", "ci.yml")
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
    """A job's one-line key (four spaces of indent), or None."""
    m = re.search(rf"^    {re.escape(key)}: *(.*)$", block, re.M)
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


# --- a GitHub Actions expression evaluator: the part of the language ci.yml uses -----------------------------------
_TOKEN = re.compile(r"\s*(?:(?P<str>'(?:[^']|'')*')|(?P<num>\d+)|(?P<op>==|!=|&&|\|\||[!(),])"
                    r"|(?P<name>[A-Za-z_][\w-]*(?:\.[A-Za-z_][\w-]*)*))")


def evaluate(expr, ctx):
    """Evaluate an expression the way GitHub does: '...' strings ('' is a quote), numbers, true/false/null, context
    paths (one not in ctx is null), ==/!= (strings compared case-insensitively), !, && and || (which return an
    operand, not a boolean), parentheses and format(). Anything else raises ValueError, so a change to ci.yml the
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
                if v != "format":
                    raise ValueError(f"function {v}() is not known to this evaluator")
                return gh_format(*args)
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


def _same_dir(a, b):
    norm = lambda p: os.path.dirname(re.sub(r"\$\{\{\s*runner\.temp\s*\}\}|\$\{?RUNNER_TEMP\}?", "<tmp>", p.strip('"')))
    return norm(a) == norm(b)


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
    publish = [i for i, s in enumerate(steps) if "gh release create" in s]
    if len(publish) != 1:
        return out + [f"{len(publish)} release steps run gh release create (expected one)"]
    if len(attest) != 1:
        return out + [f"{len(attest)} release steps use actions/attest-build-provenance (expected one)"]
    a, p = steps[attest[0]], steps[publish[0]]
    if attest[0] > publish[0]:
        out.append("the attest step runs after the publish step")
    if re.search(r"^\s+(if|continue-on-error):", a, re.M):
        out.append("the attest step can be skipped or fail (if/continue-on-error)")
    subject = re.search(r"^\s+subject-path: *(.+?)\s*$", a, re.M)
    uploaded = re.search(r"(\S*/openpnp-il-\$SHA7\.tar\.gz)\"?\s", p)
    if not subject or not subject.group(1).endswith("/openpnp-il-*.tar.gz"):
        out.append(f"the attest step's subject-path is {subject and subject.group(1)!r}, not the openpnp-il-*.tar.gz "
                   "tarball")
    elif not uploaded or not _same_dir(subject.group(1), uploaded.group(1)):
        out.append("the attest step's tarball is not the one the publish step uploads "
                   f"({subject.group(1)} vs {uploaded and uploaded.group(1)})")
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
        for bad in ("contains(github.ref, 'x')", "github.ref = 'x'", "github.ref[0]", "'open"):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                evaluate(bad, ctx)


class Release(unittest.TestCase):
    def test_the_release_job_publishes_only_attested_green_pushes_to_il_integration(self):
        self.assertEqual(release_problems(read_ci()), [])

    def test_the_checker_sees_a_weaker_release_job(self):
        text = read_ci()
        block = jobs(text)["release"]
        steps = job_steps(block)
        attest = next(s for s in steps if "attest-build-provenance" in s)
        publish = next(s for s in steps if "gh release create" in s)
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
        }
        for name, mutated in mutations.items():
            with self.subTest(name):
                self.assertNotEqual(mutated, text, "the mutation did not apply")
                self.assertTrue(release_problems(mutated), name)


if __name__ == "__main__":
    unittest.main()
