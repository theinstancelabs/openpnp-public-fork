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
     * The 25G's ladder as a camera sees it: 3 droplets, the reference pair at 1.2 mm and 4 steady dots at 1.25 mm (a
     * free dot grows as the gap shrinks: a wrong pair's area would show), then squashed (contact between 0.000 and
     * -0.025 mm); the droplets landed at (0.2, 0) (a wrong pair would show), the reference pair at (0.01, 0) and
     * (-0.01, 0.02), the 1st check dot 0.3 mm off (paste from the floor), the 2nd at {@code check2}, the deepest
     * squashed dot at {@code floor}; both check dots 1.2 mm.
     */
    static List<SpotDot> seen(double[] check2, double[] floor) {
        List<SpotDot> l = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            double[] at = i < 3 ? new double[] {0.2, 0} : i == 3 ? new double[] {0.01, 0} : i == 4
                    ? new double[] {-0.01, 0.02} : i == 12 ? floor
                    : i == 13 ? new double[] {0.3, 0} : i == 14 ? check2 : new double[] {0, 0.01};
            double d = i < 3 ? 0.5 : i < 5 || i > 12 ? 1.2 : i < 9 ? 1.25 : 1.8;
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

    /** {@link #seen} with the 2nd check dot's area {@code ratio} times the reference pair's (it lands where they did). */
    static List<SpotDot> withCheckArea(double ratio) {
        List<SpotDot> l = seen(new double[] {0, 0.01}, new double[] {0, 0.01});
        double a = Math.PI * 1.2 * 1.2 / 4 * ratio;
        l.set(14, SpotDot.dot(new Dot(0, 0.01, Math.sqrt(4 * a / Math.PI), a, 0.9)));
        return l;
    }

    /**
     * Process 11-04 15:1x: the needle's height can change without the tip moving sideways (it slipped up or down in the
     * Luer at the floor): the 2nd check dot's area more than 25 % off the reference pair's mean refuses the apply too,
     * after the landing check; a NaN fails closed.
     */
    @Test
    public void aSecondCheckDotWhoseAreaIsOffRefusesTheApply() {
        NeedleTouchOff.Analysis big = analyzed(withCheckArea(1.30));
        assertEquals(0, big.landingShiftMm, 1e-9, "it landed where the pair did");
        assertEquals(30, big.checkAreaOffPct, 1e-6);
        assertEquals("the 2nd check dot's area is off by 30 % from the reference pair's (1.470 vs 1.131 mm², at most "
                + "25 %): the needle's height may have changed; look at the tip from below and run the touch-off again",
                big.landingProblem);
        assertTrue(big.found(), "the contact stands: only its apply is refused");
        assertTrue(analyzed(withCheckArea(0.70)).landingProblem.contains("off by 30 %"), "smaller counts too");
        NeedleTouchOff.Analysis near = analyzed(withCheckArea(1.20));
        assertEquals(20, near.checkAreaOffPct, 1e-6);
        assertNull(near.landingProblem, "20 % is within the 25 %");
        assertEquals(near.areasMm2[3] * 1.2, near.checkDots.get(1).areaMm2, 1e-9);
        // a NaN area fails closed
        List<SpotDot> nan = withCheckArea(1);
        nan.set(14, SpotDot.dot(new Dot(0, 0.01, 1.2, Double.NaN, 0.9)));
        assertEquals("the 2nd check dot's area cannot be compared with the reference pair's: look at the tip from below "
                + "and run the touch-off again", analyzed(nan).landingProblem);
        // the landing is checked first: a moved tip says so, whatever the area
        List<SpotDot> both = seen(new double[] {0.15, 0.01}, new double[] {0, 0.01});
        both.set(14, SpotDot.dot(new Dot(0.15, 0.01, 1.5, 1.77, 0.9)));
        assertTrue(analyzed(both).landingProblem.startsWith("the 2nd check dot landed 0.150 mm"),
                analyzed(both).landingProblem);
    }

    /** Process 11-04 15:1x: the record keeps every ladder dot's landing and area, and each check dot's area. */
    @Test
    public void everyLadderDotsLandingAndAreaAreRecorded() {
        NeedleTouchOff.Analysis a = analyzed(seen(new double[] {0, 0.01}, null));
        assertEquals(13, a.landingsMm.length);
        assertEquals(13, a.areasMm2.length);
        assertEquals(0.2, a.landingsMm[0][0], 1e-12, "a droplet's landing too");
        assertEquals(0.01, a.landingsMm[3][0], 1e-12);
        assertEquals(0.02, a.landingsMm[4][1], 1e-12);
        assertEquals(Math.PI * 0.25 / 4, a.areasMm2[0], 1e-12);
        assertEquals(Math.PI * 1.8 * 1.8 / 4, a.areasMm2[9], 1e-12, "a squashed dot's area");
        assertNull(a.landingsMm[12], "no dot at the floor: no landing");
        assertNull(a.areasMm2[12]);
        assertEquals(Math.PI * 1.2 * 1.2 / 4, a.checkDots.get(0).areaMm2, 1e-12);
        // a centroid or area that is no number is kept as null: the record's JSON refuses NaN
        List<SpotDot> l = seen(new double[] {0, 0.01}, null);
        l.set(12, SpotDot.dot(new Dot(Double.NaN, 0, 1.8, Double.NaN, 0.9)));
        NeedleTouchOff.Analysis nan = analyzed(l);
        assertNull(nan.landingsMm[12]);
        assertNull(nan.areasMm2[12]);
        assertNull(nan.squashedShiftMm, "the deepest dot's shift is no number");
        new com.google.gson.Gson().toJson(nan);
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

    /**
     * A reference dot that left no dot: no contact, and no pair to compare the measured check dots with, so the 2nd
     * check dot's landing and area are not compared (it is off in both here) and only the reason says why.
     */
    @Test
    public void aMissingReferenceDotLeavesTheCheckDotsUncompared() {
        List<SpotDot> l = seen(new double[] {0, 0.01}, new double[] {0, 0.01});
        l.set(4, SpotDot.none());
        l.set(14, SpotDot.dot(new Dot(0.15, 0.01, 1.5, 1.77, 0.9)));
        NeedleTouchOff.Analysis a = analyzed(l);
        assertEquals("only the first dot after the 3 not counted formed before a missing one (1.200 mm at gap 0.125, "
                + "none at gap 0.100): a contact needs two steady dots before it (the first may be a droplet from too "
                + "high); run the ladder again from another start gap", a.reason);
        assertNull(a.landingProblem);
        assertNull(a.landingShiftMm);
        assertNull(a.checkAreaOffPct);
        assertEquals("dot", a.checkDots.get(1).state, "the 2nd check dot was measured");
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
