"""INF-05 phase 2: lumen-sim publishes the head's physical error at <link>.pose.json, for the SIM cameras (fake clock).

The file holds {"offset": {"X", "Y", "Z"}}: physical - logical (mm), the control state's head offset. It is rewritten
(atomically) only when the offset changes, never inside a G28, before the output of that step and before a control
reply; it follows the link, and goes at shutdown.
"""
import json
import os
import unittest

from tests.simproc import open_raw
from tests.test_transport import in_process_runner


class PoseFile(unittest.TestCase):
    def setUp(self):
        self.clock = [0.0]
        self.r = in_process_runner(self, clock=lambda: self.clock[0])
        self.r.link.open()
        self.path = self.r.link.link + ".pose.json"

    def step(self, dt=0.0):
        self.clock[0] += dt
        self.r._service()

    def send(self, line, dt=120.0):
        """A G-code line as the host sends it, then dt simulator seconds (long enough for a G28)."""
        self.r.sim.receive((line + "\n").encode(), self.r.now())
        self.step(dt)

    def control(self, **req):
        reply = self.r._handle_control(req)
        self.assertTrue(reply["ok"], reply)

    def offset(self, path=None):
        with open(path or self.path) as f:
            return json.load(f)["offset"]

    def head_offset(self):
        return self.r.sim.state(self.r.now())["head"]["offset"]

    def test_published_at_start_over_a_file_a_killed_lumen_sim_left(self):
        with open(self.path, "w") as f:
            f.write("{not json")
        self.step()
        self.assertEqual(self.offset(), self.head_offset())  # at boot: the park position, as logical reads 0
        self.assertNotEqual(self.offset()["X"], 0.0)

    def test_a_home_off_its_switch(self):
        self.control(cmd="fault", fault="home_offset", x=0.5)
        self.send("G28")
        self.assertEqual(self.offset(), {"X": 0.5, "Y": 0.0, "Z": 0.0})

    def test_lost_steps_with_no_command(self):
        self.send("G28")
        self.assertEqual(self.offset(), {"X": 0.0, "Y": 0.0, "Z": 0.0})
        self.control(cmd="fault", fault="lost_steps", y=-0.3)
        self.step()
        self.assertEqual(self.offset(), {"X": 0.0, "Y": -0.3, "Z": 0.0})

    def test_not_inside_a_g28(self):
        """Each homing phase zeroes its axis (no head error); OpenPnP believes the old place until the ok."""
        self.step()
        boot = self.offset()
        self.send("G28", dt=1.0)
        self.assertTrue(self.r.sim.fw.in_g28)
        self.assertNotEqual(self.head_offset(), boot)  # what would have been published
        self.assertEqual(self.offset(), boot)
        self.step(120.0)
        self.assertEqual(self.offset(), {"X": 0.0, "Y": 0.0, "Z": 0.0})

    def test_the_homes_ok_goes_out_after_the_file(self):
        host = open_raw(self.r.link.link)
        self.addCleanup(os.close, host)
        self.step()
        self.control(cmd="fault", fault="home_offset", x=0.5)
        seen, write = [], self.r.link.write

        def spy(data):
            if b"ok" in data:
                seen.append(self.offset())
            return write(data)
        self.r.link.write = spy
        self.send("G28")
        self.assertTrue(seen)
        for offset in seen:
            self.assertEqual(offset, {"X": 0.5, "Y": 0.0, "Z": 0.0})

    def test_a_control_reply_finds_it_written(self):
        self.send("G28")
        self.control(cmd="fault", fault="lost_steps", x=0.25)  # no step after it
        self.assertEqual(self.offset(), {"X": 0.25, "Y": 0.0, "Z": 0.0})

    @unittest.skipIf(os.geteuid() == 0, "root writes into a read-only directory")
    def test_a_failed_write_is_noted_once_and_tried_again(self):
        self.step()
        notes = []
        self.r._note = notes.append
        folder = os.path.dirname(self.path)
        os.chmod(folder, 0o500)
        self.addCleanup(os.chmod, folder, 0o700)
        self.control(cmd="fault", fault="lost_steps", y=0.25)
        self.step()
        self.step()  # must not raise, nor note it again
        self.assertEqual(len([n for n in notes if n.startswith("pose file: ")]), 1, notes)
        os.chmod(folder, 0o700)
        self.step()
        self.assertEqual(self.offset(), self.head_offset())

    def test_a_plain_move_keeps_it_and_does_not_rewrite_it(self):
        self.control(cmd="fault", fault="home_offset", x=0.5)
        self.send("G28")
        ino = os.stat(self.path).st_ino  # each write is a new file (os.replace)
        self.send("G1 X50 Y20 F600", dt=2.0)  # 10 mm/s: still moving
        self.send("M400")
        self.assertEqual(self.offset(), {"X": 0.5, "Y": 0.0, "Z": 0.0})
        self.assertEqual(os.stat(self.path).st_ino, ino)

    def test_a_power_cycle_restarts_the_belief_and_the_head_stays(self):
        self.send("G28")
        self.send("G1 X50 Y20 F12000")
        self.control(cmd="power_cycle")
        self.step()
        self.assertEqual(self.offset(), {"X": 50.0, "Y": 20.0, "Z": self.head_offset()["Z"]})

    def test_it_follows_the_link_to_a_new_path(self):
        self.step()
        new = os.path.join(os.path.dirname(self.path), "ttyLUMEN1")
        self.control(cmd="power_cycle", link=new, down_s=1.0)
        self.step()
        self.assertTrue(os.path.exists(self.path), "the head is still there while the port is down")
        self.step(1.5)
        self.assertFalse(os.path.exists(self.path))
        self.assertEqual(self.offset(new + ".pose.json"), self.head_offset())

    def test_removed_at_shutdown(self):
        self.step()
        self.assertTrue(os.path.exists(self.path))
        self.r.shutdown()
        self.assertFalse(os.path.exists(self.path))


if __name__ == "__main__":
    unittest.main()
