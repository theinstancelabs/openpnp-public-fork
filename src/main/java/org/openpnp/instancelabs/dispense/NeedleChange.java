package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

/**
 * A needle change as the engine recorded it (tip setups design §22.3, {@link
 * DispenseService#recordNeedleChange}): the fact is never refused. Its time is the new needle token
 * (strictly increasing); a running engine task was stopped, every coupon still waiting for its
 * readings got a skip row, and a {@code tip_mounts} row {@code needle} was written. Gson-friendly: the
 * API answers {@code {needleChange: {atMs, voided: {coupons, job, task}}, text}}.
 */
public final class NeedleChange {
    /** The new {@link DispenseConfig#needleChangedAtMs}: max(now, previous + 1). */
    public long atMs;
    /** The setup the needle belongs to (the mounted one). */
    public String setupKey;
    public String setupName;
    public String why;
    public String operator;
    /** What the change voided. */
    public Voided voided = new Voided();
    /** "The needle change is recorded: ...", with "wipe coupon1 before its next use" when a coupon was freed. */
    public String text;

    /** The coupons (by place) whose readings no longer count, the job they belonged to, the engine task stopped. */
    public static final class Voided {
        public List<String> coupons = new ArrayList<>();
        public String job;
        public String task;
    }
}
