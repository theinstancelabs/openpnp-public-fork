package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

        public DotSpec(double x, double y, double dispenseZ, double hopZ, double rotationDeg, int index,
                String label) {
            this.x = x;
            this.y = y;
            this.dispenseZ = dispenseZ;
            this.hopZ = hopZ;
            this.rotationDeg = rotationDeg;
            this.index = index;
            this.label = label;
        }

        public static DotSpec of(DispensePlan.PlannedDot d) {
            return new DotSpec(d.xMm, d.yMm, d.dispenseZMm, d.hopZMm, d.rotationDeg, d.index, d.padId);
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
    private double pumpOnT = -1;
    private double accel = Double.NaN;
    /** M201 B sent at session start (caps the screw acceleration). */
    private double bAccelLimit = Double.NaN;
    private GcodeProgram.Marker pending;
    private int pendingIndex;
    private String pendingPad;
    private double pendingRot;

    public GcodeStreamBuilder(DispenseConfig cfg, RawMapper mapper, boolean bZeroedAtStart, boolean keepalive) {
        this.cfg = cfg;
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
        double floor = cfg.needleFloorZMm();
        if (!Double.isNaN(floor) && d.dispenseZ < floor - 1e-9) {
            throw new IllegalStateException(String.format(Locale.ROOT, "I4: dot %d (%s) at N2 Z %.3f is below the needle "
                    + "floor %.3f (needle-frame surface - tolerance + offset)", d.index, d.label, d.dispenseZ, floor));
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
     * pressure on lets paste ooze onto the tip), then every dot.
     *
     * @param purge purge spots (dot specs at the purge row; their rotation is replaced by the
     *        profile's), possibly empty
     * @param bCount Marlin step count of B at the start of the program (M114 Count), or null if the
     *        controller did not report it: then the start angle cannot be set (refused for a
     *        partial-turn profile)
     */
    public static GcodeProgram forPlan(DispenseConfig cfg, RawMapper mapper, DispensePlan plan,
            List<DotSpec> purge, boolean bZeroed, boolean keepalive, Long bCount) throws Exception {
        List<DotSpec> dots = new ArrayList<>();
        for (DispensePlan.PlannedDot d : plan.dots) {
            dots.add(DotSpec.of(d));
        }
        GcodeStreamBuilder b = new GcodeStreamBuilder(cfg, mapper, bZeroed, keepalive);
        b.bCountAtStart = bCount;
        b.comment("; instancelabs dispense plan " + plan.planId + " board " + plan.boardName + " profile '"
                + cfg.profile.name + "' " + cfg.profile.fingerprint());
        b.sessionStart();
        if (!purge.isEmpty()) {
            b.travelTo(purge.get(0).x, purge.get(0).y);
        }
        else if (!dots.isEmpty()) {
            b.travelTo(dots.get(0).x, dots.get(0).y);
        }
        b.pressurize();
        b.purge(purge);
        double workT0 = b.t;
        for (int i = 0; i < dots.size(); i++) {
            if (i == 0) {
                b.travelTo(dots.get(0).x, dots.get(0).y);
                b.markWorkStart();
            }
            // whole steps from the profile (not the plan's degrees: a changed bStepsPerDeg would drift)
            b.dot(dots.get(i), cfg.profile.netStepsPerDot);
        }
        // From the cross after the purge to the last dot's lift: what the bench's DPH counts.
        b.prog.estimatedWorkSeconds = b.t - workT0;
        b.endSession();
        return b.build();
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
        GcodeStreamBuilder b = new GcodeStreamBuilder(cfg, mapper, bZeroed, keepalive);
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
     * Lift to travel safe Z (if not there), then XY (a dispense target: the needle XY trim applies).
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
     * Purge row: one profile dot per spot. If the B step count is known, the first purge dot turns
     * the screw so that, after the other purge dots, it rests at the profile's start angle for the
     * first work dot (required for a partial turn: its dose depends on the angle). The other purge
     * dots flush the odd-sized first one.
     */
    public void purge(List<DotSpec> spots) throws Exception {
        long net = profile.netStepsPerDot;
        if (spots.isEmpty()) {
            return; // a partial-turn profile always has purge dots (DispenseProfile.validate, I9)
        }
        if (profile.isPartialTurn(cfg) && bCountAtStart == null) {
            throw new IllegalStateException("the controller did not report the B step count (M114 Count): the "
                    + "screw start angle of a partial-turn profile cannot be set");
        }
        for (int i = 0; i < spots.size(); i++) {
            DotSpec d = spots.get(i);
            long steps = net;
            if (i == 0 && bCountAtStart != null) {
                steps = profile.phaseSteps(cfg, restCount(), spots.size() - 1);
                comment(String.format(Locale.ROOT, "; first purge dot sets the phase: rest %.1f deg -> %.1f deg, the "
                        + "work starts at %.1f deg", DispenseProfile.angleOfCount(restCount(), cfg.bStepsPerRev),
                        DispenseProfile.angleOfCount(restCount() + cfg.bExtrudeSign * steps, cfg.bStepsPerRev),
                        profile.startAngleSteps(cfg) * 360.0 / cfg.bStepsPerRev));
            }
            dot(new DotSpec(d.x, d.y, d.dispenseZ, d.hopZ, steps / cfg.bStepsPerDeg, PURGE_INDEX, d.label), steps);
        }
        prog.purgeDotCount = spots.size();
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
        double x = trimX(d.x), y = trimY(d.y);
        moveNozzle(x, y, nozzle[2], cfg.travelFeedMmS, "G0");
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

    private double trimX(double x) {
        return x - cfg.needleXyTrim.dxMm;
    }

    private double trimY(double y) {
        return y - cfg.needleXyTrim.dyMm;
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
            pending = null;
        }
        prog.lines.add(new GcodeProgram.Line(text, marker, idx, pad, rot, extra));
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
            prog.lines.set(i, new GcodeProgram.Line(l.text, primary, index, pad, rot, extra));
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
