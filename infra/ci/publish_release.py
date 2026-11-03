#!/usr/bin/env python3
"""Publish a CI run's versioned build as the prerelease the lab computer's deployer installs, or, on a re-run, check
that the release already at the tag is this run's own.

  publish_release.py --dir DIR --tag il-build-<run>-<sha7> --sha SHA --repo OWNER/NAME --branch B --run-id ID
                     --run-url URL [--gh GH]

DIR is make_release.py's output; the job's attest step has attested its tarball. No release at the tag: `gh release
create` (a prerelease at SHA, never "latest") with DIR's tarball, its .sha256 and BUILD-INFO.json. A release already at
the tag passes only if it is what an earlier attempt of this run published, judged as infra/deploy/il_deploy.py judges
a release: published by CI's github.token (github-actions[bot]), at SHA, exactly those three files, BUILD-INFO naming
this run, and the tarball matching its .sha256 and attested by this run (`gh attestation verify` with the deployer's
policy, then the run in the certificate). Nothing of a release CI did not publish is downloaded, and its tarball only
after its BUILD-INFO names this run.

Exit 0: published, or this run's own. 1: anything else at the tag (a release anyone else put there first must not
leave the run green while its build is never released), or gh failed. 2: the tag is not this commit's.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "deploy"))
import il_deploy  # noqa: E402


def release_files(sha):
    tar = f"openpnp-il-{sha[:7]}.tar.gz"
    return [tar, tar + ".sha256", "BUILD-INFO.json"]


def create(a, gh, runner):
    notes = (f"Green build of {a.branch} at {a.sha} (CI run {a.run_url}). Installed on the lab computer by "
             "infra/deploy when the machine is idle.")
    p = runner([gh, "release", "create", a.tag, "--repo", a.repo, "--target", a.sha, "--prerelease", "--latest=false",
                "--title", a.tag, "--notes", notes] + [os.path.join(a.dir, n) for n in release_files(a.sha)],
               capture_output=True, text=True, timeout=900)
    sys.stdout.write(p.stdout or "")
    if p.returncode != 0:
        print(f"::error::gh release create {a.tag} failed: {il_deploy._said(p)}")
        return 1
    print(f"published {a.tag}")
    return 0


def not_this_runs(rel, a, client, gh, runner, work):
    """(why the release rel is not what this run published, [] if it is; then the run attempt that attested it)."""
    out = []
    author = (rel.get("author") or {}).get("login")
    if author != il_deploy.RELEASE_AUTHOR:
        out.append(f"published by {author!r}, not by CI ({il_deploy.RELEASE_AUTHOR})")
    if rel.get("target_commitish") != a.sha:
        out.append(f"at {rel.get('target_commitish')!r}, not {a.sha}")
    want = sorted(release_files(a.sha))
    names = sorted(str(x.get("name")) for x in rel.get("assets") or [])
    if names != want:
        out.append(f"its assets are {names}, not {want}")
    if out:
        return out, None
    tar, sums, bi = (os.path.join(work, n) for n in release_files(a.sha))
    assets = {x["name"]: x for x in rel["assets"]}
    try:
        client.download(assets["BUILD-INFO.json"], bi)
        info = il_deploy.read_json(bi)
        if not isinstance(info, dict):
            return ["its BUILD-INFO.json is not a JSON object"], None
        got = {k: str(info.get(k)) for k in ("run_id", "sha", "version", "branch")}
        mine = {"run_id": str(a.run_id), "sha": a.sha, "version": a.tag, "branch": a.branch}
        bad = [f"{k} {got[k]!r}, not {v!r}" for k, v in mine.items() if got[k] != v]
        if bad:
            return [f"its BUILD-INFO.json is not this run's: {'; '.join(bad)}"], None
        client.download(assets[os.path.basename(tar)], tar)
        client.download(assets[os.path.basename(sums)], sums)
    except il_deploy.GitHubError as e:
        return [f"cannot download it: {e}"], None
    try:
        il_deploy.Deployer._verify_sha256(tar, sums, os.path.basename(tar))
    except il_deploy.Refused as e:
        return [str(e)], None
    p = runner([gh] + il_deploy.GhAttestation.command(tar, a.repo, a.branch, a.sha), capture_output=True, text=True,
               timeout=300)
    if p.returncode != 0:
        return [f"gh attestation verify does not verify its tarball as {il_deploy.CI_WORKFLOW}'s on {a.branch} at "
                f"{a.sha[:7]} ({il_deploy._said(p)})"], None
    try:
        entries = json.loads(p.stdout)
        judged = [(e, il_deploy.attestation_problems(e, a.repo, a.branch, a.sha, a.run_id)) for e in entries]
    except (ValueError, KeyError, TypeError, AttributeError) as e:
        return [f"cannot read gh attestation verify's JSON: {type(e).__name__}: {e}"], None
    ok = [e for e, problems in judged if not problems]
    if not ok:
        return ["its tarball is attested, but not by this run: "
                + (" | ".join("; ".join(problems) for _, problems in judged) or "no attestation in gh's output")], None
    return [], ok[0]["verificationResult"]["signature"]["certificate"]["runInvocationURI"]


def main(argv=None, runner=subprocess.run):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    for k in ("dir", "tag", "sha", "repo", "branch", "run-id", "run-url"):
        ap.add_argument("--" + k, required=True)
    ap.add_argument("--gh", default="gh")
    a = ap.parse_args(argv)
    parsed = il_deploy.parse_tag(a.tag)
    if not parsed or len(a.sha) != 40 or parsed[1] != a.sha[:7]:
        print(f"::error::tag {a.tag} is not an il-build-<run>-<sha7> tag of {a.sha}")
        return 2
    client = il_deploy.GhCliClient(a.gh, runner)
    try:
        rel = client.get_json(f"repos/{a.repo}/releases/tags/{a.tag}")
    except il_deploy.GitHubError as e:
        if e.status != 404:
            print(f"::error::cannot look up release {a.tag}: {e}")
            return 1
        return create(a, a.gh, runner)
    work = tempfile.mkdtemp(prefix="il-publish-")
    try:
        problems, attested = not_this_runs(rel, a, client, a.gh, runner, work)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    if problems:
        print(f"::error::release {a.tag} is already there and is not this run's ({a.run_url}): " + "; ".join(problems)
              + ". This run's build is not published.")
        return 1
    print(f"release {a.tag} is this run's own, published by an earlier attempt: its tarball is attested by {attested}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
