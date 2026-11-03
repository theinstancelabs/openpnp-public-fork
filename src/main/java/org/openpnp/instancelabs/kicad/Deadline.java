package org.openpnp.instancelabs.kicad;

import java.util.function.LongSupplier;

/**
 * One wall-clock budget for a whole preview or save (design §2.2): the reader, the parser, the board frame, the rules
 * and the art share it, so a slow step cannot restart the clock.
 */
public final class Deadline {
    public static final long DEFAULT_MS = 20_000;

    private final LongSupplier clockMs;
    private final long budgetMs;
    private final long end;

    public Deadline(long budgetMs) {
        this(budgetMs, () -> System.nanoTime() / 1_000_000L);
    }

    /** @param clockMs a monotonic clock in milliseconds (tests inject their own) */
    public Deadline(long budgetMs, LongSupplier clockMs) {
        this.clockMs = clockMs;
        this.budgetMs = budgetMs;
        this.end = clockMs.getAsLong() + budgetMs;
    }

    /** A budget that never runs out (tools and tests that do not test time). */
    public static Deadline none() {
        return new Deadline(Long.MAX_VALUE / 4);
    }

    public long remainingMs() {
        return end - clockMs.getAsLong();
    }

    public boolean expired() {
        return remainingMs() < 0;
    }

    /** @throws SExprException (limit) once the budget is spent */
    public void check() throws SExprException {
        if (expired()) {
            long s = budgetMs / 1000;
            String t = budgetMs % 1000 == 0 ? Long.toString(s)
                    : String.format(java.util.Locale.ROOT, "%.1f", budgetMs / 1000.0);
            throw SExprException.limit(0, "took longer than " + t + " s");
        }
    }
}
