#!/usr/bin/env python3
"""Package a green build of il/integration as the versioned release the lab computer's deployer installs.

  make_release.py --jar JAR --lib DIR --tag il-build-<run>-<sha7> --sha SHA --branch B --run-id N --run-number N
                  --run-url URL --out DIR

Writes into DIR (the contract with infra/deploy/il_deploy.py):
  openpnp-il-<sha7>.tar.gz          top dir openpnp-il/: openpnp-il.jar, lib/*.jar, VERSION.txt (= the tag; OpenPnP reads
                                    it from its working directory into server.version), BUILD-INFO.json
  openpnp-il-<sha7>.tar.gz.sha256   sha256sum format
  BUILD-INFO.json                   {version, sha, branch, run_id, run_number, run_url, built_at, jar_sha256}
"""
import argparse
import datetime
import hashlib
import io
import json
import os
import sys
import tarfile


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def make(jar, lib, tag, sha, branch, run_id, run_number, run_url, out):
    if len(sha) != 40 or not tag.endswith("-" + sha[:7]):
        raise ValueError("tag %r must end with the first 7 characters of the full 40-character sha %r" % (tag, sha))
    libs = sorted(n for n in os.listdir(lib) if n.endswith(".jar"))
    if not libs:
        raise ValueError("no jars in " + lib)
    info = {"version": tag, "sha": sha, "branch": branch, "run_id": str(run_id), "run_number": str(run_number),
            "run_url": run_url, "built_at": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
            "jar_sha256": sha256_file(jar)}
    info_bytes = (json.dumps(info, indent=1) + "\n").encode()
    os.makedirs(out, exist_ok=True)
    name = "openpnp-il-%s.tar.gz" % sha[:7]
    tgz = os.path.join(out, name)

    def add_bytes(t, arcname, data):
        ti = tarfile.TarInfo(arcname)
        ti.size, ti.mode, ti.mtime = len(data), 0o644, 0
        t.addfile(ti, io.BytesIO(data))

    def add_dir(t, arcname):
        ti = tarfile.TarInfo(arcname)
        ti.type, ti.mode, ti.mtime = tarfile.DIRTYPE, 0o755, 0
        t.addfile(ti)

    with tarfile.open(tgz, "w:gz") as t:
        add_dir(t, "openpnp-il")
        t.add(jar, "openpnp-il/openpnp-il.jar")
        add_dir(t, "openpnp-il/lib")
        for n in libs:
            t.add(os.path.join(lib, n), "openpnp-il/lib/" + n)
        add_bytes(t, "openpnp-il/VERSION.txt", (tag + "\n").encode())
        add_bytes(t, "openpnp-il/BUILD-INFO.json", info_bytes)
    with open(tgz + ".sha256", "w") as f:
        f.write("%s  %s\n" % (sha256_file(tgz), name))
    with open(os.path.join(out, "BUILD-INFO.json"), "wb") as f:
        f.write(info_bytes)
    return info


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    for k in ("jar", "lib", "tag", "sha", "branch", "run-id", "run-number", "run-url", "out"):
        ap.add_argument("--" + k, required=True)
    a = ap.parse_args(argv)
    info = make(a.jar, a.lib, a.tag, a.sha, a.branch, a.run_id, a.run_number, a.run_url, a.out)
    print(json.dumps(info, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
