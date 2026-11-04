package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * BR-77: the engine hooks of the start-angle measure. A dose-blocks step lays its block at the request's own start
 * angle (builder and stream validator agree on it), and the stored angle stays as it was.
 */
public class StartAngleBlocksTest {
    /** The count angle each dot of the step's first block started at (deg). */
    static double[] angles(DoseBlocksResult r) {
        List<DoseBlocksResult.DotOut> dots = r.blocks.get(0).dots;
        double[] a = new double[dots.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = DispenseProfile.angleOfCount(dots.get(i).startCount, 1600);
        }
        return a;
    }

    @Test
    public void aStepLaysItsBlockAtTheRequestsStartAngle() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            svc.setDotImager(new LandingCheckBlocksTest.BandImager());
            DoseBlocksRequest req = LandingCheckBlocksTest.ladder(svc, LandingCheckBlocksTest.row(98, 40, 4));
            req.purgeXy = LandingCheckBlocksTest.row(98, 46, 2);
            req.startAngleDeg = 100.0;
            DoseBlocksResult r = svc.runDoseBlocks(req).get(120, TimeUnit.SECONDS);
            assertTrue(r.ok, r.message);
            double[] a = angles(r);
            assertEquals(4, a.length);
            for (int i = 0; i < a.length; i++) {
                assertEquals(i % 2 == 0 ? 100 : 280, a[i], 0.225 / 2, "dot " + (i + 1));
            }
            assertEquals(298, svc.getConfig().profile.startAngleDeg, 0, "the stored angle is unchanged");
            // without it the profile's angle
            DoseBlocksRequest own = LandingCheckBlocksTest.ladder(svc, LandingCheckBlocksTest.row(98, 42, 2));
            own.purgeXy = LandingCheckBlocksTest.row(98, 48, 2);
            DoseBlocksResult o = svc.runDoseBlocks(own).get(120, TimeUnit.SECONDS);
            assertTrue(o.ok, o.message);
            assertEquals(298, angles(o)[0], 0.225);
        }
    }
}
