package org.openpnp.instancelabs.padprofile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The stated paste model of §1.1-§1.5: source points, the two-regime footprint law, its band, the ripple. */
public class DoseModelTest {
    @Test
    public void sourcePointsAndTheirExclusions() {
        assertEquals(2.11 / 6, DoseModel.SIXTH_MASS_MG, 1e-12);
        assertEquals(0.352, DoseModel.SIXTH_MASS_MG, 0.0005);
        assertEquals(0.359, DoseModel.SIXTH_AREA_MM2, 0);
        assertEquals(1.056, DoseModel.HALF_MASS_MG, 0);
        assertEquals(1.057, DoseModel.HALF_AREA_MM2, 0.0005);
        assertEquals(2.11, DoseModel.ONE_TURN_MASS_MG, 0);
        assertEquals(1.421, DoseModel.ONE_TURN_AREA_MM2, 0.0005);
        assertEquals(3, DoseModel.SIXTH_N);
        assertEquals(24, DoseModel.HALF_N);
        assertEquals(33, DoseModel.ONE_TURN_N);
        assertTrue(DoseModel.SIXTH_SOURCE.contains("dots 7-9") && DoseModel.SIXTH_SOURCE.contains("dots 4-6")
                && DoseModel.SIXTH_SOURCE.contains("dots 10-16") && DoseModel.SIXTH_SOURCE.contains("p18_split_blank "));
        assertTrue(DoseModel.HALF_SOURCE.contains("without dot 15") && DoseModel.HALF_SOURCE.contains("dots 0-3"));
        assertTrue(DoseModel.ONE_TURN_SOURCE.contains("dots 6-38") && DoseModel.ONE_TURN_SOURCE.contains("dots 4-5"));
        // 1.32 µg per motor step at 18 psi
        assertEquals(1.32e-3, DoseModel.M_REV_MG / DoseModel.STEPS_PER_REV, 0.005e-3);
        assertEquals(1.055, DoseModel.massMg(800), 1e-12);
        assertEquals(90.09, DoseModel.netDeg(400, 4.44), 0.005);
    }

    @Test
    public void chordExponentsAndTheLaw() {
        assertEquals(0.982, DoseModel.EXPONENT_LOW, 0.005);
        assertEquals(0.428, DoseModel.EXPONENT_HIGH, 0.005);
        // the law goes through the three points
        assertEquals(DoseModel.SIXTH_AREA_MM2, DoseModel.areaMm2(DoseModel.SIXTH_MASS_MG), 1e-9);
        assertEquals(DoseModel.HALF_AREA_MM2, DoseModel.areaMm2(DoseModel.HALF_MASS_MG), 1e-9);
        assertEquals(DoseModel.ONE_TURN_AREA_MM2, DoseModel.areaMm2(DoseModel.ONE_TURN_MASS_MG), 1e-9);
        // up to the half turn area ∝ mass (a disc of constant height), above it ∝ m^0.43
        assertEquals(2.0, DoseModel.areaMm2(1.0) / DoseModel.areaMm2(0.5), 0.03);
        assertEquals(Math.pow(2, 0.428), DoseModel.areaMm2(2.0) / DoseModel.areaMm2(1.0 * 1.06), 0.1);
    }

    @Test
    public void bandAtTheAllowedDoses() {
        assertEquals(0.825, DoseModel.dLoMm(400), 0.005);
        assertEquals(1.000, DoseModel.dHiMm(400), 0.005);
        assertEquals(0.535, DoseModel.areaBand(DoseModel.massMg(400))[0], 0.002);
        assertEquals(0.785, DoseModel.areaBand(DoseModel.massMg(400))[1], 0.002);
        assertEquals(1.160, DoseModel.dHiMm(800), 0);
        assertEquals(1.160, DoseModel.dLoMm(800), 0);
        assertEquals(1.345, DoseModel.dHiMm(1600), 0);
        assertEquals(1.345, DoseModel.dLoMm(1600), 0);
        // log A is concave in log m: the chord is a lower bound, the next chord extended an upper one
        double[] b = DoseModel.areaBand(0.8);
        assertTrue(b[0] < b[1]);
    }

    @Test
    public void meanHeightsShowTheTwoRegimes() {
        assertEquals(0.261, DoseModel.meanHeightMm(DoseModel.SIXTH_MASS_MG, DoseModel.SIXTH_AREA_MM2), 0.0005);
        assertEquals(0.266, DoseModel.meanHeightMm(DoseModel.HALF_MASS_MG, DoseModel.HALF_AREA_MM2), 0.0005);
        assertEquals(0.395, DoseModel.meanHeightMm(DoseModel.ONE_TURN_MASS_MG, DoseModel.ONE_TURN_AREA_MM2), 0.0005);
    }

    @Test
    public void rippleFactorsAndAmplitude() {
        assertEquals(0.90, DoseModel.rippleFactor(400), 0.005);
        assertEquals(0.64, DoseModel.rippleFactor(800), 0.005);
        assertEquals(0.30, DoseModel.rippleFactor(1200), 0.005);
        assertEquals(0, DoseModel.rippleFactor(1600), 1e-12);
        // a = 0.113 / beta for beta between the two chords: 0.11 .. 0.26
        assertEquals(0.263, DoseModel.rippleAmplitude(0.43), 0.001);
        assertEquals(0.115, DoseModel.rippleAmplitude(0.98), 0.001);
        assertEquals(0.11, DoseModel.RIPPLE_A_LOW, 0);
        assertEquals(0.26, DoseModel.RIPPLE_A_HIGH, 0);
        // per pad: two quarter turns in a row are one half turn of the screw (7 .. 17 %)
        double[] p = DoseModel.padRipplePct(2 * 400);
        assertEquals(7, Math.round(p[0]));
        assertEquals(17, Math.round(p[1]));
        assertArrayEquals(new double[] {0, 0}, DoseModel.padRipplePct(1600), 1e-12);
    }

    @Test
    public void allowedDosesAndTurnNames() {
        Tip tip = Tip.g22();
        for (int s : new int[] {400, 800, 1600}) {
            assertTrue(DoseModel.isAllowed(s, tip), "" + s);
        }
        for (int s : new int[] {200, 266, 267, 533, 3200, 0}) {
            assertFalse(DoseModel.isAllowed(s, tip), "" + s);
        }
        Tip coarse = new Tip("x", "x", 0.6, 0.9, 800);
        assertFalse(DoseModel.isAllowed(400, coarse));
        assertTrue(DoseModel.isPartial(400) && DoseModel.isPartial(800) && !DoseModel.isPartial(1600));
        assertEquals("quarter-turn", DoseModel.turnName(400));
        assertEquals("half-turn", DoseModel.turnName(800));
        assertEquals("one-turn", DoseModel.turnName(1600));
        assertEquals("quarter turn", DoseModel.turnNoun(400));
        assertEquals("a half turn", DoseModel.turnWithArticle(800));
        assertEquals("one turn", DoseModel.turnWithArticle(1600));
    }
}
