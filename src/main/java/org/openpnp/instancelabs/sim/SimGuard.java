package org.openpnp.instancelabs.sim;

import java.io.File;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import org.openpnp.machine.reference.driver.AbstractReferenceDriver.CommunicationsType;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Driver;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;
import org.pmw.tinylog.Logger;

/**
 * Decides whether the machine is OpenPnP's simulator, so that simulation-only code (the SIM vacuum
 * sensor, the SIM top camera, records made on the simulator) can never act on the real machine.
 *
 * <ul>
 * <li>{@link #isLoopbackOnly}: every driver is a GcodeDriver talking TCP to this computer. A UI
 * label only: a serial-over-TCP bridge on 127.0.0.1 passes it.</li>
 * <li>{@link #isSimulated}, the strict test: loopback only, and the controller identified itself as
 * OpenPnP's {@code GcodeServer} (M115 {@code FIRMWARE_NAME:GcodeServer}) on the current
 * connection. The identity is noted from the M115 lines every engine task collects first, and
 * forgotten whenever the machine is enabled or disabled (a new connection); right after the forget
 * at an enable, what {@link #askAtConnect} set asks the new connection's controller.</li>
 * </ul>
 */
public final class SimGuard {
    /** The M115 identity of org.openpnp.util.GcodeServer (and of RecordingGcodeServer in sim tests). */
    public static final String SIMULATOR_FIRMWARE = "FIRMWARE_NAME:GcodeServer";
    private static final Pattern SIMULATOR = Pattern.compile("FIRMWARE_NAME:GcodeServer(?![A-Za-z0-9_])");
    /** TcpCommunications' pseudo address: an in-process GcodeServer on localhost. */
    static final String GCODE_SERVER_ADDRESS = "GcodeServer";
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /** Machines whose controller said GcodeServer on the current connection. Guarded by SimGuard.class. */
    private static final Map<Machine, Boolean> confirmed = new WeakHashMap<>();
    /** Machines with the forgetting listener. Guarded by SimGuard.class. */
    private static final Map<Machine, Boolean> attached = new WeakHashMap<>();
    /** What asks each machine's controller who it is (M115 lines) at a connect. Guarded by SimGuard.class. */
    private static final Map<Machine, Callable<List<String>>> askers = new WeakHashMap<>();

    private SimGuard() {
    }

    /**
     * The driver's selected communications are TCP to a loopback address: an IP literal, "localhost",
     * or the pseudo address "GcodeServer". Never a name lookup (this is polled by the status).
     * An unused TCP entry beside a selected serial port reads false.
     */
    public static boolean isLoopbackTcp(GcodeDriver driver) {
        if (driver == null || driver.getCommunicationsType() != CommunicationsType.tcp) {
            return false;
        }
        String ip = driver.getIpAddress();
        if (ip == null) {
            return false;
        }
        ip = ip.trim();
        if (ip.equalsIgnoreCase("localhost") || ip.equals(GCODE_SERVER_ADDRESS)) {
            return true;
        }
        if (!IPV4.matcher(ip).matches() && !ip.contains(":")) {
            return false;
        }
        try {
            return InetAddress.getByName(ip).isLoopbackAddress();
        }
        catch (Exception e) {
            return false;
        }
    }

    /** The machine has drivers, and every one is a GcodeDriver on loopback TCP. */
    public static boolean isLoopbackOnly(Machine m) {
        if (m == null || m.getDrivers() == null || m.getDrivers().isEmpty()) {
            return false;
        }
        for (Driver d : m.getDrivers()) {
            if (!(d instanceof GcodeDriver) || !isLoopbackTcp((GcodeDriver) d)) {
                return false;
            }
        }
        return true;
    }

    /** Loopback only, and the controller identified itself as GcodeServer on this connection. */
    public static boolean isSimulated(Machine m) {
        if (!isLoopbackOnly(m)) {
            return false;
        }
        synchronized (SimGuard.class) {
            return Boolean.TRUE.equals(confirmed.get(m));
        }
    }

    /** Every connect or disconnect starts a new connection: its controller must identify itself again. */
    private static final MachineListener FORGET = new MachineListener.Adapter() {
        @Override
        public void machineEnabled(Machine machine) {
            forget(machine);
            noteAtConnect(machine);
        }

        @Override
        public void machineEnableFailed(Machine machine, String reason) {
            forget(machine);
        }

        @Override
        public void machineAboutToBeDisabled(Machine machine, String reason) {
            forget(machine);
        }

        @Override
        public void machineDisabled(Machine machine, String reason) {
            forget(machine);
        }
    };

    /** Forget the identity on every enable and disable of this machine (idempotent). */
    public static void attach(Machine m) {
        synchronized (SimGuard.class) {
            if (m == null || attached.containsKey(m)) {
                return;
            }
            attached.put(m, Boolean.TRUE);
        }
        m.addListener(FORGET);
    }

    /**
     * What asks this machine's controller who it is (M115 lines) right after every connect; null asks
     * nothing. The answer is noted in the forgetting listener, after the last connection's answer is
     * forgotten: OpenPnP fires machine listeners in no fixed order (a set), so a listener of the
     * asker's own could note the answer before it is forgotten.
     */
    public static void askAtConnect(Machine m, Callable<List<String>> ask) {
        if (m == null) {
            return;
        }
        attach(m);
        synchronized (SimGuard.class) {
            if (ask == null) {
                askers.remove(m);
            }
            else {
                askers.put(m, ask);
            }
        }
    }

    private static void noteAtConnect(Machine m) {
        Callable<List<String>> ask;
        synchronized (SimGuard.class) {
            ask = askers.get(m);
        }
        if (ask == null) {
            return;
        }
        try {
            List<String> m115 = ask.call();
            if (m115 != null) {
                noteIdentity(m, m115);
            }
        }
        catch (Exception e) {
            Logger.debug("controller identity not read at connect: {}", e.getMessage());
        }
    }

    /**
     * Notes what the controller answered to M115 on the current connection. Attaches the forgetting
     * listener first, so an identity is never kept across a reconnect.
     */
    public static void noteIdentity(Machine m, List<String> m115Lines) {
        if (m == null) {
            return;
        }
        attach(m);
        boolean sim = isSimulatorIdentity(m115Lines);
        synchronized (SimGuard.class) {
            confirmed.put(m, sim);
        }
    }

    /** One of the lines carries {@code FIRMWARE_NAME:GcodeServer} (as a whole word). */
    public static boolean isSimulatorIdentity(List<String> m115Lines) {
        if (m115Lines == null) {
            return false;
        }
        for (String l : m115Lines) {
            if (l != null && SIMULATOR.matcher(l).find()) {
                return true;
            }
        }
        return false;
    }

    static synchronized void forget(Machine m) {
        confirmed.remove(m);
    }

    /** {@code <configDir>/instancelabs/sim-scene.json}; only the sim scripts write it (into a config copy). */
    public static File sceneFile(Configuration c) {
        return sceneFile(c.getConfigurationDirectory());
    }

    public static File sceneFile(File configDir) {
        return new File(new File(configDir, "instancelabs"), "sim-scene.json");
    }
}
