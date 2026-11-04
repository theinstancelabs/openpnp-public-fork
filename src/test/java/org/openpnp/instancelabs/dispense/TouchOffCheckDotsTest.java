package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.instancelabs.vision.SpotDot;

/**
 * BR-89 (Process 11-04 12:5x): every touch-off ladder ends with 2 check dots at the reference gap (the first counted
 * dot's), and the 2nd check dot's landing is compared with the first two counted dots': a tip pushed sideways or
 * slipping in the Luer at the ladder's floor moves it. Above 0.1 mm the new offset is not applied. Free dots are
 * compared with free dots only: a squashed dot's centroid moves as paste spreads around the pressed tip, so its shift
 * is information, never a refusal. The 1st check dot may carry paste picked up at the floor: it is not compared.
 */
public class TouchOffCheckDotsTest {
    /** The 25G's ladder (Process 12:4x): skip 3, 13 dots from 0.20 mm in steps of 0.025; its reference gap 0.125. */
    static NeedleTouchOff.Request ladder25() {
        NeedleTouchOff.Request req = new NeedleTouchOff.Request();
        req.startGapMm = 0.2;
        req.stepMm = 0.025;
        req.steps = 13;
        return req;
    }

    @Test
    public void everyLadderEndsWithTwoCheckDotsAtTheReferenceGap() {
        DispenseConfig c = new DispenseConfig();
        c.profile.touchOffSkipDots = 3;
        c.needleZOffsetMm = -0.2;
        NeedleTouchOff.Request req = ladder25();
        List<GcodeStreamBuilder.DotSpec> l = NeedleTouchOff.layout(req, new DispenseConfig.NamedLocation(10, 20, 1.5), c,
                null);
        assertEquals(15, l.size(), "13 ladder dots and 2 check dots");
        assertEquals(0.125, NeedleTouchOff.checkGapMm(req, 3), 1e-12);
        for (int i : new int[] {13, 14}) {
            assertEquals(1.5 - 0.2 + 0.125, l.get(i).dispenseZ, 1e-12, "check dot " + i + " at the reference gap");
            assertEquals(l.get(3).dispenseZ, l.get(i).dispenseZ, 1e-12, "the first counted dot's Z");
            assertEquals(10 + (i + 1) * 1.6, l.get(i).x, 1e-12, "on along the row, one pitch left empty after the "
                    + "floor dot (a squashed dot is wider)");
            assertEquals(20, l.get(i).y, 1e-12);
            assertEquals(i, l.get(i).index);
            assertEquals(l.get(0).hopZ, l.get(i).hopZ, 1e-12);
            assertTrue(l.get(i).label.startsWith("touch-off check"), l.get(i).label);
        }
        // without a skip the reference gap is the start gap
        c.profile.touchOffSkipDots = 0;
        assertEquals(1.5 - 0.2 + 0.2, NeedleTouchOff.layout(req, new DispenseConfig.NamedLocation(10, 20, 1.5), c, null)
                .get(14).dispenseZ, 1e-12);
    }

    /**
     * The 25G's ladder as a camera sees it: 3 droplets, the reference pair and 4 steady dots at 1.2 mm, then squashed
     * (contact between 0.000 and -0.025 mm); the droplets landed at (0.2, 0) (a wrong pair would show), the reference
     * pair at (0.01, 0) and (-0.01, 0.02), the 1st check dot 0.3 mm off (paste from the floor), the 2nd at
     * {@code check2}, the deepest squashed dot at {@code floor}.
     */
    static List<SpotDot> seen(double[] check2, double[] floor) {
        List<SpotDot> l = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            double[] at = i < 3 ? new double[] {0.2, 0} : i == 3 ? new double[] {0.01, 0} : i == 4
                    ? new double[] {-0.01, 0.02} : i == 12 ? floor
                    : i == 13 ? new double[] {0.3, 0} : i == 14 ? check2 : new double[] {0, 0.01};
            double d = i < 3 ? 0.5 : i < 9 || i > 12 ? 1.2 : 1.8;
            l.add(at == null ? SpotDot.none() : SpotDot.dot(new Dot(at[0], at[1], d, Math.PI * d * d / 4, 0.9)));
        }
        return l;
    }

    static NeedleTouchOff.Analysis analyzed(List<SpotDot> seen) {
        double[] g = NeedleTouchOff.gaps(ladder25());
        NeedleTouchOff.Analysis a = NeedleTouchOff.analyze(g, seen.subList(0, g.length), 0.25, 1.2, 3);
        NeedleTouchOff.checkLanding(a, seen, 3);
        return a;
    }

    @Test
    public void aSecondCheckDotOffTheReferenceLandingRefusesTheApply() {
        NeedleTouchOff.Analysis moved = analyzed(seen(new double[] {0.15, 0.01}, new double[] {0, 0.01}));
        assertTrue(moved.found(), moved.reason);
        assertEquals(-0.0125, moved.contactGapMm, 1e-9, "the contact stands: only its apply is refused");
        assertEquals(0.15, moved.landingShiftMm, 1e-9, "against the pair's mean landing (0, 0.01)");
        assertEquals("the 2nd check dot landed 0.150 mm from where the first two counted dots landed (at most 0.1 mm): "
                + "the tip may have moved; look at it from below and run the touch-off again", moved.landingProblem);
        assertEquals(0.125, moved.checkGapMm, 1e-12);
        assertEquals(15, moved.dotsLaid);
        assertEquals(2, moved.checkDots.size());
        assertEquals(0.15, moved.checkDots.get(1).dxMm, 1e-12);
        assertEquals(0.01, moved.checkDots.get(1).dyMm, 1e-12);
        assertEquals("dot", moved.checkDots.get(1).state);
        assertEquals(1.2, moved.checkDots.get(1).diameterMm, 1e-12);
        NeedleTouchOff.Analysis held = analyzed(seen(new double[] {0.05, 0.01}, new double[] {0, 0.01}));
        assertEquals(0.05, held.landingShiftMm, 1e-9);
        assertNull(held.landingProblem, "0.05 mm is the 25G's own landing scatter");
        assertTrue(held.found());
    }

    @Test
    public void aSquashedDotsShiftAloneIsInformationAndTheFirstCheckDotIsNotCompared() {
        // the deepest squashed dot 0.2 mm off and the 1st check dot 0.3 mm off; the 2nd check dot where the pair landed
        NeedleTouchOff.Analysis a = analyzed(seen(new double[] {0, 0.01}, new double[] {0.2, 0.01}));
        assertEquals(0, a.landingShiftMm, 1e-9);
        assertEquals(0.2, a.squashedShiftMm, 1e-9, "the deepest counted dot against the pair");
        assertNull(a.landingProblem);
        // no dot at the floor: the deepest one that formed counts (dot 11, where the pair landed)
        NeedleTouchOff.Analysis none = analyzed(seen(new double[] {0, 0.01}, null));
        assertEquals(0, none.squashedShiftMm, 1e-9);
    }

    @Test
    public void aSecondCheckDotThatCannotBeMeasuredRefusesTheApply() {
        List<SpotDot> l = seen(new double[] {0, 0.01}, new double[] {0, 0.01});
        l.set(14, SpotDot.none());
        NeedleTouchOff.Analysis gone = analyzed(l);
        assertNull(gone.landingShiftMm);
        assertEquals("the 2nd check dot at gap 0.125 mm left no dot: the landing cannot be checked; look at the tip from "
                + "below and run the touch-off again", gone.landingProblem);
        assertEquals("none", gone.checkDots.get(1).state);
        // a centroid that is no number fails closed
        l.set(14, SpotDot.dot(new Dot(Double.NaN, 0, 1.2, 1.13, 0.9)));
        assertTrue(analyzed(l).landingProblem != null, "NaN is no landing");
        l.set(14, SpotDot.unmeasured("strings only", Collections.emptyList()));
        assertEquals("the 2nd check dot at gap 0.125 mm is strings only: the landing cannot be checked; look at the tip "
                + "from below and run the touch-off again", analyzed(l).landingProblem);
        // the UI's word for the dots left out after the prime (UI 12:5x)
        l.set(3, SpotDot.none());
        assertTrue(analyzed(l).reason.contains("the first after the 3 not counted"), analyzed(l).reason);
    }
}
