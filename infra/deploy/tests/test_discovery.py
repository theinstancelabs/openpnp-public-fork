"""Each test module imports with only tests/ on sys.path, as `unittest discover -s infra/deploy/tests` (no -t)
imports them; tests/__init__.py does not run then."""
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


class BareDiscovery(unittest.TestCase):
    def test_every_module_imports_by_bare_name(self):
        mods = sorted(n[:-3] for n in os.listdir(HERE) if n.startswith("test_") and n.endswith(".py"))
        self.assertIn("test_deploy_flow", mods)
        code = f"import sys; sys.path.insert(0, {HERE!r})\n" + "".join(f"import {m}\n" for m in mods)
        with tempfile.TemporaryDirectory() as cwd:
            p = subprocess.run([sys.executable, "-c", code], cwd=cwd, capture_output=True, text=True, timeout=60)
        self.assertEqual(p.returncode, 0, p.stderr[-2000:])


if __name__ == "__main__":
    unittest.main()
