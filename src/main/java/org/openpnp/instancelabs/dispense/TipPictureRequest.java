package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

/**
 * A picture of the mounted tip from below (tip setups design §22.4, {@link DispenseService#takeTipPicture}):
 * the setup and needle it is meant for, the records to link it to, and the STOP epoch when it was asked.
 * A needle change or a mount before the frame is stored leaves no photo.
 */
public final class TipPictureRequest {
    /** The mounted setup when asked (only the mounted tip can be photographed). */
    public String setupKey;
    /** The needle token when asked ({@link DispenseConfig#needleChangedAtMs}). */
    public Long needleSinceMs;
    /** Calibration records to link the picture to (role tip). */
    public List<Long> calibrationIds = new ArrayList<>();
    /** {@link DispenseService#stopEpoch()} when the request was built: a STOP since then refuses it. */
    public long stopEpoch;
    public String operator;
}
