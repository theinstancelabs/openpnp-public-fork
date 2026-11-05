package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BR-94: the bed's height switch, pressed by N1 or the needle, in the steps of Process's bench tool tools/switchz.py.
 * The switch is on PA5 (the HACK header's pin 3, LUMEN_AUX_PWM1; GND on pin 2): {@code M42 P53 T2} sets its pull-up
 * (M42 takes the Arduino pin) and {@code M43 P5} reads it (M43 takes the pin table's entry). The pull-up is set again
 * before every read: M43 may read an analog-capable pin through analogRead, which leaves it in analog mode. The level
 * read with both tools at travel height is the idle level, so a normally open and a normally closed switch both work.
 *
 * <p>One press: a coarse descent from the start (N1 0.25 mm, the needle 0.10) to the first step that reads pressed,
 * 0.40 mm back (still pressed there: the switch sticks or the tool is caught, refused), then a fine descent of 0.025 mm
 * from 0.30 above that step to one fine step past it: the trip is the first fine step that reads pressed, so the tool
 * goes at most one coarse and one fine step past the plunger's trip, and never past the floor. The next press starts 1 mm above the last trip. Every step is
 * a move, M400 and 50 ms before its read. Raw Z: N1 goes down as raw Z falls, the needle (the see-saw's other side) as
 * it rises ({@link Plan#down}). Travel Z at the end, also after a refusal; an exception from the link (a STOP's abort,
 * a lost link) ends it as it is.</p>
 */
public final class HeightSwitch {
    /** The named location of the switch's top centre ({@code PUT /api/locations/height-switch {x, y}}). */
    public static final String PLACE = "height-switch";
    /** PA5 for M42 (the MARLIN_F407VE variant's Arduino pin) and for M43 (its entry in the firmware's pin table). */
    public static final int M42_PIN = 53;
    public static final int M43_PIN = 5;
    public static final double FINE_MM = 0.025;
    /** The fine descent starts this far above the coarse trip. */
    public static final double FINE_FROM_MM = 0.30;
    /** How far back from the coarse trip the switch must read released. */
    public static final double BACK_MM = 0.40;
    /** The lift after a press: the next press starts here. */
    public static final double LIFT_MM = 1.0;
    /** The longest start-to-floor window (a first search over the switch's unknown height is the bench tool's). */
    public static final double MAX_WINDOW_MM = 2.0;
    /** Accepted (recorded as the tool's trip) with at least this many presses within {@link #MAX_RANGE_MM}. */
    public static final int MIN_ACCEPTED = 5;
    public static final double MAX_RANGE_MM = 0.05;
    private static final Pattern INPUT = Pattern.compile("Input\\s*=\\s*(\\d+|HIGH|LOW)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_AN_INPUT = Pattern.compile("Analog in|Output|protected", Pattern.CASE_INSENSITIVE);

    private HeightSwitch() {
    }

    /** The controller link: one line and its ok; one line and its replies. */
    public interface Io {
        void send(String line) throws Exception;

        List<String> query(String line) throws Exception;
    }

    /** One measure: the tool's head position over the switch and the window, all raw controller mm. */
    public static final class Plan {
        /** "n1" or "needle" (the result's name only). */
        public String tool;
        public double rawX, rawY, travelZ;
        /** −1: the tool goes down as raw Z falls (N1); +1: as it rises (the needle). */
        public int down;
        public double zStart, zFloor;
        /** The coarse step: 0.25 for N1, 0.10 for the needle. */
        public double coarseMm;
        public int presses;
    }

    /** What a measure found. {@code why}: why nothing (or not everything) was found; {@code refused}: a refusal. */
    public static final class Result {
        public String tool;
        public List<Double> trips = new ArrayList<>();
        public Double tripRawZ, sdMm, rangeMm;
        public Integer idleLevel;
        public boolean accepted, refused;
        public String why;
        /** The engine's: the presses asked for, the home it was measured under, when it ended. */
        public Integer presses;
        public Long homeAtMs, atMs;
    }

    /** A read of the pin with the machine idle: its level and when, or (level null) why there is none. */
    public static final class Read {
        public Integer level;
        public Long atMs;
        public String why;
    }

    /** −1 when the tool goes down as raw Z falls (N1), +1 as it rises (the needle): its mapper 1 mm lower at (x, y). */
    public static int down(RawMapper m, double x, double y, double z) throws Exception {
        return (int) Math.signum(m.toRaw(x, y, z - 1).z - m.toRaw(x, y, z).z);
    }

    /** Measures, then sends the tool back to travel Z (also after a refusal or a trip not found). */
    public static Result measure(Io io, Plan p) throws Exception {
        return measure(io, p, new Result());
    }

    /** As above, into {@code r} (the engine's task result). */
    public static Result measure(Io io, Plan p, Result r) throws Exception {
        r.tool = p.tool;
        String bad = windowProblem(p);
        if (bad != null) {
            r.refused = true;
            r.why = bad;
            return r;
        }
        presses(io, p, r);
        io.send(move(p.travelZ, 600));
        io.send("M400");
        if (!r.trips.isEmpty()) {
            double sum = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (double t : r.trips) {
                sum += t;
                min = Math.min(min, t);
                max = Math.max(max, t);
            }
            r.tripRawZ = sum / r.trips.size();
            double v = 0;
            for (double t : r.trips) {
                v += (t - r.tripRawZ) * (t - r.tripRawZ);
            }
            r.sdMm = Math.sqrt(v / r.trips.size());
            r.rangeMm = max - min;
            if (r.why == null && r.rangeMm > MAX_RANGE_MM + 1e-9) {
                r.why = String.format(Locale.ROOT, "the presses spread %.3f mm (at most %.2f to record the trip)",
                        r.rangeMm, MAX_RANGE_MM);
            }
        }
        r.accepted = r.why == null && r.trips.size() >= MIN_ACCEPTED;
        return r;
    }

    /** Why the plan's window is refused before any line (the wrong order, wider than 2 mm, above travel), or null. */
    static String windowProblem(Plan p) {
        double span = p.down * (p.zFloor - p.zStart);
        if (!(span > 0)) {
            return String.format(Locale.ROOT, "the floor (Z %.3f) must lie below the start (Z %.3f) for %s", p.zFloor,
                    p.zStart, p.tool);
        }
        if (span > MAX_WINDOW_MM + 1e-9) {
            return String.format(Locale.ROOT, "the window from Z %.3f to %.3f is %.2f mm: at most %.1f mm (a search "
                    + "over an unknown height is the bench tool's)", p.zStart, p.zFloor, span, MAX_WINDOW_MM);
        }
        if (p.down * (p.zStart - p.travelZ) < 0) {
            return String.format(Locale.ROOT, "the start (Z %.3f) lies above the travel height (Z %.3f)", p.zStart,
                    p.travelZ);
        }
        return null;
    }

    private static void presses(Io io, Plan p, Result r) throws Exception {
        io.send(move(p.travelZ, 600));
        io.send(String.format(Locale.ROOT, "G1 X%.3f Y%.3f F6000", p.rawX, p.rawY));
        io.send("M42 P" + M42_PIN + " T2");
        io.send("M400");
        io.send("G4 P300");
        r.idleLevel = read(io); // both tools up: nothing presses the switch here
        io.send(move(p.zStart, 600));
        io.send("M400");
        io.send("G4 P200");
        if (read(io) != r.idleLevel) {
            r.refused = true;
            r.why = String.format(Locale.ROOT, "the switch is already pressed at the start (Z %.3f): start higher, or "
                    + "the switch is stuck or wired wrong", p.zStart);
            return;
        }
        double from = p.zStart;
        for (int i = 0; i < p.presses; i++) {
            Double zc = descend(io, p, from, p.zFloor, p.coarseMm, r.idleLevel);
            if (zc == null) {
                r.why = String.format(Locale.ROOT, "no trip between Z %.3f and Z %.3f", from, p.zFloor);
                return;
            }
            io.send(move(zc - p.down * BACK_MM, 600));
            io.send("M400");
            io.send("G4 P200");
            if (read(io) != r.idleLevel) {
                r.refused = true;
                r.why = String.format(Locale.ROOT, "still pressed %.2f mm above the coarse trip (Z %.3f): the switch "
                        + "sticks or the tool is caught", BACK_MM, zc);
                return;
            }
            // one fine step past the coarse trip: a press that does not repeat (a bounce, noise) goes no deeper
            double end = p.down < 0 ? Math.max(zc - FINE_MM, p.zFloor) : Math.min(zc + FINE_MM, p.zFloor);
            Double t = descend(io, p, zc - p.down * FINE_FROM_MM, end, FINE_MM, r.idleLevel);
            if (t == null) {
                r.why = String.format(Locale.ROOT, "the fine descent from Z %.3f found no trip (the coarse one did at Z "
                        + "%.3f)", zc - p.down * FINE_FROM_MM, zc);
                return;
            }
            r.trips.add(t);
            from = round(t - p.down * LIFT_MM);
            io.send(move(from, 600));
            io.send("M400");
        }
    }

    /**
     * Steps from {@code from} toward {@code to} until the pin leaves its idle level: that step's Z, or null. Never past
     * {@code to}: the last step is {@code to} itself, shorter when the span is no whole number of steps.
     */
    private static Double descend(Io io, Plan p, double from, double to, double step, int idle) throws Exception {
        int n = (int) Math.ceil(Math.abs(to - from) / step - 1e-9);
        for (int k = 0; k <= n; k++) {
            double z = k == n ? to : round(from + p.down * k * step);
            io.send(move(z, 300));
            io.send("M400");
            io.send("G4 P50");
            if (read(io) != idle) {
                return z;
            }
        }
        return null;
    }

    /** The pin's level, the pull-up set again first. */
    private static int read(Io io) throws Exception {
        io.send("M42 P" + M42_PIN + " T2");
        return level(io.query("M43 P" + M43_PIN));
    }

    /**
     * PA5's level in an M43 reply: 1 high, 0 low, from its "Input = 0|1". Throws when the reply names no PA5 or no
     * input level: "Analog in" (the pin left input mode, so no pull-up), "Output" (something drives it) and
     * "protected" (the firmware will not read it) give no level to trust (the PA5 source check, 11-04).
     */
    public static Integer level(List<String> reply) {
        String t = String.join(" ", reply);
        if (!t.contains("PA5")) {
            throw new IllegalStateException("the M43 reply does not name PA5: " + abbreviate(t));
        }
        Matcher bad = NOT_AN_INPUT.matcher(t);
        if (bad.find()) {
            throw new IllegalStateException("PA5 is not a readable input (" + bad.group() + "): " + abbreviate(t));
        }
        Matcher m = INPUT.matcher(t);
        if (m.find()) {
            String v = m.group(1).toUpperCase(Locale.ROOT);
            return "LOW".equals(v) || "0".equals(v) ? 0 : 1;
        }
        throw new IllegalStateException("no pin level in the M43 reply: " + abbreviate(t));
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private static String move(double z, int feed) {
        return String.format(Locale.ROOT, "G1 Z%.3f F%d", z, feed);
    }

    private static double round(double z) {
        return Math.round(z * 1000) / 1000.0;
    }
}
