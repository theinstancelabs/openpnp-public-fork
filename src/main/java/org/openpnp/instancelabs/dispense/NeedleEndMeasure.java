package org.openpnp.instancelabs.dispense;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.opencv.core.Mat;
import org.openpnp.instancelabs.vision.EndFromBelow;
import org.openpnp.instancelabs.vision.TipClean;

/**
 * BR-94: the needle's end from below and K by parallax, Process's method as one engine job (runs/p37/bin/np1.py,
 * tools/parallax.py, parallax_fit.py and parallax_k.py; the clean check tipclean.py). N1 at each of its raw Z heights,
 * then the needle at each of its own, over the bottom camera: every X/Y move between the tools at travel Z, to the tool's
 * B (its tip, or the needle's nominal end, over the camera) approached from (−a, −a), then Z to the height; at each
 * height a frame at B, B ± (d, 0), B ± (0, d) and B again, each approached from (−a, −a), after M400 and a dwell. The
 * needle's end is np1.py's top-hat spot, N1 is followed by parallax_fit.py's template; the fit of each pixel against
 * the head's step gives M at that height. The end = the nominal end + M^-1 (spot − the camera's pixel), its line over
 * the heights the tilt; K = the needle's raw Z + N1's where both are the same distance from the camera. Travel Z at the
 * end, also after a refusal and a failure; a STOP's abort ends it where it is. The needle's valve and then its pump are
 * switched off before the first move, as for every height-switch measure.
 *
 * <p>Refused at the first check that fails, with its frame and the region searched drawn: at each height (a) the end's
 * top-hat peak stands out of its window, (b) the frames follow the head's steps (and moved with them), (c) the first and
 * last centre frames agree, (e) the end lies near where the tip setup puts it; then tipclean.py's check of one more
 * frame, the measure's last (the needle at the clean height over B): light, found or z failing is "the end was not
 * found (light or focus)" ({@link #NOT_FOUND}, measure again), spread or red failing is paste on the end
 * ({@link #PASTE}, wipe the tip and measure again); then (d) K's spread over N1's heights.</p>
 */
public final class NeedleEndMeasure {
    /** The refusal codes and what each offers. */
    public static final String NOT_FOUND = "needle-end-not-found", PASTE = "needle-end-paste";
    public static final String MEASURE_AGAIN = "measure-again", WIPE_THEN_MEASURE_AGAIN = "wipe-then-measure-again";
    /** np1.py's feeds (mm/min): Z up before X/Y, X/Y to B, Z down to a height, the steps, Z up at the end. */
    static final int F_UP = 1800, F_XY = 9000, F_DOWN = 600, F_STEP = 3000, F_LIFT = 1200;
    /** The head's steps in units of d: B, B + (d, 0), B − (d, 0), B + (0, d), B − (0, d), B. */
    static final double[][] STEPS = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0, 0}};

    private NeedleEndMeasure() {
    }

    /** The job's link: one line and its ok (the engine's gated executor); one lit frame of the bottom camera. */
    public interface Io {
        void send(String line) throws Exception;

        DotImager.Frame capture() throws Exception;

        /** Keeps a frame as taken (nothing drawn), at the store's best quality: its photo id, or null. */
        default Long keepRaw(BufferedImage frame, String caption, double mmPerPx) throws Exception {
            return null;
        }

        /** What the job does now, and the frames taken of all it plans (the task's progress). */
        default void progress(String phase, int framesDone, int framesPlanned) {
        }
    }

    /** One measure: raw X/Y of the head for each tool's B, travel Z (raw), the needle's nominal end, the settings. */
    public static final class Plan {
        public double n1X, n1Y, needleX, needleY, travelZ;
        /** Where the tip setup puts the end from the head (mm): N2's head offset + its landing + trim. */
        public double nominalDx, nominalDy;
        public DispenseConfig.NeedleEndSettings s;
        /** The mounted tip kind's clean-check limits and its key. */
        public TipClean.Limits clean;
        public String setupKey;
        /** N1's heights too, for K by parallax (false: a press whose window comes from K_switch, review Risk 3). */
        public boolean n1 = true;
    }

    /** A frame to keep: what was found drawn on it, its caption and scale (mm per px at the camera's plane). */
    public static final class Picture {
        public final BufferedImage image;
        public final String caption;
        public final double mmPerPx;

        Picture(BufferedImage image, String caption, double mmPerPx) {
            this.image = image;
            this.caption = caption;
            this.mmPerPx = mmPerPx;
        }
    }

    /** The end measured (ok), or the refusal: its code, reason, what it offers and the frame it shows. */
    public static final class Result {
        public boolean ok;
        public String code, why, next;
        /** A refusal's picture (the engine's photo id); the engine task that measured. */
        public Long photoId;
        public String runId;
        public DispenseConfig.NeedleEnd end;
        public DispenseConfig.CleanCheck clean;
        /**
         * Every frame as taken (review MINOR 4: Process re-runs its tools on them), in the order taken: N1's heights,
         * then the needle's, six each in the order of {@link NeedleEndMeasure#STEPS}, then the clean check's (null:
         * not kept).
         */
        public List<Long> framePhotoIds = new ArrayList<>();
        /** On success each height's centre frame, then the clean check's; on a refusal the one frame it failed in. */
        public transient List<Picture> pictures = new ArrayList<>();
    }

    /** A refusal of the job with its picture (the measure ends at travel Z); no code: the settings, nothing offered. */
    private static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        Refused(Result r, String code, String detail, BufferedImage frame, String caption, double mmPerPx) {
            super(detail);
            r.ok = false;
            r.code = code;
            r.why = code == null ? detail : PASTE.equals(code) ? "paste on the end: " + detail + ": wipe the tip, then "
                    + "measure again" : "the end was not found (light or focus): " + detail;
            r.next = code == null ? null : PASTE.equals(code) ? WIPE_THEN_MEASURE_AGAIN : MEASURE_AGAIN;
            r.pictures.clear();
            if (frame != null) {
                r.pictures.add(new Picture(frame, caption, mmPerPx));
            }
        }
    }

    /** One tool at one height: its frames' pixels and fit, and the centre frame drawn on. */
    private static final class Look {
        double rawZ;
        EndFromBelow.StepFit fit;
        double drift, minPeakToSd = Double.POSITIVE_INFINITY;
        double[] end;
        BufferedImage picture;
        double mmPerPx;
    }

    /**
     * The end on record while it is of the needle on (no change since: the needle token and the tip setup) and of the
     * bottom camera's calibration now (its location and centre pixel); else null. {@code cam}: the camera's location
     * {x, y, z}, null when there is none.
     */
    public static DispenseConfig.NeedleEnd current(DispenseConfig c, double[] cam) {
        DispenseConfig.NeedleEnd e = c.needleEnd;
        DispenseConfig.CameraCalibration k = e == null ? null : e.cameraCalibration;
        if (k == null || cam == null || !java.util.Objects.equals(e.needleSinceMs, c.needleChangedAtMs)
                || !java.util.Objects.equals(e.setupKey, c.tip.key)) {
            return null;
        }
        boolean moved = Math.abs(k.x - cam[0]) > 1e-6 || Math.abs(k.y - cam[1]) > 1e-6 || Math.abs(k.z - cam[2]) > 1e-6;
        return moved || !java.util.Objects.equals(k.centrePx, c.needleEndMeasure.centrePx) ? null : e;
    }

    /** Measures, then sends the head to travel Z (also after a refusal or a failure). */
    public static Result measure(Io io, Plan p) throws Exception {
        Result r = new Result();
        DispenseConfig.NeedleEndSettings s = p.s;
        int[] done = {0};
        List<Double> n1Z = p.n1 ? s.n1RawZ : new ArrayList<>();
        int planned = STEPS.length * (n1Z.size() + s.needleRawZ.size()) + 1;
        List<Look> n1 = new ArrayList<>(), nd = new ArrayList<>();
        Picture cleanPicture;
        try {
            // the passthrough may have left them on: valve, then pump, as STOP orders them, before anything moves
            io.send(ControllerCommands.VALVE_OFF);
            io.send(ControllerCommands.PUMP_OFF);
            for (int i = 0; i < n1Z.size(); i++) {
                n1.add(look(io, p, r, false, i, done, planned));
            }
            for (int i = 0; i < s.needleRawZ.size(); i++) {
                nd.add(look(io, p, r, true, i, done, planned));
            }
            cleanPicture = clean(io, p, r, nd, done, planned);
        }
        catch (Refused e) {
            lift(io, p);
            return r;
        }
        catch (DispenseExecutor.AbortedException e) {
            throw e; // a STOP: nothing moves after its abort
        }
        catch (Exception e) {
            try {
                lift(io, p);
            }
            catch (Exception x) {
                e.addSuppressed(x);
            }
            throw e;
        }
        lift(io, p);
        try {
            finish(p, r, n1, nd, cleanPicture);
        }
        catch (Refused e) {
            // r says why
        }
        return r;
    }

    /** Travel Z, Z only. */
    private static void lift(Io io, Plan p) throws Exception {
        io.send(z(p.travelZ, F_LIFT));
        io.send("M400");
    }

    private static String z(double z, int feed) {
        return String.format(Locale.ROOT, "G1 Z%.3f F%d", z, feed);
    }

    private static String xy(double x, double y, int feed) {
        return String.format(Locale.ROOT, "G1 X%.3f Y%.3f F%d", x, y, feed);
    }

    /**
     * Check (a) on one frame: the end's top-hat peak at least {@code minPeak} and at least {@code minPeakToSd} times
     * its window's sd (Process 11-05 18:xx: a faint, defocused spot passes the ratio alone).
     */
    static boolean brightEnough(double peak, double ratio, DispenseConfig.NeedleEndSettings s) {
        return peak >= s.minPeak && ratio >= s.minPeakToSd;
    }

    /** One tool at its {@code i}-th height: the moves, the six frames, their analysis and checks. */
    private static Look look(Io io, Plan p, Result r, boolean needle, int i, int[] done, int planned)
            throws Exception {
        DispenseConfig.NeedleEndSettings s = p.s;
        double bx = needle ? p.needleX : p.n1X, by = needle ? p.needleY : p.n1Y, a = s.approachMm;
        double d = needle ? s.needleStepMm : s.n1StepMm;
        List<Double> heights = needle ? s.needleRawZ : s.n1RawZ;
        Look l = new Look();
        l.rawZ = heights.get(i);
        String tool = needle ? "the needle" : "N1";
        io.progress(String.format(Locale.ROOT, "needle end: %s at raw Z %.3f (%d of %d)", tool, l.rawZ, i + 1,
                heights.size()), done[0], planned);
        if (i == 0) {
            // both tools up for every X/Y move between them: B approached from (−a, −a)
            io.send(z(p.travelZ, F_UP));
            io.send(xy(bx - a, by - a, F_XY));
            io.send(xy(bx, by, F_XY));
            io.send("M400");
        }
        io.send(z(l.rawZ, F_DOWN));
        io.send("M400");
        BufferedImage[] f = new BufferedImage[STEPS.length];
        double mmPerPx = 0;
        for (int k = 0; k < STEPS.length; k++) {
            DotImager.Frame fr = frame(io, p, r, bx + d * STEPS[k][0], by + d * STEPS[k][1], String.format(Locale.ROOT,
                    "As taken: %s at raw Z %.3f, B + (%.1f, %.1f) mm (%d of %d)", tool, l.rawZ, d * STEPS[k][0],
                    d * STEPS[k][1], k + 1, STEPS.length));
            f[k] = fr.image;
            mmPerPx = fr.mmPerPxX;
            io.progress(null, ++done[0], planned);
        }
        l.mmPerPx = mmPerPx;
        double[][] steps = new double[STEPS.length][];
        for (int k = 0; k < STEPS.length; k++) {
            steps[k] = new double[] {d * STEPS[k][0], d * STEPS[k][1]};
        }
        double bcx = s.centrePx.get(0), bcy = s.centrePx.get(1);
        String at = String.format(Locale.ROOT, "%s at raw Z %.3f", tool, l.rawZ);
        double[][] px = new double[STEPS.length][];
        if (needle) {
            if (!(mmPerPx > 0)) {
                throw new IllegalStateException("the bottom camera has no scale: OpenPnP's units per pixel");
            }
            int searchPx = (int) Math.round(s.searchMm / mmPerPx);
            EndFromBelow.Spot s0 = EndFromBelow.spot(f[0], bcx, bcy, searchPx, s.kernelPx);
            for (int k = 0; k < STEPS.length; k++) {
                EndFromBelow.Spot sk = k == 0 ? s0 : EndFromBelow.spot(f[k], s0.x, s0.y, s.followPx, s.kernelPx);
                double ratio = sk.backgroundSd > 0 ? sk.peak / sk.backgroundSd : sk.peak > 0
                        ? Double.POSITIVE_INFINITY : 0;
                if (!brightEnough(sk.peak, ratio, s)) {
                    boolean follow = k > 0;
                    BufferedImage pic = draw(f[k], g -> {
                        circle(g, Color.YELLOW, bcx, bcy, searchPx);
                        if (follow) {
                            circle(g, Color.CYAN, s0.x, s0.y, s.followPx);
                        }
                        cross(g, Color.RED, sk.x, sk.y);
                    });
                    throw new Refused(r, NOT_FOUND, String.format(Locale.ROOT, "%s, frame %d of %d: its top-hat peak "
                            + "%.0f (at least %.0f) is %.1f times its window's sd (at least %.1f)", at, k + 1,
                            STEPS.length, sk.peak, s.minPeak, ratio, s.minPeakToSd), pic,
                            "The end not found: " + at + ", frame " + (k + 1) + " (the search region drawn)", mmPerPx);
                }
                l.minPeakToSd = Math.min(l.minPeakToSd, ratio);
                px[k] = new double[] {sk.x, sk.y};
            }
            l.picture = draw(f[0], g -> {
                circle(g, Color.YELLOW, bcx, bcy, searchPx);
                circle(g, Color.CYAN, s0.x, s0.y, s.followPx);
                cross(g, Color.GREEN, s0.x, s0.y);
            });
        }
        else {
            int cx = (int) Math.round(bcx), cy = (int) Math.round(bcy);
            Mat t = EndFromBelow.template(f[0], cx, cy, s.n1TemplateHalfPx);
            try {
                for (int k = 0; k < STEPS.length; k++) {
                    EndFromBelow.Track tk = t == null ? null : EndFromBelow.track(f[k], t, cx, cy,
                            s.n1TemplateHalfPx, s.n1SearchPx);
                    if (tk == null || Double.isNaN(tk.x)) {
                        BufferedImage pic = draw(f[k], g -> box(g, Color.YELLOW, cx, cy, s.n1TemplateHalfPx
                                + s.n1SearchPx));
                        throw new Refused(r, NOT_FOUND, String.format(Locale.ROOT, "N1's template at raw Z %.3f does "
                                + "not fit inside the frame", l.rawZ), pic,
                                "N1 not found at raw Z " + l.rawZ, mmPerPx);
                    }
                    px[k] = new double[] {tk.x, tk.y};
                }
            }
            finally {
                if (t != null) {
                    t.release();
                }
            }
            l.picture = draw(f[0], g -> {
                box(g, Color.YELLOW, cx, cy, s.n1TemplateHalfPx + s.n1SearchPx);
                box(g, Color.GREEN, px[0][0], px[0][1], s.n1TemplateHalfPx);
            });
        }
        l.fit = EndFromBelow.fit(steps, px);
        l.drift = Math.hypot(px[5][0] - px[0][0], px[5][1] - px[0][1]);
        double moveX = d * Math.hypot(l.fit.m[0][0], l.fit.m[1][0]), moveY = d * Math.hypot(l.fit.m[0][1],
                l.fit.m[1][1]);
        String region = "(the frame searched drawn)";
        if (!(l.fit.residualPx <= s.maxResidualPx)) {
            throw new Refused(r, NOT_FOUND, String.format(Locale.ROOT, "%s: the frames do not follow the head's steps "
                    + "(residual %.2f px, at most %.2f)", at, l.fit.residualPx, s.maxResidualPx), l.picture,
                    "Not following the steps: " + at + " " + region, mmPerPx);
        }
        if (!(Math.min(moveX, moveY) > s.maxResidualPx)) {
            throw new Refused(r, NOT_FOUND, String.format(Locale.ROOT, "%s: what was found did not move with the head "
                    + "(a %.1f mm step moved it %.2f px): a mark or a reflection, not the tip", at, d, Math.min(moveX,
                            moveY)), l.picture, "Not moving with the head: " + at + " " + region, mmPerPx);
        }
        if (!(l.drift <= s.maxDriftPx)) {
            throw new Refused(r, NOT_FOUND, String.format(Locale.ROOT, "%s: the first and last centre frames are %.2f "
                    + "px apart (at most %.2f): the tip or the camera moved", at, l.drift, s.maxDriftPx), l.picture,
                    "Drift: " + at + " " + region, mmPerPx);
        }
        if (needle) {
            double[] off = l.fit.offsetFrom(bcx, bcy);
            l.end = new double[] {p.nominalDx + off[0], p.nominalDy + off[1]};
            double from = Math.hypot(off[0], off[1]);
            if (!(from <= s.maxFromNominalMm)) {
                throw new Refused(r, NOT_FOUND, String.format(Locale.ROOT, "%s: the end is %.2f mm from where the tip "
                        + "setup puts it (at most %.1f): another tip, a bent one, or not the end", at, from,
                        s.maxFromNominalMm), l.picture, "Far from the nominal end: " + at + " " + region, mmPerPx);
            }
        }
        return l;
    }

    /** One frame with the head at raw (x, y), approached from (−a, −a), after M400 and the dwell; kept as taken. */
    private static DotImager.Frame frame(Io io, Plan p, Result r, double x, double y, String caption)
            throws Exception {
        io.send(xy(x - p.s.approachMm, y - p.s.approachMm, F_STEP));
        io.send(xy(x, y, F_STEP));
        io.send("M400");
        io.send("G4 P" + p.s.settleMs);
        DotImager.Frame fr = io.capture();
        if (fr == null || fr.image == null) {
            throw new IllegalStateException("the bottom camera gave no frame");
        }
        r.framePhotoIds.add(io.keepRaw(fr.image, caption, fr.mmPerPxX));
        return fr;
    }

    /**
     * tipclean.py's check of one more frame, the measure's last (review MINOR 8: the nearest to the press): the needle
     * at the clean height over its B with that height's scale; refused by its first failing check. Its picture, or null
     * with no height at cleanRawZ (no verdict: {@link #finish} refuses).
     */
    private static Picture clean(Io io, Plan p, Result r, List<Look> nd, int[] done, int planned) throws Exception {
        DispenseConfig.NeedleEndSettings s = p.s;
        Look l = null;
        for (Look h : nd) {
            l = Math.abs(h.rawZ - s.cleanRawZ) < 1e-9 ? h : l;
        }
        if (l == null) {
            return null;
        }
        String at = String.format(Locale.ROOT, "the needle at raw Z %.3f", s.cleanRawZ);
        io.progress("needle end: the clean check, " + at, done[0], planned);
        io.send(z(s.cleanRawZ, F_DOWN));
        io.send("M400");
        DotImager.Frame fr = frame(io, p, r, p.needleX, p.needleY, "As taken: the clean check's frame, " + at
                + " over B");
        io.progress(null, ++done[0], planned);
        double bcx = s.centrePx.get(0), bcy = s.centrePx.get(1), k = l.fit.pxPerMm();
        TipClean.Result t = TipClean.check(fr.image, k, bcx, bcy, s.kernelPx, p.clean);
        DispenseConfig.CleanCheck c = new DispenseConfig.CleanCheck();
        c.ok = t.ok();
        c.failed = t.failed;
        c.why = t.why;
        c.rawZ = l.rawZ;
        c.k = k;
        c.rim = t.rim;
        c.rimRg = t.rimRg;
        c.peak = t.peak;
        c.spread = t.spread;
        c.red = t.red;
        c.limits = p.clean;
        c.setupKey = p.setupKey;
        c.atMs = System.currentTimeMillis();
        c.framePhotoId = r.framePhotoIds.get(r.framePhotoIds.size() - 1);
        r.clean = c;
        BufferedImage pic = draw(fr.image, g -> {
            circle(g, Color.WHITE, t.endX, t.endY, p.clean.endMm * k);
            circle(g, Color.WHITE, t.endX, t.endY, p.clean.annulusMm * k);
        });
        if (!t.ok()) {
            boolean paste = "spread".equals(t.failed) || "red".equals(t.failed);
            throw new Refused(r, paste ? PASTE : NOT_FOUND, t.why, pic,
                    (paste ? "Paste on the end: " : "The end not found: ") + at + " (the end's 0.5 and 1.0 mm drawn)",
                    fr.mmPerPxX);
        }
        return new Picture(pic, "The clean check's frame, the measure's last: " + at + ", clean (the end's 0.5 and 1.0 "
                + "mm drawn)", fr.mmPerPxX);
    }

    /** K, the tilt and the record, once every height passed; the clean check's picture last. */
    private static void finish(Plan p, Result r, List<Look> n1, List<Look> nd, Picture cleanPicture) throws Refused {
        DispenseConfig.NeedleEndSettings s = p.s;
        if (r.clean == null) {
            // review MAJOR 1: no end without a clean verdict (the needle's press needs one)
            throw new Refused(r, null, String.format(Locale.ROOT, "the clean check did not run: no needle height at its "
                    + "raw Z %.3f (needleEndMeasure.cleanRawZ must be one of needleRawZ)", s.cleanRawZ), null, null, 0);
        }
        double[] n1z = new double[n1.size()], n1k = new double[n1.size()], z = new double[nd.size()],
                k = new double[nd.size()], dx = new double[nd.size()], dy = new double[nd.size()];
        DispenseConfig.NeedleEnd e = new DispenseConfig.NeedleEnd();
        for (int i = 0; i < n1.size(); i++) {
            Look l = n1.get(i);
            n1z[i] = l.rawZ;
            n1k[i] = l.fit.pxPerMm();
            e.n1.add(height(l, null));
        }
        for (int i = 0; i < nd.size(); i++) {
            Look l = nd.get(i);
            z[i] = l.rawZ;
            k[i] = l.fit.pxPerMm();
            dx[i] = l.end[0];
            dy[i] = l.end[1];
            e.heights.add(height(l, l.end));
            e.errXyMm = Math.max(e.errXyMm, l.fit.residualPx / k[i]);
        }
        if (!n1.isEmpty()) {
            EndFromBelow.Parallax par = EndFromBelow.parallaxK(n1z, n1k, z, k);
            if (!(par.spread <= s.maxKSpreadMm)) {
                throw new Refused(r, NOT_FOUND, String.format(Locale.ROOT, "K by parallax spreads %.3f mm over N1's "
                        + "heights (at most %.2f): the scales do not agree", par.spread, s.maxKSpreadMm),
                        nd.get(0).picture, "K spread: the needle at raw Z " + nd.get(0).rawZ, nd.get(0).mmPerPx);
            }
            e.kParallax = par.k;
            e.errKMm = par.spread;
            e.fPx = par.fPx;
            e.needleMmPerRawMm = par.needleMmPerRawMm;
        }
        e.tiltXMmPerRawMm = EndFromBelow.line(z, dx)[0];
        e.tiltYMmPerRawMm = EndFromBelow.line(z, dy)[0];
        for (Look l : n1) {
            e.residualPx = Math.max(e.residualPx, l.fit.residualPx);
            e.driftPx = Math.max(e.driftPx, l.drift);
        }
        for (Look l : nd) {
            e.residualPx = Math.max(e.residualPx, l.fit.residualPx);
            e.driftPx = Math.max(e.driftPx, l.drift);
        }
        e.clean = r.clean;
        e.framePhotoIds = new ArrayList<>(r.framePhotoIds);
        e.setupKey = p.setupKey;
        r.end = e;
        r.ok = true;
        r.pictures.clear();
        for (Look l : nd) {
            r.pictures.add(new Picture(l.picture, String.format(Locale.ROOT, "The needle's end from below at raw Z "
                    + "%.3f: (%.3f, %.3f) mm from the head", l.rawZ, l.end[0], l.end[1]), l.mmPerPx));
        }
        for (Look l : n1) {
            r.pictures.add(new Picture(l.picture, String.format(Locale.ROOT, "N1 from below at raw Z %.3f: %.3f px/mm",
                    l.rawZ, l.fit.pxPerMm()), l.mmPerPx));
        }
        r.pictures.add(cleanPicture);
    }

    private static DispenseConfig.EndHeight height(Look l, double[] end) {
        DispenseConfig.EndHeight h = new DispenseConfig.EndHeight();
        h.rawZ = l.rawZ;
        h.dxMm = end == null ? null : end[0];
        h.dyMm = end == null ? null : end[1];
        h.pxPerMm = l.fit.pxPerMm();
        h.residualPx = l.fit.residualPx;
        h.driftPx = l.drift;
        h.peakToSd = end == null || Double.isInfinite(l.minPeakToSd) ? null : l.minPeakToSd;
        return h;
    }

    private interface Drawing {
        void on(Graphics2D g);
    }

    /** A copy of the frame with {@code d} drawn on it. */
    private static BufferedImage draw(BufferedImage frame, Drawing d) {
        BufferedImage c = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = c.createGraphics();
        g.drawImage(frame, 0, 0, null);
        g.setStroke(new BasicStroke(2));
        d.on(g);
        g.dispose();
        return c;
    }

    private static void circle(Graphics2D g, Color c, double x, double y, double r) {
        g.setColor(c);
        g.draw(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
    }

    private static void cross(Graphics2D g, Color c, double x, double y) {
        g.setColor(c);
        g.draw(new Line2D.Double(x - 12, y, x + 12, y));
        g.draw(new Line2D.Double(x, y - 12, x, y + 12));
    }

    private static void box(Graphics2D g, Color c, double x, double y, double half) {
        g.setColor(c);
        g.draw(new Rectangle2D.Double(x - half, y - half, 2 * half, 2 * half));
    }
}
