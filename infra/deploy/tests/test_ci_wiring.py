"""INF-02's end-to-end test with the real jar (e2e/test_deploy_openpnp.py) is a job the `gate` needs: a backend change
that breaks a field il_deploy reads must not be released (stdlib only: ci.yml is read as text)."""
import os
import re
import unittest

CI_YML = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", ".github", "workflows", "ci.yml")
E2E = "infra/deploy/e2e/test_deploy_openpnp.py"


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


class CiWiring(unittest.TestCase):
    def test_the_gate_needs_the_deploy_e2e(self):
        with open(CI_YML) as f:
            self.assertEqual(e2e_gate_problems(f.read()), [])

    def test_the_checker_sees_a_missing_job(self):
        with open(CI_YML) as f:
            text = f.read()
        gate = re.search(r"^    needs: \[[^\]]*\]", jobs(text)["gate"], re.M).group(0)
        self.assertTrue(e2e_gate_problems(text.replace(gate, "    needs: [build, infra]")))
        self.assertTrue(e2e_gate_problems(text.replace("IL_DEPLOY_E2E: '1'", "IL_DEPLOY_E2E: '0'")))


if __name__ == "__main__":
    unittest.main()
