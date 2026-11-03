package org.openpnp.instancelabs.padprofile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Does a saved profile cover every pad (§3)? Pure: pads are grouped by package and paste opening,
 * and each group gets one reason, first match wins:
 *
 * <ol>
 * <li>{@code tip}: not dispensable with the tip (dot gap below 0.20 mm, a dot on another pad's copper,
 * or no plan of §4.3 fits);</li>
 * <li>{@code part}: another group of the package is {@code tip}, so the part is treated whole and
 * none of its pads are pasted (main-thread decision 4), unless the package is in
 * {@link CheckInputs#pastePartialPackages}: then the group keeps its own reason with the partial
 * sentence;</li>
 * <li>{@code no-pad-size}, {@code no-type}, {@code below-gate} (the planner's flags; a type calibrated
 * for another tip is below the gate, decision 5);</li>
 * <li>{@code bridge}: the type's dots come within 0.20 mm of a neighbour's dot (decision 11: a flag
 * through the gate, {@link #FLAG_MAY_BRIDGE});</li>
 * <li>{@code outside}: what its type was calibrated for does not cover these pads (a note).</li>
 * </ol>
 *
 * <p>Decision 14: when the tip test or no plan fits every pad of a group, the pads whose own neighbours
 * fail it are split off as a sub-group of their own, reason {@code tip} of kind {@link #TIP_NEAR}, and
 * the rest keeps its plan. Only the other pads of those parts become {@code part} (decision 4), not the
 * package's.</p>
 *
 * <p>The check never adds or removes a flag: in a plan {@code blocking} is "some pad of the group is
 * blocked by its flags"; before planning it is what the planner would flag.</p>
 */
public final class ProfileCheck {
    public static final String TIP = "tip";
    public static final String PART = "part";
    public static final String NO_PAD_SIZE = "no-pad-size";
    public static final String NO_TYPE = "no-type";
    public static final String BELOW_GATE = "below-gate";
    public static final String BRIDGE = "bridge";
    public static final String OUTSIDE = "outside";

    public static final String TIP_PITCH = "pitch";
    public static final String TIP_COPPER = "copper";
    public static final String TIP_NO_PLAN = "no-plan";
    /** Decision 14: some pads of a group are too close to a neighbour for the plan the others keep. */
    public static final String TIP_NEAR = "near";
    /** Decision 14: why a part held whole by a {@link #TIP_NEAR} pad gets no paste ({@code otherPads}). */
    static final String NEAR_PART = "too close to a neighbour";

    public static final String OUTSIDE_INTERLOCK = "interlock";
    public static final String OUTSIDE_RECIPE = "recipe";
    public static final String OUTSIDE_AREA = "area";
    public static final String OUTSIDE_GEOMETRY = "geometry";

    /** The planner's flags (D/PadFlag names) the check reads. */
    public static final String FLAG_NO_GEOMETRY = "NO_GEOMETRY";
    public static final String FLAG_NO_PAD_TYPE = "NO_PAD_TYPE";
    public static final String FLAG_STATUS_BELOW_MIN = "STATUS_BELOW_MIN";
    /** Decision 11: the flag the planner sets on pads whose dots may bridge (dot gap below 0.20 mm). */
    public static final String FLAG_MAY_BRIDGE = "MAY_BRIDGE";

    private ProfileCheck() {
    }

    public static final class Result {
        /** No group blocks. */
        public boolean ok;
        /** Blocking groups Profile can fix (no-pad-size, no-type, below-gate, bridge). */
        public int fixable;
        /** Blocking groups of the tip (tip, and parts treated whole). */
        public int tipBlocking;
        /** {@code outside} groups. */
        public int notes;
        public long computedAtMs;
        public TipView tip;
        public List<Anchor> anchors = new ArrayList<>();
        public List<Group> groups = new ArrayList<>();
        /** Every pad's numbers (listed groups or not), by pad id: what the plan view shows per pad (§12.5). */
        public Map<String, PadInfo> pads = new LinkedHashMap<>();

        /** The group with this id, or null. */
        public Group group(String id) {
            for (Group g : groups) {
                if (g.group.equals(id)) {
                    return g;
                }
            }
            return null;
        }
    }

    /** A pad's group, the amount its opening should get and its clearance under its group's plan. */
    public static final class PadInfo {
        public String group;
        /** κ·A·k of the opening (capped at fine pitch for families without an anchor), mg. */
        public double anchorMassMg;
        /** Smallest dot gap or copper gap of the pad's dots under its group's plan (mm); null without a plan. */
        public Double clearanceMm;
    }

    public static final class TipView {
        public String key;
        public String name;
        public double odMm;
        public int minDotSteps;
        public double[] minDotMm;
    }

    public static final class Aperture {
        public double wMm;
        public double hMm;
        public double roundness;
        public double areaMm2;
    }

    /** One group (§3.5). The JSON's "short" is {@link #shortText} ("short" is a Java keyword). */
    public static final class Group {
        public String group;
        public String label;
        public List<String> packageIds = new ArrayList<>();
        public List<String> padNames = new ArrayList<>();
        public boolean allPadsOfPackage;
        public List<String> refs = new ArrayList<>();
        public int parts;
        public int pads;
        public List<String> padIds = new ArrayList<>();
        public Aperture aperture;
        public String family;
        public Double minPitchMm;
        public Double minEdgeGapMm;
        public Double hopMm;
        public String typeKey;
        public Integer typeVersion;
        public String typeStatus;
        public String typeRule;
        public String reason;
        public String tipKind;
        public String outsideKind;
        /** Decision 11: the neighbour pad the dots may bridge to ("U3 pad 2"). */
        public String bridgeTo;
        /** Decision 14: the pads of the group this sub-group ({@link #TIP_NEAR}) was split from; null otherwise. */
        public Integer nearOf;
        public boolean partial;
        public boolean blocking;
        public boolean fixable;
        public String shortText;
        public String reasonText;
        public Suggestion suggestion;

        // working state, not part of the result
        transient int[] idx = new int[0];
        transient List<PadInstance> members = new ArrayList<>();
        transient GroupKey key;
        /** What the id hashes beyond the key (a group split by type or a near sub-group), null for none. */
        transient String split;
        transient Set<String> allNames;
        transient TypeInfo type;
        transient boolean usableType;
        transient boolean centre;
        transient double area;
        transient double k = 1;
        transient double target;
        transient double uncappedTarget;
        transient boolean capped;
        transient Anchor anchor;
        transient boolean ownAnchor;
        transient double pitch = Double.NaN;
        transient Neighbours.Eval tipEval;
        transient Suggester.Candidate plan;
        transient Suggester.Candidate typePlan;
        transient boolean dotsFromSuggestion;
        /** The package's undispensable pads in a few words ("0.80 mm apart", "0.61 mm wide"). */
        transient String otherPads;
    }

    /** Everything the suggester and the decider share. */
    static final class Ctx {
        final CheckInputs in;
        final Neighbours nb;
        final Map<PadInstance, Integer> index = new IdentityHashMap<>();
        final List<Group> groups = new ArrayList<>();
        final Map<String, List<Group>> byPackage = new LinkedHashMap<>();
        final Map<String, List<Integer>> byRef = new HashMap<>();
        final Map<Integer, Suggester.DoseView> views = new HashMap<>();
        final Map<String, List<Anchor>> anchors = new HashMap<>();
        final Set<String> newKeys = new LinkedHashSet<>();
        /** The openings that fail the tip test (§4.1) on their own. */
        final Set<Integer> tipFails = new HashSet<>();
        final double dTip;
        final String typesFingerprint;

        Ctx(CheckInputs in) {
            this.in = in;
            List<PadInstance> geo = new ArrayList<>();
            for (PadInstance p : in.pads) {
                if (p.hasGeometry()) {
                    index.put(p, geo.size());
                    geo.add(p);
                    byRef.computeIfAbsent(p.ref, k -> new ArrayList<>()).add(geo.size() - 1);
                }
            }
            nb = new Neighbours(geo);
            for (int s : DoseModel.ALLOWED_STEPS) {
                views.put(s, Suggester.view(in.evidence, s));
            }
            views.computeIfAbsent(in.profileSteps, s -> Suggester.view(in.evidence, s));
            Suggester.DoseView tipView = views.computeIfAbsent(tip().minDotSteps, s -> Suggester.view(in.evidence, s));
            dTip = tipView.dGap;
            typesFingerprint = fingerprint(in.types);
            collectAnchors();
        }

        Tip tip() {
            return in.tip != null ? in.tip : Tip.g22();
        }

        Suggester.DoseView view(int steps) {
            return views.computeIfAbsent(steps, s -> Suggester.view(in.evidence, s));
        }

        /** Validated k = 1 types with a weighed mass per pad (§1.3), in key order (ties in anchorFor). */
        private void collectAnchors() {
            for (TypeInfo t : new TreeMap<>(in.types).values()) {
                if (!t.retired && TypeInfo.rank(t.status) == 3 && t.massPerPadMg != null && t.massPerPadMg > 0
                        && t.family != null && Math.abs(t.k - 1) <= Limits.EPS) {
                    anchors.computeIfAbsent(t.family, f -> new ArrayList<>())
                            .add(new Anchor(t.family, t.key, t.version, t.massPerPadMg, t.calArea()));
                }
            }
            for (Map.Entry<String, Anchor> e : new TreeMap<>(in.anchors).entrySet()) {
                anchors.computeIfAbsent(e.getKey(), f -> new ArrayList<>()).add(e.getValue());
            }
            anchors.computeIfAbsent(Family.CHIP, f -> new ArrayList<>());
            if (anchors.get(Family.CHIP).isEmpty()) {
                anchors.get(Family.CHIP).add(Anchor.BENCH);
            }
        }

        /** The family's anchor nearest in log area, or null. */
        Anchor anchorFor(String family, double area) {
            Anchor best = null;
            for (Anchor a : anchors.getOrDefault(family, Collections.emptyList())) {
                if (best == null || Math.abs(Math.log(area / a.areaMm2)) < Math.abs(Math.log(area / best.areaMm2))) {
                    best = a;
                }
            }
            return best;
        }

        boolean usable(TypeInfo t) {
            return t != null && !t.retired && TypeInfo.rank(t.status) >= TypeInfo.rank(in.gate) && !otherTip(t);
        }

        boolean otherTip(TypeInfo t) {
            return t != null && t.tipKey != null && !t.tipKey.equals(tip().key);
        }
    }

    /** The check of a plan, a board file or an upload preview. */
    public static Result of(CheckInputs in) {
        Ctx c = prepare(in);
        Result r = new Result();
        r.computedAtMs = in.nowMs;
        Tip tip = c.tip();
        r.tip = new TipView();
        r.tip.key = tip.key;
        r.tip.name = tip.name;
        r.tip.odMm = tip.odMm;
        r.tip.minDotSteps = tip.minDotSteps;
        r.tip.minDotMm = new double[] {Suggester.r3(DoseModel.dLoMm(tip.minDotSteps)),
                Suggester.r3(DoseModel.dHiMm(tip.minDotSteps))};
        Map<String, Anchor> used = new LinkedHashMap<>();
        for (Group g : c.groups) {
            if (g.reason == null) {
                continue;
            }
            g.suggestion = Suggester.suggest(c, g);
            texts(c, g);
            r.groups.add(g);
            if (g.anchor != null && g.suggestion != null && !Suggestion.NONE.equals(g.suggestion.kind)) {
                used.putIfAbsent(g.anchor.bench ? "bench" : g.anchor.typeKey, g.anchor);
            }
            if (g.blocking && g.fixable) {
                r.fixable++;
            }
            else if (g.blocking && (TIP.equals(g.reason) || PART.equals(g.reason))) {
                r.tipBlocking++;
            }
            if (OUTSIDE.equals(g.reason)) {
                r.notes++;
            }
        }
        for (Anchor a : used.values()) {
            Anchor v = a.copy();
            if (v.text == null) {
                v.text = Suggester.anchorWords(c, a);
            }
            v.mgPerMm2 = Suggester.r3(v.mgPerMm2);
            v.areaMm2 = Suggester.r3(v.areaMm2);
            r.anchors.add(v);
        }
        r.ok = true;
        for (Group g : r.groups) {
            r.ok &= !g.blocking;
        }
        for (Group g : c.groups) {
            padInfo(c, g, r.pads);
        }
        return r;
    }

    /** §12.5: each pad of a group, listed or not, with its group's amount and its clearance under the group's plan. */
    private static void padInfo(Ctx c, Group g, Map<String, PadInfo> out) {
        Suggester.Candidate x = g.typePlan != null ? g.typePlan : g.plan;
        for (PadInstance p : g.members) {
            PadInfo i = new PadInfo();
            i.group = g.group;
            i.anchorMassMg = Suggester.r3(g.target);
            Integer at = c.index.get(p);
            if (x != null && at != null && x.local.containsKey(at)) {
                Neighbours.Eval e = c.nb.evaluate(new int[] {at}, Collections.singletonMap(at, x.local.get(at)),
                        x.view.dGap / 2, x.view.dOverhang / 2);
                double gap = Math.min(e.dotGap, e.copperGap);
                i.clearanceMm = Double.isInfinite(gap) ? null : Suggester.r3(gap);
            }
            out.put(p.padId, i);
        }
    }

    /** Groups, the tip test, the neighbours' dots, the plan search (two passes) and the reasons. */
    static Ctx prepare(CheckInputs in) {
        Ctx c = new Ctx(in);
        buildGroups(c);
        Tip tip = c.tip();
        // §4.1 tip test: a centre dot of the tip's smallest dose on every opening of the board
        for (int i = 0; i < c.nb.size(); i++) {
            c.nb.setCentreDot(i, c.dTip / 2);
        }
        for (Group g : c.groups) {
            if (g.idx.length == 0) {
                continue;
            }
            g.tipEval = tipEval(c, g.idx);
            g.tipKind = tipKind(g.tipEval);
            if (g.tipKind == null) {
                continue;
            }
            for (int i : g.idx) {
                if (tipKind(tipEval(c, new int[] {i})) != null) {
                    c.tipFails.add(i); // decision 14 splits these off when the group's other openings pass
                }
            }
        }
        // §3.1 the neighbours' own dots: planned, else the usable type's plan, else the suggestion
        for (Group g : c.groups) {
            if (g.idx.length == 0) {
                continue;
            }
            boolean planned = in.planned;
            for (PadInstance p : g.members) {
                planned &= p.dots != null && !p.dots.isEmpty();
            }
            if (planned) {
                for (int i : g.idx) {
                    PadInstance p = c.nb.pads.get(i);
                    int s = p.dotSteps != null ? p.dotSteps : in.profileSteps;
                    c.nb.setDots(i, p.dots, c.view(s).dGap / 2);
                }
            }
            else if (g.usableType) {
                g.typePlan = Suggester.typePlan(c, g, g.type);
                for (int i : g.idx) {
                    c.nb.setDots(i, c.nb.toBoard(i, g.typePlan.local.get(i)), g.typePlan.view.dGap / 2);
                }
            }
            else {
                g.dotsFromSuggestion = true;
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (Group g : c.groups) {
                if (g.idx.length > 0 && g.tipKind == null) {
                    g.plan = Suggester.search(c, g);
                }
            }
            for (Group g : c.groups) {
                if (g.dotsFromSuggestion && g.plan != null) {
                    for (int i : g.idx) {
                        c.nb.setDots(i, c.nb.toBoard(i, g.plan.local.get(i)), g.plan.view.dGap / 2);
                    }
                }
            }
        }
        for (Group g : new ArrayList<>(c.groups)) {
            splitNear(c, g);
        }
        holdNearParts(c);
        for (Group g : c.groups) {
            if (g.idx.length > 0 && g.tipKind == null && g.plan == null) {
                g.tipKind = TIP_NO_PLAN;
            }
        }
        for (List<Group> pkg : c.byPackage.values()) {
            // the package's undispensable pads in a few words: the finest pitch, else the narrowest pad
            double fine = Double.NaN;
            double narrow = Double.NaN;
            for (Group g : pkg) {
                if (TIP_NEAR.equals(g.tipKind)) {
                    continue; // decision 14: these pads' own neighbours, not the package
                }
                if (TIP_NO_PLAN.equals(g.tipKind) && !(narrow <= g.key.shortMm)) {
                    narrow = g.key.shortMm;
                }
                else if (g.tipKind != null && !TIP_NO_PLAN.equals(g.tipKind) && !(fine <= g.pitch)) {
                    fine = g.pitch;
                }
            }
            String words = !Double.isNaN(fine) ? Texts.mm(fine) + " mm apart"
                    : !Double.isNaN(narrow) ? Texts.mm(narrow) + " mm wide" : null;
            for (Group g : pkg) {
                if (g.tipKind == null && words != null) {
                    g.partial = true;
                    g.otherPads = words;
                }
            }
        }
        for (Group g : c.groups) {
            reason(c, g, tip);
        }
        return c;
    }

    /** §4.1 on some openings: their centre dots of the tip's smallest dose against every opening's. */
    private static Neighbours.Eval tipEval(Ctx c, int[] idx) {
        Map<Integer, List<double[]>> centre = new HashMap<>();
        for (int i : idx) {
            centre.put(i, Collections.singletonList(new double[] {0, 0}));
        }
        return c.nb.evaluate(idx, centre, c.dTip / 2, c.dTip / 2);
    }

    private static String tipKind(Neighbours.Eval e) {
        return e.dotGap < Limits.DOT_GAP_MARGINAL_MM - Limits.EPS ? TIP_PITCH : e.copperGap < 0 ? TIP_COPPER : null;
    }

    /**
     * Decision 14: when the tip test (§4.1) or no plan fits every pad of a group, the pads whose own
     * neighbours fail the tip test or leave no plan of their own become a sub-group ({@link #TIP_NEAR})
     * if the rest then has a plan; a group with a plan for every pad keeps its reason (a pad type for
     * this size avoids the bridge, decision 11), and a group whose every pad fails the tip test keeps
     * it (the package's own pitch). Decision 4 then holds the parts of those pads whole: their other
     * pads become a sub-group of reason {@code part}, unless the package is pasted partly.
     */
    private static void splitNear(Ctx c, Group g) {
        if (g.plan != null || g.idx.length < 2 || g.idx.length != g.members.size()) {
            return;
        }
        List<PadInstance> near = new ArrayList<>();
        for (int i : g.idx) {
            Group one = newGroup(c, g.key, Collections.singletonList(c.nb.pads.get(i)), g.family, g.allNames, g.split);
            one.target = g.target;
            if (c.tipFails.contains(i) || Suggester.search(c, one) == null) {
                near.add(c.nb.pads.get(i));
            }
        }
        if (near.isEmpty() || near.size() == g.members.size()) {
            return;
        }
        List<PadInstance> keep = new ArrayList<>(g.members);
        keep.removeAll(near);
        Group rest = rebuild(c, g, keep, g.split);
        rest.plan = Suggester.search(c, rest);
        if (rest.plan == null) {
            return;
        }
        Suggester.Candidate dots = rest.dotsFromSuggestion ? rest.plan : null;
        if (rest.usableType && !c.in.planned) {
            rest.typePlan = Suggester.typePlan(c, rest, rest.type);
            dots = rest.typePlan;
        }
        for (int at : rest.idx) {
            PadInstance p = c.nb.pads.get(at);
            if (c.in.planned && p.dots != null && !p.dots.isEmpty()) {
                c.nb.setDots(at, p.dots, c.view(p.dotSteps != null ? p.dotSteps : c.in.profileSteps).dGap / 2);
            }
            else if (dots != null) {
                c.nb.setDots(at, c.nb.toBoard(at, dots.local.get(at)), dots.view.dGap / 2);
            }
        }
        Group n = rebuild(c, g, near, sub(g.split, TIP_NEAR));
        n.tipKind = TIP_NEAR;
        n.nearOf = g.members.size();
        replace(c, g, Arrays.asList(rest, n));
    }

    /**
     * Decision 4 for the near sub-groups: the other pads of their parts (in any group of the package)
     * become a sub-group of reason {@code part}, unless the package is pasted partly.
     */
    private static void holdNearParts(Ctx c) {
        for (List<Group> pkg : new ArrayList<>(c.byPackage.values())) {
            Set<String> refs = new HashSet<>();
            for (Group g : pkg) {
                if (TIP_NEAR.equals(g.tipKind)) {
                    refs.addAll(g.refs);
                }
            }
            if (refs.isEmpty() || c.in.pastePartialPackages != null
                    && c.in.pastePartialPackages.contains(pkg.get(0).packageIds.get(0))) {
                continue;
            }
            for (Group g : new ArrayList<>(pkg)) {
                if (g.tipKind != null) {
                    continue;
                }
                List<PadInstance> whole = new ArrayList<>();
                List<PadInstance> keep = new ArrayList<>();
                for (PadInstance p : g.members) {
                    (refs.contains(p.ref) ? whole : keep).add(p);
                }
                if (whole.isEmpty()) {
                    continue;
                }
                Group w = keep.isEmpty() ? g : rebuild(c, g, whole, sub(g.split, PART));
                w.partial = true;
                w.otherPads = NEAR_PART;
                if (!keep.isEmpty()) {
                    replace(c, g, Arrays.asList(rebuild(c, g, keep, g.split), w));
                }
            }
        }
    }

    private static String sub(String split, String what) {
        return split == null ? what : split + "|" + what;
    }

    /** A group of some of g's pads, with g's plans and working state (plans are by opening, so a subset keeps them). */
    private static Group rebuild(Ctx c, Group g, List<PadInstance> members, String split) {
        Group r = newGroup(c, g.key, members, g.family, g.allNames, split);
        r.tipEval = g.tipEval;
        r.plan = g.plan;
        r.typePlan = g.typePlan;
        r.dotsFromSuggestion = g.dotsFromSuggestion;
        return r;
    }

    /** Replaces a group by its sub-groups, in place, in the group list and its package's list. */
    private static void replace(Ctx c, Group g, List<Group> by) {
        for (List<Group> l : Arrays.asList(c.groups, c.byPackage.get(Objects.toString(g.packageIds.get(0), "")))) {
            int at = l.indexOf(g);
            l.remove(at);
            l.addAll(at, by);
        }
    }

    private static void buildGroups(Ctx c) {
        Map<String, List<PadInstance>> byPkg = new TreeMap<>(GroupKey.NATURAL);
        for (PadInstance p : c.in.pads) {
            byPkg.computeIfAbsent(p.packageId == null ? "" : p.packageId, k -> new ArrayList<>()).add(p);
        }
        for (Map.Entry<String, List<PadInstance>> e : byPkg.entrySet()) {
            List<PadInstance> pads = new ArrayList<>(e.getValue());
            pads.sort((a, b) -> {
                int x = GroupKey.compareNatural(a.padName, b.padName);
                return x != 0 ? x : GroupKey.compareNatural(a.ref, b.ref);
            });
            List<GroupKey> keys = new ArrayList<>();
            List<List<PadInstance>> clusters = new ArrayList<>();
            for (PadInstance p : pads) {
                GroupKey k = GroupKey.of(p);
                int at = -1;
                for (int i = 0; i < keys.size() && at < 0; i++) {
                    if (keys.get(i).joins(k)) {
                        at = i;
                    }
                }
                if (at < 0) {
                    keys.add(k);
                    clusters.add(new ArrayList<>());
                    at = keys.size() - 1;
                }
                clusters.get(at).add(p);
            }
            Set<String> allNames = new LinkedHashSet<>();
            String firstRef = null;
            int perPart = 0;
            for (PadInstance p : pads) {
                allNames.add(p.padName);
                if (firstRef == null || GroupKey.compareNatural(p.ref, firstRef) < 0) {
                    firstRef = p.ref;
                }
            }
            for (PadInstance p : pads) {
                perPart += Objects.equals(p.ref, firstRef) ? 1 : 0;
            }
            String family = Family.of(e.getKey(), perPart, clusters.size());
            List<Group> pkgGroups = new ArrayList<>();
            for (int ci = 0; ci < clusters.size(); ci++) {
                Map<String, List<PadInstance>> byType = new LinkedHashMap<>();
                for (PadInstance p : clusters.get(ci)) {
                    byType.computeIfAbsent(p.typeKey == null ? "" : p.typeKey, k -> new ArrayList<>()).add(p);
                }
                for (Map.Entry<String, List<PadInstance>> te : byType.entrySet()) {
                    Group g = newGroup(c, keys.get(ci), te.getValue(), family, allNames,
                            byType.size() > 1 ? (te.getKey().isEmpty() ? "none" : te.getKey()) : null);
                    pkgGroups.add(g);
                    c.groups.add(g);
                }
            }
            c.byPackage.put(e.getKey(), pkgGroups);
        }
    }

    private static Group newGroup(Ctx c, GroupKey key, List<PadInstance> members, String family, Set<String> allNames,
            String split) {
        Group g = new Group();
        g.key = key;
        g.split = split;
        g.allNames = allNames;
        g.group = key.id(split);
        g.members = members;
        PadInstance first = members.get(0);
        g.packageIds.add(first.packageId);
        g.label = Texts.label(first.packageId, key.longMm, key.shortMm);
        Set<String> names = new TreeSet<>(GroupKey.NATURAL);
        Set<String> refs = new TreeSet<>(GroupKey.NATURAL);
        List<Integer> idx = new ArrayList<>();
        for (PadInstance p : members) {
            names.add(p.padName);
            refs.add(p.ref);
            g.padIds.add(p.padId);
            Integer i = c.index.get(p);
            if (i != null) {
                idx.add(i);
            }
            g.centre |= p.centrePad;
        }
        g.padNames.addAll(names);
        g.refs.addAll(refs);
        g.parts = refs.size();
        g.pads = members.size();
        g.allPadsOfPackage = names.containsAll(allNames);
        g.idx = idx.stream().mapToInt(Integer::intValue).toArray();
        g.family = family;
        g.typeKey = first.typeKey;
        g.typeVersion = first.typeVersion;
        g.typeStatus = first.typeStatus;
        g.typeRule = first.typeRule;
        if (first.typeKey != null) {
            g.type = c.in.types.get(first.typeKey);
            if (g.type == null) {
                g.type = new TypeInfo();
                g.type.key = first.typeKey;
                g.type.name = first.typeKey;
                g.type.version = first.typeVersion != null ? first.typeVersion : 0;
                g.type.status = first.typeStatus;
            }
            if (g.typeVersion == null) {
                g.typeVersion = g.type.version;
            }
            if (g.typeStatus == null) {
                g.typeStatus = g.type.status;
            }
        }
        g.usableType = c.usable(g.type) && !(g.typeStatus != null && TypeInfo.rank(g.typeStatus) < TypeInfo.rank(c.in.gate));
        g.aperture = new Aperture();
        g.aperture.wMm = first.wMm;
        g.aperture.hMm = first.hMm;
        g.aperture.roundness = first.roundness;
        g.area = first.areaMm2();
        g.aperture.areaMm2 = Suggester.r3(g.area);
        if (g.idx.length > 0) {
            double pitch = Double.NaN;
            double edge = Double.NaN;
            for (int i : g.idx) {
                double p = c.nb.pitch(i);
                double e = c.nb.edgeGap(i);
                pitch = Double.isNaN(pitch) || p < pitch ? p : pitch;
                edge = Double.isNaN(edge) || e < edge ? e : edge;
            }
            g.pitch = pitch;
            g.minPitchMm = Double.isNaN(pitch) ? null : Suggester.r3(pitch);
            g.minEdgeGapMm = Double.isNaN(edge) ? null : Suggester.r3(edge);
        }
        // §1.3 amount: the family's anchor, k, the fine-pitch cap for families with no anchor of their own
        g.k = g.centre || g.area > Limits.LARGE_PAD_MM2 + Limits.EPS ? Limits.K_PLACEHOLDER : 1.0;
        Anchor own = c.anchorFor(family, g.area);
        g.ownAnchor = own != null;
        g.anchor = own != null ? own : c.anchorFor(Family.CHIP, g.area);
        g.uncappedTarget = g.anchor.mgPerMm2 * g.area * g.k;
        g.target = g.uncappedTarget;
        double cap = Limits.PASTE_DENSITY_MG_MM3 * g.area * Limits.CAP_PRINT_MM;
        if (!g.ownAnchor && g.pitch <= Limits.CAP_PITCH_MM + Limits.EPS && cap < g.target) {
            g.target = cap;
            g.capped = true;
        }
        return g;
    }

    private static boolean has(Group g, String flag) {
        for (PadInstance p : g.members) {
            if (p.flags != null && p.flags.contains(flag)) {
                return true;
            }
        }
        return false;
    }

    private static void reason(Ctx c, Group g, Tip tip) {
        boolean geometry = g.idx.length == g.members.size() && g.idx.length > 0;
        boolean noType = g.type == null || has(g, FLAG_NO_PAD_TYPE);
        boolean belowGate = !noType && (has(g, FLAG_STATUS_BELOW_MIN) || TypeInfo.rank(g.typeStatus) < TypeInfo.rank(c.in.gate)
                || g.type.retired || c.otherTip(g.type));
        boolean optIn = c.in.pastePartialPackages != null && c.in.pastePartialPackages.contains(g.packageIds.get(0));
        if (g.tipKind != null) {
            g.reason = TIP;
        }
        else if (g.partial && !optIn) {
            g.reason = PART;
        }
        else if (!geometry || has(g, FLAG_NO_GEOMETRY)) {
            g.reason = NO_PAD_SIZE;
        }
        else if (noType) {
            g.reason = NO_TYPE;
        }
        else if (belowGate) {
            g.reason = BELOW_GATE;
        }
        else {
            covered(c, g, tip);
        }
        boolean flagged = noType || belowGate || !geometry || has(g, FLAG_NO_GEOMETRY);
        if (c.in.planned) {
            for (PadInstance p : g.members) {
                g.blocking |= p.blocked;
            }
        }
        else if (g.reason != null) {
            g.blocking = PART.equals(g.reason) || BRIDGE.equals(g.reason) || TIP_NEAR.equals(g.tipKind)
                    || flagged && !OUTSIDE.equals(g.reason);
        }
        g.fixable = g.blocking && (NO_PAD_SIZE.equals(g.reason) || NO_TYPE.equals(g.reason)
                || BELOW_GATE.equals(g.reason) || BRIDGE.equals(g.reason));
    }

    /** Reasons 4 and 5 for a group whose type passes the gate: bridge, then outside. */
    private static void covered(Ctx c, Group g, Tip tip) {
        TypeInfo t = g.type;
        Suggester.Candidate tp = c.in.planned ? Suggester.plannedPlan(c, g) : null;
        if (tp == null) {
            tp = g.typePlan != null ? g.typePlan : Suggester.typePlan(c, g, t);
        }
        g.typePlan = tp;
        Neighbours.Eval e = c.nb.evaluate(g.idx, tp.local, tp.view.dGap / 2, tp.view.dOverhang / 2);
        tp.eval = e;
        if (has(g, FLAG_MAY_BRIDGE) || e.dotGap < Limits.DOT_GAP_MARGINAL_MM - Limits.EPS) {
            g.reason = BRIDGE;
            g.bridgeTo = e.dotGapPad >= 0 ? Texts.padWords(c.nb.pads.get(e.dotGapPad)) : "the next pad";
            return;
        }
        int padSteps = tp.steps * tp.n;
        double ratio = g.area / t.calArea();
        if (t.dotSteps != null && !DoseModel.isAllowed(t.dotSteps, tip)
                || padSteps < c.in.stepsPerRev / 2) {
            g.reason = OUTSIDE;
            g.outsideKind = OUTSIDE_INTERLOCK;
        }
        else if (t.doseFp != null && c.in.doseFingerprint != null && !t.doseFp.equals(c.in.doseFingerprint)) {
            g.reason = OUTSIDE;
            g.outsideKind = OUTSIDE_RECIPE;
        }
        else if (ratio < Limits.COVER_RATIO_LOW - Limits.EPS || ratio > Limits.COVER_RATIO_HIGH + Limits.EPS) {
            g.reason = OUTSIDE;
            g.outsideKind = OUTSIDE_AREA;
        }
        else if (e.overhang > Limits.OVERHANG_MARGINAL_MM + Limits.EPS
                || e.copperGap < Limits.COPPER_GAP_MARGINAL_MM - Limits.EPS) {
            g.reason = OUTSIDE;
            g.outsideKind = OUTSIDE_GEOMETRY;
        }
    }

    /** reasonText, shortText and hopMm of a listed group. */
    private static void texts(Ctx c, Group g) {
        Tip tip = c.tip();
        TypeInfo t = g.type;
        String typeName = t != null && t.name != null ? t.name : g.typeKey;
        Neighbours.Eval te = g.tipEval;
        switch (g.reason) {
            case TIP:
                g.shortText = "cannot be dispensed with the " + tip.name;
                if (TIP_NEAR.equals(g.tipKind)) {
                    nearTexts(c, g);
                }
                else if (TIP_PITCH.equals(g.tipKind)) {
                    g.reasonText = Texts.tipPitch(tip.name, g.pitch, c.dTip, tip.minDotSteps, te.dotGap);
                }
                else if (TIP_COPPER.equals(g.tipKind)) {
                    g.reasonText = Texts.tipCopper(tip.name, c.dTip, -te.copperGap);
                }
                else if (Suggester.closest(c, g) == null) {
                    g.reasonText = Texts.tipNoDose(tip.name, tip.minDotSteps);
                }
                else {
                    Suggester.Candidate x = Suggester.closest(c, g);
                    g.reasonText = Texts.tipNoPlan(tip.name, g.aperture.wMm, g.aperture.hMm, x.n, x.steps,
                            x.eval.overhang > Limits.OVERHANG_MARGINAL_MM + Limits.EPS ? x.eval.overhang : null,
                            x.eval.dotGap < Limits.DOT_GAP_MARGINAL_MM - Limits.EPS ? x.eval.dotGap : null,
                            x.eval.copperGap < Limits.COPPER_GAP_MARGINAL_MM - Limits.EPS ? x.eval.copperGap : null,
                            x.mass, g.target);
                }
                break;
            case PART:
                g.shortText = "not pasted: other pads of the part cannot be dispensed";
                g.reasonText = NEAR_PART.equals(g.otherPads) ? Texts.nearPart(g.packageIds.get(0))
                        : Texts.part(g.packageIds.get(0), tip.name, g.otherPads);
                break;
            case NO_PAD_SIZE:
                g.shortText = "no pad size";
                g.reasonText = Texts.noPadSize(g.packageIds.get(0), typeName);
                break;
            case NO_TYPE:
                g.shortText = "no pad type";
                g.reasonText = Texts.noType(g.packageIds.get(0), g.aperture.wMm, g.aperture.hMm, g.pads, g.parts);
                break;
            case BELOW_GATE:
                if (c.otherTip(t)) {
                    g.shortText = "calibrated for another tip";
                    g.reasonText = Texts.belowGateTip(typeName, t.tipKey, tip.name);
                }
                else {
                    g.shortText = "not calibrated yet";
                    g.reasonText = Texts.belowGate(typeName, g.typeStatus, c.in.gate);
                }
                break;
            case BRIDGE:
                g.shortText = Texts.bridgeShort(g.bridgeTo);
                g.reasonText = Texts.bridge(typeName, g.bridgeTo, g.typePlan.eval.dotGap);
                break;
            default:
                outsideTexts(c, g, typeName);
                break;
        }
        if (g.partial && !PART.equals(g.reason) && !TIP.equals(g.reason)) {
            g.reasonText += Texts.partialSentence(g.packageIds.get(0), g.otherPads);
        }
        g.hopMm = hop(c, g);
    }

    /** Decision 14: the sub-group's gap under the plan its group keeps (the type's), else under the closest plan. */
    private static void nearTexts(Ctx c, Group g) {
        Suggester.Candidate x = g.usableType ? Suggester.typePlan(c, g, g.type) : Suggester.closest(c, g);
        Neighbours.Eval e = x == null ? null : c.nb.evaluate(g.idx, x.local, x.view.dGap / 2, x.view.dOverhang / 2);
        boolean byDot = e == null || e.dotGap < Limits.DOT_GAP_MARGINAL_MM - Limits.EPS
                || e.copperGap >= Limits.COPPER_GAP_MARGINAL_MM - Limits.EPS;
        int at = e == null ? -1 : byDot ? e.dotGapPad : e.copperPad;
        g.bridgeTo = at >= 0 ? Texts.padWords(c.nb.pads.get(at)) : "the next pad";
        g.shortText = "too close to " + g.bridgeTo;
        g.reasonText = Texts.near(g.pads, g.nearOf == null ? g.pads : g.nearOf, g.bridgeTo, e == null ? null
                : byDot ? e.dotGap : e.copperGap, byDot);
    }

    private static void outsideTexts(Ctx c, Group g, String typeName) {
        TypeInfo t = g.type;
        Suggester.Candidate tp = g.typePlan;
        switch (g.outsideKind) {
            case OUTSIDE_INTERLOCK:
                g.shortText = "refused by the run checks";
                g.reasonText = t.dotSteps != null && !DoseModel.isAllowed(t.dotSteps, c.tip())
                        ? Texts.interlockDose(t.key, t.version, t.dotSteps, c.tip().name, c.tip().minDotSteps)
                        : Texts.interlockR6(t.key, t.version, tp.steps * tp.n);
                break;
            case OUTSIDE_RECIPE:
                g.shortText = "calibrated with another recipe";
                String diff = Texts.recipeDiff(t.doseFp, c.in.doseFingerprint);
                g.reasonText = Texts.outsideRecipe(typeName, diff.isEmpty() ? "another fingerprint" : diff);
                break;
            case OUTSIDE_AREA:
                double ratio = g.area / t.calArea() - 1;
                g.shortText = Texts.pct(ratio) + " % " + (ratio >= 0 ? "larger" : "smaller") + " than calibrated";
                g.reasonText = Texts.outsideArea(typeName, t.padWMm, t.padHMm, t.calArea(), g.aperture.wMm,
                        g.aperture.hMm, g.area, tp.mass, g.target);
                break;
            default:
                if (tp.eval.overhang > Limits.OVERHANG_MARGINAL_MM + Limits.EPS) {
                    g.shortText = "dots reach past the pad";
                    g.reasonText = Texts.outsideOverhang(typeName, tp.eval.overhang);
                }
                else {
                    g.shortText = "dots close to the next pad";
                    g.reasonText = Texts.outsideCopper(typeName, tp.eval.copperGap);
                }
                break;
        }
    }

    /**
     * The shortest centre distance between dots of two different pads of the same part in the
     * group's plan or suggestion (the move a thread forms on, §1.5); a group with one pad per part
     * measures to the part's other pads.
     */
    private static Double hop(Ctx c, Group g) {
        double best = Double.NaN;
        boolean inGroupOnly = g.pads > g.parts;
        Set<Integer> mine = new HashSet<>();
        for (int i : g.idx) {
            mine.add(i);
        }
        for (int i : g.idx) {
            PadInstance p = c.nb.pads.get(i);
            for (int j : c.byRef.getOrDefault(p.ref, Collections.emptyList())) {
                if (j == i || inGroupOnly && !mine.contains(j) || Neighbours.overlapping(p, c.nb.pads.get(j))) {
                    continue;
                }
                for (double[] a : c.nb.dots(i)) {
                    for (double[] b : c.nb.dots(j)) {
                        double d = Math.hypot(a[0] - b[0], a[1] - b[1]);
                        best = Double.isNaN(best) || d < best ? d : best;
                    }
                }
            }
        }
        return Double.isNaN(best) ? null : Suggester.r3(best);
    }

    /** Identity of the pad types the check used (part of every suggestion id). */
    static String fingerprint(Map<String, TypeInfo> types) {
        StringBuilder b = new StringBuilder();
        for (TypeInfo t : new TreeMap<>(types).values()) {
            b.append(t.key).append(':').append(t.version).append(':').append(t.status).append(':').append(t.dotSteps)
                    .append(':').append(t.dotsPerPad).append(':').append(t.calAreaMm2).append(':').append(t.tipKey)
                    .append(':').append(t.doseFp).append(':').append(t.retired).append(';');
        }
        return GroupKey.sha12(b.toString());
    }
}
