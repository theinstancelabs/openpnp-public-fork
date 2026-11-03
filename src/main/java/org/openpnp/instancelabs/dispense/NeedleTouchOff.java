package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.openpnp.instancelabs.padprofile.DoseModel;
import org.openpnp.instancelabs.vision.SpotDot;

/**
 * Needle touch-off by dot ladder at stepped Z gaps (plan §6.1, option 1): dispense equal dots at
 * commanded gaps g_0 > g_1 > ... (relative to the current needle Z offset) and measure their
 * diameters. Far from the surface the diameter is stable; at the gap where the tip reaches the
 * surface the dot squashes (diameter jumps) or stops forming. The contact gap is estimated as the
 * midpoint between the last normal gap and the first squashed/missing one; the new offset is
 * old offset + contact gap. Resolution is one step (default 0.025 mm = one Z microstep today).
 *
 * <p>Each dot is found at its known spot with the strings cut off ({@link SpotDot}); paste at a spot that is not one
 * measurable dot (joined to a neighbour, strings, a smear) is never read as "no dot": the analysis refuses to
 * conclude instead (real touch-off of 2026-10-29, F9: strung dots read "dot stopped forming between 0.450 and 0.400
 * mm", which would have raised the needle about 0.4 mm).</p>
 */
public final class NeedleTouchOff {
    /**
     * The default pitch (mm): half-turn dots are about 1.07 mm across; at 1.0 mm they merged on the real machine, at
     * 1.6 mm they stood apart (strings between them are cut by the measurement).
     */
    public static final double DEFAULT_PITCH_MM = 1.6;
    /** The smallest pitch is the expected dot diameter plus this (mm): room for the strings between the dots. */
    public static final double PITCH_MARGIN_MM = 0.4;
    /**
     * The disc that cuts the strings off the dots is this share of the expected dot diameter (0.52 mm for a half
     * turn): the real strings came out 0.28-0.36 mm thick, the dots 0.9-1.2 mm across.
     */
    static final double CUT_SHARE = 0.45;
    /**
     * The reference dots must be at least this share of the diameter a steady dot of their dose makes on the blank
     * ({@link #steadyDotMm}): a steady dot is a disc about as high as the gap (1.16 mm across for a half turn), while
     * paste that does not reach the surface as it comes out drops off the tip as a droplet, a ball 0.81 mm across at
     * most for a whole half-turn dose (0.28 mm³ at 3.76 mg/mm³) and much less as a rule: the real ladder's first dot
     * was 0.37-0.43 mm, its steady dots 0.91-1.20 mm. Reference droplets make the first full dot after them look
     * squashed.
     */
    public static final double MIN_REFERENCE_SHARE = 0.6;
    /** Start of the reason when paste at a dot's spot could not be measured. */
    public static final String STRUNG = "dots touch or are strung together";

    private NeedleTouchOff() {
    }

    public static class Request {
        /** Largest commanded gap (mm, relative to the current offset). */
        public double startGapMm = 0.125;
        public double stepMm = 0.025;
        public int steps = 6;
        /**
         * Rotation per dot (deg); NaN = one dot of the dispense profile (partial turns that are not
         * phase-locked vary about +-50 % with the screw angle, which would hide the squash jump).
         */
        public double rotationDeg = Double.NaN;
        /** Distance between the dots (mm): at least the expected dot diameter + {@link #PITCH_MARGIN_MM}. */
        public double pitchMm = DEFAULT_PITCH_MM;
        public String locationName;
        public boolean primeAtWaste = true;
        /** Relative diameter increase that counts as squashing. */
        public double jumpRatio = 0.25;
    }

    public static class Analysis {
        public final double[] gapsMm;
        public final Double[] diametersMm;
        /** Per dot: "dot" (measured), "none" (no paste at its spot) or "unmeasured" (paste, but not one dot). */
        public final String[] dotStates;
        /** Per dot: why its paste could not be measured ("joined to the dot at gap 0.200 mm"), else null. */
        public final String[] dotNotes;
        public Double contactGapMm;
        public Double bracketHighMm;
        public Double bracketLowMm;
        public Double referenceDiameterMm;
        public String reason;

        Analysis(double[] g, Double[] d, String[] notes) {
            gapsMm = g;
            diametersMm = d;
            dotNotes = notes;
            dotStates = new String[g.length];
            for (int i = 0; i < g.length; i++) {
                dotStates[i] = notes[i] != null ? "unmeasured" : d[i] != null ? "dot" : "none";
            }
        }

        public boolean found() {
            return contactGapMm != null;
        }
    }

    public static double[] gaps(Request req) {
        double[] g = new double[req.steps];
        for (int i = 0; i < req.steps; i++) {
            g[i] = req.startGapMm - i * req.stepMm;
        }
        return g;
    }

    /** The screw turn of one ladder dot in motor steps: the request's rotation, else one dot of the profile. */
    static int dotSteps(Request req, DispenseConfig c) {
        return Double.isNaN(req.rotationDeg) ? c.profile.netStepsPerDot
                : (int) Math.round(req.rotationDeg * c.bStepsPerDeg);
    }

    /** The diameter a ladder dot is expected to have on the blank (mm): the dose model's upper edge for its turn. */
    public static double expectedDotDiameterMm(Request req, DispenseConfig c) {
        return DoseModel.dHiMm(Math.max(1, dotSteps(req, c)));
    }

    /**
     * The diameter a steady ladder dot has on the blank (mm): the dose model's lower edge for its turn, against which
     * the reference dots are checked ({@link #MIN_REFERENCE_SHARE}).
     */
    public static double steadyDotMm(Request req, DispenseConfig c) {
        return DoseModel.dLoMm(Math.max(1, dotSteps(req, c)));
    }

    /** The smallest pitch for these dots (mm): the expected dot diameter + {@link #PITCH_MARGIN_MM}. */
    public static double minPitchMm(Request req, DispenseConfig c) {
        return expectedDotDiameterMm(req, c) + PITCH_MARGIN_MM;
    }

    /** The pitch when none is asked: {@link #DEFAULT_PITCH_MM}, or the smallest one (to 0.05 mm) for larger dots. */
    public static double defaultPitchMm(Request req, DispenseConfig c) {
        return Math.max(DEFAULT_PITCH_MM, Math.ceil(minPitchMm(req, c) * 20 - 1e-9) / 20);
    }

    /** Null when the pitch leaves room between the dots, else the plain refusal. */
    public static String pitchProblem(Request req, DispenseConfig c) {
        double d = expectedDotDiameterMm(req, c);
        double min = d + PITCH_MARGIN_MM;
        if (req.pitchMm >= min - 1e-9) {
            return null;
        }
        return String.format(Locale.ROOT, "the touch-off pitch %.2f mm is too small for these dots: a %s dot is "
                + "about %.2f mm across, so dots %.2f mm apart touch or string together and cannot be measured; use a "
                + "pitch of at least %.2f mm (the dot + %.2f mm)", req.pitchMm, DoseModel.turnName(dotSteps(req, c)),
                d, req.pitchMm, Math.ceil(min * 100 - 1e-9) / 100, PITCH_MARGIN_MM);
    }

    /** The disc (mm across) that cuts the strings off dots of the expected diameter. */
    public static double cutMm(double expectedDotDiameterMm) {
        return CUT_SHARE * expectedDotDiameterMm;
    }

    /**
     * Dot i at blank.x + i * pitch. Its Z is the probed blank plane at that dot plus the offset and
     * gap i (review finding: with one flat Z, a slope of the blank along the ladder was mixed into
     * the gap steps and biased the offset). Without a plane (null) the blank place's single Z is
     * used; runs refuse that (I4: no blank surface).
     */
    public static List<GcodeStreamBuilder.DotSpec> layout(Request req, DispenseConfig.NamedLocation blank,
            DispenseConfig cfg, SurfacePlane plane) {
        List<GcodeStreamBuilder.DotSpec> l = new ArrayList<>();
        double[] g = gaps(req);
        for (int i = 0; i < g.length; i++) {
            double x = blank.x + i * req.pitchMm, y = blank.y;
            double surface = plane == null ? blank.z : plane.z(x, y);
            double z = surface + cfg.needleZOffsetMm + g[i];
            double hop = surface + cfg.needleZOffsetMm + Math.max(g[0], cfg.gapMm()) + cfg.hopHeightMm;
            l.add(new GcodeStreamBuilder.DotSpec(x, y, z, hop, req.rotationDeg, i,
                    String.format(java.util.Locale.ROOT, "touch-off gap %.3f", g[i])));
        }
        return l;
    }

    /**
     * The analysis of what the camera saw at each dot's spot ({@code seen} in the order of {@code gapsMm}, spot k
     * of a {@link SpotDot#joinedTo} being dot k): a dot gives its diameter, no paste "no dot", paste that is not one
     * dot a note ("joined to the dot at gap 0.200 mm"). {@code steadyDotMm}: as in
     * {@link #analyze(double[], Double[], String[], double, double)}.
     */
    public static Analysis analyze(double[] gapsMm, List<SpotDot> seen, double jumpRatio, double steadyDotMm) {
        Double[] d = new Double[gapsMm.length];
        String[] notes = new String[gapsMm.length];
        for (int i = 0; i < gapsMm.length; i++) {
            SpotDot s = seen.get(i);
            if (s.state == SpotDot.State.DOT) {
                d[i] = s.dot.diameterMm;
            }
            else if (s.state == SpotDot.State.UNMEASURED) {
                List<String> joined = new ArrayList<>();
                for (int k : s.joinedTo) {
                    if (k >= 0 && k < gapsMm.length) {
                        joined.add(String.format(Locale.ROOT, "%.3f", gapsMm[k]));
                    }
                }
                notes[i] = joined.isEmpty() ? s.note : "joined to the dot at gap " + String.join(" and ", joined)
                        + " mm";
            }
        }
        return analyze(gapsMm, d, notes, jumpRatio, steadyDotMm);
    }

    /**
     * @param gapsMm commanded gaps in any order
     * @param diametersMm measured diameter per gap, null = no dot formed
     */
    public static Analysis analyze(double[] gapsMm, Double[] diametersMm, double jumpRatio) {
        return analyze(gapsMm, diametersMm, new String[gapsMm.length], jumpRatio);
    }

    /** As {@link #analyze(double[], Double[], String[], double, double)} without a steady dot size to check against. */
    public static Analysis analyze(double[] gapsMm, Double[] diametersMm, String[] unmeasured, double jumpRatio) {
        return analyze(gapsMm, diametersMm, unmeasured, jumpRatio, Double.NaN);
    }

    /**
     * Far to near: the first two dots are the reference and must both form, agree within the jump ratio and be steady
     * dots, at least {@link #MIN_REFERENCE_SHARE} of {@code steadyDotMm} (dots laid too high miss or come out as
     * droplets, then the next one carries the paste: the real ladder's first dot was a 0.37 mm droplet, and two
     * droplets would make the first full dot look squashed); then the first dot squashed by more than the jump ratio,
     * or missing, is the contact. Paste that could not be measured at a dot's spot ({@code unmeasured}) before the
     * contact is reached refuses the conclusion: it could be a squashed dot as well as a normal one.
     *
     * @param gapsMm commanded gaps in any order
     * @param diametersMm measured diameter per gap, null = no dot formed (or not measured)
     * @param unmeasured per gap: why the paste at its spot could not be measured, null = measured or no paste
     * @param steadyDotMm the diameter a steady dot of the ladder's dose makes on the blank ({@link #steadyDotMm}),
     *        NaN = not checked
     */
    public static Analysis analyze(double[] gapsMm, Double[] diametersMm, String[] unmeasured, double jumpRatio,
            double steadyDotMm) {
        Analysis a = new Analysis(gapsMm.clone(), diametersMm.clone(), unmeasured.clone());
        Integer[] idx = new Integer[gapsMm.length];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (p, q) -> Double.compare(gapsMm[q], gapsMm[p])); // far -> near
        if (idx.length == 0) {
            a.reason = "no dots";
            return a;
        }
        if (strung(a, gapsMm, unmeasured, idx[0])) {
            return a;
        }
        Double d0 = diametersMm[idx[0]];
        if (d0 == null) {
            a.reason = "no dot at the largest gap: the needle is at or below the surface for every step, or "
                    + "nothing dispensed; raise startGapMm";
            return a;
        }
        double refD = d0;
        if (idx.length > 1) {
            if (strung(a, gapsMm, unmeasured, idx[1])) {
                return a;
            }
            Double d1 = diametersMm[idx[1]];
            if (d1 == null) {
                a.reason = String.format(Locale.ROOT, "only the first dot formed before a missing one (%.3f mm at gap "
                        + "%.3f, none at gap %.3f): a contact needs two steady dots before it (the first may be a "
                        + "droplet from too high); run the ladder again from another start gap", d0, gapsMm[idx[0]],
                        gapsMm[idx[1]]);
                return a;
            }
            double lo = Math.min(d0, d1), hi = Math.max(d0, d1);
            if (hi > lo * (1 + jumpRatio)) {
                a.reason = String.format(Locale.ROOT, "the first two dots differ too much to be the reference "
                        + "(%.3f mm at gap %.3f, %.3f mm at gap %.3f: more than %.0f %%): the dots far from the "
                        + "surface did not form evenly, or the needle reached the surface at once; run the ladder "
                        + "again from another start gap", d0, gapsMm[idx[0]], d1, gapsMm[idx[1]], 100 * jumpRatio);
                return a;
            }
            refD = (d0 + d1) / 2;
        }
        if (refD < MIN_REFERENCE_SHARE * steadyDotMm) {
            String first = idx.length > 1 ? String.format(Locale.ROOT, "two dots (%.3f mm at gap %.3f, %.3f mm at gap "
                    + "%.3f) are", d0, gapsMm[idx[0]], diametersMm[idx[1]], gapsMm[idx[1]])
                    : String.format(Locale.ROOT, "dot (%.3f mm at gap %.3f) is", d0, gapsMm[idx[0]]);
            a.reason = String.format(Locale.ROOT, "the first %s under %.0f %% of the %.2f mm a steady dot of this dose "
                    + "makes on the blank: droplets from a needle too high, so a larger dot after them is no contact; "
                    + "run the ladder again from a lower start gap", first, 100 * MIN_REFERENCE_SHARE, steadyDotMm);
            return a;
        }
        a.referenceDiameterMm = refD;
        for (int k = 2; k < idx.length; k++) {
            if (strung(a, gapsMm, unmeasured, idx[k])) {
                return a;
            }
            Double d = diametersMm[idx[k]];
            boolean contact = d == null || d > refD * (1 + jumpRatio);
            if (contact) {
                a.bracketHighMm = gapsMm[idx[k - 1]];
                a.bracketLowMm = gapsMm[idx[k]];
                a.contactGapMm = (a.bracketHighMm + a.bracketLowMm) / 2;
                a.reason = (d == null ? "dot stopped forming" : "dot squashed") + String.format(java.util.Locale.ROOT,
                        " between %.3f and %.3f mm", a.bracketHighMm, a.bracketLowMm);
                return a;
            }
        }
        a.reason = "no squash or missing dot down to the smallest gap; contact is below "
                + gapsMm[idx[idx.length - 1]] + " mm: lower the gaps";
        return a;
    }

    /** Sets the refusal when the dot at {@code i} could not be measured. */
    private static boolean strung(Analysis a, double[] gapsMm, String[] unmeasured, int i) {
        if (unmeasured[i] == null) {
            return false;
        }
        a.reason = String.format(Locale.ROOT, "%s: the paste at gap %.3f mm is %s; use a larger pitch", STRUNG,
                gapsMm[i], unmeasured[i]);
        return true;
    }
}
