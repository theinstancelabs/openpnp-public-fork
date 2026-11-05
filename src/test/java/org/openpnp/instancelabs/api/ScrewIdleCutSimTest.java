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
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.ControllerCommands;
import org.openpnp.instancelabs.dispense.ControllerReset;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-92 on the simulated controller: the engine reads M115 at every connect. The holdB build (its FIRMWARE_NAME
 * carries the marker il-holdB: built without DISABLE_IDLE_J) keeps the screw's motor powered through the controller's
 * idle timeout, so no idle switch-off is counted; any other firmware, or an M115 not read, keeps the timeout
 * (screwIdleOffS). An M18 B still switches the motor off.
 */
public class ScrewIdleCutSimTest {
    /** The holdB build's FIRMWARE_NAME (firmware/holdB/README.md). */
    static final String HOLD_B = "Marlin bugfix-2.1.x-il-holdB (Nov  4 2026 22:37:24)";
    /** The board's firmware before the flash (Opulo's v4.0.0 release). */
    static final String STOCK = "Marlin bugfix-2.1.x (Sep 19 2024 13:39:21)";
    static final long HOUR = 3600_000L;
    /** What follows FIRMWARE_NAME on the real M115 line (firmware/holdB/firmware.bin): the name ends at the next key. */
    static final String M115_REST = " SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin PROTOCOL_VERSION:1.0"
            + " MACHINE_TYPE:LumenPnP EXTRUDER_COUNT:0 AXIS_COUNT:5";

    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;
    int lastStatus;
    String lastBody;
    /** The machine lock's token while this test holds it (BR-72), or null. */
    String token;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine(false); // each test names the firmware before its first connect
        svc = DispenseService.start(Configuration.get());
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    public void after() throws Exception {
        PlannedStopSimTest.engineClock(null);
        sim.server.mute.clear();
        api.stop();
        svc.shutdown();
        sim.close();
    }

    JsonObject call(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120));
        if (token != null) {
            b.header(MachineLock.TOKEN_HEADER, token);
        }
        b.header("Content-Type", "application/json");
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
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

    /** The controller's M115 names this FIRMWARE_NAME (null: none), on a line that goes on as the real one does. */
    void firmware(String name) {
        sim.server.firmwareName = name == null ? null : name + M115_REST;
    }

    /** The controller answers M115 with this FIRMWARE_NAME (null: none); OpenPnP connects and homes. */
    void connect(String firmwareName) throws Exception {
        firmware(firmwareName);
        sim.enableAndHome();
    }

    /** OpenPnP Stop and Start, the controller powered all along, which now answers M115 with this FIRMWARE_NAME. */
    void reconnect(String firmwareName) throws Exception {
        sim.machine.setEnabled(false);
        firmware(firmwareName);
        sim.machine.setEnabled(true);
    }

    /** status.machine.screwIdleCut */
    JsonObject idleCut() throws Exception {
        return ok("GET", "/api/machine", null).getAsJsonObject("screwIdleCut");
    }

    List<Map<String, Object>> motorOffs() throws Exception {
        return svc.getDb().query("SELECT * FROM events WHERE code = ?", ControllerReset.EVENT_MOTOR_OFF);
    }

    String journal() throws Exception {
        return String.valueOf(svc.getDb().recentEvents(20));
    }

    /** The status's I11 item. */
    JsonObject i11() throws Exception {
        for (JsonElement e : ok("GET", "/api/status", null).getAsJsonArray("interlocks")) {
            if ("I11".equals(e.getAsJsonObject().get("id").getAsString())) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no I11 in the status");
    }

    void takeTheLock() throws Exception {
        token = ok("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"start angle\"}").get("token")
                .getAsString();
    }

    void giveTheLockBack() throws Exception {
        ok("POST", "/api/lock/release", "{}");
        token = null;
    }

    /**
     * The screw turns (the dot pair that measures the start angle: a G1 B through the passthrough) and the angle is set:
     * returns a time at or after the turn, the controller's last move.
     */
    long turnScrewAndSetTheAngle() throws Exception {
        takeTheLock();
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 B30 F600\",\"M400\"]}");
        long turned = System.currentTimeMillis();
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        giveTheLockBack();
        return turned;
    }

    @Test
    public void theHoldBBuildKeepsTheScrewMotorOnPastTheIdleTimeout() throws Exception {
        JsonObject none = idleCut();
        assertTrue(none.get("cut").getAsBoolean(), "before any connect: the safe side: " + none);
        assertTrue(none.get("firmware").isJsonNull() && none.get("readAtMs").isJsonNull(), none.toString());

        long before = System.currentTimeMillis();
        connect(HOLD_B);
        long connected = System.currentTimeMillis();
        long turned = turnScrewAndSetTheAngle();
        PlannedStopSimTest.engineClock(() -> turned + 2 * HOUR);
        assertEquals("ok", i11().get("state").getAsString(), i11().toString());
        assertEquals(0, motorOffs().size(), journal());

        JsonObject cut = idleCut();
        assertEquals(3, cut.entrySet().size(), "{cut, firmware, readAtMs}: " + cut);
        assertFalse(cut.get("cut").getAsBoolean(), cut.toString());
        assertEquals(HOLD_B, cut.get("firmware").getAsString());
        long readAt = cut.get("readAtMs").getAsLong();
        assertTrue(readAt >= before && readAt <= connected, "read at the connect: " + cut);
    }

    @Test
    public void otherFirmwareSwitchesTheScrewMotorOffAtTheIdleTimeout() throws Exception {
        connect(STOCK);
        long turned = turnScrewAndSetTheAngle();
        PlannedStopSimTest.engineClock(() -> turned + HOUR - 60_000);
        assertEquals("ok", i11().get("state").getAsString(), "not idle for 3600 s yet: " + i11());
        PlannedStopSimTest.engineClock(() -> turned + HOUR + 60_000);
        String text = i11().get("text").getAsString();
        assertTrue(text.contains("(the controller's idle timeout: no move for 60 min)")
                && text.contains("measure the start angle again"), text);
        List<Map<String, Object>> offs = motorOffs();
        assertEquals(1, offs.size(), journal());
        long at = ((Number) offs.get(0).get("ts")).longValue();
        assertTrue(at > turned + HOUR - 60_000 && at <= turned + HOUR, "the last move + 3600 s: " + at);

        JsonObject cut = idleCut();
        assertTrue(cut.get("cut").getAsBoolean(), cut.toString());
        assertEquals(STOCK, cut.get("firmware").getAsString());
    }

    /** Each connect decides afresh: a reconnect to a controller without the marker brings the idle timeout back. */
    @Test
    public void aReconnectWithoutTheMarkerBringsTheIdleCutBack() throws Exception {
        connect(HOLD_B);
        long turned = turnScrewAndSetTheAngle();
        PlannedStopSimTest.engineClock(() -> turned + 2 * HOUR);
        assertEquals("ok", i11().get("state").getAsString(), i11().toString());
        assertEquals(0, motorOffs().size(), journal());

        reconnect(STOCK);
        assertTrue(i11().get("text").getAsString().contains("measure the start angle again"), i11().toString());
        List<Map<String, Object>> offs = motorOffs();
        assertEquals(1, offs.size(), journal());
        long at = ((Number) offs.get(0).get("ts")).longValue();
        assertTrue(at > turned + HOUR - 60_000 && at <= turned + HOUR, "the last move + 3600 s: " + at);
        JsonObject cut = idleCut();
        assertTrue(cut.get("cut").getAsBoolean(), cut.toString());
        assertEquals(STOCK, cut.get("firmware").getAsString());
        assertEquals(turned + 2 * HOUR, cut.get("readAtMs").getAsLong(), "read at this connect: " + cut);
    }

    @Test
    public void anM18BIsStillASwitchOffOnTheHoldBBuild() throws Exception {
        connect(HOLD_B);
        turnScrewAndSetTheAngle();
        takeTheLock();
        ok("POST", "/api/gcode", "{\"lines\":[\"M18 B\"]}");
        giveTheLockBack();
        List<Map<String, Object>> offs = motorOffs();
        assertEquals(1, offs.size(), journal());
        String why = String.valueOf(offs.get(0).get("message"));
        assertTrue(why.endsWith(" (a G-code line: M18 B)"), why);
        assertTrue(i11().get("text").getAsString().contains(why + " after the screw start angle was set"),
                i11().toString());
    }

    /** An M115 without FIRMWARE_NAME, or one not read at the connect (a timeout), keeps the cut: the safe side. */
    @Test
    public void anM115WithoutAFirmwareNameOrNotReadKeepsTheIdleCut() throws Exception {
        connect(HOLD_B);
        assertFalse(idleCut().get("cut").getAsBoolean());
        reconnect(null);
        JsonObject cut = idleCut();
        assertTrue(cut.get("cut").getAsBoolean(), cut.toString());
        assertTrue(cut.get("firmware").isJsonNull(), cut.toString());

        reconnect(HOLD_B);
        assertFalse(idleCut().get("cut").getAsBoolean());
        sim.machine.home();
        long turned = turnScrewAndSetTheAngle();
        ok("PUT", "/api/config", "{\"commandTimeoutMs\":400}");
        sim.server.mute.add(ControllerCommands.CAPABILITY_QUERY);
        long before = System.currentTimeMillis();
        reconnect(HOLD_B);
        sim.server.mute.clear();
        cut = idleCut();
        assertTrue(cut.get("cut").getAsBoolean(), cut.toString());
        assertTrue(cut.get("firmware").isJsonNull(), cut.toString());
        assertTrue(cut.get("readAtMs").getAsLong() >= before, cut.toString());
        PlannedStopSimTest.engineClock(() -> turned + HOUR + 60_000);
        assertTrue(i11().get("text").getAsString().contains("(the controller's idle timeout: no move for 60 min)"),
                i11().toString());
        assertEquals(1, motorOffs().size(), journal());
    }

    /** Without the dispenser nozzle the engine reads no M115 at a connect: the cut, as for one not read. */
    @Test
    public void aConnectWithoutTheDispenserNozzleKeepsTheIdleCut() throws Exception {
        connect(HOLD_B);
        DispenseConfig c = svc.getConfig();
        c.nozzle = "no-such-nozzle";
        svc.updateConfig(c);
        reconnect(HOLD_B);
        @SuppressWarnings("unchecked")
        Map<String, Object> cut = (Map<String, Object>) svc.getControllerReset().view().get("screwIdleCut");
        assertEquals(Boolean.TRUE, cut.get("cut"), cut.toString());
        assertEquals(null, cut.get("firmware"), cut.toString());
    }

    /** A connect made before the engine started is read at its start (BR-02's catch-up): its M115 decides too. */
    @Test
    public void aConnectMadeBeforeTheEngineStartedDecidesAtItsStart() throws Exception {
        connect(HOLD_B);
        sim.machine.setEnabled(false);
        firmware(STOCK);
        // OpenPnP starts again, and Start is pressed before the engine's listeners exist
        TipSim.hook(p -> {
            if ("listeners".equals(p)) {
                try {
                    sim.machine.setEnabled(true);
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
        assertTrue(sim.machine.isEnabled());
        JsonObject cut = idleCut();
        assertTrue(cut.get("cut").getAsBoolean(), cut.toString());
        assertEquals(STOCK, cut.get("firmware").getAsString());
    }

    /**
     * Review MINOR 1: the board went back to stock firmware while OpenPnP was down (a rollback flash), OpenPnP
     * connected before the engine started and jogged before the engine read M115. That jog is judged with the
     * firmware unread (the idle cut), never with the last connect's holdB: the switch-off an hour after the last move
     * stays on record.
     */
    @Test
    public void aMoveBeforeTheEngineReadsTheFirmwareKeepsTheIdleSwitchOff() throws Exception {
        connect(HOLD_B);
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND, String.join("\n",
                ControllerCommands.STOP_SEQUENCE));
        long turned = turnScrewAndSetTheAngle();
        ok("POST", "/api/stop/planned", null);
        PlannedStopSimTest.engineClock(() -> turned + 2 * HOUR);
        sim.machine.setEnabled(false);
        firmware(STOCK);
        // OpenPnP starts again: Start before the engine's listeners exist, a jog before its catch-up reads M115
        TipSim.hook(p -> {
            try {
                if ("listeners".equals(p)) {
                    sim.machine.setEnabled(true);
                }
                else if ("catch-up".equals(p)) {
                    sim.machine.execute(() -> {
                        sim.n1.moveTo(sim.n1.getLocation().add(new Location(LengthUnit.Millimeters, 1, 0, 0, 0)),
                                Motion.MotionOption.JogMotion);
                        return null;
                    });
                }
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            svc = DispenseService.start(Configuration.get());
        }
        finally {
            TipSim.hook(null);
        }
        List<Map<String, Object>> offs = motorOffs();
        assertEquals(1, offs.size(), journal());
        long at = ((Number) offs.get(0).get("ts")).longValue();
        assertTrue(at > turned + HOUR - 60_000 && at <= turned + HOUR, "the last move + 3600 s: " + journal());
        JsonObject cut = idleCut();
        assertTrue(cut.get("cut").getAsBoolean(), cut.toString());
        assertEquals(STOCK, cut.get("firmware").getAsString());
    }

    /**
     * BR-75 follows it: on the holdB build a planned stop longer than the idle timeout keeps the angle, across the
     * deploy's restart too. The new engine starts with the last connect's decision (on record), so the status polls
     * before the next Start count no idle switch-off, and the Start asks a confirm, not a measure.
     */
    @Test
    public void aPlannedStopLongerThanTheIdleTimeoutKeepsTheAngleOnTheHoldBBuild() throws Exception {
        connect(HOLD_B);
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND, String.join("\n",
                ControllerCommands.STOP_SEQUENCE));
        long turned = turnScrewAndSetTheAngle();
        ok("POST", "/api/stop/planned", null);
        PlannedStopSimTest.engineClock(() -> turned + 2 * HOUR);
        sim.machine.setEnabled(false); // OpenPnP quits and starts again
        svc = DispenseService.start(Configuration.get());

        i11(); // the deployer's and the UI's polls before the Start
        assertEquals(0, motorOffs().size(), journal());
        JsonObject cut = idleCut();
        assertFalse(cut.get("cut").getAsBoolean(), cut.toString());
        assertEquals(HOLD_B, cut.get("firmware").getAsString());

        ok("POST", "/api/machine/enable", null);
        String text = i11().get("text").getAsString();
        assertTrue(text.contains("the controller (re)connected"), "a confirm: " + text);
        assertFalse(text.contains("the screw may have turned"), "no measure: " + text);
        ok("POST", "/api/screw/start-angle", "{}");
        assertEquals("ok", i11().get("state").getAsString(), i11().toString());
        assertEquals(0, motorOffs().size(), journal());
    }
}
