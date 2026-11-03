package org.openpnp.instancelabs.vision;

import java.awt.image.BufferedImage;

import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;

/** Grabs a settled frame from an OpenPnP camera together with its scale in mm per pixel. */
public final class CameraCapture {
    private CameraCapture() {}

    /** An image plus its units-per-pixel converted to millimetres. */
    public static final class Capture {
        public final BufferedImage image;
        public final double mmPerPxX, mmPerPxY;

        public Capture(BufferedImage image, double mmPerPxX, double mmPerPxY) {
            this.image = image;
            this.mmPerPxX = mmPerPxX;
            this.mmPerPxY = mmPerPxY;
        }
    }

    /**
     * settle, then capture from an OpenPnP Camera (uses camera.settleAndCapture()); returns image
     * + units-per-pixel in mm (camera.getUnitsPerPixel(), the calibrated scale).
     */
    public static Capture capture(Camera cam) throws Exception {
        BufferedImage image = cam.settleAndCapture();
        return of(cam, image, cam.getUnitsPerPixel());
    }

    /**
     * Same, but with the scale for an object at height z (camera.getUnitsPerPixel(z); null means
     * the camera's default working plane). Use this when the imaged surface Z is known.
     */
    public static Capture capture(Camera cam, Length z) throws Exception {
        BufferedImage image = cam.settleAndCapture();
        return of(cam, image, cam.getUnitsPerPixel(z));
    }

    /**
     * Same as {@link #capture(Camera, Length)}, with the camera's light actuator on
     * (camera.lightSettleAndCapture()).
     */
    public static Capture captureLit(Camera cam, Length z) throws Exception {
        BufferedImage image = cam.lightSettleAndCapture();
        return of(cam, image, cam.getUnitsPerPixel(z));
    }

    private static Capture of(Camera cam, BufferedImage image, Location upp) throws Exception {
        if (image == null) {
            throw new Exception("Camera " + cam.getName() + " returned no image");
        }
        if (upp == null) {
            throw new IllegalStateException("Camera " + cam.getName() + " has no units per pixel");
        }
        Location mm = upp.convertToUnits(LengthUnit.Millimeters);
        double x = Math.abs(mm.getX());
        double y = Math.abs(mm.getY());
        if (!(x > 0) || !(y > 0) || Double.isInfinite(x) || Double.isInfinite(y)) {
            throw new IllegalStateException(
                    "Camera " + cam.getName() + " units per pixel are not calibrated: " + upp);
        }
        return new Capture(image, x, y);
    }
}
