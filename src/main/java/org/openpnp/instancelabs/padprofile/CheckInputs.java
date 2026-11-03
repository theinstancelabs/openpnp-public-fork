package org.openpnp.instancelabs.padprofile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything {@link ProfileCheck#of} needs (§3.1); built by the adapters from a plan, a board or a preview. */
public final class CheckInputs {
    public List<PadInstance> pads = new ArrayList<>();
    /** Current versions by key (retired ones included, flagged). */
    public Map<String, TypeInfo> types = new HashMap<>();
    /** Lowest status allowed on a board (D/DispenseConfig.minPadTypeStatus). */
    public String gate = TypeInfo.CHARACTERIZED;
    public Tip tip = Tip.g22();
    /** The profile's dose (D/DispenseProfile.netStepsPerDot). */
    public int profileSteps = DoseModel.HALF;
    public int stepsPerRev = DoseModel.STEPS_PER_REV;
    /** Marlin steps per degree of B, for the net turn shown (D/DispenseConfig.bStepsPerDeg). */
    public double bStepsPerDeg = DoseModel.B_STEPS_PER_DEG;
    /** Pooled fresh records per dose (§1.7, §7.6). */
    public Map<Integer, DoseEvidence> evidence = new HashMap<>();
    /**
     * Anchors by family, overriding the ones derived from validated types with a weighed mass per
     * pad; the chip family falls back to {@link Anchor#BENCH}.
     */
    public Map<String, Anchor> anchors = new HashMap<>();
    /** Current DispenseProfile.doseFingerprint() (§1.7). */
    public String doseFingerprint;
    /** Dots present (a plan). */
    public boolean planned;
    /**
     * Packages whose dispensable pads are pasted although some of their pads cannot be dispensed
     * with the tip (the "paste the dispensable pads of this part anyway" mapping option, main-thread
     * decision 4). By default such a part is treated whole: none of its pads are pasted.
     */
    public Set<String> pastePartialPackages = new HashSet<>();
    /** Stamped on the result. */
    public long nowMs;
}
