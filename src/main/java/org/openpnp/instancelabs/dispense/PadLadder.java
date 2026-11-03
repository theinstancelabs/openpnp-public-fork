package org.openpnp.instancelabs.dispense;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.openpnp.instancelabs.padprofile.Limits;
import org.openpnp.instancelabs.padprofile.LadderStats;
import org.openpnp.instancelabs.vision.BareSpotCheck;

/**
 * The optical ladder of the pad calibration on the blank (profile calibration design §7.4): how many
 * dots each dose gets, where they go in the calibration area, the camera tiles that image them, and
 * what the frames say (the bare look before, the thread corridor and the crops after). Pure but for
 * the frames it is given.
 *
 * <ul>
 * <li>Dots per dose: N = max(minLadderDots, ladderDotsPerPhase x phases, minHops + 1 - the hops already
 * pooled or planned on the coupons), rounded up to whole phase sets; the control (the profile dose
 * when no group of the job uses it) {@value #CONTROL_DOTS}.</li>
 * <li>Pitch along a row: the groups' own pad-to-pad move (a thread forms as it would on the board),
 * at least d95 + 0.3 mm (the camera needs dot edges apart), at most 2.5 mm; rows max(pitch, d95 +
 * 0.5) apart, serpentine; every dot d95/2 + 0.5 mm inside the area; an empty row between doses.</li>
 * <li>Tiles: greedy over the dots with each dot {@value #TILE_INSET_MM} mm inside the frame (the bare
 * look's ring fits around it); without a field of view one frame per dot.</li>
 * </ul>
 */
public final class PadLadder {
    /** The control's dots when the profile dose is not calibrated in the job (§7.4). */
    public static final int CONTROL_DOTS = 12;
    /** Most pitch along a row (mm). */
    public static final double MAX_PITCH_MM = 2.5;
    /** Clearance between dot edges along a row and between rows, and the inset from the area's edges (mm). */
    public static final double ROW_EDGE_GAP_MM = 0.3, ROW_GAP_MM = 0.5, AREA_INSET_MM = 0.5;
    /** Every dot of a tile lies this far inside its frame (mm). */
    public static final double TILE_INSET_MM = 2.0;
    /** A corridor share above this is a thread (§7.4). */
    public static final double THREAD_SHARE = Limits.THREAD_PIXEL_FRACTION;
    /** The crops of the result card: 3 x 3 mm, 96 px. */
    public static final double CROP_MM = 3.0;
    public static final int CROP_PX = 96;

    private PadLadder() {
    }

    // ------------------------------------------------------------------------------------------
    // How many dots, how far apart
    // ------------------------------------------------------------------------------------------

    /** Ladder dots of one dose: enough per screw angle, enough in all, enough hops with the coupons'. */
    public static int dotsFor(int steps, PadCalibrationSettings s, int hopsElsewhere) {
        int phases = LadderStats.phases(steps);
        int n = Math.max(s.minLadderDots, Math.max(s.ladderDotsPerPhase * phases, s.minHops + 1 - hopsElsewhere));
        return (n + phases - 1) / phases * phases;
    }

    /** Pitch along a row: the hop, at least d95 + 0.3 mm, at most 2.5 mm. */
    public static double pitch(double hopMm, double d95Mm) {
        double lo = d95Mm + ROW_EDGE_GAP_MM;
        double p = Double.isFinite(hopMm) && hopMm > 0 ? hopMm : MAX_PITCH_MM;
        return Math.max(lo, Math.min(p, MAX_PITCH_MM));
    }

    /** Distance between rows. */
    public static double rowPitch(double pitch, double d95Mm) {
        return Math.max(pitch, d95Mm + ROW_GAP_MM);
    }

    /** How far every dot stays inside the calibration area's edges. */
    public static double insetMm(double d95Mm) {
        return d95Mm / 2 + AREA_INSET_MM;
    }

    /** One dose of the ladder. */
    public static final class Dose {
        public int steps;
        public int dots;
        public double hopMm;
        /** The upper dot size used for the spacing: the measured d95, else the model's upper edge. */
        public double d95Mm;
        public String label;

        public Dose(int steps, int dots, double hopMm, double d95Mm, String label) {
            this.steps = steps;
            this.dots = dots;
            this.hopMm = hopMm;
            this.d95Mm = d95Mm;
            this.label = label;
        }
    }

    /** The ladder's blocks in the area, and the band of the area they take. */
    public static final class Layout {
        public final List<DoseBlocksRequest.Block> blocks = new ArrayList<>();
        /** The band from the area's used line: its height (insets included) and its extent. */
        public double reservedMm;
        public double x0, x1, y0, y1;
        /** Why it does not fit (the area's height or width), or null. */
        public String problem;
    }

    /**
     * The ladder in the area's next free band: every dose in the given order, serpentine rows, the
     * dots of each dose in dispense order.
     *
     * @param area the calibration area in effect ({@link CalibrationArea#effective}), with its used height
     */
    public static Layout layout(CalibrationArea area, List<Dose> doses) {
        Layout l = new Layout();
        double bandStart = area.y0Mm + area.usedMm;
        l.x0 = area.x0Mm;
        l.x1 = area.x0Mm + area.widthMm;
        l.y0 = bandStart;
        double lastY = Double.NaN;
        double lastRow = 0;
        double lastInset = 0;
        for (Dose d : doses) {
            double p = pitch(d.hopMm, d.d95Mm);
            double rp = rowPitch(p, d.d95Mm);
            double inset = insetMm(d.d95Mm);
            double usable = area.widthMm - 2 * inset;
            if (usable < 0) {
                l.problem = String.format(Locale.ROOT, "the calibration area is %.1f mm wide: too narrow for %s dots",
                        area.widthMm, d.label);
                return l;
            }
            int cols = Math.max(1, (int) Math.floor(usable / p + 1e-9) + 1);
            cols = Math.min(cols, Math.max(1, d.dots));
            int rows = (d.dots + cols - 1) / cols;
            double first = Double.isNaN(lastY) ? bandStart + inset : lastY + 2 * Math.max(lastRow, rp);
            double left = area.x0Mm + (area.widthMm - (cols - 1) * p) / 2;
            DoseBlocksRequest.Block b = new DoseBlocksRequest.Block(d.steps, d.label, area.surfaceName);
            b.hopMm = d.hopMm;
            b.matchRadiusMm = Math.min(p, rp) / 2;
            for (int k = 0; k < d.dots; k++) {
                int row = k / cols;
                int col = k % cols;
                int c = row % 2 == 0 ? col : cols - 1 - col;
                b.xy.add(new double[] {left + c * p, first + row * rp});
            }
            l.blocks.add(b);
            lastY = first + (rows - 1) * rp;
            lastRow = rp;
            lastInset = inset;
        }
        l.y1 = Double.isNaN(lastY) ? bandStart : lastY + lastInset;
        l.reservedMm = l.y1 - bandStart;
        if (l.reservedMm > area.leftMm() + 1e-9) {
            l.problem = String.format(Locale.ROOT, "The calibration area has %.1f mm left and this calibration needs "
                    + "%.1f mm: wipe the blank and press “Blank wiped” (Calibrate › Pad calibration).",
                    area.leftMm(), l.reservedMm);
        }
        return l;
    }

    // ------------------------------------------------------------------------------------------
    // Camera tiles
    // ------------------------------------------------------------------------------------------

    /** One camera frame: its centre and the dots (indices) it images. */
    public static final class Tile {
        public double cx, cy;
        public final List<Integer> dots = new ArrayList<>();
    }

    /**
     * Frames that image every dot with each dot at least {@code insetMm} inside a frame of {@code fov}
     * {width, height} mm: bands of rows that fit the height, then greedy along X. Without a field of
     * view (or a frame too small for the inset), one frame per dot.
     */
    public static List<Tile> tiles(List<double[]> xy, double[] fov, double insetMm) {
        List<Tile> out = new ArrayList<>();
        double uw = fov == null ? -1 : fov[0] - 2 * insetMm;
        double uh = fov == null ? -1 : fov[1] - 2 * insetMm;
        if (!(uw >= 0 && uh >= 0)) {
            for (int i = 0; i < xy.size(); i++) {
                Tile t = new Tile();
                t.cx = xy.get(i)[0];
                t.cy = xy.get(i)[1];
                t.dots.add(i);
                out.add(t);
            }
            return out;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < xy.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer>comparingDouble(i -> xy.get(i)[1]).thenComparingDouble(i -> xy.get(i)[0]));
        int at = 0;
        while (at < order.size()) {
            double y0 = xy.get(order.get(at))[1];
            List<Integer> band = new ArrayList<>();
            while (at < order.size() && xy.get(order.get(at))[1] <= y0 + uh + 1e-9) {
                band.add(order.get(at++));
            }
            band.sort(Comparator.comparingDouble(i -> xy.get(i)[0]));
            int k = 0;
            while (k < band.size()) {
                double x0 = xy.get(band.get(k))[0];
                Tile t = new Tile();
                double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                while (k < band.size() && xy.get(band.get(k))[0] <= x0 + uw + 1e-9) {
                    double[] p = xy.get(band.get(k));
                    minX = Math.min(minX, p[0]);
                    maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]);
                    maxY = Math.max(maxY, p[1]);
                    t.dots.add(band.get(k++));
                }
                t.cx = (minX + maxX) / 2;
                t.cy = (minY + maxY) / 2;
                out.add(t);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // What the frames say
    // ------------------------------------------------------------------------------------------

    /** Pixel (column, row) of machine X/Y in a frame (DotInspector's convention: index - size/2, +y up). */
    static double[] pixel(DotImager.Frame f, double x, double y) {
        return new double[] {(x - f.xMm) / f.mmPerPxX + f.image.getWidth() / 2.0,
            -(y - f.yMm) / f.mmPerPxY + f.image.getHeight() / 2.0};
    }

    /** The bare look at a dot spot of a tile: a disc a little larger than the dot, its ring close around it. */
    public static BareSpotCheck.Settings spotSettings(double d95Mm) {
        BareSpotCheck.Settings s = new BareSpotCheck.Settings();
        s.discRadiusMm = d95Mm / 2 + 0.15;
        s.ringInnerMm = s.discRadiusMm + 0.3;
        s.ringOuterMm = Math.min(s.ringInnerMm + 0.6, TILE_INSET_MM - 0.1);
        return s;
    }

    /**
     * BareSpotCheck at a spot of a frame (not its centre): the check on a crop centred on the spot.
     * Null when the look's ring does not fit inside the frame there.
     */
    public static BareSpotCheck.Result bareAt(DotImager.Frame f, double x, double y, BareSpotCheck.Settings s) {
        if (f == null || f.image == null) {
            return null;
        }
        double[] px = pixel(f, x, y);
        int rx = (int) Math.ceil(s.ringOuterMm / f.mmPerPxX) + 2;
        int ry = (int) Math.ceil(s.ringOuterMm / f.mmPerPxY) + 2;
        int cx = (int) Math.round(px[0]);
        int cy = (int) Math.round(px[1]);
        if (cx - rx < 0 || cy - ry < 0 || cx + rx >= f.image.getWidth() || cy + ry >= f.image.getHeight()) {
            return null;
        }
        BufferedImage crop = f.image.getSubimage(cx - rx, cy - ry, 2 * rx, 2 * ry);
        return BareSpotCheck.check(crop, f.mmPerPxX, f.mmPerPxY, s);
    }

    /** A frame before and after, taken at the same camera position. */
    public static final class FramePair {
        public final DotImager.Frame before;
        public final DotImager.Frame after;

        public FramePair(DotImager.Frame before, DotImager.Frame after) {
            this.before = before;
            this.after = after;
        }
    }

    /**
     * §7.4's thread test: the share of the pixels of a 0.3 mm wide corridor between two dots' edges
     * (dot a of radius ra to dot b of radius rb) that differ from the before frame by more than the
     * colour delta. Each sample is read in the first frame pair that holds it; NaN when none does or the
     * dots overlap.
     */
    public static double corridorShare(List<FramePair> frames, double ax, double ay, double ra, double bx, double by,
            double rb) {
        double dx = bx - ax, dy = by - ay, len = Math.hypot(dx, dy);
        if (len <= ra + rb || frames.isEmpty()) {
            return Double.NaN;
        }
        double ux = dx / len, uy = dy / len;
        double step = Math.min(frames.get(0).after.mmPerPxX, frames.get(0).after.mmPerPxY) / 2;
        double half = Limits.THREAD_CORRIDOR_MM / 2;
        double delta2 = (double) Limits.THREAD_COLOUR_DELTA * Limits.THREAD_COLOUR_DELTA;
        int n = 0;
        int changed = 0;
        for (double t = ra; t <= len - rb + 1e-9; t += step) {
            for (double w = -half; w <= half + 1e-9; w += step) {
                double x = ax + ux * t - uy * w, y = ay + uy * t + ux * w;
                for (FramePair fp : frames) {
                    int[] q = inside(fp.after, x, y);
                    if (q == null) {
                        continue;
                    }
                    int p0 = fp.before.image.getRGB(q[0], q[1]);
                    int p1 = fp.after.image.getRGB(q[0], q[1]);
                    double db = (p0 & 0xFF) - (p1 & 0xFF);
                    double dg = ((p0 >> 8) & 0xFF) - ((p1 >> 8) & 0xFF);
                    double dr = ((p0 >> 16) & 0xFF) - ((p1 >> 16) & 0xFF);
                    n++;
                    changed += db * db + dg * dg + dr * dr > delta2 ? 1 : 0;
                    break;
                }
            }
        }
        return n == 0 ? Double.NaN : (double) changed / n;
    }

    /** The pixel of machine X/Y in a frame, or null outside it. */
    private static int[] inside(DotImager.Frame f, double x, double y) {
        double[] p = pixel(f, x, y);
        int u = (int) Math.floor(p[0] + 0.5);
        int v = (int) Math.floor(p[1] + 0.5);
        return u < 0 || v < 0 || u >= f.image.getWidth() || v >= f.image.getHeight() ? null : new int[] {u, v};
    }

    /** A {@value #CROP_MM} mm square around a spot, scaled to {@value #CROP_PX} px, as PNG (null without an image). */
    public static byte[] cropPng(DotImager.Frame f, double x, double y) throws IOException {
        if (f == null || f.image == null) {
            return null;
        }
        double[] p = pixel(f, x, y);
        int w = Math.max(1, (int) Math.round(CROP_MM / f.mmPerPxX));
        int h = Math.max(1, (int) Math.round(CROP_MM / f.mmPerPxY));
        int u0 = Math.max(0, Math.min(f.image.getWidth() - w, (int) Math.round(p[0] - w / 2.0)));
        int v0 = Math.max(0, Math.min(f.image.getHeight() - h, (int) Math.round(p[1] - h / 2.0)));
        BufferedImage out = new BufferedImage(CROP_PX, CROP_PX, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(f.image, 0, 0, CROP_PX, CROP_PX, u0, v0, u0 + Math.min(w, f.image.getWidth()),
                    v0 + Math.min(h, f.image.getHeight()), null);
        }
        finally {
            g.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bytes);
        return bytes.toByteArray();
    }

    // ------------------------------------------------------------------------------------------
    // The weigh coupon's look (§7.2 step 2)
    // ------------------------------------------------------------------------------------------

    /**
     * The look at a weigh grid's corner: small, so that the disc and its ring cover the coupon's margin
     * right up to its edge and no further.
     */
    public static BareSpotCheck.Settings couponCornerSettings() {
        BareSpotCheck.Settings s = new BareSpotCheck.Settings();
        s.discRadiusMm = 0.4;
        s.ringInnerMm = 0.5;
        s.ringOuterMm = 0.9;
        return s;
    }

    /** The coupon's colour is the blank's within this BGR distance (§7.2 step 2). */
    public static final double COUPON_COLOUR_DELTA = 25;

    /**
     * Where a surface of colour {@code ref} begins or ends along a line in a frame: from (x, y) in
     * direction (ux, uy), the first of 3 samples in a row (0.05 mm apart) that are on the other side of
     * {@code delta} from where (x, y) is ({@code onSurface}: off it; else: on it). Returns the distance
     * (mm), or NaN when the line leaves the frame first.
     */
    public static double edgeAlong(DotImager.Frame f, double x, double y, double ux, double uy, double[] ref,
            double delta, boolean onSurface) {
        double step = 0.05;
        int run = 0;
        double first = Double.NaN;
        for (double t = 0;; t += step) {
            int[] q = inside(f, x + ux * t, y + uy * t);
            if (q == null) {
                return Double.NaN;
            }
            boolean on = colourDistance(bgrAt(f, q), ref) <= delta;
            if (on != onSurface) {
                if (run++ == 0) {
                    first = t;
                }
                if (run >= 3) {
                    return first;
                }
            }
            else {
                run = 0;
            }
        }
    }

    /** The BGR of machine X/Y in a frame, or null outside it. */
    public static double[] bgrAt(DotImager.Frame f, double x, double y) {
        int[] q = inside(f, x, y);
        return q == null ? null : bgrAt(f, q);
    }

    private static double[] bgrAt(DotImager.Frame f, int[] q) {
        int p = f.image.getRGB(q[0], q[1]);
        return new double[] {p & 0xFF, (p >> 8) & 0xFF, (p >> 16) & 0xFF};
    }

    /**
     * How far a coupon's edges lie inside its nominal rectangle {x0, y0, x1, y1}, seen in a frame centred
     * on a corner look: along X and Y through the frame's centre, where the coupon colour {@code ref}
     * ends (the centre on it) or begins (the centre off it). The largest inward shift of the two axes
     * (mm, positive = inside), NaN when no edge is in the frame.
     */
    public static double couponEdgeShift(DotImager.Frame f, double[] rect, double[] ref) {
        double cx = f.xMm, cy = f.yMm;
        double dirX = cx < (rect[0] + rect[2]) / 2 ? -1 : 1;
        double dirY = cy < (rect[1] + rect[3]) / 2 ? -1 : 1;
        double[] here = bgrAt(f, cx, cy);
        boolean on = here != null && colourDistance(here, ref) <= COUPON_COLOUR_DELTA;
        double worst = Double.NaN;
        double ex = edgeAlong(f, cx, cy, on ? dirX : -dirX, 0, ref, COUPON_COLOUR_DELTA, on);
        if (!Double.isNaN(ex)) {
            double edge = cx + (on ? dirX : -dirX) * ex;
            worst = dirX < 0 ? edge - rect[0] : rect[2] - edge;
        }
        double ey = edgeAlong(f, cx, cy, 0, on ? dirY : -dirY, ref, COUPON_COLOUR_DELTA, on);
        if (!Double.isNaN(ey)) {
            double edge = cy + (on ? dirY : -dirY) * ey;
            double shift = dirY < 0 ? edge - rect[1] : rect[3] - edge;
            worst = Double.isNaN(worst) ? shift : Math.max(worst, shift);
        }
        return worst;
    }

    /** The BGR distance of two colours. */
    public static double colourDistance(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < 3; i++) {
            s += (a[i] - b[i]) * (a[i] - b[i]);
        }
        return Math.sqrt(s);
    }
}
