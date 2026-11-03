package org.openpnp.instancelabs.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.vision.Synth.Deposit;
import org.openpnp.instancelabs.vision.Synth.Shot;

/**
 * {@link DotInspector#inspectSpots} on synthetic touch-off ladders: Lumen's blue mask, grey-blue paste, the real
 * camera's scale, half-turn dots 1.07 mm across at the touch-off pitch of 1.6 mm (F9: strings joined the real
 * ladder's dots into one blob, so no dot was measured). The cut is the touch-off's, 0.45 x 1.16 mm.
 */
public class DotInspectorSpotsTest {
    static final double MM_PER_PX = 0.0107;
    static final double PITCH = 1.6, DOT = 1.07, STRING = 0.25, CUT = 0.45 * 1.16, RADIUS = PITCH / 2;
    /** Five spots along +X, the middle one at the frame centre (the touch-off's frame of the middle dot). */
    static final List<double[]> SPOTS = Arrays.asList(new double[] {-2 * PITCH, 0}, new double[] {-PITCH, 0},
            new double[] {0, 0}, new double[] {PITCH, 0}, new double[] {2 * PITCH, 0});

    static Shot before() {
        Shot s = new Shot();
        s.width = 760;
        s.height = 300;
        s.mmPerPx = MM_PER_PX;
        s.scene = Synth.lumenPad(100, 100, 1, 1); // bare mask: the pad is far outside the frame
        s.pasteRgb = Synth.LUMEN_PASTE;
        s.noiseSeed = 21;
        return s;
    }

    static final BufferedImage BEFORE = Synth.render(before());

    /** The frame after the deposits (another noise draw, a 0.3 px re-positioning shift). */
    static BufferedImage after(List<Deposit> deposits) {
        Shot a = before().copy();
        a.noiseSeed = 22;
        a.shiftXPx = 0.3;
        a.deposits.addAll(deposits);
        return Synth.render(a);
    }

    /** A string between two spots' dots: a bar {@code width} wide from centre to centre. */
    static Deposit string(double x0, double x1, double width) {
        return Synth.rect((x0 + x1) / 2, 0, Math.abs(x1 - x0), width, 0);
    }

    static List<SpotDot> spots(List<Deposit> deposits) {
        return new DotInspector(new DotInspectorSettings()).inspectSpots(BEFORE, after(deposits), MM_PER_PX,
                MM_PER_PX, SPOTS, CUT, RADIUS);
    }

    static void assertDot(SpotDot s, double d, String what) {
        System.out.println(String.format(Locale.ROOT, "[dot-vision] spots %-34s %s", what, s));
        assertEquals(SpotDot.State.DOT, s.state, what + ": " + s);
        assertEquals(d, s.dot.diameterMm, Math.max(0.05 * d, 0.03), what + ": " + s);
    }

    /** Five dots strung together: the old measure finds none, the spots all five, each its own size. */
    @Test
    public void strungDotsAreEachFoundAtTheirSpot() {
        List<Deposit> d = new ArrayList<>();
        double[] sizes = {1.07, 1.00, 1.07, 1.40, 1.07};
        for (int i = 0; i < SPOTS.size(); i++) {
            d.add(Synth.disk(SPOTS.get(i)[0], 0, sizes[i]));
        }
        for (int i = 0; i + 1 < SPOTS.size(); i++) {
            d.add(string(SPOTS.get(i)[0], SPOTS.get(i + 1)[0], STRING));
        }
        BufferedImage after = after(d);
        DotInspector di = new DotInspector(new DotInspectorSettings());
        List<Dot> inspected = di.inspect(BEFORE, after, MM_PER_PX, MM_PER_PX);
        assertEquals(Collections.nCopies(5, SpotDot.State.NONE), states(SpotDot.nearest(inspected, SPOTS, RADIUS)),
                "the old measure: one blob, dropped by the filters: " + inspected);
        List<SpotDot> seen = di.inspectSpots(BEFORE, after, MM_PER_PX, MM_PER_PX, SPOTS, CUT, RADIUS);
        for (int i = 0; i < SPOTS.size(); i++) {
            assertDot(seen.get(i), sizes[i], "strung dot " + i);
            assertEquals(SPOTS.get(i)[0], seen.get(i).dot.xMm, 0.03, "centred on its spot");
        }
        // a squashed dot (1.40 mm) stays measurable among strung ones, so the touch-off still sees its jump
        assertTrue(seen.get(3).dot.diameterMm > 1.25 * seen.get(2).dot.diameterMm, seen.toString());
    }

    /** No dot at the middle spot and no paste near it: "no paste", the dots on either side measured. */
    @Test
    public void aTrulyMissingDotIsNone() {
        List<Deposit> d = new ArrayList<>();
        for (int i : new int[] {0, 1, 3, 4}) {
            d.add(Synth.disk(SPOTS.get(i)[0], 0, DOT));
        }
        d.add(string(SPOTS.get(0)[0], SPOTS.get(1)[0], STRING));
        d.add(string(SPOTS.get(3)[0], SPOTS.get(4)[0], STRING));
        List<SpotDot> seen = spots(d);
        assertEquals(SpotDot.State.NONE, seen.get(2).state, seen.get(2).toString());
        for (int i : new int[] {0, 1, 3, 4}) {
            assertDot(seen.get(i), DOT, "beside a missing dot " + i);
        }
    }

    /** No dot at the middle spot, but the string from one neighbour to the other runs through it: not "no dot". */
    @Test
    public void aStringThroughAnEmptySpotIsNotNone() {
        List<Deposit> d = new ArrayList<>();
        for (int i : new int[] {0, 1, 3, 4}) {
            d.add(Synth.disk(SPOTS.get(i)[0], 0, DOT));
        }
        d.add(string(SPOTS.get(1)[0], SPOTS.get(3)[0], STRING));
        SpotDot s = spots(d).get(2);
        assertEquals(SpotDot.State.UNMEASURED, s.state, s.toString());
        assertTrue(s.note.startsWith("no dot, only strings or a smear ("), s.note);
        assertTrue(s.joinedTo.isEmpty());
    }

    /** Two dots whose bodies overlap, and two joined by a neck thicker than the cut: not measurable, never "no dot". */
    @Test
    public void mergedDotsAreNotMeasured() {
        List<Deposit> d = new ArrayList<>();
        // spots 0 and 1: 1.07 mm dots 0.9 mm apart (the second laid 0.7 mm short of its spot)
        d.add(Synth.disk(SPOTS.get(0)[0], 0, DOT));
        d.add(Synth.disk(SPOTS.get(0)[0] + 0.9, 0, DOT));
        // spots 3 and 4: on their spots, joined by a 0.7 mm neck (the cut is 0.52 mm)
        d.add(Synth.disk(SPOTS.get(3)[0], 0, DOT));
        d.add(Synth.disk(SPOTS.get(4)[0], 0, DOT));
        d.add(string(SPOTS.get(3)[0], SPOTS.get(4)[0], 0.7));
        d.add(Synth.disk(0, 0, DOT));
        List<SpotDot> seen = spots(d);
        assertEquals(SpotDot.State.UNMEASURED, seen.get(0).state, seen.get(0).toString());
        assertEquals(Collections.singletonList(1), seen.get(0).joinedTo);
        assertEquals(Collections.singletonList(0), seen.get(1).joinedTo);
        assertEquals("joined to the paste at another spot", seen.get(1).note);
        assertDot(seen.get(2), DOT, "a lone dot between merged pairs");
        assertEquals(Collections.singletonList(4), seen.get(3).joinedTo);
        assertEquals(Collections.singletonList(3), seen.get(4).joinedTo);
    }

    /** A weak dot smaller than the cut is still a dot when it stands alone, but not when a string ties it to another. */
    @Test
    public void aWeakDotSmallerThanTheCut() {
        List<Deposit> alone = new ArrayList<>();
        alone.add(Synth.disk(SPOTS.get(1)[0], 0, DOT));
        alone.add(Synth.disk(0.1, -0.15, 0.35));
        alone.add(Synth.disk(SPOTS.get(3)[0], 0, DOT));
        assertDot(spots(alone).get(2), 0.35, "a weak dot alone");

        List<Deposit> tied = new ArrayList<>(alone);
        tied.add(string(SPOTS.get(1)[0], 0.1, 0.2));
        List<SpotDot> seen = spots(tied);
        assertEquals(SpotDot.State.UNMEASURED, seen.get(2).state, seen.get(2).toString());
        assertDot(seen.get(1), DOT, "its neighbour, strung to it");
    }

    /** Spots outside the frame, or too near its edge to see their surroundings, are never "no paste". */
    @Test
    public void theFrameEdge() {
        List<double[]> far = Arrays.asList(new double[] {0, 0}, new double[] {5.0, 0}, new double[] {3.7, 0});
        List<SpotDot> seen = new DotInspector(new DotInspectorSettings()).inspectSpots(BEFORE, after(
                Collections.singletonList(Synth.disk(0, 0, DOT))), MM_PER_PX, MM_PER_PX, far, CUT, RADIUS);
        assertDot(seen.get(0), DOT, "the centre");
        assertEquals("outside the camera frame", seen.get(1).note);
        assertEquals("partly outside the camera frame", seen.get(2).note);
        assertThrows(IllegalArgumentException.class, () -> new DotInspector(null).inspectSpots(BEFORE, BEFORE,
                MM_PER_PX, MM_PER_PX, SPOTS, CUT, 0));
    }

    static List<SpotDot.State> states(List<SpotDot> l) {
        List<SpotDot.State> s = new ArrayList<>();
        for (SpotDot x : l) {
            s.add(x.state);
        }
        return s;
    }
}
