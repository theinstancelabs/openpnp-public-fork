package org.openpnp.instancelabs.dispense;

/**
 * The camera that photographs the mounted tip from below (tip setups design §22.4): OpenPnP's first
 * camera looking up ({@link OpenPnpTipCamera}), or a test's ({@link DispenseService#setTipCamera}).
 * The picture is a record, not a measurement: nothing is found in it.
 */
public interface TipCamera {
    /** Whether there is a camera looking up. */
    boolean isAvailable();

    /** Its machine location {x, y, z} (mm): N2 goes to its X/Y at the balanced travel height; z is its focal plane. */
    double[] locationMm();

    /** One lit, settled frame (light actuator on for the capture) with its scale at the focal plane. */
    DotImager.Frame capture() throws Exception;
}
