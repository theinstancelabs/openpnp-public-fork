package org.openpnp.instancelabs.padprofile;

/**
 * The needle a pad type belongs to (§1.5, §5.4 TipSpec): mass per turn and dot size change with
 * the tip, so a type calibrated with another tip is treated as below the gate (main-thread decision
 * 5). Plain value class.
 */
public final class Tip {
    public String key;
    public String name;
    public double idMm;
    /** Outer diameter at the tip: the same-pad dot pitch is d/2 + OD/2 (assumed 0.72 mm for the 22G). */
    public double odMm;
    /** Smallest dose this tip makes reliably (R1). */
    public int minDotSteps;

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
        return new Tip("ad22tta-b", "22G tip", 0.41, 0.72, DoseModel.QUARTER);
    }
}
