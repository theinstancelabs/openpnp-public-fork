package org.openpnp.machine.reference.driver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        File dev = dir.resolve("usb-STMicroelectronics_MARLIN_OPULO_LUMEN_REV5-if00").toFile();
        assertTrue(dev.createNewFile());
        assertFalse(GcodeDriver.devicePathGone(dev.getPath()), "present");
        assertTrue(dev.delete());
        assertTrue(GcodeDriver.devicePathGone(dev.getPath()), "gone");
        assertFalse(GcodeDriver.devicePathGone("COM3"), "a port named without a path is never checked");
        assertFalse(GcodeDriver.devicePathGone("ttyACM0"));
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
}
