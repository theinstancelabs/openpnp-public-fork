package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Volume / rotation arithmetic (plan §5.6, §6.1). The planner no longer sizes dots by volume (one
 * profile dot per pad, bench 2026-10-28); these helpers remain for the scale records and the
 * informational stencil volume of a pad.
 */
public class DotMathTest {
    @Test
    public void first0603PadMatchesPlanNumbers() {
        // plan §5.6: 0.80 x 0.95 mm pad, t = 0.10 mm -> 0.076 mm3 -> ~0.28 mg -> ~58 deg at 0.47 uL/rev
        double a = DotMath.padAreaMm2(0.80, 0.95, 0);
        assertEquals(0.76, a, 1e-12);
        double v = DotMath.targetVolumeMm3(a, 0.10, 1.0);
        assertEquals(0.076, v, 1e-12);
        assertEquals(0.2812, v * 3.7, 1e-4);
        double rot = DotMath.rotationDegForVolume(v, 0.47);
        assertEquals(58.2128, rot, 1e-4);
        // 4.44 steps/deg -> ~258 microsteps (plan: "~260 microsteps")
        assertEquals(258.5, rot * 4.44, 0.1);
        assertEquals(v, DotMath.volumeForRotation(rot, 0.47), 1e-12);
    }

    @Test
    public void kScalesVolume() {
        assertEquals(0.5 * 9.61 * 0.1, DotMath.targetVolumeMm3(9.61, 0.10, 0.5), 1e-12);
    }

    @Test
    public void roundedPadArea() {
        // 100 % roundness on a square = circle
        assertEquals(Math.PI * 0.25, DotMath.padAreaMm2(1, 1, 100), 1e-12);
        // 50 %: r = 0.25 on a 1 x 2 pad
        assertEquals(2 - (4 - Math.PI) * 0.0625, DotMath.padAreaMm2(1, 2, 50), 1e-12);
    }

    @Test
    public void qFromScale() {
        // 30 shots of 360 deg weighing 60.0 mg total at 3.7 mg/mm3 -> 0.5405 mm3/rev
        assertEquals(60.0 / 30 / 3.7, DotMath.qFromMass(60.0, 30, 360, 3.7), 1e-12);
        // 10 shots of 180 deg, 17.39 mg total -> q = 0.47
        assertEquals(0.47, DotMath.qFromMass(0.47 / 2 * 3.7 * 10, 10, 180, 3.7), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> DotMath.qFromMass(1, 0, 360, 3.7));
        assertThrows(IllegalArgumentException.class, () -> DotMath.rotationDegForVolume(1, 0));
    }

    @Test
    public void sphericalCap() {
        // hemisphere: h = a -> V = 2/3 pi a^3
        double a = 0.35;
        double v = 2.0 / 3.0 * Math.PI * a * a * a;
        assertEquals(a, DotMath.sphericalCapHeightMm(v, 2 * a), 1e-9);
        // plan §5.6: 0.076 mm3 dot 0.7 mm across is ~0.3 mm tall
        assertEquals(0.3, DotMath.sphericalCapHeightMm(0.076, 0.7), 0.03);
        assertEquals(0, DotMath.sphericalCapHeightMm(0, 0.7), 0);
    }

    @Test
    public void stats() {
        double[] v = {1, 2, 3, 4};
        assertEquals(2.5, DotMath.mean(v), 1e-12);
        assertEquals(Math.sqrt(5.0 / 3.0), DotMath.sd(v), 1e-12);
        assertEquals(Math.sqrt(5.0 / 3.0) / 2.5, DotMath.cv(v), 1e-12);
    }
}
