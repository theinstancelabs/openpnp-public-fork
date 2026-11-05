package org.openpnp.instancelabs.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * BR-94: Process's tipclean.py --selftest, ported, on its real frames (the fixtures' README): each verdict, peak, spread
 * and red as tipclean.py reads them on the full frames; its two code paths (a dimmed frame, a travel-height k); the
 * synthetic paste blob; and for each of the five checks a moved and a dropped limit that change a real verdict.
 */
public class TipCleanTest {
    static final String DIR = "/org/openpnp/instancelabs/vision/tip-clean/";
    static final int X0 = 877, Y0 = 464;
    static final double BCX = 957.5, BCY = 544.2;
    /** tipclean.py's k: np1's 15.52 at raw 40.4; elsewhere 1 / (0.06649 − 0.001503 (Z − 39)). */
    static final double K = 15.52, Z36 = 14.0847, Z39 = 15.0399, Z42 = 16.134, Z31_5 = 12.8597;

    /** One frame: its k and what tipclean.py says on the full frame (the failed check, peak, spread, red). */
    static final class Case {
        final String name, failed;
        final double k, peak, spread, red;

        Case(String name, double k, String failed, double peak, double spread, double red) {
            this.name = name;
            this.k = k;
            this.failed = failed;
            this.peak = peak;
            this.spread = spread;
            this.red = red;
        }
    }

    static final Case[] CASES = {new Case("np1_0", K, null, 140, 0.160, 0.652), new Case("np1_1", K, null, 149, 0.162,
        0.655), new Case("np1_2", K, null, 131, 0.176, 0.659), new Case("np1_3", K, null, 132, 0.226, 0.647),
        new Case("np1_4", K, null, 136, 0.252, 0.611), new Case("np1_5", K, null, 143, 0.189, 0.669),
        new Case("np1after_0", K, null, 126, 0.180, 0.636), new Case("np1after_1", K, null, 142, 0.181, 0.705),
        new Case("np1after_2", K, null, 139, 0.184, 0.679), new Case("np1after_3", K, null, 138, 0.208, 0.663),
        new Case("np1after_4", K, null, 148, 0.230, 0.627), new Case("np1after_5", K, null, 134, 0.186, 0.664),
        new Case("t1_25g_from_below", Z39, null, 104, 0.241, 1.002), new Case("t4_25g_after_s4", Z39, null, 101, 0.247,
            0.967), new Case("t6_25g_after_140", Z39, null, 98, 0.241, 0.992), new Case("t7_below_1", Z39, null, 110,
                0.217, 0.943), new Case("t7_below_2", Z39, null, 121, 0.211, 0.766), new Case("px25_nd36", Z36, null,
                    100, 0.259, 0.970), new Case("px25_nd39", Z39, null, 107, 0.252, 1.004), new Case("px25_nd42", Z42,
                        null, 107, 0.240, 1.017), new Case("p38_s1", 15.477, null, 141, 0.175, 0.665),
        new Case("p38_s2", 15.458, null, 138, 0.177, 0.677), new Case("p38_s3", 15.446, null, 143, 0.182, 0.655),
        // dirty: a just-filled tip after primes (p34:49); each of spread and red refuses it on its own
        new Case("t7_below_3", Z39, "spread", 131, 0.475, 0.341), new Case("t7_below_4", Z39, "spread", 115, 0.652,
            0.135), new Case("t7_below_5", Z39, "spread", 121, 0.478, 0.233),
        // the needle at travel height (no highlight), and N1 over the camera
        new Case("a1_tip_z31.5", Z31_5, "found", 4, 1.511, 0.0), new Case("b0_nd27pose_31.5", Z31_5, "found", 24,
            0.908, 0.0), new Case("n1_from_below", K, "spread", 81, 0.696, 0.333)};

    static BufferedImage png(String name) throws Exception {
        try (InputStream in = TipCleanTest.class.getResourceAsStream(DIR + name + ".png")) {
            assertNotNull(in, name);
            return ImageIO.read(in);
        }
    }

    /** The 1920 x 1080 frame rebuilt from a case's end crop and rim blocks (black elsewhere). */
    static BufferedImage frame(String name) throws Exception {
        BufferedImage f = new BufferedImage(1920, 1080, BufferedImage.TYPE_3BYTE_BGR);
        BufferedImage end = png(name), rim = png(name + "_rim");
        for (int y = 0; y < end.getHeight(); y++) {
            for (int x = 0; x < end.getWidth(); x++) {
                f.setRGB(X0 + x, Y0 + y, end.getRGB(x, y));
            }
        }
        for (int y = 0; y < 400; y++) {
            for (int x = 0; x < 140; x++) {
                f.setRGB(x, 340 + y, rim.getRGB(x / 10, y / 10));
                f.setRGB(1780 + x, 340 + y, rim.getRGB(14 + x / 10, y / 10));
            }
        }
        return f;
    }

    static TipClean.Result check(BufferedImage f, double k, TipClean.Limits l) {
        return TipClean.check(f, k, BCX, BCY, 17, l);
    }

    /** tipclean.py --selftest's verdicts, with S1a/S2/S3: 23 clean frames pass, 6 are refused by the right check. */
    @Test
    public void processsFramesReadAsTipcleanPyReadsThem() throws Exception {
        for (Case c : CASES) {
            TipClean.Result r = check(frame(c.name), c.k, new TipClean.Limits());
            String m = c.name + ": " + r.why;
            assertEquals(c.failed, r.failed, m);
            assertEquals(c.failed == null, r.ok(), m);
            assertEquals(c.peak, r.peak, 0.5, m);
            assertEquals(c.spread, r.spread, 0.002, m);
            assertEquals(c.red, r.red, 0.002, m);
            assertTrue(r.rim > 100 && r.rimRg > 0.26 && r.rimRg < 0.34, m + " rim " + r.rim + " " + r.rimRg);
        }
        // the dirty ones each fail spread and red on their own (both independent tests refuse them)
        for (String dirty : new String[] {"t7_below_3", "t7_below_4", "t7_below_5"}) {
            TipClean.Result r = check(frame(dirty), Z39, new TipClean.Limits());
            assertTrue(r.spread > 0.35 && r.red < 0.45, dirty);
        }
    }

    /** tipclean.py's code paths: a frame dimmed to 30 % (the ring too dark) is light; a travel-height k is z. */
    @Test
    public void aDimFrameIsLightAndATravelHeightScaleIsZ() throws Exception {
        BufferedImage dim = frame("np1_0");
        for (int y = 0; y < dim.getHeight(); y++) {
            for (int x = 0; x < dim.getWidth(); x++) {
                int c = dim.getRGB(x, y);
                int r = (int) (((c >> 16) & 0xff) * 0.3), g = (int) (((c >> 8) & 0xff) * 0.3), b = (int) ((c & 0xff)
                        * 0.3);
                dim.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        TipClean.Result light = check(dim, K, new TipClean.Limits());
        assertEquals("light", light.failed, light.why);
        assertTrue(light.why.startsWith("light: the ring is off or not white"), light.why);
        TipClean.Result z = check(frame("np1_0"), Z31_5, new TipClean.Limits());
        assertEquals("z", z.failed, z.why);
        TipClean.Result small = TipClean.check(new BufferedImage(1280, 960, BufferedImage.TYPE_3BYTE_BGR), K, 640,
                480, 17, new TipClean.Limits());
        assertEquals("light", small.failed, "another frame size has no rim to read: " + small.why);
    }

    /**
     * Paste as a grey blob 0.65 mm across beside the end (centred 0.65 mm off it, grey 200): the highlight is still
     * found, its red still reads clean, but the annulus lights up (spread 0.82): refused by spread.
     */
    @Test
    public void aGreyBlobBesideTheEndIsRefusedBySpread() throws Exception {
        BufferedImage f = frame("np1_0");
        TipClean.Result clean = check(f, K, new TipClean.Limits());
        double cx = Math.round(clean.endX + 0.65 * K), cy = Math.round(clean.endY), r = Math.round(0.325 * K);
        for (int y = (int) (cy - r); y <= cy + r; y++) {
            for (int x = (int) (cx - r); x <= cx + r; x++) {
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) {
                    f.setRGB(x, y, 0xc8c8c8);
                }
            }
        }
        TipClean.Result blob = check(f, K, new TipClean.Limits());
        assertEquals("spread", blob.failed, blob.why);
        assertEquals(0.82, blob.spread, 0.01);
        assertTrue(blob.red >= 0.45 && blob.peak >= 60, "only spread refuses it: " + blob.red + " " + blob.peak);
    }

    /** A copy of the 25G's limits with one changed. */
    static TipClean.Limits limits(Consumer<TipClean.Limits> change) {
        TipClean.Limits l = new TipClean.Limits();
        change.accept(l);
        return l;
    }

    static String failed(String name, double k, TipClean.Limits l) throws Exception {
        return check(frame(name), k, l).failed;
    }

    /**
     * Each check bears its weight: moved past a real frame on record its verdict changes, and dropped (a limit nothing
     * reaches) a frame only it refused gets through it. Process's margins: spread 0.259 clean vs 0.475 dirty, red 0.611
     * vs 0.341.
     */
    @Test
    public void everyCheckChangesARealVerdictWhenItsLimitMovesOrGoes() throws Exception {
        // light: the white ring reads grey 103, R/G 0.32
        assertEquals("light", failed("np1_0", K, limits(l -> l.rimMin = 104)));
        assertEquals("light", failed("np1_0", K, limits(l -> l.rimRgHigh = 0.31)));
        assertEquals("light", failed("np1_0", K, limits(l -> l.rimRgLow = 0.33)));
        BufferedImage dim = frame("np1_0");
        for (int y = 340; y < 740; y++) {
            for (int x = 0; x < 1920; x = x == 139 ? 1780 : x + 1) {
                dim.setRGB(x, y, dim.getRGB(x, y) >> 2 & 0x3f3f3f);
            }
        }
        assertEquals("light", check(dim, K, new TipClean.Limits()).failed);
        assertNull(check(dim, K, limits(l -> l.rimMin = 0)).failed, "dropped: the dim rim gets through");
        // found: the travel-height needle's peak 24
        assertEquals("found", failed("b0_nd27pose_31.5", Z31_5, new TipClean.Limits()));
        assertEquals("found", failed("px25_nd39", Z39, limits(l -> l.peakMin = 108)), "moved past 107");
        assertFalse("found".equals(failed("b0_nd27pose_31.5", Z31_5, limits(l -> l.peakMin = 0))), "dropped");
        // z: the travel-height scale
        assertEquals("z", failed("px25_nd42", Z42, limits(l -> l.kHigh = 16.1)), "moved under 16.13");
        assertEquals("z", failed("px25_nd36", Z36, limits(l -> l.kLow = 14.1)), "moved over 14.08");
        assertNull(failed("np1_0", Z31_5, limits(l -> l.kLow = 0)), "dropped: np1_0 at a travel-height k passes");
        // spread: the dirty frames' best 0.475, the clean worst 0.259
        assertEquals("spread", failed("px25_nd36", Z36, limits(l -> l.spreadMax = 0.25)), "moved under 0.259");
        assertEquals("red", failed("t7_below_3", Z39, limits(l -> l.spreadMax = 0.48)), "moved over 0.475: red "
                + "still refuses it");
        assertEquals("red", failed("t7_below_4", Z39, limits(l -> l.spreadMax = 1e9)), "dropped: red refuses it");
        assertNull(failed("n1_from_below", K, limits(l -> {
            l.spreadMax = 1e9;
            l.redMin = 0;
        })), "dropped with red: N1's end gets through");
        // red: the clean worst 0.611, the dirty best 0.341
        assertEquals("red", failed("np1_4", K, limits(l -> l.redMin = 0.62)), "moved over 0.611");
        assertNull(failed("t7_below_3", Z39, limits(l -> {
            l.spreadMax = 0.48;
            l.redMin = 0.34;
        })), "moved under 0.341 with spread over 0.475: the dirty frame passes");
        assertEquals("spread", failed("t7_below_3", Z39, limits(l -> l.redMin = 0)), "dropped: spread refuses it");
    }
}
