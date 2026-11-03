package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.spi.MotionPlanner.CompletionType;
import org.openpnp.spi.base.AbstractNozzle;

/**
 * The N1 vacuum probe against a scripted controller: a seal at a known Z with partial seals, a slow
 * release and an open-air settle (virtual clock), driven through a real {@link DispenseExecutor} and its
 * probe gate. The exact G-code, the fine-pass rules (with the bench trace of 10-27 17:45:36 replayed),
 * the window, every exit and the open-air checks.
 */
public class VacuumProberTest {
    static final String READ_1 = "M260 A112 B1 S1";
    static final String READ_2 = "M260 A109 B6 S1";
    static final String READ_3 = "M261 A109 B3 S1";
    static final List<String> END = Arrays.asList("G1 Z31.5000 F600", "M400", "M107 P1", "M107 P0");
    static final List<String> STOP = Arrays.asList("M410", "M107 P3", "M107 P2", "M107 P1", "M107 P0", "M18 B");
    static final double N1X = -24.178, N1Y = -63.91;

    /** N1 written out by hand: raw X/Y = tip - head offset (-24.178, -63.91), raw Z = N1 Z. */
    static class N1Mapper implements RawMapper {
        double[] planned = {300, 200, 31.5};
        AxisBacklash[] backlash = {AxisBacklash.none(), AxisBacklash.none(), AxisBacklash.none()};
        double[] zone = {26.5, 36.5};
        boolean transformedZ;

        @Override
        public String letterX() {
            return "X";
        }

        @Override
        public String letterY() {
            return "Y";
        }

        @Override
        public String letterZ() {
            return "Z";
        }

        @Override
        public String letterB() {
            return "B";
        }

        @Override
        public RawPoint toRaw(double x, double y, double z) {
            return new RawPoint(x - N1X, y - N1Y, transformedZ ? 63 - z : z);
        }

        @Override
        public AxisBacklash[] backlash() {
            return backlash;
        }

        @Override
        public double[] currentPlanned() {
            return planned.clone();
        }

        @Override
        public double[] currentDriver() {
            return planned.clone();
        }

        @Override
        public double currentB() {
            return 0;
        }

        @Override
        public double[] currentNozzleLocation() {
            return new double[] {planned[0] + N1X, planned[1] + N1Y, planned[2]};
        }

        @Override
        public double[] zSafeZoneRaw() {
            return zone;
        }
    }

    /**
     * The controller: G-code position, pump and valve, and VAC1 as a first-order lag toward its target
     * (open air, a seal at Z <= the contact Z of the tip's X/Y, half a seal within the partial band above
     * it, a leak at a leaky spot, 0 with the vacuum off): {@code tauOn} after the valve opens, almost
     * instant sealing, {@code releaseTau} when a seal lets go. Scripted readings, when queued, are
     * returned instead (the model runs on underneath).
     */
    static class VacChannel implements GcodeChannel {
        final List<String> sent = new ArrayList<>();
        long now = 1_000_000;
        double x = 300, y = 200, z = 31.5;
        boolean pump, valve;
        double openAir = -1_550_000, sealDrop = 300_000, leakDrop = 15_000, partialBand = 0.03;
        double tauOnMs = 150, releaseTauMs = 400;
        /** Contact Z of the tip at (x, y); NaN = a hole (no contact). */
        java.util.function.BiFunction<Double, Double, Double> contact = (a, b) -> 4.52;
        final List<double[]> leaky = new ArrayList<>();
        final Deque<Integer> script = new ArrayDeque<>();
        /** Real readings by N1 Z and the read's index at that Z (null = the model); used with the vacuum on. */
        java.util.function.BiFunction<Double, Integer, Integer> byZ;
        double lastReadZ = Double.NaN;
        int readsAtZ;
        /** Per-read noise pattern added to the model value (cycled). */
        int[] noise = {0};
        int reads;
        Consumer<String> onLine;
        String failOn;
        Exception failWith;
        List<String> readReply;
        double v0, target;
        long t0 = now;
        double tau = 1;

        double value() {
            return target + (v0 - target) * Math.exp(-(now - t0) / tau);
        }

        double targetNow() {
            if (!(pump && valve)) {
                return 0;
            }
            double tx = x + N1X, ty = y + N1Y;
            Double cz = contact.apply(tx, ty);
            if (cz == null || Double.isNaN(cz)) {
                return openAir;
            }
            for (double[] l : leaky) {
                if (Math.hypot(tx - l[0], ty - l[1]) <= l[2] && z <= cz + 1e-9) {
                    return openAir - leakDrop;
                }
            }
            if (z <= cz + 1e-9) {
                return openAir - sealDrop;
            }
            if (z <= cz + partialBand + 1e-9) {
                return openAir - sealDrop / 2;
            }
            return openAir;
        }

        void retarget(boolean outputs) {
            double v = value();
            double t = targetNow();
            if (Math.abs(t - target) < 1e-9) {
                return;
            }
            tau = outputs ? (t == 0 ? 1 : tauOnMs) : t < target ? 1 : releaseTauMs;
            v0 = v;
            t0 = now;
            target = t;
        }

        static final Pattern WORD = Pattern.compile("([XYZF])(-?[0-9.]+)");

        @Override
        public void send(String line, long timeoutMs) throws Exception {
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("no timeout");
            }
            if (failOn != null && line.equals(failOn)) {
                failOn = null;
                sent.add(line);
                throw failWith;
            }
            sent.add(line);
            if (onLine != null) {
                onLine.accept(line);
            }
            now += 1;
            if (line.startsWith("G4 P")) {
                now += Long.parseLong(line.substring(4));
            }
            else if (line.startsWith("G1 ")) {
                Matcher m = WORD.matcher(line);
                double nx = x, ny = y, nz = z, f = 600;
                while (m.find()) {
                    double v = Double.parseDouble(m.group(2));
                    switch (m.group(1)) {
                        case "X":
                            nx = v;
                            break;
                        case "Y":
                            ny = v;
                            break;
                        case "Z":
                            nz = v;
                            break;
                        default:
                            f = v;
                    }
                }
                now += (long) (Math.sqrt((nx - x) * (nx - x) + (ny - y) * (ny - y) + (nz - z) * (nz - z)) / (f / 60)
                        * 1000);
                x = nx;
                y = ny;
                z = nz;
                retarget(false);
            }
            else if (line.equals("M106")) {
                pump = true;
                retarget(true);
            }
            else if (line.equals("M106 P1 S180")) {
                valve = true;
                retarget(true);
            }
            else if (line.equals("M107 P1")) {
                valve = false;
                retarget(true);
            }
            else if (line.equals("M107 P0")) {
                pump = false;
                retarget(true);
            }
        }

        @Override
        public List<String> sendAndCollect(String line, long timeoutMs) throws Exception {
            send(line, timeoutMs);
            if (!line.equals(READ_3)) {
                return Arrays.asList("ok");
            }
            if (readReply != null) {
                return readReply;
            }
            readsAtZ = Math.abs(z - lastReadZ) < 1e-9 ? readsAtZ + 1 : 0;
            lastReadZ = z;
            Integer real = byZ != null && pump && valve ? byZ.apply(z, readsAtZ) : null;
            int v = real != null ? real
                    : script.isEmpty() ? (int) Math.round(value()) + noise[reads % noise.length] : script.poll();
            reads++;
            int u = v & 0xFFFFFF;
            return Arrays.asList(String.format(Locale.ROOT, "echo:i2c-reply: from:109 bytes:3 data:%02X %02X %02X",
                    u >> 16, (u >> 8) & 0xFF, u & 0xFF), "ok");
        }
    }

    /** One probe task: channel, executor and its probe gate, settings, prober. */
    static class Rig {
        final VacChannel ch = new VacChannel();
        final DispenseConfig cfg = new DispenseConfig();
        final DispenseExecutor ex = new DispenseExecutor(cfg, ch, "B", () -> ch.now);
        final RunResult r = new RunResult();
        final VacuumProber.Settings s = VacuumProber.Settings.of(cfg, true);
        RawMapper n1 = new N1Mapper();
        VacuumProber.SpotLook look;
        VacuumProber.BaselineMemory mem = new VacuumProber.BaselineMemory();
        final List<String> progress = new ArrayList<>();
        int at;

        Rig() {
            s.clock = () -> ch.now;
        }

        VacuumProber.Io io() {
            return new VacuumProber.Io() {
                @Override
                public void send(String line, long timeoutMs) throws Exception {
                    ex.sendGated(DispenseExecutor.Scope.PROBE, line, timeoutMs, r);
                }

                @Override
                public List<String> query(String line, long timeoutMs) throws Exception {
                    return ex.queryGated(DispenseExecutor.Scope.PROBE, line, timeoutMs, r);
                }

                @Override
                public void sleep(long ms) throws Exception {
                    if (ex.isAbortRequested()) {
                        throw new DispenseExecutor.AbortedException(ex.getAbortReason());
                    }
                    ch.now += ms;
                }

                @Override
                public void shutdown() {
                    ex.shutdown(r);
                }
            };
        }

        VacuumProber prober() {
            return new VacuumProber(s, n1, io(), look, r, ex::isAbortRequested, p -> progress.add(p.state), mem);
        }

        /** Lines from the last {@link #mark()} on. */
        List<String> lines() {
            return ch.sent.subList(at, ch.sent.size());
        }

        void mark() {
            at = ch.sent.size();
        }
    }

    static VacuumProber.Target target(int n, double x, double y, Double bx, Double by) {
        VacuumProber.Target t = new VacuumProber.Target();
        t.n = n;
        t.x = x;
        t.y = y;
        t.bx = bx;
        t.by = by;
        return t;
    }

    /** The spec's example: board top 4.50, N1 tip (303.806, 122.623), raw head (327.984, 186.533). */
    static VacuumProber.Target example() {
        return target(1, 303.806, 122.623, 8.0, 8.0);
    }

    static void withAlternates(VacuumProber.Target t) {
        t.alternates.add(new double[] {t.x + 2, t.y});
        t.alternates.add(new double[] {t.x, t.y + 2});
        t.alternatesBoard.add(new double[] {t.bx + 2, t.by});
        t.alternatesBoard.add(new double[] {t.bx, t.by + 2});
    }

    static VacuumProber.Window window(Rig rig, double nominal, Double anchor) throws Exception {
        return VacuumProber.Window.of(nominal, anchor, 31.5, rig.s);
    }

    static List<String> read() {
        return Arrays.asList(READ_1, READ_2, READ_3);
    }

    static String z(double z, int f) {
        return "G1 Z" + GcodeStreamBuilder.f4(z) + " F" + f;
    }

    /** Safety asserts on every line: Z in [floor, travel], X/Y only at travel Z, only N1's outputs. */
    static void assertSafe(List<String> lines, double floor, double travel) {
        double zNow = travel;
        Pattern zw = Pattern.compile("\\bZ(-?[0-9.]+)");
        for (String l : lines) {
            if (l.startsWith("G1")) {
                Matcher m = zw.matcher(l);
                if (m.find()) {
                    zNow = Double.parseDouble(m.group(1));
                    assertTrue(zNow >= floor - 1e-9 && zNow <= travel + 1e-9, l + " outside " + floor + ".." + travel);
                }
                if (l.contains("X") || l.contains("Y")) {
                    assertEquals(travel, zNow, 1e-9, "X/Y only at travel Z: " + l);
                    assertFalse(l.contains("Z"), l);
                }
            }
            if (ControllerCommands.isOutputCommand(l)) {
                assertTrue(Arrays.asList("M106", "M106 P1 S180", "M107 P1", "M107 P0", "M107 P2", "M107 P3")
                        .contains(l), l);
                assertFalse(l.equals("M106 P2 S255") || l.equals("M106 P3 S255"), l);
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    /**
     * rc1 review (machine safety), I1: an Error while N1 is down (here the progress listener's StackOverflowError; on the
     * machine an OutOfMemoryError) still ends the point with the stop sequence, which vents N1. Only an Exception sent it
     * before: N1's vacuum stayed on, N1 down on the surface.
     */
    @Test
    public void anErrorWhileN1IsDownStillVentsIt() throws Exception {
        Rig rig = new Rig();
        VacuumProber.Window w = window(rig, 4.50, 4.50);
        VacuumProber pr = new VacuumProber(rig.s, rig.n1, rig.io(), rig.look, rig.r, rig.ex::isAbortRequested, p -> {
            if (p.lastZ != null) {
                throw new StackOverflowError("test: an Error on the machine thread");
            }
        }, rig.mem);
        assertThrows(StackOverflowError.class, () -> pr.probe(Arrays.asList(example()), w));
        List<String> sent = rig.ch.sent;
        int down = sent.indexOf("M106 P1 S180");
        int stop = java.util.Collections.indexOfSubList(sent, ControllerCommands.STOP_SEQUENCE);
        assertTrue(down >= 0 && stop > down, "the stop sequence after N1's vacuum went on: " + sent);
        assertTrue(pr.needsRecovery());
    }

    @Test
    public void theLineSequenceIsTheBenchBlock() throws Exception {
        Rig rig = new Rig();
        VacuumProber.Window w = window(rig, 4.50, 4.50);
        assertEquals(7.0, w.startZ, 1e-9);
        assertEquals(8.0, w.approachZ, 1e-9);
        assertEquals(3.0, w.floorZ, 1e-9);
        VacuumProber pr = rig.prober();
        List<VacuumProber.Point> pts = pr.probe(Arrays.asList(example()), w);
        VacuumProber.Point p = pts.get(0);
        assertEquals("contact", p.state);
        assertEquals(4.50, p.z, 1e-9, "the first fine step at or below the 4.52 surface, confirmed by the next");
        assertEquals(4.50, p.coarseZ, 1e-9);
        assertEquals(4.75, p.lastOpenZ, 1e-9);
        assertTrue(p.fine);
        assertFalse(p.retried);
        int releaseMeans = (int) p.readings.stream().filter(v -> Math.abs(v[0] - 4.75) < 1e-9).count() - 1;
        assertTrue(releaseMeans >= 1);
        List<String> want = new ArrayList<>();
        want.add("M204 S2000");
        want.addAll(ControllerCommands.VAC1_INIT);
        want.addAll(Arrays.asList("G1 X327.9840 Y186.5330 F6000", "G1 Z8.0000 F600", "M400", "M106", "M106 P1 S180",
                "G4 P1500"));
        for (int i = 0; i < 5; i++) {
            want.addAll(read());
        }
        for (double c = 7.0; c >= 4.5 - 1e-9; c -= 0.25) {
            want.add(z(c, 300));
            want.add("M400");
            for (int i = 0; i < 3; i++) {
                want.addAll(read());
            }
        }
        want.add(z(4.75, 300));
        want.add("M400");
        for (int i = 0; i < 3 * releaseMeans; i++) {
            want.addAll(read());
        }
        for (double f = 4.725; f >= 4.475 - 1e-9; f -= 0.025) {
            want.add(z(f, 300));
            want.add("M400");
            for (int i = 0; i < 3; i++) {
                want.addAll(read());
            }
        }
        want.addAll(END);
        assertEquals(want, rig.ch.sent);
        assertEquals(want, rig.r.streamedLines, "every line went through the gate and was recorded");
        assertSafe(rig.ch.sent, w.floorZ, w.travelZ);
        assertTrue(Arrays.equals(new double[] {327.984, 186.533, 31.5}, round(pr.finalPlanned())),
                Arrays.toString(pr.finalPlanned()));
        assertFalse(pr.needsRecovery());
        assertTrue(rig.progress.contains("now") && rig.progress.contains("contact"));
        assertTrue(p.notes.stream().anyMatch(n -> n.startsWith("no camera look")));
        // a second task reuses the VAC1 init only once per prober
        rig.mark();
        pr.probe(Arrays.asList(target(2, 310, 130, 14.0, 15.0)), w);
        assertFalse(rig.lines().contains("M260 A109"), "VAC1 init once per task");
    }

    static double[] round(double[] v) {
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            r[i] = Math.round(v[i] * 1e6) / 1e6;
        }
        return r;
    }

    /** runs/serial.log, 10-27 17:45:36 (PDT): baseline, coarse 7.00 .. 5.50, then the bench's fine step 5.80. */
    static final int[] BENCH_BASELINE = {-1510474, -1510063, -1510830, -1511146, -1509613};
    static final int[][] BENCH_COARSE = {{-1512699, -1510124, -1510675}, {-1511575, -1510854, -1511414},
        {-1511146, -1510565, -1510723}, {-1512298, -1510614, -1510477}, {-1511274, -1513807, -1512548},
        {-1642357, -1686132, -1740297}, {-1801253, -1807065, -1817441}};
    static final int[] BENCH_FINE_580 = {-1819075, -1812462, -1812395};

    @Test
    public void benchReplayContactBelowTheLastOpenStepNotThe580OfTheBench() throws Exception {
        Rig rig = new Rig();
        for (int v : BENCH_BASELINE) {
            rig.ch.script.add(v);
        }
        for (int[] step : BENCH_COARSE) {
            for (int v : step) {
                rig.ch.script.add(v);
            }
        }
        for (int v : BENCH_FINE_580) {
            rig.ch.script.add(v); // read right after the lift: still sealed (the bench took it for contact)
        }
        // the model underneath, consistent with the trace: open air -1.510 M, seal -0.304 M, slow release
        rig.ch.openAir = -1510425;
        rig.ch.sealDrop = 304219;
        rig.ch.releaseTauMs = 400;
        rig.ch.contact = (a, b) -> 5.73; // 5.75 within the 0.03 mm partial band: it reads a partial seal
        VacuumProber.Window w = window(rig, 4.50, null); // start 7.000, approach 8.000 as on the bench
        VacuumProber.Target t = target(1, 289.0, 268.0, null, null);
        List<VacuumProber.Point> pts = rig.prober().probe(Arrays.asList(t), w);
        VacuumProber.Point p = pts.get(0);
        assertTrue(rig.ch.sent.contains("G1 X313.1780 Y331.9100 F6000"), "the bench's raw X/Y");
        assertEquals(-1510425, p.baseline, 1);
        assertEquals(226564, p.drop, 1);
        assertEquals(5.50, p.coarseZ, 1e-9, "5.50 sealed");
        assertEquals(5.75, p.lastOpenZ, 1e-9, "5.75 read open (mean -1.690 M against a threshold of -1.737 M)");
        assertEquals("contact", p.state);
        assertTrue(p.z < 5.75 && p.z >= 5.50, "contact below 5.75: " + p.z);
        assertEquals(5.725, p.z, 1e-9, "the first step at or below the 5.73 surface, confirmed by the next");
        assertTrue(p.z <= 5.80 - 0.075 + 1e-9, "not the bench's 5.80");
        // the reads at 5.75 after the seal: the bench's still-sealed 5.80 values first, then a partial seal that
        // never lets go there (as 5.75 read during the descent: -1.64 .. -1.74 M); it lets go at 6.00
        List<double[]> at575 = new ArrayList<>();
        for (double[] r : p.readings) {
            if (Math.abs(r[0] - 5.75) < 1e-9) {
                at575.add(r);
            }
        }
        assertTrue(at575.size() >= 3, "coarse, still sealed, still drawing");
        assertTrue(at575.get(1)[1] < p.threshold, "right after the lift the seal still holds");
        assertTrue(Math.abs(at575.get(at575.size() - 1)[1] - p.baseline) > p.drop / 2, "a partial seal at 5.75");
        assertEquals(6.00, p.releaseZ, 1e-9);
        assertTrue(p.notes.stream().anyMatch(n -> n.contains("still drawing at Z 5.750")), p.notes.toString());
        int fine = rig.ch.sent.lastIndexOf(z(6.0, 300));
        assertEquals(z(5.975, 300), rig.ch.sent.get(fine + 2 + 9), "the fine pass starts below the step that let go");
        assertSafe(rig.ch.sent, w.floorZ, w.travelZ);
        assertEquals(END, rig.ch.sent.subList(rig.ch.sent.size() - 4, rig.ch.sent.size()));
    }

    @Test
    public void aFineMissReprobesOnceAndNeverTakesTheCoarseContact() throws Exception {
        // the surface sinks away whenever N1 moves back up to its last open step (a release move), and is
        // there again from the approach: every fine pass finds nothing
        Rig rig = new Rig();
        double[] surface = {4.52};
        rig.ch.contact = (a, b) -> surface[0];
        rig.ch.onLine = l -> {
            if (l.startsWith("G1 Z") && l.endsWith("F300") && Double.parseDouble(l.substring(4, l.indexOf(' ', 4)))
                    > rig.ch.z + 1e-9) {
                surface[0] = 0;
            }
            if (l.startsWith("G1 Z") && l.endsWith("F600")) {
                surface[0] = 4.52;
            }
        };
        VacuumProber.Target t = example();
        VacuumProber.Window w = window(rig, 4.50, 4.50);
        List<VacuumProber.Point> pts = rig.prober().probe(Arrays.asList(t), w);
        VacuumProber.Point p = pts.get(0);
        assertEquals("no-contact", p.state, "two fine misses: no contact, never the coarse 4.50");
        assertNull(p.z);
        assertTrue(p.retried);
        assertEquals(2, rig.ch.sent.stream().filter(l -> l.equals("G1 Z7.0000 F300")).count(), "probed again");
        assertEquals(2, rig.ch.sent.stream().filter(l -> l.equals("G4 P1500")).count(), "with a new baseline");
        assertNotNull(VacuumProber.refusal(pts));
        assertTrue(VacuumProber.refusal(pts).startsWith("PROBE: no contact at board X 8.0, Y 8.0 (N1 went down"),
                VacuumProber.refusal(pts));
        assertEquals(END, rig.ch.sent.subList(rig.ch.sent.size() - 4, rig.ch.sent.size()));
        // one fine miss, then a good re-probe: the re-probe's contact
        Rig rig2 = new Rig();
        double[] s2 = {4.52};
        int[] misses = {0};
        rig2.ch.contact = (a, b) -> s2[0];
        rig2.ch.onLine = l -> {
            if (l.startsWith("G1 Z") && l.endsWith("F300") && Double.parseDouble(l.substring(4, l.indexOf(' ', 4)))
                    > rig2.ch.z + 1e-9 && misses[0]++ == 0) {
                s2[0] = 0;
            }
            if (l.startsWith("G1 Z") && l.endsWith("F600")) {
                s2[0] = 4.52;
            }
        };
        VacuumProber.Point q = rig2.prober().probe(Arrays.asList(example()), w).get(0);
        assertEquals("contact", q.state);
        assertTrue(q.retried);
        assertEquals(4.50, q.z, 1e-9);
        assertSafe(rig2.ch.sent, w.floorZ, w.travelZ);
    }

    @Test
    public void contactNeedsTwoSealedStepsInARow() throws Exception {
        // a partial seal at one fine step (a transient bump), then open, then the real contact
        Rig rig = new Rig();
        rig.ch.contact = (a, b) -> 4.52;
        rig.ch.onLine = l -> {
            if (l.equals("G1 Z4.6500 F300")) {
                rig.ch.sealDrop = 300_000;
                rig.ch.contact = (a, b) -> 4.66; // N1 brushes something at 4.65 only
            }
            if (l.equals("G1 Z4.6250 F300")) {
                rig.ch.contact = (a, b) -> 4.52;
            }
        };
        VacuumProber.Point p = rig.prober().probe(Arrays.asList(example()), window(rig, 4.50, 4.50)).get(0);
        assertEquals(4.50, p.z, 1e-9, "the single sealed step at 4.65 is not a contact");
    }

    @Test
    public void aPartialSealAtTheLastOpenStepLetsGoOneStepHigher() throws Exception {
        Rig rig = new Rig();
        rig.ch.contact = (a, b) -> 4.73; // the coarse 4.75 lies in the partial band: open by the threshold
        VacuumProber.Point p = rig.prober().probe(Arrays.asList(example()), window(rig, 4.50, 4.50)).get(0);
        assertEquals(4.50, p.coarseZ, 1e-9);
        assertEquals(4.75, p.lastOpenZ, 1e-9);
        assertEquals(5.00, p.releaseZ, 1e-9);
        assertEquals(4.725, p.z, 1e-9, "below the last open step, within one fine step of the surface");
        List<String> l = rig.ch.sent;
        List<String> after = l.subList(l.indexOf(z(4.5, 300)) + 1, l.size());
        int back = after.indexOf(z(4.75, 300));
        int up = after.indexOf(z(5.0, 300));
        assertTrue(back >= 0 && up > back, "back to 4.75, then one coarse step higher");
        int fine = after.indexOf(z(4.975, 300));
        assertTrue(fine > up, "the fine pass starts below 5.00");
        for (String x : after.subList(up + 1, fine)) {
            assertFalse(x.startsWith("G1"), "only the release reads at 5.00 before it: " + x);
        }
        assertSafe(l, 3.0, 31.5);
    }

    /**
     * The real machine, 2026-10-29 03:04 (board 3 at board X 8, Y 8; the PROBE record's 3-read means): N1 seals
     * over Z and time. The coarse 5.25 read 1.872 M (threshold 1.886 M: open), 5.00 sealed; 5.25 never let go
     * (1.89 M), 5.50 did; the fine pass from 5.50 crossed the threshold at 5.325. The contact is 5.325, above the
     * last open coarse step: that step's partial seal was the surface.
     */
    @Test
    public void aRealProgressiveSealAboveTheLastOpenStepIsTheContact() throws Exception {
        Rig rig = new Rig();
        double[][] fine = {{5.475, -1660997}, {5.45, -1653637}, {5.425, -1659869}, {5.4, -1680617},
            {5.375, -1783311}, {5.35, -1883822}, {5.325, -1907489}, {5.3, -1936089}, {5.275, -1962524}};
        rig.ch.byZ = (z, n) -> {
            if (z > 7.0 + 1e-9) {
                return -1640380; // the baseline at the approach
            }
            if (Math.abs(z - 5.25) < 1e-9) {
                return n < 3 ? -1872480 : -1894062; // the coarse step, then the release reads
            }
            if (Math.abs(z - 5.5) < 1e-9) {
                return n < 3 ? -1675510 : -1747519; // open on the way down, let go after the seal
            }
            for (double[] f : fine) {
                if (Math.abs(z - f[0]) < 1e-9) {
                    return (int) f[1];
                }
            }
            return z <= 5.0 + 1e-9 ? -2049344 : z < 5.25 ? -1990000 : -1675000;
        };
        VacuumProber.Window w = window(rig, 4.50, 5.50);
        VacuumProber.Point p = rig.prober().probe(Arrays.asList(example()), w).get(0);
        assertEquals(5.00, p.coarseZ, 1e-9);
        assertEquals(5.25, p.lastOpenZ, 1e-9, "1.872 M against a threshold of 1.886 M");
        assertEquals(5.50, p.releaseZ, 1e-9, "5.25 never let go");
        assertTrue(p.notes.stream().anyMatch(n -> n.contains("still drawing at Z 5.250")), p.notes.toString());
        assertEquals("contact", p.state);
        assertFalse(p.retried, "no fine miss");
        assertEquals(5.325, p.z, 1e-9, "the first of the two sealed fine steps");
        assertSafe(rig.ch.sent, w.floorZ, w.travelZ);
        assertEquals(END, rig.ch.sent.subList(rig.ch.sent.size() - 4, rig.ch.sent.size()));
    }

    @Test
    public void didNotReleaseIsRefusedWithTheEndLines() throws Exception {
        Rig rig = new Rig();
        rig.ch.releaseTauMs = 100_000;
        DispenseService.RunRefusedException e = assertThrows(DispenseService.RunRefusedException.class,
                () -> rig.prober().probe(Arrays.asList(example()), window(rig, 4.50, 4.50)));
        assertEquals("PROBE: N1 did not let go of the surface within 1.5 s after the touch at X 303.81, Y 122.62: "
                + "check the N1 tip and tubing, then try again", e.getMessage());
        assertEquals(END, rig.ch.sent.subList(rig.ch.sent.size() - 4, rig.ch.sent.size()));
    }

    @Test
    public void theFloorIsTheHigherReferenceMinusOneAndAHalf() throws Exception {
        Rig rig = new Rig();
        rig.ch.contact = (a, b) -> Double.NaN; // a hole everywhere
        VacuumProber.Window w = window(rig, 4.50, 5.50);
        assertEquals(5.50, w.refZ, 1e-9);
        assertEquals(4.00, w.floorZ, 1e-9, "never deeper than either reference");
        VacuumProber.Target t = example();
        withAlternates(t);
        DispenseService.RunRefusedException e = assertThrows(DispenseService.RunRefusedException.class,
                () -> rig.prober().probe(Arrays.asList(t), w));
        assertEquals("PROBE: no contact at board X 8.0, Y 8.0 or 2 spots nearby (N1 went down to Z 4.000, 0.50 mm "
                + "below the board top): probably a hole or via there, or the board top Z is wrong. Probe by hand, or "
                + "check the board Z and try again.", e.getMessage());
        assertSafe(rig.ch.sent, 4.0, 31.5);
        assertTrue(rig.ch.sent.contains(z(4.0, 300)));
        assertEquals(3, rig.ch.sent.stream().filter(l -> l.equals("G4 P1500")).count(), "a baseline per spot");
        assertEquals(3, rig.ch.sent.stream().filter(l -> l.equals("M106 P1 S180")).count());
        assertEquals(END, rig.ch.sent.subList(rig.ch.sent.size() - 4, rig.ch.sent.size()));
        // the alternates were where the target said
        assertTrue(rig.ch.sent.contains("G1 X329.9840 F6000"));
        assertTrue(rig.ch.sent.contains("G1 X327.9840 Y188.5330 F6000"));
    }

    @Test
    public void floorPushesAreCappedAtThreePerTask() throws Exception {
        Rig rig = new Rig();
        rig.s.alternates = 0;
        rig.ch.contact = (a, b) -> a < 310 ? 4.52 : Double.NaN; // holes right of X 310
        List<VacuumProber.Target> ts = Arrays.asList(target(1, 303.8, 122.6, 8.0, 8.0),
                target(2, 320, 122.6, 24.0, 8.0));
        List<VacuumProber.Point> pts = rig.prober().probe(ts, window(rig, 4.50, 4.50));
        assertEquals("contact", pts.get(0).state);
        assertEquals("no-contact", pts.get(1).state, "one push: the point is returned, the task refuses with its text");
        // the first contact tightens the next floor: 4.50 - 1.0
        double lowest = pts.get(1).lowestZ;
        assertEquals(3.5, lowest, 1e-9);
        assertTrue(pts.get(1).failure.contains("N1 went down to Z 3.500, 1.00 mm below the board top"),
                pts.get(1).failure);
        // a board top Z typed low: the first contact's band keeps the next floor above it, and the text says so
        Rig low = new Rig();
        low.s.alternates = 0;
        low.ch.contact = (a, b) -> a < 310 ? 4.52 : Double.NaN;
        List<VacuumProber.Point> lp = low.prober().probe(ts, window(low, 3.20, 4.50));
        assertEquals(3.5, lp.get(1).lowestZ, 1e-9);
        assertTrue(lp.get(1).failure.contains("N1 went down to Z 3.500, 0.30 mm above the board top"),
                lp.get(1).failure);
        // three targets over holes, no alternates: refused at the third push
        Rig r2 = new Rig();
        r2.s.alternates = 0;
        r2.ch.contact = (a, b) -> Double.NaN;
        VacuumProber pr = r2.prober();
        VacuumProber.Window w = window(r2, 4.50, 4.50);
        pr.probe(Arrays.asList(target(1, 300, 120, 5.0, 5.0)), w);
        pr.probe(Arrays.asList(target(2, 310, 120, 15.0, 5.0)), w);
        assertEquals(2, pr.floorPushes());
        assertThrows(DispenseService.RunRefusedException.class,
                () -> pr.probe(Arrays.asList(target(3, 320, 120, 25.0, 5.0)), w));
        assertEquals(3, pr.floorPushes());
    }

    @Test
    public void laterPointsStartAboveTheFirstContact() throws Exception {
        Rig rig = new Rig();
        rig.ch.contact = (a, b) -> 4.52 + (a - 303.806) * 0.01; // a slope: 0.2 mm higher at the second point
        List<VacuumProber.Target> ts = Arrays.asList(example(), target(2, 323.806, 122.623, 28.0, 8.0));
        rig.mark();
        List<VacuumProber.Point> pts = rig.prober().probe(ts, window(rig, 3.80, 4.50));
        assertEquals(4.50, pts.get(0).z, 1e-9);
        // the second point starts at 4.50 + 2.5 and its approach is 1 mm above that
        List<String> l = rig.ch.sent;
        int second = l.indexOf("G1 X347.9840 F6000");
        assertTrue(second > 0);
        assertEquals(z(8.0, 600), l.get(second + 1));
        int settle = second + l.subList(second, l.size()).indexOf("G4 P1500");
        assertEquals(z(7.0, 300), l.get(settle + 16), "after the settle and 5 reads");
        assertEquals(4.70, pts.get(1).z, 1e-9);
    }

    @Test
    public void everyRefusalInsideAPointEndsWithTheEndLines() throws Exception {
        // baseline toward 0 (no tip)
        Rig a = new Rig();
        a.ch.openAir = -410_000;
        DispenseService.RunRefusedException e = assertThrows(DispenseService.RunRefusedException.class,
                () -> a.prober().probe(Arrays.asList(example()), window(a, 4.50, 4.50)));
        assertEquals("PROBE: N1's vacuum reads -0.41 M counts in open air at X 303.81, Y 122.62 (normal -1.90 M to "
                + "-1.20 M counts): no nozzle tip on N1, a leak, or the N1 pump is off. Check N1, then try again.",
                e.getMessage());
        assertEquals(END, a.ch.sent.subList(a.ch.sent.size() - 4, a.ch.sent.size()));
        // too strong (blocked)
        Rig b = new Rig();
        b.ch.openAir = -2_110_000;
        e = assertThrows(DispenseService.RunRefusedException.class,
                () -> b.prober().probe(Arrays.asList(example()), window(b, 4.50, 4.50)));
        assertEquals("PROBE: N1's vacuum reads -2.11 M counts in open air (normal -1.90 M to -1.20 M counts): the tip "
                + "is blocked or a part is stuck on it. Check N1, then try again.", e.getMessage());
        assertEquals(END, b.ch.sent.subList(b.ch.sent.size() - 4, b.ch.sent.size()));
        // sealed already at the start
        Rig c = new Rig();
        c.ch.contact = (x, y) -> 7.2;
        e = assertThrows(DispenseService.RunRefusedException.class,
                () -> c.prober().probe(Arrays.asList(example()), window(c, 4.50, 4.50)));
        assertEquals("PROBE: N1 sealed already at Z 7.000, 2.50 mm above the board top: something is under N1 or the "
                + "board is higher than its Z. Check, then try again.", e.getMessage());
        assertEquals(END, c.ch.sent.subList(c.ch.sent.size() - 4, c.ch.sent.size()));
        // no reading in the reply
        Rig d = new Rig();
        d.ch.readReply = Arrays.asList("ok");
        e = assertThrows(DispenseService.RunRefusedException.class,
                () -> d.prober().probe(Arrays.asList(example()), window(d, 4.50, 4.50)));
        assertEquals("PROBE: no vacuum reading from the controller (M261 answered: 'ok'): probe by hand",
                e.getMessage());
        assertEquals(END, d.ch.sent.subList(d.ch.sent.size() - 4, d.ch.sent.size()));
        assertFalse(d.ch.pump || d.ch.valve, "vented");
    }

    @Test
    public void errorsAndTheOnTimeCapEndWithTheEndLines() throws Exception {
        Rig a = new Rig();
        a.ch.failOn = z(6.0, 300);
        a.ch.failWith = new Exception("error response from controller: Error:Line Number is not Last Line Number+1");
        Exception e = assertThrows(Exception.class,
                () -> a.prober().probe(Arrays.asList(example()), window(a, 4.50, 4.50)));
        assertTrue(e.getMessage().contains("error response from controller"), e.getMessage());
        assertEquals(END, a.ch.sent.subList(a.ch.sent.size() - 4, a.ch.sent.size()));
        // an Error: line in a read reply
        Rig b = new Rig();
        b.ch.readReply = Arrays.asList("Error:I2C", "ok");
        e = assertThrows(IllegalStateException.class,
                () -> b.prober().probe(Arrays.asList(example()), window(b, 4.50, 4.50)));
        assertTrue(e.getMessage().startsWith("controller error in the vacuum read"), e.getMessage());
        assertEquals(END, b.ch.sent.subList(b.ch.sent.size() - 4, b.ch.sent.size()));
        // the vacuum on-time cap (60 s per point; here 3 s)
        Rig c = new Rig();
        c.s.maxVacuumOnMs = 3000;
        e = assertThrows(IllegalStateException.class,
                () -> c.prober().probe(Arrays.asList(example()), window(c, 4.50, 4.50)));
        assertEquals("N1 vacuum on for 3 s at one point: stopped (N1 lifted, pump and valve off)", e.getMessage());
        assertEquals(END, c.ch.sent.subList(c.ch.sent.size() - 4, c.ch.sent.size()));
        assertFalse(c.ch.pump || c.ch.valve);
        Rig d = new Rig();
        assertEquals("N1 vacuum on for 60 s at one point: stopped (N1 lifted, pump and valve off)",
                assertThrows(IllegalStateException.class, () -> {
                    d.ch.releaseTauMs = 100_000;
                    d.s.releaseTimeoutMs = 120_000;
                    d.prober().probe(Arrays.asList(example()), window(d, 4.50, 4.50));
                }).getMessage());
        assertEquals(END, d.ch.sent.subList(d.ch.sent.size() - 4, d.ch.sent.size()));
    }

    @Test
    public void stopSendsOnlyTheShutdownAndNoZMove() throws Exception {
        Rig rig = new Rig();
        rig.ch.onLine = l -> {
            if (l.equals(z(6.0, 300))) {
                rig.ex.requestAbort("STOP pressed in the web UI");
            }
        };
        VacuumProber pr = rig.prober();
        DispenseExecutor.AbortedException e = assertThrows(DispenseExecutor.AbortedException.class,
                () -> pr.probe(Arrays.asList(example()), window(rig, 4.50, 4.50)));
        assertEquals("STOP pressed in the web UI", e.getMessage());
        List<String> l = rig.ch.sent;
        int at = l.indexOf(z(6.0, 300));
        assertEquals(STOP, l.subList(at + 1, l.size()), "after the STOP only the I1 shutdown: vents, no Z move");
        assertTrue(pr.needsRecovery(), "the caller recovers (un-home)");
    }

    @Test
    public void failedEndLinesFallBackToTheShutdown() throws Exception {
        Rig rig = new Rig();
        rig.ch.failOn = "G1 Z31.5000 F600";
        rig.ch.failWith = new Exception("timeout waiting for response");
        VacuumProber pr = rig.prober();
        Exception e = assertThrows(IllegalStateException.class,
                () -> pr.probe(Arrays.asList(example()), window(rig, 4.50, 4.50)));
        assertTrue(e.getMessage().startsWith("N1 probe: the lift and vent after the point failed"), e.getMessage());
        List<String> l = rig.ch.sent;
        assertEquals("G1 Z31.5000 F600", l.get(l.size() - 7));
        assertEquals(STOP, l.subList(l.size() - 6, l.size()));
        assertTrue(pr.needsRecovery());
        assertFalse(rig.r.warnings.isEmpty());
    }

    @Test
    public void openAirSpreadIsSettledOnceThenRefused() throws Exception {
        Rig a = new Rig();
        a.ch.noise = new int[] {0, 30_000, -20_000, 10_000, 0};
        // unsteady twice
        DispenseService.RunRefusedException e = assertThrows(DispenseService.RunRefusedException.class,
                () -> a.prober().probe(Arrays.asList(example()), window(a, 4.50, 4.50)));
        assertEquals("PROBE: N1's vacuum is not steady in open air (spread 50 k counts, limit 15 k) after a second "
                + "settle: check the N1 tubing and pump", e.getMessage());
        assertEquals(2, a.ch.sent.stream().filter(x -> x.equals("G4 P1500")).count());
        assertEquals(END, a.ch.sent.subList(a.ch.sent.size() - 4, a.ch.sent.size()));
        // unsteady once: settled again, then fine
        Rig b = new Rig();
        b.ch.noise = new int[] {0, 30_000, -20_000, 10_000, 0, 0, 0, 0, 0, 0};
        VacuumProber.Point p = b.prober().probe(Arrays.asList(example()), window(b, 4.50, 4.50)).get(0);
        assertEquals("contact", p.state);
        assertTrue(p.notes.stream().anyMatch(n -> n.startsWith("open air unsteady")));
    }

    @Test
    public void baselineAgainstTheFirstPointAndTheRecentMemory() throws Exception {
        // 56 k stronger at the second point than at the first (limit 50 k): a part picked up on the way?
        Rig a = new Rig();
        a.ch.onLine = l -> {
            if (l.equals("G1 X347.9840 F6000")) {
                a.ch.openAir = -1_606_000;
            }
        };
        DispenseService.RunRefusedException e = assertThrows(DispenseService.RunRefusedException.class,
                () -> a.prober().probe(Arrays.asList(example(), target(2, 323.806, 122.623, 28.0, 8.0)),
                        window(a, 4.50, 4.50)));
        assertEquals("PROBE: N1's vacuum reads -1.61 M counts in open air, 56 k counts stronger than at the first "
                + "point of this probe (-1.55 M counts; limit 50 k): a part may be stuck on N1 or the tip is partly "
                + "blocked. Look at N1, then try again.", e.getMessage());
        assertEquals(END, a.ch.sent.subList(a.ch.sent.size() - 4, a.ch.sent.size()));
        // 65 k against the last good baseline (limit 60 k), within 2 h
        Rig b = new Rig();
        b.mem.remember(-1_485_000, b.ch.now - 3_600_000);
        e = assertThrows(DispenseService.RunRefusedException.class,
                () -> b.prober().probe(Arrays.asList(example()), window(b, 4.50, 4.50)));
        assertEquals("PROBE: N1's vacuum reads -1.55 M counts in open air, 65 k counts stronger than at "
                + ZCheck.clock(b.ch.now - 3_600_000) + " (-1.49 M counts; limit 60 k): a part may be stuck on N1 or "
                + "the tip is partly blocked. Look at N1, then try again.", e.getMessage());
        // the same memory older than 2 h is ignored, and a good baseline is remembered
        Rig c = new Rig();
        c.mem.remember(-1_485_000, c.ch.now - 2 * 3_600_000 - 60_000);
        assertEquals("contact", c.prober().probe(Arrays.asList(example()), window(c, 4.50, 4.50)).get(0).state);
        assertEquals(-1_550_000, c.mem.recent(c.ch.now, 60_000), 100, "the good baseline is remembered");
    }

    @Test
    public void simulatorTimingsAreRefusedOnARealMachine() throws Exception {
        Rig rig = new Rig();
        rig.s.simulated = false;
        rig.s.vacuumSettleMs = 300;
        DispenseService.RunRefusedException e = assertThrows(DispenseService.RunRefusedException.class,
                () -> rig.prober().probe(Arrays.asList(example()), window(rig, 4.50, 4.50)));
        assertEquals("PROBE: vacuumProbe.vacuumSettleMs 300 is a simulator setting: the real VAC1 needs at least "
                + "1000 ms (a reading 0.3 s after the valve opened looked like a seal)", e.getMessage());
        rig.s.vacuumSettleMs = 1500;
        rig.s.readDelayMs = 0;
        e = assertThrows(DispenseService.RunRefusedException.class,
                () -> rig.prober().probe(Arrays.asList(example()), window(rig, 4.50, 4.50)));
        assertTrue(e.getMessage().startsWith("PROBE: vacuumProbe.readDelayMs 0 is a simulator setting"));
        assertTrue(rig.ch.sent.isEmpty(), "nothing moved");
        rig.s.simulated = true;
        assertEquals("contact", rig.prober().probe(Arrays.asList(example()), window(rig, 4.50, 4.50)).get(0).state);
    }

    @Test
    public void openPnpN1StateIsCheckedBeforeAndInsideThePoints() throws Exception {
        try (SimMachine sim = new SimMachine()) {
            Method setPart = AbstractNozzle.class.getDeclaredMethod("setPart", Part.class);
            setPart.setAccessible(true);
            Rig rig = new Rig();
            OpenPnpRawMapper n1 = OpenPnpRawMapper.forProbeNozzle(sim.machine, sim.n1, "b");
            rig.n1 = n1;
            double[] head = n1.currentPlanned();
            rig.ch.x = head[0];
            rig.ch.y = head[1];
            rig.ch.z = head[2];
            VacuumProber.Window w = VacuumProber.Window.of(4.5, null, head[2], rig.s);
            setPart.invoke(sim.n1, new Part("R5"));
            DispenseService.RunRefusedException e = assertThrows(DispenseService.RunRefusedException.class,
                    () -> rig.prober().probe(Arrays.asList(target(1, 200, 100, null, null)), w));
            assertEquals("PROBE: OpenPnP says N1 holds a part (R5): discard it first", e.getMessage());
            assertTrue(rig.ch.sent.isEmpty(), "refused before anything moved");
            setPart.invoke(sim.n1, (Part) null);
            // a part shows up during the task (the look of point 1): point 2 is refused, N1 lifted and vented
            rig.look = (x, y) -> {
                if (x < 205) { // during point 1, after its own check
                    setPart.invoke(sim.n1, new Part("R6"));
                }
                VacuumProber.Look lk = new VacuumProber.Look();
                lk.bare = true;
                return lk;
            };
            e = assertThrows(DispenseService.RunRefusedException.class, () -> rig.prober().probe(
                    Arrays.asList(target(1, 200, 100, null, null), target(2, 210, 100, null, null)), w));
            assertEquals("PROBE: OpenPnP says N1 holds a part (R6): discard it first", e.getMessage());
            assertEquals(END.subList(1, 4), rig.ch.sent.subList(rig.ch.sent.size() - 3, rig.ch.sent.size()));
            setPart.invoke(sim.n1, (Part) null);
        }
    }

    @Test
    public void openPnpMovesTheHeadForTheLookAndTheProbeStartsFromThere() throws Exception {
        try (SimMachine sim = new SimMachine()) {
            Rig rig = new Rig();
            OpenPnpRawMapper n1 = OpenPnpRawMapper.forProbeNozzle(sim.machine, sim.n1, "b");
            rig.n1 = n1;
            double[] head = n1.currentPlanned();
            rig.ch.x = head[0];
            rig.ch.y = head[1];
            rig.ch.z = head[2];
            VacuumProber.Window w = VacuumProber.Window.of(4.5, null, head[2], rig.s);
            List<double[]> seenAt = new ArrayList<>();
            rig.look = (x, y) -> {
                if (y > 105) {
                    seenAt.add(n1.currentPlanned()); // what OpenPnP was told before it moves the head
                    // the camera tour: OpenPnP moves the head elsewhere (here N1 itself stands in for the camera)
                    sim.n1.moveTo(new Location(LengthUnit.Millimeters, 180, 110, head[2], 0));
                    sim.machine.getMotionPlanner().waitForCompletion(null, CompletionType.WaitForStillstand);
                    double[] now = n1.currentPlanned();
                    rig.ch.x = now[0];
                    rig.ch.y = now[1];
                    seenAt.add(now);
                }
                return look(true, 0);
            };
            rig.ch.onLine = l -> {
                if (l.equals("M106")) {
                    seenAt.add(new double[] {rig.ch.x, rig.ch.y});
                }
            };
            List<VacuumProber.Point> pts = rig.prober().probe(Arrays.asList(target(1, 200, 100, null, null),
                    target(2, 200, 110, null, null)), w);
            assertEquals("contact", pts.get(1).state);
            // the vacuum went on with the head where each target needs it, not where the probe last sent it
            assertEquals(200 - N1X, seenAt.get(0)[0], 1e-6);
            // OpenPnP was told where the probe had left N1 (lifted over point 1) before it moved the head
            assertEquals(200 - N1X, seenAt.get(1)[0], 1e-6);
            assertEquals(100 - N1Y, seenAt.get(1)[1], 1e-6);
            assertEquals(head[2], seenAt.get(1)[2], 1e-6);
            assertEquals(180 - N1X, seenAt.get(2)[0], 1e-6, "OpenPnP moved the head for the look");
            assertEquals(200 - N1X, seenAt.get(3)[0], 1e-6);
            assertEquals(110 - N1Y, seenAt.get(3)[1], 1e-6);
        }
    }

    static VacuumProber.Look look(boolean bare, double share) {
        VacuumProber.Look l = new VacuumProber.Look();
        l.bare = bare;
        l.foreignShare = share;
        return l;
    }

    @Test
    public void theCameraLookSkipsSpotsThatAreNotBare() throws Exception {
        Rig rig = new Rig();
        List<double[]> looked = new ArrayList<>();
        rig.look = (x, y) -> {
            looked.add(new double[] {x, y});
            return look(looked.size() > 1, 0.14);
        };
        VacuumProber.Target t = example();
        withAlternates(t);
        VacuumProber.Point p = rig.prober().probe(Arrays.asList(t), window(rig, 4.50, 4.50)).get(0);
        assertEquals("contact", p.state);
        assertEquals(1, p.alternate, "the first alternate");
        assertEquals(10.0, p.bx, 1e-9);
        assertEquals(2, looked.size());
        assertFalse(rig.ch.sent.contains("G1 X327.9840 Y186.5330 F6000"), "N1 never went to the dirty spot");
        assertTrue(p.notes.get(0).contains("paste, a part or print seen (14 % of the spot)"), p.notes.toString());
        // all three not bare: the refusal, and N1 never touched anything
        Rig all = new Rig();
        all.look = (x, y) -> look(false, x > 304 ? 0.14 : 0.06);
        VacuumProber.Target u = example();
        withAlternates(u);
        VacuumProber.Window w = window(all, 4.50, 4.50);
        List<VacuumProber.Point> pts = all.prober().probe(Arrays.asList(u, target(2, 320, 130, 20.0, 15.0)), w);
        assertEquals("not-bare", pts.get(0).state);
        assertEquals("skipped", pts.get(1).state);
        assertEquals("PROBE: something on the surface at board X 8.0, Y 8.0 and 2 spots nearby (paste, a part or print: "
                + "up to 14 % of the spot): wipe it, or probe by hand", VacuumProber.refusal(pts));
        assertFalse(all.ch.sent.contains("M106"), "no vacuum, no touch");
        assertEquals(END, all.ch.sent.subList(all.ch.sent.size() - 4, all.ch.sent.size()), "lifted and vented anyway");
    }

    @Test
    public void aLeakySpotGivesNoContactAndTheAlternateIsUsed() throws Exception {
        Rig rig = new Rig();
        rig.ch.leaky.add(new double[] {303.806, 122.623, 0.6});
        VacuumProber.Target t = example();
        withAlternates(t);
        VacuumProber.Point p = rig.prober().probe(Arrays.asList(t), window(rig, 4.50, 4.50)).get(0);
        assertEquals("contact", p.state);
        assertEquals(1, p.alternate);
        assertEquals(2, rig.ch.sent.stream().filter(l -> l.equals("G4 P1500")).count(), "a new baseline");
        assertSafe(rig.ch.sent, 3.0, 31.5);
    }

    @Test
    public void windowRefusalsAndTheTravelZ() throws Exception {
        VacuumProber.Settings s = new VacuumProber.Settings();
        DispenseService.RunRefusedException e = assertThrows(DispenseService.RunRefusedException.class,
                () -> VacuumProber.Window.of(0.0, null, 31.5, s));
        assertEquals("PROBE: the board top Z 0.000 puts the probe floor at -1.500, below the lowest N1 Z the probe may "
                + "use (2.000): set the board top Z to roughly the N1 contact height", e.getMessage());
        // with the needle frame at 5.5 a board top Z typed as 0 leaves nothing to descend through: refused here,
        // not by the probe's own Z assert after travelling over the spot
        e = assertThrows(DispenseService.RunRefusedException.class, () -> VacuumProber.Window.of(0.0, 5.5, 31.5, s));
        assertEquals("PROBE: the board top Z 0.000 lies 5.500 mm under the needle-frame surface 5.500, and the probe goes "
                + "at most 1.5 mm under the higher of the two (down to Z 4.000): it cannot reach that height. Set the "
                + "board top Z to roughly the N1 contact height, or record the needle frame again; if both are right, "
                + "probe by hand", e.getMessage());
        // review 1: planning accepts a board top up to 2.0 mm under the needle frame, and the floor sits 1.5 mm under
        // the higher of the two: a floor at or above the board top cannot reach it (the probe descended to that floor
        // three times, then blamed a hole or the board Z)
        e = assertThrows(DispenseService.RunRefusedException.class, () -> VacuumProber.Window.of(3.9, 5.5, 31.5, s));
        assertTrue(e.getMessage().startsWith("PROBE: the board top Z 3.900 lies 1.600 mm under the needle-frame surface "
                + "5.500, and the probe goes at most 1.5 mm under the higher of the two (down to Z 4.000)"), e.getMessage());
        assertThrows(DispenseService.RunRefusedException.class, () -> VacuumProber.Window.of(4.0, 5.5, 31.5, s),
                "a floor at the board top");
        assertEquals(4.0, VacuumProber.Window.of(4.25, 5.5, 31.5, s).floorZ, 1e-9, "a floor under it passes");
        assertEquals(4.0, VacuumProber.Window.of(4.5, 5.5, 31.5, s).floorZ, 1e-9, "the bench: 0.5 mm under the board");
        assertThrows(DispenseService.RunRefusedException.class, () -> VacuumProber.Window.around(3.0, 31.5, s));
        VacuumProber.Window c = VacuumProber.Window.around(4.5, 31.5, s);
        assertEquals(7.0, c.startZ, 1e-9);
        assertEquals(3.0, c.floorZ, 1e-9);
        // travel Z is the dispenser's raw travel Z, sent as N1 Z: 31.5 on the see-saw (z2 = 63 - z1)
        DispenseConfig cfg = new DispenseConfig();
        double travel = new ToyMapper(300, 200, 31.5, 0).toRaw(300, 200, cfg.travelSafeZMm).z;
        assertEquals(31.5, travel, 1e-9);
        VacuumProber.Window w = VacuumProber.Window.of(4.5, 5.5, travel, s);
        assertEquals(31.5, w.travelZ, 1e-9);
        // a travel Z outside N1's safe zone is refused before anything moves
        Rig rig = new Rig();
        VacuumProber.Window low = VacuumProber.Window.of(4.5, 5.5, 25.0, rig.s);
        e = assertThrows(DispenseService.RunRefusedException.class,
                () -> rig.prober().probe(Arrays.asList(example()), low));
        assertTrue(e.getMessage().startsWith("PROBE: the travel Z 25.000 lies outside N1's safe zone"), e.getMessage());
        // N1 Z must be raw Z
        ((N1Mapper) rig.n1).transformedZ = true;
        e = assertThrows(DispenseService.RunRefusedException.class,
                () -> rig.prober().probe(Arrays.asList(example()), w));
        assertEquals("PROBE: N1's Z is transformed in OpenPnP; the probe needs N1 Z = raw Z", e.getMessage());
        assertTrue(rig.ch.sent.isEmpty());
    }

    @Test
    public void preflightNeedleFrameAndFrameMismatch() throws Exception {
        DispenseConfig cfg = new DispenseConfig();
        VacuumProber.Settings s = VacuumProber.Settings.of(cfg, false);
        DispenseService.RunRefusedException e = assertThrows(DispenseService.RunRefusedException.class,
                () -> VacuumProber.preflight(cfg, true, "the board top Z", 4.5, 31.5, s, null));
        assertEquals("I4: the needle Z frame is not set: record it first (Setup › Needle Z frame); the probe "
                + "checks its heights against it", e.getMessage());
        long at = 1_793_200_000_000L;
        cfg.needleAnchor = new DispenseConfig.NeedleAnchor(5.5, 2.91, 322.0, 225.5, at);
        // a board 1.0 mm under the needle-frame surface is not refused: its reference is its own top Z
        VacuumProber.Window w = VacuumProber.preflight(cfg, true, "the board top Z", 4.5, 31.5, s, null);
        assertEquals(5.5, w.refZ, 1e-9);
        assertEquals(4.0, w.floorZ, 1e-9);
        // 1.5 mm under it the floor lies at the board top: refused before anything moves (review 1)
        e = assertThrows(DispenseService.RunRefusedException.class,
                () -> VacuumProber.preflight(cfg, true, "the board top Z", 4.0, 31.5, s, null));
        assertTrue(e.getMessage().startsWith("PROBE: the board top Z 4.000 lies 1.500 mm under the needle-frame "
                + "surface 5.500"), e.getMessage());
        // a weigh coupon's window is its own, +-0.3 mm around its place Z, whatever the needle frame
        VacuumProber.Window coupon = VacuumProber.preflight(cfg, true, "the 'coupon1' place Z", 3.9, 0.3, 31.5, s, null);
        assertEquals(3.9, coupon.refZ, 1e-9);
        assertEquals(4.2, coupon.startZ, 1e-9);
        assertEquals(3.6, coupon.floorZ, 1e-9);
        assertEquals(31.5, coupon.travelZ, 1e-9);
        // ... but a board top Z typed as 0 is refused before anything moves (no anchor check catches it now)
        e = assertThrows(DispenseService.RunRefusedException.class,
                () -> VacuumProber.preflight(cfg, true, "the board top Z", 0.0, 31.5, s, null));
        assertTrue(e.getMessage().startsWith("PROBE: the board top Z 0.000 lies 5.500 mm under the needle-frame "
                + "surface 5.500"), e.getMessage());
        // the blank is checked against the needle frame
        e = assertThrows(DispenseService.RunRefusedException.class,
                () -> VacuumProber.preflight(cfg, false, "the 'blank' place Z", 4.0, 31.5, s, null));
        assertEquals("PROBE: the 'blank' place Z 4.000 is 1.500 mm from the needle-frame surface 5.500 (recorded at "
                + "X 322.0, Y 225.5 on " + ZCheck.dayClock(at) + "; tolerance 1.00): set the 'blank' place Z to "
                + "roughly the N1 contact height, or record the needle frame again", e.getMessage());
        assertNotNull(VacuumProber.preflight(cfg, false, "the 'blank' place Z", 5.62, 31.5, s, null));
        s.vacuumSettleMs = 300;
        assertThrows(DispenseService.RunRefusedException.class,
                () -> VacuumProber.preflight(cfg, false, "the 'blank' place Z", 5.62, 31.5, s, null));
    }

    @Test
    public void checkTouchesUseAWindowAroundTheStoredZ() throws Exception {
        Rig rig = new Rig();
        VacuumProber.Target t = example();
        t.storedZ = 4.525;
        rig.ch.contact = (a, b) -> 4.53;
        VacuumProber.Window w = window(rig, 9.0, null); // the task window does not apply to a check touch
        VacuumProber.Point p = rig.prober().probe(Arrays.asList(t), w).get(0);
        assertEquals(4.525, p.z, 1e-9);
        assertTrue(rig.ch.sent.contains(z(7.025, 300)), "start = stored + 2.5");
        assertTrue(rig.ch.sent.contains(z(8.025, 600)), "approach = start + 1");
        assertSafe(rig.ch.sent, 4.525 - 1.5, 31.5);
        ZCheck.Reference ref = new ZCheck.Reference();
        ref.z = 4.525;
        assertTrue(ZCheck.verdict(ref, p.z));
    }

    @Test
    public void parseTheBenchReply() throws Exception {
        assertEquals(-1508992, VacuumProber.parse(Arrays.asList("echo:i2c-reply: from:109 bytes:3 data:E8 F9 80",
                "ok")));
        assertEquals(-1510666, VacuumProber.parse(Arrays.asList("echo:i2c-reply: from:109 bytes:3 data:E8 F2 F6")));
        assertEquals(1, VacuumProber.parse(Arrays.asList("data:00 00 01")));
        assertThrows(DispenseService.RunRefusedException.class, () -> VacuumProber.parse(Arrays.asList("data:ZZ 00")));
    }
}
