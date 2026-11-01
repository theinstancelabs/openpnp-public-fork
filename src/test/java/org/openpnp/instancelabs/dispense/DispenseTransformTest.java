package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis.BacklashCompensationMethod;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.MotionPlanner.CompletionType;

/**
 * Item 4: in a simulated machine (live LumenPnP axes: N2 on the z2 = 63 - z1 see-saw, head offsets,
 * one-sided XY backlash), the raw X/Y/Z the dispense engine computes for N2 must equal what
 * OpenPnP's own N2.moveTo() sends to the controller, including the backlash approach moves.
 */
public class DispenseTransformTest {
    static final double[][] LOCATIONS = {
        {100.0, 80.0, 20.0},
        {150.5, 120.25, 28.7},
        {60.0, 200.0, 10.0},
        {300.0, 40.0, 31.5},
        {120.0, 90.0, 5.2},
        {119.0, 91.0, 33.0},
        {119.0, 91.0, 12.345},
        {250.125, 300.875, 30.0},
    };

    @Test
    public void liveAxisSettings() throws Exception {
        try (SimMachine sim = new SimMachine()) {
            int[] backlashMoves = compareAll(sim);
            // the live one-sided XY compensation must actually have produced approach moves
            assertTrue(backlashMoves[0] >= LOCATIONS.length - 1, "backlash approach moves seen: " + backlashMoves[0]);
        }
    }

    @Test
    public void otherBacklashMethods() throws Exception {
        try (SimMachine sim = new SimMachine()) {
            ReferenceControllerAxis x = (ReferenceControllerAxis) axis(sim, "x");
            ReferenceControllerAxis y = (ReferenceControllerAxis) axis(sim, "y");
            ReferenceControllerAxis z = (ReferenceControllerAxis) axis(sim, "z1");
            x.setBacklashCompensationMethod(BacklashCompensationMethod.OneSidedOptimizedPositioning);
            y.setBacklashCompensationMethod(BacklashCompensationMethod.DirectionalSneakUp);
            y.setBacklashOffset(new Length(0.08, LengthUnit.Millimeters));
            y.setSneakUpOffset(new Length(0.2, LengthUnit.Millimeters));
            y.setBacklashSpeedFactor(0.3);
            z.setBacklashOffset(new Length(0.05, LengthUnit.Millimeters));
            int[] n = compareAll(sim);
            assertTrue(n[0] > 0 && n[1] > 0, "two-move and one-move cases both exercised");
        }
    }

    @Test
    public void seeSawAndHeadOffsetsAreOpenPnps() throws Exception {
        try (SimMachine sim = new SimMachine()) {
            sim.machine.execute(() -> {
                OpenPnpRawMapper m = new OpenPnpRawMapper(sim.machine, sim.n2, "b");
                RawPoint p = m.toRaw(100, 80, 20);
                // N2 head offsets (25.291, -75.926: the bench needle offset) and z1 = 63 - z2
                assertEquals(100 - 25.291, p.x, 1e-9);
                assertEquals(80 + 75.926, p.y, 1e-9);
                assertEquals(63 - 20, p.z, 1e-9);
                assertEquals("X", m.letterX());
                assertEquals("Z", m.letterZ());
                assertEquals("B", m.letterB());
                // soft limit (raw X < 0) is refused through the planner's own isValidLocation()
                assertThrows(Exception.class, () -> m.toRaw(10, 80, 20));
                return null;
            });
        }
    }

    static org.openpnp.spi.Axis axis(SimMachine sim, String name) {
        for (org.openpnp.spi.Axis a : sim.machine.getAxes()) {
            if (a.getName().equals(name)) {
                return a;
            }
        }
        throw new AssertionError(name);
    }

    /** @return {number of moves with a backlash approach, number of single moves} */
    private int[] compareAll(SimMachine sim) throws Exception {
        int[] counts = new int[2];
        for (double[] l : LOCATIONS) {
            sim.machine.execute(() -> {
                OpenPnpRawMapper mapper = new OpenPnpRawMapper(sim.machine, sim.n2, "b");
                double[] planned = mapper.currentPlanned();
                double[] driver = mapper.currentDriver();
                double[] off = {driver[0] - planned[0], driver[1] - planned[1], driver[2] - planned[2]};
                BacklashEmulator emu = new BacklashEmulator(mapper.backlash(), planned, off, true);
                RawPoint rp = mapper.toRaw(l[0], l[1], l[2]);
                List<BacklashEmulator.Move> predicted = emu.moveTo(new double[] {rp.x, rp.y, rp.z});

                double[] start = {sim.server.position('X'), sim.server.position('Y'), sim.server.position('Z')};
                sim.server.mark();
                sim.n2.moveTo(new Location(LengthUnit.Millimeters, l[0], l[1], l[2], Double.NaN));
                sim.n2.waitForCompletion(CompletionType.WaitForStillstand);
                List<double[]> sent = moves(sim.server.sinceMark(), start);

                String ctx = String.format(Locale.ROOT, "N2 -> (%.3f, %.3f, %.3f): OpenPnP sent %s, predicted %s", l[0],
                        l[1], l[2], fmt(sent), fmtMoves(predicted));
                assertEquals(predicted.size(), sent.size(), ctx);
                counts[sent.size() > 1 ? 0 : 1]++;
                for (int i = 0; i < sent.size(); i++) {
                    for (int a = 0; a < 3; a++) {
                        assertEquals(predicted.get(i).driver[a], sent.get(i)[a], 1.5e-4, ctx);
                    }
                }
                // OpenPnP's own idea of the axes after the move agrees with the emulator state
                double[] p2 = mapper.currentPlanned();
                double[] d2 = mapper.currentDriver();
                for (int a = 0; a < 3; a++) {
                    assertEquals(emu.planned()[a], p2[a], 1e-6, ctx);
                    assertEquals(emu.driver()[a], d2[a], 1e-6, ctx);
                }
                return null;
            });
        }
        return counts;
    }

    static final Pattern WORD = Pattern.compile("([XYZ])(-?\\d+(?:\\.\\d+)?)");

    /** Cumulative X/Y/Z after every G0/G1 that moves X, Y or Z. */
    static List<double[]> moves(List<String> lines, double[] start) {
        List<double[]> out = new ArrayList<>();
        double[] cur = start.clone();
        for (String line : lines) {
            String body = line.contains(";") ? line.substring(0, line.indexOf(';')).trim() : line.trim();
            if (!(body.startsWith("G1 ") || body.startsWith("G0 "))) {
                continue;
            }
            Matcher m = WORD.matcher(body.substring(2));
            boolean any = false;
            while (m.find()) {
                cur["XYZ".indexOf(m.group(1))] = Double.parseDouble(m.group(2));
                any = true;
            }
            if (any) {
                out.add(cur.clone());
            }
        }
        return out;
    }

    static String fmt(List<double[]> l) {
        StringBuilder sb = new StringBuilder();
        for (double[] d : l) {
            sb.append(String.format(Locale.ROOT, "[%.4f %.4f %.4f]", d[0], d[1], d[2]));
        }
        return sb.toString();
    }

    static String fmtMoves(List<BacklashEmulator.Move> l) {
        List<double[]> d = new ArrayList<>();
        for (BacklashEmulator.Move m : l) {
            d.add(m.driver);
        }
        return fmt(d);
    }
}
