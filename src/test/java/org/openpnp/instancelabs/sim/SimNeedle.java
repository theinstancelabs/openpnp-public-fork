package org.openpnp.instancelabs.sim;

import java.util.List;
import java.util.Locale;

/**
 * Drives a {@link SimPaste} with the lines GcodeStreamBuilder sends for a dot (G0 over the spot, Z
 * down, the forward turn + suck-back under the screw acceleration, the dwell, the suck-back, the
 * lift), with the head position after each line as RecordingGcodeServer gives it. Positions are
 * the needle tip's (machine mm) and the needle's Z; the raw head X/Y is the tip minus the scene's
 * N2 offset.
 */
public final class SimNeedle {
    /** The bench recipe: 180 rpm, 10° suck-back (44 steps), 250 ms dwell, gap 0.10, hop 1.0 mm. */
    public long suckSteps = 44;
    public int dwellMs = 250;
    public double gapMm = 0.10, hopMm = 1.0, travelZ = 31.5;
    final SimPaste paste;
    final double[] n2;
    double x, y, z = 31.5;
    long restSteps;

    public SimNeedle(SimPaste paste, double[] n2OffsetMm) {
        this.paste = paste;
        this.n2 = n2OffsetMm.clone();
    }

    public String send(String line) {
        return paste.onLine(line, x, y, z);
    }

    /** G92 B0 (Marlin keeps the count), pump ON, valve ON with the builder's settle dwells. */
    public void pressurize() {
        send("G92 B0");
        restSteps = 0;
        send("M106 P2 S255");
        send("G4 P1000");
        send("M106 P3 S255");
        send("G4 P3000");
    }

    /** Valve and pump OFF, B zeroed, the lift to travel Z. */
    public void vent() {
        send("M400");
        send("M107 P3");
        send("M107 P2");
        send("G92 B0");
        restSteps = 0;
        z = travelZ;
        send(String.format(Locale.ROOT, "G1 Z%.4f F3000", z));
    }

    /** Lift to travel Z and move over a tip position (no thread: the needle is high). */
    public void travel(double tipX, double tipY) {
        z = travelZ;
        send(String.format(Locale.ROOT, "G1 Z%.4f F3000", z));
        x = tipX - n2[0];
        y = tipY - n2[1];
        send(String.format(Locale.ROOT, "G0 X%.4f Y%.4f F12000", x, y));
    }

    /** One dot of {@code net} steps at a tip position on a surface whose needle-touch Z is touchZ. */
    public void dot(double tipX, double tipY, double touchZ, long net) {
        dotAt(tipX, tipY, touchZ + gapMm, touchZ + gapMm + hopMm, net);
    }

    /** As {@link #dot} with the needle's dispense and hop Z given. */
    public void dotAt(double tipX, double tipY, double dispenseZ, double hopZ, long net) {
        x = tipX - n2[0];
        y = tipY - n2[1];
        send(String.format(Locale.ROOT, "G0 X%.4f Y%.4f F12000", x, y));
        z = dispenseZ;
        send(String.format(Locale.ROOT, "G1 Z%.4f F3000", z));
        send("M204 S20000");
        send(String.format(Locale.ROOT, "G1 B%.4f F64800", (restSteps + net + suckSteps) / SimPaste.STEPS_PER_DEG));
        if (dwellMs > 0) {
            send("G4 P" + dwellMs);
        }
        if (suckSteps > 0) {
            send(String.format(Locale.ROOT, "G1 B%.4f F64800", (restSteps + net) / SimPaste.STEPS_PER_DEG));
        }
        restSteps += net;
        send("M204 S2000");
        z = hopZ;
        send(String.format(Locale.ROOT, "G1 Z%.4f F3000", z));
    }

    /** A row of n dots from (x0, y0) along +X at the pitch. */
    public void row(double x0, double y0, double pitch, int n, double touchZ, long net) {
        for (int i = 0; i < n; i++) {
            dot(x0 + i * pitch, y0, touchZ, net);
        }
    }

    /** The round dots (not threads) of a region or place, in order. */
    public static List<SimPaste.Deposit> dots(SimPaste p, String where) {
        List<SimPaste.Deposit> out = p.deposits(where);
        out.removeIf((d) -> d.thread);
        return out;
    }
}
