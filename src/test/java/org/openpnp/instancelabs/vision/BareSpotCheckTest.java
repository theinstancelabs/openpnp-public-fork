package org.openpnp.instancelabs.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.vision.BareSpotCheck.Result;
import org.openpnp.instancelabs.vision.BareSpotCheck.Settings;

/**
 * The camera look on bench frames (raw 1920 x 1080 at 0.01074 x 0.01106 mm/px), on the real
 * machine's frames of 2026-10-29 (real/README.md) and on Java2D frames. Each frame is printed with
 * the prefix "[bare-spot]" together with its margin to the defaults.
 */
public class BareSpotCheckTest {
    static final double SX = FidScene.BENCH_X, SY = FidScene.BENCH_Y;
    /** OpenPnP's Top camera units per pixel (the *_openpnp frames). */
    static final double OPENPNP_X = 0.01068, OPENPNP_Y = 0.01073;
    static final String BARE = "/org/openpnp/instancelabs/vision/bare/";
    static final String FID = "/org/openpnp/instancelabs/vision/fid/";
    static final String REAL = "/org/openpnp/instancelabs/vision/real/";

    static Result print(String label, Result r) {
        Settings s = new Settings();
        System.out.println(String.format(Locale.ROOT,
                "[bare-spot] %-46s share %5.1f %% (limit %.1f %%, x%.2f; raw %5.1f %%, wide"
                        + " %5.1f %%)  spread %5.1f (limit %.0f, x%.2f)  threshold %5.1f  %s",
                label, 100 * r.foreignShare, 100 * s.maxForeignShare,
                r.foreignShare / s.maxForeignShare, 100 * r.rawShare, 100 * r.wideShare,
                r.ringSpread, s.maxRingSpread, r.ringSpread / s.maxRingSpread, r.threshold,
                r.bare ? "bare" : "NOT BARE"));
        return r;
    }

    /** The look at raw pixel (x, y) of a bench frame: a 560 x 544 px cut centred there. */
    static Result look(String label, String path, int x, int y) throws Exception {
        BufferedImage img = FidScene.resource(path);
        return print(label, BareSpotCheck.check(img.getSubimage(x - 280, y - 272, 560, 544), SX,
                SY, null));
    }

    /** A real frame, cut +-3.5 mm around the camera's centre (real/README.md). */
    static Result real(String name) throws Exception {
        boolean openPnp = name.endsWith("_openpnp");
        BufferedImage img = FidScene.resource(REAL + name + ".jpg");
        return print(name, BareSpotCheck.check(img, openPnp ? OPENPNP_X : SX,
                openPnp ? OPENPNP_Y : SY, null));
    }

    /**
     * Bare surface. Looks centred on the spot, as the engine takes them, keep at least 2x margin
     * on the share; the board3 spots sit 2.9-3.0 mm from FID1 off the frame centre, in the ring
     * light's reflections, and only stay under the limit. Every spread keeps 1.3x.
     */
    @Test
    public void bareSpots() throws Exception {
        Object[][] spots = {
                {"bare1 centre (board mask)", BARE + "bare1.jpg", 960, 540},
                {"bare2 centre (silkscreen tip 0.7 mm away)", BARE + "bare2.jpg", 960, 540},
                {"board3_FID1_2 mask 3.0 mm above FID1", BARE + "board3_FID1_2.jpg", 960, 272},
                {"board3_FID1_2 mask 2.9 mm up-right of FID1", BARE + "board3_FID1_2.jpg", 1150, 350},
                {"board3_FID1_2 mask 2.9 mm up-left of FID1", BARE + "board3_FID1_2.jpg", 770, 350},
                {"reflow1_FID2_0 centre (3.2 mm from FID2)", BARE + "reflow1_FID2_0.jpg", 960, 540},
                {"reflow1_FID3_0 centre (board edge in ring)", FID + "reflow1_FID3_0.jpg", 960, 540},
                {"blk7_365 blank between dot rows", BARE + "blk7_365.jpg", 1060, 627}};
        Settings s = new Settings();
        for (Object[] p : spots) {
            Result r = look((String) p[0], (String) p[1], (Integer) p[2], (Integer) p[3]);
            assertTrue(r.bare, p[0] + ": " + r);
            assertNull(r.reason);
            boolean offCentre = ((String) p[0]).startsWith("board3");
            assertTrue(r.foreignShare <= s.maxForeignShare / (offCentre ? 1 : 2), p[0] + ": " + r);
            assertTrue(r.ringSpread <= s.maxRingSpread / 1.3, p[0] + ": " + r);
        }
    }

    /**
     * Paste and a board edge: each with at least 4x the share limit. Silkscreen keeps 2.5x: print
     * strokes thinner than 0.2 mm no longer count, the solid part of the FID1 lettering does.
     */
    @Test
    public void foreignSpots() throws Exception {
        Object[][] spots = {
                {"blk7_365 on a purge dot", BARE + "blk7_365.jpg", 950, 767, 4.0},
                {"blk7_365 on another purge dot", BARE + "blk7_365.jpg", 1172, 488, 4.0},
                {"blk7_365 purge dot 1.2 mm beside", BARE + "blk7_365.jpg", 1062, 767, 4.0},
                {"blk_y243_x340 paste on a round pad", BARE + "blk_y243_x340.jpg", 875, 453, 4.0},
                {"reflow1_FID1_0 centre (FID1 silkscreen)", FID + "reflow1_FID1_0.jpg", 960, 540, 2.5},
                {"board2_FID1_0 centre (board edge across)", FID + "board2_FID1_0.jpg", 960, 540, 4.0}};
        Settings s = new Settings();
        for (Object[] p : spots) {
            Result r = look((String) p[0], (String) p[1], (Integer) p[2], (Integer) p[3]);
            assertFalse(r.bare, p[0] + ": " + r);
            assertTrue(r.foreignShare >= (Double) p[4] * s.maxForeignShare, p[0] + ": " + r);
            assertTrue(r.reason.contains("paste, a part or print"), r.reason);
        }
    }

    /**
     * At the blank's edge the ring itself is not one surface (1.5x the spread limit; the spot also
     * reads 15x the share limit).
     */
    @Test
    public void blankEdge() throws Exception {
        Result r = look("blank_edge at the edge", BARE + "blank_edge.jpg", 1500, 540);
        assertFalse(r.bare);
        assertTrue(r.ringSpread >= 1.5 * new Settings().maxRingSpread, r.toString());
        assertTrue(r.reason.startsWith("the surface around the spot is not uniform"), r.reason);
    }

    /**
     * The real machine's bare spots, labelled by eye (real/README.md). The ring median alone
     * refused four of them: glossy blank in the dark zone between the ring light's reflections
     * (blank_328, bare_blank_324 twice) and the scratched mask of board 3 (b3_R1, b3_mask_37_37).
     * Each keeps at least 3x margin on the share and 1.3x on the spread.
     */
    @Test
    public void realBareSpots() throws Exception {
        String[] spots = {"blank_310_247", "blank_322_249.5", "blank_328_245.5",
                "bare_blank_324_245.5_bench", "bare_blank_324_245.5_openpnp",
                "bare_blank_300.32_245.5_bench", "paste_298.647_240.218", "paste_305.389_240.371",
                "paste_318.473_240.454", "b3_mask_67_67", "b3_mask_8_67", "b3_R1_37.5_42.5",
                "b3_mask_37_37"};
        Settings s = new Settings();
        Result scratched = null;
        for (String n : spots) {
            Result r = real(n);
            assertTrue(r.bare, n + ": " + r);
            assertTrue(r.foreignShare <= s.maxForeignShare / 3, n + ": " + r);
            assertTrue(r.ringSpread <= s.maxRingSpread / 1.3, n + ": " + r);
            if (n.equals("b3_mask_37_37")) {
                scratched = r;
            }
        }
        // The scratched mask: its own texture sets the threshold, and most of what still differs
        // is a scratch 0.13 mm wide across the spot.
        assertTrue(scratched.threshold > 2 * s.colourDelta, scratched.toString());
        assertTrue(scratched.rawShare > 2 * s.maxForeignShare, scratched.toString());
    }

    /**
     * The real machine's paste dots, a mounting hole's ring (twice: b3_mask_8_8, and the corner frame
     * of the first board probe) and a board edge: at least 6x.
     */
    @Test
    public void realForeignSpots() throws Exception {
        String[] spots = {"centre_c1_304.11_238.9", "centre_c2_314.54_239.47",
                "centre_c3_297.62_234.34", "centre_c4_301.94_238.44", "b3_mask_8_8",
                "b3_mask_67_8", "b3reg_corner_2_2_alt_openpnp"};
        Settings s = new Settings();
        for (String n : spots) {
            Result r = real(n);
            assertFalse(r.bare, n + ": " + r);
            assertTrue(r.foreignShare >= 6 * s.maxForeignShare, n + ": " + r);
            assertTrue(r.reason.contains("paste, a part or print"), r.reason);
        }
    }

    /**
     * Board 3's texture laid over a real frame: the frame plus (rough - its mean over +-0.5 mm), per
     * channel. Both are real/ cuts of the bench camera.
     */
    static BufferedImage roughen(String name, String rough) throws Exception {
        BufferedImage base = FidScene.resource(REAL + name + ".jpg");
        BufferedImage tex = FidScene.resource(REAL + rough + ".jpg");
        int w = base.getWidth();
        int h = base.getHeight();
        int r = (int) Math.round(0.5 / SX);
        long[][] sum = new long[3][(w + 1) * (h + 1)];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int p = tex.getRGB(i, j);
                int q = (j + 1) * (w + 1) + i + 1;
                for (int c = 0; c < 3; c++) {
                    sum[c][q] = ((p >> (8 * c)) & 0xff) + sum[c][q - 1] + sum[c][q - w - 1]
                            - sum[c][q - w - 2];
                }
            }
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int x0 = Math.max(0, i - r);
                int x1 = Math.min(w - 1, i + r);
                int y0 = Math.max(0, j - r);
                int y1 = Math.min(h - 1, j + r);
                double n = (x1 - x0 + 1) * (double) (y1 - y0 + 1);
                int pb = base.getRGB(i, j);
                int pt = tex.getRGB(i, j);
                int o = 0;
                for (int c = 0; c < 3; c++) {
                    long m = sum[c][(y1 + 1) * (w + 1) + x1 + 1] - sum[c][y0 * (w + 1) + x1 + 1]
                            - sum[c][(y1 + 1) * (w + 1) + x0] + sum[c][y0 * (w + 1) + x0];
                    double v = ((pb >> (8 * c)) & 0xff) + ((pt >> (8 * c)) & 0xff) - m / n;
                    o |= Math.max(0, Math.min(255, (int) Math.round(v))) << (8 * c);
                }
                out.setRGB(i, j, o);
            }
        }
        return out;
    }

    /** A dot of diameter d mm at (0.1, 0.1) mm, offset by contrast along SIM paste - SIM mask. */
    static BufferedImage withDot(String name, double d, double contrast) throws Exception {
        BufferedImage img = FidScene.resource(REAL + name + ".jpg");
        int w = img.getWidth();
        int h = img.getHeight();
        double[] dir = {18, 4, 13}; // PASTE - MASK, BGR
        double n = Math.sqrt(18 * 18 + 4 * 4 + 13 * 13);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int p = img.getRGB(i, j);
                double x = (i - w / 2.0) * SX - 0.1;
                double y = (h / 2.0 - j) * SY - 0.1;
                if (x * x + y * y <= d * d / 4) {
                    int o = 0;
                    for (int c = 0; c < 3; c++) {
                        double v = ((p >> (8 * c)) & 0xff) + dir[c] / n * contrast;
                        o |= Math.max(0, Math.min(255, (int) Math.round(v))) << (8 * c);
                    }
                    p = o;
                }
                out.setRGB(i, j, p);
            }
        }
        return out;
    }

    /**
     * On a surface as rough as board 3's the threshold for small patches rises to 30 .. 88; the
     * wide patches still see paste, a hole's ring and an edge there. Board 3's texture
     * (b3_mask_37_37, b3_R1) laid over the real paste, hole and edge frames: paste and the hole
     * keep 2.5x the share limit, the edge only 1.2x. The same texture over bare frames stays bare
     * with 3x margin. A 0.7 mm dot 40 from the mask (paste's rim on the blank reads 40 .. 100) on
     * board 3's own rough spots keeps 2x, a 1.0 mm dot 4x. Without the wide patches the look
     * called all of these dots, the edge and the paste frames c3 and c4 bare.
     */
    @Test
    public void roughSurfaceKeepsPasteHolesAndEdges() throws Exception {
        Settings s = new Settings();
        for (String rough : new String[] {"b3_mask_37_37", "b3_R1_37.5_42.5"}) {
            for (String n : new String[] {"centre_c1_304.11_238.9", "centre_c2_314.54_239.47",
                    "centre_c3_297.62_234.34", "centre_c4_301.94_238.44", "b3_mask_8_8"}) {
                Result r = print(n + " + " + rough, BareSpotCheck.check(roughen(n, rough), SX, SY,
                        null));
                assertFalse(r.bare, n + " + " + rough + ": " + r);
                assertTrue(r.threshold > 30, r.toString());
                assertTrue(r.wideShare >= 2.5 * s.maxForeignShare, n + " + " + rough + ": " + r);
            }
            Result edge = print("b3_mask_67_8 + " + rough, BareSpotCheck.check(
                    roughen("b3_mask_67_8", rough), SX, SY, null));
            assertFalse(edge.bare, edge.toString());
            assertTrue(edge.foreignShare >= 1.2 * s.maxForeignShare, edge.toString());
            for (String n : new String[] {"b3_mask_67_67", "b3_mask_8_67", "blank_310_247",
                    "blank_328_245.5", "paste_298.647_240.218"}) {
                Result r = print(n + " + " + rough, BareSpotCheck.check(roughen(n, rough), SX, SY,
                        null));
                assertTrue(r.bare, n + " + " + rough + ": " + r);
                assertTrue(r.foreignShare <= s.maxForeignShare / 3, n + " + " + rough + ": " + r);
            }
            for (double d : new double[] {0.7, 1.0}) {
                Result r = print(String.format(Locale.ROOT, "%.1f mm dot +40 on %s", d, rough),
                        BareSpotCheck.check(withDot(rough, d, 40), SX, SY, null));
                assertFalse(r.bare, r.toString());
                assertTrue(r.wideShare >= (d < 1 ? 2 : 4) * s.maxForeignShare, r.toString());
            }
        }
    }

    static BufferedImage synth(java.util.function.Consumer<java.awt.Graphics2D> draw) {
        return FidScene.render(640, 600, SX, SY, draw);
    }

    @Test
    public void syntheticLooks() {
        Result plain = BareSpotCheck.check(FidScene.noise(synth(g -> { }), 3, 4), SX, SY, null);
        assertTrue(plain.bare, plain.toString());
        assertEquals(0, plain.foreignShare, 0);
        assertEquals(142, plain.refB, 1);
        assertEquals(59, plain.refG, 1);
        assertEquals(0, plain.refR, 1);
        // The SIM's paste on the SIM's mask (22.6 apart): a 1.0 mm dot on the spot, with noise.
        Result dirty = BareSpotCheck.check(FidScene.noise(synth(g -> FidScene.disk(g, 0.1, -0.2,
                1.0, FidScene.PASTE)), 5, 4), SX, SY, null);
        assertFalse(dirty.bare, dirty.toString());
        assertTrue(dirty.foreignShare > 0.2, dirty.toString());
        // A 0.5 mm paste dot (6 % of the 1 mm disc) at 0.5 mm from the centre.
        Result speck = BareSpotCheck.check(synth(g -> FidScene.disk(g, 0.5, 0, 0.5,
                FidScene.PASTE)), SX, SY, null);
        assertFalse(speck.bare, speck.toString());
        assertEquals(0.5 * 0.5 / 4, speck.foreignShare, 0.01);
        // The same dot 1.3 mm away is outside the disc and too small to move the ring median.
        Result beside = BareSpotCheck.check(synth(g -> FidScene.disk(g, 1.3, 0, 0.5,
                FidScene.PASTE)), SX, SY, null);
        assertTrue(beside.bare, beside.toString());
        // The dark bed beyond an edge 2 mm from the spot covers 15 % of the ring: the median is
        // still the surface, and the spot itself is clear.
        Result edge = BareSpotCheck.check(synth(g -> {
            g.setColor(FidScene.bgr(40, 40, 40));
            g.fill(new Rectangle2D.Double(2.0, -10, 20, 20));
        }), SX, SY, null);
        assertTrue(edge.bare, edge.toString());
        // A ring of two surfaces (0.25 mm stripes of mask and print around a clean disc) has no
        // one reference colour.
        Result striped = BareSpotCheck.check(synth(g -> {
            g.setColor(FidScene.bgr(220, 200, 150));
            for (double x = -4; x < 4; x += 0.5) {
                g.fill(new Rectangle2D.Double(x, -4, 0.25, 8));
            }
            FidScene.disk(g, 0, 0, 2.4, FidScene.MASK);
        }), SX, SY, null);
        assertFalse(striped.bare, striped.toString());
        assertTrue(striped.reason.startsWith("the surface around the spot is not uniform"),
                striped.reason);
        // Grey frames read the same as their colour.
        BufferedImage colour = synth(g -> FidScene.disk(g, 0, 0, 0.8, FidScene.PAD));
        BufferedImage grey = new BufferedImage(640, 600, BufferedImage.TYPE_BYTE_GRAY);
        grey.getGraphics().drawImage(colour, 0, 0, null);
        Result gr = BareSpotCheck.check(grey, SX, SY, null);
        assertFalse(gr.bare);
        assertEquals(gr.refB, gr.refR, 0);
        assertEquals(0.16, gr.foreignShare, 0.01);
    }

    /**
     * The ring light's shading: the mask 40 darker in B and G at the spot than 3.5 mm out, falling
     * off with the square of the distance (the whole disc reads 21 .. 26 from the ring's median
     * colour, as the dark zone on glossy blank does).
     */
    static void shade(java.awt.Graphics2D g) {
        float[] at = {0f, 0.25f, 0.5f, 0.75f, 1f};
        Color[] c = new Color[at.length];
        for (int i = 0; i < at.length; i++) {
            int d = (int) Math.round(40 * (1 - at[i] * at[i]));
            c[i] = FidScene.bgr(142 - d, 59 - d, 0);
        }
        g.setPaint(new RadialGradientPaint(new Point2D.Double(0, 0), 3.5f, at, c,
                MultipleGradientPaint.CycleMethod.NO_CYCLE));
        g.fill(new Rectangle2D.Double(-10, -10, 20, 20));
    }

    /**
     * What the field, the texture threshold and the solid-patch rule each let through, and what
     * they do not: shading, a thin scratch and a speckled texture are bare; paste or an edge on
     * the same shaded or rough surface are not.
     */
    @Test
    public void surfaceShadingTextureAndStrokes() {
        // Shading alone.
        Result shaded = BareSpotCheck.check(synth(BareSpotCheckTest::shade), SX, SY, null);
        assertTrue(shaded.bare, shaded.toString());
        assertEquals(0, shaded.foreignShare, 0);
        // The SIM's 0.5 mm paste dot on the shaded mask.
        Result shadedSpeck = BareSpotCheck.check(synth(g -> {
            shade(g);
            FidScene.disk(g, 0.5, 0, 0.5, FidScene.PASTE);
        }), SX, SY, null);
        assertFalse(shadedSpeck.bare, shadedSpeck.toString());
        // A board edge 0.3 mm above the spot, on the shaded mask, with the dark bed beyond.
        Result shadedEdge = BareSpotCheck.check(synth(g -> {
            shade(g);
            g.setColor(FidScene.bgr(40, 40, 40));
            g.fill(new Rectangle2D.Double(-10, 0.3, 20, 10));
        }), SX, SY, null);
        assertFalse(shadedEdge.bare, shadedEdge.toString());
        assertTrue(shadedEdge.foreignShare > 4 * new Settings().maxForeignShare,
                shadedEdge.toString());
        // A bright scratch 0.12 mm wide across the spot is not a solid patch.
        Result scratch = BareSpotCheck.check(synth(g -> {
            g.setColor(FidScene.bgr(200, 110, 0));
            g.setStroke(new java.awt.BasicStroke(0.12f));
            g.draw(new Line2D.Double(-2.5, -0.6, 2.5, 0.4));
        }), SX, SY, null);
        assertTrue(scratch.bare, scratch.toString());
        assertTrue(scratch.rawShare > new Settings().maxForeignShare, scratch.toString());
        // A rough surface (+-15 per channel) raises the threshold; a dark 0.6 mm dot (paste's
        // shadowed rim reads 60 .. 100 below the blank) still counts in full.
        Result rough = BareSpotCheck.check(FidScene.noise(synth(g -> { }), 7, 15), SX, SY, null);
        assertTrue(rough.bare, rough.toString());
        assertTrue(rough.threshold > new Settings().colourDelta, rough.toString());
        Result roughDot = BareSpotCheck.check(FidScene.noise(synth(g -> FidScene.disk(g, 0.2, 0,
                0.6, FidScene.bgr(60, 0, 0))), 7, 15), SX, SY, null);
        assertFalse(roughDot.bare, roughDot.toString());
        assertEquals(0.6 * 0.6 / 4, roughDot.foreignShare, 0.01);
        for (Result r : new Result[] {shaded, shadedSpeck, shadedEdge, scratch, rough, roughDot}) {
            print("synthetic", r);
        }
    }

    @Test
    public void refusals() {
        BufferedImage small = synth(g -> { }).getSubimage(0, 0, 500, 500);
        assertThrows(IllegalArgumentException.class,
                () -> BareSpotCheck.check(small, SX, SY, null), "the 3 mm ring needs 560 px");
        BufferedImage ok = synth(g -> { });
        assertThrows(IllegalArgumentException.class, () -> BareSpotCheck.check(ok, 0, SY, null));
        assertThrows(IllegalArgumentException.class, () -> BareSpotCheck.check(null, SX, SY, null));
        Settings bad = new Settings();
        bad.ringInnerMm = 0.5;
        assertThrows(IllegalArgumentException.class, () -> BareSpotCheck.check(ok, SX, SY, bad));
        Settings coarse = new Settings();
        coarse.solidRadiusMm = 1.0;
        assertThrows(IllegalArgumentException.class, () -> BareSpotCheck.check(ok, SX, SY, coarse));
        assertThrows(IllegalArgumentException.class, () -> BareSpotCheck.check(ok, 4, 4, null),
                "4 mm per pixel is too coarse");
    }
}
