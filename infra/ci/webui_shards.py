#!/usr/bin/env python3
"""The UI end-to-end job's shards: which run-webui-sim-test.sh scripts one matrix leg of ci.yml's ui-e2e runs.

The named shards are fixed here; the leg "rest" runs every other script in the runner's ALL_SCRIPTS, so a new script
(tip-flow, jog, ...) runs in CI without a change here. A named script that is no longer in ALL_SCRIPTS fails the leg
(a rename must not drop it from CI silently). Prints the GitHub output line `scripts=<names>`.

    python3 infra/ci/webui_shards.py main|calib|rest|full  (from the repo root)
"""
import os
import re
import sys

RUNNER = "src/test/web/run-webui-sim-test.sh"
# webui-sim and board-import share a session's state; pad-profile and tip-setups pass alone with BR-74's paste seed.
# A name ending in "?" joins its leg once it is in ALL_SCRIPTS (never the rest): tip-flow needs tip-setups' needle
# frame, whichever PR lands first.
SHARDS = {"main": ["webui-sim", "board-import"], "calib": ["pad-profile", "tip-setups", "tip-flow?"]}
KEYS = tuple(SHARDS) + ("rest",)  # ci.yml's legs
FULL = "full"  # every script in one session, as before the shards: for a comparison run, not a leg of the gate


def all_scripts(text):
    m = re.search(r'^ALL_SCRIPTS="([^"]*)"', text, re.M)
    if not m:
        raise ValueError('no ALL_SCRIPTS="..." line in ' + RUNNER)
    return m.group(1).split()


def scripts_for(all_, key):
    if key == FULL:
        return list(all_)
    if key not in KEYS:
        raise ValueError(f"unknown shard {key!r} (known: {', '.join(KEYS)})")
    missing = [s for names in SHARDS.values() for s in names if not s.endswith("?") and s not in all_]
    if missing:
        raise ValueError(f"shard scripts not in ALL_SCRIPTS ({' '.join(all_)}): {' '.join(missing)}")
    if key == "rest":
        named = {s.rstrip("?") for names in SHARDS.values() for s in names}
        return [s for s in all_ if s not in named]
    return [s.rstrip("?") for s in SHARDS[key] if not s.endswith("?") or s.rstrip("?") in all_]


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 1:
        sys.exit(__doc__)
    repo = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    with open(os.path.join(repo, RUNNER)) as f:
        print("scripts=" + " ".join(scripts_for(all_scripts(f.read()), argv[0])))


if __name__ == "__main__":
    main()
