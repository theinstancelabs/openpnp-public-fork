package org.openpnp.instancelabs.dispense;

/**
 * Outcome of {@link DispenseService#takeTipPicture} (tip setups design §22.4), Gson-friendly: the run
 * (kind {@code tip-picture}) and the stored photo, or why there is none.
 */
public final class TipPictureResult {
    public RunResult run;
    public boolean ok;
    public String message;
    /** The stored picture (kind tip, source bottom-camera), null when none was stored. */
    public Photo photo;
}
