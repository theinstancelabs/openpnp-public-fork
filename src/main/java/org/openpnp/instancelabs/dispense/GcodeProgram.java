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
        /** On DOT_BEGIN: the net screw steps the dot was built to turn; 0 elsewhere. */
        public final int netSteps;
        /** On DOT_BEGIN of a plan's dot: the index of its pad in the plan; -1 elsewhere. */
        public final int padIndex;

        Line(String text, Marker marker, int dotIndex, String padId, double rotationDeg, List<Marker> extra) {
            this(text, marker, dotIndex, padId, rotationDeg, extra, 0, -1);
        }

        Line(String text, Marker marker, int dotIndex, String padId, double rotationDeg, List<Marker> extra,
                int netSteps, int padIndex) {
            this.text = text;
            this.marker = marker;
            this.dotIndex = dotIndex;
            this.padId = padId;
            this.rotationDeg = rotationDeg;
            this.extra = extra == null ? Collections.emptyList() : extra;
            this.netSteps = netSteps;
            this.padIndex = padIndex;
        }

        public boolean isComment() {
            return text.startsWith(";");
        }

        public boolean has(Marker m) {
            return marker == m || extra.contains(m);
        }
    }

    /**
     * One dose block of a board or calibration program (§6.2): dots of one net turn, laid together
     * after the initial purge (block 0) or after a switch on the purge row.
     */
    public static final class BlockInfo {
        public int netSteps;
        /** Work-dot index of the block's first dot, and how many it has. */
        public int firstDot;
        public int dots;
        /** Marlin step count of B at rest before the block's first dot and its screw angle, or null. */
        public Long restCountAtStart;
        public Double startAngleDeg;
        /**
         * Estimated seconds the screw has to lay the block: from the end of the dot before its first dot
         * (the last purge or flush dot) to the end of its last dot; the cross to it included.
         */
        public double seconds;
        /**
         * Block 0: the cross from the purge to its first dot; later blocks: from the previous block's last
         * dot to this block's first (purge row, phase dot, flush dots, cross).
         */
        public double switchSeconds;
        /** Paste demand of the block (mg/s) at the model's mass per step (§1.2 R7). */
        public double demandMgS;
    }

    public final List<Line> lines = new ArrayList<>();
    /** Dose blocks (board and calibration-block programs), null for other programs. */
    public List<BlockInfo> blocks;
    /** Board and block programs, by work-dot index: Marlin count of B at rest before the dot; null if unknown. */
    public long[] dotStartCount;
    /**
     * B position at the program start in extrude-positive motor steps from B zero (0 when the
     * program zeroes B itself): with the B targets it gives every dot's net turn.
     */
    public long bStartSteps;
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
    /**
     * Board programs, by plan dot index: estimated seconds from the program start to the needle
     * arriving over the dot (after its XY move) and to the end of its lift. Same estimate as above.
     */
    public double[] dotArriveSeconds, dotEndSeconds;
    /** Board programs: estimated seconds from the cross after the purge to the last dot (bench DPH). */
    public double estimatedWorkSeconds;
    /** Estimated seconds from the pump ON line to the pump OFF line. */
    public double estimatedPressurisedSeconds;
    public int dotCount;
    /** Every purge-row dot: the initial purge and the phase and flush dots of the block switches. */
    public int purgeDotCount;
    /** Marlin step count of B at the start of the block (M114 Count), or null if unknown. */
    public Long bCountAtStart;
    /** Step count and screw angle where the first work dot starts (after the purge), or null. */
    public Long workStartCount;
    public Double workStartAngleDeg;
    /**
     * Frame reference of the surface the dots take their Z from when it is not the needle frame (board
     * programs: the plan's board top Z), else null; the needle floor follows the lower of the two
     * ({@link DispenseConfig#needleFloorZMm(Double)}).
     */
    public Double floorReferenceZMm;

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
