#!/usr/bin/env python3
"""Automatic deploy of green il/integration builds of the OpenPnP fork to the lab computer (INF-02).

CI publishes each green il/integration build as a GitHub prerelease 'il-build-<run>-<sha7>' (tarball,
.sha256, BUILD-INFO.json; the tarball attested by the run). This tool stages the newest eligible one (gh verifies
the attestation), deploys it only when the machine
is idle, health-checks it (API up, version, connect, controller state, home, stop), rolls back
automatically on failure, and keeps STATE.md's Holder and "Deployed on the machine" lines current.

Layout under --root (default ~/instancelabs/deploy):
  releases/<tag>/          openpnp-il.jar, lib/, VERSION.txt, BUILD-INFO.json, STAGED.json, and
                           openpnp-il-<sha7>.tar.gz (the attested tarball; the deploy verifies it again)
  current, previous        symlinks to releases/<tag> (switched atomically)
  state/config/            the one live OpenPnP config (seeded once by init)
  snapshots/<ts>-pre-<tag>/, <ts>-abandoned-<tag>/   config copies around each switch
  prefs/                   Java preferences of the service
  log/deploy.jsonl         every step; deployed.json, bad.json (failed builds), hold.json (a person
                           must check the machine; blocks deploys until 'resume')
  claim.json               the Holder claim while it stands (a killed il_deploy's is turned into a hold)
  switch.json              a config restore and link switch under way (whatever cuts it, the next command finishes it)
  person.json              the last resume or health by hand (the timer waits the quiet period after it)
  own-activity.json        when il_deploy alone drove OpenPnP (what OpenPnP stamped or logged then is not activity)
  openpnp-exit.json        the last OpenPnP exit il_deploy did not cause that it has seen (one hold per exit)

Usage (common flags go after the subcommand):
  il_deploy.py poll                         stage the newest eligible release
  il_deploy.py deploy [--version TAG]       deploy the newest staged, not-bad release when idle;
                                            --version picks one and overrides the bad list
  il_deploy.py rollback [--discard-config-changes]
                                            one step back to previous, with its pre-deploy config
                                            snapshot; refused if the config changed since the deploy
  il_deploy.py run                          poll, then deploy if newer (what the timer calls)
  il_deploy.py keep-up                      start OpenPnP (never enabled or homed) if it is down and the machine is
                                            free: the real machine's web UI is up whenever nobody holds the machine
  il_deploy.py health [--sha7 X] [--no-claim]  the health check alone; JSON report
  il_deploy.py status                       versions, last deploy, idle verdict (JSON)
  il_deploy.py init --seed-from DIR         create state/config once from an existing config
  il_deploy.py units --out DIR [--script P] render the systemd user units (never installs them)
  il_deploy.py resume                       a person checked the machine: clear the hold, hand back the Holder
  il_deploy.py stop-openpnp                 POST /api/stop unless homing, wait until the machine, the engine and
                                            the pressure are off (the ExecStop; exit 1 if not)
  il_deploy.py check-device                 exit 1 if a process has the serial device open
  il_deploy.py mark-start                   the unit's second ExecStartPre: refuse if the serial lock is taken, else
                                            write the run marker (cleared only by stop-openpnp's confirmed STOP)
  il_deploy.py wait-display                 wait until Xvfb listens on --display (the Xvfb unit's ExecStartPost)
Common flags: --root --state-md --lock --device --activity FILE (repeatable) --quiet-min --api-port
  --trusted-origin URL (repeatable: OpenPnP's OPENPNP_IL_API_TRUSTED_ORIGINS, checked by the health check)
  --usb-sn --serial-match --expect-simulated --repo --branch --service --service-manager systemd|subprocess
  --openpnp-cmd CMD ({python} {root} {config} {port} {lock} {prefs} placeholders; subprocess only)
Exit codes: 0 done or nothing to do; 1 error; 2 refused; 3 not idle; 4 deploy failed and rolled back;
  5 failed and a person must check (hold set; the Holder stays 'Infra (...): a person must check'), or OpenPnP
  ended without il_deploy stopping it (hold set).
"""
import argparse
import contextlib
import datetime
import fcntl
import hashlib
import http.client
import json
import os
import re
import shlex
import shutil
import signal
import sqlite3
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from collections import namedtuple

HOME = os.path.expanduser("~")
DEFAULT_ROOT = os.path.join(HOME, "instancelabs", "deploy")
DEFAULT_STATE_MD = "/home/lumen/.t3/projects/openpnp-revamp/STATE.md"
DEFAULT_LOCK = "/tmp/lumen-serial.lock"
DEFAULT_DEVICE = ("/dev/serial/by-id/usb-STMicroelectronics_MARLIN_OPULO_LUMEN_REV5_CDC_in_FS_Mode_"
                  "387932683335-if00")
DEFAULT_ACTIVITY = [os.path.join(HOME, "instancelabs", "runs", "serial.log")]
DEFAULT_USB_SN = "387932683335"
DEFAULT_PORT = 18877
REPO = "theinstancelabs/openpnp"
BRANCH = "il/integration"
UNIT = "il-openpnp.service"
LOCK_CONFLICT = 75  # flock -E: the serial lock was taken by someone else when OpenPnP started
# The unit's ExecStop: a whole home, then the STOP and its confirm (machine off, engine idle, valve and pump off). The
# engine's own abort lines go out only after the move in progress, which the driver may wait on up to its longest
# timeout (60 s in the lab machine.xml); time the home wait did not use goes to the confirm too (stop_sequence).
UNIT_HOME_WAIT, UNIT_STOP_TIMEOUT = 300, 90
OPENPNP_STOP_S = UNIT_HOME_WAIT + UNIT_STOP_TIMEOUT + 30  # il-openpnp TimeoutStopSec: both, and the requests' timeouts
DEPLOY_STOP_S = 600  # il-deploy.service TimeoutStopSec: its SIGTERM handler waits out a home, STOPs, hands back
# At shutdown the user manager stops il-deploy, then il-openpnp (After=): its own stop budget must cover both. Ubuntu's
# /usr/lib/systemd/system/user@.service.d/timeout.conf sets 5 s; the system drop-in `units` renders raises it.
USER_MANAGER_STOP_S = DEPLOY_STOP_S + OPENPNP_STOP_S + 60
USER_MANAGER_DROP_IN = "zz-il-deploy-stop-timeout.conf"  # sorts after timeout.conf: drop-ins apply by file name
# GitHub compare <branch head>...<build sha>: the build is the head ("identical") or an ancestor ("behind")
ELIGIBLE_COMPARE = ("identical", "behind")
# CI's release job publishes with github.token: the release's author, and the run BUILD-INFO.run_id names
RELEASE_AUTHOR = "github-actions[bot]"
CI_WORKFLOW = ".github/workflows/ci.yml"
TAG_RE = re.compile(r"^il-build-(\d+)-([0-9a-f]{7})$")
JAVA_OPENS = ["--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
              "--add-opens=java.desktop/java.awt.color=ALL-UNNAMED"]
PAD_CAL_DONE = ("done", "stopped", "cancelled", "failed")
# MachineEndpoints.home's 409 after its own f.get(5, MINUTES): the home may still run (or OpenPnP dropped it)
HOME_GAVE_UP = "the home did not start or did not end within"
# A home that failed on a timeout of OpenPnP's own (GcodeDriver.sendCommand: "<driver>:<port> timeout waiting for
# response to G28 ..."; G28 goes out with infinity-timeout-milliseconds, 60 s in the lab machine.xml): the controller
# may still be in G28, where a STOP's M410 kills the board
HOME_TIMED_OUT = re.compile(r"\btime[ds]?[ -]?out\b", re.I)
# BR-44: server.startedAtMs is the JVM's start. The pressure hold's first value ('pressure off') is stamped while OpenPnP
# starts, before its API answers; il_deploy calls a start whose API and engine are not up within --status-timeout (120 s)
# failed. A person's pressure event can fall in this window too, so it only narrows the text (pressure_start_up_stamp)
STARTUP_STAMP_S = 120


class Refused(Exception):
    def __init__(self, msg, permanent=False):
        super().__init__(msg)
        self.permanent = permanent


class NotIdle(Exception):
    pass


class StateFileConflict(Exception):
    pass


class ServiceError(Exception):
    pass


class GitHubError(Exception):
    def __init__(self, msg, status=None):
        super().__init__(msg)
        self.status = status


class ApiDown(Exception):
    pass


class ApiTimeout(ApiDown):
    pass


class Terminated(BaseException):
    pass


# ------------------------------------------------------------------------------------------------
# STATE.md: '- **Label:** value' bullets, continued on lines indented deeper than the bullet
# ------------------------------------------------------------------------------------------------
Holder = namedtuple("Holder", "value idle reason")
_NOBODY = re.compile(r"(?i)^nobody(?![\w?!-])")
_WANTS = re.compile(r"(?i)\bwants?\b")


def _indent(s):
    s = s.expandtabs(4)
    return len(s) - len(s.lstrip(" "))


def find_entries(lines, label):
    """[(start, end, match)] for each '- **label:** ...' bullet; end is exclusive and covers its
    continuation: the following non-blank lines indented deeper than the bullet."""
    rx = re.compile(r"^(?P<indent>[ \t]*)-[ \t]+\*\*" + re.escape(label) + r":\*\*[ \t]*(?P<value>.*?)[ \t]*$")
    out = []
    for i, line in enumerate(lines):
        m = rx.match(line.rstrip("\r\n"))
        if not m:
            continue
        ind = _indent(m.group("indent"))
        j = i + 1
        while j < len(lines) and lines[j].strip() and _indent(lines[j]) > ind:
            j += 1
        out.append((i, j, m))
    return out


def _value_end(lines, i, j):
    """End of the value of the entry lines[i:j]: its first line plus wrapped lines up to the first sub-bullet.
    lines[end:j] are notes under it."""
    k = i + 1
    while k < j and not re.match(r"^[-*+][ \t]", lines[k].strip()):
        k += 1
    return k


def parse_holder(text):
    """Fail closed: idle only with exactly one Holder bullet whose value starts with 'nobody', no one waiting
    ('wants') and no notes (sub-bullets) under it: a note is for the next taker to read first."""
    lines = text.splitlines()
    found = find_entries(lines, "Holder")
    if len(found) != 1:
        return Holder(None, False, "STATE.md has %s '- **Holder:**' line" % ("no" if not found else
                                                                             f"{len(found)} a"))
    i, j, m = found[0]
    k = _value_end(lines, i, j)
    value = " ".join(p for p in [m.group("value")] + [l.strip() for l in lines[i + 1:k]] if p)
    notes = " ".join(l.strip() for l in lines[k:j])
    if not _NOBODY.match(value):
        return Holder(value, False, f"STATE.md Holder: {value or '(empty)'}")
    entry = " ".join(p for p in (value, notes) if p)
    if _WANTS.search(entry):
        return Holder(value, False, f"STATE.md Holder is nobody but someone is waiting: {entry}")
    if notes:
        return Holder(value, False, f"STATE.md Holder is nobody but has notes under it (read them first): {notes}")
    return Holder(value, True, "STATE.md Holder: " + value)


def _newline(line, text):
    if line.endswith("\r\n"):
        return "\r\n"
    if line.endswith("\n"):
        return "\n"
    return ""


def _replace_at(lines, found, label, value, text):
    i, j, m = found
    new = f"{m.group('indent')}- **{label}:** {value}{_newline(lines[i], text)}"
    return "".join(lines[:i] + [new] + lines[j:])


def replace_entry(text, label, value):
    lines = text.splitlines(keepends=True)
    found = find_entries(lines, label)
    if len(found) != 1:
        raise ValueError(f"expected one '- **{label}:**' line in STATE.md, found {len(found)}")
    return _replace_at(lines, found[0], label, value, text)


def set_deployed_line(text, value):
    """Replace the value of the '## Code' bullet '- **Deployed on the machine:**' (its wrapped lines too; the notes
    under it, sub-bullets another role left, stay), or add it at the end of that section (or a new section)."""
    label = "Deployed on the machine"
    lines = text.splitlines(keepends=True)
    found = find_entries(lines, label)
    if found:
        i, j, m = found[0]
        return _replace_at(lines, (i, _value_end(lines, i, j), m), label, value, text)
    nl = "\r\n" if "\r\n" in text else "\n"
    new = f"- **{label}:** {value}{nl}"
    code = next((k for k, l in enumerate(lines) if re.match(r"^##[ \t]+Code\b", l)), None)
    if code is None:
        body = text if not text or text.endswith("\n") else text + nl
        sep = "" if not body or body.endswith(nl + nl) else nl
        return body + sep + f"## Code{nl}" + new
    end = next((k for k in range(code + 1, len(lines)) if re.match(r"^#{1,2}[ \t]", lines[k])), len(lines))
    last = end - 1
    while last > code and not lines[last].strip():
        last -= 1
    if not lines[last].endswith("\n"):
        lines[last] += nl
    return "".join(lines[:last + 1] + [new] + lines[last + 1:])


def edit_state_file(path, fn, before_rename=None):
    """Read, transform with fn, write a temp file next to it and rename it over the original; abort with
    StateFileConflict if the file changed between the read and the rename."""
    st = os.stat(path)
    with open(path, encoding="utf-8", newline="") as f:
        text = f.read()
    new = fn(text)
    if new == text:
        return text
    fd, tmp = tempfile.mkstemp(prefix="." + os.path.basename(path) + ".", suffix=".tmp",
                               dir=os.path.dirname(os.path.abspath(path)))
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
            f.write(new)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, st.st_mode & 0o7777)
        if before_rename:
            before_rename()
        st2 = os.stat(path)
        with open(path, encoding="utf-8", newline="") as f:
            now = f.read()
        if (st2.st_ino, st2.st_size, st2.st_mtime_ns) != (st.st_ino, st.st_size, st.st_mtime_ns) or now != text:
            raise StateFileConflict(f"{path} changed while it was being edited")
        os.replace(tmp, path)
        tmp = None
    finally:
        if tmp:
            os.unlink(tmp)
    return new


def claim_holder(path, claim, before_rename=None):
    """Write claim as the Holder if it is nobody now. Returns the previous value as it stood in the file (wrapped
    lines included), so it can be put back exactly or carried into the release. NotIdle otherwise."""
    prev = {}

    def fn(text):
        h = parse_holder(text)
        if not h.idle:
            raise NotIdle(h.reason)
        lines = text.splitlines(keepends=True)
        (i, j, m), = find_entries(lines, "Holder")
        k = _value_end(lines, i, j)
        start = m.start("value")
        prev["value"] = "".join([lines[i][start:]] + lines[i + 1:k]).rstrip("\r\n")
        prefix = lines[i][:start] if lines[i][:start].endswith((" ", "\t")) else lines[i][:start] + " "
        return "".join(lines[:i] + [prefix + claim + _newline(lines[k - 1], text)] + lines[k:])

    try:
        edit_state_file(path, fn, before_rename)
    except FileNotFoundError:
        raise NotIdle(f"{path} not found")
    return prev["value"]


def _swap_claim(text, claim, value):
    """Replace our claim at the start of the Holder line with value, keeping anything others added after it
    (a "wants" note on the line or under it). None if the Holder line no longer starts with the claim."""
    lines = text.splitlines(keepends=True)
    found = find_entries(lines, "Holder")
    if len(found) != 1 or not found[0][2].group("value").startswith(claim):
        return None
    i, _, m = found[0]
    start = m.start("value")
    lines[i] = lines[i][:start] + value + lines[i][start + len(claim):]
    return "".join(lines)


def release_holder(path, claim, value, tries=5, before_rename=None):
    """Hand the Holder back (value) if its line still starts with our claim: True. Someone else's Holder is left
    alone: False. Retries on concurrent edits; raises StateFileConflict or OSError if it cannot write."""
    for attempt in range(tries):
        ours = {}

        def fn(text):
            new = _swap_claim(text, claim, value)
            ours["yes"] = new is not None
            return text if new is None else new

        try:
            edit_state_file(path, fn, before_rename)
            return ours["yes"]
        except StateFileConflict:
            if attempt == tries - 1:
                raise
            time.sleep(0.2 * (attempt + 1))


def _safe_note(note):
    """One line, no '?' or 'wants' (they would hold the machine), brackets instead of parentheses (the note sits
    inside the release stamp's)."""
    note = re.sub(r"\s+", " ", str(note)).replace("?", "").replace("(", "[").replace(")", "]")
    return _WANTS.sub("needs", note)[:300]


_INFRA_STAMP = re.compile(r"^nobody \(released by Infra [^()]*\)(?:; earlier: )?")


def released_value(action, hhmm, note=None, prev=None):
    """'nobody (released by Infra <action> at HH:MM[; note])', then what the previous idle Holder said after its
    'nobody', so a claim never loses it (an earlier Infra stamp is replaced, not nested)."""
    base = f"nobody (released by Infra {action} at {hhmm}"
    text = base + (f"; {_safe_note(note)})" if note else ")")
    rest = " ".join((prev or "").split())
    m = _INFRA_STAMP.match(rest)
    rest = rest[m.end():] if m else _NOBODY.sub("", rest, count=1).strip(" ;,.:-")
    if rest:
        text += "; earlier: " + rest
    return text if parse_holder("- **Holder:** " + text).idle else base + ")"


# ------------------------------------------------------------------------------------------------
# GitHub clients: get_json(path) and download(asset, dest). The repo is public, so urllib works
# unauthenticated (60 requests/h); gh is preferred when it is logged in.
# ------------------------------------------------------------------------------------------------
class GhCliClient:
    def __init__(self, gh="gh", runner=subprocess.run):
        self.gh = gh
        self.runner = runner

    def get_json(self, path):
        p = self.runner([self.gh, "api", "-H", "Accept: application/vnd.github+json", path],
                        capture_output=True, text=True, timeout=60)
        if p.returncode != 0:
            m = re.search(r"HTTP (\d{3})", p.stderr or "")
            raise GitHubError(f"gh api {path}: {(p.stderr or '').strip()[:300]}", int(m.group(1)) if m else None)
        try:
            return json.loads(p.stdout)
        except ValueError as e:
            raise GitHubError(f"gh api {path}: bad JSON ({e})")

    def download(self, asset, dest):
        path = asset["url"].split("api.github.com/", 1)[-1]
        with open(dest, "wb") as f:
            p = self.runner([self.gh, "api", "-H", "Accept: application/octet-stream", path], stdout=f,
                            stderr=subprocess.PIPE, timeout=900)
        if p.returncode != 0:
            raise GitHubError(f"download {asset['name']}: {p.stderr.decode(errors='replace').strip()[:300]}")


class UrllibClient:
    API = "https://api.github.com/"

    def __init__(self, token=None):
        self.token = token or os.environ.get("GITHUB_TOKEN")

    def get_json(self, path):
        h = {"Accept": "application/vnd.github+json", "User-Agent": "il-deploy", "X-GitHub-Api-Version": "2022-11-28"}
        if self.token:
            h["Authorization"] = "Bearer " + self.token
        try:
            with urllib.request.urlopen(urllib.request.Request(self.API + path, headers=h), timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            raise GitHubError(f"GET {path}: HTTP {e.code}", e.code)
        except (urllib.error.URLError, OSError, ValueError) as e:
            raise GitHubError(f"GET {path}: {e}")

    def download(self, asset, dest):
        req = urllib.request.Request(asset["browser_download_url"],
                                     headers={"Accept": "application/octet-stream", "User-Agent": "il-deploy"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r, open(dest, "wb") as f:
                shutil.copyfileobj(r, f, 1 << 20)
        except (urllib.error.URLError, OSError) as e:
            raise GitHubError(f"download {asset['name']}: {e}")


def default_github():
    gh = shutil.which("gh")
    if gh and subprocess.run([gh, "auth", "status"], capture_output=True, timeout=30).returncode == 0:
        return GhCliClient(gh)
    return UrllibClient()


# ------------------------------------------------------------------------------------------------
# Build provenance: CI's release job attests the tarball (actions/attest-build-provenance), signed with the run's own
# GitHub OIDC identity; `gh attestation verify` checks the signature and the policy, its JSON names the run.
# ------------------------------------------------------------------------------------------------
ATTESTATION_FLAGS = ("--signer-workflow", "--source-ref", "--source-digest", "--deny-self-hosted-runners")
GITHUB = "https://github.com/"


def _said(p):
    """What a failed gh said, on one line."""
    return " / ".join(l.strip() for l in (p.stderr or "").splitlines() if l.strip())[:300] or f"exit {p.returncode}"


def attestation_problems(entry, repo, branch, sha, run_id, workflow=CI_WORKFLOW):
    """What, in one verified attestation (an entry of `gh attestation verify --format json`), is not CI's release job of
    run run_id: workflow on GitHub's runners, on the branch, at sha ([] = it is). Read from the signing certificate,
    which GitHub's OIDC token fills in (the workflow cannot change it); the provenance's runDetails invocationId must
    name the same run attempt. A missing key raises KeyError or TypeError (an output gh no longer writes)."""
    result = entry["verificationResult"]
    cert = result["signature"]["certificate"]
    want = {"sourceRepositoryURI": GITHUB + repo, "sourceRepositoryRef": f"refs/heads/{branch}",
            "sourceRepositoryDigest": sha, "buildSignerURI": f"{GITHUB}{repo}/{workflow}@refs/heads/{branch}",
            "runnerEnvironment": "github-hosted"}
    out = [f"{k} {cert.get(k)!r}, not {v!r}" for k, v in want.items() if cert.get(k) != v]
    uri = cert["runInvocationURI"]
    m = re.fullmatch(re.escape(f"{GITHUB}{repo}/actions/runs/") + r"(\d+)(/attempts/\d+)?", str(uri))
    if not m or m.group(1) != str(run_id):
        out.append(f"attested by the run {uri}, not BUILD-INFO's run {run_id}")
    invocation = result["statement"]["predicate"]["runDetails"]["metadata"]["invocationId"]
    if invocation != uri:
        out.append(f"the provenance's runDetails invocationId {invocation!r} is not the certificate's run {uri!r}")
    return out


class GhAttestation:
    """verify(tarball, repo, branch, sha, run_id): the tarball's bytes carry an attestation by CI_WORKFLOW's run run_id
    on a GitHub-hosted runner, for the branch at sha (`gh attestation verify` with that policy, then the run in its
    JSON).
    Returns what it accepted, else raises Refused: permanent (the build is never trusted) only for a definite mismatch,
    gh having verified an attestation of these bytes in the repo by another run, workflow, ref, commit or runner. No
    gh, a gh without the command or a policy flag or not logged in, a network error or no attestation: retried.
    ready() checks gh before the tarball is downloaded."""

    def __init__(self, gh=None, runner=subprocess.run, timeout=180):
        self.gh = gh  # None: gh on PATH
        self.runner = runner
        self.timeout = timeout
        self._ready = False

    def _run(self, args):
        gh = self.gh or shutil.which("gh")
        if not gh:
            raise Refused("cannot verify the tarball's attestation: no gh (GitHub CLI) on PATH")
        try:
            return self.runner([gh] + args, capture_output=True, text=True, timeout=self.timeout)
        except FileNotFoundError:
            raise Refused(f"cannot verify the tarball's attestation: no gh at {gh}")
        except (OSError, subprocess.SubprocessError) as e:
            raise Refused(f"cannot verify the tarball's attestation: gh {' '.join(args[:2])}: {e}")

    def ready(self):
        """gh is there, knows every flag of the policy, and is logged in (it fetches attestations from GitHub's API)."""
        if self._ready:
            return
        p = self._run(["attestation", "verify", "--help"])
        if p.returncode != 0:
            raise Refused(f"cannot verify the tarball's attestation: this gh has no `attestation verify` ({_said(p)}): "
                          "update gh")
        missing = [f for f in ATTESTATION_FLAGS if not re.search(rf"(?m)^\s+{re.escape(f)}\b", p.stdout or "")]
        if missing:
            raise Refused(f"cannot verify the tarball's attestation: this gh's `attestation verify` lacks "
                          f"{', '.join(missing)}: update gh")
        p = self._run(["auth", "status", "--active", "--hostname", "github.com"])
        if p.returncode != 0:
            raise Refused(f"cannot verify the tarball's attestation: gh is not logged in ({_said(p)})")
        self._ready = True

    @staticmethod
    def command(path, repo, branch, sha):
        return ["attestation", "verify", path, "--repo", repo, "--signer-workflow", f"{repo}/{CI_WORKFLOW}",
                "--source-ref", f"refs/heads/{branch}", "--source-digest", sha, "--deny-self-hosted-runners",
                "--format", "json"]

    def _judged(self, p, repo, branch, sha, run_id):
        """[(entry, its problems)] of a verify gh passed; Refused (retried) if its JSON is not what gh writes."""
        try:
            entries = json.loads(p.stdout)
            if not isinstance(entries, list) or not entries:
                raise ValueError("no attestation in it")
            return [(e, attestation_problems(e, repo, branch, sha, run_id)) for e in entries]
        except (ValueError, KeyError, TypeError, AttributeError) as e:
            raise Refused(f"cannot read the result of gh attestation verify: {type(e).__name__}: {e}")

    def verify(self, path, repo, branch, sha, run_id):
        self.ready()
        p = self._run(self.command(path, repo, branch, sha))
        if p.returncode == 0:
            judged = self._judged(p, repo, branch, sha, run_id)
            ok = [e for e, problems in judged if not problems]
            if ok:
                return (f"attested by {ok[0]['verificationResult']['signature']['certificate']['runInvocationURI']} "
                        f"({CI_WORKFLOW} on {branch} at {sha[:7]})")
        else:
            # gh refused the policy: definite only if gh verifies an attestation of these bytes in the repo at all
            loose = self._run(["attestation", "verify", path, "--repo", repo, "--format", "json"])
            judged = self._judged(loose, repo, branch, sha, run_id) if loose.returncode == 0 else []
            if not judged or any(not problems for _, problems in judged):
                raise Refused(f"the tarball's attestation does not verify as CI's (gh: {_said(p)})")
        raise Refused("the tarball is attested, but not as CI's build of this release: "
                      + " | ".join("; ".join(problems) for _, problems in judged), True)


def parse_tag(tag):
    m = TAG_RE.match(tag or "")
    return (int(m.group(1)), m.group(2)) if m else None


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ------------------------------------------------------------------------------------------------
# OpenPnP's local web API (loopback; no Origin header; no proxy)
# ------------------------------------------------------------------------------------------------
class Api:
    def __init__(self, port, host="127.0.0.1"):
        self.base = f"http://{host}:{port}"
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, method, path, body=None, timeout=10, headers=None):
        data = None if body is None else json.dumps(body).encode()
        headers = dict({"Content-Type": "application/json"} if data is not None else {}, **(headers or {}))
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with self.opener.open(req, timeout=timeout) as r:
                return r.status, _json(r.read())
        except urllib.error.HTTPError as e:
            return e.code, _json(e.read())
        except TimeoutError as e:
            raise ApiTimeout(f"{method} {path}: no answer within {timeout:g} s") from e
        except (urllib.error.URLError, OSError, http.client.HTTPException) as e:
            if isinstance(getattr(e, "reason", None), TimeoutError):
                raise ApiTimeout(f"{method} {path}: no answer within {timeout:g} s") from e
            raise ApiDown(f"{method} {path}: {getattr(e, 'reason', e)}") from e

    def get(self, path, timeout=10):
        return self.call("GET", path, None, timeout)

    def post(self, path, body=None, timeout=10):
        return self.call("POST", path, {} if body is None else body, timeout)


def _json(raw):
    try:
        v = json.loads(raw or b"{}")
    except ValueError:
        return {"raw": raw[:200].decode(errors="replace")}
    return v if isinstance(v, dict) else {"value": v}


def api_busy_reasons(st):
    """Why OpenPnP's /api/status says the machine is in use (fail closed on a missing machine)."""
    r = []
    m = st.get("machine")
    if not isinstance(m, dict):
        r.append("OpenPnP reports no machine state")
    elif m.get("busy") is not False:
        r.append("OpenPnP's machine is busy")
    elif machine_homing(m):
        r.append("OpenPnP is homing (machine.homing)")
    run = st.get("run")
    if run is not None and (not isinstance(run, dict) or run.get("running") is not False):
        r.append("a run is running (%s)" % (run.get("kind") if isinstance(run, dict) else run))
    p = st.get("pressure")
    if isinstance(p, dict) and (p.get("on") or p.get("state") not in (None, "off")):
        r.append(f"the pressure is on ({p.get('state')})")
    d = st.get("dispense")
    if isinstance(d, dict) and d.get("busy"):
        r.append("the dispense engine is busy")
    pc = st.get("padCalibration")
    if pc is not None and (not isinstance(pc, dict) or pc.get("state") not in PAD_CAL_DONE):
        r.append("a pad calibration is open (%s)" % (pc.get("state") if isinstance(pc, dict) else pc))
    bi = st.get("boardImport")
    if isinstance(bi, dict) and bi.get("reading"):
        r.append("a board import is being read")
    return r


_SPAN_UNITS = {"us": 1e-6, "ms": 1e-3, "s": 1, "min": 60, "h": 3600, "d": 86400, "w": 604800}


def parse_timespan(text):
    """Seconds of a systemd time span ('5s', '17min', '1min 30s', 'infinity'; a bare number is seconds, as in unit
    files), or None if it is not one."""
    t = (text or "").strip()
    if t == "infinity":
        return float("inf")
    if re.fullmatch(r"\d+(?:\.\d+)?", t):
        return float(t)
    parts = re.findall(r"(\d+(?:\.\d+)?)([a-z]+)", t)
    if not parts or re.sub(r"\s+", "", t) != "".join(n + u for n, u in parts) or any(u not in _SPAN_UNITS
                                                                                      for _, u in parts):
        return None
    return sum(float(n) * _SPAN_UNITS[u] for n, u in parts)


def api_enabled_reasons(st):
    """A machine switched on (enabled) is in use: someone may have pressed Start and Home in the web UI and work at
    it by hand, with no run, no pressure and a quiet serial.log (fail closed: not known to be off)."""
    m = st.get("machine")
    if isinstance(m, dict) and m.get("enabled") is not False:
        return ["OpenPnP's machine is switched on (enabled): someone may be working at it (Stop in OpenPnP frees it)"]
    return []


LOCK_REFUSALS = ("locked", "taking-over", "lock-lost", "no-lock")  # BR-72: someone else has the machine lock


def api_lock_reasons(st):
    """BR-72: someone holds the machine lock (an agent role or the user): they are working at it, also when it looks
    quiet. A build from before BR-72 has no lock field."""
    if "lock" not in st:
        return []  # a build from before BR-72
    lock = st["lock"]
    if not isinstance(lock, dict) or "holder" not in lock:
        return [f"OpenPnP reports a machine lock it does not explain ({json.dumps(lock)[:80]}): taken as held"]
    if lock["holder"] is None:
        return []
    h = lock["holder"] if isinstance(lock["holder"], dict) else {}  # anything else: held by someone (fail closed)
    since = _ms(h.get("sinceMs"))
    when = time.strftime("%H:%M", time.localtime(since / 1000)) if since else "?"
    return [f"{h.get('label') or h.get('who') or 'someone'} holds the machine lock "
            f"({h.get('what') or 'no purpose given'}, since {when})"]


def _ms(v):
    return v if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def machine_homing(m):
    """machine.homing of /api/status or /api/machine (BR-44: true from the start of any home, the web's, the engine's or
    OpenPnP's Home button, until it ends, a failure or a STOP included; ReferenceMachine.isHoming), or None from a build
    before it."""
    v = m.get("homing") if isinstance(m, dict) else None
    return v if isinstance(v, bool) else None


def _named(d):
    """[(name, dict)] of a JSON map {name: {...}} or a list of {"name": ...}."""
    if isinstance(d, dict):
        return [(k, v) for k, v in d.items() if isinstance(v, dict)]
    if isinstance(d, list):
        return [(str(v.get("name", "?")), v) for v in d if isinstance(v, dict)]
    return []


# Times in OpenPnP's /api/status that mark activity (api_activity_times). A path's 'key?' may be null (nothing deeper is
# read then). Runs, pad calibrations (RunTracker, Views.run, PadCalibrationJob), the last home (HomeTracker), and hand
# work in the dispense config (DispenseEndpoints.status): needle change, touch-off, landing, XY trim and anchor, the tip
# mounted, a new seat, the purge row wiped (DispenseService.resetPurgeRow), the screw's start angle confirmed at the
# machine (I11), the controller connected (Start pressed; il_deploy's own enables fall in its own windows).
ACTIVITY_FIELDS = (
    ("a run ended", "run?.finishedAtMs"), ("a run ended", "lastRun?.endedAtMs"),
    ("a pad calibration ended", "padCalibration?.endedAtMs"), ("a home", "machine.zCheck.homeAtMs"),
    ("a needle change", "dispense.needle.changedAtMs"), ("a needle touch-off", "dispense.needle.setAtMs"),
    ("a needle landing set", "dispense.needle.landing.setAtMs"),
    ("a needle XY trim set", "dispense.needle.xyTrim?.setAtMs"), ("a needle anchor set", "dispense.needle.anchor?.setAtMs"),
    ("a tip mounted", "dispense.tip.mountedAtMs"), ("a new seat set", "dispense.newSeatAtMs"),
    ("the purge row reset", "dispense.purgeRow.resetAtMs"),
    ("the screw start angle set", "dispense.profile.startAngleSetAtMs"),
    ("the controller connected (Start)", "dispense.profile.controllerConnectedAtMs"))
# {name: {..., "setAtMs"}} maps of places and surfaces
NAMED_ACTIVITY_FIELDS = (("place '{}' set", "dispense.locations"), ("surface '{}' probed", "dispense.surfaces"))
# The state il_deploy reads besides the times (api_busy_reasons, api_enabled_reasons, the pressure event, the health
# check's status step, the STOP rule). BR-44's (server.startedAtMs, machine.homing, dispense.state, startError) are
# always there on a build since; on an older one their rules fall back to the earlier ones (a rollback can land there)
STATE_FIELDS = ("server.version", "server.simulated", "server.startedAtMs", "machine.enabled", "machine.busy",
                "machine.homing", "run?.running", "pressure.state", "pressure.on", "pressure.lastEvent",
                "pressure.lastEventAtMs", "dispense.state", "dispense.startError", "dispense.running",
                "dispense.available", "dispense.busy", "padCalibration?.state", "boardImport.reading")
# Every key path il_deploy reads in /api/status with the dispense engine running. A missing time is skipped (fail open),
# so the e2e checks them against the real build: a renamed field must not silently turn a check off.
STATUS_FIELDS = tuple(p for _, p in ACTIVITY_FIELDS) + tuple(p for _, p in NAMED_ACTIVITY_FIELDS) + STATE_FIELDS


def _at(st, path):
    """(found, value) of a key path ('a.b?.c': b may be null, then (True, None))."""
    v = st
    for key in path.split("."):
        nullable = key.endswith("?")
        key = key.rstrip("?")
        if not isinstance(v, dict) or key not in v:
            return False, None
        v = v[key]
        if v is None and nullable:
            return True, None
    return True, v


def status_field_problems(st):
    """The key paths of STATUS_FIELDS that /api/status lacks (null is fine), in that order, then each place and surface
    without its setAtMs."""
    out = [p.replace("?", "") for p in STATUS_FIELDS if not _at(st, p)[0]]
    for _, path in NAMED_ACTIVITY_FIELDS:
        out += [f"{path}.{n}.setAtMs" for n, v in _named(_at(st, path)[1]) if "setAtMs" not in v]
    return out


def _behind_null(st, path):
    """True if a 'key?' before the path's last key is null in st: the keys after it were not there to check."""
    v = st
    for key in path.split(".")[:-1]:
        if not isinstance(v, dict):
            return False
        v = v.get(key.rstrip("?"))
        if v is None and key.endswith("?"):
            return True
    return False


def unchecked_status_fields(st):
    """The key paths status_field_problems could not check in this /api/status: those behind a null 'key?' (no run, no
    pad calibration, no anchor) and the setAtMs of places and surfaces when there are none. A check against the real
    build must make each of them visible: a path that is never there to check is one a rename turns off silently."""
    out = [p.replace("?", "") for p in STATUS_FIELDS if _behind_null(st, p)]
    return out + [f"{path}.*.setAtMs" for _, path in NAMED_ACTIVITY_FIELDS if not _named(_at(st, path)[1])]


def pressure_start_up_stamp(st):
    """True if the pressure hold's last event is its first value, which PressureHold stamps when the API starts
    (PressureHold.java:77-78), not a person's event: its text, exactly 'pressure off' (every event after it has more
    text), and with server.startedAtMs (BR-44) also its time, within STARTUP_STAMP_S of the JVM's start. Both must hold,
    so neither a changed text nor a person's event early after the start passes as the stamp (either fails closed: the
    quiet period runs from that stamp)."""
    p = st.get("pressure") if isinstance(st.get("pressure"), dict) else {}
    server = st.get("server") if isinstance(st.get("server"), dict) else {}
    if p.get("lastEvent") != "pressure off":
        return False
    started, at = _ms(server.get("startedAtMs")), _ms(p.get("lastEventAtMs"))
    if started is None:
        return True
    return at is not None and started <= at <= started + STARTUP_STAMP_S * 1000


def api_activity_times(st):
    """[(what, epoch ms)] of activity OpenPnP's /api/status shows (ACTIVITY_FIELDS, the places and surfaces set, and
    pressure events: the pressure hold's first value, stamped while OpenPnP starts, is not one:
    pressure_start_up_stamp). Missing or null fields are skipped."""
    out = [(what, _at(st, path)[1]) for what, path in ACTIVITY_FIELDS]
    p = st.get("pressure") if isinstance(st.get("pressure"), dict) else {}
    out.append(("a pressure event", None if pressure_start_up_stamp(st) else p.get("lastEventAtMs")))
    for what, path in NAMED_ACTIVITY_FIELDS:
        out += [(what.format(n), v.get("setAtMs")) for n, v in _named(_at(st, path)[1])]
    return [(what, _ms(ms)) for what, ms in out if _ms(ms) is not None]


# How far from 720 a rotation axis of a reset controller may read: Marlin's M410 (every STOP's DISABLE_COMMAND)
# recomputes the position from the step counts, so after one STOP the boot position reads 720.05 (3197 counts at
# 4.44 steps/deg, lumen-sim RESEARCH.md); a step is 0.23 deg. Wider fails safe (no home, a person checks).
RESET_ROTATION_TOLERANCE = 0.5


def reset_signature(axes):
    """X, Y, Z all 0 and A or B at 720 in the controller's own coordinates: what Marlin reports after a power cycle
    (it boots both rotation axes at 720; BR-02's rule, INF-10), also after a STOP since (720.05: within
    RESET_ROTATION_TOLERANCE). B must be read (the caller checks); A may be absent."""
    c = {}
    for a in axes or []:
        letter = str(a.get("letter") or "").upper()
        v = a.get("driverCoordinate")
        if letter and letter not in c and isinstance(v, (int, float)) and not isinstance(v, bool):
            c[letter] = float(v)
    try:
        return all(abs(c[k]) < 1e-3 for k in "XYZ") and any(abs(c[k] - 720.0) <= RESET_ROTATION_TOLERANCE
                                                             for k in "AB" if k in c)
    except KeyError:
        return False


# ------------------------------------------------------------------------------------------------
# Processes: who holds the lock / the device, which OpenPnPs run
# ------------------------------------------------------------------------------------------------
def _proc_stat(pid):
    """(state, ppid, starttime, pgrp) of a process, or None."""
    try:
        with open(f"/proc/{pid}/stat") as f:
            s = f.read()
    except OSError:
        return None
    rest = s[s.rindex(")") + 2:].split()
    return rest[0], int(rest[1]), rest[19], int(rest[2])


def _threads_live(pid):
    """True if a thread of the process still runs. A process whose main thread exited first shows Z in /proc/<pid>/stat
    while its other threads run on and keep its files open (a JVM can be so while it exits); a real zombie has none."""
    try:
        tids = os.listdir(f"/proc/{pid}/task")
    except OSError:
        return False
    for tid in tids:
        try:
            with open(f"/proc/{pid}/task/{tid}/stat") as f:
                t = f.read()
        except OSError:
            continue
        if t[t.rindex(")") + 2:].split()[0] not in ("Z", "X"):
            return True
    return False


def openpnp_config_dir(cmd, cwd, home):
    """The config dir of an OpenPnP command line (java ... org.openpnp.Main / -jar openpnp-*.jar, or the
    install4j launcher), else None."""
    if not cmd:
        return None
    exe = os.path.basename(cmd[0])
    hit = exe == "OpenPnP"
    if exe.startswith("java"):
        args = cmd[1:]
        hit = "org.openpnp.Main" in args or any(
            a == "-jar" and k + 1 < len(args) and re.search(r"(openpnp-gui-[^/]*|openpnp-il)\.jar$", args[k + 1])
            for k, a in enumerate(args))
    if not hit:
        return None
    for a in cmd:
        if a.startswith("-DconfigDir="):
            return os.path.normpath(os.path.join(cwd, a.split("=", 1)[1]))
    return os.path.join(home, ".openpnp2")


LUMEN_SIM_LINK = "ttyLUMEN"  # the names of lumen-sim's pty links (uinav --controller pty, the deploy e2e)
PTS_RE = re.compile(r"^/dev/pts/\d+$")
USB_IDENTITY = ("usb-serial-number", "usb-description")


def lumen_sim_port(port):
    """True if a serial port-name can open only lumen-sim's pty (uinav's pty_port_problems): an absolute ttyLUMEN* link
    to a /dev/pts/N that exists, and neither /dev/<name> nor /dev/<port> exists (jSerialComm opens those while the link
    is missing). Nothing is opened."""
    if not port or not os.path.isabs(port) or not os.path.basename(port).startswith(LUMEN_SIM_LINK):
        return False
    real = os.path.realpath(port)
    if not PTS_RE.match(real) or not os.path.exists(real):
        return False
    return not any(os.path.lexists(d) for d in ("/dev/" + os.path.basename(port), "/dev/" + port))


def config_is_real(config_dir):
    """True unless machine.xml provably cannot reach the controller (fail closed: unreadable = real). A driver on serial
    counts as real unless its port is lumen-sim's pty (lumen_sim_port) and no USB identity is left in the file (the port
    matcher would look for the board by it)."""
    try:
        root = ET.parse(os.path.join(config_dir, "machine.xml")).getroot()
    except (OSError, ET.ParseError):
        return True
    usb = any(a in el.attrib for el in root.iter() for a in USB_IDENTITY)
    for drv in root.iter("driver"):
        comm, serial = drv.get("communications"), drv.find("serial")
        if comm == "serial" or (comm is None and serial is not None):
            if usb or serial is None or not lumen_sim_port(serial.get("port-name")):
                return True
    return False


class Procs:
    def _pids(self):
        return [int(n) for n in os.listdir("/proc") if n.isdigit()]

    def cmdline(self, pid):
        try:
            with open(f"/proc/{pid}/cmdline", "rb") as f:
                return [a.decode(errors="replace") for a in f.read().split(b"\0") if a]
        except OSError:
            return []

    def describe(self, pids):
        return ", ".join(f"pid {p} ({' '.join(self.cmdline(p))[:80] or '?'})" for p in pids)

    def _fd_targets(self, pid):
        d = f"/proc/{pid}/fd"
        try:
            names = os.listdir(d)
        except OSError:
            return
        for n in names:
            try:
                yield os.readlink(f"{d}/{n}")
            except OSError:
                continue

    def openers(self, path):
        target = os.path.realpath(path)
        return [p for p in self._pids() if any(t == target for t in self._fd_targets(p))]

    def openers_under(self, directory):
        prefix = os.path.realpath(directory).rstrip("/") + "/"
        return [p for p in self._pids() if any(t.startswith(prefix) for t in self._fd_targets(p))]

    def lock_holders(self, path):
        try:
            st = os.stat(path)
            with open("/proc/locks") as f:
                rows = f.read().splitlines()
        except OSError:
            return []
        key = f"{os.major(st.st_dev):02x}:{os.minor(st.st_dev):02x}:{st.st_ino}"
        out = []
        for row in rows:
            parts = row.split()
            # "1: FLOCK  ADVISORY  WRITE 1234 00:1b:5678 0 EOF"; waiters have "->" after the index
            if len(parts) >= 6 and parts[1] == "FLOCK" and parts[5] == key:
                out.append(int(parts[4]))
        return out

    def descendants(self, pid):
        kids = {}
        for p in self._pids():
            st = _proc_stat(p)
            if st:
                kids.setdefault(st[1], []).append(p)
        out, todo = set(), [pid]
        while todo:
            for c in kids.get(todo.pop(), []):
                if c not in out:
                    out.add(c)
                    todo.append(c)
        return out

    def openpnp_processes(self):
        """[(pid, config_dir, real)] for every OpenPnP process on the host."""
        out = []
        for pid in self._pids():
            cmd = self.cmdline(pid)
            if not cmd:
                continue
            try:
                cwd = os.readlink(f"/proc/{pid}/cwd")
            except OSError:
                cwd = "/"
            cfg = openpnp_config_dir(cmd, cwd, HOME)
            if cfg:
                out.append((pid, cfg, config_is_real(cfg)))
        return out


# ------------------------------------------------------------------------------------------------
# Service managers: is_active, start, stop, pids, exit_status
# ------------------------------------------------------------------------------------------------
class SystemdUserService:
    def __init__(self, unit, runner=subprocess.run, cgroup_root="/sys/fs/cgroup"):
        self.unit = unit
        self.runner = runner
        self.cgroup_root = cgroup_root

    def _ctl(self, *args):
        return self.runner(["systemctl", "--user", *args], capture_output=True, text=True, timeout=120)

    def is_active(self):
        return self._ctl("is-active", self.unit).stdout.strip() in ("active", "activating", "deactivating",
                                                                     "reloading")

    def is_down(self):
        """Read as stopped or failed. A read that fails (no user manager to ask, e.g. `su` without XDG_RUNTIME_DIR) is
        not 'down': the run marker never turns into a hold on a unit nobody could read."""
        p = self._ctl("show", "-p", "ActiveState", "--value", self.unit)
        return p.returncode == 0 and p.stdout.strip() in ("inactive", "failed")

    def start(self):
        p = self._ctl("start", self.unit)
        if p.returncode != 0:
            raise ServiceError(f"systemctl --user start {self.unit}: {(p.stderr or '').strip()[:300]}")

    def stop(self):
        p = self._ctl("stop", self.unit)
        if p.returncode != 0:
            raise ServiceError(f"systemctl --user stop {self.unit}: {(p.stderr or '').strip()[:300]}")

    def exit_status(self):
        try:
            return int(self._ctl("show", "-p", "ExecMainStatus", "--value", self.unit).stdout.strip())
        except ValueError:
            return None

    def last_exit(self):
        """The unit's last exit if it failed: {"id", "detail"}, or None. A stop through systemctl (or the unit's own
        ExecStop confirming the STOP) ends 'inactive'; a crash, the OOM killer, an unconfirmed ExecStop or a stop timeout
        end 'failed'. Not one: a start the serial lock refused (75) or ExecStartPre refused (no main process ran)."""
        keys = ("ActiveState", "Result", "ExecMainCode", "ExecMainStatus", "ExecMainPID", "ExecMainExitTimestampMonotonic")
        out = self._ctl("show", *[a for k in keys for a in ("-p", k)], self.unit).stdout
        v = dict(line.split("=", 1) for line in out.splitlines() if "=" in line)
        if v.get("ActiveState") != "failed" or v.get("ExecMainPID", "0") in ("", "0"):
            return None
        if v.get("Result") == "exit-code" and v.get("ExecMainStatus") == str(LOCK_CONFLICT):
            return None
        return {"id": f"{v.get('ExecMainPID')}@{v.get('ExecMainExitTimestampMonotonic')}",
                "detail": f"{self.unit} failed ({v.get('Result')}; main process code {v.get('ExecMainCode')}, status "
                          f"{v.get('ExecMainStatus')})"}

    def pids(self):
        cg = self._ctl("show", "-p", "ControlGroup", "--value", self.unit).stdout.strip()
        out = set()
        if not cg:
            return out
        for d, _, files in os.walk(os.path.join(self.cgroup_root, cg.lstrip("/"))):
            if "cgroup.procs" in files:
                with open(os.path.join(d, "cgroup.procs")) as f:
                    out.update(int(x) for x in f.read().split())
        return out


_CHILDREN = {}  # pid -> Popen, so exit codes can be read and zombies reaped


class SubprocessService:
    """Runs the command in the background in its own session and tracks it through a pid file, so later
    CLI calls find it (for tests and CI dry runs)."""

    def __init__(self, cmd, cwd, env, run_dir, log_dir, name="il-openpnp", pre_stop=None, stop_timeout=30):
        self.cmd, self.cwd, self.env = list(cmd), cwd, env
        self.run_dir, self.log_dir, self.name = run_dir, log_dir, name
        self.pre_stop, self.stop_timeout = pre_stop, stop_timeout
        self.pidfile = os.path.join(run_dir, name + ".json")

    def _record(self):
        try:
            with open(self.pidfile) as f:
                return json.load(f)
        except (OSError, ValueError):
            return None

    def _alive(self, rec):
        pid = rec.get("pid")
        st = _proc_stat(pid)
        if st is None or str(st[2]) != str(rec.get("starttime")):
            return False
        if st[0] in ("Z", "X") and not _threads_live(pid):
            if pid in _CHILDREN:
                _CHILDREN[pid].poll()
            return False
        return True

    @staticmethod
    def _group(rec):
        """Live processes of the service's process group (its own session: pgid = the started pid), started no
        earlier than it. flock dies on SIGTERM at once, the java it runs takes seconds to exit and holds the serial
        lock and the API port until then: the service runs while any of them does, as a unit while its cgroup has
        processes."""
        pgid, born = rec.get("pid"), rec.get("starttime")
        out = set()
        if not isinstance(pgid, int) or born is None:
            return out
        lead = _proc_stat(pgid)
        if lead is not None and str(lead[2]) != str(born):
            return out  # the pid is another process now: a pgid is not reused while its group has members
        for pid in Procs()._pids():
            st = _proc_stat(pid)
            if st and st[3] == pgid and int(st[2]) >= int(born) and (st[0] not in ("Z", "X") or _threads_live(pid)):
                out.add(pid)
        return out

    def _running(self, rec):
        return self._alive(rec) or bool(self._group(rec))

    def is_active(self):
        rec = self._record()
        return bool(rec) and self._running(rec)

    def start(self):
        if self.is_active():
            return
        cwd = os.path.realpath(self.cwd)
        if not os.path.isdir(cwd):
            raise ServiceError(f"working directory {self.cwd} does not exist")
        os.makedirs(self.run_dir, exist_ok=True)
        os.makedirs(self.log_dir, exist_ok=True)
        with open(os.path.join(self.log_dir, self.name + ".out"), "ab") as out:
            p = subprocess.Popen(self.cmd, cwd=cwd, env=self.env, stdin=subprocess.DEVNULL, stdout=out,
                                 stderr=subprocess.STDOUT, start_new_session=True)
        _CHILDREN[p.pid] = p
        st = _proc_stat(p.pid)
        write_json(self.pidfile, {"pid": p.pid, "starttime": st[2] if st else None, "cmd": self.cmd})

    def exit_status(self):
        rec = self._record()
        p = _CHILDREN.get(rec["pid"]) if rec else None
        return p.poll() if p else None

    def last_exit(self):
        """The exit of a run that ended without stop() (its pid file is still there): {"id", "detail"}, or None. Clean
        exits (0, SIGTERM's 143 or -15) and a start the serial lock refused (75) are none, as the unit's."""
        rec = self._record()
        if not rec or self._running(rec):
            return None
        code = self.exit_status()
        if code in (0, 143, -signal.SIGTERM, LOCK_CONFLICT):
            return None
        return {"id": f"{rec.get('pid')}@{rec.get('starttime')}",
                "detail": f"{self.name} exited on its own (exit status {'unknown' if code is None else code})"}

    def pids(self):
        rec = self._record()
        if not rec:
            return set()
        out = self._group(rec)
        if self._alive(rec):
            out |= {rec["pid"]} | Procs().descendants(rec["pid"])
        return out

    def _wait_gone(self, rec, timeout):
        end = time.monotonic() + timeout
        while self._running(rec):
            if time.monotonic() > end:
                return False
            time.sleep(0.05)
        return True

    def stop(self):
        """pre_stop (the STOP sequence), SIGTERM to the process group, and wait until all of it is gone (SIGKILL after
        stop_timeout), as `systemctl stop` waits for the unit's cgroup."""
        rec = self._record()
        if not rec:
            return
        if self._running(rec):
            if self.pre_stop:
                with contextlib.suppress(Exception):
                    self.pre_stop()
            with contextlib.suppress(ProcessLookupError):
                os.killpg(rec["pid"], signal.SIGTERM)
            if not self._wait_gone(rec, self.stop_timeout):
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(rec["pid"], signal.SIGKILL)
                self._wait_gone(rec, 5)
        with contextlib.suppress(OSError):
            os.unlink(self.pidfile)


def openpnp_command(cfg, use_override=True):
    root = cfg.root
    vals = {"python": sys.executable, "root": root, "config": os.path.join(root, "state", "config"),
            "port": str(cfg.api_port), "lock": cfg.lock, "prefs": os.path.join(root, "prefs")}
    if use_override and cfg.openpnp_cmd:
        return [t.format(**vals) for t in shlex.split(cfg.openpnp_cmd)]
    # -F: no fork, java runs as flock's own process (the unit's main process): systemd's SIGTERM reaches it
    return ["/usr/bin/flock", "-n", "-F", "-E", str(LOCK_CONFLICT), cfg.lock, cfg.java, *JAVA_OPENS,
            f"-DconfigDir={vals['config']}", f"-Djava.util.prefs.userRoot={vals['prefs']}", "-jar", "openpnp-il.jar"]


def openpnp_env(cfg):
    env = {"OPENPNP_IL_API_PORT": str(cfg.api_port), "JAVA_TOOL_OPTIONS": "-Xmx1536m", "DISPLAY": cfg.display}
    if cfg.trusted_origins:
        # BR-73: the API also answers these origins' Host and Origin (the tailnet's HTTPS name via `tailscale serve`)
        env["OPENPNP_IL_API_TRUSTED_ORIGINS"] = ",".join(cfg.trusted_origins)
    return env


ORIGIN_PROBE = "/api/il-deploy/origin-probe"  # no such route: 404 once the Host and Origin checks pass, else 403
FOREIGN_ORIGIN = "https://il-deploy-probe.invalid"
FOREIGN_HOST = "il-deploy-probe.invalid"
RUN_MARKER = "openpnp-run.json"  # OpenPnP started (mark-start) and not stopped by a confirmed STOP (stop-openpnp) yet


def boot_id():
    try:
        with open("/proc/sys/kernel/random/boot_id") as f:
            return f.read().strip()
    except OSError:
        return "unknown"


def normalise_origin(text):
    """(origin, authority) as OpenPnP's ApiSettings normalises a trusted origin (BR-73): lowercase, no trailing slash,
    the scheme's default port dropped. ValueError for anything but http(s)://host[:port]."""
    raw = text.strip(" \t\r\n\f\v")  # String.trim(): ASCII controls and space only
    t = raw.lower()  # after the ASCII check below: U+212A KELVIN SIGN lowercases to an ASCII k
    t = t[:-1] if t.endswith("/") else t  # one, as ApiSettings
    m = re.fullmatch(r"(https?)://([^/:?#@\[\]]+)(?::([0-9]+))?", t, re.ASCII) if raw.isascii() else None
    port = int(m.group(3)) if m and m.group(3) else None
    if not m or not _uri_host(m.group(2)) or port == 0 or (port or 0) > 65535:
        raise ValueError(f"not an origin (http(s)://host[:port]): {text!r}")
    scheme, host = m.group(1), m.group(2)
    if port == (443 if scheme == "https" else 80):
        port = None
    authority = f"{host}:{port}" if port else host
    return f"{scheme}://{authority}", authority


def _uri_host(h):
    """A host java.net.URI accepts (RFC 2396): an IPv4 address, or labels of letters, digits and inner hyphens whose
    last one starts with a letter. URI.getHost() is null otherwise, and ApiSettings drops the origin."""
    if re.fullmatch(r"[0-9]{1,3}(\.[0-9]{1,3}){3}", h):
        return all(int(x) <= 255 for x in h.split("."))
    labels = h[:-1].split(".") if h.endswith(".") else h.split(".")
    return (all(re.fullmatch(r"[a-z0-9]([a-z0-9-]*[a-z0-9])?", label) for label in labels)
            and re.match(r"[a-z]", labels[-1]) is not None)


def make_service(cfg):
    if cfg.service_manager == "systemd":
        return SystemdUserService(cfg.service)
    api, root = Api(cfg.api_port), cfg.root
    return SubprocessService(openpnp_command(cfg), os.path.join(root, "current"), dict(os.environ, **openpnp_env(cfg)),
                             os.path.join(root, "run"), os.path.join(root, "log"), name=cfg.service.rsplit(".", 1)[0],
                             pre_stop=lambda: stop_sequence(api, cfg.home_busy_wait, cfg.stop_timeout,
                                                            cfg.poll_interval, Clock()))


STOP_DONE = "machine disabled, dispense engine idle, pressure off"


def stop_left(st):
    """What /api/status shows still on after a STOP ([] = done). MachineEndpoints.stop disables the machine, but a
    disable queued behind a blocking command goes on after its answer, and the dispense engine's abort sends its
    STOP_SEQUENCE (valve, then pump off) only before its next line, after the move in progress (a long auger turn, a
    dwell); with the plain GcodeDriver the DISABLE_COMMAND's M410 can time out behind it and skip the M107s."""
    m, d, p = (st.get(k) if isinstance(st.get(k), dict) else {} for k in ("machine", "dispense", "pressure"))
    out = []
    if m.get("enabled") is not False:
        out.append("the machine is still enabled")
    if d.get("busy") is True:
        out.append("the dispense engine is still running its abort (valve and pump off after the move in progress)")
    if p.get("on") is True or p.get("state") not in (None, "off"):
        out.append(f"the pressure hold is {p.get('state')}")
    return out


def api_stop_and_wait(api, timeout, poll=0.2, clock=None):
    """POST /api/stop, then wait up to timeout until /api/status shows it done (stop_left). (ok, detail)."""
    clock = clock or Clock()
    try:
        code, body = api.post("/api/stop", timeout=10)
    except ApiDown as e:
        return False, f"POST /api/stop: {e}"
    if code != 200:
        return False, f"POST /api/stop: HTTP {code} {body.get('reason', '')}"
    return wait_stopped(api, timeout, poll, clock, "/api/stop")


PLANNED_STOP = "/api/stop/planned"  # BR-75
PLANNED_STOP_RETRY_S = 5  # a refusal lasts while something works: asked again this often, not every poll
NO_PLANNED_STOP = f"no {PLANNED_STOP} to use (a build before BR-75, or no connection to the API)"
PLANNED_LOCKED = "no planned stop, someone has the machine lock: "  # 409 locked: their session, whatever else reads
LINES_UNANSWERED = f"POST {PLANNED_STOP}: no ok from the controller for "  # the valve or pump may be on
PLANNED_MAY_HAVE_RUN = "the planned stop may have run, its outputs not known to be off: "  # no answer, or a lost one


def api_planned_stop(api, timeout, poll=0.2, clock=None, off_is_done=False):
    """BR-75, the deployer's own stop: POST /api/stop/planned is OpenPnP's STOP without the screw's motor-off line
    (M18 B), so the screw keeps its timing. (ok, detail) as api_stop_and_wait; a 200 whose lines went unanswered
    (detail LINES_UNANSWERED: the valve or pump may still be on) is a failure, and so (detail PLANNED_MAY_HAVE_RUN)
    are a request that timed out and, after the switch, a 200 that sent nothing after a refusal of this call: #41's
    409 for a task that outlived its wait (its lines out, their acks lost), then "already stopped". A refusal is
    answered by waiting, never by a real STOP: 409 locked returns ok None at once (detail PLANNED_LOCKED); 409 refused
    (something works) is asked again every PLANNED_STOP_RETRY_S, and ok None when timeout s have passed, nothing sent.
    off_is_done (before the switch only, where the idle check found the machine off): a refusal while /api/status
    shows the machine stopped, idle and nobody holding the lock is nothing to stop (ok True); #41 refuses without its
    engine or a machine before it looks. NO_PLANNED_STOP when the build has none (404 "no such endpoint") or the API
    gives no connection: the caller's own stop decides, as before BR-75."""
    clock = clock or Clock()
    end = clock.monotonic() + timeout
    refused = None  # this call's first refusal
    while True:
        try:
            code, body = api.post(PLANNED_STOP, timeout=60)  # the API gives its task 20 s to start and end
        except ApiTimeout as e:
            return False, PLANNED_MAY_HAVE_RUN + str(e)
        except ApiDown:
            return None, NO_PLANNED_STOP
        error, reason = body.get("error"), str(body.get("reason") or "")
        if code == 404 and reason.startswith("no such endpoint"):
            return None, NO_PLANNED_STOP
        if code == 409 and error in LOCK_REFUSALS:
            return None, PLANNED_LOCKED + reason
        if code == 409 and error == "refused":
            refused = refused or reason
            if off_is_done:
                try:
                    st_code, st = api.get("/api/status", timeout=5)
                except ApiDown:
                    st_code, st = None, {}
                if st_code == 200 and not (stop_left(st) or api_busy_reasons(st) or api_lock_reasons(st)):
                    return True, f"{STOP_DONE} (already: the planned stop answered {reason})"
            if clock.monotonic() >= end:
                return None, f"no planned stop for {timeout:g} s: {reason}"
            clock.sleep(max(poll, min(PLANNED_STOP_RETRY_S, end - clock.monotonic())))
            continue
        if code != 200:
            return False, f"POST {PLANNED_STOP}: HTTP {code} {reason}"
        failed = body.get("linesFailed") or []
        if failed:
            return False, LINES_UNANSWERED + ", ".join(map(str, failed))
        if refused and not off_is_done and not body.get("linesSent"):
            return False, f"{PLANNED_MAY_HAVE_RUN}refused ({refused}), then the machine was already stopped"
        return wait_stopped(api, timeout, poll, clock, PLANNED_STOP)


def planned_stop_kind(ok, detail):
    """A failed planned stop in the health check: a refusal (ok None), unanswered lines or a stop that may have run
    are the machine's (a person checks; nothing restarts unattended), anything else the build's."""
    return "machine" if ok is None or detail.startswith((LINES_UNANSWERED, PLANNED_MAY_HAVE_RUN)) else "build"


def wait_stopped(api, timeout, poll, clock, sent):
    """After POST <sent>: wait up to timeout until /api/status shows the stop done (stop_left). (ok, detail)."""
    end = clock.monotonic() + timeout
    while True:
        try:
            code, st = api.get("/api/status", timeout=5)
        except ApiDown as e:
            why = f"/api/status does not answer ({e})"
        else:
            left = stop_left(st) if code == 200 else [f"/api/status answered HTTP {code}"]
            if not left:
                return True, STOP_DONE
            why = "; ".join(left)
        if clock.monotonic() >= end:
            return False, f"{why} {timeout:g} s after POST {sent}"
        clock.sleep(poll)


PRESSURE_HELD = ("on", "vent_failed")  # PressureHold states with the pump or valve on (or not known to be off)


def stop_sequence(api, wait_s, stop_timeout, poll, clock):
    """STOP OpenPnP's machine before OpenPnP is stopped or left alone. (ok, detail): ok = stopped, or nothing to
    stop (the API is gone). A STOP inside G28 hangs the controller until a power cycle, so:
    - machine.homing true (BR-44: a home runs, whoever started it): wait, whatever else the status shows. A build
      before BR-44 lacks it: the rules below alone;
    - the pressure held ('on', 'vent_failed'), or an engine run past its home (phase not 'home: ...'): STOP at
      once, outputs may be on (homes refuse while the pressure is held);
    - otherwise wait while the machine is busy or its state is unknown (no answer in time, HTTP != 200), up to
      wait_s, then STOP. Still busy then: no STOP (after SIGTERM, Marlin finishes the home on its own). After the STOP
      it waits until the machine is off, the engine idle and the pressure off (stop_left), up to stop_timeout or what
      is left of wait_s, whichever is longer: one budget for both, as the unit's TimeoutStopSec. A pressure
      'starting' or 'stopping' waits too: PressureHold.start sets STARTING on the HTTP thread and queues its task
      behind the machine thread's work, which may be a home; nothing is on before that task runs, and a running
      start ends within seconds.
    machine.homing turns true only when OpenPnP's home starts: the engine's home phase and the web's home first read the
    position and raise N1 (DispenseService.homeSafely), busy and not homing yet. So busy and the run's home phase are
    still waited out with machine.homing false (WEB_API.md: a home can begin between a status read and a STOP)."""
    end = clock.monotonic() + wait_s
    while True:
        try:
            code, st = api.get("/api/status", timeout=5)
        except ApiTimeout as e:
            why = f"OpenPnP did not answer in time ({e})"
        except ApiDown as e:
            return True, f"OpenPnP's API does not answer ({e}); nothing to stop"
        else:
            if code != 200:
                why = f"/api/status answered HTTP {code}"
            else:
                m, run, p = (st.get(k) if isinstance(st.get(k), dict) else {} for k in ("machine", "run", "pressure"))
                pressure = p.get("on") is True or p.get("state") in PRESSURE_HELD
                running = run.get("running") is True
                run_homing = running and str(run.get("phase") or "").startswith("home")
                if machine_homing(m):
                    why = "a home is running (machine.homing)"
                elif pressure or (running and not run_homing) or (not running and m.get("busy") is False):
                    return api_stop_and_wait(api, max(stop_timeout, end - clock.monotonic()), poll, clock)
                else:
                    why = "the machine is busy (homing?)"
        if clock.monotonic() >= end:
            return False, f"STOP not sent: {why} for {wait_s:g} s"
        clock.sleep(poll)


# The engine's database (DispenseDb) and the BR-02 journal it keeps in its events table (#12: ControllerReset.EVENT_*,
# run_uuid NULL): every connect, home and STOP adds rows, so every health check does. Routine ones, written while the
# deployer held the machine, are no config change; a reset's (CONTROLLER_RESET, POSITION_UNREAD, Z_FRAME_MOVED) and a
# watchdog trip always are (a rollback's old database would lose them).
ENGINE_DB = os.path.join("instancelabs", "dispense.db")
ROUTINE_JOURNAL = ("SCREW_MOTOR_OFF", "SCREW_MOTION", "SCREW_COUNT_EPOCH", "HOST_WATCHDOG_ARMED", "POSITION_KNOWN",
                   "RESET_JOURNAL")
JOURNAL_SETTLE_S, JOURNAL_SETTLE_MAX_S = 1.0, 5.0  # the engine writes its journal on a thread, a moment later
ENGINE_DB_TIMEOUT_S = 5.0  # a database another writer holds locked: give up (compared by bytes), never wait forever


def _engine_db(config_dir):
    """A read-only connection to the engine's database inside one read transaction (one consistent snapshot, its WAL
    included; OpenPnP may hold it open and write on), or None: no database, or not one SQLite reads. The caller
    closes it."""
    path = os.path.join(config_dir, ENGINE_DB)
    if not os.path.isfile(path):
        return None
    db = None
    try:
        db = sqlite3.connect(f"file:{urllib.parse.quote(os.path.abspath(path))}?mode=ro", uri=True,
                             timeout=ENGINE_DB_TIMEOUT_S, isolation_level=None)
        db.execute("BEGIN")
        db.execute("SELECT count(*) FROM sqlite_master").fetchone()  # takes the snapshot; raises if not a database
        return db
    except sqlite3.Error:
        if db is not None:
            db.close()
        return None


def _newest_event(db):
    try:
        return db.execute("SELECT coalesce(max(id), 0) FROM events").fetchone()[0]
    except sqlite3.Error:
        return None


def engine_db_newest_event(config_dir):
    """The newest id in the engine database's events table (insert order), 0 if empty; None: no such table."""
    db = _engine_db(config_dir)
    if db is None:
        return None
    try:
        return _newest_event(db)
    finally:
        db.close()


def _engine_db_tables(config_dir, journal):
    """({ENGINE_DB#table: sha256 of its rows, ENGINE_DB#schema: of every table, index, trigger and view}, the newest
    events id), from one read, or (None, None) (as _engine_db). journal=(after_id, ranges) leaves out the events rows of
    the routine BR-02 journal newer than after_id and inside a range (lo, hi] of events ids: what the engine wrote while
    the deployer held the machine (claim_ranges). Of sqlite_sequence (AUTOINCREMENT's counters) only the events row is
    left out: those rows move it, while another table's insert and delete still shows there."""
    db = _engine_db(config_dir)
    if db is None:
        return None, None
    out = {}
    try:
        h = hashlib.sha256()
        for row in db.execute("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name"):
            h.update(repr(row).encode())
        out[f"{ENGINE_DB}#schema"] = h.hexdigest()
        for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").fetchall():
            h = hashlib.sha256()
            cur = db.execute(f'SELECT * FROM "{name}"' + ("" if name == "sqlite_sequence" else " ORDER BY rowid"))
            cols = [c[0] for c in cur.description]
            for row in sorted(cur, key=repr) if name == "sqlite_sequence" else cur:
                r = dict(zip(cols, row))
                if name == "sqlite_sequence" and r.get("name") == "events":
                    continue
                if name == "events" and journal and journal[0] is not None and _deployers_journal(r, *journal):
                    continue
                h.update(repr(row).encode())
            out[f"{ENGINE_DB}#{name}"] = h.hexdigest()
        return out, _newest_event(db)
    except sqlite3.Error:
        return None, None
    finally:
        db.close()


def _deployers_journal(r, after_id, ranges):
    i = r.get("id")
    return (r.get("code") in ROUTINE_JOURNAL and r.get("run_uuid") is None and isinstance(i, int) and i > after_id
            and any(lo < i <= hi for lo, hi in ranges))


def claim_ranges(journal_path, since=None):
    """[(lo, hi)]: the engine's events ids written while the deployer held the machine: from deploy.jsonl's claim and
    release records, and the health-window records of a health check without a claim (the newest events id at each
    end). None without an id at both ends (no readable database, a claim killed before its release). since (ISO): only
    windows closed at or after it, the current build's baseline: an older one may cover ids of a database a rollback
    has put back since, which hands them out again."""
    out, lo = [], None
    cut = datetime.datetime.fromisoformat(since) if since else None
    try:
        with open(journal_path) as f:
            for line in f:
                try:
                    r = json.loads(line)
                except ValueError:
                    continue
                e, phase = r.get("event"), r.get("phase")
                if e == "claim" or (e == "health-window" and phase == "begin"):
                    lo = r.get("events_id")
                elif (e == "release" or (e == "health-window" and phase == "end")) and lo is not None:
                    hi, closed = r.get("events_id"), r.get("ts")
                    try:
                        late = cut is None or datetime.datetime.fromisoformat(closed) >= cut
                    except (TypeError, ValueError):
                        late = False
                    if isinstance(lo, int) and isinstance(hi, int) and late:
                        out.append((lo, hi))
                    lo = None
    except OSError:
        pass
    return out


def config_manifest(config_dir, journal=None, rows=True, newest=None):
    """{path relative to config_dir: sha256}, to spot config changes since a deploy. Skipped: log/ and SQLite
    -shm files (an index that changes on reads; the db and its -wal hold the data). The engine's database is compared
    by its rows, one entry per table (_engine_db_tables, with `journal`), when SQLite reads it and `rows`; else by
    the bytes of the db and its -wal (a baseline kept that way, before 11-04). newest (a list): gets the newest events
    id of the same read (the baseline's post_events_id)."""
    out = {}
    tables, last = _engine_db_tables(config_dir, journal) if rows else (None, None)
    if newest is not None:
        newest.append(last)
    if tables is not None:
        out.update(tables)
    for d, dirs, files in os.walk(config_dir):
        rel = os.path.relpath(d, config_dir)
        if rel == ".":
            dirs[:] = [x for x in dirs if x != "log"]
        for n in files:
            if n.endswith("-shm"):
                continue
            if tables is not None and os.path.normpath(os.path.join(rel, n)) in (ENGINE_DB, ENGINE_DB + "-wal"):
                continue
            try:
                out[os.path.normpath(os.path.join(rel, n))] = sha256_file(os.path.join(d, n))
            except OSError:
                out[os.path.normpath(os.path.join(rel, n))] = None
    return out


def manifest_changes(old, new):
    return ([f"{k} changed" for k in sorted(old.keys() & new.keys()) if old[k] != new[k]]
            + [f"{k} added" for k in sorted(new.keys() - old.keys())]
            + [f"{k} removed" for k in sorted(old.keys() - new.keys())])


def _settled(rec):
    """A deployed.json record without its not-verified marks: 'pending' (installed or reinstalled, its health check
    has not passed: deploys are refused), 'unchecked' (put back with its config after a machine problem, not checked
    since) and 'before' (the records before a switch, to undo one a hard kill cut short)."""
    return {k: v for k, v in (rec or {}).items() if k not in ("pending", "unchecked", "before")}


def _record_for(dep, tag):
    """deployed.json's record of tag (as current or previous), or None."""
    for k in ("current", "previous"):
        r = (dep or {}).get(k)
        if tag and isinstance(r, dict) and r.get("tag") == tag:
            return r
    return None


class Clock:
    def now(self):
        return time.time()

    def monotonic(self):
        return time.monotonic()

    def sleep(self, s):
        time.sleep(s)


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = f"{path}.{os.getpid()}.tmp"
    with open(tmp, "w") as f:
        json.dump(obj, f, indent=1, default=str)
        f.write("\n")
    os.replace(tmp, path)


def write_json_durable(path, obj):
    """write_json, then fsync the file and its directory: once this returns, it survives a power cut."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = f"{path}.{os.getpid()}.tmp"
    with open(tmp, "w") as f:
        json.dump(obj, f, indent=1, default=str)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)
    fd = os.open(os.path.dirname(path), os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _iso_ts(text):
    """The POSIX time of an ISO 8601 stamp with its zone (il_deploy's iso()), or None."""
    try:
        return datetime.datetime.fromisoformat(str(text)).timestamp()
    except ValueError:
        return None


def read_json(path, default=None):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


class Deps:
    """Injectable collaborators; None means the real one for this host."""

    def __init__(self, github=None, service=None, clock=None, procs=None, runner=None, attestation=None):
        self.github, self.service, self.clock, self.procs, self.runner = github, service, clock, procs, runner
        # the build provenance verifier; tests pass one with a fake gh here (no flag or environment variable can)
        self.attestation = attestation


# ------------------------------------------------------------------------------------------------
# The deployer
# ------------------------------------------------------------------------------------------------
class Deployer:
    def __init__(self, cfg, deps=None):
        deps = deps or Deps()
        self.cfg = cfg
        self.clock = deps.clock or Clock()
        self.procs = deps.procs or Procs()
        self.svc = deps.service or make_service(cfg)
        self.runner = deps.runner or subprocess.run
        self._gh = deps.github
        self.attest = deps.attestation or GhAttestation()
        self.api = Api(cfg.api_port)
        self.out = sys.stdout
        self.held = False  # a person must check the machine (set during this claim)
        self.claim = None  # the Holder claim while it stands (with_claim)
        r = cfg.root
        self.releases = os.path.join(r, "releases")
        self.snapshots = os.path.join(r, "snapshots")
        self.state = os.path.join(r, "state")
        self.config = os.path.join(r, "state", "config")
        self.logdir = os.path.join(r, "log")
        self.journal = os.path.join(self.logdir, "deploy.jsonl")
        self.bad_path = os.path.join(r, "bad.json")
        self.hold_path = os.path.join(r, "hold.json")
        self.deployed_path = os.path.join(r, "deployed.json")
        self.own_path = os.path.join(r, "own-activity.json")
        self.claim_path = os.path.join(r, "claim.json")
        self.person_path = os.path.join(r, "person.json")
        self.switch_path = os.path.join(r, "switch.json")
        self.exit_seen_path = os.path.join(r, "openpnp-exit.json")
        self.refused_path = os.path.join(self.logdir, "refused.json")

    @property
    def gh(self):
        if self._gh is None:
            self._gh = default_github()
        return self._gh

    # --- small helpers -------------------------------------------------------------------------
    def say(self, msg):
        print(msg, file=self.out, flush=True)

    def iso(self):
        return datetime.datetime.fromtimestamp(self.clock.now()).astimezone().isoformat(timespec="seconds")

    def stamp(self, fmt="%Y-%m-%d %H:%M"):
        return time.strftime(fmt, time.localtime(self.clock.now()))

    def record(self, event, **kw):
        try:
            os.makedirs(self.logdir, exist_ok=True)
            with open(self.journal, "a") as f:
                f.write(json.dumps(dict(ts=self.iso(), event=event, **kw), default=str) + "\n")
        except OSError as e:  # a full disk must not stop a deploy halfway
            print(f"il_deploy: cannot write {self.journal}: {e}", file=sys.stderr, flush=True)

    def refuse(self, msg):
        print("refused: " + msg, flush=True)
        self.record("refused-action", reason=msg)
        return 2

    def not_idle(self, reasons):
        print("not idle: " + "; ".join(reasons), flush=True)
        self.record("not-idle", reasons=reasons)
        return 3

    def read_bad(self):
        return read_json(self.bad_path, {}) or {}

    def add_bad(self, tag, reason):
        bad = self.read_bad()
        bad[tag] = {"reason": reason, "at": self.iso()}
        write_json(self.bad_path, bad)
        self.record("bad", tag=tag, reason=reason)

    def unbad(self, tag):
        bad = self.read_bad()
        if bad.pop(tag, None) is not None:
            write_json(self.bad_path, bad)
            self.record("unbad", tag=tag)

    def read_hold(self):
        return read_json(self.hold_path)

    def set_hold(self, reason):
        """Set the hold, or add reason to the one that is set: its Holder and previous value (what resume hands back)
        stay, and so do its earlier reasons."""
        hold = self.read_hold()
        hold = hold if isinstance(hold, dict) else {}
        if hold.get("reason") and hold["reason"] != reason:
            hold["earlier"] = (hold.get("earlier") or []) + [{"reason": hold["reason"], "at": hold.get("at")}]
        write_json(self.hold_path, dict(hold, reason=reason, at=self.iso()))
        self.record("hold", reason=reason)
        self.held = True

    @staticmethod
    def hold_reasons(hold):
        """(since, text): when the hold was first set, and its reasons, the latest first."""
        earlier = [e for e in hold.get("earlier") or [] if isinstance(e, dict)]
        text = "; ".join([str(hold.get("reason"))] + [f"before that, at {e.get('at')}: {e.get('reason')}"
                                                      for e in reversed(earlier)])
        return (earlier[0].get("at") if earlier else hold.get("at")), text

    def read_deployed(self):
        return read_json(self.deployed_path)

    def _link(self, name):
        p = os.path.join(self.cfg.root, name)
        return os.path.realpath(p) if os.path.islink(p) else None

    def current_tag(self):
        t = self._link("current")
        return os.path.basename(t) if t else None

    def previous_tag(self):
        t = self._link("previous")
        return os.path.basename(t) if t else None

    def _set_link(self, name, target):
        path = os.path.join(self.cfg.root, name)
        if target is None:
            if os.path.islink(path):
                os.unlink(path)
            return
        tmp = os.path.join(self.cfg.root, f".{name}.tmp")
        if os.path.lexists(tmp):
            os.unlink(tmp)
        os.symlink(os.path.relpath(target, self.cfg.root), tmp)
        os.replace(tmp, path)

    def set_links(self, current, previous):
        with signals_deferred():  # both links or neither, as far as a SIGTERM goes
            self._set_link("previous", previous)
            self._set_link("current", current)
        self.record("switch", current=current and os.path.basename(current),
                    previous=previous and os.path.basename(previous))

    def staged(self):
        try:
            names = os.listdir(self.releases)
        except OSError:
            return []
        return sorted((n for n in names if parse_tag(n)), key=lambda n: -parse_tag(n)[0])

    def build_info(self, tag):
        return read_json(os.path.join(self.releases, tag, "BUILD-INFO.json"), {}) if tag else {}

    def describe(self, tag):
        bi = self.build_info(tag)
        sha7 = (bi.get("sha") or "")[:7] or "?"
        return f"{tag} ({sha7}, {bi.get('branch', self.cfg.branch)}, CI {bi.get('run_url', '?')})"

    def state_line(self, value):
        for _ in range(5):
            try:
                edit_state_file(self.cfg.state_md, lambda t: set_deployed_line(t, value))
                self.record("state-md", deployed=value)
                return
            except StateFileConflict:
                time.sleep(0.3)
            except OSError:
                break
        self.record("state-md-failed", deployed=value)

    # --- idle ----------------------------------------------------------------------------------
    def idle_reasons(self, our_claim=None, scope="deploy"):
        """Why the machine is not idle ([] = idle). scope 'health' checks only the Holder, the quiet
        period and the API: the OpenPnP being checked owns the lock and the device."""
        cfg, reasons = self.cfg, []
        try:
            with open(cfg.state_md, encoding="utf-8") as f:
                h = parse_holder(f.read())
            if not h.idle and not (our_claim and h.value == our_claim):
                reasons.append(h.reason)
        except OSError as e:
            reasons.append(f"STATE.md unreadable ({cfg.state_md}): {e.strerror}")
        active = self.svc.is_active()
        if scope == "deploy":
            hold = self.read_hold()
            if hold:
                since, why = self.hold_reasons(hold if isinstance(hold, dict) else {})
                reasons.append(f"deploys are on hold since {since}: {why} (after a person has checked the machine: "
                               "il_deploy.py resume)")
            ours = self.svc.pids()
            reasons += self._lock_reasons(ours) + self._device_reasons(ours)
            for pid, d, real in self.procs.openpnp_processes():
                if real and pid not in ours:
                    reasons.append(f"another OpenPnP with a real-machine config runs (pid {pid}, config {d})")
            reasons += self._openpnp_log_reasons()
        reasons += self._quiet_reasons()
        if active or scope == "health":
            try:
                code, st = self.api.get("/api/status", timeout=10)
            except ApiDown as e:
                reasons.append(f"OpenPnP's API on port {cfg.api_port} does not answer ({e})")
            else:
                if code != 200:
                    reasons.append(f"OpenPnP's /api/status answered HTTP {code}")
                else:
                    reasons += (api_busy_reasons(st) + api_enabled_reasons(st) + api_lock_reasons(st)
                                + self._api_quiet_reasons(st))
        else:
            try:
                self.api.get("/api/status", timeout=3)
                reasons.append(f"something answers on 127.0.0.1:{cfg.api_port} but {cfg.service} is not running "
                               "(another OpenPnP?)")
            except ApiDown:
                pass
        return reasons

    def shutdown_budget_reasons(self):
        """Why a reboot could SIGKILL a deploy or rollback halfway ([] = it cannot): the user manager's own stop
        timeout (user@<uid>.service TimeoutStopSec; Ubuntu's default 5 s) must cover il-deploy's SIGTERM handler and
        then il-openpnp's ExecStop. systemd units only; read with `systemctl show`, never changed."""
        if self.cfg.service_manager != "systemd":
            return []
        unit = f"user@{os.getuid()}.service"
        try:
            p = self.runner(["systemctl", "show", unit, "-p", "TimeoutStopUSec", "--value"], capture_output=True,
                            text=True, timeout=30)
            have = parse_timespan(p.stdout) if p.returncode == 0 else None
        except (OSError, subprocess.SubprocessError):
            have = None
        if have is not None and have >= USER_MANAGER_STOP_S:
            return []
        got = "an unknown time" if have is None else f"{have:g} s"
        return [f"at a reboot the user manager gets {got} to stop ({unit} TimeoutStopSec), then everything in it is "
                "killed: a deploy cut there leaves the machine unstopped and the Holder claimed. il_deploy's units "
                f"need {USER_MANAGER_STOP_S} s. Install the system drop-in once (root, with the user's OK): "
                f"il_deploy.py units --out DIR, then sudo install -D -m 644 DIR/system/{unit}.d/{USER_MANAGER_DROP_IN} "
                f"/etc/systemd/system/{unit}.d/{USER_MANAGER_DROP_IN} && sudo systemctl daemon-reload"]

    def _lock_reasons(self, ours):
        path = self.cfg.lock
        try:
            fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o666)
        except OSError as e:
            return [f"serial lock {path} cannot be opened: {e.strerror}"]
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            fcntl.flock(fd, fcntl.LOCK_UN)
            return []
        except BlockingIOError:
            holders = self.procs.lock_holders(path)
            others = [p for p in holders if p not in ours]
            if holders and not others:
                return []
            who = self.procs.describe(others) if others else "an unknown process"
            return [f"serial lock {path} is held by {who}"]
        finally:
            os.close(fd)

    def _device_reasons(self, ours):
        dev = self.cfg.device
        if not os.path.exists(dev):
            return [f"serial device missing: {dev} (unplugged?)"]
        others = [p for p in self.procs.openers(dev) if p not in ours]
        return [f"serial device {dev} is open by {self.procs.describe(others)}"] if others else []

    def _quiet_reasons(self):
        now, quiet, out = self.clock.now(), self.cfg.quiet_min * 60, []
        for path in self.cfg.activity:
            try:
                age = now - os.stat(path).st_mtime
            except FileNotFoundError:
                continue
            except OSError as e:
                out.append(f"activity file {path} unreadable: {e.strerror}")
                continue
            if age < quiet:
                out.append(f"activity in {path} {int(age)} s ago (quiet period {int(quiet)} s)" if age >= 0
                           else f"activity file {path} has a future time")
        return out

    def _openpnp_log_reasons(self):
        """OpenPnP logs each change made through its API (ApiServer: 'POST ... -> 200'), the engine's work, its start
        and its stop, and nothing while idle: the quiet period runs from its last line, also once the service stopped or
        died (its API is not asked then). What it wrote in il_deploy's own windows does not count. Deploy scope only: a
        person running `health` by hand has just started OpenPnP."""
        path = self._log_path()
        try:
            mtime = os.stat(path).st_mtime
        except FileNotFoundError:
            return []
        except OSError as e:
            return [f"OpenPnP's log {path} unreadable: {e.strerror}"]
        age, quiet = self.clock.now() - mtime, self.cfg.quiet_min * 60
        if age >= quiet or self._own(mtime * 1000):
            return []
        return [f"OpenPnP logged {int(age)} s ago ({path}; quiet period {int(quiet)} s)" if age >= 0
                else f"OpenPnP's log {path} has a future time"]

    def _api_quiet_reasons(self, st):
        """Activity in OpenPnP within the quiet period (api_activity_times), except what il_deploy's own starts and
        health checks stamped (_note_own)."""
        now_ms, quiet_ms = self.clock.now() * 1000, self.cfg.quiet_min * 60000
        latest = {}
        for what, ms in api_activity_times(st):
            if not self._own(ms):
                latest[what] = max(ms, latest.get(what, ms))
        return [f"{what} {int((now_ms - ms) / 1000)} s ago in OpenPnP (quiet period {int(quiet_ms / 1000)} s)"
                for what, ms in latest.items() if now_ms - ms < quiet_ms]

    # --- claiming --------------------------------------------------------------------------------
    def held_text(self, action, subject, note):
        """The Holder while the hold is set: it must not read as free to other roles."""
        who = f"Infra ({action}{' ' + subject if subject else ''})"
        return (f"{who}: a person must check the machine (hold set at {self.stamp('%H:%M')}"
                + (f": {_safe_note(note)}" if note else "") + "); after the check run `il_deploy.py resume`")

    def with_claim(self, action, subject, fn, scope="deploy"):
        """Claim the Holder, re-check idle, run fn(claim) -> (rc, note), and hand the Holder back whatever happens:
        'nobody (...)' if all is well, a held Holder if a person must check the machine (the hold is set). An
        unexpected exception (SIGTERM included) stops the machine if it safely can and sets the hold."""
        claim = f"Infra ({action}{' ' + subject if subject else ''}) since {self.stamp()}"
        # The claim is also kept in the root while it stands (claim.json): if no exit path runs (SIGKILL), the next
        # il_deploy finds it there and turns it into a hold (recover_stale_claim).
        info = {"holder": claim, "action": action, "subject": subject, "at": self.iso(), "pid": os.getpid()}
        _defer_term(True)
        try:
            write_json(self.claim_path, info)
            prev = claim_holder(self.cfg.state_md, claim)
            write_json(self.claim_path, dict(info, previous=prev))
        except (NotIdle, StateFileConflict, OSError, ValueError) as e:
            self._drop_claim_file()
            _defer_term(False)
            return self.not_idle([f"could not claim the Holder: {e}"])
        except BaseException:
            _defer_term(False)
            raise
        self.held = False
        self.claim, claimed_at = claim, self.clock.now()
        rc, note = 1, None
        try:
            _defer_term(False)  # a SIGTERM during the claim is raised here, inside the try
            self.record("claim", holder=claim, previous=prev, events_id=engine_db_newest_event(self.config))
            reasons = self.idle_reasons(our_claim=claim, scope=scope)
            if reasons:
                handed = self._hand_back(action, claim, prev, prev)
                self._drop_claim_file()
                self.claim = None
                return 1 if handed is None else self.not_idle(reasons)
            # OpenPnP may have died since main's check: a start would clear systemd's 'failed' and the marker
            exited = None if self.svc.is_active() else self.check_openpnp_exit()
            rc, note = (5, exited) if exited else fn(claim)
        except BaseException as e:
            rc, note = 1, self._after_exception(action, e)
        _defer_term(True)
        try:
            # il_deploy alone drove OpenPnP during the claim: what it stamped or logged then is not a person's activity,
            # and an exit of OpenPnP then was il_deploy's to handle, not a crash for the next command to hold on
            self._note_own(claimed_at)
            self._note_exit_seen()
            if self.held and not self.svc.is_active() and os.path.exists(self.run_marker_path):
                self._drop_run_marker()  # the hold carries what happened; no second hold for the same exit
                self.record("run-marker-dropped", why="the hold set during the claim carries the exit")
            if self.held:
                text = self.held_text(action, subject, note)
                write_json(self.hold_path, dict(self.read_hold() or {}, holder=text, previous=prev))
            else:
                text = released_value(action, self.stamp("%H:%M"), note, prev)
            handed = self._hand_back(action, claim, text, prev)
            if handed is None:
                rc = rc or 1
            elif handed is False:
                rc = rc or 1
                msg = (f"the Holder no longer showed il_deploy's claim at the end of the {action}: someone changed "
                       "STATE.md during it (a stale save?), and it was left as it is; a person must check who has the "
                       "machine")
                print(f"{action}: {msg}", file=sys.stderr, flush=True)
                self.record("claim-lost", at="hand-back", claim=claim)
                if not self.held:
                    self.set_hold(msg)
                write_json(self.hold_path, dict(self.read_hold() or {}, holder=None))  # resume: not ours to hand back
                note = f"{note}; {msg}" if note else msg
            self._drop_claim_file()
            self.record("result", action=action, subject=subject, rc=rc, note=note)
            if note:
                self.say(note)
        finally:
            self.claim = None
            _TERM.update(defer=False, pending=None)  # done: a SIGTERM now just ends the process as planned
        return rc

    def _claim_lost(self):
        """Why STATE.md's Holder no longer shows this run's claim (None while it does). edit_state_file protects only
        il_deploy's own writes: a save from a buffer opened before the claim (an editor, a read-modify-write script)
        replaces it silently, and another role reading 'nobody' may then take the machine mid-deploy."""
        if not self.claim:
            return None
        try:
            with open(self.cfg.state_md, encoding="utf-8") as f:
                found = find_entries(f.read().splitlines(), "Holder")
        except OSError as e:
            return f"STATE.md cannot be read to check il_deploy's claim ({e.strerror})"
        if len(found) == 1 and found[0][2].group("value").startswith(self.claim):
            return None
        now = found[0][2].group("value") if len(found) == 1 else f"{len(found)} Holder lines"
        return f"STATE.md's Holder no longer shows il_deploy's claim (it says '{now}'): someone else changed it"

    def _abort_claim_lost(self, why, what):
        note = f"{why}; {what}: a person must check who has the machine"
        self.record("claim-lost", why=why)
        self.set_hold(note)
        return 5, note

    # --- OpenPnP ending without il_deploy ------------------------------------------------------------------------
    def check_openpnp_exit(self):
        """An exit of OpenPnP that il_deploy did not cause (svc.last_exit: a crash, the OOM killer, Xvfb lost, an ExecStop
        that could not confirm the STOP): the outputs (valve, pump) keep their state when the JVM dies, nothing sent a
        STOP, and a person may be at the machine. It sets the hold, once per exit (openpnp-exit.json). Returns the note
        if this call set it."""
        try:
            ex = self.svc.last_exit()
        except (OSError, subprocess.SubprocessError, ValueError) as e:
            self.record("openpnp-exit-unknown", error=str(e))
            return None
        if ex and (read_json(self.exit_seen_path, {}) or {}).get("id") == ex["id"]:
            ex = None
        ex = ex or self._marker_exit()
        if not ex:
            return None
        note = (f"OpenPnP ended without il_deploy stopping it: {ex['detail']}. The valve and pump keep their state when "
                "it dies and no STOP was sent; a person must check the machine")
        self.record("openpnp-exit", exit=ex)
        self.set_hold(note)
        # one exit, one hold: the systemd 'failed' too when the marker spoke first (re-read), and the marker after it
        self._note_exit_seen(None if ex["id"].startswith("marker@") else ex)
        self._drop_run_marker()
        return note

    def _drop_run_marker(self):
        with contextlib.suppress(FileNotFoundError):
            os.unlink(self.run_marker_path)  # the hold carries it now; the next start writes a new one

    @property
    def run_marker_path(self):
        return os.path.join(self.cfg.root, RUN_MARKER)

    def _marker_exit(self):
        """OpenPnP down while its run marker stands: it ran (mark-start) and no confirmed STOP followed (stop-openpnp
        clears the marker only then). Covers what systemd forgets at a reboot (a freeze, a power cut, an unconfirmed
        STOP at shutdown) and an exit between two state reads. Never while the unit runs, starts or stops."""
        m = read_json(self.run_marker_path)
        down = self.svc.is_down() if hasattr(self.svc, "is_down") else not self.svc.is_active()
        if not isinstance(m, dict) or not down:
            return None
        if self.svc.exit_status() == LOCK_CONFLICT:
            # flock refused the start (the serial lock was taken after mark-start's check): java never ran
            self.record("run-marker-dropped", why="the start was refused on the serial lock (75)")
            self._drop_run_marker()
            return None
        when = "before the last reboot" if m.get("boot_id") != boot_id() else "since"
        return {"id": f"marker@{m.get('at')}", "detail": f"it was started at {m.get('at')} and is down {when} with no "
                                                         "confirmed STOP (its run marker stands)"}

    def mark_start(self):
        """The unit's ExecStartPre after check-device: refuse (exit 1: java never runs) while the serial lock is taken,
        else write the run marker with this boot's id."""
        held = self._lock_reasons(set())
        if held:
            self.say("mark-start: " + "; ".join(held))
            return 1
        write_json_durable(self.run_marker_path, {"boot_id": boot_id(), "at": self.iso()})  # before java starts
        return 0

    def _note_exit_seen(self, ex=None):
        try:
            ex = ex or self.svc.last_exit()
            if ex:
                write_json(self.exit_seen_path, dict(ex, at=self.iso()))
        except (OSError, subprocess.SubprocessError, ValueError) as e:
            self.record("openpnp-exit-not-noted", error=str(e))

    def _drop_claim_file(self):
        with contextlib.suppress(FileNotFoundError):
            os.unlink(self.claim_path)

    def recover_stale_claim(self):
        """A claim no exit path handed back: il_deploy was SIGKILLed (power loss, the OOM killer, the user manager's
        stop timeout at shutdown). Runs under the root lock, so no il_deploy works on this root now. A restore and
        switch it recorded is finished first (switch.json). The claim becomes a hold with a Holder that says so (resume
        hands it back), and the Deployed line and deployed.json say what is installed, unverified. True if it set the
        hold."""
        c = read_json(self.claim_path)
        unfinished = None
        marker = read_json(self.switch_path)
        if marker:
            try:
                self._finish_switch(marker)
            except OSError as e:
                unfinished = f"a config restore and switch was cut short and cannot be finished ({e}): {self.switch_path}"
                self.record("switch-unfinished", error=str(e), marker=marker)
        if not isinstance(c, dict) or not c.get("holder"):
            self._drop_claim_file()
            if unfinished:
                self.set_hold(unfinished)
                return True
            return False
        action, subject = c.get("action") or "deploy", c.get("subject") or ""
        what = f"{action} {subject}".strip()
        note = (f"il_deploy was killed during the {what} (claimed at {c.get('at')}): no exit path ran, the machine was "
                "not stopped through the API" + (f"; {unfinished}" if unfinished else ""))
        self.record("stale-claim", claim=c)
        self.set_hold(note)
        held = self.held_text(action, subject, note)
        write_json(self.hold_path, dict(self.read_hold() or {}, holder=held, previous=c.get("previous")))
        if self._hand_back(action, c["holder"], held, c.get("previous"), ours=False) is False:
            # the Holder no longer shows the claim (it was never written, or someone changed it): leave it alone
            write_json(self.hold_path, dict(self.read_hold() or {}, holder=None))
        if not unfinished:
            self._settle_records(action, f"il_deploy killed; found at {self.stamp()}")
        self._drop_claim_file()
        self.say(f"found the claim of an il_deploy that was killed ({c['holder']}): hold set; a person must check the "
                 "machine, then il_deploy.py resume")
        return True

    def _deployed_value(self):
        """The value of STATE.md's Deployed line (its wrapped lines joined), or None."""
        try:
            with open(self.cfg.state_md, encoding="utf-8") as f:
                lines = f.read().splitlines()
        except OSError:
            return None
        found = find_entries(lines, "Deployed on the machine")
        if not found:
            return None
        i, j, m = found[0]
        return " ".join([m.group("value")] + [l.strip() for l in lines[i + 1:_value_end(lines, i, j)]])

    def _settle_records(self, action, cause):
        """deployed.json and the Deployed line after a run that did not finish (cause: 'il_deploy killed; found at ...',
        or the exception), by what deployed.json records against the current link:
        - the current build not verified (pending, unchecked): the line says so;
        - a pending build never switched to (cut short before its switch): undone, and the current build is pending,
          as nobody checked the machine since;
        - the current build verified: it stays so; only a line still naming a switch in progress is rewritten."""
        dep = self.read_deployed() or {}
        rec, cur = dep.get("current") or {}, self.current_tag()
        if (rec.get("pending") or rec.get("unchecked")) and rec.get("tag") == cur:
            back = " or `il_deploy.py rollback`" if self.previous_tag() and rec.get("pending") else ""
            self.state_line(f"{cur} installed but NOT verified: the {action} was cut short ({cause}); a person must "
                            f"check the machine, then `il_deploy.py health` (verifies it){back}.")
            return
        if rec.get("pending"):
            before = rec.get("before") if isinstance(rec.get("before"), dict) else {}
            why = f"the {action} of {rec.get('tag')} was cut short before its switch ({cause})"
            if cur is None:
                with contextlib.suppress(OSError):
                    os.unlink(self.deployed_path)
                self.state_line(f"nothing deployed by il_deploy: {why}.")
                return
            cur_rec = _record_for(before, cur) or {"tag": cur, "sha": self.build_info(cur).get("sha")}
            write_json(self.deployed_path, dict(before, current=dict(cur_rec, pending=why + "; not checked since")))
            self.state_line(f"{self.describe(cur)}, not checked since {why}; a person must check the machine, then "
                            "`il_deploy.py health`.")
            return
        line = self._deployed_value()
        if cur and rec.get("tag") == cur and (line is None or re.search(r"\bbeing (re)?installed\b|NOT verified", line)):
            prev = rec.get("previous")
            self.state_line(f"{self.describe(cur)} since {rec.get('since', '?')}, deployed by Infra (il_deploy.py)"
                            + (f"; previous {prev}" if prev else "") + f". Config state: {self.config}.")

    def _settled_events_id(self):
        """The newest events id once the engine's journal writer has been quiet JOURNAL_SETTLE_S (at most
        JOURNAL_SETTLE_MAX_S): what it wrote for this claim's last actions counts as the claim's."""
        last, quiet, waited = engine_db_newest_event(self.config), 0.0, 0.0
        while last is not None and quiet < JOURNAL_SETTLE_S and waited < JOURNAL_SETTLE_MAX_S:
            self.clock.sleep(0.25)
            waited += 0.25
            now = engine_db_newest_event(self.config)
            quiet = 0.0 if now != last else quiet + 0.25
            last = now
        return last

    def _hand_back(self, action, claim, text, prev, ours=True):
        """Swap our claim for text. True, False (someone else's Holder now), or None: it could not be written,
        which is said on stderr and sets the hold. The release keeps the newest events id once the engine's journal has
        settled, before the machine is free (ours=False, a killed claim found later: none, what happened since is not
        ours)."""
        events_id = self._settled_events_id() if ours else None
        try:
            ok = release_holder(self.cfg.state_md, claim, text)
            self.record("release", ok=ok, holder=text, events_id=events_id)
            return ok
        except (StateFileConflict, OSError) as e:
            msg = (f"could not hand the Holder back in {self.cfg.state_md} ({e}): it still says '{claim}'; "
                   "a person must set it")
            print(f"{action}: {msg}", file=sys.stderr, flush=True)
            self.record("release", ok=False, error=str(e), holder=text, events_id=events_id)
            self.set_hold(msg)
            write_json(self.hold_path, dict(self.read_hold() or {}, holder=claim, previous=prev))
            return None

    def _after_exception(self, action, e):
        self.record("exception", error=f"{type(e).__name__}: {e}", traceback=traceback.format_exc())
        self.say(f"{action}: unexpected {type(e).__name__}: {e}")
        try:
            ok, detail = self.stop_machine()
        except BaseException as e2:
            ok, detail = False, f"STOP not sent: {type(e2).__name__}: {e2}"
        self.record("stop", ok=ok, detail=detail)
        note = (f"{action} failed unexpectedly ({type(e).__name__}: {e}); "
                + ("machine stopped through /api/stop" if ok and detail == STOP_DONE else detail))
        self.set_hold(note)
        try:
            marker = read_json(self.switch_path)
            if marker:  # a restore and switch stopped halfway (an error inside it): finish it from its record
                self._finish_switch(marker)
            self._settle_records(action, f"it stopped unexpectedly: {type(e).__name__}: {e}")
        except BaseException as e2:
            self.record("settle-failed", error=f"{type(e2).__name__}: {e2}", traceback=traceback.format_exc())
            note += f"; deployed.json and STATE.md could not be settled ({type(e2).__name__}: {e2})"
        return note

    # --- stopping and starting OpenPnP -----------------------------------------------------------
    def _wait_not_busy(self, seconds):
        """Until /api/machine shows the machine not busy and no home running (machine.homing, BR-44). False at the
        timeout."""
        end = self.clock.monotonic() + seconds
        while True:
            try:
                code, m = self.api.get("/api/machine", timeout=10)
                if code == 200 and m.get("busy") is False and not machine_homing(m):
                    return True
            except ApiDown:
                pass
            if self.clock.monotonic() >= end:
                return False
            self.clock.sleep(self.cfg.poll_interval)

    def stop_machine(self):
        """STOP and wait until disabled, never during a home (stop_sequence). (ok, detail)."""
        return stop_sequence(self.api, self.cfg.home_busy_wait, self.cfg.stop_timeout, self.cfg.poll_interval,
                             self.clock)

    def planned_stop(self, stop=None, off_is_done=False):
        """The deployer's own stop of an idle machine (BR-75, api_planned_stop); without a planned stop to use (a
        build before BR-75, the API refusing connections) stop() decides, as before (default stop_machine). (ok,
        detail); ok None: nothing sent, someone holds the lock or the machine still works."""
        ok, detail = api_planned_stop(self.api, self.cfg.stop_timeout, self.cfg.poll_interval, self.clock, off_is_done)
        if ok is None and detail == NO_PLANNED_STOP:
            return (stop or self.stop_machine)()
        return ok, detail

    def stop_service(self):
        """Stop the running service gracefully, the machine first by the planned stop. None if stopped, else (rc,
        note) for the caller to return."""
        ok, detail = self.planned_stop(off_is_done=True)
        self.record("stop", ok=ok, detail=detail)
        if ok is None:  # someone has the machine lock, or it works: nothing sent, a later run deploys
            return self.not_idle([detail]), f"{detail}; nothing changed"
        if not ok:
            note = f"OpenPnP did not switch the machine off ({detail}); nothing changed, a person must check"
            self.set_hold(note)
            return 1, note
        self.svc.stop()
        self.record("service-stop")
        end = self.clock.monotonic() + 15
        while True:
            try:
                self.api.get("/api/status", timeout=2)
            except ApiDown:
                return None
            if self.clock.monotonic() >= end:
                note = f"port {self.cfg.api_port} still answers after stopping {self.cfg.service}; nothing changed"
                self.set_hold(note)
                return 1, note
            self.clock.sleep(self.cfg.poll_interval)

    def stop_after_failure(self):
        """STOP, then stop the service. False, with OpenPnP left running, unless the STOP was confirmed or the API
        is gone: SIGTERM sends no STOP and leaves the outputs as they are."""
        ok, detail = self.stop_machine()
        self.record("stop", ok=ok, detail=detail)
        if not ok:
            return False
        self.svc.stop()
        self.record("service-stop")
        return True

    def restore_running(self, was_running):
        if not was_running and self.svc.is_active():
            self.svc.stop()
            self.record("service-stop", why="it was stopped before")

    def start_and_check(self, sha7):
        since = self.clock.now()
        try:
            self.svc.start()
        except (ServiceError, OSError, subprocess.SubprocessError) as e:
            self.record("start", ok=False, error=str(e))
            return {"ok": False, "kind": "machine", "failure": f"start: OpenPnP did not start: {e}", "steps": []}
        self.record("start", ok=True)
        try:
            rep = self.health_check(sha7, watch_service=True)
        finally:
            self._note_own(since)
        self.record("health", ok=rep["ok"], kind=rep["kind"], failure=rep["failure"], report=rep)
        return rep

    # --- health ----------------------------------------------------------------------------------
    def _wait_status(self, watch_service):
        """/api/status of a started OpenPnP: 200 with the dispense engine running. OpenPnP starts its web API and the
        engine on configurationComplete in no fixed order (its listeners are a HashSet), so a 200 without the engine
        is waited out too; at the timeout that 200 is returned, and the engine check fails on it. dispense.state
        (BR-44) 'failed' is final (DispenseService.start threw): that 200 is returned at once; 'starting' waits."""
        end = self.clock.monotonic() + self.cfg.status_timeout
        last, started = "no answer", None
        while True:
            if watch_service and not self.svc.is_active():
                code = self.svc.exit_status()
                if code == LOCK_CONFLICT:
                    return None, f"OpenPnP exited at start: the serial lock {self.cfg.lock} was taken", "machine"
                return None, f"OpenPnP exited during start (exit status {code})", "build"
            try:
                code, st = self.api.get("/api/status", timeout=5)
                if code == 200:
                    d = st.get("dispense")
                    if isinstance(d, dict) and (d.get("running") is True or d.get("state") == "failed"):
                        return st, None, None
                    started, last = st, "the dispense engine is not running yet"
                else:
                    last = f"HTTP {code}"
            except ApiDown as e:
                last = str(e)
            if self.clock.monotonic() >= end:
                if started is not None:
                    return started, None, None
                return None, f"/api/status did not answer 200 within {self.cfg.status_timeout:g} s ({last})", "build"
            self.clock.sleep(self.cfg.poll_interval)

    def _note_own(self, since):
        """Keep the window in which il_deploy alone drove OpenPnP (a start and its health check; the last 20): what
        OpenPnP stamped then is not a person's activity. Its home sets machine.zCheck.homeAtMs, and a build's first
        start can stamp the config itself (DispenseService: the needle landing 'kept from before the update')."""
        wins = read_json(self.own_path, [])
        wins = [w for w in wins if isinstance(w, list) and len(w) == 2][-19:] if isinstance(wins, list) else []
        try:
            write_json(self.own_path, wins + [[since, self.clock.now()]])
        except OSError as e:
            self.record("own-window-not-kept", error=str(e))

    def _own(self, ms):
        """True if a time OpenPnP reports (epoch ms) falls in one of il_deploy's own windows."""
        wins = read_json(self.own_path, [])
        return isinstance(wins, list) and any(
            isinstance(w, list) and len(w) == 2 and (w[0] - 1) * 1000 <= ms <= (w[1] + 2) * 1000 for w in wins)

    def _log_path(self):
        return os.path.join(self.config, "log", "OpenPnP.log")

    def _connect_lines(self, offset=0):
        """'Serial port: connecting to ...' lines of OpenPnP.log from byte offset on."""
        out = []
        with contextlib.suppress(OSError), open(self._log_path(), "rb") as f:
            if os.fstat(f.fileno()).st_size >= offset:
                f.seek(offset)
            for raw in f:
                line = raw.decode("utf-8", "replace")
                if "Serial port: connecting to " in line:
                    out.append(line.strip())
        return out

    def _gone(self, watch_service):
        """Why OpenPnP is no longer there (its service stopped, or its API refuses connections), else None."""
        if watch_service and not self.svc.is_active():
            return f"{self.cfg.service} is no longer running (exit status {self.svc.exit_status()})"
        try:
            self.api.get("/api/status", timeout=5)
        except ApiTimeout:
            return None
        except ApiDown as e:
            return f"its API refuses connections ({e})"
        return None

    def _serial_pattern(self):
        """The regular expression the controller link's connect line must match: --serial-match, else ' S/N <usb-sn>'
        (SerialPortIdentity.toDisplayString); None (no check) without either."""
        if self.cfg.serial_match:
            return self.cfg.serial_match
        return r"\bS/N " + re.escape(self.cfg.usb_sn) + r"(\s|$)" if self.cfg.usb_sn else None

    def _wait_serial_line(self, offset):
        """The controller link's 'Serial port: connecting to ...' line (SerialPortCommunications.resolvePort) that
        matches _serial_pattern, or None. The enable logs it when it connects. With connection-keep-alive (the lab
        machine.xml) a STOP leaves the link open, and the next enable does not connect again: then the last connect
        line of the current OpenPnP.log names the link, and that file is this OpenPnP's own (tinylog's
        RollingFileWriter starts a new one at each start: StartupPolicy)."""
        rx = re.compile(self._serial_pattern())
        end = self.clock.monotonic() + self.cfg.serial_wait
        while True:
            line = next((l for l in self._connect_lines(offset) if rx.search(l)), None)
            if line:
                return line
            if self.clock.monotonic() >= end:
                break
            self.clock.sleep(self.cfg.poll_interval)
        if self._connect_lines(offset):
            return None  # the enable connected, to something else
        last = (self._connect_lines() or [None])[-1]
        return f"{last} (connected earlier in this OpenPnP's run; the link is kept open)" if last and rx.search(
            last) else None

    def health_check(self, expect_sha7=None, watch_service=False):
        """API up with the expected build, enable, the real port by USB S/N, controller axes (never home
        a reset controller), home (never interrupted), homed, STOP. Returns a JSON-able report."""
        since = self.clock.now()
        try:
            return self._health_check(expect_sha7, watch_service)
        finally:
            self._note_own(since)

    def _lock_taken(self):
        """Why someone else has BR-72's machine lock now (one status read), or None."""
        try:
            code, st = self.api.get("/api/status", timeout=10)
        except ApiDown:
            return None
        return "; ".join(api_lock_reasons(st)) or None if code == 200 else None

    def _origins_check(self, server):
        """(ok, detail, kind): OpenPnP answers each --trusted-origin as the user's browser reaches it through `tailscale
        serve` (its Host and Origin, a POST), and every build still refuses a foreign Origin on its own Host (CSRF) and
        a foreign Host (DNS rebinding). A build from before BR-73 lists none: passed with a note (a rollback to it stays
        possible; the tailnet URL is refused there). An origin the API does not list is the unit's environment, not the
        build ('config': no bad list)."""
        loopback = f"127.0.0.1:{self.cfg.api_port}"
        probes = [(loopback, FOREIGN_ORIGIN, 403, "a foreign Origin is accepted (the CSRF guard is off)"),
                  (FOREIGN_HOST, "http://" + FOREIGN_HOST, 403, "a foreign Host is accepted (the DNS-rebinding guard is off)")]
        listed = server.get("trustedOrigins")
        if listed is not None and not isinstance(listed, list):
            return False, f"server.trustedOrigins is not a list: {listed!r}", "build"
        checked = []
        for text in self.cfg.trusted_origins if listed is not None else ():
            origin, host = normalise_origin(text)
            if origin not in listed:
                return False, (f"server.trustedOrigins {listed} lacks {origin}: OPENPNP_IL_API_TRUSTED_ORIGINS did not "
                               "reach OpenPnP (render and reload the units), or it dropped the entry"), "config"
            probes += [(host, origin, 404, "its own origin is refused"),
                       (host, FOREIGN_ORIGIN, 403, "a foreign Origin is accepted on the tailnet Host (CSRF guard off)")]
            checked.append(origin)
        for host, origin, want, what in probes:
            try:
                code, body = self.api.call("POST", ORIGIN_PROBE, {}, timeout=10, headers={"Host": host, "Origin": origin})
            except ApiDown as e:
                return False, f"the origin probe got no answer ({e})", "build"
            if code != want:
                return False, (f"a POST with Host {host} and Origin {origin} answered HTTP {code}, expected {want}: "
                               f"{what} ({body.get('reason', body)})"), "build"
        if listed is None:
            return True, ("this build has no trusted origins (before BR-73): the tailnet URL is refused "
                          f"({', '.join(self.cfg.trusted_origins)}); a foreign Origin and Host refused"), None
        return True, f"{', '.join(checked)}: accepted with its Host and Origin; a foreign Origin and Host refused", None

    def _health_check(self, expect_sha7, watch_service):
        cfg, steps = self.cfg, []
        rep = {"ok": False, "kind": None, "failure": None, "expected_sha7": expect_sha7,
               "expected_simulated": cfg.expect_simulated, "started_at": self.iso(), "steps": steps}

        def step(name, detail):
            steps.append({"step": name, "ok": True, "detail": detail})

        def fail(name, detail, kind="build", stop=True):
            steps.append({"step": name, "ok": False, "detail": detail})
            rep.update(failure=f"{name}: {detail}", kind=kind, finished_at=self.iso())
            if stop:
                ok, d = self.stop_machine()
                rep["stopped"] = {"ok": ok, "detail": d}
            return rep

        def gone(name, why):
            # OpenPnP's API went away or stopped answering after the enable (the JVM died mid-home, a person stopped
            # the service): the controller may still be finishing a G28 and the outputs keep their state. Never the
            # build's, so the previous build is not started, connected and homed after it unwatched.
            more = self._gone(watch_service)
            return fail(name, f"OpenPnP's API went away after the enable ({why}{'; ' + more if more else ''}); a person "
                              "must check the machine", "machine")

        def claim_lost(name, enabled):
            why = self._claim_lost()
            if why:
                self.record("claim-lost", why=why, step=name)
                return fail(name, why, "claim-lost", stop=enabled)
            return None

        st, why, kind = self._wait_status(watch_service)
        if st is None:
            return fail("status", why, kind, stop=False)
        server = st.get("server") or {}
        version, sim = str(server.get("version", "")), server.get("simulated")
        rep["version"] = version
        if expect_sha7 and not version.endswith("." + expect_sha7):
            return fail("status", f"server.version {version!r} is not build {expect_sha7}", stop=False)
        if sim is not cfg.expect_simulated:
            return fail("status", f"server.simulated is {sim}, expected {cfg.expect_simulated}", stop=False)
        d = st.get("dispense") if isinstance(st.get("dispense"), dict) else {}
        if d.get("running") is not True or d.get("available") is not True:
            # DispenseService.install only logs a failed start: OpenPnP runs on, without its engine
            if d.get("running") is not True and d.get("state") == "failed":
                return fail("status", f"the dispense engine failed to start (dispense.state failed): "
                                      f"{d.get('startError')}", stop=False)
            if d.get("running") is not True and d.get("state") == "starting":
                return fail("status", f"the dispense engine did not start within {cfg.status_timeout:g} s "
                                      "(dispense.state starting)", stop=False)
            return fail("status", f"the dispense engine is not usable (dispense.running {d.get('running')}, "
                                  f"available {d.get('available')}: {d.get('unavailableReason')})", stop=False)
        step("status", f"version {version}, simulated {sim}, dispense engine available")

        if cfg.trusted_origins:
            rep["trusted_origins"] = server.get("trustedOrigins")  # what the API listed (None: a build before BR-73)
            ok, detail, kind = self._origins_check(server)
            if not ok:
                return fail("origins", detail, kind, stop=False)
            step("origins", detail)

        try:
            code, m = self.api.get("/api/machine", timeout=10)
            if code == 200 and m.get("enabled"):
                if m.get("busy") is not False or machine_homing(m):
                    return fail("enable", "the machine is already enabled and busy; not touching it", "machine", False)
                ok, d = self.planned_stop()
                if not ok:
                    taken = d.startswith(PLANNED_LOCKED) or self._lock_taken()
                    if taken:  # someone started it and holds the lock (BR-72): their session, left alone
                        return fail("enable", d if taken is True else f"{d}; {taken}", "claim-lost", stop=False)
                    return fail("enable", f"could not stop the already enabled machine first: {d}",
                                planned_stop_kind(ok, d), stop=False)
            offset = os.path.getsize(self._log_path()) if os.path.exists(self._log_path()) else 0
        except ApiDown as e:
            return fail("enable", str(e))
        lost = claim_lost("enable", False)
        if lost:
            return lost
        try:
            code, body = self.api.post("/api/machine/enable", timeout=cfg.enable_timeout)
        except ApiDown as e:
            return gone("enable", e)
        if code != 200 or body.get("enabled") is not True:
            if code == 409 and body.get("error") in LOCK_REFUSALS:
                # someone took the machine lock after the idle check (BR-72): their session, left alone (no STOP, no
                # rollback over it); the build stays installed, unverified, and the hold asks a person to check
                return fail("enable", f"someone took the machine lock: {body.get('reason', body)}", "claim-lost",
                            stop=False)
            return fail("enable", f"HTTP {code}: {body.get('reason', body)}")
        step("enable", "machine enabled")

        pattern = self._serial_pattern()
        if pattern:
            line = self._wait_serial_line(offset)
            if not line:
                return fail("serial", f"no 'Serial port: connecting to ...' line matching {pattern!r} in "
                                      f"{self._log_path()} since the enable, nor as this OpenPnP's last connect (a link "
                                      "kept open)")
            step("serial", line)

        try:
            code, m = self.api.get("/api/machine", timeout=10)
        except ApiDown as e:
            return gone("axes", e)
        axes = m.get("axes") or []
        coords = {str(a.get("letter")).upper(): a.get("driverCoordinate") for a in axes if a.get("letter")}
        # all four: reset_signature needs B too, and a controller whose position is not known is never homed
        if not all(_ms(coords.get(k)) is not None for k in "XYZB"):
            return fail("axes", f"the controller position was not read (driver coordinates {coords})")
        if reset_signature(axes):
            return fail("axes", "controller was reset; a person must check the machine (X, Y, Z 0 and A or B 720 "
                                "after the start; not homing)", "machine")
        step("axes", coords)

        lost = claim_lost("home", True)
        if lost:
            return lost
        try:
            code, body = self.api.post("/api/machine/home", {"wait": True}, timeout=cfg.home_timeout)
            answer = f"HTTP {code}: {body.get('reason', body)}"
        except ApiTimeout:
            code, body, answer = None, {}, f"no answer within {cfg.home_timeout:g} s"
        except ApiDown as e:
            return gone("home", e)
        late = code is None or str(body.get("reason", "")).startswith(HOME_GAVE_UP)
        if code == 409 and body.get("error") in LOCK_REFUSALS:
            # the home never started: someone took the machine lock after the enable (BR-72): theirs, left alone
            return fail("home", f"someone took the machine lock: {body.get('reason', body)}", "claim-lost", stop=False)
        if code != 200 or body.get("homed") is not True:
            # Never STOP during a home: whatever the answer, wait while the machine is busy. The server answers 409
            # after its own 5 min (HOME_GAVE_UP) while the home may run on; --home-timeout is longer.
            if not self._wait_not_busy(cfg.home_busy_wait):
                if self._gone(watch_service):
                    return gone("home", answer)
                return fail("home", f"the home did not end ({answer}; still busy {cfg.home_busy_wait:g} s later): "
                                    "OpenPnP left alone, a person must check the machine", "home-stuck", stop=False)
            if not late:
                if code != 404 and HOME_TIMED_OUT.search(str(body.get("reason", ""))):
                    # OpenPnP gave up on G28 (its task ended, so busy is false) while the controller may still be in
                    # it: no STOP (its M410 inside G28 kills the board), as for a home that does not end
                    return fail("home", f"OpenPnP timed out on the home ({answer}): the controller may still be "
                                        "homing; no STOP sent, OpenPnP left alone, a person must check the machine",
                                "home-stuck", stop=False)
                # A home that ran and failed (fiducial covered, endstop, obstruction) or was refused (something else
                # holds the machine: the pressure, an engine task, OpenPnP busy) is the machine's: no second home
                # unwatched. Only a missing endpoint is plainly the build's.
                return fail("home", answer, "build" if code == 404 else "machine")
        self._wait_not_busy(min(10.0, cfg.home_busy_wait))  # the task may end a moment after the reply
        try:
            code, m = self.api.get("/api/machine", timeout=10)
        except ApiDown as e:
            return gone("homed", e)
        if m.get("homed") is not True or m.get("busy") is not False:
            return fail("home" if late else "homed", f"homed {m.get('homed')}, busy {m.get('busy')}",
                        "machine" if late else "build")
        step("home", "homed" + (f" (ended after the request: {answer})" if late else ""))
        step("homed", {"homed": True, "busy": False, "positions": m.get("positions")})

        ok, d = self.planned_stop(lambda: api_stop_and_wait(self.api, cfg.stop_timeout, cfg.poll_interval, self.clock))
        if not ok:
            if self._gone(watch_service):
                return gone("stop", d)
            taken = d.startswith(PLANNED_LOCKED) or self._lock_taken()
            if taken:  # the machine re-enabled under someone else's lock (BR-72): their session, left alone
                return fail("stop", d if taken is True else f"{d}; {taken}", "claim-lost", stop=False)
            return fail("stop", d, planned_stop_kind(ok, d), stop=False)
        step("stop", d)
        rep.update(ok=True, finished_at=self.iso())
        return rep

    # --- poll ------------------------------------------------------------------------------------
    def branch_head(self):
        return self.gh.get_json(f"repos/{self.cfg.repo}/git/ref/heads/{self.cfg.branch}")["object"]["sha"]

    def commit_eligible(self, sha, suite, head=None):
        """(ok, why): the sha is the branch head or one of its ancestors, and the 'gate' check-run of the CI run that
        built it passed: the newest one in that run's check suite (a re-run attempt adds its check-runs there). A gate
        of another run on the same sha (a workflow_dispatch re-check, a PR from a fork) neither vetoes nor approves."""
        repo, branch, sha7 = self.cfg.repo, self.cfg.branch, sha[:7]
        head = head or self.branch_head()
        try:
            status = self.gh.get_json(f"repos/{repo}/compare/{head}...{sha}").get("status")
        except GitHubError as e:
            if e.status == 404:
                return False, f"commit {sha7} is not known on GitHub ({e})"
            raise
        if status not in ELIGIBLE_COMPARE:
            return False, (f"never deploy a scratch merge: {sha7} is not on {branch} "
                           f"(compare {branch} {head[:7]}...{sha7}: {status})")
        if not suite:
            return False, f"the CI run that built {sha7} names no check suite, so its 'gate' cannot be told apart"
        # filter=latest: the newest check-run per check suite and name
        runs = self.gh.get_json(f"repos/{repo}/commits/{sha}/check-runs?check_name=gate&filter=latest&per_page=100")
        gate = [r for r in runs.get("check_runs", [])
                if r.get("name") == "gate" and (r.get("check_suite") or {}).get("id") == suite]
        if not gate:
            return False, f"no 'gate' check-run on {sha7} in the check suite {suite} of the CI run that built it"
        r = max(gate, key=lambda r: r.get("id") or 0)
        if r.get("status") != "completed" or r.get("conclusion") != "success":
            return False, (f"the 'gate' check-run of the CI run that built {sha7} is {r.get('status')}/"
                           f"{r.get('conclusion')}, not completed/success")
        return True, f"on {branch} ({status} vs head {head[:7]}), gate success in its CI run"

    def _check_build_info(self, info, tag, sha7):
        if not isinstance(info, dict):
            raise Refused("BUILD-INFO.json is not an object", True)
        if info.get("version") != tag:
            raise Refused(f"BUILD-INFO version {info.get('version')!r} is not the tag {tag}", True)
        if info.get("branch") != self.cfg.branch:
            raise Refused(f"built from branch {info.get('branch')}, not {self.cfg.branch}", True)
        sha = str(info.get("sha", ""))
        if not re.fullmatch(r"[0-9a-f]{40}", sha) or sha[:7] != sha7:
            raise Refused(f"BUILD-INFO sha {sha!r} does not match the tag {tag}", True)
        if not re.fullmatch(r"[0-9a-f]{64}", str(info.get("jar_sha256", ""))):
            raise Refused("BUILD-INFO has no jar_sha256", True)

    def check_provenance(self, rel, info, tag):
        """The artifact is CI's, not only its commit: commit_eligible checks the sha's gate, but a build of a dirty tree
        at the head reports the head's sha (buildnumber doCheck=false), and anyone with push access can publish it under
        a matching tag. So the release must be published by CI's github.token (RELEASE_AUTHOR), and BUILD-INFO.run_id
        must be a completed, successful push run of CI_WORKFLOW on the branch, at the build's sha, with the tag's run
        number (also what keeps a hand-published high number from stalling CI's releases). Refused otherwise:
        permanently, except a run not completed or not successful yet (the release job publishes before its run ends; a
        re-run may pass). Returns the run (its check suite holds the gate commit_eligible reads)."""
        author = (rel.get("author") or {}).get("login")
        if author != RELEASE_AUTHOR:
            raise Refused(f"release {tag} was published by {author!r}, not by CI ({RELEASE_AUTHOR})", True)
        run_id = str(info.get("run_id", ""))
        if not re.fullmatch(r"[0-9]+", run_id):
            raise Refused(f"BUILD-INFO run_id {info.get('run_id')!r} is not a CI run id", True)
        try:
            run = self.gh.get_json(f"repos/{self.cfg.repo}/actions/runs/{run_id}")
        except GitHubError as e:
            if e.status == 404:
                raise Refused(f"BUILD-INFO names CI run {run_id}, which {self.cfg.repo} does not have", True)
            raise
        want = {"event": "push", "head_branch": self.cfg.branch, "head_sha": info["sha"], "path": CI_WORKFLOW,
                "run_number": parse_tag(tag)[0]}
        got = dict(run, path=str(run.get("path") or "").split("@")[0])
        bad = [f"{k} {got.get(k)!r}, not {v!r}" for k, v in want.items() if got.get(k) != v]
        if bad:
            raise Refused(f"CI run {run_id} is not the {CI_WORKFLOW} push to {self.cfg.branch} that built {tag}: "
                          + "; ".join(bad), True)
        if run.get("status") != "completed" or run.get("conclusion") != "success":
            raise Refused(f"CI run {run_id} is {run.get('status')}/{run.get('conclusion')}, not completed/success")
        return run

    def _check_tree(self, d, tag, info):
        jar = os.path.join(d, "openpnp-il.jar")
        if not os.path.isfile(jar) or not os.path.isdir(os.path.join(d, "lib")):
            raise Refused("the tarball lacks openpnp-il/openpnp-il.jar or openpnp-il/lib/", True)
        try:
            with open(os.path.join(d, "VERSION.txt")) as f:
                version = f.read().strip()
        except OSError:
            version = None
        if version != tag:
            raise Refused(f"VERSION.txt is {version!r}, not {tag}", True)
        inner = read_json(os.path.join(d, "BUILD-INFO.json"))
        if not isinstance(inner, dict) or inner.get("sha") != info["sha"] or inner.get("version") != tag:
            raise Refused("the tarball's BUILD-INFO.json differs from the release's", True)
        got = sha256_file(jar)
        if got != info["jar_sha256"]:
            raise Refused(f"jar sha256 {got[:12]}... differs from BUILD-INFO jar_sha256", True)

    @staticmethod
    def _verify_sha256(path, sum_path, name):
        with open(sum_path) as f:
            first = next((l for l in f.read().splitlines() if l.strip()), "")
        m = re.match(r"^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$", first)
        if not m or os.path.basename(m.group(2)) != name:
            raise Refused(f"{name}.sha256 is not in sha256sum format for {name}")
        got = sha256_file(path)
        if got != m.group(1).lower():
            raise Refused(f"sha256 mismatch for {name}: {got[:12]}... vs {m.group(1)[:12]}...")

    def stage(self, rel, head):
        """Download, verify and unpack a release into releases/<tag>/ (one rename), with the attested tarball kept
        there for the deploy's re-check. Refused if ineligible."""
        tag = rel["tag_name"]
        sha7 = parse_tag(tag)[1]
        assets = {a.get("name"): a for a in rel.get("assets") or []}
        tar_name = f"openpnp-il-{sha7}.tar.gz"
        missing = [n for n in (tar_name, tar_name + ".sha256", "BUILD-INFO.json") if n not in assets]
        if missing:
            raise Refused(f"release {tag} lacks {', '.join(missing)}", True)
        author = (rel.get("author") or {}).get("login")
        if author != RELEASE_AUTHOR:  # before any download (check_provenance says it again with the run)
            raise Refused(f"release {tag} was published by {author!r}, not by CI ({RELEASE_AUTHOR})", True)
        os.makedirs(self.releases, exist_ok=True)
        work = tempfile.mkdtemp(prefix=f".stage-{tag}-", dir=self.releases)
        try:
            bi = os.path.join(work, "BUILD-INFO.json")
            self.gh.download(assets["BUILD-INFO.json"], bi)
            info = read_json(bi)
            self._check_build_info(info, tag, sha7)
            if rel.get("target_commitish") != info["sha"]:
                raise Refused(f"target_commitish {rel.get('target_commitish')!r} is not the build sha {info['sha']}",
                              True)
            run = self.check_provenance(rel, info, tag)
            ok, why = self.commit_eligible(info["sha"], run.get("check_suite_id"), head)
            if not ok:
                raise Refused(why)
            self.attest.ready()  # before the download: no gh, no point fetching the tarball
            tar = os.path.join(work, tar_name)
            self.gh.download(assets[tar_name], tar)
            self.gh.download(assets[tar_name + ".sha256"], tar + ".sha256")
            self._verify_sha256(tar, tar + ".sha256", tar_name)
            attested = self.attest.verify(tar, self.cfg.repo, self.cfg.branch, info["sha"], info["run_id"])
            out = os.path.join(work, "x")
            try:
                with tarfile.open(tar, "r:gz") as tf:
                    names = tf.getnames()
                    if not names or any(n != "openpnp-il" and not n.startswith("openpnp-il/") for n in names):
                        raise Refused("the tarball must hold only openpnp-il/", True)
                    tf.extractall(out, filter="data")
            except (tarfile.TarError, OSError) as e:
                raise Refused(f"the tarball cannot be unpacked: {e}", True)
            d = os.path.join(out, "openpnp-il")
            self._check_tree(d, tag, info)
            os.rename(tar, os.path.join(d, tar_name))
            write_json(os.path.join(d, "STAGED.json"), {"tag": tag, "sha": info["sha"], "staged_at": self.iso(),
                                                        "release": rel.get("html_url"), "eligible": why,
                                                        "attestation": attested})
            os.rename(d, os.path.join(self.releases, tag))
            self.record("staged", tag=tag, sha=info["sha"], why=why, attestation=attested)
            return info
        finally:
            shutil.rmtree(work, ignore_errors=True)

    def poll(self):
        cfg = self.cfg
        cands = []
        for r in self.gh.get_json(f"repos/{cfg.repo}/releases?per_page=30"):
            t = parse_tag(r.get("tag_name", ""))
            if t and not r.get("draft"):
                cands.append((t[0], r))
        cands.sort(key=lambda c: -c[0])
        bad, refused = self.read_bad(), read_json(self.refused_path, {}) or {}
        result, head = {"staged": None, "newest_staged": None, "refused": []}, None
        for _, rel in cands[:10]:
            tag = rel["tag_name"]
            if os.path.isdir(os.path.join(self.releases, tag)):
                result["newest_staged"] = tag
                break
            if tag in bad or (refused.get(tag) or {}).get("permanent"):
                continue
            try:
                head = head or self.branch_head()
                self.stage(rel, head)
            except (Refused, GitHubError) as e:
                reason = str(e) if isinstance(e, Refused) else f"GitHub: {e}"
                permanent = getattr(e, "permanent", False)
                if (refused.get(tag) or {}).get("reason") != reason:
                    self.record("refused", tag=tag, reason=reason, permanent=permanent)
                    self.say(f"poll: refused {tag}: {reason}")
                refused[tag] = {"reason": reason, "permanent": permanent, "at": self.iso()}
                result["refused"].append({"tag": tag, "reason": reason})
                continue
            refused.pop(tag, None)
            result["staged"] = tag
            self.say(f"poll: staged {tag}")
            break
        write_json(self.refused_path, refused)
        if not result["staged"]:
            self.say(f"poll: no new eligible build (newest staged: {result['newest_staged'] or 'none'})")
        return result

    # --- deploy / rollback -------------------------------------------------------------------------
    def pick_target(self, cur):
        bad = self.read_bad()
        cur_n = parse_tag(cur)[0] if parse_tag(cur) else -1
        for t in self.staged():
            if parse_tag(t)[0] <= cur_n:
                break
            if t not in bad:
                return t
        return None

    def verify_staged(self, tag):
        d = os.path.join(self.releases, tag)
        info = read_json(os.path.join(d, "BUILD-INFO.json"))
        t = parse_tag(tag)
        if not t:
            raise Refused(f"{tag} is not an il-build tag")
        self._check_build_info(info, tag, t[1])
        self._check_tree(d, tag, info)
        tar = self.kept_tarball(tag)
        if not os.path.isfile(tar):
            raise Refused(f"the staged folder has no {os.path.basename(tar)}, the attested tarball it was unpacked "
                          f"from (placed by hand?): delete releases/{tag} and poll again")
        return info

    def kept_tarball(self, tag):
        return os.path.join(self.releases, tag, f"openpnp-il-{parse_tag(tag)[1]}.tar.gz")

    def verify_on_github(self, tag, info):
        """The staged folder must be the GitHub release's build: its BUILD-INFO from the release, compared by sha,
        jar_sha256 and run_id (a folder copied in by hand agrees only with itself), the release CI's (check_provenance,
        again: a release can be deleted and published again by hand under its tag), and the tarball it was unpacked
        from still attested by that run (an attestation can be deleted, e.g. to revoke a build). Returns the run."""
        try:
            rel = self.gh.get_json(f"repos/{self.cfg.repo}/releases/tags/{tag}")
        except GitHubError as e:
            if e.status == 404:
                raise Refused(f"there is no GitHub release {tag} in {self.cfg.repo}: a folder placed by hand?")
            raise
        asset = next((a for a in rel.get("assets") or [] if a.get("name") == "BUILD-INFO.json"), None)
        if asset is None:
            raise Refused(f"the GitHub release {tag} has no BUILD-INFO.json")
        os.makedirs(self.releases, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".verify-", dir=self.releases) as d:
            self.gh.download(asset, os.path.join(d, "BUILD-INFO.json"))
            remote = read_json(os.path.join(d, "BUILD-INFO.json")) or {}
        for k in ("sha", "jar_sha256", "run_id"):
            if remote.get(k) != info.get(k):
                raise Refused(f"the staged folder is not the GitHub release's build: its {k} "
                              f"{str(info.get(k))[:12]} differs from {str(remote.get(k))[:12]}")
        run = self.check_provenance(rel, remote, tag)
        self.attest.verify(self.kept_tarball(tag), self.cfg.repo, self.cfg.branch, remote["sha"], remote["run_id"])
        return run

    def deploy(self, version=None, auto=False):
        """The timer's deploy (auto, from run) or one by hand. By hand, whatever its result, a person is at the
        machine: the timer's next deploy waits the quiet period after it."""
        try:
            return self._deploy_command(version, auto)
        finally:
            if not auto:
                self.note_person("deploy")

    def _deploy_command(self, version, auto):
        cur = self.current_tag()
        rec = (self.read_deployed() or {}).get("current") or {}
        if rec.get("pending") and rec.get("tag") == cur:
            return self.refuse(f"the current {cur} is not verified ({rec['pending']}): after checking the machine, "
                               "`il_deploy.py health` verifies it, or `il_deploy.py rollback` goes back")
        if version:
            tag = version
            if not os.path.isdir(os.path.join(self.releases, tag)):
                return self.refuse(f"{tag} is not staged (run poll first)")
            t, c = parse_tag(tag), parse_tag(cur)
            if t and c and t[0] < c[0]:
                return self.refuse(f"{tag} is older than the current {cur}, whose start migrated the config one "
                                   "way: an older build must not run on it. `il_deploy.py rollback` goes back one "
                                   "step with the matching config snapshot")
        else:
            tag = self.pick_target(cur)
            if tag is None:
                self.say(f"nothing to deploy (current {cur or 'none'})")
                return 0
        if tag == cur:
            self.say(f"{tag} is deployed already")
            return 0
        try:
            info = self.verify_staged(tag)
        except Refused as e:
            return self.refuse(f"{tag}: {e}")
        budget = self.shutdown_budget_reasons()
        if budget:
            return self.refuse(budget[0])
        reasons = self.idle_reasons() + (self._person_reasons() if auto else [])
        if reasons:
            return self.not_idle(reasons)
        try:
            run = self.verify_on_github(tag, info)
            ok, why = self.commit_eligible(info["sha"], run.get("check_suite_id"))
        except Refused as e:
            return self.refuse(f"{tag}: {e}")
        except GitHubError as e:
            return self.refuse(f"{tag}: cannot verify it on GitHub: {e}")
        if not ok:
            return self.refuse(f"{tag}: {why}")
        return self.with_claim("deploy", tag, lambda claim: self._deploy(tag, info, cur, bool(version)))

    def _deploy(self, tag, info, cur, forced):
        was_running = self.svc.is_active()
        exited = None if was_running else self.check_openpnp_exit()  # it may have died since the claim's check
        if exited:
            return 5, exited
        before = {"current": self._link("current"), "previous": self._link("previous"),
                  "deployed": self.read_deployed()}
        self.record("begin", action="deploy", tag=tag, current=cur, was_running=was_running)
        lost = self._claim_lost()
        if lost:
            return self._abort_claim_lost(lost, "nothing changed")
        if was_running:
            r = self.stop_service()
            if r:
                return r
        snap = self.snapshot(f"pre-{tag}")
        # Written before the switch, so that whatever ends this run (a SIGKILL too) leaves the truth: rollback finds
        # the unverified build, and STATE.md does not name the previous build while this one is installed.
        rec = {"tag": tag, "sha": info["sha"], "run_url": info.get("run_url"), "pre_snapshot": snap, "previous": cur,
               "switched_at": self.iso(), "pending": "installed; its health check has not passed",
               "before": before["deployed"]}
        write_json(self.deployed_path, {"current": rec, "previous": _record_for(before["deployed"], cur)})
        self.state_line(f"{tag} being installed{' over ' + cur if cur else ''} since {self.stamp()}: NOT verified "
                        "until its health check passes (if this line stays, the deploy was cut short: a person must "
                        "check the machine, then `il_deploy.py health` or `il_deploy.py rollback`).")
        self.set_links(os.path.join(self.releases, tag), before["current"])
        rep = self.start_and_check(info["sha"][:7])
        if not rep["ok"]:
            return self._recover(tag, rep, before, snap, was_running)
        self.restore_running(was_running)
        self._verified(rec)
        if forced:
            self.unbad(tag)
        self.state_line(f"{self.describe(tag)} since {self.stamp()}, deployed by Infra (il_deploy.py)"
                        + (f"; previous {cur}" if cur else "") + f". Config state: {self.config}.")
        self.record("deployed", tag=tag, previous=cur)
        self.prune()
        self.say(f"deployed {tag}" + (f" (previous {cur})" if cur else ""))
        return 0, None

    def _verified(self, rec):
        """The current build passed its health check: drop 'pending' (and 'unchecked') and keep the config's
        manifest, the baseline a later rollback compares with."""
        rec = _settled(rec)
        newest = []
        rec.update(deployed_at=self.iso(), since=self.stamp(), post_manifest=config_manifest(self.config, newest=newest),
                   post_events_id=newest[0])
        write_json(self.deployed_path, dict(self.read_deployed() or {}, current=rec))

    def _start_unchecked(self):
        """Start OpenPnP again, not enabled, and say whether it runs: its API answers (flock exits 75 a moment after a
        successful start when the serial lock is taken)."""
        ok, why = self._start_not_enabled()
        return "; OpenPnP runs again, machine not enabled" if ok else f"; OpenPnP ran before but did not start again ({why})"

    def _start_not_enabled(self):
        """Start OpenPnP and wait until its API answers; nothing is enabled or homed. (ok, detail)."""
        since = self.clock.now()
        try:
            self.svc.start()
        except (ServiceError, OSError, subprocess.SubprocessError) as e:
            self.record("start", ok=False, error=str(e))
            return False, str(e)
        try:
            st, why, _ = self._wait_status(True)
        finally:
            self._note_own(since)
        if st is None:
            self.record("start", ok=False, checked=False, error=why)
            return False, why
        self.record("start", ok=True, checked=False)
        return True, "OpenPnP runs, machine not enabled"

    def _recover(self, tag, rep, before, snap, was_running):
        """The new build failed its health check. A machine problem puts the previous build back with its config
        but unchecked, so nothing moves before a person looks. Otherwise the previous build is started and
        checked; the new one goes on the bad list only if the previous one passes (else the machine is suspect)."""
        failure, kind = rep["failure"], rep["kind"]
        prev = os.path.basename(before["current"]) if before["current"] else None
        back = f"; `il_deploy.py rollback` returns to {prev} with its config" if prev else ""
        if kind in ("home-stuck", "claim-lost"):
            # a home that may still run, or someone else's Holder: nothing more is done to OpenPnP
            note = f"{tag} health check: {failure}"
            self.set_hold(note)
            self.state_line(f"{tag} installed but NOT verified: its health check did not finish ({failure}); "
                            f"OpenPnP left running, a person must check the machine{back}.")
            return 5, note
        if not self.stop_after_failure():
            note = f"{tag} failed its health check ({failure}) and OpenPnP could not be stopped safely; left alone"
            self.set_hold(note)
            self.state_line(f"{tag} installed but NOT verified: {note}; a person must check the machine{back}.")
            return 5, note
        if prev is None:
            self.restore_and_switch(snap, f"abandoned-{tag}", None, None, before["deployed"])
            self.state_line(f"nothing deployed by il_deploy: the first deploy, {tag}, failed its health check at "
                            f"{self.stamp()} ({failure}) and was removed.")
            self.record("rolled-back", tag=tag, to=None)
            if kind == "build":
                self.add_bad(tag, failure)
            else:
                self.set_hold(f"{tag}: {failure}")
            return (5 if self.held else 4), f"{tag} failed its health check ({failure}); nothing deployed before it"
        dep = dict(before["deployed"] or {})
        prev_rec = _settled(dep.get("current") or {"tag": prev, "sha": self.build_info(prev).get("sha")})
        if kind != "build":
            note = f"{tag}'s health check hit a machine problem: {failure}"
            self.set_hold(note)
            # the verified build with its own config, but nothing checked it since: a passing health check says so
            self.restore_and_switch(snap, f"abandoned-{tag}", before["current"], before["previous"], dict(
                dep, current=dict(prev_rec, unchecked=(f"put back with its config at {self.stamp()} after {tag}'s "
                                                       "machine problem; not checked since"))))
            started = self._start_unchecked() if was_running else ""
            self.state_line(f"{self.describe(prev)} reinstalled with its config, not checked: at {self.stamp()} "
                            f"{tag}'s health check hit a machine problem ({failure}); a person must check the "
                            f"machine{started}.")
            self.record("rolled-back", tag=tag, to=prev, checked=False)
            return 5, note
        # started and checked again: unverified until that check passes, whatever ends this run
        self.restore_and_switch(snap, f"abandoned-{tag}", before["current"], before["previous"], dict(
            dep, current=dict(prev_rec, pending=f"reinstalled after {tag} failed its health check; its own check has "
                                                "not passed")))
        self.state_line(f"{prev} reinstalled since {self.stamp()} after {tag} failed its health check ({failure}): "
                        "NOT verified until its own health check passes (if this line stays, a person must check the "
                        "machine, then `il_deploy.py health`).")
        rep2 = self.start_and_check((self.build_info(prev).get("sha") or "")[:7])
        if rep2["ok"]:
            write_json(self.deployed_path, dict(dep, current=prev_rec))
            self.add_bad(tag, failure)
            self.restore_running(was_running)
            since = prev_rec.get("since", "?")
            self.state_line(f"{self.describe(prev)} since {since}; at {self.stamp()} {tag} failed its health check "
                            f"({failure}) and was rolled back automatically.")
            self.record("rolled-back", tag=tag, to=prev)
            return (5 if self.held else 4), f"{tag} failed its health check, rolled back to {prev}"
        left = self._left_after(rep2)
        note = (f"{tag} failed its health check ({failure}) and so did {prev} ({rep2['failure']}): the machine is "
                f"suspect, {tag} is not marked bad; {left}, a person must check the machine")
        self.set_hold(note)
        self.state_line(f"{prev} installed but NOT verified: {note} (log: {self.journal}).")
        self.record("rollback-failed", tag=tag, to=prev)
        return 5, note

    def rollback(self, discard=False):
        """Always by hand: whatever its result, a person is at the machine (they may stay to check the build it went
        back to), so the timer's next deploy waits the quiet period after it."""
        try:
            return self._rollback_command(discard)
        finally:
            self.note_person("rollback")

    def _rollback_command(self, discard):
        cur, prev, dep = self.current_tag(), self.previous_tag(), self.read_deployed() or {}
        rec = dep.get("current") or {}
        if not prev or rec.get("tag") != cur:
            return self.refuse("no previous version to roll back to")
        snap = rec.get("pre_snapshot")
        if not snap or not os.path.isdir(os.path.join(self.cfg.root, snap)):
            return self.refuse(f"the pre-deploy snapshot of {cur} ({snap}) is missing")
        base = rec.get("post_manifest")
        # the engine's routine journal written while this deployer held the machine is no change (#12); a baseline
        # kept by bytes is compared by bytes
        rows = base is not None and not ({ENGINE_DB, ENGINE_DB + "-wal"} & set(base))
        journal = (rec.get("post_events_id"), claim_ranges(self.journal, since=rec.get("deployed_at"))) if rows else None
        changes = None if base is None else manifest_changes(base, config_manifest(self.config, journal, rows))
        if changes != [] and not discard:
            why = (f"{cur} never passed its health check, so nothing shows what changed in {self.config} since it "
                   f"was installed ({rec.get('switched_at')})" if changes is None else
                   f"{self.config} changed since {cur} was deployed: {', '.join(changes)}")
            return self.refuse(f"{why}. A rollback puts back the config from before {cur} ({snap}) and would undo "
                               "that (the current one is kept under snapshots/). To go ahead: il_deploy.py rollback "
                               "--discard-config-changes")
        budget = self.shutdown_budget_reasons()
        if budget:
            return self.refuse(budget[0])
        reasons = self.idle_reasons()
        if reasons:
            return self.not_idle(reasons)
        return self.with_claim("rollback", f"to {prev}", lambda claim: self._rollback(cur, prev, dep, snap, changes))

    def _rollback(self, cur, prev, dep, snap, changes):
        was_running = self.svc.is_active()
        exited = None if was_running else self.check_openpnp_exit()  # it may have died since the claim's check
        if exited:
            return 5, exited
        self.record("begin", action="rollback", tag=cur, to=prev, was_running=was_running, discarded=changes)
        lost = self._claim_lost()
        if lost:
            return self._abort_claim_lost(lost, "nothing changed")
        if was_running:
            r = self.stop_service()
            if r:
                return r
        prev_rec = {k: v for k, v in _settled(_record_for(dep, prev) or {"tag": prev,
                                                                         "sha": self.build_info(prev).get("sha")})
                    .items() if k != "post_manifest"}
        prev_rec.update(deployed_at=self.iso(), since=self.stamp(), rolled_back_from=cur)
        # the config, the binary and deployed.json go back in one recorded step: whatever ends this run, they agree
        abandoned = self.restore_and_switch(snap, f"abandoned-{cur}", os.path.join(self.releases, prev), None, {
            "current": dict(prev_rec, pending=f"reinstalled by a rollback from {cur}; its health check has not passed"),
            "previous": None})
        self.state_line(f"{prev} being reinstalled by a rollback from {cur} since {self.stamp()}: NOT verified until "
                        "its health check passes (if this line stays, the rollback was cut short: a person must check "
                        "the machine, then `il_deploy.py health`).")
        self.add_bad(cur, "rolled back by 'il_deploy.py rollback'")
        rep = self.start_and_check((self.build_info(prev).get("sha") or "")[:7])
        if rep["ok"]:
            write_json(self.deployed_path, {"current": prev_rec, "previous": None})
            self.restore_running(was_running)
            lost = ("no config change since the deploy was lost" if changes == [] else
                    "config changes since the deploy discarded: " + ", ".join(changes) if changes else
                    f"config changes since {cur} was installed discarded (not compared: it was never verified)")
            self.state_line(f"{self.describe(prev)} since {self.stamp()}, rolled back from {cur} by Infra "
                            f"(il_deploy.py rollback): config back to its state before {cur}, {lost}; {cur}'s "
                            f"config is kept in {abandoned}.")
            self.record("rolled-back", tag=cur, to=prev)
            self.say(f"rolled back from {cur} to {prev}")
            return 0, (None if changes == [] else
                       f"discarded config changes kept in {abandoned}: " + ", ".join(changes or ["not compared"]))
        left = self._left_after(rep)
        note = (f"the rollback from {cur} to {prev} failed its health check ({rep['failure']}); {left}, a person must "
                "check the machine")
        self.set_hold(note)
        self.state_line(f"{prev} installed but NOT verified: {note} (log: {self.journal}).")
        return 5, note

    def _left_after(self, rep):
        """What is done with OpenPnP after a second failed check: left alone mid-home or with someone else's Holder,
        else stopped if that is safe."""
        if rep["kind"] == "home-stuck":
            return "OpenPnP left alone mid-home"
        if rep["kind"] == "claim-lost":
            return "OpenPnP left running (the Holder is no longer il_deploy's)"
        return "OpenPnP stopped" if self.stop_after_failure() else "OpenPnP could not be stopped safely, left alone"

    # --- state snapshots -------------------------------------------------------------------------
    def _snapname(self, label):
        base = f"{self.stamp('%Y%m%d-%H%M%S')}-{label}"
        name, k = base, 2
        while os.path.exists(os.path.join(self.snapshots, name)):
            name, k = f"{base}-{k}", k + 1
        return name

    def snapshot(self, label):
        """Copy state/config (OpenPnP stopped: db, -wal and -shm together) to snapshots/<ts>-<label>/."""
        os.makedirs(self.snapshots, exist_ok=True)
        name = self._snapname(label)
        tmp = os.path.join(self.snapshots, f".{name}.tmp")
        shutil.rmtree(tmp, ignore_errors=True)
        shutil.copytree(self.config, tmp, symlinks=True)
        os.rename(tmp, os.path.join(self.snapshots, name))
        rel = os.path.join("snapshots", name)
        self.record("snapshot", path=rel)
        return rel

    def _copy_snapshot(self, snap_rel):
        """Copy a config snapshot next to the live config (state/.config.restore); nothing live changes."""
        tmp = os.path.join(self.state, ".config.restore")
        shutil.rmtree(tmp, ignore_errors=True)
        try:
            shutil.copytree(os.path.join(self.cfg.root, snap_rel), tmp, symlinks=True)
        except BaseException:
            shutil.rmtree(tmp, ignore_errors=True)
            raise
        return tmp

    def restore_and_switch(self, snap_rel, label, current, previous, deployed):
        """Put a config snapshot back and switch the links as one recorded step, with deployed.json (None: removed).
        The copy comes first (cut there, nothing changed). Then switch.json records the whole step before the first
        rename, and the renames, the links and deployed.json follow with SIGTERM and SIGINT deferred. Cut anyway (an
        error, SIGKILL), the record finishes it: _after_exception, or the next command (recover_stale_claim). So the
        config, the binary and the records never disagree. Returns the abandoned config's path under the root."""
        tmp = self._copy_snapshot(snap_rel)
        os.makedirs(self.snapshots, exist_ok=True)
        abandoned = os.path.join(self.snapshots, self._snapname(label))
        marker = {"snapshot": snap_rel, "tmp": tmp, "abandoned": abandoned, "current": current, "previous": previous,
                  "deployed": deployed, "at": self.iso()}
        with signals_deferred():
            write_json(self.switch_path, marker)
            self._finish_switch(marker)
        return os.path.relpath(abandoned, self.cfg.root)

    def _finish_switch(self, m):
        """Carry out, or finish, the restore and switch switch.json records (idempotent)."""
        tmp, abandoned = m["tmp"], m["abandoned"]
        if os.path.isdir(tmp):
            if os.path.lexists(self.config):
                if os.path.lexists(abandoned):
                    raise OSError(f"{self.config}, {tmp} and {abandoned} all exist: not finishing the switch")
                os.rename(self.config, abandoned)
            os.rename(tmp, self.config)
        elif not os.path.lexists(self.config):
            # the restored copy is gone with the live config moved away: put that back, and switch nothing
            if os.path.lexists(abandoned):
                os.rename(abandoned, self.config)
            os.unlink(self.switch_path)
            raise OSError(f"the restored copy {tmp} is gone: the config before the switch is back, nothing switched")
        self.set_links(m["current"], m["previous"])
        if m["deployed"] is None:
            with contextlib.suppress(FileNotFoundError):
                os.unlink(self.deployed_path)
        else:
            write_json(self.deployed_path, m["deployed"])
        os.unlink(self.switch_path)
        self.record("restore", snapshot=m["snapshot"], abandoned=os.path.relpath(abandoned, self.cfg.root),
                    current=m["current"] and os.path.basename(m["current"]))

    def prune(self):
        dep = self.read_deployed() or {}
        keep = {os.path.basename((dep.get(k) or {}).get("pre_snapshot") or "") for k in ("current", "previous")}
        with contextlib.suppress(OSError):
            # an abandoned config may hold a day's calibration: a person deletes it, never this
            snaps = sorted(n for n in os.listdir(self.snapshots) if not n.startswith(".") and "-abandoned-" not in n)
            for n in snaps[:-self.cfg.keep_snapshots]:
                if n not in keep:
                    shutil.rmtree(os.path.join(self.snapshots, n), ignore_errors=True)
        live = {self.current_tag(), self.previous_tag()}
        for t in self.staged()[self.cfg.keep_releases:]:
            if t not in live:
                shutil.rmtree(os.path.join(self.releases, t), ignore_errors=True)

    # --- the other commands ----------------------------------------------------------------------
    def keep_up(self):
        """The real machine's web UI is up whenever nobody holds the machine (the user's P0, 11-03): start OpenPnP, never
        enabled or homed, when it is down and the machine is free. A bench session (a Holder) keeps it stopped; a hold
        (a person must check) too: an exit il_deploy did not cause sets it before this runs (main), so a crash is not
        restarted over a machine whose valve and pump may still be on. 0 started or running, 3 not free, 1 failed."""
        if self.svc.is_active():
            return 0
        exited = self.check_openpnp_exit()  # it may have gone down after main's check: never restart over a crash
        if exited:
            self.say(exited)
            return 5
        reasons = self.keep_up_reasons()
        if reasons:
            self.say("OpenPnP is down; not starting it: " + "; ".join(reasons))
            return 3
        late = self._hold_or_holder()  # again, just before the start: a session may have taken the Holder meanwhile
        if late:
            self.say("OpenPnP is down; not starting it: " + late)
            return 3
        ok, detail = self._start_not_enabled()
        self.record("keep-up", ok=ok, detail=detail)
        self.say(f"OpenPnP was down with the machine free: {detail}" if ok else
                 f"OpenPnP was down with the machine free but did not start: {detail}")
        return 0 if ok else 1

    def _hold_or_holder(self):
        """Two file reads: why the hold or STATE.md's Holder keeps OpenPnP down, or None."""
        if self.read_hold():
            return "on hold (after a person has checked the machine: il_deploy.py resume)"
        try:
            with open(self.cfg.state_md, encoding="utf-8") as f:
                h = parse_holder(f.read())
        except OSError as e:
            return f"STATE.md unreadable ({self.cfg.state_md}): {e.strerror}"
        return None if h.idle else h.reason

    def keep_up_reasons(self):
        """Why OpenPnP must stay down ([] = start it): a hold, a Holder, the serial lock or device taken, another
        OpenPnP or something else on the API port. No quiet period: a start moves nothing."""
        hold = self.read_hold()
        if hold:
            since, why = self.hold_reasons(hold if isinstance(hold, dict) else {})
            return [f"on hold since {since}: {why} (after a person has checked the machine: il_deploy.py resume)"]
        try:
            with open(self.cfg.state_md, encoding="utf-8") as f:
                h = parse_holder(f.read())
        except OSError as e:
            return [f"STATE.md unreadable ({self.cfg.state_md}): {e.strerror}"]
        if not h.idle:
            return [h.reason]  # a bench session: its tools hold the port; nothing else to look at
        if not os.path.exists(os.path.join(self.cfg.root, "current")):
            return ["nothing deployed yet (no current build)"]
        reasons = self._lock_reasons(set()) + self._device_reasons(set())
        for pid, d, real in self.procs.openpnp_processes():
            if real:
                reasons.append(f"another OpenPnP with a real-machine config runs (pid {pid}, config {d})")
        try:
            self.api.get("/api/status", timeout=3)
            reasons.append(f"something answers on 127.0.0.1:{self.cfg.api_port} but {self.cfg.service} is not running")
        except ApiDown:
            pass
        return reasons

    def run(self):
        try:
            self.poll()
        except GitHubError as e:
            self.say(f"poll failed: {e}")
            self.record("poll-failed", error=str(e))
            return 1
        return self.deploy(auto=True)

    def health(self, sha7=None, no_claim=False):
        """The health check by hand. Whatever its result, a person is at the machine: the quiet period starts after
        it."""
        try:
            return self._health(sha7, no_claim)
        finally:
            self.note_person("health")

    def _health(self, sha7, no_claim):
        self.out = sys.stderr
        taken = self._lock_taken() if self.svc.is_active() else None
        if taken:
            return self.refuse(f"{taken}: give the machine back first (Give back in the web UI, or the agent releases "
                               "it), then run the health check")
        sha7 = sha7 or (self.build_info(self.current_tag()).get("sha") or "")[:7] or None
        if no_claim:
            try:
                code, st = self.api.get("/api/status", timeout=10)
            except ApiDown as e:
                return self.not_idle([f"OpenPnP's API does not answer ({e})"])
            reasons = api_busy_reasons(st) if code == 200 else [f"/api/status answered HTTP {code}"]
            if reasons:
                return self.not_idle(reasons)
            self.record("health-window", phase="begin", events_id=engine_db_newest_event(self.config))
            try:
                rep = self.health_check(sha7)
            finally:
                self.record("health-window", phase="end", events_id=self._settled_events_id())
            self._settle_pending(rep)
            print(json.dumps(rep, indent=1, default=str))
            return 0 if rep["ok"] else 1
        reasons = self.idle_reasons(scope="health")
        if reasons:
            return self.not_idle(reasons)
        out = {}

        def check(claim):
            rep = out["rep"] = self.health_check(sha7)
            self.record("health", ok=rep["ok"], kind=rep["kind"], failure=rep["failure"], report=rep)
            if rep["ok"]:
                self._settle_pending(rep)
                return 0, None
            if rep["kind"] in ("machine", "home-stuck", "claim-lost"):
                self.set_hold(f"health check: {rep['failure']}")
            return 1, f"health check failed: {rep['failure']}"

        rc = self.with_claim("health check", "", check, scope="health")
        if "rep" in out:
            print(json.dumps(out["rep"], indent=1, default=str))
        return rc

    def _settle_pending(self, rep):
        """A passed health check of a current build that was not verified (pending: its deploy, reinstall or rollback
        did not finish its check) or not checked since a machine problem (unchecked) verifies it, and says so in
        STATE.md."""
        cur = self.current_tag()
        rec = (self.read_deployed() or {}).get("current") or {}
        sha7 = (rec.get("sha") or "")[:7]
        why = rec.get("pending") or rec.get("unchecked")
        if not (rep["ok"] and why and rec.get("tag") == cur and sha7
                and str(rep.get("version", "")).endswith("." + sha7)):
            return
        self._verified(rec)
        prev = rec.get("previous")
        self.state_line(f"{self.describe(cur)} since {self.stamp()}, verified by Infra's health check (il_deploy.py "
                        "health)" + (f"; previous {prev}" if prev else "") + f". Config state: {self.config}.")
        self.record("verified", tag=cur, was=why)

    def status(self):
        def info(tag):
            if not tag:
                return None
            bi = self.build_info(tag)
            return {"tag": tag, "sha": bi.get("sha"), "run_url": bi.get("run_url")}

        last = None
        with contextlib.suppress(OSError), open(self.journal) as f:
            for line in f:
                with contextlib.suppress(ValueError):
                    r = json.loads(line)
                    if r.get("event") == "result":
                        last = r
        reasons = self.idle_reasons()
        try:
            exited = self.svc.last_exit()
        except (OSError, subprocess.SubprocessError, ValueError) as e:
            exited = {"error": str(e)}
        print(json.dumps({"root": self.cfg.root, "current": info(self.current_tag()),
                          "previous": info(self.previous_tag()), "deployed": self.read_deployed(),
                          "service_active": self.svc.is_active(), "idle": not reasons, "reasons": reasons,
                          "hold": self.read_hold(), "bad": self.read_bad(), "staged": self.staged(),
                          "claim": read_json(self.claim_path), "shutdown_budget": self.shutdown_budget_reasons(),
                          "openpnp_exit": exited, "openpnp_exit_seen": read_json(self.exit_seen_path),
                          "run_marker": read_json(self.run_marker_path),
                          "last_deploy": last}, indent=1, default=str))
        return 0

    def init(self, seed):
        seed = os.path.realpath(seed)
        if not os.path.isfile(os.path.join(seed, "machine.xml")):
            return self.refuse(f"{seed} is not an OpenPnP config (no machine.xml)")
        if os.path.lexists(self.config):
            return self.refuse(f"{self.config} exists already; init runs once")
        running = [(p, d) for p, d, real in self.procs.openpnp_processes()
                   if real or os.path.realpath(d) in (seed, os.path.realpath(self.config))]
        if running:
            return self.refuse("OpenPnP is running: " + ", ".join(f"pid {p} (config {d})" for p, d in running))
        openers = self.procs.openers_under(seed)
        if openers:
            return self.refuse(f"files under {seed} are open by {self.procs.describe(openers)}")
        for d in (self.releases, self.snapshots, self.logdir, self.state, os.path.join(self.cfg.root, "prefs")):
            os.makedirs(d, exist_ok=True)
        tmp = os.path.join(self.state, ".config.init")
        shutil.rmtree(tmp, ignore_errors=True)
        shutil.copytree(seed, tmp, symlinks=True)
        os.rename(tmp, self.config)
        self.record("init", seed=seed)
        self.say(f"seeded {self.config} from {seed}")
        return 0

    def resume(self, found=None):
        """A person checked the machine: clear the hold and hand back the Holder the hold left. Refused if this same
        command just set the hold (found: a killed il_deploy's claim, OpenPnP ended on its own): nobody has seen it, so
        nobody checked the machine for it."""
        if found:
            return self.refuse(f"found just now: {found}. Hold set: check the machine, then run `il_deploy.py resume` "
                               "again")
        hold = self.read_hold()
        if not hold:
            self.say("no hold set")
            return 0
        if hold.get("holder"):
            text = released_value("resume", self.stamp("%H:%M"), None, hold.get("previous"))
            try:
                ok = release_holder(self.cfg.state_md, hold["holder"], text)
            except (StateFileConflict, OSError) as e:
                print(f"resume: could not hand the Holder back in {self.cfg.state_md} ({e}); the hold stays",
                      file=sys.stderr, flush=True)
                return 1
            self.say(f"Holder: {text}" if ok else "the Holder no longer shows the hold; left as it is")
        os.unlink(self.hold_path)
        self.note_person("resume")
        self.record("resume", hold=hold)
        self.say(f"hold cleared (was: {self.hold_reasons(hold)[1]}); the timer deploys again after "
                 f"{self.cfg.quiet_min:g} quiet minutes")
        return 0

    def note_person(self, cmd):
        """A person at the machine ran resume, health, deploy or rollback by hand: the timer's next deploy (and its
        home) waits the quiet period, so the head does not move by itself while they may still be at it. What those
        commands made OpenPnP stamp or log is in il_deploy's own windows: this is what says someone is there."""
        try:
            write_json(self.person_path, {"ts": self.clock.now(), "at": self.iso(), "cmd": cmd})
        except OSError as e:
            self.record("person-not-noted", cmd=cmd, error=str(e))

    def _person_reasons(self):
        """The quiet period after a command by hand (note_person; the timer's run only: a deploy by hand has its
        person)."""
        r = read_json(self.person_path, {})
        ts = _ms(r.get("ts")) if isinstance(r, dict) else None
        if ts is None:
            return []
        age, quiet = self.clock.now() - ts, self.cfg.quiet_min * 60
        return [f"a person ran il_deploy.py {r.get('cmd')} {int(age)} s ago (quiet period {int(quiet)} s)"
                ] if age < quiet else []

    def stop_openpnp(self):
        """The unit's ExecStop (outputs keep their state after SIGTERM): STOP at once while the pressure is held
        or an engine run dispenses; wait up to --home-busy-wait while busy (a home); never STOP during a home. Exit 1
        if the machine may not be off (no STOP sent, or not confirmed): the unit then ends 'failed', and il_deploy's
        next command sets the hold (an exit it did not cause)."""
        ok, detail = self.stop_machine()
        self.say(f"stop-openpnp: {detail}")
        if ok and detail != STOP_DONE:
            # No OpenPnP answered. systemd runs ExecStop also after the main process ended by itself with 0 or 143 (a
            # kill from outside: never a STOP), with $MAINPID unset; and a live JVM whose API answered earlier in this
            # run and does not now may still have its outputs on. Only a live JVM whose API never came up (still
            # starting: nothing enabled yet) has nothing to stop. Otherwise the run marker stays and the unit ends
            # 'failed': the next il_deploy sets the hold (an exit it did not cause).
            gone = not os.environ.get("MAINPID")
            if gone or self._api_was_up_this_run():
                self.say("stop-openpnp: " + ("OpenPnP had already ended by itself" if gone else
                                             "OpenPnP's API answered earlier in this run and does not now")
                         + ": no STOP confirmed, the run marker stays")
                return 1
        if ok:
            self._drop_run_marker()  # a confirmed STOP (or a JVM stopped before its API was up): this run ended well
        return 0 if ok else 1

    def _api_was_up_this_run(self):
        """OpenPnP.log shows the API started in this run (ApiServer: 'Instance Labs web UI: <url>'). OpenPnP starts a
        new log at each start, so a log older than the run marker is an earlier run's. No readable marker: unknown,
        taken as up (fail closed: the hold)."""
        m = read_json(self.run_marker_path)
        started = _iso_ts(m.get("at")) if isinstance(m, dict) else None
        if started is None:
            return True
        path = self._log_path()
        try:
            if os.stat(path).st_mtime < started:
                return False
            with open(path, errors="replace") as f:
                return any("Instance Labs web UI:" in line for line in f)
        except OSError:
            return False

    def wait_display(self, timeout, x11_dir="/tmp/.X11-unix"):
        """The Xvfb unit's ExecStartPost: wait until the X server listens. Type=simple counts as started at the
        fork, and OpenPnP's first AWT call (Main: UIManager.setLookAndFeel) exits 1 without the socket."""
        m = re.match(r"^[^:]*:(\d+)", self.cfg.display)
        if not m:
            self.say(f"wait-display: {self.cfg.display!r} is not an X display")
            return 1
        sock = os.path.join(x11_dir, "X" + m.group(1))
        end = self.clock.monotonic() + timeout
        while True:
            with contextlib.suppress(OSError):
                if stat.S_ISSOCK(os.stat(sock).st_mode):
                    return 0
            if self.clock.monotonic() >= end:
                self.say(f"wait-display: no X server socket {sock} after {timeout:g} s")
                return 1
            self.clock.sleep(0.05)

    def check_device(self):
        if not os.path.exists(self.cfg.device):
            self.say(f"serial device {self.cfg.device} is missing; OpenPnP can start but not connect")
            return 0
        pids = self.procs.openers(self.cfg.device)
        if pids:
            self.say(f"serial device {self.cfg.device} is open by {self.procs.describe(pids)}")
            return 1
        return 0


# ------------------------------------------------------------------------------------------------
# systemd user units
# ------------------------------------------------------------------------------------------------
def sd_quote(a):
    a = a.replace("%", "%%")
    if re.fullmatch(r"[\w@%+=:,./-]+", a):
        return a
    return '"' + a.replace("\\", "\\\\").replace('"', '\\"').replace("$", "$$") + '"'


def sd_join(args):
    return " ".join(sd_quote(a) for a in args)


def render_units(cfg, script, python):
    root, port = cfg.root, cfg.api_port
    me = [python, script]
    flags = ["--root", root, "--state-md", cfg.state_md, "--lock", cfg.lock, "--device", cfg.device]
    for a in cfg.activity:
        flags += ["--activity", a]
    flags += ["--quiet-min", f"{cfg.quiet_min:g}", "--api-port", str(port), "--usb-sn", cfg.usb_sn,
              "--repo", cfg.repo, "--branch", cfg.branch, "--service", cfg.service]
    if cfg.serial_match:
        flags += ["--serial-match", cfg.serial_match]
    if cfg.expect_simulated:
        flags.append("--expect-simulated")
    for o in cfg.trusted_origins:
        flags += ["--trusted-origin", o]
    env = openpnp_env(cfg)
    openpnp = "\n".join([
        "[Unit]",
        "Description=Instance Labs OpenPnP, deployed build on the real machine (managed by il_deploy.py)",
        "Requires=il-openpnp-xvfb.service",
        "After=il-openpnp-xvfb.service",
        "",
        "[Service]",
        "Type=simple",
        f"WorkingDirectory={root}/current",
        *(f'Environment="{k}={v}"' for k, v in env.items()),
        # bench tools refuse while OpenPnP holds the serial lock; refuse to start if the port is open
        "ExecStartPre=" + sd_join(me + ["check-device", "--device", cfg.device]),
        # the run marker: a down unit with it standing never had a confirmed STOP (survives a reboot, unlike 'failed')
        "ExecStartPre=" + sd_join(me + ["mark-start", "--root", root, "--lock", cfg.lock]),
        "ExecStart=" + sd_join(openpnp_command(cfg, use_override=False)),
        # STOP sequence first: SIGTERM saves nothing and leaves the outputs as they are. Never during a home:
        # wait out a whole one (API homes 21-23 s, up to 5 min); still busy then, no STOP: SIGTERM closes the
        # port and Marlin finishes the home. Then wait until the machine is off, the engine idle and the valve and
        # pump off; exit 1 if not (the unit ends 'failed': il_deploy sets the hold). TimeoutStopSec covers it all.
        "ExecStop=" + sd_join(me + ["stop-openpnp", "--root", root, "--api-port", str(port), "--home-busy-wait",
                                    str(UNIT_HOME_WAIT), "--stop-timeout", str(UNIT_STOP_TIMEOUT)]),
        # SIGTERM to the main process (java itself: flock -F), SIGKILL to anything left; java's exit on SIGTERM
        # (143) is a clean stop, so only a crash leaves the unit 'failed'
        "KillMode=mixed",
        "SuccessExitStatus=143",
        f"TimeoutStopSec={OPENPNP_STOP_S}",
        "Restart=no",
        "CPUWeight=1000",
        "IOWeight=1000",
        "MemoryLow=2G",
        # the user's web UI and the one owner of the serial port: the lowest an unprivileged unit may go (the user
        # manager's own 100); agents' scopes run at 800, so the kernel's OOM killer takes their processes first
        "OOMScoreAdjust=100",
        ""])
    xvfb = "\n".join([
        "[Unit]",
        f"Description=Xvfb {cfg.display} for the deployed OpenPnP (il-openpnp.service)",
        "",
        "[Service]",
        f"ExecStart=/usr/bin/Xvfb {cfg.display} -nolisten tcp -screen 0 1920x1080x24",
        # Type=simple is 'started' at the fork: active (and il-openpnp, After= it, started) only once X listens
        "ExecStartPost=" + sd_join(me + ["wait-display", "--display", cfg.display, "--timeout", "20"]),
        "Restart=on-failure",
        "OOMScoreAdjust=100",  # OpenPnP dies with its display
        ""])
    deploy = "\n".join([
        "[Unit]",
        "Description=Instance Labs auto-deploy: stage green il/integration builds, deploy when the machine is idle",
        # at shutdown a unit ordered After= stops first: il_deploy finishes a home and STOPs before ExecStop runs
        "After=il-openpnp.service",
        "",
        "[Service]",
        "Type=oneshot",
        "ExecStart=" + sd_join(me + ["run"] + flags),
        "SuccessExitStatus=3",
        # a deploy includes homes: never kill it halfway
        "TimeoutStartSec=infinity",
        # on SIGTERM it waits for a running home, stops the machine and releases the Holder
        f"TimeoutStopSec={DEPLOY_STOP_S // 60}min",
        "Nice=5",
        ""])
    timer = "\n".join([
        "[Unit]",
        "Description=Poll for green il/integration builds every 2 min and deploy when idle",
        "",
        "[Timer]",
        "OnBootSec=5min",
        "OnUnitInactiveSec=2min",
        "AccuracySec=15s",
        "Unit=il-deploy.service",
        "",
        "[Install]",
        "WantedBy=timers.target",
        ""])
    keepup = "\n".join([
        "[Unit]",
        "Description=Instance Labs: start OpenPnP (never enabled or homed) when it is down and nobody holds the machine",
        "After=il-openpnp.service",
        "",
        "[Service]",
        "Type=oneshot",
        "ExecStart=" + sd_join(me + ["keep-up"] + flags),
        "SuccessExitStatus=3",
        "Nice=5",
        ""])
    keepup_timer = "\n".join([
        "[Unit]",
        "Description=Keep the real machine's web UI up: check every minute that OpenPnP runs when the machine is free",
        "",
        "[Timer]",
        "OnBootSec=1min",
        "OnUnitInactiveSec=1min",
        "AccuracySec=10s",
        "Unit=il-keepup.service",
        "",
        "[Install]",
        "WantedBy=timers.target",
        ""])
    budget = "\n".join([
        "# System drop-in for the user manager that runs il_deploy's units (install as root: README.md). Ubuntu's",
        "# /usr/lib/systemd/system/user@.service.d/timeout.conf gives it 5 s at shutdown, then SIGKILL. At a reboot",
        f"# it stops il-deploy ({DEPLOY_STOP_S} s: a home waited out, the STOP, the Holder handed back), then",
        f"# il-openpnp ({OPENPNP_STOP_S} s: its ExecStop), one after the other. This file sorts after timeout.conf, so",
        "# it wins.",
        "[Service]",
        f"TimeoutStopSec={USER_MANAGER_STOP_S}",
        ""])
    return {"il-openpnp.service": openpnp, "il-openpnp-xvfb.service": xvfb, "il-deploy.service": deploy,
            "il-deploy.timer": timer, "il-keepup.service": keepup, "il-keepup.timer": keepup_timer,
            os.path.join("system", f"user@{os.getuid()}.service.d", USER_MANAGER_DROP_IN): budget}


# ------------------------------------------------------------------------------------------------
# CLI
# ------------------------------------------------------------------------------------------------
def build_parser():
    common = argparse.ArgumentParser(add_help=False)
    g = common.add_argument_group("paths and machine (defaults are this lab computer's)")
    g.add_argument("--root", default=os.environ.get("IL_DEPLOY_ROOT", DEFAULT_ROOT))
    g.add_argument("--state-md", default=DEFAULT_STATE_MD)
    g.add_argument("--lock", default=DEFAULT_LOCK, help="the bench tools' serial flock")
    g.add_argument("--device", default=DEFAULT_DEVICE, help="the controller's serial device")
    g.add_argument("--activity", action="append", default=None,
                   help="file whose mtime marks machine activity (repeatable; default runs/serial.log)")
    g.add_argument("--quiet-min", type=float, default=15)
    g.add_argument("--api-port", type=int, default=int(os.environ.get("OPENPNP_IL_API_PORT", DEFAULT_PORT)))
    g.add_argument("--usb-sn", default=DEFAULT_USB_SN, help="expected controller USB S/N ('' skips the check)")
    g.add_argument("--serial-match", help="regular expression the controller's 'Serial port: connecting to ...' log line "
                                          "must match (default: ' S/N <--usb-sn>'), for a link without a USB identity")
    g.add_argument("--expect-simulated", action="store_true", help="health: server.simulated must be true")
    g.add_argument("--trusted-origin", dest="trusted_origins", action="append", default=[], type=_origin_arg,
                   help="an origin the API must also answer, e.g. https://lumen-thinkpad.tail3e978a.ts.net:8877 "
                        "(repeatable; OpenPnP gets them as OPENPNP_IL_API_TRUSTED_ORIGINS; the health check probes them)")
    g.add_argument("--repo", default=REPO)
    g.add_argument("--branch", default=BRANCH)
    g.add_argument("--service", default=UNIT)
    g.add_argument("--service-manager", choices=("systemd", "subprocess"), default="systemd")
    g.add_argument("--openpnp-cmd", help="subprocess manager: the command (default: the unit's java command)")
    g.add_argument("--java", default="/usr/bin/java")
    g.add_argument("--display", default=":50")
    g.add_argument("--keep-snapshots", type=int, default=10)
    g.add_argument("--keep-releases", type=int, default=5)
    t = common.add_argument_group("timeouts in seconds")
    t.add_argument("--status-timeout", type=float, default=120)
    t.add_argument("--enable-timeout", type=float, default=100)
    t.add_argument("--home-timeout", type=float, default=330)
    t.add_argument("--home-busy-wait", type=float, default=300, help="wait for a running home before any STOP")
    t.add_argument("--stop-timeout", type=float, default=UNIT_STOP_TIMEOUT,
                   help="after a STOP, wait this long (at least) until the machine is off, the engine idle and the "
                        "pressure off")
    t.add_argument("--poll-interval", type=float, default=0.5)
    t.add_argument("--serial-wait", type=float, default=5, help="wait for the serial log line after enable")
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("poll", "run", "keep-up", "status", "resume", "stop-openpnp", "check-device", "mark-start"):
        sub.add_parser(name, parents=[common])
    sub.add_parser("rollback", parents=[common]).add_argument(
        "--discard-config-changes", action="store_true",
        help="go back even though the config changed since the deploy (those changes are kept in snapshots/)")
    sub.add_parser("deploy", parents=[common]).add_argument("--version", help="a staged tag (overrides the bad list)")
    p = sub.add_parser("health", parents=[common])
    p.add_argument("--sha7", help="expected build (default: the current release's)")
    p.add_argument("--no-claim", action="store_true", help="the caller holds the machine already")
    sub.add_parser("init", parents=[common]).add_argument("--seed-from", required=True)
    p = sub.add_parser("wait-display", parents=[common])
    p.add_argument("--timeout", type=float, default=20)
    p.add_argument("--x11-dir", default="/tmp/.X11-unix")
    p = sub.add_parser("units", parents=[common])
    p.add_argument("--out", required=True)
    p.add_argument("--script", help="il_deploy.py path the units call (default <root>/bin/il_deploy.py, a stable "
                                    "copy outside any git worktree)")
    p.add_argument("--python", default="/usr/bin/python3")
    return ap


def _origin_arg(text):
    try:
        return normalise_origin(text)[0]
    except ValueError as e:
        raise argparse.ArgumentTypeError(str(e))


def config_from_args(argv=None):
    cfg = build_parser().parse_args(argv)
    cfg.root = os.path.abspath(cfg.root)
    cfg.activity = [a for a in (cfg.activity if cfg.activity is not None else DEFAULT_ACTIVITY) if a]
    return cfg


@contextlib.contextmanager
def root_lock(root):
    """One il_deploy at a time per deploy root."""
    os.makedirs(root, exist_ok=True)
    fd = os.open(os.path.join(root, ".il-deploy.lock"), os.O_RDWR | os.O_CREAT, 0o644)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd)
        yield False
        return
    try:
        yield True
    finally:
        os.close(fd)


def main(argv=None, deps=None):
    cfg = config_from_args(argv)
    d = Deployer(cfg, deps)
    cmd = cfg.cmd
    if cmd == "units":
        script = cfg.script or os.path.join(cfg.root, "bin", "il_deploy.py")
        units = render_units(cfg, script, cfg.python)
        os.makedirs(cfg.out, exist_ok=True)
        for name, text in units.items():
            os.makedirs(os.path.dirname(os.path.join(cfg.out, name)), exist_ok=True)
            with open(os.path.join(cfg.out, name), "w") as f:
                f.write(text)
        drop_in = next(n for n in units if n.startswith("system" + os.sep))
        print(f"wrote {', '.join(sorted(units))} to {cfg.out}; they call {script}.\nInstall (see README.md):\n"
              f"  install -D -m 755 {os.path.abspath(__file__)} {script}\n"
              f"  cp {cfg.out}/il-* ~/.config/systemd/user/ && systemctl --user daemon-reload\n"
              f"  sudo install -D -m 644 {os.path.join(cfg.out, drop_in)} /etc/systemd/{drop_in} "
              "&& sudo systemctl daemon-reload   # root, with the user's OK\n"
              "  systemctl --user enable --now il-keepup.timer   # the real machine's web UI up whenever it is free\n"
              "  systemctl --user enable --now il-deploy.timer   # automatic deploys (they home the machine)")
        return 0
    if cmd == "status":
        return d.status()
    if cmd == "stop-openpnp":
        return d.stop_openpnp()
    if cmd == "check-device":
        return d.check_device()
    if cmd == "mark-start":
        return d.mark_start()
    if cmd == "wait-display":
        return d.wait_display(cfg.timeout, cfg.x11_dir)
    with root_lock(cfg.root) as got:
        if not got:
            print(f"not idle: another il_deploy is running on {cfg.root}")
            return 3
        # no il_deploy works on this root now: a claim file left is a killed one's
        found = (d.read_hold() or {}).get("reason") if d.recover_stale_claim() else None
        if cmd in ("deploy", "rollback", "run", "keep-up", "health", "resume"):
            exited = d.check_openpnp_exit()
            if exited:
                print(exited, file=sys.stderr if cmd == "health" else sys.stdout, flush=True)
                if cmd != "resume" and cmd != "health":
                    return 5
                found = "; ".join(x for x in (found, exited) if x)
        if cmd == "poll":
            try:
                d.poll()
                return 0
            except GitHubError as e:
                print(f"poll failed: {e}")
                return 1
        if cmd == "deploy":
            return d.deploy(cfg.version)
        if cmd == "rollback":
            return d.rollback(cfg.discard_config_changes)
        if cmd == "run":
            return d.run()
        if cmd == "keep-up":
            return d.keep_up()
        if cmd == "health":
            return d.health(cfg.sha7, cfg.no_claim)
        if cmd == "init":
            return d.init(cfg.seed_from)
        if cmd == "resume":
            return d.resume(found)
    return 2


_TERM = {"defer": False, "pending": None}


def _terminate(signum, frame):
    if _TERM["defer"]:
        _TERM["pending"] = signum
        return
    raise Terminated(f"signal {signum}")


def _defer_term(on):
    """While on, SIGTERM (SIGINT, SIGHUP) waits (a Holder claim or release must not be cut in half); turning it off
    raises one that came meanwhile."""
    _TERM["defer"] = on
    if not on and _TERM["pending"] is not None:
        signum, _TERM["pending"] = _TERM["pending"], None
        raise Terminated(f"signal {signum}")


@contextlib.contextmanager
def signals_deferred():
    """SIGTERM, SIGINT and SIGHUP wait while the block runs (a step that must not be cut in half); then one that came
    meanwhile is raised, unless the block raised or an outer block defers too."""
    was = _TERM["defer"]
    _TERM["defer"] = True
    try:
        yield
    except BaseException:
        _TERM["defer"] = was
        raise
    _TERM["defer"] = was
    if not was:
        _defer_term(False)


def _detach_terminal(fds=(1, 2)):
    """Point the output fds that are terminals (character devices) at /dev/null: after a hangup every write to the
    terminal fails (EIO), and the exit path prints. Files and pipes stay as they are."""
    null = os.open(os.devnull, os.O_WRONLY)
    try:
        for fd in fds:
            with contextlib.suppress(OSError):
                if stat.S_ISCHR(os.fstat(fd).st_mode):
                    os.dup2(null, fd)
    finally:
        os.close(null)


def _hangup(signum, frame):
    """SIGHUP: the terminal of a command by hand went away (closed, SSH dropped, tmux pane killed). Its output goes to
    /dev/null from now on, then the SIGTERM path: wait out a home, STOP, hand the Holder back."""
    _detach_terminal()
    _terminate(signum, frame)


def install_signal_handlers():
    signal.signal(signal.SIGTERM, _terminate)
    signal.signal(signal.SIGINT, _terminate)  # Ctrl-C at a deploy or rollback by hand: the same exit path, deferrable
    if signal.getsignal(signal.SIGHUP) is not signal.SIG_IGN:  # nohup: the person asked it to outlive the terminal
        signal.signal(signal.SIGHUP, _hangup)


if __name__ == "__main__":
    install_signal_handlers()
    sys.exit(main())
