package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.DoseModel;
import org.openpnp.instancelabs.sim.PadcalScenes;
import org.openpnp.instancelabs.sim.SimBoardRenderer;
import org.openpnp.instancelabs.sim.SimNeedle;
import org.openpnp.instancelabs.sim.SimPaste;
import org.openpnp.instancelabs.sim.SimScene;
import org.openpnp.instancelabs.vision.BareSpotCheck;

/**
 * The ladder of the pad calibration (profile calibration design §7.4): dots per dose, pitch from the hop, insets,
 * rows and the empty row between doses, the reserved band, camera tiles of the bench frame, and what the frames
 * say: the bare look at a spot, the thread corridor on rendered SIM paste, the crops and a coupon off its place.
 */
public class PadLadderTest {
    /** The bench frame (1920 x 1080 at the Top camera's 0.0107 mm/px). */
    static final double UPP_X = 0.01068376, UPP_Y = 0.01072961;
    static final int W = 1920, H = 1080;
    static final double[] FOV = {W * UPP_X, H * UPP_Y};

    static PadCalibrationSettings settings() {
        return new PadCalibrationSettings();
    }

    @Test
    public void dotsPerDoseCountTheHopsOfTheCoupons() {
        PadCalibrationSettings s = settings();
        // §7.9's job: 136 quarter-turn dots and 68 half-turn dots on the coupons give the hops
        assertEquals(48, PadLadder.dotsFor(400, s, 135), "12 per screw angle, 4 angles");
        assertEquals(28, PadLadder.dotsFor(800, s, 67));
        assertEquals(28, PadLadder.dotsFor(1600, s, 33));
        // the by-hand ladder has no coupons: 61 dots give the 60 hops, rounded up to whole phase sets
        assertEquals(64, PadLadder.dotsFor(400, s, 0));
        assertEquals(62, PadLadder.dotsFor(800, s, 0));
        assertEquals(61, PadLadder.dotsFor(1600, s, 0));
        assertEquals(30, PadLadder.dotsFor(800, s, 31), "61 - 31 = 30 hops still needed");
    }

    @Test
    public void pitchFollowsTheHopBetweenItsLimits() {
        assertEquals(2.0, PadLadder.pitch(2.0, 1.0), 1e-12, "the group's own pad-to-pad move");
        assertEquals(2.5, PadLadder.pitch(4.0, 1.16), 1e-12, "at most 2.5 mm");
        assertEquals(1.46, PadLadder.pitch(1.0, 1.16), 1e-12, "dot edges 0.3 mm apart for the camera");
        assertEquals(2.5, PadLadder.pitch(Double.NaN, 1.0), 1e-12, "no hop: the widest pitch");
        assertEquals(2.5, PadLadder.rowPitch(2.5, 1.345), 1e-12);
        assertEquals(1.845, PadLadder.rowPitch(1.5, 1.345), 1e-12, "rows d95 + 0.5 mm apart at least");
        assertEquals(1.08, PadLadder.insetMm(1.16), 1e-12);
    }

    static CalibrationArea area() {
        CalibrationArea a = new CalibrationArea();
        a.x0Mm = 290.0;
        a.y0Mm = 240.0;
        a.widthMm = 40.0;
        a.heightMm = 20.0;
        return a;
    }

    @Test
    public void laysTheDosesInSerpentineRowsInTheNextBand() {
        CalibrationArea a = area();
        a.usedMm = 3;
        List<PadLadder.Dose> doses = Arrays.asList(new PadLadder.Dose(800, 28, 2.5, DoseModel.dHiMm(800), "half"),
                new PadLadder.Dose(400, 48, 2.0, DoseModel.dHiMm(400), "quarter"));
        PadLadder.Layout l = PadLadder.layout(a, doses);
        assertNull(l.problem);
        assertEquals(2, l.blocks.size());
        DoseBlocksRequest.Block half = l.blocks.get(0);
        DoseBlocksRequest.Block quarter = l.blocks.get(1);
        assertEquals(28, half.xy.size());
        assertEquals(48, quarter.xy.size());
        assertEquals("blank", half.surfaceName);
        assertEquals(1.25, half.matchRadiusMm, 1e-12);
        assertEquals(1.0, quarter.matchRadiusMm, 1e-12);
        double halfInset = PadLadder.insetMm(DoseModel.dHiMm(800));
        // the first row from the used line plus the inset; 16 columns of 2.5 mm fit 40 - 2 x 1.08 mm
        assertEquals(243 + halfInset, half.xy.get(0)[1], 1e-9);
        assertEquals(half.xy.get(0)[1], half.xy.get(15)[1], 1e-9, "16 dots in the first row");
        assertEquals(half.xy.get(15)[0], half.xy.get(16)[0], 1e-9, "serpentine: the next row starts above the end");
        assertEquals(2.5, half.xy.get(16)[1] - half.xy.get(15)[1], 1e-9);
        assertEquals(310, (half.xy.get(0)[0] + half.xy.get(15)[0]) / 2, 1e-9, "the rows centred in the area");
        for (double[] p : half.xy) {
            assertTrue(p[0] >= 290 + halfInset - 1e-9 && p[0] <= 330 - halfInset + 1e-9, Arrays.toString(p));
        }
        // an empty row between the doses
        double lastHalf = half.xy.get(27)[1];
        assertEquals(lastHalf + 2 * 2.5, quarter.xy.get(0)[1], 1e-9);
        double lastQuarter = quarter.xy.get(47)[1];
        assertEquals(lastQuarter + PadLadder.insetMm(DoseModel.dHiMm(400)) - 243, l.reservedMm, 1e-9);
        assertEquals(243, l.y0, 1e-9);
        assertEquals(243 + l.reservedMm, l.y1, 1e-9);
        // hops along a row are the pitch
        assertEquals(2.0, quarter.xy.get(1)[0] - quarter.xy.get(0)[0], 1e-9);
    }

    @Test
    public void saysWhenTheAreaIsUsedUp() {
        CalibrationArea a = area();
        a.usedMm = 16;
        PadLadder.Layout l = PadLadder.layout(a, Collections.singletonList(new PadLadder.Dose(800, 62, 2.5,
                DoseModel.dHiMm(800), "half")));
        assertTrue(l.problem.startsWith("The calibration area has 4.0 mm left and this calibration needs "),
                l.problem);
        assertTrue(l.problem.endsWith("mm: wipe the blank and press “Blank wiped” (Calibrate › Pad calibration)."));
    }

    @Test
    public void tilesKeepEveryDotInsideOneFrame() {
        PadLadder.Layout l = PadLadder.layout(area(), Arrays.asList(new PadLadder.Dose(800, 28, 2.5,
                DoseModel.dHiMm(800), "half"), new PadLadder.Dose(400, 48, 2.0, DoseModel.dHiMm(400), "quarter")));
        List<double[]> all = new ArrayList<>();
        for (DoseBlocksRequest.Block b : l.blocks) {
            all.addAll(b.xy);
        }
        List<PadLadder.Tile> tiles = PadLadder.tiles(all, FOV, PadLadder.TILE_INSET_MM);
        assertTrue(tiles.size() <= 8, "76 dots in a few frames: " + tiles.size());
        Set<Integer> seen = new HashSet<>();
        for (PadLadder.Tile t : tiles) {
            for (int i : t.dots) {
                assertTrue(seen.add(i), "every dot in one frame");
                double[] p = all.get(i);
                assertTrue(Math.abs(p[0] - t.cx) <= FOV[0] / 2 - PadLadder.TILE_INSET_MM + 1e-9);
                assertTrue(Math.abs(p[1] - t.cy) <= FOV[1] / 2 - PadLadder.TILE_INSET_MM + 1e-9);
            }
        }
        assertEquals(all.size(), seen.size());
        // without a field of view: one frame per dot
        assertEquals(all.size(), PadLadder.tiles(all, null, PadLadder.TILE_INSET_MM).size());
    }

    // ------------------------------------------------------------------------------------------
    // Frames
    // ------------------------------------------------------------------------------------------

    /** The §11.3 SIM paste on a flat bench blank, threads on every hop after a small dot. */
    static SimScene scene() {
        SimScene s = new SimScene();
        s.boardFile = "toy.board.xml";
        s.seat = SimScene.seat(0, 0, 0, 1, 1, 0);
        s.blankSurface = new SimScene.Plane(5.62, 0, 0);
        PadcalScenes.padcal(s, "display");
        SimScene.Paste p = s.paste;
        p.cv = 0;
        p.ripple = 0;
        p.oozeFirst = 0;
        p.refillDip = 0;
        p.landingSdMm = 0;
        p.offsetSteps = 0;
        return s;
    }

    static DotImager.Frame frame(SimScene s, double x, double y, List<SimPaste.Deposit> deposits) {
        return new DotImager.Frame(SimBoardRenderer.render(s, null, Collections.emptyList(), x, y, W, H, UPP_X, UPP_Y,
                deposits), UPP_X, UPP_Y, x, y);
    }

    @Test
    public void theCorridorTellsAThreadFromALift() throws Exception {
        SimScene s = scene();
        s.paste.mRevMg = 1.2; // quarter-turn dots of 0.30 mg: a thread on every hop
        s.paste.threadShare = 1;
        SimPaste p = new SimPaste(() -> s, SimPaste.Store.forScene(s), () -> 0L, () -> 0.0);
        SimNeedle n = new SimNeedle(p, s.paste.n2OffsetMm);
        n.pressurize();
        n.row(300, 240, 2.0, 2, 5.62, 400);
        n.travel(306, 240); // the lift breaks the thread
        n.dot(306, 240, 5.62, 400);
        n.vent();
        List<SimPaste.Deposit> all = p.deposits("blank");
        List<SimPaste.Deposit> dots = SimNeedle.dots(p, "blank");
        assertEquals(3, dots.size());
        // the pair of frames is split across two tiles: each sample is read where it lies
        DotImager.Frame b1 = frame(s, 296, 240, Collections.emptyList());
        DotImager.Frame a1 = frame(s, 296, 240, all);
        DotImager.Frame b2 = frame(s, 310, 240, Collections.emptyList());
        DotImager.Frame a2 = frame(s, 310, 240, all);
        List<PadLadder.FramePair> pairs = Arrays.asList(new PadLadder.FramePair(b1, a1), new PadLadder.FramePair(b2, a2));
        SimPaste.Deposit d0 = dots.get(0), d1 = dots.get(1), d2 = dots.get(2);
        double threaded = PadLadder.corridorShare(pairs, d0.x, d0.y, d0.diameterMm() / 2, d1.x, d1.y,
                d1.diameterMm() / 2);
        assertTrue(threaded > PadLadder.THREAD_SHARE, "the hop 1 → 2 drew a thread: " + threaded);
        double lifted = PadLadder.corridorShare(pairs, d1.x, d1.y, d1.diameterMm() / 2, d2.x, d2.y,
                d2.diameterMm() / 2);
        assertTrue(lifted < 0.02, "no thread across a lift: " + lifted);
        assertTrue(Double.isNaN(PadLadder.corridorShare(pairs, 300, 240, 1, 300.5, 240, 1)), "overlapping dots");
    }

    @Test
    public void theBareLookAtASpotOfATileAndItsCrop() throws Exception {
        SimScene s = scene();
        SimPaste p = new SimPaste(() -> s, SimPaste.Store.forScene(s), () -> 0L, () -> 0.0);
        SimNeedle n = new SimNeedle(p, s.paste.n2OffsetMm);
        n.pressurize();
        n.dot(303, 241, 5.62, 800);
        n.vent();
        SimPaste.Deposit d = SimNeedle.dots(p, "blank").get(0);
        DotImager.Frame f = frame(s, 300, 240, p.deposits(null));
        BareSpotCheck.Settings set = PadLadder.spotSettings(DoseModel.dHiMm(800));
        BareSpotCheck.Result paste = PadLadder.bareAt(f, d.x, d.y, set);
        assertNotNull(paste);
        assertFalse(paste.bare, "an old dot under the spot: " + paste);
        BareSpotCheck.Result clean = PadLadder.bareAt(f, 297, 239, set);
        assertTrue(clean.bare, clean.toString());
        assertNull(PadLadder.bareAt(f, 300 + FOV[0] / 2 - 0.5, 240, set), "the look's ring leaves the frame");
        byte[] png = PadLadder.cropPng(f, d.x, d.y);
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        assertEquals(PadLadder.CROP_PX, img.getWidth());
        assertEquals(PadLadder.CROP_PX, img.getHeight());
    }

    @Test
    public void aCouponOffItsPlaceIsMeasuredAtItsEdge() {
        SimScene s = scene();
        SimScene.Coupon k = s.coupon("coupon1");
        double[] nominal = k.rect.clone();
        k.rect = new double[] {nominal[0] + 1.5, nominal[1], nominal[2] + 1.5, nominal[3]};
        double[] blank = {206, 25, 0};
        // the bottom-left look of a 136-dot quarter grid (32 x 14 mm about the place), out by the margin less its ring
        double out = 3 - PadLadder.couponCornerSettings().ringOuterMm;
        DotImager.Frame f = frame(s, 334 - out, 48 - out, Collections.emptyList());
        // the grid has 4.0 mm to the nominal edge where it needs 3.5: the coupon may lie 0.5 mm off, not 1.5
        assertEquals(1.5, PadLadder.couponEdgeShift(f, nominal, blank), 0.06);
        k.rect = nominal;
        DotImager.Frame ok = frame(s, 334 - out, 48 - out, Collections.emptyList());
        assertTrue(BareSpotCheck.check(ok.image, ok.mmPerPxX, ok.mmPerPxY, PadLadder.couponCornerSettings()).bare,
                "the coupon on its place: the margin lies on it");
        assertTrue(!(PadLadder.couponEdgeShift(ok, nominal, blank) > 0.2), "its edge where it should be");
    }
}
