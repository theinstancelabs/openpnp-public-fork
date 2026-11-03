package org.openpnp.instancelabs.dispense;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.openpnp.instancelabs.padprofile.Anchor;
import org.openpnp.instancelabs.padprofile.CheckInputs;
import org.openpnp.instancelabs.padprofile.DoseEvidence;
import org.openpnp.instancelabs.padprofile.DotGeometry;
import org.openpnp.instancelabs.padprofile.Limits;
import org.openpnp.instancelabs.padprofile.PadInstance;
import org.openpnp.instancelabs.padprofile.Tip;
import org.openpnp.instancelabs.padprofile.TypeInfo;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/**
 * The engine's pads as the profile check sees them (profile calibration design §3.1, §12.5): one
 * {@link PadInstance} per paste opening of a plan (with its flags and planned dots) or of a board
 * before planning (no flags, no dots), the {@link CheckInputs} around them (pad types, the gate, the
 * tip, the profile's dose), and the dot-gap test the planner flags with (decisions 11 and 14).
 */
public final class PadInstances {
    /** The family a retired pad type is moved to (A/PadTypeEndpoints "Delete"). */
    static final String RETIRED_FAMILY = "Retired";

    private PadInstances() {
    }

    /** Every pad of a plan (machine mm) with its flags, its type and its planned dots. */
    public static List<PadInstance> fromPlan(DispensePlan plan) {
        Map<String, List<double[]>> dots = new HashMap<>();
        for (DispensePlan.PlannedDot d : plan.dots) {
            dots.computeIfAbsent(d.padId, k -> new ArrayList<>()).add(new double[] {d.xMm, d.yMm});
        }
        List<PadInstance> l = new ArrayList<>();
        for (DispensePlan.PlannedPad p : plan.pads) {
            PadInstance i = instance(p.padId, p.placementId, p.packageId, p.padName, p.xMm, p.yMm, p.rotationDeg,
                    p.wMm, p.hMm, p.roundness, p.typeRule);
            if (p.typeKey != null) {
                i.typeKey = p.typeKey;
                i.typeVersion = p.typeVersion;
                i.typeStatus = p.typeStatus == null ? null : p.typeStatus.dbName;
            }
            i.flags.addAll(p.flagNames());
            i.blocked = p.isBlocked();
            List<double[]> mine = dots.get(p.padId);
            if (mine != null && !i.blocked) {
                i.dots = mine;
                i.dotSteps = p.dotSteps;
            }
            l.add(i);
        }
        return l;
    }

    /** Pads before planning (a board file, the planner's own check): no flags, no dots. */
    public static List<PadInstance> fromGeometry(List<PadGeometry> pads) {
        List<PadInstance> l = new ArrayList<>();
        for (PadGeometry g : pads) {
            PadInstance i = instance(g.padId, g.placementId, g.packageId, g.padName, g.xMm, g.yMm, g.rotationDeg,
                    g.wMm, g.hMm, g.roundness, g.typeRule);
            if (g.padType != null) {
                i.typeKey = g.padType.typeKey;
                i.typeVersion = g.padType.version;
                i.typeStatus = g.padType.status == null ? null : g.padType.status.dbName;
            }
            l.add(i);
        }
        return l;
    }

    /** The pads of a board location as {@link BoardPadExtractor} finds them (OpenPnP's transform). */
    public static List<PadInstance> fromBoard(BoardLocation bl, PadTypeResolver resolver, boolean includeDisabled) {
        return fromGeometry(new BoardPadExtractor(resolver, includeDisabled).extract(bl).pads);
    }

    /** A board file's pads in board mm: the board placed at the origin (as the SIM camera draws it). */
    public static List<PadInstance> fromBoard(Board board, PadTypeResolver resolver, boolean includeDisabled) {
        BoardLocation bl = new BoardLocation(board);
        bl.setLocation(new Location(LengthUnit.Millimeters, 0, 0, 0, 0));
        return fromBoard(bl, resolver, includeDisabled);
    }

    private static PadInstance instance(String padId, String ref, String packageId, String padName, double x,
            double y, double rotationDeg, double w, double h, double roundness, String typeRule) {
        PadInstance i = new PadInstance();
        i.padId = padId;
        i.ref = ref;
        i.packageId = packageId;
        i.padName = padName;
        i.x = x;
        i.y = y;
        i.rotationDeg = rotationDeg;
        i.wMm = w;
        i.hMm = h;
        i.roundness = roundness;
        i.typeRule = typeRule == null ? PadTypeResolver.Resolution.NONE : typeRule;
        i.centrePad = PadTypeResolver.Resolution.CENTRE_PAD.equals(typeRule);
        return i;
    }

    /** A pad type version (and its layout rule) as the check sees it. */
    public static TypeInfo typeInfo(PadType t, PadRule rule) {
        TypeInfo i = new TypeInfo();
        i.key = t.typeKey;
        i.name = t.name;
        i.family = t.family;
        i.version = t.version;
        i.status = t.status == null ? null : t.status.dbName;
        i.padWMm = t.padWMm;
        i.padHMm = t.padHMm;
        i.padRoundness = t.padRoundness;
        i.k = t.k;
        i.dotSteps = t.dotSteps;
        i.dotsPerPad = t.dotsPerPad;
        i.tipKey = Tip.setupOf(t.tipKey); // §22.1: a version without a tip key is the seeded setup's
        i.doseFp = t.doseFp;
        i.calAreaMm2 = t.calAreaMm2;
        i.massPerPadMg = t.massPerPadMg;
        i.origin = t.origin;
        i.retired = RETIRED_FAMILY.equals(t.family);
        i.standIn = t.standIn;
        if (PadType.ORIGIN_COPIED.equals(t.origin) && t.notes != null) {
            java.util.regex.Matcher m = COPIED_FROM.matcher(t.notes);
            i.copiedFrom = m.find() ? m.group(1) : null;
        }
        if (rule != null) {
            i.largestSingleDotPadMm2 = rule.largestSingleDotPadMm2;
            i.maxDots = rule.maxDots;
        }
        return i;
    }

    /** The notes of a copied version (TipSetups.copyOf): "Copied from the {source} (v{n}, ...". */
    private static final java.util.regex.Pattern COPIED_FROM = java.util.regex.Pattern.compile(
            "^Copied from the (.+?) \\(v\\d+");

    /**
     * The tip as the configuration names it, without its dot table: the bench model for the seeded setup
     * ({@link TipSpec#model}), nothing measured otherwise. The mounted setup with its dot table is
     * {@link DispenseService#mountedTip()}.
     */
    static Tip specTip(TipSpec t) {
        Tip x = new Tip(t.key, t.name, t.idMm, t.odMm, t.minDotSteps);
        x.setupVersion = t.setupVersion;
        x.dispenser = t.dispenser;
        x.benchModel = TipSetup.MODEL_BENCH.equals(t.model);
        return x;
    }

    /**
     * The check's inputs around these pads: the current pad types with their rules, the gate, the mounted tip, the
     * profile's dose and recipe, the packages pasted partly. Dose evidence and anchors other than the validated types'
     * are the caller's (none here). The tip is the mounted setup with its dot table ({@link DispenseService#mountedTip},
     * tip setups design §22.2), not {@code cfg.tip}.
     */
    public static CheckInputs inputs(List<PadInstance> pads, Collection<PadType> types, Map<String, PadRule> rules,
            DispenseConfig cfg, Collection<String> pastePartial, boolean planned, Tip tip) {
        if (tip == null) {
            throw new IllegalArgumentException("the profile check needs the mounted tip");
        }
        CheckInputs in = new CheckInputs();
        in.pads = new ArrayList<>(pads);
        for (PadType t : types) {
            in.types.put(t.typeKey, typeInfo(t, rules == null ? null : rules.get(t.typeKey)));
        }
        try {
            in.gate = cfg.minPadTypeStatus().dbName;
        }
        catch (IllegalArgumentException e) {
            in.gate = cfg.minPadTypeStatus; // an unknown gate is a CONFIG problem; the check reads it as unknown
        }
        in.tip = tip;
        for (TypeInfo t : in.types.values()) {
            t.setupName = tip.name;
        }
        in.profileSteps = cfg.profile.netStepsPerDot;
        in.stepsPerRev = cfg.bStepsPerRev;
        in.bStepsPerDeg = cfg.bStepsPerDeg;
        in.doseFingerprint = cfg.profile.doseFingerprint();
        in.planned = planned;
        if (pastePartial != null) {
            in.pastePartialPackages.addAll(pastePartial);
        }
        in.nowMs = System.currentTimeMillis();
        return in;
    }

    /**
     * The check's inputs from the engine as it is now (§3.1): the mounted tip setup with its dot table
     * ({@link DispenseService#mountedTip}), its composed pad types and their rules with the other setups' statuses,
     * the configuration, the packages pasted partly, the dose evidence of the fresh records (jobs flagged wrong-tip
     * left out) and the other setups' validated, weighed versions as anchors (§22.2).
     */
    public static CheckInputs inputs(DispenseService svc, List<PadInstance> pads, boolean planned)
            throws SQLException {
        DispenseConfig cfg = svc.getConfig();
        DispenseDb db = svc.getDb();
        Tip tip = svc.mountedTip();
        List<PadType> types = svc.getPadTypes();
        CheckInputs in = inputs(pads, types, db.currentPadRules(), cfg, db.pastePartialPackages(), planned, tip);
        long since = in.nowMs - (long) (cfg.padCalibration.doseRecordMaxAgeH * 3600e3); // older ones are stale
        in.evidence = DoseRecords.evidence(db.doseRecords(null, since), cfg, new DoseRecord.Epochs(
                svc.getControllerConnectedAtMs(), svc.isSimulatorConfirmed()), in.nowMs, db.flaggedJobs());
        others(db, tip.key, types, in);
        return in;
    }

    /**
     * The other setups' versions of the types (§22.2): their statuses on {@link TypeInfo#others} (the below-gate
     * text) and their validated, weighed versions as anchors ({@link CheckInputs#moreAnchors}).
     */
    static void others(DispenseDb db, String setupKey, List<PadType> types, CheckInputs in) throws SQLException {
        Map<String, String> names = new HashMap<>();
        for (TipSetup s : db.currentTipSetups(true)) {
            names.put(s.key, s.name);
        }
        Map<String, PadType> composed = new HashMap<>();
        for (PadType t : types) {
            composed.put(t.typeKey, t);
        }
        for (PadType o : db.otherSetupVersions(setupKey)) {
            String setup = Tip.setupOf(o.tipKey);
            String name = names.getOrDefault(setup, setup);
            TypeInfo mine = in.types.get(o.typeKey);
            if (mine != null) {
                TypeInfo.Other x = new TypeInfo.Other();
                x.setupKey = setup;
                x.setupName = name;
                x.version = o.version;
                x.status = o.status == null ? null : o.status.dbName;
                mine.others.add(x);
            }
            PadType c = composed.get(o.typeKey);
            if (o.status == PadTypeStatus.VALIDATED && o.massPerPadMg != null && o.massPerPadMg > 0
                    && Math.abs(o.k - 1) <= Limits.EPS && c != null && !RETIRED_FAMILY.equals(c.family)) {
                TypeInfo t = typeInfo(o, null);
                Anchor a = new Anchor(c.family, o.typeKey, o.version, o.massPerPadMg, t.calArea());
                a.setupName = name;
                in.moreAnchors.add(a);
            }
        }
    }

    /**
     * Decisions 11 and 14: the pads whose dots come within {@link Limits#DOT_GAP_MARGINAL_MM} of a dot
     * of another pad, each pad on its own (dot edges at {@code radiusMm} of their pad; openings of one
     * part that touch or overlap are one deposit, not neighbours). Result: pad index -> the nearest
     * such pad, in pad order.
     */
    public static Map<Integer, Integer> bridging(List<PadInstance> pads, double[] radiusMm) {
        double rMax = 0;
        for (double r : radiusMm) {
            rMax = Math.max(rMax, r);
        }
        // two dots that may bridge are less than 2 radii + the gap apart: within the 3 x 3 cells around
        double cell = Math.max(Limits.NEIGHBOUR_CELL_MM, 2 * rMax + Limits.DOT_GAP_MARGINAL_MM);
        Map<Long, List<int[]>> grid = new HashMap<>();
        for (int i = 0; i < pads.size(); i++) {
            List<double[]> dots = pads.get(i).dots;
            for (int k = 0; dots != null && k < dots.size(); k++) {
                grid.computeIfAbsent(cellKey(dots.get(k)[0], dots.get(k)[1], cell), c -> new ArrayList<>())
                        .add(new int[] {i, k});
            }
        }
        Map<Integer, double[]> worst = new TreeMap<>();
        for (int i = 0; i < pads.size(); i++) {
            PadInstance p = pads.get(i);
            for (int k = 0; p.dots != null && k < p.dots.size(); k++) {
                double[] a = p.dots.get(k);
                long cx = (long) Math.floor(a[0] / cell);
                long cy = (long) Math.floor(a[1] / cell);
                for (long gx = cx - 1; gx <= cx + 1; gx++) {
                    for (long gy = cy - 1; gy <= cy + 1; gy++) {
                        for (int[] o : grid.getOrDefault(key(gx, gy), Collections.emptyList())) {
                            int j = o[0];
                            if (j <= i || oneDeposit(p, pads.get(j))) {
                                continue;
                            }
                            double[] b = pads.get(j).dots.get(o[1]);
                            double gap = Math.hypot(a[0] - b[0], a[1] - b[1]) - radiusMm[i] - radiusMm[j];
                            if (gap < Limits.DOT_GAP_MARGINAL_MM - Limits.EPS) {
                                note(worst, i, j, gap);
                                note(worst, j, i, gap);
                            }
                        }
                    }
                }
            }
        }
        Map<Integer, Integer> l = new LinkedHashMap<>();
        for (Map.Entry<Integer, double[]> e : worst.entrySet()) {
            l.put(e.getKey(), (int) e.getValue()[0]);
        }
        return l;
    }

    private static void note(Map<Integer, double[]> worst, int i, int j, double gap) {
        double[] w = worst.get(i);
        if (w == null || gap < w[1]) {
            worst.put(i, new double[] {j, gap});
        }
    }

    private static long cellKey(double x, double y, double cell) {
        return key((long) Math.floor(x / cell), (long) Math.floor(y / cell));
    }

    private static long key(long gx, long gy) {
        return (gx << 32) ^ (gy & 0xffffffffL);
    }

    /** Openings of one part whose rectangles touch or overlap (a paste window stacked on its pad). */
    static boolean oneDeposit(PadInstance a, PadInstance b) {
        return a.ref != null && a.ref.equals(b.ref) && a.hasGeometry() && b.hasGeometry()
                && DotGeometry.padGapMm(a, b) <= 1e-9;
    }
}
