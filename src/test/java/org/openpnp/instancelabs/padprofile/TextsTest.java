package org.openpnp.instancelabs.padprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The plain-language helpers of the pad calibration's texts. */
public class TextsTest {
    @Test
    public void anAmountAgainstItsTarget() {
        assertEquals("8 % less than the 2.29 mg target", Texts.vsTarget(-0.08, 2.29));
        assertEquals("10 % more than the 2.29 mg target", Texts.vsTarget(0.10, 2.29));
        // the result card read "(0 % more than the 2.29 mg target)" when the weighed amount was the target
        assertEquals("on the 2.29 mg target", Texts.vsTarget(0, 2.29));
        assertEquals("on the 2.29 mg target", Texts.vsTarget(0.004, 2.29));
        assertEquals("on the 2.29 mg target", Texts.vsTarget(-0.004, 2.29));
    }
}
