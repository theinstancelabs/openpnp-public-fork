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
    public void aPlaneWithinReachStillFits() {
        SurfacePlane p = SurfacePlane.fit(points(0, 0, 5, 100, 0, 6, 0, 100, 7, -400, -400, -7));
        assertEquals(5.0, p.z(0, 0), 1e-9);
        assertEquals(6.0, p.z(100, 0), 1e-9);
        assertEquals(7.0, p.z(0, 100), 1e-9);
        assertEquals(Math.hypot(0.01, 0.02), p.slope(), 1e-12);
    }
}
