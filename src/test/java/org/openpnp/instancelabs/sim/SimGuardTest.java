package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DriverGcodeChannel;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.machine.reference.driver.AbstractReferenceDriver.CommunicationsType;

/**
 * The guard: simulation-only code may act only when every driver is loopback TCP and the controller
 * said GcodeServer (M115) on the current connection. Driven through OpenPnP's GcodeDriver against
 * the test RecordingGcodeServer, which answers Marlin by default.
 */
public class SimGuardTest {
    static final List<String> GCODE_SERVER = Arrays.asList(
            "FIRMWARE_NAME:GcodeServer, FIRMWARE_URL:http%3A//openpnp.org, FIRMWARE_VERSION:x, X-AXES:5", "ok");
    static final List<String> MARLIN = Arrays.asList("FIRMWARE_NAME:Marlin bugfix-2.1.x", "Cap:HOST_WATCHDOG:1", "ok");
    /** lumen-sim (infra/lumen-sim, INF-03) behind its flag: the board's M115 plus a line the real board never sends. */
    static final List<String> LUMEN_SIM = Arrays.asList("FIRMWARE_NAME:Marlin bugfix-2.1.x (Sep 19 2024 13:39:21)",
            "Cap:HOST_WATCHDOG:1", "Cap:SIMULATOR:lumen-sim", "ok");
    SimMachine sim;

    @AfterEach
    public void after() throws Exception {
        if (sim != null) {
            sim.close();
        }
    }

    /** What the engine does at the start of every task: M115 through the driver, then note the answer. */
    List<String> identify() throws Exception {
        List<String> lines = new DriverGcodeChannel(sim.driver).sendAndCollect("M115", 5000);
        SimGuard.noteIdentity(sim.machine, lines);
        return lines;
    }

    @Test
    public void loopbackTcpAndGcodeServerIsSimulated() throws Exception {
        sim = new SimMachine();
        sim.server.firmwareName = "GcodeServer (RecordingGcodeServer)";
        assertTrue(SimGuard.isLoopbackOnly(sim.machine));
        assertFalse(SimGuard.isSimulated(sim.machine), "no M115 yet on this connection");
        identify();
        assertTrue(SimGuard.isSimulated(sim.machine));
    }

    @Test
    public void loopbackTcpAndMarlinIsNotSimulated() throws Exception {
        sim = new SimMachine();
        List<String> lines = identify();
        assertTrue(lines.stream().anyMatch((l) -> l.contains("FIRMWARE_NAME:Marlin (RecordingGcodeServer)")),
                lines.toString());
        assertTrue(SimGuard.isLoopbackOnly(sim.machine), "a serial-over-TCP bridge on 127.0.0.1 passes this");
        assertFalse(SimGuard.isSimulated(sim.machine));
        // A later Marlin answer on the same connection replaces an earlier GcodeServer one.
        SimGuard.noteIdentity(sim.machine, GCODE_SERVER);
        assertTrue(SimGuard.isSimulated(sim.machine));
        identify();
        assertFalse(SimGuard.isSimulated(sim.machine));
    }

    @Test
    public void identityIsForgottenOnDisableAndOnEnable() throws Exception {
        sim = new SimMachine(false);
        sim.server.firmwareName = "GcodeServer (RecordingGcodeServer)";
        SimGuard.noteIdentity(sim.machine, GCODE_SERVER); // a stale answer from before this connection
        assertTrue(SimGuard.isSimulated(sim.machine));
        sim.enableAndHome();
        assertFalse(SimGuard.isSimulated(sim.machine), "forgotten on enable");
        identify();
        assertTrue(SimGuard.isSimulated(sim.machine));
        sim.machine.setEnabled(false);
        assertFalse(SimGuard.isSimulated(sim.machine), "forgotten on disable");
        sim.machine.setEnabled(true);
        assertFalse(SimGuard.isSimulated(sim.machine), "a reconnect needs a new M115");
        identify();
        assertTrue(SimGuard.isSimulated(sim.machine));
    }

    /**
     * What asks at a connect is noted after the last connection's answer is forgotten, in SimGuard's one
     * listener, so every connect of the simulator is confirmed at once, whatever order OpenPnP fires its
     * listeners in; with nothing asking, a connect needs an M115 of its own again.
     */
    @Test
    public void theAnswerAskedAtAConnectIsNotedAfterTheForget() throws Exception {
        sim = new SimMachine(false);
        sim.server.firmwareName = "GcodeServer (RecordingGcodeServer)";
        SimGuard.askAtConnect(sim.machine, () -> new DriverGcodeChannel(sim.driver).sendAndCollect("M115", 5000));
        for (int i = 1; i <= 3; i++) {
            sim.machine.setEnabled(true);
            assertTrue(SimGuard.isSimulated(sim.machine), "asked at connect " + i);
            sim.machine.setEnabled(false);
            assertFalse(SimGuard.isSimulated(sim.machine), "forgotten on disable");
        }
        SimGuard.askAtConnect(sim.machine, () -> {
            throw new IllegalStateException("no answer");
        });
        sim.machine.setEnabled(true);
        assertFalse(SimGuard.isSimulated(sim.machine), "a failed question confirms nothing");
        sim.machine.setEnabled(false);
        SimGuard.askAtConnect(sim.machine, null);
        sim.machine.setEnabled(true);
        assertFalse(SimGuard.isSimulated(sim.machine), "nothing asks");
        identify();
        assertTrue(SimGuard.isSimulated(sim.machine));
    }

    /**
     * BR-52: OpenPnP's own Stop may fire its last machineDisabled listeners on its own thread after the machine was
     * started again; the new connection's identity stays. A machine that reads off forgets it, as before.
     */
    @Test
    public void aLateMachineDisabledKeepsTheIdentityOfAMachineStartedAgain() throws Exception {
        sim = new SimMachine(false);
        sim.server.firmwareName = "GcodeServer (RecordingGcodeServer)";
        SimGuard.askAtConnect(sim.machine, () -> new DriverGcodeChannel(sim.driver).sendAndCollect("M115", 5000));
        sim.machine.setEnabled(true);
        assertTrue(SimGuard.isSimulated(sim.machine));
        sim.machine.fireMachineDisabled("the last listeners of an earlier switch-off");
        assertTrue(SimGuard.isSimulated(sim.machine), "kept: the machine is started");
        sim.machine.setEnabled(false);
        assertFalse(SimGuard.isSimulated(sim.machine), "forgotten on disable");
    }

    @Test
    public void serialWithAnUnusedLoopbackTcpEntryIsNotSimulated() throws Exception {
        sim = new SimMachine(false);
        // The real machine.xml's shape: communications="serial", plus an unused tcp 127.0.0.1:23 entry.
        sim.driver.setCommunicationsType(CommunicationsType.serial);
        sim.driver.getTcp().setIpAddress("127.0.0.1");
        sim.driver.getTcp().setPort(23);
        assertFalse(SimGuard.isLoopbackTcp(sim.driver));
        assertFalse(SimGuard.isLoopbackOnly(sim.machine));
        SimGuard.noteIdentity(sim.machine, GCODE_SERVER);
        assertFalse(SimGuard.isSimulated(sim.machine), "even if something answered GcodeServer");
        assertFalse(new DriverGcodeChannel(sim.driver).isLoopbackSimulator());
    }

    @Test
    public void tcpToAnotherComputerIsNotSimulated() throws Exception {
        sim = new SimMachine(false);
        sim.driver.getTcp().setIpAddress("192.168.1.20");
        assertFalse(SimGuard.isLoopbackOnly(sim.machine));
        SimGuard.noteIdentity(sim.machine, GCODE_SERVER);
        assertFalse(SimGuard.isSimulated(sim.machine));
    }

    @Test
    public void loopbackAddresses() throws Exception {
        sim = new SimMachine(false);
        for (String ip : new String[] {"127.0.0.1", "127.0.0.2", "localhost", "::1", "GcodeServer"}) {
            sim.driver.getTcp().setIpAddress(ip);
            assertTrue(SimGuard.isLoopbackTcp(sim.driver), ip);
            assertTrue(new DriverGcodeChannel(sim.driver).isLoopbackSimulator(), ip);
        }
        // Never a name lookup: a host name is not loopback, whatever it resolves to.
        for (String ip : new String[] {"10.0.0.5", "192.168.1.20", "openpnp.org", "loopback.example", "", "::2"}) {
            sim.driver.getTcp().setIpAddress(ip);
            assertFalse(SimGuard.isLoopbackTcp(sim.driver), ip);
        }
    }

    @Test
    public void onlyGcodeServerIsTheSimulator() {
        assertTrue(SimGuard.isSimulatorIdentity(GCODE_SERVER));
        assertTrue(SimGuard.isSimulatorIdentity(Arrays.asList("FIRMWARE_NAME:GcodeServer (RecordingGcodeServer)")));
        assertFalse(SimGuard.isSimulatorIdentity(MARLIN));
        assertFalse(SimGuard.isSimulatorIdentity(Arrays.asList("FIRMWARE_NAME:Marlin (RecordingGcodeServer)")));
        assertFalse(SimGuard.isSimulatorIdentity(Arrays.asList("FIRMWARE_NAME:GcodeServerX")));
        assertFalse(SimGuard.isSimulatorIdentity(Arrays.asList("echo: GcodeServer", "ok")));
        assertFalse(SimGuard.isSimulatorIdentity(null));
        assertFalse(SimGuard.isLoopbackOnly(null));
        assertFalse(SimGuard.isSimulated(null));
    }

    @Test
    public void sceneFileLivesInTheConfigurationCopy() throws Exception {
        sim = new SimMachine(false);
        assertEquals(new java.io.File(sim.configDir, "instancelabs/sim-scene.json").getAbsolutePath(),
                SimGuard.sceneFile(org.openpnp.model.Configuration.get()).getAbsolutePath());
    }

    /** A pseudo-terminal held open by python3, as lumen-sim's, and a link to it, as uinav's session link. */
    static final class Pty implements AutoCloseable {
        final Process process;
        final String device;
        final Path link;

        Pty() throws Exception {
            Process p;
            try {
                p = new ProcessBuilder("python3", "-I", "-c", "import os, pty, sys; m, s = pty.openpty(); "
                        + "print(os.ttyname(s), flush=True); sys.stdin.read()").start();
            }
            catch (java.io.IOException e) {
                p = null;
            }
            Assumptions.assumeTrue(p != null, "python3 opens the test's pseudo-terminal");
            process = p;
            device = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)).readLine();
            Assumptions.assumeTrue(device != null && device.startsWith("/dev/pts/"), "a pty: " + device);
            link = Files.createSymbolicLink(Files.createTempDirectory("simguard-pty").resolve("ttyLUMEN0"),
                    Paths.get(device));
        }

        @Override
        public void close() throws Exception {
            process.getOutputStream().close(); // python reads to EOF, then lets the pty go
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(link);
        }
    }

    /** BR-47: lumen-sim on a pty, as uinav --controller pty connects OpenPnP to it. */
    @Test
    public void aPtyAndTheLumenSimLineIsSimulated() throws Exception {
        try (Pty pty = new Pty()) {
            sim = new SimMachine(false);
            sim.driver.setCommunicationsType(CommunicationsType.serial);
            try {
                sim.driver.setPortName(pty.link.toString());
                assertTrue(SimGuard.isPtySerial(sim.driver), pty.link + " -> " + pty.device);
                assertFalse(SimGuard.isLoopbackOnly(sim.machine));
                assertFalse(SimGuard.isSimulated(sim.machine), "no M115 yet on this connection");
                SimGuard.noteIdentity(sim.machine, LUMEN_SIM);
                assertTrue(SimGuard.isSimulated(sim.machine));
                sim.driver.setPortName(pty.device);
                assertTrue(SimGuard.isSimulated(sim.machine), "the device itself");
                // the bug reproductions: lumen-sim without its flag answers with the board's exact M115
                SimGuard.noteIdentity(sim.machine, MARLIN);
                assertFalse(SimGuard.isSimulated(sim.machine), "a pty with the stock M115");
            }
            finally {
                sim.driver.setCommunicationsType(CommunicationsType.tcp);
            }
        }
    }

    /** BR-47: the lumen-sim line confirms nothing on a port that is not a pseudo-terminal. */
    @Test
    public void theLumenSimLineOnARealPortIsNotSimulated() throws Exception {
        sim = new SimMachine(false);
        sim.driver.setCommunicationsType(CommunicationsType.serial);
        try {
            Path toBoard = Files.createSymbolicLink(Files.createTempDirectory("simguard").resolve("ttyLUMEN0"),
                    Paths.get("/dev/ttyACM0"));
            Path file = Files.createTempFile("pts", "3");
            for (String port : new String[] {"/dev/ttyACM0", "ttyACM0", "/dev/ttyUSB0",
                "/dev/serial/by-id/usb-Opulo_LumenPnP_Motherboard_0001-if00", toBoard.toString(), file.toString(),
                "/dev/pts/ptmx", "/dev/pts", "pts/3", "", " "}) {
                sim.driver.setPortName(port);
                assertFalse(SimGuard.isPtySerial(sim.driver), port);
                SimGuard.noteIdentity(sim.machine, LUMEN_SIM);
                assertFalse(SimGuard.isSimulated(sim.machine), port);
            }
            Files.deleteIfExists(toBoard);
            Files.deleteIfExists(file);
        }
        finally {
            sim.driver.setCommunicationsType(CommunicationsType.tcp);
        }
    }

    @Test
    public void theLumenSimLineIsASimulatorIdentity() {
        assertTrue(SimGuard.isSimulatorIdentity(LUMEN_SIM), "records made on lumen-sim are simulated");
        assertTrue(SimGuard.isSimulatorIdentity(GCODE_SERVER));
        assertFalse(SimGuard.isSimulatorIdentity(MARLIN));
        assertFalse(SimGuard.isSimulatorIdentity(Arrays.asList("Cap:SIMULATOR:lumen-simX", "ok")));
        assertFalse(SimGuard.isSimulatorIdentity(Arrays.asList("Cap:SIMULATOR:0", "ok")));
    }
}
