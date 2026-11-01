package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.instancelabs.vision.Dot;

/**
 * Optical dot ladder (plan §5.6, §5.2 step 3): dispense rotations r_i on the blank coupon, image
 * each dot before/after with the top camera, measure diameter/area with DotInspector and append one
 * dot_ladder row per rotation. Layout and statistics are pure functions here; the machine flow is
 * in {@link DispenseService#runDotLadder}.
 */
public final class DotLadderCalibration {
    private DotLadderCalibration() {
    }

    public static class Request {
        /** Rotations per dot to characterise (deg of B), e.g. {30, 45, 58, 75, 90}. */
        public double[] rotationsDeg = {30, 45, 58, 75, 90};
        public int repeats = 5;
        /** Dot pitch on the coupon (mm); must exceed the largest expected dot. */
        public double pitchMm = 1.5;
        public String locationName;
        public boolean primeAtWaste = true;
    }

    public static class Result {
        public long calibrationId;
        public final List<DotLadderRow> rows = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public RunResult run;
    }

    /**
     * Dot specs: one row per rotation, `repeats` columns, each at the probed blank plane under it
     * plus the offset and gap (one flat Z would vary the gap with the blank's slope). Without a
     * plane (null) the blank place's single Z is used; runs refuse that (I4: no blank surface).
     */
    public static List<GcodeStreamBuilder.DotSpec> layout(Request req, DispenseConfig.NamedLocation blank,
            DispenseConfig cfg, SurfacePlane plane) {
        List<GcodeStreamBuilder.DotSpec> l = new ArrayList<>();
        int idx = 0;
        for (int r = 0; r < req.rotationsDeg.length; r++) {
            for (int c = 0; c < req.repeats; c++) {
                // serpentine rows keep the path short
                int col = (r % 2 == 0) ? c : req.repeats - 1 - c;
                double x = blank.x + col * req.pitchMm, y = blank.y + r * req.pitchMm;
                double z = (plane == null ? blank.z : plane.z(x, y)) + cfg.needleZOffsetMm + cfg.gapMm();
                l.add(new GcodeStreamBuilder.DotSpec(x, y, z, z + cfg.hopHeightMm, req.rotationsDeg[r], idx++,
                        "ladder r=" + req.rotationsDeg[r]));
            }
        }
        return l;
    }

    /**
     * Rows from measured dots. dotsPerSpec.get(i) is what was found at spec i (null = nothing found).
     */
    public static List<DotLadderRow> analyze(long calibrationId, Request req,
            List<GcodeStreamBuilder.DotSpec> specs, List<Dot> dotsPerSpec, Double zGapMm, List<String> notes) {
        List<DotLadderRow> rows = new ArrayList<>();
        for (double rot : req.rotationsDeg) {
            List<Double> d = new ArrayList<>();
            List<Double> a = new ArrayList<>();
            int missing = 0;
            for (int i = 0; i < specs.size(); i++) {
                if (specs.get(i).rotationDeg != rot) {
                    continue;
                }
                Dot dot = dotsPerSpec.get(i);
                if (dot == null) {
                    missing++;
                    continue;
                }
                d.add(dot.diameterMm);
                a.add(dot.areaMm2);
            }
            if (d.isEmpty()) {
                notes.add("rotation " + rot + ": no dots detected (" + missing + " missing), no row stored");
                continue;
            }
            double[] dv = toArray(d), av = toArray(a);
            DotLadderRow row = new DotLadderRow(calibrationId, rot, d.size(), DotMath.mean(dv),
                    nanToNull(DotMath.cv(dv)), DotMath.mean(av), nanToNull(DotMath.cv(av)));
            row.zGapMm = zGapMm;
            row.method = "optical";
            row.notes = missing > 0 ? missing + " of " + (missing + d.size()) + " dots not detected" : null;
            if (missing > 0) {
                notes.add("rotation " + rot + ": " + row.notes);
            }
            rows.add(row);
        }
        return rows;
    }

    static double[] toArray(List<Double> l) {
        double[] v = new double[l.size()];
        for (int i = 0; i < v.length; i++) {
            v[i] = l.get(i);
        }
        return v;
    }

    static Double nanToNull(double v) {
        return Double.isNaN(v) ? null : v;
    }
}
