package org.openpnp.instancelabs.dispense;

/**
 * One copper pad in global machine coordinates (the coordinates a nozzle tip is sent to), as
 * extracted from an OpenPnP board.
 */
public class PadGeometry {
    public enum Source {
        /** Pad from the package footprint in packages.xml. */
        FOOTPRINT,
        /** Pad synthesised from the pad type's layout and size (footprint had no pads). */
        PAD_TYPE
    }

    /** Unique within the board, e.g. "R12.1". */
    public String padId;
    public String placementId;
    public String packageId;
    public String padName;
    public double xMm;
    public double yMm;
    /** Global rotation of the pad's X (w) axis, degrees. */
    public double rotationDeg;
    public double wMm;
    public double hMm;
    public double roundness;
    /** Board surface Z (OpenPnP board location Z + placement Z). */
    public double surfaceZMm;
    public Source source;
    /** Pad type resolved for this pad (null if none). */
    public PadType padType;
    /** OpenPnP part id of the placement (null if the placement has no part). */
    public String partId;
    /** How the pad type was chosen ({@link PadTypeResolver.Resolution}); null if no resolver was asked. */
    public String typeRule;
    public String typeRuleDetail;
    public String typeRuleMatched;

    public PadGeometry() {
    }

    public PadGeometry(String padId, double xMm, double yMm, double rotationDeg, double wMm,
            double hMm, double surfaceZMm, PadType padType) {
        this.padId = padId;
        this.placementId = padId;
        this.xMm = xMm;
        this.yMm = yMm;
        this.rotationDeg = rotationDeg;
        this.wMm = wMm;
        this.hMm = hMm;
        this.surfaceZMm = surfaceZMm;
        this.padType = padType;
        this.source = Source.PAD_TYPE;
    }
}
