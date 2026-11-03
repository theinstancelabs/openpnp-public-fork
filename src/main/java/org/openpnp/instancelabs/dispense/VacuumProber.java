package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.openpnp.spi.Nozzle;
import org.pmw.tinylog.Logger;

/**
 * The automatic N1 vacuum contact probe of one or more points: the bench method (tools/probe.py) with
 * two corrections measured in the bench log. At each spot N1 goes to travel Z, over the spot, down to
 * the approach height; pump and valve on, settle, an open-air baseline (5 reads); coarse 0.25 mm steps
 * until the 3-read mean falls below baseline - max(40000, 15 % of |baseline|). Then, unlike the bench:
 * back to the last open coarse step until the seal lets go (the bench's fine pass started right after
 * the seal and reported contact up to 0.3 mm high, 29 of 156 passes at their first step; one step
 * higher when that step itself holds a partial seal), and a fine pass of 0.025 mm steps below it whose
 * contact needs two sealed steps in a row (partial seals reach the threshold's size). A fine miss re-probes once from the start; the coarse contact is never
 * accepted. Every point ends with the bench's lift and vent: {@code G1 Z<travel> F600, M400, M107 P1,
 * M107 P0}, also after a refusal or an error inside it; only a STOP sends nothing but the I1 shutdown.
 *
 * <p>Every line goes through {@link Io} (the executor's probe gate: the probe allowlist and the abort
 * flag). Contact Z is the N1 Z of the contact step: N1's OpenPnP Z is its raw Z (z1). X/Y move only at
 * travel Z, Z never below the window's floor nor above the travel Z, and the only output lines are
 * N1's pump and valve on and off.</p>
 */
public final class VacuumProber {
    /** The controller link of the probe. */
    public interface Io {
        /** One line ({@link DispenseExecutor#sendGated} with scope PROBE). */
        void send(String line, long timeoutMs) throws Exception;

        /** One line and its reply ({@link DispenseExecutor#queryGated} with scope PROBE). */
        List<String> query(String line, long timeoutMs) throws Exception;

        /** Host wait that ends with {@link DispenseExecutor.AbortedException} on a STOP. */
        void sleep(long ms) throws Exception;

        /**
         * The I1 shutdown (M410, the vents, M18 B: {@link DispenseExecutor#shutdown}), sent after a STOP
         * or when the end lines of a point fail. {@link #gatedIo} implements it. No default: an Io that
         * cannot vent N1 when its own lift fails must not be possible.
         */
        void shutdown();
    }

    /** The camera look before N1 touches a spot (§ "look before touching"). */
    public interface SpotLook {
        Look look(double x, double y) throws Exception;
    }

    /** What the camera saw at a spot. */
    public static final class Look {
        public boolean bare;
        public double foreignShare, ringSpread;
        /** Share of the spot over the threshold before the solid-patch rule, and that threshold (the record). */
        public double rawShare, threshold;
        /** Share of the spot in wide patches, whatever the texture (the record). */
        public double wideShare;
        public String reason;
    }

    /**
     * The last good open-air baseline, kept in memory across tasks (not persisted: a restart starts a
     * new session, and the absolute window still applies).
     */
    public static final class BaselineMemory {
        private Double last;
        private long atMs;

        public synchronized void remember(double baseline, long atMs) {
            this.last = baseline;
            this.atMs = atMs;
        }

        /** The last baseline if it is at most {@code maxAgeMs} old, else null. */
        public synchronized Double recent(long now, long maxAgeMs) {
            return last != null && now - atMs <= maxAgeMs ? last : null;
        }

        public synchronized long atMs() {
            return atMs;
        }
    }

    /** Bench values as code constants, plus the persisted knobs ({@link DispenseConfig.VacuumProbe}). */
    public static final class Settings {
        // persisted (DispenseConfig.vacuumProbe)
        public double startAboveMm = 2.5;
        public double floorBelowMm = 1.5;
        public int openAirMinCounts = -1900000;
        public int openAirMaxCounts = -1200000;
        public int alternates = 4;
        public int vacuumSettleMs = 1500;
        public int readDelayMs = 70;
        // code constants (bench probe.py and its log)
        public double approachAboveStartMm = 1.0;
        public double coarseStepMm = 0.25;
        public double fineStepMm = 0.025;
        /** The fine pass goes at most this far below the coarse contact. */
        public double fineBelowContactMm = 0.10;
        public int zTravelFeedMmMin = 600;
        public int zProbeFeedMmMin = 300;
        public int xyFeedMmMin = 6000;
        public int baselineReads = 5;
        public int stepReads = 3;
        public int minDropCounts = 40000;
        public double dropFraction = 0.15;
        public long releaseTimeoutMs = 1500;
        public int maxSpreadCounts = 15000;
        public int maxDeltaFirstCounts = 50000;
        public int maxDeltaMemoryCounts = 60000;
        public long memoryMaxAgeMs = 2 * 3600 * 1000L;
        public long maxVacuumOnMs = 60000;
        /** Lowest N1 Z the probe may ever use (catches a board Z typed as 0). */
        public double absoluteFloorZ = 2.0;
        /** After the first contact of a task, later floors stay within this of it (worst tilt seen 0.94 mm). */
        public double firstContactBandMm = 1.0;
        public int maxFloorPushes = 3;
        public double travelAccelMmS2 = 2000;
        public long commandTimeoutMs = 10000;
        /** OpenPnP's simulator: the settle and read-delay lower bounds do not apply. */
        public boolean simulated;
        public LongSupplier clock = System::currentTimeMillis;

        /** The persisted knobs and motion settings of a configuration. */
        public static Settings of(DispenseConfig cfg, boolean simulated) {
            Settings s = new Settings();
            DispenseConfig.VacuumProbe v = cfg.vacuumProbe;
            s.startAboveMm = v.startAboveMm;
            s.floorBelowMm = v.floorBelowMm;
            s.openAirMinCounts = v.openAirMinCounts;
            s.openAirMaxCounts = v.openAirMaxCounts;
            s.alternates = v.alternates;
            s.vacuumSettleMs = v.vacuumSettleMs;
            s.readDelayMs = v.readDelayMs;
            s.travelAccelMmS2 = cfg.travelAccelMmS2;
            s.commandTimeoutMs = cfg.commandTimeoutMs;
            s.simulated = simulated;
            return s;
        }
    }

    /** The Z window of a probe, all in N1's frame (raw z1). */
    public static final class Window {
        public double nominalZ, refZ, startZ, approachZ, floorZ, travelZ;

        /**
         * The window around a nominal surface Z: start = nominal + startAbove, approach = start + 1,
         * floor = max(nominal, anchor) - floorBelow (never deeper than either reference: a board Z typed
         * low must not let N1 push 2.5 mm into a hole), travel = the raw Z the dispense stream travels
         * at (the dispenser mapper's raw travel Z), sent as N1 Z. Refused when the anchor puts that floor
         * at or above the nominal: the probe could not reach the surface.
         *
         * @param anchorZ the needle-frame surface (N1 contact Z), or null without one
         */
        public static Window of(double nominalZ, Double anchorZ, double travelRawZ, Settings s)
                throws DispenseService.RunRefusedException {
            return of("the board top Z", nominalZ, anchorZ, travelRawZ, s);
        }

        /** As above; {@code what} names the nominal in the refusal ("the board top Z", "the 'blank' place Z"). */
        public static Window of(String what, double nominalZ, Double anchorZ, double travelRawZ, Settings s)
                throws DispenseService.RunRefusedException {
            Window w = new Window();
            w.nominalZ = nominalZ;
            w.refZ = anchorZ == null ? nominalZ : Math.max(nominalZ, anchorZ);
            w.startZ = nominalZ + s.startAboveMm;
            w.approachZ = w.startZ + s.approachAboveStartMm;
            w.floorZ = w.refZ - s.floorBelowMm;
            w.travelZ = travelRawZ;
            if (!(Double.isFinite(nominalZ) && w.floorZ >= s.absoluteFloorZ - 1e-9)) {
                throw refused(String.format(Locale.ROOT, "PROBE: %s %.3f puts the probe floor at %.3f, below the "
                        + "lowest N1 Z the probe may use (%.3f): set %s to roughly the N1 contact height", what,
                        nominalZ, w.floorZ, s.absoluteFloorZ, what));
            }
            // A board is not checked against the anchor (one frame reference per surface), so a board Z under it
            // reaches here with a floor taken from the anchor. Planning accepts a board top up to 2.0 mm under the
            // anchor, and the floor sits 1.5 mm under it: a floor at or above the board top cannot reach the board
            // (review 1; a board Z typed as 0 is refused here too).
            if (w.floorZ > nominalZ - 1e-9) {
                throw refused(String.format(Locale.ROOT, "PROBE: %s %.3f lies %.3f mm under the needle-frame surface "
                        + "%.3f, and the probe goes at most %.1f mm under the higher of the two (down to Z %.3f): it "
                        + "cannot reach that height. Set %s to roughly the N1 contact height, or record the needle "
                        + "frame again; if both are right, probe by hand", what, nominalZ, w.refZ - nominalZ, w.refZ,
                        s.floorBelowMm, w.floorZ, what));
            }
            return w;
        }

        /**
         * A window of +-{@code mm} around the nominal Z, without the needle-frame floor: a weigh coupon is
         * probed against its own place Z only (profile calibration design §7.2 step 2).
         */
        public static Window narrow(double nominalZ, double mm, double travelRawZ, Settings s)
                throws DispenseService.RunRefusedException {
            Window w = new Window();
            w.nominalZ = nominalZ;
            w.refZ = nominalZ;
            w.startZ = nominalZ + mm;
            w.approachZ = w.startZ + s.approachAboveStartMm;
            w.floorZ = nominalZ - mm;
            w.travelZ = travelRawZ;
            if (w.floorZ < s.absoluteFloorZ - 1e-9) {
                throw refused(String.format(Locale.ROOT, "PROBE: the place Z %.3f puts the probe floor at %.3f, below "
                        + "the lowest N1 Z the probe may use (%.3f)", nominalZ, w.floorZ, s.absoluteFloorZ));
            }
            return w;
        }

        /** A check touch (same seat, Z check): start = stored + startAbove, floor = stored - floorBelow. */
        public static Window around(double storedZ, double travelRawZ, Settings s)
                throws DispenseService.RunRefusedException {
            Window w = new Window();
            w.nominalZ = storedZ;
            w.refZ = storedZ;
            w.startZ = storedZ + s.startAboveMm;
            w.approachZ = w.startZ + s.approachAboveStartMm;
            w.floorZ = storedZ - s.floorBelowMm;
            w.travelZ = travelRawZ;
            if (!(Double.isFinite(storedZ) && w.floorZ >= s.absoluteFloorZ - 1e-9)) {
                throw refused(String.format(Locale.ROOT, "PROBE: the stored point at Z %.3f puts the probe floor at "
                        + "%.3f, below the lowest N1 Z the probe may use (%.3f)", storedZ, w.floorZ,
                        s.absoluteFloorZ));
            }
            return w;
        }

        Window copy() {
            Window w = new Window();
            w.nominalZ = nominalZ;
            w.refZ = refZ;
            w.startZ = startZ;
            w.approachZ = approachZ;
            w.floorZ = floorZ;
            w.travelZ = travelZ;
            return w;
        }
    }

    /** One point to probe: N1 tip machine X/Y, board mm when it is a board spot, nearby alternates. */
    public static final class Target {
        public int n;
        public double x, y;
        public Double bx, by;
        /** Check touches (same seat, Z check): the stored contact Z; the window is built around it. */
        public Double storedZ;
        /** Machine X/Y of the alternates, in the order to try them; and the same in board mm (board targets). */
        public List<double[]> alternates = new ArrayList<>();
        public List<double[]> alternatesBoard = new ArrayList<>();
    }

    /** What happened at one target (its last spot tried). */
    public static final class Point {
        public int n;
        public double x, y;
        public Double bx, by;
        public Double z;
        /** todo | look | now | contact | no-contact | skipped | not-bare */
        public String state = "todo";
        /** 0 = the target, k = its k-th alternate. */
        public int alternate;
        public double baseline, drop, threshold;
        public Double coarseZ, lastOpenZ;
        /** Where the seal let go before the fine pass: the last open step, or one step higher. */
        public Double releaseZ;
        public boolean fine, retried;
        /** [z, 3-read mean] at each step (the release reads at the last open Z). */
        public List<double[]> readings = new ArrayList<>();
        public List<String> notes = new ArrayList<>();
        /** The camera look at the last spot, or null. */
        public Look look;
        /** The last commanded N1 Z, and the deepest. */
        public Double lastZ, lowestZ;
        public Double storedZ;
        /** Spots tried (the target and its alternates), and how many of them were not bare. */
        public int spotsTried, notBareSpots;
        /** The refusal text when the target ended no-contact or not-bare. */
        public String failure;
    }

    private final Settings s;
    private final RawMapper n1;
    private final Io io;
    private final SpotLook look;
    private final RunResult r;
    private final BooleanSupplier aborted;
    private final Consumer<Point> progress;
    private final BaselineMemory mem;
    private BacklashEmulator emu;
    private double[] lastDriver;
    private String[] letters;
    private double travelZ;
    private boolean initDone;
    /** A line went out since the last end lines: a non-STOP exit must lift and vent. */
    private boolean dirty;
    private boolean needsRecovery;
    private int floorPushes;
    private Double firstBaseline;
    private Double firstContactZ;
    private Long vacuumOnSinceMs;
    private double pendingMoveS;
    private double maxForeignShare;

    public VacuumProber(Settings s, RawMapper n1, Io io, SpotLook look, RunResult r, BooleanSupplier aborted,
            Consumer<Point> progress, BaselineMemory mem) {
        this.s = s;
        this.n1 = n1;
        this.io = io;
        this.look = look;
        this.r = r == null ? new RunResult() : r;
        this.aborted = aborted;
        this.progress = progress;
        this.mem = mem == null ? new BaselineMemory() : mem;
    }

    /** True after a STOP or failed end lines: run Session.recover() (un-home), not a resync. */
    public boolean needsRecovery() {
        return needsRecovery;
    }

    /** Descents of this task that reached the floor without contact. */
    public int floorPushes() {
        return floorPushes;
    }

    /** Raw X/Y/Z planned (without backlash offset) where the probe left N1, for OpenPnpRawMapper.resync. */
    public double[] finalPlanned() {
        return emu == null ? n1.currentPlanned() : emu.planned();
    }

    /** Raw X/Y/Z driver coordinates (with backlash offset) where the probe left N1. */
    public double[] finalDriver() {
        return lastDriver == null ? n1.currentDriver() : lastDriver.clone();
    }

    /**
     * Probes the targets in order and returns one point per target. Stops at the first target with no
     * usable spot: it returns that point as no-contact or not-bare (with {@link Point#failure}) and the
     * following points as skipped; {@link #refusal} gives the text. Refused before anything moves on a
     * bad setting, travel Z or N1 state; inside a point after the end lines (REFUSED or ERROR); a STOP
     * throws {@link DispenseExecutor.AbortedException} after the I1 shutdown.
     */
    public List<Point> probe(List<Target> targets, Window w) throws Exception {
        List<Window> windows = checkBeforeMoving(targets, w);
        List<Point> out = new ArrayList<>();
        for (Target t : targets) {
            Point p = new Point();
            p.n = t.n;
            p.x = t.x;
            p.y = t.y;
            p.bx = t.bx;
            p.by = t.by;
            p.storedZ = t.storedZ;
            out.add(p);
        }
        if (emu == null) {
            readPosition();
            letters = new String[] {n1.letterX(), n1.letterY(), n1.letterZ()};
        }
        try {
            if (!initDone) {
                send("M204 S" + GcodeStreamBuilder.f0(s.travelAccelMmS2), s.commandTimeoutMs);
                for (String l : ControllerCommands.VAC1_INIT) {
                    send(l, s.commandTimeoutMs);
                }
                initDone = true;
            }
            for (int i = 0; i < targets.size(); i++) {
                Point p = out.get(i);
                if (!probeTarget(targets.get(i), p, windows.get(i))) {
                    for (int j = i + 1; j < out.size(); j++) {
                        out.get(j).state = "skipped";
                        report(out.get(j));
                    }
                    break;
                }
            }
            if (dirty) {
                endLinesOrShutdown(null); // e.g. no spot was bare: N1 never went down, but the task ends the same
            }
        }
        catch (DispenseExecutor.AbortedException e) {
            stopExit();
            throw e;
        }
        catch (Exception e) {
            if (dirty) {
                endLinesOrShutdown(e);
            }
            throw e;
        }
        catch (Error e) {
            if (dirty) {
                errorShutdown();
            }
            throw e;
        }
        return out;
    }

    /** The refusal text of the first target that ended no-contact or not-bare, or null. */
    public static String refusal(List<Point> points) {
        for (Point p : points) {
            if (p.failure != null) {
                return p.failure;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // One target
    // ------------------------------------------------------------------------------------------

    private boolean probeTarget(Target t, Point p, Window taskWindow) throws Exception {
        List<double[]> spots = new ArrayList<>();
        List<double[]> board = new ArrayList<>();
        spots.add(new double[] {t.x, t.y});
        board.add(t.bx == null ? null : new double[] {t.bx, t.by});
        for (int k = 0; k < Math.min(s.alternates, t.alternates == null ? 0 : t.alternates.size()); k++) {
            spots.add(t.alternates.get(k));
            board.add(t.alternatesBoard != null && k < t.alternatesBoard.size() ? t.alternatesBoard.get(k) : null);
        }
        maxForeignShare = 0;
        for (int k = 0; k < spots.size(); k++) {
            checkAbort();
            p.alternate = k;
            p.x = spots.get(k)[0];
            p.y = spots.get(k)[1];
            p.bx = board.get(k) == null ? null : board.get(k)[0];
            p.by = board.get(k) == null ? null : board.get(k)[1];
            p.spotsTried = k + 1;
            p.z = null;
            String n1State = n1State();
            if (n1State != null) {
                throw refused(n1State);
            }
            if (look != null) {
                p.state = "look";
                report(p);
                Look lk = lookAt(p);
                p.look = lk;
                if (lk != null && !lk.bare) {
                    p.notBareSpots++;
                    maxForeignShare = Math.max(maxForeignShare, lk.foreignShare);
                    p.notes.add(String.format(Locale.ROOT, "%s: paste, a part or print seen (%.0f %% of the spot)%s",
                            spotName(p), 100 * lk.foreignShare, lk.reason == null ? "" : ": " + lk.reason));
                    continue;
                }
            }
            else if (k == 0) {
                p.notes.add("no camera look (no top camera): touched without looking first");
            }
            p.state = "now";
            report(p);
            Double z = touch(t, p, windowFor(t, taskWindow));
            if (z != null) {
                p.z = z;
                p.state = "contact";
                if (firstContactZ == null && t.storedZ == null) {
                    firstContactZ = z;
                }
                report(p);
                return true;
            }
        }
        boolean allNotBare = p.notBareSpots == p.spotsTried;
        p.state = allNotBare ? "not-bare" : "no-contact";
        p.failure = allNotBare ? notBareText(t, p) : noContactText(t, p, windowFor(t, taskWindow));
        report(p);
        return false;
    }

    /** The window of a target: around its stored Z, or the task's, tightened after the first contact. */
    private Window windowFor(Target t, Window taskWindow) {
        Window w = taskWindow.copy();
        if (firstContactZ != null) {
            if (t.storedZ == null) {
                w.startZ = firstContactZ + s.startAboveMm;
                w.approachZ = w.startZ + s.approachAboveStartMm;
            }
            w.floorZ = Math.max(w.floorZ, firstContactZ - s.firstContactBandMm);
        }
        return w;
    }

    /**
     * Touches one spot: approach, vacuum on, baseline, coarse, release, fine (and one re-probe after a
     * fine miss), then the end lines. Returns the contact Z, or null (no contact).
     */
    private Double touch(Target t, Point p, Window w) throws Exception {
        Double z;
        boolean floorPush;
        try {
            if (Math.abs(emu.planned()[2] - travelZ) > 1e-6) {
                moveZ(travelZ, s.zTravelFeedMmMin, w);
            }
            moveXY(p.x, p.y);
            moveZ(w.approachZ, s.zTravelFeedMmMin, w);
            sync();
            send(ControllerCommands.LEFT_PUMP_ON, s.commandTimeoutMs);
            vacuumOnSinceMs = s.clock.getAsLong();
            send(ControllerCommands.LEFT_VALVE_ON, s.commandTimeoutMs);
            dwell(s.vacuumSettleMs);
            baseline(p);
            Double zc = coarse(t, p, w);
            floorPush = zc == null;
            z = zc == null ? null : releaseAndFine(p, w, zc);
            if (zc != null && z == null) {
                p.retried = true;
                p.notes.add(spotName(p) + ": the fine pass found no contact: probed again from the start");
                moveZ(w.approachZ, s.zTravelFeedMmMin, w);
                sync();
                dwell(s.vacuumSettleMs);
                baseline(p);
                zc = coarse(t, p, w);
                floorPush = zc == null;
                z = zc == null ? null : releaseAndFine(p, w, zc);
                if (zc != null && z == null) {
                    p.notes.add(spotName(p) + ": the fine pass found no contact twice: no contact here");
                }
            }
        }
        catch (DispenseExecutor.AbortedException e) {
            stopExit();
            throw e;
        }
        catch (Exception e) {
            endLinesOrShutdown(e);
            throw e;
        }
        catch (Error e) {
            errorShutdown();
            throw e;
        }
        endLinesOrShutdown(null);
        if (floorPush) {
            floorPushes++;
            p.notes.add(String.format(Locale.ROOT, "%s: no contact down to the floor Z %.3f", spotName(p), w.floorZ));
            if (floorPushes >= s.maxFloorPushes) {
                throw refused(noContactText(t, p, w));
            }
        }
        return z;
    }

    /** The open-air baseline with its checks; sets the point's baseline, drop and threshold. */
    private void baseline(Point p) throws Exception {
        double[] v = reads(s.baselineReads);
        double spread = spread(v);
        if (spread > s.maxSpreadCounts) {
            p.notes.add(String.format(Locale.ROOT, "open air unsteady (spread %.0f counts): settled again", spread));
            dwell(s.vacuumSettleMs);
            v = reads(s.baselineReads);
            spread = spread(v);
            if (spread > s.maxSpreadCounts) {
                throw refused(String.format(Locale.ROOT, "PROBE: N1's vacuum is not steady in open air (spread %s "
                        + "counts, limit %s) after a second settle: check the N1 tubing and pump", kilo(spread),
                        kilo(s.maxSpreadCounts)));
            }
        }
        double base = mean(v);
        if (base > s.openAirMaxCounts) {
            throw refused(String.format(Locale.ROOT, "PROBE: N1's vacuum reads %s counts in open air at X %.2f, Y %.2f "
                    + "(normal %s to %s counts): no nozzle tip on N1, a leak, or the N1 pump is off. Check N1, then "
                    + "try again.", mega(base), p.x, p.y, mega(s.openAirMinCounts), mega(s.openAirMaxCounts)));
        }
        if (base < s.openAirMinCounts) {
            throw refused(String.format(Locale.ROOT, "PROBE: N1's vacuum reads %s counts in open air (normal %s to %s "
                    + "counts): the tip is blocked or a part is stuck on it. Check N1, then try again.", mega(base),
                    mega(s.openAirMinCounts), mega(s.openAirMaxCounts)));
        }
        long now = s.clock.getAsLong();
        if (firstBaseline != null && Math.abs(base - firstBaseline) > s.maxDeltaFirstCounts) {
            throw refused(relativeText(base, firstBaseline, "the first point of this probe", s.maxDeltaFirstCounts));
        }
        Double recent = mem.recent(now, s.memoryMaxAgeMs);
        if (recent != null && Math.abs(base - recent) > s.maxDeltaMemoryCounts) {
            throw refused(relativeText(base, recent, ZCheck.clock(mem.atMs()), s.maxDeltaMemoryCounts));
        }
        mem.remember(base, now);
        if (firstBaseline == null) {
            firstBaseline = base;
        }
        p.baseline = base;
        p.drop = Math.max(s.minDropCounts, s.dropFraction * Math.abs(base));
        p.threshold = base - p.drop;
    }

    /** Coarse descent from the start Z to the floor; the coarse contact Z, or null at the floor. */
    private Double coarse(Target t, Point p, Window w) throws Exception {
        p.coarseZ = null;
        p.lastOpenZ = null;
        p.releaseZ = null;
        for (int k = 0;; k++) {
            double z = round4(w.startZ - k * s.coarseStepMm);
            if (z < w.floorZ - 1e-9) {
                return null;
            }
            double m = step(p, z, w);
            if (m < p.threshold) {
                if (k == 0) {
                    throw refused(String.format(Locale.ROOT, "PROBE: N1 sealed already at Z %.3f, %.2f mm above %s: "
                            + "something is under N1 or the %s is higher than its Z. Check, then try again.", z,
                            z - w.nominalZ, topWord(t), t.bx != null ? "board" : "surface"));
                }
                p.coarseZ = z;
                return z;
            }
            p.lastOpenZ = z;
        }
    }

    /**
     * Back to the last open coarse step until the seal lets go (|mean - baseline| < drop / 2 within
     * {@link Settings#releaseTimeoutMs}), then the fine pass below it: the first step whose mean and the
     * next step's mean are both sealed. Null when the fine pass finds no such pair.
     *
     * <p>The last "open" coarse step often reads a partial seal already: in 101 of 155 bench probes
     * (runs/serial.log) its mean was more than drop / 2 below the baseline (median 169 k counts against
     * a ~230 k threshold), and it never comes back to the baseline there. So when it does not let go
     * within the wait, N1 waits once more one coarse step higher, and the fine pass starts below that
     * step; only a seal that holds there too is refused. The fine pass may then find the contact at or
     * above the last open coarse step: that step's partial seal was the surface. Real N1 seals build up
     * over Z and time (real machine 2026-10-29, board 3: open at 5.40, 1.78 M at 5.375, sealed at
     * 5.325 / 5.30 on the fine pass, while the quick coarse reads at 5.25 stayed just above the
     * threshold), so a contact above that step is the surface, not drift.</p>
     */
    private Double releaseAndFine(Point p, Window w, double zc) throws Exception {
        double zo = p.lastOpenZ;
        if (!released(p, w, zo)) {
            double up = round4(zo + s.coarseStepMm);
            p.notes.add(String.format(Locale.ROOT, "%s: still drawing at Z %.3f after %.1f s (a partial seal at the "
                    + "last open step): let go at Z %.3f", spotName(p), zo, s.releaseTimeoutMs / 1000.0, up));
            if (!released(p, w, up)) {
                throw refused(String.format(Locale.ROOT, "PROBE: N1 did not let go of the surface within %.1f s after "
                        + "the touch at X %.2f, Y %.2f: check the N1 tip and tubing, then try again",
                        s.releaseTimeoutMs / 1000.0, p.x, p.y));
            }
            zo = up;
        }
        p.releaseZ = zo;
        double bottom = Math.max(round4(zc - s.fineBelowContactMm), w.floorZ);
        boolean prevSealed = false;
        double prevZ = Double.NaN;
        for (int k = 1;; k++) {
            double z = round4(zo - k * s.fineStepMm);
            boolean confirming = z < bottom - 1e-9;
            if (confirming && !(prevSealed && z >= w.floorZ - 1e-9)) {
                return null;
            }
            boolean sealed = step(p, z, w) < p.threshold;
            if (sealed && prevSealed) {
                p.fine = true;
                return prevZ;
            }
            if (confirming) {
                return null;
            }
            prevSealed = sealed;
            prevZ = z;
        }
    }

    /** Moves to {@code z} and reads until the seal let go there, at most {@link Settings#releaseTimeoutMs}. */
    private boolean released(Point p, Window w, double z) throws Exception {
        moveZ(z, s.zProbeFeedMmMin, w);
        sync();
        long t0 = s.clock.getAsLong();
        while (true) {
            double m = mean(reads(s.stepReads));
            p.readings.add(new double[] {z, m});
            report(p);
            if (Math.abs(m - p.baseline) < p.drop / 2) {
                return true;
            }
            if (s.clock.getAsLong() - t0 >= s.releaseTimeoutMs) {
                return false;
            }
        }
    }

    /** One probe step: Z, M400, the mean of the step reads (recorded). */
    private double step(Point p, double z, Window w) throws Exception {
        moveZ(z, s.zProbeFeedMmMin, w);
        sync();
        double m = mean(reads(s.stepReads));
        p.readings.add(new double[] {z, m});
        p.lastZ = z;
        p.lowestZ = p.lowestZ == null ? z : Math.min(p.lowestZ, z);
        report(p);
        return m;
    }

    /** Where N1 is now (raw planned and driver X/Y/Z), the start of the next raw move. */
    private void readPosition() {
        AxisBacklash[] b = n1.backlash();
        double[] planned = n1.currentPlanned();
        lastDriver = n1.currentDriver();
        double[] off = {lastDriver[0] - planned[0], lastDriver[1] - planned[1], lastDriver[2] - planned[2]};
        // Z goes exactly where it is sent (bench raw Z; z1 has no backlash offset): no Z compensation.
        emu = new BacklashEmulator(new AxisBacklash[] {b[0], b[1], AxisBacklash.none()}, planned, off, true);
    }

    /**
     * The camera look moves the head through OpenPnP: before it, OpenPnP learns where the probe left N1
     * (lifted and at rest after the end lines), after it the probe starts from where the camera left the
     * head (the capture waited for still-stand).
     */
    private Look lookAt(Point p) throws Exception {
        OpenPnpRawMapper m = n1 instanceof OpenPnpRawMapper ? (OpenPnpRawMapper) n1 : null;
        if (m != null) {
            m.resync(emu.planned(), lastDriver.clone(), m.currentB());
        }
        try {
            return look.look(p.x, p.y);
        }
        finally {
            if (m != null) {
                readPosition(); // also after a failed look: OpenPnP's state is the truth once it moved
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Lines
    // ------------------------------------------------------------------------------------------

    private void checkAbort() throws Exception {
        if (aborted != null && aborted.getAsBoolean()) {
            io.sleep(0); // the executor's Io ends with its own abort reason ("STOP pressed in the web UI")
            throw new DispenseExecutor.AbortedException("stopped during the N1 probe");
        }
    }

    /** Before every line of a point: STOP, and the vacuum on-time cap. */
    private void guard() throws Exception {
        checkAbort();
        Long on = vacuumOnSinceMs;
        if (on != null && s.clock.getAsLong() - on > s.maxVacuumOnMs) {
            throw new IllegalStateException(String.format(Locale.ROOT, "N1 vacuum on for %.0f s at one point: stopped "
                    + "(N1 lifted, pump and valve off)", s.maxVacuumOnMs / 1000.0));
        }
    }

    private void send(String line, long timeoutMs) throws Exception {
        guard();
        dirty = true;
        io.send(line, timeoutMs);
    }

    private void moveZ(double z, int feedMmMin, Window w) throws Exception {
        if (z < w.floorZ - 1e-9 || z > travelZ + 1e-9) {
            throw new IllegalStateException(String.format(Locale.ROOT, "probe Z %.4f outside the window %.4f..%.4f",
                    z, w.floorZ, travelZ));
        }
        double[] cur = emu.planned();
        emu.moveTo(new double[] {cur[0], cur[1], z});
        double dz = Math.abs(z - lastDriver[2]);
        lastDriver[2] = z;
        double t = GcodeStreamBuilder.moveSeconds(dz, feedMmMin / 60.0, s.travelAccelMmS2);
        pendingMoveS += t;
        send("G1 " + letters[2] + GcodeStreamBuilder.f4(z) + " F" + feedMmMin, s.commandTimeoutMs + (long) (t * 1000));
    }

    /** X/Y at travel Z, through the X/Y backlash emulation (one or two lines, as OpenPnP moves them). */
    private void moveXY(double x, double y) throws Exception {
        if (Math.abs(emu.planned()[2] - travelZ) > 1e-6) {
            throw new IllegalStateException("probe X/Y move away from the travel Z");
        }
        RawPoint rp = n1.toRaw(x, y, travelZ);
        for (BacklashEmulator.Move m : emu.moveTo(new double[] {rp.x, rp.y, travelZ})) {
            StringBuilder sb = new StringBuilder("G1");
            boolean any = false;
            for (int i = 0; i < 2; i++) {
                if (!GcodeStreamBuilder.f4(m.driver[i]).equals(GcodeStreamBuilder.f4(lastDriver[i]))) {
                    sb.append(' ').append(letters[i]).append(GcodeStreamBuilder.f4(m.driver[i]));
                    any = true;
                }
            }
            if (!any) {
                continue;
            }
            double feed = s.xyFeedMmMin * m.speedFactor;
            sb.append(" F").append(GcodeStreamBuilder.f0(feed));
            double t = GcodeStreamBuilder.moveSeconds(Math.hypot(m.driver[0] - lastDriver[0],
                    m.driver[1] - lastDriver[1]), feed / 60, s.travelAccelMmS2);
            pendingMoveS += t;
            lastDriver[0] = m.driver[0];
            lastDriver[1] = m.driver[1];
            send(sb.toString(), s.commandTimeoutMs + (long) (t * 1000));
        }
    }

    private void sync() throws Exception {
        long t = s.commandTimeoutMs + (long) (pendingMoveS * 1000);
        pendingMoveS = 0;
        send("M400", t);
    }

    private void dwell(int ms) throws Exception {
        if (ms > 0) {
            send("G4 P" + ms, s.commandTimeoutMs + ms);
        }
    }

    private double[] reads(int n) throws Exception {
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            if (s.readDelayMs > 0) {
                io.sleep(s.readDelayMs);
            }
            for (String l : ControllerCommands.VAC1_SELECT) {
                send(l, s.commandTimeoutMs);
            }
            guard();
            dirty = true;
            v[i] = parse(io.query(ControllerCommands.VAC1_READ, s.commandTimeoutMs));
        }
        return v;
    }

    /**
     * The VAC1 reading in a reply ({@code echo:i2c-reply: from:109 bytes:3 data:E8 F2 F6}): three hex
     * bytes, big-endian 24-bit two's complement (probe.py).
     */
    static int parse(List<String> reply) throws Exception {
        List<String> lines = reply == null ? new ArrayList<>() : reply;
        for (String l : lines) {
            if (l != null && l.trim().toLowerCase(Locale.ROOT).startsWith("error")) {
                throw new IllegalStateException("controller error in the vacuum read: " + l.trim());
            }
        }
        for (String l : lines) {
            int i = l == null ? -1 : l.indexOf("data:");
            if (i < 0) {
                continue;
            }
            String[] b = l.substring(i + 5).trim().split("\\s+");
            if (b.length >= 3) {
                try {
                    int v = (Integer.parseInt(b[0], 16) << 16) | (Integer.parseInt(b[1], 16) << 8)
                            | Integer.parseInt(b[2], 16);
                    if (v >= 0 && v <= 0xFFFFFF) {
                        return (v & 0x800000) != 0 ? v - (1 << 24) : v;
                    }
                }
                catch (NumberFormatException e) {
                    // not a reading: refused below
                }
            }
        }
        throw refused("PROBE: no vacuum reading from the controller (M261 answered: '" + String.join(" ", lines)
                + "'): probe by hand");
    }

    /** The bench's end of every point: lift to travel Z, wait, valve off, pump off. */
    private void endLines() throws Exception {
        double[] cur = emu.planned();
        emu.moveTo(new double[] {cur[0], cur[1], travelZ});
        double t = GcodeStreamBuilder.moveSeconds(Math.abs(travelZ - lastDriver[2]), s.zTravelFeedMmMin / 60.0,
                s.travelAccelMmS2);
        lastDriver[2] = travelZ;
        io.send("G1 " + letters[2] + GcodeStreamBuilder.f4(travelZ) + " F" + s.zTravelFeedMmMin,
                s.commandTimeoutMs + (long) (t * 1000));
        pendingMoveS = 0;
        io.send("M400", s.commandTimeoutMs + (long) (t * 1000));
        io.send(ControllerCommands.LEFT_VALVE_OFF, s.commandTimeoutMs);
        io.send(ControllerCommands.LEFT_PUMP_OFF, s.commandTimeoutMs);
        vacuumOnSinceMs = null;
        dirty = false;
    }

    /**
     * The end lines; if they fail, the I1 shutdown (and the caller recovers). {@code cause} is the
     * exception that ended the point, if any; without one a failure of the end lines is thrown.
     */
    private void endLinesOrShutdown(Exception cause) throws Exception {
        try {
            endLines();
        }
        catch (DispenseExecutor.AbortedException e) {
            stopExit();
            if (cause == null) {
                throw e;
            }
            cause.addSuppressed(e);
        }
        catch (Exception e) {
            Logger.warn(e, "N1 probe: the end lines failed; shutting down");
            r.warnings.add("N1 probe: the lift and vent after the point failed (" + e.getMessage()
                    + "): stop sequence sent");
            needsRecovery = true;
            io.shutdown();
            dirty = false;
            vacuumOnSinceMs = null;
            if (cause == null) {
                throw new IllegalStateException("N1 probe: the lift and vent after the point failed: " + e.getMessage(),
                        e);
            }
            cause.addSuppressed(e);
        }
    }

    /**
     * I1 after an Error ended a point (rc1 review: an OutOfMemoryError, or a progress listener's Error, left N1's vacuum on
     * and N1 down): the stop sequence only, no lift (the end lines move; what is left of the task may not be sound).
     */
    private void errorShutdown() {
        needsRecovery = true;
        dirty = false;
        vacuumOnSinceMs = null;
        io.shutdown();
    }

    /** STOP: no motion, only the I1 shutdown (it vents N1); the caller recovers (un-home). */
    private void stopExit() {
        if (!needsRecovery) {
            needsRecovery = true;
            io.shutdown();
        }
        dirty = false;
        vacuumOnSinceMs = null;
    }

    // ------------------------------------------------------------------------------------------
    // Checks before anything moves
    // ------------------------------------------------------------------------------------------

    private List<Window> checkBeforeMoving(List<Target> targets, Window w) throws Exception {
        if (targets == null || targets.isEmpty()) {
            throw new IllegalArgumentException("nothing to probe");
        }
        String timing = timingProblem(s);
        if (timing != null) {
            throw refused(timing);
        }
        travelZ = w.travelZ;
        double[] zone = n1.zSafeZoneRaw();
        if (zone != null && ((!Double.isNaN(zone[0]) && travelZ < zone[0] - 1e-6)
                || (!Double.isNaN(zone[1]) && travelZ > zone[1] + 1e-6))) {
            throw refused(String.format(Locale.ROOT, "PROBE: the travel Z %.3f lies outside N1's safe zone %s..%s: "
                    + "check travelSafeZMm", travelZ, Double.isNaN(zone[0]) ? "-" : GcodeStreamBuilder.f4(zone[0]),
                    Double.isNaN(zone[1]) ? "-" : GcodeStreamBuilder.f4(zone[1])));
        }
        try {
            if (Math.abs(n1.toRaw(targets.get(0).x, targets.get(0).y, 5).z - 5) > 1e-6
                    || Math.abs(n1.toRaw(targets.get(0).x, targets.get(0).y, 10).z - 10) > 1e-6) {
                throw refused("PROBE: N1's Z is transformed in OpenPnP; the probe needs N1 Z = raw Z");
            }
        }
        catch (DispenseService.RunRefusedException e) {
            throw e;
        }
        catch (Exception e) {
            throw refused("PROBE: N1 cannot reach X " + targets.get(0).x + ", Y " + targets.get(0).y + ": "
                    + e.getMessage());
        }
        String state = n1State();
        if (state != null) {
            throw refused(state);
        }
        List<Window> l = new ArrayList<>();
        for (Target t : targets) {
            Window tw = t.storedZ == null ? w : Window.around(t.storedZ, w.travelZ, s);
            if (tw.approachZ > travelZ + 1e-9) {
                throw refused(String.format(Locale.ROOT, "PROBE: the approach Z %.3f lies above the travel Z %.3f",
                        tw.approachZ, travelZ));
            }
            l.add(tw);
        }
        return l;
    }

    private String n1State() {
        return n1 instanceof OpenPnpRawMapper ? n1StateProblem(((OpenPnpRawMapper) n1).getNozzle()) : null;
    }

    /** OpenPnP's N1 state: a part on it, or no nozzle tip (text), else null. */
    public static String n1StateProblem(Nozzle n1) {
        if (n1 == null) {
            return null;
        }
        if (n1.getPart() != null) {
            return "PROBE: OpenPnP says " + n1.getName() + " holds a part (" + n1.getPart().getId() + "): discard it "
                    + "first";
        }
        if (n1.getNozzleTip() == null) {
            return "PROBE: OpenPnP shows no nozzle tip on " + n1.getName() + ": load the N045 tip first";
        }
        return null;
    }

    /** The simulator's settle / read delay on a machine that is not one (text), else null. */
    public static String timingProblem(Settings s) {
        if (s.simulated) {
            return null;
        }
        if (s.vacuumSettleMs < DispenseConfig.VacuumProbe.REAL_MIN_SETTLE_MS) {
            return "PROBE: vacuumProbe.vacuumSettleMs " + s.vacuumSettleMs + " is a simulator setting: the real VAC1 "
                    + "needs at least " + DispenseConfig.VacuumProbe.REAL_MIN_SETTLE_MS + " ms (a reading 0.3 s after "
                    + "the valve opened looked like a seal)";
        }
        if (s.readDelayMs < DispenseConfig.VacuumProbe.REAL_MIN_READ_DELAY_MS) {
            return "PROBE: vacuumProbe.readDelayMs " + s.readDelayMs + " is a simulator setting: the real VAC1 needs "
                    + "at least " + DispenseConfig.VacuumProbe.REAL_MIN_READ_DELAY_MS + " ms between reads (closer "
                    + "reads repeat the last conversion)";
        }
        return null;
    }

    /**
     * A surface checked against the needle frame (the blank, a place) whose nominal Z is further from
     * the anchor than the frame tolerance (text), else null. A board surface has its own reference,
     * its top Z, and is not checked against the anchor.
     */
    public static String frameMismatch(String what, double nominalZ, DispenseConfig.NeedleAnchor a, double tolMm) {
        if (a == null || Math.abs(nominalZ - a.surfaceZMm) <= tolMm + 1e-9) {
            return null;
        }
        String where = a.xMm != null && a.yMm != null
                ? String.format(Locale.ROOT, "recorded at X %.1f, Y %.1f on %s", a.xMm, a.yMm, ZCheck.dayClock(a.setAtMs))
                : "recorded on " + ZCheck.dayClock(a.setAtMs);
        return String.format(Locale.ROOT, "PROBE: %s %.3f is %.3f mm from the needle-frame surface %.3f (%s; tolerance "
                + "%.2f): set %s to roughly the N1 contact height, or record the needle frame again", what, nominalZ,
                Math.abs(nominalZ - a.surfaceZMm), a.surfaceZMm, where, tolMm, what);
    }

    /**
     * Every check of a probe task before anything moves: the needle frame (a board probe needs it:
     * I4 refuses an unanchored surface), the frame check of a surface measured against the anchor
     * (not a board, whose reference is its own top Z), the simulator timings, OpenPnP's N1 state and
     * the window. Throws with the refusal text; returns the window.
     */
    public static Window preflight(DispenseConfig cfg, boolean board, String what, double nominalZ, double travelRawZ,
            Settings s, Nozzle n1) throws DispenseService.RunRefusedException {
        return preflight(cfg, board, what, nominalZ, null, travelRawZ, s, n1);
    }

    /**
     * As above; a {@code windowMm} (a weigh coupon) gives the window +-windowMm around the nominal Z
     * ({@link Window#narrow}).
     */
    public static Window preflight(DispenseConfig cfg, boolean board, String what, double nominalZ, Double windowMm,
            double travelRawZ, Settings s, Nozzle n1) throws DispenseService.RunRefusedException {
        DispenseConfig.NeedleAnchor a = cfg.needleAnchor;
        if (board && a == null) {
            throw refused("I4: the needle Z frame is not set: record it first (Setup › Needle Z frame); the probe "
                    + "checks its heights against it");
        }
        if (!board) {
            String why = frameMismatch(what, nominalZ, a, cfg.zFrameToleranceMm);
            if (why != null) {
                throw refused(why);
            }
        }
        String timing = timingProblem(s);
        if (timing != null) {
            throw refused(timing);
        }
        String state = n1StateProblem(n1);
        if (state != null) {
            throw refused(state);
        }
        return windowMm != null ? Window.narrow(nominalZ, windowMm, travelRawZ, s)
                : Window.of(what, nominalZ, a == null ? null : a.surfaceZMm, travelRawZ, s);
    }

    /** The probe's controller link over the executor: the PROBE gate, abort-checked sleeps, the I1 shutdown. */
    public static Io gatedIo(DispenseExecutor exec, RunResult r) {
        return new Io() {
            @Override
            public void send(String line, long timeoutMs) throws Exception {
                exec.sendGated(DispenseExecutor.Scope.PROBE, line, timeoutMs, r);
            }

            @Override
            public List<String> query(String line, long timeoutMs) throws Exception {
                return exec.queryGated(DispenseExecutor.Scope.PROBE, line, timeoutMs, r);
            }

            @Override
            public void sleep(long ms) throws Exception {
                long end = System.nanoTime() + ms * 1_000_000L;
                while (true) {
                    if (exec.isAbortRequested()) {
                        throw new DispenseExecutor.AbortedException(exec.getAbortReason());
                    }
                    long left = (end - System.nanoTime()) / 1_000_000L;
                    if (left <= 0) {
                        return;
                    }
                    Thread.sleep(Math.min(left, 20));
                }
            }

            @Override
            public void shutdown() {
                exec.shutdown(r);
            }
        };
    }

    // ------------------------------------------------------------------------------------------
    // Texts and helpers
    // ------------------------------------------------------------------------------------------

    private void report(Point p) {
        if (progress == null) {
            return;
        }
        try {
            progress.accept(p);
        }
        catch (Exception e) {
            Logger.warn(e, "probe progress listener failed");
        }
    }

    private static String topWord(Target t) {
        return t.storedZ != null ? "where it touched before" : t.bx != null ? "the board top" : "the surface Z";
    }

    private static String where(Target t) {
        return t.bx != null ? String.format(Locale.ROOT, "board X %.1f, Y %.1f", t.bx, t.by)
                : String.format(Locale.ROOT, "X %.2f, Y %.2f", t.x, t.y);
    }

    private static String spotName(Point p) {
        return (p.alternate == 0 ? "the spot" : "alternate " + p.alternate)
                + (p.bx != null ? String.format(Locale.ROOT, " (board X %.1f, Y %.1f)", p.bx, p.by)
                        : String.format(Locale.ROOT, " (X %.2f, Y %.2f)", p.x, p.y));
    }

    private static String nearby(int spots, String word) {
        int n = spots - 1;
        return n <= 0 ? "" : " " + word + " " + n + (n == 1 ? " spot" : " spots") + " nearby";
    }

    private String noContactText(Target t, Point p, Window w) {
        double low = p.lowestZ == null ? w.floorZ : p.lowestZ;
        boolean board = t.bx != null;
        // after the first contact the floor may lie above the nominal (the first-contact band)
        return String.format(Locale.ROOT, "PROBE: no contact at %s%s (N1 went down to Z %.3f, %.2f mm %s %s): "
                + "probably a hole or via there, or the %s Z is wrong. Probe by hand, or check the %s Z and try "
                + "again.", where(t), nearby(p.spotsTried, "or"), low, Math.abs(w.nominalZ - low),
                low <= w.nominalZ + 1e-9 ? "below" : "above", topWord(t), board ? "board top" : "surface",
                board ? "board" : "surface");
    }

    private String notBareText(Target t, Point p) {
        return String.format(Locale.ROOT, "PROBE: something on the surface at %s%s (paste, a part or print: up to "
                + "%.0f %% of the spot): wipe it, or probe by hand", where(t), nearby(p.spotsTried, "and"),
                100 * maxForeignShare);
    }

    private String relativeText(double base, double ref, String when, int limit) {
        double d = base - ref;
        return String.format(Locale.ROOT, "PROBE: N1's vacuum reads %s counts in open air, %s counts %s than at %s "
                + "(%s counts; limit %s): %s. Look at N1, then try again.", mega(base), kilo(Math.abs(d)),
                d < 0 ? "stronger" : "weaker", when, mega(ref), kilo(limit), d < 0
                        ? "a part may be stuck on N1 or the tip is partly blocked"
                        : "N1's tip may leak, be cracked or be missing");
    }

    private static String mega(double counts) {
        return String.format(Locale.ROOT, "%.2f M", counts / 1e6);
    }

    private static String kilo(double counts) {
        return String.format(Locale.ROOT, "%.0f k", counts / 1e3);
    }

    private static double mean(double[] v) {
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }

    private static double spread(double[] v) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double x : v) {
            lo = Math.min(lo, x);
            hi = Math.max(hi, x);
        }
        return hi - lo;
    }

    private static double round4(double z) {
        return Math.round(z * 10000) / 10000.0;
    }

    private static DispenseService.RunRefusedException refused(String why) {
        return new DispenseService.RunRefusedException(why, null);
    }
}
