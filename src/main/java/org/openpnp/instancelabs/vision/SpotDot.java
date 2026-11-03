package org.openpnp.instancelabs.vision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What {@link DotInspector#inspectSpots} saw at one spot where a dot was laid: a dot, no paste, or paste that is not
 * one measurable dot (joined to the paste of another spot, or strings and smears only). Real touch-off of 2026-10-29
 * (F9): every dot had formed, but thin paste strings joined them into one blob, which the size and circularity
 * filters dropped, so each spot read "no dot".
 */
public final class SpotDot {
    public enum State {
        /** A dot was measured ({@link #dot}). */
        DOT,
        /** No new paste at the spot. */
        NONE,
        /** New paste at the spot that is not one measurable dot ({@link #note}, {@link #joinedTo}). */
        UNMEASURED
    }

    public final State state;
    /** The dot (centroid offset from the image centre), for {@link State#DOT}; else null. */
    public final Dot dot;
    /** Why the paste at the spot could not be measured, for {@link State#UNMEASURED}; else null. */
    public final String note;
    /** The other spots (indices of the spot list) whose paste is joined to this spot's; empty if none. */
    public final List<Integer> joinedTo;

    private SpotDot(State state, Dot dot, String note, List<Integer> joinedTo) {
        this.state = state;
        this.dot = dot;
        this.note = note;
        this.joinedTo = Collections.unmodifiableList(new ArrayList<>(joinedTo));
    }

    public static SpotDot dot(Dot d) {
        return new SpotDot(State.DOT, d, null, Collections.emptyList());
    }

    public static SpotDot none() {
        return new SpotDot(State.NONE, null, null, Collections.emptyList());
    }

    public static SpotDot unmeasured(String note, List<Integer> joinedTo) {
        return new SpotDot(State.UNMEASURED, null, note, joinedTo == null ? Collections.emptyList() : joinedTo);
    }

    /**
     * The nearest of {@code dots} within {@code radiusMm} of each spot, or none (imagers that only list dots, such as
     * the tests' fakes: they cannot tell strung paste from no paste).
     */
    public static List<SpotDot> nearest(List<Dot> dots, List<double[]> spotsMm, double radiusMm) {
        List<SpotDot> out = new ArrayList<>();
        for (double[] s : spotsMm) {
            Dot best = null;
            double bestD = Double.MAX_VALUE;
            if (dots != null) {
                for (Dot d : dots) {
                    double r = Math.hypot(d.xMm - s[0], d.yMm - s[1]);
                    if (r <= radiusMm && r < bestD) {
                        best = d;
                        bestD = r;
                    }
                }
            }
            out.add(best == null ? none() : dot(best));
        }
        return out;
    }

    @Override
    public String toString() {
        switch (state) {
            case DOT:
                return "SpotDot[" + dot + "]";
            case NONE:
                return "SpotDot[none]";
            default:
                return String.format(Locale.ROOT, "SpotDot[unmeasured: %s%s]", note,
                        joinedTo.isEmpty() ? "" : " " + joinedTo);
        }
    }
}
