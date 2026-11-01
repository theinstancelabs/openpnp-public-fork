#!/usr/bin/env python3
"""CI wrapper around the backend's SIM acceptance (src/test/sim/il_sim_acceptance.py).

il_sim_acceptance.py exits 0 even when a scenario fails; it writes the verdicts into results.json. This wrapper runs
a shard of scenarios one invocation each (its own --out and session prefix), keeps going after a failure, then reads
every results.json and fails unless every expected check passed.

  acceptance.py run --shard "tips board padcal:main,nano" --out DIR --jar JAR [-- extra il_sim_acceptance args]
  acceptance.py check RESULTS.json --expect ITEM

ITEM: load | stop | board | register | import | tips | padcal | padcal:<part>[,<part>...]  (parts: main nano controls)
A results file passes ITEM when the run finished, live_config_untouched is true, and ITEM's verdicts are all true:
stop is a list with one row per variant, all passing; padcal:<parts> reads padcal.<part>.pass for each part (with a
subset, il_sim_acceptance sets padcal.pass false by design); every other ITEM reads <ITEM>.pass.
"""
import argparse
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
ACCEPTANCE = os.path.join(REPO, "src", "test", "sim", "il_sim_acceptance.py")
SCENARIOS = ("load", "stop", "board", "register", "import", "tips", "padcal")
PADCAL_PARTS = ("main", "nano", "controls")


def parse_item(item):
    """'padcal:main,nano' -> ('padcal', ('main', 'nano')); 'tips' -> ('tips', ())."""
    name, _, parts = item.partition(":")
    if name not in SCENARIOS:
        raise ValueError("unknown scenario %r (one of %s)" % (name, ", ".join(SCENARIOS)))
    parts = tuple(p for p in parts.split(",") if p)
    if parts and name != "padcal":
        raise ValueError("only padcal takes parts: %r" % item)
    bad = set(parts) - set(PADCAL_PARTS)
    if bad:
        raise ValueError("unknown padcal part(s) %s in %r" % (", ".join(sorted(bad)), item))
    return name, parts


def check(path, item):
    """(ok, lines): whether results.json at path passes item, one line per verdict read."""
    name, parts = parse_item(item)
    try:
        with open(path) as f:
            r = json.load(f)
    except (OSError, ValueError) as e:
        return False, ["no results: %s (%s)" % (path, e)]
    lines, ok = [], True

    def verdict(label, value, why=""):
        nonlocal ok
        good = value is True
        ok = ok and good
        lines.append("%s %s%s" % ("PASS" if good else "FAIL", label, (": " + why) if why and not good else ""))

    verdict("finished", "finished" in r or None, "the run did not finish (crashed or killed)")
    verdict("live_config_untouched", r.get("live_config_untouched"), "the source config was modified")
    res = r.get(name)
    if res is None:
        verdict(item, None, "no %r in results" % name)
    elif name == "stop":
        if not isinstance(res, list) or not res:
            verdict("stop", None, "no stop variants ran")
        for row in res if isinstance(res, list) else []:
            verdict("stop %s/%s" % (row.get("what"), row.get("how")), row.get("pass"), row.get("error", ""))
    elif name == "padcal" and parts:
        for p in parts:
            pr = res.get(p) if isinstance(res, dict) else None
            pr = pr if isinstance(pr, dict) else {}
            verdict("padcal:" + p, pr.get("pass"), pr.get("error", "no result"))
    else:
        verdict(name, res.get("pass") if isinstance(res, dict) else None,
                res.get("error", "") if isinstance(res, dict) else "not a result object")
    return ok, lines


def plan(shard, out, jar):
    """One il_sim_acceptance invocation per item of the shard (a space-separated item list)."""
    items = shard.split()
    if not items:
        raise ValueError("empty shard")
    if len(set(items)) != len(items):
        raise ValueError("duplicate items in shard %r" % shard)
    cmds = []
    for item in items:
        name, parts = parse_item(item)
        slug = "-".join((name,) + parts)
        d = os.path.join(out, slug)
        args = [name] + (["--padcal-parts", ",".join(parts)] if parts else [])
        args += ["--jar", jar, "--out", d, "--prefix", "ci-%s-" % slug]
        cmds.append({"item": item, "args": args, "out": d, "results": os.path.join(d, "results.json")})
    return cmds


def default_runner(args, log_path):
    with open(log_path, "w") as log:
        return subprocess.call([sys.executable, "-u", ACCEPTANCE] + args, stdout=log, stderr=subprocess.STDOUT)


def run(shard, out, jar, extra=(), runner=default_runner, stream=sys.stdout):
    """Run every item (even after a failure), then check each; True only if every item passed."""
    os.makedirs(out, exist_ok=True)
    summary, all_ok = [], True
    for c in plan(shard, out, jar):
        os.makedirs(c["out"], exist_ok=True)
        log_path = os.path.join(out, os.path.basename(c["out"]) + ".log")
        print("== %s: il_sim_acceptance.py %s" % (c["item"], " ".join(c["args"] + list(extra))), file=stream, flush=True)
        rc = runner(c["args"] + list(extra), log_path)
        ok, lines = check(c["results"], c["item"])
        if rc != 0:
            ok = False
            lines.append("FAIL exit code %d (log: %s)" % (rc, log_path))
        for line in lines:
            print("   " + line, file=stream)
        print("%s %s" % ("PASS" if ok else "FAIL", c["item"]), file=stream, flush=True)
        summary.append((c["item"], ok, [x for x in lines if x.startswith("FAIL")]))
        all_ok = all_ok and ok
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a") as f:
            f.write("### SIM acceptance: %s\n\n| Item | Result | Failed checks |\n|---|---|---|\n" % shard)
            for item, ok, fails in summary:
                f.write("| %s | %s | %s |\n" % (item, "pass" if ok else "**FAIL**", "; ".join(fails)[:500]))
    return all_ok


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--shard", required=True)
    r.add_argument("--out", required=True)
    r.add_argument("--jar", required=True)
    r.add_argument("extra", nargs="*", help="after --: passed to every il_sim_acceptance invocation")
    c = sub.add_parser("check")
    c.add_argument("results")
    c.add_argument("--expect", required=True)
    a = ap.parse_args(argv)
    if a.cmd == "run":
        return 0 if run(a.shard, os.path.abspath(a.out), os.path.abspath(a.jar), a.extra) else 1
    ok, lines = check(a.results, a.expect)
    print("\n".join(lines))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
