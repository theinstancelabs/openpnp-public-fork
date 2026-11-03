package org.openpnp.instancelabs.sim;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.model.AxesLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.spi.Driver;
import org.openpnp.spi.Head;
import org.openpnp.spi.HeadMountable;
import org.openpnp.spi.Machine;
import org.pmw.tinylog.Logger;

/**
 * The paste of the SIM needle (N2), fed every line a simulated controller receives with where the
 * head is: dots appear where the needle dispenses (blank, board, weigh coupons), sized by a model
 * that differs on purpose from the engine's (PROFILE_CALIBRATION_DESIGN.md §11.2), and M114 reports
 * Marlin's B step count. Lives only inside OpenPnP's simulated controller (org.openpnp.util.GcodeServer)
 * and the test RecordingGcodeServer, and only when a sim scene exists; without a {@code paste} block
 * in the scene it only follows the screw and changes nothing.
 *
 * <ul>
 * <li><b>Screw.</b> Logical B from the G92/G0/G1 B words (degrees), the machine B behind it (G92 B keeps
 * it, as Marlin), the count = round(machine B × 4.44) since this connection, and a physical offset per
 * connection (the scene's {@code offsetSteps}, else drawn from Random(seed + connection)): the physical
 * angle (count + offset) mod 1600 is lost on reconnect, as on the bench after a power cycle.</li>
 * <li><b>Mass.</b> With the pressure on (M106 P2 S255 and M106 P3 S255 in force) a B move from θ0 to
 * θ1 steps lays mRev·Δ/1600 + mRev·ripple/2π·[sin(2πθ1/1600 − ψ) − sin(2πθ0/1600 − ψ)] (the back move
 * subtracts its own), × the session transient after an idle, × (1 + N(0, cv)), × min(1, feedLimitMgS /
 * demand), the demand being the paste the screw asked for in the last 5 s (this dot included) / 5 s.</li>
 * <li><b>Dot.</b> At the end of the back move (or when the needle moves): the tip bead takes the
 * new paste and a dot takes transferRatio of it; the needle tip = raw head X/Y + n2OffsetMm + the
 * landing scatter; above maxGapMm nothing reaches the surface, below the surface it smears.</li>
 * <li><b>Thread.</b> On the hop after a small dot (by its mass), drawn along the hop.</li>
 * </ul>
 *
 * <p>Time is the controller's: a model clock that follows the wall clock and adds every move and
 * dwell (so a test server that answers at once still shows the cadence a real controller has).
 * Deposits live in a {@link Store} per scene file (they survive scene reloads and reconnects, as
 * paste on the bench does); the screw state is per connection.</p>
 */
public final class SimPaste {
    /** Marlin's M92 B and the steps of one screw revolution (D/DispenseConfig.java:134-136). */
    public static final double STEPS_PER_DEG = 4.44;
    public static final int STEPS_PER_REV = 1600;
    /** The right pump and valve: the barrel pressure (D/ControllerCommands.java:43-46). */
    static final int PUMP_OUTPUT = 2, VALVE_OUTPUT = 3;
    /** The feed demand is the paste laid in this window (s) divided by it. */
    static final double FEED_WINDOW_S = 5;
    /** A thread: 0.15 mm wide, 0.6 × the hop long (at most 3 mm), 0.02 mg taken from the tip bead. */
    static final double THREAD_WIDTH_MM = 0.15, THREAD_SHARE_OF_HOP = 0.6, THREAD_MAX_MM = 3.0, THREAD_MG = 0.02;
    /**
     * A hop starting this close above the dot's surface draws a thread (the dot hop is hopHeightMm 1.0
     * above the dispense Z); a lift to the travel height breaks it.
     */
    static final double THREAD_MAX_LIFT_MM = 2.0;
    /** A needle below the surface smears its dot to this axis ratio (counted as a string). */
    static final double SMEAR_RATIO = 2;
    /** A round dot's axis ratio is 1 + |N(0, 0.05)|. */
    static final double ROUNDNESS_SD = 0.05;
    static final String COUNT_PREFIX = "Count X:0 Y:0 Z:0 B:";
    private static final Pattern WORD = Pattern.compile("([A-Z])\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+))");
    private static final AtomicLong VERSION = new AtomicLong();

    /** Paste on a surface (machine mm): a dot, a thread or a smear, drawn as a filled ellipse. */
    public static final class Deposit {
        public final double x, y, mg;
        /** Semi-axes (mm) and the direction of the first one (deg, counter-clockwise from +X). */
        public final double aMm, bMm, angleDeg;
        public final SimScene.Region region;
        /** The weigh coupon's place, or null. */
        public final String place;
        public final boolean thread, string;
        /** The B step count when the dot's first turn began (null for a thread). */
        public final Long startCount;
        /** Model time (s) when it was laid. */
        public final double atS;

        Deposit(double x, double y, double mg, double aMm, double bMm, double angleDeg, SimScene.Region region,
                String place, boolean thread, boolean string, Long startCount, double atS) {
            this.x = x;
            this.y = y;
            this.mg = mg;
            this.aMm = aMm;
            this.bMm = bMm;
            this.angleDeg = angleDeg;
            this.region = region;
            this.place = place;
            this.thread = thread;
            this.string = string;
            this.startCount = startCount;
            this.atS = atS;
        }

        public double areaMm2() {
            return Math.PI * aMm * bMm;
        }

        /** Equivalent-circle diameter (mm), as DotInspector reports it. */
        public double diameterMm() {
            return 2 * Math.sqrt(aMm * bMm);
        }

        /**
         * {@code where}: null or "all"; a region name ("board", "blank", "coupon", "bed"); or a coupon's
         * place.
         */
        public boolean in(String where) {
            if (where == null || where.equalsIgnoreCase("all")) {
                return true;
            }
            return region.name().equalsIgnoreCase(where) || where.equals(place);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "Deposit[%s%s %.3f,%.3f %.3f mg d %.3f%s%s]", region,
                    place == null ? "" : " " + place, x, y, mg, diameterMm(), thread ? " thread" : "",
                    string ? " string" : "");
        }
    }

    /**
     * The paste on the surfaces of one scene, the SIM balance's pan, and the world clock: per scene
     * file (so they survive scene reloads and reconnects), or per scene object for a scene built in
     * code.
     */
    public static final class Store {
        private static final Map<String, Store> BY_FILE = new HashMap<>();
        private static final Map<SimScene, Store> BY_SCENE = new WeakHashMap<>();
        private final List<Deposit> deposits = new ArrayList<>();
        /** (time s, mg) of the paste the screw asked for lately, for the feed demand. */
        private final ArrayDeque<double[]> asked = new ArrayDeque<>();
        private int connections;
        private double timeS = Double.NEGATIVE_INFINITY;
        private Double lastDotS;
        private String balancePlace;
        private long balanceLoadedAtMs;
        private Random balanceNoise;

        Store() {
        }

        public static synchronized Store forFile(File f) {
            return BY_FILE.computeIfAbsent(key(f), (k) -> new Store());
        }

        /** The scene file's store, or (a scene built in code) the scene object's. */
        public static synchronized Store forScene(SimScene sc) {
            if (sc.file != null) {
                return forFile(sc.file);
            }
            return BY_SCENE.computeIfAbsent(sc, (k) -> new Store());
        }

        /** As {@link #forScene} without creating one: null when nothing was ever laid there. */
        static synchronized Store existing(SimScene sc) {
            return sc.file != null ? BY_FILE.get(key(sc.file)) : BY_SCENE.get(sc);
        }

        private static String key(File f) {
            return f.getAbsoluteFile().toPath().normalize().toString();
        }

        /** Deposits in order, {@code where} as {@link Deposit#in}. */
        public synchronized List<Deposit> deposits(String where) {
            List<Deposit> out = new ArrayList<>();
            for (Deposit d : deposits) {
                if (d.in(where)) {
                    out.add(d);
                }
            }
            return out;
        }

        /** Paste (mg) on a region or coupon place, threads included. */
        public synchronized double massMg(String where) {
            double m = 0;
            for (Deposit d : deposits) {
                if (d.in(where)) {
                    m += d.mg;
                }
            }
            return m;
        }

        /** Removes the deposits of a region or coupon place (null or "all": every one); returns how many. */
        public synchronized int wipe(String where) {
            int before = deposits.size();
            deposits.removeIf((d) -> d.in(where));
            int n = before - deposits.size();
            if (n > 0) {
                VERSION.incrementAndGet();
            }
            return n;
        }

        synchronized void add(Deposit d) {
            deposits.add(d);
            VERSION.incrementAndGet();
        }

        synchronized int connect() {
            return connections++;
        }

        /** The model clock never runs behind the wall clock. */
        synchronized double catchUp(double wallS) {
            timeS = Math.max(timeS, wallS);
            return timeS;
        }

        synchronized void advance(double s) {
            if (s > 0) {
                timeS += s;
            }
        }

        synchronized double timeS() {
            return timeS;
        }

        synchronized Double lastDotS() {
            return lastDotS;
        }

        synchronized void dotAt(double t) {
            lastDotS = t;
        }

        /** Paste the screw asked for after {@code sinceS} (mg); older entries are dropped. */
        synchronized double askedSince(double sinceS) {
            while (!asked.isEmpty() && asked.peekFirst()[0] <= sinceS) {
                asked.removeFirst();
            }
            double m = 0;
            for (double[] e : asked) {
                m += e[1];
            }
            return m;
        }

        synchronized void asked(double t, double mg) {
            asked.addLast(new double[] {t, mg});
        }

        /** The coupon on the SIM balance's pan (null: empty) and since when (wall ms). */
        synchronized void balanceLoad(String place, long atMs) {
            balancePlace = place;
            balanceLoadedAtMs = atMs;
        }

        synchronized String balancePlace() {
            return balancePlace;
        }

        synchronized long balanceLoadedAtMs() {
            return balanceLoadedAtMs;
        }

        synchronized double balanceNoise(long seed) {
            if (balanceNoise == null) {
                balanceNoise = new Random(seed);
            }
            return balanceNoise.nextGaussian();
        }
    }

    private final Supplier<SimScene> scenes;
    private final Store store;
    private final LongSupplier clockNs;
    private final DoubleSupplier configNeedleZOffsetMm;
    private int connection;
    private Integer offset;
    private Random noise;
    // The screw and the pneumatics.
    private double logicalB, machineB;
    private boolean absolute = true;
    private double feedPerMin, accel = Double.NaN;
    private boolean pump, valve;
    /** Dots since the pressure came on; the transient of this session. */
    private int sessionDots;
    private double sessionG;
    private boolean sessionDead;
    // The dot being dispensed.
    private boolean dotOpen, forwardSeen, pressured;
    private double extruded;
    private long dotStartCount;
    private double dotX, dotY, dotZ;
    /** Paste hanging on the tip (mg): what a dot did not take. */
    private double bead;
    /** The last dot, until the next hop (its thread), and the needle Z touching its surface. */
    private Deposit lastDot;
    private double lastDotTouchZ;
    // Where the head is: raw X/Y, the needle's Z.
    private boolean havePos;
    private double posX, posY, posZ;
    // GcodeServer: the needle whose Z the raw axes give.
    private HeadMountable needle;
    private boolean needleLooked;

    /** A fixed scene on the wall clock (tests); the needle Z offset of the running dispense service. */
    public SimPaste(SimScene scene) {
        this(() -> scene, Store.forScene(scene), System::nanoTime, SimPaste::configNeedleZOffsetMm);
    }

    /**
     * @param configNeedleZOffsetMm the configuration's needle Z offset (when the scene has none)
     */
    public SimPaste(Supplier<SimScene> scenes, Store store, LongSupplier clockNs, DoubleSupplier configNeedleZOffsetMm) {
        this.scenes = scenes;
        this.store = store;
        this.clockNs = clockNs;
        this.configNeedleZOffsetMm = configNeedleZOffsetMm;
        this.connection = store.connect();
    }

    /**
     * The paste of a simulated controller in this configuration (one per connection): null unless the
     * sim scene file exists. The scene is read again when the file changes, so a {@code paste} block
     * written into a running session takes effect at once.
     */
    public static SimPaste forConfiguration(Configuration c) {
        if (c == null || !SimGuard.sceneFile(c).isFile()) {
            return null;
        }
        return new SimPaste(SimScene.Source.forConfiguration(c), Store.forFile(SimGuard.sceneFile(c)),
                System::nanoTime, SimPaste::configNeedleZOffsetMm);
    }

    /** Increments on every deposit and wipe anywhere (the SIM camera's frame cache). */
    public static long version() {
        return VERSION.get();
    }

    /** The paste of a scene (empty when nothing was ever laid there). */
    public static List<Deposit> depositsFor(SimScene sc) {
        Store s = sc == null ? null : Store.existing(sc);
        return s == null ? Collections.emptyList() : s.deposits(null);
    }

    /**
     * The tip on the SIM head (§22.8): the scene's {@code physicalTip}, else the mounted setup of the running dispense
     * service, else none (the scene's paste as it is).
     */
    static String physicalTip(SimScene sc) {
        if (sc.physicalTip != null) {
            return sc.physicalTip;
        }
        DispenseService svc = DispenseService.get();
        return svc == null ? null : svc.mountedKey();
    }

    /** The running dispense service's needle Z offset (0 without the service, DispenseConfig's default). */
    static double configNeedleZOffsetMm() {
        DispenseService svc = DispenseService.get();
        return svc == null ? 0 : svc.getConfig().needleZOffsetMm;
    }

    public Store store() {
        return store;
    }

    public List<Deposit> deposits(String where) {
        return store.deposits(where);
    }

    /** Paste on a coupon place (mg), threads included. */
    public double couponMassMg(String place) {
        return store.massMg(place);
    }

    public int wipe(String where) {
        return store.wipe(where);
    }

    /** This connection's number on the store (0 for the first). */
    public synchronized int connectionNumber() {
        return connection;
    }

    /**
     * A power cycle of the controller (a new connection): the count restarts at 0, the physical
     * offset is drawn again, the pressure is off. The paste on the surfaces and on the tip stays.
     */
    public synchronized void powerCycle() {
        connection = store.connect();
        offset = null;
        noise = null;
        logicalB = 0;
        machineB = 0;
        absolute = true;
        feedPerMin = 0;
        accel = Double.NaN;
        pump = false;
        valve = false;
        sessionDots = 0;
        dotOpen = false;
        forwardSeen = false;
        pressured = false;
        extruded = 0;
        lastDot = null;
        havePos = false;
    }

    /** Marlin's B step count since this connection. */
    public synchronized long count() {
        return Math.round(machineB * STEPS_PER_DEG);
    }

    /** Sets the step count (the screw's history since the controller's power-up); logical B stays. */
    public synchronized void setCount(long steps) {
        machineB = steps / STEPS_PER_DEG;
    }

    /** {@code Count X:0 Y:0 Z:0 B:<count>}, the part of Marlin's M114 reply the engine reads. */
    public synchronized String countLine() {
        return COUNT_PREFIX + count();
    }

    /** True while the scene has a paste block that reports the count. */
    public boolean reportsCount() {
        SimScene sc = scenes.get();
        return sc != null && sc.paste != null && sc.paste.reportCount;
    }

    /** True while the scene has a paste block. */
    public boolean isActive() {
        SimScene sc = scenes.get();
        return sc != null && sc.paste != null;
    }

    /** The physical screw angle at count 0 (steps); needs a paste block. */
    public synchronized int offsetSteps() {
        SimScene sc = scenes.get();
        if (sc == null || sc.paste == null) {
            throw new IllegalStateException("the sim scene has no paste block");
        }
        return offset(sc.paste);
    }

    /** Paste hanging on the tip (mg). */
    public synchronized double beadMg() {
        return bead;
    }

    /**
     * For GcodeServer: raw X/Y from the driver's controller axes of the planned location, the needle's
     * Z through OpenPnP's own transforms (the see-saw's mapped Z of the dispense nozzle).
     */
    public synchronized String onLine(String line, AxesLocation planned, Driver driver) {
        double[] xyz = SimVacuum.rawXyz(planned, driver);
        return onLine(line, xyz[0], xyz[1], needleZ(planned, xyz[2]));
    }

    /**
     * One received line with where the head is: raw head X/Y and the needle's Z in OpenPnP's frame.
     * A move is seen when the position changes between two lines. Returns the lines to send before
     * the reply (the Count line of M114), or null.
     */
    public synchronized String onLine(String line, double x, double y, double needleZ) {
        SimScene sc = scenes.get();
        if (sc == null || line == null) {
            return null;
        }
        SimScene.Paste p = sc.pasteFor(physicalTip(sc));
        store.catchUp(clockNs.getAsLong() / 1e9);
        moved(sc, p, x, y, needleZ);
        String code = line.contains(";") ? line.substring(0, line.indexOf(';')) : line;
        List<String[]> words = words(code.trim().toUpperCase(Locale.ROOT));
        if (words.isEmpty()) {
            return null;
        }
        char letter = words.get(0)[0].charAt(0);
        double number = Double.parseDouble(words.get(0)[1]);
        if (letter == 'G') {
            if (number == 0 || number == 1) {
                Double f = word(words, 'F');
                if (f != null) {
                    feedPerMin = f;
                }
                Double b = word(words, 'B');
                if (b != null) {
                    turn(sc, p, absolute ? b - logicalB : b);
                }
            }
            else if (number == 92) {
                Double b = word(words, 'B');
                if (b != null) {
                    logicalB = b; // Marlin: a workspace offset, the machine position stays
                }
            }
            else if (number == 4) {
                Double ms = word(words, 'P');
                Double s = word(words, 'S');
                store.advance((ms == null ? 0 : ms / 1000) + (s == null ? 0 : s));
            }
            else if (number == 90 || number == 91) {
                absolute = number == 90;
            }
        }
        else if (letter == 'M') {
            if (number == 204) {
                Double s = word(words, 'S');
                if (s != null) {
                    accel = s;
                }
            }
            else if (number == 106 || number == 107) {
                pressure(number == 106, words);
            }
            else if (number == 114 && p != null && p.reportCount) {
                return countLine();
            }
        }
        return null;
    }

    /** The head moved since the last line: its time, the end of a dot without a back move, a thread. */
    private void moved(SimScene sc, SimScene.Paste p, double x, double y, double z) {
        if (!havePos) {
            havePos = true;
            posX = x;
            posY = y;
            posZ = z;
            return;
        }
        double dx = x - posX, dy = y - posY, dz = z - posZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1e-9) {
            return;
        }
        store.advance(moveSeconds(dist, feedPerMin / 60, accel));
        if (dotOpen) {
            closeDot(sc, p);
        }
        double hop = Math.hypot(dx, dy);
        if (lastDot != null && hop > 1e-9) {
            if (p != null && posZ - lastDotTouchZ <= THREAD_MAX_LIFT_MM) {
                thread(p, lastDot, dx / hop, dy / hop, hop);
            }
            lastDot = null;
        }
        posX = x;
        posY = y;
        posZ = z;
    }

    /** A B move of {@code deg} degrees: the count, its time, and with the pressure on the paste it pushes. */
    private void turn(SimScene sc, SimScene.Paste p, double deg) {
        long c0 = count();
        machineB += deg;
        logicalB += deg;
        long c1 = count(); // the motor turns whole steps
        store.advance(moveSeconds(Math.abs(deg), feedPerMin / 60, accel));
        if (p == null || c1 == c0) {
            return;
        }
        if (!dotOpen) {
            dotOpen = true;
            forwardSeen = false;
            pressured = false;
            extruded = 0;
            dotStartCount = c0;
            dotX = posX;
            dotY = posY;
            dotZ = posZ;
        }
        if (pump && valve) {
            extruded += flowMg(p, c0, c1, offset(p));
            pressured = true;
        }
        if (c1 < c0 && forwardSeen) {
            closeDot(sc, p); // the suck-back ends the dot
        }
        else if (c1 > c0) {
            forwardSeen = true;
        }
    }

    /**
     * Paste pushed by the screw from count c0 to c1 (steps; negative backwards) at a physical offset:
     * mRev·Δ/1600 + mRev·ripple/2π·[sin(2π(c1 + off)/1600 − ψ) − sin(2π(c0 + off)/1600 − ψ)].
     */
    static double flowMg(SimScene.Paste p, double c0, double c1, double off) {
        double k = 2 * Math.PI / STEPS_PER_REV;
        double psi = Math.toRadians(p.ripplePeakDeg);
        return p.mRevMg * (c1 - c0) / STEPS_PER_REV
                + p.mRevMg * p.ripple / (2 * Math.PI) * (Math.sin(k * (c1 + off) - psi) - Math.sin(k * (c0 + off) - psi));
    }

    private void closeDot(SimScene sc, SimScene.Paste p) {
        dotOpen = false;
        forwardSeen = false;
        double ex = extruded;
        boolean pr = pressured;
        extruded = 0;
        pressured = false;
        if (p == null || !pr || ex <= 1e-9) {
            return; // net zero, or no pressure: no paste
        }
        double t = store.timeS();
        int n = sessionDots++;
        if (n == 0) {
            Double last = store.lastDotS();
            double idle = last == null ? p.idleFullS : Math.max(0, t - last);
            sessionG = Math.min(1, idle / p.idleFullS);
            sessionDead = idle >= p.idleDeadS;
        }
        store.dotAt(t);
        lastDot = null;
        if (sessionDead && n < 2) {
            return; // after a long idle with the pressure off the first two dots lay nothing
        }
        Random r = noise(p);
        double f = n == 0 ? 1 + p.oozeFirst * sessionG
                : 1 - p.refillDip * sessionG * Math.exp(-(n - 1) / p.refillTauDots);
        double m = Math.max(0, ex * f * (1 + r.nextGaussian() * p.cv));
        double demand = (store.askedSince(t - FEED_WINDOW_S) + m) / FEED_WINDOW_S;
        store.asked(t, m);
        if (demand > p.feedLimitMgS) {
            m *= p.feedLimitMgS / demand; // the screw pumps faster than the paste feeds it
        }
        double lx = dotX + p.n2OffsetMm[0] + p.landingBiasMm[0] + r.nextGaussian() * p.landingSdMm;
        double ly = dotY + p.n2OffsetMm[1] + p.landingBiasMm[1] + r.nextGaussian() * p.landingSdMm;
        SimScene.Region region = sc.regionAt(lx, ly);
        SimScene.Coupon coupon = region == SimScene.Region.COUPON ? sc.couponAt(lx, ly) : null;
        double touchZ = sc.contactZ(region, lx, ly)
                + (p.needleZOffsetMm != null ? p.needleZOffsetMm : configNeedleZOffsetMm.getAsDouble());
        double gap = dotZ - touchZ;
        double total = bead + m;
        if (gap > p.maxGapMm) {
            bead = total; // in the air: everything stays on the tip
            return;
        }
        double dep = p.transferRatio * total;
        if (dep < p.missBelowMg) {
            bead = total;
            return;
        }
        bead = total - dep;
        boolean smear = gap < 0;
        double area = p.footprintC * Math.pow(dep, p.footprintE) * Math.max(0.05, 1 + r.nextGaussian() * p.cv / 2);
        double ratio = smear ? SMEAR_RATIO : 1 + Math.abs(r.nextGaussian() * ROUNDNESS_SD);
        double angle = r.nextDouble() * 180;
        Deposit d = new Deposit(lx, ly, dep, Math.sqrt(area * ratio / Math.PI), Math.sqrt(area / (Math.PI * ratio)),
                angle, region, coupon == null ? null : coupon.place, false, smear, dotStartCount, t);
        store.add(d);
        lastDot = d;
        lastDotTouchZ = touchZ;
    }

    /** The hop after a dot of mass m: a thread with probability threadShare, falling to 0 at threadZeroMg. */
    private void thread(SimScene.Paste p, Deposit dot, double ux, double uy, double hop) {
        double share;
        if (p.threadZeroMg > p.threadFullMg) {
            share = Math.max(0, Math.min(1, (p.threadZeroMg - dot.mg) / (p.threadZeroMg - p.threadFullMg)));
        }
        else {
            share = dot.mg <= p.threadFullMg ? 1 : 0;
        }
        if (noise(p).nextDouble() >= p.threadShare * share) {
            return;
        }
        double len = Math.min(THREAD_SHARE_OF_HOP * hop, THREAD_MAX_MM);
        double from = dot.diameterMm() / 2 + len / 2;
        double mg = Math.min(THREAD_MG, bead);
        bead -= mg;
        store.add(new Deposit(dot.x + ux * from, dot.y + uy * from, mg, len / 2, THREAD_WIDTH_MM / 2,
                Math.toDegrees(Math.atan2(uy, ux)), dot.region, dot.place, true, false, null, store.timeS()));
    }

    /** M106/M107 P2 (pump) and P3 (valve): the pressure is on while both are. */
    private void pressure(boolean on, List<String[]> words) {
        Double pw = word(words, 'P');
        Double s = word(words, 'S');
        int output = pw == null ? 0 : pw.intValue();
        boolean state = on && (s == null || s > 0);
        boolean before = pump && valve;
        if (output == PUMP_OUTPUT) {
            pump = state;
        }
        else if (output == VALVE_OUTPUT) {
            valve = state;
        }
        if (!before && pump && valve) {
            sessionDots = 0;
        }
    }

    private int offset(SimScene.Paste p) {
        if (p.offsetSteps != null) {
            return p.offsetSteps;
        }
        if (offset == null) {
            offset = new Random(p.seed + connection).nextInt(STEPS_PER_REV);
        }
        return offset;
    }

    private Random noise(SimScene.Paste p) {
        if (noise == null) {
            noise = new Random((p.seed + connection) * 0x9E3779B97F4A7C15L + 1);
        }
        return noise;
    }

    /** The needle's Z from the planned axes (raw Z when the dispense nozzle is not found). */
    private double needleZ(AxesLocation planned, double rawZ) {
        if (!needleLooked) {
            needleLooked = true;
            needle = findNeedle();
            if (needle == null) {
                Logger.warn("sim paste: no dispense nozzle on the machine, the needle Z is taken as raw Z");
            }
        }
        if (needle == null) {
            return rawZ;
        }
        try {
            return needle.toHeadMountableLocation(needle.toTransformed(planned)).convertToUnits(LengthUnit.Millimeters)
                    .getZ();
        }
        catch (RuntimeException e) {
            return rawZ;
        }
    }

    /** The dispense service's nozzle (DispenseConfig.nozzle, default N2) on any head. */
    static HeadMountable findNeedle() {
        String name = "N2";
        DispenseService svc = DispenseService.get();
        if (svc != null) {
            name = svc.getConfig().nozzle;
        }
        Machine m = Configuration.get().getMachine();
        if (m == null) {
            return null;
        }
        for (Head h : m.getHeads()) {
            HeadMountable n = h.getNozzleByName(name);
            if (n != null) {
                return n;
            }
        }
        return null;
    }

    /** Time of one move from rest to rest (trapezoid, or triangle if it never reaches the feed), as the builder's. */
    static double moveSeconds(double dist, double feed, double accel) {
        if (dist <= 0 || !(feed > 0)) {
            return 0;
        }
        if (!(accel > 0) || Double.isInfinite(accel)) {
            return dist / feed;
        }
        double accelDist = feed * feed / accel;
        return dist >= accelDist ? dist / feed + feed / accel : 2 * Math.sqrt(dist / accel);
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
