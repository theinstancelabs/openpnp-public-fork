package org.openpnp.instancelabs.dispense;

/**
 * The balance a pad calibration weighs its coupons on (PROFILE_CALIBRATION_DESIGN.md §10): manual
 * entry by default ({@link ManualBalance}: the operator types the readings), a SIM balance in the
 * simulator. No serial driver: the bench balance's data port is unknown (REAL_MACHINE_TODO).
 *
 * <p>Readings are absolute (no tare): a coupon is weighed with its paste and again wiped, so an
 * auto-zeroing or auto-off balance cannot corrupt the difference.</p>
 */
public interface Balance {
    /** "manual" | "sim" (later "serial"). */
    String kind();

    /** True: the job reads the balance by itself; false: the operator types the readings. */
    boolean isConnected();

    /** Readability (mg): 1.0 for the bench's U.S. Solid 110 g × 0.001 g. */
    double resolutionMg();

    /** Waits up to timeoutMs for a stable reading (absolute, no tare); the last one if it never settles. */
    Reading read(long timeoutMs) throws Exception;

    /** One reading of the display. */
    final class Reading {
        public double mg;
        public boolean stable;
        public long atMs;

        public Reading() {
        }

        public Reading(double mg, boolean stable, long atMs) {
            this.mg = mg;
            this.stable = stable;
            this.atMs = atMs;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "Reading[%.3f mg%s]", mg, stable ? "" : " unstable");
        }
    }
}
