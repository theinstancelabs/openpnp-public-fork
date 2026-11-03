#!/usr/bin/env python3
"""The SIM gates' input files from infra/fixtures in one directory, and the env that points the gates at them.

  sim_env.py DIR [--export]

The gates default to the lab computer's paths (~/.openpnp2, ~/Desktop/ftp.board.xml,
/home/lumen/instancelabs/{config,runs}) and read env overrides instead when set (BR-40). This makes DIR (new, or
empty) with
  DIR/openpnp2/                      infra/fixtures/lab-config + a boards.xml listing DIR/ftp.board.xml
  DIR/ftp.board.xml                  src/test/resources/kicad/ftp.board.xml
  DIR/config/machine.xml.patched     tools/instancelabs/make_machine_patch.py of the fixture's machine.xml
  DIR/config/dispense-config.json    infra/fixtures/lab-instancelabs/config
  DIR/runs/                          infra/fixtures/lab-instancelabs/runs (bench run files)
and prints one NAME=path line per override (GITHUB_ENV format; --export: shell 'export' lines for eval). Nothing
outside DIR is written. CI: python3 infra/ci/sim_env.py "$RUNNER_TEMP/lab" >> "$GITHUB_ENV"
"""
import argparse
import os
import shlex
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
FIXTURES = os.path.join(REPO, "infra", "fixtures")
BOARD = os.path.join(REPO, "src", "test", "resources", "kicad", "ftp.board.xml")
PATCH_TOOL = os.path.join(REPO, "tools", "instancelabs", "make_machine_patch.py")


class Refused(Exception):
    pass


def prepare(lab):
    """Lay the files out in lab (created; refused unless new or empty); returns {env name: absolute path}."""
    lab = os.path.abspath(lab)
    if "\n" in lab or "\r" in lab:
        raise Refused("a line break in the path cannot go into GITHUB_ENV: %r" % lab)
    if os.path.exists(lab) and (not os.path.isdir(lab) or os.listdir(lab)):
        raise Refused("not empty, not overwriting: " + lab)
    cfg = os.path.join(lab, "openpnp2")
    board = os.path.join(lab, "ftp.board.xml")
    config = os.path.join(lab, "config")
    runs = os.path.join(lab, "runs")
    os.makedirs(config)
    shutil.copytree(os.path.join(FIXTURES, "lab-config"), cfg)
    shutil.copy2(BOARD, board)
    with open(os.path.join(cfg, "boards.xml"), "w") as f:
        f.write("<openpnp-boards>\n   <board>%s</board>\n</openpnp-boards>\n" % board)
    shutil.copytree(os.path.join(FIXTURES, "lab-instancelabs", "runs"), runs)
    dispense = os.path.join(config, "dispense-config.json")
    shutil.copy2(os.path.join(FIXTURES, "lab-instancelabs", "config", "dispense-config.json"), dispense)
    patched = os.path.join(config, "machine.xml.patched")
    subprocess.check_call([sys.executable, "-I", PATCH_TOOL, "--in", os.path.join(cfg, "machine.xml"), "--out", patched],
                          stdout=subprocess.DEVNULL)
    return {
        "OPENPNP_CONFIG": cfg,  # run-webui-sim-test.sh
        "IL_LIVE_CONFIG": cfg,  # il_sim_acceptance.py, il_ui_session.py, LiveBoardPlanTest
        "IL_TEST_BOARD": board,
        "IL_PATCHED_MACHINE_XML": patched,
        "IL_DISPENSE_CONFIG": dispense,
        "IL_RUNS_DIR": runs,
        "LUMEN_SIM_E2E_CONFIG": cfg,  # infra/lumen-sim/e2e/test_openpnp_pty.py
        "LUMEN_SIM_E2E_DISPENSE_CONFIG": dispense,
    }


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("dir")
    ap.add_argument("--export", action="store_true", help="print 'export NAME=value' lines for a shell's eval")
    a = ap.parse_args(argv)
    try:
        env = prepare(a.dir)
    except Refused as e:
        print("refused:", e, file=sys.stderr)
        return 2
    for k, v in env.items():
        print("export %s=%s" % (k, shlex.quote(v)) if a.export else "%s=%s" % (k, v))
    return 0


if __name__ == "__main__":
    sys.exit(main())
