#!/usr/bin/env python3
"""Markdown summary of Maven surefire reports for the CI run page.

  surefire_summary.py target/surefire-reports >> "$GITHUB_STEP_SUMMARY"
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def summary(report_dir):
    files = sorted(glob.glob(os.path.join(report_dir, "TEST-*.xml")))
    if not files:
        return "### mvn test\n\nno surefire reports in %s\n" % report_dir
    tot = dict(tests=0, failures=0, errors=0, skipped=0)
    secs, bad = 0.0, []
    for p in files:
        s = ET.parse(p).getroot()
        for k in tot:
            tot[k] += int(s.get(k, 0))
        secs += float(s.get("time", 0) or 0)
        for c in s.iter("testcase"):
            for kind in ("failure", "error"):
                e = c.find(kind)
                if e is not None:
                    bad.append("%s.%s (%s: %s)" % (c.get("classname"), c.get("name"), kind,
                                                   (e.get("message") or "")[:200].replace("|", "/")))
    md = ["### mvn test", "", "| Tests | Failures | Errors | Skipped | Classes | Seconds |", "|---|---|---|---|---|---|",
          "| %d | %d | %d | %d | %d | %.0f |" % (tot["tests"], tot["failures"], tot["errors"], tot["skipped"], len(files), secs)]
    if bad:
        md += ["", "Failed:"] + ["- " + b for b in bad[:100]]
    return "\n".join(md) + "\n"


if __name__ == "__main__":
    sys.stdout.write(summary(sys.argv[1] if len(sys.argv) > 1 else "target/surefire-reports"))
