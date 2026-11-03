package org.openpnp.instancelabs.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.vision.FiducialDetector.Detection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The fid.py port on Java2D frames and on bench frames. Every detection is printed with the
 * prefix "[fid]" so the numbers can be quoted.
 */
public class FiducialDetectorTest {
    static final double SX = FidScene.BENCH_X, SY = FidScene.BENCH_Y;
    static final double INF = Double.POSITIVE_INFINITY;

    static Detection detect(String label, BufferedImage img, double cx, double cy, double r) {
        Detection d = FiducialDetector.detect(img, SX, SY, cx, cy, r, null);
        System.out.println("[fid] " + label + ": " + d);
        return d;
    }

    /** Pixel position of (x, y) mm from the frame centre. */
    static double px(double x) {
        return FidScene.W / 2.0 + x / SX;
    }

    static double py(double y) {
        return FidScene.H / 2.0 - y / SY;
    }

    @Test
    public void subPixelCentre() {
        double[][] at = {{0.2371, -0.4113}, {-3.0049, 1.7777}, {6.1234, -2.5011}};
        for (double[] p : at) {
            BufferedImage img = FidScene.render(FidScene.W, FidScene.H, SX, SY,
                    g -> FidScene.fiducial(g, p[0], p[1], 1.0, 3.0));
            Detection d = detect("1 mm at " + p[0] + "," + p[1], img, 0, 0, INF);
            assertNotNull(d);
            assertEquals(px(p[0]), d.xPx, 0.2);
            assertEquals(py(p[1]), d.yPx, 0.2);
            assertEquals(p[0], d.offsetXMm, 0.2 * SX);
            assertEquals(p[1], d.offsetYMm, 0.2 * SY);
            assertEquals(1.0, d.diameterMm, 0.03);
            assertTrue(d.circularity > 0.85 && d.fill > 0.9, d.toString());
            assertEquals(0, d.secondScore, 0, "no other mark");
            // Noise and a coarser camera move it by less than 0.2 px too.
            BufferedImage noisy = FidScene.noise(FidScene.render(FidScene.W, FidScene.H, SX, SY,
                    g -> FidScene.fiducial(g, p[0], p[1], 1.0, 3.0)), 11, 6);
            Detection n = detect("noisy", noisy, 0, 0, INF);
            assertEquals(px(p[0]), n.xPx, 0.2);
            assertEquals(py(p[1]), n.yPx, 0.2);
        }
        BufferedImage small = FidScene.render(960, 540, 0.0214, 0.0221,
                g -> FidScene.fiducial(g, 0.5555, 0.3333, 1.0, 3.0));
        Detection d = FiducialDetector.detect(small, 0.0214, 0.0221, 0, 0, INF, null);
        assertNotNull(d, "the area window is in mm, so a 47 px mark is still a fiducial");
        assertEquals(480 + 0.5555 / 0.0214, d.xPx, 0.2);
        assertEquals(270 - 0.3333 / 0.0221, d.yPx, 0.2);
    }

    @Test
    public void wrongSizesRejected() {
        for (double size : new double[] {0.6, 1.4}) {
            BufferedImage img = FidScene.render(FidScene.W, FidScene.H, SX, SY,
                    g -> FidScene.fiducial(g, 0, 0, size, 3.0));
            assertNull(detect(size + " mm disk", img, 0, 0, INF));
        }
        for (double size : new double[] {0.8, 1.15}) {
            BufferedImage img = FidScene.render(FidScene.W, FidScene.H, SX, SY,
                    g -> FidScene.fiducial(g, 0, 0, size, 3.0));
            assertNotNull(detect(size + " mm disk", img, 0, 0, INF), "inside 0.76 .. 1.20 d");
        }
    }

    @Test
    public void rectangularPadRejected() {
        // 0.80 x 0.95 mm: area 0.76 mm^2 is inside the window, circularity 0.78, fill 0.63.
        for (double rot : new double[] {0, 30, 45}) {
            BufferedImage img = FidScene.render(FidScene.W, FidScene.H, SX, SY,
                    g -> FidScene.pad(g, 0.3, -0.2, 0.80, 0.95, rot));
            assertNull(detect("0.80x0.95 pad at " + rot + " deg", img, 0, 0, INF));
        }
    }

    @Test
    public void markAtFrameEdgeRejected() {
        double halfW = FidScene.W / 2.0 * SX;
        // Centre 0.8 mm inside the left edge: its 2r ring (1 mm) leaves the frame. fid.py would
        // score it with a clipped ring.
        BufferedImage edge = FidScene.render(FidScene.W, FidScene.H, SX, SY,
                g -> FidScene.fiducial(g, -halfW + 0.8, 0, 1.0, 3.0));
        assertNull(detect("0.8 mm from the edge", edge, 0, 0, INF));
        BufferedImage inside = FidScene.render(FidScene.W, FidScene.H, SX, SY,
                g -> FidScene.fiducial(g, -halfW + 1.1, 0, 1.0, 3.0));
        assertNotNull(detect("1.1 mm from the edge", inside, 0, 0, INF));
    }

    @Test
    public void searchRadiusExcludesFarMark() {
        BufferedImage img = FidScene.render(FidScene.W, FidScene.H, SX, SY,
                g -> FidScene.fiducial(g, 4, 2, 1.0, 3.0));
        assertNull(detect("4,2 seen from 0,0 r 3", img, 0, 0, 3.0));
        assertNotNull(detect("4,2 seen from 0,0 r 5", img, 0, 0, 5.0));
        Detection d = detect("4,2 seen from 4,2.3 r 0.5", img, 4, 2.3, 0.5);
        assertNotNull(d);
        assertEquals(4, d.offsetXMm, 0.01);
        assertEquals(2, d.offsetYMm, 0.01);
        assertNull(detect("4,2 seen from 4,2.6 r 0.5", img, 4, 2.6, 0.5));
    }

    @Test
    public void twoFiducialsAreAmbiguous() {
        BufferedImage two = FidScene.render(FidScene.W, FidScene.H, SX, SY, g -> {
            FidScene.fiducial(g, -2, 0, 1.0, 3.0);
            FidScene.fiducial(g, 2, 0.5, 1.0, 3.0);
        });
        Detection d = detect("two fiducials", two, 0, 0, INF);
        assertNotNull(d);
        assertTrue(d.isAmbiguous(0.9), d.toString());
        assertTrue(d.candidates >= 2);
        // Only one inside the search radius: not ambiguous.
        Detection one = detect("two fiducials, radius 1 around one", two, 2, 0.5, 1.0);
        assertFalse(one.isAmbiguous(0.9), one.toString());
        assertEquals(0, one.secondScore, 0);
        // A dull round mark (half the contrast) beside a fiducial is not ambiguous either.
        BufferedImage dull = FidScene.render(FidScene.W, FidScene.H, SX, SY, g -> {
            FidScene.fiducial(g, -2, 0, 1.0, 3.0);
            FidScene.disk(g, 2, 0.5, 1.0, FidScene.bgr(190, 150, 100));
        });
        Detection d2 = detect("fiducial + dull mark", dull, 0, 0, INF);
        assertEquals(-2, d2.offsetXMm, 0.01);
        assertTrue(d2.secondScore > 0 && !d2.isAmbiguous(0.9), d2.toString());
    }

    @Test
    public void darkHoleIsNoFiducial() {
        // fid.py returns the inner contour of a dark round hole in bright copper with a negative
        // score (bench frame blk_y243_x360.jpg); here it is no fiducial.
        BufferedImage img = FidScene.render(FidScene.W, FidScene.H, SX, SY, g -> {
            FidScene.disk(g, 0, 0, 3.0, FidScene.PAD);
            FidScene.disk(g, 0, 0, 1.0, FidScene.MASK);
        });
        assertNull(detect("dark 1 mm hole in copper", img, 0, 0, INF));
    }

    @Test
    public void greyAndSubImages() {
        BufferedImage img = FidScene.render(FidScene.W, FidScene.H, SX, SY,
                g -> FidScene.fiducial(g, 1.234, -0.567, 1.0, 3.0));
        Detection ref = FiducialDetector.detect(img, SX, SY, 0, 0, INF, null);
        BufferedImage grey = new BufferedImage(FidScene.W, FidScene.H,
                BufferedImage.TYPE_BYTE_GRAY);
        grey.getGraphics().drawImage(img, 0, 0, null);
        Detection g = FiducialDetector.detect(grey, SX, SY, 0, 0, INF, null);
        assertEquals(ref.xPx, g.xPx, 0.05);
        assertEquals(ref.yPx, g.yPx, 0.05);
        // A centred sub-image shares the parent's buffer; it must be read as its own frame.
        BufferedImage sub = img.getSubimage(480, 270, 960, 540);
        Detection s = FiducialDetector.detect(sub, SX, SY, 0, 0, INF, null);
        assertEquals(ref.xPx - 480, s.xPx, 1e-9);
        assertEquals(ref.offsetXMm, s.offsetXMm, 1e-9);
        assertEquals(ref.offsetYMm, s.offsetYMm, 1e-9);
        assertThrows(IllegalArgumentException.class,
                () -> FiducialDetector.detect(img, 0, SY, 0, 0, INF, null));
        assertThrows(IllegalArgumentException.class,
                () -> FiducialDetector.detect(img, SX, SY, Double.NaN, 0, INF, null));
        FiducialDetector.Settings bad = new FiducialDetector.Settings();
        bad.blurKernelPx = 6;
        assertThrows(IllegalArgumentException.class,
                () -> FiducialDetector.detect(img, SX, SY, 0, 0, INF, bad));
    }

    /**
     * Bench parity: 6 raw frames from runs/*_FID*_*.jpg against tools/fid.py's own result
     * (expected.json, written once offline in the bench venv). Unturned, the port gives fid.py's
     * contour (on all 131 bench frames checked offline: max 0.0001 px). Turned by 180 degrees,
     * as the engine sees them after the Top camera's flips, the centre stays within 0.5 px of
     * (W-1-px, H-1-py) and the diameter within 3 %: fid.py's int() truncations of the centre and
     * radius are not symmetric under the turn, so the winning threshold level can change (offline
     * over the 131 frames: max 0.61 px, 2.7 %; these 6 frames: max 0.40 px).
     */
    @Test
    public void benchParity() throws Exception {
        JsonObject doc;
        try (InputStream in = getClass().getResourceAsStream(
                "/org/openpnp/instancelabs/vision/fid/expected.json")) {
            doc = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
        assertEquals(SX, doc.get("mmPerPxX").getAsDouble(), 0);
        assertEquals(SY, doc.get("mmPerPxY").getAsDouble(), 0);
        JsonArray frames = doc.getAsJsonArray("frames");
        assertEquals(6, frames.size());
        double worstRaw = 0;
        double worstTurned = 0;
        for (JsonElement e : frames) {
            JsonObject f = e.getAsJsonObject();
            String file = f.get("file").getAsString();
            double epx = f.get("px").getAsDouble();
            double epy = f.get("py").getAsDouble();
            double ed = f.get("d").getAsDouble();
            BufferedImage raw = FidScene.resource("/org/openpnp/instancelabs/vision/fid/" + file);
            int w = raw.getWidth();
            int h = raw.getHeight();
            assertEquals(f.get("width").getAsInt(), w);
            assertEquals(f.get("height").getAsInt(), h);

            Detection r = detect(file + " raw", raw, 0, 0, INF);
            assertNotNull(r, file);
            double dr = Math.hypot(r.xPx - epx, r.yPx - epy);
            worstRaw = Math.max(worstRaw, dr);
            assertTrue(dr <= 0.01, file + " raw is " + dr + " px from fid.py");
            assertEquals(ed, r.diameterMm / Math.sqrt(SX * SY), 0.001 * ed, file);
            assertEquals(f.get("circ").getAsDouble(), r.circularity, 1e-4, file);
            assertEquals(f.get("score").getAsDouble(), r.score, 0.01, file);

            Detection t = detect(file + " turned", FidScene.turn180(raw), 0, 0, INF);
            assertNotNull(t, file);
            double dt = Math.hypot(t.xPx - (w - 1 - epx), t.yPx - (h - 1 - epy));
            worstTurned = Math.max(worstTurned, dt);
            assertTrue(dt <= 0.5, file + " turned is " + dt + " px from fid.py");
            assertEquals(ed, t.diameterMm / Math.sqrt(SX * SY), 0.03 * ed, file);
            // On turned frames the offsets are the bench mapping of tools/locate.py
            // (fid - cam = -(px-960) SX, +(py-540) SY) up to the half-pixel convention.
            assertEquals(-(epx - w / 2.0) * SX, t.offsetXMm, 1.6 * SX, file);
            assertEquals((epy - h / 2.0) * SY, t.offsetYMm, 1.6 * SY, file);
        }
        System.out.println(String.format(Locale.ROOT,
                "[fid] bench parity over %d frames: raw max %.4f px, turned max %.3f px",
                frames.size(), worstRaw, worstTurned));
    }
}
