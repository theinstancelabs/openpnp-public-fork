package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Per-dot times of a board program (the path preview's clock): from the same estimate as the
 * program's duration, by plan dot index.
 */
public class DotTimesTest {
    static void assertOrdered(GcodeProgram p, int n) {
        assertEquals(n, p.dotArriveSeconds.length);
        assertEquals(n, p.dotEndSeconds.length);
        for (int i = 0; i < n; i++) {
            assertTrue(p.dotArriveSeconds[i] > 0, "dot " + i + " arrives after the start");
            assertTrue(p.dotArriveSeconds[i] < p.dotEndSeconds[i], "dot " + i + ": arrive < end");
            if (i + 1 < n) {
                assertTrue(p.dotEndSeconds[i] <= p.dotArriveSeconds[i + 1], "dot " + i + " ends before the next");
            }
        }
        assertTrue(p.dotEndSeconds[n - 1] <= p.estimatedSeconds, "the session end comes after the last dot");
    }

    /** With the purge row: the work time minus the cross from the purge to the first dot. */
    @Test
    public void timesFollowTheEstimateWithAPurge() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        DispensePlan plan = GcodeStreamTest.toyPlan(c);
        List<GcodeStreamBuilder.DotSpec> purge = GcodeStreamTest.purge(c);
        assertFalse(purge.isEmpty());
        GcodeProgram p = GcodeStreamBuilder.forPlan(c, GcodeStreamTest.mapper(), plan, purge, false, false, 0L);
        int n = plan.dots.size();
        assertEquals(3, n);
        assertOrdered(p, n);
        // The cross: lift from the last purge dot to travel Z, then XY to the first dot (ToyMapper: 1:1 in XY and Z).
        GcodeStreamBuilder.DotSpec last = purge.get(purge.size() - 1);
        DispensePlan.PlannedDot first = plan.dots.get(0);
        double cross = GcodeStreamBuilder.moveSeconds(c.travelSafeZMm - last.hopZ, c.zLiftFeedMmS, c.travelAccelMmS2)
                + GcodeStreamBuilder.moveSeconds(Math.hypot(first.xMm - last.x, first.yMm - last.y), c.travelFeedMmS,
                        c.travelAccelMmS2);
        assertTrue(cross > 0);
        double span = p.dotEndSeconds[n - 1] - p.dotArriveSeconds[0];
        assertEquals(p.estimatedWorkSeconds - cross, span, 1e-9);
    }

    /** Without a purge the cross is empty: the first dot arrives when the work starts. */
    @Test
    public void withoutAPurgeTheSpanIsTheWorkTime() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        DispensePlan plan = GcodeStreamTest.toyPlan(c);
        GcodeProgram p = GcodeStreamBuilder.forPlan(c, GcodeStreamTest.mapper(), plan, Collections.emptyList(), false,
                false, 0L);
        int n = plan.dots.size();
        assertOrdered(p, n);
        assertEquals(p.estimatedWorkSeconds, p.dotEndSeconds[n - 1] - p.dotArriveSeconds[0], 1e-9);
        // after the first (which comes down from travel Z), every dot takes the same time over its pad (same
        // profile, same Z): only the travel between them differs
        assertTrue(p.dotEndSeconds[0] - p.dotArriveSeconds[0] > p.dotEndSeconds[1] - p.dotArriveSeconds[1]);
        for (int i = 2; i < n; i++) {
            assertEquals(p.dotEndSeconds[1] - p.dotArriveSeconds[1], p.dotEndSeconds[i] - p.dotArriveSeconds[i], 1e-9);
        }
        // between a lift and the next arrival there is only the XY move at the lift height
        for (int i = 0; i + 1 < n; i++) {
            DispensePlan.PlannedDot a = plan.dots.get(i);
            DispensePlan.PlannedDot b = plan.dots.get(i + 1);
            double xy = GcodeStreamBuilder.moveSeconds(Math.hypot(b.xMm - a.xMm, b.yMm - a.yMm), c.travelFeedMmS,
                    c.travelAccelMmS2);
            assertEquals(xy, p.dotArriveSeconds[i + 1] - p.dotEndSeconds[i], 1e-9, "dot " + i + " -> " + (i + 1));
        }
    }

    @Test
    public void emptyPlan() throws Exception {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        DispensePlan plan = GcodeStreamTest.toyPlan(c);
        plan.dots.clear();
        GcodeProgram p = GcodeStreamBuilder.forPlan(c, GcodeStreamTest.mapper(), plan, GcodeStreamTest.purge(c), false,
                false, 0L);
        assertEquals(0, p.dotArriveSeconds.length);
        assertEquals(0, p.dotEndSeconds.length);
    }
}
