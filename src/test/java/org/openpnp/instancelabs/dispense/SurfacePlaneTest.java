package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The plane fit's inputs (Infra's INF-12 re-check of #66, 11-05): a point far outside any machine gives no plane, never a
 * NaN plane (a NaN height makes every comparison of a check false) or a flat one at the mean height.
 */
public class SurfacePlaneTest {
    static List<DispenseConfig.SurfacePoint> points(double... xyz) {
        List<DispenseConfig.SurfacePoint> l = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) {
            l.add(new DispenseConfig.SurfacePoint(xyz[i], xyz[i + 1], xyz[i + 2]));
        }
        return l;
    }

    @Test
    public void aPointFarOutsideTheMachineIsRefusedNeverFitted() {
        // 1e200 overflowed the sums: det was NaN, passed "det <= 1e-9", and the plane read z = NaN, slope NaN
        for (List<DispenseConfig.SurfacePoint> far : List.of(
                points(60, 120, 5, 1e200, 120, 5, 300, 300, 5, 60, 300, 5),
                points(60, 120, 5, 1e150, 120, 9, 300, 300, 5, 60, 300, 5),
                points(60, 120, 5, 300, -1e7, 5, 300, 300, 5),
                points(60, 120, 5, 300, 120, 2e4, 300, 300, 5))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> SurfacePlane.fit(far));
            assertTrue(e.getMessage().contains("out of range"), e.getMessage());
        }
    }

    @Test
    public void theBoundItselfFitsAndJustBeyondItIsRefusedByItsExactValue() {
        SurfacePlane.fit(points(-1e4, -1e4, 5, 1e4, -1e4, 5, 1e4, 1e4, 5)); // corners at ±10,000 mm fit
        for (double over : new double[] {Math.nextUp(1e4), -Math.nextUp(1e4)}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SurfacePlane.fit(points(0, 0, 5, over, 0, 5, 0, 100, 5)));
            assertTrue(e.getMessage().contains("(" + over + ", 0.0, 5.0)"), e.getMessage()); // not "10000.0"
        }
    }

    @Test
    public void aTestedPointFarOutsideIsOutsideNeverOnTheSurface() {
        // the fit bounds its points, not the point tested: at (1e307, 1e307) every edge's cross was Inf - Inf = NaN,
        // never "< -1e-9", so I4 and I12 read it as on the surface (distance 0)
        SurfacePlane p = SurfacePlane.fit(points(0, 0, 5, 985, 174, 5, 1159, 1159, 5));
        for (double[] xy : new double[][] {{1e307, 1e307}, {-1e307, 1e307}, {Double.NaN, 500},
                {500, Double.POSITIVE_INFINITY}}) {
            assertTrue(p.distanceOutside(xy[0], xy[1]) > 1e6, xy[0] + ", " + xy[1]);
        }
        assertEquals(0, p.distanceOutside(700, 500), 1e-9);
        assertEquals(100, p.distanceOutside(-100, 0), 1e-9);
    }

    @Test
    public void aPlaneWithinReachStillFits() {
        SurfacePlane p = SurfacePlane.fit(points(0, 0, 5, 100, 0, 6, 0, 100, 7, -400, -400, -7));
        assertEquals(5.0, p.z(0, 0), 1e-9);
        assertEquals(6.0, p.z(100, 0), 1e-9);
        assertEquals(7.0, p.z(0, 100), 1e-9);
        assertEquals(Math.hypot(0.01, 0.02), p.slope(), 1e-12);
    }
}
