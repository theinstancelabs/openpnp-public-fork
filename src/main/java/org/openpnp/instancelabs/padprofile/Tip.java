package org.openpnp.instancelabs.padprofile;

import java.util.Map;
import java.util.TreeMap;

/**
 * The needle a pad type belongs to (§1.5, §5.4 TipSpec): mass per turn and dot size change with
 * the tip, so a type calibrated with another tip is treated as below the gate (main-thread decision
 * 5). Plain value class.
 *
 * <p>Tip setups (§22.2): the mounted setup with its dot table. With the bench model ({@link #benchModel},
 * the seeded 22G only) the dot sizes and masses are DoseModel's; otherwise the measured ones of the
 * setup's dot table, and a dose the tip does not know makes the getters throw: {@link #knows} decides
 * first, so no NaN reaches a comparison or a view.</p>
 */
public final class Tip {
    /** The seeded setup's key (§22.1): pad types and records without a tip key belong to it. */
    public static final String SEED_KEY = "ad22tta-b";
    /**
     * The seeded setup's name (§22.1): every text that names the mounted 22G says it, and so does TipSpec's default.
     */
    public static final String SEED_NAME = "22G bent taper";
    /**
     * The seed's name before tip setups: TipSpec's old default, so every settings file saved until then names the seed
     * so. The start's reconcile compares no name: it writes {@link #SEED_NAME} into such a file, with no new version
     * and no warning, and renames a seed row still so named (a database of an earlier tip setups build), once.
     */
    public static final String OLD_SEED_NAME = "22G tip";

    public String key;
    public String name;
    public double idMm;
    /** Outer diameter at the tip: the same-pad dot pitch is d/2 + OD/2 (assumed 0.72 mm for the 22G). */
    public double odMm;
    /** Smallest dose this tip makes reliably (R1). */
    public int minDotSteps;
    /** The setup's version and its dispenser (the check's tip view, §22.6). */
    public int setupVersion;
    public String dispenser;
    /** §1's bench model describes this tip (the seeded setup only, §22.11 item 10). */
    public boolean benchModel;
    /** The setup's dot table per dose (steps): mean and d95 diameter, the record and its time (§22.2). */
    public Map<Integer, Measured> measured = new TreeMap<>();
    /** The weighed mass per motor step (mg) and when; null = not weighed at the current recipe and paste. */
    public Double mgPerStep;
    public Long mgPerStepAtMs;
    /** The weighing was confirmed by a second reading that agrees (§7.5); false: weighed once. */
    public boolean mgPerStepConfirmed;

    /** One dose of the dot table: the newest passing optical record's sizes. */
    public static final class Measured {
        public double dMeanMm;
        public double d95Mm;
        public long recordId;
        public long atMs;
    }

    public Tip() {
    }

    public Tip(String key, String name, double idMm, double odMm, int minDotSteps) {
        this.key = key;
        this.name = name;
        this.idMm = idMm;
        this.odMm = odMm;
        this.minDotSteps = minDotSteps;
    }

    /** The tip in use: ADL AD22TTA-B, 22G plastic tapered, ID 0.41 mm (OD assumed, REAL_MACHINE_TODO). */
    public static Tip g22() {
        Tip t = new Tip(SEED_KEY, SEED_NAME, 0.41, 0.72, DoseModel.QUARTER);
        t.setupVersion = 1;
        t.dispenser = "Vieweg DV-5005";
        t.benchModel = true;
        return t;
    }

    /** The setup a record belongs to (§22.1): its tip key, or {@link #SEED_KEY} when it names none. */
    public static String setupOf(String tipKey) {
        return tipKey == null ? SEED_KEY : tipKey;
    }

    /** Whether the tip knows the dot size and mass of this dose: the bench model, or the dose in its dot table. */
    public boolean knows(int steps) {
        return steps > 0 && (benchModel || measured != null && measured.containsKey(steps) && mgPerStep != null);
    }

    /** Whether the tip knows the dot size of this dose (the bench model or its dot table), its mass or not. */
    public boolean knowsSize(int steps) {
        return steps > 0 && (benchModel || measured != null && measured.containsKey(steps));
    }

    /** Lower blank diameter of a dot of this dose (mm): DoseModel's, or the measured mean; throws when unknown. */
    public double dLoMm(int steps) {
        return benchModel ? DoseModel.dLoMm(steps) : size(steps).dMeanMm;
    }

    /** Upper blank diameter of a dot of this dose (mm): DoseModel's, or the measured d95; throws when unknown. */
    public double dHiMm(int steps) {
        return benchModel ? DoseModel.dHiMm(steps) : size(steps).d95Mm;
    }

    /** Mass of one dot of this dose (mg): DoseModel's, or mgPerStep x steps; throws when unknown. */
    public double massMg(int steps) {
        if (benchModel) {
            return DoseModel.massMg(steps);
        }
        if (mgPerStep == null || !(steps > 0)) {
            throw new IllegalStateException("the " + name + " was never weighed at this recipe and paste: the mass of a "
                    + DoseModel.turnName(steps) + " dot is not known");
        }
        return mgPerStep * steps;
    }

    /** The setup counts as measured (§22.2): its smallest dose has a dot size in the table and it has a weighing. */
    public boolean isMeasured() {
        return measured != null && measured.containsKey(minDotSteps) && mgPerStep != null;
    }

    /** Where the dot sizes come from (the check's tip view, §22.6): bench, measured or none. */
    public String dotsSource() {
        return benchModel ? "bench" : isMeasured() ? "measured" : "none";
    }

    private Measured size(int steps) {
        Measured m = measured == null ? null : measured.get(steps);
        if (m == null) {
            throw new IllegalStateException("the " + name + " has no measured " + DoseModel.turnName(steps)
                    + " dots at this recipe and paste: its dot size is not known");
        }
        return m;
    }
}
