package org.openpnp.instancelabs.padprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Tip setups (§22.3, §22.8): where a needle lands. The row's fit finds a residual shift within ±1.0 mm to 0.02 mm with
 * dots missing and a foreign blob, gives no answer with too few matches, and no second shift matches more than 3 of
 * the row (its gaps all differ); the locator finds the dot within its search radius, the largest of a splash.
 */
public class LandingFitTest {
    /** The row of 8 along X at y, from x0. */
    static List<double[]> row(double x0, double y) {
        List<double[]> l = new ArrayList<>();
        for (double d : LandingFit.ROW_MM) {
            l.add(new double[] {x0 + d, y});
        }
        return l;
    }

    /** The row's blobs: each target moved by (dx, dy) plus a scatter, some missing. */
    static List<double[]> blobs(List<double[]> targets, double dx, double dy, double sd, Random r, int... missing) {
        List<double[]> l = new ArrayList<>();
        outer:
        for (int i = 0; i < targets.size(); i++) {
            for (int m : missing) {
                if (m == i) {
                    continue outer;
                }
            }
            double[] t = targets.get(i);
            l.add(new double[] {t[0] + dx + r.nextGaussian() * sd, t[1] + dy + r.nextGaussian() * sd, 0.9});
        }
        return l;
    }

    @Test
    public void residualShiftsAreFoundWithMissingDotsAndAForeignBlob() {
        Random r = new Random(7);
        double[][] shifts = {{0, 0}, {0.37, -0.81}, {-1.0, 0.2}, {0.95, 0.95}, {-0.6, -1.0}, {0.12, 0.66}};
        for (double[] s : shifts) {
            List<double[]> targets = row(300, 250);
            List<double[]> blobs = blobs(targets, s[0], s[1], 0.02, r, 2, 5);
            blobs.add(new double[] {300 + 9.2, 250 + 0.4, 1.1}); // paste that was there before
            LandingFit.Fit f = LandingFit.fit(targets, blobs);
            assertTrue(f.ok, "shift " + s[0] + ", " + s[1] + ": matched " + f.matched + ", next " + f.nextBest);
            assertEquals(6, f.matched);
            assertEquals(s[0], f.dxMm, 0.02);
            assertEquals(s[1], f.dyMm, 0.02);
            assertTrue(f.scatterMm <= LandingFit.SCATTER_MAX_MM, f.scatterMm + " mm");
            assertEquals(6, f.pairs.size());
        }
    }

    @Test
    public void fiveMatchesAreNoAnswer() {
        List<double[]> targets = row(300, 250);
        List<double[]> blobs = blobs(targets, 0.4, 0.1, 0.0, new Random(1), 0, 3, 6);
        LandingFit.Fit f = LandingFit.fit(targets, blobs);
        assertEquals(5, f.matched);
        assertFalse(f.ok);
    }

    @Test
    public void aScatterAboveTheLadderLimitIsNoAnswer() {
        List<double[]> targets = row(300, 250);
        List<double[]> blobs = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            double[] t = targets.get(i);
            blobs.add(new double[] {t[0] + (i % 2 == 0 ? 0.25 : -0.25), t[1], 0.9});
        }
        LandingFit.Fit f = LandingFit.fit(targets, blobs);
        assertFalse(f.ok, "scatter " + f.scatterMm);
    }

    @Test
    public void noOtherShiftMatchesMoreThanThreeOnAThousandRows() {
        Random r = new Random(2026);
        int worst = 0;
        for (int k = 0; k < 1000; k++) {
            double x0 = 280 + r.nextDouble() * 20, y = 240 + r.nextDouble() * 20;
            double dx = (r.nextDouble() * 2 - 1) * LandingFit.RESIDUAL_MAX_MM;
            double dy = (r.nextDouble() * 2 - 1) * LandingFit.RESIDUAL_MAX_MM;
            List<double[]> targets = row(x0, y);
            LandingFit.Fit f = LandingFit.fit(targets, blobs(targets, dx, dy, 0.03, r));
            assertTrue(f.ok, "row " + k + ": matched " + f.matched + ", next best " + f.nextBest);
            worst = Math.max(worst, f.nextBest);
        }
        assertTrue(worst <= LandingFit.MAX_NEXT_BEST, "second-best match " + worst);
    }

    @Test
    public void theSearchRadiusAndTheBand() {
        assertEquals(2, LandingFit.searchMm(true, true, false, false), 0);
        assertEquals(2, LandingFit.searchMm(true, false, true, false), 0, "bent, its bend direction recorded");
        assertEquals(6, LandingFit.searchMm(true, false, false, false), 0, "bent, the direction not recorded");
        assertEquals(6, LandingFit.searchMm(false, true, true, false), 0, "no landing of its own");
        assertEquals(9, LandingFit.searchMm(true, true, true, true), 0, "wide");
        assertEquals(12, LandingFit.bandHeightMm(2), 0);
        assertEquals(20, LandingFit.bandHeightMm(6), 0);
        assertEquals(26, LandingFit.bandHeightMm(9), 0);
    }

    @Test
    public void theLocatorFindsTheDotWithinItsWindow() {
        double[] target = {306, 260};
        for (double off : new double[] {0, 2, 6}) {
            List<double[]> blobs = new ArrayList<>();
            blobs.add(new double[] {target[0] + off * 0.8, target[1] - off * 0.6, 0.8});
            double[] c = LandingFit.locate(blobs, target, LandingFit.SEARCH_FIRST_MM);
            assertNotNull(c, off + " mm off");
            assertEquals(off * 0.8, c[0], 1e-9);
            assertEquals(-off * 0.6, c[1], 1e-9);
        }
        // 8 mm off: only a wide search finds it
        List<double[]> far = new ArrayList<>();
        far.add(new double[] {target[0] + 8, target[1], 0.8});
        assertNull(LandingFit.locate(far, target, LandingFit.SEARCH_FIRST_MM));
        assertNotNull(LandingFit.locate(far, target, LandingFit.SEARCH_WIDE_MM));
        // the window is ±R a side
        assertNull(LandingFit.locate(far, target, 7.9));
    }

    @Test
    public void aSplashGivesTheLargestNewBlob() {
        double[] target = {306, 260};
        List<double[]> blobs = new ArrayList<>();
        blobs.add(new double[] {306.3, 260.1, 0.05});
        blobs.add(new double[] {307.1, 259.6, 0.95});
        blobs.add(new double[] {306.0, 260.0, 0.02});
        double[] c = LandingFit.locate(blobs, target, 2);
        assertEquals(1.1, c[0], 1e-9);
        assertEquals(-0.4, c[1], 1e-9);
        assertNull(LandingFit.locate(new ArrayList<>(), target, 2));
    }
}
