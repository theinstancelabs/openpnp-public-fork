"""The deterministic simulator: firmware + machine + faults on an injected clock (no I/O, no threads).

Drive it with receive(bytes, t), run_until(t), next_event_time() and take_output(t). Control requests (the JSON
protocol of the control socket) go through control(request, t); the ones the transport must carry out (power cycle,
disconnect, frozen, stop) are queued for take_transport_actions().
"""
import heapq
import math
from collections import deque

from . import personality as personalities
from .faults import OK_FAULTS, Faults, seconds
from .machine import DEFAULT_PARK, OPEN_AIR_VACUUM, Machine
from .marlin import AXES, Firmware, f32

INJECT_KINDS = {"error": "Error:simulated fault", "start": "start", "unknown": 'echo:Unknown command: "M999999"'}


class Simulator:
    def __init__(self, personality="stock", park=DEFAULT_PARK, vacuum=OPEN_AIR_VACUUM, rs485=None,
                 kill_messages=False, seed=0, t0=0.0):
        self.personality = personalities.get(personality)
        self.machine = Machine(park, vacuum, rs485)
        self.kill_messages = kill_messages
        self.faults = Faults(seed)
        self.out = deque()          # (release time, bytes), release times never decrease
        self._last_release = -math.inf
        self._timed = []            # heap of (time, n, bytes) injections
        self._n = 0
        self._actions = []
        self._dfu_reported = False
        self.seq = 0
        self.boots = 0
        self.sim_errors = deque(maxlen=100)  # lines the model could not run (kept across power cycles)
        self.on_error = None                 # optional callback(record), e.g. the runner's log
        self.fw = self._boot(t0)

    def _boot(self, t):
        self.boots += 1
        return Firmware(self.personality, self.machine, t, emit=self._emit, on_line=self._on_line,
                        kill_messages=self.kill_messages, on_error=self._sim_error)

    def _sim_error(self, record):
        self.sim_errors.append(record)
        if self.on_error:
            self.on_error(record)

    # ------------------------------------------------------------------ clock and I/O
    def receive(self, data, t):
        self.run_until(t)
        self.fw.feed(bytes(data), t)
        self._check_dfu()

    def run_until(self, t):
        while self._timed and self._timed[0][0] <= t:
            when, _, data = heapq.heappop(self._timed)
            self.fw.advance(when)
            self._queue(when, data)
        self.fw.advance(t)
        self._check_dfu()

    def next_event_time(self):
        nt = self.fw.next_wake()
        if self._timed:
            nt = min(nt, self._timed[0][0])
        if self.out:
            nt = min(nt, self.out[0][0])
        return nt

    def take_output(self, t):
        got = []
        while self.out and self.out[0][0] <= t:
            got.append(self.out.popleft())
        return got

    def take_transport_actions(self):
        got, self._actions = self._actions, []
        return got

    def _emit(self, t, data, kind, seq):
        for extra, payload in self.faults.filter(data, kind, seq):
            self._queue(t + extra, payload)

    def _queue(self, t, data):
        t = max(t, self._last_release)
        self._last_release = t
        self.out.append((t, data))

    def _on_line(self, text, t):
        self.seq += 1
        self.faults.on_line(self.seq, text)
        return self.seq

    def _check_dfu(self):
        if self.fw.halted and self.fw.halt_reason == "DFU" and not self._dfu_reported:
            self._dfu_reported = True
            self._actions.append({"action": "disconnect", "down_s": None})

    # ------------------------------------------------------------------ power
    def power_cycle(self, t):
        """Controller reset: boot state, outputs off, step counts lost; the head stays where it physically is."""
        self.run_until(t)
        self.fw.halt("power off", t)
        self.fw.stepper.update(t)  # settle the old stepper so the physical position is where it stopped
        self.out.clear()
        self._timed = []
        self._last_release = -math.inf
        self._dfu_reported = False
        self.fw = self._boot(t)

    # ------------------------------------------------------------------ control protocol
    def control(self, req, t):
        """One control request; a bad one gets ok:false (requests are checked before anything changes)."""
        try:
            return self._control(req, t)
        except Exception as e:
            return {"ok": False, "error": "%s: %s" % (type(e).__name__, e)}

    def _control(self, req, t):
        cmd = req.get("cmd")
        self.run_until(t)
        if cmd == "state":
            return {"ok": True, "state": self.state(t)}
        if cmd == "fault":
            return self._fault(req, t)
        if cmd == "clear":
            self.faults.clear()
            self._timed = []
            self._actions.append({"action": "clear"})
            return {"ok": True}
        if cmd == "power_cycle":
            link = req.get("link")
            if link is not None and not isinstance(link, str):
                raise ValueError("link must be a path, not %r" % (link,))
            down = 0.0 if req.get("down_s") is None else seconds(req["down_s"], "down_s")
            self.power_cycle(t)
            self._actions.append({"action": "power_cycle", "link": link, "down_s": down})
            return {"ok": True}
        if cmd == "set":
            return self._set(req)
        if cmd == "stop":
            self._actions.append({"action": "stop"})
            return {"ok": True}
        return {"ok": False, "error": "unknown cmd %r (state, fault, clear, power_cycle, set, stop)" % cmd}

    def _fault(self, req, t):
        kind = req.get("fault")
        spec = {k: v for k, v in req.items() if k != "cmd"}
        if kind in OK_FAULTS:
            self.faults.add(spec)
        elif kind in ("inject", "inject_bytes", "partial"):
            for key in ("text", "hex"):
                if key in req and not isinstance(req[key], str):
                    raise ValueError("%s must be a string, not %r" % (key, req[key]))
            if kind == "inject":
                text = req["text"] if "text" in req else INJECT_KINDS[req.get("kind", "error")]
                data = text.encode("latin-1") + b"\n"
            elif kind == "inject_bytes":
                data = bytes.fromhex(req["hex"]) if "hex" in req else req["text"].encode("latin-1")
            else:
                data = req["text"].encode("latin-1")
            after = seconds(req.get("after_s", 0.0), "after_s")
            if after > 0:
                self._n += 1
                heapq.heappush(self._timed, (t + after, self._n, data))
            else:
                self._queue(t, data)
        elif kind == "halt":
            self.fw.halt(str(req.get("reason", "halt fault")), t)
        elif kind == "disconnect":
            down = req.get("down_s", 0.0)
            self._actions.append({"action": "disconnect", "down_s": None if down is None else seconds(down, "down_s")})
        elif kind == "frozen":
            self._actions.append({"action": "frozen", "on": bool(req.get("on", True))})
        else:
            return {"ok": False, "error": "unknown fault %r" % kind}
        return {"ok": True}

    def _set(self, req):
        def integer(key):
            v = req[key]
            if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
                raise ValueError("%s must be a number, not %r" % (key, v))
            return int(v)
        # check everything first: a bad request changes nothing
        vacuum = integer("vacuum") if "vacuum" in req else None
        mcp = integer("mcp3426") if req.get("mcp3426") is not None else None
        rs485 = req.get("rs485", {})
        if not isinstance(rs485, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in rs485.items()):
            raise ValueError("rs485 must map request hex to reply hex (strings), not %r" % (rs485,))
        if "kill_messages" in req:
            self.kill_messages = self.fw.kill_messages = bool(req["kill_messages"])
        if vacuum is not None:
            self.machine.vacuum = vacuum
        self.machine.rs485.update({k.upper(): v for k, v in rs485.items()})
        if "mcp3426" in req:
            self.machine.mcp3426 = mcp
        return {"ok": True}

    # ------------------------------------------------------------------ state
    def state(self, t):
        self.run_until(t)
        fw = self.fw
        st = fw.stepper
        named = lambda values: {a: v for a, v in zip(AXES, values)}  # noqa: E731
        return {
            "time": t,
            "personality": self.personality.name,
            "boots": self.boots,
            "halted": fw.halted,
            "halt_reason": fw.halt_reason,
            "logical": named(fw.logical()),
            "native": named(fw.cur),
            "counts": named(fw.position),
            "stepper_counts": named(st.realtime_counts(t)),
            "physical": st.realtime_physical(t),
            "homed": {"X": fw.homed[0], "Y": fw.homed[1], "Z": fw.homed[2]},
            "homing_phase": fw.homing_phase,
            "moving": st.moving(t),
            "planner_blocks": len(st.blocks),
            "ring": [text for text, _ in fw.ring],
            "fans": list(fw.fans),
            "fan_history": list(fw.fan_history),
            "led": fw.led,
            "enabled": dict(fw.enabled),
            "soft_endstops": fw.soft_endstops,
            "settings": {
                "steps_per_unit": named(fw.steps), "max_feedrate": named(fw.max_feed),
                "max_acceleration": named(fw.max_accel),
                "acceleration": {"P": fw.accel_print, "R": fw.accel_retract, "T": fw.accel_travel},
                "position_shift": named([f32(v) for v in fw.ws]),
            },
            "watchdog": {"timeout_ms": fw.wd_timeout, "tripped": fw.wd_tripped} if self.personality.host_watchdog
            else None,
            "kill_messages": self.kill_messages,
            "faults": self.faults.describe(),
            "lines_read": self.seq,
            "sim_errors": list(self.sim_errors),
        }
