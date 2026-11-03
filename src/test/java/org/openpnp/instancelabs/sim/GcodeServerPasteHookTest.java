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
import org.openpnp.instancelabs.dispense.RecordingGcodeServer;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;
import org.openpnp.util.GcodeServer;

/**
 * The paste hook in OpenPnP's simulated controller and in the test RecordingGcodeServer: without a
 * paste block every reply is as before (M114 byte for byte); with one, a dot of the needle (N2: raw
 * head + its offset, the see-saw's Z) lands on the blank and M114 carries Marlin's Count line, which
 * a reconnect restarts.
 */
public class GcodeServerPasteHookTest {
    /**
     * The needle over (300, 240) (raw head = tip − the N2 offset), from the hop (N2 Z 6.72) to 0.10 above
     * the blank at 5.62 (N2 Z 5.72 = raw 57.28), a half turn, and back to the hop.
     */
    static final String[] DOT = {"G92 X274.709 Y315.926 Z56.28", "M106 P2 S255", "M106 P3 S255", "G1 Z57.28 F3000",
            "M204 S20000", "G1 B190.0901 F64800", "G4 P250", "G1 B180.1802 F64800", "M204 S2000", "G1 Z56.28 F3000",
            "M114", "M400", "M107 P3", "M107 P2"};
    /** The §11.3 paste with the exact law (no noise, transient, carry-over, ripple or scatter). */
    static final String PASTE = "\"paste\": {\"mRevMg\": 2.30, \"ripple\": 0, \"cv\": 0, \"oozeFirst\": 0, \"refillDip\": 0,"
            + " \"transferRatio\": 1, \"landingSdMm\": 0, \"needleZOffsetMm\": 0, \"n2OffsetMm\": [25.291, -75.926]%s}";
    SimMachine sim;
    GcodeServer server;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine(false); // the LumenPnP axes (N2 on the see-saw: z2 = 63 − z1); the driver unconnected
    }

    @AfterEach
    public void after() throws Exception {
        if (server != null) {
            server.shutdown();
        }
        sim.close();
    }

    static void writeScene(String extra) throws Exception {
        File scene = SimGuard.sceneFile(Configuration.get());
        assertTrue(scene.getParentFile().mkdirs() || scene.getParentFile().isDirectory());
        Files.write(scene.toPath(), ("{\"version\": 1, \"board\": {\"file\": \"toy.board.xml\", \"seat\": {\"x\": 0, "
                + "\"y\": 0}}, \"blank\": {\"rect\": [280, 225, 365, 250], \"surface\": {\"z0\": 5.62}}" + extra + "}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    List<List<String>> run(String... lines) throws Exception {
        if (server != null) {
            server.shutdown();
        }
        server = new GcodeServer();
        server.setDriver(sim.driver);
        return talk(server.getListenerPort(), lines);
    }

    /** Sends each line on a new connection and collects the reply lines up to "ok". */
    static List<List<String>> talk(int port, String... lines) throws Exception {
        List<List<String>> out = new ArrayList<>();
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(10000);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream o = s.getOutputStream();
            for (String line : lines) {
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

    @Test
    public void withoutAPasteBlockM114IsByteIdentical() throws Exception {
        assertNull(SimPaste.forConfiguration(Configuration.get()), "no scene, no paste model");
        List<List<String>> none = run(DOT);
        assertEquals(1, none.get(10).size(), "one line: " + none.get(10));
        assertTrue(none.get(10).get(0).startsWith("ok C: X:274.7090 Y:315.9260"), none.get(10).toString());
        writeScene("");
        assertNotNull(SimPaste.forConfiguration(Configuration.get()), "a scene: the model follows the screw");
        List<List<String>> scene = run(DOT);
        assertEquals(none, scene, "every reply as before");
        writeScene(", " + String.format(PASTE, ", \"reportCount\": false"));
        assertEquals(none, run(DOT), "a paste block without the count: as before");
        assertEquals(1, SimPaste.Store.forFile(SimGuard.sceneFile(Configuration.get())).deposits("blank").size(),
                "but the dot was laid");
    }

    @Test
    public void aDotOfTheNeedleLandsOnTheBlankAndM114CountsTheSteps() throws Exception {
        writeScene(", " + String.format(PASTE, ""));
        SimPaste.Store store = SimPaste.Store.forFile(SimGuard.sceneFile(Configuration.get()));
        store.wipe(null);
        List<List<String>> r = run(DOT);
        assertEquals(List.of("Count X:0 Y:0 Z:0 B:800"), r.get(10).subList(0, 1), "Count before ok C: " + r.get(10));
        assertTrue(r.get(10).get(1).startsWith("ok C: X:274.7090"), r.get(10).toString());
        for (int i : new int[] {1, 2, 5, 6, 7}) {
            assertEquals(List.of("ok"), r.get(i), "line " + i);
        }
        List<SimPaste.Deposit> d = store.deposits("blank");
        assertEquals(1, d.size());
        assertEquals(300, d.get(0).x, 1e-6, "raw X + n2OffsetMm");
        assertEquals(240, d.get(0).y, 1e-6);
        assertEquals(1.15, d.get(0).mg, 1e-9, "a half turn of 2.30 mg/rev");
        assertEquals(0, (long) d.get(0).startCount);
        assertFalse(d.get(0).string, "N2 Z 5.72 is 0.10 above the blank: the see-saw's Z (63 − raw)");
        // A new server is a new connection (uinav: one per connection): the count restarts, the paste stays.
        r = run("M114");
        assertEquals("Count X:0 Y:0 Z:0 B:0", r.get(0).get(0));
        assertEquals(1, store.deposits(null).size());
        store.wipe(null);
    }

    @Test
    public void recordingServerUsesThePasteCountAndPowerCyclesOnReconnect() throws Exception {
        SimScene s = SimPasteTest.exact();
        s.paste.ripple = 0;
        s.paste.needleZOffsetMm = 0.0;
        try (RecordingGcodeServer rec = new RecordingGcodeServer()) {
            SimPaste p = new SimPaste(s);
            rec.paste = p;
            List<List<String>> r = talk(rec.getPort(), DOT);
            assertTrue(r.get(10).get(0).endsWith("Count X:0 Y:0 Z:0 B:800"), r.get(10).toString());
            assertEquals(800, rec.bStepCount());
            assertEquals(1, p.deposits("blank").size());
            assertEquals(300, p.deposits("blank").get(0).x, 1e-6);
            assertFalse(p.deposits("blank").get(0).string, "raw Z 57.28 is the needle's 5.72");
            int c = p.connectionNumber();
            rec.setBStepCount(4321);
            assertEquals(4321, p.count(), "the server's power-up history is the paste model's");
            r = talk(rec.getPort(), "M114");
            assertTrue(r.get(0).get(0).endsWith("B:0"), "a reconnect is a power-up: " + r.get(0));
            assertEquals(c + 1, p.connectionNumber());
            s.paste.reportCount = false;
            r = talk(rec.getPort(), "M114");
            assertTrue(r.get(0).get(0).endsWith("Count X:0 Y:0 Z:0"), "no B count: " + r.get(0));
        }
    }
}
