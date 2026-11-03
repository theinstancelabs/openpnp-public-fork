package org.openpnp.instancelabs.sim;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.openpnp.instancelabs.dispense.Balance;
import org.openpnp.model.Configuration;

/**
 * The SIM balance (PROFILE_CALIBRATION_DESIGN.md §11.6): the coupon on the pan weighs its
 * {@code massMg} plus the SIM paste on it; a reading is round(mass + N(0, noiseMg), resolutionMg),
 * stable once {@code settleS} have passed since the load. The pan lives in the scene's
 * {@link SimPaste.Store}, so every instance of one scene sees the same coupon on it.
 *
 * <ul>
 * <li>{@code connected}: the job reads it; the emulated human ({@link #weighWipeWeigh}) puts the coupon
 * on, the job reads, the human wipes it and puts it back, the job reads again.</li>
 * <li>{@code display}: the job prompts; the operator (a test) loads a coupon ({@link #load}), reads the
 * display ({@link #display}), wipes the coupon (SimPaste wipe) and reads again.</li>
 * </ul>
 *
 * <p>Refuses every reading unless the machine is confirmed as the simulator ({@link SimGuard#isSimulated}).</p>
 */
public final class SimBalance implements Balance {
    public static final String KIND = "sim";
    /** How often {@link #read} looks again while the reading settles (ms). */
    static final long POLL_MS = 50;

    /** Thread.sleep, or a test's clock. */
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private final Supplier<SimScene> scenes;
    private final SimPaste.Store store;
    private final BooleanSupplier simulated;
    private final LongSupplier clockMs;
    private final Sleeper sleeper;

    public SimBalance(Supplier<SimScene> scenes, SimPaste.Store store, BooleanSupplier simulated, LongSupplier clockMs,
            Sleeper sleeper) {
        this.scenes = scenes;
        this.store = store;
        this.simulated = simulated;
        this.clockMs = clockMs;
        this.sleeper = sleeper;
    }

    /**
     * The SIM balance of this configuration: null unless the machine is confirmed as the simulator
     * and the sim scene has a {@code balance} block.
     */
    public static SimBalance forConfiguration(Configuration c) {
        if (c == null || !SimGuard.isSimulated(c.getMachine()) || !SimGuard.sceneFile(c).isFile()) {
            return null;
        }
        SimScene.Source source = SimScene.Source.forConfiguration(c);
        SimScene sc = source.get();
        if (sc == null || sc.balance == null) {
            return null;
        }
        return new SimBalance(source, SimPaste.Store.forFile(SimGuard.sceneFile(c)),
                () -> SimGuard.isSimulated(c.getMachine()), System::currentTimeMillis, Thread::sleep);
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean isConnected() {
        return SimScene.BALANCE_MODES[1].equals(settings().mode);
    }

    /** "display" | "connected". */
    public String mode() {
        return settings().mode;
    }

    @Override
    public double resolutionMg() {
        return settings().resolutionMg;
    }

    /** Puts the coupon of a place on the pan (null: takes it off); the reading settles again. */
    public void load(String place) {
        SimScene sc = scene();
        if (place != null && sc.coupon(place) == null) {
            throw new IllegalArgumentException("no SIM coupon on place '" + place + "'");
        }
        store.balanceLoad(place, clockMs.getAsLong());
    }

    /** The place whose coupon is on the pan, or null. */
    public String loaded() {
        return store.balancePlace();
    }

    /** What the pan carries (mg, exact): the coupon and its paste. */
    public double massOnPanMg() {
        String place = store.balancePlace();
        if (place == null) {
            return 0;
        }
        SimScene.Coupon k = scene().coupon(place);
        return k == null ? 0 : k.massMg + store.massMg(place);
    }

    /** The display now, settled or not. */
    public Reading display() {
        requireSimulated();
        SimScene.Balance b = settings();
        long now = clockMs.getAsLong();
        double v = massOnPanMg() + store.balanceNoise(b.seed) * b.noiseMg;
        boolean stable = store.balancePlace() == null || now - store.balanceLoadedAtMs() >= b.settleS * 1000;
        return new Reading(Math.round(v / b.resolutionMg) * b.resolutionMg, stable, now);
    }

    @Override
    public Reading read(long timeoutMs) throws Exception {
        requireSimulated();
        long deadline = clockMs.getAsLong() + Math.max(0, timeoutMs);
        while (true) {
            long now = clockMs.getAsLong();
            long settledAt = store.balanceLoadedAtMs() + Math.round(settings().settleS * 1000);
            if (store.balancePlace() == null || now >= settledAt || now >= deadline) {
                return display();
            }
            sleeper.sleep(Math.max(1, Math.min(POLL_MS, Math.min(settledAt, deadline) - now)));
        }
    }

    /**
     * The emulated human of a connected balance, and the job's two readings: put the coupon on, read;
     * take it off and wipe it, put it back on, read; take it off. Returns {with paste, wiped}.
     */
    public Reading[] weighWipeWeigh(String place, long timeoutMs) throws Exception {
        load(place);
        Reading full = read(timeoutMs);
        load(null);
        store.wipe(place);
        load(place);
        Reading empty = read(timeoutMs);
        load(null);
        return new Reading[] {full, empty};
    }

    private void requireSimulated() {
        if (!simulated.getAsBoolean()) {
            throw new IllegalStateException("the SIM balance reads only on the simulator: the simulator is not "
                    + "confirmed on this connection");
        }
    }

    private SimScene scene() {
        SimScene sc = scenes.get();
        if (sc == null) {
            throw new IllegalStateException("no sim scene");
        }
        return sc;
    }

    private SimScene.Balance settings() {
        SimScene.Balance b = scene().balance;
        if (b == null) {
            throw new IllegalStateException("the sim scene has no balance block");
        }
        return b;
    }
}
