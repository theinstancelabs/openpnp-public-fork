package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.FlatVacuum;
import org.openpnp.instancelabs.dispense.RunResult;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.instancelabs.dispense.SurfaceProbeRequest;
import org.openpnp.instancelabs.dispense.SurfaceProbeResult;
import org.openpnp.model.Configuration;

/**
 * INF-15 (2), Process's BR-100 test in the SIM: N1's line lags a seal by 0.5 s (the real ~0.3-1 s,
 * tools/tilesetup.py) under the bench's probe timings. The blank's probe reads the Z it reads without the lag,
 * within one fine step below the contact, and the Z check after a home on the unmoved frame passes.
 */
public class SealLagSimTest {
    /** Off the probe's 0.025 mm grid (nominal 5.45): the first step at or below it is 5.425. */
    static final double CONTACT = 5.437;
    SimMachine sim;
    DispenseService svc;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine(false);
        svc = DispenseService.start(Configuration.get());
        sim.enableAndHome();
    }

    @AfterEach
    public void after() throws Exception {
        svc.shutdown();
        sim.close();
    }

    @Test
    public void aLaggingLineReadsTheSameBlankZAndTheZCheckAfterAHomePasses() throws Exception {
        double[] quick = probeBlank(0);
        double[] lagging = probeBlank(0.5);
        assertEquals(Arrays.toString(quick), Arrays.toString(lagging), "the lag changes no point's Z");
        for (double z : lagging) {
            assertTrue(z <= CONTACT && z > CONTACT - 0.025, "within one fine step below the contact: " + z);
        }
        // A home on the unmoved frame: the Z check touches a blank point from before it, through the lag.
        sim.machine.setEnabled(false);
        sim.enableAndHome();
        RunResult r = svc.checkZ().get(120, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, r.status, r.toString());
        assertTrue(r.zCheck != null && r.zCheck.pass, String.valueOf(r.zCheck));
    }

    /** The blank probed at 3 spots with N1's line lagging by sealTauS = releaseTauS = lagS; the contact Zs. */
    double[] probeBlank(double lagS) throws Exception {
        SimScene sc = FlatVacuum.scene(CONTACT, null, 0);
        sc.vacuum.sealTauS = lagS;
        sc.vacuum.releaseTauS = lagS;
        FlatVacuum.install(sim, sc);
        SurfaceProbeRequest req = new SurfaceProbeRequest();
        req.surfaceName = "blank";
        req.nominalZ = 5.45;
        req.targets = Arrays.asList(new double[] {86, 36}, new double[] {129, 36}, new double[] {86, 49});
        SurfaceProbeResult r = svc.probeSurface(req).get(120, TimeUnit.SECONDS);
        assertTrue(r.ok, r.message);
        return r.points.stream().mapToDouble(p -> p.z).toArray();
    }
}
