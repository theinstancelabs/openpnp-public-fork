"""Personality il-holdB (il/holdB 75fb46ebc): stock with DISABLE_IDLE_J off, so the idle timeout leaves B energised,
and the il-holdB marker in M115's FIRMWARE_NAME, which the engine reads at every connect (BR-92) (fake clock)."""
import unittest

from lumen_sim.bench import Bench
from tests import goldens

LINE = ("FIRMWARE_NAME:Marlin bugfix-2.1.x-il-holdB (Nov  4 2026 22:38:38) "
        "SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin PROTOCOL_VERSION:1.0 "
        "MACHINE_TYPE:SimRap 1.4 EXTRUDER_COUNT:0 AXIS_COUNT:5")


def energised(personality="il-holdB"):
    """Every axis energised (G28 for X/Y/Z, a move for A and B) and the planner empty."""
    b = Bench(personality=personality)
    b.send("G28")
    b.send("G1 A10 B800 F60000")
    b.send("M400")
    return b


def enabled(b):
    return b.sim.state(b.t)["enabled"]


class FirmwareLine(unittest.TestCase):
    def test_m115_carries_the_marker_and_the_stock_caps(self):
        stock = [t for _, t in goldens.replies(goldens.load("m115"), "M115")]
        self.assertEqual(Bench(personality="il-holdB").send("M115").lines, [LINE] + stock[1:])

    def test_the_other_builds_have_no_marker(self):
        for p in ("stock", "il-safety"):
            self.assertNotIn("il-holdB", " ".join(Bench(personality=p).send("M115").lines), p)


class IdleTimeout(unittest.TestCase):
    """manage_inactivity(): an hour after the last move X, Y, Z and A let go; B holds."""

    def test_b_holds_through_the_idle_timeout(self):
        b = energised()
        t_end = b.t
        b.run_until(t_end + 3599.9)
        self.assertEqual(enabled(b), dict.fromkeys("XYZAB", True))
        b.run_until(t_end + 3600.1)
        self.assertEqual(enabled(b), {"X": False, "Y": False, "Z": False, "A": False, "B": True})
        b.run(10 * 3600.0)
        self.assertTrue(enabled(b)["B"])

    def test_stock_and_il_safety_release_b_with_the_rest(self):
        for p in ("stock", "il-safety"):
            b = energised(p)
            b.run(3600.1)
            self.assertEqual(enabled(b), dict.fromkeys("XYZAB", False), p)

    def test_m18_b_m84_and_a_power_cycle_still_release_b(self):
        for how in ("M18 B", "M84", "power cycle"):
            b = energised()
            self.assertTrue(enabled(b)["B"], how)
            if how == "power cycle":
                b.sim.power_cycle(b.t)
            else:
                b.send(how)
            self.assertFalse(enabled(b)["B"], how)

    def test_m112_still_releases_b(self):
        """kill(): every axis lets go 0.85 s after M112, B with them."""
        b = energised()
        t0 = b.t
        b.write("M112\n")
        b.run_until(t0 + 0.8)
        self.assertTrue(enabled(b)["B"])
        b.run_until(t0 + 0.9)
        self.assertFalse(enabled(b)["B"])


if __name__ == "__main__":
    unittest.main()
