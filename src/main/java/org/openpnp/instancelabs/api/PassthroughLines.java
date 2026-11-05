package org.openpnp.instancelabs.api;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BR-72: the lines the G-code passthrough sends (Process's BR-72 list: what the bench tools send). Hard limits only:
 * the machine's soft limits, X/Y moves once homed (Z before it only to level the see-saw: BR-97), the pump and the
 * valve only full on (plan §4.2a), the screw current ≤ its rated 670 mA; never BR-03's dispensing zones (the tools
 * dispense over pads on purpose). Lines that would leave OpenPnP's state wrong (a home, a frame shift on X/Y/Z, all
 * motors off, relative mode) or reach into the firmware (EEPROM, steps per unit, kill) are refused with the engine's
 * route to use; anything not listed: "ask Backend".
 */
final class PassthroughLines {
    private PassthroughLines() {
    }

    /** The pump's and the valve's M106 index (VAC2: M106 P2 / P3, ControllerCommands). */
    private static final Set<Integer> FULL_ON_ONLY = new HashSet<>(Arrays.asList(2, 3));
    static final double MAX_DWELL_S = 60;
    /** Any number on a line: no Infinity, no 400-digit screw turn (#19 review MINOR 4). */
    static final double MAX_MAGNITUDE = 1_000_000;
    /**
     * The bed's height switch on PA5 (the HACK header's pin 3, LUMEN_AUX_PWM1; wired 11-04): M42 names it P53 (the
     * variant's Arduino pin), M43 the same pin P5 (its entry in the firmware's pin table). The same counts give BR-48's
     * PC0 as M42 P44 and M43 P29, which Process checked on the machine 11-02.
     */
    static final double SWITCH_PIN_M42 = org.openpnp.instancelabs.dispense.HeightSwitch.M42_PIN;
    static final double SWITCH_PIN_M43 = org.openpnp.instancelabs.dispense.HeightSwitch.M43_PIN;
    static final double MAX_SCREW_MA = 670;
    /**
     * BR-97 (Process's numbers, the PM 16:4x): while not homed the see-saw is levelled by Z alone, at most 1 mm per
     * line, 5 mm per call (the caller looks between calls) and 32 mm from where the position became unknown (the
     * see-saw's 0-63: levelling from either end needs up to 31.5), at F 300 or slower.
     */
    static final double LEVEL_STEP_MM = 1;
    static final double LEVEL_CALL_MM = 5;
    static final double LEVEL_RANGE_MM = 32;
    static final double LEVEL_MAX_F = 300;
    /** What a reported Z may differ from its target by (M114 prints a few decimals). */
    private static final double LEVEL_TOL_MM = 1e-4;
    private static final Pattern WORD = Pattern.compile("([A-Z])\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+))?\\s*");

    /** One checked line: {@link #refusal} null when it may be sent as {@link #text}. */
    static final class Line {
        final String text;
        final String refusal;
        /** Nothing to send (blank or a comment). */
        final boolean empty;
        /** A G0/G1: queued motion until an M400. */
        final boolean motion;
        /** M400: queued motion has ended once its ok came. */
        final boolean sync;
        /** Moves an axis or sets its position: OpenPnP's coordinates must be re-read afterwards. */
        final boolean frame;
        /** BR-97: the Z target of a Z-only move while not homed (levelling the see-saw), else null. */
        final Double levelZ;

        Line(String text, String refusal, boolean empty, boolean motion, boolean sync, boolean frame) {
            this(text, refusal, empty, motion, sync, frame, null);
        }

        Line(String text, String refusal, boolean empty, boolean motion, boolean sync, boolean frame, Double levelZ) {
            this.text = text;
            this.refusal = refusal;
            this.empty = empty;
            this.motion = motion;
            this.sync = sync;
            this.frame = frame;
            this.levelZ = levelZ;
        }
    }

    private static Line no(String text, String why) {
        return new Line(text, why, false, false, false, false);
    }

    /**
     * Checks one line. {@code limits}: per axis letter {low, high} in raw controller coordinates, NaN where OpenPnP sets
     * no soft limit; {@code homed}: OpenPnP reads homed (X/Y/Z moves need it).
     */
    static Line check(String raw, Map<Character, double[]> limits, boolean homed) {
        String s = raw == null ? "" : raw;
        int c = s.indexOf(';');
        if (c >= 0) {
            s = s.substring(0, c);
        }
        s = s.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) {
            return new Line("", null, true, false, false, false);
        }
        if (s.startsWith("N") || s.contains("*")) {
            return no(s, "line numbers and checksums are the passthrough's job, not the line's: send the bare command");
        }
        Matcher head = Pattern.compile("^([GM])(\\d+)").matcher(s);
        if (!head.find()) {
            return no(s, "not a G or M command: ask Backend");
        }
        // at most 9 digits: a longer number would overflow the int below (#50 re-review NIT)
        if (head.group(2).length() > 9) {
            return no(s, "command number too long (at most 9 digits)");
        }
        String word = head.group(1) + Integer.parseInt(head.group(2));
        String rest = s.substring(head.end());
        if ("M485".equals(word)) {
            // Photon feeders: an RS-485 packet in hex
            String hex = rest.trim();
            return hex.matches("[0-9A-F]+") ? new Line("M485 " + hex, null, false, false, false, false)
                    : no(s, "M485 takes one hex packet (M485 FFFF0001)");
        }
        Map<Character, Double> p = new LinkedHashMap<>();
        Matcher m = WORD.matcher(rest);
        int at = 0;
        while (at < rest.length()) {
            if (Character.isWhitespace(rest.charAt(at))) {
                at++;
                continue;
            }
            if (!m.find(at) || m.start() != at) {
                return no(s, "cannot read '" + rest.substring(at).trim() + "': one command per line, plain numbers");
            }
            char letter = m.group(1).charAt(0);
            if (letter == 'G' || letter == 'M' || letter == 'T' && !"M42".equals(word) && !"M43".equals(word)
                    && !"M204".equals(word)) {
                return no(s, "one command per line (" + letter + " after " + word + ")");
            }
            if (p.containsKey(letter)) {
                return no(s, letter + " twice");
            }
            String num = m.group(2);
            Double v = num == null ? null : Double.valueOf(num);
            if (v != null && (v.isInfinite() || v.isNaN() || Math.abs(v) > MAX_MAGNITUDE)) {
                return no(s, letter + " " + num + " is out of range (at most " + (long) MAX_MAGNITUDE + " here)");
            }
            p.put(letter, v);
            at = m.end();
        }
        String text = word + render(p);
        String why = rule(word, p, limits, homed);
        if (why != null) {
            return no(text, why);
        }
        boolean motion = "G0".equals(word) || "G1".equals(word);
        // M410 flushes queued moves (none pending after it) and may stop one short: OpenPnP re-reads the position
        boolean quickStop = "M410".equals(word);
        return new Line(text, null, false, motion, "M400".equals(word) || quickStop,
                motion || "G92".equals(word) || quickStop, motion && !homed ? p.get('Z') : null);
    }

    /**
     * BR-97: why a levelling move from Z {@code from} to {@code to} may not go out, or null. {@code first}: the Z where
     * this call began; {@code ref}: where the position became unknown (the engine's ControllerReset.levelReference).
     */
    static String level(double from, double to, double first, double ref) {
        if (Math.abs(to - from) > LEVEL_STEP_MM + LEVEL_TOL_MM) {
            return String.format(Locale.ROOT, "Z %s is %s mm from Z %s before it: at most %s mm per line while not "
                    + "homed", plain(to), plain(Math.abs(to - from)), plain(from), plain(LEVEL_STEP_MM));
        }
        if (Math.abs(to - first) > LEVEL_CALL_MM + LEVEL_TOL_MM) {
            return String.format(Locale.ROOT, "Z %s is more than %s mm from Z %s, where this call began: look, then "
                    + "send the next call", plain(to), plain(LEVEL_CALL_MM), plain(first));
        }
        if (Math.abs(to - ref) > LEVEL_RANGE_MM + LEVEL_TOL_MM) {
            return String.format(Locale.ROOT, "Z %s is more than %s mm from Z %s, where the position became unknown "
                    + "(after a reset the controller's boot Z 0, else where the levelling began; the see-saw's whole "
                    + "travel is 63 mm)", plain(to), plain(LEVEL_RANGE_MM), plain(ref));
        }
        return null;
    }

    private static String render(Map<Character, Double> p) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Character, Double> e : p.entrySet()) {
            sb.append(' ').append(e.getKey());
            if (e.getValue() != null) {
                double v = e.getValue();
                sb.append(v == Math.rint(v) && Math.abs(v) < 1e15 ? String.valueOf((long) v)
                        : stripZeros(String.format(Locale.ROOT, "%.6f", v)));
            }
        }
        return sb.toString();
    }

    private static String stripZeros(String s) {
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    /** Why the command may not go out, or null. */
    private static String rule(String word, Map<Character, Double> p, Map<Character, double[]> limits, boolean homed) {
        switch (word) {
            case "G0":
            case "G1":
                return move(word, p, limits, homed);
            case "G4": {
                String bad = only(word, p, "PS", true);
                if (bad != null) {
                    return bad;
                }
                double sec = p.containsKey('S') ? p.get('S') : p.containsKey('P') ? p.get('P') / 1000.0 : 0;
                if (sec < 0) {
                    return "G4 takes no negative time (the firmware would read it as days)";
                }
                return sec > MAX_DWELL_S ? "G4 at most " + (int) MAX_DWELL_S + " s per line" : null;
            }
            case "G90":
            case "M400":
            case "M115":
            case "M119":
            case "M410":
                return p.isEmpty() ? null : word + " takes no parameters here";
            case "M114":
                return only(word, p, "DER", false);
            case "G91":
                return "G91 (relative moves) would leave the controller relative under OpenPnP's absolute moves: send "
                        + "absolute coordinates";
            case "G28":
                return "G28 is the engine's: home with POST /api/machine/home and your token (the engine's safe home: N1 "
                        + "raised first, OpenPnP's frame and visual homing kept)";
            case "G92":
                if (p.isEmpty() || !keys(p).chars().allMatch(ch -> ch == 'A' || ch == 'B')) {
                    return "G92 only with A and/or B here (G92 B0): a G92 on X, Y or Z shifts OpenPnP's frame; home with "
                            + "POST /api/machine/home instead";
                }
                return values(word, p);
            case "M106":
            case "M107": {
                String bad = only(word, p, "M107".equals(word) ? "P" : "PS", true);
                if (bad != null) {
                    return bad;
                }
                double pin = p.get('P') == null ? 0 : p.get('P');
                if (pin != Math.rint(pin) || pin < 0 || pin > 3) {
                    return word + " on P0-P3 only (P0/P1 N1's pump and valve, P2 the pump, P3 the valve)";
                }
                if ("M106".equals(word) && p.get('S') != null) {
                    double sv = p.get('S');
                    if (FULL_ON_ONLY.contains((int) pin) && sv != 255) {
                        return "the pump (P2) and the valve (P3) switch only full on (S255): never PWM the pump through "
                                + "its buck, and the valve needs at least 90 % of 24 V (plan §4.2a)";
                    }
                    if (sv < 0 || sv > 255) {
                        return "M106 S is 0..255";
                    }
                }
                return null;
            }
            case "M150":
                return only(word, p, "RUBWPIKS", true);
            case "M204": {
                String bad = only(word, p, "SPTR", true);
                if (bad != null) {
                    return bad;
                }
                for (Double v : p.values()) {
                    if (v <= 0) {
                        return "M204 accelerations are positive";
                    }
                }
                return null;
            }
            case "M201":
                return p.size() == 1 && p.containsKey('B') && p.get('B') != null && p.get('B') > 0 ? null
                        : "M201 on B only (M201 B10000): X, Y and Z accelerations are OpenPnP's";
            case "M906":
                if (p.isEmpty()) {
                    return null;
                }
                if (p.size() != 1 || !p.containsKey('B') || p.get('B') == null) {
                    return "M906 on B only (the screw: M906 B580)";
                }
                return p.get('B') <= 0 || p.get('B') > MAX_SCREW_MA
                        ? "M906 B at most " + (int) MAX_SCREW_MA + " mA (the auger motor's rating)" : null;
            case "M17":
            case "M18":
            case "M84":
                return p.size() == 1 && p.containsKey('B') && p.get('B') == null && !"M84".equals(word) ? null
                        : word + " only as M17 B / M18 B: other motors off lose OpenPnP's position";
            case "M260":
                return only(word, p, "ABSR", true);
            case "M261":
                return only(word, p, "ABS", true);
            case "M42": {
                String bad = only(word, p, "IPT", false);
                if (bad != null) {
                    return bad;
                }
                Double t = p.get('T');
                if (p.get('P') == null || p.get('P') != SWITCH_PIN_M42) {
                    return "M42 only on the height switch's pin here (PA5: M42 P53 T2)";
                }
                return t == null || !(t == 0 || t == 2 || t == 3)
                        ? "M42 only sets an input mode here (M42 P53 T2): T0, T2 or T3" : null;
            }
            case "M43":
                if (only(word, p, "IP", false) != null || p.get('P') == null || p.get('P') != SWITCH_PIN_M43) {
                    return "M43 only reads the height switch's pin here (PA5: M43 P5)";
                }
                return null;
            default:
                return word + " is not on the passthrough's list (" + firmwareNote(word) + "): ask Backend";
        }
    }

    private static String firmwareNote(String word) {
        switch (word) {
            case "M92":
                return "steps per unit are the machine's calibration";
            case "M500":
            case "M501":
            case "M502":
            case "M503":
                return "the controller's EEPROM";
            case "M112":
            case "M999":
                return "kills or restarts the firmware: use STOP";
            case "M997":
                return "firmware update";
            case "M990":
                return "the engine's host watchdog";
            default:
                return "not used by the bench tools";
        }
    }

    private static String move(String word, Map<Character, Double> p, Map<Character, double[]> limits, boolean homed) {
        String bad = only(word, p, "XYZABF", true);
        if (bad != null) {
            return bad;
        }
        // BR-97: before the home only the see-saw's levelling moves Z (its walk: level())
        boolean levelling = !homed && p.containsKey('Z');
        for (char axis : new char[] {'X', 'Y', 'Z', 'A', 'B'}) {
            Double v = p.get(axis);
            if (v == null) {
                continue;
            }
            if (!homed && (axis == 'X' || axis == 'Y')) {
                return "X and Y move only once homed: home with POST /api/machine/home and your token (before it a "
                        + "line may move Z alone, to level the see-saw)";
            }
            if (levelling && axis != 'Z') {
                return "while not homed a line moves Z alone (levelling the see-saw): " + axis + " waits for the home";
            }
            double[] lim = limits.get(axis);
            if (lim != null && (!Double.isNaN(lim[0]) && v < lim[0] || !Double.isNaN(lim[1]) && v > lim[1])) {
                return String.format(Locale.ROOT, "%c %s is outside the machine's limits %s..%s", axis, plain(v),
                        Double.isNaN(lim[0]) ? "-" : plain(lim[0]), Double.isNaN(lim[1]) ? "-" : plain(lim[1]));
            }
        }
        // G0 keeps F only while marlin-lumen's G0_FEEDRATE stays commented out (BR-97 review)
        if (levelling && "G0".equals(word)) {
            return "while not homed Z moves by G1 alone (levelling the see-saw: G1 Z1 F300): a G0 may run at the "
                    + "firmware's own speed, not F";
        }
        Double f = p.get('F');
        if (f != null && f <= 0) {
            return "F must be positive";
        }
        return levelling && (f == null || f > LEVEL_MAX_F) ? "a Z move while not homed (levelling the see-saw) needs F "
                + "at most " + plain(LEVEL_MAX_F) + " (G1 Z1 F300)" : null;
    }

    /** Null when every parameter is one of {@code letters} (with a value when {@code valued}). */
    private static String only(String word, Map<Character, Double> p, String letters, boolean valued) {
        for (Map.Entry<Character, Double> e : p.entrySet()) {
            if (letters.indexOf(e.getKey()) < 0) {
                return word + " takes " + String.join(", ", letters.split("")) + " here, not " + e.getKey();
            }
            if (valued && e.getValue() == null) {
                return word + " " + e.getKey() + " needs a number";
            }
        }
        return null;
    }

    private static String values(String word, Map<Character, Double> p) {
        for (Map.Entry<Character, Double> e : p.entrySet()) {
            if (e.getValue() == null) {
                return word + " " + e.getKey() + " needs a number";
            }
        }
        return null;
    }

    private static String keys(Map<Character, Double> p) {
        StringBuilder sb = new StringBuilder();
        for (char k : p.keySet()) {
            sb.append(k);
        }
        return sb.toString();
    }

    private static String plain(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : stripZeros(String.format(Locale.ROOT, "%.4f", v));
    }
}
