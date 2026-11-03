package org.openpnp.instancelabs.dispense;

import java.util.List;

import org.openpnp.instancelabs.vision.CameraCapture;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.instancelabs.vision.DotInspector;
import org.openpnp.instancelabs.vision.DotInspectorSettings;
import org.openpnp.instancelabs.vision.SpotDot;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.util.MovableUtils;

/** {@link DotImager} using the down-looking camera and the vision contract. */
public class OpenPnpDotImager implements DotImager {
    /** The largest blob {@link #inspectAll} keeps (a string or two dots joined by a thread), mm. */
    static final double INSPECT_ALL_MAX_DIAMETER_MM = 3.0;

    private final Camera camera;
    private final DotInspectorSettings settings;

    public OpenPnpDotImager(Camera camera, DotInspectorSettings settings) {
        this.camera = camera;
        this.settings = settings == null ? new DotInspectorSettings() : settings;
    }

    @Override
    public boolean isAvailable() {
        return camera != null;
    }

    @Override
    public Frame capture(double xMm, double yMm) throws Exception {
        Location cur = camera.getLocation().convertToUnits(LengthUnit.Millimeters);
        MovableUtils.moveToLocationAtSafeZ(camera,
                new Location(LengthUnit.Millimeters, xMm, yMm, cur.getZ(), cur.getRotation()));
        CameraCapture.Capture c = CameraCapture.capture(camera);
        return new Frame(c.image, c.mmPerPxX, c.mmPerPxY, xMm, yMm);
    }

    /** The same move, then OpenPnP's light-settle-capture (the light actuator of the camera on). */
    @Override
    public Frame captureLit(double xMm, double yMm, Double surfaceZMm) throws Exception {
        Location cur = camera.getLocation().convertToUnits(LengthUnit.Millimeters);
        MovableUtils.moveToLocationAtSafeZ(camera,
                new Location(LengthUnit.Millimeters, xMm, yMm, cur.getZ(), cur.getRotation()));
        CameraCapture.Capture c = CameraCapture.captureLit(camera,
                surfaceZMm == null ? null : new Length(surfaceZMm, LengthUnit.Millimeters));
        return new Frame(c.image, c.mmPerPxX, c.mmPerPxY, xMm, yMm);
    }

    @Override
    public List<Dot> inspect(Frame before, Frame after) throws Exception {
        return new DotInspector(settings).inspect(before.image, after.image, after.mmPerPxX, after.mmPerPxY);
    }

    /** Every blob up to 3 mm, any circularity (strings and threads are the calibration's to classify). */
    @Override
    public List<Dot> inspectAll(Frame before, Frame after) throws Exception {
        DotInspectorSettings all = settings.copy();
        all.minCircularity = 0;
        all.maxDiameterMm = Math.max(all.maxDiameterMm, INSPECT_ALL_MAX_DIAMETER_MM);
        return new DotInspector(all).inspect(before.image, after.image, after.mmPerPxX, after.mmPerPxY);
    }

    /** The dot at each known spot, strings cut off ({@link DotInspector#inspectSpots}). */
    @Override
    public List<SpotDot> inspectSpots(Frame before, Frame after, List<double[]> spotsMm, double cutMm,
            double radiusMm) throws Exception {
        return new DotInspector(settings).inspectSpots(before.image, after.image, after.mmPerPxX, after.mmPerPxY,
                spotsMm, cutMm, radiusMm);
    }

    /** The camera's frame in mm (its size in pixels times its units per pixel), or null when it cannot say. */
    @Override
    public double[] fieldOfViewMm() {
        try {
            Location upp = camera.getUnitsPerPixel().convertToUnits(LengthUnit.Millimeters);
            int w = camera.getWidth();
            int h = camera.getHeight();
            if (w <= 0 || h <= 0 || !(upp.getX() > 0) || !(upp.getY() > 0)) {
                return null;
            }
            return new double[] {w * upp.getX(), h * upp.getY()};
        }
        catch (Exception e) {
            return null;
        }
    }
}
