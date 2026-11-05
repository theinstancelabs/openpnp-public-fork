package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.ControllerCommands;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.FlatVacuum;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Configuration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-72 on the simulated controller (OpenPnP's GcodeDriver over TCP to RecordingGcodeServer, the real engine and API):
 * the lock, Take control and the G-code passthrough as docs/backend-requests.md "BR-72 API" describes them. The sim's
 * DISABLE_COMMAND is the machine's (the Stop sequence).
 */
public class LockApiSimTest {
    static final String UI = "ui";

    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine();
        FlatVacuum.install(sim, FlatVacuum.scene(5.5, null, 0));
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND, String.join("\n",
                ControllerCommands.STOP_SEQUENCE));
        svc = DispenseService.start(Configuration.get());
        assertTrue(svc.isAvailable(), String.valueOf(svc.getUnavailableReason()));
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    public void after() throws Exception {
        sim.server.delayBeforeOkMs.clear();
        api.stop();
        svc.shutdown();
        sim.close();
    }

    static final class Res {
        final int status;
        final JsonObject json;

        Res(HttpResponse<String> r) {
            status = r.statusCode();
            JsonElement e = new JsonParser().parse(r.body().isEmpty() ? "{}" : r.body());
            json = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        }

        String error() {
            return json.has("error") ? json.get("error").getAsString() : null;
        }

        @Override
        public String toString() {
            return status + " " + json;
        }
    }

    /** {@code who}: "ui" (the web UI), a lock token (an agent), or null (anonymous: CI, the deployer, curl). */
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

    JsonObject ok(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.json;
    }

    void assertError(int status, String error, Res r) {
        assertEquals(status, r.status, r.toString());
        assertEquals(error, r.error(), r.toString());
    }

    JsonObject lock() throws Exception {
        return ok(call("GET", "/api/lock", null, null));
    }

    JsonObject holder() throws Exception {
        JsonElement h = lock().get("holder");
        return h.isJsonNull() ? null : h.getAsJsonObject();
    }

    JsonObject status() throws Exception {
        return ok(call("GET", "/api/status", null, null));
    }

    String acquire(String what) throws Exception {
        return ok(post("/api/lock/acquire", "{\"who\":\"process\",\"what\":\"" + what + "\",\"thread\":\"mcp:test\"}",
                null)).get("token").getAsString();
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

    static List<String> commands(List<String> lines) {
        List<String> l = new ArrayList<>();
        for (String s : lines) {
            if (!s.startsWith(";")) {
                l.add(s.contains(";") ? s.substring(0, s.indexOf(';')).trim() : s);
            }
        }
        return l;
    }

    static final String NEEDLE_OFFSET = "{\"needleChangedAtMs\":null,\"offsetMm\":0.01}";

    /**
     * After a STOP: waits until the machine reads off, so the teardown does not close the simulated controller while
     * OpenPnP still sends its DISABLE_COMMAND lines (each would then wait the driver's timeout: a 200 s test, 11-03).
     */
    void awaitOff() throws Exception {
        long t0 = System.currentTimeMillis();
        while (status().getAsJsonObject("machine").get("enabled").getAsBoolean()
                && System.currentTimeMillis() - t0 < 20000) {
            Thread.sleep(50);
        }
        assertFalse(status().getAsJsonObject("machine").get("enabled").getAsBoolean(), "off 20 s after the STOP");
    }

    @Test
    public void anAgentTakesTheLockAndDrivesTheMachineThroughThePassthrough() throws Exception {
        assertTrue(lock().get("holder").isJsonNull());
        assertEquals(lock(), status().getAsJsonObject("lock"), "GET /api/lock is status.lock");
        String token = acquire("27G quarter map");
        JsonObject h = holder();
        assertEquals("agent", h.get("kind").getAsString());
        assertEquals("process", h.get("who").getAsString());
        assertEquals("27G quarter map", h.get("what").getAsString());
        assertEquals(lock(), status().getAsJsonObject("lock"));
        // a line and its replies, verbatim
        sim.server.mark();
        JsonObject r = ok(gcode(token, "M114", "G1 X120 Y80 F6000 ; to the coupon", "M400"));
        JsonArray results = r.getAsJsonArray("results");
        assertEquals(3, results.size());
        assertEquals("M114", results.get(0).getAsJsonObject().get("line").getAsString());
        assertTrue(results.get(0).getAsJsonObject().getAsJsonArray("replies").get(0).getAsString().startsWith("X:"),
                r.toString());
        assertEquals("G1 X120 Y80 F6000", results.get(1).getAsJsonObject().get("line").getAsString());
        List<String> sent = commands(sim.server.sinceMark());
        assertEquals(Arrays.asList("M114", "G1 X120 Y80 F6000", "M400"), sent.subList(0, 3), sent.toString());
        // OpenPnP knows where the head is after the passthrough moved it (test 10)
        JsonObject machine = ok(call("GET", "/api/machine", null, null));
        for (JsonElement a : machine.getAsJsonArray("axes")) {
            JsonObject ax = a.getAsJsonObject();
            if (ax.has("letter") && "X".equals(ax.get("letter").getAsString())) {
                assertEquals(120.0, ax.get("driverCoordinate").getAsDouble(), 1e-6, machine.toString());
            }
            if (ax.has("letter") && "Y".equals(ax.get("letter").getAsString())) {
                assertEquals(80.0, ax.get("driverCoordinate").getAsDouble(), 1e-6, machine.toString());
            }
        }
        // everyone else: 409 locked, with the lock (test 2); a second agent too; STOP never needs the lock
        Res other = post("/api/lock/acquire", "{\"who\":\"infra\",\"what\":\"deploy\"}", null);
        assertError(409, "locked", other);
        Res uiHome = post("/api/machine/home", "{\"wait\":true}", UI);
        assertError(409, "locked", uiHome);
        assertTrue(uiHome.json.get("reason").getAsString().contains("Take control"), uiHome.toString());
        assertEquals("process", uiHome.json.getAsJsonObject("lock").getAsJsonObject("holder").get("who")
                .getAsString());
        assertError(409, "locked", post("/api/machine/home", "{\"wait\":true}", null));
        assertError(409, "locked", call("PUT", "/api/config", "{}", null));
        // BR-77: the start-angle measure is a guarded write too
        assertError(409, "locked", post("/api/screw/start-angle/measure", "{\"mode\":\"halves\"}", null));
        // data writes and GETs never need it
        assertEquals(200, call("GET", "/api/status", null, UI).status);
        // its own API calls pass with its token
        ok(post("/api/needle/offset", NEEDLE_OFFSET, token));
        // refused lines: nothing is sent (test 6)
        sim.server.mark();
        for (String bad : new String[] {"G28", "G92 X0", "M18", "G1 X500", "M106 P2 S128", "M99999999999"}) {
            Res refused = gcode(token, "M114", bad);
            assertError(400, "refused-line", refused);
            assertEquals(1, refused.json.get("index").getAsInt(), refused.toString());
        }
        assertEquals(Collections.emptyList(), commands(sim.server.sinceMark()));
        // keepalive renews and updates what; release: nobody
        JsonObject kept = ok(post("/api/lock/keepalive", "{\"what\":\"27G quarter map, dot 7\"}", token));
        assertEquals("27G quarter map, dot 7", kept.getAsJsonObject("holder").get("what").getAsString());
        ok(post("/api/lock/release", "{}", token));
        assertEquals(null, holder());
        Res lost = gcode(token, "M114");
        assertError(409, "lock-lost", lost);
        assertEquals("released", lost.json.getAsJsonObject("lost").get("how").getAsString());
    }

    @Test
    public void takeControlDuringABatchStopsLikeStopThenPassesTheLock() throws Exception {
        String token = acquire("feed step check");
        ok(gcode(token, "G1 X50 Y50 F6000", "M400"));
        // a long move: the M400 answers after 3 s
        sim.server.delayBeforeOkMs.put("M400", 3000L);
        sim.server.mark();
        CompletableFuture<Res> batch = CompletableFuture.supplyAsync(() -> {
            try {
                return gcode(token, "G1 X200 Y200 F600", "M400", "G1 X10 Y10 F600", "M400");
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long t0 = System.currentTimeMillis();
        while (!commands(sim.server.sinceMark()).contains("M400") && System.currentTimeMillis() - t0 < 10000) {
            Thread.sleep(10);
        }
        assertTrue(holder().get("busy").getAsBoolean(), "a batch runs");
        Res take = post("/api/lock/take", "{}", UI);
        JsonObject t = ok(take);
        JsonObject stopped = t.getAsJsonObject("stopped");
        assertEquals("stop", stopped.get("mode").getAsString(), take.toString());
        assertTrue(stopped.get("machineOff").getAsBoolean(), take.toString());
        assertEquals("Process", stopped.get("label").getAsString());
        assertEquals("feed step check", stopped.get("what").getAsString());
        // the user reads actions verbatim (UI, 11-03): plain phrases; the technical lines are in detail
        String acts = stopped.getAsJsonArray("actions").toString();
        assertTrue(acts.contains("its G-code batch stopped") && acts.contains("the machine switched off"), acts);
        assertFalse(acts.contains("DISABLE_COMMAND") || acts.contains("emergency") || acts.contains("M410"), acts);
        assertTrue(stopped.getAsJsonArray("detail").toString().contains("DISABLE_COMMAND"), stopped.toString());
        assertEquals("user", t.getAsJsonObject("lock").getAsJsonObject("holder").get("kind").getAsString());
        // the batch ended lock-lost and sent nothing after its line in flight
        Res b = batch.get();
        assertError(409, "lock-lost", b);
        assertEquals("taken", b.json.getAsJsonObject("lost").get("how").getAsString());
        List<String> sent = commands(sim.server.sinceMark());
        assertFalse(sent.contains("G1 X10 Y10 F600"), sent.toString());
        int stop = Collections.indexOfSubList(sent, ControllerCommands.STOP_SEQUENCE);
        assertTrue(stop >= 0, "the Stop sequence in one piece: " + sent);
        for (String l : sent.subList(stop, sent.size())) {
            assertFalse(l.startsWith("G0") || l.startsWith("G1"), "no move after the Stop: " + sent);
        }
        JsonObject m = status().getAsJsonObject("machine");
        assertFalse(m.get("enabled").getAsBoolean());
        assertFalse(m.get("homed").getAsBoolean(), "a STOP un-homes: Start, then Home");
        JsonObject from = lock().getAsJsonObject("takenFrom");
        assertEquals("process", from.get("who").getAsString());
        assertEquals("user", from.get("by").getAsString());
        // the agent: lock-lost from now on, and no acquire while the user holds it
        Res next = gcode(token, "M114");
        assertError(409, "lock-lost", next);
        assertEquals("taken", next.json.getAsJsonObject("lost").get("how").getAsString());
        assertError(409, "locked", post("/api/lock/acquire", "{\"who\":\"process\",\"what\":\"again\"}", null));
        // the user's controls work: Start, Home
        sim.server.delayBeforeOkMs.clear();
        ok(post("/api/machine/enable", "{}", UI));
        ok(post("/api/machine/home", "{\"wait\":true}", UI));
        // Give back: nobody
        ok(post("/api/lock/release", "{}", UI));
        assertEquals(null, holder());
        assertTrue(lock().get("takenFrom").isJsonNull(), "cleared when the lock changes hands");
    }

    /**
     * Infra's lumen-sim e2e (11-03 16:30): a take during a batch's LAST line (the M400 of a long move) answered 200: the
     * M400's ok came from the STOP's flush, not from the head reaching its target. The batch must answer lock-lost, with
     * the line in flight marked interrupted, so the agent never assumes the move completed.
     */
    @Test
    public void takeControlDuringABatchsLastLineAnswersLockLostNotDone() throws Exception {
        String token = acquire("one long move");
        sim.server.delayBeforeOkMs.put("M400", 3000L);
        sim.server.mark();
        CompletableFuture<Res> batch = CompletableFuture.supplyAsync(() -> {
            try {
                return gcode(token, "G1 X200 Y200 F600", "M400");
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long t0 = System.currentTimeMillis();
        while (!commands(sim.server.sinceMark()).contains("M400") && System.currentTimeMillis() - t0 < 10000) {
            Thread.sleep(10);
        }
        ok(post("/api/lock/take", "{}", UI));
        Res b = batch.get();
        assertError(409, "lock-lost", b);
        assertEquals("taken", b.json.getAsJsonObject("lost").get("how").getAsString(), b.toString());
        JsonArray results = b.json.getAsJsonArray("results");
        assertEquals(2, results.size(), b.toString());
        JsonObject last = results.get(1).getAsJsonObject();
        assertEquals("M400", last.get("line").getAsString());
        assertTrue(last.has("interrupted") && last.get("interrupted").getAsBoolean(), b.toString());
        assertEquals(1, b.json.get("index").getAsInt(), "the line in flight: " + b);
    }

    /** Process's client on Infra's SIM (11-03 17:10): a web STOP during a batch's last line answered 200 too. */
    @Test
    public void aStopDuringABatchsLastLineAnswersStoppedNotDone() throws Exception {
        String token = acquire("one long move");
        sim.server.delayBeforeOkMs.put("M400", 3000L);
        sim.server.mark();
        CompletableFuture<Res> batch = CompletableFuture.supplyAsync(() -> {
            try {
                return gcode(token, "G1 X70 Y220 F300", "M400");
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long t0 = System.currentTimeMillis();
        while (!commands(sim.server.sinceMark()).contains("M400") && System.currentTimeMillis() - t0 < 10000) {
            Thread.sleep(10);
        }
        ok(post("/api/stop", null, null));
        Res b = batch.get();
        assertError(409, "stopped", b);
        JsonObject last = b.json.getAsJsonArray("results").get(1).getAsJsonObject();
        assertEquals("M400", last.get("line").getAsString());
        assertTrue(last.get("interrupted").getAsBoolean(), b.toString());
        assertEquals("process", holder().get("who").getAsString(), "a STOP does not take the lock");
        awaitOff();
    }

    @Test
    public void takeControlFromAnIdleAgentSwitchesTheOutputsOffAndKeepsTheMachineHomed() throws Exception {
        String token = acquire("pressure check");
        ok(gcode(token, "G1 X60 Y40 F6000", "M400", "M106 P2 S255"));
        sim.server.mark();
        JsonObject t = ok(post("/api/lock/take", "{}", UI));
        JsonObject stopped = t.getAsJsonObject("stopped");
        assertEquals("outputs-off", stopped.get("mode").getAsString(), t.toString());
        assertFalse(stopped.get("machineOff").getAsBoolean());
        assertEquals("[\"valve off\",\"pump off\",\"N1's valve off\",\"N1's pump off\"]",
                stopped.getAsJsonArray("actions").toString());
        assertTrue(stopped.getAsJsonArray("detail").toString().contains("M107 P3"), stopped.toString());
        assertEquals(Arrays.asList("M107 P3", "M107 P2", "M107 P1", "M107 P0"), commands(sim.server.sinceMark()));
        JsonObject m = status().getAsJsonObject("machine");
        assertTrue(m.get("enabled").getAsBoolean());
        assertTrue(m.get("homed").getAsBoolean());
        ok(post("/api/machine/home", "{\"wait\":true}", UI));
        assertError(409, "lock-lost", gcode(token, "M114"));
    }

    @Test
    public void nobodyHoldsItTheUisFirstWriteMakesTheUserTheHolder() throws Exception {
        // anonymous writes pass and take nothing (CI, the deployer)
        ok(post("/api/needle/offset", NEEDLE_OFFSET, null));
        assertEquals(null, holder());
        // the passthrough needs a holder
        assertError(409, "no-lock", gcode(null, "M114"));
        // the UI's first guarded write: the user holds it
        ok(post("/api/needle/offset", NEEDLE_OFFSET, UI));
        assertEquals("user", holder().get("kind").getAsString());
        assertError(409, "locked", post("/api/needle/offset", NEEDLE_OFFSET, null));
        assertError(409, "locked", post("/api/lock/acquire", "{\"who\":\"process\",\"what\":\"x\"}", null));
        // the user may use the passthrough too
        ok(gcode(UI, "M114"));
        // take is the UI's only
        assertError(403, "not-ui", post("/api/lock/take", "{}", null));
        // STOP is everyone's, always
        ok(post("/api/stop", null, null));
        assertEquals("user", holder().get("kind").getAsString(), "a STOP does not change the holder");
        awaitOff();
    }

    @Test
    public void aStopDuringABatchEndsItAndTheAgentKeepsTheLock() throws Exception {
        String token = acquire("dots");
        sim.server.delayBeforeOkMs.put("M400", 2000L);
        sim.server.mark();
        CompletableFuture<Res> batch = CompletableFuture.supplyAsync(() -> {
            try {
                return gcode(token, "G1 X100 Y100 F600", "M400", "G1 X20 Y20 F600", "M400");
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long t0 = System.currentTimeMillis();
        while (!commands(sim.server.sinceMark()).contains("M400") && System.currentTimeMillis() - t0 < 10000) {
            Thread.sleep(10);
        }
        ok(post("/api/stop", null, null));
        Res b = batch.get();
        assertError(409, "stopped", b);
        assertFalse(commands(sim.server.sinceMark()).contains("G1 X20 Y20 F600"));
        assertEquals("process", holder().get("who").getAsString());
        awaitOff();
    }

    @Test
    public void aLapsedLeaseSwitchesTheOutputsOffAndFreesTheLock() throws Exception {
        String token = ok(post("/api/lock/acquire", "{\"who\":\"process\",\"what\":\"dots\",\"ttlS\":30}", null))
                .get("token").getAsString();
        ok(gcode(token, "M106 P3 S255"));
        sim.server.mark();
        api.getContext().lockClockSkewMs.set(31_000L);
        long t0 = System.currentTimeMillis();
        while (holder() != null && System.currentTimeMillis() - t0 < 10000) {
            Thread.sleep(50);
        }
        assertEquals(null, holder());
        assertEquals(Arrays.asList("M107 P3", "M107 P2", "M107 P1", "M107 P0"), commands(sim.server.sinceMark()));
        Res lost = gcode(token, "M114");
        assertError(409, "lock-lost", lost);
        assertEquals("expired", lost.json.getAsJsonObject("lost").get("how").getAsString());
        assertTrue(status().getAsJsonObject("machine").get("homed").getAsBoolean());
    }

    /**
     * #19 review MAJOR 3: while a take still waits for the agent's home and stops its work, another window's write
     * answers 409 taking-over (nothing of the user's lands inside the agent's home or under the STOP), and a second
     * take joins the first: one stop, the same answer.
     */
    @Test
    public void duringTheHandoverAnotherWindowWaitsAndASecondTakeJoins() throws Exception {
        String token = acquire("re-home");
        sim.server.delayBeforeOkMs.put("G28", 3000L);
        assertEquals(202, post("/api/machine/home", "{}", token).status);
        long t0 = System.currentTimeMillis();
        while (!status().getAsJsonObject("machine").get("homing").getAsBoolean()
                && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(10);
        }
        CompletableFuture<Res> first = CompletableFuture.supplyAsync(() -> {
            try {
                return post("/api/lock/take", "{}", UI);
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        t0 = System.currentTimeMillis();
        while (!lock().get("handover").getAsBoolean() && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(10);
        }
        assertTrue(lock().get("handover").getAsBoolean(), lock().toString());
        Res other = post("/api/needle/offset", NEEDLE_OFFSET, UI);
        assertError(409, "taking-over", other);
        Res second = post("/api/lock/take", "{}", UI);
        Res one = first.get();
        JsonObject a = ok(one), b = ok(second);
        assertEquals(a.get("atMs"), b.get("atMs"), "the second take joined the first: " + b);
        assertEquals("Process", b.getAsJsonObject("stopped").get("label").getAsString(), b.toString());
        assertFalse(lock().get("handover").getAsBoolean());
        ok(post("/api/needle/offset", NEEDLE_OFFSET, UI));
    }

    /** #19 review MAJOR 2: an agent's own task (a 202 home here) keeps its lease alive: no STOP in the middle of it. */
    @Test
    public void theLeaseDoesNotLapseWhileTheAgentsTaskRuns() throws Exception {
        String token = ok(post("/api/lock/acquire", "{\"who\":\"process\",\"what\":\"a home\",\"ttlS\":30}", null))
                .get("token").getAsString();
        sim.server.delayBeforeOkMs.put("G28", 3000L);
        assertEquals(202, post("/api/machine/home", "{}", token).status);
        // the lease check (every second) sees the home as the agent's work: busy, and the lease renewed
        long t0 = System.currentTimeMillis();
        while (!holder().get("busy").getAsBoolean() && System.currentTimeMillis() - t0 < 2500) {
            Thread.sleep(50);
        }
        assertTrue(holder().get("busy").getAsBoolean(), "its home shows as work");
        api.getContext().lockClockSkewMs.set(31_000L);
        t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < 1500) {
            assertEquals("process", holder().get("who").getAsString(), "lapsed during its home");
            Thread.sleep(100);
        }
        t0 = System.currentTimeMillis();
        while (status().getAsJsonObject("machine").get("homing").getAsBoolean() && System.currentTimeMillis() - t0 < 10000) {
            Thread.sleep(50);
        }
        Thread.sleep(1500);
        assertEquals("process", holder().get("who").getAsString(), "the lease counts from the end of its work");
        assertTrue(status().getAsJsonObject("machine").get("homed").getAsBoolean(), "never stopped");
    }

    @Test
    public void takeControlWaitsForAHomeInProgress() throws Exception {
        String token = acquire("re-home");
        sim.server.delayBeforeOkMs.put("G28", 2500L);
        assertEquals(202, post("/api/machine/home", "{}", token).status);
        long t0 = System.currentTimeMillis();
        while (!status().getAsJsonObject("machine").get("homing").getAsBoolean()
                && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(10);
        }
        long t1 = System.currentTimeMillis();
        JsonObject t = ok(post("/api/lock/take", "{}", UI));
        assertTrue(System.currentTimeMillis() - t1 >= 1500, "the take waited for the home: " + t);
        assertTrue(t.getAsJsonObject("stopped").toString().contains("home"), t.toString());
        assertEquals("user", t.getAsJsonObject("lock").getAsJsonObject("holder").get("kind").getAsString());
    }
}
