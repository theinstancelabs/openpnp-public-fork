package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.DoseModel;
import org.openpnp.instancelabs.vision.DotInspector;
import org.openpnp.instancelabs.vision.DotInspectorSettings;
import org.openpnp.instancelabs.vision.SpotDot;

/**
 * F9 on the real frames of the second touch-off of session 4 (2026-10-29; the fixtures and where the spots lie:
 * {@code src/test/resources/org/openpnp/instancelabs/vision/touchoff/README.md}). Every dot of the ladder formed, joined
 * to the next by thin paste strings; the engine measured one dot of 0.373 mm, then "no dot" 9 times, and read
 * "dot stopped forming between 0.450 and 0.400 mm". Found at their spots with the strings cut off, the dots are there,
 * two pairs are joined by thick necks, and the ladder refuses to conclude.
 */
public class TouchOffRealFrameTest {
    static final String DIR = "/org/openpnp/instancelabs/vision/touchoff/";
    /** The ladder: 10 half-turn dots, gaps 0.45 to 0.00 mm, 1.6 mm apart along +X. */
    static final double PITCH = 1.6;
    static final double[] GAPS = {0.45, 0.40, 0.35, 0.30, 0.25, 0.20, 0.15, 0.10, 0.05, 0.0};
    /** OpenPnP's frame: its scale, the spot of dot 9 (the full frame's centre) and the ladder's band, in crop pixels. */
    static final double OPENPNP_X = 0.01068, OPENPNP_Y = 0.01073;
    static final double[] OPENPNP_SPOT9 = {960, 160};
    static final int[] OPENPNP_BAND = {0, 1040, 115, 255};
    /** The bench frame (turned into OpenPnP's orientation): scale, the spot of dot 0, the band. */
    static final double BENCH_X = 0.01074, BENCH_Y = 0.01106;
    static final double[] BENCH_SPOT0 = {193.2, 155};
    static final int[] BENCH_BAND = {0, 800, 105, 245};

    static BufferedImage load(String name) throws Exception {
        try (InputStream in = TouchOffRealFrameTest.class.getResourceAsStream(DIR + name)) {
            assertNotNull(in, name);
            return ImageIO.read(in);
        }
    }

    /**
     * A frame from before the dots: {@code after} with the ladder's band (columns x0..x1, rows y0..y1, exclusive)
     * replaced by a vertical blend of the 24 bare rows above and below it, each averaged over 25 columns.
     */
    static BufferedImage withoutBand(BufferedImage after, int[] band) {
        int x0 = band[0], x1 = band[1], y0 = band[2], y1 = band[3];
        int n = 24;
        BufferedImage b = new BufferedImage(after.getWidth(), after.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        b.getGraphics().drawImage(after, 0, 0, null);
        double[][] above = new double[after.getWidth()][3];
        double[][] below = new double[after.getWidth()][3];
        for (int x = 0; x < after.getWidth(); x++) {
            for (int y = 0; y < n; y++) {
                add(above[x], after.getRGB(x, y0 - n + y), 1.0 / n);
                add(below[x], after.getRGB(x, y1 + y), 1.0 / n);
            }
        }
        for (int x = x0; x < x1; x++) {
            double[] p = new double[3];
            double[] q = new double[3];
            int m = 0;
            for (int k = Math.max(0, x - 12); k <= Math.min(after.getWidth() - 1, x + 12); k++, m++) {
                for (int c = 0; c < 3; c++) {
                    p[c] += above[k][c];
                    q[c] += below[k][c];
                }
            }
            for (int y = y0; y < y1; y++) {
                double t = (y - (y0 - n / 2.0)) / ((y1 + n / 2.0) - (y0 - n / 2.0));
                int rgb = 0;
                for (int c = 0; c < 3; c++) {
                    int v = (int) Math.round(((1 - t) * p[c] + t * q[c]) / m);
                    rgb = (rgb << 8) | Math.max(0, Math.min(255, v));
                }
                b.setRGB(x, y, rgb);
            }
        }
        return b;
    }

    private static void add(double[] sum, int rgb, double w) {
        sum[0] += ((rgb >> 16) & 0xff) * w;
        sum[1] += ((rgb >> 8) & 0xff) * w;
        sum[2] += (rgb & 0xff) * w;
    }

    /** The spots of the 10 dots in a frame, mm from its centre: dot {@code ref} at pixel {@code at}, +X to the right. */
    static List<double[]> spots(BufferedImage img, double sx, double sy, int ref, double[] at) {
        List<double[]> l = new ArrayList<>();
        for (int j = 0; j < GAPS.length; j++) {
            l.add(new double[] {(at[0] - img.getWidth() / 2.0) * sx + (j - ref) * PITCH,
                -(at[1] - img.getHeight() / 2.0) * sy});
        }
        return l;
    }

    /** The cut of a half-turn ladder (the profile's dot), as the touch-off makes it. */
    static double cut() {
        return NeedleTouchOff.cutMm(DoseModel.dHiMm(DoseModel.HALF));
    }

    static void assertDot(SpotDot s, double[] spot, double min, double max, String what) {
        assertEquals(SpotDot.State.DOT, s.state, what + ": " + s);
        assertTrue(s.dot.diameterMm >= min && s.dot.diameterMm <= max, what + ": " + s);
        assertTrue(Math.hypot(s.dot.xMm - spot[0], s.dot.yMm - spot[1]) < 0.4, what + " near its spot: " + s);
    }

    static void assertJoined(SpotDot s, int to, String what) {
        assertEquals(SpotDot.State.UNMEASURED, s.state, what + ": " + s);
        assertEquals(Arrays.asList(to), s.joinedTo, what + ": " + s);
    }

    /** OpenPnP's frame: dots 3 to 9 found at their spots; 4 and 5 joined by a thick neck; the old measure found none. */
    @Test
    public void theStrungDotsOfTheRealFrameAreFoundAtTheirSpots() throws Exception {
        BufferedImage after = load("touchoff2_openpnp_after.jpg");
        BufferedImage before = withoutBand(after, OPENPNP_BAND);
        List<double[]> spots = spots(after, OPENPNP_X, OPENPNP_Y, 9, OPENPNP_SPOT9);
        DotInspector di = new DotInspector(new DotInspectorSettings());
        // what the engine did: the inspected dot nearest each spot within half a pitch; the ladder is one blob,
        // dropped by the filters, so no spot has a dot
        List<SpotDot> old = SpotDot.nearest(di.inspect(before, after, OPENPNP_X, OPENPNP_Y), spots, PITCH / 2);
        for (int j = 3; j < GAPS.length; j++) {
            assertEquals(SpotDot.State.NONE, old.get(j).state, "the old measure, dot " + j);
        }
        List<SpotDot> seen = di.inspectSpots(before, after, OPENPNP_X, OPENPNP_Y, spots, cut(), PITCH / 2);
        for (int j = 0; j < 3; j++) {
            assertEquals(SpotDot.State.UNMEASURED, seen.get(j).state, "dot " + j + ": " + seen.get(j));
            assertEquals("outside the camera frame", seen.get(j).note);
        }
        assertDot(seen.get(3), spots.get(3), 0.85, 1.2, "dot 3 (gap 0.30)");
        assertJoined(seen.get(4), 5, "dot 4 (gap 0.25)");
        assertJoined(seen.get(5), 4, "dot 5 (gap 0.20)");
        for (int j = 6; j < GAPS.length; j++) {
            assertDot(seen.get(j), spots.get(j), 0.85, 1.3, "dot " + j + " (gap " + GAPS[j] + ")");
        }
        // the last dot, at the frame's centre (where the camera stood), is the largest: 1.20 mm
        assertEquals(1.20, seen.get(9).dot.diameterMm, 0.05);
    }

    /** The bench frame: dot 0 is two droplets (the nearer one is its dot), dots 1 and 2 are one teardrop and strand. */
    @Test
    public void theTopOfTheRealLadder() throws Exception {
        BufferedImage after = load("touchoff2_bench290_after.jpg");
        List<double[]> spots = spots(after, BENCH_X, BENCH_Y, 0, BENCH_SPOT0);
        List<SpotDot> seen = new DotInspector(new DotInspectorSettings()).inspectSpots(withoutBand(after, BENCH_BAND),
                after, BENCH_X, BENCH_Y, spots, cut(), PITCH / 2);
        assertDot(seen.get(0), spots.get(0), 0.3, 0.55, "dot 0 (gap 0.45), smaller than the cut");
        assertJoined(seen.get(1), 2, "dot 1 (gap 0.40)");
        assertJoined(seen.get(2), 1, "dot 2 (gap 0.35)");
        assertDot(seen.get(3), spots.get(3), 0.85, 1.2, "dot 3 (gap 0.30)");
    }

    /**
     * The whole ladder (dots 0 to 2 from the bench frame, 3 to 9 from OpenPnP's): no contact between 0.45 and 0.40 mm
     * (what the machine read), but a refusal that names the joined dots.
     */
    @Test
    public void theRealLadderRefusesInsteadOfReadingContactAtTheTop() throws Exception {
        BufferedImage bench = load("touchoff2_bench290_after.jpg");
        BufferedImage openpnp = load("touchoff2_openpnp_after.jpg");
        DotInspector di = new DotInspector(new DotInspectorSettings());
        List<SpotDot> top = di.inspectSpots(withoutBand(bench, BENCH_BAND), bench, BENCH_X, BENCH_Y,
                spots(bench, BENCH_X, BENCH_Y, 0, BENCH_SPOT0), cut(), PITCH / 2);
        List<SpotDot> rest = di.inspectSpots(withoutBand(openpnp, OPENPNP_BAND), openpnp, OPENPNP_X, OPENPNP_Y,
                spots(openpnp, OPENPNP_X, OPENPNP_Y, 9, OPENPNP_SPOT9), cut(), PITCH / 2);
        List<SpotDot> ladder = new ArrayList<>(top.subList(0, 3));
        ladder.addAll(rest.subList(3, GAPS.length));
        for (int j = 0; j < GAPS.length; j++) {
            System.out.println(String.format(java.util.Locale.ROOT, "[touch-off real] gap %.2f: %s", GAPS[j],
                    ladder.get(j)));
        }
        NeedleTouchOff.Analysis a = NeedleTouchOff.analyze(GAPS, ladder, 0.25, DoseModel.dLoMm(DoseModel.HALF));
        assertFalse(a.found(), a.reason);
        assertNull(a.contactGapMm);
        assertEquals("dots touch or are strung together: the paste at gap 0.400 mm is joined to the dot at gap 0.350 mm; "
                + "use a larger pitch", a.reason);
        assertEquals(Arrays.asList("dot", "unmeasured", "unmeasured", "dot", "unmeasured", "unmeasured", "dot", "dot",
                "dot", "dot"), Arrays.asList(a.dotStates));
        assertEquals("joined to the dot at gap 0.250 mm", a.dotNotes[5]);
        // the machine's reading (0.373 mm, then "no dot" x 9) gave contact at +0.425; read again, a single dot
        // before a missing one is no reference
        Double[] machine = new Double[GAPS.length];
        machine[0] = 0.3727585069158047;
        NeedleTouchOff.Analysis again = NeedleTouchOff.analyze(GAPS, machine, 0.25);
        assertNull(again.contactGapMm, again.reason);
        assertTrue(again.reason.startsWith("only the first dot formed before a missing one (0.373 mm at gap 0.450, "
                + "none at gap 0.400)"), again.reason);
    }

    /**
     * The real frame changed where a contact would show (F9 review): dot 7 taken away with the strings to its
     * neighbours (a dot that stopped forming) reads "no paste", with only its strings left "not measured", and dot 8
     * grown 1.4 x about its centre (a squash) is measured that large. Read from the real dots, the first gives the
     * contact at the missing dot, the second at the squashed one.
     */
    @Test
    public void theRealFrameStillShowsAMissingDotAndASquash() throws Exception {
        BufferedImage after = load("touchoff2_openpnp_after.jpg");
        BufferedImage before = withoutBand(after, OPENPNP_BAND);
        List<double[]> spots = spots(after, OPENPNP_X, OPENPNP_Y, 9, OPENPNP_SPOT9);
        DotInspector di = new DotInspector(new DotInspectorSettings());
        double steady = DoseModel.dLoMm(DoseModel.HALF);

        // dot 7 (crop pixel 657, 183) and the strings either side of it: the bare blank of the "before"
        BufferedImage missing = copy(after);
        for (int y = OPENPNP_BAND[2]; y < OPENPNP_BAND[3]; y++) {
            for (int x = 560; x < 745; x++) {
                if (x < 602 || x >= 712 || Math.hypot(x - 657, y - 183) <= 62) {
                    missing.setRGB(x, y, before.getRGB(x, y));
                }
            }
        }
        List<SpotDot> seen = di.inspectSpots(before, missing, OPENPNP_X, OPENPNP_Y, spots, cut(), PITCH / 2);
        assertEquals(SpotDot.State.NONE, seen.get(7).state, "dot 7 gone: " + seen.get(7));
        assertDot(seen.get(6), spots.get(6), 0.85, 1.0, "dot 6 beside it");
        assertDot(seen.get(8), spots.get(8), 0.95, 1.1, "dot 8 beside it");
        double[] g = {GAPS[3], GAPS[6], GAPS[7], GAPS[8]};
        NeedleTouchOff.Analysis stopped = NeedleTouchOff.analyze(g, Arrays.asList(seen.get(3), seen.get(6), seen.get(
                7), seen.get(8)), 0.25, steady);
        assertEquals((GAPS[6] + GAPS[7]) / 2, stopped.contactGapMm, 1e-9, stopped.reason);
        assertTrue(stopped.reason.startsWith("dot stopped forming"), stopped.reason);

        // the same, but a band of its strings left through the spot: paste, no dot (refused, never "no dot")
        BufferedImage strung = copy(after);
        for (int y = OPENPNP_BAND[2]; y < OPENPNP_BAND[3]; y++) {
            for (int x = 595; x < 720; x++) {
                if (Math.abs(y - 181) > 12 && Math.hypot(x - 657, y - 183) <= 62) {
                    strung.setRGB(x, y, before.getRGB(x, y));
                }
            }
        }
        SpotDot s7 = di.inspectSpots(before, strung, OPENPNP_X, OPENPNP_Y, spots, cut(), PITCH / 2).get(7);
        assertEquals(SpotDot.State.UNMEASURED, s7.state, s7.toString());
        assertTrue(s7.note.startsWith("no dot, only strings or a smear"), s7.note);

        // dot 8 (crop pixel 797, 182) grown 1.4 x: 1.02 mm -> about 1.45
        BufferedImage squashed = copy(after);
        double k = 1.4, r = 56;
        for (int y = (int) (182 - r * k); y <= 182 + r * k; y++) {
            for (int x = (int) (797 - r * k); x <= 797 + r * k; x++) {
                if (Math.hypot(x - 797, y - 182) <= r * k) {
                    squashed.setRGB(x, y, after.getRGB((int) Math.round(797 + (x - 797) / k), (int) Math.round(182
                            + (y - 182) / k)));
                }
            }
        }
        seen = di.inspectSpots(before, squashed, OPENPNP_X, OPENPNP_Y, spots, cut(), PITCH / 2);
        assertDot(seen.get(8), spots.get(8), 1.35, 1.6, "dot 8 squashed");
        assertDot(seen.get(7), spots.get(7), 0.95, 1.15, "dot 7 beside it");
        assertDot(seen.get(9), spots.get(9), 1.1, 1.3, "dot 9 beside it");
        NeedleTouchOff.Analysis squash = NeedleTouchOff.analyze(new double[] {GAPS[6], GAPS[7], GAPS[8], GAPS[9]},
                seen.subList(6, 10), 0.25, steady);
        assertEquals((GAPS[7] + GAPS[8]) / 2, squash.contactGapMm, 1e-9, squash.reason);
        assertTrue(squash.reason.startsWith("dot squashed"), squash.reason);
    }

    static BufferedImage copy(BufferedImage a) {
        BufferedImage b = new BufferedImage(a.getWidth(), a.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        b.getGraphics().drawImage(a, 0, 0, null);
        return b;
    }
}
