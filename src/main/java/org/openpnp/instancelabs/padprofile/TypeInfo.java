package org.openpnp.instancelabs.padprofile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The current version of a pad type as the check sees it (D/PadType plus the §5.1 columns and its
 * PadRule). Plain value class: the package needs no DB or OpenPnP type.
 */
public final class TypeInfo {
    public static final String NOT_STARTED = "not-started";
    public static final String PROVISIONAL = "provisional";
    public static final String CHARACTERIZED = "characterized";
    public static final String VALIDATED = "validated";

    public String key;
    public String name;
    public String family;
    public int version;
    /** not-started | provisional | characterized | validated (D/PadTypeStatus.dbName). */
    public String status;
    /** Pad along the part X / Y axis and its roundness (% of half the short side). */
    public double padWMm;
    public double padHMm;
    public double padRoundness;
    public double k = 1.0;
    /** Own dose per dot (400, 800, 1600); null = the profile's. */
    public Integer dotSteps;
    /** Own dots per pad (1..6); null = by pad size with the rule (D/DotSizer). */
    public Integer dotsPerPad;
    public String tipKey;
    public String doseFp;
    /** Opening area the dot plan was made for; null = the type's pad area. */
    public Double calAreaMm2;
    /** Confirmed weighed mass per pad at calibration (the anchor source, §1.3). */
    public Double massPerPadMg;
    /** seed | manual | suggested | auto-calibrated. */
    public String origin;
    public boolean retired;
    /** The type's PadRule (D/PadRule): a pad larger than this gets several profile dots. */
    public Double largestSingleDotPadMm2;
    public Integer maxDots;
    /**
     * Tip setups (§22.2): the type has no version of the mounted setup (dose fields cleared, version 0),
     * the mounted setup's name for the texts, and the other setups' versions with their statuses.
     */
    public boolean standIn;
    public String setupName;
    public List<Other> others = new ArrayList<>();
    /** origin copied: the setup the dot plan was copied from (§22.2), for the suggestion's confidence; else null. */
    public String copiedFrom;

    /** Another setup's version of this type: "characterized for the 22G bent taper". */
    public static final class Other {
        public String setupKey;
        public String setupName;
        public int version;
        /** not-started | provisional | characterized | validated. */
        public String status;
    }

    public double padAreaMm2() {
        return DotGeometry.padAreaMm2(padWMm, padHMm, padRoundness);
    }

    /** The area the type was calibrated for: calAreaMm2, else its pad area. */
    public double calArea() {
        return calAreaMm2 != null && calAreaMm2 > 0 ? calAreaMm2 : padAreaMm2();
    }

    /** True if the type carries a dot plan of its own (an edited or calibrated plan, §4.2). */
    public boolean hasOwnPlan() {
        return dotSteps != null || dotsPerPad != null;
    }

    /** Status order: not-started 0, provisional 1, characterized 2, validated 3; unknown -1. */
    public static int rank(String status) {
        if (status == null) {
            return 0;
        }
        switch (status.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case NOT_STARTED:
                return 0;
            case PROVISIONAL:
                return 1;
            case CHARACTERIZED:
                return 2;
            case VALIDATED:
                return 3;
            default:
                return -1;
        }
    }
}
