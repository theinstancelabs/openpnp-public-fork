package org.openpnp.instancelabs.vision;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.openpnp.instancelabs.dispense.DispenseExecutor;
import org.openpnp.instancelabs.dispense.DotImager;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.util.Utils2D;

/**
 * Finds a board's fiducials with the top camera and fits the board-to-machine affine: the
 * centring loop of the bench's {@code tools/locate.py} around {@link FiducialDetector}, and the
 * fit of {@code tools/register.py}.
 *
 * <ul>
 * <li><b>Search.</b> Only marks within searchRadiusMm of a fiducial's expected position count, so
 * the radius the operator sets bounds how far a board may have moved. The frames tile that circle
 * ({@link #searchTiles}, at most {@link #MAX_SEARCH_FRAMES}), the one nearest the prediction first:
 * a prediction orders the frames, it never moves the bound or leaves part of the circle unseen. The
 * search stops at the first frame with a mark. A mark that centring carries past the bound is not
 * found either.</li>
 * <li><b>Centring.</b> The camera moves onto the mark until it sits less than
 * centringTolerancePx from the frame centre, at most centringIterations looks (the search frame
 * that found it is the first). Unlike locate.py, a mark that does not converge is a failure, not
 * a result.</li>
 * <li><b>Prediction.</b> Targets are visited nearest first. After the first is found, every
 * remaining prediction moves by its offset; after two, they come from the similarity of the
 * found ones (the affine from three on).</li>
 * <li><b>Fit.</b> {@code Utils2D.deriveAffineTransform}: exact affine for 3, least squares for
 * more, similarity for 2; checked against absolute limits and against this board's previous
 * registration.</li>
 * </ul>
 *
 * Failure texts are the REG texts the web page shows, in mm.
 */
public final class FiducialLocator {
    /** At most this many frames search for one fiducial. */
    public static final int MAX_SEARCH_FRAMES = 9;

    /** Camera centred on machine (x, y), light on. */
    public interface FrameSource {
        DotImager.Frame capture(double xMm, double yMm) throws Exception;
    }

    /** searchRadiusMm and minFiducials are persisted (fiducialCheck); the rest are constants. */
    public static final class Settings {
        public FiducialDetector.Settings detector = new FiducialDetector.Settings();
        public double searchRadiusMm = 8.0, centringRadiusMm = 1.0, centringTolerancePx = 3.0;
        public int centringIterations = 3;
        public int minFiducials = 2;
        public double maxScaleErrorPct = 1.0, maxShearDeg = 0.5, maxResidualMm = 0.05;
        /** Against the board's previous registration. */
        public double maxScaleChangePct = 0.15, maxShearChangeDeg = 0.15;

        /** Throws IllegalArgumentException if a field is out of range. */
        public void validate() {
            if (detector == null) {
                throw new IllegalArgumentException("detector settings are required");
            }
            detector.validate();
            if (!(searchRadiusMm > 0) || Double.isInfinite(searchRadiusMm) || !(centringRadiusMm > 0)
                    || Double.isInfinite(centringRadiusMm)) {
                throw new IllegalArgumentException("search and centring radii must be positive,"
                        + " got " + searchRadiusMm + ", " + centringRadiusMm);
            }
            if (!(centringTolerancePx > 0) || centringIterations < 1) {
                throw new IllegalArgumentException("centring needs a positive tolerance and at"
                        + " least one look, got " + centringTolerancePx + " px, "
                        + centringIterations);
            }
            if (minFiducials < 2) {
                throw new IllegalArgumentException("minFiducials must be >= 2, got " + minFiducials);
            }
            if (!(maxScaleErrorPct >= 0) || !(maxShearDeg >= 0) || !(maxResidualMm >= 0)
                    || !(maxScaleChangePct >= 0) || !(maxShearChangeDeg >= 0)) {
                throw new IllegalArgumentException("fit limits must be >= 0");
            }
        }
    }

    /** One fiducial: where it is on the board, and where the machine expects it. */
    public static final class Target {
        public String name;
        public double boardX, boardY, expectedX, expectedY, diameterMm;

        public Target() {}

        public Target(String name, double boardX, double boardY, double expectedX,
                double expectedY, double diameterMm) {
            this.name = name;
            this.boardX = boardX;
            this.boardY = boardY;
            this.expectedX = expectedX;
            this.expectedY = expectedY;
            this.diameterMm = diameterMm;
        }
    }

    /** A centred fiducial: machine x, y of its centre. */
    public static final class Found {
        public Target target;
        public double x, y;
        /**
         * Centring looks (1 = the search frame was already centred); search frames up to the one
         * that found it. Captures = frames + iterations - 1.
         */
        public int iterations, frames;
        public double lastOffsetPx, offsetFromExpectedMm;
        public FiducialDetector.Detection detection;
    }

    /** Board mm to machine mm. */
    public static final class Fit {
        public AffineTransform boardToMachine;
        /** affine-3 | affine-lsq-N | similarity-2 */
        public String method;
        public double rotationDeg, scaleX, scaleY, shearDeg, maxResidualMm;
        /** |fit(board_i) - found_i| in the order of the found list. */
        public double[] residualsMm;
        /** When this fit was measured, for the texts of a later check against it; may be null. */
        public Long atMs;

        /**
         * Describes a stored transform (e.g. a previous registration) for {@link #fit}: rotation,
         * scales and shear as {@code Utils2D.affineInfo}, shearDeg = atan(xShear).
         */
        public static Fit of(AffineTransform boardToMachine, String method, Long atMs) {
            Fit f = new Fit();
            f.boardToMachine = new AffineTransform(boardToMachine);
            f.method = method;
            f.atMs = atMs;
            Utils2D.AffineInfo ai = Utils2D.affineInfo(boardToMachine);
            f.rotationDeg = ai.rotationAngleDeg;
            f.scaleX = ai.xScale;
            f.scaleY = ai.yScale;
            f.shearDeg = Math.toDegrees(Math.atan(ai.xShear));
            return f;
        }

        boolean isSimilarity() {
            return method != null && method.startsWith("similarity");
        }
    }

    /** The check failed; the message is the REG text for a board file. */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        public static final String NOT_FOUND = "NOT_FOUND", AMBIGUOUS = "AMBIGUOUS",
                NOT_CENTRED = "NOT_CENTRED", FIT = "FIT", FEW = "FEW";
        static final String FILE_ENDING = ", then register again.";
        static final String JOB_ENDING =
                ", or run OpenPnP's fiducial check on this job board, then register again.";

        public final String code;
        /** The fiducial that failed (NOT_FOUND, AMBIGUOUS, NOT_CENTRED); null for FIT and FEW. */
        public final Target target;

        public Failure(String code, Target target, String message) {
            super(message);
            this.code = code;
            this.target = target;
        }

        /** The text for a job board: NOT_FOUND then also offers OpenPnP's own fiducial check. */
        public String messageFor(boolean jobBoard) {
            String m = getMessage();
            if (jobBoard && NOT_FOUND.equals(code) && m.endsWith(FILE_ENDING)) {
                return m.substring(0, m.length() - FILE_ENDING.length()) + JOB_ENDING;
            }
            return m;
        }
    }

    private final Settings s;
    private final FrameSource cam;
    private final BooleanSupplier aborted;
    private final Consumer<Found> onFound;
    private final Consumer<String> onStep;
    /** The camera's frame size (px) and scale (mm/px), from its first frame: the search tiles need them. */
    private int frameW, frameH;
    private double frameMmPerPxX, frameMmPerPxY;

    /**
     * onFound is told about each fiducial as it is centred; onStep gets the phase text before
     * each capture ("fiducials: FID2 (2 of 3)", "... · frame 2 of 3", "... · move 2"). Both may
     * be null.
     */
    public FiducialLocator(Settings s, FrameSource cam, BooleanSupplier aborted,
            Consumer<Found> onFound, Consumer<String> onStep) {
        this.s = s != null ? s : new Settings();
        this.s.validate();
        if (cam == null) {
            throw new IllegalArgumentException("a frame source is required");
        }
        this.cam = cam;
        this.aborted = aborted != null ? aborted : () -> false;
        this.onFound = onFound;
        this.onStep = onStep;
    }

    /** As {@link #locateAll(List, double, double)}, starting at the first target. */
    public List<Found> locateAll(List<Target> targets) throws Exception {
        if (targets == null || targets.isEmpty()) {
            return new ArrayList<>();
        }
        return locateAll(targets, targets.get(0).expectedX, targets.get(0).expectedY);
    }

    /**
     * Centres the camera on every target, nearest first from the camera at (fromX, fromY), and
     * returns them in the order of the list. Throws {@link Failure} for the first fiducial that
     * is not found, ambiguous or not centred, and DispenseExecutor.AbortedException when aborted
     * before a capture.
     */
    public List<Found> locateAll(List<Target> targets, double fromX, double fromY)
            throws Exception {
        int n = targets.size();
        double[][] pred = new double[n][];
        for (int i = 0; i < n; i++) {
            Target t = targets.get(i);
            if (t == null || !Double.isFinite(t.expectedX) || !Double.isFinite(t.expectedY)
                    || !Double.isFinite(t.boardX) || !Double.isFinite(t.boardY)) {
                throw new IllegalArgumentException("target " + i + " needs finite positions");
            }
            pred[i] = new double[] {t.expectedX, t.expectedY};
        }
        Found[] found = new Found[n];
        List<Found> order = new ArrayList<>();
        double atX = fromX;
        double atY = fromY;
        for (int k = 0; k < n; k++) {
            int next = -1;
            double bestD = Double.POSITIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                double d = Math.hypot(pred[i][0] - atX, pred[i][1] - atY);
                if (found[i] == null && d < bestD) {
                    next = i;
                    bestD = d;
                }
            }
            Target t = targets.get(next);
            String label = String.format(Locale.ROOT, "fiducials: %s (%d of %d)", t.name, k + 1, n);
            Found f = locate(t, pred[next][0], pred[next][1], label);
            found[next] = f;
            order.add(f);
            if (onFound != null) {
                onFound.accept(f);
            }
            atX = f.x;
            atY = f.y;
            repredict(targets, pred, found, order, next);
        }
        return new ArrayList<>(Arrays.asList(found));
    }

    /** Moves the remaining predictions after a find (translation, then similarity / affine). */
    private static void repredict(List<Target> targets, double[][] pred, Found[] found,
            List<Found> order, int last) {
        if (order.size() == 1) {
            double dx = found[last].x - pred[last][0];
            double dy = found[last].y - pred[last][1];
            for (int i = 0; i < pred.length; i++) {
                if (found[i] == null) {
                    pred[i][0] += dx;
                    pred[i][1] += dy;
                }
            }
            return;
        }
        AffineTransform t = derive(order);
        for (int i = 0; i < pred.length; i++) {
            if (found[i] == null) {
                Point2D p = t.transform(
                        new Point2D.Double(targets.get(i).boardX, targets.get(i).boardY), null);
                pred[i][0] = p.getX();
                pred[i][1] = p.getY();
            }
        }
    }

    /**
     * Search, then centre, one fiducial predicted at (px, py): marks within searchRadiusMm of the
     * target's expected position, the frames tiling that circle nearest the prediction first.
     */
    private Found locate(Target t, double px, double py, String label) throws Exception {
        double radius = s.searchRadiusMm;
        double ex = t.expectedX;
        double ey = t.expectedY;
        double dMm = t.diameterMm > 0 ? t.diameterMm : s.detector.diameterMm;
        FiducialDetector.Settings ds = s.detector.copy();
        ds.diameterMm = dMm;
        double margin = dMm * ds.maxDiameterFactor;
        int frames = 0;
        DotImager.Frame f;
        List<double[]> tiles;
        if (frameW > 0) {
            tiles = searchOrder(frameW, frameH, frameMmPerPxX, frameMmPerPxY, margin, radius, px - ex,
                    py - ey);
            f = capture(ex + tiles.get(0)[0], ey + tiles.get(0)[1], label);
        }
        else {
            // the camera's first frame (its size is not known before it): the expected position's tile
            f = capture(ex, ey, label);
            tiles = searchOrder(frameW, frameH, frameMmPerPxX, frameMmPerPxY, margin, radius, px - ex,
                    py - ey);
            tiles.removeIf(c -> c[0] == 0 && c[1] == 0);
            tiles.add(0, new double[] {0, 0});
        }
        frames++;
        FiducialDetector.Detection d = null;
        for (int k = 0; k < tiles.size(); k++) {
            if (k > 0) {
                f = capture(ex + tiles.get(k)[0], ey + tiles.get(k)[1],
                        label + " · frame " + (k + 1) + " of " + tiles.size());
                frames++;
            }
            d = FiducialDetector.detect(f.image, f.mmPerPxX, f.mmPerPxY, ex - f.xMm, ey - f.yMm,
                    radius, ds);
            if (d != null) {
                break;
            }
        }
        if (d == null) {
            throw notFound(t, radius, frames);
        }
        for (int it = 1;; it++) {
            if (d.isAmbiguous(ds.ambiguousScoreRatio)) {
                throw new Failure(Failure.AMBIGUOUS, t, String.format(Locale.ROOT,
                        "REG: another round mark near %s (within %.1f mm of X %.2f, Y %.2f): look"
                                + " with the camera there, then register again",
                        t.name, radius, ex, ey));
            }
            double fx = f.xMm + d.offsetXMm;
            double fy = f.yMm + d.offsetYMm;
            if (!(Math.hypot(fx - ex, fy - ey) <= radius)) {
                throw notFound(t, radius, frames + it - 1);
            }
            double offPx = Math.hypot(d.xPx - f.image.getWidth() / 2.0,
                    d.yPx - f.image.getHeight() / 2.0);
            if (offPx < s.centringTolerancePx) {
                Found r = new Found();
                r.target = t;
                r.x = fx;
                r.y = fy;
                r.iterations = it;
                r.frames = frames;
                r.lastOffsetPx = offPx;
                r.offsetFromExpectedMm = Math.hypot(fx - ex, fy - ey);
                r.detection = d;
                return r;
            }
            if (it >= s.centringIterations) {
                throw new Failure(Failure.NOT_CENTRED, t, String.format(Locale.ROOT,
                        "REG: %s did not hold still in the camera (still %.1f px off after %d"
                                + " moves): wipe %s (paste or dust on it?) and register again",
                        t.name, offPx, it, t.name));
            }
            f = capture(fx, fy, label + " · move " + (it + 1));
            d = FiducialDetector.detect(f.image, f.mmPerPxX, f.mmPerPxY, fx - f.xMm, fy - f.yMm,
                    s.centringRadiusMm, ds);
            if (d == null) {
                throw notFound(t, radius, frames + it);
            }
        }
    }

    private DotImager.Frame capture(double x, double y, String step) throws Exception {
        if (aborted.getAsBoolean()) {
            throw new DispenseExecutor.AbortedException("stopped during the fiducial check");
        }
        if (onStep != null) {
            onStep.accept(step);
        }
        DotImager.Frame f = cam.capture(x, y);
        if (f == null || f.image == null) {
            throw new Exception("the top camera returned no image at X "
                    + String.format(Locale.ROOT, "%.2f, Y %.2f", x, y));
        }
        if (frameW == 0) {
            frameW = f.image.getWidth();
            frameH = f.image.getHeight();
            frameMmPerPxX = f.mmPerPxX;
            frameMmPerPxY = f.mmPerPxY;
        }
        return f;
    }

    /**
     * A fiducial's search frames as offsets from its expected position: the tiles of its search
     * circle ({@link #searchTiles}), nearest the prediction (dx, dy from the expected position)
     * first, ties in the tiles' own order.
     */
    private static List<double[]> searchOrder(int w, int h, double mmPerPxX, double mmPerPxY,
            double marginMm, double searchRadiusMm, double dx, double dy) {
        List<double[]> tiles = searchTiles(w, h, mmPerPxX, mmPerPxY, marginMm, searchRadiusMm);
        tiles.sort(Comparator.comparingDouble(c -> Math.hypot(c[0] - dx, c[1] - dy)));
        return tiles;
    }

    /** Not found within the radius of the target's expected position. */
    private static Failure notFound(Target t, double radius, int frames) {
        return new Failure(Failure.NOT_FOUND, t, String.format(Locale.ROOT,
                "REG: %s not found within %.1f mm of X %.2f, Y %.2f (%d camera frame%s). Is %s"
                        + " clean and uncovered, and does the board sit roughly where it was"
                        + " planned? Check the board X/Y in the Board step (Use camera X/Y on the"
                        + " board origin)" + Failure.FILE_ENDING,
                t.name, radius, t.expectedX, t.expectedY, frames, frames == 1 ? "" : "s", t.name));
    }

    /**
     * Frame centres, as offsets from the search circle's centre (a fiducial's expected position),
     * that cover the circle: a grid of the usable half-frame hx = W mmPerPxX / 2 - margin,
     * hy = H mmPerPxY / 2 - margin (margin = the largest 2r ring radius, so every mark in a tile
     * can be scored), keeping the tiles that reach into the circle, nearest first (ties
     * counter-clockwise from +x), at most {@link #MAX_SEARCH_FRAMES}. The first is always (0, 0).
     */
    public static List<double[]> searchTiles(int w, int h, double mmPerPxX, double mmPerPxY,
            double marginMm, double searchRadiusMm) {
        List<double[]> all = allTiles(w, h, mmPerPxX, mmPerPxY, marginMm, searchRadiusMm);
        return new ArrayList<>(all.subList(0, Math.min(all.size(), MAX_SEARCH_FRAMES)));
    }

    /**
     * Frames a search radius needs at this camera size, before the cap of
     * {@link #MAX_SEARCH_FRAMES}: the check that a configured searchRadiusMm fits the camera.
     */
    public static int searchFrameCount(int w, int h, double mmPerPxX, double mmPerPxY,
            double marginMm, double searchRadiusMm) {
        return allTiles(w, h, mmPerPxX, mmPerPxY, marginMm, searchRadiusMm).size();
    }

    private static List<double[]> allTiles(int w, int h, double mmPerPxX, double mmPerPxY,
            double marginMm, double searchRadiusMm) {
        double hx = w * mmPerPxX / 2 - marginMm;
        double hy = h * mmPerPxY / 2 - marginMm;
        List<double[]> tiles = new ArrayList<>();
        tiles.add(new double[] {0, 0});
        if (!(hx > 0) || !(hy > 0) || !(searchRadiusMm > 0)) {
            return tiles;
        }
        int ni = (int) Math.ceil((searchRadiusMm + hx) / (2 * hx));
        int nj = (int) Math.ceil((searchRadiusMm + hy) / (2 * hy));
        List<double[]> more = new ArrayList<>();
        for (int j = -nj; j <= nj; j++) {
            for (int i = -ni; i <= ni; i++) {
                if (i == 0 && j == 0) {
                    continue;
                }
                double cx = 2 * i * hx;
                double cy = 2 * j * hy;
                double gapX = Math.max(0, Math.abs(cx) - hx);
                double gapY = Math.max(0, Math.abs(cy) - hy);
                if (Math.hypot(gapX, gapY) < searchRadiusMm) {
                    more.add(new double[] {cx, cy});
                }
            }
        }
        more.sort(Comparator.<double[]> comparingDouble(c -> Math.hypot(c[0], c[1]))
                .thenComparingDouble(c -> {
                    double a = Math.atan2(c[1], c[0]);
                    return a < 0 ? a + 2 * Math.PI : a;
                }));
        tiles.addAll(more);
        return tiles;
    }

    /**
     * Fits board -> machine to the found fiducials and checks it: |scale - 1| within
     * maxScaleErrorPct (the one scale for 2 points), |shear| within maxShearDeg (3+), every
     * residual within maxResidualMm (4+; zero by construction for 3), and against previous (may
     * be null): scales within maxScaleChangePct and shear within maxShearChangeDeg in the machine
     * frame ({@link #machineFrame}) when both are affine fits, the scale when both are
     * similarities. Throws Failure FEW or FIT; nothing is stored by this class.
     */
    public static Fit fit(List<Found> found, Fit previous, Settings s) throws Failure {
        if (s == null) {
            s = new Settings();
        }
        int n = found == null ? 0 : found.size();
        int need = Math.max(2, s.minFiducials);
        if (n < need) {
            String names = n == 0 ? "" : " (" + names(found) + ")";
            throw new Failure(Failure.FEW, null, String.format(Locale.ROOT,
                    "REG: %d fiducial%s found%s; Register board needs at least %d. Check the board"
                            + " X/Y in the Board step (Use camera X/Y on the board origin) and"
                            + " probe by hand (Do it by hand).",
                    n, n == 1 ? "" : "s", names, need));
        }
        Fit fit = new Fit();
        fit.boardToMachine = derive(found);
        fit.method = n == 2 ? "similarity-2" : n == 3 ? "affine-3" : "affine-lsq-" + n;
        Utils2D.AffineInfo ai = Utils2D.affineInfo(fit.boardToMachine);
        fit.rotationDeg = ai.rotationAngleDeg;
        fit.scaleX = ai.xScale;
        fit.scaleY = ai.yScale;
        fit.shearDeg = Math.toDegrees(Math.atan(ai.xShear));
        fit.residualsMm = new double[n];
        for (int i = 0; i < n; i++) {
            Found f = found.get(i);
            Point2D p = fit.boardToMachine.transform(
                    new Point2D.Double(f.target.boardX, f.target.boardY), null);
            fit.residualsMm[i] = Math.hypot(p.getX() - f.x, p.getY() - f.y);
            fit.maxResidualMm = Math.max(fit.maxResidualMm, fit.residualsMm[i]);
        }
        double scaleLimit = s.maxScaleErrorPct / 100;
        boolean bad = !(Math.abs(fit.scaleX - 1) <= scaleLimit)
                || !(Math.abs(fit.scaleY - 1) <= scaleLimit);
        if (n >= 3) {
            bad |= !(Math.abs(fit.shearDeg) <= s.maxShearDeg);
        }
        if (bad) {
            int[] p = worstPair(found, null);
            Found a = found.get(p[0]);
            Found b = found.get(p[1]);
            throw new Failure(Failure.FIT, null, String.format(Locale.ROOT,
                    "REG: the fiducials do not fit the board: %s to %s measure %.2f mm, the board"
                            + " file says %.2f mm. One was probably found on the wrong mark: look"
                            + " with the camera at %s and %s, then register again.",
                    a.target.name, b.target.name, measured(a, b), boardDistance(a, b, null),
                    a.target.name, b.target.name));
        }
        if (n >= 4) {
            int worst = 0;
            for (int i = 1; i < n; i++) {
                if (!(fit.residualsMm[i] <= fit.residualsMm[worst])) {
                    worst = i;
                }
            }
            if (!(fit.residualsMm[worst] <= s.maxResidualMm)) {
                throw new Failure(Failure.FIT, null, String.format(Locale.ROOT,
                        "REG: %s is %.2f mm off where the other fiducials put it (limit %.2f mm):"
                                + " it was probably found on the wrong mark",
                        found.get(worst).target.name, fit.residualsMm[worst], s.maxResidualMm));
            }
        }
        if (previous != null && changedTooMuch(fit, previous, s)) {
            int[] p = worstPair(found, previous.boardToMachine);
            Found a = found.get(p[0]);
            Found b = found.get(p[1]);
            String then = previous.boardToMachine == null ? ""
                    : String.format(Locale.ROOT, ", %.2f mm then",
                            boardDistance(a, b, previous.boardToMachine));
            throw new Failure(Failure.FIT, null, String.format(Locale.ROOT,
                    "REG: the fiducials do not fit this board's last registration%s: %s to %s"
                            + " measure %.2f mm%s. One was probably found on the wrong mark: look"
                            + " with the camera, then register again.",
                    previous.atMs == null ? "" : " (" + clock(previous.atMs) + ")", a.target.name,
                    b.target.name, measured(a, b), then));
        }
        return fit;
    }

    private static boolean changedTooMuch(Fit fit, Fit prev, Settings s) {
        double limit = s.maxScaleChangePct / 100;
        if (fit.isSimilarity() && prev.isSimilarity()) {
            return !(Math.abs(fit.scaleX - prev.scaleX) <= limit);
        }
        if (fit.isSimilarity() || prev.isSimilarity()) {
            // A similarity has one scale, an affine two: they cannot be compared.
            return false;
        }
        // in the machine frame: the board-frame scales and shear turn with the seat
        Fit a = machineFrame(fit.boardToMachine);
        Fit b = machineFrame(prev.boardToMachine);
        return !(Math.abs(a.scaleX - b.scaleX) <= limit)
                || !(Math.abs(a.scaleY - b.scaleY) <= limit)
                || !(Math.abs(a.shearDeg - b.shearDeg) <= s.maxShearChangeDeg);
    }

    /**
     * The machine-frame part of a board-to-machine affine: the stretch P of its linear part
     * L = P Q (P = sqrt(L Lᵀ), symmetric; Q a rotation), as the scales and shear of {@link Fit#of}.
     * The machine's own scales and skew (bench: X 1.0011, Y 0.9947, shear -0.27 deg) lie in the
     * machine frame, so P stays when the same board is seated at another angle; the board-frame
     * scales and shear of Utils2D.affineInfo turn with the seat (about 0.014 deg of shear per deg).
     */
    static Fit machineFrame(AffineTransform t) {
        double a = t.getScaleX();
        double b = t.getShearX();
        double c = t.getShearY();
        double d = t.getScaleY();
        double s00 = a * a + b * b;
        double s01 = a * c + b * d;
        double s11 = c * c + d * d;
        double det = Math.abs(a * d - b * c);
        double tr = Math.sqrt(s00 + s11 + 2 * det);
        return Fit.of(new AffineTransform((s00 + det) / tr, s01 / tr, s01 / tr, (s11 + det) / tr, 0, 0), null,
                null);
    }

    /**
     * The pair whose measured distance disagrees most with the board file (reference null) or
     * with the reference transform's distance.
     */
    private static int[] worstPair(List<Found> found, AffineTransform reference) {
        int[] best = {0, 1};
        double worst = -1;
        for (int i = 0; i < found.size(); i++) {
            for (int j = i + 1; j < found.size(); j++) {
                Found a = found.get(i);
                Found b = found.get(j);
                double e = Math.abs(measured(a, b) - boardDistance(a, b, reference));
                if (e > worst) {
                    worst = e;
                    best = new int[] {i, j};
                }
            }
        }
        return best;
    }

    private static double measured(Found a, Found b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    private static double boardDistance(Found a, Found b, AffineTransform t) {
        Point2D pa = new Point2D.Double(a.target.boardX, a.target.boardY);
        Point2D pb = new Point2D.Double(b.target.boardX, b.target.boardY);
        if (t != null) {
            pa = t.transform(pa, null);
            pb = t.transform(pb, null);
        }
        return pa.distance(pb);
    }

    private static AffineTransform derive(List<Found> found) {
        List<Location> board = new ArrayList<>();
        List<Location> machine = new ArrayList<>();
        for (Found f : found) {
            board.add(new Location(LengthUnit.Millimeters, f.target.boardX, f.target.boardY, 0, 0));
            machine.add(new Location(LengthUnit.Millimeters, f.x, f.y, 0, 0));
        }
        return Utils2D.deriveAffineTransform(board, machine);
    }

    private static String names(List<Found> found) {
        StringBuilder b = new StringBuilder();
        for (Found f : found) {
            b.append(b.length() == 0 ? "" : ", ").append(f.target.name);
        }
        return b.toString();
    }

    private static String clock(long atMs) {
        return DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(atMs));
    }
}
