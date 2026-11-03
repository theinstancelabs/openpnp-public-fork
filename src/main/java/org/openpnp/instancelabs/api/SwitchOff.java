package org.openpnp.instancelabs.api;

import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.pmw.tinylog.Logger;

/**
 * Whether OpenPnP is switching the machine off (BR-52). A web STOP answers within
 * {@link MachineEndpoints#STOP_ANSWER_MS}, but switching off can take longer: a DISABLE_COMMAND line whose ok was lost
 * waits the driver's timeout (OpenPnP's Stop and the engine's abort send on one driver at once, BR-42; 10 s in CI),
 * and a link that closes has its reader joined (up to 3 s). Until it ends the machine still reads started, while the
 * controller's identity is already forgotten (SimGuard, at machineAboutToBeDisabled): a Start answered "already
 * started" without starting the machine again, and the run right after it was refused as "made on the simulator"
 * (CI run 41543474439). A task started then would also send its lines beside the switch-off's.
 *
 * <p>The web STOP's own switch-off counts until its {@code setEnabled(false)} returns, every listener included. One
 * OpenPnP starts itself (its Stop button) counts from machineAboutToBeDisabled while the machine reads started: its
 * last machineDisabled listeners may still run after that (SimGuard keeps a new connection's identity then).</p>
 */
final class SwitchOff extends MachineListener.Adapter {
    static final String REFUSAL = "OpenPnP is switching the machine off (STOP): wait until it reads off, then press "
            + "Start";

    private Machine listened;
    /** The web STOP's setEnabled(false) calls still running. */
    private int byStop;
    /** machineAboutToBeDisabled seen, machineDisabled (or ...Failed) not yet. */
    private boolean announced;

    /** Listens to this machine (idempotent; a reloaded configuration's new machine replaces the old). */
    synchronized void attach(Machine m) {
        if (m == null || m == listened) {
            return;
        }
        if (listened != null) {
            listened.removeListener(this);
        }
        m.addListener(this);
        listened = m;
        ended();
    }

    synchronized void detach() {
        if (listened != null) {
            listened.removeListener(this);
            listened = null;
        }
    }

    /** The web STOP's switch-off, on the calling thread: counted until it has returned. */
    void stop(Machine m) {
        synchronized (this) {
            byStop++;
        }
        try {
            m.setEnabled(false);
        }
        catch (Exception e) {
            Logger.warn(e, "web STOP: disabling the machine failed");
        }
        finally {
            synchronized (this) {
                byStop--;
                notifyAll();
            }
        }
    }

    /**
     * The web STOP's switch-off runs, or one announced reads started still. A listener that throws inside OpenPnP's
     * fire loop can leave {@code announced} set: once the machine reads off it no longer counts.
     */
    synchronized boolean inProgress() {
        return byStop > 0 || (announced && listened != null && listened.isEnabled());
    }

    /** Waits up to {@code ms} for the switch-off in progress to end: false if it still runs then. */
    synchronized boolean awaitEnd(long ms) throws InterruptedException {
        long until = System.nanoTime() + ms * 1_000_000L;
        while (inProgress()) {
            long left = (until - System.nanoTime()) / 1_000_000L;
            if (left <= 0) {
                return false;
            }
            wait(Math.min(left, 50)); // the machine reads off without telling this monitor
        }
        return true;
    }

    @Override
    public synchronized void machineAboutToBeDisabled(Machine machine, String reason) {
        announced = true;
    }

    @Override
    public synchronized void machineDisabled(Machine machine, String reason) {
        ended();
    }

    @Override
    public synchronized void machineDisableFailed(Machine machine, String reason) {
        ended();
    }

    private void ended() {
        announced = false;
        notifyAll();
    }
}
