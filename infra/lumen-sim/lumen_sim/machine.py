"""The physical side that outlives a controller reset: head position, vacuum sensor, RS485 feeders."""

OPEN_AIR_VACUUM = -1_500_000   # raw 24-bit reading of the N1 sensor with the nozzle in open air (-1.75e6..-1.0e6)
SEAL_DROP = 329_000            # counts a seal takes off the reading: the median of SimScene's sealDrops deciles
DEFAULT_PARK = (5.0, 5.0, 31.5)
PUMP, VALVE = 0, 1             # N1's pump (M106 = P0) and valve (M106 P1 S180), as machine.xml's VAC1 actuator


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
        self.seal_drop = SEAL_DROP
        self.rs485 = dict(rs485 or {})  # request hex -> reply hex (upper case); missing -> TIMEOUT
        self.mcp3426 = None           # int value for M3426, or None: no chip answers

    def vacuum_counts(self, fw):
        if callable(self.vacuum):
            return int(self.vacuum(fw))
        return int(self.vacuum) - (self.seal_drop if self.n1_sealed(fw) else 0)

    def n1_sealed(self, fw):
        """N1's tip on the surface with its pump and valve on. The reading changes at once (no time constant, no
        partial seal): the touch's Z is exact to the step."""
        if self.surface_z is None or callable(self.vacuum) or not (fw.fans[PUMP] and fw.fans[VALVE]):
            return False
        return fw.stepper.realtime_physical(fw.now)[2] <= self.surface_z + 1e-9

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
