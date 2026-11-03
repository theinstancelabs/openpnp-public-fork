package org.openpnp.instancelabs.padprofile;

/**
 * The mass per mm² of opening that reflow accepted for a family (§1.3): a validated pad type with a
 * weighed mass per pad (k = 1), or the built-in bench anchor of the chip family. Not a dose record:
 * a calibration never changes it.
 */
public final class Anchor {
    /**
     * The half turn the user validated on 0603 pads after reflow: 1.056 mg per pad (169 mg on 160
     * pads, runs/p18_board1.json) on the R_0603_1608Metric opening 0.80 x 0.95 mm roundness 50.
     */
    public static final Anchor BENCH = bench();

    public String family;
    public String typeKey;
    public Integer version;
    public double massPerPadMg;
    public double areaMm2;
    public double mgPerMm2;
    public boolean bench;
    /** Plain words for the check's anchor list (§3.5). */
    public String text;
    /**
     * The tip setup whose validated version this is, when not the mounted one (§22.2: the amount a pad needs is
     * shared by every setup); null = the mounted setup's or the bench.
     */
    public String setupName;

    public Anchor() {
    }

    public Anchor(String family, String typeKey, Integer version, double massPerPadMg, double areaMm2) {
        this.family = family;
        this.typeKey = typeKey;
        this.version = version;
        this.massPerPadMg = massPerPadMg;
        this.areaMm2 = areaMm2;
        this.mgPerMm2 = massPerPadMg / areaMm2;
    }

    private static Anchor bench() {
        Anchor a = new Anchor(Family.CHIP, null, null, DoseModel.HALF_MASS_MG,
                DotGeometry.padAreaMm2(0.80, 0.95, 50));
        a.bench = true;
        a.text = "the half turn you validated on 0603 pads after reflow (1.06 mg on 0.73 mm², weighed 2026-10-27)";
        return a;
    }

    public Anchor copy() {
        Anchor a = new Anchor();
        a.family = family;
        a.typeKey = typeKey;
        a.version = version;
        a.massPerPadMg = massPerPadMg;
        a.areaMm2 = areaMm2;
        a.mgPerMm2 = mgPerMm2;
        a.bench = bench;
        a.text = text;
        a.setupName = setupName;
        return a;
    }
}
