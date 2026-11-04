"""The deterministic simulator: firmware + machine + faults on an injected clock (no I/O, no threads).

Drive it with receive(bytes, t), run_until(t), next_event_time() and take_output(t). Control requests (the JSON
protocol of the control socket) go through control(request, t); the ones the transport must carry out (power cycle,
disconnect, frozen, stop) are queued for take_transport_actions().
"""
import heapq
import math
import os
from collections import deque

from . import personality as personalities
from .faults import OK_FAULTS, Faults, seconds
from .machine import DEFAULT_PARK, OPEN_AIR_VACUUM, Machine
from .marlin import AXES, Firmware, f32

INJECT_KINDS = {"error": "Error:simulated fault", "start": "start", "unknown": 'echo:Unknown command: "M999999"'}
# The keys each request takes besides "cmd" (and a fault's "fault"): an unknown one (a misspelt "surfaceZ" or "downs")
# is refused, not ignored. Ok faults check theirs in faults.KEYS; physical faults take their axes (PHYSICAL_FAULTS).
REQUEST_KEYS = {"state": (), "clear": (), "stop": (), "power_cycle": ("link", "down_s"),
                "set": ("kill_messages", "sim_cap", "vacuum", "rs485", "mcp3426", "surface_z", "surfaces", "seal_drop",
                        "homing_move_delay_s")}
FAULT_KEYS = {"inject": ("kind", "text", "after_s"), "inject_bytes": ("hex", "text", "after_s"),
              "partial": ("text", "after_s"), "halt": ("reason",), "disconnect": ("down_s",), "frozen": ("on",)}
# Faults of the physical machine (INF-08): fault -> the axis keys it takes (mm, signed; at least one)
PHYSICAL_FAULTS = {"home_offset": ("x", "y"), "lost_steps": ("x", "y"), "z_shift": ("z",), "z_drop": ("z",)}
REGION = ("x0", "y0", "x1", "y1", "z")  # a surface region of set's "surfaces" (physical mm)


def only(req, keys, what):
    """ValueError if req has a key outside keys."""
    extra = sorted(set(req) - set(keys))
    if extra:
        raise ValueError("%s takes %s, not %s" % (what, ", ".join(k for k in keys if k not in ("cmd", "fault"))
                                                  or "nothing else", ", ".join(map(str, extra))))


def flag(value, name):
    """A JSON true or false (bool("false") would be True), else ValueError."""
    if not isinstance(value, bool):
        raise ValueError("%s must be true or false, not %r" % (name, value))
    return value


def mm(value, name):
    """A finite number (JSON allows NaN and Infinity; a bool is not a number here), else ValueError."""
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError("%s must be a finite number of mm, not %r" % (name, value))
    return float(value)


class Simulator:
    def __init__(self, personality="stock", park=DEFAULT_PARK, vacuum=OPEN_AIR_VACUUM, rs485=None,
                 kill_messages=False, seed=0, t0=0.0, sim_cap=False):
        self.personality = personalities.get(personality)
        self.machine = Machine(park, vacuum, rs485)
        self.kill_messages = kill_messages
        self.sim_cap = sim_cap      # lumen-sim's option, so every board it boots has it
        self.faults = Faults(seed)
        self.out = deque()          # (release time, bytes), release times never decrease
        self._last_release = -math.inf
        self._timed = []            # heap of (time, n, bytes) injections
        self._n = 0
        self._actions = []
        self._halt_seen = False
        self.seq = 0
        self.boots = 0
        self.sim_errors = deque(maxlen=100)  # lines the model could not run (kept across power cycles)
        self.on_error = None                 # optional callback(record), e.g. the runner's log
        self.fw = self._boot(t0)

    def _boot(self, t):
        self.boots += 1
        return Firmware(self.personality, self.machine, t, emit=self._emit, on_line=self._on_line,
                        kill_messages=self.kill_messages, on_error=self._sim_error, sim_cap=self.sim_cap)

    def _sim_error(self, record):
        self.sim_errors.append(record)
        if self.on_error:
            self.on_error(record)

    # ------------------------------------------------------------------ clock and I/O
    def receive(self, data, t):
        self.run_until(t)
        self.fw.feed(bytes(data), t)
        self._check_halt()

    def run_until(self, t):
        while self._timed and self._timed[0][0] <= t:
            when, _, data = heapq.heappop(self._timed)
            self.fw.advance(when)
            self._check_halt()
            self._queue(when, data)
        self.fw.advance(t)
        self._check_halt()

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
        if self._halt_seen:  # a dead board sends nothing (reset before a power cycle boots the next one)
            return
        t = max(t, self._last_release)
        self._last_release = t
        self.out.append((t, data))

    def _on_line(self, text, t):
        self.seq += 1
        self.faults.on_line(self.seq, text)
        return self.seq

    def _check_halt(self):
        """Once the board stops dead (halt fault, kill, DFU, sim error) it sends nothing more: output it had not sent
        by then (an ok a delay_ok holds, and all queued behind it) and pending injections are dropped. DFU also takes
        the port away."""
        fw = self.fw
        if not fw.halted or self._halt_seen:
            return
        self._halt_seen = True
        self.out = deque((when, data) for when, data in self.out if when <= fw.halt_time)
        self._last_release = self.out[-1][0] if self.out else -math.inf
        self._timed = []
        if fw.halt_reason == "DFU":
            self._actions.append({"action": "disconnect", "down_s": None})

    # ------------------------------------------------------------------ power
    def power_cycle(self, t):
        """Controller reset: boot state, outputs off, step counts lost; the head stays where it physically is, except
        that an energised Z lets go and drops by the machine's z_drop."""
        self.run_until(t)
        self.fw.halt("power off", t)
        # The drivers stop at t, also inside a stock kill's 0.6 s (halt() does nothing on a halted board); then
        # settle the old stepper so the physical position is where it stopped.
        self.fw.stepper.freeze(t)
        self.fw.stepper.update(t)
        for axis in AXES:          # power off: every driver lets go (a released Z drops)
            self.fw.release(axis)
        self.out.clear()
        self._timed = []
        self._last_release = -math.inf
        self._halt_seen = False
        self.faults.clear()        # the ok faults are the controller's: the reboot ends them (the machine's stay)
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
        if cmd in REQUEST_KEYS:
            only(req, ("cmd",) + REQUEST_KEYS[cmd], cmd)
        self.run_until(t)
        if cmd == "state":
            return {"ok": True, "state": self.state(t)}
        if cmd == "fault":
            return self._fault(req, t)
        if cmd == "clear":
            self.faults.clear()
            self.machine.clear_faults()
            # the switches are back at 0: a homing move running toward one ends there, and the firmware's wait with it
            self.fw.stepper.recheck_endstops(t)
            self.fw.wake(t)
            self._timed = []
            # output a delay_ok holds back (and all queued behind it) goes now
            self.out = deque((min(when, t), data) for when, data in self.out)
            self._last_release = min(self._last_release, t)
            self._actions.append({"action": "clear"})
            return {"ok": True}
        if cmd == "power_cycle":
            link = req.get("link")
            if link is not None and not (isinstance(link, str) and os.path.isabs(link)):
                raise ValueError("link must be an absolute path (lumen-sim's working directory is not the client's), "
                                 "not %r" % (link,))
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
        if kind in FAULT_KEYS:
            only(req, ("cmd", "fault") + FAULT_KEYS[kind], kind)
        if kind in OK_FAULTS:
            self.faults.add(spec)
        elif kind in ("inject", "inject_bytes", "partial"):
            if self.fw.halted:
                raise ValueError("the board is halted (%s): it sends nothing until a power cycle" % self.fw.halt_reason)
            for key in ("text", "hex"):
                if key in req and not isinstance(req[key], str):
                    raise ValueError("%s must be a string, not %r" % (key, req[key]))
            if kind == "inject":
                if "text" not in req and req.get("kind", "error") not in INJECT_KINDS:
                    raise ValueError("kind must be one of %s, not %r" % (", ".join(INJECT_KINDS), req["kind"]))
                text = req["text"] if "text" in req else INJECT_KINDS[req.get("kind", "error")]
                data = text.encode("latin-1") + b"\n"
            elif "hex" not in req and "text" not in req:
                raise ValueError("%s needs %s" % (kind, "hex or text" if kind == "inject_bytes" else "text"))
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
            reason = req.get("reason", "halt fault")
            if not isinstance(reason, str):
                raise ValueError("reason must be a string, not %r" % (reason,))
            self.fw.halt(reason, t)
            self._check_halt()
        elif kind == "disconnect":
            down = req.get("down_s", 0.0)
            down = None if down is None else seconds(down, "down_s")
            if self.fw.halted and self.fw.halt_reason == "DFU":
                down = None  # a board in its bootloader shows no serial port until a power cycle
            self._actions.append({"action": "disconnect", "down_s": down})
        elif kind == "frozen":
            self._actions.append({"action": "frozen", "on": flag(req.get("on", True), "on")})
        elif kind in PHYSICAL_FAULTS:
            self._physical_fault(kind, {k: v for k, v in spec.items() if k != "fault"}, t)
        else:
            return {"ok": False, "error": "unknown fault %r" % kind}
        return {"ok": True}

    def _physical_fault(self, kind, values, t):
        """The machine moves under the controller; M114 and the step counts never change."""
        keys = PHYSICAL_FAULTS[kind]
        if not values or not set(values) <= set(keys):
            raise ValueError("%s takes %s (mm), not %r" % (kind, " and/or ".join(keys), sorted(values)))
        v = {k: mm(values[k], k) for k in values}
        m = self.machine
        if kind == "home_offset":    # the switches trigger here from now on: every later home lands off
            for i, k in enumerate("xy"):
                if k in v:
                    m.switches[i] = v[k]
        elif kind == "lost_steps":   # the head moves now, with no command
            for i, k in enumerate("xy"):
                m.physical[i] += v.get(k, 0.0)
        elif kind == "z_shift":      # the whole Z frame: the head now, and the switch for every later home
            m.physical[2] += v["z"]
            m.switches[2] += v["z"]
        else:                        # z_drop: how far the head moves each time the Z driver lets go
            m.z_drop = v["z"]
        # a homing move running toward a switch now ends where that switch triggers, and the firmware's wait with it
        self.fw.stepper.recheck_endstops(t)
        self.fw.wake(t)

    def _set(self, req):
        def integer(key):
            v = req[key]
            if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
                raise ValueError("%s must be a number, not %r" % (key, v))
            return int(v)
        # check everything first: a bad request changes nothing
        vacuum = integer("vacuum") if "vacuum" in req else None
        mcp = integer("mcp3426") if req.get("mcp3426") is not None else None
        surface = mm(req["surface_z"], "surface_z") if req.get("surface_z") is not None else None
        surfaces = self._regions(req["surfaces"]) if "surfaces" in req else None
        drop = req.get("seal_drop")
        if drop is not None and (isinstance(drop, bool) or not isinstance(drop, int) or drop < 0):
            raise ValueError("seal_drop must be an integer >= 0 (counts), not %r" % (drop,))
        homing_delay = seconds(req["homing_move_delay_s"], "homing_move_delay_s") \
            if "homing_move_delay_s" in req else None
        rs485 = req.get("rs485", {})
        if not isinstance(rs485, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in rs485.items()):
            raise ValueError("rs485 must map request hex to reply hex (strings), not %r" % (rs485,))
        kill = flag(req["kill_messages"], "kill_messages") if "kill_messages" in req else None
        sim_cap = flag(req["sim_cap"], "sim_cap") if "sim_cap" in req else None
        if kill is not None:
            self.kill_messages = self.fw.kill_messages = kill
        if sim_cap is not None:
            self.sim_cap = self.fw.sim_cap = sim_cap
        if vacuum is not None:
            self.machine.vacuum = vacuum
        self.machine.rs485.update({k.upper(): v for k, v in rs485.items()})
        if "mcp3426" in req:
            self.machine.mcp3426 = mcp
        if "surface_z" in req:
            self.machine.surface_z = surface
        if surfaces is not None:
            self.machine.surfaces = surfaces
        if drop is not None:
            self.machine.seal_drop = drop
        if homing_delay is not None:
            self.machine.homing_move_delay_s = homing_delay
        return {"ok": True}

    @staticmethod
    def _regions(raw):
        """set's "surfaces": a list of {x0, y0, x1, y1, z} (mm, x0 <= x1, y0 <= y1); null or [] removes them."""
        if raw is None:
            return []
        if not isinstance(raw, list):
            raise ValueError("surfaces must be a list of {x0, y0, x1, y1, z}, not %r" % (raw,))
        regions = []
        for r in raw:
            if not isinstance(r, dict) or set(r) != set(REGION):
                raise ValueError("a surface region takes x0, y0, x1, y1 and z (mm), not %r" % (r,))
            v = {k: mm(r[k], k) for k in REGION}
            if v["x0"] > v["x1"] or v["y0"] > v["y1"]:
                raise ValueError("a surface region needs x0 <= x1 and y0 <= y1, not %r" % (r,))
            regions.append(v)
        return regions

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
            "head": self.head(t),
            "switches": {a: v for a, v in zip("XYZ", self.machine.switches)},
            "z_drop": self.machine.z_drop,
            "surface_z": self.machine.surface_z,
            "surfaces": [dict(r) for r in self.machine.surfaces],
            "seal_drop": self.machine.seal_drop,
            "homing_move_delay_s": self.machine.homing_move_delay_s,
            "n1_sealed": self.machine.n1_sealed(fw),
            "stepper_timeout_s": fw.stepper_timeout_ms / 1000.0,
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
            "sim_cap": self.sim_cap,
            "faults": self.faults.describe(),
            "lines_read": self.seq,
            "sim_errors": list(self.sim_errors),
        }

    def head(self, t):
        """X/Y/Z: where the head is (physical, the machine's true frame), where the controller believes it is now
        (logical: its step counts in G92 coordinates; M114 prints the planned end), and physical - logical, the
        error the controller does not know about. Rounded to 1 nm."""
        fw, st = self.fw, self.fw.stepper
        counts = st.realtime_counts(t)
        phys = st.realtime_physical(t)[:3]
        logical = [counts[i] / fw.steps[i] + fw.ws[i] for i in range(3)]
        r = lambda v: round(v, 6) + 0.0  # noqa: E731  (+ 0.0: no -0.0)
        return {
            "physical": {a: r(p) for a, p in zip("XYZ", phys)},
            "logical": {a: r(lg) for a, lg in zip("XYZ", logical)},
            "offset": {a: r(p - lg) for a, p, lg in zip("XYZ", phys, logical)},
        }
