package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Result of the clean-pad guard (I10): the bright fraction at every work dot of a plan, taken with
 * the top camera before the pressure goes on. A pad that still carries paste reads dark blue; a
 * camera that lands on solder mask (board mis-registered) reads dark too.
 */
public class PadCleanReport {
    public static class Pad {
        public String padId;
        public int dotIndex;
        public double xMm, yMm;
        /** Share of pixels brighter than the gray level in the box at the target, or null on error. */
        public Double brightFraction;
        public boolean clean;
        public String error;
        /** The box used at this dot (mm): the configured one, or smaller for a small pad. */
        public double boxWMm, boxHMm;
    }

    /** True once every dot was looked at (an aborted or failed check stays incomplete). */
    public boolean isComplete() {
        return checkedAtMs > 0;
    }

    public String planId;
    public boolean ok;
    public int grayLevel;
    public double minBrightFraction, boxWMm, boxHMm;
    public long checkedAtMs;
    public final List<Pad> pads = new ArrayList<>();

    public static PadCleanReport of(DispenseConfig.CleanPadCheck s) {
        PadCleanReport r = new PadCleanReport();
        r.grayLevel = s.grayLevel;
        r.minBrightFraction = s.minBrightFraction;
        r.boxWMm = s.boxWMm;
        r.boxHMm = s.boxHMm;
        return r;
    }

    public List<String> dirtyPadIds() {
        List<String> l = new ArrayList<>();
        for (Pad p : pads) {
            if (!p.clean && !l.contains(p.padId)) {
                l.add(p.padId);
            }
        }
        return l;
    }

    /** "I10: n pad(s) are not clean ...": the refusal text (lists up to 12 pads). */
    public String refusal() {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Pad p : pads) {
            if (!p.clean && shown++ < 12) {
                sb.append(p.padId).append(p.brightFraction == null ? " (" + p.error + ")"
                        : String.format(Locale.ROOT, " (%.2f)", p.brightFraction)).append(", ");
            }
        }
        List<String> dirty = dirtyPadIds();
        return String.format(Locale.ROOT, "I10: %d pad(s) are not clean (bright share < %.2f) or not under the "
                + "camera: %s%s. Wipe them (or re-register the board) and check again", dirty.size(),
                minBrightFraction, sb.length() > 2 ? sb.substring(0, sb.length() - 2) : "",
                shown > 12 ? ", ..." : "");
    }
}
