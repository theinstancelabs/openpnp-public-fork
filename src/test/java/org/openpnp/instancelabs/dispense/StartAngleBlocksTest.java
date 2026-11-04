package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * BR-77: the engine hooks of the start-angle measure. A dose-blocks step lays its block at the request's own start
 * angle (builder and stream validator agree on it), and the stored angle stays as it was.
 */
public class StartAngleBlocksTest {
    /** The count angle each dot of the step's first block started at (deg). */
    static double[] angles(DoseBlocksResult r) {
        List<DoseBlocksResult.DotOut> dots = r.blocks.get(0).dots;
        double[] a = new double[dots.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = DispenseProfile.angleOfCount(dots.get(i).startCount, 1600);
        }
        return a;
    }

    @Test
    public void aStepLaysItsBlockAtTheRequestsStartAngle() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            svc.setDotImager(new LandingCheckBlocksTest.BandImager());
            DoseBlocksRequest req = LandingCheckBlocksTest.ladder(svc, LandingCheckBlocksTest.row(98, 40, 4));
            req.purgeXy = LandingCheckBlocksTest.row(98, 46, 2);
            req.startAngleDeg = 100.0;
            DoseBlocksResult r = svc.runDoseBlocks(req).get(120, TimeUnit.SECONDS);
            assertTrue(r.ok, r.message);
            double[] a = angles(r);
            assertEquals(4, a.length);
            for (int i = 0; i < a.length; i++) {
                assertEquals(i % 2 == 0 ? 100 : 280, a[i], 0.225 / 2, "dot " + (i + 1));
            }
            assertEquals(298, svc.getConfig().profile.startAngleDeg, 0, "the stored angle is unchanged");
            // without it the profile's angle
            DoseBlocksRequest own = LandingCheckBlocksTest.ladder(svc, LandingCheckBlocksTest.row(98, 42, 2));
            own.purgeXy = LandingCheckBlocksTest.row(98, 48, 2);
            DoseBlocksResult o = svc.runDoseBlocks(own).get(120, TimeUnit.SECONDS);
            assertTrue(o.ok, o.message);
            assertEquals(298, angles(o)[0], 0.225);
        }
    }

    /**
     * A measure step (measuresStartAngle) lays the half and quarter turns a whole-turn recipe's tip does not take, and
     * I11 does not stop it; any other step keeps both rules.
     */
    @Test
    public void aMeasureStepIsWaivedI11AndTheTipsSmallestDose() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            svc.setDotImager(new LandingCheckBlocksTest.BandImager());
            // the 25G 0603 recipe: whole turns only; then the screw's motor went off after the angle was set (I11)
            svc.updateConfigAtomically(live -> {
                live.profile.netStepsPerDot = 1600;
                live.tip.minDotSteps = 1600;
                return live;
            });
            svc.noteControllerLine("M18 B");
            DoseBlocksRequest req = LandingCheckBlocksTest.ladder(svc, LandingCheckBlocksTest.row(98, 40, 4));
            req.purgeXy = LandingCheckBlocksTest.row(98, 46, 2);
            req.startAngleDeg = 100.0;
            DoseBlocksResult refused = svc.runDoseBlocks(req).get(120, TimeUnit.SECONDS);
            assertFalse(refused.ok);
            assertTrue(refused.message.contains("PCAL: 800 steps is not a dot the"), refused.message);
            req.measuresStartAngle = true;
            DoseBlocksResult r = svc.runDoseBlocks(req).get(120, TimeUnit.SECONDS);
            assertTrue(r.ok, r.message);
            assertTrue(r.run.interlocks.notes.contains("I11: waived: this run measures the start angle"),
                    r.run.interlocks.notes.toString());
            assertEquals(100, angles(r)[0], 0.225 / 2);
            assertEquals(1600, svc.getConfig().tip.minDotSteps, "the tip's own floor is unchanged");
            DoseBlocksRequest quarters = LandingCheckBlocksTest.ladder(svc, LandingCheckBlocksTest.row(98, 42, 4));
            quarters.blocks.get(0).netSteps = 400;
            quarters.purgeXy = LandingCheckBlocksTest.row(98, 48, 2);
            quarters.measuresStartAngle = true;
            DoseBlocksResult q = svc.runDoseBlocks(quarters).get(120, TimeUnit.SECONDS);
            assertTrue(q.ok, q.message);
            assertEquals(298, angles(q)[0], 0.225);
            assertEquals(28, angles(q)[1], 0.225);
            // a step that measures nothing: I11 in force
            svc.updateConfigAtomically(live -> {
                live.tip.minDotSteps = 400;
                return live;
            });
            DoseBlocksRequest plain = LandingCheckBlocksTest.ladder(svc, LandingCheckBlocksTest.row(98, 44, 2));
            plain.purgeXy = LandingCheckBlocksTest.row(98, 50, 2);
            DoseBlocksResult i11 = svc.runDoseBlocks(plain).get(120, TimeUnit.SECONDS);
            assertFalse(i11.ok);
            assertTrue(i11.message.contains("I11: the screw motor was switched off") && i11.message.contains("measure the "
                    + "start angle again"), i11.message);
        }
    }

    /** A set from a measure stores its feature, under BR-02's rules: evidence from before a switch-off is refused. */
    @Test
    public void aSetFromAMeasureStoresItsFeatureUnderBr02() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            long before = System.currentTimeMillis();
            Thread.sleep(5);
            svc.noteControllerLine("M18 B");
            Thread.sleep(5);
            svc.noteControllerLine("G1 B30 F600"); // the screw turned since
            DispenseProfile.StartAngleFeature old = new DispenseProfile.StartAngleFeature("halves", 179.1, before,
                    "pc-1");
            EngineRefusal e = assertThrows(EngineRefusal.class, () -> svc.setStartAngle(359.1, "measure pc-1", before,
                    old));
            assertTrue(e.getMessage().contains("measure it again"), e.getMessage());
            assertNull(svc.getConfig().profile.startAngleFeature);
            Thread.sleep(5);
            long now = System.currentTimeMillis();
            DispenseProfile p = svc.setStartAngle(359.1, "measure pc-2", now, new DispenseProfile.StartAngleFeature(
                    "halves", 179.1, now, "pc-2"));
            assertEquals(359.1, p.startAngleDeg, 0);
            assertEquals("pc-2", p.startAngleFeature.runId);
            assertEquals(179.1, svc.getConfig().profile.startAngleFeature.deg, 0);
            assertTrue(svc.getConfig().profile.startAngleSetAtMs >= now);
        }
    }

    /**
     * A measure's apply reads the stored angle and its feature and sets the new one under the service's lock: new start =
     * start + (measured feature - stored feature); refused when the stored feature is gone or of the other mode.
     */
    @Test
    public void anApplyKeepsTheRecipesOffsetUnderOneLock() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            long t0 = System.currentTimeMillis();
            svc.setStartAngle(0.4, "measure pc-1", t0, new DispenseProfile.StartAngleFeature("halves", 180.41, t0,
                    "pc-1"));
            DispenseProfile.StartAngleFeature quarters = new DispenseProfile.StartAngleFeature("quarters", 300, t0,
                    "pc-2");
            EngineRefusal other = assertThrows(EngineRefusal.class, () -> svc.applyStartAngle(quarters));
            assertTrue(other.getMessage().contains("no measured quarters feature (it was set from a halves measure "
                    + "since)"), other.getMessage());
            long t1 = System.currentTimeMillis();
            double[] ft = svc.applyStartAngle(new DispenseProfile.StartAngleFeature("halves", 88.7, t1, "pc-3"));
            assertEquals(0.4, ft[0], 0);
            assertEquals(268.69, ft[1], 1e-9);
            DispenseProfile p = svc.getConfig().profile;
            assertEquals(268.69, p.startAngleDeg, 1e-9);
            assertEquals("pc-3", p.startAngleFeature.runId);
            svc.setStartAngle(10.0, "typed");
            EngineRefusal none = assertThrows(EngineRefusal.class, () -> svc.applyStartAngle(new DispenseProfile
                    .StartAngleFeature("halves", 90, System.currentTimeMillis(), "pc-4")), "a set without a measure "
                            + "dropped the feature");
            assertTrue(none.getMessage().contains("no measured halves feature (it was set without a measure since)"),
                    none.getMessage());
            assertEquals(10, svc.getConfig().profile.startAngleDeg, 0);
        }
    }

    /**
     * A measure step without the controller's B step count (M114 Count) is refused before it homes, checks Z or looks:
     * nothing moves and the camera takes no frame.
     */
    @Test
    public void aMeasureStepWithoutTheStepCountIsRefusedBeforeItHomesOrLooks() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            svc.setDotImager(new LandingCheckBlocksTest.BandImager());
            svc.updateConfigAtomically(live -> {
                live.homeBeforeRun = DispenseConfig.HOME_ALWAYS;
                return live;
            });
            f.sim.server.reportBCount = false;
            f.sim.server.mark();
            DoseBlocksRequest req = LandingCheckBlocksTest.ladder(svc, LandingCheckBlocksTest.row(98, 40, 4));
            req.purgeXy = LandingCheckBlocksTest.row(98, 46, 2);
            req.startAngleDeg = 100.0;
            req.measuresStartAngle = true;
            DoseBlocksResult r = svc.runDoseBlocks(req).get(120, TimeUnit.SECONDS);
            assertFalse(r.ok);
            assertTrue(r.message.contains("I11: the controller did not report the B step count (M114 Count)"),
                    r.message);
            assertEquals(0, r.frames, "no camera frame");
            for (String l : f.sim.server.sinceMark()) {
                assertFalse(l.startsWith("G28") || l.startsWith("G0 ") || l.startsWith("G1 "), "nothing moved: " + l);
            }
        }
    }
}
