package org.openpnp.instancelabs.dispense;

import java.util.List;
import java.util.Locale;

/**
 * The part of the blank the pad calibration lays its test dots on (profile calibration design
 * §5.4, §7.4): a rectangle in N2 machine X/Y whose Z comes from a probed surface. It is used up in
 * bands from y0 upward ({@link #usedMm}, stamped as a ladder is reserved) and freed when the blank is
 * wiped ({@link #resetAtMs}), like the purge row.
 *
 * <p>Unset ({@link #isDefined()} false): {@link #DEFAULT_WIDTH_MM} x {@link #DEFAULT_HEIGHT_MM} from
 * the blank place, growing +X/+Y as the dot ladder and the touch-off do, moved off the purge row
 * ({@link #effective}).</p>
 */
public class CalibrationArea {
    public static final double DEFAULT_WIDTH_MM = 40;
    public static final double DEFAULT_HEIGHT_MM = 20;
    /** Every purge spot stays this far from the area (the purge row's own 1.5 mm pitch rule). */
    public static final double PURGE_CLEARANCE_MM = 1.5;
    /** Every coupon place's rectangle stays this far from the area. */
    public static final double COUPON_CLEARANCE_MM = 3.0;

    /** Surface whose probe points give the dots' Z. */
    public String surfaceName = "blank";
    /** Lower-left corner and size (mm); all null = the default. */
    public Double x0Mm, y0Mm, widthMm, heightMm;
    /** Engine-stamped: height used from y0 (mm). */
    public double usedMm = 0;
    /** Engine-stamped: when the area was last wiped. */
    public Long resetAtMs;

    public boolean isDefined() {
        return x0Mm != null && y0Mm != null && widthMm != null && heightMm != null;
    }

    /** Height of the area, the default when unset. */
    public double heightMm() {
        return isDefined() ? heightMm : DEFAULT_HEIGHT_MM;
    }

    /** Height still free (mm). */
    public double leftMm() {
        return Math.max(0, heightMm() - usedMm);
    }

    /**
     * Reserves the next band of this height and stamps {@link #usedMm}. Returns where the band
     * starts, measured from y0 (mm).
     *
     * @throws IllegalArgumentException if the height is not > 0 or more than is left
     */
    public double reserve(double bandMm) {
        if (!(bandMm > 0) || bandMm > leftMm() + 1e-9) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "the calibration area has %.1f mm left; %.1f mm asked", leftMm(), bandMm));
        }
        double start = usedMm;
        usedMm += bandMm;
        return start;
    }

    /**
     * This area with its corner and size filled in: as set, else the default at the blank place
     * moved off the purge row by the shortest of the four moves (up, down, right, left; in this order
     * on a tie) that clears every spot by {@link #PURGE_CLEARANCE_MM}. Null when unset and the
     * configuration has no blank place.
     */
    public CalibrationArea effective(DispenseConfig cfg) {
        CalibrationArea a = copy();
        if (isDefined()) {
            return a;
        }
        DispenseConfig.NamedLocation blank = cfg.location(cfg.blankLocationName);
        if (blank == null) {
            return null;
        }
        double w = DEFAULT_WIDTH_MM, h = DEFAULT_HEIGHT_MM, c = PURGE_CLEARANCE_MM;
        a.x0Mm = blank.x;
        a.y0Mm = blank.y;
        a.widthMm = w;
        a.heightMm = h;
        double x0 = blank.x, y0 = blank.y;
        // past every spot in the area's X band (moving along Y) or in its Y band (moving along X)
        double up = Double.NaN, down = Double.NaN, right = Double.NaN, left = Double.NaN;
        boolean blocked = false;
        for (double[] s : purgeSpots(cfg)) {
            blocked |= a.distanceTo(s[0], s[1]) < c - 1e-9;
            if (s[0] > x0 - c && s[0] < x0 + w + c) {
                up = max(up, s[1] + c - y0);
                down = min(down, s[1] - c - h - y0);
            }
            if (s[1] > y0 - c && s[1] < y0 + h + c) {
                right = max(right, s[0] + c - x0);
                left = min(left, s[0] - c - w - x0);
            }
        }
        if (!blocked) {
            return a;
        }
        double[][] moves = {{0, up}, {0, down}, {right, 0}, {left, 0}};
        double[] best = null;
        for (double[] m : moves) {
            double d = Math.abs(m[0]) + Math.abs(m[1]);
            if (!Double.isNaN(d) && (best == null || d < Math.abs(best[0]) + Math.abs(best[1]) - 1e-9)) {
                best = m;
            }
        }
        a.x0Mm = x0 + best[0];
        a.y0Mm = y0 + best[1];
        return a;
    }

    private static double max(double a, double b) {
        return Double.isNaN(a) ? b : Math.max(a, b);
    }

    private static double min(double a, double b) {
        return Double.isNaN(a) ? b : Math.min(a, b);
    }

    /** Distance from a point to this (defined) rectangle, 0 inside. */
    public double distanceTo(double x, double y) {
        double dx = Math.max(0, Math.max(x0Mm - x, x - (x0Mm + widthMm)));
        double dy = Math.max(0, Math.max(y0Mm - y, y - (y0Mm + heightMm)));
        return Math.hypot(dx, dy);
    }

    /** Distance between this (defined) rectangle and another given by its corners, 0 if they overlap. */
    public double distanceTo(double ox0, double oy0, double ox1, double oy1) {
        double dx = Math.max(0, Math.max(ox0 - (x0Mm + widthMm), x0Mm - ox1));
        double dy = Math.max(0, Math.max(oy0 - (y0Mm + heightMm), y0Mm - oy1));
        return Math.hypot(dx, dy);
    }

    /** Problems of a defined area, added to {@link DispenseConfig#validate()}. */
    void validate(DispenseConfig cfg, List<String> p) {
        if (surfaceName == null || surfaceName.trim().isEmpty()) {
            p.add("calibrationArea.surfaceName is required");
        }
        if (!(usedMm >= 0)) {
            p.add("calibrationArea.usedMm must be >= 0");
        }
        boolean any = x0Mm != null || y0Mm != null || widthMm != null || heightMm != null;
        if (!isDefined()) {
            if (any) {
                p.add("calibrationArea: set x0Mm, y0Mm, widthMm and heightMm together, or none of them (the default "
                        + "40 x 20 mm at the blank place)");
            }
            return;
        }
        if (!(Double.isFinite(x0Mm) && Double.isFinite(y0Mm) && widthMm >= 5 && widthMm <= 200 && heightMm >= 5
                && heightMm <= 200)) {
            p.add("calibrationArea: x0Mm and y0Mm must be numbers and the width and height 5 to 200 mm");
            return;
        }
        for (double[] s : purgeSpots(cfg)) {
            double d = distanceTo(s[0], s[1]);
            if (d < PURGE_CLEARANCE_MM - 1e-9) {
                p.add(String.format(Locale.ROOT, "calibrationArea must stay %.1f mm from every purge spot: the spot at "
                        + "X %.1f, Y %.1f is %.2f mm from it", PURGE_CLEARANCE_MM, s[0], s[1], d));
                break;
            }
        }
        for (String place : cfg.padCalibration.couponPlaces == null ? new java.util.ArrayList<String>()
                : cfg.padCalibration.couponPlaces) {
            double[] r = cfg.padCalibration.couponRect(cfg, place);
            if (r != null) {
                double d = distanceTo(r[0], r[1], r[2], r[3]);
                if (d < COUPON_CLEARANCE_MM - 1e-9) {
                    p.add(String.format(Locale.ROOT, "calibrationArea must stay %.1f mm from the coupon place '%s' "
                            + "(%.0f x %.0f mm around it): it is %.2f mm from it", COUPON_CLEARANCE_MM, place,
                            r[2] - r[0], r[3] - r[1], d));
                }
            }
        }
    }

    /** Every spot of the purge row, used or not (used spots carry paste). */
    static List<double[]> purgeSpots(DispenseConfig cfg) {
        List<double[]> l = new java.util.ArrayList<>();
        DispenseConfig.PurgeRow row = cfg.purgeRow;
        if (row != null && row.isDefined()) {
            for (int i = 0; i < row.spots; i++) {
                l.add(row.spot(i));
            }
        }
        return l;
    }

    public CalibrationArea copy() {
        CalibrationArea a = new CalibrationArea();
        a.surfaceName = surfaceName;
        a.x0Mm = x0Mm;
        a.y0Mm = y0Mm;
        a.widthMm = widthMm;
        a.heightMm = heightMm;
        a.usedMm = usedMm;
        a.resetAtMs = resetAtMs;
        return a;
    }
}
