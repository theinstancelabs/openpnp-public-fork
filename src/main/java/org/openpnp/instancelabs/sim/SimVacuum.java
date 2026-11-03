package org.openpnp.instancelabs.sim;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openpnp.model.AxesLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.ControllerAxis;
import org.openpnp.spi.Driver;

/**
 * The N1 vacuum sensor (VAC1) of a simulated controller. Fed every line the controller receives,
 * with the raw head position (for a moving head: where and when it arrives); answers
 * {@code M261 A109 B3 S1} with the reading the bench's probe.py parses. Lives only inside OpenPnP's
 * simulated controller (org.openpnp.util.GcodeServer) and the test RecordingGcodeServer, and only
 * when a sim scene exists.
 *
 * <p>State from the line text: pump {@code M106} / {@code M106 P0 S>0} on, {@code M107} / {@code M107 P0}
 * off; valve {@code M106 P1 S>0} on, {@code M107 P1} off; Marlin's M260 buffer (A address, B byte, S1
 * send) gives the multiplexer channel (address 112) and the sensor register (address 109).</p>
 *
 * <p>The reading moves toward its target with a time constant on the wall clock: {@code tauS} after a
 * pump or valve change, {@code sealTauS} when a seal forms, {@code releaseTauS} when it breaks. So a
 * baseline read too early is wrong, and a step right after a seal still reads sealed, as on the bench.
 * Sealed: N1's raw Z at or below the contact Z under the tip (board, weigh coupon, blank or bed: SimScene's
 * regions), with the whole tip disc on that surface;
 * within {@code partialBandMm} above it, half the drop; at a leaky spot or an edge, only {@code leakDrop}.</p>
 */
public final class SimVacuum {
    /** I2C address of the multiplexer and of the VAC1 sensor; N1's channel byte; the sensor's data register. */
    static final int MUX = 112, SENSOR = 109, N1_CHANNEL = 1, DATA_REGISTER = 6;
    /** Open-air readings for a missing tip (assumption, REAL_MACHINE_TODO) and a blocked one. */
    static final double MISSING_TIP_OPEN_AIR = -400_000, BLOCKED_TIP_OPEN_AIR = -2_100_000;
    /** A held 0603 reads >= 80 k below open air (tools/place.py); it seals 0.45 mm high. */
    static final double PART_EXTRA_COUNTS = -100_000, PART_HEIGHT_MM = 0.45;
    /** A controller's M400 may return this much before the planned arrival (GcodeServer waits whole milliseconds). */
    static final long ARRIVAL_SLACK_NS = 2_000_000;
    private static final Pattern WORD = Pattern.compile("([A-Z])\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+))");

    private enum Seal {
        OPEN, PARTIAL, SEALED
    }

    private final Supplier<SimScene> scenes;
    private final LongSupplier clockNs;
    private Random noise;
    private boolean pump, valve;
    private int channel = -1, register = -1;
    private int i2cAddress = -1;
    private final List<Integer> i2cBytes = new ArrayList<>();
    private double value, target, tau;
    private long lastNs;
    private boolean started;
    private String lastTip;
    /** Where the head is; where a planned move takes it (null: not moving) and when. */
    private double[] head, arriving;
    private long arrivesAtNs;

    /**
     * A fixed scene and the wall clock (tests). A board size the scene does not give comes from the
     * configuration's matching board, once it is loaded.
     */
    public SimVacuum(SimScene scene) {
        this(() -> scene.hasBoardSize() ? scene : scene.withBoardSize(scene.findBoard(Configuration.get())),
                System::nanoTime);
    }

    public SimVacuum(Supplier<SimScene> scenes, LongSupplier clockNs) {
        this.scenes = scenes;
        this.clockNs = clockNs;
    }

    /**
     * The sensor of a simulated controller in this configuration: null unless the sim scene file
     * exists. The scene is read again when the file changes.
     */
    public static SimVacuum forConfiguration(Configuration c) {
        if (c == null || !SimGuard.sceneFile(c).isFile()) {
            return null;
        }
        return new SimVacuum(SimScene.Source.forConfiguration(c), System::nanoTime);
    }

    /**
     * For GcodeServer: X/Y/Z from the driver's controller axes of the planned end location, reached in
     * {@code arriveInS} seconds (the end of its motion plan). The seal then forms or breaks when the
     * head arrives, not when the next line comes in (the prober reads 70 ms after M400).
     */
    public String onLine(String line, AxesLocation planEnd, Driver driver, double arriveInS) {
        double[] xyz = rawXyz(planEnd, driver);
        return onLine(line, xyz[0], xyz[1], xyz[2], arriveInS);
    }

    /**
     * One received line with the raw head position at that moment. Returns the lines to send before
     * "ok", or null when the line is not answered here (it is then handled as without the sensor).
     */
    public String onLine(String line, double x, double y, double z) {
        return onLine(line, x, y, z, 0);
    }

    /** As {@link #onLine(String, double, double, double)} for a head that reaches (x, y, z) in arriveInS seconds. */
    public synchronized String onLine(String line, double x, double y, double z, double arriveInS) {
        SimScene sc = scenes.get();
        if (sc == null || line == null) {
            return null;
        }
        if (noise == null) {
            noise = new Random(sc.vacuum.seed);
        }
        long now = clockNs.getAsLong();
        if (arriving != null && arrivesAtNs <= now + ARRIVAL_SLACK_NS) {
            advance(Math.min(arrivesAtNs, now));
            head = arriving;
            arriving = null;
            retarget(sc, false);
        }
        advance(now);
        double[] p = {x, y, z};
        if (arriveInS > 0) {
            arriving = p;
            arrivesAtNs = now + Math.round(arriveInS * 1e9);
            if (head == null) {
                head = p;
            }
        }
        else {
            head = p;
            arriving = null;
        }
        String code = line.contains(";") ? line.substring(0, line.indexOf(';')) : line;
        code = code.trim().toUpperCase(Locale.ROOT);
        List<String[]> words = words(code);
        int m = !words.isEmpty() && words.get(0)[0].equals("M") ? (int) Double.parseDouble(words.get(0)[1]) : -1;
        boolean pneumatic = false;
        if (m == 106 || m == 107) {
            pneumatic = fan(m == 106, words);
        }
        else if (m == 260) {
            i2cWrite(words);
        }
        retarget(sc, pneumatic || !sc.vacuum.tip.equals(lastTip));
        lastTip = sc.vacuum.tip;
        if (m == 261) {
            Double a = word(words, 'A');
            Double b = word(words, 'B');
            if (a != null && b != null && a.intValue() == SENSOR && b.intValue() == 3) {
                return reply(read(sc));
            }
        }
        return null;
    }

    /** {@code echo:i2c-reply: from:109 bytes:3 data:E8 F9 80}: 24-bit two's complement, big-endian. */
    static String reply(long counts) {
        long v = Math.max(-0x800000L, Math.min(0x7FFFFFL, counts)) & 0xFFFFFFL;
        return String.format(Locale.ROOT, "echo:i2c-reply: from:%d bytes:3 data:%02X %02X %02X", SENSOR,
                (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF);
    }

    /** Raw X, Y, Z: the driver's controller axes with the letters X, Y and Z (GcodeServer's defaults by name). */
    static double[] rawXyz(AxesLocation location, Driver driver) {
        double[] xyz = new double[3];
        boolean[] seen = new boolean[3];
        for (ControllerAxis axis : location.getControllerAxes()) {
            if (axis.getDriver() != driver) {
                continue;
            }
            String letter = axis.getLetter();
            if (letter == null || letter.length() != 1) {
                String name = axis.getName().toLowerCase(Locale.ROOT);
                letter = name.equals("x") || name.equals("y") || name.startsWith("z") ? name.substring(0, 1) : "";
            }
            int i = "XYZ".indexOf(letter.toUpperCase(Locale.ROOT));
            if (i >= 0 && !seen[i]) {
                seen[i] = true;
                xyz[i] = location.getCoordinate(axis, LengthUnit.Millimeters);
            }
        }
        return xyz;
    }

    private void advance(long now) {
        if (started) {
            double dt = Math.max(0, (now - lastNs) / 1e9);
            value = tau <= 0 ? target : target + (value - target) * Math.exp(-dt / tau);
        }
        else {
            started = true;
        }
        lastNs = Math.max(lastNs, now);
    }

    /** M106/M107 on P0 (pump) or P1 (valve); returns true if the state changed. */
    private boolean fan(boolean on, List<String[]> words) {
        Double p = word(words, 'P');
        Double s = word(words, 'S');
        int index = p == null ? 0 : p.intValue();
        boolean state = on && (s == null || s > 0);
        boolean changed;
        if (index == 0) {
            changed = pump != state;
            pump = state;
        }
        else if (index == 1) {
            changed = valve != state;
            valve = state;
        }
        else {
            changed = false;
        }
        return changed;
    }

    /** Marlin's M260: A sets the address, B adds a byte, S1 sends the buffer (R resets it). */
    private void i2cWrite(List<String[]> words) {
        Double a = word(words, 'A');
        Double b = word(words, 'B');
        Double s = word(words, 'S');
        if (a != null) {
            i2cAddress = a.intValue();
        }
        if (b != null) {
            i2cBytes.add(b.intValue() & 0xFF);
        }
        if (s != null && s.intValue() == 1) {
            if (!i2cBytes.isEmpty()) {
                if (i2cAddress == MUX) {
                    channel = i2cBytes.get(0);
                }
                else if (i2cAddress == SENSOR) {
                    register = i2cBytes.get(0);
                }
            }
            i2cBytes.clear();
        }
        else if (word(words, 'R') != null) {
            i2cBytes.clear();
        }
    }

    private long read(SimScene sc) {
        if (channel != N1_CHANNEL || register != DATA_REGISTER) {
            return Math.round(sc.vacuum.offValue);
        }
        return Math.round(value + noise.nextGaussian() * sc.vacuum.noise);
    }

    private void retarget(SimScene sc, boolean pneumatic) {
        double t = targetOf(sc, head[0], head[1], head[2]);
        if (t == target) {
            return;
        }
        if (pneumatic) {
            tau = sc.vacuum.tauS;
        }
        else {
            tau = t < target ? sc.vacuum.sealTauS : sc.vacuum.releaseTauS;
        }
        target = t;
    }

    private double targetOf(SimScene sc, double x, double y, double z) {
        SimScene.Vacuum v = sc.vacuum;
        if (!pump || !valve) {
            return v.offValue;
        }
        switch (v.tip) {
            case "missing":
                return MISSING_TIP_OPEN_AIR;
            case "blocked":
                return BLOCKED_TIP_OPEN_AIR;
            default:
                break;
        }
        boolean part = v.tip.equals("part");
        double openAir = v.openAir + (part ? PART_EXTRA_COUNTS : 0);
        double zTip = z - (part ? PART_HEIGHT_MM : 0);
        double tx = x + sc.n1OffsetX;
        double ty = y + sc.n1OffsetY;
        SimScene.Region region = sc.regionAt(tx, ty);
        double contact = sc.contactZ(region, tx, ty);
        boolean leaky = false;
        // The whole tip disc must lie on the surface under its centre: at an edge it rests on the
        // higher surface but cannot seal.
        for (int k = 0; k < 8 && sc.tipRadiusMm > 0; k++) {
            double px = tx + sc.tipRadiusMm * Math.cos(k * Math.PI / 4);
            double py = ty + sc.tipRadiusMm * Math.sin(k * Math.PI / 4);
            SimScene.Region r = sc.regionAt(px, py);
            if (r != region) {
                leaky = true;
                contact = Math.max(contact, sc.contactZ(r, px, py));
            }
        }
        if (region == SimScene.Region.BOARD) {
            Point2D.Double b = sc.toBoard(tx, ty);
            for (SimScene.Spot s : sc.leakySpots) {
                if (Math.hypot(b.x - s.bx, b.y - s.by) <= s.r) {
                    leaky = true;
                }
            }
        }
        Seal seal = zTip <= contact ? Seal.SEALED : zTip <= contact + v.partialBandMm ? Seal.PARTIAL : Seal.OPEN;
        if (seal == Seal.OPEN) {
            return openAir;
        }
        double drop = leaky ? v.leakDrop : sealDrop(v, tx, ty);
        return openAir - (seal == Seal.SEALED ? drop : drop / 2);
    }

    /** The seal drop of a spot (1 mm cells of the tip XY), drawn with the scene seed from the deciles. */
    static double sealDrop(SimScene.Vacuum v, double x, double y) {
        double[] d = v.sealDrops;
        if (d.length == 1) {
            return d[0];
        }
        long h = v.seed * 1_000_003L ^ (long) Math.floor(x) * 73_856_093L ^ (long) Math.floor(y) * 19_349_663L;
        double u = new Random(h).nextDouble() * (d.length - 1);
        int i = (int) Math.floor(u);
        return d[i] + (d[i + 1] - d[i]) * (u - i);
    }

    private static List<String[]> words(String code) {
        List<String[]> out = new ArrayList<>();
        Matcher m = WORD.matcher(code);
        while (m.find()) {
            out.add(new String[] {m.group(1), m.group(2)});
        }
        return out;
    }

    private static Double word(List<String[]> words, char letter) {
        for (int i = 1; i < words.size(); i++) {
            if (words.get(i)[0].charAt(0) == letter) {
                return Double.parseDouble(words.get(i)[1]);
            }
        }
        return null;
    }
}
