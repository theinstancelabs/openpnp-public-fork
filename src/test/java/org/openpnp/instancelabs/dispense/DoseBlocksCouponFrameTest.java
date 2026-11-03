package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.sim.SimGuard;
import org.openpnp.model.Configuration;

/**
 * Review 2, I4 for the weigh coupons (profile calibration design §7.2 steps 2 and 4): a coupon's plane comes from its
 * own N1 touches and is no Z-check reference, so the weigh dots may take their Z from it only in the Z frame it was
 * touched in, or after a Z check of the same frame. With {@code homeBeforeRun "always"} every step of a calibration
 * homes, so the weigh step homes again after the coupon step: its dots must not go down at a coupon Z from before
 * that home (a home moved Z 0.7 mm on 2026-10-28; the needle moves about twice that).
 */
public class DoseBlocksCouponFrameTest {
    static List<DispenseConfig.SurfacePoint> pts(double[][] p) {
        List<DispenseConfig.SurfacePoint> l = new ArrayList<>();
        for (double[] v : p) {
            l.add(new DispenseConfig.SurfacePoint(v[0], v[1], v[2]));
        }
        return l;
    }

    @Test
    public void weighDotsRefuseACouponTouchedBeforeTheLastHome() throws Exception {
        try (SimMachine sim = new SimMachine(false)) {
            FlatVacuum.install(sim, FlatVacuum.scene(5.45, null, 0));
            DispenseService svc = DispenseService.start(Configuration.get());
            try {
                sim.enableAndHome();
                SimGuard.noteIdentity(sim.machine, List.of(SimGuard.SIMULATOR_FIRMWARE));
                DispenseConfig c = svc.getConfig();
                FlatVacuum.fastTimings(c);
                c.locations.put("blank", new DispenseConfig.NamedLocation(90, 40, 5.45));
                c.locations.put("coupon1", new DispenseConfig.NamedLocation(110, 20, 5.45));
                c.purgeRow.x0Mm = 88.0;
                c.purgeRow.y0Mm = 45.0;
                c.purgeRow.spots = 16;
                svc.updateConfig(c);
                // home 1: the needle frame, the touch-off and the blank (the purge row's surface)
                svc.setNeedleAnchor(5.5, 2.91, null, null);
                svc.setNeedleZOffset(-2.59, "manual", "test");
                svc.setSurface("blank", pts(new double[][] {{85, 35, 5.45}, {130, 35, 5.45}, {85, 50, 5.45},
                    {130, 50, 5.45}}), "test");
                // home 2: the coupon step (it homes first under "always") touches coupon1
                Thread.sleep(5);
                sim.machine.setEnabled(false);
                sim.enableAndHome();
                SimGuard.noteIdentity(sim.machine, List.of(SimGuard.SIMULATOR_FIRMWARE));
                svc.setStartAngle(null, "not power-cycled");
                svc.setSurface("coupon1", pts(new double[][] {{100, 10, 5.45}, {120, 10, 5.45}, {100, 30, 5.45}}),
                        "coupon look and three touches");
                c = svc.getConfig();
                c.homeBeforeRun = DispenseConfig.HOME_ALWAYS;
                svc.updateConfig(c);
                Thread.sleep(5);

                // the weigh step: it homes again (home 3), checks Z on the blank (home 1's frame), then the coupon
                DoseBlocksRequest req = new DoseBlocksRequest();
                req.kind = DoseBlocksRequest.WEIGH;
                req.image = false;
                req.parkAfter = false;
                req.stopEpoch = svc.stopEpoch();
                DoseBlocksRequest.Block b = new DoseBlocksRequest.Block(1600, "one turn on coupon1", "coupon1");
                b.couponPlace = "coupon1";
                for (int i = 0; i < 4; i++) {
                    b.xy.add(new double[] {106 + 2 * i, 18});
                }
                req.blocks.add(b);
                sim.server.mark();
                DoseBlocksResult res = svc.runDoseBlocks(req).get(120, TimeUnit.SECONDS);
                assertTrue(res.home.done, "homed first (homeBeforeRun always)");
                assertFalse(res.ok, res.message);
                assertEquals(RunResult.Status.REFUSED, res.run.status, res.message);
                assertTrue(res.message.startsWith("I4: the 'coupon1' coupon was touched before the home at "),
                        res.message);
                List<String> sent = sim.server.sinceMark();
                assertFalse(sent.contains(ControllerCommands.PUMP_ON) || sent.contains(ControllerCommands.VALVE_ON),
                        "the pressure never went on: " + sent);
                assertTrue(sent.stream().noneMatch(l -> l.startsWith("G1 B")), "the screw never turned");

                // touched again in this home's frame: the weigh dots go down
                svc.setSurface("coupon1", pts(new double[][] {{100, 10, 5.45}, {120, 10, 5.45}, {100, 30, 5.45}}),
                        "touched again");
                c = svc.getConfig();
                c.homeBeforeRun = DispenseConfig.HOME_WHEN_NEEDED;
                svc.updateConfig(c);
                req.stopEpoch = svc.stopEpoch();
                res = svc.runDoseBlocks(req).get(120, TimeUnit.SECONDS);
                assertTrue(res.ok, res.message);
            }
            finally {
                svc.shutdown();
            }
        }
    }

    /**
     * rc1 review (tests), AUTO_REGISTER_DESIGN §10.2 case 22 for the coupons: a coupon probed on the simulator never takes
     * weigh dots on a controller that is not the simulator (DispenseService.couponPlane's I4 text), before the pressure.
     */
    @Test
    public void weighDotsRefuseACouponProbedOnTheSimulatorOnAnotherController() throws Exception {
        try (SimMachine sim = new SimMachine(false)) {
            FlatVacuum.install(sim, FlatVacuum.scene(5.45, null, 0));
            sim.server.firmwareName = "Marlin (RecordingGcodeServer)";
            DispenseService svc = DispenseService.start(Configuration.get());
            try {
                sim.enableAndHome();
                SimGuard.noteIdentity(sim.machine, List.of("FIRMWARE_NAME:Marlin"));
                assertFalse(svc.isSimulatorConfirmed());
                DispenseConfig c = svc.getConfig();
                c.locations.put("blank", new DispenseConfig.NamedLocation(90, 40, 5.45));
                c.locations.put("coupon1", new DispenseConfig.NamedLocation(110, 20, 5.45));
                c.purgeRow.x0Mm = 88.0;
                c.purgeRow.y0Mm = 45.0;
                c.purgeRow.spots = 16;
                svc.updateConfig(c);
                svc.setNeedleAnchor(5.5, 2.91, null, null);
                svc.setNeedleZOffset(-2.59, "manual", "test");
                svc.setStartAngle(null, "confirmed after power-up");
                svc.setSurface("blank", pts(new double[][] {{85, 35, 5.45}, {130, 35, 5.45}, {85, 50, 5.45},
                    {130, 50, 5.45}}), "test");
                svc.setSurface("coupon1", pts(new double[][] {{100, 10, 5.45}, {120, 10, 5.45}, {100, 30, 5.45}}),
                        "three touches on the simulator", null, Boolean.TRUE);
                DoseBlocksRequest req = new DoseBlocksRequest();
                req.kind = DoseBlocksRequest.WEIGH;
                req.image = false;
                req.parkAfter = false;
                req.stopEpoch = svc.stopEpoch();
                DoseBlocksRequest.Block b = new DoseBlocksRequest.Block(1600, "one turn on coupon1", "coupon1");
                b.couponPlace = "coupon1";
                for (int i = 0; i < 4; i++) {
                    b.xy.add(new double[] {106 + 2 * i, 18});
                }
                req.blocks.add(b);
                sim.server.mark();
                DoseBlocksResult res = svc.runDoseBlocks(req).get(120, TimeUnit.SECONDS);
                assertEquals(RunResult.Status.REFUSED, res.run.status, res.message);
                assertEquals("I4: the 'coupon1' coupon was probed on the simulator: probe it again on this machine",
                        res.message);
                List<String> sent = sim.server.sinceMark();
                assertFalse(sent.contains(ControllerCommands.PUMP_ON) || sent.contains(ControllerCommands.VALVE_ON),
                        "the pressure never went on: " + sent);
            }
            finally {
                svc.shutdown();
            }
        }
    }
}
