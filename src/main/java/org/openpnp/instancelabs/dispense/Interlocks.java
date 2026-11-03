package org.openpnp.instancelabs.dispense;

import java.util.List;
import java.util.Locale;

import org.openpnp.instancelabs.padprofile.Texts;

/**
 * Pre-flight interlocks (plan §5.5). Runtime interlocks are enforced elsewhere: I1 (outputs off on
 * error/abort/Stop) and I7 (on-time, at runtime) in {@link DispenseExecutor}; I2 (no B move before
 * G92 B0) structurally in {@link GcodeStreamBuilder} and checked by {@link GcodeStreamValidator}.
 */
public final class Interlocks {
    private Interlocks() {
    }

    /** Facts about the machine gathered by the caller (keeps this class free of OpenPnP state). */
    public static class Inputs {
        public DispenseConfig cfg;
        public boolean machineEnabled;
        public boolean machineHomed;
        public String nozzleName;
        /** Names of nozzle tips N2 is configured as compatible with (I3). */
        public List<String> compatibleNozzleTips;
        /** Name of the nozzle tip currently loaded on N2, or null (I3). */
        public String loadedNozzleTip;
        public BarrelPressureSensor pressureSensor;
        public DispensePlan plan;
        /**
         * The pad types now (type key -> current version), or null if unknown: a plan whose pad types changed (edited,
         * status changed, retired) after planning is stale.
         */
        public java.util.Map<String, PadType> padTypesNow;
        /**
         * {@link PadTypeResolver#fingerprint} of the pad types and package mappings now, or null if unknown: a new type,
         * or a pattern or mapping change, can resolve the plan's packages to another type.
         */
        public String padTypesFingerprintNow;
        /** Program estimate of pressurised time (I7 pre-check). */
        public double estimatedPressurisedSeconds;
        /** Top camera + dot vision present (I10 clean-pad check). */
        public boolean opticalCheckAvailable;
        /** When OpenPnP last connected to the controller (epoch ms; I11), or null if unknown. */
        public Long controllerConnectedAtMs;
        /**
         * Purge-row spots this run lays (I9): the initial purge and the switch spots of every further
         * dose block ({@link DoseBlocks#purgeSpotsNeeded}); and whether it is a board run (I9, I10, I11
         * apply). I9 also applies to calibration blocks that lay purge-row dots.
         */
        public int purgeDots;
        public boolean boardRun;
        /**
         * Some dose block of the run is a partial turn (I11). Null: a board run takes it from its plan's
         * dots (the profile's dose when it has none), any other run has none.
         */
        public Boolean partialTurn;
        /** The program's dose blocks with their paste demand (I6-feed, §1.2 R7), or null: the plan's blocks. */
        public List<GcodeProgram.BlockInfo> programBlocks;
        /** What the run touches (I4 Z frame); the default is a generic needle run. */
        public Scope scope = new Scope();
        /** The task homes first ({@link HomeTracker}): "not homed" is a note, not a violation. */
        public boolean homeFirst;
        /** The task checks Z itself after the home ({@link ZCheck}): stale Z data are a note. */
        public boolean zCheckFirst;
        /** The last home seen ({@link HomeTracker#getLastHomeAtMs()}), or null: Z data older are stale. */
        public Long lastHomeAtMs;
        /** The latest Z check, or null. */
        public ZCheck.Result zCheck;
        /** The controller is confirmed to be OpenPnP's simulator: surfaces probed there are usable. */
        public boolean simulatorConfirmed;
    }

    /** What a run uses, for the I4 Z-frame checks. */
    public static class Scope {
        /** False for runs that never move the needle (the pressure hold): no I4. */
        public boolean needleMotion = true;
        /** The named I4 waiver: the needle touch-off run itself (it sets the touched-off state). */
        public boolean touchOffWaiver;
        /** Places whose Z must agree with the needle frame (waste, blank). */
        public final List<String> referencePlaces = new java.util.ArrayList<>();
        /**
         * Places whose stored Z the run dispenses at (prime, test dot, shots, the prime at the waste): after a failed Z
         * check of this home, one set before that home is refused until its Z is taken again (I4).
         */
        public final List<String> dispensePlaces = new java.util.ArrayList<>();
        /** Surface map this run takes its dot Z from (board or blank), or null. */
        public String surfaceName;
        /** Machine X/Y of the dots that take their Z from that surface. */
        public final List<double[]> surfaceTargets = new java.util.ArrayList<>();
        /** Plan runs: the plan must have been made on the current surface map. */
        public boolean checkPlanSurface;
        /** Further surfaces the run dispenses on (the purge row on the blank) and their targets. */
        public final java.util.Map<String, List<double[]>> moreSurfaces = new java.util.LinkedHashMap<>();

        public static Scope noNeedle() {
            Scope s = new Scope();
            s.needleMotion = false;
            return s;
        }
    }

    /**
     * The pad-type gate and the pad types are read when a plan is made (§5.7). Review finding: raising the gate or
     * editing / retiring a pad type after planning left the plan "current", and a run then dispensed pads below the new
     * gate. Both now make the plan stale ("... after planning ...; plan again"), so the run is refused until it is
     * planned again. Final review: a new pad type (or a pattern or package mapping change) that takes over the plan's
     * packages left the plan current too; any change of the resolver's inputs now makes it stale.
     */
    static void checkPadTypesSincePlanning(Inputs in, InterlockReport r) {
        PadTypeStatus gate;
        try {
            gate = in.cfg.minPadTypeStatus();
        }
        catch (IllegalArgumentException e) {
            return; // an unknown gate is a CONFIG problem
        }
        int below = 0;
        int nowPass = 0;
        int otherTip = 0;
        java.util.Set<String> changed = new java.util.TreeSet<>();
        for (DispensePlan.PlannedPad p : in.plan.pads) {
            if (p.typeKey == null) {
                continue;
            }
            if (in.padTypesNow != null) {
                PadType now = in.padTypesNow.get(p.typeKey);
                if (now == null || now.version != p.typeVersion) {
                    changed.add(p.typeKey + " v" + p.typeVersion + (now == null ? " is gone" : " is now v" + now.version));
                }
            }
            if (p.typeStatus == null) {
                continue;
            }
            // decision 5: a type calibrated with another tip than the mounted one is below the gate
            boolean tipOk = !in.cfg.tip.isOther(p.typeTipKey);
            boolean gated = p.flags.contains(PadFlag.STATUS_BELOW_MIN);
            if (!gated && !p.isBlocked() && !tipOk) {
                otherTip++;
            }
            else if (!gated && !p.isBlocked() && !p.typeStatus.atLeast(gate)) {
                below++;
            }
            else if (gated && p.flags.size() == 1 && p.typeStatus.atLeast(gate) && tipOk) {
                nowPass++;
            }
        }
        if (otherTip > 0) {
            r.add("PLAN", "the tip changed after planning: " + otherTip + " pad(s) of this plan have pad types "
                    + "calibrated with another tip than the " + in.cfg.tip.name + "; plan again");
        }
        else if (below > 0) {
            r.add("PLAN", "the pad-type gate changed after planning: " + below + " pad(s) of this plan are below '"
                    + gate.dbName + "' now; plan again");
        }
        else if (nowPass > 0) {
            r.add("PLAN", "the pad-type gate changed after planning: " + nowPass + " blocked pad(s) would pass now; "
                    + "plan again");
        }
        if (!changed.isEmpty()) {
            r.add("PLAN", "pad types changed after planning: " + String.join(", ", changed) + "; plan again");
        }
        else if (in.padTypesFingerprintNow != null && in.plan.padTypesFingerprint != null
                && !in.padTypesFingerprintNow.equals(in.plan.padTypesFingerprint)) {
            r.add("PLAN", "pad types or package mappings changed after planning (a pad may resolve to another pad type "
                    + "now); plan again");
        }
    }

    /**
     * I4 (plan §5.5): needle touched off since the last needle change, and the target inside the
     * probed surface. Review finding: nothing tied place / board Z to the frame the needle offset
     * was measured in. The frame is now anchored by one surface touched by N1 and by the needle;
     * places, surface points and the offset must agree with it, every dot must lie inside its
     * probed surface, and the surface must be flat to within the gap budget.
     */
    static void checkI4(Inputs in, InterlockReport r) {
        DispenseConfig cfg = in.cfg;
        Scope sc = in.scope == null ? new Scope() : in.scope;
        if (!sc.needleMotion) {
            return;
        }
        DispenseConfig.NeedleAnchor a = cfg.needleAnchor;
        if (a == null) {
            r.add("I4", "the needle Z frame is not set: touch the same surface with N1 and with the needle tip and "
                    + "record both heights (Needle card)");
            return;
        }
        if (!cfg.isNeedleAnchored()) {
            r.add("I4", "the needle was changed after the needle Z frame was recorded: record the N1 and needle "
                    + "contact heights again (Needle card)");
            return;
        }
        if (sc.touchOffWaiver) {
            r.notes.add("I4: touch-off run (the touched-off state is what it sets)");
        }
        else if (!cfg.isNeedleTouchedOff()) {
            r.add("I4", "needle Z offset has not been set since the last needle change / needle frame (run a touch-off)");
        }
        double tol = cfg.zFrameToleranceMm;
        double drift = cfg.needleZOffsetMm - a.offsetMm();
        if (Math.abs(drift) > cfg.needleOffsetMaxDriftMm) {
            r.add("I4", String.format(Locale.ROOT, "needle Z offset %.3f mm is %.3f mm away from the needle frame's "
                    + "%.3f mm (limit %.2f): record the needle frame again", cfg.needleZOffsetMm, drift, a.offsetMm(),
                    cfg.needleOffsetMaxDriftMm));
        }
        for (String name : sc.referencePlaces) {
            DispenseConfig.NamedLocation loc = cfg.location(name);
            if (loc == null) {
                continue; // a missing place is refused by the run itself
            }
            double d = loc.z - a.surfaceZMm;
            if (Math.abs(d) > tol) {
                r.add("I4", String.format(Locale.ROOT, "place '%s' Z %.3f is %.3f mm from the needle-frame surface "
                        + "(Z %.3f, tolerance %.2f). Place Z is the N1 contact height (OpenPnP frame); a height "
                        + "taken with the needle differs by about the needle offset (%.2f mm)", name, loc.z, d,
                        a.surfaceZMm, tol, a.offsetMm()));
            }
        }
        if (sc.surfaceName != null) {
            checkSurface(in, r, sc.surfaceName, sc.surfaceTargets, sc.checkPlanSurface);
        }
        for (java.util.Map.Entry<String, List<double[]>> e : sc.moreSurfaces.entrySet()) {
            checkSurface(in, r, e.getKey(), e.getValue(), false);
        }
        checkZAfterHome(in, r, sc);
        checkPlacesAfterZMoved(in, r, sc);
    }

    /**
     * I4, places after a failed Z check of this home (rc1 review, machine safety): a place carries the time it was set,
     * not a Z frame a check can vouch for, so the remedy's frame, blank, touch-off and board left the waste (and a cup,
     * a coupon) at the old Z, and prime and the test dot then put the needle into the waste by the move. Each place the
     * run dispenses at must have been set again since that home.
     */
    static void checkPlacesAfterZMoved(Inputs in, InterlockReport r, Scope sc) {
        if (!ZCheck.failed(in.zCheck, in.lastHomeAtMs)) {
            return;
        }
        for (String name : sc.dispensePlaces) {
            DispenseConfig.NamedLocation loc = in.cfg.location(name);
            if (ZCheck.placeBefore(loc, in.lastHomeAtMs)) {
                r.add("I4", ZCheck.placeMovedMessage(name, loc, in.zCheck));
            }
        }
    }

    /**
     * I4, Z after a home: a home can move the Z zero (0.7 mm on 2026-10-28; the needle then moves
     * about twice that on the see-saw). The needle frame, the touch-off and the surfaces the scope
     * uses, measured before the last home, are accepted only after a passing Z check for that home
     * that vouches for each of them (touched a point in its Z frame).
     */
    static void checkZAfterHome(Inputs in, InterlockReport r, Scope sc) {
        if (in.homeFirst && in.zCheckFirst) {
            r.notes.add(ZCheck.checksFirstNote(in.lastHomeAtMs, true));
            return;
        }
        List<ZCheck.Stale> stale = ZCheck.unchecked(in.zCheck, in.lastHomeAtMs, ZCheck.stale(in.cfg, sc,
                in.lastHomeAtMs));
        if (stale.isEmpty()) {
            return;
        }
        if (ZCheck.failed(in.zCheck, in.lastHomeAtMs)) {
            r.add("I4", ZCheck.movedMessage(in.zCheck));
        }
        else if (in.zCheckFirst) {
            r.notes.add(ZCheck.checksFirstNote(in.lastHomeAtMs, false));
        }
        else {
            r.add("I4", ZCheck.notCheckedMessage(stale, in.lastHomeAtMs));
        }
    }

    /**
     * The frame reference of a surface (one per surface): a board surface tied to a plan is checked
     * against the plan's board top Z (the board location's Z), so a board that is simply lower than
     * the blank is not refused; every other surface, and a plan without a board Z, against the
     * needle-frame anchor. Either reference still catches a height taken with the needle (2.59 mm off).
     */
    static Double boardReferenceZ(Inputs in, boolean planSurface) {
        return planSurface && in.plan != null ? in.plan.boardZMm : null;
    }

    /** I4 for one probed surface and the dots that take their Z from it. */
    private static void checkSurface(Inputs in, InterlockReport r, String name, List<double[]> targets,
            boolean checkPlan) {
        DispenseConfig cfg = in.cfg;
        DispenseConfig.NeedleAnchor a = cfg.needleAnchor;
        double tol = cfg.zFrameToleranceMm;
        DispenseConfig.SurfaceMap m = cfg.surface(name);
        if (m == null || m.points == null || m.points.size() < 3) {
            r.add("I4", "no '" + name + "' surface: probe at least 3 points around it with N1 (Surfaces card)");
            return;
        }
        SurfacePlane pl;
        try {
            pl = SurfacePlane.fit(m.points);
        }
        catch (IllegalArgumentException e) {
            r.add("I4", "'" + name + "' surface: " + e.getMessage());
            return;
        }
        if (pl.maxAbsResidualMm > cfg.surfaceMaxResidualMm) {
            r.add("I4", String.format(Locale.ROOT, "'%s' surface is not flat enough for a plane: largest residual "
                    + "%.3f mm, limit %.3f mm (gap budget)", name, pl.maxAbsResidualMm, cfg.surfaceMaxResidualMm));
        }
        if (Boolean.TRUE.equals(m.simulated) && !in.simulatorConfirmed) {
            r.add("I4", "the '" + name + "' surface was probed on the simulator: probe it again on this machine");
        }
        Double boardZ = boardReferenceZ(in, checkPlan);
        if (boardZ != null) {
            if (pl.minZ < boardZ - tol || pl.maxZ > boardZ + tol) {
                r.add("I4", String.format(Locale.ROOT, "'%s' surface Z %.3f..%.3f is outside the board top Z %.3f "
                        + "+/- %.2f (the board location's Z of the plan): surface points are N1 contact heights "
                        + "(OpenPnP frame); a height taken with the needle is about 2.59 mm off", name, pl.minZ,
                        pl.maxZ, boardZ, tol));
            }
        }
        else if (pl.minZ < a.surfaceZMm - tol || pl.maxZ > a.surfaceZMm + tol) {
            r.add("I4", String.format(Locale.ROOT, "'%s' surface Z %.3f..%.3f is outside the needle-frame surface "
                    + "%.3f +/- %.2f: surface points are N1 contact heights (OpenPnP frame)", name, pl.minZ,
                    pl.maxZ, a.surfaceZMm, tol));
        }
        int outside = 0;
        double worst = 0;
        for (double[] xy : targets) {
            double d = pl.distanceOutside(xy[0], xy[1]);
            if (d > cfg.surfaceMarginMm) {
                outside++;
                worst = Math.max(worst, d);
            }
        }
        if (outside > 0) {
            r.add("I4", String.format(Locale.ROOT, "%d dot(s) lie outside the probed '%s' surface (up to %.1f mm "
                    + "beyond its points, margin %.1f mm): probe points around them", outside, name, worst,
                    cfg.surfaceMarginMm));
        }
        if (checkPlan && in.plan != null) {
            if (!pl.fingerprint().equals(in.plan.surfaceFingerprint)) {
                r.add("I4", "the plan was made " + (in.plan.surfaceFingerprint == null ? "without the probed '"
                        : "on another '") + name + "' surface: plan the board again");
            }
            // F3: re-probe whenever the board is re-seated (one seating was tilted 0.9 mm). The surface
            // must be tied to the board registration it was probed on, else a re-seat goes unnoticed.
            if (m.boardFingerprint == null) {
                r.add("I4", "the '" + name + "' surface is not tied to a board registration: save the probe points "
                        + "with this plan (Surfaces card) so a re-seated board is noticed");
            }
            else if (!m.boardFingerprint.equals(in.plan.boardRegistration)) {
                r.add("I4", "the '" + name + "' surface was probed on another registration of the board (moved, "
                        + "re-seated or fiducials re-checked since): probe it again");
            }
        }
    }

    /**
     * CONFIG and MACHINE only: the tasks that never dispense (Register board, the blank probe, the Z
     * check). I4/I5/I7/I9-I11 are about dispensing.
     */
    public static InterlockReport checkBasic(Inputs in) {
        InterlockReport r = new InterlockReport();
        checkConfigAndMachine(in, r);
        return r;
    }

    private static void checkConfigAndMachine(Inputs in, InterlockReport r) {
        for (String problem : in.cfg.validate()) {
            r.add("CONFIG", problem);
        }
        // Homing needs a started machine, and nothing presses Start for the operator.
        if (!in.machineEnabled) {
            r.add("MACHINE", "machine is not enabled");
        }
        if (!in.machineHomed) {
            if (in.homeFirst) {
                r.notes.add("MACHINE: not homed: homes first");
            }
            else {
                r.add("MACHINE", "machine is not homed");
            }
        }
    }

    /** I9 refusal: a plan of several doses names the spots each switch takes. */
    private static String i9Text(Inputs in, List<DoseBlocks.Run> blocks, int left) {
        DispenseConfig cfg = in.cfg;
        int flush = cfg.padCalibration.flushDots;
        if (blocks.size() <= 1) {
            return String.format(Locale.ROOT, "only %d unused purge spot(s) left, %d needed: wipe the purge row and "
                    + "reset it (or set a clean new row and reset it)", left, in.purgeDots);
        }
        List<String> per = new java.util.ArrayList<>();
        for (int k = 1; k < blocks.size(); k++) {
            per.add(Integer.toString(DoseBlocks.switchSpots(blocks.get(k).netSteps, flush, cfg.bStepsPerRev)));
        }
        return String.format(Locale.ROOT, "only %d unused purge spot(s) left, %d needed (%d to start and %s for each "
                + "further dot size): wipe the purge row and reset it", left, in.purgeDots,
                DoseBlocks.initialPurge(blocks, cfg, flush), String.join(" + ", per));
    }

    /**
     * §6.5 PLAN refusals: a pad type whose dots the mounted tip cannot make (R1), or that gives a pad
     * less than half a screw turn in a row (R6). One line per pad type.
     */
    static void checkDoses(Inputs in, InterlockReport r) {
        DispenseConfig cfg = in.cfg;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (DispensePlan.PlannedPad p : in.plan.pads) {
            if (p.isBlocked() || p.dotCount <= 0 || p.dotSteps == null) {
                continue;
            }
            String who = p.typeKey == null ? p.padId : p.typeKey;
            int padSteps = p.dotSteps * p.dotCount;
            if (!DoseBlocks.isAllowed(p.dotSteps, cfg)) {
                if (seen.add(who + " dose")) {
                    r.add("PLAN", Texts.interlockDose(who, p.typeVersion, p.dotSteps, cfg.tip.name,
                            cfg.tip.minDotSteps));
                }
            }
            else if (padSteps * 2 < cfg.bStepsPerRev && seen.add(who + " R6")) {
                r.add("PLAN", Texts.interlockR6(who, p.typeVersion, padSteps));
            }
        }
    }

    /**
     * I6-feed (§1.2 R7): no block may ask the screw for more paste per second than it was seen to
     * keep up with (a screw asked for more pulls in air: 30-50 turns to recover).
     */
    static void checkFeed(Inputs in, InterlockReport r) {
        DispenseProfile prof = in.cfg.profile;
        List<double[]> demand = new java.util.ArrayList<>(); // {netSteps, mg/s}
        if (in.programBlocks != null) {
            for (GcodeProgram.BlockInfo b : in.programBlocks) {
                demand.add(new double[] {b.netSteps, b.dots > 0 ? b.demandMgS : 0});
            }
        }
        else if (in.plan != null) {
            for (DispensePlan.Block b : in.plan.blocks) {
                demand.add(new double[] {b.netSteps, b.demandMgS == null ? 0 : b.demandMgS});
            }
        }
        for (int k = 0; k < demand.size(); k++) {
            double x = demand.get(k)[1];
            if (x > prof.sustainedFeedMgS + 1e-9) {
                r.add("I6", String.format(Locale.ROOT, "block %d (%s) asks the screw for %.2f mg/s; the most it was "
                        + "seen to keep up with at %.1f psi is %.2f mg/s: lengthen the dwell or use more, smaller dots",
                        k + 1, DoseBlocks.turnName((int) demand.get(k)[0]), x, prof.pressurePsi,
                        prof.sustainedFeedMgS));
            }
        }
    }

    public static InterlockReport check(Inputs in) {
        InterlockReport r = new InterlockReport();
        DispenseConfig cfg = in.cfg;
        checkConfigAndMachine(in, r);
        // I3: the paste tool is not a vacuum nozzle; N2 must not be usable for pick/discard.
        if (in.compatibleNozzleTips != null && !in.compatibleNozzleTips.isEmpty()) {
            r.add("I3", in.nozzleName + " is configured as a pick nozzle with compatible nozzle tips "
                    + in.compatibleNozzleTips + "; remove them so pick/discard can never fire the paste pneumatics");
        }
        if (in.loadedNozzleTip != null) {
            r.add("I3", in.nozzleName + " has nozzle tip '" + in.loadedNozzleTip + "' loaded");
        }
        checkI4(in, r);
        // I5: barrel pressure window (disabled by default: no sensor fitted).
        if (cfg.pressureInterlockEnabled) {
            Double psi = null;
            try {
                psi = in.pressureSensor == null ? null : in.pressureSensor.readPsi();
            }
            catch (Exception e) {
                r.add("I5", "barrel pressure read failed: " + e.getMessage());
            }
            if (psi == null) {
                if (!r.has("I5")) {
                    r.add("I5", "pressure interlock enabled but no barrel pressure reading is available");
                }
            }
            else if (psi < cfg.pressureMinPsi || psi > cfg.pressureMaxPsi) {
                r.add("I5", String.format(java.util.Locale.ROOT, "barrel pressure %.2f psi outside [%.2f, %.2f]",
                        psi, cfg.pressureMinPsi, cfg.pressureMaxPsi));
            }
        }
        else {
            r.notes.add("I5: barrel pressure window disabled (no sensor)");
        }
        // I7: estimated continuous on-time.
        if (in.estimatedPressurisedSeconds > cfg.maxContinuousOnTimeS) {
            r.add("I7", String.format(java.util.Locale.ROOT,
                    "estimated pressurised time %.0f s exceeds the %.0f s on-time limit",
                    in.estimatedPressurisedSeconds, cfg.maxContinuousOnTimeS));
        }
        DispenseProfile prof = cfg.profile;
        // I6: the regulator is set by hand; the profile says what it must read.
        r.notes.add(String.format(Locale.ROOT, "I6: set the regulator to %.1f psi (profile '%s'); the window is "
                + "narrow: 16 psi starved the screw, 20 psi drooled", prof.pressurePsi, prof.name));
        if (prof.screwRpm > 180) {
            r.notes.add(String.format(Locale.ROOT, "I6: the screw runs at %.0f rpm, faster than the bench-proven 180: a "
                    + "screw faster than the paste feeds it pulls in air (30-50 turns to recover)", prof.screwRpm));
        }
        List<DoseBlocks.Run> blocks = in.plan == null ? java.util.Collections.emptyList()
                : DoseBlocks.blocksOf(in.plan, cfg);
        if (in.boardRun || in.purgeDots > 0) {
            // I9: purge row (same recipe as the work, then cross without stopping).
            DispenseConfig.PurgeRow row = cfg.purgeRow;
            if (in.purgeDots > 0) {
                if (!row.isDefined()) {
                    r.add("I9", "the purge row is not set (origin, pitch, spots on the blank)");
                }
                else if (row.spotsLeft() < in.purgeDots) {
                    r.add("I9", i9Text(in, blocks, row.spotsLeft()));
                }
            }
            else {
                r.notes.add("I9: no purge dots in the profile");
            }
        }
        if (in.boardRun) {
            // I10: clean-pad guard needs the top camera.
            if (cfg.cleanPadCheck.enabled && !in.opticalCheckAvailable) {
                r.add("I10", "clean-pad check enabled but no top camera / dot vision is available");
            }
            else if (!cfg.cleanPadCheck.enabled) {
                r.notes.add("I10: clean-pad check disabled in the configuration");
            }
        }
        // I11: the start angle of a partial turn is a calibration of the current controller power-up
        // (board runs and calibration blocks: any block of a partial turn).
        boolean partial = in.partialTurn != null ? in.partialTurn : in.boardRun && DoseBlocks.anyPartial(blocks, cfg);
        if (partial) {
            if (prof.startAngleSetAtMs == null) {
                r.add("I11", "the screw start angle has never been set: set or confirm it (Profiles)");
            }
            else if (in.controllerConnectedAtMs != null && prof.startAngleSetAtMs < in.controllerConnectedAtMs) {
                r.add("I11", "the controller (re)connected after the screw start angle was set; the B step count "
                        + "restarts at power-up, so the angle may be lost: confirm it if the controller was not "
                        + "power-cycled, otherwise measure it again (Profiles)");
            }
        }
        checkFeed(in, r);
        // Plan rules (§5.6/§5.7).
        if (in.plan != null) {
            if (in.plan.dots.isEmpty()) {
                r.add("PLAN", "plan has no dots");
            }
            checkDoses(in, r);
            List<DispensePlan.PlannedPad> blocked = in.plan.blockedPads();
            if (!blocked.isEmpty() && cfg.refuseFlaggedPads) {
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (DispensePlan.PlannedPad p : blocked) {
                    if (shown++ < 8) {
                        sb.append(p.padId).append(" (").append(p.typeKey == null ? p.packageId : p.typeKey)
                                .append(": ").append(p.flagNames()).append(") ");
                    }
                }
                r.add("PLAN", blocked.size() + " pad(s) not dispensable: " + sb.toString().trim()
                        + (blocked.size() > 8 ? " ..." : ""));
            }
            else if (!blocked.isEmpty()) {
                r.notes.add(blocked.size() + " flagged pad(s) will be skipped");
            }
            checkPadTypesSincePlanning(in, r);
        }
        return r;
    }
}
