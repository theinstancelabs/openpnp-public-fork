package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Tip;
import org.openpnp.model.Configuration;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tip setups (§22.1-22.3, §22.8): what a setup sees of every pad type (the pad of any setup, the dose of its own,
 * stand-ins), the setup a record belongs to, the plan fingerprint per setup, keys, field checks, edits that make
 * another needle, copies, and the start-up reconcile of a real schema-3 settings file.
 */
public class TipSetupsTest {
    static TipSetup straight25() {
        TipSetup s = new TipSetup();
        s.key = "25g-straight";
        s.name = "25G straight";
        s.tipModel = "DSKIT1 25G";
        s.gauge = 25;
        s.idMm = 0.25;
        s.odMm = 0.51;
        s.odSource = TipSetup.OD_ASSUMED;
        s.form = TipSetup.FORM_STRAIGHT;
        s.taper = TipSetup.TAPER_STEEL;
        s.lengthMm = 12.7;
        s.minDotSteps = 400;
        return s;
    }

    static PadType version(DispenseDb db, String key, String tip, PadTypeStatus status, Integer steps, Integer perPad,
            Consumer<PadType> edit) throws Exception {
        PadType t = db.padTypeHistory(key).get(db.padTypeHistory(key).size() - 1).copy();
        t.tipKey = tip;
        t.status = status;
        t.dotSteps = steps;
        t.dotsPerPad = perPad;
        t.origin = PadType.ORIGIN_MANUAL;
        t.massPerPadMg = null;
        t.doseFp = null;
        t.calAreaMm2 = null;
        if (edit != null) {
            edit.accept(t);
        }
        return db.insertPadTypeVersion(t);
    }

    @Test
    public void aSetupSeesThePadOfAnySetupAndItsOwnDose() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            db.insertTipSetupVersion(straight25());
            version(db, "chip-0603", Tip.SEED_KEY, PadTypeStatus.CHARACTERIZED, 800, 1, t -> t.massPerPadMg = 1.0);
            version(db, "chip-0603", "25g-straight", PadTypeStatus.PROVISIONAL, 400, 2, t -> t.padWMm = 0.85);
            List<PadType> g22 = TipSetups.typesFor(db, Tip.SEED_KEY);
            List<PadType> g25 = TipSetups.typesFor(db, "25g-straight");
            List<String> order = new ArrayList<>();
            for (PadType t : g22) {
                order.add(t.typeKey);
            }
            List<String> order25 = new ArrayList<>();
            for (PadType t : g25) {
                order25.add(t.typeKey);
            }
            assertEquals(order, order25, "the same creation order for every setup (the resolver's newest-first)");
            assertEquals(12, order.size());
            PadType a = byKey(g22, "chip-0603");
            assertEquals(3, (int) a.padVersion, "the pad: the newest version of any setup");
            assertEquals(0.85, a.padWMm, 0, "a pad edit made with the 25G holds for the 22G");
            assertEquals(2, a.version, "the dose: the 22G's own newest version");
            assertEquals(PadTypeStatus.CHARACTERIZED, a.status);
            assertEquals(800, (int) a.dotSteps);
            assertEquals(1.0, a.massPerPadMg, 0);
            assertEquals(Tip.SEED_KEY, a.tipKey);
            assertEquals(Tip.SEED_KEY, a.setupKey);
            assertFalse(a.standIn);
            PadType b = byKey(g25, "chip-0603");
            assertEquals(3, b.version);
            assertEquals(PadTypeStatus.PROVISIONAL, b.status);
            assertEquals(400, (int) b.dotSteps);
            assertNull(b.massPerPadMg);
            // a seed (tip_key null) is the seeded setup's: a stand-in for the 25G, cleared
            PadType seed22 = byKey(g22, "chip-0402");
            assertFalse(seed22.standIn);
            assertEquals(1, seed22.version);
            assertEquals("seed", seed22.origin);
            PadType seed25 = byKey(g25, "chip-0402");
            assertTrue(seed25.standIn);
            assertEquals(0, seed25.version);
            assertEquals(1, (int) seed25.padVersion);
            assertEquals(PadTypeStatus.NOT_STARTED, seed25.status);
            assertNull(seed25.dotSteps);
            assertNull(seed25.origin);
            assertEquals("Not calibrated for the 25G straight yet.", seed25.notes);
            assertEquals("25g-straight", seed25.tipKey);
            assertEquals(seed25.name, seed22.name, "the pad fields are the same");
            assertEquals(1, db.currentPadType("chip-0402", "25g-straight").padVersion.intValue());
            assertTrue(db.typeKeyExists("chip-0603"));
            assertFalse(db.typeKeyExists("chip-9999"));
            assertNull(db.currentPadType("chip-9999", Tip.SEED_KEY));
        }
    }

    static PadType byKey(List<PadType> l, String key) {
        for (PadType t : l) {
            if (t.typeKey.equals(key)) {
                return t;
            }
        }
        return null;
    }

    @Test
    public void aRecordWithoutATipKeyIsTheSeededSetups() {
        assertEquals(Tip.SEED_KEY, Tip.setupOf(null));
        assertEquals("25g-straight", Tip.setupOf("25g-straight"));
        TipSpec mounted22 = new TipSpec();
        assertFalse(mounted22.isOther(null));
        assertFalse(mounted22.isOther(Tip.SEED_KEY));
        assertTrue(mounted22.isOther("25g-straight"));
        TipSpec mounted25 = straight25().toSpec();
        assertTrue(mounted25.isOther(null), "a seed type is the 22G's, never the 25G's");
        assertFalse(mounted25.isOther("25g-straight"));
    }

    @Test
    public void theFingerprintNamesTheSetupItsVersionAndBothVersionsOfEveryType() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            db.insertTipSetupVersion(straight25());
            String a = new PadTypeResolver(db.currentPadTypes(Tip.SEED_KEY), null).withSetup(Tip.SEED_KEY, 1)
                    .fingerprint();
            String b = new PadTypeResolver(db.currentPadTypes("25g-straight"), null).withSetup("25g-straight", 1)
                    .fingerprint();
            assertNotEquals(a, b, "another setup");
            assertTrue(a.contains("chip-0603:1/1@ad22tta-b;"), a);
            assertTrue(b.contains("chip-0603:1/~@25g-straight;"), b);
            assertTrue(a.endsWith("|tip:ad22tta-b@1"), a);
            String a2 = new PadTypeResolver(db.currentPadTypes(Tip.SEED_KEY), null).withSetup(Tip.SEED_KEY, 2)
                    .fingerprint();
            assertNotEquals(a, a2, "a corrected setup (a new version) makes plans stale");
            // a pad edit under the 25G changes the 22G's fingerprint (the pad is shared)
            version(db, "chip-0603", "25g-straight", PadTypeStatus.PROVISIONAL, 800, 1, t -> t.padHMm = 1.0);
            String a3 = new PadTypeResolver(db.currentPadTypes(Tip.SEED_KEY), null).withSetup(Tip.SEED_KEY, 1)
                    .fingerprint();
            assertNotEquals(a, a3);
            assertTrue(a3.contains("chip-0603:2/1@ad22tta-b;"), a3);
        }
    }

    @Test
    public void keysComeFromTheGaugeAndTheFormAndAreUnique() {
        TipSetup s = straight25();
        assertEquals("25g-straight", TipSetups.deriveKey(s, new ArrayList<>()));
        assertEquals("25g-straight-2", TipSetups.deriveKey(s, Arrays.asList("25g-straight")));
        assertEquals("25g-straight-3", TipSetups.deriveKey(s, Arrays.asList("25g-straight", "25g-straight-2")));
        s.gauge = 27;
        s.form = TipSetup.FORM_BENT;
        s.bendDeg = 45.0;
        assertEquals("27g-bent45", TipSetups.deriveKey(s, new ArrayList<>()));
        s.gauge = 22;
        s.form = TipSetup.FORM_STRAIGHT;
        s.bendDeg = null;
        s.taper = TipSetup.TAPER_PLASTIC;
        assertEquals("22g-taper", TipSetups.deriveKey(s, new ArrayList<>()));
        TipSetup none = new TipSetup();
        none.form = TipSetup.FORM_UNKNOWN;
        none.taper = TipSetup.TAPER_UNKNOWN;
        assertEquals("tip", TipSetups.deriveKey(none, new ArrayList<>()));
        assertTrue(TipSpec.KEY.matcher(TipSetups.deriveKey(s, Arrays.asList("22g-taper"))).matches());
    }

    static String only(TipSetup s) {
        List<String> p = s.problems();
        assertEquals(1, p.size(), p.toString());
        return p.get(0);
    }

    @Test
    public void everyFieldIsCheckedWithItsText() {
        assertTrue(straight25().problems().isEmpty(), straight25().problems().toString());
        assertTrue(TipSetup.seed().problems().isEmpty(), TipSetup.seed().problems().toString());
        TipSetup s = straight25();
        s.key = "Bad Key";
        assertEquals("The key must be lower-case letters, digits and '-' (at most 48).", only(s));
        s = straight25();
        s.key = TipSetup.RESERVED_KEY;
        assertEquals("The key 'undo-mount' is taken by the web page: choose another.", only(s));
        s = straight25();
        s.name = "";
        assertEquals("The name must be 1 to 40 characters.", only(s));
        s.name = "x".repeat(41);
        assertEquals("The name must be 1 to 40 characters.", only(s));
        s = straight25();
        s.dispenser = "x".repeat(61);
        assertEquals("The dispenser must be 1 to 60 characters.", only(s));
        s = straight25();
        s.tipModel = "x".repeat(61);
        assertEquals("The tip model must be at most 60 characters.", only(s));
        s = straight25();
        s.gauge = 9;
        assertEquals("The gauge must be 10 to 34.", only(s));
        s = straight25();
        s.idMm = 2.5;
        s.odMm = 2.8;
        assertEquals("The bore (ID) must be 0.05 to 2.0 mm.", only(s));
        s = straight25();
        s.odMm = 0.2;
        assertEquals("The outer diameter (OD) must be larger than the ID and at most 3.0 mm.", only(s));
        s = straight25();
        s.odSource = null;
        assertEquals("Say where the OD comes from: measured or from the chart.", only(s));
        s = straight25();
        s.form = "curly";
        assertEquals("The form is bent or straight.", only(s));
        s = straight25();
        s.bendDeg = 30.0;
        assertEquals("Only a bent tip has a bend angle and a bend direction.", only(s));
        s = straight25();
        s.form = TipSetup.FORM_BENT;
        s.bendDeg = 95.0;
        assertEquals("The bend angle must be 1 to 90°.", only(s));
        s.bendDeg = 45.0;
        s.bendToward = "+z";
        assertEquals("The bend points toward +x, -x, +y or -y.", only(s));
        s = straight25();
        s.taper = "glass";
        assertEquals("The taper is tapered plastic or straight steel.", only(s));
        s = straight25();
        s.lengthMm = 2.0;
        assertEquals("The tube length must be 3 to 60 mm.", only(s));
        s = straight25();
        s.minDotSteps = 600;
        assertEquals("The smallest dot must be 400, 800 or 1600 steps (a quarter, half or one turn).", only(s));
        s = straight25();
        s.model = "my-model";
        assertEquals("The model is bench-22g or none.", only(s));
        s = straight25();
        s.notes = "x".repeat(501);
        assertEquals("The notes must be at most 500 characters.", only(s));
        // complete: ID, OD, form and taper
        assertTrue(straight25().isComplete());
        TipSetup p = TipSetup.placeholder("x25");
        assertTrue(p.problems().isEmpty(), p.problems().toString());
        assertFalse(p.isComplete());
        assertEquals("ID", p.missing());
        assertEquals("DSKIT1 25G · 25G · straight · straight steel · 12.7 mm long · ID 0.25 mm · OD 0.51 mm (from the "
                + "chart)", straight25().summary());
        assertEquals("ADL AD22TTA-B · 22G · bent 30° · tapered plastic · ID 0.41 mm · OD 0.72 mm (from the chart)",
                TipSetup.seed().summary());
    }

    @Test
    public void identityEditsOfASetupInUseAreAnotherNeedleCorrectionsTakeTwoPresses() {
        TipSetup was = straight25();
        TipSetup next = was.copy();
        next.gauge = 27;
        EngineRefusal e = assertThrows(EngineRefusal.class, () -> TipSetups.checkEdit(was, next, true, true));
        assertEquals("another-needle", e.code);
        assertEquals(409, e.status);
        assertEquals(TipSetups.ANOTHER_NEEDLE, e.getMessage());
        TipSetups.checkEdit(was, next, false, false); // not in use yet: a plain edit
        for (Consumer<TipSetup> identity : Arrays.<Consumer<TipSetup>>asList(t -> t.dispenser = "Another dispenser",
                t -> t.form = TipSetup.FORM_BENT, t -> t.taper = TipSetup.TAPER_PLASTIC, t -> t.idMm = 0.20)) {
            TipSetup n = was.copy();
            identity.accept(n);
            if (TipSetup.FORM_BENT.equals(n.form)) {
                n.bendDeg = 45.0;
            }
            assertEquals("another-needle", assertThrows(EngineRefusal.class,
                    () -> TipSetups.checkEdit(was, n, true, true)).code);
        }
        // a measured bore within 0.03 mm is a correction: refused without the second press, then accepted
        TipSetup id = was.copy();
        id.idMm = 0.27;
        EngineRefusal c = assertThrows(EngineRefusal.class, () -> TipSetups.checkEdit(was, id, true, false));
        assertEquals("correction", c.code);
        assertEquals("Correcting the 25G straight's details (ID 0.25 → 0.27 mm)? A different needle is a new tip: Add a "
                + "tip…", c.getMessage());
        TipSetups.checkEdit(was, id, true, true);
        // plain edits: name, OD, bend direction, smallest dot, notes
        TipSetup plain = was.copy();
        plain.name = "25G straight steel";
        plain.odMm = 0.52;
        plain.minDotSteps = 800;
        plain.notes = "measured";
        TipSetups.checkEdit(was, plain, true, false);
        // filling in a placeholder is not another needle
        TipSetup ph = TipSetup.placeholder("x25");
        TipSetup filled = ph.copy();
        filled.idMm = 0.25;
        filled.odMm = 0.51;
        filled.odSource = TipSetup.OD_ASSUMED;
        filled.form = TipSetup.FORM_STRAIGHT;
        filled.taper = TipSetup.TAPER_STEEL;
        TipSetups.checkEdit(ph, filled, true, false);
        // a field problem is a 400
        TipSetup bad = was.copy();
        bad.gauge = 50;
        assertThrows(IllegalArgumentException.class, () -> TipSetups.checkEdit(was, bad, true, true));
    }

    @Test
    public void aRaiseByHandNeedsTheMountedSetupsOwnEvidenceAndWritesARecord() throws Exception {
        // §22.2: a stand-in (or anything the mounted setup never measured) goes no higher than provisional without the
        // setup's own fresh camera check and confirmed weighing of the dose; the bench setup keeps today's rule
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            f.mount("25g-straight");
            PadType standIn = svc.getDb().currentPadType("chip-0603", "25g-straight");
            assertTrue(standIn.standIn);
            for (PadTypeStatus s : new PadTypeStatus[] {PadTypeStatus.CHARACTERIZED, PadTypeStatus.VALIDATED}) {
                PadType v = standIn.copy();
                v.status = s;
                v.dotSteps = 800;
                v.dotsPerPad = 1;
                EngineRefusal e = assertThrows(EngineRefusal.class, () -> svc.savePadType(v, "25g-straight",
                        standIn.padVersion));
                assertEquals("status-evidence", e.code, s.dbName);
                assertTrue(e.getMessage().startsWith("0603 uses half-turn dots, which have no fresh camera check on "
                        + "the blank and no confirmed weighing at this recipe"), e.getMessage());
                assertEquals("status-evidence", assertThrows(EngineRefusal.class, () -> svc.savePadTypeByHand(v,
                        "25g-straight", null, null, "tester")).code);
            }
            PadType provisional = standIn.copy();
            provisional.status = PadTypeStatus.PROVISIONAL;
            provisional.dotSteps = 800;
            provisional.dotsPerPad = 1;
            assertEquals(PadTypeStatus.PROVISIONAL, svc.savePadType(provisional, "25g-straight", standIn.padVersion)
                    .status, "provisional needs no evidence");
            assertEquals(0, svc.getDb().padCalibrations("chip-0603", "25g-straight", 10).size());
            // the 22G (the bench model): raised by hand with a record, its automatic label and method by-hand
            f.mount(Tip.SEED_KEY);
            PadType chip = svc.getDb().currentPadType("chip-0603", Tip.SEED_KEY);
            PadType v = chip.copy();
            v.status = PadTypeStatus.CHARACTERIZED;
            v.dotSteps = 800;
            v.dotsPerPad = 1;
            PadCalibrationRecord rec = svc.savePadTypeByHand(v, Tip.SEED_KEY, null, null, "tester");
            assertEquals(PadCalibrationRecord.METHOD_BY_HAND, rec.method);
            assertEquals(PadCalibrationRecord.CHARACTERIZED, rec.outcome);
            assertEquals(Tip.SEED_KEY, rec.setupKey);
            assertTrue(rec.jobId.startsWith("hand-"), rec.jobId);
            PadCalibrationRecord.Label label = svc.getDb().calLabel(rec.id);
            assertTrue(label.auto);
            assertTrue(label.label.startsWith("Chip 0603 · " + Tip.SEED_NAME + " · Vieweg DV-5005 · 18 psi · "),
                    label.label);
            assertEquals(PadTypeStatus.CHARACTERIZED, svc.getDb().currentPadType("chip-0603", Tip.SEED_KEY).status);
        }
    }

    @Test
    public void aCopyIsAProvisionalStartThroughTheDoseRules() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            TipSetup t25 = db.insertTipSetupVersion(straight25());
            PadType src = version(db, "chip-0603", Tip.SEED_KEY, PadTypeStatus.CHARACTERIZED, 800, 1,
                    t -> t.calAreaMm2 = 0.73);
            PadType own = db.currentPadType("chip-0603", t25.key);
            PadType v = TipSetups.copyOf(src, Tip.SEED_NAME, own, t25);
            assertEquals(PadTypeStatus.PROVISIONAL, v.status);
            assertEquals(PadType.ORIGIN_COPIED, v.origin);
            assertEquals(800, (int) v.dotSteps);
            assertEquals(1, (int) v.dotsPerPad);
            assertEquals(0.73, v.calAreaMm2, 0);
            assertNull(v.doseFp);
            assertNull(v.massPerPadMg);
            assertEquals(t25.key, v.tipKey);
            assertEquals("Copied from the " + Tip.SEED_NAME + " (v2, characterized) as a starting point: nothing was "
                    + "measured with the 25G straight.", v.notes);
            PadType stored = db.insertPadTypeVersion(v);
            assertEquals(3, stored.version);
            // a quarter-turn plan onto a setup that takes half turns at least
            TipSetup half = straight25();
            half.minDotSteps = 800;
            PadType quarter = src.copy();
            quarter.dotSteps = 400;
            quarter.dotsPerPad = 2;
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> TipSetups.copyOf(quarter, Tip.SEED_NAME, own, half));
            assertEquals("the 25G straight takes a half turn per dot at least (800 steps), not 400 steps", e.getMessage());
            // R6: a pad gets half a turn at least
            PadType r6 = src.copy();
            r6.dotSteps = 400;
            r6.dotsPerPad = 1;
            assertThrows(IllegalArgumentException.class, () -> TipSetups.copyOf(r6, Tip.SEED_NAME, own, t25));
            // never over the target's characterized version
            PadType mine = own.copy();
            mine.standIn = false;
            mine.status = PadTypeStatus.CHARACTERIZED;
            EngineRefusal r = assertThrows(EngineRefusal.class, () -> TipSetups.copyOf(src, Tip.SEED_NAME, mine, t25));
            assertEquals(409, r.status);
            assertTrue(r.getMessage().endsWith("is characterized for the 25G straight already: a copy would make it "
                    + "provisional."), r.getMessage());
        }
    }

    @Test
    public void aNewSetupsEntryHasNoFrameAndAZeroTrim() {
        DispenseConfig live = new DispenseConfig();
        live.needleZOffsetMm = -2.59;
        DispenseConfig.ParkedTip e = TipSetups.newEntry(live, live.profile, Tip.SEED_KEY, "25G straight");
        assertEquals("18 psi half turn · 25G straight", e.profile.name);
        assertNull(e.needleAnchor);
        assertEquals(-2.59, e.needleZOffsetMm, 0);
        assertNull(e.needleZOffsetSetAtMs);
        assertNull(e.needleZOffsetHomeAtMs);
        assertEquals(0, e.needleXyTrim.dxMm, 0);
        assertEquals("new tip", e.needleXyTrim.note);
        assertNull(e.needleLanding);
        assertEquals(Tip.SEED_KEY, e.profileFrom);
        assertEquals("18 psi half turn", live.profile.name, "a copy");
    }

    // ---- the start-up reconcile -------------------------------------------------------------------------------------

    static String fixture() throws Exception {
        try (InputStream in = TipSetupsTest.class.getResourceAsStream("/tips/dispense-config-v3.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The settings file's {@code tip} object. */
    static JsonObject tipOf(String json) {
        return new JsonParser().parse(json).getAsJsonObject().getAsJsonObject("tip");
    }

    static String withTip(String json, Consumer<JsonObject> edit) {
        JsonObject o = new JsonParser().parse(json).getAsJsonObject();
        edit.accept(o);
        return new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(o);
    }

    /** A SIM machine whose settings file is {@code json}, the dispense service started on it. */
    static DispenseService start(SimMachine sim, String json) throws Exception {
        File f = DispenseConfig.fileFor(sim.configDir);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
        return DispenseService.start(Configuration.get());
    }

    static File[] v3Backups(File configDir) {
        File[] b = DispenseConfig.fileFor(configDir).getParentFile()
                .listFiles((d, n) -> n.matches("dispense-config\\.json\\.v3-\\d{8}-\\d{6}(-\\d+)?\\.bak"));
        return b == null ? new File[0] : b;
    }

    @Test
    public void aRealSchemaThreeFileIsReconciledWithoutANewVersion() throws Exception {
        String json = fixture();
        assertEquals(Tip.OLD_SEED_NAME, tipOf(json).get("name").getAsString(), "TipSpec's old default");
        try (SimMachine sim = new SimMachine(false)) {
            DispenseService svc = start(sim, json);
            try {
                DispenseConfig c = svc.getConfig();
                assertEquals(1, svc.getDb().tipSetupHistory(Tip.SEED_KEY).size(), "the ID, OD and smallest dot agree");
                assertEquals(Tip.SEED_NAME, c.tip.name);
                // the old name is the seed's: no new version, no event, and the file names the seed as it is now
                assertEquals(Tip.SEED_NAME, svc.getDb().currentTipSetup(Tip.SEED_KEY).name);
                assertTrue(svc.getDb().query("SELECT * FROM events WHERE code = 'TIP'").isEmpty());
                assertEquals(Tip.SEED_NAME, tipOf(new String(Files.readAllBytes(DispenseConfig.fileFor(sim.configDir)
                        .toPath()), StandardCharsets.UTF_8)).get("name").getAsString());
                assertEquals(1, c.tip.setupVersion);
                assertEquals(TipSetup.MODEL_BENCH, c.tip.model);
                assertEquals(TipSetup.FORM_BENT, c.tip.form);
                assertEquals(3000, c.travelAccelMmS2, 0, "a schema-3 file keeps its tuned motion");
                assertTrue(svc.mountedTip().benchModel);
                // the first start: a seed row, and the landing kept from before the update (the change was finished)
                List<TipSetups.Mount> m = svc.getDb().tipMounts(10);
                assertEquals(1, m.size());
                assertEquals(TipSetups.Mount.SEED, m.get(0).kind);
                assertEquals("in use before the update", m.get(0).why);
                assertNotNull(c.needleLanding);
                assertEquals(0, c.needleLanding.dxMm, 0);
                assertEquals("kept from before the update (boards ran with it)", c.needleLanding.note);
                assertEquals(1793300100000L, (long) c.needleLanding.setAtMs, "the touch-off's time");
                double[] ho = svc.headOffsetNow();
                assertEquals(ho[0], c.needleLanding.headOffsetXMm, 1e-9);
                assertTrue(svc.isNeedleXyChecked());
                // the first save kept the file as it was
                File[] b = v3Backups(sim.configDir);
                assertEquals(1, b.length);
                assertEquals(json, new String(Files.readAllBytes(b[0].toPath()), StandardCharsets.UTF_8));
                assertFalse(DispenseConfig.isBeforeTipSetups(new String(Files.readAllBytes(DispenseConfig.fileFor(
                        sim.configDir).toPath()), StandardCharsets.UTF_8)));
            }
            finally {
                svc.shutdown();
            }
            // a second start changes nothing: no row, no backup, the landing as it was
            DispenseService again = DispenseService.start(Configuration.get());
            try {
                assertEquals(1, again.getDb().tipMounts(10).size());
                assertEquals(1, v3Backups(sim.configDir).length);
                assertEquals(1, again.getDb().tipSetupHistory(Tip.SEED_KEY).size());
                assertTrue(again.isNeedleXyChecked());
            }
            finally {
                again.shutdown();
            }
        }
    }

    @Test
    public void anotherOdInTheSettingsFileIsANewVersionOfTheSeed() throws Exception {
        String json = withTip(fixture(), o -> o.getAsJsonObject("tip").addProperty("odMm", 0.80));
        try (SimMachine sim = new SimMachine(false)) {
            DispenseService svc = start(sim, json);
            try {
                List<TipSetup> h = svc.getDb().tipSetupHistory(Tip.SEED_KEY);
                assertEquals(2, h.size());
                TipSetup v2 = h.get(1);
                assertEquals(Tip.SEED_NAME, v2.name);
                assertEquals(0.80, v2.odMm, 0);
                assertEquals(TipSetup.OD_ASSUMED, v2.odSource);
                assertEquals("ADL AD22TTA-B", v2.tipModel);
                assertEquals(TipSetup.MODEL_BENCH, v2.model);
                assertEquals("from the settings file at the update", v2.why);
                assertEquals(2, svc.getConfig().tip.setupVersion);
                assertEquals(0.80, svc.getConfig().tip.odMm, 0);
            }
            finally {
                svc.shutdown();
            }
        }
    }

    @Test
    public void aSeedStillNamedTheOldWayIsRenamedAtStartAndNeverAgain() throws Exception {
        // a database migrated while the seed was "22G tip" (§22.1): the start appends its new name, once; a name the
        // operator gave it stays
        String json = fixture();
        try (SimMachine sim = new SimMachine(false)) {
            DispenseService svc = start(sim, json);
            TipSetup old = svc.getDb().currentTipSetup(Tip.SEED_KEY).copy();
            old.name = Tip.OLD_SEED_NAME;
            old.operator = "seed";
            svc.getDb().insertTipSetupVersion(old);
            svc.shutdown();
            svc = DispenseService.start(Configuration.get());
            try {
                TipSetup now = svc.getDb().currentTipSetup(Tip.SEED_KEY);
                assertEquals(Tip.SEED_NAME, now.name);
                assertEquals("the seed's name at the update", now.why);
                assertEquals(Tip.SEED_NAME, svc.getConfig().tip.name);
                int versions = svc.getDb().tipSetupHistory(Tip.SEED_KEY).size();
                TipSetup mine = now.copy();
                mine.name = Tip.OLD_SEED_NAME;
                mine.operator = "tester";
                svc.getDb().insertTipSetupVersion(mine);
                svc.shutdown();
                svc = DispenseService.start(Configuration.get());
                assertEquals(Tip.OLD_SEED_NAME, svc.getDb().currentTipSetup(Tip.SEED_KEY).name, "the operator's name");
                assertEquals(versions + 1, svc.getDb().tipSetupHistory(Tip.SEED_KEY).size());
            }
            finally {
                svc.shutdown();
            }
        }
    }

    @Test
    public void anUnknownKeyBecomesASetup() throws Exception {
        String json = withTip(fixture(), o -> {
            o.getAsJsonObject("tip").addProperty("key", "x30");
            o.getAsJsonObject("tip").addProperty("name", "30G straight");
            o.getAsJsonObject("tip").addProperty("idMm", 0.15);
            o.getAsJsonObject("tip").addProperty("odMm", 0.31);
        });
        try (SimMachine sim = new SimMachine(false)) {
            DispenseService svc = start(sim, json);
            try {
                TipSetup s = svc.getDb().currentTipSetup("x30");
                assertNotNull(s);
                assertEquals("30G straight", s.name);
                assertEquals(0.15, s.idMm, 0);
                assertEquals(TipSetup.FORM_UNKNOWN, s.form);
                assertEquals(TipSetup.TAPER_UNKNOWN, s.taper);
                assertFalse(s.isComplete());
                assertEquals("x30", svc.getConfig().tip.key);
                assertFalse(svc.mountedTip().benchModel, "only the seed has the bench model");
                assertFalse(svc.getDb().query("SELECT * FROM events WHERE code = 'TIP' AND message LIKE '%x30%'")
                        .isEmpty());
                assertEquals(TipSetups.Mount.SEED, svc.getDb().tipMounts(1).get(0).kind);
            }
            finally {
                svc.shutdown();
            }
        }
    }

    @Test
    public void aSettingsFileTheReconcileCannotTakeStillStartsTheEngine() throws Exception {
        // a tip name longer than a setup's 40 characters is cut
        String json = withTip(fixture(), o -> {
            o.getAsJsonObject("tip").addProperty("key", "x99");
            o.getAsJsonObject("tip").addProperty("name", "a 25G straight steel tip that someone named at length");
        });
        try (SimMachine sim = new SimMachine(false)) {
            DispenseService svc = start(sim, json);
            try {
                TipSetup s = svc.getDb().currentTipSetup("x99");
                assertTrue(s.name.length() <= TipSetup.MAX_NAME && s.name.endsWith("…"), s.name);
                assertEquals(s.name, svc.getConfig().tip.name);
            }
            finally {
                svc.shutdown();
            }
        }
        // a bore the settings allowed and a setup does not: the reconcile fails, the settings stay as they are and the
        // engine starts (the next start tries again)
        String odd = withTip(fixture(), o -> {
            o.getAsJsonObject("tip").addProperty("key", "x98");
            o.getAsJsonObject("tip").addProperty("idMm", 0.04);
        });
        try (SimMachine sim = new SimMachine(false)) {
            DispenseService svc = start(sim, odd);
            try {
                assertEquals("x98", svc.getConfig().tip.key);
                assertNull(svc.getDb().currentTipSetup("x98"));
                assertFalse(svc.mountedTip().benchModel, "only the seeded setup has the bench model");
                assertFalse(svc.getDb().query("SELECT * FROM events WHERE code = 'TIP' AND message LIKE 'the tip setup "
                        + "reconcile at start failed%'").isEmpty());
            }
            finally {
                svc.shutdown();
            }
        }
    }

    @Test
    public void theLandingOfAnOpenNeedleChangeIsStillToCheck() throws Exception {
        // the needle was changed after the touch-off: the change was not finished at the update
        String json = withTip(fixture(), o -> o.addProperty("needleChangedAtMs", 1793300150000L));
        try (SimMachine sim = new SimMachine(false)) {
            DispenseService svc = start(sim, json);
            try {
                assertNull(svc.getConfig().needleLanding);
                assertFalse(svc.isNeedleXyChecked());
                assertTrue(svc.needleXyProblem().contains("where it lands has not been checked"),
                        svc.needleXyProblem());
            }
            finally {
                svc.shutdown();
            }
        }
    }
}
