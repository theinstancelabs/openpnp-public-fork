package org.openpnp.machine.reference.driver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.instancelabs.dispense.SimMachine;

/**
 * Instance Labs (BR-93): a serial port named by its device path (machine.xml names the board's /dev/serial/by-id/…
 * link) is gone when that path is: the controller left USB.
 */
public class GcodeDriverLinkLossTest {
    @Test
    public void aSerialDevicePathThatVanishedIsGone(@TempDir Path dir) throws Exception {
        assumeTrue(File.separatorChar == '/', "device paths are checked on Unix only");
        File dev = dir.resolve("usb-STMicroelectronics_MARLIN_OPULO_LUMEN_REV5-if00").toFile();
        assertTrue(dev.createNewFile());
        assertFalse(GcodeDriver.devicePathGone(dev.getPath()), "present");
        assertTrue(dev.delete());
        assertTrue(GcodeDriver.devicePathGone(dev.getPath()), "gone");
        assertFalse(GcodeDriver.devicePathGone("COM3"), "a port named without a path is never checked");
        assertFalse(GcodeDriver.devicePathGone("ttyACM0"));
        assertFalse(GcodeDriver.devicePathGone("\\\\.\\COM3"), "#53 re-review: Windows' device name is no path");
        assertFalse(GcodeDriver.devicePathGone(null));
    }

    /**
     * #53 review MAJOR 1: OpenPnP's exit closes a link kept open across the deployer's STOP (jSerialComm's shutdown hook
     * closes every port, and the reader then fails on "Read error."): that is no loss, so no restart journals a possible
     * reset. jSerialComm runs its own hooks first, which set this flag.
     */
    @Test
    public void aLinkThatEndsAsTheJvmShutsDownIsNoLoss() throws Exception {
        SimMachine sim = new SimMachine();
        try {
            SerialPortCommunications.jvmShuttingDown = true;
            sim.server.resetLink();
            Thread.sleep(300);
            assertNull(sim.driver.getLinkLoss());
        }
        finally {
            SerialPortCommunications.jvmShuttingDown = false;
            sim.close();
        }
    }

    /**
     * #53 review MAJOR 2: the USB check looks at the device the connect opened (BR-47 may open another than the
     * configured name: the board's serial number, its description), never at the configured name.
     */
    @Test
    public void theUsbCheckLooksAtTheOpenedDevice(@TempDir Path dir) throws Exception {
        assumeTrue(File.separatorChar == '/', "device paths are checked on Unix only");
        SimMachine sim = new SimMachine(false); // a configuration for the driver below
        try {
            GcodeDriver d = new GcodeDriver();
            d.setCommunicationsType(AbstractReferenceDriver.CommunicationsType.serial);
            d.getSerial().setPortName(dir.resolve("usb-old-board-if00").toString()); // configured, not there
            d.connected = true;
            assertNull(d.getLinkLoss(), "nothing opened: the configured name is not checked");
            File opened = dir.resolve("ttyACM1").toFile();
            assertTrue(opened.createNewFile());
            java.lang.reflect.Field f = SerialPortCommunications.class.getDeclaredField("openedRealPath");
            f.setAccessible(true);
            f.set(d.getSerial(), opened.getPath());
            assertNull(d.getLinkLoss(), "the opened device is there");
            assertTrue(opened.delete());
            GcodeDriver.LinkLoss l = d.getLinkLoss();
            assertNotNull(l, "the opened device is gone");
            assertTrue(l.cause.startsWith("the controller left USB: " + opened.getPath() + " is gone"), l.cause);
        }
        finally {
            sim.close();
        }
    }

    /**
     * #53 re-review: the exit flag is set by a hook registered with jSerialComm itself (its hooks run before it closes the
     * ports), not by one of the JVM's (those run in no fixed order with jSerialComm's): a serial connect registers it
     * once, before it opens the port (this connect fails to open; an earlier test's connect may have registered it).
     */
    @Test
    public void aSerialConnectRegistersTheExitFlagWithJSerialComm() throws Exception {
        SerialPortCommunications c = new SerialPortCommunications();
        c.setPortName("/dev/no-such-port-br93");
        try {
            c.connect();
        }
        catch (Exception expected) {
            // no such port: the hook is registered before the open
        }
        java.lang.reflect.Field f = com.fazecast.jSerialComm.SerialPort.class.getDeclaredField("shutdownHooks");
        f.setAccessible(true);
        Thread hook = null;
        for (Object t : (java.util.List<?>) f.get(null)) {
            if ("serial-exit-flag".equals(((Thread) t).getName())) {
                assertNull(hook, "registered once");
                hook = (Thread) t;
            }
        }
        assertNotNull(hook, "the exit flag's hook is jSerialComm's");
        boolean was = SerialPortCommunications.jvmShuttingDown;
        try {
            hook.run();
            assertTrue(SerialPortCommunications.jvmShuttingDown);
        }
        finally {
            SerialPortCommunications.jvmShuttingDown = was;
        }
    }
}
