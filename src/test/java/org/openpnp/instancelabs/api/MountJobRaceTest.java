package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.PadType;

import com.google.gson.JsonObject;

/**
 * Tip setups (PROFILE_CALIBRATION_DESIGN.md §22.3, §22.8), races between a mount and a pad calibration job, with latches
 * and the fake engine steps of {@link PadCalibrationJobTest}: the switch runs on the jobs' monitor (a second caller
 * waits for it, a job cannot start during it) and is refused while a job has not ended, the needle change recorded
 * either way; a job between its steps when the needle changes ends stopped with nothing saved, its coupons void; a
 * profile patch built before a mount and sent after it is refused, the mounted setup kept.
 */
public class MountJobRaceTest {
    static final String K25 = TipSim.K25;
    PadCalibrationJobTest f;

    @BeforeEach
    public void before() throws Exception {
        f = new PadCalibrationJobTest();
        f.before();
        PadCalibrationJobTest.ok(f.call("POST", "/api/tip-setups", TipSim.ADD_25));
    }

    @AfterEach
    public void after() throws Exception {
        TipSim.hook(null);
        f.after();
    }

    PadCalibrationJobTest.Res mount(String key) throws Exception {
        return f.call("POST", "/api/tip-setups/" + key + "/mount", "{\"why\":\"race\"}");
    }

    JsonObject job() throws Exception {
        return PadCalibrationJobTest.ok(f.get("/api/pad-calibrations/current"));
    }

    JsonObject awaitEnd() throws Exception {
        long until = System.currentTimeMillis() + 60_000;
        JsonObject j = job();
        while (System.currentTimeMillis() < until && !"stopped".equals(PadCalibrationJobTest.str(j, "state"))
                && !"done".equals(PadCalibrationJobTest.str(j, "state"))
                && !"failed".equals(PadCalibrationJobTest.str(j, "state"))) {
            Thread.sleep(50);
            j = job();
        }
        return j;
    }

    @Test
    public void theSwitchIsRefusedWhileAJobRunsAndTheNeedleChangeStopsTheJob() throws Exception {
        f.plan();
        f.engine.holdAt = 1; // the first engine step waits: the job runs
        PadCalibrationJobTest.Res started = f.call("POST", "/api/pad-calibrations", f.body(f.group, f.attest()));
        assertEquals(202, started.status, started.toString());
        f.engine.awaitHeld();
        String typeKey = job().getAsJsonArray("typeKeys").get(0).getAsString();
        PadType saved = f.svc.getDb().currentPadType(typeKey, TipSim.SEED);

        // the mount: the needle change is recorded and stops the job; the switch waits until the job has ended
        String refused = PadCalibrationJobTest.refused(mount(K25), "refused");
        assertEquals("A pad calibration is stopping: mount again in a moment. The needle change is recorded: nothing "
                + "dispenses until the 25G straight is mounted and its needle Z frame recorded.", refused);
        assertEquals(TipSim.SEED, f.svc.getConfig().tip.key, "the 22G stays mounted");
        assertFalse(f.svc.getConfig().isNeedleAnchored(), "with a stale frame");
        f.engine.release.countDown();
        JsonObject end = awaitEnd();
        assertEquals("stopped", PadCalibrationJobTest.str(end, "state"), end.toString());
        String sentence = PadCalibrationJobTest.str(end.getAsJsonObject("result"), "sentence");
        assertTrue(sentence.matches("The needle was changed at \\d\\d:\\d\\d during the calibration: nothing was saved\\."),
                sentence);
        assertEquals(saved.version, f.svc.getDb().currentPadType(typeKey, TipSim.SEED).version, "nothing written");
        assertEquals(1, f.engine.calls.size(), "nothing queued after the change: " + f.engine.calls);

        // the job has ended: the mount goes through
        JsonObject m = PadCalibrationJobTest.ok(mount(K25));
        assertEquals(K25, PadCalibrationJobTest.str(m, "mounted"));
    }

    @Test
    public void aJobWaitingForItsReadingsEndsStoppedAndItsCouponsAreVoid() throws Exception {
        f.plan();
        PadCalibrationJobTest.ok(f.call("POST", "/api/pad-calibrations", f.body(f.group, f.attest())));
        long until = System.currentTimeMillis() + 60_000;
        while (!"awaiting-reading".equals(PadCalibrationJobTest.str(job(), "state"))
                && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        JsonObject waiting = job();
        assertEquals("awaiting-reading", PadCalibrationJobTest.str(waiting, "state"), waiting.toString());
        String typeKey = waiting.getAsJsonArray("typeKeys").get(0).getAsString();
        int version = f.svc.getDb().currentPadType(typeKey, TipSim.SEED).version;

        // between its steps: the mount's needle change ends it, the coupons get their skip rows, the switch goes through
        JsonObject m = PadCalibrationJobTest.ok(mount(K25));
        assertEquals(K25, PadCalibrationJobTest.str(m, "mounted"));
        assertTrue(m.getAsJsonObject("needleChange").getAsJsonObject("voided").getAsJsonArray("coupons").size() > 0,
                m.toString());
        JsonObject end = job();
        assertEquals("stopped", PadCalibrationJobTest.str(end, "state"), end.toString());
        String sentence = PadCalibrationJobTest.str(end.getAsJsonObject("result"), "sentence");
        assertTrue(sentence.matches("The needle was changed at \\d\\d:\\d\\d: the dots on coupon1( and coupon2)? no "
                + "longer count\\. Wipe (it|them); the types stay as they were\\."), sentence);
        assertFalse(end.getAsJsonObject("result").get("pendingCoupons").getAsBoolean());
        assertEquals(0, f.get("/api/coupons/pending").body.replace("[]", "").length(), "the coupons are free");
        assertEquals(version, f.svc.getDb().currentPadType(typeKey, TipSim.SEED).version, "nothing written");
    }

    @Test
    public void theSwitchHoldsTheJobsMonitorAndNoJobStartsDuringIt() throws Exception {
        f.plan();
        CountDownLatch in = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        TipSim.hook(point -> {
            if ("switch".equals(point)) {
                in.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        CompletableFuture<PadCalibrationJobTest.Res> mount = CompletableFuture.supplyAsync(() -> {
            try {
                return mount(K25);
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        CompletableFuture<String> second = null;
        try {
            assertTrue(in.await(30, TimeUnit.SECONDS), "the switch holds the jobs' monitor and the engine");
            // another caller of the jobs' monitor waits for the switch
            second = CompletableFuture.supplyAsync(() -> {
                try {
                    return f.ctx.padCalJobs.whileNoJob(() -> f.svc.getConfig().tip.key);
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(300);
            assertFalse(second.isDone(), "it waits for the switch's monitor");
            // a job cannot start between the switch's checks and its write
            PadCalibrationJobTest.Res start = f.call("POST", "/api/pad-calibrations", f.body(f.group, f.attest()));
            assertEquals(409, start.status, start.toString());
        }
        finally {
            go.countDown();
        }
        assertEquals(200, mount.get(30, TimeUnit.SECONDS).status);
        assertEquals(K25, second.get(30, TimeUnit.SECONDS), "it ran after the switch: the 25G was mounted");
    }

    @Test
    public void aProfilePatchBuiltBeforeAMountIsRefusedAfterIt() throws Exception {
        String forTip = f.svc.getConfig().tip.key; // the form was filled under the 22G
        int dwell = f.svc.getConfig().profile.dwellMs;
        PadCalibrationJobTest.ok(mount(K25));
        int dwell25 = f.svc.getConfig().profile.dwellMs;
        PadCalibrationJobTest.Res r = f.call("PUT", "/api/config", "{\"forTip\":\"" + forTip + "\",\"profile\":"
                + "{\"dwellMs\":" + (dwell + 100) + "}}");
        String reason = PadCalibrationJobTest.refused(r, "tip-changed");
        assertTrue(reason.matches("The tip changed while this was open \\(the 25G straight was mounted at \\d\\d:\\d\\d\\): "
                + "reload\\."), reason);
        assertEquals(K25, f.svc.getConfig().tip.key, "the mounted setup stays");
        assertEquals(dwell25, f.svc.getConfig().profile.dwellMs, "its recipe untouched");
        // the same patch for the 25G is the 25G's recipe
        PadCalibrationJobTest.ok(f.call("PUT", "/api/config", "{\"forTip\":\"" + K25 + "\",\"profile\":{\"dwellMs\":"
                + (dwell + 100) + "}}"));
        assertEquals(dwell + 100, f.svc.getConfig().profile.dwellMs);
    }
}
