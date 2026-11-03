package org.openpnp.instancelabs.padprofile;

import java.util.ArrayList;
import java.util.List;

/**
 * One paste opening (§3.1), board or machine mm, +Y up: from a plan (with its flags and planned
 * dots), a board file before planning, or the KiCad upload preview (no flags, no dots).
 */
public final class PadInstance {
    public String padId;
    public String ref;
    public String packageId;
    public String padName;
    /** Centre, rotation (deg, CCW) and size; w is along the pad's own x. */
    public double x;
    public double y;
    public double rotationDeg;
    public double wMm;
    public double hMm;
    public double roundness;
    public String typeKey;
    public Integer typeVersion;
    public String typeStatus;
    /** package-map | pad-map | centre-pad | pattern | none. */
    public String typeRule;
    /** Planner flags (D/PadFlag names), empty for previews. */
    public List<String> flags = new ArrayList<>();
    public boolean blocked;
    /** The resolver's QFN/DFN centre rule matched this opening (k 0.5). */
    public boolean centrePad;
    /** Planned dose of this pad's dots, null if none. */
    public Integer dotSteps;
    /** Planned dot centres (same frame as x, y), null in previews or when blocked. */
    public List<double[]> dots;

    public PadInstance() {
    }

    public PadInstance(String ref, String packageId, String padName, double x, double y, double rotationDeg,
            double wMm, double hMm, double roundness) {
        this.padId = ref + "." + padName;
        this.ref = ref;
        this.packageId = packageId;
        this.padName = padName;
        this.x = x;
        this.y = y;
        this.rotationDeg = rotationDeg;
        this.wMm = wMm;
        this.hMm = hMm;
        this.roundness = roundness;
    }

    public boolean hasGeometry() {
        return wMm > 0 && hMm > 0 && !Double.isNaN(x) && !Double.isNaN(y);
    }

    public double areaMm2() {
        return hasGeometry() ? DotGeometry.padAreaMm2(wMm, hMm, roundness) : 0;
    }
}
