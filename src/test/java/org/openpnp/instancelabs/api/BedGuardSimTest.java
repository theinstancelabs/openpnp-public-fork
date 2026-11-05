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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.BedFixture;
import org.openpnp.instancelabs.dispense.BedModelStore;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Machine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-98 stage 1 on the in-process SIM (collision-model-design.md §7 (c), (d)): jog, move-to and the passthrough prove
 * their whole path against the fixture model before anything is sent. Refused: 409 {@code collision} naming the
 * object, nothing sent, a MOTION_REFUSED row. Clear: the move runs, one MOTION_PROOF row. No model file: every route as
 * before. A file that fails validation: every hooked motion refused.
 */
public class BedGuardSimTest {
    static final String UI = "ui";
    /** The needle's tip over the switch (65, 25): head (39.709, 100.926); the plane there is raw -3.0361111. */
    static final double SWITCH_HX = 65 - 25.291, SWITCH_HY = 25 + 75.926;

    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;
    int status;
    String body;

    /** The SIM, homed, with the model file {@code model} (null: none) in place before the engine starts. */
    void start(String model) throws Exception {
        sim = new SimMachine();
        if (model != null) {
            File f = BedModelStore.fileFor(sim.configDir);
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), model.getBytes(StandardCharsets.UTF_8));
        }
        svc = DispenseService.start(Configuration.get());
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    public void after() throws Exception {
        if (api != null) {
            sim.server.delayBeforeOkMs.clear();
            api.stop();
            svc.shutdown();
            sim.close();
        }
    }

    JsonObject call(String method, String path, String json, String who) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json");
        if (UI.equals(who)) {
            b.header(MachineLock.UI_HEADER, "ui");
        }
        else if (who != null) {
            b.header(MachineLock.TOKEN_HEADER, who);
        }
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        status = r.statusCode();
        body = r.body();
        return new JsonParser().parse(body.isEmpty() ? "{}" : body).getAsJsonObject();
    }

    JsonObject ok(String method, String path, String json, String who) throws Exception {
        JsonObject o = call(method, path, json, who);
        assertTrue(status >= 200 && status < 300, status + " " + body);
        return o;
    }

    /** A 409 collision naming {@code blocker} (null: any), as the route answers it. */
    JsonObject collision(String blocker, JsonObject o) {
        assertEquals(409, status, body);
        assertEquals("collision", o.get("error").getAsString(), body);
        if (blocker != null) {
            assertEquals(blocker, o.getAsJsonObject("blocker").get("id").getAsString(), body);
        }
        String why = o.get("why").getAsString(), next = o.get("next").getAsString();
        // clauses: lower case, no final period (the UI adds "Not moved:" and the capital)
        assertTrue(!why.isEmpty() && !why.endsWith(".") && !next.endsWith("."), body);
        assertEquals(why + ": " + next, o.get("reason").getAsString());
        return o;
    }

    /** A proof's leg labels, as JSON. */
    static String legNames(JsonObject proof) {
        JsonArray names = new JsonArray();
        for (JsonElement l : proof.getAsJsonArray("legs")) {
            names.add(l.getAsJsonObject().get("leg"));
        }
        return names.toString();
    }

    /** The motion lines the controller received since the last mark. */
    List<String> motionSent() {
        List<String> l = new ArrayList<>();
        for (String s : sim.server.sinceMark()) {
            if (s.startsWith("G0") || s.startsWith("G1") || s.startsWith("G28")) {
                l.add(s);
            }
        }
        return l;
    }

    /** The journal's rows of {@code code}, newest first. */
    List<JsonObject> rows(String code) throws Exception {
        List<JsonObject> l = new ArrayList<>();
        for (Map<String, Object> e : svc.getDb().recentEvents(500)) {
            if (code.equals(e.get("code"))) {
                l.add(new JsonParser().parse((String) e.get("message")).getAsJsonObject());
            }
        }
        return l;
    }

    /** The needle's Z frame as the bench has it (ControlApiSimTest): its floor is low enough to reach the switch. */
    void needleFrame() throws Exception {
        ok("POST", "/api/needle/anchor", "{\"needleChangedAtMs\":null,\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}",
                UI);
        ok("POST", "/api/needle/offset", "{\"needleChangedAtMs\":null,\"offsetMm\":-2.59}", UI);
    }

    /** No model file: the check is off, every route runs as today and says so. */
    @Test
    public void withoutAModelEveryRouteRunsAsBefore() throws Exception {
        start(null);
        JsonObject bed = ok("GET", "/api/bed", null, null);
        assertTrue(bed.get("version").isJsonNull(), body);
        assertEquals("no bed model yet, moves are not checked for collisions", bed.get("why").getAsString());
        JsonObject st = ok("GET", "/api/status", null, null).getAsJsonObject("bed");
        assertTrue(st.get("version").isJsonNull(), st.toString());
        assertEquals("no bed model yet, moves are not checked for collisions", st.get("why").getAsString());
        ok("POST", "/api/jog", "{\"tool\":\"n1\",\"dx\":1}", UI);
        ok("POST", "/api/move-to", "{\"tool\":\"n1\",\"x\":230,\"y\":260}", UI); // over the fixture's black blank
        JsonObject dry = ok("POST", "/api/motion/check", "{\"kind\":\"jog\",\"tool\":\"n1\",\"dx\":1}", UI);
        assertTrue(dry.get("clear").isJsonNull(), body);
        ok("POST", "/api/lock/release", "{}", UI);
        String token = acquire();
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 X39.709 Y100.926 F3000\",\"G1 Z60 F600\",\"M400\"]}", token);
        assertTrue(rows("MOTION_PROOF").isEmpty() && rows("MOTION_REFUSED").isEmpty());
    }

    String acquire() throws Exception {
        return ok("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"bed test\"}", null).get("token")
                .getAsString();
    }

    /** A file that fails validation: every hooked motion route refuses (fail closed), naming the file's problem. */
    @Test
    public void aModelThatFailsValidationRefusesEveryHookedMotion() throws Exception {
        start(BedFixture.text().replace("\"box\": [60, 120, 160, 200]", "\"box\": [160, 120, 60, 200]"));
        JsonObject bed = ok("GET", "/api/bed", null, null);
        assertFalse(bed.get("valid").getAsBoolean(), body);
        assertTrue(bed.get("error").getAsString().contains("x0 < x1"), body);
        assertFalse(ok("GET", "/api/status", null, null).getAsJsonObject("bed").get("valid").getAsBoolean());
        sim.server.mark();
        JsonObject j = collision(null, call("POST", "/api/jog", "{\"tool\":\"n1\",\"dx\":1}", UI));
        assertTrue(j.get("why").getAsString().contains("x0 < x1"), body);
        collision(null, call("POST", "/api/move-to", "{\"tool\":\"n1\",\"x\":100,\"y\":400}", UI));
        ok("POST", "/api/lock/release", "{}", UI);
        String token = acquire();
        collision(null, call("POST", "/api/gcode", "{\"lines\":[\"G1 X5 F3000\",\"M400\"]}", token));
        assertEquals(List.of(), motionSent(), "nothing sent");
    }

    /**
     * (c) and (d) for the jog: the needle pressed down over the switch passes while it stays 0.2 mm outside the Z
     * margin and is refused one press lower, nothing sent; the same presses beside the switch pass, each proven.
     */
    @Test
    public void aJogOfTheNeedleOntoTheSwitchIsRefused() throws Exception {
        start(BedFixture.text());
        needleFrame();
        ok("POST", "/api/move-to", "{\"tool\":\"needle\",\"x\":65,\"y\":25}", UI); // ends at raw 36.5
        for (int i = 0; i < 8; i++) {
            ok("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-2}", UI);
        }
        // raw 53.161111: the needle 67.911111 - 53.161111 = 14.75 above the bed, the switch needs 12 + 2 + 0.05 + 0.5
        ok("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-0.661111}", UI);
        assertEquals(53.161111, sim.server.position('Z'), 1e-4);
        JsonObject proof = rows("MOTION_PROOF").get(0);
        assertEquals("jog", proof.get("what").getAsString());
        assertEquals("height-switch", proof.getAsJsonObject("closest").get("id").getAsString(), proof.toString());
        assertEquals(0.2, proof.getAsJsonObject("closest").get("clearanceMm").getAsDouble(), 1e-4);
        sim.server.mark();
        JsonObject r = collision("height-switch", call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-1}", UI));
        assertEquals("needle", r.get("tool").getAsString());
        assertEquals(0.8, r.get("shortfallMm").getAsDouble(), 1e-4);
        assertEquals(13.75, r.get("toolLowestZ").getAsDouble(), 1e-4);
        assertEquals(12.0, r.get("blockerTopZ").getAsDouble(), 1e-9);
        assertEquals("jog Z -1", r.get("leg").getAsString());
        assertEquals(1, r.get("modelVersion").getAsInt());
        assertEquals(65, r.getAsJsonObject("at").get("x").getAsDouble(), 1e-3);
        assertEquals(List.of(), motionSent(), "nothing sent");
        assertEquals(53.161111, sim.server.position('Z'), 1e-4);
        assertEquals("height-switch", rows("MOTION_REFUSED").get(0).getAsJsonObject("blocker").get("id")
                .getAsString());
        // the dry run gives the same answer and moves nothing
        collision("height-switch", call("POST", "/api/motion/check", "{\"kind\":\"jog\",\"tool\":\"needle\",\"dz\":-1}",
                UI));
        // beside the switch the same presses pass, each with its proof
        ok("POST", "/api/move-to", "{\"tool\":\"needle\",\"x\":95,\"y\":25}", UI);
        int proofs = rows("MOTION_PROOF").size();
        for (int i = 0; i < 9; i++) {
            ok("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-2}", UI);
        }
        assertEquals(54.5, sim.server.position('Z'), 1e-6);
        assertEquals(proofs + 9, rows("MOTION_PROOF").size());
    }

    /** (c) and (d) for move-to: across the black blank (added, not measured) refused, nothing sent; elsewhere proven. */
    @Test
    public void aMoveToAcrossAnUnmeasuredBlankIsRefused() throws Exception {
        start(BedFixture.text());
        sim.server.mark();
        JsonObject r = collision("blank-black", call("POST", "/api/move-to", "{\"tool\":\"n1\",\"x\":230,\"y\":260}",
                UI));
        assertTrue(r.get("shortfallMm").isJsonNull(), "it blocks at every height: " + body);
        assertTrue(r.get("blockerTopZ").isJsonNull());
        assertEquals("X/Y at safe Z", r.get("leg").getAsString());
        assertEquals(3, r.getAsJsonArray("path").size());
        assertEquals(List.of(), motionSent(), "nothing sent");
        // the dry run: the same refusal; elsewhere clear with its closest object
        collision("blank-black", call("POST", "/api/motion/check", "{\"kind\":\"move-to\",\"tool\":\"n1\",\"x\":230,"
                + "\"y\":260}", UI));
        JsonObject dry = ok("POST", "/api/motion/check", "{\"kind\":\"move-to\",\"tool\":\"n1\",\"x\":100,\"y\":400}",
                UI);
        assertTrue(dry.get("clear").getAsBoolean(), body);
        assertTrue(dry.get("clearanceMm").getAsDouble() > 0, body);
        assertEquals(1, dry.get("modelVersion").getAsInt());
        assertEquals("homed", dry.getAsJsonObject("start").get("source").getAsString());
        assertEquals(List.of(), motionSent(), "a dry run moves nothing");
        assertTrue(rows("MOTION_PROOF").isEmpty(), "and journals nothing");
        ok("POST", "/api/move-to", "{\"tool\":\"n1\",\"x\":100,\"y\":400}", UI);
        JsonObject proof = rows("MOTION_PROOF").get(0);
        assertEquals("move-to", proof.get("what").getAsString());
        assertEquals(3, proof.getAsJsonArray("legs").size());
        // the legs named as OpenPnP drives them: the last one is no move, the target being N1's safe Z (#66 NIT)
        assertEquals("[\"up to safe Z\",\"X/Y at safe Z\",\"N1 at the target (no move)\"]", legNames(proof));
        assertEquals(1, proof.get("modelVersion").getAsInt());
        assertEquals("homed", proof.getAsJsonObject("start").get("source").getAsString());
        assertTrue(proof.has("checkMs"));
        // home and run are later stages
        call("POST", "/api/motion/check", "{\"kind\":\"home\"}", UI);
        assertEquals(400, status, body);
        assertTrue(body.contains("not in this build yet"), body);
    }

    /**
     * (c) and (d) for the passthrough: a batch whose second line puts the needle on the switch is refused whole before
     * any line; beside the switch it runs, proven; N1 may touch the front blank (touchable, measured) contactDepthMm
     * deep but no deeper; a batch is walked from where the last batch left the head (no M400: OpenPnP's position is
     * stale).
     */
    @Test
    public void aPassthroughBatchOntoTheSwitchIsRefusedWhole() throws Exception {
        start(BedFixture.text());
        String token = acquire();
        sim.server.mark();
        JsonObject r = collision("height-switch", call("POST", "/api/gcode", "{\"lines\":[\"G1 X39.709 Y100.926 F3000\","
                + "\"G1 Z60 F600\",\"M400\"]}", token));
        assertEquals("line 2: G1 Z60 F600", r.get("leg").getAsString());
        assertEquals(List.of(), sim.server.sinceMark(), "nothing sent");
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 X59.709 Y100.926 F3000\",\"G1 Z60 F600\",\"M400\"]}", token);
        JsonObject proof = rows("MOTION_PROOF").get(0);
        assertEquals("gcode", proof.get("what").getAsString());
        assertEquals(2, proof.getAsJsonArray("legs").size());
        // N1 onto the front blank's top (8.3): 8.076 is contact (0.224 deep), 7.976 is 0.324 deep
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 Z31.5 F3000\",\"G1 X134.178 Y223.91 F3000\",\"G1 Z5.1 F300\","
                + "\"M400\"]}", token);
        sim.server.mark();
        collision("blank-front", call("POST", "/api/gcode", "{\"lines\":[\"G1 Z5.0 F300\"]}", token));
        assertEquals(List.of(), sim.server.sinceMark());
        // no M400: OpenPnP's position is stale, the walk starts where these lines left the head
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 Z31.5 F3000\"]}", token);
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 X39.709 Y100.926 F3000\"]}", token);
        collision("height-switch", call("POST", "/api/gcode", "{\"lines\":[\"G1 Z60 F600\"]}", token));
    }

    /**
     * A batch cut short (the controller answered a move with an error) leaves where the head went unknown: the next
     * batch with moves is refused until an M400 alone has OpenPnP re-read the position, also a move onto the spot
     * OpenPnP still believes in, which makes no leg from there (review MAJOR 1: the head is really elsewhere).
     */
    @Test
    public void afterABatchCutShortMovesWaitForAnM400() throws Exception {
        start(BedFixture.text());
        String token = acquire();
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 X100 Y300 F3000\",\"M400\"]}", token);
        sim.server.mark();
        sim.server.failAtLine = 0;
        call("POST", "/api/gcode", "{\"lines\":[\"G1 X10 F3000\"]}", token);
        assertEquals(502, status, body);
        sim.server.failAtLine = -1;
        int proofs = rows("MOTION_PROOF").size(), refused = rows("MOTION_REFUSED").size();
        sim.server.mark();
        JsonObject r = collision(null, call("POST", "/api/gcode", "{\"lines\":[\"G1 X20 F3000\"]}", token));
        assertTrue(r.get("why").getAsString().contains("cut short"), body);
        assertTrue(r.get("next").getAsString().contains("M400"), body);
        r = collision(null, call("POST", "/api/gcode", "{\"lines\":[\"G1 X100 Y300 F3000\"]}", token));
        assertTrue(r.get("why").getAsString().contains("cut short"), body);
        assertEquals(List.of(), motionSent(), "nothing sent");
        assertEquals(proofs, rows("MOTION_PROOF").size(), "nothing proven");
        assertEquals(refused + 2, rows("MOTION_REFUSED").size(), "each refusal on record");
        ok("POST", "/api/gcode", "{\"lines\":[\"M400\"]}", token);
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 X20 F3000\"]}", token);
        assertEquals(20, sim.server.position('X'), 1e-9);
    }

    /**
     * An M410 may stop the head short of its target: no later line of the same batch moves X, Y or Z (review MAJOR 1).
     * A batch that ends with it runs, and OpenPnP re-reads where the head is at its end.
     */
    @Test
    public void noLineAfterAnM410MovesTheHead() throws Exception {
        start(BedFixture.text());
        String token = acquire();
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 X100 Y300 F3000\",\"M400\"]}", token);
        sim.server.mark();
        JsonObject r = collision(null, call("POST", "/api/gcode", "{\"lines\":[\"G1 Y150 F3000\",\"G1 X350 F3000\","
                + "\"M410\",\"G1 Y350 F3000\"]}", token));
        assertTrue(r.get("why").getAsString().contains("line 3's M410"), body);
        assertTrue(r.get("next").getAsString().contains("batch of their own"), body);
        assertEquals(List.of(), sim.server.sinceMark(), "nothing sent");
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 Y150 F3000\",\"G1 X350 F3000\",\"M410\"]}", token);
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 Y350 F3000\"]}", token);
        assertEquals(350, sim.server.position('Y'), 1e-9);
    }

    /**
     * An M410 whose ok is lost (a timeout) may have stopped the head short: where it is is not known, so the next batch
     * with moves is refused until an M400 alone re-reads it (review 2 MINOR 3; before, it was walked from the old
     * target). The ok is held until the batch has timed out, so nothing waits on a clock.
     */
    @Test
    public void anM410WhoseOkIsLostLeavesThePlaceUnknown() throws Exception {
        start(BedFixture.text());
        String token = acquire();
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 X100 Y300 F3000\",\"M400\"]}", token);
        CountDownLatch held = new CountDownLatch(1);
        sim.server.holdOkUntil.put("M410", held);
        try {
            call("POST", "/api/gcode", "{\"lines\":[\"G1 X150 Y300 F3000\",\"M410\"],\"timeoutMs\":1000}", token);
            assertEquals(504, status, body);
        }
        finally {
            held.countDown();
        }
        sim.server.mark();
        JsonObject r = call("POST", "/api/gcode", "{\"lines\":[\"G1 X100 Y300 F3000\"],\"timeoutMs\":1000}", token);
        assertEquals(List.of(), motionSent(), "nothing sent: " + body);
        collision(null, r);
        assertTrue(r.get("why").getAsString().contains("cut short"), body);
    }

    /**
     * The same for an M410 in flight when the batch is stopped (the route's time bound: the machine stays on): the
     * next batch with moves is refused (review 2 MINOR 3, the interrupted line).
     */
    @Test
    public void anInterruptedM410LeavesThePlaceUnknown() throws Exception {
        start(BedFixture.text());
        String token = acquire();
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 X100 Y300 F3000\",\"M400\"]}", token);
        CountDownLatch held = new CountDownLatch(1);
        sim.server.holdOkUntil.put("M410", held);
        sim.server.mark();
        CompletableFuture<Integer> batch = CompletableFuture.supplyAsync(() -> {
            try {
                call("POST", "/api/gcode", "{\"lines\":[\"G1 X150 Y300 F3000\",\"M410\"]}", token);
                return status;
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        try {
            long t0 = System.currentTimeMillis();
            while (!sim.server.sinceMark().contains("M410") && System.currentTimeMillis() - t0 < 10000) {
                Thread.sleep(10);
            }
            assertTrue(api.getContext().passthrough.abort("the batch did not end in time"), "the batch runs");
        }
        finally {
            held.countDown();
        }
        assertEquals(409, (int) batch.get(), "stopped");
        assertTrue(Configuration.get().getMachine().isEnabled(), "the machine stays on");
        sim.server.mark();
        JsonObject r = call("POST", "/api/gcode", "{\"lines\":[\"G1 X100 Y300 F3000\"]}", token);
        assertEquals(List.of(), motionSent(), "nothing sent: " + body);
        collision(null, r);
        assertTrue(r.get("why").getAsString().contains("cut short"), body);
    }

    /**
     * A batch stopped with its move in flight (a STOP, a take-over, the route's time bound) leaves where the head went
     * unknown, the machine still on after the time bound: no move until an M400 alone re-reads it (review MAJOR 1).
     */
    @Test
    public void afterAnInterruptedMoveNoLineMovesTheHead() throws Exception {
        start(BedFixture.text());
        String token = acquire();
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 X100 Y300 F3000\",\"M400\"]}", token);
        CountDownLatch held = new CountDownLatch(1);
        sim.server.holdOkUntil.put("G1 X150", held);
        sim.server.mark();
        CompletableFuture<Integer> batch = CompletableFuture.supplyAsync(() -> {
            try {
                call("POST", "/api/gcode", "{\"lines\":[\"G1 X150 Y300 F3000\",\"M400\"]}", token);
                return status;
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long t0 = System.currentTimeMillis();
        while (!sim.server.sinceMark().contains("G1 X150 Y300 F3000") && System.currentTimeMillis() - t0 < 10000) {
            Thread.sleep(10);
        }
        assertTrue(api.getContext().passthrough.abort("the batch did not end in time"), "the batch runs");
        held.countDown();
        assertEquals(409, (int) batch.get(), "stopped");
        assertTrue(Configuration.get().getMachine().isEnabled(), "the machine stays on");
        sim.server.mark();
        JsonObject r = collision(null, call("POST", "/api/gcode", "{\"lines\":[\"G1 X100 Y300 F3000\"]}", token));
        assertTrue(r.get("why").getAsString().contains("cut short"), body);
        assertEquals(List.of(), motionSent(), "nothing sent");
        ok("POST", "/api/gcode", "{\"lines\":[\"M400\"]}", token);
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 X100 Y300 F3000\"]}", token);
        assertEquals(100, sim.server.position('X'), 1e-9);
    }

    /**
     * The dry run proves from where the head is: while OpenPnP's position is not re-read after the passthrough's moves
     * it refuses, as the real routes re-read it first (#66 review MINOR e).
     */
    @Test
    public void theDryRunWaitsForTheHeadsPlaceToBeRead() throws Exception {
        start(BedFixture.text());
        String token = acquire();
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 X120 F3000\"]}", token);
        String dry = "{\"kind\":\"move-to\",\"tool\":\"n1\",\"x\":100,\"y\":400}";
        JsonObject r = call("POST", "/api/motion/check", dry, UI);
        assertEquals(409, status, body);
        assertEquals("refused", r.get("error").getAsString(), body);
        assertTrue(r.get("reason").getAsString().contains("not re-read"), body);
        ok("POST", "/api/gcode", "{\"lines\":[\"M400\"]}", token);
        assertTrue(ok("POST", "/api/motion/check", dry, UI).get("clear").getAsBoolean(), body);
    }

    /**
     * After a needle change the model's needle tip end and K (the needle's length) are the old needle's (the 27G's and
     * the bent 22G's ends were 5.9 mm apart; K has been 64.50, 64.875 and 65.59): every hooked move is refused, 409
     * measurement-needed naming both in one answer, nothing sent, until each is recorded again with a measurement
     * record since the change (review MAJOR 3; review 2 MAJOR 1).
     */
    @Test
    public void afterANeedleChangeMovesWaitForTheNeedlesTipEnd() throws Exception {
        start(BedFixture.text());
        ok("POST", "/api/needle/changed", "{\"why\":\"bed test\"}", UI);
        sim.server.mark();
        JsonObject r = call("POST", "/api/jog", "{\"tool\":\"n1\",\"dx\":1}", UI);
        assertEquals(409, status, body);
        assertEquals("measurement-needed", r.get("error").getAsString(), body);
        assertTrue(r.get("why").getAsString().contains("needle's tip end"), body);
        assertTrue(r.get("why").getAsString().contains("K, the needle's length"), body);
        assertTrue(r.get("next").getAsString().contains("PUT /api/bed/tools/needle"), body);
        assertTrue(r.get("next").getAsString().contains("PUT /api/bed/seesaw"), body);
        assertEquals(r.get("why").getAsString() + ": " + r.get("next").getAsString(), r.get("reason").getAsString());
        call("POST", "/api/move-to", "{\"tool\":\"n1\",\"x\":100,\"y\":400}", UI);
        assertEquals(409, status, body);
        call("POST", "/api/motion/check", "{\"kind\":\"jog\",\"tool\":\"n1\",\"dx\":1}", UI);
        assertEquals(409, status, body);
        ok("POST", "/api/lock/release", "{}", UI);
        String token = acquire();
        call("POST", "/api/gcode", "{\"lines\":[\"G1 X100 F3000\"]}", token);
        assertEquals(409, status, body);
        assertEquals(List.of(), motionSent(), "nothing sent");
        assertEquals("measurement-needed", rows("MOTION_REFUSED").get(0).get("error").getAsString());
        // a tip end stamped after the change needs its measurement record; with it, moves pass again
        JsonObject needle = BedFixture.json().getAsJsonArray("tools").get(1).getAsJsonObject();
        needle.getAsJsonObject("tipEnd").addProperty("atMs", System.currentTimeMillis());
        call("PUT", "/api/bed/tools/needle", needle.toString(), token);
        assertEquals(409, status, "a newer tip end is a measurement: " + body);
        svc.getDb().event(null, "INFO", "NEEDLE_LANDING", "the tip's end over the bottom camera (bench)");
        needle.add("measured", new JsonParser().parse("{\"how\":\"bottom camera\",\"by\":\"Process\",\"atMs\":"
                + System.currentTimeMillis() + ",\"errMm\":0.05,\"evidence\":\"event:" + svc.getDb().recentEvents(1)
                        .get(0).get("id") + "\"}"));
        ok("PUT", "/api/bed/tools/needle", needle.toString(), token);
        // the tip end alone is not enough: K is still the old needle's
        r = call("POST", "/api/gcode", "{\"lines\":[\"G1 X100 F3000\",\"M400\"]}", token);
        assertEquals(409, status, body);
        assertEquals("measurement-needed", r.get("error").getAsString(), body);
        assertFalse(r.get("why").getAsString().contains("tip end"), body);
        assertTrue(r.get("why").getAsString().contains("K, the needle's length"), body);
        assertEquals(List.of(), motionSent(), "nothing sent");
        JsonObject seesaw = BedFixture.json().getAsJsonObject("seesaw");
        seesaw.getAsJsonObject("K").addProperty("raw", 65.2);
        svc.getDb().event(null, "INFO", "PROBE", "N1 and the needle on the front blank (bench)");
        seesaw.add("measured", new JsonParser().parse("{\"how\":\"n1 and needle touch\",\"by\":\"Process\",\"atMs\":"
                + System.currentTimeMillis() + ",\"errMm\":0.05,\"evidence\":\"event:" + svc.getDb().recentEvents(1)
                        .get(0).get("id") + "\"}"));
        ok("PUT", "/api/bed/seesaw", seesaw.toString(), token);
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 X100 F3000\",\"M400\"]}", token);
        assertEquals(100, sim.server.position('X'), 1e-9);
    }

    /**
     * While the hooked moves wait for the new needle's measurements, what measures them still runs (review 2 item 7):
     * the tip picture moves N2 over the bottom camera and stores its photo, which is then the new tip end's evidence;
     * Home sends G28; STOP answers 200. None is hooked: the gate in a shared pre-check would refuse them all.
     */
    @Test
    public void afterANeedleChangeTheTipPictureHomeAndStopStillRun() throws Exception {
        start(BedFixture.text());
        ok("POST", "/api/needle/changed", "{\"why\":\"bed test\"}", UI);
        // the gate is on: the dry run passes neither shared pre-check, so the gate moved into one fails below, not here
        JsonObject r = call("POST", "/api/motion/check", "{\"kind\":\"jog\",\"tool\":\"n1\",\"dx\":1}", UI);
        assertEquals("measurement-needed", r.get("error").getAsString(), "the gate is on: " + body);
        svc.setTipCamera(new OutputZonesSimTest.TipCam());
        sim.server.mark();
        JsonObject pic = ok("POST", "/api/tip-setups/" + svc.mountedKey() + "/picture", "{\"wait\":true}", UI)
                .getAsJsonObject("result");
        assertTrue(pic.get("ok").getAsBoolean(), body);
        assertFalse(motionSent().isEmpty(), "N2 moved to the bottom camera: " + sim.server.sinceMark());
        JsonObject needle = BedFixture.json().getAsJsonArray("tools").get(1).getAsJsonObject();
        needle.getAsJsonObject("tipEnd").addProperty("atMs", System.currentTimeMillis());
        needle.add("measured", new JsonParser().parse("{\"how\":\"bottom camera\",\"by\":\"Process\",\"atMs\":"
                + System.currentTimeMillis() + ",\"errMm\":0.05,\"evidence\":\"photo:" + pic.getAsJsonObject("photo")
                        .get("id").getAsLong() + "\"}"));
        ok("PUT", "/api/bed/tools/needle", needle.toString(), UI);
        sim.server.mark();
        ok("POST", "/api/machine/home", "{\"wait\":true}", UI);
        assertTrue(motionSent().stream().anyMatch(s -> s.startsWith("G28")), "G28 sent: " + sim.server.sinceMark());
        assertTrue(ok("POST", "/api/stop", "{}", null).get("stopped").getAsBoolean(), body);
    }

    /**
     * The proof is made on OpenPnP's machine thread right before the first line, as jog and move-to make theirs: a move
     * queued there after the batch's checks is proven from where it leaves the head (review MINOR b).
     */
    @Test
    public void aBatchIsProvenFromWhereItsFirstLineStarts() throws Exception {
        start(BedFixture.text());
        String token = acquire();
        Machine m = Configuration.get().getMachine();
        // as an OpenPnP window jog would: the head (N1 at its offset) to where the needle's tip is over the switch,
        // queued before the batch's task
        GcodePassthrough.submitHook = () -> m.submit(() -> {
            sim.n1.moveTo(sim.n1.getLocation().derive(SWITCH_HX - 24.178, SWITCH_HY - 63.91, null, null));
            return null;
        });
        try {
            sim.server.mark();
            collision("height-switch", call("POST", "/api/gcode", "{\"lines\":[\"G1 Z60 F600\"]}", token));
        }
        finally {
            GcodePassthrough.submitHook = null;
        }
        assertFalse(sim.server.sinceMark().contains("G1 Z60 F600"), "nothing of the batch sent");
        assertEquals(SWITCH_HX, sim.server.position('X'), 1e-3);
    }

    /**
     * OpenPnP's own move between two batches (its window, a script) while its position is not re-read: where the last
     * batch left the head is no longer where it is, so the next batch is refused as from an unknown place until an M400
     * alone re-reads it (review 2 MINOR 2, the re-review's probe: Z 56.5 is clear from (310, 400) and puts the needle
     * 0.59 mm into the switch's top from where the head really is).
     */
    @Test
    public void anOpenPnpMoveBetweenBatchesLeavesThePlaceUnknown() throws Exception {
        start(BedFixture.text());
        String token = acquire();
        Machine m = Configuration.get().getMachine();
        m.submit(() -> {
            sim.n1.moveTo(sim.n1.getLocation().derive(300 - 24.178, 400 - 63.91, null, null));
            return null;
        }).get();
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 X310 Y400 F3000\"]}", token); // no M400: OpenPnP's position stale
        m.submit(() -> {
            sim.n1.moveTo(sim.n1.getLocation().derive(SWITCH_HX - 24.178, SWITCH_HY - 63.91, null, null));
            return null;
        }).get();
        sim.server.mark();
        JsonObject r = call("POST", "/api/gcode", "{\"lines\":[\"G1 Z56.5 F600\"]}", token);
        assertEquals(List.of(), motionSent(), "nothing sent: " + body);
        collision(null, r);
        assertTrue(r.get("why").getAsString().contains("moved the head"), body);
        assertTrue(r.get("next").getAsString().contains("M400"), body);
        ok("POST", "/api/gcode", "{\"lines\":[\"M400\"]}", token);
        collision("height-switch", call("POST", "/api/gcode", "{\"lines\":[\"G1 Z56.5 F600\"]}", token));
        assertEquals(List.of(), motionSent(), "nothing sent");
    }
}
