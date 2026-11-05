package org.openpnp.instancelabs.vision;

import java.awt.image.BufferedImage;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * BR-94: the needle's end and N1's tip seen from below by the bottom camera, in Process's method (runs/p37/bin/np1.py,
 * tools/parallax_fit.py, tools/parallax_k.py). The needle's end is the brightest small spot (a white top-hat) near where
 * it is expected; N1 is followed by a high-pass template. The head steps by known amounts: the least-squares fit of the
 * pixel against the step gives M (px per head mm) at that height, and how well the frames agree. The scale k = sqrt|det
 * M| falls as a tip moves away from the camera (k = f / D), so K, the sum of the two tools' raw Z where both tips are the
 * same distance from the camera, comes from 1/k against raw Z for each tool. Pixel positions follow OpenCV (pixel centres
 * at integer coordinates).
 */
public final class EndFromBelow {
    static {
        nu.pattern.OpenCV.loadLocally();
    }

    /** np1.py: the grey frame is blurred 5 x 5 before the top-hat; the end's centre is weighted over 13 x 13 px. */
    static final int BLUR_PX = 5, CENTRE_HALF_PX = 6;
    /** parallax_fit.py: the high-pass subtracts a Gaussian blur of sigma 6 px. */
    static final double HIGH_PASS_SIGMA_PX = 6;

    private EndFromBelow() {
    }

    /** The needle's end in one frame (px), the top-hat's peak there and the sd of the rest of the search disc. */
    public static final class Spot {
        public final double x, y, peak, backgroundSd;

        Spot(double x, double y, double peak, double backgroundSd) {
            this.x = x;
            this.y = y;
            this.peak = peak;
            this.backgroundSd = backgroundSd;
        }
    }

    /**
     * np1.py's spot(): the grey frame blurred, its white top-hat (an ellipse {@code kernelPx} across) inside the disc of
     * {@code radiusPx} around (nearX, nearY) (truncated to whole pixels, as np1.py's int()); its first maximum, then the
     * centre of the 13 x 13 px around it weighted by the top-hat above half that peak. The background sd is the top-hat's
     * over the disc without the kernel-sized square around the peak.
     */
    public static Spot spot(BufferedImage img, double nearX, double nearY, int radiusPx, int kernelPx) {
        return spot(topHat(img, kernelPx), img.getWidth(), img.getHeight(), nearX, nearY, radiusPx, kernelPx);
    }

    /** np1.py's white top-hat of the blurred grey frame (an ellipse {@code kernelPx} across), row by row. */
    static byte[] topHat(BufferedImage img, int kernelPx) {
        Mat g = FiducialDetector.grey(img);
        Mat b = new Mat(), th = new Mat();
        Mat k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(kernelPx, kernelPx));
        try {
            Imgproc.GaussianBlur(g, b, new Size(BLUR_PX, BLUR_PX), 0);
            Imgproc.morphologyEx(b, th, Imgproc.MORPH_TOPHAT, k);
            byte[] t = new byte[img.getWidth() * img.getHeight()];
            th.get(0, 0, t);
            return t;
        }
        finally {
            g.release();
            b.release();
            th.release();
            k.release();
        }
    }

    /** {@link #spot(BufferedImage, double, double, int, int)} in a top-hat already made. */
    static Spot spot(byte[] t, int w, int h, double nearX, double nearY, int radiusPx, int kernelPx) {
        Mat disc = Mat.zeros(h, w, CvType.CV_8UC1);
        try {
            Imgproc.circle(disc, new Point((int) nearX, (int) nearY), radiusPx, new Scalar(1), -1);
            byte[] m = new byte[w * h];
            disc.get(0, 0, m);
            int best = -1, at = -1;
            for (int i = 0; i < t.length; i++) {
                int v = m[i] != 0 ? t[i] & 0xff : 0;
                if (v > best) {
                    best = v;
                    at = i;
                }
            }
            int u = at % w, v = at / w;
            double sw = 0, sx = 0, sy = 0;
            for (int y = Math.max(v - CENTRE_HALF_PX, 0); y <= Math.min(v + CENTRE_HALF_PX, h - 1); y++) {
                for (int x = Math.max(u - CENTRE_HALF_PX, 0); x <= Math.min(u + CENTRE_HALF_PX, w - 1); x++) {
                    int i = y * w + x;
                    double c = Math.max((m[i] != 0 ? t[i] & 0xff : 0) - 0.5 * best, 0);
                    sw += c;
                    sx += c * x;
                    sy += c * y;
                }
            }
            int half = kernelPx / 2;
            double n = 0, s = 0, ss = 0;
            for (int i = 0; i < t.length; i++) {
                if (m[i] != 0 && (Math.abs(i % w - u) > half || Math.abs(i / w - v) > half)) {
                    double c = t[i] & 0xff;
                    n++;
                    s += c;
                    ss += c * c;
                }
            }
            double sd = n > 0 ? Math.sqrt(Math.max(ss / n - (s / n) * (s / n), 0)) : 0;
            return new Spot(sw > 0 ? sx / sw : Double.NaN, sw > 0 ? sy / sw : Double.NaN, best, sd);
        }
        finally {
            disc.release();
        }
    }

    /** N1's tip in one frame: where the template matched best (px, sub-pixel) and the normalised correlation there. */
    public static final class Track {
        public final double x, y, score;

        Track(double x, double y, double score) {
            this.x = x;
            this.y = y;
            this.score = score;
        }
    }

    /** parallax_fit.py's high-pass: the grey frame minus its Gaussian blur (float). */
    static Mat highPass(BufferedImage img) {
        Mat g = FiducialDetector.grey(img), f = new Mat(), blur = new Mat();
        g.convertTo(f, CvType.CV_32F);
        g.release();
        Imgproc.GaussianBlur(f, blur, new Size(0, 0), HIGH_PASS_SIGMA_PX);
        Core.subtract(f, blur, f);
        blur.release();
        return f;
    }

    /**
     * parallax_fit.py's template: the high-pass frame's square of 2 {@code half} px at (cx, cy) (rows and columns from
     * c − half to c + half − 1); null when it does not lie inside the frame.
     */
    public static Mat template(BufferedImage c0, int cx, int cy, int half) {
        if (cx - half < 0 || cy - half < 0 || cx + half > c0.getWidth() || cy + half > c0.getHeight()) {
            return null;
        }
        Mat hp = highPass(c0);
        Mat t = hp.submat(new Rect(cx - half, cy - half, 2 * half, 2 * half)).clone();
        hp.release();
        return t;
    }

    /**
     * parallax_fit.py's match: the template (taken at (cx, cy)) searched {@code searchPx} around there by normalised
     * cross-correlation, the best place refined by a parabola through its neighbours (at most a pixel). A window that
     * does not lie inside the frame gives NaN.
     */
    public static Track track(BufferedImage img, Mat tpl, int cx, int cy, int half, int searchPx) {
        int x0 = cx - half - searchPx, y0 = cy - half - searchPx, side = 2 * (half + searchPx);
        if (x0 < 0 || y0 < 0 || x0 + side > img.getWidth() || y0 + side > img.getHeight()) {
            return new Track(Double.NaN, Double.NaN, Double.NaN);
        }
        Mat hp = highPass(img), r = new Mat();
        try {
            Mat win = hp.submat(new Rect(x0, y0, side, side));
            Imgproc.matchTemplate(win, tpl, r, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mm = Core.minMaxLoc(r);
            int mx = (int) mm.maxLoc.x, my = (int) mm.maxLoc.y;
            float[] c = new float[(int) r.total()];
            r.get(0, 0, c);
            int n = r.cols();
            double sx = 0, sy = 0;
            if (mx > 0 && mx < n - 1 && my > 0 && my < r.rows() - 1) {
                sx = sub(c[my * n + mx - 1], c[my * n + mx], c[my * n + mx + 1]);
                sy = sub(c[(my - 1) * n + mx], c[my * n + mx], c[(my + 1) * n + mx]);
            }
            return new Track(cx - searchPx + mx + sx, cy - searchPx + my + sy, mm.maxVal);
        }
        finally {
            hp.release();
            r.release();
        }
    }

    /** The parabola's vertex through three neighbours, within a pixel; 0 where it is not a maximum. */
    private static double sub(double a, double b, double c) {
        double d2 = c - 2 * b + a;
        return d2 < 0 ? Math.max(-1, Math.min(1, -(c - a) / 2 / d2)) : 0;
    }

    /** pixel = c + M (dx, dy) over a tool's frames at one height (np1.py's least squares). */
    public static final class StepFit {
        /** M[i][j]: pixel i (x, y) per head mm along j (X, Y). */
        public final double[][] m;
        /** The pixel at step (0, 0); the rms of the frames' distance from the fit (px). */
        public final double cx, cy, residualPx;

        StepFit(double[][] m, double cx, double cy, double residualPx) {
            this.m = m;
            this.cx = cx;
            this.cy = cy;
            this.residualPx = residualPx;
        }

        /** sqrt|det M|: px per mm at this height (parallax_k.py's k). */
        public double pxPerMm() {
            return Math.sqrt(Math.abs(det()));
        }

        double det() {
            return m[0][0] * m[1][1] - m[0][1] * m[1][0];
        }

        /**
         * Where the tip is from the point pixel (px, py) looks at, in head mm: M^-1 (c − p) (np1.py: OFF_true = OFF0 +
         * M^-1 (spot − BC), BC the pixel of the camera's location).
         */
        public double[] offsetFrom(double px, double py) {
            double d = det(), ex = cx - px, ey = cy - py;
            return new double[] {(m[1][1] * ex - m[0][1] * ey) / d, (-m[1][0] * ex + m[0][0] * ey) / d};
        }
    }

    /** The least-squares fit of each frame's pixel {x, y} against its head step {dx, dy} (mm). */
    public static StepFit fit(double[][] steps, double[][] px) {
        double[][] a = new double[3][3];
        double[] bx = new double[3], by = new double[3];
        for (int i = 0; i < steps.length; i++) {
            double[] r = {steps[i][0], steps[i][1], 1};
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    a[j][k] += r[j] * r[k];
                }
                bx[j] += r[j] * px[i][0];
                by[j] += r[j] * px[i][1];
            }
        }
        double[] x = solve3(a, bx), y = solve3(a, by);
        double ss = 0;
        for (int i = 0; i < steps.length; i++) {
            double ex = x[0] * steps[i][0] + x[1] * steps[i][1] + x[2] - px[i][0];
            double ey = y[0] * steps[i][0] + y[1] * steps[i][1] + y[2] - px[i][1];
            ss += ex * ex + ey * ey;
        }
        return new StepFit(new double[][] {{x[0], x[1]}, {y[0], y[1]}}, x[2], y[2], Math.sqrt(ss / steps.length));
    }

    /** Solves a x = b (3 x 3, Cramer's rule); NaN when singular. */
    static double[] solve3(double[][] a, double[] b) {
        double d = det3(a[0], a[1], a[2]);
        double[] x = new double[3];
        for (int c = 0; c < 3; c++) {
            double[][] m = {a[0].clone(), a[1].clone(), a[2].clone()};
            for (int r = 0; r < 3; r++) {
                m[r][c] = b[r];
            }
            x[c] = d == 0 ? Double.NaN : det3(m[0], m[1], m[2]) / d;
        }
        return x;
    }

    private static double det3(double[] a, double[] b, double[] c) {
        return a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1]
                - b[1] * c[0]);
    }

    /** A straight line's least-squares {slope, intercept} through (x, y); NaN with fewer than 2 distinct x. */
    public static double[] line(double[] x, double[] y) {
        double n = x.length, sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < x.length; i++) {
            sx += x[i];
            sy += y[i];
            sxx += x[i] * x[i];
            sxy += x[i] * y[i];
        }
        double d = n * sxx - sx * sx;
        double slope = Math.abs(d) < 1e-12 ? Double.NaN : (n * sxy - sx * sy) / d;
        return new double[] {slope, (sy - slope * sx) / n};
    }

    /** K by parallax: its value and its spread over N1's heights (mm), and the focal length f it implies (px). */
    public static final class Parallax {
        public final double k, spread, fPx;

        Parallax(double k, double spread, double fPx) {
            this.k = k;
            this.spread = spread;
            this.fPx = fPx;
        }
    }

    /**
     * parallax_k.py: 1/k is a straight line in raw Z for each tool (D = f/k; N1 farther as raw Z rises, the needle
     * nearer); the needle's raw Z at N1's distance at each of N1's raw Z, plus that raw Z, is K. It is a straight line in
     * N1's raw Z, so parallax_k.py's median over 13 points between N1's lowest and highest height is its value at their
     * middle, and its spread the difference between its ends.
     */
    public static Parallax parallaxK(double[] n1Z, double[] n1PxPerMm, double[] ndZ, double[] ndPxPerMm) {
        double[] s1 = line(n1Z, inverse(n1PxPerMm)), s2 = line(ndZ, inverse(ndPxPerMm));
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double z : n1Z) {
            lo = Math.min(lo, z);
            hi = Math.max(hi, z);
        }
        double kLo = kAt(lo, s1, s2), kHi = kAt(hi, s1, s2);
        return new Parallax(kAt((lo + hi) / 2, s1, s2), Math.abs(kHi - kLo), 1 / s1[0]);
    }

    private static double kAt(double zn, double[] s1, double[] s2) {
        return zn + (s1[0] * zn + s1[1] - s2[1]) / s2[0];
    }

    private static double[] inverse(double[] v) {
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = 1 / v[i];
        }
        return r;
    }
}
