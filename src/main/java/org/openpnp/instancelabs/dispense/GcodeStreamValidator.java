package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Static safety check of a generated program before it is streamed. Deterministic rules:
 * <ul>
 * <li>Every line is a command the dispenser sends ({@link ControllerCommands#check}): M106/M107 only
 * as the four fixed pump/valve commands (no PWM, no other port), no M42/M43 or other output
 * commands, the watchdog only as M990 / M990 S0 / M990 S&lt;window&gt;.</li>
 * <li>Valve ON only while the pump is ON; valve OFF before pump OFF (plan §4.5).</li>
 * <li>I2: no B move before G92 B0 unless B was already zeroed since home/reconnect.</li>
 * <li>B moves only inside a pressure session (pump and valve ON).</li>
 * <li>No X/Y/Z move while the screw acceleration (M204 S above the XY/Z one) is in force: M204 S also
 * governs XY and Z, so the screw's 20000 deg/s² must only ever cover the B moves.</li>
 * <li>No B feed above the B limit (bMaxFeedDegS, at most 400 rpm).</li>
 * <li>No M400 between a dot's first and last line (no per-dot synchronisation).</li>
 * <li>The program ends with valve and pump OFF and the watchdog disarmed.</li>
 * </ul>
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
        double floor = cfg.needleFloorZMm();
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
        int n = 0;
        for (GcodeProgram.Line line : prog.lines) {
            n++;
            if (line.isComment()) {
                continue;
            }
            String s = line.text.trim();
            String where = "line " + n + " '" + s + "': ";
            String refused = ControllerCommands.check(s);
            if (refused != null) {
                p.add(where + refused);
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
        if (pump || valve) {
            p.add("program ends with pneumatics ON");
        }
        if (armed) {
            p.add("program ends with the host watchdog armed");
        }
        return p;
    }
}
