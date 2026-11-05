package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.BedFixture;
import org.openpnp.instancelabs.dispense.BedModelStore;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-98 stage 1 (collision-model-design.md §6.1, §6.4 with Process's correction of 20:5x): the bed model's reads and
 * guarded writes on the in-process SIM. Writes are the lock holder's, never while anything moves; each raises the
 * version by 1 and journals a BED_MODEL row (who, before, after). A person's word may add, grow or raise; what frees
 * space needs a new measurement record naming its journal event or photos; a removed object is cleared only with both
 * the camera's comparison and N1's three-point check.
 */
public class BedApiSimTest {
    static final String UI = "ui";

    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;
    int status;
    String body;

    void start(boolean fixture) throws Exception {
        sim = new SimMachine();
        if (fixture) {
            BedFixture.install(sim.configDir);
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

    JsonObject ok(String method, String path, String json) throws Exception {
        JsonObject o = call(method, path, json, UI);
        assertTrue(status >= 200 && status < 300, status + " " + body);
        return o;
    }

    void refused(int code, String error, String text, JsonObject o) {
        assertEquals(code, status, body);
        assertEquals(error, o.get("error").getAsString(), body);
        assertTrue(body.contains(text), "'" + text + "' in " + body);
    }

    int version() throws Exception {
        return ok("GET", "/api/bed", null).get("version").getAsInt();
    }

    JsonObject object(String id) throws Exception {
        for (JsonElement e : ok("GET", "/api/bed", null).getAsJsonArray("objects")) {
            if (id.equals(e.getAsJsonObject().get("id").getAsString())) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    List<JsonObject> rows(String code) throws Exception {
        List<JsonObject> l = new ArrayList<>();
        for (Map<String, Object> e : svc.getDb().recentEvents(500)) {
            if (code.equals(e.get("code"))) {
                l.add(new JsonParser().parse((String) e.get("message")).getAsJsonObject());
            }
        }
        return l;
    }

    /** A new measurement's own journal row (as a probe or camera run writes one), to name as evidence. */
    String evidence() throws Exception {
        svc.getDb().event(null, "INFO", "PROBE", "a bench measurement");
        return "event:" + svc.getDb().recentEvents(1).get(0).get("id");
    }

    /** A new measurement record (its own time) naming {@code evidence}. */
    static String record(String how, String evidence) {
        return "{\"how\":\"" + how + "\",\"by\":\"Process\",\"atMs\":" + System.currentTimeMillis() + ",\"errMm\":0.05,"
                + "\"evidence\":\"" + evidence + "\"}";
    }

    /** The fixture's object {@code id} with {@code change} applied, as a PUT body. */
    static String objectWith(String id, String key, String json) throws Exception {
        for (JsonElement e : BedFixture.json().getAsJsonArray("objects")) {
            JsonObject o = e.getAsJsonObject();
            if (id.equals(o.get("id").getAsString())) {
                o.add(key, new JsonParser().parse(json));
                return o.toString();
            }
        }
        throw new AssertionError(id);
    }

    /** No file at first: the first whole model goes in by PUT /api/bed; the start journals version and digest. */
    @Test
    public void theFirstModelGoesInWholeAndEachStartJournalsIt() throws Exception {
        start(false);
        assertTrue(ok("GET", "/api/bed", null).get("version").isJsonNull());
        call("PUT", "/api/bed", BedFixture.text().replace("\"box\": [60, 120, 160, 200]", "\"box\": [60, 120, 60, 200]"),
                UI);
        refused(400, "bad-request", "x0 < x1", new JsonParser().parse(body).getAsJsonObject());
        JsonObject bed = ok("PUT", "/api/bed", BedFixture.text());
        assertEquals(1, bed.get("version").getAsInt(), body);
        File f = BedModelStore.fileFor(sim.configDir);
        assertTrue(f.isFile());
        JsonObject row = rows(BedModelStore.EVENT).get(0);
        assertEquals("user", row.get("who").getAsString(), row.toString());
        assertEquals("PUT /api/bed", row.get("what").getAsString());
        assertEquals("[0,1]", row.get("version").toString());
        // the read: the objects with their state in words, each tool's lowest point now, the head and the see-saw
        assertEquals("added, not measured", object("blank-black").get("stateLabel").getAsString());
        assertEquals("measured", object("holder-left").get("stateLabel").getAsString());
        JsonObject n1 = bed.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("homed", n1.get("lowest").getAsString());
        assertEquals(31.5 - BedFixture.rc(-24.178, -63.91), n1.get("lowestZNow").getAsDouble(), 1e-6, "N1 up, at home");
        JsonObject head = bed.getAsJsonObject("head");
        assertTrue(head.get("known").getAsBoolean());
        assertEquals("homed", head.get("source").getAsString());
        assertEquals(0.0, head.get("errMm").getAsDouble(), 0);
        assertEquals("homed", bed.getAsJsonObject("seesaw").get("state").getAsString());
        assertEquals(413, bed.getAsJsonObject("reach").get("cameraXEnd").getAsInt());
        JsonObject st = ok("GET", "/api/status", null).getAsJsonObject("bed");
        assertEquals(1, st.get("version").getAsInt());
        assertEquals(1, st.get("unmeasured").getAsInt());
        assertTrue(st.get("headKnown").getAsBoolean());
        // a model in place: PUT /api/bed is held to the same rules as the parts (a lower Z margin frees space)
        call("PUT", "/api/bed", BedFixture.text().replace("\"zMarginMm\": 2.0", "\"zMarginMm\": 1.5"), UI);
        refused(409, "measurement-needed", "zMarginMm", new JsonParser().parse(body).getAsJsonObject());
        assertEquals(1, version());
        assertEquals(2, ok("PUT", "/api/bed", BedFixture.text().replace("\"zMarginMm\": 2.0", "\"zMarginMm\": 2.5"))
                .get("version").getAsInt());
        // the next start reads the file and journals its version and digest
        svc.shutdown();
        svc = DispenseService.start(Configuration.get());
        JsonObject start = rows(BedModelStore.EVENT).get(0);
        assertEquals("start", start.get("at").getAsString(), start.toString());
        assertEquals(2, start.get("version").getAsInt());
        StringBuilder hex = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.toPath()))) {
            hex.append(String.format("%02x", x & 0xff));
        }
        assertEquals(hex.toString(), start.get("digest").getAsString());
    }

    /** A person's word adds, grows and raises; a lower top, a smaller outline or a touchable top needs a new record. */
    @Test
    public void freeingSpaceNeedsAMeasurementRecord() throws Exception {
        start(true);
        JsonObject added = ok("POST", "/api/bed/objects", "{\"name\":\"A reel\",\"outline\":{\"box\":[300,400,340,440]}}");
        assertEquals(2, added.get("version").getAsInt());
        JsonObject reel = object("a-reel");
        assertEquals("added", reel.get("state").getAsString());
        assertTrue(reel.get("topZ").isJsonNull());
        assertEquals(1.0, reel.get("marginMm").getAsDouble(), 0, "Process's X/Y margin");
        JsonObject row = rows(BedModelStore.EVENT).get(0);
        assertEquals("POST /api/bed/objects", row.get("what").getAsString());
        assertTrue(row.get("before").isJsonNull());
        assertEquals("a-reel", row.getAsJsonObject("after").get("id").getAsString());
        call("POST", "/api/bed/objects", "{\"name\":\"x\",\"outline\":{\"box\":[1,1,2,2]},\"topZ\":3}", UI);
        assertEquals(400, status, "an added object has no top by a person's word: " + body);
        // raise the holder's top, grow its outline: a person's word
        JsonObject h = new JsonParser().parse(objectWith("holder-left", "topZ", "15")).getAsJsonObject();
        ok("PUT", "/api/bed/objects/holder-left", h.toString());
        assertEquals(3, version());
        h.add("outline", new JsonParser().parse("{\"box\":[9,94,31,116]}"));
        ok("PUT", "/api/bed/objects/holder-left", h.toString());
        assertEquals(4, version());
        // lower it: the stored record is not a new measurement
        h.addProperty("topZ", 13);
        refused(409, "measurement-needed", "measured.top", call("PUT", "/api/bed/objects/holder-left", h.toString(), UI));
        assertEquals(4, version(), "nothing written");
        assertEquals(15.0, object("holder-left").get("topZ").getAsDouble(), 0);
        // a new record naming evidence that does not exist
        h.getAsJsonObject("measured").add("top", new JsonParser().parse(record("n1-touch", "event:99999999")));
        refused(409, "measurement-needed", "99999999", call("PUT", "/api/bed/objects/holder-left", h.toString(), UI));
        h.getAsJsonObject("measured").add("top", new JsonParser().parse(record("n1-touch", "photo:99999999")));
        refused(409, "measurement-needed", "99999999", call("PUT", "/api/bed/objects/holder-left", h.toString(), UI));
        // a new record naming a journal event: lowered
        h.getAsJsonObject("measured").add("top", new JsonParser().parse(record("n1 on top", evidence())));
        ok("PUT", "/api/bed/objects/holder-left", h.toString());
        assertEquals(13.0, object("holder-left").get("topZ").getAsDouble(), 0);
        assertEquals(5, version());
        // an older record sent again is not a new measurement
        h.addProperty("topZ", 12.5);
        h.getAsJsonObject("measured").add("top", BedFixture.json().getAsJsonArray("objects").get(0).getAsJsonObject()
                .getAsJsonObject("measured").get("top"));
        refused(409, "measurement-needed", "measured.top", call("PUT", "/api/bed/objects/holder-left", h.toString(), UI));
        h.addProperty("topZ", 13);
        // a smaller outline needs measured.xy; a touchable top needs measured.top
        refused(409, "measurement-needed", "measured.xy", call("PUT", "/api/bed/objects/blank-front",
                objectWith("blank-front", "outline", "{\"box\":[61,121,159,199]}"), UI));
        h.addProperty("touchable", true);
        refused(409, "measurement-needed", "measured.top", call("PUT", "/api/bed/objects/holder-left", h.toString(), UI));
        // Process's per-part methods: any words for how
        JsonObject cal = new JsonParser().parse(objectWith("height-switch", "topZ", "11.5")).getAsJsonObject();
        cal.getAsJsonObject("measured").add("top", new JsonParser().parse(record("caliper height + margin",
                evidence())));
        ok("PUT", "/api/bed/objects/height-switch", cal.toString());
        // never invalid: refused with the problem, nothing written
        refused(400, "bad-request", "convex", call("POST", "/api/bed/objects", "{\"name\":\"bad\",\"outline\":{\"polygon\":"
                + "[[0,0],[10,0],[5,2],[10,10],[0,10]]}}", UI));
        assertEquals(6, version());
        call("PUT", "/api/bed/objects/nothing", objectWith("holder-left", "id", "\"nothing\""), UI);
        assertEquals(404, status, body);
    }

    /**
     * Moved keeps the old entry blocking and adds the new place unmeasured; removed blocks until both checks clear it
     * (the top camera against the empty-bed reference, N1 to its floor at 3 points with no contact; Process 20:5x).
     */
    @Test
    public void aRemovedObjectIsClearedOnlyByBothMeasurements() throws Exception {
        start(true);
        String early = evidence();
        Thread.sleep(5);
        ok("POST", "/api/bed/objects/blank-front/moved", "{\"outline\":{\"box\":[60,210,160,290]}}");
        assertEquals("moved", object("blank-front").get("state").getAsString());
        JsonObject moved = object("blank-front-2");
        assertEquals("added", moved.get("state").getAsString());
        assertEquals("[60,210,160,290]", moved.getAsJsonObject("outline").get("box").toString(), "kept as written");
        assertEquals(3, ok("GET", "/api/status", null).getAsJsonObject("bed").get("unmeasured").getAsInt());
        ok("POST", "/api/bed/objects/blank-front/removed", "{}");
        assertEquals("removed, not checked", object("blank-front").get("stateLabel").getAsString());
        // a removed object stops blocking only through /cleared, never by a PUT
        JsonObject back = new JsonParser().parse(objectWith("blank-front", "state", "\"measured\"")).getAsJsonObject();
        back.getAsJsonObject("measured").add("top", new JsonParser().parse(record("n1-touch", evidence())));
        back.getAsJsonObject("measured").add("xy", new JsonParser().parse(record("camera-edges", evidence())));
        refused(409, "measurement-needed", "cleared", call("PUT", "/api/bed/objects/blank-front", back.toString(), UI));
        String camera = "\"camera\":{\"reference\":\"empty-bed:blank-front\",\"diff\":0.8,\"threshold\":2.0,\"evidence\":\""
                + evidence() + "\"}";
        String n1 = "\"n1\":{\"points\":3,\"floorRaw\":0.5,\"evidence\":\"" + evidence() + "\"}";
        int v = version();
        // the threshold is the engine's (Process's number), never the caller's: none set yet, nothing clears (#66 c)
        refused(409, "measurement-needed", "emptyBedMaxDiff", call("POST", "/api/bed/objects/blank-front/cleared", "{"
                + camera + "," + n1 + "}", UI));
        ok("PUT", "/api/config", "{\"emptyBedMaxDiff\":2.0}");
        refused(409, "measurement-needed", "threshold", call("POST", "/api/bed/objects/blank-front/cleared", "{"
                + camera.replace("\"diff\":0.8,\"threshold\":2.0", "\"diff\":2.5,\"threshold\":3.0") + "," + n1 + "}", UI));
        // a measurement from before the removal does not clear it
        refused(409, "measurement-needed", "older than", call("POST", "/api/bed/objects/blank-front/cleared", "{"
                + camera.replaceAll("event:\\d+", early) + "," + n1 + "}", UI));
        refused(409, "measurement-needed", "n1", call("POST", "/api/bed/objects/blank-front/cleared", "{" + camera + "}",
                UI));
        refused(409, "measurement-needed", "camera", call("POST", "/api/bed/objects/blank-front/cleared", "{" + n1 + "}",
                UI));
        refused(409, "measurement-needed", "threshold", call("POST", "/api/bed/objects/blank-front/cleared", "{"
                + camera.replace("\"diff\":0.8", "\"diff\":2.5") + "," + n1 + "}", UI));
        refused(409, "measurement-needed", "3 points", call("POST", "/api/bed/objects/blank-front/cleared", "{" + camera
                + "," + n1.replace("\"points\":3", "\"points\":2") + "}", UI));
        assertEquals(v, version(), "still removed, nothing written");
        assertEquals("removed", object("blank-front").get("state").getAsString());
        ok("POST", "/api/bed/objects/blank-front/cleared", "{" + camera + "," + n1 + "}");
        assertNull(object("blank-front"), "cleared: it left the model");
        JsonObject row = rows(BedModelStore.EVENT).get(0);
        assertEquals("blank-front", row.getAsJsonObject("before").get("id").getAsString());
        assertEquals(3, row.getAsJsonObject("after").getAsJsonObject("cleared").getAsJsonObject("n1").get("points")
                .getAsInt());
        assertEquals(2.0, row.getAsJsonObject("after").getAsJsonObject("cleared").getAsJsonObject("camera").get(
                "threshold").getAsDouble(), 0, "the engine's threshold on record");
        // only a removed object is cleared
        refused(409, "refused", "removed", call("POST", "/api/bed/objects/holder-left/cleared", "{" + camera + "," + n1
                + "}", UI));
        // the new place: measured only with both a new top and a new outline record
        JsonObject m = new JsonParser().parse(object("blank-front-2").toString()).getAsJsonObject();
        m.remove("stateLabel");
        m.addProperty("state", "measured");
        m.addProperty("topZ", 8.3);
        JsonObject rec = new JsonObject();
        rec.add("top", new JsonParser().parse(record("n1-touch", evidence())));
        m.add("measured", rec);
        refused(409, "measurement-needed", "measured.xy", call("PUT", "/api/bed/objects/blank-front-2", m.toString(), UI));
        rec.add("xy", new JsonParser().parse(record("camera-edges", evidence())));
        ok("PUT", "/api/bed/objects/blank-front-2", m.toString());
        assertEquals("measured", object("blank-front-2").get("stateLabel").getAsString());
    }

    /** Tools, the plane and the see-saw: growing a layer is a person's word; the rest needs a new record. */
    @Test
    public void toolsPlaneAndSeesawChangeWithRecords() throws Exception {
        start(true);
        JsonObject needle = BedFixture.json().getAsJsonArray("tools").get(1).getAsJsonObject();
        layer(needle, 1).getAsJsonObject("outline").add("circle", new JsonParser().parse("[25.291,-75.926,7]"));
        ok("PUT", "/api/bed/tools/needle", needle.toString());
        layer(needle, 1).getAsJsonObject("outline").add("circle", new JsonParser().parse("[25.291,-75.926,5]"));
        refused(409, "measurement-needed", "tools", call("PUT", "/api/bed/tools/needle", needle.toString(), UI));
        needle.add("measured", new JsonParser().parse(record("caliper", evidence())));
        ok("PUT", "/api/bed/tools/needle", needle.toString());
        JsonObject plane = BedFixture.json().getAsJsonObject("bed").getAsJsonObject("plane");
        plane.add("points", new JsonParser().parse("[[60,120,-3.1],[300,120,-3.06],[300,300,-2.99],[60,300,-3.03]]"));
        refused(409, "measurement-needed", "bed.plane", call("PUT", "/api/bed/plane", plane.toString(), UI));
        plane.addProperty("atMs", System.currentTimeMillis());
        plane.addProperty("evidence", evidence());
        ok("PUT", "/api/bed/plane", plane.toString());
        JsonObject seesaw = BedFixture.json().getAsJsonObject("seesaw");
        seesaw.getAsJsonObject("K").addProperty("raw", 64.9);
        refused(409, "measurement-needed", "seesaw.measured", call("PUT", "/api/bed/seesaw", seesaw.toString(), UI));
        seesaw.add("measured", new JsonParser().parse(record("n1-touch", evidence())));
        JsonObject bed = ok("PUT", "/api/bed/seesaw", seesaw.toString());
        assertEquals(64.9, bed.getAsJsonObject("seesaw").getAsJsonObject("K").get("raw").getAsDouble(), 0);
        assertEquals(5, bed.get("version").getAsInt());
    }

    /**
     * A record's evidence is a measurement's own: a journal row or photo at or after the stored record's time, never a
     * bed-model or motion row (#66 review MINOR c).
     */
    @Test
    public void evidenceIsANewMeasurementsOwnRow() throws Exception {
        start(true);
        String first = evidence();
        Thread.sleep(5);
        JsonObject h = new JsonParser().parse(objectWith("holder-left", "topZ", "13")).getAsJsonObject();
        h.getAsJsonObject("measured").add("top", new JsonParser().parse(record("n1 on top", first)));
        ok("PUT", "/api/bed/objects/holder-left", h.toString());
        Thread.sleep(5);
        // lowered again on a newer record naming the first measurement's row, which is older than the stored record
        h.addProperty("topZ", 12.5);
        h.getAsJsonObject("measured").add("top", new JsonParser().parse(record("n1 on top", first)));
        refused(409, "measurement-needed", "older than", call("PUT", "/api/bed/objects/holder-left", h.toString(), UI));
        // a bed-model row or a motion proof is no measurement
        for (String code : new String[] {BedModelStore.EVENT, "MOTION_PROOF"}) {
            svc.getDb().event(null, "INFO", code, "{}");
            h.getAsJsonObject("measured").add("top", new JsonParser().parse(record("n1 on top", "event:" + svc.getDb()
                    .recentEvents(1).get(0).get("id"))));
            refused(409, "measurement-needed", "not a measurement", call("PUT", "/api/bed/objects/holder-left",
                    h.toString(), UI));
        }
        h.getAsJsonObject("measured").add("top", new JsonParser().parse(record("n1 on top", evidence())));
        ok("PUT", "/api/bed/objects/holder-left", h.toString());
        assertEquals(12.5, object("holder-left").get("topZ").getAsDouble(), 0);
    }

    /** Writes are the lock holder's and never while anything moves. */
    @Test
    public void writesAreTheHoldersAndNeverWhileMoving() throws Exception {
        start(true);
        // nobody holds the lock: a write with neither a token nor the UI's header is refused (#66 review MINOR d)
        refused(409, "no-lock", "lock holder", call("POST", "/api/bed/objects/blank-front/removed", "{}", null));
        assertEquals(1, version(), "nothing written");
        String token = new JsonParser().parse(postAcquire()).getAsJsonObject().get("token").getAsString();
        refused(409, "locked", "", call("POST", "/api/bed/objects/blank-front/removed", "{}", UI));
        sim.server.delayBeforeOkMs.put("M400", 3000L);
        CompletableFuture<Integer> batch = CompletableFuture.supplyAsync(() -> {
            try {
                call("POST", "/api/gcode", "{\"lines\":[\"G1 X120 F600\",\"M400\"]}", token);
                return status;
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long t0 = System.currentTimeMillis();
        while (!api.getContext().passthrough.running() && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(10);
        }
        JsonObject r = call("POST", "/api/bed/objects/blank-front/removed", "{}", token);
        refused(409, "refused", "moving", r);
        assertEquals(200, (int) batch.get());
        assertFalse("removed".equals(object("blank-front").get("state").getAsString()));
        call("POST", "/api/bed/objects/blank-front/removed", "{}", token);
        assertEquals(200, status, body);
        assertEquals("process", rows(BedModelStore.EVENT).get(0).get("who").getAsString());
    }

    /**
     * Two writes at once, each built from the model it read: the second to write is refused (read it again) and the
     * first's change is kept; before, the later one stamped its version over the other's change (#66 review MAJOR 2).
     */
    @Test
    public void aWriteFromAModelThatChangedMeanwhileIsRefused() throws Exception {
        start(true);
        CountDownLatch read = new CountDownLatch(1), go = new CountDownLatch(1);
        BedEndpoints.writeHook = () -> {
            read.countDown();
            try {
                go.await(30, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        CompletableFuture<String> reel;
        try {
            reel = CompletableFuture.supplyAsync(() -> {
                try {
                    call("POST", "/api/bed/objects", "{\"name\":\"A reel\",\"outline\":{\"box\":[300,400,340,440]}}", UI);
                    return status + " " + body;
                }
                catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            assertTrue(read.await(30, TimeUnit.SECONDS), "the first write read version 1 and passed its checks");
            BedEndpoints.writeHook = null;
            ok("POST", "/api/bed/objects", "{\"name\":\"A tray\",\"outline\":{\"box\":[350,400,390,440]}}");
        }
        finally {
            BedEndpoints.writeHook = null;
            go.countDown();
        }
        String first = reel.get(60, TimeUnit.SECONDS);
        assertTrue(first.startsWith("409 ") && first.contains("changed meanwhile"), first);
        assertNotNull(object("a-tray"), "the write that went first is kept");
        assertNull(object("a-reel"), "the refused one wrote nothing");
        assertEquals(2, version());
    }

    String postAcquire() throws Exception {
        call("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"bed test\"}", null);
        return body;
    }

    static JsonObject layer(JsonObject tool, int i) {
        JsonArray a = tool.getAsJsonArray("layers");
        return a.get(i).getAsJsonObject();
    }
}
