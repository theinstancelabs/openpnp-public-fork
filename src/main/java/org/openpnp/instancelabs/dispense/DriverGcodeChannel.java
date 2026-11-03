package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.instancelabs.sim.SimGuard;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.machine.reference.driver.GcodeDriver.CommandType;
import org.pmw.tinylog.Logger;

/**
 * {@link GcodeChannel} over an OpenPnP GcodeDriver: {@code sendCommand()} applies the driver's own
 * flow control (GcodeDriver: wait for the confirmation of each line; GcodeAsyncDriver: its queue),
 * its error regex and its logging. Must be used from the machine thread, except
 * {@link #sendCritical} on the Stop path. Every line passes {@link ControllerCommands#check}, except the
 * N1 probe's lines ({@link #sendProbe}, {@link #collectProbe}), which pass {@link ControllerCommands#checkProbe}.
 */
public class DriverGcodeChannel implements GcodeChannel {
    private final GcodeDriver driver;

    public DriverGcodeChannel(GcodeDriver driver) {
        this.driver = driver;
    }

    @Override
    public boolean isLoopbackSimulator() {
        return SimGuard.isLoopbackTcp(driver);
    }

    /** Prefix of GcodeDriver.bailOnError()'s exception for a pending controller error line. */
    static final String ERROR_RESPONSE = "error response from controller";

    @Override
    public void send(String line, long timeoutMs) throws Exception {
        ControllerCommands.requireAllowed(line);
        driver.sendCommand(line, timeoutMs);
    }

    /**
     * GcodeDriver.sendCommand() calls bailOnError() before it writes: a controller error line that
     * arrived earlier (and was not consumed) makes it throw without sending, and clears the error.
     * A critical line is therefore sent again after such a refusal (up to 3 attempts; every
     * critical line is idempotent). Any other failure (timeout, IO error) is thrown at once.
     */
    @Override
    public String sendCritical(String line, long timeoutMs) throws Exception {
        ControllerCommands.requireAllowed(line);
        List<String> cleared = new ArrayList<>();
        for (int attempt = 1;; attempt++) {
            try {
                driver.sendCommand(line, timeoutMs);
                if (cleared.isEmpty()) {
                    return null;
                }
                String note = "'" + line + "' was refused by a pending controller error and sent again ("
                        + String.join("; ", cleared) + ")";
                Logger.warn("dispense: {}", note);
                return note;
            }
            catch (Exception e) {
                String msg = String.valueOf(e.getMessage());
                if (!msg.contains(ERROR_RESPONSE) || attempt >= 3) {
                    throw e;
                }
                cleared.add(msg);
            }
        }
    }

    /** The N1 probe's lines: {@link ControllerCommands#checkProbe}, otherwise as {@link #send}. */
    @Override
    public void sendProbe(String line, long timeoutMs) throws Exception {
        ControllerCommands.requireProbeAllowed(line);
        driver.sendCommand(line, timeoutMs);
    }

    @Override
    public List<String> collectProbe(String line, long timeoutMs) throws Exception {
        ControllerCommands.requireProbeAllowed(line);
        return collect(line, timeoutMs);
    }

    @Override
    public List<String> sendAndCollect(String line, long timeoutMs) throws Exception {
        ControllerCommands.requireAllowed(line);
        return collect(line, timeoutMs);
    }

    private List<String> collect(String line, long timeoutMs) throws Exception {
        driver.receiveResponses(); // drop stale lines; throws (and clears) a pending controller error
        driver.sendCommand(line, timeoutMs);
        String confirm = driver.getCommand(null, CommandType.COMMAND_CONFIRM_REGEX);
        List<String> out = new ArrayList<>();
        for (GcodeDriver.Line l : driver.receiveResponses(confirm == null ? "^ok.*" : confirm, timeoutMs,
                (r) -> r)) {
            out.add(l.getLine());
        }
        return out;
    }
}
