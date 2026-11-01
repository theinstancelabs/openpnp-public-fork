package org.openpnp.instancelabs.dispense;

/** Reasons a pad is not (or only provisionally) dispensable. */
public enum PadFlag {
    /** No pad type matched the package. Blocking. */
    NO_PAD_TYPE(true),
    /** Pad type status below DispenseConfig.minPadTypeStatus (plan §5.7 rule). Blocking. */
    STATUS_BELOW_MIN(true),
    /** Pad layout cannot be built (footprint has no pads and the type is footprint-only). Blocking. */
    NO_GEOMETRY(true);

    public final boolean blocking;

    PadFlag(boolean blocking) {
        this.blocking = blocking;
    }
}
