#!/usr/bin/env python3
"""Lay out the lab computer's files on a CI runner, from infra/fixtures, where the backend's SIM gates read them.

  lab_layout.py [--root /] [--home $HOME]

The gates (src/test/web/run-*.sh, src/test/sim/il_sim_acceptance.py, il_ui_session.py) default to host paths:
~/.openpnp2 (the lab config), ~/Desktop/ftp.board.xml, /home/lumen/instancelabs/config/{machine.xml.patched,
dispense-config.json} and /home/lumen/instancelabs/runs/ (bench files; hard-coded). This puts copies there so the
gates run unchanged. The patched machine.xml is made by tools/instancelabs/make_machine_patch.py from the fixture,
exactly as on the host. --root prefixes every path (tests). It never overwrites a file and refuses to run on the lab
computer itself (where runs/serial.log exists). Needs /home/lumen writable (CI: sudo install -d -o $USER /home/lumen).
"""
import argparse
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
FIXTURES = os.path.join(REPO, "infra", "fixtures")
LAB = "/home/lumen/instancelabs"
BOARD = os.path.join(REPO, "src", "test", "resources", "kicad", "ftp.board.xml")


class Refused(Exception):
    pass


def under(root, path):
    return os.path.join(root, path.lstrip("/"))


def copy_new(src, dst):
    """Copy a file or a tree; refuse if anything is already there."""
    if os.path.lexists(dst):
        raise Refused("already exists, not overwriting: " + dst)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if os.path.isdir(src):
        shutil.copytree(src, dst)
    else:
        shutil.copy2(src, dst)


def lay_out(root, home):
    if os.path.exists(under(root, LAB + "/runs/serial.log")):
        raise Refused("this is the lab computer (runs/serial.log exists): the real files are here already")
    cfg = under(root, home + "/.openpnp2")
    targets = [cfg, under(root, home + "/Desktop/ftp.board.xml"), under(root, LAB + "/config"), under(root, LAB + "/runs")]
    existing = [t for t in targets if os.path.lexists(t)]
    if existing:
        raise Refused("already exists, not overwriting: " + ", ".join(existing))
    copy_new(os.path.join(FIXTURES, "lab-config"), cfg)
    board_runtime = os.path.join(home, "Desktop", "ftp.board.xml")
    with open(os.path.join(cfg, "boards.xml"), "w") as f:
        f.write("<openpnp-boards>\n   <board>%s</board>\n</openpnp-boards>\n" % board_runtime)
    copy_new(BOARD, under(root, board_runtime))
    copy_new(os.path.join(FIXTURES, "lab-instancelabs", "config"), under(root, LAB + "/config"))
    copy_new(os.path.join(FIXTURES, "lab-instancelabs", "runs"), under(root, LAB + "/runs"))
    subprocess.check_call([sys.executable, "-I", os.path.join(REPO, "tools", "instancelabs", "make_machine_patch.py"),
                           "--in", os.path.join(cfg, "machine.xml"),
                           "--out", under(root, LAB + "/config/machine.xml.patched")], stdout=subprocess.DEVNULL)
    return [cfg, under(root, board_runtime), under(root, LAB)]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--root", default="/")
    ap.add_argument("--home", default=os.path.expanduser("~"))
    a = ap.parse_args(argv)
    try:
        for p in lay_out(a.root, a.home):
            print("laid out:", p)
    except Refused as e:
        print("refused:", e, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
