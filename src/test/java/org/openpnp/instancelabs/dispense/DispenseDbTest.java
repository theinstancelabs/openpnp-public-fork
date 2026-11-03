package org.openpnp.instancelabs.dispense;

import org.openpnp.instancelabs.padprofile.Tip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** SQLite store: schema, seeds, append-only versioning, scale entry, schema 2 mappings and records. */
public class DispenseDbTest {
    @Test
    public void seedsFamiliesTypesAndProvisionalCalibration() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            List<PadType> t = db.currentPadTypes(Tip.SEED_KEY);
            assertEquals(12, t.size());
            assertEquals("chip-0402", t.get(0).typeKey);
            assertEquals("chip-0603", t.get(1).typeKey);
            assertEquals(0.80, t.get(1).padWMm, 0);
            assertEquals(0.95, t.get(1).padHMm, 0);
            assertEquals(0.10, t.get(1).tMm, 0);
            for (PadType p : t) {
                assertEquals(PadTypeStatus.NOT_STARTED, p.status);
            }
            PadType centre = db.currentPadType("qfn-centre", Tip.SEED_KEY);
            assertTrue(centre.k < 1);
            Calibration c = db.currentCalibration(System.currentTimeMillis());
            assertEquals(0.47, c.qMm3PerRev, 0);
            assertEquals("provisional", c.status);
        }
    }

    /** At least one row in every table (row triggers only fire on existing rows). */
    static void oneRowInEveryTable(DispenseDb db) throws Exception {
        db.insertPadRule(new PadRule("chip-0603", 2, 1.0, null));
        db.mapPackage("P", "chip-0603");
        if (db.calibrations().isEmpty()) {
            Calibration c = new Calibration();
            c.method = "provisional";
            c.status = "provisional";
            c.qMm3PerRev = 0.47;
            db.insertCalibration(c);
        }
        long calibrationId = db.calibrations().get(0).id;
        DotLadderRow lr = new DotLadderRow(calibrationId, 30, 3, 0.5, 0.1, 0.2, 0.1);
        db.insertDotLadderRow(lr);
        DispensePlan plan = GcodeStreamTest.toyPlan(GcodeStreamTest.toyConfig());
        db.insertPadPlan(plan);
        RunResult r = new RunResult();
        r.runId = "x" + System.nanoTime();
        r.kind = "board";
        r.dotTimings.add(new RunResult.DotTiming(0, "p", 1, 1, 2));
        db.insertRun(r);
        db.event("x", "INFO", "c", "m");
        db.insertTouchOff("manual", 0.1, null, null, null);
        db.insertScaleEntry("shots", 360, 1, 1.0, null, 3.7, 1L, null, null);
        DoseRecord coupon = db.insertDoseRecord(DoseRecordTest.record(DoseRecord.COUPON, 800, new DispenseConfig(), 0));
        db.insertDoseRecord(DoseRecordTest.mass(coupon, 70.0));
        PadCalibrationRecord pc = new PadCalibrationRecord();
        pc.jobId = "pc-1";
        pc.typeKey = "chip-0603";
        pc.outcome = PadCalibrationRecord.PROVISIONAL;
        db.insertPadCalibration(pc);
        db.setPastePartial("P", true);
        // schema 3 (tip setups design §22.1): the seeded setup is there; a mount, a photo, its link and event, a
        // label and a flag
        TipSetups.Mount m = new TipSetups.Mount();
        m.kind = TipSetups.Mount.SEED;
        m.setupKey = "ad22tta-b";
        m.setupVersion = 1;
        m.why = "in use before the update";
        db.insertTipMount(m);
        Photo ph = new Photo();
        ph.kind = Photo.KIND_SETUP;
        ph.source = Photo.SOURCE_UPLOAD;
        ph.setupKey = "ad22tta-b";
        ph.file = "2026-10/0123456789abcdef0123.jpg";
        ph.sha256 = "0123456789abcdef0123";
        ph.widthPx = 64;
        ph.heightPx = 64;
        db.insertPhoto(ph);
        db.insertPhotoLink(ph.id, pc.id, Photo.ROLE_REFERENCE, null);
        db.insertPhotoEvent(ph.id, Photo.ACTION_MAIN, null, null);
        db.insertCalLabel(pc.id, "Chip 0603 · 22G bent taper", true, null);
        db.insertCalFlag("pc-1", "ad22tta-b", "wrong-tip", null, null);
    }

    @Test
    public void appendOnlyTablesRefuseUpdateAndDelete() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            oneRowInEveryTable(db);
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
            PadType p = db.currentPadType("chip-0603", Tip.SEED_KEY);
            p.status = PadTypeStatus.PROVISIONAL;
            p.k = 0.9;
            PadType v2 = db.insertPadTypeVersion(p);
            assertEquals(2, v2.version);
            assertEquals(2, db.padTypeHistory("chip-0603").size());
            assertEquals(0.9, db.currentPadType("chip-0603", Tip.SEED_KEY).k, 0);
            assertEquals(1.0, db.padTypeHistory("chip-0603").get(0).k, 0);
            assertEquals(12, db.currentPadTypes(Tip.SEED_KEY).size());
            // user-added type
            PadType mine = new PadType("my-led", "Custom", "Big LED", 1.2, 1.2, PadType.Layout.SINGLE, null, 1.0,
                    "MYLED", null);
            db.insertPadTypeVersion(mine);
            assertEquals(13, db.currentPadTypes(Tip.SEED_KEY).size());
            assertThrows(IllegalArgumentException.class, () -> db.insertPadTypeVersion(
                    new PadType("bad", "x", "x", 1, 1, PadType.Layout.TWO_TERMINAL, null, 1, null, null)));
            // package mapping override
            db.mapPackage("WEIRD", "my-led");
            assertEquals("my-led", db.packageMappings().get("WEIRD"));
            db.mapPackage("WEIRD", null);
            assertTrue(db.packageMappings().isEmpty());
        }
    }

    /** Schema 2 per-pad mappings (design §5.2): a SOT-223's pins and tab on two types. */
    @Test
    public void mappingsForSomePadsOfAPackage() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            db.mapPackage("SOT-223", Arrays.asList("3", "1", "2", "1"), "sot-223-pins");
            db.mapPackage("SOT-223", Collections.singletonList("4"), "sot-223-tab");
            db.mapPackage("SOT-223", "chip-0603");
            Map<String, String> m = db.packageMappings();
            assertEquals("sot-223-pins", m.get("SOT-223#1,2,3"), m.toString());
            assertEquals("sot-223-tab", m.get("SOT-223#4"));
            assertEquals("chip-0603", m.get("SOT-223"));
            assertEquals(Arrays.asList("SOT-223#1,2,3", "SOT-223#4", "SOT-223"), new ArrayList<>(m.keySet()),
                    "a package's keys in version order");
            // the current mapping of a key is its newest; versions count per package (UNIQUE(package, version))
            db.mapPackage("SOT-223", Arrays.asList("4"), "custom");
            db.mapPackage("SOT-223", Arrays.asList("1", "2", "3"), null);
            m = db.packageMappings();
            assertEquals("custom", m.get("SOT-223#4"));
            assertFalse(m.containsKey("SOT-223#1,2,3"), "cleared");
            assertEquals("chip-0603", m.get("SOT-223"));
            assertEquals(Arrays.asList(1, 2, 3, 4, 5), versions(db, "SOT-223"));
            assertEquals(Arrays.asList("SOT-223", "SOT-223#4"), new ArrayList<>(m.keySet()));
            // an empty name list is the whole package
            db.mapPackage("SOT-223", new ArrayList<>(), null);
            assertFalse(db.packageMappings().containsKey("SOT-223"));
            // names: distinct, natural order, no separators
            assertEquals("1,2,10,A2,A10,EP", DispenseDb.joinPadNames(Arrays.asList("EP", "10", "A10", "2", "A2", "1")));
            assertEquals(null, DispenseDb.joinPadNames(null));
            assertEquals(Arrays.asList("1", "2", "10"), DispenseDb.splitPadNames("1,2,10"));
            assertTrue(DispenseDb.splitPadNames(null).isEmpty());
            assertEquals("Q#1,2", DispenseDb.mappingKey("Q", "1,2"));
            assertEquals("Q", DispenseDb.mappingKey("Q", null));
            for (String bad : new String[] {"1,2", "#1", " ", ""}) {
                assertThrows(IllegalArgumentException.class, () -> db.mapPackage("P", Arrays.asList(bad), "custom"),
                        bad);
            }
            assertThrows(IllegalArgumentException.class, () -> db.mapPackage(null, "custom"));
        }
    }

    /**
     * A pad is in at most one per-pad mapping of its package: which type it gets never depends on
     * the order of the keys, so the resolver's fingerprint (the keys sorted) tells every change.
     */
    @Test
    public void perPadMappingsNeverShareAPad() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            db.mapPackage("P", Arrays.asList("1", "2"), "chip-0603");
            db.mapPackage("P", Arrays.asList("2", "3"), "chip-0805");
            Map<String, String> m = db.packageMappings();
            assertEquals("{P#1=chip-0603, P#2,3=chip-0805}", new java.util.TreeMap<>(m).toString());
            String before = new PadTypeResolver(db.currentPadTypes(Tip.SEED_KEY), m).fingerprint();
            // pad 2 goes back: chip-0805 keeps pad 3 only, and the fingerprint changes
            db.mapPackage("P", Arrays.asList("1", "2"), "chip-0603");
            m = db.packageMappings();
            assertEquals("{P#1,2=chip-0603, P#3=chip-0805}", new java.util.TreeMap<>(m).toString());
            assertFalse(before.equals(new PadTypeResolver(db.currentPadTypes(Tip.SEED_KEY), m).fingerprint()));
            // clearing some pads takes them out of their mapping; the same key again changes nothing else
            db.mapPackage("P", Arrays.asList("2"), null);
            assertEquals("{P#1=chip-0603, P#3=chip-0805}", new java.util.TreeMap<>(db.packageMappings()).toString());
            db.mapPackage("P", Arrays.asList("1"), "chip-1206");
            assertEquals("{P#1=chip-1206, P#3=chip-0805}", new java.util.TreeMap<>(db.packageMappings()).toString());
            // a mapping that covers a whole older key clears it; the whole-package mapping is not a per-pad key
            db.mapPackage("P", "sot-23");
            db.mapPackage("P", Arrays.asList("1", "3", "4"), "custom");
            assertEquals("{P=sot-23, P#1,3,4=custom}", new java.util.TreeMap<>(db.packageMappings()).toString());
            // other packages are not touched
            db.mapPackage("Q", Arrays.asList("1"), "chip-0603");
            assertEquals("chip-0603", db.packageMappings().get("Q#1"));
            assertEquals("custom", db.packageMappings().get("P#1,3,4"));
        }
    }

    /** Decision 4: "paste the dispensable pads of this part anyway", per package, default off. */
    @Test
    public void pastePartialIsAPackageOptionDefaultOff() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            assertTrue(db.pastePartialPackages().isEmpty());
            db.setPastePartial("USB_Mini-B", true);
            db.setPastePartial("VQFN-32", true);
            assertEquals(new java.util.TreeSet<>(Arrays.asList("USB_Mini-B", "VQFN-32")), db.pastePartialPackages());
            db.setPastePartial("VQFN-32", false);
            assertEquals(Collections.singleton("USB_Mini-B"), db.pastePartialPackages());
            assertEquals(Arrays.asList(1, 2), versionsOf(db, "package_options", "VQFN-32"));
            assertTrue(db.packageMappings().isEmpty(), "an option is no mapping");
            assertThrows(IllegalArgumentException.class, () -> db.setPastePartial("", true));
        }
    }

    static List<Integer> versionsOf(DispenseDb db, String table, String packageId) throws SQLException {
        List<Integer> l = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT version FROM " + table + " WHERE package_id = ? ORDER BY"
                + " version", packageId)) {
            l.add(((Number) r.get("version")).intValue());
        }
        return l;
    }

    static List<Integer> versions(DispenseDb db, String packageId) throws SQLException {
        return versionsOf(db, "package_pad_type", packageId);
    }

    /** Schema 2 pad type fields (design §5.1): a quarter, half or whole turn, at most 6 dots, half a turn per pad. */
    @Test
    public void padTypeDoseFieldsAreValidatedAndKept() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            PadType t = db.currentPadType("chip-0805", Tip.SEED_KEY);
            assertEquals("seed", t.origin);
            for (int[] ok : new int[][] {{400, 2}, {800, 1}, {1600, 1}, {400, 6}, {800, 6}}) {
                PadType v = t.copy();
                v.dotSteps = ok[0];
                v.dotsPerPad = ok[1];
                assertNull(v.doseProblem(), ok[0] + " x " + ok[1]);
            }
            String[][] bad = {{"533", "1", "dotSteps"}, {"200", "4", "dotSteps"}, {"3200", "1", "dotSteps"},
                {"800", "0", "dotsPerPad"}, {"800", "7", "dotsPerPad"}, {"400", "1", "half a screw turn per pad"}};
            for (String[] b : bad) {
                PadType v = t.copy();
                v.dotSteps = Integer.valueOf(b[0]);
                v.dotsPerPad = Integer.valueOf(b[1]);
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                        () -> db.insertPadTypeVersion(v), b[0] + " x " + b[1]);
                assertTrue(e.getMessage().contains(b[2]), e.getMessage());
            }
            PadType onlySteps = t.copy();
            onlySteps.dotSteps = 400;
            assertNull(onlySteps.doseProblem(), "dots per pad from DotSizer: the planner checks half a turn per pad");
            PadType area = t.copy();
            area.calAreaMm2 = 0.0;
            assertThrows(IllegalArgumentException.class, () -> db.insertPadTypeVersion(area));
            PadType mass = t.copy();
            mass.massPerPadMg = -1.0;
            assertThrows(IllegalArgumentException.class, () -> db.insertPadTypeVersion(mass));
            PadType origin = t.copy();
            origin.origin = "imported";
            assertThrows(IllegalArgumentException.class, () -> db.insertPadTypeVersion(origin));
            assertEquals(1, db.padTypeHistory("chip-0805").size(), "nothing refused was stored");

            PadType v2 = t.copy();
            v2.status = PadTypeStatus.PROVISIONAL;
            v2.dotSteps = 1600;
            v2.dotsPerPad = 1;
            v2.tipKey = "ad22tta-b";
            v2.calAreaMm2 = 1.381;
            v2.origin = PadType.ORIGIN_SUGGESTED;
            db.insertPadTypeVersion(v2);
            PadType back = db.currentPadType("chip-0805", Tip.SEED_KEY);
            assertEquals(2, back.version);
            assertEquals(1600, (int) back.dotSteps);
            assertEquals(1, (int) back.dotsPerPad);
            assertEquals("ad22tta-b", back.tipKey);
            assertNull(back.doseFp);
            assertEquals(1.381, back.calAreaMm2, 0);
            assertNull(back.massPerPadMg);
            assertEquals("suggested", back.origin);
            PadType copy = back.copy();
            assertEquals(back.dotSteps, copy.dotSteps);
            assertEquals(back.dotsPerPad, copy.dotsPerPad);
            assertEquals(back.tipKey, copy.tipKey);
            assertEquals(back.calAreaMm2, copy.calAreaMm2);
            assertEquals(back.origin, copy.origin);
        }
    }

    @Test
    public void doseRecordsAndPendingCoupons() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            DispenseConfig c = new DispenseConfig();
            DoseRecord o = DoseRecordTest.record(DoseRecord.OPTICAL, 400, c, 0);
            o.found = 47;
            o.misses = 1;
            o.hops = 182;
            o.threads = 2;
            o.hopMm = 1.9;
            o.areaMeanMm2 = 0.61;
            o.residCv = 0.07;
            o.diameterMeanMm = 0.88;
            o.diameterCv = 0.04;
            o.diameterP95Mm = 0.95;
            o.phaseJson = "[{\"k\":0,\"mean\":0.6}]";
            o.drift = 0.02;
            o.landingMeanMm = 0.05;
            o.landingSdMm = 0.03;
            o.dotsJson = "[]";
            o.pass = true;
            o.controllerConnectedAtMs = 11L;
            o.startAngleSetAtMs = 12L;
            o.startAngleDeg = 297.9;
            o.pasteLifeAtMs = 13L;
            o.simulated = true;
            o.runUuid = "run-1";
            o.jobId = "pc-1";
            o.operator = "test";
            long before = System.currentTimeMillis();
            db.insertDoseRecord(o);
            DoseRecord ob = db.doseRecord(o.id);
            assertTrue(ob.createdAtMs >= before);
            assertEquals("optical", ob.kind);
            assertEquals(400, ob.steps);
            assertEquals(28, ob.n);
            assertEquals(47, (int) ob.found);
            assertEquals(1, (int) ob.misses);
            assertEquals(182, (int) ob.hops);
            assertEquals(2, (int) ob.threads);
            assertEquals(1.9, ob.hopMm, 0);
            assertEquals(0.61, ob.areaMeanMm2, 0);
            assertEquals(0.07, ob.residCv, 0);
            assertEquals(0.88, ob.diameterMeanMm, 0);
            assertEquals(0.04, ob.diameterCv, 0);
            assertEquals(0.95, ob.diameterP95Mm, 0);
            assertEquals(o.phaseJson, ob.phaseJson);
            assertEquals(0.02, ob.drift, 0);
            assertEquals(0.05, ob.landingMeanMm, 0);
            assertEquals(0.03, ob.landingSdMm, 0);
            assertEquals("[]", ob.dotsJson);
            assertEquals(Boolean.TRUE, ob.pass);
            assertEquals(18, ob.pressurePsi, 0);
            assertEquals(c.profile.doseFingerprint(), ob.doseFp);
            assertEquals("ad22tta-b", ob.tipKey);
            assertEquals("gc50-t5", ob.pasteKey);
            assertEquals(18, ob.regulatorPsi, 0);
            assertEquals(11L, (long) ob.controllerConnectedAtMs);
            assertEquals(12L, (long) ob.startAngleSetAtMs);
            assertEquals(297.9, ob.startAngleDeg, 0);
            assertEquals(13L, (long) ob.pasteLifeAtMs);
            assertTrue(ob.simulated);
            assertEquals("run-1", ob.runUuid);
            assertEquals("pc-1", ob.jobId);
            assertEquals("test", ob.operator);
            assertNull(ob.couponRecordId);
            assertNull(ob.totalMg);

            DoseRecord c1 = db.insertDoseRecord(DoseRecordTest.record(DoseRecord.COUPON, 400, c, 0));
            DoseRecord c2 = db.insertDoseRecord(DoseRecordTest.record(DoseRecord.COUPON, 800, c, 0));
            assertEquals(Arrays.asList(c1.id, c2.id), ids(db.pendingCoupons(0)));
            DoseRecord m1 = db.insertDoseRecord(DoseRecordTest.mass(c1, 72.0));
            DoseRecord mb = db.doseRecord(m1.id);
            assertEquals(c1.id, (long) mb.couponRecordId);
            assertEquals(72.0, mb.totalMg, 1e-9);
            assertEquals(4172.0, mb.fullMg, 1e-9);
            assertEquals(4100.0, mb.emptyMg, 1e-9);
            assertEquals(72.0 / 68, mb.mgPerDot, 1e-12);
            assertEquals(72.0 / 68 / 400, mb.mgPerStep, 1e-15);
            assertEquals(600, (int) mb.weighDelayS);
            assertTrue(mb.hasReading());
            assertEquals(Arrays.asList(c2.id), ids(db.pendingCoupons(0)));
            assertTrue(db.pendingCoupons(System.currentTimeMillis() + 1).isEmpty());
            assertEquals(Arrays.asList(o.id, c1.id, m1.id), ids(db.doseRecords(400, 0)));
            assertEquals(Arrays.asList(c2.id), ids(db.doseRecords(800, 0)));
            assertEquals(4, db.doseRecords(null, 0).size());
            assertTrue(db.doseRecords(null, System.currentTimeMillis() + 1).isEmpty());

            // a coupon is one reading: a second mass row of it would confirm itself
            refused(db, DoseRecordTest.mass(c1, 70.0), "already has its reading");
            DoseRecord noCoupon = DoseRecordTest.mass(c2, 70.0);
            noCoupon.couponRecordId = null;
            refused(db, noCoupon, "must name a coupon record");
            DoseRecord notCoupon = DoseRecordTest.mass(c2, 70.0);
            notCoupon.couponRecordId = o.id;
            refused(db, notCoupon, "must name a coupon record");
            DoseRecord otherDose = DoseRecordTest.mass(c2, 70.0);
            otherDose.steps = 400;
            refused(db, otherDose, "800-step dots, not 400");
            DoseRecord oneReading = DoseRecordTest.mass(c2, 70.0);
            oneReading.emptyMg = null;
            refused(db, oneReading, "both readings");
            DoseRecord wrongTotal = DoseRecordTest.mass(c2, 70.0);
            wrongTotal.totalMg = 71.0;
            refused(db, wrongTotal, "both readings");
            DoseRecord wipedFirst = DoseRecordTest.mass(c2, -70.0);
            refused(db, wipedFirst, "both readings");
            DoseRecord skipped = DoseRecordTest.mass(c2, 70.0);
            skipped.fullMg = null;
            skipped.emptyMg = null;
            skipped.totalMg = null;
            refused(db, skipped, "why the weighing was skipped");
            skipped.why = "skipped: the coupon was wiped without weighing";
            db.insertDoseRecord(skipped);
            assertFalse(db.doseRecord(skipped.id).hasReading());
            assertTrue(db.pendingCoupons(0).isEmpty());

            // a mass row carries its coupon's conditions: no machine reading of simulator dots
            DoseRecord c3 = db.insertDoseRecord(DoseRecordTest.record(DoseRecord.COUPON, 800, c, 0));
            DoseRecord machineReading = DoseRecordTest.mass(c3, 70.0);
            machineReading.simulated = true;
            refused(db, machineReading, "conditions of coupon record " + c3.id);
            DoseRecord otherRecipe = DoseRecordTest.mass(c3, 70.0);
            otherRecipe.doseFp = "psi=17.000";
            refused(db, otherRecipe, "conditions of coupon record");
            DoseRecord otherRegulator = DoseRecordTest.mass(c3, 70.0);
            otherRegulator.regulatorPsi = 17.5;
            refused(db, otherRegulator, "conditions of coupon record");
            DoseRecord simBalance = DoseRecordTest.mass(c3, 70.0);
            simBalance.method = "sim";
            refused(db, simBalance, "marked simulated");
            assertEquals(Arrays.asList(c3.id), ids(db.pendingCoupons(0)));

            DoseRecord kind = DoseRecordTest.record("weighed", 800, c, 0);
            refused(db, kind, "kind must be one of");
            DoseRecord noFp = DoseRecordTest.record(DoseRecord.OPTICAL, 800, c, 0);
            noFp.doseFp = null;
            refused(db, noFp, "dose fingerprint");
            DoseRecord noTip = DoseRecordTest.record(DoseRecord.OPTICAL, 800, c, 0);
            noTip.tipKey = " ";
            refused(db, noTip, "tip");
            DoseRecord method = DoseRecordTest.record(DoseRecord.OPTICAL, 800, c, 0);
            method.method = "guess";
            refused(db, method, "method must be one of");
            DoseRecord steps = DoseRecordTest.record(DoseRecord.OPTICAL, 0, c, 0);
            refused(db, steps, "steps must be > 0");
            DoseRecord place = DoseRecordTest.record(DoseRecord.COUPON, 800, c, 0);
            place.couponPlace = null;
            refused(db, place, "coupon place");
            assertEquals(6, db.doseRecords(null, 0).size(), "nothing refused was stored");
        }
    }

    static void refused(DispenseDb db, DoseRecord r, String text) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> db.insertDoseRecord(r), text);
        assertTrue(e.getMessage().contains(text), e.getMessage());
    }

    static List<Long> ids(List<DoseRecord> l) {
        List<Long> ids = new ArrayList<>();
        for (DoseRecord r : l) {
            ids.add(r.id);
        }
        return ids;
    }

    /** The outcome of a pad type's calibration; characterized only with every check passed (design §7.7). */
    @Test
    public void padCalibrations() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            PadCalibrationRecord p = new PadCalibrationRecord();
            p.jobId = "pc-1";
            p.typeKey = "sot-23";
            p.versionFrom = 1;
            p.outcome = PadCalibrationRecord.PROVISIONAL;
            p.steps = 400;
            p.dotsPerPad = 2;
            p.checks.add(new PadCalibrationRecord.Check("threads", false, "quarter-turn dots trailed a paste thread "
                    + "to the next dot 12 times in 182 moves (at most 9 allowed)"));
            p.checks.get(0).measured = "12 / 182";
            p.checks.get(0).limit = "9";
            p.checks.add(new PadCalibrationRecord.Check("transfer", true, "48 of 48 dots"));
            p.evidence.addAll(Arrays.asList(3L, 4L));
            p.runs.add("run-a");
            p.simulated = true;
            p.message = "stays provisional";
            db.insertPadCalibration(p);

            PadCalibrationRecord ch = new PadCalibrationRecord();
            ch.jobId = "pc-2";
            ch.typeKey = "sot-23";
            ch.versionFrom = 2;
            ch.versionTo = 3;
            ch.outcome = PadCalibrationRecord.CHARACTERIZED;
            ch.massPerPadMg = 1.06;
            ch.checks.add(new PadCalibrationRecord.Check("threads", true, "2 threads in 182 moves"));
            db.insertPadCalibration(ch);

            PadCalibrationRecord other = new PadCalibrationRecord();
            other.jobId = "pc-2";
            other.typeKey = "chip-0805";
            other.outcome = PadCalibrationRecord.STOPPED;
            db.insertPadCalibration(other);

            List<PadCalibrationRecord> l = db.padCalibrations("sot-23", null, 10);
            assertEquals(2, l.size());
            assertEquals(ch.id, l.get(0).id, "newest first");
            PadCalibrationRecord back = l.get(1);
            assertEquals("pc-1", back.jobId);
            assertEquals(1, (int) back.versionFrom);
            assertNull(back.versionTo);
            assertEquals("provisional", back.outcome);
            assertEquals(400, (int) back.steps);
            assertEquals(2, (int) back.dotsPerPad);
            assertEquals(2, back.checks.size());
            assertEquals("threads", back.checks.get(0).key);
            assertFalse(back.checks.get(0).pass);
            assertEquals("12 / 182", back.checks.get(0).measured);
            assertEquals("9", back.checks.get(0).limit);
            assertNull(back.checks.get(0).predicted);
            assertTrue(back.checks.get(1).pass);
            assertEquals(Arrays.asList(3L, 4L), back.evidence);
            assertEquals(Arrays.asList("run-a"), back.runs);
            assertTrue(back.simulated);
            assertEquals("stays provisional", back.message);
            assertFalse(back.allChecksPass());
            assertTrue(l.get(0).allChecksPass());
            assertEquals(1.06, l.get(0).massPerPadMg, 0);
            assertEquals(3, db.padCalibrations(null, null, 10).size());
            assertEquals(1, db.padCalibrations(null, null, 1).size());

            // never characterized with a failed check or without checks; never validated (the user's decision)
            PadCalibrationRecord failed = new PadCalibrationRecord();
            failed.jobId = "pc-3";
            failed.typeKey = "sot-23";
            failed.outcome = PadCalibrationRecord.CHARACTERIZED;
            assertThrows(IllegalArgumentException.class, () -> db.insertPadCalibration(failed));
            failed.checks.add(new PadCalibrationRecord.Check("mass", true, "confirmed"));
            failed.checks.add(new PadCalibrationRecord.Check("control", false, "the control dots are 22 % small"));
            assertThrows(IllegalArgumentException.class, () -> db.insertPadCalibration(failed));
            failed.outcome = "validated";
            failed.checks.remove(1);
            assertThrows(IllegalArgumentException.class, () -> db.insertPadCalibration(failed));
            failed.outcome = PadCalibrationRecord.FAILED;
            failed.jobId = null;
            assertThrows(IllegalArgumentException.class, () -> db.insertPadCalibration(failed));
            assertEquals(3, db.padCalibrations(null, null, 10).size());
        }
    }

    /** Decision 6: the pads skipped for one run are kept with it (shown in Records). */
    @Test
    public void runsKeepTheirSkippedPads() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            RunResult r = new RunResult();
            r.runId = "skip-1";
            r.kind = "board";
            r.skippedPads.addAll(Arrays.asList("U1.3", "U1.4", "J1.D+"));
            db.insertRun(r);
            RunResult none = new RunResult();
            none.runId = "skip-0";
            none.kind = "board";
            db.insertRun(none);
            List<Map<String, Object>> runs = db.recentRuns(5);
            assertNull(runs.get(0).get("skipped_pads"));
            assertEquals("[\"U1.3\",\"U1.4\",\"J1.D+\"]", runs.get(1).get("skipped_pads"));
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
            assertEquals(12, db.currentPadTypes(Tip.SEED_KEY).size(), "seeded once");
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
        assertEquals(4, c.schemaVersion, "every step from 2 on: the tip setups' schema (§22.1)");
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
