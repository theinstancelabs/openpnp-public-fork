package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.openpnp.instancelabs.padprofile.DoseModel;
import org.openpnp.instancelabs.padprofile.Tip;
import org.openpnp.instancelabs.vision.Dot;
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
    /** {@link Analysis#referenceFrom} when the steady dot is the dose model's (the bench 22G's, §1.4). */
    public static final String MODEL_REFERENCE = "the 22G model";
    /**
     * BR-89 (Process 11-04 12:5x): every ladder ends with this many check dots at the reference gap; the 2nd one's
     * landing against the reference pair's shows a tip that moved at the floor (the 1st may carry paste from there).
     */
    public static final int CHECK_DOTS = 2;
    /** BR-89: a 2nd check dot this far (mm) from the reference pair's landing refuses the apply (~2-3x the 25G's scatter). */
    public static final double LANDING_SHIFT_MAX_MM = 0.1;
    /**
     * BR-89 (Process 11-04 15:1x): the 2nd check dot's area may differ from the reference pair's mean by at most this
     * share: more, and the needle's height may have changed (the tip slipped up or down in the Luer at the floor).
     */
    public static final double CHECK_AREA_MAX_OFF = 0.25;
    /**
     * The default start gap (mm): with no dot skipped the reference pair sits here and one step lower. BR-85: with
     * skipped dots the start gap must be that many steps higher, so the reference still sits here or higher; a ladder
     * that starts lower is refused ({@link #startGapProblem}), not moved up (the API's default start is that minimum).
     */
    public static final double REFERENCE_GAP_MM = 0.125;

    private NeedleTouchOff() {
    }

    public static class Request {
        /** Largest commanded gap (mm, relative to the current offset). */
        public double startGapMm = REFERENCE_GAP_MM;
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
        /**
         * BR-85: where the steady dot the reference must reach comes from: "the tip's dots at 1600 steps (11-04 14:07)"
         * or {@link #MODEL_REFERENCE}; null when not checked.
         */
        public String referenceFrom;
        /** BR-85: the dots after the prime left out before the reference pair (the recipe's touchOffSkipDots). */
        public int skippedDots;
        public String reason;
        /** BR-89: the check dots' gap (the first counted dot's), laid after the ladder; null before the check. */
        public Double checkGapMm;
        /**
         * BR-89: how far (mm) the 2nd check dot landed from the reference pair's mean landing (null: either was not
         * measured), and the deepest measured counted dot's (information only: a squashed dot's centroid moves as
         * paste spreads).
         */
        public Double landingShiftMm, squashedShiftMm;
        /** BR-89: the 2nd check dot's area off the reference pair's mean (%, either way); null when not compared. */
        public Double checkAreaOffPct;
        /** BR-89: why the new offset may not be applied (the tip may have moved, or the check could not be made). */
        public String landingProblem;
        /** BR-89: per ladder dot its landing {dx, dy} (centroid offset from its planned spot, mm) and its area (mm²); null
         * where no dot was measured. */
        public double[][] landingsMm;
        public Double[] areasMm2;
        /** The dots laid: the ladder's and the check dots. */
        public int dotsLaid;
        public final List<CheckDot> checkDots = new ArrayList<>();

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

    /** BR-89: what the camera saw at a check dot's spot: its state as {@link Analysis#dotStates}, size and landing. */
    public static class CheckDot {
        public String state;
        /** Its diameter and centroid offset from its planned spot (mm) and area (mm²), for a measured dot; else null. */
        public Double diameterMm, dxMm, dyMm, areaMm2;
    }

    /** BR-89: the check dots' gap: the reference gap, the first counted dot's (after {@code skip}). */
    public static double checkGapMm(Request req, int skip) {
        return req.startGapMm - skip * req.stepMm;
    }

    /**
     * BR-89: the landing check of a ladder's analysis from what the camera saw at every spot ({@code seen}: the
     * ladder's, then the {@link #CHECK_DOTS}): the 2nd check dot against the reference pair's mean landing (free dots
     * with free dots), the deepest counted dot's shift as information. An unmeasured 2nd check dot, or one more than
     * {@link #LANDING_SHIFT_MAX_MM} off, sets {@link Analysis#landingProblem}.
     */
    public static void checkLanding(Analysis a, List<SpotDot> seen, int skip) {
        int n = a.gapsMm.length;
        a.dotsLaid = seen.size();
        a.checkGapMm = n > skip ? a.gapsMm[skip] : null;
        a.landingsMm = new double[n][];
        a.areasMm2 = new Double[n];
        for (int i = 0; i < n && i < seen.size(); i++) {
            Dot d = seen.get(i).state == SpotDot.State.DOT ? seen.get(i).dot : null;
            if (d != null) {
                a.landingsMm[i] = new double[] {d.xMm, d.yMm};
                a.areasMm2[i] = d.areaMm2;
            }
        }
        for (int i = n; i < seen.size(); i++) {
            SpotDot s = seen.get(i);
            CheckDot d = new CheckDot();
            d.state = s.state == SpotDot.State.DOT ? "dot" : s.state == SpotDot.State.UNMEASURED ? "unmeasured" : "none";
            if (s.dot != null) {
                d.diameterMm = s.dot.diameterMm;
                d.dxMm = s.dot.xMm;
                d.dyMm = s.dot.yMm;
                d.areaMm2 = s.dot.areaMm2;
            }
            a.checkDots.add(d);
        }
        double[] ref = n > skip + 1 ? landing(seen.get(skip), seen.get(skip + 1)) : null;
        for (int i = n - 1; ref != null && i >= skip + 2; i--) {
            if (seen.get(i).state == SpotDot.State.DOT) {
                a.squashedShiftMm = shift(seen.get(i), ref);
                break;
            }
        }
        SpotDot second = seen.size() > n + 1 ? seen.get(n + 1) : null;
        String again = ": the landing cannot be checked; look at the tip from below and run the touch-off again";
        if (second == null || second.state != SpotDot.State.DOT) {
            a.landingProblem = String.format(Locale.ROOT, "the 2nd check dot at gap %.3f mm %s%s", a.checkGapMm,
                    second == null || second.state == SpotDot.State.NONE ? "left no dot" : "is " + second.note, again);
            return;
        }
        if (ref == null) {
            return; // no reference pair to compare with: the analysis found no contact either
        }
        a.landingShiftMm = shift(second, ref);
        if (!(a.landingShiftMm <= LANDING_SHIFT_MAX_MM + 1e-9)) { // a NaN centroid fails closed
            a.landingProblem = String.format(Locale.ROOT, "the 2nd check dot landed %.3f mm from where the first two "
                    + "counted dots landed (at most %s mm): the tip may have moved; look at it from below and run the "
                    + "touch-off again", a.landingShiftMm, DispenseProfile.plain(LANDING_SHIFT_MAX_MM));
            return;
        }
        if (!a.found()) {
            return; // areas only against a reference pair the analysis accepted (droplets already refuse the apply)
        }
        double refArea = (seen.get(skip).dot.areaMm2 + seen.get(skip + 1).dot.areaMm2) / 2;
        double off = Math.abs(second.dot.areaMm2 / refArea - 1);
        if (!Double.isFinite(off)) {
            a.landingProblem = "the 2nd check dot's area cannot be compared with the reference pair's: look at the tip "
                    + "from below and run the touch-off again";
            return;
        }
        a.checkAreaOffPct = off * 100;
        if (off > CHECK_AREA_MAX_OFF + 1e-9) {
            a.landingProblem = String.format(Locale.ROOT, "the 2nd check dot's area is off by %.0f %% from the reference "
                    + "pair's (%.3f vs %.3f mm², at most %.0f %%): the needle's height may have changed; look at the tip "
                    + "from below and run the touch-off again", off * 100, second.dot.areaMm2, refArea,
                    CHECK_AREA_MAX_OFF * 100);
        }
    }

    /** The mean landing (centroid offsets, mm) of two measured dots, or null. */
    private static double[] landing(SpotDot p, SpotDot q) {
        return p.state != SpotDot.State.DOT || q.state != SpotDot.State.DOT ? null
                : new double[] {(p.dot.xMm + q.dot.xMm) / 2, (p.dot.yMm + q.dot.yMm) / 2};
    }

    private static double shift(SpotDot s, double[] ref) {
        return Math.hypot(s.dot.xMm - ref[0], s.dot.yMm - ref[1]);
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

    /**
     * BR-85: the steady dot of the mounted tip: its own measured dot of this dose when its dot table has one (not the
     * 22G, whose dots are the bench model's), else the 22G model's ({@link #steadyDotMm(Request, DispenseConfig)}).
     */
    public static double steadyDotMm(Request req, DispenseConfig c, Tip tip) {
        Tip.Measured own = ownDot(req, c, tip);
        return own != null ? own.dMeanMm : steadyDotMm(req, c);
    }

    /** Where {@link #steadyDotMm(Request, DispenseConfig, Tip)} comes from, as {@link Analysis#referenceFrom} says it. */
    public static String referenceFrom(Request req, DispenseConfig c, Tip tip) {
        Tip.Measured own = ownDot(req, c, tip);
        return own == null ? MODEL_REFERENCE : "the tip's dots at " + dotSteps(req, c) + " steps ("
                + ZCheck.dayClock(own.atMs) + ")";
    }

    private static Tip.Measured ownDot(Request req, DispenseConfig c, Tip tip) {
        return tip == null || tip.benchModel || tip.measured == null ? null : tip.measured.get(dotSteps(req, c));
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

    /**
     * BR-85: null when the reference pair, the two dots after the {@code skip} left out, starts at
     * {@link #REFERENCE_GAP_MM} or higher (or nothing is skipped), else the plain refusal: lower, it may be at contact.
     */
    public static String startGapProblem(Request req, int skip, String tipName) {
        double reference = req.startGapMm - skip * req.stepMm; // 0.175 - 2 x 0.025 is 0.12499999999999999 in doubles
        if (skip <= 0 || reference >= REFERENCE_GAP_MM - 1e-9) {
            return null;
        }
        double min = REFERENCE_GAP_MM + skip * req.stepMm;
        return String.format(Locale.ROOT, "the %s's recipe skips the first %d dots after the prime (touchOffSkipDots): "
                + "with a start gap of %.3f mm its reference dots would sit at %.3f mm and %.3f mm, possibly at "
                + "contact; start at %.3f mm or more, so they sit at %.3f mm and %.3f mm as without a skip", tipName,
                skip, req.startGapMm, reference, reference - req.stepMm, Math.ceil(min * 1000 - 1e-9) / 1000,
                REFERENCE_GAP_MM, REFERENCE_GAP_MM - req.stepMm);
    }

    /** The disc (mm across) that cuts the strings off dots of the expected diameter. */
    public static double cutMm(double expectedDotDiameterMm) {
        return CUT_SHARE * expectedDotDiameterMm;
    }

    /**
     * Dot i at blank.x + i * pitch. Its Z is the probed blank plane at that dot plus the offset and
     * gap i (review finding: with one flat Z, a slope of the blank along the ladder was mixed into
     * the gap steps and biased the offset). Without a plane (null) the blank place's single Z is
     * used; runs refuse that (I4: no blank surface). BR-89: then the {@link #CHECK_DOTS} on along the row at
     * {@link #checkGapMm}, after one empty pitch.
     */
    public static List<GcodeStreamBuilder.DotSpec> layout(Request req, DispenseConfig.NamedLocation blank,
            DispenseConfig cfg, SurfacePlane plane) {
        List<GcodeStreamBuilder.DotSpec> l = new ArrayList<>();
        double[] g = gaps(req);
        double check = checkGapMm(req, cfg.profile.touchOffSkipDots);
        for (int i = 0; i < g.length + CHECK_DOTS; i++) {
            boolean ladder = i < g.length;
            // one pitch left empty before the check dots: the floor's squashed dot is wider and would join the first
            double x = blank.x + (ladder ? i : i + 1) * req.pitchMm, y = blank.y;
            double surface = plane == null ? blank.z : plane.z(x, y);
            double z = surface + cfg.needleZOffsetMm + (ladder ? g[i] : check);
            double hop = surface + cfg.needleZOffsetMm + Math.max(g[0], cfg.gapMm()) + cfg.liftMm();
            l.add(new GcodeStreamBuilder.DotSpec(x, y, z, hop, req.rotationDeg, i, String.format(java.util.Locale.ROOT,
                    ladder ? "touch-off gap %.3f" : "touch-off check gap %.3f", ladder ? g[i] : check)));
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
        return analyze(gapsMm, seen, jumpRatio, steadyDotMm, 0);
    }

    /** As above with the first {@code skip} dots after the prime left out ({@link DispenseProfile#touchOffSkipDots}). */
    public static Analysis analyze(double[] gapsMm, List<SpotDot> seen, double jumpRatio, double steadyDotMm,
            int skip) {
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
        return analyze(gapsMm, d, notes, jumpRatio, steadyDotMm, skip);
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

    /** As {@link #analyze(double[], Double[], String[], double, double, int)} with no dot skipped. */
    public static Analysis analyze(double[] gapsMm, Double[] diametersMm, String[] unmeasured, double jumpRatio,
            double steadyDotMm) {
        return analyze(gapsMm, diametersMm, unmeasured, jumpRatio, steadyDotMm, 0);
    }

    /**
     * Far to near: the first {@code skip} dots are left out (a tip's start blob after the prime, BR-85); the next two
     * are the reference and must both form, agree within the jump ratio and be steady dots, at least
     * {@link #MIN_REFERENCE_SHARE} of {@code steadyDotMm} (dots laid too high miss or come out as droplets, then the
     * next one carries the paste: the real ladder's first dot was a 0.37 mm droplet, and two droplets would make the
     * first full dot look squashed); then the first dot squashed by more than the jump ratio, or missing, is the
     * contact. Paste that could not be measured at a dot's spot ({@code unmeasured}) before the contact is reached
     * refuses the conclusion: it could be a squashed dot as well as a normal one.
     *
     * @param gapsMm commanded gaps in any order
     * @param diametersMm measured diameter per gap, null = no dot formed (or not measured)
     * @param unmeasured per gap: why the paste at its spot could not be measured, null = measured or no paste
     * @param steadyDotMm the diameter a steady dot of the ladder's dose makes on the blank ({@link #steadyDotMm}),
     *        NaN = not checked
     * @param skip the dots left out after the prime ({@link DispenseProfile#touchOffSkipDots})
     */
    public static Analysis analyze(double[] gapsMm, Double[] diametersMm, String[] unmeasured, double jumpRatio,
            double steadyDotMm, int skip) {
        Analysis a = new Analysis(gapsMm.clone(), diametersMm.clone(), unmeasured.clone());
        a.skippedDots = skip;
        Integer[] idx = new Integer[gapsMm.length];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (p, q) -> Double.compare(gapsMm[q], gapsMm[p])); // far -> near
        if (idx.length == 0) {
            a.reason = "no dots";
            return a;
        }
        if (idx.length <= skip) {
            a.reason = String.format(Locale.ROOT, "every dot of the ladder is skipped (touchOffSkipDots %d): lay at "
                    + "least %d", skip, skip + 3);
            return a;
        }
        // the texts' "first" dots are the first after the skipped ones
        String after = skip == 0 ? "" : " after the " + skip + " not counted";
        int r0 = idx[skip];
        if (strung(a, gapsMm, unmeasured, r0)) {
            return a;
        }
        Double d0 = diametersMm[r0];
        if (d0 == null) {
            a.reason = skip == 0 ? "no dot at the largest gap: the needle is at or below the surface for every step, or "
                    + "nothing dispensed; raise startGapMm" : String.format(Locale.ROOT, "no dot at gap %.3f mm, the "
                            + "first%s: the needle is at or below the surface from there, or nothing dispensed; raise "
                            + "startGapMm", gapsMm[r0], after);
            return a;
        }
        double refD = d0;
        if (idx.length > skip + 1) {
            int r1 = idx[skip + 1];
            if (strung(a, gapsMm, unmeasured, r1)) {
                return a;
            }
            Double d1 = diametersMm[r1];
            if (d1 == null) {
                a.reason = String.format(Locale.ROOT, "only the first dot%s formed before a missing one (%.3f mm at gap "
                        + "%.3f, none at gap %.3f): a contact needs two steady dots before it (the first may be a "
                        + "droplet from too high); run the ladder again from another start gap", after, d0, gapsMm[r0],
                        gapsMm[r1]);
                return a;
            }
            double lo = Math.min(d0, d1), hi = Math.max(d0, d1);
            if (hi > lo * (1 + jumpRatio)) {
                a.reason = String.format(Locale.ROOT, "the first two dots%s differ too much to be the reference "
                        + "(%.3f mm at gap %.3f, %.3f mm at gap %.3f: more than %.0f %%): the dots far from the "
                        + "surface did not form evenly, or the needle reached the surface at once; run the ladder "
                        + "again from another start gap", after, d0, gapsMm[r0], d1, gapsMm[r1], 100 * jumpRatio);
                return a;
            }
            refD = (d0 + d1) / 2;
        }
        if (refD < MIN_REFERENCE_SHARE * steadyDotMm) {
            String first = idx.length > skip + 1 ? String.format(Locale.ROOT, "two dots%s (%.3f mm at gap %.3f, %.3f mm "
                    + "at gap %.3f) are", after, d0, gapsMm[r0], diametersMm[idx[skip + 1]], gapsMm[idx[skip + 1]])
                    : String.format(Locale.ROOT, "dot%s (%.3f mm at gap %.3f) is", after, d0, gapsMm[r0]);
            a.reason = String.format(Locale.ROOT, "the first %s under %.0f %% of the %.2f mm a steady dot of this dose "
                    + "makes on the blank: droplets from a needle too high, so a larger dot after them is no contact; "
                    + "run the ladder again from a lower start gap", first, 100 * MIN_REFERENCE_SHARE, steadyDotMm);
            return a;
        }
        a.referenceDiameterMm = refD;
        for (int k = skip + 2; k < idx.length; k++) {
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
