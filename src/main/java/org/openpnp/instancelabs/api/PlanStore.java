package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.openpnp.instancelabs.dispense.DispensePlan;

/**
 * Plans the UI has previewed, so "Run" dispenses exactly the plan that was shown (by planId).
 * Keeps the most recent {@value #MAX} plans in memory; every plan is also stored by the engine
 * (pad_plan table).
 */
final class PlanStore {
    static final int MAX = 20;

    static final class Entry {
        final DispensePlan plan;
        /** How the board was chosen (job board or file + location), echoed back to the UI. */
        final Map<String, Object> source;
        /** Estimate of the whole program (moves with acceleration, dwells), s; null if unknown. */
        Double estimatedSeconds;
        Double estimatedPressurisedSeconds;
        /** Cross after the purge to the last dot (the bench's DPH window), s. */
        Double estimatedWorkSeconds;
        String estimateError;
        /** The last camera-only clean-pad check of this plan (POST /api/plan/{id}/clean-check). */
        volatile java.util.concurrent.Future<org.openpnp.instancelabs.dispense.PadCleanReport> cleanCheckFuture;

        /** The finished clean-pad check, or null (none, still running or failed). */
        org.openpnp.instancelabs.dispense.PadCleanReport cleanCheck() {
            java.util.concurrent.Future<org.openpnp.instancelabs.dispense.PadCleanReport> f = cleanCheckFuture;
            try {
                return f != null && f.isDone() ? f.get() : null;
            }
            catch (Exception e) {
                return null;
            }
        }

        boolean cleanCheckRunning() {
            java.util.concurrent.Future<?> f = cleanCheckFuture;
            return f != null && !f.isDone();
        }

        Entry(DispensePlan plan, Map<String, Object> source) {
            this.plan = plan;
            this.source = source;
        }
    }

    private final LinkedHashMap<String, Entry> plans = new LinkedHashMap<String, Entry>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX;
        }
    };

    synchronized void put(Entry e) {
        plans.put(e.plan.planId, e);
    }

    synchronized Entry get(String planId) {
        return plans.get(planId);
    }

    /** The plans kept, newest first, without the run subsets ({@code <planId>-subset-...}). */
    synchronized List<Entry> recent() {
        List<Entry> l = new ArrayList<>();
        for (Entry e : plans.values()) { // iterating does not change the access order
            if (!e.plan.planId.contains("-subset-")) {
                l.add(0, e); // most recently used first, so it wins a tie of createdAtMs below (stable sort)
            }
        }
        l.sort((a, b) -> Long.compare(b.plan.createdAtMs, a.plan.createdAtMs));
        return l;
    }

    /**
     * A plan restricted to some pads and/or the first {@code maxDots} dots in path order. The path
     * order of the full plan is kept and dots are re-indexed from 0. Returns the plan itself if
     * nothing is excluded.
     */
    static DispensePlan subset(DispensePlan p, List<String> padIds, Integer maxDots) {
        Set<String> wanted = padIds == null || padIds.isEmpty() ? null : new HashSet<>(padIds);
        if (wanted != null) {
            Set<String> known = new HashSet<>();
            for (DispensePlan.PlannedPad pad : p.pads) {
                known.add(pad.padId);
            }
            List<String> unknown = new ArrayList<>();
            for (String id : wanted) {
                if (!known.contains(id)) {
                    unknown.add(id);
                }
            }
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("pads not in plan " + p.planId + ": " + unknown);
            }
        }
        if (maxDots != null && maxDots < 1) {
            throw new IllegalArgumentException("maxDots must be at least 1");
        }
        List<DispensePlan.PlannedDot> dots = new ArrayList<>();
        for (DispensePlan.PlannedDot d : p.dots) {
            if (wanted != null && !wanted.contains(d.padId)) {
                continue;
            }
            if (maxDots != null && dots.size() >= maxDots) {
                break;
            }
            dots.add(d);
        }
        if (dots.size() == p.dots.size()) {
            return p;
        }
        Set<String> padsWithDots = new HashSet<>();
        for (DispensePlan.PlannedDot d : dots) {
            padsWithDots.add(d.padId);
        }
        DispensePlan s = new DispensePlan();
        s.planId = p.planId + "-subset-" + UUID.randomUUID().toString().substring(0, 8);
        s.boardName = p.boardName;
        s.boardLocationId = p.boardLocationId;
        s.createdAtMs = System.currentTimeMillis();
        s.profile = p.profile;
        s.profileFingerprint = p.profileFingerprint;
        s.needleGapMm = p.needleGapMm;
        s.needleZOffsetMm = p.needleZOffsetMm;
        s.hopHeightMm = p.hopHeightMm;
        s.surfaceFingerprint = p.surfaceFingerprint;
        s.boardFingerprint = p.boardFingerprint;
        s.boardRegistration = p.boardRegistration;
        s.padTypesFingerprint = p.padTypesFingerprint;
        double path = 0;
        for (int i = 1; i < dots.size(); i++) {
            path += Math.hypot(dots.get(i).xMm - dots.get(i - 1).xMm, dots.get(i).yMm - dots.get(i - 1).yMm);
        }
        // Dot-to-dot length of the kept dots (the parent's start leg is not included).
        s.pathLengthMm = path;
        s.pathLengthNearestNeighbourMm = Double.NaN;
        for (DispensePlan.PlannedPad pad : p.pads) {
            if (padsWithDots.contains(pad.padId)) {
                s.pads.add(pad);
            }
        }
        for (int i = 0; i < dots.size(); i++) {
            DispensePlan.PlannedDot o = dots.get(i);
            DispensePlan.PlannedDot d = new DispensePlan.PlannedDot();
            d.index = i;
            d.padId = o.padId;
            d.xMm = o.xMm;
            d.yMm = o.yMm;
            d.surfaceZMm = o.surfaceZMm;
            d.dispenseZMm = o.dispenseZMm;
            d.hopZMm = o.hopZMm;
            d.rotationDeg = o.rotationDeg;
            s.dots.add(d);
        }
        s.warnings.addAll(p.warnings);
        s.warnings.add("subset of plan " + p.planId + ": " + dots.size() + " of " + p.dots.size() + " dots on "
                + s.pads.size() + " of " + p.pads.size() + " pads (path order kept)");
        return s;
    }
}
