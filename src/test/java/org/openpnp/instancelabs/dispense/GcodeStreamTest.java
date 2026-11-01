package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * G-code stream generation (bench recipe 2026-10-28): golden output for a 3-pad toy board with the
 * purge row, the per-dot line sequence, whole-step B targets, the start-angle phase, the needle XY
 * trim, plus the validator and controller-command rules.
 */
public class GcodeStreamTest {
    /**
     * Toy setup in the bench needle frame: N1 contacts ~5.5 mm, the needle touches 2.59 mm lower in
     * N2's frame (raw Z = 63 - N2 Z, so a dot at the 0.10 gap is at raw 65.49 - N1 contact Z). The
     * purge row lies on a probed blank: 16 spots 2.5 mm apart (two boards of 8 purge dots).
     */
    static DispenseConfig toyConfig() {
        DispenseConfig c = new DispenseConfig();
        c.locations.put("waste", new DispenseConfig.NamedLocation(60, 30, 5.5));
        c.needleZOffsetMm = -2.59;
        c.minPadTypeStatus = "provisional";
        c.purgeRow.x0Mm = 60.0;
        c.purgeRow.y0Mm = 40.0;
        c.purgeRow.spots = 16;
        c.surfaces.put("blank", surface(new double[][] {{55, 35, 5.45}, {105, 35, 5.45}, {55, 45, 5.45},
            {105, 45, 5.45}}));
        return c;
    }

    static DispenseConfig.SurfaceMap surface(double[][] p) {
        DispenseConfig.SurfaceMap m = new DispenseConfig.SurfaceMap();
        for (double[] v : p) {
            m.points.add(new DispenseConfig.SurfacePoint(v[0], v[1], v[2]));
        }
        return m;
    }

    /** Three 0603 pads on a board whose surface is at N1 Z 5.5; the path starts at the purge row's end. */
    static DispensePlan toyPlan(DispenseConfig c) {
        PadType t = DispenseDb.seedPadTypes().get(1); // chip-0603
        t.status = PadTypeStatus.PROVISIONAL;
        t.version = 1;
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(new PadGeometry("R2.1", 110.0, 85.0, 90, 0.8, 0.95, 5.5, t));
        pads.add(new PadGeometry("R1.2", 101.65, 80.0, 0, 0.8, 0.95, 5.5, t));
        pads.add(new PadGeometry("R1.1", 100.0, 80.0, 0, 0.8, 0.95, 5.5, t));
        DispensePlan plan = new DispensePlanner(c, new PadTypeResolver(Collections.singletonList(t), null))
                .plan("toy", pads, 77.5, 40);
        plan.planId = "golden";
        return plan;
    }

    static List<GcodeStreamBuilder.DotSpec> purge(DispenseConfig c) {
        return DispenseService.purgeSpecs(c);
    }

    /** The board program as DispenseService builds it: the next purge spots of the row, then the plan. */
    static GcodeProgram board(DispenseConfig c, RawMapper m, DispensePlan plan, boolean bZeroed, boolean keepalive,
            Long bCount) throws Exception {
        return GcodeStreamBuilder.forPlan(c, m, plan, purge(c), bZeroed, keepalive, bCount);
    }

    static ToyMapper mapper() {
        return new ToyMapper(150, 100, 31.5, 0);
    }

    static void assertGolden(String name, String actual) throws Exception {
        String golden;
        File out = new File("target/" + name + ".actual");
        try (InputStream in = GcodeStreamTest.class.getResourceAsStream("/dispense/" + name)) {
            if (in == null) {
                Files.write(out.toPath(), actual.getBytes(StandardCharsets.UTF_8));
                throw new AssertionError("golden file missing; actual written to " + out.getAbsolutePath());
            }
            golden = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!golden.equals(actual)) {
            Files.write(out.toPath(), actual.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(golden, actual);
    }

    private static final Pattern B_WORD = Pattern.compile("^G1 B(-?[0-9.]+) F");

    /** B targets of the G1 B lines, in order (forward, back per dot). */
    static List<Double> bTargets(GcodeProgram p) {
        List<Double> b = new ArrayList<>();
        for (String l : p.text()) {
            Matcher m = B_WORD.matcher(l);
            if (m.find()) {
                b.add(Double.parseDouble(m.group(1)));
            }
        }
        return b;
    }

    /** Whole motor steps of a B target (deg x 4.44); fails if the target is not a whole step. */
    static long steps(double b) {
        double s = b * 4.44;
        long n = Math.round(s);
        assertEquals(n, s, 1e-3, "B" + b + " is not a whole number of steps");
        return n;
    }

    /** The non-comment lines of every dot, DOT_BEGIN .. DOT_END. */
    static List<List<GcodeProgram.Line>> dotLines(GcodeProgram p) {
        List<List<GcodeProgram.Line>> dots = new ArrayList<>();
        List<GcodeProgram.Line> cur = null;
        for (GcodeProgram.Line l : p.lines) {
            if (l.isComment()) {
                continue;
            }
            if (l.has(GcodeProgram.Marker.DOT_BEGIN)) {
                cur = new ArrayList<>();
            }
            if (cur != null) {
                cur.add(l);
            }
            if (l.has(GcodeProgram.Marker.DOT_END)) {
                dots.add(cur);
                cur = null;
            }
        }
        return dots;
    }

    @Test
    public void golden3PadBoardWithLiveBacklash() throws Exception {
        DispenseConfig c = toyConfig();
        ToyMapper m = mapper();
        // live X/Y: OneSidedPositioning, 0.1 mm, speed factor 0.5; z1 DirectionalCompensation 0 mm
        m.backlash = new AxisBacklash[] {
            new AxisBacklash(org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod.OneSidedPositioning, 0.1, 0.5, 0.226, 1e-4),
            new AxisBacklash(org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod.OneSidedPositioning, 0.1, 0.5, 0.16, 1e-4),
            new AxisBacklash(org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod.DirectionalCompensation, 0.0, 0.1, 0, 1e-4)};
        GcodeProgram p = board(c, m, toyPlan(c), false, false, 12345L);
        assertGolden("golden-3pad-backlash.gcode", p.asString());
        assertTrue(GcodeStreamValidator.validate(p, c, "B").isEmpty(), GcodeStreamValidator.validate(p, c, "B")
                .toString());
    }

    @Test
    public void golden3PadBoard() throws Exception {
        DispenseConfig c = toyConfig();
        DispensePlan plan = toyPlan(c);
        GcodeProgram p = board(c, mapper(), plan, false, false, 0L);
        assertGolden("golden-3pad.gcode", p.asString());
        assertTrue(GcodeStreamValidator.validate(p, c, "B").isEmpty(), GcodeStreamValidator.validate(p, c, "B").toString());
        assertEquals(3, p.dotCount);
        assertEquals(8, p.purgeDotCount);
        assertEquals(0L, (long) p.bCountAtStart);
        assertEquals(0.0, p.finalB, 0);
        // final state: N2 at travel Z above the last dot
        assertEquals(31.5, p.finalNozzle[2], 1e-12);
        assertEquals(63 - 31.5, p.finalPlanned[2], 1e-12);
        // one pressure session
        assertEquals(1, Collections.frequency(p.text(), ControllerCommands.PUMP_ON));
        assertEquals(1, Collections.frequency(p.text(), ControllerCommands.VALVE_ON));
        // dots at the bench height: raw Z = 63 - (5.5 - 2.59 + 0.10) = 59.99 (bench 65.49 - N1 contact)
        assertTrue(p.text().contains("G1 Z59.9900 F3000"), p.asString());
    }

    /**
     * Every dot: XY at the hop height, Z down, the screw's acceleration, B forward (net + suck-back),
     * dwell, B back by the suck-back, the XY/Z acceleration again, lift. The first purge dot needs no
     * XY move (the needle travelled above it before the pressure went on).
     */
    @Test
    public void perDotLineSequence() throws Exception {
        DispenseConfig c = toyConfig();
        GcodeProgram p = board(c, mapper(), toyPlan(c), false, false, 0L);
        List<List<GcodeProgram.Line>> dots = dotLines(p);
        assertEquals(11, dots.size());
        String[] seq = {"G0( X-?\\d+\\.\\d{4})?( Y-?\\d+\\.\\d{4})? F12000", "G1 Z-?\\d+\\.\\d{4} F3000", "M204 S20000",
            "G1 B-?\\d+\\.\\d{4} F64800", "G4 P250", "G1 B-?\\d+\\.\\d{4} F64800", "M204 S2000",
            "G1 Z-?\\d+\\.\\d{4} F3000"};
        for (int i = 0; i < dots.size(); i++) {
            List<GcodeProgram.Line> d = dots.get(i);
            int skip = i == 0 || i == 8 ? 1 : 0; // first purge dot, first work dot: XY already there
            assertEquals(seq.length - skip, d.size(), "dot " + i + ": " + texts(d));
            for (int k = 0; k < d.size(); k++) {
                assertTrue(d.get(k).text.matches(seq[k + skip]), "dot " + i + " line " + k + ": " + d.get(k).text);
            }
            if (skip == 0) {
                assertTrue(d.get(0).text.contains("X") || d.get(0).text.contains("Y"), d.get(0).text);
            }
            assertEquals(i < 8 ? GcodeStreamBuilder.PURGE_INDEX : i - 8, d.get(0).dotIndex);
        }
    }

    static List<String> texts(List<GcodeProgram.Line> l) {
        List<String> t = new ArrayList<>();
        for (GcodeProgram.Line x : l) {
            t.add(x.text);
        }
        return t;
    }

    /**
     * The first purge dot turns the screw from the reported step count to the angle that, after the
     * other 7 purge dots, starts the work at the profile's start angle (1324 of 1600 steps = 297.9
     * deg); every later dot turns exactly 800 steps, so the work dots alternate 297.9 / 117.9 deg.
     */
    @Test
    public void firstPurgeDotSetsThePhaseForAnyStepCount() throws Exception {
        DispenseConfig c = toyConfig();
        DispensePlan plan = toyPlan(c);
        int start = c.profile.startAngleSteps(c);
        assertEquals(1324, start);
        for (long count : new long[] {0, 1, 524, 799, 1324, 1325, 12345, -5000}) {
            GcodeProgram p = board(c, mapper(), plan, false, false, count);
            assertEquals(8, p.purgeDotCount);
            assertEquals(count, (long) p.bCountAtStart);
            assertEquals(start, Math.floorMod(p.workStartCount, 1600), "count " + count);
            assertEquals(297.9, p.workStartAngleDeg, 1e-9);
            assertTrue(Math.abs(p.workStartAngleDeg - c.profile.startAngleDeg) <= 360.0 / 1600 / 2);
            List<Double> b = bTargets(p);
            assertEquals(2 * 11, b.size());
            // G92 B0 at the session start: the Marlin count of a target is count + its steps
            long phase = steps(b.get(1));
            assertTrue(phase >= 89 && phase < 1600 + 89, "phase turn " + phase + " (at least 20 deg, at most one rev more)");
            for (int k = 0; k < 11; k++) {
                long rest = steps(b.get(2 * k + 1));
                assertEquals(phase + 800L * k, rest, "dot " + k);
                assertEquals(rest + 44, steps(b.get(2 * k)), "forward = rest + suck-back (44 steps)");
            }
            assertEquals(count + steps(b.get(15)), (long) p.workStartCount);
            for (int w = 0; w < 3; w++) {
                long restBefore = count + steps(b.get(2 * (8 + w) - 1));
                assertEquals(w % 2 == 0 ? 1324 : 524, Math.floorMod(restBefore, 1600), "work dot " + w);
            }
        }
        // a count that needs no phase turn at all gets a full revolution (at least 20 deg)
        assertEquals(1600, steps(bTargets(board(c, mapper(), plan, false, false, 524L)).get(1)));
    }

    /** B already zeroed at a non-zero OpenPnP B: targets are relative to it, the phase still holds. */
    @Test
    public void phaseFromAnOriginOtherThanZero() throws Exception {
        DispenseConfig c = toyConfig();
        ToyMapper m = new ToyMapper(150, 100, 31.5, 100); // B at 100 deg = 444 steps
        GcodeProgram p = board(c, m, toyPlan(c), true, false, 777L);
        assertTrue(p.text().indexOf("G92 B0") > p.text().indexOf("M106 P2 S255"),
                "B already zeroed: no G92 at the session start (only the re-zero at its end)");
        List<Double> b = bTargets(p);
        long restBeforeWork = 777 + steps(b.get(15)) - 444;
        assertEquals(1324, Math.floorMod(restBeforeWork, 1600));
        assertEquals(restBeforeWork, (long) p.workStartCount);
    }

    /** No drift: 1000 dots later every rest is still a whole number of 800-step turns from the first. */
    @Test
    public void restTargetsAreWholeTurnsOverAThousandDots() throws Exception {
        DispenseConfig c = toyConfig();
        DispensePlan plan = new DispensePlan();
        plan.planId = "long";
        for (int i = 0; i < 1000; i++) {
            DispensePlan.PlannedDot d = new DispensePlan.PlannedDot();
            d.index = i;
            d.padId = "P" + i;
            d.xMm = 100 + (i % 40) * 2;
            d.yMm = 80 + (i / 40) * 2;
            d.dispenseZMm = 5.5 - 2.59 + 0.10;
            d.hopZMm = d.dispenseZMm + 1;
            d.rotationDeg = c.profile.netDeg(c);
            plan.dots.add(d);
        }
        GcodeProgram p = board(c, mapper(), plan, false, false, 4321L);
        List<Double> b = bTargets(p);
        assertEquals(2 * 1008, b.size());
        long first = steps(b.get(2 * 8 - 1)); // rest before the first work dot
        for (int k = 8; k < 1008; k++) {
            assertEquals(800L * (k - 7), steps(b.get(2 * k + 1)) - first, "work dot " + (k - 8));
        }
        assertTrue(GcodeStreamValidator.validate(p, c, "B").isEmpty());
        // calibration dots (no purge): B = n x 800 steps from the session's G92 B0
        List<GcodeStreamBuilder.DotSpec> specs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            specs.add(new GcodeStreamBuilder.DotSpec(100 + (i % 40) * 2, 80 + (i / 40) * 2, 2.91, 3.91,
                    c.profile.netDeg(c), i, "d"));
        }
        List<Double> bd = bTargets(GcodeStreamBuilder.forDots(c, mapper(), specs, false, false, false, "drift"));
        for (int k = 0; k < 1000; k++) {
            assertEquals(800.0 * (k + 1), bd.get(2 * k + 1) * 4.44, 1e-3, "dot " + k);
            assertEquals(800.0 * (k + 1) + 44, bd.get(2 * k) * 4.44, 1e-3, "dot " + k);
        }
    }

    /**
     * The cross from the purge row to the work: lift to travel Z and XY, no M400 and no dwell (any
     * pause with the pressure on lets paste ooze onto the tip).
     */
    @Test
    public void purgeCrossesToTheWorkWithoutStopping() throws Exception {
        DispenseConfig c = toyConfig();
        GcodeProgram p = board(c, mapper(), toyPlan(c), false, false, 0L);
        int lastPurgeEnd = -1;
        int firstWorkBegin = -1;
        for (int i = 0; i < p.lines.size(); i++) {
            GcodeProgram.Line l = p.lines.get(i);
            if (l.has(GcodeProgram.Marker.DOT_END) && l.dotIndex == GcodeStreamBuilder.PURGE_INDEX) {
                lastPurgeEnd = i;
            }
            if (l.has(GcodeProgram.Marker.DOT_BEGIN) && l.dotIndex >= 0 && firstWorkBegin < 0) {
                firstWorkBegin = i;
            }
        }
        assertTrue(lastPurgeEnd > 0 && firstWorkBegin > lastPurgeEnd);
        List<String> between = new ArrayList<>();
        for (GcodeProgram.Line l : p.lines.subList(lastPurgeEnd + 1, firstWorkBegin)) {
            if (!l.isComment()) {
                between.add(l.text);
            }
        }
        assertEquals(2, between.size(), between.toString());
        assertEquals("G1 Z31.5000 F3000", between.get(0));
        assertTrue(between.get(1).matches("G0 X\\S+ Y\\S+ F12000"), between.get(1));
        // the only M400s are the two of the session end, after the last dot
        int lastDotEnd = -1;
        for (int i = 0; i < p.lines.size(); i++) {
            if (p.lines.get(i).has(GcodeProgram.Marker.DOT_END)) {
                lastDotEnd = i;
            }
        }
        List<String> text = p.text();
        assertEquals(2, Collections.frequency(text, "M400"));
        assertTrue(text.indexOf("M400") > lastDotEnd);
    }

    @Test
    public void partialTurnPurgeNeedsTheStepCount() throws Exception {
        DispenseConfig c = toyConfig();
        DispensePlan plan = toyPlan(c);
        Exception e = assertThrows(IllegalStateException.class, () -> board(c, mapper(), plan, false, false, null));
        assertTrue(e.getMessage().contains("M114 Count"), e.getMessage());
        // a whole revolution per dot repeats the angle whatever it is: no step count needed
        DispenseConfig full = toyConfig();
        full.profile.netStepsPerDot = 1600;
        assertTrue(full.validate().isEmpty(), full.validate().toString());
        GcodeProgram p = board(full, mapper(), toyPlan(full), false, false, null);
        assertEquals(8, p.purgeDotCount);
        assertNull(p.workStartAngleDeg);
        assertNull(p.bCountAtStart);
        List<Double> b = bTargets(p);
        for (int k = 0; k < 11; k++) {
            assertEquals(1600L * (k + 1), steps(b.get(2 * k + 1)));
        }
        assertTrue(GcodeStreamValidator.validate(p, full, "B").isEmpty());
    }

    /** The needle lands at camera + head offset + trim, so every dispense target moves by minus the trim. */
    @Test
    public void needleXyTrimMovesEveryTargetByMinusTheTrim() throws Exception {
        DispenseConfig c = toyConfig();
        DispensePlan plan = toyPlan(c);
        List<String> plain = board(c, mapper(), plan, false, false, 0L).text();
        c.needleXyTrim.dxMm = 0.05;
        c.needleXyTrim.dyMm = -0.03;
        List<String> trimmed = board(c, mapper(), plan, false, false, 0L).text();
        assertEquals(plain.size(), trimmed.size());
        Pattern x = Pattern.compile(" X(-?[0-9.]+)");
        Pattern y = Pattern.compile(" Y(-?[0-9.]+)");
        int words = 0;
        for (int i = 0; i < plain.size(); i++) {
            String a = plain.get(i), t = trimmed.get(i);
            if (!a.startsWith("G0") && !a.startsWith("G1")) {
                assertEquals(a, t);
                continue;
            }
            Double ax = word(x, a), tx = word(x, t), ay = word(y, a), ty = word(y, t);
            assertEquals(ax == null, tx == null, a + " / " + t);
            assertEquals(ay == null, ty == null, a + " / " + t);
            if (ax != null) {
                assertEquals(ax - 0.05, tx, 1e-6, t);
                words++;
            }
            if (ay != null) {
                assertEquals(ay + 0.03, ty, 1e-6, t);
                words++;
            }
        }
        assertTrue(words >= 11, "XY words compared: " + words);
    }

    static Double word(Pattern p, String line) {
        Matcher m = p.matcher(line);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    @Test
    public void abortLinesAreTheStopSequence() {
        DispenseConfig c = toyConfig();
        List<String> f9 = java.util.Arrays.asList("M410", "M107 P3", "M107 P2", "M107 P1", "M107 P0", "M18 B");
        assertEquals(f9, ControllerCommands.STOP_SEQUENCE);
        assertEquals(f9, GcodeStreamBuilder.abortLines(c, false, "B"));
        List<String> armed = new ArrayList<>(f9);
        armed.add("M990 S0");
        assertEquals(armed, GcodeStreamBuilder.abortLines(c, true, "B"));
        assertEquals("M18 J", GcodeStreamBuilder.abortLines(c, false, "J").get(5));
        for (String l : armed) {
            assertNull(ControllerCommands.check(l), l);
        }
    }

    @Test
    public void negativeExtrudeSignAndZeroDwell() throws Exception {
        DispenseConfig c = toyConfig();
        c.bExtrudeSign = -1;
        c.profile.dwellMs = 0;
        GcodeProgram p = board(c, mapper(), toyPlan(c), true, false, 5000L);
        String s = p.asString();
        assertFalse(s.contains("G92 B0\nM201"), "B already zeroed: no G92 at session start");
        List<Double> b = bTargets(p);
        for (double v : b) {
            assertTrue(v < 0, "extrusion is negative B: " + v);
        }
        // the Marlin count falls as the screw extrudes; the work still starts at 1324 of 1600 steps
        assertEquals(1324, Math.floorMod(5000 + steps(b.get(15)), 1600));
        assertEquals(1324, Math.floorMod(p.workStartCount, 1600));
        int dotG4 = 0;
        for (List<GcodeProgram.Line> d : dotLines(p)) {
            for (GcodeProgram.Line l : d) {
                dotG4 += l.text.startsWith("G4") ? 1 : 0;
            }
        }
        assertEquals(0, dotG4, "dwell 0 -> no per-dot G4 (no planner synchronisation)");
        assertTrue(GcodeStreamValidator.validate(p, c, "B").isEmpty());
    }

    @Test
    public void keepaliveSplitsLongDwells() throws Exception {
        DispenseConfig c = toyConfig();
        c.pumpToValveSettleMs = 2500;
        GcodeProgram p = board(c, mapper(), toyPlan(c), false, true, 0L);
        String s = p.asString();
        assertTrue(s.contains("M990 S3000"));
        assertTrue(s.contains("M106 P2 S255\nG4 P1000\nG4 P1000\nG4 P500\nM106 P3 S255\nG4 P1000\nG4 P1000\nG4 P1000\n"));
        assertTrue(s.contains("M990 S0"));
        assertTrue(GcodeStreamValidator.validate(p, c, "B").isEmpty());
    }

    @Test
    public void sessionStartSetsTheBLimitsAndTheXyzAcceleration() throws Exception {
        DispenseConfig c = toyConfig();
        List<String> t = board(c, mapper(), toyPlan(c), false, false, 0L).text();
        int g92 = t.indexOf("G92 B0");
        assertEquals(java.util.Arrays.asList("G92 B0", "M201 B20000", "M203 B2400", "M204 S2000"), t.subList(g92,
                g92 + 4));
        assertTrue(g92 < t.indexOf(ControllerCommands.PUMP_ON));
        // the session end vents, re-zeroes B and lifts
        int end = t.lastIndexOf("M400");
        assertEquals(java.util.Arrays.asList("M400", "M107 P3", "M107 P2", "G92 B0", "G1 Z31.5000 F3000", "M400"),
                t.subList(t.size() - 6, t.size()));
        assertEquals(t.size() - 1, end);
    }

    /** Move time from rest to rest, with the acceleration (trapezoid, or triangle on a short move). */
    @Test
    public void estimateIncludesAcceleration() throws Exception {
        assertEquals(100 / 200.0 + 200 / 2000.0, GcodeStreamBuilder.moveSeconds(100, 200, 2000), 1e-12);
        assertEquals(2 * Math.sqrt(10 / 2000.0), GcodeStreamBuilder.moveSeconds(10, 200, 2000), 1e-12);
        assertEquals(0.5, GcodeStreamBuilder.moveSeconds(100, 200, Double.NaN), 1e-12);
        assertEquals(0, GcodeStreamBuilder.moveSeconds(0, 200, 2000), 0);
        // half-turn dot: 844 steps forward at 1080 deg/s, 20000 deg/s^2
        double fwd = 844 / 4.44;
        assertEquals(fwd / 1080 + 1080 / 20000.0, GcodeStreamBuilder.moveSeconds(fwd, 1080, 20000), 1e-12);
        DispenseConfig c = toyConfig();
        GcodeProgram p = board(c, mapper(), toyPlan(c), false, false, 0L);
        // at least: settles 4 s + 11 dwells + 11 forward turns
        assertTrue(p.estimatedSeconds > 4 + 11 * (0.25 + fwd / 1080 + 1080 / 20000.0), "" + p.estimatedSeconds);
        assertTrue(p.estimatedPressurisedSeconds < p.estimatedSeconds);
    }

    @Test
    public void calibrationDotsPrimeAtTheWaste() throws Exception {
        DispenseConfig c = toyConfig();
        List<GcodeStreamBuilder.DotSpec> specs = new ArrayList<>();
        specs.add(new GcodeStreamBuilder.DotSpec(62, 30, 2.96, 3.96, 180.1802, 0, "d"));
        GcodeProgram p = GcodeStreamBuilder.forDots(c, mapper(), specs, false, false, true, "prime");
        String s = p.asString();
        assertTrue(s.contains("; dot -1 prime rot 360.360"), s);
        assertEquals(1, p.dotCount);
        List<Double> b = bTargets(p);
        assertEquals(1600, steps(b.get(1)), "the prime is one screw revolution (360.36 deg = 1600 steps)");
        assertEquals(2400, steps(b.get(3)));
        assertTrue(GcodeStreamValidator.validate(p, c, "B").isEmpty());
    }

    @Test
    public void missingWasteLocationRefusesPrime() {
        DispenseConfig c = toyConfig();
        c.locations.clear();
        assertThrows(Exception.class, () -> GcodeStreamBuilder.forDots(c, mapper(), new ArrayList<>(), false, false,
                true, "prime"));
    }

    static GcodeProgram raw(String... lines) {
        GcodeProgram p = new GcodeProgram();
        for (String l : lines) {
            p.lines.add(new GcodeProgram.Line(l, GcodeProgram.Marker.NONE, -9, null, 0, null));
        }
        return p;
    }

    @Test
    public void validatorCatchesUnsafePrograms() {
        DispenseConfig c = toyConfig();
        // I2: B before G92 B0
        assertFalse(GcodeStreamValidator.validate(raw("M106 P2 S255", "M106 P3 S255", "G1 B10 F100", "M107 P3",
                "M107 P2"), c, "B").isEmpty());
        // valve before pump
        assertFalse(GcodeStreamValidator.validate(raw("M106 P3 S255", "M106 P2 S255", "M107 P3", "M107 P2"), c, "B")
                .isEmpty());
        // PWM on the valve (S180 is the live value that the plan forbids)
        assertFalse(GcodeStreamValidator.validate(raw("M106 P2 S255", "M106 P3 S180", "M107 P3", "M107 P2"), c, "B")
                .isEmpty());
        // pump off while valve on
        assertFalse(GcodeStreamValidator.validate(raw("M106 P2 S255", "M106 P3 S255", "M107 P2", "M107 P3"), c, "B")
                .isEmpty());
        // ends pressurised
        assertFalse(GcodeStreamValidator.validate(raw("M106 P2 S255", "M106 P3 S255"), c, "B").isEmpty());
        // B outside a pressure session
        assertFalse(GcodeStreamValidator.validate(raw("G92 B0", "G1 B10 F100"), c, "B").isEmpty());
        // fine
        assertTrue(GcodeStreamValidator.validate(raw("G92 B0", "M106 P2 S255", "M106 P3 S255", "G1 B10 F100",
                "M107 P3", "M107 P2"), c, "B").isEmpty());
    }

    /**
     * M204 S also governs X/Y/Z: the screw's 20000 deg/s^2 must only cover the B moves (bench F5),
     * and the B feed stays under the B limit (400 rpm).
     */
    @Test
    public void validatorKeepsTheScrewAccelerationOffXyzAndCapsTheBFeed() {
        DispenseConfig c = toyConfig();
        for (String move : new String[] {"G1 Z30.0000 F3000", "G0 X10.0000 Y10.0000 F12000", "G0 Y5 F12000",
            "G1 X1 B10 F3000"}) {
            List<String> p = GcodeStreamValidator.validate(raw("G92 B0", "M106 P2 S255", "M106 P3 S255", "M204 S20000",
                    "G1 B10 F64800", move, "M204 S2000", "M107 P3", "M107 P2"), c, "B");
            assertEquals(1, p.size(), move + " " + p);
            assertTrue(p.get(0).contains("under the screw acceleration"), p.toString());
        }
        // the same moves after the XY/Z acceleration is restored: fine
        assertTrue(GcodeStreamValidator.validate(raw("G92 B0", "M106 P2 S255", "M106 P3 S255", "M204 S20000",
                "G1 B10 F64800", "G4 P250", "G1 B8 F64800", "M204 S2000", "G1 Z30.0000 F3000", "G0 X10 Y10 F12000",
                "M107 P3", "M107 P2"), c, "B").isEmpty());
        // B feed: 2400 deg/s = F144000 is the limit
        assertTrue(GcodeStreamValidator.validate(raw("G92 B0", "M106 P2 S255", "M106 P3 S255", "G1 B10 F144000",
                "M107 P3", "M107 P2"), c, "B").isEmpty());
        List<String> fast = GcodeStreamValidator.validate(raw("G92 B0", "M106 P2 S255", "M106 P3 S255",
                "G1 B10 F144060", "M107 P3", "M107 P2"), c, "B");
        assertEquals(1, fast.size(), fast.toString());
        assertTrue(fast.get(0).contains("B feed above the B limit"), fast.toString());
    }

    @Test
    public void pneumaticAndWatchdogCommandsAreFixed() {
        // review finding: PWM on the pump (buck) or valve (S180), swapped ports, other outputs; the left
        // valve and pump (P1, P0) may only be switched off (Stop)
        for (String bad : new String[] {"M106 P2 S128", "M106 P3 S180", "m0106 p2 s128", "M106 P3 S255 X1",
            "M106 S255", "M106 P1 S255", "M106 P0", "M106 P0 S255", "M107", "M107 P1 S255", "M107 P4", "M42 P10 S255",
            "M43 T P10", "M3 S1000", "M990 S50", "M990 S1", "M990 P1", "M115 S1", "T0", "N5 M106 P2 S255", "$X"}) {
            assertTrue(ControllerCommands.check(bad) != null, bad);
        }
        for (String good : new String[] {"M106 P2 S255", "m106  p3  s255", "M107 P2", "M107 P3", "M107 P1", "M107 P0",
            "m107 p0", "M115", "M990", "M990 S0", "M990 S3000", "G1 Z31.5000 F1500", "G92 B0", "M400", "M410", "M18 B",
            "G4 P100", "M204 S200", "M201 B20000", "M203 B2400", "M114", "; comment"}) {
            assertTrue(ControllerCommands.check(good) == null, good);
        }
        DispenseConfig c = GcodeStreamTest.toyConfig();
        List<String> p = GcodeStreamValidator.validate(raw("M106 P2 S255", "M106 P3 S180", "M42 P10 S255",
                "M107 P1", "M107 P0", "M107 P3", "M107 P2"), c, "B");
        assertEquals(2, p.size(), p.toString());
        // the settings no longer carry G-code strings: nothing to edit, old files still load
        assertFalse(new DispenseConfig().toJson().contains("Gcode"));
        DispenseConfig old = DispenseConfig.fromJson("{\"schemaVersion\":1,\"valveOffGcode\":\"M107 P1\","
                + "\"keepalivePingGcode\":\"M106 P3 S180\",\"needleGapMm\":0.12}");
        assertEquals(0.10, old.gapMm(), 0, "the removed needleGapMm is ignored: the gap is the profile's");
        assertTrue(old.validate().isEmpty(), old.validate().toString());
        assertTrue(new DispenseConfig().validate().isEmpty());
        DispenseConfig w = new DispenseConfig();
        w.keepaliveWindowMs = 1500; // less than 2 x the 1000 ms ping interval
        assertEquals(1, w.validate().size());
    }

    /** The Stop sequence is the bench's F9 (machine.xml DISABLE_COMMAND) and passes the command guard. */
    @Test
    public void stopSequenceIsAllowedAndInOrder() {
        List<String> s = ControllerCommands.stopSequence("B");
        assertEquals("M410", s.get(0));
        assertTrue(s.indexOf(ControllerCommands.VALVE_OFF) < s.indexOf(ControllerCommands.PUMP_OFF), "vent first");
        assertTrue(s.indexOf(ControllerCommands.LEFT_VALVE_OFF) < s.indexOf(ControllerCommands.LEFT_PUMP_OFF));
        assertEquals("M18 B", s.get(s.size() - 1));
        for (String l : s) {
            assertNull(ControllerCommands.check(l), l);
        }
        assertThrows(UnsupportedOperationException.class, () -> s.add("M106 P2 S255"));
    }

    /** The generated board program validates clean, including the I4 raw Z window of the needle frame. */
    @Test
    public void boardProgramValidatesCleanInTheNeedleFrame() throws Exception {
        DispenseConfig c = toyConfig();
        c.needleAnchor = new DispenseConfig.NeedleAnchor(5.5, 2.91, null, null, 1000L);
        ToyMapper m = mapper();
        GcodeStreamValidator.ZWindow w = GcodeStreamValidator.zWindow(c, m);
        assertNotNull(w);
        for (boolean keepalive : new boolean[] {false, true}) {
            GcodeProgram p = board(c, m, toyPlan(c), false, keepalive, 99L);
            assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(p, c, "B", w));
        }
    }
}
