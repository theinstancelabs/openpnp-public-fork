package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.ControllerReset;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;

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

    /**
     * OpenPnP (stopped) starts again and Start is pressed while the engine is starting: the web API and the engine start
     * in no fixed order. At the engine start's {@code point}: "listeners" before its machine listeners exist,
     * "catch-up" right after they do.
     */
    void startOpenPnpWithStartPressedAt(String point) throws Exception {
        startEngineWith(point, () -> {
            sim.machine.setEnabled(true);
            return null;
        });
        assertTrue(sim.machine.isEnabled(), "Start was pressed at " + point);
    }

    /** The engine starts afresh, and at its start's {@code point} OpenPnP does {@code what} (its Start, its Home). */
    void startEngineWith(String point, Callable<?> what) throws Exception {
        TipSim.hook(p -> {
            if (point.equals(p)) {
                try {
                    what.call();
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        try {
            svc = DispenseService.start(Configuration.get());
        }
        finally {
            TipSim.hook(null);
        }
    }

    /** The journal's events of one code. */
    List<java.util.Map<String, Object>> events(String code) throws Exception {
        return svc.getDb().query("SELECT * FROM events WHERE code = ?", code);
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
        // the process engineer, 11-04: the see-saw levelled (both tips up) before Home
        assertTrue(lastBody.contains("before Home: the see-saw levelled, both tips up"), lastBody);
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
        // every connect asks a confirm (the process engineer, 11-03); no switch-off: no measure
        assertTrue(p.get("startAngleNeedsConfirm").getAsBoolean(), p.toString());
        assertTrue(i11(status).get("text").getAsString().contains("the controller (re)connected"), i11(status).toString());
        assertTrue(ok("GET", "/api/machine", null).getAsJsonObject("screwMotorOff").get("atMs").isJsonNull());
        ok("POST", "/api/screw/start-angle", "{}"); // the confirm keeps it
        assertEquals("ok", i11(ok("GET", "/api/status", null)).get("state").getAsString());

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
        // the firmware reports it until the host arms the watchdog again (never, without the keepalive): once per arm
        reconnect();
        assertEquals(1, motorOffs().size(), "the same trip at the next connect");
        // the pressure hold arms it: the firmware forgets that trip, so one reported after it is a new one
        ok("POST", "/api/machine/home", "{\"wait\":true}");
        sim.towelUnderNeedle(svc); // BR-03: the hold opens the valve only over a sink
        assertEquals("on", ok("POST", "/api/pressure/start", null).get("state").getAsString());
        ok("POST", "/api/pressure/stop", null);
        reconnect();
        assertEquals(2, motorOffs().size(), "a trip reported after an arm is a new one");
        sim.server.watchdogReport = "echo:Host watchdog: off";
        reconnect();
        assertEquals(2, motorOffs().size(), "no trip reported: nothing new");
    }

    /** BR-02: a trip's journal write holds up no answer of the controller (the reader thread hands it on). */
    @Test
    public void aWatchdogTripsJournalWriteHoldsUpNoAnswer() throws Exception {
        synchronized (svc.getDb()) { // the journal cannot write now (a slow insert)
            sim.server.say("Error:host watchdog");
            Thread.sleep(200);
            sim.machine.execute(() -> {
                try {
                    sim.driver.sendCommand("M400", 2000);
                }
                catch (Exception e) {
                    // the trip's error, once
                }
                sim.driver.sendCommand("M400", 2000); // its ok is read while the journal waits
                return null;
            });
        }
        long t0 = System.currentTimeMillis();
        while (motorOffs().isEmpty() && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(20);
        }
        assertEquals(1, motorOffs().size(), String.valueOf(svc.getDb().recentEvents(20)));
    }

    /**
     * BR-02: Marlin restarts its idle clock only on a queued move: a G1 that changes nothing (feed only, or to where the
     * axis is) queues none, so it is no move for the engine either, nor a turn of the screw.
     */
    @Test
    public void aPassthroughLineThatMovesNothingIsNoTurn() throws Exception {
        liveDisableCommand();
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        Thread.sleep(5);
        ok("POST", "/api/stop", null);
        ok("POST", "/api/machine/enable", null);
        assertEquals(0, sim.server.position('B'), 1e-9, "the home's G92 B0");
        lockToken = ok("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"start angle\"}").get("token")
                .getAsString();
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 F600\",\"G1 B0 F600\",\"M400\"]}");
        call("POST", "/api/screw/start-angle", "{\"startAngleDeg\":226}");
        assertEquals(409, lastStatus, "nothing turned: " + lastBody);
        assertTrue(lastBody.contains("the screw has not turned since"), lastBody);
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 B30 F600\",\"G1 B30 F600\",\"M400\"]}");
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":226}");
    }

    /**
     * BR-02 (the process engineer, 11-03, confirmed 11-04): every OpenPnP start or connect counts as a possible phase
     * change: an angle set before it is confirmed (a confirm is enough: no reset, no switch-off), also after a restart
     * of OpenPnP (the connect time is this engine's).
     */
    @Test
    public void everyConnectAndRestartAsksForTheAngleToBeConfirmed() throws Exception {
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        JsonObject s = ok("GET", "/api/status", null);
        assertFalse(s.getAsJsonObject("dispense").getAsJsonObject("profile").get("startAngleNeedsConfirm")
                .getAsBoolean(), "set after this connect");
        assertEquals("ok", i11(s).get("state").getAsString(), i11(s).toString());
        Thread.sleep(5);
        reconnect();
        s = ok("GET", "/api/status", null);
        assertTrue(s.getAsJsonObject("dispense").getAsJsonObject("profile").get("startAngleNeedsConfirm")
                .getAsBoolean(), s.toString());
        assertEquals("blocked", i11(s).get("state").getAsString(), i11(s).toString());
        assertTrue(i11(s).get("text").getAsString().contains("confirm it if the controller was not power-cycled"),
                i11(s).toString());
        ok("POST", "/api/screw/start-angle", "{}");
        assertEquals("ok", i11(ok("GET", "/api/status", null)).get("state").getAsString());
        Thread.sleep(5);
        restartOpenPnp();
        s = ok("GET", "/api/status", null);
        assertTrue(s.getAsJsonObject("dispense").getAsJsonObject("profile").get("startAngleNeedsConfirm")
                .getAsBoolean(), "a restart asks again: " + s);
        ok("POST", "/api/screw/start-angle", "{}");
    }

    /**
     * BR-02 (the process engineer, 11-04): the home after a controller reset moves the Z frame (K by twice the shift):
     * the needle frame and its touch-off are recorded again, and the start angle measured again. An ordinary home keeps
     * the frame.
     */
    @Test
    public void theHomeAfterAResetMovesTheNeedleZFrame() throws Exception {
        ok("POST", "/api/needle/anchor", "{\"needleChangedAtMs\":null,\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}");
        ok("POST", "/api/needle/offset", "{\"needleChangedAtMs\":null,\"offsetMm\":-2.59}");
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        JsonObject n = ok("GET", "/api/status", null).getAsJsonObject("dispense").getAsJsonObject("needle");
        assertTrue(n.get("anchored").getAsBoolean() && n.get("touchedOff").getAsBoolean(), n.toString());
        Thread.sleep(5);
        reconnect();
        ok("POST", "/api/machine/home", "{\"wait\":true}");
        n = ok("GET", "/api/status", null).getAsJsonObject("dispense").getAsJsonObject("needle");
        assertTrue(n.get("anchored").getAsBoolean() && n.get("touchedOff").getAsBoolean(), "an ordinary home: " + n);

        powerCycle();
        ok("POST", "/api/machine/home", "{\"wait\":true,\"clearBelowAttested\":true}");
        JsonObject status = ok("GET", "/api/status", null);
        n = status.getAsJsonObject("dispense").getAsJsonObject("needle");
        assertFalse(n.get("anchored").getAsBoolean(), "the frame moved: " + n);
        assertFalse(n.get("touchedOff").getAsBoolean(), n.toString());
        String i4 = null;
        for (com.google.gson.JsonElement e : status.getAsJsonArray("interlocks")) {
            if ("I4".equals(e.getAsJsonObject().get("id").getAsString())) {
                i4 = e.getAsJsonObject().get("text").getAsString();
            }
        }
        assertTrue(i4 != null && i4.contains("the controller was reset: the Z frame moved at the home of ")
                && i4.contains("record the needle frame again"), String.valueOf(i4));
        call("PUT", "/api/config", "{\"zFrameMovedAtMs\":null}");
        assertEquals(400, lastStatus, "stamped by the engine: " + lastBody);
        // the start angle after it is measured again: a confirm is refused, a set needs a turn
        call("POST", "/api/screw/start-angle", "{}");
        assertEquals(409, lastStatus, lastBody);
        call("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        assertEquals(409, lastStatus, lastBody);
        // K moved with the frame (by twice the shift): no frame from it, both tips touch the same spot
        call("POST", "/api/needle/anchor", "{\"needleChangedAtMs\":null,\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("K was measured before the controller was reset"), lastBody);
        // recorded again: the frame, then its touch-off
        ok("POST", "/api/needle/anchor", "{\"needleChangedAtMs\":null,\"surfaceZ\":5.5,\"needleContactZ\":2.91}");
        n = ok("GET", "/api/status", null).getAsJsonObject("dispense").getAsJsonObject("needle");
        assertTrue(n.get("anchored").getAsBoolean(), n.toString());
        assertFalse(n.get("touchedOff").getAsBoolean(), "the touch-off follows the frame: " + n);
        ok("POST", "/api/needle/offset", "{\"needleChangedAtMs\":null,\"offsetMm\":-2.59}");
        n = ok("GET", "/api/status", null).getAsJsonObject("dispense").getAsJsonObject("needle");
        assertTrue(n.get("touchedOff").getAsBoolean(), n.toString());
        restartOpenPnp();
        assertTrue(svc.getConfig().isNeedleAnchored(), "the stamp outlives the restart, the new frame too");
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

    /**
     * #11 review (11-04) MAJOR: the web API and the engine start in no fixed order, and Start does not wait for the
     * engine. A connect made before the engine's listeners existed is handled at its start: after a power cycle the
     * home is G28 first on the operator's word, I11 asks for the angle, and the home moves the Z frame.
     */
    @Test
    public void aConnectMadeBeforeTheEngineStartedIsHandledAtItsStart() throws Exception {
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        Thread.sleep(5);
        sim.machine.setEnabled(false);
        sim.server.powerCycle();
        startOpenPnpWithStartPressedAt("listeners");

        JsonObject m = ok("GET", "/api/machine", null);
        assertFalse(m.get("positionKnown").getAsBoolean(), m.toString());
        assertTrue(m.getAsJsonObject("powerCycle").get("detected").getAsBoolean(), "read at the engine's start: " + m);
        JsonObject s = ok("GET", "/api/status", null);
        assertEquals("blocked", i11(s).get("state").getAsString(), i11(s).toString());
        assertTrue(i11(s).get("text").getAsString().contains("the controller was reset"), i11(s).toString());
        assertNotNull(svc.getControllerConnectedAtMs(), "the connect is dated at the engine's start");

        sim.server.mark();
        call("POST", "/api/machine/home", "{\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertEquals(new ArrayList<String>(), motionAndOutputs(), "a refused home sends nothing");
        ok("POST", "/api/machine/home", "{\"wait\":true,\"clearBelowAttested\":true}");
        List<String> l = motionAndOutputs();
        assertTrue(!l.isEmpty() && l.get(0).startsWith("G28"), "G28 first, no Z move before it: " + l);
        assertTrue(svc.getConfig().zFrameMovedAtMs != null, "the reset's home moved the Z frame");
    }

    /**
     * #11 review (11-04), the gap the catch-up leaves: a Start and a Home through the API while the engine starts would
     * skip its reset checks (it is not watching the connect yet, and a home made then cannot move the Z frame): both are
     * refused until it runs. OpenPnP's own Start (its window) is still caught up at the engine's start.
     */
    @Test
    public void startAndHomeThroughTheApiWaitForTheEngine() throws Exception {
        sim.machine.setEnabled(false);
        sim.server.powerCycle();
        List<String> answers = new ArrayList<>();
        TipSim.hook(p -> {
            if ("listeners".equals(p)) {
                try {
                    call("POST", "/api/machine/enable", "{}");
                    answers.add(lastStatus + " " + lastBody);
                    sim.machine.setEnabled(true); // OpenPnP's own Start
                    call("POST", "/api/machine/home", "{\"clearBelowAttested\":true}");
                    answers.add(lastStatus + " " + lastBody);
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        try {
            svc = DispenseService.start(Configuration.get());
        }
        finally {
            TipSim.hook(null);
        }
        assertEquals(2, answers.size(), answers.toString());
        for (String a : answers) {
            assertTrue(a.startsWith("409 ") && a.contains("still starting"), a);
        }
        JsonObject m = ok("GET", "/api/machine", null);
        assertFalse(m.get("positionKnown").getAsBoolean(), "the refused home moved nothing: " + m);
    }

    /**
     * #11 review (11-04): Start and Home both before the engine started: the home followed the connect, so the position
     * is known; the connect is dated at the engine's start, so an angle set before it is confirmed (every OpenPnP start,
     * the process engineer 11-03).
     */
    @Test
    public void anEngineStartedOnAHomedMachineAsksTheAngleToBeConfirmed() throws Exception {
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        Thread.sleep(5);
        svc = DispenseService.start(Configuration.get()); // OpenPnP started and homed the machine before the engine
        assertTrue(ok("GET", "/api/machine", null).get("positionKnown").getAsBoolean());
        JsonObject s = ok("GET", "/api/status", null);
        assertTrue(s.getAsJsonObject("dispense").getAsJsonObject("profile").get("startAngleNeedsConfirm")
                .getAsBoolean(), s.toString());
        assertTrue(i11(s).get("text").getAsString().contains("the controller (re)connected"), i11(s).toString());
    }

    /**
     * #11 review (11-04): read only at the engine's start, a position other than the boot's may follow a move made
     * since the connect (the passthrough needs no engine): the connect counts as unread, a possible reset, and its home
     * moves the Z frame (the conservative side).
     */
    @Test
    public void aConnectReadOnlyAtTheEngineStartCountsAsUnread() throws Exception {
        sim.machine.setEnabled(false);
        startOpenPnpWithStartPressedAt("listeners");
        JsonObject m = ok("GET", "/api/machine", null);
        assertFalse(m.get("positionKnown").getAsBoolean(), m.toString());
        assertFalse(m.getAsJsonObject("powerCycle").get("detected").getAsBoolean(), m.toString());
        assertTrue(m.get("positionUnknownWhy").getAsString().contains("read it only later"), m.toString());
        assertEquals(1, events(ControllerReset.EVENT_UNREAD).size());
        ok("POST", "/api/machine/home", "{\"wait\":true,\"clearBelowAttested\":true}");
        assertTrue(svc.getConfig().zFrameMovedAtMs != null, "the home after a possible reset");
    }

    /**
     * #11 review (11-04): a trip while OpenPnP was gone is in the firmware's M990 report, read for a connect the engine
     * did not hear too (OpenPnP died with the watchdog armed; Start was pressed before the engine was up).
     */
    @Test
    public void aWatchdogTripIsReadForAConnectMadeBeforeTheEngineStarted() throws Exception {
        sim.machine.setEnabled(false);
        sim.server.watchdogReport = "echo:Host watchdog: off tripped";
        startOpenPnpWithStartPressedAt("listeners");
        assertEquals(1, motorOffs().size(), String.valueOf(svc.getDb().recentEvents(20)));
    }

    /** #11 review (11-04): a connect both the listener and the engine start's catch-up see is handled once. */
    @Test
    public void aConnectTheListenerAndTheStartBothSeeIsHandledOnce() throws Exception {
        sim.machine.setEnabled(false);
        sim.server.powerCycle();
        sim.server.mark();
        startOpenPnpWithStartPressedAt("catch-up");
        assertEquals(1, java.util.Collections.frequency(sim.server.sinceMark(), "M114"), sim.server.sinceMark()
                .toString());
        assertEquals(1, events(ControllerReset.EVENT_RESET).size(), String.valueOf(svc.getDb().recentEvents(20)));
        assertNotNull(svc.getControllerConnectedAtMs());
    }

    /**
     * #11 review (11-04): the pressure hold's arm of the host watchdog is on record at once: OpenPnP may die right
     * after an arm, the keepalive stops and the firmware trips again, and the trip reported at the next connect is a
     * new switch-off, not the one before the arm. An engine restarted inside OpenPnP writes an arm still in memory as
     * it stops; nothing stops it when OpenPnP exits, so a stream's arm is written while the stream runs
     * (DispenseServiceSimTest.aStreamsArmIsOnRecordWhileTheStreamRuns).
     */
    @Test
    public void anArmIsOnRecordSoATripReportedAfterARestartIsNew() throws Exception {
        sim.server.watchdogReport = "echo:Host watchdog: off tripped";
        reconnect();
        assertEquals(1, motorOffs().size(), "the trip reported at the connect");
        ok("POST", "/api/machine/home", "{\"wait\":true}");
        sim.towelUnderNeedle(svc); // BR-03: the hold opens the valve only over a sink
        assertEquals("on", ok("POST", "/api/pressure/start", null).get("state").getAsString()); // it arms the watchdog
        assertEquals(1, events(ControllerReset.EVENT_WATCHDOG_ARMED).size(), "on record at once");
        ok("POST", "/api/pressure/stop", null);
        Thread.sleep(5);
        restartOpenPnp(); // OpenPnP died with the watchdog armed: it tripped again
        assertEquals(2, motorOffs().size(), "a trip reported after the arm is a new one");
        Thread.sleep(5);
        svc.getControllerReset().watchdogArmed(); // an arm in memory (a stream's, before the journal thread writes it)
        restartOpenPnp();
        assertEquals(3, motorOffs().size(), "the engine wrote the arm when it stopped");
    }

    /**
     * #11 review (11-04): after a reset's home a frame from K is refused also for a tip without a frame (the seeded 22G:
     * the page offers the bench's 65.59), and the engine checks under its lock: a K posted as the home ends, its check
     * passed before the stamp, is refused too.
     */
    @Test
    public void aFrameFromKIsRefusedAfterAResetsHomeWithoutAnOldFrameToo() throws Exception {
        assertTrue(svc.getConfig().needleAnchor == null, "the seeded tip has no frame");
        powerCycle();
        ok("POST", "/api/machine/home", "{\"wait\":true,\"clearBelowAttested\":true}");
        call("POST", "/api/needle/anchor", "{\"needleChangedAtMs\":null,\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("K was measured before the controller was reset"), lastBody);
        assertFalse(ok("GET", "/api/status", null).getAsJsonObject("dispense").getAsJsonObject("needle")
                .get("kRefusal").isJsonNull());
        double nz = svc.needleContactZFromRawSum(5.5, 65.59);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> svc.setNeedleAnchor(5.5, nz, null, null, 65.59, null));
        assertTrue(e.getMessage().contains("K was measured before the controller was reset"), e.getMessage());
        assertTrue(svc.getConfig().needleAnchor == null, "nothing recorded");
        ok("POST", "/api/needle/anchor", "{\"needleChangedAtMs\":null,\"surfaceZ\":5.5,\"needleContactZ\":2.91}");
        assertTrue(svc.needleFrameFromKRefusal() == null, "a frame after the home: K holds again");
        Thread.sleep(5);
        // the next reset's home has ended, its stamp in the settings comes a moment later: refused from the home on
        powerCycle();
        svc.getControllerReset().homed();
        assertTrue(svc.needleFrameFromKRefusal() != null, "from the home, before its stamp");
    }

    /**
     * #11 review (11-04): the status tells a reset's home from a needle change (needle.zFrameMovedAtMs), and the frame
     * recorded after it asks nothing about the tip: the old frame was of another Z frame, so a frame 1.5 mm off it says
     * nothing about which tip is on.
     */
    @Test
    public void aFrameAfterAResetsHomeAsksNoTipQuestion() throws Exception {
        ok("POST", "/api/needle/anchor", "{\"needleChangedAtMs\":null,\"surfaceZ\":5.5,\"needleContactZ\":2.91}");
        JsonObject n = ok("GET", "/api/status", null).getAsJsonObject("dispense").getAsJsonObject("needle");
        assertTrue(n.get("zFrameMovedAtMs").isJsonNull(), n.toString());
        Thread.sleep(5);
        powerCycle();
        ok("POST", "/api/machine/home", "{\"wait\":true,\"clearBelowAttested\":true}");
        n = ok("GET", "/api/status", null).getAsJsonObject("dispense").getAsJsonObject("needle");
        assertEquals(svc.getConfig().zFrameMovedAtMs.longValue(), n.get("zFrameMovedAtMs").getAsLong(), n.toString());
        JsonObject f = ok("POST", "/api/needle/anchor", "{\"needleChangedAtMs\":null,\"surfaceZ\":5.5,"
                + "\"needleContactZ\":1.41}");
        assertTrue(f.get("question").isJsonNull(), f.toString());
    }

    /** The engine's connection number (private): it moves on once the engine has heard a Stop. */
    long connection() throws Exception {
        java.lang.reflect.Field f = DispenseService.class.getDeclaredField("connection");
        f.setAccessible(true);
        return f.getLong(svc);
    }

    /**
     * #11 re-review (11-04) MAJOR: OpenPnP fires its listeners from a set, its own Stop on its own thread, so the next
     * Start can come before the engine has heard the Stop. That Start is a new connection all the same: its M114 is
     * read, and after a power cycle the home waits for the operator's word, with no Z move on the boot belief.
     */
    @Test
    public void aStartBeforeTheEngineHeardTheStopIsANewConnection() throws Exception {
        for (int attempt = 0; attempt < 400; attempt++) {
            if (attempt > 0 && attempt % 20 == 0) {
                restartOpenPnp(); // the engine's listener at another place in the set
                sim.machine.home();
            }
            long before = connection();
            boolean[] raced = {false};
            MachineListener racer = new MachineListener.Adapter() {
                @Override
                public void machineDisabled(Machine m, String reason) {
                    try {
                        if (!raced[0] && connection() == before) { // the engine's machineDisabled is still to come
                            raced[0] = true;
                            sim.server.powerCycle();
                            m.setEnabled(true); // Start, inside the Stop's listener loop
                        }
                    }
                    catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
            sim.machine.addListener(racer); // a new object: another place in the set
            try {
                sim.machine.setEnabled(false);
            }
            finally {
                sim.machine.removeListener(racer);
            }
            if (raced[0]) {
                assertTrue(sim.machine.isEnabled());
                JsonObject m = ok("GET", "/api/machine", null);
                assertFalse(m.get("positionKnown").getAsBoolean(), "its M114 was read: " + m);
                assertTrue(m.getAsJsonObject("powerCycle").get("detected").getAsBoolean(), m.toString());
                sim.server.mark();
                call("POST", "/api/machine/home", "{\"wait\":true}");
                assertEquals(409, lastStatus, lastBody);
                assertEquals(new ArrayList<String>(), motionAndOutputs(), "no Z move on the boot belief before G28");
                return;
            }
            sim.enableAndHome();
        }
        fail("no Start came before the engine heard the Stop in 400 tries");
    }

    /**
     * OpenPnP, stopped while the controller was power-cycled, starts again: Start is pressed before the engine's
     * listeners exist, and a task of OpenPnP's holds the machine thread until {@code busy} opens, with {@code queued}
     * (null: nothing) behind it, ahead of the engine's read. The engine's start waits its command timeout for that
     * read.
     */
    void startEngineWithTheMachineThreadBusy(CountDownLatch busy, Callable<Object> queued) throws Exception {
        DispenseConfig c = svc.getConfig();
        c.commandTimeoutMs = 1500;
        svc.updateConfig(c);
        sim.machine.setEnabled(false);
        sim.server.powerCycle();
        startEngineWith("listeners", () -> {
            sim.machine.setEnabled(true);
            sim.machine.submit(() -> busy.await(30, TimeUnit.SECONDS));
            return queued == null ? null : sim.machine.submit(queued);
        });
    }

    /**
     * #11 re-review (11-04): the engine's start ends without the catch-up's read when a task holds the machine thread:
     * the position counts as unknown meanwhile, and the read, once the thread is free, finds the power cycle.
     */
    @Test
    public void aCatchUpsReadHeldBehindABusyMachineThreadStillFindsTheReset() throws Exception {
        CountDownLatch busy = new CountDownLatch(1);
        try {
            startEngineWithTheMachineThreadBusy(busy, null);
            ControllerReset cr = svc.getControllerReset();
            assertFalse(cr.isPositionKnown(), "unknown while the read waits");
            assertTrue(cr.unknownWhy().contains("its position was never checked"), cr.unknownWhy());
        }
        finally {
            busy.countDown();
        }
        sim.machine.submit(() -> null).get(30, TimeUnit.SECONDS); // the read has run
        JsonObject m = ok("GET", "/api/machine", null);
        assertFalse(m.get("positionKnown").getAsBoolean(), m.toString());
        assertTrue(m.getAsJsonObject("powerCycle").get("detected").getAsBoolean(), "read once the thread ran: " + m);
        sim.server.mark();
        call("POST", "/api/machine/home", "{\"wait\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertEquals(new ArrayList<String>(), motionAndOutputs(), "a refused home sends nothing");
    }

    /**
     * #11 re-review (11-04): OpenPnP's own Home queued ahead of the catch-up's read makes the read skip (the machine is
     * homed by then): that home followed a connect never read, maybe a power cycle, so it moved the Z frame.
     */
    @Test
    public void aHomeQueuedAheadOfTheCatchUpsReadMovesTheZFrame() throws Exception {
        CountDownLatch busy = new CountDownLatch(1);
        try {
            startEngineWithTheMachineThreadBusy(busy, () -> {
                sim.machine.home();
                return null;
            });
            assertFalse(svc.getControllerReset().isPositionKnown(), "unknown while the read waits");
        }
        finally {
            busy.countDown();
        }
        sim.machine.submit(() -> null).get(30, TimeUnit.SECONDS); // the home, then the read, have run
        assertTrue(svc.getControllerReset().isPositionKnown(), "homed");
        assertNotNull(svc.getConfig().zFrameMovedAtMs, "the home after a connect never read moved the Z frame");
    }

    /**
     * #11 re-review (11-04): a reset seen before OpenPnP quit, then OpenPnP's Start and Home both before the engine
     * started again: that home followed the reset, so the engine's start stamps the Z frame it moved.
     */
    @Test
    public void aResetHomedBeforeTheEngineStartedMovesTheZFrameAtItsStart() throws Exception {
        powerCycle();
        sim.machine.setEnabled(false); // OpenPnP quits before the home
        startEngineWith("listeners", () -> {
            sim.machine.setEnabled(true);
            sim.machine.home();
            return null;
        });
        assertTrue(svc.getControllerReset().isPositionKnown(), "homed after the reset");
        assertNotNull(svc.getConfig().zFrameMovedAtMs, "the home after the reset moved the Z frame");
    }

    /**
     * #11 re-review (11-04): a restart whose old engine fails to stop reads "failed" with the reason, not "starting"
     * (the web Start and Home refuse while the engine starts), and the old engine's database is closed all the same.
     */
    @Test
    public void aRestartWhoseOldEngineFailsToStopReadsFailed() throws Exception {
        DispenseService old = svc;
        TipSim.hook(p -> {
            if ("shutdown".equals(p)) {
                throw new IllegalStateException("the old engine did not stop");
            }
        });
        try {
            assertThrows(IllegalStateException.class, () -> DispenseService.start(Configuration.get()));
        }
        finally {
            TipSim.hook(null);
        }
        assertEquals("failed", ok("GET", "/api/status", null).getAsJsonObject("dispense").get("state").getAsString());
        assertTrue(DispenseService.startError().contains("the old engine did not stop"), DispenseService.startError());
        assertThrows(java.sql.SQLException.class, () -> old.getDb().query("SELECT 1"), "its database is closed");
        old.shutdown(); // the rest of its stop: its listeners
        svc = DispenseService.start(Configuration.get());
        assertEquals("running", DispenseService.startState());
    }

    /** The response to a refused passthrough line: 400 refused-line, its rule, nothing sent. */
    void refusedLevel(String body, String rule) throws Exception {
        sim.server.mark();
        call("POST", "/api/gcode", body);
        assertEquals(400, lastStatus, body + ": " + lastBody);
        assertTrue(lastBody.contains("refused-line") && lastBody.contains(rule), body + ": " + lastBody);
        assertEquals(new ArrayList<String>(), motionAndOutputs(), body + " sends nothing");
    }

    /** The batch of these Z targets, F300, then M400, for the reason {@code what}. */
    static String level(String what, double... z) {
        StringBuilder sb = new StringBuilder("{\"lines\":[");
        for (double v : z) {
            sb.append("\"G1 Z").append(v).append(" F300\",");
        }
        return sb.append("\"M400\"],\"what\":\"").append(what).append("\"}").toString();
    }

    /** OpenPnP's believed Z (the controller axis Z, raw). */
    double believedZ() throws Exception {
        for (com.google.gson.JsonElement a : ok("GET", "/api/machine", null).getAsJsonArray("axes")) {
            JsonObject ax = a.getAsJsonObject();
            if (ax.has("letter") && "Z".equals(ax.get("letter").getAsString())) {
                return ax.get("driverCoordinate").getAsDouble();
            }
        }
        throw new AssertionError("no Z axis");
    }

    /**
     * BR-97 (the PM 16:4x): after a power cycle the see-saw is levelled before the home by Z alone, by the lock holder
     * through the passthrough: each line at most 1 mm from the Z before it, 5 mm per call, 32 mm from the Z where the
     * levelling began, F at most 300, with a reason; each call journaled. A home clears that reference.
     */
    @Test
    public void theSeeSawIsLevelledByZAloneBeforeTheHomeAfterAReset() throws Exception {
        powerCycle();
        lockToken = ok("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"see-saw levelling\"}")
                .get("token").getAsString();
        sim.server.mark();
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 Z1 F300\",\"M400\"],\"what\":\"see-saw levelling, needle tip y 494 "
                + "to 486\"}");
        assertEquals(java.util.Arrays.asList("G1 Z1 F300"), motionAndOutputs());
        assertEquals(1.0, believedZ(), 1e-6);
        List<java.util.Map<String, Object>> ev = events("Z_LEVEL");
        assertEquals(1, ev.size(), ev.toString());
        String row = String.valueOf(ev.get(0).get("message"));
        assertTrue(row.contains("process") && row.contains("G1 Z1 F300; M400")
                && row.contains("see-saw levelling, needle tip y 494 to 486"), row);

        refusedLevel("{\"lines\":[\"G1 X1 Z1.5 F300\"],\"what\":\"w\"}", "home");
        refusedLevel(level("w", 2.5), "1 mm");
        refusedLevel(level("w", 2, 3, 4, 5, 6, 7), "5 mm");
        refusedLevel("{\"lines\":[\"G1 Z1.5 F600\"],\"what\":\"w\"}", "300");
        refusedLevel("{\"lines\":[\"G1 Z1.5\"],\"what\":\"w\"}", "300");
        refusedLevel("{\"lines\":[\"G91\",\"G1 Z0.5 F300\"],\"what\":\"w\"}", "absolute");
        refusedLevel("{\"lines\":[\"G92 Z0\"],\"what\":\"w\"}", "G92");
        refusedLevel("{\"lines\":[\"G28 Z\"],\"what\":\"w\"}", "/api/machine/home");
        for (String body : new String[] {"{\"lines\":[\"G1 Z1.5 F300\"]}",
                "{\"lines\":[\"G1 Z1.5 F300\"],\"what\":\" \"}"}) {
            sim.server.mark();
            call("POST", "/api/gcode", body);
            assertEquals(400, lastStatus, lastBody);
            assertTrue(lastBody.contains("what"), lastBody);
            assertEquals(new ArrayList<String>(), motionAndOutputs(), "nothing sent");
        }
        assertEquals(1, events("Z_LEVEL").size(), "refused calls are not journaled");

        // to 31 in calls of 5 mm, then past 32 from Z 0, where it began: refused
        for (int c = 0; c < 6; c++) {
            double z = 1 + 5 * c;
            ok("POST", "/api/gcode", level("toward the far end", z + 1, z + 2, z + 3, z + 4, z + 5));
        }
        assertEquals(31.0, believedZ(), 1e-6);
        refusedLevel(level("w", 32, 33), "32 mm");
        assertEquals(7, events("Z_LEVEL").size());

        // the home clears the reference: unhomed again at Z 30 (a Stop and Start, powered), 33 is 3 mm from it
        ok("POST", "/api/machine/home", "{\"wait\":true,\"clearBelowAttested\":true}");
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 Z30 F600\",\"M400\"]}");
        reconnect();
        assertFalse(sim.machine.isHomed());
        assertEquals(30.0, believedZ(), 1e-6);
        ok("POST", "/api/gcode", level("w", 31, 32, 33));
        assertEquals(33.0, believedZ(), 1e-6);
    }

    /** BR-97: a levelling call after one that left its moves unwaited reads the controller's Z first. */
    @Test
    public void aLevellingCallAfterAnUnwaitedMoveStartsFromTheControllersZ() throws Exception {
        powerCycle();
        lockToken = ok("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"see-saw levelling\"}")
                .get("token").getAsString();
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 Z1 F300\"],\"what\":\"w\"}");
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 Z2 F300\"],\"what\":\"w\"}");
        refusedLevel("{\"lines\":[\"G1 Z0.5 F300\"],\"what\":\"w\"}", "1 mm");
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 Z1.5 F300\",\"M400\"],\"what\":\"w\"}");
        assertEquals(1.5, believedZ(), 1e-6);
    }
}
