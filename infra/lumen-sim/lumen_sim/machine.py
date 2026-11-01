"""The physical side that outlives a controller reset: head position, vacuum sensor, RS485 feeders."""

OPEN_AIR_VACUUM = -1_500_000   # raw 24-bit reading of the N1 sensor with the nozzle in open air (-1.75e6..-1.0e6)
DEFAULT_PARK = (5.0, 5.0, 31.5)


class Machine:
    def __init__(self, park=DEFAULT_PARK, vacuum=OPEN_AIR_VACUUM, rs485=None):
        # Physical position in mm/deg, in the machine's true frame: X/Y/Z 0 is where a correct home puts the head.
        # A and B have no switch; their physical value counts from power-on.
        self.physical = [float(park[0]), float(park[1]), float(park[2]), 0.0, 0.0]
        # Where the X/Y/Z min switches trigger, in that frame (a home_offset or z_shift fault moves them)
        self.switches = [0.0, 0.0, 0.0]
        self.z_drop = 0.0             # mm the head's Z moves each time the Z driver lets go (z_drop fault)
        self.vacuum = vacuum          # int, or callable(firmware) -> int
        self.rs485 = dict(rs485 or {})  # request hex -> reply hex (upper case); missing -> TIMEOUT
        self.mcp3426 = None           # int value for M3426, or None: no chip answers

    def vacuum_counts(self, fw):
        v = self.vacuum(fw) if callable(self.vacuum) else self.vacuum
        return int(v)

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
        """The Z driver lets go: the head moves by z_drop and stays there (the controller does not know)."""
        self.physical[2] += self.z_drop

    def clear_faults(self):
        """Switches back where they belong, no drop. Nothing moves: the next home finds the true switches."""
        self.switches[:] = [0.0, 0.0, 0.0]
        self.z_drop = 0.0
