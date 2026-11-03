package org.openpnp.instancelabs.dispense;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One version of a pad type (plan §5.7). Rows are append-only; the current definition of a type is
 * its highest version.
 *
 * <p>Schema 2 (profile calibration design §5.1): a type may carry its own dose (net steps per dot,
 * dots per pad), the tip and recipe it was calibrated with, the opening area its dot plan was made
 * for and its weighed mass per pad. All are null on older rows: the profile's dot and DotSizer, as
 * before.</p>
 */
public class PadType {
    /** Net screw steps a type's dot may turn (of 1600 per revolution): quarter, half, one turn (§1.2 R1). */
    public static final List<Integer> DOT_STEPS = Collections.unmodifiableList(Arrays.asList(400, 800, 1600));
    static final String DOT_STEPS_TEXT = "400, 800, 1600";
    public static final int MAX_DOTS_PER_PAD = 6;
    /** Every pad gets at least half a screw turn in a row (§1.2 R6). */
    public static final int MIN_STEPS_PER_PAD = 800;

    public static final String ORIGIN_SEED = "seed";
    public static final String ORIGIN_MANUAL = "manual";
    public static final String ORIGIN_SUGGESTED = "suggested";
    public static final String ORIGIN_AUTO_CALIBRATED = "auto-calibrated";
    /**
     * Copied from another tip setup's version as a starting point (tip setups design §22.2): provisional,
     * nothing measured with this tip.
     */
    public static final String ORIGIN_COPIED = "copied";
    static final List<String> ORIGINS = Arrays.asList(ORIGIN_SEED, ORIGIN_MANUAL, ORIGIN_SUGGESTED,
            ORIGIN_AUTO_CALIBRATED, ORIGIN_COPIED);

    /** Pad layout used when a package has no footprint pads in packages.xml. */
    public enum Layout {
        /** One pad at the part origin. */
        SINGLE("single"),
        /** Two pads at +/- pitch/2 along the part X axis (chip R/C/L/LED, SOD). */
        TWO_TERMINAL("two-terminal"),
        /** SOT-23-3: pads 1,2 at (-pitch/2, -/+0.95), pad 3 at (+pitch/2, 0). */
        SOT23_3("sot23-3"),
        /** Pad positions must come from the package footprint; this row only carries k/t/size. */
        FOOTPRINT_ONLY("footprint-only");

        public final String dbName;

        Layout(String dbName) {
            this.dbName = dbName;
        }

        public static Layout fromDb(String s) {
            for (Layout l : values()) {
                if (l.dbName.equalsIgnoreCase(s) || l.name().equalsIgnoreCase(s)) {
                    return l;
                }
            }
            throw new IllegalArgumentException("Unknown pad layout: " + s);
        }
    }

    public long rowId;
    /** Stable identity across versions, e.g. "chip-0603". */
    public String typeKey;
    public int version;
    public String family;
    public String name;
    /** Pad size along the part X axis / Y axis. */
    public double padWMm;
    public double padHMm;
    /** OpenPnP-style roundness 0..100 % of the corner radius (100 = obround/circle). */
    public double padRoundness;
    public Layout layout = Layout.TWO_TERMINAL;
    /** Centre-to-centre pad pitch for TWO_TERMINAL / SOT23_3 layouts. */
    public Double pitchMm;
    /** Equivalent stencil thickness t (mm). */
    public double tMm = 0.10;
    /** Paste factor k (1.0 default, < 1 for e.g. QFN centre pads). */
    public double k = 1.0;
    public PadTypeStatus status = PadTypeStatus.NOT_STARTED;
    /** Java regex matched (find, case-insensitive) against OpenPnP package ids; may be null. */
    public String packagePattern;
    public String notes;
    public long createdAtMs;
    public String operator;
    /** Net steps per dot (one of {@link #DOT_STEPS}); null = the profile's netStepsPerDot. */
    public Integer dotSteps;
    /** Dots per pad, 1..{@link #MAX_DOTS_PER_PAD}; null = DotSizer with the type's rule. */
    public Integer dotsPerPad;
    /** The tip ({@link TipSpec#key}) the dose was set or calibrated for; null = not tied to a tip. */
    public String tipKey;
    /** {@link DispenseProfile#doseFingerprint()} at calibration; null = not calibrated. */
    public String doseFp;
    /** Opening area (mm²) the dot plan was made for; null = the type's own pad area. */
    public Double calAreaMm2;
    /** Weighed, confirmed mass per pad at calibration (mg): the source of an anchor (§1.3). */
    public Double massPerPadMg;
    /** seed | manual | suggested | auto-calibrated | copied; null on rows from before schema 2. */
    public String origin;
    /**
     * Tip setups (§22.2), on the rows of a setup's composed view ({@link TipSetups#typesFor}): the setup
     * the row is for (= {@link #tipKey}), the version of the pad fields it shows (the type's newest
     * version of any setup; {@link #version} is the setup's own), and whether it is a stand-in (no
     * version of the setup: dose fields cleared, version 0). Null and false on rows read as stored.
     */
    public String setupKey;
    public Integer padVersion;
    public boolean standIn;

    public PadType() {
    }

    public PadType(String typeKey, String family, String name, double padWMm, double padHMm,
            Layout layout, Double pitchMm, double k, String packagePattern, String notes) {
        this.typeKey = typeKey;
        this.family = family;
        this.name = name;
        this.padWMm = padWMm;
        this.padHMm = padHMm;
        this.layout = layout;
        this.pitchMm = pitchMm;
        this.k = k;
        this.packagePattern = packagePattern;
        this.notes = notes;
    }

    public PadType copy() {
        PadType p = new PadType();
        p.rowId = rowId;
        p.typeKey = typeKey;
        p.version = version;
        p.family = family;
        p.name = name;
        p.padWMm = padWMm;
        p.padHMm = padHMm;
        p.padRoundness = padRoundness;
        p.layout = layout;
        p.pitchMm = pitchMm;
        p.tMm = tMm;
        p.k = k;
        p.status = status;
        p.packagePattern = packagePattern;
        p.notes = notes;
        p.createdAtMs = createdAtMs;
        p.operator = operator;
        p.dotSteps = dotSteps;
        p.dotsPerPad = dotsPerPad;
        p.tipKey = tipKey;
        p.doseFp = doseFp;
        p.calAreaMm2 = calAreaMm2;
        p.massPerPadMg = massPerPadMg;
        p.origin = origin;
        p.setupKey = setupKey;
        p.padVersion = padVersion;
        p.standIn = standIn;
        return p;
    }

    public static boolean isAllowedDotSteps(Integer steps) {
        return steps != null && DOT_STEPS.contains(steps);
    }

    /**
     * Why this type's own dose cannot be stored (null if it can): the dose is a quarter, half or
     * whole turn, at most {@link #MAX_DOTS_PER_PAD} dots, and every pad gets at least half a turn.
     * The tip's minimum is checked where the tip is known (planner, pad type endpoints).
     */
    public String doseProblem() {
        if (dotSteps != null && !isAllowedDotSteps(dotSteps)) {
            return "dotSteps must be " + DOT_STEPS_TEXT + " (a quarter, half or whole screw turn), not " + dotSteps;
        }
        if (dotsPerPad != null && (dotsPerPad < 1 || dotsPerPad > MAX_DOTS_PER_PAD)) {
            return "dotsPerPad must be 1 to " + MAX_DOTS_PER_PAD + ", not " + dotsPerPad;
        }
        if (dotSteps != null && dotsPerPad != null && dotSteps * dotsPerPad < MIN_STEPS_PER_PAD) {
            return "dotsPerPad x dotSteps must be at least " + MIN_STEPS_PER_PAD
                    + " steps (half a screw turn per pad), not " + dotsPerPad + " x " + dotSteps;
        }
        if ((calAreaMm2 != null && !(calAreaMm2 > 0)) || (massPerPadMg != null && !(massPerPadMg > 0))) {
            return "calAreaMm2 and massPerPadMg must be > 0 when set";
        }
        if (origin != null && !ORIGINS.contains(origin)) {
            return "origin must be one of " + ORIGINS + ", not '" + origin + "'";
        }
        return null;
    }

    @Override
    public String toString() {
        return typeKey + " v" + version + " (" + family + " / " + name + ", " + status.dbName + ")";
    }
}
