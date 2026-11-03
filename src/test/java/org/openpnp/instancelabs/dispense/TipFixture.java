package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.model.Configuration;

/**
 * The SIM machine set up for the tip setup tests (§22.8): the dispense service started on it (it sees the
 * controller connect), places, a purge row, the board and blank surfaces, the 22G's needle frame and offset and the
 * start angle, as DispenseServiceSimTest has them; and the 25G straight added as the API adds a setup.
 */
final class TipFixture implements AutoCloseable {
    final SimMachine sim;
    final DispenseService svc;

    TipFixture() throws Exception {
        sim = new SimMachine(false);
        svc = DispenseService.start(Configuration.get());
        sim.enableAndHome();
        DispenseConfig c = svc.getConfig();
        c.locations.put("waste", new DispenseConfig.NamedLocation(80, 40, 5.5));
        c.locations.put("blank", new DispenseConfig.NamedLocation(90, 40, 5.45));
        c.minPadTypeStatus = "provisional";
        c.purgeRow.x0Mm = 88.0;
        c.purgeRow.y0Mm = 45.0;
        c.purgeRow.spots = 16;
        svc.updateConfig(c);
        frame(-2.59);
        svc.setSurface("board", pts(new double[][] {{95, 75, 5.6}, {115, 75, 5.6}, {95, 90, 5.6}, {115, 90, 5.6}}),
                "test", DispenseServiceSimTest.TOY_BOARD);
        svc.setSurface("blank", pts(new double[][] {{85, 35, 5.45}, {130, 35, 5.45}, {85, 50, 5.45},
            {130, 50, 5.45}}), "test");
        svc.setStartAngle(null, "confirmed after power-up");
    }

    /** The mounted needle's frame (N1 at 5.5) and offset, under the needle token now. */
    void frame(double offsetMm) throws Exception {
        Long token = svc.getConfig().needleChangedAtMs;
        svc.setNeedleAnchor(5.5, 5.5 + offsetMm, null, null, null, token);
        svc.setNeedleZOffset(offsetMm, "manual", "test", token);
    }

    static List<DispenseConfig.SurfacePoint> pts(double[][] p) {
        List<DispenseConfig.SurfacePoint> l = new ArrayList<>();
        for (double[] v : p) {
            l.add(new DispenseConfig.SurfacePoint(v[0], v[1], v[2]));
        }
        return l;
    }

    /** The 25G straight added (version 1 and its parked entry, the profile copied from the mounted one). */
    TipSetup add25() throws Exception {
        TipSetup s = svc.getDb().insertTipSetupVersion(TipSetupsTest.straight25());
        svc.updateConfigAtomically(live -> {
            live.parkedTips.put(s.key, TipSetups.newEntry(live, live.profile, live.tip.key, s.name));
            return live;
        });
        return s;
    }

    /** "I changed the needle" for {@code key}, then the switch (as the API's mount route does both). */
    MountResult mount(String key) throws Exception {
        svc.recordNeedleChange("test", "tester");
        return svc.switchTip(key, "test", "tester");
    }

    /** The landing a landing check found, saved for the mounted setup under the needle token now. */
    DispenseConfig.NeedleLanding land(double dx, double dy) throws Exception {
        LandingResult r = new LandingResult();
        r.jobId = "landing-" + System.nanoTime();
        r.setupKey = svc.getConfig().tip.key;
        r.needleSinceMs = svc.getConfig().needleChangedAtMs;
        r.dxMm = dx;
        r.dyMm = dy;
        r.n = 8;
        r.sdMm = 0.04;
        return svc.setLanding(r, true, svc.getConfig().needleChangedAtMs);
    }

    @Override
    public void close() throws Exception {
        DispenseService.testHook = null;
        try {
            svc.shutdown();
        }
        finally {
            sim.close();
        }
    }
}
