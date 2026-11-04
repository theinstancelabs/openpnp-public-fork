package org.openpnp.instancelabs.sim;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.openpnp.machine.reference.driver.AbstractReferenceDriver.CommunicationsType;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.spi.Axis;
import org.openpnp.spi.ControllerAxis;
import org.openpnp.spi.HeadMountable;
import org.pmw.tinylog.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Where lumen-sim's head physically is against where its controller believes it is (INF-05): lumen-sim writes
 * {@code <link>.pose.json}, {@code {"offset": {"X": mm, "Y": mm, "Z": mm}}} (physical - logical: a home off its
 * switches, lost steps, a power cycle), beside the pty link the GcodeDriver opens. The SIM top camera draws at
 * OpenPnP's location plus it, so vision sees what the real camera would (SimTipCamera does not yet). Read at every
 * frame (a few bytes).
 *
 * shortcut: after a controller power cycle OpenPnP believes its old position until it re-reads M114 at the re-enable
 * (sync-initial-location, as the lab config has it); without that, an axis draws off by that position until it is
 * homed or moved. Publish the physical head and subtract OpenPnP's driver coordinates if such a config needs frames.
 */
final class SimPose {
    static final String SUFFIX = ".pose.json";
    static final long MAX_BYTES = 4096; // lumen-sim's file is about 60
    private static volatile String lastProblem; // warned once, not at every frame

    private SimPose() {
    }

    /** lumen-sim's X and Y offset (mm) for the controller axes that move hm; 0 where none is published. */
    static double[] offsetMm(HeadMountable hm) {
        return new double[] {offsetMm(hm.getAxisX()), offsetMm(hm.getAxisY())};
    }

    static double offsetMm(Axis axis) {
        if (!(axis instanceof ControllerAxis) || !(((ControllerAxis) axis).getDriver() instanceof GcodeDriver)) {
            return 0;
        }
        ControllerAxis a = (ControllerAxis) axis;
        GcodeDriver driver = (GcodeDriver) a.getDriver();
        String port = driver.getCommunicationsType() == CommunicationsType.serial ? driver.getSerial().getPortName()
                : null;
        if (port == null || port.trim().isEmpty()) {
            return 0;
        }
        String name = port.trim() + SUFFIX;
        try {
            Path file = Paths.get(name);
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                return 0;
            }
            // only a plain file is opened: a FIFO there would hang the frame, and a link is not lumen-sim's file
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
                throw new IllegalStateException("not lumen-sim's pose file (a link, a special file or over "
                        + MAX_BYTES + " bytes)");
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JsonObject offset = new JsonParser().parse(text).getAsJsonObject().getAsJsonObject("offset");
            if (!offset.has(a.getLetter())) {
                return 0;
            }
            double v = offset.get(a.getLetter()).getAsDouble();
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException(a.getLetter() + " is " + v);
            }
            return v;
        }
        catch (NoSuchFileException e) {
            return 0;
        }
        catch (Exception e) {
            String problem = name + ": " + e;
            if (!problem.equals(lastProblem)) {
                lastProblem = problem;
                Logger.warn("sim camera: no head offset from {} ({})", name, e.toString());
            }
            return 0;
        }
    }
}
