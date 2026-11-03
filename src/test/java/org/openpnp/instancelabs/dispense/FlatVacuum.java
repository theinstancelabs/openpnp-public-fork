package org.openpnp.instancelabs.dispense;

import java.awt.geom.AffineTransform;

import org.openpnp.instancelabs.sim.SimGuard;
import org.openpnp.instancelabs.sim.SimScene;
import org.openpnp.instancelabs.sim.SimVacuum;

/**
 * The SIM N1 vacuum for engine and API tests whose surfaces are typed in by hand: N1 seals at the Z of a
 * board rectangle inside it and at one flat Z everywhere else, at once (no settle or release lag), so a
 * Z check after a home touches the stored points where they were probed. The recording server answers
 * M115 as OpenPnP's simulator, which the fast probe timings need.
 */
public final class FlatVacuum {
    /** RecordingGcodeServer's M115 name that SimGuard takes for OpenPnP's simulator. */
    public static final String SIMULATOR = SimGuard.SIMULATOR_FIRMWARE.substring("FIRMWARE_NAME:".length())
            + " (RecordingGcodeServer)";

    private FlatVacuum() {
    }

    /**
     * Contact {@code z} everywhere; with {@code board} = {x0, y0, x1, y1} (machine mm) contact
     * {@code boardZ} inside that rectangle.
     */
    public static SimScene scene(double z, double[] board, double boardZ) {
        SimScene sc = new SimScene();
        if (board != null) {
            sc.seat = AffineTransform.getTranslateInstance(board[0], board[1]);
            sc.boardWidthMm = board[2] - board[0];
            sc.boardHeightMm = board[3] - board[1];
            sc.boardSurface = new SimScene.Plane(boardZ, 0, 0);
        }
        else {
            sc.seat = new AffineTransform();
        }
        sc.blankRect = new double[] {-1000, -1000, 1000, 1000};
        sc.blankSurface = new SimScene.Plane(z, 0, 0);
        sc.vacuum.tauS = 0;
        sc.vacuum.sealTauS = 0;
        sc.vacuum.releaseTauS = 0;
        sc.validate();
        return sc;
    }

    /** The vacuum on the recording server, which then also answers M115 as the simulator. */
    public static SimVacuum install(SimMachine sim, SimScene sc) {
        SimVacuum v = new SimVacuum(sc);
        sim.server.vacuum = v;
        sim.server.firmwareName = SIMULATOR;
        return v;
    }

    /** il_sim_scene.py FAST_TIMINGS: a 300 ms settle and no read delay (refused unless simulated). */
    public static void fastTimings(DispenseConfig c) {
        c.vacuumProbe.vacuumSettleMs = 300;
        c.vacuumProbe.readDelayMs = 0;
    }
}
