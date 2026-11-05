package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            // the tip's 1.20 mm), squashed at 0.05; BR-89's 2 check dots at the reference gap, steady again
            svc.setDotImager(spots(1.90, 1.60, 0.76, 0.78, 0.77, 0.76, 1.15, 0.77, 0.76));
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
            assertEquals(9, t.analysis.dotsLaid, "7 and the 2 check dots");
            assertEquals(0.25, t.analysis.checkGapMm, 1e-12, "the first counted dot's gap");
            assertEquals(0, t.analysis.landingShiftMm, 1e-12);

            // the skip is the 25G recipe's: the 22G's is none, and the 25G's comes back with it
            f.mount(Tip.SEED_KEY);
            assertEquals(0, svc.getConfig().profile.touchOffSkipDots);
            assertEquals(2, svc.getConfig().parkedTips.get(MountTest.K25).profile.touchOffSkipDots);
            f.mount(MountTest.K25);
            assertEquals(2, svc.getConfig().profile.touchOffSkipDots);
        }
    }

    /**
     * BR-85 review: the engine keeps the reference pair at the reference gap as the API does, under the task's own
     * skip. With a skip of 3 the default Request (0.125 mm in steps of 0.025) would lay it at 0.050 and 0.025 mm and
     * apply the contact found after it; it is refused before anything moves.
     */
    @Test
    public void theEngineRefusesAReferencePairBelowTheReferenceGap() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            f.mount(MountTest.K25);
            f.frame(-2.59);
            f.land(0, 0);
            svc.updateConfigAtomically(live -> {
                live.profile.touchOffSkipDots = 3;
                return live;
            });
            // steady dots, then one squashed: a contact the ladder would apply
            svc.setDotImager(spots(1.0, 1.0, 1.0, 1.0, 1.0, 1.5));
            f.sim.server.mark();
            DispenseService.TouchOffResult t = svc.runNeedleTouchOff(new NeedleTouchOff.Request(), true)
                    .get(60, TimeUnit.SECONDS);
            assertEquals(RunResult.Status.REFUSED, t.run.status, t.run + " " + t.newOffsetMm);
            assertEquals("the 25G straight's recipe skips the first 3 dots after the prime (touchOffSkipDots): with a "
                    + "start gap of 0.125 mm its reference dots would sit at 0.050 mm and 0.025 mm, possibly at "
                    + "contact; start at 0.200 mm or more, so they sit at 0.125 mm and 0.100 mm as without a skip",
                    t.run.message);
            assertEquals(-2.59, svc.getConfig().needleZOffsetMm, 0, "nothing applied");
            for (String l : f.sim.server.sinceMark()) {
                assertFalse(l.startsWith("M106") || l.startsWith("G1") || l.startsWith("G0"), "nothing sent: " + l);
            }
        }
    }

    /** A passing camera record of whole-turn dots of {@code d} mm, made under {@code c}'s recipe, paste and tip. */
    static DoseRecord dots(DispenseService svc, DispenseConfig c, double d) {
        DoseRecord r = DoseRecordTest.record(DoseRecord.OPTICAL, 1600, c, System.currentTimeMillis());
        r.pass = true;
        r.diameterMeanMm = d;
        r.diameterP95Mm = d + 0.08;
        r.simulated = svc.isSimulatorConfirmed();
        return r;
    }

    /**
     * BR-85 review: the touch-off's reference is the mounted tip's own dots of its recipe and paste. A passing camera
     * record of another recipe, another paste or another tip is left out (the 22G model's then); the tip's own counts.
     */
    @Test
    public void dotsOfAnotherRecipePasteOrTipAreNotTheReference() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            f.mount(MountTest.K25);
            svc.updateConfigAtomically(live -> {
                live.profile.netStepsPerDot = 1600;
                return live;
            });
            DispenseConfig c = svc.getConfig();
            DispenseConfig recipe = c.copy();
            recipe.profile.pressurePsi += 2;
            DoseRecord other = dots(svc, recipe, 1.20);
            DoseRecord paste = dots(svc, c, 1.21);
            paste.pasteKey = "another-paste";
            DoseRecord tip = dots(svc, c, 1.22);
            tip.tipKey = Tip.SEED_KEY;
            for (DoseRecord r : new DoseRecord[] {other, paste, tip}) {
                svc.getDb().insertDoseRecord(r);
            }
            NeedleTouchOff.Request req = new NeedleTouchOff.Request();
            assertEquals(NeedleTouchOff.MODEL_REFERENCE, NeedleTouchOff.referenceFrom(req, c, svc.mountedTip()));
            assertEquals(NeedleTouchOff.steadyDotMm(req, c), NeedleTouchOff.steadyDotMm(req, c, svc.mountedTip()), 0);
            svc.getDb().insertDoseRecord(dots(svc, c, 1.23));
            assertEquals(1.23, NeedleTouchOff.steadyDotMm(req, c, svc.mountedTip()), 0, "its own");
        }
    }
}
