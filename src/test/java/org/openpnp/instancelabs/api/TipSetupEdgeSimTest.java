package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Tip;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Tip setups through the API as an operator meets them on the SIM machine (PROFILE_CALIBRATION_DESIGN.md §22.3-22.6):
 * the needle tokens and their refusals, "I changed the needle" during a run and its I4 afterwards, the settings
 * editor's round trip and the stamped settings, a raise by hand made with a pad another tab changed meanwhile, a
 * label that never makes a plan stale, the undo's refusals, and pictures sent many at once or not as pictures.
 */
public class TipSetupEdgeSimTest {
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
    public void everyNeedleValueNamesItsNeedle() throws Exception {
        Long token = t.token();
        // missing: 400, with where the token comes from
        JsonObject missing = TipSim.refused(t.post("/api/needle/anchor", "{\"surfaceZ\":5.5,\"needleContactZ\":2.91}"),
                400, "bad-request");
        assertTrue(TipSim.str(missing, "reason").startsWith("'needleChangedAtMs' is required"), missing.toString());
        TipSim.refused(t.post("/api/needle/offset", "{\"offsetMm\":-2.59}"), 400, "bad-request");
        TipSim.refused(t.post("/api/needle/xy-trim", "{\"dxMm\":0.1,\"dyMm\":0}"), 400, "bad-request");
        TipSim.refused(t.post("/api/needle/landing", "{\"keep\":true}"), 400, "bad-request");

        // taken before a needle change: 409 needle-changed, nothing saved
        TipSim.ok(t.post("/api/needle/changed", "{\"why\":\"a new 22G\"}"));
        assertFalse(t.dispense().getAsJsonObject("needle").get("anchored").getAsBoolean());
        JsonObject stale = TipSim.refused(t.post("/api/needle/anchor", "{\"surfaceZ\":5.5,\"needleContactZ\":2.91,"
                + "\"needleChangedAtMs\":" + TipSim.js(token) + "}"), 409, "needle-changed");
        assertTrue(TipSim.str(stale, "reason").matches("The needle was changed at \\d\\d:\\d\\d after these values "
                + "were taken: take them again\\."), stale.toString());
        assertFalse(t.dispense().getAsJsonObject("needle").get("anchored").getAsBoolean(), "nothing saved");
        TipSim.refused(t.post("/api/needle/offset", "{\"offsetMm\":-2.59,\"needleChangedAtMs\":" + TipSim.js(token)
                + "}"), 409, "needle-changed");
        TipSim.refused(t.post("/api/needle/xy-trim", "{\"dxMm\":0.1,\"dyMm\":0,\"needleChangedAtMs\":"
                + TipSim.js(token) + "}"), 409, "needle-changed");

        // the trim: at most 1 mm, with the text that says what to do instead
        JsonObject far = TipSim.refused(t.post("/api/needle/xy-trim", "{\"dxMm\":1.5,\"dyMm\":0,\"needleChangedAtMs\":"
                + TipSim.js(t.token()) + "}"), 400, "bad-request");
        assertEquals("The trim is at most ±1 mm on each axis: a needle that lands farther off needs its landing "
                + "checked (Setup › Dispenser & tip › Check where it lands).", TipSim.str(far, "reason"));
        JsonObject trim = TipSim.ok(t.post("/api/needle/xy-trim", "{\"dxMm\":0.2,\"dyMm\":-0.1,\"note\":\"hand\","
                + "\"needleChangedAtMs\":" + TipSim.js(t.token()) + "}"));
        assertEquals(0.2, trim.getAsJsonObject("needleXyTrim").get("dxMm").getAsDouble(), 1e-9);

        // keep where it lands: refused for the bent 22G with the reason; neither keep nor accept: 400
        JsonObject bent = TipSim.refused(t.post("/api/needle/landing", "{\"keep\":true,\"needleChangedAtMs\":"
                + TipSim.js(t.token()) + "}"), 409, "refused");
        assertEquals("A bent tip lands where its bend points: check where it lands.", TipSim.str(bent, "reason"));
        TipSim.refused(t.post("/api/needle/landing", "{\"needleChangedAtMs\":" + TipSim.js(t.token()) + "}"), 400,
                "bad-request");
        JsonObject noJob = TipSim.refused(t.post("/api/needle/landing", "{\"jobId\":\"pc-999\",\"accept\":true,"
                + "\"needleChangedAtMs\":" + TipSim.js(t.token()) + "}"), 404, "not-found");
        assertTrue(TipSim.str(noJob, "reason").contains("check where it lands again"), noJob.toString());
        JsonObject status = t.dispense().getAsJsonObject("needle");
        assertFalse(status.get("xyChecked").getAsBoolean(), status.toString());
        assertFalse(status.getAsJsonObject("landing").get("keepable").getAsBoolean());
        assertEquals("A bent tip lands where its bend points: check where it lands.", TipSim.str(status
                .getAsJsonObject("landing"), "keepWhy"));
    }

    @Test
    public void aNeedleChangeDuringARunStopsItAndI4HoldsAfterIt() throws Exception {
        // a long run of shots at the waste (the SIM controller dwells for real, so the run lasts)
        t.sim.server.realDwell = true;
        TipSim.Res shots = t.post("/api/calibrations/shots-run", "{\"locationName\":\"waste\",\"rotationDeg\":360,"
                + "\"count\":200}");
        assertEquals(202, shots.status, shots.toString());
        long until = System.currentTimeMillis() + 30_000;
        while (!t.dispense().get("busy").getAsBoolean() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertTrue(t.dispense().get("busy").getAsBoolean(), "the shots run");
        JsonObject nc = TipSim.ok(t.post("/api/needle/changed", "{\"why\":\"the needle came loose\"}"));
        assertTrue(nc.get("needleChanged").getAsBoolean());
        assertNotNull(TipSim.str(nc.getAsJsonObject("needleChange").getAsJsonObject("voided"), "task"), nc.toString());
        until = System.currentTimeMillis() + 30_000;
        while (t.dispense().get("busy").getAsBoolean() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertFalse(t.dispense().get("busy").getAsBoolean(), "the run was stopped");
        t.sim.server.realDwell = false;
        JsonObject last = TipSim.ok(t.get("/api/runs/last")).getAsJsonObject("run");
        assertEquals("ABORTED", TipSim.str(last, "status"), last.toString());

        // the needle change stopped the run as STOP does: home; then I4 until the frame is recorded again
        TipSim.ok(t.post("/api/machine/home", "{\"wait\":true}"));
        JsonObject prime = TipSim.refused(t.post("/api/prime", "{}"), 409, "refused");
        assertTrue(prime.toString().contains("I4"), prime.toString());
        t.frame();
        JsonObject touch = TipSim.refused(t.post("/api/calibrations/touch-off", "{\"startGapMm\":0.3,\"stepMm\":0.05,"
                + "\"steps\":8}"), 409, "refused");
        assertTrue(touch.toString().contains("where it lands has not been checked: check where it lands (Setup › "
                + "Dispenser & tip)."), touch.toString());
        // prime at the waste before the landing check
        JsonObject primed = TipSim.ok(t.post("/api/prime", "{\"wait\":true}"));
        assertEquals("OK", TipSim.str(primed.getAsJsonObject("result"), "status").toUpperCase(java.util.Locale.ROOT),
                primed.toString());
    }

    @Test
    public void theSettingsEditorsRoundTripAndTheStampedSettings() throws Exception {
        // a full round trip of the settings (the editor's Save without a change) goes through with forTip
        JsonObject cfg = TipSim.ok(t.get("/api/config"));
        cfg.addProperty("forTip", TipSim.SEED);
        TipSim.ok(t.call("PUT", "/api/config", cfg.toString()));
        // without forTip, a profile is refused with where it comes from
        JsonObject noTip = TipSim.refused(t.call("PUT", "/api/config", "{\"profile\":{\"dwellMs\":300}}"), 400,
                "bad-request");
        assertTrue(TipSim.str(noTip, "reason").startsWith("'forTip' is required with 'profile'"), noTip.toString());
        // the engine's own settings: each with where it is set
        JsonObject landing = TipSim.refused(t.call("PUT", "/api/config", "{\"needleLanding\":{\"dxMm\":1,\"dyMm\":0}}"),
                400, "bad-request");
        assertEquals("Where the needle lands is set by Check where it lands and the trim panel (POST "
                + "/api/needle/landing, /api/needle/xy-trim).", TipSim.str(landing, "reason"));
        TipSim.refused(t.call("PUT", "/api/config", "{\"needleXyTrim\":{\"dxMm\":0.5}}"), 400, "bad-request");
        JsonObject parked = TipSim.refused(t.call("PUT", "/api/config", "{\"parkedTips\":{\"x\":{}}}"), 400,
                "bad-request");
        assertTrue(TipSim.str(parked, "reason").startsWith("The tips that are not mounted keep their recipe"),
                parked.toString());
        // the round trip made before a mount: the mount's tip and recipe stay
        t.add25();
        JsonObject before = TipSim.ok(t.get("/api/config"));
        before.addProperty("forTip", TipSim.SEED);
        t.mount(TipSim.K25);
        TipSim.refused(t.call("PUT", "/api/config", before.toString()), 409, "tip-changed");
        assertEquals(TipSim.K25, t.svc.getConfig().tip.key);
        // the same round trip read after the mount goes through
        JsonObject after = TipSim.ok(t.get("/api/config"));
        after.addProperty("forTip", TipSim.K25);
        TipSim.ok(t.call("PUT", "/api/config", after.toString()));
    }

    @Test
    public void aRaiseByHandWithAPadChangedMeanwhileIsRefused() throws Exception {
        JsonObject row = t.typeRow("chip-0603");
        int pad = row.get("padVersion").getAsInt();
        double w = row.get("padWMm").getAsDouble();
        double h = row.get("padHMm").getAsDouble();
        // another tab corrects the pad's width
        TipSim.ok(t.call("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\",\"padWMm\":" + (w + 0.1)
                + ",\"padVersion\":" + pad + "}"));
        // this editor, opened before it, raises the status with the pad it showed
        JsonObject stale = TipSim.refused(t.call("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\","
                + "\"status\":\"characterized\",\"padWMm\":" + w + ",\"padHMm\":" + h + ",\"padVersion\":" + pad + "}"),
                409, "pad-changed");
        assertTrue(TipSim.str(stale, "reason").contains("was changed meanwhile (version "), stale.toString());
        assertEquals(w + 0.1, t.typeRow("chip-0603").get("padWMm").getAsDouble(), 1e-9, "the other tab's edit stays");
        assertEquals(0, TipSim.okArray(t.get("/api/pad-calibrations?typeKey=chip-0603")).size(), "no record");
        // a raise that changes only the status takes the pad as it is now
        JsonObject raised = TipSim.ok(t.call("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\","
                + "\"status\":\"characterized\"}"));
        assertEquals("characterized", TipSim.str(raised.getAsJsonObject("padType"), "status"));
        assertEquals(w + 0.1, raised.getAsJsonObject("padType").get("padWMm").getAsDouble(), 1e-9);
        assertNotNull(raised.getAsJsonObject("calibration"), raised.toString());
    }

    @Test
    public void picturesOfAnotherTipOrNeedleAreRefusedBeforeAnythingIsWritten() throws Exception {
        t.add25();
        long theirs = TipSim.ok(t.send("POST", "/api/photos?kind=setup&setup=" + TipSim.K25, PhotoApiSimTest.picture(
                200, 150, "jpg"), "image/jpeg")).getAsJsonObject("photo").get("id").getAsLong();
        String before = TipSim.str(t.typeRow("chip-0805"), "status");
        // a raise by hand naming the 25G's picture: refused, and nothing was raised or recorded
        JsonObject mismatch = TipSim.refused(t.call("PUT", "/api/pad-types/chip-0805", "{\"forSetup\":\"ad22tta-b\","
                + "\"status\":\"characterized\",\"calibration\":{\"photoIds\":[" + theirs + "]}}"), 409,
                "setup-mismatch");
        assertTrue(TipSim.str(mismatch, "reason").startsWith("This picture is of the 25G straight"),
                mismatch.toString());
        assertEquals(before, TipSim.str(t.typeRow("chip-0805"), "status"));
        assertEquals(0, TipSim.okArray(t.get("/api/pad-calibrations?typeKey=chip-0805")).size());
        // with a picture of its own tip it goes through and shows it
        long mine = TipSim.ok(t.send("POST", "/api/photos?kind=tip&setup=" + TipSim.SEED, PhotoApiSimTest.picture(200,
                150, "jpg"), "image/jpeg")).getAsJsonObject("photo").get("id").getAsLong();
        JsonObject raised = TipSim.ok(t.call("PUT", "/api/pad-types/chip-0805", "{\"forSetup\":\"ad22tta-b\","
                + "\"status\":\"characterized\",\"calibration\":{\"photoIds\":[" + mine + "]}}"));
        JsonObject cal = raised.getAsJsonObject("calibration");
        assertTrue(cal.getAsJsonArray("photos").toString().contains("\"id\":" + mine + ","), cal.toString());
        long rec = cal.get("id").getAsLong();

        // a picture from below for that record: linked while it is the same needle; after a needle change refused
        // before the head moves
        PhotoApiSimTest.Camera cam = new PhotoApiSimTest.Camera();
        t.svc.setTipCamera(cam);
        try {
            // the same needle: the picture is linked to the record
            JsonObject from = TipSim.ok(t.post("/api/tip-setups/" + TipSim.SEED + "/picture", "{\"calibrationIds\":["
                    + rec + "],\"wait\":true}")).getAsJsonObject("result");
            assertTrue(from.get("ok").getAsBoolean(), from.toString());
            assertEquals("[" + rec + "]", from.getAsJsonObject("photo").getAsJsonArray("links").toString());
            assertEquals(1, cam.captures);
            TipSim.ok(t.post("/api/needle/changed", "{}"));
            JsonObject needle = TipSim.refused(t.post("/api/tip-setups/" + TipSim.SEED + "/picture",
                    "{\"calibrationIds\":[" + rec + "],\"wait\":true}"), 409, "needle-changed");
            assertTrue(TipSim.str(needle, "reason").startsWith("This picture was taken with another needle than "
                    + "calibration " + rec), needle.toString());
            TipSim.refused(t.post("/api/tip-setups/" + TipSim.SEED + "/picture", "{\"calibrationIds\":[99999]}"), 404,
                    "not-found");
            assertEquals(1, cam.captures, "nothing moved since");
        }
        finally {
            t.svc.setTipCamera(null);
        }
    }

    @Test
    public void aNeedleChangeUnderThePressureHoldIsRecordedAndTheSwitchWaits() throws Exception {
        t.add25();
        assertEquals("on", TipSim.str(TipSim.ok(t.post("/api/pressure/start", null)), "state"));
        Long before = t.token();
        JsonObject nc = TipSim.ok(t.post("/api/needle/changed", "{}"));
        assertTrue(nc.get("needleChanged").getAsBoolean(), "never refused");
        assertTrue(before == null || t.token() > before);
        assertFalse(t.dispense().getAsJsonObject("needle").get("anchored").getAsBoolean(), "I4: the frame is stale");
        String off = "Switch the pressure off first: a needle comes off with the pressure off.";
        assertEquals(off, TipSim.str(TipSim.ok(t.get("/api/tip-setups/" + TipSim.K25 + "/mount-preview")),
                "refusal"));
        JsonObject no = TipSim.refused(t.post("/api/tip-setups/" + TipSim.K25 + "/mount", "{}"), 409, "refused");
        assertEquals(off + " The needle change is recorded: nothing dispenses until the 25G straight is mounted and "
                + "its needle Z frame recorded.", TipSim.str(no, "reason"));
        assertNotNull(no.getAsJsonObject("needleChange"), no.toString());
        assertEquals(TipSim.SEED, t.svc.getConfig().tip.key);
        TipSim.ok(t.post("/api/pressure/stop", null));
        assertEquals(TipSim.K25, TipSim.str(t.mount(TipSim.K25), "mounted"));
    }

    @Test
    public void aLabelNeverMakesAPlanStale() throws Exception {
        TipSim.ok(t.call("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\",\"status\":"
                + "\"characterized\"}"));
        long id = TipSim.okArray(t.get("/api/pad-calibrations?typeKey=chip-0603")).get(0).getAsJsonObject().get("id")
                .getAsLong();
        JsonObject plan = t.plan(t.writeBoard());
        String planId = TipSim.str(plan, "planId");
        String before = TipSim.violations(TipSim.ok(t.get("/api/plan/" + planId)));
        assertFalse(before.contains("PLAN"), before);
        TipSim.ok(t.call("PUT", "/api/pad-calibrations/" + id + "/label", "{\"label\":\"0603 for the bent 22G\"}"));
        TipSim.ok(t.call("PUT", "/api/pad-calibrations/" + id + "/label", "{\"auto\":true}"));
        String after = TipSim.violations(TipSim.ok(t.get("/api/plan/" + planId)));
        assertFalse(after.contains("PLAN"), after);
        // a label of 161 characters, or only control characters: 400 with the limits
        StringBuilder l = new StringBuilder();
        for (int i = 0; i < 161; i++) {
            l.append('x');
        }
        assertEquals("The label must be 1 to 160 characters.", TipSim.str(TipSim.refused(t.call("PUT",
                "/api/pad-calibrations/" + id + "/label", "{\"label\":\"" + l + "\"}"), 400, "label"), "reason"));
        TipSim.refused(t.call("PUT", "/api/pad-calibrations/" + id + "/label", "{\"label\":\"\\u0007\\t\"}"), 400,
                "label");
        TipSim.refused(t.call("PUT", "/api/pad-calibrations/99999/label", "{\"label\":\"x\"}"), 404, "not-found");
    }

    @Test
    public void theLabelOfARecordOfATipThatCameOffNamesItsOwnRecipe() throws Exception {
        t.add25();
        t.mount(TipSim.K25);
        TipSim.ok(t.call("PUT", "/api/config", "{\"forTip\":\"" + TipSim.K25 + "\",\"profile\":{\"pressurePsi\":20}}"));
        // a job the mount stopped writes its records as it ends: they name the 22G, whose recipe is parked
        org.openpnp.instancelabs.dispense.PadCalibrationRecord rec = new org.openpnp.instancelabs.dispense
                .PadCalibrationRecord();
        rec.jobId = "pc-stopped";
        rec.typeKey = "chip-0603";
        rec.setupKey = TipSim.SEED;
        rec.outcome = org.openpnp.instancelabs.dispense.PadCalibrationRecord.STOPPED;
        rec.message = "The tip changed during the calibration: nothing was saved.";
        t.svc.applyPadCalibration(rec, null);
        JsonObject row = TipSim.okArray(t.get("/api/pad-calibrations?setup=" + TipSim.SEED)).get(0).getAsJsonObject();
        assertEquals("Chip 0603 · " + Tip.SEED_NAME + " · Vieweg DV-5005 · 18 psi · Loctite GC50 SAC305 T5",
                TipSim.str(row, "label"), row.toString());
    }

    @Test
    public void theUndoSaysWhyItIsTooLate() throws Exception {
        JsonObject nothing = TipSim.refused(t.post("/api/tip-setups/undo-mount", "{}"), 409, "too-late");
        assertEquals("Nothing to undo: no other tip was mounted in the last 2 min.", TipSim.str(nothing, "reason"));
        t.add25();
        t.mount(TipSim.K25);
        t.frame();
        JsonObject framed = TipSim.refused(t.post("/api/tip-setups/undo-mount", "{}"), 409, "too-late");
        assertEquals("Too late to undo: the frame was recorded. Mount the " + Tip.SEED_NAME + " again instead.",
                TipSim.str(framed, "reason"));
        // a placeholder without its bore cannot be mounted; the needle change is recorded all the same
        Long before = t.token();
        JsonObject added = TipSim.ok(t.post("/api/tip-setups", "{\"name\":\"Footed 19G\",\"gauge\":19,\"idMm\":0.69,"
                + "\"odMm\":1.07,\"form\":\"straight\",\"taper\":\"unknown\"}"));
        String key = TipSim.str(added.getAsJsonObject("setup"), "key");
        JsonObject incomplete = TipSim.refused(t.post("/api/tip-setups/" + key + "/mount", "{}"), 409, "refused");
        assertEquals("The Footed 19G has no taper yet: fill it in first (Edit…). The needle change is recorded: "
                + "nothing dispenses until the Footed 19G is mounted and its needle Z frame recorded.", TipSim.str(
                        incomplete, "reason"));
        assertTrue(t.token() > before, "the needle change is recorded");
        assertEquals(TipSim.K25, t.svc.getConfig().tip.key);
    }

    @Test
    public void picturesSentManyAtOnceOrNotAsPictures() throws Exception {
        String q = "kind=setup&setup=" + TipSim.SEED;
        byte[] jpg = PhotoApiSimTest.picture(400, 300, "jpg");
        // eight at once: each stored or refused busy, never an error
        List<CompletableFuture<TipSim.Res>> all = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            all.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return t.send("POST", "/api/photos?" + q, jpg, "image/jpeg");
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        int stored = 0;
        for (CompletableFuture<TipSim.Res> f : all) {
            TipSim.Res r = f.get();
            assertTrue(r.status == 201 || r.status == 409 && "busy".equals(TipSim.str(r.json(), "error")),
                    r.toString());
            stored += r.status == 201 ? 1 : 0;
        }
        assertTrue(stored >= 1);
        // equal pictures share one file and each keeps its row
        JsonElement listed = TipSim.ok(t.get("/api/tip-setups")).getAsJsonArray("setups").get(0).getAsJsonObject()
                .get("photos");
        assertEquals(stored, listed.getAsJsonArray().size(), listed.toString());

        // a PNG whose header says 20,000 px: refused before it is decoded
        byte[] png = PhotoApiSimTest.picture(100, 100, "png");
        java.nio.ByteBuffer.wrap(png, 16, 8).putInt(20_000).putInt(20_000);
        JsonObject huge = TipSim.refused(t.send("POST", "/api/photos?" + q, png, "image/png"), 400, "size");
        assertTrue(TipSim.str(huge, "reason").startsWith("The picture is 20000 × 20000 px"), huge.toString());
        // within 12,000 px a side but over 40 Mpx: the text names the limit it breaks
        byte[] wide = PhotoApiSimTest.picture(100, 100, "png");
        java.nio.ByteBuffer.wrap(wide, 16, 8).putInt(7_000).putInt(6_000);
        JsonObject mpx = TipSim.refused(t.send("POST", "/api/photos?" + q, wide, "image/png"), 400, "size");
        assertTrue(TipSim.str(mpx, "reason").contains("40"), mpx.toString());
        // JPEG's first bytes and nothing after them; an empty body; ids that are not numbers
        TipSim.refused(t.send("POST", "/api/photos?" + q, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2},
                "image/jpeg"), 400, "not-an-image");
        TipSim.refused(t.send("POST", "/api/photos?" + q, new byte[0], "image/jpeg"), 400, "bad-request");
        TipSim.refused(t.send("POST", "/api/photos?" + q + "&calibrations=4x", jpg, "image/jpeg"), 400, "bad-request");
        TipSim.refused(t.send("POST", "/api/photos?" + q + "&calibrations=99999", jpg, "image/jpeg"), 404,
                "not-found");
        assertNull(TipSim.str(t.dispense().getAsJsonObject("tip"), "error"));
    }
}
