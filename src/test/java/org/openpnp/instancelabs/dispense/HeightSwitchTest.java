package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * BR-94: the bed's height switch on PA5, pressed by N1 or the needle in the steps of Process's tools/switchz.py, on a
 * fake switch that tracks the raw Z of the G-code lines and answers M43 P5 as the board does.
 */
public class HeightSwitchTest {
    /** A switch whose plunger trips at {@code trip} (raw Z) for a tool that goes down as raw Z moves by {@code down}. */
    static class FakeSwitch implements HeightSwitch.Io {
        final double trip;
        final int down;
        /** The level it reads unpressed (1: pulled up, a normally open switch closes to GND). */
        int idle = 1;
        boolean stuck;
        /** Once pressed it never lets go. */
        boolean sticky;
        /**
         * A hinge lever pressed near its free end: once pressed it lets go only this far (mm) above the trip, in the
         * tool's up direction (its movement differential times the lever ratio); 0: where it trips.
         */
        double differential;
        /** Pressed and not let go yet (with a differential). */
        boolean held;
        boolean pullUp;
        double z = 31.5;
        final List<String> lines = new ArrayList<>();
        static final Pattern Z = Pattern.compile("\\bZ(-?\\d+(?:\\.\\d+)?)");

        FakeSwitch(double trip, int down) {
            this.trip = trip;
            this.down = down;
        }

        boolean pressed() {
            return down < 0 ? z <= trip + 1e-9 : z >= trip - 1e-9;
        }

        @Override
        public void send(String line) {
            lines.add(line);
            Matcher m = Z.matcher(line);
            if (line.startsWith("G1") && m.find()) {
                z = Double.parseDouble(m.group(1));
                stuck |= sticky && pressed();
                held = differential > 0 && (pressed() || held && down * (z - trip) > 1e-9 - differential);
            }
            if (line.equals("M42 P53 T2")) {
                pullUp = true;
            }
        }

        @Override
        public List<String> query(String line) {
            lines.add(line);
            assertEquals("M43 P5", line);
            assertTrue(pullUp, "the pull-up is set before the read");
            int level = stuck || held || pressed() ? 1 - idle : idle;
            return Arrays.asList(String.format(Locale.ROOT,
                    "PIN: PA5  (A9)   M42 P53          <unused/unknown>                       Input  = %d", level), "ok");
        }

        /** The deepest Z the tool reached, past the trip (mm; 0 when it never got there). */
        double deepestPast() {
            return past(trip);
        }

        /** How far past raw Z {@code ref} the tool went (mm; 0 when never). */
        double past(double ref) {
            return past(ref, down);
        }

        /** How far past raw Z {@code ref} the tool went in direction {@code dir} (−1: as raw Z falls; mm, 0: never). */
        double past(double ref, int dir) {
            double worst = 0;
            for (String l : lines) {
                Matcher m = Z.matcher(l);
                if (l.startsWith("G1") && m.find()) {
                    worst = Math.max(worst, dir * (Double.parseDouble(m.group(1)) - ref));
                }
            }
            return worst;
        }
    }

    static HeightSwitch.Plan plan(String tool, int down, double zStart, double zFloor, int presses) {
        HeightSwitch.Plan p = new HeightSwitch.Plan();
        p.tool = tool;
        p.rawX = 100;
        p.rawY = 200;
        p.travelZ = 31.5;
        p.down = down;
        p.zStart = zStart;
        p.zFloor = zFloor;
        p.coarseMm = down < 0 ? 0.25 : 0.10;
        p.presses = presses;
        return p;
    }

    @Test
    public void n1FindsTheTripWithinOneFineStepAndStaysWithinOneCoarseStepPastIt() throws Exception {
        FakeSwitch sw = new FakeSwitch(12.0, -1);
        HeightSwitch.Result r = HeightSwitch.measure(sw, plan("n1", -1, 13.0, 11.0, 5));
        assertNull(r.why, r.why);
        assertEquals(5, r.trips.size());
        for (double t : r.trips) {
            assertTrue(t <= 12.0 + 1e-9 && t > 12.0 - 0.025 - 1e-9, "the first fine step at or past the trip: " + t);
        }
        assertEquals(12.0, r.tripRawZ, 0.025);
        assertTrue(r.rangeMm <= 0.025 + 1e-9, String.valueOf(r.rangeMm));
        assertTrue(r.accepted, "5 presses within 0.05 mm");
        assertEquals(Integer.valueOf(1), r.idleLevel);
        assertTrue(sw.deepestPast() <= 0.25 + 1e-9, "never more than one coarse step past the trip: " + sw.deepestPast());
        assertEquals("G1 Z31.500 F600", sw.lines.get(sw.lines.size() - 2), "travel Z at the end: " + sw.lines);
    }

    @Test
    public void theNeedleGoesDownAsRawZRisesInFinerCoarseSteps() throws Exception {
        FakeSwitch sw = new FakeSwitch(52.0, 1);
        sw.z = 31.5;
        HeightSwitch.Result r = HeightSwitch.measure(sw, plan("needle", 1, 51.0, 53.0, 5));
        assertNull(r.why, r.why);
        for (double t : r.trips) {
            assertTrue(t >= 52.0 - 1e-9 && t < 52.0 + 0.025 + 1e-9, String.valueOf(t));
        }
        assertTrue(r.accepted);
        assertTrue(sw.deepestPast() <= 0.10 + 1e-9, "the needle's coarse step is 0.10: " + sw.deepestPast());
    }

    /**
     * The machine's switch (Process 22:3x) is a hinge lever pressed near its free end: it lets go well above its trip
     * (N1 pressed at raw 25.50 still read pressed at 25.90). After each trip the tool lifts in 0.25 mm steps until it
     * reads released: 5 presses accepted for both tools, each trip within one fine step, each release recorded, and
     * the differential is their mean distance at 0.25 mm resolution (0.45 reads 0.50, 0.9 reads 1.00, 1.6 reads 1.75).
     * Release review MINOR: right after each press's release the next press starts 1 mm above its trip, also when the
     * release lies above that (1.6). NIT: a trip off the coarse grid (0.07 past a step) records the press's release,
     * not the coarse trip's.
     */
    @Test
    public void aLeverThatLetsGoWellAboveItsTripIsMeasuredWithItsReleases() {
        List<Executable> cases = new ArrayList<>(); // each case passes or fails on its own
        for (int down : new int[] {-1, 1}) {
            // the differential, what it reads, how far past a coarse step the switch trips
            for (double[] c : new double[][] {{0.45, 0.50, 0}, {0.9, 1.00, 0}, {1.6, 1.75, 0}, {0.45, 0.50, 0.07}}) {
                cases.add(() -> {
                    double grid = down < 0 ? 12.0 : 52.0, trip = grid + down * c[2];
                    FakeSwitch sw = new FakeSwitch(trip, down);
                    sw.differential = c[0];
                    HeightSwitch.Result r = HeightSwitch.measure(sw, plan(down < 0 ? "n1" : "needle", down,
                            grid - down, grid + down, 5));
                    String at = (down < 0 ? "N1" : "the needle") + ", trip " + trip + ", differential " + c[0] + ": ";
                    assertTrue(r.accepted, at + r.why);
                    assertEquals(5, r.trips.size(), at + r.trips);
                    assertEquals(5, r.releases.size(), at + r.releases);
                    for (int i = 0; i < 5; i++) {
                        double t = r.trips.get(i);
                        assertTrue(down * (t - trip) > -1e-9 && down * (t - trip) < HeightSwitch.FINE_MM - 1e-9,
                                at + "the trip within one fine step: " + t);
                        assertEquals(t - down * c[1], r.releases.get(i), 1e-9, at + "the first 0.25 step it let go at");
                    }
                    assertEquals(c[1], r.differentialMm, 1e-9, at);
                    assertEquals("G1 Z31.500 F600", sw.lines.get(sw.lines.size() - 2), at + "travel Z at the end");
                    // right after each press's release, the next press's start 1 mm above its trip (not the release)
                    String release = String.format(Locale.ROOT, "G1 Z%.3f F600", r.releases.get(0)), last = "",
                            start = String.format(Locale.ROOT, "G1 Z%.3f F600", r.trips.get(0) - down);
                    int starts = 0;
                    for (String l : sw.lines) {
                        if (l.endsWith(" F600")) {
                            starts += last.equals(release) && l.equals(start) ? 1 : 0;
                            last = l;
                        }
                    }
                    assertEquals(5, starts, at + release + " then " + start + ", once a press");
                });
            }
        }
        assertAll(cases);
    }

    /**
     * The steps of a press on a lever: the coarse trip, lifts of 0.25 until released (each a move, M400, 100 ms and
     * the read), straight to 0.30 above the coarse trip and a read there (200 ms), the fine descent, lifts until
     * released again, and the next press from 1 mm above the trip (it stays released between the trip and the
     * release), not from the release.
     */
    @Test
    public void aPressLiftsUntilReleasedReadsAtTheFineStartAndTheNextStartsOneMmAboveTheTrip() throws Exception {
        FakeSwitch sw = new FakeSwitch(12.0, -1);
        sw.differential = 0.45;
        HeightSwitch.Result r = HeightSwitch.measure(sw, plan("n1", -1, 13.0, 11.0, 2));
        assertTrue(r.why == null && r.trips.size() == 2, r.why + " " + r.trips);
        List<String> lifts = new ArrayList<>();
        for (String l : sw.lines) {
            if (l.startsWith("G1 Z") && l.endsWith(" F600")) {
                lifts.add(l.substring(4, l.length() - 5));
            }
        }
        // each press: still pressed at 12.25, released at 12.5; the fine start 12.3; the same lifts; the next at 13.0
        assertEquals(Arrays.asList("31.500", "12.250", "12.500", "12.300", "12.250", "12.500", "13.000", "12.250",
                "12.500", "12.300", "12.250", "12.500", "13.000", "31.500"), lifts, sw.lines.toString());
        int lift = sw.lines.indexOf("G1 Z12.250 F600"), fine = sw.lines.indexOf("G1 Z12.300 F600");
        assertEquals(Arrays.asList("G1 Z12.250 F600", "M400", "G4 P100", "M42 P53 T2", "M43 P5"),
                sw.lines.subList(lift, lift + 5));
        assertEquals(Arrays.asList("G1 Z12.300 F600", "M400", "G4 P200", "M42 P53 T2", "M43 P5"),
                sw.lines.subList(fine, fine + 5));
    }

    /**
     * Pressed again at the fine start, 0.30 above the coarse trip, after it read released below it: the switch is
     * erratic (the operating point lies within one coarse step above the coarse trip), refused and lifted.
     */
    @Test
    public void pressedAgainAtTheFineStartAfterItsReleaseIsRefusedAsErratic() throws Exception {
        for (int down : new int[] {-1, 1}) {
            double trip = down < 0 ? 12.0 : 52.0, again = trip - down * HeightSwitch.FINE_FROM_MM;
            FakeSwitch sw = new FakeSwitch(trip, down) {
                boolean tripped;

                @Override
                boolean pressed() {
                    tripped |= super.pressed();
                    return super.pressed() || tripped && Math.abs(z - again) < 1e-9;
                }
            };
            HeightSwitch.Result r = HeightSwitch.measure(sw, plan(down < 0 ? "n1" : "needle", down, trip - down,
                    trip + down, 5));
            assertEquals(String.format(Locale.ROOT, "pressed again 0.30 mm above the coarse trip after its release at "
                    + "Z %.3f: the switch is erratic", trip - down * 0.25), r.why);
            assertTrue(r.refused && r.trips.isEmpty() && !r.accepted, String.valueOf(r.trips));
            assertEquals("G1 Z31.500 F600", sw.lines.get(sw.lines.size() - 2), sw.lines.toString());
            assertEquals(31.5, sw.z, 1e-9);
        }
    }

    /**
     * A release lift that would pass travel Z stops there (past it the see-saw lowers the other tool), for N1 (down:
     * raw Z falls) and the needle (raw Z rises): a lever that lets go 0.02 below travel Z is released at travel Z on
     * every lift, and nothing goes past it.
     */
    @Test
    public void aReleaseLiftStopsAtTravelZ() throws Exception {
        for (int down : new int[] {-1, 1}) {
            double trip = 31.5 + down * 0.55, start = 31.5 + down * 0.1;
            FakeSwitch sw = new FakeSwitch(trip, down);
            sw.differential = 0.53;
            HeightSwitch.Result r = HeightSwitch.measure(sw, plan(down < 0 ? "n1" : "needle", down, start,
                    start + down * 2.0, 5));
            assertTrue(r.accepted, r.why);
            assertEquals(Collections.nCopies(5, 31.5), r.releases);
            assertEquals(0.55, r.differentialMm, 1e-9);
            assertEquals(0, sw.past(31.5, -down), 1e-9, "never past travel Z: " + sw.lines);
        }
    }

    /**
     * Re-review MINOR: a window of no whole number of coarse steps never takes a step longer than one coarse step, on
     * the presses (13.0 to the floor 11.15, the trip at 11.45) and on the way down from travel Z (to zStart 13.15, the
     * trip at 13.45): a rounded step count would put the tool 0.30 past the trip.
     */
    @Test
    public void aWindowOfNoWholeNumberOfStepsStaysWithinOneCoarseStepOfTheTrip() throws Exception {
        FakeSwitch presses = new FakeSwitch(11.45, -1);
        HeightSwitch.measure(presses, plan("n1", -1, 13.0, 11.15, 1));
        assertTrue(presses.deepestPast() <= 0.25 + 1e-9, "presses: " + presses.deepestPast() + " " + presses.lines);
        FakeSwitch wayDown = new FakeSwitch(13.45, -1);
        HeightSwitch.measure(wayDown, plan("n1", -1, 13.15, 11.15, 1));
        assertTrue(wayDown.deepestPast() <= 0.25 + 1e-9, "way down: " + wayDown.deepestPast() + " " + wayDown.lines);
    }

    /**
     * Review MAJOR 2: the needle's measure switches the valve and then the pump off (the order STOP uses) before its
     * first move: the passthrough may have left them on, and paste must never touch the switch.
     */
    @Test
    public void theNeedlesMeasureSwitchesTheValveAndThePumpOffBeforeItsFirstMove() throws Exception {
        FakeSwitch sw = new FakeSwitch(52.0, 1);
        HeightSwitch.measure(sw, plan("needle", 1, 51.0, 53.0, 1));
        assertEquals(Arrays.asList("M107 P3", "M107 P2", "G1 Z31.500 F600"), sw.lines.subList(0, 3), sw.lines.toString());
    }

    /**
     * Re-review NIT: N1's measure switches the needle's valve and pump off first too (switching off is always safe): a
     * passthrough may have left them on, and N1 would carry a pressurised needle across the bed.
     */
    @Test
    public void n1sMeasureAlsoSwitchesTheValveAndThePumpOffFirst() throws Exception {
        FakeSwitch sw = new FakeSwitch(12.0, -1);
        HeightSwitch.measure(sw, plan("n1", -1, 13.0, 11.0, 1));
        assertEquals(Arrays.asList("M107 P3", "M107 P2", "G1 Z31.500 F600"), sw.lines.subList(0, 3), sw.lines.toString());
    }

    @Test
    public void everyReadSetsThePullUpFirstAfterTheMoveHasEnded() throws Exception {
        FakeSwitch sw = new FakeSwitch(12.0, -1);
        HeightSwitch.measure(sw, plan("n1", -1, 13.0, 11.0, 1));
        for (int i = 0; i < sw.lines.size(); i++) {
            if (sw.lines.get(i).equals("M43 P5")) {
                assertEquals("M42 P53 T2", sw.lines.get(i - 1), "pull-up right before each read: " + sw.lines);
                assertTrue(sw.lines.subList(0, i).lastIndexOf("M400") > sw.lines.subList(0, i).lastIndexOf(
                        lastMove(sw.lines.subList(0, i))), "M400 after the last move before a read");
            }
        }
    }

    static String lastMove(List<String> l) {
        for (int i = l.size() - 1; i >= 0; i--) {
            if (l.get(i).startsWith("G1")) {
                return l.get(i);
            }
        }
        return "";
    }

    @Test
    public void aNormallyClosedSwitchReadsTheOtherWay() throws Exception {
        FakeSwitch sw = new FakeSwitch(12.0, -1);
        sw.idle = 0;
        HeightSwitch.Result r = HeightSwitch.measure(sw, plan("n1", -1, 13.0, 11.0, 5));
        assertEquals(Integer.valueOf(0), r.idleLevel);
        assertTrue(r.accepted, String.valueOf(r.why));
        assertEquals(12.0, r.tripRawZ, 0.025);
    }

    @Test
    public void pressedAtTheStartIsRefusedAndTheToolGoesBackUp() throws Exception {
        FakeSwitch sw = new FakeSwitch(13.5, -1);
        HeightSwitch.Result r = HeightSwitch.measure(sw, plan("n1", -1, 13.0, 11.0, 5));
        assertTrue(r.refused && r.why.contains("already pressed at the start") && r.why.contains("start higher"), r.why);
        assertTrue(r.trips.isEmpty());
        assertFalse(r.accepted);
        assertEquals("G1 Z31.500 F600", sw.lines.get(sw.lines.size() - 2), sw.lines.toString());
    }

    /**
     * Review MAJOR 4: the way down to the start is stepped and read like a descent, so a start past the trip (the
     * needle's trip 43, zStart 45) stops at most one coarse step past the trip and is refused, not driven 2 mm into it.
     */
    @Test
    public void aStartPastTheTripStopsWithinOneCoarseStepOfItOnTheWayDown() throws Exception {
        FakeSwitch sw = new FakeSwitch(43.0, 1);
        HeightSwitch.Result r = HeightSwitch.measure(sw, plan("needle", 1, 45.0, 46.0, 5));
        assertTrue(r.refused && r.why.contains("already pressed") && r.why.contains("start higher"), r.why);
        assertTrue(sw.deepestPast() <= 0.10 + 1e-9, "at most one coarse step past the trip: " + sw.deepestPast());
        assertEquals(31.5, sw.z, 1e-9, "back up");
    }

    /**
     * Still pressed 2.5 mm above the trip (lifted in 0.25 mm steps, each read): the switch sticks or the tool is
     * caught, refused and lifted to travel Z; after the coarse trip and after the fine one (the press is not recorded).
     */
    @Test
    public void aSwitchThatSticksIsRefused() throws Exception {
        FakeSwitch sw = new FakeSwitch(12.0, -1);
        sw.sticky = true;
        HeightSwitch.Result r = HeightSwitch.measure(sw, plan("n1", -1, 13.0, 11.0, 5));
        assertTrue(r.refused, r.why);
        assertEquals("still pressed 2.50 mm above the coarse trip (Z 12.000): the switch sticks or the tool is caught",
                r.why);
        List<String> l = sw.lines, moves = new ArrayList<>(), want = new ArrayList<>();
        for (String x : l.subList(l.indexOf("G1 Z12.000 F300") + 1, l.size())) {
            if (x.startsWith("G1")) {
                moves.add(x);
            }
        }
        for (int k = 1; k <= 10; k++) {
            want.add(String.format(Locale.ROOT, "G1 Z%.3f F600", 12.0 + 0.25 * k));
        }
        want.add("G1 Z31.500 F600");
        assertEquals(want, moves, "lifts of 0.25 to 2.5 mm above the coarse trip, then travel Z");
        assertEquals("M400", l.get(l.size() - 1));
        FakeSwitch late = new FakeSwitch(12.0, -1) {
            @Override
            public void send(String line) {
                sticky |= line.equals("G1 Z12.300 F600"); // from the fine start on
                super.send(line);
            }
        };
        HeightSwitch.Result fine = HeightSwitch.measure(late, plan("n1", -1, 13.0, 11.0, 5));
        assertTrue(fine.refused, fine.why);
        assertEquals("still pressed 2.50 mm above the trip (Z 12.000): the switch sticks or the tool is caught",
                fine.why);
        assertTrue(fine.trips.isEmpty() && fine.releases.isEmpty(), fine.trips + " " + fine.releases);
        assertEquals("G1 Z31.500 F600", late.lines.get(late.lines.size() - 2), late.lines.toString());
    }

    /**
     * BR-94 follow-up NIT: a start close below travel Z leaves the release lifts little room (they stop at travel Z); a
     * switch still pressed there is refused in words that say so, not as one still pressed 2.5 mm above its trip.
     * Review MINOR: and that send the operator the right way (the switch sits high under travel Z, not the window):
     * one that sticks, and a lever that lets go only above travel Z.
     */
    @Test
    public void aSwitchStillPressedAtTravelZSaysSo() throws Exception {
        for (int down : new int[] {-1, 1}) {
            double start = 31.5 + down * 0.1;
            FakeSwitch sticks = new FakeSwitch(start + down * 0.1, down);
            sticks.sticky = true;
            FakeSwitch high = new FakeSwitch(31.5 + down * 0.55, down);
            high.differential = 0.6; // lets go 0.05 above travel Z
            for (FakeSwitch sw : new FakeSwitch[] {sticks, high}) {
                HeightSwitch.Result r = HeightSwitch.measure(sw, plan(down < 0 ? "n1" : "needle", down, start,
                        start + down * 2.0, 5));
                assertTrue(r.refused && r.why.startsWith("the switch still reads pressed at travel Z (Z 31.500, the "
                        + "coarse trip at Z ") && r.why.endsWith("): it sticks, or it releases only above travel Z (the "
                                + "tool still holds its lever down at travel height): check the switch's height or "
                                + "travelSafeZMm"), r.why);
                assertEquals(0, sw.past(31.5, -down), 1e-9, "never past travel Z: " + sw.lines);
                assertEquals(31.5, sw.z, 1e-9);
            }
        }
    }

    /**
     * No line goes past travel Z (31.5) in the tool's up direction: on the see-saw N1 past it lowers the needle, the
     * needle past it lowers N1. A start 0.5 mm below travel put the 1 mm lift past it; 0.1 mm below, the release lifts
     * and the fine descent's start too. They stop at travel Z, and the trip is found as far from it.
     */
    @Test
    public void noLineGoesPastTravelZWithAStartCloseBelowIt() throws Exception {
        for (double gap : new double[] {0.5, 0.1}) {
            for (int down : new int[] {-1, 1}) {
                double start = 31.5 + down * gap, trip = start + down * 0.1;
                FakeSwitch sw = new FakeSwitch(trip, down);
                HeightSwitch.Result r = HeightSwitch.measure(sw, plan(down < 0 ? "n1" : "needle", down, start,
                        start + down * 2.0, 5));
                assertEquals(0, sw.past(31.5, -down), 1e-9, "never past travel Z (start " + start + "): " + sw.lines);
                assertTrue(r.accepted, r.why);
                assertEquals(trip, r.tripRawZ, 0.025);
            }
        }
    }

    /** Review MAJOR 3: no step past the floor, also when the window is not a whole number of coarse steps. */
    @Test
    public void noTripBeforeTheFloorIsNotAcceptedAndNoStepPassesTheFloor() throws Exception {
        FakeSwitch sw = new FakeSwitch(5.0, -1);
        HeightSwitch.Result r = HeightSwitch.measure(sw, plan("n1", -1, 13.0, 11.1, 5));
        assertFalse(r.accepted);
        assertFalse(r.refused);
        assertTrue(r.why.contains("no trip between") && r.why.contains("13.000") && r.why.contains("11.100"), r.why);
        assertEquals(0, sw.past(11.1), 1e-9, "never below N1's floor: " + sw.lines);
        assertEquals(31.5, sw.z, 1e-9, "back up");
        FakeSwitch needle = new FakeSwitch(60.0, 1);
        HeightSwitch.measure(needle, plan("needle", 1, 51.0, 52.96, 5));
        assertEquals(0, needle.past(52.96), 1e-9, "never past the needle's floor: " + needle.lines);
    }

    /**
     * BR-94 needle aim: the needle never goes more than heightSwitchMaxPastTripMm past the trip its window expects,
     * whatever the window says; no trip by then is refused (not a 200 "no trip"), and it goes back up to travel Z. A
     * trip inside the cap is measured as before; a start past the cap is refused before any line. N1 has no cap.
     */
    @Test
    public void theNeedleNeverGoesMoreThanMaxPastTripPastTheExpectedTrip() throws Exception {
        FakeSwitch miss = new FakeSwitch(60.0, 1); // the tip misses the lever: no trip in the window
        HeightSwitch.Plan p = plan("needle", 1, 41.0, 43.0, 5);
        p.expectedTrip = 42.0;
        p.maxPastTripMm = 0.5;
        HeightSwitch.Result r = HeightSwitch.measure(miss, p);
        assertTrue(r.refused && !r.accepted, String.valueOf(r.why));
        assertTrue(r.why.contains("no trip by Z 42.500") && r.why.contains("0.50 mm past the expected trip (Z 42.000)")
                && r.why.contains("heightSwitchMaxPastTripMm"), r.why);
        assertEquals(0, miss.past(42.5), 1e-9, "never past the cap: " + miss.lines);
        assertEquals(31.5, miss.z, 1e-9, "back at travel Z");
        FakeSwitch hit = new FakeSwitch(42.3, 1);
        HeightSwitch.Result ok = HeightSwitch.measure(hit, p);
        assertTrue(ok.accepted, ok.why);
        assertEquals(42.3, ok.tripRawZ, 0.025);
        assertEquals(0, hit.past(42.5), 1e-9, hit.lines.toString());
        // a window floor nearer than the cap is kept; no trip by it is refused too (the estimate was wrong)
        HeightSwitch.Plan near = plan("needle", 1, 41.0, 42.2, 5);
        near.expectedTrip = 42.0;
        near.maxPastTripMm = 0.5;
        FakeSwitch miss2 = new FakeSwitch(60.0, 1);
        HeightSwitch.Result r2 = HeightSwitch.measure(miss2, near);
        assertTrue(r2.refused && r2.why.contains("no trip between Z 41.000 and Z 42.200"), r2.why);
        assertEquals(0, miss2.past(42.2), 1e-9, miss2.lines.toString());
        HeightSwitch.Plan late = plan("needle", 1, 42.6, 43.0, 5);
        late.expectedTrip = 42.0;
        late.maxPastTripMm = 0.5;
        FakeSwitch none = new FakeSwitch(60.0, 1);
        HeightSwitch.Result lr = HeightSwitch.measure(none, late);
        assertTrue(lr.refused && lr.why.contains("the floor (Z 42.500) must lie below the start (Z 42.600)"), lr.why);
        assertTrue(none.lines.isEmpty(), "nothing sent: " + none.lines);
        // N1: no cap, a no-trip stays a 200 with its reason (unchanged)
        FakeSwitch n1 = new FakeSwitch(5.0, -1);
        HeightSwitch.Result nr = HeightSwitch.measure(n1, plan("n1", -1, 13.0, 11.1, 5));
        assertFalse(nr.refused, nr.why);
    }

    /** A trip of the needle or N1 at raw Z {@code z}, measured under home {@code home} at {@code atMs}. */
    static DispenseConfig.HeightSwitchTrip trip(double z, long home, long atMs, Double kSwitch) {
        DispenseConfig.HeightSwitchTrip t = new DispenseConfig.HeightSwitchTrip();
        t.tripRawZ = z;
        t.homeAtMs = home;
        t.atMs = atMs;
        t.kSwitch = kSwitch;
        return t;
    }

    /**
     * BR-94 needle aim: the engine derives the needle's window from its records only, with Process's pair on the
     * lever's straight part (11-04 NP-5: N1 23.875 + the needle 41.200 = K 65.075). A first press after a change: K by
     * parallax − N1's trip of this home ± 1 mm, the floor capped at heightSwitchMaxPastTripMm; a later one: its last
     * trip's K_switch − N1's trip now (a new home moved N1's by +0.1), 1.0 above and 0.5 past. Without the setting, N1's
     * trip of this home, or an end: no window, and why.
     */
    @Test
    public void theEngineDerivesTheNeedlesWindowFromItsRecords() {
        DispenseConfig c = new DispenseConfig();
        DispenseConfig.NeedleEnd end = new DispenseConfig.NeedleEnd();
        end.kParallax = 65.075;
        assertEquals(HeightSwitch.NO_MAX_PAST_TRIP, HeightSwitch.needleWindow(c, end, 100L, 1).why);
        c.heightSwitchMaxPastTripMm = 0.5;
        assertEquals(HeightSwitch.PRESS_N1_FIRST, HeightSwitch.needleWindow(c, end, 100L, 1).why, "no N1 trip");
        c.heightSwitch.n1 = trip(23.875, 99, 1, null);
        assertEquals(HeightSwitch.PRESS_N1_FIRST, HeightSwitch.needleWindow(c, end, 100L, 1).why, "another home's");
        c.heightSwitch.n1 = trip(23.875, 100, 1, null);
        HeightSwitch.Window first = HeightSwitch.needleWindow(c, end, 100L, 1);
        assertNull(first.why, first.why);
        assertEquals("kParallax", first.from);
        assertEquals(41.2, first.expectedRawZ, 1e-9);
        assertEquals(40.2, first.zStart, 1e-9, "1 mm above (the needle goes down as raw Z rises)");
        assertEquals(41.7, first.zFloor, 1e-9, "1 mm past, capped 0.5 past");
        c.heightSwitchMaxPastTripMm = 2.0;
        assertEquals(42.2, HeightSwitch.needleWindow(c, end, 100L, 1).zFloor, 1e-9, "the full 1 mm under a wider cap");
        c.heightSwitchMaxPastTripMm = 0.3;
        assertEquals(41.5, HeightSwitch.needleWindow(c, end, 100L, 1).zFloor, 1e-9);
        assertTrue(HeightSwitch.needleWindow(c, null, 100L, 1).why.contains("its end measured from below"),
                "a first press needs the end");
        // its own trip with K_switch, a home later: N1's trip there moved +0.1, the needle's expected trip −0.1
        c.heightSwitchMaxPastTripMm = 0.5;
        c.heightSwitch.needle = trip(41.2, 100, 2, 65.075);
        c.heightSwitch.n1 = trip(23.975, 200, 3, null);
        HeightSwitch.Window later = HeightSwitch.needleWindow(c, null, 200L, 1);
        assertEquals("kSwitch", later.from);
        assertEquals(41.1, later.expectedRawZ, 1e-9);
        assertEquals(40.1, later.zStart, 1e-9);
        assertEquals(41.6, later.zFloor, 1e-9);
        c.heightSwitchMaxPastTripMm = 0.3;
        assertEquals(41.4, HeightSwitch.needleWindow(c, null, 200L, 1).zFloor, 1e-9, "capped");
        // a needle change voids its trip: the first window again; a trip recorded before K_switch was kept: the same
        c.needleChangedAtMs = 5L;
        assertEquals("kParallax", HeightSwitch.needleWindow(c, end, 200L, 1).from);
        c.needleChangedAtMs = null;
        c.heightSwitch.needle.kSwitch = null;
        assertEquals("kParallax", HeightSwitch.needleWindow(c, end, 200L, 1).from);
    }

    /** Each press announces itself before its descent: the task's progress (press n of N). */
    @Test
    public void eachPressIsAnnouncedForTheTasksProgress() throws Exception {
        List<String> said = new ArrayList<>();
        FakeSwitch sw = new FakeSwitch(12.0, -1) {
            @Override
            public void press(int n, int of) {
                said.add(n + "/" + of + "@" + lines.size());
            }
        };
        HeightSwitch.measure(sw, plan("n1", -1, 13.0, 11.0, 3));
        assertEquals(3, said.size(), said.toString());
        assertTrue(said.get(0).startsWith("1/3") && said.get(2).startsWith("3/3"), said.toString());
    }

    /**
     * Review MINOR 5: a coarse press the fine pass does not see again (a bounce, noise) ends the fine pass one fine step
     * past the coarse trip, not 0.30 past it.
     */
    @Test
    public void aPressTheFinePassDoesNotSeeAgainEndsOneFineStepPastTheCoarseTrip() throws Exception {
        FakeSwitch sw = new FakeSwitch(51.95, 1) {
            boolean seen;

            @Override
            boolean pressed() {
                boolean now = super.pressed() && !seen;
                seen |= now;
                return now;
            }
        };
        HeightSwitch.Result r = HeightSwitch.measure(sw, plan("needle", 1, 51.0, 52.9, 5));
        assertTrue(r.why != null && r.why.contains("the fine descent") && r.why.contains("52.000"), r.why);
        assertTrue(r.trips.isEmpty());
        assertEquals(HeightSwitch.FINE_MM, sw.past(52.0), 1e-9, "one fine step past the coarse trip: " + sw.lines);
        assertEquals(31.5, sw.z, 1e-9, "back up");
    }

    @Test
    public void fewerThanFivePressesOrASpreadOver50umIsNotAccepted() throws Exception {
        HeightSwitch.Result three = HeightSwitch.measure(new FakeSwitch(12.0, -1), plan("n1", -1, 13.0, 11.0, 3));
        assertEquals(3, three.trips.size());
        assertFalse(three.accepted, "3 presses: the mean for a frame shift, not a recorded trip");
        assertTrue(three.why == null && three.tripRawZ != null);
        // a switch whose trip wanders 0.07 mm between presses
        HeightSwitch.Plan p = plan("n1", -1, 13.0, 11.0, 5);
        HeightSwitch.Result spread = HeightSwitch.measure(new WanderingSwitch(), p);
        assertFalse(spread.accepted, "range " + spread.rangeMm);
        assertTrue(spread.rangeMm > 0.05, String.valueOf(spread.rangeMm));
        assertTrue(spread.why.contains("spread"), spread.why);
    }

    /**
     * Review MAJOR 1: a reply with no level to trust mid-descent is read once more after the pull-up (the PA5 notes); a
     * second one ends the measure with the tool sent up to travel Z, Z only, and the failure thrown.
     */
    @Test
    public void aBadReplyMidDescentIsReadOnceMoreThenTheToolGoesUpAndTheFailureIsThrown() throws Exception {
        HeightSwitch.Result once = HeightSwitch.measure(new BadReplies(1), plan("n1", -1, 13.0, 11.0, 5));
        assertTrue(once.accepted, "one bad reply, read once more: " + once.why);
        BadReplies twice = new BadReplies(2);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> HeightSwitch.measure(twice, plan("n1", -1, 13.0, 11.0, 5)));
        assertTrue(e.getMessage().contains("Analog in"), e.getMessage());
        List<String> l = twice.lines;
        assertEquals(Arrays.asList("G1 Z12.500 F300", "M400", "G4 P50", "M42 P53 T2", "M43 P5", "M42 P53 T2", "M43 P5",
                "G1 Z31.500 F600", "M400"), l.subList(l.size() - 9, l.size()), "read once more, then up: " + l);
        assertEquals(31.5, twice.z, 1e-9);
    }

    /** A switch whose M43 replies say analog mode {@code bad} times, from its first read at or below Z 12.5. */
    static final class BadReplies extends FakeSwitch {
        int left;

        BadReplies(int bad) {
            super(12.0, -1);
            left = bad;
        }

        @Override
        public List<String> query(String line) {
            List<String> reply = super.query(line);
            if (z > 12.5 + 1e-9 || left == 0) {
                return reply;
            }
            left--;
            return Arrays.asList("PIN: PA5  (A9)   M42 P53          <unused/unknown>  Analog in =     0   Input  = 0",
                    "ok");
        }
    }

    /** Review MAJOR 1: a line that times out mid-descent ends the measure with the tool at travel Z, the failure thrown. */
    @Test
    public void aTimeoutMidDescentSendsTheToolUpAndThrowsTheFailure() throws Exception {
        FakeSwitch sw = new FakeSwitch(12.0, -1);
        Exception timeout = new Exception("timeout waiting for response to M400");
        HeightSwitch.Io io = new HeightSwitch.Io() {
            boolean failed;

            @Override
            public void send(String line) throws Exception {
                sw.send(line);
                if (!failed && line.equals("M400") && sw.z <= 12.5 + 1e-9) {
                    failed = true;
                    throw timeout;
                }
            }

            @Override
            public List<String> query(String line) {
                return sw.query(line);
            }
        };
        Exception e = assertThrows(Exception.class, () -> HeightSwitch.measure(io, plan("n1", -1, 13.0, 11.0, 5)));
        assertSame(timeout, e);
        List<String> l = sw.lines;
        assertEquals(Arrays.asList("G1 Z12.500 F300", "M400", "G1 Z31.500 F600", "M400"), l.subList(l.size() - 4,
                l.size()), l.toString());
        assertEquals(31.5, sw.z, 1e-9);
    }

    /** A STOP mid-descent (the executor's abort) ends the measure where it is: no line after it, not even the lift. */
    @Test
    public void aStopMidDescentSendsNothingMore() throws Exception {
        FakeSwitch sw = new FakeSwitch(12.0, -1);
        HeightSwitch.Io io = new HeightSwitch.Io() {
            @Override
            public void send(String line) throws Exception {
                if (line.equals("M400") && sw.z <= 12.5 + 1e-9) {
                    throw new DispenseExecutor.AbortedException("STOP pressed in the web UI");
                }
                sw.send(line);
            }

            @Override
            public List<String> query(String line) {
                return sw.query(line);
            }
        };
        assertThrows(DispenseExecutor.AbortedException.class, () -> HeightSwitch.measure(io, plan("n1", -1, 13.0, 11.0,
                5)));
        assertEquals("G1 Z12.500 F300", sw.lines.get(sw.lines.size() - 1), "nothing after the STOP: " + sw.lines);
    }

    /** A switch that trips 0.07 mm lower at every other press (a press ends with the 1 mm lift above 12.5). */
    static final class WanderingSwitch implements HeightSwitch.Io {
        final FakeSwitch a = new FakeSwitch(12.0, -1), b = new FakeSwitch(11.93, -1);
        int lifts;
        double z = 31.5;

        @Override
        public void send(String line) {
            a.send(line);
            b.send(line);
            if (line.startsWith("G1 Z") && line.endsWith("F600") && a.z > 12.5 && z <= 12.5) {
                lifts++;
            }
            z = a.z;
        }

        @Override
        public List<String> query(String line) {
            return lifts % 2 == 0 ? a.query(line) : b.query(line);
        }
    }

    @Test
    public void aWindowOver2mmOrTheWrongWayIsRefusedBeforeAnyMove() throws Exception {
        FakeSwitch sw = new FakeSwitch(12.0, -1);
        HeightSwitch.Result wide = HeightSwitch.measure(sw, plan("n1", -1, 14.0, 11.0, 5));
        assertTrue(wide.refused && wide.why.contains("2.0 mm"), wide.why);
        HeightSwitch.Result wrong = HeightSwitch.measure(sw, plan("n1", -1, 11.0, 12.0, 5));
        assertTrue(wrong.refused && wrong.why.contains("below"), wrong.why);
        assertTrue(sw.lines.isEmpty(), "nothing sent: " + sw.lines);
    }

    /**
     * Review MINOR 7 and 9: refused before any line, each with a plain reason: a window outside Z's soft limits (N1
     * zStart 1, zFloor -1 under a low limit of 0; the needle past a high one), a travel Z outside Z's safe zone, a
     * start above the travel height. An open side limits nothing.
     */
    @Test
    public void aWindowOutsideTheLimitsATravelZOutsideTheSafeZoneOrAStartAboveTravelIsRefused() throws Exception {
        HeightSwitch.Plan low = plan("n1", -1, 1.0, -1.0, 5);
        low.zLimits = new double[] {0, 63};
        String why = HeightSwitch.windowProblem(low);
        assertTrue(why != null && why.contains("Z -1.000 lies outside Z's soft limits 0.000..63.000"), why);
        FakeSwitch sw = new FakeSwitch(0.0, -1);
        HeightSwitch.Result r = HeightSwitch.measure(sw, low);
        assertTrue(r.refused && sw.lines.isEmpty(), "nothing sent: " + sw.lines);
        low.zLimits = new double[] {Double.NaN, 63};
        assertNull(HeightSwitch.windowProblem(low), "no low limit");
        HeightSwitch.Plan high = plan("needle", 1, 62.0, 63.5, 5);
        high.zLimits = new double[] {0, 63};
        why = HeightSwitch.windowProblem(high);
        assertTrue(why != null && why.contains("Z 63.500 lies outside"), why);
        HeightSwitch.Plan travel = plan("n1", -1, 13.0, 11.0, 5);
        travel.safeZone = new double[] {26.5, 30};
        why = HeightSwitch.windowProblem(travel);
        assertTrue(why != null && why.contains("travel Z 31.500 lies outside Z's safe zone 26.500..30.000"), why);
        travel.safeZone = new double[] {26.5, Double.NaN};
        assertNull(HeightSwitch.windowProblem(travel));
        why = HeightSwitch.windowProblem(plan("n1", -1, 32.0, 31.0, 5));
        assertTrue(why != null && why.contains("the start (Z 32.000) lies above the travel height (Z 31.500)"), why);
    }

    /**
     * The config keeps each tool's accepted trip and the forbidden circle's radius (Gson-saved): a file from before
     * them loads with none and the default radius, a null is put right, a copy keeps them, the radius is bounded.
     */
    @Test
    public void theConfigKeepsEachToolsTripAndAnOldFileLoads() {
        DispenseConfig old = DispenseConfig.fromJson("{\"schemaVersion\": 4, \"outputZones\": {\"forbidRadiusMm\": "
                + "15}}");
        assertNull(old.heightSwitch.n1);
        assertNull(old.heightSwitch.needle);
        assertEquals(10, old.outputZones.heightSwitchRadiusMm, 0);
        assertEquals(new ArrayList<>(), old.validate());
        assertNull(DispenseConfig.fromJson("{\"heightSwitch\": null}").heightSwitch.n1);
        DispenseConfig c = new DispenseConfig();
        DispenseConfig.HeightSwitchTrip t = new DispenseConfig.HeightSwitchTrip();
        t.tripRawZ = 12.0125;
        t.rangeMm = 0.025;
        t.sdMm = 0.01;
        t.presses = 5;
        t.atMs = 1000;
        t.homeAtMs = 900L;
        t.releases = Arrays.asList(12.5, 12.5, 12.5, 12.5, 12.5);
        t.differentialMm = 0.5;
        c.heightSwitch.n1 = t;
        DispenseConfig copy = c.copy();
        assertEquals(12.0125, copy.heightSwitch.n1.tripRawZ, 0);
        assertEquals(5, copy.heightSwitch.n1.presses);
        assertEquals(Long.valueOf(900), copy.heightSwitch.n1.homeAtMs);
        assertEquals(t.releases, copy.heightSwitch.n1.releases);
        assertEquals(Double.valueOf(0.5), copy.heightSwitch.n1.differentialMm);
        DispenseConfig.HeightSwitchTrip before = DispenseConfig.fromJson("{\"heightSwitch\": {\"n1\": {\"tripRawZ\": "
                + "12}}}").heightSwitch.n1;
        assertTrue(before.releases == null && before.differentialMm == null, "a trip from before them has none");
        assertNull(copy.heightSwitch.needle);
        c.outputZones.heightSwitchRadiusMm = 0;
        assertTrue(c.validate().stream().anyMatch(x -> x.contains("heightSwitchRadiusMm")), c.validate().toString());
        c.outputZones.heightSwitchRadiusMm = 40;
        assertTrue(c.validate().stream().anyMatch(x -> x.contains("heightSwitchRadiusMm")), c.validate().toString());
        c.outputZones.heightSwitchRadiusMm = 10;
        assertEquals("height-switch", HeightSwitch.PLACE);
        // how far past the trip the needle may press the switch: Process's, no default; above 0 and at most the window
        assertNull(old.heightSwitchMaxPastTripMm, "no default");
        for (double bad : new double[] {0, -0.5, 2.5, Double.NaN}) {
            c.heightSwitchMaxPastTripMm = bad;
            assertTrue(c.validate().stream().anyMatch(x -> x.contains("heightSwitchMaxPastTripMm")), bad + " "
                    + c.validate());
        }
        c.heightSwitchMaxPastTripMm = 0.5;
        assertEquals(new ArrayList<>(), c.validate());
        assertEquals(Double.valueOf(0.5), c.copy().heightSwitchMaxPastTripMm);
    }

    @Test
    public void readsTheBoardsM43Reply() {
        assertEquals(Integer.valueOf(0), HeightSwitch.level(Arrays.asList(
                "PIN: PA5  (A9)   M42 P53          <unused/unknown>                       Input  = 0", "ok")));
        assertEquals(Integer.valueOf(1), HeightSwitch.level(Arrays.asList("PIN: PA5 M42 P53 Input = 1", "ok")));
        // the PA5 source check (11-04): an input with its pull-up reads "Input = 0|1"; "Analog in" means the pin left
        // input mode (no pull-up), "Output" that something drives it, "protected" that the firmware won't read it: no
        // level to trust in any of them
        assertThrows(IllegalStateException.class, () -> HeightSwitch.level(Arrays.asList(
                "PIN: PA5 M42 P53 Analog in = 4000")), "analog mode");
        assertThrows(IllegalStateException.class, () -> HeightSwitch.level(Arrays.asList(
                "PIN: PA5  (A9)   M42 P53          <unused/unknown>                       Output = 1")), "an output");
        assertThrows(IllegalStateException.class, () -> HeightSwitch.level(Arrays.asList(
                "PIN: PA5  (A9)   M42 P53          <unused/unknown>  protected  Input  = 1")), "protected");
        assertThrows(IllegalStateException.class, () -> HeightSwitch.level(Arrays.asList("PIN: PC0 Input = 1")),
                "a reply that names another pin");
        assertThrows(IllegalStateException.class, () -> HeightSwitch.level(Arrays.asList("ok")));
    }
}
