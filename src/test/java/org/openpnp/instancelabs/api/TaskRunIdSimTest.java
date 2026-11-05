package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.ControllerCommands;
import org.openpnp.instancelabs.dispense.LandingResult;
import org.openpnp.instancelabs.dispense.RunResult;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * BR-101, mostly on the SIM machine: a task's 202 names the run it started ({@code runId}, as status.run shows it),
 * and a task refused before its run started leaves its label to no other run.
 */
public class TaskRunIdSimTest {
    TipSim t;

    @AfterEach
    public void after() throws Exception {
        if (t != null) {
            t.sim.server.holdOkUntil.values().forEach(CountDownLatch::countDown);
            t.sim.server.holdOkUntil.clear();
            t.close();
        }
    }

    /** status.run, or null before the first run. */
    JsonObject run() throws Exception {
        JsonElement r = TipSim.ok(t.get("/api/status")).get("run");
        return r == null || r.isJsonNull() ? null : r.getAsJsonObject();
    }

    /** Polls status.run until {@code done} holds (at most 30 s); the last one read. */
    JsonObject runUntil(Predicate<JsonObject> done) throws Exception {
        long until = System.currentTimeMillis() + 30_000;
        JsonObject r = run();
        while (!done.test(r) && System.currentTimeMillis() < until) {
            Thread.sleep(50);
            r = run();
        }
        return r;
    }

    /** The bench, ready to lay a touch-off as TipSetupValuesSimTest lays one: the 25G's frame and landing, a camera. */
    void readyToLay() throws Exception {
        t = new TipSim();
        t.bench();
        t.add25();
        t.mount(TipSim.K25);
        t.frame();
        LandingResult l = new LandingResult();
        l.setupKey = TipSim.K25;
        l.needleSinceMs = t.token();
        l.n = 8;
        t.svc.setLanding(l, true, t.token());
        t.svc.setDotImager(new ApiServerSimTest.FakeImager());
    }

    /** The UI binds its live line to its own run: a touch-off's 202 names it, and status.run shows it while it runs. */
    @Test
    public void aTouchOffsAnswerNamesTheRunStatusShows() throws Exception {
        readyToLay();
        CountDownLatch pump = new CountDownLatch(1);
        t.sim.server.holdOkUntil.put(ControllerCommands.PUMP_ON, pump); // the run waits at its pump while we look

        TipSim.Res r = t.post("/api/calibrations/touch-off", "{}");
        assertEquals(202, r.status, r.toString());
        String runId = TipSim.str(r.json(), "runId");
        assertNotNull(runId, r.toString());
        JsonObject run = runUntil(x -> x != null);
        assertTrue(run.get("running").getAsBoolean(), run.toString());
        assertEquals(runId, TipSim.str(run, "runId"), run.toString());
        assertEquals("needle touch-off", TipSim.str(run, "label"), run.toString());

        pump.countDown();
        run = runUntil(x -> !x.get("running").getAsBoolean());
        assertEquals(runId, TipSim.str(run, "runId"), run.toString());
        assertEquals("OK", TipSim.str(run, "status"), run.toString());
    }

    /**
     * A touch-off refused after the route named its task (by the engine: a blank whose surface is probed but whose
     * place is not set passes the route's checks) leaves no label behind: the next run, one the engine starts for a
     * client that names none, shows its own kind, not "needle touch-off".
     */
    @Test
    public void aRefusedTouchOffLeavesNoLabelForTheNextRun() throws Exception {
        readyToLay();
        TipSim.ok(t.call("PUT", "/api/surfaces/spot",
                "{\"points\":[[60,30,5.5],[200,30,5.5],[60,50,5.5],[200,50,5.5]]}"));
        TipSim.Res r = t.post("/api/calibrations/touch-off", "{\"locationName\":\"spot\"}");
        assertEquals("location 'spot' is not set", TipSim.str(TipSim.refused(r, 409, "refused"), "reason"));

        t.svc.checkZ().get(60, TimeUnit.SECONDS);
        JsonObject run = run();
        assertEquals("z-check", TipSim.str(run, "kind"), run.toString());
        assertEquals("z-check", TipSim.str(run, "label"), run.toString());
    }

    /**
     * The tracker alone: a request refused while another's run is queued (the engine runs one task at a time) neither
     * takes nor clears that run's label.
     */
    @Test
    public void aRequestRefusedMeanwhileNeitherTakesNorClearsAQueuedRunsLabel() throws Exception {
        RunTracker tracker = new RunTracker();
        RunTracker.Started<Object> queued = tracker.start("dot ladder", () -> new CompletableFuture<>());
        assertThrows(IllegalStateException.class, () -> tracker.start("needle touch-off", () -> {
            throw new IllegalStateException("a dispense task is already running");
        }));
        assertEquals(1, tracker.labelsHeld(), "the refused start took its label with it");
        RunResult run = new RunResult(); // the queued run, under the id its task was started with
        run.runId = queued.runId;
        run.kind = "dot-ladder";
        tracker.onPhase(run, "dispensing calibration dots");
        assertEquals("dot ladder", tracker.snapshot().get("label"));
        assertEquals(0, tracker.labelsHeld());
    }
}
