package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

/**
 * The switch of a mount (tip setups design §22.3, {@link DispenseService#switchTip} and {@link
 * DispenseService#undoMount}): what is mounted now, what it replaced, its profile, what went stale,
 * N2's head offset now against the one the incoming landing was checked under, and until when it can
 * be undone. Gson-friendly. The API adds the needle change it recorded first ({@link #needleChange})
 * and the next steps.
 */
public final class MountResult {
    /** The setup mounted now and its name. */
    public String mounted;
    public String mountedName;
    /** The setup it replaced and its name. */
    public String from;
    public String fromName;
    public long atMs;
    /** The needle token, as the needle change wrote it (a mount keeps it). */
    public Long needleChangedAtMs;
    /** The mounted setup's dispense profile (the live start angle carried over). */
    public DispenseProfile profile;
    /** The needle change recorded before the switch (set by the caller of {@link DispenseService#switchTip}). */
    public NeedleChange needleChange;
    /** What goes stale: "needle Z frame", "offset", "landing", "plans", ... */
    public List<String> stale = new ArrayList<>();
    /** N2's head offset in OpenPnP now, and the one the incoming setup's landing was checked under (mm), or null. */
    public double[] headOffsetNow;
    public double[] headOffsetLandingWas;
    /** Until when {@code POST /api/tip-setups/undo-mount} may restore the previous mount (epoch ms), or null. */
    public Long undoUntilMs;
    /** E.g. the incoming profile was reset because it no longer validated. */
    public List<String> warnings = new ArrayList<>();
    /** "{S} mounted at {HH:MM}. Next: record its needle Z frame (Setup › Needle Z frame)." */
    public String text;
}
