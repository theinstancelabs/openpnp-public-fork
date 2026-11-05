package org.openpnp.instancelabs.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.opencv.core.Mat;

/**
 * BR-94: the needle's end and N1 from below, in Process's method. On np1.py's real frames (the fixtures' README) the end,
 * its scale and OFF_true come out as np1.py's; on drawn frames the top-hat takes the small bright end over a wide glint
 * and noise, the template follows N1 to a tenth of a pixel; on Process's run p34/t2_px25 K by parallax is
 * parallax_k.py's.
 */
public class EndFromBelowTest {
    static final String DIR = "/org/openpnp/instancelabs/vision/needle-end/";
    /** The crops' origin in the 1920 x 1080 frame; Process's BC (the bottom camera's location's pixel, raw frame). */
    static final int X0 = 657, Y0 = 244;
    static final double BCX = 957.5, BCY = 544.2;
    static final String[] FRAMES = {"np1_0_+0_+0", "np1_1_+1_+0", "np1_2_-1_+0", "np1_3_+0_+1", "np1_4_+0_-1",
        "np1_5_+0_+0"};
    static final double[][] STEPS = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0, 0}};

    static BufferedImage load(String name) throws Exception {
        try (InputStream in = EndFromBelowTest.class.getResourceAsStream(DIR + name + ".png")) {
            assertNotNull(in, name);
            return ImageIO.read(in);
        }
    }

    /**
     * np1.py on its own frames: the first frame searched 220 px around BC, the others 60 px around that spot (kernel 17);
     * the fit of the six against the head steps. The JPEGs differ a little from the live frames np1.py measured (README):
     * the end (956.87, 543.20), M [[15.414, 0.133], [-0.078, 15.632]], residual 0.14 px, drift 0.15 px there.
     */
    @Test
    public void np1sFramesGiveTheEndItsScaleAndWhereItIs() throws Exception {
        EndFromBelow.Spot s0 = EndFromBelow.spot(load(FRAMES[0]), BCX - X0, BCY - Y0, 220, 17);
        double[][] px = new double[FRAMES.length][];
        for (int i = 0; i < FRAMES.length; i++) {
            EndFromBelow.Spot s = EndFromBelow.spot(load(FRAMES[i]), s0.x, s0.y, 60, 17);
            px[i] = new double[] {s.x + X0, s.y + Y0};
            assertTrue(s.peak >= 130 && s.peak <= 150, FRAMES[i] + " peak " + s.peak);
            assertTrue(s.peak >= 15 * s.backgroundSd, FRAMES[i] + ": the peak stands out: " + s.peak + " vs sd "
                    + s.backgroundSd);
        }
        EndFromBelow.StepFit fit = EndFromBelow.fit(STEPS, px);
        assertEquals(956.864, fit.cx, 0.01);
        assertEquals(543.202, fit.cy, 0.01);
        assertEquals(956.87, fit.cx, 0.05, "np1.json");
        assertEquals(543.20, fit.cy, 0.05, "np1.json");
        double[][] m = {{15.412, 0.171}, {-0.072, 15.622}}, live = {{15.414, 0.133}, {-0.078, 15.632}};
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                assertEquals(m[i][j], fit.m[i][j], 0.005, "M[" + i + "][" + j + "]");
                assertEquals(live[i][j], fit.m[i][j], 0.05, "np1.json's M[" + i + "][" + j + "]");
            }
        }
        assertEquals(15.517, fit.pxPerMm(), 0.005);
        assertEquals(0.150, fit.residualPx, 0.005);
        assertEquals(0.149, Math.hypot(px[5][0] - px[0][0], px[5][1] - px[0][1]), 0.005, "drift");
        // OFF_true = OFF0 + M^-1 (spot − BC): np1.json (19.73, -72.248); within 0.07 mm of OFF0, 220 px of BC
        double[] d = fit.offsetFrom(BCX, BCY);
        assertEquals(19.729, 19.77 + d[0], 0.001);
        assertEquals(-72.248, -72.184 + d[1], 0.001);
        assertTrue(Math.hypot(d[0], d[1]) <= 0.08, Math.hypot(d[0], d[1]) + " mm from OFF0");
    }

    /** A grey frame with sensor noise. */
    static BufferedImage frame(int w, int h, int grey, double noise, Random rnd) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = clamp(grey + rnd.nextGaussian() * noise);
                for (int b = 0; b < 3; b++) {
                    img.getRaster().setSample(x, y, b, v);
                }
            }
        }
        return img;
    }

    /** Adds a round Gaussian blob of {@code amp} grey levels at (cx, cy) (sigma px) to every channel. */
    static void blob(BufferedImage img, double cx, double cy, double sigma, double amp) {
        int r = (int) Math.ceil(4 * sigma);
        for (int y = Math.max(0, (int) cy - r); y <= Math.min(img.getHeight() - 1, (int) cy + r); y++) {
            for (int x = Math.max(0, (int) cx - r); x <= Math.min(img.getWidth() - 1, (int) cx + r); x++) {
                double v = amp * Math.exp(-((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (2 * sigma * sigma));
                add(img, x, y, v);
            }
        }
    }

    static void add(BufferedImage img, int x, int y, double v) {
        for (int b = 0; b < 3; b++) {
            img.getRaster().setSample(x, y, b, clamp(img.getRaster().getSample(x, y, 0) + v));
        }
    }

    static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /**
     * The end's exit highlight is a small bright spot; the hub beside it glints wider and brighter: the top-hat (17 px)
     * keeps the spot and flattens the glint, so the end is found to a few hundredths of a pixel in the noise.
     */
    @Test
    public void theTopHatTakesTheSmallBrightEndOverAWideGlint() {
        Random rnd = new Random(7);
        BufferedImage img = frame(400, 400, 60, 3, rnd);
        blob(img, 280, 250, 12, 150); // the hub's glint: some 60 px across, 210 grey at its middle
        blob(img, 210.3, 190.7, 1.6, 120);
        EndFromBelow.Spot s = EndFromBelow.spot(img, 200, 200, 150, 17);
        assertEquals(210.3, s.x, 0.1);
        assertEquals(190.7, s.y, 0.1);
        assertTrue(s.peak >= 3 * s.backgroundSd, s.peak + " vs " + s.backgroundSd);
        // no end in the frame: the brightest noise is some 6 times its window's sd (np1's real ends 16-45 times)
        EndFromBelow.Spot none = EndFromBelow.spot(frame(400, 400, 60, 3, rnd), 200, 200, 150, 17);
        assertTrue(none.peak < 10 * none.backgroundSd, none.peak + " vs " + none.backgroundSd);
    }

    /** N1's end: a ring and a speckle that move with it, drawn at sub-pixel places. */
    static BufferedImage n1(double cx, double cy, Random noise) {
        BufferedImage img = frame(400, 400, 50, 2, noise);
        Random tex = new Random(11);
        for (int i = 0; i < 40; i++) {
            double a = tex.nextDouble() * 2 * Math.PI, r = tex.nextDouble() * 7;
            blob(img, cx + r * Math.cos(a), cy + r * Math.sin(a), 1.2, 40 + tex.nextDouble() * 60);
        }
        for (int i = 0; i < 36; i++) {
            double a = i * Math.PI / 18;
            blob(img, cx + 8 * Math.cos(a), cy + 8 * Math.sin(a), 1.0, 70);
        }
        return img;
    }

    /** parallax_fit.py: a high-pass template of N1 (half 8) is followed to a tenth of a pixel in the noise. */
    @Test
    public void theTemplateFollowsN1ToATenthOfAPixel() {
        Random rnd = new Random(3);
        Mat t = EndFromBelow.template(n1(200, 200, rnd), 200, 200, 8);
        try {
            for (double[] to : new double[][] {{237.3, 187.4}, {162.6, 214.8}, {200.45, 199.55}}) {
                EndFromBelow.Track k = EndFromBelow.track(n1(to[0], to[1], rnd), t, 200, 200, 8, 140);
                assertEquals(to[0], k.x, 0.1, "x");
                assertEquals(to[1], k.y, 0.1, "y");
                assertTrue(k.score > 0.8, "score " + k.score);
            }
            EndFromBelow.Track out = EndFromBelow.track(n1(200, 200, rnd), t, 50, 50, 8, 140);
            assertTrue(Double.isNaN(out.x), "a window outside the frame finds nothing");
        }
        finally {
            t.release();
        }
    }

    /**
     * parallax_k.py on Process's 25G run (p34/t2_px25: N1 at raw 19/22/25, the needle at 36/39/42, d 5): K 65.129,
     * spread 0.087, f 673.5 px (its k_result.json).
     */
    @Test
    public void kByParallaxIsParallaxKsOnProcesssRun() {
        EndFromBelow.Parallax p = EndFromBelow.parallaxK(new double[] {19, 22, 25}, new double[] {17.9148, 16.6096,
            15.4493}, new double[] {36, 39, 42}, new double[] {14.087065703958002, 15.042154279537822,
                16.142743877556});
        assertEquals(65.129, p.k, 0.0005);
        assertEquals(0.087, p.spread, 0.0005);
        assertEquals(673.5, p.fPx, 0.05);
        assertEquals(1.0148, p.needleMmPerRawMm, 0.00005, "needle_mm_per_rawz");
        // a see-saw drawn exactly: K wherever N1 is, no spread
        double f = 673.5, c = -18.6, k = 65.0;
        double[] n1z = {19, 25}, ndz = {36, 42}, n1k = new double[2], ndk = new double[2];
        for (int i = 0; i < 2; i++) {
            n1k[i] = f / (n1z[i] - c);
            ndk[i] = f / (k - c - ndz[i]);
        }
        EndFromBelow.Parallax exact = EndFromBelow.parallaxK(n1z, n1k, ndz, ndk);
        assertEquals(65.0, exact.k, 1e-9);
        assertEquals(0, exact.spread, 1e-9);
        assertEquals(1.0, exact.needleMmPerRawMm, 1e-9, "a true see-saw");
    }

    /** A straight line through points and the fit of a pixel against the step recover what drew them. */
    @Test
    public void theFitsRecoverTheirLines() {
        double[] l = EndFromBelow.line(new double[] {36, 39, 42}, new double[] {19.70, 19.757, 19.814});
        assertEquals(0.019, l[0], 1e-9);
        assertTrue(Double.isNaN(EndFromBelow.line(new double[] {40, 40}, new double[] {1, 2})[0]), "one height");
        double[][] px = new double[STEPS.length][];
        for (int i = 0; i < STEPS.length; i++) {
            px[i] = new double[] {100 + 15 * STEPS[i][0] + 0.2 * STEPS[i][1], 50 - 0.1 * STEPS[i][0] - 15.5
                    * STEPS[i][1]};
        }
        EndFromBelow.StepFit fit = EndFromBelow.fit(STEPS, px);
        assertEquals(15, fit.m[0][0], 1e-9);
        assertEquals(-15.5, fit.m[1][1], 1e-9);
        assertEquals(0, fit.residualPx, 1e-9);
        double[] at = fit.offsetFrom(100 - 15, 50); // the camera's pixel 15 px left: the tip 1 mm along +X of it
        assertEquals(1.0, at[0], 1e-3);
    }
}
