package org.openpnp.instancelabs.vision;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.Locale;

/**
 * Bare-pad check before dispensing: is the pad under the camera clean copper, or does it carry
 * paste left from an earlier run? Bare copper reads bright under the ring light (BGR ~246,244,222,
 * grey ~238) and paste reads dark blue (BGR ~160,63,13, grey ~59), so the check is the fraction of
 * bright pixels in a fixed box centred on the pad.
 *
 * This is the bench rule of {@code tools/disp.py --require-clean} pixel for pixel: on a 1920x1080
 * top-camera frame at 0.01074 x 0.01106 mm/px the default box is {@code [504:577, 923:998]} and
 * the grey level is OpenCV's {@code COLOR_BGR2GRAY} ({@link #gray}). Known-clean pads read
 * >= 0.90.
 *
 * Pure: no camera, no OpenCV. Offsets follow {@link DotInspector}: mm from the image centre
 * (pixel index - size/2), +x right, +y up.
 */
public final class PadCleanCheck {
    private PadCleanCheck() {}

    /** Tuning. Plain mutable fields like {@link DotInspectorSettings}. */
    public static final class Settings {
        /** A pixel is bright when its grey value is strictly above this level, 0-254. */
        public int grayLevel = 200;
        /** The pad is clean when at least this fraction of the box is bright, 0..1. */
        public double minBrightFraction = 0.8;
        /** Box width, mm (the bench box is 75 px = 0.806 mm; Lumen pads are ~1.0 mm square). */
        public double boxWMm = 0.806;
        /** Box height, mm (the bench box is 73 px = 0.807 mm). */
        public double boxHMm = 0.807;

        public Settings copy() {
            Settings c = new Settings();
            c.grayLevel = grayLevel;
            c.minBrightFraction = minBrightFraction;
            c.boxWMm = boxWMm;
            c.boxHMm = boxHMm;
            return c;
        }

        /** Throws IllegalArgumentException if a field is out of range. */
        public void validate() {
            if (grayLevel < 0 || grayLevel > 254) {
                throw new IllegalArgumentException("grayLevel must be 0..254, got " + grayLevel);
            }
            if (!(minBrightFraction >= 0 && minBrightFraction <= 1)) {
                throw new IllegalArgumentException(
                        "minBrightFraction must be 0..1, got " + minBrightFraction);
            }
            if (!(boxWMm > 0) || !(boxHMm > 0) || Double.isInfinite(boxWMm)
                    || Double.isInfinite(boxHMm)) {
                throw new IllegalArgumentException("box size must be positive and finite, got "
                        + boxWMm + " x " + boxHMm + " mm");
            }
        }
    }

    /** One measurement. The box is pixels x0..x0+boxWPx-1, y0..y0+boxHPx-1 (index convention). */
    public static final class Result {
        /** brightPx / totalPx, 0..1. */
        public final double brightFraction;
        /** brightFraction >= minBrightFraction. */
        public final boolean clean;
        public final int brightPx, totalPx, x0, y0, boxWPx, boxHPx;

        public Result(int brightPx, int x0, int y0, int boxWPx, int boxHPx,
                double minBrightFraction) {
            this.brightPx = brightPx;
            this.totalPx = boxWPx * boxHPx;
            this.brightFraction = brightPx / (double) totalPx;
            this.clean = brightFraction >= minBrightFraction;
            this.x0 = x0;
            this.y0 = y0;
            this.boxWPx = boxWPx;
            this.boxHPx = boxHPx;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "PadClean[%s, bright=%.4f (%d/%d), box=%d,%d %dx%d]",
                    clean ? "clean" : "dirty", brightFraction, brightPx, totalPx, x0, y0, boxWPx,
                    boxHPx);
        }
    }

    /**
     * Measures the box centred (dxMm, dyMm) from the image centre. Box size in px is
     * round(boxMm / mmPerPx) (at least 1), its centre is (W/2 + round(dxMm/mmPerPxX),
     * H/2 - round(dyMm/mmPerPxY)) and its start is centre - size/2 (integer division). The box must
     * lie inside the image. Null settings mean the defaults.
     */
    public static Result measure(BufferedImage img, double mmPerPxX, double mmPerPxY, double dxMm,
            double dyMm, Settings s) {
        if (img == null) {
            throw new IllegalArgumentException("image is required");
        }
        if (!(mmPerPxX > 0) || !(mmPerPxY > 0) || Double.isInfinite(mmPerPxX)
                || Double.isInfinite(mmPerPxY)) {
            throw new IllegalArgumentException("mm per pixel must be positive and finite");
        }
        if (!Double.isFinite(dxMm) || !Double.isFinite(dyMm)) {
            throw new IllegalArgumentException("pad offset must be finite");
        }
        if (s == null) {
            s = new Settings();
        }
        s.validate();
        int iw = img.getWidth();
        int ih = img.getHeight();
        // long, so that absurd offsets or scales cannot wrap an int back into the image.
        long w = Math.max(1, Math.round(s.boxWMm / mmPerPxX));
        long h = Math.max(1, Math.round(s.boxHMm / mmPerPxY));
        long x0 = iw / 2 + Math.round(dxMm / mmPerPxX) - w / 2;
        long y0 = ih / 2 - Math.round(dyMm / mmPerPxY) - h / 2;
        if (x0 < 0 || y0 < 0 || x0 > iw - w || y0 > ih - h) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "pad box %dx%d px at (%.3f, %.3f) mm from the centre lies outside the"
                            + " %dx%d image",
                    w, h, dxMm, dyMm, iw, ih));
        }
        int bw = (int) w;
        int bh = (int) h;
        int bright = 0;
        if (DotInspector.isGray(img)) {
            // From the raster: getRGB would apply a linear-to-sRGB curve to TYPE_BYTE_GRAY.
            Raster r = img.getRaster();
            int bits = img.getColorModel().getComponentSize(0);
            double scale = 255.0 / ((1L << bits) - 1);
            int[] v = r.getSamples(r.getMinX() + (int) x0, r.getMinY() + (int) y0, bw, bh, 0,
                    (int[]) null);
            for (int g : v) {
                if (Math.round(g * scale) > s.grayLevel) {
                    bright++;
                }
            }
        }
        else {
            for (int p : img.getRGB((int) x0, (int) y0, bw, bh, null, 0, bw)) {
                if (gray(p) > s.grayLevel) {
                    bright++;
                }
            }
        }
        return new Result(bright, (int) x0, (int) y0, bw, bh, s.minBrightFraction);
    }

    /**
     * Grey level of a packed (A)RGB pixel exactly as OpenCV's 8-bit {@code COLOR_BGR2GRAY}
     * (fixed-point 0.299 R + 0.587 G + 0.114 B, 15 fractional bits, round half up).
     */
    public static int gray(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return (9798 * r + 19235 * g + 3735 * b + 16384) >> 15;
    }
}
