package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Static safety check of a generated program before it is streamed. Deterministic rules:
 * <ul>
 * <li>Every line is a command the dispenser sends ({@link ControllerCommands#check}): M106/M107 only
 * as the four fixed pump/valve commands (no PWM, no other port), no M42/M43 or other output
 * commands, the watchdog only as M990 / M990 S0 / M990 S&lt;window&gt;. The N1 vacuum and VAC1
 * lines belong to the probe only ({@link ControllerCommands#checkProbe}), never to a program.</li>
 * <li>Valve ON only while the pump is ON; valve OFF before pump OFF (plan §4.5).</li>
 * <li>I2: no B move before G92 B0 unless B was already zeroed since home/reconnect.</li>
 * <li>B moves only inside a pressure session (pump and valve ON).</li>
 * <li>No X/Y/Z move while the screw acceleration (M204 S above the XY/Z one) is in force: M204 S also
 * governs XY and Z, so the screw's 20000 deg/s² must only ever cover the B moves.</li>
 * <li>No B feed above the B limit (bMaxFeedDegS, at most 400 rpm).</li>
 * <li>No M400 between a dot's first and last line (no per-dot synchronisation).</li>
 * <li>The program ends with valve and pump OFF and the watchdog disarmed.</li>
 * </ul>
 *
 * <p>The screw turns (profile calibration design §6.4), from the B targets in steps since each
 * G92 B0: every dot turns what it was built to turn; a purge-row dot (purge, phase, flush) turns
 * more than nothing and at most one revolution and the 20° phase turn. In dose-block programs
 * (board plans and calibration blocks) also: every work dot is a quarter, half or whole turn the
 * tip takes (or the profile's own dose); every block of a partial turn that follows purge-row dots
 * starts at the profile's start angle (with the step count known); two work dots of different
 * turns have a block switch between them (the last flush dots turn the new dose); a pad's dots are
 * never split; and the whole program turns the screw no more than its work dots and purge-row
 * dots allow.</p>
 */
public final class GcodeStreamValidator {
    private GcodeStreamValidator() {
    }

    /**
     * Allowed raw Z of every G0/G1 Z word: between the raw Z of the I4 needle floor and of the
     * travel Z (direction-agnostic, so it holds for the negated see-saw), plus the Z backlash.
     */
    public static final class ZWindow {
        public final String letterZ;
        public final double lo, hi;

        public ZWindow(String letterZ, double lo, double hi) {
            this.letterZ = letterZ;
            this.lo = lo;
            this.hi = hi;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "%s %.4f..%.4f", letterZ, lo, hi);
        }
    }

    /** The raw Z window for this configuration, or null without a needle frame (I4 refuses then). */
    public static ZWindow zWindow(DispenseConfig cfg, RawMapper mapper) throws Exception {
        return zWindow(cfg, mapper, null);
    }

    /**
     * As above, for a program whose dots take their Z from a surface with its own frame reference
     * ({@link GcodeProgram#floorReferenceZMm}: a board's top Z); the floor follows the lower of the
     * two ({@link DispenseConfig#needleFloorZMm(Double)}).
     */
    public static ZWindow zWindow(DispenseConfig cfg, RawMapper mapper, Double surfaceReferenceZMm) throws Exception {
        double floor = cfg.needleFloorZMm(surfaceReferenceZMm);
        if (Double.isNaN(floor)) {
            return null;
        }
        double[] n = mapper.currentNozzleLocation();
        double a = mapper.toRaw(n[0], n[1], floor).z;
        double b = mapper.toRaw(n[0], n[1], cfg.travelSafeZMm).z;
        AxisBacklash z = mapper.backlash()[2];
        double tol = 0.01 + Math.abs(z.offsetMm) + Math.abs(z.sneakUpOffsetMm);
        return new ZWindow(mapper.letterZ(), Math.min(a, b) - tol, Math.max(a, b) + tol);
    }

    /**
     * The screw-turn rules (§6.4) over one program: B targets in extrude-positive motor steps from
     * the last G92 B0, the step count moved since the start, every dot's net turn.
     */
    private static final class Turns {
        private final GcodeProgram prog;
        private final DispenseConfig cfg;
        private final List<String> p;
        private final Pattern bValue;
        private final boolean blocks;
        private final int rev;
        private final long purgeCap;
        /** B position (steps) and the steps moved since the program start; null before B is zeroed. */
        private Long pos;
        private long moved;
        // the dot being read
        private boolean inDot;
        private int dotLine;
        private String dotText;
        private int dotIndex;
        private int dotPlanned;
        private int dotPad;
        private String dotPadId;
        private long dotMovedBefore;
        /** B was zeroed when the dot began (its rest before is known). */
        private boolean dotKnown;
        // across dots
        private int purgeDots;
        private final List<Long> purgeSinceWork = new ArrayList<>();
        private Long lastWorkNet;
        private int lastWorkIndex = -1;
        private int lastPad = -1;
        private final java.util.Map<Integer, Integer> lastDotOfPad = new java.util.HashMap<>();
        private final java.util.Set<Integer> splitPads = new java.util.HashSet<>();
        private int block;
        private long plannedWork;

        Turns(GcodeProgram prog, DispenseConfig cfg, String letterB, List<String> p) {
            this.prog = prog;
            this.cfg = cfg;
            this.p = p;
            this.bValue = Pattern.compile("(?:^|\\s)" + Pattern.quote(letterB) + "([-+]?[0-9]*\\.?[0-9]+)");
            this.blocks = prog.blocks != null;
            this.rev = cfg.bStepsPerRev;
            this.purgeCap = rev + Math.round(DispenseProfile.MIN_PHASE_TURN_DEG / 360.0 * rev);
            this.pos = prog.bZeroedAtStart ? Long.valueOf(prog.bStartSteps) : null;
        }

        void line(int n, GcodeProgram.Line line) {
            String s = line.text.trim();
            if (line.has(GcodeProgram.Marker.DOT_BEGIN)) {
                inDot = true;
                dotLine = n;
                dotText = s;
                dotIndex = line.dotIndex;
                dotPlanned = line.netSteps;
                dotPad = line.padIndex;
                dotPadId = line.padId;
                dotMovedBefore = moved;
                dotKnown = pos != null;
            }
            if (s.matches("G92\\s.*") && bValue.matcher(s.substring(3)).find()) {
                pos = 0L;
            }
            else if (s.matches("G[01](\\s.*)?")) {
                java.util.regex.Matcher m = bValue.matcher(s.substring(2));
                if (m.find() && pos != null) {
                    long to = Math.round(cfg.bExtrudeSign * Double.parseDouble(m.group(1)) * cfg.bStepsPerDeg);
                    moved += to - pos;
                    pos = to;
                }
            }
            if (inDot && line.has(GcodeProgram.Marker.DOT_END)) {
                inDot = false;
                if (dotKnown && pos != null) {
                    dot(moved - dotMovedBefore);
                }
            }
        }

        private void dot(long net) {
            String where = "line " + dotLine + " '" + dotText + "': ";
            if (dotPlanned > 0 && net != dotPlanned) {
                p.add(where + "dot " + dotIndex + " turns " + net + " steps, its plan says " + dotPlanned);
            }
            if (dotIndex == GcodeStreamBuilder.PURGE_INDEX) {
                if (net <= 0 || net > purgeCap) {
                    p.add("line " + dotLine + ": purge dot " + purgeDots + " turns " + net + " steps, "
                            + (net <= 0 ? "no paste" : "more than one revolution and the 20° phase turn"));
                }
                purgeDots++;
                purgeSinceWork.add(net);
                return;
            }
            if (dotIndex < 0 || !blocks) {
                return;
            }
            plannedWork += Math.max(dotPlanned, 0);
            if (!DoseBlocks.isAllowed((int) net, cfg)) {
                p.add(where + "dot " + dotIndex + " turns " + net + " steps: "
                        + (PadType.isAllowedDotSteps((int) net) ? "less than the " + cfg.tip.name + " takes ("
                                + cfg.tip.minDotSteps + " steps at least)"
                                : "not a quarter, half or whole turn (" + rev + " steps)"));
            }
            boolean switched = !purgeSinceWork.isEmpty();
            boolean start = lastWorkNet == null || switched;
            if (lastWorkNet != null && net != lastWorkNet) {
                int flush = cfg.padCalibration.flushDots;
                boolean flushed = purgeSinceWork.size() >= flush;
                for (int i = purgeSinceWork.size() - flush; flushed && i < purgeSinceWork.size(); i++) {
                    flushed = purgeSinceWork.get(i) == net;
                }
                if (!switched || !flushed) {
                    p.add("dots " + lastWorkIndex + " and " + dotIndex + " have different turns without a block start "
                            + "(phase dot and flush) between them");
                }
            }
            if (start) {
                block++;
                Long count = countBefore();
                if (count != null && switched && DoseBlocks.isPartial((int) net, rev)) {
                    int a = (int) Math.floorMod(count, (long) rev);
                    int want = cfg.profile.startAngleSteps(cfg);
                    if (a != want) {
                        p.add(String.format(java.util.Locale.ROOT, "block %d (%s) starts at %.1f°, not at the start "
                                + "angle %.1f°", block, DoseBlocks.turnName((int) net), a * 360.0 / rev,
                                want * 360.0 / rev));
                    }
                }
            }
            if (dotPad >= 0) {
                Integer prev = lastDotOfPad.get(dotPad);
                if (prev != null && lastPad != dotPad && splitPads.add(dotPad)) {
                    p.add("pad " + dotPadId + "'s dots are split (dots " + prev + " and " + dotIndex + ")");
                }
                lastDotOfPad.put(dotPad, dotIndex);
            }
            lastPad = dotPad;
            lastWorkNet = net;
            lastWorkIndex = dotIndex;
            purgeSinceWork.clear();
        }

        /** Marlin's step count at rest before the current dot, or null when the program does not know it. */
        private Long countBefore() {
            return prog.bCountAtStart == null ? null : prog.bCountAtStart + cfg.bExtrudeSign * dotMovedBefore;
        }

        void end() {
            if (!blocks) {
                return;
            }
            long allowed = plannedWork + purgeDots * purgeCap;
            if (moved > allowed) {
                p.add("the program turns the screw " + moved + " steps; the plan allows at most " + allowed);
            }
        }
    }

    public static List<String> validate(GcodeProgram prog, DispenseConfig cfg, String letterB) {
        return validate(prog, cfg, letterB, null);
    }

    public static List<String> validate(GcodeProgram prog, DispenseConfig cfg, String letterB, ZWindow zw) {
        List<String> p = new ArrayList<>();
        Pattern zWord = zw == null ? null
                : Pattern.compile("(?:^|\\s)" + Pattern.quote(zw.letterZ) + "([-+]?[0-9]*\\.?[0-9]+)");
        boolean pump = false, valve = false, zeroed = prog.bZeroedAtStart, inDot = false, armed = false;
        Pattern bWord = Pattern.compile("(^|\\s)" + Pattern.quote(letterB) + "[-+]?[0-9.]");
        Pattern otherAxis = Pattern.compile("(^|\\s)(?!" + Pattern.quote(letterB) + ")[A-EG-Z][-+]?[0-9.]");
        Pattern fWord = Pattern.compile("(?:^|\\s)F([-+]?[0-9]*\\.?[0-9]+)");
        Pattern m204 = Pattern.compile("^M204\\s+S([0-9]*\\.?[0-9]+)$");
        double xyzAccelMax = Math.max(cfg.travelAccelMmS2, cfg.zLiftAccelMmS2);
        double accel = Double.NaN;
        Turns turns = new Turns(prog, cfg, letterB, p);
        int n = 0;
        for (GcodeProgram.Line line : prog.lines) {
            n++;
            if (line.isComment()) {
                continue;
            }
            turns.line(n, line);
            String s = line.text.trim();
            String where = "line " + n + " '" + s + "': ";
            String refused = ControllerCommands.check(s);
            if (refused != null) {
                p.add(where + (ControllerCommands.isProbeLine(s)
                        ? "N1 vacuum and I2C lines belong to the probe, never to a dispense program" : refused));
                continue;
            }
            String norm = ControllerCommands.normalize(s);
            if (norm.equals(ControllerCommands.PUMP_ON)) {
                pump = true;
            }
            else if (norm.equals(ControllerCommands.VALVE_ON)) {
                if (!pump) {
                    p.add(where + "valve ON before pump ON");
                }
                valve = true;
            }
            else if (norm.equals(ControllerCommands.VALVE_OFF)) {
                valve = false;
            }
            else if (norm.equals(ControllerCommands.PUMP_OFF)) {
                if (valve) {
                    p.add(where + "pump OFF while valve still ON (valve must close first)");
                }
                pump = false;
            }
            else if (s.matches("G92\\s.*") && bWord.matcher(s.substring(3)).find()) {
                zeroed = true;
            }
            if (zWord != null && s.matches("G[01](\\s.*)?")) {
                java.util.regex.Matcher zm = zWord.matcher(s.substring(2));
                if (zm.find()) {
                    double z = Double.parseDouble(zm.group(1));
                    if (z < zw.lo || z > zw.hi) {
                        p.add(where + "I4: raw Z " + z + " outside the needle-floor / travel-Z window " + zw);
                    }
                }
            }
            java.util.regex.Matcher am = m204.matcher(norm);
            if (am.matches()) {
                accel = Double.parseDouble(am.group(1));
            }
            if (s.matches("G[01](\\s.*)?") && otherAxis.matcher(s.substring(2)).find() && accel > xyzAccelMax + 1e-6) {
                p.add(where + "X/Y/Z move under the screw acceleration M204 S" + GcodeStreamBuilder.f0(accel)
                        + " (XY/Z limit " + GcodeStreamBuilder.f0(xyzAccelMax) + ")");
            }
            if (s.matches("G[01](\\s.*)?") && bWord.matcher(s.substring(2)).find()) {
                java.util.regex.Matcher fm = fWord.matcher(s.substring(2));
                if (fm.find() && Double.parseDouble(fm.group(1)) > cfg.bMaxFeedDegS * 60 + 1e-6) {
                    p.add(where + "B feed above the B limit " + GcodeStreamBuilder.f0(cfg.bMaxFeedDegS * 60)
                            + " deg/min");
                }
                if (!zeroed) {
                    p.add(where + "I2: B move before G92 " + letterB + "0");
                }
                if (!(pump && valve)) {
                    p.add(where + "B move outside a pressure session");
                }
            }
            else if (s.equals("M400") && inDot) {
                p.add(where + "M400 inside a dot (breaks streaming)");
            }
            else if (norm.startsWith("M990 S") && !norm.equals(ControllerCommands.KEEPALIVE_DISARM)) {
                armed = true;
            }
            else if (norm.equals(ControllerCommands.KEEPALIVE_DISARM)) {
                armed = false;
            }
            if (line.has(GcodeProgram.Marker.DOT_BEGIN)) {
                inDot = true;
            }
            if (line.has(GcodeProgram.Marker.DOT_END)) {
                inDot = false;
            }
        }
        turns.end();
        if (pump || valve) {
            p.add("program ends with pneumatics ON");
        }
        if (armed) {
            p.add("program ends with the host watchdog armed");
        }
        return p;
    }
}
