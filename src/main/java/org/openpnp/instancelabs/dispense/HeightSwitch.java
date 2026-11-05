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
 * Every measure, N1's too, switches the needle's valve and then its pump off before it moves (the passthrough may have
 * left them on): paste must never touch the switch, nor N1 carry a pressurised needle across the bed.
 *
 * <p>The way down from travel Z to the start is stepped and read as a coarse descent is (a press on it is refused:
 * start higher), so a start past the trip goes at most one coarse step past it. The switch is a hinge lever pressed
 * near its free end: it lets go well above its trip (its movement differential times the lever ratio). One press
 * (Process's rule, 11-04): a coarse descent from the start (N1 0.25 mm, the needle 0.10) to the first step that reads
 * pressed; lifts of 0.25 mm until it reads released (still pressed 2.5 mm up: the switch sticks or the tool is caught,
 * refused); straight to 0.30 above that step, above the operating point (within one coarse step above it), where a
 * snap switch stays released (pressed there: erratic, refused); then a fine descent of 0.025 mm to one fine step past
 * that step: the trip is the first fine step that reads pressed, so the tool goes at most one coarse and one fine step
 * past the plunger's trip, and never past the floor. Lifts of 0.25 mm again until it reads released: the press's
 * release (the same refusal); the differential is the mean of |release − trip|, an upper bound, at most 0.275 mm over
 * (a 0.25 mm lift plus the 0.025 mm fine step). The next press starts 1 mm above the trip (it stays released between
 * the trip and the release). No move goes above travel Z (the lifts and the fine start stop there): past it the see-saw
 * lowers the other tool; still pressed at travel Z is refused as that (it sticks, or it releases only above travel Z).
 * Every step is a move, M400 and a dwell before its read (a descent's 50 ms, a lift's 100, the fine start's 200, as the
 * bench tool's). Raw Z: N1 goes down as raw Z falls, the needle (the see-saw's other side) as it rises
 * ({@link Plan#down}). Travel Z at the end, also after a refusal and after a failure (a reply with no level twice, a
 * line that timed out: the tool goes up, Z only, before the failure is thrown); a STOP's abort ends it where it is.</p>
 */
public final class HeightSwitch {
    /** The named location of the switch's top centre ({@code PUT /api/locations/height-switch {x, y}}). */
    public static final String PLACE = "height-switch";
    /** No place, or one without its set time (no trip counts there): the measure and the window say so first. */
    public static final String PLACE_NOT_SET = "the height switch's place is not set: PUT /api/locations/" + PLACE
            + " {x, y}, the switch's top centre";
    /** PA5 for M42 (the MARLIN_F407VE variant's Arduino pin) and for M43 (its entry in the firmware's pin table). */
    public static final int M42_PIN = 53;
    public static final int M43_PIN = 5;
    public static final double FINE_MM = 0.025;
    /** The fine descent starts this far above the coarse trip. */
    public static final double FINE_FROM_MM = 0.30;
    /** After a trip the tool lifts in steps of this until the switch reads released, at most {@link #RELEASE_MAX_MM}. */
    public static final double RELEASE_STEP_MM = 0.25;
    public static final double RELEASE_MAX_MM = 2.5;
    /** The lift after a press: the next press starts here. */
    public static final double LIFT_MM = 1.0;
    /** The longest start-to-floor window (a first search over the switch's unknown height is the bench tool's). */
    public static final double MAX_WINDOW_MM = 2.0;
    /** Accepted (recorded as the tool's trip) with at least this many presses within {@link #MAX_RANGE_MM}. */
    public static final int MIN_ACCEPTED = 5;
    public static final double MAX_RANGE_MM = 0.05;
    /** The needle's measure without {@code heightSwitchMaxPastTripMm}. */
    public static final String NO_MAX_PAST_TRIP = "Process sets how far past the trip the switch may be pressed: "
            + "heightSwitchMaxPastTripMm is not set (PUT /api/config)";
    /** The needle's measure without N1's trip of this home (the needle's window is K − N1's trip). */
    public static final String PRESS_N1_FIRST = "press N1 on the switch first: N1 has no accepted trip on the height "
            + "switch in this home since its place was set";
    /**
     * A window from K: this far above and past the trip it expects (mm): kParallax on a needle's first press since a
     * change, its K_switch on a press in another home (Process 11-05 04:3x).
     */
    public static final double K_WINDOW_MM = 1.0;
    /** A window from the needle's own trip in this home: 1.0 above ({@link #LIFT_MM}) and this far past it (mm). */
    public static final double PAST_MM = 0.5;
    /**
     * The needle's accepted trip, or null when it was measured before the needle now on was put on: another tip's trip
     * gives neither a window nor K_switch (#60 review).
     */
    public static DispenseConfig.HeightSwitchTrip needleTrip(DispenseConfig c) {
        DispenseConfig.HeightSwitchTrip t = c.heightSwitch.needle;
        return t == null || c.needleChangedAtMs != null && t.atMs < c.needleChangedAtMs ? null : t;
    }

    /**
     * The trip counts in home {@code home}: measured under it, after the switch's place was last set (re-review MINOR
     * 1: another point of the lever trips at another height). None counts without the place's set time.
     */
    public static boolean counts(DispenseConfig c, DispenseConfig.HeightSwitchTrip t, Long home) {
        DispenseConfig.NamedLocation place = c.location(PLACE);
        return t != null && home != null && home.equals(t.homeAtMs) && place != null && place.setAtMs != null
                && t.atMs > place.setAtMs;
    }

    private static final Pattern INPUT = Pattern.compile("Input\\s*=\\s*(\\d+|HIGH|LOW)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_AN_INPUT = Pattern.compile("Analog in|Output|protected", Pattern.CASE_INSENSITIVE);

    private HeightSwitch() {
    }

    /** The controller link: one line and its ok; one line and its replies. */
    public interface Io {
        void send(String line) throws Exception;

        List<String> query(String line) throws Exception;

        /** Press {@code n} of {@code of} begins (the task's progress). */
        default void press(int n, int of) {
        }
    }

    /** One measure: the tool's head position over the switch and the window, all raw controller mm. */
    public static final class Plan {
        /** "n1" or "needle": the result's name. */
        public String tool;
        public double rawX, rawY, travelZ;
        /** −1: the tool goes down as raw Z falls (N1); +1: as it rises (the needle). */
        public int down;
        public double zStart, zFloor;
        /** The coarse step: 0.25 for N1, 0.10 for the needle. */
        public double coarseMm;
        /** X/Y over the switch is approached from (−this, −this) mm, as the end measure's frames (0: straight). */
        public double approachMm;
        public int presses;
        /** Raw Z's soft limits {low, high} (NaN: that side open), null: none: the window lies inside them. */
        public double[] zLimits;
        /** Raw Z's safe zone {low, high} (NaN: that side open), null: none: travel Z lies in it. */
        public double[] safeZone;
        /**
         * The needle's (null for N1: no cap): the trip its window expects (raw Z) and how far past it the tool may go
         * ({@code heightSwitchMaxPastTripMm}): the floor is never further, and no trip by the floor is refused.
         */
        public Double expectedTrip, maxPastTripMm;

        boolean capped() {
            return expectedTrip != null && maxPastTripMm != null;
        }

        /** The floor the descents stop at: {@link #zFloor}, or the cap past the expected trip where that is nearer. */
        double floor() {
            if (!capped()) {
                return zFloor;
            }
            double cap = round(expectedTrip + down * maxPastTripMm);
            return down * (zFloor - cap) > 0 ? cap : zFloor;
        }
    }

    /** What a measure found. {@code why}: why nothing (or not everything) was found; {@code refused}: a refusal. */
    public static final class Result {
        public String tool;
        /** Each press's trip and where it read released on the lifts after it (raw Z, one of each per press). */
        public List<Double> trips = new ArrayList<>(), releases = new ArrayList<>();
        /**
         * {@code differentialMm}: the mean of |release − trip|, an upper bound, at most 0.275 mm over (a 0.25 mm lift
         * plus the 0.025 mm fine step), null with no press.
         */
        public Double tripRawZ, sdMm, rangeMm, differentialMm;
        public Integer idleLevel;
        public boolean accepted, refused;
        public String why;
        /** The engine's: the presses asked for, the home it was measured under, when it ended. */
        public Integer presses;
        public Long homeAtMs, atMs;
        /**
         * A refusal's code and what it offers (the needle's end from below: needle-end-not-found, needle-end-paste;
         * needle-changed: a needle change stopped the press).
         */
        public String code, next;
        /** The refusal's picture (a photo id). */
        public Long photoId;
        /** The needle's: its end measured from below in the same task right before the press, the aim, the window. */
        public DispenseConfig.NeedleEnd needleEnd;
        public Aim aim;
        public Window window;
        /** Which needle (the needle token then) and tip kind; whether it is the tool's trip in use; marked for review. */
        public Long needleSinceMs;
        public String setupKey, tipName;
        public boolean inUse, forReview;
    }

    /** Where the needle's press aimed: the end at the press height (from the head, mm) and the head's raw X/Y. */
    public static final class Aim {
        public double atRawZ, endDxMm, endDyMm, rawX, rawY;
    }

    /** The needle's window (raw Z) as the engine derives it, or why there is none. */
    public static final class Window {
        public Double zStart, zFloor, expectedRawZ;
        /** trip (its own trip in this home), kSwitch (its last trip's, of another home) or kParallax (a first press). */
        public String from;
        public String why;
    }

    /**
     * BR-94: the needle's window, from the engine's records only (review MINOR 3, Process 11-05 04:3x), of trips that
     * count ({@link #counts}). In this home from its own trip there, 1.0 above and 0.5 past; with a trip of another
     * home or of the place before its K_switch − N1's trip in this home, ± 1 mm; its first press since a change
     * (kParallax − N1's trip in this home) ± 1 mm. The floor is never more than
     * {@code heightSwitchMaxPastTripMm} past the expected trip. In this home a fresh kParallax more than
     * {@code needleEndMeasure.maxKFromSwitchMm} from its K_switch gives none (review MINOR 2: a constant K error, N1's
     * template on its holder or a slipped tip, never sets a window). {@code end}: the needle's end measured now, or null;
     * {@code down}: +1 when the needle goes down as raw Z rises. {@code from} is set also when there is no window.
     */
    public static Window needleWindow(DispenseConfig c, DispenseConfig.NeedleEnd end, Long home, int down) {
        Window w = new Window();
        DispenseConfig.HeightSwitchTrip n1 = c.heightSwitch.n1, own = needleTrip(c);
        DispenseConfig.NamedLocation place = c.location(PLACE);
        if (place == null || place.setAtMs == null) {
            w.why = PLACE_NOT_SET; // as the press refuses it first, not "press N1 first" (#110 review NIT 1)
            return w;
        }
        if (c.heightSwitchMaxPastTripMm == null) {
            w.why = NO_MAX_PAST_TRIP;
            return w;
        }
        if (!counts(c, n1, home)) {
            w.why = PRESS_N1_FIRST;
            return w;
        }
        Double k = end == null ? null : end.kParallax;
        double past = K_WINDOW_MM;
        if (counts(c, own, home)) {
            w.from = "trip";
            w.expectedRawZ = round(own.tripRawZ);
            past = PAST_MM;
            double max = c.needleEndMeasure.maxKFromSwitchMm;
            if (k != null && own.kSwitch != null && !(Math.abs(k - own.kSwitch) <= max)) {
                w.why = String.format(Locale.ROOT, "K by parallax (%.3f) is %.3f mm from this needle's K_switch (%.3f) "
                        + "in this home, at most %.2f (needleEndMeasure.maxKFromSwitchMm): a slipped tip, or N1 not "
                        + "found at its end; the needle does not press", k, Math.abs(k - own.kSwitch), own.kSwitch, max);
                return w;
            }
        }
        else if (own != null && own.kSwitch != null) {
            w.from = "kSwitch";
            w.expectedRawZ = round(own.kSwitch - n1.tripRawZ);
        }
        else {
            w.from = "kParallax";
            if (k == null) {
                w.why = "the needle's first window comes from its end measured from below: the press measures it first";
                return w;
            }
            w.expectedRawZ = round(k - n1.tripRawZ);
        }
        w.zStart = round(w.expectedRawZ - down * LIFT_MM);
        w.zFloor = round(w.expectedRawZ + down * Math.min(past, c.heightSwitchMaxPastTripMm));
        return w;
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
        try {
            presses(io, p, r);
        }
        catch (DispenseExecutor.AbortedException e) {
            throw e; // a STOP: nothing moves after its abort
        }
        catch (Exception e) {
            // a reply with no level, a line that timed out: the tool goes up first (the executor's gate refuses the
            // lift too once a STOP came in), then the failure is thrown
            try {
                lift(io, p);
            }
            catch (Exception x) {
                e.addSuppressed(x);
            }
            throw e;
        }
        lift(io, p);
        if (!r.trips.isEmpty()) {
            double sum = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE, differential = 0;
            for (int i = 0; i < r.trips.size(); i++) {
                double t = r.trips.get(i);
                sum += t;
                min = Math.min(min, t);
                max = Math.max(max, t);
                differential += Math.abs(r.releases.get(i) - t);
            }
            r.tripRawZ = sum / r.trips.size();
            r.differentialMm = differential / r.trips.size();
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

    /**
     * Why the plan's window is refused before any line (the wrong order, wider than 2 mm, above travel, outside Z's
     * soft limits; travel Z outside Z's safe zone), or null.
     */
    static String windowProblem(Plan p) {
        double floor = p.floor();
        double span = p.down * (floor - p.zStart);
        if (!(span > 0)) {
            return String.format(Locale.ROOT, "the floor (Z %.3f) must lie below the start (Z %.3f) for %s%s", floor,
                    p.zStart, p.tool, floor == p.zFloor ? "" : String.format(Locale.ROOT, " (the floor is %.2f mm past "
                            + "the expected trip at Z %.3f: heightSwitchMaxPastTripMm)", p.maxPastTripMm, p.expectedTrip));
        }
        if (span > MAX_WINDOW_MM + 1e-9) {
            return String.format(Locale.ROOT, "the window from Z %.3f to %.3f is %.2f mm: at most %.1f mm (a search "
                    + "over an unknown height is the bench tool's)", p.zStart, floor, span, MAX_WINDOW_MM);
        }
        if (p.down * (p.zStart - p.travelZ) < 0) {
            return String.format(Locale.ROOT, "the start (Z %.3f) lies above the travel height (Z %.3f)", p.zStart,
                    p.travelZ);
        }
        for (double z : new double[] {p.zStart, floor}) {
            if (outside(p.zLimits, z)) {
                return String.format(Locale.ROOT, "Z %.3f lies outside Z's soft limits %s: the window from Z %.3f to "
                        + "%.3f must lie inside them", z, band(p.zLimits), p.zStart, floor);
            }
        }
        if (outside(p.safeZone, p.travelZ)) {
            return String.format(Locale.ROOT, "the travel Z %.3f lies outside Z's safe zone %s: check travelSafeZMm",
                    p.travelZ, band(p.safeZone));
        }
        return null;
    }

    /** {@code z} lies outside {@code b} ({low, high}, NaN: that side open; null: no band). */
    private static boolean outside(double[] b, double z) {
        return b != null && (z < b[0] - 1e-6 || z > b[1] + 1e-6);
    }

    private static String band(double[] b) {
        return (Double.isNaN(b[0]) ? "-" : String.format(Locale.ROOT, "%.3f", b[0])) + ".."
                + (Double.isNaN(b[1]) ? "-" : String.format(Locale.ROOT, "%.3f", b[1]));
    }

    private static void presses(Io io, Plan p, Result r) throws Exception {
        // the passthrough may have left them on: valve, then pump, as STOP orders them; no pressure at the switch, and
        // none in a needle that N1's measure carries across the bed (switching off is always safe)
        io.send(ControllerCommands.VALVE_OFF);
        io.send(ControllerCommands.PUMP_OFF);
        io.send(move(p.travelZ, 600));
        if (p.approachMm > 0) {
            // the side the end measure's frames came from: backlash leaves the press point where it was aimed
            io.send(String.format(Locale.ROOT, "G1 X%.3f Y%.3f F6000", p.rawX - p.approachMm, p.rawY - p.approachMm));
        }
        io.send(String.format(Locale.ROOT, "G1 X%.3f Y%.3f F6000", p.rawX, p.rawY));
        io.send("M42 P" + M42_PIN + " T2");
        io.send("M400");
        io.send("G4 P300");
        r.idleLevel = read(io); // both tools up: nothing presses the switch here
        // down to the start in coarse steps, each read: a start past the trip stops within one step of it
        Double early = descend(io, p, p.travelZ, p.zStart, p.coarseMm, r.idleLevel);
        if (early != null) {
            r.refused = true;
            r.why = String.format(Locale.ROOT, "the switch is already pressed at the start (Z %.3f) or above it (at Z "
                    + "%.3f): start higher, or the switch is stuck or wired wrong", p.zStart, early);
            return;
        }
        double from = p.zStart, floor = p.floor();
        for (int i = 0; i < p.presses; i++) {
            io.press(i + 1, p.presses);
            Double zc = descend(io, p, from, floor, p.coarseMm, r.idleLevel);
            if (zc == null) {
                // with a cap the window was the expected trip's: no trip by its floor means the estimate or the aim
                // was wrong, and the needle goes no deeper
                r.refused = p.capped();
                r.why = floor != p.zFloor ? String.format(Locale.ROOT, "no trip by Z %.3f, %.2f mm past the expected "
                        + "trip (Z %.3f): the needle goes no further past it (heightSwitchMaxPastTripMm)", floor,
                        p.maxPastTripMm, p.expectedTrip) : String.format(Locale.ROOT, "no trip between Z %.3f and Z "
                                + "%.3f", from, floor);
                return;
            }
            Double released = release(io, p, r, zc, "the coarse trip");
            if (released == null) {
                return;
            }
            // above the operating point (within one coarse step above zc): a snap switch stays released here
            double fine = up(p, zc, FINE_FROM_MM);
            io.send(move(fine, 600));
            io.send("M400");
            io.send("G4 P200");
            if (read(io) != r.idleLevel) {
                r.refused = true;
                r.why = String.format(Locale.ROOT, "pressed again %.2f mm above the coarse trip after its release at Z "
                        + "%.3f: the switch is erratic", Math.abs(zc - fine), released);
                return;
            }
            // one fine step past the coarse trip: a press that does not repeat (a bounce, noise) goes no deeper
            double end = p.down < 0 ? Math.max(zc - FINE_MM, floor) : Math.min(zc + FINE_MM, floor);
            Double t = descend(io, p, fine, end, FINE_MM, r.idleLevel);
            if (t == null) {
                r.why = String.format(Locale.ROOT, "the fine descent from Z %.3f found no trip (the coarse one did at Z "
                        + "%.3f)", fine, zc);
                return;
            }
            Double tReleased = release(io, p, r, t, "the trip");
            if (tReleased == null) {
                return;
            }
            r.trips.add(t);
            r.releases.add(tReleased);
            // it stays released between the trip and the release, wherever the release was
            from = round(up(p, t, LIFT_MM));
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

    /**
     * Lifts from {@code from} in 0.25 mm steps, each read, until the pin is back at its idle level: that Z, or null
     * when refused (still pressed 2.5 mm up, or at travel Z, where the lifts stop). {@code what}: the trip it lifts
     * from, for the refusal.
     */
    private static Double release(Io io, Plan p, Result r, double from, String what) throws Exception {
        int n = (int) Math.round(RELEASE_MAX_MM / RELEASE_STEP_MM);
        for (int k = 1; k <= n; k++) {
            double z = up(p, from, k * RELEASE_STEP_MM);
            io.send(move(z, 600));
            io.send("M400");
            io.send("G4 P100");
            if (read(io) == r.idleLevel) {
                return round(z);
            }
            if (z == p.travelZ) {
                r.refused = true;
                r.why = String.format(Locale.ROOT, "the switch still reads pressed at travel Z (Z %.3f, %s at Z %.3f): "
                        + "it sticks, or it releases only above travel Z (the tool still holds its lever down at travel "
                        + "height): check the switch's height or travelSafeZMm", z, what, from);
                return null;
            }
        }
        r.refused = true;
        r.why = String.format(Locale.ROOT, "still pressed %.2f mm above %s (Z %.3f): the switch sticks or the tool is "
                + "caught", RELEASE_MAX_MM, what, from);
        return null;
    }

    /**
     * {@code mm} above {@code z} in the tool's up direction, at most travel Z: on the see-saw past it the other tool
     * goes down (N1 rising past it lowers the needle, the needle rising past it lowers N1).
     */
    private static double up(Plan p, double z, double mm) {
        double u = z - p.down * mm;
        return p.down < 0 ? Math.min(u, p.travelZ) : Math.max(u, p.travelZ);
    }

    /** Travel Z, Z only: the end of every measure. */
    private static void lift(Io io, Plan p) throws Exception {
        io.send(move(p.travelZ, 600));
        io.send("M400");
    }

    /** The pin's level, the pull-up set again first; a reply with no level is read once more (the PA5 notes). */
    private static int read(Io io) throws Exception {
        io.send("M42 P" + M42_PIN + " T2");
        List<String> reply = io.query("M43 P" + M43_PIN);
        try {
            return level(reply);
        }
        catch (IllegalStateException e) {
            io.send("M42 P" + M42_PIN + " T2");
            return level(io.query("M43 P" + M43_PIN));
        }
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
