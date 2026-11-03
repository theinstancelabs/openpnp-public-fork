package org.openpnp.instancelabs.dispense;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.openpnp.instancelabs.padprofile.Tip;

/**
 * The dispense needle on the screw (profile calibration design §1.5, §5.4). Pad types and dose
 * records name the tip they were made with ({@link #key}): a finer tip changes the flow resistance,
 * so 18 psi and the mass per turn do not carry over (main-thread decision 5). Freshness after a
 * needle change uses {@link DispenseConfig#needleChangedAtMs} ("I changed the needle").
 */
public class TipSpec {
    /** Lower-case letters, digits and '-' (as pad type keys). */
    public static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");

    /** Identity recorded on pad types and dose records. */
    public String key = "ad22tta-b";
    /**
     * Words for the texts ("Not dispensable with the 22G bent taper"). Files saved before tip setups say "22G tip"
     * ({@link Tip#OLD_SEED_NAME}): the start's reconcile writes the setup's name over it (§22.1).
     */
    public String name = Tip.SEED_NAME;
    /** Inner diameter (mm): ADL AD22TTA-B, 22G plastic tapered. */
    public double idMm = 0.41;
    /**
     * Outer diameter at the tip (mm). Assumed, not measured (typical 22G tapered 0.65-0.80; decision
     * 12): the same-pad dot pitch d/2 + OD/2 comes from it, so a measurement changes it here only.
     */
    public double odMm = 0.72;
    /** Smallest net turn per dot this tip takes (steps of 1600): a quarter turn for the 22G (§1.5). */
    public int minDotSteps = 400;
    /**
     * Tip setups (§22.1): this is the engine's view of the mounted setup. Its version (part of the plan
     * fingerprint, so a corrected OD makes plans stale), {@link TipSetup#MODEL_BENCH} when §1's model
     * describes the tip (else null), its dispenser, form (bent | straight | unknown), bend angle and
     * the machine direction the bend points (+x | -x | +y | -y, null = not recorded). The defaults are
     * the seeded setup's.
     */
    public int setupVersion = 1;
    public String model = TipSetup.MODEL_BENCH;
    public String dispenser = TipSetup.DEFAULT_DISPENSER;
    public String form = TipSetup.FORM_BENT;
    public Double bendDeg = 30.0;
    public String bendToward = null;

    /**
     * True when a pad type or record belongs to another tip setup than this one (§22.1: a record without a tip key
     * is the seeded setup's, {@link Tip#setupOf}).
     */
    public boolean isOther(String tipKey) {
        return !Tip.setupOf(tipKey).equals(key);
    }

    /** Problems with these values, added to {@link DispenseConfig#validate()}. */
    void validate(List<String> p) {
        if (key == null || !KEY.matcher(key).matches()) {
            p.add("tip.key must be lower-case letters, digits and '-' (max 48)");
        }
        if (name == null || name.trim().isEmpty()) {
            p.add("tip.name is required (it is in the texts: \"with the 22G bent taper\")");
        }
        if (!(idMm > 0 && odMm > idMm && odMm <= 3)) {
            p.add("tip.idMm and tip.odMm must be > 0, the OD larger than the ID and at most 3 mm");
        }
        if (!PadType.isAllowedDotSteps(minDotSteps)) {
            p.add(String.format(Locale.ROOT, "tip.minDotSteps must be one of %s (a quarter, half or whole turn)",
                    PadType.DOT_STEPS_TEXT));
        }
    }
}
