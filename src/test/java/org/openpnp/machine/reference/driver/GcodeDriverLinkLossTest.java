package org.openpnp.machine.reference.driver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
