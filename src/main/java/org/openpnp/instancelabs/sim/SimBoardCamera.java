package org.openpnp.instancelabs.sim;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.openpnp.machine.reference.SimulationModeMachine;
import org.openpnp.machine.reference.camera.ImageCamera;
import org.openpnp.machine.reference.driver.AbstractReferenceDriver.CommunicationsType;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Driver;
import org.openpnp.spi.Machine;
import org.openpnp.util.ImageUtils;

/**
 * The SIM top camera: draws the sim scene's board (SimBoardRenderer) where the camera looks. Installed
 * in a uinav SIM session's config copy with {@code --xml-set} on the Top camera (uinav first turns it
 * into an ImageCamera, so its XML fits this class).
 *
 * <ul>
 * <li>{@link #open()} refuses unless every driver is loopback TCP and the scene file exists, so a
 * mis-set class in a real configuration only gives a camera that does not open.</li>
 * <li>{@link #internalCapture()} draws the board only while {@link SimGuard#isSimulated} (the
 * controller said GcodeServer on this connection); otherwise a uniform grey frame stamped
 * {@value #UNCONFIRMED}, on which nothing can be found.</li>
 * <li>The configured flips are applied to the drawing in advance: ReferenceCamera's transform flips it
 * back, so the engine sees the board the right way round. Any other image transform is refused.</li>
 * </ul>
 */
public class SimBoardCamera extends ImageCamera {
    static final String SOURCE_URI = "classpath://org/openpnp/instancelabs/sim/sim-1px.png";
    static final String UNCONFIRMED = "SIM CAMERA: controller not confirmed";
    static final String NO_SCENE = "SIM CAMERA: no sim scene";
    /** The Swing view captures about 10 times a second: a still camera reuses its frame for up to this long. */
    static final long CACHE_MS = 1000;

    private SimScene.Source scenes;
    private List<Object> cachedKey;
    private long cachedAtMs;
    private BufferedImage cachedFrame;

    @Override
    public synchronized void open() throws Exception {
        requireSimulationMachine(Configuration.get());
        if (!SOURCE_URI.equals(getSourceUri())) {
            setSourceUri(SOURCE_URI); // never the 7002 x 4000 test image
        }
        super.open();
    }

    /** Throws unless every driver is a GcodeDriver on loopback TCP and the scene file exists. */
    static void requireSimulationMachine(Configuration c) {
        Machine m = c.getMachine();
        if (!SimGuard.isLoopbackOnly(m)) {
            throw new IllegalStateException("simulation-only camera on a non-simulated machine: " + drivers(m));
        }
        File scene = SimGuard.sceneFile(c);
        if (!scene.isFile()) {
            throw new IllegalStateException("simulation-only camera without a sim scene: " + scene + " does not exist");
        }
    }

    static String drivers(Machine m) {
        if (m == null || m.getDrivers() == null || m.getDrivers().isEmpty()) {
            return "the machine has no driver";
        }
        List<String> out = new ArrayList<>();
        for (Driver d : m.getDrivers()) {
            if (d instanceof GcodeDriver) {
                GcodeDriver g = (GcodeDriver) d;
                out.add("driver " + g.getName() + " uses " + (g.getCommunicationsType() == CommunicationsType.tcp
                        ? "tcp to " + g.getIpAddress() : String.valueOf(g.getCommunicationsType())));
            }
            else {
                out.add("driver " + d.getName() + " is a " + d.getClass().getSimpleName());
            }
        }
        return String.join(", ", out);
    }

    @Override
    public synchronized BufferedImage internalCapture() {
        if (!ensureOpen()) {
            return null;
        }
        requireFlipsOnly();
        int w = getViewWidth();
        int h = getViewHeight();
        Configuration c = Configuration.get();
        if (!SimGuard.isSimulated(c.getMachine())) {
            return preFlip(SimBoardRenderer.blank(w, h, UNCONFIRMED));
        }
        if (scenes == null) {
            scenes = SimScene.Source.forConfiguration(c);
        }
        SimScene sc = scenes.get();
        if (sc == null) {
            return preFlip(SimBoardRenderer.blank(w, h, NO_SCENE));
        }
        Location loc = SimulationModeMachine.getSimulatedPhysicalLocation(this, getLooking(), false)
                .convertToUnits(LengthUnit.Millimeters);
        Location upp = getUnitsPerPixel().convertToUnits(LengthUnit.Millimeters);
        Board board = sc.findBoard(c);
        List<Object> key = Arrays.asList(sc, board, loc.getX(), loc.getY(), w, h, upp.getX(), upp.getY(), isFlipX(),
                isFlipY(), SimPaste.version());
        long now = System.currentTimeMillis();
        if (cachedFrame == null || !key.equals(cachedKey) || now - cachedAtMs > CACHE_MS) {
            cachedFrame = preFlip(SimBoardRenderer.render(sc, board, loc.getX(), loc.getY(), w, h, upp.getX(),
                    upp.getY()));
            cachedKey = key;
            cachedAtMs = now;
        }
        return ImageUtils.clone(cachedFrame);
    }

    /**
     * Only the flips may be set: everything else ReferenceCamera.transformImage does would move the
     * drawing against the configured scale.
     */
    void requireFlipsOnly() {
        if (isRotated() || isOffset() || isCropped() || isScaled() || isDeinterlaced() || isUndistorted()
                || getAdvancedCalibration().isEnabled()
                || getAdvancedCalibration().isOverridingOldTransformsAndDistortionCorrectionSettings()) {
            throw new IllegalStateException("SimBoardCamera supports flips only (camera " + getName()
                    + " has a rotation, offset, crop, scale, deinterlace or lens calibration set)");
        }
    }

    /** Applies the configured flips (flipX: about the x axis, i.e. rows; flipY: columns), as transformImage does. */
    BufferedImage preFlip(BufferedImage img) {
        if (!isFlipX() && !isFlipY()) {
            return img;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] src = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        byte[] dst = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < h; y++) {
            int sy = isFlipX() ? h - 1 - y : y;
            for (int x = 0; x < w; x++) {
                int sx = isFlipY() ? w - 1 - x : x;
                int s = (sy * w + sx) * 3;
                int d = (y * w + x) * 3;
                dst[d] = src[s];
                dst[d + 1] = src[s + 1];
                dst[d + 2] = src[s + 2];
            }
        }
        return out;
    }
}
