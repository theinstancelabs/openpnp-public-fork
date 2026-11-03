package org.openpnp.instancelabs.dispense;

import java.io.File;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * SQLite store at {@code <configDir>/instancelabs/dispense.db}. Every table is append-only (enforced
 * with BEFORE UPDATE/DELETE triggers): edits append a new version, nothing is overwritten (plan
 * §5.6, §5.7).
 *
 * <p>Tables: pad_types (versioned), pad_rules (versioned), package_pad_type (versioned mapping
 * overrides, whole package or some of its pads), calibration, dot_ladder, pad_plan, runs, dot_log,
 * events, needle_touchoff, scale_entries, dose_records and pad_calibrations (profile calibration
 * design §5), package_options (main-thread decision 4).</p>
 *
 * <p>Schema 2 adds nullable columns and the three tables; a schema-1 file is copied to
 * {@code dispense.db.v1-<time>.bak} and migrated in one transaction when it is opened
 * ({@link #migrate()}).</p>
 */
public class DispenseDb implements AutoCloseable {
    public static final String FILE_NAME = "dispense.db";
    public static final int SCHEMA_VERSION = 2;

    static final String[] TABLES = {"schema_info", "pad_types", "pad_rules", "package_pad_type",
            "calibration", "dot_ladder", "pad_plan", "runs", "dot_log", "events", "needle_touchoff",
            "scale_entries", "dose_records", "pad_calibrations", "package_options"};
    /** The tables schema 2 added (created by {@link #migrate()}, as a fresh database's). */
    private static final Set<String> V2_TABLES = new HashSet<>(Arrays.asList("dose_records", "pad_calibrations",
            "package_options"));
    /** Columns schema 2 added to schema-1 tables: {table, column, type}, all nullable (old rows read null). */
    private static final String[][] V2_COLUMNS = {
        {"pad_types", "dot_steps", "INTEGER"}, {"pad_types", "dots_per_pad", "INTEGER"},
        {"pad_types", "tip_key", "TEXT"}, {"pad_types", "dose_fp", "TEXT"}, {"pad_types", "cal_area_mm2", "REAL"},
        {"pad_types", "mass_per_pad_mg", "REAL"}, {"pad_types", "origin", "TEXT"},
        {"package_pad_type", "pad_names", "TEXT"}, {"runs", "skipped_pads", "TEXT"}};
    private static final String PAD_TYPES_CURRENT = "CREATE VIEW IF NOT EXISTS pad_types_current AS SELECT p.*"
            + " FROM pad_types p JOIN (SELECT type_key, MAX(version) AS v FROM pad_types GROUP BY type_key) m"
            + " ON p.type_key = m.type_key AND p.version = m.v";
    /** The newest row of every (package, pad names) mapping key. */
    private static final String CURRENT_MAPPINGS = "SELECT p.package_id, p.pad_names, p.type_key"
            + " FROM package_pad_type p JOIN (SELECT package_id, IFNULL(pad_names, '') AS n, MAX(version) AS v"
            + " FROM package_pad_type GROUP BY package_id, IFNULL(pad_names, '')) m"
            + " ON p.package_id = m.package_id AND IFNULL(p.pad_names, '') = m.n AND p.version = m.v";
    private static final String DOSE_RECORD_COLUMNS = "created_at, kind, steps, n, found, misses, hops, threads,"
            + " hop_mm, area_mean_mm2, resid_cv, diameter_mean_mm, diameter_cv, diameter_p95_mm, phase_json, drift,"
            + " landing_mean_mm, landing_sd_mm, dots_json, pass, why, coupon_place, coupon_record_id, full_mg,"
            + " empty_mg, total_mg, mg_per_dot, mg_per_step, mg_se, weigh_delay_s, confirmed_by, pressure_psi,"
            + " dose_fp, tip_key, paste_key, regulator_psi, regulator_at_ms, paste_life_at_ms,"
            + " controller_connected_at_ms, start_angle_set_at_ms, start_angle_deg, method, simulated, run_uuid,"
            + " job_id, notes, operator";

    private final Connection conn;
    private final String url;
    /** The database file; null in memory. */
    private final File file;

    public static File fileFor(File configDir) {
        return new File(new File(configDir, DispenseConfig.SUBDIR), FILE_NAME);
    }

    public static DispenseDb open(File configDir) throws SQLException {
        File f = fileFor(configDir);
        f.getParentFile().mkdirs();
        return new DispenseDb("jdbc:sqlite:" + f.getAbsolutePath());
    }

    /** For tests: an in-memory database. */
    public static DispenseDb inMemory() throws SQLException {
        return new DispenseDb("jdbc:sqlite::memory:");
    }

    public DispenseDb(String url) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        }
        catch (ClassNotFoundException e) {
            throw new SQLException("sqlite-jdbc not on classpath", e);
        }
        this.url = url;
        this.file = url.startsWith("jdbc:sqlite:") && !url.contains(":memory:")
                ? new File(url.substring("jdbc:sqlite:".length())) : null;
        this.conn = DriverManager.getConnection(url);
        try {
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
                if (!url.contains(":memory:")) {
                    st.execute("PRAGMA journal_mode=WAL");
                }
            }
            createSchema();
            migrate();
            seedIfEmpty();
        }
        catch (SQLException | RuntimeException e) {
            conn.close(); // a failed open (e.g. no room for the backup) leaves nothing open
            throw e;
        }
    }

    public String getUrl() {
        return url;
    }

    @Override
    public synchronized void close() throws SQLException {
        conn.close();
    }

    // ------------------------------------------------------------------------------------------
    // Schema
    // ------------------------------------------------------------------------------------------

    /** The schema-1 tables, view and triggers (schema 2 is {@link #migrate()} on top of them). */
    private void createSchema() throws SQLException {
        String[] ddl = {
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
            PAD_TYPES_CURRENT,
        };
        synchronized (this) {
            try (Statement st = conn.createStatement()) {
                for (String s : ddl) {
                    st.execute(s);
                }
                for (String t : TABLES) {
                    if (!V2_TABLES.contains(t)) {
                        appendOnly(st, t);
                    }
                }
            }
        }
    }

    private static void appendOnly(Statement st, String t) throws SQLException {
        st.execute("CREATE TRIGGER IF NOT EXISTS " + t + "_no_update BEFORE UPDATE ON " + t
                + " BEGIN SELECT RAISE(ABORT, '" + t + " is append-only'); END");
        st.execute("CREATE TRIGGER IF NOT EXISTS " + t + "_no_delete BEFORE DELETE ON " + t
                + " BEGIN SELECT RAISE(ABORT, '" + t + " is append-only'); END");
    }

    /**
     * Schema 1 -> 2 (profile calibration design §5.4), also the second half of creating a new
     * database: a no-op at version 2. A schema-1 file is first copied next to itself (the database is
     * the audit record); then, in one transaction, the new nullable columns (ALTER TABLE fires no
     * append-only trigger), the pad_types_current view again (it lists the columns), the new
     * tables with their triggers, and a schema_info row (a new row: the table is append-only). A
     * failure rolls back and the next open tries again.
     */
    private synchronized void migrate() throws SQLException {
        Integer v = schemaVersion();
        if (v != null && v >= SCHEMA_VERSION) {
            return;
        }
        File backup = v != null && file != null ? backup(v) : null;
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.execute("DROP VIEW IF EXISTS pad_types_current");
            for (String[] c : V2_COLUMNS) {
                if (!columns(c[0]).contains(c[1])) {
                    st.execute("ALTER TABLE " + c[0] + " ADD COLUMN " + c[1] + " " + c[2]);
                }
            }
            st.execute(PAD_TYPES_CURRENT);
            st.execute("CREATE TABLE IF NOT EXISTS dose_records ("
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
                    + " notes TEXT, operator TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS pad_calibrations ("
                    + " id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, job_id TEXT NOT NULL,"
                    + " type_key TEXT NOT NULL, version_from INTEGER, version_to INTEGER, outcome TEXT NOT NULL,"
                    + " steps INTEGER, dots_per_pad INTEGER, mass_per_pad_mg REAL, checks_json TEXT NOT NULL,"
                    + " evidence_json TEXT, runs_json TEXT, simulated INTEGER NOT NULL DEFAULT 0, message TEXT,"
                    + " operator TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS package_options ("
                    + " row_id INTEGER PRIMARY KEY AUTOINCREMENT, package_id TEXT NOT NULL, version INTEGER NOT NULL,"
                    + " paste_partial INTEGER NOT NULL CHECK (paste_partial IN (0, 1)), created_at INTEGER NOT NULL,"
                    + " UNIQUE(package_id, version))");
            for (String t : V2_TABLES) {
                appendOnly(st, t);
            }
            if (v != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO schema_info(version, created_at) VALUES (?, ?)")) {
                    ps.setInt(1, SCHEMA_VERSION);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO events(ts, run_uuid, level, code, message) VALUES (?,?,?,?,?)")) {
                    ps.setLong(1, System.currentTimeMillis());
                    ps.setString(2, null);
                    ps.setString(3, "INFO");
                    ps.setString(4, "SCHEMA");
                    ps.setString(5, "dispense.db schema " + v + " -> " + SCHEMA_VERSION
                            + (backup == null ? "" : "; the old file is kept as " + backup.getName()));
                    ps.executeUpdate();
                }
            }
            conn.commit();
        }
        catch (SQLException e) {
            conn.rollback();
            throw e;
        }
        finally {
            conn.setAutoCommit(auto);
        }
        if (v != null) {
            org.pmw.tinylog.Logger.info("dispense.db migrated from schema {} to {}{}", v, SCHEMA_VERSION,
                    backup == null ? "" : "; backup " + backup.getAbsolutePath());
        }
    }

    /** Highest schema_info version, null for a new database. */
    synchronized Integer schemaVersion() throws SQLException {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_info")) {
            return rs.next() && rs.getObject(1) != null ? rs.getInt(1) : null;
        }
    }

    private Set<String> columns(String table) throws SQLException {
        Set<String> s = new LinkedHashSet<>();
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                s.add(rs.getString("name"));
            }
        }
        return s;
    }

    /**
     * A consistent copy of the file as it is (VACUUM INTO also takes what is still in the WAL):
     * {@code dispense.db.v<version>-<yyyyMMdd-HHmmss>.bak} next to it.
     */
    private File backup(int version) throws SQLException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String base = file.getName() + ".v" + version + "-" + stamp;
        File b = new File(file.getParentFile(), base + ".bak");
        for (int i = 2; b.exists(); i++) {
            b = new File(file.getParentFile(), base + "-" + i + ".bak");
        }
        try (Statement st = conn.createStatement()) {
            st.execute("VACUUM INTO '" + b.getAbsolutePath().replace("'", "''") + "'");
        }
        return b;
    }

    private synchronized void seedIfEmpty() throws SQLException {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM schema_info")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO schema_info(version, created_at) VALUES (?, ?)")) {
                ps.setInt(1, SCHEMA_VERSION);
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            }
            for (PadType p : seedPadTypes()) {
                p.operator = "seed";
                insertPadTypeVersion(p);
            }
            Calibration c = new Calibration();
            c.method = "provisional";
            c.status = "provisional";
            c.qMm3PerRev = 0.47;
            c.densityMgMm3 = 3.7;
            c.operator = "seed";
            c.cartridge = "VIEWEG DFS16-A";
            c.tip = "ADL AD22TTA-B (22G, 0.41 mm ID)";
            c.notes = "Provisional: ~0.0028 cm3/s at 361 rpm read off DS:DFS Fig. 3 (140,000 mPa.s fluid). "
                    + "Plan §5.6. Replace with a scale calibration (§5.2).";
            insertCalibration(c);
            conn.commit();
        }
        catch (SQLException e) {
            conn.rollback();
            throw e;
        }
        finally {
            conn.setAutoCommit(auto);
        }
    }

    /**
     * Seed pad types (plan §5.7). Pad sizes are KiCad IPC-7351 nominal land patterns quoted from
     * memory (UNVERIFIED); the board's own footprint pads win whenever packages.xml has them. All
     * seeds start as not-started.
     */
    public static List<PadType> seedPadTypes() {
        String kicad = "Seed: KiCad nominal land pattern from memory (UNVERIFIED); footprint pads win.";
        List<PadType> l = new ArrayList<>();
        l.add(new PadType("chip-0402", "Chip (2-terminal SMD)", "0402", 0.54, 0.64,
                PadType.Layout.TWO_TERMINAL, 1.02, 1.0, "(^|[^0-9])0402([^0-9]|$)|1005Metric", kicad));
        l.add(new PadType("chip-0603", "Chip (2-terminal SMD)", "0603", 0.80, 0.95,
                PadType.Layout.TWO_TERMINAL, 1.65, 1.0, "(^|[^0-9])0603([^0-9]|$)|1608Metric",
                kicad + " Plan §5.6 first target (R_0603_1608Metric 0.80 x 0.95)."));
        l.add(new PadType("chip-0805", "Chip (2-terminal SMD)", "0805", 1.025, 1.40,
                PadType.Layout.TWO_TERMINAL, 1.825, 1.0, "(^|[^0-9])0805([^0-9]|$)|2012Metric", kicad));
        l.add(new PadType("chip-1206", "Chip (2-terminal SMD)", "1206", 1.125, 1.75,
                PadType.Layout.TWO_TERMINAL, 2.925, 1.0, "(^|[^0-9])1206([^0-9]|$)|3216Metric", kicad));
        l.add(new PadType("sod-123", "Small-outline discretes", "SOD-123", 0.90, 1.20,
                PadType.Layout.TWO_TERMINAL, 3.30, 1.0, "SOD-?123", kicad));
        l.add(new PadType("sot-23", "Small-outline discretes", "SOT-23", 1.325, 0.60,
                PadType.Layout.SOT23_3, 2.275, 1.0, "SOT-?23(-3)?($|[^0-9-])", kicad));
        l.add(new PadType("soic", "Leaded ICs", "SOIC (1.27 mm pitch)", 1.95, 0.60,
                PadType.Layout.FOOTPRINT_ONLY, null, 1.0, "SOIC", kicad));
        l.add(new PadType("tssop", "Leaded ICs", "TSSOP (0.65 mm pitch)", 1.475, 0.40,
                PadType.Layout.FOOTPRINT_ONLY, null, 1.0, "TSSOP|MSOP", kicad));
        l.add(new PadType("qfp", "Leaded ICs", "QFP (0.5 mm pitch)", 1.50, 0.30,
                PadType.Layout.FOOTPRINT_ONLY, null, 1.0, "QFP", kicad));
        l.add(new PadType("qfn-dfn-perimeter", "No-lead ICs", "QFN / DFN perimeter pad", 0.80, 0.25,
                PadType.Layout.FOOTPRINT_ONLY, null, 1.0, "QFN|DFN", kicad));
        l.add(new PadType("qfn-centre", "No-lead ICs", "QFN centre pad", 3.10, 3.10,
                PadType.Layout.SINGLE, null, 0.5, null,
                "k < 1 to avoid floating the part (plan §5.7); 0.5 is a placeholder for you to set. "
                        + "Assigned to the large pad at the origin of QFN/DFN footprints."));
        l.add(new PadType("custom", "Custom", "Custom", 1.0, 1.0, PadType.Layout.SINGLE, null, 1.0,
                null, "Template for user-added types."));
        for (PadType p : l) {
            p.origin = PadType.ORIGIN_SEED;
        }
        return l;
    }

    // ------------------------------------------------------------------------------------------
    // Pad types
    // ------------------------------------------------------------------------------------------

    /** Appends a new version of a pad type (version = previous max + 1). Returns the stored row. */
    public synchronized PadType insertPadTypeVersion(PadType p) throws SQLException {
        if (p.typeKey == null || p.typeKey.trim().isEmpty()) {
            throw new IllegalArgumentException("typeKey required");
        }
        if (p.padWMm <= 0 || p.padHMm <= 0 || p.tMm <= 0 || p.k <= 0) {
            throw new IllegalArgumentException("pad size, t and k must be > 0");
        }
        if ((p.layout == PadType.Layout.TWO_TERMINAL || p.layout == PadType.Layout.SOT23_3)
                && (p.pitchMm == null || p.pitchMm <= 0)) {
            throw new IllegalArgumentException("pitchMm required for layout " + p.layout.dbName);
        }
        String dose = p.doseProblem();
        if (dose != null) {
            throw new IllegalArgumentException(dose);
        }
        int version = 1;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT MAX(version) FROM pad_types WHERE type_key = ?")) {
            ps.setString(1, p.typeKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getObject(1) != null) {
                    version = rs.getInt(1) + 1;
                }
            }
        }
        PadType stored = p.copy();
        stored.version = version;
        stored.createdAtMs = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO pad_types(type_key, version, family,"
                + " name, pad_w_mm, pad_h_mm, pad_roundness, layout, pitch_mm, t_mm, k, status,"
                + " package_pattern, notes, created_at, operator, dot_steps, dots_per_pad, tip_key, dose_fp,"
                + " cal_area_mm2, mass_per_pad_mg, origin) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            ps.setString(i++, stored.typeKey);
            ps.setInt(i++, stored.version);
            ps.setString(i++, stored.family);
            ps.setString(i++, stored.name);
            ps.setDouble(i++, stored.padWMm);
            ps.setDouble(i++, stored.padHMm);
            ps.setDouble(i++, stored.padRoundness);
            ps.setString(i++, stored.layout.dbName);
            setNullable(ps, i++, stored.pitchMm);
            ps.setDouble(i++, stored.tMm);
            ps.setDouble(i++, stored.k);
            ps.setString(i++, stored.status.dbName);
            ps.setString(i++, stored.packagePattern);
            ps.setString(i++, stored.notes);
            ps.setLong(i++, stored.createdAtMs);
            ps.setString(i++, stored.operator);
            setNullableLong(ps, i++, longOf(stored.dotSteps));
            setNullableLong(ps, i++, longOf(stored.dotsPerPad));
            ps.setString(i++, stored.tipKey);
            ps.setString(i++, stored.doseFp);
            setNullable(ps, i++, stored.calAreaMm2);
            setNullable(ps, i++, stored.massPerPadMg);
            ps.setString(i++, stored.origin);
            ps.executeUpdate();
            stored.rowId = generatedKey(ps);
        }
        return stored;
    }

    /** Current (highest version) definition of every pad type, in creation order. */
    public synchronized List<PadType> currentPadTypes() throws SQLException {
        List<PadType> l = new ArrayList<>();
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT c.* FROM pad_types_current c JOIN (SELECT type_key, MIN(row_id) AS first_row"
                                + " FROM pad_types GROUP BY type_key) f ON c.type_key = f.type_key"
                                + " ORDER BY f.first_row")) {
            while (rs.next()) {
                l.add(readPadType(rs));
            }
        }
        return l;
    }

    public synchronized PadType currentPadType(String typeKey) throws SQLException {
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT * FROM pad_types_current WHERE type_key = ?")) {
            ps.setString(1, typeKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readPadType(rs) : null;
            }
        }
    }

    public synchronized List<PadType> padTypeHistory(String typeKey) throws SQLException {
        List<PadType> l = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM pad_types WHERE type_key = ? ORDER BY version")) {
            ps.setString(1, typeKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    l.add(readPadType(rs));
                }
            }
        }
        return l;
    }

    private static PadType readPadType(ResultSet rs) throws SQLException {
        PadType p = new PadType();
        p.rowId = rs.getLong("row_id");
        p.typeKey = rs.getString("type_key");
        p.version = rs.getInt("version");
        p.family = rs.getString("family");
        p.name = rs.getString("name");
        p.padWMm = rs.getDouble("pad_w_mm");
        p.padHMm = rs.getDouble("pad_h_mm");
        p.padRoundness = rs.getDouble("pad_roundness");
        p.layout = PadType.Layout.fromDb(rs.getString("layout"));
        p.pitchMm = getNullableDouble(rs, "pitch_mm");
        p.tMm = rs.getDouble("t_mm");
        p.k = rs.getDouble("k");
        p.status = PadTypeStatus.fromDb(rs.getString("status"));
        p.packagePattern = rs.getString("package_pattern");
        p.notes = rs.getString("notes");
        p.createdAtMs = rs.getLong("created_at");
        p.operator = rs.getString("operator");
        p.dotSteps = getNullableInt(rs, "dot_steps");
        p.dotsPerPad = getNullableInt(rs, "dots_per_pad");
        p.tipKey = rs.getString("tip_key");
        p.doseFp = rs.getString("dose_fp");
        p.calAreaMm2 = getNullableDouble(rs, "cal_area_mm2");
        p.massPerPadMg = getNullableDouble(rs, "mass_per_pad_mg");
        p.origin = rs.getString("origin");
        return p;
    }

    /** Appends a package -> pad type mapping override (typeKey null clears the override). */
    public synchronized void mapPackage(String packageId, String typeKey) throws SQLException {
        mapPackage(packageId, null, typeKey);
    }

    /**
     * Appends a mapping override for some pads of a package (padNames null or empty: the whole
     * package; typeKey null clears it). The current mapping of a (package, pad names) key is its
     * highest version; versions count per package. A package's per-pad mappings never share a pad:
     * the pads of this one leave the older keys (each is cleared and, for its other pads, mapped
     * again), so which type a pad gets never depends on the order of the keys.
     */
    public synchronized void mapPackage(String packageId, Collection<String> padNames, String typeKey)
            throws SQLException {
        if (packageId == null || packageId.isEmpty()) {
            throw new IllegalArgumentException("packageId required");
        }
        String names = joinPadNames(padNames);
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            if (names != null) {
                Set<String> mine = new HashSet<>(splitPadNames(names));
                for (Map.Entry<String, String> e : padMappings(packageId).entrySet()) {
                    List<String> rest = splitPadNames(e.getKey());
                    if (!e.getKey().equals(names) && rest.removeAll(mine)) {
                        appendMapping(packageId, e.getKey(), null);
                        if (!rest.isEmpty()) {
                            appendMapping(packageId, joinPadNames(rest), e.getValue());
                        }
                    }
                }
            }
            appendMapping(packageId, names, typeKey);
            conn.commit();
        }
        catch (SQLException | RuntimeException e) {
            conn.rollback();
            throw e;
        }
        finally {
            conn.setAutoCommit(auto);
        }
    }

    private void appendMapping(String packageId, String names, String typeKey) throws SQLException {
        int version = 1;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT MAX(version) FROM package_pad_type WHERE package_id = ?")) {
            ps.setString(1, packageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getObject(1) != null) {
                    version = rs.getInt(1) + 1;
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO package_pad_type(package_id, pad_names,"
                + " type_key, version, created_at) VALUES (?,?,?,?,?)")) {
            ps.setString(1, packageId);
            ps.setString(2, names);
            ps.setString(3, typeKey);
            ps.setInt(4, version);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /**
     * Current mapping overrides (cleared ones omitted): key {@link #mappingKey} (the package id for a
     * whole package, {@code packageId#names} for some of its pads) -> type key, ordered by package id,
     * then by version. The per-pad keys of a package name disjoint pads ({@link #mapPackage}).
     */
    public synchronized Map<String, String> packageMappings() throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(CURRENT_MAPPINGS + " ORDER BY p.package_id, p.version")) {
            while (rs.next()) {
                if (rs.getString(3) != null) {
                    m.put(mappingKey(rs.getString(1), rs.getString(2)), rs.getString(3));
                }
            }
        }
        return m;
    }

    /** A package's current per-pad mappings: joined pad names -> type key, oldest first. */
    private Map<String, String> padMappings(String packageId) throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(CURRENT_MAPPINGS + " WHERE p.package_id = ?"
                + " AND p.pad_names IS NOT NULL AND p.type_key IS NOT NULL ORDER BY p.version")) {
            ps.setString(1, packageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    m.put(rs.getString(2), rs.getString(3));
                }
            }
        }
        return m;
    }

    /**
     * Main-thread decision 4: a part with an undispensable pad is treated whole (none of its pads
     * pasted) unless its package has this option on ("paste the dispensable pads of this part
     * anyway"). Appends the package's new setting; default off.
     */
    public synchronized void setPastePartial(String packageId, boolean on) throws SQLException {
        if (packageId == null || packageId.isEmpty()) {
            throw new IllegalArgumentException("packageId required");
        }
        int version = 1;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT MAX(version) FROM package_options WHERE package_id = ?")) {
            ps.setString(1, packageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getObject(1) != null) {
                    version = rs.getInt(1) + 1;
                }
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO package_options(package_id, version,"
                + " paste_partial, created_at) VALUES (?,?,?,?)")) {
            ps.setString(1, packageId);
            ps.setInt(2, version);
            ps.setInt(3, on ? 1 : 0);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** The packages whose current setting pastes the dispensable pads of a partly undispensable part. */
    public synchronized Set<String> pastePartialPackages() throws SQLException {
        Set<String> s = new TreeSet<>();
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT o.package_id FROM package_options o JOIN (SELECT package_id,"
                        + " MAX(version) AS v FROM package_options GROUP BY package_id) m"
                        + " ON o.package_id = m.package_id AND o.version = m.v WHERE o.paste_partial = 1")) {
            while (rs.next()) {
                s.add(rs.getString(1));
            }
        }
        return s;
    }

    /** The key of a mapping in {@link #packageMappings()}: the package id, or {@code packageId#names}. */
    public static String mappingKey(String packageId, String joinedPadNames) {
        return joinedPadNames == null ? packageId : packageId + "#" + joinedPadNames;
    }

    /**
     * Pad names as stored: distinct, in natural order ("2" before "10"), comma-joined; null for none.
     * A name may not be blank or hold ',' or '#' (the separators of the stored names and the key).
     */
    public static String joinPadNames(Collection<String> padNames) {
        if (padNames == null || padNames.isEmpty()) {
            return null;
        }
        List<String> l = new ArrayList<>(new LinkedHashSet<>(padNames));
        for (String n : l) {
            if (n == null || n.trim().isEmpty() || n.contains(",") || n.contains("#")) {
                throw new IllegalArgumentException("pad names must not be blank or contain ',' or '#': '" + n + "'");
            }
        }
        l.sort(DispenseDb::compareNatural);
        return String.join(",", l);
    }

    /** The names of {@link #joinPadNames}, or an empty list for null. */
    public static List<String> splitPadNames(String joined) {
        return joined == null || joined.isEmpty() ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(joined.split(",")));
    }

    /** Natural order: runs of digits compare as numbers ("A2" < "A10"), the rest as text. */
    static int compareNatural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                int c = na.length() != nb.length() ? Integer.compare(na.length(), nb.length()) : na.compareTo(nb);
                if (c != 0) {
                    return c;
                }
            }
            else {
                if (ca != cb) {
                    return Character.compare(ca, cb);
                }
                i++;
                j++;
            }
        }
        int c = Integer.compare(a.length() - i, b.length() - j);
        return c != 0 ? c : a.compareTo(b);
    }

    /** Appends a new version of a pad rule. */
    public synchronized PadRule insertPadRule(PadRule r) throws SQLException {
        if (r.typeKey == null) {
            throw new IllegalArgumentException("typeKey required");
        }
        int version = 1;
        try (PreparedStatement ps = conn.prepareStatement("SELECT MAX(version) FROM pad_rules WHERE type_key = ?")) {
            ps.setString(1, r.typeKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getObject(1) != null) {
                    version = rs.getInt(1) + 1;
                }
            }
        }
        r.version = version;
        r.createdAtMs = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO pad_rules(type_key, version, max_dots,"
                + " fit_factor, largest_single_dot_pad_mm2, grid_pitch_rule, created_at, notes)"
                + " VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.typeKey);
            ps.setInt(2, r.version);
            setNullable(ps, 3, r.maxDots == null ? null : r.maxDots.doubleValue());
            setNullable(ps, 4, r.fitFactor);
            setNullable(ps, 5, r.largestSingleDotPadMm2);
            ps.setString(6, r.gridPitchRule);
            ps.setLong(7, r.createdAtMs);
            ps.setString(8, r.notes);
            ps.executeUpdate();
            r.rowId = generatedKey(ps);
        }
        return r;
    }

    /** Current (highest version) rule per pad type. */
    public synchronized Map<String, PadRule> currentPadRules() throws SQLException {
        Map<String, PadRule> m = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT p.* FROM pad_rules p JOIN (SELECT type_key, MAX(version) AS v"
                        + " FROM pad_rules GROUP BY type_key) m ON p.type_key = m.type_key AND p.version = m.v")) {
            while (rs.next()) {
                PadRule r = new PadRule();
                r.rowId = rs.getLong("row_id");
                r.typeKey = rs.getString("type_key");
                r.version = rs.getInt("version");
                Double md = getNullableDouble(rs, "max_dots");
                r.maxDots = md == null ? null : md.intValue();
                r.fitFactor = getNullableDouble(rs, "fit_factor");
                r.largestSingleDotPadMm2 = getNullableDouble(rs, "largest_single_dot_pad_mm2");
                r.gridPitchRule = rs.getString("grid_pitch_rule");
                r.notes = rs.getString("notes");
                r.createdAtMs = rs.getLong("created_at");
                m.put(r.typeKey, r);
            }
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------
    // Calibration + dot ladder
    // ------------------------------------------------------------------------------------------

    public synchronized Calibration insertCalibration(Calibration c) throws SQLException {
        c.createdAtMs = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO calibration(created_at, operator, method,"
                + " status, tip, cartridge, paste_lot, set_pressure_psi, b_feed_deg_s, q_mm3_per_rev, q_cv, q_n,"
                + " density_mg_mm3, barrel_temp_c, barrel_pressure_psi, paste_hours_rt, valid_until, notes)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            ps.setLong(i++, c.createdAtMs);
            ps.setString(i++, c.operator);
            ps.setString(i++, c.method);
            ps.setString(i++, c.status);
            ps.setString(i++, c.tip);
            ps.setString(i++, c.cartridge);
            ps.setString(i++, c.pasteLot);
            setNullable(ps, i++, c.setPressurePsi);
            setNullable(ps, i++, c.bFeedDegS);
            ps.setDouble(i++, c.qMm3PerRev);
            setNullable(ps, i++, c.qCv);
            setNullable(ps, i++, c.qN == null ? null : c.qN.doubleValue());
            setNullable(ps, i++, c.densityMgMm3);
            setNullable(ps, i++, c.barrelTempC);
            setNullable(ps, i++, c.barrelPressurePsi);
            setNullable(ps, i++, c.pasteHoursRt);
            setNullable(ps, i++, c.validUntilMs == null ? null : c.validUntilMs.doubleValue());
            ps.setString(i++, c.notes);
            ps.executeUpdate();
            c.id = generatedKey(ps);
        }
        return c;
    }

    public synchronized Calibration calibration(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM calibration WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readCalibration(rs) : null;
            }
        }
    }

    public synchronized List<Calibration> calibrations() throws SQLException {
        List<Calibration> l = new ArrayList<>();
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM calibration ORDER BY id")) {
            while (rs.next()) {
                l.add(readCalibration(rs));
            }
        }
        return l;
    }

    /**
     * The calibration the planner uses: the newest characterized one that has not expired; else
     * the newest record of any status. Never null after seeding.
     */
    public synchronized Calibration currentCalibration(long nowMs) throws SQLException {
        Calibration best = null;
        for (Calibration c : calibrations()) {
            boolean expired = c.validUntilMs != null && c.validUntilMs < nowMs;
            if (c.isCharacterized() && !expired) {
                best = c;
            }
        }
        if (best != null) {
            return best;
        }
        List<Calibration> all = calibrations();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private static Calibration readCalibration(ResultSet rs) throws SQLException {
        Calibration c = new Calibration();
        c.id = rs.getLong("id");
        c.createdAtMs = rs.getLong("created_at");
        c.operator = rs.getString("operator");
        c.method = rs.getString("method");
        c.status = rs.getString("status");
        c.tip = rs.getString("tip");
        c.cartridge = rs.getString("cartridge");
        c.pasteLot = rs.getString("paste_lot");
        c.setPressurePsi = getNullableDouble(rs, "set_pressure_psi");
        c.bFeedDegS = getNullableDouble(rs, "b_feed_deg_s");
        c.qMm3PerRev = rs.getDouble("q_mm3_per_rev");
        c.qCv = getNullableDouble(rs, "q_cv");
        Double qn = getNullableDouble(rs, "q_n");
        c.qN = qn == null ? null : qn.intValue();
        c.densityMgMm3 = getNullableDouble(rs, "density_mg_mm3");
        c.barrelTempC = getNullableDouble(rs, "barrel_temp_c");
        c.barrelPressurePsi = getNullableDouble(rs, "barrel_pressure_psi");
        c.pasteHoursRt = getNullableDouble(rs, "paste_hours_rt");
        Double vu = getNullableDouble(rs, "valid_until");
        c.validUntilMs = vu == null ? null : vu.longValue();
        c.notes = rs.getString("notes");
        return c;
    }

    public synchronized DotLadderRow insertDotLadderRow(DotLadderRow r) throws SQLException {
        r.createdAtMs = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO dot_ladder(calibration_id, created_at,"
                + " rotation_deg, z_gap_mm, n, diameter_mean_mm, diameter_cv, area_mean_mm2, area_cv,"
                + " mg_per_dot_mean, mg_per_dot_se, height_mm, method, notes) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            ps.setLong(i++, r.calibrationId);
            ps.setLong(i++, r.createdAtMs);
            ps.setDouble(i++, r.rotationDeg);
            setNullable(ps, i++, r.zGapMm);
            ps.setInt(i++, r.n);
            setNullable(ps, i++, r.diameterMeanMm);
            setNullable(ps, i++, r.diameterCv);
            setNullable(ps, i++, r.areaMeanMm2);
            setNullable(ps, i++, r.areaCv);
            setNullable(ps, i++, r.mgPerDotMean);
            setNullable(ps, i++, r.mgPerDotSe);
            setNullable(ps, i++, r.heightMm);
            ps.setString(i++, r.method);
            ps.setString(i++, r.notes);
            ps.executeUpdate();
            r.id = generatedKey(ps);
        }
        return r;
    }

    /** All ladder rows of a calibration, oldest first. */
    public synchronized List<DotLadderRow> dotLadder(long calibrationId) throws SQLException {
        List<DotLadderRow> l = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM dot_ladder WHERE calibration_id = ? ORDER BY id")) {
            ps.setLong(1, calibrationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DotLadderRow r = new DotLadderRow();
                    r.id = rs.getLong("id");
                    r.calibrationId = rs.getLong("calibration_id");
                    r.createdAtMs = rs.getLong("created_at");
                    r.rotationDeg = rs.getDouble("rotation_deg");
                    r.zGapMm = getNullableDouble(rs, "z_gap_mm");
                    r.n = rs.getInt("n");
                    r.diameterMeanMm = getNullableDouble(rs, "diameter_mean_mm");
                    r.diameterCv = getNullableDouble(rs, "diameter_cv");
                    r.areaMeanMm2 = getNullableDouble(rs, "area_mean_mm2");
                    r.areaCv = getNullableDouble(rs, "area_cv");
                    r.mgPerDotMean = getNullableDouble(rs, "mg_per_dot_mean");
                    r.mgPerDotSe = getNullableDouble(rs, "mg_per_dot_se");
                    r.heightMm = getNullableDouble(rs, "height_mm");
                    r.method = rs.getString("method");
                    r.notes = rs.getString("notes");
                    l.add(r);
                }
            }
        }
        return l;
    }

    // ------------------------------------------------------------------------------------------
    // Plans, runs, events
    // ------------------------------------------------------------------------------------------

    public synchronized void insertPadPlan(DispensePlan plan) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO pad_plan(plan_id, created_at, board,"
                + " pad_id, type_key, type_version, area_mm2, target_volume_mm3, dots, rotation_per_dot_deg,"
                + " predicted_diameter_mm, predicted_height_mm, calibration_id, flags)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            long now = System.currentTimeMillis();
            for (DispensePlan.PlannedPad p : plan.pads) {
                int i = 1;
                ps.setString(i++, plan.planId);
                ps.setLong(i++, now);
                ps.setString(i++, plan.boardName);
                ps.setString(i++, p.padId);
                ps.setString(i++, p.typeKey);
                ps.setInt(i++, p.typeVersion);
                ps.setDouble(i++, p.areaMm2);
                ps.setDouble(i++, p.targetVolumeMm3);
                ps.setInt(i++, p.dotCount);
                setNullable(ps, i++, p.rotationPerDotDeg);
                setNullable(ps, i++, null); // predicted_diameter_mm, predicted_height_mm, calibration_id:
                setNullable(ps, i++, null); // the volumetric (q) dose model is gone; the profile is in events
                setNullable(ps, i++, null);
                ps.setString(i++, p.flags.isEmpty() ? null : String.join(",", p.flagNames()));
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        }
        catch (SQLException e) {
            conn.rollback();
            throw e;
        }
        finally {
            conn.setAutoCommit(auto);
        }
    }

    public synchronized void insertRun(RunResult r) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO runs(run_uuid, kind, plan_id, board,"
                    + " started_at, ended_at, status, dots_planned, dots_done, first_dot_start_ms, last_dot_end_ms,"
                    + " motion_s, total_s, dph_motion, dph_overall, calibration_id, keepalive, lines_sent, message,"
                    + " skipped_pads) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                int i = 1;
                ps.setString(i++, r.runId);
                ps.setString(i++, r.kind);
                ps.setString(i++, r.planId);
                ps.setString(i++, r.boardName);
                ps.setLong(i++, r.startedAtMs);
                ps.setLong(i++, r.endedAtMs);
                ps.setString(i++, r.status.name());
                ps.setInt(i++, r.dotsPlanned);
                ps.setInt(i++, r.dotsDone);
                setNullable(ps, i++, r.firstDotStartMs == 0 ? null : (double) r.firstDotStartMs);
                setNullable(ps, i++, r.lastDotEndMs == 0 ? null : (double) r.lastDotEndMs);
                setNullable(ps, i++, r.motionSeconds());
                ps.setDouble(i++, r.totalSeconds());
                setNullable(ps, i++, r.dphMotion());
                setNullable(ps, i++, r.dphOverall());
                setNullable(ps, i++, r.calibrationId == 0 ? null : (double) r.calibrationId);
                ps.setInt(i++, r.keepaliveActive ? 1 : 0);
                ps.setInt(i++, r.linesSent);
                ps.setString(i++, r.message);
                ps.setString(i++, r.skippedPads.isEmpty() ? null : new Gson().toJson(r.skippedPads));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO dot_log(run_uuid, dot_index, pad_id,"
                    + " rotation_deg, start_ms, end_ms) VALUES (?,?,?,?,?,?)")) {
                for (RunResult.DotTiming t : r.dotTimings) {
                    ps.setString(1, r.runId);
                    ps.setInt(2, t.dotIndex);
                    ps.setString(3, t.padId);
                    ps.setDouble(4, t.rotationDeg);
                    ps.setLong(5, t.startMs);
                    ps.setLong(6, t.endMs);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
        catch (SQLException e) {
            conn.rollback();
            throw e;
        }
        finally {
            conn.setAutoCommit(auto);
        }
    }

    /** Recent runs, newest first, as column maps (for the web API). */
    public synchronized List<Map<String, Object>> recentRuns(int limit) throws SQLException {
        return query("SELECT * FROM runs ORDER BY id DESC LIMIT " + Math.max(1, limit));
    }

    public synchronized void event(String runId, String level, String code, String message) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO events(ts, run_uuid, level, code, message) VALUES (?,?,?,?,?)")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, runId);
            ps.setString(3, level);
            ps.setString(4, code);
            ps.setString(5, message);
            ps.executeUpdate();
        }
        catch (SQLException e) {
            org.pmw.tinylog.Logger.warn(e, "dispense event not stored: {} {}", code, message);
        }
    }

    public synchronized List<Map<String, Object>> recentEvents(int limit) throws SQLException {
        return query("SELECT * FROM events ORDER BY id DESC LIMIT " + Math.max(1, limit));
    }

    public synchronized long insertTouchOff(String method, double zOffsetMm, Double previousOffsetMm,
            String details, String operator) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO needle_touchoff(created_at, method,"
                + " z_offset_mm, previous_offset_mm, details, operator) VALUES (?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, method);
            ps.setDouble(3, zOffsetMm);
            setNullable(ps, 4, previousOffsetMm);
            ps.setString(5, details);
            ps.setString(6, operator);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    public synchronized long insertScaleEntry(String kind, double rotationDeg, int count, double totalMassMg,
            String perItemMg, Double density, Long calibrationId, String notes, String operator)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO scale_entries(created_at, kind,"
                + " rotation_deg, count, total_mass_mg, per_item_mg, density_mg_mm3, calibration_id, notes,"
                + " operator) VALUES (?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, kind);
            ps.setDouble(3, rotationDeg);
            ps.setInt(4, count);
            ps.setDouble(5, totalMassMg);
            ps.setString(6, perItemMg);
            setNullable(ps, 7, density);
            setNullable(ps, 8, calibrationId == null ? null : calibrationId.doubleValue());
            ps.setString(9, notes);
            ps.setString(10, operator);
            ps.executeUpdate();
            return generatedKey(ps);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Dose records, pad calibrations (profile calibration design §5.3)
    // ------------------------------------------------------------------------------------------

    /**
     * Appends a dose record (created_at = now). A mass row names a coupon record of the same dose
     * that has no mass row yet (one coupon is one reading: two rows of one coupon would confirm each
     * other); it carries a reading pair (total = with paste - wiped, > 0) or none at all (a skipped
     * weighing, with {@code why}), and its coupon's conditions (§1.7): the readings of dots are
     * evidence for the setup the dots were laid in, and a simulator coupon never gives a machine mass.
     */
    public synchronized DoseRecord insertDoseRecord(DoseRecord r) throws SQLException {
        if (!DoseRecord.KINDS.contains(r.kind)) {
            throw new IllegalArgumentException("kind must be one of " + DoseRecord.KINDS);
        }
        if (r.steps <= 0 || r.n < 0) {
            throw new IllegalArgumentException("steps must be > 0 and n >= 0");
        }
        if (!(r.pressurePsi > 0) || blank(r.doseFp) || blank(r.tipKey) || blank(r.pasteKey)) {
            throw new IllegalArgumentException("a dose record needs its pressure, dose fingerprint, tip and paste");
        }
        if (!DoseRecord.METHODS.contains(r.method)) {
            throw new IllegalArgumentException("method must be one of " + DoseRecord.METHODS);
        }
        if ("sim".equals(r.method) && !r.simulated) {
            throw new IllegalArgumentException("a record of the simulator's balance must be marked simulated");
        }
        if (DoseRecord.COUPON.equals(r.kind) && blank(r.couponPlace)) {
            throw new IllegalArgumentException("a coupon record needs its coupon place");
        }
        if (DoseRecord.MASS.equals(r.kind)) {
            checkMassRow(r);
        }
        r.createdAtMs = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO dose_records(" + DOSE_RECORD_COLUMNS
                + ") VALUES (" + String.join(",", Collections.nCopies(DOSE_RECORD_COLUMNS.split(",").length, "?"))
                + ")",
                Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            ps.setLong(i++, r.createdAtMs);
            ps.setString(i++, r.kind);
            ps.setInt(i++, r.steps);
            ps.setInt(i++, r.n);
            setNullableLong(ps, i++, longOf(r.found));
            setNullableLong(ps, i++, longOf(r.misses));
            setNullableLong(ps, i++, longOf(r.hops));
            setNullableLong(ps, i++, longOf(r.threads));
            setNullable(ps, i++, r.hopMm);
            setNullable(ps, i++, r.areaMeanMm2);
            setNullable(ps, i++, r.residCv);
            setNullable(ps, i++, r.diameterMeanMm);
            setNullable(ps, i++, r.diameterCv);
            setNullable(ps, i++, r.diameterP95Mm);
            ps.setString(i++, r.phaseJson);
            setNullable(ps, i++, r.drift);
            setNullable(ps, i++, r.landingMeanMm);
            setNullable(ps, i++, r.landingSdMm);
            ps.setString(i++, r.dotsJson);
            setNullableLong(ps, i++, r.pass == null ? null : r.pass ? 1L : 0L);
            ps.setString(i++, r.why);
            ps.setString(i++, r.couponPlace);
            setNullableLong(ps, i++, r.couponRecordId);
            setNullable(ps, i++, r.fullMg);
            setNullable(ps, i++, r.emptyMg);
            setNullable(ps, i++, r.totalMg);
            setNullable(ps, i++, r.mgPerDot);
            setNullable(ps, i++, r.mgPerStep);
            setNullable(ps, i++, r.mgSe);
            setNullableLong(ps, i++, longOf(r.weighDelayS));
            setNullableLong(ps, i++, r.confirmedBy);
            ps.setDouble(i++, r.pressurePsi);
            ps.setString(i++, r.doseFp);
            ps.setString(i++, r.tipKey);
            ps.setString(i++, r.pasteKey);
            setNullable(ps, i++, r.regulatorPsi);
            setNullableLong(ps, i++, r.regulatorAtMs);
            setNullableLong(ps, i++, r.pasteLifeAtMs);
            setNullableLong(ps, i++, r.controllerConnectedAtMs);
            setNullableLong(ps, i++, r.startAngleSetAtMs);
            setNullable(ps, i++, r.startAngleDeg);
            ps.setString(i++, r.method);
            ps.setInt(i++, r.simulated ? 1 : 0);
            ps.setString(i++, r.runUuid);
            ps.setString(i++, r.jobId);
            ps.setString(i++, r.notes);
            ps.setString(i++, r.operator);
            ps.executeUpdate();
            r.id = generatedKey(ps);
        }
        return r;
    }

    private void checkMassRow(DoseRecord r) throws SQLException {
        DoseRecord coupon = r.couponRecordId == null ? null : doseRecord(r.couponRecordId);
        if (coupon == null || !DoseRecord.COUPON.equals(coupon.kind)) {
            throw new IllegalArgumentException("a mass record must name a coupon record");
        }
        if (coupon.steps != r.steps) {
            throw new IllegalArgumentException("coupon record " + coupon.id + " has " + coupon.steps
                    + "-step dots, not " + r.steps);
        }
        if (coupon.simulated != r.simulated || !coupon.doseFp.equals(r.doseFp) || !coupon.tipKey.equals(r.tipKey)
                || !coupon.pasteKey.equals(r.pasteKey) || !Objects.equals(coupon.regulatorPsi, r.regulatorPsi)) {
            throw new IllegalArgumentException("a mass record carries the conditions of coupon record " + coupon.id
                    + " (simulator flag, recipe, tip, paste, regulator reading)");
        }
        if (!query("SELECT id FROM dose_records WHERE kind = 'mass' AND coupon_record_id = ?", coupon.id).isEmpty()) {
            throw new IllegalArgumentException("coupon record " + coupon.id + " already has its reading");
        }
        boolean none = r.fullMg == null && r.emptyMg == null && r.totalMg == null;
        if (none ? blank(r.why) : r.fullMg == null || r.emptyMg == null || r.totalMg == null
                || Math.abs(r.totalMg - (r.fullMg - r.emptyMg)) > 1e-6 || !(r.totalMg > 0)) {
            throw new IllegalArgumentException("a mass record needs both readings with total = with paste - wiped > 0, "
                    + "or no readings and why the weighing was skipped");
        }
    }

    public synchronized DoseRecord doseRecord(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM dose_records WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readDoseRecord(rs) : null;
            }
        }
    }

    /** Dose records made at or after sinceMs, of one dose (steps null: every dose), oldest first. */
    public synchronized List<DoseRecord> doseRecords(Integer steps, long sinceMs) throws SQLException {
        String sql = "SELECT * FROM dose_records WHERE created_at >= ?" + (steps == null ? "" : " AND steps = ?")
                + " ORDER BY id";
        List<DoseRecord> l = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sinceMs);
            if (steps != null) {
                ps.setInt(2, steps);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    l.add(readDoseRecord(rs));
                }
            }
        }
        return l;
    }

    /** Coupon records made at or after sinceMs that no mass record names yet (dots waiting to be weighed). */
    public synchronized List<DoseRecord> pendingCoupons(long sinceMs) throws SQLException {
        List<DoseRecord> l = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM dose_records c WHERE c.kind = 'coupon'"
                + " AND c.created_at >= ? AND NOT EXISTS (SELECT 1 FROM dose_records m WHERE m.kind = 'mass'"
                + " AND m.coupon_record_id = c.id) ORDER BY c.id")) {
            ps.setLong(1, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    l.add(readDoseRecord(rs));
                }
            }
        }
        return l;
    }

    private static DoseRecord readDoseRecord(ResultSet rs) throws SQLException {
        DoseRecord r = new DoseRecord();
        r.id = rs.getLong("id");
        r.createdAtMs = rs.getLong("created_at");
        r.kind = rs.getString("kind");
        r.steps = rs.getInt("steps");
        r.n = rs.getInt("n");
        r.found = getNullableInt(rs, "found");
        r.misses = getNullableInt(rs, "misses");
        r.hops = getNullableInt(rs, "hops");
        r.threads = getNullableInt(rs, "threads");
        r.hopMm = getNullableDouble(rs, "hop_mm");
        r.areaMeanMm2 = getNullableDouble(rs, "area_mean_mm2");
        r.residCv = getNullableDouble(rs, "resid_cv");
        r.diameterMeanMm = getNullableDouble(rs, "diameter_mean_mm");
        r.diameterCv = getNullableDouble(rs, "diameter_cv");
        r.diameterP95Mm = getNullableDouble(rs, "diameter_p95_mm");
        r.phaseJson = rs.getString("phase_json");
        r.drift = getNullableDouble(rs, "drift");
        r.landingMeanMm = getNullableDouble(rs, "landing_mean_mm");
        r.landingSdMm = getNullableDouble(rs, "landing_sd_mm");
        r.dotsJson = rs.getString("dots_json");
        Integer pass = getNullableInt(rs, "pass");
        r.pass = pass == null ? null : pass != 0;
        r.why = rs.getString("why");
        r.couponPlace = rs.getString("coupon_place");
        r.couponRecordId = getNullableLong(rs, "coupon_record_id");
        r.fullMg = getNullableDouble(rs, "full_mg");
        r.emptyMg = getNullableDouble(rs, "empty_mg");
        r.totalMg = getNullableDouble(rs, "total_mg");
        r.mgPerDot = getNullableDouble(rs, "mg_per_dot");
        r.mgPerStep = getNullableDouble(rs, "mg_per_step");
        r.mgSe = getNullableDouble(rs, "mg_se");
        r.weighDelayS = getNullableInt(rs, "weigh_delay_s");
        r.confirmedBy = getNullableLong(rs, "confirmed_by");
        r.pressurePsi = rs.getDouble("pressure_psi");
        r.doseFp = rs.getString("dose_fp");
        r.tipKey = rs.getString("tip_key");
        r.pasteKey = rs.getString("paste_key");
        r.regulatorPsi = getNullableDouble(rs, "regulator_psi");
        r.regulatorAtMs = getNullableLong(rs, "regulator_at_ms");
        r.pasteLifeAtMs = getNullableLong(rs, "paste_life_at_ms");
        r.controllerConnectedAtMs = getNullableLong(rs, "controller_connected_at_ms");
        r.startAngleSetAtMs = getNullableLong(rs, "start_angle_set_at_ms");
        r.startAngleDeg = getNullableDouble(rs, "start_angle_deg");
        r.method = rs.getString("method");
        r.simulated = rs.getInt("simulated") != 0;
        r.runUuid = rs.getString("run_uuid");
        r.jobId = rs.getString("job_id");
        r.notes = rs.getString("notes");
        r.operator = rs.getString("operator");
        return r;
    }

    /**
     * Appends the outcome of one pad type's calibration (created_at = now). A {@code characterized}
     * outcome needs at least one check and every check passed.
     */
    public synchronized PadCalibrationRecord insertPadCalibration(PadCalibrationRecord r) throws SQLException {
        if (blank(r.jobId) || blank(r.typeKey)) {
            throw new IllegalArgumentException("a pad calibration needs its job id and type key");
        }
        if (!PadCalibrationRecord.OUTCOMES.contains(r.outcome)) {
            throw new IllegalArgumentException("outcome must be one of " + PadCalibrationRecord.OUTCOMES);
        }
        if (PadCalibrationRecord.CHARACTERIZED.equals(r.outcome) && !r.allChecksPass()) {
            throw new IllegalArgumentException("characterized needs every check passed");
        }
        Gson gson = new Gson();
        r.createdAtMs = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO pad_calibrations(created_at, job_id, type_key,"
                + " version_from, version_to, outcome, steps, dots_per_pad, mass_per_pad_mg, checks_json,"
                + " evidence_json, runs_json, simulated, message, operator) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            ps.setLong(i++, r.createdAtMs);
            ps.setString(i++, r.jobId);
            ps.setString(i++, r.typeKey);
            setNullableLong(ps, i++, longOf(r.versionFrom));
            setNullableLong(ps, i++, longOf(r.versionTo));
            ps.setString(i++, r.outcome);
            setNullableLong(ps, i++, longOf(r.steps));
            setNullableLong(ps, i++, longOf(r.dotsPerPad));
            setNullable(ps, i++, r.massPerPadMg);
            ps.setString(i++, gson.toJson(r.checks == null ? new ArrayList<>() : r.checks));
            ps.setString(i++, gson.toJson(r.evidence == null ? new ArrayList<>() : r.evidence));
            ps.setString(i++, gson.toJson(r.runs == null ? new ArrayList<>() : r.runs));
            ps.setInt(i++, r.simulated ? 1 : 0);
            ps.setString(i++, r.message);
            ps.setString(i++, r.operator);
            ps.executeUpdate();
            r.id = generatedKey(ps);
        }
        return r;
    }

    /** Pad calibrations, newest first, of one type (typeKey null: every type). */
    public synchronized List<PadCalibrationRecord> padCalibrations(String typeKey, int limit) throws SQLException {
        Gson gson = new Gson();
        Type checks = new TypeToken<List<PadCalibrationRecord.Check>>() { }.getType();
        Type ids = new TypeToken<List<Long>>() { }.getType();
        Type strings = new TypeToken<List<String>>() { }.getType();
        List<PadCalibrationRecord> l = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM pad_calibrations"
                + (typeKey == null ? "" : " WHERE type_key = ?") + " ORDER BY id DESC LIMIT " + Math.max(1, limit))) {
            if (typeKey != null) {
                ps.setString(1, typeKey);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PadCalibrationRecord r = new PadCalibrationRecord();
                    r.id = rs.getLong("id");
                    r.createdAtMs = rs.getLong("created_at");
                    r.jobId = rs.getString("job_id");
                    r.typeKey = rs.getString("type_key");
                    r.versionFrom = getNullableInt(rs, "version_from");
                    r.versionTo = getNullableInt(rs, "version_to");
                    r.outcome = rs.getString("outcome");
                    r.steps = getNullableInt(rs, "steps");
                    r.dotsPerPad = getNullableInt(rs, "dots_per_pad");
                    r.massPerPadMg = getNullableDouble(rs, "mass_per_pad_mg");
                    r.checks = gson.fromJson(rs.getString("checks_json"), checks);
                    String ev = rs.getString("evidence_json"), runs = rs.getString("runs_json");
                    r.evidence = ev == null ? new ArrayList<>() : gson.fromJson(ev, ids);
                    r.runs = runs == null ? new ArrayList<>() : gson.fromJson(runs, strings);
                    r.simulated = rs.getInt("simulated") != 0;
                    r.message = rs.getString("message");
                    r.operator = rs.getString("operator");
                    l.add(r);
                }
            }
        }
        return l;
    }

    /** Generic read-only query helper (column label -> value). */
    public synchronized List<Map<String, Object>> query(String sql, Object... args) throws SQLException {
        List<Map<String, Object>> l = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int c = 1; c <= n; c++) {
                        m.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    }
                    l.add(m);
                }
            }
        }
        return l;
    }

    /** Raw statement access for tests (e.g. proving UPDATE is refused). */
    synchronized void executeRaw(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    // ------------------------------------------------------------------------------------------

    private static void setNullable(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v == null || v.isNaN()) {
            ps.setNull(i, Types.REAL);
        }
        else {
            ps.setDouble(i, v);
        }
    }

    private static void setNullableLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        }
        else {
            ps.setLong(i, v);
        }
    }

    private static Long longOf(Integer v) {
        return v == null ? null : v.longValue();
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static Double getNullableDouble(ResultSet rs, String col) throws SQLException {
        double d = rs.getDouble(col);
        return rs.wasNull() ? null : d;
    }

    private static Integer getNullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static Long getNullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static long generatedKey(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.getGeneratedKeys()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        }
        throw new SQLException("no generated key");
    }
}
