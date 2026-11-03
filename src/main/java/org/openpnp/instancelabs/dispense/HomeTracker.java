package org.openpnp.instancelabs.dispense;

import java.util.function.LongSupplier;

import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;

/**
 * When the machine was connected, disabled and homed, so Register board, the blank probe and a board
 * run can home by themselves when a home is needed (setting {@link DispenseConfig#homeBeforeRun}), and
 * Z data measured before the last home are noticed ({@link ZCheck}). Nothing else homes on its own.
 *
 * <p>"Needed" = not homed, a STOP / disable / reconnect since the last home (OpenPnP reconnects with a
 * disable then an enable), or a STOP that came during the last home. A home started outside the
 * engine (OpenPnP's own Home button) has no known start and counts as confirmed when the machine is
 * enabled. The service may start on a machine that is already homed: that home has no known time,
 * so {@link #getLastHomeAtMs()} stays null, which neither forces a home nor makes Z data stale.</p>
 *
 * <p>Fields are epoch ms from an injectable clock.</p>
 */
public final class HomeTracker extends MachineListener.Adapter {
    /** Home duration assumed until one was measured (s; shown with "about"). */
    public static final double DEFAULT_HOME_ESTIMATE_S = 25;

    /** Whether a task must home first, and why. */
    public static final class Plan {
        public boolean needed;
        /** always | home-unconfirmed | stopped | not-homed | homed */
        public String whyCode;
        /** Plain words, no clock times (the page adds the time from {@link #sinceMs}). */
        public String why;
        public Long sinceMs;
        public String policy;
        /** The last home seen ({@link HomeTracker#getLastHomeAtMs()}). */
        public Long homedAtMs;
        /** Seconds a home takes: the last measured one, else {@link #DEFAULT_HOME_ESTIMATE_S}. */
        public double estimateS;
    }

    private final LongSupplier clock;
    private final long serviceStartMs;
    private final boolean homedAtStart;
    private volatile Long connectedAtMs;
    private volatile Long disabledAtMs;
    private volatile Long homeStartedAtMs;
    private volatile Long lastHomeAtMs;
    private volatile boolean homeUnconfirmed;
    private volatile Long lastHomeMs;
    private volatile ZCheck.Result zCheck;

    /**
     * @param clock epoch ms (null = the system clock)
     * @param homedAtStart whether OpenPnP was homed when the service started
     */
    public HomeTracker(LongSupplier clock, boolean homedAtStart) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.serviceStartMs = this.clock.getAsLong();
        this.homedAtStart = homedAtStart;
    }

    @Override
    public void machineEnabled(Machine machine) {
        connectedAtMs = clock.getAsLong();
    }

    @Override
    public void machineDisabled(Machine machine, String reason) {
        disabledAtMs = clock.getAsLong();
    }

    @Override
    public void machineHomed(Machine machine, boolean isHomed) {
        if (!isHomed) {
            return;
        }
        long now = clock.getAsLong();
        Long started = homeStartedAtMs;
        Long disabled = disabledAtMs;
        boolean unconfirmed = (machine != null && !machine.isEnabled())
                || (started != null && disabled != null && disabled > started);
        homeUnconfirmed = unconfirmed;
        if (!unconfirmed && started != null) {
            lastHomeMs = now - started;
        }
        lastHomeAtMs = now;
        homeStartedAtMs = null;
    }

    /** Called right before every engine or API home (the next {@code machineHomed(true)} ends it). */
    public void homeStarted() {
        homeStartedAtMs = clock.getAsLong();
    }

    /**
     * Called after every engine or API home, also one a STOP or a failure ended: OpenPnP's home then throws
     * and never reports homed, and its start must not stay behind, or the next clean home (OpenPnP's own Home
     * button) would be taken for one a stop came during (review 1).
     */
    public void homeEnded() {
        homeStartedAtMs = null;
    }

    /** Whether a task must home first under {@code policy} ("whenNeeded" | "always"), and why. */
    public Plan plan(boolean enabled, boolean homed, String policy) {
        Plan p = new Plan();
        p.policy = policy;
        p.homedAtMs = lastHomeAtMs;
        Long lh = lastHomeAtMs;
        long lastHome = lh != null ? lh : (homedAtStart ? serviceStartMs : Long.MIN_VALUE);
        Long conn = connectedAtMs, dis = disabledAtMs;
        long since = Math.max(conn == null ? Long.MIN_VALUE : conn, dis == null ? Long.MIN_VALUE : dis);
        Long ms = lastHomeMs;
        p.estimateS = ms != null ? ms / 1000.0 : DEFAULT_HOME_ESTIMATE_S;
        if (DispenseConfig.HOME_ALWAYS.equals(policy)) {
            p.needed = true;
            p.whyCode = "always";
            p.why = "the setting is: always home first";
        }
        else if (homeUnconfirmed) {
            p.needed = true;
            p.whyCode = "home-unconfirmed";
            p.why = "a stop came during the last home";
            p.sinceMs = dis;
        }
        else if (since > lastHome) {
            p.needed = true;
            p.whyCode = "stopped";
            p.why = "stopped or reconnected since the last home";
            p.sinceMs = since;
        }
        else if (!homed) {
            p.needed = true;
            p.whyCode = "not-homed";
            p.why = "the machine is not homed";
        }
        else {
            p.needed = false;
            p.whyCode = "homed";
            p.why = "homed";
            p.sinceMs = lh;
        }
        return p;
    }

    /** When OpenPnP last enabled (connected to) the controller, or null (I11). */
    public Long getConnectedAtMs() {
        return connectedAtMs;
    }

    public Long getDisabledAtMs() {
        return disabledAtMs;
    }

    public Long getHomeStartedAtMs() {
        return homeStartedAtMs;
    }

    /** The last {@code machineHomed(true)} seen since the service started, or null. */
    public Long getLastHomeAtMs() {
        return lastHomeAtMs;
    }

    /**
     * The Z frame of data measured now ({@link ZCheck}): the last home seen, or the service start when
     * none was seen since (the home in effect then, of unknown time). No home lies between data with
     * the same value; a home between others may have moved Z.
     */
    public long frameHomeAtMs() {
        Long lh = lastHomeAtMs;
        return lh != null ? lh : serviceStartMs;
    }

    public boolean isHomeUnconfirmed() {
        return homeUnconfirmed;
    }

    /** Duration of the last confirmed home with a known start (ms), or null. */
    public Long getLastHomeMs() {
        return lastHomeMs;
    }

    public long getServiceStartMs() {
        return serviceStartMs;
    }

    public boolean isHomedAtStart() {
        return homedAtStart;
    }

    /** The latest Z check (memory only), or null. */
    public ZCheck.Result getZCheck() {
        return zCheck;
    }

    public void setZCheck(ZCheck.Result r) {
        zCheck = r;
    }
}
