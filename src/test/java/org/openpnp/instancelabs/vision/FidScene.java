package org.openpnp.instancelabs.vision;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import java.util.function.Consumer;

import javax.imageio.ImageIO;

/**
 * Java2D frames of a Lumen board under the top camera, for the fiducial tests. Colours are the
 * BGR measured on bench frame board3_FID1_2.jpg (the SIM renderer's table): solder mask, the 3 mm
 * fiducial mask opening, fiducial copper, pad copper, paste.
 */
final class FidScene {
    private FidScene() {}

    static final Color MASK = bgr(142, 59, 0), OPENING = bgr(128, 47, 0),
            COPPER = bgr(241, 240, 199), PAD = bgr(246, 244, 222), PASTE = bgr(160, 63, 13);
    /** Bench top camera, raw 1920 x 1080 frames (tools/locate.py SX, SY). */
    static final double BENCH_X = 0.01074, BENCH_Y = 0.01106;
    static final int W = 1920, H = 1080;

    static Color bgr(int b, int g, int r) {
        return new Color(r, g, b);
    }

    /**
     * A TYPE_3BYTE_BGR frame of solder mask; draw() paints in mm from the frame centre, +x right,
     * +y up, so that a point (x, y) lands at the OpenCV pixel position (w/2 + x/mmX, h/2 -
     * y/mmY), the convention of FiducialDetector's offsets. Antialiased.
     */
    static BufferedImage render(int w, int h, double mmX, double mmY, Consumer<Graphics2D> draw) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = img.createGraphics();
        g.setColor(MASK);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        // Java2D pixel i covers [i, i+1): its centre (OpenCV coordinate i) is at i + 0.5.
        AffineTransform t = new AffineTransform();
        t.translate(w / 2.0 + 0.5, h / 2.0 + 0.5);
        t.scale(1 / mmX, -1 / mmY);
        g.transform(t);
        draw.accept(g);
        g.dispose();
        return img;
    }

    /** A fiducial: copper disk of diameter d in a mask opening of diameter mask, mm. */
    static void fiducial(Graphics2D g, double x, double y, double d, double mask) {
        g.setColor(OPENING);
        g.fill(new Ellipse2D.Double(x - mask / 2, y - mask / 2, mask, mask));
        disk(g, x, y, d, COPPER);
    }

    static void disk(Graphics2D g, double x, double y, double d, Color c) {
        g.setColor(c);
        g.fill(new Ellipse2D.Double(x - d / 2, y - d / 2, d, d));
    }

    /** A copper pad w x h mm, rotated rotDeg counter-clockwise. */
    static void pad(Graphics2D g, double x, double y, double w, double h, double rotDeg) {
        AffineTransform keep = g.getTransform();
        g.translate(x, y);
        g.rotate(Math.toRadians(rotDeg));
        g.setColor(PAD);
        g.fill(new Rectangle2D.Double(-w / 2, -h / 2, w, h));
        g.setTransform(keep);
    }

    /** Adds seeded uniform noise of +-amp grey levels to every channel. */
    static BufferedImage noise(BufferedImage img, long seed, int amp) {
        byte[] px = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        Random r = new Random(seed);
        for (int i = 0; i < px.length; i++) {
            int v = (px[i] & 0xff) + r.nextInt(2 * amp + 1) - amp;
            px[i] = (byte) Math.max(0, Math.min(255, v));
        }
        return img;
    }

    /** The frame turned by 180 degrees: pixel (x, y) moves to (w-1-x, h-1-y). */
    static BufferedImage turn180(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        int[] row = new int[w];
        int[] rev = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                rev[w - 1 - x] = row[x];
            }
            out.setRGB(0, h - 1 - y, w, 1, rev, 0, w);
        }
        return out;
    }

    static BufferedImage resource(String path) throws IOException {
        try (InputStream in = FidScene.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing test resource " + path);
            }
            return ImageIO.read(in);
        }
    }
}
