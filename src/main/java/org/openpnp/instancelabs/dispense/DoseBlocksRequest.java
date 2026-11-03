package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

/**
 * One engine step of the pad calibration (profile calibration design §7.3): dose blocks of test dots
 * laid in one pressure session with the board runs' phase rules (initial purge, a phase dot and the
 * flush dots before every further block), imaged before and after by the top camera. Kinds:
 * {@code pad-ladder} (the optical ladder on the calibration area of the blank) and {@code pad-weigh}
 * (the weigh dots on the coupons).
 */
public final class DoseBlocksRequest {
    public static final String LADDER = "pad-ladder";
    public static final String WEIGH = "pad-weigh";

    /** "pad-ladder" | "pad-weigh". */
    public String kind;
    public List<Block> blocks = new ArrayList<>();
    /** Before and after frames of every dot (the camera checks and the statistics). */
    public boolean image = true;
    /** Calibration-area height (mm) stamped as used right before streaming (the ladder), or null. */
    public Double areaReserveMm;
    /** {@link DispenseService#stopEpoch()} when the job built the request: a STOP since then refuses it (§7.8). */
    public long stopEpoch;
    /** At the end, park N2 at travel Z over the next unused purge spot (the operator weighs next). */
    public boolean parkAfter = true;

    /** Dots of one dose, laid together. */
    public static final class Block {
        public int netSteps;
        public String label;
        /** The move between consecutive dots the groups of this dose make on the board (mm). */
        public double hopMm;
        /** The dots' Z: the calibration area's surface, or the coupon place's own probed surface. */
        public String surfaceName;
        /** pad-weigh: the coupon place (reservation, bare check, record), else null. */
        public String couponPlace;
        /** N2 machine X/Y of the dots, in dispense order. */
        public List<double[]> xy = new ArrayList<>();
        /** The distance below which a found dot is taken for this block's spot (half the row pitch), mm. */
        public double matchRadiusMm = 1.0;

        public Block() {
        }

        public Block(int netSteps, String label, String surfaceName) {
            this.netSteps = netSteps;
            this.label = label;
            this.surfaceName = surfaceName;
        }
    }

    /** Every dot of every block. */
    public int dots() {
        int n = 0;
        for (Block b : blocks) {
            n += b.xy.size();
        }
        return n;
    }
}
