package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * BR-03: where the valve and the screw may switch on. Allowed: the places (waste, towel, park, cup, coupons, ...), the
 * purge row, the calibration area and every probed surface (engine-probed patches, the blank, the boards of plans).
 * Forbidden, whatever else covers it: the bottom camera (a 15 mm circle) and any configured circle.
 */
public class OutputZonesTest {
    static final double[] CAMERA = {200, 30};

    static DispenseConfig config() {
        DispenseConfig c = new DispenseConfig();
        c.locations.put("waste", new DispenseConfig.NamedLocation(100, 50, 5));
        c.locations.put("towel", new DispenseConfig.NamedLocation(210, 35, 5)); // 11 mm from the camera
        c.purgeRow.x0Mm = 80.0;
        c.purgeRow.y0Mm = 230.0;
        c.purgeRow.pitchXMm = 2.5;
        c.purgeRow.spots = 20;
        DispenseConfig.SurfaceMap patch = new DispenseConfig.SurfaceMap();
        patch.points.add(new DispenseConfig.SurfacePoint(300, 260, 5.6));
        patch.points.add(new DispenseConfig.SurfacePoint(320, 260, 5.6));
        patch.points.add(new DispenseConfig.SurfacePoint(310, 275, 5.6));
        c.surfaces.put("patch1", patch);
        return c;
    }

    @Test
    public void placesRowsAndSurfacesAreAllowed() {
        OutputZones z = OutputZones.of(config(), CAMERA);
        assertNull(z.refusal(100, 50), "the waste");
        assertNull(z.refusal(103, 52), "near the waste, inside its radius");
        assertNull(z.refusal(80 + 19 * 2.5, 230), "the last purge spot");
        assertNull(z.refusal(310, 268), "inside the probed patch");
        assertEquals("waste", z.zoneAt(100, 50));
        assertEquals("purge row", z.zoneAt(85, 230));
        assertEquals("surface patch1", z.zoneAt(310, 268));
    }

    @Test
    public void aCouponIsAllowedOverItsWholeRectangle() {
        DispenseConfig c = config();
        c.locations.put("coupon1", new DispenseConfig.NamedLocation(330, 50, 5.6)); // a 40 x 30 coupon
        OutputZones z = OutputZones.of(c, CAMERA);
        assertNull(z.refusal(345, 60), "a weigh dot near the coupon's corner");
        assertEquals("coupon coupon1", z.zoneAt(345, 60));
        assertNotNull(z.refusal(355, 70), "beyond the coupon");
    }

    @Test
    public void theBottomCameraIsForbiddenWhateverCoversIt() {
        OutputZones z = OutputZones.of(config(), CAMERA);
        String why = z.refusal(205, 32);
        assertNotNull(why);
        assertTrue(why.contains("bottom camera"), why);
        assertEquals("forbidden: bottom camera", z.zoneAt(205, 32));
        // the towel's circle reaches into the camera's: the overlap stays forbidden, its far side is allowed
        assertNotNull(z.refusal(206, 33));
        assertNull(z.refusal(214, 38));
    }

    @Test
    public void nowhereInParticularIsRefused() {
        OutputZones z = OutputZones.of(config(), CAMERA);
        String why = z.refusal(150, 150);
        assertNotNull(why);
        assertTrue(why.contains("not over a place where paste may go"), why);
        assertNull(z.zoneAt(150, 150));
    }

    @Test
    public void aSegmentMayNotCrossAForbiddenZone() {
        OutputZones z = OutputZones.of(config(), CAMERA);
        assertNotNull(z.crossing(150, 30, 250, 30), "straight over the camera");
        assertNull(z.crossing(150, 60, 250, 60), "30 mm beside it");
    }

    @Test
    public void configuredForbiddenCirclesAndRadii() {
        DispenseConfig c = config();
        c.outputZones.forbidRadiusMm = 25;
        DispenseConfig.ForbidZone f = new DispenseConfig.ForbidZone();
        f.name = "feeder";
        f.x = 100;
        f.y = 52;
        f.radiusMm = 1;
        c.outputZones.forbid.add(f);
        OutputZones z = OutputZones.of(c, CAMERA);
        assertNotNull(z.refusal(214, 38), "inside the wider camera circle now");
        assertTrue(z.refusal(100, 52).contains("feeder"), z.refusal(100, 52));
        assertNull(OutputZones.of(c, null).refusal(205, 32), "no bottom camera: the towel there is allowed");
    }
}
