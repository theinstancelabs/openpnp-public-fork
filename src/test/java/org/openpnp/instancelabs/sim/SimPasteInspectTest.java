package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.instancelabs.vision.DotInspector;
import org.openpnp.instancelabs.vision.DotInspectorSettings;

/**
 * The SIM paste as the top camera sees it (§11.4): deposits of a quarter, a half and a whole turn of
 * the engine's 2.11 mg/rev drawn on the bench blank by SimBoardRenderer and measured by the real
 * DotInspector (OpenCV): found where they are, at their size; a thread found by the corridor test of
 * §7.4; and on the old SIM colours (18 levels apart) nothing is found.
 */
public class SimPasteInspectTest {
    static final double UPP_X = 0.01068376, UPP_Y = 0.01072961; // the Top camera's (machine.xml)
    static final int W = 960, H = 720;
    /** BareSpotCheck's colour delta and the corridor share of §7.4. */
    static final double COLOUR_DELTA = 20, THREAD_SHARE = 0.15, CORRIDOR_MM = 0.3;

    /** Dots of the exact law at 2.11 mg/rev on the §11.3 scene's blank, 8 mm apart: 0.53, 1.06, 2.11 mg. */
    static SimPaste laid(SimScene s) {
        SimPaste p = new SimPaste(() -> s, SimPaste.Store.forScene(s), () -> 0L, () -> 0.0);
        SimNeedle n = new SimNeedle(p, s.paste.n2OffsetMm);
        n.pressurize();
        n.dot(300, 240, SimPasteTest.BLANK_Z, 400);
        n.dot(308, 240, SimPasteTest.BLANK_Z, 800);
        n.dot(316, 240, SimPasteTest.BLANK_Z, 1600);
        n.vent();
        return p;
    }

    static SimScene scene() {
        SimScene s = SimPasteTest.exact();
        s.paste.ripple = 0;
        s.paste.mRevMg = 2.11;
        return s;
    }

    static BufferedImage frame(SimScene s, double x, double y, List<SimPaste.Deposit> deposits) {
        return SimBoardRenderer.render(s, null, Collections.emptyList(), x, y, W, H, UPP_X, UPP_Y, deposits);
    }

    @Test
    public void dotInspectorMeasuresTheDepositsOnTheBenchBlank() {
        SimScene s = scene();
        List<SimPaste.Deposit> deposits = laid(s).deposits("blank");
        assertEquals(3, deposits.size());
        double[] mg = {0.5275, 1.055, 2.11};
        DotInspector inspector = new DotInspector(new DotInspectorSettings());
        for (int i = 0; i < 3; i++) {
            SimPaste.Deposit d = deposits.get(i);
            assertEquals(mg[i], d.mg, 1e-9);
            double camX = d.x + 0.4, camY = d.y - 0.3; // not centred: the offsets must come out right
            BufferedImage before = frame(s, camX, camY, Collections.emptyList());
            BufferedImage after = SimBoardRenderer.render(s, null, Collections.emptyList(), camX, camY, W, H, UPP_X,
                    UPP_Y); // the old signature draws the scene's paste
            List<Dot> found = inspector.inspect(before, after, UPP_X, UPP_Y);
            assertEquals(1, found.size(), mg[i] + " mg: " + found);
            Dot f = found.get(0);
            assertEquals(d.diameterMm(), f.diameterMm, 0.05 * d.diameterMm(), mg[i] + " mg: d within 5 % " + f);
            assertEquals(d.x - camX, f.xMm, 0.02, "where it lies");
            assertEquals(d.y - camY, f.yMm, 0.02);
        }
        // The SIM's footprint law (A = 0.875·m^0.77), not the engine's 0.83-1.00 / 1.16 / 1.345 mm (§1.4).
        assertEquals(0.825, deposits.get(0).diameterMm(), 0.005);
        assertEquals(1.078, deposits.get(1).diameterMm(), 0.005);
        assertEquals(1.407, deposits.get(2).diameterMm(), 0.005);
    }

    @Test
    public void aThreadIsFoundByTheCorridorTest() {
        SimScene s = scene();
        s.paste.mRevMg = 1.2; // quarter dots of 0.30 mg: a thread on every hop
        s.paste.threadShare = 1;
        s.paste.transferRatio = 0.75;
        SimPaste p = new SimPaste(() -> s, SimPaste.Store.forScene(s), () -> 0L, () -> 0.0);
        SimNeedle n = new SimNeedle(p, s.paste.n2OffsetMm);
        n.pressurize();
        n.row(300, 240, 2.5, 3, SimPasteTest.BLANK_Z, 400);
        n.travel(310, 240); // the lift breaks it: no thread from the third dot
        n.dot(310, 240, SimPasteTest.BLANK_Z, 400);
        n.vent();
        List<SimPaste.Deposit> all = p.deposits("blank");
        List<SimPaste.Deposit> dots = SimNeedle.dots(p, "blank");
        assertEquals(6, all.size(), "4 dots, 2 threads: " + all);
        BufferedImage before = frame(s, 305, 240, Collections.emptyList());
        BufferedImage after = frame(s, 305, 240, all);
        double threaded = corridorShare(before, after, 305, 240, dots.get(0), dots.get(1));
        assertTrue(threaded > THREAD_SHARE, "the hop 1 → 2 drew a thread: " + threaded);
        double clean = corridorShare(before, after, 305, 240, dots.get(2), dots.get(3));
        assertTrue(clean < 0.02, "no thread across a lift: " + clean);
        // DotInspector itself keeps the dots round: a thread is not taken for a dot.
        List<Dot> found = new DotInspector(new DotInspectorSettings()).inspect(before, after, UPP_X, UPP_Y);
        assertTrue(found.size() >= 2, found.toString());
    }

    @Test
    public void theOldSimColoursWouldMissThem() {
        // Today's blank (the mask colour) and paste differ by 18 in blue, below DotInspector's 25.
        Color blank = SimBoardRenderer.BLANK, paste = SimBoardRenderer.PASTE;
        int diff = Math.max(Math.abs(blank.getBlue() - paste.getBlue()), Math.max(Math.abs(blank.getGreen()
                - paste.getGreen()), Math.abs(blank.getRed() - paste.getRed())));
        assertEquals(18, diff);
        assertTrue(diff < new DotInspectorSettings().diffThreshold);
        Color bench = SimBoardRenderer.BENCH_BLANK, onBench = SimBoardRenderer.BLANK_PASTE;
        assertEquals(56, bench.getBlue() - onBench.getBlue(), "the bench frames' blank vs paste (p18_half)");
        SimScene s = scene();
        s.blankBgr = SimScene.DEFAULT_BLANK_BGR.clone();
        List<SimPaste.Deposit> deposits = laid(s).deposits("blank");
        DotInspector inspector = new DotInspector(new DotInspectorSettings());
        for (SimPaste.Deposit d : deposits) {
            List<Dot> found = inspector.inspect(frame(s, d.x, d.y, Collections.emptyList()),
                    frame(s, d.x, d.y, deposits), UPP_X, UPP_Y);
            assertTrue(found.isEmpty(), d + " on the old blank: " + found);
        }
    }

    /**
     * §7.4's thread test: the share of the pixels of a 0.3 mm wide corridor between the two dots' edges
     * that differ from the before frame by more than the colour delta (BGR distance).
     */
    static double corridorShare(BufferedImage before, BufferedImage after, double camX, double camY,
            SimPaste.Deposit a, SimPaste.Deposit b) {
        double dx = b.x - a.x, dy = b.y - a.y, len = Math.hypot(dx, dy);
        double ux = dx / len, uy = dy / len;
        double from = a.diameterMm() / 2, to = len - b.diameterMm() / 2;
        int n = 0, changed = 0;
        for (double t = from; t <= to; t += UPP_X / 2) {
            for (double w = -CORRIDOR_MM / 2; w <= CORRIDOR_MM / 2; w += UPP_Y / 2) {
                double x = a.x + ux * t - uy * w, y = a.y + uy * t + ux * w;
                int px = (int) Math.floor((x - camX) / UPP_X + W / 2.0 + 0.5); // SimBoardRenderer's pixel centres
                int py = (int) Math.floor(-(y - camY) / UPP_Y + H / 2.0 + 0.5);
                int p = before.getRGB(px, py), q = after.getRGB(px, py);
                double db = (p & 0xFF) - (q & 0xFF), dg = ((p >> 8) & 0xFF) - ((q >> 8) & 0xFF),
                        dr = ((p >> 16) & 0xFF) - ((q >> 16) & 0xFF);
                n++;
                changed += Math.sqrt(db * db + dg * dg + dr * dr) > COLOUR_DELTA ? 1 : 0;
            }
        }
        return (double) changed / n;
    }
}
