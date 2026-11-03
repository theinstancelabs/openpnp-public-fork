package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Tip;

import com.google.gson.Gson;

/**
 * Tip setups (§22.3, §22.8): a needle change is a fact the engine records at once and never refuses; mounting another
 * setup is that fact plus a switch that parks the setup on the dispenser and brings in the other one (its profile,
 * frame, offset, trim and landing; the live start angle kept), and that alone may be refused.
 */
public class MountTest {
    static final String K25 = "25g-straight";

    static InterlockReport boardRun(DispenseService svc) throws Exception {
        DispensePlan p = svc.newPlanner().plan("toy", new ArrayList<>(), 100, 50);
        return svc.checkInterlocks(p, 0);
    }

    static boolean i4Refuses(DispenseService svc) throws Exception {
        return boardRun(svc).has("I4");
    }

    @Test
    public void aMountParksTheSetupAndBringsTheOtherBackExactly() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            svc.setXyTrim(0.05, -0.03, "fine", svc.getConfig().needleChangedAtMs);
            DispenseConfig before = svc.getConfig();
            TipSetup s25 = f.add25();
            MountResult r = f.mount(K25);
            DispenseConfig c = svc.getConfig();
            assertEquals(K25, r.mounted);
            assertEquals("25G straight", r.mountedName);
            assertEquals(Tip.SEED_KEY, r.from);
            assertTrue(r.text.startsWith("25G straight mounted at "), r.text);
            assertTrue(r.text.endsWith("Next: record its needle Z frame (Setup › Needle Z frame)."), r.text);
            assertTrue(r.stale.contains("needle Z frame") && r.stale.contains("where it lands"), r.stale.toString());
            assertNotNull(r.undoUntilMs);
            assertEquals(K25, c.tip.key);
            assertEquals("25G straight", c.tip.name);
            assertEquals(0.51, c.tip.odMm, 0);
            // the new setup's empty entry: no frame, the offset a placeholder, a zero trim noted "new tip", no landing
            assertNull(c.needleAnchor);
            assertNull(c.needleZOffsetSetAtMs);
            assertEquals(0, c.needleXyTrim.dxMm, 0);
            assertEquals("new tip", c.needleXyTrim.note);
            assertNull(c.needleLanding);
            assertEquals("18 psi half turn · 25G straight", c.profile.name);
            assertTrue(i4Refuses(svc));
            assertTrue(boardRun(svc).toString().contains("the 25G straight was put on at"), boardRun(svc).toString());
            // the 22G parked with everything it had
            DispenseConfig.ParkedTip p22 = c.parkedTips.get(Tip.SEED_KEY);
            Gson g = new Gson();
            assertEquals(g.toJson(before.needleAnchor), g.toJson(p22.needleAnchor));
            assertEquals(before.needleZOffsetMm, p22.needleZOffsetMm, 0);
            assertEquals(before.needleZOffsetSetAtMs, p22.needleZOffsetSetAtMs);
            assertEquals(g.toJson(before.needleXyTrim), g.toJson(p22.needleXyTrim));
            assertEquals(g.toJson(before.needleLanding), g.toJson(p22.needleLanding));
            assertEquals(before.profile.name, p22.profile.name);
            assertNotNull(p22.parkedAtMs);
            assertFalse(c.parkedTips.containsKey(K25));
            // the 25G gets its own frame and landing, then the 22G comes back exactly as it was
            f.frame(-3.10);
            f.land(2.4, -0.4);
            DispenseConfig with25 = svc.getConfig();
            f.mount(Tip.SEED_KEY);
            DispenseConfig back = svc.getConfig();
            assertEquals(Tip.SEED_KEY, back.tip.key);
            assertEquals(g.toJson(before.needleAnchor), g.toJson(back.needleAnchor));
            assertEquals(before.needleZOffsetMm, back.needleZOffsetMm, 0);
            assertEquals(before.needleZOffsetSetAtMs, back.needleZOffsetSetAtMs, "restored values keep their times");
            assertEquals(g.toJson(before.needleXyTrim), g.toJson(back.needleXyTrim));
            assertEquals(g.toJson(before.needleLanding), g.toJson(back.needleLanding));
            assertEquals(before.profile.fingerprint(), back.profile.fingerprint());
            DispenseConfig.ParkedTip p25 = back.parkedTips.get(K25);
            assertEquals(g.toJson(with25.needleAnchor), g.toJson(p25.needleAnchor));
            assertEquals(2.4, p25.needleLanding.dxMm, 0);
            // the frame is stale (a needle change came with the mount): I4 asks again
            assertFalse(back.isNeedleAnchored());
            assertTrue(i4Refuses(svc));
            // rows and events
            List<TipSetups.Mount> rows = svc.getDb().tipMounts(10);
            assertEquals(TipSetups.Mount.MOUNT, rows.get(0).kind);
            assertEquals(Tip.SEED_KEY, rows.get(0).setupKey);
            assertEquals(K25, rows.get(0).fromSetupKey);
            assertEquals(TipSetups.Mount.NEEDLE, rows.get(1).kind);
            assertNotNull(rows.get(0).profileJson);
            assertFalse(svc.getDb().query("SELECT * FROM events WHERE code = 'TIP' AND message = 'mounted the "
                    + Tip.SEED_NAME + " (was the 25G straight)'").isEmpty());
            assertFalse(svc.getDb().query("SELECT * FROM events WHERE code = 'TIP' AND message LIKE 'needle changed (25G"
                    + " straight): needle Z frame, offset and landing to do%'").isEmpty());
            assertEquals(s25.key, svc.getDb().currentTipSetup(K25).key);
        }
    }

    @Test
    public void theTokenIsStrictlyIncreasingAndOlderRecordsAreStale() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            long a = svc.recordNeedleChange(null, null).atMs;
            long b = svc.recordNeedleChange(null, null).atMs;
            long c = svc.recordNeedleChange(null, null).atMs;
            assertTrue(a < b && b < c, a + " " + b + " " + c);
            assertEquals(c, (long) svc.getConfig().needleChangedAtMs);
            // a record of the 22G made before is no evidence for the 25G (another tip) nor after a change
            DispenseConfig cfg = svc.getConfig();
            DoseRecord r = DoseRecordTest.record(DoseRecord.OPTICAL, 800, cfg, a - 1);
            r.regulatorPsi = cfg.profile.pressurePsi;
            assertEquals("made before the needle was changed", r.whyStale(System.currentTimeMillis(), cfg,
                    new DoseRecord.Epochs(null, svc.isSimulatorConfirmed())));
            f.add25();
            f.mount(K25);
            DoseRecord now22 = DoseRecordTest.record(DoseRecord.OPTICAL, 800, cfg, System.currentTimeMillis());
            assertTrue(now22.whyStale(System.currentTimeMillis(), svc.getConfig(), new DoseRecord.Epochs(null,
                    svc.isSimulatorConfirmed())).startsWith("made with another tip"));
        }
    }

    @Test
    public void theTokenComesAfterEverythingMeasuredForTheNeedleThatCameOff() throws Exception {
        // the frame, the offset and the landing were stamped a minute ahead of the clock (it was set back since): the
        // needle change still makes all three stale, else the frame of the needle that came off would count
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            long ahead = System.currentTimeMillis() + 60_000;
            svc.updateConfigAtomically(live -> {
                live.needleAnchor = new DispenseConfig.NeedleAnchor(5.5, 2.91, null, null, ahead);
                live.needleZOffsetSetAtMs = ahead;
                live.needleLanding.setAtMs = ahead;
                return live;
            });
            assertTrue(svc.getConfig().isNeedleAnchored() && svc.getConfig().isNeedleTouchedOff()
                    && svc.isNeedleXyChecked());
            NeedleChange nc = svc.recordNeedleChange(null, null);
            assertTrue(nc.atMs > ahead, nc.atMs + " " + ahead);
            assertFalse(svc.getConfig().isNeedleAnchored());
            assertFalse(svc.getConfig().isNeedleTouchedOff());
            assertFalse(svc.isNeedleXyChecked());
            assertTrue(i4Refuses(svc));
        }
    }

    @Test
    public void madeWithAnotherTipFlagsTheJobAndRestoresTheVersionItWrote() throws Exception {
        // §22.3 "Made with another tip…": the job's dose records leave the setup's evidence and dot table, its coupons
        // waiting for readings get skip rows, and the version it wrote gives way to the setup's previous own one
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            DispenseConfig c = svc.getConfig();
            long now = System.currentTimeMillis();
            DoseRecord dots = DoseRecordTest.record(DoseRecord.OPTICAL, 800, c, now);
            dots.jobId = "job-w";
            dots.pass = true;
            dots.diameterMeanMm = 1.10;
            dots.diameterP95Mm = 1.20;
            dots.simulated = svc.isSimulatorConfirmed();
            svc.getDb().insertDoseRecord(dots);
            DoseRecord coupon = DoseRecordTest.record(DoseRecord.COUPON, 800, c, now);
            coupon.jobId = "job-w";
            coupon.simulated = svc.isSimulatorConfirmed();
            long couponId = svc.getDb().insertDoseRecord(coupon).id;
            assertTrue(svc.dotTable().doses.containsKey(800), "the job's dots are in the 22G's dot table");
            PadType was = svc.getDb().currentPadType("chip-0603", Tip.SEED_KEY);
            PadType v = was.copy();
            v.status = PadTypeStatus.PROVISIONAL;
            v.dotSteps = 800;
            v.dotsPerPad = 1;
            PadCalibrationRecord rec = new PadCalibrationRecord();
            rec.jobId = "job-w";
            rec.typeKey = "chip-0603";
            rec.outcome = PadCalibrationRecord.PROVISIONAL;
            rec = svc.applyPadCalibration(rec, v);
            assertEquals((int) rec.versionTo, svc.getDb().currentPadType("chip-0603", Tip.SEED_KEY).version);
            List<PadType> restored = svc.markWrongTip("job-w", "tester");
            assertEquals(1, restored.size());
            PadType back = svc.getDb().currentPadType("chip-0603", Tip.SEED_KEY);
            assertEquals(restored.get(0).version, back.version);
            assertEquals(was.status, back.status, "the dose fields of the previous own version");
            assertEquals(was.dotSteps, back.dotSteps);
            assertEquals("restored: calibration " + rec.id + " was made with another tip", back.notes);
            assertTrue(svc.getDb().flaggedJobs().contains("job-w"));
            assertFalse(svc.dotTable().doses.containsKey(800), "the job's dots left the dot table");
            assertEquals(1, svc.getDb().query("SELECT * FROM dose_records WHERE kind = 'mass' AND coupon_record_id = ? "
                    + "AND why = 'the calibration was made with another tip'", couponId).size());
            // a second press changes nothing; an unknown job is not found
            assertTrue(svc.markWrongTip("job-w", "tester").isEmpty());
            assertEquals(back.version, svc.getDb().currentPadType("chip-0603", Tip.SEED_KEY).version);
            assertEquals("not-found", assertThrows(EngineRefusal.class, () -> svc.markWrongTip("job-none", null)).code);
        }
    }

    @Test
    public void aSwitchWithoutTheNeedleChangeBeforeItIsRefused() throws Exception {
        // the mount route records the needle change, then switches: a switch on its own (a route that forgot the
        // change) would let a parked frame newer than the token count for the needle put on now
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            assertNull(svc.getConfig().needleChangedAtMs, "no change recorded yet");
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> svc.switchTip(K25, "x", null));
            assertTrue(e.getMessage().startsWith("A mount needs the needle change recorded first"), e.getMessage());
            assertEquals(Tip.SEED_KEY, svc.getConfig().tip.key, "nothing was changed");
            assertFalse(svc.isBusy());
            f.mount(K25);
            f.frame(-3.0); // the 25G's frame, under the mount's token
            // back to the 22G without a change: its parked frame is older than the token, so it stays stale
            assertEquals(Tip.SEED_KEY, svc.switchTip(Tip.SEED_KEY, "x", null).mounted);
            assertFalse(svc.getConfig().isNeedleAnchored());
            // and to the 25G again without a change: its frame is not older than the token
            e = assertThrows(IllegalStateException.class, () -> svc.switchTip(K25, "x", null));
            assertTrue(e.getMessage().startsWith("A mount needs the needle change recorded first"), e.getMessage());
            assertEquals(Tip.SEED_KEY, svc.getConfig().tip.key);
            f.mount(K25);
            assertEquals(K25, svc.getConfig().tip.key);
            assertFalse(svc.getConfig().isNeedleAnchored(), "the 25G's frame is stale after the change");
        }
    }

    @Test
    public void theLiveStartAngleIsKeptAcrossAMount() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            // the 25G's entry was copied with an old angle; the live one is set after
            svc.updateConfigAtomically(live -> {
                live.parkedTips.get(K25).profile.startAngleDeg = 45;
                live.parkedTips.get(K25).profile.startAngleSetAtMs = 1000L;
                return live;
            });
            svc.setStartAngle(120.0, "measured");
            DispenseProfile live = svc.getConfig().profile;
            f.mount(K25);
            DispenseProfile now = svc.getConfig().profile;
            assertEquals(120, now.startAngleDeg, 0);
            assertEquals(live.startAngleSetAtMs, now.startAngleSetAtMs, "not stamped: no new measurement");
            // after a controller reconnect the angle is stale (I11); a mount leaves it as it was
            f.sim.machine.setEnabled(false);
            f.sim.enableAndHome();
            f.mount(Tip.SEED_KEY);
            assertEquals(live.startAngleSetAtMs, svc.getConfig().profile.startAngleSetAtMs);
            assertTrue(svc.getConfig().profile.startAngleSetAtMs < svc.getControllerConnectedAtMs(), "I11 still asks");
        }
    }

    @Test
    public void eachRefusalOfTheSwitchInOrderAndTheNeedleChangeIsRecordedInEvery() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            String recorded = " The needle change is recorded: nothing dispenses until the 25G straight is mounted and "
                    + "its needle Z frame recorded.";
            // a dispense task runs (held on the machine thread at the start of a dose-blocks step)
            CountDownLatch in = new CountDownLatch(1);
            CountDownLatch out = new CountDownLatch(1);
            DispenseService.testHook = point -> {
                if ("dose-blocks".equals(point)) {
                    in.countDown();
                    try {
                        out.await(30, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            DoseBlocksRequest req = new DoseBlocksRequest();
            req.kind = DoseBlocksRequest.LADDER;
            req.stopEpoch = svc.stopEpoch();
            DoseBlocksRequest.Block b = new DoseBlocksRequest.Block(800, "half", "blank");
            b.xy.add(new double[] {100, 40});
            req.blocks.add(b);
            Future<DoseBlocksResult> task = svc.runDoseBlocks(req);
            assertTrue(in.await(30, TimeUnit.SECONDS));
            long t0 = svc.getConfig().needleChangedAtMs == null ? 0 : svc.getConfig().needleChangedAtMs;
            EngineRefusal e = assertThrows(EngineRefusal.class, () -> f.mount(K25));
            assertEquals("refused", e.code);
            assertEquals("An engine task is running: a needle cannot come off while the head moves (press STOP first)."
                    + recorded, e.getMessage());
            assertTrue(svc.getConfig().needleChangedAtMs > t0, "the needle change was recorded first");
            assertEquals(Tip.SEED_KEY, svc.getConfig().tip.key);
            out.countDown();
            DoseBlocksResult res = task.get(60, TimeUnit.SECONDS);
            assertFalse(res.ok, "the needle change stopped it");
            DispenseService.testHook = null;
            assertTrue(i4Refuses(svc));
            // the pressure hold is on
            svc.setPressureHoldCheck(() -> true);
            e = assertThrows(EngineRefusal.class, () -> f.mount(K25));
            assertEquals("Switch the pressure off first: a needle comes off with the pressure off." + recorded,
                    e.getMessage());
            assertTrue(i4Refuses(svc));
            svc.setPressureHoldCheck(null);
            // retired
            TipSetup ret = svc.getDb().currentTipSetup(K25).copy();
            ret.retired = true;
            svc.getDb().insertTipSetupVersion(ret);
            e = assertThrows(EngineRefusal.class, () -> f.mount(K25));
            assertEquals("The 25G straight is retired: bring it back first (Change tip › Show retired tips)." + recorded,
                    e.getMessage());
            ret.retired = false;
            svc.getDb().insertTipSetupVersion(ret);
            // incomplete
            svc.getDb().insertTipSetupVersion(TipSetup.placeholder("x25"));
            e = assertThrows(EngineRefusal.class, () -> f.mount("x25"));
            assertEquals("The x25 has no ID yet: fill it in first (Edit…). The needle change is recorded: nothing "
                    + "dispenses until the x25 is mounted and its needle Z frame recorded.", e.getMessage());
            assertTrue(i4Refuses(svc));
            assertEquals(Tip.SEED_KEY, svc.getConfig().tip.key, "nothing switched");
        }
    }

    @Test
    public void aNeedleChangeIsNeverRefused() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            DispenseConfig c = svc.getConfig();
            // a coupon waiting for its readings, of a job waiting for them
            DoseRecord coupon = DoseRecordTest.record(DoseRecord.COUPON, 800, c, 0);
            coupon.jobId = "job-w";
            coupon.simulated = svc.isSimulatorConfirmed();
            coupon.regulatorPsi = c.profile.pressurePsi;
            coupon.startAngleSetAtMs = c.profile.startAngleSetAtMs;
            coupon = svc.getDb().insertDoseRecord(coupon);
            assertNotNull(svc.couponReservation("coupon1"));
            // the pressure hold on, and the mounted setup incomplete
            svc.setPressureHoldCheck(() -> true);
            TipSetup bad = svc.getDb().currentTipSetup(Tip.SEED_KEY).copy();
            bad.taper = TipSetup.TAPER_UNKNOWN;
            svc.getDb().insertTipSetupVersion(bad);
            NeedleChange nc = svc.recordNeedleChange("swapped", "tester");
            assertEquals(Tip.SEED_KEY, nc.setupKey);
            assertEquals("tester", nc.operator);
            assertEquals(java.util.Collections.singletonList("coupon1"), nc.voided.coupons);
            assertEquals("job-w", nc.voided.job);
            assertTrue(nc.text.contains("Wipe coupon1 before its next use"), nc.text);
            // the skip row: the database says why the weighing never came
            List<java.util.Map<String, Object>> skip = svc.getDb().query("SELECT why, job_id FROM dose_records WHERE "
                    + "kind = 'mass' AND coupon_record_id = ?", coupon.id);
            assertEquals(1, skip.size());
            assertTrue(((String) skip.get(0).get("why")).startsWith("the needle was changed at "), skip.toString());
            assertNull(svc.couponReservation("coupon1"), "freed (paste is still on it: wipe it)");
            assertEquals(TipSetups.Mount.NEEDLE, svc.getDb().tipMounts(1).get(0).kind);
            assertTrue(svc.getDb().tipMounts(1).get(0).voidedJson.contains("coupon1"));
            svc.setPressureHoldCheck(null);
            assertTrue(i4Refuses(svc));
        }
    }

    @Test
    public void aNeedleChangeDuringATaskStopsIt() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.sim.server.realDwell = true; // the valve settle (3 s) keeps the shots in their stream
            CountDownLatch streaming = new CountDownLatch(1);
            svc.addListener(new DispenseListener() {
                @Override
                public void onPhase(RunResult run, String phase) {
                    if ("shots".equals(phase)) {
                        streaming.countDown();
                    }
                }
            });
            Future<RunResult> shots = svc.runShots("waste", 90, 1, true);
            assertTrue(streaming.await(30, TimeUnit.SECONDS));
            NeedleChange nc = svc.recordNeedleChange(null, null);
            assertEquals("shots", nc.voided.task);
            RunResult r = shots.get(60, TimeUnit.SECONDS);
            assertEquals(RunResult.Status.ABORTED, r.status, r.toString());
        }
    }

    @Test
    public void anIncomingProfileThatNoLongerValidatesIsReset() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            // a parked value a newer rule refuses
            svc.updateConfigAtomically(live -> {
                live.parkedTips.get(K25).profile.gapMm = 0.5;
                return live;
            });
            MountResult r = f.mount(K25);
            assertEquals(1, r.warnings.size(), r.warnings.toString());
            assertTrue(r.warnings.get(0).startsWith("The 25G straight's dispense profile no longer validates ("),
                    r.warnings.get(0));
            assertTrue(svc.getConfig().profile.name.startsWith("25G straight (reset: "), svc.getConfig().profile.name);
            assertEquals(new DispenseProfile().gapMm, svc.getConfig().profile.gapMm, 0);
            assertFalse(svc.getDb().query("SELECT * FROM events WHERE code = 'TIP' AND message LIKE '%no longer "
                    + "validates%'").isEmpty());
        }
    }

    @Test
    public void undoWithinTwoMinutesAndNotAfterATaskOrAFrame() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            DispenseConfig before = svc.getConfig();
            assertThrows(EngineRefusal.class, () -> svc.undoMount("tester"), "nothing to undo");
            f.mount(K25);
            assertNotNull(svc.undoUntilMs());
            MountResult u = svc.undoMount("tester");
            assertEquals(Tip.SEED_KEY, u.mounted);
            DispenseConfig back = svc.getConfig();
            Gson g = new Gson();
            assertEquals(Tip.SEED_KEY, back.tip.key);
            assertEquals(g.toJson(before.needleAnchor), g.toJson(back.needleAnchor));
            assertEquals(before.needleChangedAtMs, back.needleChangedAtMs, "the needle never left: its token back");
            assertTrue(back.isNeedleAnchored());
            assertEquals(g.toJson(before.needleLanding), g.toJson(back.needleLanding));
            assertEquals(TipSetups.Mount.UNDO, svc.getDb().tipMounts(1).get(0).kind);
            assertNull(svc.undoUntilMs());
            assertNotNull(back.parkedTips.get(K25), "the 25G parked again");
            // a tip added between the mount and its undo keeps its entry: the undo puts back the mount only
            f.mount(K25);
            TipSetup s27 = TipSetupsTest.straight25();
            s27.key = "27g-straight";
            s27.name = "27G straight";
            s27.gauge = 27;
            s27.idMm = 0.20;
            s27.odMm = 0.41;
            TipSetup added = svc.getDb().insertTipSetupVersion(s27);
            svc.updateConfigAtomically(live -> {
                live.parkedTips.put(added.key, TipSetups.newEntry(live, new DispenseProfile(), "default", added.name));
                return live;
            });
            svc.undoMount("tester");
            assertEquals(Tip.SEED_KEY, svc.getConfig().tip.key);
            assertTrue(svc.getConfig().parkedTips.containsKey("27g-straight"), svc.getConfig().parkedTips.keySet()
                    .toString());
            assertTrue(svc.getConfig().parkedTips.containsKey(K25));
            assertFalse(svc.getConfig().parkedTips.containsKey(Tip.SEED_KEY), "the mounted tip is not parked");
            // after a frame was recorded: too late
            f.mount(K25);
            f.frame(-3.0);
            EngineRefusal e = assertThrows(EngineRefusal.class, () -> svc.undoMount("tester"));
            assertEquals("Too late to undo: the frame was recorded. Mount the " + Tip.SEED_NAME + " again instead.",
                    e.getMessage());
            // after a task ran: too late
            f.mount(Tip.SEED_KEY);
            svc.runShots("waste", 90, 1, true).get(60, TimeUnit.SECONDS);
            e = assertThrows(EngineRefusal.class, () -> svc.undoMount("tester"));
            assertEquals("Too late to undo: something ran. Mount the 25G straight again instead.", e.getMessage());
        }
    }
}
