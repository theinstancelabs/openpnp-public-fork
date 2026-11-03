package org.openpnp.instancelabs.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * The spot measurement of {@link DotInspector#inspectSpots}, on the new material of one before/after pair:
 *
 * <ol>
 * <li>The material is opened with a disc {@code cutMm} across: strings and necks thinner than it go, while a round
 * dot wider than it keeps its outline (an opening keeps every disc that fits), so a dot comes out without its
 * strings.</li>
 * <li>A piece left that reaches within half the spot radius of a spot belongs to that spot. A spot whose pieces all
 * reach another spot too holds dots joined by more than a string (merged, or a neck thicker than the cut): not
 * measurable. A piece that reaches no spot belongs to the spot its centroid is nearest, within the spot radius (a dot
 * laid off its spot).</li>
 * <li>A spot without a piece: a separate blob too small for the cut (a weak dot), nearer this spot than any other and
 * not part of a cut dot's paste, is its dot; else any paste within the spot radius apart from the cut dots (and a
 * margin of half the cut around them) is paste that is no dot (strings, a smear); else the spot has no paste.</li>
 * <li>A dot passes the inspector's size and circularity filters and lies inside the frame, or it is not measurable;
 * a spot whose surroundings are not all in the frame never reads "no paste".</li>
 * </ol>
 */
final class SpotMeasure {
    private final DotInspectorSettings settings;
    private final int w, h;
    private final double sx, sy;
    private final double cutMm, radiusMm;
    /** Spot positions in pixel index coordinates. */
    private final double[] px, py;

    private SpotMeasure(int w, int h, DotInspectorSettings settings, double sx, double sy, List<double[]> spotsMm,
            double cutMm, double radiusMm) {
        this.settings = settings;
        this.w = w;
        this.h = h;
        this.sx = sx;
        this.sy = sy;
        this.cutMm = cutMm;
        this.radiusMm = radiusMm;
        px = new double[spotsMm.size()];
        py = new double[spotsMm.size()];
        for (int k = 0; k < px.length; k++) {
            px[k] = w / 2.0 + spotsMm.get(k)[0] / sx;
            py[k] = h / 2.0 - spotsMm.get(k)[1] / sy;
        }
    }

    static List<SpotDot> measure(DotInspector.Analysis a, DotInspectorSettings settings, double sx, double sy,
            List<double[]> spotsMm, double cutMm, double radiusMm) {
        return new SpotMeasure(a.width, a.height, settings, sx, sy, spotsMm, cutMm, radiusMm).run(a.material);
    }

    /** One labelling of a binary mask: the label of every pixel, and each piece's area, centroid and box. */
    private static final class Pieces {
        final int[] label;
        final int n;
        final double[] area, cx, cy;
        /** {left, top, width, height} per label. */
        final int[][] box;

        Pieces(Mat mask) {
            Mat lab = new Mat();
            Mat stats = new Mat();
            Mat cent = new Mat();
            try {
                n = Imgproc.connectedComponentsWithStats(mask, lab, stats, cent, 8, CvType.CV_32S);
                label = new int[(int) lab.total()];
                lab.get(0, 0, label);
                area = new double[n];
                cx = new double[n];
                cy = new double[n];
                box = new int[n][];
                for (int i = 1; i < n; i++) {
                    area[i] = stats.get(i, Imgproc.CC_STAT_AREA)[0];
                    cx[i] = cent.get(i, 0)[0];
                    cy[i] = cent.get(i, 1)[0];
                    box[i] = new int[] {(int) stats.get(i, Imgproc.CC_STAT_LEFT)[0],
                        (int) stats.get(i, Imgproc.CC_STAT_TOP)[0], (int) stats.get(i, Imgproc.CC_STAT_WIDTH)[0],
                        (int) stats.get(i, Imgproc.CC_STAT_HEIGHT)[0]};
                }
            }
            finally {
                lab.release();
                stats.release();
                cent.release();
            }
        }
    }

    private List<SpotDot> run(Mat material) {
        Mat opened = open(material, cutMm);
        Mat dotArea = null;
        try {
            Pieces cut = new Pieces(opened);
            Pieces raw = new Pieces(material);
            byte[] mat = new byte[w * h];
            material.get(0, 0, mat);
            // the cut dots and half a cut around them: not loose paste of a spot next to them
            dotArea = dilate(opened, cutMm / 2);
            byte[] near = new byte[w * h];
            dotArea.get(0, 0, near);
            List<Set<Integer>> at = new ArrayList<>();
            List<Set<Integer>> spotsOf = new ArrayList<>();
            for (int i = 0; i < cut.n; i++) {
                spotsOf.add(new TreeSet<>());
            }
            for (int k = 0; k < px.length; k++) {
                Set<Integer> labels = labelsWithin(cut.label, k, radiusMm / 2);
                at.add(labels);
                for (int c : labels) {
                    spotsOf.get(c).add(k);
                }
            }
            List<SpotDot> out = new ArrayList<>();
            for (int j = 0; j < px.length; j++) {
                out.add(spot(j, cut, raw, at.get(j), spotsOf, mat, near));
            }
            return out;
        }
        finally {
            opened.release();
            if (dotArea != null) {
                dotArea.release();
            }
        }
    }

    private SpotDot spot(int j, Pieces cut, Pieces raw, Set<Integer> here, List<Set<Integer>> spotsOf, byte[] mat,
            byte[] near) {
        if (px[j] < 0 || py[j] < 0 || px[j] > w - 1 || py[j] > h - 1) {
            return SpotDot.unmeasured("outside the camera frame", Collections.emptyList());
        }
        if (!here.isEmpty()) {
            int own = 0;
            Set<Integer> others = new TreeSet<>();
            for (int c : here) {
                if (spotsOf.get(c).size() == 1) {
                    own = own == 0 || cut.area[c] > cut.area[own] ? c : own;
                }
                else {
                    others.addAll(spotsOf.get(c));
                }
            }
            if (own != 0) {
                return measured(cut, own);
            }
            others.remove(j);
            return SpotDot.unmeasured("joined to the paste at another spot", new ArrayList<>(others));
        }
        int free = nearestFree(j, cut, spotsOf);
        if (free != 0) {
            return measured(cut, free);
        }
        int weak = nearestWeak(j, raw, cut);
        if (weak != 0) {
            return measured(raw, weak);
        }
        double loose = looseArea(j, mat, near);
        if (loose >= Math.PI / 4 * settings.minDiameterMm * settings.minDiameterMm) {
            return SpotDot.unmeasured(String.format(Locale.ROOT, "no dot, only strings or a smear (%.2f mm²)", loose),
                    Collections.emptyList());
        }
        if (!discInFrame(j)) {
            return SpotDot.unmeasured("partly outside the camera frame", Collections.emptyList());
        }
        return SpotDot.none();
    }

    /** A piece as the spot's dot, or not measurable when it fails the filters or the frame cuts it. */
    private SpotDot measured(Pieces p, int c) {
        int[] b = p.box[c];
        if (b[0] == 0 || b[1] == 0 || b[0] + b[2] == w || b[1] + b[3] == h) {
            return SpotDot.unmeasured("cut by the edge of the camera frame", Collections.emptyList());
        }
        Dot d = toDot(p, c);
        String why = d.diameterMm > settings.maxDiameterMm ? "too large"
                : d.circularity < settings.minCircularity ? "not round" : null;
        if (why == null) {
            return SpotDot.dot(d);
        }
        return SpotDot.unmeasured(String.format(Locale.ROOT, "no single round dot (%s: %.2f mm across, circularity "
                + "%.2f)", why, d.diameterMm, d.circularity), Collections.emptyList());
    }

    /** The cut piece that reaches no spot whose centroid is nearest spot {@code j} (within the spot radius), or 0. */
    private int nearestFree(int j, Pieces cut, List<Set<Integer>> spotsOf) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 1; i < cut.n; i++) {
            double d = mmFrom(j, cut.cx[i], cut.cy[i]);
            if (spotsOf.get(i).isEmpty() && d <= radiusMm && d < bestD && nearestSpot(cut.cx[i], cut.cy[i]) == j) {
                best = i;
                bestD = d;
            }
        }
        return best;
    }

    /**
     * A weak dot: the raw piece nearest spot {@code j} (centroid within the spot radius, nearer to it than to any
     * other spot) that holds no cut dot and passes the size and circularity filters, or 0.
     */
    private int nearestWeak(int j, Pieces raw, Pieces cut) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 1; i < raw.n; i++) {
            double d = mmFrom(j, raw.cx[i], raw.cy[i]);
            if (d > radiusMm || d >= bestD || nearestSpot(raw.cx[i], raw.cy[i]) != j || holdsCut(raw, i, cut)) {
                continue;
            }
            double area = raw.area[i] * sx * sy;
            double dia = 2 * Math.sqrt(area / Math.PI);
            if (dia < settings.minDiameterMm || dia > settings.maxDiameterMm
                    || circularity(raw, i) < settings.minCircularity) {
                continue;
            }
            best = i;
            bestD = d;
        }
        return best;
    }

    /** Whether raw piece {@code i} holds a cut dot (it is a dot's paste with its strings). */
    private boolean holdsCut(Pieces raw, int i, Pieces cut) {
        int[] b = raw.box[i];
        for (int y = b[1]; y < b[1] + b[3]; y++) {
            for (int x = b[0]; x < b[0] + b[2]; x++) {
                int k = y * w + x;
                if (raw.label[k] == i && cut.label[k] != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Paste within the spot radius of spot {@code j} that is not a cut dot or its margin, mm². */
    private double looseArea(int j, byte[] mat, byte[] near) {
        int[] r = window(j, radiusMm);
        int n = 0;
        for (int y = r[1]; y < r[3]; y++) {
            for (int x = r[0]; x < r[2]; x++) {
                int k = y * w + x;
                if (mat[k] != 0 && near[k] == 0 && mmFrom(j, x, y) <= radiusMm) {
                    n++;
                }
            }
        }
        return n * sx * sy;
    }

    /** Whether every pixel within the spot radius of spot {@code j} is in the frame. */
    private boolean discInFrame(int j) {
        return px[j] - radiusMm / sx >= 0 && py[j] - radiusMm / sy >= 0 && px[j] + radiusMm / sx <= w - 1
                && py[j] + radiusMm / sy <= h - 1;
    }

    /** Labels of {@code label} within {@code rMm} of spot {@code k}. */
    private Set<Integer> labelsWithin(int[] label, int k, double rMm) {
        Set<Integer> s = new TreeSet<>();
        int[] r = window(k, rMm);
        for (int y = r[1]; y < r[3]; y++) {
            for (int x = r[0]; x < r[2]; x++) {
                int c = label[y * w + x];
                if (c != 0 && mmFrom(k, x, y) <= rMm) {
                    s.add(c);
                }
            }
        }
        return s;
    }

    /** {x0, y0, x1, y1} (x1, y1 exclusive) of the pixels within {@code rMm} of spot {@code k}, inside the frame. */
    private int[] window(int k, double rMm) {
        return new int[] {Math.max(0, (int) Math.floor(px[k] - rMm / sx)),
            Math.max(0, (int) Math.floor(py[k] - rMm / sy)), Math.min(w, (int) Math.ceil(px[k] + rMm / sx) + 1),
            Math.min(h, (int) Math.ceil(py[k] + rMm / sy) + 1)};
    }

    private double mmFrom(int k, double x, double y) {
        return Math.hypot((x - px[k]) * sx, (y - py[k]) * sy);
    }

    private int nearestSpot(double x, double y) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int k = 0; k < px.length; k++) {
            double d = mmFrom(k, x, y);
            if (d < bestD) {
                best = k;
                bestD = d;
            }
        }
        return best;
    }

    /** A piece as a dot: its area (pixel count), centroid and the circularity of its outline. */
    private Dot toDot(Pieces p, int i) {
        double area = p.area[i] * sx * sy;
        return new Dot((p.cx[i] - w / 2.0) * sx, -(p.cy[i] - h / 2.0) * sy, 2 * Math.sqrt(area / Math.PI), area,
                circularity(p, i));
    }

    /** 4 pi A / P^2 of the piece's outline (as {@link DotInspector} measures a blob). */
    private double circularity(Pieces p, int i) {
        int[] b = p.box[i];
        int bw = b[2] + 2;
        byte[] m = new byte[(b[3] + 2) * bw];
        for (int y = 0; y < b[3]; y++) {
            for (int x = 0; x < b[2]; x++) {
                if (p.label[(b[1] + y) * w + b[0] + x] == i) {
                    m[(y + 1) * bw + x + 1] = (byte) 255;
                }
            }
        }
        Mat one = new Mat(b[3] + 2, bw, CvType.CV_8UC1);
        one.put(0, 0, m);
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hier = new Mat();
        try {
            Imgproc.findContours(one, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE);
            double bestArea = -1;
            MatOfPoint best = null;
            for (MatOfPoint c : contours) {
                double ar = Imgproc.contourArea(c);
                if (ar > bestArea) {
                    bestArea = ar;
                    best = c;
                }
            }
            if (best == null) {
                return 0;
            }
            MatOfPoint2f poly = new MatOfPoint2f(best.toArray());
            double perimeter = Imgproc.arcLength(poly, true);
            poly.release();
            return perimeter > 0 ? Math.min(1.0, 4 * Math.PI * bestArea / (perimeter * perimeter)) : 0;
        }
        finally {
            one.release();
            hier.release();
            for (MatOfPoint c : contours) {
                c.release();
            }
        }
    }

    /** The material opened with an elliptical disc {@code dMm} across (a copy when that is under 3 px). */
    private Mat open(Mat src, double dMm) {
        Mat k = disc(dMm / 2);
        Mat out = new Mat();
        if (k == null) {
            src.copyTo(out);
            return out;
        }
        Imgproc.morphologyEx(src, out, Imgproc.MORPH_OPEN, k);
        k.release();
        return out;
    }

    private Mat dilate(Mat src, double rMm) {
        Mat k = disc(rMm);
        Mat out = new Mat();
        if (k == null) {
            src.copyTo(out);
            return out;
        }
        Imgproc.dilate(src, out, k, new Point(-1, -1), 1, Core.BORDER_CONSTANT, new Scalar(0));
        k.release();
        return out;
    }

    /** An elliptical structuring element of radius {@code rMm} at this scale, or null under 1 px. */
    private Mat disc(double rMm) {
        int rx = (int) Math.round(rMm / sx);
        int ry = (int) Math.round(rMm / sy);
        if (rx < 1 || ry < 1) {
            return null;
        }
        return Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(2 * rx + 1, 2 * ry + 1));
    }
}
