package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Tip;

/**
 * Tip setups (§22.1-22.3, §22.8), races with latches: the switch of a mount pauses at its critical point (holding the
 * service's lock and the busy flag) while another path runs. A task submitted meanwhile is refused, a save for the
 * old setup waits and then gets tip-changed, an edit applied after the mount sees the live configuration (and a
 * profile patch built for the old setup gets tip-changed), a frame taken under the old needle gets needle-changed,
 * and a dose-blocks step of the old setup or needle is refused on the machine thread.
 */
public class MountRaceTest {
    static final String K25 = "25g-straight";

    /** Pauses the next mount's switch: {@code in} counts down when it holds the lock, it waits for {@code go}. */
    static void pauseSwitch(CountDownLatch in, CountDownLatch go) {
        DispenseService.testHook = point -> {
            if ("switch".equals(point)) {
                in.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    @Test
    public void aTaskSubmittedDuringTheSwitchIsRefused() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            CountDownLatch in = new CountDownLatch(1);
            CountDownLatch go = new CountDownLatch(1);
            pauseSwitch(in, go);
            svc.recordNeedleChange(null, null);
            CompletableFuture<MountResult> mount = CompletableFuture.supplyAsync(() -> {
                try {
                    return svc.switchTip(K25, "race", null);
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTrue(in.await(30, TimeUnit.SECONDS));
            IllegalStateException busy = assertThrows(IllegalStateException.class,
                    () -> svc.runShots("waste", 90, 1, true));
            assertEquals("a dispense task is already running", busy.getMessage());
            go.countDown();
            assertEquals(K25, mount.get(30, TimeUnit.SECONDS).mounted);
            assertFalse(svc.isBusy());
        }
    }

    @Test
    public void aSaveForTheOldSetupWaitsForTheSwitchAndIsRefused() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            PadType shown = svc.getDb().currentPadType("chip-0603", Tip.SEED_KEY); // read for the 22G
            PadType edit = shown.copy();
            edit.status = PadTypeStatus.PROVISIONAL;
            edit.dotSteps = 800;
            edit.dotsPerPad = 1;
            CountDownLatch in = new CountDownLatch(1);
            CountDownLatch go = new CountDownLatch(1);
            pauseSwitch(in, go);
            svc.recordNeedleChange(null, null);
            CompletableFuture<MountResult> mount = CompletableFuture.supplyAsync(() -> {
                try {
                    return svc.switchTip(K25, "race", null);
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertTrue(in.await(30, TimeUnit.SECONDS));
            CountDownLatch saving = new CountDownLatch(1);
            AtomicReference<Throwable> saved = new AtomicReference<>();
            Thread save = new Thread(() -> {
                saving.countDown();
                try {
                    svc.savePadType(edit, Tip.SEED_KEY, null);
                }
                catch (Throwable t) {
                    saved.set(t);
                }
            });
            save.start();
            assertTrue(saving.await(30, TimeUnit.SECONDS));
            go.countDown();
            mount.get(30, TimeUnit.SECONDS);
            save.join(30_000);
            assertTrue(saved.get() instanceof EngineRefusal, String.valueOf(saved.get()));
            assertEquals("tip-changed", ((EngineRefusal) saved.get()).code);
            assertTrue(saved.get().getMessage().startsWith("The tip changed while this was open (the 25G straight was "
                    + "mounted at "), saved.get().getMessage());
            assertTrue(saved.get().getMessage().endsWith("): reload."), saved.get().getMessage());
            assertEquals(shown.version, svc.getDb().currentPadType("chip-0603", Tip.SEED_KEY).version,
                    "nothing written for the 22G");
        }
    }

    @Test
    public void anEditAfterAMountSeesTheLiveConfiguration() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            DispenseConfig form = svc.getConfig(); // a settings form opened with the 22G mounted
            String forTip = form.tip.key;
            f.mount(K25);
            // a profile patch built for the 22G: the edit compares its forTip with the live tip
            EngineRefusal e = assertThrows(EngineRefusal.class, () -> svc.updateConfigAtomically(live -> {
                if (!live.tip.key.equals(forTip)) {
                    throw new EngineRefusal(409, "tip-changed", "The tip changed while this was open: reload.");
                }
                live.profile.dwellMs = 300;
                return live;
            }));
            assertEquals("tip-changed", e.code);
            assertEquals(new DispenseProfile().dwellMs, svc.getConfig().profile.dwellMs, "nothing written");
            assertEquals(K25, svc.getConfig().tip.key);
            // an edit of another setting applied after the mount puts back nothing of the old tip
            DispenseConfig now = svc.updateConfigAtomically(live -> {
                live.hopHeightMm = 1.2;
                return live;
            });
            assertEquals(1.2, now.hopHeightMm, 0);
            assertEquals(K25, now.tip.key);
            assertEquals("18 psi half turn · 25G straight", now.profile.name);
            assertEquals(null, now.needleAnchor, "the 25G's empty frame, not the form's 22G frame");
            assertNotNull(now.parkedTips.get(Tip.SEED_KEY), "the 22G stays parked");
            assertNotNull(form.needleAnchor, "the stale form had the 22G's frame");
        }
    }

    @Test
    public void aConfigurationCopiedBeforeAMountOrANeedleChangeIsRefused() throws Exception {
        // a settings form, or a request that copies the configuration outside the lock (PUT /api/config, the paste,
        // the calibration area) and writes it back: it must never put back the tip that came off, nor make the frame
        // of the needle before count again
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            DispenseConfig form = svc.getConfig(); // copied with the 22G mounted
            form.hopHeightMm = 1.2;
            f.mount(K25);
            Long token = svc.getConfig().needleChangedAtMs;
            EngineRefusal e = assertThrows(EngineRefusal.class, () -> svc.updateConfig(form));
            assertEquals("tip-changed", e.code);
            assertTrue(e.getMessage().startsWith("The tip changed while this was open (the 25G straight was mounted at "),
                    e.getMessage());
            DispenseConfig now = svc.getConfig();
            assertEquals(K25, now.tip.key);
            assertEquals(token, now.needleChangedAtMs, "the needle change stays recorded");
            assertFalse(now.isNeedleAnchored(), "the 22G's frame does not come back");
            assertEquals(new DispenseConfig().hopHeightMm, now.hopHeightMm, 0, "nothing written");
            // a copy taken before a needle change of the mounted tip: the frame of the needle before stays stale
            f.frame(-2.59);
            DispenseConfig before = svc.getConfig();
            NeedleChange nc = svc.recordNeedleChange(null, null);
            EngineRefusal n = assertThrows(EngineRefusal.class, () -> svc.updateConfig(before));
            assertEquals("needle-changed", n.code);
            assertEquals("The needle was changed at " + ZCheck.clock(nc.atMs) + " while this was open: reload.",
                    n.getMessage());
            assertEquals(nc.atMs, (long) svc.getConfig().needleChangedAtMs);
            assertFalse(svc.getConfig().isNeedleAnchored());
            // a tip changed by hand in the settings is no mount: 400
            DispenseConfig hand = svc.getConfig();
            hand.tip.key = "x30";
            IllegalArgumentException h = assertThrows(IllegalArgumentException.class, () -> svc.updateConfig(hand));
            assertEquals("The tip is the mounted tip setup: change it in Setup › Dispenser & tip.", h.getMessage());
            // the live configuration edited is written
            assertEquals(1.2, svc.updateConfigAtomically(live -> {
                live.hopHeightMm = 1.2;
                return live;
            }).hopHeightMm, 0);
        }
    }

    @Test
    public void theResultPhotosOfAStepStayItsSetupWhenAnotherIsMountedBeforeTheyAreStored() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            // a step of the 22G ended (the machine thread stamps its setup and needle) ...
            DoseBlocksResult step = new DoseBlocksResult();
            step.setupKey = svc.getConfig().tip.key;
            step.needleSinceMs = svc.getConfig().needleChangedAtMs;
            DoseBlocksResult.BlockPhoto bp = new DoseBlocksResult.BlockPhoto();
            bp.doseSteps = 800;
            bp.jpeg = PhotoStore.jpeg(new java.awt.image.BufferedImage(200, 150,
                    java.awt.image.BufferedImage.TYPE_INT_RGB));
            step.photos.add(bp);
            // ... and the 25G was mounted before the job stored its photos
            f.mount(K25);
            Photo p = svc.storeResultPhotos("job-22g", step).get(0);
            assertEquals(Tip.SEED_KEY, p.setupKey);
            assertEquals(step.needleSinceMs, p.needleSinceMs);
        }
    }

    @Test
    public void aFrameTakenUnderTheOldNeedleIsRefused() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            Long old = svc.getConfig().needleChangedAtMs;
            NeedleChange nc = svc.recordNeedleChange(null, null);
            EngineRefusal e = assertThrows(EngineRefusal.class, () -> svc.setNeedleAnchor(5.5, 2.91, null, null, null,
                    old));
            assertEquals("needle-changed", e.code);
            assertEquals("The needle was changed at " + ZCheck.clock(nc.atMs) + " after these values were taken: take "
                    + "them again.", e.getMessage());
            assertThrows(EngineRefusal.class, () -> svc.setNeedleZOffset(-2.59, "manual", "x", old));
            assertThrows(EngineRefusal.class, () -> svc.setXyTrim(0.1, 0, "x", old));
            assertThrows(EngineRefusal.class, () -> svc.keepLanding(old));
            assertFalse(svc.getConfig().isNeedleAnchored(), "nothing was saved");
            // under the new token it is
            DispenseService.NeedleFrameResult r = svc.setNeedleAnchor(5.5, 2.91, null, null, null, nc.atMs);
            assertTrue(svc.getConfig().isNeedleAnchored());
            assertEquals(-2.59, r.offsetMm, 1e-9);
        }
    }

    static DoseBlocksRequest step(DispenseService svc) {
        DoseBlocksRequest req = new DoseBlocksRequest();
        req.kind = DoseBlocksRequest.LADDER;
        DoseBlocksRequest.Block b = new DoseBlocksRequest.Block(800, "half", "blank");
        b.xy.add(new double[] {100, 40});
        req.blocks.add(b);
        DispenseConfig c = svc.getConfig();
        req.forTip = c.tip.key;
        req.needleSinceMs = c.needleChangedAtMs;
        return req;
    }

    @Test
    public void aDoseBlocksStepOfTheOldSetupOrNeedleIsRefusedOnTheMachineThread() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            f.sim.server.mark();
            // the step was built by a job of the 22G; the 25G was mounted before the machine thread ran it
            DoseBlocksRequest req = step(svc);
            f.mount(K25);
            req.stopEpoch = svc.stopEpoch();
            DoseBlocksResult r = svc.runDoseBlocks(req).get(60, TimeUnit.SECONDS);
            assertFalse(r.ok);
            assertEquals(RunResult.Status.REFUSED, r.run.status);
            assertTrue(r.message.startsWith("The tip changed after this calibration started (the 25G straight was "
                    + "mounted at "), r.message);
            // a step of the needle before a needle change
            DoseBlocksRequest req2 = step(svc);
            svc.recordNeedleChange(null, null);
            req2.stopEpoch = svc.stopEpoch();
            DoseBlocksResult r2 = svc.runDoseBlocks(req2).get(60, TimeUnit.SECONDS);
            assertFalse(r2.ok);
            assertTrue(r2.message.startsWith("The needle was changed at "), r2.message);
            assertTrue(r2.message.endsWith("after this calibration started: nothing was sent."), r2.message);
            DispenseServiceSimTest.assertNothingStreamed(f.sim.server.sinceMark());
            // a step paused on the machine thread while the needle is changed: the change stops it, nothing is sent
            DoseBlocksRequest req3 = step(svc);
            req3.stopEpoch = svc.stopEpoch();
            CountDownLatch in = new CountDownLatch(1);
            CountDownLatch go = new CountDownLatch(1);
            DispenseService.testHook = point -> {
                if ("dose-blocks".equals(point)) {
                    in.countDown();
                    try {
                        go.await(30, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            java.util.concurrent.Future<DoseBlocksResult> held = svc.runDoseBlocks(req3);
            assertTrue(in.await(30, TimeUnit.SECONDS));
            NeedleChange nc = svc.recordNeedleChange(null, null);
            assertNotNull(nc.voided.task);
            go.countDown();
            DoseBlocksResult r3 = held.get(60, TimeUnit.SECONDS);
            assertFalse(r3.ok);
            DispenseServiceSimTest.assertNothingStreamed(f.sim.server.sinceMark());
        }
    }
}
