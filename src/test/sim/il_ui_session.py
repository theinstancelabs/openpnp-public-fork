#!/usr/bin/env python3
"""il_ui_session.py — a SIM-mode OpenPnP with the web UI ready to look at (UI work, screenshots, QA).

  python3 src/test/sim/il_ui_session.py start --jar target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar --name ui-qa1
  python3 src/test/sim/il_ui_session.py stop --name ui-qa1

start: launches openpnp-uinav in SIM mode (never the serial port or cameras), enables and homes the simulated
machine, and prepares it like the sim acceptance does (needle Z frame from K = 65.59, blank surface and purge row,
start angle confirmed, chip-0603 characterized), then plans ~/Desktop/ftp.board.xml with the board surface tied to
the plan. --clean-check keeps the clean-pad guard on (the sim's top camera is a static picture, so every pad reads
dirty: useful to see that state). Prints one JSON line: {"session", "url", "port", "planId"}. The session keeps
running until 'stop'.
"""
import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import il_sim_acceptance as acc  # noqa: E402


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("what", choices=["start", "stop"])
    ap.add_argument("--name", default="ui", help="session name (prefix il-ui- is added)")
    ap.add_argument("--clean-check", action="store_true", help="keep the clean-pad guard on")
    ap.add_argument("--no-plan", action="store_true", help="do not plan the board")
    for k, v in acc.DEF.items():
        ap.add_argument("--" + k.replace("_", "-"), dest=k, default=v)
    a = ap.parse_args(argv)
    a.prefix = "il-ui-"
    a.jar = os.path.abspath(a.jar)
    if a.what == "stop":
        os.system("%s --session %s stop >/dev/null 2>&1" % (acc.UINAV, a.prefix + a.name))
        print(json.dumps({"stopped": a.prefix + a.name}))
        return 0
    a.out = os.path.join("/tmp", "il-ui-session-" + a.name)
    os.makedirs(a.out, exist_ok=True)
    s = acc.Session(a, a.name)
    acc.setup_machine(s)
    if a.clean_check:
        s.api("PUT", "/api/config", {"cleanPadCheck": {"enabled": True}})
    plan_id = None
    if not a.no_plan:
        plan_id = acc.plan_board(s)[0]["planId"]
    print(json.dumps({"session": s.name, "url": s.base + "/", "port": s.port, "planId": plan_id}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
