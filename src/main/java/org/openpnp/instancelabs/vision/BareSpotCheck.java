package org.openpnp.instancelabs.vision;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.Arrays;
import java.util.Locale;

/**
 * Look before touching: is the spot under the camera bare surface, or does it carry paste, a part
 * or print? N1 must not touch wet paste or a loose part (paste in the tip fouls N1).
 *
 * In a frame centred on the spot:
 * <ul>
 * <li>surface = a smooth colour field fitted to the ring 1.5 .. 3.0 mm from the centre: per BGR
 * channel a quadratic in x and y, least squares over the half of the ring pixels nearest to it,
 * refitted {@value #FIT_ROUNDS} times starting from the ring's median colour. The ring light's
 * shading on glossy mask and blank is part of the surface; paste, parts, print or the far side of
 * an edge in the ring are not;</li>
 * <li>texture = the median distance of that nearest half from the field: how uneven the surface
 * itself is (board 3's scratched mask reads 9 and 14, clean mask and blank 1 .. 5);</li>
 * <li>a pixel differs when its BGR distance from the field exceeds the threshold max(colourDelta,
 * textureFactor x texture): on a rough mask only what stands out of the texture counts;</li>
 * <li>foreign pixels = differing pixels in solid patches: a 3 x 3 majority vote, then an opening
 * with a disc of radius solidRadiusMm. Texture, scratches and thin print strokes drop out; a paste
 * dot (0.4 mm and up), a part, a hole's copper ring or the far side of an edge stay;</li>
 * <li>plus the pixels beyond wideDelta, whatever the texture, in wide patches (majority vote, then
 * an opening with a disc of radius wideRadiusMm). Texture does not form patches 0.4 mm across at
 * that distance (board 3's scratched mask reads 0 %), paste, a part or a hole's ring do: a rough
 * surface raises the threshold for small patches only, not for a paste dot of 0.5 mm and up;</li>
 * <li>not bare if the foreign share of the disc of radius 1.0 mm (tip radius 0.35 + N1 offset
 * error 0.4 + 0.25 margin) is above maxForeignShare, or the ring spread (the median BGR distance
 * of the ring from its median colour) is above maxRingSpread: an edge, the blank's border,
 * tape.</li>
 * </ul>
 *
 * The defaults were fixed on bench frames (0.01074 x 0.01106 mm/px) and on the real machine's
 * frames of 2026-10-29 (WB 2800 manual, exposure 146). Under the blue ring light paste is far
 * from the blank (40 .. 100 at its rim) but close to the board's solder mask (~20 .. 26; the SIM
 * colours 22.6), so colourDelta is 18. The ring's median colour alone refused bare glossy blank
 * in the dark zone between the light's reflections (the whole disc ~22 from the ring: 89 .. 97 %
 * foreign) and board 3's scratched mask (54 .. 60 %). Now every bare bench and real spot reads
 * 0 .. 1.3 %; paste, parts, a hole's copper ring and board edges read 25 .. 87 %, the FID1
 * lettering 14 % and the SIM's 0.5 mm paste dot 6.1 %. BareSpotCheckTest records each margin. On
 * a mask as rough as board 3's at (37, 37) the threshold is 57: there only the wide patches see
 * paste that stands less far out (a 0.7 mm dot 40 from the mask reads 12 %, the real paste and
 * hole frames with board 3's texture laid over them 15 .. 70 %, the board edge only 6 %), and
 * print strokes, specks under 0.4 mm across and anything within 22 of the mask are not seen.
 *
 * Pure: no camera, no OpenCV. Offsets follow {@link DotInspector}: mm from the image centre
 * (pixel index - size/2), +x right, +y up.
 */
public final class BareSpotCheck {
    private BareSpotCheck() {}

    /** Rounds of the surface fit, each on the half of the ring nearest to the previous one. */
    static final int FIT_ROUNDS = 4;
    /** Histogram bins per BGR distance unit for the quantiles of the fit. */
    private static final int BINS_PER_UNIT = 8;
    private static final double MAX_DISTANCE = Math.sqrt(3) * 255;

    /** Code constants (not persisted); tests set them directly. */
    public static final class Settings {
        /** Where N1 may land: tip radius 0.35 + N1 offset error 0.4 + 0.25 margin, mm. */
        public double discRadiusMm = 1.0;
        /** The reference ring, mm from the centre. */
        public double ringInnerMm = 1.5, ringOuterMm = 3.0;
        /**
         * A pixel differs from the surface above this BGR distance from the fitted field, at
         * least (paste is 40 .. 100 from the blank at its rim, ~20 .. 26 from the board's solder
         * mask, 22.6 in the SIM colours).
         */
        public double colourDelta = 18;
        /**
         * On an uneven surface the threshold is this many times its texture (board 3's scratched
         * mask: 36 and 57; a clean surface keeps colourDelta).
         */
        public double textureFactor = 4;
        /**
         * Differing pixels count only in patches that hold a disc of this radius, mm: texture,
         * scratches and print strokes under 0.2 mm wide drop out, a 0.4 mm paste dot stays.
         */
        public double solidRadiusMm = 0.10;
        /**
         * Pixels beyond wideDelta also count, whatever the texture, in patches that hold a disc of
         * this radius, mm: board 3's scratched mask forms none, paste dots of 0.4 mm and up, a
         * part, a hole's ring and the far side of an edge do.
         */
        public double wideRadiusMm = 0.20;
        /**
         * The BGR distance from the fitted field for the wide patches: board 3's bare mask strays
         * up to 19 over patches that wide (the dark zone at R1), paste's rim on the blank reads
         * 40 .. 100, a board edge 42.
         */
        public double wideDelta = 22;
        /** Not bare above this foreign share of the disc, 0..1 (a 0.45 mm dot is 5 %). */
        public double maxForeignShare = 0.05;
        /**
         * Not bare above this ring spread (median BGR distance from the ring's median colour):
         * shading reads up to 24, board 3's scratched mask with two parts in the ring 38, the
         * blank's edge 76.
         */
        public double maxRingSpread = 50;

        /** Throws IllegalArgumentException if a field is out of range. */
        public void validate() {
            if (!(discRadiusMm > 0) || !(ringInnerMm >= discRadiusMm)
                    || !(ringOuterMm > ringInnerMm) || Double.isInfinite(ringOuterMm)) {
                throw new IllegalArgumentException("need 0 < disc <= ring inner < ring outer,"
                        + " got " + discRadiusMm + ", " + ringInnerMm + ", " + ringOuterMm + " mm");
            }
            if (!(colourDelta >= 0) || !(maxRingSpread >= 0) || Double.isInfinite(colourDelta)) {
                throw new IllegalArgumentException("colourDelta and maxRingSpread must be >= 0,"
                        + " got " + colourDelta + ", " + maxRingSpread);
            }
            if (!(textureFactor >= 0) || Double.isInfinite(textureFactor)
                    || !(solidRadiusMm >= 0) || !(solidRadiusMm < discRadiusMm)) {
                throw new IllegalArgumentException("need textureFactor >= 0 and 0 <= solidRadiusMm"
                        + " < discRadiusMm, got " + textureFactor + ", " + solidRadiusMm + " mm");
            }
            if (!(wideRadiusMm >= solidRadiusMm) || !(wideRadiusMm < discRadiusMm)
                    || !(wideDelta >= 0) || Double.isInfinite(wideDelta)) {
                throw new IllegalArgumentException("need solidRadiusMm <= wideRadiusMm"
                        + " < discRadiusMm and wideDelta >= 0, got " + wideRadiusMm + " mm, "
                        + wideDelta);
            }
            if (!(maxForeignShare >= 0 && maxForeignShare <= 1)) {
                throw new IllegalArgumentException(
                        "maxForeignShare must be 0..1, got " + maxForeignShare);
            }
        }
    }

    /** One look. */
    public static final class Result {
        public final boolean bare;
        /** Foreign pixels (in solid or wide patches) / disc pixels, 0..1. */
        public final double foreignShare;
        /** Disc pixels that differ before the solid-patch rule / disc pixels, 0..1. */
        public final double rawShare;
        /** Disc pixels beyond wideDelta in wide patches / disc pixels, 0..1. */
        public final double wideShare;
        /** Median BGR distance of the ring pixels from their median colour. */
        public final double ringSpread;
        /** Median distance of the ring's nearest half from the fitted surface. */
        public final double texture;
        /** The BGR distance above which a pixel differs: max(colourDelta, factor x texture). */
        public final double threshold;
        /** Why the spot is not bare; null when it is. */
        public final String reason;
        /** The fitted surface colour at the spot. */
        public final double refB, refG, refR;
        public final int discPx, ringPx;

        Result(double foreignShare, double rawShare, double wideShare, double ringSpread,
                double texture, double threshold, double[] ref, int discPx, int ringPx,
                Settings s) {
            this.foreignShare = foreignShare;
            this.rawShare = rawShare;
            this.wideShare = wideShare;
            this.ringSpread = ringSpread;
            this.texture = texture;
            this.threshold = threshold;
            this.refB = ref[0];
            this.refG = ref[1];
            this.refR = ref[2];
            this.discPx = discPx;
            this.ringPx = ringPx;
            String why = null;
            if (!(ringSpread <= s.maxRingSpread)) {
                why = String.format(Locale.ROOT, "the surface around the spot is not uniform"
                        + " (spread %.1f, limit %.1f): an edge, a border or tape",
                        ringSpread, s.maxRingSpread);
            }
            if (!(foreignShare <= s.maxForeignShare)) {
                String f = String.format(Locale.ROOT, "%.1f %% of the spot differs from the surface"
                        + " around it (limit %.1f %%): paste, a part or print",
                        100 * foreignShare, 100 * s.maxForeignShare);
                why = why == null ? f : why + "; " + f;
            }
            this.reason = why;
            this.bare = why == null;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "BareSpot[%s, foreign=%.4f raw=%.4f wide=%.4f (%d px disc) spread=%.2f"
                            + " texture=%.2f threshold=%.1f (%d px ring) ref=%.0f,%.0f,%.0f%s]",
                    bare ? "bare" : "not bare", foreignShare, rawShare, wideShare, discPx,
                    ringSpread, texture, threshold, ringPx, refB, refG, refR,
                    reason == null ? "" : " " + reason);
        }
    }

    /**
     * Looks at the spot at the frame centre. The ring must lie inside the frame. Null settings
     * mean the defaults.
     */
    public static Result check(BufferedImage img, double mmPerPxX, double mmPerPxY, Settings s) {
        if (img == null) {
            throw new IllegalArgumentException("image is required");
        }
        if (!(mmPerPxX > 0) || !(mmPerPxY > 0) || Double.isInfinite(mmPerPxX)
                || Double.isInfinite(mmPerPxY)) {
            throw new IllegalArgumentException("mm per pixel must be positive and finite");
        }
        if (s == null) {
            s = new Settings();
        }
        s.validate();
        int w = img.getWidth();
        int h = img.getHeight();
        double cx = w / 2.0;
        double cy = h / 2.0;
        long x0 = (long) Math.ceil(cx - s.ringOuterMm / mmPerPxX);
        long x1 = (long) Math.floor(cx + s.ringOuterMm / mmPerPxX);
        long y0 = (long) Math.ceil(cy - s.ringOuterMm / mmPerPxY);
        long y1 = (long) Math.floor(cy + s.ringOuterMm / mmPerPxY);
        if (x0 < 0 || y0 < 0 || x1 > w - 1 || y1 > h - 1) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "the %.1f mm look ring does not fit in the %dx%d frame", s.ringOuterMm, w, h));
        }
        int bw = (int) (x1 - x0 + 1);
        int bh = (int) (y1 - y0 + 1);
        int[][] bgr = pixels(img, (int) x0, (int) y0, bw, bh);
        double[] xs = new double[bw];
        double[] ys = new double[bh];
        for (int i = 0; i < bw; i++) {
            xs[i] = (x0 + i - cx) * mmPerPxX;
        }
        for (int j = 0; j < bh; j++) {
            ys[j] = (y0 + j - cy) * mmPerPxY;
        }
        double disc2 = s.discRadiusMm * s.discRadiusMm;
        double in2 = s.ringInnerMm * s.ringInnerMm;
        double out2 = s.ringOuterMm * s.ringOuterMm;
        byte[] zone = new byte[bw * bh];
        int[][] hist = new int[3][256];
        int ringPx = 0;
        int discPx = 0;
        for (int j = 0; j < bh; j++) {
            for (int i = 0; i < bw; i++) {
                double r2 = xs[i] * xs[i] + ys[j] * ys[j];
                int k = j * bw + i;
                if (r2 <= disc2) {
                    zone[k] = 1;
                    discPx++;
                }
                else if (r2 >= in2 && r2 <= out2) {
                    zone[k] = 2;
                    ringPx++;
                    for (int c = 0; c < 3; c++) {
                        hist[c][bgr[c][k]]++;
                    }
                }
            }
        }
        if (discPx == 0 || ringPx == 0) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%.4f x %.4f mm per pixel is too coarse for the look", mmPerPxX, mmPerPxY));
        }
        double[] median = new double[3];
        for (int c = 0; c < 3; c++) {
            median[c] = median(hist[c], ringPx);
        }
        double[] ringDist = new double[ringPx];
        int n = 0;
        for (int k = 0; k < zone.length; k++) {
            if (zone[k] == 2) {
                ringDist[n++] = distance(bgr, k, median[0], median[1], median[2]);
            }
        }
        double[] sorted = ringDist.clone();
        Arrays.sort(sorted);
        double spread = (ringPx % 2 == 1) ? sorted[ringPx / 2]
                : 0.5 * (sorted[ringPx / 2 - 1] + sorted[ringPx / 2]);

        double[][] field = fitSurface(bgr, zone, xs, ys, bw, median, ringDist);
        double texture = quantile(ringDist, 0.25);
        double threshold = Math.max(s.colourDelta, s.textureFactor * texture);
        boolean[] differs = new boolean[bw * bh];
        boolean[] beyond = new boolean[bw * bh];
        int raw = 0;
        for (int j = 0; j < bh; j++) {
            for (int i = 0; i < bw; i++) {
                int k = j * bw + i;
                double[] f = surface(field, xs[i], ys[j]);
                double d = distance(bgr, k, f[0], f[1], f[2]);
                differs[k] = d > threshold;
                beyond[k] = d > s.wideDelta;
                if (differs[k] && zone[k] == 1) {
                    raw++;
                }
            }
        }
        boolean[] solid = open(majority(differs, bw, bh), bw, bh, s.solidRadiusMm, mmPerPxX,
                mmPerPxY);
        boolean[] wide = open(majority(beyond, bw, bh), bw, bh, s.wideRadiusMm, mmPerPxX,
                mmPerPxY);
        int foreign = 0;
        int wideN = 0;
        for (int k = 0; k < zone.length; k++) {
            if (zone[k] == 1) {
                if (solid[k] || wide[k]) {
                    foreign++;
                }
                if (wide[k]) {
                    wideN++;
                }
            }
        }
        return new Result(foreign / (double) discPx, raw / (double) discPx,
                wideN / (double) discPx, spread, texture, threshold, surface(field, 0, 0), discPx,
                ringPx, s);
    }

    /**
     * The surface's colour field: per channel c0 + c1 x + c2 y + c3 x^2 + c4 xy + c5 y^2 (mm),
     * least squares over the half of the ring nearest to the previous field (the first is the
     * ring's median colour). dist holds the ring pixels' distances from the field on return.
     */
    private static double[][] fitSurface(int[][] bgr, byte[] zone, double[] xs, double[] ys,
            int bw, double[] median, double[] dist) {
        double[][] field = new double[3][6];
        for (int c = 0; c < 3; c++) {
            field[c][0] = median[c];
        }
        double[] t = new double[6];
        for (int round = 0; round < FIT_ROUNDS; round++) {
            double cut = quantile(dist, 0.5);
            double[][] ata = new double[6][6];
            double[][] atb = new double[3][6];
            int n = 0;
            for (int k = 0; k < zone.length; k++) {
                if (zone[k] != 2) {
                    continue;
                }
                if (dist[n++] > cut) {
                    continue;
                }
                terms(xs[k % bw], ys[k / bw], t);
                for (int a = 0; a < 6; a++) {
                    for (int b = a; b < 6; b++) {
                        ata[a][b] += t[a] * t[b];
                    }
                    for (int c = 0; c < 3; c++) {
                        atb[c][a] += t[a] * bgr[c][k];
                    }
                }
            }
            for (int a = 0; a < 6; a++) {
                for (int b = 0; b < a; b++) {
                    ata[a][b] = ata[b][a];
                }
            }
            double[][] next = solve(ata, atb);
            if (next == null) {
                break; // too few ring pixels for a quadratic: keep the field so far
            }
            field = next;
            n = 0;
            for (int k = 0; k < zone.length; k++) {
                if (zone[k] == 2) {
                    double[] f = surface(field, xs[k % bw], ys[k / bw]);
                    dist[n++] = distance(bgr, k, f[0], f[1], f[2]);
                }
            }
        }
        return field;
    }

    private static void terms(double x, double y, double[] t) {
        t[0] = 1;
        t[1] = x;
        t[2] = y;
        t[3] = x * x;
        t[4] = x * y;
        t[5] = y * y;
    }

    private static double[] surface(double[][] field, double x, double y) {
        double[] t = new double[6];
        terms(x, y, t);
        double[] f = new double[3];
        for (int c = 0; c < 3; c++) {
            for (int a = 0; a < 6; a++) {
                f[c] += field[c][a] * t[a];
            }
        }
        return f;
    }

    /** ata x = atb[c] for each channel by Gaussian elimination; null if singular. */
    private static double[][] solve(double[][] ata, double[][] atb) {
        int m = ata.length;
        double[][] a = new double[m][];
        for (int i = 0; i < m; i++) {
            a[i] = Arrays.copyOf(ata[i], m + atb.length);
            for (int c = 0; c < atb.length; c++) {
                a[i][m + c] = atb[c][i];
            }
        }
        for (int col = 0; col < m; col++) {
            int p = col;
            for (int i = col + 1; i < m; i++) {
                if (Math.abs(a[i][col]) > Math.abs(a[p][col])) {
                    p = i;
                }
            }
            if (!(Math.abs(a[p][col]) > 1e-9 * Math.max(1, Math.abs(a[0][0])))) {
                return null;
            }
            double[] swap = a[p];
            a[p] = a[col];
            a[col] = swap;
            for (int i = 0; i < m; i++) {
                if (i != col) {
                    double f = a[i][col] / a[col][col];
                    for (int j = col; j < a[i].length; j++) {
                        a[i][j] -= f * a[col][j];
                    }
                }
            }
        }
        double[][] x = new double[atb.length][m];
        for (int c = 0; c < atb.length; c++) {
            for (int i = 0; i < m; i++) {
                x[c][i] = a[i][m + c] / a[i][i];
            }
        }
        return x;
    }

    private static double distance(int[][] bgr, int k, double b, double g, double r) {
        double db = bgr[0][k] - b;
        double dg = bgr[1][k] - g;
        double dr = bgr[2][k] - r;
        return Math.sqrt(db * db + dg * dg + dr * dr);
    }

    /** The p quantile of the values, to 1/8 of a BGR distance unit. */
    private static double quantile(double[] v, double p) {
        int bins = (int) Math.ceil(MAX_DISTANCE * BINS_PER_UNIT) + 2;
        int[] hist = new int[bins];
        for (double d : v) {
            hist[Math.min(bins - 1, (int) (d * BINS_PER_UNIT))]++;
        }
        long need = (long) Math.ceil(p * v.length);
        long seen = 0;
        for (int b = 0; b < bins; b++) {
            seen += hist[b];
            if (seen >= need) {
                return (b + 1) / (double) BINS_PER_UNIT;
            }
        }
        return MAX_DISTANCE;
    }

    /** 3 x 3 majority vote (outside the box counts as not differing). */
    private static boolean[] majority(boolean[] m, int bw, int bh) {
        boolean[] out = new boolean[m.length];
        for (int j = 0; j < bh; j++) {
            for (int i = 0; i < bw; i++) {
                int count = 0;
                for (int dj = -1; dj <= 1; dj++) {
                    int y = j + dj;
                    if (y < 0 || y >= bh) {
                        continue;
                    }
                    for (int di = -1; di <= 1; di++) {
                        int x = i + di;
                        if (x >= 0 && x < bw && m[y * bw + x]) {
                            count++;
                        }
                    }
                }
                out[j * bw + i] = count >= 5;
            }
        }
        return out;
    }

    /**
     * Morphological opening with a disc of radius r mm (an ellipse in pixels): the union of every
     * such disc that fits inside the mask. Row sums make each row of the disc one lookup.
     */
    private static boolean[] open(boolean[] m, int bw, int bh, double r, double mmX, double mmY) {
        int ry = (int) Math.floor(r / mmY);
        int[] half = new int[2 * ry + 1];
        for (int d = -ry; d <= ry; d++) {
            double yy = d * mmY;
            half[d + ry] = (int) Math.floor(Math.sqrt(Math.max(0, r * r - yy * yy)) / mmX);
        }
        boolean[] eroded = new boolean[m.length];
        int[][] rows = rowSums(m, bw, bh);
        for (int j = 0; j < bh; j++) {
            for (int i = 0; i < bw; i++) {
                boolean all = true;
                for (int d = -ry; d <= ry && all; d++) {
                    int y = j + d;
                    int a = i - half[d + ry];
                    int b = i + half[d + ry];
                    all = y >= 0 && y < bh && a >= 0 && b < bw
                            && rows[y][b + 1] - rows[y][a] == b - a + 1;
                }
                eroded[j * bw + i] = all;
            }
        }
        boolean[] out = new boolean[m.length];
        rows = rowSums(eroded, bw, bh);
        for (int j = 0; j < bh; j++) {
            for (int i = 0; i < bw; i++) {
                boolean any = false;
                for (int d = -ry; d <= ry && !any; d++) {
                    int y = j + d;
                    if (y < 0 || y >= bh) {
                        continue;
                    }
                    int a = Math.max(0, i - half[d + ry]);
                    int b = Math.min(bw - 1, i + half[d + ry]);
                    any = rows[y][b + 1] - rows[y][a] > 0;
                }
                out[j * bw + i] = any;
            }
        }
        return out;
    }

    /** Per row, the number of set pixels before each column (bw + 1 entries). */
    private static int[][] rowSums(boolean[] m, int bw, int bh) {
        int[][] rows = new int[bh][bw + 1];
        for (int j = 0; j < bh; j++) {
            for (int i = 0; i < bw; i++) {
                rows[j][i + 1] = rows[j][i] + (m[j * bw + i] ? 1 : 0);
            }
        }
        return rows;
    }

    /** Median of n values given as a 0..255 histogram (the mean of the middle two for even n). */
    private static double median(int[] hist, int n) {
        int lo = -1;
        int hi = -1;
        int seen = 0;
        for (int v = 0; v < hist.length && hi < 0; v++) {
            seen += hist[v];
            if (lo < 0 && seen > (n - 1) / 2) {
                lo = v;
            }
            if (seen > n / 2) {
                hi = v;
            }
        }
        return (n % 2 == 1) ? hi : 0.5 * (lo + hi);
    }

    /**
     * B, G, R planes (0..255) of a box. Grey images are read from the raster (getRGB would apply a
     * linear-to-sRGB curve to TYPE_BYTE_GRAY) and give three equal planes.
     */
    private static int[][] pixels(BufferedImage img, int x0, int y0, int bw, int bh) {
        int[][] out = new int[3][bw * bh];
        if (DotInspector.isGray(img)) {
            Raster r = img.getRaster();
            int bits = img.getColorModel().getComponentSize(0);
            double scale = 255.0 / ((1L << bits) - 1);
            int[] v = r.getSamples(r.getMinX() + x0, r.getMinY() + y0, bw, bh, 0, (int[]) null);
            for (int k = 0; k < v.length; k++) {
                int g = (int) Math.round(v[k] * scale);
                out[0][k] = g;
                out[1][k] = g;
                out[2][k] = g;
            }
            return out;
        }
        int[] rgb = img.getRGB(x0, y0, bw, bh, null, 0, bw);
        for (int k = 0; k < rgb.length; k++) {
            int p = rgb[k];
            out[0][k] = p & 0xff;
            out[1][k] = (p >> 8) & 0xff;
            out[2][k] = (p >> 16) & 0xff;
        }
        return out;
    }
}
