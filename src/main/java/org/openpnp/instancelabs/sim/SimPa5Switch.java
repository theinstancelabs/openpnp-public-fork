package org.openpnp.instancelabs.sim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.openpnp.model.AxesLocation;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Driver;
import org.pmw.tinylog.Logger;

/**
 * The limit switch on PA5 (the user, 11-04) of a simulated controller: N1 and the needle press it to measure the
 * needle's Z, and the engine reads it with {@code M400} then {@code M43 P5}. lumen-sim's switch (infra/lumen-sim,
 * "The PA5 switch"), set by the scene's {@link SimScene#pa5Switch}. Lives only inside OpenPnP's simulated controller
 * (org.openpnp.util.GcodeServer) and the test RecordingGcodeServer, and only when a sim scene exists.
 *
 * <p>In a press window (raw head X/Y, edges included, the first listed wins) N1 presses it as raw Z falls to the
 * press's trip point, the needle as Z rises to it (the see-saw). Each press trips at tripZ + N(0, repeatSdMm), one
 * draw per press from a Random seeded with the switch's seed, and holds that point until the tool goes back past it or
 * leaves the window. The switch sees the head where each move ends, when it ends, and where it is at each M43 P5. A
 * press deeper than overtravelMm past its point is counted and reported once. Nothing stops the head.</p>
 *
 * <p>A new {@code pa5Switch} object (a test's, or the scene file written again) is a new switch: its draws start from
 * its seed and its count from 0.</p>
 */
public final class SimPa5Switch {
    /** M43 P5's line on the real board (runs/serial.log, 11-02), byte for byte, up to the level. */
    static final String LINE = "PIN: PA5  (A9)   M42 P53          <unused/unknown>                       Input  = ";
    static final double EPS = 1e-9;

    private final Supplier<SimScene> scenes;
    private final LongSupplier clockNs;
    /** Planned moves not yet ended: {x, y, z, when (clock s)}, in time order. */
    private final ArrayDeque<double[]> ends = new ArrayDeque<>();
    private final List<String> messages = new ArrayList<>();
    /** The switch the state below belongs to; null: nothing wired. */
    private SimScene.Pa5Switch wired;
    private Random rng;
    /** The next press's trip point from its window's tripZ. */
    private double offset;
    /** The press under way (tool null: none): its trip point, its deepest, whether its overtravel was reported. */
    private String tool;
    private double trip, depth;
    private boolean reported;
    private int overtravels;

    /** A fixed scene and the wall clock (tests). */
    public SimPa5Switch(SimScene scene) {
        this(() -> scene, System::nanoTime);
    }

    public SimPa5Switch(Supplier<SimScene> scenes, LongSupplier clockNs) {
        this.scenes = scenes;
        this.clockNs = clockNs;
    }

    /**
     * The switch of a simulated controller in this configuration: null unless the sim scene file exists. The scene is
     * read again when the file changes.
     */
    public static SimPa5Switch forConfiguration(Configuration c) {
        if (c == null || !SimGuard.sceneFile(c).isFile()) {
            return null;
        }
        return new SimPa5Switch(SimScene.Source.forConfiguration(c), System::nanoTime);
    }

    /** For GcodeServer: a planned move ends at {@code end} (the driver's raw X/Y/Z) in {@code inS} seconds. */
    public void moveEnds(AxesLocation end, Driver driver, double inS) {
        double[] p = SimVacuum.rawXyz(end, driver);
        moveEnds(p[0], p[1], p[2], inS);
    }

    /** A move ends at raw (x, y, z) in {@code inS} seconds (0: the head is there now). */
    public synchronized void moveEnds(double x, double y, double z, double inS) {
        ends.addLast(new double[] {x, y, z, clockNs.getAsLong() / 1e9 + Math.max(0, inS)});
    }

    /**
     * For GcodeServer: a received line, with where its motion plan ends and (asked only for an M43 P5 while a move is
     * under way) where the plan has the axes now. Returns the line to send before "ok", or null.
     */
    public String onLine(String line, AxesLocation planEnd, Supplier<AxesLocation> now, Driver driver) {
        // With every move ended (M400 returns up to a millisecond early) the head is exactly where the plan ends.
        return read(line, () -> SimVacuum.rawXyz(ends.isEmpty() ? planEnd : now.get(), driver));
    }

    /** A received line with the raw head at (x, y, z) now: M43 P5's line to send before "ok", else null. */
    public String onLine(String line, double x, double y, double z) {
        return read(line, () -> new double[] {x, y, z});
    }

    private synchronized String read(String line, Supplier<double[]> head) {
        if (catchUp() == null || line == null || !isRead(line)) {
            return null;
        }
        if (wired != null) {
            double[] p = head.get();
            observe(p[0], p[1], p[2]);
        }
        return LINE + levelNow();
    }

    /** M43 with P5 (the switch's pin); M43 without P or with another pin is left as it was (plain "ok"). */
    static boolean isRead(String line) {
        String code = line.contains(";") ? line.substring(0, line.indexOf(';')) : line;
        List<String[]> words = SimVacuum.words(code.trim().toUpperCase(Locale.ROOT));
        Double p = SimVacuum.word(words, 'P');
        return !words.isEmpty() && words.get(0)[0].equals("M") && Double.parseDouble(words.get(0)[1]) == 43
                && p != null && p == 5;
    }

    /** The pin's level, as of the last move end or read (0 with nothing wired, as the board read 11-02). */
    public synchronized int level() {
        catchUp();
        return levelNow();
    }

    public synchronized boolean pressed() {
        catchUp();
        return tool != null;
    }

    /** The tool of the press under way (n1 or needle), or null. */
    public synchronized String tool() {
        catchUp();
        return tool;
    }

    /** The trip point (raw Z) of the press under way, or null. */
    public synchronized Double tripZ() {
        catchUp();
        return tool == null ? null : trip;
    }

    /** How far (mm) the press under way went past its trip point at its deepest, or null. */
    public synchronized Double depthMm() {
        catchUp();
        return tool == null ? null : depth;
    }

    /** Presses of this switch that went deeper than its overtravel. */
    public synchronized int overtravels() {
        catchUp();
        return overtravels;
    }

    /** The overtravel reports, oldest first (also logged). */
    public synchronized List<String> messages() {
        catchUp();
        return new ArrayList<>(messages);
    }

    /** The scene now (null: none), after the head was seen at each move end that is due. */
    private SimScene catchUp() {
        SimScene sc = scenes.get();
        SimScene.Pa5Switch s = sc == null ? null : sc.pa5Switch;
        if (s != wired) {
            wired = s;
            tool = null;
            overtravels = 0;
            rng = s == null ? null : new Random(s.seed);
            offset = s == null ? 0 : rng.nextGaussian() * s.repeatSdMm;
        }
        double now = clockNs.getAsLong() / 1e9 + SimVacuum.ARRIVAL_SLACK_NS / 1e9;
        while (!ends.isEmpty() && ends.peekFirst()[3] <= now) {
            double[] e = ends.pollFirst();
            if (wired != null) {
                observe(e[0], e[1], e[2]);
            }
        }
        return sc;
    }

    private int levelNow() {
        return wired == null ? 0 : tool != null ? wired.pressedLevel : 1 - wired.pressedLevel;
    }

    /** The head at raw (x, y, z), as lumen-sim's Pa5Switch.observe. */
    private void observe(double x, double y, double z) {
        SimScene.Pa5Press w = null;
        for (SimScene.Pa5Press p : wired.presses) {
            if (p.x0 <= x && x <= p.x1 && p.y0 <= y && y <= p.y1) {
                w = p;
                break;
            }
        }
        if (tool != null && (w == null || !w.tool.equals(tool))) {
            release();
        }
        if (w == null) {
            return;
        }
        double at = tool != null ? trip : w.tripZ + offset;
        double d = w.tool.equals("n1") ? at - z : z - at;
        if (d < -EPS) {
            if (tool != null) {
                release();
            }
            return;
        }
        if (tool == null) {
            tool = w.tool;
            trip = at;
            depth = 0;
            reported = false;
        }
        depth = Math.max(depth, d);
        if (depth > wired.overtravelMm + EPS && !reported) {
            reported = true;
            overtravels++;
            String m = "PA5 switch overtravel: " + tool + " " + mm(depth) + " mm past its trip point (overtravel "
                    + mm(wired.overtravelMm) + " mm)";
            messages.add(m);
            Logger.warn(m);
        }
    }

    /** The tool is off the switch: the next press draws its own trip point. */
    private void release() {
        tool = null;
        offset = rng.nextGaussian() * wired.repeatSdMm;
    }

    /** As lumen-sim's %.2f: the exact value rounded half to even (Java's %.2f rounds its shortest decimal half up). */
    static String mm(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
