package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;

/** SQLite store: schema, seeds, append-only versioning, scale entry. */
public class DispenseDbTest {
    @Test
    public void seedsFamiliesTypesAndProvisionalCalibration() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            List<PadType> t = db.currentPadTypes();
            assertEquals(12, t.size());
            assertEquals("chip-0402", t.get(0).typeKey);
            assertEquals("chip-0603", t.get(1).typeKey);
            assertEquals(0.80, t.get(1).padWMm, 0);
            assertEquals(0.95, t.get(1).padHMm, 0);
            assertEquals(0.10, t.get(1).tMm, 0);
            for (PadType p : t) {
                assertEquals(PadTypeStatus.NOT_STARTED, p.status);
            }
            PadType centre = db.currentPadType("qfn-centre");
            assertTrue(centre.k < 1);
            Calibration c = db.currentCalibration(System.currentTimeMillis());
            assertEquals(0.47, c.qMm3PerRev, 0);
            assertEquals("provisional", c.status);
        }
    }

    @Test
    public void appendOnlyTablesRefuseUpdateAndDelete() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            // put at least one row in every table (row triggers only fire on existing rows)
            db.insertPadRule(new PadRule("chip-0603", 2, 1.0, null));
            db.mapPackage("P", "chip-0603");
            DotLadderRow lr = new DotLadderRow(1, 30, 3, 0.5, 0.1, 0.2, 0.1);
            db.insertDotLadderRow(lr);
            DispensePlan plan = GcodeStreamTest.toyPlan(GcodeStreamTest.toyConfig());
            db.insertPadPlan(plan);
            RunResult r = new RunResult();
            r.runId = "x";
            r.kind = "board";
            r.dotTimings.add(new RunResult.DotTiming(0, "p", 1, 1, 2));
            db.insertRun(r);
            db.event("x", "INFO", "c", "m");
            db.insertTouchOff("manual", 0.1, null, null, null);
            db.insertScaleEntry("shots", 360, 1, 1.0, null, 3.7, 1L, null, null);
            for (String t : DispenseDb.TABLES) {
                assertTrue(((Number) db.query("SELECT COUNT(*) AS n FROM " + t).get(0).get("n")).intValue() > 0, t);
                assertThrows(SQLException.class, () -> db.executeRaw("DELETE FROM " + t), t);
            }
            assertThrows(SQLException.class, () -> db.executeRaw("UPDATE pad_types SET k = 2"));
            assertThrows(SQLException.class, () -> db.executeRaw("UPDATE calibration SET q_mm3_per_rev = 1"));
            assertThrows(SQLException.class, () -> db.executeRaw("UPDATE runs SET status = 'OK'"));
            db.insertPadRule(new PadRule("chip-0603", 3, null, null));
            assertEquals(3, (int) db.currentPadRules().get("chip-0603").maxDots);
        }
    }

    @Test
    public void editsAppendVersions() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            PadType p = db.currentPadType("chip-0603");
            p.status = PadTypeStatus.PROVISIONAL;
            p.k = 0.9;
            PadType v2 = db.insertPadTypeVersion(p);
            assertEquals(2, v2.version);
            assertEquals(2, db.padTypeHistory("chip-0603").size());
            assertEquals(0.9, db.currentPadType("chip-0603").k, 0);
            assertEquals(1.0, db.padTypeHistory("chip-0603").get(0).k, 0);
            assertEquals(12, db.currentPadTypes().size());
            // user-added type
            PadType mine = new PadType("my-led", "Custom", "Big LED", 1.2, 1.2, PadType.Layout.SINGLE, null, 1.0,
                    "MYLED", null);
            db.insertPadTypeVersion(mine);
            assertEquals(13, db.currentPadTypes().size());
            assertThrows(IllegalArgumentException.class, () -> db.insertPadTypeVersion(
                    new PadType("bad", "x", "x", 1, 1, PadType.Layout.TWO_TERMINAL, null, 1, null, null)));
            // package mapping override
            db.mapPackage("WEIRD", "my-led");
            assertEquals("my-led", db.packageMappings().get("WEIRD"));
            db.mapPackage("WEIRD", null);
            assertTrue(db.packageMappings().isEmpty());
        }
    }

    @Test
    public void characterizedCalibrationWinsUntilExpired() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            Calibration c = new Calibration();
            c.method = "scale-shots";
            c.status = "characterized";
            c.qMm3PerRev = 0.52;
            c.validUntilMs = 5000L;
            db.insertCalibration(c);
            Calibration p = new Calibration();
            p.qMm3PerRev = 0.6;
            db.insertCalibration(p);
            assertEquals(0.52, db.currentCalibration(4000).qMm3PerRev, 0);
            assertEquals(0.6, db.currentCalibration(6000).qMm3PerRev, 0, "expired -> newest record");
        }
    }

    @Test
    public void scaleEntries() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            ScaleCalibration.ShotsEntry e = new ScaleCalibration.ShotsEntry();
            e.rotationDegPerShot = 720;
            e.perShotMassMg = new double[] {3.50, 3.45, 3.55, 3.48, 3.52};
            e.densityMgMm3 = 3.7;
            e.markCharacterized = true;
            Calibration c = ScaleCalibration.recordShots(db, e);
            assertEquals(DotMath.mean(e.perShotMassMg) / 3.7 / 2, c.qMm3PerRev, 1e-12);
            assertEquals(5, (int) c.qN);
            assertNotNull(c.qCv);
            assertEquals(c.id, db.currentCalibration(System.currentTimeMillis()).id);

            ScaleCalibration.DotsEntry d = new ScaleCalibration.DotsEntry();
            d.calibrationId = c.id;
            d.rotationDeg = 58;
            d.count = 300;
            d.totalMassMg = 84.0;
            d.scaleSdMg = 1.0;
            d.diameterMeanMm = 0.7;
            DotLadderRow r = ScaleCalibration.recordDots(db, d);
            assertEquals(0.28, r.mgPerDotMean, 1e-12);
            assertEquals(Math.sqrt(2) / 300, r.mgPerDotSe, 1e-12);
            assertEquals(DotMath.sphericalCapHeightMm(0.28 / 3.7, 0.7), r.heightMm, 1e-12);
            assertEquals(1, db.dotLadder(c.id).size());
            assertEquals(2, db.query("SELECT * FROM scale_entries").size());
        }
    }

    @Test
    public void runsAndEventsPersistToFile() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("dispense-db").toFile();
        RunResult r = new RunResult();
        r.runId = "run-1";
        r.kind = "board";
        r.status = RunResult.Status.OK;
        r.startedAtMs = 1000;
        r.endedAtMs = 61000;
        r.dotsPlanned = r.dotsDone = 2;
        r.firstDotStartMs = 2000;
        r.lastDotEndMs = 4000;
        r.dotTimings.add(new RunResult.DotTiming(0, "R1.1", 58, 2000, 2900));
        r.dotTimings.add(new RunResult.DotTiming(1, "R1.2", 58, 2900, 3500));
        try (DispenseDb db = DispenseDb.open(dir)) {
            db.insertRun(r);
            db.event("run-1", "INFO", "X", "hello");
        }
        assertTrue(new File(dir, "instancelabs/dispense.db").exists());
        try (DispenseDb db = DispenseDb.open(dir)) {
            List<java.util.Map<String, Object>> runs = db.recentRuns(5);
            assertEquals(1, runs.size());
            assertEquals(3600.0, ((Number) runs.get(0).get("dph_motion")).doubleValue(), 1e-9);
            assertEquals(120.0, ((Number) runs.get(0).get("dph_overall")).doubleValue(), 1e-9);
            assertEquals(2, db.query("SELECT * FROM dot_log WHERE run_uuid = ?", "run-1").size());
            assertEquals(1, db.recentEvents(5).size());
            assertEquals(12, db.currentPadTypes().size(), "seeded once");
        }
    }

    @Test
    public void configRoundTripsAsJson() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("dispense-cfg").toFile();
        DispenseConfig c = new DispenseConfig();
        c.locations.put("waste", new DispenseConfig.NamedLocation(1, 2, 3));
        c.profile.gapMm = 0.12;
        c.profile.dwellMs = 300;
        c.profile.startAngleSetAtMs = 1234L;
        c.purgeRow.x0Mm = 88.0;
        c.purgeRow.y0Mm = 45.0;
        c.purgeRow.spots = 16;
        c.purgeRow.usedSpots = 8;
        c.cleanPadCheck.minBrightFraction = 0.85;
        c.needleXyTrim.dxMm = 0.04;
        c.needleXyTrim.note = "landing data";
        c.surfaces.put("board", new DispenseConfig.SurfaceMap());
        c.surfaces.get("board").boardFingerprint = "abc";
        c.needleAnchor = new DispenseConfig.NeedleAnchor(5.5, 2.91, null, null, 99L);
        c.needleAnchor.rawSumMm = 65.59;
        c.save(dir);
        assertTrue(new File(dir, "instancelabs/dispense-config.json").exists());
        DispenseConfig back = DispenseConfig.load(dir);
        assertEquals(0.12, back.gapMm(), 0);
        assertEquals(c.profile.fingerprint(), back.profile.fingerprint());
        assertEquals(1234L, (long) back.profile.startAngleSetAtMs);
        assertEquals(8, back.purgeRow.spotsLeft());
        assertEquals(88.0 + 3 * 2.5, back.purgeRow.spot(3)[0], 1e-12);
        assertEquals(0.85, back.cleanPadCheck.minBrightFraction, 0);
        assertEquals(0.04, back.needleXyTrim.dxMm, 0);
        assertEquals("landing data", back.needleXyTrim.note);
        assertEquals("abc", back.surface("board").boardFingerprint);
        assertEquals(65.59, back.needleAnchor.rawSumMm, 0);
        assertEquals(-2.59, back.needleAnchor.offsetMm(), 1e-12);
        assertEquals(DispenseConfig.SCHEMA_VERSION, back.schemaVersion);
        assertEquals(3, back.location("waste").z, 0);
        assertFalse(back.toJson().contains("Gcode"), "pump/valve/watchdog G-code are constants, not settings");
        assertEquals(c.toJson(), back.toJson());
        DispenseConfig fresh = DispenseConfig.load(java.nio.file.Files.createTempDirectory("x").toFile());
        assertEquals(0.10, fresh.gapMm(), 0, "default gap: the profile's 0.10 mm");
        assertTrue(fresh.validate().isEmpty(), fresh.validate().toString());
    }

    /**
     * Schema 2 -> 3: the removed per-dot and test-dot keys are ignored, the motion becomes the bench
     * recipe (XY/Z at 2000, lift at 50 mm/s, B limit 400 rpm) and the I4 limits follow the new
     * needle frame.
     */
    @Test
    public void schemaTwoConfigMigrates() {
        String v2 = "{\"schemaVersion\":2,\"needleGapMm\":0.15,\"bFeedDegS\":360,\"bAccelDegS2\":2000,"
                + "\"retractDeg\":5,\"retractFeedDegS\":360,\"dwellMs\":50,\"abortDisablesB\":true,"
                + "\"primeAndTestDot\":true,\"testDotOpticalCheck\":true,\"rePrimeRotationDeg\":90,"
                + "\"testDotMinDiameterRatio\":0.6,\"provisionalQMm3PerRev\":0.47,\"maxDotsPerPad\":4,"
                + "\"dotFitFactor\":1.0,\"travelAccelMmS2\":1000,\"zLiftAccelMmS2\":200,\"zLiftFeedMmS\":25,"
                + "\"bMaxFeedDegS\":720,\"surfaceMaxResidualMm\":0.075,\"surfaceMarginMm\":2.0,"
                + "\"zFrameToleranceMm\":3.0,\"primeRotationDeg\":360,\"hopHeightMm\":1.5,"
                + "\"locations\":{\"waste\":{\"x\":1,\"y\":2,\"z\":3}}}";
        DispenseConfig c = DispenseConfig.fromJson(v2);
        assertEquals(DispenseConfig.SCHEMA_VERSION, c.schemaVersion);
        assertEquals(3, c.schemaVersion);
        assertEquals(0.10, c.gapMm(), 0, "needleGapMm is gone: the gap is the profile's");
        assertEquals(new DispenseProfile().fingerprint(), c.profile.fingerprint());
        assertEquals(2000, c.travelAccelMmS2, 0);
        assertEquals(2000, c.zLiftAccelMmS2, 0);
        assertEquals(50, c.zLiftFeedMmS, 0);
        assertEquals(2400, c.bMaxFeedDegS, 0);
        assertEquals(0.04, c.surfaceMaxResidualMm, 0);
        assertEquals(3.5, c.surfaceMarginMm, 0);
        assertTrue(c.zFrameToleranceMm <= 2, "" + c.zFrameToleranceMm);
        assertEquals(360.36, c.primeRotationDeg, 0);
        // kept: everything that still exists
        assertEquals(1.5, c.hopHeightMm, 0);
        assertEquals(3, c.location("waste").z, 0);
        assertTrue(c.validate().isEmpty(), c.validate().toString());
        String json = c.toJson();
        for (String gone : new String[] {"needleGapMm", "bFeedDegS", "retractDeg", "testDotOpticalCheck",
            "primeAndTestDot", "provisionalQMm3PerRev", "maxDotsPerPad", "dotFitFactor", "abortDisablesB"}) {
            assertFalse(json.contains("\"" + gone + "\""), gone);
        }
        // tighter or newer values are kept, and a schema-3 file is not migrated again
        DispenseConfig tight = DispenseConfig.fromJson("{\"schemaVersion\":2,\"surfaceMaxResidualMm\":0.02,"
                + "\"surfaceMarginMm\":5.0,\"zFrameToleranceMm\":0.5,\"zLiftFeedMmS\":80}");
        assertEquals(0.02, tight.surfaceMaxResidualMm, 0);
        assertEquals(5.0, tight.surfaceMarginMm, 0);
        assertEquals(0.5, tight.zFrameToleranceMm, 0);
        assertEquals(80, tight.zLiftFeedMmS, 0);
        DispenseConfig v3 = DispenseConfig.fromJson("{\"schemaVersion\":3,\"travelAccelMmS2\":1500}");
        assertEquals(1500, v3.travelAccelMmS2, 0);
    }
}
