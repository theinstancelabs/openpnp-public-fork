package org.openpnp.instancelabs.padprofile;

import java.util.ArrayList;
import java.util.List;

/**
 * Pooled fresh evidence of one dose (§1.7, §7.4-§7.6): every fresh record of the dose, pass or fail,
 * with the statistics recomputed on the pooled dots ({@link LadderStats#of}). Plain value class.
 */
public final class DoseEvidence {
    public int steps;
    /** Weighed mass per motor step and per dot (null = not weighed). */
    public Double mgPerStep;
    public Double mgPerDot;
    /** Two fresh readings agree within the confirm tolerance and one is of this dose (§7.5). */
    public boolean massConfirmed;
    public int massReadings;
    /** Blank diameter: mean, coefficient of variation and d95 = mean (1 + 1.65 CV). */
    public Double dMeanMm;
    public Double dCv;
    public Double d95Mm;
    /** Residual area CV after the phase harmonic, its upper bound used by the check. */
    public Double residCv;
    /** Phase statistic with its 1.645 SE (half split or quarter amplitude) and its limit. */
    public Double phaseStat;
    public Double phaseLimit;
    /** Worst record drift over the record (fraction). */
    public Double driftPct;
    public int misses;
    public int hops;
    public int threads;
    public Double landingMeanMm;
    public Double landingSdMm;
    /** Dots laid (found or not). */
    public int n;
    /** A fresh optical record of the dose exists, and its pooled checks pass. */
    public boolean opticalFresh;
    public boolean opticalPass;
    /** Plain-words reasons of every failed optical check. */
    public List<String> failures = new ArrayList<>();

    public DoseEvidence() {
    }

    public DoseEvidence(int steps) {
        this.steps = steps;
    }

    /** True if a measured blank diameter can replace the model's. */
    public boolean hasMeasuredDots() {
        return opticalFresh && d95Mm != null && dMeanMm != null;
    }
}
