package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.board.BoardArt;
import org.openpnp.instancelabs.board.BoardArtStore;
import org.openpnp.instancelabs.board.BoardImporter;
import org.openpnp.instancelabs.board.BoardImporterTest;
import org.openpnp.instancelabs.board.EdtRunner;
import org.openpnp.instancelabs.dispense.BoardPadExtractor;
import org.openpnp.instancelabs.dispense.DispenseDb;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.FlatVacuum;
import org.openpnp.instancelabs.dispense.PadGeometry;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.PadTypeResolver;
import org.openpnp.instancelabs.dispense.PadTypeStatus;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The KiCad upload end to end on a simulated machine (BOARD_IMPORT_DESIGN.md §6.5): the real ApiServer and
 * DispenseService over OpenPnP's GcodeDriver to the RecordingGcodeServer (localhost only), the fixtures of
 * src/test/resources/kicad sent as raw bytes. Preview, save, plan, the drawing, replace and staleness, attach,
 * limits, ids and rollback.
 */
public class BoardImportSimTest {
    static final String FTP = "ftp.kicad_pcb.gz";

    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;
    boolean stopped;

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
        BoardImporter.failAfterStep = 0;
        BoardImporter.inPipeline = null;
    }

    @AfterEach
    public void after() throws Exception {
        BoardImporter.failAfterStep = 0;
        BoardImporter.inPipeline = null;
        if (!stopped) {
            api.stop();
            svc.shutdown();
        }
        sim.close();
    }

    // ------------------------------------------------------------------------------------------
    // HTTP and fixtures
    // ------------------------------------------------------------------------------------------

    static final class Res {
        final int status;
        final byte[] bytes;
        final String body;
        final Map<String, List<String>> headers;

        Res(HttpResponse<byte[]> r) {
            status = r.statusCode();
            bytes = r.body();
            body = new String(bytes, StandardCharsets.UTF_8);
            headers = r.headers().map();
        }

        JsonObject json() {
            return new JsonParser().parse(body).getAsJsonObject();
        }

        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return status + " " + (body.length() > 2000 ? body.substring(0, 2000) + "…" : body);
        }
    }

    Res send(String method, String path, byte[] body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        return new Res(http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray()));
    }

    Res get(String path) throws Exception {
        return send("GET", path, null);
    }

    Res postJson(String path, String json) throws Exception {
        return send("POST", path, json == null ? null : json.getBytes(StandardCharsets.UTF_8), "Content-Type",
                "application/json");
    }

    Res upload(String path, byte[] body) throws Exception {
        return send("POST", path, body, "Content-Type", "application/octet-stream");
    }

    static JsonObject ok(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.json();
    }

    static JsonObject refused(Res r, int status, String code) {
        assertEquals(status, r.status, r.toString());
        JsonObject o = r.json();
        assertEquals(code, o.get("error").getAsString(), r.toString());
        return o;
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] fixture(String name) throws Exception {
        try (InputStream raw = BoardImportSimTest.class.getResourceAsStream("/kicad/" + name)) {
            assertNotNull(raw, name);
            InputStream in = name.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    JsonObject preview(byte[] b, String file) throws Exception {
        return ok(upload("/api/boards/import?file=" + enc(file), b));
    }

    Res saveRes(byte[] b, String file, String name, boolean replace, JsonObject pv) throws Exception {
        return upload("/api/boards/import/save?file=" + enc(file) + "&name=" + enc(name) + "&replace="
                + (replace ? 1 : 0) + "&sha256=" + pv.getAsJsonObject("file").get("sha256").getAsString()
                + "&decisions=" + pv.get("decisions").getAsString(), b);
    }

    /** Preview, then save under a name (201). */
    JsonObject importBoard(byte[] b, String file, String name, boolean replace) throws Exception {
        Res r = saveRes(b, file, name, replace, preview(b, file));
        assertEquals(201, r.status, r.toString());
        return r.json();
    }

    static String text(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    static byte[] edit(byte[] b, String from, String to) {
        String s = text(b);
        assertEquals(1, s.split(java.util.regex.Pattern.quote(from), -1).length - 1, from);
        return s.replace(from, to).getBytes(StandardCharsets.UTF_8);
    }

    /** The ftp fixture with R1 moved 1 mm in X. */
    static byte[] ftpR1Moved() throws Exception {
        return edit(fixture(FTP), "(at 37.499966 -42.499987 90)", "(at 38.499966 -42.499987 90)");
    }

    static Set<String> codes(JsonObject pv) {
        Set<String> s = new HashSet<>();
        for (JsonElement w : pv.getAsJsonArray("warnings")) {
            s.add(w.getAsJsonObject().get("code").getAsString());
        }
        return s;
    }

    static JsonObject byId(JsonArray a, String key, String id) {
        for (JsonElement e : a) {
            if (e.getAsJsonObject().has(key) && !e.getAsJsonObject().get(key).isJsonNull()
                    && id.equals(e.getAsJsonObject().get(key).getAsString())) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    File boardsRoot() {
        return new File(sim.configDir, "instancelabs/boards");
    }

    String planBody(String file, double x, double y, double z, double rot) {
        return "{\"source\":\"file\",\"file\":\"" + file.replace("\\", "\\\\") + "\",\"x\":" + x + ",\"y\":" + y
                + ",\"z\":" + z + ",\"rotation\":" + rot + "}";
    }

    JsonObject plan(String file) throws Exception {
        return ok(postJson("/api/plan", planBody(file, 120, 120, 10, 0)));
    }

    static List<String> listing(File dir) throws Exception {
        List<String> l = new ArrayList<>();
        if (dir.isDirectory()) {
            try (java.util.stream.Stream<java.nio.file.Path> s = Files.walk(dir.toPath())) {
                s.filter(Files::isRegularFile).forEach(p -> l.add(dir.toPath().relativize(p).toString()));
            }
        }
        l.sort(null);
        return l;
    }

    static Map<String, String> contents(File dir) throws Exception {
        Map<String, String> m = new TreeMap<>();
        for (String f : listing(dir)) {
            m.put(f, BoardArt.sha256Hex(Files.readAllBytes(new File(dir, f).toPath())));
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------
    // 1-4, 9: preview, save, plan, the drawing, persistence
    // ------------------------------------------------------------------------------------------

    @Test
    public void ftpPreviewSavePlanAndTheDrawing() throws Exception {
        // the 0603s' pad type passes the gate: nothing needs a profile (PROFILE_CALIBRATION_DESIGN.md §12.6)
        ok(send("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\"}".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/json"));
        byte[] ftp = fixture(FTP);
        JsonObject pv = preview(ftp, "ftp.kicad_pcb");
        JsonObject counts = pv.getAsJsonObject("counts");
        assertEquals(80, counts.get("placements").getAsInt());
        assertEquals(3, counts.get("fiducials").getAsInt());
        assertEquals(160, counts.get("pastePads").getAsInt());
        assertTrue(pv.get("decisions").getAsString().matches("[0-9a-f]{16}"));
        assertEquals("ftp", pv.get("suggestedName").getAsString());
        assertEquals("ftp", pv.get("slug").getAsString());
        assertTrue(pv.get("exists").isJsonNull());
        assertEquals(8, pv.getAsJsonObject("kicad").get("major").getAsInt());
        JsonObject bd = pv.getAsJsonObject("board");
        assertEquals(75.0, bd.get("widthMm").getAsDouble(), 1e-9);
        assertEquals("outline", bd.get("origin").getAsString());
        assertEquals(0, pv.getAsJsonArray("attach").size());
        assertEquals("kicad", pv.getAsJsonObject("art").get("kind").getAsString());
        assertTrue(pv.getAsJsonObject("art").getAsJsonObject("outline").get("closed").getAsBoolean());
        JsonArray packages = pv.getAsJsonArray("packages");
        assertEquals(3, packages.size());
        for (JsonElement e : packages) {
            assertEquals("new", e.getAsJsonObject().get("state").getAsString(), e.toString());
        }
        JsonObject r = byId(packages, "id", "R_0603_1608Metric");
        JsonObject rType = r.getAsJsonArray("padTypes").get(0).getAsJsonObject();
        assertEquals("chip-0603", rType.get("typeKey").getAsString());
        assertEquals("pattern", rType.get("rule").getAsString());
        assertEquals("0603", rType.get("matched").getAsString());
        assertEquals("Chosen because the package name contains “0603”.", rType.get("sentence").getAsString());
        assertEquals(2, rType.get("pads").getAsInt());
        assertEquals("paste", r.get("outcome").getAsString());
        assertEquals("not-dispensed", byId(packages, "id", "Fiducial_1mm_Mask3mm").get("outcome").getAsString());
        JsonObject r1 = byId(pv.getAsJsonArray("placements"), "id", "R1");
        assertEquals(37.499966, r1.get("x").getAsDouble(), 1e-9);
        assertEquals(42.499987, r1.get("y").getAsDouble(), 1e-9);
        assertEquals("R_0603_1608Metric-R_Small", r1.get("partId").getAsString());
        assertEquals("paste", r1.get("outcome").getAsString());
        JsonObject fid1 = byId(pv.getAsJsonArray("fiducials"), "id", "FID1");
        assertEquals(62.5, fid1.get("x").getAsDouble(), 1e-6);
        assertEquals(1.0, fid1.get("diameterMm").getAsDouble(), 1e-9);
        assertEquals(3.0, fid1.get("maskMm").getAsDouble(), 1e-9);
        Set<String> codes = codes(pv);
        assertTrue(codes.contains("BOTTOM_SKIPPED"), codes.toString());
        for (JsonElement w : pv.getAsJsonArray("warnings")) {
            assertTrue(w.getAsJsonObject().get("group").getAsInt() >= 3, "nothing blocks ftp: " + w);
        }

        // 2. save
        JsonObject saved = ok(saveRes(ftp, "ftp.kicad_pcb", "ftp", false, pv));
        File dir = new File(boardsRoot(), "ftp");
        File boardFile = new File(dir, "ftp.board.xml");
        assertEquals(boardFile.getCanonicalPath(), saved.get("file").getAsString());
        assertFalse(saved.get("replaced").getAsBoolean());
        assertEquals(Arrays.asList("ftp.art.json", "ftp.board.xml", "ftp.kicad_pcb"), listing(dir));
        assertArrayEquals(ftp, Files.readAllBytes(new File(dir, "ftp.kicad_pcb").toPath()), "the upload, unchanged");
        String artId = saved.getAsJsonObject("art").get("id").getAsString();
        assertEquals(artId, BoardArt.idOf(Files.readAllBytes(new File(dir, "ftp.art.json").toPath())));
        String packagesXml = text(Files.readAllBytes(new File(sim.configDir, "packages.xml").toPath()));
        String partsXml = text(Files.readAllBytes(new File(sim.configDir, "parts.xml").toPath()));
        String boardsXml = text(Files.readAllBytes(new File(sim.configDir, "boards.xml").toPath()));
        assertTrue(packagesXml.contains("id=\"R_0603_1608Metric\"") && packagesXml.contains("LED_0603_1608Metric"));
        assertTrue(partsXml.contains("id=\"R_0603_1608Metric-R_Small\""), partsXml);
        assertTrue(boardsXml.contains(boardFile.getCanonicalPath()), boardsXml);
        String index = text(Files.readAllBytes(new File(boardsRoot(), "index.json").toPath()));
        assertTrue(index.contains("\"artId\": \"" + artId + "\""), index);
        JsonObject listed = byId(saved.getAsJsonObject("boards").getAsJsonArray("files"), "file",
                boardFile.getCanonicalPath());
        assertNotNull(listed, saved.getAsJsonObject("boards").toString());
        assertEquals("kicad", listed.getAsJsonObject("art").get("kind").getAsString());
        assertEquals(artId, listed.getAsJsonObject("art").get("id").getAsString());
        assertEquals("outline", listed.get("origin").getAsString());
        assertEquals(3, listed.getAsJsonArray("fiducials").size());
        assertEquals(83, listed.get("placements").getAsInt());
        assertEquals("ftp", listed.getAsJsonObject("imported").get("slug").getAsString());
        assertEquals("ftp.kicad_pcb", listed.getAsJsonObject("imported").get("sourceName").getAsString());

        // 3. plan it at (120, 120, z 10, 0°)
        JsonObject plan = plan(boardFile.getCanonicalPath());
        JsonArray pads = plan.getAsJsonArray("pads");
        assertEquals(160, pads.size());
        JsonObject board = plan.getAsJsonObject("board");
        double[] m = new double[6];
        for (int i = 0; i < 6; i++) {
            m[i] = board.getAsJsonArray("toMachine").get(i).getAsDouble();
        }
        assertArrayEquals(new double[] {1, 0, 0, 1, 120, 120}, m, 1e-9);
        assertEquals(artId, board.getAsJsonObject("art").get("id").getAsString());
        assertEquals("kicad", board.getAsJsonObject("art").get("kind").getAsString());
        assertEquals(3, board.getAsJsonArray("fiducials").size());
        JsonObject planFid1 = byId(board.getAsJsonArray("fiducials"), "id", "FID1");
        assertEquals(182.5, planFid1.get("x").getAsDouble(), 1e-6);
        assertEquals(125.0, planFid1.get("y").getAsDouble(), 1e-6);
        double maxR = 0;
        double maxLed = 0;
        for (JsonElement e : pads) {
            JsonObject p = e.getAsJsonObject();
            assertEquals("FOOTPRINT", p.get("source").getAsString());
            assertEquals(50.0, p.get("roundness").getAsDouble(), 1e-9);
            assertTrue(p.get("padName").getAsString().matches("[12]"), p.toString());
            assertEquals("0603", p.getAsJsonObject("typeRule").get("matched").getAsString());
            assertTrue(p.getAsJsonObject("typeRule").get("sentence").getAsString().startsWith("Chosen because"));
            // the pad-type plan of the same placement: chip-0603 pitch 1.65 mm
            JsonObject pl = byId(pv.getAsJsonArray("placements"), "id", p.get("placementId").getAsString());
            double rot = Math.toRadians(pl.get("rotation").getAsDouble());
            double u = "1".equals(p.get("padName").getAsString()) ? -0.825 : 0.825;
            double ex = 120 + pl.get("x").getAsDouble() + u * Math.cos(rot);
            double ey = 120 + pl.get("y").getAsDouble() + u * Math.sin(rot);
            double d = Math.hypot(p.get("x").getAsDouble() - ex, p.get("y").getAsDouble() - ey);
            if (p.get("placementId").getAsString().startsWith("R")) {
                maxR = Math.max(maxR, d);
            }
            else {
                maxLed = Math.max(maxLed, d);
            }
        }
        assertTrue(maxR <= 0.01, "R pads " + maxR);
        assertTrue(maxLed <= 0.04, "LED pads " + maxLed);
        assertTrue(plan.getAsJsonObject("summary").get("estimateError").isJsonNull(), plan.toString());
        JsonArray dots = plan.getAsJsonArray("dots");
        assertEquals(160, dots.size());
        double last = -1;
        for (JsonElement e : dots) {
            double ta = e.getAsJsonObject().get("ta").getAsDouble();
            double te = e.getAsJsonObject().get("te").getAsDouble();
            assertTrue(ta >= last && te > ta, e.toString());
            last = te;
        }
        assertEquals("none", plan.getAsJsonObject("dot").get("source").getAsString(), "no dot ladder measured");

        // 4. the drawing: served by id, cached for good
        Res art = get("/api/board-art/" + artId);
        assertEquals(200, art.status);
        assertEquals("private, max-age=31536000, immutable", art.header("Cache-Control"));
        assertTrue(art.header("Content-Type").startsWith("application/json"));
        assertEquals(artId, BoardArt.idOf(art.bytes));
        refused(get("/api/board-art/0123456789abcdef"), 404, "art-gone");
        refused(get("/api/board-art/nope"), 404, "art-gone");

        // 9. persistence: OpenPnP loads the same directory again
        String path = boardFile.getCanonicalPath();
        api.stop();
        svc.shutdown();
        stopped = true;
        Configuration.initialize(sim.configDir);
        Configuration.get().load();
        Board loaded = null;
        for (Board b : Configuration.get().getBoards()) {
            if (b.getFile() != null && b.getFile().getCanonicalPath().equals(path)) {
                loaded = b;
            }
        }
        assertNotNull(loaded, "listed in boards.xml");
        assertEquals(83, loaded.getPlacements().size());
        for (Placement pl : loaded.getPlacements()) {
            assertNotNull(pl.getPart(), pl.getId());
            assertNotNull(pl.getPart().getPackage(), pl.getId());
        }
        List<PadType> seeds = new ArrayList<>();
        for (PadType t : DispenseDb.seedPadTypes()) {
            t.version = 1;
            t.status = PadTypeStatus.PROVISIONAL;
            seeds.add(t);
        }
        BoardLocation bl = new BoardLocation(loaded);
        bl.setLocation(new Location(LengthUnit.Millimeters, 0, 0, 0, 0));
        List<PadGeometry> geo = new BoardPadExtractor(new PadTypeResolver(seeds, null), true).extract(bl).pads;
        assertEquals(160, geo.size());
        for (PadGeometry g : geo) {
            assertEquals(PadGeometry.Source.FOOTPRINT, g.source);
        }
        BoardArtStore.ArtRef ref = BoardArtStore.get().refFor(loaded, null);
        assertEquals("kicad", ref.kind);
        assertEquals(artId, ref.id);
    }

    // ------------------------------------------------------------------------------------------
    // 5-7: replace, staleness, the drawing fixed at plan time
    // ------------------------------------------------------------------------------------------

    @Test
    public void replaceKeepsHistoryAndMakesOldPlansStale() throws Exception {
        setUpBoardRuns();
        ok(send("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\"}".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/json"));
        byte[] ftp = fixture(FTP);
        JsonObject first = importBoard(ftp, "ftp.kicad_pcb", "ftp", false);
        String file = first.get("file").getAsString();
        String art1 = first.getAsJsonObject("art").get("id").getAsString();

        // 5. the same name again: asks first
        JsonObject pv = preview(ftp, "ftp.kicad_pcb");
        assertEquals("ftp", pv.getAsJsonObject("exists").get("slug").getAsString());
        for (JsonElement e : pv.getAsJsonArray("packages")) {
            assertEquals("reuse", e.getAsJsonObject().get("state").getAsString(), "the saved packages are used");
        }
        JsonObject exists = refused(saveRes(ftp, "ftp.kicad_pcb", "ftp", false, pv), 409, "exists");
        assertTrue(exists.get("reason").getAsString().startsWith("A board named \"ftp\" exists (imported "),
                exists.toString());
        assertEquals("ftp", exists.getAsJsonObject("exists").get("slug").getAsString());

        // plan A on the first board (z 5.5: the board surface of setPlaces)
        JsonObject planA = ok(postJson("/api/plan", planBody(file, 100, 80, 5.5, 0)));
        String a = planA.get("planId").getAsString();
        assertEquals(art1, planA.getAsJsonObject("board").getAsJsonObject("art").get("id").getAsString());

        // 6./7. replace with R1 moved 1 mm
        JsonObject second = importBoard(ftpR1Moved(), "ftp.kicad_pcb", "ftp", true);
        assertTrue(second.get("replaced").getAsBoolean());
        String art2 = second.getAsJsonObject("art").get("id").getAsString();
        assertNotEquals(art1, art2);
        File dir = new File(boardsRoot(), "ftp");
        assertEquals(Arrays.asList("ftp.art.json", "ftp.board.xml", "ftp.kicad_pcb", "history/" + art1 + ".art.json",
                "history/" + art1 + ".kicad_pcb"), listing(dir));
        assertEquals(200, get("/api/board-art/" + art1).status, "the old drawing is still served");
        Res run = postJson("/api/run", "{\"planId\":\"" + a + "\"}");
        assertEquals(409, run.status, run.toString());
        assertTrue(run.body.contains("changed"), run.toString());
        JsonObject again = ok(get("/api/plan/" + a));
        assertEquals(art1, again.getAsJsonObject("board").getAsJsonObject("art").get("id").getAsString(),
                "a plan keeps the drawing it was made with");
        JsonObject planB = ok(postJson("/api/plan", planBody(file, 100, 80, 5.5, 0)));
        assertEquals(art2, planB.getAsJsonObject("board").getAsJsonObject("art").get("id").getAsString());

        // only R1's pads change (a paste margin): the placements are the same, the content digest is not
        String b = planB.get("planId").getAsString();
        byte[] margin = edit(ftpR1Moved(), "(at 38.499966 -42.499987 90)",
                "(at 38.499966 -42.499987 90)\n\t\t(solder_paste_margin -0.05)");
        JsonObject third = importBoard(margin, "ftp.kicad_pcb", "ftp", true);
        JsonObject r1Package = byId(third.getAsJsonArray("packages"), "id", "R_0603_1608Metric~2");
        assertEquals("suffixed", r1Package.get("state").getAsString(), "R1's footprint is a variant of its own");
        assertEquals("reuse", byId(third.getAsJsonArray("packages"), "id", "R_0603_1608Metric").get("state")
                .getAsString());
        run = postJson("/api/run", "{\"planId\":\"" + b + "\"}");
        assertEquals(409, run.status, run.toString());
        assertTrue(run.body.contains("changed"), run.toString());
    }

    // ------------------------------------------------------------------------------------------
    // 8: a hand edit and copies
    // ------------------------------------------------------------------------------------------

    @Test
    public void aHandEditFallsBackAndACopyKeepsTheDrawing() throws Exception {
        byte[] ftp = fixture(FTP);
        JsonObject saved = importBoard(ftp, "ftp.kicad_pcb", "ftp", false);
        String file = saved.get("file").getAsString();
        String artId = saved.getAsJsonObject("art").get("id").getAsString();
        // a byte copy under another path (uinav copies every listed board): the same drawing
        File copy = new File(sim.configDir, "00-ftp.board.xml");
        Files.copy(new File(file).toPath(), copy.toPath());
        EdtRunner.call(() -> Configuration.get().getBoard(copy), 10_000);
        JsonObject boards = ok(get("/api/boards"));
        JsonObject c = byId(boards.getAsJsonArray("files"), "file", copy.getCanonicalPath());
        assertEquals("kicad", c.getAsJsonObject("art").get("kind").getAsString());
        assertEquals(artId, c.getAsJsonObject("art").get("id").getAsString());

        // a placement moved in OpenPnP's Boards tab
        EdtRunner.call(() -> {
            Board b = Configuration.get().getBoard(new File(file));
            Placement r1 = b.getPlacements().get("R1");
            r1.setLocation(r1.getLocation().add(new Location(LengthUnit.Millimeters, 0.5, 0, 0, 0)));
            return null;
        }, 10_000);
        boards = ok(get("/api/boards"));
        JsonObject edited = byId(boards.getAsJsonArray("files"), "file", file);
        assertEquals("fallback", edited.getAsJsonObject("art").get("kind").getAsString());
        assertEquals(BoardArtStore.NOTE_CHANGED, edited.getAsJsonObject("art").get("note").getAsString());
        assertTrue(edited.get("imported").isJsonNull());
        String fallbackId = edited.getAsJsonObject("art").get("id").getAsString();
        JsonObject plan = plan(file);
        assertEquals(fallbackId, plan.getAsJsonObject("board").getAsJsonObject("art").get("id").getAsString());
        assertEquals("fallback", plan.getAsJsonObject("board").getAsJsonObject("art").get("kind").getAsString());
        assertEquals(200, get("/api/board-art/" + fallbackId).status);
    }

    // ------------------------------------------------------------------------------------------
    // 10: limits and refusals
    // ------------------------------------------------------------------------------------------

    /**
     * The answer to a raw request that declares a body of contentLength bytes and sends only its first 16 KB (less
     * than the server drains before it closes, so the answer is never cut by a reset): the status line, the headers
     * and the body.
     */
    String rawAnswer(String path, long contentLength) throws Exception {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), api.getPort())) {
            s.setSoTimeout(20_000);
            OutputStream out = s.getOutputStream();
            out.write(("POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + api.getPort() + "\r\nContent-Type: "
                    + "application/octet-stream\r\nContent-Length: " + contentLength + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[16 * 1024]);
            out.flush();
            InputStream in = s.getInputStream();
            StringBuilder head = new StringBuilder();
            int length = 0;
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                head.append(line).append('\n');
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring(15).trim());
                }
            }
            byte[] body = in.readNBytes(length);
            return head + "\n" + text(body);
        }
    }

    static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) >= 0 && c != '\n';) {
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    @Test
    public void limitsAndRefusals() throws Exception {
        byte[] ftp = fixture(FTP);
        // over 12 MiB, declared: refused before a byte is read
        String big = rawAnswer("/api/boards/import?file=big.kicad_pcb", BoardImporter.MAX_UPLOAD + 1L);
        assertTrue(big.startsWith("HTTP/1.1 413"), big);
        assertTrue(big.contains("The file is larger than 12 MB."), big);
        // the other routes keep the 2 MiB cap
        String plan3 = rawAnswer("/api/plan", 3 * 1024 * 1024);
        assertTrue(plan3.startsWith("HTTP/1.1 413"), plan3);
        assertTrue(plan3.contains("request body larger than 2097152 bytes"), plan3);
        // what is not a board
        refused(upload("/api/boards/import?file=x.json", "{\"file\":\"ftp\"}".getBytes(StandardCharsets.UTF_8)), 400,
                "bad-request");
        refused(upload("/api/boards/import?file=x", new byte[0]), 400, "bad-request");
        JsonObject mod = refused(upload("/api/boards/import?file=R.kicad_mod",
                "(footprint \"R_0603\" (layer \"F.Cu\"))".getBytes(StandardCharsets.UTF_8)), 400, "not-kicad");
        assertTrue(mod.get("reason").getAsString().startsWith("This is a KiCad footprint"));
        JsonObject cut = refused(upload("/api/boards/import?file=cut.kicad_pcb", Arrays.copyOf(ftp, ftp.length / 2)),
                400, "kicad-syntax");
        assertTrue(cut.get("reason").getAsString().matches("Line \\d+, column \\d+: .*"), cut.toString());
        // browser safety (a small body: the refusal comes before the body is read, and the server drains only
        // 64 KB of a body it does not read before it closes the connection)
        byte[] small = BoardImporterTest.board(BoardImporterTest.outline(20, 10));
        assertEquals(403, send("POST", "/api/boards/import?file=x.kicad_pcb", small, "Origin", "http://evil.example")
                .status);
        // name and file checks of the save
        JsonObject pv = preview(ftp, "ftp.kicad_pcb");
        refused(saveRes(ftp, "ftp.kicad_pcb", "!!", false, pv), 400, "bad-request");
        byte[] other = ftpR1Moved();
        JsonObject changed = refused(saveRes(other, "ftp.kicad_pcb", "ftp", false, pv), 400, "bad-request");
        assertEquals("The file changed since the preview: upload it again.", changed.get("reason").getAsString());
        // no outline and one fiducial: the origin cannot be found on the machine
        byte[] noOrigin = BoardImporterTest.board(BoardImporterTest.part2("Resistor_SMD", "R_0603_1608Metric", "R1",
                5, 5, "10k", 0.8) + BoardImporterTest.fid("FID1", 2, 2));
        JsonObject pvNo = preview(noOrigin, "loose.kicad_pcb");
        assertEquals("estimated", pvNo.getAsJsonObject("board").get("origin").getAsString());
        refused(saveRes(noOrigin, "loose.kicad_pcb", "loose", false, pvNo), 400, "no-origin");
        // 99 variants of K
        EdtRunner.call(() -> {
            Configuration.get().addPackage(new Package("K"));
            for (int k = 2; k <= 99; k++) {
                Package p = new Package("K~" + k);
                Footprint.Pad pad = new Footprint.Pad();
                pad.setName("1");
                pad.setWidth(3);
                pad.setHeight(3);
                p.getFootprint().addPad(pad);
                Configuration.get().addPackage(p);
            }
            return null;
        }, 10_000);
        byte[] k = BoardImporterTest.board(BoardImporterTest.outline(20, 10)
                + BoardImporterTest.part2("Lib", "K", "U1", 5, 5, "A", 0.8));
        JsonObject ex = refused(upload("/api/boards/import?file=k.kicad_pcb", k), 409, "ids-exhausted");
        assertTrue(ex.get("reason").getAsString().startsWith("K already has 99 variants"), ex.toString());
        // a plan of a board file that does not exist: 404, and no file is made
        File missing = new File(sim.configDir, "missing.board.xml");
        refused(postJson("/api/plan", planBody(missing.getAbsolutePath(), 100, 80, 5.5, 0)), 404, "not-found");
        assertFalse(missing.exists());
        // two previews at once: the second is busy
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BoardImporter.inPipeline = () -> {
            inside.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        AtomicReference<Res> firstRes = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                firstRes.set(upload("/api/boards/import?file=ftp.kicad_pcb", ftp));
            }
            catch (Exception e) {
                // asserted below
            }
        });
        t.start();
        assertTrue(inside.await(20, TimeUnit.SECONDS));
        BoardImporter.inPipeline = null;
        JsonObject busy = refused(upload("/api/boards/import?file=ftp.kicad_pcb", ftp), 409, "busy");
        assertEquals("Another board file is being read: try again in a moment.", busy.get("reason").getAsString());
        release.countDown();
        t.join(60_000);
        assertEquals(200, firstRes.get().status, String.valueOf(firstRes.get()));
    }

    @Test
    public void uploadsWaitForTheRunToFinish() throws Exception {
        setUpBoardRuns();
        double[][] pos = new double[30][];
        for (int i = 0; i < pos.length; i++) {
            // 4 mm apart: at 2.5 mm two 0603s' facing dots would bridge (MAY_BRIDGE)
            pos[i] = new double[] {2 + 4.0 * (i % 6), 2 + 3 * (i / 6), 0};
        }
        File board = writeBoard(pos);
        ok(send("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\"}".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/json"));
        byte[] ftp = fixture(FTP);
        JsonObject pv = preview(ftp, "ftp.kicad_pcb");
        JsonObject plan = planTied(board);
        assertTrue(plan.getAsJsonObject("interlocks").get("ok").getAsBoolean(), plan.toString());
        sim.server.delayBeforeOkMs.put("G4", 60L); // a slow run: every dwell waits
        try {
            assertEquals(202, postJson("/api/run", "{\"planId\":\"" + plan.get("planId").getAsString() + "\"}").status);
            long t0 = System.currentTimeMillis();
            while (!svc.isBusy() && System.currentTimeMillis() - t0 < 10_000) {
                Thread.sleep(10);
            }
            assertTrue(svc.isBusy());
            String why = "Upload the board when the run has finished.";
            assertEquals(why, refused(upload("/api/boards/import?file=ftp.kicad_pcb", ftp), 409, "refused")
                    .get("reason").getAsString());
            assertEquals(why, refused(saveRes(ftp, "ftp.kicad_pcb", "ftp", false, pv), 409, "refused").get("reason")
                    .getAsString());
            assertEquals(why, refused(upload("/api/boards/import/attach?file=ftp.kicad_pcb&board=" + enc(
                    board.getAbsolutePath()) + "&sha256=" + pv.getAsJsonObject("file").get("sha256").getAsString(),
                    ftp), 409, "refused").get("reason").getAsString());
        }
        finally {
            ok(postJson("/api/stop", null));
            sim.server.delayBeforeOkMs.clear();
            long t0 = System.currentTimeMillis();
            while (svc.isBusy() && System.currentTimeMillis() - t0 < 30_000) {
                Thread.sleep(20);
            }
        }
        assertFalse(new File(boardsRoot(), "ftp").exists());
    }

    /**
     * Review 1: an upload checked once, at its start, that the engine was idle, then read the file for up to 20 s
     * while a run, a registration or a probe could start (§3.4: no parse while one streams). They now refuse while
     * an upload holds the pipeline.
     */
    @Test
    public void runsAndProbesWaitForTheUploadToFinish() throws Exception {
        setUpBoardRuns();
        ok(send("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\"}".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/json"));
        JsonObject plan = planTied(writeBoard(new double[][] {{2, 2, 0}, {6, 2, 0}}));
        assertTrue(plan.getAsJsonObject("interlocks").get("ok").getAsBoolean(), plan.toString());
        String run = "{\"planId\":\"" + plan.get("planId").getAsString() + "\"}";
        byte[] ftp = fixture(FTP);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BoardImporter.inPipeline = () -> {
            inside.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        AtomicReference<Res> uploaded = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                uploaded.set(upload("/api/boards/import?file=ftp.kicad_pcb", ftp));
            }
            catch (Exception e) {
                // asserted below
            }
        });
        t.start();
        try {
            assertTrue(inside.await(20, TimeUnit.SECONDS));
            BoardImporter.inPipeline = null;
            // the status says so: the web UI locks what the engine refuses meanwhile
            assertTrue(ok(get("/api/status")).getAsJsonObject("boardImport").get("reading").getAsBoolean());
            String why = "a board file is being read (an upload takes up to about 20 s): wait for it to finish";
            assertEquals(why, refused(postJson("/api/run", run), 409, "refused").get("reason").getAsString());
            assertEquals(why, refused(postJson("/api/surfaces/blank/probe", "{}"), 409, "refused").get("reason")
                    .getAsString());
            assertFalse(svc.isBusy(), "nothing started");
        }
        finally {
            release.countDown();
            t.join(60_000);
        }
        assertEquals(200, uploaded.get().status, String.valueOf(uploaded.get()));
        assertFalse(ok(get("/api/status")).getAsJsonObject("boardImport").get("reading").getAsBoolean());
        sim.server.delayBeforeOkMs.put("G4", 60L);
        try {
            assertEquals(202, postJson("/api/run", run).status, "the upload has finished");
        }
        finally {
            ok(postJson("/api/stop", null));
            sim.server.delayBeforeOkMs.clear();
            long t0 = System.currentTimeMillis();
            while (svc.isBusy() && System.currentTimeMillis() - t0 < 30_000) {
                Thread.sleep(20);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // 11: ids and cleaning
    // ------------------------------------------------------------------------------------------

    @Test
    public void idsAreAllocatedWithoutChangingWhatOpenPnpHas() throws Exception {
        // (a) the live case: R_0603_1608Metric without pads, mapped to a pad type by hand
        EdtRunner.call(() -> {
            Package pkg = new Package("R_0603_1608Metric");
            Configuration.get().addPackage(pkg);
            Part part = new Part("R_0603_1608Metric-R_Small");
            part.setPackage(pkg);
            Configuration.get().addPart(part);
            return null;
        }, 10_000);
        svc.getDb().mapPackage("R_0603_1608Metric", "chip-0805");
        byte[] ftp = fixture(FTP);
        JsonObject pv = preview(ftp, "ftp.kicad_pcb");
        JsonObject r = byId(pv.getAsJsonArray("packages"), "baseId", "R_0603_1608Metric");
        assertEquals("R_0603_1608Metric~2", r.get("id").getAsString());
        assertEquals("suffixed", r.get("state").getAsString());
        assertEquals("R_0603_1608Metric exists in OpenPnP without pads", r.get("why").getAsString());
        assertEquals("R_0603_1608Metric", r.get("usesPadTypesOf").getAsString(), "the mapping is carried over");
        JsonObject type = r.getAsJsonArray("padTypes").get(0).getAsJsonObject();
        assertEquals("chip-0805", type.get("typeKey").getAsString());
        assertEquals("package-map", type.get("rule").getAsString());
        assertTrue(codes(pv).contains("PACKAGE_SUFFIXED"));
        // a save that fails after step 3 takes the carried mapping back with the packages and parts
        BoardImporter.failAfterStep = 3;
        refused(saveRes(ftp, "ftp.kicad_pcb", "ftp", false, pv), 500, "internal");
        BoardImporter.failAfterStep = 0;
        assertNull(Configuration.get().getPackage("R_0603_1608Metric~2"));
        assertFalse(svc.getDb().packageMappings().containsKey("R_0603_1608Metric~2"), "the mapping is undone");
        JsonObject saved = ok(saveRes(ftp, "ftp.kicad_pcb", "ftp", false, pv));
        assertEquals("R_0603_1608Metric~2-R_Small", byId(saved.getAsJsonArray("parts"), "id",
                "R_0603_1608Metric~2-R_Small").get("id").getAsString());
        assertEquals(0, Configuration.get().getPackage("R_0603_1608Metric").getFootprint().getPads().size(),
                "the existing package is never changed");
        assertEquals(2, Configuration.get().getPackage("R_0603_1608Metric~2").getFootprint().getPads().size());
        assertEquals("chip-0805", svc.getDb().packageMappings().get("R_0603_1608Metric~2"));
        assertEquals("R_0603_1608Metric", Configuration.get().getPart("R_0603_1608Metric-R_Small").getPackage()
                .getId());

        // (b) a pad-less K and two variants of K in one file
        EdtRunner.call(() -> {
            Configuration.get().addPackage(new Package("K"));
            return null;
        }, 10_000);
        byte[] k = BoardImporterTest.board(BoardImporterTest.outline(20, 10)
                + BoardImporterTest.part2("Lib", "K", "U1", 5, 5, "A", 0.8)
                + BoardImporterTest.part2("Lib", "K", "U2", 10, 5, "A", 0.9) + BoardImporterTest.fid("FID1", 2, 2)
                + BoardImporterTest.fid("FID2", 18, 8));
        JsonObject kSaved = importBoard(k, "k.kicad_pcb", "k", false);
        assertNotNull(byId(kSaved.getAsJsonArray("packages"), "id", "K~2"));
        assertNotNull(byId(kSaved.getAsJsonArray("packages"), "id", "K~3"));

        // (c) SOT-23 + 5-X and SOT-23-5 + X
        byte[] sot = BoardImporterTest.board(BoardImporterTest.outline(20, 10)
                + BoardImporterTest.part2("Package_TO_SOT_SMD", "SOT-23", "U1", 5, 5, "5-X", 0.6)
                + BoardImporterTest.part2("Package_TO_SOT_SMD", "SOT-23-5", "U2", 10, 5, "X", 0.6)
                + BoardImporterTest.fid("FID1", 2, 2) + BoardImporterTest.fid("FID2", 18, 8));
        JsonObject sotSaved = importBoard(sot, "sot.kicad_pcb", "sot", false);
        assertNotNull(byId(sotSaved.getAsJsonArray("parts"), "id", "SOT-23-5-X"));
        assertNotNull(byId(sotSaved.getAsJsonArray("parts"), "id", "SOT-23-5-X~2"));

        // (d) characters OpenPnP cannot store: cleaned, and OpenPnP loads them again
        byte[] dirty = BoardImporterTest.board(BoardImporterTest.outline(20, 10)
                + BoardImporterTest.part2("Lib", "WEIRD\nFP", "R￾9", 5, 5, "10k\u00071%", 0.7)
                + BoardImporterTest.fid("FID1", 2, 2) + BoardImporterTest.fid("FID2", 18, 8));
        JsonObject dpv = preview(dirty, "dirty.kicad_pcb");
        assertTrue(codes(dpv).contains("ID_CLEANED"), dpv.getAsJsonArray("warnings").toString());
        Res dres = saveRes(dirty, "dirty.kicad_pcb", "dirty\u0001 board", false, dpv);
        assertEquals(201, dres.status, dres.toString());
        assertEquals("dirty board", dres.json().get("name").getAsString());
        JsonObject dp = byId(dpv.getAsJsonArray("placements"), "ref", "R9");
        assertEquals("R9", dp.get("id").getAsString());
        assertEquals("WEIRD FP-10k1%", dp.get("partId").getAsString());

        // (e) OpenPnP's packages change between the preview and the save
        byte[] e = BoardImporterTest.board(BoardImporterTest.outline(20, 10)
                + BoardImporterTest.part2("Lib", "NEWPKG", "U1", 5, 5, "A", 0.8) + BoardImporterTest.fid("FID1", 2, 2)
                + BoardImporterTest.fid("FID2", 18, 8));
        JsonObject epv = preview(e, "e.kicad_pcb");
        EdtRunner.call(() -> {
            Configuration.get().addPackage(new Package("NEWPKG"));
            return null;
        }, 10_000);
        JsonObject stale = refused(saveRes(e, "e.kicad_pcb", "e", false, epv), 409, "preview-stale");
        JsonObject fresh = stale.getAsJsonObject("preview");
        assertEquals("NEWPKG~2", byId(fresh.getAsJsonArray("packages"), "baseId", "NEWPKG").get("id").getAsString());
        assertEquals(201, saveRes(e, "e.kicad_pcb", "e", false, fresh).status);

        // OpenPnP starts again from these files: every id is the cleaned one and resolves
        api.stop();
        svc.shutdown();
        stopped = true;
        Configuration.initialize(sim.configDir);
        Configuration.get().load();
        assertNotNull(Configuration.get().getPart("WEIRD FP-10k1%"));
        assertNotNull(Configuration.get().getPackage("WEIRD FP"));
        int boards = 0;
        for (Board b : Configuration.get().getBoards()) {
            if (b.getFile() != null && b.getFile().getPath().contains("instancelabs" + File.separator + "boards")) {
                boards++;
                for (Placement pl : b.getPlacements()) {
                    assertNotNull(pl.getPart(), b.getFile() + " " + pl.getId());
                }
            }
        }
        assertEquals(5, boards);
    }

    // ------------------------------------------------------------------------------------------
    // 12: rollback
    // ------------------------------------------------------------------------------------------

    @Test
    public void aFailedSaveChangesNothing() throws Exception {
        byte[] ftp = fixture(FTP);
        File root = boardsRoot();
        for (int step : new int[] {3, 5, 7, 8}) {
            Set<String> packages = ids(Configuration.get().getPackages());
            List<Board> before = new ArrayList<>(Configuration.get().getBoards());
            JsonObject pv = preview(ftp, "ftp.kicad_pcb");
            BoardImporter.failAfterStep = step;
            JsonObject failed = refused(saveRes(ftp, "ftp.kicad_pcb", "ftp", false, pv), 500, "internal");
            BoardImporter.failAfterStep = 0;
            assertTrue(failed.get("reason").getAsString().startsWith("Could not save the board: test: the save fails "
                    + "after step " + step), failed.toString());
            assertTrue(failed.get("reason").getAsString().endsWith("Nothing was changed."));
            assertEquals(packages, ids(Configuration.get().getPackages()), "step " + step);
            assertNull(Configuration.get().getPart("R_0603_1608Metric-R_Small"), "step " + step);
            assertEquals(before, Configuration.get().getBoards(), "step " + step);
            assertEquals(new ArrayList<>(), listing(new File(root, "ftp")), "step " + step);
            assertNoStaging(root);
            File packagesXml = new File(sim.configDir, "packages.xml");
            assertTrue(!packagesXml.exists()
                    || !text(Files.readAllBytes(packagesXml.toPath())).contains("R_0603_1608Metric"), "step " + step);
        }
        // a replace that fails puts the old board back, file for file
        JsonObject first = importBoard(ftp, "ftp.kicad_pcb", "ftp", false);
        File dir = new File(root, "ftp");
        Map<String, String> files = contents(dir);
        Board listed = Configuration.get().getBoard(new File(first.get("file").getAsString()));
        for (int step : new int[] {5, 7, 8}) {
            JsonObject pv = preview(ftpR1Moved(), "ftp.kicad_pcb");
            BoardImporter.failAfterStep = step;
            refused(saveRes(ftpR1Moved(), "ftp.kicad_pcb", "ftp", true, pv), 500, "internal");
            BoardImporter.failAfterStep = 0;
            assertEquals(files, contents(dir), "step " + step);
            assertNoStaging(root);
            Board now = null;
            for (Board b : Configuration.get().getBoards()) {
                if (b.getFile().equals(listed.getFile())) {
                    now = b;
                }
            }
            assertTrue(now == listed, "step " + step + ": the board OpenPnP had is listed again");
            assertEquals(37.499966, now.getPlacements().get("R1").getLocation().getX(), 1e-9);
        }
    }

    static Set<String> ids(List<Package> l) {
        Set<String> s = new HashSet<>();
        for (Package p : l) {
            s.add(p.getId());
        }
        return s;
    }

    static void assertNoStaging(File root) {
        String[] names = root.list();
        for (String n : names == null ? new String[0] : names) {
            assertFalse(n.startsWith(".staging-"), n);
        }
    }

    // ------------------------------------------------------------------------------------------
    // 13: attach the drawing to the bench's ftp.board.xml
    // ------------------------------------------------------------------------------------------

    @Test
    public void attachGivesTheBenchBoardItsDrawingAndChangesNothingElse() throws Exception {
        File xml = new File(sim.configDir, "ftp.board.xml");
        Files.write(xml.toPath(), fixture("ftp.board.xml"));
        EdtRunner.call(() -> {
            Configuration c = Configuration.get();
            String[][] parts = {{"R_0603_1608Metric-R_Small", "R_0603_1608Metric"},
                    {"LED_0603_1608Metric-LED_Small", "LED_0603_1608Metric"},
                    {"Fiducial_1mm_Mask3mm-Fiducial", "Fiducial_1mm_Mask3mm"}};
            for (String[] p : parts) {
                Package pkg = new Package(p[1]);
                c.addPackage(pkg);
                Part part = new Part(p[0]);
                part.setPackage(pkg);
                c.addPart(part);
            }
            c.getBoard(xml);
            return null;
        }, 10_000);
        ok(send("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\"}".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/json"));
        String path = xml.getCanonicalPath();
        JsonObject before = plan(path);
        assertEquals("fallback", before.getAsJsonObject("board").getAsJsonObject("art").get("kind").getAsString());
        byte[] packagesXml = readIfThere(new File(sim.configDir, "packages.xml"));
        byte[] partsXml = readIfThere(new File(sim.configDir, "parts.xml"));

        byte[] ftp = fixture(FTP);
        JsonObject pv = preview(ftp, "ftp.kicad_pcb");
        JsonArray attach = pv.getAsJsonArray("attach");
        assertEquals(1, attach.size(), attach.toString());
        JsonObject cand = attach.get(0).getAsJsonObject();
        assertEquals(path, cand.get("file").getAsString());
        assertEquals(0.3136, cand.get("maxOffsetMm").getAsDouble(), 1e-3);
        assertEquals("FID1", cand.getAsJsonArray("offsets").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(1, cand.getAsJsonArray("offsets").size());

        Res res = upload("/api/boards/import/attach?file=ftp.kicad_pcb&board=" + enc(path) + "&sha256="
                + pv.getAsJsonObject("file").get("sha256").getAsString(), ftp);
        assertEquals(201, res.status, res.toString());
        JsonObject at = res.json();
        assertEquals("ftp", at.get("slug").getAsString());
        String artId = at.getAsJsonObject("art").get("id").getAsString();
        JsonObject listed = byId(at.getAsJsonObject("boards").getAsJsonArray("files"), "file", path);
        assertEquals("kicad", listed.getAsJsonObject("art").get("kind").getAsString());
        assertEquals(artId, listed.getAsJsonObject("art").get("id").getAsString());
        assertEquals("attach", listed.getAsJsonObject("imported").get("how").getAsString());
        assertEquals(Arrays.asList("ftp.art.json", "ftp.kicad_pcb"), listing(new File(boardsRoot(), "ftp")));

        JsonObject after = plan(path);
        assertEquals("kicad", after.getAsJsonObject("board").getAsJsonObject("art").get("kind").getAsString());
        assertEquals(artId, after.getAsJsonObject("board").getAsJsonObject("art").get("id").getAsString());
        JsonArray p0 = before.getAsJsonArray("pads");
        JsonArray p1 = after.getAsJsonArray("pads");
        assertEquals(160, p1.size());
        for (int i = 0; i < p0.size(); i++) {
            JsonObject a = p0.get(i).getAsJsonObject();
            JsonObject b = p1.get(i).getAsJsonObject();
            assertEquals(a.get("padId").getAsString(), b.get("padId").getAsString());
            assertEquals(a.get("x").getAsDouble(), b.get("x").getAsDouble(), 0.0);
            assertEquals(a.get("y").getAsDouble(), b.get("y").getAsDouble(), 0.0);
        }
        assertArrayEquals(packagesXml, readIfThere(new File(sim.configDir, "packages.xml")));
        assertArrayEquals(partsXml, readIfThere(new File(sim.configDir, "parts.xml")));
        // a moved placement no longer matches
        byte[] moved = ftpR1Moved();
        JsonObject pvMoved = preview(moved, "ftp.kicad_pcb");
        assertEquals(0, pvMoved.getAsJsonArray("attach").size());
        JsonObject no = refused(upload("/api/boards/import/attach?file=ftp.kicad_pcb&board=" + enc(path)
                + "&replace=1&sha256=" + pvMoved.getAsJsonObject("file").get("sha256").getAsString(), moved), 409,
                "refused");
        assertEquals("The board file no longer matches this KiCad file (R1 moved 1.0 mm): upload it as a new board "
                + "instead.", no.get("reason").getAsString());
        JsonObject notListed = refused(upload("/api/boards/import/attach?file=ftp.kicad_pcb&board=" + enc(
                new File(sim.configDir, "nope.board.xml").getAbsolutePath()) + "&sha256="
                + pv.getAsJsonObject("file").get("sha256").getAsString(), ftp), 409, "refused");
        assertTrue(notListed.get("reason").getAsString().endsWith("is not in OpenPnP's board list."));
    }

    static byte[] readIfThere(File f) throws Exception {
        return f.isFile() ? Files.readAllBytes(f.toPath()) : null;
    }

    // ------------------------------------------------------------------------------------------
    // 14: the board list while a save runs
    // ------------------------------------------------------------------------------------------

    @Test
    public void theBoardListCanBeReadWhileASaveRuns() throws Exception {
        byte[] ftp = fixture(FTP);
        JsonObject pv = preview(ftp, "ftp.kicad_pcb");
        AtomicInteger good = new AtomicInteger();
        AtomicReference<String> bad = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                try {
                    Res r = get("/api/boards");
                    if (r.status == 200) {
                        good.incrementAndGet();
                    }
                    else {
                        bad.compareAndSet(null, r.toString());
                    }
                }
                catch (Exception e) {
                    bad.compareAndSet(null, e.toString());
                }
            }
        });
        reader.start();
        assertEquals(201, saveRes(ftp, "ftp.kicad_pcb", "ftp", false, pv).status);
        reader.join(120_000);
        assertNull(bad.get(), bad.get());
        assertEquals(200, good.get());
    }

    // ------------------------------------------------------------------------------------------
    // 15: static files; the other fixtures end to end
    // ------------------------------------------------------------------------------------------

    @Test
    public void theBoardDrawingScriptsAreServed() throws Exception {
        String index = get("/").body;
        for (String f : new String[] {"board.css", "boardart.js", "pathpreview.js", "padinspect.js", "boardflow.js",
                "boardimport.js", "boardimport.css"}) {
            assertTrue(index.contains("/static/" + f), "index.html loads " + f);
            Res r = get("/static/" + f);
            assertEquals(200, r.status, f);
            assertFalse(r.body.isEmpty(), f);
        }
        // the board-import glue loads after the drawing it builds on, and before app.js boots (the Run and Records
        // hooks read IL.boardFlow from the first render on)
        assertTrue(index.indexOf("/static/boardview.js") < index.indexOf("/static/boardflow.js"), "boardview.js first");
        assertTrue(index.indexOf("/static/boardimport.js") < index.indexOf("/static/app.js"), "before app.js");
    }

    @Test
    public void nanoAndMoboFromUploadToPlan() throws Exception {
        // nano (KiCad 6, CRLF): FD1/FD2 by the footprint name rule, IC3's paste polygon, connectors without a type
        byte[] nano = fixture("NanoV3.3.kicad_pcb.gz");
        JsonObject pv = preview(nano, "NanoV3.3.kicad_pcb");
        assertEquals(6, pv.getAsJsonObject("kicad").get("major").getAsInt());
        assertEquals(8, pv.getAsJsonObject("counts").get("placements").getAsInt());
        assertEquals(2, pv.getAsJsonObject("counts").get("fiducials").getAsInt());
        assertEquals(20, pv.getAsJsonObject("skipped").getAsJsonArray("bottom").size());
        Set<String> codes = codes(pv);
        // with the engine the profile check's NEEDS_PROFILE replaces NO_PAD_TYPE (PROFILE_CALIBRATION_DESIGN.md §12.6)
        assertTrue(codes.containsAll(Arrays.asList("PASTE_POLY", "PAD_NAME_EMPTY", "NEEDS_PROFILE", "BOTTOM_SKIPPED")),
                codes.toString());
        assertFalse(codes.contains("NO_PAD_TYPE"), codes.toString());
        int blocked = 0;
        for (JsonElement w : pv.getAsJsonArray("warnings")) {
            JsonObject o = w.getAsJsonObject();
            if ("NEEDS_PROFILE".equals(o.get("code").getAsString())) {
                assertEquals(1, o.get("group").getAsInt());
                assertTrue(o.get("text").getAsString().contains("SW_SPST_3.9x2.9mm (no pad type)"), o.toString());
                blocked += o.getAsJsonArray("refs").size();
            }
        }
        assertEquals(8, blocked, "4 LEDs below the gate, J3, SW1, Y1 and IC3");
        JsonObject saved = importBoard(nano, "NanoV3.3.kicad_pcb", "Arduino Nano V3.3", false);
        assertEquals("arduino-nano-v3-3", saved.get("slug").getAsString());
        JsonObject plan = plan(saved.get("file").getAsString());
        assertEquals(55, plan.getAsJsonArray("pads").size());
        assertEquals(saved.getAsJsonObject("art").get("id").getAsString(),
                plan.getAsJsonObject("board").getAsJsonObject("art").get("id").getAsString());
        assertEquals(2, plan.getAsJsonObject("board").getAsJsonArray("fiducials").size());

        // mobo (KiCad 9): U2 do-not-place (no paste by default), its windows, the DNP checkbox
        byte[] mobo = fixture("mobo.kicad_pcb.gz");
        JsonObject mpv = preview(mobo, "mobo.kicad_pcb");
        assertEquals(54, mpv.getAsJsonObject("counts").get("placements").getAsInt());
        assertEquals(3, mpv.getAsJsonObject("counts").get("fiducials").getAsInt());
        assertTrue(codes(mpv).contains("DNP_SKIPPED"), codes(mpv).toString());
        assertFalse(mpv.get("pasteDnp").getAsBoolean());
        JsonObject withDnp = ok(upload("/api/boards/import?file=mobo.kicad_pcb&pasteDnp=1", mobo));
        assertTrue(withDnp.get("pasteDnp").getAsBoolean());
        assertEquals(55, withDnp.getAsJsonObject("counts").get("placements").getAsInt());
        assertTrue(codes(withDnp).containsAll(Arrays.asList("DNP_STILL_PASTED", "QFN_WINDOWS")));
        assertNotEquals(mpv.get("decisions").getAsString(), withDnp.get("decisions").getAsString());
        Res mres = upload("/api/boards/import/save?file=mobo.kicad_pcb&name=mobo&pasteDnp=1&sha256="
                + withDnp.getAsJsonObject("file").get("sha256").getAsString() + "&decisions="
                + withDnp.get("decisions").getAsString(), mobo);
        assertEquals(201, mres.status, mres.toString());
        JsonObject mplan = plan(mres.json().get("file").getAsString());
        assertEquals(261, mplan.getAsJsonArray("pads").size());
        assertEquals(mres.json().getAsJsonObject("art").get("id").getAsString(),
                mplan.getAsJsonObject("board").getAsJsonObject("art").get("id").getAsString());
        JsonObject u2 = byId(mplan.getAsJsonArray("pads"), "padId", "U2.33~1");
        assertNotNull(u2, "the QFN windows are pads of their own");
    }

    // ------------------------------------------------------------------------------------------
    // board runs (as ApiServerSimTest)
    // ------------------------------------------------------------------------------------------

    void setPlaces() throws Exception {
        ok(send("PUT", "/api/locations/waste", "{\"x\":80,\"y\":40,\"z\":5.5}".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/json"));
        ok(send("PUT", "/api/locations/blank", "{\"x\":90,\"y\":40,\"z\":5.5}".getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/json"));
        ok(postJson("/api/needle/anchor", "{\"needleChangedAtMs\":null,\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}"));
        ok(postJson("/api/needle/offset", "{\"needleChangedAtMs\":null,\"offsetMm\":-2.59}"));
        ok(send("PUT", "/api/surfaces/blank", "{\"points\":[[60,30,5.5],[200,30,5.5],[60,50,5.5],[200,50,5.5]]}"
                .getBytes(StandardCharsets.UTF_8), "Content-Type", "application/json"));
        ok(send("PUT", "/api/surfaces/board", ("{" + BOARD_POINTS + "}").getBytes(StandardCharsets.UTF_8),
                "Content-Type", "application/json"));
        ok(send("PUT", "/api/config", "{\"vacuumProbe\":{\"vacuumSettleMs\":300,\"readDelayMs\":0}}"
                .getBytes(StandardCharsets.UTF_8), "Content-Type", "application/json"));
    }

    static final String BOARD_POINTS = "\"points\":[[98,78,5.5],[130,78,5.5],[98,95,5.5],[130,95,5.5]]";

    void setUpBoardRuns() throws Exception {
        setPlaces();
        ok(send("PUT", "/api/config", ("{\"purgeRow\":{\"x0Mm\":80,\"y0Mm\":47,\"pitchXMm\":2.5,\"spots\":48},"
                + "\"cleanPadCheck\":{\"enabled\":false}}").getBytes(StandardCharsets.UTF_8), "Content-Type",
                "application/json"));
        ok(postJson("/api/screw/start-angle", "{}"));
    }

    File writeBoard(double[][] pos) throws Exception {
        EdtRunner.call(() -> {
            Package pkg = new Package("R_0603_API");
            Configuration.get().addPackage(pkg);
            Part part = new Part("R0603-API-TEST");
            part.setPackage(pkg);
            Configuration.get().addPart(part);
            return null;
        }, 10_000);
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

    JsonObject planTied(File board) throws Exception {
        JsonObject plan = ok(postJson("/api/plan", planBody(board.getAbsolutePath(), 100, 80, 5.5, 0)));
        String id = plan.get("planId").getAsString();
        ok(send("PUT", "/api/surfaces/board", ("{" + BOARD_POINTS + ",\"planId\":\"" + id + "\"}")
                .getBytes(StandardCharsets.UTF_8), "Content-Type", "application/json"));
        return ok(get("/api/plan/" + id));
    }
}
