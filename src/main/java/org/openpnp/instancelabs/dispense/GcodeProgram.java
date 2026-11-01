package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A block of G-code streamed in one go through the GcodeDriver, with markers the executor uses for
 * timing, interlocks and keepalive, and the axis state the block leaves behind (for re-sync).
 */
public class GcodeProgram {
    public enum Marker {
        NONE,
        /** First line of a dot (prime, test and purge dots carry dotIndex < 0). */
        DOT_BEGIN,
        /** Last line of a dot. A line can be both begin and end only for 1-line dots (never here). */
        DOT_END,
        PUMP_ON,
        VALVE_ON,
        VALVE_OFF,
        /** Pump off = pressure session closed. */
        PUMP_OFF,
        KEEPALIVE_ARM,
        KEEPALIVE_DISARM,
        B_ZERO,
        /** M400: waits for the controller to finish all queued motion. */
        SYNC
    }

    public static final class Line {
        public final String text;
        public final Marker marker;
        /** Dot index for DOT_BEGIN/DOT_END; -1 prime, -2 test dot, -3 purge, else -9. */
        public final int dotIndex;
        public final String padId;
        public final double rotationDeg;
        /** Extra markers when a single line carries two roles (e.g. DOT_END on a lift line). */
        public final List<Marker> extra;

        Line(String text, Marker marker, int dotIndex, String padId, double rotationDeg, List<Marker> extra) {
            this.text = text;
            this.marker = marker;
            this.dotIndex = dotIndex;
            this.padId = padId;
            this.rotationDeg = rotationDeg;
            this.extra = extra == null ? Collections.emptyList() : extra;
        }

        public boolean isComment() {
            return text.startsWith(";");
        }

        public boolean has(Marker m) {
            return marker == m || extra.contains(m);
        }
    }

    public final List<Line> lines = new ArrayList<>();
    /** State after the block: planned raw X/Y/Z, driver raw X/Y/Z, raw B. */
    public double[] finalPlanned;
    public double[] finalDriver;
    public double finalB;
    /** N2 machine location after the block. */
    public double[] finalNozzle;
    public boolean bZeroedAtStart;
    public boolean keepalive;
    /**
     * Duration estimate, seconds: every move from rest to rest with its acceleration, dwells and
     * settles (no host latency, no look-ahead between moves).
     */
    public double estimatedSeconds;
    /** Board programs: estimated seconds from the cross after the purge to the last dot (bench DPH). */
    public double estimatedWorkSeconds;
    /** Estimated seconds from the pump ON line to the pump OFF line. */
    public double estimatedPressurisedSeconds;
    public int dotCount;
    public int purgeDotCount;
    /** Marlin step count of B at the start of the block (M114 Count), or null if unknown. */
    public Long bCountAtStart;
    /** Step count and screw angle where the first work dot starts (after the purge), or null. */
    public Long workStartCount;
    public Double workStartAngleDeg;

    /** All lines (including comments), one per entry. */
    public List<String> text() {
        List<String> l = new ArrayList<>();
        for (Line line : lines) {
            l.add(line.text);
        }
        return l;
    }

    public String asString() {
        return String.join("\n", text()) + "\n";
    }
}
