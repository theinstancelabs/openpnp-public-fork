package org.openpnp.instancelabs.dispense;

/**
 * Per pad-type dot layout rule (plan §5.6 table {@code pad_rules}). Null fields fall back to the
 * DispenseConfig defaults. Versioned, append-only.
 */
public class PadRule {
    public long rowId;
    public String typeKey;
    public int version;
    /** Maximum dots per pad for this type (default DotSizer.DEFAULT_MAX_DOTS). */
    public Integer maxDots;
    /** Unused since the dose model was removed (kept for the stored rows). */
    public Double fitFactor;
    /** Pads larger than this (mm^2) get ceil(area / this) profile dots on a grid. */
    public Double largestSingleDotPadMm2;
    /** Informational: how the grid pitch is chosen (currently always "cell = pad / grid"). */
    public String gridPitchRule;
    public String notes;
    public long createdAtMs;

    public PadRule() {
    }

    public PadRule(String typeKey, Integer maxDots, Double fitFactor, Double largestSingleDotPadMm2) {
        this.typeKey = typeKey;
        this.maxDots = maxDots;
        this.fitFactor = fitFactor;
        this.largestSingleDotPadMm2 = largestSingleDotPadMm2;
    }
}
