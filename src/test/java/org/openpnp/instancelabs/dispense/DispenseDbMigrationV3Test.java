package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Tip;

/**
 * Schema 2 -> 3 (tip setups design §22.1, §22.8): a schema-2 file made with the schema-2 DDL and rows (pad types
 * with tip_key null, ad22tta-b and x25; dose records of two jobs, one x25; a characterized calibration with its
 * version and a failed x25 one without; runs) is backed up and migrated: the seven tables with their triggers, the
 * two views, the new columns, the seeded setup and a placeholder x25. Every row that existed is the same after it on
 * the schema-2 column list; the failed record resolves to x25 through its job's dose records; a second open changes
 * nothing.
 */
public class DispenseDbMigrationV3Test {
    /** What schema 2 added to the schema-1 DDL (frozen as DispenseDb wrote it before schema 3). */
    static final String[] V2_DDL = {
        "ALTER TABLE pad_types ADD COLUMN dot_steps INTEGER", "ALTER TABLE pad_types ADD COLUMN dots_per_pad INTEGER",
        "ALTER TABLE pad_types ADD COLUMN tip_key TEXT", "ALTER TABLE pad_types ADD COLUMN dose_fp TEXT",
        "ALTER TABLE pad_types ADD COLUMN cal_area_mm2 REAL", "ALTER TABLE pad_types ADD COLUMN mass_per_pad_mg REAL",
        "ALTER TABLE pad_types ADD COLUMN origin TEXT", "ALTER TABLE package_pad_type ADD COLUMN pad_names TEXT",
        "ALTER TABLE runs ADD COLUMN skipped_pads TEXT",
        "DROP VIEW IF EXISTS pad_types_current",
        "CREATE VIEW IF NOT EXISTS pad_types_current AS SELECT p.* FROM pad_types p JOIN (SELECT type_key, MAX(version)"
            + " AS v FROM pad_types GROUP BY type_key) m ON p.type_key = m.type_key AND p.version = m.v",
        "CREATE TABLE IF NOT EXISTS dose_records ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL,"
            + " kind TEXT NOT NULL CHECK (kind IN ('optical','coupon','mass')),"
            + " steps INTEGER NOT NULL, n INTEGER NOT NULL,"
            + " found INTEGER, misses INTEGER, hops INTEGER, threads INTEGER, hop_mm REAL,"
            + " area_mean_mm2 REAL, resid_cv REAL, diameter_mean_mm REAL, diameter_cv REAL,"
            + " diameter_p95_mm REAL, phase_json TEXT, drift REAL, landing_mean_mm REAL, landing_sd_mm REAL,"
            + " dots_json TEXT, pass INTEGER, why TEXT, coupon_place TEXT,"
            + " coupon_record_id INTEGER, full_mg REAL, empty_mg REAL, total_mg REAL, mg_per_dot REAL,"
            + " mg_per_step REAL, mg_se REAL, weigh_delay_s INTEGER, confirmed_by INTEGER,"
            + " pressure_psi REAL NOT NULL, dose_fp TEXT NOT NULL, tip_key TEXT NOT NULL,"
            + " paste_key TEXT NOT NULL, regulator_psi REAL, regulator_at_ms INTEGER, paste_life_at_ms INTEGER,"
            + " controller_connected_at_ms INTEGER, start_angle_set_at_ms INTEGER, start_angle_deg REAL,"
            + " method TEXT NOT NULL, simulated INTEGER NOT NULL DEFAULT 0, run_uuid TEXT, job_id TEXT,"
            + " notes TEXT, operator TEXT)",
        "CREATE TABLE IF NOT EXISTS pad_calibrations ("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, job_id TEXT NOT NULL,"
            + " type_key TEXT NOT NULL, version_from INTEGER, version_to INTEGER, outcome TEXT NOT NULL,"
            + " steps INTEGER, dots_per_pad INTEGER, mass_per_pad_mg REAL, checks_json TEXT NOT NULL,"
            + " evidence_json TEXT, runs_json TEXT, simulated INTEGER NOT NULL DEFAULT 0, message TEXT,"
            + " operator TEXT)",
        "CREATE TABLE IF NOT EXISTS package_options ("
            + " row_id INTEGER PRIMARY KEY AUTOINCREMENT, package_id TEXT NOT NULL, version INTEGER NOT NULL,"
            + " paste_partial INTEGER NOT NULL CHECK (paste_partial IN (0, 1)), created_at INTEGER NOT NULL,"
            + " UNIQUE(package_id, version))",
    };
    static final List<String> V2_TABLES = Arrays.asList("schema_info", "pad_types", "pad_rules", "package_pad_type",
            "calibration", "dot_ladder", "pad_plan", "runs", "dot_log", "events", "needle_touchoff", "scale_entries",
            "dose_records", "pad_calibrations", "package_options");
    static final List<String> NEW_TABLES = Arrays.asList("tip_setups", "tip_mounts", "photos", "photo_links",
            "photo_events", "cal_labels", "cal_flags");

    static String dose(String job, String tip, int steps, long at) {
        return "INSERT INTO dose_records(created_at, kind, steps, n, pressure_psi, dose_fp, tip_key, paste_key, method,"
                + " job_id, pass, diameter_mean_mm, diameter_p95_mm) VALUES (" + at + ", 'optical', " + steps
                + ", 28, 18, 'fp', '" + tip + "', 'gc50-t5', 'auto', '" + job + "', 1, 0.9, 1.0)";
    }

    /** A config dir whose dispense.db is a schema-2 file (WAL) with rows of two tips. */
    static File schemaTwoDir() throws Exception {
        File dir = Files.createTempDirectory("dispense-v2").toFile();
        File f = DispenseDb.fileFor(dir);
        f.getParentFile().mkdirs();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
                Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            for (String ddl : DispenseDbMigrationTest.V1_DDL) {
                st.execute(ddl);
            }
            for (String ddl : V2_DDL) {
                st.execute(ddl);
            }
            for (String t : V2_TABLES) {
                st.execute("CREATE TRIGGER IF NOT EXISTS " + t + "_no_update BEFORE UPDATE ON " + t
                        + " BEGIN SELECT RAISE(ABORT, '" + t + " is append-only'); END");
                st.execute("CREATE TRIGGER IF NOT EXISTS " + t + "_no_delete BEFORE DELETE ON " + t
                        + " BEGIN SELECT RAISE(ABORT, '" + t + " is append-only'); END");
            }
            st.execute("INSERT INTO schema_info(version, created_at) VALUES (2, 1000)");
            st.execute("INSERT INTO pad_types(type_key, version, family, name, pad_w_mm, pad_h_mm, layout, pitch_mm,"
                    + " status, package_pattern, created_at, operator, origin) VALUES ('chip-0603', 1, 'Chip (2-terminal"
                    + " SMD)', '0603', 0.80, 0.95, 'two-terminal', 1.65, 'not-started', '0603', 1000, 'seed', 'seed')");
            st.execute("INSERT INTO pad_types(type_key, version, family, name, pad_w_mm, pad_h_mm, layout, pitch_mm,"
                    + " status, package_pattern, created_at, operator, dot_steps, dots_per_pad, tip_key, dose_fp,"
                    + " origin) VALUES ('chip-0603', 2, 'Chip (2-terminal SMD)', '0603', 0.80, 0.95, 'two-terminal',"
                    + " 1.65, 'characterized', '0603', 2000, 'job', 800, 1, 'ad22tta-b', 'fp', 'auto-calibrated')");
            st.execute("INSERT INTO pad_types(type_key, version, family, name, pad_w_mm, pad_h_mm, layout, pitch_mm,"
                    + " status, created_at, dot_steps, dots_per_pad, tip_key, origin) VALUES ('sot-23', 1, 'Small-outline"
                    + " discretes', 'SOT-23', 1.325, 0.60, 'sot23-3', 2.275, 'provisional', 1500, 400, 2, 'x25',"
                    + " 'manual')");
            st.execute(dose("job-1", "ad22tta-b", 800, 2500));
            st.execute(dose("job-1", "ad22tta-b", 400, 2600));
            st.execute(dose("job-2", "x25", 400, 3500));
            st.execute("INSERT INTO pad_calibrations(created_at, job_id, type_key, version_from, version_to, outcome,"
                    + " steps, dots_per_pad, checks_json) VALUES (2700, 'job-1', 'chip-0603', 1, 2, 'characterized', 800,"
                    + " 1, '[]')");
            st.execute("INSERT INTO pad_calibrations(created_at, job_id, type_key, version_from, outcome, checks_json)"
                    + " VALUES (3700, 'job-2', 'sot-23', 1, 'failed', '[]')");
            st.execute("INSERT INTO runs(run_uuid, kind, started_at, status, message) VALUES ('run-v2', 'board', 3000,"
                    + " 'OK', 'board 3')");
            st.execute("INSERT INTO events(ts, level, code, message) VALUES (3000, 'INFO', 'X', 'v2 event')");
        }
        return dir;
    }

    /** Every row of a table on the given columns, in rowid order. */
    static List<Map<String, Object>> rows(Connection c, String table, List<String> cols) throws SQLException {
        List<Map<String, Object>> l = new ArrayList<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT " + String.join(", ", cols) + " FROM " + table
                        + " ORDER BY rowid")) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    m.put(md.getColumnLabel(i), rs.getObject(i));
                }
                l.add(m);
            }
        }
        return l;
    }

    static List<String> columns(Connection c, String table) throws SQLException {
        List<String> l = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                l.add(rs.getString("name"));
            }
        }
        return l;
    }

    static File[] backups(File dir) {
        File[] b = DispenseDb.fileFor(dir).getParentFile()
                .listFiles((d, n) -> n.matches("dispense\\.db\\.v2-\\d{8}-\\d{6}(-\\d+)?\\.bak"));
        return b == null ? new File[0] : b;
    }

    @Test
    public void aSchemaTwoFileIsBackedUpAndMigratedWithoutRewritingARow() throws Exception {
        File dir = schemaTwoDir();
        File f = DispenseDb.fileFor(dir);
        Map<String, List<String>> v2Columns = new HashMap<>();
        Map<String, List<Map<String, Object>>> before = new HashMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath())) {
            for (String t : V2_TABLES) {
                v2Columns.put(t, columns(c, t));
                before.put(t, rows(c, t, v2Columns.get(t)));
            }
        }
        try (DispenseDb db = DispenseDb.open(dir)) {
            assertEquals(Arrays.asList(2, 3), DispenseDbMigrationTest.schemaRows(db), "schema_info: a row 3 on upgrade");
            List<Map<String, Object>> ev = db.query("SELECT message FROM events WHERE code = 'SCHEMA'");
            assertEquals(1, ev.size());
            assertTrue(((String) ev.get(0).get("message")).startsWith("dispense.db schema 2 -> 3; the old file is kept "
                    + "as dispense.db.v2-"), ev.toString());
            for (String t : NEW_TABLES) {
                assertFalse(db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", t).isEmpty(), t);
                assertEquals(2, db.query("SELECT name FROM sqlite_master WHERE type = 'trigger' AND tbl_name = ?", t)
                        .size(), t + ": its UPDATE and DELETE triggers");
            }
            assertFalse(db.query("SELECT name FROM sqlite_master WHERE type = 'view' AND name = 'pad_types_by_setup'")
                    .isEmpty());
            assertTrue(DispenseDbMigrationTest.columns(db, "pad_calibrations").containsAll(Arrays.asList("setup_key",
                    "method", "needle_since_ms")));
            assertTrue(DispenseDbMigrationTest.columns(db, "dose_records").containsAll(Arrays.asList("landing_dx_mm",
                    "landing_dy_mm")));
            assertTrue(DispenseDbMigrationTest.columns(db, "runs").contains("setup_key"));
            // the seeded setup and a placeholder for x25 (it cannot be mounted until completed)
            TipSetup seed = db.currentTipSetup(Tip.SEED_KEY);
            assertNotNull(seed);
            assertEquals(Tip.SEED_NAME, seed.name);
            assertEquals("ADL AD22TTA-B", seed.tipModel);
            assertEquals(TipSetup.MODEL_BENCH, seed.model);
            assertEquals(TipSetup.OD_ASSUMED, seed.odSource);
            assertTrue(seed.isComplete());
            TipSetup x25 = db.currentTipSetup("x25");
            assertNotNull(x25);
            assertEquals("x25", x25.name);
            assertEquals(TipSetup.FORM_UNKNOWN, x25.form);
            assertEquals(TipSetup.TAPER_UNKNOWN, x25.taper);
            assertNull(x25.idMm);
            assertFalse(x25.isComplete());
            assertEquals(TipSetup.PLACEHOLDER_NOTES, x25.notes);
            assertEquals(2, db.currentTipSetups(true).size());
            // the old records resolve to their setups: the failed x25 row through its job's dose records
            Map<String, String> setups = new HashMap<>();
            for (PadCalibrationRecord r : db.padCalibrations(null, null, 10)) {
                setups.put(r.jobId, r.setupKey);
                assertEquals(PadCalibrationRecord.METHOD_AUTO, r.method, "null = auto before schema 3");
                assertNull(r.needleSinceMs);
            }
            assertEquals("ad22tta-b", setups.get("job-1"));
            assertEquals("x25", setups.get("job-2"));
            assertEquals(1, db.padCalibrations(null, "x25", 10).size());
            assertEquals("sot-23", db.padCalibrations(null, "x25", 10).get(0).typeKey);
            // the composed views: null and ad22tta-b are the seed's; sot-23 is a stand-in for the 22G
            PadType chip = db.currentPadType("chip-0603", Tip.SEED_KEY);
            assertEquals(2, chip.version);
            assertFalse(chip.standIn);
            PadType sot = db.currentPadType("sot-23", Tip.SEED_KEY);
            assertTrue(sot.standIn);
            assertEquals(0, sot.version);
            assertEquals(1, (int) db.currentPadType("sot-23", "x25").version);
            // append-only, the new tables too
            assertThrows(SQLException.class, () -> db.executeRaw("UPDATE tip_setups SET name = 'x'"));
            assertThrows(SQLException.class, () -> db.executeRaw("DELETE FROM tip_setups"));
        }
        // every row that existed is the same, on the schema-2 column list
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath())) {
            for (String t : V2_TABLES) {
                List<Map<String, Object>> after = rows(c, t, v2Columns.get(t));
                assertTrue(after.size() >= before.get(t).size(), t);
                assertEquals(before.get(t), after.subList(0, before.get(t).size()), t);
            }
        }
        assertEquals(1, backups(dir).length, "the backup of the schema-2 file");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backups(dir)[0].getAbsolutePath());
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE name = 'tip_setups'")) {
            assertFalse(rs.next(), "no schema-3 table in the backup");
        }
        // a second open changes nothing
        try (DispenseDb db = DispenseDb.open(dir)) {
            assertEquals(Arrays.asList(2, 3), DispenseDbMigrationTest.schemaRows(db));
            assertEquals(2, db.currentTipSetups(true).size());
            assertEquals(1, db.query("SELECT * FROM events WHERE code = 'SCHEMA'").size());
            assertEquals(2, db.query("SELECT * FROM tip_setups").size());
        }
        assertEquals(1, backups(dir).length);
    }

    @Test
    public void aFreshDatabaseStartsAtThreeWithTheSeeds() throws Exception {
        File dir = Files.createTempDirectory("dispense-v3").toFile();
        try (DispenseDb db = DispenseDb.open(dir)) {
            assertEquals(Arrays.asList(3), DispenseDbMigrationTest.schemaRows(db), "seedIfEmpty writes it, to3 does not");
            assertEquals(12, db.currentPadTypes(Tip.SEED_KEY).size(), "the seed pad types");
            assertEquals(1, db.currentTipSetups(true).size());
            assertEquals(Tip.SEED_NAME, db.currentTipSetup(Tip.SEED_KEY).name);
            assertTrue(db.query("SELECT * FROM events WHERE code = 'SCHEMA'").isEmpty());
        }
        assertEquals(0, backups(dir).length);
    }
}
