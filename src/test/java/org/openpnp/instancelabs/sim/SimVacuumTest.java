package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/**
 * The simulated N1 vacuum sensor on a fake clock: the reply format, the I2C state, settling, seals,
 * partial seals, the slow release, leaks, edges, tip states, per-spot seal drops and scene reloads.
 */
public class SimVacuumTest {
    static final Pattern DATA = Pattern.compile("data:([0-9A-F]{2}) ([0-9A-F]{2}) ([0-9A-F]{2})");
    final long[] nowNs = {0};

    /** A 75 × 75 board seated off-axis (rotated, scaled, sheared), a flat-ish surface, no noise, instant dynamics. */
    static SimScene scene() {
        SimScene s = new SimScene();
        s.boardFile = "toy.board.xml";
        s.boardWidthMm = 75;
        s.boardHeightMm = 75;
        s.seat = SimScene.seat(300, 115, 1.5, 1.002, 0.997, 0.1);
        s.boardSurface = new SimScene.Plane(5.43, -0.0006, 0.0009);
        s.vacuum.noise = 0;
        s.vacuum.tauS = 0;
        s.vacuum.sealTauS = 0;
        s.vacuum.releaseTauS = 0;
        s.vacuum.sealDrops = new double[] {300000};
        return s;
    }

    SimVacuum vacuum(SimScene s) {
        return new SimVacuum(() -> s, () -> nowNs[0]);
    }

    void at(double seconds) {
        nowNs[0] = Math.round(seconds * 1e9);
    }

    /** Raw head X/Y that put N1's tip on a board point. */
    static double[] head(SimScene s, double bx, double by) {
        Point2D.Double m = s.toMachine(bx, by);
        return new double[] {m.x - s.n1OffsetX, m.y - s.n1OffsetY};
    }

    static long parse(String reply) {
        Matcher m = DATA.matcher(reply);
        assertTrue(m.find(), reply);
        int v = Integer.parseInt(m.group(1) + m.group(2) + m.group(3), 16);
        return v >= 0x800000 ? v - 0x1000000 : v;
    }

    /** One bench read (tools/probe.py): select channel 1, register 6, read 3 bytes. */
    static long read(SimVacuum v, double[] h, double z) {
        assertNull(v.onLine("M260 A112 B1 S1", h[0], h[1], z));
        assertNull(v.onLine("M260 A109 B6 S1", h[0], h[1], z));
        return parse(v.onLine("M261 A109 B3 S1", h[0], h[1], z));
    }

    static void vacuumOn(SimVacuum v, double[] h, double z) {
        assertNull(v.onLine("M106", h[0], h[1], z));
        assertNull(v.onLine("M106 P1 S180", h[0], h[1], z));
    }

    @Test
    public void replyIsTwentyFourBitTwosComplementBigEndian() {
        assertEquals("echo:i2c-reply: from:109 bytes:3 data:E8 F9 80", SimVacuum.reply(-1_508_992));
        assertEquals(-1_508_992, parse(SimVacuum.reply(-1_508_992)));
        assertEquals("echo:i2c-reply: from:109 bytes:3 data:00 00 00", SimVacuum.reply(0));
        assertEquals("echo:i2c-reply: from:109 bytes:3 data:01 E2 40", SimVacuum.reply(123_456));
        // Clamped to the 24-bit range.
        assertEquals(-0x800000, parse(SimVacuum.reply(-20_000_000)));
    }

    @Test
    public void offValueUntilPumpAndValveAreOn() {
        SimScene s = scene();
        SimVacuum v = vacuum(s);
        double[] h = head(s, 37, 37);
        assertEquals(0, read(v, h, 31.5));
        assertNull(v.onLine("M106", h[0], h[1], 31.5));
        assertEquals(0, read(v, h, 31.5), "pump alone");
        assertNull(v.onLine("M106 P1 S180", h[0], h[1], 31.5));
        assertEquals(-1_550_000, read(v, h, 31.5));
        assertNull(v.onLine("M107 P1", h[0], h[1], 31.5));
        assertEquals(0, read(v, h, 31.5), "valve off");
        assertNull(v.onLine("M106 P1 S0", h[0], h[1], 31.5));
        assertEquals(0, read(v, h, 31.5), "S0 is off");
        assertNull(v.onLine("M106 P1 S255", h[0], h[1], 31.5));
        assertNull(v.onLine("M107", h[0], h[1], 31.5));
        assertEquals(0, read(v, h, 31.5), "pump off");
        // Other fans (the needle's pressure valve P2) do not touch N1's vacuum.
        vacuumOn(v, h, 31.5);
        assertNull(v.onLine("M106 P2 S255", h[0], h[1], 31.5));
        assertNull(v.onLine("M107 P2", h[0], h[1], 31.5));
        assertEquals(-1_550_000, read(v, h, 31.5));
    }

    @Test
    public void channelAndRegisterComeFromTheI2cWrites() {
        SimScene s = scene();
        s.vacuum.offValue = -7;
        SimVacuum v = vacuum(s);
        double[] h = head(s, 37, 37);
        vacuumOn(v, h, 31.5);
        // The bench's VAC1 init (tools/probe.py:49): the buffer A109 B48 B27 sent with S1 -> register 48.
        for (String l : new String[] {"M260 A112 B1 S1", "M260 A109", "M260 B48", "M260 B27", "M260 S1"}) {
            assertNull(v.onLine(l, h[0], h[1], 31.5));
        }
        assertEquals(-7, parse(v.onLine("M261 A109 B3 S1", h[0], h[1], 31.5)), "register 48 is not the data");
        assertNull(v.onLine("M260 A109 B6 S1", h[0], h[1], 31.5));
        assertEquals(-1_550_000, parse(v.onLine("M261 A109 B3 S1", h[0], h[1], 31.5)));
        // Channel 2 of the multiplexer is not N1's sensor.
        assertNull(v.onLine("M260 A112 B2 S1", h[0], h[1], 31.5));
        assertEquals(-7, parse(v.onLine("M261 A109 B3 S1", h[0], h[1], 31.5)));
        // OpenPnP's own VAC1 read and every other line are not answered here (plain "ok" as today).
        assertNull(v.onLine("M261 A109 B1 S2", h[0], h[1], 31.5));
        assertNull(v.onLine("M115", h[0], h[1], 31.5));
        assertNull(v.onLine("G1 Z8.0000 F600", h[0], h[1], 8));
    }

    @Test
    public void baselineSettlesWithTau() {
        SimScene s = scene();
        s.vacuum.tauS = 0.3;
        SimVacuum v = vacuum(s);
        double[] h = head(s, 37, 37);
        at(0);
        vacuumOn(v, h, 8);
        at(0.3);
        long early = read(v, h, 8);
        assertEquals(-1_550_000 * (1 - Math.exp(-1)), early, 2.0);
        at(1.5);
        long settled = read(v, h, 8);
        assertTrue(settled < early - 500_000, "a read at 0.3 s is still falling: " + early + " then " + settled);
        assertEquals(-1_550_000, settled, 0.01 * 1_550_000);
    }

    @Test
    public void sealsAtContactWithAPartialBandAbove() {
        SimScene s = scene();
        s.boardSurface = new SimScene.Plane(5.43, -0.004, 0.006);
        SimVacuum v = vacuum(s);
        double[] h = head(s, 20, 30);
        double contact = s.boardContactZ(20, 30);
        vacuumOn(v, h, 8);
        assertEquals(-1_550_000, read(v, h, contact + 0.031));
        assertEquals(-1_550_000 - 150_000, read(v, h, contact + 0.029), "partial seal: half the drop");
        assertEquals(-1_550_000 - 300_000, read(v, h, contact), "sealed at contact");
        assertEquals(-1_550_000 - 300_000, read(v, h, contact - 0.5), "sealed below");
        // The surface is the scene's tilted plane, through the true seat.
        double[] far = head(s, 70, 70);
        double contactFar = s.boardContactZ(70, 70);
        assertNotEquals(contact, contactFar, 0.02);
        assertEquals(-1_550_000, read(v, far, contactFar + 0.04));
        assertEquals(-1_850_000, read(v, far, contactFar - 0.001));
    }

    @Test
    public void sealFormsWithSealTauAndReleasesSlowly() {
        SimScene s = scene();
        s.vacuum.sealTauS = 0.03;
        s.vacuum.releaseTauS = 0.4;
        SimVacuum v = vacuum(s);
        double[] h = head(s, 37, 37);
        double contact = s.boardContactZ(37, 37);
        at(0);
        vacuumOn(v, h, contact + 0.25);
        at(1.0);
        assertNull(v.onLine("G1 Z" + (contact - 0.05) + " F300", h[0], h[1], contact - 0.05));
        at(1.07);
        long sealed = read(v, h, contact - 0.05);
        assertEquals(-1_550_000 - 300_000 * (1 - Math.exp(-0.07 / 0.03)), sealed, 2.0);
        at(2.0);
        assertEquals(-1_850_000, read(v, h, contact - 0.05), 1.0);
        // Lift back to the last open coarse step: 0.37 s later the seal has not let go by the
        // prober's rule (|mean - baseline| < drop/2, drop = 0.15 * |baseline|).
        assertNull(v.onLine("G1 Z" + (contact + 0.20) + " F300", h[0], h[1], contact + 0.20));
        at(2.37);
        long lifting = read(v, h, contact + 0.20);
        assertEquals(-1_550_000 - 300_000 * Math.exp(-0.37 / 0.4), lifting, 2.0);
        assertTrue(Math.abs(lifting - -1_550_000) > 0.15 * 1_550_000 / 2, "still sealed: " + lifting);
        at(4.0);
        assertEquals(-1_550_000, read(v, h, contact + 0.20), 3000);
    }

    /** GcodeServer passes where its motion plan ends and when: the seal forms on arrival, not at the next line. */
    @Test
    public void sealFormsWhenTheHeadArrives() {
        SimScene s = scene();
        s.vacuum.sealTauS = 0.03;
        SimVacuum v = vacuum(s);
        double[] h = head(s, 37, 37);
        double contact = s.boardContactZ(37, 37);
        at(0);
        vacuumOn(v, h, contact + 0.25);
        at(1.0);
        // "M400" received while the 0.25 mm step down is planned to end 0.1 s later.
        assertNull(v.onLine("M400", h[0], h[1], contact, 0.1));
        at(1.05);
        assertNull(v.onLine("M260 A112 B1 S1", h[0], h[1], contact, 0.05));
        assertNull(v.onLine("M260 A109 B6 S1", h[0], h[1], contact, 0.05));
        assertEquals(-1_550_000, parse(v.onLine("M261 A109 B3 S1", h[0], h[1], contact, 0.05)), "still moving");
        // The prober reads 70 ms after M400 returned (at 1.1 s).
        at(1.17);
        long first = read(v, h, contact);
        assertEquals(-1_550_000 - 300_000 * (1 - Math.exp(-0.07 / 0.03)), first, 2.0);
    }

    @Test
    public void leakySpotReadsOnlyTheLeakDrop() {
        SimScene s = scene();
        s.leakySpots.add(new SimScene.Spot(8, 8, 0.6));
        SimVacuum v = vacuum(s);
        double contact = s.boardContactZ(8.3, 8);
        double[] h = head(s, 8.3, 8);
        vacuumOn(v, h, 8);
        assertEquals(-1_550_000 - 15_000, read(v, h, contact - 0.3));
        double[] away = head(s, 8, 10);
        assertEquals(-1_850_000, read(v, away, s.boardContactZ(8, 10) - 0.01), "2 mm away it seals");
    }

    @Test
    public void tipDiscPartlyOffTheBoardDoesNotSeal() {
        SimScene s = scene();
        SimVacuum v = vacuum(s);
        double[] edge = head(s, 0.2, 37.5); // the 0.35 mm tip disc reaches 0.15 mm past the board edge
        vacuumOn(v, edge, 8);
        double contact = s.boardContactZ(0.2, 37.5);
        assertEquals(-1_550_000 - 15_000, read(v, edge, contact - 0.05));
        assertEquals(-1_550_000 - 15_000, read(v, edge, 2.0), "below the bed too: still the edge");
        double[] inside = head(s, 0.5, 37.5);
        assertEquals(-1_850_000, read(v, inside, s.boardContactZ(0.5, 37.5) - 0.01));
    }

    @Test
    public void blankAndBedHaveTheirOwnContact() {
        SimScene s = scene();
        SimVacuum v = vacuum(s);
        double[] onBlank = {300 - s.n1OffsetX, 230 - s.n1OffsetY};
        double blankZ = s.blankContactZ(300, 230);
        assertEquals(5.62 - 0.0012 * 20, blankZ, 1e-12, "z0 at the blank rectangle's first corner");
        vacuumOn(v, onBlank, 8);
        assertEquals(-1_550_000, read(v, onBlank, blankZ + 0.05));
        assertEquals(-1_850_000, read(v, onBlank, blankZ - 0.01));
        double[] bed = {100 - s.n1OffsetX, 100 - s.n1OffsetY};
        assertEquals(-1_550_000, read(v, bed, 3.0), "off the board: no contact above the bed");
        assertEquals(-1_850_000, read(v, bed, 2.5));
    }

    @Test
    public void tipStates() {
        SimScene s = scene();
        SimVacuum v = vacuum(s);
        double[] h = head(s, 37, 37);
        double contact = s.boardContactZ(37, 37);
        vacuumOn(v, h, 8);
        s.vacuum.tip = "missing";
        assertEquals(-400_000, read(v, h, 8));
        assertEquals(-400_000, read(v, h, contact - 0.1), "no tip, no seal");
        s.vacuum.tip = "blocked";
        assertEquals(-2_100_000, read(v, h, 8));
        s.vacuum.tip = "part";
        assertEquals(-1_650_000, read(v, h, 8), "a held part: 100 k below open air");
        assertEquals(-1_950_000, read(v, h, contact + 0.44), "the part seals 0.45 mm high");
        s.vacuum.tip = "normal";
        assertEquals(-1_550_000, read(v, h, contact + 0.44));
    }

    @Test
    public void sealDropsAreDrawnPerSpotFromTheDeciles() {
        SimScene s = scene();
        s.vacuum.sealDrops = new SimScene.Vacuum().sealDrops;
        List<Double> drops = new ArrayList<>();
        Set<Long> distinct = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            double bx = 5 + i;
            double by = 5 + (i * 7) % 65;
            Point2D.Double m = s.toMachine(bx, by);
            double d = SimVacuum.sealDrop(s.vacuum, m.x, m.y);
            assertTrue(d >= 257000 && d <= 404000, String.valueOf(d));
            assertEquals(d, SimVacuum.sealDrop(s.vacuum, Math.floor(m.x) + 0.9, Math.floor(m.y) + 0.1), 0,
                    "the same spot draws the same drop");
            drops.add(d);
            distinct.add(Math.round(d));
        }
        assertTrue(distinct.size() > 30, "drops vary between spots: " + distinct.size());
        s.vacuum.seed = 8;
        Point2D.Double m = s.toMachine(5, 5);
        assertNotEquals(drops.get(0), SimVacuum.sealDrop(s.vacuum, m.x, m.y), "another seed, other drops");
        // A reading sealed at a spot uses that spot's drop.
        s.vacuum.seed = 7;
        SimVacuum v = vacuum(s);
        double[] h = head(s, 5, 5);
        vacuumOn(v, h, 8);
        assertEquals(-1_550_000 - drops.get(0), read(v, h, s.boardContactZ(5, 5) - 0.01), 1.0);
    }

    @Test
    public void noiseIsSeeded() {
        List<Long> a = sequence(7);
        assertEquals(a, sequence(7));
        assertNotEquals(a, sequence(8));
        double mean = a.stream().mapToLong(Long::longValue).average().getAsDouble();
        assertEquals(-1_550_000, mean, 1000);
        long spread = a.stream().mapToLong(Long::longValue).max().getAsLong()
                - a.stream().mapToLong(Long::longValue).min().getAsLong();
        assertTrue(spread > 1000 && spread < 15000, "sigma 1500 over 20 reads: " + spread);
    }

    List<Long> sequence(long seed) {
        SimScene s = scene();
        s.vacuum.noise = 1500;
        s.vacuum.seed = seed;
        SimVacuum v = vacuum(s);
        double[] h = head(s, 37, 37);
        vacuumOn(v, h, 8);
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            out.add(read(v, h, 8));
        }
        return out;
    }

    @Test
    public void reloadsWhenTheSceneFileChanges() throws Exception {
        File dir = Files.createTempDirectory("sim-scene").toFile();
        File f = SimGuard.sceneFile(dir);
        long[] nowMs = {1000};
        write(f, 5.00);
        SimScene.Source src = new SimScene.Source(f, () -> nowMs[0], (sc) -> null);
        SimScene first = src.get();
        assertEquals(5.00, first.boardSurface.z0, 0);
        SimVacuum v = new SimVacuum(src, () -> nowNs[0]);
        double[] h = head(first, 37, 37);
        vacuumOn(v, h, 8);
        double c0 = first.boardContactZ(37, 37);
        assertEquals(-1_850_000, read(v, h, c0 - 0.01));
        // The board is lowered by 0.5 mm in the running session.
        write(f, 4.50);
        nowMs[0] += 100;
        assertEquals(first, src.get(), "checked at most every 250 ms");
        nowMs[0] += 200;
        SimScene second = src.get();
        assertEquals(4.50, second.boardSurface.z0, 0);
        assertEquals(-1_550_000, read(v, h, c0 - 0.01), "no contact any more at the old height");
        // A file that does not parse keeps the last good scene; a deleted one turns the simulator off.
        Files.write(f.toPath(), "{".getBytes(StandardCharsets.UTF_8));
        nowMs[0] += 300;
        assertEquals(second, src.get());
        assertTrue(f.delete());
        nowMs[0] += 300;
        assertNull(src.get());
        assertNull(v.onLine("M261 A109 B3 S1", h[0], h[1], 8), "no scene: not answered");
    }

    /** A scene without board.size: the outline comes from the board once it is loaded (boards load on demand). */
    @Test
    public void boardSizeComesFromTheBoardOnceItIsLoaded() throws Exception {
        File f = SimGuard.sceneFile(Files.createTempDirectory("sim-scene").toFile());
        write(f, 5.00, false);
        long[] nowMs = {1000};
        Board[] loaded = {null};
        SimScene.Source src = new SimScene.Source(f, () -> nowMs[0], (sc) -> loaded[0]);
        SimVacuum v = new SimVacuum(src, () -> nowNs[0]);
        double[] h = head(src.get(), 37, 37);
        vacuumOn(v, h, 8);
        assertEquals(-1_550_000, read(v, h, 4.99), "board not loaded yet: no outline, no seal");
        Board b = new Board();
        b.setDimensions(new Location(LengthUnit.Millimeters, 75, 75, 0, 0));
        loaded[0] = b;
        nowMs[0] += 300;
        assertEquals(-1_850_000, read(v, h, 4.99), "loaded after the first read: its outline is used");
        assertEquals(75, src.get().boardWidthMm, 0);
    }

    /** new SimVacuum(scene) with no board size: the configuration's matching board gives the outline. */
    @Test
    public void fixedSceneTakesTheBoardSizeFromTheConfiguration() throws Exception {
        File dir = Files.createTempDirectory("sim-config").toFile();
        Configuration.initialize(dir);
        Configuration.get().load();
        SimScene s = scene();
        s.boardWidthMm = 0;
        s.boardHeightMm = 0;
        SimVacuum v = new SimVacuum(s);
        double[] h = head(s, 37, 37);
        double contact = s.boardContactZ(37, 37);
        vacuumOn(v, h, 8);
        assertEquals(-1_550_000, read(v, h, contact - 0.01), "no such board in the configuration: no outline");
        Board b = Configuration.get().getBoard(new File(dir, "toy.board.xml"));
        b.setDimensions(new Location(LengthUnit.Millimeters, 75, 75, 0, 0));
        assertEquals(-1_850_000, read(v, h, contact - 0.01));
        assertEquals(75, s.boardHeightMm, 0);
    }

    static void write(File f, double z0) throws Exception {
        write(f, z0, true);
    }

    /** As il_sim_scene.py write(): a temp file renamed over the scene. */
    static void write(File f, double z0, boolean size) throws Exception {
        f.getParentFile().mkdirs();
        String json = "{\"version\": 1, \"board\": {\"file\": \"toy.board.xml\"," + (size ? " \"size\": [75, 75]," : "")
                + " \"seat\": {\"x\": 300, \"y\": 115, \"rotationDeg\": 1.5},"
                + " \"surface\": {\"z0\": " + z0 + ", \"slopeX\": 0, \"slopeY\": 0}},"
                + " \"vacuum\": {\"noise\": 0, \"tauS\": 0, \"sealTauS\": 0, \"releaseTauS\": 0, \"sealDrops\": [300000]}}";
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        assertNotNull(SimScene.load(f));
    }
}
