package org.openpnp.instancelabs.vision;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Moments;

/**
 * Finds the round copper fiducial in a top-camera frame. A port of the bench detector
 * {@code tools/fid.py}, line by line:
 *
 * <ol>
 * <li>Grey ({@code COLOR_BGR2GRAY}), Gaussian blur 7x7.</li>
 * <li>Threshold sweep 250, 240 ... 130 ({@code THRESH_BINARY}), {@code findContours(RETR_LIST,
 * CHAIN_APPROX_NONE)} at each level.</li>
 * <li>A contour is a candidate if its area lies in the window pi/4 (0.76 d)^2 .. pi/4 (1.20 d)^2,
 * its circularity 4 pi A / P^2 is at least 0.82 and its fill A / (pi r^2) (minimum enclosing
 * circle) at least 0.80.</li>
 * <li>Score = circularity x contrast, where contrast = mean grey in the disk of radius int(r)
 * minus the mean in the ring int(1.5 r) .. int(2 r) around the centroid (moments). The best score
 * wins; ties keep the first, as fid.py.</li>
 * </ol>
 *
 * Where it departs from fid.py:
 * <ul>
 * <li>The area window is in mm^2, so it does not depend on the camera scale. At the bench scale
 * (0.01074 x 0.01106 mm/px) it is 3822 .. 9522 px^2; fid.py has 3800 .. 9500.</li>
 * <li>Only candidates whose centre lies within the search radius count, and whose 2r ring lies
 * inside the frame (a mark at the edge cannot be scored).</li>
 * <li>A candidate must be brighter than its ring (contrast &gt; 0). fid.py also returns the inner
 * contour of a dark hole, e.g. the centre of a round via, with a negative score.</li>
 * </ul>
 *
 * Offsets follow OpenPnP ({@code VisionUtils.getPixelCenterOffsets}): mm from the image centre
 * (pixel index - size/2), +x right, +y up. Pixel positions follow OpenCV (pixel centres at integer
 * coordinates).
 */
public final class FiducialDetector {
    static {
        nu.pattern.OpenCV.loadLocally();
    }

    /** Candidates closer than this to the winner are the same mark (other threshold levels). */
    static final double SAME_MARK_MM = 0.3;

    private FiducialDetector() {}

    /** The fid.py numbers. A test seam, not persisted. */
    public static final class Settings {
        /** Nominal copper diameter, mm. */
        public double diameterMm = 1.0;
        /** Area window as a diameter range, factors of diameterMm (fid.py 3800 .. 9500 px^2). */
        public double minDiameterFactor = 0.76, maxDiameterFactor = 1.20;
        public double minCircularity = 0.82, minFill = 0.80;
        public int thresholdHigh = 250, thresholdLow = 130, thresholdStep = 10, blurKernelPx = 7;
        /** The caller fails AMBIGUOUS when secondScore >= this x score. */
        public double ambiguousScoreRatio = 0.9;

        public Settings copy() {
            Settings c = new Settings();
            c.diameterMm = diameterMm;
            c.minDiameterFactor = minDiameterFactor;
            c.maxDiameterFactor = maxDiameterFactor;
            c.minCircularity = minCircularity;
            c.minFill = minFill;
            c.thresholdHigh = thresholdHigh;
            c.thresholdLow = thresholdLow;
            c.thresholdStep = thresholdStep;
            c.blurKernelPx = blurKernelPx;
            c.ambiguousScoreRatio = ambiguousScoreRatio;
            return c;
        }

        /** Throws IllegalArgumentException if a field is out of range. */
        public void validate() {
            if (!(diameterMm > 0) || Double.isInfinite(diameterMm)) {
                throw new IllegalArgumentException("diameterMm must be positive, got " + diameterMm);
            }
            if (!(minDiameterFactor > 0) || !(maxDiameterFactor > minDiameterFactor)
                    || Double.isInfinite(maxDiameterFactor)) {
                throw new IllegalArgumentException("diameter factors must be 0 < min < max, got "
                        + minDiameterFactor + " .. " + maxDiameterFactor);
            }
            if (!(minCircularity >= 0 && minCircularity <= 1) || !(minFill >= 0 && minFill <= 1)) {
                throw new IllegalArgumentException("minCircularity and minFill must be 0..1, got "
                        + minCircularity + ", " + minFill);
            }
            if (thresholdLow < 0 || thresholdHigh > 255 || thresholdLow > thresholdHigh
                    || thresholdStep < 1) {
                throw new IllegalArgumentException("thresholds must be 0 <= low <= high <= 255,"
                        + " step >= 1, got " + thresholdLow + ".." + thresholdHigh + " step "
                        + thresholdStep);
            }
            if (blurKernelPx < 1 || blurKernelPx % 2 == 0) {
                throw new IllegalArgumentException("blurKernelPx must be odd and >= 1, got "
                        + blurKernelPx);
            }
            if (!(ambiguousScoreRatio > 0 && ambiguousScoreRatio <= 1)) {
                throw new IllegalArgumentException("ambiguousScoreRatio must be in (0, 1], got "
                        + ambiguousScoreRatio);
            }
        }
    }

    /** The best mark of one frame. */
    public static final class Detection {
        /** Centroid (moments), frame pixels. */
        public double xPx, yPx;
        /** From the frame centre, OpenPnP convention (+x right, +y up). */
        public double offsetXMm, offsetYMm;
        /**
         * Minimum enclosing circle diameter (fid.py's d), converted with the geometric mean of the
         * two scales.
         */
        public double diameterMm;
        public double circularity, fill, contrast, score;
        /** The threshold level that produced the winning contour. */
        public int threshold;
        /** Best score of another mark more than 0.3 mm away, 0 if none. */
        public double secondScore;
        /** Contours that passed every filter, over all threshold levels. */
        public int candidates;

        /** secondScore >= ratio x score: another mark is about as good. */
        public boolean isAmbiguous(double ratio) {
            return secondScore >= ratio * score;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "Fiducial[px=%.2f,%.2f off=%.4f,%.4f mm d=%.3f mm circ=%.3f fill=%.3f"
                            + " contrast=%.1f score=%.1f t=%d second=%.1f n=%d]",
                    xPx, yPx, offsetXMm, offsetYMm, diameterMm, circularity, fill, contrast, score,
                    threshold, secondScore, candidates);
        }
    }

    /**
     * Best fiducial whose centre lies within searchRadiusMm of (centreXMm, centreYMm) (mm from the
     * frame centre) and whose 2r ring lies inside the frame; null if none. A radius of
     * Double.POSITIVE_INFINITY searches the whole frame. Null settings mean the defaults.
     */
    public static Detection detect(BufferedImage img, double mmPerPxX, double mmPerPxY,
            double centreXMm, double centreYMm, double searchRadiusMm, Settings s) {
        if (img == null) {
            throw new IllegalArgumentException("image is required");
        }
        if (!(mmPerPxX > 0) || !(mmPerPxY > 0) || Double.isInfinite(mmPerPxX)
                || Double.isInfinite(mmPerPxY)) {
            throw new IllegalArgumentException("mm per pixel must be positive and finite");
        }
        if (!Double.isFinite(centreXMm) || !Double.isFinite(centreYMm) || !(searchRadiusMm > 0)) {
            throw new IllegalArgumentException("search centre must be finite and the radius positive");
        }
        if (s == null) {
            s = new Settings();
        }
        s.validate();
        int w = img.getWidth();
        int h = img.getHeight();
        double pxArea = mmPerPxX * mmPerPxY;
        double minArea = Math.PI / 4 * sq(s.minDiameterFactor * s.diameterMm);
        double maxArea = Math.PI / 4 * sq(s.maxDiameterFactor * s.diameterMm);
        List<double[]> marks = new ArrayList<>();
        Detection best = null;
        Mat g = grey(img);
        Mat th = new Mat();
        try {
            Imgproc.GaussianBlur(g, g, new Size(s.blurKernelPx, s.blurKernelPx), 0);
            for (int t = s.thresholdHigh; t >= s.thresholdLow; t -= s.thresholdStep) {
                Imgproc.threshold(g, th, t, 255, Imgproc.THRESH_BINARY);
                List<MatOfPoint> contours = new ArrayList<>();
                Mat hierarchy = new Mat();
                Imgproc.findContours(th, contours, hierarchy, Imgproc.RETR_LIST,
                        Imgproc.CHAIN_APPROX_NONE);
                hierarchy.release();
                for (MatOfPoint c : contours) {
                    try {
                        Detection d = candidate(g, c, t, w, h, mmPerPxX, mmPerPxY, pxArea, minArea,
                                maxArea, centreXMm, centreYMm, searchRadiusMm, s);
                        if (d == null) {
                            continue;
                        }
                        marks.add(new double[] {d.xPx, d.yPx, d.score});
                        if (best == null || d.score > best.score) {
                            best = d;
                        }
                    }
                    finally {
                        c.release();
                    }
                }
            }
        }
        finally {
            g.release();
            th.release();
        }
        if (best == null) {
            return null;
        }
        best.candidates = marks.size();
        for (double[] m : marks) {
            double dist = Math.hypot((m[0] - best.xPx) * mmPerPxX, (m[1] - best.yPx) * mmPerPxY);
            if (dist > SAME_MARK_MM && m[2] > best.secondScore) {
                best.secondScore = m[2];
            }
        }
        return best;
    }

    /** One contour through fid.py's filters and score; null if it fails any of them. */
    private static Detection candidate(Mat g, MatOfPoint c, int t, int w, int h, double mmPerPxX,
            double mmPerPxY, double pxArea, double minArea, double maxArea, double centreXMm,
            double centreYMm, double searchRadiusMm, Settings s) {
        double a = Imgproc.contourArea(c);
        double areaMm = a * pxArea;
        if (!(areaMm > minArea && areaMm < maxArea)) {
            return null;
        }
        MatOfPoint2f c2 = new MatOfPoint2f(c.toArray());
        double circ;
        Point centre = new Point();
        float[] radius = new float[1];
        try {
            double p = Imgproc.arcLength(c2, true);
            circ = 4 * Math.PI * a / (p * p);
            if (circ < s.minCircularity) {
                return null;
            }
            Imgproc.minEnclosingCircle(c2, centre, radius);
        }
        finally {
            c2.release();
        }
        double r = radius[0];
        double fill = a / (Math.PI * r * r);
        if (fill < s.minFill) {
            return null;
        }
        Moments m = Imgproc.moments(c);
        double cx = m.m10 / m.m00;
        double cy = m.m01 / m.m00;
        double offX = (cx - w / 2.0) * mmPerPxX;
        double offY = -(cy - h / 2.0) * mmPerPxY;
        if (!(Math.hypot(offX - centreXMm, offY - centreYMm) <= searchRadiusMm)) {
            return null;
        }
        int ix = (int) cx;
        int iy = (int) cy;
        int r2 = (int) (2 * r);
        if (ix - r2 < 0 || iy - r2 < 0 || ix + r2 >= w || iy + r2 >= h) {
            return null;
        }
        double con = contrast(g, ix, iy, (int) r, (int) (1.5 * r), r2);
        if (!(con > 0)) {
            return null;
        }
        Detection d = new Detection();
        d.xPx = cx;
        d.yPx = cy;
        d.offsetXMm = offX;
        d.offsetYMm = offY;
        d.diameterMm = 2 * r * Math.sqrt(pxArea);
        d.circularity = circ;
        d.fill = fill;
        d.contrast = con;
        d.score = circ * con;
        d.threshold = t;
        return d;
    }

    /**
     * fid.py's contrast: mean grey in the filled circle of radius rIn minus the mean in the
     * filled circle of radius rOut with the filled circle of radius rHole cleared, all centred on
     * (ix, iy) and drawn with cv2.circle. Drawn in the ring's bounding box only, which gives the
     * same pixels as fid.py's full-frame masks because the centre and radii are integers and the
     * box lies inside the frame.
     */
    private static double contrast(Mat g, int ix, int iy, int rIn, int rHole, int rOut) {
        Rect box = new Rect(ix - rOut, iy - rOut, 2 * rOut + 1, 2 * rOut + 1);
        Mat roi = g.submat(box);
        Mat disk = Mat.zeros(box.height, box.width, CvType.CV_8UC1);
        Mat ring = Mat.zeros(box.height, box.width, CvType.CV_8UC1);
        try {
            Point p = new Point(rOut, rOut);
            Imgproc.circle(disk, p, rIn, new Scalar(255), -1);
            Imgproc.circle(ring, p, rOut, new Scalar(255), -1);
            Imgproc.circle(ring, p, rHole, new Scalar(0), -1);
            return Core.mean(roi, disk).val[0] - Core.mean(roi, ring).val[0];
        }
        finally {
            roi.release();
            disk.release();
            ring.release();
        }
    }

    /**
     * 8-bit grey Mat: OpenCV's COLOR_BGR2GRAY of the BGR pixels, as fid.py after cv2.imread. Grey
     * images are used as they are.
     */
    static Mat grey(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (DotInspector.isGray(img) && img.getColorModel().getComponentSize(0) == 8) {
            Raster r = img.getRaster();
            byte[] px = (byte[]) r.getDataElements(r.getMinX(), r.getMinY(), w, h, null);
            Mat g = new Mat(h, w, CvType.CV_8UC1);
            g.put(0, 0, px);
            return g;
        }
        BufferedImage bgr = img;
        if (img.getType() != BufferedImage.TYPE_3BYTE_BGR || img.getRaster().getParent() != null
                || ((DataBufferByte) img.getRaster().getDataBuffer()).getData().length != w * h
                        * 3) {
            // Also copies sub-images, whose shared buffer is larger than the image.
            bgr = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g2 = bgr.createGraphics();
            g2.drawImage(img, 0, 0, null);
            g2.dispose();
        }
        Mat m = new Mat(h, w, CvType.CV_8UC3);
        m.put(0, 0, ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData());
        Mat g = new Mat();
        Imgproc.cvtColor(m, g, Imgproc.COLOR_BGR2GRAY);
        m.release();
        return g;
    }

    private static double sq(double v) {
        return v * v;
    }
}
