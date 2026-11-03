package org.openpnp.instancelabs.dispense;

import java.util.List;

/**
 * Planners of the tests (tip setups design §22.2: the planner needs the mounted tip): the configuration's tip, read
 * when the plan is made, as the bench 22G unless the test names another.
 */
final class TestTips {
    private TestTips() {
    }

    /** A planner that plans with the tip of {@code c} at the time of the plan (without its dot table). */
    static DispensePlanner planner(DispenseConfig c, PadTypeResolver r) {
        return new DispensePlanner(c, r) {
            @Override
            public DispensePlan plan(String boardName, List<PadGeometry> pads, double startX, double startY) {
                withTip(PadInstances.specTip(c.tip));
                return super.plan(boardName, pads, startX, startY);
            }
        };
    }
}
