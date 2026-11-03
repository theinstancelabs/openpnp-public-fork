package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.ControllerCommands;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DotImager;
import org.openpnp.instancelabs.dispense.FlatVacuum;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Configuration;
import org.openpnp.model.Package;
import org.openpnp.model.Part;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The HTTP API end to end on a simulated machine: OpenPnP's GcodeDriver over TCP to the engine's
 * RecordingGcodeServer (localhost; no device is opened), the real DispenseService, the real
 * ApiServer on an ephemeral 127.0.0.1 port, a JDK HTTP client.
 */
public class ApiServerSimTest {
    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine();
        // N1 touches 5.5 everywhere, as setPlaces types it in: a run after a home checks Z with one touch
        FlatVacuum.install(sim, FlatVacuum.scene(5.5, null, 0));
        svc = DispenseService.start(Configuration.get());
        assertTrue(svc.isAvailable(), String.valueOf(svc.getUnavailableReason()));
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    public void after() throws Exception {
        api.stop();
        svc.shutdown();
        sim.close();
    }

    // ------------------------------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------------------------------

    static final class Res {
        final int status;
        final String body;
        final String contentType;

        Res(HttpResponse<String> r) {
            status = r.statusCode();
            body = r.body();
            contentType = r.headers().firstValue("Content-Type").orElse("");
        }

        JsonObject json() {
            return new JsonParser().parse(body).getAsJsonObject();
        }

        JsonArray array() {
            return new JsonParser().parse(body).getAsJsonArray();
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    Res call(String method, String path, String json, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
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

    Res post(String path, String json) throws Exception {
        return call("POST", path, json);
    }

    JsonObject ok(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.json();
    }

    static List<String> commands(List<String> lines) {
        List<String> l = new ArrayList<>();
        for (String s : lines) {
            if (!s.startsWith(";")) {
                l.add(s);
            }
        }
        return l;
    }

    /** Bench geometry: N1 touches the blank and the board at Z 5.5 (OpenPnP frame). */
    void setPlaces() throws Exception {
        ok(call("PUT", "/api/locations/waste", "{\"x\":80,\"y\":40,\"z\":5.5}"));
        ok(call("PUT", "/api/locations/blank", "{\"x\":90,\"y\":40,\"z\":5.5}"));
        // I4 needle frame from the bench relation (needle contact raw Z = 65.59 - N1 contact Z, i.e.
        // N2 contact 2.91: offset -2.59), then the offset confirmed
        ok(post("/api/needle/anchor", "{\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}"));
        ok(post("/api/needle/offset", "{\"offsetMm\":-2.59}"));
        // probed surfaces: the blank (places and purge row) and the test board's area (origin 100, 80; 20 x 10 mm)
        ok(call("PUT", "/api/surfaces/blank", "{\"points\":[[60,30,5.5],[200,30,5.5],[60,50,5.5],[200,50,5.5]]}"));
        ok(call("PUT", "/api/surfaces/board", "{\"points\":[[98,78,5.5],[130,78,5.5],[98,95,5.5],[130,95,5.5]]}"));
        // the simulator's probe timings (il_sim_scene.py FAST_TIMINGS): the Z check after a home
        ok(call("PUT", "/api/config", "{\"vacuumProbe\":{\"vacuumSettleMs\":300,\"readDelayMs\":0}}"));
    }

    /**
     * Places plus what a board run needs on top: the purge row on the blank (48 spots = 6 boards of 8
     * purge dots), the screw start angle confirmed for this controller connection (I11), and no
     * clean-pad check (I10: the sim machine has no camera).
     */
    void setUpBoardRuns() throws Exception {
        setPlaces();
        ok(call("PUT", "/api/config", "{\"purgeRow\":{\"x0Mm\":80,\"y0Mm\":47,\"pitchXMm\":2.5,\"spots\":48},"
                + "\"cleanPadCheck\":{\"enabled\":false}}"));
        ok(post("/api/screw/start-angle", "{}"));
    }

    /** OpenPnP Stop and Start (a controller reconnect), then home. */
    void reconnect() throws Exception {
        sim.machine.setEnabled(false);
        sim.machine.setEnabled(true);
        sim.machine.home();
    }

    static List<String> bMoves(List<String> l) {
        List<String> b = new ArrayList<>();
        for (String s : l) {
            if (s.startsWith("G1 B")) {
                b.add(s);
            }
        }
        return b;
    }

    /** Top camera stand-in for the clean-pad check (I10): a bright share per target, dirty where listed. */
    static class FakeImager implements DotImager {
        final Map<String, Double> dirty = new ConcurrentHashMap<>();
        final List<double[]> captured = new CopyOnWriteArrayList<>();

        static String key(double x, double y) {
            return String.format(Locale.ROOT, "%.3f,%.3f", x, y);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Frame capture(double xMm, double yMm) {
            captured.add(new double[] {xMm, yMm});
            return new Frame(null, 0.01, 0.01, xMm, yMm);
        }

        @Override
        public List<Dot> inspect(Frame before, Frame after) {
            return new ArrayList<>();
        }

        @Override
        public double brightFraction(Frame f, DispenseConfig.CleanPadCheck s) {
            return dirty.getOrDefault(key(f.xMm, f.yMm), 0.95);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------------------------

    @Test
    public void servesTheUiAndStatus() throws Exception {
        Res index = get("/");
        assertEquals(200, index.status);
        assertTrue(index.contentType.startsWith("text/html"));
        assertTrue(index.body.contains("id=\"btn-stop\""));
        // the web UI: core + one module per workspace (index.html loads every one of them)
        for (String f : new String[] {"app.js", "app.css", "favicon.svg", "core.js", "boardview.js", "run.js", "run.css",
                "setup.js", "setup.css", "profile.js", "profile.css", "calibrate.js", "records.js"}) {
            Res r = get("/static/" + f);
            assertEquals(200, r.status, f);
            assertFalse(r.body.isEmpty(), f);
            if (!f.equals("favicon.svg")) {
                assertTrue(index.body.contains("/static/" + f), "index.html loads " + f);
            }
        }
        assertEquals(404, get("/static/../machine.xml").status);
        assertEquals(404, get("/api/nope").status);
        assertEquals(405, call("DELETE", "/api/status", null).status);

        JsonObject s = ok(get("/api/status"));
        JsonObject m = s.getAsJsonObject("machine");
        assertTrue(m.get("enabled").getAsBoolean());
        assertTrue(m.get("homed").getAsBoolean());
        JsonObject d = s.getAsJsonObject("dispense");
        assertTrue(d.get("available").getAsBoolean());
        assertEquals("off", s.getAsJsonObject("pressure").get("state").getAsString());
        List<String> ids = new ArrayList<>();
        for (JsonElement e : s.getAsJsonArray("interlocks")) {
            ids.add(e.getAsJsonObject().get("id").getAsString());
        }
        for (String id : new String[] {"MACHINE", "CONFIG", "I1", "I2", "I3", "I4", "I5", "I6", "I7", "I8", "I9",
                "I10", "I11"}) {
            assertTrue(ids.contains(id), id + " in " + ids);
        }
        // test-dot default = one dot of the dispense profile (half a screw turn: 800 steps / 4.44)
        double rot = d.getAsJsonObject("testDot").get("defaultRotationDeg").getAsDouble();
        assertEquals(180.1802, rot, 1e-9);
        assertFalse(d.has("calibration"), "the volumetric q model is gone from the status");
    }

    /** The dispense profile in the status, with the numbers the UI shows (bench recipe p18_board1). */
    @Test
    public void statusShowsTheProfileWithDerivedNumbers() throws Exception {
        JsonObject d = ok(get("/api/status")).getAsJsonObject("dispense");
        JsonObject p = d.getAsJsonObject("profile");
        assertEquals(18, p.get("pressurePsi").getAsDouble(), 1e-9);
        assertEquals(800, p.get("netStepsPerDot").getAsInt());
        assertEquals(0.5, p.get("revolutionsPerDot").getAsDouble(), 1e-9);
        assertEquals(180.1802, p.get("netDeg").getAsDouble(), 1e-4);
        assertEquals(190.0901, p.get("forwardDeg").getAsDouble(), 1e-4, "net + 44 steps of suck-back");
        assertEquals(44, p.get("suckBackSteps").getAsInt(), "round(10 deg x 4.44)");
        assertEquals(64800, p.get("feedDegPerMin").getAsDouble(), 1e-9, "180 rpm = G1 B F64800");
        assertTrue(p.get("partialTurn").getAsBoolean());
        JsonArray angles = p.getAsJsonArray("startAngles");
        assertEquals(2, angles.size(), "a half turn alternates two start angles: " + angles);
        assertEquals(298, angles.get(0).getAsDouble(), 0.15);
        assertEquals(118, angles.get(1).getAsDouble(), 0.15);
        assertTrue(p.get("startAngleNeedsConfirm").getAsBoolean(), "never set");
        assertEquals(0, p.getAsJsonArray("problems").size(), p.toString());
        assertEquals(0.10, d.getAsJsonObject("needle").get("gapMm").getAsDouble(), 1e-9, "the gap is the profile's");

        JsonObject row = d.getAsJsonObject("purgeRow");
        assertFalse(row.get("defined").getAsBoolean());
        assertEquals(8, row.get("perBoard").getAsInt());
        assertEquals(0, row.get("boardsLeft").getAsInt());
        assertTrue(d.getAsJsonObject("cleanPadCheck").get("enabled").getAsBoolean());

        setUpBoardRuns();
        d = ok(get("/api/status")).getAsJsonObject("dispense");
        row = d.getAsJsonObject("purgeRow");
        assertTrue(row.get("defined").getAsBoolean());
        assertTrue(row.get("surfaceProbed").getAsBoolean(), "the purge row lies on the probed blank");
        assertEquals(48, row.get("spotsLeft").getAsInt());
        assertEquals(6, row.get("boardsLeft").getAsInt());
        assertFalse(d.getAsJsonObject("profile").get("startAngleNeedsConfirm").getAsBoolean());
        JsonObject i6 = null;
        for (JsonElement e : ok(get("/api/status")).getAsJsonArray("interlocks")) {
            if (e.getAsJsonObject().get("id").getAsString().equals("I6")) {
                i6 = e.getAsJsonObject();
            }
        }
        assertTrue(i6.get("text").getAsString().contains("18.0 psi"), i6.toString());
    }

    @Test
    public void refusesCrossSiteRequestsAndForeignHosts() throws Exception {
        Res r = call("POST", "/api/needle/changed", "{}", "Origin", "http://evil.example");
        assertEquals(403, r.status, r.toString());
        r = call("POST", "/api/needle/changed", "{}", "Sec-Fetch-Site", "cross-site");
        assertEquals(403, r.status, r.toString());
        // same-origin browser request is fine
        r = call("POST", "/api/needle/offset", "{\"offsetMm\":0.01}", "Origin", "http://127.0.0.1:" + api.getPort(),
                "Sec-Fetch-Site", "same-origin");
        assertEquals(200, r.status, r.toString());
        // DNS-rebinding guard: a foreign Host header is refused even for GET
        try (Socket sock = new Socket(InetAddress.getLoopbackAddress(), api.getPort())) {
            OutputStream out = sock.getOutputStream();
            out.write(("GET /api/status HTTP/1.1\r\nHost: evil.example:" + api.getPort()
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String first = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
            assertTrue(first.contains(" 403"), first);
        }
        assertTrue(ApiServer.isLoopbackHost("localhost:8765"));
        assertTrue(ApiServer.isLoopbackHost("[::1]:8765"));
        assertFalse(ApiServer.isLoopbackHost("127.0.0.1.evil.example"));
    }

    @Test
    public void padTypesAreGroupedAddableVersionedAndRetirable() throws Exception {
        JsonObject list = ok(get("/api/pad-types"));
        JsonArray fams = list.getAsJsonArray("families");
        assertEquals("Chip (2-terminal SMD)", fams.get(0).getAsJsonObject().get("family").getAsString());
        boolean has0603 = false;
        for (JsonElement t : fams.get(0).getAsJsonObject().getAsJsonArray("types")) {
            if (t.getAsJsonObject().get("typeKey").getAsString().equals("chip-0603")) {
                has0603 = true;
                assertEquals("not-started", t.getAsJsonObject().get("status").getAsString());
                // one profile dot per pad (the dose is the profile's, not a q-model rotation)
                assertEquals(1, t.getAsJsonObject().get("dotsPerPad").getAsInt());
                assertEquals(180.1802, t.getAsJsonObject().get("rotationPerDotDeg").getAsDouble(), 1e-4);
                assertEquals("18 psi half turn", t.getAsJsonObject().get("profileName").getAsString());
                assertFalse(t.getAsJsonObject().has("singleDotRotationDeg"));
            }
        }
        assertTrue(has0603);

        Res created = post("/api/pad-types",
                "{\"family\":\"Custom\",\"name\":\"Test pad 1\",\"padWMm\":1.2,\"padHMm\":0.8,\"k\":0.9}");
        assertEquals(201, created.status, created.toString());
        JsonObject pt = created.json().getAsJsonObject("padType");
        assertEquals("custom-test-pad-1", pt.get("typeKey").getAsString());
        assertEquals(1, pt.get("version").getAsInt());
        assertEquals("single", pt.get("layout").getAsString());

        JsonObject upd = ok(call("PUT", "/api/pad-types/custom-test-pad-1", "{\"status\":\"provisional\",\"tMm\":0.12}"))
                .getAsJsonObject("padType");
        assertEquals(2, upd.get("version").getAsInt());
        assertEquals("provisional", upd.get("status").getAsString());
        assertEquals(0.9, upd.get("k").getAsDouble(), 1e-9, "unchanged fields are kept");

        assertEquals(400, call("PUT", "/api/pad-types/custom-test-pad-1", "{\"packagePattern\":\"([\"}").status);
        assertEquals(400, call("PUT", "/api/pad-types/custom-test-pad-1", "{\"status\":\"great\"}").status);
        assertEquals(409, post("/api/pad-types", "{\"typeKey\":\"chip-0603\",\"name\":\"x\",\"padWMm\":1,\"padHMm\":1}").status);
        assertEquals(400, post("/api/pad-types", "{\"name\":\"no size\"}").status);

        JsonObject ret = ok(call("DELETE", "/api/pad-types/custom-test-pad-1", null)).getAsJsonObject("padType");
        assertEquals("Retired", ret.get("family").getAsString());
        assertEquals("not-started", ret.get("status").getAsString());
        assertTrue(ret.get("packagePattern").isJsonNull());
        JsonObject hist = ok(get("/api/pad-types/custom-test-pad-1"));
        assertEquals(3, hist.getAsJsonArray("history").size(), "append-only: three versions");

        JsonObject rule = ok(call("PUT", "/api/pad-types/chip-0603/rule", "{\"maxDots\":2}"));
        assertEquals(2, rule.get("maxDots").getAsInt());
    }

    @Test
    public void pressureHoldSwitchesPumpThenValveAndVentsValveFirst() throws Exception {
        setPlaces();
        sim.server.mark();
        JsonObject on = ok(post("/api/pressure/start", null));
        assertEquals("on", on.get("state").getAsString(), on.toString());
        List<String> l = commands(sim.server.sinceMark());
        int pump = l.indexOf("M106 P2 S255"), valve = l.indexOf("M106 P3 S255");
        assertTrue(pump >= 0 && valve > pump, l.toString());
        // pump -> wait -> valve (plan §4.5); the wait is host-side so a Stop can cancel the valve ON
        assertFalse(l.subList(pump, valve).stream().anyMatch(x -> x.startsWith("G4")), l.toString());
        List<String> raw = sim.server.sinceMark();
        long waitedMs = (sim.server.timeSinceMark(raw.indexOf("M106 P3 S255"))
                - sim.server.timeSinceMark(raw.indexOf("M106 P2 S255"))) / 1_000_000L;
        assertTrue(waitedMs >= 490, "pump-to-valve wait " + waitedMs + " ms");
        assertTrue(l.contains("M990 S3000"), "host watchdog armed (server reports the capability)");

        // engine runs are refused while the hold is on, with the reason
        Res prime = post("/api/prime", "{}");
        assertEquals(409, prime.status);
        assertTrue(prime.json().get("reason").getAsString().contains("Switch it off first"), prime.toString());
        assertEquals(409, post("/api/pressure/start", null).status);

        Thread.sleep(1500); // at least one keepalive ping
        sim.server.mark();
        JsonObject off = ok(post("/api/pressure/stop", null));
        assertEquals("off", off.get("state").getAsString());
        l = commands(sim.server.sinceMark());
        int vOff = l.indexOf("M107 P3"), pOff = l.indexOf("M107 P2");
        assertTrue(vOff >= 0 && pOff > vOff, "valve off, then pump off: " + l);
        assertTrue(l.contains("M990 S0"), l.toString());
        assertTrue(ok(get("/api/status")).getAsJsonObject("pressure").get("pings").getAsInt() >= 1);
    }

    @Test
    public void openPnpsOwnStopVentsTheHold() throws Exception {
        setPlaces();
        ok(post("/api/pressure/start", null));
        sim.server.mark();
        sim.machine.setEnabled(false); // what OpenPnP's Stop button does (no web STOP involved)
        List<String> l = commands(sim.server.sinceMark());
        int vOff = l.indexOf("M107 P3"), pOff = l.indexOf("M107 P2");
        assertTrue(vOff >= 0 && pOff > vOff, l.toString());
        assertEquals("off", ok(get("/api/status")).getAsJsonObject("pressure").get("state").getAsString());
    }

    @Test
    public void holdSwitchesOffAtTheOnTimeLimit() throws Exception {
        setPlaces();
        // I7 counts from the pump: shorter settles than the bench's 1 s + 3 s so the hold reaches ON first
        ok(call("PUT", "/api/config", "{\"maxContinuousOnTimeS\":1,\"pumpToValveSettleMs\":200,\"valveSettleMs\":200}"));
        JsonObject on = ok(post("/api/pressure/start", null));
        assertEquals("on", on.get("state").getAsString());
        sim.server.mark();
        long t0 = System.currentTimeMillis();
        while (ok(get("/api/status")).getAsJsonObject("pressure").get("state").getAsString().equals("on")
                && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(50);
        }
        JsonObject p = ok(get("/api/status")).getAsJsonObject("pressure");
        assertEquals("off", p.get("state").getAsString());
        assertTrue(p.get("lastEvent").getAsString().contains("I7"), p.toString());
        List<String> l = commands(sim.server.sinceMark());
        assertTrue(l.indexOf("M107 P3") >= 0 && l.indexOf("M107 P2") > l.indexOf("M107 P3"), l.toString());
    }

    @Test
    public void stopVentsTheHoldAndStopsTheMachine() throws Exception {
        setPlaces();
        ok(post("/api/pressure/start", null));
        sim.server.mark();
        JsonObject r = ok(post("/api/stop", null));
        assertTrue(r.get("stopped").getAsBoolean());
        assertFalse(r.get("machineEnabled").getAsBoolean(), r.toString());
        List<String> l = commands(sim.server.sinceMark());
        int vOff = l.indexOf("M107 P3"), pOff = l.indexOf("M107 P2");
        assertTrue(vOff >= 0 && pOff > vOff, l.toString());
        JsonObject s = ok(get("/api/status"));
        assertFalse(s.getAsJsonObject("machine").get("enabled").getAsBoolean());
        assertEquals("off", s.getAsJsonObject("pressure").get("state").getAsString());
        // STOP is always allowed, also when everything is already stopped
        assertEquals(200, post("/api/stop", null).status);
    }

    @Test
    public void primeAndTestDotRunThroughTheEngine() throws Exception {
        setPlaces();
        sim.server.mark();
        JsonObject prime = ok(post("/api/prime", "{\"rotationDeg\":360,\"wait\":true}"));
        JsonObject res = prime.getAsJsonObject("result");
        assertEquals("OK", res.get("status").getAsString(), prime.toString());
        List<String> l = commands(sim.server.sinceMark());
        assertTrue(l.indexOf("G92 B0") >= 0 && l.indexOf("G92 B0") < l.indexOf("M106 P2 S255"), "I2: " + l);
        assertTrue(l.indexOf("M107 P3") < l.lastIndexOf("M107 P2"), l.toString());

        JsonObject td = ok(post("/api/test-dot", "{\"wait\":true}"));
        assertEquals("OK", td.getAsJsonObject("result").get("status").getAsString(), td.toString());
        JsonObject loc = ok(get("/api/locations")).getAsJsonObject(DispenseEndpoints.TEST_DOT_LOCATION);
        assertEquals(82.0, loc.get("x").getAsDouble(), 1e-9, "waste + 2 mm test-dot offset");
        assertEquals(40.0, loc.get("y").getAsDouble(), 1e-9);
        JsonObject run = ok(get("/api/status")).getAsJsonObject("run");
        assertEquals("test dot", run.get("label").getAsString());
        assertFalse(run.get("running").getAsBoolean());
        assertEquals(400, post("/api/test-dot", "{\"rotationDeg\":-5}").status);
    }

    @Test
    public void refusalsCarryTheInterlockReason() throws Exception {
        Res noWaste = post("/api/prime", "{}");
        assertEquals(409, noWaste.status);
        assertTrue(noWaste.json().get("reason").getAsString().contains("waste"), noWaste.toString());
        setPlaces();
        sim.machine.setHomed(false);
        Res r = post("/api/prime", "{}");
        assertEquals(409, r.status);
        JsonObject b = r.json();
        assertTrue(b.get("reason").getAsString().contains("not homed"), r.toString());
        assertEquals("MACHINE", b.getAsJsonObject("interlocks").getAsJsonArray("violations").get(0).getAsJsonObject()
                .get("code").getAsString());
        assertEquals(409, post("/api/pressure/start", null).status);
        // the dot ladder requires a touch-off (I4)
        sim.machine.setHomed(true);
        ok(post("/api/needle/changed", null));
        Res dl = post("/api/calibrations/dot-ladder", "{}");
        assertEquals(409, dl.status);
        assertTrue(dl.body.contains("I4"), dl.toString());
    }

    File writeBoard() throws Exception {
        return writeBoard(new double[][] {{2, 2, 0}, {8, 2, 90}, {14, 6, 0}});
    }

    File writeBoard(double[][] pos) throws Exception {
        Package pkg = new Package("R_0603_1608Metric");
        Configuration.get().addPackage(pkg);
        Part part = new Part("R0603-API-TEST");
        part.setPackage(pkg);
        Configuration.get().addPart(part);
        StringBuilder sb = new StringBuilder("<openpnp-board version=\"1.1\" name=\"api-test.board.xml\">\n"
                + "<dimensions units=\"Millimeters\" x=\"20.0\" y=\"10.0\" z=\"0.0\" rotation=\"0.0\"/>\n<placements>\n");
        for (int i = 0; i < pos.length; i++) {
            sb.append("<placement version=\"1.4\" side=\"Top\" id=\"R").append(i + 1)
                    .append("\" part-id=\"R0603-API-TEST\" type=\"Placement\" enabled=\"true\" rank=\"0\">\n")
                    .append("<location units=\"Millimeters\" x=\"").append(pos[i][0]).append("\" y=\"")
                    .append(pos[i][1]).append("\" z=\"0.0\" rotation=\"").append(pos[i][2]).append("\"/>\n")
                    .append("<error-handling>Default</error-handling>\n</placement>\n");
        }
        sb.append("</placements>\n<fiducials/>\n</openpnp-board>\n");
        File f = new File(sim.configDir, "api-test.board.xml");
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** Review finding: the used purge spots are the engine's (a stale full-settings save must not free them). */
    @Test
    public void usedPurgeSpotsOnlyChangeThroughRunsAndTheReset() throws Exception {
        setUpBoardRuns();
        assertEquals(400, call("PUT", "/api/config", "{\"purgeRow\":{\"usedSpots\":3}}").status);
        ok(call("PUT", "/api/config", "{\"purgeRow\":{\"usedSpots\":0,\"spots\":40}}")); // unchanged round trip
        assertEquals(40, ok(get("/api/status")).getAsJsonObject("dispense").getAsJsonObject("purgeRow")
                .get("spotsLeft").getAsInt());
    }

    String planBody(File board) {
        return planBody(board, 100);
    }

    /** The board probe points of {@link #setPlaces}. */
    static final String BOARD_POINTS = "\"points\":[[98,78,5.5],[130,78,5.5],[98,95,5.5],[130,95,5.5]]";

    /**
     * Plans the board and ties the board surface to it, as an operator does (plan, probe, save the points
     * with the plan): I4 refuses a board surface that is not tied to the board's registration.
     */
    JsonObject planTied(File board) throws Exception {
        JsonObject plan = ok(post("/api/plan", planBody(board)));
        String id = plan.get("planId").getAsString();
        ok(call("PUT", "/api/surfaces/board", "{" + BOARD_POINTS + ",\"planId\":\"" + id + "\"}"));
        return ok(get("/api/plan/" + id));
    }

    String planBody(File board, double x) {
        return "{\"source\":\"file\",\"file\":\"" + board.getAbsolutePath().replace("\\", "\\\\")
                + "\",\"x\":" + x + ",\"y\":80,\"z\":5.5,\"rotation\":0}";
    }

    @Test
    public void plansAndRunsABoardWithDph() throws Exception {
        setUpBoardRuns();
        File board = writeBoard();
        // §5.7 rule: 0603 is not-started -> the plan is refused, and says why
        JsonObject refused = ok(post("/api/plan", planBody(board)));
        assertFalse(refused.getAsJsonObject("interlocks").get("ok").getAsBoolean());
        assertTrue(refused.toString().contains("STATUS_BELOW_MIN"), refused.toString());
        Res runRefused = post("/api/run", "{\"planId\":\"" + refused.get("planId").getAsString() + "\"}");
        assertEquals(409, runRefused.status);
        assertTrue(runRefused.body.contains("PLAN"), runRefused.toString());

        ok(call("PUT", "/api/pad-types/chip-0603", "{\"status\":\"characterized\"}"));
        JsonObject plan = planTied(board);
        JsonObject sum = plan.getAsJsonObject("summary");
        assertEquals(6, sum.get("pads").getAsInt());
        assertEquals(6, sum.get("dots").getAsInt());
        assertEquals(0, sum.get("blockedPads").getAsInt());
        assertTrue(plan.getAsJsonObject("interlocks").get("ok").getAsBoolean(), plan.toString());
        assertTrue(sum.get("estimatedSeconds").getAsDouble() > 0, sum.toString());
        assertTrue(sum.get("estimatedDph").getAsDouble() > 0, sum.toString());
        assertEquals(8, sum.get("purgeDots").getAsInt());
        assertEquals(8, plan.get("purgeSpotsNeeded").getAsInt(), "one dot size: the profile's purge dots (I9)");
        assertFalse(sum.has("estimatedDphUpperBound"), sum.toString());
        // the plan carries the profile it was made with; no calibration / q any more
        assertFalse(plan.has("calibration"), plan.toString());
        assertEquals("18 psi half turn", plan.getAsJsonObject("profile").get("name").getAsString());
        assertTrue(plan.get("profileFingerprint").getAsString().contains("net=800"), plan.toString());
        assertEquals(6, plan.getAsJsonArray("dots").size());
        for (JsonElement e : plan.getAsJsonArray("dots")) {
            assertEquals(180.1802, e.getAsJsonObject().get("rotationDeg").getAsDouble(), 1e-4, "every dot is one profile dot");
            // N1 contact 5.5 - 2.59 + gap 0.10 (N2 frame)
            assertEquals(3.01, e.getAsJsonObject().get("dispenseZ").getAsDouble(), 1e-6);
        }
        assertTrue(plan.get("cleanCheck").isJsonNull());
        String planId = plan.get("planId").getAsString();
        assertEquals(planId, ok(get("/api/plan/" + planId)).get("planId").getAsString());
        // the board on this seat has no paste yet: it will be board 1 of the session
        JsonObject pasted = plan.getAsJsonObject("pasted");
        assertTrue(pasted.get("board").isJsonNull() && pasted.get("nextBoard").getAsInt() == 1, pasted.toString());

        sim.server.setBStepCount(4321); // the screw's history since the controller's power-up
        sim.server.mark();
        JsonObject run = ok(post("/api/run", "{\"planId\":\"" + planId + "\",\"wait\":true}"));
        JsonObject res = run.getAsJsonObject("result");
        assertEquals("OK", res.get("status").getAsString(), run.toString());
        assertEquals(6, res.get("dotsDone").getAsInt());
        assertTrue(res.get("dphMotion").getAsDouble() > 0);
        assertTrue(res.get("dphOverall").getAsDouble() > 0);
        assertEquals(8, res.get("purgeDots").getAsInt());
        // the start angle comes from the controller's M114 Count (no simulator fallback)
        assertEquals(4321, res.get("bCountAtStart").getAsLong());
        assertFalse(res.get("warnings").toString().contains("B step count taken as 0"), res.toString());
        assertEquals(297.9, res.get("workStartAngleDeg").getAsDouble(), 1e-9, "start angle 298 at 1600 steps/rev");
        assertEquals("18 psi half turn", res.getAsJsonObject("profile").get("name").getAsString());
        List<String> l = commands(sim.server.sinceMark());
        assertTrue(l.indexOf("M106 P2 S255") < l.indexOf("M106 P3 S255"));
        // one pressure session: 8 purge dots + 6 work dots, each a forward and a back move of the screw
        List<String> b = bMoves(l);
        assertEquals(2 * (8 + 6), b.size(), b.toString());
        assertTrue(b.stream().allMatch(x -> x.endsWith(" F64800")), b.toString());
        assertEquals(1, Collections.frequency(l, "M106 P3 S255"), "the valve opens once per board");

        JsonObject s = ok(get("/api/status"));
        assertEquals(6, s.getAsJsonObject("lastRun").get("dotsDone").getAsInt());
        JsonObject progress = s.getAsJsonObject("run");
        assertEquals(6, progress.getAsJsonArray("doneDots").size());
        // the pressure session started with the first pressurised phase (UI: pressure on-time of a run)
        long onAt = progress.get("pressureOnAtMs").getAsLong();
        assertTrue(onAt >= progress.get("startedAtMs").getAsLong()
                && onAt <= progress.get("finishedAtMs").getAsLong(), progress.toString());
        assertTrue(get("/api/runs").array().size() >= 1);
        JsonObject last = ok(get("/api/runs/last")).getAsJsonObject("run");
        assertEquals(6, last.getAsJsonArray("dotTimings").size());
        // I9: the run used 8 purge spots
        JsonObject row = s.getAsJsonObject("dispense").getAsJsonObject("purgeRow");
        assertEquals(8, row.get("usedSpots").getAsInt());
        assertEquals(5, row.get("boardsLeft").getAsInt());
        // rc1 review: the server keeps the board's paste (a new tab or browser sees it): board 1, a dot on each pad
        assertEquals(1, progress.get("boardNumber").getAsInt(), progress.toString());
        assertEquals(1, s.getAsJsonObject("lastRun").get("boardNumber").getAsInt());
        pasted = ok(get("/api/plan/" + planId)).getAsJsonObject("pasted");
        assertEquals(1, pasted.get("board").getAsInt(), pasted.toString());
        assertEquals(6, pasted.get("dots").getAsInt());
        assertEquals(6, pasted.getAsJsonObject("pads").entrySet().size(), pasted.toString());

        // a subset: only the first 2 dots, logged as such
        JsonObject sub = ok(post("/api/run", "{\"planId\":\"" + planId + "\",\"maxDots\":2,\"wait\":true}"));
        assertEquals(2, sub.get("dots").getAsInt());
        assertEquals(2, sub.getAsJsonObject("result").get("dotsDone").getAsInt());
        assertEquals(2, sub.getAsJsonObject("result").get("dotsPlanned").getAsInt());
        assertEquals(planId, sub.get("parentPlanId").getAsString());
        assertEquals(8, ok(get("/api/plan/" + planId)).getAsJsonObject("pasted").get("dots").getAsInt(),
                "a subset run's dots go on the same board");
        // GET /api/plans: the plans kept, newest first, without the run subsets (a fresh browser restores the latest)
        JsonArray kept = get("/api/plans").array();
        List<String> keptIds = new ArrayList<>();
        for (JsonElement e : kept) {
            keptIds.add(e.getAsJsonObject().get("planId").getAsString());
        }
        assertTrue(keptIds.contains(planId), keptIds.toString());
        assertFalse(keptIds.stream().anyMatch(x -> x.contains("-subset-")), keptIds.toString());
        JsonObject first = kept.get(0).getAsJsonObject();
        assertEquals(planId, first.get("planId").getAsString(), "newest first: " + keptIds);
        assertEquals(6, first.get("dots").getAsInt());
        assertEquals("file", first.getAsJsonObject("source").get("source").getAsString());
        // the profile the plan was made with (Records names it without fetching every full plan)
        assertTrue(first.getAsJsonObject("profile").get("name").getAsString().length() > 0, first.toString());
        assertTrue(first.get("profileFingerprint").getAsString().contains("psi="), first.toString());
        assertTrue(first.get("createdAtMs").getAsLong() > 0);
        // the same seat planned again is the same board, with its paste
        JsonObject again = ok(post("/api/plan", planBody(board)));
        assertEquals(8, again.getAsJsonObject("pasted").get("dots").getAsInt(), again.toString());
        assertEquals(1, again.getAsJsonObject("pasted").get("board").getAsInt());
        // the operator wiped the board: a run with "wiped" clears its paste first, then counts its own dots
        JsonObject wiped = ok(post("/api/run", "{\"planId\":\"" + planId + "\",\"maxDots\":2,\"wiped\":true,\"wait\":true}"));
        assertEquals("OK", wiped.getAsJsonObject("result").get("status").getAsString(), wiped.toString());
        JsonObject afterWipe = ok(get("/api/plan/" + planId)).getAsJsonObject("pasted");
        assertEquals(2, afterWipe.get("dots").getAsInt(), afterWipe.toString());
        assertEquals(1, afterWipe.get("board").getAsInt(), "the same board, wiped");
        // "Next board": the board is taken off; the next one is board 2
        assertEquals(2, ok(post("/api/board/next", null)).get("nextBoard").getAsInt());
        JsonObject next = ok(get("/api/plan/" + planId)).getAsJsonObject("pasted");
        assertTrue(next.get("board").isJsonNull() && next.get("dots").getAsInt() == 0 && next.get("nextBoard").getAsInt()
                == 2, next.toString());

        // the purge row was wiped: all spots free again
        JsonObject reset = ok(post("/api/purge-row/reset", null));
        assertEquals(0, reset.get("usedSpots").getAsInt());
        assertEquals(48, reset.get("spotsLeft").getAsInt());
        assertFalse(reset.get("resetAtMs").isJsonNull());
        assertEquals(400, call("PUT", "/api/config", "{\"purgeRow\":{\"resetAtMs\":1}}").status, "stamped by the reset only");

        // a stale plan (needle offset changed) is refused
        ok(post("/api/needle/offset", "{\"offsetMm\":-2.57}"));
        Res stale = post("/api/run", "{\"planId\":\"" + planId + "\"}");
        assertEquals(409, stale.status);
        assertTrue(stale.body.contains("plan the board again"), stale.toString());
        assertEquals(404, post("/api/run", "{\"planId\":\"nope\"}").status);

        // a profile changed after planning: the plan's dots would be the old recipe's
        String fresh = ok(post("/api/plan", planBody(board))).get("planId").getAsString();
        assertEquals(fresh, get("/api/plans").array().get(0).getAsJsonObject().get("planId").getAsString());
        ok(call("PUT", "/api/config", "{\"profile\":{\"dwellMs\":300}}"));
        Res staleProfile = post("/api/run", "{\"planId\":\"" + fresh + "\"}");
        assertEquals(409, staleProfile.status, staleProfile.toString());
        assertTrue(staleProfile.body.contains("dispense profile"), staleProfile.toString());
    }

    /** I11: the start angle is a calibration of the controller power-up; a reconnect asks for it again. */
    @Test
    public void boardRunNeedsTheStartAngleOfThisControllerConnection() throws Exception {
        setUpBoardRuns();
        File board = writeBoard();
        ok(call("PUT", "/api/pad-types/chip-0603", "{\"status\":\"characterized\"}"));
        reconnect();
        JsonObject p = ok(get("/api/status")).getAsJsonObject("dispense").getAsJsonObject("profile");
        assertTrue(p.get("startAngleNeedsConfirm").getAsBoolean(), p.toString());
        assertFalse(p.get("controllerConnectedAtMs").isJsonNull());
        JsonObject plan = planTied(board);
        assertTrue(plan.getAsJsonObject("interlocks").toString().contains("I11"), plan.toString());
        Res run = post("/api/run", "{\"planId\":\"" + plan.get("planId").getAsString() + "\"}");
        assertEquals(409, run.status, run.toString());
        assertTrue(run.body.contains("I11"), run.toString());

        // "still valid": the controller was not power-cycled
        long t0 = System.currentTimeMillis();
        JsonObject conf = ok(post("/api/screw/start-angle", "{}"));
        assertEquals(298, conf.get("startAngleDeg").getAsDouble(), 1e-9, "a confirm keeps the angle");
        assertTrue(conf.get("startAngleSetAtMs").getAsLong() >= t0);
        assertFalse(conf.get("startAngleNeedsConfirm").getAsBoolean());
        assertTrue(ok(get("/api/plan/" + plan.get("planId").getAsString())).getAsJsonObject("interlocks").get("ok")
                .getAsBoolean());

        // a new angle (measured again) changes the profile: plan again
        JsonObject set = ok(post("/api/screw/start-angle", "{\"startAngleDeg\":120,\"note\":\"re-measured\"}"));
        assertEquals(120, set.get("startAngleDeg").getAsDouble(), 1e-9);
        assertEquals(120, set.getAsJsonArray("startAngles").get(0).getAsDouble(), 0.15);
        assertEquals(300, set.getAsJsonArray("startAngles").get(1).getAsDouble(), 0.15);
        Res stale = post("/api/run", "{\"planId\":\"" + plan.get("planId").getAsString() + "\"}");
        assertEquals(409, stale.status, stale.toString());
        assertEquals(400, post("/api/screw/start-angle", "{\"startAngleDeg\":400}").status);
        assertEquals(400, post("/api/screw/start-angle", "{\"startAngleDeg\":-1}").status);
        assertEquals(120, ok(get("/api/status")).getAsJsonObject("dispense").getAsJsonObject("profile")
                .get("startAngleDeg").getAsDouble(), 1e-9, "refused requests change nothing");
    }

    /** Review findings: board Z must be given for a board file; a plan is stale when the board changed. */
    @Test
    public void planNeedsBoardZAndRunRefusesAChangedBoard() throws Exception {
        setUpBoardRuns();
        File board = writeBoard();
        ok(call("PUT", "/api/pad-types/chip-0603", "{\"status\":\"characterized\"}"));
        Res noZ = post("/api/plan", "{\"source\":\"file\",\"file\":\"" + board.getAbsolutePath() + "\",\"x\":100,\"y\":80}");
        assertEquals(400, noZ.status, noZ.toString());
        assertTrue(noZ.body.contains("'z'"), noZ.toString());
        JsonObject plan = planTied(board);
        assertTrue(plan.getAsJsonObject("interlocks").get("ok").getAsBoolean(), plan.toString());
        assertTrue(plan.get("surfaceProbed").getAsBoolean());
        // a placement is edited in OpenPnP after planning
        org.openpnp.model.Placement pl = Configuration.get().getBoard(board).getPlacements().get(0);
        org.openpnp.model.Location was = pl.getLocation();
        pl.setLocation(was.add(new org.openpnp.model.Location(was.getUnits(), 0.8, 0, 0, 0)));
        Res stale = post("/api/run", "{\"planId\":\"" + plan.get("planId").getAsString() + "\"}");
        assertEquals(409, stale.status, stale.toString());
        assertTrue(stale.body.contains("changed in OpenPnP"), stale.toString());
        pl.setLocation(was);
        // the board surface is re-probed after planning: the plan's dot heights are stale (I4)
        String probed = "\"points\":[[98,78,5.52],[130,78,5.5],[98,95,5.5],[130,95,5.5]]";
        ok(call("PUT", "/api/surfaces/board", "{" + probed + "}"));
        Res stale2 = post("/api/run", "{\"planId\":\"" + plan.get("planId").getAsString() + "\"}");
        assertEquals(409, stale2.status, stale2.toString());
        assertTrue(stale2.body.contains("plan the board again"), stale2.toString());
        // F3: probed with planId, the surface is tied to that plan's board registration ...
        JsonObject tied = ok(call("PUT", "/api/surfaces/board", "{" + probed + ",\"planId\":\""
                + plan.get("planId").getAsString() + "\"}"));
        assertTrue(tied.get("boundToBoard").getAsBoolean(), tied.toString());
        JsonObject again = ok(post("/api/plan", planBody(board)));
        assertTrue(again.getAsJsonObject("interlocks").get("ok").getAsBoolean(), again.toString());
        assertFalse(again.getAsJsonObject("interlocks").toString().contains("not tied to a board registration"));
        // ... so a re-seated board (new position) asks for new probes
        JsonObject moved = ok(post("/api/plan", planBody(board, 100.5)));
        assertTrue(moved.getAsJsonObject("interlocks").toString().contains("probed on another registration"),
                moved.toString());
        assertEquals(404, call("PUT", "/api/surfaces/board", "{" + probed + ",\"planId\":\"nope\"}").status);
        // without a board surface the plan says so and a run is refused (I4)
        ok(call("DELETE", "/api/surfaces/board", null));
        JsonObject flat = ok(post("/api/plan", planBody(board)));
        assertFalse(flat.get("surfaceProbed").getAsBoolean());
        assertTrue(flat.getAsJsonObject("interlocks").toString().contains("no 'board' surface"), flat.toString());
    }

    /** Review finding: the needle frame and the surfaces are not settings; they are validated. */
    @Test
    public void needleFrameAndSurfacesHaveTheirOwnEndpoints() throws Exception {
        // the needle touches 2.59 mm below N1 in OpenPnP's N2 frame (bench, side camera)
        JsonObject a = ok(post("/api/needle/anchor", "{\"surfaceZ\":5.5,\"needleContactZ\":2.91}"));
        assertEquals(-2.59, a.get("offsetMm").getAsDouble(), 1e-9);
        assertFalse(a.get("touchedOff").getAsBoolean(), "the frame alone is not a touch-off");
        assertTrue(a.getAsJsonObject("anchor").get("rawSumMm").isJsonNull());
        assertEquals(400, post("/api/needle/anchor", "{\"surfaceZ\":5.5}").status);
        // the bench relation: needle contact raw Z = K - N1 contact Z. The sim machine maps N2's Z
        // like the LumenPnP see-saw (z2 = 63 - raw), so K 65.59 at N1 5.5 = raw 60.09 = N2 Z 2.91
        JsonObject k = ok(post("/api/needle/anchor", "{\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}"));
        assertEquals(2.91, k.getAsJsonObject("anchor").get("needleContactZMm").getAsDouble(), 1e-9);
        assertEquals(-2.59, k.get("offsetMm").getAsDouble(), 1e-9);
        assertEquals(65.59, k.getAsJsonObject("anchor").get("rawSumMm").getAsDouble(), 1e-9);
        assertEquals(400, post("/api/needle/anchor", "{\"surfaceZ\":5.5,\"needleContactZ\":2.91,"
                + "\"needleContactRawSum\":65.59}").status, "one or the other");
        assertEquals(400, call("PUT", "/api/config", "{\"needleAnchor\":null}").status);
        assertEquals(400, call("PUT", "/api/config", "{\"surfaces\":{\"board\":{\"points\":[]}}}").status);
        Res line = call("PUT", "/api/surfaces/board", "{\"points\":[[0,0,5.5],[10,10,5.5],[20,20,5.5]]}");
        assertEquals(400, line.status, line.toString());
        assertEquals(400, call("PUT", "/api/surfaces/board", "{\"points\":[[0,0,5.5],[10,0,5.5]]}").status);
        JsonObject sf = ok(call("PUT", "/api/surfaces/board",
                "{\"points\":[{\"x\":0,\"y\":0,\"z\":5.5,\"bx\":8},{\"x\":30,\"y\":0,\"z\":5.6},{\"x\":0,\"y\":30,\"z\":5.4}]}"));
        assertEquals(0, sf.getAsJsonObject("plane").get("maxResidualMm").getAsDouble(), 1e-9);
        JsonObject st = ok(get("/api/status")).getAsJsonObject("dispense");
        assertTrue(st.getAsJsonObject("needle").get("anchored").getAsBoolean());
        assertTrue(st.getAsJsonObject("surfaces").has("board"));
        // Setup › Settings › Refuse flagged pads (rc1 review): the readiness reads it from the status
        assertTrue(st.get("refuseFlaggedPads").getAsBoolean());
        ok(call("PUT", "/api/config", "{\"refuseFlaggedPads\":false}"));
        assertFalse(ok(get("/api/status")).getAsJsonObject("dispense").get("refuseFlaggedPads").getAsBoolean());
        ok(call("PUT", "/api/config", "{\"refuseFlaggedPads\":true}"));
        // a needle change invalidates the frame
        assertTrue(st.getAsJsonObject("needle").get("kRefusal").isJsonNull());
        ok(post("/api/needle/changed", null));
        JsonObject changed = ok(get("/api/status")).getAsJsonObject("dispense").getAsJsonObject("needle");
        assertFalse(changed.get("anchored").getAsBoolean());
        // K belongs to the needle it was measured with: the new needle's frame is measured with both tips
        assertTrue(changed.get("kRefusal").getAsString().startsWith("K was measured with the needle before the change at"),
                changed.toString());
        Res fromK = post("/api/needle/anchor", "{\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}");
        assertEquals(409, fromK.status, fromK.toString());
        assertTrue(fromK.body.contains("record the needle frame with both tips touching the same spot"), fromK.toString());
        ok(post("/api/needle/anchor", "{\"surfaceZ\":5.5,\"needleContactZ\":2.71}"));
        JsonObject again = ok(get("/api/status")).getAsJsonObject("dispense").getAsJsonObject("needle");
        assertTrue(again.get("anchored").getAsBoolean() && again.get("kRefusal").isJsonNull(), again.toString());
        assertEquals(-2.79, again.get("anchorOffsetMm").getAsDouble(), 1e-9);
    }

    /** TCP proxy that delays every "ok" from the controller, so a run takes long enough to STOP it. */
    static final class SlowProxy implements AutoCloseable {
        final java.net.ServerSocket ss;
        final List<Socket> sockets = new java.util.concurrent.CopyOnWriteArrayList<>();

        SlowProxy(int targetPort, long okDelayMs) throws Exception {
            ss = new java.net.ServerSocket(0, 5, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> {
                while (!ss.isClosed()) {
                    try {
                        Socket client = ss.accept();
                        Socket up = new Socket(InetAddress.getLoopbackAddress(), targetPort);
                        sockets.add(client);
                        sockets.add(up);
                        pump(client, up, 0);
                        pump(up, client, okDelayMs);
                    }
                    catch (Exception e) {
                        // closed
                    }
                }
            }, "SlowProxy");
            t.setDaemon(true);
            t.start();
        }

        static void pump(Socket from, Socket to, long okDelayMs) {
            Thread t = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(from.getInputStream(),
                        StandardCharsets.UTF_8))) {
                    OutputStream out = to.getOutputStream();
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (okDelayMs > 0 && line.startsWith("ok")) {
                            Thread.sleep(okDelayMs);
                        }
                        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
                catch (Exception e) {
                    // closed
                }
            }, "SlowProxy-pump");
            t.setDaemon(true);
            t.start();
        }

        int port() {
            return ss.getLocalPort();
        }

        @Override
        public void close() throws Exception {
            ss.close();
            for (Socket s : sockets) {
                s.close();
            }
        }
    }

    @Test
    public void stopDuringARunAbortsTheEngineAndVentsBeforeOpenPnpStops() throws Exception {
        setUpBoardRuns();
        double[][] pos = new double[30][];
        for (int i = 0; i < pos.length; i++) {
            // 4 mm apart: at 2.5 mm two 0603s' facing dots would bridge (MAY_BRIDGE)
            pos[i] = new double[] {2 + 4.0 * (i % 6), 2 + 3 * (i / 6), 0};
        }
        File board = writeBoard(pos);
        ok(call("PUT", "/api/pad-types/chip-0603", "{\"status\":\"characterized\"}"));
        // reconnect OpenPnP through the slow proxy (still only localhost sockets)
        try (SlowProxy proxy = new SlowProxy(sim.server.getPort(), 10)) {
            sim.machine.setEnabled(false);
            sim.driver.disconnect(); // connection-keep-alive would otherwise keep the direct link
            ((org.openpnp.machine.reference.driver.TcpCommunications) sim.driver.getCommunications())
                    .setPort(proxy.port());
            sim.machine.setEnabled(true);
            sim.machine.home();
            ok(post("/api/screw/start-angle", "{}")); // I11: a reconnect asks for the start angle again
            JsonObject plan = planTied(board);
            assertEquals(60, plan.getAsJsonObject("summary").get("dots").getAsInt(), plan.toString());
            assertTrue(plan.getAsJsonObject("interlocks").get("ok").getAsBoolean(), plan.toString());
            assertEquals(202, post("/api/run", "{\"planId\":\"" + plan.get("planId").getAsString() + "\"}").status);
            long t0 = System.currentTimeMillis();
            JsonObject s;
            do {
                Thread.sleep(20);
                s = ok(get("/api/status"));
            } while ((s.get("run").isJsonNull() || s.getAsJsonObject("run").get("dotsDone").getAsInt() < 2)
                    && System.currentTimeMillis() - t0 < 30000);
            sim.server.mark();
            JsonObject stop = ok(post("/api/stop", null));
            t0 = System.currentTimeMillis();
            do {
                Thread.sleep(20);
                s = ok(get("/api/status"));
            } while (s.getAsJsonObject("run").get("running").getAsBoolean() && System.currentTimeMillis() - t0 < 30000);
            JsonObject run = s.getAsJsonObject("run");
            assertEquals("ABORTED", run.get("status").getAsString(), run.toString());
            assertTrue(run.get("dotsDone").getAsInt() < 60, run.toString());
            assertFalse(s.getAsJsonObject("machine").get("enabled").getAsBoolean(), stop.toString());
            List<String> l = commands(sim.server.sinceMark());
            // engine I1 after STOP: the bench F9 sequence, in one piece
            assertEquals(Arrays.asList("M410", "M107 P3", "M107 P2", "M107 P1", "M107 P0", "M18 B"),
                    ControllerCommands.STOP_SEQUENCE);
            assertTrue(Collections.indexOfSubList(l, ControllerCommands.STOP_SEQUENCE) >= 0, "engine I1 after STOP: " + l);
            // this config has no DISABLE_COMMAND: STOP waited for the engine before OpenPnP's Stop
            assertTrue(stop.toString().contains("engine"), stop.toString());
        }
    }

    @Test
    public void scaleEntriesCreateRecords() throws Exception {
        JsonObject c = ok(post("/api/calibrations/scale-shots",
                "{\"rotationDegPerShot\":360,\"perShotMassMg\":\"1.80, 1.82, 1.79\",\"tip\":\"22G\"}"))
                        .getAsJsonObject("calibration");
        // q = mass / density per turn = 1.8033 / 3.7
        assertEquals(1.8033 / 3.7, c.get("qMm3PerRev").getAsDouble(), 1e-3);
        assertEquals(3, c.get("qN").getAsInt());
        assertEquals("provisional", c.get("status").getAsString());
        // the record notes the profile's regulator pressure and screw feed (180 rpm = 1080 deg/s)
        assertEquals(18, c.get("setPressurePsi").getAsDouble(), 1e-9);
        assertEquals(1080, c.get("bFeedDegS").getAsDouble(), 1e-9);
        long id = c.get("id").getAsLong();
        JsonObject row = ok(post("/api/calibrations/scale-dots",
                "{\"calibrationId\":" + id + ",\"rotationDeg\":58,\"count\":300,\"totalMassMg\":84,\"scaleSdMg\":0.5}"))
                        .getAsJsonObject("row");
        assertEquals(0.28, row.get("mgPerDotMean").getAsDouble(), 1e-9);
        JsonObject all = ok(get("/api/calibrations"));
        assertTrue(all.getAsJsonArray("calibrations").size() >= 2);
        assertEquals(1, get("/api/calibrations/" + id + "/ladder").array().size());
        assertEquals(400, post("/api/calibrations/scale-shots", "{\"rotationDegPerShot\":360}").status);
    }

    @Test
    public void settingsAreValidatedAndNeverPwm() throws Exception {
        // review finding: no G-code string is a setting (PWM pump/valve, swapped ports, a valve OFF
        // that never vents, an M106 as capability query or keepalive ping)
        for (String k : new String[] {"pumpOnGcode", "pumpOffGcode", "valveOnGcode", "valveOffGcode",
                "capabilityQueryGcode", "keepaliveArmGcode", "keepalivePingGcode", "keepaliveDisarmGcode"}) {
            Res pwm = call("PUT", "/api/config", "{\"" + k + "\":\"M106 P2 S128\"}");
            assertEquals(400, pwm.status, k + ": " + pwm);
            assertTrue(pwm.body.contains("unknown setting"), pwm.toString());
        }
        assertEquals(400, call("PUT", "/api/config", "{\"noSuchSetting\":1}").status);
        // I4 state cannot be forged through the settings; an unchanged round-trip is accepted
        Res forge = call("PUT", "/api/config", "{\"needleZOffsetSetAtMs\":" + System.currentTimeMillis() + "}");
        assertEquals(400, forge.status, forge.toString());
        // nor the Z frame of the touch-off (the home it was measured under, ZCheck)
        forge = call("PUT", "/api/config", "{\"needleZOffsetHomeAtMs\":" + System.currentTimeMillis() + "}");
        assertEquals(400, forge.status, forge.toString());
        JsonObject all = ok(get("/api/config"));
        assertEquals(200, call("PUT", "/api/config", all.toString()).status);
        assertEquals(404, get("/static/..").status);
        // removed with the bench recipe (schema 3): the gap is the profile's, the purge row replaced
        // the prime + test dot, the q model is gone
        for (String k : new String[] {"needleGapMm", "primeAndTestDot", "testDotOpticalCheck", "bFeedDegS",
                "retractDeg", "dwellMs", "provisionalQMm3PerRev"}) {
            Res gone = call("PUT", "/api/config", "{\"" + k + "\":1}");
            assertEquals(400, gone.status, k + ": " + gone);
            assertTrue(gone.body.contains("unknown setting"), gone.toString());
        }
        // gap: 0.05-0.15 behave the same, >= 0.20 misses dots
        Res wide = call("PUT", "/api/config", "{\"profile\":{\"gapMm\":0.2}}");
        assertEquals(400, wide.status, wide.toString());
        assertTrue(wide.body.contains("gapMm must be 0.05 to 0.15"), wide.toString());
        JsonObject c = ok(call("PUT", "/api/config", "{\"profile\":{\"gapMm\":0.15}}"));
        assertEquals(0.15, c.getAsJsonObject("profile").get("gapMm").getAsDouble(), 1e-9);
        assertEquals(3, c.get("schemaVersion").getAsInt());
        assertFalse(c.toString().contains("Gcode"), c.toString());
    }

    /** PUT /api/config merges the nested settings one level deep; stamped fields are the engine's. */
    @Test
    public void nestedSettingsMergeOneLevelDeep() throws Exception {
        JsonObject c = ok(call("PUT", "/api/config", "{\"profile\":{\"dwellMs\":300}}"));
        JsonObject p = c.getAsJsonObject("profile");
        assertEquals(300, p.get("dwellMs").getAsInt());
        // the other profile fields are kept
        assertEquals("18 psi half turn", p.get("name").getAsString());
        assertEquals(18, p.get("pressurePsi").getAsDouble(), 1e-9);
        assertEquals(800, p.get("netStepsPerDot").getAsInt());
        assertEquals(10, p.get("suckBackDeg").getAsDouble(), 1e-9);
        assertEquals(180, p.get("screwRpm").getAsDouble(), 1e-9);
        assertEquals(8, p.get("purgeDots").getAsInt());
        assertTrue(p.get("startAngleSetAtMs").isJsonNull(), "a dwell change does not stamp the start angle");

        Res unknown = call("PUT", "/api/config", "{\"profile\":{\"noSuchKnob\":1}}");
        assertEquals(400, unknown.status, unknown.toString());
        assertTrue(unknown.body.contains("profile.noSuchKnob"), unknown.toString());
        assertEquals(400, call("PUT", "/api/config", "{\"profile\":5}").status, "a nested setting is an object");
        // the profile is validated as a whole
        assertEquals(400, call("PUT", "/api/config", "{\"profile\":{\"netStepsPerDot\":700}}").status,
                "700 steps does not divide a revolution");
        assertEquals(400, call("PUT", "/api/config", "{\"profile\":{\"screwRpm\":450}}").status);
        assertEquals(400, call("PUT", "/api/config", "{\"cleanPadCheck\":{\"minBrightFraction\":1.5}}").status);

        // the start angle's set time is stamped by the engine only ...
        Res forged = call("PUT", "/api/config", "{\"profile\":{\"startAngleSetAtMs\":" + System.currentTimeMillis() + "}}");
        assertEquals(400, forged.status, forged.toString());
        assertTrue(forged.body.contains("profile.startAngleSetAtMs"), forged.toString());
        // ... and a changed start angle is stamped (it is a calibration of this controller power-up)
        long t0 = System.currentTimeMillis();
        p = ok(call("PUT", "/api/config", "{\"profile\":{\"startAngleDeg\":118}}")).getAsJsonObject("profile");
        assertEquals(118, p.get("startAngleDeg").getAsDouble(), 1e-9);
        assertTrue(p.get("startAngleSetAtMs").getAsLong() >= t0, p.toString());
        // an unchanged stamp in a round-trip is fine
        assertEquals(200, call("PUT", "/api/config", "{\"profile\":{\"startAngleSetAtMs\":"
                + p.get("startAngleSetAtMs").getAsLong() + ",\"dwellMs\":250}}").status);

        // needle XY trim: the set time is stamped when dx/dy change
        JsonObject t = ok(call("PUT", "/api/config", "{\"needleXyTrim\":{\"dxMm\":0.04,\"dyMm\":-0.02,"
                + "\"note\":\"landing data\"}}")).getAsJsonObject("needleXyTrim");
        assertEquals(0.04, t.get("dxMm").getAsDouble(), 1e-9);
        assertTrue(t.get("setAtMs").getAsLong() >= t0);
        assertEquals(400, call("PUT", "/api/config", "{\"needleXyTrim\":{\"setAtMs\":1}}").status);
        assertEquals(400, call("PUT", "/api/config", "{\"needleXyTrim\":{\"dxMm\":2}}").status, "fix the head offset");
        JsonObject st = ok(get("/api/status")).getAsJsonObject("dispense");
        assertEquals(-0.02, st.getAsJsonObject("needle").getAsJsonObject("xyTrim").get("dyMm").getAsDouble(), 1e-9);

        // purge row: a partial object keeps the pitch default
        JsonObject row = ok(call("PUT", "/api/config", "{\"purgeRow\":{\"x0Mm\":80,\"y0Mm\":47,\"spots\":16}}"))
                .getAsJsonObject("purgeRow");
        assertEquals(2.5, row.get("pitchXMm").getAsDouble(), 1e-9);
        assertEquals("blank", row.get("surfaceName").getAsString());
        assertEquals(400, call("PUT", "/api/config", "{\"purgeRow\":{\"pitchXMm\":0.5}}").status, "dots would overlap");
    }

    /** I1 outside runs: OpenPnP's Stop must send the bench F9 sequence in one piece (DISABLE_COMMAND). */
    @Test
    public void stopCheckWantsTheExactF9Sequence() throws Exception {
        JsonObject i1 = interlock("I1");
        assertEquals("warning", i1.get("state").getAsString(), "the sim config has no DISABLE_COMMAND: " + i1);
        assertTrue(i1.get("text").getAsString().contains("M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B"), i1.toString());
        // the old 4-line Stop (no left valve/pump OFF) is not enough
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND, "M410\nM107 P3\nM107 P2\nM18 B");
        i1 = interlock("I1");
        assertEquals("warning", i1.get("state").getAsString(), i1.toString());
        JsonObject det = i1.getAsJsonObject("detail");
        assertTrue(det.get("valveOff").getAsBoolean() && det.get("pumpOff").getAsBoolean(), det.toString());
        assertFalse(det.get("stopSequence").getAsBoolean(), det.toString());
        // out of order is not enough either
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND,
                "M410\nM107 P2\nM107 P3\nM107 P1\nM107 P0\nM18 B");
        assertEquals("warning", interlock("I1").get("state").getAsString());
        // the F9 sequence, with comments and extra lines around it
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND,
                "M400 ; settle\nM410 ; quick stop\nM107 P3\nM107  P2\nM107 P1\nM107 P0\nM18 B\nM84");
        i1 = interlock("I1");
        assertEquals("ok", i1.get("state").getAsString(), i1.toString());
        assertTrue(i1.getAsJsonObject("detail").get("ok").getAsBoolean());
        assertEquals(6, i1.getAsJsonObject("detail").getAsJsonArray("expected").size());
    }

    JsonObject interlock(String id) throws Exception {
        for (JsonElement e : ok(get("/api/status")).getAsJsonArray("interlocks")) {
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError(id + " not in the interlock list");
    }

    /**
     * I10: the camera-only clean-pad check of a plan (kept with the plan), and a board run refused
     * before the pressure goes on when a pad still carries paste.
     */
    @Test
    public void cleanPadCheckFindsPasteLeftOnAPad() throws Exception {
        setUpBoardRuns();
        ok(call("PUT", "/api/pad-types/chip-0603", "{\"status\":\"characterized\"}"));
        File board = writeBoard();
        FakeImager cam = new FakeImager();
        svc.setDotImager(cam);
        ok(call("PUT", "/api/config", "{\"cleanPadCheck\":{\"enabled\":true}}"));
        JsonObject plan = planTied(board);
        assertTrue(plan.getAsJsonObject("interlocks").get("ok").getAsBoolean(), plan.toString());
        String planId = plan.get("planId").getAsString();
        JsonObject dirtyDot = plan.getAsJsonArray("dots").get(2).getAsJsonObject();
        String dirtyPad = dirtyDot.get("padId").getAsString();
        cam.dirty.put(FakeImager.key(dirtyDot.get("x").getAsDouble(), dirtyDot.get("y").getAsDouble()), 0.42);

        sim.server.mark();
        JsonObject r = ok(post("/api/plan/" + planId + "/clean-check", "{\"wait\":true}"));
        JsonObject cc = r.getAsJsonObject("result");
        assertFalse(cc.get("ok").getAsBoolean(), cc.toString());
        assertEquals(6, cc.get("targets").getAsInt());
        assertEquals(Collections.singletonList(dirtyPad), Arrays.asList(new com.google.gson.Gson()
                .fromJson(cc.getAsJsonArray("dirtyPadIds"), String[].class)));
        int clean = 0;
        for (JsonElement e : cc.getAsJsonArray("pads")) {
            JsonObject pad = e.getAsJsonObject();
            boolean dirty = pad.get("padId").getAsString().equals(dirtyPad);
            assertEquals(dirty ? 0.42 : 0.95, pad.get("brightFraction").getAsDouble(), 1e-9);
            clean += pad.get("clean").getAsBoolean() ? 1 : 0;
        }
        assertEquals(5, clean);
        assertEquals(6, cam.captured.size(), "one picture per distinct dot");
        assertFalse(commands(sim.server.sinceMark()).contains("M106 P2 S255"), "camera only: no pressure");
        // kept with the plan
        JsonObject again = ok(get("/api/plan/" + planId));
        assertFalse(again.get("cleanCheckRunning").getAsBoolean());
        assertEquals(dirtyPad, again.getAsJsonObject("cleanCheck").getAsJsonArray("dirtyPadIds").get(0).getAsString());

        // a run repeats the check right before the pressure goes on, and refuses
        sim.server.mark();
        JsonObject run = ok(post("/api/run", "{\"planId\":\"" + planId + "\",\"wait\":true}")).getAsJsonObject("result");
        assertEquals("REFUSED", run.get("status").getAsString(), run.toString());
        assertTrue(run.get("message").getAsString().startsWith("I10"), run.toString());
        assertTrue(run.get("message").getAsString().contains(dirtyPad + " (0.42)"), run.toString());
        assertEquals(dirtyPad, run.getAsJsonObject("cleanCheck").getAsJsonArray("dirtyPadIds").get(0).getAsString());
        List<String> l = commands(sim.server.sinceMark());
        assertFalse(l.contains("M106 P2 S255") || l.contains("M106 P3 S255"), "nothing pressurised: " + l);
        assertTrue(bMoves(l).isEmpty(), l.toString());
        assertEquals(0, ok(get("/api/status")).getAsJsonObject("dispense").getAsJsonObject("purgeRow")
                .get("usedSpots").getAsInt(), "no purge spot used");

        // wiped: the run goes through
        cam.dirty.clear();
        run = ok(post("/api/run", "{\"planId\":\"" + planId + "\",\"wait\":true}")).getAsJsonObject("result");
        assertEquals("OK", run.get("status").getAsString(), run.toString());
        assertTrue(run.getAsJsonObject("cleanCheck").get("ok").getAsBoolean());

        // without a camera the check cannot run: I10 refuses the plan
        svc.setDotImager(new FakeImager() {
            @Override
            public boolean isAvailable() {
                return false;
            }
        });
        JsonObject blind = ok(post("/api/plan", planBody(board)));
        assertTrue(blind.getAsJsonObject("interlocks").toString().contains("I10"), blind.toString());
    }

    @Test
    public void cameraAndBoardsWithoutGui() throws Exception {
        // the sim machine has no camera
        Res cam = get("/api/camera/top.jpg");
        assertEquals(404, cam.status, cam.toString());
        JsonObject b = ok(get("/api/boards"));
        assertFalse(b.get("jobAvailable").getAsBoolean(), "headless: no OpenPnP job window");
        assertTrue(get("/api/packages").array().size() > 0);
        assertTrue(ok(get("/api")).getAsJsonArray("routes").size() > 20);
    }

    @Test
    public void homeAndStartGoThroughOpenPnp() throws Exception {
        ok(post("/api/stop", null));
        assertEquals(409, post("/api/machine/home", "{}").status, "not started");
        JsonObject en = ok(post("/api/machine/enable", null));
        assertTrue(en.get("enabled").getAsBoolean());
        JsonObject h = ok(post("/api/machine/home", "{\"wait\":true}"));
        assertTrue(h.get("homed").getAsBoolean());
    }
}
