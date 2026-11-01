package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.vision.Dot;

/**
 * I10 clean-pad guard (bench disp.py --require-clean): the share of bright pixels in the box at the
 * target, through the camera imager's default method, and the refusal it produces.
 */
public class PadCleanGuardTest {
    /** A camera that only measures (the default brightFraction). */
    static final DotImager CAMERA = new DotImager() {
        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Frame capture(double xMm, double yMm) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Dot> inspect(Frame before, Frame after) {
            throw new UnsupportedOperationException();
        }
    };

    /**
     * 200 x 200 px at 0.01 mm/px centred on the target: bare copper (gray 206) right of the centre,
     * dark blue paste left of it.
     */
    static DotImager.Frame halfPasted() {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(230, 200, 170));
        g.fillRect(0, 0, 200, 200);
        g.setColor(new Color(40, 60, 120));
        g.fillRect(0, 0, 100, 200);
        g.dispose();
        return new DotImager.Frame(img, 0.01, 0.01, 100, 80);
    }

    @Test
    public void defaultBrightFractionIsTheBenchRule() {
        DispenseConfig.CleanPadCheck s = new DispenseConfig().cleanPadCheck;
        // box 0.806 x 0.807 mm = 81 x 81 px from x 60: 40 paste columns, 41 copper columns
        assertEquals(41.0 / 81, CAMERA.brightFraction(halfPasted(), s), 1e-12);
        assertTrue(CAMERA.brightFraction(halfPasted(), s) < s.minBrightFraction);
        // the settings reach the measurement: a smaller box, a higher gray level
        s.boxWMm = 0.40;
        assertEquals(0.5, CAMERA.brightFraction(halfPasted(), s), 1e-12);
        s.grayLevel = 210; // copper (206) no longer counts as bright
        assertEquals(0, CAMERA.brightFraction(halfPasted(), s), 0);
        // no image (or the box off the frame): the pad cannot be judged, the service marks it not clean
        assertThrows(IllegalArgumentException.class, () -> CAMERA.brightFraction(new DotImager.Frame(null, 0.01, 0.01,
                0, 0), new DispenseConfig().cleanPadCheck));
        DispenseConfig.CleanPadCheck big = new DispenseConfig().cleanPadCheck;
        big.boxWMm = 3;
        assertThrows(IllegalArgumentException.class, () -> CAMERA.brightFraction(halfPasted(), big));
    }

    static PadCleanReport.Pad pad(String id, Double bright, String error) {
        PadCleanReport.Pad p = new PadCleanReport.Pad();
        p.padId = id;
        p.brightFraction = bright;
        p.error = error;
        p.clean = bright != null && bright >= 0.8;
        return p;
    }

    @Test
    public void reportListsTheDirtyPads() {
        PadCleanReport r = PadCleanReport.of(new DispenseConfig().cleanPadCheck);
        assertEquals(200, r.grayLevel);
        assertEquals(0.8, r.minBrightFraction, 0);
        assertEquals(0.806, r.boxWMm, 0);
        r.pads.addAll(Arrays.asList(pad("R1.1", 0.95, null), pad("R1.2", 0.5, null), pad("D3.1", null,
                "pad box outside the image"), pad("R1.2", 0.6, null)));
        assertEquals(Arrays.asList("R1.2", "D3.1"), r.dirtyPadIds());
        String why = r.refusal();
        assertTrue(why.startsWith("I10: 2 pad(s) are not clean (bright share < 0.80)"), why);
        assertTrue(why.contains("R1.2 (0.50)"), why);
        assertTrue(why.contains("D3.1 (pad box outside the image)"), why);
        assertFalse(why.contains("R1.1"), why);
        assertFalse(why.contains(", ..."), why);
        // long lists are cut after 12 pads
        PadCleanReport many = PadCleanReport.of(new DispenseConfig().cleanPadCheck);
        for (int i = 1; i <= 15; i++) {
            many.pads.add(pad("P" + i, 0.1, null));
        }
        why = many.refusal();
        assertTrue(why.startsWith("I10: 15 pad(s)"), why);
        assertTrue(why.contains("P12 (0.10), ..."), why);
        assertFalse(why.contains("P13"), why);
    }
}
