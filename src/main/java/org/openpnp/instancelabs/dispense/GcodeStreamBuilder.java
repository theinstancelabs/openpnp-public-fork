package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the raw G-code block for one pressure session (plan §4.5, bench recipe 2026-10-28):
 * session start (G92 B0 unless B is already zeroed since home/reconnect, M201/M203 for B, optional
 * host-watchdog arm), pump ON -> wait -> valve ON -> settle, the purge row (same recipe as the
 * work; the last purge dot leaves the screw at the profile's start angle), a cross to the work at
 * travel Z with no stop, then per dot: XY at the current (hop) height, Z down to the gap,
 * {@code M204 S<screw accel>}, B forward (net + suck-back), dwell, B back (suck-back),
 * {@code M204 S<XY/Z accel>}, Z up. Session end: M400, valve OFF, pump OFF, re-zero B (aborts:
 * the Stop sequence, see {@link #abortLines}).
 *
 * <p>Dose blocks (profile calibration design §1.2 R2/R3, §6.2): the dots of one net turn are laid
 * together. Before every block after the first the needle goes back to the purge row: for a partial
 * turn a phase dot that leaves the screw, after the flush dots, at the start angle, then the flush
 * dots of the new dose (they carry the old dose's paste out of the screw), then the cross to the
 * block. The purge row lies on the blank, so its dots keep the needle-frame floor; the work dots take
 * their own surface's.</p>
 *
 * <p>All XYZ coordinates go through {@link RawMapper} (OpenPnP's transform chain) and
 * {@link BacklashEmulator}. B targets are whole motor steps from the session's G92 B0 (Marlin
 * rounds each absolute target to steps): every dot, the first included, turns forward net +
 * suck-back and back by the suck-back, so the screw rests exactly net steps further (800 for half
 * a revolution) and the start angle repeats.</p>
 */
public class GcodeStreamBuilder {
    /** One dot to dispense, in N2 machine coordinates. */
    public static final class DotSpec {
        public final double x, y, dispenseZ, hopZ, rotationDeg;
        public final int index;
        public final String label;
        /** Index of the dot's pad in its plan (the validator keeps a pad's dots together), -1 if none. */
        public final int padIndex;

        public DotSpec(double x, double y, double dispenseZ, double hopZ, double rotationDeg, int index,
                String label) {
            this(x, y, dispenseZ, hopZ, rotationDeg, index, label, -1);
        }

        public DotSpec(double x, double y, double dispenseZ, double hopZ, double rotationDeg, int index,
                String label, int padIndex) {
            this.x = x;
            this.y = y;
            this.dispenseZ = dispenseZ;
            this.hopZ = hopZ;
            this.rotationDeg = rotationDeg;
            this.index = index;
            this.label = label;
            this.padIndex = padIndex;
        }

        public static DotSpec of(DispensePlan.PlannedDot d) {
            return new DotSpec(d.xMm, d.yMm, d.dispenseZMm, d.hopZMm, d.rotationDeg, d.index, d.padId);
        }
    }

    /**
     * Dots of one dose laid together (§1.2 R2): {@code netSteps} per dot, in the given order. A
     * board plan's blocks come from its dots ({@link #forPlan}); calibration builds its own
     * ({@link #forBlocks}).
     */
    public static final class BlockSpec {
        public final int netSteps;
        public final List<DotSpec> dots;
        public final String label;
        /**
         * Frame reference of these dots' surface for the needle floor (a coupon place's own Z), or
         * null: the program's.
         */
        public Double floorRefZ;

        public BlockSpec(int netSteps, List<DotSpec> dots, String label) {
            this.netSteps = netSteps;
            this.dots = dots;
            this.label = label;
        }
    }

    public static final int PRIME_INDEX = -1;
    public static final int TEST_DOT_INDEX = -2;
    public static final int PURGE_INDEX = -3;

    private final DispenseConfig cfg;
    private final RawMapper mapper;
    private final BacklashEmulator emu;
    private final GcodeProgram prog = new GcodeProgram();
    private final String[] letters;
    private final String lb;
    private double[] lastDriver;
    private double[] nozzle;
    private final DispenseProfile profile;
    private boolean bZeroed;
    /** B position (extrude-positive deg) that step 0 stands for (raw B = bExtrudeSign * position). */
    private double bOrigin;
    /** Current and resting B position in extrude-positive steps from bOrigin. */
    private long bSteps, restSteps;
    /** Marlin step count of B at the start of this program (M114 Count), or null if unknown. */
    private Long bCountAtStart;
    private boolean pumpOn, valveOn, armed;
    private final boolean keepalive;
    private double t;
    /** Estimated time when the last dot's XY move ended (per-dot times of board programs). */
    private double lastDotArriveT;
    private double pumpOnT = -1;
    private double accel = Double.NaN;
    /** M201 B sent at session start (caps the screw acceleration). */
    private double bAccelLimit = Double.NaN;
    /** Frame reference of the dots' surface when it is not the needle frame (board top Z), or null. */
    private Double floorReferenceZ;
    private GcodeProgram.Marker pending;
    private int pendingIndex;
    private String pendingPad;
    private double pendingRot;
    private int pendingNetSteps;
    private int pendingPadIndex;

    /**
     * Where the mounted tip lands and what its screw feeds (tip setups design §22.2-22.3): every dispensed target is
     * moved by minus (landing + trim), and R7's feed check counts with the setup's paste per step.
     */
    public static final class TipXy {
        /** Every dispensed target is moved by minus this (landing + trim, mm). */
        public double dxMm, dyMm;
        /** R7: the paste per motor step the feed check counts with (mg); null = the bench model's. */
        public Double feedMgPerStep;

        /** The configuration's: its landing (when it has one) plus its trim, the bench model's feed. */
        public static TipXy of(DispenseConfig c) {
            TipXy x = new TipXy();
            x.dxMm = c.needleXyTrim.dxMm + (c.needleLanding == null ? 0 : c.needleLanding.dxMm);
            x.dyMm = c.needleXyTrim.dyMm + (c.needleLanding == null ? 0 : c.needleLanding.dyMm);
            return x;
        }
    }

    /** Where targets are moved and the feed check's paste per step (the configuration's unless a factory got one). */
    private TipXy xy;

    public GcodeStreamBuilder(DispenseConfig cfg, RawMapper mapper, boolean bZeroedAtStart, boolean keepalive) {
        this.cfg = cfg;
        this.xy = TipXy.of(cfg);
        this.mapper = mapper;
        this.keepalive = keepalive;
        double[] planned = mapper.currentPlanned();
        double[] driver = mapper.currentDriver();
        double[] off = new double[3];
        for (int i = 0; i < 3; i++) {
            off[i] = driver[i] - planned[i];
        }
        this.emu = new BacklashEmulator(mapper.backlash(), planned, off, cfg.honorBacklash);
        this.lastDriver = driver.clone();
        this.nozzle = mapper.currentNozzleLocation().clone();
        this.letters = new String[] {mapper.letterX(), mapper.letterY(), mapper.letterZ()};
        this.lb = mapper.letterB();
        this.profile = cfg.profile;
        this.bZeroed = bZeroedAtStart;
        this.bOrigin = bZeroedAtStart ? cfg.bExtrudeSign * mapper.currentB() : Double.NaN;
        prog.bZeroedAtStart = bZeroedAtStart;
        prog.bStartSteps = bZeroedAtStart ? Math.round(bOrigin * cfg.bStepsPerDeg) : 0;
        prog.keepalive = keepalive;
        checkTravelZ();
    }

    /**
     * Review finding: on the see-saw, N2 "higher" pushes N1 lower and vice versa, so a travel Z
     * outside the Z axis' safe zone can drive the needle (travel Z low) or N1 (travel Z high) into
     * the work. The travel Z must map into the raw safe zone (when the axis has one).
     */
    private void checkTravelZ() {
        double[] zone = mapper.zSafeZoneRaw();
        if (zone == null) {
            return;
        }
        double raw;
        try {
            raw = mapper.toRaw(nozzle[0], nozzle[1], cfg.travelSafeZMm).z;
        }
        catch (Exception e) {
            throw new IllegalStateException("travelSafeZMm " + cfg.travelSafeZMm + " cannot be mapped: " + e.getMessage(), e);
        }
        if ((!Double.isNaN(zone[0]) && raw < zone[0] - 1e-6) || (!Double.isNaN(zone[1]) && raw > zone[1] + 1e-6)) {
            throw new IllegalStateException(String.format(Locale.ROOT, "travelSafeZMm %.3f maps to raw Z %.3f, outside "
                    + "the Z axis safe zone %s..%s: on the see-saw that puts a nozzle into the work",
                    cfg.travelSafeZMm, raw, Double.isNaN(zone[0]) ? "-" : f4(zone[0]),
                    Double.isNaN(zone[1]) ? "-" : f4(zone[1])));
        }
    }

    /**
     * Per-dot Z rules: dispense Z above the I4 needle floor, the lift above the dispense Z and not
     * above the travel Z (a hop above travel Z would push N1 below its safe zone on the see-saw).
     */
    private void checkDotZ(DotSpec d) {
        double floor = cfg.needleFloorZMm(floorReferenceZ);
        if (!Double.isNaN(floor) && d.dispenseZ < floor - 1e-9) {
            throw new IllegalStateException(String.format(Locale.ROOT, "I4: dot %d (%s) at N2 Z %.3f is below the needle "
                    + "floor %.3f (%s - tolerance + offset)", d.index, d.label, d.dispenseZ, floor,
                    floorReferenceZ == null ? "needle-frame surface"
                            : "the lower of the needle-frame surface and the board top Z"));
        }
        if (!(d.hopZ > d.dispenseZ)) {
            throw new IllegalStateException(String.format(Locale.ROOT, "dot %d (%s): lift Z %.3f is not above the "
                    + "dispense Z %.3f", d.index, d.label, d.hopZ, d.dispenseZ));
        }
        if (d.hopZ > cfg.travelSafeZMm + 1e-6) {
            throw new IllegalStateException(String.format(Locale.ROOT, "dot %d (%s): lift Z %.3f is above the travel Z "
                    + "%.3f (hopHeightMm %.3f too large): on the see-saw that pushes N1 down", d.index, d.label, d.hopZ,
                    cfg.travelSafeZMm, cfg.hopHeightMm));
        }
    }

    // ------------------------------------------------------------------------------------------
    // Composite programs
    // ------------------------------------------------------------------------------------------

    /**
     * Program for a board plan: one pressure session with the purge row first (same recipe as the
     * work), then a cross to the first dot at travel Z with no M400 or dwell (any pause with the
     * pressure on lets paste ooze onto the tip), then every dot. A plan with dots of several doses
     * lays them block by block (consecutive dots of one dose), with a switch on the purge row
     * before every block after the first.
     *
     * @param purge purge spots (dot specs at the purge row; their rotation is replaced by the
     *        block's dose), possibly empty: the initial purge, then the switch spots of every
     *        further block ({@link DoseBlocks#purgeSpotsNeeded}; refused if there are fewer)
     * @param bCount Marlin step count of B at the start of the program (M114 Count), or null if the
     *        controller did not report it: then the start angle cannot be set (refused when a
     *        block is a partial turn)
     */
    public static GcodeProgram forPlan(DispenseConfig cfg, RawMapper mapper, DispensePlan plan,
            List<DotSpec> purge, boolean bZeroed, boolean keepalive, Long bCount) throws Exception {
        return forPlan(cfg, mapper, plan, purge, bZeroed, keepalive, bCount, null);
    }

    /** As above with the mounted tip's landing and feed ({@code xy} null: the configuration's). */
    public static GcodeProgram forPlan(DispenseConfig cfg, RawMapper mapper, DispensePlan plan,
            List<DotSpec> purge, boolean bZeroed, boolean keepalive, Long bCount, TipXy xy) throws Exception {
        Map<String, Integer> padIndex = new HashMap<>();
        for (int i = 0; i < plan.pads.size(); i++) {
            padIndex.putIfAbsent(plan.pads.get(i).padId, i);
        }
        int[] doses = DoseBlocks.dosesOf(plan, cfg.profile.netStepsPerDot);
        List<BlockSpec> blocks = new ArrayList<>();
        for (DoseBlocks.Run r : DoseBlocks.blocksOf(doses)) {
            List<DotSpec> dots = new ArrayList<>();
            for (int i = r.first; i < r.first + r.count; i++) {
                DispensePlan.PlannedDot d = plan.dots.get(i);
                dots.add(new DotSpec(d.xMm, d.yMm, d.dispenseZMm, d.hopZMm, d.rotationDeg, d.index, d.padId,
                        padIndexOf(plan, d, padIndex)));
            }
            blocks.add(new BlockSpec(r.netSteps, dots, DoseBlocks.turnName(r.netSteps)));
        }
        GcodeStreamBuilder b = new GcodeStreamBuilder(cfg, mapper, bZeroed, keepalive);
        b.xy = xy != null ? xy : b.xy;
        b.bCountAtStart = bCount;
        b.prog.floorReferenceZMm = plan.boardZMm;
        b.comment("; instancelabs dispense plan " + plan.planId + " board " + plan.boardName + " profile '"
                + cfg.profile.name + "' " + cfg.profile.fingerprint());
        // the purge dots lie on the blank (the anchor's floor); only the board dots take the board's reference
        b.blocks(blocks, purge, cfg.padCalibration.flushDots, plan.boardZMm);
        return b.build();
    }

    /**
     * The dot's pad in the plan: the planner's {@link DispensePlan.PlannedDot#padIndex} (two pads of one
     * footprint may share a name, and so a pad id), else the first pad of its id (a plan made without it).
     */
    private static int padIndexOf(DispensePlan plan, DispensePlan.PlannedDot d, Map<String, Integer> byId) {
        int i = d.padIndex;
        if (i >= 0 && i < plan.pads.size() && java.util.Objects.equals(plan.pads.get(i).padId, d.padId)) {
            return i;
        }
        return byId.getOrDefault(d.padId, -1);
    }

    /**
     * Program for dose blocks of calibration dots (the pad ladder and the weigh dots): the same
     * session, purge, switches and phase rules as a board plan, every block in its given order.
     *
     * @param purgeSpots the initial purge and the switch spots ({@link DoseBlocks#purgeSpotsNeeded})
     * @param flush flush dots of every switch (R3; {@link PadCalibrationSettings#flushDots})
     * @param floorRefZ frame reference of the dots' surface for the needle floor (null: the needle
     *        frame); a block's own {@link BlockSpec#floorRefZ} replaces it for that block
     */
    public static GcodeProgram forBlocks(DispenseConfig cfg, RawMapper mapper, List<BlockSpec> blocks,
            List<DotSpec> purgeSpots, int flush, boolean bZeroed, boolean keepalive, Long bCount, Double floorRefZ)
            throws Exception {
        return forBlocks(cfg, mapper, blocks, purgeSpots, flush, bZeroed, keepalive, bCount, floorRefZ, null);
    }

    /** As above with the mounted tip's landing and feed ({@code xy} null: the configuration's). */
    public static GcodeProgram forBlocks(DispenseConfig cfg, RawMapper mapper, List<BlockSpec> blocks,
            List<DotSpec> purgeSpots, int flush, boolean bZeroed, boolean keepalive, Long bCount, Double floorRefZ,
            TipXy xy) throws Exception {
        GcodeStreamBuilder b = new GcodeStreamBuilder(cfg, mapper, bZeroed, keepalive);
        b.xy = xy != null ? xy : b.xy;
        b.bCountAtStart = bCount;
        Double lowest = floorRefZ;
        List<String> labels = new ArrayList<>();
        for (BlockSpec bs : blocks) {
            Double ref = bs.floorRefZ != null ? bs.floorRefZ : floorRefZ;
            if (ref != null && (lowest == null || ref < lowest)) {
                lowest = ref;
            }
            labels.add(bs.label + " x" + bs.dots.size());
        }
        // one Z window for the validator: the floor of the lowest reference the program uses
        b.prog.floorReferenceZMm = lowest;
        b.comment("; instancelabs dispense blocks: " + String.join(", ", labels) + " profile '" + cfg.profile.name
                + "' " + cfg.profile.fingerprint());
        b.blocks(blocks, purgeSpots, flush, floorRefZ);
        return b.build();
    }

    /**
     * The body of a block program: session start, pressure, the initial purge (its first dot sets
     * the phase when the step count is known), every block (a switch on the purge row before each
     * after the first), the per-dot times and counts, session end.
     */
    private void blocks(List<BlockSpec> blocks, List<DotSpec> purge, int flush, Double floorRef) throws Exception {
        int rev = cfg.bStepsPerRev;
        List<DoseBlocks.Run> runs = new ArrayList<>();
        int total = 0;
        for (BlockSpec bs : blocks) {
            runs.add(new DoseBlocks.Run(bs.netSteps, total, bs.dots.size()));
            total += bs.dots.size();
        }
        int switches = 0;
        for (int k = 1; k < blocks.size(); k++) {
            switches += DoseBlocks.switchSpots(blocks.get(k).netSteps, flush, rev);
        }
        if (blocks.size() > 1 && purge.size() < DoseBlocks.initialPurge(runs, cfg, flush) + switches) {
            // the spots the row has free: the caller passes none at all when they are too few (rc1 review: "gives 0")
            int need = DoseBlocks.initialPurge(runs, cfg, flush) + switches;
            int free = !purge.isEmpty() ? purge.size() : cfg.purgeRow.isDefined() ? cfg.purgeRow.spotsLeft() : 0;
            throw new IllegalStateException(String.format(Locale.ROOT, "this program lays %d dot sizes: it needs %d "
                    + "purge spots (%d to start and %d for the block switches), the purge row has %d free: %s",
                    blocks.size(), need, DoseBlocks.initialPurge(runs, cfg, flush), switches, free,
                    cfg.purgeRow.spots < need ? "give the row more spots" : "wipe the purge row and reset it"));
        }
        boolean partial = DoseBlocks.anyPartial(runs, cfg);
        if (partial && bCountAtStart == null && (!purge.isEmpty() || blocks.size() > 1)) {
            throw new IllegalStateException("the controller did not report the B step count (M114 Count): the "
                    + "screw start angle of a partial-turn dose cannot be set");
        }
        DotSpec first = blocks.isEmpty() || blocks.get(0).dots.isEmpty() ? null : blocks.get(0).dots.get(0);
        sessionStart();
        if (!purge.isEmpty()) {
            travelTo(purge.get(0).x, purge.get(0).y);
        }
        else if (first != null) {
            travelTo(first.x, first.y);
        }
        pressurize();
        int initial = purge.size() - switches;
        purgeDots(purge.subList(0, initial), blocks.isEmpty() ? profile.netStepsPerDot : blocks.get(0).netSteps,
                bCountAtStart != null, null);
        int spot = initial;
        double workT0 = t;
        double[] arrive = new double[total];
        double[] end = new double[total];
        long[] counts = bCountAtStart == null ? null : new long[total];
        double mgPerStep = xy.feedMgPerStep != null ? xy.feedMgPerStep : DoseBlocks.modelMgPerStep(cfg);
        prog.blocks = new ArrayList<>();
        int w = 0;
        for (int k = 0; k < blocks.size(); k++) {
            BlockSpec bs = blocks.get(k);
            GcodeProgram.BlockInfo bi = new GcodeProgram.BlockInfo();
            bi.netSteps = bs.netSteps;
            bi.firstDot = w;
            bi.dots = bs.dots.size();
            if (k > 0) {
                boolean phase = DoseBlocks.isPartial(bs.netSteps, rev);
                int n = DoseBlocks.switchSpots(bs.netSteps, flush, rev);
                comment(String.format(Locale.ROOT, "; block %d (%s, %d dots): purge row, %s%d flush dots", k + 1,
                        DoseBlocks.turnName(bs.netSteps), bs.dots.size(), phase ? "phase dot and " : "", flush));
                floorReferenceZ = null; // the purge row lies on the blank: the needle-frame anchor's floor
                List<DotSpec> sw = purge.subList(spot, spot + n);
                spot += n;
                travelTo(sw.get(0).x, sw.get(0).y);
                purgeDots(sw, bs.netSteps, phase, k + 1);
            }
            double blockT0 = t;
            floorReferenceZ = bs.floorRefZ != null ? bs.floorRefZ : floorRef;
            for (int i = 0; i < bs.dots.size(); i++) {
                DotSpec d = bs.dots.get(i);
                if (i == 0) {
                    travelTo(d.x, d.y);
                    if (k == 0) {
                        markWorkStart();
                    }
                    if (bCountAtStart != null) {
                        bi.restCountAtStart = restCount();
                        bi.startAngleDeg = DispenseProfile.angleOfCount(restCount(), rev);
                    }
                }
                if (counts != null) {
                    counts[w] = restCount();
                }
                // whole steps of the block's dose (not the plan's degrees: a changed bStepsPerDeg would drift)
                dot(d, bs.netSteps);
                arrive[w] = lastDotArriveT;
                end[w] = t;
                w++;
            }
            if (bi.dots > 0) {
                bi.seconds = t - blockT0;
                bi.switchSeconds = arrive[bi.firstDot] - (k == 0 ? workT0 : end[bi.firstDot - 1]);
                bi.demandMgS = DoseBlocks.demandMgS(bi.dots, bs.netSteps, mgPerStep, bi.seconds);
            }
            prog.blocks.add(bi);
        }
        prog.dotArriveSeconds = arrive;
        prog.dotEndSeconds = end;
        prog.dotStartCount = counts;
        // From the cross after the purge to the last dot's lift: what the bench's DPH counts.
        prog.estimatedWorkSeconds = t - workT0;
        endSession();
    }

    /**
     * Program for calibration dots (dot ladder, touch-off, shots): optional prime at the waste
     * location, then the dots in the given order.
     */
    public static GcodeProgram forDots(DispenseConfig cfg, RawMapper mapper, List<DotSpec> dots,
            boolean bZeroed, boolean keepalive, boolean primeAtWaste, String title) throws Exception {
        return forDots(cfg, mapper, dots, bZeroed, keepalive, primeAtWaste, 0, title);
    }

    /** As above; the prime at the waste pad is {@code primeExtraMm} higher than a normal dot. */
    public static GcodeProgram forDots(DispenseConfig cfg, RawMapper mapper, List<DotSpec> dots,
            boolean bZeroed, boolean keepalive, boolean primeAtWaste, double primeExtraMm, String title)
            throws Exception {
        return forDots(cfg, mapper, dots, bZeroed, keepalive, primeAtWaste, primeExtraMm, title, null);
    }

    /** As above with the mounted tip's landing ({@code xy} null: the configuration's). */
    public static GcodeProgram forDots(DispenseConfig cfg, RawMapper mapper, List<DotSpec> dots,
            boolean bZeroed, boolean keepalive, boolean primeAtWaste, double primeExtraMm, String title, TipXy xy)
            throws Exception {
        GcodeStreamBuilder b = new GcodeStreamBuilder(cfg, mapper, bZeroed, keepalive);
        b.xy = xy != null ? xy : b.xy;
        b.primeExtraClearanceMm = primeExtraMm;
        b.comment("; instancelabs dispense " + title);
        b.sessionStart();
        DispenseConfig.NamedLocation waste = cfg.location(cfg.wasteLocationName);
        if (primeAtWaste) {
            if (waste == null) {
                throw new Exception("waste location '" + cfg.wasteLocationName + "' is not set");
            }
            b.travelTo(waste.x, waste.y);
        }
        else if (!dots.isEmpty()) {
            b.travelTo(dots.get(0).x, dots.get(0).y);
        }
        b.pressurize();
        if (primeAtWaste) {
            b.dot(b.atLocation(waste, 0, 0, cfg.primeRotationDeg, PRIME_INDEX, "prime"));
        }
        for (int i = 0; i < dots.size(); i++) {
            if (i == 0) {
                b.travelTo(dots.get(0).x, dots.get(0).y);
            }
            b.dot(dots.get(i));
        }
        b.endSession();
        return b.build();
    }

    /**
     * The fixed abort / error sequence: the Stop sequence (M410, valve OFF, pump OFF, left valve and
     * pump OFF, M18 B), then the watchdog disarm if it was armed.
     */
    public static List<String> abortLines(DispenseConfig cfg, boolean keepaliveArmed, String letterB) {
        List<String> l = new ArrayList<>(ControllerCommands.stopSequence(letterB));
        if (keepaliveArmed) {
            l.add(ControllerCommands.KEEPALIVE_DISARM);
        }
        return l;
    }

    /** Dot at a named location (+ offset) with the configured gap; lifts to travel Z after. */
    public DotSpec atLocation(DispenseConfig.NamedLocation loc, double dx, double dy, double rot, int index,
            String label) {
        double z = loc.z + cfg.needleZOffsetMm + cfg.gapMm() + primeExtraClearanceMm;
        return new DotSpec(loc.x + dx, loc.y + dy, z, cfg.travelSafeZMm, rot, index, label);
    }

    /** Extra height for the prime at the waste pad (needle not touched off yet). */
    private double primeExtraClearanceMm;

    // ------------------------------------------------------------------------------------------
    // Building blocks
    // ------------------------------------------------------------------------------------------

    public void comment(String c) {
        add(c.startsWith(";") ? c : "; " + c, GcodeProgram.Marker.NONE);
    }

    public void sessionStart() {
        comment("; session start");
        if (!bZeroed) {
            add("G92 " + lb + "0", GcodeProgram.Marker.B_ZERO);
            bZeroed = true;
            bOrigin = 0;
        }
        bSteps = 0;
        restSteps = 0;
        bAccelLimit = profile.screwAccelDegS2;
        add("M201 " + lb + f0(bAccelLimit), GcodeProgram.Marker.NONE);
        add("M203 " + lb + f0(cfg.bMaxFeedDegS), GcodeProgram.Marker.NONE);
        setAccel(cfg.travelAccelMmS2);
        if (keepalive) {
            add(ControllerCommands.keepaliveArm(cfg.keepaliveWindowMs), GcodeProgram.Marker.KEEPALIVE_ARM);
            armed = true;
        }
    }

    public void pressurize() {
        comment("; pressurize: pump ON -> wait -> valve ON -> settle");
        add(ControllerCommands.PUMP_ON, GcodeProgram.Marker.PUMP_ON);
        pumpOn = true;
        pumpOnT = t;
        dwell(cfg.pumpToValveSettleMs);
        add(ControllerCommands.VALVE_ON, GcodeProgram.Marker.VALVE_ON);
        valveOn = true;
        dwell(cfg.valveSettleMs);
    }

    /**
     * Lift to travel safe Z (if not there), then XY (a dispense target: where the needle lands applies, landing + trim).
     */
    public void travelTo(double x, double y) throws Exception {
        if (Math.abs(nozzle[2] - cfg.travelSafeZMm) > 1e-6) {
            moveNozzle(nozzle[0], nozzle[1], cfg.travelSafeZMm, cfg.zLiftFeedMmS, "G1");
        }
        moveNozzle(trimX(x), trimY(y), cfg.travelSafeZMm, cfg.travelFeedMmS, "G0");
    }

    /** One dot of its own rotation (rounded to whole steps). */
    public void dot(DotSpec d) throws Exception {
        dot(d, Math.round(d.rotationDeg * cfg.bStepsPerDeg));
    }

    /**
     * Purge-row dots of {@code steps} each (the purge row: the same recipe as the work). With
     * {@code phase} (the B step count known) the first dot turns the screw so that, after the others,
     * it rests at the profile's start angle for the next work dot (required for a partial turn: its
     * dose depends on the angle); the others flush the odd-sized first one and the previous dose.
     * {@code block}: the 1-based block a switch leads to, null for the initial purge.
     */
    private void purgeDots(List<DotSpec> spots, int steps, boolean phase, Integer block) throws Exception {
        for (int i = 0; i < spots.size(); i++) {
            DotSpec d = spots.get(i);
            long n = steps;
            if (i == 0 && phase) {
                n = profile.phaseSteps(cfg, restCount(), spots.size() - 1, steps);
                double from = DispenseProfile.angleOfCount(restCount(), cfg.bStepsPerRev);
                double to = DispenseProfile.angleOfCount(restCount() + cfg.bExtrudeSign * n, cfg.bStepsPerRev);
                double start = profile.startAngleSteps(cfg) * 360.0 / cfg.bStepsPerRev;
                comment(block == null
                        ? String.format(Locale.ROOT, "; first purge dot sets the phase: rest %.1f deg -> %.1f deg, the "
                                + "work starts at %.1f deg", from, to, start)
                        : String.format(Locale.ROOT, "; phase dot: rest %.1f deg -> %.1f deg, block %d starts at %.1f "
                                + "deg", from, to, block, start));
            }
            dot(new DotSpec(d.x, d.y, d.dispenseZ, d.hopZ, n / cfg.bStepsPerDeg, PURGE_INDEX, d.label), n);
        }
        prog.purgeDotCount += spots.size();
    }

    /** Records the screw state where the work starts (after the purge). */
    private void markWorkStart() {
        if (bCountAtStart != null) {
            prog.workStartCount = restCount();
            prog.workStartAngleDeg = DispenseProfile.angleOfCount(restCount(), cfg.bStepsPerRev);
        }
    }

    /** Marlin step count of B at rest (null-safe callers only). */
    private long restCount() {
        return bCountAtStart + cfg.bExtrudeSign * restSteps;
    }

    /**
     * One dot turning the screw by {@code netSteps}: forward net + suck-back under the screw
     * acceleration, dwell, back by the suck-back, XY/Z acceleration again (M204 S caps B as well),
     * lift.
     */
    public void dot(DotSpec d, long netSteps) throws Exception {
        if (!bZeroed) {
            throw new IllegalStateException("I2: no B move before B is zeroed");
        }
        if (!(pumpOn && valveOn)) {
            throw new IllegalStateException("dot outside a pressure session");
        }
        if (netSteps <= 0) {
            throw new IllegalStateException("dot " + d.index + " (" + d.label + "): the screw turn must be > 0");
        }
        checkDotZ(d);
        comment(String.format(Locale.ROOT, "; dot %d %s rot %.3f", d.index, d.label, d.rotationDeg));
        pending = GcodeProgram.Marker.DOT_BEGIN;
        pendingIndex = d.index;
        pendingPad = d.label;
        pendingRot = d.rotationDeg;
        pendingNetSteps = (int) netSteps;
        pendingPadIndex = d.padIndex;
        double x = trimX(d.x), y = trimY(d.y);
        moveNozzle(x, y, nozzle[2], cfg.travelFeedMmS, "G0");
        lastDotArriveT = t;
        moveNozzle(x, y, d.dispenseZ, cfg.zDownFeedMmS, "G1");
        long suck = profile.suckBackSteps(cfg);
        setAccel(profile.screwAccelDegS2);
        moveBSteps(restSteps + netSteps + suck, profile.feedDegS());
        dwell(profile.dwellMs);
        if (suck > 0) {
            moveBSteps(restSteps + netSteps, profile.feedDegS());
        }
        restSteps += netSteps;
        setAccel(cfg.zLiftAccelMmS2);
        moveNozzle(x, y, d.hopZ, cfg.zLiftFeedMmS, "G1");
        setAccel(cfg.travelAccelMmS2);
        markLast(GcodeProgram.Marker.DOT_END, d.index, d.label, d.rotationDeg);
        prog.dotCount += d.index >= 0 ? 1 : 0;
    }

    /** A dispense target moved by minus where the needle lands (landing + trim, §22.3). */
    private double trimX(double x) {
        return x - xy.dxMm;
    }

    private double trimY(double y) {
        return y - xy.dyMm;
    }

    public void endSession() throws Exception {
        // No M410 here: after M400 nothing is left to stop, and Marlin's quick-stop holds its "ok"
        // for about 1 s (planner cleaning_buffer_counter), which kept the valve open 1 s after the
        // last dot (review finding). The valve vents right after the last dot's lift has finished.
        comment("; session end: wait, valve OFF (vent), pump OFF");
        add("M400", GcodeProgram.Marker.SYNC);
        add(ControllerCommands.VALVE_OFF, GcodeProgram.Marker.VALVE_OFF);
        valveOn = false;
        add(ControllerCommands.PUMP_OFF, GcodeProgram.Marker.PUMP_OFF);
        pumpOn = false;
        if (pumpOnT >= 0) {
            prog.estimatedPressurisedSeconds += t - pumpOnT;
            pumpOnT = -1;
        }
        if (armed) {
            add(ControllerCommands.KEEPALIVE_DISARM, GcodeProgram.Marker.KEEPALIVE_DISARM);
            armed = false;
        }
        add("G92 " + lb + "0", GcodeProgram.Marker.B_ZERO);
        bOrigin = 0;
        bSteps = 0;
        restSteps = 0;
        if (Math.abs(nozzle[2] - cfg.travelSafeZMm) > 1e-6) {
            moveNozzle(nozzle[0], nozzle[1], cfg.travelSafeZMm, cfg.zLiftFeedMmS, "G1");
        }
        add("M400", GcodeProgram.Marker.SYNC);
    }

    public void dwell(int ms) {
        if (ms <= 0) {
            return;
        }
        int chunk = keepalive ? Math.max(100, cfg.keepaliveIntervalMs) : ms;
        int remaining = ms;
        while (remaining > 0) {
            int c = Math.min(chunk, remaining);
            add("G4 P" + c, GcodeProgram.Marker.NONE);
            t += c / 1000.0;
            remaining -= c;
        }
    }

    public GcodeProgram build() {
        if (pumpOn || valveOn) {
            throw new IllegalStateException("program would leave the pneumatics on");
        }
        prog.finalPlanned = emu.planned();
        prog.finalDriver = emu.driver();
        prog.finalB = cfg.bExtrudeSign * bPosDeg();
        prog.bCountAtStart = bCountAtStart;
        prog.finalNozzle = nozzle.clone();
        prog.estimatedSeconds = t;
        return prog;
    }

    // ------------------------------------------------------------------------------------------

    private void moveNozzle(double x, double y, double z, double feedMmS, String word) throws Exception {
        RawPoint r = mapper.toRaw(x, y, z);
        for (BacklashEmulator.Move m : emu.moveTo(new double[] {r.x, r.y, r.z})) {
            StringBuilder sb = new StringBuilder(word);
            boolean any = false;
            for (int i = 0; i < 3; i++) {
                if (!f4(m.driver[i]).equals(f4(lastDriver[i]))) {
                    sb.append(' ').append(letters[i]).append(f4(m.driver[i]));
                    any = true;
                }
            }
            if (!any) {
                continue;
            }
            double f = feedMmS * m.speedFactor;
            sb.append(" F").append(f0(f * 60));
            double dx = m.driver[0] - lastDriver[0], dy = m.driver[1] - lastDriver[1], dz = m.driver[2] - lastDriver[2];
            t += moveSeconds(Math.sqrt(dx * dx + dy * dy + dz * dz), f, accel);
            lastDriver = m.driver.clone();
            add(sb.toString(), GcodeProgram.Marker.NONE);
        }
        nozzle = new double[] {x, y, z};
    }

    /** Absolute B move to a whole number of extrude steps from the origin. */
    private void moveBSteps(long steps, double feedDegS) {
        double ext = bOrigin + steps / cfg.bStepsPerDeg;
        add("G1 " + lb + f4(cfg.bExtrudeSign * ext) + " F" + f0(feedDegS * 60), GcodeProgram.Marker.NONE);
        double a = Double.isNaN(bAccelLimit) ? accel : Math.min(accel, bAccelLimit);
        t += moveSeconds(Math.abs(steps - bSteps) / cfg.bStepsPerDeg, Math.min(feedDegS, cfg.bMaxFeedDegS), a);
        bSteps = steps;
    }

    /** Current B position, extrude-positive degrees. */
    private double bPosDeg() {
        return bOrigin + bSteps / cfg.bStepsPerDeg;
    }

    /**
     * Time of one move from rest to rest (trapezoid, or triangle if it never reaches the feed). The
     * moves of a dot are separated by stops anyway (Z down, the B turn, G4 drains the planner, lift),
     * so this is close to Marlin's; between dots it ignores look-ahead (a small overestimate).
     */
    static double moveSeconds(double dist, double feed, double accel) {
        if (dist <= 0) {
            return 0;
        }
        if (!(accel > 0) || Double.isInfinite(accel)) {
            return dist / feed;
        }
        double accelDist = feed * feed / accel;
        return dist >= accelDist ? dist / feed + feed / accel : 2 * Math.sqrt(dist / accel);
    }

    private void setAccel(double a) {
        if (Double.isNaN(accel) || Math.abs(accel - a) > 1e-9) {
            add("M204 S" + f0(a), GcodeProgram.Marker.NONE);
            accel = a;
        }
    }

    private void add(String text, GcodeProgram.Marker m) {
        GcodeProgram.Marker marker = m;
        int idx = -9;
        String pad = null;
        double rot = 0;
        int net = 0;
        int padIdx = -1;
        List<GcodeProgram.Marker> extra = null;
        if (pending != null && !text.startsWith(";")) {
            if (marker != GcodeProgram.Marker.NONE) {
                extra = new ArrayList<>();
                extra.add(marker);
            }
            marker = pending;
            idx = pendingIndex;
            pad = pendingPad;
            rot = pendingRot;
            net = pendingNetSteps;
            padIdx = pendingPadIndex;
            pending = null;
        }
        prog.lines.add(new GcodeProgram.Line(text, marker, idx, pad, rot, extra, net, padIdx));
    }

    private void markLast(GcodeProgram.Marker m, int index, String pad, double rot) {
        for (int i = prog.lines.size() - 1; i >= 0; i--) {
            GcodeProgram.Line l = prog.lines.get(i);
            if (l.isComment()) {
                continue;
            }
            List<GcodeProgram.Marker> extra = new ArrayList<>(l.extra);
            GcodeProgram.Marker primary = l.marker;
            if (primary == GcodeProgram.Marker.NONE) {
                primary = m;
            }
            else {
                extra.add(m);
            }
            prog.lines.set(i, new GcodeProgram.Line(l.text, primary, index, pad, rot, extra, l.netSteps, l.padIndex));
            return;
        }
    }

    static String f4(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        return s.equals("-0.0000") ? "0.0000" : s;
    }

    static String f0(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }
}
