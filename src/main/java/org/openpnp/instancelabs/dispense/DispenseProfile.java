package org.openpnp.instancelabs.dispense;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.openpnp.instancelabs.padprofile.Texts;

/**
 * The dispense recipe: one per pressure session, the same for the purge row and the work (bench,
 * 2026-10-28, runs/p18_board1: 160/160 pads, 0.714 mm², CV 13 %, 5,490 DPH).
 *
 * <p>Dose model (measured, not fitted): the auger is a pump fed by the barrel pressure. The dose of
 * a dot is set by the screw turn per dot together with the pressure and the dwell. Partial turns
 * vary about +-50 % with the screw angle, so a dot's turn is a whole number of motor steps that
 * divides a revolution (1600 steps): the start angle then repeats (298, 118, 298, ... for half
 * turns). The first purge dot turns the screw to the angle that, after the remaining purge dots,
 * starts the work at {@link #startAngleDeg}; the remaining purge dots flush that odd-sized dot
 * (bench: a phase turn right before the board left a tail on the first board dot). There is no
 * mg-per-turn or angle-compensation model.</p>
 *
 * <p>Screw angle = (Marlin M114 Count B mod 1600) x 360 / 1600: the step count since power-up. G92 B
 * does not reset it (Marlin workspace offset), a power cycle does, so the start angle is a
 * calibration of the current controller power-up ({@link #startAngleSetAtMs}).</p>
 */
public class DispenseProfile {
    /** Vieweg DV-5005 maximum screw speed. */
    public static final double MAX_RPM = 400;
    /** DV-5005 maximum feed pressure, 1.5 bar. */
    public static final double MAX_PSI = 21.7;
    /** Smallest net turn of the purge dot that sets the start angle (the bench's 20 deg). */
    public static final double MIN_PHASE_TURN_DEG = 20;

    public String name = "18 psi half turn";
    /**
     * Regulator pressure (psi). A note: it is set by hand on the regulator (no sensor). The window
     * is narrow because flow rises about as pressure^3.7: 16 psi starved the screw (small dots that
     * depend on history), 20 psi drooled (dots grow without bound), 18 psi met the target.
     */
    public double pressurePsi = 18;
    /** Net screw turn per dot in motor steps; 800 = half a revolution (one revolution per 0603 part). */
    public int netStepsPerDot = 800;
    /** Suck-back after each dot (deg of B, sent as whole steps): forward = net + suck-back. */
    public double suckBackDeg = 10;
    /** Screw angle at which the first work dot starts (deg, 0..360; calibration value). */
    public double startAngleDeg = 298;
    /** When the start angle was set or confirmed (epoch ms); null = never. */
    public Long startAngleSetAtMs = null;
    /** Screw speed: G1 B feed = rpm x 360 deg/min (the bench's 180 rpm = F64800). */
    public double screwRpm = 180;
    /** Screw acceleration (deg/s²), sent as M204 S around the B moves only (M204 S also caps B). */
    public double screwAccelDegS2 = 20000;
    /** Dwell after the forward turn, before the suck-back (ms): the dot-size knob at fixed pressure. */
    public int dwellMs = 250;
    /** Real needle-to-surface gap while dispensing (mm): 0.05-0.15 behave the same, >= 0.20 misses. */
    public double gapMm = 0.10;
    /**
     * Purge dots on the purge row before the work, same recipe, then cross without stopping; the
     * first one sets the start angle.
     */
    public int purgeDots = 8;
    /**
     * Highest paste demand (mg/s) a block of dots may ask of the screw (§1.2 R7): the bench saw 2.56
     * mg/s sustained at 18 psi (36 one-turn dots at 180 rpm and 250 ms dwell, runs/p18_blank). A
     * screw asked for more pulls in air. Not part of a dot, so not in the fingerprints.
     */
    public double sustainedFeedMgS = 2.6;
    public String notes = "Window is narrow: 16 psi starved the screw, 20 psi drooled. Never run the screw "
            + "faster than the paste feeds it (it pulls in air; 30-50 turns to recover).";

    /** True if a dot is not a whole number of revolutions (the start angle then matters). */
    public boolean isPartialTurn(DispenseConfig c) {
        return netStepsPerDot % c.bStepsPerRev != 0;
    }

    public long suckBackSteps(DispenseConfig c) {
        return Math.round(suckBackDeg * c.bStepsPerDeg);
    }

    /** Net turn per dot in degrees of B (Marlin units). */
    public double netDeg(DispenseConfig c) {
        return netStepsPerDot / c.bStepsPerDeg;
    }

    /** Forward turn per dot in degrees of B. */
    public double forwardDeg(DispenseConfig c) {
        return (netStepsPerDot + suckBackSteps(c)) / c.bStepsPerDeg;
    }

    /** Revolutions per dot (0.5 for a half turn). */
    public double revolutionsPerDot(DispenseConfig c) {
        return netStepsPerDot / (double) c.bStepsPerRev;
    }

    /** B feed (deg/s of B). */
    public double feedDegS() {
        return screwRpm * 360.0 / 60.0;
    }

    /** The start angle in steps of the revolution (0 .. stepsPerRev-1). */
    public int startAngleSteps(DispenseConfig c) {
        return Math.floorMod((int) Math.round(startAngleDeg / 360.0 * c.bStepsPerRev), c.bStepsPerRev);
    }

    /** Start angles the work dots cycle through (deg), beginning with the start angle. */
    public List<Double> startAngles(DispenseConfig c) {
        List<Double> l = new ArrayList<>();
        int s = startAngleSteps(c);
        int a = s;
        do {
            l.add(a * 360.0 / c.bStepsPerRev);
            a = Math.floorMod(a + netStepsPerDot, c.bStepsPerRev);
        } while (a != s && l.size() < 64);
        return l;
    }

    /** Screw angle (deg) of a Marlin step count. */
    public static double angleOfCount(long count, int stepsPerRev) {
        return Math.floorMod(count, (long) stepsPerRev) * 360.0 / stepsPerRev;
    }

    /**
     * Net steps of the dot that turns the screw from a rest count so that, after {@code dotsAfter}
     * more profile dots, it rests at the start angle: at least {@link #MIN_PHASE_TURN_DEG}, at most
     * one revolution more.
     */
    public long phaseSteps(DispenseConfig c, long restCount, int dotsAfter) {
        return phaseSteps(c, restCount, dotsAfter, netStepsPerDot);
    }

    /**
     * As {@link #phaseSteps(DispenseConfig, long, int)} for {@code dotsAfter} dots of {@code stepsPerDot}
     * net steps each: the phase dot of a block switch (the flush dots of the new dose follow it).
     */
    public long phaseSteps(DispenseConfig c, long restCount, int dotsAfter, int stepsPerDot) {
        long target = startAngleSteps(c) - (long) c.bExtrudeSign * dotsAfter * stepsPerDot;
        long n = Math.floorMod(c.bExtrudeSign * (target - restCount), (long) c.bStepsPerRev);
        if (n < Math.round(MIN_PHASE_TURN_DEG / 360.0 * c.bStepsPerRev)) {
            n += c.bStepsPerRev;
        }
        return n;
    }

    /** Identity of everything that changes a dot (a plan made on another profile is stale). */
    public String fingerprint() {
        return String.format(Locale.ROOT, "psi=%.3f net=%d suck=%.3f start=%.3f rpm=%.3f acc=%.1f dwell=%d gap=%.4f "
                + "purge=%d", pressurePsi, netStepsPerDot, suckBackDeg, startAngleDeg, screwRpm, screwAccelDegS2,
                dwellMs, gapMm, purgeDots);
    }

    /**
     * Identity of everything but the turn that sets how much paste a turn lays (pressure, suck-back,
     * speed, acceleration, dwell, gap): dose records and calibrated pad types carry it (§1.7). The
     * net turn is the record's own, the start angle has its own rule, purge count and name are no
     * part of a dot.
     */
    public String doseFingerprint() {
        return String.format(Locale.ROOT, "psi=%.3f suck=%.3f rpm=%.3f acc=%.1f dwell=%d gap=%.4f", pressurePsi,
                suckBackDeg, screwRpm, screwAccelDegS2, dwellMs, gapMm);
    }

    /**
     * What differs between two fingerprints in words, e.g. {@code "dwell 250 -> 300, gap 0.1 -> 0.12"}
     * (keys only one side has are listed with "none"); empty when equal. The one recipe formatter of
     * the dose records and the profile check ({@link Texts#recipeDiff}).
     */
    public static String fingerprintDiff(String was, String now) {
        return Texts.recipeDiff(was, now);
    }

    /** A number without trailing zeros (18.000 -> "18", 0.1000 -> "0.1"). */
    static String plain(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** Problems with this profile (empty if OK). */
    public List<String> validate(DispenseConfig c) {
        List<String> p = new ArrayList<>();
        if (!(pressurePsi > 0 && pressurePsi <= MAX_PSI)) {
            p.add(String.format(Locale.ROOT, "profile pressurePsi must be > 0 and <= %.1f (DV-5005 1.5 bar)", MAX_PSI));
        }
        int rev = c.bStepsPerRev;
        // R1 (profile calibration §1.2): the start angle repeats, and a purge dot (the profile's dose) stays
        // within the stream validator's one revolution and the 20° phase turn
        if (!(netStepsPerDot == rev || 2 * netStepsPerDot == rev || 4 * netStepsPerDot == rev)) {
            p.add("profile netStepsPerDot must be " + rev / 4 + ", " + rev / 2 + " or " + rev + " steps (a quarter, "
                    + "half or whole turn of the " + rev + " steps of a revolution): the start angle repeats and a "
                    + "purge dot turns at most one revolution");
        }
        if (!(suckBackDeg >= 0 && suckBackDeg <= 30)) {
            p.add("profile suckBackDeg must be 0 to 30");
        }
        if (!(startAngleDeg >= 0 && startAngleDeg < 360)) {
            p.add("profile startAngleDeg must be 0 to < 360");
        }
        if (!(screwRpm > 0 && screwRpm <= MAX_RPM)) {
            p.add("profile screwRpm must be > 0 and <= 400 (Vieweg maximum)");
        }
        else if (feedDegS() > c.bMaxFeedDegS) {
            p.add(String.format(Locale.ROOT, "profile screw feed %.0f deg/s is above the B axis limit bMaxFeedDegS %.0f",
                    feedDegS(), c.bMaxFeedDegS));
        }
        if (!(screwAccelDegS2 >= 1000 && screwAccelDegS2 <= 30000)) {
            p.add("profile screwAccelDegS2 must be 1000 to 30000");
        }
        if (!(dwellMs >= 0 && dwellMs <= 5000)) {
            p.add("profile dwellMs must be 0 to 5000");
        }
        if (!(gapMm >= 0.05 - 1e-9 && gapMm <= 0.15 + 1e-9)) {
            p.add("profile gapMm must be 0.05 to 0.15 (>= 0.20 misses dots)");
        }
        if (!(purgeDots >= 0 && purgeDots <= 60)) {
            p.add("profile purgeDots must be 0 to 60");
        }
        else if (purgeDots < 2 && netStepsPerDot > 0 && isPartialTurn(c)) {
            p.add("profile purgeDots must be at least 2 for a partial turn: the first purge dot sets the start angle "
                    + "and at least one normal dot must flush its odd size before the work");
        }
        if (!(sustainedFeedMgS > 0 && sustainedFeedMgS <= 10)) {
            p.add("profile sustainedFeedMgS must be > 0 and <= 10 mg/s (the bench saw 2.56 at 18 psi)");
        }
        return p;
    }

    public DispenseProfile copy() {
        DispenseProfile x = new DispenseProfile();
        x.name = name;
        x.pressurePsi = pressurePsi;
        x.netStepsPerDot = netStepsPerDot;
        x.suckBackDeg = suckBackDeg;
        x.startAngleDeg = startAngleDeg;
        x.startAngleSetAtMs = startAngleSetAtMs;
        x.screwRpm = screwRpm;
        x.screwAccelDegS2 = screwAccelDegS2;
        x.dwellMs = dwellMs;
        x.gapMm = gapMm;
        x.purgeDots = purgeDots;
        x.sustainedFeedMgS = sustainedFeedMgS;
        x.notes = notes;
        return x;
    }
}
