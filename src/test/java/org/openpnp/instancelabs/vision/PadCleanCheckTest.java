package org.openpnp.instancelabs.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.vision.PadCleanCheck.Result;
import org.openpnp.instancelabs.vision.PadCleanCheck.Settings;
import org.openpnp.instancelabs.vision.Synth.Shot;

/**
 * Bare-pad check on synthetic Lumen frames and on hand-painted images. Every scene test prints its
 * bright fraction (prefix "[pad-clean]") so the numbers can be quoted.
 */
public class PadCleanCheckTest {
    /** Bench top camera (lumen.py SX, SY), a near-square calibration, and a coarse camera. */
    static final double[][] SCALES = {{0.01074, 0.01106}, {0.01068, 0.01073}, {0.02, 0.02}};
    static final double BENCH_X = SCALES[0][0], BENCH_Y = SCALES[0][1];
    /** Bench pads are ~1.0 mm square. */
    static final double PAD = 1.0;

    /**
     * B, G, R -> grey from OpenCV 5.0.0 cv2.cvtColor(COLOR_BGR2GRAY) in the bench venv (the
     * integer formula matched cv2 on all 2^24 colours). Includes the Lumen colours, the 200/201
     * threshold edge and exact .5 rounding ties.
     */
    static final int[][] CV_GRAY = {
            {0, 0, 0, 0}, {255, 255, 255, 255}, {255, 0, 0, 29}, {0, 255, 0, 150},
            {0, 0, 255, 76}, {160, 63, 13, 59}, {208, 45, 0, 50}, {246, 244, 222, 238},
            {222, 244, 246, 242}, {200, 200, 200, 200}, {201, 201, 201, 201}, {199, 201, 200, 200},
            {232, 192, 252, 215}, {182, 174, 186, 179}, {115, 216, 201, 200}, {47, 205, 250, 200},
            {218, 255, 88, 201}, {82, 234, 181, 201}, {11, 31, 98, 49}, {245, 93, 107, 115},
            {158, 82, 36, 77}, {9, 66, 162, 88}};

    @Test
    public void grayMatchesOpenCv() {
        for (int[] v : CV_GRAY) {
            int rgb = 0xff000000 | (v[2] << 16) | (v[1] << 8) | v[0];
            assertEquals(v[3], PadCleanCheck.gray(rgb), "BGR " + v[0] + "," + v[1] + "," + v[2]);
        }
    }

    /** A frame centred on a pad at (padX, padY) mm, optionally with a paste disk. */
    static Shot lumen(double mmX, double mmY, double padX, double padY, double pasteD) {
        Shot s = new Shot();
        s.width = 320;
        s.height = 240;
        s.mmPerPx = mmX;
        s.mmPerPxY = mmY;
        s.scene = Synth.lumenPad(padX, padY, PAD, PAD);
        s.pasteRgb = Synth.LUMEN_PASTE;
        s.noiseSeed = 7;
        if (pasteD > 0) {
            s.deposits.add(Synth.disk(0, 0, pasteD));
        }
        return s;
    }

    static Result measure(String label, Shot s) {
        double mmY = s.mmPerPxY > 0 ? s.mmPerPxY : s.mmPerPx;
        Result r = PadCleanCheck.measure(Synth.render(s), s.mmPerPx, mmY, 0, 0, new Settings());
        System.out.println(String.format(Locale.ROOT,
                "[pad-clean] %-34s scale=%.5f,%.5f box=%dx%dpx (%.4f x %.4f mm) bright=%.4f %s",
                label, s.mmPerPx, mmY, r.boxWPx, r.boxHPx, r.boxWPx * s.mmPerPx,
                r.boxHPx * mmY, r.brightFraction, r.clean ? "clean" : "dirty"));
        return r;
    }

    @Test
    public void cleanCentredPadPasses() {
        Result r = measure("clean centred", lumen(BENCH_X, BENCH_Y, 0, 0, 0));
        assertTrue(r.brightFraction >= 0.95, r.toString());
        assertTrue(r.clean);
    }

    @Test
    public void landingShiftOf50umPasses() {
        double d = 0.05;
        double c = d / Math.sqrt(2);
        double[][] shifts = {{d, 0}, {-d, 0}, {0, d}, {0, -d}, {c, c}, {-c, -c}};
        for (double[] sh : shifts) {
            Result r = measure(String.format(Locale.ROOT, "pad shifted %.3f,%.3f", sh[0], sh[1]),
                    lumen(BENCH_X, BENCH_Y, sh[0], sh[1], 0));
            assertTrue(r.brightFraction >= 0.9, r.toString());
            assertTrue(r.clean);
        }
    }

    @Test
    public void leftoverPasteFails() {
        Result big = measure("paste d=0.50", lumen(BENCH_X, BENCH_Y, 0, 0, 0.5));
        // Disk area over box area: 1 - pi 0.25^2 / (0.806 * 0.807) = 0.698.
        assertEquals(0.70, big.brightFraction, 0.05, big.toString());
        assertFalse(big.clean);
        Result small = measure("paste d=0.30", lumen(BENCH_X, BENCH_Y, 0, 0, 0.3));
        assertTrue(small.clean, small.toString());
    }

    @Test
    public void solderMaskOnlyFails() {
        Result r = measure("solder mask only", lumen(BENCH_X, BENCH_Y, 3, 3, 0));
        assertTrue(r.brightFraction < 0.02, r.toString());
        assertFalse(r.clean);
    }

    @Test
    public void sameVerdictAcrossScales() {
        double[] pastes = {0, 0.3, 0.5, -1};
        boolean[] clean = {true, true, false, false};
        for (double[] sc : SCALES) {
            for (int i = 0; i < pastes.length; i++) {
                Shot s = pastes[i] < 0 ? lumen(sc[0], sc[1], 3, 3, 0)
                        : lumen(sc[0], sc[1], 0, 0, pastes[i]);
                Result r = measure(pastes[i] < 0 ? "mask only" : "paste d=" + pastes[i], s);
                assertEquals(clean[i], r.clean, r.toString());
                assertEquals(0.806, r.boxWPx * sc[0], sc[0] / 2);
                assertEquals(0.807, r.boxHPx * sc[1], sc[1] / 2);
            }
        }
    }

    /** 1920x1080 BGR image, black except for the bench box [504:577, 923:998] painted white. */
    static BufferedImage benchBoxImage() {
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 504; y < 577; y++) {
            for (int x = 923; x < 998; x++) {
                img.setRGB(x, y, 0xffffff);
            }
        }
        return img;
    }

    @Test
    public void benchBoxGeometry() {
        BufferedImage img = benchBoxImage();
        Result r = PadCleanCheck.measure(img, BENCH_X, BENCH_Y, 0, 0, null);
        assertEquals(923, r.x0);
        assertEquals(504, r.y0);
        assertEquals(75, r.boxWPx);
        assertEquals(73, r.boxHPx);
        assertEquals(75 * 73, r.totalPx);
        assertEquals(r.totalPx, r.brightPx);
        // The corner pixels are inside the box.
        img.setRGB(923, 504, 0);
        img.setRGB(997, 576, 0);
        assertEquals(r.totalPx - 2,
                PadCleanCheck.measure(img, BENCH_X, BENCH_Y, 0, 0, null).brightPx);
        // +x right, +y up: 10 px right and 10 px up.
        Result o = PadCleanCheck.measure(img, BENCH_X, BENCH_Y, 10 * BENCH_X, 10 * BENCH_Y, null);
        assertEquals(933, o.x0);
        assertEquals(494, o.y0);
    }

    @Test
    public void exactlyMinFractionIsClean() {
        // 4380 / 5475 == 0.8: the bench rule calls a pad dirty only below 0.8.
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_3BYTE_BGR);
        int n = 0;
        for (int y = 504; y < 577 && n < 4380; y++) {
            for (int x = 923; x < 998 && n < 4380; x++, n++) {
                img.setRGB(x, y, (201 << 16) | (201 << 8) | 201);
            }
        }
        Result r = PadCleanCheck.measure(img, BENCH_X, BENCH_Y, 0, 0, null);
        assertEquals(4380, r.brightPx);
        assertEquals(0.8, r.brightFraction, 0);
        assertTrue(r.clean);
        img.setRGB(923, 504, (200 << 16) | (200 << 8) | 200);
        assertFalse(PadCleanCheck.measure(img, BENCH_X, BENCH_Y, 0, 0, null).clean);
    }

    @Test
    public void grayImagesReadTheRaster() {
        BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster r = img.getRaster();
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                r.setSample(x, y, 0, x < 20 ? 200 : 201);
            }
        }
        Settings s = new Settings();
        s.boxWMm = 0.2;
        s.boxHMm = 0.2;
        Result res = PadCleanCheck.measure(img, 0.01, 0.01, 0, 0, s);
        assertEquals(10, res.x0);
        assertEquals(0.5, res.brightFraction, 0);
    }

    @Test
    public void rejectsBadInput() {
        BufferedImage img = benchBoxImage();
        assertThrows(IllegalArgumentException.class,
                () -> PadCleanCheck.measure(null, BENCH_X, BENCH_Y, 0, 0, null));
        // Box partly outside the frame (left edge, top edge) and larger than the frame.
        assertThrows(IllegalArgumentException.class,
                () -> PadCleanCheck.measure(img, BENCH_X, BENCH_Y, -10.0, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> PadCleanCheck.measure(img, BENCH_X, BENCH_Y, 0, 5.6, null));
        assertThrows(IllegalArgumentException.class,
                () -> PadCleanCheck.measure(img, BENCH_X, BENCH_Y, 1e300, 0, null));
        Settings huge = new Settings();
        huge.boxWMm = 30;
        assertThrows(IllegalArgumentException.class,
                () -> PadCleanCheck.measure(img, BENCH_X, BENCH_Y, 0, 0, huge));
        // The box touching the frame edge is still inside.
        assertEquals(0, PadCleanCheck.measure(img, BENCH_X, BENCH_Y, -923 * BENCH_X, 0, null).x0);
        assertThrows(IllegalArgumentException.class,
                () -> PadCleanCheck.measure(img, BENCH_X, BENCH_Y, -924 * BENCH_X, 0, null));

        assertThrows(IllegalArgumentException.class,
                () -> PadCleanCheck.measure(img, 0, BENCH_Y, 0, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> PadCleanCheck.measure(img, BENCH_X, Double.NaN, 0, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> PadCleanCheck.measure(img, BENCH_X, BENCH_Y, Double.NaN, 0, null));

        Settings[] bad = new Settings[8];
        for (int i = 0; i < bad.length; i++) {
            bad[i] = new Settings();
        }
        bad[0].grayLevel = -1;
        bad[1].grayLevel = 255;
        bad[2].minBrightFraction = -0.01;
        bad[3].minBrightFraction = 1.01;
        bad[4].minBrightFraction = Double.NaN;
        bad[5].boxWMm = 0;
        bad[6].boxHMm = -1;
        bad[7].boxWMm = Double.POSITIVE_INFINITY;
        for (Settings s : bad) {
            assertThrows(IllegalArgumentException.class,
                    () -> PadCleanCheck.measure(img, BENCH_X, BENCH_Y, 0, 0, s));
        }
        Settings edge = new Settings();
        edge.grayLevel = 254;
        edge.minBrightFraction = 1;
        assertTrue(PadCleanCheck.measure(img, BENCH_X, BENCH_Y, 0, 0, edge).clean);
        edge.grayLevel = 0;
        edge.minBrightFraction = 0;
        edge.validate();
    }

    @Test
    public void settingsCopyIsIndependent() {
        Settings a = new Settings();
        a.grayLevel = 180;
        a.minBrightFraction = 0.7;
        a.boxWMm = 0.5;
        a.boxHMm = 0.6;
        Settings b = a.copy();
        a.grayLevel = 1;
        a.boxHMm = 2;
        assertEquals(180, b.grayLevel);
        assertEquals(0.7, b.minBrightFraction, 0);
        assertEquals(0.5, b.boxWMm, 0);
        assertEquals(0.6, b.boxHMm, 0);
    }
}
