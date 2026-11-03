package org.openpnp.instancelabs.dispense;

import org.openpnp.instancelabs.vision.CameraCapture;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;

/**
 * The tip camera of the machine (tip setups design §22.4): OpenPnP's first camera looking up, at its
 * own location, captured with OpenPnP's light-settle-capture ({@code CameraCapture.captureLit(camera,
 * null)}), as bottom vision does.
 */
public final class OpenPnpTipCamera implements TipCamera {
    private final Machine machine;

    /** The tip camera of this machine (its cameras are looked up at every call: a configuration may change them). */
    public OpenPnpTipCamera(Machine machine) {
        this.machine = machine;
    }

    /** The first camera looking up: a machine camera, else one on a head; null when there is none. */
    Camera camera() {
        if (machine == null) {
            return null;
        }
        for (Camera c : machine.getCameras()) {
            if (c.getLooking() == Camera.Looking.Up) {
                return c;
            }
        }
        for (Head h : machine.getHeads()) {
            for (Camera c : h.getCameras()) {
                if (c.getLooking() == Camera.Looking.Up) {
                    return c;
                }
            }
        }
        return null;
    }

    /** True when the machine has a camera looking up. */
    @Override
    public boolean isAvailable() {
        return camera() != null;
    }

    /** The up-looking camera's location in mm {x, y, z}. */
    @Override
    public double[] locationMm() {
        Camera c = camera();
        if (c == null) {
            throw new IllegalStateException("No bottom camera: OpenPnP has no camera looking up.");
        }
        Location l = c.getLocation().convertToUnits(LengthUnit.Millimeters);
        return new double[] {l.getX(), l.getY(), l.getZ()};
    }

    /** A lit frame of the up-looking camera with its units per pixel at the focal plane. */
    @Override
    public DotImager.Frame capture() throws Exception {
        Camera c = camera();
        if (c == null) {
            throw new IllegalStateException("No bottom camera: OpenPnP has no camera looking up.");
        }
        CameraCapture.Capture cap = CameraCapture.captureLit(c, null);
        double[] at = locationMm();
        return new DotImager.Frame(cap.image, cap.mmPerPxX, cap.mmPerPxY, at[0], at[1]);
    }
}
