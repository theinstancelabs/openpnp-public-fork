package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Limits and sizes of the pad calibration (profile calibration design §5.4, §7.4-7.6). The phase,
 * thread and footprint limits of §1.6 are code constants of the model; these are the site settings.
 */
public class PadCalibrationSettings {
    /** Below these, the ladder and weighing statistics lose their operating points (§7.4): simulator only. */
    public static final int REAL_MIN_LADDER_DOTS_PER_PHASE = 12;
    public static final int REAL_MIN_LADDER_DOTS = 28;
    public static final int REAL_MIN_HOPS = 60;
    public static final double REAL_MIN_WEIGH_TARGET_MG = 50;

    /** Repeat: residual area CV (phase harmonic removed), upper bound. */
    public double maxResidCv = 0.13;
    /** Phase of half turns: relative split of the two start angles, upper bound. */
    public double maxHalfSplit = 0.13;
    /** Phase of quarter turns: first-harmonic amplitude, upper bound. */
    public double maxQuarterAmplitude = 0.20;
    /** Mass per pad within -massTolLow / +massTolHigh of the target. */
    public double massTolLow = 0.25;
    public double massTolHigh = 0.10;
    /** Two readings confirm a mass when their mass per step agrees within this. */
    public double confirmTolerance = 0.05;
    /** The control (profile dose) against the bench's blank area. */
    public double controlTolerance = 0.15;
    /** Paste per weigh coupon (mg): the dot count follows from it. */
    public double weighTargetMg = 70;
    /** Dose records count this long (h): one session. A placeholder until drift is measured (decision 9). */
    public double doseRecordMaxAgeH = 8;
    /** A reading typed this long after its dots needs a confirm (the flux dries). */
    public double readingConfirmAfterMin = 15;
    /**
     * Places of the weigh coupons (Setup › Places). One defined place is enough to start; a second
     * one lets one job confirm the mass by a second reading (decision 1).
     */
    public List<String> couponPlaces = new ArrayList<>(Arrays.asList("coupon1", "coupon2"));
    /** Nominal coupon size, X and Y (mm), centred on its place. */
    public double[] couponSizeMm = {40, 30};
    /** Weigh dots stay this far inside the coupon's nominal rectangle (mm). */
    public double couponMarginMm = 3;
    /** Coupon surface probe window around the place's Z (mm). */
    public double couponWindowMm = 0.3;
    /** Dots of the new dose laid on the purge row at a block switch (§1.2 R3). */
    public int flushDots = 4;
    public int ladderDotsPerPhase = 12;
    public int minLadderDots = 28;
    public int minHops = 60;
    /** Thread and miss rates the binomial checks accept (§7.4). */
    public double threadRateOk = 0.03;
    public double missRateOk = 0.005;

    /** The configured coupon places that exist as named places, in order. */
    public List<String> definedCouponPlaces(DispenseConfig cfg) {
        List<String> l = new ArrayList<>();
        for (String place : couponPlaces) {
            if (cfg.location(place) != null) {
                l.add(place);
            }
        }
        return l;
    }

    /**
     * The nominal rectangle of the coupon on a place ({x0, y0, x1, y1}, centred on the place), or
     * null if the place is not defined.
     */
    public double[] couponRect(DispenseConfig cfg, String place) {
        DispenseConfig.NamedLocation l = cfg.location(place);
        if (l == null || couponSizeMm == null || couponSizeMm.length != 2) {
            return null;
        }
        double hw = couponSizeMm[0] / 2, hh = couponSizeMm[1] / 2;
        return new double[] {l.x - hw, l.y - hh, l.x + hw, l.y + hh};
    }

    /** Problems with these values, added to {@link DispenseConfig#validate()}. */
    void validate(List<String> p) {
        String n = "padCalibration.";
        fraction(p, n + "maxResidCv", maxResidCv);
        fraction(p, n + "maxHalfSplit", maxHalfSplit);
        fraction(p, n + "maxQuarterAmplitude", maxQuarterAmplitude);
        fraction(p, n + "massTolLow", massTolLow);
        fraction(p, n + "massTolHigh", massTolHigh);
        fraction(p, n + "controlTolerance", controlTolerance);
        fraction(p, n + "threadRateOk", threadRateOk);
        fraction(p, n + "missRateOk", missRateOk);
        if (!(confirmTolerance > 0 && confirmTolerance <= 0.5)) {
            p.add(n + "confirmTolerance must be > 0 and <= 0.5");
        }
        if (!(weighTargetMg > 0 && weighTargetMg <= 1000)) {
            p.add(n + "weighTargetMg must be > 0 and <= 1000 mg");
        }
        if (!(doseRecordMaxAgeH > 0 && doseRecordMaxAgeH <= 168)) {
            p.add(n + "doseRecordMaxAgeH must be > 0 and <= 168 h");
        }
        if (!(readingConfirmAfterMin >= 0 && readingConfirmAfterMin <= 1440)) {
            p.add(n + "readingConfirmAfterMin must be 0 to 1440 min");
        }
        boolean placesOk = couponPlaces != null && !couponPlaces.isEmpty() && couponPlaces.size() <= 2
                && new HashSet<>(couponPlaces).size() == couponPlaces.size();
        for (int i = 0; placesOk && i < couponPlaces.size(); i++) {
            placesOk = couponPlaces.get(i) != null && !couponPlaces.get(i).trim().isEmpty();
        }
        if (!placesOk) {
            p.add(n + "couponPlaces must name one or two different places (two let one job confirm the mass)");
        }
        if (!(couponMarginMm >= 0 && couponMarginMm <= 20)) {
            p.add(n + "couponMarginMm must be 0 to 20 mm");
        }
        else if (couponSizeMm == null || couponSizeMm.length != 2
                || !(couponSizeMm[0] > 2 * couponMarginMm + 5 && couponSizeMm[0] <= 200)
                || !(couponSizeMm[1] > 2 * couponMarginMm + 5 && couponSizeMm[1] <= 200)) {
            p.add(String.format(Locale.ROOT, "%scouponSizeMm must be [x, y] mm, each more than 5 mm beyond twice the "
                    + "margin (%.1f mm) and at most 200", n, couponMarginMm));
        }
        if (!(couponWindowMm > 0 && couponWindowMm <= 2)) {
            p.add(n + "couponWindowMm must be > 0 and <= 2 mm");
        }
        if (flushDots < 1 || flushDots > 20) {
            p.add(n + "flushDots must be 1 to 20");
        }
        if (ladderDotsPerPhase < 1 || ladderDotsPerPhase > 100) {
            p.add(n + "ladderDotsPerPhase must be 1 to 100");
        }
        if (minLadderDots < 1 || minLadderDots > 500) {
            p.add(n + "minLadderDots must be 1 to 500");
        }
        if (minHops < 1 || minHops > 1000) {
            p.add(n + "minHops must be 1 to 1000");
        }
    }

    private static void fraction(List<String> p, String name, double v) {
        if (!(v > 0 && v < 1)) {
            p.add(name + " must be > 0 and < 1");
        }
    }

    /**
     * The shortened statistics a SIM test may use, refused on the real machine (PUT /api/config off
     * the simulator, design §12.1): one text per value below its floor.
     */
    public List<String> realMachineProblems() {
        List<String> p = new ArrayList<>();
        floor(p, "ladderDotsPerPhase", ladderDotsPerPhase, REAL_MIN_LADDER_DOTS_PER_PHASE);
        floor(p, "minLadderDots", minLadderDots, REAL_MIN_LADDER_DOTS);
        floor(p, "minHops", minHops, REAL_MIN_HOPS);
        floor(p, "weighTargetMg", weighTargetMg, REAL_MIN_WEIGH_TARGET_MG);
        return p;
    }

    private static void floor(List<String> p, String name, double v, double min) {
        if (v < min) {
            p.add(String.format(Locale.ROOT, "padCalibration.%s below %.0f is allowed in the simulator only", name,
                    min));
        }
    }
}
