package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Tip;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.instancelabs.vision.SpotDot;

/**
 * BR-85: the touch-off of a mounted tip that is not the 22G, on the SIM machine. Its recipe's touchOffSkipDots leaves
 * the start blob out of the reference, and the reference is judged against the tip's own dots of the dose (its dot
 * table), not the 22G model; the skip is parked and restored with the tip's recipe.
 */
public class NeedleTouchOffTipTest {
    /** The camera at the touch-off's spots: one dot of {@code sizes[j]} mm at spot j. */
    static DotImager spots(double... sizes) {
        return new DotImager() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Frame capture(double x, double y) {
                return new Frame(null, 0.01, 0.01, x, y);
            }

            @Override
            public List<Dot> inspect(Frame before, Frame after) {
                throw new AssertionError("the touch-off finds its dots at their spots");
            }

            @Override
            public List<SpotDot> inspectSpots(Frame before, Frame after, List<double[]> s, double cut, double radius) {
                List<SpotDot> l = new ArrayList<>();
                for (int j = 0; j < s.size(); j++) {
                    l.add(SpotDot.dot(new Dot(s.get(j)[0], s.get(j)[1], sizes[j], Math.PI / 4 * sizes[j] * sizes[j],
                            0.9)));
                }
                return l;
            }
        };
    }

    @Test
    public void theTouchOffSkipsTheTipsStartBlobAndJudgesItsOwnDots() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            f.mount(MountTest.K25);
            f.frame(-3.0);
            f.land(0, 0);
            svc.updateConfigAtomically(live -> {
                live.profile.netStepsPerDot = 1600;
                live.profile.touchOffSkipDots = 2;
                return live;
            });
            // the 25G's whole-turn dots on the blank: 1.20 mm (its dot table)
            DispenseConfig c = svc.getConfig();
            DoseRecord own = DoseRecordTest.record(DoseRecord.OPTICAL, 1600, c, System.currentTimeMillis());
            own.pass = true;
            own.diameterMeanMm = 1.20;
            own.diameterP95Mm = 1.28;
            own.simulated = svc.isSimulatorConfirmed();
            svc.getDb().insertDoseRecord(own);
            long at = svc.mountedTip().measured.get(1600).atMs;
            // the start blob on dots 1 and 2, then 0.77 mm dots (droplets for the 22G model's 1.345 mm, steady dots for
            // the tip's 1.20 mm), squashed at 0.05
            svc.setDotImager(spots(1.90, 1.60, 0.76, 0.78, 0.77, 0.76, 1.15));
            NeedleTouchOff.Request req = new NeedleTouchOff.Request();
            req.startGapMm = 0.35;
            req.stepMm = 0.05;
            req.steps = 7;
            req.pitchMm = NeedleTouchOff.defaultPitchMm(req, svc.getConfig());
            DispenseService.TouchOffResult t = svc.runNeedleTouchOff(req, false).get(60, TimeUnit.SECONDS);
            assertEquals(RunResult.Status.OK, t.run.status, t.run + " " + t.run.interlocks);
            assertTrue(t.analysis.found(), t.analysis.reason);
            assertEquals(2, t.analysis.skippedDots);
            assertEquals(0.77, t.analysis.referenceDiameterMm, 1e-12, "dots 3 and 4");
            assertEquals(0.075, t.analysis.contactGapMm, 1e-12);
            assertEquals("the tip's dots at 1600 steps (" + ZCheck.dayClock(at) + ")", t.analysis.referenceFrom);

            // the skip is the 25G recipe's: the 22G's is none, and the 25G's comes back with it
            f.mount(Tip.SEED_KEY);
            assertEquals(0, svc.getConfig().profile.touchOffSkipDots);
            assertEquals(2, svc.getConfig().parkedTips.get(MountTest.K25).profile.touchOffSkipDots);
            f.mount(MountTest.K25);
            assertEquals(2, svc.getConfig().profile.touchOffSkipDots);
        }
    }
}
