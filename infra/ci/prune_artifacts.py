#!/usr/bin/env python3
"""Keep only the newest build artifact (the user, 11-04 20:2x: 62 GB of artifacts, 50 GB of them openpnp-build).

Deletes every unexpired artifact named --name (openpnp-build) of a run that ended in success or was cancelled, except
--keep-run's own (the run that just released): every run's jobs download their own build within the run, and
nothing reads an older run's (the deployer uses releases; release-fast reads only the queue run's gate-needs). A run
still going keeps its build, and so does one that failed or timed out: `gh run rerun --failed` reuses the first
attempt's build (it expires after a day). Any API error keeps that artifact. Exit 1 if the listing or a delete failed.
"""
import argparse
import json
import subprocess
import sys
from collections import namedtuple

Result = namedtuple("Result", "returncode stdout stderr")
PAGE = 100
STATE_JQ = '.status + " " + (.conclusion // "")'  # "completed success", "in_progress "
PRUNED = ("success", "cancelled")  # the conclusions whose builds go; a failed run's stays for its re-run


def gh_api(*args):
    try:
        p = subprocess.run(["gh", "api", *args], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError) as e:
        return Result(1, "", str(e))
    return Result(p.returncode, p.stdout, p.stderr)


def plan(artifacts, keep_run, state_of):
    """The artifacts to delete: of a run other than keep_run that completed in PRUNED. state_of(run_id): the run's
    (status, conclusion), (status,) while it runs, or None when it cannot be read (kept)."""
    out = []
    for a in artifacts:
        run = (a.get("workflow_run") or {}).get("id")
        if a.get("expired") or run is None or run == keep_run:
            continue
        st = state_of(run)
        if st and len(st) == 2 and st[0] == "completed" and st[1] in PRUNED:
            out.append(a)
    return out


def main(argv=None, gh=gh_api):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--repo", required=True)
    p.add_argument("--keep-run", type=int, required=True)
    p.add_argument("--name", default="openpnp-build")
    a = p.parse_args(argv)
    arts, page = [], 1
    while True:
        r = gh(f"repos/{a.repo}/actions/artifacts?name={a.name}&per_page={PAGE}&page={page}")
        if r.returncode != 0:
            print(f"listing the {a.name} artifacts failed (page {page}): {r.stderr.strip()}; nothing deleted",
                  file=sys.stderr)
            return 1
        got = json.loads(r.stdout or "{}").get("artifacts") or []
        arts += got
        if len(got) < PAGE:
            break
        page += 1
    states = {}

    def state_of(run):
        if run not in states:
            s = gh(f"repos/{a.repo}/actions/runs/{run}", "--jq", STATE_JQ)
            states[run] = tuple(s.stdout.split()) if s.returncode == 0 and s.stdout.split() else None
        return states[run]

    doomed, failed = plan(arts, a.keep_run, state_of), 0
    for art in doomed:
        d = gh("-X", "DELETE", f"repos/{a.repo}/actions/artifacts/{art['id']}")
        failed += d.returncode != 0
        print(f"{'deleted' if d.returncode == 0 else 'NOT deleted (' + d.stderr.strip() + ')'}: {art['name']} "
              f"{art['id']} of run {art['workflow_run']['id']}, {art.get('size_in_bytes', 0) / 1e6:.0f} MB")
    print(f"{a.name}: {len(arts)} found, {len(doomed) - failed} deleted, {failed} failed, the rest kept (run "
          f"{a.keep_run}'s own, unfinished runs' and failed runs')")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
