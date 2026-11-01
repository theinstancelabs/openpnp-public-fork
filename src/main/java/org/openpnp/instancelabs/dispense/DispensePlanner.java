package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.openpnp.model.BoardLocation;

/**
 * Board -> pads -> dots -> order (nearest neighbour + 2-opt). Every dot is one dot of the active
 * dispense profile (its screw turn, dwell and gap): one dot per pad, or a grid where the pad
 * type's rule says the pad is too large for one dot. There is no dose model: the volumetric
 * (q per revolution) model was removed after the bench showed the dose follows the turn, pressure
 * and dwell, not a displacement constant (2026-10-28). Each plan carries a copy of the profile.
 */
public class DispensePlanner {
    private final DispenseConfig cfg;
    private final PadTypeResolver resolver;
    private java.util.Map<String, PadRule> rules = java.util.Collections.emptyMap();
    private SurfacePlane surface;

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

    /** Plans an OpenPnP board location (pads from packages.xml footprints or pad types). */
    public DispensePlan planBoard(BoardLocation bl, double startX, double startY) {
        BoardPadExtractor.Result r = new BoardPadExtractor(resolver, cfg.includeDisabledPlacements).extract(bl);
        String name = bl.getBoard() == null ? bl.getId() : bl.getBoard().getName();
        DispensePlan plan = plan(name, r.pads, startX, startY);
        plan.boardLocationId = bl.getId();
        plan.warnings.addAll(0, r.warnings);
        return plan;
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
        double netDeg = cfg.profile.netDeg(cfg);
        PadTypeStatus minStatus = cfg.minPadTypeStatus();

        List<double[]> dotXy = new ArrayList<>();
        List<DispensePlan.PlannedDot> unordered = new ArrayList<>();
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
            p.surfaceZMm = surface == null ? g.surfaceZMm : surface.z(g.xMm, g.yMm);
            if (surface != null) {
                worstBoardZDiff = Math.max(worstBoardZDiff, Math.abs(g.surfaceZMm - p.surfaceZMm));
            }
            plan.pads.add(p);
            PadType t = g.padType;
            if (t == null) {
                p.flags.add(PadFlag.NO_PAD_TYPE);
                continue;
            }
            p.typeKey = t.typeKey;
            p.typeVersion = t.version;
            p.typeStatus = t.status;
            if (!t.status.atLeast(minStatus)) {
                p.flags.add(PadFlag.STATUS_BELOW_MIN);
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
            p.rotationPerDotDeg = netDeg;
            if (p.isBlocked()) {
                p.dotCount = 0;
                continue;
            }
            List<double[]> offsets = DotSizer.layout(g.wMm, g.hMm, p.areaMm2, rules.get(t.typeKey));
            p.dotCount = offsets.size();
            double rad = Math.toRadians(g.rotationDeg);
            double c = Math.cos(rad), sn = Math.sin(rad);
            for (double[] o : offsets) {
                DispensePlan.PlannedDot d = new DispensePlan.PlannedDot();
                d.padId = g.padId;
                d.xMm = g.xMm + o[0] * c - o[1] * sn;
                d.yMm = g.yMm + o[0] * sn + o[1] * c;
                d.surfaceZMm = surface == null ? g.surfaceZMm : surface.z(d.xMm, d.yMm);
                d.dispenseZMm = d.surfaceZMm + cfg.needleZOffsetMm + cfg.gapMm();
                d.hopZMm = d.dispenseZMm + cfg.hopHeightMm;
                d.rotationDeg = netDeg;
                unordered.add(d);
                dotXy.add(new double[] {d.xMm, d.yMm});
            }
        }
        double[] xs = new double[unordered.size()];
        double[] ys = new double[unordered.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = dotXy.get(i)[0];
            ys[i] = dotXy.get(i)[1];
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
        PathOrder.Result order = PathOrder.order(xs, ys, startX, startY, cfg.twoOptMaxPasses);
        for (int i = 0; i < order.order.length; i++) {
            DispensePlan.PlannedDot d = unordered.get(order.order[i]);
            d.index = i;
            plan.dots.add(d);
        }
        plan.pathLengthNearestNeighbourMm = order.nearestNeighbourLength;
        plan.pathLengthMm = order.length;
        return plan;
    }
}
