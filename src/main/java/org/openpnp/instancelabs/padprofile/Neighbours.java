package org.openpnp.instancelabs.padprofile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The board's openings on a 3 mm spatial grid (§3.2) with every opening's neighbours, and the dots
 * each opening carries for its neighbours' checks (§3.1: planned dots, the type's plan, the group's
 * suggestion, else a centre dot of the tip's smallest dose). Pads of the same part whose rectangles
 * touch or overlap are one deposit, not neighbours (§1.6: AVX-9155 stacks a paste window on its
 * pad). Openings farther than both half diagonals + 3 mm cannot meet any limit of §1.6 (dots stay
 * within about half a dot of their pad), so only those within are kept; an opening with none keeps
 * its nearest one, so pitches and gaps are always reported.
 */
final class Neighbours {
    /** Beyond both half diagonals: room for dot overhang and the limits, with margin. */
    private static final double REACH_MM = 3.0;

    final List<PadInstance> pads;
    private final List<int[]> near = new ArrayList<>();
    private final double[] pitch;
    private final List<List<double[]>> dots = new ArrayList<>();
    private final double[] dotR;

    Neighbours(List<PadInstance> pads) {
        this.pads = pads;
        int n = pads.size();
        pitch = new double[n];
        dotR = new double[n];
        double cell = Limits.NEIGHBOUR_CELL_MM;
        Map<Long, List<Integer>> grid = new HashMap<>();
        double maxHalf = 0;
        long minCx = Long.MAX_VALUE;
        long maxCx = Long.MIN_VALUE;
        long minCy = Long.MAX_VALUE;
        long maxCy = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            PadInstance p = pads.get(i);
            maxHalf = Math.max(maxHalf, DotGeometry.halfDiagonal(p));
            long gx = (long) Math.floor(p.x / cell);
            long gy = (long) Math.floor(p.y / cell);
            minCx = Math.min(minCx, gx);
            maxCx = Math.max(maxCx, gx);
            minCy = Math.min(minCy, gy);
            maxCy = Math.max(maxCy, gy);
            grid.computeIfAbsent(cellKey(gx, gy), k -> new ArrayList<>()).add(i);
            dots.add(Collections.emptyList());
        }
        for (int i = 0; i < n; i++) {
            PadInstance p = pads.get(i);
            double radius = DotGeometry.halfDiagonal(p) + maxHalf + REACH_MM;
            long cx = (long) Math.floor(p.x / cell);
            long cy = (long) Math.floor(p.y / cell);
            long rings = (long) Math.ceil(radius / cell);
            long lastRing = Math.max(rings, Math.max(Math.max(cx - minCx, maxCx - cx), Math.max(cy - minCy, maxCy - cy)));
            List<Integer> found = new ArrayList<>();
            double best = Double.POSITIVE_INFINITY;
            int nearest = -1;
            for (long ring = 0; ring <= lastRing; ring++) {
                for (long gx = cx - ring; gx <= cx + ring; gx++) {
                    for (long gy = cy - ring; gy <= cy + ring; gy++) {
                        List<Integer> l = Math.max(Math.abs(gx - cx), Math.abs(gy - cy)) == ring
                                ? grid.get(cellKey(gx, gy)) : null;
                        for (int j : l == null ? Collections.<Integer>emptyList() : l) {
                            if (j == i || overlapping(p, pads.get(j))) {
                                continue;
                            }
                            double d = Math.hypot(pads.get(j).x - p.x, pads.get(j).y - p.y);
                            if (d < best) {
                                best = d;
                                nearest = j;
                            }
                            if (d <= radius) {
                                found.add(j);
                            }
                        }
                    }
                }
                // every opening not seen yet is at least ring x cell away
                if (ring >= rings && best <= ring * cell) {
                    break;
                }
            }
            if (nearest >= 0 && !found.contains(nearest)) {
                found.add(nearest);
            }
            Collections.sort(found);
            int[] a = new int[found.size()];
            for (int k = 0; k < a.length; k++) {
                a[k] = found.get(k);
            }
            near.add(a);
            pitch[i] = nearest >= 0 ? best : Double.NaN;
        }
    }

    private static long cellKey(long gx, long gy) {
        return (gx << 32) ^ (gy & 0xffffffffL);
    }

    /** Same part and the rectangles touch or overlap. */
    static boolean overlapping(PadInstance a, PadInstance b) {
        return a.ref != null && a.ref.equals(b.ref) && DotGeometry.padGapMm(a, b) <= 1e-9;
    }

    int size() {
        return pads.size();
    }

    int[] near(int i) {
        return near.get(i);
    }

    /** Nearest centre of any other opening (NaN if alone). */
    double pitch(int i) {
        return pitch[i];
    }

    /** Nearest copper of any other opening (edge to edge). */
    double edgeGap(int i) {
        double best = Double.NaN;
        for (int j : near.get(i)) {
            double g = DotGeometry.padGapMm(pads.get(i), pads.get(j));
            if (Double.isNaN(best) || g < best) {
                best = g;
            }
        }
        return best;
    }

    List<double[]> dots(int i) {
        return dots.get(i);
    }

    double dotRadius(int i) {
        return dotR[i];
    }

    /** The dots opening i carries for its neighbours (board frame) and their radius. */
    void setDots(int i, List<double[]> boardXy, double r) {
        dots.set(i, boardXy);
        dotR[i] = r;
    }

    void setCentreDot(int i, double r) {
        List<double[]> l = new ArrayList<>();
        l.add(new double[] {pads.get(i).x, pads.get(i).y});
        setDots(i, l, r);
    }

    /** Pad-frame positions of opening i in the board frame. */
    List<double[]> toBoard(int i, List<double[]> local) {
        List<double[]> l = new ArrayList<>();
        for (double[] o : local) {
            l.add(DotGeometry.toBoard(pads.get(i), o[0], o[1]));
        }
        return l;
    }

    /** The geometry of a plan on a group's openings against the neighbours' own dots (§4.3 step 4). */
    static final class Eval {
        /** Largest overhang past the own pad edge, and the pad side it is measured across. */
        double overhang = Double.NEGATIVE_INFINITY;
        double overhangAcrossMm = Double.NaN;
        /** Smallest gap between dots of different pads, and the pad at that gap (-1 none). */
        double dotGap = Double.POSITIVE_INFINITY;
        int dotGapPad = -1;
        int dotGapFrom = -1;
        /** Smallest gap from a dot to another pad's copper. */
        double copperGap = Double.POSITIVE_INFINITY;
        int copperPad = -1;

        boolean passes() {
            return overhang <= Limits.OVERHANG_MM + Limits.EPS && dotGap >= Limits.DOT_GAP_MM - Limits.EPS
                    && copperGap >= Limits.COPPER_GAP_MM - Limits.EPS;
        }

        boolean marginal() {
            return overhang <= Limits.OVERHANG_MARGINAL_MM + Limits.EPS
                    && dotGap >= Limits.DOT_GAP_MARGINAL_MM - Limits.EPS
                    && copperGap >= Limits.COPPER_GAP_MARGINAL_MM - Limits.EPS;
        }
    }

    /**
     * Evaluates dots on a group's openings: {@code local.get(i)} in the pad frame of opening i (for
     * every i of the group), radius {@code rGap} for gaps and {@code rOverhang} for the overhang.
     * The group's own other openings carry the same plan; every other opening its current dots.
     */
    Eval evaluate(int[] group, Map<Integer, List<double[]>> local, double rGap, double rOverhang) {
        Map<Integer, List<double[]>> board = new HashMap<>();
        for (int i : group) {
            board.put(i, toBoard(i, local.get(i)));
        }
        Eval e = new Eval();
        for (int i : group) {
            PadInstance p = pads.get(i);
            List<double[]> mine = board.get(i);
            List<double[]> loc = local.get(i);
            for (int k = 0; k < mine.size(); k++) {
                double[] o = loc.get(k);
                double ox = Math.abs(o[0]) + rOverhang - p.wMm / 2;
                double oy = Math.abs(o[1]) + rOverhang - p.hMm / 2;
                if (Math.max(ox, oy) > e.overhang) {
                    e.overhang = Math.max(ox, oy);
                    e.overhangAcrossMm = ox >= oy ? p.wMm : p.hMm;
                }
                double[] pt = mine.get(k);
                for (int j : near.get(i)) {
                    PadInstance q = pads.get(j);
                    double cg = DotGeometry.pointToPadMm(pt[0], pt[1], q) - rGap;
                    if (cg < e.copperGap) {
                        e.copperGap = cg;
                        e.copperPad = j;
                    }
                    List<double[]> theirs = board.containsKey(j) ? board.get(j) : dots.get(j);
                    double rj = board.containsKey(j) ? rGap : dotR[j];
                    for (double[] t : theirs) {
                        double g = Math.hypot(pt[0] - t[0], pt[1] - t[1]) - rGap - rj;
                        if (g < e.dotGap) {
                            e.dotGap = g;
                            e.dotGapPad = j;
                            e.dotGapFrom = i;
                        }
                    }
                }
            }
        }
        return e;
    }
}
