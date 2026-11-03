package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.IntFunction;

import org.openpnp.instancelabs.padprofile.DoseModel;
import org.openpnp.instancelabs.padprofile.DotGeometry;
import org.openpnp.instancelabs.padprofile.GroupKey;
import org.openpnp.instancelabs.padprofile.Limits;
import org.openpnp.instancelabs.padprofile.PadInstance;
import org.openpnp.instancelabs.padprofile.ProfileCheck;
import org.openpnp.instancelabs.padprofile.Texts;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Placement;
import org.openpnp.util.Utils2D;

/**
 * Board -> pads -> dots -> blocks -> order. A dot is one dot of the active dispense profile (its
 * dwell and gap) with the screw turn of its pad type's own dose, else the profile's (profile
 * calibration design §6.1): one dot per pad, a grid where the pad type's rule says the pad is too
 * large for one dot, or the type's own dots per pad. There is no volumetric dose model: the dose
 * follows the turn, pressure and dwell (bench, 2026-10-28). Each plan carries a copy of the profile.
 *
 * <p>The screw-phase rules (§1.2): dots of one dose form a block (the profile's dose first, then
 * descending); inside a block the pads are ordered nearest neighbour + 2-opt from where the block
 * starts, and each pad's dots follow one another (R5). Pads whose dots would bridge to another
 * pad's (decisions 11, 14), pads of a part that has an undispensable pad (decision 4) and pads of a
 * type calibrated with another tip (decision 5) are flagged and get no dots.</p>
 */
public class DispensePlanner {
    private final DispenseConfig cfg;
    private final PadTypeResolver resolver;
    private java.util.Map<String, PadRule> rules = java.util.Collections.emptyMap();
    private SurfacePlane surface;
    /** Purge-row spots from the next unused one (ordinal 0) where block switches lay their dots, or null. */
    private IntFunction<double[]> purgeSpot = k -> null;
    /** Dot diameters (mm) by net steps for the dot-gap flag; the model's upper edge where none is given. */
    private Map<Integer, Double> dotDiameters = Collections.emptyMap();

    public DispensePlanner(DispenseConfig cfg, PadTypeResolver resolver) {
        this.cfg = cfg;
        this.resolver = resolver;
    }

    /** Per pad-type layout rules (pad_rules): pads larger than largestSingleDotPadMm2 get a grid. */
    public DispensePlanner withRules(java.util.Map<String, PadRule> rules) {
        this.rules = rules == null ? java.util.Collections.emptyMap() : rules;
        return this;
    }

    /**
     * The probed board surface (I4): every dot takes its Z from this plane. Without one the dots
     * use the board's single Z, the plan says so, and a run is refused by I4.
     */
    public DispensePlanner withSurface(SurfacePlane surface) {
        this.surface = surface;
        return this;
    }

    /**
     * The purge row's spots by ordinal from the next unused one: a block after the first starts
     * where its switch's last flush dot lies (ordinal null: the previous block's last dot).
     */
    public DispensePlanner withPurgeSpots(IntFunction<double[]> spotByOrdinal) {
        this.purgeSpot = spotByOrdinal == null ? k -> null : spotByOrdinal;
        return this;
    }

    /** Measured dot diameters (mm, by net steps) for the dot-gap flag instead of the model's upper edge. */
    public DispensePlanner withDotDiameters(Map<Integer, Double> byNetSteps) {
        this.dotDiameters = byNetSteps == null ? Collections.emptyMap() : byNetSteps;
        return this;
    }

    /** Plans an OpenPnP board location (pads from packages.xml footprints or pad types). */
    public DispensePlan planBoard(BoardLocation bl, double startX, double startY) {
        BoardPadExtractor.Result r = new BoardPadExtractor(resolver, cfg.includeDisabledPlacements).extract(bl);
        String name = bl.getBoard() == null ? bl.getId() : bl.getBoard().getName();
        DispensePlan plan = plan(name, r.pads, startX, startY);
        plan.boardLocationId = bl.getId();
        plan.boardZMm = bl.getGlobalLocation().convertToUnits(org.openpnp.model.LengthUnit.Millimeters).getZ();
        plan.warnings.addAll(0, r.warnings);
        boardFrame(plan, bl);
        return plan;
    }

    /**
     * The board frame of a plan (the board drawing and the path preview): the board -> machine map,
     * taken through the call every pad goes through (so the drawing, the pads and the dots agree,
     * with OpenPnP's fiducial transform or a registration too), the board's file and size, and its
     * top, enabled fiducials.
     */
    private static void boardFrame(DispensePlan plan, BoardLocation bl) {
        Location o = boardToMachine(bl, 0, 0);
        Location ex = boardToMachine(bl, 100, 0);
        Location ey = boardToMachine(bl, 0, 100);
        plan.boardToMachine = new double[] {(ex.getX() - o.getX()) / 100, (ex.getY() - o.getY()) / 100,
            (ey.getX() - o.getX()) / 100, (ey.getY() - o.getY()) / 100, o.getX(), o.getY()};
        plan.boardTransformStatus = String.valueOf(bl.getPlacementsTransformStatus());
        Board board = bl.getBoard();
        if (board == null) {
            return;
        }
        plan.boardFile = board.getFile() == null ? null : board.getFile().getAbsolutePath();
        Location dims = board.getDimensions().convertToUnits(LengthUnit.Millimeters);
        plan.boardWidthMm = dims.getX();
        plan.boardHeightMm = dims.getY();
        for (Placement pl : board.getPlacements()) {
            if (pl.getType() != Placement.Type.Fiducial || pl.getSide() != Side.Top || !pl.isEnabled()) {
                continue;
            }
            Location l = pl.getLocation().convertToUnits(LengthUnit.Millimeters);
            Location m = boardToMachine(bl, l.getX(), l.getY());
            DispensePlan.BoardFiducial f = new DispensePlan.BoardFiducial();
            f.id = pl.getId();
            f.partId = pl.getPart() == null ? null : pl.getPart().getId();
            f.bx = l.getX();
            f.by = l.getY();
            f.xMm = m.getX();
            f.yMm = m.getY();
            plan.fiducials.add(f);
        }
    }

    private static Location boardToMachine(BoardLocation bl, double x, double y) {
        return Utils2D.calculateBoardPlacementLocation(bl, new Location(LengthUnit.Millimeters, x, y, 0, 0))
                .convertToUnits(LengthUnit.Millimeters);
    }

    /** What the pad inspector shows about a pad besides its size: name, part, roundness, type rule. */
    private static void copyPadDetails(PadGeometry g, DispensePlan.PlannedPad p) {
        p.padName = g.padName;
        p.partId = g.partId;
        p.roundness = g.roundness;
        p.typeRule = g.typeRule;
        p.typeRuleDetail = g.typeRuleDetail;
        p.typeRuleMatched = g.typeRuleMatched;
    }

    public DispensePlan plan(String boardName, List<PadGeometry> pads, double startX, double startY) {
        DispensePlan plan = new DispensePlan();
        plan.planId = UUID.randomUUID().toString();
        plan.boardName = boardName;
        plan.createdAtMs = System.currentTimeMillis();
        plan.profile = cfg.profile.copy();
        plan.profileFingerprint = cfg.profile.fingerprint();
        plan.needleGapMm = cfg.gapMm();
        plan.needleZOffsetMm = cfg.needleZOffsetMm;
        plan.hopHeightMm = cfg.hopHeightMm;
        plan.surfaceFingerprint = surface == null ? null : surface.fingerprint();
        plan.padTypesFingerprint = resolver == null ? null : resolver.fingerprint();
        double worstBoardZDiff = 0;
        int profileSteps = cfg.profile.netStepsPerDot;
        PadTypeStatus minStatus = cfg.minPadTypeStatus();
        Set<String> wholePart = wholePartPads(pads, plan);
        Map<String, Integer> otherTip = new TreeMap<>();

        // per pad of plan.pads: its dots (machine mm), null when it gets none
        List<List<DispensePlan.PlannedDot>> padDots = new ArrayList<>();
        for (PadGeometry g : pads) {
            DispensePlan.PlannedPad p = new DispensePlan.PlannedPad();
            p.padId = g.padId;
            p.placementId = g.placementId;
            p.packageId = g.packageId;
            p.source = g.source;
            p.xMm = g.xMm;
            p.yMm = g.yMm;
            p.rotationDeg = g.rotationDeg;
            p.wMm = g.wMm;
            p.hMm = g.hMm;
            copyPadDetails(g, p);
            p.surfaceZMm = surface == null ? g.surfaceZMm : surface.z(g.xMm, g.yMm);
            if (surface != null) {
                worstBoardZDiff = Math.max(worstBoardZDiff, Math.abs(g.surfaceZMm - p.surfaceZMm));
            }
            plan.pads.add(p);
            padDots.add(null);
            PadType t = g.padType;
            if (t == null) {
                p.flags.add(PadFlag.NO_PAD_TYPE);
                continue;
            }
            p.typeKey = t.typeKey;
            p.typeVersion = t.version;
            p.typeStatus = t.status;
            p.typeTipKey = t.tipKey;
            if (!t.status.atLeast(minStatus)) {
                p.flags.add(PadFlag.STATUS_BELOW_MIN);
            }
            if (cfg.tip.isOther(t.tipKey)) {
                // decision 5: mass per turn and dot size change with the tip, so the type is below the gate
                p.flags.add(PadFlag.STATUS_BELOW_MIN);
                otherTip.merge(t.typeKey, 1, Integer::sum);
            }
            if (wholePart.contains(g.padId)) {
                p.flags.add(PadFlag.PART_NOT_DISPENSABLE);
            }
            if (g.wMm <= 0 || g.hMm <= 0) {
                p.flags.add(PadFlag.NO_GEOMETRY);
                continue;
            }
            p.tMm = t.tMm;
            p.k = t.k;
            p.areaMm2 = DotMath.padAreaMm2(g.wMm, g.hMm, g.roundness);
            p.targetVolumeMm3 = DotMath.targetVolumeMm3(p.areaMm2, t.tMm, t.k);
            p.targetMassMg = p.targetVolumeMm3 * cfg.pasteDensityMgMm3;
            int steps = t.dotSteps != null ? t.dotSteps : profileSteps;
            List<double[]> offsets = offsets(g, t, p.areaMm2, steps);
            p.dotSteps = steps;
            p.dotsPerPad = offsets.size();
            p.rotationPerDotDeg = steps / cfg.bStepsPerDeg;
            if (p.isBlocked()) {
                p.dotCount = 0;
                continue;
            }
            double rad = Math.toRadians(g.rotationDeg);
            double c = Math.cos(rad), sn = Math.sin(rad);
            List<DispensePlan.PlannedDot> mine = new ArrayList<>();
            for (double[] o : offsets) {
                DispensePlan.PlannedDot d = new DispensePlan.PlannedDot();
                d.padId = g.padId;
                d.xMm = g.xMm + o[0] * c - o[1] * sn;
                d.yMm = g.yMm + o[0] * sn + o[1] * c;
                d.surfaceZMm = surface == null ? g.surfaceZMm : surface.z(d.xMm, d.yMm);
                d.dispenseZMm = d.surfaceZMm + cfg.needleZOffsetMm + cfg.gapMm();
                d.hopZMm = d.dispenseZMm + cfg.hopHeightMm;
                d.netSteps = steps;
                d.rotationDeg = steps / cfg.bStepsPerDeg;
                d.padIndex = plan.pads.size() - 1;
                mine.add(d);
            }
            p.dotCount = mine.size();
            padDots.set(plan.pads.size() - 1, mine);
        }
        holdBackBridging(plan, padDots);
        for (Map.Entry<String, Integer> e : otherTip.entrySet()) {
            PadType t = resolver.byKey(e.getKey());
            plan.warnings.add(Texts.belowGateTip(t == null || t.name == null ? e.getKey() : t.name,
                    t == null ? "?" : t.tipKey, cfg.tip.name) + " " + Texts.plural(e.getValue(), "pad", "pads")
                    + " of " + e.getKey() + (e.getValue() == 1 ? " gets" : " get") + " no paste (STATUS_BELOW_MIN).");
        }
        if (surface == null) {
            plan.warnings.add("no probed board surface: dot Z is the board's single Z; a run is refused (I4) until "
                    + "the board surface is probed (at least 3 N1 points)");
        }
        else if (worstBoardZDiff > cfg.zFrameToleranceMm) {
            plan.warnings.add(String.format(java.util.Locale.ROOT, "the board location Z differs from the probed "
                    + "surface by up to %.2f mm; dot Z comes from the probed surface. Check the board Z in OpenPnP "
                    + "(it is also used for placing)", worstBoardZDiff));
        }
        order(plan, padDots, startX, startY);
        return plan;
    }

    /**
     * Dot centres in the pad frame: a type with its own dose or dots per pad lays them with the
     * same-pad pitch (the needle face must not land on the earlier dot) and moves them in to the
     * overhang limit (§4.3 step 3); the others keep DotSizer's grid, as before.
     */
    private List<double[]> offsets(PadGeometry g, PadType t, double areaMm2, int steps) {
        PadRule rule = rules.get(t.typeKey);
        if (t.dotSteps == null && t.dotsPerPad == null) {
            return DotSizer.layout(g.wMm, g.hMm, areaMm2, rule);
        }
        int n = t.dotsPerPad != null ? t.dotsPerPad
                : DotGeometry.profileDotCount(areaMm2, rule == null ? null : rule.largestSingleDotPadMm2,
                        rule == null ? null : rule.maxDots);
        double r = DoseModel.dHiMm(steps) / 2;
        return DotGeometry.layoutN(g.wMm, g.hMm, n, r + cfg.tip.odMm / 2, r);
    }

    /**
     * Decision 4: the pads of parts that have a pad the mounted tip cannot dispense (the profile
     * check before planning, reason "part"): the part is treated whole unless its package is set to
     * paste the dispensable pads anyway ({@link PadTypeResolver#pastePartial()}). The pads the tip
     * cannot dispense (reason "tip") of such a part get no paste either way, also when a pad type
     * covers them: only its dispensable pads are ever pasted. A part made only of such pads is left
     * to the gate (§3.3), unless they are too close to a neighbour (decision 14: always held back).
     */
    private Set<String> wholePartPads(List<PadGeometry> pads, DispensePlan plan) {
        Set<String> ids = new HashSet<>();
        if (resolver == null || pads.isEmpty()) {
            return ids;
        }
        ProfileCheck.Result r;
        try {
            r = ProfileCheck.of(PadInstances.inputs(PadInstances.fromGeometry(pads), resolver.types(), rules, cfg,
                    resolver.pastePartial(), false));
        }
        catch (RuntimeException e) {
            plan.warnings.add("the profile check failed (" + e + "): parts with pads the tip cannot dispense are not "
                    + "held back");
            return ids;
        }
        Set<String> refs = new TreeSet<>(GroupKey.NATURAL);
        Set<String> tip = new HashSet<>();
        Set<String> near = new HashSet<>();
        for (ProfileCheck.Group g : r.groups) {
            if (ProfileCheck.PART.equals(g.reason)) {
                ids.addAll(g.padIds);
                refs.addAll(g.refs);
            }
            else if (ProfileCheck.TIP.equals(g.reason)) {
                tip.addAll(g.padIds);
                if (ProfileCheck.TIP_NEAR.equals(g.tipKind)) {
                    near.addAll(g.padIds);
                }
            }
        }
        if (!ids.isEmpty()) {
            plan.warnings.add(Texts.plural(ids.size(), "pad", "pads") + " of " + Texts.plural(refs.size(), "part",
                    "parts") + (ids.size() == 1 ? " gets" : " get") + " no paste (" + shortList(refs) + "): another "
                    + "pad of the part cannot be dispensed with the " + cfg.tip.name + ", and a part soldered by hand "
                    + "gets no paste on its other pads either (PART_NOT_DISPENSABLE). Profile can paste the "
                    + "dispensable pads of a package anyway.");
        }
        // the tip's own pads of a part that also has dispensable pads, where a pad type would paste them
        Set<String> mixed = new HashSet<>();
        for (PadGeometry g : pads) {
            if (g.placementId != null && !tip.contains(g.padId)) {
                mixed.add(g.placementId);
            }
        }
        Set<String> tipIds = new HashSet<>();
        Set<String> tipRefs = new TreeSet<>(GroupKey.NATURAL);
        for (PadGeometry g : pads) {
            if (g.padType != null && tip.contains(g.padId)
                    && (mixed.contains(g.placementId) || near.contains(g.padId))) {
                tipIds.add(g.padId);
                tipRefs.add(g.placementId);
            }
        }
        if (!tipIds.isEmpty()) {
            ids.addAll(tipIds);
            plan.warnings.add(Texts.plural(tipIds.size(), "pad", "pads") + " of " + Texts.plural(tipRefs.size(),
                    "part", "parts") + (tipIds.size() == 1 ? " gets" : " get") + " no paste (" + shortList(tipRefs)
                    + "): the " + cfg.tip.name + " cannot dispense " + (tipIds.size() == 1 ? "it" : "them") + ", also "
                    + "where the other pads of the part are pasted (PART_NOT_DISPENSABLE).");
        }
        return ids;
    }

    /**
     * Decisions 11 and 14: a pad whose dots come within 0.20 mm of another pad's dots gets the
     * blocking MAY_BRIDGE flag and no dots, each pad on its own (not its whole group).
     */
    private void holdBackBridging(DispensePlan plan, List<List<DispensePlan.PlannedDot>> padDots) {
        List<PadInstance> laid = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        List<Double> radius = new ArrayList<>();
        for (int i = 0; i < plan.pads.size(); i++) {
            List<DispensePlan.PlannedDot> dots = padDots.get(i);
            if (dots == null || dots.isEmpty()) {
                continue;
            }
            DispensePlan.PlannedPad p = plan.pads.get(i);
            PadInstance pi = new PadInstance();
            pi.padId = p.padId;
            pi.ref = p.placementId;
            pi.x = p.xMm;
            pi.y = p.yMm;
            pi.rotationDeg = p.rotationDeg;
            pi.wMm = p.wMm;
            pi.hMm = p.hMm;
            pi.dots = new ArrayList<>();
            for (DispensePlan.PlannedDot d : dots) {
                pi.dots.add(new double[] {d.xMm, d.yMm});
            }
            laid.add(pi);
            at.add(i);
            radius.add(dotDiameters.getOrDefault(p.dotSteps, DoseModel.dHiMm(p.dotSteps)) / 2);
        }
        double[] r = new double[radius.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = radius.get(i);
        }
        Map<Integer, Integer> bridging = PadInstances.bridging(laid, r);
        if (bridging.isEmpty()) {
            return;
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : bridging.entrySet()) {
            int i = at.get(e.getKey());
            DispensePlan.PlannedPad p = plan.pads.get(i);
            p.flags.add(PadFlag.MAY_BRIDGE);
            p.dotCount = 0;
            padDots.set(i, null);
            pairs.add(p.padId + " to " + laid.get(e.getValue()).padId);
        }
        plan.warnings.add(String.format(Locale.ROOT, "%s may bridge: their dots would come within %s mm of another "
                + "pad's dots (%s); they get no paste (MAY_BRIDGE). A pad type for this size avoids it.",
                Texts.plural(bridging.size(), "pad", "pads"), Texts.mm(Limits.DOT_GAP_MARGINAL_MM), shortList(pairs)));
    }

    private static String shortList(Collection<String> l) {
        List<String> a = new ArrayList<>(l);
        return a.size() <= 8 ? String.join(", ", a) : String.join(", ", a.subList(0, 8)) + ", ...";
    }

    /**
     * Blocks and order (§6.1): the pads with dots grouped by dose, the profile's dose first, then
     * descending; in each block the pads in nearest neighbour + 2-opt order (by their centres) from
     * where the block starts (block 0: the end of the initial purge; later blocks: their switch's last
     * flush spot); each pad's dots nearest first from the previous dot (R5). Dot indices run across
     * the blocks; every pad gets the screw angle its first dot starts at.
     */
    private void order(DispensePlan plan, List<List<DispensePlan.PlannedDot>> padDots, double startX,
            double startY) {
        int profileSteps = cfg.profile.netStepsPerDot;
        Map<Integer, List<Integer>> byDose = new LinkedHashMap<>();
        for (int i = 0; i < plan.pads.size(); i++) {
            if (padDots.get(i) != null) {
                byDose.computeIfAbsent(plan.pads.get(i).dotSteps, k -> new ArrayList<>()).add(i);
            }
        }
        List<Integer> doses = new ArrayList<>(byDose.keySet());
        doses.sort((a, b) -> a == profileSteps ? -1 : b == profileSteps ? 1 : Integer.compare(b, a));
        int flush = cfg.padCalibration.flushDots;
        int rev = cfg.bStepsPerRev;
        List<DoseBlocks.Run> runs = new ArrayList<>();
        for (int s : doses) {
            runs.add(new DoseBlocks.Run(s, 0, 0));
        }
        int initial = DoseBlocks.initialPurge(runs, cfg, flush);
        int ordinal = initial - 1;
        int startAngle = cfg.profile.startAngleSteps(cfg);
        long angle = startAngle;
        double sx = startX, sy = startY;
        double path = 0, nnExtra = 0;
        boolean onePerPad = true;
        PathOrder.Result last = null;
        for (int k = 0; k < doses.size(); k++) {
            int steps = doses.get(k);
            boolean partial = DoseBlocks.isPartial(steps, rev);
            if (k > 0) {
                ordinal += DoseBlocks.switchSpots(steps, flush, rev);
                double[] spot = purgeSpot.apply(ordinal);
                if (spot != null) {
                    sx = spot[0];
                    sy = spot[1];
                }
                if (partial) {
                    angle = startAngle;
                }
            }
            List<Integer> blockPads = byDose.get(steps);
            double[] xs = new double[blockPads.size()];
            double[] ys = new double[blockPads.size()];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = plan.pads.get(blockPads.get(i)).xMm;
                ys[i] = plan.pads.get(blockPads.get(i)).yMm;
            }
            PathOrder.Result o = PathOrder.order(xs, ys, sx, sy, cfg.twoOptMaxPasses);
            DispensePlan.Block b = new DispensePlan.Block();
            b.index = k;
            b.netSteps = steps;
            b.turn = DoseBlocks.turnName(steps);
            b.pads = blockPads.size();
            b.firstDot = plan.dots.size();
            b.phaseDot = k == 0 ? initial > 0 : partial;
            b.flushDots = k == 0 ? Math.max(0, initial - 1) : flush;
            double cx = sx, cy = sy, blockPath = 0;
            for (int t : o.order) {
                int pi = blockPads.get(t);
                DispensePlan.PlannedPad p = plan.pads.get(pi);
                p.startPhase = (int) Math.floorMod(angle, (long) rev);
                List<DispensePlan.PlannedDot> left = new ArrayList<>(padDots.get(pi));
                onePerPad &= left.size() == 1;
                while (!left.isEmpty()) {
                    int best = 0;
                    for (int j = 1; j < left.size(); j++) {
                        if (Math.hypot(left.get(j).xMm - cx, left.get(j).yMm - cy)
                                < Math.hypot(left.get(best).xMm - cx, left.get(best).yMm - cy) - 1e-12) {
                            best = j;
                        }
                    }
                    DispensePlan.PlannedDot d = left.remove(best);
                    blockPath += Math.hypot(d.xMm - cx, d.yMm - cy);
                    cx = d.xMm;
                    cy = d.yMm;
                    d.index = plan.dots.size();
                    d.block = k;
                    plan.dots.add(d);
                    angle += (long) cfg.bExtrudeSign * steps;
                }
            }
            b.lastDot = plan.dots.size() - 1;
            b.dots = b.lastDot - b.firstDot + 1;
            plan.blocks.add(b);
            path += blockPath;
            // the pads in nearest-neighbour order instead of 2-opt (the dots inside a pad as above)
            nnExtra += o.nearestNeighbourLength - o.length;
            sx = cx;
            sy = cy;
            last = o;
        }
        if (doses.size() == 1 && onePerPad) {
            // one dot per pad in one block: the dots are the pad centres, so the pad path is the dot path
            plan.pathLengthNearestNeighbourMm = last.nearestNeighbourLength;
            plan.pathLengthMm = last.length;
            return;
        }
        plan.pathLengthMm = path;
        plan.pathLengthNearestNeighbourMm = path + nnExtra;
    }
}
