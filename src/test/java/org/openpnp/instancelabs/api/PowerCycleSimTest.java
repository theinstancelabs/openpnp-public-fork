package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.openpnp.instancelabs.dispense.ControllerReset;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.machine.reference.driver.GcodeDriver;
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

    /** The machine lock's token this test holds (BR-72, sent with every call), or null. */
    String lockToken;

    JsonObject call(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120));
        if (lockToken != null) {
            b.header(MachineLock.TOKEN_HEADER, lockToken);
        }
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

    /**
     * OpenPnP quits and starts again (a deploy, or a restart to recover the serial link) with the controller powered
     * all along: the engine starts afresh on the same database and sees the next connect.
     */
    void restartOpenPnp() throws Exception {
        sim.machine.setEnabled(false);
        svc = DispenseService.start(Configuration.get());
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

        // #11 final review MINOR: the status no longer says N1 is low and that the home raises it (the believed Z 0
        // is the boot's, and the attested home goes straight to G28)
        JsonObject home0 = m.getAsJsonObject("home");
        assertFalse(home0.get("n1Low").getAsBoolean(), m.toString());
        assertTrue(home0.get("lowTool").isJsonNull(), m.toString());
        assertTrue(home0.get("n1Z").isJsonNull(), m.toString());

        // without the operator's word: refused, and nothing is sent
        sim.server.mark();
        call("POST", "/api/machine/home", "{\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("nothing is under either tool"), lastBody);
        // #11 final review MAJOR: G28 drives X, then Y, at the tools' height first (no Z clearance on this firmware)
        assertTrue(lastBody.contains("homing corner") && lastBody.contains("at the height the tools are at now")
                && lastBody.contains("level N1 and the needle by hand"), lastBody);
        assertFalse(lastBody.contains("nothing moves before it"), lastBody);
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
        assertTrue(after.getAsJsonObject("powerCycle").get("detected").getAsBoolean(),
                "this connection began with a reset: " + after);

        // a Stop and Start afterwards, the controller powered all along: this connection is not a reset
        reconnect();
        JsonObject again = ok("GET", "/api/machine", null);
        JsonObject pc2 = again.getAsJsonObject("powerCycle");
        assertFalse(pc2.get("detected").getAsBoolean(), again.toString());
        assertEquals(pc.get("atMs").getAsLong(), pc2.get("atMs").getAsLong(), "the last reset stays on record");
    }

    /**
     * #11 re-review MAJOR (CI's deploy dry run): after a power cycle a bench tool zeroed the screw (G92 B0) and nobody
     * homed. X, Y, Z and A still read their boot values: the home needs the operator's word, and no Z move goes out on
     * the boot belief before G28.
     */
    @Test
    public void aBootWithTheScrewZeroedNeedsTheOperatorsWord() throws Exception {
        sim.machine.setEnabled(false);
        sim.server.powerCycle();
        sim.server.lineFromAnotherTool("G92 B0");
        sim.machine.setEnabled(true);
        JsonObject m = ok("GET", "/api/machine", null);
        assertFalse(m.get("positionKnown").getAsBoolean(), m.toString());
        sim.server.mark();
        call("POST", "/api/machine/home", "{\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertEquals(new ArrayList<String>(), motionAndOutputs(), "no Z raise on the boot belief");
        sim.server.mark();
        ok("POST", "/api/machine/home", "{\"wait\":true,\"clearBelowAttested\":true}");
        List<String> l = motionAndOutputs();
        assertTrue(!l.isEmpty() && l.get(0).startsWith("G28"), "G28 first: " + l);
    }

    /**
     * #11 re-review MINOR: after a detected reset the screw count restarted for sure, so a bare confirm cannot keep an
     * angle set before it, also once a later Stop and Start reads detected false; a new angle can be set.
     */
    @Test
    public void aConfirmCannotKeepAnAngleSetBeforeADetectedReset() throws Exception {
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":298}");
        Thread.sleep(5);
        powerCycle();
        ok("POST", "/api/machine/home", "{\"wait\":true,\"clearBelowAttested\":true}");
        reconnect();
        call("POST", "/api/screw/start-angle", "{}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("measure"), lastBody);
        // nor can one be set before the screw has turned since (a dot pair measures it): the old value would pass
        call("POST", "/api/screw/start-angle", "{\"startAngleDeg\":298}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("the screw has not turned since the controller was reset at ")
                && lastBody.contains("measure the start angle (a dot pair) first"), lastBody);
        turnScrew();
        JsonObject p = ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        assertFalse(p.get("startAngleNeedsConfirm").getAsBoolean(), "a new angle after the reset: " + p);
        ok("POST", "/api/screw/start-angle", "{}"); // and a confirm of that one is fine
    }

    @Test
    public void aG92InTheConnectCommandIsWarnedAbout() throws Exception {
        // it would set the position before the engine's M114 reads it, hiding a reset's boot position
        String connect = sim.driver.getCommand(null, GcodeDriver.CommandType.CONNECT_COMMAND);
        sim.driver.setCommand(null, GcodeDriver.CommandType.CONNECT_COMMAND,
                (connect == null ? "" : connect + "\n") + "G92 X0 Y0 ; a config edit");
        reconnect();
        reconnect();
        int warned = 0;
        for (java.util.Map<String, Object> e : svc.getDb().recentEvents(20)) {
            if (String.valueOf(e.get("message")).contains("CONNECT_COMMAND sends 'G92 X0 Y0")) {
                warned++;
            }
        }
        assertEquals(1, warned, "warned once per engine: " + svc.getDb().recentEvents(20));
    }

    @Test
    public void aResetStaysOnRecordAcrossAnOpenPnpRestart() throws Exception {
        long setAt = ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":298}").get("startAngleSetAtMs")
                .getAsLong();
        Thread.sleep(5); // the reset comes later than the angle, in ms
        powerCycle();
        ok("POST", "/api/machine/home", "{\"wait\":true,\"clearBelowAttested\":true}");
        // the home's G92 A0 B0 removed the boot position: the next connect reads a homed controller
        restartOpenPnp();

        JsonObject m = ok("GET", "/api/machine", null);
        assertTrue(m.get("positionKnown").getAsBoolean(), "the attested home followed the reset: " + m);
        JsonObject pc = m.getAsJsonObject("powerCycle");
        assertFalse(pc.get("detected").getAsBoolean(), "this connection is not a reset: " + m);
        assertFalse(pc.get("atMs").isJsonNull(), "the last reset stays on record: " + m);
        JsonObject p = ok("GET", "/api/status", null).getAsJsonObject("dispense").getAsJsonObject("profile");
        assertFalse(p.get("controllerResetAtMs").isJsonNull(), p.toString());
        assertTrue(p.get("controllerResetAtMs").getAsLong() > setAt, p.toString());
        assertTrue(p.get("startAngleNeedsConfirm").getAsBoolean(),
                "I11: the angle was set before the reset, whatever restarted since: " + p);
    }

    @Test
    public void aResetNotHomedBeforeAnOpenPnpRestartStillNeedsTheOperatorsWord() throws Exception {
        powerCycle();
        // OpenPnP's own jog moves the head while unhomed: the controller no longer reads the boot position
        sim.machine.execute(() -> {
            sim.driver.sendCommand("G1 X5 F3000");
            return null;
        });
        restartOpenPnp();

        JsonObject m = ok("GET", "/api/machine", null);
        assertFalse(m.get("positionKnown").getAsBoolean(), "the reset was never followed by a home: " + m);
        assertTrue(m.get("positionUnknownWhy").getAsString().contains("reset"), m.toString());
        assertFalse(m.getAsJsonObject("powerCycle").get("detected").getAsBoolean(), "this connect read X 5: " + m);
        sim.server.mark();
        call("POST", "/api/machine/home", "{\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("nothing is under either tool"), lastBody);
        assertEquals(new ArrayList<String>(), motionAndOutputs(), "a refused home sends nothing");

        sim.server.mark();
        ok("POST", "/api/machine/home", "{\"wait\":true,\"clearBelowAttested\":true}");
        List<String> l = motionAndOutputs();
        assertFalse(l.isEmpty(), sim.server.sinceMark().toString());
        assertTrue(l.get(0).startsWith("G28"), "G28 first: " + l);

        restartOpenPnp();
        assertTrue(ok("GET", "/api/machine", null).get("positionKnown").getAsBoolean(), "the home is on record too");
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

    /** The live machine's DISABLE_COMMAND (machine.xml, bench F9): its last line switches the screw's motor off. */
    void liveDisableCommand() {
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND,
                "M410\nM107 P3\nM107 P2\nM107 P1\nM107 P0\nM18 B");
    }

    /** The journal's screw motor switch-offs. */
    List<java.util.Map<String, Object>> motorOffs() throws Exception {
        return svc.getDb().query("SELECT * FROM events WHERE code = ?", ControllerReset.EVENT_MOTOR_OFF);
    }

    /** The status's I11 item. */
    JsonObject i11(JsonObject status) {
        for (com.google.gson.JsonElement e : status.getAsJsonArray("interlocks")) {
            if ("I11".equals(e.getAsJsonObject().get("id").getAsString())) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no I11 in " + status);
    }

    /**
     * BR-02: a STOP's DISABLE_COMMAND ends with M18 B: with its motor off the screw turns freely (118 deg became 226 deg
     * on the bench), so the angle set before it must be measured again; a bare confirm is refused, as after a reset.
     */
    @Test
    public void aStopThatSwitchesTheScrewMotorOffAsksForTheAngleToBeMeasuredAgain() throws Exception {
        liveDisableCommand();
        long setAt = ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}").get("startAngleSetAtMs")
                .getAsLong();
        Thread.sleep(5);
        sim.server.mark();
        ok("POST", "/api/stop", null);
        ok("POST", "/api/machine/enable", null); // waits for the switch-off to end; the controller stayed powered
        assertTrue(sim.server.sinceMark().contains("M18 B"), sim.server.sinceMark().toString());
        assertEquals(1, motorOffs().size(), String.valueOf(svc.getDb().recentEvents(20)));
        String why = String.valueOf(motorOffs().get(0).get("message"));
        assertTrue(why.startsWith("the screw motor was switched off at ") && why.contains("(a STOP"), why);

        JsonObject m = ok("GET", "/api/machine", null);
        assertFalse(m.getAsJsonObject("powerCycle").get("detected").getAsBoolean(), "not a reset: " + m);
        assertTrue(m.get("positionKnown").getAsBoolean(), "M18 B leaves the head's motors on: " + m);
        assertTrue(m.getAsJsonObject("screwMotorOff").get("atMs").getAsLong() > setAt, m.toString());
        JsonObject status = ok("GET", "/api/status", null);
        JsonObject p = status.getAsJsonObject("dispense").getAsJsonObject("profile");
        assertTrue(p.get("startAngleNeedsConfirm").getAsBoolean(), p.toString());
        assertTrue(p.get("controllerResetAtMs").getAsLong() > setAt, "the screw count is dated by it: " + p);
        JsonObject i11 = i11(status);
        assertEquals("blocked", i11.get("state").getAsString(), i11.toString());
        String text = i11.get("text").getAsString();
        assertTrue(text.contains("the screw motor was switched off at ") && text.contains("(a STOP")
                && text.contains("the screw may have turned: measure the start angle again"), text);
        assertFalse(text.contains("confirm"), text);

        call("POST", "/api/screw/start-angle", "{}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("the screw motor was switched off") && lastBody.contains("measure it again"),
                lastBody);
        // the old angle, one click away, would clear I11: no set before the screw has turned since
        call("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("the screw has not turned since its motor was switched off at "), lastBody);
        turnScrew(); // the dot pair that measures it
        call("POST", "/api/screw/start-angle", "{\"startAngleDeg\":226,\"measuredAtMs\":" + setAt + "}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("before its motor was switched off at "), "evidence from before it: " + lastBody);
        JsonObject again = ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":226,\"measuredAtMs\":"
                + System.currentTimeMillis() + "}");
        assertFalse(again.get("startAngleNeedsConfirm").getAsBoolean(), "measured again after it: " + again);
        ok("POST", "/api/screw/start-angle", "{}"); // and a confirm of that one is fine
        assertEquals("ok", i11(ok("GET", "/api/status", null)).get("state").getAsString());
        assertEquals(1, motorOffs().size());
    }

    /**
     * BR-02: a disable whose driver is already disconnected sends no DISABLE_COMMAND (a planned stop that disconnects
     * first, BR-75): the screw's motor stays on and its angle holds.
     */
    @Test
    public void aDisableWithTheDriverDisconnectedLeavesTheScrewMotorOn() throws Exception {
        liveDisableCommand();
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        Thread.sleep(5);
        sim.server.mark();
        sim.driver.disconnect();
        sim.machine.setEnabled(false);
        assertFalse(sim.server.sinceMark().contains("M18 B"), sim.server.sinceMark().toString());
        assertEquals(0, motorOffs().size(), String.valueOf(svc.getDb().recentEvents(20)));
        sim.machine.setEnabled(true);
        JsonObject status = ok("GET", "/api/status", null);
        JsonObject p = status.getAsJsonObject("dispense").getAsJsonObject("profile");
        assertFalse(p.get("startAngleNeedsConfirm").getAsBoolean(), "the angle holds: " + p);
        assertEquals("ok", i11(status).get("state").getAsString(), i11(status).toString());
        assertTrue(ok("GET", "/api/machine", null).getAsJsonObject("screwMotorOff").get("atMs").isJsonNull());
        ok("POST", "/api/screw/start-angle", "{}");

        // the same disable with the driver connected sends its M18 B: on record
        sim.server.mark();
        sim.machine.setEnabled(false);
        assertTrue(sim.server.sinceMark().contains("M18 B"), sim.server.sinceMark().toString());
        assertEquals(1, motorOffs().size());
    }

    /** BR-02: the API's G-code passthrough: its M18 B switches the screw's motor off as a STOP's does. */
    @Test
    public void aPassthroughM18BSwitchesTheScrewMotorOff() throws Exception {
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        Thread.sleep(5);
        lockToken = ok("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"screw\"}").get("token")
                .getAsString();
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 B30 F600\",\"M400\"]}");
        assertEquals(0, motorOffs().size(), "a screw move leaves it on");
        ok("POST", "/api/gcode", "{\"lines\":[\"M18 B\"]}");
        assertEquals(1, motorOffs().size(), String.valueOf(svc.getDb().recentEvents(20)));
        String why = String.valueOf(motorOffs().get(0).get("message"));
        assertTrue(why.endsWith(" (a G-code line: M18 B)"), why);
        call("POST", "/api/screw/start-angle", "{}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("measure it again"), lastBody);
    }

    /** The screw turns (a dot pair measures the start angle): a G1 B through the passthrough, as the lock's agent. */
    void turnScrew() throws Exception {
        if (lockToken == null) {
            lockToken = ok("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"start angle\"}")
                    .get("token").getAsString();
        }
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 B30 F600\",\"M400\"]}");
    }

    /**
     * BR-02: a Start that fails after the screw's driver connected is rolled back by OpenPnP: the driver's
     * DISABLE_COMMAND goes out (its M18 B) though only machineEnableFailed fires: on record all the same.
     */
    @Test
    public void aFailedStartsDisableCommandSwitchesTheScrewMotorOff() throws Exception {
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        sim.machine.setEnabled(false); // the test config has no DISABLE_COMMAND: nothing switched off
        assertEquals(0, motorOffs().size());
        liveDisableCommand();
        org.openpnp.machine.reference.driver.NullDriver failing = new org.openpnp.machine.reference.driver.NullDriver() {
            @Override
            public void setEnabled(boolean enabled) throws Exception {
                if (enabled) {
                    throw new Exception("a second controller did not answer");
                }
                super.setEnabled(false);
            }
        };
        Thread.sleep(5);
        sim.machine.addDriver(failing); // enabled after the screw's driver: its failure rolls that one back
        try {
            sim.server.mark();
            assertThrows(Exception.class, () -> sim.machine.setEnabled(true));
            assertTrue(sim.server.sinceMark().contains("M18 B"), "the rollback's DISABLE_COMMAND: "
                    + sim.server.sinceMark());
        }
        finally {
            sim.machine.removeDriver(failing);
        }
        assertEquals(1, motorOffs().size(), String.valueOf(svc.getDb().recentEvents(20)));
        String why = String.valueOf(motorOffs().get(0).get("message"));
        assertTrue(why.contains("(a Start that failed"), why);
        sim.machine.setEnabled(true);
        call("POST", "/api/screw/start-angle", "{}");
        assertEquals(409, lastStatus, lastBody);
    }

    /**
     * BR-02: the il/safety firmware's host watchdog trips when the host falls silent while it is armed: it switches the
     * screw's driver off and says "Error:host watchdog".
     */
    @Test
    public void aHostWatchdogTripSwitchesTheScrewMotorOff() throws Exception {
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        Thread.sleep(5);
        sim.server.say("Error:host watchdog");
        long t0 = System.currentTimeMillis();
        while (motorOffs().isEmpty() && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(20);
        }
        assertEquals(1, motorOffs().size(), String.valueOf(svc.getDb().recentEvents(20)));
        String why = String.valueOf(motorOffs().get(0).get("message"));
        assertTrue(why.contains("(the controller's host watchdog tripped"), why);
        call("POST", "/api/screw/start-angle", "{}");
        assertEquals(409, lastStatus, lastBody);
    }

    /**
     * BR-02: a trip while OpenPnP was gone (no one heard it) is in the firmware's M990 report until the next arm: read at
     * every connect, only where M115 names the watchdog.
     */
    @Test
    public void aWatchdogTripReportedAtTheConnectSwitchesTheScrewMotorOff() throws Exception {
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        Thread.sleep(5);
        sim.server.watchdogReport = "echo:Host watchdog: off tripped";
        sim.server.hostWatchdogCapability = false;
        sim.server.mark();
        reconnect();
        assertFalse(sim.server.sinceMark().contains("M990"), "no watchdog in M115: not asked " + sim.server.sinceMark());
        assertEquals(0, motorOffs().size());
        sim.server.hostWatchdogCapability = true;
        sim.server.mark();
        reconnect();
        assertTrue(sim.server.sinceMark().contains("M990"), sim.server.sinceMark().toString());
        assertEquals(1, motorOffs().size(), String.valueOf(svc.getDb().recentEvents(20)));
        String why = String.valueOf(motorOffs().get(0).get("message"));
        assertTrue(why.contains("(the controller's host watchdog tripped") && why.contains("M990"), why);
        call("POST", "/api/screw/start-angle", "{}");
        assertEquals(409, lastStatus, lastBody);
        sim.server.watchdogReport = "echo:Host watchdog: off";
        reconnect();
        assertEquals(1, motorOffs().size(), "no trip reported: nothing new");
    }

    /** BR-02: the controller's idle timeout is at most Marlin's 3600 s (a longer one would miss its switch-off). */
    @Test
    public void theIdleTimeoutIsAtMostTheControllers() throws Exception {
        call("PUT", "/api/config", "{\"screwIdleOffS\":3601}");
        assertEquals(400, lastStatus, lastBody);
        assertTrue(lastBody.contains("screwIdleOffS"), lastBody);
        ok("PUT", "/api/config", "{\"screwIdleOffS\":1800}");
        assertEquals(1800, svc.getConfig().screwIdleOffS, 0);
    }

    @Test
    public void everyOtherMotionWaitsForTheHomeAfterAReset() throws Exception {
        powerCycle();
        sim.server.mark();
        call("POST", "/api/machine/z-check", "{\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("controller was reset"), lastBody);
        assertTrue(lastBody.contains("homing corner"), "the remedy says what the home does: " + lastBody);
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
