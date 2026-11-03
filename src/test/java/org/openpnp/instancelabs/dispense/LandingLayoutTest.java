package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.LandingFit;

/**
 * Tip setups (§22.3, §22.8): where the landing check lays its dots. The band is the area's next free 2R + 8 mm, the
 * locator at its centre and the camera frames over it; once the locator's coarse shift is known, the purge and the
 * row lie 3 and 6 mm from where its dot landed on the side with room (the purge and the row on opposite sides in a
 * 12 mm band), every dot 1.5 mm clear of the band's edges; and the refusals when the area cannot hold it.
 */
public class LandingLayoutTest {
    /** The bench's top camera frame (mm). */
    static final double[] FOV = {37.6, 21.2};
    static final double DOT = 0.8;

    /** The default 40 x 20 mm area on the blank. */
    static CalibrationArea area(double usedMm) {
        CalibrationArea a = new CalibrationArea();
        a.x0Mm = 280.0;
        a.y0Mm = 225.0;
        a.widthMm = CalibrationArea.DEFAULT_WIDTH_MM;
        a.heightMm = CalibrationArea.DEFAULT_HEIGHT_MM;
        a.usedMm = usedMm;
        return a;
    }

    static void clearOfTheBand(PadLadder.LandingLayout l, double[] p) {
        double keep = DOT / 2 + PadLadder.LANDING_CLEARANCE_MM;
        assertTrue(p[0] >= l.x0 + keep - 1e-9 && p[0] <= l.x1 - keep + 1e-9, p[0] + " in x");
        assertTrue(p[1] >= l.y0 + keep - 1e-9 && p[1] <= l.y1 - keep + 1e-9, p[1] + " in y");
    }

    @Test
    public void aFirstCheckSearchesSixMillimetresInATwentyMillimetreBand() {
        PadLadder.LandingLayout l = PadLadder.landingLayout(area(0), LandingFit.SEARCH_FIRST_MM, DOT, FOV, null);
        assertNull(l.problem);
        assertEquals(20, l.reservedMm, 1e-9);
        assertEquals(225, l.y0, 1e-9);
        assertEquals(245, l.y1, 1e-9);
        assertArrayEquals(new double[] {300, 235}, l.locator, 1e-9);
        assertEquals(2, l.frames.size(), "2 x 1 frames of the bench camera");
        assertTrue(l.purge.isEmpty() && l.row.isEmpty(), "the locator's step lays one dot");
        // the locator's dot landed 0.5, -1.0 mm off: the lines above it, where there is more room
        l = PadLadder.landingLayout(area(0), LandingFit.SEARCH_FIRST_MM, DOT, FOV, new double[] {0.5, -1.0});
        assertNull(l.problem);
        assertEquals(PadLadder.LANDING_PURGE_DOTS, l.purge.size());
        assertEquals(LandingFit.ROW_MM.length, l.row.size());
        for (double[] p : l.purge) {
            assertEquals(234 + PadLadder.LANDING_PURGE_MM, p[1], 1e-9);
            clearOfTheBand(l, p);
        }
        for (double[] p : l.row) {
            assertEquals(234 + PadLadder.LANDING_ROW_MM, p[1], 1e-9);
            clearOfTheBand(l, p);
        }
        assertEquals(PadLadder.LANDING_PURGE_PITCH_MM, l.purge.get(1)[0] - l.purge.get(0)[0], 1e-9);
        assertEquals(LandingFit.ROW_MM[7], l.row.get(7)[0] - l.row.get(0)[0], 1e-9);
        assertEquals(300.5, (l.row.get(0)[0] + l.row.get(7)[0]) / 2, 1e-9, "centred on the locator's dot");
        // landed high in the band: the lines go below it
        l = PadLadder.landingLayout(area(0), LandingFit.SEARCH_FIRST_MM, DOT, FOV, new double[] {0, 5.5});
        assertEquals(240.5 - PadLadder.LANDING_ROW_MM, l.row.get(0)[1], 1e-9);
    }

    @Test
    public void aRemountSearchesTwoMillimetresWithThePurgeAndTheRowOnEitherSide() {
        PadLadder.LandingLayout l = PadLadder.landingLayout(area(0), LandingFit.SEARCH_KNOWN_MM, DOT, FOV,
                new double[] {0, 0});
        assertNull(l.problem);
        assertEquals(12, l.reservedMm, 1e-9);
        assertEquals(231, l.locator[1], 1e-9);
        assertEquals(2, l.frames.size(), "the band's 40 mm take two frames side by side");
        assertEquals(231 - PadLadder.LANDING_PURGE_MM, l.purge.get(0)[1], 1e-9);
        assertEquals(231 + PadLadder.LANDING_PURGE_MM, l.row.get(0)[1], 1e-9);
        for (double[] p : l.row) {
            clearOfTheBand(l, p);
        }
        // landed near an edge: no room on either side for a line
        l = PadLadder.landingLayout(area(0), LandingFit.SEARCH_KNOWN_MM, DOT, FOV, new double[] {0, 1.5});
        assertEquals("The locator's dot landed too near the band's edge for the row: wipe the blank and check again.",
                l.problem);
    }

    @Test
    public void whatTheAreaCannotHold() {
        // wide (±9 mm) needs a 26 mm band: taller than the default area
        assertEquals("Searching ±9 mm needs 26 mm of the calibration area; it is 20 mm tall: make it taller "
                + "(Calibrate › Pad calibration).", PadLadder.landingLayout(area(0), LandingFit.SEARCH_WIDE_MM, DOT,
                        FOV, null).problem);
        assertEquals("The calibration area has 10.0 mm left; the landing check needs 20 mm: wipe the blank (Calibrate › "
                + "Pad calibration › Blank wiped).", PadLadder.landingLayout(area(10), LandingFit.SEARCH_FIRST_MM, DOT,
                        FOV, null).problem);
        CalibrationArea narrow = area(0);
        narrow.widthMm = 20.0;
        assertEquals("The calibration area is too narrow for the landing check's row (28.3 mm needed): make it wider "
                + "(Calibrate › Pad calibration).", PadLadder.landingLayout(narrow, LandingFit.SEARCH_FIRST_MM, DOT, FOV,
                        new double[] {0, 0}).problem);
        assertEquals("The calibration area is not set: set the blank place first (Setup › Places).",
                PadLadder.landingLayout(new CalibrationArea(), LandingFit.SEARCH_FIRST_MM, DOT, FOV, null).problem);
    }
}
