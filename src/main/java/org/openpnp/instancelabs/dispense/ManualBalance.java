package org.openpnp.instancelabs.dispense;

/**
 * The default balance: not connected to the computer (only the Lumen and the oven controller are on
 * USB), so the job asks the operator for each reading (§7.5). Readability 1 mg, the bench's
 * U.S. Solid 110 g × 0.001 g.
 */
public final class ManualBalance implements Balance {
    public static final String KIND = "manual";
    public static final double RESOLUTION_MG = 1.0;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public double resolutionMg() {
        return RESOLUTION_MG;
    }

    /** Never: the operator reads this balance and types the reading. */
    @Override
    public Reading read(long timeoutMs) {
        throw new UnsupportedOperationException("the balance is not connected: the operator types the readings");
    }
}
