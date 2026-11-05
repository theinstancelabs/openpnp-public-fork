"""Run a simulated LumenPnP Marlin controller on a pseudo-terminal, or talk to a running one.

usage:
  python3 -m lumen_sim --link PATH [--personality stock|il-safety] [--control SOCK] [--io-log FILE]
                       [--time-scale 1.0] [--park X,Y,Z] [--kill-messages] [--vacuum N] [--surface-z Z]
                       [--seed N] [--sim-cap] [--collision-model PATH]
  python3 -m lumen_sim ctl --control SOCK state
  python3 -m lumen_sim ctl --control SOCK fault '{"fault": "no_ok", "match": "^G4"}'
  python3 -m lumen_sim ctl --control SOCK set '{"vacuum": -1600000}'
  python3 -m lumen_sim ctl --control SOCK power-cycle [--link NEW_PATH] [--down-s SECONDS]
  python3 -m lumen_sim ctl --control SOCK clear | stop

Point OpenPnP's serial port-name at the --link path (a symlink to /dev/pts/N). Its name must start with ttyLUMEN:
while the link is missing, jSerialComm opens /dev/<name> instead. Stop with SIGINT/SIGTERM/SIGHUP or `ctl stop`.
"""
import argparse
import json
import math
import os
import signal
import sys

from . import collision, control
from .core import Simulator
from .machine import DEFAULT_PARK, OPEN_AIR_VACUUM
from .pty_link import LinkGuard, check_link_path
from .runner import Runner


def _finite(text):
    v = float(text)
    if not math.isfinite(v):
        raise argparse.ArgumentTypeError("%r is not a finite number" % text)
    return v


def _park(text):
    parts = [_finite(v) for v in text.split(",")]
    if len(parts) != 3:
        raise argparse.ArgumentTypeError("--park needs X,Y,Z")
    return tuple(parts)


def run(argv):
    ap = argparse.ArgumentParser(prog="python3 -m lumen_sim", description="Simulated LumenPnP Marlin controller")
    ap.add_argument("--link", required=True, help="symlink to publish the pty slave at (name ttyLUMEN*)")
    ap.add_argument("--personality", default="stock", choices=["stock", "il-safety"])
    ap.add_argument("--control", help="Unix socket for the JSON control channel")
    ap.add_argument("--io-log", help="append the serial traffic here, serial.log style")
    ap.add_argument("--time-scale", type=_finite, default=1.0, help="simulator seconds per wall second")
    ap.add_argument("--park", type=_park, default=DEFAULT_PARK,
                    help="physical X,Y,Z of the head at power-on (mm from the switches), default 5,5,31.5")
    ap.add_argument("--kill-messages", action="store_true",
                    help="print 'echo:<reason>' and 'Error:Printer halted. kill() called!' on kill()")
    ap.add_argument("--vacuum", type=int, default=OPEN_AIR_VACUUM, help="raw N1 open-air vacuum reading (M261)")
    ap.add_argument("--surface-z", type=_finite,
                    help="physical Z (mm) at which N1 touches a surface and seals (default: nothing under N1)")
    ap.add_argument("--seed", type=int, default=0, help="seed for random fault delays")
    ap.add_argument("--sim-cap", action="store_true",
                    help="M115 also sends 'Cap:SIMULATOR:lumen-sim', which OpenPnP's SimGuard takes as a simulator "
                         "(SIM-feature scenarios only; never for the bug reproductions: the board never sends it)")
    ap.add_argument("--collision-model", metavar="PATH",
                    help="the collision model, Backend's collision-model.json (schema 1): collisions in sim_errors, "
                         "the see-saw's rest and end stops, N1's seal (README, 'The collision model')")
    args = ap.parse_args(argv)
    if args.time_scale <= 0:  # NaN and inf are refused by _finite: the loop would never sleep
        ap.error("--time-scale must be > 0")
    try:
        check_link_path(args.link)
    except ValueError as e:
        ap.error("--link: %s" % e)
    sim = Simulator(args.personality, park=args.park, vacuum=args.vacuum, kill_messages=args.kill_messages,
                    seed=args.seed, sim_cap=args.sim_cap)
    if args.surface_z is not None:
        sim.machine.surface_z = args.surface_z
    if args.collision_model:
        try:
            with open(args.collision_model, encoding="utf-8") as f:
                sim.fw.set_collision(collision.parse(f.read()))  # at boot: Z unpowered, at its rest
        except (OSError, ValueError) as e:
            ap.error("--collision-model: %s" % e)
    guard = LinkGuard()  # before anything is opened: it must hold none of it
    try:
        runner = Runner(sim, args.link, control=args.control, io_log=args.io_log, time_scale=args.time_scale,
                        log=lambda msg: print(msg, file=sys.stderr, flush=True), guard=guard)
    except (OSError, ValueError) as e:  # e.g. another lumen-sim on the same link or socket
        guard.close()
        print("lumen-sim: %s" % e, file=sys.stderr)
        return 2

    def stop(signum, frame):
        runner.running = False
    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):  # SIGHUP: its terminal or ssh session closed
        signal.signal(sig, stop)
    try:
        runner.run()
    finally:
        guard.close()
    return 0


def ctl(argv):
    ap = argparse.ArgumentParser(prog="python3 -m lumen_sim ctl")
    ap.add_argument("--control", required=True)
    ap.add_argument("what", choices=["state", "fault", "set", "power-cycle", "clear", "stop"])
    ap.add_argument("json", nargs="?", help="the fault or set request, as a JSON object")
    ap.add_argument("--link", help="power-cycle: publish the new pty at this path instead (relative: to here)")
    ap.add_argument("--down-s", type=float, help="power-cycle: the port is absent this long before it is back")
    args = ap.parse_args(argv)
    if args.what in ("fault", "set"):
        if not args.json:
            ap.error("%s needs a JSON object" % args.what)
        req = json.loads(args.json)
        req["cmd"] = args.what
    elif args.what == "power-cycle":
        # lumen-sim takes absolute paths only: its working directory is not ours
        req = {"cmd": "power_cycle", "link": os.path.abspath(args.link) if args.link else None, "down_s": args.down_s}
    else:
        req = {"cmd": args.what}
    reply = control.request(args.control, req)
    print(json.dumps(reply, indent=1))
    return 0 if reply.get("ok") and not reply.get("link_error") else 1


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv and argv[0] == "ctl":
        return ctl(argv[1:])
    return run(argv)


if __name__ == "__main__":
    sys.exit(main())
