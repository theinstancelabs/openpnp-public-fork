package org.openpnp.instancelabs.padprofile;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Family of a package (§4.4), for classification and the anchor only: nothing is written as a
 * pattern. The names are Profile's families (A/PadTypeEndpoints.java FAMILY_ORDER).
 */
public final class Family {
    public static final String CHIP = "Chip (2-terminal SMD)";
    public static final String SMALL_OUTLINE = "Small-outline discretes";
    public static final String LEADED = "Leaded ICs";
    public static final String NO_LEAD = "No-lead ICs";
    public static final String CUSTOM = "Custom";

    private static final Pattern NO_LEAD_RE = Pattern.compile("QFN|DFN|SON|LGA");
    private static final Pattern LEADED_RE = Pattern.compile("SOIC|SSOP|TSSOP|MSOP|QFP|SOP");
    private static final Pattern SMALL_OUTLINE_RE = Pattern.compile("SOT|SOD|SMA|SMB|SMC");

    private Family() {
    }

    /**
     * @param packageId the package (a "~n" variant suffix is ignored)
     * @param openingsPerPart paste openings of one part of the package
     * @param sizes groups (opening sizes) of the package
     */
    public static String of(String packageId, int openingsPerPart, int sizes) {
        String u = GroupKey.basePackage(packageId).toUpperCase(Locale.ROOT);
        if (NO_LEAD_RE.matcher(u).find()) {
            return NO_LEAD;
        }
        if (LEADED_RE.matcher(u).find()) {
            return LEADED;
        }
        if (SMALL_OUTLINE_RE.matcher(u).find()) {
            return SMALL_OUTLINE;
        }
        if (openingsPerPart == 2 && sizes == 1) {
            return CHIP;
        }
        return CUSTOM;
    }
}
