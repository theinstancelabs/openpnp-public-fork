package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
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
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.BoardFeatures;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.FlatVacuum;
import org.openpnp.instancelabs.dispense.PadGeometry;
import org.openpnp.instancelabs.dispense.ProbeTargets;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.instancelabs.dispense.VacuumProber;
import org.openpnp.instancelabs.sim.SimBoardRenderer;
import org.openpnp.instancelabs.sim.SimScene;
import org.openpnp.instancelabs.sim.SimVacuum;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.Package;
import org.openpnp.model.Part;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A board imported from KiCad goes through Register board in SIM (BOARD_IMPORT_DESIGN.md §6.5, §10.2 items 1 and 4):
 * the SIM top camera draws the imported ftp board at a seat moved, turned and scaled from where it is planned, the
 * SIM vacuum answers N1 from the scene; Register board finds its fiducials, probes it clear of the holes its KiCad
 * drawing names and plans its dots on the true pads. Replacing the import with a fiducial moved drops the stored
 * registration, and the run is refused until the board is registered again. No device is opened.
 */
public class BoardImportRegistrationSimTest {
    /** The nominal seat (where the plan puts the board), the needle frame and the blank. */
    static final double NX = 150, NY = 100, NZ = 5.5;
    /** ftp's FID1 in its KiCad file, and moved 0.3 mm in X (KiCad Y points down: board Y = -KiCad Y). */
    static final String FID1_AT = "(at 62.5 -5)", FID1_MOVED_AT = "(at 62.8 -5)";

    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;
    volatile SimScene scene;
    volatile File boardFile;

    /** S1: the board origin moved +0.60 / -0.45 mm, turned +0.20 deg, scaled 1.002 / 0.997, sheared 0.1 deg. */
    static AffineTransform s1() {
        return SimScene.seat(NX + 0.60, NY - 0.45, 0.20, 1.002, 0.997, 0.1);
    }

    /** The imported ftp (75 x 75 mm) at a seat; its fiducials drawn from the board as it is now in OpenPnP. */
    static SimScene scene(AffineTransform seat) {
        SimScene sc = new SimScene();
        sc.boardFile = "ftp.board.xml";
        sc.boardWidthMm = 75;
        sc.boardHeightMm = 75;
        sc.seat = seat;
        sc.boardSurface = new SimScene.Plane(5.43, 0.0006, -0.0009);
        // ftp's four M3 holes leak where N1 would touch them (the drill, r 1.6 mm)
        for (double[] h : new double[][] {{5, 5}, {70, 5}, {70, 70}, {5, 70}}) {
            sc.leakySpots.add(new SimScene.Spot(h[0], h[1], 1.6));
        }
        sc.blankRect = new double[] {55, 25, 230, 60};
        sc.blankSurface = new SimScene.Plane(NZ, 0, 0);
        sc.bedZ = 2.5;
        sc.vacuum.tauS = 0;
        sc.vacuum.sealTauS = 0;
        sc.vacuum.releaseTauS = 0;
        sc.validate();
        return sc;
    }

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine();
        FlatVacuum.install(sim, FlatVacuum.scene(5.5, null, 0));
        svc = DispenseService.start(Configuration.get());
        assertTrue(svc.isAvailable(), String.valueOf(svc.getUnavailableReason()));
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        scene = scene(s1());
        sim.server.vacuum = new SimVacuum(() -> scene, System::nanoTime);
        sim.server.firmwareName = FlatVacuum.SIMULATOR;
        svc.setDotImager(new SceneImager(() -> scene, this::board));
        // as RegistrationSimTest: places and the purge row on the blank, the needle frame, the blank surface,
        // 0603 characterized, the simulator's probe timings, the start angle confirmed for this connection
        ok(call("PUT", "/api/locations/waste", "{\"x\":80,\"y\":40,\"z\":5.5}"));
        ok(call("PUT", "/api/locations/blank", "{\"x\":90,\"y\":40,\"z\":5.5}"));
        ok(post("/api/needle/anchor", "{\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}"));
        ok(post("/api/needle/offset", "{\"offsetMm\":-2.59}"));
        ok(call("PUT", "/api/surfaces/blank", "{\"points\":[[60,30,5.5],[220,30,5.5],[60,55,5.5],[220,55,5.5]]}"));
        ok(call("PUT", "/api/config", "{\"purgeRow\":{\"x0Mm\":80,\"y0Mm\":47,\"pitchXMm\":2.5,\"spots\":48},"
                + "\"vacuumProbe\":{\"vacuumSettleMs\":300,\"readDelayMs\":0}}"));
        ok(call("PUT", "/api/pad-types/chip-0603", "{\"status\":\"characterized\"}"));
        ok(post("/api/screw/start-angle", "{}"));
    }

    @AfterEach
    public void after() throws Exception {
        api.stop();
        svc.shutdown();
        sim.close();
    }

    Board board() {
        try {
            return boardFile == null ? null : Configuration.get().getBoard(boardFile);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // HTTP and fixtures
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
            return status + " " + (body.length() > 2000 ? body.substring(0, 2000) + "…" : body);
        }
    }

    Res send(String method, String path, byte[] body, String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(180));
        if (body != null) {
            b.header("Content-Type", contentType);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        return new Res(http.send(b.build(), HttpResponse.BodyHandlers.ofString()));
    }

    Res call(String method, String path, String json) throws Exception {
        return send(method, path, json == null ? null : json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    Res get(String path) throws Exception {
        return call("GET", path, null);
    }

    Res post(String path, String json) throws Exception {
        return call("POST", path, json);
    }

    static JsonObject ok(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.json();
    }

    static byte[] fixture(String name) throws Exception {
        try (InputStream raw = BoardImportRegistrationSimTest.class.getResourceAsStream("/kicad/" + name)) {
            assertNotNull(raw, name);
            InputStream in = name.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    /** Preview, then save under the name "ftp" (a replace when asked): the saved board file. */
    File importFtp(byte[] kicad, boolean replace) throws Exception {
        JsonObject pv = ok(send("POST", "/api/boards/import?file=ftp.kicad_pcb", kicad, "application/octet-stream"));
        Res r = send("POST", "/api/boards/import/save?file=ftp.kicad_pcb&name=ftp&replace=" + (replace ? 1 : 0)
                + "&sha256=" + pv.getAsJsonObject("file").get("sha256").getAsString() + "&decisions="
                + enc(pv.get("decisions").getAsString()), kicad, "application/octet-stream");
        assertEquals(201, r.status, r.toString());
        File f = new File(r.json().get("file").getAsString());
        boardFile = f;
        return f;
    }

    String planBody(File f, String registration) {
        return "{\"source\":\"file\",\"file\":\"" + f.getAbsolutePath().replace("\\", "\\\\") + "\",\"x\":" + NX
                + ",\"y\":" + NY + ",\"z\":" + NZ + ",\"rotation\":0" + (registration == null ? ""
                        : ",\"registration\":\"" + registration + "\"") + "}";
    }

    JsonObject plan(File f, String registration) throws Exception {
        return ok(post("/api/plan", planBody(f, registration)));
    }

    JsonObject register(String planId) throws Exception {
        JsonObject r = ok(post("/api/plan/" + planId + "/register", "{\"wait\":true}"));
        assertTrue(r.get("finished").getAsBoolean(), r.toString());
        return r.getAsJsonObject("result");
    }

    static Point2D apply(AffineTransform t, double bx, double by) {
        return t.transform(new Point2D.Double(bx, by), null);
    }

    /** True pad centres: the board's pads (board mm, as the SIM camera draws them) through a seat. */
    Map<String, Point2D> truePads(AffineTransform seat) throws Exception {
        Map<String, Point2D> m = new HashMap<>();
        for (PadGeometry g : SimBoardRenderer.boardPads(board(), svc.padTypeResolver())) {
            m.put(g.padId, apply(seat, g.xMm, g.yMm));
        }
        return m;
    }

    /** Largest distance of the plan's pads and dots from the true pad centres (mm); every pad must be there. */
    static double worstPadError(JsonObject plan, Map<String, Point2D> pads) {
        double worst = 0;
        for (String k : new String[] {"pads", "dots"}) {
            for (JsonElement e : plan.getAsJsonArray(k)) {
                JsonObject p = e.getAsJsonObject();
                Point2D t = pads.get(p.get("padId").getAsString());
                assertNotNull(t, p.toString());
                worst = Math.max(worst, t.distance(p.get("x").getAsDouble(), p.get("y").getAsDouble()));
            }
        }
        return worst;
    }

    /** Every probe target and alternate of a registration preview, in board mm: {bx, by, target n}. */
    static List<double[]> probeSpots(JsonObject preview) {
        List<double[]> l = new ArrayList<>();
        for (JsonElement e : preview.getAsJsonArray("probes")) {
            JsonObject t = e.getAsJsonObject();
            int n = t.get("n").getAsInt();
            l.add(new double[] {t.get("bx").getAsDouble(), t.get("by").getAsDouble(), n});
            for (JsonElement a : t.getAsJsonArray("alternates")) {
                l.add(new double[] {a.getAsJsonObject().get("bx").getAsDouble(),
                        a.getAsJsonObject().get("by").getAsDouble(), n});
            }
        }
        return l;
    }

    /** The plan's dots in board mm (planned at the nominal seat: rotation 0, origin NX, NY). */
    static List<double[]> dotsBoard(JsonObject plan) {
        List<double[]> l = new ArrayList<>();
        for (JsonElement e : plan.getAsJsonArray("dots")) {
            JsonObject d = e.getAsJsonObject();
            l.add(new double[] {d.get("x").getAsDouble() - NX, d.get("y").getAsDouble() - NY});
        }
        return l;
    }

    // ------------------------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------------------------

    /**
     * The imported ftp registers: its fiducials found where the seat puts them, N1 probing clear of every hole its
     * KiCad drawing names (the four M3 holes and J1's pins, the outline), the new plan's dots on the true pads within
     * 0.05 mm, and 20 of them dispensed after the clean check saw copper under each.
     */
    @Test
    public void anImportedBoardRegistersAndItsDotsLandOnThePads() throws Exception {
        File f = importFtp(fixture("ftp.kicad_pcb.gz"), false);
        JsonObject p0 = plan(f, null);
        String p0Id = p0.get("planId").getAsString();
        assertEquals(160, p0.getAsJsonArray("dots").size());
        assertEquals("kicad", p0.getAsJsonObject("board").getAsJsonObject("art").get("kind").getAsString());
        Map<String, Point2D> s1Pads = truePads(s1());
        assertTrue(worstPadError(p0, s1Pads) > 0.4, "the nominal plan misses the true pads: sensitive by construction");

        // the probe targets of the preview stay off the holes of the drawing (the board's real holes)
        JsonObject preview = p0.getAsJsonObject("registrationTargets");
        assertTrue(preview.get("error").isJsonNull(), preview.toString());
        assertEquals(3, preview.getAsJsonArray("fiducials").size(), preview.toString());
        String artId = p0.getAsJsonObject("board").getAsJsonObject("art").get("id").getAsString();
        JsonArray holes = ok(get("/api/board-art/" + artId)).getAsJsonArray("keepOutHoles");
        assertEquals(6, holes.size(), "H1-H4 and J1's two pins");
        double keepOut = new DispenseConfig().vacuumProbe.padKeepOutMm;
        for (double[] p : probeSpots(preview)) {
            for (JsonElement h : holes) {
                JsonArray c = h.getAsJsonArray();
                double d = Math.hypot(p[0] - c.get(0).getAsDouble(), p[1] - c.get(1).getAsDouble());
                assertTrue(d >= c.get(2).getAsDouble() + keepOut - 1e-9, "target " + (int) p[2] + " at " + p[0] + ", "
                        + p[1] + " is " + d + " mm from the hole at " + c);
            }
        }

        // Register board: home is not needed, the fiducials, the probe, the plan made again
        JsonObject res = register(p0Id);
        assertEquals("OK", res.get("status").getAsString(), res.toString());
        assertEquals("first", res.get("seat").getAsString());
        assertTrue(res.get("simulated").getAsBoolean());
        double worstFid = 0;
        for (JsonElement e : res.getAsJsonArray("fiducials")) {
            JsonObject fd = e.getAsJsonObject();
            assertEquals("found", fd.get("state").getAsString(), fd.toString());
            JsonObject b = fd.getAsJsonObject("board");
            Point2D t = apply(s1(), b.get("x").getAsDouble(), b.get("y").getAsDouble());
            JsonObject found = fd.getAsJsonObject("found");
            worstFid = Math.max(worstFid, t.distance(found.get("x").getAsDouble(), found.get("y").getAsDouble()));
        }
        assertTrue(worstFid < 0.02, "fiducials within 0.02 mm of the true seat: " + worstFid);
        JsonObject probe = res.getAsJsonObject("probe");
        assertTrue(probe.get("saved").getAsBoolean(), probe.toString());
        for (JsonElement e : probe.getAsJsonArray("points")) {
            JsonObject pt = e.getAsJsonObject();
            assertEquals("contact", pt.get("state").getAsString(), pt.toString());
            Point2D b = scene.toBoard(pt.get("x").getAsDouble(), pt.get("y").getAsDouble());
            assertEquals(scene.boardContactZ(b.getX(), b.getY()), pt.get("z").getAsDouble(), 0.03, pt.toString());
        }

        // the plan made again: every pad and dot on the true pad, the drawing kept, the interlocks clear
        String p1Id = res.get("newPlanId").getAsString();
        assertNotEquals(p0Id, p1Id);
        JsonObject p1 = ok(get("/api/plan/" + p1Id));
        double worst = worstPadError(p1, s1Pads);
        assertTrue(worst < 0.05, "dots on the pads within 0.05 mm: " + worst);
        assertEquals(artId, p1.getAsJsonObject("board").getAsJsonObject("art").get("id").getAsString());
        assertTrue(p1.getAsJsonObject("interlocks").get("ok").getAsBoolean(), p1.toString());

        // 20 dots: the clean check sees copper under each (they lie on the pads), then they are dispensed
        JsonObject run = ok(post("/api/run", "{\"planId\":\"" + p1Id + "\",\"maxDots\":20,\"wait\":true}"))
                .getAsJsonObject("result");
        assertEquals("OK", run.get("status").getAsString(), run.toString());
        assertTrue(run.getAsJsonObject("cleanCheck").get("ok").getAsBoolean(), run.toString());
        assertEquals(20, run.get("dotsDone").getAsInt(), run.toString());
    }

    /**
     * The probe targets come from the drawing when the board has one (BOARD_IMPORT_DESIGN.md §10.2 item 4): the
     * imported ftp's targets are the ones its holes, vias and outline give (with the corner holes assumed instead,
     * target 1 sat 1.9 mm from J1's pin 1 drill, inside its copper ring); the bench's ftp.board.xml, which has no
     * KiCad drawing, keeps the mounting hole assumed in each corner (AR §4.6).
     */
    @Test
    public void theDrawingsHolesReplaceTheAssumedOnesOnlyWhenThereIsADrawing() throws Exception {
        File imported = importFtp(fixture("ftp.kicad_pcb.gz"), false);
        JsonObject withArt = plan(imported, null);
        RegistrationEndpoints.ArtHints art = RegistrationEndpoints.ArtHints.of(board());
        assertNotNull(art.keepOuts, "the import has its drawing");
        assertEquals(1.0, art.fiducials.get("FID1")[0], 1e-9);
        assertEquals(3.0, art.fiducials.get("FID1")[1], 1e-9);
        DispenseConfig c = svc.getConfig();
        BoardFeatures fArt = BoardFeatures.of(board(), svc.padTypeResolver(), art.fiducials);
        List<VacuumProber.Target> expected = ProbeTargets.forBoard(fArt, dotsBoard(withArt), c, art.keepOuts);
        assertSameTargets(expected, withArt.getAsJsonObject("registrationTargets"));
        // the assumption alone would have put N1 on a hole the drawing knows (J1's pins)
        boolean assumedHitsAHole = false;
        for (VacuumProber.Target t : ProbeTargets.forBoard(fArt, dotsBoard(withArt), c, null)) {
            for (ProbeTargets.KeepOut k : art.keepOuts) {
                assumedHitsAHole |= Math.hypot(t.bx - k.x, t.by - k.y) < k.r + c.vacuumProbe.padKeepOutMm - 1e-9;
            }
        }
        assertTrue(assumedHitsAHole, "the drawing changes where ftp is probed");
        for (double[] p : probeSpots(withArt.getAsJsonObject("registrationTargets"))) {
            for (ProbeTargets.KeepOut k : art.keepOuts) {
                assertTrue(Math.hypot(p[0] - k.x, p[1] - k.y) >= k.r + c.vacuumProbe.padKeepOutMm - 1e-9,
                        "target " + (int) p[2] + " near " + k.x + ", " + k.y);
            }
        }

        // the bench's ftp.board.xml: no KiCad drawing (its fallback drawing has no holes), the corner holes assumed
        Package fidPkg = new Package("Fiducial_1mm_Mask3mm-bench");
        Configuration.get().addPackage(fidPkg);
        for (String[] p : new String[][] {{"R_0603_1608Metric-R_Small", "R_0603_1608Metric"},
                {"LED_0603_1608Metric-LED_Small", "LED_0603_1608Metric"},
                {"Fiducial_1mm_Mask3mm-Fiducial", "Fiducial_1mm_Mask3mm"}}) {
            if (Configuration.get().getPart(p[0]) == null) {
                Part part = new Part(p[0]);
                part.setPackage(Configuration.get().getPackage(p[1]));
                Configuration.get().addPart(part);
            }
        }
        File bench = new File(sim.configDir, "bench-ftp.board.xml");
        Files.write(bench.toPath(), fixture("ftp.board.xml"));
        boardFile = bench;
        JsonObject noArt = plan(bench, null);
        assertEquals("fallback", noArt.getAsJsonObject("board").getAsJsonObject("art").get("kind").getAsString());
        RegistrationEndpoints.ArtHints none = RegistrationEndpoints.ArtHints.of(board());
        assertNull(none.keepOuts, "no KiCad drawing: nothing replaces the assumption");
        assertNull(none.fiducials);
        BoardFeatures fNone = BoardFeatures.of(board(), svc.padTypeResolver());
        assertSameTargets(ProbeTargets.forBoard(fNone, dotsBoard(noArt), c), noArt.getAsJsonObject(
                "registrationTargets"));
        for (double[] p : probeSpots(noArt.getAsJsonObject("registrationTargets"))) {
            for (double[] h : new double[][] {{4.5, 4.5}, {70.5, 4.5}, {70.5, 70.5}, {4.5, 70.5}}) {
                assertTrue(Math.hypot(p[0] - h[0], p[1] - h[1]) >= ProbeTargets.ASSUMED_HOLE_RADIUS_MM
                        + c.vacuumProbe.padKeepOutMm - 1e-9, "target " + (int) p[2] + " on an assumed corner hole");
            }
        }
    }

    static void assertSameTargets(List<VacuumProber.Target> expected, JsonObject preview) {
        JsonArray probes = preview.getAsJsonArray("probes");
        assertEquals(expected.size(), probes.size(), preview.toString());
        for (int i = 0; i < expected.size(); i++) {
            VacuumProber.Target t = expected.get(i);
            JsonObject p = probes.get(i).getAsJsonObject();
            assertEquals(t.bx, p.get("bx").getAsDouble(), 1e-9, p.toString());
            assertEquals(t.by, p.get("by").getAsDouble(), 1e-9, p.toString());
            JsonArray alts = p.getAsJsonArray("alternates");
            assertEquals(t.alternatesBoard.size(), alts.size(), p.toString());
            for (int k = 0; k < alts.size(); k++) {
                assertEquals(t.alternatesBoard.get(k)[0], alts.get(k).getAsJsonObject().get("bx").getAsDouble(), 1e-9);
                assertEquals(t.alternatesBoard.get(k)[1], alts.get(k).getAsJsonObject().get("by").getAsDouble(), 1e-9);
            }
        }
    }

    /**
     * §10.2 item 1: a replace that moves a fiducial makes the stored registration stale. The next plan with
     * registration "auto" carries none, its run is refused (the board surface belongs to the old registration),
     * and Register board makes a new one on which the run goes through.
     */
    @Test
    public void replacingAFiducialPositionDropsTheStoredRegistration() throws Exception {
        byte[] ftp = fixture("ftp.kicad_pcb.gz");
        File f = importFtp(ftp, false);
        JsonObject res = register(plan(f, null).get("planId").getAsString());
        assertEquals("OK", res.get("status").getAsString(), res.toString());
        String regId = res.get("registrationId").getAsString();
        JsonObject auto = plan(f, "auto");
        assertEquals(regId, auto.getAsJsonObject("registration").get("id").getAsString(), "the same board: applied");
        String autoId = auto.get("planId").getAsString();

        // the same KiCad file with FID1 0.3 mm to the right, saved over the import (the board on the bed is the
        // corrected one: the SIM camera draws FID1 where the board says)
        String text = new String(ftp, StandardCharsets.UTF_8);
        assertEquals(1, text.split(java.util.regex.Pattern.quote(FID1_AT), -1).length - 1);
        File again = importFtp(text.replace(FID1_AT, FID1_MOVED_AT).getBytes(StandardCharsets.UTF_8), true);
        assertEquals(f.getCanonicalPath(), again.getCanonicalPath(), "replaced in place");
        Res stale = post("/api/run", "{\"planId\":\"" + autoId + "\",\"maxDots\":4}");
        assertEquals(409, stale.status, "the plan of the old board: " + stale);

        JsonObject after = plan(f, "auto");
        assertTrue(after.get("registration").isJsonNull(), "FID1 moved: the stored registration no longer applies: "
                + after.get("registration"));
        Res refused = post("/api/run", "{\"planId\":\"" + after.get("planId").getAsString() + "\",\"maxDots\":4}");
        assertEquals(409, refused.status, refused.toString());
        assertTrue(refused.body.contains("registration"), refused.toString());

        JsonObject res2 = register(after.get("planId").getAsString());
        assertEquals("OK", res2.get("status").getAsString(), res2.toString());
        assertNotEquals(regId, res2.get("registrationId").getAsString(), "a new registration");
        assertEquals("first", res2.get("seat").getAsString(), "the old record is not this board's any more");
        JsonObject p2 = ok(get("/api/plan/" + res2.get("newPlanId").getAsString()));
        assertTrue(worstPadError(p2, truePads(s1())) < 0.05);
        JsonObject run = ok(post("/api/run", "{\"planId\":\"" + p2.get("planId").getAsString()
                + "\",\"maxDots\":4,\"wait\":true}")).getAsJsonObject("result");
        assertEquals("OK", run.get("status").getAsString(), run.toString());
        assertFalse(run.get("homedFirst").getAsBoolean());
    }
}
