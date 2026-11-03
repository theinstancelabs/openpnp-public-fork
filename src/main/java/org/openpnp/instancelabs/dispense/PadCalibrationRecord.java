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
    static final List<String> OUTCOMES = Arrays.asList(CHARACTERIZED, PROVISIONAL, STOPPED, CANCELLED, FAILED);

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
