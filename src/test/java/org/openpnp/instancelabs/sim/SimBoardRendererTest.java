package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.PadGeometry;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;

/**
 * What the SIM top camera draws: colours where the board file puts its fiducials, pads and paste,
 * at the true seat, in the pixel convention of the vision code (offset = (px − W/2)·mm/px).
 */
public class SimBoardRendererTest {
    static final double UPP_X = 0.01068376, UPP_Y = 0.01072961; // the Top camera's (machine.xml)
    static final int W = 640, H = 480;

    @BeforeAll
    public static void configuration() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("sim-render").toFile();
        Configuration.initialize(dir);
        Configuration.get().load();
    }

    /** The ftp-like board re-seated by a shift, -1.6°, scale 1.002/0.997 and a 0.1° shear; fiducials from the board. */
    static SimScene scene(Board board) {
        SimScene s = new SimScene();
        s.boardFile = "ftp-like.board.xml";
        s.seat = SimScene.seat(296.2, 114.1, -1.6, 1.002, 0.997, 0.1);
        s.withBoardSize(board);
        return s;
    }

    static int[] bgr(BufferedImage img, int x, int y) {
        int rgb = img.getRGB(x, y);
        return new int[] {rgb & 0xFF, (rgb >> 8) & 0xFF, (rgb >> 16) & 0xFF};
    }

    static double grey(int[] bgr) {
        return 0.114 * bgr[0] + 0.587 * bgr[1] + 0.299 * bgr[2];
    }

    static void assertColour(Color expected, int[] bgr, int tolerance, String what) {
        int[] e = {expected.getBlue(), expected.getGreen(), expected.getRed()};
        for (int i = 0; i < 3; i++) {
            assertEquals(e[i], bgr[i], tolerance, what + ": BGR " + bgr[0] + "," + bgr[1] + "," + bgr[2]);
        }
    }

    /** The centre pixel of a frame centred on a machine point. */
    static int[] look(SimScene s, Board b, List<PadGeometry> pads, Point2D.Double at) {
        BufferedImage img = SimBoardRenderer.render(s, b, pads, at.x, at.y, W, H, UPP_X, UPP_Y);
        assertEquals(BufferedImage.TYPE_3BYTE_BGR, img.getType());
        return bgr(img, W / 2, H / 2);
    }

    /**
     * Weighted centre (pixel index coordinates) of the bright pixels within r px of (cx, cy), and the
     * copper area in pixels: the coverage (grey − opening) / (copper − opening), summed without
     * clipping so the noise cancels (r must stay inside the mask opening).
     */
    static double[] centroid(BufferedImage img, double cx, double cy, double r) {
        double copper = grey(new int[] {241, 240, 199});
        double opening = grey(new int[] {128, 47, 0});
        double sw = 0, sx = 0, sy = 0, n = 0;
        for (int y = (int) (cy - r); y <= cy + r; y++) {
            for (int x = (int) (cx - r); x <= cx + r; x++) {
                if (Math.hypot(x - cx, y - cy) > r) {
                    continue;
                }
                double g = grey(bgr(img, x, y));
                double w = Math.max(0, g - 100);
                sw += w;
                sx += w * x;
                sy += w * y;
                n += (g - opening) / (copper - opening);
            }
        }
        return new double[] {sx / sw, sy / sw, n};
    }

    @Test
    public void fiducialsPadsAndPasteWhereTheBoardPutsThem() {
        Board b = SimBoards.ftpLike();
        SimScene s = scene(b);
        s.dirtyPads.add("R2.1");
        s.dirtySpots.add(new double[] {30, 10});
        s.blankDots.add(new double[] {300, 230});
        List<PadGeometry> pads = SimBoardRenderer.boardPads(b, SimBoards.footprintsOnly());
        assertEquals(4, pads.size());
        int tol = 12; // noise ±4 and the 3 px blur
        Point2D.Double fid1 = s.toMachine(SimBoards.FTP_FIDUCIALS[0][0], SimBoards.FTP_FIDUCIALS[0][1]);
        assertColour(SimBoardRenderer.FIDUCIAL_COPPER, look(s, b, pads, fid1), tol, "FID1 centre");
        Point2D.Double ring = s.toMachine(SimBoards.FTP_FIDUCIALS[0][0] + 1.2, SimBoards.FTP_FIDUCIALS[0][1]);
        assertColour(SimBoardRenderer.FIDUCIAL_OPENING, look(s, b, pads, ring), tol, "1.2 mm from FID1");
        Point2D.Double mask = s.toMachine(SimBoards.FTP_FIDUCIALS[0][0] + 2.0, SimBoards.FTP_FIDUCIALS[0][1]);
        assertColour(SimBoardRenderer.MASK, look(s, b, pads, mask), tol, "2 mm from FID1");
        Point2D.Double r1 = s.toMachine(37.5 - SimBoards.PAD_X, 37.5);
        assertColour(SimBoardRenderer.PAD_COPPER, look(s, b, pads, r1), tol, "R1.1");
        Point2D.Double gap = s.toMachine(37.5, 37.5);
        assertColour(SimBoardRenderer.MASK, look(s, b, pads, gap), tol, "between R1's pads");
        // R2 is turned 90°: its pads lie along board Y; R2.1 carries paste.
        Point2D.Double r21 = s.toMachine(20, 50 - SimBoards.PAD_X);
        assertColour(SimBoardRenderer.PASTE, look(s, b, pads, r21), tol, "R2.1 (dirty)");
        Point2D.Double r22 = s.toMachine(20, 50 + SimBoards.PAD_X);
        assertColour(SimBoardRenderer.PAD_COPPER, look(s, b, pads, r22), tol, "R2.2");
        assertColour(SimBoardRenderer.PASTE, look(s, b, pads, s.toMachine(30, 10)), tol, "dirty spot");
        assertColour(SimBoardRenderer.BLANK, look(s, b, pads, new Point2D.Double(330, 240)), tol, "blank");
        assertColour(SimBoardRenderer.PASTE, look(s, b, pads, new Point2D.Double(300, 230)), tol, "blank dot");
        assertColour(SimBoardRenderer.OUTSIDE, look(s, b, pads, s.toMachine(-3, 30)), tol, "beside the board");
        s.hideFiducials.add("FID1");
        assertColour(SimBoardRenderer.MASK, look(s, b, pads, fid1), tol, "FID1 hidden");
    }

    @Test
    public void fiducialCentreFollowsThePixelConvention() {
        Board b = SimBoards.ftpLike();
        SimScene s = scene(b);
        s.camera.uppError = new double[] {0, 0.03}; // the bench's 3 % Y scale gap
        Point2D.Double fid2 = s.toMachine(SimBoards.FTP_FIDUCIALS[1][0], SimBoards.FTP_FIDUCIALS[1][1]);
        double camX = fid2.x + 0.4123;
        double camY = fid2.y - 0.2456;
        BufferedImage img = SimBoardRenderer.render(s, b, camX, camY, W, H, UPP_X, UPP_Y);
        double u = W / 2.0 + (fid2.x - camX) / UPP_X;
        double v = H / 2.0 - (fid2.y - camY) / (UPP_Y * 1.03);
        double[] c = centroid(img, u, v, 0.75 / UPP_X);
        assertEquals(u, c[0], 0.05, "x");
        assertEquals(v, c[1], 0.05, "y");
        // 1.0 mm disk: π/4 mm² in true pixels (Y pixels are 3 % larger).
        double areaMm2 = c[2] * UPP_X * UPP_Y * 1.03;
        assertEquals(Math.PI / 4, areaMm2, 0.03 * Math.PI / 4);
    }

    /** A board whose fiducials have no size in their package id: the round footprint pad gives it (KiCad import). */
    @Test
    public void kicadLikeBoardDrawsItsFootprintFiducials() {
        Board b = SimBoards.kicadLike();
        SimScene s = scene(b);
        assertEquals(40, s.boardWidthMm, 0);
        s.camera.noise = 0;
        Point2D.Double a = s.toMachine(3, 3);
        BufferedImage img = SimBoardRenderer.render(s, b, a.x, a.y, W, H, UPP_X, UPP_Y);
        double[] c = centroid(img, W / 2.0, H / 2.0, 0.9 / UPP_X);
        assertEquals(W / 2.0, c[0], 0.05);
        double areaMm2 = c[2] * UPP_X * UPP_Y;
        assertEquals(Math.PI / 4 * 1.2 * 1.2, areaMm2, 0.03 * Math.PI / 4 * 1.44, "1.2 mm footprint pad");
        Point2D.Double disabled = s.toMachine(37, 3);
        assertColour(SimBoardRenderer.MASK, look(s, b, SimBoardRenderer.boardPads(b, SimBoards.footprintsOnly()),
                disabled), 8, "a disabled fiducial is not drawn");
        // C1 turned 45°: both pads where the footprint puts them.
        List<PadGeometry> pads = SimBoardRenderer.boardPads(b, SimBoards.footprintsOnly());
        double d = SimBoards.PAD_X / Math.sqrt(2);
        assertColour(SimBoardRenderer.PAD_COPPER, look(s, b, pads, s.toMachine(20 + d, 15 + d)), 12, "C1.2");
        assertColour(SimBoardRenderer.PAD_COPPER, look(s, b, pads, s.toMachine(20 - d, 15 - d)), 12, "C1.1");
    }

    /** A scene without board.size: the mask outline comes from the board's dimensions. */
    @Test
    public void outlineFromTheBoardWhenTheSceneHasNoSize() {
        Board b = SimBoards.ftpLike();
        SimScene s = new SimScene();
        s.seat = SimScene.seat(296.2, 114.1, -1.6, 1.002, 0.997, 0.1);
        assertFalse(s.hasBoardSize());
        assertColour(SimBoardRenderer.MASK, look(s, b, List.of(), s.toMachine(10, 37.5)), 12, "mask");
        assertEquals(75, s.boardWidthMm, 0);
    }

    @Test
    public void onlyASmallStampInTheCorner() {
        Board b = SimBoards.ftpLike();
        SimScene s = scene(b);
        s.camera.noise = 0;
        s.camera.blurPx = 0;
        Point2D.Double away = s.toMachine(-20, -20);
        BufferedImage img = SimBoardRenderer.render(s, b, away.x, away.y, W, H, UPP_X, UPP_Y);
        int marked = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int[] p = bgr(img, x, y);
                if (p[0] != 40 || p[1] != 40 || p[2] != 40) {
                    assertTrue(x < 40 && y < 16, "a mark outside the stamp at " + x + "," + y);
                    marked++;
                }
            }
        }
        assertTrue(marked > 0, "the SIM stamp is drawn");
        // Far below the fiducial area window (0.45 mm², ~3900 px at this scale).
        assertTrue(marked < 400, String.valueOf(marked));
    }

    @Test
    public void unconfirmedFrameHasNoMarks() {
        BufferedImage img = SimBoardRenderer.blank(W, H, SimBoardCamera.UNCONFIRMED);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (y < 16 && x < 260) {
                    continue; // the stamp
                }
                assertColour(SimBoardRenderer.UNCONFIRMED, bgr(img, x, y), 0, x + "," + y);
            }
        }
    }

    @Test
    public void fullFrameRendersQuickly() {
        Board b = SimBoards.ftpLike();
        SimScene s = scene(b);
        Point2D.Double c = s.toMachine(37.5, 37.5);
        SimBoardRenderer.render(s, b, c.x, c.y, 1920, 1080, UPP_X, UPP_Y);
        long t0 = System.nanoTime();
        BufferedImage img = SimBoardRenderer.render(s, b, c.x, c.y, 1920, 1080, UPP_X, UPP_Y);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(1920, img.getWidth());
        assertTrue(ms < 2000, ms + " ms");
    }
}
