package org.openpnp.instancelabs.dispense;

import java.util.List;

/**
 * BR-98: the check (collision-model-design.md §3). Proves a path, a list of straight raw legs (head X/Y, raw Z), against
 * the model. Per leg and tool layer: the area the layer sweeps (its outline at A and at B and between) against every
 * obstacle whose outline, grown by its margin and the start's X/Y error, it overlaps; the layer's lowest bottom on the
 * leg is at one of its ends (the see-saw maps are monotonic in raw Z, the plane is linear). An overlapped obstacle needs
 * top + zMarginMm + its top's error + the tool's height error at or below that bottom; one not measured blocks at every
 * height. Pure: no machine.
 *
 * <p>The sweep is done in the head's configuration space: the layer at head h overlaps the obstacle grown by g exactly
 * when h is within g of the obstacle grown by the layer turned round, so a leg is one segment against one convex
 * polygon per pair (made once per model, {@link BedModel.Pair}).</p>
 *
 * <p>Exemptions cover a tip layer only, on a measured touchable top whose outline holds the layer at both ends (the
 * start's X/Y error included): raw G-code (the passthrough, which has no semantics) may go {@code contactDepthMm} into
 * it; a jog may take the jogged tool's tip down to the jog floor the route already holds it to.</p>
 */
public final class CollisionCheck {
    private static final double EPS = 1e-9;

    private CollisionCheck() {
    }

    /** One straight raw move: head X/Y (machine mm) and raw Z at each end. */
    public static final class Leg {
        public final String label;
        public final double ax, ay, az, bx, by, bz;

        public Leg(String label, double ax, double ay, double az, double bx, double by, double bz) {
            this.label = label;
            this.ax = ax;
            this.ay = ay;
            this.az = az;
            this.bx = bx;
            this.by = by;
            this.bz = bz;
        }
    }

    /** How a path is checked. */
    public static final class Options {
        /** The start's X/Y error: it grows every outline. */
        public double xyErrMm;
        /** The home the raw Z is in (null: unknown, so every raw value of the model counts as another home's). */
        public Long homeAtMs;
        /** Raw G-code: a tip layer may go {@code contactDepthMm} into a measured touchable top that holds it. */
        public boolean contact;
        /** A jog: this tool's tip layer may go down to {@link #jogFloorRaw} over a measured touchable top holding it. */
        public String jogTool;
        public double jogFloorRaw = Double.NaN;

        public static Options strict(Long homeAtMs) {
            Options o = new Options();
            o.homeAtMs = homeAtMs;
            return o;
        }

        public static Options contact(Long homeAtMs) {
            Options o = strict(homeAtMs);
            o.contact = true;
            return o;
        }
    }

    /** A tool layer and an obstacle on a leg. */
    public static final class Hit {
        public final String leg;
        public final int legIndex;
        public final BedModel.Obj object;
        public final BedModel.Tool tool;
        public final BedModel.Layer layer;
        /** Blocked: the shortfall (null: not measured, it blocks at every height). Clear: the clearance. */
        public final Double mm;
        /** Null where unknown (the needle without K) or not a height (an unmeasured top). */
        public final Double toolLowestZ, blockerTopZ;
        /** Over the obstacle (vertical), or beside it (no layer was over anything on the whole path). */
        public final boolean vertical;
        /** The tool's tip (the head, for a tool without one) where it begins. */
        public final double atX, atY;

        Hit(String leg, int legIndex, BedModel.Obj object, BedModel.Tool tool, BedModel.Layer layer, Double mm,
                Double toolLowestZ, Double blockerTopZ, boolean vertical, double atX, double atY) {
            this.leg = leg;
            this.legIndex = legIndex;
            this.object = object;
            this.tool = tool;
            this.layer = layer;
            this.mm = mm;
            this.toolLowestZ = toolLowestZ;
            this.blockerTopZ = blockerTopZ;
            this.vertical = vertical;
            this.atX = atX;
            this.atY = atY;
        }

        @Override
        public String toString() {
            return (tool == null ? "?" : tool.id + "/" + layer.name) + " vs " + (object == null ? "?" : object.id)
                    + " on '" + leg + "': " + mm + " mm" + (vertical ? "" : " to the side") + " (lowest " + toolLowestZ
                    + ", top " + blockerTopZ + ")";
        }
    }

    public static final class Result {
        public final boolean clear;
        /** Blocked: the first leg's worst pair. Clear: the closest pair, or null when nothing is near. */
        public final Hit hit;

        Result(boolean clear, Hit hit) {
            this.clear = clear;
            this.hit = hit;
        }
    }

    /** The pair found so far, its {@link Hit} made at the end. */
    private static final class Pick {
        int leg = -1;
        BedModel.Tool tool;
        BedModel.Layer layer;
        int obstacle;
        double slack = Double.POSITIVE_INFINITY, bottom;
    }

    public static Result check(BedModel m, List<Leg> legs, Options o) {
        List<BedModel.Obj> obs = m.obstacles();
        Pick closest = new Pick();
        for (int li = 0; li < legs.size(); li++) {
            Leg g = legs.get(li);
            double minX = Math.min(g.ax, g.bx), maxX = Math.max(g.ax, g.bx);
            double minY = Math.min(g.ay, g.by), maxY = Math.max(g.ay, g.by);
            Pick worst = null;
            for (BedModel.Tool t : m.tools()) {
                double tipLow = Math.min(m.tipZ(t, g.az, g.ax, g.ay), m.tipZ(t, g.bz, g.bx, g.by));
                double tipErr = m.tipErrMm(t, o.homeAtMs);
                boolean jog = t.id.equals(o.jogTool) && !Double.isNaN(o.jogFloorRaw);
                double floor = jog ? Math.max(m.tipZ(t, o.jogFloorRaw, g.ax, g.ay), m.tipZ(t, o.jogFloorRaw, g.bx, g.by))
                        : Double.NaN;
                for (BedModel.Layer l : t.layers) {
                    double bottom = l.bottomZ != null ? l.bottomZ : tipLow + l.aboveTipMm;
                    for (int oi = 0; oi < obs.size(); oi++) {
                        BedModel.Obj ob = obs.get(oi);
                        BedModel.Pair p = l.pairs[oi];
                        double grow = ob.marginMm + o.xyErrMm;
                        BedModel.Poly c = p.cspace;
                        if (minX > c.maxX + grow || maxX < c.minX - grow || minY > c.maxY + grow || maxY < c.minY - grow
                                || c.distance(g.ax, g.ay, g.bx, g.by) > grow) {
                            continue;
                        }
                        double slack;
                        if (ob.blocksEveryHeight()) {
                            slack = Double.NEGATIVE_INFINITY;
                        }
                        else {
                            double required = ob.topZ + m.zMarginMm + ob.topErrMm + tipErr;
                            if (l.tip && ob.touchable && p.holds(g.ax, g.ay, o.xyErrMm) && p.holds(g.bx, g.by,
                                    o.xyErrMm)) {
                                if (o.contact) {
                                    required = Math.min(required, ob.topZ - m.contactDepthMm);
                                }
                                if (jog) {
                                    required = Math.min(required, floor + l.aboveTipMm);
                                }
                            }
                            slack = bottom - required;
                        }
                        if (Double.isNaN(slack) || slack < -EPS) {
                            if (worst == null || slack < worst.slack) {
                                worst = pick(li, t, l, oi, Double.isNaN(slack) ? Double.NEGATIVE_INFINITY : slack,
                                        bottom);
                            }
                        }
                        else if (slack < closest.slack) {
                            closest = pick(li, t, l, oi, slack, bottom);
                        }
                    }
                }
            }
            if (worst != null) {
                return new Result(false, hit(m, legs, worst, o, true, true));
            }
        }
        if (closest.leg >= 0) {
            return new Result(true, hit(m, legs, closest, o, true, false));
        }
        return new Result(true, beside(m, legs, o));
    }

    private static Pick pick(int leg, BedModel.Tool t, BedModel.Layer l, int obstacle, double slack, double bottom) {
        Pick p = new Pick();
        p.leg = leg;
        p.tool = t;
        p.layer = l;
        p.obstacle = obstacle;
        p.slack = slack;
        p.bottom = bottom;
        return p;
    }

    /** No layer over anything on the whole path: the smallest gap to the side, or null when there is no obstacle. */
    private static Hit beside(BedModel m, List<Leg> legs, Options o) {
        Pick best = null;
        for (int li = 0; li < legs.size(); li++) {
            Leg g = legs.get(li);
            for (BedModel.Tool t : m.tools()) {
                for (BedModel.Layer l : t.layers) {
                    for (int oi = 0; oi < l.pairs.length; oi++) {
                        double gap = l.pairs[oi].cspace.distance(g.ax, g.ay, g.bx, g.by) - m.obstacles().get(oi).marginMm
                                - o.xyErrMm;
                        if (best == null || gap < best.slack) {
                            best = pick(li, t, l, oi, gap, Double.NaN);
                        }
                    }
                }
            }
        }
        return best == null ? null : hit(m, legs, best, o, false, false);
    }

    private static Hit hit(BedModel m, List<Leg> legs, Pick p, Options o, boolean vertical, boolean blocked) {
        Leg g = legs.get(p.leg);
        BedModel.Obj ob = m.obstacles().get(p.obstacle);
        double grow = ob.marginMm + o.xyErrMm;
        double t = where(p.layer.pairs[p.obstacle].cspace, g, vertical ? grow : Double.NEGATIVE_INFINITY);
        double hx = g.ax + t * (g.bx - g.ax), hy = g.ay + t * (g.by - g.ay);
        boolean tipped = !Double.isNaN(p.tool.tipDx);
        Double mm = Double.isInfinite(p.slack) ? null : blocked ? -p.slack : Math.max(0, p.slack);
        return new Hit(g.label, p.leg, ob, p.tool, p.layer, mm, finite(p.bottom), ob.blocksEveryHeight() ? null
                : ob.topZ, vertical, tipped ? hx + p.tool.tipDx : hx, tipped ? hy + p.tool.tipDy : hy);
    }

    private static Double finite(double v) {
        return Double.isFinite(v) ? v : null;
    }

    /**
     * Where on the leg (0..1) the head first comes within {@code grow} of the polygon, or (grow minus infinity) where
     * it comes closest. The distance along a line to a convex polygon is convex: a ternary search finds its least, a
     * bisection before it the first point within reach.
     */
    private static double where(BedModel.Poly c, Leg g, double grow) {
        if (c.distance(g.ax, g.ay) <= grow) {
            return 0;
        }
        double lo = 0, hi = 1;
        for (int i = 0; i < 100; i++) {
            double m1 = lo + (hi - lo) / 3, m2 = hi - (hi - lo) / 3;
            if (at(c, g, m1) <= at(c, g, m2)) {
                hi = m2;
            }
            else {
                lo = m1;
            }
        }
        double best = (lo + hi) / 2;
        if (at(c, g, best) > grow) {
            return best;
        }
        lo = 0;
        hi = best;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (at(c, g, mid) <= grow) {
                hi = mid;
            }
            else {
                lo = mid;
            }
        }
        return hi;
    }

    private static double at(BedModel.Poly c, Leg g, double t) {
        return c.distance(g.ax + t * (g.bx - g.ax), g.ay + t * (g.by - g.ay));
    }
}
