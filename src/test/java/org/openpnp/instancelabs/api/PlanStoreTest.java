package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispensePlan;

/** The run subsets of a plan (POST /api/run with maxDots or padIds). */
public class PlanStoreTest {
    /** A plan in path order, one entry per pad: {padId, dots, net steps per dot}. */
    static DispensePlan plan(Object[]... pads) {
        DispensePlan p = new DispensePlan();
        p.planId = "p";
        for (Object[] x : pads) {
            DispensePlan.PlannedPad pad = new DispensePlan.PlannedPad();
            pad.padId = (String) x[0];
            pad.dotCount = (Integer) x[1];
            pad.dotSteps = (Integer) x[2];
            p.pads.add(pad);
            for (int k = 0; k < pad.dotCount; k++) {
                DispensePlan.PlannedDot d = new DispensePlan.PlannedDot();
                d.index = p.dots.size();
                d.padId = pad.padId;
                d.padIndex = p.pads.size() - 1;
                d.netSteps = pad.dotSteps;
                d.xMm = 100 + d.index;
                d.yMm = 80;
                p.dots.add(d);
            }
        }
        return p;
    }

    /**
     * Review 1: J1's two pads named 6 share the pad id J1.6. A subset keeps every dot's own pad (by the
     * plan's pad index) and its dose, so the stream does not take the two for one pad split in two.
     */
    @Test
    public void aSubsetKeepsEachDotsPadAndDose() {
        DispensePlan p = plan(new Object[] {"J1.6", 1, 800}, new Object[] {"J1.1", 1, 1600},
                new Object[] {"J1.6", 1, 800}, new Object[] {"R1.1", 1, 800});
        DispensePlan s = PlanStore.subset(p, null, 3);
        assertEquals(3, s.dots.size());
        assertEquals(3, s.pads.size());
        for (int i = 0; i < 3; i++) {
            assertSame(p.pads.get(i), s.pads.get(i));
            assertEquals(i, s.dots.get(i).padIndex, "dot " + i);
            assertEquals(p.dots.get(i).netSteps, s.dots.get(i).netSteps, "dot " + i);
        }
        // the first two dots: the second J1.6 pad has none, so it is not in the subset
        DispensePlan two = PlanStore.subset(p, null, 2);
        assertEquals(2, two.pads.size());
        assertSame(p.pads.get(0), two.pads.get(0));
        assertSame(p.pads.get(1), two.pads.get(1));
    }

    /**
     * Review 1: "Test run with fewer dots" cut pads of two quarter-turn dots in the middle, and the pad kept its
     * full dot count, so the dose check (R6: half a turn per pad) saw 800 steps where the run gave 400. The cut
     * falls before the pad it would split.
     */
    @Test
    public void maxDotsKeepsWholePads() {
        DispensePlan p = plan(new Object[] {"A.1", 2, 400}, new Object[] {"B.1", 2, 400},
                new Object[] {"C.1", 1, 800});
        DispensePlan s = PlanStore.subset(p, null, 3);
        assertEquals(2, s.dots.size(), "B.1's two dots would be cut after the first");
        assertEquals(1, s.pads.size());
        assertEquals("A.1", s.pads.get(0).padId);
        assertEquals("subset of plan p: 2 of 5 dots on 1 of 3 pads (path order kept; whole pads, so 2 of the 3 dots "
                + "asked for)", s.warnings.get(s.warnings.size() - 1));
        for (int n : new int[] {2, 4}) {
            DispensePlan w = PlanStore.subset(p, null, n);
            assertEquals(n, w.dots.size());
            assertEquals("subset of plan p: " + n + " of 5 dots on " + n / 2 + " of 3 pads (path order kept)",
                    w.warnings.get(w.warnings.size() - 1));
            for (int i = 0; i < w.pads.size(); i++) {
                int dots = 0;
                for (DispensePlan.PlannedDot d : w.dots) {
                    dots += d.padIndex == i ? 1 : 0;
                }
                assertEquals(w.pads.get(i).dotCount, dots, "every pad has all its dots: " + w.pads.get(i).padId);
            }
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PlanStore.subset(p, null,
                1));
        assertEquals("a test run lays whole pads: the first pad, A.1, has 2 dots, so ask for at least 2",
                e.getMessage());
        // of some pads: the first dots of those
        DispensePlan b = PlanStore.subset(p, Arrays.asList("B.1", "C.1"), 2);
        assertEquals(2, b.dots.size());
        assertEquals("B.1", b.pads.get(0).padId);
        assertEquals(3, PlanStore.subset(p, Arrays.asList("B.1", "C.1"), 3).dots.size());
        assertSame(p, PlanStore.subset(p, null, 5), "nothing left out");
    }
}
