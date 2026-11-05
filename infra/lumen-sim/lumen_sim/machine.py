"""The physical side that outlives a controller reset: head position, vacuum sensor, RS485 feeders, the PA5 switch."""
import random

OPEN_AIR_VACUUM = -1_500_000   # raw 24-bit reading of the N1 sensor with the nozzle in open air (-1.75e6..-1.0e6)
SEAL_DROP = 329_000            # counts a seal takes off the reading: the median of SimScene's sealDrops deciles
DEFAULT_PARK = (5.0, 5.0, 31.5)
PUMP, VALVE = 0, 1             # N1's pump (M106 = P0) and valve (M106 P1 S180), as machine.xml's VAC1 actuator


# The limit switch on PA5 (the user, 11-04): which way Z goes to press it with each tool. N1 goes down as Z falls, the
# needle on the see-saw's other side as Z rises (needle contact raw Z = K - N1 contact).
PA5_TOOLS = {"n1": -1, "needle": 1}
PA5_LINE = "PIN: PA5  (A9)   M42 P53          <unused/unknown>                       Input  = %d"  # M43 P5, the real board's


class Pa5Switch:
    """The PA5 switch: in a press window (physical X/Y, edges included) a tool presses it at its own trip point,
    trip_z + N(0, repeat_sd_mm) drawn anew for each press (seeded), until it goes back past that point or leaves the
    window. A press deeper than overtravel_mm is reported once. Nothing stops the head (no hard stop is modelled),
    and the switch neither chatters nor has a release differential."""

    def __init__(self, presses, overtravel_mm=0.5, repeat_sd_mm=0.0, pressed_level=1, seed=0):
        self.presses, self.overtravel, self.sd = [dict(p) for p in presses], overtravel_mm, repeat_sd_mm
        self.pressed_level, self.seed = pressed_level, seed
        self.rng = random.Random(seed)
        self.offset = self.rng.gauss(0.0, self.sd)  # the next press's trip point, from its window's trip_z
        self.press = None  # {"tool", "trip", "depth", "reported"} while pressed
        self.released = None  # (tool, z): no new press until the head moves toward the switch past z (the release
        # point, then wherever the head went away to)
        self.level = 1 - pressed_level  # the last observation's
        self.overtravels = 0

    def observe(self, x, y, z):
        """The head at physical x, y, z: the pin's level, and an overtravel message the first time a press goes
        deeper than the overtravel (else None)."""
        level, message = self._observe(x, y, z)
        self.level = level
        return level, message

    def _observe(self, x, y, z):
        region = next((r for r in self.presses if r["x0"] <= x <= r["x1"] and r["y0"] <= y <= r["y1"]), None)
        if region is None or (self.released and self.released[0] != region["tool"]):
            self.released = None  # off the window: the next press starts afresh
        if self.press is not None and (region is None or region["tool"] != self.press["tool"]):
            self._release(None)
        p = self.press
        if region is None:
            return 1 - self.pressed_level, None
        way = PA5_TOOLS[region["tool"]]
        if p is None and self.released and (z - self.released[1]) * way <= 1e-9:
            self.released = (region["tool"], z)  # not moved toward the switch since: no chatter; follows it away
            return 1 - self.pressed_level, None
        trip = p["trip"] if p is not None else region["trip_z"] + self.offset
        depth = (z - trip) * way
        if depth < -1e-9:
            if p is not None:
                self._release((region["tool"], z))
            return 1 - self.pressed_level, None
        if p is None:
            p = self.press = {"tool": region["tool"], "trip": trip, "depth": 0.0, "reported": False}
        p["depth"] = max(p["depth"], depth)
        if p["depth"] <= self.overtravel + 1e-9 or p["reported"]:
            return self.pressed_level, None
        p["reported"] = True
        self.overtravels += 1
        return self.pressed_level, "PA5 switch overtravel: %s %.2f mm past its trip point (overtravel %.2f mm)" % (
            p["tool"], p["depth"], self.overtravel)

    def _release(self, at):
        """The tool is off the switch (at: (tool, z) where it backed off, None: it left the window): the next press
        draws its own trip point."""
        self.press, self.released, self.offset = None, at, self.rng.gauss(0.0, self.sd)

    def describe(self):
        """As of the last observation (a move's end or a read): describing it reads nothing."""
        p, r = self.press, lambda v: round(v, 6) + 0.0  # noqa: E731  (+ 0.0: no -0.0)
        return {"presses": [dict(q) for q in self.presses], "overtravel_mm": self.overtravel,
                "repeat_sd_mm": self.sd, "pressed_level": self.pressed_level, "seed": self.seed, "level": self.level,
                "pressed": p is not None, "tool": p and p["tool"], "trip_z": p and r(p["trip"]),
                "depth_mm": p and r(p["depth"]), "overtravels": self.overtravels}


class Machine:
    def __init__(self, park=DEFAULT_PARK, vacuum=OPEN_AIR_VACUUM, rs485=None):
        # Physical position in mm/deg, in the machine's true frame: X/Y/Z 0 is where a correct home puts the head.
        # A and B have no switch; their physical value counts from power-on.
        self.physical = [float(park[0]), float(park[1]), float(park[2]), 0.0, 0.0]
        # Where the X/Y/Z min switches trigger, in that frame (a home_offset or z_shift fault moves them)
        self.switches = [0.0, 0.0, 0.0]
        self.z_drop = 0.0             # mm the head's Z moves each time the Z driver lets go (z_drop fault)
        self.vacuum = vacuum          # open-air reading: int, or callable(firmware) -> int (then used as it is)
        # N1 seals at this physical Z (mm in the head's true frame; N1 goes down as Z falls), or None: nothing under it
        self.surface_z = None
        # regions with a height of their own, [{x0, y0, x1, y1, z}] in physical mm (edges included; the first listed
        # that holds the head wins); surface_z is the height everywhere else
        self.surfaces = []
        self.seal_drop = SEAL_DROP
        self.rs485 = dict(rs485 or {})  # request hex -> reply hex (upper case); missing -> TIMEOUT
        self.mcp3426 = None           # int value for M3426, or None: no chip answers
        # Extra wait before each move of a G28 (10 in a full home). The board's G28s come in two speeds: none at
        # serial.log lines 168535 and 169216 (and the boot G28), ~1.4 s more per home at 166293 and 166954 (0.142 s per
        # move); the cause is unknown (README, "Homing time"). A knob, not a fault: it outlives a power cycle and clear.
        self.homing_move_delay_s = 0.0
        self.pa5 = None               # the PA5 switch (Pa5Switch), or None: nothing wired, M43 P5 reads 0
        self.collision = None         # the collision model (collision.Model), or None: no objects, no see-saw model

    def vacuum_counts(self, fw):
        if callable(self.vacuum):
            return int(self.vacuum(fw))
        return int(self.vacuum) - (self.seal_drop if self.n1_sealed(fw) else 0)

    def surface_at(self, x, y):
        """The surface height under the head at physical X/Y, or None: nothing there."""
        for r in self.surfaces:
            if r["x0"] <= x <= r["x1"] and r["y0"] <= y <= r["y1"]:
                return r["z"]
        return self.surface_z

    def n1_sealed(self, fw):
        """N1's tip on the surface under it with its pump and valve on. The reading changes at once (no time
        constant, no partial seal): the touch's Z is exact to the step."""
        if callable(self.vacuum) or not (fw.fans[PUMP] and fw.fans[VALVE]):
            return False
        x, y, z = fw.stepper.realtime_physical(fw.now)[:3]
        if self.collision is not None:  # the model's flat tops only, never the bare bed (Process 11-04 17:3x)
            return self.collision.n1_sealed(x, y, z)
        surface = self.surface_at(x, y)
        return surface is not None and z <= surface + 1e-9

    def i2c_read(self, fw, address, count):
        """Bytes a read of `count` from `address` returns; [] when nothing answers."""
        if address != 109:
            return []
        raw = self.vacuum_counts(fw) & 0xFFFFFF
        data = list(raw.to_bytes(3, "big"))
        return (data + [0] * count)[:count]

    def rs485_reply(self, request_hex):
        return self.rs485.get(request_hex.upper())

    def release_z(self):
        """The Z driver lets go: the see-saw falls to its rest (the collision model's seesaw.restRaw), else the head
        moves by z_drop; the end stops hold it, and it stays there (the controller does not know)."""
        rest = None if self.collision is None else self.collision.rest
        self.physical[2] = self.limit_z(self.physical[2] + self.z_drop if rest is None else rest)

    def limit_z(self, z):
        """Where the arm's end stops (the collision model's seesaw.endStopsRaw) hold a raw Z; without a model, z."""
        return z if self.collision is None else min(max(z, self.collision.stops[0]), self.collision.stops[1])

    def clear_faults(self):
        """Switches back where they belong, no drop. Nothing moves: the next home finds the true switches."""
        self.switches[:] = [0.0, 0.0, 0.0]
        self.z_drop = 0.0
