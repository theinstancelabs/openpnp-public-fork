package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.vision.TipClean;

/**
 * BR-94: the needle's end from below and K by parallax as one job (Process's np1.py, parallax.py, parallax_k.py and
 * tipclean.py), on a drawn bottom camera ({@link FromBelowScene}) that follows the job's G-code: the end at every height,
 * its tilt and K come out as drawn; the moves are np1.py's; every fault the checks are for is refused with its kind,
 * what it offers and its picture, and the head ends at travel Z.
 */
public class NeedleEndMeasureTest {
    /** The head follows the lines' raw X/Y/Z; a frame is the scene with the head there. */
    static class Camera implements NeedleEndMeasure.Io {
        final FromBelowScene scene;
        double x = 100, y = 100, z = 31.5;
        final List<String> lines = new ArrayList<>();
        /** The head's raw (x, y, z) at each frame. */
        final List<double[]> frames = new ArrayList<>();
        final List<String> phases = new ArrayList<>();
        int framesDone, framesPlanned;
        static final Pattern W = Pattern.compile("([XYZ])(-?\\d+(?:\\.\\d+)?)");

        Camera(FromBelowScene scene) {
            this.scene = scene;
        }

        @Override
        public void send(String line) {
            lines.add(line);
            if (line.startsWith("G1")) {
                Matcher m = W.matcher(line);
                while (m.find()) {
                    double v = Double.parseDouble(m.group(2));
                    switch (m.group(1)) {
                        case "X":
                            x = v;
                            break;
                        case "Y":
                            y = v;
                            break;
                        default:
                            z = v;
                    }
                }
            }
        }

        @Override
        public DotImager.Frame capture() throws Exception {
            frames.add(new double[] {x, y, z});
            return new DotImager.Frame(scene.render(x, y, z), 0.0667, 0.0656, scene.camX, scene.camY);
        }

        @Override
        public void progress(String phase, int done, int planned) {
            if (phase != null) {
                phases.add(phase);
            }
            framesDone = done;
            framesPlanned = planned;
        }
    }

    /** The plan with the nominal end {@code nominal} from the head: B puts it over the camera. */
    static NeedleEndMeasure.Plan plan(FromBelowScene sc, double nominalDx, double nominalDy) {
        NeedleEndMeasure.Plan p = new NeedleEndMeasure.Plan();
        p.s = new DispenseConfig.NeedleEndSettings();
        p.travelZ = 31.5;
        p.nominalDx = nominalDx;
        p.nominalDy = nominalDy;
        p.needleX = sc.camX - nominalDx;
        p.needleY = sc.camY - nominalDy;
        p.n1X = sc.camX - sc.n1Dx;
        p.n1Y = sc.camY - sc.n1Dy;
        p.clean = new TipClean.Limits();
        p.setupKey = "plastic-25g-taper";
        return p;
    }

    /**
     * A clean straight tip 0.3 mm off its nominal end, tilting as Process's 25G: the end at each height to a hundredth of
     * a mm, the tilt, K as drawn, every check's number, the clean check at raw 40.4, and a picture per height.
     */
    @Test
    public void theEndItsTiltAndKComeOutAsDrawn() throws Exception {
        FromBelowScene sc = new FromBelowScene(1);
        Camera cam = new Camera(sc);
        NeedleEndMeasure.Plan p = plan(sc, sc.endDx - 0.3, sc.endDy + 0.2);
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, p);
        assertTrue(r.ok, r.why);
        DispenseConfig.NeedleEnd e = r.end;
        assertEquals(4, e.heights.size());
        for (DispenseConfig.EndHeight h : e.heights) {
            double[] want = sc.end(h.rawZ);
            assertEquals(want[0], h.dxMm, 0.01, "dx at " + h.rawZ);
            assertEquals(want[1], h.dyMm, 0.01, "dy at " + h.rawZ);
            assertEquals(sc.needleScale(h.rawZ), h.pxPerMm, 0.02 * h.pxPerMm, "k at " + h.rawZ);
            assertTrue(h.residualPx < 0.3 && h.driftPx < 0.3, h.residualPx + " " + h.driftPx);
            assertTrue(h.peakToSd > 3, String.valueOf(h.peakToSd));
        }
        assertEquals(0.019, e.tiltXMmPerRawMm, 0.003);
        assertEquals(-0.076, e.tiltYMmPerRawMm, 0.003);
        double[] at = e.at(43.0);
        assertEquals(sc.end(43.0)[0], at[0], 0.015, "extrapolated to a press height");
        assertEquals(sc.end(43.0)[1], at[1], 0.015);
        assertEquals(65.0, e.kParallax, 0.1);
        assertTrue(e.errKMm < 0.1, "K's spread " + e.errKMm);
        assertTrue(e.errXyMm < 0.02 && e.residualPx < 0.3 && e.driftPx < 0.3, e.errXyMm + " " + e.residualPx);
        assertEquals(3, e.n1.size());
        for (DispenseConfig.EndHeight h : e.n1) {
            assertNull(h.dxMm);
            assertEquals(sc.n1Scale(h.rawZ), h.pxPerMm, 0.01 * h.pxPerMm, "N1's k at " + h.rawZ);
        }
        assertNotNull(e.clean);
        assertTrue(e.clean.ok, e.clean.why);
        assertEquals(40.4, e.clean.rawZ, 1e-9);
        assertTrue(e.clean.spread < 0.35 && e.clean.red >= 0.45 && e.clean.peak >= 60, e.clean.spread + " "
                + e.clean.red + " " + e.clean.peak);
        assertEquals("plastic-25g-taper", e.setupKey);
        assertEquals(7, r.pictures.size(), "each height's centre frame");
        assertEquals(42, cam.framesDone);
        assertEquals(42, cam.framesPlanned);
        assertTrue(cam.phases.get(0).startsWith("needle end: N1 at raw Z 19.000 (1 of 3)"), cam.phases.toString());
        assertEquals("needle end: the needle at raw Z 42.000 (4 of 4)", cam.phases.get(6));
    }

    static final Pattern XY = Pattern.compile("G1 X(-?[\\d.]+) Y(-?[\\d.]+) F(\\d+)");

    /**
     * np1.py's moves, after the valve and the pump off: each tool's X/Y only at travel Z, B approached from (−0.5,
     * −0.5); each frame's point approached from (−0.5, −0.5) after M400 and the dwell; the heights as set; travel Z at
     * the end.
     */
    @Test
    public void theMovesAreNp1s() throws Exception {
        FromBelowScene sc = new FromBelowScene(2);
        Camera cam = new Camera(sc);
        NeedleEndMeasure.Plan p = plan(sc, sc.endDx, sc.endDy);
        assertTrue(NeedleEndMeasure.measure(cam, p).ok);
        assertEquals("M107 P3", cam.lines.get(0), "the valve off first");
        assertEquals("M107 P2", cam.lines.get(1), "then the pump, before anything moves");
        double z = 31.5;
        for (int i = 0; i < cam.lines.size(); i++) {
            String l = cam.lines.get(i);
            Matcher m = Pattern.compile("G1 Z(-?[\\d.]+)").matcher(l);
            if (m.find()) {
                z = Double.parseDouble(m.group(1));
            }
            Matcher xy = XY.matcher(l);
            if (xy.matches() && Integer.parseInt(xy.group(3)) == 9000) {
                assertEquals(31.5, z, 1e-9, "X/Y between the tools at travel Z: " + l);
            }
        }
        // every frame: its point approached from (−0.5, −0.5), M400, the dwell, at a configured height
        int frame = 0;
        for (int i = 0; i < cam.lines.size(); i++) {
            if (cam.lines.get(i).equals("G4 P300")) {
                Matcher to = XY.matcher(cam.lines.get(i - 2)), from = XY.matcher(cam.lines.get(i - 3));
                assertTrue(to.matches() && from.matches() && cam.lines.get(i - 1).equals("M400"), cam.lines.subList(i
                        - 3, i + 1).toString());
                assertEquals(Double.parseDouble(to.group(1)) - 0.5, Double.parseDouble(from.group(1)), 1e-6);
                assertEquals(Double.parseDouble(to.group(2)) - 0.5, Double.parseDouble(from.group(2)), 1e-6);
                double[] h = cam.frames.get(frame++);
                assertTrue(p.s.n1RawZ.contains(h[2]) || p.s.needleRawZ.contains(h[2]), "height " + h[2]);
            }
        }
        assertEquals(42, frame);
        // the steps: N1 ±5 mm, the needle ±1 mm around its B
        assertEquals(p.n1X + 5, cam.frames.get(1)[0], 1e-6);
        assertEquals(p.needleY - 1, cam.frames.get(22)[1], 1e-6);
        assertEquals("G1 Z31.500 F1200", cam.lines.get(cam.lines.size() - 2));
        assertEquals("M400", cam.lines.get(cam.lines.size() - 1));
    }

    static void refused(NeedleEndMeasure.Result r, String code, String why, Camera cam) {
        assertFalse(r.ok);
        assertEquals(code, r.code, r.why);
        assertTrue(r.why.contains(why), r.why);
        assertEquals(NeedleEndMeasure.PASTE.equals(code) ? NeedleEndMeasure.WIPE_THEN_MEASURE_AGAIN
                : NeedleEndMeasure.MEASURE_AGAIN, r.next);
        assertNull(r.end);
        assertEquals(1, r.pictures.size(), "the frame it failed in, the region searched drawn");
        assertEquals("G1 Z31.500 F1200", cam.lines.get(cam.lines.size() - 2), "back at travel Z");
    }

    /** The light off: N1's template follows only noise, refused at N1's first height (a). */
    @Test
    public void theLightOffIsTheEndNotFound() throws Exception {
        FromBelowScene sc = new FromBelowScene(3);
        sc.light = false;
        Camera cam = new Camera(sc);
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, plan(sc, sc.endDx, sc.endDy));
        refused(r, NeedleEndMeasure.NOT_FOUND, "N1 at raw Z 19.000", cam);
        assertEquals(6, cam.frames.size(), "no further height after the refusal");
    }

    /** No end in view, only a bright mark on the camera's window: it does not move with the head (b). */
    @Test
    public void aMarkThatStaysPutIsNotTheEnd() throws Exception {
        FromBelowScene sc = new FromBelowScene(4);
        sc.needle = false;
        sc.mark = true;
        Camera cam = new Camera(sc);
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, plan(sc, sc.endDx, sc.endDy));
        refused(r, NeedleEndMeasure.NOT_FOUND, "did not move with the head", cam);
    }

    /**
     * No end and no mark: the brightest noise in the window is some 6 times its sd (check a's 3 lets it through), but
     * it does not follow the head's steps (b).
     */
    @Test
    public void noEndIsTheEndNotFound() throws Exception {
        FromBelowScene sc = new FromBelowScene(5);
        sc.needle = false;
        Camera cam = new Camera(sc);
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, plan(sc, sc.endDx, sc.endDy));
        refused(r, NeedleEndMeasure.NOT_FOUND, "the end was not found (light or focus): the needle at raw Z 36.000",
                cam);
    }

    /** A loose tip creeping 0.3 px a frame: the centre frames 1.5 px apart (c). */
    @Test
    public void aCreepingEndIsRefusedByItsDrift() throws Exception {
        FromBelowScene sc = new FromBelowScene(6);
        sc.creepPx = 0.3;
        Camera cam = new Camera(sc);
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, plan(sc, sc.endDx, sc.endDy));
        refused(r, NeedleEndMeasure.NOT_FOUND, "the first and last centre frames are", cam);
    }

    /** The end 2.5 mm from where the tip setup puts it (e). */
    @Test
    public void anEndFarFromTheNominalIsRefused() throws Exception {
        FromBelowScene sc = new FromBelowScene(7);
        Camera cam = new Camera(sc);
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, plan(sc, sc.endDx - 2.5, sc.endDy));
        refused(r, NeedleEndMeasure.NOT_FOUND, "from where the tip setup puts it (at most 2.0)", cam);
    }

    /** The needle's scale changes 10 % faster than N1's: K is not one number over N1's heights (d). */
    @Test
    public void scalesThatDisagreeSpreadK() throws Exception {
        FromBelowScene sc = new FromBelowScene(8);
        sc.needleScale = 1.1;
        Camera cam = new Camera(sc);
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, plan(sc, sc.endDx, sc.endDy));
        refused(r, NeedleEndMeasure.NOT_FOUND, "K by parallax spreads", cam);
    }

    /**
     * Paste beside the end (tipclean.py's grey blob): refused as paste on the end (spread), wipe and measure again;
     * the clean check's numbers and its failed check are in the result.
     */
    @Test
    public void pasteOnTheEndIsRefusedAsPaste() throws Exception {
        FromBelowScene sc = new FromBelowScene(9);
        sc.paste = true;
        Camera cam = new Camera(sc);
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, plan(sc, sc.endDx, sc.endDy));
        refused(r, NeedleEndMeasure.PASTE, "paste on the end: spread", cam);
        assertTrue(r.why.endsWith("wipe the tip, then measure again"), r.why);
        assertEquals("spread", r.clean.failed);
        assertTrue(r.clean.spread > 0.35, String.valueOf(r.clean.spread));
        assertEquals(40.4, r.clean.rawZ, 1e-9);
    }

    /** A room-lit frame (the ring's glow not white): the clean check's light, the end not found (light or focus). */
    @Test
    public void aRingThatIsNotWhiteIsTheLight() throws Exception {
        FromBelowScene sc = new FromBelowScene(10);
        sc.rimRg = 0.6;
        Camera cam = new Camera(sc);
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, plan(sc, sc.endDx, sc.endDy));
        refused(r, NeedleEndMeasure.NOT_FOUND, "the end was not found (light or focus): light", cam);
        assertEquals("light", r.clean.failed);
    }

    /**
     * The end on record holds for the needle it was measured on and the camera's calibration then: a needle change (a
     * mount), another tip setup, the camera's location or its centre pixel moved: null.
     */
    @Test
    public void theEndHoldsForItsNeedleAndTheCamerasCalibrationOnly() {
        DispenseConfig c = new DispenseConfig();
        c.needleChangedAtMs = 7L;
        DispenseConfig.NeedleEnd e = new DispenseConfig.NeedleEnd();
        e.needleSinceMs = 7L;
        e.setupKey = c.tip.key;
        e.cameraCalibration = new DispenseConfig.CameraCalibration();
        e.cameraCalibration.x = 217.129;
        e.cameraCalibration.y = 152.319;
        e.cameraCalibration.z = 25.2;
        e.cameraCalibration.centrePx = new ArrayList<>(c.needleEndMeasure.centrePx);
        c.needleEnd = e;
        double[] cam = {217.129, 152.319, 25.2};
        assertTrue(NeedleEndMeasure.current(c, cam) == e);
        assertNull(NeedleEndMeasure.current(c, null), "no camera");
        assertNull(NeedleEndMeasure.current(c, new double[] {217.229, 152.319, 25.2}), "a new camera calibration");
        c.needleEndMeasure.centrePx.set(1, 544.2);
        assertNull(NeedleEndMeasure.current(c, cam), "another centre pixel");
        c.needleEndMeasure.centrePx.set(1, 534.8);
        c.needleChangedAtMs = 8L;
        assertNull(NeedleEndMeasure.current(c, cam), "a needle change");
        c.needleChangedAtMs = 7L;
        e.setupKey = "another";
        assertNull(NeedleEndMeasure.current(c, cam), "another tip setup");
    }

    /** A frame that fails mid-job: the head goes up first (Z only), then the failure is thrown; a STOP sends nothing. */
    @Test
    public void aFailureLiftsTheHeadAndAStopSendsNothingMore() throws Exception {
        FromBelowScene sc = new FromBelowScene(11);
        Camera broken = new Camera(sc) {
            @Override
            public DotImager.Frame capture() {
                throw new IllegalStateException("camera gone");
            }
        };
        assertThrows(IllegalStateException.class, () -> NeedleEndMeasure.measure(broken, plan(sc, sc.endDx,
                sc.endDy)));
        assertEquals("G1 Z31.500 F1200", broken.lines.get(broken.lines.size() - 2));
        Camera stopped = new Camera(sc) {
            @Override
            public DotImager.Frame capture() throws DispenseExecutor.AbortedException {
                throw new DispenseExecutor.AbortedException("STOP");
            }
        };
        assertThrows(DispenseExecutor.AbortedException.class, () -> NeedleEndMeasure.measure(stopped, plan(sc,
                sc.endDx, sc.endDy)));
        assertEquals("G4 P300", stopped.lines.get(stopped.lines.size() - 1), "nothing after the STOP");
    }
}
