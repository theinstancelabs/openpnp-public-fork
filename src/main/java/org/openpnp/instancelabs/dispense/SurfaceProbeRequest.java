package org.openpnp.instancelabs.dispense;

import java.util.List;

/** Probe the blank (or another needle-frame surface, or a weigh coupon) automatically with N1. */
public final class SurfaceProbeRequest {
    public String surfaceName;
    /** The nominal N1 contact Z of the surface (the place's z, or the request's). */
    public double nominalZ;
    /** Explicit N1 tip machine X/Y (3..20); null = {@link ProbeTargets#forRow}. */
    public List<double[]> targets;
    /**
     * False for a surface that is not part of the needle frame (a weigh coupon lies on the bed with its
     * own Z, profile calibration design §7.2 step 2): its place Z and points are not checked against
     * the needle-frame surface; the dots on it keep the needle floor of their own place Z.
     */
    public boolean frameCheck = true;
    /** The probe window: this far above and below the nominal Z (mm); null = the vacuum probe's own. */
    public Double windowMm;
    /**
     * A weigh coupon (§7.2 step 2): its name in the texts ("Coupon 1"), its nominal rectangle {x0, y0,
     * x1, y1} on its place, the camera spots that must be bare blank PCB before any touch (the weigh
     * grid's corners pushed out by the margin, and its centre) and a spot of the blank whose colour the
     * coupon must match. Null for other surfaces.
     */
    public Coupon coupon;

    /** The camera look of a weigh coupon before its probe. */
    public static final class Coupon {
        public String label;
        public double[] rect;
        public List<double[]> looks;
        /** Where the blank's own colour is read (the calibration area's centre), or null: not compared. */
        public double[] blankSpot;
        /**
         * How far the coupon's edges may lie inside its nominal rectangle before the weigh grid's margin is
         * short (mm): the grid's room beyond the margin and half a dot.
         */
        public double slackMm;
    }
}
