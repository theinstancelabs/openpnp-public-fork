package org.openpnp.instancelabs.padprofile;

/**
 * The stated paste model (PROFILE_CALIBRATION_DESIGN.md §1.1-§1.5), fitted on steady-state
 * normal-lift dots on the blank at 18 psi only. Pure arithmetic: mass per screw step, the screw
 * phase ripple, and a two-regime footprint law with a band between the measured points.
 *
 * <p>Footprint (§1.4): up to about a half turn the deposit is a disc of constant height set by the
 * needle (area ∝ mass); beyond it the paste builds up (area ∝ m^0.43). The law is the piecewise
 * power law through the three points; between two points the chord is the lower edge of the band and
 * the next chord extended the upper edge (log A is concave in log m). Geometry checks use the upper
 * edge {@link #dHiMm(int)}.</p>
 */
public final class DoseModel {
    /** Motor steps per screw revolution (D/DispenseConfig.java bStepsPerRev). */
    public static final int STEPS_PER_REV = 1600;
    /** Marlin steps per degree of B (D/DispenseConfig.java): 400 steps = 90.09 deg. Display only. */
    public static final double B_STEPS_PER_DEG = 4.44;
    /** Mass per revolution at 18 psi: 169 mg on 160 half-turn pads (runs/p18_board1.json). */
    public static final double M_REV_MG = 2.11;
    public static final int QUARTER = 400;
    public static final int HALF = 800;
    public static final int ONE_TURN = 1600;
    /** R1: the doses a dot may have, in steps (thirds and sixths are not whole steps). */
    public static final int[] ALLOWED_STEPS = {QUARTER, HALF, ONE_TURN};

    // §1.4 source points (median of the steady normal-lift dots) and what was left out

    public static final String SIXTH_SOURCE = "runs/p18_split_blank2/measured.csv dots 7-9; excluded: dots 4-6 "
            + "(carry-over after the half-turn block), dots 10-16 (slow lift), runs/p18_split_blank (1 h idle start-up)";
    public static final String HALF_SOURCE = "runs/p18_half/meas.txt dots 4-28 without dot 15; excluded: dot 15 "
            + "(a phase dot), dots 0-3 (start-up)";
    public static final String ONE_TURN_SOURCE = "runs/p18_blank/meas.txt dots 6-38; excluded: dots 4-5 (start-up blobs)";
    public static final int SIXTH_N = 3;
    public static final int HALF_N = 24;
    public static final int ONE_TURN_N = 33;

    /**
     * Sixth turn (60 deg net), mass inferred: runs/p18_split_blank2/measured.csv dots 7-9 (0.359,
     * 0.344, 0.413 mm²). Excluded: dots 4-6 (carry-over after the half-turn block), 10-16 (slow
     * lift), all of runs/p18_split_blank (1 h idle; its pairs 11/12 and 14/15 are one thread).
     */
    public static final double SIXTH_MASS_MG = M_REV_MG / 6;
    public static final double SIXTH_AREA_MM2 = 0.359;
    /**
     * Half turn (180.18 deg net), weighed 1.056 mg: runs/p18_half/meas.txt dots 4-28 without dot 15
     * (a phase dot); dots 0-3 are the start-up. n 24, CV_d 3.9 %.
     */
    public static final double HALF_MASS_MG = 1.056;
    public static final double HALF_DIAMETER_MM = 1.160;
    /** One turn, 2 x half (positive displacement): runs/p18_blank/meas.txt dots 6-38 (4-5 start-up). */
    public static final double ONE_TURN_MASS_MG = M_REV_MG;
    public static final double ONE_TURN_DIAMETER_MM = 1.345;
    public static final double HALF_AREA_MM2 = areaOfDiameter(HALF_DIAMETER_MM);
    public static final double ONE_TURN_AREA_MM2 = areaOfDiameter(ONE_TURN_DIAMETER_MM);
    /** Log-log chord exponents: 0.982 below the half turn, 0.428 above. */
    public static final double EXPONENT_LOW = Math.log(HALF_AREA_MM2 / SIXTH_AREA_MM2)
            / Math.log(HALF_MASS_MG / SIXTH_MASS_MG);
    public static final double EXPONENT_HIGH = Math.log(ONE_TURN_AREA_MM2 / HALF_AREA_MM2)
            / Math.log(ONE_TURN_MASS_MG / HALF_MASS_MG);

    // §1.2 screw ripple

    /** The two p18_half block pairs solve to psi ≈ 299 deg and beta·a·sinc(half) = 0.072: a = 0.113 / beta. */
    public static final double RIPPLE_A_TIMES_BETA = 0.113;
    /** a for beta between the two chords (0.43 .. 0.98): 0.11 .. 0.26. */
    public static final double RIPPLE_A_LOW = 0.11;
    public static final double RIPPLE_A_HIGH = 0.26;
    public static final double RIPPLE_PHASE_DEG = 299;

    private DoseModel() {
    }

    public static double areaOfDiameter(double d) {
        return Math.PI / 4 * d * d;
    }

    public static double diameterOfArea(double a) {
        return Math.sqrt(4 * a / Math.PI);
    }

    /** R1 for a tip: a quarter, half or one turn, at or above the tip's smallest dose. */
    public static boolean isAllowed(int steps, Tip tip) {
        boolean listed = steps == QUARTER || steps == HALF || steps == ONE_TURN;
        return listed && (tip == null || steps >= tip.minDotSteps);
    }

    /** True if a dose is not a whole number of revolutions (its block needs the start angle, R2). */
    public static boolean isPartial(int steps) {
        return steps % STEPS_PER_REV != 0;
    }

    /** Positive displacement: the model mass of a dot of {@code steps} (M_rev · s / 1600). */
    public static double massMg(int steps) {
        return M_REV_MG * steps / STEPS_PER_REV;
    }

    /** Net turn in degrees of B. */
    public static double netDeg(int steps, double bStepsPerDeg) {
        return steps / (bStepsPerDeg > 0 ? bStepsPerDeg : B_STEPS_PER_DEG);
    }

    /** Footprint band at a mass on the blank: {lower, upper} in mm² (§1.4). */
    public static double[] areaBand(double massMg) {
        double m = massMg;
        if (m <= SIXTH_MASS_MG) {
            return new double[] {SIXTH_AREA_MM2 * m / SIXTH_MASS_MG,
                    SIXTH_AREA_MM2 * Math.pow(m / SIXTH_MASS_MG, EXPONENT_LOW)};
        }
        if (m <= HALF_MASS_MG) {
            return new double[] {SIXTH_AREA_MM2 * Math.pow(m / SIXTH_MASS_MG, EXPONENT_LOW),
                    HALF_AREA_MM2 * Math.pow(m / HALF_MASS_MG, EXPONENT_HIGH)};
        }
        if (m <= ONE_TURN_MASS_MG) {
            return new double[] {HALF_AREA_MM2 * Math.pow(m / HALF_MASS_MG, EXPONENT_HIGH),
                    HALF_AREA_MM2 * Math.pow(m / HALF_MASS_MG, EXPONENT_LOW)};
        }
        return new double[] {ONE_TURN_AREA_MM2, ONE_TURN_AREA_MM2 * Math.pow(m / ONE_TURN_MASS_MG, EXPONENT_HIGH)};
    }

    /** The adopted law (the lower chord): A = 0.359 (m/0.352)^0.982 up to the half turn, then 1.057 (m/1.056)^0.428. */
    public static double areaMm2(double massMg) {
        if (massMg <= HALF_MASS_MG) {
            return SIXTH_AREA_MM2 * Math.pow(massMg / SIXTH_MASS_MG, EXPONENT_LOW);
        }
        return HALF_AREA_MM2 * Math.pow(massMg / HALF_MASS_MG, EXPONENT_HIGH);
    }

    /** Lower edge of the blank diameter of a dose (the measured point for 800 and 1600). */
    public static double dLoMm(int steps) {
        if (steps == HALF) {
            return HALF_DIAMETER_MM;
        }
        if (steps == ONE_TURN) {
            return ONE_TURN_DIAMETER_MM;
        }
        return diameterOfArea(areaBand(massMg(steps))[0]);
    }

    /** Upper edge of the blank diameter of a dose: 400 -> 1.00, 800 -> 1.16, 1600 -> 1.345 mm. */
    public static double dHiMm(int steps) {
        if (steps == HALF) {
            return HALF_DIAMETER_MM;
        }
        if (steps == ONE_TURN) {
            return ONE_TURN_DIAMETER_MM;
        }
        return diameterOfArea(areaBand(massMg(steps))[1]);
    }

    /** Mean height V/A of a deposit (mm): 0.261 sixth, 0.266 half, 0.395 one turn. */
    public static double meanHeightMm(double massMg, double areaMm2) {
        return massMg / Limits.PASTE_DENSITY_MG_MM3 / areaMm2;
    }

    /**
     * First-harmonic ripple factor of a turn of {@code steps}: sinc(theta / 2) = 0.90 quarter, 0.64
     * half, 0.30 three quarters, 0 whole (§1.2). A pad's consecutive dots count as one turn n·s.
     */
    public static double rippleFactor(int steps) {
        double x = Math.PI * steps / STEPS_PER_REV;
        if (Math.abs(Math.sin(x)) < 1e-12) {
            return x == 0 ? 1 : 0;
        }
        return Math.sin(x) / x;
    }

    /** The ripple amplitude a for a local footprint exponent beta (a = 0.113 / beta). */
    public static double rippleAmplitude(double beta) {
        return RIPPLE_A_TIMES_BETA / beta;
    }

    /** Per-pad spread from the screw angle, % : 100 · a · sinc(T/2) for a = 0.11 and 0.26. */
    public static double[] padRipplePct(int padSteps) {
        double f = rippleFactor(padSteps);
        return new double[] {100 * RIPPLE_A_LOW * f, 100 * RIPPLE_A_HIGH * f};
    }

    /** Adjective of a dose: quarter-turn, half-turn, one-turn (§3.4). */
    public static String turnName(int steps) {
        String n = turnNoun(steps);
        return n.replace(' ', '-');
    }

    /** Noun of a dose: quarter turn, half turn, one turn. */
    public static String turnNoun(int steps) {
        if (steps * 8 == STEPS_PER_REV) {
            return "eighth turn";
        }
        if (steps * 4 == STEPS_PER_REV) {
            return "quarter turn";
        }
        if (steps * 2 == STEPS_PER_REV) {
            return "half turn";
        }
        if (steps == STEPS_PER_REV) {
            return "one turn";
        }
        if (steps == 2 * STEPS_PER_REV) {
            return "two turn";
        }
        return steps + " step";
    }

    /** "a quarter turn", "a half turn", "one turn". */
    public static String turnWithArticle(int steps) {
        String n = turnNoun(steps);
        return n.startsWith("one") || n.startsWith("two") ? n : "a " + n;
    }
}
