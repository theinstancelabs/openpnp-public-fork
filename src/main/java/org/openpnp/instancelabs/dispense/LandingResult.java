package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

/**
 * What a landing check found (tip setups design §22.3): where the needle lands, T = expected landing +
 * trim + coarse shift + residual, relative to camera + N2 head offset, with the fit's numbers. Made by
 * the landing check job, saved by {@link DispenseService#setLanding}, which fills {@link #saved},
 * {@link #question} and {@link #text}. Gson-friendly: the job view's {@code result.landing}.
 */
public final class LandingResult {
    public String jobId;
    /** The setup and needle token at the job's start. */
    public String setupKey;
    public Long needleSinceMs;
    /** The landing T (mm), each axis within {@link DispenseConfig#MAX_LANDING_MM}. */
    public double dxMm, dyMm;
    /** Scatter of the matched pairs (mm; at most 0.15). */
    public Double sdMm;
    /** Row dots matched (of 8) and new blobs found. */
    public int n;
    public int found;
    /** The search radius R (2, 6 or 9 mm). */
    public double searchMm;
    /** The expected landing E, the locator's coarse shift C and the fit's residual {dx, dy} (mm). */
    public double[] expected;
    public double[] coarse;
    public double[] residual;
    /** The matched {target x, y, blob x, y} pairs (for the TIP event). */
    public List<double[]> pairs = new ArrayList<>();
    /** The result photo (the band's after frame). */
    public Long photoId;
    /** N2's head offset in OpenPnP during the check {x, y} (mm). */
    public double[] headOffset;
    /** Saved as the setup's landing. */
    public boolean saved;
    /** Not saved: "The {S} lands {d} mm from where it landed on {date}: is the {S} on the dispenser...?" */
    public String question;
    /** The result card's line. */
    public String text;
}
