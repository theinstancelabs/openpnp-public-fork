package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

/** Outcome of Register board (Gson-friendly); the result card shows it. */
public final class RegistrationResult {
    /** One fiducial of the board as the check saw it. */
    public static class FidResult {
        public String name;
        public double bx, by;
        public double expectedX, expectedY;
        /** Found position, null if not found. */
        public Double x, y;
        /** found | not-found | ambiguous | not-centred | todo */
        public String state = "todo";
        public int iterations, frames;
        public Double offsetPx, offsetFromExpectedMm;
        /** Distance from the same fiducial of the previous registration, null without one. */
        public Double movedMm;
        public double searchRadiusMm;
    }

    /** The fit and the limits it was held to. */
    public static class FitResult {
        public static class Limits {
            public double scalePct, shearDeg, residualMm, scaleChangePct, shearChangeDeg;
        }

        public static class Previous {
            public long atMs;
            public double scaleX, scaleY, shearDeg;
        }

        /** affine-3 | affine-lsq-N | similarity-2 */
        public String method;
        public double[] affine;
        public double rotationDeg, scaleX, scaleY, shearDeg, maxResidualMm;
        public Limits limits;
        public Previous previous;
    }

    public RunResult run;
    public boolean ok;
    /** The result card's first sentence. */
    public String message;
    /** home | fiducials | same-seat | probe | save | plan, when ABORTED. */
    public String stoppedStage;
    public HomeReport home;
    public List<FidResult> fiducials = new ArrayList<>();
    public FitResult fit;
    /** first | same | reseated */
    public String seat;
    public Double movedMm;
    public Long previousAtMs;
    public String registrationId;
    public boolean registrationStored;
    public SameSeatReport sameSeat;
    public ProbeReport probe;
    public String newPlanId;
    public boolean simulated;
    /**
     * A board without fiducials, probed only (F10): no fiducial fit, no seat and no registration stored; the
     * surface is tied to the board where the plan puts it.
     */
    public boolean probeOnly;
}
