"""init: one-time seeding of <root>/state/config."""
import os
import unittest

from fakes import Lab


class Init(unittest.TestCase):
    def setUp(self):
        self.lab = Lab()

    def tearDown(self):
        self.lab.cleanup()

    def test_seed_copies_db_with_wal_and_shm(self):
        self.assertEqual(self.lab.main("init", "--seed-from", self.lab.seed), 0, self.lab.last_out)
        il = os.path.join(self.lab.config, "instancelabs")
        self.assertEqual(sorted(os.listdir(il)),
                         ["dispense-config.json", "dispense.db", "dispense.db-shm", "dispense.db-wal"])
        with open(os.path.join(il, "dispense.db-wal"), "rb") as f:
            self.assertEqual(f.read(), b"wal-frames")
        for d in ("releases", "snapshots", "log", "prefs"):
            self.assertTrue(os.path.isdir(os.path.join(self.lab.root, d)), d)
        self.assertEqual(self.lab.journal()[-1]["event"], "init")

    def test_refuses_existing_state(self):
        self.assertEqual(self.lab.main("init", "--seed-from", self.lab.seed), 0)
        self.assertEqual(self.lab.main("init", "--seed-from", self.lab.seed), 2)
        self.assertIn("exists", self.lab.last_out)

    def test_refuses_a_running_real_openpnp(self):
        self.lab.procs.openpnp = [(4242, "/somewhere/realcfg", True)]
        self.assertEqual(self.lab.main("init", "--seed-from", self.lab.seed), 2)
        self.assertIn("4242", self.lab.last_out)
        self.assertFalse(os.path.exists(self.lab.config))

    def test_refuses_an_openpnp_on_the_seed(self):
        self.lab.procs.openpnp = [(4243, self.lab.seed, False)]
        self.assertEqual(self.lab.main("init", "--seed-from", self.lab.seed), 2)
        self.assertIn("4243", self.lab.last_out)

    def test_refuses_open_files_under_the_seed(self):
        with open(os.path.join(self.lab.seed, "instancelabs", "dispense.db"), "rb"):
            self.assertEqual(self.lab.main("init", "--seed-from", self.lab.seed), 2)
        self.assertIn("open", self.lab.last_out)
        self.assertFalse(os.path.exists(self.lab.config))

    def test_refuses_missing_seed(self):
        self.assertEqual(self.lab.main("init", "--seed-from", os.path.join(self.lab.dir, "nope")), 2)


if __name__ == "__main__":
    unittest.main()
