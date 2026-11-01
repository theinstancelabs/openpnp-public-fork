package org.openpnp.instancelabs.dispense;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixed controller commands of the dispenser, and the guard every line the engine or the
 * pressure hold sends must pass. These are constants, not settings (review finding: a settable
 * pump/valve/watchdog string could PWM the pump through its buck, PWM the valve at S180, swap pump
 * and valve, or never vent).
 *
 * <ul>
 * <li>Right pump: VAC2's first line, {@code M106 P2 S255} / {@code M107 P2} (plan §4.2a: ON/OFF only,
 * never PWM through the 12 V buck).</li>
 * <li>Right valve: {@code M106 P3 S255} / {@code M107 P3} (plan §10 item 8: the EV-3-24 needs >= 90 %
 * of 24 V; never S180).</li>
 * <li>Host watchdog (il/safety firmware): {@code M115} capability query, {@code M990 S<ms>} arm,
 * {@code M990} ping, {@code M990 S0} disarm.</li>
 * <li>Stop (bench, = machine.xml DISABLE_COMMAND): {@link #STOP_SEQUENCE} {@code M410, M107 P3,
 * M107 P2, M107 P1, M107 P0, M18 B}: quick-stop, right valve, right pump, left valve, left pump,
 * screw driver off.</li>
 * </ul>
 *
 * <p>{@link #check(String)} allows only the command words the engine needs. M106 must be one of the
 * two ON constants exactly (pump, valve); M107 one of the four OFF constants (P0-P3: switching an
 * output off is always safe). Output-driving commands such as M42/M43/M3-M9 are not on the list.</p>
 */
public final class ControllerCommands {
    private ControllerCommands() {
    }

    public static final String PUMP_ON = "M106 P2 S255";
    public static final String PUMP_OFF = "M107 P2";
    public static final String VALVE_ON = "M106 P3 S255";
    public static final String VALVE_OFF = "M107 P3";
    /** Left (N1) vacuum valve and pump OFF: only ever switched off by the dispenser (Stop). */
    public static final String LEFT_VALVE_OFF = "M107 P1";
    public static final String LEFT_PUMP_OFF = "M107 P0";
    public static final String CAPABILITY_QUERY = "M115";
    public static final String WATCHDOG_CAPABILITY = "Cap:HOST_WATCHDOG:1";
    public static final String KEEPALIVE_PING = "M990";
    public static final String KEEPALIVE_DISARM = "M990 S0";
    /** il/safety firmware: M990 S must be 0 or 100..3600000 ms. */
    public static final int KEEPALIVE_MIN_MS = 100;
    public static final int KEEPALIVE_MAX_MS = 3600000;

    private static final Set<String> OUTPUT_ON = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(PUMP_ON, VALVE_ON)));
    private static final Set<String> OUTPUT_OFF = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(VALVE_OFF, PUMP_OFF, LEFT_VALVE_OFF, LEFT_PUMP_OFF)));

    /**
     * The Stop sequence for a screw on axis letter {@code b} (bench F9; machine.xml DISABLE_COMMAND):
     * quick-stop, right valve OFF (vent first), right pump OFF, left valve OFF, left pump OFF, screw
     * driver off.
     */
    public static List<String> stopSequence(String b) {
        return Collections.unmodifiableList(Arrays.asList("M410", VALVE_OFF, PUMP_OFF, LEFT_VALVE_OFF, LEFT_PUMP_OFF,
                "M18 " + b));
    }

    /** {@link #stopSequence} for the B axis. */
    public static final List<String> STOP_SEQUENCE = stopSequence("B");
    /** Command words (letter + number, leading zeros removed) the engine may send. */
    private static final Set<String> ALLOWED_WORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "G0", "G1", "G4", "G92", "M18", "M106", "M107", "M114", "M115", "M201", "M203", "M204", "M400",
            "M410", "M990")));
    private static final Pattern WORD = Pattern.compile("^([GM])0*(\\d+)(?![0-9.])");
    private static final Pattern ARM = Pattern.compile("^M990 S(\\d{1,9})$");

    /** The host-watchdog arm command for a window of {@code ms}. */
    public static String keepaliveArm(int ms) {
        return "M990 S" + ms;
    }

    /** Upper-case, comment removed, whitespace collapsed. */
    static String normalize(String line) {
        String s = line == null ? "" : line;
        int c = s.indexOf(';');
        if (c >= 0) {
            s = s.substring(0, c);
        }
        return s.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /** The command word of a line ("M106" for "m0106 p2"), or null if it does not start with G or M. */
    static String word(String normalized) {
        Matcher m = WORD.matcher(normalized);
        return m.find() ? m.group(1) + Integer.parseInt(m.group(2)) : null;
    }

    /** True for M106/M107 lines (any form). */
    public static boolean isOutputCommand(String line) {
        String w = word(normalize(line));
        return "M106".equals(w) || "M107".equals(w);
    }

    /**
     * Why the line may not be sent, or null if it may. Comments and blank lines are allowed (the
     * driver strips them).
     */
    public static String check(String line) {
        String s = normalize(line);
        if (s.isEmpty()) {
            return null;
        }
        String w = word(s);
        if (w == null || !ALLOWED_WORDS.contains(w)) {
            return "'" + line + "' is not a command the dispenser sends";
        }
        switch (w) {
            case "M106":
                if (!OUTPUT_ON.contains(s)) {
                    return "'" + line + "': only " + PUMP_ON + " and " + VALVE_ON
                            + " may switch an output on (no PWM, no other port)";
                }
                return null;
            case "M107":
                if (!OUTPUT_OFF.contains(s)) {
                    return "'" + line + "': only " + VALVE_OFF + ", " + PUMP_OFF + ", " + LEFT_VALVE_OFF + " and "
                            + LEFT_PUMP_OFF + " may switch an output off";
                }
                return null;
            case "M115":
                return s.equals(CAPABILITY_QUERY) ? null : "'" + line + "': the capability query is exactly M115";
            case "M990":
                if (s.equals(KEEPALIVE_PING) || s.equals(KEEPALIVE_DISARM)) {
                    return null;
                }
                Matcher m = ARM.matcher(s);
                if (m.matches()) {
                    long ms = Long.parseLong(m.group(1));
                    if (ms >= KEEPALIVE_MIN_MS && ms <= KEEPALIVE_MAX_MS) {
                        return null;
                    }
                }
                return "'" + line + "': M990 must be M990, M990 S0 or M990 S<" + KEEPALIVE_MIN_MS + ".."
                        + KEEPALIVE_MAX_MS + ">";
            default:
                return null;
        }
    }

    /** Throws if {@link #check(String)} refuses the line. */
    public static void requireAllowed(String line) {
        String why = check(line);
        if (why != null) {
            throw new IllegalStateException("refused to send G-code: " + why);
        }
    }
}
