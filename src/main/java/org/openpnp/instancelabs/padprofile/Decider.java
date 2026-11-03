package org.openpnp.instancelabs.padprofile;

import java.util.ArrayList;
import java.util.List;

/**
 * The decision after a calibration (§7.6), pure. Per group, with the pooled fresh evidence of every
 * dose ({@link CheckInputs#evidence}: every fresh record, pass or fail, statistics recomputed on the
 * pooled dots): the chosen plan (the user's edited plan for calibrate-existing) stays unless it fails
 * a check and an alternative with optical evidence passes. A type becomes {@code characterized} only
 * when every check passes; fallback plans stay provisional; nothing here is ever {@code validated}.
 */
public final class Decider {
    public static final String CHARACTERIZED = TypeInfo.CHARACTERIZED;
    public static final String PROVISIONAL = TypeInfo.PROVISIONAL;

    private Decider() {
    }

    public static final class Inputs {
        /** The board's openings and types, the tip, the profile, and the pooled fresh evidence per dose. */
        public CheckInputs check;
        /** The group the calibration was for. */
        public String group;
        /** The plan saved as provisional before the calibration. */
        public int steps;
        public int perPad;
        /** The plan is the user's own (planFrom "your plan"). */
        public boolean edited;
        /** The control (§7.4) passed, else its text. False until the job says so (fails closed). */
        public boolean controlPass;
        public String controlText;
        /** The regulator reading was attested for the job (§12.2). False until the job says so. */
        public boolean regulatorAttested;
        /** The tip the records were made with (null = the current tip). */
        public String recordsTipKey;
        /** The before-start view named the demotion of a validated type. */
        public boolean validatedNamed;
        public LadderStats.Settings settings = new LadderStats.Settings();
    }

    /** One line of the result: predicted / measured / limit. */
    public static final class Check {
        public String key;
        public boolean ok;
        public String predicted;
        public String measured;
        public String limit;
        /** Plain words when the check fails. */
        public String text;

        Check(String key, boolean ok, String predicted, String measured, String limit, String text) {
            this.key = key;
            this.ok = ok;
            this.predicted = predicted;
            this.measured = measured;
            this.limit = limit;
            this.text = ok ? null : text;
        }
    }

    public static final class Decision {
        public String group;
        public String typeKey;
        /** characterized | provisional (never validated). */
        public String outcome;
        public int steps;
        public int perPad;
        /** Dot centres on the group's first opening (pad frame). */
        public List<double[]> positions = new ArrayList<>();
        public double mgPerDot;
        public double massPerPadMg;
        public double targetMg;
        public int errorPct;
        public String fit;
        /** The chosen plan failed and an alternative passed. */
        public boolean replaced;
        public String replacedText;
        public List<Check> checks = new ArrayList<>();
        /** The first failed check in plain words (null when characterized). */
        public String why;
        /** Per-pad spread from the screw angle, %: predicted (a 0.11 and 0.26) and measured. */
        public double[] padRipplePct;
        public Double measuredRipplePct;
        public List<String> notes = new ArrayList<>();
    }

    public static Decision decide(Inputs in) {
        ProfileCheck.Ctx c = ProfileCheck.prepare(in.check);
        ProfileCheck.Group g = null;
        for (ProfileCheck.Group x : c.groups) {
            if (x.group.equals(in.group)) {
                g = x;
            }
        }
        if (g == null || g.idx.length == 0) {
            throw new IllegalArgumentException("no group " + in.group + " with pads on this board");
        }
        Suggester.Candidate chosen = Suggester.evaluate(c, g, in.steps, in.perPad, c.view(in.steps));
        List<Check> chosenChecks = checks(in, c, g, chosen);
        Suggester.Candidate pick = chosen;
        List<Check> pickChecks = chosenChecks;
        if (!allOk(chosenChecks)) {
            List<Suggester.Candidate> alt = new ArrayList<>();
            for (Suggester.Candidate x : Suggester.candidates(c, g, c.views)) {
                DoseEvidence e = in.check.evidence.get(x.steps);
                if (e != null && e.opticalFresh && x.fit != null && !(x.steps == chosen.steps && x.n == chosen.n)) {
                    alt.add(x);
                }
            }
            for (String fit : new String[] {Suggester.PASS, Suggester.FALLBACK, Suggester.MARGINAL,
                    Suggester.MARGINAL_FALLBACK}) {
                List<Suggester.Candidate> l = new ArrayList<>();
                for (Suggester.Candidate x : alt) {
                    if (fit.equals(x.fit)) {
                        l.add(x);
                    }
                }
                l.sort(Suggester.comparator(fit));
                for (Suggester.Candidate x : l) {
                    List<Check> ck = checks(in, c, g, x);
                    if (allOk(ck) && pick == chosen) {
                        pick = x;
                        pickChecks = ck;
                    }
                }
            }
        }
        Decision d = new Decision();
        d.group = g.group;
        d.typeKey = g.typeKey;
        d.steps = pick.steps;
        d.perPad = pick.n;
        for (double[] p : pick.local.get(g.idx[0])) {
            d.positions.add(new double[] {Suggester.r3(p[0]), Suggester.r3(p[1])});
        }
        d.mgPerDot = Suggester.r3(pick.view.mgPerDot);
        d.massPerPadMg = Suggester.r3(pick.mass);
        d.targetMg = Suggester.r3(g.target);
        d.errorPct = Texts.wholePct(pick.err);
        d.fit = pick.fit == null ? Suggester.BREAKS_LIMITS : pick.fit;
        d.checks = pickChecks;
        d.padRipplePct = DoseModel.padRipplePct(pick.steps * pick.n);
        DoseEvidence pe = in.check.evidence.get(pick.steps);
        if (pe != null && pe.phaseStat != null && DoseModel.isPartial(pick.steps)) {
            // a from the dose's phase statistic (an upper 95 % bound, area ∝ mass at or below the half
            // turn), carried to the pad's whole turn n·s
            double a = pe.phaseStat / DoseModel.rippleFactor(pick.steps);
            d.measuredRipplePct = Math.round(100 * a * DoseModel.rippleFactor(pick.steps * pick.n) * 10) / 10.0;
        }
        if (allOk(pickChecks)) {
            d.outcome = CHARACTERIZED;
            if (pick != chosen) {
                d.replaced = true;
                d.replacedText = (in.edited ? "your " : "the suggested ") + planWords(chosen) + " "
                        + failureWords(chosenChecks, chosen) + ": calibrated " + planWords(pick) + " instead";
            }
        }
        else {
            d.outcome = PROVISIONAL;
            for (Check k : pickChecks) {
                if (!k.ok) {
                    d.why = k.text;
                    break;
                }
            }
            if (!Suggester.PASS.equals(d.fit) && !Suggester.MARGINAL.equals(d.fit)) {
                d.notes.add("the closest amount that fits is " + Texts.signedPct(pick.err) + " %");
                if (pick.steps == in.check.profileSteps) {
                    d.notes.add("you can raise it by hand after a test board");
                }
            }
        }
        return d;
    }

    private static boolean allOk(List<Check> l) {
        for (Check k : l) {
            if (!k.ok) {
                return false;
            }
        }
        return true;
    }

    /** "2 half-turn dots", "1 one-turn dot". */
    private static String planWords(Suggester.Candidate x) {
        return x.n + " " + DoseModel.turnName(x.steps) + " " + (x.n == 1 ? "dot" : "dots");
    }

    /** The chosen plan's first failure, as a predicate after "your 2 half-turn dots". */
    private static String failureWords(List<Check> checks, Suggester.Candidate x) {
        boolean one = x.n == 1;
        for (Check k : checks) {
            if (k.ok) {
                continue;
            }
            Neighbours.Eval e = x.eval;
            switch (k.key) {
                case "geometry":
                    if (e.overhang > Limits.OVERHANG_MM + Limits.EPS) {
                        return (one ? "reaches " : "reach ") + Texts.mm(e.overhang) + " mm past the pad";
                    }
                    if (e.dotGap < Limits.DOT_GAP_MM - Limits.EPS) {
                        return (one ? "leaves " : "leave ") + Texts.mm(e.dotGap) + " mm to the next pad's dot";
                    }
                    return (one ? "comes" : "come") + " within " + Texts.mm(e.copperGap) + " mm of the next pad";
                case "amount":
                    return (one ? "gives " : "give ") + Texts.pct(x.err) + " % " + (x.err < 0 ? "less" : "more")
                            + " than the target";
                case "r6":
                    return (one ? "gives" : "give") + " a pad less than half a screw turn";
                default:
                    return "did not pass: " + k.text;
            }
        }
        return "passed";
    }

    /** The checks for characterized (§7.6 step 2), each with predicted / measured / limit. */
    static List<Check> checks(Inputs in, ProfileCheck.Ctx c, ProfileCheck.Group g, Suggester.Candidate x) {
        List<Check> l = new ArrayList<>();
        LadderStats.Settings s = in.settings != null ? in.settings : new LadderStats.Settings();
        String turn = DoseModel.turnName(x.steps);
        DoseEvidence e = in.check.evidence.get(x.steps);
        boolean optical = e != null && e.opticalFresh && e.opticalPass;
        String opticalText = e == null || !e.opticalFresh ? "no camera check of " + turn + " dots on the blank yet"
                : e.failures.isEmpty() ? turn + " dots failed the camera checks" : e.failures.get(0);
        l.add(new Check("optical", optical, null, e == null ? null : e.n + " dots", "transfer, threads, repeat, phase, "
                + "drift, landing", opticalText));
        boolean confirmed = e != null && e.massConfirmed;
        String massText = e == null || e.massReadings == 0
                ? "the " + DoseModel.turnNoun(x.steps) + " was not weighed in this job: Calibrate again weighs it"
                : "the amount was weighed once; a second weighing (another coupon or another job) confirms it";
        l.add(new Check("mass", confirmed, Texts.mg(DoseModel.massMg(x.steps)) + " mg per dot",
                e != null && e.mgPerDot != null ? Texts.mg(e.mgPerDot) + " mg per dot" : null,
                "two readings within " + Texts.pct(s.confirmTolerance) + " %", massText));
        boolean amount = x.err >= -s.massTolLow - Limits.EPS && x.err <= s.massTolHigh + Limits.EPS;
        l.add(new Check("amount", amount, Texts.mg(g.target) + " mg per pad", Texts.mg(x.mass) + " mg per pad",
                "-" + Texts.pct(s.massTolLow) + " % to +" + Texts.pct(s.massTolHigh) + " %", x.n + " " + turn + " "
                        + (x.n == 1 ? "dot gives " : "dots give ") + Texts.mg(x.mass) + " mg per pad, "
                        + Texts.pct(x.err) + " % " + (x.err < 0 ? "less" : "more") + " than the " + Texts.mg(g.target)
                        + " mg target"));
        boolean r6 = x.steps * x.n * 2 >= in.check.stepsPerRev;
        l.add(new Check("r6", r6, null, x.steps * x.n + " steps per pad", "at least half a screw turn per pad",
                "each pad gets " + x.steps * x.n + " steps, less than half a screw turn"));
        Neighbours.Eval ev = x.eval;
        DoseEvidence d = e != null && e.hasMeasuredDots() ? e : null;
        String geometryText;
        if (ev.dotGap < Limits.DOT_GAP_MM - Limits.EPS) {
            geometryText = "the dots, up to " + Texts.mm(x.view.dGap) + " mm wide, leave " + Texts.mm(ev.dotGap)
                    + " mm to the next pad's dot (" + Texts.mm(Limits.DOT_GAP_MM) + " needed)";
        }
        else if (ev.overhang > Limits.OVERHANG_MM + Limits.EPS) {
            geometryText = "the dots, " + Texts.mm(x.view.dOverhang) + " mm wide on average, reach "
                    + Texts.mm(ev.overhang) + " mm past the " + Texts.mm(ev.overhangAcrossMm) + " mm pad ("
                    + Texts.mm(Limits.OVERHANG_MM) + " allowed)";
        }
        else {
            geometryText = "the dots, up to " + Texts.mm(x.view.dGap) + " mm wide, come within "
                    + Texts.mm(ev.copperGap) + " mm of the next pad (" + Texts.mm(Limits.COPPER_GAP_MM) + " needed)";
        }
        l.add(new Check("geometry", d != null && ev.passes(), "overhang, dot gap, copper gap with the model's dots",
                "overhang " + Texts.mm(ev.overhang) + ", dot gap " + Texts.mm(ev.dotGap) + ", copper gap "
                        + Texts.mm(ev.copperGap) + " mm" + (d == null ? " (model dots)" : ""),
                "overhang " + Texts.mm(Limits.OVERHANG_MM) + ", gaps " + Texts.mm(Limits.DOT_GAP_MM) + " / "
                        + Texts.mm(Limits.COPPER_GAP_MM) + " mm",
                d == null ? "no measured " + turn + " dot size to check the pads with" : geometryText));
        l.add(new Check("control", in.controlPass, null, null, null,
                in.controlText != null ? in.controlText : "the control dots were not checked or did not match the bench"));
        boolean tip = in.recordsTipKey == null || in.recordsTipKey.equals(c.tip().key);
        l.add(new Check("tip", tip, null, in.recordsTipKey, c.tip().key, "the dots were measured with another tip ("
                + in.recordsTipKey + ")"));
        l.add(new Check("regulator", in.regulatorAttested, null, null, null,
                "the regulator reading was not confirmed for this calibration"));
        boolean validated = g.type != null && TypeInfo.rank(g.type.status) == 3;
        l.add(new Check("validated", !validated || in.validatedNamed, null, null, null, (g.type == null ? "" : g.type.name)
                + " is validated on a board: calibrating it again needs your confirmation before the start"));
        return l;
    }
}
