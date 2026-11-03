package org.openpnp.instancelabs.padprofile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The suggestion for a group (§4), pure and deterministic: an existing pad type of the same size
 * ({@code use}), the group's own type with a plan ({@code calibrate-existing}), or a new pad type
 * with a dot plan that keeps the screw phase (R1, R6), keeps the needle off earlier dots and does
 * not bridge (§1.6), with the predicted amount and a confidence.
 */
public final class Suggester {
    public static final String PASS = "pass";
    public static final String FALLBACK = "fallback";
    public static final String MARGINAL = "marginal";
    public static final String MARGINAL_FALLBACK = "marginal-fallback";
    /** An edited plan inside no band (kept: the user chose it; the calibration decides). */
    public static final String BREAKS_LIMITS = "breaks-limits";

    private Suggester() {
    }

    /** One dose's numbers: mass per dot (weighed, else another dose's weighed mass per step, else the model) and dot size. */
    static final class DoseView {
        int steps;
        double mgPerDot;
        String mgSource;
        /** Diameter for gaps and the same-pad pitch (d95 or dHi) and for the overhang (mean or dHi). */
        double dGap;
        double dOverhang;
        String dSource;
        double[] dShown;
        DoseEvidence ev;
        boolean measured;
        boolean opticalPass;
        boolean massConfirmed;
    }

    static DoseView view(Map<Integer, DoseEvidence> evidence, int steps) {
        DoseView v = new DoseView();
        v.steps = steps;
        v.ev = evidence == null ? null : evidence.get(steps);
        // the confirmed dose nearest in log steps; a tie (400 and 1600 for 800) goes to the larger, so the
        // result does not depend on the map's order
        DoseEvidence other = null;
        if (evidence != null) {
            for (DoseEvidence e : evidence.values()) {
                if (e == null || e.steps == steps || !e.massConfirmed || e.mgPerStep == null) {
                    continue;
                }
                double d = other == null ? 0
                        : Math.abs(Math.log((double) e.steps / steps)) - Math.abs(Math.log((double) other.steps / steps));
                if (other == null || d < -1e-12 || Math.abs(d) <= 1e-12 && e.steps > other.steps) {
                    other = e;
                }
            }
        }
        if (v.ev != null && v.ev.massConfirmed && v.ev.mgPerDot != null) {
            v.mgPerDot = v.ev.mgPerDot;
            v.mgSource = "weighed";
            v.massConfirmed = true;
        }
        else if (v.ev != null && v.ev.massConfirmed && v.ev.mgPerStep != null) {
            v.mgPerDot = v.ev.mgPerStep * steps;
            v.mgSource = "weighed";
            v.massConfirmed = true;
        }
        else if (other != null) {
            v.mgPerDot = other.mgPerStep * steps;
            v.mgSource = "weighed-per-step";
        }
        else {
            v.mgPerDot = DoseModel.massMg(steps);
            v.mgSource = "model";
        }
        if (v.ev != null && v.ev.hasMeasuredDots()) {
            v.dGap = v.ev.d95Mm;
            v.dOverhang = v.ev.dMeanMm;
            v.dSource = "measured";
            v.measured = true;
            v.opticalPass = v.ev.opticalPass;
            v.dShown = new double[] {r3(v.ev.dMeanMm), r3(v.ev.d95Mm)};
        }
        else {
            v.dGap = DoseModel.dHiMm(steps);
            v.dOverhang = v.dGap;
            v.dSource = "model";
            v.dShown = new double[] {r3(DoseModel.dLoMm(steps)), r3(DoseModel.dHiMm(steps))};
        }
        return v;
    }

    /**
     * Mass per dot of a dose as the suggestions count it: weighed, else another dose's weighed mass
     * per step, else the model (for the plan view's predicted mass per pad, §12.5).
     */
    public static double mgPerDot(Map<Integer, DoseEvidence> evidence, int steps) {
        return view(evidence, steps).mgPerDot;
    }

    /** A dose's dot size as the suggestions show it: measured mean and d95, else the model's band (mm). */
    public static double[] dotDiameterMm(Map<Integer, DoseEvidence> evidence, int steps) {
        return view(evidence, steps).dShown.clone();
    }

    /** A dot plan on a group's openings, evaluated. */
    static final class Candidate {
        int steps;
        int n;
        DoseView view;
        Map<Integer, List<double[]>> local = new HashMap<>();
        double mass;
        double err;
        Neighbours.Eval eval;
        String fit;
        int cls;
        boolean own;
        /** The type whose plan this is (reuse). */
        TypeInfo type;
    }

    /** Dose classes (§4.3 step 5): the profile dose, then measured before untested, phase-free before partial. */
    static List<Integer> classOrder(int profileSteps) {
        List<Integer> l = new ArrayList<>();
        l.add(profileSteps);
        for (int s : new int[] {DoseModel.ONE_TURN, DoseModel.HALF, DoseModel.QUARTER}) {
            if (s != profileSteps) {
                l.add(s);
            }
        }
        return l;
    }

    /** A plan of n dots of s on every opening of the group, against the neighbours' current dots. */
    static Candidate evaluate(ProfileCheck.Ctx c, ProfileCheck.Group g, int s, int n, DoseView v) {
        Candidate x = new Candidate();
        x.steps = s;
        x.n = n;
        x.view = v;
        double pMin = v.dGap / 2 + c.tip().odMm / 2;
        for (int i : g.idx) {
            PadInstance p = c.nb.pads.get(i);
            x.local.put(i, DotGeometry.layoutN(p.wMm, p.hMm, n, pMin, v.dOverhang / 2));
        }
        x.eval = c.nb.evaluate(g.idx, x.local, v.dGap / 2, v.dOverhang / 2);
        x.mass = n * v.mgPerDot;
        x.err = x.mass / g.target - 1;
        x.cls = classOrder(c.in.profileSteps).indexOf(s);
        x.fit = fitOf(x);
        return x;
    }

    static String fitOf(Candidate x) {
        boolean massPass = x.err >= Limits.MASS_TOL_LOW - Limits.EPS && x.err <= Limits.MASS_TOL_HIGH + Limits.EPS;
        boolean massFallback = x.err >= Limits.FALLBACK_LOW - Limits.EPS && x.err <= Limits.FALLBACK_HIGH + Limits.EPS;
        if (x.eval.passes()) {
            return massPass ? PASS : massFallback ? FALLBACK : null;
        }
        if (x.eval.marginal()) {
            return massPass ? MARGINAL : massFallback ? MARGINAL_FALLBACK : null;
        }
        return null;
    }

    /** Every allowed candidate of §4.3 step 2 (doses the tip allows, n 1..6, n·s at least half a turn). */
    static List<Candidate> candidates(ProfileCheck.Ctx c, ProfileCheck.Group g, Map<Integer, DoseView> views) {
        List<Candidate> l = new ArrayList<>();
        for (int s : classOrder(c.in.profileSteps)) {
            if (!DoseModel.isAllowed(s, c.tip())) {
                continue;
            }
            for (int n = 1; n <= Limits.MAX_DOTS; n++) {
                if (n * s * 2 < c.in.stepsPerRev) {
                    continue;
                }
                l.add(evaluate(c, g, s, n, views.get(s)));
            }
        }
        return l;
    }

    private static final String[] FIT_ORDER = {PASS, FALLBACK, MARGINAL, MARGINAL_FALLBACK};

    /** Steps 5-8: pass by (class, |err|, n); fallbacks by the nearer amount (whole %), ties to the larger. */
    static Candidate choose(List<Candidate> all) {
        for (String fit : FIT_ORDER) {
            List<Candidate> l = new ArrayList<>();
            for (Candidate x : all) {
                if (fit.equals(x.fit)) {
                    l.add(x);
                }
            }
            if (!l.isEmpty()) {
                l.sort(comparator(fit));
                return l.get(0);
            }
        }
        return null;
    }

    static Comparator<Candidate> comparator(String fit) {
        if (FALLBACK.equals(fit) || MARGINAL_FALLBACK.equals(fit)) {
            return Comparator.<Candidate>comparingLong(x -> Math.round(Math.abs(x.err) * 100))
                    .thenComparing(x -> -x.mass).thenComparingInt(x -> x.cls).thenComparingInt(x -> x.n);
        }
        return Comparator.<Candidate>comparingInt(x -> x.cls).thenComparingDouble(x -> Math.abs(x.err))
                .thenComparingInt(x -> x.n);
    }

    /** §4.3: the best plan for a group with the current evidence, or null (no plan: reason tip). */
    static Candidate search(ProfileCheck.Ctx c, ProfileCheck.Group g) {
        return choose(candidates(c, g, c.views));
    }

    /** The plan closest to the target for the no-plan text: the nearest mass, then the smallest violation (null: none). */
    static Candidate closest(ProfileCheck.Ctx c, ProfileCheck.Group g) {
        List<Candidate> all = candidates(c, g, c.views);
        if (all.isEmpty()) {
            return null;
        }
        all.sort(Comparator.<Candidate>comparingLong(x -> Math.round(Math.abs(x.err) * 100))
                .thenComparingDouble(x -> violation(x.eval)).thenComparingInt(x -> x.cls).thenComparingInt(x -> x.n));
        return all.get(0);
    }

    private static double violation(Neighbours.Eval e) {
        return Math.max(0, e.overhang - Limits.OVERHANG_MARGINAL_MM) + Math.max(0, Limits.DOT_GAP_MARGINAL_MM - e.dotGap)
                + Math.max(0, Limits.COPPER_GAP_MARGINAL_MM - e.copperGap);
    }

    /**
     * A type's plan on the group's openings (§6.1): its own dose and dots per pad, laid with the
     * pitch floor; a type without a plan of its own gets the profile's dots by pad size (DotSizer).
     */
    static Candidate typePlan(ProfileCheck.Ctx c, ProfileCheck.Group g, TypeInfo t) {
        Candidate x = new Candidate();
        x.steps = t.dotSteps != null ? t.dotSteps : c.in.profileSteps;
        x.view = c.view(x.steps);
        x.own = t.hasOwnPlan();
        double pMin = x.view.dGap / 2 + c.tip().odMm / 2;
        for (int i : g.idx) {
            PadInstance p = c.nb.pads.get(i);
            int n = t.dotsPerPad != null ? t.dotsPerPad
                    : DotGeometry.profileDotCount(p.areaMm2(), t.largestSingleDotPadMm2, t.maxDots);
            x.n = n;
            x.local.put(i, x.own ? DotGeometry.layoutN(p.wMm, p.hMm, n, pMin, x.view.dOverhang / 2)
                    : DotGeometry.layoutN(p.wMm, p.hMm, n, 0, 0));
        }
        x.mass = x.n * x.view.mgPerDot;
        x.err = x.mass / g.target - 1;
        x.cls = classOrder(c.in.profileSteps).indexOf(x.steps);
        return x;
    }

    /** The planned dots of a plan's group (null when some pad of the group has none). */
    static Candidate plannedPlan(ProfileCheck.Ctx c, ProfileCheck.Group g) {
        Candidate x = new Candidate();
        for (int i : g.idx) {
            PadInstance p = c.nb.pads.get(i);
            if (p.dots == null || p.dots.isEmpty()) {
                return null;
            }
            List<double[]> l = new ArrayList<>();
            for (double[] d : p.dots) {
                l.add(DotGeometry.toPad(p, d[0], d[1]));
            }
            x.local.put(i, l);
            x.n = l.size();
            x.steps = p.dotSteps != null ? p.dotSteps : c.in.profileSteps;
        }
        x.view = c.view(x.steps);
        x.mass = x.n * x.view.mgPerDot;
        x.err = x.mass / g.target - 1;
        return x;
    }

    // ---- the suggestion -----------------------------------------------------------------------

    static Suggestion suggest(ProfileCheck.Ctx c, ProfileCheck.Group g) {
        Suggestion s = new Suggestion();
        s.group = g.group;
        String reason = g.reason;
        if (ProfileCheck.TIP.equals(reason) || ProfileCheck.PART.equals(reason)
                || ProfileCheck.NO_PAD_SIZE.equals(reason) || g.plan == null) {
            s.kind = Suggestion.NONE;
            s.id = "s" + GroupKey.sha12(g.group + "|none|" + reason + "|" + c.typesFingerprint);
            return s;
        }
        Candidate use = reuse(c, g);
        if (use != null) {
            return useSuggestion(c, g, use);
        }
        boolean covered = g.type != null && !g.type.retired && ratioIn(g.area / g.type.calArea(),
                Limits.COVER_RATIO_LOW, Limits.COVER_RATIO_HIGH);
        Candidate chosen = g.plan;
        String kind = Suggestion.NEW;
        if (ProfileCheck.BELOW_GATE.equals(reason) && covered) {
            kind = Suggestion.CALIBRATE_EXISTING;
            if (g.type.hasOwnPlan()) {
                chosen = typePlan(c, g, g.type);
                chosen.eval = c.nb.evaluate(g.idx, chosen.local, chosen.view.dGap / 2, chosen.view.dOverhang / 2);
                chosen.fit = fitOf(chosen);
                if (chosen.fit == null) {
                    chosen.fit = BREAKS_LIMITS;
                }
                s.planFrom = "your plan";
            }
            else {
                s.planFrom = "suggested";
            }
        }
        s.kind = kind;
        s.family = g.family;
        s.opening = opening(g);
        if (Suggestion.NEW.equals(kind)) {
            String base = GroupKey.basePackage(g.packageIds.get(0));
            s.typeKey = uniqueKey(c, key(base, g.key.longMm, g.key.shortMm));
            s.name = base + " · " + Texts.mm(g.key.longMm) + " × " + Texts.mm(g.key.shortMm) + " mm"
                    + (c.byPackage.get(g.packageIds.get(0)).size() > 1 ? padRange(g) : "");
            s.mapping = mapping(g);
            s.pad = s.opening;
        }
        else {
            s.typeKey = g.type.key;
            s.typeVersion = g.type.version;
            s.name = g.type.name;
        }
        s.amount = amount(c, g);
        fill(c, g, s, chosen);
        s.phase = phase(c, chosen);
        confidence(c, g, s, chosen);
        s.sentence = sentence(c, g, s, chosen, kind);
        calibration(c, g, s, chosen);
        alternatives(c, g, s, chosen);
        s.id = id(c, g, s, chosen);
        return s;
    }

    private static boolean ratioIn(double r, double lo, double hi) {
        return r >= lo - Limits.EPS && r <= hi + Limits.EPS;
    }

    /** §4.2: a current type at or above the gate, of this tip and recipe, of the same pad size, whose own plan fits. */
    static Candidate reuse(ProfileCheck.Ctx c, ProfileCheck.Group g) {
        Candidate best = null;
        for (TypeInfo t : new TreeMap<>(c.in.types).values()) {
            if (t.retired || TypeInfo.rank(t.status) < TypeInfo.rank(c.in.gate) || c.otherTip(t)
                    || t.doseFp != null && c.in.doseFingerprint != null && !t.doseFp.equals(c.in.doseFingerprint)
                    || g.type != null && t.key.equals(g.type.key)) {
                continue;
            }
            double ratio = g.area / t.calArea();
            double tl = Math.max(t.padWMm, t.padHMm);
            double ts = Math.min(t.padWMm, t.padHMm);
            if (!ratioIn(ratio, Limits.SAME_SIZE_RATIO_LOW, Limits.SAME_SIZE_RATIO_HIGH)
                    || Math.abs(tl - g.key.longMm) > Limits.SAME_SIZE_MM + Limits.EPS
                    || Math.abs(ts - g.key.shortMm) > Limits.SAME_SIZE_MM + Limits.EPS) {
                continue;
            }
            Candidate x = typePlan(c, g, t);
            if (!DoseModel.isAllowed(x.steps, c.tip()) && x.own || x.steps * x.n * 2 < c.in.stepsPerRev) {
                continue;
            }
            x.eval = c.nb.evaluate(g.idx, x.local, x.view.dGap / 2, x.view.dOverhang / 2);
            if (!x.eval.passes() || x.err < Limits.MASS_TOL_LOW - Limits.EPS || x.err > Limits.MASS_TOL_HIGH + Limits.EPS) {
                continue;
            }
            x.fit = PASS;
            x.type = t;
            boolean better = best == null;
            if (!better) {
                double d = Math.abs(ratio - 1) - Math.abs(g.area / best.type.calArea() - 1);
                better = d < -1e-12 || Math.abs(d) <= 1e-12 && TypeInfo.rank(t.status) > TypeInfo.rank(best.type.status);
            }
            if (better) {
                best = x;
            }
        }
        return best;
    }

    private static Suggestion useSuggestion(ProfileCheck.Ctx c, ProfileCheck.Group g, Candidate x) {
        TypeInfo t = x.type;
        Suggestion s = new Suggestion();
        s.kind = Suggestion.USE;
        s.group = g.group;
        s.typeKey = t.key;
        s.typeVersion = t.version;
        s.name = t.name;
        s.family = t.family;
        s.mapping = mapping(g);
        s.opening = opening(g);
        s.amount = amount(c, g);
        fill(c, g, s, x);
        s.fit = PASS;
        int rank = TypeInfo.rank(t.status);
        s.confidence = rank >= 3 ? "high" : rank == 2 ? "medium" : "low";
        if (rank < 3) {
            s.confidenceWhy.add(t.name + " is " + Texts.statusWord(t.status) + ", not validated on a board yet");
        }
        String d = dotWidth(x.view);
        s.sentence = "Use " + t.name + ": same pad (" + Texts.mm(t.padWMm) + " × " + Texts.mm(t.padHMm) + " mm; these are "
                + Texts.mm(g.aperture.wMm) + " × " + Texts.mm(g.aperture.hMm) + " mm). " + x.n + " "
                + DoseModel.turnName(x.steps) + " " + (x.n == 1 ? "dot" : "dots") + " per pad, " + Texts.mg(x.mass)
                + " mg, dots " + d + " mm wide.";
        s.id = "s" + GroupKey.sha12(g.group + "|use|" + t.key + "|" + t.version + "|" + x.steps + "|" + x.n + "|"
                + evidenceKey(x.view) + "|" + c.typesFingerprint);
        return s;
    }

    private static Suggestion.Opening opening(ProfileCheck.Group g) {
        Suggestion.Opening o = new Suggestion.Opening();
        o.wMm = g.aperture.wMm;
        o.hMm = g.aperture.hMm;
        o.roundness = g.aperture.roundness;
        o.areaMm2 = g.aperture.areaMm2;
        return o;
    }

    private static Suggestion.Mapping mapping(ProfileCheck.Group g) {
        Suggestion.Mapping m = new Suggestion.Mapping();
        m.packageId = g.packageIds.get(0);
        m.padNames = g.allPadsOfPackage ? null : new ArrayList<>(g.padNames);
        return m;
    }

    private static String padRange(ProfileCheck.Group g) {
        String first = g.padNames.get(0);
        String last = g.padNames.get(g.padNames.size() - 1);
        return first.equals(last) ? " pad " + first : " pads " + first + "–" + last;
    }

    /** slug(package) cut to 34 characters + "-{L×100}x{S×100}" (A/PadTypeEndpoints KEY: [a-z0-9][a-z0-9-]{0,47}). */
    static String key(String basePackage, double longMm, double shortMm) {
        String k = basePackage.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (k.length() > 34) {
            k = k.substring(0, 34).replaceAll("-+$", "");
        }
        if (k.isEmpty()) {
            k = "pad";
        }
        return k + "-" + Texts.num(longMm * 100, 0) + "x" + Texts.num(shortMm * 100, 0);
    }

    private static String uniqueKey(ProfileCheck.Ctx c, String base) {
        String k = base;
        for (int i = 2; c.in.types.containsKey(k) || c.newKeys.contains(k); i++) {
            k = base + "-" + i;
        }
        c.newKeys.add(k);
        return k;
    }

    private static Suggestion.Amount amount(ProfileCheck.Ctx c, ProfileCheck.Group g) {
        Suggestion.Amount a = new Suggestion.Amount();
        a.targetMg = r3(g.target);
        a.k = g.k;
        a.mgPerMm2 = r3(g.anchor.mgPerMm2);
        a.capped = g.capped;
        String rule = Texts.mg(g.anchor.mgPerMm2) + " mg per mm² of opening: " + anchorShort(c, g.anchor);
        if (g.capped) {
            rule = Texts.mg(Limits.PASTE_DENSITY_MG_MM3 * Limits.CAP_PRINT_MM) + " mg per mm² of opening: a "
                    + Texts.mm(Limits.CAP_PRINT_MM) + " mm print, the most at this " + Texts.mm(g.pitch)
                    + " mm pitch (" + anchorShort(c, g.anchor) + " would give " + Texts.mg(g.uncappedTarget) + " mg)";
        }
        if (g.k != 1) {
            rule += ", times k " + Texts.num(g.k, 1) + " (a placeholder for " + (g.centre ? "centre pads" : "pads above "
                    + Texts.num(Limits.LARGE_PAD_MM2, 0) + " mm²") + ")";
        }
        a.rule = rule;
        return a;
    }

    /** "the half turn you validated on 0603 pads (1.06 mg on 0.73 mm²)". */
    static String anchorShort(ProfileCheck.Ctx c, Anchor a) {
        if (a.bench) {
            return "the half turn you validated on 0603 pads (" + Texts.mg(a.massPerPadMg) + " mg on " + Texts.mm(a.areaMm2)
                    + " mm²)";
        }
        return typeName(c, a.typeKey) + " validated on a board (" + Texts.mg(a.massPerPadMg) + " mg on "
                + Texts.mm(a.areaMm2) + " mm²)";
    }

    static String anchorWords(ProfileCheck.Ctx c, Anchor a) {
        return a.bench ? Anchor.BENCH.text : anchorShort(c, a);
    }

    private static String typeName(ProfileCheck.Ctx c, String key) {
        TypeInfo t = key == null ? null : c.in.types.get(key);
        return t != null && t.name != null ? t.name : String.valueOf(key);
    }

    private static void fill(ProfileCheck.Ctx c, ProfileCheck.Group g, Suggestion s, Candidate x) {
        Suggestion.Dots d = new Suggestion.Dots();
        d.perPad = x.n;
        d.steps = x.steps;
        d.turn = DoseModel.turnNoun(x.steps);
        d.netDeg = Math.round(DoseModel.netDeg(x.steps, c.in.bStepsPerDeg) * 100) / 100.0;
        d.mgPerDot = r3(x.view.mgPerDot);
        d.mgSource = x.view.mgSource;
        d.diameterMm = x.view.dShown.clone();
        d.dSource = x.view.dSource;
        for (double[] p : x.local.get(g.idx[0])) {
            d.positions.add(new double[] {r3(p[0]), r3(p[1])});
        }
        s.dots = d;
        Suggestion.Predicted p = new Suggestion.Predicted();
        p.mgPerPad = r3(x.mass);
        p.errorPct = Texts.wholePct(x.err);
        p.overhangMm = r3(x.eval.overhang);
        p.dotGapMm = Double.isInfinite(x.eval.dotGap) ? 99 : r3(x.eval.dotGap);
        p.padGapMm = Double.isInfinite(x.eval.copperGap) ? 99 : r3(x.eval.copperGap);
        double[] rp = DoseModel.padRipplePct(x.steps * x.n);
        p.padRipplePct = new double[] {Math.round(rp[0]), Math.round(rp[1])};
        s.predicted = p;
        s.fit = x.fit;
    }

    private static Suggestion.Phase phase(ProfileCheck.Ctx c, Candidate x) {
        Suggestion.Phase ph = new Suggestion.Phase();
        String turn = DoseModel.turnName(x.steps);
        String inRow = x.n == 1 ? "one per pad" : "each pad gets its " + x.n + " dots in a row (" + padTurnWords(x.steps * x.n)
                + ")";
        if (x.steps == c.in.profileSteps) {
            ph.rule = "profile";
            ph.text = cap(turn) + " dots, the profile's own: laid with the board's other " + turn + " dots, " + inRow + ".";
            return ph;
        }
        ph.rule = "block";
        int flush = 4;
        String after = "after the " + DoseModel.turnName(c.in.profileSteps) + " dots";
        if (DoseModel.isPartial(x.steps)) {
            ph.text = cap(turn) + " dots are laid together " + after + "; " + (flush + 1) + " dots on the purge row first "
                    + "bring the screw to the same start angle, so every " + DoseModel.turnNoun(x.steps) + " repeats, and "
                    + inRow + ".";
        }
        else {
            ph.text = cap(turn) + " dots are laid together " + after + "; " + flush + " dots on the purge row first flush "
                    + "the screw (a whole turn needs no start angle), and " + inRow + ".";
        }
        return ph;
    }

    private static String padTurnWords(int padSteps) {
        int q = padSteps * 4 / DoseModel.STEPS_PER_REV;
        if (padSteps * 4 % DoseModel.STEPS_PER_REV != 0) {
            return padSteps + " steps of the screw";
        }
        switch (q) {
            case 2:
                return "half a screw turn, like the 0603 pads";
            case 3:
                return "three quarters of a screw turn";
            case 4:
                return "one screw turn";
            default:
                return Texts.num(q / 4.0, 2).replaceAll("0+$", "").replaceAll("\\.$", "") + " screw turns";
        }
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String dotWidth(DoseView v) {
        return Math.abs(v.dShown[0] - v.dShown[1]) < 0.005 ? Texts.mm(v.dShown[1])
                : Texts.mm(v.dShown[0]) + "–" + Texts.mm(v.dShown[1]);
    }

    /** §4.5: high, medium or low, and every reason that kept it from high. */
    private static void confidence(ProfileCheck.Ctx c, ProfileCheck.Group g, Suggestion s, Candidate x) {
        List<String> why = s.confidenceWhy;
        DoseView v = x.view;
        double ratio = g.area / g.anchor.areaMm2;
        boolean fitPass = PASS.equals(x.fit);
        boolean neverQuarter = x.steps == DoseModel.QUARTER && (v.ev == null || v.ev.n == 0);
        boolean high = v.measured && v.opticalPass && v.massConfirmed && g.ownAnchor
                && ratioIn(ratio, Limits.COVER_RATIO_LOW, Limits.COVER_RATIO_HIGH) && g.k == 1 && fitPass;
        // medium needs an anchor of the family (its own, or the bench's for chips), §4.5
        boolean medium = (x.steps == DoseModel.HALF || x.steps == DoseModel.ONE_TURN) && g.ownAnchor
                && ratioIn(ratio, Limits.MEDIUM_RATIO_LOW, Limits.MEDIUM_RATIO_HIGH) && g.k == 1 && fitPass;
        s.confidence = high ? "high" : medium ? "medium" : "low";
        if (neverQuarter) {
            why.add("a quarter turn has never been dispensed on this machine");
        }
        else if (!(v.measured && v.opticalPass)) {
            why.add("no fresh camera check of " + DoseModel.turnName(x.steps) + " dots on the blank yet");
        }
        if (!g.ownAnchor) {
            why.add("no validated " + g.family + " pads yet: the amount is scaled from the 0603 half turn"
                    + (g.capped ? " and capped at a " + Texts.mm(Limits.CAP_PRINT_MM) + " mm print at this "
                            + Texts.mm(g.pitch) + " mm pitch" : ""));
        }
        if (g.k != 1) {
            why.add(g.centre ? "the amount for a centre pad is a placeholder (k " + Texts.num(g.k, 1) + ")"
                    : "the amount for pads above " + Texts.num(Limits.LARGE_PAD_MM2, 0) + " mm² is a placeholder (k "
                            + Texts.num(g.k, 1) + ")");
        }
        if (!ratioIn(ratio, Limits.COVER_RATIO_LOW, Limits.COVER_RATIO_HIGH)) {
            why.add("this pad is " + Texts.num(ratio, 1) + "× the " + (g.anchor.bench ? "0603"
                    : typeName(c, g.anchor.typeKey)) + " pad the amount is anchored on");
        }
        if (FALLBACK.equals(x.fit) || MARGINAL_FALLBACK.equals(x.fit) || BREAKS_LIMITS.equals(x.fit)) {
            why.add("the closest amount that fits is " + Texts.signedPct(x.err) + " %");
        }
        if (!PASS.equals(x.fit) && !FALLBACK.equals(x.fit)) {
            Neighbours.Eval e = x.eval;
            if (e.dotGap < Limits.DOT_GAP_MM - Limits.EPS) {
                why.add("marginal: the dots leave " + Texts.mm(e.dotGap) + " mm to the next pad ("
                        + Texts.mm(Limits.DOT_GAP_MM) + " wanted); the measured dot size decides");
            }
            if (e.overhang > Limits.OVERHANG_MM + Limits.EPS) {
                why.add("marginal: the dots reach " + Texts.mm(e.overhang) + " mm past the " + Texts.mm(e.overhangAcrossMm)
                        + " mm pad (" + Texts.mm(Limits.OVERHANG_MM) + " wanted); the measured dot size decides");
            }
            if (e.copperGap < Limits.COPPER_GAP_MM - Limits.EPS) {
                why.add("marginal: the dots come within " + Texts.mm(e.copperGap) + " mm of the next pad's copper ("
                        + Texts.mm(Limits.COPPER_GAP_MM) + " wanted); the measured dot size decides");
            }
        }
        if ("weighed-per-step".equals(v.mgSource)) {
            why.add("the mass per step comes from another turn size");
        }
        else if (!v.massConfirmed) {
            why.add("no weighing at this recipe yet");
        }
    }

    private static String sentence(ProfileCheck.Ctx c, ProfileCheck.Group g, Suggestion s, Candidate x, String kind) {
        StringBuilder b = new StringBuilder();
        if (Suggestion.NEW.equals(kind)) {
            b.append("New pad type ").append(s.name).append(": ");
        }
        else {
            b.append("Calibrate ").append(s.name).append(" for these pads").append("your plan".equals(s.planFrom)
                    ? " with your plan" : "").append(": ");
        }
        b.append(x.n).append(' ').append(DoseModel.turnName(x.steps)).append(' ').append(x.n == 1 ? "dot" : "dots")
                .append(" per pad (").append(dotWidth(x.view)).append(" mm wide), about ").append(Texts.mg(x.mass))
                .append(" mg per pad, ");
        String target = g.capped ? "the " + Texts.mg(g.target) + " mg target for these pads (a "
                + Texts.mm(Limits.CAP_PRINT_MM) + " mm print at this " + Texts.mm(g.pitch) + " mm pitch)"
                : "the " + Texts.mg(g.target) + " mg these pads would get at your " + (g.anchor.bench ? "0603"
                        : typeName(c, g.anchor.typeKey)) + " rate";
        if ("0".equals(Texts.pct(x.err))) {
            b.append("the same as ").append(target);
        }
        else {
            b.append(Texts.pct(x.err)).append(" % ").append(x.err < 0 ? "less" : "more").append(" than ").append(target);
        }
        Neighbours.Eval e = x.eval;
        if (e.overhang > Limits.OVERHANG_MM + Limits.EPS) {
            b.append("; the dots may reach ").append(Texts.mm(e.overhang))
                    .append(" mm past the pad edge (the calibration's measured dot decides).");
        }
        else if (e.dotGap < Limits.DOT_GAP_MM - Limits.EPS) {
            b.append("; the dots may leave only ").append(Texts.mm(e.dotGap))
                    .append(" mm to the next pad's dot (the calibration's measured dot decides).");
        }
        else if (e.copperGap < Limits.COPPER_GAP_MM - Limits.EPS) {
            b.append("; the dots may come within ").append(Texts.mm(e.copperGap))
                    .append(" mm of the next pad (the calibration's measured dot decides).");
        }
        else if (e.overhang > 0) {
            b.append("; at most ").append(Texts.mm(e.overhang)).append(" mm past the pad edge.");
        }
        else {
            b.append("; the dots stay on the pad.");
        }
        return b.toString();
    }

    /** The chosen dose, the next allowed doses on either side with a passing plan (at most 3), the control, the weighing. */
    private static void calibration(ProfileCheck.Ctx c, ProfileCheck.Group g, Suggestion s, Candidate x) {
        Suggestion.Calibration cal = new Suggestion.Calibration();
        List<Integer> allowed = new ArrayList<>();
        for (int st : DoseModel.ALLOWED_STEPS) {
            if (DoseModel.isAllowed(st, c.tip())) {
                allowed.add(st);
            }
        }
        int at = allowed.indexOf(x.steps);
        List<Candidate> all = candidates(c, g, c.views);
        for (int k = 0; k < allowed.size(); k++) {
            int st = allowed.get(k);
            boolean side = at >= 0 && Math.abs(k - at) == 1;
            if (st == x.steps || side && hasPass(all, st)) {
                cal.doses.add(st);
            }
        }
        if (!cal.doses.contains(x.steps)) {
            cal.doses.add(0, x.steps);
        }
        if (g.plan != null && g.plan != x && !cal.doses.contains(g.plan.steps)) {
            cal.doses.add(g.plan.steps);
        }
        cal.doses.sort(Integer::compare);
        cal.control = c.in.profileSteps;
        for (int st : cal.doses) {
            if (!c.view(st).massConfirmed) {
                cal.weigh.add(st);
            }
        }
        s.calibration = cal;
    }

    private static boolean hasPass(List<Candidate> all, int steps) {
        for (Candidate x : all) {
            if (x.steps == steps && PASS.equals(x.fit)) {
                return true;
            }
        }
        return false;
    }

    /** Per other allowed dose: its best plan and why it was not chosen; an edited plan lists §4.3's best first. */
    private static void alternatives(ProfileCheck.Ctx c, ProfileCheck.Group g, Suggestion s, Candidate x) {
        List<Candidate> all = candidates(c, g, c.views);
        if (g.plan != null && g.plan != x && (g.plan.steps != x.steps || g.plan.n != x.n)) {
            s.alternatives.add(alternative(g.plan, "the suggested plan (" + g.plan.fit + "): the ladder measures it too"));
        }
        for (int st : classOrder(c.in.profileSteps)) {
            if (st == x.steps || !DoseModel.isAllowed(st, c.tip()) || g.plan != null && g.plan != x && st == g.plan.steps) {
                continue;
            }
            Candidate best = null;
            List<Candidate> mine = new ArrayList<>();
            for (Candidate y : all) {
                if (y.steps == st) {
                    mine.add(y);
                }
            }
            if (mine.isEmpty()) {
                continue;
            }
            for (String fit : FIT_ORDER) {
                for (Candidate y : mine) {
                    if (best == null && fit.equals(y.fit)) {
                        List<Candidate> same = new ArrayList<>();
                        for (Candidate z : mine) {
                            if (fit.equals(z.fit)) {
                                same.add(z);
                            }
                        }
                        same.sort(comparator(fit));
                        best = same.get(0);
                    }
                }
            }
            if (best == null) {
                mine.sort(Comparator.<Candidate>comparingDouble(y -> Math.abs(y.err)).thenComparingInt(y -> y.n));
                best = mine.get(0);
            }
            s.alternatives.add(alternative(best, why(best, x, g)));
        }
    }

    private static Suggestion.Alternative alternative(Candidate y, String why) {
        Suggestion.Alternative a = new Suggestion.Alternative();
        a.steps = y.steps;
        a.perPad = y.n;
        a.mgPerPad = r3(y.mass);
        a.why = why;
        return a;
    }

    private static String why(Candidate y, Candidate chosen, ProfileCheck.Group g) {
        String dots = (y.n == 1 ? "a " + DoseModel.turnName(y.steps) + " dot" : y.n + " " + DoseModel.turnName(y.steps)
                + " dots") + " (" + Texts.mm(y.view.dGap) + " mm)";
        boolean one = y.n == 1;
        Neighbours.Eval e = y.eval;
        if (e.overhang > Limits.OVERHANG_MM + Limits.EPS) {
            return dots + (one ? " reaches " : " reach ") + Texts.mm(e.overhang) + " mm past the "
                    + Texts.mm(e.overhangAcrossMm) + " mm pad";
        }
        if (e.dotGap < Limits.DOT_GAP_MM - Limits.EPS) {
            return dots + (one ? " leaves " : " leave ") + Texts.mm(e.dotGap) + " mm to the next pad's dot";
        }
        if (e.copperGap < Limits.COPPER_GAP_MM - Limits.EPS) {
            return dots + (one ? " comes" : " come") + " within " + Texts.mm(e.copperGap) + " mm of the next pad";
        }
        if (y.err < Limits.MASS_TOL_LOW - Limits.EPS || y.err > Limits.MASS_TOL_HIGH + Limits.EPS) {
            return dots + (one ? " gives " : " give ") + Texts.mg(y.mass) + " mg per pad, " + Texts.pct(y.err) + " % "
                    + (y.err < 0 ? "less" : "more") + " than the " + Texts.mg(g.target) + " mg target";
        }
        return dots + " also fit (" + Texts.mg(y.mass) + " mg per pad, " + Texts.signedPct(y.err) + " %): the "
                + DoseModel.turnName(chosen.steps) + " plan comes first";
    }

    private static String evidenceKey(DoseView v) {
        DoseEvidence e = v.ev;
        return v.steps + ":" + v.mgSource + ":" + v.mgPerDot + ":" + v.dSource + ":" + v.dGap + ":" + v.dOverhang + ":"
                + (e == null ? "-" : e.massConfirmed + "/" + e.massReadings + "/" + e.opticalPass + "/" + e.n);
    }

    private static String id(ProfileCheck.Ctx c, ProfileCheck.Group g, Suggestion s, Candidate x) {
        StringBuilder b = new StringBuilder(g.group).append('|').append(s.kind).append('|').append(s.typeKey).append('|')
                .append(x.steps).append('x').append(x.n).append('|').append(g.anchor.bench ? "bench" : g.anchor.typeKey)
                .append(':').append(g.anchor.version).append(':').append(g.anchor.mgPerMm2).append('|');
        for (int st : DoseModel.ALLOWED_STEPS) {
            b.append(evidenceKey(c.view(st))).append(';');
        }
        return "s" + GroupKey.sha12(b.append('|').append(c.typesFingerprint).toString());
    }

    /** Rounded to 0.001 (mm, mg in results). */
    static double r3(double x) {
        return Math.round(x * 1000) / 1000.0;
    }
}
