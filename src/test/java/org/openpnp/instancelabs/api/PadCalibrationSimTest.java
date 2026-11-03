package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.board.EdtRunner;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DotImager;
import org.openpnp.instancelabs.dispense.FlatVacuum;
import org.openpnp.instancelabs.dispense.PadGeometry;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.instancelabs.sim.PadcalScenes;
import org.openpnp.instancelabs.sim.SimBoardRenderer;
import org.openpnp.instancelabs.sim.SimGuard;
import org.openpnp.instancelabs.sim.SimPaste;
import org.openpnp.instancelabs.sim.SimScene;
import org.openpnp.instancelabs.sim.SimVacuum;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.instancelabs.vision.DotInspector;
import org.openpnp.instancelabs.vision.DotInspectorSettings;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Package;
import org.openpnp.model.Part;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Calibrate automatically end to end in SIM (PROFILE_CALIBRATION_DESIGN.md §14.3): OpenPnP's GcodeDriver over
 * loopback to the RecordingGcodeServer, which answers as OpenPnP's simulator with the scene file's SIM vacuum and
 * SIM paste (Marlin's B count, a mass per turn of 2.30 mg against the engine's 2.11, a footprint law of its own,
 * ripple and noise); the SIM top camera draws the deposits, which the real DotInspector measures; the SIM balance
 * is read by the test (the operator) through the SIM endpoints and the readings are typed. Every negative control
 * of §11.7 ends with its expected reason. No device is opened.
 */
public class PadCalibrationSimTest {
    static final double BOARD_X = 150, BOARD_Y = 100, Z = 5.5;
    /** The bench frame at the Top camera's scale (machine.xml). */
    static final int W = 1920, H = 1080;
    static final double UPP_X = 0.01068376, UPP_Y = 0.01072961;

    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;
    File boardFile;
    File sceneFile;
    long regulatorAtMs;

    // ------------------------------------------------------------------------------------------
    // The set-up: the scene file, the machine, the board
    // ------------------------------------------------------------------------------------------

    /** The §11.3 scene around this test's bed: the board, the bench blank, the paste, two coupons, a balance. */
    static JsonObject scene(String balanceMode) {
        JsonObject o = new JsonParser().parse("{\"version\": 1,"
                + " \"board\": {\"file\": \"padcal-test.board.xml\", \"size\": [46, 30], \"seat\": {\"x\": " + BOARD_X
                + ", \"y\": " + BOARD_Y + "}, \"surface\": {\"z0\": " + Z + "}},"
                + " \"blank\": {\"rect\": [270, 215, 345, 275], \"surface\": {\"z0\": " + Z + "}, \"bgr\": [206, 25, 0]},"
                + " \"bedZ\": 2.5,"
                + " \"vacuum\": {\"tauS\": 0, \"sealTauS\": 0, \"releaseTauS\": 0},"
                + " \"paste\": {\"mRevMg\": 2.30, \"ripple\": 0.15, \"ripplePeakDeg\": 299, \"cv\": 0.08, \"seed\": 11,"
                + "  \"footprint\": {\"c\": 0.875, \"e\": 0.77}, \"transferRatio\": 0.75, \"oozeFirst\": 0.40,"
                + "  \"refillDip\": 0.30, \"refillTauDots\": 2, \"idleFullS\": 300, \"idleDeadS\": 3600,"
                + "  \"threadShare\": 0.5, \"threadFullMg\": 0.35, \"threadZeroMg\": 0.45, \"missBelowMg\": 0.12,"
                + "  \"maxGapMm\": 0.20, \"needleZOffsetMm\": null, \"feedLimitMgS\": 3.0, \"landingSdMm\": 0.03,"
                + "  \"landingBiasMm\": [0, 0], \"n2OffsetMm\": [25.291, -75.926], \"reportCount\": true},"
                + " \"coupons\": [{\"place\": \"coupon1\", \"rect\": [330, 40, 370, 70], \"z\": 5.80, \"bgr\": [206, 25, 0],"
                + "  \"massMg\": 4100},"
                + "  {\"place\": \"coupon2\", \"rect\": [330, 75, 370, 105], \"z\": 5.80, \"bgr\": [206, 25, 0],"
                + "  \"massMg\": 4060}],"
                + " \"balance\": {\"mode\": \"" + balanceMode + "\", \"resolutionMg\": 1, \"noiseMg\": 0.4,"
                + "  \"settleS\": 0.1}}").getAsJsonObject();
        return o;
    }

    static JsonObject paste(JsonObject scene) {
        return scene.getAsJsonObject("paste");
    }

    void writeScene(JsonObject scene) throws Exception {
        Files.createDirectories(sceneFile.getParentFile().toPath());
        Files.write(sceneFile.toPath(), scene.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The machine and services on a scene (the variant's change applied), the bench set-up, the board. */
    void start(Consumer<JsonObject> variant) throws Exception {
        sim = new SimMachine();
        sceneFile = SimGuard.sceneFile(Configuration.get());
        JsonObject sc = scene("display");
        if (variant != null) {
            variant.accept(sc);
        }
        writeScene(sc);
        SimScene.Source source = SimScene.Source.forConfiguration(Configuration.get());
        sim.server.vacuum = new SimVacuum(source, System::nanoTime);
        sim.server.paste = SimPaste.forConfiguration(Configuration.get());
        sim.server.firmwareName = FlatVacuum.SIMULATOR;
        // the simulator's identity as the first engine task notes it (the SIM-only settings may be set before it)
        SimGuard.noteIdentity(sim.machine, List.of(SimGuard.SIMULATOR_FIRMWARE));
        svc = DispenseService.start(Configuration.get());
        assertTrue(svc.isAvailable(), String.valueOf(svc.getUnavailableReason()));
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        boardFile = writeBoard();
        svc.setDotImager(new Camera(source, this::board));
        ok(call("PUT", "/api/locations/waste", "{\"x\":340,\"y\":268,\"z\":5.5}"));
        ok(call("PUT", "/api/locations/blank", "{\"x\":290,\"y\":240,\"z\":5.5}"));
        ok(call("PUT", "/api/locations/coupon1", "{\"x\":350,\"y\":55,\"z\":5.80}"));
        ok(call("PUT", "/api/locations/coupon2", "{\"x\":350,\"y\":90,\"z\":5.80}"));
        ok(call("POST", "/api/needle/anchor", "{\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}"));
        ok(call("POST", "/api/needle/offset", "{\"offsetMm\":-2.59}"));
        ok(call("PUT", "/api/surfaces/blank", "{\"points\":[[272,217,5.5],[343,217,5.5],[272,273,5.5],[343,273,5.5]]}"));
        ok(call("PUT", "/api/config", "{\"purgeRow\":{\"x0Mm\":275,\"y0Mm\":220,\"pitchXMm\":2.0,\"spots\":33},"
                + "\"vacuumProbe\":{\"vacuumSettleMs\":300,\"readDelayMs\":0},\"cleanPadCheck\":{\"enabled\":false}}"));
        ok(call("PUT", "/api/pad-types/chip-0603", "{\"status\":\"characterized\"}"));
        ok(call("POST", "/api/screw/start-angle", "{}"));
        regulatorAtMs = System.currentTimeMillis();
    }

    @AfterEach
    public void after() throws Exception {
        if (api != null) {
            api.stop();
        }
        if (svc != null) {
            svc.shutdown();
        }
        if (sim != null) {
            sim.close();
        }
    }

    Board board() {
        try {
            return Configuration.get().getBoard(boardFile);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A footprint of pads {name, x, y, w, h, roundness}. */
    static Footprint footprint(Object[]... pads) {
        Footprint fp = new Footprint();
        fp.setUnits(LengthUnit.Millimeters);
        for (Object[] p : pads) {
            Footprint.Pad pad = new Footprint.Pad();
            pad.setName((String) p[0]);
            pad.setX((Double) p[1]);
            pad.setY((Double) p[2]);
            pad.setWidth((Double) p[3]);
            pad.setHeight((Double) p[4]);
            pad.setRoundness((Double) p[5]);
            fp.addPad(pad);
        }
        return fp;
    }

    static Object[] pad(String name, double x, double y, double w, double h, double r) {
        return new Object[] {name, x, y, w, h, r};
    }

    /** The WSON-8 of §14.3: 8 pins 0.5 mm apart in two rows and the exposed pad. */
    static Footprint wson() {
        List<Object[]> l = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            l.add(pad(Integer.toString(i + 1), -0.975, 0.75 - 0.5 * i, 0.55, 0.25, 0));
            l.add(pad(Integer.toString(8 - i), 0.975, 0.75 - 0.5 * i, 0.55, 0.25, 0));
        }
        l.add(pad("9", 0, 0, 0.9, 1.6, 0));
        return footprint(l.toArray(new Object[0][]));
    }

    /**
     * §14.3's board in code (46 x 30 mm at X 150, Y 100): R1-R4 0603 (chip-0603, characterized), T1 TestChip_A
     * (use chip-0603), J1-J2 SW_SPST_3.9x2.9mm (2 half turns per pad), Q1-Q3 Test_Strip_0.62x1.50 (2 quarter turns
     * per pad), S1-S3 Test_Square_1.0x1.0 (one half turn per pad: the single-dot group), U1 WSON-8 (not dispensable).
     */
    File writeBoard() throws Exception {
        Map<String, Footprint> pkgs = new LinkedHashMap<>();
        pkgs.put("R_0603_1608Metric", null);
        pkgs.put("TestChip_A", footprint(pad("1", -0.825, 0, 0.80, 0.95, 50), pad("2", 0.825, 0, 0.80, 0.95, 50)));
        pkgs.put("SW_SPST_3.9x2.9mm", footprint(pad("1", -2.0, 0, 0.864, 1.829, 18), pad("2", 2.0, 0, 0.864, 1.829, 18)));
        pkgs.put("Test_Strip_0.62x1.50", footprint(pad("1", -1.0, 0, 0.62, 1.50, 0), pad("2", 1.0, 0, 0.62, 1.50, 0)));
        pkgs.put("Test_Square_1.0x1.0", footprint(pad("1", -1.0, 0, 1.0, 1.0, 50), pad("2", 1.0, 0, 1.0, 1.0, 50)));
        pkgs.put("WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm", wson());
        Object[][] parts = {{"R1", "R_0603_1608Metric", 4, 4}, {"R2", "R_0603_1608Metric", 10, 4},
            {"R3", "R_0603_1608Metric", 16, 4}, {"R4", "R_0603_1608Metric", 22, 4}, {"T1", "TestChip_A", 30, 4},
            {"J1", "SW_SPST_3.9x2.9mm", 6, 12}, {"J2", "SW_SPST_3.9x2.9mm", 18, 12},
            {"Q1", "Test_Strip_0.62x1.50", 28, 12}, {"Q2", "Test_Strip_0.62x1.50", 34, 12},
            {"Q3", "Test_Strip_0.62x1.50", 40, 12}, {"S1", "Test_Square_1.0x1.0", 6, 21},
            {"S2", "Test_Square_1.0x1.0", 14, 21}, {"S3", "Test_Square_1.0x1.0", 22, 21},
            {"U1", "WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm", 36, 22}};
        StringBuilder sb = new StringBuilder("<openpnp-board version=\"1.1\" name=\"padcal-test.board.xml\">\n"
                + "<dimensions units=\"Millimeters\" x=\"46.0\" y=\"30.0\" z=\"0.0\" rotation=\"0.0\"/>\n<placements>\n");
        for (Object[] p : parts) {
            sb.append("<placement version=\"1.4\" side=\"Top\" id=\"").append(p[0]).append("\" part-id=\"part-")
                    .append(p[1]).append("\" type=\"Placement\" enabled=\"true\" rank=\"0\">\n")
                    .append("<location units=\"Millimeters\" x=\"").append(p[2]).append("\" y=\"").append(p[3])
                    .append("\" z=\"0.0\" rotation=\"0.0\"/>\n<error-handling>Default</error-handling>\n</placement>\n");
        }
        sb.append("</placements>\n<fiducials/>\n</openpnp-board>\n");
        File f = new File(sim.configDir, "padcal-test.board.xml");
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        EdtRunner.call(() -> {
            Configuration c = Configuration.get();
            for (Map.Entry<String, Footprint> e : pkgs.entrySet()) {
                Package pkg = new Package(e.getKey());
                if (e.getValue() != null) {
                    pkg.setFootprint(e.getValue());
                }
                c.addPackage(pkg);
                Part part = new Part("part-" + e.getKey());
                part.setPackage(pkg);
                c.addPart(part);
            }
            c.getBoard(f);
            return null;
        }, 10_000);
        return f;
    }

    /** The SIM top camera: the scene file drawn as it is now (deposits included), measured by the real DotInspector. */
    static final class Camera implements DotImager {
        final SimScene.Source scenes;
        final java.util.function.Supplier<Board> board;
        List<PadGeometry> pads;

        Camera(SimScene.Source scenes, java.util.function.Supplier<Board> board) {
            this.scenes = scenes;
            this.board = board;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Frame capture(double xMm, double yMm) {
            return captureLit(xMm, yMm, null);
        }

        @Override
        public synchronized Frame captureLit(double xMm, double yMm, Double surfaceZMm) {
            Board b = board.get();
            if (pads == null) {
                pads = SimBoardRenderer.boardPads(b, new org.openpnp.instancelabs.dispense.PadTypeResolver(
                        new ArrayList<>(), null));
            }
            return new Frame(SimBoardRenderer.render(scenes.get(), b, pads, xMm, yMm, W, H, UPP_X, UPP_Y), UPP_X, UPP_Y,
                    xMm, yMm);
        }

        @Override
        public List<Dot> inspect(Frame before, Frame after) {
            return new DotInspector(new DotInspectorSettings()).inspect(before.image, after.image, after.mmPerPxX,
                    after.mmPerPxY);
        }

        @Override
        public List<Dot> inspectAll(Frame before, Frame after) {
            DotInspectorSettings s = new DotInspectorSettings();
            s.minCircularity = 0;
            s.maxDiameterMm = 3.0;
            return new DotInspector(s).inspect(before.image, after.image, after.mmPerPxX, after.mmPerPxY);
        }

        @Override
        public double[] fieldOfViewMm() {
            return new double[] {W * UPP_X, H * UPP_Y};
        }
    }

    // ------------------------------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------------------------------

    static final class Res {
        final int status;
        final String body;

        Res(HttpResponse<String> r) {
            status = r.statusCode();
            body = r.body();
        }

        JsonObject json() {
            return new JsonParser().parse(body).getAsJsonObject();
        }

        JsonArray array() {
            return new JsonParser().parse(body).getAsJsonArray();
        }

        @Override
        public String toString() {
            return status + " " + (body.length() > 4000 ? body.substring(0, 4000) + "…" : body);
        }
    }

    Res call(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(600));
        if (json != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return new Res(http.send(b.build(), HttpResponse.BodyHandlers.ofString()));
    }

    Res get(String path) throws Exception {
        return call("GET", path, null);
    }

    static JsonObject ok(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.json();
    }

    static String str(JsonObject o, String k) {
        return o == null || o.get(k) == null || o.get(k).isJsonNull() ? null : o.get(k).getAsString();
    }

    static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    JsonObject plan() throws Exception {
        JsonObject plan = ok(call("POST", "/api/plan", "{\"source\":\"file\",\"file\":\"" + boardFile.getAbsolutePath()
                .replace("\\", "\\\\") + "\",\"x\":" + BOARD_X + ",\"y\":" + BOARD_Y + ",\"z\":" + Z
                + ",\"rotation\":0}"));
        String id = str(plan, "planId");
        ok(call("PUT", "/api/surfaces/board", "{\"points\":[[148,98,5.5],[198,98,5.5],[148,132,5.5],[198,132,5.5]],"
                + "\"planId\":\"" + id + "\"}"));
        return ok(get("/api/plan/" + id));
    }

    /** The groups of a check by package. */
    static Map<String, List<JsonObject>> groups(JsonObject check) {
        Map<String, List<JsonObject>> m = new HashMap<>();
        for (JsonElement e : check.getAsJsonArray("groups")) {
            JsonObject g = e.getAsJsonObject();
            m.computeIfAbsent(g.getAsJsonArray("packageIds").get(0).getAsString(), k -> new ArrayList<>()).add(g);
        }
        return m;
    }

    static JsonObject one(Map<String, List<JsonObject>> groups, String pkg) {
        List<JsonObject> l = groups.get(pkg);
        assertNotNull(l, pkg + " in " + groups.keySet());
        assertEquals(1, l.size(), pkg);
        return l.get(0);
    }

    /** The attestations the browser keeps (regulator at the profile's psi, paste life), as the body's fields. */
    String attest() {
        return "\"regulator\":{\"psi\":18,\"atMs\":" + regulatorAtMs + "},\"pasteLifeAtMs\":" + regulatorAtMs;
    }

    /** The start body of an automatic calibration of some groups of a check. */
    String body(String planId, List<JsonObject> groups) {
        StringBuilder ids = new StringBuilder();
        StringBuilder sids = new StringBuilder();
        for (JsonObject g : groups) {
            ids.append(ids.length() == 0 ? "" : ",").append('"').append(str(g, "group")).append('"');
            sids.append(sids.length() == 0 ? "" : ",").append('"').append(str(g, "group")).append("\":\"")
                    .append(str(g.getAsJsonObject("suggestion"), "id")).append('"');
        }
        return "{\"planId\":\"" + planId + "\",\"groups\":[" + ids + "],\"save\":true,\"suggestionIds\":{" + sids + "},"
                + attest() + "}";
    }

    /** The job's status until it waits for the readings or ends. */
    JsonObject awaitJob(String state) throws Exception {
        long until = System.currentTimeMillis() + 600_000;
        JsonObject j = null;
        while (System.currentTimeMillis() < until) {
            JsonElement e = ok(get("/api/status")).get("padCalibration");
            j = e == null || e.isJsonNull() ? null : e.getAsJsonObject();
            String s = str(j, "state");
            if (s != null && (s.equals(state) || s.equals("done") || s.equals("failed") || s.equals("stopped")
                    || s.equals("cancelled"))) {
                return j;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the pad calibration did not reach " + state + ": " + j);
    }

    /** The operator at the SIM balance (display): per coupon load, read, wipe, read; then type the readings. */
    JsonObject weighAndType(JsonObject job) throws Exception {
        StringBuilder cs = new StringBuilder();
        for (JsonElement e : job.getAsJsonObject("awaiting").getAsJsonArray("coupons")) {
            String place = str(e.getAsJsonObject(), "place");
            double full = read(place);
            ok(call("POST", "/api/sim/balance/load", "{\"place\":null}"));
            assertTrue(ok(call("POST", "/api/sim/paste/wipe", "{\"region\":\"" + place + "\"}")).get("wiped")
                    .getAsInt() > 0);
            double empty = read(place);
            ok(call("POST", "/api/sim/balance/load", "{\"place\":null}"));
            cs.append(cs.length() == 0 ? "" : ",").append(String.format(Locale.ROOT,
                    "{\"place\":\"%s\",\"fullG\":%.3f,\"emptyG\":%.3f}", place, full, empty));
        }
        return ok(call("POST", "/api/pad-calibrations/" + str(job, "id") + "/answer", "{\"step\":\"readings\","
                + "\"coupons\":[" + cs + "],\"wait\":true}"));
    }

    /** One coupon on the SIM balance, read once it is stable (grams, as the display shows). */
    double read(String place) throws Exception {
        ok(call("POST", "/api/sim/balance/load", "{\"place\":\"" + place + "\"}"));
        for (int i = 0; i < 100; i++) {
            JsonObject b = ok(get("/api/sim/balance"));
            if (b.get("stable").getAsBoolean()) {
                assertEquals(place, str(b, "loaded"));
                return Double.parseDouble(str(b, "display").replace(" g", ""));
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the SIM balance did not settle");
    }

    static JsonObject result(JsonObject job) {
        return job.getAsJsonObject("result");
    }

    static JsonObject groupResult(JsonObject job, String group) {
        for (JsonElement e : result(job).getAsJsonArray("groups")) {
            if (group.equals(str(e.getAsJsonObject(), "group"))) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no group " + group + " in " + job);
    }

    // ------------------------------------------------------------------------------------------
    // §14.3: from "needs a profile" to characterized, a run, and a reconnect
    // ------------------------------------------------------------------------------------------

    @Test
    public void calibratesASingleDotAndTwoMultiDotGroupsInOneJob() throws Exception {
        start(null);
        JsonObject plan = plan();
        String planId = str(plan, "planId");
        JsonObject pc = plan.getAsJsonObject("profileCheck");
        Map<String, List<JsonObject>> g = groups(pc);
        JsonObject j = one(g, "SW_SPST_3.9x2.9mm");
        JsonObject q = one(g, "Test_Strip_0.62x1.50");
        JsonObject s = one(g, "Test_Square_1.0x1.0");
        JsonObject t = one(g, "TestChip_A");
        assertEquals("no-type", str(j, "reason"));
        assertEquals("new", str(j.getAsJsonObject("suggestion"), "kind"));
        assertEquals(800, j.getAsJsonObject("suggestion").getAsJsonObject("dots").get("steps").getAsInt());
        assertEquals(2, j.getAsJsonObject("suggestion").getAsJsonObject("dots").get("perPad").getAsInt());
        assertEquals(400, q.getAsJsonObject("suggestion").getAsJsonObject("dots").get("steps").getAsInt());
        assertEquals(2, q.getAsJsonObject("suggestion").getAsJsonObject("dots").get("perPad").getAsInt());
        assertEquals(800, s.getAsJsonObject("suggestion").getAsJsonObject("dots").get("steps").getAsInt());
        assertEquals(1, s.getAsJsonObject("suggestion").getAsJsonObject("dots").get("perPad").getAsInt(),
                "the single-dot group");
        assertEquals("use", str(t.getAsJsonObject("suggestion"), "kind"));
        for (JsonObject u : g.get("WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm")) {
            assertEquals("none", str(u.getAsJsonObject("suggestion"), "kind"), u.toString());
        }
        assertEquals(4, pc.get("fixable").getAsInt(), pc.toString());

        // Use for T: the mapping, the board planned again
        planId = str(ok(call("POST", "/api/pad-profile/apply", "{\"planId\":\"" + planId + "\",\"groups\":[\""
                + str(t, "group") + "\"],\"choice\":\"use\",\"suggestionIds\":{\"" + str(t, "group") + "\":\""
                + str(t.getAsJsonObject("suggestion"), "id") + "\"}}")), "newPlanId");
        assertNotNull(planId);
        g = groups(ok(get("/api/pad-profile/check?planId=" + planId)));
        j = one(g, "SW_SPST_3.9x2.9mm");
        q = one(g, "Test_Strip_0.62x1.50");
        s = one(g, "Test_Square_1.0x1.0");
        List<JsonObject> chosen = List.of(j, q, s);

        // the before-start summary (§7.9)
        JsonObject pv = ok(get("/api/pad-calibrations/preview?planId=" + planId + "&groups=all"));
        assertTrue(str(pv, "sentence").contains("Lays 76 test dots on the blank (28 half-turn, 48 quarter-turn)"),
                pv.toString());
        // two groups chose the half turn (J and S), one the quarter turn: the half turn is weighed first (§7.2)
        assertTrue(str(pv, "sentence").contains("Then lays 68 half-turn dots on coupon 1 and 136 quarter-turn dots on "
                + "coupon 2"), str(pv, "sentence"));
        assertTrue(str(pv, "sentence").contains("Then it needs you once: weigh each coupon, wipe it and weigh it again "
                + "(4 readings)."), str(pv, "sentence"));
        assertEquals("You need: two clean weigh coupons on coupon1 and coupon2 (blank PCB, 40 × 30 mm, at least 3 g), "
                + "the balance, a lint-free wipe and isopropyl alcohol.", str(pv, "needsText"));
        assertEquals("Read the regulator first: it must show 18.0 psi (Run › Checks › Regulator).", str(pv, "refusal"),
                "the preview says what Start would refuse: the browser gave no attestation");
        assertEquals(3, pv.getAsJsonArray("saves").size());

        // refusals in §12.2's order: a stale suggestion id, then the attestations
        Res stale = call("POST", "/api/pad-calibrations", body(planId, chosen).replace(str(j.getAsJsonObject(
                "suggestion"), "id"), "s000000000000"));
        assertEquals(409, stale.status, stale.toString());
        assertEquals("suggestion-stale", str(stale.json(), "error"));
        Res noReg = call("POST", "/api/pad-calibrations", body(planId, chosen).replace(attest(),
                "\"pasteLifeAtMs\":" + regulatorAtMs));
        assertEquals(409, noReg.status, noReg.toString());
        assertEquals("Read the regulator first: it must show 18.0 psi (Run › Checks › Regulator).",
                str(noReg.json(), "reason"));

        // Calibrate all three: it runs, then waits for the operator at the balance
        sim.server.mark();
        JsonObject started = ok(call("POST", "/api/pad-calibrations", body(planId, chosen)));
        String jobId = str(started, "id");
        Res busy = call("POST", "/api/run", "{\"planId\":\"" + planId + "\"}");
        assertEquals(409, busy.status, "every other task waits while the calibration runs: " + busy);
        JsonObject job = awaitJob("awaiting-reading");
        assertEquals("awaiting-reading", str(job, "state"), job.toString());
        assertTrue(str(job.getAsJsonObject("awaiting"), "text").startsWith("The dots are laid and the head is parked "
                + "over the purge row. For each coupon: put it on the balance and type the reading;"), job.toString());
        assertTrue(str(job.getAsJsonObject("awaiting"), "text").contains("Coupon 1 has 68 half-turn dots"));
        for (JsonElement e : job.getAsJsonObject("awaiting").getAsJsonArray("coupons")) {
            JsonObject crop = e.getAsJsonObject().getAsJsonObject("crop");
            assertEquals("median", str(crop, "what"), "a camera snapshot of each coupon's dots: " + e);
            assertEquals(str(e.getAsJsonObject(), "steps"), str(crop, "steps"));
        }
        // the coupons were looked at and touched; the ladder and the weigh dots are what §14.3 says
        JsonArray steps = job.getAsJsonArray("steps");
        assertEquals("done", str(steps.get(1).getAsJsonObject(), "state"), steps.toString());
        assertEquals("coupon1 and coupon2 found and touched", str(steps.get(1).getAsJsonObject(), "text"));
        assertEquals("76 dots: half ok, quarter ok", str(steps.get(2).getAsJsonObject(), "text"),
                job.getAsJsonArray("ladder").toString());
        assertEquals("68 half-turn dots on coupon1, 136 quarter-turn dots on coupon2",
                str(steps.get(3).getAsJsonObject(), "text"));
        int ladderDots = 0;
        for (SimPaste.Deposit d : round(SimPaste.Store.forFile(sceneFile).deposits("blank"))) {
            ladderDots += d.y > 235 ? 1 : 0; // the calibration area (the purge row lies at Y 220)
        }
        assertEquals(76, ladderDots, "every ladder dot laid on the blank");
        assertEquals(68, round(SimPaste.Store.forFile(sceneFile).deposits("coupon1")).size());
        assertEquals(136, round(SimPaste.Store.forFile(sceneFile).deposits("coupon2")).size());
        for (JsonElement e : job.getAsJsonArray("ladder")) {
            JsonObject l = e.getAsJsonObject();
            assertEquals(l.get("dots").getAsInt(), l.get("found").getAsInt(), "the camera found every dot: " + l);
        }
        checkBlockStarts();

        assertEquals(0, job.getAsJsonArray("warnings").size(), "the engine steps went as planned (parked too): "
                + job.getAsJsonArray("warnings"));
        // the operator weighs, wipes and weighs again; the decision and the new plan follow
        JsonObject done = weighAndType(job);
        assertEquals("done", str(done, "state"), done.toString());
        JsonObject res = result(done);
        assertEquals("characterized", str(res, "outcome"), res.toString());
        assertTrue(str(res, "sentence").startsWith("Characterized "), str(res, "sentence"));
        assertTrue(str(res, "sentence").endsWith("; planned again."), str(res, "sentence"));
        String newPlanId = str(res, "newPlanId");
        assertNotNull(newPlanId);
        JsonObject jr = groupResult(done, str(j, "group"));
        JsonObject qr = groupResult(done, str(q, "group"));
        JsonObject sr = groupResult(done, str(s, "group"));
        // the SIM's 2.30 mg/rev: 1.15 mg per half turn, 0.575 per quarter turn, not the model's 1.06 / 0.53
        assertEquals(2.30, jr.get("mgPerPad").getAsDouble(), 2.30 * 0.05, jr.toString());
        assertEquals(1.15, qr.get("mgPerPad").getAsDouble(), 1.15 * 0.05, qr.toString());
        assertEquals(1.15, sr.get("mgPerPad").getAsDouble(), 1.15 * 0.05, sr.toString());
        assertEquals(1, sr.get("perPad").getAsInt());
        for (JsonObject x : List.of(jr, qr, sr)) {
            assertEquals("characterized", str(x, "outcome"), x.toString());
            assertTrue(x.get("massConfirmed").getAsBoolean(), x.toString());
        }
        assertTrue(str(res.getAsJsonObject("control"), "drift").startsWith("the validated half turn now gives 1.1"),
                res.toString());
        assertTrue(res.getAsJsonArray("crops").size() >= 6, "3 per dose of the ladder and the coupons");
        JsonObject crop = res.getAsJsonArray("crops").get(0).getAsJsonObject();
        HttpResponse<byte[]> png = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort()
                + str(crop, "after"))).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, png.statusCode());
        assertEquals((byte) 0x89, png.body()[0], "a PNG");
        assertEquals(8, res.getAsJsonArray("predictions").size());

        // the types: characterized with the measured plan and the weighed mass, written once per group
        JsonObject strip = typeOf(str(qr, "typeKey"));
        assertEquals("characterized", str(strip, "status"));
        assertEquals(400, strip.get("dotSteps").getAsInt());
        assertEquals(2, strip.get("dotsPerPad").getAsInt());
        assertEquals("auto-calibrated", str(strip, "origin"));
        assertEquals(1.15, strip.get("massPerPadMg").getAsDouble(), 1.15 * 0.05);
        JsonArray rows = ok2(get("/api/pad-calibrations?typeKey=" + enc(str(qr, "typeKey"))));
        assertEquals(1, rows.size());
        assertEquals("characterized", str(rows.get(0).getAsJsonObject(), "outcome"));
        // the mass records: one per coupon, confirmed by each other (mass per step within 5 %)
        int mass = 0;
        for (JsonElement e : ok2(get("/api/dose-records?freshOnly=true"))) {
            JsonObject r = e.getAsJsonObject();
            if ("mass".equals(str(r, "kind"))) {
                mass++;
                assertEquals(0.575 / 400, r.get("mgPerStep").getAsDouble(), 0.575 / 400 * 0.04, r.toString());
            }
        }
        assertEquals(2, mass);

        // the new plan: no fixable group (U stays not dispensable)
        JsonObject next = ok(get("/api/plan/" + newPlanId));
        assertEquals(0, next.getAsJsonObject("profileCheck").get("fixable").getAsInt(),
                next.getAsJsonObject("profileCheck").toString());

        // a second job finds nothing to calibrate: nothing written, nothing planned
        Res again = call("POST", "/api/pad-calibrations", "{\"planId\":\"" + newPlanId + "\",\"groups\":\"all\","
                + "\"save\":true," + attest() + "}");
        assertEquals(409, again.status, again.toString());
        assertEquals("Nothing to calibrate: every chosen group cannot be dispensed with the 22G tip.",
                str(again.json(), "reason"));

        // the by-hand weighing (§8) on a connected SIM balance: the coupons the operator wiped look clean again, the job
        // reads the balance itself and the emulated human wipes the coupons between the readings
        ok(call("POST", "/api/purge-row/reset", null)); // the operator wiped the purge row
        JsonObject connected = scene("connected");
        writeScene(connected);
        Thread.sleep(400); // the scene file is read again when it changed (250 ms)
        JsonObject weighed = ok(call("POST", "/api/pad-calibrations", "{\"steps\":[\"weigh\"],\"doseSteps\":800,"
                + "\"typeKeys\":[\"chip-0603\"]," + attest() + ",\"wait\":true}")).getAsJsonObject("result");
        assertEquals("done", str(weighed, "state"), weighed.toString());
        assertEquals("sim", str(weighed.getAsJsonObject("balance"), "kind"));
        assertTrue(weighed.getAsJsonObject("balance").get("connected").getAsBoolean());
        assertTrue(str(result(weighed), "sentence").startsWith("Weighed "), weighed.toString());
        assertTrue(str(result(weighed), "sentence").endsWith("; the mass is confirmed."), weighed.toString());
        assertTrue(round(SimPaste.Store.forFile(sceneFile).deposits("coupon")).isEmpty(), "the human wiped them");
        int simRows = 0;
        for (JsonElement e : ok2(get("/api/dose-records?freshOnly=true"))) {
            simRows += "sim".equals(str(e.getAsJsonObject(), "method")) ? 1 : 0;
        }
        assertEquals(2, simRows, "the connected balance's readings");

        // the board, U skipped: blocks 800 (R, T, J, S) and 400 (Q), each Q pad's 2 dots in a row, deposits as predicted
        ok(call("POST", "/api/purge-row/reset", null));
        ok(call("PUT", "/api/config", "{\"refuseFlaggedPads\":false}"));
        JsonObject runPlan = plan();
        sim.server.mark();
        SimPaste.Store store = SimPaste.Store.forFile(sceneFile);
        int boardBefore = store.deposits("board").size();
        JsonObject run = ok(call("POST", "/api/run", "{\"planId\":\"" + str(runPlan, "planId") + "\",\"wait\":true}"))
                .getAsJsonObject("result");
        assertEquals("OK", str(run, "status"), run.toString());
        JsonArray blocks = runPlan.getAsJsonArray("blocks");
        assertEquals(2, blocks.size(), blocks.toString());
        // rc1 review: the purge spots the run lays, as I9 counts them (the web UI's Purge row item reads this)
        assertEquals(8 + 5, runPlan.get("purgeSpotsNeeded").getAsInt(), "8 to start and 5 for the quarter-turn block");
        assertEquals(800, blocks.get(0).getAsJsonObject().get("netSteps").getAsInt());
        assertEquals(400, blocks.get(1).getAsJsonObject().get("netSteps").getAsInt());
        checkPadsWhole(runPlan);
        checkDeposits(runPlan, store.deposits("board").subList(boardBefore, store.deposits("board").size()));

        // a reconnect: the quarter-turn block refuses (I11) until the start angle is confirmed; the quarter
        // camera records are stale, the weighings are not
        sim.machine.setEnabled(false);
        sim.enableAndHome();
        JsonObject runPlan2 = plan();
        Res i11 = call("POST", "/api/run", "{\"planId\":\"" + str(runPlan2, "planId") + "\"}");
        assertEquals(409, i11.status, i11.toString());
        assertTrue(str(i11.json(), "reason").contains("I11: the controller (re)connected after the screw start angle "
                + "was set"), i11.toString());
        boolean quarterStale = false;
        for (JsonElement e : ok2(get("/api/dose-records?steps=400"))) {
            JsonObject r = e.getAsJsonObject();
            if ("optical".equals(str(r, "kind"))) {
                assertFalse(r.get("fresh").getAsBoolean());
                assertEquals("made before the controller last connected: the screw angle may have moved",
                        str(r, "whyStale"));
                quarterStale = true;
            }
            if ("mass".equals(str(r, "kind"))) {
                assertTrue(r.get("fresh").getAsBoolean(), "a weighing survives a reconnect: " + r);
            }
        }
        assertTrue(quarterStale);
    }

    JsonArray ok2(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.array();
    }

    JsonObject typeOf(String key) throws Exception {
        for (JsonElement f : ok(get("/api/pad-types")).getAsJsonArray("families")) {
            for (JsonElement t : f.getAsJsonObject().getAsJsonArray("types")) {
                if (key.equals(str(t.getAsJsonObject(), "typeKey"))) {
                    return t.getAsJsonObject();
                }
            }
        }
        throw new AssertionError("no pad type " + key);
    }

    static List<SimPaste.Deposit> round(List<SimPaste.Deposit> l) {
        List<SimPaste.Deposit> out = new ArrayList<>();
        for (SimPaste.Deposit d : l) {
            if (!d.thread) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * Every work dot of the ladder and the weighing starts at the start angle 298° plus whole quarter turns, by the
     * SIM screw's own count (§14.3): every partial block started at the start angle.
     */
    void checkBlockStarts() {
        int n = 0;
        for (SimPaste.Deposit d : round(SimPaste.Store.forFile(sceneFile).deposits(null))) {
            boolean work = d.region == SimScene.Region.COUPON || d.region == SimScene.Region.BLANK && d.y > 235;
            if (!work || d.startCount == null) {
                continue;
            }
            long a = Math.floorMod(d.startCount - 1324, 1600L);
            assertEquals(0, a % 400, "every work dot starts at 298° + whole quarter turns: " + d);
            n++;
        }
        assertEquals(76 + 136 + 68, n);
    }

    /** R5: every pad's dots are consecutive in the plan. */
    static void checkPadsWhole(JsonObject plan) {
        Map<String, Integer> last = new HashMap<>();
        JsonArray dots = plan.getAsJsonArray("dots");
        for (int i = 0; i < dots.size(); i++) {
            String pad = str(dots.get(i).getAsJsonObject(), "padId");
            Integer prev = last.put(pad, i);
            assertTrue(prev == null || prev == i - 1, "pad " + pad + "'s dots are consecutive");
        }
    }

    /** The SIM paste laid per pad (every dot of a pad within half a pitch) against the plan's predicted mass. */
    static void checkDeposits(JsonObject plan, List<SimPaste.Deposit> laid) {
        JsonArray dots = plan.getAsJsonArray("dots");
        Map<String, double[]> perPad = new LinkedHashMap<>();
        for (JsonElement e : dots) {
            JsonObject d = e.getAsJsonObject();
            double x = d.get("x").getAsDouble(), y = d.get("y").getAsDouble();
            for (SimPaste.Deposit p : laid) {
                if (!p.thread && Math.hypot(p.x - x, p.y - y) < 0.3) {
                    perPad.computeIfAbsent(str(d, "padId"), k -> new double[1])[0] += p.mg;
                }
            }
        }
        Map<String, List<Double>> byPackage = new LinkedHashMap<>();
        for (JsonElement e : plan.getAsJsonArray("pads")) {
            JsonObject p = e.getAsJsonObject();
            if (p.get("predictedMassMg") == null || p.get("predictedMassMg").isJsonNull() || !perPad.containsKey(str(p,
                    "padId"))) {
                continue;
            }
            double ratio = perPad.get(str(p, "padId"))[0] / p.get("predictedMassMg").getAsDouble();
            byPackage.computeIfAbsent(str(p, "packageId"), k -> new ArrayList<>()).add(ratio);
            assertTrue(ratio > 0.7 && ratio < 1.3, str(p, "padId") + " laid " + ratio + " of its predicted mass");
        }
        for (Map.Entry<String, List<Double>> e : byPackage.entrySet()) {
            double mean = e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            assertEquals(1.0, mean, 0.10, e.getKey() + ": the pads' mean against the predicted mass " + e.getValue());
        }
    }

    // ------------------------------------------------------------------------------------------
    // §11.7: the negative controls, each with its expected reason
    // ------------------------------------------------------------------------------------------

    /** The by-hand ladder (§8): one dose measured on the blank, recorded, nothing decided. */
    JsonObject ladder(int steps, String typeKey) throws Exception {
        JsonObject started = ok(call("POST", "/api/pad-calibrations", "{\"steps\":[\"ladder\"],\"doseSteps\":" + steps
                + ",\"typeKeys\":[\"" + typeKey + "\"]," + attest() + ",\"wait\":true}"));
        return started.getAsJsonObject("result");
    }

    /**
     * The SIM balance answers right after a disconnect and a connect, before any engine task (the operator weighs
     * the coupons of a job stopped at its readings after connecting again): the service's M115 at the connect is
     * noted after SimGuard forgets the last connection's answer, whatever order the machine's listeners run in
     * (a set: each service start adds its own anew, at another place in that order).
     */
    @Test
    public void theSimBalanceAnswersRightAfterAReconnect() throws Exception {
        start(null);
        for (int i = 1; i <= 6; i++) {
            svc = DispenseService.start(Configuration.get());
            sim.machine.setEnabled(false);
            assertEquals(409, get("/api/sim/balance").status, "the simulator is not confirmed while disconnected");
            sim.machine.setEnabled(true);
            assertTrue(SimGuard.isSimulated(sim.machine), "the answer of connect " + i + " is kept");
            assertEquals(4100, read("coupon1") * 1000, 2, "coupon1 on the SIM balance after connect " + i);
        }
    }

    @Test
    public void reportCountOffRefusesTheQuarterTurnBlock() throws Exception {
        start(sc -> paste(sc).addProperty("reportCount", false));
        JsonObject job = ladder(400, "chip-0603");
        assertEquals("failed", str(job, "state"), job.toString());
        assertTrue(str(result(job), "sentence").contains("I11: the controller did not report the B step count (M114 "
                + "Count): the start angle of a partial-turn dose cannot be set. Nothing was sent."), job.toString());
        assertTrue(round(SimPaste.Store.forFile(sceneFile).deposits(null)).isEmpty(), "nothing was laid");
    }

    @Test
    public void rippleFailsThePhaseChecksOfBothGroups() throws Exception {
        // §20.1 item 15: ripple 0.6 at the worst physical offset, so the half-turn split fails with margin
        start(sc -> {
            paste(sc).addProperty("ripple", 0.6);
            paste(sc).addProperty("offsetSteps", PadcalScenes.RIPPLE_WORST_OFFSET_STEPS);
        });
        ok(call("PUT", "/api/config", "{\"padCalibration\":{\"weighTargetMg\":35}}"));
        JsonObject plan = plan();
        Map<String, List<JsonObject>> g = groups(plan.getAsJsonObject("profileCheck"));
        JsonObject j = one(g, "SW_SPST_3.9x2.9mm");
        JsonObject q = one(g, "Test_Strip_0.62x1.50");
        ok(call("POST", "/api/pad-calibrations", body(str(plan, "planId"), List.of(j, q))));
        JsonObject job = awaitJob("awaiting-reading");
        JsonObject done = ok(call("POST", "/api/pad-calibrations/" + str(job, "id") + "/answer", "{\"step\":"
                + "\"readings\",\"skip\":true,\"wait\":true}"));
        assertEquals("provisional", str(result(done), "outcome"), done.toString());
        JsonObject jr = groupResult(done, str(j, "group"));
        JsonObject qr = groupResult(done, str(q, "group"));
        assertEquals("provisional", str(jr, "outcome"));
        assertEquals("provisional", str(qr, "outcome"));
        assertTrue(str(jr, "text").contains("the two start angles of the half-turn dots differ"), jr.toString());
        // at 0.6 the smallest quarter turns also miss and thread; the phase check fails among them
        boolean swing = false;
        for (JsonElement e : done.getAsJsonArray("ladder")) {
            JsonObject l = e.getAsJsonObject();
            if (l.get("steps").getAsInt() == 400) {
                assertFalse(l.get("pass").getAsBoolean());
                swing = l.getAsJsonArray("failures").toString().contains("the quarter-turn dots swing");
            }
        }
        assertTrue(swing, done.getAsJsonArray("ladder").toString());
        assertTrue(qr.get("phase").getAsDouble() > qr.get("phaseLimit").getAsDouble(), qr.toString());
    }

    @Test
    public void threadsFailTheQuarterTurns() throws Exception {
        start(sc -> paste(sc).addProperty("threadZeroMg", 0.65));
        JsonObject job = ladder(400, "chip-0603");
        assertEquals("done", str(job, "state"), job.toString());
        assertTrue(Pattern.compile("quarter-turn dots trailed a paste thread to the next dot \\d+ times in \\d+ moves "
                + "\\(at most \\d+ allowed\\)").matcher(str(result(job), "sentence")).find(), job.toString());
    }

    @Test
    public void aLargeCvFailsTheRepeatCheck() throws Exception {
        start(sc -> paste(sc).addProperty("cv", PadcalScenes.REPEAT_FAIL_CV));
        JsonObject job = ladder(800, "chip-0603");
        assertEquals("done", str(job, "state"), job.toString());
        assertTrue(str(result(job), "sentence").contains("the half-turn dots vary"), job.toString());
        assertTrue(str(result(job), "sentence").contains("in area beyond the screw-angle pattern"), job.toString());
    }

    @Test
    public void aLandingBiasFailsTheLandingCheck() throws Exception {
        start(sc -> {
            JsonArray b = new JsonArray();
            b.add(new com.google.gson.JsonPrimitive(0.2));
            b.add(new com.google.gson.JsonPrimitive(0));
            paste(sc).add("landingBiasMm", b);
        });
        JsonObject job = ladder(800, "chip-0603");
        assertEquals("done", str(job, "state"), job.toString());
        assertTrue(str(result(job), "sentence").contains("the dots landed 0.20 mm off on average (at most 0.15 mm "
                + "allowed): check the needle offset"), job.toString());
    }

    @Test
    public void aCouponOffItsPlaceIsRefusedBeforeItIsTouched() throws Exception {
        start(sc -> {
            JsonObject k = sc.getAsJsonArray("coupons").get(0).getAsJsonObject();
            JsonArray r = new JsonArray();
            for (double v : new double[] {331.5, 40, 371.5, 70}) {
                r.add(new com.google.gson.JsonPrimitive(v));
            }
            k.add("rect", r);
        });
        JsonObject started = ok(call("POST", "/api/pad-calibrations", "{\"steps\":[\"weigh\"],\"doseSteps\":400,"
                + "\"typeKeys\":[\"chip-0603\"]," + attest() + ",\"wait\":true}"));
        JsonObject job = started.getAsJsonObject("result");
        assertEquals("failed", str(job, "state"), job.toString());
        assertTrue(str(result(job), "sentence").contains("Coupon 1 is not at its place: its edge is 1.5 mm inside the "
                + "weigh grid's margin"), job.toString());
        assertTrue(round(SimPaste.Store.forFile(sceneFile).deposits(null)).isEmpty(), "nothing was laid");
    }

    @Test
    public void aNeedleTooHighMissesEveryDot() throws Exception {
        start(sc -> paste(sc).addProperty("needleZOffsetMm", -2.59 - 0.15));
        JsonObject job = ladder(800, "chip-0603");
        assertEquals("failed", str(job, "state"), job.toString());
        assertTrue(str(result(job), "sentence").contains("were not found"), job.toString());
    }

    /**
     * rc1 review (tests), PC §7.4: the control's tolerance in a job (only the pure LadderStats.control was tested). The
     * SIM paste gives 1.6 mg per turn instead of 2.30 (a starved screw, a low regulator): the half-turn control dots are
     * found but about a fifth under the bench's 1.06 mm², and the job stops after the ladder with the control's text.
     */
    @Test
    public void aStarvedScrewFailsTheControlAndTheJobStops() throws Exception {
        start(sc -> paste(sc).addProperty("mRevMg", 1.6));
        JsonObject job = ladder(800, "chip-0603");
        assertEquals("failed", str(job, "state"), job.toString());
        String sentence = str(result(job), "sentence");
        assertTrue(sentence.contains("the control half-turn dots are ") && sentence.contains(" % below the bench's 1.06 mm²: "
                + "check the regulator (18 psi) and the paste before calibrating"), sentence);
    }

    /**
     * rc1 review (tests), PC §7.3 step 5: the look at every spot before the dots (only the pure PadLadder.bareAt was
     * tested). Blank wiped pressed without wiping: the area's band starts at 0 again over the old dots, and the next
     * ladder is refused before anything is laid, naming the spots it saw paste on.
     */
    @Test
    public void aLadderOnAnAreaThatWasNotWipedIsRefused() throws Exception {
        start(null);
        JsonObject first = ladder(800, "chip-0603");
        assertEquals("done", str(first, "state"), first.toString());
        ok(call("POST", "/api/calibration-area/reset", "{}")); // "Blank wiped", and nothing was wiped
        int laid = SimPaste.Store.forFile(sceneFile).deposits(null).size();
        JsonObject again = ladder(800, "chip-0603");
        assertEquals("failed", str(again, "state"), again.toString());
        String sentence = str(result(again), "sentence");
        assertTrue(sentence.contains("the calibration area is not clean or not flat under ") && sentence.contains(
                "wipe it, or move the calibration area (Calibrate › Pad calibration)"), sentence);
        assertEquals(laid, SimPaste.Store.forFile(sceneFile).deposits(null).size(), "nothing laid on the old dots");
    }

    @Test
    public void thePurgeAbsorbsDeadFirstDots() throws Exception {
        start(sc -> paste(sc).addProperty("idleDeadS", 0));
        // no blank surface yet: the job probes it first (the purge row's targets and the area's corners, each looked at)
        ok(call("DELETE", "/api/surfaces/blank", null));
        JsonObject pv = ok(get("/api/pad-calibrations/preview?steps=ladder&doseSteps=800&typeKeys=chip-0603"));
        assertEquals("no 'blank' surface yet", str(pv.getAsJsonObject("blank"), "probeWhy"));
        assertTrue(str(pv, "sentence").startsWith("Probes the blank with N1 ("), str(pv, "sentence"));
        JsonObject job = ladder(800, "chip-0603");
        assertEquals("done", str(job, "state"), job.toString());
        JsonObject blank = job.getAsJsonArray("steps").get(0).getAsJsonObject();
        assertEquals("done", str(blank, "state"), blank.toString());
        assertTrue(str(blank, "text").startsWith("blank surface probed: "), blank.toString());
        assertTrue(str(result(job), "sentence").endsWith("every camera check passed."), job.toString());
        boolean p7 = false;
        for (JsonElement e : result(job).getAsJsonArray("predictions")) {
            p7 |= "P7".equals(str(e.getAsJsonObject(), "id"));
        }
        assertTrue(p7);
    }

    @Test
    public void aShortDwellWithOneTurnDotsIsRefusedBeforeAnythingMoves() throws Exception {
        start(null);
        ok(call("PUT", "/api/config", "{\"profile\":{\"dwellMs\":50}}"));
        ok(call("POST", "/api/screw/start-angle", "{}"));
        JsonObject job = ladder(1600, "chip-0805");
        assertEquals("failed", str(job, "state"), job.toString());
        assertTrue(str(result(job), "sentence").contains("asks the screw for"), job.toString());
        assertTrue(str(result(job), "sentence").contains("I6"), job.toString());
        assertTrue(round(SimPaste.Store.forFile(sceneFile).deposits(null)).isEmpty(), "nothing was laid");
    }
}
