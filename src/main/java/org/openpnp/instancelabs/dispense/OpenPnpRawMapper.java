package org.openpnp.instancelabs.dispense;

import java.lang.reflect.Field;

import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.machine.reference.driver.AbstractMotionPlanner;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Axis;
import org.openpnp.spi.ControllerAxis;
import org.openpnp.spi.Driver;
import org.openpnp.spi.MotionPlanner;
import org.openpnp.spi.Nozzle;
import org.pmw.tinylog.Logger;

/**
 * {@link RawMapper} backed by OpenPnP's own transform chain, i.e. exactly what
 * AbstractHeadMountable.moveTo() does before handing the move to the motion planner:
 * {@code nozzle.toRaw(nozzle.toHeadLocation(location, currentLocation))} (head offsets, runout /
 * rotation-mode compensation, transformed axes such as the negating see-saw mapping of z2 onto z1,
 * linear/non-squareness transforms). Soft limits are checked with the motion planner's own
 * isValidLocation(). Backlash is reproduced by {@link BacklashEmulator} from the axes' settings.
 */
public class OpenPnpRawMapper implements RawMapper {
    private final ReferenceMachine machine;
    private final Nozzle nozzle;
    private final ReferenceControllerAxis axX, axY, axZ, axB;
    private final GcodeDriver driver;

    public OpenPnpRawMapper(ReferenceMachine machine, Nozzle nozzle, String bAxisName) throws Exception {
        this(machine, nozzle, bAxisName, true);
    }

    /**
     * The mapper of the N1 vacuum probe: the same X/Y/Z and driver checks, but N1's rotation may map
     * to its own axis (A); B stays the configured auger axis (for M18 B and {@link #resync}). N1's Z
     * must be untransformed ({@link #probeNozzleProblem}).
     */
    public static OpenPnpRawMapper forProbeNozzle(ReferenceMachine machine, Nozzle n, String bAxisName)
            throws Exception {
        return new OpenPnpRawMapper(machine, n, bAxisName, false);
    }

    private OpenPnpRawMapper(ReferenceMachine machine, Nozzle nozzle, String bAxisName, boolean requireRotationOnB)
            throws Exception {
        this.machine = machine;
        this.nozzle = nozzle;
        Location here = nozzle.getLocation();
        AxesLocation raw = nozzle.toRaw(nozzle.toHeadLocation(here));
        ReferenceControllerAxis x = null, y = null, z = null, b = null;
        for (ControllerAxis a : raw.getControllerAxes()) {
            if (!(a instanceof ReferenceControllerAxis)) {
                throw new Exception("Dispenser axis " + a.getName() + " is not a ReferenceControllerAxis");
            }
            ReferenceControllerAxis ra = (ReferenceControllerAxis) a;
            switch (a.getType()) {
                case X:
                    x = check(x, ra);
                    break;
                case Y:
                    y = check(y, ra);
                    break;
                case Z:
                    z = check(z, ra);
                    break;
                case Rotation:
                    b = check(b, ra);
                    break;
                default:
                    break;
            }
        }
        if (x == null || y == null || z == null) {
            throw new Exception("Nozzle " + nozzle.getName() + " does not map to one X, Y and Z controller axis");
        }
        Axis named = null;
        for (Axis a : machine.getAxes()) {
            if (a.getName().equals(bAxisName)) {
                named = a;
            }
        }
        if (!(named instanceof ReferenceControllerAxis)) {
            throw new Exception("Auger axis '" + bAxisName + "' is not a controller axis");
        }
        if (requireRotationOnB && b != null && b != named) {
            throw new Exception("Nozzle " + nozzle.getName() + " rotation maps to axis " + b.getName()
                    + ", not the configured auger axis " + bAxisName);
        }
        b = (ReferenceControllerAxis) named;
        Driver d = x.getDriver();
        if (!(d instanceof GcodeDriver) || y.getDriver() != d || z.getDriver() != d || b.getDriver() != d) {
            throw new Exception("X, Y, Z and B of " + nozzle.getName() + " must be on the same GcodeDriver");
        }
        if (((GcodeDriver) d).getUnits() != LengthUnit.Millimeters) {
            throw new Exception("GcodeDriver units must be millimeters for raw streaming");
        }
        this.axX = x;
        this.axY = y;
        this.axZ = z;
        this.axB = b;
        this.driver = (GcodeDriver) d;
    }

    private static ReferenceControllerAxis check(ReferenceControllerAxis prev, ReferenceControllerAxis a)
            throws Exception {
        if (prev != null && prev != a) {
            throw new Exception("Ambiguous raw axes " + prev.getName() + " / " + a.getName());
        }
        return a;
    }

    public GcodeDriver getDriver() {
        return driver;
    }

    /**
     * Why this mapper's nozzle cannot be the N1 vacuum probe, or null: it must not be the dispenser
     * nozzle, and its OpenPnP Z must be its raw Z (the probe sends N1 Z as raw Z, as the bench does).
     */
    public String probeNozzleProblem(Nozzle dispenser) {
        if (nozzle == dispenser) {
            return "PROBE: '" + nozzle.getName() + "' is the dispenser nozzle; the vacuum probe needs N1";
        }
        try {
            double[] n = currentNozzleLocation();
            if (Math.abs(toRaw(n[0], n[1], 5).z - 5) > 1e-6 || Math.abs(toRaw(n[0], n[1], 10).z - 10) > 1e-6) {
                return "PROBE: " + nozzle.getName() + "'s Z is transformed in OpenPnP; the probe needs "
                        + nozzle.getName() + " Z = raw Z";
            }
        }
        catch (Exception e) {
            return "PROBE: " + nozzle.getName() + "'s Z cannot be mapped to the controller: " + e.getMessage();
        }
        return null;
    }

    public Nozzle getNozzle() {
        return nozzle;
    }

    public ReferenceControllerAxis[] xyzAxes() {
        return new ReferenceControllerAxis[] {axX, axY, axZ};
    }

    public ReferenceControllerAxis bAxis() {
        return axB;
    }

    @Override
    public String letterX() {
        return axX.getLetter();
    }

    @Override
    public String letterY() {
        return axY.getLetter();
    }

    @Override
    public String letterZ() {
        return axZ.getLetter();
    }

    @Override
    public String letterB() {
        return axB.getLetter();
    }

    @Override
    public RawPoint toRaw(double x, double y, double z) throws Exception {
        Location current = nozzle.getLocation();
        Location target = new Location(LengthUnit.Millimeters, x, y, z, current.getRotation());
        Location head = nozzle.toHeadLocation(target);
        AxesLocation raw = nozzle.toRaw(head);
        MotionPlanner mp = machine.getMotionPlanner();
        if (!mp.isValidLocation(nozzle, raw)) {
            throw new Exception(String.format(java.util.Locale.ROOT,
                    "%s at (%.3f, %.3f, %.3f) is outside the soft limits (raw %s)", nozzle.getName(), x, y, z, raw));
        }
        return new RawPoint(raw.getCoordinate(axX, LengthUnit.Millimeters),
                raw.getCoordinate(axY, LengthUnit.Millimeters), raw.getCoordinate(axZ, LengthUnit.Millimeters));
    }

    @Override
    public AxisBacklash[] backlash() {
        return new AxisBacklash[] {AxisBacklash.of(axX), AxisBacklash.of(axY), AxisBacklash.of(axZ)};
    }

    @Override
    public double[] currentPlanned() {
        return new double[] {mm(axX.getLengthCoordinate()), mm(axY.getLengthCoordinate()),
                mm(axZ.getLengthCoordinate())};
    }

    @Override
    public double[] currentDriver() {
        return new double[] {mm(axX.getDriverLengthCoordinate()), mm(axY.getDriverLengthCoordinate()),
                mm(axZ.getDriverLengthCoordinate())};
    }

    @Override
    public double currentB() {
        return axB.getDriverCoordinate();
    }

    @Override
    public double[] currentNozzleLocation() {
        Location l = nozzle.getLocation().convertToUnits(LengthUnit.Millimeters);
        return new double[] {l.getX(), l.getY(), l.getZ()};
    }

    @Override
    public double[] zSafeZoneRaw() {
        boolean lo = axZ.isSafeZoneLowEnabled(), hi = axZ.isSafeZoneHighEnabled();
        if (!lo && !hi) {
            return null;
        }
        return new double[] {lo ? mm(axZ.getSafeZoneLow()) : Double.NaN, hi ? mm(axZ.getSafeZoneHigh()) : Double.NaN};
    }

    private static double mm(Length l) {
        return l.convertToUnits(LengthUnit.Millimeters).getValue();
    }

    /**
     * Tells OpenPnP where the streamed block left the axes: planned coordinates (without backlash
     * offset) and driver coordinates (with), plus the planner's private directional backlash state,
     * and B. Call only once the controller has completed the block (after M400).
     */
    public void resync(double[] planned, double[] driverCoords, double b) {
        ReferenceControllerAxis[] xyz = xyzAxes();
        AxesLocation offsets = new AxesLocation();
        for (int i = 0; i < 3; i++) {
            xyz[i].setLengthCoordinate(new Length(planned[i], LengthUnit.Millimeters));
            xyz[i].setDriverLengthCoordinate(new Length(driverCoords[i], LengthUnit.Millimeters));
            offsets = offsets.put(new AxesLocation(xyz[i], driverCoords[i] - planned[i]));
        }
        axB.setLengthCoordinate(new Length(b, LengthUnit.Millimeters));
        axB.setDriverCoordinate(b);
        setPlannerBacklashOffsets(offsets);
        if (nozzle.getHead() != null) {
            machine.fireMachineHeadActivity(nozzle.getHead());
        }
    }

    /**
     * AbstractMotionPlanner keeps the last directional backlash offsets in a protected field; it is
     * used to derive the start of the next move. Update it so OpenPnP's next move starts from the
     * truth. Best effort: logs if the field is not accessible.
     */
    private void setPlannerBacklashOffsets(AxesLocation offsets) {
        MotionPlanner mp = machine.getMotionPlanner();
        if (!(mp instanceof AbstractMotionPlanner)) {
            return;
        }
        try {
            Field f = AbstractMotionPlanner.class.getDeclaredField("lastDirectionalBacklashOffset");
            f.setAccessible(true);
            AxesLocation cur = (AxesLocation) f.get(mp);
            f.set(mp, (cur == null ? new AxesLocation() : cur).put(offsets));
        }
        catch (Exception e) {
            Logger.warn(e, "Could not update the motion planner's backlash state after streaming");
        }
    }
}
