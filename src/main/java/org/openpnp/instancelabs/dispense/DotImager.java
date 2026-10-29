package org.openpnp.instancelabs.dispense;

import java.awt.image.BufferedImage;
import java.util.List;

import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.instancelabs.vision.PadCleanCheck;

/**
 * Before/after imaging of dots with the top camera. The OpenPnP implementation uses the vision
 * contract (CameraCapture + DotInspector); tests substitute fakes.
 */
public interface DotImager {
    final class Frame {
        public final BufferedImage image;
        public final double mmPerPxX, mmPerPxY;
        /** Machine location the camera was centred on. */
        public final double xMm, yMm;

        public Frame(BufferedImage image, double mmPerPxX, double mmPerPxY, double xMm, double yMm) {
            this.image = image;
            this.mmPerPxX = mmPerPxX;
            this.mmPerPxY = mmPerPxY;
            this.xMm = xMm;
            this.yMm = yMm;
        }
    }

    /** Whether a camera and dot vision are available. */
    boolean isAvailable();

    /** Centres the top camera on machine (x, y) at safe Z, settles and captures. */
    Frame capture(double xMm, double yMm) throws Exception;

    /**
     * As {@link #capture}, with the camera's light on (fiducials, the look before an N1 touch); the
     * scale is taken at {@code surfaceZMm} (null = the camera's default plane).
     */
    default Frame captureLit(double xMm, double yMm, Double surfaceZMm) throws Exception {
        return capture(xMm, yMm);
    }

    /** New blobs between two frames from the same camera position (offsets from frame centre). */
    List<Dot> inspect(Frame before, Frame after) throws Exception;

    /**
     * As {@link #inspect} with every new blob kept, round or not, up to 3 mm (the pad calibration's
     * ladder, design §7.3): strings and big dots are classified by the statistics, not dropped by the
     * inspector. Imagers without such a mode give {@link #inspect}.
     */
    default List<Dot> inspectAll(Frame before, Frame after) throws Exception {
        return inspect(before, after);
    }

    /**
     * The frame size {width, height} in mm at the camera's scale, or null when unknown: the pad
     * calibration then takes one frame per dot instead of tiles.
     */
    default double[] fieldOfViewMm() {
        return null;
    }

    /**
     * Clean-pad guard (I10): share of bright pixels in the box at the frame centre (the camera was
     * centred on the target).
     */
    default double brightFraction(Frame f, DispenseConfig.CleanPadCheck s) {
        PadCleanCheck.Settings ps = new PadCleanCheck.Settings();
        ps.grayLevel = s.grayLevel;
        ps.minBrightFraction = s.minBrightFraction;
        ps.boxWMm = s.boxWMm;
        ps.boxHMm = s.boxHMm;
        return PadCleanCheck.measure(f.image, f.mmPerPxX, f.mmPerPxY, 0, 0, ps).brightFraction;
    }
}
