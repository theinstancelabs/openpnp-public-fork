package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Tip;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/**
 * Tip setups (§22.4, §22.8): the picture of the mounted tip from below. N2 goes to the bottom camera's X/Y at the
 * balanced travel height (never lower); the task refuses outside N2's safe zone, unhomed, under the pressure hold and
 * for a setup that is not mounted; a STOP during the move or after the capture, or a needle change meanwhile, leaves
 * no photo; else the picture is stored with its row and links.
 */
public class TipPictureTaskTest {
    static final double CX = 217.13, CY = 152.32;

    /** A camera looking up at (217.13, 152.32) that draws a ring and notes where N2 was when it captured. */
    static final class RingCamera implements TipCamera {
        final SimMachine sim;
        volatile Runnable onCapture;
        volatile Runnable onLocation;
        volatile Location n2AtCapture;

        RingCamera(SimMachine sim) {
            this.sim = sim;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public double[] locationMm() {
            Runnable r = onLocation;
            if (r != null) {
                r.run();
            }
            return new double[] {CX, CY, 20};
        }

        @Override
        public DotImager.Frame capture() {
            n2AtCapture = sim.n2.getLocation().convertToUnits(LengthUnit.Millimeters);
            BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(25, 25, 25));
            g.fillRect(0, 0, 320, 240);
            g.setColor(Color.ORANGE);
            g.setStroke(new BasicStroke(6));
            g.drawOval(110, 70, 100, 100);
            g.dispose();
            Runnable r = onCapture;
            if (r != null) {
                r.run();
            }
            return new DotImager.Frame(img, 0.02, 0.02, CX, CY);
        }
    }

    static TipPictureRequest request(DispenseService svc) {
        TipPictureRequest req = new TipPictureRequest();
        DispenseConfig c = svc.getConfig();
        req.setupKey = c.tip.key;
        req.needleSinceMs = c.needleChangedAtMs;
        req.stopEpoch = svc.stopEpoch();
        req.operator = "tester";
        return req;
    }

    private static final Pattern Z = Pattern.compile("\\bZ(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern X = Pattern.compile("\\bX(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern Y = Pattern.compile("\\bY(-?\\d+(?:\\.\\d+)?)");

    static Double word(Pattern p, String line) {
        Matcher m = p.matcher(line);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    @Test
    public void n2GoesToTheCameraAtTravelHeightAndThePictureIsStoredWithItsLinks() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            RingCamera cam = new RingCamera(f.sim);
            svc.setTipCamera(cam);
            DispenseConfig c = svc.getConfig();
            PadCalibrationRecord rec = new PadCalibrationRecord();
            rec.jobId = "job-1";
            rec.typeKey = "chip-0603";
            rec.outcome = PadCalibrationRecord.PROVISIONAL;
            rec.setupKey = c.tip.key;
            rec.needleSinceMs = c.needleChangedAtMs;
            rec = svc.getDb().insertPadCalibration(rec);
            RawPoint raw = new OpenPnpRawMapper(f.sim.machine, f.sim.n2, c.bAxisName).toRaw(CX, CY,
                    c.travelSafeZMm);
            f.sim.server.mark();
            TipPictureRequest req = request(svc);
            req.calibrationIds.add(rec.id);
            TipPictureResult res = svc.takeTipPicture(req).get(60, TimeUnit.SECONDS);
            assertTrue(res.ok, res.message);
            assertEquals(RunResult.Status.OK, res.run.status);
            assertEquals("tip-picture", res.run.kind);
            // N2 was over the camera at the travel height when the frame was taken
            assertEquals(CX, cam.n2AtCapture.getX(), 1e-3);
            assertEquals(CY, cam.n2AtCapture.getY(), 1e-3);
            assertEquals(c.travelSafeZMm, cam.n2AtCapture.getZ(), 1e-3);
            List<String> lines = f.sim.server.sinceMark();
            boolean there = false;
            for (String l : lines) {
                Double x = word(X, l), y = word(Y, l), z = word(Z, l);
                if ((l.startsWith("G0") || l.startsWith("G1")) && z != null) {
                    // raw Z = 63 - N2 Z on the see-saw: N2 never below the travel height
                    assertTrue(z <= raw.z + 1e-3, "N2 below the travel height: " + l);
                }
                there |= x != null && y != null && Math.abs(x - raw.x) < 1e-3 && Math.abs(y - raw.y) < 1e-3;
            }
            assertTrue(there, "a move to the camera's X/Y: " + lines);
            assertFalse(lines.stream().anyMatch(l -> l.startsWith("M106")), "the pneumatics stay off");
            Photo p = res.photo;
            assertNotNull(p);
            assertEquals(Photo.KIND_TIP, p.kind);
            assertEquals(Photo.SOURCE_BOTTOM_CAMERA, p.source);
            assertEquals(Tip.SEED_KEY, p.setupKey);
            assertEquals(c.needleChangedAtMs, p.needleSinceMs);
            assertEquals(0.02, p.mmPerPx, 0);
            assertTrue(p.caption.matches("From below at \\d\\d:\\d\\d, at travel height \\(may be soft\\): the "
                    + Pattern.quote(Tip.SEED_NAME)), p.caption);
            assertEquals(java.util.Collections.singletonList(rec.id), p.links);
            assertEquals(Photo.ROLE_TIP, svc.getDb().photoLinks(p.id, rec.id).get(0).role);
            assertTrue(svc.getPhotoStore().file(p).isFile());
            assertFalse(svc.getDb().query("SELECT * FROM events WHERE code = 'TIP' AND message = 'picture " + p.id
                    + " of the " + Tip.SEED_NAME + "'").isEmpty());
        }
    }

    @Test
    public void theRefusals() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            svc.setTipCamera(new RingCamera(f.sim));
            // a setup that is not mounted
            TipPictureRequest other = request(svc);
            other.setupKey = "25g-straight";
            f.add25();
            EngineRefusal e = assertThrows(EngineRefusal.class, () -> svc.takeTipPicture(other));
            assertEquals("Only the mounted tip can be photographed: mount the 25G straight first.", e.getMessage());
            // the pressure hold
            svc.setPressureHoldCheck(() -> true);
            e = assertThrows(EngineRefusal.class, () -> svc.takeTipPicture(request(svc)));
            assertEquals("Switch the pressure off first: the head moves to the bottom camera.", e.getMessage());
            svc.setPressureHoldCheck(null);
            // unhomed: it never homes by itself
            f.sim.machine.setHomed(false);
            TipPictureResult r = svc.takeTipPicture(request(svc)).get(60, TimeUnit.SECONDS);
            assertFalse(r.ok);
            assertEquals(RunResult.Status.REFUSED, r.run.status);
            assertEquals("Home first: the head moves to the bottom camera.", r.run.message);
            f.sim.machine.setHomed(true);
            // 31.5 outside N2's safe zone
            ReferenceControllerAxis z = new OpenPnpRawMapper(f.sim.machine, f.sim.n2, "b").xyzAxes()[2];
            boolean was = z.isSafeZoneHighEnabled();
            Length high = z.getSafeZoneHigh();
            z.setSafeZoneHigh(new Length(20, LengthUnit.Millimeters));
            z.setSafeZoneHighEnabled(true);
            r = svc.takeTipPicture(request(svc)).get(60, TimeUnit.SECONDS);
            assertFalse(r.ok);
            assertTrue(r.run.message.startsWith("N2's safe Z zone ("), r.run.message);
            assertTrue(r.run.message.endsWith("does not hold the balanced travel height 31.5 mm: the picture would move "
                    + "N1 down."), r.run.message);
            z.setSafeZoneHigh(high);
            z.setSafeZoneHighEnabled(was);
            // no camera looking up
            svc.setTipCamera(new TipCamera() {
                @Override
                public boolean isAvailable() {
                    return false;
                }

                @Override
                public double[] locationMm() {
                    return null;
                }

                @Override
                public DotImager.Frame capture() {
                    return null;
                }
            });
            e = assertThrows(EngineRefusal.class, () -> svc.takeTipPicture(request(svc)));
            assertEquals("No bottom camera: OpenPnP has no camera looking up.", e.getMessage());
            assertTrue(svc.getDb().photos(null, null, null, null, true, 10).isEmpty());
        }
    }

    @Test
    public void aStopOrANeedleChangeLeavesNoPhoto() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            RingCamera cam = new RingCamera(f.sim);
            svc.setTipCamera(cam);
            // STOP as the head moves to the camera
            cam.onLocation = () -> svc.abort("STOP pressed");
            TipPictureResult r = svc.takeTipPicture(request(svc)).get(60, TimeUnit.SECONDS);
            assertFalse(r.ok);
            assertNull(r.photo);
            assertEquals(RunResult.Status.ABORTED, r.run.status, r.run.toString());
            cam.onLocation = null;
            // STOP right after the capture
            cam.onCapture = () -> svc.abort("STOP pressed");
            r = svc.takeTipPicture(request(svc)).get(60, TimeUnit.SECONDS);
            assertFalse(r.ok);
            assertNull(r.photo);
            // a needle change while the picture is taken: no photo of the needle that came off
            cam.onCapture = () -> {
                try {
                    svc.recordNeedleChange("swapped meanwhile", null);
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            };
            r = svc.takeTipPicture(request(svc)).get(60, TimeUnit.SECONDS);
            assertFalse(r.ok);
            assertNull(r.photo);
            assertTrue(svc.getDb().photos(null, null, null, null, true, 10).isEmpty());
        }
    }
}
