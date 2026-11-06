package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.board.EdtRunner;
import org.openpnp.instancelabs.dispense.BoardRegistration;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DoseBlocksRequest;
import org.openpnp.instancelabs.dispense.DoseBlocksResult;
import org.openpnp.instancelabs.dispense.FlatVacuum;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Package;
import org.openpnp.model.Part;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-81 on the simulated controller: a paste place that lies on a board on the bed (a registration's board, or an
 * imported tile's outline) is refused wherever a run uses it, naming the place and the board, before a line is sent;
 * the readiness (the status's I13) lists each such place; the pressure hold's park is checked the same way; a board
 * run still pastes its own board, on the outlines its registration knew. A sink (the waste, the towel, a cup) and the
 * hold keep their 6 mm circle and the 2 mm margin off every board (Process, 11-04).
 */
public class PlacesOnBoardsSimTest {
    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;
    int lastStatus;
    String lastBody;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine();
        // N1 touches 5.5 everywhere, as the places are typed in: a run after a home checks Z with one touch
        FlatVacuum.install(sim, FlatVacuum.scene(5.5, null, 0));
        svc = DispenseService.start(Configuration.get());
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        // the waste and the needle frame as the bench has them (I4): Prime may run
        ok("PUT", "/api/locations/waste", "{\"x\":80,\"y\":40,\"z\":5.5}");
        ok("POST", "/api/needle/anchor", "{\"needleChangedAtMs\":null,\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}");
        ok("POST", "/api/needle/offset", "{\"needleChangedAtMs\":null,\"offsetMm\":-2.59}");
    }

    @AfterEach
    public void after() throws Exception {
        api.stop();
        svc.shutdown();
        sim.close();
    }

    JsonObject call(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120));
        if (json != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        lastStatus = r.statusCode();
        lastBody = r.body();
        JsonElement e = new JsonParser().parse(r.body());
        return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    JsonObject ok(String method, String path, String json) throws Exception {
        JsonObject o = call(method, path, json);
        assertTrue(lastStatus >= 200 && lastStatus < 300, lastStatus + " " + lastBody);
        return o;
    }

    /** A straight registration of a board of w x h mm whose corner (0, 0) lies at (x, y); its label in the texts. */
    String register(String name, double x, double y, double w, double h) throws Exception {
        BoardRegistration reg = new BoardRegistration();
        reg.id = UUID.randomUUID().toString();
        reg.boardKey = "file:/br81/" + name;
        reg.boardName = name;
        reg.affine = new double[] {1, 0, 0, 1, x, y};
        reg.boardRectMm = new double[] {0, 0, w, h};
        reg.atMs = System.currentTimeMillis();
        svc.updateConfigAtomically(c -> {
            c.putBoardRegistration(reg);
            return c;
        });
        return "board '" + name + "' (registration " + reg.shortId() + ")";
    }

    String registrationId(String name) {
        return svc.getConfig().boardRegistrations.get("file:/br81/" + name).id;
    }

    JsonObject prime() throws Exception {
        return call("POST", "/api/prime", "{\"rotationDeg\":360,\"wait\":true}");
    }

    boolean switchedOn() {
        return sim.server.sinceMark().stream().anyMatch(l -> l.startsWith("M106"));
    }

    /** The newest journal row of this code, or null. */
    JsonObject event(String code) throws Exception {
        call("GET", "/api/events?limit=20", null);
        for (JsonElement e : new JsonParser().parse(lastBody).getAsJsonArray()) {
            if (code.equals(e.getAsJsonObject().get("code").getAsString())) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    JsonObject i13() throws Exception {
        for (JsonElement e : ok("GET", "/api/status", null).getAsJsonArray("interlocks")) {
            if ("I13".equals(e.getAsJsonObject().get("id").getAsString())) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no I13 in the status");
    }

    @Test
    public void aWasteOnARegisteredBoardRefusesPrimeNamingTheBoard() throws Exception {
        String board = register("w.board.xml", 60, 30, 30, 15); // x 60..90, y 30..45: the waste (80, 40) on it
        sim.server.mark();
        prime();
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("I13: the waste (80.0, 40.0) lies on " + board + ": set it again off the board "
                + "(Machine › Places)"), lastBody);
        assertFalse(switchedOn(), "nothing switched on: " + sim.server.sinceMark());

        // the waste keeps its pile's 6 mm circle and the 2 mm margin off a board: 3 mm off is refused
        ok("PUT", "/api/locations/waste", "{\"x\":93,\"y\":40,\"z\":5.5}");
        prime();
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("I13: the waste (93.0, 40.0) lies within 8.0 mm of " + board
                + " (its 6.0 mm circle and the 2.0 mm margin): set it again off the board (Machine › Places)"), lastBody);
        // 1 mm outside those 8 mm: Prime goes on; 0.5 mm inside them: refused
        ok("PUT", "/api/locations/waste", "{\"x\":99,\"y\":40,\"z\":5.5}");
        JsonObject run = prime().getAsJsonObject("result");
        assertEquals(200, lastStatus, lastBody);
        assertEquals("OK", run.get("status").getAsString(), run.toString());
        ok("PUT", "/api/locations/waste", "{\"x\":97.5,\"y\":40,\"z\":5.5}");
        prime();
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("I13: the waste (97.5, 40.0) lies within 8.0 mm of " + board), lastBody);

        // the board left the bed: forget its registration (a journal row), and Prime goes on
        call("DELETE", "/api/board-registrations/nope", null);
        assertEquals(404, lastStatus, lastBody);
        String id = registrationId("w.board.xml");
        ok("DELETE", "/api/board-registrations/" + id, null);
        assertTrue(svc.getConfig().boardRegistrations.isEmpty());
        JsonObject forgot = event("REG");
        assertTrue(forgot != null && forgot.get("message").getAsString().contains("forgotten") && forgot.get("message")
                .getAsString().contains(id), String.valueOf(forgot));
        run = prime().getAsJsonObject("result");
        assertEquals("OK", run.get("status").getAsString(), lastBody);
    }

    /** The hold's park over the waste on a board is refused the same way, before anything moves or switches on. */
    @Test
    public void theHoldsParkOverAWasteOnABoardIsRefused() throws Exception {
        String board = register("w.board.xml", 60, 30, 30, 15);
        sim.server.mark();
        call("POST", "/api/pressure/start", "{\"park\":\"waste\"}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("I12: the park over 'waste' (80.0, 40.0) is on " + board + ": park over another "
                + "place"), lastBody);
        assertFalse(switchedOn(), "nothing switched on: " + sim.server.sinceMark());
        // 3 mm off the board: the drool's circle would reach it
        ok("PUT", "/api/locations/waste", "{\"x\":93,\"y\":40,\"z\":5.5}");
        call("POST", "/api/pressure/start", "{\"park\":\"waste\"}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("I12: the park over 'waste' (93.0, 40.0) is within 8.0 mm of " + board + ", where "
                + "its drool may reach the board: park over another place"), lastBody);
        assertFalse(switchedOn(), "nothing switched on: " + sim.server.sinceMark());
    }

    /** A test-dot spot on a board, the waste itself off them, refuses Test dot at its start like the others (409). */
    @Test
    public void aTestDotSpotOnABoardIsRefusedAtTheStart() throws Exception {
        String board = register("w.board.xml", 60, 30, 30, 15);
        ok("PUT", "/api/locations/waste", "{\"x\":99,\"y\":40,\"z\":5.5}"); // 9 mm off the board
        ok("PUT", "/api/config", "{\"testDotOffsetXMm\":-8}"); // its spot (91, 40): 1 mm off
        sim.server.mark();
        call("POST", "/api/test-dot", "{\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("Test dot refused by the interlocks: I13: the test dot's spot (91.0, 40.0) "
                + "lies on " + board + ": set the waste again off the board (Machine › Places)"), lastBody);
        assertTrue(sim.server.sinceMark().isEmpty(), "no line at all: " + sim.server.sinceMark());
    }

    @Test
    public void theReadinessListsEachPlaceOnABoard() throws Exception {
        JsonObject ok = i13();
        assertEquals("ok", ok.get("state").getAsString(), ok.toString());
        assertEquals(0, ok.getAsJsonArray("outlines").size(), ok.toString());

        String board = register("r.board.xml", 60, 20, 40, 40); // x 60..100, y 20..60
        ok("PUT", "/api/locations/towel", "{\"x\":150,\"y\":40,\"z\":5.5}");
        ok("PUT", "/api/locations/coupon1", "{\"x\":120,\"y\":40,\"z\":5.5}"); // its 40 x 30 coupon reaches x 100
        // spot 3 (101, 20) lies 1 mm from the board's corner, inside the 2 mm margin; spots 1 and 2 lie off it
        ok("PUT", "/api/config", "{\"purgeRow\":{\"x0Mm\":101,\"y0Mm\":10,\"pitchXMm\":0,\"pitchYMm\":5,\"spots\":8}}");
        JsonObject w = i13();
        assertEquals("warning", w.get("state").getAsString(), w.toString());
        List<String> places = new ArrayList<>();
        for (JsonElement e : w.getAsJsonArray("places")) {
            JsonObject p = e.getAsJsonObject();
            places.add(p.get("place").getAsString() + "/" + p.get("kind").getAsString());
            assertEquals(board, p.get("board").getAsString(), p.toString());
        }
        assertEquals(List.of("waste/place", "purge row/purge-row", "coupon1/coupon"), places, w.toString());
        assertTrue(w.get("text").getAsString().startsWith("3 paste places lie on a board: the waste (80.0, 40.0) lies on "
                + board), w.toString());
        assertTrue(w.toString().contains("the purge row's spot 3 of 8 (101.0, 20.0) lies on " + board + ": set the row "
                + "again off the board (Machine › Places)"), w.toString());
        JsonObject outline = w.getAsJsonArray("outlines").get(0).getAsJsonObject();
        assertEquals(board, outline.get("label").getAsString(), outline.toString());
        assertEquals("registration", outline.get("kind").getAsString(), outline.toString());

        ok("PUT", "/api/locations/waste", "{\"x\":40,\"y\":40,\"z\":5.5}");
        ok("PUT", "/api/locations/coupon1", "{\"x\":125,\"y\":40,\"z\":5.5}");
        ok("PUT", "/api/config", "{\"purgeRow\":{\"x0Mm\":110}}");
        assertEquals("ok", i13().get("state").getAsString(), i13().toString());
    }

    /** A tile of three boards, x 60..120 and y 30..60, the boards A, B, C 16 mm wide with 4 mm gaps. */
    static final String TILE = "{\"label\":\"Nano tile\",\"outline\":[[60,30],[120,30],[120,60],[60,60]],"
            + "\"boards\":[{\"name\":\"A\",\"outline\":[[62,32],[78,32],[78,58],[62,58]]},"
            + "{\"name\":\"B\",\"outline\":[[82,32],[98,32],[98,58],[82,58]]},"
            + "{\"name\":\"C\",\"outline\":[[102,32],[118,32],[118,58],[102,58]]}],"
            + "\"checkPoints\":[[70,45],[90,45],[110,45]],\"source\":\"PlacesOnBoardsSimTest\"}";

    @Test
    public void anImportedTileCountsLikeARegistration() throws Exception {
        ok("PUT", "/api/locations/waste", "{\"x\":80.5,\"y\":40,\"z\":5.5}"); // in the gap between A and B
        JsonObject put = ok("PUT", "/api/tiles/nano/outline", TILE);
        assertEquals("Nano tile", put.getAsJsonObject("tile").get("label").getAsString(), put.toString());
        assertEquals(1, put.getAsJsonArray("placesOnBoards").size(), put.toString());
        sim.server.mark();
        prime();
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("I13: the waste (80.5, 40.0) lies on the Nano tile (board B): set it again off "
                + "the board (Machine › Places)"), lastBody);
        assertFalse(switchedOn(), "nothing switched on: " + sim.server.sinceMark());
        assertEquals("tile", i13().getAsJsonArray("outlines").get(0).getAsJsonObject().get("kind").getAsString());

        // the tiles are the import's alone, not a settings save's
        call("PUT", "/api/config", "{\"tiles\":{}}");
        assertEquals(400, lastStatus, lastBody);

        assertTrue(event("TILE").get("message").getAsString().startsWith("imported 'nano'"), lastBody);
        ok("DELETE", "/api/tiles/nano", null);
        assertTrue(event("TILE").get("message").getAsString().startsWith("removed 'nano'"), lastBody);
        call("DELETE", "/api/tiles/nano", null);
        assertEquals(404, lastStatus, lastBody);
        JsonObject run = prime().getAsJsonObject("result");
        assertEquals("OK", run.get("status").getAsString(), lastBody);
    }

    /** An import that does not hold together is refused whole: nothing is stored. */
    @Test
    public void aTileImportThatDoesNotHoldIsRefused() throws Exception {
        String outside = TILE.replace("[110,45]]", "[130,45]]"); // a check point off the tile: a wrong transform
        String crossing = TILE.replace("[[60,30],[120,30],[120,60],[60,60]]", "[[60,30],[120,60],[120,30],[60,60]]");
        String boardOff = TILE.replace("[[102,32],[118,32],[118,58],[102,58]]", "[[202,32],[218,32],[218,58],[202,58]]");
        for (String body : new String[] {outside, crossing, boardOff}) {
            assertFalse(body.equals(TILE), body);
            call("PUT", "/api/tiles/nano/outline", body);
            assertEquals(400, lastStatus, body + " -> " + lastBody);
        }
        assertTrue(svc.getConfig().tiles.isEmpty(), "nothing stored");
    }

    // ------------------------------------------------------------------------------------------
    // Board runs (the harness of ApiServerSimTest)
    // ------------------------------------------------------------------------------------------

    /** The board's probe points (N1 touches 5.5 there). */
    static final String BOARD_POINTS = "\"points\":[[98,78,5.5],[130,78,5.5],[98,95,5.5],[130,95,5.5]]";

    /** The blank, its surface and the board's, the purge row on the blank, I11 confirmed, no clean-pad camera. */
    void setUpBoardRuns() throws Exception {
        ok("PUT", "/api/locations/blank", "{\"x\":90,\"y\":40,\"z\":5.5}");
        ok("PUT", "/api/surfaces/blank", "{\"points\":[[60,30,5.5],[200,30,5.5],[60,50,5.5],[200,50,5.5]]}");
        ok("PUT", "/api/surfaces/board", "{" + BOARD_POINTS + "}");
        ok("PUT", "/api/config", "{\"vacuumProbe\":{\"vacuumSettleMs\":300,\"readDelayMs\":0},\"purgeRow\":{\"x0Mm\":80,"
                + "\"y0Mm\":47,\"pitchXMm\":2.5,\"spots\":48},\"cleanPadCheck\":{\"enabled\":false}}");
        ok("POST", "/api/screw/start-angle", "{}");
        ok("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\"}");
    }

    /** ApiServerSimTest's board: 20 x 10 mm, three 0603 resistors. */
    File writeBoard() throws Exception {
        return writeBoard(new double[][] {{2, 2, 0}, {8, 2, 90}, {14, 6, 0}});
    }

    /** The same board with its 0603 resistors at these {x, y, rotation} (board mm). */
    File writeBoard(double[][] pos) throws Exception {
        Package pkg = new Package("R_0603_1608Metric");
        Configuration.get().addPackage(pkg);
        Part part = new Part("R0603-API-TEST");
        part.setPackage(pkg);
        Configuration.get().addPart(part);
        StringBuilder sb = new StringBuilder("<openpnp-board version=\"1.1\" name=\"api-test.board.xml\">\n"
                + "<dimensions units=\"Millimeters\" x=\"20.0\" y=\"10.0\" z=\"0.0\" rotation=\"0.0\"/>\n<placements>\n");
        for (int i = 0; i < pos.length; i++) {
            sb.append(String.format(Locale.ROOT, "<placement version=\"1.4\" side=\"Top\" id=\"R%d\" part-id="
                    + "\"R0603-API-TEST\" type=\"Placement\" enabled=\"true\" rank=\"0\">\n<location units="
                    + "\"Millimeters\" x=\"%s\" y=\"%s\" z=\"0.0\" rotation=\"%s\"/>\n<error-handling>Default"
                    + "</error-handling>\n</placement>\n", i + 1, pos[i][0], pos[i][1], pos[i][2]));
        }
        sb.append("</placements>\n<fiducials/>\n</openpnp-board>\n");
        File f = new File(sim.configDir, "api-test.board.xml");
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** The registration Register board stores for that board at its planned seat (100, 80), made {@code agoMs} ago. */
    String registerPlanned(File f, long agoMs) throws Exception {
        BoardRegistration reg = new BoardRegistration();
        reg.id = UUID.randomUUID().toString();
        reg.boardKey = BoardRegistration.fileKey(f);
        reg.boardName = "api-test.board.xml";
        reg.nominal = new BoardRegistration.Nominal();
        reg.nominal.x = 100;
        reg.nominal.y = 80;
        reg.nominal.z = 5.5;
        reg.nominal.side = "Top";
        reg.affine = new double[] {1, 0, 0, 1, 100, 80};
        reg.boardRectMm = new double[] {0, 0, 20, 10};
        reg.atMs = System.currentTimeMillis() - agoMs;
        svc.updateConfigAtomically(c -> {
            c.putBoardRegistration(reg);
            return c;
        });
        return "board 'api-test.board.xml' (registration " + reg.shortId() + ")";
    }

    /** Plans the board at (100, 80), with its stored registration when it has one, and ties the board surface to it. */
    String planTied(File f) throws Exception {
        return planTied(f, "auto");
    }

    /** As above with the plan's {@code registration}: "auto", or "none" (the UI's "Plan without fiducial correction"). */
    String planTied(File f, String registration) throws Exception {
        String id = ok("POST", "/api/plan", "{\"source\":\"file\",\"file\":\"" + f.getAbsolutePath().replace("\\",
                "\\\\") + "\",\"x\":100,\"y\":80,\"z\":5.5,\"rotation\":0,\"registration\":\"" + registration
                + "\"}").get("planId").getAsString();
        ok("PUT", "/api/surfaces/board", "{" + BOARD_POINTS + ",\"planId\":\"" + id + "\"}");
        return id;
    }

    @Test
    public void aPurgeRowCrossingABoardRefusesTheRunBeforeAnyLine() throws Exception {
        setUpBoardRuns();
        String board = register("p.board.xml", 150, 44, 10, 8); // x 150..160: the purge spots 29..33 of 48 on it
        String planId = planTied(writeBoard());
        sim.server.mark();
        call("POST", "/api/run", "{\"planId\":\"" + planId + "\",\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("I13: the purge row's spot 29 of 48 (150.0, 47.0) lies on " + board + ": set the "
                + "row again off the board (Machine › Places)"), lastBody);
        assertTrue(sim.server.sinceMark().isEmpty(), "no line at all: " + sim.server.sinceMark());
    }

    /** The board's own planned dots stay allowed on it: the run pastes them, with the purge row off the board. */
    @Test
    public void aBoardRunStillPastesItsOwnBoard() throws Exception {
        setUpBoardRuns();
        File f = writeBoard();
        registerPlanned(f, 1000); // the planned board itself, as Register board records it
        String planId = planTied(f);
        assertEquals(svc.getConfig().boardRegistrations.get(BoardRegistration.fileKey(f)).id,
                ok("GET", "/api/plan/" + planId, null).getAsJsonObject("source").get("registrationId").getAsString());
        sim.server.mark();
        JsonObject res = ok("POST", "/api/run", "{\"planId\":\"" + planId + "\",\"wait\":true}")
                .getAsJsonObject("result");
        assertEquals("OK", res.get("status").getAsString(), res.toString());
        assertEquals(6, res.get("dotsDone").getAsInt(), res.toString());
        assertTrue(switchedOn(), "the valve opened");
    }

    /**
     * A board run's planned dots are allowed only on the outlines its registration knew: a tile imported after the
     * plan's registration (the bed changed since) refuses the run at its start, and so does any outline under a plan
     * made without a registration.
     */
    @Test
    public void aBoardRunsDotsOnAnOutlineItsRegistrationDidNotKnowAreRefused() throws Exception {
        setUpBoardRuns();
        File f = writeBoard();
        register("x.board.xml", 98, 78, 30, 15); // another board's outline over the plan's dots (x 98..128, y 78..93)
        String planId = planTied(f); // no registration of the planned board
        sim.server.mark();
        call("POST", "/api/run", "{\"planId\":\"" + planId + "\",\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("I13: the plan's dot at (") && lastBody.contains(") lies on board 'x.board.xml' "
                + "(registration ") && lastBody.contains(", and the plan has no board registration: register the board "
                + "(Register board)"), lastBody);
        assertTrue(sim.server.sinceMark().isEmpty(), "no line at all: " + sim.server.sinceMark());

        ok("DELETE", "/api/board-registrations/" + registrationId("x.board.xml"), null);
        registerPlanned(f, 1000); // registered: the outlines up to its registration are its bed
        planId = planTied(f);
        ok("PUT", "/api/tiles/nano/outline", "{\"label\":\"Nano tile\",\"outline\":[[95,75],[125,75],[125,95],"
                + "[95,95]],\"boards\":[],\"checkPoints\":[[100,80],[110,85],[120,90]]}");
        sim.server.mark();
        call("POST", "/api/run", "{\"planId\":\"" + planId + "\",\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("I13: the plan's dot at (") && lastBody.contains(") lies on the Nano tile, "
                + "recorded after the plan's board registration (the bed changed since): register the board again "
                + "(Register board)"), lastBody);
        assertTrue(sim.server.sinceMark().isEmpty(), "no line at all: " + sim.server.sinceMark());
    }

    /** A weigh coupon whose place lies on a board refuses its weigh dots at the start (I13), naming the coupon. */
    @Test
    public void aCouponOnABoardRefusesItsWeighDotsAtTheStart() throws Exception {
        setUpBoardRuns();
        ok("PUT", "/api/locations/coupon1", "{\"x\":110,\"y\":20,\"z\":5.5}"); // its 40 x 30 coupon: x 90..130
        ok("PUT", "/api/surfaces/coupon1", "{\"points\":[[100,10,5.5],[120,10,5.5],[100,30,5.5]]}");
        String board = register("k.board.xml", 126, 10, 10, 10); // x 126..136: under the coupon's edge, not its dots
        DoseBlocksRequest req = new DoseBlocksRequest();
        req.kind = DoseBlocksRequest.WEIGH;
        req.image = false;
        req.parkAfter = false;
        req.stopEpoch = svc.stopEpoch();
        DoseBlocksRequest.Block b = new DoseBlocksRequest.Block(1600, "one turn on coupon1", "coupon1");
        b.couponPlace = "coupon1";
        for (int i = 0; i < 4; i++) {
            b.xy.add(new double[] {106 + 2 * i, 18});
        }
        req.blocks.add(b);
        sim.server.mark();
        DoseBlocksResult res = svc.runDoseBlocks(req).get(120, TimeUnit.SECONDS);
        assertFalse(res.ok, res.message);
        assertTrue(res.message.contains("I13: the coupon place 'coupon1' (110.0, 20.0) lies on " + board + ": set the "
                + "coupon place again off the board (Machine › Places)"), res.message);
        assertFalse(switchedOn(), "nothing switched on: " + sim.server.sinceMark());
    }

    /** A tile over the planned board's dots (x 95..125, y 75..95), no boards of its own. */
    static final String TILE_OVER_THE_BOARD = "{\"label\":\"Nano tile\",\"outline\":[[95,75],[125,75],[125,95],[95,95]],"
            + "\"boards\":[],\"checkPoints\":[[100,80],[110,85],[120,90]]}";

    /**
     * A plan made without the fiducial correction (registration "none": the UI's fix after a failed Register board) may
     * lay its dots on its own board's stored registration, and on no other outline: another board's registration or a
     * tile over its dots refuses it.
     */
    @Test
    public void aPlanWithoutItsRegistrationPastesOnlyItsOwnRegisteredBoard() throws Exception {
        setUpBoardRuns();
        File f = writeBoard();
        registerPlanned(f, 1000); // the board is registered, but the plan is made without it
        String planId = planTied(f, "none");
        assertTrue(ok("GET", "/api/plan/" + planId, null).get("registration").isJsonNull(), lastBody);
        JsonObject res = ok("POST", "/api/run", "{\"planId\":\"" + planId + "\",\"maxDots\":2,\"wait\":true}")
                .getAsJsonObject("result");
        assertEquals("OK", res.get("status").getAsString(), res.toString());

        String other = register("y.board.xml", 98, 78, 30, 15); // another board's outline over its dots
        sim.server.mark();
        call("POST", "/api/run", "{\"planId\":\"" + planId + "\",\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains(") lies on " + other + ", and the plan has no board registration: register the "
                + "board (Register board)"), lastBody);
        ok("DELETE", "/api/board-registrations/" + registrationId("y.board.xml"), null);
        ok("PUT", "/api/tiles/nano/outline", TILE_OVER_THE_BOARD);
        call("POST", "/api/run", "{\"planId\":\"" + planId + "\",\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains(") lies on the Nano tile, and the plan has no board registration: register the "
                + "board (Register board)"), lastBody);
        assertTrue(sim.server.sinceMark().isEmpty(), "no line at all: " + sim.server.sinceMark());
    }

    /**
     * Outline stamps survive a clock set back: a tile imported after a registration stamped while the clock ran an hour
     * ahead is still the newer of the two, so the registered plan is refused on it.
     */
    @Test
    public void aTileImportedAfterTheClockWasSetBackIsStillTheNewerOutline() throws Exception {
        setUpBoardRuns();
        File f = writeBoard();
        registerPlanned(f, -3_600_000); // stamped an hour ahead of the clock now
        String planId = planTied(f);
        ok("PUT", "/api/tiles/nano/outline", TILE_OVER_THE_BOARD);
        long regAt = svc.getConfig().boardRegistrations.get(BoardRegistration.fileKey(f)).atMs;
        DispenseConfig.Tile tile = svc.getConfig().tiles.get("nano");
        assertTrue(tile.outlineAtMs != null && tile.outlineAtMs > regAt, "the tile is ordered after the registration");
        assertTrue(tile.atMs < regAt, "its atMs stays on the wall clock");
        sim.server.mark();
        call("POST", "/api/run", "{\"planId\":\"" + planId + "\",\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains(") lies on the Nano tile, recorded after the plan's board registration (the bed "
                + "changed since): register the board again (Register board)"), lastBody);
        assertTrue(sim.server.sinceMark().isEmpty(), "no line at all: " + sim.server.sinceMark());
    }

    /** With the needle's position unknown, the hold points to the waste only while the waste is off the boards. */
    @Test
    public void theHoldPointsToTheWasteOnlyWhileItIsOffTheBoards() throws Exception {
        assertEquals("the needle's position is unknown: start it with park: 'waste'",
                PressureHold.zoneRefusal(svc, new PressureHold.Options(), null));
        register("w.board.xml", 60, 30, 30, 15); // the waste (80, 40) on it
        assertEquals("the needle's position is unknown: start it with a park over a place off the boards (the waste "
                + "too lies within 8.0 mm of one)", PressureHold.zoneRefusal(svc, new PressureHold.Options(), null));
    }

    /** A 20 x 10 mm board of two parts whose package has no pad type yet (0603 pads): a "use" suggestion. */
    File chipBoard() throws Exception {
        StringBuilder sb = new StringBuilder("<openpnp-board version=\"1.1\" name=\"chips.board.xml\">\n"
                + "<dimensions units=\"Millimeters\" x=\"20.0\" y=\"10.0\" z=\"0.0\" rotation=\"0.0\"/>\n<placements>\n");
        for (int i = 0; i < 2; i++) {
            sb.append(String.format(Locale.ROOT, "<placement version=\"1.4\" side=\"Top\" id=\"C%d\" part-id="
                    + "\"part-TestChip_A\" type=\"Placement\" enabled=\"true\" rank=\"0\">\n<location units="
                    + "\"Millimeters\" x=\"%d\" y=\"3\" z=\"0.0\" rotation=\"0.0\"/>\n<error-handling>Default"
                    + "</error-handling>\n</placement>\n", i + 1, 3 + 6 * i));
        }
        sb.append("</placements>\n<fiducials/>\n</openpnp-board>\n");
        File file = new File(sim.configDir, "chips.board.xml");
        Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        EdtRunner.call(() -> {
            Footprint fp = new Footprint();
            fp.setUnits(LengthUnit.Millimeters);
            for (int k = 0; k < 2; k++) {
                Footprint.Pad pad = new Footprint.Pad();
                pad.setName(Integer.toString(k + 1));
                pad.setX((k == 0 ? -1 : 1) * 1.65 / 2);
                pad.setWidth(0.80);
                pad.setHeight(0.95);
                pad.setRoundness(50);
                fp.addPad(pad);
            }
            Package pkg = new Package("TestChip_A");
            pkg.setFootprint(fp);
            Configuration.get().addPackage(pkg);
            Part part = new Part("part-TestChip_A");
            part.setPackage(pkg);
            Configuration.get().addPart(part);
            Configuration.get().getBoard(file);
            return null;
        }, 10_000);
        return file;
    }

    /**
     * A plan planned again after a pad-type suggestion is applied (PlanAgain: also the end of a pad calibration) keeps
     * its own board's outline: planned without a registration on its registered board, the new plan runs.
     */
    @Test
    public void aPlanMadeAgainAfterASuggestionKeepsItsOwnBoard() throws Exception {
        setUpBoardRuns();
        File f = chipBoard();
        registerPlanned(f, 1000);
        String planId = planTied(f, "none");
        JsonObject g = null;
        for (JsonElement e : ok("GET", "/api/pad-profile/check?planId=" + planId, null).getAsJsonArray("groups")) {
            if ("TestChip_A".equals(e.getAsJsonObject().getAsJsonArray("packageIds").get(0).getAsString())) {
                g = e.getAsJsonObject();
            }
        }
        assertTrue(g != null && "use".equals(g.getAsJsonObject("suggestion").get("kind").getAsString()), lastBody);
        String group = g.get("group").getAsString(), sid = g.getAsJsonObject("suggestion").get("id").getAsString();
        String again = ok("POST", "/api/pad-profile/apply", "{\"planId\":\"" + planId + "\",\"groups\":[\"" + group
                + "\"],\"choice\":\"use\",\"suggestionIds\":{\"" + group + "\":\"" + sid + "\"}}").get("newPlanId")
                .getAsString();
        JsonObject res = ok("POST", "/api/run", "{\"planId\":\"" + again + "\",\"wait\":true}").getAsJsonObject("result");
        assertEquals("OK", res.get("status").getAsString(), res.toString());
    }

    /**
     * The own-board exemption follows the board at its seat, not its file: a second copy of a registered board file,
     * planned at its own seat ("auto" finds the record is the first copy's), has no outline of its own, so its dot
     * within the margin of the first copy is refused.
     */
    @Test
    public void aSecondCopyOfARegisteredBoardFileIsNotExemptOnTheFirst() throws Exception {
        setUpBoardRuns();
        File f = writeBoard(new double[][] {{0.9, 5, 0}}); // one 0603, its left pad 0.1 mm from the board's edge
        registerPlanned(f, 1000); // copy 1 at (100, 80): x 100..120
        String wide = "\"points\":[[98,78,5.5],[145,78,5.5],[98,95,5.5],[145,95,5.5]]";
        ok("PUT", "/api/surfaces/board", "{" + wide + "}");
        // copy 2 1.5 mm right of copy 1 (x 121.5..141.5): its left pad (121.6, 85) 1.6 mm from copy 1
        String id = ok("POST", "/api/plan", "{\"source\":\"file\",\"file\":\"" + f.getAbsolutePath().replace("\\",
                "\\\\") + "\",\"x\":121.5,\"y\":80,\"z\":5.5,\"rotation\":0}").get("planId").getAsString();
        assertTrue(ok("GET", "/api/plan/" + id, null).get("registration").isJsonNull(), "the record is copy 1's: none");
        ok("PUT", "/api/surfaces/board", "{" + wide + ",\"planId\":\"" + id + "\"}");
        sim.server.mark();
        call("POST", "/api/run", "{\"planId\":\"" + id + "\",\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("I13: the plan's dot at (121.6, 85.0) lies on board 'api-test.board.xml' "
                + "(registration ") && lastBody.contains(", and the plan has no board registration: register the board "
                + "(Register board)"), lastBody);
        assertTrue(sim.server.sinceMark().isEmpty(), "no line at all: " + sim.server.sinceMark());
    }
}
