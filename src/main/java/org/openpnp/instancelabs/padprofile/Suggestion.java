package org.openpnp.instancelabs.padprofile;

import java.util.ArrayList;
import java.util.List;

/**
 * What the check suggests for a group (§4.6), Gson-friendly: {@code use} an existing pad type of the
 * same size (an explicit mapping), {@code calibrate-existing} the group's own type, a {@code new}
 * pad type, or {@code none} (not dispensable, no pad size, a part treated whole).
 */
public final class Suggestion {
    public static final String USE = "use";
    public static final String CALIBRATE_EXISTING = "calibrate-existing";
    public static final String NEW = "new";
    public static final String NONE = "none";
    /**
     * The mounted tip setup is not measured (§22.2): "Measure the {S}'s dots first (...)": nothing is suggested
     * until its dot sizes and paste per turn are known.
     */
    public static final String MEASURE_TIP = "measure-tip";

    public String kind;
    /** "s" + 12 hex of SHA-1 over the group, the plan, the anchor, the evidence used and the types. */
    public String id;
    public String group;
    public String typeKey;
    public Integer typeVersion;
    public String name;
    public String family;
    /** Explicit mapping to write (use, new); null for calibrate-existing. Never a regex. */
    public Mapping mapping;
    /** calibrate-existing: "suggested" or "your plan" (the type's own edited plan, §4.2). */
    public String planFrom;
    public Opening opening;
    /** new: the pad fields of the type (= the opening). */
    public Opening pad;
    public Amount amount;
    public Dots dots;
    public Predicted predicted;
    /** pass | fallback | marginal | marginal-fallback | breaks-limits (an edited plan outside every band). */
    public String fit;
    public Phase phase;
    /** high | medium | low, and every reason that kept it from high (§4.5). */
    public String confidence;
    public List<String> confidenceWhy = new ArrayList<>();
    public String sentence;
    public Calibration calibration;
    public List<Alternative> alternatives = new ArrayList<>();

    public static final class Mapping {
        public String packageId;
        /** Null when the group is every opening of the package. */
        public List<String> padNames;
    }

    public static final class Opening {
        public double wMm;
        public double hMm;
        public double roundness;
        public double areaMm2;
    }

    public static final class Amount {
        public double targetMg;
        public double k;
        public double mgPerMm2;
        public boolean capped;
        public String rule;
    }

    public static final class Dots {
        public int perPad;
        public int steps;
        /** "quarter turn" */
        public String turn;
        public double netDeg;
        public double mgPerDot;
        /** weighed | weighed-per-step (another dose's confirmed mass per step) | model */
        public String mgSource;
        /** [lower, upper] blank diameter (model band, or measured mean and d95). */
        public double[] diameterMm;
        /** measured | model */
        public String dSource;
        /** Dot centres in the pad frame of the group's first opening (mm, x along w). */
        public List<double[]> positions = new ArrayList<>();
    }

    public static final class Predicted {
        public double mgPerPad;
        public int errorPct;
        public double overhangMm;
        public double dotGapMm;
        public double padGapMm;
        /** Per-pad spread from the screw angle, % (100 a sinc(T/2), a 0.11 and 0.26). */
        public double[] padRipplePct;
    }

    public static final class Phase {
        /** profile (the profile's dose, laid with today's dots) | block (its own block, R2/R3) */
        public String rule;
        public String text;
    }

    public static final class Calibration {
        /** The chosen dose and the next allowed doses on either side with a passing plan (at most 3). */
        public List<Integer> doses = new ArrayList<>();
        /** The profile dose, always laid first (§7.4). */
        public int control;
        /** Doses with no confirmed mass (§7.5). */
        public List<Integer> weigh = new ArrayList<>();
    }

    public static final class Alternative {
        public int steps;
        public int perPad;
        public double mgPerPad;
        public String why;
    }
}
