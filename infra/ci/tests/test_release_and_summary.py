"""Tests for infra/ci/make_release.py (the versioned build the deployer installs) and surefire_summary.py.

Run from the repo root: python3 -m unittest discover -s infra/ci/tests -t infra/ci
"""
import hashlib
import json
import os
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import make_release  # noqa: E402
import surefire_summary  # noqa: E402

SHA = "0123456789abcdef0123456789abcdef01234567"


class MakeReleaseTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.jar = os.path.join(self.d, "openpnp-gui-0.0.1-alpha-SNAPSHOT.jar")
        with open(self.jar, "wb") as f:
            f.write(b"jar bytes")
        self.lib = os.path.join(self.d, "lib")
        os.makedirs(self.lib)
        for n in ("a.jar", "b.jar"):
            with open(os.path.join(self.lib, n), "wb") as f:
                f.write(n.encode())
        self.out = os.path.join(self.d, "rel")

    def make(self, **kw):
        args = dict(jar=self.jar, lib=self.lib, tag="il-build-7-0123456", sha=SHA, branch="il/integration",
                    run_id="99", run_number="7", run_url="https://example/run/99", out=self.out)
        args.update(kw)
        return make_release.make(**args)

    def test_contract(self):
        self.make()
        tgz = os.path.join(self.out, "openpnp-il-0123456.tar.gz")
        with tarfile.open(tgz) as t:
            names = sorted(t.getnames())
            self.assertEqual(names, ["openpnp-il", "openpnp-il/BUILD-INFO.json", "openpnp-il/VERSION.txt",
                                     "openpnp-il/lib", "openpnp-il/lib/a.jar", "openpnp-il/lib/b.jar",
                                     "openpnp-il/openpnp-il.jar"])
            self.assertEqual(t.extractfile("openpnp-il/VERSION.txt").read().decode().strip(), "il-build-7-0123456")
            inner = json.load(t.extractfile("openpnp-il/BUILD-INFO.json"))
        with open(os.path.join(self.out, "BUILD-INFO.json")) as f:
            info = json.load(f)
        self.assertEqual(info, inner)
        for k in ("version", "sha", "branch", "run_id", "run_number", "run_url", "built_at", "jar_sha256"):
            self.assertIn(k, info)
        self.assertEqual(info["version"], "il-build-7-0123456")
        self.assertEqual(info["sha"], SHA)
        self.assertEqual(info["branch"], "il/integration")
        self.assertEqual(info["jar_sha256"], hashlib.sha256(b"jar bytes").hexdigest())
        with open(tgz + ".sha256") as f:
            line = f.read()
        with open(tgz, "rb") as f:
            self.assertEqual(line, "%s  openpnp-il-0123456.tar.gz\n" % hashlib.sha256(f.read()).hexdigest())

    def test_refuses_a_tag_that_does_not_name_the_sha(self):
        with self.assertRaises(ValueError):
            self.make(tag="il-build-7-fffffff")
        with self.assertRaises(ValueError):
            self.make(sha="0123456")

    def test_refuses_empty_lib(self):
        for n in os.listdir(self.lib):
            os.remove(os.path.join(self.lib, n))
        with self.assertRaises(ValueError):
            self.make()


REPORT = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="org.x.%(n)s" time="%(t)s" tests="%(tests)d" errors="%(e)d" skipped="%(s)d" failures="%(f)d">
  <testcase name="ok" classname="org.x.%(n)s" time="0.1"/>
  %(extra)s
</testsuite>
"""


class SurefireSummaryTest(unittest.TestCase):
    def test_totals_and_failures(self):
        d = tempfile.mkdtemp()
        with open(os.path.join(d, "TEST-org.x.A.xml"), "w") as f:
            f.write(REPORT % dict(n="A", t="1.5", tests=3, e=0, s=1, f=1,
                                  extra='<testcase name="bad" classname="org.x.A"><failure message="boom"/></testcase>'))
        with open(os.path.join(d, "TEST-org.x.B.xml"), "w") as f:
            f.write(REPORT % dict(n="B", t="2.0", tests=2, e=1, s=0, f=0,
                                  extra='<testcase name="err" classname="org.x.B"><error message="npe"/></testcase>'))
        md = surefire_summary.summary(d)
        self.assertIn("| 5 | 1 | 1 | 1 |", md)
        self.assertIn("org.x.A.bad", md)
        self.assertIn("org.x.B.err", md)

    def test_no_reports(self):
        self.assertIn("no surefire reports", surefire_summary.summary(tempfile.mkdtemp()))


if __name__ == "__main__":
    unittest.main()
