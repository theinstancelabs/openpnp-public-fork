"""Run a simulated LumenPnP Marlin controller on a pseudo-terminal, or talk to a running one.

usage:
  python3 -m lumen_sim --link PATH [--personality stock|il-safety] [--control SOCK] [--io-log FILE]
                       [--time-scale 1.0] [--park X,Y,Z] [--kill-messages] [--vacuum N] [--seed N]
  python3 -m lumen_sim ctl --control SOCK state
  python3 -m lumen_sim ctl --control SOCK fault '{"fault": "no_ok", "match": "^G4"}'
  python3 -m lumen_sim ctl --control SOCK set '{"vacuum": -1600000}'
  python3 -m lumen_sim ctl --control SOCK power-cycle [--link NEW_PATH]
  python3 -m lumen_sim ctl --control SOCK clear | stop

Point OpenPnP's serial port-name at the --link path (a symlink to /dev/pts/N). Stop with SIGINT/SIGTERM or `ctl stop`.
"""
import argparse
import json
import signal
import sys

from . import control
from .core import Simulator
from .machine import DEFAULT_PARK, OPEN_AIR_VACUUM
from .runner import Runner


def _park(text):
    parts = [float(v) for v in text.split(",")]
    if len(parts) != 3:
        raise argparse.ArgumentTypeError("--park needs X,Y,Z")
    return tuple(parts)


def run(argv):
    ap = argparse.ArgumentParser(prog="python3 -m lumen_sim", description="Simulated LumenPnP Marlin controller")
    ap.add_argument("--link", required=True, help="symlink to publish the pty slave at")
    ap.add_argument("--personality", default="stock", choices=["stock", "il-safety"])
    ap.add_argument("--control", help="Unix socket for the JSON control channel")
    ap.add_argument("--io-log", help="append the serial traffic here, serial.log style")
    ap.add_argument("--time-scale", type=float, default=1.0, help="simulator seconds per wall second")
    ap.add_argument("--park", type=_park, default=DEFAULT_PARK,
                    help="physical X,Y,Z of the head at power-on (mm from the switches), default 5,5,31.5")
    ap.add_argument("--kill-messages", action="store_true",
                    help="print 'echo:<reason>' and 'Error:Printer halted. kill() called!' on kill()")
    ap.add_argument("--vacuum", type=int, default=OPEN_AIR_VACUUM, help="raw N1 vacuum reading (M261)")
    ap.add_argument("--seed", type=int, default=0, help="seed for random fault delays")
    args = ap.parse_args(argv)
    if args.time_scale <= 0:
        ap.error("--time-scale must be > 0")
    sim = Simulator(args.personality, park=args.park, vacuum=args.vacuum, kill_messages=args.kill_messages,
                    seed=args.seed)
    runner = Runner(sim, args.link, control=args.control, io_log=args.io_log, time_scale=args.time_scale,
                    log=lambda msg: print(msg, file=sys.stderr, flush=True))

    def stop(signum, frame):
        runner.running = False
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    runner.run()
    return 0


def ctl(argv):
    ap = argparse.ArgumentParser(prog="python3 -m lumen_sim ctl")
    ap.add_argument("--control", required=True)
    ap.add_argument("what", choices=["state", "fault", "set", "power-cycle", "clear", "stop"])
    ap.add_argument("json", nargs="?", help="the fault or set request, as a JSON object")
    ap.add_argument("--link", help="power-cycle: publish the new pty at this path instead")
    args = ap.parse_args(argv)
    if args.what in ("fault", "set"):
        if not args.json:
            ap.error("%s needs a JSON object" % args.what)
        req = json.loads(args.json)
        req["cmd"] = args.what
    elif args.what == "power-cycle":
        req = {"cmd": "power_cycle", "link": args.link}
    else:
        req = {"cmd": args.what}
    reply = control.request(args.control, req)
    print(json.dumps(reply, indent=1))
    return 0 if reply.get("ok") else 1


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if argv and argv[0] == "ctl":
        return ctl(argv[1:])
    return run(argv)


if __name__ == "__main__":
    sys.exit(main())
