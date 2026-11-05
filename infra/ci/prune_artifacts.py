#!/usr/bin/env python3
"""Keep only the newest build artifact (the user, 11-04 20:2x: 62 GB of artifacts, 50 GB of them openpnp-build).

Deletes every unexpired artifact named --name (openpnp-build) except --keep-run's own (the run that just released)
and those of runs not completed yet: every run's jobs download their own build within the run, and nothing reads an
older run's (the deployer uses releases; release-fast reads only the queue run's gate-needs). A partial re-run of a
completed run (`gh run rerun --failed`) then has no build to download: re-run the whole run. Any API error keeps that
artifact (uploads expire after a day anyway). Exit 1 if the listing or a delete failed.
"""
import argparse
import json
import subprocess
import sys
from collections import namedtuple

Result = namedtuple("Result", "returncode stdout stderr")
PAGE = 100


def gh_api(*args):
    try:
        p = subprocess.run(["gh", "api", *args], capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.SubprocessError) as e:
        return Result(1, "", str(e))
    return Result(p.returncode, p.stdout, p.stderr)


def plan(artifacts, keep_run, status_of):
    """The artifacts to delete: of a completed run other than keep_run. status_of(run_id): the run's status, or None
    when it cannot be read (kept)."""
    out = []
    for a in artifacts:
        run = (a.get("workflow_run") or {}).get("id")
        if a.get("expired") or run is None or run == keep_run:
            continue
        if status_of(run) == "completed":
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
    statuses = {}

    def status_of(run):
        if run not in statuses:
            s = gh(f"repos/{a.repo}/actions/runs/{run}", "--jq", ".status")
            statuses[run] = s.stdout.strip() if s.returncode == 0 else None
        return statuses[run]

    doomed, failed = plan(arts, a.keep_run, status_of), 0
    for art in doomed:
        d = gh("-X", "DELETE", f"repos/{a.repo}/actions/artifacts/{art['id']}")
        failed += d.returncode != 0
        print(f"{'deleted' if d.returncode == 0 else 'NOT deleted (' + d.stderr.strip() + ')'}: {art['name']} "
              f"{art['id']} of run {art['workflow_run']['id']}, {art.get('size_in_bytes', 0) / 1e6:.0f} MB")
    print(f"{a.name}: {len(arts)} found, {len(doomed) - failed} deleted, {failed} failed, the rest kept (run "
          f"{a.keep_run}'s own and unfinished runs')")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
