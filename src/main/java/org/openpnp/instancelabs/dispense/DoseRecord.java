package org.openpnp.instancelabs.dispense;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One row of {@code dose_records} (profile calibration design §5.3): what one dose (net steps per
 * dot) did under stated conditions. Kinds:
 * <ul>
 * <li>{@code optical}: dots on the blank, measured by the top camera;</li>
 * <li>{@code coupon}: weigh dots laid on a coupon place, imaged; pending until a mass row names it;</li>
 * <li>{@code mass}: the reading pair of a coupon (with paste, wiped), or a skipped weighing (no
 * readings, {@link #why} says so).</li>
 * </ul>
 * Records are evidence for the setup they were made in only: {@link #whyStale} (§1.7).
 */
public class DoseRecord {
    public static final String OPTICAL = "optical";
    public static final String COUPON = "coupon";
    public static final String MASS = "mass";
    static final List<String> KINDS = Arrays.asList(OPTICAL, COUPON, MASS);
    /** auto (a calibration job), manual (the guide's steps), typed (readings typed), sim (the SIM balance). */
    static final List<String> METHODS = Arrays.asList("auto", "manual", "typed", "sim");

    public long id;
    public long createdAtMs;
    public String kind;
    /** Net steps per dot. */
    public int steps;
    /** Dots laid. */
    public int n;
    // optical / coupon: what the camera saw
    public Integer found, misses, hops, threads;
    public Double hopMm;
    public Double areaMeanMm2, residCv, diameterMeanMm, diameterCv, diameterP95Mm;
    /** Per-phase statistics as JSON (LadderStats). */
    public String phaseJson;
    public Double drift, landingMeanMm, landingSdMm;
    /** The mean landing per axis, found - commanded (mm; tip setups design §22.1); landingMeanMm is its length. */
    public Double landingDxMm, landingDyMm;
    /** Per-dot measurements as JSON. */
    public String dotsJson;
    public Boolean pass;
    /** Why the record failed or, for a mass row without readings, why the weighing was skipped. */
    public String why;
    /** Coupon records: the place the dots lie on. */
    public String couponPlace;
    // mass: a reading pair of a coupon record
    public Long couponRecordId;
    public Double fullMg, emptyMg, totalMg, mgPerDot, mgPerStep, mgSe;
    /** Seconds from the coupon's dots to the reading. */
    public Integer weighDelayS;
    /** The other mass record this one agrees with (§7.5), if any. */
    public Long confirmedBy;
    // conditions (§1.7)
    public double pressurePsi;
    public String doseFp, tipKey, pasteKey;
    /** The operator's regulator reading attested for the run that laid the dots, and when. */
    public Double regulatorPsi;
    public Long regulatorAtMs;
    public Long pasteLifeAtMs;
    public Long controllerConnectedAtMs, startAngleSetAtMs;
    public Double startAngleDeg;
    public String method;
    public boolean simulated;
    public String runUuid, jobId, notes, operator;

    /** What the record is checked against that is not in the configuration. */
    public static final class Epochs {
        /** The controller's last connect (the B step count restarts at power-up); null = unknown. */
        public final Long controllerConnectedAtMs;
        /** {@code SimGuard.isSimulated} now: SIM records never count on the machine and the other way round. */
        public final boolean simulated;

        public Epochs(Long controllerConnectedAtMs, boolean simulated) {
            this.controllerConnectedAtMs = controllerConnectedAtMs;
            this.simulated = simulated;
        }
    }

    /** A partial turn: its per-phase results belong to the start angle the screw started at. */
    public boolean isPartialTurn(int stepsPerRev) {
        return steps % stepsPerRev != 0;
    }

    /** A mass row with a reading pair (not a skipped weighing). */
    public boolean hasReading() {
        return MASS.equals(kind) && totalMg != null;
    }

    /** When the paste left the screw: a mass row's dots were laid {@link #weighDelayS} before it. */
    public long dispensedAtMs() {
        return MASS.equals(kind) && weighDelayS != null ? createdAtMs - weighDelayS * 1000L : createdAtMs;
    }

    public boolean isFresh(long nowMs, DispenseConfig cfg, Epochs epochs) {
        return whyStale(nowMs, cfg, epochs) == null;
    }

    /**
     * Why this record is no evidence for the current setup (null if it is), the first rule that
     * fails (§1.7): the simulator flag, the dose recipe, the attested regulator reading, the tip and
     * the needle change, the paste and its loading, the age; for optical and coupon records of a
     * partial turn also the controller's connect and the start angle (mass records count whole
     * revolutions, whole turns have no phase).
     */
    public String whyStale(long nowMs, DispenseConfig cfg, Epochs epochs) {
        long at = dispensedAtMs();
        if (simulated != epochs.simulated) {
            return simulated ? "made in the simulator" : "made on the machine, not in this simulator";
        }
        String fp = cfg.profile.doseFingerprint();
        if (!fp.equals(doseFp)) {
            return "made with another recipe (" + DispenseProfile.fingerprintDiff(doseFp, fp) + ")";
        }
        if (regulatorPsi == null) {
            return "made without a regulator reading";
        }
        if (Math.abs(regulatorPsi - cfg.profile.pressurePsi) > 1e-6) {
            return String.format(Locale.ROOT, "made with the regulator at %s psi; the profile asks for %s psi",
                    DispenseProfile.plain(regulatorPsi), DispenseProfile.plain(cfg.profile.pressurePsi));
        }
        if (!cfg.tip.key.equals(tipKey)) {
            return "made with another tip (" + tipKey + "); the tip now is the " + cfg.tip.name + " (" + cfg.tip.key
                    + ")";
        }
        if (cfg.needleChangedAtMs != null && at < cfg.needleChangedAtMs) {
            return "made before the needle was changed";
        }
        if (!cfg.paste.key.equals(pasteKey)) {
            return "made with another paste (" + pasteKey + "; the paste now is " + cfg.paste.key + ")";
        }
        if (cfg.paste.loadedAtMs != null && at < cfg.paste.loadedAtMs) {
            return "made before the new paste was loaded";
        }
        double maxAgeH = cfg.padCalibration.doseRecordMaxAgeH;
        if (nowMs - at > maxAgeH * 3600e3) {
            return String.format(Locale.ROOT, "older than %s h (made %.1f h ago)", DispenseProfile.plain(maxAgeH),
                    (nowMs - at) / 3600e3);
        }
        if (!MASS.equals(kind) && isPartialTurn(cfg.bStepsPerRev)) {
            if (epochs.controllerConnectedAtMs != null && at < epochs.controllerConnectedAtMs) {
                return "made before the controller last connected: the screw angle may have moved";
            }
            Long set = cfg.profile.startAngleSetAtMs;
            if (set == null) {
                return "the screw start angle has never been set";
            }
            if (at < set) {
                return "made before the screw start angle was set again";
            }
        }
        return null;
    }
}
