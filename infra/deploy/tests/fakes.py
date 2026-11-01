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
import socket
import sys
import tarfile
import tempfile
import time

import il_deploy

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
        self.calls = []
        self.downloads = []

    # --- commit graph --------------------------------------------------------------------------
    def commit(self, name, *parents):
        sha = sha_of(name)
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

    def set_gate(self, sha, conclusion="success", status="completed", name="gate"):
        self.checks.setdefault(sha, []).append(
            {"id": len(self.checks.get(sha, [])) + 1, "name": name, "status": status, "conclusion": conclusion})

    # --- releases ------------------------------------------------------------------------------
    def add_release(self, run_number, sha, branch=BRANCH, jar=None, corrupt_sha256=False, gate="success",
                    info_overrides=None, target=None, members=None, omit=()):
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
        assets = []
        for i, (name, data) in enumerate([(tar_name, tar_bytes),
                                          (tar_name + ".sha256", f"{digest}  {tar_name}\n".encode()),
                                          ("BUILD-INFO.json", info_bytes)]):
            if name in omit:
                continue
            aid = run_number * 10 + i
            url = f"https://github.com/{self.repo}/releases/download/{tag}/{name}"
            self.assets[url] = data
            assets.append({"id": aid, "name": name, "size": len(data), "browser_download_url": url,
                           "url": f"{API}repos/{self.repo}/releases/assets/{aid}"})
        self.releases.insert(0, {"id": run_number, "tag_name": tag, "target_commitish": target or sha,
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
        m = re.fullmatch(rf"repos/{repo}/commits/([0-9a-f]{{40}})/check-runs\?(.*)", path)
        if m:
            sha, query = m.groups()
            runs = list(self.checks.get(sha, []))
            q = dict(kv.split("=", 1) for kv in query.split("&") if "=" in kv)
            if "check_name" in q:
                runs = [r for r in runs if r["name"] == q["check_name"]]
            if q.get("filter", "latest") == "latest" and runs:
                runs = [max(runs, key=lambda r: r["id"])]
            return {"total_count": len(runs), "check_runs": runs}
        raise il_deploy.GitHubError("Not Found: " + path, status=404)

    def download(self, asset, dest):
        self.downloads.append(asset["name"])
        with open(dest, "wb") as f:
            f.write(self.assets[asset["browser_download_url"]])


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

    def deps(self, **kw):
        kw.setdefault("github", self.gh)
        kw.setdefault("procs", self.procs)
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
