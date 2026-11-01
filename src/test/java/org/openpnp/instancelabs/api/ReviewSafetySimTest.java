package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.ControllerCommands;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DriverGcodeChannel;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Configuration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Regression tests for the safety review of il/integration (pressure hold, vent, Stop, controller
 * errors), against the simulated controller. Each test names the finding it covers.
 */
public class ReviewSafetySimTest {
    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine();
        svc = DispenseService.start(Configuration.get());
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

    int lastStatus;

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
        return new JsonParser().parse(r.body()).getAsJsonObject();
    }

    JsonObject ok(String method, String path, String json) throws Exception {
        JsonObject o = call(method, path, json);
        assertTrue(lastStatus >= 200 && lastStatus < 300, lastStatus + " " + o);
        return o;
    }

    JsonObject pressure() throws Exception {
        return ok("GET", "/api/status", null).getAsJsonObject("pressure");
    }

    /** Leaves an unconsumed controller error in the driver (an Error: reply to an OpenPnP M114). */
    void leaveControllerErrorPending() throws Exception {
        sim.server.mark();
        sim.server.failAtLine = 0;
        sim.machine.execute(() -> {
            sim.driver.sendCommand("M114");
            return null;
        });
        sim.server.failAtLine = -1;
    }

    /** Finding: PressureHold.ventNow dropped the valve OFF when a controller error was pending. */
    @Test
    public void holdVentSendsValveOffDespiteAPendingControllerError() throws Exception {
        sim.server.hostWatchdogCapability = false; // stock firmware: no pinger consumes errors
        assertEquals("on", ok("POST", "/api/pressure/start", null).get("state").getAsString());
        leaveControllerErrorPending();
        sim.server.mark();
        JsonObject off = ok("POST", "/api/pressure/stop", null);
        List<String> l = sim.server.sinceMark();
        assertEquals(java.util.Arrays.asList("M107 P3", "M107 P2"), l, "valve OFF first, then pump OFF");
        assertEquals("off", off.get("state").getAsString(), off.toString());
        assertTrue(off.get("lastEvent").getAsString().contains("sent again"), off.toString());
    }

    /** Finding: the hold must not report OFF (nor disarm the watchdog) while the valve OFF was not sent. */
    @Test
    public void holdStaysVentFailedUntilTheValveOffIsSent() throws Exception {
        ok("PUT", "/api/config", "{\"commandTimeoutMs\":400}");
        assertEquals("on", ok("POST", "/api/pressure/start", null).get("state").getAsString());
        sim.server.mute.add("M107 P3"); // the valve OFF times out
        sim.server.mark();
        JsonObject r = ok("POST", "/api/pressure/stop", null);
        assertEquals("vent_failed", r.get("state").getAsString(), r.toString());
        List<String> l = sim.server.sinceMark();
        assertTrue(l.contains("M107 P3") && l.contains("M107 P2"), l.toString());
        assertFalse(l.contains("M990 S0"), "watchdog not disarmed while the valve may be on: " + l);
        call("POST", "/api/prime", "{}");
        assertEquals(409, lastStatus, "engine runs refused while the hold is not vented");
        // the user presses Pressure off again once the link answers
        sim.server.mute.clear();
        sim.server.mark();
        r = ok("POST", "/api/pressure/stop", null);
        assertEquals("off", r.get("state").getAsString(), r.toString());
        l = sim.server.sinceMark();
        assertTrue(l.indexOf("M107 P3") >= 0 && l.indexOf("M107 P2") > l.indexOf("M107 P3"), l.toString());
        assertTrue(l.contains("M990 S0"), l.toString());
    }

    /** Finding: OpenPnP Stop during the switch-on waited for G4s and then switched the valve ON. */
    @Test
    public void openPnpStopDuringSwitchOnNeverOpensTheValve() throws Exception {
        ok("PUT", "/api/config", "{\"pumpToValveSettleMs\":1500,\"valveSettleMs\":500}");
        sim.server.realDwell = true;
        sim.server.mark();
        Thread starter = new Thread(() -> {
            try {
                call("POST", "/api/pressure/start", null);
            }
            catch (Exception e) {
                // reported through the state below
            }
        });
        starter.start();
        long t0 = System.currentTimeMillis();
        while (!sim.server.sinceMark().contains("M106 P2 S255") && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(5);
        }
        Thread.sleep(150); // inside the pump-to-valve wait
        long s0 = System.nanoTime();
        sim.machine.setEnabled(false); // OpenPnP's Stop (emergency path: the machine thread is busy)
        long stopMs = (System.nanoTime() - s0) / 1_000_000L;
        starter.join(10000);
        List<String> l = sim.server.sinceMark();
        assertFalse(l.contains("M106 P3 S255"), "valve switched on after Stop: " + l);
        int v = l.indexOf("M107 P3"), p = l.indexOf("M107 P2");
        assertTrue(v >= 0 && p > v, l.toString());
        assertTrue(stopMs < 1000, "Stop took " + stopMs + " ms");
        assertEquals("off", pressure().get("state").getAsString());
        sim.machine.setEnabled(true);
    }

    /** Finding: web STOP while switching on returned without venting. */
    @Test
    public void webStopDuringSwitchOnVentsAtOnce() throws Exception {
        ok("PUT", "/api/config", "{\"pumpToValveSettleMs\":1500}");
        sim.server.mark();
        Thread starter = new Thread(() -> {
            try {
                call("POST", "/api/pressure/start", null);
            }
            catch (Exception e) {
                // state below
            }
        });
        starter.start();
        long t0 = System.currentTimeMillis();
        while (!sim.server.sinceMark().contains("M106 P2 S255") && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(5);
        }
        ok("POST", "/api/stop", null);
        starter.join(10000);
        List<String> l = sim.server.sinceMark();
        assertFalse(l.contains("M106 P3 S255"), l.toString());
        assertTrue(l.indexOf("M107 P3") >= 0 && l.indexOf("M107 P2") > l.indexOf("M107 P3"), l.toString());
        assertEquals("off", pressure().get("state").getAsString());
    }

    /** Finding: the hold's M115 probe swallowed a pending controller error. */
    @Test
    public void holdRefusedWhenTheControllerReportedAnError() throws Exception {
        leaveControllerErrorPending();
        sim.server.mark();
        JsonObject r = call("POST", "/api/pressure/start", null);
        assertEquals(409, lastStatus, r.toString());
        assertTrue(r.toString().contains("error"), r.toString());
        assertFalse(sim.server.sinceMark().contains("M106 P2 S255"), sim.server.sinceMark().toString());
        assertEquals("off", pressure().get("state").getAsString());
    }

    /**
     * Bench F9: with the machine.xml patch, OpenPnP's Stop (DISABLE_COMMAND) sends the whole Stop
     * sequence, so the web STOP of a held pressure ends in M410, both valves and pumps off, M18 B.
     */
    @Test
    public void webStopWithTheF9DisableCommandSendsTheWholeStopSequence() throws Exception {
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND,
                String.join("\n", ControllerCommands.STOP_SEQUENCE));
        JsonObject i1 = null;
        for (com.google.gson.JsonElement e : ok("GET", "/api/status", null).getAsJsonArray("interlocks")) {
            if (e.getAsJsonObject().get("id").getAsString().equals("I1")) {
                i1 = e.getAsJsonObject();
            }
        }
        assertEquals("ok", i1.get("state").getAsString(), i1.toString());
        assertEquals("on", ok("POST", "/api/pressure/start", null).get("state").getAsString());
        sim.server.mark();
        JsonObject r = ok("POST", "/api/stop", null);
        assertFalse(r.get("machineEnabled").getAsBoolean(), r.toString());
        List<String> l = sim.server.sinceMark();
        int vent = l.indexOf("M107 P3");
        int seq = java.util.Collections.indexOfSubList(l, ControllerCommands.STOP_SEQUENCE);
        assertTrue(vent >= 0 && l.indexOf("M107 P2") > vent, "the hold vents first: " + l);
        assertTrue(seq > vent, "then OpenPnP's Stop sends M410, M107 P3, M107 P2, M107 P1, M107 P0, M18 B: " + l);
        assertFalse(l.subList(vent, l.size()).contains("M106 P3 S255"), l.toString());
        assertEquals("off", pressure().get("state").getAsString());
    }

    /** Finding: a stale controller error refused the next driver line without sending it. */
    @Test
    public void criticalLinesAreSentAgainAfterAPendingError() throws Exception {
        leaveControllerErrorPending();
        sim.server.mark();
        String note = sim.machine.execute(() -> new DriverGcodeChannel(sim.driver).sendCritical("M107 P3", 2000));
        assertEquals(java.util.Arrays.asList("M107 P3"), sim.server.sinceMark());
        assertTrue(note != null && note.contains("simulated failure"), String.valueOf(note));
    }
}
