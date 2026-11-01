#!/usr/bin/env python3
"""A fake OpenPnP web API for the il_deploy tests (stdlib only; never touches a serial port).

Usage (as the deployed service would run OpenPnP; the working directory is the release dir):
  fake_openpnp.py --port N --config-dir DIR

It implements the calls the deploy health check makes: GET /api/status, GET /api/machine,
POST /api/machine/enable, POST /api/machine/home {"wait": true}, POST /api/stop.

Behaviour comes from two places:
- the release's "jar" (<cwd>/openpnp-il.jar) is JSON: {"sha": full sha, "fail": [...], "schema": n}.
  server.version = <cwd>/VERSION.txt + "_2026-10-31_12-00-00." + sha7, as OpenPnP's Main.getVersion().
  "schema" emulates a config migration: <config>/instancelabs/dispense-config.json gets that schema
  at start, and the db WAL gets a line, so a rollback can be checked to restore the snapshot.
- <config>/fake-machine.json (machine-level, survives a version change): {"fail": [...],
  "usb_sn": "387932683335", "simulated": false, "home_s": 0.05, "fail_for": {sha7: [...]} (failures for
  one build only), "run_phase": "dots" (the engine run's phase with fail "run"), and activity N s before
  the request: "last_run_ended_s_ago" (lastRun.endedAtMs), "run_finished_s_ago" (run.finishedAtMs),
  "pad_cal_ended_s_ago" (padCalibration.endedAtMs), "pressure_event_s_ago" (pressure.lastEventAtMs)}.
Failures (either list): version (wrong sha7 in server.version), crash (exit 1 at start), enable,
reset (axes X/Y/Z 0 and B 720 after enable), no-sn (no serial log line), home, stop (machine stays
enabled), busy, run, pressure, engine (the dispense engine did not start), unavailable (engine without
its nozzle), machine-500 (GET /api/status and /api/machine answer 500).
As in the real API (PressureHold.java:77-78), pressure.lastEvent is "pressure off" with lastEventAtMs at the
start until a real pressure event.

Every request is appended to --requests (default <config>/fake-requests.jsonl). A STOP while homing is recorded as
{"violation": "stop-during-home"}; SIGTERM while the machine is enabled as
{"violation": "sigterm-while-enabled"}.
"""
import argparse
import json
import os
import signal
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BUILD_TIME = "2026-10-31_12-00-00"


class FakeOpenPnP:
    def __init__(self, port, cwd, config_dir, machine=None, requests=None):
        self.port = port
        self.requests = requests or os.path.join(config_dir, "fake-requests.jsonl")
        self.cwd = cwd
        self.config = config_dir
        self.jar = self._read_json(os.path.join(cwd, "openpnp-il.jar")) or {}
        if machine is None:
            machine = self._read_json(os.path.join(config_dir, "fake-machine.json")) or {}
        self.machine = machine
        sha7 = str(self.jar.get("sha", ""))[:7]
        self.fail = (set(self.jar.get("fail", [])) | set(machine.get("fail", []))
                     | set((machine.get("fail_for") or {}).get(sha7, [])))
        self.started_ms = int(time.time() * 1000)
        self.lock = threading.Lock()
        self.enabled = False
        self.homed = False
        self.homing = False
        self.axes = {"X": 0.0, "Y": 0.0, "Z": 0.0, "B": 0.0}
        self.server = None
        os.makedirs(os.path.join(config_dir, "log"), exist_ok=True)

    @staticmethod
    def _read_json(path):
        try:
            with open(path) as f:
                return json.load(f)
        except (OSError, ValueError):
            return None

    # --- helpers -------------------------------------------------------------------------------
    def record(self, entry):
        entry = dict(entry, t=time.time())
        with open(self.requests, "a") as f:
            f.write(json.dumps(entry) + "\n")

    def log(self, line):
        stamp = time.strftime("%Y-%m-%d %H:%M:%S") + ".000"
        with open(os.path.join(self.config, "log", "OpenPnP.log"), "a") as f:
            f.write(f"{stamp} {line}\n")

    def version(self):
        try:
            with open(os.path.join(self.cwd, "VERSION.txt")) as f:
                v = f.read().strip()
        except OSError:
            v = "n/a"
        sha7 = "0000000" if "version" in self.fail else str(self.jar.get("sha", "unknown"))[:7]
        return f"{v}_{BUILD_TIME}.{sha7}"

    def migrate(self):
        schema = self.jar.get("schema")
        if schema is None:
            return
        path = os.path.join(self.config, "instancelabs", "dispense-config.json")
        cfg = self._read_json(path) or {}
        cfg["schema"] = schema
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            json.dump(cfg, f)
        with open(path.replace("dispense-config.json", "dispense.db-wal"), "a") as f:
            f.write(f"written by {str(self.jar.get('sha', ''))[:7]}\n")

    def machine_status(self):
        with self.lock:
            busy = self.homing or "busy" in self.fail
            axes = [{"name": n, "type": t, "letter": letter, "driverCoordinate": self.axes[letter],
                     "coordinate": self.axes[letter]}
                    for n, t, letter in (("x", "X", "X"), ("y", "Y", "Y"), ("z1", "Z", "Z"), ("b", "Rotation", "B"))]
            return {"present": True, "enabled": self.enabled, "connected": self.enabled, "homed": self.homed,
                    "busy": busy, "drivers": [{"name": "GcodeDriver", "class": "GcodeDriver", "connection": "fake"}],
                    "positions": [], "axes": axes}

    def ago_ms(self, key):
        s = self.machine.get(key)
        return None if s is None else int((time.time() - float(s)) * 1000)

    def run_phase(self):
        return str(self.machine.get("run_phase", "dots"))

    def status(self):
        sim = bool(self.machine.get("simulated", False))
        pressure_on = "pressure" in self.fail
        event_ms = self.ago_ms("pressure_event_s_ago")
        pressure = {"state": "on" if pressure_on else "off", "on": pressure_on, "lastEvent": "pressure off",
                    "lastEventAtMs": self.started_ms}
        if event_ms is not None:
            pressure.update(lastEvent="pressure off (valve, then pump): API request", lastEventAtMs=event_ms)
        run = None
        if "run" in self.fail:
            run = {"runId": "r2", "kind": "board", "running": True, "status": "RUNNING", "phase": self.run_phase(),
                   "startedAtMs": self.started_ms, "finishedAtMs": None}
        elif self.ago_ms("run_finished_s_ago") is not None:
            run = {"runId": "r1", "kind": "camera", "running": False, "status": "DONE", "phase": "finished: DONE",
                   "startedAtMs": self.started_ms, "finishedAtMs": self.ago_ms("run_finished_s_ago")}
        pad = None
        if self.ago_ms("pad_cal_ended_s_ago") is not None:
            pad = {"state": "done", "endedAtMs": self.ago_ms("pad_cal_ended_s_ago")}
        out = {
            "server": {"version": self.version(), "timeMs": int(time.time() * 1000), "port": self.port,
                       "lanAccess": False, "simulated": sim, "simulatorConfirmed": sim},
            "machine": self.machine_status(),
            "dispense": {"running": True, "available": "unavailable" not in self.fail, "busy": "run" in self.fail},
            "pressure": pressure,
            "run": run,
            "padCalibration": pad,
            "boardImport": {"reading": False},
        }
        if "engine" in self.fail:
            # DispenseEndpoints.status: no engine, so no lastRun either
            out["dispense"] = {"running": False, "available": False,
                               "unavailableReason": "the dispense engine is not running"}
            return out
        last = None
        if self.ago_ms("last_run_ended_s_ago") is not None:
            last = {"runId": "r1", "kind": "board", "status": "DONE", "startedAtMs": self.started_ms,
                    "endedAtMs": self.ago_ms("last_run_ended_s_ago")}
        out["lastRun"] = last
        return out

    # --- endpoints -----------------------------------------------------------------------------
    def enable(self):
        if "enable" in self.fail:
            return 409, {"error": "refused", "reason": "could not start the machine: fake port not found"}
        with self.lock:
            already = self.enabled
            self.enabled = True
            if "reset" in self.fail:
                self.axes = {"X": 0.0, "Y": 0.0, "Z": 0.0, "B": 720.0}
            else:
                self.axes = {"X": 217.081, "Y": 200.223, "Z": -3.5, "B": 1234.5}
        if not already and "no-sn" not in self.fail:
            sn = self.machine.get("usb_sn", "387932683335")
            self.log('SerialPortCommunications INFO: Serial port: connecting to ttyACM0 by USB serial number: '
                     f'ttyACM0 (/dev/ttyACM0) "MARLIN_OPULO_LUMEN_REV5 CDC in FS Mode" S/N {sn} USB 0483:5740')
        return 200, {"enabled": True, "homed": self.homed}

    def home(self, body):
        with self.lock:
            if not self.enabled:
                return 409, {"error": "refused", "reason": "the machine is not started: press Start first"}
            self.homing = True
        result = {}

        def work():
            time.sleep(float(self.machine.get("home_s", 0.05)))
            with self.lock:
                self.homing = False
                if "home" in self.fail:
                    result["error"] = "homing failed: fake endstop not reached"
                else:
                    self.homed = True

        t = threading.Thread(target=work, daemon=True)
        t.start()
        if not body.get("wait"):
            return 202, {"started": True, "homing": True}
        t.join()
        if "error" in result:
            return 409, {"error": "refused", "reason": result["error"]}
        return 200, {"homed": True, "n1RaisedFromZ": None}

    def stop(self):
        with self.lock:
            if self.homing or ("run" in self.fail and self.run_phase().startswith("home")):
                self.record({"violation": "stop-during-home"})
            if "stop" not in self.fail:
                self.enabled = False
                self.homed = False
            return 200, {"stopped": True, "machineEnabled": self.enabled, "actions": ["OpenPnP Stop"]}

    def handle(self, method, path, body):
        self.record({"method": method, "path": path})
        if method == "GET" and path in ("/api/status", "/api/machine") and "machine-500" in self.fail:
            return 500, {"error": "internal", "reason": "NullPointerException in machineStatus"}
        if method == "GET" and path == "/api/status":
            return 200, self.status()
        if method == "GET" and path == "/api/machine":
            return 200, self.machine_status()
        if method == "POST" and path == "/api/machine/enable":
            return self.enable()
        if method == "POST" and path == "/api/machine/home":
            return self.home(body)
        if method == "POST" and path == "/api/stop":
            return self.stop()
        return 404, {"error": "not-found", "reason": "no such endpoint: " + path}

    # --- server --------------------------------------------------------------------------------
    def make_server(self):
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def _do(self, method):
                n = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(n) if n else b""
                try:
                    body = json.loads(raw) if raw else {}
                except ValueError:
                    body = {}
                code, out = fake.handle(method, self.path.split("?")[0], body)
                data = json.dumps(out).encode()
                try:
                    self.send_response(code)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # the client gave up waiting (a home that outlasts its request timeout)

            def do_GET(self):
                self._do("GET")

            def do_POST(self):
                self._do("POST")

            def log_message(self, *a):
                pass

        ThreadingHTTPServer.allow_reuse_address = True
        self.server = ThreadingHTTPServer(("127.0.0.1", self.port), Handler)
        self.server.daemon_threads = True
        return self.server

    def start_thread(self):
        self.make_server()
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return self

    def shutdown(self):
        if self.server:
            self.server.shutdown()
            self.server.server_close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--config-dir", required=True)
    ap.add_argument("--requests", help="request log (default <config>/fake-requests.jsonl)")
    a = ap.parse_args()
    fake = FakeOpenPnP(a.port, os.getcwd(), a.config_dir, requests=a.requests)
    fake.log("Main INFO: fake OpenPnP starting")
    if "crash" in fake.fail:
        sys.exit(1)
    fake.migrate()

    def on_term(signum, frame):
        if fake.enabled:
            fake.record({"violation": "sigterm-while-enabled"})
        fake.record({"event": "sigterm"})
        os._exit(0)

    signal.signal(signal.SIGTERM, on_term)
    fake.make_server().serve_forever()


if __name__ == "__main__":
    main()
