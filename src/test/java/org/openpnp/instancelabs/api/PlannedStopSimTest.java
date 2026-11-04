package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.ControllerCommands;
import org.openpnp.instancelabs.dispense.ControllerReset;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DoseBlocksRequest;
import org.openpnp.instancelabs.dispense.DoseBlocksResult;
import org.openpnp.instancelabs.dispense.DoseRecord;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Motion;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.openpnp.spi.MotionPlanner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-75 on the simulated controller: the deployer's planned stop switches the outputs off as STOP does but sends no
 * M18 B, so the screw's motor stays powered: after the next Start I11 asks a confirm, not a measure, and the
 * partial-turn records made at the angle stay evidence. Refused while anything works; guarded by the machine lock,
 * while STOP stays open and unchanged.
 */
public class PlannedStopSimTest {
    static final String UI = "ui";
    /** The live DISABLE_COMMAND (machine.xml, bench F9) without its M18 B. */
    static final List<String> PLANNED = Arrays.asList("M410", "M107 P3", "M107 P2", "M107 P1", "M107 P0");

    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;
    int lastStatus;
    String lastBody;
    /** Who sends the calls: null (anonymous: the deployer, curl), {@link #UI} (the web UI) or an agent's lock token. */
    String who;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine(false);
        svc = DispenseService.start(Configuration.get());
        sim.enableAndHome(); // the service sees this connect
        // the live machine's DISABLE_COMMAND: its last line switches the screw's motor off
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND, String.join("\n",
                ControllerCommands.STOP_SEQUENCE));
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    public void after() throws Exception {
        TipSim.hook(null);
        engineClock(null);
        MachineEndpoints.plannedStopHook = null;
        MachineEndpoints.plannedStopWaitMs = MachineEndpoints.START_WAITS_FOR_SWITCH_OFF_MS;
        sim.server.holdOkUntil.clear();
        sim.server.mute.clear();
        api.stop();
        svc.shutdown();
        sim.close();
    }

    HttpRequest request(String method, String path, String json) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120));
        if (UI.equals(who)) {
            b.header(MachineLock.UI_HEADER, "ui");
        }
        else if (who != null) {
            b.header(MachineLock.TOKEN_HEADER, who);
        }
        b.header("Content-Type", "application/json");
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return b.build();
    }

    JsonObject call(String method, String path, String json) throws Exception {
        HttpResponse<String> r = http.send(request(method, path, json), HttpResponse.BodyHandlers.ofString());
        lastStatus = r.statusCode();
        lastBody = r.body();
        return new JsonParser().parse(r.body()).getAsJsonObject();
    }

    JsonObject ok(String method, String path, String json) throws Exception {
        JsonObject o = call(method, path, json);
        assertTrue(lastStatus >= 200 && lastStatus < 300, lastStatus + " " + o);
        return o;
    }

    static List<String> strings(JsonArray a) {
        List<String> l = new ArrayList<>();
        for (JsonElement e : a) {
            l.add(e.getAsString());
        }
        return l;
    }

    /**
     * The lines since the mark are a planned stop's: OpenPnP's disable listeners switch the outputs off on the open link
     * first (the live VAC1/VAC2 ActuateOff), then {@link #PLANNED}; no M18 B.
     */
    void assertPlannedStopSent() {
        List<String> l = sim.server.sinceMark();
        int n = l.size() - PLANNED.size();
        assertTrue(n >= 0 && PLANNED.equals(l.subList(n, l.size())), l.toString());
        for (String s : l.subList(0, n)) {
            assertTrue(s.startsWith("M107") || "M400".equals(s), "only outputs off before them: " + l);
        }
    }

    /** The journal's screw motor switch-offs. */
    List<java.util.Map<String, Object>> motorOffs() throws Exception {
        return svc.getDb().query("SELECT * FROM events WHERE code = ?", ControllerReset.EVENT_MOTOR_OFF);
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

    /** I11 asks a confirm only: the angle survived (no reset, no switch-off of the screw's motor). */
    void assertConfirmAsked() throws Exception {
        String text = i11().get("text").getAsString();
        assertTrue(text.contains("the controller (re)connected"), "a confirm: " + text);
        assertFalse(text.contains("the screw may have turned") || text.contains("was reset"), "no measure: " + text);
    }

    JsonObject profile() throws Exception {
        return ok("GET", "/api/status", null).getAsJsonObject("dispense").getAsJsonObject("profile");
    }

    /** A half-turn optical dose record of the current setup, made now: the camera's evidence at this start angle. */
    long halfTurnRecord() throws Exception {
        DispenseConfig c = svc.getConfig();
        DoseRecord r = new DoseRecord();
        r.kind = DoseRecord.OPTICAL;
        r.steps = c.bStepsPerRev / 2;
        r.n = 28;
        r.pressurePsi = c.profile.pressurePsi;
        r.doseFp = c.profile.doseFingerprint();
        r.tipKey = c.tip.key;
        r.pasteKey = c.paste.key;
        r.regulatorPsi = c.profile.pressurePsi;
        r.regulatorAtMs = System.currentTimeMillis();
        r.simulated = svc.isSimulatorConfirmed();
        r.method = r.simulated ? "sim" : "auto";
        return svc.getDb().insertDoseRecord(r).id;
    }

    /** The engine's clock of the controller's idle timeout and its journal (BR-02); null: the system's. */
    static void engineClock(java.util.function.LongSupplier c) throws Exception {
        java.lang.reflect.Field f = DispenseService.class.getDeclaredField("testClock");
        f.setAccessible(true);
        f.set(null, c);
    }

    /**
     * OpenPnP quits (its quit disables the machine: nothing goes out on the closed link) and starts again, the
     * controller powered all along: the engine starts afresh on the same database.
     */
    void quitAndRestartOpenPnp() throws Exception {
        sim.server.mark();
        sim.machine.setEnabled(false);
        assertEquals(Collections.emptyList(), sim.server.sinceMark(), "OpenPnP's quit sends no M18 B");
        svc = DispenseService.start(Configuration.get());
    }

    /**
     * The screw turns (the dot pair that measures the start angle: a G1 B through the passthrough, as the lock's agent),
     * the angle is set and the lock given back: returns a time at or after the turn, the controller's last move.
     */
    long turnScrewAndSetTheAngle() throws Exception {
        who = ok("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"start angle\"}").get("token")
                .getAsString();
        ok("POST", "/api/gcode", "{\"lines\":[\"G1 B30 F600\",\"M400\"]}");
        long turned = System.currentTimeMillis();
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        ok("POST", "/api/lock/release", "{}");
        who = null;
        return turned;
    }

    /** Why the record is no evidence now, as GET /api/dose-records says it (null: it is). */
    String whyStale(long id) throws Exception {
        return svc.getDb().doseRecord(id).whyStale(System.currentTimeMillis(), svc.getConfig(), svc.doseEpochs());
    }

    @Test
    public void aPlannedStopSwitchesTheOutputsOffAndLeavesTheScrewMotorOn() throws Exception {
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        Thread.sleep(5);
        sim.server.mark();
        JsonObject r = ok("POST", "/api/stop/planned", null);
        assertTrue(r.get("planned").getAsBoolean(), r.toString());
        assertTrue(r.get("stopped").getAsBoolean(), r.toString());
        assertFalse(r.get("machineEnabled").getAsBoolean(), r.toString());
        assertEquals(PLANNED, strings(r.getAsJsonArray("linesSent")), r.toString());
        assertEquals(Collections.emptyList(), strings(r.getAsJsonArray("linesFailed")), r.toString());
        assertPlannedStopSent(); // the DISABLE_COMMAND without its M18 B
        assertFalse(sim.machine.isEnabled());
        assertFalse(sim.driver.isConnected(), "the link closed after its lines: no DISABLE_COMMAND");
        assertEquals(0, motorOffs().size(), String.valueOf(svc.getDb().recentEvents(20)));
        assertTrue(ok("GET", "/api/machine", null).getAsJsonObject("screwMotorOff").get("atMs").isJsonNull());

        ok("POST", "/api/machine/enable", null);
        assertConfirmAsked();
        ok("POST", "/api/screw/start-angle", "{}");
        assertEquals("ok", i11().get("state").getAsString());
        assertEquals(118, profile().get("startAngleDeg").getAsDouble(), 0);
    }

    /**
     * Review MAJOR: STOP's order, M18 B aside. OpenPnP's disable listeners act on the open link (the live machine.xml
     * switches VAC1 and VAC2 off at every disable: on a closed link each planned stop failed them with an IO error and
     * left OpenPnP's record of them on), then the lines without M18 B go out, then the link closes.
     */
    @Test
    public void openPnpsDisableListenersSwitchTheOutputsOffOnTheOpenLink() throws Exception {
        ReferenceActuator vac1 = (ReferenceActuator) sim.machine.getDefaultHead().getActuatorByName("VAC1");
        ReferenceActuator vac2 = (ReferenceActuator) sim.machine.getDefaultHead().getActuatorByName("VAC2");
        sim.machine.execute(() -> {
            vac1.actuate(true);
            vac2.actuate(true);
            return null;
        });
        assertEquals(Boolean.TRUE, vac2.getLastActuationValue());
        sim.server.mark();
        ok("POST", "/api/stop/planned", null);
        assertEquals(Boolean.FALSE, vac1.getLastActuationValue(), "VAC1 off: its lines went out on the open link");
        assertEquals(Boolean.FALSE, vac2.getLastActuationValue(), "VAC2 off: its lines went out on the open link");
        assertPlannedStopSent();
        List<String> l = sim.server.sinceMark();
        assertTrue(l.indexOf("M410") > 0, "the outputs' lines first: " + l);
        // re-review NIT 5: VAC2 off as on the machine: the valve vents (P3) before the pump stops (P2)
        assertTrue(l.indexOf("M107 P3;") >= 0 && l.indexOf("M107 P3;") < l.indexOf("M107 P2;"), l.toString());
        assertFalse(sim.driver.isConnected());
    }

    /** Review MINOR 1: a planned stop whose task did not start in time says so, and never runs later. */
    @Test
    public void aPlannedStopThatDidNotStartInTimeNeverRuns() throws Exception {
        CountDownLatch out = new CountDownLatch(1);
        MachineEndpoints.plannedStopWaitMs = 500;
        // a task of OpenPnP's own starts after the route's checks: the planned stop queues behind it
        MachineEndpoints.plannedStopHook = () -> sim.machine.submit(() -> {
            out.await(30, TimeUnit.SECONDS);
            return null;
        });
        sim.server.mark();
        JsonObject r = call("POST", "/api/stop/planned", null);
        assertEquals(409, lastStatus, lastBody);
        String reason = r.get("reason").getAsString();
        assertTrue(reason.contains("did not start") && reason.contains("it will not run"), reason);
        MachineEndpoints.plannedStopHook = null;
        out.countDown();
        sim.machine.submit(() -> null).get(30, TimeUnit.SECONDS); // the planned stop's task has had its turn
        assertEquals(Collections.emptyList(), sim.server.sinceMark(), "nothing sent, then or later");
        assertTrue(sim.machine.isEnabled());
        assertTrue(sim.driver.isConnected());
    }

    /** Review MINOR 2: linesSent are the lines the controller acknowledged; one without its ok is in linesFailed. */
    @Test
    public void linesSentAreTheOnesTheControllerAcknowledged() throws Exception {
        sim.server.mute.add("M107 P1"); // its ok never comes
        JsonObject r = ok("POST", "/api/stop/planned", null);
        assertEquals(Arrays.asList("M410", "M107 P3", "M107 P2", "M107 P0"), strings(r.getAsJsonArray("linesSent")),
                r.toString());
        assertEquals(Collections.singletonList("M107 P1"), strings(r.getAsJsonArray("linesFailed")), r.toString());
        assertFalse(sim.machine.isEnabled());
        assertFalse(sim.driver.isConnected());
    }

    /**
     * Review MINOR 3, re-review NIT 2: after a planned stop (the machine off, the screw's link closed) a real STOP sends
     * nothing, and its answer says so: nothing can switch the screw's motor off until the next Start.
     */
    @Test
    public void aStopAfterAPlannedStopSaysNothingCanSwitchTheScrewOff() throws Exception {
        ok("POST", "/api/stop/planned", null);
        sim.server.mark();
        JsonObject r = ok("POST", "/api/stop", null);
        String acts = r.getAsJsonArray("actions").toString();
        assertTrue(acts.contains("machine was already stopped"), acts);
        assertTrue(acts.contains("the screw's controller link is closed") && acts.contains("until the next Start"), acts);
        assertEquals(Collections.emptyList(), sim.server.sinceMark());
    }

    /**
     * The PM (re-review, a safety fix): STOP is always allowed, and nothing in front of OpenPnP's Stop may skip it. With
     * the lookup of the screw's link failing (an axis without a name makes it throw), POST /api/stop and Take control
     * from a working holder still send the whole DISABLE_COMMAND (M410 first, valve, pump, N1's valve and pump, M18 B),
     * the machine reads off and both answer 200.
     */
    @Test
    public void aStopWhoseLinkLookupFailsStillSwitchesEverythingOff() throws Exception {
        org.openpnp.spi.base.AbstractAxis a = null;
        for (org.openpnp.spi.Axis x : sim.machine.getAxes()) {
            if ("a".equals(x.getName())) {
                a = (org.openpnp.spi.base.AbstractAxis) x; // N1's rotation: the lookup meets it before the screw's
            }
        }
        try {
            // Take control from an agent whose pressure hold is on (a working holder: it is stopped as STOP does)
            sim.server.hostWatchdogCapability = false;
            sim.towelUnderNeedle(svc);
            String agent = ok("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"hold\"}").get("token")
                    .getAsString();
            who = agent;
            assertEquals("on", ok("POST", "/api/pressure/start", null).get("state").getAsString());
            a.setName(null);
            sim.server.mark();
            who = UI;
            JsonObject take = call("POST", "/api/lock/take", "{}");
            assertEquals(200, lastStatus, lastBody);
            assertTrue(take.getAsJsonObject("stopped").get("confirmed").getAsBoolean(), take.toString());
            assertInOrder(sim.server.sinceMark(), ControllerCommands.STOP_SEQUENCE);
            assertFalse(sim.machine.isEnabled());

            a.setName("a");
            sim.machine.setEnabled(true);
            a.setName(null);
            sim.server.mark();
            who = null;
            JsonObject stop = call("POST", "/api/stop", null);
            assertEquals(200, lastStatus, lastBody);
            assertTrue(stop.get("stopped").getAsBoolean(), stop.toString());
            long until = System.currentTimeMillis() + 10_000;
            while (sim.machine.isEnabled() && System.currentTimeMillis() < until) {
                Thread.sleep(20);
            }
            assertFalse(sim.machine.isEnabled());
            assertInOrder(sim.server.sinceMark(), ControllerCommands.STOP_SEQUENCE);
        }
        finally {
            a.setName("a");
        }
    }

    /** {@code expected} appear in {@code lines} in their order (other lines may come between). */
    static void assertInOrder(List<String> lines, List<String> expected) {
        int i = 0;
        for (String l : lines) {
            if (i < expected.size() && expected.get(i).equals(l)) {
                i++;
            }
        }
        assertEquals(expected.size(), i, "in order " + expected + ": " + lines);
    }

    /**
     * Re-review M-1: a disable listener that throws stops OpenPnP's Stop before its drivers: the machine still reads
     * started, the link is open, nothing of the planned stop went out. The answer says so, not "stopped": the deployer
     * would quit OpenPnP, and the quit's disable sends the whole DISABLE_COMMAND, M18 B included.
     */
    @Test
    public void aPlannedStopWhoseSwitchOffFailedSaysSo() throws Exception {
        MachineListener throwing = new MachineListener.Adapter() {
            @Override
            public void machineAboutToBeDisabled(Machine machine, String reason) {
                throw new IllegalStateException("a disable listener that throws");
            }
        };
        sim.machine.addListener(throwing);
        try {
            sim.server.mark();
            JsonObject r = call("POST", "/api/stop/planned", null);
            assertEquals(409, lastStatus, lastBody);
            String reason = r.get("reason").getAsString();
            assertTrue(reason.startsWith("No planned stop: OpenPnP's Stop did not switch the machine off")
                    && reason.endsWith("The real STOP is POST /api/stop"), reason);
            assertTrue(sim.machine.isEnabled());
            assertTrue(sim.driver.isConnected());
            assertFalse(sim.server.sinceMark().contains("M410"), sim.server.sinceMark().toString());
        }
        finally {
            sim.machine.removeListener(throwing);
        }
    }

    /**
     * Re-review M-2: the planned stop's override is its own thread's only. A disable of the driver on another thread in
     * the planned stop's window (OpenPnP's own Stop) sends the whole DISABLE_COMMAND, M18 B included, counted once.
     */
    @Test
    public void aDisableOnAnotherThreadInThePlannedStopsWindowSendsTheWholeDisableCommand() throws Exception {
        CountDownLatch inWindow = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        MachineListener pause = new MachineListener.Adapter() {
            @Override
            public void machineAboutToBeDisabled(Machine machine, String reason) {
                if (machine.isTask(Thread.currentThread())) { // the planned stop's thread, inside its window
                    inWindow.countDown();
                    try {
                        otherDone.await(30, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        sim.machine.addListener(pause);
        try {
            long counted = sim.driver.getDisableCommandsSent();
            sim.server.mark();
            CompletableFuture<HttpResponse<String>> planned = http.sendAsync(request("POST", "/api/stop/planned",
                    null), HttpResponse.BodyHandlers.ofString());
            assertTrue(inWindow.await(30, TimeUnit.SECONDS));
            Thread other = new Thread(() -> {
                try {
                    sim.driver.setEnabled(false);
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            other.start();
            other.join(30_000);
            otherDone.countDown();
            assertEquals(200, planned.get(30, TimeUnit.SECONDS).statusCode());
            List<String> l = sim.server.sinceMark();
            assertEquals(1, Collections.frequency(l, "M18 B"), l.toString());
            for (String s : PLANNED) {
                assertEquals(2, Collections.frequency(l, s), s + " from both: " + l);
            }
            assertEquals(counted + 1, sim.driver.getDisableCommandsSent(), "the other thread's, counted once");
        }
        finally {
            sim.machine.removeListener(pause);
        }
    }

    /**
     * Re-review NIT 4: a planned stop whose wait is interrupted (OpenPnP stops its web API: the request threads are
     * interrupted) never runs later.
     */
    @Test
    public void aPlannedStopWhoseWaitIsInterruptedNeverRuns() throws Exception {
        CountDownLatch queued = new CountDownLatch(1);
        CountDownLatch out = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> route = new java.util.concurrent.atomic.AtomicReference<>();
        MachineEndpoints.plannedStopHook = () -> {
            route.set(Thread.currentThread()); // the hook runs on the route's request thread
            sim.machine.submit(() -> {
                out.await(30, TimeUnit.SECONDS);
                return null;
            });
            queued.countDown();
        };
        sim.server.mark();
        http.sendAsync(request("POST", "/api/stop/planned", null), HttpResponse.BodyHandlers.ofString());
        assertTrue(queued.await(30, TimeUnit.SECONDS));
        Thread.sleep(200); // the route waits for its task
        api.stop();
        MachineEndpoints.plannedStopHook = null;
        // the interrupted route claims its task in its catch: release the machine thread only after that ran (its
        // request thread ends then: the stopped server's pool lets it go), else the two race (CI #47, 11-04)
        route.get().join(10_000);
        assertFalse(route.get().isAlive(), "the route's thread ended after the interrupt");
        out.countDown();
        sim.machine.submit(() -> null, null, true).get(30, TimeUnit.SECONDS); // the planned stop's task had its turn
        assertEquals(Collections.emptyList(), sim.server.sinceMark(), "nothing sent, then or later");
        assertTrue(sim.machine.isEnabled());
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s); // for after()
    }

    /** Review MINOR 6: the machine task checks again: work that began after the route's checks refuses it. */
    @Test
    public void theTaskChecksAgainWhatBeganAfterTheRoutesChecks() throws Exception {
        sim.server.hostWatchdogCapability = false;
        sim.towelUnderNeedle(svc);
        MachineEndpoints.plannedStopHook = () -> {
            try {
                api.getContext().pressure.start(svc); // the pressure hold comes on after the route's checks
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        sim.server.mark();
        JsonObject r = call("POST", "/api/stop/planned", null);
        MachineEndpoints.plannedStopHook = null;
        assertEquals(409, lastStatus, lastBody);
        assertTrue(r.get("reason").getAsString().contains("the pressure hold"), lastBody);
        assertTrue(api.getContext().pressure.isActive());
        sim.machine.submit(() -> null).get(30, TimeUnit.SECONDS); // nothing of the planned stop is left to run
        assertFalse(sim.server.sinceMark().contains("M410"), "the planned stop sent nothing: " + sim.server.sinceMark());
        assertTrue(sim.machine.isEnabled());
        ok("POST", "/api/pressure/stop", null);
    }

    /**
     * A deploy: a planned stop before the switch, Start, Home, the health check's planned stop, OpenPnP quits and starts
     * again, Start: a confirm keeps the angle every time, and a half-turn record made before it all is still evidence.
     * A real STOP after it switches the motor off: the record is stale and the angle must be measured.
     */
    @Test
    public void aDeploysStopsRestartAndHomeKeepTheAngleAndItsRecords() throws Exception {
        long setAt = ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}").get("startAngleSetAtMs")
                .getAsLong();
        Thread.sleep(5);
        long rec = halfTurnRecord();
        assertNull(whyStale(rec));
        Thread.sleep(5);
        ok("POST", "/api/stop/planned", null);
        ok("POST", "/api/machine/enable", null);
        assertConfirmAsked();
        assertTrue(ok("POST", "/api/machine/home", "{\"wait\":true}").get("homed").getAsBoolean());
        assertConfirmAsked();
        sim.server.mark();
        ok("POST", "/api/stop/planned", null);
        assertPlannedStopSent();
        quitAndRestartOpenPnp();
        ok("POST", "/api/machine/enable", null);
        assertConfirmAsked();
        ok("POST", "/api/screw/start-angle", "{}");
        assertEquals("ok", i11().get("state").getAsString());
        JsonObject p = profile();
        assertEquals(118, p.get("startAngleDeg").getAsDouble(), 0);
        assertEquals(setAt, p.get("startAngleChangedAtMs").getAsLong(), "a confirm keeps when the angle was set");
        assertTrue(p.get("startAngleSetAtMs").getAsLong() > setAt, "the confirm is stamped (I11)");
        assertEquals(0, motorOffs().size(), String.valueOf(svc.getDb().recentEvents(20)));
        assertNull(whyStale(rec), "a half-turn record made at the same angle is still evidence");

        // the real STOP still sends M18 B: the screw may turn, so the record is stale and I11 asks a measure
        ok("POST", "/api/stop", null);
        ok("POST", "/api/machine/enable", null); // waits for the switch-off to end
        assertEquals(1, motorOffs().size(), String.valueOf(svc.getDb().recentEvents(20)));
        String why = whyStale(rec);
        assertTrue(why != null && why.startsWith("made before the screw motor was switched off at "), why);
        assertTrue(i11().get("text").getAsString().contains("measure the start angle again"), i11().toString());
    }

    /**
     * Settings from before BR-75 have no change time: the first confirm after the deploy dates the angle from its last
     * set or confirm, so it keeps the records made since, as every later confirm does.
     */
    @Test
    public void theFirstConfirmOnOlderSettingsKeepsTheRecordsToo() throws Exception {
        long setAt = ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}").get("startAngleSetAtMs")
                .getAsLong();
        svc.updateConfigAtomically(live -> {
            live.profile.startAngleChangedAtMs = null; // a settings file from before it
            return live;
        });
        Thread.sleep(5);
        long rec = halfTurnRecord();
        Thread.sleep(5);
        ok("POST", "/api/stop/planned", null);
        ok("POST", "/api/machine/enable", null);
        ok("POST", "/api/screw/start-angle", "{}");
        assertEquals(setAt, profile().get("startAngleChangedAtMs").getAsLong());
        assertNull(whyStale(rec));
    }

    /**
     * A set of the angle, even to the value it had, is a measure, and so is a settings change of it: the partial-turn
     * records made before it are stale. The change time is the engine's alone: a forged one in PUT /api/config (0) is
     * refused, and cannot make them evidence again (review MINOR 6).
     */
    @Test
    public void aSetOrASettingsChangeOfTheAngleMakesThePartialTurnRecordsStale() throws Exception {
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        Thread.sleep(5);
        long rec = halfTurnRecord();
        Thread.sleep(5);
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}");
        assertEquals("made before the screw start angle was set again", whyStale(rec));
        Thread.sleep(5);
        long later = halfTurnRecord();
        Thread.sleep(5);
        svc.updateConfigAtomically(live -> {
            live.profile.startAngleDeg = 298;
            return live;
        });
        assertEquals("made before the screw start angle was set again", whyStale(later));
        call("PUT", "/api/config", "{\"forTip\":\"" + svc.getConfig().tip.key + "\",\"profile\":"
                + "{\"startAngleChangedAtMs\":0}}");
        assertEquals(400, lastStatus, lastBody);
        assertTrue(lastBody.contains("profile.startAngleChangedAtMs"), lastBody);
        assertEquals("made before the screw start angle was set again", whyStale(later));
    }

    /**
     * Process (11-04): the controller switches the screw's motor off after its idle timeout (no move of any axis for
     * 3600 s, Marlin's DISABLE_IDLE_J), connected or not: a planned stop that lasts longer loses the angle all the same
     * (BR-02's idle clock, journaled across the restart): a measure, and a bare confirm is refused.
     */
    @Test
    public void aPlannedStopLongerThanTheIdleTimeoutStillAsksForAMeasure() throws Exception {
        long idleMs = Math.round(svc.getConfig().screwIdleOffS * 1000);
        long turned = turnScrewAndSetTheAngle();
        ok("POST", "/api/stop/planned", null);
        engineClock(() -> turned + idleMs + 60_000);
        quitAndRestartOpenPnp();
        ok("POST", "/api/machine/enable", null);
        String text = i11().get("text").getAsString();
        assertTrue(text.contains("(the controller's idle timeout: no move for 60 min)")
                && text.contains("measure the start angle again"), text);
        call("POST", "/api/screw/start-angle", "{}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("the controller's idle timeout") && lastBody.contains("measure it again"), lastBody);
        assertEquals(1, motorOffs().size(), String.valueOf(svc.getDb().recentEvents(20)));
    }

    /** The contrast: a planned stop and a restart within the idle timeout keep the angle: a confirm suffices. */
    @Test
    public void aPlannedStopWithinTheIdleTimeoutNeedsOnlyAConfirm() throws Exception {
        long idleMs = Math.round(svc.getConfig().screwIdleOffS * 1000);
        long turned = turnScrewAndSetTheAngle();
        ok("POST", "/api/stop/planned", null);
        engineClock(() -> turned + idleMs / 2);
        quitAndRestartOpenPnp();
        ok("POST", "/api/machine/enable", null);
        assertConfirmAsked();
        ok("POST", "/api/screw/start-angle", "{}");
        assertEquals("ok", i11().get("state").getAsString());
        assertEquals(0, motorOffs().size(), String.valueOf(svc.getDb().recentEvents(20)));
    }

    /** A planned stop now: 409 naming {@code what} and the real STOP, nothing sent, the machine still started. */
    void refusedWith(String what) throws Exception {
        sim.server.mark();
        JsonObject r = call("POST", "/api/stop/planned", null);
        assertEquals(409, lastStatus, lastBody);
        assertEquals("refused", r.get("error").getAsString(), lastBody);
        String reason = r.get("reason").getAsString();
        assertTrue(reason.contains(what) && reason.contains("POST /api/stop"), reason);
        assertEquals(Collections.emptyList(), sim.server.sinceMark(), "nothing sent");
        assertTrue(sim.machine.isEnabled());
        assertTrue(sim.driver.isConnected());
    }

    @Test
    public void refusedWhileAnythingWorksWithNothingSent() throws Exception {
        // the pressure hold (stock firmware: no host watchdog pings while it is on)
        sim.server.hostWatchdogCapability = false;
        sim.towelUnderNeedle(svc);
        assertEquals("on", ok("POST", "/api/pressure/start", null).get("state").getAsString());
        refusedWith("the pressure hold");
        ok("POST", "/api/pressure/stop", null);

        // OpenPnP busy: a task of its own holds the machine thread
        CountDownLatch in = new CountDownLatch(1);
        CountDownLatch out = new CountDownLatch(1);
        Future<Object> held = sim.machine.submit(() -> {
            in.countDown();
            out.await(30, TimeUnit.SECONDS);
            return null;
        });
        assertTrue(in.await(30, TimeUnit.SECONDS));
        refusedWith("OpenPnP is busy");
        out.countDown();
        held.get(30, TimeUnit.SECONDS);

        // review MINOR 4: OpenPnP's last move was not waited for (its window's jog): the controller may still turn
        sim.machine.execute(() -> {
            sim.n1.moveTo(sim.n1.getLocation().add(new Location(LengthUnit.Millimeters, 1, 0, 0, 0)),
                    Motion.MotionOption.JogMotion);
            return null;
        });
        refusedWith("OpenPnP's last move");
        sim.machine.execute(() -> {
            sim.machine.getMotionPlanner().waitForCompletion(null, MotionPlanner.CompletionType.WaitForStillstand);
            return null;
        });

        // an engine task, held on the machine thread at the start of a dose-blocks step
        CountDownLatch stepIn = new CountDownLatch(1);
        CountDownLatch stepOut = new CountDownLatch(1);
        TipSim.hook(point -> {
            if ("dose-blocks".equals(point)) {
                stepIn.countDown();
                try {
                    stepOut.await(30, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        DoseBlocksRequest req = new DoseBlocksRequest();
        req.kind = DoseBlocksRequest.LADDER;
        req.stopEpoch = svc.stopEpoch();
        DoseBlocksRequest.Block b = new DoseBlocksRequest.Block(800, "half", "blank");
        b.xy.add(new double[] {100, 40});
        req.blocks.add(b);
        Future<DoseBlocksResult> task = svc.runDoseBlocks(req);
        assertTrue(stepIn.await(30, TimeUnit.SECONDS));
        refusedWith("a dispense task");
        svc.abort("test: the step refuses at its start, nothing sent");
        stepOut.countDown();
        assertFalse(task.get(60, TimeUnit.SECONDS).ok);
        TipSim.hook(null);

        // a G-code batch of the lock holder, its line waiting for the controller's ok; the planned stop is the holder's
        who = ok("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"bench dots\"}").get("token")
                .getAsString();
        CountDownLatch okLater = new CountDownLatch(1);
        sim.server.holdOkUntil.put("G4", okLater);
        CompletableFuture<HttpResponse<String>> batch = http.sendAsync(request("POST", "/api/gcode",
                "{\"lines\":[\"G4 P10\"]}"), HttpResponse.BodyHandlers.ofString());
        long until = System.currentTimeMillis() + 10_000;
        while (!sim.server.all().contains("G4 P10") && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        assertTrue(sim.server.all().contains("G4 P10"), "the batch's line is in flight");
        refusedWith("a G-code batch");
        okLater.countDown();
        assertEquals(200, batch.get(30, TimeUnit.SECONDS).statusCode());

        // a home under way (its G28 waits for the controller's ok)
        CountDownLatch g28 = new CountDownLatch(1);
        sim.server.holdOkUntil.put("G28", g28);
        sim.server.mark();
        CompletableFuture<HttpResponse<String>> home = http.sendAsync(request("POST", "/api/machine/home",
                "{\"wait\":true}"), HttpResponse.BodyHandlers.ofString());
        until = System.currentTimeMillis() + 10_000;
        while (sim.server.sinceMark().stream().noneMatch(l -> l.startsWith("G28")) && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        refusedWith("OpenPnP is busy");
        g28.countDown();
        assertEquals(200, home.get(60, TimeUnit.SECONDS).statusCode());

        // a STOP still switching off (its M410 waits for the controller's ok): only the STOP's own lines go out
        sim.server.holdOkUntil.put("M410", new CountDownLatch(1));
        sim.server.mark();
        ok("POST", "/api/stop", null);
        JsonObject r = call("POST", "/api/stop/planned", null);
        assertEquals(409, lastStatus, lastBody);
        assertTrue(r.get("reason").getAsString().contains("switching the machine off"), lastBody);
        assertEquals(1, Collections.frequency(sim.server.sinceMark(), "M410"), sim.server.sinceMark().toString());
        sim.server.holdOkUntil.get("M410").countDown();
        until = System.currentTimeMillis() + 20_000;
        while (sim.machine.isEnabled() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
    }

    @Test
    public void guardedByTheLockWhileTheStopStaysOpen() throws Exception {
        assertEquals("guarded", LockEndpoints.kindOf("POST", "/api/stop/planned"));
        assertEquals("open", LockEndpoints.kindOf("POST", "/api/stop"));
        who = UI;
        ok("POST", "/api/screw/start-angle", "{\"startAngleDeg\":118}"); // the user holds the machine now
        who = null; // the deployer
        sim.server.mark();
        JsonObject r = call("POST", "/api/stop/planned", null);
        assertEquals(409, lastStatus, lastBody);
        assertEquals("locked", r.get("error").getAsString(), lastBody);
        assertEquals(Collections.emptyList(), sim.server.sinceMark());
        assertTrue(sim.machine.isEnabled());
        // STOP is open to anyone, and its path is unchanged: its DISABLE_COMMAND still ends with M18 B
        ok("POST", "/api/stop", null);
        long until = System.currentTimeMillis() + 10_000;
        while (sim.machine.isEnabled() && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        assertFalse(sim.machine.isEnabled());
        assertTrue(sim.server.sinceMark().contains("M18 B"), sim.server.sinceMark().toString());
    }

    /**
     * The PM (11-04): never the web UI's, refused before the lock is looked at (the UI's request would make the user its
     * holder); the lock holder's token, or the deployer with no lock, passes.
     */
    @Test
    public void neverTheWebUisButTheLockHoldersToken() throws Exception {
        who = UI;
        sim.server.mark();
        JsonObject r = call("POST", "/api/stop/planned", null);
        assertEquals(409, lastStatus, lastBody);
        assertEquals("refused", r.get("error").getAsString(), lastBody);
        assertEquals("the planned stop is for deploys; the UI's STOP is POST /api/stop", r.get("reason").getAsString());
        assertEquals(Collections.emptyList(), sim.server.sinceMark());
        assertTrue(sim.machine.isEnabled());
        who = null;
        assertTrue(ok("GET", "/api/lock", null).get("holder").isJsonNull(), "the lock unchanged: nobody holds it");
        who = ok("POST", "/api/lock/acquire", "{\"who\":\"infra\",\"what\":\"deploy\"}").get("token").getAsString();
        sim.server.mark();
        assertEquals(PLANNED, strings(ok("POST", "/api/stop/planned", null).getAsJsonArray("linesSent")));
        assertPlannedStopSent();
    }

    @Test
    public void withTheMachineOffItSendsNothingAndClosesTheLinkKeptOpen() throws Exception {
        // re-review NIT 3: a jog's move not waited for no longer refuses it once the machine is off (only a Start clears
        // that flag). Left by a disable that waits for nothing (outputs left as they are; the live ActuateOff waits)
        ReferenceActuator vac1 = (ReferenceActuator) sim.machine.getDefaultHead().getActuatorByName("VAC1");
        ReferenceActuator vac2 = (ReferenceActuator) sim.machine.getDefaultHead().getActuatorByName("VAC2");
        vac1.setDisabledActuation(ReferenceActuator.MachineStateActuation.LeaveAsIs);
        vac2.setDisabledActuation(ReferenceActuator.MachineStateActuation.LeaveAsIs);
        sim.machine.execute(() -> {
            sim.n1.moveTo(sim.n1.getLocation().add(new Location(LengthUnit.Millimeters, 1, 0, 0, 0)),
                    Motion.MotionOption.JogMotion);
            return null;
        });
        sim.machine.setEnabled(false);
        assertTrue(sim.driver.isMotionPending(), "nothing waited for the jog");
        assertTrue(sim.driver.isConnected(), "connection-keep-alive: the link stays open while the machine is off");
        sim.server.mark();
        JsonObject r = ok("POST", "/api/stop/planned", null);
        assertTrue(r.get("planned").getAsBoolean(), r.toString());
        assertFalse(r.get("machineEnabled").getAsBoolean(), r.toString());
        assertTrue(strings(r.getAsJsonArray("actions")).contains("machine was already stopped"), r.toString());
        assertEquals(Collections.emptyList(), strings(r.getAsJsonArray("linesSent")));
        assertFalse(sim.driver.isConnected(), "closed");
        sim.machine.setEnabled(false); // OpenPnP's quit: no DISABLE_COMMAND on a closed link
        assertEquals(Collections.emptyList(), sim.server.sinceMark());
    }

    @Test
    public void thePlannedLinesAreTheDisableCommandWithoutTheScrewOff() {
        assertEquals(PLANNED, DispenseService.plannedStopLines(String.join("\n", ControllerCommands.STOP_SEQUENCE),
                "B"));
        // M84 and a bare M18 switch every motor off, the screw's too; M18 X Y leaves it on; comments and blank lines
        assertEquals(Arrays.asList("M410", "M18 X Y", "M107 P3 ; valve off"), DispenseService.plannedStopLines(
                "M410\r\n\nM84\nM18\n  m18 b ; the screw\nM18 X Y\n; a comment\nM107 P3 ; valve off\n  \n", "B"));
        assertEquals(Collections.singletonList("M18 B"), DispenseService.plannedStopLines("M18 B\nM18 C", "C"),
                "the screw's own letter");
        assertEquals(Collections.emptyList(), DispenseService.plannedStopLines(null, "B"));
    }
}
