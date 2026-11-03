package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.ControllerCommands;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.FlatVacuum;
import org.openpnp.instancelabs.dispense.PadGeometry;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.instancelabs.sim.SimBoardRenderer;
import org.openpnp.instancelabs.sim.SimScene;
import org.openpnp.instancelabs.sim.SimVacuum;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.Package;
import org.openpnp.model.Part;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Register board end to end in SIM (AUTO_REGISTER_DESIGN §10.2): OpenPnP's GcodeDriver over loopback
 * TCP to the RecordingGcodeServer, which answers M115 as OpenPnP's simulator and M261 from the SIM
 * vacuum of the scene; the SIM top camera ({@link SceneImager}) draws the board at its true seat. The
 * seat is deliberately moved, turned and scaled from where the board is planned; every expected
 * position is computed from the scene's transforms. No device is opened.
 */
public class RegistrationSimTest {
    /** Where the board is planned (its nominal seat), N1 contact heights of the needle frame and the blank. */
    static final double NX = 150, NY = 100, NZ = 5.5, BLANK_Z = 5.5;
    static final double[][] FIDUCIALS = {{3, 3}, {37, 27}, {3, 27}};
    static final double[][] RESISTORS = {{12, 8, 0}, {20, 15, 90}, {28, 22, 0}, {30, 8, 0}, {12, 22, 0}};

    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;
    File boardFile;
    volatile SimScene scene;
    SceneImager cam;

    /** S1: the board origin moved +0.60 / -0.45 mm, turned +0.20 deg, scaled 1.002 / 0.997, sheared 0.1 deg. */
    static AffineTransform s1() {
        return SimScene.seat(NX + 0.60, NY - 0.45, 0.20, 1.002, 0.997, 0.1);
    }

    /** The nominal seat: where the plan puts the board. */
    static AffineTransform nominal() {
        return AffineTransform.getTranslateInstance(NX, NY);
    }

    /** A re-seat in the machine frame about the board origin of {@code base}: T(base(0) + d) R(drot) T(-base(0)) base. */
    static AffineTransform reseat(AffineTransform base, double dx, double dy, double drotDeg) {
        Point2D o = base.transform(new Point2D.Double(0, 0), null);
        AffineTransform t = AffineTransform.getTranslateInstance(o.getX() + dx, o.getY() + dy);
        t.rotate(Math.toRadians(drotDeg));
        t.translate(-o.getX(), -o.getY());
        t.concatenate(base);
        return t;
    }

    static Point2D apply(AffineTransform t, double bx, double by) {
        return t.transform(new Point2D.Double(bx, by), null);
    }

    static SimScene scene(AffineTransform seat) {
        SimScene sc = new SimScene();
        sc.boardFile = "reg-test.board.xml";
        sc.boardWidthMm = 40;
        sc.boardHeightMm = 30;
        sc.seat = seat;
        // N1 contact Z on the board (board mm), slightly tilted; the bed under every probe floor
        sc.boardSurface = new SimScene.Plane(5.43, 0.0006, -0.0009);
        sc.blankRect = new double[] {55, 25, 210, 60};
        sc.blankSurface = new SimScene.Plane(BLANK_Z, 0, 0);
        sc.bedZ = 2.5;
        // il_sim_scene.py FAST_VACUUM with the FAST_TIMINGS of the config: no settle or release lag
        sc.vacuum.tauS = 0;
        sc.vacuum.sealTauS = 0;
        sc.vacuum.releaseTauS = 0;
        sc.validate();
        return sc;
    }

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine();
        svc = DispenseService.start(Configuration.get());
        assertTrue(svc.isAvailable(), String.valueOf(svc.getUnavailableReason()));
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        boardFile = writeBoard();
        scene = scene(s1());
        sim.server.vacuum = new SimVacuum(() -> scene, System::nanoTime);
        sim.server.firmwareName = FlatVacuum.SIMULATOR;
        cam = new SceneImager(() -> scene, this::board);
        svc.setDotImager(cam);
        setUp();
    }

    @AfterEach
    public void after() throws Exception {
        api.stop();
        svc.shutdown();
        sim.close();
    }

    Board board() {
        try {
            return Configuration.get().getBoard(boardFile);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 40 x 30 mm: FID1-FID3 (Fiducial_1mm_Mask3mm, no footprint pads) in three corners, five 0603 resistors. */
    File writeBoard() throws Exception {
        Package fidPkg = new Package("Fiducial_1mm_Mask3mm");
        Configuration.get().addPackage(fidPkg);
        Part fid = new Part("FID-REG-TEST");
        fid.setPackage(fidPkg);
        Configuration.get().addPart(fid);
        Package pkg = new Package("R_0603_1608Metric");
        Configuration.get().addPackage(pkg);
        Part part = new Part("R0603-REG-TEST");
        part.setPackage(pkg);
        Configuration.get().addPart(part);
        return writeBoard("reg-test.board.xml", true);
    }

    /** The 40 x 30 mm board with its five resistors, with or without FID1-FID3 (the parts exist). */
    File writeBoard(String name, boolean fiducials) throws Exception {
        StringBuilder sb = new StringBuilder("<openpnp-board version=\"1.1\" name=\"" + name + "\">\n"
                + "<dimensions units=\"Millimeters\" x=\"40.0\" y=\"30.0\" z=\"0.0\" rotation=\"0.0\"/>\n<placements>\n");
        for (int i = 0; fiducials && i < FIDUCIALS.length; i++) {
            placement(sb, "FID" + (i + 1), "FID-REG-TEST", "Fiducial", FIDUCIALS[i][0], FIDUCIALS[i][1], 0);
        }
        for (int i = 0; i < RESISTORS.length; i++) {
            placement(sb, "R" + (i + 1), "R0603-REG-TEST", "Placement", RESISTORS[i][0], RESISTORS[i][1],
                    RESISTORS[i][2]);
        }
        sb.append("</placements>\n<fiducials/>\n</openpnp-board>\n");
        File f = new File(sim.configDir, name);
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    static void placement(StringBuilder sb, String id, String part, String type, double x, double y, double rot) {
        sb.append("<placement version=\"1.4\" side=\"Top\" id=\"").append(id).append("\" part-id=\"").append(part)
                .append("\" type=\"").append(type).append("\" enabled=\"true\" rank=\"0\">\n")
                .append("<location units=\"Millimeters\" x=\"").append(x).append("\" y=\"").append(y)
                .append("\" z=\"0.0\" rotation=\"").append(rot).append("\"/>\n")
                .append("<error-handling>Default</error-handling>\n</placement>\n");
    }

    /**
     * The bench setup in this machine's frame: places and the purge row on the blank (5.5), the needle
     * frame (N1 5.5, K 65.59), the blank surface by hand, 0603 characterized, the simulator's probe
     * timings, the start angle confirmed for this connection.
     */
    void setUp() throws Exception {
        ok(call("PUT", "/api/locations/waste", "{\"x\":80,\"y\":40,\"z\":5.5}"));
        ok(call("PUT", "/api/locations/blank", "{\"x\":90,\"y\":40,\"z\":5.5}"));
        ok(post("/api/needle/anchor", "{\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}"));
        ok(post("/api/needle/offset", "{\"offsetMm\":-2.59}"));
        ok(call("PUT", "/api/surfaces/blank", "{\"points\":[[60,30,5.5],[200,30,5.5],[60,55,5.5],[200,55,5.5]]}"));
        ok(call("PUT", "/api/config", "{\"purgeRow\":{\"x0Mm\":80,\"y0Mm\":47,\"pitchXMm\":2.5,\"spots\":48},"
                + "\"vacuumProbe\":{\"vacuumSettleMs\":300,\"readDelayMs\":0}}"));
        ok(call("PUT", "/api/pad-types/chip-0603", "{\"status\":\"characterized\"}"));
        ok(post("/api/screw/start-angle", "{}"));
    }

    // ------------------------------------------------------------------------------------------
    // HTTP helpers
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

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    Res call(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(180));
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

    static JsonObject started(Res r) {
        assertEquals(202, r.status, r.toString());
        return r.json();
    }

    static JsonObject ok(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.json();
    }

    String planBody(String registration) {
        return "{\"source\":\"file\",\"file\":\"" + boardFile.getAbsolutePath().replace("\\", "\\\\") + "\",\"x\":" + NX
                + ",\"y\":" + NY + ",\"z\":" + NZ + ",\"rotation\":0" + (registration == null ? ""
                        : ",\"registration\":\"" + registration + "\"") + "}";
    }

    JsonObject plan() throws Exception {
        return ok(post("/api/plan", planBody(null)));
    }

    JsonObject register(String planId, String body) throws Exception {
        JsonObject r = ok(post("/api/plan/" + planId + "/register", body));
        assertTrue(r.get("finished").getAsBoolean(), r.toString());
        return r.getAsJsonObject("result");
    }

    /** Polls the status until {@code until} holds (30 s at most). */
    JsonObject waitStatus(Predicate<JsonObject> until) throws Exception {
        long t0 = System.currentTimeMillis();
        JsonObject s;
        do {
            s = ok(get("/api/status"));
            if (until.test(s)) {
                return s;
            }
            Thread.sleep(15);
        } while (System.currentTimeMillis() - t0 < 30000);
        throw new AssertionError("status never reached the expected state: " + s);
    }

    static boolean finished(JsonObject s) {
        return !s.get("run").isJsonNull() && !s.getAsJsonObject("run").get("running").getAsBoolean();
    }

    static String phase(JsonObject s) {
        JsonObject run = s.get("run").isJsonNull() ? null : s.getAsJsonObject("run");
        return run == null || run.get("phase").isJsonNull() ? "" : run.get("phase").getAsString();
    }

    static List<String> commands(List<String> lines) {
        List<String> l = new ArrayList<>();
        for (String x : lines) {
            if (!x.startsWith(";")) {
                l.add(x);
            }
        }
        return l;
    }

    static int count(List<String> l, String line) {
        return Collections.frequency(l, line);
    }

    /** The Z words of the G1 lines that move Z only (the probe's and the home's raise). */
    static List<Double> zMoves(List<String> l) {
        List<Double> z = new ArrayList<>();
        for (String x : l) {
            if (x.matches("G1 Z-?[0-9.]+ F\\d+")) {
                z.add(Double.parseDouble(x.substring(4, x.indexOf(' ', 4))));
            }
        }
        return z;
    }

    /** True pad centres: the board's pads (board mm, as the renderer draws them) through a seat. */
    Map<String, Point2D> truePads(AffineTransform seat) throws Exception {
        Map<String, Point2D> m = new HashMap<>();
        for (PadGeometry g : SimBoardRenderer.boardPads(board(), svc.padTypeResolver())) {
            m.put(g.padId, apply(seat, g.xMm, g.yMm));
        }
        return m;
    }

    /** Largest distance of the plan's pads and dots from the true pad centres (mm). */
    double worstPadError(JsonObject plan, Map<String, Point2D> pads) {
        double worst = 0;
        int n = 0;
        for (JsonElement e : plan.getAsJsonArray("pads")) {
            JsonObject p = e.getAsJsonObject();
            Point2D t = pads.get(p.get("padId").getAsString());
            assertNotNull(t, p.toString());
            worst = Math.max(worst, t.distance(p.get("x").getAsDouble(), p.get("y").getAsDouble()));
            n++;
        }
        for (JsonElement e : plan.getAsJsonArray("dots")) {
            JsonObject d = e.getAsJsonObject();
            Point2D t = pads.get(d.get("padId").getAsString());
            worst = Math.max(worst, t.distance(d.get("x").getAsDouble(), d.get("y").getAsDouble()));
        }
        assertEquals(2 * RESISTORS.length, n, "every pad planned");
        return worst;
    }

    // ------------------------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------------------------

    /**
     * §10.2 cases 1 and 2: a first registration of a moved, turned and scaled seat finds the fiducials
     * where the scene put them, probes the board and plans it again on the true pads; the run of that
     * plan goes through (the clean check sees copper at every dot); the same seat registered again keeps
     * its record and re-touches 3 points.
     */
    @Test
    public void registersAMovedTurnedScaledSeatAndItsDotsLandOnThePads() throws Exception {
        JsonObject p0 = plan();
        String p0Id = p0.get("planId").getAsString();
        assertTrue(p0.get("registration").isJsonNull(), "nothing registered yet");
        Map<String, Point2D> nominalPads = truePads(nominal());
        Map<String, Point2D> s1Pads = truePads(s1());
        // sensitive by construction: the nominal plan misses the true pads by more than a pad's half width
        assertTrue(worstPadError(p0, s1Pads) > 0.4, "nominal vs S1: " + worstPadError(p0, s1Pads));
        assertTrue(worstPadError(p0, nominalPads) < 1e-6);
        JsonObject preview = p0.getAsJsonObject("registrationTargets");
        assertEquals(3, preview.getAsJsonArray("fiducials").size(), preview.toString());
        assertTrue(preview.get("error").isJsonNull(), preview.toString());
        assertTrue(preview.getAsJsonObject("frame").get("ok").getAsBoolean(), preview.toString());

        // without wait: the live detail and the next plan show in the status
        sim.server.mark();
        Res started = post("/api/plan/" + p0Id + "/register", "{}");
        assertEquals(202, started.status, started.toString());
        JsonObject acc = started.json();
        assertEquals("board registration", acc.get("kind").getAsString());
        assertFalse(acc.getAsJsonObject("home").get("needed").getAsBoolean(), acc.toString());
        assertEquals(3, acc.getAsJsonArray("fiducials").size());
        assertTrue(acc.getAsJsonArray("probeTargets").size() >= 3, acc.toString());
        boolean[] sawDetail = {false, false};
        JsonObject st = waitStatus(x -> {
            JsonObject run = x.get("run").isJsonNull() ? null : x.getAsJsonObject("run");
            if (run != null && run.has("detail") && !run.get("detail").isJsonNull()) {
                JsonObject d = run.getAsJsonObject("detail");
                sawDetail[0] |= d.has("fiducials");
                sawDetail[1] |= d.has("probes");
            }
            return finished(x);
        });
        assertTrue(sawDetail[0] && sawDetail[1], "run.detail with fiducials and probes was seen");
        JsonObject run = st.getAsJsonObject("run");
        assertEquals("OK", run.get("status").getAsString(), run.toString());
        String p1Id = run.get("nextPlanId").getAsString();
        assertNotEquals(p0Id, p1Id);
        // the plan made again carries the board drawing, as the first did (BOARD_IMPORT_DESIGN.md §4.5)
        JsonObject p1Art = ok(get("/api/plan/" + p1Id)).getAsJsonObject("board").getAsJsonObject("art");
        assertNotNull(p1Art, "the plan made again has its board drawing");
        assertEquals(p0.getAsJsonObject("board").getAsJsonObject("art").get("id").getAsString(),
                p1Art.get("id").getAsString());
        JsonObject res = ok(get("/api/plan/" + p0Id)).getAsJsonObject("registrationRun");
        assertTrue(res.get("ok").getAsBoolean(), res.toString());
        assertEquals("first", res.get("seat").getAsString());
        assertEquals(p1Id, res.get("newPlanId").getAsString());
        assertTrue(res.get("simulated").getAsBoolean());

        // each fiducial found where S1 puts it: the correction is S1(b) - N(b)
        double worstFid = 0;
        for (JsonElement e : res.getAsJsonArray("fiducials")) {
            JsonObject f = e.getAsJsonObject();
            assertEquals("found", f.get("state").getAsString(), f.toString());
            JsonObject b = f.getAsJsonObject("board");
            Point2D t = apply(s1(), b.get("x").getAsDouble(), b.get("y").getAsDouble());
            JsonObject found = f.getAsJsonObject("found");
            worstFid = Math.max(worstFid, t.distance(found.get("x").getAsDouble(), found.get("y").getAsDouble()));
        }
        assertTrue(worstFid < 0.02, "fiducials within 0.02 mm of S1: " + worstFid);
        // the probe: 5 points, each contact within 0.03 mm of the scene's plane, below its last open step
        JsonObject probe = res.getAsJsonObject("probe");
        assertTrue(probe.get("saved").getAsBoolean(), probe.toString());
        assertTrue(probe.getAsJsonArray("points").size() >= 4, probe.toString());
        for (JsonElement e : probe.getAsJsonArray("points")) {
            JsonObject pt = e.getAsJsonObject();
            assertEquals("contact", pt.get("state").getAsString(), pt.toString());
            Point2D b = scene.toBoard(pt.get("x").getAsDouble(), pt.get("y").getAsDouble());
            double truth = scene.boardContactZ(b.getX(), b.getY());
            assertEquals(truth, pt.get("z").getAsDouble(), 0.03, pt.toString());
            assertTrue(pt.get("z").getAsDouble() < pt.get("lastOpenZ").getAsDouble(), pt.toString());
            assertTrue(pt.getAsJsonObject("look").get("bare").getAsBoolean(), pt.toString());
        }
        JsonObject surf = ok(get("/api/surfaces")).getAsJsonObject("surfaces").getAsJsonObject("board");
        assertTrue(surf.get("boundToBoard").getAsBoolean(), surf.toString());
        assertTrue(surf.get("simulated").getAsBoolean());

        // the G-code: VAC1 init once, every N1 vacuum switched off again, no dispenser output, Z in the window
        List<String> l = commands(sim.server.sinceMark());
        assertEquals(1, count(l, "M260 A109"), "VAC1 init once");
        int on = count(l, ControllerCommands.LEFT_VALVE_ON);
        assertTrue(on >= 4, l.toString());
        assertEquals(on, count(l, "M107 P1"), "every valve on is switched off");
        assertTrue(l.lastIndexOf("M107 P0") > l.lastIndexOf(ControllerCommands.LEFT_VALVE_ON));
        assertFalse(l.contains("M106 P2 S255") || l.contains("M106 P3 S255"), "no dispenser output");
        double floor = probe.getAsJsonObject("window").get("floorZ").getAsDouble();
        assertEquals(4.0, floor, 1e-9, "max(board 5.5, anchor 5.5) - 1.5");
        for (double z : zMoves(l)) {
            assertTrue(z >= floor - 1e-9 && z <= 31.5 + 1e-9, "Z " + z);
        }

        // the new plan: every pad and dot on the true pad (S1), registered, interlocks clear
        JsonObject p1 = ok(get("/api/plan/" + p1Id));
        double worst = worstPadError(p1, s1Pads);
        assertTrue(worst < 0.05, "dots on the pads within 0.05 mm: " + worst);
        JsonObject reg = p1.getAsJsonObject("registration");
        assertEquals(res.get("registrationId").getAsString(), reg.get("id").getAsString());
        assertEquals(reg.get("id").getAsString(), p1.getAsJsonObject("source").get("registrationId").getAsString());
        assertTrue(p1.getAsJsonObject("interlocks").get("ok").getAsBoolean(), p1.toString());
        // the run: the clean check finds copper at every dot (the dots lie on the pads), then dispenses
        sim.server.mark();
        JsonObject runRes = ok(post("/api/run", "{\"planId\":\"" + p1Id + "\",\"wait\":true}")).getAsJsonObject("result");
        assertEquals("OK", runRes.get("status").getAsString(), runRes.toString());
        assertTrue(runRes.getAsJsonObject("cleanCheck").get("ok").getAsBoolean(), runRes.toString());
        assertFalse(runRes.get("homedFirst").getAsBoolean());
        assertTrue(runRes.get("zCheck").isJsonNull(), "no home since the Z data: no Z check");

        // the same seat again: the record is kept, 3 stored points are touched again, the surface is kept
        long surfaceAt = surf.get("setAtMs").getAsLong();
        sim.server.mark();
        JsonObject same = register(p1Id, "{\"wait\":true}");
        assertEquals("OK", same.get("status").getAsString(), same.toString());
        assertEquals("same", same.get("seat").getAsString());
        assertTrue(same.get("movedMm").getAsDouble() < 0.02, same.toString());
        assertEquals(res.get("registrationId").getAsString(), same.get("registrationId").getAsString());
        assertTrue(same.getAsJsonObject("sameSeat").get("pass").getAsBoolean(), same.toString());
        assertFalse(same.getAsJsonObject("probe").get("saved").getAsBoolean());
        assertEquals(3, count(commands(sim.server.sinceMark()), ControllerCommands.LEFT_VALVE_ON), "3 check touches");
        assertEquals(surfaceAt, ok(get("/api/surfaces")).getAsJsonObject("surfaces").getAsJsonObject("board")
                .get("setAtMs").getAsLong(), "the surface is kept");
        assertEquals(p1Id, same.get("newPlanId").getAsString(), "the plan already carries both");

        // §10.2 case 3: the same seat, but the board now touches 0.08 mm higher (fiducial X/Y cannot see it):
        // the 3 touches disagree, so every target is probed again and the board planned on the new surface
        SimScene raised = scene(s1());
        raised.boardSurface = new SimScene.Plane(5.43 + 0.08, 0.0006, -0.0009);
        scene = raised;
        JsonObject changed = register(p1Id, "{\"wait\":true}");
        assertEquals("OK", changed.get("status").getAsString(), changed.toString());
        assertEquals("same", changed.get("seat").getAsString());
        assertEquals(res.get("registrationId").getAsString(), changed.get("registrationId").getAsString());
        JsonObject check = changed.getAsJsonObject("sameSeat");
        assertFalse(check.get("pass").getAsBoolean(), check.toString());
        for (JsonElement e : check.getAsJsonArray("points")) {
            assertEquals(0.08, e.getAsJsonObject().get("deltaMm").getAsDouble(), 0.03, e.toString());
        }
        JsonObject reprobe = changed.getAsJsonObject("probe");
        assertEquals("same seat, surface changed: probed again", reprobe.get("why").getAsString());
        assertTrue(reprobe.get("saved").getAsBoolean(), reprobe.toString());
        for (JsonElement e : reprobe.getAsJsonArray("points")) {
            JsonObject pt = e.getAsJsonObject();
            Point2D b = scene.toBoard(pt.get("x").getAsDouble(), pt.get("y").getAsDouble());
            assertEquals(scene.boardContactZ(b.getX(), b.getY()), pt.get("z").getAsDouble(), 0.03, pt.toString());
        }
        assertTrue(boardSurfaceAt() > surfaceAt, "a new surface");
        assertNotEquals(p1Id, changed.get("newPlanId").getAsString(), "planned again on the new surface");
    }

    /**
     * A STOP at each phase of Register board (home, fiducials, probe) ends it ABORTED with the stage
     * recorded, the vacuum and the pressure off and N1 never pushed down after it; a STOP during the
     * probe leaves N1 low, and the next run raises it (Z only) before it homes, checks Z with one touch,
     * then dispenses.
     */
    @Test
    public void stopAtEveryPhaseLeavesTheMachineSafeAndTheNextRunHomesAndChecksZ() throws Exception {
        String p0 = plan().get("planId").getAsString();
        JsonObject first = register(p0, "{\"wait\":true}");
        assertEquals("OK", first.get("status").getAsString(), first.toString());
        String p1 = first.get("newPlanId").getAsString();
        String regId = first.get("registrationId").getAsString();
        long surfaceAt = boardSurfaceAt();

        // STOP during the fiducials (a slow camera): nothing is stored, N1 never moved
        cam.delayMs = 300;
        sim.server.mark();
        started(post("/api/plan/" + p1 + "/register", "{}"));
        waitStatus(x -> phase(x).startsWith("fiducials"));
        ok(post("/api/stop", null));
        JsonObject stopped = waitStatus(RegistrationSimTest::finished);
        assertAborted(stopped, p1, "fiducials");
        assertFalse(commands(sim.server.sinceMark()).contains(ControllerCommands.LEFT_VALVE_ON));
        cam.delayMs = 0;
        assertEquals(regId, registrationIdNow(), "the registration is untouched");
        assertEquals(surfaceAt, boardSurfaceAt());
        assertFalse(n1Low(), "N1 never went down");
        restart();

        // STOP during the home: the drivers are disconnected, so nothing moves after it
        sim.server.delayBeforeOkMs.put("G28", 2500L);
        setInfinityTimeout(4000); // a G28 whose answer was cut off ends here (the bench's 60 s otherwise)
        JsonObject s = ok(get("/api/status"));
        assertTrue(s.getAsJsonObject("machine").getAsJsonObject("home").get("needed").getAsBoolean(), s.toString());
        assertEquals("stopped", s.getAsJsonObject("machine").getAsJsonObject("home").get("whyCode").getAsString());
        sim.server.mark();
        started(post("/api/plan/" + p1 + "/register", "{}"));
        waitStatus(x -> phase(x).startsWith("home"));
        Thread.sleep(200); // into the G28
        ok(post("/api/stop", null));
        stopped = waitStatus(RegistrationSimTest::finished);
        assertAborted(stopped, p1, "home");
        List<String> l = commands(sim.server.sinceMark());
        assertTrue(l.stream().anyMatch(x -> x.startsWith("G28")), l.toString());
        assertTrue(l.subList(l.indexOf(l.stream().filter(x -> x.startsWith("G28")).findFirst().get()) + 1, l.size())
                .stream().noneMatch(x -> x.startsWith("G1") || x.startsWith("G0")), "nothing moved after the STOP: " + l);
        assertFalse(l.contains(ControllerCommands.LEFT_VALVE_ON));
        assertFalse(sim.machine.isHomed());
        restart();

        // STOP during a run's own home: the same safe home; the pressure never goes on, nothing moves after it
        sim.server.mark();
        JsonObject acc = started(post("/api/run", "{\"planId\":\"" + p1 + "\"}"));
        assertTrue(acc.get("homeFirst").getAsBoolean() && acc.get("zCheckFirst").getAsBoolean(), acc.toString());
        waitStatus(x -> phase(x).startsWith("home"));
        Thread.sleep(200); // into the G28
        ok(post("/api/stop", null));
        stopped = waitStatus(RegistrationSimTest::finished);
        assertEquals("ABORTED", stopped.getAsJsonObject("run").get("status").getAsString(), stopped.toString());
        assertEquals("board", stopped.getAsJsonObject("run").get("label").getAsString());
        l = commands(sim.server.sinceMark());
        int home = indexOfPrefix(l, "G28");
        assertTrue(home >= 0, l.toString());
        assertTrue(l.subList(home + 1, l.size()).stream().noneMatch(x -> x.startsWith("G1") || x.startsWith("G0")),
                "nothing moved after the STOP: " + l);
        assertFalse(l.contains("M106 P2 S255") || l.contains(ControllerCommands.LEFT_VALVE_ON), l.toString());
        assertFalse(sim.machine.isHomed());
        assertEquals("ABORTED", ok(get("/api/runs/last")).getAsJsonObject("run").get("status").getAsString());
        sim.server.delayBeforeOkMs.clear();
        setInfinityTimeout(60000);
        restart();

        // STOP during the probe (probe: always; slow reads): N1 vented and left where it stopped
        sim.server.delayBeforeOkMs.put("M261", 25L);
        sim.server.mark();
        started(post("/api/plan/" + p1 + "/register", "{\"probe\":\"always\"}"));
        waitStatus(x -> {
            JsonObject run = x.get("run").isJsonNull() ? null : x.getAsJsonObject("run");
            JsonObject d = run == null || run.get("detail").isJsonNull() ? null : run.getAsJsonObject("detail");
            if (d == null || !"probe".equals(d.get("stage").getAsString()) || !d.has("probes")) {
                return false;
            }
            for (JsonElement e : d.getAsJsonArray("probes")) {
                JsonObject p = e.getAsJsonObject();
                if ("now".equals(p.get("state").getAsString()) && !p.get("lastZ").isJsonNull()) {
                    return true; // N1 is down at a probe step
                }
            }
            return false;
        });
        ok(post("/api/stop", null));
        stopped = waitStatus(RegistrationSimTest::finished);
        assertAborted(stopped, p1, "probe");
        l = commands(sim.server.sinceMark());
        int stop = Collections.indexOfSubList(l, ControllerCommands.STOP_SEQUENCE);
        assertTrue(stop >= 0, "the I1 shutdown vents N1: " + l);
        assertTrue(l.subList(stop, l.size()).stream().noneMatch(x -> x.startsWith("G1 Z")), "no Z after the M410");
        assertTrue(l.lastIndexOf("M107 P1") > l.lastIndexOf(ControllerCommands.LEFT_VALVE_ON));
        assertTrue(l.lastIndexOf("M107 P0") > l.lastIndexOf(ControllerCommands.LEFT_PUMP_ON));
        assertFalse(l.contains("M106 P2 S255") || l.contains("M106 P3 S255"), "the pressure never went on");
        JsonObject reg = ok(get("/api/plan/" + p1)).getAsJsonObject("registrationRun");
        assertEquals("probe", reg.get("stoppedStage").getAsString(), reg.toString());
        assertTrue(reg.get("registrationStored").getAsBoolean(), "the registration is real");
        assertFalse(reg.getAsJsonObject("probe").get("saved").getAsBoolean(), "never a partial surface");
        assertEquals(surfaceAt, boardSurfaceAt());
        assertEquals(p1, reg.get("newPlanId").getAsString(), "same seat, same surface: the plan stays");
        assertTrue(n1Low(), "N1 stopped low: " + ok(get("/api/status")).getAsJsonObject("machine").get("home"));
        assertEquals("N1", ok(get("/api/status")).getAsJsonObject("machine").getAsJsonObject("home").get("lowTool")
                .getAsString(), "N1 under its safe zone: N1 is the tool to look under");
        sim.server.delayBeforeOkMs.clear();
        restart();

        // the run after the STOP: N1 raised (Z only), G28, one Z-check touch (all heights from one home), the dots
        JsonObject zc = ok(get("/api/status")).getAsJsonObject("machine").getAsJsonObject("zCheck");
        assertEquals(1, zc.get("touchesAfterHome").getAsInt(), zc.toString());
        sim.server.mark();
        JsonObject run = ok(post("/api/run", "{\"planId\":\"" + p1 + "\",\"wait\":true}"));
        assertEquals(1, run.get("zCheckTouches").getAsInt(), run.toString());
        assertTrue(run.get("homeFirst").getAsBoolean(), run.toString());
        assertEquals("stopped", run.get("homeWhyCode").getAsString());
        assertTrue(run.get("zCheckFirst").getAsBoolean(), run.toString());
        JsonObject result = run.getAsJsonObject("result");
        assertEquals("OK", result.get("status").getAsString(), result.toString());
        assertTrue(result.get("homedFirst").getAsBoolean());
        assertTrue(result.getAsJsonObject("zCheck").get("pass").getAsBoolean(), result.toString());
        l = commands(sim.server.sinceMark());
        int g28 = indexOfPrefix(l, "G28");
        int raise = l.indexOf("G1 Z31.5000 F600");
        assertTrue(raise >= 0 && raise < g28, "N1 raised before the home: " + l);
        for (String x : l.subList(0, g28)) {
            assertFalse(x.startsWith("G1 X") || x.startsWith("G1 Y") || x.matches("G1 .*[XY]-?\\d.*"),
                    "no X/Y before G28: " + x);
        }
        int firstDot = l.indexOf("M106 P2 S255");
        int touch = l.indexOf(ControllerCommands.LEFT_VALVE_ON);
        assertTrue(g28 < touch && touch < firstDot, "home, then the Z-check touch, then the dots: " + l);
        assertEquals(1, count(l.subList(0, firstDot), ControllerCommands.LEFT_VALVE_ON), "one touch");
        assertFalse(n1Low());
    }

    /**
     * rc1 review (tests): Register board refuses a contact outside the board top Z band itself (only the run's I4 band
     * check was tested): the board planned 1.30 mm under where N1 touches it ends REFUSED with the engine's text, and no
     * board surface is saved.
     */
    @Test
    public void registerRefusesAContactOutsideTheBoardTopZBand() throws Exception {
        double low = NZ - 1.30;
        String p0 = ok(post("/api/plan", planBody("none").replace("\"z\":" + NZ, "\"z\":" + low))).get("planId")
                .getAsString();
        JsonObject r = register(p0, "{\"wait\":true}");
        assertEquals("REFUSED", r.get("status").getAsString(), r.toString());
        String m = r.get("message").getAsString();
        assertTrue(m.startsWith("I4: N1 touched at Z ") && m.contains(String.format(Locale.ROOT, "mm above the board top "
                + "Z %.3f (tolerance 1.00): the board top Z or the surface is off", low)), m);
        assertFalse(ok(get("/api/surfaces")).getAsJsonObject("surfaces").has("board"), "no board surface saved");
    }

    /**
     * rc1 review (ui-ux): a STOP during a board's dots leaves the needle low, not N1. On the shared Z axis N1 then sits
     * above its safe zone (N1 near 60, the needle near 3 over the pad), and the status says which tool is low, so the
     * recovery names the needle and never calls a high N1 "low".
     */
    @Test
    public void aStopDuringTheDotsLeavesTheNeedleLowNotN1() throws Exception {
        JsonObject first = register(plan().get("planId").getAsString(), "{\"wait\":true}");
        assertEquals("OK", first.get("status").getAsString(), first.toString());
        String p1 = first.get("newPlanId").getAsString();
        sim.server.delayBeforeOkMs.put("G1 B", 400L); // each dot's screw turn: slow enough to STOP between dots
        started(post("/api/run", "{\"planId\":\"" + p1 + "\"}"));
        waitStatus(x -> {
            JsonObject run = x.get("run").isJsonNull() ? null : x.getAsJsonObject("run");
            return run != null && run.has("dotsDone") && !run.get("dotsDone").isJsonNull()
                    && run.get("dotsDone").getAsInt() >= 1;
        });
        ok(post("/api/stop", null));
        JsonObject stopped = waitStatus(RegistrationSimTest::finished);
        assertEquals("ABORTED", stopped.getAsJsonObject("run").get("status").getAsString(), stopped.toString());
        sim.server.delayBeforeOkMs.clear();
        JsonObject home = ok(get("/api/status")).getAsJsonObject("machine").getAsJsonObject("home");
        assertTrue(home.get("n1Low").getAsBoolean(), "a tool is outside the safe zone: " + home);
        assertEquals("needle", home.get("lowTool").getAsString(), home.toString());
        assertTrue(home.get("n1Z").getAsDouble() > 36.5, "N1 above its safe zone, not low: " + home);
    }

    /**
     * §10.2 cases 5, 4, 14, 22 and the board-import rule (§10.2 item 1): a re-seated board is found,
     * probed again and planned on its new pads, and a plan of the old seat is refused; the operator's
     * new-seat answer forces the probe; the plan's registration parameter; a fiducial moved in the
     * board, and a record made on the simulator on a link that is not, are not applied.
     */
    @Test
    public void aReseatedBoardIsProbedAgainAndOldRegistrationsAreNotApplied() throws Exception {
        String p0 = plan().get("planId").getAsString();
        JsonObject first = register(p0, "{\"wait\":true}");
        assertEquals("OK", first.get("status").getAsString(), first.toString());
        String p1 = first.get("newPlanId").getAsString();
        String id1 = first.get("registrationId").getAsString();

        // the board re-seated: 0.8 / 0.5 mm and -0.4 deg about its origin, scale and shear unchanged
        AffineTransform s2 = reseat(s1(), 0.8, 0.5, -0.4);
        scene = scene(s2);
        JsonObject again = register(p1, "{\"wait\":true}");
        assertEquals("OK", again.get("status").getAsString(), again.toString());
        assertEquals("reseated", again.get("seat").getAsString());
        double moved = 0;
        for (double[] f : FIDUCIALS) {
            moved = Math.max(moved, apply(s2, f[0], f[1]).distance(apply(s1(), f[0], f[1])));
        }
        assertEquals(moved, again.get("movedMm").getAsDouble(), 0.02, again.toString());
        assertTrue(again.getAsJsonObject("probe").get("saved").getAsBoolean(), "probed again");
        String id2 = again.get("registrationId").getAsString();
        assertNotEquals(id1, id2);
        String p2 = again.get("newPlanId").getAsString();
        double worst = worstPadError(ok(get("/api/plan/" + p2)), truePads(s2));
        assertTrue(worst < 0.05, "dots on the re-seated pads within 0.05 mm: " + worst);
        Res old = post("/api/run", "{\"planId\":\"" + p1 + "\"}");
        assertEquals(409, old.status, old.toString());
        assertTrue(old.body.contains("is not stored any more") && old.body.contains("plan the board again"),
                old.toString());

        // "new seat" with the board untouched: re-seated by the operator's word, probed, the flag cleared
        JsonObject flag = ok(post("/api/board/new-seat", "{\"newSeat\":true}"));
        assertFalse(flag.get("newSeatAtMs").isJsonNull());
        assertEquals(400, post("/api/board/new-seat", "{}").status);
        JsonObject announced = register(p2, "{\"wait\":true}");
        assertEquals("reseated", announced.get("seat").getAsString(), announced.toString());
        assertEquals("a new seat was announced", announced.getAsJsonObject("probe").get("why").getAsString());
        assertTrue(announced.get("movedMm").getAsDouble() < 0.02);
        assertTrue(ok(get("/api/status")).getAsJsonObject("dispense").get("newSeatAtMs").isJsonNull());
        String id3 = announced.get("registrationId").getAsString();

        // the plan's registration parameter
        assertTrue(ok(post("/api/plan", planBody("none"))).get("registration").isJsonNull());
        assertEquals(id3, ok(post("/api/plan", planBody("auto"))).getAsJsonObject("registration").get("id")
                .getAsString());
        Res gone = post("/api/plan", planBody(id1));
        assertEquals(409, gone.status);
        assertTrue(gone.body.contains("is not stored any more"), gone.toString());
        Res elsewhere = post("/api/plan", planBody(id3).replace("\"x\":" + NX, "\"x\":" + (NX + 1)));
        assertEquals(409, elsewhere.status);
        assertTrue(elsewhere.body.contains("belongs to another board or position"), elsewhere.toString());
        // F8: only the board Z typed again (as I4 asks) keeps the registration
        assertEquals(id3, ok(post("/api/plan", planBody("auto").replace("\"z\":" + NZ, "\"z\":" + (NZ - 0.85))))
                .getAsJsonObject("registration").get("id").getAsString());

        // a fiducial moved in the board file: the record no longer applies (auto plans without it)
        org.openpnp.model.Placement fid2 = null;
        for (org.openpnp.model.Placement pl : board().getPlacements()) {
            fid2 = "FID2".equals(pl.getId()) ? pl : fid2;
        }
        org.openpnp.model.Location was = fid2.getLocation();
        fid2.setLocation(was.add(new org.openpnp.model.Location(was.getUnits(), 0.5, 0, 0, 0)));
        assertTrue(ok(post("/api/plan", planBody("auto"))).get("registration").isJsonNull());
        Res moved2 = post("/api/plan", planBody(id3));
        assertEquals(409, moved2.status);
        assertTrue(moved2.body.contains("FID2 moved or is gone"), moved2.toString());
        fid2.setLocation(was);

        // a link whose controller is not the simulator: the simulator's records are refused
        sim.server.firmwareName = "Marlin (RecordingGcodeServer)";
        sim.machine.setEnabled(false);
        ok(post("/api/machine/enable", null));
        Res real = post("/api/plan", planBody(id3));
        assertEquals(409, real.status);
        assertTrue(real.body.contains("was made on the simulator"), real.toString());
        assertTrue(ok(post("/api/plan", planBody("auto"))).get("registration").isJsonNull());
        assertFalse(ok(get("/api/status")).getAsJsonObject("server").get("simulatorConfirmed").getAsBoolean());
        assertTrue(ok(get("/api/status")).getAsJsonObject("server").get("simulated").getAsBoolean(), "loopback");
        // §10.2 case 22 (rc1 review: untested): the board surface Register probed on the simulator is refused here
        JsonObject onMarlin = ok(post("/api/plan", planBody("none")));
        String ilk = onMarlin.getAsJsonObject("interlocks").toString();
        assertTrue(ilk.contains("the 'board' surface was probed on the simulator: probe it again on this machine"), ilk);
        Res run = post("/api/run", "{\"planId\":\"" + onMarlin.get("planId").getAsString() + "\"}");
        assertEquals(409, run.status, run.toString());
        assertTrue(run.body.contains("the 'board' surface was probed on the simulator"), run.toString());
    }

    /** §10.2 cases 7, 13 and 16: refusals before N1 or anything else moves. */
    @Test
    public void refusalsComeBeforeAnythingMoves() throws Exception {
        String p0 = plan().get("planId").getAsString();
        assertEquals(400, post("/api/plan/" + p0 + "/register", "{\"probe\":\"bogus\"}").status);
        assertEquals(404, post("/api/plan/nope/register", "{}").status);
        // FID2 hidden: not found, where it was looked for; nothing stored, N1 never switched on
        scene.hideFiducials.add("FID2");
        sim.server.mark();
        JsonObject hidden = register(p0, "{\"wait\":true}");
        assertEquals("REFUSED", hidden.get("status").getAsString(), hidden.toString());
        String msg = hidden.get("message").getAsString();
        assertTrue(msg.startsWith("REG: FID2 not found within 8.0 mm of X ") && msg.contains("camera frames"), msg);
        assertFalse(hidden.get("registrationStored").getAsBoolean());
        assertTrue(hidden.get("newPlanId").isJsonNull());
        assertFalse(commands(sim.server.sinceMark()).stream().anyMatch(x -> x.startsWith("M106")));
        assertEquals(0, ok(get("/api/config")).getAsJsonObject("boardRegistrations").entrySet().size());
        for (JsonElement e : hidden.getAsJsonArray("fiducials")) {
            JsonObject f = e.getAsJsonObject();
            if ("FID2".equals(f.get("name").getAsString())) {
                assertEquals("not-found", f.get("state").getAsString());
            }
        }
        scene.hideFiducials.clear();
        // the pressure hold, and a board with fewer fiducials than needed
        ok(post("/api/pressure/start", null));
        Res held = post("/api/plan/" + p0 + "/register", "{}");
        assertEquals(409, held.status);
        assertTrue(held.body.contains("pressure"), held.toString());
        ok(post("/api/pressure/stop", null));
        ok(call("PUT", "/api/config", "{\"fiducialCheck\":{\"minFiducials\":4}}"));
        Res few = post("/api/plan/" + p0 + "/register", "{}");
        assertEquals(409, few.status);
        assertTrue(few.body.contains("REG: the board has 3 fiducials (FID1, FID2, FID3); Register board needs at "
                + "least 4"), few.toString());
        // settings: the engine's stamps are refused, a nested partial merge is kept, ranges are checked
        assertEquals(400, call("PUT", "/api/config", "{\"newSeatAtMs\":1}").status);
        assertEquals(400, call("PUT", "/api/config", "{\"boardRegistrations\":{\"x\":{}}}").status);
        JsonObject c = ok(call("PUT", "/api/config", "{\"fiducialCheck\":{\"minFiducials\":2},"
                + "\"vacuumProbe\":{\"alternates\":1}}"));
        assertEquals(1, c.getAsJsonObject("vacuumProbe").get("alternates").getAsInt());
        assertEquals(300, c.getAsJsonObject("vacuumProbe").get("vacuumSettleMs").getAsInt(), "the rest kept");
        assertEquals(400, call("PUT", "/api/config", "{\"vacuumProbe\":{\"floorBelowMm\":9}}").status);
        assertEquals(400, call("PUT", "/api/config", "{\"fiducialCheck\":{\"searchRadiusMm\":20}}").status);
        assertEquals(200, call("PUT", "/api/config", ok(get("/api/config")).toString()).status, "a round trip");
        // a board top Z typed in the needle's frame (2.59 mm off) is refused at planning
        Res needleZ = post("/api/plan", planBody(null).replace("\"z\":" + NZ, "\"z\":" + (NZ - 2.59)));
        assertEquals(409, needleZ.status);
        assertTrue(needleZ.body.contains("the board top Z 2.910 is 2.59 mm from the needle-frame surface 5.500"),
                needleZ.toString());
        ok(post("/api/plan", planBody(null).replace("\"z\":" + NZ, "\"z\":" + (NZ - 1.0)))); // 1 mm under: fine
        assertEquals(2.0, DispenseService.MAX_BOARD_Z_FROM_ANCHOR_MM);
        String low = ok(post("/api/plan", planBody(null).replace("\"z\":" + NZ, "\"z\":" + (NZ - 2.0))))
                .get("planId").getAsString(); // the bound itself
        // review 1: but the probe's floor (1.5 mm under the needle frame) lies above that board top: Register board
        // refuses before anything moves and its preview says why, instead of three pushes to the floor
        String unreachable = "PROBE: the board top Z 3.500 lies 2.000 mm under the needle-frame surface 5.500, and the "
                + "probe goes at most 1.5 mm under the higher of the two (down to Z 4.000): it cannot reach that height. "
                + "Set the board top Z to roughly the N1 contact height, or record the needle frame again; if both are "
                + "right, probe by hand";
        JsonObject frame = ok(get("/api/plan/" + low + "/registration-targets")).getAsJsonObject("frame");
        assertFalse(frame.get("ok").getAsBoolean(), frame.toString());
        assertEquals(unreachable, frame.get("message").getAsString());
        sim.server.mark();
        Res lowReg = post("/api/plan/" + low + "/register", "{}");
        assertEquals(409, lowReg.status, lowReg.toString());
        assertEquals(unreachable, lowReg.json().get("reason").getAsString());
        assertTrue(sim.server.sinceMark().isEmpty(), "nothing sent: " + sim.server.sinceMark());
        Res high = post("/api/plan", planBody(null).replace("\"z\":" + NZ, "\"z\":" + (NZ + 2.01)));
        assertEquals(409, high.status, high.toString());
        assertTrue(high.body.contains("2.01 mm from the needle-frame surface"), high.toString());
        org.openpnp.instancelabs.dispense.DispenseConfig noAnchor = svc.getConfig();
        noAnchor.needleAnchor = null;
        assertEquals(null, DispenseService.boardZProblem(noAnchor, 0.0), "nothing to compare without a needle frame");
        // F14: a touch-off pitch with no room between half-turn dots (1.0 mm merged them on the machine)
        sim.server.mark();
        Res tight = post("/api/calibrations/touch-off", "{\"pitchMm\":1.0}");
        assertEquals(400, tight.status, tight.toString());
        assertTrue(tight.body.contains("the touch-off pitch 1.00 mm is too small for these dots: a half-turn dot is "
                + "about 1.16 mm across") && tight.body.contains("use a pitch of at least 1.56 mm"), tight.toString());
        assertFalse(commands(sim.server.sinceMark()).stream().anyMatch(x -> x.startsWith("G") || x.startsWith("M106")),
                "nothing moved or switched on");
        // a task queued while the machine is stopped ends refused and does not stay busy (§2.7)
        sim.machine.setEnabled(false);
        org.openpnp.instancelabs.dispense.RunResult r = svc.checkZ().get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(org.openpnp.instancelabs.dispense.RunResult.Status.REFUSED, r.status, r.toString());
        assertTrue(r.message.startsWith("MACHINE: the machine is not started"), r.message);
        assertFalse(svc.isBusy());
    }

    /**
     * §10.2 cases 17 and 19: the blank probed with N1 around the purge row, the board's surface left to
     * Register board; after a home the needle tasks refuse until Z is checked; a Z zero that moved is
     * refused with the size of the move.
     */
    @Test
    public void theBlankProbeAndTheZCheckAfterAHome() throws Exception {
        assertEquals(409, post("/api/surfaces/board/probe", "{}").status);
        JsonObject t = ok(get("/api/surfaces/blank/probe-targets"));
        assertTrue(t.get("error").isJsonNull(), t.toString());
        // §10.2 case 17 (rc1 review: not exercised): a paste dot under the first target: the look sends N1 to an alternate
        JsonObject t0 = t.getAsJsonArray("targets").get(0).getAsJsonObject();
        scene.blankDots.add(new double[] {t0.get("x").getAsDouble(), t0.get("y").getAsDouble()});
        JsonObject blank = ok(post("/api/surfaces/blank/probe", "{\"wait\":true}")).getAsJsonObject("result");
        scene.blankDots.clear();
        assertEquals("OK", blank.get("status").getAsString(), blank.toString());
        JsonObject b0 = blank.getAsJsonArray("points").get(0).getAsJsonObject();
        assertEquals(1, b0.get("alternate").getAsInt(), b0.toString());
        assertTrue(b0.get("notes").toString().contains("paste, a part or print seen"), b0.toString());
        assertTrue(blank.get("saved").getAsBoolean());
        int n = blank.getAsJsonArray("points").size();
        assertTrue(n >= 4 && n <= 6, blank.toString());
        for (JsonElement e : blank.getAsJsonArray("points")) {
            assertEquals(BLANK_Z, e.getAsJsonObject().get("z").getAsDouble(), 0.03, e.toString());
        }
        assertTrue(blank.getAsJsonObject("plane").get("coversSpots").getAsBoolean());
        assertTrue(blank.getAsJsonObject("surface").get("simulated").getAsBoolean());

        // a home makes every Z datum older than it: prime refuses until Z is checked
        JsonObject home = ok(post("/api/machine/home", "{\"wait\":true}"));
        assertTrue(home.get("homed").getAsBoolean(), home.toString());
        Res prime = post("/api/prime", "{}");
        assertEquals(409, prime.status);
        assertTrue(prime.body.contains("measured before the home at"), prime.toString());
        JsonObject zc = ok(get("/api/status")).getAsJsonObject("machine").getAsJsonObject("zCheck");
        assertTrue(zc.get("needed").getAsBoolean(), zc.toString());
        // rc1 review (AR §2.4 step 1): paste on every stored point (purge dots beside the row, the blank slid): the check
        // touches none of them and refuses with what to do; paste on the first only: it touches the next one
        JsonArray stored = blank.getAsJsonObject("surface").getAsJsonArray("points"); // the Z check's references
        for (JsonElement e : stored) {
            scene.blankDots.add(new double[] {e.getAsJsonObject().get("x").getAsDouble(), e.getAsJsonObject().get("y")
                    .getAsDouble()});
        }
        sim.server.mark();
        JsonObject dirty = ok(post("/api/machine/z-check", "{\"wait\":true}")).getAsJsonObject("result");
        assertEquals("REFUSED", dirty.get("status").getAsString(), dirty.toString());
        assertTrue(dirty.get("message").getAsString().contains("but something lies on every stored point (paste, a part "
                + "or print seen): wipe one of them, or record the needle frame again"), dirty.toString());
        assertFalse(commands(sim.server.sinceMark()).contains(ControllerCommands.LEFT_VALVE_ON), "N1 touched no paste");
        scene.blankDots.subList(1, scene.blankDots.size()).clear();
        JsonObject check = ok(post("/api/machine/z-check", "{\"wait\":true}"));
        assertTrue(check.get("needed").getAsBoolean());
        JsonObject res = check.getAsJsonObject("result");
        assertEquals("OK", res.get("status").getAsString(), res.toString());
        assertTrue(res.getAsJsonObject("zCheck").get("pass").getAsBoolean());
        JsonObject second = stored.get(1).getAsJsonObject();
        assertEquals(second.get("x").getAsDouble(), res.getAsJsonObject("zCheck").get("x").getAsDouble(), 1e-9,
                "the next stored point: " + res);
        assertTrue(res.get("warnings").toString().contains("(the next stored point is used)"), res.toString());
        scene.blankDots.clear();
        assertEquals(200, post("/api/prime", "{\"wait\":true}").status);
        assertEquals("Z data were measured after the last home: nothing to check",
                ok(post("/api/machine/z-check", "{\"wait\":true}")).getAsJsonObject("result").get("message")
                        .getAsString());

        // the Z zero moved 0.70 mm at the next home: the check refuses, and so does prime
        ok(post("/api/machine/home", "{\"wait\":true}"));
        SimScene shifted = scene(s1());
        shifted.blankSurface = new SimScene.Plane(BLANK_Z - 0.70, 0, 0);
        shifted.boardSurface = new SimScene.Plane(5.43 - 0.70, 0.0006, -0.0009);
        scene = shifted;
        JsonObject moved = ok(post("/api/machine/z-check", "{\"wait\":true}")).getAsJsonObject("result");
        assertEquals("REFUSED", moved.get("status").getAsString(), moved.toString());
        assertTrue(moved.get("message").getAsString().startsWith("I4: Z moved -0.70 mm at the home of"),
                moved.toString());
        Res after = post("/api/prime", "{}");
        assertEquals(409, after.status);
        assertTrue(after.body.contains("Z moved -0.70 mm"), after.toString());
        // review (machine safety): K is a sum of raw Z heights, so a frame "from K" would give the old offset (63 - K)
        // whatever N1 touches now. While the check of this home failed it is refused; both tips measure the frame
        JsonObject needle = ok(get("/api/status")).getAsJsonObject("dispense").getAsJsonObject("needle");
        assertTrue(needle.get("kRefusal").getAsString().startsWith("K was measured under another home: Z moved -0.70 mm "
                + "at the home of "), needle.toString());
        Res fromK = post("/api/needle/anchor", "{\"surfaceZ\":" + (BLANK_Z - 0.70) + ",\"needleContactRawSum\":65.59}");
        assertEquals(409, fromK.status, fromK.toString());
        assertTrue(fromK.json().get("reason").getAsString().endsWith("Record the needle frame with both tips touching the "
                + "same spot"), fromK.toString());
        double offset = ok(get("/api/status")).getAsJsonObject("dispense").getAsJsonObject("needle").get("zOffsetMm")
                .getAsDouble();
        JsonObject both = ok(post("/api/needle/anchor", "{\"surfaceZ\":" + (BLANK_Z - 0.70) + ",\"needleContactZ\":"
                + (BLANK_Z - 0.70 - 2.30) + "}"));
        assertEquals(-2.30, both.get("offsetMm").getAsDouble(), 1e-9, "measured, not carried over from "
                + offset);
        // the places moved too: the status lists those set before the home until their Z is taken again
        JsonArray places = ok(get("/api/status")).getAsJsonObject("machine").getAsJsonObject("zCheck")
                .getAsJsonArray("places");
        assertEquals("[\"waste\",\"blank\"]", names(places), places.toString());
        assertTrue(prime(409).contains("place 'waste' Z 5.500 was taken at "), "prime at the old waste Z");
        ok(call("PUT", "/api/locations/waste", "{\"x\":80,\"y\":40,\"z\":" + (5.5 - 0.70) + "}"));
        places = ok(get("/api/status")).getAsJsonObject("machine").getAsJsonObject("zCheck").getAsJsonArray("places");
        assertEquals("[\"blank\"]", names(places), places.toString());
        assertFalse(prime(409).contains("place 'waste'"), "the waste taken again");
    }

    /** The "name" of each entry of {@code a}, as a JSON list. */
    static String names(JsonArray a) {
        JsonArray n = new JsonArray();
        for (JsonElement e : a) {
            n.add(e.getAsJsonObject().get("name"));
        }
        return n.toString();
    }

    /** POST /api/prime, expecting {@code status}: the answer's body. */
    String prime(int status) throws Exception {
        Res r = post("/api/prime", "{}");
        assertEquals(status, r.status, r.toString());
        return r.body;
    }

    /**
     * Review 1 (machine safety), I4: home 1 moved Z 0.70 mm; the board was registered (probed) after it,
     * the needle frame, the touch-off and the blank were not; home 2 repeats home 1. Register board's 3
     * same-seat touches agree, but they vouch only for the board's Z frame: the run still checks Z on the
     * blank, which shows the move, and refuses before the pressure goes on.
     */
    @Test
    public void aSameSeatCheckVouchesOnlyForTheBoardsZFrame() throws Exception {
        ok(post("/api/machine/home", "{\"wait\":true}")); // home 1
        SimScene shifted = scene(s1());
        shifted.blankSurface = new SimScene.Plane(BLANK_Z - 0.70, 0, 0);
        shifted.boardSurface = new SimScene.Plane(5.43 - 0.70, 0.0006, -0.0009);
        scene = shifted;
        JsonObject first = register(plan().get("planId").getAsString(), "{\"wait\":true}");
        assertEquals("OK", first.get("status").getAsString(), first.toString());
        String p1 = first.get("newPlanId").getAsString();
        ok(post("/api/machine/home", "{\"wait\":true}")); // home 2 repeats home 1
        JsonObject same = register(p1, "{\"wait\":true}");
        assertEquals("same", same.get("seat").getAsString(), same.toString());
        JsonObject check = same.getAsJsonObject("sameSeat");
        assertTrue(check.get("pass").getAsBoolean() && check.get("zCheckStamped").getAsBoolean(), check.toString());
        assertEquals(p1, same.get("newPlanId").getAsString());
        sim.server.mark();
        JsonObject result = post("/api/run", "{\"planId\":\"" + p1 + "\",\"wait\":true}").json().getAsJsonObject(
                "result");
        assertEquals("REFUSED", result.get("status").getAsString(), result.toString());
        assertTrue(result.get("message").getAsString().startsWith("I4: Z moved -0.70 mm at the home of"),
                result.toString());
        List<String> l = commands(sim.server.sinceMark());
        assertEquals(1, count(l, ControllerCommands.LEFT_VALVE_ON), "one touch, on the blank: " + l);
        assertFalse(l.contains("M106 P2 S255") || l.contains("M106 P3 S255"), "the pressure never went on");
    }

    /**
     * §10.2 cases 9, 10, 11 and 13: a hole under a spot and paste on a spot each cost an alternate; a
     * target with no usable spot, a missing tip, a part on N1 and the simulator's timings on a
     * controller that is not the simulator are refused. A refused probe keeps the registration and the
     * plan, saves no surface, lifts and vents N1 and leaves the machine homed.
     */
    @Test
    public void theProbeTakesAlternatesAndARefusedProbeSavesNoSurface() throws Exception {
        String p0 = plan().get("planId").getAsString();
        JsonObject t1 = firstProbeTarget(p0);
        // a hole (via) under the first target: N1 finds no seal there and touches its first alternate
        scene.leakySpots.add(new SimScene.Spot(t1.get("bx").getAsDouble(), t1.get("by").getAsDouble(), 0.5));
        JsonObject first = register(p0, "{\"wait\":true}");
        assertEquals("OK", first.get("status").getAsString(), first.toString());
        assertEquals(1, firstPoint(first).get("alternate").getAsInt(), first.toString());
        String p1 = first.get("newPlanId").getAsString();
        String regId = first.get("registrationId").getAsString();
        long surfaceAt = boardSurfaceAt();

        // holes under the target and all its alternates: refused, nothing saved, N1 lifted and vented
        t1 = firstProbeTarget(p1);
        for (JsonElement e : t1.getAsJsonArray("alternates")) {
            JsonObject a = e.getAsJsonObject();
            scene.leakySpots.add(new SimScene.Spot(a.get("bx").getAsDouble(), a.get("by").getAsDouble(), 0.5));
        }
        sim.server.mark();
        JsonObject holed = register(p1, "{\"wait\":true,\"probe\":\"always\"}");
        assertEquals("REFUSED", holed.get("status").getAsString(), holed.toString());
        assertTrue(holed.get("message").getAsString().contains("probably a hole or via"), holed.toString());
        assertTrue(holed.get("registrationStored").getAsBoolean());
        assertEquals(regId, holed.get("registrationId").getAsString(), "the same seat keeps its record");
        assertEquals(p1, holed.get("newPlanId").getAsString(), "registration and surface unchanged: the plan stays");
        assertEquals(surfaceAt, boardSurfaceAt(), "no surface saved");
        assertTrue(sim.machine.isHomed(), "no STOP: the machine stays homed");
        List<String> l = commands(sim.server.sinceMark());
        assertEquals(java.util.Arrays.asList("G1 Z31.5000 F600", "M400", "M107 P1", "M107 P0"),
                l.subList(l.size() - 4, l.size()), "the end lines follow the last point");
        // the refused probe is recorded with its readings, for diagnosis on the machine (§5.4)
        List<Map<String, Object>> rec = svc.getDb().query("SELECT level, message FROM events WHERE run_uuid = ? AND "
                + "code = 'PROBE'", holed.get("runId").getAsString());
        assertEquals(1, rec.size(), rec.toString());
        assertEquals("WARN", rec.get(0).get("level"));
        JsonObject probed = new JsonParser().parse((String) rec.get(0).get("message")).getAsJsonObject();
        assertEquals("board probe", probed.get("what").getAsString());
        JsonObject p1st = probed.getAsJsonArray("points").get(0).getAsJsonObject();
        assertEquals(3, p1st.get("spotsTried").getAsInt(), p1st.toString()); // the third floor push refused
        assertTrue(p1st.get("notes").toString().contains("no contact down to the floor"), p1st.toString());
        assertTrue(p1st.getAsJsonArray("readings").size() > 5, p1st.toString());
        scene.leakySpots.clear();

        // paste on the first target: the camera look sends N1 to an alternate
        t1 = firstProbeTarget(p1);
        scene.dirtySpots.add(new double[] {t1.get("bx").getAsDouble(), t1.get("by").getAsDouble()});
        JsonObject pasted = register(p1, "{\"wait\":true,\"probe\":\"always\"}");
        assertEquals("OK", pasted.get("status").getAsString(), pasted.toString());
        JsonObject pt = firstPoint(pasted);
        assertEquals(1, pt.get("alternate").getAsInt(), pt.toString());
        assertTrue(pt.get("notes").toString().contains("paste, a part or print seen"), pt.toString());
        scene.dirtySpots.clear();
        String p2 = pasted.get("newPlanId").getAsString();

        // no tip on N1: the open-air baseline reads toward 0
        scene.vacuum.tip = "missing";
        JsonObject noTip = register(p2, "{\"wait\":true,\"probe\":\"always\"}");
        assertEquals("REFUSED", noTip.get("status").getAsString(), noTip.toString());
        assertTrue(noTip.get("message").getAsString().contains("no nozzle tip on N1"), noTip.toString());
        scene.vacuum.tip = "normal";

        // OpenPnP says N1 holds a part: refused before anything moves
        java.lang.reflect.Method setPart = org.openpnp.spi.base.AbstractNozzle.class.getDeclaredMethod("setPart",
                Part.class);
        setPart.setAccessible(true);
        setPart.invoke(svc.getProbeNozzle(), Configuration.get().getPart("R0603-REG-TEST"));
        Res part = post("/api/plan/" + p2 + "/register", "{}");
        assertEquals(409, part.status, part.toString());
        assertTrue(part.body.contains("OpenPnP says N1 holds a part (R0603-REG-TEST)"), part.toString());
        setPart.invoke(svc.getProbeNozzle(), (Part) null);

        // the simulator's timings on a controller that says it is not the simulator
        sim.server.firmwareName = "Marlin (RecordingGcodeServer)";
        sim.machine.setEnabled(false);
        ok(post("/api/machine/enable", null));
        String unregistered = ok(post("/api/plan", planBody("none"))).get("planId").getAsString();
        Res real = post("/api/plan/" + unregistered + "/register", "{}");
        assertEquals(409, real.status, real.toString());
        assertTrue(real.body.contains("PROBE: vacuumProbe.vacuumSettleMs 300 is a simulator setting"), real.toString());
    }

    /**
     * F10 (real session 4: a blank board file had no fiducials, so neither Register board nor the surface probe could
     * give it a 'board' surface): Register with probe "always" on a plan without a registration probes it only, where
     * the plan puts it (home if needed, the camera look and N1 at its targets), stores no registration, ties the
     * surface to that seat and plans the board again on it, which then runs; a plan of the board 1 mm further is not
     * tied to it until probed there. The other probe modes keep the fiducial refusal, which names the way out. A STOP
     * during a probe only vents N1 and saves nothing.
     */
    @Test
    public void aBoardWithoutFiducialsIsProbedOnlyWhereItIsPlanned() throws Exception {
        boardFile = writeBoard("nofid-test.board.xml", false);
        SimScene planned = scene(nominal()); // its X/Y set by the camera: the board sits where it is planned
        planned.boardFile = boardFile.getName();
        scene = planned;
        JsonObject p0 = ok(post("/api/plan", planBody("none")));
        String p0Id = p0.get("planId").getAsString();
        JsonObject preview = p0.getAsJsonObject("registrationTargets");
        assertEquals(0, preview.get("fiducialCount").getAsInt(), preview.toString());
        assertTrue(preview.get("probeOnly").getAsBoolean(), preview.toString());
        assertTrue(preview.getAsJsonArray("probes").size() >= 3, preview.toString());
        sim.server.mark();
        for (String body : new String[] {"{}", "{\"probe\":\"auto\"}", "{\"probe\":\"never\"}"}) {
            Res few = post("/api/plan/" + p0Id + "/register", body);
            assertEquals(409, few.status, body + ": " + few);
            assertTrue(few.body.contains("REG: the board has 0 fiducials; Register board needs at least 2.") && few.body
                    .contains("Or probe it only, where it is planned: Register with probe \\\"always\\\"."), few.toString());
        }
        assertFalse(commands(sim.server.sinceMark()).stream().anyMatch(x -> x.startsWith("G") || x.startsWith("M106")),
                "refused before anything moves");

        JsonObject res = register(p0Id, "{\"wait\":true,\"probe\":\"always\"}");
        assertEquals("OK", res.get("status").getAsString(), res.toString());
        assertTrue(res.get("probeOnly").getAsBoolean());
        String msg = res.get("message").getAsString();
        assertTrue(msg.startsWith("Registered ") && msg.contains(": no fiducials: probed only, X/Y as planned; board "
                + "probed at "), msg);
        assertFalse(res.get("registrationStored").getAsBoolean());
        assertTrue(res.get("registrationId").isJsonNull() && res.get("seat").isJsonNull() && res.get("fit")
                .isJsonNull(), res.toString());
        assertEquals(0, res.getAsJsonArray("fiducials").size());
        assertEquals(0, ok(get("/api/config")).getAsJsonObject("boardRegistrations").entrySet().size());
        JsonObject probe = res.getAsJsonObject("probe");
        assertEquals("no fiducials: probed only, X/Y as planned", probe.get("why").getAsString());
        assertTrue(probe.get("saved").getAsBoolean(), probe.toString());
        assertTrue(probe.getAsJsonArray("points").size() >= 4, probe.toString());
        for (JsonElement e : probe.getAsJsonArray("points")) {
            JsonObject pt = e.getAsJsonObject();
            assertEquals("contact", pt.get("state").getAsString(), pt.toString());
            assertTrue(pt.getAsJsonObject("look").get("bare").getAsBoolean(), "looked at first: " + pt);
            Point2D b = scene.toBoard(pt.get("x").getAsDouble(), pt.get("y").getAsDouble());
            assertEquals(scene.boardContactZ(b.getX(), b.getY()), pt.get("z").getAsDouble(), 0.03, pt.toString());
        }
        JsonObject surf = ok(get("/api/surfaces")).getAsJsonObject("surfaces").getAsJsonObject("board");
        assertTrue(surf.get("boundToBoard").getAsBoolean(), surf.toString());
        assertTrue(surf.get("note").getAsString().contains("(no fiducials: probed only, X/Y as planned, run "),
                surf.toString());
        // planned again on the new surface, still without a registration: its interlocks pass and it runs
        String p1Id = res.get("newPlanId").getAsString();
        assertNotEquals(p0Id, p1Id);
        JsonObject p1 = ok(get("/api/plan/" + p1Id));
        assertTrue(p1.get("registration").isJsonNull(), p1.toString());
        assertTrue(p1.getAsJsonObject("interlocks").get("ok").getAsBoolean(), p1.toString());
        JsonObject run = ok(post("/api/run", "{\"planId\":\"" + p1Id + "\",\"wait\":true}")).getAsJsonObject("result");
        assertEquals("OK", run.get("status").getAsString(), run.toString());
        assertEquals(2 * RESISTORS.length, run.get("dotsDone").getAsInt(), "a dot on each pad: " + run);

        // the board planned 1 mm further in X: that seat has no surface tied to it, until it is probed there
        String moved = ok(post("/api/plan", planBody("none").replace("\"x\":" + NX, "\"x\":" + (NX + 1))))
                .get("planId").getAsString();
        JsonObject interlocks = ok(get("/api/plan/" + moved)).getAsJsonObject("interlocks");
        assertFalse(interlocks.get("ok").getAsBoolean(), interlocks.toString());
        assertTrue(interlocks.toString().contains("surface was probed on another registration of the board"),
                interlocks.toString());
        SimScene there = scene(AffineTransform.getTranslateInstance(NX + 1, NY));
        there.boardFile = boardFile.getName();
        scene = there;
        // a Stop and Start since the last home: probe only homes first, as Register board does
        ok(post("/api/stop", null));
        restart();
        assertTrue(ok(get("/api/status")).getAsJsonObject("machine").getAsJsonObject("home").get("needed")
                .getAsBoolean());
        JsonObject again = register(moved, "{\"wait\":true,\"probe\":\"always\"}");
        assertEquals("OK", again.get("status").getAsString(), again.toString());
        assertTrue(again.get("probeOnly").getAsBoolean());
        assertTrue(again.getAsJsonObject("home").get("done").getAsBoolean(), "homed first: " + again);
        String p2 = again.get("newPlanId").getAsString();
        assertTrue(ok(get("/api/plan/" + p2)).getAsJsonObject("interlocks").get("ok").getAsBoolean());

        // a STOP while N1 is down at a point of a probe only (slow reads): ABORTED in the probe, N1 vented by the I1
        // shutdown, no surface saved (the last one stays), nothing planned again, still no registration (F10 review)
        long surfaceAt = boardSurfaceAt();
        sim.server.delayBeforeOkMs.put("M261", 25L);
        sim.server.mark();
        started(post("/api/plan/" + p2 + "/register", "{\"probe\":\"always\"}"));
        waitStatus(x -> {
            JsonObject r = x.get("run").isJsonNull() ? null : x.getAsJsonObject("run");
            JsonObject d = r == null || r.get("detail").isJsonNull() ? null : r.getAsJsonObject("detail");
            if (d == null || !"probe".equals(d.get("stage").getAsString()) || !d.has("probes")) {
                return false;
            }
            for (JsonElement e : d.getAsJsonArray("probes")) {
                JsonObject p = e.getAsJsonObject();
                if ("now".equals(p.get("state").getAsString()) && !p.get("lastZ").isJsonNull()) {
                    return true; // N1 is down at a probe step
                }
            }
            return false;
        });
        ok(post("/api/stop", null));
        assertAborted(waitStatus(RegistrationSimTest::finished), p2, "probe");
        List<String> l = commands(sim.server.sinceMark());
        int stop = Collections.indexOfSubList(l, ControllerCommands.STOP_SEQUENCE);
        assertTrue(stop >= 0, "the I1 shutdown vents N1: " + l);
        assertTrue(l.subList(stop, l.size()).stream().noneMatch(x -> x.startsWith("G1 Z")), "no Z after the M410");
        assertTrue(l.lastIndexOf("M107 P1") > l.lastIndexOf(ControllerCommands.LEFT_VALVE_ON), "the valve off");
        assertTrue(l.lastIndexOf("M107 P0") > l.lastIndexOf(ControllerCommands.LEFT_PUMP_ON), "the pump off");
        JsonObject stopped = ok(get("/api/plan/" + p2)).getAsJsonObject("registrationRun");
        assertTrue(stopped.get("probeOnly").getAsBoolean(), stopped.toString());
        assertFalse(stopped.getAsJsonObject("probe").get("saved").getAsBoolean(), "never a partial surface");
        assertTrue(stopped.get("newPlanId").isJsonNull(), stopped.toString());
        assertEquals(surfaceAt, boardSurfaceAt());
        assertEquals(0, ok(get("/api/config")).getAsJsonObject("boardRegistrations").entrySet().size());
        sim.server.delayBeforeOkMs.clear();
    }

    /**
     * F10 keeps every refusal of a board that has its fiducials: probe "always" on a plan without a registration
     * still finds them first (a hidden one refuses before N1 moves), and registers by them. Probe only is not offered
     * on a plan that carries a registration, nor for a board whose fiducials are too few for the setting.
     */
    @Test
    public void probeOnlyNeverSkipsTheFiducialsOfABoardThatHasThem() throws Exception {
        String p0 = ok(post("/api/plan", planBody("none"))).get("planId").getAsString();
        JsonObject preview = ok(get("/api/plan/" + p0)).getAsJsonObject("registrationTargets");
        assertFalse(preview.get("probeOnly").getAsBoolean(), preview.toString());
        scene.hideFiducials.add("FID2");
        sim.server.mark();
        JsonObject hidden = register(p0, "{\"wait\":true,\"probe\":\"always\"}");
        assertEquals("REFUSED", hidden.get("status").getAsString(), hidden.toString());
        assertTrue(hidden.get("message").getAsString().startsWith("REG: FID2 not found within 8.0 mm"), hidden.toString());
        assertFalse(hidden.get("probeOnly").getAsBoolean());
        assertFalse(commands(sim.server.sinceMark()).stream().anyMatch(x -> x.startsWith("M106")), "N1 never on");
        scene.hideFiducials.clear();
        JsonObject reg = register(p0, "{\"wait\":true,\"probe\":\"always\"}");
        assertEquals("OK", reg.get("status").getAsString(), reg.toString());
        assertEquals("first", reg.get("seat").getAsString());
        assertFalse(reg.get("probeOnly").getAsBoolean());
        assertTrue(reg.get("registrationStored").getAsBoolean());
        // a plan that carries the registration, once the board has too few fiducials for the setting: refused,
        // and probe only is not offered
        ok(call("PUT", "/api/config", "{\"fiducialCheck\":{\"minFiducials\":4}}"));
        String p1 = reg.get("newPlanId").getAsString();
        assertFalse(ok(get("/api/plan/" + p1)).getAsJsonObject("registrationTargets").get("probeOnly").getAsBoolean());
        Res few = post("/api/plan/" + p1 + "/register", "{\"probe\":\"always\"}");
        assertEquals(409, few.status, few.toString());
        assertTrue(few.body.contains("REG: the board has 3 fiducials (FID1, FID2, FID3); Register board needs at least "
                + "4") && !few.body.contains("probe it only"), few.toString());
        // nor on a plan without a registration: a board with fiducials, too few for the setting, is never probed
        // without them (F10 review)
        String p2 = ok(post("/api/plan", planBody("none"))).get("planId").getAsString();
        assertFalse(ok(get("/api/plan/" + p2)).getAsJsonObject("registrationTargets").get("probeOnly").getAsBoolean());
        sim.server.mark();
        Res none = post("/api/plan/" + p2 + "/register", "{\"probe\":\"always\"}");
        assertEquals(409, none.status, none.toString());
        assertTrue(none.body.contains("REG: the board has 3 fiducials (FID1, FID2, FID3); Register board needs at "
                + "least 4") && !none.body.contains("probe it only"), none.toString());
        assertFalse(commands(sim.server.sinceMark()).stream().anyMatch(x -> x.startsWith("G") || x.startsWith("M106")),
                "refused before anything moves");
    }

    /** The first probe target of a plan's registration preview (board and machine mm, alternates). */
    JsonObject firstProbeTarget(String planId) throws Exception {
        return ok(get("/api/plan/" + planId)).getAsJsonObject("registrationTargets").getAsJsonArray("probes").get(0)
                .getAsJsonObject();
    }

    static JsonObject firstPoint(JsonObject registration) {
        return registration.getAsJsonObject("probe").getAsJsonArray("points").get(0).getAsJsonObject();
    }

    void assertAborted(JsonObject status, String planId, String stage) throws Exception {
        JsonObject run = status.getAsJsonObject("run");
        assertEquals("ABORTED", run.get("status").getAsString(), run.toString());
        assertEquals("board registration", run.get("label").getAsString());
        assertFalse(status.getAsJsonObject("machine").get("enabled").getAsBoolean(), "OpenPnP stopped too");
        JsonObject reg = ok(get("/api/plan/" + planId)).getAsJsonObject("registrationRun");
        assertEquals(stage, reg.get("stoppedStage").getAsString(), reg.toString());
        assertEquals("ABORTED", reg.get("status").getAsString());
        // recorded as stopped
        JsonObject last = ok(get("/api/runs/last")).getAsJsonObject("run");
        assertEquals("ABORTED", last.get("status").getAsString());
        assertEquals("board-registration", last.get("kind").getAsString());
    }

    /** Start (a controller connect) and the start-angle confirm of the new connection (I11). */
    void restart() throws Exception {
        ok(post("/api/machine/enable", null));
        ok(post("/api/screw/start-angle", "{}"));
    }

    boolean n1Low() throws Exception {
        return ok(get("/api/status")).getAsJsonObject("machine").getAsJsonObject("home").get("n1Low").getAsBoolean();
    }

    long boardSurfaceAt() throws Exception {
        return ok(get("/api/surfaces")).getAsJsonObject("surfaces").getAsJsonObject("board").get("setAtMs")
                .getAsLong();
    }

    String registrationIdNow() throws Exception {
        JsonObject regs = ok(get("/api/config")).getAsJsonObject("boardRegistrations");
        return regs.getAsJsonObject(org.openpnp.instancelabs.dispense.BoardRegistration.fileKey(boardFile)).get("id")
                .getAsString();
    }

    void setInfinityTimeout(int ms) throws Exception {
        Field f = GcodeDriver.class.getDeclaredField("infinityTimeoutMilliseconds");
        f.setAccessible(true);
        f.setInt(sim.driver, ms);
    }

    static int indexOfPrefix(List<String> l, String prefix) {
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
