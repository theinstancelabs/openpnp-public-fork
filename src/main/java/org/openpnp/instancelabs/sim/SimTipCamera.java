package org.openpnp.instancelabs.sim;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;

import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.TipSetup;
import org.openpnp.machine.reference.SimulationModeMachine;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.Nozzle;
import org.pmw.tinylog.Logger;

/**
 * The SIM bottom camera (tip setups design §22.8): installed in a SIM session with {@code --xml-set
 * ".//camera[@looking='Up']@class=org.openpnp.instancelabs.sim.SimTipCamera"}, as the top camera is.
 * Its frame is {@value #WIDTH_PX} × {@value #HEIGHT_PX} px at {@value #MM_PER_PX} mm/px, dark grey;
 * when N2 is in view it draws the physical tip end-on (read in-process from the engine): a grey disc of
 * its OD with a black bore of its ID, an amber ring for a tapered plastic tip, a grey capsule toward
 * the hub for a bent one, blurred by its height above the focal plane, tagged {@value #TAG}. With N2
 * elsewhere the frame is empty, so a test proves the head went there.
 *
 * <p>Like {@link SimBoardCamera} it opens only on a simulated machine (every driver loopback TCP and the sim
 * scene present) and draws the tip only while the controller is confirmed to be OpenPnP's simulator.</p>
 */
public class SimTipCamera extends SimulatedUpCamera {
    public static final int WIDTH_PX = 1280, HEIGHT_PX = 960;
    public static final double MM_PER_PX = 0.02;
    /** The frame's grey (BGR 25, 25, 25). */
    public static final int BACKGROUND = 25;
    public static final String TAG = "SIM bottom camera";
    /** The amber ring of a tapered plastic tip (mm across) and a bent tube's length when not recorded (mm). */
    static final double TAPER_RING_MM = 2.5, BENT_DEFAULT_MM = 6;
    static final Color AMBER = new Color(255, 176, 0), GREY = new Color(150, 150, 150);
    /** The running configuration's scene (its {@code physicalTip}), read again when the file changes. */
    private transient SimScene.Source scenes;

    public SimTipCamera() {
        super();
        width = WIDTH_PX;
        height = HEIGHT_PX;
        setUnitsPerPixel(new Location(LengthUnit.Millimeters, MM_PER_PX, MM_PER_PX, 0, 0));
    }

    @Override
    public synchronized void open() throws Exception {
        SimBoardCamera.requireSimulationMachine(Configuration.get());
        width = WIDTH_PX;
        height = HEIGHT_PX;
        super.open();
    }

    /** The frame described above (the physical tip when N2 is over the camera, else empty). */
    @Override
    public BufferedImage internalCapture() {
        Machine m = Configuration.get() == null ? null : Configuration.get().getMachine();
        if (m == null || !SimGuard.isSimulated(m)) {
            return render(null, 0, 0, 0);
        }
        try {
            Nozzle needle = needle(m);
            if (needle == null) {
                return render(null, 0, 0, 0);
            }
            // where OpenPnP put N2 (its planned location): on the simulator that is where the head is once still
            Location n = needle.getLocation().convertToUnits(LengthUnit.Millimeters);
            Location cam = getSimulatedLocation().convertToUnits(LengthUnit.Millimeters);
            double dx = n.getX() - cam.getX(), dy = n.getY() - cam.getY();
            if (!inView(dx, dy)) {
                // another nozzle over the camera (N1 at its nozzle tip calibration while homing): OpenPnP's simulated
                // up camera draws it, as the session's bottom camera would without the tip camera
                if (otherNozzleInView(m, needle, cam)) {
                    return super.internalCapture();
                }
                return render(null, 0, 0, 0); // N2 elsewhere: nothing to look up (no scene or engine read)
            }
            return render(physicalTip(scene()), dx, dy, n.getZ() - cam.getZ());
        }
        catch (RuntimeException e) {
            Logger.warn(e, "sim bottom camera: no tip drawn");
            return render(null, 0, 0, 0);
        }
    }

    /** A nozzle other than the needle whose simulated tip lies in this camera's frame. */
    private boolean otherNozzleInView(Machine m, Nozzle needle, Location cam) {
        for (Head h : m.getHeads()) {
            for (Nozzle n : h.getNozzles()) {
                if (n == needle) {
                    continue;
                }
                Location l = SimulationModeMachine.getSimulatedPhysicalLocation(n, getLooking(), true)
                        .convertToUnits(LengthUnit.Millimeters);
                if (inView(l.getX() - cam.getX(), l.getY() - cam.getY())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The dispense service's nozzle (N2) on any head, or null. */
    static Nozzle needle(Machine m) {
        DispenseService svc = DispenseService.get();
        String name = svc == null ? "N2" : svc.getConfig().nozzle;
        for (Head h : m.getHeads()) {
            Nozzle n = h.getNozzleByName(name);
            if (n != null) {
                return n;
            }
        }
        return null;
    }

    /** The scene of the running configuration, or null. */
    private synchronized SimScene scene() {
        try {
            if (scenes == null) {
                scenes = SimScene.Source.forConfiguration(Configuration.get());
            }
            return scenes.get();
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** The tip on the SIM head: the scene's {@code physicalTip}, else the mounted setup; null without the engine. */
    static TipSetup physicalTip(SimScene sc) {
        DispenseService svc = DispenseService.get();
        if (svc == null || svc.getDb() == null) {
            return null;
        }
        String key = sc == null ? null : sc.physicalTip;
        try {
            return svc.getDb().currentTipSetup(key != null ? key : svc.mountedKey());
        }
        catch (Exception e) {
            return null;
        }
    }

    /**
     * The frame with the tip end-on at (dx, dy) mm from the camera's centre and {@code heightMm} above its focal
     * plane (null tip, or outside the frame: the empty frame), tagged {@value #TAG}.
     */
    public static BufferedImage render(TipSetup tip, double dxMm, double dyMm, double heightMm) {
        BufferedImage img = new BufferedImage(WIDTH_PX, HEIGHT_PX, BufferedImage.TYPE_INT_RGB);
        boolean drawn;
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(BACKGROUND, BACKGROUND, BACKGROUND));
            g.fillRect(0, 0, WIDTH_PX, HEIGHT_PX);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawn = tip != null && inView(dxMm, dyMm);
            if (drawn) {
                double cx = WIDTH_PX / 2.0 + dxMm / MM_PER_PX, cy = HEIGHT_PX / 2.0 - dyMm / MM_PER_PX;
                double od = (tip.odMm != null ? tip.odMm : 0.72) / MM_PER_PX;
                double id = (tip.idMm != null ? tip.idMm : 0.41) / MM_PER_PX;
                if (TipSetup.FORM_BENT.equals(tip.form)) {
                    double len = (tip.lengthMm != null && tip.bendDeg != null
                            ? tip.lengthMm * Math.sin(Math.toRadians(tip.bendDeg)) : BENT_DEFAULT_MM) / MM_PER_PX;
                    double[] u = hubDirection(tip.bendToward);
                    g.setColor(GREY);
                    g.setStroke(new BasicStroke((float) od, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new Line2D.Double(cx, cy, cx + u[0] * len, cy - u[1] * len));
                }
                if (TipSetup.TAPER_PLASTIC.equals(tip.taper)) {
                    double ring = TAPER_RING_MM / MM_PER_PX;
                    g.setColor(AMBER);
                    g.fill(new Ellipse2D.Double(cx - ring / 2, cy - ring / 2, ring, ring));
                }
                g.setColor(GREY);
                g.fill(new Ellipse2D.Double(cx - od / 2, cy - od / 2, od, od));
                g.setColor(Color.BLACK);
                g.fill(new Ellipse2D.Double(cx - id / 2, cy - id / 2, id, id));
            }
        }
        finally {
            g.dispose();
        }
        int k = Math.min(15, 1 + 2 * (int) Math.round(Math.abs(heightMm) / 2));
        if (drawn && k > 1) {
            // a box blur of k px, in two passes (k + k instead of k × k operations per pixel)
            float[] w = new float[k];
            java.util.Arrays.fill(w, 1f / k);
            img = new ConvolveOp(new Kernel(k, 1, w), ConvolveOp.EDGE_NO_OP, null).filter(img, null);
            img = new ConvolveOp(new Kernel(1, k, w), ConvolveOp.EDGE_NO_OP, null).filter(img, null);
        }
        Graphics2D t = img.createGraphics();
        try {
            t.setColor(new Color(200, 200, 200));
            t.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20));
            t.drawString(TAG, 12, 28);
        }
        finally {
            t.dispose();
        }
        return img;
    }

    /** Whether a point (dx, dy) mm from the camera's centre is inside the frame. */
    static boolean inView(double dxMm, double dyMm) {
        return Math.abs(dxMm) < WIDTH_PX * MM_PER_PX / 2 && Math.abs(dyMm) < HEIGHT_PX * MM_PER_PX / 2;
    }

    /** From the tip end toward the hub, {x, y} machine axes: opposite to where the bend points (-y when unknown). */
    static double[] hubDirection(String bendToward) {
        if ("+x".equals(bendToward)) {
            return new double[] {-1, 0};
        }
        if ("-x".equals(bendToward)) {
            return new double[] {1, 0};
        }
        if ("+y".equals(bendToward)) {
            return new double[] {0, -1};
        }
        if ("-y".equals(bendToward)) {
            return new double[] {0, 1};
        }
        return new double[] {0, -1};
    }
}
