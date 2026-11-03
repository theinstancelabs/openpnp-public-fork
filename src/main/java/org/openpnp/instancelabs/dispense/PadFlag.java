package org.openpnp.instancelabs.dispense;

/** Reasons a pad is not (or only provisionally) dispensable. */
public enum PadFlag {
    /** No pad type matched the package. Blocking. */
    NO_PAD_TYPE(true),
    /**
     * Pad type status below DispenseConfig.minPadTypeStatus (plan §5.7 rule), or calibrated with
     * another tip than the one mounted (profile calibration design, main-thread decision 5). Blocking.
     */
    STATUS_BELOW_MIN(true),
    /** Pad layout cannot be built (footprint has no pads and the type is footprint-only). Blocking. */
    NO_GEOMETRY(true),
    /**
     * The pad's planned dots come within 0.20 mm of another pad's dots (main-thread decisions 11 and
     * 14: each such pad on its own, not its whole group). Blocking.
     */
    MAY_BRIDGE(true),
    /**
     * Another pad of the part cannot be dispensed with the mounted tip, so the part is treated whole
     * and none of its pads get paste (main-thread decision 4), unless its package is set to paste the
     * dispensable pads anyway; the pads the tip cannot dispense of such a part never get paste. Blocking.
     */
    PART_NOT_DISPENSABLE(true);

    public final boolean blocking;

    PadFlag(boolean blocking) {
        this.blocking = blocking;
    }
}
