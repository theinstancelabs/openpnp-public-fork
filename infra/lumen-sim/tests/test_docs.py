"""Safety statements of the README that a reader acts on, checked against what they claim."""
import os
import re
import shutil
import subprocess
import tempfile
import unittest

README = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "README.md")
SERIAL = ('<serial line-ending-type="LF" port-name="/dev/serial/by-id/usb-Arduino_LLC_Lumen-if00" '
          'usb-serial-number="387932683335" usb-description="LumenPnP Motherboard" baud="115200" '
          'name="SerialPortCommunications"/>')


def run_section():
    with open(README, encoding="utf-8") as f:
        text = f.read()
    return text[text.index("## Run it"):text.index("## What it models")]


class UsbIdentity(unittest.TestCase):
    """The fork's SerialPortMatcher looks up usb-serial-number before port-name: a config that keeps the real
    board's identity opens the real board while lumen-sim runs."""

    def test_the_readme_says_the_identity_must_go_and_never_offers_to_keep_it(self):
        text = run_section()
        self.assertNotIn("keep them", text)
        self.assertRegex(text, r"\*\*Remove `usb-serial-number` and `usb-description`")
        self.assertIn("--controller pty", text)

    def test_the_readme_strip_command_removes_the_identity_and_points_at_the_link(self):
        blocks = re.findall(r"```sh\n(.*?)```", run_section(), re.S)
        (cmd,) = [b for b in blocks if "usb-" in b]
        tmp = tempfile.mkdtemp(prefix="lumen-sim-test-")
        self.addCleanup(shutil.rmtree, tmp, True)
        with open(os.path.join(tmp, "machine.xml"), "w") as f:
            f.write("<machine>\n  <driver>\n    %s\n  </driver>\n</machine>\n" % SERIAL)
        subprocess.run(["bash", "-c", cmd], cwd=tmp, check=True, timeout=10)
        with open(os.path.join(tmp, "machine.xml")) as f:
            out = f.read()
        self.assertNotIn("usb-serial-number", out)
        self.assertNotIn("usb-description", out)
        self.assertIn('port-name="/tmp/lumen/ttyLUMEN"', out)
        self.assertIn('baud="115200" name="SerialPortCommunications"/>', out)


class SimCapScenarios(unittest.TestCase):
    """Review round 4: the README sent `register`, `tips` and `padcal` to --sim-cap without saying they fail on the
    pty until BR-69 feeds the SIM vacuum and paste there; a CI gate built from it would fail on every PR."""

    def test_the_use_it_line_says_they_fail_on_the_pty_until_br69(self):
        with open(README, encoding="utf-8") as f:
            text = f.read()
        section = text[text.index("## The simulator line"):]
        section = section[:section.index("\n## ")]
        bullet = section[section.index("- **Use it**"):]
        bullet = bullet[:bullet.index("\n- ")]
        for scenario in ("register", "tips", "padcal"):
            self.assertIn("`%s`" % scenario, bullet)
        self.assertIn("BR-69", bullet)
        self.assertRegex(bullet, r"still fail on the pty")


if __name__ == "__main__":
    unittest.main()
