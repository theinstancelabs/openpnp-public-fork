package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openpnp.instancelabs.padprofile.DoseModel;

/**
 * The screw-phase rules of the dose blocks (profile calibration design §1.2 R1-R3, R7, §6.3), pure:
 * which net turns a dot may have, the blocks of a plan's dots (consecutive dots of one dose), the
 * purge-row spots a program needs (the initial purge, then a phase dot for a partial turn and the
 * flush dots before every further block) and a block's paste demand.
 */
public final class DoseBlocks {
    private DoseBlocks() {
    }

    /** A run of consecutive dots of one dose: {@code count} dots from dot {@code first}. */
    public static final class Run {
        public final int netSteps;
        public final int first;
        public final int count;

        public Run(int netSteps, int first, int count) {
            this.netSteps = netSteps;
            this.first = first;
            this.count = count;
        }
    }

    /**
     * R1: the profile's own dose (today's rule, {@link DispenseProfile#validate}), or a pad type's
     * quarter, half or whole turn at or above the mounted tip's smallest dose.
     */
    public static boolean isAllowed(int steps, DispenseConfig cfg) {
        if (steps == cfg.profile.netStepsPerDot) {
            return true;
        }
        return PadType.isAllowedDotSteps(steps) && steps >= cfg.tip.minDotSteps;
    }

    /** True if a dose is not a whole number of revolutions: its block needs the start angle (R2). */
    public static boolean isPartial(int steps, int stepsPerRev) {
        return steps % stepsPerRev != 0;
    }

    /** "quarter turn", "half turn", "one turn". */
    public static String turnName(int steps) {
        return DoseModel.turnNoun(steps);
    }

    /**
     * The dose of every dot of a plan, in its order: the dot's own net steps, else its pad's
     * ({@link DispensePlan.PlannedPad#dotSteps}: a plan copied without the dot fields keeps the pad's
     * dose), else the profile's.
     */
    public static int[] dosesOf(DispensePlan plan, int profileSteps) {
        Map<String, Integer> byPad = new HashMap<>();
        for (DispensePlan.PlannedPad p : plan.pads) {
            if (p.dotSteps != null) {
                byPad.put(p.padId, p.dotSteps);
            }
        }
        int[] d = new int[plan.dots.size()];
        for (int i = 0; i < d.length; i++) {
            DispensePlan.PlannedDot dot = plan.dots.get(i);
            Integer pad = byPad.get(dot.padId);
            d[i] = dot.netSteps > 0 ? dot.netSteps : pad != null ? pad : profileSteps;
        }
        return d;
    }

    /** Blocks of consecutive equal doses. */
    public static List<Run> blocksOf(int[] doses) {
        List<Run> l = new ArrayList<>();
        int i = 0;
        while (i < doses.length) {
            int j = i;
            while (j < doses.length && doses[j] == doses[i]) {
                j++;
            }
            l.add(new Run(doses[i], i, j - i));
            i = j;
        }
        return l;
    }

    public static List<Run> blocksOf(DispensePlan plan, DispenseConfig cfg) {
        return blocksOf(dosesOf(plan, cfg.profile.netStepsPerDot));
    }

    /** Some block is a partial turn; with no blocks, the profile's dose decides (the purge alone uses it). */
    public static boolean anyPartial(List<Run> blocks, DispenseConfig cfg) {
        if (blocks.isEmpty()) {
            return cfg.profile.isPartialTurn(cfg);
        }
        for (Run b : blocks) {
            if (isPartial(b.netSteps, cfg.bStepsPerRev)) {
                return true;
            }
        }
        return false;
    }

    public static boolean anyPartial(DispensePlan plan, DispenseConfig cfg) {
        return anyPartial(blocksOf(plan, cfg), cfg);
    }

    /** Purge-row spots of a block switch (R3): the flush dots, and a phase dot first for a partial turn. */
    public static int switchSpots(int steps, int flush, int stepsPerRev) {
        return flush + (isPartial(steps, stepsPerRev) ? 1 : 0);
    }

    /**
     * Spots of the initial purge: the profile's purge dots; when the first block is not the
     * profile's dose but a partial turn, at least a phase dot and the flush dots, as at a switch (a
     * profile of whole turns may have no purge).
     */
    public static int initialPurge(List<Run> blocks, DispenseConfig cfg, int flush) {
        int n = cfg.profile.purgeDots;
        if (!blocks.isEmpty() && blocks.get(0).netSteps != cfg.profile.netStepsPerDot
                && isPartial(blocks.get(0).netSteps, cfg.bStepsPerRev)) {
            n = Math.max(n, 1 + flush);
        }
        return n;
    }

    /** purgeDots + the switch spots of every block after the first (§6.3). */
    public static int purgeSpotsNeeded(List<Run> blocks, DispenseConfig cfg, int flush) {
        int n = initialPurge(blocks, cfg, flush);
        for (int k = 1; k < blocks.size(); k++) {
            n += switchSpots(blocks.get(k).netSteps, flush, cfg.bStepsPerRev);
        }
        return n;
    }

    public static int purgeSpotsNeeded(DispensePlan plan, DispenseConfig cfg) {
        return purgeSpotsNeeded(blocksOf(plan, cfg), cfg, cfg.padCalibration.flushDots);
    }

    /** The model's mass per motor step at 18 psi (§1.1: 2.11 mg per revolution). */
    public static double modelMgPerStep(DispenseConfig cfg) {
        return DoseModel.M_REV_MG / cfg.bStepsPerRev;
    }

    /**
     * R7's paste per motor step for the mounted tip setup (tip setups design §22.2): the bench model's for the bench
     * setup (the 22G as before); otherwise its weighed mass per step, else the bench's × 1.5 when its bore is wider
     * than the 22G's 0.41 mm (a wider bore slips less, so the bench value would understate the demand).
     */
    public static double feedMgPerStep(DispenseConfig cfg, org.openpnp.instancelabs.padprofile.Tip tip) {
        if (tip == null || tip.benchModel) {
            return modelMgPerStep(cfg);
        }
        if (tip.mgPerStep != null && tip.mgPerStep > 0) {
            return tip.mgPerStep;
        }
        return modelMgPerStep(cfg) * (tip.idMm > 0.41 + 1e-9 ? 1.5 : 1);
    }

    /** R7: the paste a block asks of the screw, mg/s. */
    public static double demandMgS(int dots, int steps, double mgPerStep, double seconds) {
        return seconds > 0 ? dots * (double) steps * mgPerStep / seconds : Double.POSITIVE_INFINITY;
    }

    /** Copies the estimate's per-block demand to the plan's blocks (the same blocks, in the same order). */
    public static void fillDemand(DispensePlan plan, GcodeProgram estimate) {
        if (estimate == null || estimate.blocks == null || estimate.blocks.size() != plan.blocks.size()) {
            return;
        }
        for (int k = 0; k < plan.blocks.size(); k++) {
            plan.blocks.get(k).demandMgS = estimate.blocks.get(k).demandMgS;
        }
    }
}
