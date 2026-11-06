package org.openpnp.instancelabs.dispense;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.Random;

/**
 * BR-94's bottom camera in a test: N1's end and the needle's as the real camera shows them, in OpenPnP's frame (1920 x
 * 1080, turned over its x-axis: +Y up the frame), each at k = f / D px per mm, D its distance from the camera's centre
 * of projection (N1 farther as raw Z rises, the needle nearer: the see-saw), with sensor noise, the ring light's glow at
 * the frame's edges, the needle's lit end (a small white highlight on the red end's lavender glow) and a wide hub glint
 * beside it, N1's end a ring and a speckle. The faults the end measure must refuse are switches.
 */
public class FromBelowScene {
    public static final int W = 1920, H = 1080;
    /** The camera's location (machine mm) and the pixel it is (Process's BC turned as OpenPnP turns the frame). */
    public double camX = 217.129, camY = 152.319, bcx = 957.5, bcy = 534.8;
    /** f (px) and the centre of projection in N1's raw Z (Process's t2_px25: f 673.5, N1 k 15.45 at raw 25). */
    public double fPx = 673.5, cRaw = -18.6;
    /** K: N1's raw Z + the needle's where both tips are the same distance from the camera. */
    public double k = 65.0;
    /** The needle's D grows this much faster than N1's per raw mm (1: a true see-saw; else K spreads over N1's Z). */
    public double needleScale = 1.0;
    /** The needle's end from the head at raw Z zRef and its tilt per raw mm (Process's 25G on p34). */
    public double endDx = 25.4, endDy = -76.2, tiltX = 0.019, tiltY = -0.076, zRef = 40.4;
    /** N1's tip from the head (OpenPnP's offset in the SIM). */
    public double n1Dx = -24.178, n1Dy = -63.91;
    /** The ring light on; its glow's R/G (white: 0.32; a room-lit frame 0.6). */
    public boolean light = true;
    public double rimRg = 0.32;
    /** Whether the needle's end is in the scene (false: a camera with no tip over it). */
    public boolean needle = true;
    /** Paste: a grey-200 blob 0.65 mm across centred 0.65 mm off the end (tipclean.py's case, spread ~0.8). */
    public boolean paste;
    /** A bright mark on the camera's window that does not move with the head. */
    public boolean mark;
    /** A brighter small object this far from the end (head mm) that moves with it (a glint off the holder), or null. */
    public double[] brightMm;
    /** The end creeps by this much per frame (px, along x): a loose tip. */
    public double creepPx;
    public double noise = 3;
    /** The needle's light, scaled: a dim or defocused end (Process's p32 end at travel Z read a top-hat peak of 16). */
    public double endGain = 1;
    private final double[] table = new double[512 * 512];
    private final Random rnd;
    private int frames;

    public FromBelowScene(long seed) {
        rnd = new Random(seed);
        for (int i = 0; i < table.length; i++) {
            table[i] = rnd.nextGaussian();
        }
    }

    /** px per mm of N1's tip at raw Z {@code z}. */
    public double n1Scale(double z) {
        return fPx / (z - cRaw);
    }

    /** px per mm of the needle's end at raw Z {@code z}. */
    public double needleScale(double z) {
        return fPx / (needleScale * (k - z) - cRaw);
    }

    /** The needle's end from the head at raw Z {@code z} (mm). */
    public double[] end(double z) {
        return new double[] {endDx + tiltX * (z - zRef), endDy + tiltY * (z - zRef)};
    }

    /** The frame with the head at raw (x, y, z). */
    public BufferedImage render(double x, double y, double z) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR);
        byte[] px = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        int off = rnd.nextInt(table.length);
        double bg = light ? 40 : 8;
        for (int i = 0, n = W * H; i < n; i++) {
            int v = clamp(bg + noise * table[(off + i * 7) % table.length]);
            px[3 * i] = (byte) v;
            px[3 * i + 1] = (byte) v;
            px[3 * i + 2] = (byte) v;
        }
        if (light) {
            // the ring's glow at the left and right edges: grey ~103 at R/G rimRg (B, G, R)
            double g = 309 / (1 + rimRg + 1.24), b = 1.24 * g, r = rimRg * g;
            for (int yy = 0; yy < H; yy++) {
                for (int xx = 0; xx < W; xx = xx == 139 ? W - 140 : xx + 1) {
                    set(px, xx, yy, b, g, r);
                }
            }
        }
        double t = frames++ * creepPx;
        if (mark) {
            blob(px, bcx + 5, bcy + 3, 1.5, 150, 150, 150);
        }
        if (light && z <= 31.5 + 1e-9) {
            double s = n1Scale(z), tx = bcx + s * (x + n1Dx - camX), ty = bcy - s * (y + n1Dy - camY);
            Random tex = new Random(11);
            for (int i = 0; i < 40; i++) {
                double a = tex.nextDouble() * 2 * Math.PI, rr = tex.nextDouble() * 0.45, v = 40 + tex.nextDouble() * 60;
                blob(px, tx + s * rr * Math.cos(a), ty + s * rr * Math.sin(a), 0.08 * s, v, v, v);
            }
            for (int i = 0; i < 36; i++) {
                double a = i * Math.PI / 18;
                blob(px, tx + 0.5 * s * Math.cos(a), ty + 0.5 * s * Math.sin(a), 0.06 * s, 70, 70, 70);
            }
        }
        if (light && needle && z >= 31.5 - 1e-9) {
            double s = needleScale(z);
            double[] e = end(z);
            double ex = bcx + s * (x + e[0] - camX) + t, ey = bcy - s * (y + e[1] - camY);
            double focus = 1 + 0.12 * Math.abs(z - zRef);
            // the red end's own glow, ~0.8 mm across, lavender (R/G ~0.85)
            double gain = endGain;
            disc(px, ex, ey, 0.4 * s, 110 * gain, 130 * gain, 150 * gain);
            blob(px, ex, ey, 1.4 * focus, 140 * gain, 140 * gain, 140 * gain);
            blob(px, ex + 1.5 * s, ey + 0.4 * s, 0.6 * s, 90 * gain, 90 * gain, 90 * gain); // the hub's glint
            if (paste) {
                disc(px, ex + 0.65 * s, ey, 0.325 * s, 200, 200, 200, true);
            }
            if (brightMm != null) {
                // saturated white, 0.5 mm across: brighter in the top-hat than the end
                disc(px, ex + s * brightMm[0], ey - s * brightMm[1], 0.25 * s, 255, 255, 255, true);
            }
        }
        return img;
    }

    private static void set(byte[] px, int x, int y, double b, double g, double r) {
        int i = 3 * (y * W + x);
        px[i] = (byte) clamp(b);
        px[i + 1] = (byte) clamp(g);
        px[i + 2] = (byte) clamp(r);
    }

    /** Adds a round Gaussian blob (B, G, R amplitudes) at sub-pixel (cx, cy). */
    private static void blob(byte[] px, double cx, double cy, double sigma, double b, double g, double r) {
        int rr = (int) Math.ceil(4 * sigma);
        for (int y = Math.max(0, (int) cy - rr); y <= Math.min(H - 1, (int) cy + rr); y++) {
            for (int x = Math.max(0, (int) cx - rr); x <= Math.min(W - 1, (int) cx + rr); x++) {
                double w = Math.exp(-((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (2 * sigma * sigma));
                int i = 3 * (y * W + x);
                px[i] = (byte) clamp((px[i] & 0xff) + w * b);
                px[i + 1] = (byte) clamp((px[i + 1] & 0xff) + w * g);
                px[i + 2] = (byte) clamp((px[i + 2] & 0xff) + w * r);
            }
        }
    }

    private static void disc(byte[] px, double cx, double cy, double radius, double b, double g, double r) {
        disc(px, cx, cy, radius, b, g, r, false);
    }

    /** A disc of colour (B, G, R) at sub-pixel (cx, cy), its edge anti-aliased; {@code paint} replaces, else adds. */
    private static void disc(byte[] px, double cx, double cy, double radius, double b, double g, double r,
            boolean paint) {
        int rr = (int) Math.ceil(radius) + 1;
        for (int y = Math.max(0, (int) cy - rr); y <= Math.min(H - 1, (int) cy + rr); y++) {
            for (int x = Math.max(0, (int) cx - rr); x <= Math.min(W - 1, (int) cx + rr); x++) {
                double w = Math.max(0, Math.min(1, radius + 0.5 - Math.hypot(x - cx, y - cy)));
                int i = 3 * (y * W + x);
                double[] was = {px[i] & 0xff, px[i + 1] & 0xff, px[i + 2] & 0xff}, c = {b, g, r};
                for (int k = 0; k < 3; k++) {
                    px[i + k] = (byte) clamp(paint ? was[k] + w * (c[k] - was[k]) : was[k] + w * c[k]);
                }
            }
        }
    }

    static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
