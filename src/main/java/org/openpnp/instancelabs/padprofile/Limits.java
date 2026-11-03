package org.openpnp.instancelabs.padprofile;

/**
 * Code constants of the paste model (PROFILE_CALIBRATION_DESIGN.md §1.3, §1.6, §3, §4.3, §7.4): the
 * limits that come from the physics and the bench, not settings. The calibration's own thresholds
 * (CV, phase, hops, rates) are settings ({@link LadderStats.Settings}).
 */
public final class Limits {
    /** Comparison slack for the limits below (mm or fractions). */
    public static final double EPS = 1e-9;

    // §1.6: the dot geometry, checked with the blank diameter of every dot

    /** Overhang of a dot past its own pad edge, any axis (0603 R half turn: 0.18, measured 0.19). */
    public static final double OVERHANG_MM = 0.20;
    /** Gap between dots of different pads (3 sigma size + landing). */
    public static final double DOT_GAP_MM = 0.30;
    /** Gap from a dot to another pad's copper. */
    public static final double COPPER_GAP_MM = 0.15;
    /** Marginal band: suggested with low confidence, the measured dot decides. */
    public static final double OVERHANG_MARGINAL_MM = 0.25;
    public static final double DOT_GAP_MARGINAL_MM = 0.20;
    public static final double COPPER_GAP_MARGINAL_MM = 0.10;

    // §1.3 / §4.3: the amount per pad

    /** Mass error band of a passing plan (and of high/medium confidence): -25 % / +10 %. */
    public static final double MASS_TOL_LOW = -0.25;
    public static final double MASS_TOL_HIGH = 0.10;
    /**
     * Fallback band (§4.3 steps 6 and 8, main-thread decision 3): the nearer allowed dose, ties to
     * the larger, within +-40 %. Too little paste failed visibly after reflow (the sixth turn left
     * bare pad), so a one-turn LED_0805 (+33 %) is preferred over a half turn (-33 %).
     */
    public static final double FALLBACK_LOW = -0.40;
    public static final double FALLBACK_HIGH = 0.40;
    /** Families without an anchor of their own: at a pitch of 1.27 mm or less, at most a 0.25 mm print. */
    public static final double CAP_PRINT_MM = 0.25;
    public static final double CAP_PITCH_MM = 1.27;
    /** Pads above 4 mm² (tabs, mounting, thermal pads) and centre pads get k = 0.5 (placeholder). */
    public static final double LARGE_PAD_MM2 = 4.0;
    public static final double K_PLACEHOLDER = 0.5;
    /** GC50 T5 84 % (mg/mm³). */
    public static final double PASTE_DENSITY_MG_MM3 = 3.76;

    // §3, §4.2, §4.5: grouping, coverage, reuse, confidence

    /** Openings of one package join a group within 0.02 mm (long and short side) and 5 % roundness. */
    public static final double GROUP_TOL_MM = 0.02;
    public static final double GROUP_TOL_ROUNDNESS = 5;
    /** Cell of the board-wide spatial grid. */
    public static final double NEIGHBOUR_CELL_MM = 3.0;
    /** A type covers openings of 0.80 to 1.25 times the area it was calibrated for. */
    public static final double COVER_RATIO_LOW = 0.80;
    public static final double COVER_RATIO_HIGH = 1.25;
    /** "Same pad size" for reusing a type: 0.90 to 1.10 times its area, sides within 0.10 mm. */
    public static final double SAME_SIZE_RATIO_LOW = 0.90;
    public static final double SAME_SIZE_RATIO_HIGH = 1.10;
    public static final double SAME_SIZE_MM = 0.10;
    /** Confidence medium: the opening is 0.5 to 2.5 times the anchor's (high: the cover ratios). */
    public static final double MEDIUM_RATIO_LOW = 0.5;
    public static final double MEDIUM_RATIO_HIGH = 2.5;
    /** Most dots per pad (DotSizer's default maximum, D/DotSizer.java). */
    public static final int MAX_DOTS = 6;

    // §7.4: what the camera sees

    /** A string: circularity below 0.35, or an area above 1.8 times the dose's median of round dots. */
    public static final double STRING_CIRCULARITY = 0.35;
    public static final double STRING_AREA_FACTOR = 1.8;
    /** The thread corridor between two dots' edges: 0.3 mm wide, 15 % of its pixels changed by 20. */
    public static final double THREAD_CORRIDOR_MM = 0.3;
    public static final double THREAD_PIXEL_FRACTION = 0.15;
    public static final int THREAD_COLOUR_DELTA = 20;
    /** The first 2 dots after a cross carry the switch over (PT/lessons.md:15): out of repeat, phase, drift. */
    public static final int EXCLUDED_AFTER_CROSS = 2;
    /** Drift over a record fails above 10 % when it is also above 2 standard errors. */
    public static final double DRIFT_LIMIT = 0.10;
    public static final double DRIFT_SE = 2.0;
    /** Landing: |mean offset| and its standard deviation (per axis). */
    public static final double LANDING_MEAN_MM = 0.15;
    public static final double LANDING_SD_MM = 0.08;
    /** Weigh dots per coupon. */
    public static final int WEIGH_DOTS_MIN = 30;
    public static final int WEIGH_DOTS_MAX = 200;
    /** The control: the bench's half-turn blank area (runs/p18_half) and the tolerance to a fresh record. */
    public static final double BENCH_CONTROL_AREA_MM2 = 1.057;
    public static final double CONTROL_RECORD_TOLERANCE = 0.10;
    /** One-sided 95 % normal quantile (upper bounds) and the d95 factor (d95 = mean (1 + 1.65 CV)). */
    public static final double Z95 = 1.645;
    public static final double D95_Z = 1.65;

    private Limits() {
    }
}
