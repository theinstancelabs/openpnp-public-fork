package org.openpnp.instancelabs.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.openpnp.instancelabs.dispense.BoardRegistration;
import org.openpnp.instancelabs.dispense.DispensePlan;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.model.BoardLocation;

/**
 * Plans a plan's board again from its source (PROFILE_CALIBRATION_DESIGN.md §12.1, as Register board does, AR §5.3):
 * the same board and seat with the registration the plan was made with, the pad types and mappings as they are now.
 * The new plan is estimated, gets its registration preview and its drawing fixed, and is kept in the plan store. Used
 * after a suggestion was applied and by the pad calibration (§7.2 step 7).
 */
final class PlanAgain {
    private PlanAgain() {
    }

    /** The new entry; refused as POST /api/plan would be (board gone, registration no longer stored or valid). */
    static PlanStore.Entry of(ApiContext ctx, DispenseEndpoints dispense, PlanStore.Entry e) throws Exception {
        DispenseService svc = ctx.availableSvc();
        Map<String, Object> src = new LinkedHashMap<>(e.source);
        BoardLocation bl = dispense.baseBoardLocation(src);
        String regId = (String) src.get("registrationId");
        BoardRegistration reg = regId == null ? null : dispense.registrationFor(svc, regId, src, bl);
        DispensePlan plan = svc.planBoard(bl, reg);
        dispense.artRef(plan, bl);
        PlanStore.Entry next = new PlanStore.Entry(plan, src);
        next.registrationRecord = reg;
        dispense.estimate(svc, next);
        next.registrationTargets = dispense.registration.targets(svc, next);
        ctx.plans.put(next);
        return next;
    }
}
