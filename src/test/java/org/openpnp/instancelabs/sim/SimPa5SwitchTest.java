package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;
import org.openpnp.util.GcodeServer;

/**
 * The limit switch on PA5 (the user, 11-04) in OpenPnP's simulated controller:
 * lumen-sim's switch (infra/lumen-sim/tests/test_pa5_switch.py), set by the sim scene's "pa5Switch". N1 presses it as
 * raw Z falls to its trip point, the needle as Z rises (the see-saw); M43 P5 reads it where the head is; each press
 * trips at its own point; a press past the overtravel is reported once, seen at the end of each move.
 */
public class SimPa5SwitchTest {
    /** The real board's answer to M43 P5 (runs/serial.log, 11-02, nothing wired: 0), byte for byte, up to the level. */
    static final String LINE = "PIN: PA5  (A9)   M42 P53          <unused/unknown>                       Input  = ";
    static final String N1 = "{\"tool\": \"n1\", \"x0\": 99.0, \"y0\": 49.0, \"x1\": 101.0, \"y1\": 51.0, "
            + "\"tripZ\": 12.0}";
    static final String NEEDLE = "{\"tool\": \"needle\", \"x0\": 199.0, \"y0\": 49.0, \"x1\": 201.0, \"y1\": 51.0, "
            + "\"tripZ\": 52.0}";

    SimMachine sim;
    GcodeServer server;
    Socket socket;
    BufferedReader in;
    OutputStream out;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine(false); // the LumenPnP axes for GcodeServer; the driver stays unconnected
    }

    @AfterEach
    public void after() throws Exception {
        if (server != null) {
            server.shutdown();
        }
        if (socket != null) {
            socket.close(); // after the shutdown: until then GcodeServer's worker of a closed connection spins
        }
        sim.close();
    }

    /** A sim scene with this "pa5Switch" block (null: none). */
    static String scene(String pa5) {
        return "{\"version\": 1, \"board\": {\"file\": \"toy.board.xml\", \"seat\": {\"x\": 0, \"y\": 0}}"
                + (pa5 == null ? "" : ", \"pa5Switch\": " + pa5) + "}";
    }

    /** lumen-sim's switch(): both windows, overtravel 0.5 mm. */
    static String wired(double repeatSdMm, int pressedLevel, long seed) {
        return String.format(Locale.ROOT, "{\"presses\": [%s, %s], \"overtravelMm\": 0.5, \"repeatSdMm\": %s, "
                + "\"pressedLevel\": %d, \"seed\": %d}", N1, NEEDLE, repeatSdMm, pressedLevel, seed);
    }

    static String wired() {
        return wired(0, 1, 0);
    }

    /** The scene file (null: none), then OpenPnP's simulator, talked to on one connection. */
    void start(String scene) throws Exception {
        if (scene != null) {
            File f = SimGuard.sceneFile(Configuration.get());
            assertTrue(f.getParentFile().mkdirs() || f.getParentFile().isDirectory());
            Files.write(f.toPath(), scene.getBytes(StandardCharsets.UTF_8));
        }
        server = new GcodeServer();
        server.setDriver(sim.driver);
        socket = new Socket(InetAddress.getLoopbackAddress(), server.getListenerPort());
        socket.setSoTimeout(10000);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = socket.getOutputStream();
    }

    /** A new connection: OpenPnP makes a new GcodeServer on every connect. */
    void reconnect() throws Exception {
        server.shutdown();
        socket.close();
        start(null);
    }

    /** One line and its reply lines up to "ok". */
    List<String> send(String line) throws Exception {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        List<String> reply = new ArrayList<>();
        String r;
        do {
            r = in.readLine();
            assertNotNull(r, "no reply to " + line);
            reply.add(r);
        } while (!r.startsWith("ok"));
        return reply;
    }

    /** Puts the head at raw X/Y/Z without a move (a G92, as the vacuum hook test does). */
    void place(double x, double y, double z) throws Exception {
        send(String.format(Locale.ROOT, "G92 X%s Y%s Z%s", x, y, z));
    }

    /** A Z move, then M400 (lumen-sim's to(b, z=...)). */
    void z(double z) throws Exception {
        send(String.format(Locale.ROOT, "G1 Z%s F12000", z));
        send("M400");
    }

    /** M43 P5 after the moves ended, as the engine reads it: the pin's level. */
    int level() throws Exception {
        List<String> r = send("M43 P5");
        assertEquals(2, r.size(), r.toString());
        assertEquals("ok", r.get(1));
        assertTrue(r.get(0).matches(Pattern.quote(LINE) + "[01]"), r.get(0));
        return Integer.parseInt(r.get(0).substring(LINE.length()));
    }

    SimPa5Switch pa5() {
        return server.getPa5();
    }

    /** The route's step (mm). */
    static final double STEP = 0.025;

    static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** From {@code from}, steps toward the switch ({@code way}: -1 N1, Z falls; 1 the needle) until M43 P5 reads 1. */
    double stepUntilPressed(double from, int way) throws Exception {
        double z = from;
        z(z);
        while (level() == 0) {
            z = round3(z + way * STEP);
            assertTrue(Math.abs(z - from) < 1, "pressed within 1 mm of " + from);
            z(z);
        }
        return z;
    }

    /** N1's window, then the needle's: its X, the Z the route starts from, the way Z goes to press it. */
    static final double[][] TOOLS = {{100, 12.2, -1}, {200, 51.8, 1}};

    /** The model alone: a move to (x, 50, z) ends now, then M43 P5 there: the pin's level. */
    static int to(SimPa5Switch sw, double x, double z) {
        sw.moveEnds(x, 50, z, 0);
        return read(sw, x, z);
    }

    /** The model alone: M43 P5 with the head at (x, 50, z). */
    static int read(SimPa5Switch sw, double x, double z) {
        String r = sw.onLine("M43 P5", x, 50, z);
        assertTrue(r != null && r.matches(Pattern.quote(LINE) + "[01]"), r);
        return Integer.parseInt(r.substring(LINE.length()));
    }

    static SimScene sceneOf(String pa5) {
        return SimScene.of(SimSceneTest.json(scene(pa5)));
    }

    @Test
    public void withoutASceneM43IsAsBefore() throws Exception {
        start(null);
        assertNull(server.getPa5(), "no scene, no switch model");
        assertEquals(List.of("ok"), send("M43 P5"));
    }

    @Test
    public void withoutASwitchItReadsAsTheBoardDid() throws Exception {
        start(scene(null));
        assertEquals(List.of(LINE + "0", "ok"), send("M43 P5"));
        assertEquals(List.of("ok"), send("M43"), "other M43 forms as before");
        assertEquals(List.of("ok"), send("M43 P6"));
        assertEquals(0, pa5().level());
        assertFalse(pa5().pressed());
        assertNull(pa5().tool());
        assertEquals(0, pa5().overtravels());
    }

    /**
     * M42 P53 T sets PA5's input mode, so what it reads with nothing wired (lumen-sim's pa5_pull): T2 pull-up 1, T3
     * pull-down and T0 floating 0 (as read 11-02); other M42 forms only ack. A new connection boots the controller.
     */
    @Test
    public void thePullWithoutASwitch() throws Exception {
        start(scene(null));
        for (String[] m : new String[][] {{"T2", "1"}, {"T3", "0"}, {"T0", "0"}, {"T2", "1"}}) {
            assertEquals(List.of("ok"), send("M42 P53 " + m[0]));
            assertEquals(Integer.parseInt(m[1]), level(), m[0]);
        }
        send("M42 P44 T3");
        send("M42 P53 T1");
        assertEquals(1, level(), "another pin, an output mode: as it was");
        reconnect();
        assertEquals(0, level(), "the pin as at boot");
    }

    /** pressedLevel and the other level are the switch's: M42's pull shows only with nothing wired. */
    @Test
    public void aWiredSwitchReadsItsOwnLevelsWhateverThePull() throws Exception {
        start(scene(wired()));
        place(100, 50, 13);
        send("M42 P53 T2");
        assertEquals(0, level());
    }

    @Test
    public void n1TripsItAsZFallsToTheTrip() throws Exception {
        start(scene(wired()));
        place(100, 50, 12.1);
        assertEquals(0, level());
        z(12.0);
        assertEquals(1, level());
        assertEquals("n1", pa5().tool());
        z(11.9);
        // lumen-sim's test_state
        assertEquals(1, pa5().level());
        assertTrue(pa5().pressed());
        assertEquals(12.0, pa5().tripZ(), 0);
        assertEquals(0.1, pa5().depthMm(), 1e-9);
        assertEquals(0, pa5().overtravels());
        assertEquals(List.of(), pa5().messages());
        z(13);
        assertEquals(0, level(), "released on the way back up");
        assertFalse(pa5().pressed());
        assertNull(pa5().tool());
        assertNull(pa5().tripZ());
        assertNull(pa5().depthMm());
    }

    @Test
    public void theNeedleTripsItAsZRisesToTheTrip() throws Exception {
        start(scene(wired()));
        place(200, 50, 51.9);
        assertEquals(0, level());
        z(52.0);
        assertEquals(1, level());
        assertEquals("needle", pa5().tool());
        z(40);
        assertEquals(0, level());
    }

    @Test
    public void nothingPressesItOutsideTheWindows() throws Exception {
        start(scene(wired()));
        place(150, 50, 13);
        z(5);
        assertEquals(0, level());
        z(60);
        assertEquals(0, level());
        assertEquals(List.of(), pa5().messages());
    }

    @Test
    public void aSwitchThatPullsThePinLow() throws Exception {
        start(scene(wired(0, 0, 0)));
        place(100, 50, 13);
        assertEquals(1, level());
        z(12);
        assertEquals(0, level());
    }

    /** M43 reads the pin at once: a read during a move sees the head where it is then. The engine sends M400 first. */
    @Test
    public void theReadUsesWhereTheHeadIsNow() throws Exception {
        start(scene(wired()));
        place(100, 50, 13);
        send("G1 Z12 F60"); // 1 mm/s: about 1 s down to the trip
        assertEquals(List.of(LINE + "0", "ok"), send("M43 P5"), "still on its way down");
        send("M400");
        assertEquals(1, level());
        // Across N1's window 0.1 mm past its point, from outside it to outside it: only reads mid-move can see it.
        z(11.9);
        send("G1 X95 F12000");
        send("M400");
        assertEquals(0, level());
        send("G1 X105 F600"); // 10 mm/s: over the window (x 99-101) for 0.2 s of about 1 s
        List<Integer> seen = new ArrayList<>();
        for (long until = System.nanoTime() + 1_500_000_000L; System.nanoTime() < until; ) {
            seen.add(level());
            Thread.sleep(10);
        }
        assertTrue(seen.contains(1), "pressed while over it: " + seen);
        assertEquals(0, level(), "released past it");
    }

    @Test
    public void aMoveThroughTheSwitchIsCaughtWithoutARead() throws Exception {
        start(scene(wired()));
        place(100, 50, 31.5);
        z(5);
        assertEquals(List.of("PA5 switch overtravel: n1 7.00 mm past its trip point (overtravel 0.50 mm)"),
                pa5().messages());
        z(31.5);
        // Down and up again with no M400 between: the end of the move down is seen when it comes, not at a read.
        send("G1 Z5 F12000");
        send("G1 Z31.5 F12000");
        send("M400");
        assertEquals(2, pa5().overtravels());
        assertFalse(pa5().pressed(), "and released at the top");
    }

    @Test
    public void overtravelIsReportedOncePerPress() throws Exception {
        start(scene(wired()));
        place(100, 50, 13);
        z(11.5);
        assertEquals(List.of(), pa5().messages(), "0.5 mm past the trip: within the overtravel");
        z(11.475);
        assertEquals(List.of("PA5 switch overtravel: n1 0.53 mm past its trip point (overtravel 0.50 mm)"),
                pa5().messages());
        z(11.0);
        assertEquals(1, pa5().messages().size(), "one per press");
        assertEquals(1.0, pa5().depthMm(), 1e-9);
        z(13);
        z(11.0);
        assertEquals(2, pa5().messages().size(), "a new press, a new report");
        assertEquals(2, pa5().overtravels());
    }

    @Test
    public void theNeedlesOvertravel() throws Exception {
        start(scene(wired()));
        place(200, 50, 40);
        z(52.6);
        assertEquals(List.of("PA5 switch overtravel: needle 0.60 mm past its trip point (overtravel 0.50 mm)"),
                pa5().messages());
    }

    /**
     * A press holds its own point until released: pressed at the first step past it (so within the noise of it), each
     * read and each move that stays past it reads pressed, press after press.
     */
    @Test
    public void aPressHoldsItsPointUntilReleased() throws Exception {
        start(scene(wired(0.01, 1, 0)));
        place(100, 50, 12.2);
        for (int press = 1; press <= 12; press++) {
            double z = stepUntilPressed(12.2, -1);
            Double trip = pa5().tripZ();
            z(round3(z - 0.01));
            z(z);
            assertEquals(List.of(1, 1, 1), List.of(level(), level(), level()), "press " + press);
            assertEquals(trip, pa5().tripZ(), "press " + press);
        }
    }

    /**
     * The route steps toward the switch until it reads pressed, then one step back: released, and it stays released
     * while the head stands still (a new press needs a move toward the switch), whatever each press's own point. Many
     * presses of one seed: Java's first draws for seeds 0..39 are mostly high, so one press per seed would miss it.
     */
    @Test
    public void aOneStepBackOffReleasesItPressAfterPress() throws Exception {
        start(scene(wired(0.01, 1, 0)));
        for (double[] t : TOOLS) {
            place(t[0], 50, t[1]);
            for (int press = 1; press <= 12; press++) {
                double z = stepUntilPressed(t[1], (int) t[2]);
                z(round3(z - t[2] * STEP));
                assertEquals(List.of(0, 0), List.of(level(), level()), "x " + t[0] + ", press " + press);
            }
        }
    }

    /**
     * The release point follows the head as it moves away: back at the switch after a lift, a press whose own point
     * lies behind the release point trips there, not a step late.
     */
    @Test
    public void afterABackOffAndALiftTheNextPressTripsAtItsOwnPoint() throws Exception {
        start(scene(wired(0.01, 1, 0)));
        for (double[] t : TOOLS) {
            place(t[0], 50, t[1]);
            for (int press = 1; press <= 12; press++) {
                double z = stepUntilPressed(t[1], (int) t[2]);
                z(round3(z - t[2] * STEP)); // one step back: released
                z(round3(t[1] - t[2] * 0.3)); // lifted away
                stepUntilPressed(t[1], (int) t[2]);
                Double depth = pa5().depthMm();
                assertTrue(depth != null && depth < STEP, "x " + t[0] + ", press " + press + ": depth " + depth);
            }
        }
    }

    /** Off the window, or in another tool's, the release point is gone: back in a window a press starts afresh. */
    @Test
    public void leavingTheWindowClearsTheReleasePoint() {
        String n1b = "{\"tool\": \"n1\", \"x0\": 109.0, \"y0\": 49.0, \"x1\": 111.0, \"y1\": 51.0, \"tripZ\": 12.1}";
        for (double[] away : new double[][] {{105, 12.025}, {200, 20}}) { // in no window; in the needle's
            SimPa5Switch sw = new SimPa5Switch(sceneOf("{\"presses\": [" + N1 + ", " + n1b + ", " + NEEDLE + "]}"));
            assertEquals(1, to(sw, 100, 12.0));
            assertEquals(0, to(sw, 100, 12.025), "one step back: released");
            to(sw, away[0], away[1]);
            assertEquals(1, to(sw, 110, 12.025), "via x " + away[0] + ": N1's other window, 0.075 mm past its point");
        }
    }

    /** A changed switch is a new one: with the head at rest after a back-off, it sees the head afresh. */
    @Test
    public void aChangedSwitchStartsAfresh() {
        SimScene[] now = {sceneOf(wired())};
        SimPa5Switch sw = new SimPa5Switch(() -> now[0], System::nanoTime);
        assertEquals(1, to(sw, 100, 12.0));
        assertEquals(0, to(sw, 100, 12.025), "one step back: released");
        now[0] = sceneOf("{\"presses\": [" + N1.replace("12.0", "12.05") + "]}");
        assertEquals(1, read(sw, 100, 12.025), "at rest 0.025 mm past the new switch's point");
    }

    /**
     * The scene file written again with the same switch (the acceptance scripts change other keys mid-session) keeps
     * it: its press, count, messages and draws. A changed switch is a new one: count and messages from 0 together.
     */
    @Test
    public void theSameSwitchReadAgainKeepsItsState() throws Exception {
        File f = new File(sim.configDir, "pa5-scene.json");
        Files.write(f.toPath(), scene(wired(0.01, 1, 7)).getBytes(StandardCharsets.UTF_8));
        long[] ms = {0};
        SimPa5Switch sw = new SimPa5Switch(new SimScene.Source(f, () -> ms[0], (sc) -> null), System::nanoTime);
        List<Double> trips = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            sw.moveEnds(100, 50, 11.0, 0); // 1 mm past its point: reported
            trips.add(sw.tripZ());
            if (i == 2) {
                String other = scene(wired(0.01, 1, 7)).replace("\"version\": 1", "\"version\": 1, \"bedZ\": 2.5");
                Files.write(f.toPath(), other.getBytes(StandardCharsets.UTF_8));
                ms[0] += 1000;
                sw.moveEnds(100, 50, 11.0, 0); // the head still there
                assertEquals(trips.get(1), sw.tripZ(), "the press under way, kept");
            }
            assertEquals(i, sw.overtravels());
            assertEquals(i, sw.messages().size(), "one report per press");
            sw.moveEnds(100, 50, 13, 0);
        }
        assertEquals(trips(7).subList(0, 4), trips, "the draws go on");
        Files.write(f.toPath(), scene(wired(0.01, 1, 8)).getBytes(StandardCharsets.UTF_8));
        ms[0] += 1000;
        assertEquals(0, sw.overtravels());
        assertEquals(List.of(), sw.messages());
        sw.moveEnds(100, 50, 11.0, 0);
        assertEquals(List.of(1, 1), List.of(sw.overtravels(), sw.messages().size()));
    }

    /** The switch is the machine's: a reconnect (a new GcodeServer) keeps its count, messages and draws. */
    @Test
    public void theSwitchOutlivesAReconnect() throws Exception {
        start(scene(wired(0.01, 1, 7)));
        place(100, 50, 13);
        z(11.0); // 1 mm past its point: reported
        z(13);
        reconnect();
        place(100, 50, 13);
        z(11.0);
        assertEquals(trips(7).get(1), pa5().tripZ(), "the seed's second point");
        assertEquals(2, pa5().overtravels());
        assertEquals(2, pa5().messages().size());
    }

    /**
     * M400 returns up to a millisecond early, so the plan's last end within 2 ms counts as reached. An end within 2 ms
     * with a move after it does not: a read then sees the head on its way, never behind an end already seen.
     */
    @Test
    public void onlyThePlansLastEndIsSeenEarly() {
        long[] ns = {0};
        SimScene sc = sceneOf(wired());
        SimPa5Switch sw = new SimPa5Switch(() -> sc, () -> ns[0]);
        sw.moveEnds(100, 50, 13, 0);
        sw.moveEnds(100, 50, 11.999, 0.001); // down past its point (12.0), ending in 1 ms
        sw.moveEnds(100, 50, 13, 0.1); // then up again
        assertEquals(0, read(sw, 100, 12.0005), "on its way down, not yet at its point");
        ns[0] = 2_000_000;
        assertTrue(sw.pressed(), "seen where the move down ended");
        ns[0] = 99_000_000;
        assertFalse(sw.pressed(), "the last end, 1 ms ahead: reached");
    }

    /** Repeat noise: a new trip point per press, tripZ + N(0, repeatSdMm), the same for the same seed. */
    @Test
    public void eachPressTripsAtItsOwnPoint() {
        List<Double> a = trips(7);
        assertEquals(a, trips(7), "reproducible");
        assertNotEquals(a, trips(8));
        assertEquals(40, new HashSet<>(a).size(), "a new point every press");
        double mean = a.stream().mapToDouble(Double::doubleValue).average().getAsDouble();
        double sd = Math.sqrt(a.stream().mapToDouble((t) -> (t - mean) * (t - mean)).sum() / (a.size() - 1));
        assertEquals(12.0, mean, 0.006);
        assertTrue(sd > 0.005 && sd < 0.02, "sd " + sd);
    }

    /** 40 presses of N1 (down to 11.9, back up to 13) with the switch of a scene built in code. */
    static List<Double> trips(long seed) {
        SimScene sc = new SimScene();
        sc.seat = new AffineTransform();
        sc.pa5Switch = new SimScene.Pa5Switch(new SimScene.Pa5Press("n1", 99, 49, 101, 51, 12.0));
        sc.pa5Switch.repeatSdMm = 0.01;
        sc.pa5Switch.seed = seed;
        sc.validate();
        SimPa5Switch sw = new SimPa5Switch(sc);
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            sw.moveEnds(100, 50, 11.9, 0);
            out.add(sw.tripZ());
            sw.moveEnds(100, 50, 13, 0);
            assertFalse(sw.pressed());
        }
        return out;
    }

    @Test
    public void badSettingsAreRefusedAndChangeNothing() throws Exception {
        String window = "\"x0\": 99.0, \"y0\": 49.0, \"x1\": 101.0, \"y1\": 51.0";
        String[] bad = {"{\"presses\": [{\"tool\": \"n2\", " + window + ", \"tripZ\": 12.0}]}",
                "{\"presses\": [{\"tool\": \"n1\", \"tripZ\": 12}]}",
                "{\"presses\": [{\"tool\": \"n1\", \"x0\": 102.0, \"y0\": 49.0, \"x1\": 101.0, \"y1\": 51.0, "
                        + "\"tripZ\": 12.0}]}",
                "{\"presses\": [{\"tool\": \"n1\", " + window + ", \"tripZ\": \"12\"}]}",
                "{\"presses\": [" + N1 + "], \"overtravelMm\": -0.1}",
                "{\"presses\": [" + N1 + "], \"repeatSdMm\": NaN}",
                "{\"presses\": [" + N1 + "], \"pressedLevel\": 2}",
                "{\"presses\": [" + N1 + "], \"seed\": 1.5}",
                "{\"presses\": [{\"tool\": \"n1\", " + window + ", \"tripZ\": 12.0, \"extra\": 1}]}",
                "{\"presses\": [" + N1 + "], \"overtravels\": 3}",
                "{\"presses\": []}",
                "{\"presses\": [{\"tool\": [\"n1\"], " + window + ", \"tripZ\": 12.0}]}",
                "{\"presses\": {\"tool\": \"n1\"}}",
                "{\"presses\": [" + N1 + "], \"repeatSdMm\": 2.0}",
                "{\"presses\": [" + N1 + "], \"overtravelMm\": 11.0}",
                // As lumen-sim: null is no default; a level or seed written as a fraction or exponent is no integer.
                "{\"presses\": [" + N1 + "], \"overtravelMm\": null}",
                "{\"presses\": [" + N1 + "], \"repeatSdMm\": null}",
                "{\"presses\": [" + N1 + "], \"pressedLevel\": null}",
                "{\"presses\": [" + N1 + "], \"seed\": null}",
                "{\"presses\": [" + N1 + "], \"pressedLevel\": 1.0}",
                "{\"presses\": [" + N1 + "], \"seed\": 1e3}",
                "{\"presses\": [" + N1 + "], \"seed\": 9223372036854775808}"};
        // The scene file of a running session: a file the scene refuses keeps the last good one, and so the switch.
        File f = new File(sim.configDir, "pa5-scene.json");
        Files.write(f.toPath(), scene(wired()).getBytes(StandardCharsets.UTF_8));
        long[] ms = {0};
        SimScene.Source source = new SimScene.Source(f, () -> ms[0], (sc) -> null);
        SimPa5Switch sw = new SimPa5Switch(source, System::nanoTime);
        sw.moveEnds(100, 50, 11.9, 0);
        assertEquals(12.0, sw.tripZ(), 0);
        SimScene good = source.get();
        for (String b : bad) {
            assertThrows(IllegalArgumentException.class, () -> SimScene.of(SimSceneTest.json(scene(b))), b);
            Files.write(f.toPath(), scene(b).getBytes(StandardCharsets.UTF_8));
            ms[0] += 1000;
            assertSame(good, source.get(), b);
            assertEquals(12.0, sw.tripZ(), 0, "the press as it was: " + b);
            assertEquals(2, good.pa5Switch.presses.size());
        }
        // lumen-sim's limits, edges included: overtravelMm 0-10, repeatSdMm 0-1.
        SimScene edges = sceneOf("{\"presses\": [" + N1 + "], \"overtravelMm\": 10, \"repeatSdMm\": 1}");
        assertEquals(List.of(10.0, 1.0), List.of(edges.pa5Switch.overtravelMm, edges.pa5Switch.repeatSdMm));
        // A seed is any integer in lumen-sim; here any long (java.util.Random takes no more).
        for (long seed : new long[] {-4294967296L, Long.MAX_VALUE}) {
            assertEquals(seed, sceneOf("{\"presses\": [" + N1 + "], \"seed\": " + seed + "}").pa5Switch.seed);
        }
        // Set in code: validate() refuses it (the models take the scene as validated, as SimVacuum does).
        SimScene sc = SimScene.of(SimSceneTest.json(scene(wired())));
        for (double sd : new double[] {Double.NaN, 1.5}) {
            sc.pa5Switch.repeatSdMm = sd;
            assertThrows(IllegalArgumentException.class, sc::validate, "repeatSdMm " + sd);
        }
    }
}
