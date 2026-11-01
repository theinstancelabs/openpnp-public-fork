"""Tests for infra/ci/lab_layout.py (lays the lab computer's file layout out on a CI runner from infra/fixtures).

Run from the repo root: python3 -m unittest discover -s infra/ci/tests
"""
import filecmp
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import lab_layout  # noqa: E402

REPO = lab_layout.REPO


class LabLayoutTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.home = "/home/ci"

    def p(self, *parts):
        return os.path.join(self.root, *[x.lstrip("/") for x in parts])

    def test_lays_out_every_path_the_gates_read(self):
        lab_layout.lay_out(self.root, self.home)
        cfg = self.p(self.home, ".openpnp2")
        for f in ("machine.xml", "packages.xml", "parts.xml", "vision-settings.xml", "panels.xml", "boards.xml"):
            self.assertTrue(os.path.isfile(os.path.join(cfg, f)), f)
        self.assertTrue(os.path.isdir(os.path.join(cfg, "scripts")))
        board = self.p(self.home, "Desktop", "ftp.board.xml")
        self.assertTrue(filecmp.cmp(board, os.path.join(REPO, "src/test/resources/kicad/ftp.board.xml"), shallow=False))
        # boards.xml names the board where the program will find it (the runtime path, not the test root)
        with open(os.path.join(cfg, "boards.xml")) as f:
            self.assertIn("<board>/home/ci/Desktop/ftp.board.xml</board>", f.read())
        lab = self.p("/home/lumen/instancelabs")
        for f in ("config/dispense-config.json", "config/machine.xml.patched", "runs/purge_spots.json",
                  "runs/p18_board1.json", "runs/p18_board1/timing.json", "runs/p16_board1/dots.csv"):
            self.assertTrue(os.path.isfile(os.path.join(lab, f)), f)

    def test_patched_machine_xml_is_make_machine_patch_of_the_fixture(self):
        lab_layout.lay_out(self.root, self.home)
        ref = os.path.join(tempfile.mkdtemp(), "ref.xml")
        subprocess.check_call([sys.executable, "-I", os.path.join(REPO, "tools/instancelabs/make_machine_patch.py"),
                               "--in", os.path.join(lab_layout.FIXTURES, "lab-config", "machine.xml"), "--out", ref],
                              stdout=subprocess.DEVNULL)
        self.assertTrue(filecmp.cmp(ref, self.p("/home/lumen/instancelabs/config/machine.xml.patched"), shallow=False))

    def test_never_overwrites(self):
        lab_layout.lay_out(self.root, self.home)
        with self.assertRaises(lab_layout.Refused):
            lab_layout.lay_out(self.root, self.home)

    def test_refuses_on_the_lab_computer(self):
        fake_root = tempfile.mkdtemp()
        os.makedirs(os.path.join(fake_root, "home/lumen/instancelabs/runs"))
        open(os.path.join(fake_root, "home/lumen/instancelabs/runs/serial.log"), "w").close()
        with self.assertRaises(lab_layout.Refused):
            lab_layout.lay_out(fake_root, self.home)

    def test_fixture_has_no_legacy_automation_scripts(self):
        names = [f for _, _, fs in os.walk(os.path.join(lab_layout.FIXTURES, "lab-config")) for f in fs]
        self.assertNotIn("Automation_Reviewed_Command.js", names)
        self.assertNotIn("Paste_Experiment_Console.js", names)


if __name__ == "__main__":
    unittest.main()
