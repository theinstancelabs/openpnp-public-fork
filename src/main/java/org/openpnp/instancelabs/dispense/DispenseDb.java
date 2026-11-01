package org.openpnp.instancelabs.dispense;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite store at {@code <configDir>/instancelabs/dispense.db}. Every table is append-only (enforced
 * with BEFORE UPDATE/DELETE triggers): edits append a new version, nothing is overwritten (plan
 * §5.6, §5.7).
 *
 * <p>Tables: pad_types (versioned), pad_rules (versioned), package_pad_type (versioned mapping
 * overrides), calibration, dot_ladder, pad_plan, runs, dot_log, events, needle_touchoff,
 * scale_entries.</p>
 */
public class DispenseDb implements AutoCloseable {
    public static final String FILE_NAME = "dispense.db";
    public static final int SCHEMA_VERSION = 1;

    static final String[] TABLES = {"schema_info", "pad_types", "pad_rules", "package_pad_type",
            "calibration", "dot_ladder", "pad_plan", "runs", "dot_log", "events", "needle_touchoff",
            "scale_entries"};

    private final Connection conn;
    private final String url;

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
        this.conn = DriverManager.getConnection(url);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
            if (!url.contains(":memory:")) {
                st.execute("PRAGMA journal_mode=WAL");
            }
        }
        createSchema();
        seedIfEmpty();
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
            "CREATE VIEW IF NOT EXISTS pad_types_current AS SELECT p.* FROM pad_types p JOIN"
                + " (SELECT type_key, MAX(version) AS v FROM pad_types GROUP BY type_key) m"
                + " ON p.type_key = m.type_key AND p.version = m.v",
        };
        synchronized (this) {
            try (Statement st = conn.createStatement()) {
                for (String s : ddl) {
                    st.execute(s);
                }
                for (String t : TABLES) {
                    st.execute("CREATE TRIGGER IF NOT EXISTS " + t + "_no_update BEFORE UPDATE ON " + t
                            + " BEGIN SELECT RAISE(ABORT, '" + t + " is append-only'); END");
                    st.execute("CREATE TRIGGER IF NOT EXISTS " + t + "_no_delete BEFORE DELETE ON " + t
                            + " BEGIN SELECT RAISE(ABORT, '" + t + " is append-only'); END");
                }
            }
        }
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
                + " package_pattern, notes, created_at, operator) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
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
        return p;
    }

    /** Appends a package -> pad type mapping override (typeKey null clears the override). */
    public synchronized void mapPackage(String packageId, String typeKey) throws SQLException {
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
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO package_pad_type(package_id, type_key, version, created_at) VALUES (?,?,?,?)")) {
            ps.setString(1, packageId);
            ps.setString(2, typeKey);
            ps.setInt(3, version);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Current package id -> type key overrides (cleared overrides are omitted). */
    public synchronized Map<String, String> packageMappings() throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT p.package_id, p.type_key FROM package_pad_type p JOIN"
                        + " (SELECT package_id, MAX(version) AS v FROM package_pad_type GROUP BY package_id) m"
                        + " ON p.package_id = m.package_id AND p.version = m.v ORDER BY p.package_id")) {
            while (rs.next()) {
                if (rs.getString(2) != null) {
                    m.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return m;
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
                    + " motion_s, total_s, dph_motion, dph_overall, calibration_id, keepalive, lines_sent, message)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
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

    private static Double getNullableDouble(ResultSet rs, String col) throws SQLException {
        double d = rs.getDouble(col);
        return rs.wasNull() ? null : d;
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
