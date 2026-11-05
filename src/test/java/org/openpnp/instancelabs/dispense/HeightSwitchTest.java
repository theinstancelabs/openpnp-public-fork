package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

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
            int level = stuck || pressed() ? 1 - idle : idle;
            return Arrays.asList(String.format(Locale.ROOT,
                    "PIN: PA5  (A9)   M42 P53          <unused/unknown>                       Input  = %d", level), "ok");
        }

        /** The deepest Z the tool reached, past the trip (mm; 0 when it never got there). */
        double deepestPast() {
            double worst = 0;
            for (String l : lines) {
                Matcher m = Z.matcher(l);
                if (l.startsWith("G1") && m.find()) {
                    worst = Math.max(worst, down * (Double.parseDouble(m.group(1)) - trip));
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

    @Test
    public void aSwitchThatSticksIsRefused() throws Exception {
        FakeSwitch sw = new FakeSwitch(12.0, -1) {
            @Override
            boolean pressed() {
                return stuck || super.pressed();
            }
        };
        HeightSwitch.Io io = new HeightSwitch.Io() {
            @Override
            public void send(String line) {
                sw.send(line);
                if (line.startsWith("G1") && sw.pressed()) {
                    sw.stuck = true; // from the first press on it never lets go
                }
            }

            @Override
            public List<String> query(String line) {
                return sw.query(line);
            }
        };
        HeightSwitch.Result r = HeightSwitch.measure(io, plan("n1", -1, 13.0, 11.0, 5));
        assertTrue(r.refused && r.why.contains("still pressed 0.40 mm above") && r.why.contains("sticks"), r.why);
        assertEquals("G1 Z31.500 F600", sw.lines.get(sw.lines.size() - 2), sw.lines.toString());
    }

    @Test
    public void noTripBeforeTheFloorIsNotAccepted() throws Exception {
        FakeSwitch sw = new FakeSwitch(5.0, -1);
        HeightSwitch.Result r = HeightSwitch.measure(sw, plan("n1", -1, 13.0, 11.0, 5));
        assertFalse(r.accepted);
        assertFalse(r.refused);
        assertTrue(r.why.contains("no trip between") && r.why.contains("13.000") && r.why.contains("11.000"), r.why);
        assertTrue(sw.deepestPast() == 0 && Math.abs(sw.z - 31.5) < 1e-9, "never below the floor, back up");
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
        c.heightSwitch.n1 = t;
        DispenseConfig copy = c.copy();
        assertEquals(12.0125, copy.heightSwitch.n1.tripRawZ, 0);
        assertEquals(5, copy.heightSwitch.n1.presses);
        assertEquals(Long.valueOf(900), copy.heightSwitch.n1.homeAtMs);
        assertNull(copy.heightSwitch.needle);
        c.outputZones.heightSwitchRadiusMm = 0;
        assertTrue(c.validate().stream().anyMatch(x -> x.contains("heightSwitchRadiusMm")), c.validate().toString());
        c.outputZones.heightSwitchRadiusMm = 40;
        assertTrue(c.validate().stream().anyMatch(x -> x.contains("heightSwitchRadiusMm")), c.validate().toString());
        assertEquals("height-switch", HeightSwitch.PLACE);
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
