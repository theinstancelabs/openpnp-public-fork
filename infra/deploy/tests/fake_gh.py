#!/usr/bin/env python3
"""`gh` on PATH for tests that run a script of ci.yml (the release job's Publish step) as GitHub Actions would: FakeGhCli
(fakes.py) over a FakeGitHub kept in a JSON file, $FAKE_GH_STATE, between calls; each call is logged there.

  dump(github, path, login)  write the state (login: the account of GH_TOKEN, the author of what `release create` makes)
  load(path)                 -> (FakeGitHub, login, calls), after the script ran
"""
import base64
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fakes import FakeGhCli, FakeGitHub  # noqa: E402


def dump(github, path, login="github-actions[bot]", calls=()):
    state = {"repo": github.repo, "branch": github.branch, "login": login, "releases": github.releases,
             "attestations": github.attestations, "calls": list(calls),
             "assets": {url: base64.b64encode(data).decode() for url, data in github.assets.items()}}
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(state, f)
    os.replace(tmp, path)


def load(path):
    with open(path) as f:
        state = json.load(f)
    github = FakeGitHub(state["repo"], state["branch"])
    github.releases, github.attestations = state["releases"], state["attestations"]
    github.assets = {url: base64.b64decode(data) for url, data in state["assets"].items()}
    return github, state["login"], state["calls"]


def main(args):
    path = os.environ["FAKE_GH_STATE"]
    github, login, calls = load(path)
    argv = ["gh"] + args
    p = FakeGhCli(github, login=login).answer(argv)
    dump(github, path, login, calls + [argv])
    out = p.stdout or ""
    sys.stdout.buffer.write(out if isinstance(out, bytes) else out.encode())
    sys.stderr.write(p.stderr or "")
    return p.returncode


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
