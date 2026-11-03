package org.openpnp.instancelabs.dispense;

import java.util.List;

/** Line-oriented, flow-controlled link to the controller. */
public interface GcodeChannel {
    /** Sends one line and returns when the driver's flow control accepted it. */
    void send(String line, long timeoutMs) throws Exception;

    /** Sends one line and returns every response line up to and including the confirmation. */
    List<String> sendAndCollect(String line, long timeoutMs) throws Exception;

    /**
     * Sends a stop / vent line that must reach the controller (valve OFF, pump OFF, M410, M18,
     * watchdog disarm). Implementations make sure a stale controller error does not swallow it:
     * GcodeDriver refuses the next line, without writing it, when an error line arrived earlier
     * and nobody consumed it (bailOnError).
     *
     * @return a note when an earlier controller error had to be cleared first, otherwise null
     */
    default String sendCritical(String line, long timeoutMs) throws Exception {
        send(line, timeoutMs);
        return null;
    }

    /**
     * Sends one line of the N1 vacuum probe ({@link DispenseExecutor#sendGated} with scope PROBE):
     * implementations guard it with {@link ControllerCommands#checkProbe} instead of
     * {@link ControllerCommands#check}. The default sends it as any line.
     */
    default void sendProbe(String line, long timeoutMs) throws Exception {
        send(line, timeoutMs);
    }

    /** As {@link #sendProbe}, returning every response line up to the confirmation (the M261 reply). */
    default List<String> collectProbe(String line, long timeoutMs) throws Exception {
        return sendAndCollect(line, timeoutMs);
    }

    /**
     * True only for a simulated controller on this computer (a TCP link to a loopback address): a
     * real Marlin reports the B step count with M114, OpenPnP's simulator does not.
     */
    default boolean isLoopbackSimulator() {
        return false;
    }
}
