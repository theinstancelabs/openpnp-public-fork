package org.openpnp.instancelabs.vision;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.CvException;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.core.TermCriteria;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;
import org.opencv.video.Video;

/**
 * Finds material (solder-paste dots) that appeared between a "before" and an "after" image taken
 * from the same camera position. Deterministic OpenCV pipeline, no learned model:
 *
 * <ol>
 * <li>Float channels (colour images keep B, G, R, because grey paste on orange copper can have
 * the same luminance; grey images stay grey), Gaussian blur of {@code blurPx}.</li>
 * <li>Optional registration ({@code registerShift}): ECC on a low-passed
 * copy estimate a global sub-pixel shift and "after" is resampled onto "before", so machine
 * re-positioning error does not light up textured backgrounds. A second ECC pass masks out the
 * candidate material so the new dots cannot bias the estimate.</li>
 * <li>Robust per-channel brightness normalization: fit after = g*before + o + px*X + py*Y with
 * iterative outlier rejection (the dots are the outliers), so a global gain/offset change or a
 * mild illumination gradient cancels.</li>
 * <li>Difference = largest per-channel |after - predicted|; pixels saturated in both images are
 * ignored (specular copper, HASL glints).</li>
 * <li>Threshold at {@code diffThreshold}, morphological open + close (3x3), and a larger closing
 * applied only on strong "before" edges (a deposit crossing a pad edge matches the blurred edge
 * colour somewhere, which would otherwise crack the blob).</li>
 * <li>Per blob: re-decide the boundary at a local 50 % level (half way between background and
 * the nearby material contrast), which removes the size bias of a fixed threshold on blurred
 * edges; fill holes; then area (pixel count), centroid (moments) and circularity
 * (4*pi*A/P^2 of the outline).</li>
 * <li>{@link #inspect} filters by diameter and circularity; {@link #padCoverage} uses every blob;
 * {@link #inspectSpots} cuts strings off the new material and measures the dot at each known spot.</li>
 * </ol>
 *
 * Coordinates follow OpenPnP ({@code VisionUtils.getPixelCenterOffsets}): offset = pixel index -
 * size/2, +x right, +y up. Thread-safe as long as the settings are not mutated concurrently.
 */
public final class DotInspector {
    static {
        nu.pattern.OpenCV.loadLocally();
    }

    /** Pixels at or above this level in both images are treated as clipped highlights. */
    private static final double SATURATED = 250;
    /** Shifts smaller than this are not applied (avoids needless interpolation). */
    private static final double MIN_SHIFT_PX = 0.05;
    /** How far (px) the 50 % re-threshold may grow a blob beyond its seed. */
    private static final int GROW_PX = 3;
    /** Radius (px) of the closing that bridges cracks on "before" edges. */
    private static final int BRIDGE_PX = 3;
    /** Margin (px) around candidate material excluded from the second registration pass. */
    private static final int MASK_MARGIN_PX = 6;
    /** Low-pass applied before registration, px. */
    private static final double REGISTRATION_SIGMA_PX = 2.0;
    /** Registration uses at most this many central pixels. */
    private static final int REGISTRATION_MAX_PIXELS = 640 * 480;
    /** ECC correlation below this means "no reliable shift". */
    static final double MIN_ECC_CORRELATION = 0.2;
    /** Sub-samples per pixel side for the pad mask. */
    private static final int PAD_SUBSAMPLES = 4;
    /** Approximate number of pixels sampled for the brightness fit (4 parameters). */
    private static final double FIT_SAMPLES = 20000;

    private final DotInspectorSettings settings;

    public DotInspector(DotInspectorSettings s) {
        this.settings = s != null ? s : new DotInspectorSettings();
    }

    public DotInspectorSettings getSettings() {
        return settings;
    }

    /** before/after taken from the same camera position; returns blobs that appeared. */
    public List<Dot> inspect(BufferedImage before, BufferedImage after, double mmPerPxX,
            double mmPerPxY) {
        checkScale(mmPerPxX, mmPerPxY);
        try (Analysis a = analyze(before, after)) {
            List<Dot> dots = new ArrayList<>();
            for (Blob b : a.blobs) {
                Dot d = a.toDot(b, mmPerPxX, mmPerPxY);
                if (rejectReason(d) == null) {
                    dots.add(d);
                }
            }
            dots.sort(Comparator.comparingDouble(d -> Math.hypot(d.xMm, d.yMm)));
            return dots;
        }
    }

    /**
     * The dot laid at each of {@code spotsMm} ({x, y} offsets from the image centre, mm, +x right, +y up), found
     * even when paste strings join it to its neighbours ({@link SpotMeasure}): the new material is opened with a disc
     * {@code cutMm} across, which cuts strings and necks thinner than that, and the piece at each spot is its dot. A
     * spot whose paste is joined to another spot's, or that holds only strings or a smear, is
     * {@link SpotDot.State#UNMEASURED}, never "no dot". {@code radiusMm}: how far from its spot a dot (or loose
     * paste) still belongs to it; pieces within half of it reach the spot.
     */
    public List<SpotDot> inspectSpots(BufferedImage before, BufferedImage after, double mmPerPxX, double mmPerPxY,
            List<double[]> spotsMm, double cutMm, double radiusMm) {
        checkScale(mmPerPxX, mmPerPxY);
        if (!(cutMm >= 0) || !(radiusMm > 0) || Double.isInfinite(cutMm) || Double.isInfinite(radiusMm)) {
            throw new IllegalArgumentException("the cut must be 0 or more and the spot radius above 0 mm");
        }
        try (Analysis a = analyze(before, after)) {
            return SpotMeasure.measure(a, settings, mmPerPxX, mmPerPxY, spotsMm, cutMm, radiusMm);
        }
    }

    /**
     * fraction 0..1 of each pad rectangle (centre/size/rotation in mm relative to image centre)
     * covered by new material. Rotation is in degrees, counter-clockwise positive (+y up). The pad
     * must lie entirely inside the image.
     */
    public double padCoverage(BufferedImage before, BufferedImage after, double mmPerPxX,
            double mmPerPxY, double cxMm, double cyMm, double wMm, double hMm, double rotDeg) {
        checkScale(mmPerPxX, mmPerPxY);
        try (Analysis a = analyze(before, after)) {
            return coverage(a, mmPerPxX, mmPerPxY, cxMm, cyMm, wMm, hMm, rotDeg);
        }
    }

    /** Null if the dot passes the size/circularity filters, otherwise a short reason. */
    String rejectReason(Dot d) {
        if (d.diameterMm < settings.minDiameterMm) {
            return "too small";
        }
        if (d.diameterMm > settings.maxDiameterMm) {
            return "too large";
        }
        if (d.circularity < settings.minCircularity) {
            return "not circular";
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Analysis

    /** One connected region of new material, in pixel coordinates (index convention). */
    static final class Blob {
        double cxPx, cyPx, areaPx, circularity;
        /**
         * Diagnostics: median difference in the ring around the blob, median difference inside
         * the seed, and median of the local 50 % levels applied at its boundary.
         */
        double background, contrast, level;
    }

    /** Result of the shared pipeline; owns native OpenCV memory, so close it. */
    static final class Analysis implements AutoCloseable {
        final int width, height;
        /** CV_8UC1, 255 where new material was found (holes filled). */
        Mat material;
        final List<Blob> blobs = new ArrayList<>();
        double shiftXPx, shiftYPx, eccCorrelation = Double.NaN;
        boolean shiftApplied;
        double[][] brightnessFit;

        Analysis(int width, int height) {
            this.width = width;
            this.height = height;
        }

        Dot toDot(Blob b, double mmPerPxX, double mmPerPxY) {
            double area = b.areaPx * mmPerPxX * mmPerPxY;
            return new Dot((b.cxPx - width / 2.0) * mmPerPxX, -(b.cyPx - height / 2.0) * mmPerPxY,
                    2 * Math.sqrt(area / Math.PI), area, b.circularity);
        }

        @Override
        public void close() {
            if (material != null) {
                material.release();
                material = null;
            }
        }
    }

    Analysis analyze(BufferedImage before, BufferedImage after) {
        if (before == null || after == null) {
            throw new IllegalArgumentException("before and after images are required");
        }
        final int w = before.getWidth();
        final int h = before.getHeight();
        if (after.getWidth() != w || after.getHeight() != h) {
            throw new IllegalArgumentException(String.format("image sizes differ: %dx%d vs %dx%d",
                    w, h, after.getWidth(), after.getHeight()));
        }
        boolean color = !(isGray(before) && isGray(after));
        float[][] b = channels(before, color);
        float[][] a = channels(after, color);
        int k = kernelSize();
        int n = b.length;
        Analysis result = new Analysis(w, h);
        List<Mat> bm = new ArrayList<>();
        List<Mat> am = new ArrayList<>();
        List<Mat> aligned = null;
        Mat diffMat = null;
        Mat edges = null;
        try {
            for (int c = 0; c < n; c++) {
                bm.add(toMat(b[c], w, h, k));
                am.add(toMat(a[c], w, h, k));
                bm.get(c).get(0, 0, b[c]);
            }
            float[] diff;
            if (settings.registerShift) {
                // Pass 1: whole image. Pass 2: ECC again with the new material masked out, so
                // the dots themselves cannot pull the estimate.
                double[] shift = estimateShift(bm, am, null, null, result);
                aligned = warp(am, shift);
                diff = difference(b, aligned, w, h, border(shift), result);
                Mat mask = materialExclusionMask(diff, w, h);
                if (mask != null) {
                    double[] refined = estimateShift(bm, am, mask, shift, result);
                    mask.release();
                    if (Math.hypot(refined[0] - shift[0], refined[1] - shift[1]) > 0.005) {
                        if (aligned != am) {
                            release(aligned);
                        }
                        shift = refined;
                        aligned = warp(am, shift);
                        diff = difference(b, aligned, w, h, border(shift), result);
                    }
                }
                result.shiftXPx = shift[0];
                result.shiftYPx = shift[1];
                result.shiftApplied = shift[2] != 0;
            }
            else {
                diff = difference(b, am, w, h, 0, result);
            }
            diffMat = new Mat(h, w, CvType.CV_32FC1);
            diffMat.put(0, 0, diff);
            edges = edgeMask(bm, settings.diffThreshold);
            byte[] edgeA = new byte[w * h];
            edges.get(0, 0, edgeA);
            result.material = Mat.zeros(h, w, CvType.CV_8UC1);
            findBlobs(diffMat, diff, edges, edgeA, w, h, k, result);
            return result;
        }
        catch (RuntimeException e) {
            result.close();
            throw e;
        }
        finally {
            release(bm);
            if (aligned != null && aligned != am) {
                release(aligned);
            }
            release(am);
            if (diffMat != null) {
                diffMat.release();
            }
            if (edges != null) {
                edges.release();
            }
        }
    }

    /**
     * Brightness-normalized difference: per channel, fit after ~ before (robustly), then take the
     * largest |after - predicted| over channels. Pixels saturated in both images and the invalid
     * border are 0.
     */
    private static float[] difference(float[][] b, List<Mat> am, int w, int h, int border,
            Analysis result) {
        int n = b.length;
        float[] diff = new float[w * h];
        float[] a = new float[w * h];
        float[] pred = new float[w * h];
        result.brightnessFit = new double[n][];
        for (int c = 0; c < n; c++) {
            am.get(c).get(0, 0, a);
            double[] p = robustFit(b[c], a, w, h, border);
            result.brightnessFit[c] = p;
            predict(b[c], p, w, h, pred);
            for (int y = border; y < h - border; y++) {
                for (int x = border, i = y * w + border; x < w - border; x++, i++) {
                    if (b[c][i] >= SATURATED && a[i] >= SATURATED) {
                        continue;
                    }
                    float d = Math.abs(a[i] - pred[i]);
                    if (d > diff[i]) {
                        diff[i] = d;
                    }
                }
            }
        }
        return diff;
    }

    /** 0 near anything that already looks like new material, 255 elsewhere; null if none. */
    private Mat materialExclusionMask(float[] diff, int w, int h) {
        byte[] m = new byte[w * h];
        boolean any = false;
        for (int i = 0; i < m.length; i++) {
            if (diff[i] > settings.diffThreshold) {
                m[i] = (byte) 255;
                any = true;
            }
        }
        if (!any) {
            return null;
        }
        Mat seed = new Mat(h, w, CvType.CV_8UC1);
        seed.put(0, 0, m);
        Mat grown = dilate(seed, MASK_MARGIN_PX);
        seed.release();
        Core.bitwise_not(grown, grown);
        return grown;
    }

    private static int border(double[] shift) {
        return shift[2] == 0 ? 0
                : (int) Math.ceil(Math.max(Math.abs(shift[0]), Math.abs(shift[1]))) + 1;
    }

    double coverage(Analysis a, double mmPerPxX, double mmPerPxY, double cxMm, double cyMm,
            double wMm, double hMm, double rotDeg) {
        if (!(wMm > 0) || !(hMm > 0)) {
            throw new IllegalArgumentException("pad size must be positive");
        }
        double rad = Math.toRadians(rotDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double hw = wMm / 2;
        double hh = hMm / 2;
        // Pad bounding box in pixel index coordinates.
        double ex = Math.abs(hw * cos) + Math.abs(hh * sin);
        double ey = Math.abs(hw * sin) + Math.abs(hh * cos);
        double x0 = a.width / 2.0 + (cxMm - ex) / mmPerPxX;
        double x1 = a.width / 2.0 + (cxMm + ex) / mmPerPxX;
        double y0 = a.height / 2.0 - (cyMm + ey) / mmPerPxY;
        double y1 = a.height / 2.0 - (cyMm - ey) / mmPerPxY;
        if (x0 < -0.5 || y0 < -0.5 || x1 > a.width - 0.5 || y1 > a.height - 0.5) {
            throw new IllegalArgumentException("pad is not entirely inside the image");
        }
        int i0 = (int) Math.floor(x0);
        int i1 = Math.min(a.width - 1, (int) Math.ceil(x1));
        int j0 = (int) Math.floor(y0);
        int j1 = Math.min(a.height - 1, (int) Math.ceil(y1));
        i0 = Math.max(0, i0);
        j0 = Math.max(0, j0);
        int rw = i1 - i0 + 1;
        int rh = j1 - j0 + 1;
        byte[] mat = new byte[rw * rh];
        Mat roi = a.material.submat(new Rect(i0, j0, rw, rh));
        roi.get(0, 0, mat);
        roi.release();
        double total = 0;
        double covered = 0;
        int s = PAD_SUBSAMPLES;
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                int inside = 0;
                for (int sv = 0; sv < s; sv++) {
                    double y = -(j + (sv + 0.5) / s - 0.5 - a.height / 2.0) * mmPerPxY - cyMm;
                    for (int su = 0; su < s; su++) {
                        double x = (i + (su + 0.5) / s - 0.5 - a.width / 2.0) * mmPerPxX - cxMm;
                        double u = x * cos + y * sin;
                        double v = -x * sin + y * cos;
                        if (Math.abs(u) <= hw && Math.abs(v) <= hh) {
                            inside++;
                        }
                    }
                }
                if (inside > 0) {
                    total += inside;
                    if (mat[(j - j0) * rw + (i - i0)] != 0) {
                        covered += inside;
                    }
                }
            }
        }
        if (total == 0) {
            throw new IllegalArgumentException("pad covers no pixels");
        }
        return covered / total;
    }

    // ------------------------------------------------------------------------------------------
    // Pipeline steps

    private int kernelSize() {
        if (!(settings.blurPx > 1)) {
            return 1;
        }
        int k = (int) Math.ceil(settings.blurPx);
        return (k % 2 == 0) ? k + 1 : k;
    }

    private static Mat toMat(float[] data, int w, int h, int k) {
        Mat m = new Mat(h, w, CvType.CV_32FC1);
        m.put(0, 0, data);
        if (k > 1) {
            Imgproc.GaussianBlur(m, m, new Size(k, k), 0);
        }
        return m;
    }

    /**
     * Estimates the shift d of "after" relative to "before" (after(x) ~ before(x - d)). Returns
     * {dx, dy, apply ? 1 : 0}.
     *
     * ECC (enhanced correlation coefficient: insensitive to gain/offset) with a translation
     * model, on grey images low-passed by REGISTRATION_SIGMA_PX (near-Nyquist texture aliases and
     * misleads the estimate; the coarse structure moves faithfully), cropped to the central
     * REGISTRATION_MAX_PIXELS for speed. Starts from 0 (or {@code init}); if that does not
     * converge, retries from a phase-correlation estimate. Measured on the synthetic scenes:
     * phase correlation alone was off by 0.3-0.8 px, ECC by &lt; 0.1 px, and ECC on downsampled
     * images diverged on low-contrast paper, hence full resolution.
     *
     * @param mask optional CV_8U mask (full resolution) of valid "after" pixels
     * @param init optional starting estimate
     */
    private double[] estimateShift(List<Mat> bm, List<Mat> am, Mat mask, double[] init,
            Analysis result) {
        Mat bg = lowPass(bm);
        Mat ag = lowPass(am);
        Rect crop = centralCrop(bg.cols(), bg.rows(), REGISTRATION_MAX_PIXELS);
        Mat bc = bg.submat(crop);
        Mat ac = ag.submat(crop);
        Mat mc = mask != null ? mask.submat(crop) : new Mat();
        try {
            double[] start = init != null ? new double[] {init[0], init[1]} : new double[2];
            double[] found = ecc(bc, ac, mc, start);
            if (found == null && init == null) {
                Mat b2 = bc.clone();
                Mat a2 = ac.clone();
                Mat none = new Mat();
                // No window: OpenCV 4.5.5 multiplies src1 by the window in place.
                Point p = Imgproc.phaseCorrelate(b2, a2, none);
                b2.release();
                a2.release();
                none.release();
                if (Math.hypot(p.x, p.y) <= settings.maxShiftPx) {
                    found = ecc(bc, ac, mc, new double[] {p.x, p.y});
                }
            }
            if (found == null) {
                result.eccCorrelation = Double.NaN;
                return new double[] {0, 0, 0};
            }
            result.eccCorrelation = found[2];
            double mag = Math.hypot(found[0], found[1]);
            boolean apply = found[2] >= MIN_ECC_CORRELATION && mag >= MIN_SHIFT_PX
                    && mag <= settings.maxShiftPx;
            return new double[] {found[0], found[1], apply ? 1 : 0};
        }
        finally {
            bc.release();
            ac.release();
            mc.release();
            bg.release();
            ag.release();
        }
    }

    /** One ECC run from {@code start}; returns {dx, dy, correlation} or null. */
    private double[] ecc(Mat b, Mat a, Mat mask, double[] start) {
        Mat warp = Mat.eye(2, 3, CvType.CV_32FC1);
        try {
            warp.put(0, 2, start[0]);
            warp.put(1, 2, start[1]);
            // Finds W with after(W x) ~ before(x); for a translation W x = x + d.
            double cc = Video.findTransformECC(b, a, warp, Video.MOTION_TRANSLATION,
                    new TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 50, 1e-6), mask, 1);
            double dx = warp.get(0, 2)[0];
            double dy = warp.get(1, 2)[0];
            if (Double.isNaN(dx) || Double.isNaN(dy) || Math.hypot(dx, dy) > settings.maxShiftPx) {
                return null;
            }
            return new double[] {dx, dy, cc};
        }
        catch (CvException e) {
            // Did not converge (e.g. featureless or uncorrelated images).
            return null;
        }
        finally {
            warp.release();
        }
    }

    private static Rect centralCrop(int w, int h, int maxPixels) {
        if ((long) w * h <= maxPixels) {
            return new Rect(0, 0, w, h);
        }
        double f = Math.sqrt((double) maxPixels / ((double) w * h));
        int cw = Math.max(16, (int) (w * f));
        int ch = Math.max(16, (int) (h * f));
        return new Rect((w - cw) / 2, (h - ch) / 2, cw, ch);
    }

    /** "after" resampled into the "before" frame (the input list itself if no shift applies). */
    private static List<Mat> warp(List<Mat> am, double[] shift) {
        if (shift[2] == 0) {
            return am;
        }
        Mat m = new Mat(2, 3, CvType.CV_64FC1);
        // after(x) ~ before(x - d): resample after at x + d.
        m.put(0, 0, 1, 0, -shift[0], 0, 1, -shift[1]);
        List<Mat> out = new ArrayList<>();
        for (Mat a : am) {
            Mat o = new Mat();
            Imgproc.warpAffine(a, o, m, a.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);
            out.add(o);
        }
        m.release();
        return out;
    }

    /** Grey mean of the channels, Gaussian low-passed. */
    private static Mat lowPass(List<Mat> channels) {
        Mat m = mean(channels);
        Mat out = new Mat();
        Imgproc.GaussianBlur(m, out, new Size(0, 0), REGISTRATION_SIGMA_PX);
        if (m != channels.get(0)) {
            m.release();
        }
        return out;
    }

    /**
     * Pixels where "before" has a strong edge (dilated by 1 px). There a deposit can match the
     * blurred edge colour somewhere across it, leaving a thin crack of low difference through a
     * dot; such cracks are bridged instead of trusted.
     */
    private static Mat edgeMask(List<Mat> bm, double thr) {
        Mat g = mean(bm);
        Mat dx = new Mat();
        Mat dy = new Mat();
        Mat mag = new Mat();
        Mat edges = new Mat();
        try {
            // Sobel 3x3 scaled by 1/8: grey levels per pixel.
            Imgproc.Sobel(g, dx, CvType.CV_32F, 1, 0, 3, 0.125);
            Imgproc.Sobel(g, dy, CvType.CV_32F, 0, 1, 3, 0.125);
            Core.magnitude(dx, dy, mag);
            Imgproc.threshold(mag, mag, 0.5 * thr, 255, Imgproc.THRESH_BINARY);
            mag.convertTo(edges, CvType.CV_8UC1);
            Mat d = dilate(edges, 1);
            edges.release();
            return d;
        }
        finally {
            if (g != bm.get(0)) {
                g.release();
            }
            dx.release();
            dy.release();
            mag.release();
        }
    }

    private static Mat mean(List<Mat> channels) {
        if (channels.size() == 1) {
            return channels.get(0);
        }
        Mat sum = channels.get(0).clone();
        for (int c = 1; c < channels.size(); c++) {
            Core.add(sum, channels.get(c), sum);
        }
        Core.multiply(sum, new Scalar(1.0 / channels.size()), sum);
        return sum;
    }

    /**
     * Robust least-squares fit of after = g*before + o + px*X + py*Y, X and Y in [-1, 1] across
     * the image. Returns {g, o, px, py}.
     */
    static double[] robustFit(float[] b, float[] a, int w, int h, int border) {
        int stride = Math.max(1, (int) Math.sqrt((double) w * h / FIT_SAMPLES));
        int cap = (w / stride + 1) * (h / stride + 1);
        int[] idx = new int[cap];
        int n = 0;
        for (int y = border; y < h - border; y += stride) {
            for (int x = border; x < w - border; x += stride) {
                int i = y * w + x;
                if (b[i] > 2 && b[i] < SATURATED && a[i] > 2 && a[i] < SATURATED) {
                    idx[n++] = i;
                }
            }
        }
        double[] identity;
        {
            float[] d = new float[Math.max(1, n)];
            for (int s = 0; s < n; s++) {
                d[s] = a[idx[s]] - b[idx[s]];
            }
            identity = new double[] {1, n > 0 ? median(d, n) : 0, 0, 0};
        }
        if (n < 16) {
            return identity;
        }
        double[] p = identity.clone();
        float[] res = new float[n];
        double cx = w / 2.0;
        double cy = h / 2.0;
        for (int iter = 0; iter < 5; iter++) {
            for (int s = 0; s < n; s++) {
                int i = idx[s];
                res[s] = (float) Math.abs(a[i] - eval(p, b[i], i % w, i / w, cx, cy));
            }
            double cutoff = Math.max(3 * 1.4826 * median(res.clone(), n), 4.0);
            double[][] m = new double[4][4];
            double[] v = new double[4];
            double sb = 0;
            double sbb = 0;
            int used = 0;
            for (int s = 0; s < n; s++) {
                if (res[s] > cutoff) {
                    continue;
                }
                int i = idx[s];
                double[] f = {b[i], 1, (i % w - cx) / cx, (i / w - cy) / cy};
                for (int r = 0; r < 4; r++) {
                    for (int c = 0; c < 4; c++) {
                        m[r][c] += f[r] * f[c];
                    }
                    v[r] += f[r] * a[i];
                }
                sb += b[i];
                sbb += (double) b[i] * b[i];
                used++;
            }
            if (used < 16) {
                break;
            }
            double varB = sbb / used - (sb / used) * (sb / used);
            double[] q;
            if (varB < 4) {
                // Almost uniform background: gain is not identifiable, keep g = 1.
                double[][] m3 = new double[3][3];
                double[] v3 = new double[3];
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        m3[r][c] = m[r + 1][c + 1];
                    }
                    // sum f_r*(a - b) = v_r - m[r][0]
                    v3[r] = v[r + 1] - m[r + 1][0];
                }
                double[] t = solve(m3, v3);
                q = t == null ? null : new double[] {1, t[0], t[1], t[2]};
            }
            else {
                q = solve(m, v);
            }
            if (q == null || !(q[0] > 0.5 && q[0] < 2.0)) {
                break;
            }
            p = q;
        }
        return p;
    }

    private static double eval(double[] p, double b, int x, int y, double cx, double cy) {
        return p[0] * b + p[1] + p[2] * (x - cx) / cx + p[3] * (y - cy) / cy;
    }

    private static void predict(float[] b, double[] p, int w, int h, float[] out) {
        double cx = w / 2.0;
        double cy = h / 2.0;
        for (int y = 0, i = 0; y < h; y++) {
            for (int x = 0; x < w; x++, i++) {
                out[i] = (float) eval(p, b[i], x, y, cx, cy);
            }
        }
    }

    private void findBlobs(Mat diffMat, float[] diff, Mat edges, byte[] edgeA, int w, int h, int k,
            Analysis result) {
        double thr = settings.diffThreshold;
        Mat seed = new Mat();
        Mat kernel3 = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(3, 3));
        Mat hierarchy = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            Imgproc.threshold(diffMat, seed, thr, 255, Imgproc.THRESH_BINARY);
            seed.convertTo(seed, CvType.CV_8UC1);
            Imgproc.morphologyEx(seed, seed, Imgproc.MORPH_OPEN, kernel3);
            Imgproc.morphologyEx(seed, seed, Imgproc.MORPH_CLOSE, kernel3);
            // Bridge cracks, but only across "before" edges (never between dots on a plain
            // background, and never creating a blob from nothing).
            Mat bridge = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,
                    new Size(2 * BRIDGE_PX + 1, 2 * BRIDGE_PX + 1));
            Mat closed = new Mat();
            Imgproc.morphologyEx(seed, closed, Imgproc.MORPH_CLOSE, bridge);
            Core.bitwise_and(closed, edges, closed);
            Core.bitwise_or(seed, closed, seed);
            closed.release();
            bridge.release();
            Imgproc.findContours(seed, contours, hierarchy, Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_NONE);
            int margin = k / 2 + 2 * GROW_PX + 3;
            for (MatOfPoint c : contours) {
                Blob blob = refine(c, diff, edgeA, w, h, k, margin, thr, result.material);
                if (blob != null) {
                    result.blobs.add(blob);
                }
            }
        }
        finally {
            seed.release();
            kernel3.release();
            hierarchy.release();
            release(contours);
        }
    }

    /**
     * Re-thresholds one seed blob at a local 50 % level, fills holes, measures it and paints it
     * into the material mask.
     *
     * A fixed threshold on a blurred edge biases the size: high-contrast dots come out too big,
     * low-contrast ones too small. The edge of a step sits where the blurred difference is half
     * way between the background and the material next to it, so each pixel's level is
     * bg + 0.5 * (M - bg), with M the local material contrast (3x3 mean, then max within
     * {@code k/2 + 2} px). Contrast varies inside one dot (e.g. a dot overlapping both a pad and
     * solder mask), so the seed's interior, eroded by the same radius, is always kept and only
     * the boundary is re-decided.
     */
    private static Blob refine(MatOfPoint seedContour, float[] diff, byte[] edgeA, int w, int h,
            int k, int margin, double thr, Mat material) {
        Rect br = Imgproc.boundingRect(seedContour);
        int x0 = Math.max(0, br.x - margin);
        int y0 = Math.max(0, br.y - margin);
        int x1 = Math.min(w, br.x + br.width + margin);
        int y1 = Math.min(h, br.y + br.height + margin);
        Rect roi = new Rect(x0, y0, x1 - x0, y1 - y0);
        int rw = roi.width;
        int rh = roi.height;
        int n = rw * rh;
        int rc = k / 2 + 2;
        List<Mat> temps = new ArrayList<>();
        List<MatOfPoint> pieces = new ArrayList<>();
        try {
            Mat comp = track(temps, Mat.zeros(rh, rw, CvType.CV_8UC1));
            Imgproc.drawContours(comp, Collections.singletonList(seedContour), 0, new Scalar(255),
                    Imgproc.FILLED, Imgproc.LINE_8, track(temps, new Mat()), 0, new Point(-x0, -y0));
            Mat grow = track(temps, dilate(comp, GROW_PX));
            Mat ringIn = track(temps, dilate(comp, GROW_PX + 1));
            Mat ringOut = track(temps, dilate(comp, margin - 1));
            Mat core = track(temps, erode(comp, rc));

            float[] d = new float[n];
            byte[] edge = new byte[n];
            for (int y = 0; y < rh; y++) {
                System.arraycopy(diff, (y0 + y) * w + x0, d, y * rw, rw);
                System.arraycopy(edgeA, (y0 + y) * w + x0, edge, y * rw, rw);
            }
            Mat dRoi = track(temps, new Mat(rh, rw, CvType.CV_32FC1));
            dRoi.put(0, 0, d);
            Mat local = track(temps, new Mat());
            Imgproc.medianBlur(dRoi, local, 5);
            Mat localMax = track(temps, dilate(local, rc));

            byte[] compA = new byte[n];
            byte[] growA = new byte[n];
            byte[] inA = new byte[n];
            byte[] outA = new byte[n];
            byte[] coreA = new byte[n];
            float[] maxA = new float[n];
            comp.get(0, 0, compA);
            grow.get(0, 0, growA);
            ringIn.get(0, 0, inA);
            ringOut.get(0, 0, outA);
            core.get(0, 0, coreA);
            localMax.get(0, 0, maxA);

            float[] ring = new float[n];
            float[] inside = new float[n];
            int nRing = 0;
            int nInside = 0;
            for (int i = 0; i < n; i++) {
                if (outA[i] != 0 && inA[i] == 0) {
                    ring[nRing++] = d[i];
                }
                if (compA[i] != 0) {
                    inside[nInside++] = d[i];
                }
            }
            Blob blob = new Blob();
            blob.background = nRing > 0 ? median(ring, nRing) : 0;
            blob.contrast = nInside > 0 ? median(inside, nInside) : 0;

            byte[] refined = new byte[n];
            double floor = 0.5 * thr;
            float[] levels = new float[n];
            int nLevels = 0;
            for (int i = 0; i < n; i++) {
                if (coreA[i] != 0 || (compA[i] != 0 && edge[i] != 0 && d[i] <= thr)) {
                    // Interior, or a bridged crack (below threshold, on a "before" edge): keep.
                    refined[i] = (byte) 255;
                }
                else if (growA[i] != 0) {
                    double level = Math.max(blob.background + 0.5 * (maxA[i] - blob.background),
                            floor);
                    if (d[i] > level) {
                        refined[i] = (byte) 255;
                    }
                    if (compA[i] != 0) {
                        levels[nLevels++] = (float) level;
                    }
                }
            }
            blob.level = nLevels > 0 ? median(levels, nLevels) : floor;
            Mat refinedMat = track(temps, new Mat(rh, rw, CvType.CV_8UC1));
            refinedMat.put(0, 0, refined);
            Mat hier = track(temps, new Mat());
            Imgproc.findContours(refinedMat, pieces, hier, Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_NONE);
            if (pieces.isEmpty()) {
                return null;
            }
            Mat filledAll = track(temps, Mat.zeros(rh, rw, CvType.CV_8UC1));
            Imgproc.drawContours(filledAll, pieces, -1, new Scalar(255), Imgproc.FILLED);
            Mat matRoi = track(temps, material.submat(roi));
            Core.bitwise_or(matRoi, filledAll, matRoi);

            int best = -1;
            double bestArea = -1;
            for (int i = 0; i < pieces.size(); i++) {
                double ar = Imgproc.contourArea(pieces.get(i));
                if (ar > bestArea) {
                    bestArea = ar;
                    best = i;
                }
            }
            Mat filled = track(temps, Mat.zeros(rh, rw, CvType.CV_8UC1));
            Imgproc.drawContours(filled, pieces, best, new Scalar(255), Imgproc.FILLED);
            Moments mo = Imgproc.moments(filled, true);
            if (mo.m00 <= 0) {
                return null;
            }
            blob.areaPx = mo.m00;
            blob.cxPx = mo.m10 / mo.m00 + x0;
            blob.cyPx = mo.m01 / mo.m00 + y0;
            MatOfPoint2f poly = new MatOfPoint2f(pieces.get(best).toArray());
            double perimeter = Imgproc.arcLength(poly, true);
            poly.release();
            blob.circularity = perimeter > 0
                    ? Math.min(1.0, 4 * Math.PI * bestArea / (perimeter * perimeter))
                    : 0;
            return blob;
        }
        finally {
            release(temps);
            release(pieces);
        }
    }

    private static Mat erode(Mat src, int radius) {
        Mat dst = new Mat();
        Mat k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,
                new Size(2 * radius + 1, 2 * radius + 1));
        Imgproc.erode(src, dst, k, new Point(-1, -1), 1, Core.BORDER_CONSTANT, new Scalar(0));
        k.release();
        return dst;
    }

    private static Mat dilate(Mat src, int radius) {
        Mat dst = new Mat();
        if (radius <= 0) {
            src.copyTo(dst);
            return dst;
        }
        Mat k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE,
                new Size(2 * radius + 1, 2 * radius + 1));
        Imgproc.dilate(src, dst, k);
        k.release();
        return dst;
    }

    // ------------------------------------------------------------------------------------------
    // Helpers

    private static Mat track(List<Mat> temps, Mat m) {
        temps.add(m);
        return m;
    }

    private static void release(List<? extends Mat> mats) {
        for (Mat m : mats) {
            m.release();
        }
    }

    /** Median of the first n values (reorders the array). */
    static float median(float[] v, int n) {
        Arrays.sort(v, 0, n);
        return (n % 2 == 1) ? v[n / 2] : 0.5f * (v[n / 2 - 1] + v[n / 2]);
    }

    /** Solves m x = v by Gaussian elimination with partial pivoting; null if singular. */
    static double[] solve(double[][] m, double[] v) {
        int n = v.length;
        double[][] a = new double[n][];
        double[] b = v.clone();
        for (int i = 0; i < n; i++) {
            a[i] = m[i].clone();
        }
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) {
                    piv = r;
                }
            }
            if (Math.abs(a[piv][col]) < 1e-12) {
                return null;
            }
            double[] t = a[col];
            a[col] = a[piv];
            a[piv] = t;
            double tb = b[col];
            b[col] = b[piv];
            b[piv] = tb;
            for (int r = col + 1; r < n; r++) {
                double f = a[r][col] / a[col][col];
                for (int c = col; c < n; c++) {
                    a[r][c] -= f * a[col][c];
                }
                b[r] -= f * b[col];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int c = r + 1; c < n; c++) {
                s -= a[r][c] * x[c];
            }
            x[r] = s / a[r][r];
        }
        return x;
    }

    private static void checkScale(double mmPerPxX, double mmPerPxY) {
        if (!(mmPerPxX > 0) || !(mmPerPxY > 0) || Double.isInfinite(mmPerPxX)
                || Double.isInfinite(mmPerPxY)) {
            throw new IllegalArgumentException("mm per pixel must be positive and finite");
        }
    }

    static boolean isGray(BufferedImage img) {
        return img.getRaster().getNumBands() == 1
                && img.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY;
    }

    /**
     * Float channels 0..255. Grey images are read from the raster directly (getRGB would apply a
     * linear-to-sRGB curve to TYPE_BYTE_GRAY).
     */
    static float[][] channels(BufferedImage img, boolean color) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (isGray(img)) {
            Raster r = img.getRaster();
            int bits = img.getColorModel().getComponentSize(0);
            float scale = (float) (255.0 / ((1L << bits) - 1));
            int[] s = r.getSamples(r.getMinX(), r.getMinY(), w, h, 0, (int[]) null);
            float[] g = new float[w * h];
            for (int i = 0; i < g.length; i++) {
                g[i] = s[i] * scale;
            }
            return color ? new float[][] {g, g.clone(), g.clone()} : new float[][] {g};
        }
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        if (!color) {
            float[] g = new float[w * h];
            for (int i = 0; i < g.length; i++) {
                int p = rgb[i];
                g[i] = 0.299f * ((p >> 16) & 0xff) + 0.587f * ((p >> 8) & 0xff)
                        + 0.114f * (p & 0xff);
            }
            return new float[][] {g};
        }
        float[] bl = new float[w * h];
        float[] gr = new float[w * h];
        float[] re = new float[w * h];
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            re[i] = (p >> 16) & 0xff;
            gr[i] = (p >> 8) & 0xff;
            bl[i] = p & 0xff;
        }
        return new float[][] {bl, gr, re};
    }
}
