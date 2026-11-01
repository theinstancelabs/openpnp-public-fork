#!/usr/bin/env python3
"""Automatic deploy of green il/integration builds of the OpenPnP fork to the lab computer (INF-02).

CI publishes each green il/integration build as a GitHub prerelease 'il-build-<run>-<sha7>' (tarball,
.sha256, BUILD-INFO.json). This tool stages the newest eligible one, deploys it only when the machine
is idle, health-checks it (API up, version, connect, controller state, home, stop), rolls back
automatically on failure, and keeps STATE.md's Holder and "Deployed on the machine" lines current.

Layout under --root (default ~/instancelabs/deploy):
  releases/<tag>/          openpnp-il.jar, lib/, VERSION.txt, BUILD-INFO.json, STAGED.json
  current, previous        symlinks to releases/<tag> (switched atomically)
  state/config/            the one live OpenPnP config (seeded once by init)
  snapshots/<ts>-pre-<tag>/, <ts>-abandoned-<tag>/   config copies around each switch
  prefs/                   Java preferences of the service
  log/deploy.jsonl         every step; deployed.json, bad.json (failed builds), hold.json (a person
                           must check the machine; blocks deploys until 'resume')

Usage (common flags go after the subcommand):
  il_deploy.py poll                         stage the newest eligible release
  il_deploy.py deploy [--version TAG]       deploy the newest staged, not-bad release when idle;
                                            --version picks one and overrides the bad list
  il_deploy.py rollback [--discard-config-changes]
                                            one step back to previous, with its pre-deploy config
                                            snapshot; refused if the config changed since the deploy
  il_deploy.py run                          poll, then deploy if newer (what the timer calls)
  il_deploy.py health [--sha7 X] [--no-claim]  the health check alone; JSON report
  il_deploy.py status                       versions, last deploy, idle verdict (JSON)
  il_deploy.py init --seed-from DIR         create state/config once from an existing config
  il_deploy.py units --out DIR [--script P] render the systemd user units (never installs them)
  il_deploy.py resume                       a person checked the machine: clear the hold, hand back the Holder
  il_deploy.py stop-openpnp                 POST /api/stop unless homing, wait until disabled (the ExecStop)
  il_deploy.py check-device                 exit 1 if a process has the serial device open
Common flags: --root --state-md --lock --device --activity FILE (repeatable) --quiet-min --api-port
  --usb-sn --expect-simulated --repo --branch --service --service-manager systemd|subprocess
  --openpnp-cmd CMD ({python} {root} {config} {port} {lock} {prefs} placeholders; subprocess only)
Exit codes: 0 done or nothing to do; 1 error; 2 refused; 3 not idle; 4 deploy failed and rolled back;
  5 failed and a person must check (hold set; the Holder stays 'Infra (...): a person must check').
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
import subprocess
import sys
import tarfile
import tempfile
import time
import traceback
import urllib.error
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
UNIT_HOME_WAIT, UNIT_STOP_TIMEOUT = 300, 10  # the unit's ExecStop: a whole home, then the STOP
# GitHub compare <branch head>...<build sha>: the build is the head ("identical") or an ancestor ("behind")
ELIGIBLE_COMPARE = ("identical", "behind")
TAG_RE = re.compile(r"^il-build-(\d+)-([0-9a-f]{7})$")
JAVA_OPENS = ["--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
              "--add-opens=java.desktop/java.awt.color=ALL-UNNAMED"]
PAD_CAL_DONE = ("done", "stopped", "cancelled", "failed")


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
    """Replace the '## Code' bullet '- **Deployed on the machine:**' (and its continuation), or add it at
    the end of that section (or a new section) if missing."""
    label = "Deployed on the machine"
    lines = text.splitlines(keepends=True)
    found = find_entries(lines, label)
    if found:
        return _replace_at(lines, found[0], label, value, text)
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

    def call(self, method, path, body=None, timeout=10):
        data = None if body is None else json.dumps(body).encode()
        headers = {"Content-Type": "application/json"} if data is not None else {}
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


def reset_signature(axes):
    """X, Y, Z all 0 and B at 720 in the controller's own coordinates: what Marlin reports after a power cycle."""
    c = {}
    for a in axes or []:
        letter = str(a.get("letter") or "").upper()
        v = a.get("driverCoordinate")
        if letter and letter not in c and isinstance(v, (int, float)) and not isinstance(v, bool):
            c[letter] = float(v)
    try:
        return all(abs(c[k]) < 1e-3 for k in "XYZ") and abs(c["B"] - 720.0) < 1e-3
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


def config_is_real(config_dir):
    """True unless machine.xml provably drives no serial port (fail closed: unreadable = real)."""
    try:
        root = ET.parse(os.path.join(config_dir, "machine.xml")).getroot()
    except (OSError, ET.ParseError):
        return True
    for drv in root.iter("driver"):
        comm = drv.get("communications")
        if comm == "serial" or (comm is None and drv.find("serial") is not None):
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
        if st[0] in ("Z", "X"):
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
            if st and st[3] == pgid and st[0] not in ("Z", "X") and int(st[2]) >= int(born):
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
    return ["/usr/bin/flock", "-n", "-E", str(LOCK_CONFLICT), cfg.lock, cfg.java, *JAVA_OPENS,
            f"-DconfigDir={vals['config']}", f"-Djava.util.prefs.userRoot={vals['prefs']}", "-jar", "openpnp-il.jar"]


def openpnp_env(cfg):
    return {"OPENPNP_IL_API_PORT": str(cfg.api_port), "JAVA_TOOL_OPTIONS": "-Xmx1536m", "DISPLAY": cfg.display}


def make_service(cfg):
    if cfg.service_manager == "systemd":
        return SystemdUserService(cfg.service)
    api, root = Api(cfg.api_port), cfg.root
    return SubprocessService(openpnp_command(cfg), os.path.join(root, "current"), dict(os.environ, **openpnp_env(cfg)),
                             os.path.join(root, "run"), os.path.join(root, "log"), name=cfg.service.rsplit(".", 1)[0],
                             pre_stop=lambda: stop_sequence(api, cfg.home_busy_wait, cfg.stop_timeout,
                                                            cfg.poll_interval, Clock()))


def api_stop_and_wait(api, timeout, poll=0.2):
    """POST /api/stop, then wait until the machine is disabled. (ok, detail)."""
    try:
        code, body = api.post("/api/stop", timeout=10)
    except ApiDown as e:
        return False, f"POST /api/stop: {e}"
    if code != 200:
        return False, f"POST /api/stop: HTTP {code} {body.get('reason', '')}"
    if body.get("machineEnabled") is False:
        return True, "machine disabled"
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        time.sleep(poll)
        with contextlib.suppress(ApiDown):
            code, m = api.get("/api/machine", timeout=5)
            if code == 200 and m.get("enabled") is False:
                return True, "machine disabled"
    return False, f"the machine is still enabled {timeout:g} s after POST /api/stop"


def stop_sequence(api, wait_s, stop_timeout, poll, clock):
    """STOP OpenPnP's machine before OpenPnP is stopped or left alone. (ok, detail): ok = stopped, or nothing to
    stop (the API is gone). A STOP inside G28 hangs the controller until a power cycle, so:
    - the pressure held, or an engine run past its home (phase not 'home: ...'): STOP at once, outputs may be on
      (homes refuse while the pressure is held);
    - otherwise wait while the machine is busy or its state is unknown (no answer in time, HTTP != 200), up to
      wait_s, then STOP. Still busy then: no STOP (after SIGTERM, Marlin finishes the home on its own)."""
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
                pressure = bool(p.get("on")) or p.get("state") not in (None, "off")
                running = run.get("running") is True
                run_homing = running and str(run.get("phase") or "").startswith("home")
                if pressure or (running and not run_homing) or (not running and m.get("busy") is False):
                    return api_stop_and_wait(api, stop_timeout, poll)
                why = "the machine is busy (homing?)"
        if clock.monotonic() >= end:
            return False, f"STOP not sent: {why} for {wait_s:g} s"
        clock.sleep(poll)


def config_manifest(config_dir):
    """{path relative to config_dir: sha256}, to spot config changes since a deploy. Skipped: log/ and SQLite
    -shm files (an index that changes on reads; the db and its -wal hold the data)."""
    out = {}
    for d, dirs, files in os.walk(config_dir):
        rel = os.path.relpath(d, config_dir)
        if rel == ".":
            dirs[:] = [x for x in dirs if x != "log"]
        for n in files:
            if n.endswith("-shm"):
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


def read_json(path, default=None):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


class Deps:
    """Injectable collaborators; None means the real one for this host."""

    def __init__(self, github=None, service=None, clock=None, procs=None):
        self.github, self.service, self.clock, self.procs = github, service, clock, procs


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
        self._gh = deps.github
        self.api = Api(cfg.api_port)
        self.out = sys.stdout
        self.held = False  # a person must check the machine (set during this claim)
        self.switched = False  # current/previous were switched during this claim
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
        write_json(self.hold_path, {"reason": reason, "at": self.iso()})
        self.record("hold", reason=reason)
        self.held = True

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
        self.switched = True
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
                reasons.append(f"deploys are on hold since {hold.get('at')}: {hold.get('reason')} (after a person "
                               "has checked the machine: il_deploy.py resume)")
            ours = self.svc.pids()
            reasons += self._lock_reasons(ours) + self._device_reasons(ours)
            for pid, d, real in self.procs.openpnp_processes():
                if real and pid not in ours:
                    reasons.append(f"another OpenPnP with a real-machine config runs (pid {pid}, config {d})")
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
                    reasons += api_busy_reasons(st) + self._api_quiet_reasons(st)
        else:
            try:
                self.api.get("/api/status", timeout=3)
                reasons.append(f"something answers on 127.0.0.1:{cfg.api_port} but {cfg.service} is not running "
                               "(another OpenPnP?)")
            except ApiDown:
                pass
        return reasons

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

    def _api_quiet_reasons(self, st):
        """Activity in OpenPnP within the quiet period: run.finishedAtMs (RunTracker), lastRun.endedAtMs
        (Views.run), padCalibration.endedAtMs, pressure.lastEventAtMs. The pressure hold's first value, 'pressure
        off' stamped when the API starts (PressureHold.java:77-78), is not an event."""
        now_ms, quiet_ms = self.clock.now() * 1000, self.cfg.quiet_min * 60000
        run, last, pad, p = (st.get(k) if isinstance(st.get(k), dict) else {}
                             for k in ("run", "lastRun", "padCalibration", "pressure"))
        latest = {}
        for what, ms in (("a run ended", run.get("finishedAtMs")), ("a run ended", last.get("endedAtMs")),
                         ("a pad calibration ended", pad.get("endedAtMs")),
                         ("a pressure event", None if p.get("lastEvent") == "pressure off" else p.get("lastEventAtMs"))):
            if isinstance(ms, (int, float)) and not isinstance(ms, bool):
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
        _defer_term(True)
        try:
            prev = claim_holder(self.cfg.state_md, claim)
        except (NotIdle, StateFileConflict, OSError, ValueError) as e:
            _defer_term(False)
            return self.not_idle([f"could not claim the Holder: {e}"])
        except BaseException:
            _defer_term(False)
            raise
        self.held = self.switched = False
        rc, note = 1, None
        try:
            _defer_term(False)  # a SIGTERM during the claim is raised here, inside the try
            self.record("claim", holder=claim, previous=prev)
            reasons = self.idle_reasons(our_claim=claim, scope=scope)
            if reasons:
                if self._hand_back(action, claim, prev, prev) is None:
                    return 1
                return self.not_idle(reasons)
            rc, note = fn(claim)
        except BaseException as e:
            rc, note = 1, self._after_exception(action, e)
        _defer_term(True)
        try:
            if self.held:
                text = self.held_text(action, subject, note)
                write_json(self.hold_path, dict(self.read_hold() or {}, holder=text, previous=prev))
            else:
                text = released_value(action, self.stamp("%H:%M"), note, prev)
            if self._hand_back(action, claim, text, prev) is None:
                rc = rc or 1
            self.record("result", action=action, subject=subject, rc=rc, note=note)
            if note:
                self.say(note)
        finally:
            _TERM.update(defer=False, pending=None)  # done: a SIGTERM now just ends the process as planned
        return rc

    def _hand_back(self, action, claim, text, prev):
        """Swap our claim for text. True, False (someone else's Holder now), or None: it could not be written,
        which is said on stderr and sets the hold."""
        try:
            ok = release_holder(self.cfg.state_md, claim, text)
            self.record("release", ok=ok, holder=text)
            return ok
        except (StateFileConflict, OSError) as e:
            msg = (f"could not hand the Holder back in {self.cfg.state_md} ({e}): it still says '{claim}'; "
                   "a person must set it")
            print(f"{action}: {msg}", file=sys.stderr, flush=True)
            self.record("release", ok=False, error=str(e), holder=text)
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
                + ("machine stopped through /api/stop" if ok and detail == "machine disabled" else detail))
        self.set_hold(note)
        if self.switched:
            cur = self.current_tag()
            rec = (self.read_deployed() or {}).get("current") or {}
            then = ("`il_deploy.py health` (verifies it) or `il_deploy.py rollback`"
                    if rec.get("pending") and rec.get("tag") == cur else "`il_deploy.py health`")
            self.state_line(f"{cur} installed but NOT verified: the {action} stopped unexpectedly "
                            f"({type(e).__name__}: {e}); a person must check the machine, then {then}.")
        return note

    # --- stopping and starting OpenPnP -----------------------------------------------------------
    def _wait_not_busy(self, seconds):
        end = self.clock.monotonic() + seconds
        while True:
            try:
                code, m = self.api.get("/api/machine", timeout=10)
                if code == 200 and m.get("busy") is False:
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

    def stop_service(self):
        """Stop the running service gracefully. None if stopped, else (rc, note) for the caller to return."""
        ok, detail = self.stop_machine()
        self.record("stop", ok=ok, detail=detail)
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
        try:
            self.svc.start()
        except (ServiceError, OSError, subprocess.SubprocessError) as e:
            self.record("start", ok=False, error=str(e))
            return {"ok": False, "kind": "machine", "failure": f"start: OpenPnP did not start: {e}", "steps": []}
        self.record("start", ok=True)
        rep = self.health_check(sha7, watch_service=True)
        self.record("health", ok=rep["ok"], kind=rep["kind"], failure=rep["failure"], report=rep)
        return rep

    # --- health ----------------------------------------------------------------------------------
    def _wait_status(self, watch_service):
        """/api/status of a started OpenPnP: 200 with the dispense engine running. OpenPnP starts its web API and the
        engine on configurationComplete in no fixed order (its listeners are a HashSet), so a 200 without the engine
        is waited out too; at the timeout that 200 is returned, and the engine check fails on it."""
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
                    if isinstance(d, dict) and d.get("running") is True:
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

    def _log_path(self):
        return os.path.join(self.config, "log", "OpenPnP.log")

    def _wait_serial_line(self, offset):
        rx = re.compile(r"Serial port: connecting to .*\bS/N " + re.escape(self.cfg.usb_sn) + r"(\s|$)")
        end = self.clock.monotonic() + self.cfg.serial_wait
        while True:
            with contextlib.suppress(OSError), open(self._log_path(), "rb") as f:
                if os.fstat(f.fileno()).st_size >= offset:
                    f.seek(offset)
                for raw in f:
                    line = raw.decode("utf-8", "replace")
                    if rx.search(line):
                        return line.strip()
            if self.clock.monotonic() >= end:
                return None
            self.clock.sleep(self.cfg.poll_interval)

    def health_check(self, expect_sha7=None, watch_service=False):
        """API up with the expected build, enable, the real port by USB S/N, controller axes (never home
        a reset controller), home (never interrupted), homed, STOP. Returns a JSON-able report."""
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
            return fail("status", f"the dispense engine is not usable (dispense.running {d.get('running')}, "
                                  f"available {d.get('available')}: {d.get('unavailableReason')})", stop=False)
        step("status", f"version {version}, simulated {sim}, dispense engine available")

        try:
            code, m = self.api.get("/api/machine", timeout=10)
            if code == 200 and m.get("enabled"):
                if m.get("busy") is not False:
                    return fail("enable", "the machine is already enabled and busy; not touching it", "machine", False)
                ok, d = self.stop_machine()
                if not ok:
                    return fail("enable", f"could not stop the already enabled machine first: {d}", stop=False)
            offset = os.path.getsize(self._log_path()) if os.path.exists(self._log_path()) else 0
            code, body = self.api.post("/api/machine/enable", timeout=cfg.enable_timeout)
        except ApiDown as e:
            return fail("enable", str(e))
        if code != 200 or body.get("enabled") is not True:
            return fail("enable", f"HTTP {code}: {body.get('reason', body)}")
        step("enable", "machine enabled")

        if cfg.usb_sn:
            line = self._wait_serial_line(offset)
            if not line:
                return fail("serial", f"no 'Serial port: connecting ... S/N {cfg.usb_sn}' line in {self._log_path()} "
                                      "since the enable")
            step("serial", line)

        try:
            code, m = self.api.get("/api/machine", timeout=10)
        except ApiDown as e:
            return fail("axes", str(e))
        axes = m.get("axes") or []
        coords = {str(a.get("letter")).upper(): a.get("driverCoordinate") for a in axes if a.get("letter")}
        if not all(isinstance(coords.get(k), (int, float)) for k in "XYZ"):
            return fail("axes", f"the controller position was not read (driver coordinates {coords})")
        if reset_signature(axes):
            return fail("axes", "controller was reset; a person must check the machine (X, Y, Z 0 and B 720 after "
                                "the start; not homing)", "machine")
        step("axes", coords)

        try:
            code, body = self.api.post("/api/machine/home", {"wait": True}, timeout=cfg.home_timeout)
            late = False
        except ApiTimeout:
            # never STOP during a home: wait for it to end
            if not self._wait_not_busy(cfg.home_busy_wait):
                return fail("home", f"the home did not end within {cfg.home_timeout + cfg.home_busy_wait:g} s; "
                                    "OpenPnP left alone, a person must check the machine", "home-stuck", stop=False)
            code, body, late = 200, {"homed": True}, True
        except ApiDown as e:
            return fail("home", str(e))
        if code != 200 or body.get("homed") is not True:
            reason = str(body.get("reason", body))
            # a failed home (fiducial covered, endstop, obstruction) is the machine's: no second home unwatched
            return fail("home", f"HTTP {code}: {reason}", "machine" if "homing failed" in reason else "build")

        self._wait_not_busy(min(10.0, cfg.home_busy_wait))  # the task may end a moment after the reply
        try:
            code, m = self.api.get("/api/machine", timeout=10)
        except ApiDown as e:
            return fail("homed", str(e))
        if m.get("homed") is not True or m.get("busy") is not False:
            return fail("home" if late else "homed", f"homed {m.get('homed')}, busy {m.get('busy')}",
                        "machine" if late else "build")
        step("home", "homed" + (" (ended after the request timed out)" if late else ""))
        step("homed", {"homed": True, "busy": False, "positions": m.get("positions")})

        ok, d = api_stop_and_wait(self.api, cfg.stop_timeout, cfg.poll_interval)
        if not ok:
            return fail("stop", d, stop=False)
        step("stop", d)
        rep.update(ok=True, finished_at=self.iso())
        return rep

    # --- poll ------------------------------------------------------------------------------------
    def branch_head(self):
        return self.gh.get_json(f"repos/{self.cfg.repo}/git/ref/heads/{self.cfg.branch}")["object"]["sha"]

    def commit_eligible(self, sha, head=None):
        """(ok, why): the sha is the branch head or one of its ancestors, and its 'gate' check passed."""
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
        runs = self.gh.get_json(f"repos/{repo}/commits/{sha}/check-runs?check_name=gate&filter=latest")
        gate = [r for r in runs.get("check_runs", []) if r.get("name") == "gate"]
        if not gate:
            return False, f"no 'gate' check-run on {sha7}"
        bad = [f"{r.get('status')}/{r.get('conclusion')}" for r in gate
               if r.get("status") != "completed" or r.get("conclusion") != "success"]
        if bad:
            return False, f"the 'gate' check-run on {sha7} is {', '.join(bad)}, not completed/success"
        return True, f"on {branch} ({status} vs head {head[:7]}), gate success"

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
        """Download, verify and unpack a release into releases/<tag>/ (one rename). Refused if ineligible."""
        tag = rel["tag_name"]
        sha7 = parse_tag(tag)[1]
        assets = {a.get("name"): a for a in rel.get("assets") or []}
        tar_name = f"openpnp-il-{sha7}.tar.gz"
        missing = [n for n in (tar_name, tar_name + ".sha256", "BUILD-INFO.json") if n not in assets]
        if missing:
            raise Refused(f"release {tag} lacks {', '.join(missing)}", True)
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
            ok, why = self.commit_eligible(info["sha"], head)
            if not ok:
                raise Refused(why)
            tar = os.path.join(work, tar_name)
            self.gh.download(assets[tar_name], tar)
            self.gh.download(assets[tar_name + ".sha256"], tar + ".sha256")
            self._verify_sha256(tar, tar + ".sha256", tar_name)
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
            write_json(os.path.join(d, "STAGED.json"), {"tag": tag, "sha": info["sha"], "staged_at": self.iso(),
                                                        "release": rel.get("html_url"), "eligible": why})
            os.rename(d, os.path.join(self.releases, tag))
            self.record("staged", tag=tag, sha=info["sha"], why=why)
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
        return info

    def verify_on_github(self, tag, info):
        """The staged folder must be the GitHub release's build: its BUILD-INFO from the release, compared by sha
        and jar_sha256 (a folder copied in by hand agrees only with itself)."""
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
        for k in ("sha", "jar_sha256"):
            if remote.get(k) != info.get(k):
                raise Refused(f"the staged folder is not the GitHub release's build: its {k} "
                              f"{str(info.get(k))[:12]} differs from {str(remote.get(k))[:12]}")

    def deploy(self, version=None):
        cur = self.current_tag()
        rec = (self.read_deployed() or {}).get("current") or {}
        if rec.get("pending") and rec.get("tag") == cur:
            return self.refuse(f"the current {cur} is not verified (its deploy was cut short at "
                               f"{rec.get('switched_at')}): after checking the machine, `il_deploy.py health` "
                               "verifies it, or `il_deploy.py rollback` goes back")
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
        reasons = self.idle_reasons()
        if reasons:
            return self.not_idle(reasons)
        try:
            self.verify_on_github(tag, info)
            ok, why = self.commit_eligible(info["sha"])
        except Refused as e:
            return self.refuse(f"{tag}: {e}")
        except GitHubError as e:
            return self.refuse(f"{tag}: cannot verify it on GitHub: {e}")
        if not ok:
            return self.refuse(f"{tag}: {why}")
        return self.with_claim("deploy", tag, lambda claim: self._deploy(tag, info, cur, bool(version)))

    def _deploy(self, tag, info, cur, forced):
        was_running = self.svc.is_active()
        before = {"current": self._link("current"), "previous": self._link("previous"),
                  "deployed": self.read_deployed()}
        self.record("begin", action="deploy", tag=tag, current=cur, was_running=was_running)
        if was_running:
            r = self.stop_service()
            if r:
                return r
        snap = self.snapshot(f"pre-{tag}")
        # written before the switch, so that rollback finds an unverified build whatever stops this run
        rec = {"tag": tag, "sha": info["sha"], "run_url": info.get("run_url"), "pre_snapshot": snap, "previous": cur,
               "switched_at": self.iso(), "pending": "installed; its health check has not passed"}
        write_json(self.deployed_path, {"current": rec, "previous": _record_for(before["deployed"], cur)})
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
        """The current build passed its health check: drop 'pending' and keep the config's manifest, the baseline
        a later rollback compares with."""
        rec = {k: v for k, v in rec.items() if k != "pending"}
        rec.update(deployed_at=self.iso(), since=self.stamp(), post_manifest=config_manifest(self.config))
        write_json(self.deployed_path, dict(self.read_deployed() or {}, current=rec))

    def _start_unchecked(self):
        try:
            self.svc.start()
        except (ServiceError, OSError, subprocess.SubprocessError) as e:
            self.record("start", ok=False, error=str(e))
            return f"; OpenPnP ran before but did not start again ({e})"
        self.record("start", ok=True, checked=False)
        return "; OpenPnP runs again, machine not enabled"

    def _recover(self, tag, rep, before, snap, was_running):
        """The new build failed its health check. A machine problem puts the previous build back with its config
        but unchecked, so nothing moves before a person looks. Otherwise the previous build is started and
        checked; the new one goes on the bad list only if the previous one passes (else the machine is suspect)."""
        failure, kind = rep["failure"], rep["kind"]
        prev = os.path.basename(before["current"]) if before["current"] else None
        back = f"; `il_deploy.py rollback` returns to {prev} with its config" if prev else ""
        if kind == "home-stuck":
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
        self.restore_snapshot(snap, f"abandoned-{tag}")
        self.set_links(before["current"], before["previous"])
        if before["deployed"] is None:
            with contextlib.suppress(OSError):
                os.unlink(self.deployed_path)
        else:
            write_json(self.deployed_path, before["deployed"])
        if prev is None:
            self.record("rolled-back", tag=tag, to=None)
            if kind == "build":
                self.add_bad(tag, failure)
            else:
                self.set_hold(f"{tag}: {failure}")
            return (5 if self.held else 4), f"{tag} failed its health check ({failure}); nothing deployed before it"
        if kind != "build":
            note = f"{tag}'s health check hit a machine problem: {failure}"
            self.set_hold(note)
            started = self._start_unchecked() if was_running else ""
            self.state_line(f"{self.describe(prev)} reinstalled with its config, not checked: at {self.stamp()} "
                            f"{tag}'s health check hit a machine problem ({failure}); a person must check the "
                            f"machine{started}.")
            self.record("rolled-back", tag=tag, to=prev, checked=False)
            return 5, note
        rep2 = self.start_and_check((self.build_info(prev).get("sha") or "")[:7])
        if rep2["ok"]:
            self.add_bad(tag, failure)
            self.restore_running(was_running)
            since = ((before["deployed"] or {}).get("current") or {}).get("since", "?")
            self.state_line(f"{self.describe(prev)} since {since}; at {self.stamp()} {tag} failed its health check "
                            f"({failure}) and was rolled back automatically.")
            self.record("rolled-back", tag=tag, to=prev)
            return (5 if self.held else 4), f"{tag} failed its health check, rolled back to {prev}"
        if rep2["kind"] == "home-stuck":
            left = "OpenPnP left alone mid-home"
        else:
            left = "OpenPnP stopped" if self.stop_after_failure() else "OpenPnP could not be stopped safely, left alone"
        note = (f"{tag} failed its health check ({failure}) and so did {prev} ({rep2['failure']}): the machine is "
                f"suspect, {tag} is not marked bad; {left}, a person must check the machine")
        self.set_hold(note)
        self.state_line(f"{prev} installed but NOT verified: {note} (log: {self.journal}).")
        self.record("rollback-failed", tag=tag, to=prev)
        return 5, note

    def rollback(self, discard=False):
        cur, prev, dep = self.current_tag(), self.previous_tag(), self.read_deployed() or {}
        rec = dep.get("current") or {}
        if not prev or rec.get("tag") != cur:
            return self.refuse("no previous version to roll back to")
        snap = rec.get("pre_snapshot")
        if not snap or not os.path.isdir(os.path.join(self.cfg.root, snap)):
            return self.refuse(f"the pre-deploy snapshot of {cur} ({snap}) is missing")
        base = rec.get("post_manifest")
        changes = None if base is None else manifest_changes(base, config_manifest(self.config))
        if changes != [] and not discard:
            why = (f"{cur} never passed its health check, so nothing shows what changed in {self.config} since it "
                   f"was installed ({rec.get('switched_at')})" if changes is None else
                   f"{self.config} changed since {cur} was deployed: {', '.join(changes)}")
            return self.refuse(f"{why}. A rollback puts back the config from before {cur} ({snap}) and would undo "
                               "that (the current one is kept under snapshots/). To go ahead: il_deploy.py rollback "
                               "--discard-config-changes")
        reasons = self.idle_reasons()
        if reasons:
            return self.not_idle(reasons)
        return self.with_claim("rollback", f"to {prev}", lambda claim: self._rollback(cur, prev, dep, snap, changes))

    def _rollback(self, cur, prev, dep, snap, changes):
        was_running = self.svc.is_active()
        self.record("begin", action="rollback", tag=cur, to=prev, was_running=was_running, discarded=changes)
        if was_running:
            r = self.stop_service()
            if r:
                return r
        abandoned = self.restore_snapshot(snap, f"abandoned-{cur}")
        self.set_links(os.path.join(self.releases, prev), None)
        prev_rec = {k: v for k, v in (_record_for(dep, prev) or {"tag": prev, "sha": self.build_info(prev).get("sha")})
                    .items() if k != "post_manifest"}
        prev_rec.update(deployed_at=self.iso(), since=self.stamp(), rolled_back_from=cur)
        write_json(self.deployed_path, {"current": prev_rec, "previous": None})
        self.add_bad(cur, "rolled back by 'il_deploy.py rollback'")
        rep = self.start_and_check((self.build_info(prev).get("sha") or "")[:7])
        if rep["ok"]:
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
        if rep["kind"] == "home-stuck":
            left = "OpenPnP left alone mid-home"
        else:
            left = "OpenPnP stopped" if self.stop_after_failure() else "OpenPnP could not be stopped safely, left alone"
        note = (f"the rollback from {cur} to {prev} failed its health check ({rep['failure']}); {left}, a person must "
                "check the machine")
        self.set_hold(note)
        self.state_line(f"{prev} installed but NOT verified: {note} (log: {self.journal}).")
        return 5, note

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

    def restore_snapshot(self, snap_rel, label):
        tmp = os.path.join(self.state, ".config.restore")
        shutil.rmtree(tmp, ignore_errors=True)
        shutil.copytree(os.path.join(self.cfg.root, snap_rel), tmp, symlinks=True)
        os.makedirs(self.snapshots, exist_ok=True)
        abandoned = os.path.join(self.snapshots, self._snapname(label))
        os.rename(self.config, abandoned)
        os.rename(tmp, self.config)
        rel = os.path.relpath(abandoned, self.cfg.root)
        self.record("restore", snapshot=snap_rel, abandoned=rel)
        return rel

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
    def run(self):
        try:
            self.poll()
        except GitHubError as e:
            self.say(f"poll failed: {e}")
            self.record("poll-failed", error=str(e))
            return 1
        return self.deploy()

    def health(self, sha7=None, no_claim=False):
        self.out = sys.stderr
        sha7 = sha7 or (self.build_info(self.current_tag()).get("sha") or "")[:7] or None
        if no_claim:
            try:
                code, st = self.api.get("/api/status", timeout=10)
            except ApiDown as e:
                return self.not_idle([f"OpenPnP's API does not answer ({e})"])
            reasons = api_busy_reasons(st) if code == 200 else [f"/api/status answered HTTP {code}"]
            if reasons:
                return self.not_idle(reasons)
            rep = self.health_check(sha7)
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
            if rep["kind"] in ("machine", "home-stuck"):
                self.set_hold(f"health check: {rep['failure']}")
            return 1, f"health check failed: {rep['failure']}"

        rc = self.with_claim("health check", "", check, scope="health")
        if "rep" in out:
            print(json.dumps(out["rep"], indent=1, default=str))
        return rc

    def _settle_pending(self, rep):
        """A passed health check of a current build whose deploy was cut short verifies it."""
        cur = self.current_tag()
        rec = (self.read_deployed() or {}).get("current") or {}
        sha7 = (rec.get("sha") or "")[:7]
        if not (rep["ok"] and rec.get("pending") and rec.get("tag") == cur and sha7
                and str(rep.get("version", "")).endswith("." + sha7)):
            return
        self._verified(rec)
        prev = rec.get("previous")
        self.state_line(f"{self.describe(cur)} since {self.stamp()}, deployed by Infra (il_deploy.py), verified by a "
                        "health check after its deploy was cut short" + (f"; previous {prev}" if prev else "")
                        + f". Config state: {self.config}.")
        self.record("verified", tag=cur)

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
        print(json.dumps({"root": self.cfg.root, "current": info(self.current_tag()),
                          "previous": info(self.previous_tag()), "deployed": self.read_deployed(),
                          "service_active": self.svc.is_active(), "idle": not reasons, "reasons": reasons,
                          "hold": self.read_hold(), "bad": self.read_bad(), "staged": self.staged(),
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

    def resume(self):
        """A person checked the machine: clear the hold and hand back the Holder the hold left."""
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
        self.record("resume", hold=hold)
        self.say(f"hold cleared (was: {hold.get('reason')})")
        return 0

    def stop_openpnp(self):
        """The unit's ExecStop (outputs keep their state after SIGTERM): STOP at once while the pressure is held
        or an engine run dispenses; wait up to --home-busy-wait while busy (a home); never STOP during a home."""
        ok, detail = self.stop_machine()
        self.say(f"stop-openpnp: {detail}")
        return 0

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
    if cfg.expect_simulated:
        flags.append("--expect-simulated")
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
        "ExecStart=" + sd_join(openpnp_command(cfg, use_override=False)),
        # STOP sequence first: SIGTERM saves nothing and leaves the outputs as they are. Never during a home:
        # wait out a whole one (API homes 21-23 s, up to 5 min); still busy then, no STOP: SIGTERM closes the
        # port and Marlin finishes the home. TimeoutStopSec covers the wait plus the STOP itself.
        "ExecStop=" + sd_join(me + ["stop-openpnp", "--api-port", str(port), "--home-busy-wait",
                                    str(UNIT_HOME_WAIT), "--stop-timeout", str(UNIT_STOP_TIMEOUT)]),
        "KillMode=mixed",
        f"TimeoutStopSec={UNIT_HOME_WAIT + 60}",
        "Restart=no",
        "CPUWeight=1000",
        "IOWeight=1000",
        "MemoryLow=2G",
        ""])
    xvfb = "\n".join([
        "[Unit]",
        f"Description=Xvfb {cfg.display} for the deployed OpenPnP (il-openpnp.service)",
        "",
        "[Service]",
        f"ExecStart=/usr/bin/Xvfb {cfg.display} -nolisten tcp -screen 0 1920x1080x24",
        "Restart=on-failure",
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
        "TimeoutStopSec=10min",
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
    return {"il-openpnp.service": openpnp, "il-openpnp-xvfb.service": xvfb, "il-deploy.service": deploy,
            "il-deploy.timer": timer}


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
    g.add_argument("--expect-simulated", action="store_true", help="health: server.simulated must be true")
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
    t.add_argument("--stop-timeout", type=float, default=20)
    t.add_argument("--poll-interval", type=float, default=0.5)
    t.add_argument("--serial-wait", type=float, default=5, help="wait for the serial log line after enable")
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__.split("\n\n", 1)[1],
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("poll", "run", "status", "resume", "stop-openpnp", "check-device"):
        sub.add_parser(name, parents=[common])
    sub.add_parser("rollback", parents=[common]).add_argument(
        "--discard-config-changes", action="store_true",
        help="go back even though the config changed since the deploy (those changes are kept in snapshots/)")
    sub.add_parser("deploy", parents=[common]).add_argument("--version", help="a staged tag (overrides the bad list)")
    p = sub.add_parser("health", parents=[common])
    p.add_argument("--sha7", help="expected build (default: the current release's)")
    p.add_argument("--no-claim", action="store_true", help="the caller holds the machine already")
    sub.add_parser("init", parents=[common]).add_argument("--seed-from", required=True)
    p = sub.add_parser("units", parents=[common])
    p.add_argument("--out", required=True)
    p.add_argument("--script", help="il_deploy.py path the units call (default <root>/bin/il_deploy.py, a stable "
                                    "copy outside any git worktree)")
    p.add_argument("--python", default="/usr/bin/python3")
    return ap


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
            with open(os.path.join(cfg.out, name), "w") as f:
                f.write(text)
        print(f"wrote {', '.join(sorted(units))} to {cfg.out}; they call {script}.\nInstall (see README.md):\n"
              f"  install -D -m 755 {os.path.abspath(__file__)} {script}\n"
              f"  cp {cfg.out}/il-* ~/.config/systemd/user/ && systemctl --user daemon-reload\n"
              "  systemctl --user enable --now il-deploy.timer")
        return 0
    if cmd == "status":
        return d.status()
    if cmd == "stop-openpnp":
        return d.stop_openpnp()
    if cmd == "check-device":
        return d.check_device()
    with root_lock(cfg.root) as got:
        if not got:
            print(f"not idle: another il_deploy is running on {cfg.root}")
            return 3
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
        if cmd == "health":
            return d.health(cfg.sha7, cfg.no_claim)
        if cmd == "init":
            return d.init(cfg.seed_from)
        if cmd == "resume":
            return d.resume()
    return 2


_TERM = {"defer": False, "pending": None}


def _terminate(signum, frame):
    if _TERM["defer"]:
        _TERM["pending"] = signum
        return
    raise Terminated(f"signal {signum}")


def _defer_term(on):
    """While on, SIGTERM waits (a Holder claim or release must not be cut in half); turning it off raises a SIGTERM
    that came meanwhile."""
    _TERM["defer"] = on
    if not on and _TERM["pending"] is not None:
        signum, _TERM["pending"] = _TERM["pending"], None
        raise Terminated(f"signal {signum}")


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, _terminate)
    sys.exit(main())
