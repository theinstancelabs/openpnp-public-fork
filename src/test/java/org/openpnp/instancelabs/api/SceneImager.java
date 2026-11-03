package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.openpnp.instancelabs.dispense.DotImager;
import org.openpnp.instancelabs.sim.SimBoardRenderer;
import org.openpnp.instancelabs.sim.SimScene;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.model.Board;

/**
 * The SIM top camera for the API tests, whose machine has no OpenPnP camera: every frame is drawn by
 * {@link SimBoardRenderer} from the scene as it is now (the board at its true seat, the blank, paste),
 * at the bench frame size and OpenPnP's camera scale (machine.xml), centred where the engine asks.
 */
final class SceneImager implements DotImager {
    static final int W = 1920, H = 1080;
    static final double UPP_X = 0.01068376, UPP_Y = 0.01072961;

    private final Supplier<SimScene> scene;
    private final Supplier<Board> board;
    final AtomicInteger captures = new AtomicInteger();
    /** Wait before each frame (ms): a slow camera, so a STOP lands during the fiducial check. */
    volatile long delayMs;

    SceneImager(Supplier<SimScene> scene, Supplier<Board> board) {
        this.scene = scene;
        this.board = board;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Frame capture(double xMm, double yMm) throws Exception {
        return captureLit(xMm, yMm, null);
    }

    @Override
    public Frame captureLit(double xMm, double yMm, Double surfaceZMm) throws Exception {
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
        captures.incrementAndGet();
        return new Frame(SimBoardRenderer.render(scene.get(), board.get(), xMm, yMm, W, H, UPP_X, UPP_Y), UPP_X, UPP_Y,
                xMm, yMm);
    }

    @Override
    public List<Dot> inspect(Frame before, Frame after) {
        return new ArrayList<>();
    }
}
