package org.openpnp.instancelabs.vision;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Synthetic camera images for the dot-vision tests. A scene is a radiance function in mm (image
 * centre origin, +x right, +y up), rendered with 3x3 supersampling (exact anti-aliased edges),
 * optical blur, exposure gain/offset/gradient, Gaussian sensor noise and 8-bit clipping.
 */
final class Synth {
    static {
        nu.pattern.OpenCV.loadLocally();
    }

    private Synth() {}

    enum Background {
        /** White paper towel: bright, fibrous low-contrast texture. */
        PAPER,
        /** Bare copper: orange, brushed, with saturated specular sparkles. */
        COPPER,
        /** Green FR4 solder mask with glass weave and a grid of 0603 pads (HASL / ENIG). */
        FR4
    }

    /** FR4 pad grid: centres at x = PAD_X0 + PAD_PITCH_X*m, y = PAD_Y0 + PAD_PITCH_Y*n. */
    static final double PAD_X0 = 1.4, PAD_PITCH_X = 2.8, PAD_Y0 = 1.5, PAD_PITCH_Y = 3.0;
    static final double PAD_W = 0.8, PAD_H = 0.95;

    /** Background radiance (RGB) at (x, y) mm; set on a {@link Shot} it replaces the Background. */
    interface Scene {
        void radiance(double x, double y, double[] out);
    }

    /**
     * Lumen board under the top camera's ring light, RGB as read on bench frames: blue solder
     * mask (BGR ~208,45,0), bare copper pad (BGR ~246,244,222), solder paste (BGR ~160,63,13).
     */
    static final double[] LUMEN_MASK = {0, 45, 208}, LUMEN_COPPER = {222, 244, 246},
            LUMEN_PASTE = {13, 63, 160};

    /**
     * Lumen work board: solder mask with one bare-copper pad of w x h mm (0.15 mm corner radius)
     * centred at (cx, cy). Bench pads are ~1.0 mm square.
     */
    static Scene lumenPad(double cx, double cy, double w, double h) {
        double r = 0.15;
        return (x, y, out) -> {
            double qx = Math.max(0, Math.abs(x - cx) - (w / 2 - r));
            double qy = Math.max(0, Math.abs(y - cy) - (h / 2 - r));
            if (qx * qx + qy * qy <= r * r) {
                double f = 0.97 + 0.06 * value(x / 0.05, y / 0.05, 13);
                for (int c = 0; c < 3; c++) {
                    out[c] = LUMEN_COPPER[c] * f;
                }
                return;
            }
            double tex = 8 * (fbm(x / 0.2, y / 0.2, 12, 2) - 0.5) * 2;
            for (int c = 0; c < 3; c++) {
                out[c] = LUMEN_MASK[c] + tex;
            }
        };
    }

    /** Paste deposit shape. */
    interface Deposit {
        boolean contains(double x, double y);

        /** Normalized distance from the centre, 0 centre .. 1 edge (drives rim shading). */
        double rho(double x, double y);

        /** Extra brightness from a specular highlight. */
        double highlight(double x, double y);
    }

    static Deposit disk(double cx, double cy, double diameter) {
        double r = diameter / 2;
        return new Deposit() {
            public boolean contains(double x, double y) {
                double dx = x - cx;
                double dy = y - cy;
                return dx * dx + dy * dy <= r * r;
            }

            public double rho(double x, double y) {
                return Math.hypot(x - cx, y - cy) / r;
            }

            public double highlight(double x, double y) {
                double dx = x - (cx - 0.2 * r);
                double dy = y - (cy + 0.2 * r);
                double s = 0.18 * r;
                return 60 * Math.exp(-(dx * dx + dy * dy) / (s * s));
            }
        };
    }

    static Deposit rect(double cx, double cy, double w, double h, double rotDeg) {
        double c = Math.cos(Math.toRadians(rotDeg));
        double s = Math.sin(Math.toRadians(rotDeg));
        return new Deposit() {
            public boolean contains(double x, double y) {
                return rho(x, y) <= 1;
            }

            public double rho(double x, double y) {
                double dx = x - cx;
                double dy = y - cy;
                double u = dx * c + dy * s;
                double v = -dx * s + dy * c;
                return Math.max(Math.abs(u) / (w / 2), Math.abs(v) / (h / 2));
            }

            public double highlight(double x, double y) {
                return 0;
            }
        };
    }

    /** One exposure. */
    static final class Shot {
        int width = 640, height = 480;
        double mmPerPx = 0.035;
        /** Vertical scale, mm/px; 0 means square pixels (mmPerPx). */
        double mmPerPxY = 0;
        Background background = Background.PAPER;
        /** Replaces the background when set. */
        Scene scene;
        /** Paste colour (RGB, shaded like the grey paste); null keeps the grey paste. */
        double[] pasteRgb;
        /** Rotation of the FR4 board (pads) in degrees CCW. */
        double boardRotDeg = 0;
        final List<Deposit> deposits = new ArrayList<>();
        double gain = 1, offset = 0;
        /** Additive brightness at the right / bottom image edge (linear ramp from the centre). */
        double gradX = 0, gradY = 0;
        /** Scene content moves by this many pixels (camera re-positioning error). */
        double shiftXPx = 0, shiftYPx = 0;
        double noiseSigma = 2.5;
        double opticsSigmaPx = 0.6;
        long noiseSeed = 1;
        boolean gray = false;

        Shot copy() {
            Shot s = new Shot();
            s.width = width;
            s.height = height;
            s.mmPerPx = mmPerPx;
            s.mmPerPxY = mmPerPxY;
            s.background = background;
            s.scene = scene;
            s.pasteRgb = pasteRgb;
            s.boardRotDeg = boardRotDeg;
            s.deposits.addAll(deposits);
            s.gain = gain;
            s.offset = offset;
            s.gradX = gradX;
            s.gradY = gradY;
            s.shiftXPx = shiftXPx;
            s.shiftYPx = shiftYPx;
            s.noiseSigma = noiseSigma;
            s.opticsSigmaPx = opticsSigmaPx;
            s.noiseSeed = noiseSeed;
            s.gray = gray;
            return s;
        }
    }

    static BufferedImage render(Shot s) {
        int w = s.width;
        int h = s.height;
        final int ss = 3;
        double mmPerPxY = s.mmPerPxY > 0 ? s.mmPerPxY : s.mmPerPx;
        float[][] img = new float[3][w * h];
        double[] rgb = new double[3];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                double r = 0, g = 0, b = 0;
                for (int sv = 0; sv < ss; sv++) {
                    double py = j + (sv + 0.5) / ss - 0.5 - s.shiftYPx;
                    double y = -(py - h / 2.0) * mmPerPxY;
                    for (int su = 0; su < ss; su++) {
                        double px = i + (su + 0.5) / ss - 0.5 - s.shiftXPx;
                        double x = (px - w / 2.0) * s.mmPerPx;
                        radiance(s, x, y, rgb);
                        r += rgb[0];
                        g += rgb[1];
                        b += rgb[2];
                    }
                }
                int k = j * w + i;
                img[0][k] = (float) (r / (ss * ss));
                img[1][k] = (float) (g / (ss * ss));
                img[2][k] = (float) (b / (ss * ss));
            }
        }
        if (s.opticsSigmaPx > 0) {
            for (int c = 0; c < 3; c++) {
                Mat m = new Mat(h, w, CvType.CV_32FC1);
                m.put(0, 0, img[c]);
                Imgproc.GaussianBlur(m, m, new Size(0, 0), s.opticsSigmaPx);
                m.get(0, 0, img[c]);
                m.release();
            }
        }
        Random rnd = new Random(s.noiseSeed);
        BufferedImage out = new BufferedImage(w, h,
                s.gray ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_3BYTE_BGR);
        byte[] data = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int k = j * w + i;
                double ramp = s.gradX * (i - w / 2.0) / (w / 2.0)
                        + s.gradY * (j - h / 2.0) / (h / 2.0);
                double[] v = new double[3];
                for (int c = 0; c < 3; c++) {
                    v[c] = s.gain * img[c][k] + s.offset + ramp;
                }
                if (s.gray) {
                    double gv = 0.299 * v[0] + 0.587 * v[1] + 0.114 * v[2]
                            + s.noiseSigma * rnd.nextGaussian();
                    data[k] = (byte) clip(gv);
                }
                else {
                    // TYPE_3BYTE_BGR
                    data[3 * k] = (byte) clip(v[2] + s.noiseSigma * rnd.nextGaussian());
                    data[3 * k + 1] = (byte) clip(v[1] + s.noiseSigma * rnd.nextGaussian());
                    data[3 * k + 2] = (byte) clip(v[0] + s.noiseSigma * rnd.nextGaussian());
                }
            }
        }
        return out;
    }

    private static int clip(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /** Scene radiance (RGB, may exceed 255 for highlights) at (x, y) mm. */
    static void radiance(Shot s, double x, double y, double[] out) {
        for (Deposit d : s.deposits) {
            if (d.contains(x, y)) {
                double rho = Math.min(1, d.rho(x, y));
                double v = 150 + 14 * (value(x / 0.025, y / 0.025, 9) - 0.5) * 2
                        - 25 * Math.pow(rho, 6) + d.highlight(x, y);
                if (s.pasteRgb != null) {
                    for (int c = 0; c < 3; c++) {
                        out[c] = s.pasteRgb[c] * v / 150;
                    }
                    return;
                }
                out[0] = v;
                out[1] = v;
                out[2] = v + 6;
                return;
            }
        }
        if (s.scene != null) {
            s.scene.radiance(x, y, out);
            return;
        }
        switch (s.background) {
            case PAPER: {
                double v = 226 + 16 * (fbm(x / 0.35, y / 0.35, 1, 3) - 0.5) * 2
                        + 8 * (value(x / 0.04, y / 0.04, 2) - 0.5) * 2;
                out[0] = v;
                out[1] = v;
                out[2] = v + 4;
                return;
            }
            case COPPER: {
                double t = value(x / 1.5, y / 0.025, 3);
                double base = 0.85 + 0.3 * (t - 0.5) + 0.15 * (fbm(x / 0.3, y / 0.3, 4, 2) - 0.5);
                double sp = value(x / 0.04, y / 0.04, 5);
                double spark = sp > 0.8 ? (sp - 0.8) / 0.2 * 260 : 0;
                out[0] = 215 * base + spark;
                out[1] = 130 * base + spark;
                out[2] = 85 * base + spark;
                return;
            }
            case FR4:
            default: {
                double c = Math.cos(Math.toRadians(-s.boardRotDeg));
                double sn = Math.sin(Math.toRadians(-s.boardRotDeg));
                double bx = x * c - y * sn;
                double by = x * sn + y * c;
                double m = Math.rint((bx - PAD_X0) / PAD_PITCH_X);
                double n = Math.rint((by - PAD_Y0) / PAD_PITCH_Y);
                double dx = bx - (PAD_X0 + m * PAD_PITCH_X);
                double dy = by - (PAD_Y0 + n * PAD_PITCH_Y);
                if (Math.abs(dx) <= PAD_W / 2 && Math.abs(dy) <= PAD_H / 2) {
                    if (((long) (m + n) & 1) == 0) {
                        // HASL: lumpy silver with small glints.
                        double f = 0.9 + 0.2 * fbm(bx / 0.15, by / 0.15, 6, 2);
                        double sp = value(bx / 0.03, by / 0.03, 7);
                        double glint = sp > 0.85 ? (sp - 0.85) / 0.15 * 120 : 0;
                        out[0] = 200 * f + glint;
                        out[1] = 200 * f + glint;
                        out[2] = 206 * f + glint;
                    }
                    else {
                        // ENIG: flat gold.
                        double f = 0.95 + 0.1 * fbm(bx / 0.1, by / 0.1, 8, 2);
                        out[0] = 220 * f;
                        out[1] = 185 * f;
                        out[2] = 105 * f;
                    }
                    return;
                }
                double weave = 10 * Math.sin(2 * Math.PI * bx / 0.5) * Math.sin(2 * Math.PI * by / 0.5);
                double tex = 8 * (fbm(bx / 0.2, by / 0.2, 10, 2) - 0.5) * 2;
                out[0] = 35 + weave * 0.5 + tex;
                out[1] = 95 + weave + tex;
                out[2] = 55 + weave * 0.6 + tex;
            }
        }
    }

    /** Ground-truth coverage of a pad rectangle by the deposits (dense numeric integration). */
    static double trueCoverage(List<Deposit> deposits, double cx, double cy, double w, double h,
            double rotDeg) {
        int n = 500;
        double c = Math.cos(Math.toRadians(rotDeg));
        double s = Math.sin(Math.toRadians(rotDeg));
        int in = 0;
        for (int j = 0; j < n; j++) {
            double v = ((j + 0.5) / n - 0.5) * h;
            for (int i = 0; i < n; i++) {
                double u = ((i + 0.5) / n - 0.5) * w;
                double x = cx + u * c - v * s;
                double y = cy + u * s + v * c;
                for (Deposit d : deposits) {
                    if (d.contains(x, y)) {
                        in++;
                        break;
                    }
                }
            }
        }
        return in / (double) (n * n);
    }

    // Value noise -------------------------------------------------------------------------------

    private static double hash(long x, long y, int seed) {
        long h = x * 374761393L + y * 668265263L + seed * 2246822519L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        return (h & 0xffffffL) / 16777216.0;
    }

    /** Smooth value noise in [0, 1) with unit feature size. */
    static double value(double x, double y, int seed) {
        long xi = (long) Math.floor(x);
        long yi = (long) Math.floor(y);
        double fx = x - xi;
        double fy = y - yi;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        double a = hash(xi, yi, seed);
        double b = hash(xi + 1, yi, seed);
        double c = hash(xi, yi + 1, seed);
        double d = hash(xi + 1, yi + 1, seed);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
    }

    static double fbm(double x, double y, int seed, int octaves) {
        double sum = 0, amp = 0.5, norm = 0;
        for (int o = 0; o < octaves; o++) {
            sum += amp * value(x, y, seed + 31 * o);
            norm += amp;
            amp *= 0.5;
            x *= 2.03;
            y *= 2.03;
        }
        return sum / norm;
    }
}
