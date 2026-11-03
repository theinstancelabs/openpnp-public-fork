package org.openpnp.instancelabs.sim;

/**
 * The pad-calibration SIM scene (PROFILE_CALIBRATION_DESIGN.md §11.3) and its negative controls
 * (§11.7), for the SIM tests: the same blocks and variant names as il_sim_scene.py ({@code --paste
 * --coupons --balance MODE --bench-blank}, {@code --variant NAME}).
 */
public final class PadcalScenes {
    /** The two weigh coupons of §11.3 (machine mm; z = their top, an N1 contact Z). */
    public static final double[] COUPON1_RECT = {330, 40, 370, 70}, COUPON2_RECT = {330, 75, 370, 105};
    public static final double COUPON_Z = 5.80, COUPON1_MG = 4100, COUPON2_MG = 4060;
    /**
     * ripple-0.45 pins the physical offset so that the engine's 298° start (count based) meets the
     * ripple at its worst for half turns: φ0 + 400 − ψ ≡ 0 (mod 800 steps), ψ = 299° (1328.9 steps).
     * Drawn, the offset could put the start where the two half-turn phases are equal.
     */
    public static final int RIPPLE_WORST_OFFSET_STEPS = 405;
    /**
     * cv-0.20 is §11.7's "cv 0.16" row. 0.16 leaves the tip bead out: transferRatio 0.75 averages each
     * dot's mass noise with the dots before it (× 0.775), so 0.16 gives a residual area CV of 0.123,
     * and that passes the repeat check on about one ladder in five. 0.20 gives 0.154, close to the
     * 0.147 that §11.7 meant (√((0.77·0.16)² + 0.08²) without the bead).
     */
    public static final double REPEAT_FAIL_CV = 0.20;

    /** §11.7: the scene variants and the two that are a test action, with the outcome the design expects. */
    public enum Variant {
        REPORT_COUNT_OFF("report-count-off", true, "the quarter-turn block refuses (I11 count text)"),
        RIPPLE_045("ripple-0.45", true, "the phase checks fail; J and Q stay provisional"),
        THREAD_ZERO_065("thread-zero-0.65", true, "quarter-turn threads > k95: Q stays provisional, text names threads"),
        CV_020("cv-0.20", true, "the repeat check fails"),
        LANDING_BIAS_02("landing-bias-0.2", true, "the landing check fails (points to the needle offset)"),
        COUPON1_OFF_15("coupon1-off-1.5", true, "step 2 refuses (Coupon 1 is not at its place: its edge is 1.5 mm "
                + "inside the weigh grid's margin)"),
        NEEDLE_GAP_025("needle-gap-0.25", true, "misses: transfer fails"),
        RECONNECT("reconnect", false, "the run refuses the 400 block (I11) until the start angle is confirmed; a new "
                + "job cannot reuse the old quarter optical record"),
        IDLE_DEAD_0("idle-dead-0", true, "the purge absorbs the dead first dots: the ladder still passes (P7 reported)"),
        DWELL_50_ONE_TURN("dwell-50-one-turn", false, "I6-feed refuses before anything moves");

        public final String key;
        /** False: not a scene change (a reconnect between the steps; profile dwellMs 50 with one-turn dots). */
        public final boolean sceneChange;
        public final String expected;

        Variant(String key, boolean sceneChange, String expected) {
            this.key = key;
            this.sceneChange = sceneChange;
            this.expected = expected;
        }

        public static Variant of(String key) {
            for (Variant v : values()) {
                if (v.key.equals(key)) {
                    return v;
                }
            }
            throw new IllegalArgumentException("no padcal variant " + key);
        }
    }

    private PadcalScenes() {
    }

    /** Adds the §11.3 blocks to a scene: the paste model, two coupons, a balance, the bench blank colour. */
    public static SimScene padcal(SimScene s, String balanceMode) {
        s.paste = new SimScene.Paste();
        s.coupons.clear();
        s.coupons.add(coupon("coupon1", COUPON1_RECT, COUPON1_MG));
        s.coupons.add(coupon("coupon2", COUPON2_RECT, COUPON2_MG));
        s.balance = new SimScene.Balance();
        s.balance.mode = balanceMode;
        s.blankBgr = new int[] {206, 25, 0};
        return s;
    }

    static SimScene.Coupon coupon(String place, double[] rect, double mg) {
        SimScene.Coupon k = new SimScene.Coupon();
        k.place = place;
        k.rect = rect.clone();
        k.z = COUPON_Z;
        k.massMg = mg;
        return k;
    }

    /**
     * Applies a scene variant (a test action variant leaves the scene alone).
     *
     * @param configNeedleZOffsetMm the engine's needle Z offset: needle-gap-0.25 puts the true one 0.15 mm
     *        lower, so the commanded 0.10 gap is 0.25 in truth
     */
    public static SimScene apply(SimScene s, Variant v, double configNeedleZOffsetMm) {
        switch (v) {
            case REPORT_COUNT_OFF:
                s.paste.reportCount = false;
                break;
            case RIPPLE_045:
                s.paste.ripple = 0.45;
                s.paste.offsetSteps = RIPPLE_WORST_OFFSET_STEPS;
                break;
            case THREAD_ZERO_065:
                s.paste.threadZeroMg = 0.65;
                break;
            case CV_020:
                s.paste.cv = REPEAT_FAIL_CV;
                break;
            case LANDING_BIAS_02:
                s.paste.landingBiasMm = new double[] {0.2, 0};
                break;
            case COUPON1_OFF_15:
                SimScene.Coupon k = s.coupon("coupon1");
                k.rect = new double[] {k.rect[0] + 1.5, k.rect[1], k.rect[2] + 1.5, k.rect[3]};
                break;
            case NEEDLE_GAP_025:
                s.paste.needleZOffsetMm = configNeedleZOffsetMm - 0.15;
                break;
            case IDLE_DEAD_0:
                s.paste.idleDeadS = 0;
                break;
            default:
                break;
        }
        return s;
    }
}
