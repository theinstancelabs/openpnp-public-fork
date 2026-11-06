package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.BoardRegistration;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.FlatVacuum;
import org.openpnp.instancelabs.dispense.OpenPnpRawMapper;
import org.openpnp.instancelabs.dispense.RawPoint;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;
import org.openpnp.model.Package;
import org.openpnp.model.Part;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-76 on the simulated controller (OpenPnP's GcodeDriver over TCP to RecordingGcodeServer, the real engine and API):
 * a registered board's ledger as the engine's runs and the passthrough's dots write it, the budget before anything
 * moves, the plan need, Board wiped (under a waiting agent's lock too), Process's file and an engine restart.
 */
public class PadLedgerSimTest {
    static final String UI = "ui";
    /** The board's seat (its registration: a plain shift), N1 contact Z of its top; the needle offset is -2.59. */
    static final double BX = 100, BY = 80, TOP = 5.5, OFFSET = -2.59;

    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;
    File board;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine();
        FlatVacuum.install(sim, FlatVacuum.scene(TOP, null, 0));
        svc = DispenseService.start(Configuration.get());
        assertTrue(svc.isAvailable(), String.valueOf(svc.getUnavailableReason()));
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ok(call("PUT", "/api/locations/waste", "{\"x\":80,\"y\":40,\"z\":5.5}", null));
        ok(call("PUT", "/api/locations/blank", "{\"x\":90,\"y\":40,\"z\":5.5}", null));
        ok(post("/api/needle/anchor", "{\"needleChangedAtMs\":null,\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}",
                null));
        ok(post("/api/needle/offset", "{\"needleChangedAtMs\":null,\"offsetMm\":" + OFFSET + "}", null));
        ok(call("PUT", "/api/surfaces/blank", "{\"points\":[[60,30,5.5],[200,30,5.5],[60,50,5.5],[200,50,5.5]]}",
                null));
        ok(call("PUT", "/api/surfaces/board", "{" + BOARD_POINTS + "}", null));
        ok(call("PUT", "/api/config", "{\"vacuumProbe\":{\"vacuumSettleMs\":300,\"readDelayMs\":0},\"purgeRow\":"
                + "{\"x0Mm\":80,\"y0Mm\":47,\"pitchXMm\":2.5,\"spots\":48},\"cleanPadCheck\":{\"enabled\":false}}", null));
        ok(post("/api/screw/start-angle", "{}", null));
        ok(call("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\"}", null));
        board = writeBoard();
        register(board);
    }

    @AfterEach
    public void after() throws Exception {
        api.stop();
        svc.shutdown();
        sim.close();
    }

    /** "ledger.board.xml", 20 x 10 mm: three 0603 resistors (six pads), as ApiServerSimTest's board. */
    File writeBoard() throws Exception {
        Package pkg = new Package("R_0603_1608Metric");
        Configuration.get().addPackage(pkg);
        Part part = new Part("R0603-LEDGER-TEST");
        part.setPackage(pkg);
        Configuration.get().addPart(part);
        StringBuilder sb = new StringBuilder("<openpnp-board version=\"1.1\" name=\"ledger.board.xml\">\n"
                + "<dimensions units=\"Millimeters\" x=\"20.0\" y=\"10.0\" z=\"0.0\" rotation=\"0.0\"/>\n<placements>\n");
        double[][] pos = {{2, 2, 0}, {8, 2, 90}, {14, 6, 0}};
        for (int i = 0; i < pos.length; i++) {
            sb.append("<placement version=\"1.4\" side=\"Top\" id=\"R").append(i + 1)
                    .append("\" part-id=\"R0603-LEDGER-TEST\" type=\"Placement\" enabled=\"true\" rank=\"0\">\n")
                    .append("<location units=\"Millimeters\" x=\"").append(pos[i][0]).append("\" y=\"")
                    .append(pos[i][1]).append("\" z=\"0.0\" rotation=\"").append(pos[i][2]).append("\"/>\n")
                    .append("<error-handling>Default</error-handling>\n</placement>\n");
        }
        sb.append("</placements>\n<fiducials/>\n</openpnp-board>\n");
        File f = new File(sim.configDir, "ledger.board.xml");
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** A registration of the board at its seat, as Register board stores one (here a plain shift). */
    void register(File f) throws Exception {
        BoardRegistration r = new BoardRegistration();
        r.id = "reg-ledger";
        r.boardKey = BoardRegistration.fileKey(f);
        r.boardName = "ledger.board.xml";
        r.nominal = new BoardRegistration.Nominal();
        r.nominal.x = BX;
        r.nominal.y = BY;
        r.nominal.z = TOP;
        r.nominal.side = "Top";
        r.setTransform(AffineTransform.getTranslateInstance(BX, BY));
        r.method = "affine-3";
        r.atMs = System.currentTimeMillis();
        r.simulated = true;
        DispenseConfig c = svc.getConfig();
        c.putBoardRegistration(r);
        svc.updateConfig(c);
    }

    /** The board's probe points (its seat, 20 x 10 mm, and around). */
    static final String BOARD_POINTS = "\"points\":[[98,78,5.5],[122,78,5.5],[98,92,5.5],[122,92,5.5]]";

    /** The board planned at its seat with the probed surface, then the surface tied to it: the plan's id. */
    String plan() throws Exception {
        JsonObject p = ok(post("/api/plan", "{\"source\":\"file\",\"file\":\"" + board.getAbsolutePath()
                + "\",\"x\":" + BX + ",\"y\":" + BY + ",\"z\":" + TOP + ",\"rotation\":0,\"registration\":\"none\"}", null));
        String id = p.get("planId").getAsString();
        ok(call("PUT", "/api/surfaces/board", "{" + BOARD_POINTS + ",\"planId\":\"" + id + "\"}", null));
        return id;
    }

    // ------------------------------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------------------------------

    static final class Res {
        final int status;
        final JsonObject json;

        Res(HttpResponse<String> r) {
            status = r.statusCode();
            JsonElement e = new JsonParser().parse(r.body().isEmpty() ? "{}" : r.body());
            json = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        }

        @Override
        public String toString() {
            return status + " " + json;
        }
    }

    /** {@code who}: "ui" (the web UI), a lock token (an agent), or null (anonymous). */
    Res call(String method, String path, String body, String who) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120));
        if (UI.equals(who)) {
            b.header(MachineLock.UI_HEADER, "ui");
        }
        else if (who != null) {
            b.header(MachineLock.TOKEN_HEADER, who);
        }
        b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return new Res(http.send(b.build(), HttpResponse.BodyHandlers.ofString()));
    }

    Res post(String path, String body, String who) throws Exception {
        return call("POST", path, body, who);
    }

    static JsonObject ok(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.json;
    }

    JsonObject pads() throws Exception {
        return ok(call("GET", "/api/boards/ledger/pads", null, null));
    }

    JsonArray ledgers() throws Exception {
        return ok(call("GET", "/api/status", null, null)).getAsJsonArray("padLedgers");
    }

    static JsonObject pad(JsonObject pads, String id) {
        for (JsonElement e : pads.getAsJsonArray("pads")) {
            if (id.equals(e.getAsJsonObject().get("id").getAsString())) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no pad " + id + " in " + pads);
    }

    /** Marks pads used as dots on them would, at their centres (the ledger's own call). */
    void paste(String... ids) throws Exception {
        JsonObject p = pads();
        for (String id : ids) {
            JsonObject x = pad(p, id);
            svc.getPadLedger().dot(Collections.singletonList(new double[] {x.get("x").getAsDouble(),
                    x.get("y").getAsDouble(), TOP}), System.currentTimeMillis(), "test", "dot", null);
        }
    }

    /** The newest events' messages of {@code code} (GET /api/events answers a list, newest first). */
    List<String> events(String code) throws Exception {
        HttpRequest q = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + "/api/events?limit=50"))
                .timeout(Duration.ofSeconds(30)).build();
        List<String> l = new ArrayList<>();
        for (JsonElement e : new JsonParser().parse(http.send(q, HttpResponse.BodyHandlers.ofString()).body())
                .getAsJsonArray()) {
            if (code.equals(e.getAsJsonObject().get("code").getAsString())) {
                l.add(e.getAsJsonObject().get("message").getAsString());
            }
        }
        return l;
    }

    String acquire() throws Exception {
        return ok(post("/api/lock/acquire", "{\"who\":\"process\",\"what\":\"pad tests\",\"thread\":\"mcp:test\"}",
                null)).get("token").getAsString();
    }

    /** The needle's raw X/Y/Z for its tip at (x, y) and {@code above} mm over the board's top. */
    RawPoint raw(double x, double y, double above) throws Exception {
        return new OpenPnpRawMapper(sim.machine, sim.n2, "b").toRaw(x, y, TOP + OFFSET + above);
    }

    String move(RawPoint p) {
        return String.format(Locale.ROOT, "G1 X%.4f Y%.4f Z%.4f F3000", p.x, p.y, p.z);
    }

    Res gcode(String token, String... lines) throws Exception {
        JsonArray a = new JsonArray();
        for (String l : lines) {
            a.add(new com.google.gson.JsonPrimitive(l));
        }
        JsonObject b = new JsonObject();
        b.add("lines", a);
        return post("/api/gcode", b.toString(), token);
    }

    // ------------------------------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------------------------------

    @Test
    public void aRunMarksItsPadsByXyAndAWipeClearsThem() throws Exception {
        assertEquals(0, ledgers().size(), "no ledger before the board's first record: " + ledgers());
        String id = plan();
        JsonObject pl = ok(call("GET", "/api/plan/" + id, null, null)).getAsJsonObject("padLedger");
        assertEquals("ledger", pl.get("board").getAsString(), pl.toString());
        assertEquals(6, pl.get("needed").getAsInt());
        assertEquals(6, pl.get("clean").getAsInt());
        JsonObject run = ok(post("/api/run", "{\"planId\":\"" + id + "\",\"wait\":true}", null));
        JsonObject res = run.getAsJsonObject("result");
        assertEquals("OK", res.get("status").getAsString(), run.toString());
        JsonObject p = pads();
        assertEquals(0, p.get("clean").getAsInt(), p.toString());
        assertEquals(6, p.get("total").getAsInt());
        for (JsonElement e : p.getAsJsonArray("pads")) {
            JsonObject x = e.getAsJsonObject();
            assertEquals("used", x.get("state").getAsString(), x.toString());
            assertEquals(1, x.get("layers").getAsInt(), "one dot per pad, its turn back is no dot: " + x);
            assertEquals("dot", x.get("kind").getAsString());
            assertEquals(res.get("runId").getAsString(), x.get("run").getAsString());
        }
        assertEquals(0, p.getAsJsonArray("offPad").size(), "the purge row is no board's: " + p.get("offPad"));
        JsonObject view = p.getAsJsonObject("view");
        assertEquals(20, view.get("widthMm").getAsDouble(), 1e-9);
        assertEquals(BX, view.getAsJsonArray("toMachine").get(4).getAsDouble(), 1e-9);
        JsonObject l = ledgers().get(0).getAsJsonObject();
        assertEquals("ledger", l.get("board").getAsString());
        assertEquals(0, l.get("clean").getAsInt());
        assertEquals("R", l.getAsJsonArray("sets").get(0).getAsJsonObject().get("set").getAsString());

        JsonObject w = ok(post("/api/boards/ledger/wiped", "{}", UI));
        assertTrue(w.get("check").isJsonNull(), "the camera's re-check is stage 2: " + w);
        assertEquals(6, w.get("clean").getAsInt(), w.toString());
        assertTrue(w.get("wipedAtMs").getAsLong() > 0);
        assertEquals("clean", pad(pads(), "R1.1").get("state").getAsString());
    }

    @Test
    public void aRunNeedingMoreThanTheCleanPadsIsRefusedBeforeAnyLine() throws Exception {
        String id = plan();
        paste("R1.1", "R1.2", "R2.1", "R2.2");
        JsonObject pl = ok(call("GET", "/api/plan/" + id, null, null)).getAsJsonObject("padLedger");
        assertEquals(2, pl.get("clean").getAsInt(), pl.toString());
        sim.server.mark();
        Res r = post("/api/run", "{\"planId\":\"" + id + "\",\"wait\":true}", null);
        assertEquals(409, r.status, r.toString());
        assertEquals("clean-pads", r.json.get("error").getAsString());
        assertEquals("ledger", r.json.get("board").getAsString());
        assertEquals(2, r.json.get("clean").getAsInt());
        assertEquals(6, r.json.get("needed").getAsInt());
        assertEquals("Wipe the board first: 2 clean, this run needs 6.", r.json.get("reason").getAsString());
        assertEquals(Collections.emptyList(), sim.server.sinceMark(), "nothing sent");
        assertTrue(ok(call("GET", "/api/status", null, null)).get("run").isJsonNull(), "no task started");

        JsonObject run = ok(post("/api/run", "{\"planId\":\"" + id + "\",\"wait\":true,\"force\":true}", null));
        assertEquals("OK", run.getAsJsonObject("result").get("status").getAsString(), run.toString());
        assertEquals(2, pad(pads(), "R1.1").get("layers").getAsInt(), "forced: on record, and its dots counted");
        assertTrue(events("PADS").get(0).startsWith("forced past the clean-pad budget (run): Wipe the board first: 2"),
                "a forced run is on record: " + events("PADS"));
    }

    @Test
    public void passthroughDotsOnPadsCount() throws Exception {
        String token = acquire();
        JsonObject p = pads();
        JsonObject r11 = pad(p, "R1.1");
        double x = r11.get("x").getAsDouble(), y = r11.get("y").getAsDouble();
        RawPoint on = raw(x, y, 0.1), air = raw(x, y, 2), off = raw(BX + 10, BY + 8, 0.1);
        ok(gcode(token, move(raw(x, y, 5)), move(on), "M106 P3 S255", "G1 B90 F600", "G1 B180 F600", "M107 P3", "M400"));
        JsonObject q = pad(pads(), "R1.1");
        assertEquals("used", q.get("state").getAsString(), q.toString());
        assertEquals(1, q.get("layers").getAsInt(), "two turns at one spot are one dot");
        assertEquals("Process", q.get("by").getAsString());
        assertEquals(5, pads().get("clean").getAsInt());
        ok(gcode(token, move(air), "M106 P3 S255", "G1 B270 F600", "M107 P3", "M400"));
        assertEquals(1, pad(pads(), "R1.1").get("layers").getAsInt(), "an air turn 2 mm up marks nothing");
        ok(gcode(token, move(raw(BX + 10, BY + 8, 5)), move(off), "M106 P3 S255", "G1 B360 F600", "M107 P3", "M400"));
        JsonArray offPad = pads().getAsJsonArray("offPad");
        assertEquals(1, offPad.size(), offPad.toString());
        assertEquals(BX + 10, offPad.get(0).getAsJsonObject().get("x").getAsDouble(), 0.01);
        ok(gcode(token, move(raw(x, y, 5)), move(on), "G1 B450 F600", "M400"));
        assertEquals(1, pad(pads(), "R1.1").get("layers").getAsInt(), "the valve is closed: no dot");
    }

    @Test
    public void thePlanNeedIsShownAndRefusesThePlansFirstRun() throws Exception {
        String id = plan();
        ok(post("/api/boards/ledger/need", "{\"pads\":8,\"by\":\"Process\",\"what\":\"the gap ladder\"}", null));
        JsonObject need = ledgers().get(0).getAsJsonObject().getAsJsonObject("need");
        assertEquals(8, need.get("pads").getAsInt(), need.toString());
        assertEquals("Process", need.get("by").getAsString());
        assertEquals("the gap ladder", need.get("what").getAsString());
        sim.server.mark();
        Res r = post("/api/run", "{\"planId\":\"" + id + "\"}", null);
        assertEquals(409, r.status, r.toString());
        assertEquals("clean-pads", r.json.get("error").getAsString());
        assertEquals(6, r.json.get("clean").getAsInt());
        assertEquals(8, r.json.get("needed").getAsInt());
        assertEquals("Wipe the board first: 6 clean, the declared plan needs 8 (this run 6).",
                r.json.get("reason").getAsString());
        assertEquals(Collections.emptyList(), sim.server.sinceMark(), "nothing sent");
        assertTrue(ok(call("GET", "/api/status", null, null)).get("run").isJsonNull(), "no task started");
        ok(post("/api/boards/ledger/need", "{\"pads\":0}", null));
        assertTrue(ledgers().get(0).getAsJsonObject().get("need").isJsonNull(), "{pads: 0} clears it");
        ok(post("/api/boards/ledger/need", "{\"pads\":6,\"by\":\"Process\"}", null));
        JsonObject run = ok(post("/api/run", "{\"planId\":\"" + id + "\",\"wait\":true}", null));
        assertEquals("OK", run.getAsJsonObject("result").get("status").getAsString(), run.toString());
        assertTrue(ledgers().get(0).getAsJsonObject().get("need").isJsonNull(), "the plan's first run started");
    }

    @Test
    public void theUsersWipePassesUnderAWaitingAgentsLock() throws Exception {
        String id = plan();
        paste("R1.1", "R1.2", "R2.1");
        String token = acquire();
        Res r = post("/api/run", "{\"planId\":\"" + id + "\"}", token);
        assertEquals(409, r.status, r.toString());
        JsonObject w = ledgers().get(0).getAsJsonObject().getAsJsonObject("waiting");
        assertEquals("Process", w.get("by").getAsString(), w.toString());
        assertEquals("run", w.get("what").getAsString());
        assertEquals(3, w.get("clean").getAsInt());
        assertEquals(6, w.get("needed").getAsInt());
        // a later guarded call of the agent ends the exception
        ok(post("/api/purge-row/reset", null, token));
        assertEquals(409, post("/api/boards/ledger/wiped", "{}", UI).status, "the agent works again: locked");
        assertEquals(409, post("/api/run", "{\"planId\":\"" + id + "\"}", token).status);
        JsonObject ok = ok(post("/api/boards/ledger/wiped", "{}", UI));
        assertEquals(6, ok.get("clean").getAsInt(), ok.toString());
        JsonObject holder = ok(call("GET", "/api/lock", null, null)).getAsJsonObject("holder");
        assertEquals("agent", holder.get("kind").getAsString(), "the lock stays the agent's: " + holder);
        assertTrue(ledgers().get(0).getAsJsonObject().get("waiting").isJsonNull(), "the wait ends with the wipe");
        assertEquals("board ledger wiped by the web UI's user: every pad but the fixed ones", events("PADS").get(0));
    }

    @Test
    public void processFileIsImportedAndTheCountsSurviveAnEngineRestart() throws Exception {
        pads(); // the board's pads known
        ok(post("/api/boards/ledger/pads/import", "{\"board\":\"ledger\",\"wipes\":[{\"at\":\"2026-11-03T22:08:00\","
                + "\"note\":\"user wipe\"}],\"fixed\":{\"R1.1\":\"old solder\"},\"pasted\":{\"R3.2\":{\"at\":"
                + "\"2026-11-03T22:17:45\",\"run\":\"p34/b25\",\"kind\":\"throwaway\"}}}", null));
        paste("R2.1");
        JsonObject p = pads();
        assertEquals("fixed", pad(p, "R1.1").get("state").getAsString());
        assertEquals("old solder", pad(p, "R1.1").get("note").getAsString());
        assertEquals("throwaway", pad(p, "R3.2").get("kind").getAsString());
        assertEquals(3, p.get("clean").getAsInt(), p.toString());
        svc = DispenseService.start(Configuration.get()); // stops the engine and starts it again
        JsonObject again = pads();
        assertEquals(3, again.get("clean").getAsInt(), again.toString());
        assertEquals(p.get("pads"), again.get("pads"));
        assertEquals(p.get("wipedAtMs"), again.get("wipedAtMs"));
        List<String> states = new ArrayList<>();
        for (JsonElement e : again.getAsJsonArray("pads")) {
            states.add(e.getAsJsonObject().get("state").getAsString());
        }
        assertEquals(List.of("fixed", "clean", "used", "clean", "clean", "used"), states);
        Res twice = post("/api/boards/ledger/wiped", "{\"pads\":[\"R9.9\"]}", null);
        assertEquals(400, twice.status, twice.toString());
    }
}
