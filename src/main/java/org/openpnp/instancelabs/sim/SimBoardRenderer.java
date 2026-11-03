package org.openpnp.instancelabs.sim;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.DataBufferByte;
import java.awt.image.Kernel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.openpnp.instancelabs.dispense.BoardPadExtractor;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.PadGeometry;
import org.openpnp.instancelabs.dispense.PadTypeResolver;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.pmw.tinylog.Logger;

/**
 * Draws what the top camera sees of a {@link SimScene}: the board at its true seat (mask, pads,
 * fiducials, paste), the blank and the bed, in OpenPnP's image orientation (+x right, +y up).
 * Pixel (u, v), counted from pixel centres as the vision code does, shows machine
 * X = camX + (u − w/2)·uppX·(1 + uppError[0]), Y = camY − (v − h/2)·uppY·(1 + uppError[1]).
 *
 * <p>Fiducials come from the scene (il_sim_scene.py writes them from the board file's placements) or,
 * when the scene has none, from the board itself; pads from the board's placements and footprints /
 * pad types (BoardPadExtractor on the board placed at the origin). Nothing is specific to one board.</p>
 *
 * <p>The SIM needle's paste ({@link SimPaste}) is drawn on top, each dot, thread and smear as a
 * filled ellipse of its own size: on the blank and the weigh coupons in the bench's paste-on-blank
 * colour, on the board and the bed in the board paste colour. With the bench blank colour (the
 * scene's {@code blank.bgr} 206, 25, 0) DotInspector sees it (56 levels of blue).</p>
 */
public final class SimBoardRenderer {
    /** Colours, BGR measured on runs/board3_FID1_2.jpg (PadCleanCheck), as java.awt.Color(r, g, b). */
    static final Color MASK = bgr(142, 59, 0);
    static final Color FIDUCIAL_OPENING = bgr(128, 47, 0);
    static final Color FIDUCIAL_COPPER = bgr(241, 240, 199);
    static final Color PAD_COPPER = bgr(246, 244, 222);
    static final Color PASTE = bgr(160, 63, 13);
    /** Today's blank (the scene's default {@code blank.bgr}): 18 levels from PASTE, below DotInspector's 25. */
    static final Color BLANK = bgr(142, 59, 0);
    /**
     * The bench blank and paste on it, BGR at the frame centre of runs/p18_half/before_005.jpg,
     * after_005.jpg, before_006.jpg, after_006.jpg, before_020.jpg, after_020.jpg: blank (204-209,
     * 0-53, 0), paste (147-155, 36-44, 1).
     */
    static final Color BENCH_BLANK = bgr(206, 25, 0);
    static final Color BLANK_PASTE = bgr(150, 40, 0);
    static final Color OUTSIDE = bgr(40, 40, 40);
    static final Color UNCONFIRMED = bgr(128, 128, 128);
    static final double PASTE_DOT_MM = 1.0;
    static final String STAMP = "SIM";

    private SimBoardRenderer() {
    }

    static Color bgr(int b, int g, int r) {
        return new Color(r, g, b);
    }

    /** Pads from the dispense service's pad types when it runs, otherwise footprint pads only. */
    public static BufferedImage render(SimScene sc, Board board, double camX, double camY, int w, int h,
            double uppX, double uppY) {
        return render(sc, board, board == null ? Collections.emptyList() : boardPads(board, resolver()), camX,
                camY, w, h, uppX, uppY);
    }

    /** With the scene's SIM paste ({@link SimPaste#depositsFor}). */
    public static BufferedImage render(SimScene sc, Board board, List<PadGeometry> boardPads, double camX,
            double camY, int w, int h, double uppX, double uppY) {
        return render(sc, board, boardPads, camX, camY, w, h, uppX, uppY, SimPaste.depositsFor(sc));
    }

    /**
     * A board size the scene does not give is filled from the board ({@link SimScene#withBoardSize}).
     *
     * @param boardPads pads in board mm ({@link #boardPads})
     * @param deposits SIM paste in machine mm, drawn on top
     * @return a TYPE_3BYTE_BGR frame
     */
    public static BufferedImage render(SimScene sc, Board board, List<PadGeometry> boardPads, double camX,
            double camY, int w, int h, double uppX, double uppY, List<SimPaste.Deposit> deposits) {
        sc.withBoardSize(board);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(OUTSIDE);
            g.fillRect(0, 0, w, h);
            AffineTransform machine = machineToPixel(sc, camX, camY, w, h, uppX, uppY);
            AffineTransform boardTx = new AffineTransform(machine);
            boardTx.concatenate(sc.seat);
            if (sc.blankRect != null) {
                double[] r = sc.blankRect;
                fill(g, machine, new Rectangle2D.Double(r[0], r[1], r[2] - r[0], r[3] - r[1]), bgr(sc.blankBgr));
                for (double[] d : sc.blankDots) {
                    fill(g, machine, disk(d[0], d[1], PASTE_DOT_MM), PASTE);
                }
            }
            for (SimScene.Coupon k : sc.coupons) {
                double[] r = k.rect;
                fill(g, machine, new Rectangle2D.Double(r[0], r[1], r[2] - r[0], r[3] - r[1]), bgr(k.bgr));
            }
            if (sc.hasBoardSize()) {
                fill(g, boardTx, new Rectangle2D.Double(0, 0, sc.boardWidthMm, sc.boardHeightMm), MASK);
            }
            for (PadGeometry p : boardPads) {
                if (!(p.wMm > 0 && p.hMm > 0)) {
                    continue; // no geometry known: nothing to draw
                }
                AffineTransform t = new AffineTransform(boardTx);
                t.translate(p.xMm, p.yMm);
                t.rotate(Math.toRadians(p.rotationDeg));
                double arc = Math.max(0, Math.min(100, p.roundness)) / 100 * Math.min(p.wMm, p.hMm);
                fill(g, t, new RoundRectangle2D.Double(-p.wMm / 2, -p.hMm / 2, p.wMm, p.hMm, arc, arc), PAD_COPPER);
                if (sc.dirtyPads.contains(p.padId) || sc.dirtyPads.contains(p.placementId)) {
                    fill(g, t, disk(0, 0, Math.min(p.wMm, p.hMm)), PASTE);
                }
            }
            for (SimScene.Fiducial f : sc.visibleFiducials(board)) {
                fill(g, boardTx, disk(f.bx, f.by, f.mask), FIDUCIAL_OPENING);
                fill(g, boardTx, disk(f.bx, f.by, f.d), FIDUCIAL_COPPER);
            }
            for (double[] d : sc.dirtySpots) {
                fill(g, boardTx, disk(d[0], d[1], PASTE_DOT_MM), PASTE);
            }
            drawDeposits(g, machine, deposits, camX, camY, w * uppX * (1 + Math.abs(sc.camera.uppError[0])),
                    h * uppY * (1 + Math.abs(sc.camera.uppError[1])));
        }
        finally {
            g.dispose();
        }
        if (sc.camera.blurPx > 0) {
            img = blur(img, sc.camera.blurPx);
        }
        addNoise(img, sc.camera.noise, sc.vacuum.seed);
        stamp(img, STAMP, new Color(200, 200, 200));
        return img;
    }

    /** Deposits that reach into the frame (fwMm × fhMm about the camera), each in its surface's paste colour. */
    private static void drawDeposits(Graphics2D g, AffineTransform machine, List<SimPaste.Deposit> deposits,
            double camX, double camY, double fwMm, double fhMm) {
        for (SimPaste.Deposit d : deposits) {
            double reach = Math.max(d.aMm, d.bMm);
            if (Math.abs(d.x - camX) > fwMm / 2 + reach || Math.abs(d.y - camY) > fhMm / 2 + reach) {
                continue;
            }
            AffineTransform t = new AffineTransform(machine);
            t.translate(d.x, d.y);
            t.rotate(Math.toRadians(d.angleDeg));
            boolean onBlank = d.region == SimScene.Region.BLANK || d.region == SimScene.Region.COUPON;
            fill(g, t, new Ellipse2D.Double(-d.aMm, -d.bMm, 2 * d.aMm, 2 * d.bMm), onBlank ? BLANK_PASTE : PASTE);
        }
    }

    static Color bgr(int[] bgr) {
        return bgr(bgr[0], bgr[1], bgr[2]);
    }

    /** A uniform grey frame stamped {@code text}: no marks the vision code could take for anything. */
    public static BufferedImage blank(int w, int h, String text) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(UNCONFIRMED);
            g.fillRect(0, 0, w, h);
        }
        finally {
            g.dispose();
        }
        stamp(img, text, new Color(180, 180, 180));
        return img;
    }

    /** The board's pads in board mm: BoardPadExtractor on the board placed at the origin. */
    public static List<PadGeometry> boardPads(Board board, PadTypeResolver resolver) {
        try {
            BoardLocation bl = new BoardLocation(board);
            bl.setLocation(new Location(LengthUnit.Millimeters, 0, 0, 0, 0));
            return new BoardPadExtractor(resolver, true).extract(bl).pads;
        }
        catch (Exception e) {
            Logger.warn("sim camera: no pads for board {}: {}", board.getName(), e.getMessage());
            return new ArrayList<>();
        }
    }

    static PadTypeResolver resolver() {
        DispenseService svc = DispenseService.get();
        if (svc != null && svc.getDb() != null) {
            try {
                return svc.padTypeResolver(); // the mounted tip setup's view (§22.2)
            }
            catch (Exception e) {
                Logger.warn("sim camera: pad types not read, footprint pads only: {}", e.getMessage());
            }
        }
        return new PadTypeResolver(Collections.emptyList(), null);
    }

    /** Machine mm → Java2D pixel coordinates (pixel centres at +0.5). */
    static AffineTransform machineToPixel(SimScene sc, double camX, double camY, int w, int h, double uppX,
            double uppY) {
        double ux = uppX * (1 + sc.camera.uppError[0]);
        double uy = uppY * (1 + sc.camera.uppError[1]);
        AffineTransform t = AffineTransform.getTranslateInstance(w / 2.0 + 0.5, h / 2.0 + 0.5);
        t.scale(1 / ux, -1 / uy);
        t.translate(-camX, -camY);
        return t;
    }

    private static Shape disk(double x, double y, double d) {
        return new Ellipse2D.Double(x - d / 2, y - d / 2, d, d);
    }

    private static void fill(Graphics2D g, AffineTransform t, Shape s, Color c) {
        g.setColor(c);
        g.fill(t.createTransformedShape(s));
    }

    private static BufferedImage blur(BufferedImage img, int r) {
        int n = 2 * r + 1;
        float[] k = new float[n * n];
        Arrays.fill(k, 1f / (n * n));
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        return new ConvolveOp(new Kernel(n, n, k), ConvolveOp.EDGE_NO_OP, null).filter(img, out);
    }

    /** ±noise grey levels per pixel, the same on the three channels; seeded (xorshift), so a frame repeats. */
    private static void addNoise(BufferedImage img, int noise, long seed) {
        if (noise <= 0) {
            return;
        }
        byte[] px = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        long s = seed * 0x9E3779B97F4A7C15L | 1;
        int span = 2 * noise + 1;
        for (int i = 0; i + 2 < px.length; i += 3) {
            s ^= s << 13;
            s ^= s >>> 7;
            s ^= s << 17;
            int d = (int) Long.remainderUnsigned(s, span) - noise;
            for (int c = 0; c < 3; c++) {
                px[i + c] = (byte) Math.max(0, Math.min(255, (px[i + c] & 0xFF) + d));
            }
        }
    }

    /** Small text in the top-left corner (8 px glyphs), far from the frame centre and every probe look ring. */
    private static void stamp(BufferedImage img, String text, Color c) {
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(c);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            g.drawString(text, 4, 12);
        }
        finally {
            g.dispose();
        }
    }
}
