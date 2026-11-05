package org.openpnp.instancelabs.sim;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.DoubleStream;

import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Placement;
import org.pmw.tinylog.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The simulated world of the SIM vacuum sensor, the SIM top camera, the SIM paste, the SIM
 * balance and the PA5 switch: where the board truly sits, the heights N1 touches, holes, paste,
 * the needle's paste model, the weigh coupons, the balance and where the switch trips. Read from
 * {@code <configDir>/instancelabs/sim-scene.json}, which only the sim scripts write, into a config
 * copy (src/test/sim/il_sim_scene.py). Missing keys take the bench defaults below. Public fields,
 * so tests can build and change a scene in code.
 *
 * <p>Frames: board mm (the board file's placements, origin at the board's bottom-left corner) and
 * machine mm (OpenPnP's frame, the X/Y the head is sent to). {@link #seat} maps the first to the
 * second.</p>
 */
public final class SimScene {
    /** A plane z = z0 + slopeX·u + slopeY·v, with (u, v) measured from the plane's reference point. */
    public static final class Plane {
        public double z0, slopeX, slopeY;

        public Plane(double z0, double slopeX, double slopeY) {
            this.z0 = z0;
            this.slopeX = slopeX;
            this.slopeY = slopeY;
        }

        public double at(double u, double v) {
            return z0 + slopeX * u + slopeY * v;
        }
    }

    /** A circle in board mm (a leaky hole or via). */
    public static final class Spot {
        public double bx, by, r;

        public Spot(double bx, double by, double r) {
            this.bx = bx;
            this.by = by;
            this.r = r;
        }
    }

    /** A fiducial in board mm: copper disk {@code d} inside a mask opening {@code mask}. */
    public static final class Fiducial {
        public String name;
        public double bx, by, d, mask;

        public Fiducial(String name, double bx, double by, double d, double mask) {
            this.name = name;
            this.bx = bx;
            this.by = by;
            this.d = d;
            this.mask = mask;
        }
    }

    /**
     * The N1 vacuum sensor (counts as the controller reports them; more negative = more vacuum). The
     * time constants are on the wall clock: a session with the simulator timings (vacuumSettleMs 300,
     * readDelayMs 0) needs fast ones (il_sim_scene.py FAST_VACUUM), as a real sensor would.
     */
    public static final class Vacuum {
        public double openAir = -1_550_000;
        /** Seal drop deciles (offline parse of runs/serial.log); each spot draws its own drop from them. */
        public double[] sealDrops = {257000, 271000, 290000, 303000, 316000, 329000, 350000, 370000, 404000};
        public double partialBandMm = 0.03;
        public double leakDrop = 15000;
        public double offValue = 0;
        public double tauS = 0.3, sealTauS = 0.03, releaseTauS = 0.4;
        public double noise = 1500;
        public long seed = 7;
        /** normal | missing | blocked | part */
        public String tip = "normal";
    }

    /** The simulated top camera's departures from its configuration. */
    public static final class Camera {
        /** True mm/px = configured × (1 + error), per axis. */
        public double[] uppError = {0, 0};
        /** Uniform grey-level noise ±noise. */
        public int noise = 4;
        public int blurPx = 1;
    }

    public static final String[] TIP_STATES = {"normal", "missing", "blocked", "part"};
    /**
     * The paste of the SIM needle (SimPaste, PROFILE_CALIBRATION_DESIGN.md §11.2-11.3). Its mass per
     * turn, footprint law, ripple and noise differ on purpose from the engine's model, so a
     * calibration has to measure them.
     */
    public static final class Paste {
        /** Mass per screw revolution (mg): on purpose not the engine's 2.11 (tools/lumen.py:97); a weighing finds it. */
        public double mRevMg = 2.30;
        /** First-harmonic flow ripple (share of the mean flow) and the physical angle of its peak (deg). */
        public double ripple = 0.15, ripplePeakDeg = 299;
        /** Dot-to-dot mass noise (CV); the footprint gets cv / 2 on top. */
        public double cv = 0.08;
        public long seed = 11;
        /** Footprint on the surface: A = c·m^e (mm², mg). */
        public double footprintC = 0.875, footprintE = 0.77;
        /** Share of the tip bead plus the new paste that a dot takes; the rest stays on the tip. */
        public double transferRatio = 0.75;
        /** The first dots of a session after an idle: f(0) = 1 + oozeFirst·g, f(n) = 1 − refillDip·g·e^(−(n−1)/tau). */
        public double oozeFirst = 0.40, refillDip = 0.30, refillTauDots = 2;
        /** g = min(1, idle / idleFullS); after idleDeadS the first two dots lay nothing. */
        public double idleFullS = 300, idleDeadS = 3600;
        /** Thread probability on the hop after a dot: threadShare up to threadFullMg, falling to 0 at threadZeroMg. */
        public double threadShare = 0.5, threadFullMg = 0.35, threadZeroMg = 0.45;
        /** A deposit below this does not stick (it stays on the tip). */
        public double missBelowMg = 0.12;
        /**
         * Needle-to-surface gap above which nothing reaches the surface (a dot commanded at it lands). shortcut: one
         * reach for every tip; add maxGapMm to byTip when a SIM scene runs the 25G above 0.20 (Process: it lands
         * over 0.10-0.30, runs/p35).
         */
        public double maxGapMm = 0.20;
        /**
         * The true needle Z offset (N2 Z touching a surface of N1 contact Z s = s + this), as
         * DispenseConfig.needleZOffsetMm; null = the configuration's.
         */
        public Double needleZOffsetMm;
        /** The paste the screw can be fed with (mg/s over 5 s); above it every dot shrinks. */
        public double feedLimitMgS = 3.0;
        public double landingSdMm = 0.03;
        public double[] landingBiasMm = {0, 0};
        /** The needle tip = raw head X/Y + this (the bench N2 head offset). */
        public double[] n2OffsetMm = {25.291, -75.926};
        /** M114 reports the B step count ({@code Count ... B:<n>}), as the bench's Marlin does. */
        public boolean reportCount = true;
        /** The physical screw angle at count 0 (steps); null = drawn per connection from the seed. */
        public Integer offsetSteps;
        /** Every motor-off (M18/M84 of B, the idle timeout, a power cycle) draws a new screw angle (INF-06 §4). */
        public boolean newPhaseOnMotorOff;
        /** Only whole net turns lay paste; other contacts lay nothing, counted (sub-turns not modelled, INF-06). */
        public boolean wholeTurnsOnly;
        /**
         * INF-06 §2b (Process 11-05): a contact's mass by the contact history, not the angle ripple (SimPaste's
         * ContactHistory), a sub-turn model: wholeTurnsOnly does not apply. The screw pushes mRevMg / 3 on each live
         * quarter and nothing on the dead one from deadQuarterDeg (physical; the 25G at 13.7 psi: 226). Off: today's
         * law.
         */
        public boolean perContact;
        public double deadQuarterDeg = 226;
        /** Screw rotation in the air (deg) that makes the next contact hold its first quarter (unverified: 0 vs 90). */
        public double holdArmAirDeg = 45;
        /** Time in the air (s) that does the same; null: off (R1's B and R2b's b, Process 11-05). */
        public Double holdArmAirS;
        /** Whole turns a newly mounted tip takes before paste comes out (INF-06 §8); 0 = always full. */
        public double primeTurns;
        /** Contacts after a power cycle that come out empty (INF-06 §5: the 22G ~12). */
        public int emptyDotsAfterPowerCycle;
        /**
         * BR-74: the seed of the dose and thread noise on every connection (null: seed + the connection's number, as
         * before), so a scene's paste is the same however often the controller reconnected (a test alone in a fresh
         * session draws what it draws after the suite's reconnects).
         */
        public Long noiseSeed;
        /**
         * Per tip setup key (tip setups design §22.8, {@code "byTip"}): that tip's entry, any keys of this paste block,
         * laid over a copy of it ({@link SimScene#pasteFor}; INF-06); a tip without an entry uses the fields above.
         */
        public Map<String, JsonObject> byTip = new LinkedHashMap<>();

        /** A field-by-field copy (the per-tip overlays of {@link SimScene#pasteFor}). */
        public Paste copy() {
            Paste p = new Paste();
            p.mRevMg = mRevMg;
            p.ripple = ripple;
            p.ripplePeakDeg = ripplePeakDeg;
            p.cv = cv;
            p.seed = seed;
            p.footprintC = footprintC;
            p.footprintE = footprintE;
            p.transferRatio = transferRatio;
            p.oozeFirst = oozeFirst;
            p.refillDip = refillDip;
            p.refillTauDots = refillTauDots;
            p.idleFullS = idleFullS;
            p.idleDeadS = idleDeadS;
            p.threadShare = threadShare;
            p.threadFullMg = threadFullMg;
            p.threadZeroMg = threadZeroMg;
            p.missBelowMg = missBelowMg;
            p.maxGapMm = maxGapMm;
            p.needleZOffsetMm = needleZOffsetMm;
            p.feedLimitMgS = feedLimitMgS;
            p.landingSdMm = landingSdMm;
            p.landingBiasMm = landingBiasMm == null ? null : landingBiasMm.clone();
            p.n2OffsetMm = n2OffsetMm == null ? null : n2OffsetMm.clone();
            p.reportCount = reportCount;
            p.offsetSteps = offsetSteps;
            p.newPhaseOnMotorOff = newPhaseOnMotorOff;
            p.wholeTurnsOnly = wholeTurnsOnly;
            p.perContact = perContact;
            p.deadQuarterDeg = deadQuarterDeg;
            p.holdArmAirDeg = holdArmAirDeg;
            p.holdArmAirS = holdArmAirS;
            p.primeTurns = primeTurns;
            p.emptyDotsAfterPowerCycle = emptyDotsAfterPowerCycle;
            p.noiseSeed = noiseSeed;
            p.byTip = new LinkedHashMap<>(byTip);
            return p;
        }
    }

    /** A weigh coupon: a piece of blank PCB on a named place (machine mm, its top an N1 contact Z). */
    public static final class Coupon {
        public String place;
        /** x0, y0, x1, y1 (machine mm). */
        public double[] rect;
        public double z;
        public int[] bgr = {206, 25, 0};
        /** The bare coupon's mass on the balance. */
        public double massMg;

        public boolean contains(double x, double y) {
            return x >= rect[0] && y >= rect[1] && x <= rect[2] && y <= rect[3];
        }
    }

    /** The SIM balance (SimBalance): display = the operator reads it, connected = the job reads it. */
    public static final class Balance {
        public String mode = "display";
        public double resolutionMg = 1, noiseMg = 0.4, settleS = 2;
        public long seed = 5;
    }

    public static final String[] BALANCE_MODES = {"display", "connected"};

    /**
     * The limit switch on PA5 (the user, 11-04; lumen-sim's {@code pa5_switch}): N1 and the needle press it to measure
     * the needle's Z, {@code M43 P5} reads it (SimPa5Switch).
     */
    public static final class Pa5Switch {
        /** Where a tool presses it; the first window that holds the head wins. */
        public List<Pa5Press> presses;
        /** A press deeper than this past its trip point is reported once (nothing stops the head). */
        public double overtravelMm = 0.5;
        /** Each press trips at its window's tripZ + N(0, repeatSdMm), drawn from {@code seed}. */
        public double repeatSdMm;
        /** The pin's level while pressed (M43 P5's Input): 1, or 0 for a switch that pulls it low. */
        public int pressedLevel = 1;
        public long seed;

        public Pa5Switch(Pa5Press... presses) {
            this.presses = new ArrayList<>(Arrays.asList(presses));
        }
    }

    /** A window of the raw head X/Y (mm, edges included) where {@code tool} presses the switch at raw Z tripZ. */
    public static final class Pa5Press {
        /** n1 (presses it as Z falls to tripZ) or needle (as Z rises to it: the see-saw). */
        public String tool;
        public double x0, y0, x1, y1, tripZ;

        public Pa5Press(String tool, double x0, double y0, double x1, double y1, double tripZ) {
            this.tool = tool;
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.tripZ = tripZ;
        }
    }

    public static final String[] PA5_TOOLS = {"n1", "needle"};

    /** Today's blank colour (BGR, the board mask's); padcal scenes use the bench blank (206, 25, 0). */
    public static final int[] DEFAULT_BLANK_BGR = {142, 59, 0};

    /** Matched against the end of each board path of the configuration. */
    public String boardFile;
    /**
     * Board width/height (mm); 0 when the scene does not say (then taken from the board, see
     * {@link #withBoardSize}). While unknown, nothing lies on the board: no seal, no mask.
     */
    public double boardWidthMm, boardHeightMm;
    /** The true board→machine transform; the board origin lands at its translation. */
    public AffineTransform seat;
    /** N1 contact Z on the board, in board mm from the board origin ({@link #boardContactZ}). */
    public Plane boardSurface = new Plane(5.43, -0.0006, 0.0009);
    /** From the scene, or null: then the renderer takes them from the board's Fiducial placements. */
    public List<Fiducial> fiducials;
    public Set<String> hideFiducials = new HashSet<>();
    /** Pad ids ("R12.1") or placement ids ("R12") drawn with paste. */
    public Set<String> dirtyPads = new HashSet<>();
    /** Paste dots of 1.0 mm, board mm. */
    public List<double[]> dirtySpots = new ArrayList<>();
    public List<Spot> leakySpots = new ArrayList<>();
    /** Blank rectangle, machine mm: x0, y0, x1, y1; null = no blank. */
    public double[] blankRect = {280, 225, 365, 250};
    /**
     * N1 contact Z on the blank, in machine mm from the rectangle's first corner (x0, y0)
     * ({@link #blankContactZ}): the bench defaults (BLANK_PROBES) are fitted there.
     */
    public Plane blankSurface = new Plane(5.62, -0.0012, 0.0);
    /** Paste dots of 1.0 mm, machine mm. */
    public List<double[]> blankDots = new ArrayList<>();
    /** Contact everywhere else: below every probe floor, so off the board there is no contact. */
    public double bedZ = 2.5;
    /** N1 tip = raw head X/Y + this offset (tools/probe.py). */
    public double n1OffsetX = -24.178, n1OffsetY = -63.91;
    public double tipRadiusMm = 0.35;
    public Vacuum vacuum = new Vacuum();
    public Camera camera = new Camera();
    /** The blank's colour, BGR. */
    public int[] blankBgr = DEFAULT_BLANK_BGR.clone();
    /** Null: no paste model (the needle lays nothing, M114 has no Count). */
    public Paste paste;
    /**
     * The tip on the SIM head (tip setups design §22.8, {@code "physicalTip"}): a setup key; null = the mounted one.
     * A test can mount the 22G while the 25G is "on the head".
     */
    public String physicalTip;
    public List<Coupon> coupons = new ArrayList<>();
    /** Null: no SIM balance (the readings are typed). */
    public Balance balance;
    /** The PA5 switch; null: nothing wired (M43 P5 reads M42 P53's pull: 0 at boot, as the board did). */
    public Pa5Switch pa5Switch;
    /** The file this scene was read from; null for a scene built in code (SimPaste keys its deposits by it). */
    public File file;

    public static SimScene load(File f) throws IOException {
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        JsonElement e;
        try {
            e = new JsonParser().parse(text);
        }
        catch (RuntimeException ex) {
            throw new IOException("sim scene " + f + " is not valid JSON: " + ex.getMessage(), ex);
        }
        if (!e.isJsonObject()) {
            throw new IOException("sim scene " + f + " is not a JSON object");
        }
        try {
            SimScene s = of(e.getAsJsonObject());
            s.file = f;
            return s;
        }
        catch (RuntimeException ex) {
            throw new IOException("sim scene " + f + ": " + ex.getMessage(), ex);
        }
    }

    /** Parses the scene JSON (format of il_sim_scene.py). Throws IllegalArgumentException on a bad field. */
    public static SimScene of(JsonObject o) {
        int version = o.has("version") ? o.get("version").getAsInt() : 1;
        if (version != 1) {
            throw new IllegalArgumentException("sim scene version " + version + " is not supported (1)");
        }
        SimScene s = new SimScene();
        JsonObject b = obj(o, "board");
        if (b == null) {
            throw new IllegalArgumentException("sim scene has no board");
        }
        s.boardFile = b.has("file") ? b.get("file").getAsString() : null;
        double[] size = numbers(b, "size", 2);
        if (size != null) {
            s.boardWidthMm = size[0];
            s.boardHeightMm = size[1];
        }
        JsonObject seat = obj(b, "seat");
        if (seat == null) {
            throw new IllegalArgumentException("sim scene board has no seat");
        }
        s.seat = seatOf(seat);
        JsonObject surface = obj(b, "surface");
        if (surface != null) {
            s.boardSurface = planeOf(surface);
        }
        if (b.has("fiducials")) {
            s.fiducials = new ArrayList<>();
            for (JsonElement fe : b.getAsJsonArray("fiducials")) {
                JsonObject f = fe.getAsJsonObject();
                s.fiducials.add(new Fiducial(f.get("name").getAsString(), f.get("bx").getAsDouble(),
                        f.get("by").getAsDouble(), num(f, "d", 1.0), num(f, "mask", 3.0)));
            }
        }
        s.hideFiducials = strings(b, "hideFiducials");
        s.dirtyPads = strings(b, "dirtyPads");
        s.dirtySpots = points(b, "dirtySpots", "bx", "by");
        if (b.has("leakySpots")) {
            for (JsonElement le : b.getAsJsonArray("leakySpots")) {
                JsonObject l = le.getAsJsonObject();
                s.leakySpots.add(new Spot(l.get("bx").getAsDouble(), l.get("by").getAsDouble(), num(l, "r", 0.6)));
            }
        }
        if (o.has("blank") && o.get("blank").isJsonNull()) {
            s.blankRect = null;
        }
        JsonObject blank = obj(o, "blank");
        if (blank != null) {
            double[] rect = numbers(blank, "rect", 4);
            if (rect != null) {
                s.blankRect = new double[] {Math.min(rect[0], rect[2]), Math.min(rect[1], rect[3]),
                        Math.max(rect[0], rect[2]), Math.max(rect[1], rect[3])};
            }
            JsonObject bs = obj(blank, "surface");
            if (bs != null) {
                s.blankSurface = planeOf(bs);
            }
            s.blankDots = points(blank, "dots", "x", "y");
            int[] bgr = bgr(blank, "bgr");
            if (bgr != null) {
                s.blankBgr = bgr;
            }
        }
        s.bedZ = num(o, "bedZ", s.bedZ);
        double[] n1 = numbers(o, "n1OffsetMm", 2);
        if (n1 != null) {
            s.n1OffsetX = n1[0];
            s.n1OffsetY = n1[1];
        }
        s.tipRadiusMm = num(o, "tipRadiusMm", s.tipRadiusMm);
        JsonObject v = obj(o, "vacuum");
        if (v != null) {
            Vacuum d = s.vacuum;
            d.openAir = num(v, "openAir", d.openAir);
            if (v.has("sealDrops")) {
                JsonArray a = v.getAsJsonArray("sealDrops");
                if (a.size() == 0) {
                    throw new IllegalArgumentException("vacuum.sealDrops is empty");
                }
                d.sealDrops = new double[a.size()];
                for (int i = 0; i < a.size(); i++) {
                    d.sealDrops[i] = a.get(i).getAsDouble();
                }
            }
            d.partialBandMm = num(v, "partialBandMm", d.partialBandMm);
            d.leakDrop = num(v, "leakDrop", d.leakDrop);
            d.offValue = num(v, "offValue", d.offValue);
            d.tauS = num(v, "tauS", d.tauS);
            d.sealTauS = num(v, "sealTauS", d.sealTauS);
            d.releaseTauS = num(v, "releaseTauS", d.releaseTauS);
            d.noise = num(v, "noise", d.noise);
            d.seed = v.has("seed") ? v.get("seed").getAsLong() : d.seed;
            d.tip = v.has("tip") ? v.get("tip").getAsString() : d.tip;
        }
        JsonObject c = obj(o, "camera");
        if (c != null) {
            double[] err = numbers(c, "uppError", 2);
            if (err != null) {
                s.camera.uppError = err;
            }
            s.camera.noise = (int) num(c, "noise", s.camera.noise);
            s.camera.blurPx = (int) num(c, "blurPx", s.camera.blurPx);
        }
        JsonObject p = obj(o, "paste");
        if (p != null) {
            s.paste = pasteOf(p);
        }
        s.physicalTip = o.has("physicalTip") && !o.get("physicalTip").isJsonNull()
                ? o.get("physicalTip").getAsString() : null;
        if (o.has("coupons") && !o.get("coupons").isJsonNull()) {
            for (JsonElement ce : o.getAsJsonArray("coupons")) {
                JsonObject co = ce.getAsJsonObject();
                Coupon k = new Coupon();
                k.place = co.has("place") ? co.get("place").getAsString() : null;
                double[] r = numbers(co, "rect", 4);
                if (r != null) {
                    k.rect = new double[] {Math.min(r[0], r[2]), Math.min(r[1], r[3]), Math.max(r[0], r[2]),
                            Math.max(r[1], r[3])};
                }
                k.z = num(co, "z", Double.NaN);
                int[] bgr = bgr(co, "bgr");
                if (bgr != null) {
                    k.bgr = bgr;
                }
                k.massMg = num(co, "massMg", Double.NaN);
                s.coupons.add(k);
            }
        }
        JsonObject bal = obj(o, "balance");
        if (bal != null) {
            Balance d = new Balance();
            d.mode = bal.has("mode") ? bal.get("mode").getAsString() : d.mode;
            d.resolutionMg = num(bal, "resolutionMg", d.resolutionMg);
            d.noiseMg = num(bal, "noiseMg", d.noiseMg);
            d.settleS = num(bal, "settleS", d.settleS);
            d.seed = bal.has("seed") ? bal.get("seed").getAsLong() : d.seed;
            s.balance = d;
        }
        if (o.has("pa5Switch") && !o.get("pa5Switch").isJsonNull()) {
            s.pa5Switch = pa5SwitchOf(o.get("pa5Switch"));
        }
        s.validate();
        return s;
    }

    static final Set<String> PA5_KEYS = new LinkedHashSet<>(
            Arrays.asList("presses", "overtravelMm", "repeatSdMm", "pressedLevel", "seed"));
    static final Set<String> PA5_PRESS_KEYS = new LinkedHashSet<>(
            Arrays.asList("tool", "x0", "y0", "x1", "y1", "tripZ"));

    /**
     * The "pa5Switch" block: {presses: [{tool, x0, y0, x1, y1, tripZ}], overtravelMm, repeatSdMm, pressedLevel, seed},
     * lumen-sim's pa5_switch in the scene's names. An unknown key, a number given as text, a null, or a level or seed
     * written as no integer is refused, as lumen-sim refuses it: a typo would wire another switch than the test meant.
     */
    static Pa5Switch pa5SwitchOf(JsonElement e) {
        JsonObject o = e.isJsonObject() ? e.getAsJsonObject() : null;
        if (o == null || !o.has("presses") || !o.get("presses").isJsonArray()) {
            throw new IllegalArgumentException("pa5Switch takes presses (a list) and " + PA5_KEYS + ", not " + e);
        }
        only(o, PA5_KEYS, "pa5Switch");
        Pa5Switch w = new Pa5Switch();
        for (JsonElement pe : o.getAsJsonArray("presses")) {
            JsonObject p = pe.isJsonObject() ? pe.getAsJsonObject() : null;
            JsonElement tool = p == null ? null : p.get("tool");
            if (tool == null || !tool.isJsonPrimitive() || !tool.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("a pa5Switch press takes a tool (text) and x0, y0, x1, y1, tripZ, "
                        + "not " + pe);
            }
            only(p, PA5_PRESS_KEYS, "a pa5Switch press");
            w.presses.add(new Pa5Press(tool.getAsString(), finite(p, "x0", null), finite(p, "y0", null),
                    finite(p, "x1", null), finite(p, "y1", null), finite(p, "tripZ", null)));
        }
        w.overtravelMm = finite(o, "overtravelMm", w.overtravelMm);
        w.repeatSdMm = finite(o, "repeatSdMm", w.repeatSdMm);
        long level = whole(o, "pressedLevel", w.pressedLevel);
        if (level != 0 && level != 1) {
            throw new IllegalArgumentException("pressedLevel must be 0 or 1, not " + level);
        }
        w.pressedLevel = (int) level;
        w.seed = whole(o, "seed", 0);
        return w;
    }

    /** Refuses a key of {@code o} outside {@code keys}. */
    private static void only(JsonObject o, Set<String> keys, String what) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!keys.contains(e.getKey())) {
                throw new IllegalArgumentException(what + " takes " + keys + ", not " + e.getKey());
            }
        }
    }

    /**
     * A finite JSON number, not text (Gson would read "12" as 12) or null, as lumen-sim; missing: {@code dflt} (null:
     * required).
     */
    private static double finite(JsonObject o, String key, Double dflt) {
        JsonElement e = o.get(key);
        if (e == null && dflt != null) {
            return dflt;
        }
        double v = e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : Double.NaN;
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(key + " must be a finite number, not " + e);
        }
        return v;
    }

    /** A JSON integer, written without a fraction or exponent as lumen-sim's int; missing: {@code dflt}. */
    private static long whole(JsonObject o, String key, long dflt) {
        JsonElement e = o.get(key);
        if (e == null) {
            return dflt;
        }
        String v = e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsString() : "";
        try {
            if (v.matches("-?\\d+")) {
                return Long.parseLong(v);
            }
        }
        catch (NumberFormatException x) { // beyond a long: lumen-sim takes it, java.util.Random could not
        }
        throw new IllegalArgumentException(key + " must be an integer, not " + e);
    }

    /** Throws IllegalArgumentException (the message starting with {@code what}) for a paste the model cannot use. */
    static void check(Paste p, String what) {
        if (!(p.mRevMg > 0) || !(p.footprintC > 0) || !(p.footprintE > 0) || !(p.transferRatio > 0)
                || p.transferRatio > 1 || !(p.refillTauDots > 0) || !(p.idleFullS > 0) || !(p.feedLimitMgS > 0)) {
            throw new IllegalArgumentException(what + "mRevMg, footprint c and e, refillTauDots, idleFullS and "
                    + "feedLimitMgS must be > 0, transferRatio in (0, 1]");
        }
        if (p.ripple < 0 || p.cv < 0 || p.oozeFirst < 0 || p.refillDip < 0 || p.refillDip > 1 || p.idleDeadS < 0
                || p.primeTurns < 0 || p.emptyDotsAfterPowerCycle < 0
                || p.threadShare < 0 || p.threadShare > 1 || p.threadFullMg < 0
                || p.threadZeroMg < p.threadFullMg || p.missBelowMg < 0 || p.landingSdMm < 0) {
            throw new IllegalArgumentException(what + "negative ripple, cv, transient, idle, prime turns, empty dots, "
                    + "miss or landing sd, "
                    + "refillDip or threadShare above 1, or threadZeroMg below threadFullMg");
        }
        if (!(p.deadQuarterDeg >= 0 && p.deadQuarterDeg < 360) || !(p.holdArmAirDeg >= 0 && p.holdArmAirDeg <= 360)
                || (p.holdArmAirS != null && !(p.holdArmAirS >= 0))) {
            throw new IllegalArgumentException(what + "deadQuarterDeg must be in [0, 360), holdArmAirDeg in [0, 360], "
                    + "holdArmAirS >= 0 or null");
        }
        if (p.landingBiasMm == null || p.landingBiasMm.length != 2 || p.n2OffsetMm == null
                || p.n2OffsetMm.length != 2) {
            throw new IllegalArgumentException(what + "landingBiasMm and n2OffsetMm need 2 numbers");
        }
    }

    /** Throws IllegalArgumentException for values the models cannot use. */
    public void validate() {
        if (seat == null || Math.abs(seat.getDeterminant()) < 1e-6) {
            throw new IllegalArgumentException("board.seat is missing or not invertible");
        }
        if (!Arrays.asList(TIP_STATES).contains(vacuum.tip)) {
            throw new IllegalArgumentException("vacuum.tip '" + vacuum.tip + "' is not one of "
                    + String.join(", ", TIP_STATES));
        }
        if (vacuum.tauS < 0 || vacuum.sealTauS < 0 || vacuum.releaseTauS < 0 || vacuum.noise < 0
                || vacuum.partialBandMm < 0 || tipRadiusMm < 0 || camera.noise < 0 || camera.blurPx < 0) {
            throw new IllegalArgumentException("negative time constant, noise, band, tip radius or blur");
        }
        if (paste != null) {
            check(paste, "paste: ");
            for (Map.Entry<String, JsonObject> e : paste.byTip.entrySet()) {
                String what = "paste.byTip['" + e.getKey() + "']: ";
                Paste t;
                try {
                    t = apply(e.getValue(), paste.copy());
                }
                catch (RuntimeException x) { // a wrong count or type in the entry
                    throw new IllegalArgumentException(what + x.getMessage(), x);
                }
                check(t, what);
            }
        }
        Set<String> places = new HashSet<>();
        for (Coupon k : coupons) {
            if (k.place == null || k.place.trim().isEmpty() || !places.add(k.place)) {
                throw new IllegalArgumentException("coupons: every coupon needs its own place name");
            }
            if (k.rect == null || !(k.rect[2] > k.rect[0]) || !(k.rect[3] > k.rect[1]) || !Double.isFinite(k.z)
                    || !(k.massMg > 0)) {
                throw new IllegalArgumentException("coupon " + k.place + ": needs a rect, a z and a massMg > 0");
            }
            checkBgr(k.bgr, "coupon " + k.place + ".bgr");
        }
        checkBgr(blankBgr, "blank.bgr");
        if (balance != null) {
            if (!Arrays.asList(BALANCE_MODES).contains(balance.mode)) {
                throw new IllegalArgumentException("balance.mode '" + balance.mode + "' is not one of "
                        + String.join(", ", BALANCE_MODES));
            }
            if (!(balance.resolutionMg > 0) || balance.noiseMg < 0 || balance.settleS < 0) {
                throw new IllegalArgumentException("balance: resolutionMg must be > 0, noiseMg and settleS >= 0");
            }
        }
        if (pa5Switch != null) {
            if (pa5Switch.presses == null || pa5Switch.presses.isEmpty()) {
                throw new IllegalArgumentException("pa5Switch needs a press");
            }
            for (Pa5Press p : pa5Switch.presses) {
                if (p == null || !Arrays.asList(PA5_TOOLS).contains(p.tool)
                        || !DoubleStream.of(p.x0, p.y0, p.x1, p.y1, p.tripZ).allMatch(Double::isFinite)
                        || p.x0 > p.x1 || p.y0 > p.y1) {
                    throw new IllegalArgumentException("pa5Switch: a press needs a tool (" + String.join(" or ",
                            PA5_TOOLS) + "), finite x0 <= x1, y0 <= y1 and tripZ");
                }
            }
            if (!(pa5Switch.overtravelMm >= 0 && pa5Switch.overtravelMm <= 10 && pa5Switch.repeatSdMm >= 0
                    && pa5Switch.repeatSdMm <= 1) || pa5Switch.pressedLevel != 0 && pa5Switch.pressedLevel != 1) {
                throw new IllegalArgumentException("pa5Switch: overtravelMm must be 0-10 and repeatSdMm 0-1 (mm), "
                        + "pressedLevel 0 or 1");
            }
        }
    }

    private static void checkBgr(int[] bgr, String what) {
        if (bgr == null || bgr.length != 3) {
            throw new IllegalArgumentException(what + " needs 3 numbers");
        }
        for (int v : bgr) {
            if (v < 0 || v > 255) {
                throw new IllegalArgumentException(what + " must be 0..255");
            }
        }
    }

    /** The paste block (§11.3); missing keys keep the defaults of {@link Paste}. */
    static Paste pasteOf(JsonObject o) {
        Paste p = apply(o, new Paste());
        JsonObject byTip = obj(o, "byTip");
        if (byTip != null) {
            for (Map.Entry<String, JsonElement> e : byTip.entrySet()) {
                if (e.getValue() != null && e.getValue().isJsonObject()) {
                    p.byTip.put(e.getKey(), e.getValue().getAsJsonObject());
                }
            }
        }
        return p;
    }

    /**
     * Sets on {@code p} the fields {@code o} names (a paste block, or a tip's entry laid over a copy of it); the others
     * keep their value. A null clears needleZOffsetMm, noiseSeed or offsetSteps.
     */
    static Paste apply(JsonObject o, Paste p) {
        p.mRevMg = num(o, "mRevMg", p.mRevMg);
        p.ripple = num(o, "ripple", p.ripple);
        p.ripplePeakDeg = num(o, "ripplePeakDeg", p.ripplePeakDeg);
        p.cv = num(o, "cv", p.cv);
        p.seed = o.has("seed") ? o.get("seed").getAsLong() : p.seed;
        JsonObject f = obj(o, "footprint");
        if (f != null) {
            p.footprintC = num(f, "c", p.footprintC);
            p.footprintE = num(f, "e", p.footprintE);
        }
        p.transferRatio = num(o, "transferRatio", p.transferRatio);
        p.oozeFirst = num(o, "oozeFirst", p.oozeFirst);
        p.refillDip = num(o, "refillDip", p.refillDip);
        p.refillTauDots = num(o, "refillTauDots", p.refillTauDots);
        p.idleFullS = num(o, "idleFullS", p.idleFullS);
        p.idleDeadS = num(o, "idleDeadS", p.idleDeadS);
        p.threadShare = num(o, "threadShare", p.threadShare);
        p.threadFullMg = num(o, "threadFullMg", p.threadFullMg);
        p.threadZeroMg = num(o, "threadZeroMg", p.threadZeroMg);
        p.missBelowMg = num(o, "missBelowMg", p.missBelowMg);
        p.maxGapMm = num(o, "maxGapMm", p.maxGapMm);
        if (o.has("needleZOffsetMm")) {
            p.needleZOffsetMm = o.get("needleZOffsetMm").isJsonNull() ? null : num(o, "needleZOffsetMm", 0);
        }
        p.feedLimitMgS = num(o, "feedLimitMgS", p.feedLimitMgS);
        p.landingSdMm = num(o, "landingSdMm", p.landingSdMm);
        double[] bias = numbers(o, "landingBiasMm", 2);
        if (bias != null) {
            p.landingBiasMm = bias;
        }
        double[] n2 = numbers(o, "n2OffsetMm", 2);
        if (n2 != null) {
            p.n2OffsetMm = n2;
        }
        p.reportCount = o.has("reportCount") ? o.get("reportCount").getAsBoolean() : p.reportCount;
        p.newPhaseOnMotorOff = flag(o, "newPhaseOnMotorOff", p.newPhaseOnMotorOff);
        p.wholeTurnsOnly = flag(o, "wholeTurnsOnly", p.wholeTurnsOnly);
        p.perContact = flag(o, "perContact", p.perContact);
        p.deadQuarterDeg = num(o, "deadQuarterDeg", p.deadQuarterDeg);
        p.holdArmAirDeg = num(o, "holdArmAirDeg", p.holdArmAirDeg);
        if (o.has("holdArmAirS")) {
            p.holdArmAirS = o.get("holdArmAirS").isJsonNull() ? null : num(o, "holdArmAirS", 0);
        }
        p.primeTurns = num(o, "primeTurns", p.primeTurns);
        p.emptyDotsAfterPowerCycle = integer(o, "emptyDotsAfterPowerCycle", p.emptyDotsAfterPowerCycle);
        if (o.has("noiseSeed")) {
            p.noiseSeed = o.get("noiseSeed").isJsonNull() ? null : Long.valueOf(o.get("noiseSeed").getAsLong());
        }
        if (o.has("offsetSteps")) {
            p.offsetSteps = o.get("offsetSteps").isJsonNull() ? null
                    : Integer.valueOf(Math.floorMod(o.get("offsetSteps").getAsInt(), SimPaste.STEPS_PER_REV));
        }
        return p;
    }

    /** A JSON true or false (Gson reads 1 or "yes" as false: a guard would switch off without a word). */
    static boolean flag(JsonObject o, String key, boolean def) {
        if (!o.has(key)) {
            return def;
        }
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(key + " must be true or false, not " + e);
        }
        return e.getAsBoolean();
    }

    /** A JSON integer, null keeping the default as for every number of the block (Gson's getAsInt makes 12.7 a 12
     * and reads "12" as 12: both refused). */
    static int integer(JsonObject o, String key, int def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            return def;
        }
        double v = e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : Double.NaN;
        if (v != Math.rint(v) || Math.abs(v) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " must be an integer, not " + e);
        }
        return (int) v;
    }

    /** Overlays of {@link #paste} per tip key, made once per scene ({@link #pasteFor}). */
    private final transient Map<String, Paste> overlays = new java.util.concurrent.ConcurrentHashMap<>();

    /** The scene file's stamp (file key, mtime, size) this scene was read from, by {@link Source}; null when built in
     * code. OpenPnP's simulator makes a new Source, so a new SimScene, on every connect: this tells versions apart. */
    public transient List<Object> fileVersion;

    /**
     * The paste of the SIM needle with the tip {@code tipKey} on the head (§22.8): the scene's paste with that tip's
     * {@code byTip} entry laid over it, or the paste itself when the tip has none; null without a paste block.
     */
    public Paste pasteFor(String tipKey) {
        Paste base = paste;
        if (base == null || tipKey == null || !base.byTip.containsKey(tipKey)) {
            return base;
        }
        return overlays.computeIfAbsent(tipKey, k -> apply(base.byTip.get(k), base.copy()));
    }

    /**
     * {@code {"affine": [[a, b, tx], [c, d, ty]]}}, or {@code {x, y, rotationDeg, scaleX, scaleY, shearDeg}}
     * = T(x, y)·R(rotation)·Shear·Scale (the decomposition of Utils2D.affineInfo; shear is an x-shear).
     */
    static AffineTransform seatOf(JsonObject seat) {
        if (seat.has("affine")) {
            JsonArray rows = seat.getAsJsonArray("affine");
            if (rows.size() != 2) {
                throw new IllegalArgumentException("seat.affine must have 2 rows");
            }
            double[] r0 = row(rows.get(0).getAsJsonArray());
            double[] r1 = row(rows.get(1).getAsJsonArray());
            return new AffineTransform(r0[0], r1[0], r0[1], r1[1], r0[2], r1[2]);
        }
        return seat(num(seat, "x", 0), num(seat, "y", 0), num(seat, "rotationDeg", 0), num(seat, "scaleX", 1),
                num(seat, "scaleY", 1), num(seat, "shearDeg", 0));
    }

    /** T(x, y)·R(rotationDeg)·Shear(shearDeg)·Scale(scaleX, scaleY), as il_sim_scene.py seat(). */
    public static AffineTransform seat(double x, double y, double rotationDeg, double scaleX, double scaleY,
            double shearDeg) {
        AffineTransform t = AffineTransform.getTranslateInstance(x, y);
        t.rotate(Math.toRadians(rotationDeg));
        t.shear(Math.tan(Math.toRadians(shearDeg)), 0);
        t.scale(scaleX, scaleY);
        return t;
    }

    /** Board mm → machine mm through the true seat. */
    public Point2D.Double toMachine(double bx, double by) {
        Point2D.Double p = new Point2D.Double();
        seat.transform(new Point2D.Double(bx, by), p);
        return p;
    }

    /** Machine mm → board mm through the inverse of the true seat. */
    public Point2D.Double toBoard(double x, double y) {
        Point2D.Double p = new Point2D.Double();
        try {
            seat.inverseTransform(new Point2D.Double(x, y), p);
        }
        catch (NoninvertibleTransformException e) {
            throw new IllegalStateException("board.seat is not invertible", e);
        }
        return p;
    }

    /** True if the board point lies on the board (its outline is the rectangle 0..width × 0..height). */
    public boolean onBoard(double bx, double by) {
        return bx >= 0 && by >= 0 && bx <= boardWidthMm && by <= boardHeightMm;
    }

    public boolean onBlank(double x, double y) {
        return blankRect != null && x >= blankRect[0] && y >= blankRect[1] && x <= blankRect[2]
                && y <= blankRect[3];
    }

    /** N1 contact Z at a board point. */
    public double boardContactZ(double bx, double by) {
        return boardSurface.at(bx, by);
    }

    /** N1 contact Z at a machine point of the blank. */
    public double blankContactZ(double x, double y) {
        return blankSurface.at(x - blankRect[0], y - blankRect[1]);
    }

    /** What lies under a machine point: the board (on top), the blank, or the bed. */
    public enum Region {
        BOARD, COUPON, BLANK, BED
    }

    /** The board on top, then a weigh coupon, then the blank, else the bed. */
    public Region regionAt(double x, double y) {
        Point2D.Double b = toBoard(x, y);
        if (onBoard(b.x, b.y)) {
            return Region.BOARD;
        }
        if (couponAt(x, y) != null) {
            return Region.COUPON;
        }
        return onBlank(x, y) ? Region.BLANK : Region.BED;
    }

    /** The weigh coupon under a machine point, or null. */
    public Coupon couponAt(double x, double y) {
        for (Coupon k : coupons) {
            if (k.contains(x, y)) {
                return k;
            }
        }
        return null;
    }

    /** The coupon on a named place, or null. */
    public Coupon coupon(String place) {
        for (Coupon k : coupons) {
            if (k.place.equals(place)) {
                return k;
            }
        }
        return null;
    }

    /** N1 contact Z at a machine point, on the given region. */
    public double contactZ(Region region, double x, double y) {
        switch (region) {
            case BOARD:
                Point2D.Double b = toBoard(x, y);
                return boardContactZ(b.x, b.y);
            case COUPON:
                Coupon k = couponAt(x, y);
                return k != null ? k.z : bedZ;
            case BLANK:
                return blankContactZ(x, y);
            default:
                return bedZ;
        }
    }

    /** The configuration's board whose file path ends with {@link #boardFile}, or null. */
    public Board findBoard(Configuration c) {
        if (c == null || boardFile == null || boardFile.isEmpty()) {
            return null;
        }
        String want = boardFile.replace('\\', '/');
        List<Board> boards;
        try {
            boards = c.getBoards();
        }
        catch (ConcurrentModificationException e) {
            return null; // a board is being loaded right now: the next call sees it
        }
        for (Board b : boards) {
            File f = b.getFile();
            if (f == null) {
                continue;
            }
            String path = f.getPath().replace('\\', '/');
            if (path.equals(want) || path.endsWith("/" + want)) {
                return b;
            }
        }
        return null;
    }

    /** True once the board outline is known (the scene's board.size, or filled from the board). */
    public boolean hasBoardSize() {
        return boardWidthMm > 0 && boardHeightMm > 0;
    }

    /** Fills a missing board size from the board's dimensions (the board file's {@code <dimensions>}). */
    public SimScene withBoardSize(Board board) {
        if (!hasBoardSize() && board != null) {
            Location d = board.getDimensions().convertToUnits(LengthUnit.Millimeters);
            boardWidthMm = d.getX();
            boardHeightMm = d.getY();
        }
        return this;
    }

    /** The scene's fiducials, or (when it has none) the board's. Hidden ones are left out. */
    public List<Fiducial> visibleFiducials(Board board) {
        List<Fiducial> all = fiducials != null ? fiducials : fiducialsOf(board);
        List<Fiducial> out = new ArrayList<>();
        for (Fiducial f : all) {
            if (!hideFiducials.contains(f.name)) {
                out.add(f);
            }
        }
        return out;
    }

    private static final Pattern FID_DIAMETER = Pattern.compile("(?i)fiducial[_-]?(\\d+(?:\\.\\d+)?)mm");
    private static final Pattern FID_MASK = Pattern.compile("(?i)mask(\\d+(?:\\.\\d+)?)mm");

    /**
     * The board's enabled top-side {@code Fiducial} placements: the diameter from the first round
     * footprint pad, else parsed from the package id ({@code Fiducial_1mm_Mask3mm}), else 1.0 mm; the
     * mask opening parsed, else 3.0 mm. il_sim_scene.py applies the same rule.
     */
    public static List<Fiducial> fiducialsOf(Board board) {
        List<Fiducial> out = new ArrayList<>();
        if (board == null) {
            return out;
        }
        for (Placement p : board.getPlacements()) {
            if (p.getType() != Placement.Type.Fiducial || p.getSide() != Side.Top || !p.isEnabled()) {
                continue;
            }
            Location l = p.getLocation().convertToUnits(LengthUnit.Millimeters);
            Package pkg = p.getPart() == null ? null : p.getPart().getPackage();
            String id = pkg != null ? pkg.getId() : (p.getPart() != null ? p.getPart().getId() : "");
            Double d = null;
            Footprint fp = pkg == null ? null : pkg.getFootprint();
            if (fp != null && fp.getPads() != null) {
                double f = new Length(1, fp.getUnits()).convertToUnits(LengthUnit.Millimeters).getValue();
                for (Footprint.Pad pad : fp.getPads()) {
                    if (pad.getRoundness() >= 100 && Math.abs(pad.getWidth() - pad.getHeight()) < 1e-9
                            && pad.getWidth() > 0) {
                        d = pad.getWidth() * f;
                        break;
                    }
                }
            }
            if (d == null) {
                d = parse(FID_DIAMETER, id, 1.0);
            }
            out.add(new Fiducial(p.getId(), l.getX(), l.getY(), d, parse(FID_MASK, id, 3.0)));
        }
        return out;
    }

    private static double parse(Pattern p, String s, double dflt) {
        Matcher m = p.matcher(s == null ? "" : s);
        return m.find() ? Double.parseDouble(m.group(1)) : dflt;
    }

    // --- JSON helpers -----------------------------------------------------------------------------

    private static JsonObject obj(JsonObject o, String name) {
        JsonElement e = o.get(name);
        return e == null || !e.isJsonObject() ? null : e.getAsJsonObject();
    }

    private static double num(JsonObject o, String name, double dflt) {
        JsonElement e = o.get(name);
        if (e == null || e.isJsonNull()) {
            return dflt;
        }
        double v = e.getAsDouble();
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(name + " is not a finite number");
        }
        return v;
    }

    private static double[] numbers(JsonObject o, String name, int n) {
        JsonElement e = o.get(name);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        double[] r = row(e.getAsJsonArray());
        if (r.length != n) {
            throw new IllegalArgumentException(name + " must have " + n + " numbers");
        }
        return r;
    }

    private static int[] bgr(JsonObject o, String name) {
        double[] v = numbers(o, name, 3);
        if (v == null) {
            return null;
        }
        return new int[] {(int) Math.round(v[0]), (int) Math.round(v[1]), (int) Math.round(v[2])};
    }

    private static double[] row(JsonArray a) {
        double[] r = new double[a.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = a.get(i).getAsDouble();
            if (!Double.isFinite(r[i])) {
                throw new IllegalArgumentException("not a finite number: " + a);
            }
        }
        return r;
    }

    private static Plane planeOf(JsonObject o) {
        return new Plane(num(o, "z0", 0), num(o, "slopeX", 0), num(o, "slopeY", 0));
    }

    private static Set<String> strings(JsonObject o, String name) {
        Set<String> out = new HashSet<>();
        if (o.has(name)) {
            for (JsonElement e : o.getAsJsonArray(name)) {
                out.add(e.getAsString());
            }
        }
        return out;
    }

    /** [{x, y}] or [[x, y]] → list of {x, y}. */
    private static List<double[]> points(JsonObject o, String name, String kx, String ky) {
        List<double[]> out = new ArrayList<>();
        if (!o.has(name)) {
            return out;
        }
        for (JsonElement e : o.getAsJsonArray(name)) {
            if (e.isJsonArray()) {
                double[] r = row(e.getAsJsonArray());
                if (r.length < 2) {
                    throw new IllegalArgumentException(name + ": a point needs x and y");
                }
                out.add(new double[] {r[0], r[1]});
            }
            else {
                JsonObject p = e.getAsJsonObject();
                out.add(new double[] {p.get(kx).getAsDouble(), p.get(ky).getAsDouble()});
            }
        }
        return out;
    }

    // --- the scene file of a running session ------------------------------------------------------

    /**
     * The scene file, read again when it changes (file key, mtime or size; checked at most every
     * 250 ms), so a test can move the board in a running session. A missing file gives null (the
     * simulator is off); a file that does not parse keeps the last good scene.
     */
    public static final class Source implements Supplier<SimScene> {
        static final long CHECK_MS = 250;
        private final File file;
        private final LongSupplier clockMs;
        private final Function<SimScene, Board> boardOf;
        private long checkedAtMs;
        private boolean checked;
        private List<Object> stamp = Collections.emptyList();
        private SimScene scene;

        /**
         * @param boardOf the scene's board, for a size the scene does not give (may return null)
         */
        public Source(File file, LongSupplier clockMs, Function<SimScene, Board> boardOf) {
            this.file = file;
            this.clockMs = clockMs;
            this.boardOf = boardOf;
        }

        /** For the running configuration: its scene file, the wall clock, its boards. */
        public static Source forConfiguration(Configuration c) {
            return new Source(SimGuard.sceneFile(c), System::currentTimeMillis,
                    (sc) -> sc.findBoard(Configuration.get()));
        }

        public File getFile() {
            return file;
        }

        @Override
        public synchronized SimScene get() {
            long now = clockMs.getAsLong();
            if (checked && now - checkedAtMs < CHECK_MS) {
                return scene;
            }
            checked = true;
            checkedAtMs = now;
            List<Object> st = stampOf(file);
            if (st == null) {
                scene = null;
                stamp = Collections.emptyList();
                return null;
            }
            if (scene != null && st.equals(stamp)) {
                if (!scene.hasBoardSize()) {
                    scene.withBoardSize(boardOf.apply(scene)); // boards load on demand, maybe after the first read
                }
                return scene;
            }
            try {
                SimScene s = load(file);
                scene = s.withBoardSize(boardOf.apply(s));
                scene.fileVersion = st;
                stamp = st;
            }
            catch (Exception e) {
                Logger.warn("sim scene {} not loaded, keeping the previous one: {}", file, e.getMessage());
            }
            return scene;
        }

        private static List<Object> stampOf(File f) {
            try {
                BasicFileAttributes a = Files.readAttributes(f.toPath(), BasicFileAttributes.class);
                List<Object> st = new ArrayList<>();
                st.add(Objects.toString(a.fileKey()));
                st.add(a.lastModifiedTime().toString());
                st.add(a.size());
                return st;
            }
            catch (IOException e) {
                return null;
            }
        }
    }
}
