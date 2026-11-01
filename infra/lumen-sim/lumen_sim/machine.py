"""The physical side that outlives a controller reset: head position, vacuum sensor, RS485 feeders."""

OPEN_AIR_VACUUM = -1_500_000   # raw 24-bit reading of the N1 sensor with the nozzle in open air (-1.75e6..-1.0e6)
DEFAULT_PARK = (5.0, 5.0, 31.5)


class Machine:
    def __init__(self, park=DEFAULT_PARK, vacuum=OPEN_AIR_VACUUM, rs485=None):
        # physical position in mm/deg; the X/Y/Z min switches trigger at <= 0. A and B have no switch.
        self.physical = [float(park[0]), float(park[1]), float(park[2]), 0.0, 0.0]
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
