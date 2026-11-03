package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Tip;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tip setups through the API on the SIM machine (PROFILE_CALIBRATION_DESIGN.md §22.3, §22.6, §22.8): the library (add
 * with its recipe from a setup or the defaults, add and mount, list, edit with another needle refused and a correction
 * in two presses, retire), a mount (its response, what went stale, status, the plan made before it stale, the needle
 * readiness), a refused mount that still records the needle change, the tip that PUT /api/config cannot change, the
 * pad types per setup with the others' versions, a copy from another setup, a mount back that finds its types as they
 * were, "Made with another tip" and the undo of a mount.
 */
public class TipSetupApiSimTest {
    TipSim t;

    @BeforeEach
    public void before() throws Exception {
        t = new TipSim();
        t.bench();
    }

    @AfterEach
    public void after() throws Exception {
        t.close();
    }

    @Test
    public void theLibraryAddsEditsAndRetiresTipSetups() throws Exception {
        JsonObject list = TipSim.ok(t.get("/api/tip-setups"));
        assertEquals(TipSim.SEED, TipSim.str(list, "mounted"));
        assertEquals(1, list.getAsJsonArray("setups").size());
        JsonObject seed = list.getAsJsonArray("setups").get(0).getAsJsonObject();
        assertEquals(Tip.SEED_NAME, TipSim.str(seed, "name"));
        assertEquals("Vieweg DV-5005", TipSim.str(seed, "dispenser"));
        assertEquals("bench-22g", TipSim.str(seed, "model"));
        assertTrue(seed.get("complete").getAsBoolean());
        assertTrue(seed.get("mounted").getAsBoolean());
        assertEquals("bench", TipSim.str(seed.getAsJsonObject("dots"), "source"));
        assertEquals(2.11, seed.getAsJsonObject("dots").get("mgPerTurn").getAsDouble(), 1e-9);
        assertFalse(seed.getAsJsonObject("editable").get("identity").getAsBoolean(), "the seed has records");

        // Add a tip: its recipe a copy of the defaults, named for the setup; nothing measured
        JsonObject added = t.add25();
        assertEquals(TipSim.K25, TipSim.str(added, "key"));
        assertFalse(added.get("mounted").getAsBoolean());
        assertEquals("18 psi half turn · 25G straight", TipSim.str(added.getAsJsonObject("profile"), "name"));
        assertTrue(added.getAsJsonObject("profile").get("parked").getAsBoolean());
        assertEquals("none", TipSim.str(added.getAsJsonObject("dots"), "source"));
        assertEquals("DSKIT1 25G · 25G · straight · straight steel · 12.7 mm long · ID 0.25 mm · OD 0.51 mm (from the "
                + "chart)", TipSim.str(added, "summary"));
        TipSim.refused(t.post("/api/tip-setups", TipSim.ADD_25), 409, "exists");
        TipSim.refused(t.post("/api/tip-setups", TipSim.ADD_25.replace("25G straight\"", "25G other\"")
                .replace("{", "{\"key\":\"undo-mount\",")), 400, "bad-request");
        assertEquals(2, TipSim.ok(t.get("/api/tip-setups")).getAsJsonArray("setups").size());
        // its recipe from the mounted setup's, by default
        JsonObject from22 = TipSim.ok(t.post("/api/tip-setups", "{\"name\":\"27G straight\",\"gauge\":27,"
                + "\"idMm\":0.20,\"odMm\":0.41,\"form\":\"straight\",\"taper\":\"straight-steel\"}"));
        assertEquals("27g-straight", TipSim.str(from22.getAsJsonObject("setup"), "key"));
        assertEquals("Added. It has no pad profiles yet.", TipSim.str(from22, "note"));
        assertEquals(TipSim.SEED, TipSim.str(from22.getAsJsonObject("setup").getAsJsonObject("profile"), "from"));

        // another needle is another setup once a setup has records; a correction of the same needle takes two presses
        JsonObject another = TipSim.refused(t.call("PUT", "/api/tip-setups/" + TipSim.SEED, "{\"gauge\":25}"), 409,
                "another-needle");
        assertEquals("This makes it another needle: add a tip instead (Add a tip…, starting from this one).",
                TipSim.str(another, "reason"));
        JsonObject corr = TipSim.refused(t.call("PUT", "/api/tip-setups/" + TipSim.SEED, "{\"idMm\":0.42}"), 409,
                "correction");
        assertTrue(TipSim.str(corr, "reason").startsWith("Correcting the " + Tip.SEED_NAME + "'s details (ID 0.41 → 0.42 "
                + "mm)?"), corr.toString());
        JsonObject fixed = TipSim.ok(t.call("PUT", "/api/tip-setups/" + TipSim.SEED, "{\"idMm\":0.42,\"correction\":"
                + "true}"));
        assertEquals(2, fixed.get("version").getAsInt());
        assertEquals(0.42, t.svc.getConfig().tip.idMm, 1e-9, "the mounted setup's view is refreshed");
        TipSim.refused(t.call("PUT", "/api/tip-setups/" + TipSim.SEED, "{\"idMm\":0.50,\"correction\":true}"), 409,
                "another-needle");
        // a plain edit (the OD, the bend's direction) needs no second press
        assertEquals(0.75, TipSim.ok(t.call("PUT", "/api/tip-setups/" + TipSim.SEED, "{\"odMm\":0.75}")).get("odMm")
                .getAsDouble(), 1e-9);

        // the tip is the mounted setup: the settings cannot change it
        JsonObject tip = TipSim.refused(t.call("PUT", "/api/config", "{\"tip\":{\"key\":\"25g-straight\"}}"), 400,
                "bad-request");
        assertEquals("The tip is the mounted tip setup: change it in Setup › Dispenser & tip.", TipSim.str(tip,
                "reason"));

        // retire and bring back: a retired setup is listed only when asked; the mounted one stays
        JsonObject retired = TipSim.ok(t.post("/api/tip-setups/" + TipSim.K25 + "/retire", "{}"));
        assertTrue(retired.get("retired").getAsBoolean());
        assertEquals(2, TipSim.ok(t.get("/api/tip-setups")).getAsJsonArray("setups").size());
        assertEquals(3, TipSim.ok(t.get("/api/tip-setups?retired=true")).getAsJsonArray("setups").size());
        assertEquals("The mounted tip cannot be retired.", TipSim.str(TipSim.refused(t.post("/api/tip-setups/"
                + TipSim.SEED + "/retire", "{}"), 409, "refused"), "reason"));
        assertFalse(TipSim.ok(t.post("/api/tip-setups/" + TipSim.K25 + "/unretire", "{}")).get("retired")
                .getAsBoolean());
        TipSim.refused(t.call("PUT", "/api/tip-setups/nope", "{\"odMm\":1}"), 404, "not-found");

        // Add and mount: the tip is on the dispenser now
        TipSim.Res both = t.post("/api/tip-setups", "{\"name\":\"18G straight taper\",\"gauge\":18,\"idMm\":0.84,"
                + "\"odMm\":1.27,\"form\":\"straight\",\"taper\":\"tapered-plastic\",\"mount\":true}");
        assertEquals(201, both.status, both.toString());
        JsonObject mounted = both.json().getAsJsonObject("mount");
        assertEquals("18g-taper", TipSim.str(mounted, "mounted"), mounted.toString());
        assertTrue(both.json().getAsJsonObject("setup").get("mounted").getAsBoolean());
        assertEquals("18g-taper", TipSim.str(t.dispense().getAsJsonObject("tip"), "key"));
    }

    @Test
    public void aMountSwitchesTheSetupAndAMountBackFindsItsTypes() throws Exception {
        // chip-0603 characterized for the 22G (by hand: the bench measured the profile's half turn)
        TipSim.ok(t.call("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\","
                + "\"dotSteps\":800,\"dotsPerPad\":1}"));
        JsonObject chip22 = t.typeRow("chip-0603");
        assertEquals("characterized", TipSim.str(chip22, "status"));
        int v22 = chip22.get("version").getAsInt();
        File board = t.writeBoard();
        JsonObject plan = t.plan(board);
        String planId = TipSim.str(plan, "planId");
        assertEquals(TipSim.SEED, TipSim.str(plan, "setupKey"), plan.toString());
        t.add25();

        // a refused mount still records the needle change: nothing dispenses until the setup is mounted
        TipSim.ok(t.post("/api/tip-setups/" + TipSim.K25 + "/retire", "{}"));
        Long before = t.token();
        JsonObject no = TipSim.refused(t.post("/api/tip-setups/" + TipSim.K25 + "/mount", "{}"), 409, "refused");
        assertEquals("The 25G straight is retired: bring it back first (Change tip › Show retired tips). The needle "
                + "change is recorded: nothing dispenses until the 25G straight is mounted and its needle Z frame "
                + "recorded.", TipSim.str(no, "reason"));
        assertNotNull(no.getAsJsonObject("needleChange"), no.toString());
        Long changed = t.token();
        assertNotNull(changed);
        assertTrue(before == null || changed > before);
        assertEquals(TipSim.SEED, TipSim.str(t.dispense().getAsJsonObject("tip"), "key"), "the 22G stays mounted");
        assertFalse(t.dispense().getAsJsonObject("needle").get("anchored").getAsBoolean(), "its frame is stale");
        TipSim.ok(t.post("/api/tip-setups/" + TipSim.K25 + "/unretire", "{}"));
        // the mount preview says what goes stale and what stays
        JsonObject preview = TipSim.ok(t.get("/api/tip-setups/" + TipSim.K25 + "/mount-preview"));
        assertTrue(TipSim.str(preview, "text").startsWith("Nothing dispenses until you record the 25G straight's "
                + "needle Z frame (Both tips touch, or touch off) and check where it lands. The " + Tip.SEED_NAME
                + " keeps its 1 calibrated pad type; the 25G straight has none yet."), preview.toString());
        assertNull(TipSim.str(preview, "refusal"));

        // the mount: (a) the needle change, (b) the switch
        JsonObject m = t.mount(TipSim.K25);
        assertEquals(TipSim.K25, TipSim.str(m, "mounted"));
        assertEquals(TipSim.SEED, TipSim.str(m, "from"));
        assertTrue(TipSim.str(m, "text").startsWith("25G straight mounted at "), m.toString());
        assertTrue(TipSim.str(m, "text").contains(". Next: record its needle Z frame (Setup › Needle Z frame)."));
        JsonArray stale = m.getAsJsonArray("stale");
        assertEquals("[\"needle Z frame\",\"needle Z offset\",\"where it lands\",\"plans\",\"regulator reading\","
                + "\"prime\"]", stale.toString());
        assertTrue(lng(m, "undoUntilMs") > System.currentTimeMillis());
        assertEquals("18 psi half turn · 25G straight", TipSim.str(m.getAsJsonObject("profile"), "name"));
        assertEquals("frame", TipSim.str(m.getAsJsonArray("next").get(0).getAsJsonObject(), "key"));
        JsonObject d = t.dispense();
        assertEquals(TipSim.K25, TipSim.str(d.getAsJsonObject("tip"), "key"));
        assertEquals("25G straight", TipSim.str(d.getAsJsonObject("tip"), "name"));
        assertEquals("none", TipSim.str(d.getAsJsonObject("tip"), "dots"));
        assertNotNull(lng(d.getAsJsonObject("tip"), "undoUntilMs"));
        assertFalse(d.getAsJsonObject("needle").get("anchored").getAsBoolean(), "a new tip has no frame");
        assertFalse(d.getAsJsonObject("needle").get("xyChecked").getAsBoolean(), "nor a landing");
        // the plan made with the 22G is stale
        String v = TipSim.violations(TipSim.ok(t.get("/api/plan/" + planId)));
        assertTrue(v.contains("PLAN: the tip changed after planning (the 25G straight was mounted at "), v);

        // the pad types as the 25G sees them: chip-0603 a stand-in, the 22G's version among the others
        JsonObject row = t.typeRow("chip-0603");
        assertEquals("not-started", TipSim.str(row, "status"));
        assertTrue(row.get("standIn").getAsBoolean());
        assertEquals(0, row.get("version").getAsInt());
        assertEquals(v22 + 1, row.get("nextVersion").getAsInt());
        JsonObject other = row.getAsJsonArray("others").get(0).getAsJsonObject();
        assertEquals(TipSim.SEED, TipSim.str(other, "setupKey"));
        assertEquals("characterized", TipSim.str(other, "status"));
        assertEquals(v22, other.get("version").getAsInt());
        assertEquals(TipSim.K25, TipSim.str(TipSim.ok(t.get("/api/pad-types")).getAsJsonObject("forSetup"), "key"));
        // a write for the 22G is refused while the 25G is on
        TipSim.refused(t.call("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\",\"notes\":\"x\"}"), 409,
                "tip-changed");
        // a copy of the 22G's plan: provisional, nothing measured with the 25G
        JsonObject copy = TipSim.ok(t.post("/api/pad-types/chip-0603/copy", "{\"fromSetup\":\"ad22tta-b\","
                + "\"forSetup\":\"25g-straight\"}"));
        JsonObject copied = copy.getAsJsonObject("padType");
        assertEquals("provisional", TipSim.str(copied, "status"));
        assertEquals("copied", TipSim.str(copied, "origin"));
        assertEquals(800, copied.get("dotSteps").getAsInt());
        assertTrue(TipSim.str(copied, "notes").startsWith("Copied from the " + Tip.SEED_NAME + " (v" + v22
                + ", characterized) as a starting point: nothing was measured with the 25G straight."), copied.toString());
        int v25 = copied.get("version").getAsInt();

        // the mount back: chip-0603 is the 22G's version again, nothing new written; its frame is to do again
        JsonObject back = t.mount(TipSim.SEED);
        assertEquals(TipSim.SEED, TipSim.str(back, "mounted"));
        JsonObject again = t.typeRow("chip-0603");
        assertEquals("characterized", TipSim.str(again, "status"));
        assertEquals(v22, again.get("version").getAsInt());
        assertEquals(v25, TipSim.ok(t.get("/api/pad-types/chip-0603")).getAsJsonObject("bySetup").getAsJsonObject(
                TipSim.K25).get("version").getAsInt());
        assertFalse(t.dispense().getAsJsonObject("needle").get("anchored").getAsBoolean());

        // the undo of a mount within 2 min: the 25G again, then the undo puts the 22G back exactly
        t.frame();
        Long token22 = t.token();
        t.mount(TipSim.K25);
        JsonObject undone = TipSim.ok(t.post("/api/tip-setups/undo-mount", "{\"operator\":\"tester\"}"));
        assertEquals(TipSim.SEED, TipSim.str(undone, "mounted"));
        assertTrue(TipSim.str(undone, "text").contains("never left the dispenser"), undone.toString());
        assertEquals(token22, t.token(), "the needle token from before the mount");
        assertTrue(t.dispense().getAsJsonObject("needle").get("anchored").getAsBoolean(), "its frame is back");
        JsonObject late = TipSim.refused(t.post("/api/tip-setups/undo-mount", "{}"), 409, "too-late");
        assertTrue(TipSim.str(late, "reason").startsWith("Nothing to undo"), late.toString());
    }

    @Test
    public void aCorrectionOfTheMountedSetupMakesThePlanStale() throws Exception {
        t.add25();
        t.mount(TipSim.K25);
        t.frame();
        JsonObject plan = t.plan(t.writeBoard());
        assertEquals(TipSim.K25, TipSim.str(plan, "setupKey"));
        assertEquals(1, plan.get("setupVersion").getAsInt());
        // a plain edit of the OD: a new version, the planned bridging and pitch no longer hold
        JsonObject s = TipSim.ok(t.call("PUT", "/api/tip-setups/" + TipSim.K25, "{\"odMm\":0.55}"));
        assertEquals(2, s.get("version").getAsInt());
        assertEquals(0.55, t.svc.getConfig().tip.odMm, 1e-9);
        String v = TipSim.violations(TipSim.ok(t.get("/api/plan/" + TipSim.str(plan, "planId"))));
        assertTrue(v.contains("PLAN: the 25G straight changed after planning (OD 0.51 → 0.55 mm): plan again"), v);
    }

    @Test
    public void madeWithAnotherTipRestoresThePreviousVersion() throws Exception {
        JsonObject before = t.typeRow("chip-0805");
        TipSim.ok(t.call("PUT", "/api/pad-types/chip-0805", "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\","
                + "\"dotSteps\":800,\"dotsPerPad\":2}"));
        JsonArray rows = TipSim.okArray(t.get("/api/pad-calibrations?typeKey=chip-0805"));
        assertEquals(1, rows.size());
        JsonObject rec = rows.get(0).getAsJsonObject();
        assertEquals("by-hand", TipSim.str(rec, "method"));
        assertEquals(TipSim.SEED, TipSim.str(rec, "setupKey"));
        assertEquals(Tip.SEED_NAME, TipSim.str(rec, "setupName"));
        assertTrue(rec.get("labelAuto").getAsBoolean());
        assertEquals("[]", rec.getAsJsonArray("flags").toString());
        // a dose record of the record's job: the flag takes it out of the evidence and the dot table
        long id = rec.get("id").getAsLong();
        JsonObject wrong = TipSim.ok(t.post("/api/pad-calibrations/" + id + "/wrong-tip", "{\"operator\":\"tester\"}"));
        assertEquals(TipSim.str(rec, "jobId"), TipSim.str(wrong, "flagged"));
        assertEquals(1, wrong.getAsJsonArray("restored").size(), wrong.toString());
        JsonObject after = t.typeRow("chip-0805");
        assertEquals(TipSim.str(before, "status"), TipSim.str(after, "status"), "the previous version's dose fields");
        assertTrue(TipSim.str(after, "notes").startsWith("restored: calibration " + id + " was made with another tip"),
                after.toString());
        JsonObject flagged = TipSim.okArray(t.get("/api/pad-calibrations?setup=ad22tta-b")).get(0).getAsJsonObject();
        assertEquals("[\"wrong-tip\"]", flagged.getAsJsonArray("flags").toString());
        assertEquals(0, TipSim.okArray(t.get("/api/pad-calibrations?setup=25g-straight")).size());
        assertTrue(t.svc.getDb().flaggedJobs().contains(TipSim.str(rec, "jobId")));
        TipSim.refused(t.post("/api/pad-calibrations/999999/wrong-tip", "{}"), 404, "not-found");
    }

    static Long lng(JsonObject o, String k) {
        return TipSim.lng(o, k);
    }
}
