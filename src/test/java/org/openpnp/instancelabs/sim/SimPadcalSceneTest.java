package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DriverGcodeChannel;
import org.openpnp.instancelabs.dispense.PadGeometry;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The pad-calibration blocks of the scene file (§11.3): their format and checks, the weigh coupons as
 * surfaces (N1 seals on their own plane, the camera draws them), the bench blank colour, the SIM
 * camera drawing new paste at once, the SIM balance only on the confirmed simulator, and the
 * negative-control variants (§11.7) as scene changes.
 */
public class SimPadcalSceneTest {
    /** §11.3's blocks verbatim, on a minimal board. */
    static final String EXAMPLE = "{\"version\": 1, \"board\": {\"file\": \"toy.board.xml\", \"seat\": {\"x\": 0, \"y\": 0}},"
            + " \"paste\": {\"mRevMg\": 2.30, \"ripple\": 0.15, \"ripplePeakDeg\": 299, \"cv\": 0.08, \"seed\": 11,"
            + "  \"footprint\": {\"c\": 0.875, \"e\": 0.77},"
            + "  \"transferRatio\": 0.75, \"oozeFirst\": 0.40, \"refillDip\": 0.30, \"refillTauDots\": 2, \"idleFullS\": 300,"
            + "  \"idleDeadS\": 3600, \"threadShare\": 0.5, \"threadFullMg\": 0.35, \"threadZeroMg\": 0.45,"
            + "  \"missBelowMg\": 0.12, \"maxGapMm\": 0.20, \"needleZOffsetMm\": null, \"feedLimitMgS\": 3.0,"
            + "  \"landingSdMm\": 0.03, \"landingBiasMm\": [0, 0], \"n2OffsetMm\": [25.291, -75.926], \"reportCount\": true},"
            + " \"coupons\": [{\"place\": \"coupon1\", \"rect\": [330, 40, 370, 70], \"z\": 5.80, \"bgr\": [206, 25, 0],"
            + "  \"massMg\": 4100},"
            + "  {\"place\": \"coupon2\", \"rect\": [330, 75, 370, 105], \"z\": 5.80, \"bgr\": [206, 25, 0], \"massMg\": 4060}],"
            + " \"balance\": {\"mode\": \"display\", \"resolutionMg\": 1, \"noiseMg\": 0.4, \"settleS\": 2},"
            + " \"blank\": {\"bgr\": [206, 25, 0]}}";
    static final List<PadGeometry> NO_PADS = Collections.emptyList();
    SimMachine sim;
    SimBoardCamera camera;

    @AfterEach
    public void after() throws Exception {
        if (camera != null) {
            camera.close();
        }
        if (sim != null) {
            sim.close();
        }
    }

    static JsonObject json(String s) {
        return new JsonParser().parse(s).getAsJsonObject();
    }

    @Test
    public void parsesTheSpecBlocksAsThePadcalScene() {
        SimScene s = SimScene.of(json(EXAMPLE));
        SimScene.Paste p = s.paste;
        SimScene.Paste d = new SimScene.Paste();
        assertEquals(2.30, p.mRevMg, 0);
        assertEquals(0.15, p.ripple, 0);
        assertEquals(299, p.ripplePeakDeg, 0);
        assertEquals(11, p.seed);
        assertEquals(0.875, p.footprintC, 0);
        assertEquals(0.77, p.footprintE, 0);
        assertNull(p.needleZOffsetMm, "null: the configuration's");
        assertArrayEquals(new double[] {25.291, -75.926}, p.n2OffsetMm, 0);
        assertNull(p.offsetSteps, "drawn per connection");
        // Every number of the example is the model's default.
        assertEquals(new com.google.gson.Gson().toJson(d), new com.google.gson.Gson().toJson(p));
        assertEquals(2, s.coupons.size());
        assertEquals("coupon2", s.coupons.get(1).place);
        assertArrayEquals(new double[] {330, 75, 370, 105}, s.coupons.get(1).rect, 0);
        assertEquals(5.80, s.coupons.get(0).z, 0);
        assertEquals(4060, s.coupons.get(1).massMg, 0);
        assertArrayEquals(new int[] {206, 25, 0}, s.coupons.get(0).bgr);
        assertEquals("display", s.balance.mode);
        assertEquals(1, s.balance.resolutionMg, 0);
        assertEquals(0.4, s.balance.noiseMg, 0);
        assertEquals(2, s.balance.settleS, 0);
        assertArrayEquals(new int[] {206, 25, 0}, s.blankBgr);
        assertArrayEquals(new double[] {280, 225, 365, 250}, s.blankRect, 0, "blank.bgr alone keeps the bench rect");
        // PadcalScenes builds the same scene in code.
        SimScene c = PadcalScenes.padcal(new SimScene(), "display");
        assertEquals(new com.google.gson.Gson().toJson(p), new com.google.gson.Gson().toJson(c.paste));
        assertEquals(new com.google.gson.Gson().toJson(s.coupons), new com.google.gson.Gson().toJson(c.coupons));
        assertEquals(new com.google.gson.Gson().toJson(s.balance), new com.google.gson.Gson().toJson(c.balance));
    }

    @Test
    public void withoutTheBlocksTheSceneIsAsBefore() {
        SimScene s = SimScene.of(json("{\"board\": {\"file\": \"toy.board.xml\", \"seat\": {\"x\": 0, \"y\": 0}}}"));
        assertNull(s.paste);
        assertTrue(s.coupons.isEmpty());
        assertNull(s.balance);
        assertArrayEquals(new int[] {142, 59, 0}, s.blankBgr, "today's blank colour (AR's tests and Playwright)");
        SimScene q = SimScene.of(json("{\"board\": {\"file\": \"t\", \"seat\": {\"x\": 0, \"y\": 0}},"
                + " \"paste\": {\"needleZOffsetMm\": 0.37, \"offsetSteps\": -100}}"));
        assertEquals(0.37, q.paste.needleZOffsetMm, 0);
        assertEquals(1500, (int) q.paste.offsetSteps, "taken mod 1600");
    }

    @Test
    public void badBlocksAreRefused() {
        String board = "{\"board\": {\"file\": \"t\", \"seat\": {\"x\": 0, \"y\": 0}}, ";
        String[] bad = {"\"balance\": {\"mode\": \"serial\"}}", "\"balance\": {\"resolutionMg\": 0}}",
                "\"paste\": {\"transferRatio\": 0}}", "\"paste\": {\"transferRatio\": 1.2}}",
                "\"paste\": {\"threadZeroMg\": 0.3}}", "\"paste\": {\"n2OffsetMm\": [1]}}",
                "\"coupons\": [{\"place\": \"a\", \"rect\": [0, 0, 40, 30], \"z\": 5}]}",
                "\"coupons\": [{\"place\": \"a\", \"rect\": [0, 0, 40, 30], \"z\": 5, \"massMg\": 3000},"
                        + " {\"place\": \"a\", \"rect\": [0, 40, 40, 70], \"z\": 5, \"massMg\": 3000}]}",
                "\"coupons\": [{\"rect\": [0, 0, 40, 30], \"z\": 5, \"massMg\": 3000}]}",
                "\"blank\": {\"bgr\": [300, 0, 0]}}"};
        for (String b : bad) {
            assertThrows(IllegalArgumentException.class, () -> SimScene.of(json(board + b)), b);
        }
    }

    @Test
    public void n1SealsOnACouponsOwnPlane() {
        SimScene s = SimPasteTest.scene();
        s.vacuum.noise = 0;
        s.vacuum.tauS = 0;
        s.vacuum.sealTauS = 0;
        s.vacuum.releaseTauS = 0;
        s.vacuum.sealDrops = new double[] {300000};
        assertEquals(SimScene.Region.COUPON, s.regionAt(350, 55));
        assertEquals("coupon1", s.couponAt(350, 55).place);
        assertEquals(5.80, s.contactZ(SimScene.Region.COUPON, 350, 55), 0);
        assertEquals(SimScene.Region.BED, s.regionAt(350, 72.5), "between the coupons");
        assertEquals(SimScene.Region.BLANK, s.regionAt(300, 240));
        long[] now = {0};
        SimVacuum v = new SimVacuum(() -> s, () -> now[0]);
        double hx = 350 - s.n1OffsetX, hy = 55 - s.n1OffsetY;
        v.onLine("M106", hx, hy, 10);
        v.onLine("M106 P1 S180", hx, hy, 10);
        assertEquals(-1_550_000 - 300_000, SimVacuumTest.read(v, new double[] {hx, hy}, 5.80), "sealed at the coupon's z");
        assertEquals(-1_550_000, SimVacuumTest.read(v, new double[] {hx, hy}, 5.84), "open above it");
        double ex = 330.1 - s.n1OffsetX; // the tip disc over the coupon's edge
        assertEquals(-1_550_000 - s.vacuum.leakDrop, SimVacuumTest.read(v, new double[] {ex, hy}, 5.80),
                "an edge leaks");
    }

    @Test
    public void theRendererDrawsCouponsTheBenchBlankAndPasteColours() {
        SimScene s = SimPasteTest.scene();
        s.camera.noise = 0;
        s.coupons.get(1).bgr = new int[] {10, 200, 30};
        List<SimPaste.Deposit> none = Collections.emptyList();
        BufferedImage img = SimBoardRenderer.render(s, null, NO_PADS, 300, 240, 64, 48, 0.0107, 0.0107, none);
        SimBoardRendererTest.assertColour(SimBoardRenderer.BENCH_BLANK, SimBoardRendererTest.bgr(img, 32, 24), 2,
                "bench blank");
        img = SimBoardRenderer.render(s, null, NO_PADS, 350, 90, 64, 48, 0.0107, 0.0107, none);
        SimBoardRendererTest.assertColour(SimBoardRenderer.bgr(10, 200, 30), SimBoardRendererTest.bgr(img, 32, 24), 2,
                "coupon 2's own colour");
        SimScene plain = SimPasteTest.scene();
        plain.blankBgr = SimScene.DEFAULT_BLANK_BGR.clone();
        plain.camera.noise = 0;
        img = SimBoardRenderer.render(plain, null, NO_PADS, 300, 240, 64, 48, 0.0107, 0.0107, none);
        SimBoardRendererTest.assertColour(SimBoardRenderer.BLANK, SimBoardRendererTest.bgr(img, 32, 24), 2,
                "the default blank");
        // Paste on the blank and coupons in the bench's paste-on-blank colour, on the board in the board paste's.
        SimPaste p = new SimPaste(() -> s, SimPaste.Store.forScene(s), () -> 0L, () -> 0.0);
        SimNeedle n = new SimNeedle(p, s.paste.n2OffsetMm);
        n.pressurize();
        n.dot(300, 240, SimPasteTest.BLANK_Z, 1600);
        n.dot(350, 90, PadcalScenes.COUPON_Z, 1600);
        SimPaste.Deposit onBlank = p.deposits("blank").get(0), onCoupon = p.deposits("coupon2").get(0);
        img = SimBoardRenderer.render(s, null, NO_PADS, onBlank.x, onBlank.y, 64, 48, 0.0107, 0.0107, p.deposits(null));
        SimBoardRendererTest.assertColour(SimBoardRenderer.BLANK_PASTE, SimBoardRendererTest.bgr(img, 32, 24), 2,
                "paste on the blank");
        img = SimBoardRenderer.render(s, null, NO_PADS, onCoupon.x, onCoupon.y, 64, 48, 0.0107, 0.0107, p.deposits(null));
        SimBoardRendererTest.assertColour(SimBoardRenderer.BLANK_PASTE, SimBoardRendererTest.bgr(img, 32, 24), 2,
                "paste on a coupon");
    }

    @Test
    public void theSimCameraDrawsNewPasteAtOnce() throws Exception {
        sim = new SimMachine();
        File f = SimGuard.sceneFile(sim.configDir);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), EXAMPLE.replace("\"blank\": {\"bgr\"", "\"camera\": {\"noise\": 0}, \"blank\": {\"bgr\"")
                .getBytes(StandardCharsets.UTF_8));
        camera = new SimBoardCamera();
        camera.setName("Top");
        camera.setLooking(Camera.Looking.Down);
        camera.setUnitsPerPixel(new Location(LengthUnit.Millimeters, 0.0107, 0.0107, 0, 0));
        camera.setViewWidth(64);
        camera.setViewHeight(48);
        camera.setHeadOffsets(new Location(LengthUnit.Millimeters, 300, 240, 0, 0)); // no axes: it sits there
        sim.machine.getDefaultHead().addCamera(camera);
        sim.server.firmwareName = "GcodeServer (RecordingGcodeServer)";
        SimGuard.noteIdentity(sim.machine, new DriverGcodeChannel(sim.driver).sendAndCollect("M115", 5000));
        BufferedImage bare = camera.internalCapture();
        SimBoardRendererTest.assertColour(SimBoardRenderer.BENCH_BLANK, SimBoardRendererTest.bgr(bare, 32, 24), 2,
                "bench blank");
        SimScene s = SimScene.load(f);
        s.paste.landingSdMm = 0;
        SimPaste p = new SimPaste(() -> s, SimPaste.Store.forFile(f), System::nanoTime, () -> 0.0);
        SimNeedle n = new SimNeedle(p, s.paste.n2OffsetMm);
        n.pressurize();
        n.dot(300, 240, SimPasteTest.BLANK_Z, 1600);
        BufferedImage wet = camera.internalCapture(); // well within the 1 s frame cache
        SimBoardRendererTest.assertColour(SimBoardRenderer.BLANK_PASTE, SimBoardRendererTest.bgr(wet, 32, 24), 2,
                "the dot under the camera");
        p.wipe("blank");
        SimBoardRendererTest.assertColour(SimBoardRenderer.BENCH_BLANK,
                SimBoardRendererTest.bgr(camera.internalCapture(), 32, 24), 2, "wiped");
        // The SIM balance of this configuration: the confirmed simulator and a balance block.
        SimBalance b = SimBalance.forConfiguration(Configuration.get());
        assertNotNull(b);
        assertFalse(b.isConnected(), "display");
        SimGuard.noteIdentity(sim.machine, List.of("FIRMWARE_NAME:Marlin"));
        assertNull(SimBalance.forConfiguration(Configuration.get()), "not on a controller that is not the simulator");
        assertThrows(IllegalStateException.class, () -> b.read(0), "nor does an instance read there");
    }

    @Test
    public void theVariantsChangeWhatTheySay() throws Exception {
        for (PadcalScenes.Variant v : PadcalScenes.Variant.values()) {
            SimScene base = PadcalScenes.padcal(new SimScene(), "connected");
            base.seat = SimScene.seat(0, 0, 0, 1, 1, 0);
            SimScene s = PadcalScenes.padcal(new SimScene(), "connected");
            s.seat = SimScene.seat(0, 0, 0, 1, 1, 0);
            PadcalScenes.apply(s, v, 0.42);
            com.google.gson.Gson g = new com.google.gson.Gson();
            boolean changed = !g.toJson(base.paste).equals(g.toJson(s.paste))
                    || !g.toJson(base.coupons).equals(g.toJson(s.coupons));
            assertEquals(v.sceneChange, changed, v.key);
        }
        SimScene s = PadcalScenes.apply(PadcalScenes.padcal(new SimScene(), "connected"),
                PadcalScenes.Variant.NEEDLE_GAP_025, 0.42);
        assertEquals(0.27, s.paste.needleZOffsetMm, 1e-12, "the true touch 0.15 below the engine's: gap 0.10 → 0.25");
        s = PadcalScenes.apply(PadcalScenes.padcal(new SimScene(), "connected"), PadcalScenes.Variant.COUPON1_OFF_15, 0);
        assertArrayEquals(new double[] {331.5, 40, 371.5, 70}, s.coupon("coupon1").rect, 0);
        // il_sim_scene.py writes the same variants by the same names.
        String py = new String(Files.readAllBytes(new File("src/test/sim/il_sim_scene.py").toPath()),
                StandardCharsets.UTF_8);
        for (PadcalScenes.Variant v : PadcalScenes.Variant.values()) {
            assertTrue(py.contains("\"" + v.key + "\""), "il_sim_scene.py VARIANTS has " + v.key);
        }
    }
}
