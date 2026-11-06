package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.ControllerCommands;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DriverGcodeChannel;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.openpnp.spi.base.AbstractMachine;

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
        sim.towelUnderNeedle(svc); // BR-03: the hold opens the valve only over a place
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

    /**
     * BR-106: a task of OpenPnP's own that fails, queued while OpenPnP reads idle: its machine thread is held outside
     * any task (as before a queued task begins) until {@code behind} more tasks are queued behind it. It then fails
     * and OpenPnP drops them.
     */
    Future<Object> aFailingTaskAhead(int behind) throws Exception {
        CountDownLatch queued = new CountDownLatch(1 + behind);
        LinkedBlockingQueue<Runnable> counted = new LinkedBlockingQueue<Runnable>() {
            @Override
            public boolean offer(Runnable task) {
                boolean in = super.offer(task);
                queued.countDown();
                return in;
            }
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, counted);
        pool.execute(() -> {
            try {
                queued.await(30, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        machineExecutor(pool);
        return sim.machine.submit(() -> {
            throw new Exception("test: a task of OpenPnP's own fails (its Home meets a limit)");
        });
    }

    /** OpenPnP's machine thread replaced by {@code pool} (null: a new one at the next task); the old one ends. */
    void machineExecutor(ThreadPoolExecutor pool) throws Exception {
        Field executor = AbstractMachine.class.getDeclaredField("executor");
        executor.setAccessible(true);
        ExecutorService was = (ExecutorService) executor.get(sim.machine);
        if (was != null) {
            was.shutdown();
        }
        executor.set(sim.machine, pool);
    }

    /**
     * BR-106: the web STOP with OpenPnP idle queues its switch-off on the machine thread. A task of OpenPnP's own
     * queued just ahead of it (not begun, so OpenPnP read idle) failed and dropped it: the machine stayed started.
     * The STOP sees its task dropped and switches off on the emergency path.
     */
    @Test
    public void aWebStopDroppedBehindAFailingTaskStillSwitchesOff() throws Exception {
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND, "M410");
        long disables = sim.driver.getDisableCommandsSent();
        Future<Object> failing = aFailingTaskAhead(1);
        JsonObject r = ok("POST", "/api/stop", null);
        assertFalse(r.get("machineEnabled").getAsBoolean(), r.toString());
        assertTrue(r.get("actions").toString().contains("the Stop's task never ran"), r.toString());
        assertFalse(sim.machine.isEnabled());
        assertEquals(disables + 1, sim.driver.getDisableCommandsSent(), "the Stop's lines went out");
        assertThrows(ExecutionException.class, () -> failing.get(10, TimeUnit.SECONDS));
    }

    /**
     * BR-106 review: the web STOP's queued task failed before its body when a machineBusy(true) listener threw an
     * Error (fire* lets Errors through): nothing switched off, the machine stayed started. A task that never began
     * takes the emergency path as a dropped one does.
     */
    @Test
    public void aWebStopWhoseTaskFailedBeforeItBeganStillSwitchesOff() throws Exception {
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND, "M410");
        long disables = sim.driver.getDisableCommandsSent();
        AtomicBoolean armed = new AtomicBoolean(true);
        MachineListener error = new MachineListener.Adapter() {
            @Override
            public void machineBusy(Machine machine, boolean busy) {
                if (busy && armed.compareAndSet(true, false)) {
                    throw new StackOverflowError("test: a busy listener's Error, on the Stop's task");
                }
            }
        };
        sim.machine.addListener(error);
        try {
            JsonObject r = ok("POST", "/api/stop", null);
            assertFalse(armed.get(), "the Stop's task met the Error");
            assertFalse(r.get("machineEnabled").getAsBoolean(), r.toString());
            assertTrue(r.get("actions").toString().contains("the Stop's task never ran"), r.toString());
            assertEquals(disables + 1, sim.driver.getDisableCommandsSent(), "the Stop's lines went out");
        }
        finally {
            sim.machine.removeListener(error);
        }
    }

    /**
     * BR-106 review: OpenPnP refused to queue the web STOP's task (RejectedExecutionException: its executor shut down
     * by a failing task between its check and the submit). The route answered 500 and nothing switched off. It takes
     * the emergency path now.
     */
    @Test
    public void aWebStopOpenPnpRefusedToQueueStillSwitchesOff() throws Exception {
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND, "M410");
        long disables = sim.driver.getDisableCommandsSent();
        machineExecutor(new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable command) {
                throw new RejectedExecutionException("test: shut down between OpenPnP's check and the submit");
            }
        });
        try {
            JsonObject r = ok("POST", "/api/stop", null);
            assertFalse(r.get("machineEnabled").getAsBoolean(), r.toString());
            assertTrue(r.get("actions").toString().contains("OpenPnP refused to queue it"), r.toString());
            assertEquals(disables + 1, sim.driver.getDisableCommandsSent(), "the Stop's lines went out");
        }
        finally {
            machineExecutor(null);
        }
    }

    /**
     * BR-106 review: Pressure on whose machine task OpenPnP dropped answered the dropped-task 409 but stayed
     * STARTING: a retry was refused as already starting, and the engine routes and the planned stop refused until
     * Pressure off or STOP. It ends off, nothing switched on.
     */
    @Test
    public void aPressureStartOpenPnpDroppedEndsOff() throws Exception {
        Future<Object> failing = aFailingTaskAhead(1);
        try {
            JsonObject r = call("POST", "/api/pressure/start", null);
            assertEquals(409, lastStatus, r.toString());
            assertTrue(r.toString().contains("OpenPnP dropped this task"), r.toString());
            assertThrows(ExecutionException.class, () -> failing.get(10, TimeUnit.SECONDS));
            assertEquals("off", pressure().get("state").getAsString());
        }
        finally {
            call("POST", "/api/pressure/stop", null);
        }
    }

    /**
     * BR-106: a web home queued behind a task of OpenPnP's own that fails is dropped: OpenPnP cancels it, which
     * answered 409 with no reason. The answer says what happened.
     */
    @Test
    public void aWebHomeOpenPnpDroppedSaysSo() throws Exception {
        Future<Object> failing = aFailingTaskAhead(1);
        JsonObject r = call("POST", "/api/machine/home", "{\"wait\":true}");
        assertEquals(409, lastStatus, r.toString());
        assertTrue(r.toString().contains("OpenPnP dropped this task because a task queued ahead of it failed: nothing "
                + "was done; try again"), r.toString());
        assertThrows(ExecutionException.class, () -> failing.get(10, TimeUnit.SECONDS));
        assertTrue(sim.machine.isEnabled(), "nothing was done");
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
     * BR-66: OpenPnP's Stop sends its whole DISABLE_COMMAND even with a controller error pending. Every line called
     * bailOnError first, so the pending error threw at M410 and nothing of the Stop went out.
     */
    @Test
    public void openPnpStopSendsItsWholeSequenceDespiteAPendingControllerError() throws Exception {
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND,
                String.join("\n", ControllerCommands.STOP_SEQUENCE));
        leaveControllerErrorPending();
        sim.server.mark();
        sim.machine.setEnabled(false);
        List<String> l = sim.server.sinceMark();
        assertTrue(java.util.Collections.indexOfSubList(l, ControllerCommands.STOP_SEQUENCE) >= 0,
                "M410, both valves and pumps off, M18 B: " + l);
    }

    /** BR-66: a Stop line whose ok never comes (CI: lost to a second sender) no longer keeps the lines after it. */
    @Test
    public void openPnpStopSendsTheLinesAfterOneThatTimesOut() throws Exception {
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND,
                String.join("\n", ControllerCommands.STOP_SEQUENCE));
        sim.driver.setTimeoutMilliseconds(500);
        sim.server.mute.add(ControllerCommands.PUMP_OFF);
        sim.server.mark();
        sim.machine.setEnabled(false);
        List<String> l = sim.server.sinceMark();
        assertTrue(java.util.Collections.indexOfSubList(l, ControllerCommands.STOP_SEQUENCE) >= 0,
                "the pick valve and pump off and M18 B after the pump off that got no answer: " + l);
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

    /**
     * AUTO_REGISTER_DESIGN §2.7: nothing homes or touches with N1 while the barrel is held under
     * pressure (paste would ooze over the board): every task that homes or probes is refused before
     * any G28 or N1 line.
     */
    @Test
    public void tasksThatHomeOrProbeAreRefusedWhileThePressureIsHeld() throws Exception {
        assertEquals("on", ok("POST", "/api/pressure/start", null).get("state").getAsString());
        sim.server.mark();
        String[][] tasks = {{"/api/run", "{\"planId\":\"x\"}"}, {"/api/plan/x/register", "{}"},
                {"/api/surfaces/blank/probe", "{}"}, {"/api/machine/z-check", "{}"}, {"/api/machine/home", "{}"}};
        for (String[] t : tasks) {
            JsonObject r = call("POST", t[0], t[1]);
            assertEquals(409, lastStatus, t[0] + " " + r);
            assertTrue(r.toString().contains("pressure is being held on"), t[0] + " " + r);
        }
        for (String l : sim.server.sinceMark()) {
            assertFalse(l.startsWith("G28") || l.startsWith("M106 P1") || l.startsWith("M260"), l);
        }
        ok("POST", "/api/pressure/stop", null);
    }

    /**
     * AUTO_REGISTER_DESIGN §2.7 / §10.2 case 24: the pressure hold and an engine task that both passed
     * their API checks are serialised on the machine thread, where each re-checks the other: exactly
     * one of them runs. The engine task is queued while the hold's start waits behind other machine
     * work; the engine task queued while the hold is on is refused there and does not stay busy.
     */
    @Test
    public void thePressureHoldAndAStartingTaskNeverBothRun() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        sim.machine.submit(() -> {
            release.await();
            return null;
        });
        // OpenPnP is busy: the hold's start is refused at once, never queued behind the machine thread's work (#12
        // final review m2: a start queued behind motion trusted OpenPnP's planned position)
        sim.server.mark();
        JsonObject held = call("POST", "/api/pressure/start", null);
        assertEquals(409, lastStatus, held.toString());
        assertTrue(held.toString().contains("busy"), held.toString());
        java.util.concurrent.Future<org.openpnp.instancelabs.dispense.RunResult> task = svc.checkZ();
        release.countDown();
        org.openpnp.instancelabs.dispense.RunResult r = task.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("off", pressure().get("state").getAsString());
        assertEquals(org.openpnp.instancelabs.dispense.RunResult.Status.OK, r.status, r.toString());
        assertFalse(sim.server.sinceMark().contains(ControllerCommands.PUMP_ON), "the pump never went on");

        // the other order: the hold is on when the engine task reaches the machine thread
        assertEquals("on", ok("POST", "/api/pressure/start", null).get("state").getAsString());
        r = svc.checkZ().get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(org.openpnp.instancelabs.dispense.RunResult.Status.REFUSED, r.status, r.toString());
        assertEquals("the pressure hold is on: Pressure off first", r.message);
        assertFalse(svc.isBusy());
        assertEquals("on", pressure().get("state").getAsString(), "the hold is untouched");
        ok("POST", "/api/pressure/stop", null);
    }

    /**
     * Review 1 (machine safety): OpenPnP drops every task queued behind one that throws, and an engine task
     * dropped so never ends (its busy flag stayed set until OpenPnP restarted). While the web home runs,
     * every engine endpoint refuses (OpenPnP is busy); a task queued behind the home all the same (it passed
     * its checks just before the home started) still runs after a STOP made the home fail, refuses, and
     * leaves the engine free.
     */
    @Test
    public void aStoppedWebHomeCannotLeaveTheEngineBusy() throws Exception {
        svc.setLocation("waste", 80, 40, 5.5);
        sim.server.delayBeforeOkMs.put("G28", 2500L);
        java.lang.reflect.Field infinity = GcodeDriver.class.getDeclaredField("infinityTimeoutMilliseconds");
        infinity.setAccessible(true);
        infinity.setInt(sim.driver, 4000); // a G28 whose answer was cut off ends here (60 s otherwise)
        sim.server.mark();
        String[] homed = {""};
        int[] status = new int[1];
        Thread home = new Thread(() -> {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + api.getPort() + "/api/machine/home")).timeout(Duration.ofSeconds(120))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"wait\":true}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                status[0] = r.statusCode();
                homed[0] = r.body();
            }
            catch (Exception e) {
                homed[0] = String.valueOf(e);
            }
        });
        home.start();
        long t0 = System.currentTimeMillis();
        while (sim.server.sinceMark().stream().noneMatch(l -> l.startsWith("G28"))) {
            assertTrue(System.currentTimeMillis() - t0 < 10000, "the home sends G28");
            Thread.sleep(10);
        }
        String[][] engine = {{"/api/prime", "{}"}, {"/api/test-dot", "{}"}, {"/api/calibrations/shots-run",
            "{\"locationName\":\"waste\",\"rotationDeg\":90,\"count\":1}"}, {"/api/calibrations/dot-ladder", "{}"},
            {"/api/calibrations/touch-off", "{}"}, {"/api/plan/p1/clean-check", "{}"}, {"/api/run",
                "{\"planId\":\"p1\"}"}, {"/api/machine/z-check", "{}"}};
        for (String[] e : engine) {
            JsonObject o = call("POST", e[0], e[1]);
            assertEquals(409, lastStatus, e[0] + " " + o);
            assertEquals("OpenPnP is busy (homing or moving): wait for it to finish", o.get("reason").getAsString(),
                    e[0]);
        }
        assertFalse(svc.isBusy());
        java.util.concurrent.Future<org.openpnp.instancelabs.dispense.RunResult> queued = svc.checkZ();
        ok("POST", "/api/stop", null);
        org.openpnp.instancelabs.dispense.RunResult r = queued.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(org.openpnp.instancelabs.dispense.RunResult.Status.REFUSED, r.status, r.toString());
        assertEquals(DispenseService.STOPPED_SINCE_QUEUED, r.message);
        assertFalse(svc.isBusy(), "the engine is free again");
        home.join(30000);
        assertEquals(409, status[0], homed[0]);
        assertTrue(homed[0].contains("homing failed: "), homed[0]);
        assertFalse(sim.machine.isHomed());
    }

    /**
     * AUTO_REGISTER_DESIGN §2.7 for POST /api/machine/home: a home that passed its API check, and a hold
     * started after it, meet on the machine thread. The home looks at the hold again there and does
     * not start (G28 is never sent) while the hold is on or starting.
     */
    @Test
    public void theWebHomeDoesNotStartWhileTheHoldIsOn() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        sim.machine.submit(() -> {
            release.await();
            return null;
        });
        long t1 = System.currentTimeMillis();
        while (!sim.machine.isBusy()) {
            assertTrue(System.currentTimeMillis() - t1 < 10000, "the blocker runs");
            Thread.sleep(10);
        }
        // rc1 review: OpenPnP busy (here a task of its own): the web home is refused at once, as every engine endpoint
        // (a home queued behind one of OpenPnP's tasks that fails would be dropped, the request hang for 5 min)
        JsonObject busy = call("POST", "/api/machine/home", "{\"wait\":true}");
        assertEquals(409, lastStatus, busy.toString());
        assertEquals("OpenPnP is busy (homing or moving): wait for it to finish", busy.get("reason").getAsString());
        // the race that check leaves: a home that passed it just before the blocker took the machine thread
        java.lang.reflect.Field thread = org.openpnp.spi.base.AbstractMachine.class.getDeclaredField("taskThread");
        thread.setAccessible(true);
        thread.set(sim.machine, null);
        sim.server.mark();
        String[] homed = {""};
        int[] status = new int[1];
        Thread home = new Thread(() -> {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + api.getPort() + "/api/machine/home")).timeout(Duration.ofSeconds(120))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"wait\":true}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                status[0] = r.statusCode();
                homed[0] = r.body();
            }
            catch (Exception e) {
                homed[0] = String.valueOf(e);
            }
        });
        home.start();
        Thread.sleep(500); // its API checks passed: the home task waits behind the blocker
        Thread hold = new Thread(() -> {
            try {
                call("POST", "/api/pressure/start", null);
            }
            catch (Exception e) {
                // the state is checked below
            }
        });
        hold.start();
        long t0 = System.currentTimeMillis();
        while (!"starting".equals(pressure().get("state").getAsString())) {
            assertTrue(System.currentTimeMillis() - t0 < 10000, "the hold's start is queued");
            Thread.sleep(10);
        }
        release.countDown();
        home.join(30000);
        hold.join(30000);
        assertEquals(409, status[0], homed[0]);
        assertTrue(homed[0].contains("the pressure hold came on before the home started"), homed[0]);
        assertFalse(sim.server.sinceMark().stream().anyMatch(l -> l.startsWith("G28")), "no home under pressure");
        assertEquals("on", pressure().get("state").getAsString());
        ok("POST", "/api/pressure/stop", null);
    }
}
