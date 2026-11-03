package org.openpnp.instancelabs.dispense;

import org.openpnp.instancelabs.padprofile.Tip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Schema 1 -> 2 (profile calibration design §5.4), then -> 3 in the same open (tip setups design §22.1): a
 * schema-1 file made with the schema-1 DDL is backed up once and migrated step by step on open, old rows read
 * with null new fields, every table stays append-only, and a second open changes nothing.
 */
public class DispenseDbMigrationTest {
    /** The schema-1 DDL as DispenseDb created it before schema 2 (frozen). */
    static final String[] V1_DDL = {
        "CREATE TABLE IF NOT EXISTS schema_info (version INTEGER NOT NULL, created_at INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS pad_types ("
            + " row_id INTEGER PRIMARY KEY AUTOINCREMENT, type_key TEXT NOT NULL, version INTEGER NOT NULL,"
            + " family TEXT NOT NULL, name TEXT NOT NULL, pad_w_mm REAL NOT NULL, pad_h_mm REAL NOT NULL,"
            + " pad_roundness REAL NOT NULL DEFAULT 0, layout TEXT NOT NULL, pitch_mm REAL,"
            + " t_mm REAL NOT NULL DEFAULT 0.10, k REAL NOT NULL DEFAULT 1.0,"
            + " status TEXT NOT NULL CHECK (status IN ('not-started','provisional','characterized','validated')),"
            + " package_pattern TEXT, notes TEXT, created_at INTEGER NOT NULL, operator TEXT,"
            + " UNIQUE(type_key, version))",
        "CREATE TABLE IF NOT EXISTS pad_rules ("
            + " row_id INTEGER PRIMARY KEY AUTOINCREMENT, type_key TEXT NOT NULL, version INTEGER NOT NULL,"
            + " max_dots INTEGER, fit_factor REAL, largest_single_dot_pad_mm2 REAL, grid_pitch_rule TEXT,"
            + " created_at INTEGER NOT NULL, notes TEXT, UNIQUE(type_key, version))",
        "CREATE TABLE IF NOT EXISTS package_pad_type ("
            + " row_id INTEGER PRIMARY KEY AUTOINCREMENT, package_id TEXT NOT NULL, type_key TEXT,"
            + " version INTEGER NOT NULL, created_at INTEGER NOT NULL, UNIQUE(package_id, version))",
        "CREATE TABLE IF NOT EXISTS calibration ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, operator TEXT,"
            + " method TEXT NOT NULL, status TEXT NOT NULL CHECK (status IN ('provisional','characterized')),"
            + " tip TEXT, cartridge TEXT, paste_lot TEXT, set_pressure_psi REAL, b_feed_deg_s REAL,"
            + " q_mm3_per_rev REAL NOT NULL CHECK (q_mm3_per_rev > 0), q_cv REAL, q_n INTEGER,"
            + " density_mg_mm3 REAL, barrel_temp_c REAL, barrel_pressure_psi REAL, paste_hours_rt REAL,"
            + " valid_until INTEGER, notes TEXT)",
        "CREATE TABLE IF NOT EXISTS dot_ladder ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT, calibration_id INTEGER NOT NULL REFERENCES calibration(id),"
            + " created_at INTEGER NOT NULL, rotation_deg REAL NOT NULL CHECK (rotation_deg > 0),"
            + " z_gap_mm REAL, n INTEGER NOT NULL, diameter_mean_mm REAL, diameter_cv REAL,"
            + " area_mean_mm2 REAL, area_cv REAL, mg_per_dot_mean REAL, mg_per_dot_se REAL, height_mm REAL,"
            + " method TEXT, notes TEXT)",
        "CREATE TABLE IF NOT EXISTS pad_plan ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT, plan_id TEXT NOT NULL, created_at INTEGER NOT NULL,"
            + " board TEXT, pad_id TEXT NOT NULL, type_key TEXT, type_version INTEGER, area_mm2 REAL,"
            + " target_volume_mm3 REAL, dots INTEGER, rotation_per_dot_deg REAL, predicted_diameter_mm REAL,"
            + " predicted_height_mm REAL, calibration_id INTEGER, flags TEXT)",
        "CREATE TABLE IF NOT EXISTS runs ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT, run_uuid TEXT NOT NULL UNIQUE, kind TEXT NOT NULL,"
            + " plan_id TEXT, board TEXT, started_at INTEGER NOT NULL, ended_at INTEGER, status TEXT NOT NULL,"
            + " dots_planned INTEGER, dots_done INTEGER, first_dot_start_ms INTEGER, last_dot_end_ms INTEGER,"
            + " motion_s REAL, total_s REAL, dph_motion REAL, dph_overall REAL, calibration_id INTEGER,"
            + " keepalive INTEGER, lines_sent INTEGER, message TEXT)",
        "CREATE TABLE IF NOT EXISTS dot_log ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT, run_uuid TEXT NOT NULL, dot_index INTEGER NOT NULL,"
            + " pad_id TEXT, rotation_deg REAL, start_ms INTEGER, end_ms INTEGER)",
        "CREATE TABLE IF NOT EXISTS events ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, run_uuid TEXT, level TEXT NOT NULL,"
            + " code TEXT, message TEXT)",
        "CREATE TABLE IF NOT EXISTS needle_touchoff ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, method TEXT NOT NULL,"
            + " z_offset_mm REAL NOT NULL, previous_offset_mm REAL, details TEXT, operator TEXT)",
        "CREATE TABLE IF NOT EXISTS scale_entries ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, kind TEXT NOT NULL"
            + " CHECK (kind IN ('shots','dots')), rotation_deg REAL NOT NULL, count INTEGER NOT NULL,"
            + " total_mass_mg REAL NOT NULL, per_item_mg TEXT, density_mg_mm3 REAL, calibration_id INTEGER,"
            + " notes TEXT, operator TEXT)",
        "CREATE VIEW IF NOT EXISTS pad_types_current AS SELECT p.* FROM pad_types p JOIN"
            + " (SELECT type_key, MAX(version) AS v FROM pad_types GROUP BY type_key) m"
            + " ON p.type_key = m.type_key AND p.version = m.v",
    };
    static final List<String> V1_TABLES = Arrays.asList("schema_info", "pad_types", "pad_rules", "package_pad_type",
            "calibration", "dot_ladder", "pad_plan", "runs", "dot_log", "events", "needle_touchoff", "scale_entries");
    static final List<String> NEW_PAD_TYPE_COLUMNS = Arrays.asList("dot_steps", "dots_per_pad", "tip_key", "dose_fp",
            "cal_area_mm2", "mass_per_pad_mg", "origin");

    /** A config dir whose dispense.db is a schema-1 file (WAL, as the old code left it) with rows. */
    static File schemaOneDir() throws Exception {
        File dir = Files.createTempDirectory("dispense-v1").toFile();
        File f = DispenseDb.fileFor(dir);
        f.getParentFile().mkdirs();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
                Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            for (String ddl : V1_DDL) {
                st.execute(ddl);
            }
            for (String t : V1_TABLES) {
                st.execute("CREATE TRIGGER IF NOT EXISTS " + t + "_no_update BEFORE UPDATE ON " + t
                        + " BEGIN SELECT RAISE(ABORT, '" + t + " is append-only'); END");
                st.execute("CREATE TRIGGER IF NOT EXISTS " + t + "_no_delete BEFORE DELETE ON " + t
                        + " BEGIN SELECT RAISE(ABORT, '" + t + " is append-only'); END");
            }
            st.execute("INSERT INTO schema_info(version, created_at) VALUES (1, 1000)");
            st.execute("INSERT INTO pad_types(type_key, version, family, name, pad_w_mm, pad_h_mm, layout, pitch_mm,"
                    + " status, package_pattern, created_at, operator) VALUES ('chip-0603', 1, 'Chip (2-terminal SMD)',"
                    + " '0603', 0.80, 0.95, 'two-terminal', 1.65, 'not-started', '0603', 1000, 'seed')");
            st.execute("INSERT INTO pad_types(type_key, version, family, name, pad_w_mm, pad_h_mm, layout, pitch_mm,"
                    + " status, package_pattern, created_at, operator) VALUES ('chip-0603', 2, 'Chip (2-terminal SMD)',"
                    + " '0603', 0.80, 0.95, 'two-terminal', 1.65, 'validated', '0603', 2000, 'bench')");
            st.execute("INSERT INTO pad_types(type_key, version, family, name, pad_w_mm, pad_h_mm, layout, pitch_mm,"
                    + " status, created_at) VALUES ('sot-23', 1, 'Small-outline discretes', 'SOT-23', 1.325, 0.60,"
                    + " 'sot23-3', 2.275, 'not-started', 1000)");
            st.execute("INSERT INTO package_pad_type(package_id, type_key, version, created_at)"
                    + " VALUES ('R_0603_1608Metric', 'chip-0603', 1, 1500)");
            st.execute("INSERT INTO runs(run_uuid, kind, started_at, status, message) VALUES ('run-v1', 'board', 3000,"
                    + " 'OK', 'board 2')");
            st.execute("INSERT INTO events(ts, level, code, message) VALUES (3000, 'INFO', 'X', 'v1 event')");
        }
        assertTrue(f.exists());
        return dir;
    }

    static File[] backups(File dir) {
        File[] b = DispenseDb.fileFor(dir).getParentFile()
                .listFiles((d, n) -> n.matches("dispense\\.db\\.v1-\\d{8}-\\d{6}(-\\d+)?\\.bak"));
        return b == null ? new File[0] : b;
    }

    static List<String> columns(DispenseDb db, String table) throws SQLException {
        List<String> l = new ArrayList<>();
        for (Map<String, Object> m : db.query("PRAGMA table_info(" + table + ")")) {
            l.add((String) m.get("name"));
        }
        return l;
    }

    /** The newest version of a type as stored (not composed for a setup). */
    static PadType stored(DispenseDb db, String key) throws SQLException {
        List<PadType> h = db.padTypeHistory(key);
        return h.get(h.size() - 1);
    }

    static List<Integer> schemaRows(DispenseDb db) throws SQLException {
        List<Integer> l = new ArrayList<>();
        for (Map<String, Object> m : db.query("SELECT version FROM schema_info ORDER BY rowid")) {
            l.add(((Number) m.get("version")).intValue());
        }
        return l;
    }

    @Test
    public void schemaOneFileIsBackedUpAndMigrated() throws Exception {
        File dir = schemaOneDir();
        assertEquals(0, backups(dir).length);
        try (DispenseDb db = DispenseDb.open(dir)) {
            assertEquals(3, DispenseDb.SCHEMA_VERSION);
            assertEquals(3, (int) db.schemaVersion());
            assertEquals(Arrays.asList(1, 2, 3), schemaRows(db), "schema_info is append-only: a new row per step");
            assertTrue(columns(db, "pad_types").containsAll(NEW_PAD_TYPE_COLUMNS), columns(db, "pad_types").toString());
            assertTrue(columns(db, "package_pad_type").contains("pad_names"));
            assertTrue(columns(db, "runs").contains("skipped_pads"));
            assertTrue(columns(db, "dose_records").containsAll(Arrays.asList("kind", "steps", "coupon_record_id",
                    "dose_fp", "tip_key", "paste_key", "regulator_psi", "controller_connected_at_ms", "simulated")));
            assertTrue(columns(db, "pad_calibrations").containsAll(Arrays.asList("job_id", "type_key", "outcome",
                    "checks_json", "evidence_json", "runs_json")));
            assertTrue(columns(db, "package_options").containsAll(Arrays.asList("package_id", "version",
                    "paste_partial")));
            assertTrue(db.pastePartialPackages().isEmpty(), "decision 4: off for every package");
            // the view was made again: it lists the new columns
            assertTrue(columns(db, "pad_types_current").containsAll(NEW_PAD_TYPE_COLUMNS));
            // old rows read with null new fields; nothing was seeded again
            assertEquals(2, db.currentPadTypes(Tip.SEED_KEY).size());
            PadType chip = stored(db, "chip-0603");
            assertEquals(2, chip.version);
            assertEquals(PadTypeStatus.VALIDATED, chip.status);
            assertNull(chip.dotSteps);
            assertNull(chip.dotsPerPad);
            assertNull(chip.tipKey);
            assertNull(chip.doseFp);
            assertNull(chip.calAreaMm2);
            assertNull(chip.massPerPadMg);
            assertNull(chip.origin);
            assertEquals("chip-0603", db.packageMappings().get("R_0603_1608Metric"));
            List<Map<String, Object>> runs = db.recentRuns(5);
            assertEquals("board 2", runs.get(0).get("message"));
            assertNull(runs.get(0).get("skipped_pads"));
            List<Map<String, Object>> evs = db.query("SELECT message FROM events WHERE code = 'SCHEMA' ORDER BY id");
            assertEquals(2, evs.size(), evs.toString());
            assertTrue(((String) evs.get(0).get("message")).startsWith("dispense.db schema 1 -> 2; the old file is kept "
                    + "as dispense.db.v1-"), evs.toString());
            assertEquals("dispense.db schema 2 -> 3", evs.get(1).get("message"), "one backup, before the first step");

            // the new fields round-trip on the migrated file
            PadType v3 = chip.copy();
            v3.status = PadTypeStatus.CHARACTERIZED;
            v3.dotSteps = 800;
            v3.dotsPerPad = 1;
            v3.tipKey = "ad22tta-b";
            v3.doseFp = new DispenseProfile().doseFingerprint();
            v3.calAreaMm2 = 0.7257;
            v3.massPerPadMg = 1.056;
            v3.origin = PadType.ORIGIN_AUTO_CALIBRATED;
            db.insertPadTypeVersion(v3);
            PadType back = db.currentPadType("chip-0603", Tip.SEED_KEY);
            assertEquals(3, back.version);
            assertEquals(800, (int) back.dotSteps);
            assertEquals(1, (int) back.dotsPerPad);
            assertEquals("ad22tta-b", back.tipKey);
            assertEquals(v3.doseFp, back.doseFp);
            assertEquals(0.7257, back.calAreaMm2, 0);
            assertEquals(1.056, back.massPerPadMg, 0);
            assertEquals("auto-calibrated", back.origin);
            assertNull(db.padTypeHistory("chip-0603").get(0).origin);

            // every table, old and new, is still append-only (row triggers need a row in each)
            DispenseDbTest.oneRowInEveryTable(db);
            for (String t : DispenseDb.TABLES) {
                assertTrue(((Number) db.query("SELECT COUNT(*) AS n FROM " + t).get(0).get("n")).intValue() > 0, t);
                assertThrows(SQLException.class, () -> db.executeRaw("DELETE FROM " + t), t);
            }
            assertThrows(SQLException.class, () -> db.executeRaw("UPDATE pad_types SET dot_steps = 1600"));
            assertThrows(SQLException.class, () -> db.executeRaw("UPDATE package_pad_type SET pad_names = '1'"));
            assertThrows(SQLException.class, () -> db.executeRaw("UPDATE runs SET skipped_pads = '[]'"));
            assertThrows(SQLException.class, () -> db.executeRaw("UPDATE dose_records SET total_mg = 1"));
            assertThrows(SQLException.class, () -> db.executeRaw("UPDATE pad_calibrations SET outcome = 'x'"));
            assertThrows(SQLException.class, () -> db.executeRaw("UPDATE package_options SET paste_partial = 0"));
            assertThrows(SQLException.class, () -> db.executeRaw("UPDATE schema_info SET version = 1"));
        }

        // the backup is the schema-1 file as it was
        File[] b = backups(dir);
        assertEquals(1, b.length);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + b[0].getAbsolutePath());
                Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT MAX(version), COUNT(*) FROM schema_info")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals(1, rs.getInt(2));
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM pad_types")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE name IN ('dose_records',"
                    + " 'pad_calibrations', 'package_options')")) {
                assertFalse(rs.next(), "no schema-2 table in the backup");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM pragma_table_info('pad_types')"
                    + " WHERE name = 'dot_steps'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }

        // a second open is a no-op: no new schema row, no new backup, the same columns and rows
        int padTypeRows;
        List<String> padTypeColumns;
        try (DispenseDb db = DispenseDb.open(dir)) {
            padTypeRows = db.query("SELECT * FROM pad_types").size();
            padTypeColumns = columns(db, "pad_types");
        }
        try (DispenseDb db = DispenseDb.open(dir)) {
            assertEquals(Arrays.asList(1, 2, 3), schemaRows(db));
            assertEquals(padTypeRows, db.query("SELECT * FROM pad_types").size());
            assertEquals(padTypeColumns, columns(db, "pad_types"));
            assertEquals(2, db.query("SELECT * FROM events WHERE code = 'SCHEMA'").size());
        }
        assertEquals(1, backups(dir).length);
    }

    @Test
    public void newDatabaseIsCreatedAtTheCurrentSchema() throws Exception {
        File dir = Files.createTempDirectory("dispense-v2").toFile();
        try (DispenseDb db = DispenseDb.open(dir)) {
            assertEquals(Arrays.asList(3), schemaRows(db), "created at 3 directly");
            assertTrue(columns(db, "pad_types").containsAll(NEW_PAD_TYPE_COLUMNS));
            assertTrue(columns(db, "pad_types_current").containsAll(NEW_PAD_TYPE_COLUMNS));
            assertTrue(columns(db, "package_pad_type").contains("pad_names"));
            assertTrue(columns(db, "runs").contains("skipped_pads"));
            assertEquals(12, db.currentPadTypes(Tip.SEED_KEY).size());
            for (PadType c : db.currentPadTypes(Tip.SEED_KEY)) {
                PadType t = stored(db, c.typeKey);
                assertEquals("seed", t.origin, t.typeKey);
                assertNull(t.dotSteps, t.typeKey);
                assertNull(t.tipKey, t.typeKey);
                assertEquals("ad22tta-b", c.tipKey, "a seed belongs to the seeded setup (§22.1)");
            }
            assertTrue(db.query("SELECT * FROM events WHERE code = 'SCHEMA'").isEmpty());
        }
        assertEquals(0, backups(dir).length);
        try (DispenseDb db = DispenseDb.open(dir)) {
            assertEquals(Arrays.asList(3), schemaRows(db));
        }
        try (DispenseDb mem = DispenseDb.inMemory()) {
            assertEquals(Arrays.asList(3), schemaRows(mem));
            assertTrue(columns(mem, "dose_records").contains("paste_key"));
        }
    }
}
