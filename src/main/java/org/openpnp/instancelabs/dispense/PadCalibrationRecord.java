package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One row of {@code pad_calibrations} (profile calibration design §5.3, §7.7): the outcome of
 * calibrating one pad type, with every check that decided it. Plain fields, so Gson writes it as
 * the API's JSON; the lists are stored as JSON columns.
 */
public class PadCalibrationRecord {
    public static final String CHARACTERIZED = "characterized";
    public static final String PROVISIONAL = "provisional";
    public static final String STOPPED = "stopped";
    public static final String CANCELLED = "cancelled";
    public static final String FAILED = "failed";
    /**
     * Written by the by-hand save only (tip setups design §22.5): the job never writes it, so it is not in
     * {@link #OUTCOMES}; the database accepts it for records of method {@link #METHOD_BY_HAND}.
     */
    public static final String VALIDATED = "validated";
    static final List<String> OUTCOMES = Arrays.asList(CHARACTERIZED, PROVISIONAL, STOPPED, CANCELLED, FAILED);
    /** {@link #method}: the job's decision, or the pad-type editor's by-hand save (§22.5). */
    public static final String METHOD_AUTO = "auto";
    public static final String METHOD_BY_HAND = "by-hand";

    /** One check of the decision (§7.6): a line with predicted / measured / limit. */
    public static class Check {
        /** Stable id, e.g. "threads", "mass-confirmed", "overhang". */
        public String key;
        public boolean pass;
        /** The plain-words line of the result card. */
        public String text;
        public String predicted, measured, limit;

        public Check() {
        }

        public Check(String key, boolean pass, String text) {
            this.key = key;
            this.pass = pass;
            this.text = text;
        }
    }

    public long id;
    public long createdAtMs;
    public String jobId;
    public String typeKey;
    /** The type's version before and the version written (null when none was written). */
    public Integer versionFrom, versionTo;
    /** characterized | provisional | stopped | cancelled | failed (never validated: that stays the user's). */
    public String outcome;
    public Integer steps, dotsPerPad;
    public Double massPerPadMg;
    public List<Check> checks = new ArrayList<>();
    /** The dose record ids pooled for the decision. */
    public List<Long> evidence = new ArrayList<>();
    /** Engine runs of the job (run uuids). */
    public List<String> runs = new ArrayList<>();
    public boolean simulated;
    public String message;
    public String operator;
    /**
     * Tip setups (§22.1): the setup the record was made with; null on records from before schema 3 (resolved when
     * read: the tip key of its job's dose records, of the version it wrote, else the seeded setup).
     */
    public String setupKey;
    /** auto | by-hand; null = auto (the only kind before schema 3). */
    public String method;
    /** The needle token at the job's start ({@link DispenseConfig#needleChangedAtMs}, §22.3). */
    public Long needleSinceMs;

    /** A "calibrated for" label of a record (§22.5): a row of {@code cal_labels}; the newest counts. */
    public static class Label {
        public long id;
        public long createdAtMs;
        /** The {@code pad_calibrations} row. */
        public long calibrationId;
        /** 1-160 characters. */
        public String label;
        /** The automatic text ({@code Texts.calLabel}), or the operator's. */
        public boolean auto;
        public String operator;
    }

    /** True when there is at least one check and every one passed (the rule for {@code characterized}). */
    public boolean allChecksPass() {
        if (checks == null || checks.isEmpty()) {
            return false;
        }
        for (Check c : checks) {
            if (c == null || !c.pass) {
                return false;
            }
        }
        return true;
    }
}
