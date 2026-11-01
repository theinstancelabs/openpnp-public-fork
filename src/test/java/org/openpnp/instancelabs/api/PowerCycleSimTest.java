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
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-02 on the simulated controller: a power cycle (stock Marlin boots believing X 0, Y 0, Z 0, B 720) is detected at
 * the connect; the home after it needs the operator's word that nothing is under either tool and sends G28 first, with
 * no Z move on the believed position; every other motion waits for that home. A plain reconnect is not a reset, and a
 * STOP that left N1 low still gets the Z-only raise before G28.
 */
public class PowerCycleSimTest {
    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;
    int lastStatus;
    String lastBody;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine(false);
        svc = DispenseService.start(Configuration.get());
        sim.enableAndHome(); // the service sees this connect
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
        return new JsonParser().parse(r.body()).getAsJsonObject();
    }

    JsonObject ok(String method, String path, String json) throws Exception {
        JsonObject o = call(method, path, json);
        assertTrue(lastStatus >= 200 && lastStatus < 300, lastStatus + " " + o);
        return o;
    }

    /** The controller loses power while connected: the link drops, it boots, and OpenPnP connects again. */
    void powerCycle() throws Exception {
        sim.machine.setEnabled(false);
        sim.server.powerCycle();
        sim.machine.setEnabled(true);
    }

    /** OpenPnP Stop and Start with the controller powered all along. */
    void reconnect() throws Exception {
        sim.machine.setEnabled(false);
        sim.machine.setEnabled(true);
    }

    /** The lines since the mark that move an axis or switch an output. */
    List<String> motionAndOutputs() {
        List<String> out = new ArrayList<>();
        for (String l : sim.server.sinceMark()) {
            if (l.startsWith("G0") || l.startsWith("G1") || l.startsWith("G28") || l.startsWith("M106")) {
                out.add(l);
            }
        }
        return out;
    }

    @Test
    public void aResetIsDetectedAndItsHomeIsG28FirstAfterTheOperatorsWord() throws Exception {
        JsonObject before = ok("GET", "/api/machine", null);
        assertTrue(before.get("positionKnown").getAsBoolean(), before.toString());
        assertFalse(before.getAsJsonObject("powerCycle").get("detected").getAsBoolean(), before.toString());

        powerCycle();
        JsonObject m = ok("GET", "/api/machine", null);
        assertFalse(m.get("positionKnown").getAsBoolean(), m.toString());
        JsonObject pc = m.getAsJsonObject("powerCycle");
        assertTrue(pc.get("detected").getAsBoolean(), m.toString());
        assertFalse(pc.get("atMs").isJsonNull(), m.toString());
        JsonObject rep = pc.getAsJsonObject("reported");
        assertEquals(0, rep.get("x").getAsDouble(), 1e-9);
        assertEquals(0, rep.get("y").getAsDouble(), 1e-9);
        assertEquals(0, rep.get("z").getAsDouble(), 1e-9);
        assertEquals(720, rep.get("b").getAsDouble(), 1e-9);

        // without the operator's word: refused, and nothing is sent
        sim.server.mark();
        call("POST", "/api/machine/home", "{\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("nothing is under either tool"), lastBody);
        assertEquals(new ArrayList<String>(), motionAndOutputs(), "a refused home sends nothing");

        // with it: G28 is the first motion line, no Z move on the believed position before it
        sim.server.mark();
        JsonObject home = ok("POST", "/api/machine/home", "{\"wait\":true,\"clearBelowAttested\":true}");
        assertTrue(home.get("homed").getAsBoolean(), home.toString());
        List<String> l = motionAndOutputs();
        assertFalse(l.isEmpty(), sim.server.sinceMark().toString());
        assertTrue(l.get(0).startsWith("G28"), "G28 first: " + l);
        assertTrue(home.get("n1RaisedFromZ").isJsonNull(), home.toString());

        JsonObject after = ok("GET", "/api/machine", null);
        assertTrue(after.get("positionKnown").getAsBoolean(), after.toString());
        assertTrue(after.getAsJsonObject("powerCycle").get("detected").getAsBoolean(), "the reset stays on record");
    }

    @Test
    public void aReconnectIsNotAResetAndALowN1IsStillRaisedBeforeTheHome() throws Exception {
        // a STOP during a probe left N1 low (raw Z 10, below its safe zone)
        sim.machine.execute(() -> {
            sim.driver.sendCommand("G1 Z10 F600");
            return null;
        });
        reconnect();
        JsonObject m = ok("GET", "/api/machine", null);
        assertTrue(m.get("positionKnown").getAsBoolean(), m.toString());
        assertFalse(m.getAsJsonObject("powerCycle").get("detected").getAsBoolean(), m.toString());

        sim.server.mark();
        JsonObject home = ok("POST", "/api/machine/home", "{\"wait\":true}");
        assertEquals(10, home.get("n1RaisedFromZ").getAsDouble(), 1e-6, home.toString());
        List<String> l = motionAndOutputs();
        int raise = l.indexOf("G1 Z31.5000 F600");
        int g28 = -1;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).startsWith("G28")) {
                g28 = i;
                break;
            }
        }
        assertTrue(raise >= 0 && g28 > raise, "N1 raised (Z only) before G28: " + l);
    }

    @Test
    public void everyOtherMotionWaitsForTheHomeAfterAReset() throws Exception {
        powerCycle();
        sim.server.mark();
        call("POST", "/api/machine/z-check", "{\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("controller was reset"), lastBody);
        call("POST", "/api/pressure/start", null);
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("controller was reset"), lastBody);
        call("POST", "/api/surfaces/blank/probe", "{}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("controller was reset"), lastBody);
        assertEquals(new ArrayList<String>(), motionAndOutputs(), "refused routes send nothing");
        JsonObject status = ok("GET", "/api/status", null);
        assertTrue(status.toString().contains("controller was reset"), "the status names the reason: " + status);

        ok("POST", "/api/machine/home", "{\"wait\":true,\"clearBelowAttested\":true}");
        call("POST", "/api/pressure/start", null);
        assertFalse(lastBody.contains("controller was reset"), "known again after the home: " + lastBody);
    }
}
