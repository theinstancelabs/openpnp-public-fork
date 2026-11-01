package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

/**
 * Needle touch-off by dot ladder at stepped Z gaps (plan §6.1, option 1): dispense equal dots at
 * commanded gaps g_0 > g_1 > ... (relative to the current needle Z offset) and measure their
 * diameters. Far from the surface the diameter is stable; at the gap where the tip reaches the
 * surface the dot squashes (diameter jumps) or stops forming. The contact gap is estimated as the
 * midpoint between the last normal gap and the first squashed/missing one; the new offset is
 * old offset + contact gap. Resolution is one step (default 0.025 mm = one Z microstep today).
 */
public final class NeedleTouchOff {
    private NeedleTouchOff() {
    }

    public static class Request {
        /** Largest commanded gap (mm, relative to the current offset). */
        public double startGapMm = 0.125;
        public double stepMm = 0.025;
        public int steps = 6;
        /**
         * Rotation per dot (deg); NaN = one dot of the dispense profile (partial turns that are not
         * phase-locked vary about +-50 % with the screw angle, which would hide the squash jump).
         */
        public double rotationDeg = Double.NaN;
        public double pitchMm = 1.5;
        public String locationName;
        public boolean primeAtWaste = true;
        /** Relative diameter increase that counts as squashing. */
        public double jumpRatio = 0.25;
    }

    public static class Analysis {
        public final double[] gapsMm;
        public final Double[] diametersMm;
        public Double contactGapMm;
        public Double bracketHighMm;
        public Double bracketLowMm;
        public Double referenceDiameterMm;
        public String reason;

        Analysis(double[] g, Double[] d) {
            gapsMm = g;
            diametersMm = d;
        }

        public boolean found() {
            return contactGapMm != null;
        }
    }

    public static double[] gaps(Request req) {
        double[] g = new double[req.steps];
        for (int i = 0; i < req.steps; i++) {
            g[i] = req.startGapMm - i * req.stepMm;
        }
        return g;
    }

    /**
     * Dot i at blank.x + i * pitch. Its Z is the probed blank plane at that dot plus the offset and
     * gap i (review finding: with one flat Z, a slope of the blank along the ladder was mixed into
     * the gap steps and biased the offset). Without a plane (null) the blank place's single Z is
     * used; runs refuse that (I4: no blank surface).
     */
    public static List<GcodeStreamBuilder.DotSpec> layout(Request req, DispenseConfig.NamedLocation blank,
            DispenseConfig cfg, SurfacePlane plane) {
        List<GcodeStreamBuilder.DotSpec> l = new ArrayList<>();
        double[] g = gaps(req);
        for (int i = 0; i < g.length; i++) {
            double x = blank.x + i * req.pitchMm, y = blank.y;
            double surface = plane == null ? blank.z : plane.z(x, y);
            double z = surface + cfg.needleZOffsetMm + g[i];
            double hop = surface + cfg.needleZOffsetMm + Math.max(g[0], cfg.gapMm()) + cfg.hopHeightMm;
            l.add(new GcodeStreamBuilder.DotSpec(x, y, z, hop, req.rotationDeg, i,
                    String.format(java.util.Locale.ROOT, "touch-off gap %.3f", g[i])));
        }
        return l;
    }

    /**
     * @param gapsMm commanded gaps in any order
     * @param diametersMm measured diameter per gap, null = no dot formed
     */
    public static Analysis analyze(double[] gapsMm, Double[] diametersMm, double jumpRatio) {
        Analysis a = new Analysis(gapsMm.clone(), diametersMm.clone());
        Integer[] idx = new Integer[gapsMm.length];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (p, q) -> Double.compare(gapsMm[q], gapsMm[p])); // far -> near
        List<Double> ref = new ArrayList<>();
        for (int k = 0; k < idx.length && ref.size() < 2; k++) {
            Double d = diametersMm[idx[k]];
            if (d == null) {
                break;
            }
            ref.add(d);
        }
        if (ref.isEmpty()) {
            a.reason = "no dot at the largest gap: the needle is at or below the surface for every step, or "
                    + "nothing dispensed; raise startGapMm";
            return a;
        }
        double refD = ref.size() == 1 ? ref.get(0) : (ref.get(0) + ref.get(1)) / 2;
        a.referenceDiameterMm = refD;
        for (int k = 1; k < idx.length; k++) {
            Double d = diametersMm[idx[k]];
            boolean contact = d == null || d > refD * (1 + jumpRatio);
            if (contact) {
                a.bracketHighMm = gapsMm[idx[k - 1]];
                a.bracketLowMm = gapsMm[idx[k]];
                a.contactGapMm = (a.bracketHighMm + a.bracketLowMm) / 2;
                a.reason = (d == null ? "dot stopped forming" : "dot squashed") + String.format(java.util.Locale.ROOT,
                        " between %.3f and %.3f mm", a.bracketHighMm, a.bracketLowMm);
                return a;
            }
        }
        a.reason = "no squash or missing dot down to the smallest gap; contact is below "
                + gapsMm[idx[idx.length - 1]] + " mm: lower the gaps";
        return a;
    }
}
