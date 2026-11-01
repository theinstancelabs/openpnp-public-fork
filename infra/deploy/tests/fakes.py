"""Test doubles for il_deploy: a fake GitHub (with a commit graph, so compare semantics are real),
CI-style release tarballs, a temporary lab (deploy root, STATE.md, lock, device, activity file),
fake process scans and a fake clock."""
import contextlib
import hashlib
import io
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tarfile
import tempfile
import time

# il_deploy is one level up: also when unittest imports this module by bare name (no -t); make_release (CI's
# release packager) is in infra/ci
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "ci"))
import il_deploy  # noqa: E402
import make_release  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
FAKE_OPENPNP = os.path.join(HERE, "fake_openpnp.py")
REPO = "theinstancelabs/openpnp"
BRANCH = "il/integration"
API = "https://api.github.com/"


def sha_of(name):
    return hashlib.sha1(name.encode()).hexdigest()


class FakeGitHub:
    """get_json(path) and download(asset, dest), like il_deploy's clients, from in-memory data."""

    def __init__(self, repo=REPO, branch=BRANCH):
        self.repo = repo
        self.branch = branch
        self.parents = {}
        self.head = None
        self.releases = []
        self.assets = {}
        self.checks = {}
        self.runs = {}  # workflow runs by id: GET repos/<repo>/actions/runs/<id>
        self.attestations = {}  # sha256 of an artifact -> the attestations GitHub stores for it (FakeGhCli reads them)
        self.calls = []
        self.downloads = []
        self._ids = 0

    # --- commit graph --------------------------------------------------------------------------
    def commit(self, name, *parents, sha=None):
        sha = sha or sha_of(name)
        self.parents[sha] = list(parents)
        return sha

    def ancestors(self, sha):
        seen, todo = set(), [sha]
        while todo:
            s = todo.pop()
            if s not in seen:
                seen.add(s)
                todo.extend(self.parents.get(s, []))
        return seen

    def compare(self, base, head):
        # GitHub: ahead_by = commits in head not in base, behind_by = commits in base not in head
        a, b = self.ancestors(head), self.ancestors(base)
        ahead, behind = len(a - b), len(b - a)
        if ahead == 0 and behind == 0:
            return "identical"
        if behind == 0:
            return "ahead"
        if ahead == 0:
            return "behind"
        return "diverged"

    def next_id(self):
        self._ids += 1
        return self._ids

    def set_gate(self, sha, conclusion="success", status="completed", name="gate", suite=None):
        """A check-run on sha, in a check suite: by default the one of the newest workflow run at sha (a re-run of the
        CI run that built it adds its check-runs there), else a suite of its own (another workflow's run)."""
        if suite is None:
            runs = [r for r in self.runs.values() if r["head_sha"] == sha]
            suite = max(runs, key=lambda r: r["id"])["check_suite_id"] if runs else 900000 + self.next_id()
        self.checks.setdefault(sha, []).append({"id": self.next_id(), "name": name, "status": status,
                                                "conclusion": conclusion, "head_sha": sha,
                                                "check_suite": {"id": suite}})

    # --- releases ------------------------------------------------------------------------------
    def add_run(self, run_id, run_number, sha, branch=BRANCH, overrides=None):
        """The CI run that published a release: a successful push run of ci.yml on the branch, at sha."""
        self.runs[int(run_id)] = dict({"id": int(run_id), "run_number": int(run_number), "head_sha": sha,
                                       "head_branch": branch, "event": "push", "path": ".github/workflows/ci.yml",
                                       "status": "completed", "conclusion": "success", "run_attempt": 1,
                                       "check_suite_id": 500000 + int(run_id)},
                                      **(overrides or {}))
        return self.runs[int(run_id)]

    def attest(self, data, run, commit, **overrides):
        """CI's release job attests the artifact (actions/attest-build-provenance): GitHub stores the attestation under
        the artifact's sha256. overrides: repo, workflow, ref, runner ('self-hosted'), attempt, event, invocation (the
        provenance's runDetails invocationId, if it differs from the certificate's run), and run_id or sha (another
        run's or commit's attestation of the same bytes)."""
        rec = dict({"repo": self.repo, "workflow": ".github/workflows/ci.yml", "ref": "refs/heads/" + self.branch,
                    "sha": commit, "run_id": int(run), "attempt": 1, "runner": "github-hosted", "event": "push",
                    "invocation": None}, **overrides)
        self.attestations.setdefault(hashlib.sha256(data).hexdigest(), []).append(rec)
        return rec

    def unattest(self, data):
        """The attestations of an artifact deleted (GitHub lets a repository admin delete them)."""
        self.attestations.pop(hashlib.sha256(data).hexdigest(), None)

    def asset_bytes(self, tag, name):
        rel = next(r for r in self.releases if r["tag_name"] == tag)
        return self.assets[next(a for a in rel["assets"] if a["name"] == name)["browser_download_url"]]

    def add_release(self, run_number, sha, branch=BRANCH, jar=None, corrupt_sha256=False, gate="success",
                    info_overrides=None, target=None, members=None, omit=(), author="github-actions[bot]", run=True,
                    run_overrides=None, member_overrides=None, attest=True):
        """A release as CI's release job publishes it (author github-actions[bot], its run a successful push of ci.yml,
        the tarball attested by that run); author, run=False (no such run), run_overrides and attest (False: none, a
        dict: FakeGitHub.attest's overrides) make the other kinds. members replaces the tarball's files,
        member_overrides changes or adds some of them ({name: bytes})."""
        sha7 = sha[:7]
        tag = f"il-build-{run_number}-{sha7}"
        jar_bytes = json.dumps(jar if jar is not None else {"sha": sha}).encode()
        info = {"version": tag, "sha": sha, "branch": branch, "run_id": 1000 + run_number,
                "run_number": run_number, "run_url": f"https://github.com/{self.repo}/actions/runs/{1000 + run_number}",
                "built_at": "2026-10-31T12:00:00Z", "jar_sha256": hashlib.sha256(jar_bytes).hexdigest()}
        info.update(info_overrides or {})
        info_bytes = json.dumps(info, indent=1).encode()
        files = members if members is not None else {
            "openpnp-il/openpnp-il.jar": jar_bytes,
            "openpnp-il/lib/dep-1.0.jar": b"dependency",
            "openpnp-il/VERSION.txt": (tag + "\n").encode(),
            "openpnp-il/BUILD-INFO.json": info_bytes,
        }
        files = {**files, **(member_overrides or {})}
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w:gz") as tf:
            for name, data in files.items():
                ti = tarfile.TarInfo(name)
                ti.size = len(data)
                ti.mode = 0o644
                tf.addfile(ti, io.BytesIO(data))
        tar_bytes = buf.getvalue()
        tar_name = f"openpnp-il-{sha7}.tar.gz"
        digest = hashlib.sha256(tar_bytes).hexdigest()
        if corrupt_sha256:
            digest = hashlib.sha256(b"something else").hexdigest()
        files = [(tar_name, tar_bytes), (tar_name + ".sha256", f"{digest}  {tar_name}\n".encode()),
                 ("BUILD-INFO.json", info_bytes)]
        if run:
            self.add_run(1000 + run_number, run_number, sha, branch, run_overrides)
        if attest:
            self.attest(tar_bytes, 1000 + run_number, sha, **(attest if isinstance(attest, dict) else {}))
        return self._publish(run_number, sha, tag, [f for f in files if f[0] not in omit], target or sha, gate, author)

    def add_release_dir(self, out, gate="success"):
        """Publish what infra/ci/make_release.py wrote into out, as CI's release job does: the tarball attested by the
        run BUILD-INFO names, then `gh release create "$TAG" --target "$SHA"` with the tarball, its .sha256 and
        BUILD-INFO.json, under the tag and sha that BUILD-INFO names."""
        with open(os.path.join(out, "BUILD-INFO.json"), "rb") as f:
            info_bytes = f.read()
        info = json.loads(info_bytes)
        tag, sha = info["version"], info["sha"]
        tar_name = f"openpnp-il-{sha[:7]}.tar.gz"
        files = []
        for name in (tar_name, tar_name + ".sha256"):
            with open(os.path.join(out, name), "rb") as f:
                files.append((name, f.read()))
        self.add_run(info["run_id"], info["run_number"], sha, info["branch"])
        self.attest(files[0][1], info["run_id"], sha)
        return self._publish(il_deploy.parse_tag(tag)[0], sha, tag, files + [("BUILD-INFO.json", info_bytes)], sha,
                             gate)

    def _publish(self, run_number, sha, tag, files, target, gate, author="github-actions[bot]"):
        assets = []
        for i, (name, data) in enumerate(files):
            aid = run_number * 10 + i
            url = f"https://github.com/{self.repo}/releases/download/{tag}/{name}"
            self.assets[url] = data
            assets.append({"id": aid, "name": name, "size": len(data), "browser_download_url": url,
                           "url": f"{API}repos/{self.repo}/releases/assets/{aid}"})
        self.releases.insert(0, {"id": run_number, "tag_name": tag, "target_commitish": target,
                                 "author": {"login": author, "type": "Bot" if author.endswith("[bot]") else "User"},
                                 "draft": False, "prerelease": True,
                                 "created_at": f"2026-10-31T12:{run_number % 60:02d}:00Z",
                                 "html_url": f"https://github.com/{self.repo}/releases/tag/{tag}", "assets": assets})
        if gate:
            self.set_gate(sha, gate)
        return tag

    # --- the client interface ------------------------------------------------------------------
    def get_json(self, path):
        self.calls.append(path)
        repo = re.escape(self.repo)
        if re.fullmatch(rf"repos/{repo}/releases(\?.*)?", path):
            return list(self.releases)
        m = re.fullmatch(rf"repos/{repo}/releases/tags/(.+)", path)
        if m:
            for r in self.releases:
                if r["tag_name"] == m.group(1):
                    return r
            raise il_deploy.GitHubError("Not Found", status=404)
        m = re.fullmatch(rf"repos/{repo}/git/ref/heads/(.+)", path)
        if m:
            if m.group(1) != self.branch or not self.head:
                raise il_deploy.GitHubError("Not Found", status=404)
            return {"ref": "refs/heads/" + self.branch, "object": {"sha": self.head, "type": "commit"}}
        m = re.fullmatch(rf"repos/{repo}/compare/([0-9a-f]{{40}})\.\.\.([0-9a-f]{{40}})", path)
        if m:
            base, head = m.groups()
            if base not in self.parents or head not in self.parents:
                raise il_deploy.GitHubError("Not Found", status=404)
            return {"status": self.compare(base, head), "base_commit": {"sha": base}}
        m = re.fullmatch(rf"repos/{repo}/actions/runs/(\d+)", path)
        if m:
            if int(m.group(1)) not in self.runs:
                raise il_deploy.GitHubError("Not Found", status=404)
            return dict(self.runs[int(m.group(1))])
        m = re.fullmatch(rf"repos/{repo}/commits/([0-9a-f]{{40}})/check-runs\?(.*)", path)
        if m:
            sha, query = m.groups()
            runs = list(self.checks.get(sha, []))
            q = dict(kv.split("=", 1) for kv in query.split("&") if "=" in kv)
            if "check_name" in q:
                runs = [r for r in runs if r["name"] == q["check_name"]]
            if q.get("filter", "latest") == "latest":  # GitHub: the newest per check suite and name
                newest = {}
                for r in runs:
                    k = (r["check_suite"]["id"], r["name"])
                    if k not in newest or r["id"] > newest[k]["id"]:
                        newest[k] = r
                runs = sorted(newest.values(), key=lambda r: -r["id"])
            return {"total_count": len(runs), "check_runs": runs}
        raise il_deploy.GitHubError("Not Found: " + path, status=404)

    def download(self, asset, dest):
        self.downloads.append(asset["name"])
        with open(dest, "wb") as f:
            f.write(self.assets[asset["browser_download_url"]])


GH_HELP_FLAGS = ("--bundle", "--deny-self-hosted-runners", "--format", "--owner", "--repo", "--signer-workflow",
                 "--source-digest", "--source-ref")


def attestation_entry(rec, digest, name="artifact"):
    """One entry of `gh attestation verify --format json` for an attestation FakeGitHub stores, in the real shape
    (tests/gh-attestation-verify-cli-2.97.0.json is a real one)."""
    gh = "https://github.com/"
    signer = f"{gh}{rec['repo']}/{rec['workflow']}@{rec['ref']}"
    run = f"{gh}{rec['repo']}/actions/runs/{rec['run_id']}/attempts/{rec['attempt']}"
    return {"attestation": {"bundle": "(a Sigstore bundle)"}, "verificationResult": {
        "mediaType": "application/vnd.dev.sigstore.verificationresult+json;version=0.1",
        "signature": {"certificate": {
            "certificateIssuer": "CN=sigstore-intermediate,O=sigstore.dev", "subjectAlternativeName": signer,
            "issuer": "https://token.actions.githubusercontent.com", "githubWorkflowTrigger": rec["event"],
            "githubWorkflowSHA": rec["sha"], "githubWorkflowRepository": rec["repo"], "githubWorkflowRef": rec["ref"],
            "buildSignerURI": signer, "buildSignerDigest": rec["sha"], "runnerEnvironment": rec["runner"],
            "sourceRepositoryURI": gh + rec["repo"], "sourceRepositoryDigest": rec["sha"],
            "sourceRepositoryRef": rec["ref"], "buildConfigURI": signer, "buildTrigger": rec["event"],
            "runInvocationURI": run}},
        "verifiedTimestamps": [{"type": "Tlog", "uri": "https://rekor.sigstore.dev",
                                "timestamp": "2026-10-31T12:00:00Z"}],
        "statement": {"_type": "https://in-toto.io/Statement/v1",
                      "subject": [{"name": name, "digest": {"sha256": digest}}],
                      "predicateType": "https://slsa.dev/provenance/v1",
                      "predicate": {"buildDefinition": {"externalParameters": {"workflow": {
                                        "path": rec["workflow"], "ref": rec["ref"], "repository": gh + rec["repo"]}}},
                                    "runDetails": {"builder": {"id": signer},
                                                   "metadata": {"invocationId": rec["invocation"] or run}}}}}}


class FakeGhCli:
    """`gh` as il_deploy.GhAttestation runs it (subprocess.run's signature), against FakeGitHub's attestations:
    `attestation verify <file> --repo R [policy flags] --format json` finds the attestations of the file's sha256 in R
    and keeps those that pass the policy flags (--signer-workflow, --source-ref, --source-digest,
    --deny-self-hosted-runners), with gh's exit codes and messages; `attestation verify --help`; `auth status`.
    installed=False (no gh), attestation_command=False (a gh before 2.49), flags (what --help lists: an older gh
    lacks some), logged_in=False, offline=True (no network) and stdout (what verify prints) make the failures."""

    def __init__(self, github, installed=True, attestation_command=True, flags=GH_HELP_FLAGS, logged_in=True,
                 offline=False, stdout=None):
        self.github = github
        self.installed, self.attestation_command, self.flags = installed, attestation_command, flags
        self.logged_in, self.offline, self.stdout = logged_in, offline, stdout
        self.calls = []
        self.verified = []  # (file, its sha256) of each `attestation verify`

    @staticmethod
    def done(argv, rc, out="", err=""):
        return subprocess.CompletedProcess(argv, rc, out, err)

    def __call__(self, argv, capture_output=False, text=False, timeout=None, **kw):
        assert capture_output and text and timeout, "GhAttestation captures gh's output as text, with a timeout"
        self.calls.append(list(argv))
        if not self.installed:
            raise FileNotFoundError(2, "No such file or directory", argv[0])
        args = argv[1:]
        if args[:2] == ["auth", "status"]:
            if self.logged_in:
                return self.done(argv, 0, "github.com\n  ✓ Logged in to github.com account il-lab (keyring)\n")
            return self.done(argv, 1, "", "You are not logged into any GitHub hosts. To log in, run: gh auth login\n")
        if args[:1] == ["attestation"] and not self.attestation_command:
            return self.done(argv, 1, "", f'unknown command "{args[0]}" for "gh"\n')
        if args == ["attestation", "verify", "--help"]:
            return self.done(argv, 0, "USAGE\n  gh attestation verify [<file-path>] [flags]\n\nFLAGS\n"
                             + "".join(f"      {f} string\n" for f in self.flags))
        if args[:2] == ["attestation", "verify"]:
            return self.verify(argv, args[2:])
        return self.done(argv, 1, "", f"unknown command {args!r}\n")

    def verify(self, argv, args):
        path, opts, i = args[0], {}, 1
        while i < len(args):
            flag = args[i]
            if flag not in self.flags:
                return self.done(argv, 1, "", f"unknown flag: {flag}\n")
            if flag == "--deny-self-hosted-runners":
                opts[flag], i = True, i + 1
            else:
                opts[flag], i = args[i + 1], i + 2
        if not self.logged_in:
            return self.done(argv, 4, "", "To get started with GitHub CLI, please run:  gh auth login\n")
        if self.offline:
            return self.done(argv, 1, "", "error creating Sigstore verifier: no valid Sigstore verifiers could be "
                                          "initialized\n")
        digest = il_deploy.sha256_file(path)
        self.verified.append((path, digest))
        found = [r for r in self.github.attestations.get(digest, []) if r["repo"] == opts["--repo"]]
        if not found:
            return self.done(argv, 1, "", "Error: no attestations found\n")
        checks = [("--signer-workflow", lambda r, v: f"{r['repo']}/{r['workflow']}" == v, "verifying with issuer "
                   '"sigstore.dev"'),
                  ("--source-ref", lambda r, v: r["ref"] == v, "expected SourceRepositoryRef to be {v}, got {got}"),
                  ("--source-digest", lambda r, v: r["sha"] == v,
                   "expected SourceRepositoryDigest to be {v}, got {got}"),
                  ("--deny-self-hosted-runners", lambda r, v: r["runner"] == "github-hosted",
                   "expected RunnerEnvironment to be github-hosted, got {got}")]
        for flag, ok, msg in checks:
            if flag in opts:
                kept = [r for r in found if ok(r, opts[flag])]
                if not kept:
                    got = found[0][{"--source-ref": "ref", "--source-digest": "sha"}.get(flag, "runner")]
                    return self.done(argv, 1, "", "\nError: " + msg.format(v=opts[flag], got=got) + "\n")
                found = kept
        out = self.stdout if self.stdout is not None else json.dumps(
            [attestation_entry(r, digest, os.path.basename(path)) for r in found])
        return self.done(argv, 0, out)


class FakeProcs(il_deploy.Procs):
    """Real /proc scans for files (lock, device), but no host OpenPnP processes unless given."""

    def __init__(self, openpnp=()):
        self.openpnp = list(openpnp)

    def openpnp_processes(self):
        return list(self.openpnp)


class FakeClock(il_deploy.Clock):
    def __init__(self, t=1793500000.0):
        self.t = t

    def now(self):
        return self.t

    def monotonic(self):
        return self.t

    def sleep(self, s):
        self.t += s


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


STATE_MD = """# STATE — test

## Machine
- **Holder:** nobody (released by Process at 18:00)
- **Air:** unknown. Read the gauge.

## Code
- **Integration branch: `il/integration`** on theinstancelabs/openpnp.
- **Deployed on the machine:** a scratch build (`realjar-sw8`),
  copied by hand.

## Owners
| What | Owner |
"""


class Lab:
    """A throwaway lab: deploy root, seed config, STATE.md, lock, device, activity file, fake GitHub."""

    def __init__(self, quiet_min=15):
        self.dir = tempfile.mkdtemp(prefix="il-deploy-test-")
        self.root = os.path.join(self.dir, "deploy")
        self.state_md = os.path.join(self.dir, "STATE.md")
        self.lock = os.path.join(self.dir, "lumen-serial.lock")
        self.device = os.path.join(self.dir, "ttyACM-fake")
        self.activity = os.path.join(self.dir, "serial.log")
        self.seed = os.path.join(self.dir, "seed-config")
        self.port = free_port()
        self.quiet_min = quiet_min
        self.gh = FakeGitHub()
        self.gh_cli = FakeGhCli(self.gh)  # the `gh` il_deploy verifies attestations with
        self.requests_path = os.path.join(self.dir, "fake-requests.jsonl")
        self.procs = FakeProcs()
        with open(self.state_md, "w") as f:
            f.write(STATE_MD)
        open(self.device, "w").close()
        with open(self.activity, "w") as f:
            f.write("old bench line\n")
        old = time.time() - 3600
        os.utime(self.activity, (old, old))
        os.makedirs(os.path.join(self.seed, "instancelabs"))
        with open(os.path.join(self.seed, "machine.xml"), "w") as f:
            f.write('<openpnp-machine><machine><drivers><driver communications="tcp"/></drivers></machine>'
                    '</openpnp-machine>\n')
        with open(os.path.join(self.seed, "instancelabs", "dispense-config.json"), "w") as f:
            json.dump({"schema": 3}, f)
        for ext, data in (("", b"SQLite format 3\0main"), ("-wal", b"wal-frames"), ("-shm", b"shm-index")):
            with open(os.path.join(self.seed, "instancelabs", "dispense.db" + ext), "wb") as f:
                f.write(data)
        # commit graph: a <- b <- c is il/integration
        self.a = self.gh.commit("a")
        self.b = self.gh.commit("b", self.a)
        self.c = self.gh.commit("c", self.b)
        self.gh.head = self.c

    @property
    def config(self):
        return os.path.join(self.root, "state", "config")

    def openpnp_cmd(self):
        return (f"/usr/bin/flock -n -E 75 {{lock}} {sys.executable} {FAKE_OPENPNP} "
                f"--port {{port}} --config-dir {{config}} --requests {self.requests_path}")

    def argv(self, *cmd, extra=()):
        return list(cmd) + [
            "--root", self.root, "--state-md", self.state_md, "--lock", self.lock, "--device", self.device,
            "--activity", self.activity, "--quiet-min", str(self.quiet_min), "--api-port", str(self.port),
            "--usb-sn", "387932683335", "--service-manager", "subprocess", "--openpnp-cmd", self.openpnp_cmd(),
            "--status-timeout", "15", "--enable-timeout", "5", "--home-timeout", "5", "--home-busy-wait", "5",
            "--stop-timeout", "5", "--poll-interval", "0.05", "--serial-wait", "1",
        ] + list(extra)

    def ci_release(self, run_number, sha, jar=None):
        """A release exactly as CI's release job makes it (infra/ci/make_release.py), into a fresh directory; the jar
        is the fake OpenPnP's JSON (default {"sha": sha})."""
        d = tempfile.mkdtemp(prefix=f"ci-release-{run_number}-", dir=self.dir)
        jar_path, lib = os.path.join(d, "openpnp-gui-0.0.1-alpha-SNAPSHOT.jar"), os.path.join(d, "lib")
        os.makedirs(lib)
        with open(jar_path, "w") as f:
            json.dump(jar if jar is not None else {"sha": sha}, f)
        with open(os.path.join(lib, "dep-1.0.jar"), "wb") as f:
            f.write(b"dependency")
        out = os.path.join(d, "rel")
        make_release.make(jar_path, lib, f"il-build-{run_number}-{sha[:7]}", sha, BRANCH, 1000 + run_number,
                          run_number, f"https://github.com/{REPO}/actions/runs/{1000 + run_number}", out)
        return out

    def deps(self, **kw):
        kw.setdefault("github", self.gh)
        kw.setdefault("procs", self.procs)
        kw.setdefault("attestation", il_deploy.GhAttestation(gh="gh", runner=self.gh_cli))
        return il_deploy.Deps(**kw)

    def main(self, *cmd, deps=None, extra=()):
        """Run the CLI in-process; extra flags go last, so they override the lab's."""
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            rc = il_deploy.main(self.argv(*cmd, extra=extra), deps=deps or self.deps())
        self.last_out, self.last_err = out.getvalue(), err.getvalue()
        return rc

    def machine(self, **kw):
        os.makedirs(self.config, exist_ok=True)
        with open(os.path.join(self.config, "fake-machine.json"), "w") as f:
            json.dump(kw, f)

    def requests(self):
        if not os.path.exists(self.requests_path):
            return []
        with open(self.requests_path) as f:
            return [json.loads(line) for line in f if line.strip()]

    def holder(self):
        with open(self.state_md) as f:
            return il_deploy.parse_holder(f.read())

    def state_text(self):
        with open(self.state_md) as f:
            return f.read()

    def service(self):
        cfg = il_deploy.config_from_args(self.argv("status"))
        return il_deploy.make_service(cfg)

    def start_service(self, timeout=15):
        """Start OpenPnP (the fake) as a person would, an hour ago: wait until its API answers (a deploy over an OpenPnP
        that is still starting is 'not idle', fail closed), then date its OpenPnP.log back an hour (a start is activity:
        the quiet period runs from OpenPnP's last log line). Neither is what these tests are about."""
        svc = self.service()
        svc.start()
        self.wait_api(timeout)
        self.age_openpnp_log()
        return svc

    def wait_api(self, timeout=15):
        """Until the (fake) OpenPnP's API answers: a check against one still starting is 'not idle' (fail closed)."""
        api, end = il_deploy.Api(self.port), time.monotonic() + timeout
        while True:
            try:
                api.get("/api/status", timeout=2)
                return
            except il_deploy.ApiDown:
                if time.monotonic() > end:
                    raise
                time.sleep(0.02)

    def age_openpnp_log(self, seconds=3600):
        log = os.path.join(self.config, "log", "OpenPnP.log")
        if os.path.exists(log):
            old = time.time() - seconds
            os.utime(log, (old, old))

    def kill_service(self):
        """SIGKILL OpenPnP's whole process group, as the OOM killer or a JVM crash ends it: no stop, no STOP."""
        rec = il_deploy.read_json(os.path.join(self.root, "run", "il-openpnp.json"))
        os.killpg(rec["pid"], signal.SIGKILL)
        svc, end = self.service(), time.monotonic() + 10
        while svc.is_active() and time.monotonic() < end:
            time.sleep(0.02)
        assert not svc.is_active()

    def link(self, name):
        p = os.path.join(self.root, name)
        return os.path.basename(os.readlink(p)) if os.path.islink(p) else None

    def journal(self):
        path = os.path.join(self.root, "log", "deploy.jsonl")
        with open(path) as f:
            return [json.loads(line) for line in f if line.strip()]

    def schema(self):
        with open(os.path.join(self.config, "instancelabs", "dispense-config.json")) as f:
            return json.load(f).get("schema")

    def cleanup(self):
        try:
            if os.path.isdir(self.root):
                svc = self.service()
                if svc.is_active():
                    svc.stop()
        finally:
            shutil.rmtree(self.dir, ignore_errors=True)
