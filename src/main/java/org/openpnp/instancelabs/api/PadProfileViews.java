package org.openpnp.instancelabs.api;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;

import org.openpnp.instancelabs.dispense.DispensePlan;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DoseBlocks;
import org.openpnp.instancelabs.dispense.PadInstances;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.PadTypeStatus;
import org.openpnp.instancelabs.dispense.TipSetup;
import org.openpnp.instancelabs.dispense.TipSetups;
import org.openpnp.instancelabs.padprofile.Anchor;
import org.openpnp.instancelabs.padprofile.CheckInputs;
import org.openpnp.instancelabs.padprofile.DoseModel;
import org.openpnp.instancelabs.padprofile.PadInstance;
import org.openpnp.instancelabs.padprofile.ProfileCheck;
import org.openpnp.instancelabs.padprofile.Suggester;
import org.openpnp.instancelabs.padprofile.TypeInfo;
import org.pmw.tinylog.Logger;

/**
 * The profile check as JSON (PROFILE_CALIBRATION_DESIGN.md §3.5) and what a plan view adds for it (§12.5): the
 * check, per pad its group, dose, predicted and anchored mass, dot size and clearance, per dot its dose, block and
 * pad, and the plan's dose blocks. The check of a plan is memoised per plan entry while what it depends on holds.
 */
final class PadProfileViews {
    /** The check of a plan entry and what it was computed from (§3.5: keyed by the entry instance). */
    private static final class Memo {
        final String tuple;
        final ProfileCheck.Result result;

        Memo(String tuple, ProfileCheck.Result result) {
            this.tuple = tuple;
            this.result = result;
        }
    }

    private static final Map<PlanStore.Entry, Memo> MEMO = Collections.synchronizedMap(new WeakHashMap<>());

    private PadProfileViews() {
    }

    /** The check's inputs of a plan now (the plan's pads, the current types, gate, tip, recipe and evidence). */
    static CheckInputs inputs(DispenseService svc, DispensePlan plan) throws SQLException {
        return PadInstances.inputs(svc, PadInstances.fromPlan(plan), true);
    }

    /** The check of a plan entry, computed again only when its plan, pad types, gate, tip, recipe or evidence changed. */
    static ProfileCheck.Result check(DispenseService svc, PlanStore.Entry e) throws SQLException {
        return check(e, inputs(svc, e.plan));
    }

    static ProfileCheck.Result check(PlanStore.Entry e, CheckInputs in) {
        String tuple = e.plan.planId + "|" + Json.toJson(in.types) + "|" + in.gate + "|" + Json.toJson(in.tip) + "|"
                + in.profileSteps + "|" + in.doseFingerprint + "|" + in.pastePartialPackages + "|"
                + Json.toJson(in.evidence);
        Memo m = MEMO.get(e);
        if (m == null || !m.tuple.equals(tuple)) {
            m = new Memo(tuple, ProfileCheck.of(in));
            MEMO.put(e, m);
        }
        return m.result;
    }

    /** The plan view's additions (§12.5); a check that fails leaves the plan view as it was, with the reason. */
    @SuppressWarnings("unchecked")
    static void addTo(Map<String, Object> out, PlanStore.Entry e, DispenseService svc) {
        DispensePlan p = e.plan;
        ProfileCheck.Result r = null;
        CheckInputs in = null;
        if (svc != null) {
            try {
                in = inputs(svc, p);
                r = check(e, in);
            }
            catch (SQLException | RuntimeException x) {
                Logger.warn(x, "profile check of plan {} failed", p.planId);
                out.put("profileCheckError", MachineEndpoints.rootMessage(x));
            }
        }
        Map<String, Object> view = r == null ? null : check(r, in.pastePartialPackages, in.types);
        if (view != null) {
            view.put("otherTips", otherTips(svc, in));
        }
        out.put("profileCheck", view);
        List<Map<String, Object>> pads = (List<Map<String, Object>>) out.get("pads");
        for (int i = 0; pads != null && i < pads.size() && i < p.pads.size(); i++) {
            DispensePlan.PlannedPad x = p.pads.get(i);
            Map<String, Object> d = pads.get(i);
            ProfileCheck.PadInfo info = r == null ? null : r.pads.get(x.padId);
            d.put("profileGroup", info == null ? null : info.group);
            d.put("dotSteps", x.dotSteps);
            d.put("dotsPerPad", x.dotsPerPad);
            d.put("startPhase", x.startPhase);
            boolean dose = x.dotSteps != null && in != null;
            // §22.2: null where the mounted tip does not know the dose (never NaN in the JSON)
            Double perDot = dose ? Suggester.mgPerDot(in.evidence, x.dotSteps, in.tip) : null;
            d.put("predictedMassMg", perDot != null && x.dotCount > 0 ? r3(x.dotCount * perDot) : null);
            d.put("padRipplePct", dose && x.dotsPerPad > 0 ? rounded(DoseModel.padRipplePct(x.dotSteps * x.dotsPerPad))
                    : null);
            d.put("anchorMassMg", info == null ? null : info.anchorMassMg);
            d.put("dotDiameterMm", dose ? Suggester.dotDiameterMm(in.evidence, x.dotSteps, in.tip) : null);
            d.put("clearanceMm", info == null ? null : info.clearanceMm);
        }
        List<Map<String, Object>> dots = (List<Map<String, Object>>) out.get("dots");
        // the dose the stream gives each dot: its own, else its pad's (a plan copied without the dot fields)
        int[] doses = in == null ? null : DoseBlocks.dosesOf(p, in.profileSteps);
        for (int i = 0; dots != null && i < dots.size() && i < p.dots.size(); i++) {
            DispensePlan.PlannedDot x = p.dots.get(i);
            Map<String, Object> d = dots.get(i);
            int steps = x.netSteps > 0 || doses == null ? x.netSteps : doses[i];
            d.put("netSteps", steps);
            d.put("block", x.block);
            d.put("padIndex", x.padIndex);
            double[] size = steps > 0 && in != null ? Suggester.dotDiameterMm(in.evidence, steps, in.tip) : null;
            d.put("diameterMm", size == null ? null : size[1]);
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (DispensePlan.Block b : p.blocks) {
            blocks.add(Views.map("index", b.index, "netSteps", b.netSteps, "turn", b.turn, "dots", b.dots, "pads",
                    b.pads, "firstDot", b.firstDot, "lastDot", b.lastDot, "phaseDot", b.phaseDot, "flushDots",
                    b.flushDots, "demandMgS", b.demandMgS == null ? null : r3(b.demandMgS)));
        }
        out.put("blocks", blocks);
    }

    /**
     * §3.5: the check as JSON, with the packages whose dispensable pads are pasted although others are not, and per
     * group its pad type as the mounted setup sees it (§22.2: a stand-in, the setup, the other setups' statuses, the
     * setup a plan was copied from). The callers that read the database add {@code otherTips}.
     */
    static Map<String, Object> check(ProfileCheck.Result r, java.util.Collection<String> pastePartial,
            Map<String, TypeInfo> types) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok);
        m.put("fixable", r.fixable);
        m.put("tipBlocking", r.tipBlocking);
        m.put("notes", r.notes);
        m.put("computedAtMs", r.computedAtMs);
        // §22.6: the setup's version and dispenser, where its dot sizes come from; minDotMm null when not known
        m.put("tip", Views.map("key", r.tip.key, "name", r.tip.name, "odMm", r.tip.odMm, "minDotSteps",
                r.tip.minDotSteps, "minDotMm", r.tip.minDotMm, "setupVersion", r.tip.setupVersion, "dispenser",
                r.tip.dispenser, "dots", r.tip.dots));
        List<Map<String, Object>> anchors = new ArrayList<>();
        for (Anchor a : r.anchors) {
            anchors.add(Views.map("family", a.family, "typeKey", a.typeKey, "bench", a.bench, "massPerPadMg",
                    a.massPerPadMg, "areaMm2", a.areaMm2, "mgPerMm2", a.mgPerMm2, "text", a.text, "setupName",
                    a.setupName));
        }
        m.put("anchors", anchors);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (ProfileCheck.Group g : r.groups) {
            Map<String, Object> x = group(g);
            TypeInfo t = g.typeKey == null || types == null ? null : types.get(g.typeKey);
            x.put("type", t == null ? null : Views.map("standIn", t.standIn, "setupName", t.setupName, "others",
                    t.others, "copiedFrom", t.copiedFrom));
            groups.add(x);
        }
        m.put("groups", groups);
        m.put("pastePartialPackages", pastePartial == null ? new ArrayList<>() : new ArrayList<>(pastePartial));
        return m;
    }

    /**
     * §22.6 {@code otherTips}: the setups not retired, other than the mounted one, whose own versions of every pad type
     * the pads use are at or above the gate ({key, name, covers, of}): Needs a profile names them ("The 22G bent taper
     * has all 5 pad types of this board"). A failed read leaves the list empty.
     */
    static List<Map<String, Object>> otherTips(DispenseService svc, CheckInputs in) {
        List<Map<String, Object>> l = new ArrayList<>();
        Set<String> used = new TreeSet<>();
        for (PadInstance p : in.pads) {
            if (p.typeKey != null) {
                used.add(p.typeKey);
            }
        }
        if (svc == null || used.isEmpty()) {
            return l;
        }
        try {
            PadTypeStatus gate = svc.getConfig().minPadTypeStatus();
            for (TipSetup s : svc.getDb().currentTipSetups(false)) {
                if (s.key.equals(in.tip.key)) {
                    continue;
                }
                Map<String, PadType> own = new HashMap<>();
                for (PadType t : TipSetups.typesFor(svc.getDb(), s.key)) {
                    own.put(t.typeKey, t);
                }
                int covers = 0;
                for (String k : used) {
                    PadType t = own.get(k);
                    covers += t != null && !t.standIn && t.status != null && t.status.atLeast(gate)
                            && !PadTypeEndpoints.RETIRED.equals(t.family) ? 1 : 0;
                }
                if (covers == used.size()) {
                    l.add(Views.map("key", s.key, "name", s.name, "covers", covers, "of", used.size()));
                }
            }
        }
        catch (SQLException | RuntimeException e) {
            Logger.warn(e, "the other tips of a profile check were not read");
        }
        return l;
    }

    /** One group of §3.5 ("short" is the group's shortText). */
    static Map<String, Object> group(ProfileCheck.Group g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("group", g.group);
        m.put("label", g.label);
        m.put("packageIds", g.packageIds);
        m.put("padNames", g.padNames);
        m.put("allPadsOfPackage", g.allPadsOfPackage);
        m.put("refs", g.refs);
        m.put("parts", g.parts);
        m.put("pads", g.pads);
        m.put("padIds", g.padIds);
        m.put("aperture", g.aperture);
        m.put("family", g.family);
        m.put("minPitchMm", g.minPitchMm);
        m.put("minEdgeGapMm", g.minEdgeGapMm);
        m.put("hopMm", g.hopMm);
        m.put("typeKey", g.typeKey);
        m.put("typeVersion", g.typeVersion);
        m.put("typeStatus", g.typeStatus);
        m.put("typeRule", g.typeRule);
        m.put("reason", g.reason);
        m.put("tipKind", g.tipKind);
        m.put("outsideKind", g.outsideKind);
        m.put("bridgeTo", g.bridgeTo);
        m.put("nearOf", g.nearOf);
        m.put("partial", g.partial);
        m.put("blocking", g.blocking);
        m.put("fixable", g.fixable);
        m.put("short", g.shortText);
        m.put("reasonText", g.reasonText);
        m.put("suggestion", g.suggestion);
        return m;
    }

    /** The profile calibration fields of a pad type version (§5.1), for {@link Views#padType}. */
    static void padTypeFields(Map<String, Object> m, PadType t) {
        m.put("dotSteps", t.dotSteps);
        m.put("dotsPerPad", t.dotsPerPad);
        m.put("turn", t.dotSteps == null ? null : DoseModel.turnNoun(t.dotSteps));
        m.put("tipKey", t.tipKey);
        m.put("doseFp", t.doseFp);
        m.put("calAreaMm2", t.calAreaMm2);
        m.put("massPerPadMg", t.massPerPadMg);
        m.put("origin", t.origin);
    }

    private static double r3(double x) {
        return Math.round(x * 1000) / 1000.0;
    }

    private static double[] rounded(double[] pct) {
        return new double[] {Math.round(pct[0]), Math.round(pct[1])};
    }
}
