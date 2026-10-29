package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.DoseModel;

/**
 * The weighing of the pad calibration (profile calibration design §7.2, §7.5): counts of whole revolutions, the grid
 * inside the coupon's margin, the camera looks and the touches, and the allocation of doses to the coupons.
 */
public class WeighPlanTest {
    static final double[] COUPON = {330, 40, 370, 70};

    @Test
    public void countsAreWholeRevolutionsNearTheTarget() {
        assertEquals(136, WeighPlan.count(400, DoseModel.massMg(400), 70), "ceil(70 / 0.53) = 133, x 4");
        assertEquals(68, WeighPlan.count(800, DoseModel.massMg(800), 70));
        assertEquals(34, WeighPlan.count(1600, DoseModel.massMg(1600), 70));
        assertEquals(32, WeighPlan.count(400, DoseModel.massMg(400), 15), "at least 30 dots, x 4");
        assertEquals(30, WeighPlan.count(800, DoseModel.massMg(800), 15));
        assertEquals(200, WeighPlan.count(400, 0.1, 70), "at most 200 dots");
        assertEquals(64, WeighPlan.count(400, 0.575, 35), "the SIM's 0.575 mg quarter turn at 35 mg");
    }

    @Test
    public void theGridLiesInsideTheMarginCentredOnThePlace() {
        double d95 = DoseModel.dHiMm(400);
        List<double[]> g = WeighPlan.grid(COUPON, 136, 2.0, d95, 3);
        assertEquals(136, g.size());
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        for (double[] p : g) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
            assertTrue(p[0] >= 330 + 3 + d95 / 2 - 1e-9 && p[0] <= 370 - 3 - d95 / 2 + 1e-9);
            assertTrue(p[1] >= 40 + 3 + d95 / 2 - 1e-9 && p[1] <= 70 - 3 - d95 / 2 + 1e-9);
        }
        // §7.5: 8 rows of 17, 32 x 14 mm of dot centres about the place
        assertEquals(32, maxX - minX, 1e-9);
        assertEquals(14, maxY - minY, 1e-9);
        assertEquals(350, (minX + maxX) / 2, 1e-9);
        assertEquals(55, (minY + maxY) / 2, 1e-9);
        assertEquals(g.get(16)[0], g.get(17)[0], 1e-9, "serpentine");
        assertEquals(2.0, g.get(1)[0] - g.get(0)[0], 1e-9, "the hop pitch along a row");
        assertNull(WeighPlan.grid(COUPON, 200, 2.5, d95, 3), "200 dots at 2.5 mm do not fit 40 x 30 mm");
        assertEquals(3 + d95 / 2, WeighPlan.insetMm(d95, 3), 1e-12);
    }

    @Test
    public void looksAtTheCornersAndTheMiddleAndTouchesThreeCorners() {
        List<double[]> g = WeighPlan.grid(COUPON, 136, 2.0, 1.0, 3);
        List<double[]> looks = WeighPlan.looks(g, 2.1);
        assertEquals(5, looks.size());
        assertEquals(334 - 2.1, looks.get(0)[0], 1e-9);
        assertEquals(48 - 2.1, looks.get(0)[1], 1e-9);
        assertEquals(366 + 2.1, looks.get(2)[0], 1e-9);
        assertEquals(62 + 2.1, looks.get(2)[1], 1e-9);
        assertEquals(350, looks.get(4)[0], 1e-9, "the middle last");
        List<double[]> t = WeighPlan.touches(COUPON);
        assertEquals(3, t.size());
        assertEquals(336, t.get(0)[0], 1e-9);
        assertEquals(46, t.get(0)[1], 1e-9);
        assertEquals(364, t.get(1)[0], 1e-9);
        assertEquals(64, t.get(2)[1], 1e-9);
    }

    static WeighPlan.Need need(int steps, int groups) {
        return new WeighPlan.Need(steps, groups);
    }

    @Test
    public void allocatesTheDosesToTheCoupons() {
        WeighPlan.Allocation none = WeighPlan.allocate(Collections.emptyList(), 800, false, 2);
        assertTrue(none.coupons.isEmpty() && none.notWeighed.isEmpty());
        // one dose: the profile dose on coupon 2 when it has no confirmed mass (the control's weighing) ...
        assertEquals(Arrays.asList(400, 800), WeighPlan.allocate(Arrays.asList(need(400, 1)), 800, false, 2).coupons);
        // ... else the same dose again (two readings confirm it)
        assertEquals(Arrays.asList(400, 400), WeighPlan.allocate(Arrays.asList(need(400, 1)), 800, true, 2).coupons);
        assertEquals(Arrays.asList(800, 800), WeighPlan.allocate(Arrays.asList(need(800, 2)), 800, false, 2).coupons);
        // two doses: one each, the one more groups chose first, then the smaller
        assertEquals(Arrays.asList(400, 1600), WeighPlan.allocate(Arrays.asList(need(1600, 1), need(400, 1)), 800,
                false, 2).coupons);
        assertEquals(Arrays.asList(800, 400), WeighPlan.allocate(Arrays.asList(need(400, 1), need(800, 2)), 800,
                false, 2).coupons);
        // three: the first two; the rest waits for another calibration
        WeighPlan.Allocation three = WeighPlan.allocate(Arrays.asList(need(1600, 1), need(400, 1), need(800, 1)), 800,
                false, 2);
        assertEquals(Arrays.asList(400, 800), three.coupons);
        assertEquals(Arrays.asList(1600), three.notWeighed);
        // one coupon place: one weighing
        WeighPlan.Allocation one = WeighPlan.allocate(Arrays.asList(need(400, 1), need(800, 1)), 800, false, 1);
        assertEquals(Arrays.asList(400), one.coupons);
        assertEquals(Arrays.asList(800), one.notWeighed);
        assertEquals(Arrays.asList(400), WeighPlan.allocate(Arrays.asList(need(400, 1)), 800, false, 0).notWeighed);
    }
}
