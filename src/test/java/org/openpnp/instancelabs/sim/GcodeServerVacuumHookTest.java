package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;
import org.openpnp.util.GcodeServer;

/**
 * OpenPnP's own simulated controller with the vacuum hook: without a scene it answers exactly as
 * before; with one it answers the bench's VAC1 read from where the head is (or arrives).
 */
public class GcodeServerVacuumHookTest {
    SimMachine sim;
    GcodeServer server;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine(false); // a configuration with the LumenPnP axes; the driver stays unconnected
    }

    @AfterEach
    public void after() throws Exception {
        if (server != null) {
            server.shutdown();
        }
        sim.close();
    }

    /** Sends each line and collects the reply lines up to "ok" ("sleep <ms>" pauses, as the prober's read delay). */
    static List<List<String>> talk(GcodeServer server, String... lines) throws Exception {
        List<List<String>> out = new ArrayList<>();
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), server.getListenerPort())) {
            s.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream o = s.getOutputStream();
            for (String line : lines) {
                if (line.startsWith("sleep ")) {
                    Thread.sleep(Long.parseLong(line.substring(6)));
                    continue;
                }
                o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
                List<String> reply = new ArrayList<>();
                String r;
                do {
                    r = in.readLine();
                    assertNotNull(r, "no reply to " + line);
                    reply.add(r);
                } while (!r.startsWith("ok"));
                out.add(reply);
            }
        }
        return out;
    }

    static final String[] READ = {"M260 A112 B1 S1", "M260 A109 B6 S1", "M261 A109 B3 S1"};

    @Test
    public void withoutASceneNothingChanges() throws Exception {
        assertFalse(SimGuard.sceneFile(Configuration.get()).exists());
        assertNull(SimVacuum.forConfiguration(Configuration.get()));
        server = new GcodeServer();
        server.setDriver(sim.driver);
        List<List<String>> r = talk(server, "M106", "M106 P1 S180", READ[0], READ[1], READ[2], "M115");
        for (int i = 0; i < 5; i++) {
            assertEquals(List.of("ok"), r.get(i), "line " + i);
        }
        // The identity SimGuard requires is OpenPnP's own GcodeServer's.
        assertTrue(r.get(5).get(0).startsWith(SimGuard.SIMULATOR_FIRMWARE), r.get(5).toString());
        assertTrue(SimGuard.isSimulatorIdentity(r.get(5)));
    }

    @Test
    public void withASceneTheSensorAnswersFromThePlannedMotion() throws Exception {
        File scene = SimGuard.sceneFile(Configuration.get());
        assertTrue(scene.getParentFile().mkdirs() || scene.getParentFile().isDirectory());
        // Board origin at machine (0, 0): head (30, 70) puts N1's tip at board (5.822, 6.09), on the board.
        Files.write(scene.toPath(), ("{\"version\": 1, \"board\": {\"file\": \"toy.board.xml\", \"size\": [75, 75],"
                + " \"seat\": {\"x\": 0, \"y\": 0}, \"surface\": {\"z0\": 5.43, \"slopeX\": 0, \"slopeY\": 0}},"
                + " \"vacuum\": {\"noise\": 0, \"tauS\": 0, \"releaseTauS\": 0, \"sealDrops\": [300000]}}")
                        .getBytes(StandardCharsets.UTF_8));
        server = new GcodeServer();
        server.setDriver(sim.driver);
        // The default sealTauS (0.03 s) with the bench's read 70 ms after M400: the seal formed when Z arrived,
        // and not while Z was still on its way down (Z 8 -> 5.3 at 3 mm/s: 0.9 s).
        List<List<String>> r = talk(server, "G92 X30 Y70 Z10", "M204 S2000", "M106", "M106 P1 S180", READ[0], READ[1],
                READ[2], "G1 Z5.3 F600", "M400", "sleep 70", READ[0], READ[1], READ[2], "G1 Z8 F600", "M400", READ[0],
                READ[1], READ[2], "M114", "M261 A109 B1 S2", "G1 Z5.3 F180", READ[0], "sleep 200", READ[2], "M400",
                "sleep 70", READ[2], "M107 P1", "M107", READ[2]);
        // M106 and the M260 writes are parsed as before: plain ok.
        for (int i : new int[] {2, 3, 4, 5}) {
            assertEquals(List.of("ok"), r.get(i), "line " + i);
        }
        assertEquals(List.of("echo:i2c-reply: from:109 bytes:3 data:E8 59 50", "ok"), r.get(6), "open air -1,550,000");
        long pressed = SimVacuumTest.parse(r.get(11).get(0));
        assertTrue(pressed < -1_550_000 - 0.85 * 300_000 && pressed >= -1_850_000,
                "N1 at Z 5.3 is pressed on the board: " + pressed);
        assertEquals(-1_550_000, SimVacuumTest.parse(r.get(16).get(0)), "lifted to Z 8: open again");
        assertTrue(r.get(17).get(0).contains("Z:8.0000"), "the motion is simulated as before: " + r.get(17));
        assertEquals(List.of("ok"), r.get(18), "OpenPnP's own VAC1 read is not modelled");
        assertEquals(-1_550_000, SimVacuumTest.parse(r.get(21).get(0)), "0.2 s into the 0.9 s move down: open");
        assertTrue(SimVacuumTest.parse(r.get(23).get(0)) < -1_550_000 - 0.85 * 300_000, "arrived 70 ms ago");
        assertEquals(0, SimVacuumTest.parse(r.get(26).get(0)), "vacuum off");
    }
}
