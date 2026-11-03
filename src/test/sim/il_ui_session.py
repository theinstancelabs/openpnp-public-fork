#!/usr/bin/env python3
"""il_ui_session.py — a SIM-mode OpenPnP with the web UI ready to look at (UI work, screenshots, QA).

  python3 src/test/sim/il_ui_session.py start --jar target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar --name ui-qa1
  python3 src/test/sim/il_ui_session.py start --name ui-qa2 --kicad src/test/resources/kicad/ftp.kicad_pcb.gz --register
  python3 src/test/sim/il_ui_session.py start --name ui-qa3 --padcal
  python3 src/test/sim/il_ui_session.py stop --name ui-qa1

start: launches openpnp-uinav in SIM mode (never the serial port or cameras), enables and homes the simulated
machine, and prepares it like the sim acceptance does (needle Z frame from K = 65.59, blank surface and purge row,
start angle confirmed, chip-0603 characterized, the simulator's probe timings), then plans ~/Desktop/ftp.board.xml
with the board surface tied to the plan. The SIM top camera draws the board where the SIM scene says it truly sits:
0.60 / -0.45 mm and +0.20 deg off the planned seat, scaled 1.002 / 0.997 and sheared 0.1 deg (AUTO_REGISTER_DESIGN.md
§9.5), so the planned dots miss the pads until the board is registered.

  --register     plan, then Register board (home if needed, the fiducials, the N1 probe; about a minute) instead of
                 tying the bench's surface points: the plan made then is on the true pads
  --kicad FILE   upload this KiCad board (.kicad_pcb, or .kicad_pcb.gz) through the web API (preview, then save,
                 replacing an import of the same name in the session copy) and plan it instead of the bench board;
                 the SIM scene then shows it
  --clean-check  keep the clean-pad guard on (the SIM camera draws the pads, so it sees the true seat)
  --no-plan      do not plan
  --padcal       missing paste profiles and the pad calibration (PROFILE_CALIBRATION_DESIGN.md §11, §14.5): the SIM
                 scene gets the SIM paste (2.30 mg per turn, not the engine's 2.11), the two weigh coupons, the SIM
                 balance and the bench blank colour; the blank surface, the purge row, the calibration area, the blank
                 place and the coupon places are set on the SIM blank and coupons as the sim acceptance's padcal
                 scenario sets them; then src/test/resources/padprofile/padcal.kicad_pcb (or --kicad FILE) is
                 uploaded and planned where it truly sits, its board surface tied. Profile › Needs a profile lists
                 its groups; Calibrate automatically runs in the SIM. On the balance's display you are the operator:
                   POST /api/sim/balance/load {"place": "coupon1"}   then GET /api/sim/balance (stable, display)
                   POST /api/sim/paste/wipe {"region": "coupon1"}     then load and read it again, type both readings
  --balance MODE with --padcal: display (default, you read it) or connected (the job reads the SIM balance itself and
                 an emulated operator wipes the coupons between the readings)
  --api-port N   the web API on the first free port of N … N+8 (default any free port)
  --tip-camera   the SIM bottom camera (PROFILE_CALIBRATION_DESIGN.md §22.8): it draws the tip on the head end-on when
                 N2 is over it, so "Take a picture from below" has a picture
  --physical-tip KEY  the tip "on the head" (the SIM scene's physicalTip; default: the mounted setup): the SIM bottom
                 camera draws it and, with --padcal, the SIM paste lays its dots, so mounting another setup than the
                 one on the head can be tried (the landing question, the control naming another tip)
  --paste-by-tip KEY:mRevMg=M,c=C,e=E,landing=DX:DY  with --padcal: a tip setup's own SIM paste (§22.8; repeatable),
                 e.g. 25g-straight:mRevMg=1.6,c=0.6,landing=2.4:-0.4 (smaller dots, lands 2.4, -0.4 mm off)

Prints one JSON line: {"session", "url", "port", "planId", "board", "config"} (config: the session's copy, where
il_sim_scene.py amend/write changes the SIM scene), with --register
"registration": {"status", "seat", "movedMm", "newPlanId", "message"}, and with --padcal "preview" (the upload's
NEEDS_PROFILE warning) and "needsProfile": the plan's groups [{"group", "label", "reason", "fixable", "suggestion"}].
The session keeps running until 'stop'.
"""
import argparse
import gzip
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import il_sim_acceptance as acc  # noqa: E402
import il_sim_scene as scn  # noqa: E402

# Where the board truly sits (AUTO_REGISTER_DESIGN.md §9.5): the planned seat, re-seated, scaled and sheared.
TRUE_SEAT = scn.reseat(scn.seat(acc.BOARD_LOC["x"], acc.BOARD_LOC["y"], acc.BOARD_LOC["rotation"], 1.002, 0.997, 0.1),
                       0.60, -0.45, 0.20)


def scene_of(board, config_dir):
    return scn.scene(board, TRUE_SEAT, acc.BOARD_PLANE, config_dir=config_dir, vacuum=scn.FAST_VACUUM)


def tip_scene(s, a):
    """--physical-tip and --paste-by-tip: the session's SIM scene amended in place (it reloads on its stamp)."""
    if a.physical_tip is None and not a.paste_by_tip:
        return
    with open(scn.scene_path(s.cfg)) as f:
        sc = json.load(f)
    for text in a.paste_by_tip:
        scn.paste_by_tip(sc, *scn.parse_paste_by_tip(text))
    if a.physical_tip is not None:
        scn.physical_tip(sc, a.physical_tip)
    scn.write(s.cfg, sc)


def padcal(s, a):
    """--padcal: the sim acceptance's padcal set-up, the board uploaded where it truly sits and planned (or registered)."""
    acc.padcal_setup(s, balance=a.balance)
    s.api("PUT", "/api/config", {"cleanPadCheck": {"enabled": bool(a.clean_check)}})
    path = a.kicad or acc.KICAD_PADCAL
    pv, board = acc.padcal_import(s, path, os.path.basename(path).split(".kicad_pcb")[0])
    sc = acc.padcal_scene(s, board, balance=a.balance)
    tip_scene(s, a)
    out = {"session": s.name, "url": s.base + "/", "port": s.port, "planId": None, "board": board, "config": s.cfg,
           "preview": acc.preview_summary(pv)["needs_profile"], "needsProfile": None}
    if not a.no_plan:
        if a.register:
            p = s.api("POST", "/api/plan", dict(acc.BOARD_LOC, source="file", file=board), timeout=120)
            r = acc.register(s, p["planId"])
            out["registration"] = {k: r.get(k) for k in ("status", "seat", "movedMm", "newPlanId", "message")}
            out["planId"] = r.get("newPlanId") or p["planId"]
        else:
            out["planId"] = acc.tied_plan(s, board, sc)["planId"]
        check = s.api("GET", "/api/pad-profile/check?planId=" + out["planId"])
        out["needsProfile"] = [{"group": g["group"], "label": g["label"], "reason": g["reason"],
                                "fixable": g.get("fixable"), "suggestion": (g.get("suggestion") or {}).get("sentence")}
                               for g in check["groups"]]
    print(json.dumps(out))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("what", choices=["start", "stop"])
    ap.add_argument("--name", default="ui", help="session name (the prefix is added)")
    ap.add_argument("--prefix", default="il-ui-", help="uinav session name prefix")
    ap.add_argument("--clean-check", action="store_true", help="keep the clean-pad guard on")
    ap.add_argument("--no-plan", action="store_true", help="do not plan the board")
    ap.add_argument("--register", action="store_true", help="plan, then Register board (about a minute)")
    ap.add_argument("--kicad", help="upload this KiCad board (.kicad_pcb[.gz]) and plan it instead of the bench board")
    ap.add_argument("--padcal", action="store_true", help="the pad calibration's SIM set-up and padcal.kicad_pcb")
    ap.add_argument("--balance", choices=["display", "connected"], default="display", help="--padcal: the SIM balance")
    ap.add_argument("--api-port", type=int, help="the web API on the first free port of N … N+8")
    ap.add_argument("--tip-camera", action="store_true", help="the SIM bottom camera that draws the tip on the head")
    ap.add_argument("--physical-tip", metavar="KEY", help="the tip on the head (SIM scene physicalTip; default: the "
                    "mounted setup)")
    ap.add_argument("--paste-by-tip", action="append", default=[], metavar="KEY:mRevMg=M,c=C,e=E,landing=DX:DY",
                    help="with --padcal: a tip setup's own SIM paste (repeatable)")
    for k, v in acc.DEF.items():
        ap.add_argument("--" + k.replace("_", "-"), dest=k, default=v)
    a = ap.parse_args(argv)
    a.jar = os.path.abspath(a.jar)
    if a.paste_by_tip and not a.padcal:
        ap.error("--paste-by-tip needs --padcal (the SIM paste)")
    for text in a.paste_by_tip:
        try:
            scn.parse_paste_by_tip(text)
        except ValueError as e:
            ap.error(str(e))
    if a.what == "stop":
        os.system("%s --session %s stop >/dev/null 2>&1" % (acc.UINAV, a.prefix + a.name))
        print(json.dumps({"stopped": a.prefix + a.name}))
        return 0
    a.out = os.path.join("/tmp", a.prefix + "session-" + a.name)
    os.makedirs(a.out, exist_ok=True)
    s = acc.Session(a, a.name, scene=scene_of,
                    xml_sets=acc.SIM_XML_SETS + ([scn.TIP_CAMERA_XML_SET] if a.tip_camera else []))
    if a.padcal:
        return padcal(s, a)
    acc.setup_machine(s)
    s.api("PUT", "/api/config", dict(scn.FAST_TIMINGS, cleanPadCheck={"enabled": bool(a.clean_check)}))
    board = acc.session_board(s)
    if a.kicad:
        raw = open(a.kicad, "rb").read()
        data = gzip.decompress(raw) if a.kicad.endswith(".gz") else raw
        file_name = os.path.basename(a.kicad)[:-3] if a.kicad.endswith(".gz") else os.path.basename(a.kicad)
        name = os.path.splitext(file_name)[0]
        q = acc.urllib.parse.quote
        pv = acc.upload(s, "/api/boards/import?file=" + q(file_name), data)
        saved = acc.upload(s, "/api/boards/import/save?file=%s&name=%s&replace=1&sha256=%s&decisions=%s"
                           % (q(file_name), q(name), pv["file"]["sha256"], q(pv["decisions"])), data)
        board = saved["file"]
        scn.write(s.cfg, scene_of(board, s.cfg))
    tip_scene(s, a)
    out = {"session": s.name, "url": s.base + "/", "port": s.port, "planId": None, "board": board, "config": s.cfg}
    if not a.no_plan:
        if a.register:
            acc.log("Register board: home if needed, 3 fiducials, 5 N1 probe points (about a minute in the SIM)")
            p = s.api("POST", "/api/plan", dict(acc.BOARD_LOC, source="file", file=board), timeout=120)
            r = acc.register(s, p["planId"])
            out["registration"] = {k: r.get(k) for k in ("status", "seat", "movedMm", "newPlanId", "message")}
            out["planId"] = r.get("newPlanId") or p["planId"]
        else:
            out["planId"] = acc.plan_board(s, board)[0]["planId"]
    print(json.dumps(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
