"""Fault injection and control requests on the deterministic core (fake clock)."""
import unittest

from lumen_sim.bench import Bench


def fault(b, **spec):
    reply = b.sim.control(dict(cmd="fault", **spec), b.t)
    assert reply["ok"], reply
    return reply


class OkFaults(unittest.TestCase):
    def test_no_ok_for_the_nth_line(self):
        b = Bench()
        fault(b, fault="no_ok", nth=2)
        self.assertEqual(b.send("M400").lines, ["ok"])
        r = b.send("M400", timeout=5.0)
        self.assertIsNone(r.ok_at)
        self.assertEqual(b.send("M400").lines, ["ok"])

    def test_no_ok_for_matching_lines(self):
        b = Bench()
        fault(b, fault="no_ok", match=r"^G4\b")
        self.assertIsNone(b.send("G4 P10", timeout=2.0).ok_at)
        self.assertIsNone(b.send("G4 P10", timeout=2.0).ok_at)
        self.assertEqual(b.send("M114").lines[-1], "ok")

    def test_extra_delay_before_ok(self):
        b = Bench()
        fault(b, fault="delay_ok", s=0.75, match="M400")
        self.assertAlmostEqual(b.send("M400").ok_at, 0.75, delta=1e-6)
        self.assertLess(b.send("M114").ok_at, 0.001)

    def test_random_delay_is_seeded(self):
        got = []
        for _ in range(2):
            b = Bench(seed=7)
            fault(b, fault="delay_ok", random=[0.1, 0.4])
            got.append([round(b.send("M400").ok_at, 9) for _ in range(3)])
        self.assertEqual(got[0], got[1])
        self.assertTrue(all(0.1 <= t <= 0.4 for t in got[0]), got)

    def test_duplicate_ok(self):
        b = Bench()
        fault(b, fault="dup_ok", nth=1)
        b.write("M400\n")
        b.run(0.1)
        self.assertEqual([l for _, l in b.rx], ["ok", "ok"])

    def test_clear(self):
        b = Bench()
        fault(b, fault="no_ok", match=".")
        self.assertTrue(b.sim.control({"cmd": "clear"}, b.t)["ok"])
        self.assertEqual(b.send("M400").lines, ["ok"])

    def test_count_limits_a_match_rule(self):
        b = Bench()
        fault(b, fault="no_ok", match="^M400", count=2)
        self.assertIsNone(b.send("M400", timeout=2.0).ok_at)
        self.assertIsNone(b.send("M400", timeout=2.0).ok_at)
        self.assertEqual(b.send("M400").lines, ["ok"])
        self.assertEqual(b.sim.state(b.t)["faults"], [])

    def test_count_limits_a_match_rule_on_lines_read_together(self):
        """Lines read before the first ok goes out: only `count` of them are hit."""
        b = Bench()
        fault(b, fault="no_ok", match="^M400", count=2)
        b.write("M400\nM400\nM400\n")
        b.run(1.0)
        self.assertEqual([l for _, l in b.rx], ["ok"])

    def test_two_rules_on_one_line_are_both_used_up(self):
        b = Bench()
        fault(b, fault="no_ok", nth=1)
        fault(b, fault="dup_ok", nth=1)
        self.assertIsNone(b.send("M400", timeout=2.0).ok_at)
        self.assertEqual(b.sim.state(b.t)["faults"], [])
        b.write("M400\n")
        b.run(0.1)
        self.assertEqual([l for _, l in b.rx], ["ok"])

    def test_a_rule_whose_line_a_power_cycle_lost_is_used_up(self):
        b = Bench()
        fault(b, fault="no_ok", match="^G4", count=1)
        b.write("G4 P5000\n")
        b.run(1.0)
        self.assertEqual(b.sim.state(b.t)["faults"][0]["pending"], 1)
        b.sim.control({"cmd": "power_cycle"}, b.t)
        self.assertEqual(b.sim.state(b.t)["faults"], [])
        self.assertEqual(b.send("G4 P10").lines, ["ok"])

    def test_clear_lets_held_output_go_at_once(self):
        """A delay_ok holds back every later line; clear releases them, so the controller is not mute for the rest
        of the delay."""
        b = Bench()
        fault(b, fault="delay_ok", s=600, match="^M400")
        b.write("M400\n")
        b.run(1.0)
        b.write("M105\n")
        b.run(1.0)
        self.assertEqual(b.rx, [])
        t = b.t
        self.assertTrue(b.sim.control({"cmd": "clear"}, b.t)["ok"])
        b.run(0.01)
        self.assertEqual([(round(tt - t, 3), l) for tt, l in b.rx], [(0.0, "ok"), (0.0, "ok T:0")])
        r = b.send("M115")
        self.assertEqual(r.lines[-1], "ok")
        self.assertLess(r.ok_at, 0.001)


class Injections(unittest.TestCase):
    def test_inject_lines(self):
        b = Bench()
        fault(b, fault="inject", kind="error")
        fault(b, fault="inject", kind="start")
        fault(b, fault="inject", kind="unknown")
        fault(b, fault="inject", text="echo:hello")
        b.run(0.01)
        self.assertEqual([l for _, l in b.rx],
                         ["Error:simulated fault", "start", 'echo:Unknown command: "M999999"', "echo:hello"])

    def test_inject_ff_byte_and_partial_line(self):
        b = Bench()
        fault(b, fault="inject_bytes", hex="ff")
        fault(b, fault="partial", text="X:1")
        b.run(0.01)
        self.assertEqual(b.raw, b"\xffX:1")

    def test_inject_after_delay(self):
        b = Bench()
        fault(b, fault="inject", text="start", after_s=1.5)
        b.run(3.0)
        self.assertEqual([(round(t, 3), l) for t, l in b.rx], [(1.5, "start")])

    def test_clear_and_power_cycle_drop_pending_injections(self):
        for cmd in ("clear", "power_cycle"):
            b = Bench()
            fault(b, fault="inject", text="start", after_s=1.5)
            b.run(1.0)
            self.assertTrue(b.sim.control({"cmd": cmd}, b.t)["ok"])
            b.run(3.0)
            self.assertEqual(b.rx, [], cmd)


class LinkAndPower(unittest.TestCase):
    def test_halt_is_a_dead_link(self):
        b = Bench()
        fault(b, fault="halt")
        self.assertIsNone(b.send("M115", timeout=3.0).ok_at)
        self.assertTrue(b.sim.state(b.t)["halted"])

    def test_halt_answers_nothing_at_all_until_a_power_cycle(self):
        """BR-09's dead link: no ok, no busy line, no report for any line, however long the host waits."""
        b = Bench()
        b.send("G28")
        fault(b, fault="halt")
        said = len(b.rx)
        for line in ("M105", "M114", "M115", "M503", "G4 P10", "M400", "G1 X10 F6000", "M410", "M112"):
            b.write(line + "\n")
        b.run(10.0)
        self.assertEqual(b.rx[said:], [])
        b.sim.power_cycle(b.t)
        self.assertEqual(b.send("M105").lines, ["ok T:0"])

    def test_transport_faults_are_handed_to_the_transport(self):
        b = Bench()
        fault(b, fault="disconnect", down_s=0.5)
        fault(b, fault="frozen", on=True)
        self.assertEqual(b.sim.take_transport_actions(),
                         [{"action": "disconnect", "down_s": 0.5}, {"action": "frozen", "on": True}])

    def test_power_cycle_request_returns_to_boot_and_loses_the_b_count(self):
        b = Bench()
        b.send("G92 B0")
        b.send("G1 B3600 F360000")
        b.send("M106 P2 S255")
        b.send("M400")
        reply = b.sim.control({"cmd": "power_cycle"}, b.t)
        self.assertTrue(reply["ok"])
        self.assertEqual(b.sim.take_transport_actions(), [{"action": "power_cycle", "link": None, "down_s": 0.0}])
        self.assertEqual(b.send("M114").lines[0],
                         "X:0.00 Y:0.00 Z:0.00 A:720.00 B:720.00 Count X:0 Y:0 Z:0 A:3197 B:3197")
        self.assertEqual(b.sim.state(b.t)["fans"], [0, 0, 0, 0])

    def test_power_cycle_resets_every_position_and_count(self):
        """BR-02: X 0, Y 0, Z 0 with B 720 and the counts of a boot, whatever came before."""
        b = Bench(park=(100.0, 50.0, 31.5))
        b.send("G28")
        b.send("G1 X120 Y80 Z20 A30 F12000")
        b.send("G92 B0")
        b.send("G1 B1800 F360000")
        b.send("M400")
        self.assertEqual(b.send("M114").lines[0],
                         "X:120.00 Y:80.00 Z:20.00 A:30.00 B:1800.00 Count X:38400 Y:25600 Z:800 A:133 B:11189")
        b.sim.power_cycle(b.t)
        self.assertEqual(b.send("M114").lines[0],
                         "X:0.00 Y:0.00 Z:0.00 A:720.00 B:720.00 Count X:0 Y:0 Z:0 A:3197 B:3197")
        self.assertEqual(b.sim.state(b.t)["homed"], {"X": False, "Y": False, "Z": False})

    def test_plain_state_survives_without_power_cycle(self):
        b = Bench()
        b.send("G92 B0")
        b.send("G1 B360 F360000")
        b.send("M400")
        line = b.send("M114").lines[0]
        self.assertTrue(line.endswith("B:4795"), line)  # 720 + 360 deg = 1080 deg * 4.44


    def test_power_cycle_with_a_down_time_is_handed_to_the_transport(self):
        b = Bench()
        reply = b.sim.control({"cmd": "power_cycle", "down_s": 1.5}, b.t)
        self.assertTrue(reply["ok"], reply)
        self.assertEqual(b.sim.take_transport_actions(), [{"action": "power_cycle", "link": None, "down_s": 1.5}])
        self.assertEqual(b.sim.state(b.t)["boots"], 2)  # the board resets at once; the port stays away 1.5 s
    def test_a_power_cycle_drops_output_the_old_board_had_not_sent(self):
        b = Bench()
        fault(b, fault="delay_ok", s=5, match="^M400")
        b.write("M400\n")
        b.run(1.0)
        b.sim.power_cycle(b.t)
        b.run(10.0)
        self.assertEqual(b.rx, [])

    def test_dfu_drops_the_port_again_after_a_power_cycle(self):
        b = Bench(personality="il-safety")
        for _ in range(2):
            b.write("M997\n")
            b.run(0.1)
            self.assertEqual(b.sim.take_transport_actions(), [{"action": "disconnect", "down_s": None}])
            b.sim.power_cycle(b.t)


class Set(unittest.TestCase):
    def test_set_vacuum_rs485_kill_messages(self):
        b = Bench()
        reply = b.sim.control({"cmd": "set", "vacuum": -1_200_000, "rs485": {"AA": "BB"},
                               "kill_messages": True}, b.t)
        self.assertTrue(reply["ok"], reply)
        self.assertIn("data:ED B0 80", b.send("M261 A109 B3 S1").lines[0])
        self.assertEqual(b.send("M485 AA").lines, ["rs485-reply: BB", "ok"])
        self.assertTrue(b.sim.state(b.t)["kill_messages"])

    def test_unknown_requests_are_refused(self):
        b = Bench()
        self.assertFalse(b.sim.control({"cmd": "nope"}, b.t)["ok"])
        self.assertFalse(b.sim.control({"cmd": "fault", "fault": "nope"}, b.t)["ok"])


class MalformedRequests(unittest.TestCase):
    """A bad control request is refused with ok:false and nothing changes; none of them may break the next line."""

    BAD = [
        {"cmd": "fault", "fault": "no_ok", "match": "^G4("},          # re.error is not a ValueError
        {"cmd": "fault", "fault": "no_ok", "match": 5},
        {"cmd": "fault", "fault": "no_ok", "count": "2"},
        {"cmd": "fault", "fault": "no_ok", "count": 0},
        {"cmd": "fault", "fault": "no_ok", "count": True},
        {"cmd": "fault", "fault": "no_ok", "nth": True},
        {"cmd": "fault", "fault": "delay_ok", "random": "ab"},
        {"cmd": "fault", "fault": "delay_ok", "random": [0.1, "x"]},
        {"cmd": "fault", "fault": "delay_ok", "random": [-1, 1]},
        {"cmd": "fault", "fault": "delay_ok", "s": float("nan")},
        {"cmd": "fault", "fault": "delay_ok", "s": -0.5},
        {"cmd": "fault", "fault": "inject", "text": 5},
        {"cmd": "fault", "fault": "inject", "text": "x", "after_s": float("nan")},
        {"cmd": "fault", "fault": "disconnect", "down_s": float("nan")},
        {"cmd": "fault", "fault": "disconnect", "down_s": -1},
        {"cmd": "set", "rs485": ["FF00", "0017"]},
        {"cmd": "set", "rs485": {"FF00": 17}},
        {"cmd": "set", "vacuum": float("inf")},
        {"cmd": "power_cycle", "down_s": "x"},
        {"cmd": "power_cycle", "down_s": float("nan")},
        {"cmd": "power_cycle", "link": 5},
        {"cmd": "fault", "fault": "no_ok", "line": 3},                # a misspelt selector: not every line
        {"cmd": "fault", "fault": "no_ok", "Nth": 3},
        {"cmd": "fault", "fault": "dup_ok", "regex": "^M400"},
        {"cmd": "fault", "fault": "no_ok", "s": 1},                   # s is delay_ok's
        {"cmd": "fault", "fault": "delay_ok", "s": 1, "after_s": 2},
    ]

    def test_each_is_refused_and_the_controller_keeps_answering(self):
        for req in self.BAD:
            b = Bench()
            reply = b.sim.control(dict(req), b.t)
            self.assertFalse(reply["ok"], req)
            self.assertIn("error", reply)
            self.assertEqual(b.sim.take_transport_actions(), [], req)
            self.assertEqual(b.sim.state(b.t)["faults"], [], req)
            self.assertEqual(b.sim.state(b.t)["boots"], 1, req)
            self.assertEqual(b.send("G4 P10").lines, ["ok"], req)
            self.assertEqual(b.send("M400").lines, ["ok"], req)


if __name__ == "__main__":
    unittest.main()
