"""The rollback guard and the engine's database (#12, BR-02).

The engine journals the controller's state (connects, homes, the screw's motor-offs, the host watchdog) in
instancelabs/dispense.db's events table, so every health check adds rows there, after the deploy's baseline too (a
health check by hand). The guard compares that database by its rows, one entry per table, and leaves out only BR-02's
routine journal rows the engine wrote while the deployer held the machine (the newest events id at each claim and
release, so in insert order: an event's ts is when it happened, not when it was written). Any other change still
refuses a rollback: places, setups and records are what a rollback restores (PM 11-04 07:2x), and a real reset's
journal must not be lost to a rollback's old database.
"""
import json
import os
import shutil
import sqlite3
import tempfile
import unittest

import il_deploy
from fakes import Lab

DB = os.path.join("instancelabs", "dispense.db")


def engine_db(path):
    """A database as the engine keeps it (WAL; the events table as DispenseDb's), held open as OpenPnP holds it."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    c = sqlite3.connect(path)
    c.execute("PRAGMA journal_mode=WAL")
    c.execute("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, run_uuid TEXT,"
              " level TEXT NOT NULL, code TEXT, message TEXT)")
    c.execute("CREATE TABLE tip_setups (id TEXT PRIMARY KEY, name TEXT)")
    c.execute("INSERT INTO tip_setups VALUES ('25g', '25G plastic taper')")
    c.execute("INSERT INTO events(ts, level, code, message) VALUES (1000, 'INFO', 'POSITION_KNOWN', 'homed')")
    c.commit()
    return c


def event(c, code="SCREW_MOTOR_OFF", run=None, ts=5000):
    c.execute("INSERT INTO events(ts, run_uuid, level, code, message) VALUES (?, ?, 'WARN', ?, 'x')", (ts, run, code))
    c.commit()


class Manifest(unittest.TestCase):
    def setUp(self):
        self.cfg = tempfile.mkdtemp(prefix="il-guard-")
        self.addCleanup(shutil.rmtree, self.cfg, True)
        with open(os.path.join(self.cfg, "machine.xml"), "w") as f:
            f.write("<openpnp-machine/>\n")
        self.db = engine_db(os.path.join(self.cfg, DB))
        self.addCleanup(self.db.close)
        self.base = il_deploy.config_manifest(self.cfg)
        self.after = il_deploy.engine_db_newest_event(self.cfg)
        self.assertEqual(self.after, 1)

    def changes(self, ranges=()):
        live = il_deploy.config_manifest(self.cfg, journal=(self.after, list(ranges)))
        return il_deploy.manifest_changes(self.base, live)

    def test_the_database_is_compared_by_its_rows_one_entry_per_table(self):
        self.assertEqual(sorted(k for k in self.base if k.startswith(DB)), [DB + "#events", DB + "#tip_setups"])
        self.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")  # the rows move from the WAL into the db file
        self.assertEqual(self.changes(), [])
        with open(os.path.join(self.cfg, "machine.xml"), "a") as f:
            f.write("<!-- edited -->\n")
        self.assertEqual(self.changes(), ["machine.xml changed"])  # the other files: by their bytes, as before

    def test_the_deployers_own_routine_journal_is_no_change(self):
        for code in il_deploy.ROUTINE_JOURNAL:
            event(self.db, code)
        newest = il_deploy.engine_db_newest_event(self.cfg)
        self.assertEqual(self.changes([(1, newest)]), [])
        self.assertEqual(self.changes([(0, newest)]), [], "a claim made before the baseline: the baseline decides")

    def test_a_journal_row_outside_the_deployers_claims_is_a_change(self):
        event(self.db)  # id 2: written while nobody from the deployer held the machine
        event(self.db)  # id 3
        self.assertEqual(self.changes([(2, 3)]), [DB + "#events changed"], "id 2 is outside the claim (2, 3]")
        self.assertEqual(self.changes([(1, 3)]), [])

    def test_a_real_reset_journaled_even_inside_a_claim_is_a_change(self):
        for code in ("CONTROLLER_RESET", "POSITION_UNREAD", "Z_FRAME_MOVED", "HOST_WATCHDOG_TRIP"):
            with self.subTest(code):
                self.db.execute("DELETE FROM events WHERE id > 1")
                self.db.commit()
                event(self.db, code)
                self.assertEqual(self.changes([(1, 99)]), [DB + "#events changed"])

    def test_any_other_change_during_a_dry_run_still_trips_the_guard(self):
        """PM 07:2x: places, setups and records are what a rollback must restore."""
        cases = {
            "a tip setup changed": "UPDATE tip_setups SET name = 'x' WHERE id = '25g'",
            "a tip setup added": "INSERT INTO tip_setups VALUES ('22g', 'bent 22G')",
            "an event of a run": "INSERT INTO events(ts, run_uuid, level, code) VALUES (5000, 'r1', 'INFO', "
                                 "'SCREW_MOTOR_OFF')",
            "another code (I4 at a reset's home)": "INSERT INTO events(ts, level, code) VALUES (5000, 'WARN', 'I4')",
            "an old journal row changed": "UPDATE events SET message = 'y' WHERE id = 1",
            "an old journal row deleted": "DELETE FROM events WHERE id = 1",
            "a table added": "CREATE TABLE places (name TEXT)",
        }
        for why, sql in cases.items():
            with self.subTest(why):
                self.setUp()
                self.db.execute(sql)
                self.db.commit()
                newest = il_deploy.engine_db_newest_event(self.cfg) or 1
                self.assertNotEqual(self.changes([(1, max(newest, 99))]), [], why)
        self.setUp()
        with open(os.path.join(self.cfg, "instancelabs", "dispense-config.json"), "w") as f:
            json.dump({"locations": {"waste": {"x": 160, "y": 265}}}, f)  # a place: the config file, by its bytes
        self.assertEqual(self.changes([(1, 99)]), [os.path.join("instancelabs", "dispense-config.json") + " added"])

    def test_a_database_that_is_not_sqlite_is_compared_by_its_files_as_before(self):
        cfg = tempfile.mkdtemp(prefix="il-guard-")
        self.addCleanup(shutil.rmtree, cfg, True)
        os.makedirs(os.path.join(cfg, "instancelabs"))
        for ext, data in (("", b"SQLite format 3\0main"), ("-wal", b"wal-frames"), ("-shm", b"shm-index")):
            with open(os.path.join(cfg, DB + ext), "wb") as f:
                f.write(data)
        self.assertEqual(sorted(il_deploy.config_manifest(cfg)), [DB, DB + "-wal"])
        self.assertIsNone(il_deploy.engine_db_newest_event(cfg))

    def test_a_baseline_kept_by_bytes_is_compared_by_bytes(self):
        """A deploy made by the deployer before this one kept the db and its -wal by their bytes."""
        old = il_deploy.config_manifest(self.cfg, rows=False)
        self.assertEqual(sorted(k for k in old if k.startswith(DB)), [DB, DB + "-wal"])

    def test_the_claims_are_read_from_the_deployers_log(self):
        log = os.path.join(self.cfg, "deploy.jsonl")
        with open(log, "w") as f:
            for r in ({"event": "claim", "events_id": 4}, {"event": "health"}, {"event": "release", "events_id": 9},
                      {"event": "claim", "events_id": None}, {"event": "release", "events_id": 12},
                      {"event": "claim"}, {"event": "release"},  # a build without a database
                      {"event": "claim", "events_id": 20}):  # never released (a hard kill): no range
                f.write(json.dumps(r) + "\n")
        self.assertEqual(il_deploy.claim_ranges(log), [(4, 9), (0, 12)])
        self.assertEqual(il_deploy.claim_ranges(os.path.join(self.cfg, "missing.jsonl")), [])


class RollbackFlow(unittest.TestCase):
    """deploy, deploy, a health check by hand, rollback: with an engine that journals (#12's) in a real database."""

    def setUp(self):
        self.lab = Lab()
        self.gh = self.lab.gh
        seed_db = os.path.join(self.lab.seed, DB)
        for ext in ("", "-wal", "-shm"):
            os.remove(seed_db + ext)
        engine_db(seed_db).close()
        self.assertEqual(self.lab.main("init", "--seed-from", self.lab.seed), 0, self.lab.last_out)

    def tearDown(self):
        self.lab.cleanup()

    def release(self, n, sha, **jar):
        tag = self.gh.add_release(n, sha, jar=dict(jar, sha=sha, journal=True))
        self.assertEqual(self.lab.main("poll"), 0, self.lab.last_out + self.lab.last_err)
        return tag

    def deployed_then_checked_by_hand(self):
        a = self.release(4, self.lab.b)
        self.assertEqual(self.lab.main("deploy"), 0, self.lab.last_out + self.lab.last_err)
        self.release(5, self.lab.c)
        self.assertEqual(self.lab.main("deploy"), 0, self.lab.last_out + self.lab.last_err)
        baseline = il_deploy.read_json(os.path.join(self.lab.root, "deployed.json"), {})["current"]["post_events_id"]
        self.lab.start_service()  # the deploys left it as they found it: stopped
        self.assertEqual(self.lab.main("health"), 0, self.lab.last_out + self.lab.last_err)
        self.assertGreater(il_deploy.engine_db_newest_event(self.lab.config), baseline, "the health check journaled")
        return a

    def live_db(self):
        c = sqlite3.connect(os.path.join(self.lab.config, DB))
        self.addCleanup(c.close)
        return c

    def test_the_deployers_own_journal_does_not_refuse_a_rollback(self):
        a = self.deployed_then_checked_by_hand()
        self.assertEqual(self.lab.main("rollback"), 0, self.lab.last_out + self.lab.last_err)
        self.assertEqual(self.lab.link("current"), a)

    def test_a_setup_changed_after_the_deploy_still_refuses_it(self):
        self.deployed_then_checked_by_hand()
        c = self.live_db()
        c.execute("UPDATE tip_setups SET name = 'edited in use' WHERE id = '25g'")
        c.commit()
        self.assertEqual(self.lab.main("rollback"), 2, self.lab.last_out + self.lab.last_err)
        self.assertIn(DB + "#tip_setups changed", self.lab.last_out)
        self.assertIn("--discard-config-changes", self.lab.last_out)

    def test_a_reset_journaled_in_use_still_refuses_it(self):
        self.deployed_then_checked_by_hand()
        event(self.live_db(), "CONTROLLER_RESET")
        self.assertEqual(self.lab.main("rollback"), 2, self.lab.last_out + self.lab.last_err)
        self.assertIn(DB + "#events changed", self.lab.last_out)


if __name__ == "__main__":
    unittest.main()
